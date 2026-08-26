package com.glazegram.chat

import com.glazegram.tdlib.ChatMessage

/**
 * Presentation item for the message list.
 *
 * Cluster fields follow Telegram behavior (TGA 5-minute rule / TGX sender+time).
 * Display order is newest-first; LazyColumn uses reverseLayout=true:
 * - newer messages are visually BELOW (bottom), older ABOVE (top)
 * - [joinsNewer]: true when this item joins the newer neighbor below it (small bottom gap, tight bottom corners, avatar at bottom of cluster when false)
 * - [joinsOlder]: true when this item joins the older neighbor above it (small top gap, tight top corners, name at top of cluster when false)
 * Visual helpers: joinsMessageAbove = joinsOlder, joinsMessageBelow = joinsNewer.
 * Service messages and albums are atomic but may join neighbors by edge rules.
 * There is NO arbitrary max messages per cluster.
 */
sealed interface MessageListItem {
    val key: String
    val messages: List<ChatMessage>
    val joinsNewer: Boolean
    val joinsOlder: Boolean

    data class Single(
        val message: ChatMessage,
        override val joinsNewer: Boolean = false,
        override val joinsOlder: Boolean = false,
    ) : MessageListItem {
        override val key = "message:${message.chatId}:${message.id}"
        override val messages = listOf(message)
    }

    data class Album(
        val albumId: Long,
        override val messages: List<ChatMessage>,
        override val joinsNewer: Boolean = false,
        override val joinsOlder: Boolean = false,
    ) : MessageListItem {
        override val key = "album:${messages.first().chatId}:$albumId"
    }
}

private const val CLUSTER_WINDOW_SECONDS = 300 // TGA: <=5 minutes

private fun canJoin(older: ChatMessage?, newer: ChatMessage?): Boolean {
    if (older == null || newer == null) return false
    if (older.isService || newer.isService) return false
    if (older.senderKey.isBlank() || older.senderKey != newer.senderKey) return false
    if (older.isOutgoing != newer.isOutgoing) return false
    if (older.forwardedFrom != newer.forwardedFrom) return false
    return newer.date - older.date in 0..CLUSTER_WINDOW_SECONDS
}

/**
 * Groups media albums (presentation-only) and annotates items with cluster
 * membership for consecutive same-sender messages. Canonical order is
 * preserved; storage identity of every message is untouched.
 */
fun groupMessageAlbums(messages: List<ChatMessage>): List<MessageListItem> {
    val albums = messages.asSequence()
        .filter { it.mediaAlbumId != 0L }
        .groupBy { it.chatId to it.mediaAlbumId }
    val emitted = HashSet<Pair<Long, Long>>()

    data class Slot(val item: MessageListItem, val edgeTop: ChatMessage?, val edgeBottom: ChatMessage?)

    val slots = buildList {
        for (message in messages) {
            val albumId = message.mediaAlbumId
            val albumKey = message.chatId to albumId
            if (albumId == 0L) {
                add(Slot(MessageListItem.Single(message), message, message))
            } else if (emitted.add(albumKey)) {
                val members = albums.getValue(albumKey)
                // members keep canonical newest-first order
                add(Slot(MessageListItem.Album(albumId, members), members.first(), members.last()))
            }
        }
    }

    return slots.mapIndexed { index, slot ->
        val newerNeighbor = slots.getOrNull(index - 1)
        val olderNeighbor = slots.getOrNull(index + 1)
        // Display order is newest-first: neighbor at index-1 is newer.
        val joinsNewer = canJoin(slot.edgeTop, newerNeighbor?.edgeBottom)
        val joinsOlder = canJoin(olderNeighbor?.edgeTop, slot.edgeBottom)
        when (val item = slot.item) {
            is MessageListItem.Single -> item.copy(joinsNewer = joinsNewer, joinsOlder = joinsOlder)
            is MessageListItem.Album -> item.copy(joinsNewer = joinsNewer, joinsOlder = joinsOlder)
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
