package com.laughing.telephotos.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laughing.telephotos.core.AppSettings
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.MediaStoreScanner
import com.laughing.telephotos.data.local.AlbumWithCount
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.SyncTargetEntity
import com.laughing.telephotos.data.repository.ManualSyncProgressState
import com.laughing.telephotos.data.telegram.TelegramAuthState
import com.laughing.telephotos.data.telegram.TelegramUploadMode
import com.laughing.telephotos.ui.components.ChronologicalMediaGrid
import com.laughing.telephotos.ui.components.SyncUploadModeBottomSheet
import com.laughing.telephotos.ui.components.TelePhotosGlassCard
import com.laughing.telephotos.ui.theme.StatusUploadedGreen
import com.laughing.telephotos.ui.theme.StatusWarningAmber
import com.laughing.telephotos.ui.viewmodel.TimelineSection

private enum class VideoSyncPromptAction {
  SELECTED_UPLOAD,
  TAB_SYNC,
}

/**
 * "Video" tab: displays VIDEOS ONLY (unified local MediaStore + Telegram "Video" Topic).
 * Shows static thumbnails + duration badges only; zero auto-play or auto-download on scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideosScreen(
  sections: List<TimelineSection>,
  selectedIds: Set<Long>,
  albums: List<AlbumWithCount>,
  lockedTarget: SyncTargetEntity?,
  settings: AppSettings,
  authState: TelegramAuthState,
  floodWaitRemainingSec: Long,
  manualSyncProgress: ManualSyncProgressState,
  hasMediaPermission: Boolean,
  hasPromptedPermission: Boolean,
  onPermissionPrompted: () -> Unit,
  onDevicePermissionGranted: () -> Unit,
  onMediaClick: (MediaEntity) -> Unit,
  onToggleSelection: (Long) -> Unit,
  onToggleDateGroup: (List<Long>) -> Unit,
  onClearSelection: () -> Unit,
  onImportPickedVideos: (List<android.net.Uri>) -> Unit,
  onUploadSelectedItems: (TelegramUploadMode) -> Unit,
  onFreeUpSelectedSpace: () -> Unit,
  onMoveSelectedToTrash: () -> Unit,
  onAssignSelectedToAlbum: (Long) -> Unit,
  onStopManualSync: () -> Unit,
  onOpenTargetSelector: () -> Unit,
  onOpenTelegramAuth: () -> Unit,
  onSyncTab: (TelegramUploadMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val isSelectionMode = selectedIds.isNotEmpty()
  val isLoggedIn = settings.isTelegramLoggedIn || authState is TelegramAuthState.Ready
  var showAlbumPickerDialog by remember { mutableStateOf(false) }
  var pendingSyncAction by remember { mutableStateOf<VideoSyncPromptAction?>(null) }

  if (isSelectionMode) {
    BackHandler { onClearSelection() }
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions(),
    onResult = { grants ->
      if (grants.values.any { it }) {
        onDevicePermissionGranted()
      }
    },
  )

  LaunchedEffect(hasMediaPermission) {
    if (hasMediaPermission) {
      onDevicePermissionGranted()
    } else if (!hasPromptedPermission) {
      onPermissionPrompted()
      permissionLauncher.launch(MediaStoreScanner.requiredMediaPermissions())
    }
  }

  val videoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(50),
    onResult = { uris ->
      if (uris.isNotEmpty()) {
        onImportPickedVideos(uris)
      }
    },
  )

  Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      if (isSelectionMode) {
        TopAppBar(
          title = {
            Text(
              text = "Đã chọn ${selectedIds.size} video",
              style = MaterialTheme.typography.titleMedium,
            )
          },
          navigationIcon = {
            IconButton(
              onClick = onClearSelection,
              modifier = Modifier.testTag("videos_clear_selection_button"),
            ) {
              Icon(Icons.Filled.Close, contentDescription = "Bỏ chọn")
            }
          },
          actions = {
            IconButton(
              onClick = { pendingSyncAction = VideoSyncPromptAction.SELECTED_UPLOAD },
              modifier = Modifier.testTag("videos_upload_selected_button"),
            ) {
              Icon(Icons.Filled.CloudUpload, contentDescription = "Tải lên video đã chọn")
            }
            IconButton(
              onClick = onFreeUpSelectedSpace,
              modifier = Modifier.testTag("videos_free_up_selected_button"),
            ) {
              Icon(Icons.Filled.CleaningServices, contentDescription = "Giải phóng dung lượng")
            }
            IconButton(
              onClick = { showAlbumPickerDialog = true },
              modifier = Modifier.testTag("videos_assign_album_button"),
            ) {
              Icon(Icons.Filled.CreateNewFolder, contentDescription = "Chuyển vào Album")
            }
            IconButton(
              onClick = onMoveSelectedToTrash,
              modifier = Modifier.testTag("videos_trash_selected_button"),
            ) {
              Icon(Icons.Filled.DeleteOutline, contentDescription = "Chuyển vào Đã xoá gần đây")
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
          ),
        )
      } else {
        TopAppBar(
          title = {
            Column {
              Text(
                text = "Video",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
              )
              Text(
                text = "Chỉ tải & phát khi chạm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          },
          actions = {
            TextButton(
              onClick = {
                when {
                  manualSyncProgress.isRunning -> onStopManualSync()
                  !isLoggedIn -> onOpenTelegramAuth()
                  lockedTarget == null -> onOpenTargetSelector()
                  else -> pendingSyncAction = VideoSyncPromptAction.TAB_SYNC
                }
              },
              modifier = Modifier.testTag("videos_sync_button"),
            ) {
              Icon(
                imageVector = if (manualSyncProgress.isRunning) Icons.Filled.Stop else Icons.Filled.Sync,
                contentDescription = if (manualSyncProgress.isRunning) "Dừng đồng bộ" else "Đồng bộ Video",
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(if (manualSyncProgress.isRunning) "Dừng" else "Đồng bộ")
            }
            IconButton(
              onClick = onOpenTargetSelector,
              modifier = Modifier.testTag("videos_target_button"),
            ) {
              Icon(
                imageVector = if (lockedTarget != null) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                contentDescription = "Đích lưu trữ Telegram",
                tint = if (lockedTarget != null) StatusUploadedGreen else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
      }

      if (manualSyncProgress.isRunning) {
        ManualSyncProgressStrip(
          progress = manualSyncProgress,
          onStopManualSync = onStopManualSync,
        )
      }

      if (!hasMediaPermission) {
        TelePhotosGlassCard(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.weight(1f).padding(end = 8.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.PermMedia,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text("Hiển thị video trên thiết bị", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "Cấp quyền thư viện để gộp video trên máy với chủ đề Video",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            TextButton(
              onClick = { permissionLauncher.launch(MediaStoreScanner.requiredMediaPermissions()) },
              modifier = Modifier.testTag("videos_grant_permission_button"),
            ) {
              Text("Cấp quyền")
            }
          }
        }
      }

      if (floodWaitRemainingSec > 0L) {
        TelePhotosGlassCard(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Filled.PauseCircleFilled,
              contentDescription = "Giới hạn tốc độ",
              tint = StatusWarningAmber,
              modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = "Telegram đang giới hạn tốc độ. Vui lòng đợi thêm ${
                Formatters.formatRemainingSeconds(floodWaitRemainingSec)
              } trước khi bấm đồng bộ lại.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      } else if (!isLoggedIn || lockedTarget == null) {
        TelePhotosGlassCard(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
          onClick = {
            if (!isLoggedIn) onOpenTelegramAuth() else onOpenTargetSelector()
          },
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.weight(1f),
            ) {
              Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text(
                  text = if (!isLoggedIn) {
                    "Kết nối Telegram để sao lưu video gốc"
                  } else {
                    "Chưa khoá đích lưu trữ"
                  },
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = if (!isLoggedIn) {
                    "Chạm để đăng nhập TDLib hoặc xem danh sách video trên máy"
                  } else {
                    "Chọn Nhóm Telegram, Kênh hoặc Tin nhắn đã lưu"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
            Text(
              text = "Thiết lập",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }

      if (sections.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          TelePhotosGlassCard(
            modifier = Modifier
              .widthIn(max = 460.dp)
              .fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(22.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Icon(
                imageVector = Icons.Filled.VideoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(46.dp),
              )
              Spacer(modifier = Modifier.height(14.dp))
              Text(
                text = "Chưa có video nào",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = "Lưới chỉ hiển thị ảnh thu nhỏ tĩnh kèm thời lượng, không tự động phát hay tải xuống khi cuộn để tiết kiệm RAM và dữ liệu di động.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
              Spacer(modifier = Modifier.height(18.dp))
              Button(
                onClick = {
                  videoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                  )
                },
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("empty_pick_videos_button"),
              ) {
                Icon(Icons.Filled.VideoCall, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chọn video")
              }
              if (isLoggedIn && lockedTarget != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                  onClick = { pendingSyncAction = VideoSyncPromptAction.TAB_SYNC },
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Text("Đồng bộ video với Telegram")
                }
              }
            }
          }
        }
      } else {
        ChronologicalMediaGrid(
          sections = sections,
          selectedIds = selectedIds,
          isSelectionMode = isSelectionMode,
          onItemClick = { media ->
            if (isSelectionMode) {
              onToggleSelection(media.id)
            } else {
              onMediaClick(media)
            }
          },
          onItemLongPress = { media ->
            onToggleSelection(media.id)
          },
          onToggleDateGroup = onToggleDateGroup,
          modifier = Modifier.weight(1f),
        )
      }
    }

    if (!isSelectionMode) {
      ExtendedFloatingActionButton(
        onClick = {
          videoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
          )
        },
        icon = {
          Icon(Icons.Filled.VideoCall, contentDescription = "Thêm video")
        },
        text = { Text("Thêm video") },
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(16.dp)
          .testTag("fab_add_videos"),
      )
    }
  }

  if (showAlbumPickerDialog) {
    AlbumPickerDialog(
      albums = albums,
      onDismiss = { showAlbumPickerDialog = false },
      onSelectAlbum = { albumId ->
        onAssignSelectedToAlbum(albumId)
        showAlbumPickerDialog = false
      },
    )
  }

  pendingSyncAction?.let { action ->
    val sheetTitle = when (action) {
      VideoSyncPromptAction.SELECTED_UPLOAD -> "Tải lên ${selectedIds.size} video đã chọn"
      VideoSyncPromptAction.TAB_SYNC -> "Đồng bộ Video lên Telegram"
    }
    val sheetSubtitle = when (action) {
      VideoSyncPromptAction.SELECTED_UPLOAD -> "Chọn định dạng tải lên chỉ dành cho các video đã chọn"
      VideoSyncPromptAction.TAB_SYNC -> "Chọn định dạng tải lên cho các video chưa sao lưu"
    }
    SyncUploadModeBottomSheet(
      title = sheetTitle,
      subtitle = sheetSubtitle,
      onSelectMode = { mode ->
        pendingSyncAction = null
        when (action) {
          VideoSyncPromptAction.SELECTED_UPLOAD -> onUploadSelectedItems(mode)
          VideoSyncPromptAction.TAB_SYNC -> onSyncTab(mode)
        }
      },
      onDismiss = { pendingSyncAction = null },
    )
  }
}
