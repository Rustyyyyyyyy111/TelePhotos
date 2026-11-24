package com.laughing.telephotos.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MediaType {
  PHOTO,
  VIDEO,
}

enum class UploadStatus(val labelVi: String) {
  LOCAL_ONLY("Chờ chọn đích"),
  QUEUED("Chờ đồng bộ thủ công"),
  UPLOADING("Đang tải lên"),
  UPLOADED("Đã tải lên"),
  FAILED("Tải lên lỗi"),
  PAUSED_FLOOD_WAIT("Tạm dừng (Giới hạn Telegram)"),
}

@Entity(
  tableName = "media_items",
  indices = [
    Index(value = ["contentHash"], unique = true),
    Index(value = ["isTrashed", "takenAtEpochMs"]),
    Index(value = ["albumId", "isTrashed"]),
    Index(value = ["uploadStatus"]),
  ],
)
data class MediaEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val contentHash: String,
  val localUri: String? = null,
  val fileName: String,
  val mimeType: String,
  val mediaType: MediaType,
  val sizeBytes: Long,
  val width: Int = 0,
  val height: Int = 0,
  val durationSec: Int = 0,
  val takenAtEpochMs: Long,
  val albumId: Long? = null,
  val topicId: Long? = null,
  val previousAlbumId: Long? = null,
  val previousTopicId: Long? = null,
  val uploadStatus: UploadStatus = UploadStatus.QUEUED,
  val telegramChatId: Long? = null,
  val telegramMessageId: Long? = null,
  val telegramFileId: Int? = null,
  val telegramRemoteFileId: String? = null,
  val telegramThumbFileId: Int? = null,
  val cachedThumbPath: String? = null,
  val cachedLocalVideoPath: String? = null,
  val isTrashed: Boolean = false,
  val trashedAtEpochMs: Long? = null,
  val trashTopicMessageId: Long? = null,
  val lastError: String? = null,
  val updatedAtEpochMs: Long = System.currentTimeMillis(),
)
