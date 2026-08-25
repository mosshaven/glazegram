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
)

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
