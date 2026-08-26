package com.glazegram.diagnostics

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class LogEntry(
    val timeMs: Long,
    val level: String,
    val tag: String,
    val message: String,
)

class LogBuffer(private val capacity: Int = 400) {
    private val lock = ReentrantLock()
    private val ring = ArrayDeque<LogEntry>(capacity)

    fun add(entry: LogEntry) {
        lock.withLock {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(entry)
        }
    }

    fun snapshot(): List<LogEntry> = lock.withLock { ring.toList() }

    fun clear() = lock.withLock { ring.clear() }
}
