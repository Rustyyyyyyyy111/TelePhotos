package com.laughing.telephotos.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
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
  version = 4,
  exportSchema = true,
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

    val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_items ADD COLUMN syncMode TEXT")
        db.execSQL("ALTER TABLE media_items ADD COLUMN userCaption TEXT")
        db.execSQL("ALTER TABLE albums ADD COLUMN latestMessageId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE albums ADD COLUMN oldestMessageId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE albums ADD COLUMN backfillExhausted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE albums ADD COLUMN lastSyncedAtEpochMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
          """
          UPDATE albums
          SET latestMessageId = COALESCE(
            (SELECT MAX(m.telegramMessageId) FROM media_items m
             WHERE m.telegramChatId = albums.chatId
               AND m.telegramMessageId IS NOT NULL
               AND m.telegramMessageId > 0
               AND (m.albumId = albums.id OR (albums.messageThreadId > 0 AND m.topicId = albums.messageThreadId))),
            0
          ),
          oldestMessageId = COALESCE(
            (SELECT MIN(m.telegramMessageId) FROM media_items m
             WHERE m.telegramChatId = albums.chatId
               AND m.telegramMessageId IS NOT NULL
               AND m.telegramMessageId > 0
               AND (m.albumId = albums.id OR (albums.messageThreadId > 0 AND m.topicId = albums.messageThreadId))),
            0
          )
          """.trimIndent()
        )
      }
    }

    /**
     * Non-destructive v3 -> v4 migration:
     * 1. Rebuilds media_items so contentHash becomes nullable/non-unique (real SHA-256 only),
     *    separates localMediaStoreKey, telegramFileUniqueId, shortHashHint, and tracks metadataVersion.
     * 2. Normalizes sentinel telegramMessageId <= 0 to NULL and deterministically merges any duplicate
     *    (telegramChatId, telegramMessageId) rows preserving all viewer-critical and upload-critical fields.
     * 3. Adds Room-validated UNIQUE(telegramChatId, telegramMessageId) and identity lookup indexes.
     * 4. Adds forward continuation cursors, retryable backfillState, and per-topic syncStatus on albums,
     *    safely converting any previously locked backfillExhausted=1 flag to LIKELY_END_RETRYABLE.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `media_items_new` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `contentHash` TEXT,
            `localUri` TEXT,
            `fileName` TEXT NOT NULL,
            `mimeType` TEXT NOT NULL,
            `mediaType` TEXT NOT NULL,
            `sizeBytes` INTEGER NOT NULL,
            `width` INTEGER NOT NULL,
            `height` INTEGER NOT NULL,
            `durationSec` INTEGER NOT NULL,
            `takenAtEpochMs` INTEGER NOT NULL,
            `albumId` INTEGER,
            `topicId` INTEGER,
            `previousAlbumId` INTEGER,
            `previousTopicId` INTEGER,
            `uploadStatus` TEXT NOT NULL,
            `telegramChatId` INTEGER,
            `telegramMessageId` INTEGER,
            `telegramFileId` INTEGER,
            `telegramRemoteFileId` TEXT,
            `telegramThumbFileId` INTEGER,
            `cachedThumbPath` TEXT,
            `cachedLocalVideoPath` TEXT,
            `isTrashed` INTEGER NOT NULL,
            `trashedAtEpochMs` INTEGER,
            `trashTopicMessageId` INTEGER,
            `lastError` TEXT,
            `updatedAtEpochMs` INTEGER NOT NULL,
            `syncMode` TEXT,
            `userCaption` TEXT,
            `localMediaStoreKey` TEXT,
            `telegramFileUniqueId` TEXT,
            `shortHashHint` TEXT,
            `metadataVersion` INTEGER NOT NULL,
            `metadataParsedAtEpochMs` INTEGER
          )
          """.trimIndent()
        )

        val rows = readAllV3MediaRows(db)
        val trashAlbumIds = readTrashAlbumIds(db)
        val byTelegramMessage = LinkedHashMap<Pair<Long, Long>, MutableList<V3MediaRow>>()
        val withoutMessageKey = mutableListOf<V3MediaRow>()

        for (row in rows) {
          val chatId = row.telegramChatId?.takeIf { it != 0L }
          val msgId = row.telegramMessageId?.takeIf { it > 0L }
          val normalized = row.copy(telegramChatId = chatId, telegramMessageId = msgId)
          if (chatId != null && msgId != null) {
            byTelegramMessage.getOrPut(chatId to msgId) { mutableListOf() }.add(normalized)
          } else {
            withoutMessageKey.add(normalized)
          }
        }

        val mergedRows = ArrayList<V4MigratedRow>(withoutMessageKey.size + byTelegramMessage.size)
        for (row in withoutMessageKey) {
          mergedRows.add(toV4Row(listOf(row), trashAlbumIds))
        }
        for ((_, group) in byTelegramMessage) {
          mergedRows.add(toV4Row(group, trashAlbumIds))
        }
        mergedRows.sortBy { it.id }

        for (row in mergedRows) {
          val cv = ContentValues().apply {
            put("id", row.id)
            putNullableString("contentHash", row.contentHash)
            putNullableString("localUri", row.localUri)
            put("fileName", row.fileName)
            put("mimeType", row.mimeType)
            put("mediaType", row.mediaType)
            put("sizeBytes", row.sizeBytes)
            put("width", row.width)
            put("height", row.height)
            put("durationSec", row.durationSec)
            put("takenAtEpochMs", row.takenAtEpochMs)
            putNullableLong("albumId", row.albumId)
            putNullableLong("topicId", row.topicId)
            putNullableLong("previousAlbumId", row.previousAlbumId)
            putNullableLong("previousTopicId", row.previousTopicId)
            put("uploadStatus", row.uploadStatus)
            putNullableLong("telegramChatId", row.telegramChatId)
            putNullableLong("telegramMessageId", row.telegramMessageId)
            putNullableInt("telegramFileId", row.telegramFileId)
            putNullableString("telegramRemoteFileId", row.telegramRemoteFileId)
            putNullableInt("telegramThumbFileId", row.telegramThumbFileId)
            putNullableString("cachedThumbPath", row.cachedThumbPath)
            putNullableString("cachedLocalVideoPath", row.cachedLocalVideoPath)
            put("isTrashed", if (row.isTrashed) 1 else 0)
            putNullableLong("trashedAtEpochMs", row.trashedAtEpochMs)
            putNullableLong("trashTopicMessageId", row.trashTopicMessageId)
            putNullableString("lastError", row.lastError)
            put("updatedAtEpochMs", row.updatedAtEpochMs)
            putNullableString("syncMode", row.syncMode)
            putNullableString("userCaption", row.userCaption)
            putNullableString("localMediaStoreKey", row.localMediaStoreKey)
            putNullableString("telegramFileUniqueId", row.telegramFileUniqueId)
            putNullableString("shortHashHint", row.shortHashHint)
            put("metadataVersion", row.metadataVersion)
            putNullableLong("metadataParsedAtEpochMs", row.metadataParsedAtEpochMs)
          }
          db.insert("media_items_new", SQLiteDatabase.CONFLICT_ABORT, cv)
        }

        db.execSQL("DROP TABLE `media_items`")
        db.execSQL("ALTER TABLE `media_items_new` RENAME TO `media_items`")

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_contentHash` ON `media_items` (`contentHash`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_localMediaStoreKey` ON `media_items` (`localMediaStoreKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_telegramFileUniqueId` ON `media_items` (`telegramFileUniqueId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_shortHashHint` ON `media_items` (`shortHashHint`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_localUri` ON `media_items` (`localUri`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_media_items_telegramChatId_telegramMessageId` ON `media_items` (`telegramChatId`, `telegramMessageId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_isTrashed_takenAtEpochMs` ON `media_items` (`isTrashed`, `takenAtEpochMs`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_albumId_isTrashed` ON `media_items` (`albumId`, `isTrashed`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_uploadStatus` ON `media_items` (`uploadStatus`)")

        db.execSQL("ALTER TABLE albums ADD COLUMN pendingForwardCursor INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE albums ADD COLUMN pendingForwardHighWaterMark INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE albums ADD COLUMN backfillState TEXT NOT NULL DEFAULT 'HAS_MORE'")
        db.execSQL("ALTER TABLE albums ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'IDLE'")
        db.execSQL("ALTER TABLE albums ADD COLUMN lastSyncError TEXT")
        db.execSQL("ALTER TABLE albums ADD COLUMN lastBackfillAtEpochMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
          """
          UPDATE albums
          SET backfillState = CASE WHEN backfillExhausted = 1 THEN 'LIKELY_END_RETRYABLE' ELSE 'HAS_MORE' END,
              backfillExhausted = 0
          """.trimIndent()
        )
      }
    }

    fun getInstance(context: Context): TelePhotosDatabase =
      instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
          context.applicationContext,
          TelePhotosDatabase::class.java,
          "tele_photos_vault.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
          .build()
          .also { instance = it }
      }
  }
}

private data class V3MediaRow(
  val id: Long,
  val rawContentHash: String,
  val localUri: String?,
  val fileName: String,
  val mimeType: String,
  val mediaType: String,
  val sizeBytes: Long,
  val width: Int,
  val height: Int,
  val durationSec: Int,
  val takenAtEpochMs: Long,
  val albumId: Long?,
  val topicId: Long?,
  val previousAlbumId: Long?,
  val previousTopicId: Long?,
  val uploadStatus: String,
  val telegramChatId: Long?,
  val telegramMessageId: Long?,
  val telegramFileId: Int?,
  val telegramRemoteFileId: String?,
  val telegramThumbFileId: Int?,
  val cachedThumbPath: String?,
  val cachedLocalVideoPath: String?,
  val isTrashed: Boolean,
  val trashedAtEpochMs: Long?,
  val trashTopicMessageId: Long?,
  val lastError: String?,
  val updatedAtEpochMs: Long,
  val syncMode: String?,
  val userCaption: String?,
)

private data class V4MigratedRow(
  val id: Long,
  val contentHash: String?,
  val localUri: String?,
  val fileName: String,
  val mimeType: String,
  val mediaType: String,
  val sizeBytes: Long,
  val width: Int,
  val height: Int,
  val durationSec: Int,
  val takenAtEpochMs: Long,
  val albumId: Long?,
  val topicId: Long?,
  val previousAlbumId: Long?,
  val previousTopicId: Long?,
  val uploadStatus: String,
  val telegramChatId: Long?,
  val telegramMessageId: Long?,
  val telegramFileId: Int?,
  val telegramRemoteFileId: String?,
  val telegramThumbFileId: Int?,
  val cachedThumbPath: String?,
  val cachedLocalVideoPath: String?,
  val isTrashed: Boolean,
  val trashedAtEpochMs: Long?,
  val trashTopicMessageId: Long?,
  val lastError: String?,
  val updatedAtEpochMs: Long,
  val syncMode: String?,
  val userCaption: String?,
  val localMediaStoreKey: String?,
  val telegramFileUniqueId: String?,
  val shortHashHint: String?,
  val metadataVersion: Int,
  val metadataParsedAtEpochMs: Long?,
)

private data class ClassifiedV3Hash(
  val realContentHash: String?,
  val localMediaStoreKey: String?,
  val telegramFileUniqueId: String?,
  val shortHashHint: String?,
)

private fun classifyV3ContentHash(raw: String): ClassifiedV3Hash {
  val trimmed = raw.trim()
  if (trimmed.isBlank()) return ClassifiedV3Hash(null, null, null, null)
  return when {
    trimmed.startsWith("ms_") -> ClassifiedV3Hash(
      realContentHash = null,
      localMediaStoreKey = trimmed,
      telegramFileUniqueId = null,
      shortHashHint = null,
    )
    trimmed.startsWith("tg_u_") -> ClassifiedV3Hash(
      realContentHash = null,
      localMediaStoreKey = null,
      telegramFileUniqueId = trimmed.removePrefix("tg_u_").takeIf { it.isNotBlank() },
      shortHashHint = null,
    )
    trimmed.startsWith("tg_") -> ClassifiedV3Hash(null, null, null, null)
    (trimmed.startsWith("sampled_") && trimmed.length >= 32) || trimmed.length >= 32 -> ClassifiedV3Hash(
      realContentHash = trimmed,
      localMediaStoreKey = null,
      telegramFileUniqueId = null,
      shortHashHint = trimmed.take(16),
    )
    trimmed.length in 6..31 -> ClassifiedV3Hash(
      realContentHash = null,
      localMediaStoreKey = null,
      telegramFileUniqueId = null,
      shortHashHint = trimmed,
    )
    else -> ClassifiedV3Hash(null, null, null, null)
  }
}

private fun readTrashAlbumIds(db: SupportSQLiteDatabase): Set<Long> {
  val result = HashSet<Long>()
  db.query("SELECT id FROM albums WHERE isTrashTopic = 1").use { c ->
    while (c.moveToNext()) {
      result.add(c.getLong(0))
    }
  }
  return result
}

private fun readAllV3MediaRows(db: SupportSQLiteDatabase): List<V3MediaRow> {
  val result = mutableListOf<V3MediaRow>()
  db.query("SELECT * FROM media_items ORDER BY id ASC").use { c ->
    val idxId = c.getColumnIndexOrThrow("id")
    val idxHash = c.getColumnIndexOrThrow("contentHash")
    val idxLocalUri = c.getColumnIndexOrThrow("localUri")
    val idxFileName = c.getColumnIndexOrThrow("fileName")
    val idxMime = c.getColumnIndexOrThrow("mimeType")
    val idxType = c.getColumnIndexOrThrow("mediaType")
    val idxSize = c.getColumnIndexOrThrow("sizeBytes")
    val idxW = c.getColumnIndexOrThrow("width")
    val idxH = c.getColumnIndexOrThrow("height")
    val idxDur = c.getColumnIndexOrThrow("durationSec")
    val idxTaken = c.getColumnIndexOrThrow("takenAtEpochMs")
    val idxAlbum = c.getColumnIndexOrThrow("albumId")
    val idxTopic = c.getColumnIndexOrThrow("topicId")
    val idxPrevAlbum = c.getColumnIndexOrThrow("previousAlbumId")
    val idxPrevTopic = c.getColumnIndexOrThrow("previousTopicId")
    val idxStatus = c.getColumnIndexOrThrow("uploadStatus")
    val idxChatId = c.getColumnIndexOrThrow("telegramChatId")
    val idxMsgId = c.getColumnIndexOrThrow("telegramMessageId")
    val idxFileId = c.getColumnIndexOrThrow("telegramFileId")
    val idxRemoteFileId = c.getColumnIndexOrThrow("telegramRemoteFileId")
    val idxThumbFileId = c.getColumnIndexOrThrow("telegramThumbFileId")
    val idxCachedThumb = c.getColumnIndexOrThrow("cachedThumbPath")
    val idxCachedVideo = c.getColumnIndexOrThrow("cachedLocalVideoPath")
    val idxTrashed = c.getColumnIndexOrThrow("isTrashed")
    val idxTrashedAt = c.getColumnIndexOrThrow("trashedAtEpochMs")
    val idxTrashMsgId = c.getColumnIndexOrThrow("trashTopicMessageId")
    val idxError = c.getColumnIndexOrThrow("lastError")
    val idxUpdated = c.getColumnIndexOrThrow("updatedAtEpochMs")
    val idxSyncMode = c.getColumnIndex("syncMode")
    val idxUserCaption = c.getColumnIndex("userCaption")

    while (c.moveToNext()) {
      result.add(
        V3MediaRow(
          id = c.getLong(idxId),
          rawContentHash = c.getString(idxHash).orEmpty(),
          localUri = c.getNullableString(idxLocalUri),
          fileName = c.getString(idxFileName).orEmpty().ifBlank { "media_${c.getLong(idxId)}" },
          mimeType = c.getString(idxMime).orEmpty().ifBlank { "application/octet-stream" },
          mediaType = c.getString(idxType).orEmpty().ifBlank { "PHOTO" },
          sizeBytes = c.getLong(idxSize),
          width = c.getInt(idxW),
          height = c.getInt(idxH),
          durationSec = c.getInt(idxDur),
          takenAtEpochMs = c.getLong(idxTaken),
          albumId = c.getNullableLong(idxAlbum),
          topicId = c.getNullableLong(idxTopic),
          previousAlbumId = c.getNullableLong(idxPrevAlbum),
          previousTopicId = c.getNullableLong(idxPrevTopic),
          uploadStatus = c.getString(idxStatus).orEmpty().ifBlank { "QUEUED" },
          telegramChatId = c.getNullableLong(idxChatId),
          telegramMessageId = c.getNullableLong(idxMsgId),
          telegramFileId = c.getNullableInt(idxFileId),
          telegramRemoteFileId = c.getNullableString(idxRemoteFileId),
          telegramThumbFileId = c.getNullableInt(idxThumbFileId),
          cachedThumbPath = c.getNullableString(idxCachedThumb),
          cachedLocalVideoPath = c.getNullableString(idxCachedVideo),
          isTrashed = c.getInt(idxTrashed) != 0,
          trashedAtEpochMs = c.getNullableLong(idxTrashedAt),
          trashTopicMessageId = c.getNullableLong(idxTrashMsgId),
          lastError = c.getNullableString(idxError),
          updatedAtEpochMs = c.getLong(idxUpdated),
          syncMode = if (idxSyncMode >= 0) c.getNullableString(idxSyncMode) else null,
          userCaption = if (idxUserCaption >= 0) c.getNullableString(idxUserCaption) else null,
        )
      )
    }
  }
  return result
}

private fun toV4Row(group: List<V3MediaRow>, trashAlbumIds: Set<Long>): V4MigratedRow {
  fun statusRank(status: String): Int = when (status) {
    "UPLOADED" -> 6
    "UPLOADING" -> 5
    "QUEUED" -> 4
    "PAUSED_FLOOD_WAIT" -> 3
    "FAILED" -> 2
    else -> 1
  }

  val ordered = group.sortedWith(
    compareByDescending<V3MediaRow> { !it.localUri.isNullOrBlank() && !it.localUri.contains("/picker") }
      .thenByDescending { !it.localUri.isNullOrBlank() }
      .thenByDescending { statusRank(it.uploadStatus) }
      .thenBy { it.id }
  )
  val primary = ordered.first()
  val classified = group.map { classifyV3ContentHash(it.rawContentHash) }

  val bestLocalUri = group.mapNotNull { it.localUri?.takeIf { u -> u.isNotBlank() } }
    .sortedByDescending { !it.contains("/picker") }
    .firstOrNull()

  val bestFileName = group.map { it.fileName.trim() }
    .filter { it.isNotBlank() }
    .sortedWith(
      compareByDescending<String> {
        !it.startsWith("TG_Photo_") && !it.startsWith("TG_Video_") &&
          !it.startsWith("TG_Doc_") && !it.startsWith("media_")
      }.thenByDescending { it.length }
    ).firstOrNull() ?: primary.fileName

  val bestMime = group.map { it.mimeType.trim() }
    .filter { it.isNotBlank() }
    .sortedByDescending { it != "application/octet-stream" }
    .firstOrNull() ?: primary.mimeType

  val resolvedMediaType = if (group.any { it.mediaType == "VIDEO" } || bestMime.startsWith("video/", ignoreCase = true)) {
    "VIDEO"
  } else {
    "PHOTO"
  }

  val resolvedStatus = group.maxByOrNull { statusRank(it.uploadStatus) }?.uploadStatus ?: primary.uploadStatus
  val mostRecentUploaded = group.filter { it.uploadStatus == "UPLOADED" }.maxByOrNull { it.updatedAtEpochMs } ?: primary

  val resolvedIsTrashed = group.all { it.isTrashed }
  val resolvedTrashedAt = if (resolvedIsTrashed) group.mapNotNull { it.trashedAtEpochMs }.minOrNull() else null
  val resolvedAlbumId = if (!resolvedIsTrashed) {
    group.mapNotNull { it.albumId?.takeIf { id -> id !in trashAlbumIds } }.firstOrNull()
      ?: group.mapNotNull { it.albumId }.firstOrNull()
  } else {
    group.mapNotNull { it.albumId }.firstOrNull()
  }
  val resolvedTopicId = group.mapNotNull { it.topicId?.takeIf { t -> t > 0L } }.firstOrNull()
    ?: group.mapNotNull { it.topicId }.firstOrNull()

  val bestTakenAt = group
    .filter { it.takenAtEpochMs > 0L }
    .sortedWith(
      compareByDescending<V3MediaRow> { !it.localUri.isNullOrBlank() }
        .thenByDescending { it.syncMode != null || !it.userCaption.isNullOrBlank() }
        .thenBy { it.takenAtEpochMs }
    )
    .firstOrNull()?.takenAtEpochMs ?: primary.takenAtEpochMs

  val resolvedSyncMode = group.mapNotNull { it.syncMode?.takeIf { m -> m.isNotBlank() } }.firstOrNull()
  val resolvedUserCaption = group.mapNotNull { it.userCaption?.takeIf { cap -> cap.isNotBlank() } }
    .maxByOrNull { it.length }

  val resolvedRealHash = classified.mapNotNull { it.realContentHash }.firstOrNull()
  val resolvedMsKey = classified.mapNotNull { it.localMediaStoreKey }.firstOrNull()
  val resolvedUniqueId = classified.mapNotNull { it.telegramFileUniqueId }.firstOrNull()
  val resolvedShortHint = classified.mapNotNull { it.shortHashHint }.firstOrNull()

  val resolvedUpdatedAt = group.maxOfOrNull { it.updatedAtEpochMs } ?: primary.updatedAtEpochMs
  val hasV3ParsedMeta = resolvedSyncMode != null || resolvedUserCaption != null
  val resolvedMetaVersion = if (hasV3ParsedMeta) 3 else 0
  val resolvedMetaParsedAt = if (hasV3ParsedMeta) resolvedUpdatedAt else null

  return V4MigratedRow(
    id = primary.id,
    contentHash = resolvedRealHash,
    localUri = bestLocalUri,
    fileName = bestFileName,
    mimeType = bestMime,
    mediaType = resolvedMediaType,
    sizeBytes = group.maxOfOrNull { it.sizeBytes }?.coerceAtLeast(0L) ?: 0L,
    width = group.maxOfOrNull { it.width }?.coerceAtLeast(0) ?: 0,
    height = group.maxOfOrNull { it.height }?.coerceAtLeast(0) ?: 0,
    durationSec = group.maxOfOrNull { it.durationSec }?.coerceAtLeast(0) ?: 0,
    takenAtEpochMs = bestTakenAt,
    albumId = resolvedAlbumId,
    topicId = resolvedTopicId,
    previousAlbumId = group.mapNotNull { it.previousAlbumId }.firstOrNull(),
    previousTopicId = group.mapNotNull { it.previousTopicId }.firstOrNull(),
    uploadStatus = resolvedStatus,
    telegramChatId = primary.telegramChatId,
    telegramMessageId = primary.telegramMessageId,
    telegramFileId = mostRecentUploaded.telegramFileId?.takeIf { it > 0 }
      ?: group.mapNotNull { it.telegramFileId?.takeIf { fid -> fid > 0 } }.firstOrNull(),
    telegramRemoteFileId = mostRecentUploaded.telegramRemoteFileId?.takeIf { it.isNotBlank() }
      ?: group.mapNotNull { it.telegramRemoteFileId?.takeIf { rid -> rid.isNotBlank() } }.firstOrNull(),
    telegramThumbFileId = mostRecentUploaded.telegramThumbFileId?.takeIf { it > 0 }
      ?: group.mapNotNull { it.telegramThumbFileId?.takeIf { tid -> tid > 0 } }.firstOrNull(),
    cachedThumbPath = group.mapNotNull { it.cachedThumbPath?.takeIf { p -> p.isNotBlank() } }.firstOrNull(),
    cachedLocalVideoPath = group.mapNotNull { it.cachedLocalVideoPath?.takeIf { p -> p.isNotBlank() } }.firstOrNull(),
    isTrashed = resolvedIsTrashed,
    trashedAtEpochMs = resolvedTrashedAt,
    trashTopicMessageId = group.mapNotNull { it.trashTopicMessageId?.takeIf { tid -> tid > 0L } }.firstOrNull(),
    lastError = if (resolvedStatus == "UPLOADED") null else group.mapNotNull { it.lastError }.firstOrNull(),
    updatedAtEpochMs = resolvedUpdatedAt,
    syncMode = resolvedSyncMode,
    userCaption = resolvedUserCaption,
    localMediaStoreKey = resolvedMsKey,
    telegramFileUniqueId = resolvedUniqueId,
    shortHashHint = resolvedShortHint,
    metadataVersion = resolvedMetaVersion,
    metadataParsedAtEpochMs = resolvedMetaParsedAt,
  )
}

private fun Cursor.getNullableString(index: Int): String? =
  if (index < 0 || isNull(index)) null else getString(index)

private fun Cursor.getNullableLong(index: Int): Long? =
  if (index < 0 || isNull(index)) null else getLong(index)

private fun Cursor.getNullableInt(index: Int): Int? =
  if (index < 0 || isNull(index)) null else getInt(index)

private fun ContentValues.putNullableString(key: String, value: String?) {
  if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullableLong(key: String, value: Long?) {
  if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullableInt(key: String, value: Int?) {
  if (value == null) putNull(key) else put(key, value)
}
