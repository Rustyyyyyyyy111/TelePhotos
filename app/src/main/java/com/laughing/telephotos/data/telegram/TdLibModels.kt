package com.laughing.telephotos.data.telegram

import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.data.local.SyncTargetType

enum class TelegramConnectionStatus(val labelVi: String) {
  DISCONNECTED_IDLE("Đã ngắt kết nối Telegram (chỉ kết nối khi bấm đồng bộ)"),
  CONNECTING("Đang kết nối Telegram..."),
  CONNECTED_ACTIVE("Đang kết nối Telegram"),
  DISCONNECTING("Đang ngắt kết nối Telegram..."),
}

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
) {
  val fraction: Float
    get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

class FloodWaitException(
  val retryAfterSeconds: Int,
  override val message: String,
) : Exception(message)
