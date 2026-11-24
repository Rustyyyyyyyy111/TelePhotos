package com.laughing.telephotos.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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
)

object TelegramCaptionMetadata {
  private const val META_TAG = "#tele_anh_meta"
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun buildCaption(payload: TelegramCaptionPayload): String {
    val encoded = json.encodeToString(payload)
    val kindLabel = if (payload.mediaKind == "VIDEO") "Video" else "Ảnh"
    val trashHint = if (payload.isTrashed) " • Đã xoá gần đây" else ""
    return "📦 Tele Photos ($kindLabel$trashHint)\n$META_TAG $encoded"
  }

  fun parseFromCaption(caption: String?): TelegramCaptionPayload? {
    if (caption.isNullOrBlank()) return null
    val markerIndex = caption.indexOf(META_TAG)
    if (markerIndex < 0) return null
    val jsonStart = caption.indexOf('{', startIndex = markerIndex)
    val jsonEnd = caption.lastIndexOf('}')
    if (jsonStart < 0 || jsonEnd <= jsonStart) return null
    val rawJson = caption.substring(jsonStart, jsonEnd + 1)
    return runCatching { json.decodeFromString<TelegramCaptionPayload>(rawJson) }.getOrNull()
  }
}
