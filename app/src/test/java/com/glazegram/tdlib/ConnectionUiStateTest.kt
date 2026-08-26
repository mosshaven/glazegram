package com.glazegram.tdlib

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionUiStateTest {
    @Test
    fun mapsTdlibConnectionStates() {
        assertEquals(ConnectionUiState.READY, connectionUiStateFor(TdApi.ConnectionStateReady()))
        assertEquals(ConnectionUiState.UPDATING, connectionUiStateFor(TdApi.ConnectionStateUpdating()))
        assertEquals(ConnectionUiState.CONNECTING, connectionUiStateFor(TdApi.ConnectionStateConnecting()))
        assertEquals(ConnectionUiState.CONNECTING, connectionUiStateFor(TdApi.ConnectionStateConnectingToProxy()))
        assertEquals(
            ConnectionUiState.WAITING_FOR_NETWORK,
            connectionUiStateFor(TdApi.ConnectionStateWaitingForNetwork()),
        )
    }
}
