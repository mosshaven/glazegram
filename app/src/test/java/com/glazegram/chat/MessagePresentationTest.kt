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

    @Test
    fun consecutiveSameSenderMessagesJoinIntoCluster() {
        // newest first: 12 -> 11 -> 10, same sender, within 5 minutes
        val messages = listOf(
            message(12).copy(date = 300, senderKey = "u:1"),
            message(11).copy(date = 290, senderKey = "u:1"),
            message(10).copy(date = 280, senderKey = "u:1"),
        )

        val items = groupMessageAlbums(messages)

        assertTrue(items.all { it is MessageListItem.Single })
        val heads = items.filter { !it.joinsNewer }
        val tails = items.filter { !it.joinsOlder }
        assertEquals(listOf(12L), heads.map { it.messages.first().id }) // only newest renders header
        assertEquals(listOf(10L), tails.map { it.messages.first().id }) // only oldest keeps tail corners
    }

    @Test
    fun differentSenderOrTimeBreaksCluster() {
        val differentSender = groupMessageAlbums(
            listOf(
                message(2).copy(date = 1000, senderKey = "u:2"),
                message(1).copy(date = 990, senderKey = "u:1"),
            ),
        )
        assertTrue(differentSender.none { it.joinsNewer || it.joinsOlder })

        val tooOld = groupMessageAlbums(
            listOf(
                message(2).copy(date = 5000, senderKey = "u:1"),
                message(1).copy(date = 100, senderKey = "u:1"),
            ),
        )
        assertTrue(tooOld.none { it.joinsNewer || it.joinsOlder })
    }

    @Test
    fun serviceMessageAlwaysBreaksCluster() {
        val items = groupMessageAlbums(
            listOf(
                message(3).copy(date = 300, senderKey = "u:1", isService = true),
                message(2).copy(date = 290, senderKey = "u:1"),
                message(1).copy(date = 280, senderKey = "u:1"),
            ),
        )

        assertTrue(!items[0].joinsNewer && !items[0].joinsOlder)
        assertTrue(!items[1].joinsNewer)
    }

    @Test
    fun sixSameSenderFormsOneCluster() {
        val ordered = listOf(
            message(6).copy(date = 350, senderKey = "u:1"),
            message(5).copy(date = 340, senderKey = "u:1"),
            message(4).copy(date = 330, senderKey = "u:1"),
            message(3).copy(date = 320, senderKey = "u:1"),
            message(2).copy(date = 310, senderKey = "u:1"),
            message(1).copy(date = 300, senderKey = "u:1"),
        )
        val items = groupMessageAlbums(ordered)
        println("six same sender items: ${items.map { it.messages.first().id to (it.joinsNewer to it.joinsOlder) }}")
        assertEquals(6, items.size)
        // visual mapping: oldest (top) is cluster-start (!joinsOlder), newest (bottom) is cluster-end (!joinsNewer)
        assertTrue(!items[0].joinsNewer && items[0].joinsOlder) // newest (bottom) end
        assertTrue(items[1].joinsNewer && items[1].joinsOlder)
        assertTrue(items[2].joinsNewer && items[2].joinsOlder)
        assertTrue(items[3].joinsNewer && items[3].joinsOlder)
        assertTrue(items[4].joinsNewer && items[4].joinsOlder)
        assertTrue(items[5].joinsNewer && !items[5].joinsOlder) // oldest (top) start
        // no arbitrary max 4
        assertTrue(items.all { it.joinsNewer || it.joinsOlder || it.messages.first().id == 6L || it.messages.first().id == 1L })
    }

    @Test
    fun senderChangeAfterSixCreatesBoundary() {
        val six = listOf(
            message(7).copy(date = 360, senderKey = "u:1"),
            message(6).copy(date = 350, senderKey = "u:1"),
            message(5).copy(date = 340, senderKey = "u:1"),
            message(4).copy(date = 330, senderKey = "u:1"),
            message(3).copy(date = 320, senderKey = "u:1"),
            message(2).copy(date = 310, senderKey = "u:1"),
            message(1).copy(date = 300, senderKey = "u:2"),
        )
        // newest first: 7..1, last is different sender
        val items = groupMessageAlbums(six)
        // boundary between 2 (u:1) and 1 (u:2) must break
        assertTrue(!items[5].joinsOlder) // item 2 (u:1) should not join older different sender
        assertTrue(!items[6].joinsNewer) // item 1 (u:2) should not join newer
    }

    @Test
    fun timeThresholdBreaksClusterEvenWithSameSender() {
        val items = groupMessageAlbums(
            listOf(
                message(2).copy(date = 1000, senderKey = "u:1"),
                message(1).copy(date = 600, senderKey = "u:1"), // diff 400 >300
            ),
        )
        assertTrue(items.none { it.joinsNewer || it.joinsOlder })
    }

    @Test
    fun albumBetweenSameSenderKeepsClusterWhenCompatible() {
        // sequence: single, album (2 messages same sender), single, all same sender within window
        val albumMembers = listOf(
            message(4).copy(date = 330, senderKey = "u:1", mediaAlbumId = 99),
            message(3).copy(date = 320, senderKey = "u:1", mediaAlbumId = 99),
        )
        val ordered = listOf(
            message(5).copy(date = 340, senderKey = "u:1"),
            message(4).copy(date = 330, senderKey = "u:1", mediaAlbumId = 99),
            message(3).copy(date = 320, senderKey = "u:1", mediaAlbumId = 99),
            message(2).copy(date = 310, senderKey = "u:1"),
            message(1).copy(date = 300, senderKey = "u:1"),
        )
        val items = groupMessageAlbums(ordered)
        // should be 3 items: single, album, 2 singles? Actually 5 messages with album grouping -> 4 items: single5, album(4,3), single2, single1
        assertEquals(4, items.size)
        // album should join both neighbors since same sender within window
        val albumIndex = 1
        assertTrue(items[albumIndex].joinsNewer && items[albumIndex].joinsOlder)
        // neighbors also join
        assertTrue(items[0].joinsOlder)
        assertTrue(items[2].joinsNewer && items[2].joinsOlder)
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
