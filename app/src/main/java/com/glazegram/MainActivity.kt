package com.glazegram

import android.graphics.BitmapFactory
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.glazegram.tdlib.AccountSummary
import com.glazegram.tdlib.AuthUiState
import com.glazegram.tdlib.ChatMessage
import com.glazegram.tdlib.ChatKind
import com.glazegram.tdlib.ChatSummary
import com.glazegram.tdlib.DeliveryState
import com.glazegram.tdlib.MediaKind
import com.glazegram.tdlib.MessageTextStyleKind
import com.glazegram.tdlib.TdLibRuntime
import com.glazegram.chat.ChatNavigationEvent
import com.glazegram.chat.ChatViewModel
import com.glazegram.chat.MessageListItem
import com.glazegram.chat.containsMessage
import com.glazegram.chat.resolveReplyTarget
import com.glazegram.ui.theme.GlazegramTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(this, Intent(this, TdLibService::class.java))
        setContent {
            GlazegramTheme {
                Surface(Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val state by TdLibRuntime.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    TdLibRuntime.setForeground(true)
                    TdLibRuntime.refreshChats()
                }
                Lifecycle.Event.ON_STOP -> TdLibRuntime.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "app-state",
    ) { current ->
        when (current) {
            AuthUiState.Ready -> AuthenticatedApp()
            AuthUiState.Initializing, AuthUiState.LoggingOut -> LoadingScreen()
            is AuthUiState.Phone -> AuthScreen("Введите номер телефона") {
                PhoneForm(current.phoneNumber, current.error, current.submitting)
            }
            is AuthUiState.Code -> AuthScreen("Введите код из Telegram") {
                CodeForm(current.error, current.submitting)
            }
            is AuthUiState.Password -> AuthScreen("Введите пароль 2FA") {
                PasswordForm(current.hint, current.error, current.submitting)
            }
        }
    }
    BackHandler(enabled = state is AuthUiState.Code || state is AuthUiState.Password) {
        TdLibRuntime.cancelAuthorization()
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AuthScreen(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Glazegram", style = MaterialTheme.typography.headlineMedium)
        Text(title, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        content()
    }
}

private enum class MainDestination { Chats, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedApp() {
    val chats by TdLibRuntime.chats.collectAsState()
    val account by TdLibRuntime.account.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val chatListState = rememberLazyListState()
    var destination by rememberSaveable { mutableStateOf(MainDestination.Chats) }
    var selectedChatId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedChat = chats.firstOrNull { it.id == selectedChatId }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = selectedChat == null,
        drawerContent = {
            AppDrawer(
                account = account,
                destination = destination,
                onDestination = {
                    destination = it
                    selectedChatId = null
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        AnimatedContent(
            targetState = selectedChatId,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "chat-navigation",
        ) { chatId ->
            val chat = chats.firstOrNull { it.id == chatId }
            when {
                chat != null -> ChatScreen(chat, onBack = { selectedChatId = null })
                destination == MainDestination.Settings -> SettingsScreen(
                    account = account,
                    onMenu = { scope.launch { drawerState.open() } },
                )
                else -> ChatListScreen(
                    chats = chats,
                    listState = chatListState,
                    onMenu = { scope.launch { drawerState.open() } },
                    onChat = { selectedChatId = it },
                )
            }
        }
    }
}

@Composable
private fun AppDrawer(
    account: AccountSummary?,
    destination: MainDestination,
    onDestination: (MainDestination) -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            ChatAvatar(account?.name ?: "Glazegram", account?.avatarPath, 72)
            Spacer(Modifier.height(16.dp))
            Text(account?.name ?: "Glazegram", style = MaterialTheme.typography.titleLarge)
            if (!account?.detail.isNullOrBlank()) {
                Text(account?.detail.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        NavigationDrawerItem(
            label = { Text("Чаты") },
            selected = destination == MainDestination.Chats,
            onClick = { onDestination(MainDestination.Chats) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("Настройки") },
            selected = destination == MainDestination.Settings,
            onClick = { onDestination(MainDestination.Settings) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListScreen(
    chats: List<ChatSummary>,
    listState: LazyListState,
    onMenu: () -> Unit,
    onChat: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Glazegram") },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(Icons.Default.Menu, contentDescription = "Открыть меню")
                    }
                },
            )
        },
    ) { padding ->
        if (chats.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(chats, key = { it.id }, contentType = { "chat" }) { chat ->
                    ChatListItem(chat, onClick = { onChat(chat.id) })
                    HorizontalDivider(modifier = Modifier.padding(start = 88.dp))
                }
            }
        }
    }
}

@Composable
private fun ChatListItem(chat: ChatSummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(chat.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                listOf(chat.lastMessageAuthor, chat.lastMessage)
                    .filter(String::isNotBlank).joinToString(": "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { ChatAvatar(chat.title, chat.avatarPath) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(chat.lastMessageTime, style = MaterialTheme.typography.labelSmall)
                if (chat.isPinned) Text("PIN", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                if (chat.unreadMentionCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Badge { Text("@${chat.unreadMentionCount}") }
                }
                if (chat.unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Badge { Text(chat.unreadCount.toString()) }
                }
            }
        },
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(account: AccountSummary?, onMenu: () -> Unit) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var clearingCache by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(Icons.Default.Menu, contentDescription = "Открыть меню")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChatAvatar(account?.name ?: "Glazegram", account?.avatarPath, 64)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(account?.name ?: "Glazegram", style = MaterialTheme.typography.titleMedium)
                    Text(account?.detail.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
            ListItem(
                headlineContent = { Text("Очистить кэш") },
                supportingContent = { Text("Удалить загруженные медиафайлы TDLib") },
                trailingContent = { if (clearingCache) CircularProgressIndicator(Modifier.size(24.dp)) },
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = !clearingCache) {
                    clearingCache = true
                    TdLibRuntime.clearCache { success ->
                        scope.launch {
                            clearingCache = false
                            Toast.makeText(
                                context,
                                if (success) "Кэш очищен" else "Не удалось очистить кэш",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Выйти из аккаунта", color = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { confirmLogout = true },
            )
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Локальная сессия Glazegram будет завершена.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    TdLibRuntime.logout()
                }) { Text("Выйти") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Отмена") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatScreen(chat: ChatSummary, onBack: () -> Unit) {
    val viewModel: ChatViewModel = viewModel(key = "chat:${chat.id}", factory = ChatViewModel.Factory(chat.id))
    val state by viewModel.state.collectAsState()
    val messages = state.messages
    val listState = rememberLazyListState()
    var draft by rememberSaveable(chat.id) { mutableStateOf("") }
    var selectedMediaId by remember { mutableStateOf<Long?>(null) }
    var unseenCount by remember(chat.id) { mutableIntStateOf(0) }
    var previousNewestId by remember(chat.id) { mutableStateOf<Long?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val awayFromBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 48 }
    }

    BackHandler(onBack = onBack)
    DisposableEffect(chat.id) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    DisposableEffect(chat.id, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(chat.id) {
        snapshotFlow {
            val maxIndex = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            maxIndex to state.items.size
        }.distinctUntilChanged().collect { (maxIndex, size) ->
            if (size > 0 && maxIndex >= size - 6) viewModel.loadOlder()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.navigation.collect { event ->
            when (event) {
                is ChatNavigationEvent.ScrollTo -> {
                    snapshotFlow { state.items.indexOfFirst { it.containsMessage(event.messageId) } }
                        .first { it >= 0 }
                        .let { listState.scrollToItem(it) }
                    viewModel.highlight(event.messageId)
                }
                is ChatNavigationEvent.Unavailable -> Toast.makeText(
                    context,
                    "Сообщение удалено или недоступно",
                    Toast.LENGTH_SHORT,
                ).show()
                ChatNavigationEvent.ScrollToBottom -> {
                    listState.animateScrollToItem(0)
                    unseenCount = 0
                }
            }
        }
    }
    LaunchedEffect(chat.id) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { visible ->
                state.items.getOrNull(visible.index)
            }.flatMap { it.messages }.map { it.id }
        }.distinctUntilChanged().collect { visibleIds ->
            if (visibleIds.isNotEmpty()) {
                delay(250)
                viewModel.viewMessages(visibleIds)
            }
        }
    }
    LaunchedEffect(messages.firstOrNull()?.id) {
        val newest = messages.firstOrNull()
        if (newest != null && previousNewestId != null && newest.id != previousNewestId) {
            if (newest.isOutgoing) {
                listState.animateScrollToItem(0)
                unseenCount = 0
            } else if (awayFromBottom) {
                unseenCount += 1
            }
        }
        previousNewestId = newest?.id
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            chat.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            chat.subtitle,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = { ChatAvatar(chat.title, chat.avatarPath, 40) },
            )
        },
        bottomBar = {
            if (chat.canSendMessages) {
                MessageComposer(
                    draft = draft,
                    onDraft = { draft = it },
                    replyTo = state.replyTo,
                    onCancelReply = viewModel::cancelReply,
                    onSend = {
                        if (viewModel.send(draft)) draft = ""
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.20f),
                        ),
                    ),
                ),
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.items, key = { it.key }, contentType = { it::class }) { item ->
                    when (item) {
                        is MessageListItem.Single -> MessageBubble(
                            message = item.message,
                            repliedMessage = resolveReplyTarget(item.message, messages, state.replyTargets),
                            replyUnavailable = item.message.replyToMessageId in state.unavailableReplyIds,
                            replyLoading = item.message.replyToMessageId?.let {
                                state.replyTargets.containsKey(it) && state.replyTargets[it] == null &&
                                    it !in state.unavailableReplyIds
                            } == true,
                            highlighted = state.highlightedMessageId == item.message.id,
                            modifier = Modifier.animateItem(),
                            onActions = { viewModel.showActions(item.message) },
                            onSwipeReply = { viewModel.reply(item.message) },
                            onReplyClick = { item.message.replyToMessageId?.let(viewModel::navigateTo) },
                            onOpenMedia = { selectedMediaId = item.message.id },
                        )
                        is MessageListItem.Album -> AlbumBubble(
                            album = item,
                            highlightedMessageId = state.highlightedMessageId,
                            onActions = viewModel::showActions,
                            onOpenMedia = { selectedMediaId = it.id },
                        )
                    }
                }
                if (state.loadingOlder || state.hasMore) {
                    item("history-loading") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            if (state.loadingOlder) CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                }
            }
            if (awayFromBottom || unseenCount > 0) {
                FilledTonalIconButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(0)
                            unseenCount = 0
                            state.items.firstOrNull()?.messages?.map { it.id }?.let(viewModel::viewMessages)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(52.dp),
                ) {
                    val count = maxOf(unseenCount, chat.unreadCount)
                    Text(if (count > 0) "↓$count" else "↓", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    selectedMediaId?.let { messageId ->
        messages.firstOrNull { it.id == messageId }?.let { message ->
            MediaViewer(chat.id, message, onDismiss = { selectedMediaId = null })
        }
    }
    state.actionTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = viewModel::dismissActions) {
            if (target.text.isNotBlank()) {
                ListItem(
                    headlineContent = { Text("Копировать") },
                    modifier = Modifier.clickable {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("message", target.text))
                        viewModel.dismissActions()
                    },
                )
            }
            state.deleteCapability?.let { capability ->
                if (capability.forSelf) {
                    ListItem(
                        headlineContent = { Text("Удалить у меня", color = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable { viewModel.delete(target, forEveryone = false) },
                    )
                }
                if (capability.forEveryone) {
                    ListItem(
                        headlineContent = { Text("Удалить у всех", color = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable { viewModel.delete(target, forEveryone = true) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    repliedMessage: ChatMessage?,
    replyUnavailable: Boolean,
    replyLoading: Boolean,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onActions: () -> Unit,
    onSwipeReply: () -> Unit,
    onReplyClick: () -> Unit,
    onOpenMedia: () -> Unit,
) {
    val swipeOffset = remember(message.id) { Animatable(0f) }
    val swipeScope = rememberCoroutineScope()
    val swipeDirection = if (message.isOutgoing) -1f else 1f
    val bubbleColor by animateColorAsState(
        targetValue = if (highlighted || message.containsUnreadMention) MaterialTheme.colorScheme.tertiaryContainer
        else if (message.isOutgoing) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "message-highlight",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = swipeOffset.value }
            .pointerInput(message.id, message.isOutgoing) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        val next = (swipeOffset.value + dragAmount)
                            .coerceIn(if (message.isOutgoing) -120f else 0f, if (message.isOutgoing) 0f else 120f)
                        if (dragAmount * swipeDirection > 0) change.consume()
                        swipeScope.launch { swipeOffset.snapTo(next) }
                    },
                    onDragEnd = {
                        if (abs(swipeOffset.value) >= 72f) onSwipeReply()
                        swipeScope.launch { swipeOffset.animateTo(0f, spring()) }
                    },
                    onDragCancel = { swipeScope.launch { swipeOffset.animateTo(0f, spring()) } },
                )
            }
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        if (!message.isOutgoing) {
            ChatAvatar(message.author, message.authorAvatarPath, 32)
            Spacer(Modifier.width(6.dp))
        }
        Surface(
            color = bubbleColor,
            shape = if (message.isOutgoing) RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp)
            else RoundedCornerShape(20.dp, 20.dp, 20.dp, 5.dp),
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .animateContentSize()
                .combinedClickable(onClick = {}, onLongClick = onActions),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!message.isOutgoing) {
                    Text(message.author, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                if (message.containsUnreadMention) {
                    Text("Упоминание @", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                }
                message.forwardedFrom?.let { origin ->
                    Text(
                        "Переслано от $origin",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (message.replyToMessageId != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onReplyClick),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(repliedMessage?.author ?: "Ответ", style = MaterialTheme.typography.labelSmall)
                            Text(
                                when {
                                    repliedMessage != null -> repliedMessage.text.ifBlank {
                                        repliedMessage.contentPreview.ifBlank { "Сообщение" }
                                    }
                                    replyUnavailable -> "Сообщение удалено или недоступно"
                                    replyLoading -> "Загрузка…"
                                    else -> "Сообщение"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                MessageMedia(message, onOpenMedia)
                if (message.text.isNotBlank() && message.mediaKind != MediaKind.Sticker) {
                    FormattedMessageText(message)
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text(message.time, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    if (message.isOutgoing) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            when (message.deliveryState) {
                                DeliveryState.Sending -> "…"
                                DeliveryState.Sent -> "✓"
                                DeliveryState.Read -> "✓✓"
                                DeliveryState.Failed -> "!"
                            },
                            color = if (message.deliveryState == DeliveryState.Failed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormattedMessageText(message: ChatMessage) {
    val primary = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val quoteColor = MaterialTheme.colorScheme.onSurfaceVariant
    val annotated = remember(message.text, message.textStyles, primary, codeBackground, quoteColor) {
        buildAnnotatedString {
            append(message.text)
            for (entity in message.textStyles) {
                val start = entity.offset.coerceIn(0, message.text.length)
                val end = (entity.offset + entity.length).coerceIn(start, message.text.length)
                if (start == end) continue
                val style = when (entity.kind) {
                    MessageTextStyleKind.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
                    MessageTextStyleKind.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                    MessageTextStyleKind.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
                    MessageTextStyleKind.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    MessageTextStyleKind.Code -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                    MessageTextStyleKind.Link -> SpanStyle(color = primary, textDecoration = TextDecoration.Underline)
                    MessageTextStyleKind.Spoiler -> SpanStyle(background = codeBackground, color = quoteColor)
                    MessageTextStyleKind.Quote -> SpanStyle(fontStyle = FontStyle.Italic, color = quoteColor)
                }
                addStyle(style, start, end)
            }
        }
    }
    Text(annotated)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumBubble(
    album: MessageListItem.Album,
    highlightedMessageId: Long?,
    onActions: (ChatMessage) -> Unit,
    onOpenMedia: (ChatMessage) -> Unit,
) {
    val outgoing = album.messages.first().isOutgoing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        if (!outgoing) {
            val author = album.messages.first()
            ChatAvatar(author.author, author.authorAvatarPath, 32)
            Spacer(Modifier.width(6.dp))
        }
        Surface(
            color = if (album.containsMessage(highlightedMessageId ?: Long.MIN_VALUE)) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else if (outgoing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(4.dp)) {
                album.messages.chunked(2).forEach { rowMessages ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        rowMessages.forEach { message ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .combinedClickable(
                                        onClick = { onOpenMedia(message) },
                                        onLongClick = { onActions(message) },
                                    ),
                            ) {
                                MessageMedia(message, onOpen = { onOpenMedia(message) }, compact = true)
                            }
                        }
                        if (rowMessages.size == 1 && album.messages.size > 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(3.dp))
                }
                album.messages.filter { it.text.isNotBlank() }.forEach { message ->
                    Text(message.text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageMedia(message: ChatMessage, onOpen: () -> Unit, compact: Boolean = false) {
    if (message.mediaKind == MediaKind.Text) return
    val bitmap = remember(message.mediaPreviewPath, message.mediaMinithumbnail) {
        message.mediaPreviewPath?.let(BitmapFactory::decodeFile)
            ?: message.mediaMinithumbnail?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    val aspectRatio = if (message.mediaWidth > 0 && message.mediaHeight > 0) {
        (message.mediaWidth.toFloat() / message.mediaHeight).coerceIn(0.65f, 1.85f)
    } else {
        4f / 3f
    }
    val mediaModifier = Modifier
        .then(if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(min = 120.dp, max = 300.dp))
        .aspectRatio(aspectRatio)
        .clip(RoundedCornerShape(if (compact) 10.dp else 14.dp))
        .clickable(onClick = onOpen)
    if (bitmap != null) {
        Box(mediaModifier) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = message.mediaLabel,
                contentScale = if (message.mediaKind == MediaKind.Sticker) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (message.mediaKind in setOf(MediaKind.Video, MediaKind.VideoNote, MediaKind.Animation)) {
                Text(
                    text = if (message.mediaKind == MediaKind.Animation) "GIF" else "▶ ${message.mediaLabel.orEmpty()}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        if (!compact && !message.mediaLabel.isNullOrBlank() && message.mediaKind in setOf(
                MediaKind.Document,
                MediaKind.Audio,
                MediaKind.Voice,
            )) {
            Spacer(Modifier.height(6.dp))
            Text(
                message.mediaLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = mediaModifier,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(message.mediaLabel ?: "Медиа", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (!compact) Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MediaViewer(chatId: Long, message: ChatMessage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val path = message.mediaOpenPath
    LaunchedEffect(message.id, path) {
        if (path == null) TdLibRuntime.downloadMessageMedia(chatId, message.id)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.96f)) {
            Box(Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
                when {
                    path == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    message.mediaKind in setOf(MediaKind.Photo, MediaKind.Sticker) -> {
                        val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
                        if (bitmap != null) {
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    message.mediaKind in setOf(
                        MediaKind.Video,
                        MediaKind.VideoNote,
                        MediaKind.Animation,
                        MediaKind.Audio,
                        MediaKind.Voice,
                    ) -> {
                        AndroidView(
                            factory = { viewContext ->
                                VideoView(viewContext).apply {
                                    val controls = MediaController(viewContext)
                                    controls.setAnchorView(this)
                                    setMediaController(controls)
                                    setVideoPath(path)
                                    setOnPreparedListener { start() }
                                }
                            },
                            update = { view ->
                                if (view.tag != path) {
                                    view.tag = path
                                    view.setVideoPath(path)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    message.mediaKind == MediaKind.Document -> {
                        Button(
                            onClick = {
                                val uri: Uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.files",
                                    File(path),
                                )
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(uri, message.mediaMimeType ?: "*/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            },
                            modifier = Modifier.align(Alignment.Center),
                        ) { Text("Открыть ${message.mediaLabel ?: "файл"}") }
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun MessageComposer(
    draft: String,
    onDraft: (String) -> Unit,
    replyTo: ChatMessage?,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (replyTo != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text("Ответ: ${replyTo.author}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        Text(
                            replyTo.text.ifBlank { replyTo.contentPreview },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onCancelReply) {
                        Icon(Icons.Default.Close, contentDescription = "Отменить ответ")
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text("Сообщение") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                FilledTonalIconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                }
            }
        }
    }
}

@Composable
private fun ChatAvatar(title: String, path: String?, size: Int = 52) {
    val bitmap = remember(path) { path?.let(BitmapFactory::decodeFile) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = title,
            modifier = Modifier.size(size.dp).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun PhoneForm(initialPhone: String, error: String?, submitting: Boolean) {
    var phone by rememberSaveable(initialPhone) { mutableStateOf(initialPhone) }
    AuthField(phone, { phone = it }, "Номер телефона", KeyboardType.Phone, error, "Продолжить", submitting) {
        TdLibRuntime.submitPhoneNumber(phone.trim())
    }
}

@Composable
private fun CodeForm(error: String?, submitting: Boolean) {
    var code by rememberSaveable { mutableStateOf("") }
    AuthField(code, { code = it }, "Код", KeyboardType.Number, error, "Подтвердить", submitting) {
        TdLibRuntime.submitCode(code.trim())
    }
}

@Composable
private fun PasswordForm(hint: String, error: String?, submitting: Boolean) {
    var password by rememberSaveable { mutableStateOf("") }
    AuthField(
        password,
        { password = it },
        if (hint.isBlank()) "Пароль 2FA" else "Пароль 2FA ($hint)",
        KeyboardType.Password,
        error,
        "Войти",
        submitting,
        password = true,
    ) { TdLibRuntime.submitPassword(password) }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    error: String?,
    actionLabel: String,
    submitting: Boolean,
    password: Boolean = false,
    onAction: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (!submitting) onAction() }),
    )
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    Button(
        onClick = onAction,
        enabled = !submitting,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
    ) {
        if (submitting) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(actionLabel)
    }
}
