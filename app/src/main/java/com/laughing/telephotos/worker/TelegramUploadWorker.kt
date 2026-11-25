package com.laughing.telephotos.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.laughing.telephotos.TelePhotosApplication
import com.laughing.telephotos.core.MediaHashUtil
import com.laughing.telephotos.core.NetworkOperationType
import com.laughing.telephotos.core.NetworkRetryPolicy
import com.laughing.telephotos.core.NetworkRetryTracker
import com.laughing.telephotos.core.RetryDecision
import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.data.local.UploadStatus
import com.laughing.telephotos.data.telegram.FloodWaitException
import com.laughing.telephotos.data.telegram.PendingUploadInterruptedException
import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * On-demand single-thread sequential upload worker triggered ONLY by explicit user tap
 * (or rescheduled by WorkManager after a long FLOOD_WAIT cooldown).
 * - Shares the app-wide TdLibManager session; keeps TDLib alive while foregrounded
 *   and closes cleanly on completion only when backgrounded.
 * - Keeps transient network retry (8s x 2 + 25s stall) strictly separate from dynamic FLOOD_WAIT.
 * - Respects exact FLOOD_WAIT unlock timestamp: short waits (<=60s) suspend/delay in-worker
 *   keeping TDLib idle and resuming the pending item first; long waits (>60s) reschedule via
 *   WorkManager initialDelay so no wake lock is held.
 */
class TelegramUploadWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val app = applicationContext as? TelePhotosApplication ?: return Result.failure()
    val db = app.database
    val mediaDao = db.mediaDao()
    val syncTargetDao = db.syncTargetDao()
    val prefs = app.settingsPreferences
    val tdLib = app.tdLibManager
    val repo = app.vaultRepository

    val initialSettings = prefs.getCurrentSettings()
    val initialTarget = syncTargetDao.getTarget() ?: return Result.success()
    if (!initialTarget.isLocked || !initialSettings.isTelegramLoggedIn) return Result.success()

    val filterMediaType = inputData.getString(KEY_FILTER_MEDIA_TYPE)
      ?.let { raw -> runCatching { MediaType.valueOf(raw) }.getOrNull() }
    val isSelectedBatch = inputData.getBoolean(KEY_IS_SELECTED_BATCH, false)
    val selectedBatchIds = if (isSelectedBatch) {
      prefs.getSelectedUploadBatchIds()
    } else {
      emptyList()
    }
    if (isSelectedBatch && selectedBatchIds.isEmpty()) {
      return Result.success()
    }

    tdLib.beginWorkLease()

    var hitFloodWaitRescheduled = false
    var stoppedByNetworkFailure = false
    var uploadedAnyOrClean = false

    try {
      // 1. Check persisted or in-memory FLOOD_WAIT cooldown before sending any Telegram request
      val initialUnlockAt = maxOf(
        initialSettings.floodWaitUntilEpochMs,
        tdLib.floodWaitState.value?.unlockAtEpochMs ?: 0L,
      )
      val initialNow = System.currentTimeMillis()
      if (initialUnlockAt > initialNow) {
        val remainingMs = initialUnlockAt - initialNow
        val retryAfterSec = maxOf(
          initialSettings.floodWaitRetryAfterSec,
          tdLib.floodWaitState.value?.retryAfterSeconds ?: 0,
        )
        val shouldSuspendInWorker = retryAfterSec in 1..SHORT_FLOOD_WAIT_MAX_SEC ||
          remainingMs <= SHORT_FLOOD_WAIT_MAX_WAIT_MS
        if (shouldSuspendInWorker) {
          val unlocked = suspendForFloodWait(
            unlockAtEpochMs = initialUnlockAt,
            currentFileName = "Tạm khoá hàng đợi",
            completedCount = 0,
            totalCount = 1,
          )
          if (!unlocked || isStopped) return Result.success()
          tdLib.clearFloodWait()
          requeueFloodWaitPaused(repo, mediaDao, filterMediaType, isSelectedBatch, selectedBatchIds)
        } else {
          hitFloodWaitRescheduled = true
          repo.scheduleUploadAfterFloodWait(remainingMs, filterMediaType, isSelectedBatch)
          return Result.success()
        }
      } else if (initialSettings.floodWaitUntilEpochMs > 0L || tdLib.floodWaitState.value != null) {
        tdLib.clearFloodWait()
        requeueFloodWaitPaused(repo, mediaDao, filterMediaType, isSelectedBatch, selectedBatchIds)
      } else {
        requeueFloodWaitPaused(repo, mediaDao, filterMediaType, isSelectedBatch, selectedBatchIds)
      }

      // 2. Ensure single-flight TDLib connection (retry 8s x 2 ONLY on transient network errors)
      val retryTracker = NetworkRetryTracker()
      var sessionConnected = false
      while (!isStopped) {
        if (!NetworkRetryPolicy.isNetworkAvailable(applicationContext)) {
          when (val decision = retryTracker.onFailure()) {
            is RetryDecision.WaitAndRetry -> {
              setProgress(
                workDataOf(
                  KEY_CURRENT_FILE to "Mất kết nối mạng, thử lại sau 8 giây (${decision.attemptNumber}/2)...",
                  KEY_COMPLETED_COUNT to 0,
                  KEY_TOTAL_COUNT to 1,
                )
              )
              delay(decision.delayMs)
              continue
            }
            RetryDecision.StopAndNotify -> {
              stoppedByNetworkFailure = true
              repo.reportNetworkInterruption(NetworkOperationType.UPLOAD)
              prefs.setLastSyncStatusMessage("Tải lên bị gián đoạn — Không thể kết nối Telegram")
              return Result.failure()
            }
          }
        }

        val connectResult = tdLib.ensureConnected()
        if (connectResult.isSuccess) {
          retryTracker.reset()
          sessionConnected = true
          break
        }

        val err = connectResult.exceptionOrNull()
        if (err is FloodWaitException) {
          repo.handleFloodWait(err)
          val remainingMs = (err.unlockAtEpochMs - System.currentTimeMillis()).coerceAtLeast(1_000L)
          if (err.retryAfterSeconds <= SHORT_FLOOD_WAIT_MAX_SEC) {
            val unlocked = suspendForFloodWait(
              unlockAtEpochMs = err.unlockAtEpochMs,
              currentFileName = "Tạm khoá hàng đợi",
              completedCount = 0,
              totalCount = 1,
            )
            if (!unlocked || isStopped) return Result.success()
            tdLib.clearFloodWait()
            requeueFloodWaitPaused(repo, mediaDao, filterMediaType, isSelectedBatch, selectedBatchIds)
            continue
          } else {
            hitFloodWaitRescheduled = true
            repo.scheduleUploadAfterFloodWait(remainingMs, filterMediaType, isSelectedBatch)
            return Result.success()
          }
        }

        if (!NetworkRetryPolicy.isTransientNetworkError(err, applicationContext)) {
          prefs.setLastSyncStatusMessage(err?.message ?: "Không thể kết nối Telegram")
          return Result.failure()
        }

        when (val decision = retryTracker.onFailure()) {
          is RetryDecision.WaitAndRetry -> {
            setProgress(
              workDataOf(
                KEY_CURRENT_FILE to "Kết nối Telegram gián đoạn, thử lại sau 8 giây (${decision.attemptNumber}/2)...",
                KEY_COMPLETED_COUNT to 0,
                KEY_TOTAL_COUNT to 1,
              )
            )
            delay(decision.delayMs)
          }
          RetryDecision.StopAndNotify -> {
            stoppedByNetworkFailure = true
            repo.reportNetworkInterruption(NetworkOperationType.UPLOAD)
            prefs.setLastSyncStatusMessage("Tải lên bị gián đoạn — Không thể kết nối Telegram")
            return Result.failure()
          }
        }
      }

      if (!sessionConnected || isStopped) {
        return Result.success()
      }

      if (!isSelectedBatch) {
        setProgress(
          workDataOf(
            KEY_CURRENT_FILE to "Đang kiểm tra chủ đề Telegram...",
            KEY_COMPLETED_COUNT to 0,
            KEY_TOTAL_COUNT to 1,
          )
        )
        runCatching {
          repo.syncActiveTabHistoryWhileConnected(
            target = syncTargetDao.getTarget() ?: initialTarget,
            filterMediaType = filterMediaType,
          )
        }
      }

      val initialTotal = (when {
        isSelectedBatch -> repo.countPendingUploadsScoped(selectedBatchIds, filterMediaType)
        filterMediaType != null -> mediaDao.countPendingUploadsByType(filterMediaType.name)
        else -> mediaDao.countPendingUploads()
      }).coerceAtLeast(1)
      var completed = 0

      // 3. Sequential upload loop (1 file at a time, 2.0-3.5s pacing)
      while (!isStopped) {
        // Re-check queue flood lock before picking next item
        val activeUnlock = tdLib.floodWaitState.value?.unlockAtEpochMs
          ?: prefs.getCurrentSettings().floodWaitUntilEpochMs
        if (activeUnlock > System.currentTimeMillis()) {
          val remainingMs = activeUnlock - System.currentTimeMillis()
          val retrySec = tdLib.floodWaitState.value?.retryAfterSeconds
            ?: prefs.getCurrentSettings().floodWaitRetryAfterSec
          if (retrySec in 1..SHORT_FLOOD_WAIT_MAX_SEC || remainingMs <= SHORT_FLOOD_WAIT_MAX_WAIT_MS) {
            val unlocked = suspendForFloodWait(
              unlockAtEpochMs = activeUnlock,
              currentFileName = "Tạm khoá hàng đợi",
              completedCount = completed,
              totalCount = initialTotal,
            )
            if (!unlocked || isStopped) return Result.success()
            tdLib.clearFloodWait()
            requeueFloodWaitPaused(repo, mediaDao, filterMediaType, isSelectedBatch, selectedBatchIds)
          } else {
            hitFloodWaitRescheduled = true
            repo.scheduleUploadAfterFloodWait(remainingMs, filterMediaType, isSelectedBatch)
            return Result.success()
          }
        }

        val nextItem = when {
          isSelectedBatch -> repo.getNextPendingUploadScoped(selectedBatchIds, filterMediaType)
          filterMediaType != null -> mediaDao.getNextPendingUploadByType(filterMediaType.name)
          else -> mediaDao.getNextPendingUpload()
        } ?: break
        val currentTarget = syncTargetDao.getTarget() ?: initialTarget

        // 2-layer deduplication guard: skip if hash was already uploaded
        val alreadyUploaded = mediaDao.findUploadedByHash(nextItem.contentHash)
        if (alreadyUploaded != null && alreadyUploaded.id != nextItem.id) {
          val dest = runCatching { repo.resolveUploadDestination(nextItem, currentTarget) }.getOrNull()
          val resolvedAlbumId = nextItem.albumId ?: alreadyUploaded.albumId ?: dest?.albumId
          val resolvedTopicId = alreadyUploaded.topicId ?: nextItem.topicId ?: dest?.topicId
          if (alreadyUploaded.localUri == null && nextItem.localUri != null) {
            mediaDao.updateLocalUri(alreadyUploaded.id, nextItem.localUri)
            mediaDao.deleteByIds(listOf(nextItem.id))
          } else {
            mediaDao.markUploaded(
              id = nextItem.id,
              chatId = alreadyUploaded.telegramChatId ?: currentTarget.chatId,
              messageId = alreadyUploaded.telegramMessageId ?: 0L,
              fileId = alreadyUploaded.telegramFileId,
              remoteFileId = alreadyUploaded.telegramRemoteFileId,
              topicId = resolvedTopicId,
              thumbFileId = alreadyUploaded.telegramThumbFileId,
              albumId = resolvedAlbumId,
            )
          }
          retryTracker.reset()
          completed++
          continue
        }

        val uriString = nextItem.localUri
        if (uriString.isNullOrBlank()) {
          mediaDao.updateUploadStatus(nextItem.id, UploadStatus.FAILED, "Không tìm thấy tệp nguồn")
          completed++
          continue
        }

        setProgress(
          workDataOf(
            KEY_CURRENT_FILE to nextItem.fileName,
            KEY_COMPLETED_COUNT to completed,
            KEY_TOTAL_COUNT to initialTotal,
          )
        )

        mediaDao.updateUploadStatus(nextItem.id, UploadStatus.UPLOADING, null)

        var stagingFile: java.io.File? = null
        var uploadThumb: com.laughing.telephotos.core.GeneratedUploadThumbnail? = null
        var pendingMessageId: Long? = null
        var itemHandled = false

        try {
          stagingFile = MediaHashUtil.copyUriToStagingFile(
            context = applicationContext,
            uri = Uri.parse(uriString),
            fileName = nextItem.fileName,
          )
          uploadThumb = MediaHashUtil.generateUploadThumbnail(
            context = applicationContext,
            localFile = stagingFile,
            mediaType = nextItem.mediaType,
          )

          var effectiveItem = nextItem
          if (nextItem.contentHash.startsWith("ms_")) {
            val trueHash = runCatching { MediaHashUtil.computeFileSha256(stagingFile) }.getOrNull()
            if (!trueHash.isNullOrBlank()) {
              val uploadedDup = mediaDao.findUploadedByHash(trueHash)
              if (uploadedDup != null && uploadedDup.id != nextItem.id) {
                val dest = runCatching { repo.resolveUploadDestination(nextItem, currentTarget) }.getOrNull()
                val resolvedAlbumId = nextItem.albumId ?: uploadedDup.albumId ?: dest?.albumId
                val resolvedTopicId = uploadedDup.topicId ?: nextItem.topicId ?: dest?.topicId
                if (uploadedDup.localUri == null && nextItem.localUri != null) {
                  mediaDao.updateLocalUri(uploadedDup.id, nextItem.localUri)
                  mediaDao.deleteByIds(listOf(nextItem.id))
                } else {
                  mediaDao.markUploaded(
                    id = nextItem.id,
                    chatId = uploadedDup.telegramChatId ?: currentTarget.chatId,
                    messageId = uploadedDup.telegramMessageId ?: 0L,
                    fileId = uploadedDup.telegramFileId,
                    remoteFileId = uploadedDup.telegramRemoteFileId,
                    topicId = resolvedTopicId,
                    thumbFileId = uploadedDup.telegramThumbFileId,
                    albumId = resolvedAlbumId,
                  )
                }
                completed++
                itemHandled = true
                setProgress(
                  workDataOf(
                    KEY_CURRENT_FILE to nextItem.fileName,
                    KEY_COMPLETED_COUNT to completed,
                    KEY_TOTAL_COUNT to initialTotal,
                  )
                )
                continue
              } else if (mediaDao.findByHash(trueHash) == null) {
                mediaDao.updateContentHash(nextItem.id, trueHash)
                effectiveItem = nextItem.copy(contentHash = trueHash)
              }
            }
          }

          while (!isStopped && !itemHandled) {
            try {
              if (!NetworkRetryPolicy.isNetworkAvailable(applicationContext)) {
                throw PendingUploadInterruptedException(
                  pendingMessageId ?: 0L,
                  "Mất kết nối mạng khi đang tải lên Telegram",
                )
              }
              if (!tdLib.isSessionReady()) {
                tdLib.ensureConnected().getOrThrow()
              }

              val refreshedTarget = syncTargetDao.getTarget() ?: currentTarget
              val dest = repo.resolveUploadDestination(effectiveItem, refreshedTarget)
              val resolvedTopicId = dest.topicId
              val resolvedAlbumId = dest.albumId ?: effectiveItem.albumId
              val caption = repo.buildDocumentCaption(
                effectiveItem.copy(topicId = resolvedTopicId, albumId = resolvedAlbumId)
              )

              val sent = tdLib.sendDocumentOriginal(
                chatId = refreshedTarget.chatId,
                messageThreadId = resolvedTopicId,
                localFilePath = stagingFile.absolutePath,
                caption = caption,
                thumbnailPath = uploadThumb?.file?.absolutePath,
                thumbnailWidth = uploadThumb?.width ?: 0,
                thumbnailHeight = uploadThumb?.height ?: 0,
                existingPendingMessageId = pendingMessageId,
                onProgressMade = { retryTracker.reset() },
              )

              val finalTopicId = sent.messageThreadId ?: resolvedTopicId
              mediaDao.markUploaded(
                id = effectiveItem.id,
                chatId = sent.chatId,
                messageId = sent.messageId,
                fileId = sent.fileId,
                remoteFileId = sent.remoteFileId,
                topicId = finalTopicId,
                thumbFileId = sent.thumbFileId,
                albumId = resolvedAlbumId,
              )
              repo.updateAlbumCoverIfMissing(resolvedAlbumId, effectiveItem.localUri)
              retryTracker.reset()
              completed++
              itemHandled = true
              setProgress(
                workDataOf(
                  KEY_CURRENT_FILE to nextItem.fileName,
                  KEY_COMPLETED_COUNT to completed,
                  KEY_TOTAL_COUNT to initialTotal,
                )
              )
            } catch (flood: FloodWaitException) {
              // Keep TDLib session idle/alive, lock upload queue until unlockAtEpochMs
              repo.handleFloodWait(flood)
              val remainingMs = (flood.unlockAtEpochMs - System.currentTimeMillis()).coerceAtLeast(1_000L)
              if (flood.retryAfterSeconds <= SHORT_FLOOD_WAIT_MAX_SEC) {
                val unlocked = suspendForFloodWait(
                  unlockAtEpochMs = flood.unlockAtEpochMs,
                  currentFileName = effectiveItem.fileName,
                  completedCount = completed,
                  totalCount = initialTotal,
                )
                if (!unlocked || isStopped) {
                  return Result.success()
                }
                tdLib.clearFloodWait()
                requeueFloodWaitPaused(repo, mediaDao, filterMediaType, isSelectedBatch, selectedBatchIds)
                mediaDao.updateUploadStatus(effectiveItem.id, UploadStatus.UPLOADING, null)
                // Loop immediately resumes uploading this pending item first!
              } else {
                hitFloodWaitRescheduled = true
                repo.scheduleUploadAfterFloodWait(remainingMs, filterMediaType, isSelectedBatch)
                return Result.success()
              }
            } catch (t: Throwable) {
              if (isStopped) {
                mediaDao.updateUploadStatus(nextItem.id, UploadStatus.QUEUED, null)
                return Result.success()
              }
              if (t is PendingUploadInterruptedException && t.pendingMessageId != 0L) {
                pendingMessageId = t.pendingMessageId
              }

              // Strictly separate transient network retries (8s x 2) from FLOOD_WAIT
              if (NetworkRetryPolicy.isTransientNetworkError(t, applicationContext)) {
                when (val decision = retryTracker.onFailure()) {
                  is RetryDecision.WaitAndRetry -> {
                    setProgress(
                      workDataOf(
                        KEY_CURRENT_FILE to "${nextItem.fileName} — Thử lại sau 8s (${decision.attemptNumber}/2)",
                        KEY_COMPLETED_COUNT to completed,
                        KEY_TOTAL_COUNT to initialTotal,
                      )
                    )
                    delay(decision.delayMs)
                    if (isStopped) {
                      mediaDao.updateUploadStatus(nextItem.id, UploadStatus.QUEUED, null)
                      return Result.success()
                    }
                    if (!tdLib.isSessionReady()) {
                      runCatching { tdLib.ensureConnected(forceReconnect = true) }
                    }
                  }
                  RetryDecision.StopAndNotify -> {
                    stoppedByNetworkFailure = true
                    mediaDao.updateUploadStatus(
                      id = nextItem.id,
                      status = UploadStatus.QUEUED,
                      error = "Mất kết nối mạng sau 3 lần thử",
                    )
                    repo.reportNetworkInterruption(NetworkOperationType.UPLOAD)
                    prefs.setLastSyncStatusMessage("Tải lên bị gián đoạn — Không thể kết nối Telegram")
                    return Result.failure()
                  }
                }
              } else {
                val msg = t.message ?: "Lỗi tải lên Telegram"
                mediaDao.updateUploadStatus(
                  id = nextItem.id,
                  status = UploadStatus.FAILED,
                  error = msg,
                )
                if (msg.contains("Chủ đề", ignoreCase = true) || msg.contains("quyền", ignoreCase = true)) {
                  prefs.setLastSyncStatusMessage(msg)
                }
                completed++
                itemHandled = true
              }
            }
          }
        } finally {
          stagingFile?.parentFile?.deleteRecursively() ?: stagingFile?.delete()
          uploadThumb?.file?.delete()
        }

        if (isStopped) break

        // Enforce 2.0-3.5s pacing + jitter between sequential files
        val latestSettings = prefs.getCurrentSettings()
        val basePacingMs = latestSettings.uploadPacingMs.coerceAtLeast(2000L)
        val jitterMs = Random.nextLong(0L, 1501L)
        delay(basePacingMs + jitterMs)
      }

      uploadedAnyOrClean = true
    } finally {
      withContext(NonCancellable) {
        if (isSelectedBatch && !hitFloodWaitRescheduled) {
          prefs.clearSelectedUploadBatchIds()
        }
        if (isStopped) {
          mediaDao.resetInterruptedUploadsToQueued()
          prefs.setLastSyncStatusMessage(
            if (tdLib.isAppInForeground()) {
              "Đã dừng đồng bộ — Phiên Telegram sẵn sàng"
            } else {
              "Đã dừng đồng bộ — Đã chuyển sang chế độ nền"
            }
          )
        } else if (!hitFloodWaitRescheduled && !stoppedByNetworkFailure && uploadedAnyOrClean) {
          prefs.setLastSyncStatusMessage(
            if (tdLib.isAppInForeground()) {
              "Đã đồng bộ xong — Phiên Telegram sẵn sàng"
            } else {
              "Đã đồng bộ xong — Đã chuyển sang chế độ nền"
            }
          )
        }
        tdLib.endWorkLease()
      }
    }

    return Result.success()
  }

  private suspend fun suspendForFloodWait(
    unlockAtEpochMs: Long,
    currentFileName: String,
    completedCount: Int,
    totalCount: Int,
  ): Boolean {
    while (!isStopped) {
      val remainingMs = unlockAtEpochMs - System.currentTimeMillis()
      if (remainingMs <= 0L) return true
      val remSec = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
      setProgress(
        workDataOf(
          KEY_CURRENT_FILE to "$currentFileName — Chờ FLOOD_WAIT ${remSec}s",
          KEY_COMPLETED_COUNT to completedCount,
          KEY_TOTAL_COUNT to totalCount,
        )
      )
      delay(minOf(1_000L, remainingMs))
    }
    return false
  }

  private suspend fun requeueFloodWaitPaused(
    repo: com.laughing.telephotos.data.repository.VaultRepository,
    mediaDao: com.laughing.telephotos.data.local.MediaDao,
    filterMediaType: MediaType?,
    isSelectedBatch: Boolean,
    selectedBatchIds: List<Long>,
  ) {
    when {
      isSelectedBatch -> repo.requeueFloodWaitPausedScoped(selectedBatchIds, filterMediaType)
      filterMediaType != null -> mediaDao.requeueFloodWaitPausedByType(filterMediaType.name)
      else -> mediaDao.requeueFloodWaitPaused()
    }
  }

  companion object {
    const val UNIQUE_WORK_NAME = "tele_photos_manual_upload"
    const val KEY_CURRENT_FILE = "current_file"
    const val KEY_COMPLETED_COUNT = "completed_count"
    const val KEY_TOTAL_COUNT = "total_count"
    const val KEY_FILTER_MEDIA_TYPE = "filter_media_type"
    const val KEY_IS_SELECTED_BATCH = "is_selected_batch"
    private const val SHORT_FLOOD_WAIT_MAX_SEC = 60
    private const val SHORT_FLOOD_WAIT_MAX_WAIT_MS = 65_000L
  }
}
