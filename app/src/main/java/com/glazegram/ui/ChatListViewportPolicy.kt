package com.glazegram.ui

private const val TOP_OFFSET_THRESHOLD_PX = 4

data class ChatListViewportSnapshot(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val isScrollInProgress: Boolean,
)

fun ChatListViewportSnapshot.isSettledAtTop(): Boolean =
    !isScrollInProgress &&
        firstVisibleItemIndex == 0 &&
        firstVisibleItemScrollOffset <= TOP_OFFSET_THRESHOLD_PX

fun shouldRestoreChatListTop(
    wasAtTopBeforeMutation: Boolean,
    isScrollInProgress: Boolean,
    searchMode: Boolean,
): Boolean = !searchMode && wasAtTopBeforeMutation && !isScrollInProgress
