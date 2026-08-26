package com.glazegram.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListViewportPolicyTest {
    @Test
    fun settledTopPinsAfterChatReorder() {
        val before = ChatListViewportSnapshot(0, 0, false)
        assertTrue(before.isSettledAtTop())
        assertTrue(shouldRestoreChatListTop(true, false, searchMode = false))
    }

    @Test
    fun awayFromTopKeepsStableKeyAnchor() {
        val before = ChatListViewportSnapshot(5, 12, false)
        assertFalse(before.isSettledAtTop())
        assertFalse(shouldRestoreChatListTop(false, false, searchMode = false))
    }

    @Test
    fun activeScrollDoesNotForceAnchor() {
        assertFalse(shouldRestoreChatListTop(true, true, searchMode = false))
    }

    @Test
    fun searchModeNeverTriggersTopAnchor() {
        assertFalse(shouldRestoreChatListTop(true, false, searchMode = true))
    }
}
