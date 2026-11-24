package com.laughing.telephotos.ui.screens

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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
  trashedMedia: List<MediaEntity>,
  onBack: () -> Unit,
  onRestoreItem: (Long) -> Unit,
  onOpenMedia: (MediaEntity) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize()) {
    TopAppBar(
      title = { Text("Đã xoá gần đây", style = MaterialTheme.typography.titleLarge) },
      navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.testTag("trash_back_button")) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
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
          text = "Chế độ Chỉ đọc + Tải lên: Tệp xoá trong ứng dụng KHÔNG bao giờ bị xoá khỏi Telegram. Các mục được ghi nhận tại chủ đề «Đã xoá gần đây» và có thể khôi phục nguyên vẹn.",
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
            Text("Thùng rác đang trống", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "Khi bạn xoá tạm ảnh hoặc video từ tab Ảnh hoặc Video, chúng sẽ xuất hiện ở đây để bạn khôi phục khi cần.",
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
          TelePhotosGlassCard(
            onClick = { onOpenMedia(item) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                modifier = Modifier
                  .size(68.dp)
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
                  text = "Đã xoá lúc: ${
                    item.trashedAtEpochMs?.let { Formatters.formatFullDateTime(it) } ?: "Gần đây"
                  }",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = Formatters.formatFileSize(item.sizeBytes),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              Spacer(modifier = Modifier.width(8.dp))

              Button(
                onClick = { onRestoreItem(item.id) },
                modifier = Modifier.testTag("restore_button_${item.id}"),
              ) {
                Icon(Icons.Filled.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Khôi phục")
              }
            }
          }
        }
      }
    }
  }
}
