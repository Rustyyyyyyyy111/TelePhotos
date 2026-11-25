package com.laughing.telephotos.ui.viewmodel

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laughing.telephotos.TelePhotosApplication
import com.laughing.telephotos.core.AppSettings
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.LocalDeletePrepResult
import com.laughing.telephotos.core.NetworkInterruptionAlert
import com.laughing.telephotos.core.ThemeMode
import com.laughing.telephotos.data.local.AlbumEntity
import com.laughing.telephotos.data.local.AlbumWithCount
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.data.local.StorageStats
import com.laughing.telephotos.data.local.SyncTargetEntity
import com.laughing.telephotos.data.repository.ManualSyncProgressState
import com.laughing.telephotos.data.repository.SaveToDeviceProgress
import com.laughing.telephotos.data.telegram.FileDownloadProgress
import com.laughing.telephotos.data.telegram.FloodWaitException
import com.laughing.telephotos.data.telegram.FloodWaitState
import com.laughing.telephotos.data.telegram.TdLibLifecycleState
import com.laughing.telephotos.data.telegram.TelegramAuthState
import com.laughing.telephotos.data.telegram.TelegramCandidateTarget
import com.laughing.telephotos.data.telegram.TelegramConnectionStatus
import com.laughing.telephotos.data.telegram.TelegramPolicyGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TimelineSection(
  val headerTitle: String,
  val monthLabel: String,
  val items: List<MediaEntity>,
)

enum class SystemUriActionType {
  MOVE_TO_TRASH,
  FREE_UP_SPACE,
}

data class PendingSystemUriRequest(
  val actionType: SystemUriActionType,
  val mediaIds: List<Long>,
  val intentSender: IntentSender,
  val targetUris: Set<String>,
  val thumbOverrides: Map<Long, String>,
  val skippedUnsyncedCount: Int = 0,
)

class TelePhotosViewModel(application: Application) : AndroidViewModel(application) {

  private val app = application as TelePhotosApplication
  private val repo = app.vaultRepository
  private val prefs = app.settingsPreferences
  private val tdLib = app.tdLibManager

  val settings: StateFlow<AppSettings> = prefs.settingsFlow.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = AppSettings(),
  )

  val authState: StateFlow<TelegramAuthState> = repo.authState
  val connectionStatus: StateFlow<TelegramConnectionStatus> = repo.connectionStatus
  val lifecycleState: StateFlow<TdLibLifecycleState> = repo.lifecycleState
  val floodWaitState: StateFlow<FloodWaitState?> = repo.floodWaitState
  val networkInterruptionAlert: StateFlow<NetworkInterruptionAlert?> = repo.networkInterruptionAlert

  val lockedTarget: StateFlow<SyncTargetEntity?> = repo.lockedTargetFlow.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = null,
  )

  val photosTimelineSections: StateFlow<List<TimelineSection>> =
    repo.photosTimelineFlow.map { items -> groupMediaByDay(items) }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  val videosTimelineSections: StateFlow<List<TimelineSection>> =
    repo.videosTimelineFlow.map { items -> groupMediaByDay(items) }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  val albums: StateFlow<List<AlbumWithCount>> =
    repo.albumsWithCountFlow.map { list ->
      list.filterNot { TelegramPolicyGuard.isSystemAutoTopicTitle(it.title) || it.isTrashTopic }
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  val trashedMedia: StateFlow<List<MediaEntity>> = repo.trashedMediaFlow.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = emptyList(),
  )

  val uploadQueue: StateFlow<List<MediaEntity>> = repo.uploadQueueFlow.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = emptyList(),
  )

  val storageStats: StateFlow<StorageStats> = repo.storageStatsFlow.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = StorageStats(0, 0, 0, 0, 0, 0L, 0L, 0, 0L),
  )

  val manualSyncProgress: StateFlow<ManualSyncProgressState> =
    repo.manualSyncProgressFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = ManualSyncProgressState(),
    )

  private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
  val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

  private val _candidateTargets = MutableStateFlow<List<TelegramCandidateTarget>>(emptyList())
  val candidateTargets: StateFlow<List<TelegramCandidateTarget>> = _candidateTargets.asStateFlow()

  private val _isLoadingTargets = MutableStateFlow(false)
  val isLoadingTargets: StateFlow<Boolean> = _isLoadingTargets.asStateFlow()

  private val _isBusy = MutableStateFlow(false)
  val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

  private val _statusBanner = MutableStateFlow<String?>(null)
  val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

  private val _floodWaitRemainingSec = MutableStateFlow(0L)
  val floodWaitRemainingSec: StateFlow<Long> = _floodWaitRemainingSec.asStateFlow()

  private val _hasPromptedMediaPermission = MutableStateFlow(false)
  val hasPromptedMediaPermission: StateFlow<Boolean> = _hasPromptedMediaPermission.asStateFlow()

  private val _cacheSizeBytes = MutableStateFlow(0L)
  val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

  private val _pendingSystemUriRequest = MutableStateFlow<PendingSystemUriRequest?>(null)
  val pendingSystemUriRequest: StateFlow<PendingSystemUriRequest?> =
    _pendingSystemUriRequest.asStateFlow()

  init {
    viewModelScope.launch {
      repo.purgeExpiredTrashedMedia()
      repo.pruneSystemTopicAlbums()
      repo.enforceCacheLimit()
      _cacheSizeBytes.value = repo.calculateCacheSizeBytes()
    }
    viewModelScope.launch {
      combine(settings, floodWaitState) { appSettings, activeFlood ->
        maxOf(appSettings.floodWaitUntilEpochMs, activeFlood?.unlockAtEpochMs ?: 0L)
      }.collectLatest { until ->
        if (until > System.currentTimeMillis()) {
          while (true) {
            val rem = ((until - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(0L)
            _floodWaitRemainingSec.value = rem
            if (rem <= 0L) {
              tdLib.clearFloodWait()
              break
            }
            delay(1000L)
          }
        } else {
          _floodWaitRemainingSec.value = 0L
          if (settings.value.floodWaitUntilEpochMs > 0L || floodWaitState.value != null) {
            tdLib.clearFloodWait()
          }
        }
      }
    }
  }

  fun dismissBanner() {
    _statusBanner.value = null
  }

  fun showBanner(message: String) {
    _statusBanner.value = message
  }

  fun dismissNetworkInterruptionAlert() {
    repo.dismissNetworkInterruptionAlert()
  }

  fun markMediaPermissionPrompted() {
    _hasPromptedMediaPermission.value = true
  }

  fun purgeExpiredTrash() {
    viewModelScope.launch {
      repo.purgeExpiredTrashedMedia()
    }
  }

  fun refreshCacheSize() {
    viewModelScope.launch {
      repo.enforceCacheLimit()
      _cacheSizeBytes.value = repo.calculateCacheSizeBytes()
    }
  }

  fun clearTemporaryCache() {
    viewModelScope.launch {
      _isBusy.value = true
      repo.clearTemporaryCache()
        .onSuccess { freed ->
          _cacheSizeBytes.value = repo.calculateCacheSizeBytes()
          _statusBanner.value = if (freed > 0L) {
            "Đã xoá ${Formatters.formatFileSize(freed)} bộ nhớ đệm xem trực tuyến"
          } else {
            "Bộ nhớ đệm đã trống"
          }
        }
        .onFailure { err ->
          _statusBanner.value = err.message ?: "Không thể xoá bộ nhớ đệm"
        }
      _isBusy.value = false
    }
  }

  fun syncDevicePhotos() {
    viewModelScope.launch {
      repo.syncDeviceMediaStore(scanPhotos = true, scanVideos = false)
    }
  }

  fun syncDeviceVideos() {
    viewModelScope.launch {
      repo.syncDeviceMediaStore(scanPhotos = false, scanVideos = true)
    }
  }

  fun syncDeviceAllMedia() {
    viewModelScope.launch {
      repo.syncDeviceMediaStore(scanPhotos = true, scanVideos = true)
    }
  }

  fun toggleSelection(mediaId: Long) {
    val current = _selectedIds.value
    _selectedIds.value = if (mediaId in current) current - mediaId else current + mediaId
  }

  fun clearSelection() {
    _selectedIds.value = emptySet()
  }

  fun ensureTelegramInitialized() {
    viewModelScope.launch {
      tdLib.ensureInitializedOnDemand()
    }
  }

  fun closeTelegramIdle() {
    viewModelScope.launch {
      tdLib.closeConnectionIdle(preserveWaitApiState = false)
    }
  }

  fun saveApiCredentials(apiId: String, apiHash: String) {
    viewModelScope.launch {
      _isBusy.value = true
      tdLib.saveAndApplyApiCredentials(apiId, apiHash)
        .onFailure { err ->
          _statusBanner.value = err.message ?: "Thông số API TDLib không hợp lệ"
        }
      _isBusy.value = false
    }
  }

  fun switchApiCredentials() {
    viewModelScope.launch {
      tdLib.switchApiCredentials()
    }
  }

  fun returnToPhoneNumberStep() {
    tdLib.returnToPhoneNumberStep()
  }

  fun importSelectedMedia(uris: List<Uri>, targetAlbumId: Long? = null) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      _isBusy.value = true
      val summary = repo.importSelectedUris(uris, targetAlbumId)
      _isBusy.value = false
      val parts = buildList {
        if (summary.addedCount > 0) {
          add("Đã thêm ${summary.addedCount} mục ngoại tuyến (chờ bạn bấm Tải lên ngay)")
        }
        if (summary.duplicateSkippedCount > 0) {
          add("Bỏ qua ${summary.duplicateSkippedCount} mục trùng (Đã tải lên)")
        }
      }
      _statusBanner.value = parts.joinToString(" • ").ifBlank { "Không có mục mới nào được thêm" }
    }
  }

  fun uploadSelectedItemsNow() {
    val ids = _selectedIds.value.toList()
    if (ids.isEmpty()) return
    viewModelScope.launch {
      repo.startManualUploadBatch(selectedOnlyIds = ids)
        .onSuccess { count ->
          _selectedIds.value = emptySet()
          _statusBanner.value = "Đang tải lên $count mục đã chọn"
        }
        .onFailure { err ->
          _statusBanner.value = err.message ?: "Không thể bắt đầu tải lên"
        }
    }
  }

  fun startTabSync(mediaType: MediaType) {
    viewModelScope.launch {
      repo.syncDeviceMediaStore(
        scanPhotos = mediaType == MediaType.PHOTO,
        scanVideos = mediaType == MediaType.VIDEO,
      )
      repo.startManualUploadBatch(selectedOnlyIds = null, filterMediaType = mediaType)
        .onSuccess { count ->
          val label = if (mediaType == MediaType.VIDEO) "video" else "ảnh"
          _statusBanner.value = if (count > 0) {
            "Đang tải lên $count $label chưa sao lưu"
          } else {
            "Đang đối chiếu $label với chủ đề Telegram..."
          }
        }
        .onFailure { err ->
          _statusBanner.value = err.message ?: "Không thể đồng bộ"
        }
    }
  }

  fun startManualUploadBatch() {
    viewModelScope.launch {
      repo.syncDeviceMediaStore(scanPhotos = true, scanVideos = true)
      repo.startManualUploadBatch(selectedOnlyIds = null)
        .onSuccess { count ->
          _statusBanner.value = "Đang tải lên $count mục mới"
        }
        .onFailure { err ->
          _statusBanner.value = err.message ?: "Không thể bắt đầu tải lên"
        }
    }
  }

  fun stopManualSync() {
    viewModelScope.launch {
      repo.stopManualSync()
      _cacheSizeBytes.value = repo.calculateCacheSizeBytes()
      _statusBanner.value = "Đã dừng đồng bộ"
    }
  }

  fun moveSelectedToTrash() {
    val ids = _selectedIds.value.toList()
    if (ids.isEmpty()) return
    requestMoveToTrash(ids)
  }

  fun moveSingleToTrash(mediaId: Long) {
    requestMoveToTrash(listOf(mediaId))
  }

  fun moveMediaIdsToTrash(ids: List<Long>) {
    if (ids.isEmpty()) return
    requestMoveToTrash(ids)
  }

  private fun requestMoveToTrash(ids: List<Long>) {
    viewModelScope.launch {
      _isBusy.value = true
      val prep = repo.prepareSystemDelete(ids)
      _isBusy.value = false
      when (val result = prep.prepResult) {
        is LocalDeletePrepResult.NeedsSystemConfirmation -> {
          _pendingSystemUriRequest.value = PendingSystemUriRequest(
            actionType = SystemUriActionType.MOVE_TO_TRASH,
            mediaIds = prep.mediaIds,
            intentSender = result.intentSender,
            targetUris = result.targetUris,
            thumbOverrides = prep.thumbPathOverrides,
          )
        }
        is LocalDeletePrepResult.NoSystemPromptNeeded -> {
          repo.finalizeConfirmedDelete(
            mediaIds = prep.mediaIds,
            deletedLocalUris = result.deletedUris,
            thumbOverrides = prep.thumbPathOverrides,
          )
            .onSuccess { msg ->
              _selectedIds.value = _selectedIds.value - prep.mediaIds.toSet()
              _statusBanner.value = msg
            }
            .onFailure { err ->
              _statusBanner.value = err.message ?: "Không thể xoá mục đã chọn"
            }
        }
      }
    }
  }

  /**
   * "Giải phóng dung lượng máy" for selected items in Ảnh/Video multi-select mode.
   */
  fun requestFreeUpSpaceSelected() {
    val ids = _selectedIds.value.toList()
    if (ids.isEmpty()) return
    requestFreeUpSpaceInternal(ids)
  }

  /**
   * "Giải phóng dung lượng máy" for a single synced item inside MediaViewerScreen.
   */
  fun requestFreeUpSpaceSingle(mediaId: Long) {
    requestFreeUpSpaceInternal(listOf(mediaId))
  }

  /**
   * "Giải phóng dung lượng đã đồng bộ" bulk action in Setting.
   */
  fun requestFreeUpAllSynced() {
    requestFreeUpSpaceInternal(null)
  }

  private fun requestFreeUpSpaceInternal(mediaIds: List<Long>?) {
    viewModelScope.launch {
      _isBusy.value = true
      val prep = repo.prepareFreeUpSpace(mediaIds)
      _isBusy.value = false

      if (prep.eligibleMediaIds.isEmpty()) {
        _statusBanner.value = if (prep.skippedUnsyncedCount > 0) {
          "Chỉ có thể giải phóng mục đã sao lưu trên Telegram (tick xanh)"
        } else {
          "Không có tệp cục bộ đã đồng bộ nào cần giải phóng"
        }
        return@launch
      }

      when (val result = prep.prepResult) {
        is LocalDeletePrepResult.NeedsSystemConfirmation -> {
          _pendingSystemUriRequest.value = PendingSystemUriRequest(
            actionType = SystemUriActionType.FREE_UP_SPACE,
            mediaIds = prep.eligibleMediaIds,
            intentSender = result.intentSender,
            targetUris = result.targetUris,
            thumbOverrides = prep.thumbPathOverrides,
            skippedUnsyncedCount = prep.skippedUnsyncedCount,
          )
        }
        is LocalDeletePrepResult.NoSystemPromptNeeded -> {
          repo.finalizeFreeUpSpace(
            eligibleMediaIds = prep.eligibleMediaIds,
            deletedLocalUris = result.deletedUris,
            thumbOverrides = prep.thumbPathOverrides,
          )
            .onSuccess { freedCount ->
              _selectedIds.value = _selectedIds.value - prep.eligibleMediaIds.toSet()
              _cacheSizeBytes.value = repo.calculateCacheSizeBytes()
              _statusBanner.value = buildFreeUpSuccessBanner(freedCount, prep.skippedUnsyncedCount)
            }
            .onFailure { err ->
              _statusBanner.value = err.message ?: "Không thể giải phóng dung lượng máy"
            }
        }
      }
    }
  }

  fun completeSystemUriRequest(pending: PendingSystemUriRequest) {
    _pendingSystemUriRequest.value = null
    viewModelScope.launch {
      _isBusy.value = true
      when (pending.actionType) {
        SystemUriActionType.MOVE_TO_TRASH -> {
          repo.finalizeConfirmedDelete(
            mediaIds = pending.mediaIds,
            deletedLocalUris = pending.targetUris,
            thumbOverrides = pending.thumbOverrides,
          )
            .onSuccess { msg ->
              _selectedIds.value = _selectedIds.value - pending.mediaIds.toSet()
              _statusBanner.value = msg
            }
            .onFailure { err ->
              _statusBanner.value = err.message ?: "Không thể hoàn tất thao tác xoá"
            }
        }
        SystemUriActionType.FREE_UP_SPACE -> {
          repo.finalizeFreeUpSpace(
            eligibleMediaIds = pending.mediaIds,
            deletedLocalUris = pending.targetUris,
            thumbOverrides = pending.thumbOverrides,
          )
            .onSuccess { freedCount ->
              _selectedIds.value = _selectedIds.value - pending.mediaIds.toSet()
              _cacheSizeBytes.value = repo.calculateCacheSizeBytes()
              _statusBanner.value = buildFreeUpSuccessBanner(freedCount, pending.skippedUnsyncedCount)
            }
            .onFailure { err ->
              _statusBanner.value = err.message ?: "Không thể hoàn tất giải phóng dung lượng"
            }
        }
      }
      _isBusy.value = false
    }
  }

  fun cancelSystemUriRequest() {
    _pendingSystemUriRequest.value = null
    _statusBanner.value = "Đã huỷ thao tác xoá tệp trên máy"
  }

  private fun buildFreeUpSuccessBanner(freedCount: Int, skippedUnsyncedCount: Int): String {
    val base = if (freedCount > 0) {
      "Đã giải phóng $freedCount mục khỏi máy — vẫn xem trực tuyến trên Telegram"
    } else {
      "Không có bản cục bộ nào bị xoá"
    }
    return if (skippedUnsyncedCount > 0) {
      "$base (đã giữ lại $skippedUnsyncedCount mục chưa sao lưu)"
    } else {
      base
    }
  }

  fun restoreSingleFromTrash(mediaId: Long) {
    viewModelScope.launch {
      repo.restoreFromRecentlyDeleted(listOf(mediaId))
      _statusBanner.value = "Đã khôi phục về danh sách trực tuyến (không ép tải lại về máy)"
    }
  }

  fun permanentlyDeleteFromTrash(mediaId: Long) {
    viewModelScope.launch {
      repo.permanentlyDeleteLocalMedia(listOf(mediaId))
      _cacheSizeBytes.value = repo.calculateCacheSizeBytes()
      _statusBanner.value = "Đã xoá vĩnh viễn khỏi thiết bị (vẫn giữ trên Telegram)"
    }
  }

  fun permanentlyDeleteAllTrash() {
    viewModelScope.launch {
      val count = repo.permanentlyDeleteAllTrashed()
      _cacheSizeBytes.value = repo.calculateCacheSizeBytes()
      if (count > 0) {
        _statusBanner.value = "Đã xoá vĩnh viễn $count mục trên thiết bị (không xoá trên Telegram)"
      }
    }
  }

  fun assignSelectedToAlbum(albumId: Long) {
    val ids = _selectedIds.value.toList()
    if (ids.isEmpty()) return
    viewModelScope.launch {
      repo.assignMediaToAlbum(ids, albumId)
      _selectedIds.value = emptySet()
      _statusBanner.value = "Đã chuyển ${ids.size} mục vào album"
    }
  }

  fun createAlbum(title: String, iconColor: Int) {
    viewModelScope.launch {
      _isBusy.value = true
      repo.createAlbum(title, iconColor)
        .onSuccess { album ->
          _statusBanner.value = "Đã tạo album «${album.title}»"
        }
        .onFailure { err ->
          if (err is FloodWaitException) {
            repo.handleFloodWait(err)
            _statusBanner.value = "Telegram giới hạn tốc độ: ${err.message}"
          } else {
            _statusBanner.value = err.message ?: "Không thể tạo album"
          }
        }
      _isBusy.value = false
    }
  }

  fun refreshAllowedTargets() {
    viewModelScope.launch {
      _isLoadingTargets.value = true
      runCatching { repo.loadAllowedTargets() }
        .onSuccess { list -> _candidateTargets.value = list }
        .onFailure { err ->
          if (err is FloodWaitException) {
            repo.handleFloodWait(err)
          }
          _statusBanner.value = err.message ?: "Không thể tải danh sách đích lưu trữ"
        }
      _isLoadingTargets.value = false
    }
  }

  fun lockSyncTarget(candidate: TelegramCandidateTarget, onSuccess: () -> Unit) {
    viewModelScope.launch {
      _isBusy.value = true
      repo.lockSyncTarget(candidate)
        .onSuccess { target ->
          _statusBanner.value = "Đã khoá đích lưu trữ: ${target.chatTitle}"
          onSuccess()
        }
        .onFailure { err ->
          if (err is FloodWaitException) {
            repo.handleFloodWait(err)
          }
          _statusBanner.value = err.message ?: "Không thể khoá đích lưu trữ"
        }
      _isBusy.value = false
    }
  }

  fun syncHistoryReadOnly() {
    viewModelScope.launch {
      _isBusy.value = true
      repo.syncHistoryReadOnly()
        .onSuccess { count ->
          _cacheSizeBytes.value = repo.calculateCacheSizeBytes()
          _statusBanner.value = if (count > 0) {
            "Đã đồng bộ chỉ đọc $count mục mới từ Telegram"
          } else {
            "Thư viện đã khớp với Telegram"
          }
        }
        .onFailure { err ->
          if (err is FloodWaitException) {
            repo.handleFloodWait(err)
          }
          _statusBanner.value = err.message ?: "Không thể quét lịch sử Telegram"
        }
      _isBusy.value = false
    }
  }

  fun setThemeMode(mode: ThemeMode) {
    viewModelScope.launch { prefs.setThemeMode(mode) }
  }

  fun setAdvancedBlurEnabled(enabled: Boolean) {
    viewModelScope.launch { prefs.setAdvancedBlurEnabled(enabled) }
  }

  fun setWifiOnlyUpload(wifiOnly: Boolean) {
    viewModelScope.launch { prefs.setWifiOnlyUpload(wifiOnly) }
  }

  fun submitPhoneNumber(phone: String) {
    viewModelScope.launch {
      _isBusy.value = true
      tdLib.sendPhoneNumber(phone)
        .onFailure { _statusBanner.value = it.message ?: "Số điện thoại không hợp lệ" }
      _isBusy.value = false
    }
  }

  fun submitAuthCode(code: String) {
    viewModelScope.launch {
      _isBusy.value = true
      tdLib.sendAuthCode(code)
        .onFailure { _statusBanner.value = it.message ?: "Mã xác nhận OTP không chính xác" }
      _isBusy.value = false
    }
  }

  fun submitPassword(password: String) {
    viewModelScope.launch {
      _isBusy.value = true
      tdLib.sendPassword(password)
        .onFailure { _statusBanner.value = it.message ?: "Mật khẩu xác minh 2 bước không chính xác" }
      _isBusy.value = false
    }
  }

  fun logoutTelegram() {
    viewModelScope.launch {
      repo.stopManualSync()
      tdLib.logOut()
      _statusBanner.value = "Đã đăng xuất phiên Telegram khỏi thiết bị"
    }
  }

  fun observeAlbum(albumId: Long): Flow<AlbumEntity?> = repo.observeAlbum(albumId)

  fun observeAlbumPhotos(albumId: Long): Flow<List<MediaEntity>> = repo.observeAlbumPhotos(albumId)

  fun observeAlbumVideos(albumId: Long): Flow<List<MediaEntity>> = repo.observeAlbumVideos(albumId)

  fun observeMediaById(mediaId: Long): Flow<MediaEntity?> = repo.observeMediaById(mediaId)

  fun downloadVideoOnDemand(fileId: Int): Flow<FileDownloadProgress> =
    repo.downloadMediaOnDemand(fileId)

  fun saveDownloadedVideoPath(mediaId: Long, path: String) {
    viewModelScope.launch {
      repo.saveDownloadedVideoPath(mediaId, path)
      _cacheSizeBytes.value = repo.calculateCacheSizeBytes()
    }
  }

  fun saveMediaToDeviceGallery(media: MediaEntity): Flow<SaveToDeviceProgress> =
    repo.saveMediaToDeviceGallery(media)

  private fun groupMediaByDay(items: List<MediaEntity>): List<TimelineSection> {
    if (items.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val grouped = LinkedHashMap<String, MutableList<MediaEntity>>()
    val monthByHeader = mutableMapOf<String, String>()
    for (item in items) {
      val header = Formatters.formatTimelineDayHeader(item.takenAtEpochMs, now)
      grouped.getOrPut(header) { mutableListOf() }.add(item)
      if (!monthByHeader.containsKey(header)) {
        monthByHeader[header] = Formatters.formatMonthYearLabel(item.takenAtEpochMs)
      }
    }
    return grouped.map { (header, list) ->
      TimelineSection(
        headerTitle = header,
        monthLabel = monthByHeader[header] ?: header,
        items = list,
      )
    }
  }
}
