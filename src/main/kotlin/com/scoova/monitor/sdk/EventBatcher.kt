package com.scoova.monitor.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

@Serializable
internal data class EventPayload(
    val eventName: String,
    val eventData: Map<String, String>? = null,
    val userId: String? = null,
    val sessionId: String? = null,
    val sessionNumber: Int? = null,
    val device: DeviceInfoLight? = null,
    val timestamp: String? = null
)

@Serializable
internal data class DeviceInfoLight(
    val manufacturer: String? = null,
    val model: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val osApiLevel: String? = null,
    val appVersion: String? = null,
    val locale: String? = null,
    val country: String? = null,
    val carrier: String? = null,
    val networkType: String? = null,
    val networkGeneration: String? = null,
    val cpuArch: String? = null,
    val framework: String? = null,
    val sdkVersion: String? = null
)

@Serializable
internal data class BatchPayload(val events: List<EventPayload>)

@Serializable
internal data class MetricPayload(
    val metricType: String,
    val metricName: String,
    val value: Double,
    val unit: String,
    val device: DeviceInfoLight? = null,
    val sessionId: String? = null,
    val url: String? = null,
    val httpMethod: String? = null,
    val statusCode: Int? = null,
    val timestamp: String? = null,
    // Default is null (not "native"), because kotlinx-serialization
    // omits fields whose value matches the default. Setting "native"
    // here meant the JSON payload dropped the field and the server
    // stored os_name=null + framework=null. Now the value is always
    // wire-encoded.
    val framework: String? = null,
)

@Serializable
internal data class BatchMetricPayload(val metrics: List<MetricPayload>)

internal class EventBatcher(
    private val apiKey: String,
    private val endpoint: String,
    private val maxBatchSize: Int = 50,
    // 30s default — was 5 min, which meant short user sessions and
    // Device Farm runs could end with events still queued in memory.
    // Also matters for the anon→real merge: identify needs the anon
    // row to exist before it runs, so events must reach the server
    // promptly.
    flushIntervalMs: Long = 30_000
) {
    // Disk-backed queues — survive app kills
    private lateinit var eventDiskQueue: DiskQueue
    private lateinit var metricDiskQueue: DiskQueue
    private var initialized = false

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ScoovaMonitor-Batcher").apply { isDaemon = true }
    }

    // Exponential backoff state
    private var consecutiveFailures = 0
    private val maxBackoffMs = 300_000L // 5 minutes max

    init {
        try {
            eventDiskQueue = DiskQueue(ScoovaMonitor.appContext, "events")
            metricDiskQueue = DiskQueue(ScoovaMonitor.appContext, "metrics")
            initialized = true
        } catch (_: Exception) {}

        executor.scheduleAtFixedRate(::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS)
    }

    fun trackEvent(name: String, data: Map<String, String>? = null) {
        if (!initialized) return

        val lightDevice = try {
            val ctx = DeviceContext.collectLight(ScoovaMonitor.appContext)
            DeviceInfoLight(
                manufacturer = ctx["manufacturer"], model = ctx["model"],
                osName = ctx["osName"], osVersion = ctx["osVersion"],
                osApiLevel = ctx["osApiLevel"], appVersion = ctx["appVersion"],
                locale = ctx["locale"], country = ctx["country"],
                carrier = ctx["carrier"], networkType = ctx["networkType"],
                networkGeneration = ctx["networkGeneration"], cpuArch = ctx["cpuArch"],
                framework = ctx["framework"], sdkVersion = ctx["sdkVersion"]
            )
        } catch (_: Exception) { null }

        val payload = EventPayload(
            eventName = name,
            eventData = PrivacyGuard.sanitizeEventData(data),
            userId = PrivacyGuard.hashUserId(DeviceContext.userId),
            sessionId = DeviceContext.sessionId,
            sessionNumber = DeviceContext.sessionNumber.takeIf { it > 0 },
            device = lightDevice,
            timestamp = LocalDateTime.now().toString()
        )

        eventDiskQueue.append(Json.encodeToString(payload))
        if (eventDiskQueue.size() >= maxBatchSize) flush()
    }

    fun trackMetric(
        type: String, name: String, value: Double, unit: String,
        url: String? = null, method: String? = null, statusCode: Int? = null
    ) {
        if (!initialized) return

        // Same device-context shape trackEvent uses so the dashboard's
        // per-(osName, framework) breakdown can bucket metrics. Without
        // this, os_name arrived as NULL and every metric fell into the
        // "unknown" perf-page bucket.
        val lightDevice = try {
            val ctx = DeviceContext.collectLight(ScoovaMonitor.appContext)
            DeviceInfoLight(
                manufacturer = ctx["manufacturer"], model = ctx["model"],
                osName = ctx["osName"], osVersion = ctx["osVersion"],
                osApiLevel = ctx["osApiLevel"], appVersion = ctx["appVersion"],
                locale = ctx["locale"], country = ctx["country"],
                carrier = ctx["carrier"], networkType = ctx["networkType"],
                networkGeneration = ctx["networkGeneration"], cpuArch = ctx["cpuArch"],
                framework = ctx["framework"], sdkVersion = ctx["sdkVersion"]
            )
        } catch (_: Exception) { null }
        val payload = MetricPayload(
            metricType = type, metricName = name, value = value, unit = unit,
            device = lightDevice,
            sessionId = DeviceContext.sessionId,
            url = PrivacyGuard.sanitizeUrl(url),
            httpMethod = method, statusCode = statusCode,
            timestamp = LocalDateTime.now().toString(),
            framework = "native",
        )

        metricDiskQueue.append(Json.encodeToString(payload))
        if (metricDiskQueue.size() >= maxBatchSize) flush()
    }

    fun flush() {
        if (!initialized) return
        // Check backoff
        if (consecutiveFailures > 0) {
            val backoffMs = minOf((1000L * (1 shl minOf(consecutiveFailures, 8))), maxBackoffMs)
            // Skip this flush cycle if in backoff
            if (consecutiveFailures > 3) return
        }
        flushEvents()
        flushMetrics()
    }

    private fun flushEvents() {
        val rawBatch = eventDiskQueue.take(maxBatchSize)
        if (rawBatch.isEmpty()) return

        executor.submit {
            try {
                val events = rawBatch.map { Json.decodeFromString<EventPayload>(it) }
                val json = Json.encodeToString(BatchPayload(events))
                postGzipJson("$endpoint/v1/ingest/events/batch", json)
                consecutiveFailures = 0
                ScoovaMonitor.log("Flushed ${events.size} events")
            } catch (e: Exception) {
                consecutiveFailures++
                eventDiskQueue.appendAll(rawBatch) // Put back on disk
                ScoovaMonitor.log("Flush failed (attempt $consecutiveFailures): ${e.message}")
            }
        }
    }

    private fun flushMetrics() {
        val rawBatch = metricDiskQueue.take(maxBatchSize)
        if (rawBatch.isEmpty()) return

        executor.submit {
            try {
                val metrics = rawBatch.map { Json.decodeFromString<MetricPayload>(it) }
                val json = Json.encodeToString(BatchMetricPayload(metrics))
                postGzipJson("$endpoint/v1/ingest/metrics/batch", json)
                consecutiveFailures = 0
                ScoovaMonitor.log("Flushed ${metrics.size} metrics")
            } catch (e: Exception) {
                consecutiveFailures++
                metricDiskQueue.appendAll(rawBatch)
            }
        }
    }

    /** POST with gzip compression */
    private fun postGzipJson(urlStr: String, json: String) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Content-Encoding", "gzip")
        conn.setRequestProperty("X-API-Key", apiKey)
        if (ScoovaMonitor.bundleId.isNotBlank()) {
            conn.setRequestProperty("X-Bundle-Id", ScoovaMonitor.bundleId)
        }
        conn.doOutput = true

        // Gzip compress
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        conn.outputStream.use { it.write(compressed.toByteArray()) }

        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code")
    }

    /**
     * Wipe both event and metric disk queues. Called from clearLocalUserData()
     * to satisfy GDPR right-to-erasure requests. In-flight requests aren't
     * cancelled — but anything not yet sent is dropped.
     */
    fun clearAllQueues() {
        if (::eventDiskQueue.isInitialized) eventDiskQueue.clear()
        if (::metricDiskQueue.isInitialized) metricDiskQueue.clear()
    }
}
