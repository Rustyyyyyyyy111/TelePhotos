package com.laughing.telephotos.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.laughing.telephotos.data.telegram.TelegramUploadMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.telePhotosDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "tele_photos_settings"
)

data class AppSettings(
  val themeMode: ThemeMode = ThemeMode.LIGHT,
  val advancedBlurEnabled: Boolean = false,
  val appIconPreset: AppIconPreset = AppIconPreset.DEFAULT,
  val wifiOnlyUpload: Boolean = true,
  val uploadPacingMs: Long = 2000L,
  val photoGridColumns: Int = 3,
  val defaultUploadAlbumId: Long = 0L,
  val floodWaitUntilEpochMs: Long = 0L,
  val floodWaitRetryAfterSec: Int = 0,
  val floodWaitReason: String? = null,
  val lastHistorySyncEpochMs: Long = 0L,
  val customTelegramApiId: Int = 0,
  val customTelegramApiHash: String = "",
  val isTelegramLoggedIn: Boolean = false,
  val telegramUserId: Long = 0L,
  val telegramDisplayName: String = "",
  val telegramPhoneNumber: String = "",
  val lastSyncStatusMessage: String = "",
) {
  fun isFloodWaitActive(nowMs: Long = System.currentTimeMillis()): Boolean =
    floodWaitUntilEpochMs > nowMs
}

class SettingsPreferences(private val context: Context) {

  private object Keys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val ADVANCED_BLUR = booleanPreferencesKey("advanced_blur")
    val APP_ICON_PRESET = stringPreferencesKey("app_icon_preset")
    val WIFI_ONLY = booleanPreferencesKey("wifi_only_upload")
    val UPLOAD_PACING_MS = longPreferencesKey("upload_pacing_ms")
    val PHOTO_GRID_COLUMNS = intPreferencesKey("photo_grid_columns")
    val DEFAULT_UPLOAD_ALBUM_ID = longPreferencesKey("default_upload_album_id")
    val FLOOD_WAIT_UNTIL = longPreferencesKey("flood_wait_until_epoch_ms")
    val FLOOD_WAIT_RETRY_SEC = intPreferencesKey("flood_wait_retry_after_sec")
    val FLOOD_WAIT_REASON = stringPreferencesKey("flood_wait_reason")
    val LAST_HISTORY_SYNC = longPreferencesKey("last_history_sync_epoch_ms")
    val CUSTOM_API_ID = intPreferencesKey("custom_telegram_api_id")
    val CUSTOM_API_HASH = stringPreferencesKey("custom_telegram_api_hash")
    val TG_LOGGED_IN = booleanPreferencesKey("tg_logged_in")
    val TG_USER_ID = longPreferencesKey("tg_user_id")
    val TG_DISPLAY_NAME = stringPreferencesKey("tg_display_name")
    val TG_PHONE_NUMBER = stringPreferencesKey("tg_phone_number")
    val LAST_SYNC_STATUS_MSG = stringPreferencesKey("last_sync_status_msg")
    val SELECTED_UPLOAD_BATCH_IDS = stringPreferencesKey("selected_upload_batch_ids")
    val ACTIVE_UPLOAD_BATCH_MODE = stringPreferencesKey("active_upload_batch_mode")
  }

  val settingsFlow: Flow<AppSettings> =
    context.telePhotosDataStore.data.map { prefs ->
      val modeName = prefs[Keys.THEME_MODE] ?: ThemeMode.LIGHT.name
      val themeMode =
        ThemeMode.entries.firstOrNull { it.name == modeName } ?: ThemeMode.LIGHT
      val iconName = prefs[Keys.APP_ICON_PRESET] ?: AppIconPreset.DEFAULT.name
      val appIconPreset =
        AppIconPreset.entries.firstOrNull { it.name == iconName } ?: AppIconPreset.DEFAULT
      val storedCols = prefs[Keys.PHOTO_GRID_COLUMNS] ?: 3
      AppSettings(
        themeMode = themeMode,
        advancedBlurEnabled = prefs[Keys.ADVANCED_BLUR] ?: false,
        appIconPreset = appIconPreset,
        wifiOnlyUpload = prefs[Keys.WIFI_ONLY] ?: true,
        uploadPacingMs = prefs[Keys.UPLOAD_PACING_MS] ?: 2000L,
        photoGridColumns = storedCols.coerceIn(MIN_PHOTO_GRID_COLUMNS, MAX_PHOTO_GRID_COLUMNS),
        defaultUploadAlbumId = (prefs[Keys.DEFAULT_UPLOAD_ALBUM_ID] ?: 0L).coerceAtLeast(0L),
        floodWaitUntilEpochMs = prefs[Keys.FLOOD_WAIT_UNTIL] ?: 0L,
        floodWaitRetryAfterSec = prefs[Keys.FLOOD_WAIT_RETRY_SEC] ?: 0,
        floodWaitReason = prefs[Keys.FLOOD_WAIT_REASON],
        lastHistorySyncEpochMs = prefs[Keys.LAST_HISTORY_SYNC] ?: 0L,
        customTelegramApiId = prefs[Keys.CUSTOM_API_ID] ?: 0,
        customTelegramApiHash = prefs[Keys.CUSTOM_API_HASH] ?: "",
        isTelegramLoggedIn = prefs[Keys.TG_LOGGED_IN] ?: false,
        telegramUserId = prefs[Keys.TG_USER_ID] ?: 0L,
        telegramDisplayName = prefs[Keys.TG_DISPLAY_NAME] ?: "",
        telegramPhoneNumber = prefs[Keys.TG_PHONE_NUMBER] ?: "",
        lastSyncStatusMessage = prefs[Keys.LAST_SYNC_STATUS_MSG] ?: "",
      )
    }

  suspend fun getCurrentSettings(): AppSettings = settingsFlow.first()

  suspend fun setSelectedUploadBatchIds(ids: List<Long>) {
    val clean = ids.distinct().filter { it > 0L }
    context.telePhotosDataStore.edit { prefs ->
      if (clean.isEmpty()) {
        prefs.remove(Keys.SELECTED_UPLOAD_BATCH_IDS)
      } else {
        prefs[Keys.SELECTED_UPLOAD_BATCH_IDS] = clean.joinToString(",")
      }
    }
  }

  suspend fun getSelectedUploadBatchIds(): List<Long> {
    val raw = context.telePhotosDataStore.data.first()[Keys.SELECTED_UPLOAD_BATCH_IDS].orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw.split(',')
      .mapNotNull { it.trim().toLongOrNull() }
      .filter { it > 0L }
      .distinct()
  }

  suspend fun clearSelectedUploadBatchIds() {
    context.telePhotosDataStore.edit { prefs ->
      prefs.remove(Keys.SELECTED_UPLOAD_BATCH_IDS)
    }
  }

  suspend fun setActiveUploadBatchMode(mode: TelegramUploadMode) {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.ACTIVE_UPLOAD_BATCH_MODE] = mode.name
    }
  }

  suspend fun getActiveUploadBatchMode(): TelegramUploadMode {
    val raw = context.telePhotosDataStore.data.first()[Keys.ACTIVE_UPLOAD_BATCH_MODE]
    return TelegramUploadMode.fromWireName(raw)
  }

  suspend fun clearActiveUploadBatchMode() {
    context.telePhotosDataStore.edit { prefs ->
      prefs.remove(Keys.ACTIVE_UPLOAD_BATCH_MODE)
    }
  }

  suspend fun saveApiCredentials(apiId: Int, apiHash: String) {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.CUSTOM_API_ID] = apiId
      prefs[Keys.CUSTOM_API_HASH] = apiHash.trim()
    }
  }

  suspend fun clearApiCredentials() {
    context.telePhotosDataStore.edit { prefs ->
      prefs.remove(Keys.CUSTOM_API_ID)
      prefs.remove(Keys.CUSTOM_API_HASH)
    }
  }

  suspend fun saveTelegramSession(
    userId: Long,
    displayName: String,
    phoneNumber: String,
  ) {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.TG_LOGGED_IN] = true
      prefs[Keys.TG_USER_ID] = userId
      prefs[Keys.TG_DISPLAY_NAME] = displayName
      prefs[Keys.TG_PHONE_NUMBER] = phoneNumber
    }
  }

  suspend fun clearTelegramSession() {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.TG_LOGGED_IN] = false
      prefs.remove(Keys.TG_USER_ID)
      prefs.remove(Keys.TG_DISPLAY_NAME)
      prefs.remove(Keys.TG_PHONE_NUMBER)
      prefs.remove(Keys.LAST_SYNC_STATUS_MSG)
      prefs.remove(Keys.SELECTED_UPLOAD_BATCH_IDS)
      prefs.remove(Keys.ACTIVE_UPLOAD_BATCH_MODE)
    }
  }

  suspend fun setLastSyncStatusMessage(message: String) {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.LAST_SYNC_STATUS_MSG] = message
    }
  }

  suspend fun setThemeMode(mode: ThemeMode) {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.THEME_MODE] = mode.name
    }
  }

  suspend fun setAdvancedBlurEnabled(enabled: Boolean) {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.ADVANCED_BLUR] = enabled
    }
  }

  suspend fun setAppIconPreset(preset: AppIconPreset) {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.APP_ICON_PRESET] = preset.name
    }
  }

  suspend fun setWifiOnlyUpload(wifiOnly: Boolean) {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.WIFI_ONLY] = wifiOnly
    }
  }

  suspend fun setUploadPacingMs(pacingMs: Long) {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.UPLOAD_PACING_MS] = pacingMs.coerceIn(2000L, 10000L)
    }
  }

  suspend fun setPhotoGridColumns(columns: Int) {
    val clamped = clampPhotoGridColumns(columns)
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.PHOTO_GRID_COLUMNS] = clamped
    }
  }

  suspend fun setDefaultUploadAlbumId(albumId: Long) {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.DEFAULT_UPLOAD_ALBUM_ID] = albumId.coerceAtLeast(0L)
    }
  }

  suspend fun setFloodWaitUntil(
    untilEpochMs: Long,
    reason: String?,
    retryAfterSeconds: Int = 0,
  ) {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.FLOOD_WAIT_UNTIL] = untilEpochMs
      prefs[Keys.FLOOD_WAIT_RETRY_SEC] = retryAfterSeconds.coerceAtLeast(0)
      if (reason != null) {
        prefs[Keys.FLOOD_WAIT_REASON] = reason
      } else {
        prefs.remove(Keys.FLOOD_WAIT_REASON)
      }
    }
  }

  suspend fun clearFloodWait() {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.FLOOD_WAIT_UNTIL] = 0L
      prefs.remove(Keys.FLOOD_WAIT_RETRY_SEC)
      prefs.remove(Keys.FLOOD_WAIT_REASON)
    }
  }

  suspend fun updateLastHistorySync(epochMs: Long) {
    context.telePhotosDataStore.edit { prefs ->
      prefs[Keys.LAST_HISTORY_SYNC] = epochMs
    }
  }

  companion object {
    const val MIN_PHOTO_GRID_COLUMNS = 2
    const val MAX_PHOTO_GRID_COLUMNS = 5

    fun clampPhotoGridColumns(columns: Int): Int =
      columns.coerceIn(MIN_PHOTO_GRID_COLUMNS, MAX_PHOTO_GRID_COLUMNS)
  }
}
