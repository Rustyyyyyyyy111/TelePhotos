package com.laughing.telephotos

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.laughing.telephotos.core.SettingsPreferences
import com.laughing.telephotos.data.local.TelePhotosDatabase
import com.laughing.telephotos.data.repository.VaultRepository
import com.laughing.telephotos.data.telegram.TdLibManager

/**
 * Tele Photos application container.
 * Enforces Manual-Only Sync: does NOT trigger Telegram network polling or background workers on startup.
 */
class TelePhotosApplication : Application(), ImageLoaderFactory {

  val database: TelePhotosDatabase by lazy { TelePhotosDatabase.getInstance(this) }
  val settingsPreferences: SettingsPreferences by lazy { SettingsPreferences(this) }
  val tdLibManager: TdLibManager by lazy { TdLibManager(this, settingsPreferences) }
  val vaultRepository: VaultRepository by lazy {
    VaultRepository(
      context = this,
      mediaDao = database.mediaDao(),
      albumDao = database.albumDao(),
      syncTargetDao = database.syncTargetDao(),
      tdLibManager = tdLibManager,
      settingsPreferences = settingsPreferences,
    )
  }

  override fun newImageLoader(): ImageLoader {
    return ImageLoader.Builder(this)
      .components {
        add(VideoFrameDecoder.Factory())
      }
      .memoryCache {
        MemoryCache.Builder(this)
          .maxSizePercent(0.15)
          .build()
      }
      .diskCache {
        DiskCache.Builder()
          .directory(cacheDir.resolve("coil_thumb_cache"))
          .maxSizeBytes(64L * 1024L * 1024L)
          .build()
      }
      .respectCacheHeaders(false)
      .build()
  }
}
