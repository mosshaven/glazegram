package com.glazegram.diagnostics

import org.junit.Assert.*
import org.junit.Test

class GlazeLogTest {
    @Test
    fun ringBufferBounds() {
        val buf = LogBuffer(3)
        buf.add(LogEntry(1, "I", "a", "1"))
        buf.add(LogEntry(2, "I", "a", "2"))
        buf.add(LogEntry(3, "I", "a", "3"))
        buf.add(LogEntry(4, "I", "a", "4"))
        val snap = buf.snapshot()
        assertEquals(3, snap.size)
        assertEquals("2", snap[0].message)
        assertEquals("4", snap[2].message)
    }

    @Test
    fun levelFiltering() {
        GlazeLog.level = LogLevel.OFF
        GlazeLog.clear()
        GlazeLog.d("T", "debug")
        assertTrue(GlazeLog.snapshot().isEmpty())
        GlazeLog.level = LogLevel.BASIC
        GlazeLog.i("T", "info")
        assertEquals(1, GlazeLog.snapshot().size)
        GlazeLog.clear()
        GlazeLog.level = LogLevel.VERBOSE
        GlazeLog.d("T", "debug2")
        assertEquals(1, GlazeLog.snapshot().size)
        GlazeLog.clear()
        GlazeLog.level = LogLevel.BASIC
    }

    @Test
    fun noPiiInHistoryLogs() {
        // ensure history log helpers do not accept message text
        GlazeLog.level = LogLevel.VERBOSE
        GlazeLog.clear()
        GlazeLog.historyOpen(123L, true, 2)
        GlazeLog.historyLocal(123L, 50, 10)
        GlazeLog.historyNetwork(123L, 50, 20, false)
        GlazeLog.historyOlder(123L, 999L, 20, false, 15)
        val msgs = GlazeLog.snapshot().joinToString { it.message }
        assertFalse(msgs.contains("secret"))
        assertTrue(msgs.contains("chatId=123"))
        GlazeLog.clear()
        GlazeLog.level = LogLevel.BASIC
    }
}
