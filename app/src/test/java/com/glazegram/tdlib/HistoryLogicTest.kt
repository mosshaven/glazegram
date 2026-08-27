package com.glazegram.tdlib

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production [HistoryMerge] and [HistoryPolicy] units used by `TdLibRuntime`. */
class HistoryLogicTest {

    private fun message(id: Long, date: Int = id.toInt()): TdApi.Message =
        TdApi.Message().also {
            it.id = id
            it.chatId = 1L
            it.date = date
        }

    private fun pendingMessage(id: Long, date: Int): TdApi.Message =
        message(id, date).also { it.sendingState = TdApi.MessageSendingStatePending() }

    // ---- merge --------------------------------------------------------------

    @Test
    fun mergeDeduplicatesByIdNewestFirst() {
        val existing = arrayOf(message(3L), message(2L), message(1L))
        val incoming = listOf(message(2L), message(4L))

        val merged = HistoryMerge.merge(existing, incoming)

        assertEquals(listOf(4L, 3L, 2L, 1L), merged.map { it.id })
    }

    @Test
    fun mergeLetsIncomingWinOverRetainedCopy() {
        val existing = arrayOf(message(1L, date = 10))
        val updated = message(1L, date = 99)

        val merged = HistoryMerge.merge(existing, listOf(updated))

        assertEquals(1, merged.size)
        assertEquals(99, merged.single().date)
    }

    @Test
    fun unsentMessagesSortAboveEverythingElse() {
        val merged = HistoryMerge.merge(
            arrayOf(message(10L, date = 100)),
            listOf(pendingMessage(2L, date = 1)),
        )

        assertEquals(listOf(2L, 10L), merged.map { it.id })
    }

    @Test
    fun replaceSwapsTemporaryMessageForTheFinalOne() {
        val existing = arrayOf(pendingMessage(900L, date = 50), message(10L, date = 10))

        val replaced = HistoryMerge.replace(existing, oldMessageId = 900L, message = message(11L, date = 50))

        assertEquals(listOf(11L, 10L), replaced.map { it.id })
        assertFalse(replaced.any { it.id == 900L })
    }

    // ---- older-page anchor --------------------------------------------------

    @Test
    fun anchorIsTheLowestRetainedId() {
        val retained = arrayOf(message(30L), message(20L), message(10L))

        assertEquals(10L, HistoryMerge.oldestId(retained))
    }

    @Test
    fun anchorIgnoresUnsentMessages() {
        // A yet-unsent message carries an id that does not follow chat history order.
        val retained = arrayOf(pendingMessage(1L, date = 100), message(30L), message(20L))

        assertEquals(20L, HistoryMerge.oldestId(retained))
    }

    @Test
    fun anchorIsAbsentWhenNothingUsableIsRetained() {
        assertNull(HistoryMerge.oldestId(emptyArray()))
        assertNull(HistoryMerge.oldestId(arrayOf(pendingMessage(5L, date = 1))))
    }

    // ---- end-of-history policy ---------------------------------------------

    @Test
    fun endIsDecidedByProgressAgainstTheAnchorNotByPageSize() {
        // A full page with progress and a short page with progress are both "not the end".
        assertFalse(HistoryPolicy.endReachedAfterOlder(HistoryPolicy.INITIAL_PAGE_SIZE))
        assertFalse(HistoryPolicy.endReachedAfterOlder(1))
        // Only zero progress ends history.
        assertTrue(HistoryPolicy.endReachedAfterOlder(0))
    }

    @Test
    fun countOlderThanCountsOnlyMessagesBelowTheAnchor() {
        val anchor = 500L
        val returned = listOf(700L, 500L, 499L, 12L, 0L, -1L)

        assertEquals(2, HistoryPolicy.countOlderThan(anchor, returned))
    }

    // ---- retention ---------------------------------------------------------

    @Test
    fun activeChatMayExceedTheRetainedMessageCap() {
        val overCap = HistoryPolicy.MAX_MESSAGES_PER_CHAT + 50

        assertFalse(HistoryPolicy.canTrim(active = true, retainedMessages = overCap))
        assertTrue(HistoryPolicy.canTrim(active = false, retainedMessages = overCap))
        assertFalse(HistoryPolicy.canTrim(active = false, retainedMessages = HistoryPolicy.MAX_MESSAGES_PER_CHAT))
    }

    @Test
    fun inactiveChatCompactsToTheNewestMessages() {
        val messages = (1..200).map { message((201 - it).toLong(), date = 201 - it) }.toTypedArray()

        val trimmed = HistoryMerge.trimToNewest(messages, HistoryPolicy.MAX_MESSAGES_PER_CHAT)

        assertEquals(HistoryPolicy.MAX_MESSAGES_PER_CHAT, trimmed.size)
        assertEquals(200L, trimmed.first().id) // newest kept
        assertEquals(51L, trimmed.last().id) // oldest 50 dropped
    }

    @Test
    fun trimIsANoOpBelowTheCap() {
        val messages = arrayOf(message(2L), message(1L))

        assertSameIds(messages, HistoryMerge.trimToNewest(messages, HistoryPolicy.MAX_MESSAGES_PER_CHAT))
    }

    @Test
    fun aroundMessageTargetSurvivesWhileTheChatIsActive() {
        // 150 recent messages retained, then reply navigation pulls in a much older target.
        val recent = (1..HistoryPolicy.MAX_MESSAGES_PER_CHAT)
            .map { message((1000 + it).toLong(), date = 1000 + it) }
            .toTypedArray()
        val target = message(500L, date = 500)

        val merged = HistoryMerge.merge(recent, listOf(target))
        assertTrue(merged.size > HistoryPolicy.MAX_MESSAGES_PER_CHAT)
        assertFalse(HistoryPolicy.canTrim(active = true, retainedMessages = merged.size))

        // The target is the oldest row, so a trim would drop it – which only happens on close.
        assertEquals(500L, merged.last().id)
        val compacted = HistoryMerge.trimToNewest(merged, HistoryPolicy.MAX_MESSAGES_PER_CHAT)
        assertFalse(compacted.any { it.id == 500L })
    }

    @Test
    fun onlyInactiveChatsAreEvicted() {
        val overCap = HistoryPolicy.MAX_RETAINED_CHATS + 1

        assertFalse(HistoryPolicy.shouldEvict(active = true, retainedChats = overCap))
        assertTrue(HistoryPolicy.shouldEvict(active = false, retainedChats = overCap))
        assertFalse(HistoryPolicy.shouldEvict(active = false, retainedChats = HistoryPolicy.MAX_RETAINED_CHATS))
    }

    // ---- media prefetch provenance -----------------------------------------

    @Test
    fun warmupIngestionNeverRequestsMedia() {
        assertFalse(HistoryLoadSource.WARMUP.requestsMedia)
    }

    @Test
    fun visibleIngestionMayRequestMedia() {
        listOf(
            HistoryLoadSource.INITIAL,
            HistoryLoadSource.REFRESH,
            HistoryLoadSource.OLDER,
            HistoryLoadSource.CONTEXT,
            HistoryLoadSource.REALTIME,
        ).forEach { assertTrue(it.name, it.requestsMedia) }
    }

    // ---- initial-loading visibility ----------------------------------------

    @Test
    fun initialSpinnerShowsBeforeAnyViewportIsReady() {
        assertTrue(HistoryPolicy.initialLoadingVisible(initialInFlight = true, initialReady = false))
    }

    @Test
    fun initialSpinnerHidesOnceALocalViewportIsPublishedWhileNetworkStillRuns() {
        // Local page produced a usable viewport; the INITIAL request is still in flight.
        assertFalse(HistoryPolicy.initialLoadingVisible(initialInFlight = true, initialReady = true))
    }

    @Test
    fun initialSpinnerHiddenWhenNoInitialRequestIsInFlight() {
        assertFalse(HistoryPolicy.initialLoadingVisible(initialInFlight = false, initialReady = false))
        assertFalse(HistoryPolicy.initialLoadingVisible(initialInFlight = false, initialReady = true))
    }

    private fun assertSameIds(expected: Array<TdApi.Message>, actual: Array<TdApi.Message>) {
        assertEquals(expected.map { it.id }, actual.map { it.id })
    }
}
