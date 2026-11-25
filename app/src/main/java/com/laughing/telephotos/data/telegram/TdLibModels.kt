package com.laughing.telephotos.data.telegram

import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.data.local.SyncTargetType

enum class TelegramConnectionStatus(val labelVi: String) {
  DISCONNECTED_IDLE("Đã ngắt kết nối (Chờ thao tác)"),
  CONNECTING("Đang kết nối Telegram..."),
  RECONNECTING("Đang kết nối lại Telegram..."),
  READY("Sẵn sàng • Đã kết nối Telegram"),
  SYNCING("Đang đồng bộ / Đang tải..."),
  FLOOD_WAIT("Tạm khoá hàng đợi (FLOOD_WAIT)"),
  DISCONNECTING("Đang chuyển sang chế độ nền..."),
}

enum class TdLibLifecycleState {
  IDLE,
  CONNECTING,
  RECONNECTING,
  READY,
  AWAITING_CREDENTIALS,
  AWAITING_PHONE,
  AWAITING_CODE,
  AWAITING_PASSWORD,
  FLOOD_WAIT,
  ERROR,
}

data class FloodWaitState(
  val retryAfterSeconds: Int,
  val unlockAtEpochMs: Long,
  val reason: String,
)

sealed interface TelegramAuthState {
  data object Uninitialized : TelegramAuthState
  data class WaitApiCredentials(
    val initialApiId: String = "",
    val initialApiHash: String = "",
  ) : TelegramAuthState
  data class WaitPhoneNumber(val initialPhone: String = "+84") : TelegramAuthState
  data class WaitCode(val phoneHint: String = "") : TelegramAuthState
  data class WaitPassword(val passwordHint: String = "") : TelegramAuthState
  data class Ready(
    val userId: Long,
    val displayName: String,
    val phoneNumber: String = "",
  ) : TelegramAuthState
  data class Error(val messageVi: String) : TelegramAuthState
}

data class TelegramCandidateTarget(
  val chatId: Long,
  val title: String,
  val targetType: SyncTargetType,
  val isForumEnabled: Boolean,
  val subtitleVi: String,
)

data class TopicInfoModel(
  val chatId: Long,
  val messageThreadId: Long,
  val title: String,
  val iconColor: Int,
)

data class SentDocumentResult(
  val chatId: Long,
  val messageId: Long,
  val fileId: Int?,
  val remoteFileId: String?,
  val messageThreadId: Long?,
  val thumbFileId: Int? = null,
)

data class ScannedTelegramMedia(
  val contentHash: String,
  val chatId: Long,
  val messageId: Long,
  val topicId: Long?,
  val fileId: Int,
  val remoteFileId: String?,
  val thumbFileId: Int?,
  val fileName: String,
  val mimeType: String,
  val mediaType: MediaType,
  val sizeBytes: Long,
  val durationSec: Int,
  val takenAtEpochMs: Long,
  val isTrashed: Boolean,
)

data class FileDownloadProgress(
  val fileId: Int,
  val downloadedBytes: Long,
  val totalBytes: Long,
  val isCompleted: Boolean,
  val localPath: String?,
  val statusMessageVi: String? = null,
) {
  val fraction: Float
    get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

data class RefreshedTelegramFile(
  val fileId: Int,
  val remoteFileId: String?,
  val thumbFileId: Int?,
  val localPath: String?,
  val isDownloadCompleted: Boolean,
  val expectedSizeBytes: Long,
)

class FloodWaitException(
  val retryAfterSeconds: Int,
  val unlockAtEpochMs: Long,
  override val message: String,
) : Exception(message)

enum class TelegramUploadMode(val labelVi: String) {
  AS_FILE("Gửi dạng Tệp (Send as File)"),
  HD_MEDIA("Gửi HD (Send HD)");

  companion object {
    fun fromWireName(raw: String?): TelegramUploadMode {
      return entries.firstOrNull { it.name == raw } ?: AS_FILE
    }
  }
}
