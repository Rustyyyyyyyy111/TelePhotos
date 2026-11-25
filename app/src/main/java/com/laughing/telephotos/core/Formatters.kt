package com.laughing.telephotos.core

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

object Formatters {

  private val vietnameseLocale = Locale.forLanguageTag("vi-VN")

  fun formatVideoDuration(durationSec: Int): String {
    val safeSec = durationSec.coerceAtLeast(0)
    val hours = safeSec / 3600
    val minutes = (safeSec % 3600) / 60
    val seconds = safeSec % 60
    return if (hours > 0) {
      String.format(vietnameseLocale, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(vietnameseLocale, "%d:%02d", minutes, seconds)
    }
  }

  fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
      .coerceIn(0, units.lastIndex)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return if (digitGroups == 0) {
      "$bytes B"
    } else {
      String.format(vietnameseLocale, "%.1f %s", value, units[digitGroups])
    }
  }

  fun formatTimelineDayHeader(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val calTarget = Calendar.getInstance().apply { timeInMillis = epochMs }
    val calNow = Calendar.getInstance().apply { timeInMillis = nowMs }

    val sameYear = calTarget.get(Calendar.YEAR) == calNow.get(Calendar.YEAR)
    val targetDay = calTarget.get(Calendar.DAY_OF_YEAR)
    val nowDay = calNow.get(Calendar.DAY_OF_YEAR)

    return when {
      sameYear && targetDay == nowDay -> "Hôm nay"
      sameYear && targetDay == nowDay - 1 -> "Hôm qua"
      sameYear -> {
        val sdf = SimpleDateFormat("EEE, d 'thg' M", vietnameseLocale)
        sdf.format(Date(epochMs)).replaceFirstChar { it.titlecase(vietnameseLocale) }
      }
      else -> {
        val sdf = SimpleDateFormat("d 'thg' M, yyyy", vietnameseLocale)
        sdf.format(Date(epochMs))
      }
    }
  }

  fun formatMonthYearLabel(epochMs: Long): String {
    val sdf = SimpleDateFormat("'Tháng' M, yyyy", vietnameseLocale)
    return sdf.format(Date(epochMs))
  }

  fun formatFullDateTime(epochMs: Long): String {
    val sdf = SimpleDateFormat("HH:mm • dd/MM/yyyy", vietnameseLocale)
    return sdf.format(Date(epochMs))
  }

  fun formatRemainingSeconds(remainingSeconds: Long): String {
    val safe = remainingSeconds.coerceAtLeast(0L)
    val min = safe / 60
    val sec = safe % 60
    return if (min > 0) {
      "${min} phút ${sec} giây"
    } else {
      "${sec} giây"
    }
  }

  /**
   * Normalizes Vietnamese phone numbers to E.164 format (+84...).
   */
  fun normalizePhoneNumber(raw: String): String {
    val compact = raw.trim().replace(Regex("[\\s.-]"), "")
    return when {
      compact.startsWith("+") -> compact
      compact.startsWith("0") && compact.length >= 9 -> "+84" + compact.substring(1)
      compact.startsWith("84") && compact.length >= 10 -> "+$compact"
      else -> "+84$compact"
    }
  }

  const val TRASH_RETENTION_DAYS = 30
  const val DAY_MS = 86_400_000L

  fun computeRemainingTrashDays(
    trashedAtEpochMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
  ): Int {
    if (trashedAtEpochMs == null) return TRASH_RETENTION_DAYS
    val expiresAt = trashedAtEpochMs + TRASH_RETENTION_DAYS * DAY_MS
    val remainingMs = (expiresAt - nowMs).coerceAtLeast(0L)
    if (remainingMs == 0L) return 0
    return ((remainingMs + DAY_MS - 1L) / DAY_MS).toInt().coerceIn(1, TRASH_RETENTION_DAYS)
  }

  fun formatRemainingTrashDays(
    trashedAtEpochMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
  ): String {
    val days = computeRemainingTrashDays(trashedAtEpochMs, nowMs)
    return if (days <= 0) "Sắp tự động xoá" else "Còn lại $days ngày"
  }

  private val combiningMarksRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
  private val whitespaceRegex = Regex("\\s+")

  /**
   * Strips Vietnamese accents/diacritics and folds 'đ'/'Đ' -> 'd' for search & topic comparison.
   * Ensures tone placement variants such as "xoá" and "xóa" produce identical normalized text.
   */
  fun removeVietnameseDiacritics(raw: String): String {
    val nfd = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
    return combiningMarksRegex.replace(nfd, "")
      .replace('đ', 'd')
      .replace('Đ', 'D')
      .replace(whitespaceRegex, " ")
      .lowercase(Locale.ROOT)
      .trim()
  }

  fun normalizeTopicKey(title: String): String = removeVietnameseDiacritics(title)
}
