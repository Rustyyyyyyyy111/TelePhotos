package com.laughing.telephotos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncTargetDao {

  @Query("SELECT * FROM sync_targets WHERE id = 1 LIMIT 1")
  fun observeTarget(): Flow<SyncTargetEntity?>

  @Query("SELECT * FROM sync_targets WHERE id = 1 LIMIT 1")
  suspend fun getTarget(): SyncTargetEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveTarget(target: SyncTargetEntity)

  @Query("UPDATE sync_targets SET trashThreadId = :trashThreadId WHERE id = 1")
  suspend fun updateTrashThreadId(trashThreadId: Long)

  @Query("DELETE FROM sync_targets")
  suspend fun clearTarget()
}
