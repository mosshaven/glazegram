package com.glazegram.tdlib

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private fun readyChat(): HistoryCoordinator = HistoryCoordinator().also {
        it.open(CHAT)
        it.completeInitial(it.begin(CHAT, HistorySlot.INITIAL)!!, InitialOutcome.LOADED)
    }

    private companion object {
        const val CHAT = 1L
        const val ANCHOR = 500L
    }
}
