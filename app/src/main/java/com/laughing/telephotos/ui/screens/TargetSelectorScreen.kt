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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.laughing.telephotos.core.AppSettings
import com.laughing.telephotos.data.local.SyncTargetEntity
import com.laughing.telephotos.data.local.SyncTargetType
import com.laughing.telephotos.data.telegram.TelegramAuthState
import com.laughing.telephotos.data.telegram.TelegramCandidateTarget
import com.laughing.telephotos.data.telegram.TelegramPolicyGuard
import com.laughing.telephotos.ui.components.TelePhotosGlassCard
import com.laughing.telephotos.ui.theme.StatusUploadedGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetSelectorScreen(
  settings: AppSettings,
  authState: TelegramAuthState,
  lockedTarget: SyncTargetEntity?,
  candidates: List<TelegramCandidateTarget>,
  isLoading: Boolean,
  onBack: () -> Unit,
  onRefreshTargets: () -> Unit,
  onConfirmLockTarget: (TelegramCandidateTarget) -> Unit,
  onOpenTelegramAuth: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isLoggedIn = settings.isTelegramLoggedIn || authState is TelegramAuthState.Ready
  var pendingConfirmation by remember { mutableStateOf<TelegramCandidateTarget?>(null) }
  var searchQuery by remember { mutableStateOf("") }

  val filteredCandidates = remember(candidates, searchQuery) {
    if (searchQuery.isBlank()) {
      candidates
    } else {
      candidates.filter { TelegramPolicyGuard.matchesVietnameseSearch(it.title, searchQuery) }
    }
  }

  LaunchedEffect(isLoggedIn) {
    if (isLoggedIn) {
      onRefreshTargets()
    }
  }

  Column(modifier = modifier.fillMaxSize()) {
    TopAppBar(
      title = { Text("Chọn đích lưu trữ", style = MaterialTheme.typography.titleLarge) },
      navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.testTag("target_selector_back")) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
        }
      },
      actions = {
        if (isLoggedIn) {
          IconButton(
            onClick = onRefreshTargets,
            modifier = Modifier.testTag("refresh_targets_button"),
          ) {
            Icon(Icons.Filled.Refresh, contentDescription = "Tải lại danh sách")
          }
        }
      },
      colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )

    if (isLoggedIn) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("Tìm kiếm nhóm hoặc kênh Telegram...") },
        leadingIcon = {
          Icon(Icons.Filled.Search, contentDescription = "Tìm kiếm nhóm")
        },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(
              onClick = { searchQuery = "" },
              modifier = Modifier.testTag("target_search_clear_button"),
            ) {
              Icon(Icons.Filled.Close, contentDescription = "Xoá từ khoá")
            }
          }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 6.dp)
          .testTag("target_search_input"),
      )
    }

    LazyColumn(
      contentPadding = PaddingValues(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      item(key = "allowlist_policy_card") {
        TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Filled.VerifiedUser,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = "Ưu tiên Nhóm Telegram: Hỗ trợ Nhóm thường, Siêu nhóm riêng tư (tự tách chủ đề Ảnh & Video nếu bật Forum), Kênh riêng tư và Tin nhắn đã lưu.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      if (lockedTarget != null) {
        item(key = "current_locked_target") {
          TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
              modifier = Modifier.padding(16.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = StatusUploadedGreen,
                modifier = Modifier.size(22.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text("Đích đang khoá hiện tại", style = MaterialTheme.typography.labelMedium)
                Text(lockedTarget.chatTitle, style = MaterialTheme.typography.titleMedium)
                Text(
                  text = "Chat ID: ${lockedTarget.chatId} • ${lockedTarget.targetType.labelVi}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }

      if (!isLoggedIn) {
        item(key = "need_auth") {
          TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
              Text("Chưa đăng nhập Telegram", style = MaterialTheme.typography.titleMedium)
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "Vui lòng hoàn tất đăng nhập tài khoản Telegram để quét các đích lưu trữ riêng tư của bạn.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(12.dp))
              Button(
                onClick = onOpenTelegramAuth,
                modifier = Modifier.testTag("target_open_auth_button"),
              ) {
                Text("Đăng nhập Telegram ngay")
              }
            }
          }
        }
      } else if (isLoading) {
        item(key = "loading_targets") {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = "Đang mở kết nối ngắn hạn để tải danh sách đích...",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else if (candidates.isEmpty()) {
        item(key = "empty_targets") {
          TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
              Text("Không tìm thấy đích phù hợp", style = MaterialTheme.typography.titleMedium)
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "Hãy tạo một Kênh riêng tư hoặc Siêu nhóm riêng tư (bật Chủ đề / Topics) trong ứng dụng Telegram rồi nhấn Tải lại.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(12.dp))
              OutlinedButton(onClick = onRefreshTargets) {
                Text("Tải lại danh sách")
              }
            }
          }
        }
      } else if (filteredCandidates.isEmpty()) {
        item(key = "empty_search_targets") {
          TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
              Text("Không tìm thấy nhóm phù hợp", style = MaterialTheme.typography.titleMedium)
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "Không có nhóm hoặc kênh nào khớp với từ khoá «$searchQuery».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(12.dp))
              OutlinedButton(onClick = { searchQuery = "" }) {
                Text("Xoá bộ lọc tìm kiếm")
              }
            }
          }
        }
      } else {
        val groupTargets = filteredCandidates.filter {
          it.targetType == SyncTargetType.PRIVATE_SUPERGROUP || it.targetType == SyncTargetType.BASIC_GROUP
        }
        val channelTargets = filteredCandidates.filter { it.targetType == SyncTargetType.PRIVATE_CHANNEL }
        val savedTargets = filteredCandidates.filter { it.targetType == SyncTargetType.SAVED_MESSAGES }

        val groupedSections = buildList {
          if (groupTargets.isNotEmpty()) add("Nhóm Telegram (Ưu tiên)" to groupTargets)
          if (channelTargets.isNotEmpty()) add("Kênh Telegram" to channelTargets)
          if (savedTargets.isNotEmpty()) add("Tin nhắn đã lưu" to savedTargets)
        }

        groupedSections.forEach { (sectionTitle, sectionItems) ->
          item(key = "header_$sectionTitle") {
            Text(
              text = sectionTitle,
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
          }
          items(sectionItems, key = { it.chatId }) { candidate ->
            val isCurrent = lockedTarget?.chatId == candidate.chatId
            val icon = when (candidate.targetType) {
              SyncTargetType.PRIVATE_SUPERGROUP -> Icons.Filled.Forum
              SyncTargetType.BASIC_GROUP -> Icons.Filled.Groups
              SyncTargetType.PRIVATE_CHANNEL -> Icons.Filled.Campaign
              SyncTargetType.SAVED_MESSAGES -> Icons.Filled.Bookmark
            }
            TelePhotosGlassCard(
              onClick = { pendingConfirmation = candidate },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("candidate_target_${candidate.chatId}"),
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = icon,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(candidate.title, style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = candidate.subtitleVi,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                if (isCurrent) {
                  Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Đang sử dụng",
                    tint = StatusUploadedGreen,
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  pendingConfirmation?.let { candidate ->
    AlertDialog(
      onDismissRequest = { pendingConfirmation = null },
      title = { Text("Xác nhận khoá đích lưu trữ") },
      text = {
        Column {
          Text(
            text = "Bạn chắc chắn muốn khoá đích lưu trữ này làm kho ảnh/video duy nhất cho Tele Photos?",
            style = MaterialTheme.typography.bodyMedium,
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text(candidate.title, style = MaterialTheme.typography.titleSmall)
          Text(
            text = "Chat ID: ${candidate.chatId} • ${candidate.targetType.labelVi}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (candidate.isForumEnabled) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "Nhóm có bật Chủ đề (Forum): ứng dụng sẽ tự động tạo/dùng các chủ đề «Ảnh», «Video» cho tệp tải lên và «Đã xoá gần đây».",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            onConfirmLockTarget(candidate)
            pendingConfirmation = null
          },
          modifier = Modifier.testTag("confirm_lock_target_button"),
        ) {
          Text("Khoá đích này")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingConfirmation = null }) {
          Text("Huỷ")
        }
      },
    )
  }
}
