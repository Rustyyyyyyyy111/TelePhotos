package com.laughing.telephotos

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.laughing.telephotos.core.TelegramCaptionMetadata
import com.laughing.telephotos.data.local.AlbumEntity
import com.laughing.telephotos.data.local.AlbumSyncStatus
import com.laughing.telephotos.data.local.BackfillState
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.data.local.TelePhotosDatabase
import com.laughing.telephotos.data.local.UploadStatus
import com.laughing.telephotos.data.repository.TopicSyncUiState
import com.laughing.telephotos.data.repository.VaultRepository
import com.laughing.telephotos.data.telegram.FloodWaitException
import com.laughing.telephotos.data.telegram.HistoryRawMessage
import com.laughing.telephotos.data.telegram.ScannedTelegramMedia
import com.laughing.telephotos.data.telegram.TelegramUploadMode
import com.laughing.telephotos.ui.screens.AlbumDetailScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TelePhotosArchitectureTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private fun createRepo(
    app: TelePhotosApplication,
    db: TelePhotosDatabase,
  ): VaultRepository = VaultRepository(
    context = app,
    database = db,
    mediaDao = db.mediaDao(),
    albumDao = db.albumDao(),
    syncTargetDao = db.syncTargetDao(),
    tdLibManager = app.tdLibManager,
    settingsPreferences = app.settingsPreferences,
    networkAlertCoordinator = app.networkAlertCoordinator,
    syncCoordinator = app.syncCoordinator,
  )

  private fun fakeScannedMedia(
    chatId: Long = -1001L,
    messageId: Long,
    topicId: Long = 10L,
    fileUniqueId: String = "uniq_$messageId",
    shortHashHint: String? = null,
    fullContentHash: String? = null,
    userCaption: String? = null,
    syncMode: String? = null,
    takenAtEpochMs: Long = messageId * 1_000L,
  ) = ScannedTelegramMedia(
    chatId = chatId,
    messageId = messageId,
    topicId = topicId,
    fileId = messageId.toInt(),
    remoteFileId = "remote_$messageId",
    telegramFileUniqueId = fileUniqueId,
    thumbFileId = null,
    fileName = "TG_Photo_$messageId.jpg",
    mimeType = "image/jpeg",
    mediaType = MediaType.PHOTO,
    sizeBytes = 2048L,
    durationSec = 0,
    takenAtEpochMs = takenAtEpochMs,
    fullContentHash = fullContentHash,
    shortHashHint = shortHashHint,
    syncMode = syncMode,
    userCaption = userCaption,
    isTrashed = false,
    metadataVersion = TelegramCaptionMetadata.CURRENT_METADATA_VERSION,
  )

  @Test
  fun `migration 3 to 4 validates schema merges duplicate telegram keys normalizes sentinels and unlocks backfill`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_v3_v4_test.db"
    context.deleteDatabase(dbName)

    // 1. Build real Room v3 SQLite database on disk with synthetic edge-case rows
    val openHelper = FrameworkSQLiteOpenHelperFactory().create(
      SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(dbName)
        .callback(
          object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
              db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `media_items` (
                  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                  `contentHash` TEXT NOT NULL,
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
                  `userCaption` TEXT
                )
                """.trimIndent()
              )
              db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `albums` (
                  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                  `title` TEXT NOT NULL,
                  `chatId` INTEGER NOT NULL,
                  `messageThreadId` INTEGER NOT NULL,
                  `isTrashTopic` INTEGER NOT NULL,
                  `iconColor` INTEGER NOT NULL,
                  `coverUri` TEXT,
                  `createdAtEpochMs` INTEGER NOT NULL,
                  `latestMessageId` INTEGER NOT NULL DEFAULT 0,
                  `oldestMessageId` INTEGER NOT NULL DEFAULT 0,
                  `backfillExhausted` INTEGER NOT NULL DEFAULT 0,
                  `lastSyncedAtEpochMs` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
              )
              db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_albums_chatId_messageThreadId` ON `albums` (`chatId`, `messageThreadId`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_albums_isTrashTopic` ON `albums` (`isTrashTopic`)")
              db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_targets` (
                  `id` INTEGER NOT NULL,
                  `chatId` INTEGER NOT NULL,
                  `chatTitle` TEXT NOT NULL,
                  `targetType` TEXT NOT NULL,
                  `isForumEnabled` INTEGER NOT NULL,
                  `defaultMessageThreadId` INTEGER,
                  `photosThreadId` INTEGER,
                  `videosThreadId` INTEGER,
                  `trashThreadId` INTEGER,
                  `isLocked` INTEGER NOT NULL,
                  `confirmedAtEpochMs` INTEGER NOT NULL,
                  PRIMARY KEY(`id`)
                )
                """.trimIndent()
              )

              db.execSQL(
                """
                INSERT INTO albums (id, title, chatId, messageThreadId, isTrashTopic, iconColor, coverUri, createdAtEpochMs, latestMessageId, oldestMessageId, backfillExhausted, lastSyncedAtEpochMs)
                VALUES (1, 'Du lịch', -1001, 10, 0, 7322096, NULL, 1000, 500, 100, 1, 2000)
                """.trimIndent()
              )

              // Duplicate (telegramChatId = -1001, telegramMessageId = 500) pair across remote + local rows
              db.execSQL(
                """
                INSERT INTO media_items (id, contentHash, localUri, fileName, mimeType, mediaType, sizeBytes, width, height, durationSec, takenAtEpochMs, albumId, topicId, uploadStatus, telegramChatId, telegramMessageId, telegramFileId, telegramRemoteFileId, telegramThumbFileId, cachedThumbPath, isTrashed, updatedAtEpochMs, syncMode, userCaption)
                VALUES (10, 'tg_-1001_500', NULL, 'TG_Photo_500.jpg', 'image/jpeg', 'PHOTO', 4000, 0, 0, 0, 2000, 1, 10, 'UPLOADED', -1001, 500, 777, 'rem_500', 778, '/tmp/thumb_500.jpg', 0, 5000, 'HD_MEDIA', 'Hoàng hôn Đà Lạt')
                """.trimIndent()
              )
              db.execSQL(
                """
                INSERT INTO media_items (id, contentHash, localUri, fileName, mimeType, mediaType, sizeBytes, width, height, durationSec, takenAtEpochMs, albumId, topicId, uploadStatus, telegramChatId, telegramMessageId, telegramFileId, telegramRemoteFileId, telegramThumbFileId, cachedThumbPath, isTrashed, updatedAtEpochMs, syncMode, userCaption)
                VALUES (11, 'fedcba9876543210fedcba9876543210', 'content://media/external/images/media/50', 'IMG_500.jpg', 'image/jpeg', 'PHOTO', 4000, 4032, 3024, 0, 1500, 1, 10, 'QUEUED', -1001, 500, NULL, NULL, NULL, NULL, 0, 4000, NULL, NULL)
                """.trimIndent()
              )

              // Two sentinel rows with telegramMessageId = 0 must normalize to NULL without unique violation
              db.execSQL(
                """
                INSERT INTO media_items (id, contentHash, localUri, fileName, mimeType, mediaType, sizeBytes, width, height, durationSec, takenAtEpochMs, albumId, topicId, uploadStatus, telegramChatId, telegramMessageId, isTrashed, updatedAtEpochMs)
                VALUES (12, 'ms_photo_101', 'content://media/external/images/media/101', 'LOCAL_101.jpg', 'image/jpeg', 'PHOTO', 1200, 800, 600, 0, 1600, NULL, NULL, 'LOCAL_ONLY', 0, 0, 0, 1600)
                """.trimIndent()
              )
              db.execSQL(
                """
                INSERT INTO media_items (id, contentHash, localUri, fileName, mimeType, mediaType, sizeBytes, width, height, durationSec, takenAtEpochMs, albumId, topicId, uploadStatus, telegramChatId, telegramMessageId, isTrashed, updatedAtEpochMs)
                VALUES (13, 'tg_u_UNIQUE_FILE_99', NULL, 'TG_Doc_501.jpg', 'image/jpeg', 'PHOTO', 2200, 1000, 1000, 0, 1700, 1, 10, 'UPLOADED', -1001, 501, 0, 1700)
                """.trimIndent()
              )
              db.execSQL(
                """
                INSERT INTO media_items (id, contentHash, localUri, fileName, mimeType, mediaType, sizeBytes, width, height, durationSec, takenAtEpochMs, albumId, topicId, uploadStatus, telegramChatId, telegramMessageId, isTrashed, updatedAtEpochMs)
                VALUES (14, '0123456789abcdef', 'content://media/external/images/media/102', 'SHORT_HINT.jpg', 'image/jpeg', 'PHOTO', 1300, 800, 600, 0, 1800, NULL, NULL, 'QUEUED', -1001, 0, 0, 1800)
                """.trimIndent()
              )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
          }
        )
        .build()
    )
    openHelper.writableDatabase.close()
    openHelper.close()

    // 2. Reopen via Room v4 with MIGRATION_3_4 so Room's onValidateSchema validates every column & index
    val db = Room.databaseBuilder(context, TelePhotosDatabase::class.java, dbName)
      .addMigrations(TelePhotosDatabase.MIGRATION_3_4)
      .allowMainThreadQueries()
      .build()
    try {
      val album = db.albumDao().getAlbum(1L)!!
      assertEquals(BackfillState.LIKELY_END_RETRYABLE.name, album.backfillState)
      assertFalse(album.backfillExhausted)
      assertEquals(0L, album.pendingForwardCursor)
      assertEquals(AlbumSyncStatus.IDLE.name, album.syncStatus)

      val items = db.mediaDao().getAllMediaSnapshot().sortedBy { it.id }
      assertEquals(4, items.size)

      // Merged row for (-1001, 500) preserves both local media info and Telegram file/caption metadata
      val merged = db.mediaDao().findByChatAndMessageId(-1001L, 500L)!!
      assertEquals("content://media/external/images/media/50", merged.localUri)
      assertEquals("IMG_500.jpg", merged.fileName)
      assertEquals(4032, merged.width)
      assertEquals(3024, merged.height)
      assertEquals(UploadStatus.UPLOADED, merged.uploadStatus)
      assertEquals(777, merged.telegramFileId)
      assertEquals("rem_500", merged.telegramRemoteFileId)
      assertEquals("HD_MEDIA", merged.syncMode)
      assertEquals("Hoàng hôn Đà Lạt", merged.userCaption)
      assertEquals("fedcba9876543210fedcba9876543210", merged.contentHash)
      assertEquals("fedcba9876543210", merged.shortHashHint)

      // Sentinel 0L values normalized to NULL & prefixed contentHash reclassified properly
      val msRow = items.first { it.fileName == "LOCAL_101.jpg" }
      assertNull(msRow.contentHash)
      assertEquals("ms_photo_101", msRow.localMediaStoreKey)
      assertNull(msRow.telegramChatId)
      assertNull(msRow.telegramMessageId)

      val tgUniqueRow = items.first { it.fileName == "TG_Doc_501.jpg" }
      assertNull(tgUniqueRow.contentHash)
      assertEquals("UNIQUE_FILE_99", tgUniqueRow.telegramFileUniqueId)

      val shortHintRow = items.first { it.fileName == "SHORT_HINT.jpg" }
      assertNull(shortHintRow.contentHash)
      assertEquals("0123456789abcdef", shortHintRow.shortHashHint)
      assertNull(shortHintRow.telegramMessageId)
    } finally {
      db.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun `bounded forward sync bridges 1050 unseen messages across 4 runs with pendingForwardCursor and crash recovery`() = runTest {
    val app = ApplicationProvider.getApplicationContext<TelePhotosApplication>()
    val db = Room.inMemoryDatabaseBuilder(app, TelePhotosDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = createRepo(app, db)
      val albumDao = db.albumDao()
      val mediaDao = db.mediaDao()

      val albumId = albumDao.upsert(
        AlbumEntity(
          title = "Du lịch",
          chatId = -1001L,
          messageThreadId = 10L,
          latestMessageId = 100L,
          oldestMessageId = 50L,
        )
      )
      mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "checkpoint_100",
          fileName = "checkpoint_100.jpg",
          mimeType = "image/jpeg",
          mediaType = MediaType.PHOTO,
          sizeBytes = 1024L,
          takenAtEpochMs = 100_000L,
          albumId = albumId,
          topicId = 10L,
          telegramChatId = -1001L,
          telegramMessageId = 100L,
          uploadStatus = UploadStatus.UPLOADED,
        )
      )

      // 1,050 unseen messages (IDs 101..1150) plus known checkpoint 100
      val remoteMessages = (1150L downTo 100L).toList()
      fun serveDescendingPage(fromMessageId: Long, limit: Int): List<HistoryRawMessage> {
        val slice = if (fromMessageId == 0L) {
          remoteMessages.take(limit)
        } else {
          remoteMessages.filter { it < fromMessageId }.take(limit)
        }
        return slice.map { msgId ->
          HistoryRawMessage(
            messageId = msgId,
            isCreationMarker = false,
            media = fakeScannedMedia(messageId = msgId),
          )
        }
      }

      // Sync #1: fetches 10 pages * 30 = 300 newest items (1150..851), leaves latestMessageId = 100
      val added1 = repo.syncAlbumIncrementalWithPageSource(
        albumId = albumId,
        chatId = -1001L,
        threadId = 10L,
        isTrashTopic = false,
        maxPages = 10,
        pageSize = 30,
        pageDelayMs = 0L,
        fetchPage = ::serveDescendingPage,
      )
      assertEquals(300, added1)
      val afterRun1 = albumDao.getAlbum(albumId)!!
      assertEquals(100L, afterRun1.latestMessageId)
      assertEquals(851L, afterRun1.pendingForwardCursor)
      assertEquals(1150L, afterRun1.pendingForwardHighWaterMark)

      // Calling bumpAlbumLatestMessageId mid-bridge must NOT advance committed latestMessageId!
      repo.bumpAlbumLatestMessageId(albumId, 1151L)
      val afterBump = albumDao.getAlbum(albumId)!!
      assertEquals(100L, afterBump.latestMessageId)
      assertEquals(851L, afterBump.pendingForwardCursor)
      assertEquals(1151L, afterBump.pendingForwardHighWaterMark)

      // Simulate transient crash mid-run #2: pendingForwardCursor stays 851L
      val crashErr = runCatching {
        repo.syncAlbumIncrementalWithPageSource(
          albumId = albumId,
          chatId = -1001L,
          threadId = 10L,
          isTrashTopic = false,
          maxPages = 10,
          pageSize = 30,
          pageDelayMs = 0L,
        ) { _, _ -> throw RuntimeException("TDLib dropped mid-scan") }
      }.exceptionOrNull()
      assertNotNull(crashErr)
      assertEquals(851L, albumDao.getAlbum(albumId)!!.pendingForwardCursor)
      assertEquals(100L, albumDao.getAlbum(albumId)!!.latestMessageId)

      // Sync #2: resumes strictly from pendingForwardCursor=851 -> ingests 850..551 (300 items)
      val seenCursorsRun2 = mutableListOf<Long>()
      val added2 = repo.syncAlbumIncrementalWithPageSource(
        albumId = albumId,
        chatId = -1001L,
        threadId = 10L,
        isTrashTopic = false,
        maxPages = 10,
        pageSize = 30,
        pageDelayMs = 0L,
      ) { fromId, limit ->
        seenCursorsRun2.add(fromId)
        serveDescendingPage(fromId, limit)
      }
      assertEquals(851L, seenCursorsRun2.first())
      assertEquals(300, added2)
      assertEquals(100L, albumDao.getAlbum(albumId)!!.latestMessageId)
      assertEquals(551L, albumDao.getAlbum(albumId)!!.pendingForwardCursor)

      // Sync #3: resumes from 551 -> ingests 550..251 (300 items)
      val added3 = repo.syncAlbumIncrementalWithPageSource(
        albumId = albumId,
        chatId = -1001L,
        threadId = 10L,
        isTrashTopic = false,
        maxPages = 10,
        pageSize = 30,
        pageDelayMs = 0L,
        fetchPage = ::serveDescendingPage,
      )
      assertEquals(300, added3)
      assertEquals(100L, albumDao.getAlbum(albumId)!!.latestMessageId)
      assertEquals(251L, albumDao.getAlbum(albumId)!!.pendingForwardCursor)

      // Sync #4: resumes from 251 -> ingests 250..101 (150 items) and hits checkpoint 100!
      val added4 = repo.syncAlbumIncrementalWithPageSource(
        albumId = albumId,
        chatId = -1001L,
        threadId = 10L,
        isTrashTopic = false,
        maxPages = 10,
        pageSize = 30,
        pageDelayMs = 0L,
        fetchPage = ::serveDescendingPage,
      )
      assertEquals(150, added4)
      val finalAlbum = albumDao.getAlbum(albumId)!!
      assertEquals(0L, finalAlbum.pendingForwardCursor)
      assertEquals(0L, finalAlbum.pendingForwardHighWaterMark)
      assertEquals(1151L, finalAlbum.latestMessageId)

      // Every single message ID 100..1150 must exist exactly once (1,051 rows total)
      val storedIds = mediaDao.getAllMediaSnapshot().mapNotNull { it.telegramMessageId }.sorted()
      assertEquals((100L..1150L).toList(), storedIds)
    } finally {
      db.close()
    }
  }

  @Test
  fun `identical file across two Telegram messages remains separate and duplicate upload merge avoids unique collision`() = runTest {
    val app = ApplicationProvider.getApplicationContext<TelePhotosApplication>()
    val db = Room.inMemoryDatabaseBuilder(app, TelePhotosDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = createRepo(app, db)
      val albumDao = db.albumDao()
      val mediaDao = db.mediaDao()
      val albumId = albumDao.upsert(AlbumEntity(title = "Chung", chatId = -1002L, messageThreadId = 20L))

      // Two distinct Telegram messages carrying identical fileUniqueId & shortHashHint
      val added = repo.syncAlbumIncrementalWithPageSource(
        albumId = albumId,
        chatId = -1002L,
        threadId = 20L,
        isTrashTopic = false,
        pageDelayMs = 0L,
      ) { _, _ ->
        listOf(
          HistoryRawMessage(
            messageId = 201L,
            isCreationMarker = false,
            media = fakeScannedMedia(
              chatId = -1002L,
              messageId = 201L,
              topicId = 20L,
              fileUniqueId = "SAME_REMOTE_FILE_UNIQUE",
              shortHashHint = "aabbccddeeff0011",
              fullContentHash = null,
            ),
          ),
          HistoryRawMessage(
            messageId = 200L,
            isCreationMarker = true,
            media = fakeScannedMedia(
              chatId = -1002L,
              messageId = 200L,
              topicId = 20L,
              fileUniqueId = "SAME_REMOTE_FILE_UNIQUE",
              shortHashHint = "aabbccddeeff0011",
              fullContentHash = null,
            ),
          ),
        )
      }
      assertEquals(2, added)
      val rows = mediaDao.getAllMediaSnapshot()
      assertEquals(2, rows.size)
      assertEquals(setOf(200L, 201L), rows.mapNotNull { it.telegramMessageId }.toSet())

      // Direct insert violating UNIQUE(telegramChatId, telegramMessageId) is rejected/ignored by SQLite
      try {
        db.openHelper.writableDatabase.execSQL(
          """
          INSERT INTO media_items (contentHash, fileName, mimeType, mediaType, sizeBytes, width, height, durationSec, takenAtEpochMs, uploadStatus, telegramChatId, telegramMessageId, isTrashed, updatedAtEpochMs, metadataVersion)
          VALUES (NULL, 'dup.jpg', 'image/jpeg', 'PHOTO', 100, 10, 10, 0, 1000, 'UPLOADED', -1002, 201, 0, 1000, 0)
          """.trimIndent()
        )
        fail("Expected SQLiteConstraintException for duplicate (telegramChatId, telegramMessageId)")
      } catch (_: SQLiteConstraintException) {
        // Expected
      }

      // Duplicate SHA-256 merge inside db.withTransaction attaches localUri to alreadyUploaded and deletes draft
      val localDraftId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
          localUri = "content://media/external/images/media/777",
          fileName = "local_777.jpg",
          mimeType = "image/jpeg",
          mediaType = MediaType.PHOTO,
          sizeBytes = 2048L,
          width = 1920,
          height = 1080,
          takenAtEpochMs = 5000L,
          uploadStatus = UploadStatus.QUEUED,
        )
      )
      val msg201Id = mediaDao.findByChatAndMessageId(-1002L, 201L)!!.id
      db.withTransaction {
        mediaDao.mergeLocalDetailsIntoUploaded(
          targetId = msg201Id,
          localUri = "content://media/external/images/media/777",
          width = 1920,
          height = 1080,
          durationSec = 0,
          albumId = albumId,
          topicId = 20L,
          contentHash = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
        )
        mediaDao.deleteByIds(listOf(localDraftId))
      }
      assertNull(mediaDao.getById(localDraftId))
      assertEquals("content://media/external/images/media/777", mediaDao.getById(msg201Id)?.localUri)
    } finally {
      db.close()
    }
  }

  @Test
  fun `transaction atomicity rolls back all writes on exception`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, TelePhotosDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val mediaDao = db.mediaDao()
      runCatching {
        db.withTransaction {
          mediaDao.insertIgnore(
            MediaEntity(
              contentHash = "tx_rollback_1",
              fileName = "tx_1.jpg",
              mimeType = "image/jpeg",
              mediaType = MediaType.PHOTO,
              sizeBytes = 100L,
              takenAtEpochMs = 1000L,
            )
          )
          error("Simulated failure before commit")
        }
      }
      assertTrue(mediaDao.getAllMediaSnapshot().isEmpty())
    } finally {
      db.close()
    }
  }

  @Test
  fun `conservative backfill distinguishes LIKELY_END_RETRYABLE from CONFIRMED_END and handles FloodWait or cancellation`() = runTest {
    val app = ApplicationProvider.getApplicationContext<TelePhotosApplication>()
    val db = Room.inMemoryDatabaseBuilder(app, TelePhotosDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = createRepo(app, db)
      val albumDao = db.albumDao()
      val albumId = albumDao.upsert(
        AlbumEntity(
          title = "Lịch sử",
          chatId = -1003L,
          messageThreadId = 30L,
          latestMessageId = 100L,
          oldestMessageId = 50L,
        )
      )

      // 1. Short batch without creation marker => LIKELY_END_RETRYABLE (never permanently locked!)
      val shortRes = repo.backfillOlderAlbumHistoryWithPageSource(
        albumId = albumId,
        chatId = -1003L,
        threadId = 30L,
        maxPages = 2,
        pageSize = 25,
        pageDelayMs = 0L,
      ) { _, _ ->
        listOf(HistoryRawMessage(40L, isCreationMarker = false, media = fakeScannedMedia(-1003L, 40L, 30L)))
      }
      assertEquals(BackfillState.LIKELY_END_RETRYABLE, shortRes.backfillState)
      assertFalse(shortRes.reachedHistoryEnd)
      assertFalse(albumDao.getAlbum(albumId)!!.backfillExhausted)

      // 2. FloodWaitException propagates cleanly and preserves LIKELY_END_RETRYABLE state
      val floodErr = runCatching {
        repo.backfillOlderAlbumHistoryWithPageSource(
          albumId = albumId,
          chatId = -1003L,
          threadId = 30L,
          pageDelayMs = 0L,
        ) { _, _ ->
          throw FloodWaitException(retryAfterSeconds = 12, unlockAtEpochMs = System.currentTimeMillis() + 12_000L, message = "FLOOD_WAIT_12")
        }
      }.exceptionOrNull()
      assertTrue(floodErr is FloodWaitException)
      assertEquals(BackfillState.LIKELY_END_RETRYABLE.name, albumDao.getAlbum(albumId)!!.backfillState)

      // 3. CancellationException propagates cleanly without mutating backfill state
      val cancelErr = runCatching {
        repo.backfillOlderAlbumHistoryWithPageSource(
          albumId = albumId,
          chatId = -1003L,
          threadId = 30L,
          pageDelayMs = 0L,
        ) { _, _ -> throw CancellationException("User closed") }
      }.exceptionOrNull()
      assertTrue(cancelErr is CancellationException)
      assertFalse(albumDao.getAlbum(albumId)!!.backfillExhausted)

      // 4. Retrying still invokes fetchPage and locks to CONFIRMED_END ONLY when creation marker is seen
      val retryRes = repo.backfillOlderAlbumHistoryWithPageSource(
        albumId = albumId,
        chatId = -1003L,
        threadId = 30L,
        maxPages = 2,
        pageSize = 25,
        pageDelayMs = 0L,
      ) { _, _ ->
        listOf(
          HistoryRawMessage(10L, isCreationMarker = false, media = fakeScannedMedia(-1003L, 10L, 30L)),
          HistoryRawMessage(1L, isCreationMarker = true, media = null),
        )
      }
      assertEquals(BackfillState.CONFIRMED_END, retryRes.backfillState)
      assertTrue(retryRes.reachedHistoryEnd)
      assertTrue(albumDao.getAlbum(albumId)!!.backfillExhausted)

      // 5. Once CONFIRMED_END, further calls exit immediately without invoking fetchPage
      var invokedAfterConfirm = false
      val noopRes = repo.backfillOlderAlbumHistoryWithPageSource(
        albumId = albumId,
        chatId = -1003L,
        threadId = 30L,
        pageDelayMs = 0L,
      ) { _, _ ->
        invokedAfterConfirm = true
        emptyList()
      }
      assertFalse(invokedAfterConfirm)
      assertEquals(0, noopRes.fetchedCount)
      assertTrue(noopRes.reachedHistoryEnd)
    } finally {
      db.close()
    }
  }

  @Test
  fun `reingesting scanned metadata preserves existing local date and user caption`() = runTest {
    val app = ApplicationProvider.getApplicationContext<TelePhotosApplication>()
    val db = Room.inMemoryDatabaseBuilder(app, TelePhotosDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = createRepo(app, db)
      val albumDao = db.albumDao()
      val mediaDao = db.mediaDao()
      val albumId = albumDao.upsert(
        AlbumEntity(
          title = "Kỷ niệm",
          chatId = -1004L,
          messageThreadId = 40L,
          latestMessageId = 800L,
          pendingForwardCursor = 950L,
          pendingForwardHighWaterMark = 950L,
        )
      )

      mediaDao.insertIgnore(
        MediaEntity(
          contentHash = null,
          localUri = "content://media/external/images/media/901",
          fileName = "IMG_901.jpg",
          mimeType = "image/jpeg",
          mediaType = MediaType.PHOTO,
          sizeBytes = 2048L,
          takenAtEpochMs = 1_600_000_000_000L,
          albumId = albumId,
          topicId = 40L,
          telegramChatId = -1004L,
          telegramMessageId = 901L,
          uploadStatus = UploadStatus.UPLOADED,
          syncMode = TelegramUploadMode.AS_FILE.name,
          userCaption = "Chú thích riêng tư của tôi",
          metadataVersion = 2,
        )
      )

      repo.syncAlbumIncrementalWithPageSource(
        albumId = albumId,
        chatId = -1004L,
        threadId = 40L,
        isTrashTopic = false,
        pageDelayMs = 0L,
      ) { _, _ ->
        listOf(
          HistoryRawMessage(
            messageId = 901L,
            isCreationMarker = false,
            media = fakeScannedMedia(
              chatId = -1004L,
              messageId = 901L,
              topicId = 40L,
              shortHashHint = "1122334455667788",
              userCaption = "Caption mặc định Telegram",
              syncMode = TelegramUploadMode.HD_MEDIA.name,
              takenAtEpochMs = 1_750_000_000_000L,
            ),
          ),
          HistoryRawMessage(
            messageId = 800L,
            isCreationMarker = false,
            media = null,
          ),
        )
      }

      val updated = mediaDao.findByChatAndMessageId(-1004L, 901L)!!
      assertEquals(1_600_000_000_000L, updated.takenAtEpochMs)
      assertEquals("Chú thích riêng tư của tôi", updated.userCaption)
      assertEquals(TelegramUploadMode.AS_FILE.name, updated.syncMode)
      assertEquals("1122334455667788", updated.shortHashHint)
      assertEquals(TelegramCaptionMetadata.CURRENT_METADATA_VERSION, updated.metadataVersion)
    } finally {
      db.close()
    }
  }

  @Test
  fun `albumDetailScreen displays syncing progress error retry and retryable vs confirmed backfill UI`() {
    var retryCount = 0
    val sampleAlbum = AlbumEntity(
      id = 1L,
      title = "Đà Lạt 2026",
      chatId = -1001L,
      messageThreadId = 10L,
      oldestMessageId = 50L,
      backfillState = BackfillState.LIKELY_END_RETRYABLE.name,
    )
    val samplePhoto = MediaEntity(
      id = 10L,
      contentHash = null,
      fileName = "dalat.jpg",
      mimeType = "image/jpeg",
      mediaType = MediaType.PHOTO,
      sizeBytes = 2048L,
      takenAtEpochMs = 1_700_000_000_000L,
      albumId = 1L,
      topicId = 10L,
      uploadStatus = UploadStatus.UPLOADED,
    )

    var albumState by mutableStateOf(sampleAlbum)
    var topicState by mutableStateOf(TopicSyncUiState(status = AlbumSyncStatus.SYNCING))

    composeTestRule.setContent {
      MaterialTheme {
        AlbumDetailScreen(
          album = albumState,
          photos = listOf(samplePhoto),
          videos = emptyList(),
          isLoggedIn = true,
          topicSyncState = topicState,
          onBack = {},
          onMediaClick = { _, _ -> },
          onImportIntoAlbum = {},
          onRefreshAlbum = { retryCount++ },
        )
      }
    }

    // 1. SYNCING state shows subtle progress bar + LIKELY_END_RETRYABLE footer text
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("album_sync_progress").assertIsDisplayed()
    composeTestRule.onNodeWithTag("album_load_older_button").assertIsDisplayed()
    composeTestRule.onNodeWithText("Kiểm tra lại lịch sử cũ hơn").assertIsDisplayed()

    // 2. ERROR state + CONFIRMED_END shows error banner with clickable Retry button and terminal footer
    composeTestRule.runOnIdle {
      albumState = sampleAlbum.copy(
        backfillState = BackfillState.CONFIRMED_END.name,
        backfillExhausted = true,
      )
      topicState = TopicSyncUiState(
        status = AlbumSyncStatus.ERROR,
        lastError = "Không thể kết nối Telegram",
      )
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("album_sync_error_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("album_sync_retry_button").assertIsDisplayed().performClick()
    assertEquals(1, retryCount)
    composeTestRule.onNodeWithText("Đã tải hết lịch sử chủ đề này").assertIsDisplayed()
  }
}
