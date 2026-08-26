package com.glazegram.tdlib

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production [HistoryCoordinator] used by `TdLibRuntime`. */
class HistoryCoordinatorTest {

    private fun message(id: Long, date: Int = id.toInt()): TdApi.Message =
        TdApi.Message().also {
            it.id = id
            it.chatId = CHAT
            it.date = date
        }

    // ---- A. realtime before the initial page -------------------------------

    @Test
    fun firstRealtimeMessageIsBufferedWhenNoHistoryExists() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)

        val decision = coordinator.classifyRealtime(
            chatId = CHAT,
            messageId = 100L,
            alreadyRetained = false,
            hasRetainedHistory = false,
        )

        assertEquals(RealtimeDecision.BUFFER, decision)
    }

    @Test
    fun realtimeWhileInitialInFlightIsBufferedNotDropped() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        coordinator.begin(CHAT, HistorySlot.INITIAL)

        val decision = coordinator.classifyRealtime(CHAT, 100L, false, false)
        assertEquals(RealtimeDecision.BUFFER, decision)
        coordinator.buffer(CHAT, message(100L))

        assertEquals(1, coordinator.pendingCount(CHAT))
    }

    @Test
    fun duplicateRealtimeMessageIsNotBufferedTwice() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        coordinator.buffer(CHAT, message(100L))

        assertEquals(RealtimeDecision.DROP, coordinator.classifyRealtime(CHAT, 100L, false, false))
        // Already merged into the store: also a drop, from the other input.
        assertEquals(RealtimeDecision.DROP, coordinator.classifyRealtime(CHAT, 100L, true, true))
        assertEquals(1, coordinator.pendingCount(CHAT))
    }

    @Test
    fun bufferedMessageDrainsIntoInitialHistoryExactlyOnce() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        val request = coordinator.begin(CHAT, HistorySlot.INITIAL)!!
        coordinator.buffer(CHAT, message(100L))

        assertTrue(coordinator.markInitialReady(request))
        val drained = coordinator.drainPending(CHAT)

        assertEquals(listOf(100L), drained.map { it.id })
        assertEquals(0, coordinator.pendingCount(CHAT))
        assertTrue(coordinator.drainPending(CHAT).isEmpty())
        // Once the viewport exists, realtime goes straight in.
        assertEquals(RealtimeDecision.MERGE, coordinator.classifyRealtime(CHAT, 101L, false, true))
    }

    @Test
    fun realtimeBufferIsBounded() {
        val coordinator = HistoryCoordinator(maxPendingRealtime = 3)
        coordinator.open(CHAT)
        (1L..10L).forEach { coordinator.buffer(CHAT, message(it)) }

        assertEquals(3, coordinator.pendingCount(CHAT))
        assertEquals(listOf(8L, 9L, 10L), coordinator.drainPending(CHAT).map { it.id })
    }

    // ---- B. request slots ---------------------------------------------------

    @Test
    fun initialRequestCannotInvalidateOlderRequest() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        val older = coordinator.begin(CHAT, HistorySlot.OLDER)!!
        val initial = coordinator.begin(CHAT, HistorySlot.INITIAL)!!

        coordinator.completeInitial(initial, InitialOutcome.LOADED)

        assertNotNull(coordinator.owner(older))
        assertTrue(coordinator.finish(older))
        assertFalse(coordinator.peek(CHAT)!!.anyLoading)
    }

    @Test
    fun refreshRequestCannotInvalidateOlderRequest() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        val older = coordinator.begin(CHAT, HistorySlot.OLDER)!!
        val refresh = coordinator.begin(CHAT, HistorySlot.REFRESH)!!

        assertTrue(coordinator.finish(refresh))

        assertTrue(coordinator.peek(CHAT)!!.isLoading(HistorySlot.OLDER))
        assertNotNull(coordinator.owner(older))
        assertTrue(coordinator.finish(older))
    }

    @Test
    fun olderRequestCannotInvalidateInitialRequest() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        val initial = coordinator.begin(CHAT, HistorySlot.INITIAL)!!
        val older = coordinator.begin(CHAT, HistorySlot.OLDER)!!

        assertEquals(HistoryBoundary.END_REACHED, coordinator.completeOlder(older, olderThanAnchor = 0))

        assertNotNull(coordinator.owner(initial))
        assertTrue(coordinator.peek(CHAT)!!.isLoading(HistorySlot.INITIAL))
        assertTrue(coordinator.completeInitial(initial, InitialOutcome.LOADED))
    }

    @Test
    fun supersedingRequestInSameSlotMakesPreviousRequestStale() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        val first = coordinator.begin(CHAT, HistorySlot.OLDER)!!
        assertTrue(coordinator.finish(first))
        val second = coordinator.begin(CHAT, HistorySlot.OLDER)!!

        assertNull(coordinator.owner(first))
        assertNotNull(coordinator.owner(second))
    }

    @Test
    fun staleRequestCompletionCannotClearNewerRequestLoadingState() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        val stale = coordinator.begin(CHAT, HistorySlot.OLDER)!!
        coordinator.finish(stale)
        val current = coordinator.begin(CHAT, HistorySlot.OLDER)!!

        assertFalse(coordinator.finish(stale))
        assertNull(coordinator.completeOlder(stale, olderThanAnchor = 0))
        assertFalse(coordinator.markInitialReady(stale))

        assertTrue(coordinator.peek(CHAT)!!.isLoading(HistorySlot.OLDER))
        assertTrue(coordinator.finish(current))
        assertFalse(coordinator.peek(CHAT)!!.isLoading(HistorySlot.OLDER))
    }

    @Test
    fun everyCompletionPathReleasesItsSlot() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)

        // success
        coordinator.completeInitial(coordinator.begin(CHAT, HistorySlot.INITIAL)!!, InitialOutcome.LOADED)
        // empty result
        coordinator.completeInitial(coordinator.begin(CHAT, HistorySlot.INITIAL)!!, InitialOutcome.EMPTY)
        // failure
        coordinator.completeInitial(coordinator.begin(CHAT, HistorySlot.INITIAL)!!, InitialOutcome.FAILED)
        // transport failure on the other slots
        coordinator.finish(coordinator.begin(CHAT, HistorySlot.REFRESH)!!)
        coordinator.finish(coordinator.begin(CHAT, HistorySlot.WARMUP)!!)
        coordinator.completeOlder(coordinator.begin(CHAT, HistorySlot.OLDER)!!, olderThanAnchor = 4)

        assertFalse(coordinator.peek(CHAT)!!.anyLoading)
    }

    @Test
    fun failedInitialStaysUnreadySoReopeningRetries() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        val request = coordinator.begin(CHAT, HistorySlot.INITIAL)!!

        assertTrue(coordinator.completeInitial(request, InitialOutcome.FAILED))

        val state = coordinator.peek(CHAT)!!
        assertFalse(state.initialReady)
        assertFalse(state.isLoading(HistorySlot.INITIAL))
        assertNotNull(coordinator.begin(CHAT, HistorySlot.INITIAL))
    }

    // ---- C. eviction and stale callbacks ------------------------------------

    @Test
    fun peekOfUnknownChatDoesNotCreateState() {
        val coordinator = HistoryCoordinator()

        assertNull(coordinator.peek(CHAT))
        assertEquals(0, coordinator.retainedChats())
        assertNull(coordinator.begin(CHAT, HistorySlot.INITIAL))
        assertEquals("unknown chat", coordinator.olderRequestBlocked(CHAT))
        assertFalse(coordinator.markLoadableIfUnknown(CHAT))
        assertNull(coordinator.peek(CHAT))
    }

    @Test
    fun staleWarmupCallbackCannotResurrectEvictedChat() {
        val coordinator = HistoryCoordinator(maxRetainedChats = 2)
        coordinator.ensure(CHAT)
        coordinator.touch(CHAT)
        val warmup = coordinator.begin(CHAT, HistorySlot.WARMUP)!!

        coordinator.ensure(2L); coordinator.touch(2L)
        coordinator.ensure(3L)
        assertEquals(listOf(CHAT), coordinator.touch(3L))

        // The callback lands after eviction: it must simply die.
        assertNull(coordinator.owner(warmup))
        assertFalse(coordinator.finish(warmup))
        coordinator.holdWarmup(CHAT, untilMs = 1_000L)
        assertNull(coordinator.peek(CHAT))
        assertEquals(2, coordinator.retainedChats())
    }

    @Test
    fun reopenedChatRejectsCallbacksFromThePreviousIncarnation() {
        val coordinator = HistoryCoordinator(maxRetainedChats = 1)
        coordinator.ensure(CHAT)
        coordinator.touch(CHAT)
        val request = coordinator.begin(CHAT, HistorySlot.INITIAL)!!
        coordinator.ensure(2L)
        coordinator.touch(2L)

        coordinator.open(CHAT) // fresh epoch

        assertNull(coordinator.owner(request))
        assertFalse(coordinator.peek(CHAT)!!.isLoading(HistorySlot.INITIAL))
    }

    // ---- D. older boundary --------------------------------------------------

    @Test
    fun genuinelyOlderMessagesKeepBoundaryLoadable() {
        val coordinator = readyChat()
        val request = coordinator.begin(CHAT, HistorySlot.OLDER)!!
        val returned = listOf(400L, 300L, 200L)

        val progress = HistoryPolicy.countOlderThan(ANCHOR, returned)
        assertEquals(3, progress)
        assertEquals(HistoryBoundary.CAN_LOAD, coordinator.completeOlder(request, progress))
        assertNull(coordinator.olderRequestBlocked(CHAT))
    }

    @Test
    fun shortPageWithProgressIsNotEndOfHistory() {
        val coordinator = readyChat()
        val request = coordinator.begin(CHAT, HistorySlot.OLDER)!!
        // Page limit is 50; TDLib answered with 3 genuinely older messages.
        val progress = HistoryPolicy.countOlderThan(ANCHOR, listOf(499L, 498L, 497L))

        assertEquals(HistoryBoundary.CAN_LOAD, coordinator.completeOlder(request, progress))
    }

    @Test
    fun anchorOnlyResponseIsEndOfHistory() {
        val coordinator = readyChat()
        val request = coordinator.begin(CHAT, HistorySlot.OLDER)!!
        val progress = HistoryPolicy.countOlderThan(ANCHOR, listOf(ANCHOR))

        assertEquals(0, progress)
        assertEquals(HistoryBoundary.END_REACHED, coordinator.completeOlder(request, progress))
        assertEquals("endReached", coordinator.olderRequestBlocked(CHAT))
    }

    @Test
    fun duplicatesOnlyResponseIsEndOfHistory() {
        val coordinator = readyChat()
        val request = coordinator.begin(CHAT, HistorySlot.OLDER)!!
        // Everything at or above the anchor is already retained – no progress.
        val progress = HistoryPolicy.countOlderThan(ANCHOR, listOf(ANCHOR, 600L, 700L))

        assertEquals(0, progress)
        assertEquals(HistoryBoundary.END_REACHED, coordinator.completeOlder(request, progress))
    }

    @Test
    fun emptyResponseIsEndOfHistory() {
        val coordinator = readyChat()
        val request = coordinator.begin(CHAT, HistorySlot.OLDER)!!

        val progress = HistoryPolicy.countOlderThan(ANCHOR, emptyList())
        assertEquals(0, progress)
        assertEquals(HistoryBoundary.END_REACHED, coordinator.completeOlder(request, progress))
    }

    @Test
    fun newerMessagesDoNotCountAsOlderProgress() {
        assertFalse(HistoryPolicy.isOlderThan(ANCHOR, ANCHOR))
        assertFalse(HistoryPolicy.isOlderThan(ANCHOR, ANCHOR + 1))
        assertTrue(HistoryPolicy.isOlderThan(ANCHOR, ANCHOR - 1))
        // Ids are never valid at or below zero.
        assertFalse(HistoryPolicy.isOlderThan(ANCHOR, 0L))
        assertEquals(0, HistoryPolicy.countOlderThan(ANCHOR, listOf(600L, 700L, 800L)))
    }

    @Test
    fun paginationIsBlockedUntilInitialPageIsReady() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        assertEquals("not initialReady", coordinator.olderRequestBlocked(CHAT))

        val request = coordinator.begin(CHAT, HistorySlot.OLDER)!!
        assertEquals("older already loading", coordinator.olderRequestBlocked(CHAT))
        coordinator.finish(request)

        coordinator.completeInitial(coordinator.begin(CHAT, HistorySlot.INITIAL)!!, InitialOutcome.LOADED)
        assertNull(coordinator.olderRequestBlocked(CHAT))
    }

    @Test
    fun emptyInitialPageMarksEndOfHistory() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        val request = coordinator.begin(CHAT, HistorySlot.INITIAL)!!

        coordinator.completeInitial(request, InitialOutcome.EMPTY)

        assertEquals(HistoryBoundary.END_REACHED, coordinator.peek(CHAT)!!.boundary)
    }

    // ---- E. retention -------------------------------------------------------

    @Test
    fun activeChatIsNotEvicted() {
        val coordinator = HistoryCoordinator(maxRetainedChats = 2)
        coordinator.open(CHAT) // active
        coordinator.touch(CHAT)
        coordinator.ensure(2L); coordinator.touch(2L)
        coordinator.ensure(3L)

        val evicted = coordinator.touch(3L)

        assertEquals(listOf(2L), evicted)
        assertNotNull(coordinator.peek(CHAT))
    }

    @Test
    fun closingAChatMakesItEvictable() {
        val coordinator = HistoryCoordinator(maxRetainedChats = 1)
        coordinator.open(CHAT)
        coordinator.touch(CHAT)
        coordinator.setActive(CHAT, false)
        coordinator.ensure(2L)

        assertEquals(listOf(CHAT), coordinator.touch(2L))
        assertNull(coordinator.peek(CHAT))
    }

    @Test
    fun clearDropsAllCoordinationState() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        coordinator.buffer(CHAT, message(1L))
        coordinator.touch(CHAT)

        coordinator.clear()

        assertNull(coordinator.peek(CHAT))
        assertEquals(0, coordinator.retainedChats())
        assertEquals(0, coordinator.pendingCount(CHAT))
    }

    // ---- F. warmup ----------------------------------------------------------

    @Test
    fun warmupIsBlockedByActivityLoadingAndCooldown() {
        val coordinator = HistoryCoordinator()
        assertTrue(coordinator.warmupAllowed(CHAT, nowMs = 0L))

        coordinator.open(CHAT)
        assertFalse(coordinator.warmupAllowed(CHAT, nowMs = 0L)) // active

        coordinator.setActive(CHAT, false)
        assertTrue(coordinator.warmupAllowed(CHAT, nowMs = 0L))

        val request = coordinator.begin(CHAT, HistorySlot.INITIAL)!!
        assertFalse(coordinator.warmupAllowed(CHAT, nowMs = 0L)) // loading
        coordinator.finish(request)

        coordinator.holdWarmup(CHAT, untilMs = HistoryPolicy.WARMUP_COOLDOWN_MS)
        assertFalse(coordinator.warmupAllowed(CHAT, nowMs = 0L))
        assertFalse(coordinator.warmupAllowed(CHAT, nowMs = HistoryPolicy.WARMUP_COOLDOWN_MS - 1))
        // Cooldown is bounded: it expires instead of disabling warmup forever.
        assertTrue(coordinator.warmupAllowed(CHAT, nowMs = HistoryPolicy.WARMUP_COOLDOWN_MS))
    }

    @Test
    fun warmupNeverMarksTheViewportReady() {
        val coordinator = HistoryCoordinator()
        coordinator.ensure(CHAT)
        val warmup = coordinator.begin(CHAT, HistorySlot.WARMUP)!!

        assertTrue(coordinator.markLoadableIfUnknown(CHAT))
        coordinator.finish(warmup)

        val state = coordinator.peek(CHAT)!!
        assertFalse(state.initialReady)
        assertEquals(HistoryBoundary.CAN_LOAD, state.boundary)
        // A warmed but never-opened chat must not buffer realtime messages.
        assertEquals(RealtimeDecision.MERGE, coordinator.classifyRealtime(CHAT, 5L, false, true))
        assertEquals(RealtimeDecision.DROP, coordinator.classifyRealtime(CHAT, 5L, false, false))
    }

    @Test
    fun markLoadableIfUnknownNeverDowngradesEndReached() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        coordinator.completeInitial(coordinator.begin(CHAT, HistorySlot.INITIAL)!!, InitialOutcome.EMPTY)

        assertFalse(coordinator.markLoadableIfUnknown(CHAT))
        assertEquals(HistoryBoundary.END_REACHED, coordinator.peek(CHAT)!!.boundary)
    }

    // ---- G. retention trim reopens older history ----------------------------

    @Test
    fun retentionTrimMakesOlderHistoryLoadableAgain() {
        val coordinator = readyChat()
        coordinator.completeOlder(coordinator.begin(CHAT, HistorySlot.OLDER)!!, olderThanAnchor = 0)
        assertEquals("endReached", coordinator.olderRequestBlocked(CHAT))

        // Trimming discarded retained messages, so that history is reachable again.
        assertTrue(coordinator.onRetentionTrimmed(CHAT))

        assertEquals(HistoryBoundary.CAN_LOAD, coordinator.peek(CHAT)!!.boundary)
        assertNull(coordinator.olderRequestBlocked(CHAT))
        assertNotNull(coordinator.begin(CHAT, HistorySlot.OLDER))
        // An unknown chat has nothing to reopen.
        assertFalse(HistoryCoordinator().onRetentionTrimmed(CHAT))
    }

    @Test
    fun trimWhileOlderRequestIsInFlightMakesThatCallbackStale() {
        val coordinator = readyChat()
        val inFlight = coordinator.begin(CHAT, HistorySlot.OLDER)!!

        assertTrue(coordinator.onRetentionTrimmed(CHAT))

        // The response was anchored before the trim: it may not restore the old boundary.
        assertNull(coordinator.owner(inFlight))
        assertNull(coordinator.completeOlder(inFlight, olderThanAnchor = 0))
        assertFalse(coordinator.finish(inFlight))

        val state = coordinator.peek(CHAT)!!
        assertEquals(HistoryBoundary.CAN_LOAD, state.boundary)
        assertFalse(state.isLoading(HistorySlot.OLDER))
        assertNull(coordinator.olderRequestBlocked(CHAT))
        assertNotNull(coordinator.begin(CHAT, HistorySlot.OLDER))
    }

    @Test
    fun retentionTrimOnlyInvalidatesTheOlderSlot() {
        val coordinator = readyChat()
        val initial = coordinator.begin(CHAT, HistorySlot.INITIAL)!!
        val refresh = coordinator.begin(CHAT, HistorySlot.REFRESH)!!

        coordinator.onRetentionTrimmed(CHAT)

        assertNotNull(coordinator.owner(initial))
        assertNotNull(coordinator.owner(refresh))
        assertTrue(coordinator.peek(CHAT)!!.initialReady)
    }

    // ---- H. opening supersedes warmup ---------------------------------------

    @Test
    fun openingAChatInvalidatesItsInFlightWarmup() {
        val coordinator = HistoryCoordinator()
        coordinator.ensure(CHAT)
        val warmup = coordinator.begin(CHAT, HistorySlot.WARMUP)!!

        coordinator.open(CHAT)

        assertNull(coordinator.owner(warmup))
        assertFalse(coordinator.finish(warmup))
        val state = coordinator.peek(CHAT)!!
        assertTrue(state.active)
        // The flag is released rather than stranded, so a later warmup may still run.
        assertFalse(state.isLoading(HistorySlot.WARMUP))
        assertNotNull(coordinator.begin(CHAT, HistorySlot.WARMUP))
    }

    @Test
    fun openingAChatLeavesTheOtherSlotsAndTheViewportIntact() {
        val coordinator = readyChat()
        val initial = coordinator.begin(CHAT, HistorySlot.INITIAL)!!
        val refresh = coordinator.begin(CHAT, HistorySlot.REFRESH)!!
        val older = coordinator.begin(CHAT, HistorySlot.OLDER)!!

        coordinator.open(CHAT)

        assertNotNull(coordinator.owner(initial))
        assertNotNull(coordinator.owner(refresh))
        assertNotNull(coordinator.owner(older))
        // Reopening does not recreate the record, so the viewport stays ready.
        assertTrue(coordinator.peek(CHAT)!!.initialReady)
    }

    // ---- I. warmup states stay bounded --------------------------------------

    @Test
    fun emptyWarmupsStayInsideTheRetentionCap() {
        val coordinator = HistoryCoordinator()
        val total = HistoryPolicy.MAX_RETAINED_CHATS + 5L

        for (chatId in 1L..total) {
            // The runtime enrolls a warmup candidate in the LRU before sending its request.
            coordinator.ensure(chatId)
            coordinator.touch(chatId)
            val warmup = coordinator.begin(chatId, HistorySlot.WARMUP)!!
            // GetChatHistory answered empty: cooldown only, nothing merged, nothing retained.
            coordinator.holdWarmup(chatId, untilMs = HistoryPolicy.WARMUP_COOLDOWN_MS)
            coordinator.finish(warmup)
            assertTrue(coordinator.retainedChats() <= HistoryPolicy.MAX_RETAINED_CHATS)
        }

        var records = 0
        for (chatId in 1L..total) if (coordinator.peek(chatId) != null) records++
        assertEquals(HistoryPolicy.MAX_RETAINED_CHATS, records)
        assertEquals(HistoryPolicy.MAX_RETAINED_CHATS, coordinator.retainedChats())
        assertNull(coordinator.peek(1L))
        assertNotNull(coordinator.peek(total))
        // Cooldown survives on the records that are still held, and still expires.
        assertFalse(coordinator.warmupAllowed(total, nowMs = 0L))
        assertTrue(coordinator.warmupAllowed(total, nowMs = HistoryPolicy.WARMUP_COOLDOWN_MS))
    }

    // ---- J. mutations of not-yet-published messages -------------------------

    @Test
    fun contentUpdateReachesAMessageStillWaitingForTheFirstPage() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        val original = TdApi.MessageText()
        val edited = TdApi.MessageText()
        coordinator.buffer(CHAT, message(100L).also { it.content = original })

        assertTrue(coordinator.updatePendingContent(CHAT, 100L, edited))
        assertFalse(coordinator.updatePendingContent(CHAT, 999L, TdApi.MessageText()))
        assertFalse(coordinator.updatePendingContent(2L, 100L, TdApi.MessageText()))

        val drained = coordinator.drainPending(CHAT).single()
        assertSame(edited, drained.content)
    }

    @Test
    fun deletedBufferedMessageNeverReappearsAfterDrain() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        coordinator.buffer(CHAT, message(100L))
        coordinator.buffer(CHAT, message(101L))

        assertTrue(coordinator.removePending(CHAT, setOf(100L)))
        assertFalse(coordinator.removePending(CHAT, setOf(500L)))
        assertFalse(coordinator.removePending(2L, setOf(100L)))
        assertEquals(1, coordinator.pendingCount(CHAT))
        assertEquals(listOf(101L), coordinator.drainPending(CHAT).map { it.id })

        // Deleting the only buffered message leaves nothing to drain at all.
        coordinator.buffer(CHAT, message(102L))
        assertTrue(coordinator.removePending(CHAT, setOf(102L)))
        assertEquals(0, coordinator.pendingCount(CHAT))
        assertTrue(coordinator.drainPending(CHAT).isEmpty())
    }

    @Test
    fun retainedCopyIsNotOverwrittenByAStaleBufferedDuplicate() {
        val coordinator = HistoryCoordinator()
        coordinator.open(CHAT)
        val request = coordinator.begin(CHAT, HistorySlot.INITIAL)!!
        coordinator.buffer(CHAT, message(100L))
        coordinator.buffer(CHAT, message(101L))

        assertTrue(coordinator.markInitialReady(request))
        // The page being published already holds 100, so only 101 may come from the buffer.
        val drained = coordinator.drainPending(CHAT, retainedIds = setOf(100L))

        assertEquals(listOf(101L), drained.map { it.id })
        assertEquals(0, coordinator.pendingCount(CHAT))
    }

    private fun readyChat(): HistoryCoordinator = HistoryCoordinator().also {
        it.open(CHAT)
        it.completeInitial(it.begin(CHAT, HistorySlot.INITIAL)!!, InitialOutcome.LOADED)
    }

    private companion object {
        const val CHAT = 1L
        const val ANCHOR = 500L
    }
}
