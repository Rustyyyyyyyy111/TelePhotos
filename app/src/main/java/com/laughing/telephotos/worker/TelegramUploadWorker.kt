package com.laughing.telephotos.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.laughing.telephotos.TelePhotosApplication
import com.laughing.telephotos.core.MediaHashUtil
import com.laughing.telephotos.data.local.UploadStatus
import com.laughing.telephotos.data.telegram.FloodWaitException
import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * On-demand single-thread sequential upload worker triggered ONLY by explicit user tap.
 * - Opens a short-lived TDLib session, uploads only new/unsynced items,
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

    val target = syncTargetDao.getTarget() ?: return Result.success()
    if (!target.isLocked || !settings.isTelegramLoggedIn) return Result.success()

    val connectResult = tdLib.acquireShortLivedSession()
    if (connectResult.isFailure) {
      prefs.setLastSyncStatusMessage(
        connectResult.exceptionOrNull()?.message ?: "Không thể kết nối Telegram — Đã ngắt kết nối"
      )
      return Result.failure()
    }

    var hitFloodWait = false
    var uploadedAnyOrClean = false
    try {
      val initialTotal = mediaDao.countPendingUploads().coerceAtLeast(1)
      var completed = 0

      while (!isStopped) {
        val nextItem = mediaDao.getNextPendingUpload() ?: break

        // 2-layer deduplication guard: skip if hash was already uploaded
        val alreadyUploaded = mediaDao.findUploadedByHash(nextItem.contentHash)
        if (alreadyUploaded != null && alreadyUploaded.id != nextItem.id) {
          mediaDao.markUploaded(
            id = nextItem.id,
            chatId = alreadyUploaded.telegramChatId ?: target.chatId,
            messageId = alreadyUploaded.telegramMessageId ?: 0L,
            fileId = alreadyUploaded.telegramFileId,
            remoteFileId = alreadyUploaded.telegramRemoteFileId,
            topicId = alreadyUploaded.topicId ?: nextItem.topicId,
          )
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
        try {
          stagingFile = MediaHashUtil.copyUriToStagingFile(
            context = applicationContext,
            uri = Uri.parse(uriString),
            fileName = nextItem.fileName,
          )

          val caption = repo.buildDocumentCaption(nextItem)
          val sent = tdLib.sendDocumentOriginal(
            chatId = target.chatId,
            messageThreadId = nextItem.topicId ?: target.defaultMessageThreadId,
            localFilePath = stagingFile.absolutePath,
            caption = caption,
          )

          mediaDao.markUploaded(
            id = nextItem.id,
            chatId = sent.chatId,
            messageId = sent.messageId,
            fileId = sent.fileId,
            remoteFileId = sent.remoteFileId,
            topicId = sent.messageThreadId ?: nextItem.topicId,
          )
          completed++
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
          mediaDao.updateUploadStatus(
            id = nextItem.id,
            status = UploadStatus.FAILED,
            error = t.message ?: "Lỗi tải lên Telegram",
          )
          completed++
        } finally {
          stagingFile?.delete()
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
        } else if (!hitFloodWait && uploadedAnyOrClean) {
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
  }
}
