package com.laughing.telephotos.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TelegramCaptionPayload(
  @SerialName("v") val version: Int = TelegramCaptionMetadata.CURRENT_METADATA_VERSION,
  @SerialName("hash") val contentHash: String,
  @SerialName("ts") val takenAtEpochMs: Long,
  @SerialName("dur") val durationSec: Int = 0,
  @SerialName("topic") val topicId: Long? = null,
  @SerialName("trash") val isTrashed: Boolean = false,
  @SerialName("name") val fileName: String = "",
  @SerialName("type") val mediaKind: String = "PHOTO",
  @SerialName("size") val sizeBytes: Long = 0L,
  @SerialName("mode") val syncMode: String? = null,
  @SerialName("caption") val userCaption: String? = null,
)

object TelegramCaptionMetadata {
  const val CURRENT_METADATA_VERSION = 4
  private const val LEGACY_META_TAG = "#tele_anh_meta"
  const val APP_HASHTAG = "#tele_anh"
  const val PHOTO_TYPE_TAG = "#t_photo"
  const val VIDEO_TYPE_TAG = "#t_video"
  const val FILE_MODE_TAG = "#m_file"
  const val HD_MODE_TAG = "#m_hd"
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
   * Builds a compact, non-intrusive Telegram caption with NO raw JSON strings:
   * - Preserves any custom `userCaption` lines cleanly above the footer when present.
   * - When `userCaption` is blank, uses a 2-line layout (`🖼️/🎞️ <name>\n📅 <dd/MM/yyyy HH:mm> • ...`).
   * - Encodes `mediaKind` (`#t_photo`/`#t_video`), `syncMode` (`#m_file`/`#m_hd`),
   *   optional `#h_<16hex>` hint (from real content hashes only), and `#trash` on the footer line.
   */
  fun buildCaption(payload: TelegramCaptionPayload): String {
    val isVideo = payload.mediaKind.equals("VIDEO", ignoreCase = true)
    val icon = if (isVideo) "🎞️" else "🖼️"
    val typeTag = if (isVideo) VIDEO_TYPE_TAG else PHOTO_TYPE_TAG
    val modeTag = when (payload.syncMode) {
      "HD_MEDIA" -> HD_MODE_TAG
      "AS_FILE" -> FILE_MODE_TAG
      else -> null
    }
    val dateStr = formatCaptionDate(payload.takenAtEpochMs)

    val compactHash = payload.contentHash
      .trim()
      .takeIf { it.isNotBlank() && !it.startsWith("ms_") && !it.startsWith("tg_") }
      ?.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
      ?.take(16)

    val suffixTags = buildList {
      add(APP_HASHTAG)
      add(typeTag)
      if (modeTag != null) {
        add(modeTag)
      }
      if (!compactHash.isNullOrBlank()) {
        add("$HASH_TAG_PREFIX$compactHash")
      }
      if (payload.isTrashed) {
        add(TRASH_TAG)
      }
    }.joinToString(" ")

    val footerLine = "📅 $dateStr • $suffixTags"
    val cleanUserCaption = payload.userCaption
      ?.lines()
      ?.map { it.trimEnd() }
      ?.filter { it.isNotBlank() && !isFooterMetadataLine(it) }
      ?.joinToString("\n")
      ?.trim()

    if (!cleanUserCaption.isNullOrBlank()) {
      return "$cleanUserCaption\n$footerLine"
    }

    val cleanName = sanitizeFileNameChars.replace(payload.fileName, " ")
      .trim()
      .ifBlank { if (isVideo) "video.mp4" else "photo.jpg" }

    return "$icon $cleanName\n$footerLine"
  }

  /**
   * Parses both:
   * 1. Legacy captions containing `#tele_anh_meta {...}` JSON payloads (backward compatibility)
   * 2. Clean footer captions carrying explicit Tele Photos tags (`#tele_anh`, `#t_photo`, `#t_video`, `#h_...`).
   *
   * Plain native Telegram captions (even if they start with "🖼️" or "🎞️") return `null` so callers
   * preserve raw caption text and fall back cleanly to Telegram message timestamp.
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

    // 2. Clean footer format parser: require explicit Tele Photos hashtag markers
    val hasExplicitAppTag = caption.contains(APP_HASHTAG) ||
      caption.contains(PHOTO_TYPE_TAG) ||
      caption.contains(VIDEO_TYPE_TAG) ||
      hashTagRegex.containsMatchIn(caption)
    if (!hasExplicitAppTag) return null

    val lines = caption.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
    val footerLine = lines.lastOrNull { isFooterMetadataLine(it) } ?: caption
    val bodyLines = lines.filterNot { isFooterMetadataLine(it) }
    val firstBodyLine = bodyLines.firstOrNull()?.trim().orEmpty()

    val mediaKind = when {
      footerLine.contains(VIDEO_TYPE_TAG) || caption.contains(VIDEO_TYPE_TAG) -> "VIDEO"
      footerLine.contains(PHOTO_TYPE_TAG) || caption.contains(PHOTO_TYPE_TAG) -> "PHOTO"
      firstBodyLine.startsWith("🎞️") -> "VIDEO"
      else -> "PHOTO"
    }

    val syncMode = when {
      footerLine.contains(HD_MODE_TAG) || caption.contains(HD_MODE_TAG) -> "HD_MEDIA"
      footerLine.contains(FILE_MODE_TAG) || caption.contains(FILE_MODE_TAG) -> "AS_FILE"
      else -> null
    }

    val isAutoFileHeaderOnly = bodyLines.size == 1 &&
      (firstBodyLine.startsWith("🖼️") || firstBodyLine.startsWith("🎞️"))

    val parsedFileName = if (firstBodyLine.startsWith("🖼️") || firstBodyLine.startsWith("🎞️")) {
      firstBodyLine.removePrefix("🖼️").removePrefix("🎞️").trim()
    } else {
      ""
    }

    val parsedUserCaption = if (!isAutoFileHeaderOnly && bodyLines.isNotEmpty()) {
      bodyLines.joinToString("\n").trim().takeIf { it.isNotBlank() }
    } else {
      null
    }

    val parsedDateMs = dateLineRegex.find(footerLine)?.groupValues?.getOrNull(1)?.let { dateText ->
      runCatching {
        SimpleDateFormat(CAPTION_DATE_PATTERN, vietnameseLocale).apply {
          isLenient = false
        }.parse(dateText)?.time
      }.getOrNull()
    } ?: 0L

    val parsedHash = hashTagRegex.find(caption)?.groupValues?.getOrNull(1).orEmpty()
    val isTrashed = caption.contains(TRASH_TAG)

    return TelegramCaptionPayload(
      version = CURRENT_METADATA_VERSION,
      contentHash = parsedHash,
      takenAtEpochMs = parsedDateMs,
      durationSec = 0,
      topicId = null,
      isTrashed = isTrashed,
      fileName = parsedFileName,
      mediaKind = mediaKind,
      sizeBytes = 0L,
      syncMode = syncMode,
      userCaption = parsedUserCaption,
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

  private fun isFooterMetadataLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.startsWith("📅") &&
      (trimmed.contains(APP_HASHTAG) ||
        trimmed.contains(PHOTO_TYPE_TAG) ||
        trimmed.contains(VIDEO_TYPE_TAG) ||
        hashTagRegex.containsMatchIn(trimmed))
    ) {
      return true
    }
    return trimmed.startsWith(APP_HASHTAG) && trimmed.contains(HASH_TAG_PREFIX)
  }

  private fun formatCaptionDate(epochMs: Long): String {
    val safeMs = if (epochMs > 0L) epochMs else System.currentTimeMillis()
    return SimpleDateFormat(CAPTION_DATE_PATTERN, vietnameseLocale).format(Date(safeMs))
  }
}
