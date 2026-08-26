package com.glazegram.tdlib

object HistoryPolicy {
    const val INITIAL_PAGE_SIZE = 50
    const val WARMUP_PAGE_SIZE = 20
    const val MAX_RETAINED_CHATS = 8
    const val MAX_MESSAGES_PER_CHAT = 150

    // For TDLib, short page does NOT prove end if we made progress (new IDs)
    fun shouldMarkEndReached(newOlderIds: Int, totalCount: Int): Boolean {
        // End only when we got zero messages or no new IDs and totalCount ==0
        // If we got new IDs, even if totalCount < limit, not end
        return newOlderIds == 0 && totalCount == 0
    }

    fun shouldMarkEndReachedForTest(newOlderIds: Int, count: Int): Boolean {
        // exposed for test: mimics production older pagination logic
        return newOlderIds == 0 && count == 0
    }

    fun activeCanExceedCap(active: Boolean, size: Int): Boolean {
        return active || size <= MAX_MESSAGES_PER_CHAT
    }

    fun shouldEvict(active: Boolean, retainedSize: Int): Boolean {
        return !active && retainedSize > MAX_RETAINED_CHATS
    }
}
