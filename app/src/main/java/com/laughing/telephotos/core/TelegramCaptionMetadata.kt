package com.laughing.telephotos.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TelegramCaptionPayload(
  @SerialName("v") val version: Int = 1,
  @SerialName("hash") val contentHash: String,
  @SerialName("ts") val takenAtEpochMs: Long,
  @SerialName("dur") val durationSec: Int = 0,
  @SerialName("topic") val topicId: Long? = null,
  @SerialName("trash") val isTrashed: Boolean = false,
  @SerialName("name") val fileName: String = "",
  @SerialName("type") val mediaKind: String = "PHOTO",
  @SerialName("size") val sizeBytes: Long = 0L,
)

object TelegramCaptionMetadata {
  private const val LEGACY_META_TAG = "#tele_anh_meta"
  const val APP_HASHTAG = "#tele_anh"
  private const val HASH_TAG_PREFIX = "#h_"
  private const val TRASH_TAG = "#trash"
  private const val CAPTION_DATE_PATTERN = "dd/MM/yyyy HH:mm"

  private val vietnameseLocale = Locale.forLanguageTag("vi-VN")
  private val hashTagRegex = Regex("""#h_([A-Za-z0-9_-]{6,64})""")
  private val dateLineRegex = Regex("""(\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2})""")
  private val sanitizeFileNameChars = Regex("""[\r\n\t]+""")

  private val legacyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  /**
   * Builds a clean, human-readable 2-line Telegram caption with NO raw JSON strings.
   * Technical fields (size, type, topic, duration) are read directly from TDLib Document/Message
   * objects when scanning history, while an optional compact #h_<prefix> / #trash tag at the end
   * preserves hash & trash state cleanly.
   */
  fun buildCaption(payload: TelegramCaptionPayload): String {
    val icon = if (payload.mediaKind == "VIDEO") "🎞️" else "🖼️"
    val cleanName = sanitizeFileNameChars.replace(payload.fileName, " ")
      .trim()
      .ifBlank { if (payload.mediaKind == "VIDEO") "video.mp4" else "photo.jpg" }
    val dateStr = formatCaptionDate(payload.takenAtEpochMs)

    val compactHash = payload.contentHash
      .trim()
      .takeIf { it.isNotBlank() && !it.startsWith("ms_") && !it.startsWith("tg_") }
      ?.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
      ?.take(16)

    val suffixTags = buildList {
      add(APP_HASHTAG)
      if (!compactHash.isNullOrBlank()) {
        add("$HASH_TAG_PREFIX$compactHash")
      }
      if (payload.isTrashed) {
        add(TRASH_TAG)
      }
    }.joinToString(" ")

    return "$icon $cleanName\n📅 $dateStr • $suffixTags"
  }

  /**
   * Parses both:
   * 1. Legacy captions containing `#tele_anh_meta {...}` JSON payloads (full backward compatibility)
   * 2. Clean 2-line captions (`🖼️/🎞️ <name>\n📅 <dd/MM/yyyy HH:mm> • #tele_anh [#h_<hash>] [#trash]`)
   */
  fun parseFromCaption(caption: String?): TelegramCaptionPayload? {
    if (caption.isNullOrBlank()) return null

    // 1. Legacy JSON compatibility path
    val markerIndex = caption.indexOf(LEGACY_META_TAG)
    if (markerIndex >= 0) {
      val jsonStart = caption.indexOf('{', startIndex = markerIndex)
      val jsonEnd = caption.lastIndexOf('}')
      if (jsonStart in 0 until jsonEnd) {
        val rawJson = caption.substring(jsonStart, jsonEnd + 1)
        val decoded = runCatching {
          legacyJson.decodeFromString<TelegramCaptionPayload>(rawJson)
        }.getOrNull()
        if (decoded != null) return decoded
      }
    }

    // 2. Clean 2-line format parser
    val hasAppTag = caption.contains(APP_HASHTAG) ||
      hashTagRegex.containsMatchIn(caption) ||
      caption.trimStart().startsWith("🖼️") ||
      caption.trimStart().startsWith("🎞️")
    if (!hasAppTag) return null

    val lines = caption.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val firstLine = lines.firstOrNull().orEmpty()
    val mediaKind = if (firstLine.startsWith("🎞️")) "VIDEO" else "PHOTO"
    val parsedFileName = firstLine
      .removePrefix("🖼️")
      .removePrefix("🎞️")
      .trim()

    val parsedDateMs = dateLineRegex.find(caption)?.groupValues?.getOrNull(1)?.let { dateText ->
      runCatching {
        SimpleDateFormat(CAPTION_DATE_PATTERN, vietnameseLocale).apply {
          isLenient = false
        }.parse(dateText)?.time
      }.getOrNull()
    } ?: 0L

    val parsedHash = hashTagRegex.find(caption)?.groupValues?.getOrNull(1).orEmpty()
    val isTrashed = caption.contains(TRASH_TAG)

    return TelegramCaptionPayload(
      version = 2,
      contentHash = parsedHash,
      takenAtEpochMs = parsedDateMs,
      durationSec = 0,
      topicId = null,
      isTrashed = isTrashed,
      fileName = parsedFileName,
      mediaKind = mediaKind,
      sizeBytes = 0L,
    )
  }

  /**
   * Builds a clean fallback display filename for pre-existing native Telegram photos/videos
   * that do not carry app-generated captions.
   */
  fun extractReadableTitle(caption: String?, fallbackName: String): String {
    if (caption.isNullOrBlank()) return fallbackName
    val cleanFirstLine = caption.lineSequence()
      .map { it.trim() }
      .firstOrNull { line ->
        line.isNotEmpty() &&
          !line.startsWith("#") &&
          !line.startsWith("📅") &&
          !line.startsWith("📦")
      }
      ?.removePrefix("🖼️")
      ?.removePrefix("🎞️")
      ?.trim()
      ?.take(60)
      .orEmpty()
    if (cleanFirstLine.isBlank()) return fallbackName
    val sanitized = cleanFirstLine.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    if (sanitized.isBlank()) return fallbackName
    val fallbackExt = fallbackName.substringAfterLast('.', "")
    return if (fallbackExt.isNotBlank() && !sanitized.contains('.')) {
      "$sanitized.$fallbackExt"
    } else {
      sanitized
    }
  }

  private fun formatCaptionDate(epochMs: Long): String {
    val safeMs = if (epochMs > 0L) epochMs else System.currentTimeMillis()
    return SimpleDateFormat(CAPTION_DATE_PATTERN, vietnameseLocale).format(Date(safeMs))
  }
}
