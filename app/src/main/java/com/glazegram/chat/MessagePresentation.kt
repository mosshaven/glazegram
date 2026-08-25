package com.glazegram.chat

import com.glazegram.tdlib.ChatMessage

sealed interface MessageListItem {
    val key: String
    val messages: List<ChatMessage>

    data class Single(val message: ChatMessage) : MessageListItem {
        override val key = "message:${message.chatId}:${message.id}"
        override val messages = listOf(message)
    }

    data class Album(val albumId: Long, override val messages: List<ChatMessage>) : MessageListItem {
        override val key = "album:${messages.first().chatId}:$albumId"
    }
}

fun groupMessageAlbums(messages: List<ChatMessage>): List<MessageListItem> {
    val albums = messages.asSequence()
        .filter { it.mediaAlbumId != 0L }
        .groupBy { it.chatId to it.mediaAlbumId }
    val emitted = HashSet<Pair<Long, Long>>()

    return buildList {
        for (message in messages) {
            val albumId = message.mediaAlbumId
            val albumKey = message.chatId to albumId
            if (albumId == 0L) {
                add(MessageListItem.Single(message))
            } else if (emitted.add(albumKey)) {
                add(MessageListItem.Album(albumId, albums.getValue(albumKey)))
            }
        }
    }
}

fun MessageListItem.containsMessage(messageId: Long): Boolean = messages.any { it.id == messageId }

fun resolveReplyTarget(
    message: ChatMessage,
    loadedMessages: List<ChatMessage>,
    referencedMessages: Map<Long, ChatMessage?>,
): ChatMessage? {
    val replyId = message.replyToMessageId ?: return null
    val replyChatId = message.replyToChatId?.takeIf { it != 0L } ?: message.chatId
    return loadedMessages.firstOrNull { it.chatId == replyChatId && it.id == replyId } ?: referencedMessages[replyId]
}
