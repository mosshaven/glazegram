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

    // Per-chat history coordination
    enum class HistoryBoundary { UNKNOWN, CAN_LOAD, END_REACHED }
    private data class HistoryState(
        var initialReady: Boolean = false,
        var initialLoading: Boolean = false,
        var refreshing: Boolean = false,
        var olderLoading: Boolean = false,
        var boundary: HistoryBoundary = HistoryBoundary.UNKNOWN,
        var generation: Int = 0,
        var active: Boolean = false,
        var warmupCooldownUntil: Long = 0L,
    )
    private val historyStates = HashMap<Long, HistoryState>()
    private val pendingInitialMessages = HashMap<Long, MutableList<TdApi.Message>>()
    private fun historyState(chatId: Long): HistoryState = historyStates.getOrPut(chatId) { HistoryState() }
    private fun syncHistoryLoading(chatId: Long) {
        val st = historyStates[chatId]
        mutableHistoryLoading.value = mutableHistoryLoading.value + (chatId to (st?.olderLoading == true))
    }
    private fun syncHistoryHasMore(chatId: Long) {
        val st = historyStates[chatId]
        val hasMore = st == null || st.boundary != HistoryBoundary.END_REACHED
        mutableHistoryHasMore.value = mutableHistoryHasMore.value + (chatId to hasMore)
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

    // Process-local retention – bounded LRU, metadata only (no bitmap)
    private val retentionOrder = LinkedHashMap<Long, Unit>(16, 0.75f, true)
    private val MAX_RETAINED_CHATS = 8 // keep recent 8 chats
    private val MAX_MESSAGES_PER_CHAT = 150 // cap per chat to bound memory
    private val INITIAL_PAGE_SIZE = 50
    private val RETAINED_VIEWPORT_THRESHOLD = 20 // usable viewport if >=20 messages retained

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
        val st = historyState(chatId)
        st.active = true
        touchRetention(chatId)
        // Do not use messageHistory existence as readiness
        if (st.initialReady) {
            val existingSize = messageHistory[chatId]?.size ?: 0
            val cacheHit = existingSize >= RETAINED_VIEWPORT_THRESHOLD
            GlazeLog.historyOpen(chatId, cacheHit, retentionOrder.size)
            // Render retained immediately (already in StateFlow), but still refresh lightly
            if (!st.initialLoading && !st.refreshing) {
                scheduleRefresh(chatId)
            }
            return
        }
        if (st.initialLoading) {
            GlazeLog.paginationSuppressed(chatId, "openChat already initialLoading")
            return
        }
        val existingSize = messageHistory[chatId]?.size ?: 0
        val cacheHit = existingSize >= RETAINED_VIEWPORT_THRESHOLD
        GlazeLog.historyOpen(chatId, cacheHit, retentionOrder.size)
        // Cold open: bounded local-first initial page (no single-message floating)
        loadInitial(chatId)
    }

    fun refreshChats() {
        if (mutableState.value == AuthUiState.Ready) loadChats()
    }

    fun setForeground(foreground: Boolean) {
        send(TdApi.SetOption("online", TdApi.OptionValueBoolean(foreground)))
        if (foreground) publishNetworkType()
    }

    fun refreshChatHistory(chatId: Long) {
        if (mutableState.value == AuthUiState.Ready) {
            scheduleRefresh(chatId)
        }
    }

    fun closeChat(chatId: Long) {
        send(TdApi.CloseChat(chatId))
        val st = historyStates[chatId]
        if (st != null) st.active = false
        // Compact retained history to newest MAX on close (active can grow unbounded while open)
        val arr = messageHistory[chatId]
        if (arr != null && arr.size > MAX_MESSAGES_PER_CHAT) {
            val trimmed = arr.take(MAX_MESSAGES_PER_CHAT).toTypedArray()
            messageHistory[chatId] = trimmed
            publishMessages(chatId)
            GlazeLog.d("History/Retention", "compact chatId=$chatId size=${trimmed.size}")
        }
        // allow LRU to evict only inactive
    }

    fun loadOlderMessages(chatId: Long) {
        val st = historyState(chatId)
        if (st.olderLoading) {
            GlazeLog.paginationSuppressed(chatId, "older already loading")
            return
        }
        if (st.boundary == HistoryBoundary.END_REACHED) {
            GlazeLog.paginationSuppressed(chatId, "endReached")
            return
        }
        if (!st.initialReady) {
            GlazeLog.paginationSuppressed(chatId, "not initialReady")
            return
        }
        val oldestMessageId = messageHistory[chatId]?.lastOrNull()?.id ?: return
        touchRetention(chatId)
        st.olderLoading = true
        st.generation += 1
        val gen = st.generation
        syncHistoryLoading(chatId)
        val t0 = SystemClock.elapsedRealtime()
        val existingIds = messageHistory[chatId]?.map { it.id }?.toHashSet() ?: emptySet()
        send(
            TdApi.GetChatHistory(chatId, oldestMessageId, 0, INITIAL_PAGE_SIZE, false),
            result = { result ->
                if (historyState(chatId).generation != gen) {
                    GlazeLog.paginationSuppressed(chatId, "stale older gen=$gen")
                    return@send
                }
                val history = result as? TdApi.Messages ?: run {
                    st.olderLoading = false; syncHistoryLoading(chatId); return@send
                }
                val count = history.messages.size
                // progress = new older IDs not previously present
                val newOlderIds = history.messages.count { it.id !in existingIds }
                val hasProgress = newOlderIds > 0
                if (hasProgress) {
                    // merge without media? for older pagination we want media as visible, allow media
                    mergeMessages(chatId, history.messages, requestMedia = true)
                }
                val endReached = !hasProgress && count == 0 // only when no new IDs and empty, per TDLib may return <50 even with more, so don't use count==limit
                // Alternative: if count==0 -> end, else if no progress but count>0 could still be end, but we conservatively keep CAN_LOAD
                // Use official rule: end only when no progress and count==0 or all duplicates
                if (endReached) {
                    st.boundary = HistoryBoundary.END_REACHED
                } else if (hasProgress) {
                    st.boundary = HistoryBoundary.CAN_LOAD
                }
                st.olderLoading = false
                syncHistoryLoading(chatId)
                syncHistoryHasMore(chatId)
                val latency = SystemClock.elapsedRealtime() - t0
                GlazeLog.historyOlder(chatId, oldestMessageId, count, st.boundary == HistoryBoundary.END_REACHED, latency)
            },
            onFailure = { error ->
                if (historyState(chatId).generation != gen) return@send
                try { Log.e("TdLibHistory", "older chat=$chatId failed: $error") } catch (_: RuntimeException) {}
                st.olderLoading = false
                syncHistoryLoading(chatId)
                GlazeLog.paginationSuppressed(chatId, "older request failed")
            },
        )
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
                if (message.id !in finalizedTemporaryMessageIds) mergeMessages(chatId, arrayOf(message))
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
                        val history = historyResponse as? TdApi.Messages
                        if (history == null) result(false)
                        else {
                            mergeMessages(chatId, history.messages)
                            result(messageHistory[chatId].orEmpty().any { it.id == messageId })
                        }
                    },
                    onFailure = { result(false) },
                )
            },
            onFailure = { result(false) },
        )
    }

    fun downloadMessageMedia(chatId: Long, messageId: Long) {
        val message = messageHistory[chatId]?.firstOrNull { it.id == messageId } ?: return
        messageMediaFile(message.content)?.let { requestFile(it, priority = 32) }
    }

    private fun scheduleRefresh(chatId: Long) {
        val st = historyState(chatId)
        if (st.refreshing || st.initialLoading) return
        st.refreshing = true
        st.generation += 1
        val gen = st.generation
        val tNet = SystemClock.elapsedRealtime()
        send(
            TdApi.GetChatHistory(chatId, 0, 0, INITIAL_PAGE_SIZE, false),
            result = { netResult ->
                if (historyState(chatId).generation != gen) return@send
                val net = netResult as? TdApi.Messages
                val netCount = net?.messages?.size ?: 0
                if (netCount > 0 && net != null) mergeMessages(chatId, net.messages)
                st.refreshing = false
                // do not change initialReady, keep boundary as is (UNKNOWN/CAN_LOAD)
                if (st.boundary == HistoryBoundary.UNKNOWN && netCount > 0) st.boundary = HistoryBoundary.CAN_LOAD
                syncHistoryHasMore(chatId)
                val latency = SystemClock.elapsedRealtime() - tNet
                GlazeLog.historyNetwork(chatId, netCount, latency, false)
            },
            onFailure = { err ->
                if (historyState(chatId).generation != gen) return@send
                try { Log.e("TdLibHistory", "refresh chat=$chatId failed: $err") } catch (_: RuntimeException) {}
                st.refreshing = false
            },
        )
    }

    private fun loadInitial(chatId: Long) {
        val st = historyState(chatId)
        st.initialLoading = true
        st.generation += 1
        val gen = st.generation
        // ensure not marked ready yet
        val tLocal = SystemClock.elapsedRealtime()
        send(
            TdApi.GetChatHistory(chatId, 0, 0, INITIAL_PAGE_SIZE, true),
            result = { localResult ->
                if (historyState(chatId).generation != gen) return@send
                val local = localResult as? TdApi.Messages
                val localCount = local?.messages?.size ?: 0
                val localLatency = SystemClock.elapsedRealtime() - tLocal
                GlazeLog.historyLocal(chatId, localCount, localLatency)
                // Merge local viewport immediately if any, but do not yet mark ready until network coheres
                if (localCount > 0 && local != null) {
                    mergeMessages(chatId, local.messages)
                    // local page alone does not prove end; keep boundary UNKNOWN
                    st.boundary = HistoryBoundary.UNKNOWN
                    syncHistoryHasMore(chatId)
                    // If local gave us a usable viewport, we could mark initialReady early to allow rendering,
                    // but per spec we wait for first useful page – local counts as useful.
                    // We will mark ready after local to allow immediate render, then still refresh network.
                    // However to avoid floating single pending, we will mark ready here and merge pending later.
                    st.initialReady = true
                    // Also merge any pending realtime messages that arrived early
                    val pending = pendingInitialMessages.remove(chatId)
                    if (!pending.isNullOrEmpty()) {
                        mergeMessages(chatId, pending.toTypedArray())
                    }
                }
                // 2. network page to refresh/fill
                val tNet = SystemClock.elapsedRealtime()
                send(
                    TdApi.GetChatHistory(chatId, 0, 0, INITIAL_PAGE_SIZE, false),
                    result = { netResult ->
                        if (historyState(chatId).generation != gen) return@send
                        val net = netResult as? TdApi.Messages
                        val netCount = net?.messages?.size ?: 0
                        if (netCount > 0 && net != null) mergeMessages(chatId, net.messages)
                        // Merge any additional pending that arrived during network
                        val pending2 = pendingInitialMessages.remove(chatId)
                        if (!pending2.isNullOrEmpty()) mergeMessages(chatId, pending2.toTypedArray())
                        st.initialReady = true
                        st.initialLoading = false
                        // Short page with progress != endReached – keep UNKNOWN/CAN_LOAD, not END
                        // Only mark END if we truly got zero and no progress
                        if (localCount == 0 && netCount == 0) {
                            st.boundary = HistoryBoundary.END_REACHED
                        } else {
                            if (st.boundary == HistoryBoundary.UNKNOWN) st.boundary = HistoryBoundary.CAN_LOAD
                        }
                        syncHistoryHasMore(chatId)
                        val latency = SystemClock.elapsedRealtime() - tNet
                        GlazeLog.historyNetwork(chatId, netCount, latency, st.boundary == HistoryBoundary.END_REACHED)
                    },
                    onFailure = { err ->
                        if (historyState(chatId).generation != gen) return@send
                        try { Log.e("TdLibHistory", "initial network chat=$chatId failed: $err") } catch (_: RuntimeException) {}
                        // If we had local, we are already ready
                        if (localCount > 0) {
                            st.initialReady = true
                            st.initialLoading = false
                            if (st.boundary == HistoryBoundary.UNKNOWN) st.boundary = HistoryBoundary.CAN_LOAD
                            syncHistoryHasMore(chatId)
                            val pending = pendingInitialMessages.remove(chatId)
                            if (!pending.isNullOrEmpty()) mergeMessages(chatId, pending.toTypedArray())
                        } else {
                            st.initialLoading = false
                            st.boundary = HistoryBoundary.END_REACHED
                            syncHistoryHasMore(chatId)
                        }
                    },
                )
            },
            onFailure = { err ->
                if (historyState(chatId).generation != gen) return@send
                try { Log.e("TdLibHistory", "initial local chat=$chatId failed: $err") } catch (_: RuntimeException) {}
                // fallback directly to network
                val tNet = SystemClock.elapsedRealtime()
                send(
                    TdApi.GetChatHistory(chatId, 0, 0, INITIAL_PAGE_SIZE, false),
                    result = { netResult ->
                        if (historyState(chatId).generation != gen) return@send
                        val net = netResult as? TdApi.Messages
                        val netCount = net?.messages?.size ?: 0
                        if (netCount > 0 && net != null) mergeMessages(chatId, net.messages)
                        val pending = pendingInitialMessages.remove(chatId)
                        if (!pending.isNullOrEmpty()) mergeMessages(chatId, pending.toTypedArray())
                        st.initialReady = true
                        st.initialLoading = false
                        st.boundary = if (netCount == 0) HistoryBoundary.END_REACHED else HistoryBoundary.CAN_LOAD
                        syncHistoryHasMore(chatId)
                        val latency = SystemClock.elapsedRealtime() - tNet
                        GlazeLog.historyNetwork(chatId, netCount, latency, st.boundary == HistoryBoundary.END_REACHED)
                    },
                    onFailure = { e2 ->
                        if (historyState(chatId).generation != gen) return@send
                        try { Log.e("TdLibHistory", "initial network fallback chat=$chatId failed: $e2") } catch (_: RuntimeException) {}
                        st.initialLoading = false
                        st.boundary = HistoryBoundary.END_REACHED
                        syncHistoryHasMore(chatId)
                    },
                )
            },
        )
    }

    private fun touchRetention(chatId: Long) {
        retentionOrder[chatId] = Unit
        // evict only inactive retained chats, never active
        while (retentionOrder.size > MAX_RETAINED_CHATS) {
            val eldest = retentionOrder.keys.firstOrNull() ?: break
            val st = historyStates[eldest]
            if (st?.active == true) {
                // skip active, try next
                // move it to end to avoid loop
                retentionOrder.remove(eldest)
                retentionOrder[eldest] = Unit
                // if all are active, break
                if (retentionOrder.keys.all { historyStates[it]?.active == true }) break
                continue
            }
            retentionOrder.remove(eldest)
            historyStates.remove(eldest)
            pendingInitialMessages.remove(eldest)
            messageHistory.remove(eldest)
            mutableMessages.value = mutableMessages.value - eldest
            // clean loading/hasMore for evicted
            mutableHistoryLoading.value = mutableHistoryLoading.value - eldest
            mutableHistoryHasMore.value = mutableHistoryHasMore.value - eldest
            GlazeLog.retentionEvict(eldest, retentionOrder.size)
        }
    }

    private fun trimHistoryIfInactive(chatId: Long) {
        val st = historyStates[chatId]
        if (st?.active == true) return
        val arr = messageHistory[chatId] ?: return
        if (arr.size > MAX_MESSAGES_PER_CHAT) {
            val trimmed = arr.take(MAX_MESSAGES_PER_CHAT).toTypedArray()
            messageHistory[chatId] = trimmed
            publishMessages(chatId)
            GlazeLog.d("History/Retention", "compact chatId=$chatId size=${trimmed.size}")
        }
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
        synchronized(chatMap) {
            chatMap.clear()
            users.clear()
            basicGroups.clear()
            supergroups.clear()
            onlineMemberCounts.clear()
            chatActions.clear()
            messageHistory.clear()
        }
        retentionOrder.clear()
        historyStates.clear()
        pendingInitialMessages.clear()
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
        synchronized(chatMap) { chatMap[chat.id] = chat }
        requestAvatar(chat)
        requestMessageAuthor(chat.lastMessage)
        requestChatMetadata(chat)
        if (!suppressChatPublishing) publishChats()
        messageHistory.keys.forEach(::publishMessages)
    }

    private fun handleChatUpdate(update: TdApi.Update): Boolean {
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
                    messageHistory[update.chatId] = messageHistory[update.chatId]
                        ?.map { message ->
                            if (message.id == update.messageId) message.apply { containsUnreadMention = false }
                            else message
                        }
                        ?.toTypedArray()
                        ?: emptyArray()
                    publishMessages(update.chatId)
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
                    val st = historyStates[chatId]
                    // If chat has never had initial viewport, buffer realtime update but do not publish lone bubble
                    if (st != null && !st.initialReady) {
                        // Preserve for coherent viewport
                        val pending = pendingInitialMessages.getOrPut(chatId) { mutableListOf() }
                        // deduplicate by id
                        if (pending.none { it.id == update.message.id } && messageHistory[chatId]?.none { it.id == update.message.id } == true) {
                            pending.add(update.message)
                        }
                        // Also ensure chat appears in retention for quick open, but not as ready viewport
                        messageHistory.putIfAbsent(chatId, emptyArray())
                        retentionOrder[chatId] = Unit
                    } else {
                        val existing = messageHistory[chatId]
                        if (existing != null) {
                            mergeMessages(chatId, arrayOf(update.message))
                        } else {
                            // Chat not yet loaded but already ready? keep pending
                            val pending = pendingInitialMessages.getOrPut(chatId) { mutableListOf() }
                            pending.add(update.message)
                            messageHistory.putIfAbsent(chatId, emptyArray())
                        }
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
                    messageHistory[update.chatId] = messageHistory[update.chatId]
                        ?.map { message ->
                            if (message.id == update.messageId) message.apply { content = update.newContent }
                            else message
                        }
                        ?.toTypedArray()
                        ?: emptyArray()
                    publishMessages(update.chatId)
                }
                is TdApi.UpdateDeleteMessages -> {
                    val deletedIds = update.messageIds.toHashSet()
                    messageHistory[update.chatId] = messageHistory[update.chatId]
                        ?.filterNot { it.id in deletedIds }
                        ?.toTypedArray()
                        ?: emptyArray()
                    publishMessages(update.chatId)
                }
                is TdApi.UpdateChatReadOutbox -> {
                    chatMap[update.chatId]?.lastReadOutboxMessageId = update.lastReadOutboxMessageId
                    if (messageHistory.containsKey(update.chatId)) publishMessages(update.chatId)
                }
                is TdApi.UpdateChatNotificationSettings -> {
                    chatMap[update.chatId]?.notificationSettings = update.notificationSettings
                }
                is TdApi.UpdateFile -> updateAvatar(update.file)
                else -> return false
            }
        }
        if (update is TdApi.UpdateNewChat) {
            requestAvatar(update.chat)
            requestMessageAuthor(update.chat.lastMessage)
            requestChatMetadata(update.chat)
        }
        if (!suppressChatPublishing) publishChats()
        return true
    }

    // Background warmup – bounded, local only, coordinated with history state
    @Volatile private var warmupInFlight = false
    private fun maybeWarmupRecentChats() {
        if (warmupInFlight) return
        if (mutableState.value != AuthUiState.Ready) return
        val now = SystemClock.elapsedRealtime()
        val top = mutableChats.value.take(3)
        val candidates = top.filter {
            val st = historyStates[it.id]
            val notActive = st?.active != true
            val notLoading = st?.initialLoading != true && st?.refreshing != true && st?.olderLoading != true
            val need = (messageHistory[it.id]?.size ?: 0) < 10
            val cooldownOk = (st?.warmupCooldownUntil ?: 0L) < now
            notActive && notLoading && need && cooldownOk
        }
        if (candidates.isEmpty()) return
        warmupInFlight = true
        GlazeLog.warmup("start", "candidates=${candidates.map { it.id }}")
        fun warmNext(index: Int) {
            if (index >= candidates.size || index >= 2) {
                warmupInFlight = false
                GlazeLog.warmup("end", "warmed $index chats retained=${retentionOrder.size}")
                return
            }
            val chatId = candidates[index].id
            val st = historyState(chatId)
            if (st.active || st.initialLoading || st.refreshing) {
                warmNext(index + 1); return
            }
            if ((messageHistory[chatId]?.size ?: 0) >= 10) {
                warmNext(index + 1); return
            }
            // per-chat generation for warmup
            val gen = st.generation
            send(
                TdApi.GetChatHistory(chatId, 0, 0, 20, true),
                result = { res ->
                    if (historyState(chatId).generation != gen) { warmNext(index + 1); return@send }
                    val msgs = (res as? TdApi.Messages)?.messages
                    if (!msgs.isNullOrEmpty()) {
                        mergeMessagesNoMedia(chatId, msgs)
                        // Do not mark initialReady from warmup alone; keep UNKNOWN/CAN_LOAD
                        if (historyState(chatId).boundary == HistoryBoundary.UNKNOWN) {
                            historyState(chatId).boundary = HistoryBoundary.CAN_LOAD
                            syncHistoryHasMore(chatId)
                        }
                    } else {
                        // empty local result – cooldown to avoid retry on every publishChats
                        st.warmupCooldownUntil = SystemClock.elapsedRealtime() + 60_000
                    }
                    warmNext(index + 1)
                },
                onFailure = {
                    if (historyState(chatId).generation == gen) {
                        st.warmupCooldownUntil = SystemClock.elapsedRealtime() + 60_000
                    }
                    warmNext(index + 1)
                },
            )
        }
        warmNext(0)
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
        users[user.id] = user
        user.profilePhoto?.small?.let(::requestFile)
        if (!suppressChatPublishing) publishChats()
        messageHistory.keys.forEach(::publishMessages)
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

    private fun mergeMessages(chatId: Long, messages: Array<TdApi.Message>, requestMedia: Boolean = true) {
        val existing = messageHistory[chatId].orEmpty()
        val merged = LinkedHashMap<Long, TdApi.Message>()
        (existing.asList() + messages.asList()).forEach { merged[it.id] = it }
        val sorted = merged.values.sortedWith(messageComparator).toTypedArray()
        // Active chat can grow beyond retention cap; trim only on close/inactive
        messageHistory[chatId] = sorted
        retentionOrder[chatId] = Unit
        // Evict only inactive chats via touchRetention (not here to avoid double)
        touchRetention(chatId)
        messages.forEach(::requestMessageAuthor)
        messages.forEach(::requestForwardOrigin)
        if (requestMedia) messages.forEach(::requestMessageMedia)
        publishMessages(chatId)
    }
    // for warmup: history only, no media
    private fun mergeMessagesNoMedia(chatId: Long, messages: Array<TdApi.Message>) {
        mergeMessages(chatId, messages, requestMedia = false)
    }

    private fun replaceMessage(chatId: Long, oldMessageId: Long, message: TdApi.Message) {
        val existing = messageHistory[chatId].orEmpty()
        messageHistory[chatId] = (existing.filterNot { it.id == oldMessageId } + message)
            .distinctBy { it.id }
            .sortedWith(messageComparator)
            .toTypedArray()
        requestMessageAuthor(message)
        requestForwardOrigin(message)
        requestMessageMedia(message)
        publishMessages(chatId)
    }

    private val messageComparator = Comparator<TdApi.Message> { first, second ->
        val firstPending = first.sendingState is TdApi.MessageSendingStatePending
        val secondPending = second.sendingState is TdApi.MessageSendingStatePending
        when {
            firstPending != secondPending -> if (firstPending) -1 else 1
            first.date != second.date -> second.date.compareTo(first.date)
            else -> second.id.compareTo(first.id)
        }
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
