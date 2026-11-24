package com.laughing.telephotos.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.laughing.telephotos.data.local.AlbumWithCount
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.SyncTargetEntity
import com.laughing.telephotos.data.repository.ManualSyncProgressState
import com.laughing.telephotos.data.telegram.TelegramAuthState
import com.laughing.telephotos.ui.components.ChronologicalMediaGrid
import com.laughing.telephotos.ui.components.TelePhotosGlassCard
import com.laughing.telephotos.ui.viewmodel.TimelineSection

/**
 * "Video" tab: displays VIDEOS ONLY (mediaType == VIDEO, excluding trashed items).
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
  manualSyncProgress: ManualSyncProgressState,
  onMediaClick: (MediaEntity) -> Unit,
  onToggleSelection: (Long) -> Unit,
  onClearSelection: () -> Unit,
  onImportPickedVideos: (List<android.net.Uri>) -> Unit,
  onUploadSelectedItems: () -> Unit,
  onMoveSelectedToTrash: () -> Unit,
  onAssignSelectedToAlbum: (Long) -> Unit,
  onStopManualSync: () -> Unit,
  onSyncReadOnly: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isSelectionMode = selectedIds.isNotEmpty()
  val isLoggedIn = settings.isTelegramLoggedIn || authState is TelegramAuthState.Ready
  var showAlbumPickerDialog by remember { mutableStateOf(false) }

  if (isSelectionMode) {
    BackHandler { onClearSelection() }
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
              onClick = onUploadSelectedItems,
              modifier = Modifier.testTag("videos_upload_selected_button"),
            ) {
              Icon(Icons.Filled.CloudUpload, contentDescription = "Tải lên video đã chọn")
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
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "Video",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
              )
              Spacer(modifier = Modifier.width(8.dp))
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
              ) {
                Text(
                  text = "Chỉ tải & phát khi chạm",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
              }
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
                  onClick = onSyncReadOnly,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Text("Khôi phục video từ Telegram (Chỉ đọc)")
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
}
