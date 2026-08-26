package com.glazegram.tdlib

import org.drinkless.tdlib.TdApi

/** Retained-history data operations used by [TdLibRuntime]. */
object HistoryMerge {
    /** Newest first: yet-unsent messages, then by date, then by id. */
    val newestFirst: Comparator<TdApi.Message> = Comparator { first, second ->
        val firstPending = first.sendingState is TdApi.MessageSendingStatePending
        val secondPending = second.sendingState is TdApi.MessageSendingStatePending
        when {
            firstPending != secondPending -> if (firstPending) -1 else 1
            first.date != second.date -> second.date.compareTo(first.date)
            else -> second.id.compareTo(first.id)
        }
    }

    /** Union by message id — incoming wins — ordered newest first. */
    fun merge(existing: Array<TdApi.Message>, incoming: List<TdApi.Message>): Array<TdApi.Message> {
        val merged = LinkedHashMap<Long, TdApi.Message>(existing.size + incoming.size)
        for (message in existing) merged[message.id] = message
        for (message in incoming) merged[message.id] = message
        return merged.values.sortedWith(newestFirst).toTypedArray()
    }

    fun replace(
        existing: Array<TdApi.Message>,
        oldMessageId: Long,
        message: TdApi.Message,
    ): Array<TdApi.Message> =
        merge(existing.filterNot { it.id == oldMessageId }.toTypedArray(), listOf(message))

    fun trimToNewest(messages: Array<TdApi.Message>, max: Int): Array<TdApi.Message> =
        if (messages.size <= max) messages else messages.copyOfRange(0, max)

    /**
     * Anchor for the next older page: the lowest retained id, ignoring yet-unsent messages.
     * The lowest id rather than the last rendered row, so the anchor stays meaningful when
     * message dates disagree with id order and progress can be measured against it.
     */
    fun oldestId(messages: Array<TdApi.Message>): Long? = messages.asSequence()
        .filter { it.sendingState !is TdApi.MessageSendingStatePending && it.id > 0 }
        .minOfOrNull { it.id }
}
