package com.laughing.telephotos

import com.laughing.telephotos.core.AppSettings
import com.laughing.telephotos.core.CacheCleanupManager
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.NetworkAlertCoordinator
import com.laughing.telephotos.core.NetworkOperationType
import com.laughing.telephotos.core.NetworkRetryPolicy
import com.laughing.telephotos.core.NetworkRetryTracker
import com.laughing.telephotos.core.RetryDecision
import com.laughing.telephotos.core.TelegramCaptionMetadata
import com.laughing.telephotos.core.TelegramCaptionPayload
import com.laughing.telephotos.data.local.SyncTargetType
import com.laughing.telephotos.data.telegram.TelegramCandidateTarget
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
  fun captionMetadata_roundTrip_preservesHashTopicAndSize() {
    val payload = TelegramCaptionPayload(
      contentHash = "abc123def456",
      takenAtEpochMs = 1732400000000L,
      durationSec = 65,
      topicId = 999L,
      isTrashed = false,
      fileName = "clip.mp4",
      mediaKind = "VIDEO",
      sizeBytes = 12_345_678L,
    )
    val caption = TelegramCaptionMetadata.buildCaption(payload)
    val parsed = TelegramCaptionMetadata.parseFromCaption(caption)
    assertNotNull(parsed)
    assertEquals(payload.contentHash, parsed?.contentHash)
    assertEquals(payload.topicId, parsed?.topicId)
    assertEquals(65, parsed?.durationSec)
    assertEquals(12_345_678L, parsed?.sizeBytes)
  }

  @Test
  fun vietnameseTopicNormalization_preventsDuplicateTrashAndSystemTopics() {
    assertEquals("da xoa gan day", Formatters.normalizeTopicKey("Đã xoá gần đây"))
    assertEquals("da xoa gan day", Formatters.normalizeTopicKey("Đã xóa gần đây"))
    assertEquals("da xoa gan day", Formatters.normalizeTopicKey("  ĐÃ  XOÁ   GẦN ĐÂY "))

    assertTrue(TelegramPolicyGuard.isTrashTopicTitle("Đã xoá gần đây"))
    assertTrue(TelegramPolicyGuard.isTrashTopicTitle("Đã xóa gần đây"))
    assertTrue(TelegramPolicyGuard.isSystemAutoTopicTitle("Ảnh"))
    assertTrue(TelegramPolicyGuard.isSystemAutoTopicTitle("Video"))
    assertTrue(TelegramPolicyGuard.isSystemAutoTopicTitle("Đã Xoá Gần Đây"))
    assertFalse(TelegramPolicyGuard.isSystemAutoTopicTitle("Du lịch Đà Lạt"))
  }

  @Test
  fun vietnameseSearch_matchesWithAndWithoutDiacritics() {
    assertTrue(TelegramPolicyGuard.matchesVietnameseSearch("Gia đình Sài Gòn", "gia dinh"))
    assertTrue(TelegramPolicyGuard.matchesVietnameseSearch("Đà Lạt 2026", "da lat"))
    assertFalse(TelegramPolicyGuard.matchesVietnameseSearch("Ảnh công việc", "du lich"))
  }

  @Test
  fun cacheCleanupManager_uses500MbDefaultCap() {
    assertEquals(500L * 1024L * 1024L, CacheCleanupManager.MAX_ONLINE_CACHE_BYTES)
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

  @Test
  fun targetPrioritySorting_placesGroupsBeforeChannelsAndSavedMessages() {
    val saved = TelegramCandidateTarget(1L, "Saved", SyncTargetType.SAVED_MESSAGES, false, "")
    val channel = TelegramCandidateTarget(2L, "Channel", SyncTargetType.PRIVATE_CHANNEL, false, "")
    val basic = TelegramCandidateTarget(3L, "Basic Group", SyncTargetType.BASIC_GROUP, false, "")
    val forum = TelegramCandidateTarget(4L, "Forum Group", SyncTargetType.PRIVATE_SUPERGROUP, true, "")

    val sorted = TelegramPolicyGuard.sortTargetsByPriority(listOf(saved, channel, basic, forum))
    assertEquals(listOf(4L, 3L, 2L, 1L), sorted.map { it.chatId })
  }

  @Test
  fun trashRetentionFormatter_computesRemainingDaysAccurately() {
    val now = 1_700_000_000_000L
    assertEquals(30, Formatters.computeRemainingTrashDays(now, now))
    assertEquals("Còn lại 30 ngày", Formatters.formatRemainingTrashDays(now, now))
    assertEquals(10, Formatters.computeRemainingTrashDays(now - 20L * Formatters.DAY_MS, now))
    assertEquals(0, Formatters.computeRemainingTrashDays(now - 31L * Formatters.DAY_MS, now))
  }

  @Test
  fun networkRetryTracker_waits8SecondsTwiceAndStopsOnThirdFailure() {
    val tracker = NetworkRetryTracker()
    assertEquals(RetryDecision.WaitAndRetry(1, 8_000L), tracker.onFailure())
    assertEquals(RetryDecision.WaitAndRetry(2, 8_000L), tracker.onFailure())
    assertEquals(RetryDecision.StopAndNotify, tracker.onFailure())

    tracker.reset()
    assertEquals(0, tracker.consecutiveFailures)
    assertEquals(RetryDecision.WaitAndRetry(1, 8_000L), tracker.onFailure())
  }

  @Test
  fun networkAlertCoordinator_preventsDuplicateDialogsAndUsesRequiredVietnameseCopy() {
    val coordinator = NetworkAlertCoordinator()
    coordinator.reportFinalFailure(NetworkOperationType.UPLOAD)
    val first = coordinator.activeAlert.value
    assertNotNull(first)
    assertEquals("Không thể kết nối Telegram", first?.titleVi)
    assertEquals("Đã hiểu", first?.confirmButtonVi)
    assertTrue(first!!.messageVi.contains("Tải ảnh/video lên Telegram bị gián đoạn."))
    assertTrue(first.messageVi.contains(NetworkRetryPolicy.ALERT_BODY_VI))

    // Second simultaneous report must not overwrite/stack existing unacknowledged alert
    coordinator.reportFinalFailure(NetworkOperationType.DOWNLOAD)
    assertEquals(first.id, coordinator.activeAlert.value?.id)

    coordinator.dismissAlert()
    assertNull(coordinator.activeAlert.value)
  }
}
