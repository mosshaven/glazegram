package com.glazegram.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glazegram.tdlib.ChatMessage
import com.glazegram.tdlib.MessageDeleteCapability
import com.glazegram.tdlib.TdLibRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatScreenState(
    val messages: List<ChatMessage> = emptyList(),
    val items: List<MessageListItem> = emptyList(),
    val loadingOlder: Boolean = false,
    val hasMore: Boolean = true,
    val replyTo: ChatMessage? = null,
    val actionTarget: ChatMessage? = null,
    val replyTargets: Map<Long, ChatMessage?> = emptyMap(),
    val unavailableReplyIds: Set<Long> = emptySet(),
    val highlightedMessageId: Long? = null,
    val deleteCapability: MessageDeleteCapability? = null,
)

sealed interface ChatNavigationEvent {
    data class ScrollTo(val messageId: Long) : ChatNavigationEvent
    data class Unavailable(val messageId: Long) : ChatNavigationEvent
    data object ScrollToBottom : ChatNavigationEvent
}

class ChatViewModel(private val chatId: Long) : ViewModel() {
    private val interaction = MutableStateFlow(ChatScreenState())
    val navigation = MutableSharedFlow<ChatNavigationEvent>(extraBufferCapacity = 1)

    val state = combine(
        TdLibRuntime.messages,
        TdLibRuntime.historyLoading,
        TdLibRuntime.historyHasMore,
        interaction,
    ) { allMessages, loading, hasMore, local ->
        val messages = allMessages[chatId].orEmpty()
        local.copy(
            messages = messages,
            items = groupMessageAlbums(messages),
            loadingOlder = loading[chatId] == true,
            hasMore = hasMore[chatId] != false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatScreenState())

    init {
        viewModelScope.launch {
            TdLibRuntime.messages.collect { allMessages ->
                allMessages[chatId].orEmpty().forEach { message ->
                    val targetId = message.replyToMessageId ?: return@forEach
                    val targetChatId = message.replyToChatId?.takeIf { it != 0L } ?: chatId
                    val loadedTarget = allMessages[targetChatId].orEmpty().firstOrNull { it.id == targetId }
                    if (loadedTarget != null) {
                        if (interaction.value.replyTargets[targetId]?.id != loadedTarget.id) {
                            interaction.value = interaction.value.copy(
                                replyTargets = interaction.value.replyTargets + (targetId to loadedTarget),
                                unavailableReplyIds = interaction.value.unavailableReplyIds - targetId,
                            )
                        }
                        return@forEach
                    }
                    if (interaction.value.replyTargets.containsKey(targetId)) return@forEach
                    interaction.value = interaction.value.copy(
                        replyTargets = interaction.value.replyTargets + (targetId to null),
                    )
                    TdLibRuntime.resolveMessage(targetChatId, targetId) { target ->
                        interaction.value = interaction.value.copy(
                            replyTargets = interaction.value.replyTargets + (targetId to target),
                            unavailableReplyIds = if (target == null) {
                                interaction.value.unavailableReplyIds + targetId
                            } else {
                                interaction.value.unavailableReplyIds - targetId
                            },
                        )
                    }
                }
            }
        }
    }

    fun open() = TdLibRuntime.openChat(chatId)
    fun close() = TdLibRuntime.closeChat(chatId)
    fun loadOlder() = TdLibRuntime.loadOlderMessages(chatId)
    fun refresh() = TdLibRuntime.refreshChatHistory(chatId)

    fun showActions(message: ChatMessage) {
        interaction.value = interaction.value.copy(actionTarget = message, deleteCapability = null)
        TdLibRuntime.getDeleteCapability(chatId, message.id) { capability ->
            if (interaction.value.actionTarget?.id == message.id) {
                interaction.value = interaction.value.copy(deleteCapability = capability)
            }
        }
    }

    fun dismissActions() {
        interaction.value = interaction.value.copy(actionTarget = null, deleteCapability = null)
    }

    fun reply(message: ChatMessage) {
        interaction.value = interaction.value.copy(replyTo = message, actionTarget = null)
    }

    fun cancelReply() {
        interaction.value = interaction.value.copy(replyTo = null)
    }

    fun send(text: String): Boolean {
        val accepted = TdLibRuntime.sendTextMessage(chatId, text, interaction.value.replyTo?.id)
        if (accepted) {
            interaction.value = interaction.value.copy(replyTo = null)
            navigation.tryEmit(ChatNavigationEvent.ScrollToBottom)
        }
        return accepted
    }

    fun delete(message: ChatMessage, forEveryone: Boolean) {
        TdLibRuntime.deleteMessage(chatId, message.id, forEveryone)
        dismissActions()
    }

    fun viewMessages(messageIds: List<Long>) = TdLibRuntime.viewMessages(chatId, messageIds)

    fun navigateTo(messageId: Long) {
        if (state.value.items.any { it.containsMessage(messageId) }) {
            navigation.tryEmit(ChatNavigationEvent.ScrollTo(messageId))
            return
        }
        TdLibRuntime.loadMessageContext(chatId, messageId) { loaded ->
            navigation.tryEmit(
                if (loaded) ChatNavigationEvent.ScrollTo(messageId)
                else ChatNavigationEvent.Unavailable(messageId),
            )
        }
    }

    fun highlight(messageId: Long) {
        highlightJob?.cancel()
        interaction.value = interaction.value.copy(highlightedMessageId = messageId)
        highlightJob = viewModelScope.launch {
            delay(2_000)
            if (interaction.value.highlightedMessageId == messageId) {
                interaction.value = interaction.value.copy(highlightedMessageId = null)
            }
        }
    }

    private var highlightJob: Job? = null

    class Factory(private val chatId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(chatId) as T
    }
}
