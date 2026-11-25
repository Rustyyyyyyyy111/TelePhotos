package com.laughing.telephotos.data.telegram

import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.data.local.SyncTargetType
import org.drinkless.tdlib.TdApi

/**
 * Enforces the strict "Upload + Read-Only" Telegram safety policy and target allowlist.
 */
object TelegramPolicyGuard {

  const val TRASH_TOPIC_TITLE = "Đã Xoá Gần Đây"
  const val PHOTOS_TOPIC_TITLE = "Ảnh"
  const val VIDEOS_TOPIC_TITLE = "Video"
  const val FLOOD_WAIT_SAFETY_BUFFER_SEC = 5

  private const val NORMALIZED_TRASH_KEY = "da xoa gan day"
  private const val NORMALIZED_PHOTOS_KEY = "anh"
  private const val NORMALIZED_VIDEOS_KEY = "video"

  fun isTrashTopicTitle(title: String): Boolean =
    Formatters.normalizeTopicKey(title) == NORMALIZED_TRASH_KEY

  fun isPhotosAutoTopicTitle(title: String): Boolean =
    Formatters.normalizeTopicKey(title) == NORMALIZED_PHOTOS_KEY

  fun isVideosAutoTopicTitle(title: String): Boolean =
    Formatters.normalizeTopicKey(title) == NORMALIZED_VIDEOS_KEY

  fun isSystemAutoTopicTitle(title: String): Boolean =
    isTrashTopicTitle(title) || isPhotosAutoTopicTitle(title) || isVideosAutoTopicTitle(title)

  fun matchesVietnameseSearch(candidateTitle: String, query: String): Boolean {
    val cleanQuery = query.trim()
    if (cleanQuery.isEmpty()) return true
    if (candidateTitle.contains(cleanQuery, ignoreCase = true)) return true
    val normalizedCandidate = Formatters.removeVietnameseDiacritics(candidateTitle)
    val normalizedQuery = Formatters.removeVietnameseDiacritics(cleanQuery)
    return normalizedQuery.isEmpty() || normalizedCandidate.contains(normalizedQuery)
  }

  private val floodWaitRegex = Regex("""(?:FLOOD_WAIT_|retry after\s+)(\d+)""", RegexOption.IGNORE_CASE)

  fun parseFloodWaitSeconds(errorMessage: String?): Int? {
    if (errorMessage.isNullOrBlank()) return null
    val match = floodWaitRegex.find(errorMessage) ?: return null
    return match.groupValues.getOrNull(1)?.toIntOrNull()
  }

  fun translateAuthError(rawMessage: String?): String {
    val msg = rawMessage.orEmpty()
    val floodSec = parseFloodWaitSeconds(msg)
    if (floodSec != null) {
      return "Telegram đang giới hạn tốc độ. Vui lòng thử lại sau $floodSec giây."
    }
    return when {
      msg.contains("API_ID_INVALID", ignoreCase = true) ||
        msg.contains("API_ID_PUBLISHED_FLOOD", ignoreCase = true) ->
        "API ID hoặc API Hash không hợp lệ. Vui lòng kiểm tra lại tại my.telegram.org."
      msg.contains("PHONE_NUMBER_INVALID", ignoreCase = true) ->
        "Số điện thoại không hợp lệ. Hãy nhập đúng định dạng (ví dụ: +84912345678)."
      msg.contains("PHONE_CODE_INVALID", ignoreCase = true) ->
        "Mã xác nhận OTP không đúng. Vui lòng kiểm tra lại tin nhắn Telegram."
      msg.contains("PHONE_CODE_EXPIRED", ignoreCase = true) ->
        "Mã xác nhận OTP đã hết hạn. Vui lòng quay lại để gửi lại mã mới."
      msg.contains("PASSWORD_HASH_INVALID", ignoreCase = true) ->
        "Mật khẩu xác minh 2 bước không chính xác."
      msg.contains("CHAT_ADMIN_REQUIRED", ignoreCase = true) ||
        msg.contains("NOT_ENOUGH_RIGHTS", ignoreCase = true) ||
        msg.contains("RIGHTS", ignoreCase = true) ->
        "Nhóm chưa bật quyền tạo Chủ đề (Topics) cho tài khoản của bạn. Vui lòng cấp quyền quản trị hoặc chọn nhóm/kênh khác."
      msg.contains("CHAT_WRITE_FORBIDDEN", ignoreCase = true) ->
        "Tài khoản không có quyền gửi tệp vào nhóm hoặc kênh này."
      msg.contains("FORUM_NOT_FOUND", ignoreCase = true) ||
        msg.contains("TOPIC_CLOSED", ignoreCase = true) ->
        "Nhóm chưa hỗ trợ Chủ đề (Forum Topics) hoặc chủ đề đã bị đóng."
      else -> msg.ifBlank { "Đã xảy ra lỗi kết nối Telegram" }
    }
  }

  fun classifyAllowedTarget(
    chat: TdApi.Chat,
    myUserId: Long,
    supergroup: TdApi.Supergroup?,
    basicGroup: TdApi.BasicGroup? = null,
  ): TelegramCandidateTarget? {
    return when (val type = chat.type) {
      is TdApi.ChatTypePrivate -> {
        if (type.userId == myUserId && myUserId != 0L) {
          TelegramCandidateTarget(
            chatId = chat.id,
            title = "Tin nhắn đã lưu (Saved Messages)",
            targetType = SyncTargetType.SAVED_MESSAGES,
            isForumEnabled = false,
            subtitleVi = "Lưu trữ cá nhân riêng tư mặc định của bạn",
          )
        } else {
          null
        }
      }

      is TdApi.ChatTypeBasicGroup -> {
        val bg = basicGroup ?: return null
        if (!bg.isActive || bg.upgradedToSupergroupId != 0L) return null
        val hasUploadPermission = when (val status = bg.status) {
          is TdApi.ChatMemberStatusCreator -> true
          is TdApi.ChatMemberStatusAdministrator -> true
          is TdApi.ChatMemberStatusMember -> chat.permissions?.canSendDocuments != false
          is TdApi.ChatMemberStatusRestricted -> status.permissions.canSendDocuments
          else -> false
        }
        if (!hasUploadPermission) return null
        TelegramCandidateTarget(
          chatId = chat.id,
          title = chat.title,
          targetType = SyncTargetType.BASIC_GROUP,
          isForumEnabled = false,
          subtitleVi = "Nhóm thường • Tải lên trực tiếp, giữ nguyên chất lượng gốc",
        )
      }

      is TdApi.ChatTypeSupergroup -> {
        val sg = supergroup ?: return null
        val hasPublicUsername =
          !sg.usernames?.activeUsernames.isNullOrEmpty() ||
            !sg.usernames?.editableUsername.isNullOrBlank()
        if (hasPublicUsername) return null

        val hasUploadPermission = when (val status = sg.status) {
          is TdApi.ChatMemberStatusCreator -> true
          is TdApi.ChatMemberStatusAdministrator -> {
            if (sg.isChannel) status.rights.canPostMessages else true
          }
          is TdApi.ChatMemberStatusMember -> !sg.isChannel && (chat.permissions?.canSendDocuments != false)
          is TdApi.ChatMemberStatusRestricted -> !sg.isChannel && status.permissions.canSendDocuments
          else -> false
        }
        if (!hasUploadPermission) return null

        if (sg.isChannel) {
          TelegramCandidateTarget(
            chatId = chat.id,
            title = chat.title,
            targetType = SyncTargetType.PRIVATE_CHANNEL,
            isForumEnabled = false,
            subtitleVi = "Kênh riêng tư • Giữ nguyên chất lượng gốc & EXIF",
          )
        } else {
          val forumText = if (sg.isForum) {
            "Siêu nhóm riêng tư (Forum Topics) • Tự động tách chủ đề Ảnh & Video"
          } else {
            "Siêu nhóm riêng tư • Tải lên trực tiếp"
          }
          TelegramCandidateTarget(
            chatId = chat.id,
            title = chat.title,
            targetType = SyncTargetType.PRIVATE_SUPERGROUP,
            isForumEnabled = sg.isForum,
            subtitleVi = forumText,
          )
        }
      }

      else -> null
    }
  }

  fun targetPriorityRank(target: TelegramCandidateTarget): Int = when (target.targetType) {
    SyncTargetType.PRIVATE_SUPERGROUP -> if (target.isForumEnabled) 0 else 1
    SyncTargetType.BASIC_GROUP -> 2
    SyncTargetType.PRIVATE_CHANNEL -> 3
    SyncTargetType.SAVED_MESSAGES -> 4
  }

  fun sortTargetsByPriority(targets: List<TelegramCandidateTarget>): List<TelegramCandidateTarget> =
    targets.sortedWith(
      compareBy<TelegramCandidateTarget> { targetPriorityRank(it) }
        .thenBy { it.title.lowercase() }
    )
}
