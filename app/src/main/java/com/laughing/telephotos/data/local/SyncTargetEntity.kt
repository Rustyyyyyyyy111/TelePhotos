package com.laughing.telephotos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncTargetType(val labelVi: String) {
  SAVED_MESSAGES("Tin nhắn đã lưu"),
  PRIVATE_CHANNEL("Kênh riêng tư"),
  PRIVATE_SUPERGROUP("Siêu nhóm riêng tư"),
}

@Entity(tableName = "sync_targets")
data class SyncTargetEntity(
  @PrimaryKey val id: Int = 1,
  val chatId: Long,
  val chatTitle: String,
  val targetType: SyncTargetType,
  val isForumEnabled: Boolean = false,
  val defaultMessageThreadId: Long? = null,
  val trashThreadId: Long? = null,
  val isLocked: Boolean = true,
  val confirmedAtEpochMs: Long = System.currentTimeMillis(),
)
