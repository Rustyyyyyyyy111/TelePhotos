package com.laughing.telephotos.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laughing.telephotos.core.AppSettings
import com.laughing.telephotos.core.CacheCleanupManager
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.ThemeMode
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.StorageStats
import com.laughing.telephotos.data.local.SyncTargetEntity
import com.laughing.telephotos.data.local.UploadStatus
import com.laughing.telephotos.data.repository.ManualSyncProgressState
import com.laughing.telephotos.data.telegram.TelegramAuthState
import com.laughing.telephotos.data.telegram.TelegramConnectionStatus
import com.laughing.telephotos.ui.components.TelePhotosGlassCard
import com.laughing.telephotos.ui.theme.StatusErrorRed
import com.laughing.telephotos.ui.theme.StatusUploadedGreen
import com.laughing.telephotos.ui.theme.StatusWarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStorageScreen(
  settings: AppSettings,
  authState: TelegramAuthState,
  connectionStatus: TelegramConnectionStatus,
  lockedTarget: SyncTargetEntity?,
  storageStats: StorageStats,
  uploadQueue: List<MediaEntity>,
  floodWaitRemainingSec: Long,
  manualSyncProgress: ManualSyncProgressState,
  cacheSizeBytes: Long,
  isBusy: Boolean,
  onSelectThemeMode: (ThemeMode) -> Unit,
  onToggleAdvancedBlur: (Boolean) -> Unit,
  onToggleWifiOnly: (Boolean) -> Unit,
  onOpenTelegramAuth: () -> Unit,
  onSwitchApiCredentials: () -> Unit,
  onOpenTargetSelector: () -> Unit,
  onSyncHistoryReadOnly: () -> Unit,
  onStartManualUploadBatch: () -> Unit,
  onStopManualSync: () -> Unit,
  onFreeUpAllSynced: () -> Unit,
  onClearCache: () -> Unit,
  onRefreshCacheSize: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val isLoggedIn = settings.isTelegramLoggedIn || authState is TelegramAuthState.Ready
  var showFreeUpAllDialog by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    onRefreshCacheSize()
  }

  Column(modifier = modifier.fillMaxSize()) {
    TopAppBar(
      title = { Text("Setting", style = MaterialTheme.typography.headlineMedium) },
      colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )

    LazyColumn(
      contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier
        .fillMaxSize()
        .testTag("sync_storage_list"),
    ) {
      // 0. Per-Action Short-Lived Telegram Connection Status Card
      item(key = "connection_lifecycle_card") {
        val isActive = connectionStatus == TelegramConnectionStatus.CONNECTED_ACTIVE ||
          connectionStatus == TelegramConnectionStatus.CONNECTING
        TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = if (isActive) Icons.Filled.CloudQueue else Icons.Filled.CloudOff,
              contentDescription = null,
              tint = if (isActive) StatusUploadedGreen else MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Text(
                text = connectionStatus.labelVi,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
              if (settings.lastSyncStatusMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = settings.lastSyncStatusMessage,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              } else {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = "Chế độ thủ công: Chỉ kết nối Telegram khi bạn bấm đồng bộ hoặc mở tệp từ đám mây.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }

      // 1. Live Manual Sync Progress Card (visible while running)
      if (manualSyncProgress.isRunning) {
        item(key = "live_manual_progress_card") {
          TelePhotosGlassCard(
            modifier = Modifier
              .fillMaxWidth()
              .testTag("sync_live_progress_card"),
          ) {
            Column(modifier = Modifier.padding(16.dp)) {
              Text("Đang đồng bộ thủ công", style = MaterialTheme.typography.titleMedium)
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = if (manualSyncProgress.currentFileName.isNotBlank()) {
                  "Đang tải: ${manualSyncProgress.currentFileName} (${manualSyncProgress.completedCount}/${manualSyncProgress.totalCount})"
                } else {
                  "Đang kết nối Telegram để bắt đầu lô tải lên..."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Spacer(modifier = Modifier.height(10.dp))
              if (manualSyncProgress.totalCount > 0) {
                LinearProgressIndicator(
                  progress = { manualSyncProgress.fraction },
                  modifier = Modifier.fillMaxWidth(),
                )
              } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              }
              Spacer(modifier = Modifier.height(12.dp))
              Button(
                onClick = onStopManualSync,
                colors = ButtonDefaults.buttonColors(
                  containerColor = StatusErrorRed,
                  contentColor = Color.White,
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("stop_manual_sync_button"),
              ) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Dừng đồng bộ")
              }
            }
          }
        }
      }

      // 2. FLOOD_WAIT Circuit Breaker Banner
      if (floodWaitRemainingSec > 0L) {
        item(key = "flood_wait_card") {
          TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
              modifier = Modifier.padding(16.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Filled.PauseCircleFilled,
                contentDescription = "Tạm dừng FLOOD_WAIT",
                tint = StatusWarningAmber,
                modifier = Modifier.size(26.dp),
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column {
                Text("Bộ ngắt mạch FLOOD_WAIT đang kích hoạt", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = "Đã ngắt kết nối Telegram để bảo vệ tài khoản. Còn lại ${
                    Formatters.formatRemainingSeconds(floodWaitRemainingSec)
                  } trước khi bạn có thể bấm tải lên lại.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }

      // 3. Telegram Account & Locked Target Card
      item(key = "telegram_account_card") {
        TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text("Tài khoản & Đích lưu trữ", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(10.dp))
            val accountSummary = when {
              authState is TelegramAuthState.Ready -> "Đã liên kết: ${authState.displayName}"
              settings.isTelegramLoggedIn -> "Đã liên kết: ${
                settings.telegramDisplayName.ifBlank { "Tài khoản Telegram" }
              }"
              authState is TelegramAuthState.WaitApiCredentials -> "Cần nhập API ID & API Hash để kết nối Telegram"
              authState is TelegramAuthState.Error -> authState.messageVi
              else -> "Chưa đăng nhập Telegram"
            }
            Text(
              text = accountSummary,
              style = MaterialTheme.typography.bodyMedium,
              color = if (isLoggedIn) StatusUploadedGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = if (lockedTarget != null) {
                "Đích đã khoá: ${lockedTarget.chatTitle} (${lockedTarget.targetType.labelVi})"
              } else {
                "Đích lưu trữ: Chưa khoá đích lưu trữ"
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(
                onClick = onOpenTelegramAuth,
                modifier = Modifier
                  .weight(1f)
                  .testTag("sync_open_auth_button"),
              ) {
                Text(if (isLoggedIn) "Tài khoản TG" else "Đăng nhập TG")
              }
              Button(
                onClick = onOpenTargetSelector,
                modifier = Modifier
                  .weight(1f)
                  .testTag("sync_open_target_button"),
              ) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Chọn đích")
              }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
              onClick = onSwitchApiCredentials,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("sync_switch_api_button"),
            ) {
              Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Đổi khoá API")
            }
          }
        }
      }

      // 4. Storage & Deduplication Metrics + Manual Sync Controls
      item(key = "storage_metrics_card") {
        TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(22.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text("Thống kê lưu trữ & Chống trùng lặp", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              StatTile("Tổng mục", "${storageStats.totalCount}")
              StatTile("Đã tải lên", "${storageStats.uploadedCount}")
              StatTile("Đang chờ", "${storageStats.queuedCount}")
              StatTile("Thùng rác", "${storageStats.trashedCount}")
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = "Đã đồng bộ lên Telegram (gốc): ${Formatters.formatFileSize(storageStats.uploadedBytes)} / ${
                Formatters.formatFileSize(storageStats.totalBytes)
              }",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(
                onClick = onSyncHistoryReadOnly,
                enabled = !isBusy && isLoggedIn && lockedTarget != null,
                modifier = Modifier
                  .weight(1f)
                  .testTag("sync_read_only_history_button"),
              ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Quét Chỉ đọc")
              }
              Button(
                onClick = if (manualSyncProgress.isRunning) onStopManualSync else onStartManualUploadBatch,
                enabled = manualSyncProgress.isRunning || (!isBusy && floodWaitRemainingSec == 0L && isLoggedIn && lockedTarget != null),
                modifier = Modifier
                  .weight(1f)
                  .testTag("run_upload_queue_button"),
              ) {
                Icon(
                  imageVector = if (manualSyncProgress.isRunning) Icons.Filled.Stop else Icons.Filled.CloudUpload,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (manualSyncProgress.isRunning) "Dừng đồng bộ" else "Tải lên ngay")
              }
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
              onClick = { showFreeUpAllDialog = true },
              enabled = !isBusy && storageStats.freeableCount > 0,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("free_up_all_synced_button"),
            ) {
              Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = if (storageStats.freeableCount > 0) {
                  "Giải phóng dung lượng máy (${storageStats.freeableCount} mục • ${
                    Formatters.formatFileSize(storageStats.freeableBytes)
                  })"
                } else {
                  "Không có tệp đã đồng bộ cần giải phóng"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }

      // 4b. Lightweight Cache Management (500 MB LRU cap)
      item(key = "cache_management_card") {
        val ratio = (cacheSizeBytes.toFloat() / CacheCleanupManager.MAX_ONLINE_CACHE_BYTES.toFloat())
          .coerceIn(0f, 1f)
        TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.Cached,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text("Bộ nhớ đệm xem trực tuyến", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "Đang dùng: ${Formatters.formatFileSize(cacheSizeBytes)} / 500 MB (tự động xoá tệp xem cũ nhất khi vượt ngưỡng)",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
              progress = { ratio },
              modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
              onClick = onClearCache,
              enabled = !isBusy,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("clear_cache_button"),
            ) {
              Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Xoá bộ nhớ đệm (${Formatters.formatFileSize(cacheSizeBytes)})")
            }
          }
        }
      }

      // 5. Theme Mode (Sáng / Tối / Liquid Glass) & Low-End Optimization
      item(key = "theme_settings_card") {
        TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text("Giao diện & Tối ưu máy yếu", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              ThemeMode.entries.forEach { mode ->
                FilterChip(
                  selected = settings.themeMode == mode,
                  onClick = { onSelectThemeMode(mode) },
                  label = { Text(mode.labelVi, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                  modifier = Modifier
                    .weight(1f)
                    .testTag("theme_chip_${mode.name}"),
                )
              }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = settings.themeMode.descriptionVi,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Hiệu ứng làm mờ nâng cao", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "Mặc định tắt trên thiết bị cấu hình thấp để giữ cuộn lưới mượt mà",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.advancedBlurEnabled,
                onCheckedChange = onToggleAdvancedBlur,
                enabled = settings.themeMode == ThemeMode.LIQUID_GLASS,
                modifier = Modifier.testTag("advanced_blur_switch"),
              )
            }
          }
        }
      }

      // 6. Anti-Ban & Wi-Fi Guard Rules
      item(key = "upload_rules_card") {
        TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(22.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text("Chính sách an toàn tài khoản", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
              ) {
                Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                  Text("Chỉ cho phép tải lên khi dùng Wi-Fi", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "WorkManager tuần tự 1 luồng theo yêu cầu, giãn nhịp 2.0–3.5 giây/tệp dưới dạng Document gốc rồi tự ngắt kết nối",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              Switch(
                checked = settings.wifiOnlyUpload,
                onCheckedChange = onToggleWifiOnly,
                modifier = Modifier.testTag("wifi_only_switch"),
              )
            }
          }
        }
      }

      // 7. Manual Upload Queue Items
      if (uploadQueue.isNotEmpty()) {
        item(key = "queue_header") {
          Text(
            text = "Hàng đợi chờ đồng bộ (${uploadQueue.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
        items(uploadQueue, key = { "q_${it.id}" }) { item ->
          TelePhotosGlassCard(
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                  text = item.fileName,
                  style = MaterialTheme.typography.titleSmall,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = item.lastError ?: "${Formatters.formatFileSize(item.sizeBytes)} • SHA-256 ${
                    item.contentHash.take(10)
                  }",
                  style = MaterialTheme.typography.bodySmall,
                  color = if (item.uploadStatus == UploadStatus.FAILED) StatusErrorRed else MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              Text(
                text = item.uploadStatus.labelVi,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }
      }
    }
  }

  if (showFreeUpAllDialog) {
    AlertDialog(
      onDismissRequest = { showFreeUpAllDialog = false },
      title = { Text("Giải phóng dung lượng máy?") },
      text = {
        Text(
          text = "Xoá ${storageStats.freeableCount} ảnh/video ĐÃ SAO LƯU (${Formatters.formatFileSize(storageStats.freeableBytes)}) khỏi bộ nhớ máy. Các mục chưa tải lên sẽ được giữ lại, và toàn bộ tệp trên Telegram vẫn nguyên vẹn để xem trực tuyến.",
          style = MaterialTheme.typography.bodyMedium,
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showFreeUpAllDialog = false
            onFreeUpAllSynced()
          },
          modifier = Modifier.testTag("confirm_free_up_all_button"),
        ) {
          Text("Tiếp tục")
        }
      },
      dismissButton = {
        TextButton(onClick = { showFreeUpAllDialog = false }) {
          Text("Huỷ")
        }
      },
    )
  }
}

@Composable
private fun StatTile(label: String, value: String) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(text = value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
