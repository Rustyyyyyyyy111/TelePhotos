package com.laughing.telephotos.ui.viewmodel

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laughing.telephotos.TelePhotosApplication
import com.laughing.telephotos.core.AppIconManager
import com.laughing.telephotos.core.AppIconPreset
import com.laughing.telephotos.core.AppSettings
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.LocalDeletePrepResult
import com.laughing.telephotos.core.NetworkInterruptionAlert
import com.laughing.telephotos.core.ThemeMode
import com.laughing.telephotos.data.local.AlbumEntity
import com.laughing.telephotos.data.local.AlbumSyncStatus
import com.laughing.telephotos.data.local.AlbumWithCount
import com.laughing.telephotos.data.local.BackfillState
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.data.local.StorageStats
import com.laughing.telephotos.data.local.SyncTargetEntity
import com.laughing.telephotos.data.repository.ManualSyncProgressState
import com.laughing.telephotos.data.repository.SaveToDeviceProgress
import com.laughing.telephotos.data.repository.TopicSyncKey
import com.laughing.telephotos.data.repository.TopicSyncUiState
import com.laughing.telephotos.data.telegram.FileDownloadProgress
import com.laughing.telephotos.data.telegram.FloodWaitException
import com.laughing.telephotos.data.telegram.FloodWaitState
import com.laughing.telephotos.data.telegram.TdLibLifecycleState
import com.laughing.telephotos.data.telegram.TelegramAuthState
import com.laughing.telephotos.data.telegram.TelegramCandidateTarget
import com.laughing.telephotos.data.telegram.TelegramConnectionStatus
import com.laughing.telephotos.data.telegram.TelegramPolicyGuard
import com.laughing.telephotos.data.telegram.TelegramUploadMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
enum class MediaCollectionScope {
  PHOTOS,
  VIDEOS,
  ALBUM_PHOTOS,
  ALBUM_VIDEOS,
  TRASH,
}

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
  val selectionScopeType: MediaType? = null,
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
  val topicSyncStates: StateFlow<Map<TopicSyncKey, TopicSyncUiState>> = repo.topicSyncStates

  val lockedTarget: StateFlow<SyncTargetEntity?> = repo.lockedTargetFlow.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = null,
  )

  val photosTimelineSections: StateFlow<List<TimelineSection>> =
    repo.photosTimelineFlow
      .map { items -> groupMediaByDay(items) }
      .flowOn(Dispatchers.Default)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val videosTimelineSections: StateFlow<List<TimelineSection>> =
    repo.videosTimelineFlow
      .map { items -> groupMediaByDay(items) }
      .flowOn(Dispatchers.Default)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val albums: StateFlow<List<AlbumWithCount>> =
    repo.albumsWithCountFlow
      .map { list ->
        list.filterNot { it.isTrashTopic || TelegramPolicyGuard.isTrashTopicTitle(it.title) }
      }
      .flowOn(Dispatchers.Default)
      .stateIn(
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

  private val _selectedPhotoIds = MutableStateFlow<Set<Long>>(emptySet())
  val selectedPhotoIds: StateFlow<Set<Long>> = _selectedPhotoIds.asStateFlow()

  private val _selectedVideoIds = MutableStateFlow<Set<Long>>(emptySet())
  val selectedVideoIds: StateFlow<Set<Long>> = _selectedVideoIds.asStateFlow()

  private fun selectionFlowFor(mediaType: MediaType): MutableStateFlow<Set<Long>> =
    when (mediaType) {
      MediaType.PHOTO -> _selectedPhotoIds
      MediaType.VIDEO -> _selectedVideoIds
    }

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
    viewModelScope.launch(Dispatchers.IO) {
      repo.resetStaleTopicSyncStatuses()
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

  fun ensureSessionPhotosScanned() {
    viewModelScope.launch(Dispatchers.IO) {
      repo.syncDeviceMediaStoreIfNeededForSession(MediaType.PHOTO)
    }
  }

  fun ensureSessionVideosScanned() {
    viewModelScope.launch(Dispatchers.IO) {
      repo.syncDeviceMediaStoreIfNeededForSession(MediaType.VIDEO)
    }
  }

  fun syncDevicePhotos() {
    viewModelScope.launch(Dispatchers.IO) {
      repo.syncDeviceMediaStore(scanPhotos = true, scanVideos = false, force = true)
    }
  }

  fun syncDeviceVideos() {
    viewModelScope.launch(Dispatchers.IO) {
      repo.syncDeviceMediaStore(scanPhotos = false, scanVideos = true, force = true)
    }
  }

  fun syncDeviceAllMedia() {
    viewModelScope.launch(Dispatchers.IO) {
      repo.syncDeviceMediaStore(scanPhotos = true, scanVideos = true, force = true)
    }
  }

  fun toggleSelection(mediaType: MediaType, mediaId: Long) {
    val flow = selectionFlowFor(mediaType)
    val current = flow.value
    flow.value = if (mediaId in current) current - mediaId else current + mediaId
  }

  fun toggleDateGroupSelection(mediaType: MediaType, itemIds: List<Long>) {
    if (itemIds.isEmpty()) return
    val flow = selectionFlowFor(mediaType)
    val current = flow.value
    val allSelected = itemIds.all { it in current }
    flow.value = if (allSelected) {
      current - itemIds.toSet()
    } else {
      current + itemIds
    }
  }

  fun clearSelection(mediaType: MediaType) {
    selectionFlowFor(mediaType).value = emptySet()
  }

  fun clearAllSelections() {
    _selectedPhotoIds.value = emptySet()
    _selectedVideoIds.value = emptySet()
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

  fun uploadSelectedItemsNow(
    mediaType: MediaType,
    uploadMode: TelegramUploadMode = TelegramUploadMode.AS_FILE,
  ) {
    val flow = selectionFlowFor(mediaType)
    val ids = flow.value.toList()
    if (ids.isEmpty()) {
      _statusBanner.value = "Vui lòng chọn ít nhất 1 mục để tải lên"
      return
    }
    viewModelScope.launch {
      repo.startManualUploadBatch(
        selectedOnlyIds = ids,
        filterMediaType = mediaType,
        uploadMode = uploadMode,
      )
        .onSuccess { count ->
          flow.value = emptySet()
          val label = if (mediaType == MediaType.VIDEO) "video" else "ảnh"
          _statusBanner.value = "Đang tải lên $count $label đã chọn • ${uploadMode.labelVi}"
        }
        .onFailure { err ->
          _statusBanner.value = err.message ?: "Không thể bắt đầu tải lên"
        }
    }
  }

  fun startTabSync(
    mediaType: MediaType,
    uploadMode: TelegramUploadMode = TelegramUploadMode.AS_FILE,
  ) {
    viewModelScope.launch {
      repo.syncDeviceMediaStore(
        scanPhotos = mediaType == MediaType.PHOTO,
        scanVideos = mediaType == MediaType.VIDEO,
      )
      repo.startManualUploadBatch(
        selectedOnlyIds = null,
        filterMediaType = mediaType,
        uploadMode = uploadMode,
      )
        .onSuccess { count ->
          val label = if (mediaType == MediaType.VIDEO) "video" else "ảnh"
          _statusBanner.value = if (count > 0) {
            "Đang tải lên $count $label chưa sao lưu • ${uploadMode.labelVi}"
          } else {
            "Đang đối chiếu $label với chủ đề Telegram..."
          }
        }
        .onFailure { err ->
          _statusBanner.value = err.message ?: "Không thể đồng bộ"
        }
    }
  }

  fun startManualUploadBatch(
    uploadMode: TelegramUploadMode = TelegramUploadMode.AS_FILE,
  ) {
    viewModelScope.launch {
      repo.syncDeviceMediaStore(scanPhotos = true, scanVideos = true)
      repo.startManualUploadBatch(selectedOnlyIds = null, uploadMode = uploadMode)
        .onSuccess { count ->
          _statusBanner.value = "Đang tải lên $count mục mới • ${uploadMode.labelVi}"
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

  fun moveSelectedToTrash(mediaType: MediaType) {
    val ids = selectionFlowFor(mediaType).value.toList()
    if (ids.isEmpty()) return
    requestMoveToTrash(ids, selectionScopeType = mediaType)
  }

  fun moveSingleToTrash(mediaId: Long) {
    requestMoveToTrash(listOf(mediaId), selectionScopeType = null)
  }

  fun moveMediaIdsToTrash(ids: List<Long>) {
    if (ids.isEmpty()) return
    requestMoveToTrash(ids, selectionScopeType = null)
  }

  private fun requestMoveToTrash(
    ids: List<Long>,
    selectionScopeType: MediaType?,
  ) {
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
            selectionScopeType = selectionScopeType,
          )
        }
        is LocalDeletePrepResult.NoSystemPromptNeeded -> {
          repo.finalizeConfirmedDelete(
            mediaIds = prep.mediaIds,
            deletedLocalUris = result.deletedUris,
            thumbOverrides = prep.thumbPathOverrides,
          )
            .onSuccess { msg ->
              removeIdsFromSelection(prep.mediaIds.toSet(), selectionScopeType)
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
  fun requestFreeUpSpaceSelected(mediaType: MediaType) {
    val ids = selectionFlowFor(mediaType).value.toList()
    if (ids.isEmpty()) return
    requestFreeUpSpaceInternal(ids, selectionScopeType = mediaType)
  }

  /**
   * "Giải phóng dung lượng máy" for a single synced item inside MediaViewerScreen.
   */
  fun requestFreeUpSpaceSingle(mediaId: Long) {
    requestFreeUpSpaceInternal(listOf(mediaId), selectionScopeType = null)
  }

  /**
   * "Giải phóng dung lượng đã đồng bộ" bulk action in Setting.
   */
  fun requestFreeUpAllSynced() {
    requestFreeUpSpaceInternal(null, selectionScopeType = null)
  }

  private fun requestFreeUpSpaceInternal(
    mediaIds: List<Long>?,
    selectionScopeType: MediaType?,
  ) {
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
            selectionScopeType = selectionScopeType,
          )
        }
        is LocalDeletePrepResult.NoSystemPromptNeeded -> {
          repo.finalizeFreeUpSpace(
            eligibleMediaIds = prep.eligibleMediaIds,
            deletedLocalUris = result.deletedUris,
            thumbOverrides = prep.thumbPathOverrides,
          )
            .onSuccess { freedCount ->
              removeIdsFromSelection(prep.eligibleMediaIds.toSet(), selectionScopeType)
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
              removeIdsFromSelection(pending.mediaIds.toSet(), pending.selectionScopeType)
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
              removeIdsFromSelection(pending.mediaIds.toSet(), pending.selectionScopeType)
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

  private fun removeIdsFromSelection(idsToRemove: Set<Long>, selectionScopeType: MediaType?) {
    if (selectionScopeType != null) {
      val flow = selectionFlowFor(selectionScopeType)
      flow.value = flow.value - idsToRemove
    } else {
      _selectedPhotoIds.value = _selectedPhotoIds.value - idsToRemove
      _selectedVideoIds.value = _selectedVideoIds.value - idsToRemove
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

  fun assignSelectedToAlbum(mediaType: MediaType, albumId: Long) {
    val flow = selectionFlowFor(mediaType)
    val ids = flow.value.toList()
    if (ids.isEmpty()) return
    viewModelScope.launch {
      repo.assignMediaToAlbum(ids, albumId)
      flow.value = emptySet()
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
      withContext(Dispatchers.IO) { repo.syncHistoryReadOnly() }
        .onSuccess { count ->
          _cacheSizeBytes.value = withContext(Dispatchers.IO) { repo.calculateCacheSizeBytes() }
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

  fun syncAlbumsFromTelegram(specificAlbumId: Long? = null, silent: Boolean = false) {
    viewModelScope.launch {
      if (!silent) _isBusy.value = true
      withContext(Dispatchers.IO) { repo.syncAlbumsFromTelegram(specificAlbumId) }
        .onSuccess { count ->
          if (!silent) {
            val bridgePending = withContext(Dispatchers.IO) {
              repo.hasPendingForwardBridge(specificAlbumId)
            }
            _statusBanner.value = when {
              bridgePending && count > 0 ->
                "Đã tải $count mục mới — vẫn còn tin nhắn chưa đồng bộ, nhấn Làm mới để tiếp tục"
              bridgePending ->
                "Vẫn còn tin nhắn mới chưa đồng bộ hết — nhấn Làm mới để tiếp tục"
              count > 0 ->
                "Đã cập nhật $count mục từ Telegram vào Album"
              else ->
                "Album đã đồng bộ mới nhất với Telegram"
            }
          }
        }
        .onFailure { err ->
          if (err is FloodWaitException) {
            repo.handleFloodWait(err)
          }
          if (!silent) {
            _statusBanner.value = err.message ?: "Không thể tải ảnh từ Telegram"
          }
        }
      if (!silent) _isBusy.value = false
    }
  }

  fun backfillOlderAlbumHistory(albumId: Long) {
    viewModelScope.launch {
      _isBusy.value = true
      withContext(Dispatchers.IO) { repo.backfillOlderAlbumHistory(albumId) }
        .onSuccess { res ->
          _statusBanner.value = when {
            res.addedCount > 0 -> "Đã tải thêm ${res.addedCount} mục cũ từ Telegram"
            res.backfillState == BackfillState.CONFIRMED_END -> "Đã tải hết lịch sử của chủ đề này"
            res.backfillState == BackfillState.LIKELY_END_RETRYABLE ->
              "Chưa thấy thêm tin cũ hơn — chạm để kiểm tra lại"
            else -> "Không có thêm ảnh hoặc video cũ hơn"
          }
        }
        .onFailure { err ->
          if (err is FloodWaitException) {
            repo.handleFloodWait(err)
          }
          _statusBanner.value = err.message ?: "Không thể tải thêm lịch sử cũ"
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

  fun setAppIconPreset(preset: AppIconPreset) {
    viewModelScope.launch {
      runCatching { AppIconManager.applyIconPreset(app, preset) }
        .onSuccess {
          prefs.setAppIconPreset(preset)
          _statusBanner.value = "Đã đổi biểu tượng ứng dụng sang «${preset.labelVi}»"
        }
        .onFailure {
          _statusBanner.value = "Không thể thay đổi biểu tượng trên trình khởi chạy này"
        }
    }
  }

  fun setWifiOnlyUpload(wifiOnly: Boolean) {
    viewModelScope.launch { prefs.setWifiOnlyUpload(wifiOnly) }
  }

  fun setPhotoGridColumns(columns: Int) {
    viewModelScope.launch { prefs.setPhotoGridColumns(columns) }
  }

  fun setDefaultUploadAlbumId(albumId: Long) {
    viewModelScope.launch { prefs.setDefaultUploadAlbumId(albumId) }
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

  fun observeMediaCollection(
    scope: MediaCollectionScope,
    albumId: Long,
    fallbackMediaId: Long,
  ): Flow<List<MediaEntity>> {
    val sourceFlow = when (scope) {
      MediaCollectionScope.PHOTOS -> repo.photosTimelineFlow
      MediaCollectionScope.VIDEOS -> repo.videosTimelineFlow
      MediaCollectionScope.ALBUM_PHOTOS -> {
        if (albumId > 0L) repo.observeAlbumPhotos(albumId) else repo.photosTimelineFlow
      }
      MediaCollectionScope.ALBUM_VIDEOS -> {
        if (albumId > 0L) repo.observeAlbumVideos(albumId) else repo.videosTimelineFlow
      }
      MediaCollectionScope.TRASH -> repo.trashedMediaFlow
    }
    return combine(sourceFlow, repo.observeMediaById(fallbackMediaId)) { list, fallback ->
      when {
        list.isNotEmpty() -> list
        fallback != null && ((scope == MediaCollectionScope.TRASH) == fallback.isTrashed) ->
          listOf(fallback)
        else -> emptyList()
      }
    }
  }

  fun downloadMediaOnDemand(media: MediaEntity): Flow<FileDownloadProgress> =
    repo.downloadMediaOnDemand(media)

  fun downloadVideoOnDemand(fileId: Int): Flow<FileDownloadProgress> =
    repo.downloadMediaOnDemand(fileId)

  fun clearStaleCachedVideoPath(mediaId: Long) {
    viewModelScope.launch {
      repo.clearStaleCachedVideoPath(mediaId)
    }
  }

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
    val viLocale = Locale.forLanguageTag("vi-VN")
    val calNow = Calendar.getInstance()
    val nowYear = calNow.get(Calendar.YEAR)
    val nowDay = calNow.get(Calendar.DAY_OF_YEAR)
    val calTarget = Calendar.getInstance()
    val sameYearFmt = SimpleDateFormat("EEE, d 'thg' M", viLocale)
    val otherYearFmt = SimpleDateFormat("d 'thg' M, yyyy", viLocale)
    val monthFmt = SimpleDateFormat("'Tháng' M, yyyy", viLocale)
    val dateReuse = Date()

    val grouped = LinkedHashMap<String, MutableList<MediaEntity>>()
    val monthByHeader = HashMap<String, String>()

    for (item in items) {
      calTarget.timeInMillis = item.takenAtEpochMs
      val targetYear = calTarget.get(Calendar.YEAR)
      val targetDay = calTarget.get(Calendar.DAY_OF_YEAR)
      dateReuse.time = item.takenAtEpochMs

      val header = when {
        targetYear == nowYear && targetDay == nowDay -> "Hôm nay"
        targetYear == nowYear && targetDay == nowDay - 1 -> "Hôm qua"
        targetYear == nowYear ->
          sameYearFmt.format(dateReuse).replaceFirstChar { it.titlecase(viLocale) }
        else ->
          otherYearFmt.format(dateReuse)
      }

      grouped.getOrPut(header) { mutableListOf() }.add(item)
      if (!monthByHeader.containsKey(header)) {
        monthByHeader[header] = monthFmt.format(dateReuse)
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
