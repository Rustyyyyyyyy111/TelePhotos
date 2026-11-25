package com.laughing.telephotos.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.laughing.telephotos.data.local.MediaType
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GeneratedUploadThumbnail(
  val file: File,
  val width: Int,
  val height: Int,
)

data class ExtractedMediaMetadata(
  val contentHash: String,
  val fileName: String,
  val mimeType: String,
  val mediaType: MediaType,
  val sizeBytes: Long,
  val width: Int,
  val height: Int,
  val durationSec: Int,
  val takenAtEpochMs: Long,
)

data class LocalMediaDimensions(
  val width: Int,
  val height: Int,
  val durationSec: Int = 0,
)

object MediaHashUtil {

  private const val SAMPLED_THRESHOLD_BYTES = 16L * 1024L * 1024L
  private const val SAMPLE_CHUNK_BYTES = 1024 * 1024 // 1 MB

  suspend fun extractAndHash(context: Context, uri: Uri): ExtractedMediaMetadata =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      val mimeType = resolver.getType(uri) ?: "image/jpeg"
      var fileName = "media_${System.currentTimeMillis()}"
      var sizeBytes = 0L

      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
            if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
          }
        }

      val mediaType =
        if (mimeType.startsWith("video/", ignoreCase = true)) MediaType.VIDEO else MediaType.PHOTO

      var durationSec = 0
      var width = 0
      var height = 0
      var takenAtEpochMs = System.currentTimeMillis()

      if (mediaType == MediaType.VIDEO) {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(context, uri)
          val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
          durationSec = ((durMs + 500L) / 1000L).toInt().coerceAtLeast(1)
          width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
          height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
          durationSec = 1
        } finally {
          runCatching { retriever.release() }
        }
      }

      runCatching {
        resolver.query(uri, arrayOf("datetaken", "date_added"), null, null, null)?.use { c ->
          if (c.moveToFirst()) {
            val dtIdx = c.getColumnIndex("datetaken")
            val daIdx = c.getColumnIndex("date_added")
            val dt = if (dtIdx >= 0) c.getLong(dtIdx) else 0L
            val da = if (daIdx >= 0) c.getLong(daIdx) * 1000L else 0L
            if (dt > 0L) takenAtEpochMs = dt else if (da > 0L) takenAtEpochMs = da
          }
        }
      }

      val hash = computeContentHash(context, uri, sizeBytes)

      ExtractedMediaMetadata(
        contentHash = hash,
        fileName = fileName,
        mimeType = mimeType,
        mediaType = mediaType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationSec = durationSec,
        takenAtEpochMs = takenAtEpochMs,
      )
    }

  fun computeContentHash(context: Context, uri: Uri, knownSizeBytes: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val resolver = context.contentResolver

    if (knownSizeBytes > SAMPLED_THRESHOLD_BYTES) {
      resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
        afd.createInputStream().use { stream ->
          val channel = stream.channel
          val fileSize = channel.size().coerceAtLeast(knownSizeBytes)
          val buffer = ByteArray(64 * 1024)

          readSampleWindow(stream, digest, buffer, SAMPLE_CHUNK_BYTES.toLong())

          val midPos = (fileSize / 2L) - (SAMPLE_CHUNK_BYTES / 2L)
          if (midPos > SAMPLE_CHUNK_BYTES) {
            channel.position(midPos)
            readSampleWindow(stream, digest, buffer, SAMPLE_CHUNK_BYTES.toLong())
          }

          val tailPos = (fileSize - SAMPLE_CHUNK_BYTES).coerceAtLeast(0L)
          if (tailPos > midPos + SAMPLE_CHUNK_BYTES) {
            channel.position(tailPos)
            readSampleWindow(stream, digest, buffer, SAMPLE_CHUNK_BYTES.toLong())
          }

          digest.update("SIZE:$fileSize".toByteArray())
          return "sampled_" + digest.digest().toHexString()
        }
      }
    }

    resolver.openInputStream(uri)?.use { input ->
      val buffer = ByteArray(64 * 1024)
      var total = 0L
      while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
        total += read
      }
      if (total == 0L) {
        digest.update(uri.toString().toByteArray())
      }
    } ?: digest.update(uri.toString().toByteArray())

    return digest.digest().toHexString()
  }

  private fun readSampleWindow(
    input: java.io.InputStream,
    digest: MessageDigest,
    buffer: ByteArray,
    maxBytes: Long,
  ) {
    var remaining = maxBytes
    while (remaining > 0) {
      val toRead = minOf(buffer.size.toLong(), remaining).toInt()
      val read = input.read(buffer, 0, toRead)
      if (read <= 0) break
      digest.update(buffer, 0, read)
      remaining -= read
    }
  }

  suspend fun copyUriToStagingFile(context: Context, uri: Uri, fileName: String): File =
    withContext(Dispatchers.IO) {
      val stagingParent = File(
        File(context.cacheDir, "upload_staging"),
        "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
      ).apply { mkdirs() }
      val cleanFileName = fileName
        .replace(Regex("[/\\\\\\u0000:*?\"<>|]"), "_")
        .trim()
        .ifBlank { "media_${System.currentTimeMillis()}" }
      val outFile = File(stagingParent, cleanFileName)
      context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(outFile).use { output ->
          input.copyTo(output, bufferSize = 64 * 1024)
        }
      } ?: error("Không thể mở tệp nguồn để tải lên")
      outFile
    }

  /**
   * Generates a lightweight <= 320px JPEG preview thumbnail from a local staged photo or video
   * for attaching to TdApi.InputMessageDocument without memory leaks.
   */
  suspend fun generateUploadThumbnail(
    context: Context,
    localFile: File,
    mediaType: MediaType,
    maxEdgePx: Int = 320,
  ): GeneratedUploadThumbnail? = withContext(Dispatchers.IO) {
    if (!localFile.exists()) return@withContext null
    val thumbDir = File(context.cacheDir, "upload_thumbs").apply { mkdirs() }
    val outFile = File(thumbDir, "thumb_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")

    var sourceBitmap: Bitmap? = null
    var scaledBitmap: Bitmap? = null
    try {
      sourceBitmap = if (mediaType == MediaType.VIDEO) {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(localFile.absolutePath)
          retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(0L)
        } finally {
          runCatching { retriever.release() }
        }
      } else {
        decodeSampledPhotoWithExif(localFile.absolutePath, maxEdgePx)
      }

      val validSource = sourceBitmap ?: return@withContext null
      val maxSide = maxOf(validSource.width, validSource.height)
      val targetBitmap = if (maxSide > maxEdgePx && maxSide > 0) {
        val ratio = maxEdgePx.toFloat() / maxSide.toFloat()
        val targetW = (validSource.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (validSource.height * ratio).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(validSource, targetW, targetH, true).also {
          scaledBitmap = it
        }
      } else {
        validSource
      }

      val width = targetBitmap.width
      val height = targetBitmap.height
      FileOutputStream(outFile).use { fos ->
        targetBitmap.compress(Bitmap.CompressFormat.JPEG, 82, fos)
      }
      if (outFile.exists() && outFile.length() > 0L) {
        GeneratedUploadThumbnail(file = outFile, width = width, height = height)
      } else {
        outFile.delete()
        null
      }
    } catch (_: Throwable) {
      outFile.delete()
      null
    } finally {
      if (scaledBitmap != null && scaledBitmap !== sourceBitmap && scaledBitmap?.isRecycled == false) {
        scaledBitmap?.recycle()
      }
      if (sourceBitmap != null && sourceBitmap.isRecycled == false) {
        sourceBitmap.recycle()
      }
    }
  }

  private fun decodeSampledPhotoWithExif(path: String, maxEdgePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (maxDim / sampleSize > maxEdgePx * 2) {
      sampleSize *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = BitmapFactory.decodeFile(path, opts) ?: return null

    val degrees = runCatching {
      val exif = ExifInterface(path)
      when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
      }
    }.getOrDefault(0f)

    if (degrees == 0f) return decoded

    val matrix = Matrix().apply { postRotate(degrees) }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (rotated !== decoded) {
      decoded.recycle()
    }
    return rotated
  }

  suspend fun computeFileSha256(file: File): String =
    withContext(Dispatchers.IO) {
      val digest = MessageDigest.getInstance("SHA-256")
      val fileSize = file.length()
      if (fileSize > SAMPLED_THRESHOLD_BYTES) {
        java.io.FileInputStream(file).use { stream ->
          val channel = stream.channel
          val buffer = ByteArray(64 * 1024)
          readSampleWindow(stream, digest, buffer, SAMPLE_CHUNK_BYTES.toLong())
          val midPos = (fileSize / 2L) - (SAMPLE_CHUNK_BYTES / 2L)
          if (midPos > SAMPLE_CHUNK_BYTES) {
            channel.position(midPos)
            readSampleWindow(stream, digest, buffer, SAMPLE_CHUNK_BYTES.toLong())
          }
          val tailPos = (fileSize - SAMPLE_CHUNK_BYTES).coerceAtLeast(0L)
          if (tailPos > midPos + SAMPLE_CHUNK_BYTES) {
            channel.position(tailPos)
            readSampleWindow(stream, digest, buffer, SAMPLE_CHUNK_BYTES.toLong())
          }
          digest.update("SIZE:$fileSize".toByteArray())
          return@withContext "sampled_" + digest.digest().toHexString()
        }
      }
      java.io.FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val read = input.read(buffer)
          if (read <= 0) break
          digest.update(buffer, 0, read)
        }
      }
      digest.digest().toHexString()
    }

  suspend fun extractLocalMediaDimensions(
    localFile: File,
    mediaType: MediaType,
  ): LocalMediaDimensions = withContext(Dispatchers.IO) {
    if (!localFile.exists()) return@withContext LocalMediaDimensions(0, 0, 0)
    if (mediaType == MediaType.VIDEO) {
      val retriever = MediaMetadataRetriever()
      return@withContext try {
        retriever.setDataSource(localFile.absolutePath)
        val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
          ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
          ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
          ?.toIntOrNull() ?: 0
        val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
          ?.toLongOrNull() ?: 0L
        val durSec = if (durMs > 0L) ((durMs + 500L) / 1000L).toInt().coerceAtLeast(1) else 0
        val swapped = rotation == 90 || rotation == 270
        LocalMediaDimensions(
          width = if (swapped) rawH else rawW,
          height = if (swapped) rawW else rawH,
          durationSec = durSec,
        )
      } catch (_: Throwable) {
        LocalMediaDimensions(0, 0, 0)
      } finally {
        runCatching { retriever.release() }
      }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(localFile.absolutePath, bounds)
    val rawW = bounds.outWidth.coerceAtLeast(0)
    val rawH = bounds.outHeight.coerceAtLeast(0)
    val orientation = runCatching {
      ExifInterface(localFile.absolutePath)
        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val swapped = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
      orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
      orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
      orientation == ExifInterface.ORIENTATION_TRANSVERSE
    LocalMediaDimensions(
      width = if (swapped) rawH else rawW,
      height = if (swapped) rawW else rawH,
      durationSec = 0,
    )
  }

  private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }
}
