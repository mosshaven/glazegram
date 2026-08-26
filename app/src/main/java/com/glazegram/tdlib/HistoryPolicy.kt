package com.glazegram.tdlib

object HistoryPolicy {
    const val INITIAL_PAGE_SIZE = 50
    const val WARMUP_PAGE_SIZE = 20
    const val MAX_RETAINED_CHATS = 8
    const val MAX_MESSAGES_PER_CHAT = 150
    const val MAX_PENDING_REALTIME = 100
    const val RETAINED_VIEWPORT_THRESHOLD = 20
    const val WARMUP_MIN_MESSAGES = 10
    const val WARMUP_COOLDOWN_MS = 60_000L

    /**
     * Inside one chat TDLib message ids grow monotonically with time, so "older than the anchor"
     * means "id below the anchor id". Yet-unsent messages carry ids that do not follow chat
     * history order, so they are excluded from anchor selection in [HistoryMerge.oldestId].
     */
    fun isOlderThan(anchorId: Long, messageId: Long): Boolean = messageId in 1 until anchorId

    fun countOlderThan(anchorId: Long, messageIds: List<Long>): Int =
        messageIds.count { isOlderThan(anchorId, it) }

    /**
     * An older page ends history only when nothing older than the request anchor came back.
     * Page size is never used as a signal: TDLib routinely answers a limit-50 request with
     * fewer messages while more history exists, and may answer with the anchor itself or with
     * messages we already retain — which is progressless and therefore the real end signal.
     */
    fun endReachedAfterOlder(olderThanAnchor: Int): Boolean = olderThanAnchor == 0

    /** Only closed chats are compacted; an open chat may exceed the cap while paginating. */
    fun canTrim(active: Boolean, retainedMessages: Int): Boolean =
        !active && retainedMessages > MAX_MESSAGES_PER_CHAT

    fun shouldEvict(active: Boolean, retainedChats: Int, maxRetained: Int = MAX_RETAINED_CHATS): Boolean =
        !active && retainedChats > maxRetained
}
