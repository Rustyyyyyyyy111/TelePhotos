package com.laughing.telephotos.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laughing.telephotos.R
import com.laughing.telephotos.core.AppSettings
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.MediaStoreScanner
import com.laughing.telephotos.data.local.AlbumWithCount
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.SyncTargetEntity
import com.laughing.telephotos.data.repository.ManualSyncProgressState
import com.laughing.telephotos.data.telegram.TelegramAuthState
import com.laughing.telephotos.data.telegram.TelegramConnectionStatus
import com.laughing.telephotos.ui.components.ChronologicalMediaGrid
import com.laughing.telephotos.ui.components.TelePhotosGlassCard
import com.laughing.telephotos.ui.theme.StatusUploadedGreen
import com.laughing.telephotos.ui.theme.StatusWarningAmber
import com.laughing.telephotos.ui.viewmodel.TimelineSection

/**
 * "Ảnh" tab: displays PHOTOS ONLY (unified local MediaStore + Telegram "Ảnh" Topic).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
  sections: List<TimelineSection>,
  selectedIds: Set<Long>,
  albums: List<AlbumWithCount>,
  lockedTarget: SyncTargetEntity?,
  settings: AppSettings,
  authState: TelegramAuthState,
  connectionStatus: TelegramConnectionStatus,
  floodWaitRemainingSec: Long,
  manualSyncProgress: ManualSyncProgressState,
  hasMediaPermission: Boolean,
  hasPromptedPermission: Boolean,
  onPermissionPrompted: () -> Unit,
  onDevicePermissionGranted: () -> Unit,
  onMediaClick: (MediaEntity) -> Unit,
  onToggleSelection: (Long) -> Unit,
  onClearSelection: () -> Unit,
  onImportPickedPhotos: (List<android.net.Uri>) -> Unit,
  onUploadSelectedItems: () -> Unit,
  onFreeUpSelectedSpace: () -> Unit,
  onMoveSelectedToTrash: () -> Unit,
  onAssignSelectedToAlbum: (Long) -> Unit,
  onStopManualSync: () -> Unit,
  onOpenTargetSelector: () -> Unit,
  onOpenTelegramAuth: () -> Unit,
  onSyncTab: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isSelectionMode = selectedIds.isNotEmpty()
  val isLoggedIn = settings.isTelegramLoggedIn || authState is TelegramAuthState.Ready
  var showAlbumPickerDialog by remember { mutableStateOf(false) }

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

  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(50),
    onResult = { uris ->
      if (uris.isNotEmpty()) {
        onImportPickedPhotos(uris)
      }
    },
  )

  Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      if (isSelectionMode) {
        TopAppBar(
          title = {
            Text(
              text = "Đã chọn ${selectedIds.size} ảnh",
              style = MaterialTheme.typography.titleMedium,
            )
          },
          navigationIcon = {
            IconButton(
              onClick = onClearSelection,
              modifier = Modifier.testTag("clear_selection_button"),
            ) {
              Icon(Icons.Filled.Close, contentDescription = "Bỏ chọn")
            }
          },
          actions = {
            IconButton(
              onClick = onUploadSelectedItems,
              modifier = Modifier.testTag("upload_selected_button"),
            ) {
              Icon(Icons.Filled.CloudUpload, contentDescription = "Tải lên mục đã chọn")
            }
            IconButton(
              onClick = onFreeUpSelectedSpace,
              modifier = Modifier.testTag("free_up_selected_button"),
            ) {
              Icon(Icons.Filled.CleaningServices, contentDescription = "Giải phóng dung lượng")
            }
            IconButton(
              onClick = { showAlbumPickerDialog = true },
              modifier = Modifier.testTag("assign_album_button"),
            ) {
              Icon(Icons.Filled.CreateNewFolder, contentDescription = "Chuyển vào Album")
            }
            IconButton(
              onClick = onMoveSelectedToTrash,
              modifier = Modifier.testTag("trash_selected_button"),
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
                text = "Tele Photos",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
              )
              Text(
                text = when (connectionStatus) {
                  TelegramConnectionStatus.CONNECTED_ACTIVE,
                  TelegramConnectionStatus.CONNECTING -> "Đang kết nối Telegram"
                  else -> "Thủ công • Đã ngắt kết nối"
                },
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
                  else -> onSyncTab()
                }
              },
              modifier = Modifier.testTag("timeline_sync_button"),
            ) {
              Icon(
                imageVector = if (manualSyncProgress.isRunning) Icons.Filled.Stop else Icons.Filled.Sync,
                contentDescription = if (manualSyncProgress.isRunning) "Dừng đồng bộ" else "Đồng bộ Ảnh",
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(if (manualSyncProgress.isRunning) "Dừng" else "Đồng bộ")
            }
            IconButton(
              onClick = onOpenTargetSelector,
              modifier = Modifier.testTag("timeline_target_button"),
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
                Text("Hiển thị ảnh trên thiết bị", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "Cấp quyền thư viện để gộp ảnh máy với ảnh trên Telegram",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            TextButton(
              onClick = { permissionLauncher.launch(MediaStoreScanner.requiredMediaPermissions()) },
              modifier = Modifier.testTag("timeline_grant_permission_button"),
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
                    "Kết nối Telegram để sao lưu ảnh gốc"
                  } else {
                    "Chưa khoá đích lưu trữ"
                  },
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = if (!isLoggedIn) {
                    "Chạm để đăng nhập TDLib hoặc quản lý ảnh ngoại tuyến trước"
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
              Image(
                painter = painterResource(id = R.drawable.img_empty_gallery),
                contentDescription = "Chưa có ảnh nào",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                  .fillMaxWidth()
                  .height(170.dp)
                  .clip(RoundedCornerShape(14.dp)),
              )
              Spacer(modifier = Modifier.height(16.dp))
              Text(
                text = "Chưa có ảnh nào",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = "Cấp quyền thư viện để hiển thị toàn bộ ảnh trên máy, hoặc chọn ảnh thêm thủ công. Chỉ tải lên dạng tệp gốc kèm ảnh thu nhỏ khi bạn bấm Đồng bộ.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
              Spacer(modifier = Modifier.height(18.dp))
              Button(
                onClick = {
                  photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                  )
                },
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("empty_pick_photos_button"),
              ) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chọn ảnh")
              }
              if (isLoggedIn && lockedTarget != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                  onClick = onSyncTab,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Text("Đồng bộ ảnh với Telegram")
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
          modifier = Modifier.weight(1f),
        )
      }
    }

    if (!isSelectionMode) {
      ExtendedFloatingActionButton(
        onClick = {
          photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
          )
        },
        icon = {
          Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Thêm ảnh")
        },
        text = { Text("Thêm ảnh") },
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(16.dp)
          .testTag("fab_add_photos"),
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
}

@Composable
internal fun ManualSyncProgressStrip(
  progress: ManualSyncProgressState,
  onStopManualSync: () -> Unit,
  modifier: Modifier = Modifier,
) {
  TelePhotosGlassCard(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 4.dp)
      .testTag("timeline_manual_sync_strip"),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
          val countSuffix = if (progress.totalCount > 0) {
            " (${progress.completedCount}/${progress.totalCount})"
          } else {
            ""
          }
          Text(
            text = if (progress.currentFileName.isNotBlank()) {
              "Đang tải lên$countSuffix: ${progress.currentFileName}"
            } else {
              "Đang kết nối Telegram để đồng bộ$countSuffix..."
            },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (progress.totalCount > 0) {
            Text(
              text = "Đang tải lên ${progress.completedCount}/${progress.totalCount} mục",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        TextButton(
          onClick = onStopManualSync,
          modifier = Modifier.testTag("timeline_stop_sync_button"),
        ) {
          Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Dừng")
        }
      }
      Spacer(modifier = Modifier.height(6.dp))
      if (progress.totalCount > 0) {
        LinearProgressIndicator(
          progress = { progress.fraction },
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }
    }
  }
}

@Composable
internal fun AlbumPickerDialog(
  albums: List<AlbumWithCount>,
  onDismiss: () -> Unit,
  onSelectAlbum: (Long) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Chuyển vào Album (Chủ đề)") },
    text = {
      if (albums.isEmpty()) {
        Text("Chưa có album nào. Hãy vào tab Album để tạo chủ đề mới trước.")
      } else {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(6.dp),
          contentPadding = PaddingValues(vertical = 4.dp),
        ) {
          items(albums, key = { it.id }) { album ->
            Surface(
              onClick = { onSelectAlbum(album.id) },
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(album.title, style = MaterialTheme.typography.titleSmall)
                Text(
                  "${album.itemCount} mục",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Đóng")
      }
    },
  )
}
