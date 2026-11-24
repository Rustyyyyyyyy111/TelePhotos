package com.laughing.telephotos.data.telegram

import android.content.Context
import android.os.Build
import com.laughing.telephotos.BuildConfig
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.SettingsPreferences
import com.laughing.telephotos.core.TelegramCaptionMetadata
import com.laughing.telephotos.data.local.MediaType
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

class TdLibManager(
  private val context: Context,
  private val settingsPreferences: SettingsPreferences,
) {

  private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lifecycleMutex = Mutex()
  private val activeShortLivedActions = AtomicInteger(0)

  private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Uninitialized)
  val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

  private val _connectionStatus = MutableStateFlow(TelegramConnectionStatus.DISCONNECTED_IDLE)
  val connectionStatus: StateFlow<TelegramConnectionStatus> = _connectionStatus.asStateFlow()

  private val fileUpdates = MutableSharedFlow<TdApi.File>(extraBufferCapacity = 64)
  private val lastTopicMutationMs = AtomicLong(0L)

  @Volatile private var client: Client? = null
  @Volatile private var cachedUserId: Long = 0L
  @Volatile private var lastPhoneEntered: String = "+84"
  @Volatile private var activeApiId: Int = 0
  @Volatile private var activeApiHash: String = ""
  @Volatile private var waitingForParameters: Boolean = false
  @Volatile private var closedDeferred: CompletableDeferred<Unit>? = null

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
      is TdApi.UpdateFile -> fileUpdates.tryEmit(obj.file)
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
      val supergroup = when (val type = chat.type) {
        is TdApi.ChatTypeSupergroup -> {
          runCatching { sendSuspending(TdApi.GetSupergroup(type.supergroupId)) as? TdApi.Supergroup }
            .getOrNull()
        }
        else -> null
      }
      TelegramPolicyGuard.classifyAllowedTarget(chat, cachedUserId, supergroup)?.let {
        results.add(it)
      }
    }
    return results.distinctBy { it.chatId }
  }

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

  suspend fun findOrCreateTrashTopic(chatId: Long): Long {
    val existing = getForumTopics(chatId).firstOrNull {
      it.title.equals(TelegramPolicyGuard.TRASH_TOPIC_TITLE, ignoreCase = true)
    }
    if (existing != null) return existing.messageThreadId

    val created = createForumTopicRateLimited(
      chatId = chatId,
      title = TelegramPolicyGuard.TRASH_TOPIC_TITLE,
      iconColor = 0xFB6F5F,
    )
    return created.messageThreadId
  }

  suspend fun createForumTopicRateLimited(
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

  suspend fun sendDocumentOriginal(
    chatId: Long,
    messageThreadId: Long?,
    localFilePath: String,
    caption: String,
  ): SentDocumentResult {
    val content = TdApi.InputMessageDocument().apply {
      document = TdApi.InputFileLocal(localFilePath)
      thumbnail = null
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

    val initialMsg = sendSuspending(req) as TdApi.Message
    val doc = (initialMsg.content as? TdApi.MessageDocument)?.document
    val resolvedTopicId = (initialMsg.topicId as? TdApi.MessageTopicForum)?.forumTopicId?.toLong()
    return SentDocumentResult(
      chatId = initialMsg.chatId,
      messageId = initialMsg.id,
      fileId = doc?.document?.id,
      remoteFileId = doc?.document?.remote?.id,
      messageThreadId = resolvedTopicId,
    )
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
            sizeBytes = doc.document.size,
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
   * Opens a short-lived TDLib session on demand to download a tapped photo or video,
   * and immediately closes TDLib once download completes or is cancelled.
   */
  fun downloadFileOnDemand(fileId: Int): Flow<FileDownloadProgress> = callbackFlow {
    var acquired = false
    val sessionResult = acquireShortLivedSession()
    if (sessionResult.isFailure) {
      close(sessionResult.exceptionOrNull() ?: IllegalStateException("Không thể kết nối Telegram để tải tệp"))
      awaitClose { }
      return@callbackFlow
    }
    acquired = true

    val c = client
    if (c == null) {
      releaseShortLivedSession(forceClose = false)
      close(IllegalStateException("Chưa kết nối Telegram"))
      awaitClose { }
      return@callbackFlow
    }

    c.send(TdApi.DownloadFile(fileId, 16, 0, 0, true)) { obj ->
      if (obj is TdApi.Error) {
        val waitSec = TelegramPolicyGuard.parseFloodWaitSeconds(obj.message)
        if (waitSec != null) {
          close(FloodWaitException(waitSec, "Telegram giới hạn tốc độ ($waitSec giây)"))
        } else {
          close(IllegalStateException(TelegramPolicyGuard.translateAuthError(obj.message)))
        }
      } else if (obj is TdApi.File) {
        val completed = obj.local?.isDownloadingCompleted == true
        val localPath = obj.local?.path?.takeIf { completed && it.isNotBlank() }
        trySend(
          FileDownloadProgress(
            fileId = obj.id,
            downloadedBytes = obj.local?.downloadedSize ?: 0L,
            totalBytes = obj.expectedSize,
            isCompleted = completed,
            localPath = localPath,
          )
        )
        if (completed && localPath != null) {
          close()
        }
      }
    }

    val collectorJob = CoroutineScope(coroutineContext).launch {
      fileUpdates.collect { updatedFile ->
        if (updatedFile.id == fileId) {
          val completed = updatedFile.local?.isDownloadingCompleted == true
          val localPath = updatedFile.local?.path?.takeIf { completed && it.isNotBlank() }
          trySend(
            FileDownloadProgress(
              fileId = updatedFile.id,
              downloadedBytes = updatedFile.local?.downloadedSize ?: 0L,
              totalBytes = updatedFile.expectedSize,
              isCompleted = completed,
              localPath = localPath,
            )
          )
          if (completed && localPath != null) {
            close()
          }
        }
      }
    }

    awaitClose {
      collectorJob.cancel()
      if (acquired) {
        internalScope.launch(NonCancellable) {
          releaseShortLivedSession(forceClose = false)
        }
      }
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
