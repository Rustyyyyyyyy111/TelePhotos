package com.laughing.telephotos.data.telegram

import com.laughing.telephotos.data.local.SyncTargetType
import org.drinkless.tdlib.TdApi

/**
 * Enforces the strict "Upload + Read-Only" Telegram safety policy and target allowlist.
 */
object TelegramPolicyGuard {

  const val TRASH_TOPIC_TITLE = "Đã xoá gần đây"
  const val FLOOD_WAIT_SAFETY_BUFFER_SEC = 5

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
      else -> msg.ifBlank { "Đã xảy ra lỗi kết nối Telegram" }
    }
  }

  fun classifyAllowedTarget(
    chat: TdApi.Chat,
    myUserId: Long,
    supergroup: TdApi.Supergroup?,
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
          is TdApi.ChatMemberStatusMember -> !sg.isChannel
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
            "Siêu nhóm riêng tư (Hỗ trợ Chủ đề / Album)"
          } else {
            "Siêu nhóm riêng tư"
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
}
