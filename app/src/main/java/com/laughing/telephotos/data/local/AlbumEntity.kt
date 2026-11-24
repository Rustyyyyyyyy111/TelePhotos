package com.laughing.telephotos.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
  val itemCount: Int,
)
