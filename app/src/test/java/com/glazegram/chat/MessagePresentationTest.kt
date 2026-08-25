package com.glazegram.chat

import com.glazegram.tdlib.ChatMessage
import com.glazegram.tdlib.DeliveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePresentationTest {
    @Test
    fun groupsAlbumWithoutChangingSurroundingOrder() {
        val messages = listOf(message(6), message(5, 10), message(4, 10), message(3), message(2, 20), message(1, 20))

        val items = groupMessageAlbums(messages)

        assertEquals(4, items.size)
        assertEquals(6, (items[0] as MessageListItem.Single).message.id)
        assertEquals(listOf(5L, 4L), (items[1] as MessageListItem.Album).messages.map { it.id })
        assertEquals(3, (items[2] as MessageListItem.Single).message.id)
        assertEquals(listOf(2L, 1L), (items[3] as MessageListItem.Album).messages.map { it.id })
    }

    @Test
    fun partialRealtimeAlbumKeepsStableKey() {
        val first = groupMessageAlbums(listOf(message(2, 99))).single()
        val updated = groupMessageAlbums(listOf(message(3, 99), message(2, 99))).single()

        assertEquals(first.key, updated.key)
        assertTrue(updated.containsMessage(3))
    }

    @Test
    fun replyTargetPrefersLoadedMessageAndFallsBackToReference() {
        val reply = message(10)
        val loadedTarget = message(5)
        val referencedTarget = message(4)
        val loadedReply = reply.copy(replyToMessageId = 5)
        val referencedReply = reply.copy(replyToMessageId = 4)

        assertEquals(loadedTarget, resolveReplyTarget(loadedReply, listOf(loadedTarget), mapOf(5L to referencedTarget)))
        assertEquals(referencedTarget, resolveReplyTarget(referencedReply, emptyList(), mapOf(4L to referencedTarget)))
    }

    @Test
    fun replyTargetDoesNotMatchSameIdFromDifferentChat() {
        val reply = message(10).copy(replyToMessageId = 5, replyToChatId = 2)
        val wrongChat = message(5)
        val referencedTarget = message(5).copy(chatId = 2)

        assertEquals(referencedTarget, resolveReplyTarget(reply, listOf(wrongChat), mapOf(5L to referencedTarget)))
    }

    private fun message(id: Long, albumId: Long = 0) = ChatMessage(
        chatId = 1,
        id = id,
        author = "",
        text = "",
        time = "",
        isOutgoing = false,
        replyToMessageId = null,
        replyToChatId = null,
        deliveryState = DeliveryState.Sent,
        mediaAlbumId = albumId,
    )
}
