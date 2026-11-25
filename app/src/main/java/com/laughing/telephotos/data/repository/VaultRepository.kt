package com.laughing.telephotos.data.repository

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.laughing.telephotos.core.CacheCleanupManager
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.LocalDeletePrepResult
import com.laughing.telephotos.core.MediaHashUtil
import com.laughing.telephotos.core.MediaStoreScanner
import com.laughing.telephotos.core.NetworkAlertCoordinator
import com.laughing.telephotos.core.NetworkInterruptionAlert
import com.laughing.telephotos.core.NetworkOperationType
import com.laughing.telephotos.core.NetworkRetryPolicy
import com.laughing.telephotos.core.SettingsPreferences
import com.laughing.telephotos.core.TelegramCaptionMetadata
import com.laughing.telephotos.core.TelegramCaptionPayload
import com.laughing.telephotos.data.local.AlbumDao
import com.laughing.telephotos.data.local.AlbumEntity
import com.laughing.telephotos.data.local.AlbumWithCount
import com.laughing.telephotos.data.local.MediaDao
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.data.local.StorageStats
import com.laughing.telephotos.data.local.SyncTargetDao
import com.laughing.telephotos.data.local.SyncTargetEntity
import com.laughing.telephotos.data.local.UploadStatus
import com.laughing.telephotos.data.telegram.FileDownloadProgress
import com.laughing.telephotos.data.telegram.FloodWaitException
import com.laughing.telephotos.data.telegram.FloodWaitState
import com.laughing.telephotos.data.telegram.ScannedTelegramMedia
import com.laughing.telephotos.data.telegram.TdLibLifecycleState
import com.laughing.telephotos.data.telegram.TdLibManager
import com.laughing.telephotos.data.telegram.TelegramAuthState
import com.laughing.telephotos.data.telegram.TelegramCandidateTarget
import com.laughing.telephotos.data.telegram.TelegramConnectionStatus
import com.laughing.telephotos.data.telegram.TelegramPolicyGuard
import com.laughing.telephotos.worker.TelegramUploadWorker
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ImportSummary(
  val addedCount: Int,
  val duplicateSkippedCount: Int,
)

sealed interface SaveToDeviceProgress {
  data class Downloading(val fraction: Float, val statusVi: String) : SaveToDeviceProgress
  data class Completed(val savedUri: Uri) : SaveToDeviceProgress
}

data class FreeUpPreparation(
  val eligibleMediaIds: List<Long>,
  val skippedUnsyncedCount: Int,
  val prepResult: LocalDeletePrepResult,
  val thumbPathOverrides: Map<Long, String>,
)

data class ResolvedUploadDestination(
  val albumId: Long?,
  val topicId: Long?,
)

data class ManualSyncProgressState(
  val isRunning: Boolean = false,
  val currentFileName: String = "",
  val completedCount: Int = 0,
  val totalCount: Int = 0,
) {
  val fraction: Float
    get() = if (totalCount > 0) (completedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f) else 0f
}

class VaultRepository(
  private val context: Context,
  private val mediaDao: MediaDao,
  private val albumDao: AlbumDao,
  private val syncTargetDao: SyncTargetDao,
  private val tdLibManager: TdLibManager,
  private val settingsPreferences: SettingsPreferences,
  private val networkAlertCoordinator: NetworkAlertCoordinator = NetworkAlertCoordinator(),
) {

  private val mediaStoreScanMutex = Mutex()

  val authState: StateFlow<TelegramAuthState> = tdLibManager.authState
  val connectionStatus: StateFlow<TelegramConnectionStatus> = tdLibManager.connectionStatus
  val lifecycleState: StateFlow<TdLibLifecycleState> = tdLibManager.lifecycleState
  val floodWaitState: StateFlow<FloodWaitState?> = tdLibManager.floodWaitState
  val networkInterruptionAlert: StateFlow<NetworkInterruptionAlert?> = networkAlertCoordinator.activeAlert
  val photosTimelineFlow: Flow<List<MediaEntity>> = mediaDao.observePhotosTimeline()
  val videosTimelineFlow: Flow<List<MediaEntity>> = mediaDao.observeVideosTimeline()
  val trashedMediaFlow: Flow<List<MediaEntity>> = mediaDao.observeTrashedMedia()
  val albumsWithCountFlow: Flow<List<AlbumWithCount>> = albumDao.observeAlbumsWithCount()
  val uploadQueueFlow: Flow<List<MediaEntity>> = mediaDao.observeUploadQueue()
  val storageStatsFlow: Flow<StorageStats> = mediaDao.observeStorageStats()
  val lockedTargetFlow: Flow<SyncTargetEntity?> = syncTargetDao.observeTarget()

  fun dismissNetworkInterruptionAlert() {
    networkAlertCoordinator.dismissAlert()
  }

  fun reportNetworkInterruption(type: NetworkOperationType) {
    networkAlertCoordinator.reportFinalFailure(type)
  }

  val manualSyncProgressFlow: Flow<ManualSyncProgressState> =
    WorkManager.getInstance(context)
      .getWorkInfosForUniqueWorkFlow(TelegramUploadWorker.UNIQUE_WORK_NAME)
      .map { infos ->
        val active = infos.firstOrNull {
          it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        }
        if (active == null) {
          ManualSyncProgressState(isRunning = false)
        } else {
          val progress = active.progress
          ManualSyncProgressState(
            isRunning = true,
            currentFileName = progress.getString(TelegramUploadWorker.KEY_CURRENT_FILE).orEmpty(),
            completedCount = progress.getInt(TelegramUploadWorker.KEY_COMPLETED_COUNT, 0),
            totalCount = progress.getInt(TelegramUploadWorker.KEY_TOTAL_COUNT, 0),
          )
        }
      }

  fun observeAlbum(albumId: Long): Flow<AlbumEntity?> = albumDao.observeAlbum(albumId)

  fun observeAlbumPhotos(albumId: Long): Flow<List<MediaEntity>> =
    mediaDao.observeAlbumPhotos(albumId)

  fun observeAlbumVideos(albumId: Long): Flow<List<MediaEntity>> =
    mediaDao.observeAlbumVideos(albumId)

  fun observeMediaById(mediaId: Long): Flow<MediaEntity?> = mediaDao.observeById(mediaId)

  /**
   * Indexes picked media locally ONLY (2-Layer SHA-256 deduplication via MediaStore/ContentResolver).
   * Updates local Room immediately offline so new photos/videos appear without touching Telegram.
   */
  suspend fun importSelectedUris(
    uris: List<Uri>,
    targetAlbumId: Long? = null,
  ): ImportSummary {
    if (uris.isEmpty()) return ImportSummary(0, 0)

    val lockedTarget = syncTargetDao.getTarget()
    val targetAlbum = targetAlbumId?.let { albumDao.getAlbum(it) }
    var added = 0
    var duplicates = 0

    for (uri in uris) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
      }

      val meta = runCatching { MediaHashUtil.extractAndHash(context, uri) }.getOrNull() ?: continue
      val existing = mediaDao.findByHash(meta.contentHash)
      if (existing != null) {
        if (existing.localUri == null) {
          mediaDao.upsert(existing.copy(localUri = uri.toString()))
        }
        duplicates++
        continue
      }

      val status = if (lockedTarget != null) UploadStatus.QUEUED else UploadStatus.LOCAL_ONLY
      val entity = MediaEntity(
        contentHash = meta.contentHash,
        localUri = uri.toString(),
        fileName = meta.fileName,
        mimeType = meta.mimeType,
        mediaType = meta.mediaType,
        sizeBytes = meta.sizeBytes,
        width = meta.width,
        height = meta.height,
        durationSec = meta.durationSec,
        takenAtEpochMs = meta.takenAtEpochMs,
        albumId = targetAlbum?.id,
        topicId = targetAlbum?.messageThreadId ?: lockedTarget?.defaultMessageThreadId,
        uploadStatus = status,
      )
      mediaDao.insertIgnore(entity)
      added++
    }

    return ImportSummary(addedCount = added, duplicateSkippedCount = duplicates)
  }

  /**
   * Scans device MediaStore (Images & Videos) and merges with existing Room / Telegram items.
   * Deduplicates by localUri first and by (fileName, sizeBytes, mediaType) second so
   * Telegram-synced items and local items never appear twice.
   */
  suspend fun syncDeviceMediaStore(
    scanPhotos: Boolean = true,
    scanVideos: Boolean = true,
  ): ImportSummary = mediaStoreScanMutex.withLock {
    val deviceEntries = buildList {
      if (scanPhotos) addAll(MediaStoreScanner.scanDeviceImages(context))
      if (scanVideos) addAll(MediaStoreScanner.scanDeviceVideos(context))
    }

    val existingAll = mediaDao.getAllMediaSnapshot()
    val byLocalUri = HashMap<String, MediaEntity>(existingAll.size)
    val byNameAndSize = HashMap<String, MutableList<MediaEntity>>(existingAll.size)
    val knownHashes = HashSet<String>(existingAll.size)

    for (item in existingAll) {
      knownHashes.add(item.contentHash)
      item.localUri?.let { byLocalUri[it] = item }
      if (item.sizeBytes > 0L) {
        val key = "${item.mediaType.name}|${item.fileName.lowercase()}|${item.sizeBytes}"
        byNameAndSize.getOrPut(key) { mutableListOf() }.add(item)
      }
    }

    var added = 0
    var merged = 0
    val liveUris = HashSet<String>(deviceEntries.size)

    for (entry in deviceEntries) {
      liveUris.add(entry.contentUri)
      if (byLocalUri.containsKey(entry.contentUri)) {
        continue
      }

      val matchKey = "${entry.mediaType.name}|${entry.fileName.lowercase()}|${entry.sizeBytes}"
      val candidates = byNameAndSize[matchKey].orEmpty()
      val mergeTarget = candidates.firstOrNull { candidate ->
        candidate.localUri == null || candidate.localUri.contains("/picker")
      }

      if (mergeTarget != null) {
        val updated = mergeTarget.copy(
          localUri = entry.contentUri,
          width = if (mergeTarget.width == 0) entry.width else mergeTarget.width,
          height = if (mergeTarget.height == 0) entry.height else mergeTarget.height,
          durationSec = if (mergeTarget.durationSec == 0) entry.durationSec else mergeTarget.durationSec,
          updatedAtEpochMs = System.currentTimeMillis(),
        )
        mediaDao.upsert(updated)
        byLocalUri[entry.contentUri] = updated
        val bucket = byNameAndSize[matchKey]
        if (bucket != null) {
          val idx = bucket.indexOfFirst { it.id == mergeTarget.id }
          if (idx >= 0) bucket[idx] = updated
        }
        merged++
        continue
      }

      var candidateHash = "ms_${entry.mediaType.name.lowercase()}_${entry.mediaStoreId}"
      if (!knownHashes.add(candidateHash)) {
        candidateHash = "${candidateHash}_${System.nanoTime()}"
        knownHashes.add(candidateHash)
      }

      val newEntity = MediaEntity(
        contentHash = candidateHash,
        localUri = entry.contentUri,
        fileName = entry.fileName,
        mimeType = entry.mimeType,
        mediaType = entry.mediaType,
        sizeBytes = entry.sizeBytes,
        width = entry.width,
        height = entry.height,
        durationSec = entry.durationSec,
        takenAtEpochMs = entry.takenAtEpochMs,
        uploadStatus = UploadStatus.LOCAL_ONLY,
      )
      mediaDao.insertIgnore(newEntity)
      byLocalUri[entry.contentUri] = newEntity
      byNameAndSize.getOrPut(matchKey) { mutableListOf() }.add(newEntity)
      added++
    }

    // Reconcile files deleted externally from device MediaStore when full permission is active
    val canReconcilePhotos = scanPhotos && MediaStoreScanner.hasFullMediaReadPermission(context, MediaType.PHOTO)
    val canReconcileVideos = scanVideos && MediaStoreScanner.hasFullMediaReadPermission(context, MediaType.VIDEO)
    val staleLocalOnlyIds = mutableListOf<Long>()
    for (existing in existingAll) {
      val uri = existing.localUri ?: continue
      if (!uri.startsWith("content://media/external/")) continue
      val shouldCheck = (existing.mediaType == MediaType.PHOTO && canReconcilePhotos) ||
        (existing.mediaType == MediaType.VIDEO && canReconcileVideos)
      if (shouldCheck && uri !in liveUris) {
        if (existing.uploadStatus == UploadStatus.UPLOADED) {
          mediaDao.updateLocalUri(existing.id, null)
        } else if (!existing.isTrashed && existing.uploadStatus == UploadStatus.LOCAL_ONLY) {
          staleLocalOnlyIds.add(existing.id)
        }
      }
    }
    if (staleLocalOnlyIds.isNotEmpty()) {
      mediaDao.deleteByIds(staleLocalOnlyIds)
    }

    ImportSummary(addedCount = added, duplicateSkippedCount = merged)
  }

  /**
   * Ensures a persistent AlbumEntity exists for a Telegram Topic (including system "Ảnh" & "Video")
   * without replacing existing rows (which would regenerate the primary key ID).
   */
  suspend fun ensureSystemMediaTopicAlbum(
    chatId: Long,
    threadId: Long,
    title: String,
    iconColor: Int,
  ): Long {
    val existing = albumDao.getByChatAndThread(chatId, threadId)
    if (existing != null) {
      return existing.id
    }
    return albumDao.upsert(
      AlbumEntity(
        title = title,
        chatId = chatId,
        messageThreadId = threadId,
        isTrashTopic = false,
        iconColor = iconColor,
      )
    )
  }

  /**
   * Normalizes legacy trash album flags and ensures "Ảnh" / "Video" Forum Topic cards exist
   * when a Forum-enabled Telegram target is locked.
   */
  suspend fun pruneSystemTopicAlbums() {
    val allAlbums = albumDao.getAllAlbums()
    for (album in allAlbums) {
      if (TelegramPolicyGuard.isTrashTopicTitle(album.title) && !album.isTrashTopic) {
        albumDao.upsert(album.copy(isTrashTopic = true, title = TelegramPolicyGuard.TRASH_TOPIC_TITLE))
      }
    }
    val lockedTarget = syncTargetDao.getTarget()
    if (lockedTarget != null && lockedTarget.isForumEnabled) {
      lockedTarget.photosThreadId?.takeIf { it > 0L }?.let { photosId ->
        ensureSystemMediaTopicAlbum(
          chatId = lockedTarget.chatId,
          threadId = photosId,
          title = TelegramPolicyGuard.PHOTOS_TOPIC_TITLE,
          iconColor = 0x6FB9F0,
        )
      }
      lockedTarget.videosThreadId?.takeIf { it > 0L }?.let { videosId ->
        ensureSystemMediaTopicAlbum(
          chatId = lockedTarget.chatId,
          threadId = videosId,
          title = TelegramPolicyGuard.VIDEOS_TOPIC_TITLE,
          iconColor = 0xCB86DB,
        )
      }
    }
  }

  /**
   * Loads allowlisted private targets reusing the active TDLib session.
   */
  suspend fun loadAllowedTargets(): List<TelegramCandidateTarget> =
    tdLibManager.withActiveSession(markSyncing = false) {
      tdLibManager.loadAllowedTargets()
    }

  /**
   * Locks an allowlisted destination and ensures the 3 core topics ("Ảnh", "Video", "Đã Xoá Gần Đây").
   */
  suspend fun lockSyncTarget(candidate: TelegramCandidateTarget): Result<SyncTargetEntity> =
    runCatching {
      var resolvedTrashId: Long? = null
      var resolvedPhotosId: Long? = null
      var resolvedVideosId: Long? = null

      val currentDefaultAlbumId = settingsPreferences.getCurrentSettings().defaultUploadAlbumId
      if (currentDefaultAlbumId > 0L) {
        val currentDefaultAlbum = albumDao.getAlbum(currentDefaultAlbumId)
        if (currentDefaultAlbum == null || currentDefaultAlbum.chatId != candidate.chatId) {
          settingsPreferences.setDefaultUploadAlbumId(0L)
        }
      }

      if (candidate.isForumEnabled) {
        tdLibManager.withActiveSession(markSyncing = true) {
          val core = tdLibManager.ensureThreeCoreTopics(candidate.chatId)
          resolvedPhotosId = core.photosThreadId
          resolvedVideosId = core.videosThreadId
          resolvedTrashId = core.trashThreadId

          if (resolvedTrashId != null &&
            albumDao.getByChatAndThread(candidate.chatId, resolvedTrashId!!) == null
          ) {
            albumDao.upsert(
              AlbumEntity(
                title = TelegramPolicyGuard.TRASH_TOPIC_TITLE,
                chatId = candidate.chatId,
                messageThreadId = resolvedTrashId!!,
                isTrashTopic = true,
                iconColor = 0xFB6F5F,
              )
            )
          }

          if (resolvedPhotosId != null) {
            ensureSystemMediaTopicAlbum(
              chatId = candidate.chatId,
              threadId = resolvedPhotosId!!,
              title = TelegramPolicyGuard.PHOTOS_TOPIC_TITLE,
              iconColor = 0x6FB9F0,
            )
          }
          if (resolvedVideosId != null) {
            ensureSystemMediaTopicAlbum(
              chatId = candidate.chatId,
              threadId = resolvedVideosId!!,
              title = TelegramPolicyGuard.VIDEOS_TOPIC_TITLE,
              iconColor = 0xCB86DB,
            )
          }

          for (topic in core.allTopics) {
            if (TelegramPolicyGuard.isTrashTopicTitle(topic.title)) {
              continue
            }
            if (albumDao.getByChatAndThread(candidate.chatId, topic.messageThreadId) == null) {
              albumDao.upsert(
                AlbumEntity(
                  title = topic.title,
                  chatId = candidate.chatId,
                  messageThreadId = topic.messageThreadId,
                  isTrashTopic = false,
                  iconColor = topic.iconColor,
                )
              )
            }
          }
        }
      }

      if (!candidate.isForumEnabled &&
        albumDao.getByChatAndThread(candidate.chatId, 0L) == null
      ) {
        albumDao.upsert(
          AlbumEntity(
            title = candidate.title,
            chatId = candidate.chatId,
            messageThreadId = 0L,
            isTrashTopic = false,
            iconColor = 0x6FB9F0,
          )
        )
      }

      val target = SyncTargetEntity(
        id = 1,
        chatId = candidate.chatId,
        chatTitle = candidate.title,
        targetType = candidate.targetType,
        isForumEnabled = candidate.isForumEnabled,
        defaultMessageThreadId = null,
        photosThreadId = resolvedPhotosId,
        videosThreadId = resolvedVideosId,
        trashThreadId = resolvedTrashId,
        isLocked = true,
        confirmedAtEpochMs = System.currentTimeMillis(),
      )
      syncTargetDao.saveTarget(target)
      pruneSystemTopicAlbums()
      target
    }

  /**
   * Resolves both Album row ID and Telegram Forum Topic ID for an upload item:
   * - Explicit `media.albumId` takes top priority.
   * - Otherwise falls back to Setting's `defaultUploadAlbumId` if valid for the locked chat.
   * - Otherwise auto-routes to "Ảnh" (PHOTO) or "Video" (VIDEO) Forum Topic and ensures its Album card exists.
   */
  suspend fun resolveUploadDestination(
    media: MediaEntity,
    target: SyncTargetEntity,
  ): ResolvedUploadDestination {
    val latestTarget = syncTargetDao.getTarget() ?: target
    val explicitAlbum = media.albumId?.let { albumDao.getAlbum(it) }
      ?.takeIf { !it.isTrashTopic && it.chatId == latestTarget.chatId }

    val configuredDefaultAlbum = if (explicitAlbum == null) {
      val defaultAlbumId = settingsPreferences.getCurrentSettings().defaultUploadAlbumId
      if (defaultAlbumId > 0L) {
        albumDao.getAlbum(defaultAlbumId)
          ?.takeIf { !it.isTrashTopic && it.chatId == latestTarget.chatId }
      } else {
        null
      }
    } else {
      null
    }

    val chosenAlbum = explicitAlbum ?: configuredDefaultAlbum
    if (!latestTarget.isForumEnabled) {
      val streamAlbumId = chosenAlbum?.id
        ?: albumDao.getByChatAndThread(latestTarget.chatId, 0L)?.id
        ?: albumDao.upsert(
          AlbumEntity(
            title = latestTarget.chatTitle,
            chatId = latestTarget.chatId,
            messageThreadId = 0L,
            isTrashTopic = false,
            iconColor = 0x6FB9F0,
          )
        )
      return ResolvedUploadDestination(
        albumId = streamAlbumId,
        topicId = latestTarget.defaultMessageThreadId,
      )
    }

    if (chosenAlbum != null) {
      return ResolvedUploadDestination(
        albumId = chosenAlbum.id,
        topicId = chosenAlbum.messageThreadId.takeIf { it > 0L },
      )
    }

    return when (media.mediaType) {
      MediaType.PHOTO -> {
        val threadId = latestTarget.photosThreadId ?: tdLibManager.findOrCreateMediaTopic(
          chatId = latestTarget.chatId,
          title = TelegramPolicyGuard.PHOTOS_TOPIC_TITLE,
          iconColor = 0x6FB9F0,
        ).also { createdId ->
          syncTargetDao.updatePhotosThreadId(createdId)
        }
        val albumId = ensureSystemMediaTopicAlbum(
          chatId = latestTarget.chatId,
          threadId = threadId,
          title = TelegramPolicyGuard.PHOTOS_TOPIC_TITLE,
          iconColor = 0x6FB9F0,
        )
        ResolvedUploadDestination(albumId = albumId, topicId = threadId)
      }
      MediaType.VIDEO -> {
        val threadId = latestTarget.videosThreadId ?: tdLibManager.findOrCreateMediaTopic(
          chatId = latestTarget.chatId,
          title = TelegramPolicyGuard.VIDEOS_TOPIC_TITLE,
          iconColor = 0xCB86DB,
        ).also { createdId ->
          syncTargetDao.updateVideosThreadId(createdId)
        }
        val albumId = ensureSystemMediaTopicAlbum(
          chatId = latestTarget.chatId,
          threadId = threadId,
          title = TelegramPolicyGuard.VIDEOS_TOPIC_TITLE,
          iconColor = 0xCB86DB,
        )
        ResolvedUploadDestination(albumId = albumId, topicId = threadId)
      }
    }
  }

  suspend fun resolveUploadTopicId(media: MediaEntity, target: SyncTargetEntity): Long? =
    resolveUploadDestination(media, target).topicId

  suspend fun updateAlbumCoverIfMissing(albumId: Long?, candidateCoverUri: String?) {
    if (albumId == null || candidateCoverUri.isNullOrBlank()) return
    val album = albumDao.getAlbum(albumId) ?: return
    if (album.coverUri.isNullOrBlank()) {
      albumDao.updateCoverUri(albumId, candidateCoverUri)
    }
  }

  /**
   * Creates an Album (Forum Topic via active TDLib session when Forum-enabled).
   */
  suspend fun createAlbum(title: String, iconColor: Int = 0x6FB9F0): Result<AlbumEntity> =
    runCatching {
      val cleanTitle = title.trim()
      require(cleanTitle.isNotBlank()) { "Tên album không được để trống" }
      require(!TelegramPolicyGuard.isTrashTopicTitle(cleanTitle)) {
        "Tên này được dành riêng cho thùng rác"
      }
      require(
        !TelegramPolicyGuard.isPhotosAutoTopicTitle(cleanTitle) &&
          !TelegramPolicyGuard.isVideosAutoTopicTitle(cleanTitle)
      ) {
        "Tên «Ảnh» và «Video» được dành riêng cho chủ đề tự động của hệ thống"
      }

      val target = syncTargetDao.getTarget()
        ?: error("Vui lòng chọn và khoá đích lưu trữ Telegram trước khi tạo Album")
      val settings = settingsPreferences.getCurrentSettings()

      val threadId = if (target.isForumEnabled && settings.isTelegramLoggedIn) {
        tdLibManager.withActiveSession(markSyncing = true) {
          tdLibManager.findOrCreateMediaTopic(
            chatId = target.chatId,
            title = cleanTitle,
            iconColor = iconColor,
          )
        }
      } else {
        System.currentTimeMillis()
      }

      val existing = albumDao.getByChatAndThread(target.chatId, threadId)
      if (existing != null) return@runCatching existing

      val album = AlbumEntity(
        title = cleanTitle,
        chatId = target.chatId,
        messageThreadId = threadId,
        isTrashTopic = false,
        iconColor = iconColor,
      )
      val id = albumDao.upsert(album)
      album.copy(id = id)
    }

  /**
   * Strict Read-Only + Upload policy: NEVER deletes Telegram messages.
   * For synced items on a Forum-enabled group, forwards a copy of the Telegram message
   * into the "Đã Xoá Gần Đây" topic and moves the local entry to Trash.
   */
  suspend fun moveMediaToRecentlyDeleted(
    mediaIds: List<Long>,
    clearLocalUris: Boolean = false,
    thumbPathOverrides: Map<Long, String> = emptyMap(),
  ): Result<Int> = runCatching {
    if (mediaIds.isEmpty()) return@runCatching 0
    val target = syncTargetDao.getTarget()
    val settings = settingsPreferences.getCurrentSettings()
    var trashAlbum = target?.let { albumDao.getTrashAlbum(it.chatId) }
    var trashTopicId = target?.trashThreadId ?: trashAlbum?.messageThreadId

    val items = mediaIds.mapNotNull { mediaDao.getById(it) }
    val needsTelegramAction = target != null && target.isForumEnabled && settings.isTelegramLoggedIn &&
      NetworkRetryPolicy.isNetworkAvailable(context) &&
      (trashTopicId == null || items.any { it.telegramMessageId != null && it.telegramChatId != null && it.trashTopicMessageId == null })

    val forwardedTrashMsgIds = mutableMapOf<Long, Long>()
    if (needsTelegramAction && target != null) {
      runCatching {
        tdLibManager.withActiveSession(markSyncing = true) {
          val resolvedTrashTopicId = trashTopicId ?: tdLibManager.findOrCreateTrashTopic(target.chatId)
          trashTopicId = resolvedTrashTopicId
          syncTargetDao.updateTrashThreadId(resolvedTrashTopicId)
          val insertedId = albumDao.upsert(
            AlbumEntity(
              title = TelegramPolicyGuard.TRASH_TOPIC_TITLE,
              chatId = target.chatId,
              messageThreadId = resolvedTrashTopicId,
              isTrashTopic = true,
              iconColor = 0xFB6F5F,
            )
          )
          trashAlbum = albumDao.getAlbum(insertedId)

          for (item in items) {
            val sourceMsgId = item.telegramMessageId
            val sourceChatId = item.telegramChatId
            if (sourceMsgId != null && sourceChatId != null && item.trashTopicMessageId == null &&
              item.topicId != resolvedTrashTopicId
            ) {
              val fwdId = runCatching {
                tdLibManager.forwardMessageToTrashTopic(
                  chatId = target.chatId,
                  fromChatId = sourceChatId,
                  sourceMessageId = sourceMsgId,
                  trashThreadId = resolvedTrashTopicId,
                )
              }.getOrNull()
              if (fwdId != null) {
                forwardedTrashMsgIds[item.id] = fwdId
              }
            }
          }
        }
      }
    }

    val now = System.currentTimeMillis()
    var count = 0
    for (id in mediaIds) {
      thumbPathOverrides[id]?.let { thumbPath ->
        mediaDao.updateCachedThumbPath(id, thumbPath, now)
      }
      mediaDao.moveToTrash(
        id = id,
        trashedAt = now,
        trashAlbumId = trashAlbum?.id,
        trashTopicId = trashTopicId,
        trashMessageId = forwardedTrashMsgIds[id],
      )
      if (clearLocalUris) {
        mediaDao.updateLocalUri(id, null, now)
      }
      count++
    }
    count
  }

  data class DeletePreparation(
    val mediaIds: List<Long>,
    val prepResult: LocalDeletePrepResult,
    val thumbPathOverrides: Map<Long, String>,
  )

  suspend fun prepareSystemDelete(mediaIds: List<Long>): DeletePreparation {
    val items = mediaIds.mapNotNull { mediaDao.getById(it) }
    val thumbOverrides = mutableMapOf<Long, String>()
    for (item in items) {
      if (item.uploadStatus == UploadStatus.UPLOADED && item.localUri != null) {
        MediaStoreScanner.cacheSmallThumbnailForTrash(context, item)?.let { thumb ->
          thumbOverrides[item.id] = thumb
        }
      }
    }
    val prep = MediaStoreScanner.prepareSystemLocalDelete(context, items)
    return DeletePreparation(
      mediaIds = mediaIds,
      prepResult = prep,
      thumbPathOverrides = thumbOverrides,
    )
  }

  suspend fun finalizeConfirmedDelete(
    mediaIds: List<Long>,
    deletedLocalUris: Set<String>,
    thumbOverrides: Map<Long, String>,
  ): Result<String> = runCatching {
    val items = mediaIds.mapNotNull { mediaDao.getById(it) }
    val moveToTrashSyncedIds = mutableListOf<Long>()
    val moveToTrashUnverifiedLocalIds = mutableListOf<Long>()
    val removeLocalOnlyIds = mutableListOf<Long>()

    for (item in items) {
      val uri = item.localUri
      val localWasDeleted = uri != null && uri in deletedLocalUris
      if (item.uploadStatus == UploadStatus.UPLOADED) {
        moveToTrashSyncedIds.add(item.id)
      } else if (localWasDeleted) {
        removeLocalOnlyIds.add(item.id)
      } else {
        moveToTrashUnverifiedLocalIds.add(item.id)
      }
    }

    if (moveToTrashSyncedIds.isNotEmpty()) {
      moveMediaToRecentlyDeleted(
        mediaIds = moveToTrashSyncedIds,
        clearLocalUris = true,
        thumbPathOverrides = thumbOverrides,
      ).getOrThrow()
    }
    if (moveToTrashUnverifiedLocalIds.isNotEmpty()) {
      moveMediaToRecentlyDeleted(
        mediaIds = moveToTrashUnverifiedLocalIds,
        clearLocalUris = false,
        thumbPathOverrides = thumbOverrides,
      ).getOrThrow()
    }
    if (removeLocalOnlyIds.isNotEmpty()) {
      deleteLocalOnlyMediaRecords(removeLocalOnlyIds)
    }

    when {
      moveToTrashSyncedIds.isNotEmpty() && removeLocalOnlyIds.isNotEmpty() ->
        "Đã xoá tệp trên máy và chuyển tiếp ${moveToTrashSyncedIds.size} mục sang «Đã Xoá Gần Đây»"
      moveToTrashSyncedIds.isNotEmpty() ->
        "Đã xoá trên máy và chuyển tiếp ${moveToTrashSyncedIds.size} mục sang «Đã Xoá Gần Đây»"
      removeLocalOnlyIds.isNotEmpty() ->
        "Đã xoá ${removeLocalOnlyIds.size} mục khỏi thiết bị"
      else ->
        "Đã chuyển ${moveToTrashUnverifiedLocalIds.size} mục vào «Đã xoá gần đây»"
    }
  }

  suspend fun deleteLocalOnlyMediaRecords(mediaIds: List<Long>): Int {
    if (mediaIds.isEmpty()) return 0
    for (id in mediaIds) {
      mediaDao.getById(id)?.let { deleteInternalCachedFiles(it) }
    }
    mediaDao.deleteByIds(mediaIds)
    return mediaIds.size
  }

  suspend fun restoreFromRecentlyDeleted(mediaIds: List<Long>): Int {
    val now = System.currentTimeMillis()
    for (id in mediaIds) {
      mediaDao.restoreFromTrash(id = id, restoredAt = now)
    }
    return mediaIds.size
  }

  /**
   * Local-only permanent delete: deletes Room entries and internal app-cached files only.
   * NEVER deletes or edits anything on Telegram.
   */
  suspend fun permanentlyDeleteLocalMedia(mediaIds: List<Long>): Int {
    if (mediaIds.isEmpty()) return 0
    for (id in mediaIds) {
      mediaDao.getById(id)?.let { deleteInternalCachedFiles(it) }
    }
    mediaDao.deleteByIds(mediaIds)
    return mediaIds.size
  }

  /**
   * Local-only permanent delete of all items in "Đã xóa gần đây".
   * NEVER touches Telegram messages or topics.
   */
  suspend fun permanentlyDeleteAllTrashed(): Int {
    val trashed = mediaDao.getAllTrashedItems()
    if (trashed.isEmpty()) return 0
    for (item in trashed) {
      deleteInternalCachedFiles(item)
    }
    mediaDao.deleteAllTrashed()
    return trashed.size
  }

  /**
   * Automatically removes locally trashed entries older than 30 days.
   */
  suspend fun purgeExpiredTrashedMedia(nowMs: Long = System.currentTimeMillis()): Int {
    val cutoff = nowMs - (Formatters.TRASH_RETENTION_DAYS * Formatters.DAY_MS)
    val expired = mediaDao.getExpiredTrashedItems(cutoff)
    if (expired.isEmpty()) return 0
    for (item in expired) {
      deleteInternalCachedFiles(item)
    }
    return mediaDao.deleteExpiredTrashed(cutoff)
  }

  private fun deleteInternalCachedFiles(item: MediaEntity) {
    val cacheRoot = context.cacheDir.absolutePath
    val filesRoot = context.filesDir.absolutePath
    listOfNotNull(item.cachedLocalVideoPath, item.cachedThumbPath).forEach { path ->
      runCatching {
        val file = File(path)
        val abs = file.absolutePath
        if (file.exists() && (abs.startsWith(cacheRoot) || abs.startsWith(filesRoot))) {
          file.delete()
        }
      }
    }
  }

  suspend fun assignMediaToAlbum(mediaIds: List<Long>, albumId: Long) {
    val album = albumDao.getAlbum(albumId) ?: return
    mediaDao.assignToAlbum(ids = mediaIds, albumId = album.id, topicId = album.messageThreadId)
    if (album.coverUri == null) {
      val firstMedia = mediaIds.firstOrNull()?.let { mediaDao.getById(it) }
      if (firstMedia?.localUri != null) {
        albumDao.updateCoverUri(album.id, firstMedia.localUri)
      }
    }
  }

  /**
   * Read-only history reconstruction reusing the active TDLib session.
   */
  suspend fun syncHistoryReadOnly(): Result<Int> = runCatching {
    val target = syncTargetDao.getTarget()
      ?: error("Chưa khoá đích lưu trữ Telegram")
    val settings = settingsPreferences.getCurrentSettings()
    if (!settings.isTelegramLoggedIn) {
      error("Vui lòng đăng nhập Telegram trong Setting trước khi quét chỉ đọc")
    }

    val existingAll = mediaDao.getAllMediaSnapshot()
    val existingHashes = existingAll.filter { it.uploadStatus == UploadStatus.UPLOADED }
      .map { it.contentHash }.toHashSet()
    val reconstructedCount = tdLibManager.withActiveSession(markSyncing = true) {
      var totalAdded = 0
      if (target.isForumEnabled) {
        val topics = tdLibManager.getForumTopics(target.chatId)
        for (topic in topics) {
          val isTrash = TelegramPolicyGuard.isTrashTopicTitle(topic.title)
          val isPhotosAuto = TelegramPolicyGuard.isPhotosAutoTopicTitle(topic.title)
          val isVideosAuto = TelegramPolicyGuard.isVideosAutoTopicTitle(topic.title)

          val albumId: Long? = when {
            isPhotosAuto -> {
              syncTargetDao.updatePhotosThreadId(topic.messageThreadId)
              ensureSystemMediaTopicAlbum(
                chatId = target.chatId,
                threadId = topic.messageThreadId,
                title = TelegramPolicyGuard.PHOTOS_TOPIC_TITLE,
                iconColor = topic.iconColor,
              )
            }
            isVideosAuto -> {
              syncTargetDao.updateVideosThreadId(topic.messageThreadId)
              ensureSystemMediaTopicAlbum(
                chatId = target.chatId,
                threadId = topic.messageThreadId,
                title = TelegramPolicyGuard.VIDEOS_TOPIC_TITLE,
                iconColor = topic.iconColor,
              )
            }
            isTrash -> {
              syncTargetDao.updateTrashThreadId(topic.messageThreadId)
              val existingTrash = albumDao.getByChatAndThread(target.chatId, topic.messageThreadId)
              existingTrash?.id ?: albumDao.upsert(
                AlbumEntity(
                  title = TelegramPolicyGuard.TRASH_TOPIC_TITLE,
                  chatId = target.chatId,
                  messageThreadId = topic.messageThreadId,
                  isTrashTopic = true,
                  iconColor = topic.iconColor,
                )
              )
            }
            else -> {
              val existingAlbum = albumDao.getByChatAndThread(target.chatId, topic.messageThreadId)
              existingAlbum?.id ?: albumDao.upsert(
                AlbumEntity(
                  title = topic.title,
                  chatId = target.chatId,
                  messageThreadId = topic.messageThreadId,
                  isTrashTopic = false,
                  iconColor = topic.iconColor,
                )
              )
            }
          }

          val scanned = tdLibManager.scanHistoryReadOnly(
            chatId = target.chatId,
            messageThreadId = topic.messageThreadId,
            limit = 60,
          )
          totalAdded += ingestScannedItems(scanned, albumId, isTrash, existingHashes)
          delay(200L)
        }
      } else {
        val streamAlbum = albumDao.getByChatAndThread(target.chatId, 0L)
          ?: albumDao.upsert(
            AlbumEntity(
              title = target.chatTitle,
              chatId = target.chatId,
              messageThreadId = 0L,
              isTrashTopic = false,
              iconColor = 0x6FB9F0,
            )
          ).let { insertedId -> albumDao.getAlbum(insertedId) }
        val scanned = tdLibManager.scanHistoryReadOnly(
          chatId = target.chatId,
          messageThreadId = target.defaultMessageThreadId,
          limit = 80,
        )
        totalAdded += ingestScannedItems(scanned, streamAlbum?.id, false, existingHashes)
      }
      totalAdded
    }

    pruneSystemTopicAlbums()
    settingsPreferences.updateLastHistorySync(System.currentTimeMillis())
    settingsPreferences.setLastSyncStatusMessage(
      "Đã quét chỉ đọc xong ($reconstructedCount mục mới)"
    )
    reconstructedCount
  }

  /**
   * Refreshes Album topics and pre-existing Telegram photos/videos/documents (including native
   * MessagePhoto without #tele_anh_meta captions) directly for the Album tab or AlbumDetailScreen.
   */
  suspend fun syncAlbumsFromTelegram(specificAlbumId: Long? = null): Result<Int> = runCatching {
    val target = syncTargetDao.getTarget()
      ?: error("Chưa khoá đích lưu trữ Telegram")
    val settings = settingsPreferences.getCurrentSettings()
    if (!settings.isTelegramLoggedIn) {
      error("Vui lòng đăng nhập Telegram trong Setting")
    }

    val existingHashes = mediaDao.getAllMediaSnapshot()
      .filter { it.uploadStatus == UploadStatus.UPLOADED }
      .map { it.contentHash }
      .toHashSet()

    val addedCount = tdLibManager.withActiveSession(markSyncing = true) {
      var totalAdded = 0
      if (specificAlbumId != null) {
        val album = albumDao.getAlbum(specificAlbumId) ?: return@withActiveSession 0
        val threadId = album.messageThreadId.takeIf { target.isForumEnabled && it != 0L }
        val scanned = tdLibManager.scanHistoryReadOnly(
          chatId = target.chatId,
          messageThreadId = threadId,
          limit = 80,
        )
        totalAdded += ingestScannedItems(scanned, album.id, album.isTrashTopic, existingHashes)
      } else if (target.isForumEnabled) {
        val core = tdLibManager.ensureThreeCoreTopics(target.chatId)
        core.photosThreadId?.let {
          syncTargetDao.updatePhotosThreadId(it)
          ensureSystemMediaTopicAlbum(
            chatId = target.chatId,
            threadId = it,
            title = TelegramPolicyGuard.PHOTOS_TOPIC_TITLE,
            iconColor = 0x6FB9F0,
          )
        }
        core.videosThreadId?.let {
          syncTargetDao.updateVideosThreadId(it)
          ensureSystemMediaTopicAlbum(
            chatId = target.chatId,
            threadId = it,
            title = TelegramPolicyGuard.VIDEOS_TOPIC_TITLE,
            iconColor = 0xCB86DB,
          )
        }
        core.trashThreadId?.let { syncTargetDao.updateTrashThreadId(it) }

        val topics = core.allTopics.ifEmpty { tdLibManager.getForumTopics(target.chatId) }
        for (topic in topics) {
          if (TelegramPolicyGuard.isTrashTopicTitle(topic.title)) {
            syncTargetDao.updateTrashThreadId(topic.messageThreadId)
            continue
          }
          val normalizedTitle = when {
            TelegramPolicyGuard.isPhotosAutoTopicTitle(topic.title) -> {
              syncTargetDao.updatePhotosThreadId(topic.messageThreadId)
              TelegramPolicyGuard.PHOTOS_TOPIC_TITLE
            }
            TelegramPolicyGuard.isVideosAutoTopicTitle(topic.title) -> {
              syncTargetDao.updateVideosThreadId(topic.messageThreadId)
              TelegramPolicyGuard.VIDEOS_TOPIC_TITLE
            }
            else -> topic.title
          }
          val albumId = albumDao.getByChatAndThread(target.chatId, topic.messageThreadId)?.id
            ?: albumDao.upsert(
              AlbumEntity(
                title = normalizedTitle,
                chatId = target.chatId,
                messageThreadId = topic.messageThreadId,
                isTrashTopic = false,
                iconColor = topic.iconColor,
              )
            )
          val scanned = tdLibManager.scanHistoryReadOnly(
            chatId = target.chatId,
            messageThreadId = topic.messageThreadId,
            limit = 60,
          )
          totalAdded += ingestScannedItems(scanned, albumId, false, existingHashes)
          delay(150L)
        }
      } else {
        val streamAlbumId = albumDao.getByChatAndThread(target.chatId, 0L)?.id
          ?: albumDao.upsert(
            AlbumEntity(
              title = target.chatTitle,
              chatId = target.chatId,
              messageThreadId = 0L,
              isTrashTopic = false,
              iconColor = 0x6FB9F0,
            )
          )
        val scanned = tdLibManager.scanHistoryReadOnly(
          chatId = target.chatId,
          messageThreadId = target.defaultMessageThreadId,
          limit = 80,
        )
        totalAdded += ingestScannedItems(scanned, streamAlbumId, false, existingHashes)
      }
      totalAdded
    }

    pruneSystemTopicAlbums()
    addedCount
  }

  /**
   * Fast read-only pull for a specific MediaType topic ("Ảnh" or "Video") called within
   * an already-open TDLib session before uploading local items, matching existing Telegram backups.
   */
  suspend fun syncActiveTabHistoryWhileConnected(
    target: SyncTargetEntity,
    filterMediaType: MediaType?,
  ): Int {
    val existingHashes = mediaDao.getAllMediaSnapshot()
      .filter { it.uploadStatus == UploadStatus.UPLOADED }
      .map { it.contentHash }
      .toHashSet()

    if (!target.isForumEnabled) {
      val streamAlbumId = albumDao.getByChatAndThread(target.chatId, 0L)?.id
      val scanned = tdLibManager.scanHistoryReadOnly(
        chatId = target.chatId,
        messageThreadId = target.defaultMessageThreadId,
        limit = 60,
      )
      return ingestScannedItems(scanned, streamAlbumId, false, existingHashes)
    }

    val core = tdLibManager.ensureThreeCoreTopics(target.chatId)
    core.photosThreadId?.let { syncTargetDao.updatePhotosThreadId(it) }
    core.videosThreadId?.let { syncTargetDao.updateVideosThreadId(it) }
    core.trashThreadId?.let { syncTargetDao.updateTrashThreadId(it) }

    var added = 0
    if (filterMediaType == null || filterMediaType == MediaType.PHOTO) {
      val pTopic = core.photosThreadId
      if (pTopic != null) {
        val photosAlbumId = ensureSystemMediaTopicAlbum(
          chatId = target.chatId,
          threadId = pTopic,
          title = TelegramPolicyGuard.PHOTOS_TOPIC_TITLE,
          iconColor = 0x6FB9F0,
        )
        val scannedPhotos = tdLibManager.scanHistoryReadOnly(target.chatId, pTopic, limit = 60)
        added += ingestScannedItems(scannedPhotos, photosAlbumId, false, existingHashes)
      }
    }
    if (filterMediaType == null || filterMediaType == MediaType.VIDEO) {
      val vTopic = core.videosThreadId
      if (vTopic != null) {
        val videosAlbumId = ensureSystemMediaTopicAlbum(
          chatId = target.chatId,
          threadId = vTopic,
          title = TelegramPolicyGuard.VIDEOS_TOPIC_TITLE,
          iconColor = 0xCB86DB,
        )
        val scannedVideos = tdLibManager.scanHistoryReadOnly(target.chatId, vTopic, limit = 60)
        added += ingestScannedItems(scannedVideos, videosAlbumId, false, existingHashes)
      }
    }
    return added
  }

  private suspend fun ingestScannedItems(
    scanned: List<ScannedTelegramMedia>,
    albumId: Long?,
    isTrashTopic: Boolean,
    existingHashes: MutableSet<String>,
  ): Int {
    var added = 0
    var thumbFetchCount = 0
    val maxQuickThumbsPerBatch = 24
    val localSnapshot = mediaDao.getAllMediaSnapshot()
    val byNameSize = HashMap<String, MediaEntity>()
    val byMessageKey = HashMap<String, MediaEntity>()
    val byShortHashPrefix = HashMap<String, MediaEntity>()
    var albumCoverNeeded = albumId?.let { id -> albumDao.getAlbum(id)?.coverUri == null } ?: false

    for (m in localSnapshot) {
      if (m.sizeBytes > 0L) {
        byNameSize["${m.mediaType.name}|${m.fileName.lowercase()}|${m.sizeBytes}"] = m
      }
      if (m.telegramChatId != null && m.telegramMessageId != null) {
        byMessageKey["${m.telegramChatId}_${m.telegramMessageId}"] = m
      }
      if (m.contentHash.length >= 12 && !m.contentHash.startsWith("ms_") && !m.contentHash.startsWith("tg_")) {
        byShortHashPrefix[m.contentHash.take(16)] = m
      }
    }

    for (item in scanned) {
      val msgMatch = byMessageKey["${item.chatId}_${item.messageId}"]
      if (msgMatch != null) {
        if (item.fileId != msgMatch.telegramFileId ||
          item.remoteFileId != msgMatch.telegramRemoteFileId ||
          item.thumbFileId != msgMatch.telegramThumbFileId
        ) {
          mediaDao.updateTelegramFileRefs(
            id = msgMatch.id,
            fileId = item.fileId,
            remoteFileId = item.remoteFileId,
            thumbFileId = item.thumbFileId,
          )
        }
        val needsAlbumOrTopic = !isTrashTopic &&
          ((albumId != null && msgMatch.albumId == null) ||
            (item.topicId != null && msgMatch.topicId == null))
        if (needsAlbumOrTopic) {
          mediaDao.assignToAlbum(
            ids = listOf(msgMatch.id),
            albumId = msgMatch.albumId ?: albumId,
            topicId = msgMatch.topicId ?: item.topicId,
          )
        }
        if (albumCoverNeeded && albumId != null) {
          val coverCandidate = msgMatch.localUri ?: msgMatch.cachedThumbPath
          if (!coverCandidate.isNullOrBlank()) {
            albumDao.updateCoverUri(albumId, coverCandidate)
            albumCoverNeeded = false
          }
        }
        continue
      }

      val prefixMatch = if (item.contentHash.length in 12..31) byShortHashPrefix[item.contentHash.take(16)] else null
      val existingByHash = prefixMatch ?: mediaDao.findByHash(item.contentHash)
      if (existingByHash != null) {
        if (existingByHash.uploadStatus != UploadStatus.UPLOADED) {
          mediaDao.markUploaded(
            id = existingByHash.id,
            chatId = item.chatId,
            messageId = item.messageId,
            fileId = item.fileId,
            remoteFileId = item.remoteFileId,
            topicId = item.topicId ?: existingByHash.topicId,
            thumbFileId = item.thumbFileId,
            albumId = if (!isTrashTopic) existingByHash.albumId ?: albumId else existingByHash.albumId,
          )
        } else if (item.fileId != existingByHash.telegramFileId ||
          item.remoteFileId != existingByHash.telegramRemoteFileId ||
          item.thumbFileId != existingByHash.telegramThumbFileId
        ) {
          mediaDao.updateTelegramFileRefs(
            id = existingByHash.id,
            fileId = item.fileId,
            remoteFileId = item.remoteFileId,
            thumbFileId = item.thumbFileId,
          )
        }
        val needsAlbumOrTopic = !isTrashTopic &&
          ((albumId != null && existingByHash.albumId == null) ||
            (item.topicId != null && existingByHash.topicId == null))
        if (needsAlbumOrTopic) {
          mediaDao.assignToAlbum(
            ids = listOf(existingByHash.id),
            albumId = existingByHash.albumId ?: albumId,
            topicId = existingByHash.topicId ?: item.topicId,
          )
        }
        var currentThumb = existingByHash.cachedThumbPath
        if (existingByHash.localUri == null &&
          !MediaStoreScanner.isUsableLocalFile(currentThumb) &&
          item.thumbFileId != null &&
          thumbFetchCount < maxQuickThumbsPerBatch
        ) {
          thumbFetchCount++
          tdLibManager.downloadThumbnailQuick(item.thumbFileId)?.let { thumbPath ->
            currentThumb = thumbPath
            mediaDao.updateThumbInfo(existingByHash.id, item.thumbFileId, thumbPath)
          }
        }
        if (albumCoverNeeded && albumId != null) {
          val coverCandidate = existingByHash.localUri ?: currentThumb
          if (!coverCandidate.isNullOrBlank()) {
            albumDao.updateCoverUri(albumId, coverCandidate)
            albumCoverNeeded = false
          }
        }
        existingHashes.add(item.contentHash)
        continue
      }

      if (existingHashes.contains(item.contentHash)) continue

      // Deduplicate against MediaStore items matched by (mediaType, fileName, sizeBytes)
      val nameSizeKey = "${item.mediaType.name}|${item.fileName.lowercase()}|${item.sizeBytes}"
      val existingLocalMatch = if (item.sizeBytes > 0L) byNameSize[nameSizeKey] else null
      if (existingLocalMatch != null) {
        if (existingLocalMatch.uploadStatus != UploadStatus.UPLOADED) {
          mediaDao.markUploaded(
            id = existingLocalMatch.id,
            chatId = item.chatId,
            messageId = item.messageId,
            fileId = item.fileId,
            remoteFileId = item.remoteFileId,
            topicId = item.topicId ?: existingLocalMatch.topicId,
            thumbFileId = item.thumbFileId,
            albumId = if (!isTrashTopic) existingLocalMatch.albumId ?: albumId else existingLocalMatch.albumId,
          )
          if (existingLocalMatch.contentHash.startsWith("ms_") || item.contentHash.length >= 32) {
            mediaDao.updateContentHash(existingLocalMatch.id, item.contentHash)
          }
        } else if (item.fileId != existingLocalMatch.telegramFileId ||
          item.remoteFileId != existingLocalMatch.telegramRemoteFileId ||
          item.thumbFileId != existingLocalMatch.telegramThumbFileId
        ) {
          mediaDao.updateTelegramFileRefs(
            id = existingLocalMatch.id,
            fileId = item.fileId,
            remoteFileId = item.remoteFileId,
            thumbFileId = item.thumbFileId,
          )
        }
        val needsAlbumOrTopic = !isTrashTopic &&
          ((albumId != null && existingLocalMatch.albumId == null) ||
            (item.topicId != null && existingLocalMatch.topicId == null))
        if (needsAlbumOrTopic) {
          mediaDao.assignToAlbum(
            ids = listOf(existingLocalMatch.id),
            albumId = existingLocalMatch.albumId ?: albumId,
            topicId = existingLocalMatch.topicId ?: item.topicId,
          )
        }
        if (albumCoverNeeded && albumId != null) {
          val coverCandidate = existingLocalMatch.localUri ?: existingLocalMatch.cachedThumbPath
          if (!coverCandidate.isNullOrBlank()) {
            albumDao.updateCoverUri(albumId, coverCandidate)
            albumCoverNeeded = false
          }
        }
        existingHashes.add(item.contentHash)
        continue
      }

      val isTrashed = item.isTrashed || isTrashTopic
      val downloadedThumbPath = if (item.thumbFileId != null && thumbFetchCount < maxQuickThumbsPerBatch) {
        thumbFetchCount++
        tdLibManager.downloadThumbnailQuick(item.thumbFileId)
      } else {
        null
      }
      mediaDao.insertIgnore(
        MediaEntity(
          contentHash = item.contentHash,
          localUri = null,
          fileName = item.fileName,
          mimeType = item.mimeType,
          mediaType = item.mediaType,
          sizeBytes = item.sizeBytes,
          durationSec = item.durationSec,
          takenAtEpochMs = item.takenAtEpochMs,
          albumId = albumId,
          topicId = item.topicId,
          uploadStatus = UploadStatus.UPLOADED,
          telegramChatId = item.chatId,
          telegramMessageId = item.messageId,
          telegramFileId = item.fileId,
          telegramRemoteFileId = item.remoteFileId,
          telegramThumbFileId = item.thumbFileId,
          cachedThumbPath = downloadedThumbPath,
          isTrashed = isTrashed,
          trashedAtEpochMs = if (isTrashed) System.currentTimeMillis() else null,
        )
      )
      if (albumCoverNeeded && albumId != null && !downloadedThumbPath.isNullOrBlank()) {
        albumDao.updateCoverUri(albumId, downloadedThumbPath)
        albumCoverNeeded = false
      }
      existingHashes.add(item.contentHash)
      added++
    }
    return added
  }

  fun downloadMediaOnDemand(media: MediaEntity): Flow<FileDownloadProgress> =
    tdLibManager.downloadMediaOnDemand(media) { refreshed ->
      mediaDao.updateTelegramFileRefs(
        id = media.id,
        fileId = refreshed.fileId,
        remoteFileId = refreshed.remoteFileId,
        thumbFileId = refreshed.thumbFileId,
      )
    }

  fun downloadMediaOnDemand(fileId: Int): Flow<FileDownloadProgress> =
    tdLibManager.downloadFileOnDemand(fileId)

  suspend fun clearStaleCachedVideoPath(mediaId: Long) {
    mediaDao.clearCachedVideoPath(mediaId)
  }

  suspend fun saveDownloadedVideoPath(mediaId: Long, localPath: String) {
    mediaDao.updateCachedVideoPath(mediaId, localPath)
    CacheCleanupManager.enforceCacheLimitLru(context)
  }

  /**
   * Explicitly downloads (or copies from internal viewer cache) a single original media item
   * into Pictures/Tele Photos or Movies/Tele Photos and marks localUri as present in Room.
   */
  fun saveMediaToDeviceGallery(media: MediaEntity): Flow<SaveToDeviceProgress> = flow {
    if (MediaStoreScanner.isLocalUriAccessible(context, media.localUri)) {
      emit(SaveToDeviceProgress.Completed(Uri.parse(media.localUri!!)))
      return@flow
    }

    val cachedPath = media.cachedLocalVideoPath?.takeIf { MediaStoreScanner.isUsableLocalFile(it) }
    if (cachedPath == null && !media.cachedLocalVideoPath.isNullOrBlank()) {
      mediaDao.clearCachedVideoPath(media.id)
    }

    val sourceFile = if (cachedPath != null) {
      emit(SaveToDeviceProgress.Downloading(0.85f, "Đang lưu tệp vào thư viện thiết bị..."))
      File(cachedPath)
    } else {
      var downloadedPath: String? = null
      downloadMediaOnDemand(media).collect { prog ->
        if (prog.isCompleted && MediaStoreScanner.isUsableLocalFile(prog.localPath)) {
          downloadedPath = prog.localPath
        } else {
          val pct = (prog.fraction * 100).toInt()
          emit(
            SaveToDeviceProgress.Downloading(
              fraction = prog.fraction,
              statusVi = prog.statusMessageVi ?: "Đang tải tệp gốc về máy... $pct%",
            )
          )
        }
      }
      val resolved = downloadedPath?.takeIf { MediaStoreScanner.isUsableLocalFile(it) }?.let { File(it) }
        ?: error("Không thể hoàn tất tải tệp từ Telegram")
      resolved
    }

    val savedUri = MediaStoreScanner.saveFileToPublicGallery(
      context = context,
      sourceFile = sourceFile,
      fileName = media.fileName,
      mimeType = media.mimeType,
      mediaType = media.mediaType,
    )
    mediaDao.markSavedToDevice(media.id, savedUri.toString())
    CacheCleanupManager.enforceCacheLimitLru(context)
    emit(SaveToDeviceProgress.Completed(savedUri))
  }

  /**
   * Prepares "Giải phóng dung lượng máy" for already-synced items ONLY (uploadStatus == UPLOADED).
   * Deletes only the local MediaStore file copy, never deletes anything on Telegram, and caches a
   * tiny thumbnail preview so items transition smoothly to Cloud-only (Trực tuyến).
   */
  suspend fun prepareFreeUpSpace(mediaIds: List<Long>? = null): FreeUpPreparation {
    val candidates = if (mediaIds == null) {
      mediaDao.getAllFreeableSyncedMedia()
    } else {
      mediaDao.getByIds(mediaIds)
    }
    val eligible = candidates.filter {
      !it.isTrashed && it.uploadStatus == UploadStatus.UPLOADED && !it.localUri.isNullOrBlank()
    }
    val skippedUnsynced = if (mediaIds != null) {
      candidates.count { !it.isTrashed && it.uploadStatus != UploadStatus.UPLOADED }
    } else {
      0
    }

    val thumbOverrides = mutableMapOf<Long, String>()
    for (item in eligible) {
      if (item.cachedThumbPath.isNullOrBlank() || !File(item.cachedThumbPath).exists()) {
        MediaStoreScanner.cacheSmallThumbnailForTrash(context, item)?.let { thumb ->
          thumbOverrides[item.id] = thumb
        }
      }
    }

    val prep = MediaStoreScanner.prepareSystemLocalDelete(context, eligible)
    return FreeUpPreparation(
      eligibleMediaIds = eligible.map { it.id },
      skippedUnsyncedCount = skippedUnsynced,
      prepResult = prep,
      thumbPathOverrides = thumbOverrides,
    )
  }

  suspend fun finalizeFreeUpSpace(
    eligibleMediaIds: List<Long>,
    deletedLocalUris: Set<String>,
    thumbOverrides: Map<Long, String>,
  ): Result<Int> = runCatching {
    if (eligibleMediaIds.isEmpty()) return@runCatching 0
    val items = mediaDao.getByIds(eligibleMediaIds)
    val now = System.currentTimeMillis()
    var freedCount = 0

    for (item in items) {
      if (item.uploadStatus != UploadStatus.UPLOADED) continue
      val uri = item.localUri ?: continue
      val canClearUri = uri in deletedLocalUris ||
        uri.contains("/picker") ||
        !MediaStoreScanner.isLocalUriAccessible(context, uri)
      if (canClearUri) {
        thumbOverrides[item.id]?.let { thumb ->
          mediaDao.updateCachedThumbPath(item.id, thumb, now)
        }
        mediaDao.updateLocalUri(item.id, null, now)
        freedCount++
      }
    }
    CacheCleanupManager.enforceCacheLimitLru(context)
    freedCount
  }

  suspend fun calculateCacheSizeBytes(): Long =
    CacheCleanupManager.calculateCacheSizeBytes(context)

  suspend fun enforceCacheLimit(): Long =
    CacheCleanupManager.enforceCacheLimitLru(context)

  suspend fun clearTemporaryCache(): Result<Long> = runCatching {
    val freed = CacheCleanupManager.clearAllTemporaryCache(context)
    mediaDao.clearTemporaryCachePaths()
    freed
  }

  /**
   * Starts a manual on-demand WorkManager upload batch ONLY when explicitly invoked by user tap.
   * When filterMediaType is non-null (from Ảnh or Video top bar), queues and uploads ONLY that type.
   */
  suspend fun startManualUploadBatch(
    selectedOnlyIds: List<Long>? = null,
    filterMediaType: MediaType? = null,
  ): Result<Int> = runCatching {
    val target = syncTargetDao.getTarget()
      ?: error("Vui lòng chọn và khoá đích lưu trữ Telegram trong Setting trước")
    if (!target.isLocked) error("Đích lưu trữ chưa được khoá an toàn")

    val settings = settingsPreferences.getCurrentSettings()
    if (!settings.isTelegramLoggedIn) {
      error("Vui lòng đăng nhập Telegram trong Setting trước khi đồng bộ")
    }

    val now = System.currentTimeMillis()
    val activeUnlockMs = maxOf(
      settings.floodWaitUntilEpochMs,
      tdLibManager.floodWaitState.value?.unlockAtEpochMs ?: 0L,
    )
    if (activeUnlockMs > now) {
      val rem = ((activeUnlockMs - now + 999L) / 1000L).coerceAtLeast(1L)
      error("Telegram đang giới hạn tốc độ (FLOOD_WAIT). Hàng đợi tạm khoá thêm $rem giây.")
    } else if (settings.floodWaitUntilEpochMs > 0L || tdLibManager.floodWaitState.value != null) {
      tdLibManager.clearFloodWait()
    }

    if (settings.wifiOnlyUpload && !isWifiConnected()) {
      error("Đang bật «Chỉ cho phép tải lên khi dùng Wi-Fi». Hãy kết nối Wi-Fi để bắt đầu.")
    }

    when {
      selectedOnlyIds != null && selectedOnlyIds.isNotEmpty() -> {
        mediaDao.markSelectedQueued(selectedOnlyIds)
      }
      filterMediaType != null -> {
        mediaDao.requeueAllPendingByType(filterMediaType.name)
      }
      else -> {
        mediaDao.requeueAllPending()
      }
    }

    val pendingCount = if (filterMediaType != null) {
      mediaDao.countPendingUploadsByType(filterMediaType.name)
    } else {
      mediaDao.countPendingUploads()
    }

    // For Settings "Tải lên ngay", fail fast if queue is empty.
    // For Ảnh / Video tab Sync button, still launch Worker so it pulls latest cloud items from that Topic too.
    if (filterMediaType == null && pendingCount <= 0) {
      error("Không có ảnh hoặc video mới nào cần tải lên")
    }

    settingsPreferences.setLastSyncStatusMessage("")

    val networkType = if (settings.wifiOnlyUpload) NetworkType.UNMETERED else NetworkType.CONNECTED
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(networkType)
      .build()

    val inputData = workDataOf(
      TelegramUploadWorker.KEY_FILTER_MEDIA_TYPE to filterMediaType?.name,
    )

    val request = OneTimeWorkRequestBuilder<TelegramUploadWorker>()
      .setConstraints(constraints)
      .setInputData(inputData)
      .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
      TelegramUploadWorker.UNIQUE_WORK_NAME,
      ExistingWorkPolicy.KEEP,
      request,
    )
    pendingCount
  }

  /**
   * Reschedules TelegramUploadWorker after a long FLOOD_WAIT cooldown expires so no wake lock
   * is held while waiting.
   */
  suspend fun scheduleUploadAfterFloodWait(
    delayMs: Long,
    filterMediaType: MediaType?,
  ) {
    val settings = settingsPreferences.getCurrentSettings()
    val networkType = if (settings.wifiOnlyUpload) NetworkType.UNMETERED else NetworkType.CONNECTED
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(networkType)
      .build()

    val inputData = workDataOf(
      TelegramUploadWorker.KEY_FILTER_MEDIA_TYPE to filterMediaType?.name,
    )

    val request = OneTimeWorkRequestBuilder<TelegramUploadWorker>()
      .setInitialDelay(delayMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
      .setConstraints(constraints)
      .setInputData(inputData)
      .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
      TelegramUploadWorker.UNIQUE_WORK_NAME,
      ExistingWorkPolicy.REPLACE,
      request,
    )
  }

  /**
   * Immediately cancels the running manual WorkManager batch, resets interrupted items,
   * trims TDLib cache, and keeps the foreground session alive (or closes if backgrounded).
   */
  suspend fun stopManualSync() {
    WorkManager.getInstance(context).cancelUniqueWork(TelegramUploadWorker.UNIQUE_WORK_NAME)
    mediaDao.resetInterruptedUploadsToQueued()
    tdLibManager.optimizeStorageLightweight()
    if (!tdLibManager.isAppInForeground()) {
      tdLibManager.closeConnectionIdle(preserveWaitApiState = false)
    }
    settingsPreferences.setLastSyncStatusMessage(
      if (tdLibManager.isAppInForeground()) {
        "Đã dừng đồng bộ — Phiên Telegram sẵn sàng"
      } else {
        "Đã dừng đồng bộ — Đã chuyển sang chế độ nền"
      }
    )
  }

  /**
   * Records dynamic FLOOD_WAIT unlock timestamp without closing or reconnecting TDLib,
   * and locks/pauses the upload queue until unlockAtEpochMs expires.
   */
  suspend fun handleFloodWait(exception: FloodWaitException) {
    val now = System.currentTimeMillis()
    val unlockAtMs = exception.unlockAtEpochMs.takeIf { it > now }
      ?: TelegramPolicyGuard.computeFloodWaitUnlockAtEpochMs(exception.retryAfterSeconds, now)
    val totalWaitSec = ((unlockAtMs - now + 999L) / 1000L).toInt()
      .coerceAtLeast(exception.retryAfterSeconds)

    tdLibManager.recordFloodWait(
      retryAfterSeconds = exception.retryAfterSeconds,
      unlockAtEpochMs = unlockAtMs,
      reason = exception.message,
    )
    mediaDao.pauseActiveQueueForFloodWait("Tạm khoá ${totalWaitSec}s do giới hạn Telegram (FLOOD_WAIT_${exception.retryAfterSeconds})")
    settingsPreferences.setLastSyncStatusMessage(
      "Telegram giới hạn tốc độ (${exception.retryAfterSeconds}s) — Tạm khoá hàng đợi ${totalWaitSec}s (giữ phiên nhàn rỗi)"
    )
  }

  fun buildDocumentCaption(media: MediaEntity): String {
    return TelegramCaptionMetadata.buildCaption(
      TelegramCaptionPayload(
        contentHash = media.contentHash,
        takenAtEpochMs = media.takenAtEpochMs,
        durationSec = media.durationSec,
        topicId = media.topicId,
        isTrashed = media.isTrashed,
        fileName = media.fileName,
        mediaKind = if (media.mediaType == MediaType.VIDEO) "VIDEO" else "PHOTO",
        sizeBytes = media.sizeBytes,
      )
    )
  }

  private fun isWifiConnected(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
  }
}
