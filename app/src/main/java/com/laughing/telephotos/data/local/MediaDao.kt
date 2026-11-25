package com.laughing.telephotos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class StorageStats(
  val totalCount: Int,
  val uploadedCount: Int,
  val queuedCount: Int,
  val failedCount: Int,
  val trashedCount: Int,
  val totalBytes: Long,
  val uploadedBytes: Long,
  val freeableCount: Int = 0,
  val freeableBytes: Long = 0L,
)

@Dao
interface MediaDao {

  @Query(
    """
    SELECT * FROM media_items
    WHERE isTrashed = 0
    ORDER BY takenAtEpochMs DESC, id DESC
    """
  )
  fun observeTimelineMedia(): Flow<List<MediaEntity>>

  @Query(
    """
    SELECT * FROM media_items
    WHERE isTrashed = 0 AND mediaType = 'PHOTO'
    ORDER BY takenAtEpochMs DESC, id DESC
    """
  )
  fun observePhotosTimeline(): Flow<List<MediaEntity>>

  @Query(
    """
    SELECT * FROM media_items
    WHERE isTrashed = 0 AND mediaType = 'VIDEO'
    ORDER BY takenAtEpochMs DESC, id DESC
    """
  )
  fun observeVideosTimeline(): Flow<List<MediaEntity>>

  @Query(
    """
    SELECT * FROM media_items
    WHERE isTrashed = 0 AND albumId = :albumId
    ORDER BY takenAtEpochMs DESC, id DESC
    """
  )
  fun observeMediaByAlbum(albumId: Long): Flow<List<MediaEntity>>

  @Query(
    """
    SELECT * FROM media_items
    WHERE isTrashed = 0 AND albumId = :albumId AND mediaType = 'PHOTO'
    ORDER BY takenAtEpochMs DESC, id DESC
    """
  )
  fun observeAlbumPhotos(albumId: Long): Flow<List<MediaEntity>>

  @Query(
    """
    SELECT * FROM media_items
    WHERE isTrashed = 0 AND albumId = :albumId AND mediaType = 'VIDEO'
    ORDER BY takenAtEpochMs DESC, id DESC
    """
  )
  fun observeAlbumVideos(albumId: Long): Flow<List<MediaEntity>>

  @Query(
    """
    SELECT * FROM media_items
    WHERE isTrashed = 1
    ORDER BY trashedAtEpochMs DESC, id DESC
    """
  )
  fun observeTrashedMedia(): Flow<List<MediaEntity>>

  @Query(
    """
    SELECT * FROM media_items
    WHERE uploadStatus IN ('LOCAL_ONLY', 'QUEUED', 'FAILED', 'PAUSED_FLOOD_WAIT', 'UPLOADING')
      AND isTrashed = 0
    ORDER BY takenAtEpochMs ASC, id ASC
    """
  )
  fun observeUploadQueue(): Flow<List<MediaEntity>>

  @Query(
    """
    SELECT * FROM media_items
    WHERE uploadStatus = 'QUEUED'
      AND isTrashed = 0
    ORDER BY takenAtEpochMs DESC, id ASC
    LIMIT 1
    """
  )
  suspend fun getNextPendingUpload(): MediaEntity?

  @Query(
    """
    SELECT COUNT(*) FROM media_items
    WHERE uploadStatus IN ('QUEUED', 'UPLOADING')
      AND isTrashed = 0
    """
  )
  suspend fun countPendingUploads(): Int

  @Query("SELECT * FROM media_items")
  suspend fun getAllMediaSnapshot(): List<MediaEntity>

  @Query(
    """
    SELECT * FROM media_items
    WHERE uploadStatus = 'QUEUED'
      AND isTrashed = 0
      AND mediaType = :mediaTypeName
    ORDER BY takenAtEpochMs DESC, id ASC
    LIMIT 1
    """
  )
  suspend fun getNextPendingUploadByType(mediaTypeName: String): MediaEntity?

  @Query(
    """
    SELECT COUNT(*) FROM media_items
    WHERE uploadStatus IN ('QUEUED', 'UPLOADING')
      AND isTrashed = 0
      AND mediaType = :mediaTypeName
    """
  )
  suspend fun countPendingUploadsByType(mediaTypeName: String): Int

  @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
  fun observeById(id: Long): Flow<MediaEntity?>

  @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
  suspend fun getById(id: Long): MediaEntity?

  @Query("SELECT * FROM media_items WHERE contentHash = :hash LIMIT 1")
  suspend fun findByHash(hash: String): MediaEntity?

  @Query("SELECT * FROM media_items WHERE contentHash = :hash AND uploadStatus = 'UPLOADED' LIMIT 1")
  suspend fun findUploadedByHash(hash: String): MediaEntity?

  @Query("SELECT contentHash FROM media_items WHERE uploadStatus = 'UPLOADED'")
  suspend fun getAllUploadedHashes(): List<String>

  @Query("SELECT * FROM media_items WHERE id IN (:ids)")
  suspend fun getByIds(ids: List<Long>): List<MediaEntity>

  @Query(
    """
    SELECT * FROM media_items
    WHERE isTrashed = 0
      AND uploadStatus = 'UPLOADED'
      AND localUri IS NOT NULL
    ORDER BY takenAtEpochMs ASC, id ASC
    """
  )
  suspend fun getAllFreeableSyncedMedia(): List<MediaEntity>

  @Query(
    """
    SELECT
      COUNT(CASE WHEN isTrashed = 0 THEN 1 END) AS totalCount,
      COUNT(CASE WHEN isTrashed = 0 AND uploadStatus = 'UPLOADED' THEN 1 END) AS uploadedCount,
      COUNT(CASE WHEN isTrashed = 0 AND uploadStatus IN ('LOCAL_ONLY', 'QUEUED', 'UPLOADING', 'PAUSED_FLOOD_WAIT') THEN 1 END) AS queuedCount,
      COUNT(CASE WHEN isTrashed = 0 AND uploadStatus = 'FAILED' THEN 1 END) AS failedCount,
      COUNT(CASE WHEN isTrashed = 1 THEN 1 END) AS trashedCount,
      COALESCE(SUM(CASE WHEN isTrashed = 0 THEN sizeBytes ELSE 0 END), 0) AS totalBytes,
      COALESCE(SUM(CASE WHEN isTrashed = 0 AND uploadStatus = 'UPLOADED' THEN sizeBytes ELSE 0 END), 0) AS uploadedBytes,
      COUNT(CASE WHEN isTrashed = 0 AND uploadStatus = 'UPLOADED' AND localUri IS NOT NULL THEN 1 END) AS freeableCount,
      COALESCE(SUM(CASE WHEN isTrashed = 0 AND uploadStatus = 'UPLOADED' AND localUri IS NOT NULL THEN sizeBytes ELSE 0 END), 0) AS freeableBytes
    FROM media_items
    """
  )
  fun observeStorageStats(): Flow<StorageStats>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIgnore(entity: MediaEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: MediaEntity): Long

  @Query(
    """
    UPDATE media_items
    SET uploadStatus = :status,
        lastError = :error,
        updatedAtEpochMs = :updatedAt
    WHERE id = :id
    """
  )
  suspend fun updateUploadStatus(
    id: Long,
    status: UploadStatus,
    error: String? = null,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE media_items
    SET uploadStatus = 'UPLOADED',
        telegramChatId = :chatId,
        telegramMessageId = :messageId,
        telegramFileId = :fileId,
        telegramRemoteFileId = :remoteFileId,
        topicId = :topicId,
        telegramThumbFileId = COALESCE(:thumbFileId, telegramThumbFileId),
        lastError = NULL,
        updatedAtEpochMs = :updatedAt
    WHERE id = :id
    """
  )
  suspend fun markUploaded(
    id: Long,
    chatId: Long,
    messageId: Long,
    fileId: Int?,
    remoteFileId: String?,
    topicId: Long?,
    thumbFileId: Int? = null,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE media_items
    SET telegramThumbFileId = COALESCE(:thumbFileId, telegramThumbFileId),
        cachedThumbPath = COALESCE(:cachedThumbPath, cachedThumbPath),
        updatedAtEpochMs = :updatedAt
    WHERE id = :id
    """
  )
  suspend fun updateThumbInfo(
    id: Long,
    thumbFileId: Int?,
    cachedThumbPath: String?,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE media_items
    SET isTrashed = 1,
        trashedAtEpochMs = :trashedAt,
        previousAlbumId = albumId,
        previousTopicId = topicId,
        albumId = :trashAlbumId,
        topicId = :trashTopicId,
        trashTopicMessageId = :trashMessageId,
        updatedAtEpochMs = :trashedAt
    WHERE id = :id
    """
  )
  suspend fun moveToTrash(
    id: Long,
    trashedAt: Long,
    trashAlbumId: Long?,
    trashTopicId: Long?,
    trashMessageId: Long?,
  )

  @Query(
    """
    UPDATE media_items
    SET isTrashed = 0,
        trashedAtEpochMs = NULL,
        albumId = previousAlbumId,
        topicId = previousTopicId,
        previousAlbumId = NULL,
        previousTopicId = NULL,
        updatedAtEpochMs = :restoredAt
    WHERE id = :id
    """
  )
  suspend fun restoreFromTrash(
    id: Long,
    restoredAt: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE media_items
    SET albumId = :albumId,
        topicId = :topicId,
        updatedAtEpochMs = :updatedAt
    WHERE id IN (:ids)
    """
  )
  suspend fun assignToAlbum(
    ids: List<Long>,
    albumId: Long?,
    topicId: Long?,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE media_items
    SET cachedLocalVideoPath = NULL,
        cachedThumbPath = NULL,
        updatedAtEpochMs = :updatedAt
    """
  )
  suspend fun clearTemporaryCachePaths(updatedAt: Long = System.currentTimeMillis())

  @Query(
    """
    UPDATE media_items
    SET localUri = :localUri,
        updatedAtEpochMs = :updatedAt
    WHERE id = :id
    """
  )
  suspend fun markSavedToDevice(
    id: Long,
    localUri: String,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE media_items
    SET cachedLocalVideoPath = :localPath,
        updatedAtEpochMs = :updatedAt
    WHERE id = :id
    """
  )
  suspend fun updateCachedVideoPath(
    id: Long,
    localPath: String,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE media_items
    SET cachedThumbPath = :thumbPath,
        updatedAtEpochMs = :updatedAt
    WHERE id = :id
    """
  )
  suspend fun updateCachedThumbPath(
    id: Long,
    thumbPath: String,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE media_items
    SET uploadStatus = 'PAUSED_FLOOD_WAIT',
        lastError = :reason,
        updatedAtEpochMs = :updatedAt
    WHERE uploadStatus IN ('QUEUED', 'UPLOADING') AND isTrashed = 0
    """
  )
  suspend fun pauseActiveQueueForFloodWait(
    reason: String,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE media_items
    SET uploadStatus = 'QUEUED',
        lastError = NULL,
        updatedAtEpochMs = :updatedAt
    WHERE uploadStatus IN ('LOCAL_ONLY', 'PAUSED_FLOOD_WAIT', 'FAILED') AND isTrashed = 0
    """
  )
  suspend fun requeueAllPending(updatedAt: Long = System.currentTimeMillis())

  @Query(
    """
    UPDATE media_items
    SET uploadStatus = 'QUEUED',
        lastError = NULL,
        updatedAtEpochMs = :updatedAt
    WHERE uploadStatus IN ('LOCAL_ONLY', 'PAUSED_FLOOD_WAIT', 'FAILED')
      AND isTrashed = 0
      AND mediaType = :mediaTypeName
    """
  )
  suspend fun requeueAllPendingByType(
    mediaTypeName: String,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE media_items
    SET uploadStatus = 'QUEUED',
        lastError = NULL,
        updatedAtEpochMs = :updatedAt
    WHERE uploadStatus = 'PAUSED_FLOOD_WAIT'
      AND isTrashed = 0
    """
  )
  suspend fun requeueFloodWaitPaused(updatedAt: Long = System.currentTimeMillis())

  @Query(
    """
    UPDATE media_items
    SET uploadStatus = 'QUEUED',
        lastError = NULL,
        updatedAtEpochMs = :updatedAt
    WHERE uploadStatus = 'PAUSED_FLOOD_WAIT'
      AND isTrashed = 0
      AND mediaType = :mediaTypeName
    """
  )
  suspend fun requeueFloodWaitPausedByType(
    mediaTypeName: String,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query("UPDATE media_items SET contentHash = :contentHash WHERE id = :id")
  suspend fun updateContentHash(id: Long, contentHash: String)

  @Query("UPDATE media_items SET localUri = :localUri, updatedAtEpochMs = :updatedAt WHERE id = :id")
  suspend fun updateLocalUri(
    id: Long,
    localUri: String?,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE media_items
    SET uploadStatus = 'QUEUED',
        lastError = NULL,
        updatedAtEpochMs = :updatedAt
    WHERE id IN (:ids) AND uploadStatus != 'UPLOADED' AND isTrashed = 0
    """
  )
  suspend fun markSelectedQueued(
    ids: List<Long>,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query(
    """
    UPDATE media_items
    SET uploadStatus = 'QUEUED',
        lastError = NULL,
        updatedAtEpochMs = :updatedAt
    WHERE uploadStatus = 'UPLOADING' AND isTrashed = 0
    """
  )
  suspend fun resetInterruptedUploadsToQueued(updatedAt: Long = System.currentTimeMillis())

  @Query(
    """
    SELECT * FROM media_items
    WHERE isTrashed = 1 AND trashedAtEpochMs IS NOT NULL AND trashedAtEpochMs <= :cutoffEpochMs
    """
  )
  suspend fun getExpiredTrashedItems(cutoffEpochMs: Long): List<MediaEntity>

  @Query("SELECT * FROM media_items WHERE isTrashed = 1")
  suspend fun getAllTrashedItems(): List<MediaEntity>

  @Query("DELETE FROM media_items WHERE id IN (:ids)")
  suspend fun deleteByIds(ids: List<Long>)

  @Query("DELETE FROM media_items WHERE isTrashed = 1")
  suspend fun deleteAllTrashed()

  @Query(
    """
    DELETE FROM media_items
    WHERE isTrashed = 1 AND trashedAtEpochMs IS NOT NULL AND trashedAtEpochMs <= :cutoffEpochMs
    """
  )
  suspend fun deleteExpiredTrashed(cutoffEpochMs: Long): Int
}
