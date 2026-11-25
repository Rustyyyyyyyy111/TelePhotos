package com.laughing.telephotos.data.telegram

import android.content.Context
import android.os.Build
import com.laughing.telephotos.BuildConfig
import com.laughing.telephotos.core.CacheCleanupManager
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.NetworkAlertCoordinator
import com.laughing.telephotos.core.NetworkInterruptedException
import com.laughing.telephotos.core.NetworkOperationType
import com.laughing.telephotos.core.NetworkRetryPolicy
import com.laughing.telephotos.core.NetworkRetryTracker
import com.laughing.telephotos.core.RetryDecision
import com.laughing.telephotos.core.SettingsPreferences
import com.laughing.telephotos.core.StalledTransferException
import com.laughing.telephotos.core.TelegramCaptionMetadata
import com.laughing.telephotos.data.local.MediaType
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
import kotlinx.coroutines.suspendCancellableCoroutine
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

class TdLibManager(
  private val context: Context,
  private val settingsPreferences: SettingsPreferences,
  private val networkAlertCoordinator: NetworkAlertCoordinator = NetworkAlertCoordinator(),
) {

  private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lifecycleMutex = Mutex()
  private val activeShortLivedActions = AtomicInteger(0)

  private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Uninitialized)
  val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

  private val _connectionStatus = MutableStateFlow(TelegramConnectionStatus.DISCONNECTED_IDLE)
  val connectionStatus: StateFlow<TelegramConnectionStatus> = _connectionStatus.asStateFlow()

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

  /**
   * Initializes TDLib strictly on-demand (never called at Application startup).
   */
  suspend fun ensureInitializedOnDemand() {
    if (client != null && _authState.value !is TelegramAuthState.WaitApiCredentials) {
      return
    }

    val settings = settingsPreferences.getCurrentSettings()
    val resolved = resolveCredentials(settings.customTelegramApiId, settings.customTelegramApiHash)
    if (resolved == null) {
      _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
      _authState.value = TelegramAuthState.WaitApiCredentials()
      return
    }

    activeApiId = resolved.first
    activeApiHash = resolved.second
    _connectionStatus.value = TelegramConnectionStatus.CONNECTING
    createClientIfNeeded()
  }

  fun isSessionReady(): Boolean =
    client != null && _authState.value is TelegramAuthState.Ready &&
      tdConnectionState !is TdApi.ConnectionStateWaitingForNetwork

  /**
   * Opens a short-lived TDLib session for a single user-triggered action.
   */
  suspend fun acquireShortLivedSession(timeoutMs: Long = 20_000L): Result<TelegramAuthState.Ready> {
    activeShortLivedActions.incrementAndGet()
    lifecycleMutex.withLock {
      ensureInitializedOnDemand()
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
        _connectionStatus.value = TelegramConnectionStatus.CONNECTED_ACTIVE
        Result.success(terminalState)
      }
      is TelegramAuthState.WaitApiCredentials -> {
        releaseShortLivedSession(forceClose = false)
        Result.failure(IllegalStateException("Chưa cấu hình API ID / API Hash Telegram trong Setting."))
      }
      is TelegramAuthState.WaitPhoneNumber,
      is TelegramAuthState.WaitCode,
      is TelegramAuthState.WaitPassword -> {
        releaseShortLivedSession(forceClose = false)
        Result.failure(IllegalStateException("Phiên Telegram chưa đăng nhập. Vui lòng vào Setting để đăng nhập."))
      }
      is TelegramAuthState.Error -> {
        releaseShortLivedSession(forceClose = false)
        Result.failure(IllegalStateException(terminalState.messageVi))
      }
      null, TelegramAuthState.Uninitialized -> {
        releaseShortLivedSession(forceClose = false)
        Result.failure(IllegalStateException("Hết thời gian kết nối tới Telegram. Vui lòng thử lại."))
      }
    }
  }

  suspend fun releaseShortLivedSession(forceClose: Boolean = false) {
    val remaining = if (forceClose) {
      activeShortLivedActions.set(0)
      0
    } else {
      activeShortLivedActions.decrementAndGet().coerceAtLeast(0)
    }
    if (remaining == 0 || forceClose) {
      closeConnectionIdle(preserveWaitApiState = false)
    }
  }

  /**
   * Executes a single Telegram action inside a short-lived TDLib connection
   * and immediately closes TDLib via TdApi.Close() when finished, failed, or cancelled.
   */
  suspend fun <T> withShortLivedConnection(block: suspend () -> T): T {
    acquireShortLivedSession().getOrThrow()
    return try {
      block()
    } finally {
      withContext(NonCancellable) {
        releaseShortLivedSession(forceClose = false)
      }
    }
  }

  /**
   * Immediately closes the TDLib client to free native threads, sockets, and RAM on low-end devices.
   * Preserves local DataStore login profile so subsequent actions reconnect without re-entering OTP.
   */
  suspend fun closeConnectionIdle(preserveWaitApiState: Boolean = false) {
    lifecycleMutex.withLock {
      val currentClient = client
      if (currentClient == null) {
        _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
        if (!preserveWaitApiState && _authState.value !is TelegramAuthState.WaitApiCredentials) {
          _authState.value = TelegramAuthState.Uninitialized
        }
        return
      }

      _connectionStatus.value = TelegramConnectionStatus.DISCONNECTING
      // Trim TDLib file database before closing if authenticated and active
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
            )
          )
        }
      }
      val deferred = CompletableDeferred<Unit>()
      closedDeferred = deferred
      runCatching {
        currentClient.send(TdApi.Close()) { }
      }
      withTimeoutOrNull(1200L) {
        deferred.await()
      }
      closedDeferred = null
      client = null
      waitingForParameters = false
      if (!preserveWaitApiState) {
        _authState.value = TelegramAuthState.Uninitialized
      }
      _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
      runCatching { CacheCleanupManager.enforceCacheLimitLru(context) }
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
      activeApiId = parsedId
      activeApiHash = cleanHash
      _connectionStatus.value = TelegramConnectionStatus.CONNECTING

      if (client == null) {
        createClientIfNeeded()
      } else if (waitingForParameters) {
        applyTdlibParameters()
      } else {
        closeConnectionIdle(preserveWaitApiState = false)
        _connectionStatus.value = TelegramConnectionStatus.CONNECTING
        createClientIfNeeded()
      }
      Unit
    }

  suspend fun switchApiCredentials() {
    val settings = settingsPreferences.getCurrentSettings()
    val currentId = if (settings.customTelegramApiId > 0) settings.customTelegramApiId.toString() else ""
    val currentHash = settings.customTelegramApiHash
    closeConnectionIdle(preserveWaitApiState = true)
    _authState.value = TelegramAuthState.WaitApiCredentials(
      initialApiId = currentId,
      initialApiHash = currentHash,
    )
  }

  fun returnToPhoneNumberStep() {
    _authState.value = TelegramAuthState.WaitPhoneNumber(initialPhone = lastPhoneEntered)
  }

  private fun createClientIfNeeded() {
    if (client != null) return
    val created = runCatching {
      Client.create(
        { obj -> handleUpdate(obj) },
        { err ->
          _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
          _authState.value = TelegramAuthState.Error(
            TelegramPolicyGuard.translateAuthError(err.message)
          )
        },
        { err ->
          _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
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
          _authState.value = TelegramAuthState.WaitApiCredentials()
        }
      }
      is TdApi.AuthorizationStateWaitPhoneNumber -> {
        waitingForParameters = false
        _connectionStatus.value = TelegramConnectionStatus.CONNECTED_ACTIVE
        _authState.value = TelegramAuthState.WaitPhoneNumber(initialPhone = lastPhoneEntered)
      }
      is TdApi.AuthorizationStateWaitCode -> {
        waitingForParameters = false
        _connectionStatus.value = TelegramConnectionStatus.CONNECTED_ACTIVE
        _authState.value = TelegramAuthState.WaitCode(lastPhoneEntered)
      }
      is TdApi.AuthorizationStateWaitPassword -> {
        waitingForParameters = false
        _connectionStatus.value = TelegramConnectionStatus.CONNECTED_ACTIVE
        _authState.value = TelegramAuthState.WaitPassword(state.passwordHint ?: "")
      }
      is TdApi.AuthorizationStateReady -> {
        waitingForParameters = false
        fetchCurrentUser()
      }
      is TdApi.AuthorizationStateClosed -> {
        client = null
        waitingForParameters = false
        closedDeferred?.complete(Unit)
        _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
        if (_authState.value !is TelegramAuthState.WaitApiCredentials) {
          _authState.value = TelegramAuthState.Uninitialized
        }
      }
      else -> Unit
    }
  }

  private fun applyTdlibParameters() {
    if (activeApiId <= 0 || activeApiHash.isBlank()) {
      _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
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

    client?.send(params) { result ->
      if (result is TdApi.Error) {
        _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
        _authState.value = TelegramAuthState.Error(
          TelegramPolicyGuard.translateAuthError(result.message)
        )
      }
    }
  }

  private fun fetchCurrentUser() {
    val c = client ?: return
    c.send(TdApi.GetMe()) { obj ->
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
          _connectionStatus.value = TelegramConnectionStatus.CONNECTED_ACTIVE
          _authState.value = TelegramAuthState.Ready(
            userId = obj.id,
            displayName = name,
            phoneNumber = phone,
          )
        }
        is TdApi.Error -> {
          _connectionStatus.value = TelegramConnectionStatus.DISCONNECTED_IDLE
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
    closeConnectionIdle(preserveWaitApiState = false)
    Unit
  }

  suspend fun loadAllowedTargets(): List<TelegramCandidateTarget> {
    if (client == null || _authState.value !is TelegramAuthState.Ready) return emptyList()
    val chatsObj = sendSuspending(TdApi.GetChats(TdApi.ChatListMain(), 100)) as? TdApi.Chats
      ?: return emptyList()

    val results = mutableListOf<TelegramCandidateTarget>()
    for (chatId in chatsObj.chatIds) {
      val chat = runCatching { sendSuspending(TdApi.GetChat(chatId)) as? TdApi.Chat }.getOrNull()
        ?: continue
      var supergroup: TdApi.Supergroup? = null
      var basicGroup: TdApi.BasicGroup? = null
      when (val type = chat.type) {
        is TdApi.ChatTypeSupergroup -> {
          supergroup = runCatching {
            sendSuspending(TdApi.GetSupergroup(type.supergroupId)) as? TdApi.Supergroup
          }.getOrNull()
        }
        is TdApi.ChatTypeBasicGroup -> {
          basicGroup = runCatching {
            sendSuspending(TdApi.GetBasicGroup(type.basicGroupId)) as? TdApi.BasicGroup
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
    val topicsObj = sendSuspending(TdApi.GetForumTopics(chatId, "", 0, 0L, 0, 100))
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
    if (client == null || _authState.value !is TelegramAuthState.Ready) return null
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
    val result = sendSuspending(req) as? TdApi.Messages ?: return null
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
    val info = sendSuspending(req) as TdApi.ForumTopicInfo
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
        sendSuspending(TdApi.GetMessage(chatId, existingPendingMessageId)) as? TdApi.Message
      }.getOrNull()

      if (existing != null) {
        when (existing.sendingState) {
          null -> {
            // Already finished uploading during/after recovery -> zero duplicate!
            return messageToSentResult(existing)
          }
          is TdApi.MessageSendingStatePending -> {
            targetPendingMsg = existing
          }
          is TdApi.MessageSendingStateFailed -> {
            val failedState = existing.sendingState as TdApi.MessageSendingStateFailed
            if (failedState.canRetry) {
              val resent = runCatching {
                sendSuspending(
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
      val content = TdApi.InputMessageDocument().apply {
        document = TdApi.InputFileLocal(localFilePath)
        thumbnail = if (!thumbnailPath.isNullOrBlank() && File(thumbnailPath).exists()) {
          TdApi.InputThumbnail(
            TdApi.InputFileLocal(thumbnailPath),
            thumbnailWidth.coerceAtLeast(0),
            thumbnailHeight.coerceAtLeast(0),
          )
        } else {
          null
        }
        this.caption = TdApi.FormattedText(caption, emptyArray())
        disableContentTypeDetection = true
      }

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
        this.inputMessageContent = content
      }
      sendSuspending(req) as TdApi.Message
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
    val doc = (msg.content as? TdApi.MessageDocument)?.document
    val resolvedTopicId = (msg.topicId as? TdApi.MessageTopicForum)?.forumTopicId?.toLong()
    return SentDocumentResult(
      chatId = msg.chatId,
      messageId = msg.id,
      fileId = doc?.document?.id,
      remoteFileId = doc?.document?.remote?.id,
      messageThreadId = resolvedTopicId,
      thumbFileId = doc?.thumbnail?.file?.id,
    )
  }

  private suspend fun awaitPendingMessageUpload(
    initialMsg: TdApi.Message,
    onProgressMade: () -> Unit,
  ): SentDocumentResult = coroutineScope {
    val doc = (initialMsg.content as? TdApi.MessageDocument)?.document
    val fileId = doc?.document?.id
    var lastUploadedBytes = doc?.document?.remote?.uploadedSize ?: 0L
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
                FloodWaitException(floodSec ?: 30, "Telegram yêu cầu chờ ${floodSec ?: 30} giây")
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
      // Double check via GetMessage in case update fired just before collector subscribed
      val currentCheck = runCatching {
        sendSuspending(TdApi.GetMessage(initialMsg.chatId, initialMsg.id)) as? TdApi.Message
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
    limit: Int = 30,
  ): List<ScannedTelegramMedia> {
    if (client == null || _authState.value !is TelegramAuthState.Ready) return emptyList()

    val messagesObj = if (messageThreadId != null && messageThreadId != 0L) {
      sendSuspending(TdApi.GetForumTopicHistory(chatId, messageThreadId.toInt(), 0L, 0, limit))
    } else {
      sendSuspending(TdApi.GetChatHistory(chatId, 0L, 0, limit, false))
    } as? TdApi.Messages ?: return emptyList()

    val scanned = mutableListOf<ScannedTelegramMedia>()
    for (msg in messagesObj.messages) {
      val content = msg.content
      if (content is TdApi.MessageDocument) {
        val doc = content.document
        val parsed = TelegramCaptionMetadata.parseFromCaption(content.caption?.text)
        val mime = doc.mimeType ?: "application/octet-stream"
        val mediaType = when {
          parsed?.mediaKind == "VIDEO" -> MediaType.VIDEO
          mime.startsWith("video/", ignoreCase = true) -> MediaType.VIDEO
          else -> MediaType.PHOTO
        }
        val fallbackHash = doc.document.remote?.uniqueId?.takeIf { it.isNotBlank() }
          ?: "tg_${msg.chatId}_${msg.id}"
        val msgTopicId = (msg.topicId as? TdApi.MessageTopicForum)?.forumTopicId?.toLong()

        scanned.add(
          ScannedTelegramMedia(
            contentHash = parsed?.contentHash ?: fallbackHash,
            chatId = msg.chatId,
            messageId = msg.id,
            topicId = parsed?.topicId ?: msgTopicId,
            fileId = doc.document.id,
            remoteFileId = doc.document.remote?.id,
            thumbFileId = doc.thumbnail?.file?.id,
            fileName = parsed?.fileName?.ifBlank { doc.fileName } ?: doc.fileName,
            mimeType = mime,
            mediaType = mediaType,
            sizeBytes = parsed?.sizeBytes?.takeIf { it > 0L } ?: doc.document.size,
            durationSec = parsed?.durationSec ?: 0,
            takenAtEpochMs = parsed?.takenAtEpochMs ?: (msg.date * 1000L),
            isTrashed = parsed?.isTrashed ?: false,
          )
        )
      }
    }
    return scanned
  }

  /**
   * Quickly fetches a tiny (~15KB) Telegram document preview thumbnail synchronously while a
   * short-lived TDLib session is already open so cloud-only grids render smoothly offline.
   */
  suspend fun downloadThumbnailQuick(fileId: Int): String? {
    if (fileId <= 0 || client == null || _authState.value !is TelegramAuthState.Ready) return null
    val result = withTimeoutOrNull(4_000L) {
      runCatching {
        sendSuspending(TdApi.DownloadFile(fileId, 32, 0L, 0L, true)) as? TdApi.File
      }.getOrNull()
    } ?: return null
    val completed = result.local?.isDownloadingCompleted == true
    return result.local?.path?.takeIf { completed && it.isNotBlank() && File(it).exists() }
  }

  /**
   * Opens a short-lived TDLib session on demand to download a tapped photo or video,
   * automatically detects network interruptions / stalls, retries twice with an 8s wait,
   * stops & alerts on the 3rd consecutive failure, and closes TDLib immediately afterward.
   */
  fun downloadFileOnDemand(fileId: Int): Flow<FileDownloadProgress> = flow {
    val tracker = NetworkRetryTracker()
    var lastKnownBytes = 0L

    while (true) {
      var acquired = false
      try {
        acquireShortLivedSession().getOrThrow()
        acquired = true

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
        if (acquired) {
          withContext(NonCancellable) {
            releaseShortLivedSession(forceClose = true)
          }
          acquired = false
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
          }
          RetryDecision.StopAndNotify -> {
            networkAlertCoordinator.reportFinalFailure(NetworkOperationType.DOWNLOAD)
            throw NetworkInterruptedException("Không thể tải ảnh/video từ Telegram.")
          }
        }
      } finally {
        if (acquired) {
          withContext(NonCancellable) {
            releaseShortLivedSession(forceClose = false)
          }
        }
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

    val collectorJob = launch {
      fileUpdates.collect { updatedFile ->
        if (updatedFile.id == fileId) {
          val downloaded = updatedFile.local?.downloadedSize ?: 0L
          if (downloaded > lastSeenBytes) {
            lastSeenBytes = downloaded
            lastProgressAtMs = System.currentTimeMillis()
          }
          val completed = updatedFile.local?.isDownloadingCompleted == true
          val localPath = updatedFile.local?.path?.takeIf { completed && it.isNotBlank() }
          eventChannel.trySend(
            Result.success(
              FileDownloadProgress(
                fileId = updatedFile.id,
                downloadedBytes = downloaded,
                totalBytes = updatedFile.expectedSize,
                isCompleted = completed,
                localPath = localPath,
              )
            )
          )
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

    c.send(TdApi.DownloadFile(fileId, 16, 0, 0, true)) { obj ->
      if (obj is TdApi.Error) {
        val waitSec = TelegramPolicyGuard.parseFloodWaitSeconds(obj.message)
        if (waitSec != null) {
          eventChannel.trySend(
            Result.failure(FloodWaitException(waitSec, "Telegram giới hạn tốc độ ($waitSec giây)"))
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
        val downloaded = obj.local?.downloadedSize ?: 0L
        if (downloaded > lastSeenBytes) {
          lastSeenBytes = downloaded
          lastProgressAtMs = System.currentTimeMillis()
        }
        val completed = obj.local?.isDownloadingCompleted == true
        val localPath = obj.local?.path?.takeIf { completed && it.isNotBlank() }
        eventChannel.trySend(
          Result.success(
            FileDownloadProgress(
              fileId = obj.id,
              downloadedBytes = downloaded,
              totalBytes = obj.expectedSize,
              isCompleted = completed,
              localPath = localPath,
            )
          )
        )
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

  private suspend fun sendSuspending(function: TdApi.Function<*>): TdApi.Object =
    suspendCancellableCoroutine { cont ->
      val c = client
      if (c == null) {
        cont.resumeWithException(IllegalStateException("TDLib chưa sẵn sàng"))
        return@suspendCancellableCoroutine
      }
      c.send(function) { result ->
        if (!cont.isActive) return@send
        if (result is TdApi.Error) {
          val floodSec = TelegramPolicyGuard.parseFloodWaitSeconds(result.message)
          if (floodSec != null || result.code == 429) {
            val waitSec = floodSec ?: 30
            cont.resumeWithException(
              FloodWaitException(
                retryAfterSeconds = waitSec,
                message = "Telegram yêu cầu chờ $waitSec giây (FLOOD_WAIT_$waitSec)",
              )
            )
          } else {
            cont.resumeWithException(
              IllegalStateException(TelegramPolicyGuard.translateAuthError(result.message))
            )
          }
        } else {
          cont.resume(result)
        }
      }
    }
}
