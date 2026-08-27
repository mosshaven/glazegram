package com.glazegram

import android.view.HapticFeedbackConstants
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
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
import com.glazegram.tdlib.ConnectionUiState
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
import com.glazegram.ui.components.EmptyState
import com.glazegram.ui.components.GlazegramAvatar
import com.glazegram.ui.components.LoadingState
import com.glazegram.ui.components.pressScale
import com.glazegram.ui.components.rememberDecodedImage
import com.glazegram.ui.components.rememberPressInteraction
import com.glazegram.ui.theme.Motion
import com.glazegram.ui.theme.Spacing
import com.glazegram.ui.ChatListViewportSnapshot
import com.glazegram.ui.isSettledAtTop
import com.glazegram.ui.shouldRestoreChatListTop
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
        transitionSpec = {
            (slideInHorizontally(tween(Motion.DURATION_MEDIUM, easing = Motion.EasingStandard)) { it / 6 } + fadeIn(
                tween(Motion.DURATION_MEDIUM)
            )) togetherWith
                (slideOutHorizontally(tween(Motion.DURATION_MEDIUM, easing = Motion.EasingExit)) { -it / 6 } + fadeOut(
                    tween(Motion.DURATION_SHORT)
                ))
        },
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
            transitionSpec = {
                (slideInHorizontally(tween(Motion.DURATION_MEDIUM, easing = Motion.EasingStandard)) { it / 4 } + fadeIn(
                    tween(Motion.DURATION_MEDIUM)
                )) togetherWith
                    (slideOutHorizontally(tween(Motion.DURATION_MEDIUM, easing = Motion.EasingStandard)) { -it / 4 } + fadeOut(
                        tween(Motion.DURATION_SHORT)
                    ))
            },
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
    ModalDrawerSheet(
        modifier = Modifier.width(304.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 16.dp)) {
            GlazegramAvatar(account?.name ?: "Glazegram", account?.avatarPath, size = 56.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                account?.name ?: "Glazegram",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!account?.detail.isNullOrBlank()) {
                Text(
                    account?.detail.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        NavigationDrawerItem(
            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(22.dp)) },
            label = { Text("Чаты", style = MaterialTheme.typography.labelLarge) },
            selected = destination == MainDestination.Chats,
            onClick = { onDestination(MainDestination.Chats) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(22.dp)) },
            label = { Text("Настройки", style = MaterialTheme.typography.labelLarge) },
            selected = destination == MainDestination.Settings,
            onClick = { onDestination(MainDestination.Settings) },
            shape = MaterialTheme.shapes.medium,
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
    val chatListLoaded by TdLibRuntime.chatListLoaded.collectAsState()
    val connectionUiState by TdLibRuntime.connectionUiState.collectAsState()
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    var wasAtTopBeforeMutation by remember { mutableStateOf(true) }
    val visibleChats = remember(chats, searchQuery) {
        if (searchQuery.isBlank()) chats
        else chats.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.collect { (index, offset, scrolling) ->
            // Keep last settled position. During a drag/fling, don't classify an
            // in-flight position as the user's final anchor.
            if (!scrolling) {
                wasAtTopBeforeMutation = ChatListViewportSnapshot(index, offset, scrolling).isSettledAtTop()
            }
        }
    }

    LaunchedEffect(chats) {
        // Stable keys preserve the anchor for users reading older chats. Only
        // explicitly restore item 0 when the list was already settled there.
        if (shouldRestoreChatListTop(wasAtTopBeforeMutation, listState.isScrollInProgress, searchMode)) {
            listState.scrollToItem(0)
        }
    }

    BackHandler(enabled = searchMode) {
        searchMode = false
        searchQuery = ""
    }
    Scaffold(
        topBar = {
            if (!searchMode) {
                TopAppBar(
                    title = {
                        Text(
                            when (connectionUiState) {
                                ConnectionUiState.READY -> "Glazegram"
                                ConnectionUiState.UPDATING -> "Обновление…"
                                ConnectionUiState.CONNECTING -> "Подключение…"
                                ConnectionUiState.WAITING_FOR_NETWORK -> "Ожидание сети…"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onMenu) {
                            Icon(Icons.Default.Menu, contentDescription = "Открыть меню")
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchMode = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Поиск")
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            searchMode = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Поиск", style = MaterialTheme.typography.bodyLarge) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Очистить")
                            }
                        }
                    },
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        },
    ) { padding ->
        when {
            !chatListLoaded && chats.isEmpty() ->
                LoadingState(Modifier.padding(padding))
            chats.isEmpty() ->
                EmptyState(
                    title = "Пока нет чатов",
                    subtitle = "Начните переписку — она появится здесь",
                    modifier = Modifier.padding(padding),
                )
            visibleChats.isEmpty() ->
                EmptyState(
                    title = "Ничего не найдено",
                    subtitle = "Попробуйте изменить запрос",
                    modifier = Modifier.padding(padding),
                )
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = Spacing.xs, bottom = Spacing.lg),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(visibleChats, key = { it.id }, contentType = { "chat" }) { chat ->
                    ChatListItem(chat, onClick = { onChat(chat.id) })
                }
            }
        }
    }
}

@Composable
private fun ChatListItem(chat: ChatSummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val unread = chat.unreadCount > 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        GlazegramAvatar(chat.title, chat.avatarPath, size = 54.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                chat.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chat.lastMessage.isNotBlank()) {
                Text(
                    chat.lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (chat.lastMessageIsOutgoing && chat.lastMessageDeliveryState != null) {
                    val (icon, cd, tint) = when (chat.lastMessageDeliveryState) {
                        DeliveryState.Sending -> Triple(Icons.Default.Schedule, "Отправляется", MaterialTheme.colorScheme.onSurfaceVariant)
                        DeliveryState.Sent -> Triple(Icons.Default.Done, "Отправлено", MaterialTheme.colorScheme.onSurfaceVariant)
                        DeliveryState.Read -> Triple(Icons.Default.DoneAll, "Прочитано", MaterialTheme.colorScheme.primary)
                        DeliveryState.Failed -> Triple(Icons.Default.ErrorOutline, "Ошибка", MaterialTheme.colorScheme.error)
                    }
                    Icon(icon, contentDescription = cd, tint = tint, modifier = Modifier.size(14.dp))
                }
                if (chat.isPinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Закреплён",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (chat.lastMessageTime.isNotBlank()) {
                    Text(
                        chat.lastMessageTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (chat.unreadMentionCount > 0 || unread) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    if (chat.unreadMentionCount > 0) {
                        Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("@") }
                    }
                    if (unread) {
                        Badge(containerColor = if (chat.isMuted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) {
                            Text(
                                if (chat.unreadCount > 999) "999+" else chat.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
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
        Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlazegramAvatar(account?.name ?: "Glazegram", account?.avatarPath, size = 64.dp)
                Spacer(Modifier.width(Spacing.lg))
                Column {
                    Text(account?.name ?: "Glazegram", style = MaterialTheme.typography.titleMedium)
                    Text(
                        account?.detail.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xl))
            ListItem(
                leadingContent = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                headlineContent = { Text("Очистить кэш") },
                supportingContent = { Text("Удалить загруженные медиафайлы TDLib") },
                trailingContent = { if (clearingCache) CircularProgressIndicator(Modifier.size(24.dp)) },
                modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = !clearingCache) {
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
                leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
                headlineContent = { Text("Диагностика") },
                supportingContent = { Text("Уровень логов: ${com.glazegram.diagnostics.GlazeLog.level}") },
                modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable {
                    val next = when (com.glazegram.diagnostics.GlazeLog.level) {
                        com.glazegram.diagnostics.LogLevel.OFF -> com.glazegram.diagnostics.LogLevel.BASIC
                        com.glazegram.diagnostics.LogLevel.BASIC -> com.glazegram.diagnostics.LogLevel.VERBOSE
                        com.glazegram.diagnostics.LogLevel.VERBOSE -> com.glazegram.diagnostics.LogLevel.OFF
                    }
                    com.glazegram.diagnostics.GlazeLog.setLevelAndPersist(context, next)
                },
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                headlineContent = { Text("Выйти из аккаунта", color = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { confirmLogout = true },
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
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        GlazegramAvatar(chat.title, chat.avatarPath, size = 40.dp)
                        Spacer(Modifier.width(Spacing.md))
                        Column {
                            Text(
                                chat.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                chat.subtitle,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
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
                .background(MaterialTheme.colorScheme.surface),
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
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
                            joinsNewer = item.joinsNewer,
                            joinsOlder = item.joinsOlder,
                            isGroup = chat.kind == com.glazegram.tdlib.ChatKind.BasicGroup || chat.kind == com.glazegram.tdlib.ChatKind.Supergroup,
                            modifier = Modifier.animateItem(),
                            onActions = { viewModel.showActions(item.message) },
                            onSwipeReply = { viewModel.reply(item.message) },
                            onReplyClick = { item.message.replyToMessageId?.let(viewModel::navigateTo) },
                            onOpenMedia = { selectedMediaId = item.message.id },
                        )
                        is MessageListItem.Album -> AlbumBubble(
                            album = item,
                            highlightedMessageId = state.highlightedMessageId,
                            isGroup = chat.kind == com.glazegram.tdlib.ChatKind.BasicGroup || chat.kind == com.glazegram.tdlib.ChatKind.Supergroup,
                            modifier = Modifier.animateItem(),
                            onActions = viewModel::showActions,
                            onSwipeReply = { viewModel.reply(item.messages.first()) },
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
            if (state.initialLoading && state.items.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
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
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(48.dp),
                ) {
                    val count = maxOf(unseenCount, chat.unreadCount)
                    if (count > 0) {
                        BadgedBox(badge = { Badge { Text(count.toString()) } }) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Вниз к непрочитанным")
                        }
                    } else {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Прокрутить вниз")
                    }
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
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        LaunchedEffect(target.id) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissActions,
            sheetState = sheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                ListItem(
                    headlineContent = { Text("Ответить") },
                    modifier = Modifier.clickable { viewModel.reply(target) },
                )
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
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
}

/**
 * Telegram-style swipe-to-reply arbitration (TGA/TGX-verified behavior):
 * - physical LEFT swipe only, identical for incoming/outgoing;
 * - starts only on a clear leftward lock beyond touch slop;
 * - refuses to start near screen edges so system back gesture wins;
 * - dp-based trigger/cap (TGX-like 42dp/64dp), not raw pixels;
 * - vertical scrolling is untouched: detectHorizontalDragGestures only locks
 *   after horizontal slop, and we additionally require leftward dominance.
 */
@Composable
private fun Modifier.replySwipe(
    key: Long,
    enabled: Boolean = true,
    onReply: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    val offset = remember(key) { Animatable(0f) }
    val view = LocalView.current
    return this
        .graphicsLayer { translationX = offset.value }
        .pointerInput(key, enabled) {
            if (!enabled) return@pointerInput
            val maxPx = 64.dp.toPx()
            val triggerPx = 36.dp.toPx()
            val edgePx = 28.dp.toPx()
            val slop = viewConfiguration.touchSlop
            var translating = false
            var startX = 0f
            detectHorizontalDragGestures(
                onDragStart = { start ->
                    startX = start.x
                    translating = false
                },
                onHorizontalDrag = { change, dragAmount ->
                    if (!translating) {
                        val width = size.width.toFloat()
                        val x = change.position.x
                        // Back-edge protection: never hijack system navigation zones.
                        if (startX < edgePx || startX > width - edgePx) return@detectHorizontalDragGestures
                        when {
                            x < startX - slop -> translating = true // clear leftward intent
                            x > startX + slop -> return@detectHorizontalDragGestures // rightward: not ours
                            else -> return@detectHorizontalDragGestures // undecided
                        }
                    }
                    change.consume()
                    val next = (offset.value + dragAmount).coerceIn(-maxPx, 0f)
                    scope.launch { offset.snapTo(next) }
                },
                onDragEnd = {
                    if (translating && offset.value <= -triggerPx) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onReply()
                    }
                    translating = false
                    scope.launch { offset.animateTo(0f, spring()) }
                },
                onDragCancel = {
                    translating = false
                    scope.launch { offset.animateTo(0f, spring()) }
                },
            )
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
    joinsNewer: Boolean,
    joinsOlder: Boolean,
    isGroup: Boolean,
    modifier: Modifier = Modifier,
    onActions: () -> Unit,
    onSwipeReply: () -> Unit,
    onReplyClick: () -> Unit,
    onOpenMedia: () -> Unit,
) {
    val bubbleColor by animateColorAsState(
        targetValue = if (highlighted || message.containsUnreadMention) MaterialTheme.colorScheme.tertiaryContainer
        else if (message.isOutgoing) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "message-highlight",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (joinsOlder) 2.dp else 10.dp)
            .replySwipe(message.id) { onSwipeReply() }
            .padding(horizontal = 12.dp),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        // Visual mapping for reverseLayout=true: older neighbor is visually above, newer below.
        // joinsOlder -> joins message above (small top gap, tight top corners, name at top)
        // joinsNewer -> joins message below (tight bottom corners, avatar at bottom)
        val showName = isGroup && !joinsOlder && !message.isOutgoing
        val showAvatar = isGroup && !joinsNewer && !message.isOutgoing
        if (isGroup && !message.isOutgoing) {
            if (showAvatar) {
                GlazegramAvatar(message.author, message.authorAvatarPath, size = 32.dp)
                Spacer(Modifier.width(6.dp))
            } else {
                Spacer(Modifier.width(38.dp))
            }
        }
        Surface(
            color = bubbleColor,
            shape = when {
                message.isOutgoing -> RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = if (joinsOlder) 8.dp else 20.dp,
                    bottomEnd = if (joinsNewer) 8.dp else 5.dp,
                    bottomStart = 20.dp,
                )
                else -> RoundedCornerShape(
                    topStart = if (joinsOlder) 8.dp else 20.dp,
                    topEnd = 20.dp,
                    bottomEnd = 20.dp,
                    bottomStart = if (joinsNewer) 8.dp else 5.dp,
                )
            },
            tonalElevation = 0.dp,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .animateContentSize()
                .combinedClickable(onClick = {}, onLongClick = onActions),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showName) {
                    Text(
                        message.author,
                        color = com.glazegram.ui.theme.senderColor(message.senderKey),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                        Spacer(Modifier.width(4.dp))
                        val (icon, description) = when (message.deliveryState) {
                            DeliveryState.Sending -> Icons.Default.Schedule to "Отправляется"
                            DeliveryState.Sent -> Icons.Default.Done to "Отправлено"
                            DeliveryState.Read -> Icons.Default.DoneAll to "Прочитано"
                            DeliveryState.Failed -> Icons.Default.ErrorOutline to "Ошибка отправки"
                        }
                        Icon(
                            icon,
                            contentDescription = description,
                            tint = if (message.deliveryState == DeliveryState.Failed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
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
    isGroup: Boolean,
    modifier: Modifier = Modifier,
    onActions: (ChatMessage) -> Unit,
    onSwipeReply: () -> Unit,
    onOpenMedia: (ChatMessage) -> Unit,
) {
    val outgoing = album.messages.first().isOutgoing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (album.joinsOlder) 2.dp else 10.dp)
            .replySwipe(album.albumId) { onSwipeReply() }
            .padding(horizontal = 12.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        // reverseLayout: older above, newer below
        val showName = isGroup && !outgoing && !album.joinsOlder
        val showAvatar = isGroup && !outgoing && !album.joinsNewer
        if (isGroup && !outgoing) {
            if (showAvatar) {
                val author = album.messages.first()
                GlazegramAvatar(author.author, author.authorAvatarPath, size = 32.dp)
                Spacer(Modifier.width(6.dp))
            } else {
                Spacer(Modifier.width(38.dp))
            }
        }
        Surface(
            color = if (album.containsMessage(highlightedMessageId ?: Long.MIN_VALUE)) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else if (outgoing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = when {
                outgoing -> RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = if (album.joinsOlder) 8.dp else 20.dp,
                    bottomEnd = if (album.joinsNewer) 8.dp else 5.dp,
                    bottomStart = 20.dp,
                )
                else -> RoundedCornerShape(
                    topStart = if (album.joinsOlder) 8.dp else 20.dp,
                    topEnd = 20.dp,
                    bottomEnd = 20.dp,
                    bottomStart = if (album.joinsNewer) 8.dp else 5.dp,
                )
            },
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(4.dp)) {
                if (showName) {
                    val first = album.messages.first()
                    Text(
                        first.author,
                        color = com.glazegram.ui.theme.senderColor(first.senderKey),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
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
    val isStickerLike = message.mediaKind == MediaKind.Sticker
    val bitmap = rememberDecodedImage(
        path = message.mediaPreviewPath,
        minithumbnail = message.mediaMinithumbnail,
        // Tiles render up to ~300dp (compact album cells narrower); over-decode margin kept small.
        targetDp = if (compact) 200 else 300,
        // Stickers/animations can carry alpha; photos/video-thumbs are opaque JPEG previews.
        allowRgb565 = !isStickerLike && message.mediaKind != MediaKind.Animation,
    )
    val aspectRatio = if (message.mediaWidth > 0 && message.mediaHeight > 0) {
        (message.mediaWidth.toFloat() / message.mediaHeight).coerceIn(0.65f, 1.85f)
    } else {
        4f / 3f
    }
    val isVisualMedia = message.mediaKind in setOf(
        MediaKind.Photo,
        MediaKind.Video,
        MediaKind.VideoNote,
        MediaKind.Animation,
        MediaKind.Sticker,
    )
    val mediaModifier = if (isVisualMedia) {
        Modifier
            .then(if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(min = 120.dp, max = 300.dp))
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(if (compact) 10.dp else 14.dp))
            .clickable(onClick = onOpen)
    } else {
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
    }
    if (bitmap != null) {
        Box(mediaModifier) {
            Image(
                bitmap = bitmap,
                contentDescription = message.mediaLabel,
                contentScale = if (message.mediaKind == MediaKind.Sticker) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (message.mediaPreviewPath == null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            if (message.mediaKind in setOf(MediaKind.Video, MediaKind.VideoNote, MediaKind.Animation)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (message.mediaKind == MediaKind.Animation) "GIF" else message.mediaLabel.orEmpty(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
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
    } else if (isVisualMedia) {
        Box(
            mediaModifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (message.mediaPreviewPath == null) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = message.mediaLabel ?: "Медиа",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                }
                if (message.mediaPreviewPath == null) {
                    Text(
                        "Загрузка…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = mediaModifier.clickable(onClick = onOpen),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                val icon = when (message.mediaKind) {
                    MediaKind.Document -> Icons.Default.Description
                    MediaKind.Voice -> Icons.AutoMirrored.Filled.VolumeUp
                    MediaKind.Audio -> Icons.AutoMirrored.Filled.VolumeUp
                    else -> Icons.Default.Description
                }
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        message.mediaLabel ?: "Медиа",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
                        val bitmap = rememberDecodedImage(path = path)
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
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
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        if (replyTo != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        replyTo.author,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        replyTo.text.ifBlank { replyTo.contentPreview },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onCancelReply, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Отменить ответ", modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            TextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text("Сообщение") },
                maxLines = 5,
                leadingIcon = {
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Вложения пока недоступны", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Прикрепить", modifier = Modifier.size(20.dp))
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.xs))
            val sendInteraction = rememberPressInteraction()
            FilledTonalIconButton(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                interactionSource = sendInteraction,
                modifier = Modifier.padding(bottom = 6.dp).pressScale(sendInteraction),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
            }
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
