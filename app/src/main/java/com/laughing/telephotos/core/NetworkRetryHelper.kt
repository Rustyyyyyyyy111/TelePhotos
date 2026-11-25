package com.laughing.telephotos.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.laughing.telephotos.data.telegram.FloodWaitException
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkOperationType(val contextPrefixVi: String) {
  UPLOAD("Tải ảnh/video lên Telegram bị gián đoạn."),
  DOWNLOAD("Không thể tải ảnh/video từ Telegram."),
}

data class NetworkInterruptionAlert(
  val id: Long = System.currentTimeMillis(),
  val operationType: NetworkOperationType,
  val titleVi: String = NetworkRetryPolicy.ALERT_TITLE_VI,
  val messageVi: String = "${operationType.contextPrefixVi}\n\n${NetworkRetryPolicy.ALERT_BODY_VI}",
  val confirmButtonVi: String = NetworkRetryPolicy.ALERT_BUTTON_VI,
)

class NetworkInterruptedException(message: String) : IOException(message)

class StalledTransferException(message: String) : IOException(message)

sealed interface RetryDecision {
  data class WaitAndRetry(val attemptNumber: Int, val delayMs: Long) : RetryDecision
  data object StopAndNotify : RetryDecision
}

/**
 * Enforces the 8-second wait retry policy:
 * - 1st failure -> wait 8s -> retry
 * - 2nd failure -> wait 8s -> retry
 * - 3rd consecutive failure -> stop automatic retries and notify user
 * Resets counter on progress/completion.
 */
class NetworkRetryTracker(
  val maxAutoRetries: Int = NetworkRetryPolicy.MAX_AUTO_RETRIES,
  val retryDelayMs: Long = NetworkRetryPolicy.RETRY_DELAY_MS,
) {
  var consecutiveFailures: Int = 0
    private set

  fun reset() {
    consecutiveFailures = 0
  }

  fun onFailure(): RetryDecision {
    consecutiveFailures += 1
    return if (consecutiveFailures <= maxAutoRetries) {
      RetryDecision.WaitAndRetry(
        attemptNumber = consecutiveFailures,
        delayMs = retryDelayMs,
      )
    } else {
      RetryDecision.StopAndNotify
    }
  }
}

class NetworkAlertCoordinator {
  private val _activeAlert = MutableStateFlow<NetworkInterruptionAlert?>(null)
  val activeAlert: StateFlow<NetworkInterruptionAlert?> = _activeAlert.asStateFlow()

  fun reportFinalFailure(operationType: NetworkOperationType) {
    // Prevent duplicate or stacked dialogs if multiple failures happen simultaneously
    if (_activeAlert.value == null) {
      _activeAlert.value = NetworkInterruptionAlert(operationType = operationType)
    }
  }

  fun dismissAlert() {
    _activeAlert.value = null
  }
}

object NetworkRetryPolicy {
  const val MAX_AUTO_RETRIES = 2
  const val RETRY_DELAY_MS = 8_000L
  const val STALL_TIMEOUT_MS = 25_000L
  const val PROGRESS_CHECK_INTERVAL_MS = 2_000L

  const val ALERT_TITLE_VI = "Không thể kết nối Telegram"
  const val ALERT_BODY_VI =
    "Kết nối mạng hoặc máy chủ Telegram đang gặp sự cố. Ứng dụng đã thử kết nối lại nhưng không thành công. Vui lòng kiểm tra mạng và quay lại sau."
  const val ALERT_BUTTON_VI = "Đã hiểu"

  fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  fun isFloodWaitError(throwable: Throwable?): Boolean {
    if (throwable is FloodWaitException) return true
    val msg = throwable?.message.orEmpty()
    return msg.contains("FLOOD_WAIT", ignoreCase = true) ||
      msg.contains("retry after", ignoreCase = true) ||
      msg.contains("Too Many Requests", ignoreCase = true) ||
      msg.contains("giới hạn tốc độ", ignoreCase = true)
  }

  fun isTransientNetworkError(throwable: Throwable?, context: Context? = null): Boolean {
    if (throwable is CancellationException) return false
    if (throwable is SecurityException) return false
    if (isFloodWaitError(throwable)) return false
    if (throwable is NetworkInterruptedException ||
      throwable is StalledTransferException ||
      throwable is IOException ||
      throwable is TimeoutException
    ) {
      return true
    }
    if (context != null && !isNetworkAvailable(context)) {
      return true
    }
    val msg = throwable?.message.orEmpty().lowercase()
    if (msg.contains("chat_admin_required") ||
      msg.contains("not_enough_rights") ||
      msg.contains("thiếu quyền") ||
      msg.contains("chưa bật quyền") ||
      msg.contains("chưa đăng nhập") ||
      msg.contains("chưa cấu hình api") ||
      msg.contains("chính sách chỉ đọc")
    ) {
      return false
    }
    return msg.contains("network") ||
      msg.contains("timeout") ||
      msg.contains("timed out") ||
      msg.contains("hết thời gian") ||
      msg.contains("connect") ||
      msg.contains("kết nối") ||
      msg.contains("socket") ||
      msg.contains("unreachable") ||
      msg.contains("reset") ||
      msg.contains("eof") ||
      msg.contains("unavailable") ||
      msg.contains("waitingfornetwork") ||
      msg.contains("tdlib chưa sẵn sàng") ||
      msg.contains("client is closed")
  }
}
