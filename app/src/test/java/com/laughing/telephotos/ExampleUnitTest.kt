package com.laughing.telephotos

import com.laughing.telephotos.core.AppIconPreset
import com.laughing.telephotos.core.AppSettings
import com.laughing.telephotos.core.CacheCleanupManager
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.MediaStoreScanner
import com.laughing.telephotos.core.NetworkAlertCoordinator
import com.laughing.telephotos.core.NetworkInterruptedException
import com.laughing.telephotos.core.NetworkOperationType
import com.laughing.telephotos.core.NetworkRetryPolicy
import com.laughing.telephotos.core.NetworkRetryTracker
import com.laughing.telephotos.core.RetryDecision
import com.laughing.telephotos.core.SettingsPreferences
import com.laughing.telephotos.core.TelegramCaptionMetadata
import com.laughing.telephotos.core.TelegramCaptionPayload
import com.laughing.telephotos.data.local.AlbumSyncStatus
import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.data.local.SyncTargetType
import com.laughing.telephotos.data.repository.SyncCoordinator
import com.laughing.telephotos.data.repository.TopicSyncKey
import com.laughing.telephotos.data.telegram.FloodWaitException
import com.laughing.telephotos.data.telegram.HistoryEndSignal
import com.laughing.telephotos.data.telegram.HistoryPaginationEngine
import com.laughing.telephotos.data.telegram.HistoryRawMessage
import com.laughing.telephotos.data.telegram.ScannedTelegramMedia
import com.laughing.telephotos.data.telegram.TelegramCandidateTarget
import com.laughing.telephotos.data.telegram.TelegramConnectionStatus
import com.laughing.telephotos.data.telegram.TelegramPolicyGuard
import com.laughing.telephotos.data.telegram.TelegramUploadMode
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun captionMetadata_cleanTwoLineFormat_hasNoJsonAndParsesTags() {
    val payload = TelegramCaptionPayload(
      contentHash = "abc123def4567890fedcba9876543210",
      takenAtEpochMs = 1732400000000L,
      durationSec = 65,
      topicId = 999L,
      isTrashed = true,
      fileName = "clip.mp4",
      mediaKind = "VIDEO",
      sizeBytes = 12_345_678L,
    )
    val caption = TelegramCaptionMetadata.buildCaption(payload)
    assertFalse(caption.contains("{"))
    assertFalse(caption.contains("}"))
    assertFalse(caption.contains("#tele_anh_meta"))
    assertTrue(caption.lines().size <= 2)
    assertTrue(caption.contains("#tele_anh"))
    assertTrue(caption.contains("#h_abc123def4567890"))
    assertTrue(caption.contains("#trash"))

    val parsed = TelegramCaptionMetadata.parseFromCaption(caption)
    assertNotNull(parsed)
    assertEquals("abc123def4567890", parsed?.contentHash)
    assertEquals("clip.mp4", parsed?.fileName)
    assertEquals("VIDEO", parsed?.mediaKind)
    assertTrue(parsed?.isTrashed == true)
    assertTrue((parsed?.takenAtEpochMs ?: 0L) > 0L)
  }

  @Test
  fun captionMetadata_legacyJsonFormat_remainsBackwardCompatible() {
    val legacyCaption = """
      clip.mp4
      #tele_anh_meta {"v":1,"hash":"abc123def456","ts":1732400000000,"dur":65,"topic":999,"trash":false,"name":"clip.mp4","type":"VIDEO","size":12345678}
    """.trimIndent()
    val parsed = TelegramCaptionMetadata.parseFromCaption(legacyCaption)
    assertNotNull(parsed)
    assertEquals("abc123def456", parsed?.contentHash)
    assertEquals(999L, parsed?.topicId)
    assertEquals(65, parsed?.durationSec)
    assertEquals(12_345_678L, parsed?.sizeBytes)
  }

  @Test
  fun captionMetadata_extractReadableTitle_andAppIconPresets_workCleanly() {
    assertEquals(
      "Du lịch Đà Lạt.jpg",
      TelegramCaptionMetadata.extractReadableTitle("Du lịch Đà Lạt\n#dulich", "tg_1001.jpg"),
    )
    assertEquals(
      "tg_1001.jpg",
      TelegramCaptionMetadata.extractReadableTitle("#onlytag", "tg_1001.jpg"),
    )

    val aliases = AppIconPreset.entries.map { it.aliasClassName }.toSet()
    assertEquals(3, aliases.size)
    assertTrue(aliases.any { it.endsWith(".LauncherIconDefault") })
    assertTrue(aliases.any { it.endsWith(".LauncherIconDark") })
    assertTrue(aliases.any { it.endsWith(".LauncherIconMono") })
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

  @Test
  fun policyGuard_blocksDestructiveTelegramFunctionsAndAllowsReadUpload() {
    assertFalse(TelegramPolicyGuard.isAllowedFunction(TdApi.DeleteMessages(1L, longArrayOf(2L), true)))
    assertFalse(TelegramPolicyGuard.isAllowedFunction(TdApi.DeleteChatHistory(1L, true, true)))
    assertFalse(TelegramPolicyGuard.isAllowedFunction(TdApi.DeleteForumTopic(1L, 2)))
    assertFalse(TelegramPolicyGuard.isAllowedFunction(TdApi.DeleteChat(1L)))

    assertTrue(TelegramPolicyGuard.isAllowedFunction(TdApi.GetForumTopics(1L, "", 0, 0L, 0, 10)))
    assertTrue(TelegramPolicyGuard.isAllowedFunction(TdApi.SendMessage()))
    assertTrue(TelegramPolicyGuard.isAllowedFunction(TdApi.ForwardMessages()))

    var securityExceptionThrown = false
    try {
      TelegramPolicyGuard.assertAllowedFunction(TdApi.DeleteMessages(1L, longArrayOf(2L), true))
    } catch (_: SecurityException) {
      securityExceptionThrown = true
    }
    assertTrue(securityExceptionThrown)
  }

  @Test
  fun dynamicFloodWait_computesTieredSafetyBuffersAndUnlockTimestamp() {
    assertEquals(3, TelegramPolicyGuard.computeFloodWaitBufferSeconds(12))
    assertEquals(3, TelegramPolicyGuard.computeFloodWaitBufferSeconds(30))
    assertEquals(5, TelegramPolicyGuard.computeFloodWaitBufferSeconds(31))
    assertEquals(5, TelegramPolicyGuard.computeFloodWaitBufferSeconds(300))
    assertEquals(10, TelegramPolicyGuard.computeFloodWaitBufferSeconds(301))

    val baseNow = 1_700_000_000_000L
    assertEquals(baseNow + (20 + 3) * 1000L, TelegramPolicyGuard.computeFloodWaitUnlockAtEpochMs(20, baseNow))
    assertEquals(baseNow + (120 + 5) * 1000L, TelegramPolicyGuard.computeFloodWaitUnlockAtEpochMs(120, baseNow))
    assertEquals(baseNow + (600 + 10) * 1000L, TelegramPolicyGuard.computeFloodWaitUnlockAtEpochMs(600, baseNow))

    val settingsActive = AppSettings(floodWaitUntilEpochMs = baseNow + 10_000L)
    assertTrue(settingsActive.isFloodWaitActive(baseNow))
    assertFalse(settingsActive.isFloodWaitActive(baseNow + 10_000L))
  }

  @Test
  fun networkRetryPolicy_separatesFloodWaitAndSecurityFromTransientDisconnects() {
    val floodErr = FloodWaitException(45, 1_700_000_045_000L, "FLOOD_WAIT_45")
    assertTrue(NetworkRetryPolicy.isFloodWaitError(floodErr))
    assertFalse(NetworkRetryPolicy.isTransientNetworkError(floodErr))
    assertFalse(NetworkRetryPolicy.isTransientNetworkError(IllegalStateException("Too Many Requests: retry after 19")))
    assertFalse(NetworkRetryPolicy.isTransientNetworkError(SecurityException("Blocked")))

    assertTrue(NetworkRetryPolicy.isTransientNetworkError(NetworkInterruptedException("Mất mạng")))
    assertTrue(NetworkRetryPolicy.isTransientNetworkError(IllegalStateException("Client is closed")))
  }

  @Test
  fun photoGridColumns_clampsBetween2And5_andDefaultsToAutoDestination() {
    val defaults = AppSettings()
    assertEquals(3, defaults.photoGridColumns)
    assertEquals(0L, defaults.defaultUploadAlbumId)

    assertEquals(2, SettingsPreferences.clampPhotoGridColumns(0))
    assertEquals(2, SettingsPreferences.clampPhotoGridColumns(2))
    assertEquals(4, SettingsPreferences.clampPhotoGridColumns(4))
    assertEquals(5, SettingsPreferences.clampPhotoGridColumns(5))
    assertEquals(5, SettingsPreferences.clampPhotoGridColumns(12))
  }

  @Test
  fun mediaStoreScanner_isUsableLocalFile_rejectsMissingAndZeroByteFiles() {
    assertFalse(MediaStoreScanner.isUsableLocalFile(null))
    assertFalse(MediaStoreScanner.isUsableLocalFile("   "))
    assertFalse(MediaStoreScanner.isUsableLocalFile("/nonexistent/telephotos/missing.jpg"))

    val tempEmpty = File.createTempFile("tp_empty_", ".jpg")
    val tempValid = File.createTempFile("tp_valid_", ".jpg")
    try {
      assertEquals(0L, tempEmpty.length())
      assertFalse(MediaStoreScanner.isUsableLocalFile(tempEmpty.absolutePath))

      tempValid.writeBytes(byteArrayOf(1, 2, 3, 4))
      assertTrue(MediaStoreScanner.isUsableLocalFile(tempValid.absolutePath))
    } finally {
      tempEmpty.delete()
      tempValid.delete()
    }
  }

  @Test
  fun telegramUploadMode_fromWireName_resolvesModesAndDefaultsToAsFile() {
    assertEquals(TelegramUploadMode.AS_FILE, TelegramUploadMode.fromWireName("AS_FILE"))
    assertEquals(TelegramUploadMode.HD_MEDIA, TelegramUploadMode.fromWireName("HD_MEDIA"))
    assertEquals(TelegramUploadMode.AS_FILE, TelegramUploadMode.fromWireName(null))
    assertEquals(TelegramUploadMode.AS_FILE, TelegramUploadMode.fromWireName("UNKNOWN_MODE"))
    assertTrue(TelegramUploadMode.AS_FILE.labelVi.contains("Send as File"))
    assertTrue(TelegramUploadMode.HD_MEDIA.labelVi.contains("Send HD"))
  }

  @Test
  fun captionMetadata_preservesUserCaptionAndSyncModeFooterTags() {
    val payload = TelegramCaptionPayload(
      contentHash = "fedcba98765432100123456789abcdef",
      takenAtEpochMs = 1732400000000L,
      fileName = "sunset.jpg",
      mediaKind = "PHOTO",
      syncMode = TelegramUploadMode.HD_MEDIA.name,
      userCaption = "Hoàng hôn Đà Lạt\nNgày thứ hai",
    )
    val caption = TelegramCaptionMetadata.buildCaption(payload)
    assertTrue(caption.startsWith("Hoàng hôn Đà Lạt\nNgày thứ hai\n📅 "))
    assertTrue(caption.contains("#tele_anh"))
    assertTrue(caption.contains("#t_photo"))
    assertTrue(caption.contains("#m_hd"))
    assertTrue(caption.contains("#h_fedcba9876543210"))

    val parsed = TelegramCaptionMetadata.parseFromCaption(caption)
    assertNotNull(parsed)
    assertEquals("Hoàng hôn Đà Lạt\nNgày thứ hai", parsed?.userCaption)
    assertEquals("PHOTO", parsed?.mediaKind)
    assertEquals("HD_MEDIA", parsed?.syncMode)
    assertEquals("fedcba9876543210", parsed?.contentHash)
    assertTrue((parsed?.takenAtEpochMs ?: 0L) > 0L)

    // AS_FILE footer check
    val fileCaption = TelegramCaptionMetadata.buildCaption(
      payload.copy(
        mediaKind = "VIDEO",
        syncMode = TelegramUploadMode.AS_FILE.name,
        userCaption = null,
        fileName = "raw_clip.mov",
      )
    )
    assertTrue(fileCaption.lines().size == 2)
    assertTrue(fileCaption.contains("#t_video"))
    assertTrue(fileCaption.contains("#m_file"))
    val parsedFile = TelegramCaptionMetadata.parseFromCaption(fileCaption)
    assertEquals("VIDEO", parsedFile?.mediaKind)
    assertEquals("AS_FILE", parsedFile?.syncMode)
    assertNull(parsedFile?.userCaption)

    // Plain native Telegram caption without Tele Photos tags falls back cleanly (returns null)
    assertNull(TelegramCaptionMetadata.parseFromCaption("Ảnh chụp gia đình cuối tuần"))
  }

  @Test
  fun captionMetadata_bareEmojiWithoutExplicitAppTag_returnsNull() {
    assertNull(TelegramCaptionMetadata.parseFromCaption("🖼️ Ảnh hoàng hôn bãi biển"))
    assertNull(TelegramCaptionMetadata.parseFromCaption("🎞️ Video du lịch gia đình\n📅 24/11/2024 10:15"))
  }

  @Test
  fun historyPaginationEngine_overlappingPagesAndConservativeBackfill_behaveSafely() = runTest {
    fun fakeMedia(id: Long) = ScannedTelegramMedia(
      chatId = -1001L,
      messageId = id,
      topicId = 10L,
      fileId = id.toInt(),
      remoteFileId = "r_$id",
      telegramFileUniqueId = "u_$id",
      thumbFileId = null,
      fileName = "img_$id.jpg",
      mimeType = "image/jpeg",
      mediaType = MediaType.PHOTO,
      sizeBytes = 100L,
      durationSec = 0,
      takenAtEpochMs = id * 1000L,
      fullContentHash = null,
      shortHashHint = null,
      syncMode = null,
      userCaption = null,
      isTrashed = false,
      metadataVersion = 4,
    )

    // Overlapping page boundary returns id=50 twice without duplicating or looping infinitely
    var calls = 0
    val forward = HistoryPaginationEngine.fetchIncrementalForward(
      sinceMessageId = 40L,
      startFromMessageId = 0L,
      maxPages = 5,
      pageSize = 3,
      pageDelayMs = 0L,
    ) { fromId, _ ->
      calls++
      when (calls) {
        1 -> listOf(55L, 52L, 50L).map { HistoryRawMessage(it, false, fakeMedia(it)) }
        2 -> listOf(50L, 45L, 40L).map { HistoryRawMessage(it, false, fakeMedia(it)) }
        else -> emptyList()
      }
    }
    assertTrue(forward.hitKnownCheckpoint)
    assertEquals(listOf(55L, 52L, 50L, 45L), forward.items.map { it.messageId })

    // Short backfill page without creation marker => UNCERTAIN_EMPTY_OR_SHORT
    val shortBackfill = HistoryPaginationEngine.fetchOlderBackfill(
      beforeMessageId = 50L,
      maxPages = 2,
      pageSize = 25,
      pageDelayMs = 0L,
    ) { fromId, _ ->
      if (fromId > 49L) {
        listOf(HistoryRawMessage(49L, isCreationMarker = false, media = fakeMedia(49L)))
      } else {
        emptyList()
      }
    }
    assertEquals(HistoryEndSignal.UNCERTAIN_EMPTY_OR_SHORT, shortBackfill.endSignal)

    // Backfill hitting topic creation marker => CONFIRMED_CREATION_END
    val confirmedBackfill = HistoryPaginationEngine.fetchOlderBackfill(
      beforeMessageId = 50L,
      maxPages = 2,
      pageSize = 25,
      pageDelayMs = 0L,
    ) { _, _ ->
      listOf(
        HistoryRawMessage(2L, isCreationMarker = false, media = fakeMedia(2L)),
        HistoryRawMessage(1L, isCreationMarker = true, media = null),
      )
    }
    assertEquals(HistoryEndSignal.CONFIRMED_CREATION_END, confirmedBackfill.endSignal)
  }

  @Test
  fun syncCoordinator_serializesSameTopic_allowsOtherTopic_andHandlesCancelOrError() = runTest {
    val coordinator = SyncCoordinator()
    val keyA = TopicSyncKey(-1001L, 10L)
    val keyB = TopicSyncKey(-1001L, 20L)

    val gateA1 = CompletableDeferred<Unit>()
    val enteredA1 = CompletableDeferred<Unit>()
    val events = mutableListOf<String>()

    val jobA1 = async {
      coordinator.runSerialized(key = keyA, mode = AlbumSyncStatus.SYNCING) {
        events.add("A1_start")
        enteredA1.complete(Unit)
        gateA1.await()
        events.add("A1_end")
      }
    }
    enteredA1.await()
    assertEquals(AlbumSyncStatus.SYNCING, coordinator.currentState(keyA).status)

    // Second job on same topic A must wait for A1, while topic B runs concurrently
    val jobA2 = async {
      coordinator.runSerialized(key = keyA, mode = AlbumSyncStatus.BACKFILLING) {
        events.add("A2_run")
      }
    }
    val jobB = async {
      coordinator.runSerialized(key = keyB, mode = AlbumSyncStatus.SYNCING) {
        events.add("B_run")
      }
    }
    jobB.await()
    assertTrue("B should finish before A1 releases", "B_run" in events && "A2_run" !in events)

    gateA1.complete(Unit)
    jobA1.await()
    jobA2.await()
    assertEquals(listOf("A1_start", "B_run", "A1_end", "A2_run"), events)
    assertEquals(AlbumSyncStatus.IDLE, coordinator.currentState(keyA).status)

    // Error state transitions cleanly to ERROR and resets to IDLE on next success
    runCatching {
      coordinator.runSerialized(key = keyA, mode = AlbumSyncStatus.SYNCING) {
        error("Network timeout")
      }
    }
    assertEquals(AlbumSyncStatus.ERROR, coordinator.currentState(keyA).status)
    assertEquals("Network timeout", coordinator.currentState(keyA).lastError)

    // Cancellation recovers cleanly to IDLE without sticking
    val cancelGate = CompletableDeferred<Unit>()
    val cancelJob = launch {
      coordinator.runSerialized(key = keyA, mode = AlbumSyncStatus.SYNCING) {
        cancelGate.complete(Unit)
        delay(10_000L)
      }
    }
    cancelGate.await()
    cancelJob.cancel(CancellationException("user cancelled"))
    cancelJob.join()
    assertEquals(AlbumSyncStatus.IDLE, coordinator.currentState(keyA).status)
  }
}
