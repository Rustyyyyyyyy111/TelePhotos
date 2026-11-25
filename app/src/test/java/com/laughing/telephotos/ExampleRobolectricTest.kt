package com.laughing.telephotos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.TelegramCaptionMetadata
import com.laughing.telephotos.core.TelegramCaptionPayload
import com.laughing.telephotos.data.local.AlbumEntity
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.data.local.TelePhotosDatabase
import com.laughing.telephotos.data.local.UploadStatus
import com.laughing.telephotos.data.repository.VaultRepository
import com.laughing.telephotos.data.telegram.TelegramPolicyGuard
import com.laughing.telephotos.data.telegram.TelegramUploadMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `app name matches Tele Photos`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Tele Photos", appName)
  }

  @Test
  fun `caption metadata round trip preserves hash and legacy topic`() {
    val payload = TelegramCaptionPayload(
      contentHash = "abc123def456",
      takenAtEpochMs = 1732400000000L,
      durationSec = 65,
      topicId = 999L,
      isTrashed = false,
      fileName = "clip.mp4",
      mediaKind = "VIDEO",
    )
    val caption = TelegramCaptionMetadata.buildCaption(payload)
    assertFalse(caption.contains("{"))
    assertFalse(caption.contains("#tele_anh_meta"))
    val parsed = TelegramCaptionMetadata.parseFromCaption(caption)
    assertNotNull(parsed)
    assertEquals(payload.contentHash, parsed?.contentHash)
    assertEquals("clip.mp4", parsed?.fileName)
    assertEquals("VIDEO", parsed?.mediaKind)

    val legacyCaption = """
      clip.mp4
      #tele_anh_meta {"v":1,"hash":"abc123def456","ts":1732400000000,"dur":65,"topic":999,"trash":false,"name":"clip.mp4","type":"VIDEO"}
    """.trimIndent()
    val parsedLegacy = TelegramCaptionMetadata.parseFromCaption(legacyCaption)
    assertNotNull(parsedLegacy)
    assertEquals(999L, parsedLegacy?.topicId)
    assertEquals(65, parsedLegacy?.durationSec)
    assertEquals("1:05", Formatters.formatVideoDuration(65))
    assertEquals("0:14", Formatters.formatVideoDuration(14))
  }

  @Test
  fun `flood wait parser extracts wait seconds`() {
    assertEquals(42, TelegramPolicyGuard.parseFloodWaitSeconds("FLOOD_WAIT_42"))
    assertEquals(18, TelegramPolicyGuard.parseFloodWaitSeconds("Too Many Requests: retry after 18"))
    assertNull(TelegramPolicyGuard.parseFloodWaitSeconds("NETWORK_UNAVAILABLE"))
  }

  @Test
  fun `room deduplication and trash restore preserve previous album`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, TelePhotosDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val mediaDao = db.mediaDao()
      val albumDao = db.albumDao()

      val travelAlbumId = albumDao.upsert(
        AlbumEntity(
          title = "Du lịch",
          chatId = -100123L,
          messageThreadId = 10L,
          isTrashTopic = false,
        )
      )
      val trashAlbumId = albumDao.upsert(
        AlbumEntity(
          title = TelegramPolicyGuard.TRASH_TOPIC_TITLE,
          chatId = -100123L,
          messageThreadId = 77L,
          isTrashTopic = true,
        )
      )

      val photoId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "sha256_unique_1",
          fileName = "photo_1.jpg",
          mimeType = "image/jpeg",
          mediaType = MediaType.PHOTO,
          sizeBytes = 1024L,
          takenAtEpochMs = 1000L,
          albumId = travelAlbumId,
          topicId = 10L,
          uploadStatus = UploadStatus.UPLOADED,
        )
      )
      val videoId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "sha256_unique_video_1",
          fileName = "video_1.mp4",
          mimeType = "video/mp4",
          mediaType = MediaType.VIDEO,
          durationSec = 42,
          sizeBytes = 4096L,
          takenAtEpochMs = 1500L,
          albumId = travelAlbumId,
          topicId = 10L,
          uploadStatus = UploadStatus.QUEUED,
        )
      )
      val duplicateId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "sha256_unique_1",
          fileName = "photo_1_copy.jpg",
          mimeType = "image/jpeg",
          mediaType = MediaType.PHOTO,
          sizeBytes = 1024L,
          takenAtEpochMs = 2000L,
        )
      )
      assertTrue(photoId > 0L)
      assertTrue(videoId > 0L)
      assertEquals(-1L, duplicateId)

      assertEquals(1, mediaDao.observePhotosTimeline().first().size)
      assertEquals(1, mediaDao.observeVideosTimeline().first().size)
      assertEquals(1, mediaDao.observeAlbumPhotos(travelAlbumId).first().size)
      assertEquals(1, mediaDao.observeAlbumVideos(travelAlbumId).first().size)

      mediaDao.moveToTrash(
        id = photoId,
        trashedAt = 5000L,
        trashAlbumId = trashAlbumId,
        trashTopicId = 77L,
        trashMessageId = null,
      )
      assertEquals(0, mediaDao.observePhotosTimeline().first().size)
      assertEquals(1, mediaDao.observeVideosTimeline().first().size)
      assertEquals(1, mediaDao.observeTrashedMedia().first().size)

      mediaDao.restoreFromTrash(photoId)
      val restored = mediaDao.getById(photoId)
      assertNotNull(restored)
      assertFalse(restored!!.isTrashed)
      assertEquals(travelAlbumId, restored.albumId)
      assertEquals(10L, restored.topicId)
    } finally {
      db.close()
    }
  }

  @Test
  fun `album queries surface Photo and Video Forum Topics and match topicId items`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, TelePhotosDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val mediaDao = db.mediaDao()
      val albumDao = db.albumDao()

      val photosAlbumId = albumDao.upsert(
        AlbumEntity(
          title = TelegramPolicyGuard.PHOTOS_TOPIC_TITLE,
          chatId = -100555L,
          messageThreadId = 101L,
          isTrashTopic = false,
        )
      )
      val videosAlbumId = albumDao.upsert(
        AlbumEntity(
          title = TelegramPolicyGuard.VIDEOS_TOPIC_TITLE,
          chatId = -100555L,
          messageThreadId = 102L,
          isTrashTopic = false,
        )
      )
      val customAlbumId = albumDao.upsert(
        AlbumEntity(
          title = "Gia đình",
          chatId = -100555L,
          messageThreadId = 202L,
          isTrashTopic = false,
        )
      )
      albumDao.upsert(
        AlbumEntity(
          title = TelegramPolicyGuard.TRASH_TOPIC_TITLE,
          chatId = -100555L,
          messageThreadId = 999L,
          isTrashTopic = true,
        )
      )

      val topicPhotoId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "sha256_topic_photo_1",
          fileName = "TG_Photo_101.jpg",
          mimeType = "image/jpeg",
          mediaType = MediaType.PHOTO,
          sizeBytes = 2048L,
          takenAtEpochMs = 3000L,
          albumId = null,
          topicId = 101L,
          cachedLocalVideoPath = "/tmp/stale/cache.jpg",
          telegramFileId = 42,
          uploadStatus = UploadStatus.UPLOADED,
        )
      )
      mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "sha256_topic_video_1",
          fileName = "TG_Video_102.mp4",
          mimeType = "video/mp4",
          mediaType = MediaType.VIDEO,
          durationSec = 15,
          sizeBytes = 8192L,
          takenAtEpochMs = 3100L,
          albumId = null,
          topicId = 102L,
          uploadStatus = UploadStatus.UPLOADED,
        )
      )
      mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "sha256_custom_photo_1",
          fileName = "family.jpg",
          mimeType = "image/jpeg",
          mediaType = MediaType.PHOTO,
          sizeBytes = 1024L,
          takenAtEpochMs = 2900L,
          albumId = customAlbumId,
          topicId = 202L,
          uploadStatus = UploadStatus.UPLOADED,
        )
      )

      val albums = albumDao.observeAlbumsWithCount().first()
      assertEquals(listOf("Ảnh", "Video", "Gia đình"), albums.map { it.title })
      assertEquals(1, albums.first { it.id == photosAlbumId }.itemCount)
      assertEquals(1, albums.first { it.id == videosAlbumId }.itemCount)
      assertEquals(1, albums.first { it.id == customAlbumId }.itemCount)

      assertEquals(1, mediaDao.observeAlbumPhotos(photosAlbumId).first().size)
      assertEquals(1, mediaDao.observeAlbumVideos(videosAlbumId).first().size)

      mediaDao.clearCachedVideoPath(topicPhotoId)
      mediaDao.updateTelegramFileRefs(
        id = topicPhotoId,
        fileId = 888,
        remoteFileId = "remote_ref_888",
        thumbFileId = 889,
      )
      val refreshed = mediaDao.getById(topicPhotoId)!!
      assertNull(refreshed.cachedLocalVideoPath)
      assertEquals(888, refreshed.telegramFileId)
      assertEquals("remote_ref_888", refreshed.telegramRemoteFileId)
      assertEquals(889, refreshed.telegramThumbFileId)
    } finally {
      db.close()
    }
  }

  @Test
  fun `selected video batch isolates single video and ignores pre-existing queued photos and videos`() = runTest {
    val app = ApplicationProvider.getApplicationContext<TelePhotosApplication>()
    val db = Room.inMemoryDatabaseBuilder(app, TelePhotosDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    val prefs = app.settingsPreferences
    try {
      val mediaDao = db.mediaDao()
      val repo = VaultRepository(
        context = app,
        mediaDao = mediaDao,
        albumDao = db.albumDao(),
        syncTargetDao = db.syncTargetDao(),
        tdLibManager = app.tdLibManager,
        settingsPreferences = prefs,
        networkAlertCoordinator = app.networkAlertCoordinator,
      )

      val queuedPhotoId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "batch_queued_photo",
          fileName = "queued_photo.jpg",
          mimeType = "image/jpeg",
          mediaType = MediaType.PHOTO,
          sizeBytes = 1024L,
          takenAtEpochMs = 9000L,
          uploadStatus = UploadStatus.QUEUED,
        )
      )
      val pausedPhotoId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "batch_paused_photo",
          fileName = "paused_photo.jpg",
          mimeType = "image/jpeg",
          mediaType = MediaType.PHOTO,
          sizeBytes = 1024L,
          takenAtEpochMs = 8500L,
          uploadStatus = UploadStatus.PAUSED_FLOOD_WAIT,
        )
      )
      val queuedOtherVideoId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "batch_other_queued_video",
          fileName = "other_queued.mp4",
          mimeType = "video/mp4",
          mediaType = MediaType.VIDEO,
          durationSec = 20,
          sizeBytes = 2048L,
          takenAtEpochMs = 9500L,
          uploadStatus = UploadStatus.QUEUED,
        )
      )
      val pausedOtherVideoId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "batch_other_paused_video",
          fileName = "other_paused.mp4",
          mimeType = "video/mp4",
          mediaType = MediaType.VIDEO,
          durationSec = 25,
          sizeBytes = 2048L,
          takenAtEpochMs = 8000L,
          uploadStatus = UploadStatus.PAUSED_FLOOD_WAIT,
        )
      )
      val selectedVideoId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "batch_selected_video",
          fileName = "selected_only.mp4",
          mimeType = "video/mp4",
          mediaType = MediaType.VIDEO,
          durationSec = 12,
          sizeBytes = 4096L,
          takenAtEpochMs = 1000L,
          uploadStatus = UploadStatus.LOCAL_ONLY,
        )
      )

      val prepared = repo.prepareManualUploadBatch(
        selectedOnlyIds = listOf(selectedVideoId),
        filterMediaType = MediaType.VIDEO,
      )

      assertTrue(prepared.isSelectedBatch)
      assertEquals(MediaType.VIDEO, prepared.filterMediaType)
      assertEquals(listOf(selectedVideoId), prepared.selectedBatchIds)
      assertEquals(1, prepared.pendingCount)
      assertEquals(listOf(selectedVideoId), prefs.getSelectedUploadBatchIds())

      // Scoped queue returns ONLY the selected video, even though other QUEUED items have newer timestamps
      val next = repo.getNextPendingUploadScoped(prepared.selectedBatchIds, MediaType.VIDEO)
      assertEquals(selectedVideoId, next?.id)

      // Scoped FLOOD_WAIT requeue leaves unrelated paused photos/videos untouched
      repo.requeueFloodWaitPausedScoped(prepared.selectedBatchIds, MediaType.VIDEO)
      assertEquals(UploadStatus.PAUSED_FLOOD_WAIT, mediaDao.getById(pausedPhotoId)?.uploadStatus)
      assertEquals(UploadStatus.PAUSED_FLOOD_WAIT, mediaDao.getById(pausedOtherVideoId)?.uploadStatus)
      assertEquals(UploadStatus.QUEUED, mediaDao.getById(queuedPhotoId)?.uploadStatus)
      assertEquals(UploadStatus.QUEUED, mediaDao.getById(queuedOtherVideoId)?.uploadStatus)

      // Completing the selected video drains the scoped batch completely without touching other queued items
      mediaDao.markUploaded(
        id = selectedVideoId,
        chatId = -1001L,
        messageId = 55L,
        fileId = 10,
        remoteFileId = "remote_10",
        thumbFileId = null,
        albumId = null,
        topicId = 102L,
      )
      assertEquals(0, repo.countPendingUploadsScoped(prepared.selectedBatchIds, MediaType.VIDEO))
      assertNull(repo.getNextPendingUploadScoped(prepared.selectedBatchIds, MediaType.VIDEO))
    } finally {
      prefs.clearSelectedUploadBatchIds()
      db.close()
    }
  }

  @Test
  fun `empty or cross-type selection fails fast and Video tab sync excludes photos`() = runTest {
    val app = ApplicationProvider.getApplicationContext<TelePhotosApplication>()
    val db = Room.inMemoryDatabaseBuilder(app, TelePhotosDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    val prefs = app.settingsPreferences
    try {
      val mediaDao = db.mediaDao()
      val repo = VaultRepository(
        context = app,
        mediaDao = mediaDao,
        albumDao = db.albumDao(),
        syncTargetDao = db.syncTargetDao(),
        tdLibManager = app.tdLibManager,
        settingsPreferences = prefs,
        networkAlertCoordinator = app.networkAlertCoordinator,
      )

      val localPhotoId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "scope_local_photo",
          fileName = "local_photo.jpg",
          mimeType = "image/jpeg",
          mediaType = MediaType.PHOTO,
          sizeBytes = 1024L,
          takenAtEpochMs = 2000L,
          uploadStatus = UploadStatus.LOCAL_ONLY,
        )
      )
      val pausedPhotoId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "scope_paused_photo",
          fileName = "paused_photo.jpg",
          mimeType = "image/jpeg",
          mediaType = MediaType.PHOTO,
          sizeBytes = 1024L,
          takenAtEpochMs = 2100L,
          uploadStatus = UploadStatus.PAUSED_FLOOD_WAIT,
        )
      )
      val localVideoId = mediaDao.insertIgnore(
        MediaEntity(
          contentHash = "scope_local_video",
          fileName = "local_video.mp4",
          mimeType = "video/mp4",
          mediaType = MediaType.VIDEO,
          durationSec = 18,
          sizeBytes = 4096L,
          takenAtEpochMs = 2200L,
          uploadStatus = UploadStatus.LOCAL_ONLY,
        )
      )

      // 1. Empty selection must never fall back to full library sync
      val emptyError = runCatching {
        repo.prepareManualUploadBatch(selectedOnlyIds = emptyList(), filterMediaType = MediaType.VIDEO)
      }.exceptionOrNull()
      assertNotNull(emptyError)
      assertEquals(UploadStatus.LOCAL_ONLY, mediaDao.getById(localPhotoId)?.uploadStatus)
      assertEquals(UploadStatus.PAUSED_FLOOD_WAIT, mediaDao.getById(pausedPhotoId)?.uploadStatus)
      assertEquals(UploadStatus.LOCAL_ONLY, mediaDao.getById(localVideoId)?.uploadStatus)

      // 2. Selecting a PHOTO ID while inside VIDEO tab scope must fail without queuing the photo
      val crossTypeError = runCatching {
        repo.prepareManualUploadBatch(selectedOnlyIds = listOf(localPhotoId), filterMediaType = MediaType.VIDEO)
      }.exceptionOrNull()
      assertNotNull(crossTypeError)
      assertEquals(UploadStatus.LOCAL_ONLY, mediaDao.getById(localPhotoId)?.uploadStatus)

      // 3. Video-tab full sync (selectedOnlyIds = null, filterMediaType = VIDEO) queues ONLY videos
      prefs.setSelectedUploadBatchIds(listOf(999L))
      val videoTabBatch = repo.prepareManualUploadBatch(
        selectedOnlyIds = null,
        filterMediaType = MediaType.VIDEO,
      )
      assertFalse(videoTabBatch.isSelectedBatch)
      assertEquals(MediaType.VIDEO, videoTabBatch.filterMediaType)
      assertEquals(1, videoTabBatch.pendingCount)
      assertTrue(prefs.getSelectedUploadBatchIds().isEmpty())
      assertEquals(UploadStatus.QUEUED, mediaDao.getById(localVideoId)?.uploadStatus)
      assertEquals(UploadStatus.LOCAL_ONLY, mediaDao.getById(localPhotoId)?.uploadStatus)
      assertEquals(UploadStatus.PAUSED_FLOOD_WAIT, mediaDao.getById(pausedPhotoId)?.uploadStatus)
    } finally {
      prefs.clearSelectedUploadBatchIds()
      db.close()
    }
  }

  @Test
  fun `active upload batch mode persists and resets cleanly`() = runTest {
    val app = ApplicationProvider.getApplicationContext<TelePhotosApplication>()
    val prefs = app.settingsPreferences
    try {
      prefs.clearActiveUploadBatchMode()
      assertEquals(TelegramUploadMode.AS_FILE, prefs.getActiveUploadBatchMode())

      prefs.setActiveUploadBatchMode(TelegramUploadMode.HD_MEDIA)
      assertEquals(TelegramUploadMode.HD_MEDIA, prefs.getActiveUploadBatchMode())

      prefs.clearActiveUploadBatchMode()
      assertEquals(TelegramUploadMode.AS_FILE, prefs.getActiveUploadBatchMode())
    } finally {
      prefs.clearActiveUploadBatchMode()
    }
  }
}
