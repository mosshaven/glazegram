package com.glazegram.tdlib

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Collections
import com.glazegram.BuildConfig
import com.glazegram.diagnostics.GlazeLog
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

object TdLibRuntime {
    private val mutableState = MutableStateFlow<AuthUiState>(AuthUiState.Initializing)
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()
    private val mutableChats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chats: StateFlow<List<ChatSummary>> = mutableChats.asStateFlow()
    private val mutableChatListLoaded = MutableStateFlow(false)
    val chatListLoaded: StateFlow<Boolean> = mutableChatListLoaded.asStateFlow()
    private val mutableConnectionUiState = MutableStateFlow(ConnectionUiState.READY)
    val connectionUiState: StateFlow<ConnectionUiState> = mutableConnectionUiState.asStateFlow()
    private val mutableMessages = MutableStateFlow<Map<Long, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<Long, List<ChatMessage>>> = mutableMessages.asStateFlow()
    private val mutableHistoryLoading = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val historyLoading: StateFlow<Map<Long, Boolean>> = mutableHistoryLoading.asStateFlow()
    private val mutableHistoryHasMore = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val historyHasMore: StateFlow<Map<Long, Boolean>> = mutableHistoryHasMore.asStateFlow()
    private val mutableAccount = MutableStateFlow<AccountSummary?>(null)
    val account: StateFlow<AccountSummary?> = mutableAccount.asStateFlow()

    /**
     * Per-chat history coordination. `history`, [messageHistory] and the published history
     * flags are only touched inside `history.withLock { }` — the UI thread calls in through
     * [openChat]/[closeChat]/[loadOlderMessages]/[refreshChatHistory] while TDLib answers on
     * its own threads.
     */
    private val history = HistoryCoordinator()

    private fun syncHistoryLoading(chatId: Long) {
        val state = history.peek(chatId) ?: return
        mutableHistoryLoading.value =
            mutableHistoryLoading.value + (chatId to state.isLoading(HistorySlot.OLDER))
    }

    private fun syncHistoryHasMore(chatId: Long) {
        val state = history.peek(chatId) ?: return
        mutableHistoryHasMore.value =
            mutableHistoryHasMore.value + (chatId to (state.boundary != HistoryBoundary.END_REACHED))
    }

    @Volatile
    private var initialized = false
    private var client: Client? = null
    private var parametersRequested = false
    private var parametersSent = false
    private var restoringPendingCode = true
    private var resetRequested = false
    private var resetError: String? = null
    private var authNotice: String? = null
    private val chatMap = LinkedHashMap<Long, TdApi.Chat>()
    private val users = HashMap<Long, TdApi.User>()
    private val pendingUserRequests = HashSet<Long>()
    private val basicGroups = HashMap<Long, TdApi.BasicGroup>()
    private val supergroups = HashMap<Long, TdApi.Supergroup>()
    private val onlineMemberCounts = HashMap<Long, Int>()
    private val chatActions = HashMap<Long, MutableMap<String, Pair<TdApi.MessageSender, TdApi.ChatAction>>>()
    private val messageHistory = HashMap<Long, Array<TdApi.Message>>()
    private val finalizedTemporaryMessageIds = Collections.synchronizedSet(HashSet<Long>())
    private var suppressChatPublishing = false
    private var currentUser: TdApi.User? = null
    private lateinit var preferences: android.content.SharedPreferences

    // Process-local retention – bounded LRU, metadata only (no bitmap). Retention order and
    // per-chat coordination live in `history`; the messages themselves in messageHistory.

    fun initialize(context: Context) {
        if (initialized) return

        System.loadLibrary("tdjni")
        Client.setLogMessageHandler(1) { verbosityLevel, message ->
            Log.println(if (verbosityLevel <= 1) Log.INFO else Log.DEBUG, "TdLib", message)
        }
        Client.execute(TdApi.SetLogVerbosityLevel(0))
        storageContext = context.applicationContext
        preferences = storageContext.getSharedPreferences("auth", Context.MODE_PRIVATE)
        GlazeLog.init(storageContext)
        client = Client.create(::handleUpdate, ::logException, ::logException)
        initialized = true
        observeNetwork()
        if (parametersRequested) sendParameters()

        client?.send(TdApi.GetAuthorizationState(), ::ignoreResult, ::handleException)
    }

    fun submitPhoneNumber(phoneNumber: String) {
        if (phoneNumber.isBlank()) {
            mutableState.value = AuthUiState.Phone(phoneNumber, "Введите номер телефона")
            return
        }
        preferences.edit().putString("pending_phone", phoneNumber).apply()
        restoringPendingCode = false
        if (!beginSubmit()) return
        sendAuth(TdApi.SetAuthenticationPhoneNumber(phoneNumber, TdApi.PhoneNumberAuthenticationSettings()))
    }

    fun submitCode(code: String) {
        if (code.isBlank()) {
            mutableState.value = AuthUiState.Code("Введите код из Telegram")
            return
        }
        if (!beginSubmit()) return
        sendAuth(TdApi.CheckAuthenticationCode(code))
    }

    fun submitPassword(password: String) {
        if (password.isBlank()) {
            val current = mutableState.value as? AuthUiState.Password ?: return
            mutableState.value = current.copy(error = "Введите пароль 2FA")
            return
        }
        if (!beginSubmit()) return
        sendAuth(TdApi.CheckAuthenticationPassword(password))
    }

    fun logout() {
        if (mutableState.value != AuthUiState.Ready) return
        mutableState.value = AuthUiState.LoggingOut
        send(TdApi.LogOut(), onFailure = { mutableState.value = AuthUiState.Ready })
    }

    fun clearCache(result: (Boolean) -> Unit) {
        send(
            TdApi.OptimizeStorage(0L, 0, 0, 0, emptyArray(), longArrayOf(), longArrayOf(), false, 0),
            result = { result(true) },
            onFailure = { result(false) },
        )
    }

    fun viewMessages(chatId: Long, messageIds: List<Long>) {
        val ids = messageIds.asSequence().filter { it > 0 }.distinct().toList().toLongArray()
        if (ids.isEmpty()) return
        send(TdApi.ViewMessages(chatId, ids, TdApi.MessageSourceChatHistory(), true))
    }

    fun getDeleteCapability(chatId: Long, messageId: Long, result: (MessageDeleteCapability?) -> Unit) {
        send(
            TdApi.GetMessageProperties(chatId, messageId),
            result = { response ->
                val properties = response as? TdApi.MessageProperties
                result(properties?.let {
                    MessageDeleteCapability(it.canBeDeletedOnlyForSelf, it.canBeDeletedForAllUsers)
                })
            },
            onFailure = { result(null) },
        )
    }

    fun deleteMessage(chatId: Long, messageId: Long, forEveryone: Boolean) {
        send(TdApi.DeleteMessages(chatId, longArrayOf(messageId), forEveryone))
    }

    fun cancelAuthorization() {
        if (resetRequested) return
        resetRequested = true
        mutableState.value = AuthUiState.Initializing
        send(TdApi.Destroy())
    }

    fun openChat(chatId: Long) {
        send(TdApi.OpenChat(chatId))
        history.withLock {
            val state = history.open(chatId)
            touchRetention(chatId)
            val retained = messageHistory[chatId]?.size ?: 0
            val cacheHit = retained >= HistoryPolicy.RETAINED_VIEWPORT_THRESHOLD
            GlazeLog.historyOpen(chatId, cacheHit, history.retainedChats())
            when {
                // Retained viewport is already published; refresh it lightly.
                state.initialReady -> scheduleRefresh(chatId)
                state.isLoading(HistorySlot.INITIAL) ->
                    GlazeLog.paginationSuppressed(chatId, "openChat already initialLoading")
                // Cold open: bounded local-first initial page (no single-message floating).
                else -> loadInitial(chatId)
            }
        }
    }

    fun refreshChats() {
        if (mutableState.value == AuthUiState.Ready) loadChats()
    }

    fun setForeground(foreground: Boolean) {
        send(TdApi.SetOption("online", TdApi.OptionValueBoolean(foreground)))
        if (foreground) publishNetworkType()
    }

    fun refreshChatHistory(chatId: Long) {
        if (mutableState.value != AuthUiState.Ready) return
        history.withLock { scheduleRefresh(chatId) }
    }

    fun closeChat(chatId: Long) {
        send(TdApi.CloseChat(chatId))
        history.withLock {
            history.setActive(chatId, false)
            // An open chat may grow past the cap while paginating; compact it once it is closed.
            if (trimHistoryIfInactive(chatId)) publishMessages(chatId)
        }
        // allow LRU to evict only inactive
    }

    fun loadOlderMessages(chatId: Long) {
        history.withLock {
            val blocked = history.olderRequestBlocked(chatId)
            if (blocked != null) {
                GlazeLog.paginationSuppressed(chatId, blocked)
                return@withLock
            }
            val anchorId = HistoryMerge.oldestId(messageHistory[chatId] ?: emptyArray())
            if (anchorId == null) {
                GlazeLog.paginationSuppressed(chatId, "no older anchor")
                return@withLock
            }
            val request = history.begin(chatId, HistorySlot.OLDER) ?: return@withLock
            touchRetention(chatId)
            syncHistoryLoading(chatId)
            val startedAt = SystemClock.elapsedRealtime()
            send(
                TdApi.GetChatHistory(chatId, anchorId, 0, HistoryPolicy.INITIAL_PAGE_SIZE, false),
                result = { result ->
                    history.withLock {
                        if (history.owner(request) == null) {
                            GlazeLog.paginationSuppressed(chatId, "stale older response")
                            return@withLock
                        }
                        val messages = (result as? TdApi.Messages)?.messages
                        if (messages == null) {
                            history.finish(request)
                            syncHistoryLoading(chatId)
                            return@withLock
                        }
                        // Progress is measured against the anchor, never against the page size.
                        val older = messages.filter { HistoryPolicy.isOlderThan(anchorId, it.id) }
                        if (older.isNotEmpty()) mergeMessages(chatId, older, HistoryLoadSource.OLDER)
                        val boundary = history.completeOlder(request, older.size)
                        syncHistoryLoading(chatId)
                        syncHistoryHasMore(chatId)
                        GlazeLog.historyOlder(
                            chatId = chatId,
                            fromId = anchorId,
                            count = messages.size,
                            olderCount = older.size,
                            endReached = boundary == HistoryBoundary.END_REACHED,
                            latencyMs = SystemClock.elapsedRealtime() - startedAt,
                        )
                    }
                },
                onFailure = { error ->
                    history.withLock {
                        // Transport failure is not an end of history: release the slot, keep the boundary.
                        if (history.finish(request)) {
                            GlazeLog.e("History/Older", "chatId=$chatId failed: $error")
                            syncHistoryLoading(chatId)
                        }
                    }
                },
            )
        }
    }

    fun sendTextMessage(chatId: Long, text: String, replyToMessageId: Long? = null): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val replyTo = replyToMessageId?.let {
            TdApi.InputMessageReplyToMessage(it, null, 0, "")
        }
        send(
            TdApi.SendMessage(
                chatId,
                null,
                replyTo,
                TdApi.MessageSendOptions(),
                null,
                TdApi.InputMessageText(TdApi.FormattedText(trimmed, emptyArray()), null, true),
            ),
            result = { result ->
                val message = result as? TdApi.Message ?: return@send
                history.withLock {
                    if (message.id !in finalizedTemporaryMessageIds) {
                        mergeMessages(chatId, listOf(message), HistoryLoadSource.REALTIME)
                    }
                }
            },
        )
        return true
    }

    fun resolveMessage(chatId: Long, messageId: Long, result: (ChatMessage?) -> Unit) {
        send(
            TdApi.GetMessage(chatId, messageId),
            result = { response -> result((response as? TdApi.Message)?.let(::projectMessage)) },
            onFailure = { result(null) },
        )
    }

    fun loadMessageContext(chatId: Long, messageId: Long, result: (Boolean) -> Unit) {
        send(
            TdApi.GetMessage(chatId, messageId),
            result = { response ->
                if (response !is TdApi.Message) {
                    result(false)
                    return@send
                }
                send(
                    TdApi.GetChatHistory(chatId, messageId, -19, 33, false),
                    result = { historyResponse ->
                        val messages = (historyResponse as? TdApi.Messages)?.messages
                        if (messages == null) {
                            result(false)
                        } else {
                            history.withLock {
                                mergeMessages(chatId, messages.asList(), HistoryLoadSource.CONTEXT)
                                result(messageHistory[chatId].orEmpty().any { it.id == messageId })
                            }
                        }
                    },
                    onFailure = { result(false) },
                )
            },
            onFailure = { result(false) },
        )
    }

    fun downloadMessageMedia(chatId: Long, messageId: Long) {
        val message = history.withLock { messageHistory[chatId]?.firstOrNull { it.id == messageId } } ?: return
        messageMediaFile(message.content)?.let { requestFile(it, priority = 32) }
    }

    /** Caller holds the history lock. */
    private fun scheduleRefresh(chatId: Long) {
        val state = history.peek(chatId) ?: return
        if (state.isLoading(HistorySlot.INITIAL)) return
        val request = history.begin(chatId, HistorySlot.REFRESH) ?: return
        val startedAt = SystemClock.elapsedRealtime()
        send(
            TdApi.GetChatHistory(chatId, 0, 0, HistoryPolicy.INITIAL_PAGE_SIZE, false),
            result = { result ->
                history.withLock {
                    if (history.owner(request) == null) return@withLock
                    val messages = (result as? TdApi.Messages)?.messages.orEmpty()
                    if (messages.isNotEmpty()) {
                        mergeMessages(chatId, messages.asList(), HistoryLoadSource.REFRESH)
                        // Refresh never moves initialReady or the boundary beyond "history exists".
                        history.markLoadableIfUnknown(chatId)
                    }
                    history.finish(request)
                    syncHistoryHasMore(chatId)
                    GlazeLog.historyNetwork(
                        chatId,
                        messages.size,
                        SystemClock.elapsedRealtime() - startedAt,
                        false,
                    )
                }
            },
            onFailure = { error ->
                history.withLock {
                    if (history.finish(request)) GlazeLog.e("History/Refresh", "chatId=$chatId failed: $error")
                }
            },
        )
    }

    /** Caller holds the history lock. */
    private fun loadInitial(chatId: Long) {
        val request = history.begin(chatId, HistorySlot.INITIAL) ?: return
        val startedAt = SystemClock.elapsedRealtime()
        send(
            TdApi.GetChatHistory(chatId, 0, 0, HistoryPolicy.INITIAL_PAGE_SIZE, true),
            result = { result ->
                history.withLock {
                    if (history.owner(request) == null) return@withLock
                    val messages = (result as? TdApi.Messages)?.messages.orEmpty()
                    GlazeLog.historyLocal(chatId, messages.size, SystemClock.elapsedRealtime() - startedAt)
                    if (messages.isNotEmpty()) {
                        // Local page renders immediately but never proves the boundary.
                        mergeMessages(chatId, messages.asList(), HistoryLoadSource.INITIAL)
                        publishInitialViewport(chatId, request)
                    }
                    loadInitialNetworkPage(chatId, request)
                }
            },
            onFailure = { error ->
                history.withLock {
                    if (history.owner(request) == null) return@withLock
                    GlazeLog.e("History/Initial", "local chatId=$chatId failed: $error")
                    loadInitialNetworkPage(chatId, request)
                }
            },
        )
    }

    /** Second stage of the same INITIAL request; caller holds the history lock. */
    private fun loadInitialNetworkPage(chatId: Long, request: HistoryRequest) {
        val startedAt = SystemClock.elapsedRealtime()
        send(
            TdApi.GetChatHistory(chatId, 0, 0, HistoryPolicy.INITIAL_PAGE_SIZE, false),
            result = { result ->
                history.withLock {
                    if (history.owner(request) == null) return@withLock
                    val messages = (result as? TdApi.Messages)?.messages.orEmpty()
                    if (messages.isNotEmpty()) {
                        mergeMessages(chatId, messages.asList(), HistoryLoadSource.INITIAL)
                    }
                    publishInitialViewport(chatId, request)
                    val retained = messageHistory[chatId]?.size ?: 0
                    val outcome =
                        if (retained > 0) InitialOutcome.LOADED else InitialOutcome.EMPTY
                    history.completeInitial(request, outcome)
                    syncHistoryHasMore(chatId)
                    GlazeLog.historyNetwork(
                        chatId,
                        messages.size,
                        SystemClock.elapsedRealtime() - startedAt,
                        history.peek(chatId)?.boundary == HistoryBoundary.END_REACHED,
                    )
                }
            },
            onFailure = { error ->
                history.withLock {
                    if (history.owner(request) == null) return@withLock
                    GlazeLog.e("History/Initial", "network chatId=$chatId failed: $error")
                    // Anything retained (local page) is a usable viewport; otherwise stay unready
                    // and keep the buffered realtime messages for the next attempt.
                    val retained = messageHistory[chatId]?.size ?: 0
                    val outcome =
                        if (retained > 0) InitialOutcome.LOADED else InitialOutcome.FAILED
                    history.completeInitial(request, outcome)
                    syncHistoryHasMore(chatId)
                }
            },
        )
    }

    /**
     * Marks the viewport publishable and folds in realtime messages that arrived before the
     * first page, so they appear exactly once and never as a lone bubble.
     * Caller holds the history lock.
     */
    private fun publishInitialViewport(chatId: Long, request: HistoryRequest) {
        if (!history.markInitialReady(request)) return
        // The published page holds the newest copy of anything it shares with the buffer.
        val retainedIds = (messageHistory[chatId] ?: emptyArray()).mapTo(HashSet<Long>()) { it.id }
        val buffered = history.drainPending(chatId, retainedIds)
        if (buffered.isNotEmpty()) {
            GlazeLog.d("History/Realtime", "chatId=$chatId merged buffered=${buffered.size}")
            mergeMessages(chatId, buffered, HistoryLoadSource.REALTIME)
        }
        syncHistoryHasMore(chatId)
    }

    /** Caller holds the history lock. */
    private fun touchRetention(chatId: Long) {
        for (evicted in history.touch(chatId)) {
            messageHistory.remove(evicted)
            mutableMessages.value = mutableMessages.value - evicted
            mutableHistoryLoading.value = mutableHistoryLoading.value - evicted
            mutableHistoryHasMore.value = mutableHistoryHasMore.value - evicted
            GlazeLog.retentionEvict(evicted, history.retainedChats())
        }
    }

    /** Returns true when the retained history was compacted. Caller holds the history lock. */
    private fun trimHistoryIfInactive(chatId: Long): Boolean {
        val retained = messageHistory[chatId] ?: return false
        val active = history.peek(chatId)?.active == true
        if (!HistoryPolicy.canTrim(active, retained.size)) return false
        val trimmed = HistoryMerge.trimToNewest(retained, HistoryPolicy.MAX_MESSAGES_PER_CHAT)
        messageHistory[chatId] = trimmed
        // The discarded messages only left process memory, so older history is reachable again.
        history.onRetentionTrimmed(chatId)
        syncHistoryLoading(chatId)
        syncHistoryHasMore(chatId)
        GlazeLog.d("History/Retention", "compact chatId=$chatId size=${trimmed.size}")
        return true
    }

    private lateinit var storageContext: Context
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publishNetworkType()
        override fun onLost(network: Network) = publishNetworkType()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publishNetworkType()
    }

    private fun observeNetwork() {
        connectivityManager = storageContext.getSystemService(ConnectivityManager::class.java)
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            networkCallback,
        )
        publishNetworkType()
    }

    private fun publishNetworkType() {
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val type = when {
            capabilities == null -> TdApi.NetworkTypeNone()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TdApi.NetworkTypeMobile()
            else -> TdApi.NetworkTypeOther()
        }
        send(TdApi.SetNetworkType(type))
    }

    private fun handleUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateConnectionState) {
            mutableConnectionUiState.value = connectionUiStateFor(update.state)
        }
        if (update is TdApi.Update) handleChatUpdate(update)
        val authorizationUpdate = update as? TdApi.UpdateAuthorizationState ?: return
        when (val authorizationState = authorizationUpdate.authorizationState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                Log.i("TdLib", "authorization: wait_tdlib_parameters")
                parametersRequested = true
                sendParameters()
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                Log.i("TdLib", "authorization: wait_phone_number")
                val notice = authNotice
                authNotice = null
                mutableState.value = AuthUiState.Phone(
                    phoneNumber = preferences.getString("pending_phone", "") ?: "",
                    error = notice,
                )
            }
            is TdApi.AuthorizationStateWaitCode -> {
                Log.i("TdLib", "authorization: wait_code")
                mutableState.value = if (restoringPendingCode) {
                    restoringPendingCode = false
                    AuthUiState.Phone(preferences.getString("pending_phone", "") ?: "")
                } else {
                    AuthUiState.Code()
                }
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                Log.i("TdLib", "authorization: wait_password")
                mutableState.value = AuthUiState.Password(authorizationState.passwordHint)
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                Log.i("TdLib", "authorization: wait_registration")
                resetError = "Аккаунт с этим номером не существует"
                cancelAuthorization()
            }
            is TdApi.AuthorizationStateReady -> {
                Log.i("TdLib", "authorization: ready")
                preferences.edit().remove("pending_phone").apply()
                mutableState.value = AuthUiState.Ready
                loadCurrentUser()
                loadChats()
            }
            is TdApi.AuthorizationStateLoggingOut -> mutableState.value = AuthUiState.LoggingOut
            is TdApi.AuthorizationStateClosed -> {
                if (resetRequested) restartClient()
                else mutableState.value = AuthUiState.Phone()
            }
            else -> mutableState.value = AuthUiState.Initializing
        }
    }

    @Synchronized
    private fun sendParameters() {
        if (parametersSent) return
        val activeClient = client ?: return
        val context = storageContext
        val tdlibDirectory = context.filesDir.resolve("tdlib").also { it.mkdirs() }
        val parameters = TdApi.SetTdlibParameters(
            false,
            tdlibDirectory.resolve("database").absolutePath,
            tdlibDirectory.resolve("files").absolutePath,
            ByteArray(0),
            true,
            true,
            true,
            true,
            BuildConfig.TELEGRAM_APP_ID.toIntOrNull() ?: 0,
            BuildConfig.TELEGRAM_APP_HASH,
            Locale.getDefault().toLanguageTag(),
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            BuildConfig.VERSION_NAME,
        )
        parametersSent = true
        activeClient.send(parameters, ::handleResult, ::handleException)
    }

    private fun loadChats() {
        suppressChatPublishing = true
        send(
            TdApi.LoadChats(TdApi.ChatListMain(), 100),
            result = {
                suppressChatPublishing = false
                mutableChatListLoaded.value = true
                publishChats()
            },
            onFailure = {
                suppressChatPublishing = false
                mutableChatListLoaded.value = true
                publishChats()
            },
        )
    }

    private fun send(
        function: TdApi.Function<*>,
        result: (TdApi.Object) -> Unit = ::ignoreResult,
        reportToAuth: Boolean = false,
        onFailure: ((String) -> Unit)? = null,
    ) {
        val activeClient = client
        if (activeClient == null) {
            onFailure?.invoke("TDLib client is not initialized")
            handleException(IllegalStateException("TDLib client is not initialized"))
            return
        }
        activeClient.send(
            function,
            { response ->
                if (response is TdApi.Error) {
                    onFailure?.invoke(userFacingError(response.message))
                    if (reportToAuth) handleTdError(response) else logTdError(response)
                }
                else result(response)
            },
            { throwable ->
                onFailure?.invoke(throwable.message ?: "TDLib request failed")
                if (reportToAuth) handleException(throwable) else logException(throwable)
            },
        )
    }

    private fun sendAuth(function: TdApi.Function<*>) {
        send(function, reportToAuth = true)
    }

    private fun beginSubmit(): Boolean {
        return when (val current = mutableState.value) {
            is AuthUiState.Phone -> if (current.submitting) false else {
                mutableState.value = current.copy(error = null, submitting = true)
                true
            }
            is AuthUiState.Code -> if (current.submitting) false else {
                mutableState.value = current.copy(error = null, submitting = true)
                true
            }
            is AuthUiState.Password -> if (current.submitting) false else {
                mutableState.value = current.copy(error = null, submitting = true)
                true
            }
            else -> false
        }
    }

    private fun ignoreResult(result: TdApi.Object) = Unit

    private fun handleResult(result: TdApi.Object) {
        if (result is TdApi.Error) handleTdError(result)
    }

    private fun handleTdError(error: TdApi.Error) {
        val message = userFacingError(error.message)
        Log.e("TdLib", "request failed ${error.code}: ${error.message}")
        mutableState.value = mutableState.value.withError(message)
    }

    private fun logTdError(error: TdApi.Error) {
        Log.w("TdLib", "background request failed ${error.code}: ${error.message}")
    }

    private fun logException(throwable: Throwable) {
        Log.w("TdLib", "background request failed", throwable)
    }

    private fun restartClient() {
        client = null
        parametersRequested = false
        parametersSent = false
        restoringPendingCode = false
        resetRequested = false
        preferences.edit().remove("pending_phone").apply()
        val error = resetError
        resetError = null
        authNotice = error
        // History lock first, then chatMap – the one lock order used everywhere.
        history.withLock {
            history.clear()
            synchronized(chatMap) {
                chatMap.clear()
                users.clear()
                basicGroups.clear()
                supergroups.clear()
                onlineMemberCounts.clear()
                chatActions.clear()
                messageHistory.clear()
            }
        }
        pendingUserRequests.clear()
        finalizedTemporaryMessageIds.clear()
        currentUser = null
        mutableChats.value = emptyList()
        mutableChatListLoaded.value = false
        mutableMessages.value = emptyMap()
        mutableHistoryLoading.value = emptyMap()
        mutableHistoryHasMore.value = emptyMap()
        mutableAccount.value = null
        mutableState.value = AuthUiState.Initializing
        client = Client.create(::handleUpdate, ::logException, ::logException)
    }

    private fun upsertChat(result: TdApi.Object) {
        val chat = result as? TdApi.Chat ?: return
        history.withLock {
            synchronized(chatMap) { chatMap[chat.id] = chat }
            requestAvatar(chat)
            requestMessageAuthor(chat.lastMessage)
            requestChatMetadata(chat)
            if (!suppressChatPublishing) publishChats()
            messageHistory.keys.toList().forEach(::publishMessages)
        }
    }

    /**
     * History state is always taken before [chatMap] so the two locks keep one order across
     * TDLib update threads, TDLib request callbacks and the UI thread.
     */
    private fun handleChatUpdate(update: TdApi.Update): Boolean = history.withLock {
        synchronized(chatMap) {
            when (update) {
                is TdApi.UpdateNewChat -> chatMap[update.chat.id] = update.chat
                is TdApi.UpdateChatTitle -> chatMap[update.chatId]?.title = update.title
                is TdApi.UpdateChatPhoto -> chatMap[update.chatId]?.let { chat ->
                    chat.photo = update.photo
                    requestAvatar(chat)
                }
                is TdApi.UpdateChatLastMessage -> {
                    chatMap[update.chatId]?.let { chat ->
                        chat.lastMessage = update.lastMessage
                        chat.positions = update.positions
                    }
                    requestMessageAuthor(update.lastMessage)
                }
                is TdApi.UpdateChatReadInbox -> chatMap[update.chatId]?.unreadCount = update.unreadCount
                is TdApi.UpdateChatUnreadMentionCount -> {
                    chatMap[update.chatId]?.unreadMentionCount = update.unreadMentionCount
                }
                is TdApi.UpdateMessageMentionRead -> {
                    chatMap[update.chatId]?.unreadMentionCount = update.unreadMentionCount
                    val retained = messageHistory[update.chatId]
                    if (retained != null) {
                        messageHistory[update.chatId] = retained.map { message ->
                            if (message.id == update.messageId) message.apply { containsUnreadMention = false }
                            else message
                        }.toTypedArray()
                        publishMessages(update.chatId)
                    }
                }
                is TdApi.UpdateChatPermissions -> chatMap[update.chatId]?.permissions = update.permissions
                is TdApi.UpdateUser -> {
                    if (currentUser?.id == update.user.id) {
                        currentUser = update.user
                        update.user.profilePhoto?.small?.let(::requestFile)
                        publishAccount()
                    }
                    upsertUser(update.user)
                }
                is TdApi.UpdateBasicGroup -> basicGroups[update.basicGroup.id] = update.basicGroup
                is TdApi.UpdateSupergroup -> supergroups[update.supergroup.id] = update.supergroup
                is TdApi.UpdateChatOnlineMemberCount -> onlineMemberCounts[update.chatId] = update.onlineMemberCount
                is TdApi.UpdateChatAction -> {
                    val key = senderKey(update.senderId)
                    val actions = chatActions.getOrPut(update.chatId) { LinkedHashMap() }
                    if (update.action is TdApi.ChatActionCancel) actions.remove(key)
                    else actions[key] = update.senderId to update.action
                    requestSender(update.senderId)
                }
                is TdApi.UpdateChatPosition -> {
                    val chat = chatMap[update.chatId]
                    if (chat != null && update.position.list is TdApi.ChatListMain) {
                        chat.positions = chat.positions.filterNot { it.list is TdApi.ChatListMain }.toMutableList()
                            .apply { if (update.position.order != 0L) add(update.position) }
                            .toTypedArray()
                    }
                }
                is TdApi.UpdateNewMessage -> {
                    val chatId = update.message.chatId
                    val retained = messageHistory[chatId]
                    val decision = history.classifyRealtime(
                        chatId = chatId,
                        messageId = update.message.id,
                        alreadyRetained = retained?.any { it.id == update.message.id } == true,
                        hasRetainedHistory = retained != null,
                    )
                    when (decision) {
                        RealtimeDecision.MERGE ->
                            mergeMessages(chatId, listOf(update.message), HistoryLoadSource.REALTIME)
                        // Before the first page: hold the message back instead of publishing a
                        // lone bubble. Drained by publishInitialViewport.
                        RealtimeDecision.BUFFER -> {
                            history.buffer(chatId, update.message)
                            GlazeLog.d(
                                "History/Realtime",
                                "chatId=$chatId buffered pending=${history.pendingCount(chatId)}",
                            )
                        }
                        RealtimeDecision.DROP -> Unit
                    }
                }
                is TdApi.UpdateMessageSendSucceeded -> {
                    finalizedTemporaryMessageIds += update.oldMessageId
                    replaceMessage(update.message.chatId, update.oldMessageId, update.message)
                }
                is TdApi.UpdateMessageSendFailed -> {
                    finalizedTemporaryMessageIds += update.oldMessageId
                    replaceMessage(update.message.chatId, update.oldMessageId, update.message)
                }
                is TdApi.UpdateMessageContent -> {
                    // A message may be retained, or still buffered awaiting the first page — an
                    // edit has to reach whichever copy exists, or a stale body survives the drain.
                    history.updatePendingContent(update.chatId, update.messageId, update.newContent)
                    // Only chats whose history we retain; never materialize an empty viewport.
                    val retained = messageHistory[update.chatId]
                    if (retained != null) {
                        messageHistory[update.chatId] = retained.map { message ->
                            if (message.id == update.messageId) message.apply { content = update.newContent }
                            else message
                        }.toTypedArray()
                        publishMessages(update.chatId)
                    }
                }
                is TdApi.UpdateDeleteMessages -> {
                    val deletedIds = update.messageIds.toHashSet()
                    // Drop buffered copies too, so a drain can never resurrect a deleted message.
                    history.removePending(update.chatId, deletedIds)
                    val retained = messageHistory[update.chatId]
                    if (retained != null) {
                        messageHistory[update.chatId] = retained.filterNot { it.id in deletedIds }.toTypedArray()
                        publishMessages(update.chatId)
                    }
                }
                is TdApi.UpdateChatReadOutbox -> {
                    chatMap[update.chatId]?.lastReadOutboxMessageId = update.lastReadOutboxMessageId
                    if (messageHistory.containsKey(update.chatId)) publishMessages(update.chatId)
                }
                is TdApi.UpdateChatNotificationSettings -> {
                    chatMap[update.chatId]?.notificationSettings = update.notificationSettings
                }
                is TdApi.UpdateFile -> updateAvatar(update.file)
                else -> return@withLock false
            }
        }
        if (update is TdApi.UpdateNewChat) {
            requestAvatar(update.chat)
            requestMessageAuthor(update.chat.lastMessage)
            requestChatMetadata(update.chat)
        }
        if (!suppressChatPublishing) publishChats()
        true
    }

    // Background warmup – bounded, local only, coordinated with history state
    private var warmupInFlight = false

    private fun maybeWarmupRecentChats() {
        if (mutableState.value != AuthUiState.Ready) return
        history.withLock {
            if (warmupInFlight) return@withLock
            val now = SystemClock.elapsedRealtime()
            val candidates = mutableChats.value.take(3).map { it.id }.filter { warmupNeeded(it, now) }.take(2)
            if (candidates.isEmpty()) return@withLock
            warmupInFlight = true
            GlazeLog.warmup("start", "candidates=$candidates")
            warmupNext(candidates, 0)
        }
    }

    /** Caller holds the history lock. */
    private fun warmupNeeded(chatId: Long, nowMs: Long): Boolean =
        history.warmupAllowed(chatId, nowMs) &&
            (messageHistory[chatId]?.size ?: 0) < HistoryPolicy.WARMUP_MIN_MESSAGES

    /** Caller holds the history lock. */
    private fun warmupNext(candidates: List<Long>, index: Int) {
        if (index >= candidates.size) {
            warmupInFlight = false
            GlazeLog.warmup("end", "warmed=$index retained=${history.retainedChats()}")
            return
        }
        val chatId = candidates[index]
        if (!warmupNeeded(chatId, SystemClock.elapsedRealtime())) {
            warmupNext(candidates, index + 1)
            return
        }
        history.ensure(chatId)
        // Join the retained LRU before the request, not after a successful merge: an empty or
        // failed warmup must be evictable too, or its state would live outside the cap forever.
        touchRetention(chatId)
        val request = history.begin(chatId, HistorySlot.WARMUP) ?: run {
            warmupNext(candidates, index + 1)
            return
        }
        send(
            TdApi.GetChatHistory(chatId, 0, 0, HistoryPolicy.WARMUP_PAGE_SIZE, true),
            result = { result ->
                history.withLock {
                    // An evicted or reopened chat kills the callback; the chain still continues.
                    if (history.owner(request) != null) {
                        val messages = (result as? TdApi.Messages)?.messages.orEmpty()
                        if (messages.isNotEmpty()) {
                            // Warmup never marks initialReady and never prefetches media.
                            mergeMessages(chatId, messages.asList(), HistoryLoadSource.WARMUP)
                            history.markLoadableIfUnknown(chatId)
                            syncHistoryHasMore(chatId)
                        } else {
                            history.holdWarmup(
                                chatId,
                                SystemClock.elapsedRealtime() + HistoryPolicy.WARMUP_COOLDOWN_MS,
                            )
                        }
                        history.finish(request)
                    }
                    warmupNext(candidates, index + 1)
                }
            },
            onFailure = {
                history.withLock {
                    if (history.owner(request) != null) {
                        history.holdWarmup(
                            chatId,
                            SystemClock.elapsedRealtime() + HistoryPolicy.WARMUP_COOLDOWN_MS,
                        )
                        history.finish(request)
                    }
                    warmupNext(candidates, index + 1)
                }
            },
        )
    }

    private fun publishChats() {
        mutableChats.value = synchronized(chatMap) {
            chatMap.values
                .map { chat ->
                    val position = chat.positions.firstOrNull { it.list is TdApi.ChatListMain }
                    val kind = when (val type = chat.type) {
                        is TdApi.ChatTypePrivate -> ChatKind.Private
                        is TdApi.ChatTypeBasicGroup -> ChatKind.BasicGroup
                        is TdApi.ChatTypeSupergroup -> if (type.isChannel) ChatKind.Channel else ChatKind.Supergroup
                        is TdApi.ChatTypeSecret -> ChatKind.Secret
                        else -> ChatKind.Private
                    }
                    val last = chat.lastMessage
                    val isOutgoing = last?.isOutgoing == true
                    val isSaved = (chat.type as? TdApi.ChatTypePrivate)?.userId?.let { it == currentUser?.id } == true ||
                        (chat.type as? TdApi.ChatTypeSecret)?.userId?.let { it == currentUser?.id } == true
                    val isMuted = (chat.notificationSettings?.muteFor ?: 0) != 0
                    ChatSummary(
                        id = chat.id,
                        title = chat.title,
                        lastMessageAuthor = messageAuthorOrEmpty(last),
                        lastMessage = chatListPreview(
                            messageAuthorOrEmpty(last),
                            messagePreview(last),
                            kind,
                            isOutgoing,
                            isSaved,
                        ),
                        lastMessageTime = last?.let(::formatMessageTime).orEmpty(),
                        unreadCount = chat.unreadCount,
                        isPinned = position?.isPinned == true,
                        order = position?.order ?: 0L,
                        avatarPath = chat.photo?.small?.local?.path?.takeIf { chat.photo?.small?.local?.isDownloadingCompleted == true },
                        kind = kind,
                        subtitle = chatSubtitle(chat),
                        canSendMessages = chat.permissions.canSendBasicMessages,
                        unreadMentionCount = chat.unreadMentionCount,
                        lastMessageIsOutgoing = isOutgoing,
                        lastMessageDeliveryState = last?.let { deliveryState(it) }?.takeIf { isOutgoing },
                        isMuted = isMuted,
                        isSavedMessages = isSaved,
                    )
                }
                .filter { it.order != 0L }
                .sortedWith(compareByDescending<ChatSummary> { it.order }.thenByDescending { it.id })
        }
        // trigger small bounded warmup for top chats (local only)
        maybeWarmupRecentChats()
    }

    private fun publishMessages(chatId: Long) {
        val items = messageHistory[chatId].orEmpty().map(::projectMessage)
        mutableMessages.value = mutableMessages.value + (chatId to items)
    }

    private fun projectMessage(message: TdApi.Message): ChatMessage {
        val media = mediaContent(message.content)
        return ChatMessage(
                chatId = message.chatId,
                id = message.id,
                author = messageAuthor(message),
                authorAvatarPath = messageAuthorAvatar(message),
                text = media.text,
                time = SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(Date(message.date.toLong() * 1000L)),
                isOutgoing = message.isOutgoing,
                replyToMessageId = (message.replyTo as? TdApi.MessageReplyToMessage)?.messageId,
                replyToChatId = (message.replyTo as? TdApi.MessageReplyToMessage)?.chatId,
                deliveryState = deliveryState(message),
                mediaKind = media.kind,
                mediaPreviewPath = media.previewPath,
                mediaOpenPath = media.openPath,
                mediaFileId = media.fileId,
                mediaMimeType = media.mimeType,
                mediaLabel = media.label,
                mediaAlbumId = message.mediaAlbumId,
                mediaWidth = media.width,
                mediaHeight = media.height,
                mediaMinithumbnail = media.minithumbnail,
                textStyles = formattedTextStyles(media.formattedText),
                forwardedFrom = forwardOriginName(message.forwardInfo?.origin),
                containsUnreadMention = message.containsUnreadMention,
                contentPreview = dialogPreview(message.content),
                date = message.date,
                senderKey = senderKey(message.senderId),
                isService = media.kind == MediaKind.Service || media.kind == MediaKind.Unsupported,
            )
    }

    private fun messageAuthor(message: TdApi.Message): String {
        if (message.isOutgoing) return "Вы"
        return when (val sender = message.senderId) {
            is TdApi.MessageSenderUser -> users[sender.userId]?.displayName() ?: "Пользователь"
            is TdApi.MessageSenderChat -> synchronized(chatMap) {
                chatMap[sender.chatId]?.title ?: "Чат"
            }
            else -> ""
        }
    }

    private fun messageAuthorOrEmpty(message: TdApi.Message?): String =
        if (message == null) "" else messageAuthor(message)

    private fun requestMessageAuthor(message: TdApi.Message?) {
        message?.senderId?.let(::requestSender)
    }

    private fun requestSender(sender: TdApi.MessageSender) {
        when (sender) {
            is TdApi.MessageSenderUser -> requestUser(sender.userId)
            is TdApi.MessageSenderChat -> if (!chatMap.containsKey(sender.chatId)) {
                send(TdApi.GetChat(sender.chatId), result = ::upsertChat)
            }
        }
    }

    private fun requestUser(userId: Long) {
        if (users.containsKey(userId) || !pendingUserRequests.add(userId)) return
        send(
            TdApi.GetUser(userId),
            result = { result ->
                pendingUserRequests.remove(userId)
                val user = result as? TdApi.User ?: return@send
                upsertUser(user)
            },
            onFailure = { pendingUserRequests.remove(userId) },
        )
    }

    private fun upsertUser(user: TdApi.User) {
        history.withLock {
            synchronized(chatMap) { users[user.id] = user }
            user.profilePhoto?.small?.let(::requestFile)
            if (!suppressChatPublishing) publishChats()
            messageHistory.keys.toList().forEach(::publishMessages)
        }
    }

    private fun TdApi.User.displayName(): String = listOf(firstName, lastName)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .ifBlank { usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "Пользователь" }

    private fun messageAuthorAvatar(message: TdApi.Message): String? = when (val sender = message.senderId) {
        is TdApi.MessageSenderUser -> users[sender.userId]?.profilePhoto?.small?.local?.let { local ->
            local.path.takeIf { local.isDownloadingCompleted }
        }
        is TdApi.MessageSenderChat -> chatMap[sender.chatId]?.photo?.small?.local?.let { local ->
            local.path.takeIf { local.isDownloadingCompleted }
        }
        else -> null
    }

    private fun requestChatMetadata(chat: TdApi.Chat) {
        when (val type = chat.type) {
            is TdApi.ChatTypePrivate -> requestUser(type.userId)
            is TdApi.ChatTypeSecret -> requestUser(type.userId)
            is TdApi.ChatTypeBasicGroup -> if (!basicGroups.containsKey(type.basicGroupId)) {
                send(TdApi.GetBasicGroup(type.basicGroupId), result = { result ->
                    (result as? TdApi.BasicGroup)?.let {
                        basicGroups[it.id] = it
                        publishChats()
                    }
                })
            }
            is TdApi.ChatTypeSupergroup -> if (!supergroups.containsKey(type.supergroupId)) {
                send(TdApi.GetSupergroup(type.supergroupId), result = { result ->
                    (result as? TdApi.Supergroup)?.let {
                        supergroups[it.id] = it
                        publishChats()
                    }
                })
            }
            else -> Unit
        }
    }

    private fun chatSubtitle(chat: TdApi.Chat): String {
        val action = chatActions[chat.id]?.values?.firstOrNull { (sender, _) ->
            (sender as? TdApi.MessageSenderUser)?.userId != currentUser?.id
        }
        if (action != null) {
            val (sender, chatAction) = action
            val author = when (sender) {
                is TdApi.MessageSenderUser -> users[sender.userId]?.displayName()
                is TdApi.MessageSenderChat -> chatMap[sender.chatId]?.title
                else -> null
            }
            return listOfNotNull(author, actionLabel(chatAction)).joinToString(" ")
        }

        val memberCount = when (val type = chat.type) {
            is TdApi.ChatTypeBasicGroup -> basicGroups[type.basicGroupId]?.memberCount
            is TdApi.ChatTypeSupergroup -> supergroups[type.supergroupId]?.memberCount
            else -> null
        }
        if (memberCount != null) {
            val noun = if ((chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true) "подписчиков" else "участников"
            val online = onlineMemberCounts[chat.id]?.takeIf { it > 0 }
            return if (online != null) "$memberCount $noun, $online онлайн" else "$memberCount $noun"
        }
        return when (chat.type) {
            is TdApi.ChatTypePrivate -> "личный чат"
            is TdApi.ChatTypeSecret -> "секретный чат"
            is TdApi.ChatTypeBasicGroup -> "группа"
            is TdApi.ChatTypeSupergroup -> if ((chat.type as TdApi.ChatTypeSupergroup).isChannel) "канал" else "супергруппа"
            else -> "Telegram"
        }
    }

    private fun actionLabel(action: TdApi.ChatAction): String = when (action) {
        is TdApi.ChatActionTyping -> "печатает…"
        is TdApi.ChatActionRecordingVoiceNote -> "записывает голосовое…"
        is TdApi.ChatActionRecordingVideo, is TdApi.ChatActionRecordingVideoNote -> "записывает видео…"
        is TdApi.ChatActionUploadingPhoto -> "отправляет фото…"
        is TdApi.ChatActionUploadingVideo -> "отправляет видео…"
        is TdApi.ChatActionUploadingDocument -> "отправляет файл…"
        is TdApi.ChatActionChoosingSticker -> "выбирает стикер…"
        else -> "активен…"
    }

    private fun senderKey(sender: TdApi.MessageSender): String = when (sender) {
        is TdApi.MessageSenderUser -> "user:${sender.userId}"
        is TdApi.MessageSenderChat -> "chat:${sender.chatId}"
        else -> sender.toString()
    }

    private fun requestForwardOrigin(message: TdApi.Message) {
        when (val origin = message.forwardInfo?.origin) {
            is TdApi.MessageOriginUser -> requestUser(origin.senderUserId)
            is TdApi.MessageOriginChat -> requestSender(TdApi.MessageSenderChat(origin.senderChatId))
            is TdApi.MessageOriginChannel -> requestSender(TdApi.MessageSenderChat(origin.chatId))
        }
    }

    private fun forwardOriginName(origin: TdApi.MessageOrigin?): String? = when (origin) {
        is TdApi.MessageOriginUser -> users[origin.senderUserId]?.displayName() ?: "Пользователь"
        is TdApi.MessageOriginChat -> chatMap[origin.senderChatId]?.title
            ?: origin.authorSignature.takeIf(String::isNotBlank)
            ?: "Чат"
        is TdApi.MessageOriginChannel -> chatMap[origin.chatId]?.title
            ?: origin.authorSignature.takeIf(String::isNotBlank)
            ?: "Канал"
        is TdApi.MessageOriginHiddenUser -> origin.senderName
        else -> null
    }

    private fun formattedTextStyles(formattedText: TdApi.FormattedText?): List<MessageTextStyle> =
        formattedText?.entities.orEmpty().mapNotNull { entity ->
            val kind = when (entity.type) {
                is TdApi.TextEntityTypeBold -> MessageTextStyleKind.Bold
                is TdApi.TextEntityTypeItalic -> MessageTextStyleKind.Italic
                is TdApi.TextEntityTypeUnderline -> MessageTextStyleKind.Underline
                is TdApi.TextEntityTypeStrikethrough -> MessageTextStyleKind.Strikethrough
                is TdApi.TextEntityTypeCode, is TdApi.TextEntityTypePre, is TdApi.TextEntityTypePreCode ->
                    MessageTextStyleKind.Code
                is TdApi.TextEntityTypeUrl,
                is TdApi.TextEntityTypeTextUrl,
                is TdApi.TextEntityTypeMention,
                is TdApi.TextEntityTypeMentionName,
                is TdApi.TextEntityTypeEmailAddress,
                is TdApi.TextEntityTypePhoneNumber -> MessageTextStyleKind.Link
                is TdApi.TextEntityTypeSpoiler -> MessageTextStyleKind.Spoiler
                is TdApi.TextEntityTypeBlockQuote, is TdApi.TextEntityTypeExpandableBlockQuote ->
                    MessageTextStyleKind.Quote
                else -> null
            } ?: return@mapNotNull null
            MessageTextStyle(entity.offset, entity.length, kind)
        }

    private fun deliveryState(message: TdApi.Message): DeliveryState {
        return when (message.sendingState) {
            is TdApi.MessageSendingStatePending -> DeliveryState.Sending
            is TdApi.MessageSendingStateFailed -> DeliveryState.Failed
            else -> {
                val lastRead = synchronized(chatMap) {
                    chatMap[message.chatId]?.lastReadOutboxMessageId ?: 0L
                }
                if (message.isOutgoing && message.id <= lastRead) DeliveryState.Read else DeliveryState.Sent
            }
        }
    }

    /**
     * Caller holds the history lock. Media prefetch follows [source]: warmup fills history
     * only, so it must never pull files for chats the user has not opened.
     */
    private fun mergeMessages(chatId: Long, messages: List<TdApi.Message>, source: HistoryLoadSource) {
        // An active chat may exceed the per-chat cap while paginating; trimming happens on close.
        messageHistory[chatId] = HistoryMerge.merge(messageHistory[chatId] ?: emptyArray(), messages)
        touchRetention(chatId)
        for (message in messages) {
            requestMessageAuthor(message)
            requestForwardOrigin(message)
            if (source.requestsMedia) requestMessageMedia(message)
        }
        trimHistoryIfInactive(chatId)
        publishMessages(chatId)
    }

    /** Caller holds the history lock. */
    private fun replaceMessage(chatId: Long, oldMessageId: Long, message: TdApi.Message) {
        messageHistory[chatId] =
            HistoryMerge.replace(messageHistory[chatId] ?: emptyArray(), oldMessageId, message)
        requestMessageAuthor(message)
        requestForwardOrigin(message)
        requestMessageMedia(message)
        publishMessages(chatId)
    }

    private fun loadCurrentUser() {
        send(
            TdApi.GetMe(),
            result = { result ->
                val user = result as? TdApi.User ?: return@send
                currentUser = user
                user.profilePhoto?.small?.let(::requestFile)
                publishAccount()
            },
        )
    }

    private fun publishAccount() {
        val user = currentUser ?: return
        val name = listOf(user.firstName, user.lastName).filter(String::isNotBlank).joinToString(" ")
        val username = user.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" }
        mutableAccount.value = AccountSummary(
            name = name,
            detail = username ?: user.phoneNumber.takeIf(String::isNotBlank).orEmpty(),
            avatarPath = user.profilePhoto?.small?.local?.path
                ?.takeIf { user.profilePhoto?.small?.local?.isDownloadingCompleted == true },
        )
    }

    private fun messagePreview(message: TdApi.Message?): String {
        val content = message?.content ?: return ""
        return dialogPreview(content)
    }

    private fun dialogPreview(content: TdApi.MessageContent): String = when (content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> mediaDialogLabel("Фото", content.caption.text)
        is TdApi.MessageVideo -> mediaDialogLabel("Видео", content.caption.text)
        is TdApi.MessageVideoNote -> "Видео-сообщение"
        is TdApi.MessageAnimation -> mediaDialogLabel("GIF", content.caption.text)
        is TdApi.MessageAudio -> content.audio.let { audio ->
            listOf(audio.performer, audio.title).filter(String::isNotBlank).joinToString(" — ").ifBlank { "Аудио" }
        }
        is TdApi.MessageVoiceNote -> mediaDialogLabel("Голосовое сообщение", content.caption.text)
        is TdApi.MessageDocument -> mediaDialogLabel(
            content.document.fileName.ifBlank { "Файл" },
            content.caption.text,
        )
        is TdApi.MessageSticker -> listOf(content.sticker.emoji, "Стикер").filter(String::isNotBlank).joinToString(" ")
        is TdApi.MessageLocation -> "Геопозиция"
        is TdApi.MessageVenue -> content.venue.title.ifBlank { "Место" }
        is TdApi.MessageContact -> "Контакт"
        is TdApi.MessagePoll -> content.poll.question.text
        else -> mediaContent(content).let { it.text.ifBlank { it.label ?: "Сообщение" } }
    }

    private fun mediaDialogLabel(label: String, caption: String): String =
        if (caption.isBlank()) label else "$label: $caption"

    private fun formatMessageTime(message: TdApi.Message): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.date.toLong() * 1000L))

    private fun requestAvatar(chat: TdApi.Chat) {
        val file = chat.photo?.small ?: return
        requestFile(file)
    }

    private fun requestFile(file: TdApi.File, priority: Int = 1) {
        if (file.local.isDownloadingCompleted || !file.local.canBeDownloaded || file.local.isDownloadingActive) return
        send(TdApi.DownloadFile(file.id, priority, 0L, 0L, false))
    }

    private fun updateAvatar(file: TdApi.File) {
        synchronized(chatMap) {
            chatMap.values.forEach { chat ->
                if (chat.photo?.small?.id == file.id) chat.photo?.small = file
            }
        }
        if (currentUser?.profilePhoto?.small?.id == file.id) {
            currentUser?.profilePhoto?.small = file
            publishAccount()
        }
        var authorAvatarUpdated = false
        users.values.forEach { user ->
            if (user.profilePhoto?.small?.id == file.id) {
                user.profilePhoto?.small = file
                authorAvatarUpdated = true
            }
        }
        if (authorAvatarUpdated) messageHistory.keys.forEach(::publishMessages)
        messageHistory.forEach { (chatId, messages) ->
            if (messages.any { updateMessageFile(it.content, file) }) publishMessages(chatId)
        }
    }

    private data class MediaContent(
        val kind: MediaKind,
        val text: String,
        val previewPath: String? = null,
        val openPath: String? = null,
        val fileId: Int? = null,
        val mimeType: String? = null,
        val label: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val minithumbnail: ByteArray? = null,
        val formattedText: TdApi.FormattedText? = null,
    )

    private fun mediaContent(content: TdApi.MessageContent): MediaContent = when (content) {
        is TdApi.MessageText -> MediaContent(MediaKind.Text, content.text.text, formattedText = content.text)
        is TdApi.MessagePhoto -> {
            val target = content.photo.sizes.maxByOrNull { it.width * it.height }
            val preview = photoPreviewSize(content.photo)
            MediaContent(
                MediaKind.Photo,
                content.caption.text,
                previewPath = completedPath(preview?.photo) ?: completedPath(target?.photo),
                openPath = completedPath(target?.photo),
                fileId = target?.photo?.id,
                mimeType = "image/*",
                width = target?.width ?: 0,
                height = target?.height ?: 0,
                minithumbnail = content.photo.minithumbnail?.data,
                formattedText = content.caption,
            )
        }
        is TdApi.MessageVideo -> MediaContent(
            MediaKind.Video,
            content.caption.text,
            previewPath = completedPath(content.video.thumbnail?.file),
            openPath = completedPath(content.video.video),
            fileId = content.video.video.id,
            mimeType = content.video.mimeType,
            label = "${formatDuration(content.video.duration)}",
            width = content.video.width,
            height = content.video.height,
            minithumbnail = content.video.minithumbnail?.data,
            formattedText = content.caption,
        )
        is TdApi.MessageVideoNote -> MediaContent(
            MediaKind.VideoNote,
            "",
            previewPath = completedPath(content.videoNote.thumbnail?.file),
            openPath = completedPath(content.videoNote.video),
            fileId = content.videoNote.video.id,
            mimeType = "video/mp4",
            label = formatDuration(content.videoNote.duration),
            width = content.videoNote.length,
            height = content.videoNote.length,
            minithumbnail = content.videoNote.minithumbnail?.data,
        )
        is TdApi.MessageAnimation -> MediaContent(
            MediaKind.Animation,
            content.caption.text,
            previewPath = completedPath(content.animation.thumbnail?.file),
            openPath = completedPath(content.animation.animation),
            fileId = content.animation.animation.id,
            mimeType = content.animation.mimeType,
            width = content.animation.width,
            height = content.animation.height,
            minithumbnail = content.animation.minithumbnail?.data,
            formattedText = content.caption,
        )
        is TdApi.MessageAudio -> MediaContent(
            MediaKind.Audio,
            content.caption.text,
            previewPath = completedPath(content.audio.albumCoverThumbnail?.file),
            openPath = completedPath(content.audio.audio),
            fileId = content.audio.audio.id,
            mimeType = content.audio.mimeType,
            label = listOf(content.audio.performer, content.audio.title)
                .filter(String::isNotBlank)
                .joinToString(" — ")
                .ifBlank { content.audio.fileName.ifBlank { "Аудио" } },
            formattedText = content.caption,
        )
        is TdApi.MessageDocument -> MediaContent(
            MediaKind.Document,
            content.caption.text,
            previewPath = completedPath(content.document.thumbnail?.file),
            openPath = completedPath(content.document.document),
            fileId = content.document.document.id,
            mimeType = content.document.mimeType,
            label = content.document.fileName.ifBlank { "Файл" },
            width = content.document.thumbnail?.width ?: 0,
            height = content.document.thumbnail?.height ?: 0,
            minithumbnail = content.document.minithumbnail?.data,
            formattedText = content.caption,
        )
        is TdApi.MessageVoiceNote -> MediaContent(
            MediaKind.Voice,
            content.caption.text,
            openPath = completedPath(content.voiceNote.voice),
            fileId = content.voiceNote.voice.id,
            mimeType = content.voiceNote.mimeType,
            label = "Голосовое · ${formatDuration(content.voiceNote.duration)}",
            formattedText = content.caption,
        )
        is TdApi.MessageSticker -> MediaContent(
            MediaKind.Sticker,
            content.sticker.emoji,
            previewPath = completedPath(content.sticker.sticker),
            openPath = completedPath(content.sticker.sticker),
            fileId = content.sticker.sticker.id,
            mimeType = "image/webp",
        )
        is TdApi.MessageAnimatedEmoji -> MediaContent(MediaKind.Text, content.emoji)
        is TdApi.MessageLocation -> MediaContent(
            MediaKind.Location,
            "${content.location.latitude}, ${content.location.longitude}",
            label = if (content.livePeriod > 0) "Геопозиция в реальном времени" else "Геопозиция",
        )
        is TdApi.MessageVenue -> MediaContent(
            MediaKind.Location,
            content.venue.address,
            label = content.venue.title,
        )
        is TdApi.MessageContact -> MediaContent(
            MediaKind.Contact,
            content.contact.phoneNumber,
            label = listOf(content.contact.firstName, content.contact.lastName)
                .filter(String::isNotBlank)
                .joinToString(" "),
        )
        is TdApi.MessagePoll -> MediaContent(
            MediaKind.Poll,
            content.poll.question.text,
            label = "Опрос · ${content.poll.totalVoterCount}",
        )
        is TdApi.MessageExpiredPhoto -> MediaContent(MediaKind.Service, "", label = "Фото уничтожено")
        is TdApi.MessageExpiredVideo -> MediaContent(MediaKind.Service, "", label = "Видео уничтожено")
        is TdApi.MessageExpiredVoiceNote -> MediaContent(MediaKind.Service, "", label = "Голосовое сообщение уничтожено")
        is TdApi.MessageExpiredVideoNote -> MediaContent(MediaKind.Service, "", label = "Видеосообщение уничтожено")
        is TdApi.MessageUnsupported -> MediaContent(MediaKind.Unsupported, "", label = "Неподдерживаемое сообщение")
        else -> MediaContent(
            MediaKind.Service,
            "",
            label = content.javaClass.simpleName.removePrefix("Message").replace(Regex("([a-z])([A-Z])"), "$1 $2"),
        )
    }

    private fun requestMessageMedia(message: TdApi.Message) {
        when (val content = message.content) {
            is TdApi.MessagePhoto -> photoPreviewSize(content.photo)?.photo?.let { requestFile(it, 4) }
            is TdApi.MessageVideo -> content.video.thumbnail?.file?.let { requestFile(it, 4) }
            is TdApi.MessageVideoNote -> content.videoNote.thumbnail?.file?.let { requestFile(it, 4) }
            is TdApi.MessageAnimation -> content.animation.thumbnail?.file?.let { requestFile(it, 4) }
            is TdApi.MessageAudio -> content.audio.albumCoverThumbnail?.file?.let { requestFile(it, 4) }
            is TdApi.MessageDocument -> content.document.thumbnail?.file?.let { requestFile(it, 4) }
            is TdApi.MessageSticker -> requestFile(content.sticker.sticker, 4)
        }
    }

    private fun updateMessageFile(content: TdApi.MessageContent, file: TdApi.File): Boolean = when (content) {
        is TdApi.MessagePhoto -> content.photo.sizes.firstOrNull { it.photo.id == file.id }?.let {
            it.photo = file
            true
        } ?: false
        is TdApi.MessageVideo -> updateThumbnail(content.video.thumbnail, file)
            || updateFile(content.video.video, file) { content.video.video = it }
        is TdApi.MessageVideoNote -> updateThumbnail(content.videoNote.thumbnail, file)
            || updateFile(content.videoNote.video, file) { content.videoNote.video = it }
        is TdApi.MessageAnimation -> updateThumbnail(content.animation.thumbnail, file)
            || updateFile(content.animation.animation, file) { content.animation.animation = it }
        is TdApi.MessageAudio -> updateThumbnail(content.audio.albumCoverThumbnail, file)
            || updateFile(content.audio.audio, file) { content.audio.audio = it }
        is TdApi.MessageDocument -> updateThumbnail(content.document.thumbnail, file)
            || updateFile(content.document.document, file) { content.document.document = it }
        is TdApi.MessageVoiceNote -> updateFile(content.voiceNote.voice, file) { content.voiceNote.voice = it }
        is TdApi.MessageSticker -> if (content.sticker.sticker.id == file.id) {
            content.sticker.sticker = file
            true
        } else false
        else -> false
    }

    private fun updateThumbnail(thumbnail: TdApi.Thumbnail?, file: TdApi.File): Boolean {
        if (thumbnail?.file?.id != file.id) return false
        thumbnail.file = file
        return true
    }

    private fun updateFile(current: TdApi.File, updated: TdApi.File, assign: (TdApi.File) -> Unit): Boolean {
        if (current.id != updated.id) return false
        assign(updated)
        return true
    }

    private fun messageMediaFile(content: TdApi.MessageContent): TdApi.File? = when (content) {
        is TdApi.MessagePhoto -> content.photo.sizes.maxByOrNull { it.width * it.height }?.photo
        is TdApi.MessageVideo -> content.video.video
        is TdApi.MessageVideoNote -> content.videoNote.video
        is TdApi.MessageAnimation -> content.animation.animation
        is TdApi.MessageAudio -> content.audio.audio
        is TdApi.MessageDocument -> content.document.document
        is TdApi.MessageVoiceNote -> content.voiceNote.voice
        is TdApi.MessageSticker -> content.sticker.sticker
        else -> null
    }

    private fun photoPreviewSize(photo: TdApi.Photo): TdApi.PhotoSize? =
        photo.sizes.filter { maxOf(it.width, it.height) >= 640 }
            .minByOrNull { it.width * it.height }
            ?: photo.sizes.maxByOrNull { it.width * it.height }

    private fun completedPath(file: TdApi.File?): String? =
        file?.local?.path?.takeIf { file.local.isDownloadingCompleted }

    private fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun handleException(throwable: Throwable) {
        val message = (throwable as? Client.ExecutionException)?.error?.message
            ?: throwable.message
            ?: "TDLib request failed"
        val userMessage = userFacingError(message)
        Log.e("TdLib", "request failed: $message", throwable)
        mutableState.value = mutableState.value.withError(userMessage)
    }

    private fun userFacingError(message: String): String {
        val normalized = message.uppercase(Locale.ROOT)
        return when {
            normalized.contains("PHONE_NUMBER_INVALID") -> "Номер телефона недействителен"
            normalized.contains("PHONE_NUMBER_BANNED") -> "Этот номер телефона заблокирован"
            normalized.contains("PHONE_NUMBER_OCCUPIED") -> "Номер уже используется аккаунтом"
            normalized.contains("PHONE_CODE_INVALID") -> "Неверный код из Telegram"
            normalized.contains("PHONE_CODE_EXPIRED") -> "Код из Telegram истёк. Запросите новый код"
            normalized.contains("PASSWORD_HASH_INVALID") -> "Неверный пароль 2FA"
            normalized.contains("PASSWORD_EMPTY") -> "Пароль 2FA не может быть пустым"
            normalized.contains("FLOOD_WAIT") || normalized.contains("TOO_MANY_REQUESTS") ->
                "Слишком много попыток. Подождите и попробуйте снова"
            else -> message
        }
    }
}
