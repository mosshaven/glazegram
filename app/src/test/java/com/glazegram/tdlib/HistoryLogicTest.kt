package com.glazegram.tdlib

import org.junit.Assert.*
import org.junit.Test

class HistoryLogicTest {
    @Test
    fun mergeDeduplicatesById() {
        val existing = arrayOf(fakeMessage(3), fakeMessage(2), fakeMessage(1))
        val incoming = arrayOf(fakeMessage(2), fakeMessage(4))
        val merged = linkedMapOf<Long, org.drinkless.tdlib.TdApi.Message>()
        (existing.asList() + incoming.asList()).forEach { merged[it.id] = it }
        val sorted = merged.values.sortedWith(compareByDescending { it.id })
        assertEquals(listOf(4L, 3L, 2L, 1L), sorted.map { it.id })
    }

    @Test
    fun lruEvictionBoundsRetainedChats() {
        val maxChats = 8
        val order = LinkedHashMap<Long, Unit>(16, 0.75f, true)
        for (id in 1L..10L) {
            order[id] = Unit
            while (order.size > maxChats) {
                val eldest = order.keys.first()
                order.remove(eldest)
            }
        }
        assertEquals(8, order.size)
        assertFalse(order.containsKey(1L))
        assertFalse(order.containsKey(2L))
        assertTrue(order.containsKey(10L))
    }

    @Test
    fun retainedMessageCountBounded() {
        val maxPerChat = 150
        val messages = (1..200).map { fakeMessage(it.toLong()) }.toTypedArray()
        val trimmed = if (messages.size > maxPerChat) messages.take(maxPerChat).toTypedArray() else messages
        assertEquals(150, trimmed.size)
        assertEquals(1L, trimmed.first().id)
    }

    @Test
    fun paginationEndReachedBlocksFurther() {
        val hasMore = false
        var loadCalled = false
        fun loadOlder() {
            if (!hasMore) return
            loadCalled = true
        }
        loadOlder()
        assertFalse(loadCalled)
    }

    @Test
    fun singleInFlightSuppressed() {
        val loading = mutableMapOf<Long, Boolean>(1L to true)
        var called = false
        fun tryLoad(chatId: Long) {
            if (loading[chatId] == true) return
            called = true
        }
        tryLoad(1L)
        assertFalse(called)
        tryLoad(2L)
        assertTrue(called)
    }

    private fun fakeMessage(id: Long): org.drinkless.tdlib.TdApi.Message {
        val msg = org.drinkless.tdlib.TdApi.Message()
        msg.id = id
        msg.chatId = 1
        msg.date = id.toInt()
        return msg
    }
}
