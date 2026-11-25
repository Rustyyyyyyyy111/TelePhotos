package com.laughing.telephotos.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
  version = 2,
  exportSchema = false,
)
@TypeConverters(TelePhotosConverters::class)
abstract class TelePhotosDatabase : RoomDatabase() {
  abstract fun mediaDao(): MediaDao
  abstract fun albumDao(): AlbumDao
  abstract fun syncTargetDao(): SyncTargetDao

  companion object {
    @Volatile private var instance: TelePhotosDatabase? = null

    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_targets ADD COLUMN photosThreadId INTEGER")
        db.execSQL("ALTER TABLE sync_targets ADD COLUMN videosThreadId INTEGER")
      }
    }

    fun getInstance(context: Context): TelePhotosDatabase =
      instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
          context.applicationContext,
          TelePhotosDatabase::class.java,
          "tele_photos_vault.db",
        ).addMigrations(MIGRATION_1_2)
          .build()
          .also { instance = it }
      }
  }
}
