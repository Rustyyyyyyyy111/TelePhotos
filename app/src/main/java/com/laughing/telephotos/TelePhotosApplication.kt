package com.laughing.telephotos

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.laughing.telephotos.core.NetworkAlertCoordinator
import com.laughing.telephotos.core.SettingsPreferences
import com.laughing.telephotos.data.local.TelePhotosDatabase
import com.laughing.telephotos.data.repository.VaultRepository
import com.laughing.telephotos.data.telegram.TdLibManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tele Photos application container.
 * Hosts singleton TdLibManager & VaultRepository; keeps the TDLib session alive while
 * the app is in foreground and closes it cleanly when moved to background.
 */
class TelePhotosApplication : Application(), ImageLoaderFactory {

  val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val database: TelePhotosDatabase by lazy { TelePhotosDatabase.getInstance(this) }
  val settingsPreferences: SettingsPreferences by lazy { SettingsPreferences(this) }
  val networkAlertCoordinator: NetworkAlertCoordinator by lazy { NetworkAlertCoordinator() }
  val tdLibManager: TdLibManager by lazy { TdLibManager(this, settingsPreferences, networkAlertCoordinator) }
  val vaultRepository: VaultRepository by lazy {
    VaultRepository(
      context = this,
      mediaDao = database.mediaDao(),
      albumDao = database.albumDao(),
      syncTargetDao = database.syncTargetDao(),
      tdLibManager = tdLibManager,
      settingsPreferences = settingsPreferences,
      networkAlertCoordinator = networkAlertCoordinator,
    )
  }

  private var startedActivityCount = 0
  private var isActivityChangingConfigurations = false
  @Volatile var hasInitializedUiInCurrentProcess: Boolean = false

  override fun onCreate() {
    super.onCreate()
    registerActivityLifecycleCallbacks(
      object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) {
          if (++startedActivityCount == 1 && !isActivityChangingConfigurations) {
            applicationScope.launch {
              tdLibManager.onAppForegrounded()
            }
          }
        }

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) {
          isActivityChangingConfigurations = activity.isChangingConfigurations
          if (--startedActivityCount <= 0) {
            startedActivityCount = 0
            if (!isActivityChangingConfigurations) {
              applicationScope.launch {
                tdLibManager.onAppBackgrounded()
              }
            }
          }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
      }
    )
  }

  override fun newImageLoader(): ImageLoader {
    return ImageLoader.Builder(this)
      .components {
        add(VideoFrameDecoder.Factory())
      }
      .allowRgb565(true)
      .crossfade(false)
      .memoryCache {
        MemoryCache.Builder(this)
          .maxSizePercent(0.14)
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
