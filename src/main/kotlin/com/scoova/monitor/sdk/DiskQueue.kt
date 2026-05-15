package com.scoova.monitor.sdk

import android.content.Context
import java.io.File
import java.util.concurrent.locks.ReentrantLock

/**
 * Persistent disk-backed queue. Events survive app kills and crashes.
 * Each queue has its own file. Data is stored as newline-delimited JSON.
 */
internal class DiskQueue(context: Context, private val name: String, private val maxSize: Int = 1000) {
    private val file = File(context.filesDir, "scoova_queue_$name.jsonl")
    private val lock = ReentrantLock()

    fun append(json: String) {
        lock.lock()
        try {
            // Trim if too large
            val lines = readLines().toMutableList()
            if (lines.size >= maxSize) {
                lines.subList(0, lines.size - maxSize + 1).clear()
            }
            lines.add(json)
            file.writeText(lines.joinToString("\n"))
        } catch (_: Exception) {
        } finally {
            lock.unlock()
        }
    }

    fun appendAll(jsons: List<String>) {
        if (jsons.isEmpty()) return
        lock.lock()
        try {
            val lines = readLines().toMutableList()
            lines.addAll(jsons)
            // Trim from front if over max
            while (lines.size > maxSize) lines.removeAt(0)
            file.writeText(lines.joinToString("\n"))
        } catch (_: Exception) {
        } finally {
            lock.unlock()
        }
    }

    fun take(count: Int): List<String> {
        lock.lock()
        try {
            val lines = readLines().toMutableList()
            if (lines.isEmpty()) return emptyList()
            val batch = lines.take(count)
            val remaining = lines.drop(count)
            file.writeText(remaining.joinToString("\n"))
            return batch
        } catch (_: Exception) {
            return emptyList()
        } finally {
            lock.unlock()
        }
    }

    fun size(): Int {
        lock.lock()
        try {
            return readLines().size
        } finally {
            lock.unlock()
        }
    }

    fun clear() {
        lock.lock()
        try { file.writeText("") } catch (_: Exception) {}
        finally { lock.unlock() }
    }

    private fun readLines(): List<String> {
        return try {
            if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
        } catch (_: Exception) { emptyList() }
    }
}
