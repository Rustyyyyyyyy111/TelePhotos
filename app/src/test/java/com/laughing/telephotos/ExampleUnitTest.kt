package com.laughing.telephotos

import com.laughing.telephotos.core.AppSettings
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.TelegramCaptionMetadata
import com.laughing.telephotos.core.TelegramCaptionPayload
import com.laughing.telephotos.data.telegram.TelegramConnectionStatus
import com.laughing.telephotos.data.telegram.TelegramPolicyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun captionMetadata_roundTrip_preservesHashAndTopic() {
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
  }

  @Test
  fun videoDurationFormatter_outputsExpectedCompactBadges() {
    assertEquals("0:14", Formatters.formatVideoDuration(14))
    assertEquals("1:05", Formatters.formatVideoDuration(65))
    assertEquals("1:01:05", Formatters.formatVideoDuration(3665))
  }

  @Test
  fun floodWaitParser_extractsWaitSeconds() {
    assertEquals(42, TelegramPolicyGuard.parseFloodWaitSeconds("FLOOD_WAIT_42"))
    assertEquals(18, TelegramPolicyGuard.parseFloodWaitSeconds("Too Many Requests: retry after 18"))
    assertNull(TelegramPolicyGuard.parseFloodWaitSeconds("NETWORK_UNAVAILABLE"))
  }

  @Test
  fun phoneNormalizer_convertsLeadingZeroToVietnamPrefix() {
    assertEquals("+84912345678", Formatters.normalizePhoneNumber("0912345678"))
    assertEquals("+84912345678", Formatters.normalizePhoneNumber("+84 912-345-678"))
    assertEquals("+84912345678", Formatters.normalizePhoneNumber("912345678"))
  }

  @Test
  fun connectionStatus_andDefaultSettings_startDisconnectedOffline() {
    val defaults = AppSettings()
    assertFalse(defaults.isTelegramLoggedIn)
    assertEquals("", defaults.lastSyncStatusMessage)
    assertTrue(TelegramConnectionStatus.DISCONNECTED_IDLE.labelVi.contains("Đã ngắt kết nối"))
  }
}
