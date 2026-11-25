package com.laughing.telephotos.core

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.MediaType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class DeviceMediaEntry(
  val mediaStoreId: Long,
  val contentUri: String,
  val fileName: String,
  val mimeType: String,
  val mediaType: MediaType,
  val sizeBytes: Long,
  val width: Int,
  val height: Int,
  val durationSec: Int,
  val takenAtEpochMs: Long,
)

sealed interface LocalDeletePrepResult {
  data class NoSystemPromptNeeded(val deletedUris: Set<String> = emptySet()) : LocalDeletePrepResult
  data class NeedsSystemConfirmation(
    val intentSender: IntentSender,
    val targetUris: Set<String>,
  ) : LocalDeletePrepResult
}

object MediaStoreScanner {

  fun requiredMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
      )
    } else {
      arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

  fun hasFullMediaReadPermission(context: Context, mediaType: MediaType): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val perm = if (mediaType == MediaType.VIDEO) {
        Manifest.permission.READ_MEDIA_VIDEO
      } else {
        Manifest.permission.READ_MEDIA_IMAGES
      }
      ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    } else {
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_EXTERNAL_STORAGE,
      ) == PackageManager.PERMISSION_GRANTED
    }
  }

  fun hasMediaReadPermission(context: Context, mediaType: MediaType): Boolean {
    if (hasFullMediaReadPermission(context, mediaType)) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
      ) == PackageManager.PERMISSION_GRANTED
    }
    return false
  }

  suspend fun scanDeviceImages(context: Context): List<DeviceMediaEntry> =
    withContext(Dispatchers.IO) {
      if (!hasMediaReadPermission(context, MediaType.PHOTO)) return@withContext emptyList()
      queryMediaStore(
        context = context,
        collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        mediaType = MediaType.PHOTO,
      )
    }

  suspend fun scanDeviceVideos(context: Context): List<DeviceMediaEntry> =
    withContext(Dispatchers.IO) {
      if (!hasMediaReadPermission(context, MediaType.VIDEO)) return@withContext emptyList()
      queryMediaStore(
        context = context,
        collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        mediaType = MediaType.VIDEO,
      )
    }

  private fun queryMediaStore(
    context: Context,
    collectionUri: Uri,
    mediaType: MediaType,
  ): List<DeviceMediaEntry> {
    val results = mutableListOf<DeviceMediaEntry>()
    val projection = mutableListOf(
      MediaStore.MediaColumns._ID,
      MediaStore.MediaColumns.DISPLAY_NAME,
      MediaStore.MediaColumns.MIME_TYPE,
      MediaStore.MediaColumns.SIZE,
      MediaStore.MediaColumns.WIDTH,
      MediaStore.MediaColumns.HEIGHT,
      MediaStore.MediaColumns.DATE_TAKEN,
      MediaStore.MediaColumns.DATE_ADDED,
    ).apply {
      if (mediaType == MediaType.VIDEO) {
        add(MediaStore.Video.VideoColumns.DURATION)
      }
    }.toTypedArray()

    val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
    runCatching {
      context.contentResolver.query(
        collectionUri,
        projection,
        null,
        null,
        sortOrder,
      )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val takenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val addedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        val durationCol = if (mediaType == MediaType.VIDEO) {
          cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
        } else {
          -1
        }

        while (cursor.moveToNext()) {
          val id = cursor.getLong(idCol)
          val contentUri = ContentUris.withAppendedId(collectionUri, id).toString()
          val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
          if (sizeBytes <= 0L) continue

          val defaultName = if (mediaType == MediaType.VIDEO) "video_$id.mp4" else "photo_$id.jpg"
          val fileName = (if (nameCol >= 0) cursor.getString(nameCol) else null)
            ?.takeIf { it.isNotBlank() } ?: defaultName
          val defaultMime = if (mediaType == MediaType.VIDEO) "video/mp4" else "image/jpeg"
          val mimeType = (if (mimeCol >= 0) cursor.getString(mimeCol) else null)
            ?.takeIf { it.isNotBlank() } ?: defaultMime
          val width = if (widthCol >= 0) cursor.getInt(widthCol) else 0
          val height = if (heightCol >= 0) cursor.getInt(heightCol) else 0

          val dateTaken = if (takenCol >= 0) cursor.getLong(takenCol) else 0L
          val dateAddedSec = if (addedCol >= 0) cursor.getLong(addedCol) else 0L
          val takenAt = when {
            dateTaken > 0L -> dateTaken
            dateAddedSec > 0L -> dateAddedSec * 1000L
            else -> System.currentTimeMillis()
          }

          val durationSec = if (durationCol >= 0) {
            val durMs = cursor.getLong(durationCol)
            if (durMs > 0L) ((durMs + 500L) / 1000L).toInt().coerceAtLeast(1) else 0
          } else {
            0
          }

          results.add(
            DeviceMediaEntry(
              mediaStoreId = id,
              contentUri = contentUri,
              fileName = fileName,
              mimeType = mimeType,
              mediaType = mediaType,
              sizeBytes = sizeBytes,
              width = width,
              height = height,
              durationSec = durationSec,
              takenAtEpochMs = takenAt,
            )
          )
        }
      }
    }

    return results.sortedByDescending { it.takenAtEpochMs }
  }

  /**
   * Filters valid existing MediaStore URIs eligible for Android system deletion
   * and returns either an IntentSender (API 30+ createDeleteRequest or API 29 RecoverableSecurityException)
   * or deletes directly on API <= 29 when permitted.
   */
  suspend fun prepareSystemLocalDelete(
    context: Context,
    items: List<MediaEntity>,
  ): LocalDeletePrepResult = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val deletableUris = items.mapNotNull { item ->
      val raw = item.localUri ?: return@mapNotNull null
      val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@mapNotNull null
      if (uri.scheme != "content" || uri.authority != "media") return@mapNotNull null
      if (uri.path?.contains("/picker") == true) return@mapNotNull null
      val exists = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { c ->
          c.moveToFirst()
        } ?: false
      }.getOrDefault(false)
      if (exists) uri else null
    }.distinct()

    if (deletableUris.isEmpty()) {
      return@withContext LocalDeletePrepResult.NoSystemPromptNeeded()
    }

    val uriStrings = deletableUris.map { it.toString() }.toSet()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val pendingIntent = runCatching {
        MediaStore.createDeleteRequest(resolver, deletableUris)
      }.getOrNull()
      if (pendingIntent != null) {
        return@withContext LocalDeletePrepResult.NeedsSystemConfirmation(
          intentSender = pendingIntent.intentSender,
          targetUris = uriStrings,
        )
      }
    }

    val deletedDirect = mutableSetOf<String>()
    for (uri in deletableUris) {
      try {
        if (resolver.delete(uri, null, null) > 0) {
          deletedDirect.add(uri.toString())
        }
      } catch (sec: SecurityException) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sec is RecoverableSecurityException) {
          return@withContext LocalDeletePrepResult.NeedsSystemConfirmation(
            intentSender = sec.userAction.actionIntent.intentSender,
            targetUris = uriStrings,
          )
        }
      }
    }
    LocalDeletePrepResult.NoSystemPromptNeeded(deletedDirect)
  }

  /**
   * Saves a lightweight 256px JPEG thumbnail into app cacheDir before Android removes the
   * original file from MediaStore, keeping previews intact in "Đã xoá gần đây".
   */
  suspend fun cacheSmallThumbnailForTrash(context: Context, item: MediaEntity): String? =
    withContext(Dispatchers.IO) {
      if (!item.cachedThumbPath.isNullOrBlank() && File(item.cachedThumbPath).exists()) {
        return@withContext item.cachedThumbPath
      }
      val uriStr = item.localUri ?: return@withContext null
      val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@withContext null

      val thumbDir = File(context.cacheDir, "trash_thumbs").apply { mkdirs() }
      val outFile = File(thumbDir, "thumb_${item.id}_${System.currentTimeMillis()}.jpg")

      val bitmap: Bitmap? = if (item.mediaType == MediaType.VIDEO) {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(context, uri)
          val frame = retriever.getFrameAtTime(0L)
          frame?.let { scaleBitmapDown(it, 280) }
        } catch (_: Throwable) {
          null
        } finally {
          runCatching { retriever.release() }
        }
      } else {
        runCatching {
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
          }
          val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
          var sample = 1
          while (maxDim / sample > 512) {
            sample *= 2
          }
          val opts = BitmapFactory.Options().apply { inSampleSize = sample }
          context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
          }?.let { scaleBitmapDown(it, 280) }
        }.getOrNull()
      }

      if (bitmap != null) {
        val saved = runCatching {
          FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 78, fos)
          }
          true
        }.getOrDefault(false)
        bitmap.recycle()
        if (saved && outFile.exists()) return@withContext outFile.absolutePath
      }
      null
    }

  private fun scaleBitmapDown(source: Bitmap, maxEdge: Int): Bitmap {
    val maxSide = maxOf(source.width, source.height)
    if (maxSide <= maxEdge || maxSide <= 0) return source
    val ratio = maxEdge.toFloat() / maxSide.toFloat()
    val w = (source.width * ratio).toInt().coerceAtLeast(1)
    val h = (source.height * ratio).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(source, w, h, true)
    if (scaled !== source) source.recycle()
    return scaled
  }

  fun isUsableLocalFile(path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    return runCatching {
      val file = File(path)
      file.exists() && file.isFile && file.canRead() && file.length() > 0L
    }.getOrDefault(false)
  }

  fun isLocalUriAccessible(context: Context, uriString: String?): Boolean {
    if (uriString.isNullOrBlank()) return false
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
    if (uri.scheme == "file") {
      return isUsableLocalFile(uri.path)
    }
    return runCatching {
      context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
        afd.parcelFileDescriptor.statSize != 0L
      } ?: false
    }.getOrDefault(false)
  }

  /**
   * Saves an original downloaded file to the device's public gallery:
   * - Photos -> Pictures/Tele Photos
   * - Videos -> Movies/Tele Photos
   */
  suspend fun saveFileToPublicGallery(
    context: Context,
    sourceFile: File,
    fileName: String,
    mimeType: String,
    mediaType: MediaType,
  ): Uri = withContext(Dispatchers.IO) {
    require(sourceFile.exists() && sourceFile.length() > 0L) {
      "Không tìm thấy tệp nguồn hợp lệ để lưu về máy"
    }

    val safeName = fileName
      .replace(Regex("[/\\\\\\u0000:*?\"<>|]"), "_")
      .trim()
      .ifBlank {
        if (mediaType == MediaType.VIDEO) "video_${System.currentTimeMillis()}.mp4"
        else "photo_${System.currentTimeMillis()}.jpg"
      }

    val safeMime = mimeType.ifBlank {
      if (mediaType == MediaType.VIDEO) "video/mp4" else "image/jpeg"
    }

    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val collection = if (mediaType == MediaType.VIDEO) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
      } else {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
      }
      val relativeFolder = if (mediaType == MediaType.VIDEO) {
        "${Environment.DIRECTORY_MOVIES}/Tele Photos"
      } else {
        "${Environment.DIRECTORY_PICTURES}/Tele Photos"
      }
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
        put(MediaStore.MediaColumns.MIME_TYPE, safeMime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
      val itemUri = resolver.insert(collection, values)
        ?: error("Không thể tạo mục mới trong thư viện ảnh thiết bị")

      try {
        resolver.openOutputStream(itemUri)?.use { out ->
          FileInputStream(sourceFile).use { input ->
            input.copyTo(out, bufferSize = 64 * 1024)
          }
        } ?: error("Không thể ghi tệp vào thư viện thiết bị")

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)
        return@withContext itemUri
      } catch (t: Throwable) {
        runCatching { resolver.delete(itemUri, null, null) }
        throw t
      }
    } else {
      @Suppress("DEPRECATION")
      val baseDir = Environment.getExternalStoragePublicDirectory(
        if (mediaType == MediaType.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
      )
      val targetDir = File(baseDir, "Tele Photos").apply { mkdirs() }
      var destFile = File(targetDir, safeName)
      if (destFile.exists()) {
        val dotIdx = safeName.lastIndexOf('.')
        val stem = if (dotIdx > 0) safeName.substring(0, dotIdx) else safeName
        val ext = if (dotIdx > 0) safeName.substring(dotIdx) else ""
        destFile = File(targetDir, "${stem}_${System.currentTimeMillis()}$ext")
      }
      FileInputStream(sourceFile).use { input ->
        FileOutputStream(destFile).use { out ->
          input.copyTo(out, bufferSize = 64 * 1024)
        }
      }
      val scannedUri = scanFileWithMediaScanner(context, destFile.absolutePath, safeMime)
      return@withContext scannedUri ?: Uri.fromFile(destFile)
    }
  }

  private suspend fun scanFileWithMediaScanner(
    context: Context,
    path: String,
    mimeType: String,
  ): Uri? = suspendCancellableCoroutine { cont ->
    MediaScannerConnection.scanFile(
      context,
      arrayOf(path),
      arrayOf(mimeType),
    ) { _, uri ->
      if (cont.isActive) cont.resume(uri)
    }
  }
}
