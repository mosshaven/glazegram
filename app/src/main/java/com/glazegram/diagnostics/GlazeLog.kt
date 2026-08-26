package com.glazegram.diagnostics

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

object GlazeLog {
    @Volatile var level: LogLevel = LogLevel.BASIC
    private val buffer = LogBuffer(500)
    private val startMs = System.currentTimeMillis()

    fun d(tag: String, msg: String) = log("D", tag, msg, LogLevel.VERBOSE)
    fun i(tag: String, msg: String) = log("I", tag, msg, LogLevel.BASIC)
    fun w(tag: String, msg: String) = log("W", tag, msg, LogLevel.BASIC)
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        log("E", tag, msg, LogLevel.BASIC)
        try {
            if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        } catch (_: RuntimeException) {
        }
    }

    private fun log(lvl: String, tag: String, msg: String, required: LogLevel) {
        if (level == LogLevel.OFF) return
        if (required == LogLevel.VERBOSE && level != LogLevel.VERBOSE) return
        if (required == LogLevel.BASIC && level == LogLevel.OFF) return
        val entry = LogEntry(System.currentTimeMillis() - startMs, lvl, tag, msg)
        buffer.add(entry)
        try {
            when (lvl) {
                "D" -> Log.d(tag, msg)
                "I" -> Log.i(tag, msg)
                "W" -> Log.w(tag, msg)
                "E" -> Log.e(tag, msg)
            }
        } catch (_: RuntimeException) {
            // unit tests run without Android Log mocked – ignore
        }
    }

    fun snapshot(): List<LogEntry> = buffer.snapshot()
    fun clear() = buffer.clear()

    // metrics helpers – structural, no PII
    fun historyOpen(chatId: Long, cacheHit: Boolean, retained: Int) {
        d("History/Open", "chatId=$chatId cacheHit=$cacheHit retainedChats=$retained")
    }
    fun historyLocal(chatId: Long, count: Int, latencyMs: Long) {
        d("History/Local", "chatId=$chatId count=$count latencyMs=$latencyMs")
    }
    fun historyNetwork(chatId: Long, count: Int, latencyMs: Long, endReached: Boolean) {
        d("History/Network", "chatId=$chatId count=$count latencyMs=$latencyMs endReached=$endReached")
    }
    fun historyOlder(chatId: Long, fromId: Long, count: Int, endReached: Boolean, latencyMs: Long) {
        d("History/Older", "chatId=$chatId fromMessageId=$fromId count=$count endReached=$endReached latencyMs=$latencyMs")
    }
    fun retentionEvict(chatId: Long, retained: Int) {
        i("History/Retention", "evict chatId=$chatId retainedChats=$retained")
    }
    fun paginationSuppressed(chatId: Long, reason: String) {
        d("History/Pagination", "chatId=$chatId suppressed=$reason")
    }
    fun warmup(tag: String, msg: String) = d("Warmup/$tag", msg)
}
