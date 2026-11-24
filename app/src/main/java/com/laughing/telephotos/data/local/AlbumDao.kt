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
           COUNT(m.id) AS itemCount
    FROM albums a
    LEFT JOIN media_items m ON m.albumId = a.id AND m.isTrashed = 0
    WHERE a.isTrashTopic = 0
    GROUP BY a.id
    ORDER BY a.createdAtEpochMs DESC
    """
  )
  fun observeAlbumsWithCount(): Flow<List<AlbumWithCount>>

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
}
