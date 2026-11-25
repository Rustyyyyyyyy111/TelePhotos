package com.laughing.telephotos.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.laughing.telephotos.data.telegram.TelegramUploadMode
import com.laughing.telephotos.ui.theme.LocalTelePhotosTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncUploadModeBottomSheet(
  title: String,
  subtitle: String,
  onSelectMode: (TelegramUploadMode) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val tokens = LocalTelePhotosTokens.current

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = if (tokens.isGlass) {
      MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    } else {
      MaterialTheme.colorScheme.surface
    },
    modifier = Modifier.testTag("sync_mode_bottom_sheet"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 20.dp)
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      SyncModeOptionCard(
        icon = Icons.Filled.InsertDriveFile,
        title = "Gửi dạng Tệp (Send as File)",
        description = "Giữ nguyên tệp gốc (Document), không nén hay chuyển mã; tương thích hoàn toàn với trình xem tệp trực tuyến.",
        isPrimaryTint = true,
        onClick = { onSelectMode(TelegramUploadMode.AS_FILE) },
        modifier = Modifier.testTag("sync_option_send_as_file"),
      )

      SyncModeOptionCard(
        icon = Icons.Filled.HighQuality,
        title = "Gửi HD (Send HD)",
        description = "Tải lên dạng Ảnh/Video chất lượng cao (Media HD), không hạ độ phân giải thấp và hỗ trợ phát trực tiếp trên Telegram.",
        isPrimaryTint = false,
        onClick = { onSelectMode(TelegramUploadMode.HD_MEDIA) },
        modifier = Modifier.testTag("sync_option_send_hd"),
      )

      Spacer(modifier = Modifier.height(2.dp))

      OutlinedButton(
        onClick = onDismiss,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .minimumInteractiveComponentSize()
          .testTag("sync_option_cancel"),
      ) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text("Huỷ")
      }
    }
  }
}

@Composable
private fun SyncModeOptionCard(
  icon: ImageVector,
  title: String,
  description: String,
  isPrimaryTint: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val containerColor = if (isPrimaryTint) {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
  } else {
    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
  }
  val iconTint = if (isPrimaryTint) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.secondary
  }
  val borderColor = if (isPrimaryTint) {
    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
  } else {
    MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f)
  }

  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(16.dp),
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
    modifier = modifier
      .fillMaxWidth()
      .minimumInteractiveComponentSize(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = iconTint.copy(alpha = 0.14f),
        modifier = Modifier.size(44.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
          )
        }
      }
      Spacer(modifier = Modifier.width(14.dp))
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
