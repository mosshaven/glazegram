package com.glazegram.tdlib

import org.drinkless.tdlib.TdApi

enum class ConnectionUiState {
    READY,
    UPDATING,
    CONNECTING,
    WAITING_FOR_NETWORK,
}

fun connectionUiStateFor(state: TdApi.ConnectionState): ConnectionUiState = when (state) {
    is TdApi.ConnectionStateReady -> ConnectionUiState.READY
    is TdApi.ConnectionStateUpdating -> ConnectionUiState.UPDATING
    is TdApi.ConnectionStateWaitingForNetwork -> ConnectionUiState.WAITING_FOR_NETWORK
    is TdApi.ConnectionStateConnecting,
    is TdApi.ConnectionStateConnectingToProxy -> ConnectionUiState.CONNECTING
    else -> ConnectionUiState.CONNECTING
}
