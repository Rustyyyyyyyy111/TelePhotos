package com.laughing.telephotos.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class TelePhotosConverters {
  @TypeConverter
  fun fromMediaType(value: MediaType): String = value.name

  @TypeConverter
  fun toMediaType(value: String): MediaType =
    MediaType.entries.firstOrNull { it.name == value } ?: MediaType.PHOTO

  @TypeConverter
  fun fromUploadStatus(value: UploadStatus): String = value.name

  @TypeConverter
  fun toUploadStatus(value: String): UploadStatus =
    UploadStatus.entries.firstOrNull { it.name == value } ?: UploadStatus.QUEUED

  @TypeConverter
  fun fromSyncTargetType(value: SyncTargetType): String = value.name

  @TypeConverter
  fun toSyncTargetType(value: String): SyncTargetType =
    SyncTargetType.entries.firstOrNull { it.name == value } ?: SyncTargetType.SAVED_MESSAGES
}

@Database(
  entities = [MediaEntity::class, AlbumEntity::class, SyncTargetEntity::class],
  version = 1,
  exportSchema = false,
)
@TypeConverters(TelePhotosConverters::class)
abstract class TelePhotosDatabase : RoomDatabase() {
  abstract fun mediaDao(): MediaDao
  abstract fun albumDao(): AlbumDao
  abstract fun syncTargetDao(): SyncTargetDao

  companion object {
    @Volatile private var instance: TelePhotosDatabase? = null

    fun getInstance(context: Context): TelePhotosDatabase =
      instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
          context.applicationContext,
          TelePhotosDatabase::class.java,
          "tele_photos_vault.db",
        ).build().also { instance = it }
      }
  }
}
