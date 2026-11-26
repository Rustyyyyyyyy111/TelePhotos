package com.laughing.telephotos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {

  @Query(
    """
    SELECT a.id, a.title, a.chatId, a.messageThreadId, a.isTrashTopic,
           a.iconColor, a.coverUri, a.createdAtEpochMs,
           a.latestMessageId, a.oldestMessageId, a.backfillExhausted, a.lastSyncedAtEpochMs,
           a.pendingForwardCursor, a.pendingForwardHighWaterMark,
           a.backfillState, a.syncStatus, a.lastSyncError, a.lastBackfillAtEpochMs,
           COUNT(DISTINCT m.id) AS itemCount
    FROM albums a
    LEFT JOIN media_items m
      ON m.isTrashed = 0
      AND (m.albumId = a.id OR (a.messageThreadId > 0 AND m.topicId = a.messageThreadId))
    WHERE a.isTrashTopic = 0
      AND LOWER(TRIM(a.title)) NOT IN ('đã xoá gần đây', 'đã xóa gần đây')
    GROUP BY a.id
    ORDER BY
      CASE
        WHEN LOWER(TRIM(a.title)) = 'ảnh' THEN 0
        WHEN LOWER(TRIM(a.title)) = 'video' THEN 1
        ELSE 2
      END ASC,
      a.createdAtEpochMs DESC
    """
  )
  fun observeAlbumsWithCount(): Flow<List<AlbumWithCount>>

  @Query("SELECT * FROM albums")
  suspend fun getAllAlbums(): List<AlbumEntity>

  @Query("SELECT * FROM albums WHERE id = :albumId LIMIT 1")
  fun observeAlbum(albumId: Long): Flow<AlbumEntity?>

  @Query("SELECT * FROM albums WHERE id = :albumId LIMIT 1")
  suspend fun getAlbum(albumId: Long): AlbumEntity?

  @Query("SELECT * FROM albums WHERE chatId = :chatId AND messageThreadId = :threadId LIMIT 1")
  suspend fun getByChatAndThread(chatId: Long, threadId: Long): AlbumEntity?

  @Query("SELECT * FROM albums WHERE chatId = :chatId AND isTrashTopic = 1 LIMIT 1")
  suspend fun getTrashAlbum(chatId: Long): AlbumEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(album: AlbumEntity): Long

  @Query("UPDATE albums SET coverUri = :coverUri WHERE id = :albumId")
  suspend fun updateCoverUri(albumId: Long, coverUri: String?)

  @Query(
    """
    UPDATE albums
    SET latestMessageId = :latestMessageId,
        oldestMessageId = :oldestMessageId,
        backfillExhausted = :backfillExhausted,
        lastSyncedAtEpochMs = :lastSyncedAtEpochMs,
        pendingForwardCursor = COALESCE(:pendingForwardCursor, pendingForwardCursor),
        pendingForwardHighWaterMark = COALESCE(:pendingForwardHighWaterMark, pendingForwardHighWaterMark),
        backfillState = COALESCE(:backfillState, backfillState),
        lastBackfillAtEpochMs = COALESCE(:lastBackfillAtEpochMs, lastBackfillAtEpochMs)
    WHERE id = :albumId
    """
  )
  suspend fun updateSyncCheckpoints(
    albumId: Long,
    latestMessageId: Long,
    oldestMessageId: Long,
    backfillExhausted: Boolean,
    lastSyncedAtEpochMs: Long = System.currentTimeMillis(),
    pendingForwardCursor: Long? = null,
    pendingForwardHighWaterMark: Long? = null,
    backfillState: String? = null,
    lastBackfillAtEpochMs: Long? = null,
  )

  @Query(
    """
    UPDATE albums
    SET syncStatus = :syncStatus,
        lastSyncError = :lastSyncError
    WHERE id = :albumId
    """
  )
  suspend fun updateSyncStatus(
    albumId: Long,
    syncStatus: String,
    lastSyncError: String?,
  )

  @Query(
    """
    UPDATE albums
    SET syncStatus = 'IDLE'
    WHERE syncStatus IN ('SYNCING', 'BACKFILLING')
    """
  )
  suspend fun resetStaleSyncStatuses()

  @Query("DELETE FROM albums WHERE id IN (:ids)")
  suspend fun deleteByIds(ids: List<Long>)
}
