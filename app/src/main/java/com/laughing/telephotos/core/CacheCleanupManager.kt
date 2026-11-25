package com.laughing.telephotos.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages lightweight LRU cache bounds (~500 MB) and manual cleanup for online viewing
 * without touching TDLib's login database (filesDir/tdlib_db) or local device MediaStore files.
 */
object CacheCleanupManager {

  const val MAX_ONLINE_CACHE_BYTES: Long = 500L * 1024L * 1024L

  private val managedCacheDirNames = listOf(
    "tdlib_files",
    "coil_thumb_cache",
    "upload_thumbs",
    "trash_thumbs",
    "upload_staging",
  )

  private fun managedDirs(context: Context): List<File> =
    managedCacheDirNames.map { File(context.cacheDir, it) }

  suspend fun calculateCacheSizeBytes(context: Context): Long = withContext(Dispatchers.IO) {
    var total = 0L
    for (dir in managedDirs(context)) {
      if (!dir.exists()) continue
      dir.walkTopDown().forEach { f ->
        if (f.isFile) {
          total += f.length()
        }
      }
    }
    total
  }

  /**
   * Automatically prunes oldest cached files (by lastModified) whenever total temporary cache
   * exceeds [maxBytes] (default 500 MB).
   */
  suspend fun enforceCacheLimitLru(
    context: Context,
    maxBytes: Long = MAX_ONLINE_CACHE_BYTES,
  ): Long = withContext(Dispatchers.IO) {
    val allFiles = mutableListOf<File>()
    var totalSize = 0L
    for (dir in managedDirs(context)) {
      if (!dir.exists()) continue
      dir.walkTopDown().forEach { file ->
        if (file.isFile) {
          allFiles.add(file)
          totalSize += file.length()
        }
      }
    }

    if (totalSize <= maxBytes) {
      return@withContext totalSize
    }

    // Oldest files first (LRU approximation via filesystem mtime)
    allFiles.sortBy { it.lastModified() }
    for (file in allFiles) {
      if (totalSize <= maxBytes) break
      val len = file.length()
      if (runCatching { file.delete() }.getOrDefault(false)) {
        totalSize = (totalSize - len).coerceAtLeast(0L)
      }
    }

    // Remove empty subdirectories left behind
    for (dir in managedDirs(context)) {
      if (!dir.exists()) continue
      dir.walkBottomUp().forEach { sub ->
        if (sub.isDirectory && sub != dir && sub.listFiles().isNullOrEmpty()) {
          runCatching { sub.delete() }
        }
      }
    }

    totalSize
  }

  /**
   * Clears all temporary online-viewing and thumbnail cache directories while preserving
   * TDLib session/database files and device MediaStore files.
   */
  suspend fun clearAllTemporaryCache(context: Context): Long = withContext(Dispatchers.IO) {
    var freed = 0L
    for (dir in managedDirs(context)) {
      if (!dir.exists()) continue
      dir.walkBottomUp().forEach { file ->
        if (file.isFile) {
          val len = file.length()
          if (runCatching { file.delete() }.getOrDefault(false)) {
            freed += len
          }
        } else if (file.isDirectory && file != dir) {
          runCatching { file.delete() }
        }
      }
    }
    freed
  }
}
