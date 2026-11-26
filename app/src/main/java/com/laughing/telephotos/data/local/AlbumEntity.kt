package com.laughing.telephotos.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BackfillState {
  HAS_MORE,
  LIKELY_END_RETRYABLE,
  CONFIRMED_END,
  ;

  companion object {
    fun fromWireName(raw: String?): BackfillState =
      entries.firstOrNull { it.name == raw } ?: HAS_MORE
  }
}

enum class AlbumSyncStatus {
  IDLE,
  SYNCING,
  BACKFILLING,
  ERROR,
  ;

  companion object {
    fun fromWireName(raw: String?): AlbumSyncStatus =
      entries.firstOrNull { it.name == raw } ?: IDLE
  }
}

@Entity(
  tableName = "albums",
  indices = [
    Index(value = ["chatId", "messageThreadId"], unique = true),
    Index(value = ["isTrashTopic"]),
  ],
)
data class AlbumEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val title: String,
  val chatId: Long,
  val messageThreadId: Long,
  val isTrashTopic: Boolean = false,
  val iconColor: Int = 0x6FB9F0,
  val coverUri: String? = null,
  val createdAtEpochMs: Long = System.currentTimeMillis(),
  val latestMessageId: Long = 0L,
  val oldestMessageId: Long = 0L,
  val backfillExhausted: Boolean = false,
  val lastSyncedAtEpochMs: Long = 0L,
  val pendingForwardCursor: Long = 0L,
  val pendingForwardHighWaterMark: Long = 0L,
  val backfillState: String = BackfillState.HAS_MORE.name,
  val syncStatus: String = AlbumSyncStatus.IDLE.name,
  val lastSyncError: String? = null,
  val lastBackfillAtEpochMs: Long = 0L,
)

data class AlbumWithCount(
  val id: Long,
  val title: String,
  val chatId: Long,
  val messageThreadId: Long,
  val isTrashTopic: Boolean,
  val iconColor: Int,
  val coverUri: String?,
  val createdAtEpochMs: Long,
  val latestMessageId: Long = 0L,
  val oldestMessageId: Long = 0L,
  val backfillExhausted: Boolean = false,
  val lastSyncedAtEpochMs: Long = 0L,
  val pendingForwardCursor: Long = 0L,
  val pendingForwardHighWaterMark: Long = 0L,
  val backfillState: String = BackfillState.HAS_MORE.name,
  val syncStatus: String = AlbumSyncStatus.IDLE.name,
  val lastSyncError: String? = null,
  val lastBackfillAtEpochMs: Long = 0L,
  val itemCount: Int,
)
