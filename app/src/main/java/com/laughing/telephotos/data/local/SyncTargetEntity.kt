package com.laughing.telephotos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncTargetType(val labelVi: String) {
  PRIVATE_SUPERGROUP("Siêu nhóm riêng tư"),
  BASIC_GROUP("Nhóm thường"),
  PRIVATE_CHANNEL("Kênh riêng tư"),
  SAVED_MESSAGES("Tin nhắn đã lưu"),
}

@Entity(tableName = "sync_targets")
data class SyncTargetEntity(
  @PrimaryKey val id: Int = 1,
  val chatId: Long,
  val chatTitle: String,
  val targetType: SyncTargetType,
  val isForumEnabled: Boolean = false,
  val defaultMessageThreadId: Long? = null,
  val photosThreadId: Long? = null,
  val videosThreadId: Long? = null,
  val trashThreadId: Long? = null,
  val isLocked: Boolean = true,
  val confirmedAtEpochMs: Long = System.currentTimeMillis(),
)
