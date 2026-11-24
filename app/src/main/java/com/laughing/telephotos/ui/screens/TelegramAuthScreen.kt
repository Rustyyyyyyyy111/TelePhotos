package com.laughing.telephotos.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.laughing.telephotos.R
import com.laughing.telephotos.core.AppSettings
import com.laughing.telephotos.data.local.SyncTargetEntity
import com.laughing.telephotos.data.telegram.TelegramAuthState
import com.laughing.telephotos.ui.components.TelePhotosGlassCard
import com.laughing.telephotos.ui.theme.StatusErrorRed
import com.laughing.telephotos.ui.theme.StatusUploadedGreen
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramAuthScreen(
  settings: AppSettings,
  authState: TelegramAuthState,
  lockedTarget: SyncTargetEntity?,
  isBusy: Boolean,
  onEnsureTelegramInitialized: () -> Unit,
  onCloseTelegramIdle: () -> Unit,
  onSaveApiCredentials: (String, String) -> Unit,
  onSwitchApiCredentials: () -> Unit,
  onReturnToPhoneNumber: () -> Unit,
  onBack: () -> Unit,
  onSubmitPhone: (String) -> Unit,
  onSubmitCode: (String) -> Unit,
  onSubmitPassword: (String) -> Unit,
  onLogout: () -> Unit,
  onAuthReadyNavigate: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var apiIdInput by remember { mutableStateOf("") }
  var apiHashInput by remember { mutableStateOf("") }
  var phoneInput by remember { mutableStateOf("+84") }
  var codeInput by remember { mutableStateOf("") }
  var passwordInput by remember { mutableStateOf("") }

  var startedNotReady by remember { mutableStateOf(!settings.isTelegramLoggedIn && authState !is TelegramAuthState.Ready) }

  LaunchedEffect(settings.isTelegramLoggedIn) {
    if (!settings.isTelegramLoggedIn && authState !is TelegramAuthState.WaitApiCredentials) {
      onEnsureTelegramInitialized()
    }
  }

  LaunchedEffect(authState) {
    if (authState is TelegramAuthState.WaitApiCredentials) {
      if (authState.initialApiId.isNotBlank() && apiIdInput.isBlank()) {
        apiIdInput = authState.initialApiId
      }
      if (authState.initialApiHash.isNotBlank() && apiHashInput.isBlank()) {
        apiHashInput = authState.initialApiHash
      }
      startedNotReady = true
    } else if (authState is TelegramAuthState.WaitPhoneNumber) {
      phoneInput = authState.initialPhone.ifBlank { "+84" }
      startedNotReady = true
    } else if (authState is TelegramAuthState.WaitCode || authState is TelegramAuthState.WaitPassword) {
      startedNotReady = true
    } else if (authState is TelegramAuthState.Ready && startedNotReady) {
      delay(700L)
      onCloseTelegramIdle()
      onAuthReadyNavigate()
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState()),
  ) {
    TopAppBar(
      title = { Text("Kết nối Telegram (TDLib)", style = MaterialTheme.typography.titleLarge) },
      navigationIcon = {
        IconButton(
          onClick = {
            if (settings.isTelegramLoggedIn) onCloseTelegramIdle()
            onBack()
          },
          modifier = Modifier.testTag("auth_back_button"),
        ) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
        }
      },
      colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      TelePhotosGlassCard(
        modifier = Modifier
          .widthIn(max = 520.dp)
          .fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Image(
            painter = painterResource(id = R.drawable.img_vault_hero),
            contentDescription = "Tele Photos Telegram Vault",
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .fillMaxWidth()
              .height(150.dp)
              .clip(RoundedCornerShape(12.dp)),
          )
          Spacer(modifier = Modifier.height(16.dp))

          // Show persisted account summary when already logged in and TDLib is closed idle
          if (settings.isTelegramLoggedIn &&
            authState !is TelegramAuthState.WaitApiCredentials &&
            authState !is TelegramAuthState.WaitPhoneNumber &&
            authState !is TelegramAuthState.WaitCode &&
            authState !is TelegramAuthState.WaitPassword &&
            authState !is TelegramAuthState.Error
          ) {
            val displayName = (authState as? TelegramAuthState.Ready)?.displayName
              ?: settings.telegramDisplayName.ifBlank { "Người dùng Telegram" }
            val phone = (authState as? TelegramAuthState.Ready)?.phoneNumber
              ?: settings.telegramPhoneNumber
            val userId = (authState as? TelegramAuthState.Ready)?.userId
              ?: settings.telegramUserId

            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = StatusUploadedGreen,
                modifier = Modifier.size(22.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Đã kết nối Telegram thành công",
                style = MaterialTheme.typography.titleMedium,
                color = StatusUploadedGreen,
              )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Tài khoản: $displayName", style = MaterialTheme.typography.bodyLarge)
            if (phone.isNotBlank()) {
              Text(
                text = "Số điện thoại: $phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Text(
              text = "ID: $userId • Phiên đang ngắt kết nối nhàn rỗi (tiết kiệm RAM)",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
              onClick = {
                onCloseTelegramIdle()
                onAuthReadyNavigate()
              },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("auth_continue_button"),
            ) {
              Text(if (lockedTarget == null) "Chọn đích lưu trữ" else "Vào thư viện Tele Photos")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
              onClick = onSwitchApiCredentials,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("auth_ready_switch_api_button"),
            ) {
              Text("Đổi khoá API")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
              onClick = onLogout,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("auth_logout_button"),
            ) {
              Text("Đăng xuất phiên Telegram")
            }
          } else {
            when (authState) {
              is TelegramAuthState.WaitApiCredentials -> {
                Text("Cấu hình API TDLib", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = "Tele Photos kết nối trực tiếp bằng Telegram API của bạn và lưu khoá an toàn trong thiết bị.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                  value = apiIdInput,
                  onValueChange = { next -> apiIdInput = next.filter { it.isDigit() } },
                  label = { Text("API ID") },
                  placeholder = { Text("Nhập API ID từ my.telegram.org") },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  singleLine = true,
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_api_id_input"),
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                  value = apiHashInput,
                  onValueChange = { apiHashInput = it },
                  label = { Text("API Hash") },
                  placeholder = { Text("Nhập API Hash") },
                  singleLine = true,
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_api_hash_input"),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                  onClick = { onSaveApiCredentials(apiIdInput, apiHashInput) },
                  enabled = !isBusy && apiIdInput.isNotBlank() && apiHashInput.trim().length >= 16,
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_save_api_button"),
                ) {
                  Text("Lưu & Tiếp tục")
                }
              }

              TelegramAuthState.Uninitialized -> {
                Column(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  CircularProgressIndicator()
                  Spacer(modifier = Modifier.height(10.dp))
                  Text("Đang khởi tạo TDLib cho Tele Photos...", style = MaterialTheme.typography.bodyMedium)
                }
              }

              is TelegramAuthState.WaitPhoneNumber -> {
                Text("Đăng nhập bằng số điện thoại", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = "Phiên đăng nhập được mã hoá cục bộ bởi TDLib với chính sách Chỉ đọc + Tải lên.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                  value = phoneInput,
                  onValueChange = { phoneInput = it },
                  label = { Text("Số điện thoại") },
                  placeholder = { Text("Nhập số điện thoại (ví dụ: 912345678)") },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                  singleLine = true,
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_phone_input"),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = "Mặc định mã quốc gia Việt Nam +84 (số bắt đầu bằng 0 sẽ tự động đổi thành +84).",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                  onClick = { onSubmitPhone(phoneInput) },
                  enabled = !isBusy && phoneInput.trim().length >= 6,
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_phone_submit"),
                ) {
                  Text("Gửi mã xác nhận")
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.Center,
                ) {
                  TextButton(
                    onClick = onSwitchApiCredentials,
                    modifier = Modifier.testTag("auth_switch_api_button"),
                  ) {
                    Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Đổi khoá API")
                  }
                }
              }

              is TelegramAuthState.WaitCode -> {
                Text("Nhập mã OTP từ Telegram", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = "Mã xác nhận đã được gửi tới số ${authState.phoneHint}.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                  value = codeInput,
                  onValueChange = { next -> codeInput = next.filter { it.isDigit() }.take(6) },
                  label = { Text("Mã xác nhận OTP") },
                  placeholder = { Text("Nhập mã xác nhận từ Telegram") },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  singleLine = true,
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_code_input"),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                  onClick = { onSubmitCode(codeInput) },
                  enabled = !isBusy && codeInput.length >= 5,
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_code_submit"),
                ) {
                  Text("Xác nhận")
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.Center,
                ) {
                  TextButton(
                    onClick = onReturnToPhoneNumber,
                    modifier = Modifier.testTag("auth_back_to_phone_button"),
                  ) {
                    Text("Quay lại sửa số điện thoại")
                  }
                }
              }

              is TelegramAuthState.WaitPassword -> {
                Text("Mật khẩu xác minh 2 bước", style = MaterialTheme.typography.titleMedium)
                if (authState.passwordHint.isNotBlank()) {
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = "Gợi ý: ${authState.passwordHint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                  value = passwordInput,
                  onValueChange = { passwordInput = it },
                  label = { Text("Mật khẩu đám mây") },
                  placeholder = { Text("Nhập mật khẩu xác minh 2 bước") },
                  visualTransformation = PasswordVisualTransformation(),
                  singleLine = true,
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_password_input"),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                  onClick = { onSubmitPassword(passwordInput) },
                  enabled = !isBusy && passwordInput.isNotBlank(),
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_password_submit"),
                ) {
                  Text("Đăng nhập")
                }
              }

              is TelegramAuthState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = StatusUploadedGreen,
                    modifier = Modifier.size(22.dp),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = "Đã kết nối Telegram thành công",
                    style = MaterialTheme.typography.titleMedium,
                    color = StatusUploadedGreen,
                  )
                }
              }

              is TelegramAuthState.Error -> {
                Text("Trạng thái TDLib", style = MaterialTheme.typography.titleMedium, color = StatusErrorRed)
                Spacer(modifier = Modifier.height(6.dp))
                Text(authState.messageVi, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                  onClick = onSwitchApiCredentials,
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_error_switch_api_button"),
                ) {
                  Text("Đổi khoá API / Thử lại")
                }
              }
            }
          }
        }
      }
    }
  }
}
