package com.glazegram.tdlib

data class ChatSummary(
    val id: Long,
    val title: String,
    val lastMessageAuthor: String,
    val lastMessage: String,
    val lastMessageTime: String,
    val unreadCount: Int,
    val isPinned: Boolean,
    val order: Long,
    val avatarPath: String?,
    val kind: ChatKind,
    val subtitle: String,
    val canSendMessages: Boolean,
    val unreadMentionCount: Int,
    val lastMessageIsOutgoing: Boolean = false,
    val lastMessageDeliveryState: DeliveryState? = null,
    val isMuted: Boolean = false,
    val isSavedMessages: Boolean = false,
)

/**
 * Composes the single-line chat-list preview using semantic metadata only.
 * Display string "Вы" is presentation-only and never used for branching.
 *
 * Rules:
 * - CHANNEL: bare preview (no sender prefix)
 * - SAVED: bare preview (do not duplicate title)
 * - PRIVATE incoming: preview
 * - PRIVATE outgoing: "Вы: preview"
 * - GROUP / SUPERGROUP incoming: "Author: preview"
 * - GROUP / SUPERGROUP outgoing: "Вы: preview"
 */
fun chatListPreview(
    author: String,
    preview: String,
    kind: ChatKind,
    isOutgoing: Boolean,
    isSavedMessages: Boolean = false,
): String {
    if (preview.isBlank()) return ""
    if (isSavedMessages) return preview
    if (kind == ChatKind.Channel) return preview
    return when {
        isOutgoing -> "Вы: $preview"
        kind == ChatKind.BasicGroup || kind == ChatKind.Supergroup ->
            if (author.isNotBlank()) "$author: $preview" else preview
        else -> preview
    }
}

enum class ChatKind { Private, BasicGroup, Supergroup, Channel, Secret }

data class ChatMessage(
    val chatId: Long,
    val id: Long,
    val author: String,
    val authorAvatarPath: String? = null,
    val text: String,
    val time: String,
    val isOutgoing: Boolean,
    val replyToMessageId: Long?,
    val replyToChatId: Long?,
    val deliveryState: DeliveryState,
    val mediaKind: MediaKind = MediaKind.Text,
    val mediaPreviewPath: String? = null,
    val mediaOpenPath: String? = null,
    val mediaFileId: Int? = null,
    val mediaMimeType: String? = null,
    val mediaLabel: String? = null,
    val mediaAlbumId: Long = 0,
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    val mediaMinithumbnail: ByteArray? = null,
    val textStyles: List<MessageTextStyle> = emptyList(),
    val forwardedFrom: String? = null,
    val containsUnreadMention: Boolean = false,
    val contentPreview: String = "",
    /** Epoch seconds; presentation-layer cluster boundaries only. */
    val date: Int = 0,
    /** Stable sender identity for clustering, e.g. "u:123" / "c:-100…". */
    val senderKey: String = "",
    /** Service/system messages always break clusters. */
    val isService: Boolean = false,
)

data class MessageTextStyle(
    val offset: Int,
    val length: Int,
    val kind: MessageTextStyleKind,
)

enum class MessageTextStyleKind { Bold, Italic, Underline, Strikethrough, Code, Link, Spoiler, Quote }

data class MessageDeleteCapability(
    val forSelf: Boolean,
    val forEveryone: Boolean,
)

enum class MediaKind {
    Text,
    Photo,
    Video,
    VideoNote,
    Animation,
    Audio,
    Document,
    Voice,
    Sticker,
    Location,
    Contact,
    Poll,
    Service,
    Unsupported,
}

enum class DeliveryState {
    Sending,
    Sent,
    Read,
    Failed,
}

data class AccountSummary(
    val name: String,
    val detail: String,
    val avatarPath: String?,
)

sealed interface AuthUiState {
    data object Initializing : AuthUiState
    data class Phone(
        val phoneNumber: String = "",
        val error: String? = null,
        val submitting: Boolean = false,
    ) : AuthUiState
    data class Code(val error: String? = null, val submitting: Boolean = false) : AuthUiState
    data class Password(
        val hint: String,
        val error: String? = null,
        val submitting: Boolean = false,
    ) : AuthUiState
    data object Ready : AuthUiState
    data object LoggingOut : AuthUiState

    fun withError(message: String): AuthUiState = when (this) {
        Initializing -> Phone(error = message)
        is Phone -> copy(error = message, submitting = false)
        is Code -> copy(error = message, submitting = false)
        is Password -> copy(error = message, submitting = false)
        Ready -> this
        LoggingOut -> this
    }
}
