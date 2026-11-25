package com.laughing.telephotos.data.telegram

import android.content.Context
import android.os.Build
import com.laughing.telephotos.BuildConfig
import com.laughing.telephotos.core.CacheCleanupManager
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.MediaStoreScanner
import com.laughing.telephotos.core.NetworkAlertCoordinator
import com.laughing.telephotos.core.NetworkInterruptedException
import com.laughing.telephotos.core.NetworkOperationType
import com.laughing.telephotos.core.NetworkRetryPolicy
import com.laughing.telephotos.core.NetworkRetryTracker
import com.laughing.telephotos.core.RetryDecision
import com.laughing.telephotos.core.SettingsPreferences
import com.laughing.telephotos.core.StalledTransferException
import com.laughing.telephotos.core.TelegramCaptionMetadata
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.MediaType
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

sealed interface MessageSendEvent {
  val oldMessageId: Long
  data class Succeeded(override val oldMessageId: Long, val message: TdApi.Message) : MessageSendEvent
  data class Failed(override val oldMessageId: Long, val error: TdApi.Error?) : MessageSendEvent
}

class PendingUploadInterruptedException(
  val pendingMessageId: Long,
  message: String,
  cause: Throwable? = null,
) : java.io.IOException(message, cause)

private class StaleOrMissingTelegramFileException(
  message: String,
) : IllegalStateException(message)

/**
 * Persistent foreground-reusable & self-recovering TDLib manager:
 * - Keeps one app-wide TDLib client alive while the app is in foreground so actions reuse the session.
 * - Uses Mutex + single-flight CompletableDeferred to prevent duplicate clients or init races.
 * - Tracks all in-flight TDLib requests via ConcurrentHashMap with timeouts.
 * - Routes every TdApi.Function through TelegramPolicyGuard.assertAllowedFunction.
 * - Keeps the session idle/alive during FLOOD_WAIT and persists cooldown expiry without reconnecting.
 * - Runs lightweight OptimizeStorage + Close() only when backgrounded (with no active workers/transfers),
 *   on explicit logout, or when switching API credentials.
 */
class TdLibManager(
  private val context: Context,
  private val settingsPreferences: SettingsPreferences,
  private val networkAlertCoordinator: NetworkAlertCoordinator = NetworkAlertCoordinator(),
) {

  private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lifecycleMutex = Mutex()
  private val activeTransfers = AtomicInteger(0)
  private val activeWorkers = AtomicInteger(0)
  private val requestIdGenerator = AtomicLong(1L)
  private val inFlightRequests = ConcurrentHashMap<Long, CompletableDeferred<TdApi.Object>>()

  private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Uninitialized)
  val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

  private val _connectionStatus = MutableStateFlow(TelegramConnectionStatus.DISCONNECTED_IDLE)
  val connectionStatus: StateFlow<TelegramConnectionStatus> = _connectionStatus.asStateFlow()

  private val _lifecycleState = MutableStateFlow(TdLibLifecycleState.IDLE)
  val lifecycleState: StateFlow<TdLibLifecycleState> = _lifecycleState.asStateFlow()

  private val _floodWaitState = MutableStateFlow<FloodWaitState?>(null)
  val floodWaitState: StateFlow<FloodWaitState?> = _floodWaitState.asStateFlow()

  private val fileUpdates = MutableSharedFlow<TdApi.File>(extraBufferCapacity = 64)
  private val messageSendEvents = MutableSharedFlow<MessageSendEvent>(extraBufferCapacity = 64)
  private val lastTopicMutationMs = AtomicLong(0L)
  private val topicInitMutexes = ConcurrentHashMap<Long, Mutex>()

  private fun topicMutexFor(chatId: Long): Mutex =
    topicInitMutexes.computeIfAbsent(chatId) { Mutex() }

  @Volatile private var client: Client? = null
  @Volatile private var cachedUserId: Long = 0L
  @Volatile private var lastPhoneEntered: String = "+84"
  @Volatile private var activeApiId: Int = 0
  @Volatile private var activeApiHash: String = ""
  @Volatile private var waitingForParameters: Boolean = false
  @Volatile private var closedDeferred: CompletableDeferred<Unit>? = null
  @Volatile private var tdConnectionState: TdApi.ConnectionState? = null
  @Volatile private var activeConnectDeferred: CompletableDeferred<Result<TelegramAuthState.Ready>>? = null
  @Volatile private var isIntentionalShutdown: Boolean = false
  @Volatile private var appInForeground: Boolean = false
  @Volatile private var closeWhenIdlePending: Boolean = false

  fun isAppInForeground(): Boolean = appInForeground

  fun isSessionReady(): Boolean =
    client != null && _authState.value is TelegramAuthState.Ready &&
      tdConnectionState !is TdApi.ConnectionStateWaitingForNetwork

  private fun isFloodWaitCurrentlyActive(): Boolean {
    val active = _floodWaitState.value ?: return false
    return active.unlockAtEpochMs > System.currentTimeMillis()
  }

  private fun refreshSteadyStatus() {
    val currentFlood = _floodWaitState.value
    if (currentFlood != null && currentFlood.unlockAtEpochMs > System.currentTimeMillis()) {
      _connectionStatus.value = TelegramConnectionStatus.FLOOD_WAIT
      _lifecycleState.value = TdLibLifecycleState.FLOOD_WAIT
      return
    }
    when {
      activeWorkers.get() > 0 || activeTransfers.get() > 0 -> {
        _connectionStatus.value = TelegramConnectionStatus.SYNCING
        if (_authState.value is TelegramAuthState.Ready) {
          _lifecycleState.value = TdLibLifecycleState.READY
        }
      }
      client != null && _authState.value is TelegramAuthState.Ready -> {
        _connectionStatus.value = TelegramConnectionStatus.READY
        _lifecycleState.value = TdLibLifecycleState.READY
      }
      client == null -> {
        _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
        _lifecycleState.value = TdLibLifecycleState.IDLE
      }
    }
  }

  /**
   * Called by TelePhotosApplication when the app enters the foreground.
   * Restores any persisted FLOOD_WAIT state and proactively warms up the reusable session
   * if the user is already authenticated.
   */
  suspend fun onAppForegrounded() {
    appInForeground = true
    closeWhenIdlePending = false
    val settings = settingsPreferences.getCurrentSettings()
    val now = System.currentTimeMillis()
    if (settings.floodWaitUntilEpochMs > now) {
      _floodWaitState.value = FloodWaitState(
        retryAfterSeconds = settings.floodWaitRetryAfterSec.coerceAtLeast(1),
        unlockAtEpochMs = settings.floodWaitUntilEpochMs,
        reason = settings.floodWaitReason ?: "Telegram đang giới hạn tốc độ",
      )
      _connectionStatus.value = TelegramConnectionStatus.FLOOD_WAIT
      _lifecycleState.value = TdLibLifecycleState.FLOOD_WAIT
    } else if (settings.floodWaitUntilEpochMs > 0L) {
      settingsPreferences.clearFloodWait()
      _floodWaitState.value = null
    }

    if (settings.isTelegramLoggedIn &&
      NetworkRetryPolicy.isNetworkAvailable(context) &&
      !isSessionReady()
    ) {
      runCatching { ensureConnected(forceReconnect = false) }
    } else {
      refreshSteadyStatus()
    }
  }

  /**
   * Called by TelePhotosApplication when all non-config-changing activities stop (app backgrounded).
   * Closes TDLib cleanly once any active transfers/workers finish.
   */
  suspend fun onAppBackgrounded() {
    appInForeground = false
    if (activeTransfers.get() > 0 || activeWorkers.get() > 0) {
      closeWhenIdlePending = true
      return
    }
    closeConnectionIdle(preserveWaitApiState = false)
  }

  /**
   * Initializes TDLib strictly on-demand when UI/Auth or foreground session needs it.
   */
  suspend fun ensureInitializedOnDemand() {
    lifecycleMutex.withLock {
      ensureInitializedLocked(isReconnect = false)
    }
  }

  private suspend fun ensureInitializedLocked(isReconnect: Boolean) {
    if (client != null && _authState.value !is TelegramAuthState.WaitApiCredentials) {
      return
    }
    isIntentionalShutdown = false
    val settings = settingsPreferences.getCurrentSettings()
    val resolved = resolveCredentials(settings.customTelegramApiId, settings.customTelegramApiHash)
    if (resolved == null) {
      _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
      _lifecycleState.value = TdLibLifecycleState.AWAITING_CREDENTIALS
      _authState.value = TelegramAuthState.WaitApiCredentials()
      return
    }

    activeApiId = resolved.first
    activeApiHash = resolved.second
    _connectionStatus.value = if (isReconnect) {
      TelegramConnectionStatus.RECONNECTING
    } else {
      TelegramConnectionStatus.CONNECTING
    }
    _lifecycleState.value = if (isReconnect) {
      TdLibLifecycleState.RECONNECTING
    } else {
      TdLibLifecycleState.CONNECTING
    }
    createClientIfNeeded()
  }

  /**
   * Single-flight connection guarantee: concurrent callers join the same CompletableDeferred
   * so duplicate TDLib clients are never spawned. Automatically recovers if closed/uninitialized.
   */
  suspend fun ensureConnected(
    forceReconnect: Boolean = false,
    timeoutMs: Long = 20_000L,
  ): Result<TelegramAuthState.Ready> {
    if (!forceReconnect && isSessionReady()) {
      val currentReady = _authState.value as? TelegramAuthState.Ready
      if (currentReady != null) {
        refreshSteadyStatus()
        return Result.success(currentReady)
      }
    }

    val deferred = lifecycleMutex.withLock {
      if (!forceReconnect && isSessionReady()) {
        val currentReady = _authState.value as? TelegramAuthState.Ready
        if (currentReady != null) {
          refreshSteadyStatus()
          return Result.success(currentReady)
        }
      }

      val existing = activeConnectDeferred
      if (!forceReconnect && existing != null && !existing.isCompleted) {
        existing
      } else {
        if (forceReconnect) {
          closeClientLocked(
            preserveWaitApiState = false,
            closingStatus = TelegramConnectionStatus.RECONNECTING,
          )
        }
        val newDeferred = CompletableDeferred<Result<TelegramAuthState.Ready>>()
        activeConnectDeferred = newDeferred
        internalScope.launch {
          val result = awaitOrStartSessionInternal(
            isReconnect = forceReconnect,
            timeoutMs = timeoutMs,
          )
          newDeferred.complete(result)
          if (activeConnectDeferred === newDeferred) {
            activeConnectDeferred = null
          }
        }
        newDeferred
      }
    }

    val awaited = withTimeoutOrNull(timeoutMs + 1_000L) {
      deferred.await()
    }
    return awaited ?: Result.failure(
      NetworkInterruptedException("Hết thời gian kết nối tới Telegram. Vui lòng thử lại.")
    )
  }

  private suspend fun awaitOrStartSessionInternal(
    isReconnect: Boolean,
    timeoutMs: Long,
  ): Result<TelegramAuthState.Ready> {
    lifecycleMutex.withLock {
      ensureInitializedLocked(isReconnect = isReconnect)
    }

    val terminalState = withTimeoutOrNull(timeoutMs) {
      authState.first { state ->
        state is TelegramAuthState.Ready ||
          state is TelegramAuthState.WaitApiCredentials ||
          state is TelegramAuthState.WaitPhoneNumber ||
          state is TelegramAuthState.WaitCode ||
          state is TelegramAuthState.WaitPassword ||
          state is TelegramAuthState.Error
      }
    }

    return when (terminalState) {
      is TelegramAuthState.Ready -> {
        refreshSteadyStatus()
        Result.success(terminalState)
      }
      is TelegramAuthState.WaitApiCredentials -> {
        _lifecycleState.value = TdLibLifecycleState.AWAITING_CREDENTIALS
        _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
        Result.failure(IllegalStateException("Chưa cấu hình API ID / API Hash Telegram trong Setting."))
      }
      is TelegramAuthState.WaitPhoneNumber -> {
        _lifecycleState.value = TdLibLifecycleState.AWAITING_PHONE
        Result.failure(IllegalStateException("Phiên Telegram chưa đăng nhập. Vui lòng vào Setting để đăng nhập."))
      }
      is TelegramAuthState.WaitCode -> {
        _lifecycleState.value = TdLibLifecycleState.AWAITING_CODE
        Result.failure(IllegalStateException("Phiên Telegram đang chờ mã OTP. Vui lòng vào Setting để đăng nhập."))
      }
      is TelegramAuthState.WaitPassword -> {
        _lifecycleState.value = TdLibLifecycleState.AWAITING_PASSWORD
        Result.failure(IllegalStateException("Phiên Telegram đang chờ mật khẩu 2 bước. Vui lòng vào Setting để đăng nhập."))
      }
      is TelegramAuthState.Error -> {
        _lifecycleState.value = TdLibLifecycleState.ERROR
        Result.failure(IllegalStateException(terminalState.messageVi))
      }
      null, TelegramAuthState.Uninitialized -> {
        _lifecycleState.value = TdLibLifecycleState.ERROR
        Result.failure(NetworkInterruptedException("Hết thời gian kết nối tới Telegram. Vui lòng thử lại."))
      }
    }
  }

  fun beginTransfer(markSyncing: Boolean = true) {
    activeTransfers.incrementAndGet()
    if (markSyncing && !isFloodWaitCurrentlyActive()) {
      _connectionStatus.value = TelegramConnectionStatus.SYNCING
    }
  }

  suspend fun endTransfer() {
    val remaining = activeTransfers.decrementAndGet().coerceAtLeast(0)
    if (remaining == 0 && !appInForeground && closeWhenIdlePending && activeWorkers.get() == 0) {
      closeConnectionIdle(preserveWaitApiState = false)
    } else {
      refreshSteadyStatus()
    }
  }

  fun beginWorkLease() {
    activeWorkers.incrementAndGet()
    if (!isFloodWaitCurrentlyActive()) {
      _connectionStatus.value = TelegramConnectionStatus.SYNCING
    }
  }

  suspend fun endWorkLease() {
    val remaining = activeWorkers.decrementAndGet().coerceAtLeast(0)
    if (isSessionReady()) {
      optimizeStorageLightweight()
    }
    if (remaining == 0 && !appInForeground && activeTransfers.get() == 0) {
      closeConnectionIdle(preserveWaitApiState = false)
    } else {
      refreshSteadyStatus()
    }
  }

  /**
   * Executes a Telegram operation reusing the active TDLib session (connecting if needed).
   * Keeps the session alive while the app is in the foreground.
   */
  suspend fun <T> withActiveSession(
    markSyncing: Boolean = true,
    block: suspend () -> T,
  ): T {
    beginTransfer(markSyncing = markSyncing)
    return try {
      ensureConnected().getOrThrow()
      block()
    } finally {
      withContext(NonCancellable) {
        endTransfer()
      }
    }
  }

  /**
   * Lightweight cache & TDLib database trim safe to run without tearing down the active session.
   */
  suspend fun optimizeStorageLightweight() {
    val currentClient = client
    if (currentClient != null && _authState.value is TelegramAuthState.Ready) {
      runCatching {
        withTimeoutOrNull(1_200L) {
          sendFunction(
            TdApi.OptimizeStorage(
              400L * 1024L * 1024L,
              -1,
              -1,
              60 * 60 * 24,
              null,
              null,
              null,
              false,
              50,
            ),
            timeoutMs = 1_200L,
          )
        }
      }
    }
    runCatching { CacheCleanupManager.enforceCacheLimitLru(context) }
  }

  /**
   * Records a dynamic FLOOD_WAIT cooldown without closing or reconnecting the TDLib client.
   */
  suspend fun recordFloodWait(
    retryAfterSeconds: Int,
    unlockAtEpochMs: Long,
    reason: String,
  ) {
    val safeRetry = retryAfterSeconds.coerceAtLeast(1)
    _floodWaitState.value = FloodWaitState(
      retryAfterSeconds = safeRetry,
      unlockAtEpochMs = unlockAtEpochMs,
      reason = reason,
    )
    _connectionStatus.value = TelegramConnectionStatus.FLOOD_WAIT
    _lifecycleState.value = TdLibLifecycleState.FLOOD_WAIT
    settingsPreferences.setFloodWaitUntil(
      untilEpochMs = unlockAtEpochMs,
      reason = reason,
      retryAfterSeconds = safeRetry,
    )
  }

  suspend fun clearFloodWait() {
    _floodWaitState.value = null
    settingsPreferences.clearFloodWait()
    refreshSteadyStatus()
  }

  /**
   * Closes the TDLib client cleanly (background transition, logout, or credential switch).
   * Preserves local DataStore login profile so subsequent sessions resume without OTP.
   */
  suspend fun closeConnectionIdle(preserveWaitApiState: Boolean = false) {
    lifecycleMutex.withLock {
      closeClientLocked(
        preserveWaitApiState = preserveWaitApiState,
        closingStatus = TelegramConnectionStatus.DISCONNECTING,
      )
    }
  }

  private suspend fun closeClientLocked(
    preserveWaitApiState: Boolean,
    closingStatus: TelegramConnectionStatus,
  ) {
    val currentClient = client
    if (currentClient == null) {
      failAllInFlightRequests("Client is closed")
      if (!isFloodWaitCurrentlyActive()) {
        _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
        _lifecycleState.value = if (preserveWaitApiState) {
          TdLibLifecycleState.AWAITING_CREDENTIALS
        } else {
          TdLibLifecycleState.IDLE
        }
      }
      if (!preserveWaitApiState && _authState.value !is TelegramAuthState.WaitApiCredentials) {
        _authState.value = TelegramAuthState.Uninitialized
      }
      return
    }

    isIntentionalShutdown = true
    closeWhenIdlePending = false
    _connectionStatus.value = closingStatus

    if (_authState.value is TelegramAuthState.Ready) {
      runCatching {
        withTimeoutOrNull(800L) {
          sendSuspending(
            TdApi.OptimizeStorage(
              400L * 1024L * 1024L,
              -1,
              -1,
              60 * 60 * 24,
              null,
              null,
              null,
              false,
              50,
            ),
            timeoutMs = 800L,
          )
        }
      }
    }

    val deferred = CompletableDeferred<Unit>()
    closedDeferred = deferred
    runCatching {
      val closeFn = TdApi.Close()
      TelegramPolicyGuard.assertAllowedFunction(closeFn)
      currentClient.send(closeFn) { }
    }
    withTimeoutOrNull(1_200L) {
      deferred.await()
    }
    closedDeferred = null
    client = null
    waitingForParameters = false
    failAllInFlightRequests("Client is closed")

    if (!preserveWaitApiState) {
      _authState.value = TelegramAuthState.Uninitialized
    }
    if (isFloodWaitCurrentlyActive()) {
      _connectionStatus.value = TelegramConnectionStatus.FLOOD_WAIT
      _lifecycleState.value = TdLibLifecycleState.FLOOD_WAIT
    } else {
      _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
      _lifecycleState.value = if (preserveWaitApiState) {
        TdLibLifecycleState.AWAITING_CREDENTIALS
      } else {
        TdLibLifecycleState.IDLE
      }
    }
    runCatching { CacheCleanupManager.enforceCacheLimitLru(context) }
  }

  private fun failAllInFlightRequests(reason: String) {
    val entries = inFlightRequests.entries.toList()
    inFlightRequests.clear()
    for ((_, deferred) in entries) {
      if (!deferred.isCompleted) {
        deferred.completeExceptionally(NetworkInterruptedException(reason))
      }
    }
  }

  private fun resolveCredentials(customId: Int, customHash: String): Pair<Int, String>? {
    if (customId > 0 && customHash.isNotBlank()) {
      return customId to customHash.trim()
    }
    val buildId = BuildConfig.TELEGRAM_API_ID.toIntOrNull() ?: 0
    val buildHash = BuildConfig.TELEGRAM_API_HASH
    if (buildId > 0 && buildHash.isNotBlank() && buildHash != "YOUR_TELEGRAM_API_HASH") {
      return buildId to buildHash.trim()
    }
    return null
  }

  suspend fun saveAndApplyApiCredentials(rawApiId: String, rawApiHash: String): Result<Unit> =
    runCatching {
      val parsedId = rawApiId.trim().toIntOrNull()
        ?: throw IllegalArgumentException("API ID phải là số nguyên hợp lệ")
      require(parsedId > 0) { "API ID phải lớn hơn 0" }
      val cleanHash = rawApiHash.trim()
      require(cleanHash.length >= 16) { "API Hash không hợp lệ (quá ngắn)" }

      settingsPreferences.saveApiCredentials(parsedId, cleanHash)
      lifecycleMutex.withLock {
        activeApiId = parsedId
        activeApiHash = cleanHash
        isIntentionalShutdown = false
        _connectionStatus.value = TelegramConnectionStatus.CONNECTING
        _lifecycleState.value = TdLibLifecycleState.CONNECTING

        if (client == null) {
          createClientIfNeeded()
        } else if (waitingForParameters) {
          applyTdlibParameters()
        } else {
          closeClientLocked(
            preserveWaitApiState = false,
            closingStatus = TelegramConnectionStatus.RECONNECTING,
          )
          _connectionStatus.value = TelegramConnectionStatus.CONNECTING
          _lifecycleState.value = TdLibLifecycleState.CONNECTING
          isIntentionalShutdown = false
          createClientIfNeeded()
        }
      }
      Unit
    }

  suspend fun switchApiCredentials() {
    val settings = settingsPreferences.getCurrentSettings()
    val currentId = if (settings.customTelegramApiId > 0) settings.customTelegramApiId.toString() else ""
    val currentHash = settings.customTelegramApiHash
    closeConnectionIdle(preserveWaitApiState = true)
    _lifecycleState.value = TdLibLifecycleState.AWAITING_CREDENTIALS
    _authState.value = TelegramAuthState.WaitApiCredentials(
      initialApiId = currentId,
      initialApiHash = currentHash,
    )
  }

  fun returnToPhoneNumberStep() {
    _lifecycleState.value = TdLibLifecycleState.AWAITING_PHONE
    _authState.value = TelegramAuthState.WaitPhoneNumber(initialPhone = lastPhoneEntered)
  }

  private fun createClientIfNeeded() {
    if (client != null) return
    isIntentionalShutdown = false
    val created = runCatching {
      Client.create(
        { obj -> handleUpdate(obj) },
        { err ->
          _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
          _lifecycleState.value = TdLibLifecycleState.ERROR
          _authState.value = TelegramAuthState.Error(
            TelegramPolicyGuard.translateAuthError(err.message)
          )
        },
        { err ->
          _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
          _lifecycleState.value = TdLibLifecycleState.ERROR
          _authState.value = TelegramAuthState.Error(
            TelegramPolicyGuard.translateAuthError(err.message)
          )
        },
      )
    }

    created.onSuccess { c ->
      client = c
    }.onFailure { throwable ->
      _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
      _lifecycleState.value = TdLibLifecycleState.ERROR
      _authState.value = TelegramAuthState.Error(
        "Không thể khởi tạo thư viện TDLib trên thiết bị này: ${throwable.message ?: "Thiếu thư viện native"}"
      )
    }
  }

  private fun handleUpdate(obj: TdApi.Object) {
    when (obj) {
      is TdApi.UpdateAuthorizationState -> onAuthStateChanged(obj.authorizationState)
      is TdApi.UpdateConnectionState -> tdConnectionState = obj.state
      is TdApi.UpdateFile -> fileUpdates.tryEmit(obj.file)
      is TdApi.UpdateMessageSendSucceeded ->
        messageSendEvents.tryEmit(MessageSendEvent.Succeeded(obj.oldMessageId, obj.message))
      is TdApi.UpdateMessageSendFailed ->
        messageSendEvents.tryEmit(MessageSendEvent.Failed(obj.oldMessageId, obj.error))
      else -> Unit
    }
  }

  private fun onAuthStateChanged(state: TdApi.AuthorizationState) {
    when (state) {
      is TdApi.AuthorizationStateWaitTdlibParameters -> {
        waitingForParameters = true
        if (activeApiId > 0 && activeApiHash.isNotBlank()) {
          applyTdlibParameters()
        } else {
          _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
          _lifecycleState.value = TdLibLifecycleState.AWAITING_CREDENTIALS
          _authState.value = TelegramAuthState.WaitApiCredentials()
        }
      }
      is TdApi.AuthorizationStateWaitPhoneNumber -> {
        waitingForParameters = false
        _connectionStatus.value = TelegramConnectionStatus.CONNECTING
        _lifecycleState.value = TdLibLifecycleState.AWAITING_PHONE
        _authState.value = TelegramAuthState.WaitPhoneNumber(initialPhone = lastPhoneEntered)
      }
      is TdApi.AuthorizationStateWaitCode -> {
        waitingForParameters = false
        _connectionStatus.value = TelegramConnectionStatus.CONNECTING
        _lifecycleState.value = TdLibLifecycleState.AWAITING_CODE
        _authState.value = TelegramAuthState.WaitCode(lastPhoneEntered)
      }
      is TdApi.AuthorizationStateWaitPassword -> {
        waitingForParameters = false
        _connectionStatus.value = TelegramConnectionStatus.CONNECTING
        _lifecycleState.value = TdLibLifecycleState.AWAITING_PASSWORD
        _authState.value = TelegramAuthState.WaitPassword(state.passwordHint ?: "")
      }
      is TdApi.AuthorizationStateReady -> {
        waitingForParameters = false
        fetchCurrentUser()
      }
      is TdApi.AuthorizationStateClosed -> {
        client = null
        waitingForParameters = false
        failAllInFlightRequests("Client is closed")
        closedDeferred?.complete(Unit)

        val unexpectedClose = !isIntentionalShutdown
        if (_authState.value !is TelegramAuthState.WaitApiCredentials) {
          _authState.value = TelegramAuthState.Uninitialized
        }
        if (unexpectedClose && appInForeground && !isFloodWaitCurrentlyActive()) {
          _connectionStatus.value = TelegramConnectionStatus.RECONNECTING
          _lifecycleState.value = TdLibLifecycleState.RECONNECTING
          internalScope.launch {
            val settings = settingsPreferences.getCurrentSettings()
            if (settings.isTelegramLoggedIn && NetworkRetryPolicy.isNetworkAvailable(context)) {
              delay(500L)
              ensureConnected(forceReconnect = false)
            } else {
              _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
              _lifecycleState.value = TdLibLifecycleState.IDLE
            }
          }
        } else {
          if (isFloodWaitCurrentlyActive()) {
            _connectionStatus.value = TelegramConnectionStatus.FLOOD_WAIT
            _lifecycleState.value = TdLibLifecycleState.FLOOD_WAIT
          } else {
            _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
            _lifecycleState.value = TdLibLifecycleState.IDLE
          }
        }
      }
      else -> Unit
    }
  }

  private fun applyTdlibParameters() {
    if (activeApiId <= 0 || activeApiHash.isBlank()) {
      _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
      _lifecycleState.value = TdLibLifecycleState.AWAITING_CREDENTIALS
      _authState.value = TelegramAuthState.WaitApiCredentials()
      return
    }

    val dbDir = File(context.filesDir, "tdlib_db").apply { mkdirs() }.absolutePath
    val filesDir = File(context.cacheDir, "tdlib_files").apply { mkdirs() }.absolutePath

    val params = TdApi.SetTdlibParameters().apply {
      useTestDc = false
      databaseDirectory = dbDir
      this.filesDirectory = filesDir
      databaseEncryptionKey = ByteArray(0)
      useFileDatabase = true
      useChatInfoDatabase = true
      useMessageDatabase = true
      useSecretChats = false
      apiId = activeApiId
      apiHash = activeApiHash
      systemLanguageCode = "vi"
      deviceModel = Build.MODEL ?: "Android"
      systemVersion = Build.VERSION.RELEASE ?: "14"
      applicationVersion = "1.0"
    }

    TelegramPolicyGuard.assertAllowedFunction(params)
    client?.send(params) { result ->
      if (result is TdApi.Error) {
        _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
        _lifecycleState.value = TdLibLifecycleState.ERROR
        _authState.value = TelegramAuthState.Error(
          TelegramPolicyGuard.translateAuthError(result.message)
        )
      }
    }
  }

  private fun fetchCurrentUser() {
    val c = client ?: return
    val getMe = TdApi.GetMe()
    TelegramPolicyGuard.assertAllowedFunction(getMe)
    c.send(getMe) { obj ->
      when (obj) {
        is TdApi.User -> {
          cachedUserId = obj.id
          val name = listOf(obj.firstName, obj.lastName).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Người dùng Telegram" }
          val phone = obj.phoneNumber ?: lastPhoneEntered
          internalScope.launch {
            settingsPreferences.saveTelegramSession(
              userId = obj.id,
              displayName = name,
              phoneNumber = phone,
            )
          }
          _authState.value = TelegramAuthState.Ready(
            userId = obj.id,
            displayName = name,
            phoneNumber = phone,
          )
          refreshSteadyStatus()
        }
        is TdApi.Error -> {
          _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
          _lifecycleState.value = TdLibLifecycleState.ERROR
          _authState.value = TelegramAuthState.Error(
            TelegramPolicyGuard.translateAuthError(obj.message)
          )
        }
      }
    }
  }

  suspend fun sendPhoneNumber(rawPhone: String): Result<Unit> = runCatching {
    val normalized = Formatters.normalizePhoneNumber(rawPhone)
    require(normalized.length >= 10) { "Số điện thoại quá ngắn" }
    lastPhoneEntered = normalized
    val req = TdApi.SetAuthenticationPhoneNumber(normalized, null)
    sendSuspending(req)
    Unit
  }

  suspend fun sendAuthCode(code: String): Result<Unit> = runCatching {
    val cleanCode = code.trim()
    require(cleanCode.length >= 4) { "Vui lòng nhập đầy đủ mã xác nhận" }
    sendSuspending(TdApi.CheckAuthenticationCode(cleanCode))
    Unit
  }

  suspend fun sendPassword(password: String): Result<Unit> = runCatching {
    require(password.isNotEmpty()) { "Vui lòng nhập mật khẩu xác minh 2 bước" }
    sendSuspending(TdApi.CheckAuthenticationPassword(password))
    Unit
  }

  suspend fun logOut(): Result<Unit> = runCatching {
    if (client == null) {
      ensureInitializedOnDemand()
      delay(400L)
    }
    if (client != null) {
      runCatching { sendSuspending(TdApi.LogOut()) }
    }
    cachedUserId = 0L
    settingsPreferences.clearTelegramSession()
    clearFloodWait()
    closeConnectionIdle(preserveWaitApiState = false)
    Unit
  }

  suspend fun loadAllowedTargets(): List<TelegramCandidateTarget> {
    if (!isSessionReady()) {
      ensureConnected().getOrThrow()
    }
    val chatsObj = sendFunction(TdApi.GetChats(TdApi.ChatListMain(), 100)) as? TdApi.Chats
      ?: return emptyList()

    val results = mutableListOf<TelegramCandidateTarget>()
    for (chatId in chatsObj.chatIds) {
      val chat = runCatching { sendFunction(TdApi.GetChat(chatId)) as? TdApi.Chat }.getOrNull()
        ?: continue
      var supergroup: TdApi.Supergroup? = null
      var basicGroup: TdApi.BasicGroup? = null
      when (val type = chat.type) {
        is TdApi.ChatTypeSupergroup -> {
          supergroup = runCatching {
            sendFunction(TdApi.GetSupergroup(type.supergroupId)) as? TdApi.Supergroup
          }.getOrNull()
        }
        is TdApi.ChatTypeBasicGroup -> {
          basicGroup = runCatching {
            sendFunction(TdApi.GetBasicGroup(type.basicGroupId)) as? TdApi.BasicGroup
          }.getOrNull()
        }
        else -> Unit
      }
      TelegramPolicyGuard.classifyAllowedTarget(chat, cachedUserId, supergroup, basicGroup)?.let {
        results.add(it)
      }
    }
    return TelegramPolicyGuard.sortTargetsByPriority(results.distinctBy { it.chatId })
  }

  data class CoreTopicIds(
    val photosThreadId: Long?,
    val videosThreadId: Long?,
    val trashThreadId: Long?,
    val allTopics: List<TopicInfoModel>,
  )

  suspend fun getForumTopics(chatId: Long): List<TopicInfoModel> {
    if (client == null) return emptyList()
    val topicsObj = sendFunction(TdApi.GetForumTopics(chatId, "", 0, 0L, 0, 100))
      as? TdApi.ForumTopics ?: return emptyList()
    return topicsObj.topics.map { topic ->
      TopicInfoModel(
        chatId = chatId,
        messageThreadId = topic.info.forumTopicId.toLong(),
        title = topic.info.name,
        iconColor = topic.info.icon?.color ?: 0x6FB9F0,
      )
    }
  }

  /**
   * Thread-safe check-before-create for all 3 required core topics ("Ảnh", "Video", "Đã Xoá Gần Đây")
   * serialized by a per-chat Mutex with Vietnamese tone/diacritic normalization.
   */
  suspend fun ensureThreeCoreTopics(chatId: Long): CoreTopicIds =
    topicMutexFor(chatId).withLock {
      val initialTopics = getForumTopics(chatId)
      var photosId = initialTopics.firstOrNull {
        TelegramPolicyGuard.isPhotosAutoTopicTitle(it.title)
      }?.messageThreadId
      var videosId = initialTopics.firstOrNull {
        TelegramPolicyGuard.isVideosAutoTopicTitle(it.title)
      }?.messageThreadId
      var trashId = initialTopics.firstOrNull {
        TelegramPolicyGuard.isTrashTopicTitle(it.title)
      }?.messageThreadId

      if (photosId == null) {
        photosId = runCatching {
          createForumTopicRateLimited(
            chatId = chatId,
            title = TelegramPolicyGuard.PHOTOS_TOPIC_TITLE,
            iconColor = 0x6FB9F0,
          ).messageThreadId
        }.getOrNull()
      }

      if (videosId == null) {
        videosId = runCatching {
          createForumTopicRateLimited(
            chatId = chatId,
            title = TelegramPolicyGuard.VIDEOS_TOPIC_TITLE,
            iconColor = 0xCB86DB,
          ).messageThreadId
        }.getOrNull()
      }

      if (trashId == null) {
        trashId = runCatching {
          createForumTopicRateLimited(
            chatId = chatId,
            title = TelegramPolicyGuard.TRASH_TOPIC_TITLE,
            iconColor = 0xFB6F5F,
          ).messageThreadId
        }.getOrNull()
      }

      val finalTopics = if (photosId == null || videosId == null || trashId == null) {
        initialTopics
      } else {
        getForumTopics(chatId).ifEmpty { initialTopics }
      }
      CoreTopicIds(
        photosThreadId = photosId,
        videosThreadId = videosId,
        trashThreadId = trashId,
        allTopics = finalTopics,
      )
    }

  suspend fun findOrCreateMediaTopic(
    chatId: Long,
    title: String,
    iconColor: Int = 0x6FB9F0,
  ): Long = topicMutexFor(chatId).withLock {
    val cleanTitle = title.trim()
    val targetKey = Formatters.normalizeTopicKey(cleanTitle)
    val existing = getForumTopics(chatId).firstOrNull {
      Formatters.normalizeTopicKey(it.title) == targetKey
    }
    if (existing != null) return@withLock existing.messageThreadId

    val created = createForumTopicRateLimited(
      chatId = chatId,
      title = cleanTitle,
      iconColor = iconColor,
    )
    created.messageThreadId
  }

  suspend fun findOrCreateTrashTopic(chatId: Long): Long {
    return findOrCreateMediaTopic(
      chatId = chatId,
      title = TelegramPolicyGuard.TRASH_TOPIC_TITLE,
      iconColor = 0xFB6F5F,
    )
  }

  suspend fun forwardMessageToTrashTopic(
    chatId: Long,
    fromChatId: Long,
    sourceMessageId: Long,
    trashThreadId: Long,
  ): Long? {
    if (!isSessionReady()) return null
    val options = TdApi.MessageSendOptions().apply {
      disableNotification = true
      fromBackground = false
    }
    val req = TdApi.ForwardMessages(
      chatId,
      TdApi.MessageTopicForum(trashThreadId.toInt()),
      fromChatId,
      longArrayOf(sourceMessageId),
      options,
      true,
      false,
    )
    val result = sendFunction(req) as? TdApi.Messages ?: return null
    return result.messages?.firstOrNull()?.id
  }

  private suspend fun createForumTopicRateLimited(
    chatId: Long,
    title: String,
    iconColor: Int = 0x6FB9F0,
  ): TopicInfoModel {
    val elapsed = System.currentTimeMillis() - lastTopicMutationMs.get()
    if (elapsed in 0..1999) {
      delay(2000L - elapsed)
    }
    lastTopicMutationMs.set(System.currentTimeMillis())

    val icon = TdApi.ForumTopicIcon(iconColor, 0L)
    val req = TdApi.CreateForumTopic(chatId, title.trim(), false, icon)
    val info = sendFunction(req) as TdApi.ForumTopicInfo
    return TopicInfoModel(
      chatId = chatId,
      messageThreadId = info.forumTopicId.toLong(),
      title = info.name,
      iconColor = info.icon?.color ?: iconColor,
    )
  }

  /**
   * Sends a document preserving original bytes and monitors TDLib upload completion/stalls.
   * Supports resuming a previously queued pendingMessageId after an 8s network interruption wait
   * without duplicating messages on Telegram.
   */
  suspend fun sendDocumentOriginal(
    chatId: Long,
    messageThreadId: Long?,
    localFilePath: String,
    caption: String,
    thumbnailPath: String? = null,
    thumbnailWidth: Int = 0,
    thumbnailHeight: Int = 0,
    existingPendingMessageId: Long? = null,
    onProgressMade: () -> Unit = {},
  ): SentDocumentResult {
    val content = TdApi.InputMessageDocument().apply {
      document = TdApi.InputFileLocal(localFilePath)
      thumbnail = buildInputThumbnail(thumbnailPath, thumbnailWidth, thumbnailHeight)
      this.caption = TdApi.FormattedText(caption, emptyArray())
      disableContentTypeDetection = true
    }
    return sendMessageWithResume(
      chatId = chatId,
      messageThreadId = messageThreadId,
      inputMessageContent = content,
      existingPendingMessageId = existingPendingMessageId,
      onProgressMade = onProgressMade,
    )
  }

  /**
   * Sends a photo or video as native high-quality Telegram media (InputMessagePhoto / InputMessageVideo)
   * without client-side downscaling, supporting streaming for videos and preserving resume/no-duplicate semantics.
   */
  suspend fun sendMediaHd(
    chatId: Long,
    messageThreadId: Long?,
    mediaType: MediaType,
    localFilePath: String,
    caption: String,
    width: Int = 0,
    height: Int = 0,
    durationSec: Int = 0,
    thumbnailPath: String? = null,
    thumbnailWidth: Int = 0,
    thumbnailHeight: Int = 0,
    existingPendingMessageId: Long? = null,
    onProgressMade: () -> Unit = {},
  ): SentDocumentResult {
    val thumb = buildInputThumbnail(thumbnailPath, thumbnailWidth, thumbnailHeight)
    val formattedCaption = TdApi.FormattedText(caption, emptyArray())
    val content: TdApi.InputMessageContent = when (mediaType) {
      MediaType.PHOTO -> TdApi.InputMessagePhoto().apply {
        photo = TdApi.InputFileLocal(localFilePath)
        thumbnail = thumb
        addedStickerFileIds = intArrayOf()
        this.width = width.coerceAtLeast(0)
        this.height = height.coerceAtLeast(0)
        this.caption = formattedCaption
        showCaptionAboveMedia = false
        selfDestructType = null
        hasSpoiler = false
      }
      MediaType.VIDEO -> TdApi.InputMessageVideo().apply {
        video = TdApi.InputFileLocal(localFilePath)
        thumbnail = thumb
        cover = null
        startTimestamp = 0
        addedStickerFileIds = intArrayOf()
        this.duration = durationSec.coerceAtLeast(0)
        this.width = width.coerceAtLeast(0)
        this.height = height.coerceAtLeast(0)
        supportsStreaming = true
        this.caption = formattedCaption
        showCaptionAboveMedia = false
        selfDestructType = null
        hasSpoiler = false
      }
    }
    return sendMessageWithResume(
      chatId = chatId,
      messageThreadId = messageThreadId,
      inputMessageContent = content,
      existingPendingMessageId = existingPendingMessageId,
      onProgressMade = onProgressMade,
    )
  }

  private fun buildInputThumbnail(
    thumbnailPath: String?,
    thumbnailWidth: Int,
    thumbnailHeight: Int,
  ): TdApi.InputThumbnail? {
    if (thumbnailPath.isNullOrBlank() || !File(thumbnailPath).exists()) return null
    return TdApi.InputThumbnail(
      TdApi.InputFileLocal(thumbnailPath),
      thumbnailWidth.coerceAtLeast(0),
      thumbnailHeight.coerceAtLeast(0),
    )
  }

  private suspend fun sendMessageWithResume(
    chatId: Long,
    messageThreadId: Long?,
    inputMessageContent: TdApi.InputMessageContent,
    existingPendingMessageId: Long?,
    onProgressMade: () -> Unit,
  ): SentDocumentResult {
    if (!NetworkRetryPolicy.isNetworkAvailable(context) ||
      tdConnectionState is TdApi.ConnectionStateWaitingForNetwork
    ) {
      if (existingPendingMessageId != null) {
        throw PendingUploadInterruptedException(existingPendingMessageId, "Mất kết nối mạng khi tải lên Telegram")
      }
      throw NetworkInterruptedException("Mất kết nối mạng khi tải lên Telegram")
    }

    var targetPendingMsg: TdApi.Message? = null
    if (existingPendingMessageId != null) {
      val existing = runCatching {
        sendFunction(TdApi.GetMessage(chatId, existingPendingMessageId)) as? TdApi.Message
      }.getOrNull()

      if (existing != null) {
        when (existing.sendingState) {
          null -> return messageToSentResult(existing)
          is TdApi.MessageSendingStatePending -> targetPendingMsg = existing
          is TdApi.MessageSendingStateFailed -> {
            val failedState = existing.sendingState as TdApi.MessageSendingStateFailed
            if (failedState.canRetry) {
              val resent = runCatching {
                sendFunction(
                  TdApi.ResendMessages(chatId, longArrayOf(existing.id), null, 0L)
                ) as? TdApi.Messages
              }.getOrNull()
              val firstResent = resent?.messages?.firstOrNull()
              if (firstResent != null) {
                if (firstResent.sendingState == null) {
                  return messageToSentResult(firstResent)
                }
                targetPendingMsg = firstResent
              }
            }
          }
        }
      }
    }

    val initialMsg = targetPendingMsg ?: run {
      val options = TdApi.MessageSendOptions().apply {
        disableNotification = true
        fromBackground = false
      }

      val req = TdApi.SendMessage().apply {
        this.chatId = chatId
        this.topicId = messageThreadId?.takeIf { it != 0L }?.let {
          TdApi.MessageTopicForum(it.toInt())
        }
        this.options = options
        this.inputMessageContent = inputMessageContent
      }
      sendFunction(req) as TdApi.Message
    }

    if (initialMsg.sendingState == null) {
      return messageToSentResult(initialMsg)
    }

    return awaitPendingMessageUpload(
      initialMsg = initialMsg,
      onProgressMade = onProgressMade,
    )
  }

  private fun messageToSentResult(msg: TdApi.Message): SentDocumentResult {
    val resolvedTopicId = (msg.topicId as? TdApi.MessageTopicForum)?.forumTopicId?.toLong()
    return when (val content = msg.content) {
      is TdApi.MessageDocument -> {
        val doc = content.document
        SentDocumentResult(
          chatId = msg.chatId,
          messageId = msg.id,
          fileId = doc?.document?.id,
          remoteFileId = doc?.document?.remote?.id,
          messageThreadId = resolvedTopicId,
          thumbFileId = doc?.thumbnail?.file?.id,
        )
      }
      is TdApi.MessagePhoto -> {
        val sizes = content.photo?.sizes
        val largest = sizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        val compactThumb = sizes
          ?.filter { maxOf(it.width, it.height) in 90..360 }
          ?.minByOrNull { it.width.toLong() * it.height.toLong() }
          ?: sizes?.minByOrNull { it.width.toLong() * it.height.toLong() }
        SentDocumentResult(
          chatId = msg.chatId,
          messageId = msg.id,
          fileId = largest?.photo?.id,
          remoteFileId = largest?.photo?.remote?.id,
          messageThreadId = resolvedTopicId,
          thumbFileId = compactThumb?.photo?.id ?: largest?.photo?.id,
        )
      }
      is TdApi.MessageVideo -> {
        val video = content.video
        SentDocumentResult(
          chatId = msg.chatId,
          messageId = msg.id,
          fileId = video?.video?.id,
          remoteFileId = video?.video?.remote?.id,
          messageThreadId = resolvedTopicId,
          thumbFileId = video?.thumbnail?.file?.id,
        )
      }
      else -> SentDocumentResult(
        chatId = msg.chatId,
        messageId = msg.id,
        fileId = null,
        remoteFileId = null,
        messageThreadId = resolvedTopicId,
        thumbFileId = null,
      )
    }
  }

  private fun extractUploadProgressFile(msg: TdApi.Message): TdApi.File? {
    return when (val content = msg.content) {
      is TdApi.MessageDocument -> content.document?.document
      is TdApi.MessagePhoto -> content.photo?.sizes
        ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        ?.photo
      is TdApi.MessageVideo -> content.video?.video
      else -> null
    }
  }

  private suspend fun awaitPendingMessageUpload(
    initialMsg: TdApi.Message,
    onProgressMade: () -> Unit,
  ): SentDocumentResult = coroutineScope {
    val uploadFile = extractUploadProgressFile(initialMsg)
    val fileId = uploadFile?.id
    var lastUploadedBytes = uploadFile?.remote?.uploadedSize ?: 0L
    var lastProgressAtMs = System.currentTimeMillis()
    val resultChannel = Channel<Result<SentDocumentResult>>(Channel.BUFFERED)

    val sendJob = launch {
      messageSendEvents.collect { event ->
        if (event.oldMessageId == initialMsg.id) {
          when (event) {
            is MessageSendEvent.Succeeded -> {
              onProgressMade()
              resultChannel.trySend(Result.success(messageToSentResult(event.message)))
            }
            is MessageSendEvent.Failed -> {
              val rawMsg = event.error?.message
              val floodSec = TelegramPolicyGuard.parseFloodWaitSeconds(rawMsg)
              val ex: Throwable = if (floodSec != null || event.error?.code == 429) {
                val waitSec = (floodSec ?: 30).coerceAtLeast(1)
                val unlockAt = TelegramPolicyGuard.computeFloodWaitUnlockAtEpochMs(waitSec)
                val reason = "Telegram yêu cầu chờ $waitSec giây (FLOOD_WAIT_$waitSec)"
                recordFloodWait(waitSec, unlockAt, reason)
                FloodWaitException(waitSec, unlockAt, reason)
              } else {
                val translated = TelegramPolicyGuard.translateAuthError(rawMsg)
                if (NetworkRetryPolicy.isTransientNetworkError(IllegalStateException(rawMsg), context)) {
                  PendingUploadInterruptedException(initialMsg.id, translated)
                } else {
                  IllegalStateException(translated)
                }
              }
              resultChannel.trySend(Result.failure(ex))
            }
          }
        }
      }
    }

    val fileJob = launch {
      if (fileId != null) {
        fileUpdates.collect { updated ->
          if (updated.id == fileId) {
            val uploaded = updated.remote?.uploadedSize ?: 0L
            if (uploaded > lastUploadedBytes) {
              lastUploadedBytes = uploaded
              lastProgressAtMs = System.currentTimeMillis()
              onProgressMade()
            }
          }
        }
      }
    }

    val watchdogJob = launch {
      while (true) {
        delay(NetworkRetryPolicy.PROGRESS_CHECK_INTERVAL_MS)
        if (!NetworkRetryPolicy.isNetworkAvailable(context) ||
          tdConnectionState is TdApi.ConnectionStateWaitingForNetwork
        ) {
          resultChannel.trySend(
            Result.failure(
              PendingUploadInterruptedException(initialMsg.id, "Mất kết nối mạng khi đang tải tệp lên Telegram")
            )
          )
          break
        }
        val elapsedNoProgress = System.currentTimeMillis() - lastProgressAtMs
        if (elapsedNoProgress >= NetworkRetryPolicy.STALL_TIMEOUT_MS) {
          resultChannel.trySend(
            Result.failure(
              PendingUploadInterruptedException(
                initialMsg.id,
                "Quá trình tải tệp lên Telegram bị gián đoạn",
                StalledTransferException("Upload stalled"),
              )
            )
          )
          break
        }
      }
    }

    try {
      val currentCheck = runCatching {
        sendFunction(TdApi.GetMessage(initialMsg.chatId, initialMsg.id)) as? TdApi.Message
      }.getOrNull()
      if (currentCheck != null && currentCheck.sendingState == null) {
        return@coroutineScope messageToSentResult(currentCheck)
      }
      resultChannel.receive().getOrThrow()
    } finally {
      sendJob.cancel()
      fileJob.cancel()
      watchdogJob.cancel()
      resultChannel.close()
    }
  }

  suspend fun scanHistoryReadOnly(
    chatId: Long,
    messageThreadId: Long? = null,
    limit: Int = 60,
  ): List<ScannedTelegramMedia> {
    if (!isSessionReady()) {
      ensureConnected().getOrThrow()
    }

    val targetLimit = limit.coerceIn(1, 200)
    val collected = mutableListOf<TdApi.Message>()
    val seenMsgIds = HashSet<Long>()
    var fromMessageId = 0L
    var pageAttempts = 0

    while (collected.size < targetLimit && pageAttempts < 5) {
      pageAttempts++
      val batchSize = (targetLimit - collected.size).coerceIn(20, 50)
      val messagesObj = if (messageThreadId != null && messageThreadId != 0L) {
        sendFunction(TdApi.GetForumTopicHistory(chatId, messageThreadId.toInt(), fromMessageId, 0, batchSize))
      } else {
        sendFunction(TdApi.GetChatHistory(chatId, fromMessageId, 0, batchSize, false))
      } as? TdApi.Messages ?: break

      val batch = messagesObj.messages ?: break
      if (batch.isEmpty()) break

      var addedInBatch = 0
      for (msg in batch) {
        if (seenMsgIds.add(msg.id)) {
          collected.add(msg)
          addedInBatch++
        }
      }

      val oldestId = batch.last().id
      if (oldestId == 0L || oldestId == fromMessageId || addedInBatch == 0) {
        break
      }
      fromMessageId = oldestId
      if (collected.size < targetLimit) {
        delay(80L)
      }
    }

    val scanned = mutableListOf<ScannedTelegramMedia>()
    for (msg in collected) {
      val msgTopicId = (msg.topicId as? TdApi.MessageTopicForum)?.forumTopicId?.toLong()
        ?: messageThreadId?.takeIf { it != 0L }

      when (val content = msg.content) {
        is TdApi.MessageDocument -> {
          val doc = content.document
          val captionText = content.caption?.text
          val parsed = TelegramCaptionMetadata.parseFromCaption(captionText)
          val rawFileName = doc.fileName?.takeIf { it.isNotBlank() }
            ?: parsed?.fileName?.takeIf { it.isNotBlank() }
            ?: "TG_Doc_${msg.id}"
          val mime = doc.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
          val isVideoExt = rawFileName.substringAfterLast('.', "").lowercase() in setOf(
            "mp4", "mov", "mkv", "webm", "3gp", "avi", "m4v"
          )
          val mediaType = when {
            mime.startsWith("video/", ignoreCase = true) || isVideoExt -> MediaType.VIDEO
            parsed?.mediaKind == "VIDEO" -> MediaType.VIDEO
            else -> MediaType.PHOTO
          }
          val fallbackHash = doc.document.remote?.uniqueId?.takeIf { it.isNotBlank() }
            ?.let { "tg_u_$it" }
            ?: "tg_${msg.chatId}_${msg.id}"
          val directSize = doc.document.size.takeIf { it > 0L }
            ?: doc.document.expectedSize.takeIf { it > 0L }
            ?: (parsed?.sizeBytes ?: 0L)

          scanned.add(
            ScannedTelegramMedia(
              contentHash = parsed?.contentHash?.takeIf { it.isNotBlank() } ?: fallbackHash,
              chatId = msg.chatId,
              messageId = msg.id,
              topicId = msgTopicId ?: parsed?.topicId,
              fileId = doc.document.id,
              remoteFileId = doc.document.remote?.id,
              thumbFileId = doc.thumbnail?.file?.id,
              fileName = rawFileName,
              mimeType = mime,
              mediaType = mediaType,
              sizeBytes = directSize,
              durationSec = parsed?.durationSec ?: 0,
              takenAtEpochMs = parsed?.takenAtEpochMs?.takeIf { it > 0L } ?: (msg.date * 1000L),
              isTrashed = parsed?.isTrashed ?: false,
            )
          )
        }

        is TdApi.MessagePhoto -> {
          val sizes = content.photo.sizes ?: continue
          val largest = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: continue
          val compactThumb = sizes
            .filter { maxOf(it.width, it.height) in 90..360 }
            .minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: sizes.minByOrNull { it.width.toLong() * it.height.toLong() }
          val captionText = content.caption?.text
          val parsed = TelegramCaptionMetadata.parseFromCaption(captionText)
          val fallbackName = "TG_Photo_${msg.id}.jpg"
          val resolvedName = parsed?.fileName?.takeIf { it.isNotBlank() }
            ?: TelegramCaptionMetadata.extractReadableTitle(captionText, fallbackName)
          val fallbackHash = largest.photo.remote?.uniqueId?.takeIf { it.isNotBlank() }
            ?.let { "tg_u_$it" }
            ?: "tg_photo_${msg.chatId}_${msg.id}"
          val sizeBytes = largest.photo.size.takeIf { it > 0L }
            ?: largest.photo.expectedSize.coerceAtLeast(0L)

          scanned.add(
            ScannedTelegramMedia(
              contentHash = parsed?.contentHash?.takeIf { it.isNotBlank() } ?: fallbackHash,
              chatId = msg.chatId,
              messageId = msg.id,
              topicId = msgTopicId ?: parsed?.topicId,
              fileId = largest.photo.id,
              remoteFileId = largest.photo.remote?.id,
              thumbFileId = compactThumb?.photo?.id ?: largest.photo.id,
              fileName = resolvedName,
              mimeType = "image/jpeg",
              mediaType = MediaType.PHOTO,
              sizeBytes = sizeBytes,
              durationSec = 0,
              takenAtEpochMs = parsed?.takenAtEpochMs?.takeIf { it > 0L } ?: (msg.date * 1000L),
              isTrashed = parsed?.isTrashed ?: false,
            )
          )
        }

        is TdApi.MessageVideo -> {
          val video = content.video
          val captionText = content.caption?.text
          val parsed = TelegramCaptionMetadata.parseFromCaption(captionText)
          val fallbackName = "TG_Video_${msg.id}.mp4"
          val resolvedName = video.fileName?.takeIf { it.isNotBlank() }
            ?: parsed?.fileName?.takeIf { it.isNotBlank() }
            ?: TelegramCaptionMetadata.extractReadableTitle(captionText, fallbackName)
          val fallbackHash = video.video.remote?.uniqueId?.takeIf { it.isNotBlank() }
            ?.let { "tg_u_$it" }
            ?: "tg_video_${msg.chatId}_${msg.id}"
          val sizeBytes = video.video.size.takeIf { it > 0L }
            ?: video.video.expectedSize.coerceAtLeast(0L)

          scanned.add(
            ScannedTelegramMedia(
              contentHash = parsed?.contentHash?.takeIf { it.isNotBlank() } ?: fallbackHash,
              chatId = msg.chatId,
              messageId = msg.id,
              topicId = msgTopicId ?: parsed?.topicId,
              fileId = video.video.id,
              remoteFileId = video.video.remote?.id,
              thumbFileId = video.thumbnail?.file?.id,
              fileName = resolvedName,
              mimeType = video.mimeType?.takeIf { it.isNotBlank() } ?: "video/mp4",
              mediaType = MediaType.VIDEO,
              sizeBytes = sizeBytes,
              durationSec = video.duration.coerceAtLeast(0),
              takenAtEpochMs = parsed?.takenAtEpochMs?.takeIf { it > 0L } ?: (msg.date * 1000L),
              isTrashed = parsed?.isTrashed ?: false,
            )
          )
        }

        else -> Unit
      }
    }
    return scanned
  }

  /**
   * Quickly fetches a tiny (~15KB) Telegram document/photo preview thumbnail synchronously while a
   * TDLib session is open so cloud-only grids render smoothly offline.
   */
  suspend fun downloadThumbnailQuick(fileId: Int): String? {
    if (fileId <= 0 || client == null || _authState.value !is TelegramAuthState.Ready) return null
    val result = withTimeoutOrNull(2_500L) {
      runCatching {
        sendFunction(TdApi.DownloadFile(fileId, 32, 0L, 0L, true), timeoutMs = 2_500L) as? TdApi.File
      }.getOrNull()
    } ?: return null
    val completed = result.local?.isDownloadingCompleted == true
    val path = result.local?.path
    return path?.takeIf { completed && MediaStoreScanner.isUsableLocalFile(it) }
  }

  private fun extractFileFromMessage(
    msg: TdApi.Message,
    expectedMediaType: MediaType? = null,
  ): RefreshedTelegramFile? {
    return when (val content = msg.content) {
      is TdApi.MessageDocument -> {
        val doc = content.document
        val file = doc.document
        val rawPath = file.local?.path
        val usable = file.local?.isDownloadingCompleted == true && MediaStoreScanner.isUsableLocalFile(rawPath)
        RefreshedTelegramFile(
          fileId = file.id,
          remoteFileId = file.remote?.id,
          thumbFileId = doc.thumbnail?.file?.id,
          localPath = rawPath.takeIf { usable },
          isDownloadCompleted = usable,
          expectedSizeBytes = file.expectedSize.takeIf { it > 0L } ?: file.size.coerceAtLeast(0L),
        )
      }
      is TdApi.MessagePhoto -> {
        val sizes = content.photo.sizes ?: return null
        val largest = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: return null
        val compactThumb = sizes
          .filter { maxOf(it.width, it.height) in 90..360 }
          .minByOrNull { it.width.toLong() * it.height.toLong() }
          ?: sizes.minByOrNull { it.width.toLong() * it.height.toLong() }
        val file = largest.photo
        val rawPath = file.local?.path
        val usable = file.local?.isDownloadingCompleted == true && MediaStoreScanner.isUsableLocalFile(rawPath)
        RefreshedTelegramFile(
          fileId = file.id,
          remoteFileId = file.remote?.id,
          thumbFileId = compactThumb?.photo?.id ?: file.id,
          localPath = rawPath.takeIf { usable },
          isDownloadCompleted = usable,
          expectedSizeBytes = file.expectedSize.takeIf { it > 0L } ?: file.size.coerceAtLeast(0L),
        )
      }
      is TdApi.MessageVideo -> {
        val video = content.video
        val file = video.video
        val rawPath = file.local?.path
        val usable = file.local?.isDownloadingCompleted == true && MediaStoreScanner.isUsableLocalFile(rawPath)
        RefreshedTelegramFile(
          fileId = file.id,
          remoteFileId = file.remote?.id,
          thumbFileId = video.thumbnail?.file?.id,
          localPath = rawPath.takeIf { usable },
          isDownloadCompleted = usable,
          expectedSizeBytes = file.expectedSize.takeIf { it > 0L } ?: file.size.coerceAtLeast(0L),
        )
      }
      else -> if (expectedMediaType == null) null else null
    }
  }

  /**
   * Re-fetches the authoritative Telegram message via chatId + messageId (with bounded topic/chat
   * history fallback) to obtain a fresh session fileId when stored handles or cache paths are stale.
   */
  suspend fun refreshMediaSourceFromMessage(
    chatId: Long,
    messageId: Long,
    topicId: Long?,
    expectedMediaType: MediaType,
  ): RefreshedTelegramFile? {
    if (chatId == 0L || messageId == 0L) return null
    if (!isSessionReady()) {
      ensureConnected().getOrThrow()
    }

    val directMsg = try {
      sendFunction(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message
    } catch (t: Throwable) {
      if (t is CancellationException || t is FloodWaitException || t is SecurityException) throw t
      if (NetworkRetryPolicy.isTransientNetworkError(t, context)) throw t
      null
    }

    val resolvedMsg = directMsg ?: run {
      val historyObj = try {
        if (topicId != null && topicId > 0L) {
          sendFunction(TdApi.GetForumTopicHistory(chatId, topicId.toInt(), messageId, -1, 5))
        } else {
          sendFunction(TdApi.GetChatHistory(chatId, messageId, -1, 5, false))
        } as? TdApi.Messages
      } catch (t: Throwable) {
        if (t is CancellationException || t is FloodWaitException || t is SecurityException) throw t
        if (NetworkRetryPolicy.isTransientNetworkError(t, context)) throw t
        null
      }
      historyObj?.messages?.firstOrNull { it.id == messageId }
    }

    val extracted = resolvedMsg?.let { extractFileFromMessage(it, expectedMediaType) } ?: return null
    if (!extracted.isDownloadCompleted && extracted.fileId > 0) {
      // Ensure TDLib doesn't cling to a previously deleted/zero-byte cache record for this fileId
      val probed = runCatching { sendFunction(TdApi.GetFile(extracted.fileId)) as? TdApi.File }.getOrNull()
      if (probed?.local?.isDownloadingCompleted == true &&
        !MediaStoreScanner.isUsableLocalFile(probed.local?.path)
      ) {
        runCatching { sendFunction(TdApi.DeleteFile(probed.id)) }
      }
    }
    return extracted
  }

  private suspend fun resolveFreshMediaFile(media: MediaEntity): RefreshedTelegramFile {
    ensureConnected().getOrThrow()

    val storedFileId = media.telegramFileId
    if (storedFileId != null && storedFileId > 0) {
      val existingFile = try {
        sendFunction(TdApi.GetFile(storedFileId)) as? TdApi.File
      } catch (t: Throwable) {
        if (t is CancellationException || t is FloodWaitException || t is SecurityException) throw t
        if (NetworkRetryPolicy.isTransientNetworkError(t, context)) throw t
        null
      }

      if (existingFile != null && existingFile.id > 0) {
        val localCompleted = existingFile.local?.isDownloadingCompleted == true
        val localPath = existingFile.local?.path
        if (localCompleted && MediaStoreScanner.isUsableLocalFile(localPath)) {
          return RefreshedTelegramFile(
            fileId = existingFile.id,
            remoteFileId = existingFile.remote?.id ?: media.telegramRemoteFileId,
            thumbFileId = media.telegramThumbFileId,
            localPath = localPath,
            isDownloadCompleted = true,
            expectedSizeBytes = existingFile.expectedSize.takeIf { it > 0L } ?: existingFile.size.coerceAtLeast(0L),
          )
        }
        if (localCompleted && !MediaStoreScanner.isUsableLocalFile(localPath)) {
          // Local cache file under cacheDir/tdlib_files was cleared/corrupted after restart: reset handle!
          runCatching { sendFunction(TdApi.DeleteFile(existingFile.id)) }
        } else {
          return RefreshedTelegramFile(
            fileId = existingFile.id,
            remoteFileId = existingFile.remote?.id ?: media.telegramRemoteFileId,
            thumbFileId = media.telegramThumbFileId,
            localPath = null,
            isDownloadCompleted = false,
            expectedSizeBytes = existingFile.expectedSize.takeIf { it > 0L } ?: existingFile.size.coerceAtLeast(0L),
          )
        }
      }
    }

    val chatId = media.telegramChatId
    val messageId = media.telegramMessageId
    if (chatId != null && messageId != null) {
      val refreshedFromMessage = refreshMediaSourceFromMessage(
        chatId = chatId,
        messageId = messageId,
        topicId = media.topicId,
        expectedMediaType = media.mediaType,
      )
      if (refreshedFromMessage != null && refreshedFromMessage.fileId > 0) {
        return refreshedFromMessage
      }
    }

    val remoteId = media.telegramRemoteFileId
    if (!remoteId.isNullOrBlank()) {
      val remoteFile = try {
        sendFunction(TdApi.GetRemoteFile(remoteId, null)) as? TdApi.File
      } catch (t: Throwable) {
        if (t is CancellationException || t is FloodWaitException || t is SecurityException) throw t
        if (NetworkRetryPolicy.isTransientNetworkError(t, context)) throw t
        null
      }
      if (remoteFile != null && remoteFile.id > 0) {
        val localCompleted = remoteFile.local?.isDownloadingCompleted == true
        val localPath = remoteFile.local?.path
        if (localCompleted && !MediaStoreScanner.isUsableLocalFile(localPath)) {
          runCatching { sendFunction(TdApi.DeleteFile(remoteFile.id)) }
        }
        val usable = localCompleted && MediaStoreScanner.isUsableLocalFile(localPath)
        return RefreshedTelegramFile(
          fileId = remoteFile.id,
          remoteFileId = remoteFile.remote?.id ?: remoteId,
          thumbFileId = media.telegramThumbFileId,
          localPath = localPath.takeIf { usable },
          isDownloadCompleted = usable,
          expectedSizeBytes = remoteFile.expectedSize.takeIf { it > 0L } ?: remoteFile.size.coerceAtLeast(0L),
        )
      }
    }

    throw IllegalStateException("Không tìm thấy tệp trên Telegram (tin nhắn hoặc tệp đã bị xoá)")
  }

  /**
   * Self-healing on-demand media download:
   * 1. Guards behind TDLib readiness (`ensureConnected` -> `AuthorizationStateReady`).
   * 2. Validates existing fileId & physical disk file (>0 bytes); clears stale TDLib file state.
   * 3. Automatically re-fetches Telegram message (GetMessage / topic history) if fileId is stale
   *    or TDLib reports "File not found", updates persisted refs, and downloads cleanly.
   */
  fun downloadMediaOnDemand(
    media: MediaEntity,
    onResolvedTelegramFile: suspend (RefreshedTelegramFile) -> Unit = {},
  ): Flow<FileDownloadProgress> = flow {
    val tracker = NetworkRetryTracker()
    var lastKnownBytes = 0L
    var activeFileId = media.telegramFileId ?: 0
    var hasRefreshedFromMessage = false

    beginTransfer(markSyncing = true)
    try {
      emit(
        FileDownloadProgress(
          fileId = activeFileId,
          downloadedBytes = 0L,
          totalBytes = media.sizeBytes,
          isCompleted = false,
          localPath = null,
          statusMessageVi = if (isSessionReady()) {
            "Đang chuẩn bị tải tệp từ Telegram..."
          } else {
            "Đang kết nối lại phiên Telegram..."
          },
        )
      )

      val initialTarget = resolveFreshMediaFile(media)
      activeFileId = initialTarget.fileId
      if (initialTarget.fileId != media.telegramFileId ||
        initialTarget.remoteFileId != media.telegramRemoteFileId ||
        initialTarget.thumbFileId != media.telegramThumbFileId
      ) {
        onResolvedTelegramFile(initialTarget)
      }
      if (initialTarget.isDownloadCompleted && MediaStoreScanner.isUsableLocalFile(initialTarget.localPath)) {
        val completedPath = initialTarget.localPath!!
        val finalSize = File(completedPath).length().coerceAtLeast(initialTarget.expectedSizeBytes)
        emit(
          FileDownloadProgress(
            fileId = initialTarget.fileId,
            downloadedBytes = finalSize,
            totalBytes = finalSize,
            isCompleted = true,
            localPath = completedPath,
          )
        )
        return@flow
      }

      while (true) {
        try {
          ensureConnected().getOrThrow()

          val c = client ?: throw NetworkInterruptedException("Chưa kết nối Telegram")
          if (!NetworkRetryPolicy.isNetworkAvailable(context)) {
            throw NetworkInterruptedException("Không có kết nối mạng")
          }

          val completed = awaitSingleDownloadAttempt(
            c = c,
            fileId = activeFileId,
            initialBytes = lastKnownBytes,
            onProgress = { prog ->
              if (prog.downloadedBytes > lastKnownBytes) {
                lastKnownBytes = prog.downloadedBytes
                tracker.reset()
              }
              emit(prog)
            },
          )
          if (completed != null) {
            emit(completed)
            return@flow
          }
        } catch (t: Throwable) {
          if (t is CancellationException) throw t
          if (t is FloodWaitException) throw t

          val rawLower = t.message.orEmpty().lowercase()
          val isMissingOrStaleFile = t is StaleOrMissingTelegramFileException ||
            rawLower.contains("file not found") ||
            rawLower.contains("không tìm thấy tệp")
          if (isMissingOrStaleFile) {
            if (!hasRefreshedFromMessage) {
              hasRefreshedFromMessage = true
              lastKnownBytes = 0L
              emit(
                FileDownloadProgress(
                  fileId = activeFileId,
                  downloadedBytes = 0L,
                  totalBytes = media.sizeBytes,
                  isCompleted = false,
                  localPath = null,
                  statusMessageVi = "Đang làm mới liên kết tệp từ Telegram...",
                )
              )
              if (activeFileId > 0) {
                runCatching { sendFunction(TdApi.DeleteFile(activeFileId)) }
              }
              val refreshed = if (media.telegramChatId != null && media.telegramMessageId != null) {
                refreshMediaSourceFromMessage(
                  chatId = media.telegramChatId,
                  messageId = media.telegramMessageId,
                  topicId = media.topicId,
                  expectedMediaType = media.mediaType,
                )
              } else {
                null
              } ?: media.telegramRemoteFileId?.takeIf { it.isNotBlank() }?.let { remoteId ->
                runCatching { sendFunction(TdApi.GetRemoteFile(remoteId, null)) as? TdApi.File }
                  .getOrNull()
                  ?.takeIf { it.id > 0 }
                  ?.let { rf ->
                    RefreshedTelegramFile(
                      fileId = rf.id,
                      remoteFileId = rf.remote?.id ?: remoteId,
                      thumbFileId = media.telegramThumbFileId,
                      localPath = null,
                      isDownloadCompleted = false,
                      expectedSizeBytes = rf.expectedSize.takeIf { it > 0L } ?: rf.size.coerceAtLeast(0L),
                    )
                  }
              }

              if (refreshed != null && refreshed.fileId > 0) {
                if (refreshed.fileId != activeFileId) {
                  runCatching { sendFunction(TdApi.DeleteFile(refreshed.fileId)) }
                }
                activeFileId = refreshed.fileId
                onResolvedTelegramFile(refreshed)
                if (refreshed.isDownloadCompleted && MediaStoreScanner.isUsableLocalFile(refreshed.localPath)) {
                  val path = refreshed.localPath!!
                  val len = File(path).length().coerceAtLeast(refreshed.expectedSizeBytes)
                  emit(
                    FileDownloadProgress(
                      fileId = refreshed.fileId,
                      downloadedBytes = len,
                      totalBytes = len,
                      isCompleted = true,
                      localPath = path,
                    )
                  )
                  return@flow
                }
                continue
              }
            }
            throw IllegalStateException("Không tìm thấy tệp trên Telegram (tin nhắn hoặc tệp đã bị xoá)")
          }

          if (!NetworkRetryPolicy.isTransientNetworkError(t, context)) {
            throw t
          }
          when (val decision = tracker.onFailure()) {
            is RetryDecision.WaitAndRetry -> {
              emit(
                FileDownloadProgress(
                  fileId = activeFileId,
                  downloadedBytes = lastKnownBytes,
                  totalBytes = media.sizeBytes,
                  isCompleted = false,
                  localPath = null,
                  statusMessageVi = "Mất kết nối, đang thử lại sau 8 giây (lần ${decision.attemptNumber}/2)...",
                )
              )
              delay(decision.delayMs)
              if (!isSessionReady()) {
                runCatching { ensureConnected(forceReconnect = true) }
              }
            }
            RetryDecision.StopAndNotify -> {
              networkAlertCoordinator.reportFinalFailure(NetworkOperationType.DOWNLOAD)
              throw NetworkInterruptedException("Không thể tải ảnh/video từ Telegram.")
            }
          }
        }
      }
    } finally {
      withContext(NonCancellable) {
        endTransfer()
      }
    }
  }.flowOn(Dispatchers.IO)

  /**
   * Reuses the persistent foreground TDLib session on demand to download a tapped photo or video,
   * detects genuine network interruptions / stalls (8s x 2 retry + 25s stall detection),
   * and keeps TDLib alive while in foreground (or closes if backgrounded once finished).
   */
  fun downloadFileOnDemand(fileId: Int): Flow<FileDownloadProgress> = flow {
    val tracker = NetworkRetryTracker()
    var lastKnownBytes = 0L

    beginTransfer(markSyncing = true)
    try {
      while (true) {
        try {
          ensureConnected().getOrThrow()

          val c = client ?: throw NetworkInterruptedException("Chưa kết nối Telegram")
          if (!NetworkRetryPolicy.isNetworkAvailable(context)) {
            throw NetworkInterruptedException("Không có kết nối mạng")
          }

          val completed = awaitSingleDownloadAttempt(
            c = c,
            fileId = fileId,
            initialBytes = lastKnownBytes,
            onProgress = { prog ->
              if (prog.downloadedBytes > lastKnownBytes) {
                lastKnownBytes = prog.downloadedBytes
                tracker.reset()
              }
              emit(prog)
            },
          )
          if (completed != null) {
            emit(completed)
            return@flow
          }
        } catch (t: Throwable) {
          if (t is CancellationException) throw t
          if (t is FloodWaitException || !NetworkRetryPolicy.isTransientNetworkError(t, context)) {
            throw t
          }
          when (val decision = tracker.onFailure()) {
            is RetryDecision.WaitAndRetry -> {
              emit(
                FileDownloadProgress(
                  fileId = fileId,
                  downloadedBytes = lastKnownBytes,
                  totalBytes = 0L,
                  isCompleted = false,
                  localPath = null,
                  statusMessageVi = "Mất kết nối, đang thử lại sau 8 giây (lần ${decision.attemptNumber}/2)...",
                )
              )
              delay(decision.delayMs)
              if (!isSessionReady()) {
                runCatching { ensureConnected(forceReconnect = true) }
              }
            }
            RetryDecision.StopAndNotify -> {
              networkAlertCoordinator.reportFinalFailure(NetworkOperationType.DOWNLOAD)
              throw NetworkInterruptedException("Không thể tải ảnh/video từ Telegram.")
            }
          }
        }
      }
    } finally {
      withContext(NonCancellable) {
        endTransfer()
      }
    }
  }.flowOn(Dispatchers.IO)

  private suspend fun awaitSingleDownloadAttempt(
    c: Client,
    fileId: Int,
    initialBytes: Long,
    onProgress: suspend (FileDownloadProgress) -> Unit,
  ): FileDownloadProgress? = coroutineScope {
    val eventChannel = Channel<Result<FileDownloadProgress>>(Channel.BUFFERED)
    var lastProgressAtMs = System.currentTimeMillis()
    var lastSeenBytes = initialBytes

    fun emitFileState(fileObj: TdApi.File) {
      val downloaded = fileObj.local?.downloadedSize ?: 0L
      if (downloaded > lastSeenBytes) {
        lastSeenBytes = downloaded
        lastProgressAtMs = System.currentTimeMillis()
      }
      val reportedCompleted = fileObj.local?.isDownloadingCompleted == true
      val rawPath = fileObj.local?.path
      if (reportedCompleted && !MediaStoreScanner.isUsableLocalFile(rawPath)) {
        eventChannel.trySend(
          Result.failure(StaleOrMissingTelegramFileException("File not found"))
        )
        return
      }
      val verifiedPath = rawPath?.takeIf { reportedCompleted && MediaStoreScanner.isUsableLocalFile(it) }
      eventChannel.trySend(
        Result.success(
          FileDownloadProgress(
            fileId = fileObj.id,
            downloadedBytes = downloaded,
            totalBytes = fileObj.expectedSize,
            isCompleted = verifiedPath != null,
            localPath = verifiedPath,
          )
        )
      )
    }

    val collectorJob = launch {
      fileUpdates.collect { updatedFile ->
        if (updatedFile.id == fileId) {
          emitFileState(updatedFile)
        }
      }
    }

    val watchdogJob = launch {
      while (true) {
        delay(NetworkRetryPolicy.PROGRESS_CHECK_INTERVAL_MS)
        if (!NetworkRetryPolicy.isNetworkAvailable(context) ||
          tdConnectionState is TdApi.ConnectionStateWaitingForNetwork
        ) {
          eventChannel.trySend(Result.failure(NetworkInterruptedException("Mất kết nối mạng")))
          break
        }
        val elapsed = System.currentTimeMillis() - lastProgressAtMs
        if (elapsed >= NetworkRetryPolicy.STALL_TIMEOUT_MS) {
          eventChannel.trySend(Result.failure(StalledTransferException("Tải tệp bị gián đoạn")))
          break
        }
      }
    }

    val downloadReq = TdApi.DownloadFile(fileId, 16, 0, 0, true)
    TelegramPolicyGuard.assertAllowedFunction(downloadReq)
    c.send(downloadReq) { obj ->
      if (obj is TdApi.Error) {
        val waitSec = TelegramPolicyGuard.parseFloodWaitSeconds(obj.message)
        if (waitSec != null || obj.code == 429) {
          val safeWait = (waitSec ?: 30).coerceAtLeast(1)
          val unlockAt = TelegramPolicyGuard.computeFloodWaitUnlockAtEpochMs(safeWait)
          val reason = "Telegram giới hạn tốc độ ($safeWait giây)"
          internalScope.launch { recordFloodWait(safeWait, unlockAt, reason) }
          eventChannel.trySend(
            Result.failure(FloodWaitException(safeWait, unlockAt, reason))
          )
        } else if (obj.message?.contains("file not found", ignoreCase = true) == true) {
          eventChannel.trySend(
            Result.failure(StaleOrMissingTelegramFileException(obj.message ?: "File not found"))
          )
        } else {
          val translated = TelegramPolicyGuard.translateAuthError(obj.message)
          val ex: Throwable = if (NetworkRetryPolicy.isTransientNetworkError(IllegalStateException(obj.message), context)) {
            NetworkInterruptedException(translated)
          } else {
            IllegalStateException(translated)
          }
          eventChannel.trySend(Result.failure(ex))
        }
      } else if (obj is TdApi.File) {
        emitFileState(obj)
      }
    }

    try {
      for (event in eventChannel) {
        val prog = event.getOrThrow()
        if (prog.isCompleted && prog.localPath != null) {
          return@coroutineScope prog
        }
        onProgress(prog)
      }
      null
    } finally {
      collectorJob.cancel()
      watchdogJob.cancel()
      eventChannel.close()
    }
  }

  /**
   * Executes a TDLib function with policy verification, in-flight request tracking, timeout,
   * dynamic FLOOD_WAIT capture (without reconnecting), and single automatic client recovery
   * if the underlying client was closed unexpectedly.
   */
  suspend fun sendFunction(
    function: TdApi.Function<*>,
    timeoutMs: Long = 30_000L,
  ): TdApi.Object {
    TelegramPolicyGuard.assertAllowedFunction(function)
    return try {
      sendSuspending(function, timeoutMs)
    } catch (t: Throwable) {
      if (t is CancellationException || t is FloodWaitException || t is SecurityException) {
        throw t
      }
      val msg = t.message.orEmpty().lowercase()
      val shouldRecoverClient = client == null ||
        msg.contains("client is closed") ||
        msg.contains("tdlib chưa sẵn sàng")
      if (shouldRecoverClient &&
        function !is TdApi.Close &&
        function !is TdApi.LogOut &&
        function !is TdApi.OptimizeStorage
      ) {
        ensureConnected(forceReconnect = true).getOrThrow()
        sendSuspending(function, timeoutMs)
      } else {
        throw t
      }
    }
  }

  private suspend fun sendSuspending(
    function: TdApi.Function<*>,
    timeoutMs: Long = 30_000L,
  ): TdApi.Object {
    TelegramPolicyGuard.assertAllowedFunction(function)
    val c = client ?: throw NetworkInterruptedException("TDLib chưa sẵn sàng")
    val reqId = requestIdGenerator.getAndIncrement()
    val deferred = CompletableDeferred<TdApi.Object>()
    inFlightRequests[reqId] = deferred

    val result = try {
      c.send(function) { obj ->
        val pending = inFlightRequests.remove(reqId)
        pending?.complete(obj)
      }
      withTimeoutOrNull(timeoutMs) {
        deferred.await()
      } ?: throw NetworkInterruptedException("Hết thời gian chờ phản hồi từ Telegram")
    } finally {
      inFlightRequests.remove(reqId)
    }

    if (result is TdApi.Error) {
      val floodSec = TelegramPolicyGuard.parseFloodWaitSeconds(result.message)
      if (floodSec != null || result.code == 429) {
        val waitSec = (floodSec ?: 30).coerceAtLeast(1)
        val unlockAt = TelegramPolicyGuard.computeFloodWaitUnlockAtEpochMs(waitSec)
        val reason = "Telegram yêu cầu chờ $waitSec giây (FLOOD_WAIT_$waitSec)"
        recordFloodWait(waitSec, unlockAt, reason)
        throw FloodWaitException(
          retryAfterSeconds = waitSec,
          unlockAtEpochMs = unlockAt,
          message = reason,
        )
      }
      val translated = TelegramPolicyGuard.translateAuthError(result.message)
      if (NetworkRetryPolicy.isTransientNetworkError(IllegalStateException(result.message), context)) {
        throw NetworkInterruptedException(translated)
      }
      throw IllegalStateException(translated)
    }
    return result
  }
}
