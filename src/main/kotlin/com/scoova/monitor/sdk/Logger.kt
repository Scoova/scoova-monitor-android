package com.scoova.monitor.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDateTime

/**
 * Scoova Monitor Logger — Structured event and debug logging.
 *
 * Usage:
 * ```kotlin
 * val logger = ScoovaMonitor.logger("payment")
 *
 * logger.info("Payment started", mapOf("amount" to "29.99", "currency" to "USD"))
 * logger.warning("Retry payment", mapOf("attempt" to "2"))
 * logger.error("Payment failed", mapOf("error_code" to "card_declined"))
 * logger.debug("Payment flow details", mapOf("step" to "tokenize"))
 *
 * // Or use the static shorthand:
 * ScoovaMonitor.log("payment", "info", "Payment completed")
 * ```
 */
class ScoovaLogger internal constructor(private val tag: String) {

    fun debug(message: String, data: Map<String, String>? = null) = log("debug", message, data)
    fun info(message: String, data: Map<String, String>? = null) = log("info", message, data)
    fun warning(message: String, data: Map<String, String>? = null) = log("warning", message, data)
    fun error(message: String, data: Map<String, String>? = null) = log("error", message, data)

    /**
     * Log an event with custom level.
     */
    fun log(level: String, message: String, data: Map<String, String>? = null) {
        if (!ScoovaMonitor.isInitialized) return

        val payload = LogPayload(
            level = level.lowercase(),
            tag = tag,
            message = PrivacyGuard.sanitizeMessage(message),
            data = PrivacyGuard.sanitizeEventData(data),
            userId = PrivacyGuard.hashUserId(DeviceContext.userId),
            sessionId = DeviceContext.sessionId,
            timestamp = LocalDateTime.now().toString()
        )

        LogQueue.enqueue(payload)

        // Also add as breadcrumb for crash context
        ScoovaMonitor.addBreadcrumb("[$level] [$tag] $message", "log")

        // Print to logcat in debug builds
        when (level.lowercase()) {
            "debug" -> android.util.Log.d("Scoova:$tag", message)
            "info" -> android.util.Log.i("Scoova:$tag", message)
            "warning" -> android.util.Log.w("Scoova:$tag", message)
            "error" -> android.util.Log.e("Scoova:$tag", message)
        }
    }
}

@Serializable
internal data class LogPayload(
    val level: String,
    val tag: String,
    val message: String,
    val data: Map<String, String>? = null,
    val userId: String? = null,
    val sessionId: String? = null,
    val timestamp: String? = null
)

// Wrapper so we can serialize with a concrete @Serializable type rather
// than an inline mapOf("logs" to ...) — kotlinx-serialization infers the
// serializer correctly from a known data class but can be flaky with
// raw Map<String, List<...>> generics through reified inline factories,
// especially under R8 in non-debug builds.
@Serializable
internal data class BatchLogsBody(val logs: List<LogPayload>)

/**
 * Internal log queue — batches logs and sends to server.
 * Uses the same DiskQueue for persistence.
 */
internal object LogQueue {
    private lateinit var diskQueue: DiskQueue
    private var initialized = false
    private val flushHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val periodicFlush = object : Runnable {
        override fun run() {
            flush()
            flushHandler.postDelayed(this, 10_000L)
        }
    }

    fun init() {
        try {
            diskQueue = DiskQueue(ScoovaMonitor.appContext, "logs", maxSize = 2000)
            initialized = true
            // Flush every 10 seconds so a few-line log session lands in the
            // dashboard without waiting for the 50-entry batch threshold.
            // Keep the threshold for high-volume cases — they'll flush
            // sooner than the timer.
            flushHandler.postDelayed(periodicFlush, 10_000L)
        } catch (_: Exception) {}
    }

    fun enqueue(payload: LogPayload) {
        if (!initialized) return
        diskQueue.append(Json.encodeToString(payload))

        // Auto-flush at 10 logs (was 50 — too high for typical session lengths)
        if (diskQueue.size() >= 10) flush()
    }

    fun flush() {
        if (!initialized) return
        val batch = diskQueue.take(100)
        if (batch.isEmpty()) return

        Thread {
            try {
                val logs = batch.map { Json.decodeFromString<LogPayload>(it) }
                val json = Json.encodeToString(BatchLogsBody(logs))
                android.util.Log.d("ScoovaMonitor", "LogQueue flushing ${logs.size} logs")

                val url = java.net.URL("${ScoovaMonitor.endpoint}/v1/ingest/logs/batch")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-API-Key", ScoovaMonitor.apiKey)
                if (ScoovaMonitor.bundleId.isNotBlank()) {
                    conn.setRequestProperty("X-Bundle-Id", ScoovaMonitor.bundleId)
                }
                conn.doOutput = true

                // Gzip compress
                val compressed = java.io.ByteArrayOutputStream()
                java.util.zip.GZIPOutputStream(compressed).use { it.write(json.toByteArray()) }
                conn.setRequestProperty("Content-Encoding", "gzip")
                conn.outputStream.use { it.write(compressed.toByteArray()) }

                val rc = conn.responseCode
                if (rc !in 200..299) {
                    android.util.Log.w("ScoovaMonitor", "Log batch HTTP $rc — re-queueing ${batch.size}")
                    diskQueue.appendAll(batch) // Re-queue on failure
                } else {
                    android.util.Log.d("ScoovaMonitor", "Log batch HTTP $rc — sent ${batch.size} logs")
                }
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w("ScoovaMonitor", "Log batch failed: ${e.message}")
                diskQueue.appendAll(batch)
            }
        }.start()
    }
}
