package com.laughing.telephotos.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.laughing.telephotos.data.local.MediaType
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
      val stagingDir = File(context.cacheDir, "upload_staging").apply { mkdirs() }
      val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
      val outFile = File(stagingDir, "${System.currentTimeMillis()}_$safeName")
      context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(outFile).use { output ->
          input.copyTo(output, bufferSize = 64 * 1024)
        }
      } ?: error("Không thể mở tệp nguồn để tải lên")
      outFile
    }

  private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }
}
