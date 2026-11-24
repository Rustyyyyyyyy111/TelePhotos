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
import com.laughing.telephotos.core.MediaHashUtil
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
import com.laughing.telephotos.data.telegram.TdLibManager
import com.laughing.telephotos.data.telegram.TelegramAuthState
import com.laughing.telephotos.data.telegram.TelegramCandidateTarget
import com.laughing.telephotos.data.telegram.TelegramConnectionStatus
import com.laughing.telephotos.data.telegram.TelegramPolicyGuard
import com.laughing.telephotos.worker.TelegramUploadWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

data class ImportSummary(
  val addedCount: Int,
  val duplicateSkippedCount: Int,
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
) {

  val authState: StateFlow<TelegramAuthState> = tdLibManager.authState
  val connectionStatus: StateFlow<TelegramConnectionStatus> = tdLibManager.connectionStatus
  val photosTimelineFlow: Flow<List<MediaEntity>> = mediaDao.observePhotosTimeline()
  val videosTimelineFlow: Flow<List<MediaEntity>> = mediaDao.observeVideosTimeline()
  val trashedMediaFlow: Flow<List<MediaEntity>> = mediaDao.observeTrashedMedia()
  val albumsWithCountFlow: Flow<List<AlbumWithCount>> = albumDao.observeAlbumsWithCount()
  val uploadQueueFlow: Flow<List<MediaEntity>> = mediaDao.observeUploadQueue()
  val storageStatsFlow: Flow<StorageStats> = mediaDao.observeStorageStats()
  val lockedTargetFlow: Flow<SyncTargetEntity?> = syncTargetDao.observeTarget()

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
   * Loads allowlisted private targets via a short-lived TDLib session, then disconnects immediately.
   */
  suspend fun loadAllowedTargetsShortLived(): List<TelegramCandidateTarget> =
    tdLibManager.withShortLivedConnection {
      tdLibManager.loadAllowedTargets()
    }

  /**
   * Locks an allowlisted destination via a short-lived TDLib session, ensures "Đã xoá gần đây" topic,
   * then disconnects TDLib immediately.
   */
  suspend fun lockSyncTarget(candidate: TelegramCandidateTarget): Result<SyncTargetEntity> =
    runCatching {
      val trashThreadId = tdLibManager.withShortLivedConnection {
        var resolvedTrashId: Long? = null
        if (candidate.isForumEnabled) {
          resolvedTrashId = tdLibManager.findOrCreateTrashTopic(candidate.chatId)
          albumDao.upsert(
            AlbumEntity(
              title = TelegramPolicyGuard.TRASH_TOPIC_TITLE,
              chatId = candidate.chatId,
              messageThreadId = resolvedTrashId,
              isTrashTopic = true,
              iconColor = 0xFB6F5F,
            )
          )
          val remoteTopics = tdLibManager.getForumTopics(candidate.chatId)
          for (topic in remoteTopics) {
            val isTrash = topic.title.equals(TelegramPolicyGuard.TRASH_TOPIC_TITLE, ignoreCase = true)
            if (albumDao.getByChatAndThread(candidate.chatId, topic.messageThreadId) == null) {
              albumDao.upsert(
                AlbumEntity(
                  title = topic.title,
                  chatId = candidate.chatId,
                  messageThreadId = topic.messageThreadId,
                  isTrashTopic = isTrash,
                  iconColor = topic.iconColor,
                )
              )
            }
          }
        }
        resolvedTrashId
      }

      val target = SyncTargetEntity(
        id = 1,
        chatId = candidate.chatId,
        chatTitle = candidate.title,
        targetType = candidate.targetType,
        isForumEnabled = candidate.isForumEnabled,
        defaultMessageThreadId = null,
        trashThreadId = trashThreadId,
        isLocked = true,
        confirmedAtEpochMs = System.currentTimeMillis(),
      )
      syncTargetDao.saveTarget(target)
      target
    }

  /**
   * Creates an Album (Forum Topic via short-lived TDLib connection when Forum-enabled),
   * then disconnects TDLib immediately.
   */
  suspend fun createAlbum(title: String, iconColor: Int = 0x6FB9F0): Result<AlbumEntity> =
    runCatching {
      val cleanTitle = title.trim()
      require(cleanTitle.isNotBlank()) { "Tên album không được để trống" }
      require(!cleanTitle.equals(TelegramPolicyGuard.TRASH_TOPIC_TITLE, ignoreCase = true)) {
        "Tên này được dành riêng cho thùng rác"
      }

      val target = syncTargetDao.getTarget()
        ?: error("Vui lòng chọn và khoá đích lưu trữ Telegram trước khi tạo Album")
      val settings = settingsPreferences.getCurrentSettings()

      val threadId = if (target.isForumEnabled && settings.isTelegramLoggedIn) {
        tdLibManager.withShortLivedConnection {
          tdLibManager.createForumTopicRateLimited(
            chatId = target.chatId,
            title = cleanTitle,
            iconColor = iconColor,
          ).messageThreadId
        }
      } else {
        System.currentTimeMillis()
      }

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
   * Marks items as trashed in Room and links to "Đã xoá gần đây" topic.
   */
  suspend fun moveMediaToRecentlyDeleted(mediaIds: List<Long>): Result<Int> = runCatching {
    val target = syncTargetDao.getTarget()
    val settings = settingsPreferences.getCurrentSettings()
    var trashAlbum = target?.let { albumDao.getTrashAlbum(it.chatId) }
    var trashTopicId = target?.trashThreadId ?: trashAlbum?.messageThreadId

    if (target != null && target.isForumEnabled && trashTopicId == null && settings.isTelegramLoggedIn) {
      trashTopicId = tdLibManager.withShortLivedConnection {
        tdLibManager.findOrCreateTrashTopic(target.chatId)
      }
      syncTargetDao.updateTrashThreadId(trashTopicId)
      val insertedId = albumDao.upsert(
        AlbumEntity(
          title = TelegramPolicyGuard.TRASH_TOPIC_TITLE,
          chatId = target.chatId,
          messageThreadId = trashTopicId,
          isTrashTopic = true,
          iconColor = 0xFB6F5F,
        )
      )
      trashAlbum = albumDao.getAlbum(insertedId)
    }

    val now = System.currentTimeMillis()
    var count = 0
    for (id in mediaIds) {
      mediaDao.moveToTrash(
        id = id,
        trashedAt = now,
        trashAlbumId = trashAlbum?.id,
        trashTopicId = trashTopicId,
        trashMessageId = null,
      )
      count++
    }
    count
  }

  suspend fun restoreFromRecentlyDeleted(mediaIds: List<Long>): Int {
    val now = System.currentTimeMillis()
    for (id in mediaIds) {
      mediaDao.restoreFromTrash(id = id, restoredAt = now)
    }
    return mediaIds.size
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
   * Read-only history reconstruction wrapped in a short-lived TDLib session.
   * Immediately disconnects TDLib when finished.
   */
  suspend fun syncHistoryReadOnly(): Result<Int> = runCatching {
    val target = syncTargetDao.getTarget()
      ?: error("Chưa khoá đích lưu trữ Telegram")
    val settings = settingsPreferences.getCurrentSettings()
    if (!settings.isTelegramLoggedIn) {
      error("Vui lòng đăng nhập Telegram trong Setting trước khi quét chỉ đọc")
    }

    val existingHashes = mediaDao.getAllUploadedHashes().toHashSet()
    val reconstructedCount = tdLibManager.withShortLivedConnection {
      var totalAdded = 0
      if (target.isForumEnabled) {
        val topics = tdLibManager.getForumTopics(target.chatId)
        for (topic in topics) {
          val isTrash = topic.title.equals(TelegramPolicyGuard.TRASH_TOPIC_TITLE, ignoreCase = true)
          val existingAlbum = albumDao.getByChatAndThread(target.chatId, topic.messageThreadId)
          val albumId = existingAlbum?.id ?: albumDao.upsert(
            AlbumEntity(
              title = topic.title,
              chatId = target.chatId,
              messageThreadId = topic.messageThreadId,
              isTrashTopic = isTrash,
              iconColor = topic.iconColor,
            )
          )

          val scanned = tdLibManager.scanHistoryReadOnly(
            chatId = target.chatId,
            messageThreadId = topic.messageThreadId,
            limit = 30,
          )
          totalAdded += ingestScannedItems(scanned, albumId, isTrash, existingHashes)
          delay(350L)
        }
      } else {
        val scanned = tdLibManager.scanHistoryReadOnly(
          chatId = target.chatId,
          messageThreadId = target.defaultMessageThreadId,
          limit = 50,
        )
        totalAdded += ingestScannedItems(scanned, null, false, existingHashes)
      }
      totalAdded
    }

    settingsPreferences.updateLastHistorySync(System.currentTimeMillis())
    settingsPreferences.setLastSyncStatusMessage(
      "Đã quét chỉ đọc xong ($reconstructedCount mục mới) — Đã ngắt kết nối Telegram"
    )
    reconstructedCount
  }

  private suspend fun ingestScannedItems(
    scanned: List<com.laughing.telephotos.data.telegram.ScannedTelegramMedia>,
    albumId: Long?,
    isTrashTopic: Boolean,
    existingHashes: MutableSet<String>,
  ): Int {
    var added = 0
    for (item in scanned) {
      if (existingHashes.contains(item.contentHash)) continue
      val existing = mediaDao.findByHash(item.contentHash)
      if (existing != null) {
        if (existing.uploadStatus != UploadStatus.UPLOADED) {
          mediaDao.markUploaded(
            id = existing.id,
            chatId = item.chatId,
            messageId = item.messageId,
            fileId = item.fileId,
            remoteFileId = item.remoteFileId,
            topicId = item.topicId,
          )
        }
        existingHashes.add(item.contentHash)
        continue
      }

      val isTrashed = item.isTrashed || isTrashTopic
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
          isTrashed = isTrashed,
          trashedAtEpochMs = if (isTrashed) System.currentTimeMillis() else null,
        )
      )
      existingHashes.add(item.contentHash)
      added++
    }
    return added
  }

  fun downloadMediaOnDemand(fileId: Int): Flow<FileDownloadProgress> =
    tdLibManager.downloadFileOnDemand(fileId)

  suspend fun saveDownloadedVideoPath(mediaId: Long, localPath: String) {
    mediaDao.updateCachedVideoPath(mediaId, localPath)
  }

  /**
   * Starts a manual on-demand WorkManager upload batch ONLY when explicitly invoked by user tap.
   * Connects TDLib for the batch, uploads only newly added / unsynced items, then closes TDLib immediately.
   */
  suspend fun startManualUploadBatch(selectedOnlyIds: List<Long>? = null): Result<Int> = runCatching {
    val target = syncTargetDao.getTarget()
      ?: error("Vui lòng chọn và khoá đích lưu trữ Telegram trong Setting trước")
    if (!target.isLocked) error("Đích lưu trữ chưa được khoá an toàn")

    val settings = settingsPreferences.getCurrentSettings()
    if (!settings.isTelegramLoggedIn) {
      error("Vui lòng đăng nhập Telegram trong Setting trước khi đồng bộ")
    }

    val now = System.currentTimeMillis()
    if (settings.floodWaitUntilEpochMs > now) {
      val rem = ((settings.floodWaitUntilEpochMs - now) / 1000L).coerceAtLeast(1L)
      error("Telegram đang giới hạn tốc độ. Vui lòng đợi thêm $rem giây.")
    } else if (settings.floodWaitUntilEpochMs > 0L) {
      settingsPreferences.clearFloodWait()
    }

    if (settings.wifiOnlyUpload && !isWifiConnected()) {
      error("Đang bật «Chỉ cho phép tải lên khi dùng Wi-Fi». Hãy kết nối Wi-Fi để bắt đầu.")
    }

    if (selectedOnlyIds != null && selectedOnlyIds.isNotEmpty()) {
      mediaDao.markSelectedQueued(selectedOnlyIds)
    } else {
      mediaDao.requeueAllPending()
    }

    val pendingCount = mediaDao.countPendingUploads()
    if (pendingCount <= 0) {
      error("Không có ảnh hoặc video mới nào cần tải lên")
    }

    settingsPreferences.setLastSyncStatusMessage("")

    val networkType = if (settings.wifiOnlyUpload) NetworkType.UNMETERED else NetworkType.CONNECTED
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(networkType)
      .build()

    val request = OneTimeWorkRequestBuilder<TelegramUploadWorker>()
      .setConstraints(constraints)
      .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
      TelegramUploadWorker.UNIQUE_WORK_NAME,
      ExistingWorkPolicy.KEEP,
      request,
    )
    pendingCount
  }

  /**
   * Immediately cancels the running manual WorkManager batch, resets interrupted items,
   * and closes TDLib immediately so no background connection remains open.
   */
  suspend fun stopManualSync() {
    WorkManager.getInstance(context).cancelUniqueWork(TelegramUploadWorker.UNIQUE_WORK_NAME)
    mediaDao.resetInterruptedUploadsToQueued()
    tdLibManager.releaseShortLivedSession(forceClose = true)
    settingsPreferences.setLastSyncStatusMessage(
      "Đã dừng đồng bộ — Đã ngắt kết nối Telegram"
    )
  }

  suspend fun handleFloodWait(exception: FloodWaitException) {
    val bufferSec = TelegramPolicyGuard.FLOOD_WAIT_SAFETY_BUFFER_SEC
    val totalWaitSec = exception.retryAfterSeconds + bufferSec
    val untilMs = System.currentTimeMillis() + (totalWaitSec * 1000L)
    settingsPreferences.setFloodWaitUntil(untilMs, exception.message)
    mediaDao.pauseActiveQueueForFloodWait("Tạm dừng ${totalWaitSec}s do giới hạn Telegram")
    settingsPreferences.setLastSyncStatusMessage(
      "Tạm dừng ${totalWaitSec}s do giới hạn Telegram — Đã ngắt kết nối Telegram"
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
