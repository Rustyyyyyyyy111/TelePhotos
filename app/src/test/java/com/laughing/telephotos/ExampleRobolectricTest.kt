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
import com.laughing.telephotos.data.telegram.TelegramPolicyGuard
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
  fun `caption metadata round trip preserves hash and topic`() {
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
    val parsed = TelegramCaptionMetadata.parseFromCaption(caption)
    assertNotNull(parsed)
    assertEquals(payload.contentHash, parsed?.contentHash)
    assertEquals(payload.topicId, parsed?.topicId)
    assertEquals(65, parsed?.durationSec)
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
}
