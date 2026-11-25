package com.laughing.telephotos.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.ui.components.TelePhotosGlassCard
import com.laughing.telephotos.ui.theme.StatusErrorRed
import com.laughing.telephotos.ui.theme.StatusWarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
  trashedMedia: List<MediaEntity>,
  onBack: () -> Unit,
  onRestoreItem: (Long) -> Unit,
  onPermanentDeleteItem: (Long) -> Unit,
  onPermanentDeleteAll: () -> Unit,
  onOpenMedia: (MediaEntity) -> Unit,
  onPurgeExpired: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var showDeleteAllDialog by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    onPurgeExpired()
  }

  Column(modifier = modifier.fillMaxSize()) {
    TopAppBar(
      title = { Text("Đã xóa gần đây", style = MaterialTheme.typography.titleLarge) },
      navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.testTag("trash_back_button")) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
        }
      },
      actions = {
        if (trashedMedia.isNotEmpty()) {
          TextButton(
            onClick = { showDeleteAllDialog = true },
            modifier = Modifier.testTag("trash_delete_all_button"),
          ) {
            Text(
              text = "Xoá tất cả",
              color = StatusErrorRed,
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }
      },
      colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )

    TelePhotosGlassCard(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
      Row(
        modifier = Modifier.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Filled.Shield,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.secondary,
          modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
          text = "Lưu giữ tối đa 30 ngày trước khi tự động dọn khỏi thiết bị. Thao tác xoá chỉ xoá trong máy và KHÔNG xoá tệp gốc trên Telegram.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (trashedMedia.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
          Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Icon(
              imageVector = Icons.Filled.DeleteSweep,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text("Đã xóa gần đây đang trống", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "Ảnh hoặc video xoá từ Album, tab Ảnh hay Video sẽ nằm ở đây trong 30 ngày để bạn xem lại, khôi phục hoặc xoá khỏi thiết bị.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    } else {
      LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
          .fillMaxSize()
          .testTag("trash_list"),
      ) {
        items(trashedMedia, key = { it.id }) { item ->
          val remainingDays = Formatters.computeRemainingTrashDays(item.trashedAtEpochMs)
          val remainingLabel = Formatters.formatRemainingTrashDays(item.trashedAtEpochMs)

          TelePhotosGlassCard(
            onClick = { onOpenMedia(item) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Box(
                  modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp)),
                  contentAlignment = Alignment.Center,
                ) {
                  val preview = item.localUri ?: item.cachedThumbPath ?: item.cachedLocalVideoPath
                  if (preview != null) {
                    AsyncImage(
                      model = preview,
                      contentDescription = item.fileName,
                      contentScale = ContentScale.Crop,
                      modifier = Modifier.fillMaxSize(),
                    )
                  } else {
                    Surface(
                      color = MaterialTheme.colorScheme.surfaceVariant,
                      modifier = Modifier.fillMaxSize(),
                    ) {
                      Box(contentAlignment = Alignment.Center) {
                        Icon(
                          imageVector = Icons.Filled.Videocam,
                          contentDescription = item.fileName,
                          tint = MaterialTheme.colorScheme.primary,
                        )
                      }
                    }
                  }
                  if (item.mediaType == MediaType.VIDEO) {
                    Surface(
                      shape = RoundedCornerShape(5.dp),
                      color = Color.Black.copy(alpha = 0.7f),
                      modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                    ) {
                      Text(
                        text = Formatters.formatVideoDuration(item.durationSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                      )
                    }
                  }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Spacer(modifier = Modifier.height(3.dp))
                  Text(
                    text = "Đã xoá: ${
                      item.trashedAtEpochMs?.let { Formatters.formatFullDateTime(it) } ?: "Gần đây"
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Text(
                      text = Formatters.formatFileSize(item.sizeBytes),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = if (remainingDays <= 5) {
                        StatusWarningAmber.copy(alpha = 0.18f)
                      } else {
                        MaterialTheme.colorScheme.secondaryContainer
                      },
                    ) {
                      Text(
                        text = remainingLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (remainingDays <= 5) {
                          StatusWarningAmber
                        } else {
                          MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                      )
                    }
                  }
                }
              }

              Spacer(modifier = Modifier.height(10.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                OutlinedButton(
                  onClick = { onPermanentDeleteItem(item.id) },
                  border = BorderStroke(1.dp, StatusErrorRed.copy(alpha = 0.55f)),
                  modifier = Modifier
                    .weight(1f)
                    .testTag("permanent_delete_button_${item.id}"),
                ) {
                  Icon(
                    imageVector = Icons.Filled.DeleteForever,
                    contentDescription = null,
                    tint = StatusErrorRed,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = "Xoá vĩnh viễn",
                    color = StatusErrorRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }

                Button(
                  onClick = { onRestoreItem(item.id) },
                  modifier = Modifier
                    .weight(1f)
                    .testTag("restore_button_${item.id}"),
                ) {
                  Icon(
                    imageVector = Icons.Filled.RestoreFromTrash,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = "Khôi phục",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  if (showDeleteAllDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteAllDialog = false },
      title = { Text("Xoá tất cả khỏi thiết bị?") },
      text = {
        Text(
          text = "Toàn bộ ${trashedMedia.size} mục trong Đã xóa gần đây sẽ bị xoá vĩnh viễn khỏi ứng dụng và bộ nhớ đệm trên máy. Ảnh và video đã đồng bộ trên Telegram vẫn được giữ nguyên (Chế độ Chỉ đọc).",
          style = MaterialTheme.typography.bodyMedium,
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onPermanentDeleteAll()
            showDeleteAllDialog = false
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = StatusErrorRed,
            contentColor = Color.White,
          ),
          modifier = Modifier.testTag("confirm_delete_all_trash_button"),
        ) {
          Text("Xoá tất cả")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteAllDialog = false }) {
          Text("Huỷ")
        }
      },
    )
  }
}
