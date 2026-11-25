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
 * On-demand single-thread sequential upload worker triggered ONLY by explicit user tap.
 * - Opens a short-lived TDLib session, uploads only new/unsynced items,
 *   routes unassigned photos/videos to auto-created "Ảnh" / "Video" Forum Topics,
 *   retries genuine network interruptions twice with an 8s wait (stopping & notifying on the 3rd),
 *   and immediately closes TDLib (TdApi.Close()) on completion, error, or "Dừng đồng bộ".
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

    val settings = prefs.getCurrentSettings()
    val now = System.currentTimeMillis()
    if (settings.floodWaitUntilEpochMs > now) {
      return Result.success()
    } else if (settings.floodWaitUntilEpochMs > 0L) {
      prefs.clearFloodWait()
    }

    val initialTarget = syncTargetDao.getTarget() ?: return Result.success()
    if (!initialTarget.isLocked || !settings.isTelegramLoggedIn) return Result.success()

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
            repo.reportNetworkInterruption(NetworkOperationType.UPLOAD)
            prefs.setLastSyncStatusMessage("Tải lên bị gián đoạn — Không thể kết nối Telegram")
            return Result.failure()
          }
        }
      }

      val connectResult = tdLib.acquireShortLivedSession()
      if (connectResult.isSuccess) {
        retryTracker.reset()
        sessionConnected = true
        break
      }

      val err = connectResult.exceptionOrNull()
      if (!NetworkRetryPolicy.isTransientNetworkError(err, applicationContext)) {
        prefs.setLastSyncStatusMessage(err?.message ?: "Không thể kết nối Telegram — Đã ngắt kết nối")
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
          repo.reportNetworkInterruption(NetworkOperationType.UPLOAD)
          prefs.setLastSyncStatusMessage("Tải lên bị gián đoạn — Không thể kết nối Telegram")
          return Result.failure()
        }
      }
    }

    if (!sessionConnected) {
      return Result.success()
    }

    val filterMediaType = inputData.getString(KEY_FILTER_MEDIA_TYPE)
      ?.let { raw -> runCatching { MediaType.valueOf(raw) }.getOrNull() }

    var hitFloodWait = false
    var stoppedByNetworkFailure = false
    var uploadedAnyOrClean = false
    try {
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

      val initialTotal = (if (filterMediaType != null) {
        mediaDao.countPendingUploadsByType(filterMediaType.name)
      } else {
        mediaDao.countPendingUploads()
      }).coerceAtLeast(1)
      var completed = 0

      while (!isStopped) {
        val nextItem = if (filterMediaType != null) {
          mediaDao.getNextPendingUploadByType(filterMediaType.name)
        } else {
          mediaDao.getNextPendingUpload()
        } ?: break
        val currentTarget = syncTargetDao.getTarget() ?: initialTarget

        // 2-layer deduplication guard: skip if hash was already uploaded
        val alreadyUploaded = mediaDao.findUploadedByHash(nextItem.contentHash)
        if (alreadyUploaded != null && alreadyUploaded.id != nextItem.id) {
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
              topicId = alreadyUploaded.topicId ?: nextItem.topicId,
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
                    topicId = uploadedDup.topicId ?: nextItem.topicId,
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
                tdLib.acquireShortLivedSession().getOrThrow()
              }

              val refreshedTarget = syncTargetDao.getTarget() ?: currentTarget
              val resolvedTopicId = repo.resolveUploadTopicId(effectiveItem, refreshedTarget)
              val caption = repo.buildDocumentCaption(effectiveItem.copy(topicId = resolvedTopicId))

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

              mediaDao.markUploaded(
                id = effectiveItem.id,
                chatId = sent.chatId,
                messageId = sent.messageId,
                fileId = sent.fileId,
                remoteFileId = sent.remoteFileId,
                topicId = sent.messageThreadId ?: resolvedTopicId,
                thumbFileId = sent.thumbFileId,
              )
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
              hitFloodWait = true
              repo.handleFloodWait(flood)
              return Result.success()
            } catch (t: Throwable) {
              if (isStopped) {
                mediaDao.updateUploadStatus(nextItem.id, UploadStatus.QUEUED, null)
                return Result.success()
              }
              if (t is PendingUploadInterruptedException && t.pendingMessageId != 0L) {
                pendingMessageId = t.pendingMessageId
              }

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
                      runCatching { tdLib.closeConnectionIdle(preserveWaitApiState = false) }
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
                    prefs.setLastSyncStatusMessage("Tải lên bị gián đoạn — Đã ngắt kết nối Telegram")
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
        val basePacingMs = settings.uploadPacingMs.coerceAtLeast(2000L)
        val jitterMs = Random.nextLong(0L, 1501L)
        delay(basePacingMs + jitterMs)
      }

      uploadedAnyOrClean = true
    } finally {
      withContext(NonCancellable) {
        if (isStopped) {
          mediaDao.resetInterruptedUploadsToQueued()
          prefs.setLastSyncStatusMessage("Đã dừng đồng bộ — Đã ngắt kết nối Telegram")
        } else if (!hitFloodWait && !stoppedByNetworkFailure && uploadedAnyOrClean) {
          prefs.setLastSyncStatusMessage(
            "Đã đồng bộ xong — Đã ngắt kết nối Telegram (chỉ kết nối khi bạn bấm đồng bộ)"
          )
        }
        tdLib.releaseShortLivedSession(forceClose = true)
      }
    }

    return Result.success()
  }

  companion object {
    const val UNIQUE_WORK_NAME = "tele_photos_manual_upload"
    const val KEY_CURRENT_FILE = "current_file"
    const val KEY_COMPLETED_COUNT = "completed_count"
    const val KEY_TOTAL_COUNT = "total_count"
    const val KEY_FILTER_MEDIA_TYPE = "filter_media_type"
  }
}
