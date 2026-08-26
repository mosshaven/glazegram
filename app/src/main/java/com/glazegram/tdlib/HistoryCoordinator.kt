package com.glazegram.tdlib

import org.drinkless.tdlib.TdApi

/** Where a batch of messages came from. Decides whether media prefetch is allowed. */
enum class HistoryLoadSource(val requestsMedia: Boolean) {
    INITIAL(true),
    REFRESH(true),
    OLDER(true),
    CONTEXT(true),
    REALTIME(true),
    WARMUP(false),
}

/** How much older history is still reachable for a chat. */
enum class HistoryBoundary { UNKNOWN, CAN_LOAD, END_REACHED }

/**
 * Independent request slots. A request may only be invalidated by another request in the
 * same slot, so an initial, refresh, older or warmup response can never strand a sibling
 * slot's loading flag.
 */
enum class HistorySlot { INITIAL, REFRESH, OLDER, WARMUP }

/** Terminal outcome of the two-stage initial page load. */
enum class InitialOutcome { LOADED, EMPTY, FAILED }

/** What to do with a realtime message that arrived outside a history request. */
enum class RealtimeDecision { BUFFER, MERGE, DROP }

/**
 * Identity of one in-flight TDLib history request: which chat, which slot, which incarnation
 * of the chat state ([epoch]) and which request inside that slot ([token]).
 */
data class HistoryRequest(
    val chatId: Long,
    val slot: HistorySlot,
    val epoch: Long,
    val token: Long,
)

/** Per-chat history coordination record. Mutated only through [HistoryCoordinator]. */
class HistoryState internal constructor(val epoch: Long) {
    var initialReady: Boolean = false
        private set
    var boundary: HistoryBoundary = HistoryBoundary.UNKNOWN
        private set
    var active: Boolean = false
        private set
    var warmupCooldownUntil: Long = 0L
        private set

    private val tokens = LongArray(HistorySlot.entries.size)

    fun isLoading(slot: HistorySlot): Boolean = tokens[slot.ordinal] != NO_TOKEN

    val anyLoading: Boolean get() = tokens.any { it != NO_TOKEN }

    internal fun assign(slot: HistorySlot, token: Long) { tokens[slot.ordinal] = token }
    internal fun owns(slot: HistorySlot, token: Long): Boolean = tokens[slot.ordinal] == token
    internal fun release(slot: HistorySlot) { tokens[slot.ordinal] = NO_TOKEN }
    internal fun markActive(value: Boolean) { active = value }
    internal fun markInitialReady() { initialReady = true }
    internal fun moveBoundary(value: HistoryBoundary) { boundary = value }
    internal fun holdWarmupUntil(value: Long) { warmupCooldownUntil = value }

    private companion object {
        const val NO_TOKEN = 0L
    }
}

/**
 * Owns per-chat history coordination: request ownership, the realtime buffer that keeps a
 * chat from publishing a lone bubble before its first page, and the retained-chat LRU.
 *
 * Not self-synchronizing per call: the runtime mutates this together with its message store,
 * so both live inside a single [withLock] section.
 */
class HistoryCoordinator(
    private val maxRetainedChats: Int = HistoryPolicy.MAX_RETAINED_CHATS,
    private val maxPendingRealtime: Int = HistoryPolicy.MAX_PENDING_REALTIME,
) {
    private val lock = Any()
    private val states = HashMap<Long, HistoryState>()
    private val pendingRealtime = HashMap<Long, MutableList<TdApi.Message>>()
    private val retention = LinkedHashMap<Long, Unit>(16, 0.75f, true)
    private var sequence = 0L

    /** The single serialization boundary for history state: UI thread and TDLib threads meet here. */
    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    // ---- state access ------------------------------------------------------

    /** Existing state, or null. Never creates — a stale callback must not resurrect an evicted chat. */
    fun peek(chatId: Long): HistoryState? = states[chatId]

    fun retainedChats(): Int = retention.size

    /** Marks the chat open, creating its coordination record when absent. */
    fun open(chatId: Long): HistoryState = ensure(chatId).also { it.markActive(true) }

    /** Creates the coordination record without marking the chat open (warmup). */
    fun ensure(chatId: Long): HistoryState = states.getOrPut(chatId) { HistoryState(++sequence) }

    fun setActive(chatId: Long, active: Boolean) {
        states[chatId]?.markActive(active)
    }

    // ---- request ownership -------------------------------------------------

    /** Starts a request in [slot]; null when the chat is unknown or that slot is already busy. */
    fun begin(chatId: Long, slot: HistorySlot): HistoryRequest? {
        val state = states[chatId] ?: return null
        if (state.isLoading(slot)) return null
        val token = ++sequence
        state.assign(slot, token)
        return HistoryRequest(chatId, slot, state.epoch, token)
    }

    /**
     * Validates a callback without creating state. Null when the chat was evicted or closed,
     * when the state was recreated since (epoch) or when a newer request took over the slot
     * (token) — in which case that newer request owns the flag and will clear it.
     */
    fun owner(request: HistoryRequest): HistoryState? {
        val state = states[request.chatId] ?: return null
        if (state.epoch != request.epoch) return null
        if (!state.owns(request.slot, request.token)) return null
        return state
    }

    /** Releases the slot iff [request] still owns it. */
    fun finish(request: HistoryRequest): Boolean {
        val state = owner(request) ?: return false
        state.release(request.slot)
        return true
    }

    // ---- initial page ------------------------------------------------------

    /** Publishes the viewport as soon as a usable page exists, while the request stays in flight. */
    fun markInitialReady(request: HistoryRequest): Boolean {
        val state = owner(request) ?: return false
        state.markInitialReady()
        return true
    }

    fun completeInitial(request: HistoryRequest, outcome: InitialOutcome): Boolean {
        val state = owner(request) ?: return false
        when (outcome) {
            InitialOutcome.LOADED -> {
                state.markInitialReady()
                if (state.boundary == HistoryBoundary.UNKNOWN) {
                    state.moveBoundary(HistoryBoundary.CAN_LOAD)
                }
            }
            InitialOutcome.EMPTY -> {
                state.markInitialReady()
                state.moveBoundary(HistoryBoundary.END_REACHED)
            }
            // Nothing retained and the request failed: stay unready so reopening retries.
            InitialOutcome.FAILED -> Unit
        }
        state.release(request.slot)
        return true
    }

    // ---- older pages -------------------------------------------------------

    /** Suppression reason for an older-page request, or null when it may proceed. */
    fun olderRequestBlocked(chatId: Long): String? {
        val state = states[chatId] ?: return "unknown chat"
        return when {
            state.isLoading(HistorySlot.OLDER) -> "older already loading"
            state.boundary == HistoryBoundary.END_REACHED -> "endReached"
            !state.initialReady -> "not initialReady"
            else -> null
        }
    }

    /**
     * Completes an older page. [olderThanAnchor] is how many returned messages were genuinely
     * older than the request anchor; zero means the boundary is reached, so an anchor-only or
     * all-duplicates answer stops pagination instead of being retried forever.
     */
    fun completeOlder(request: HistoryRequest, olderThanAnchor: Int): HistoryBoundary? {
        val state = owner(request) ?: return null
        state.moveBoundary(
            if (HistoryPolicy.endReachedAfterOlder(olderThanAnchor)) HistoryBoundary.END_REACHED
            else HistoryBoundary.CAN_LOAD,
        )
        state.release(request.slot)
        return state.boundary
    }

    /** A page proves history exists without proving where it ends. */
    fun markLoadableIfUnknown(chatId: Long): Boolean {
        val state = states[chatId] ?: return false
        if (state.boundary != HistoryBoundary.UNKNOWN) return false
        state.moveBoundary(HistoryBoundary.CAN_LOAD)
        return true
    }

    // ---- warmup ------------------------------------------------------------

    fun warmupAllowed(chatId: Long, nowMs: Long): Boolean {
        val state = states[chatId] ?: return true
        return !state.active && !state.anyLoading && state.warmupCooldownUntil <= nowMs
    }

    fun holdWarmup(chatId: Long, untilMs: Long) {
        states[chatId]?.holdWarmupUntil(untilMs)
    }

    // ---- realtime before the first page ------------------------------------

    /**
     * Decides what to do with an incoming message. [alreadyRetained] and [hasRetainedHistory]
     * are facts about the runtime's message store; a chat with no store entry yet must still
     * buffer, which is why absence is passed as false rather than inferred from a null map.
     */
    fun classifyRealtime(
        chatId: Long,
        messageId: Long,
        alreadyRetained: Boolean,
        hasRetainedHistory: Boolean,
    ): RealtimeDecision {
        val state = states[chatId]
            ?: return if (hasRetainedHistory) RealtimeDecision.MERGE else RealtimeDecision.DROP
        if (state.initialReady) return RealtimeDecision.MERGE
        val awaitingViewport = state.active || state.isLoading(HistorySlot.INITIAL)
        if (!awaitingViewport) {
            // Warmed or closed chat without a viewport: keep what we hold fresh, buffer nothing.
            return if (hasRetainedHistory) RealtimeDecision.MERGE else RealtimeDecision.DROP
        }
        if (alreadyRetained) return RealtimeDecision.DROP
        if (pendingRealtime[chatId]?.any { it.id == messageId } == true) return RealtimeDecision.DROP
        return RealtimeDecision.BUFFER
    }

    fun buffer(chatId: Long, message: TdApi.Message) {
        val buffered = pendingRealtime.getOrPut(chatId) { mutableListOf() }
        buffered.add(message)
        while (buffered.size > maxPendingRealtime) buffered.removeAt(0)
    }

    fun pendingCount(chatId: Long): Int = pendingRealtime[chatId]?.size ?: 0

    fun drainPending(chatId: Long): List<TdApi.Message> = pendingRealtime.remove(chatId).orEmpty()

    // ---- retention ---------------------------------------------------------

    /** Records use of [chatId] and returns the chats evicted to stay inside the cap. */
    fun touch(chatId: Long): List<Long> {
        retention[chatId] = Unit
        if (retention.size <= maxRetainedChats) return emptyList()
        val evicted = mutableListOf<Long>()
        while (retention.size > maxRetainedChats) {
            val victim = retention.keys.firstOrNull {
                HistoryPolicy.shouldEvict(states[it]?.active == true, retention.size, maxRetainedChats)
            } ?: break
            retention.remove(victim)
            states.remove(victim)
            pendingRealtime.remove(victim)
            evicted += victim
        }
        return evicted
    }

    fun clear() {
        states.clear()
        pendingRealtime.clear()
        retention.clear()
    }
}
