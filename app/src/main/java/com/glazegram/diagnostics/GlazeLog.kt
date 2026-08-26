package com.glazegram.diagnostics

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

object GlazeLog {
    @Volatile var level: LogLevel = LogLevel.BASIC
    private val buffer = LogBuffer(500)
    private val startMs = System.currentTimeMillis()
    private const val PREFS = "glazegram_log"
    private const val KEY_LEVEL = "level"
    private const val MAX_THROWABLE_MESSAGE = 160

    fun init(context: android.content.Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_LEVEL, LogLevel.BASIC.name)
            level = try { LogLevel.valueOf(name ?: LogLevel.BASIC.name) } catch (_: Exception) { LogLevel.BASIC }
        } catch (_: Exception) {}
    }

    fun setLevelAndPersist(context: android.content.Context, newLevel: LogLevel) {
        level = newLevel
        try {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY_LEVEL, newLevel.name).apply()
        } catch (_: Exception) {}
    }

    fun d(tag: String, msg: String) = log("D", tag, msg, LogLevel.VERBOSE)
    fun i(tag: String, msg: String) = log("I", tag, msg, LogLevel.BASIC)
    fun w(tag: String, msg: String) = log("W", tag, msg, LogLevel.BASIC)

    /**
     * Errors reach Logcat exactly once, with the stack trace attached. The in-memory ring keeps a
     * bounded one-line summary of the throwable so the diagnostics screen still shows what failed.
     */
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        log("E", tag, msg + throwableSummary(tr), LogLevel.BASIC, tr)
    }

    private fun throwableSummary(tr: Throwable?): String {
        if (tr == null) return ""
        val cause = tr.message?.takeIf { it.isNotBlank() }?.let { ": " + it.take(MAX_THROWABLE_MESSAGE) }.orEmpty()
        return " | ${tr.javaClass.simpleName}$cause"
    }

    private fun log(lvl: String, tag: String, msg: String, required: LogLevel, tr: Throwable? = null) {
        if (level == LogLevel.OFF) return
        if (required == LogLevel.VERBOSE && level != LogLevel.VERBOSE) return
        val entry = LogEntry(System.currentTimeMillis() - startMs, lvl, tag, msg)
        buffer.add(entry)
        try {
            when (lvl) {
                "D" -> Log.d(tag, msg)
                "I" -> Log.i(tag, msg)
                "W" -> Log.w(tag, msg)
                "E" -> if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
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
    /** [olderCount] is the progress actually made: how many returned messages were older than the anchor. */
    fun historyOlder(chatId: Long, fromId: Long, count: Int, olderCount: Int, endReached: Boolean, latencyMs: Long) {
        d(
            "History/Older",
            "chatId=$chatId fromMessageId=$fromId count=$count olderCount=$olderCount " +
                "endReached=$endReached latencyMs=$latencyMs",
        )
    }
    fun retentionEvict(chatId: Long, retained: Int) {
        i("History/Retention", "evict chatId=$chatId retainedChats=$retained")
    }
    fun paginationSuppressed(chatId: Long, reason: String) {
        d("History/Pagination", "chatId=$chatId suppressed=$reason")
    }
    fun warmup(tag: String, msg: String) = d("Warmup/$tag", msg)
}
