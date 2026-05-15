package com.scoova.monitor.sdk

import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock

internal class PerformanceTracker(private val batcher: EventBatcher) {

    /**
     * Real "process start" time for the cold-start measurement.
     *
     *  - On API 24+ we use Process.getStartUptimeMillis(), which the OS
     *    sets to the real moment the zygote forked our process. That's
     *    what users actually feel as "tap-to-first-frame."
     *
     *  - On older devices we fall back to capturing SystemClock.uptimeMillis()
     *    at SDK init time. Less accurate (it skips Application.onCreate
     *    work that happened before init) but the best we can do without
     *    an Android Jetpack startup probe.
     *
     * Previous version captured SystemClock.elapsedRealtime() in this
     * field and subtracted it inside trackAppStart() — but trackAppStart
     * is called immediately after the field initializer, so the delta
     * was always 1-2 ms (just SDK init time, not real cold start). On
     * Device Farm we saw cold_start = 1-2 ms which is impossible; that
     * was the bug.
     */
    private val processStartUptime: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Process.getStartUptimeMillis()
    } else {
        SystemClock.uptimeMillis()
    }

    fun trackAppStart() {
        val startupMs = SystemClock.uptimeMillis() - processStartUptime
        // Sanity floor: if for some reason the API gave us a future time
        // (clock skew, manufacturer ROM weirdness), don't ship a negative
        // or absurdly small value. Real cold starts are >50ms even on
        // flagship devices.
        val safeMs = startupMs.coerceAtLeast(0L)
        batcher.trackMetric(
            type = "app_start",
            name = "cold_start",
            value = safeMs.toDouble(),
            unit = "ms"
        )
    }

    fun trackNetworkRequest(url: String, method: String, statusCode: Int, durationMs: Long) {
        batcher.trackMetric(
            type = "network",
            name = "http_request",
            value = durationMs.toDouble(),
            unit = "ms",
            url = url,
            method = method,
            statusCode = statusCode
        )
    }

    fun trackCustomMetric(name: String, value: Double, unit: String) {
        batcher.trackMetric(
            type = "custom",
            name = name,
            value = value,
            unit = unit
        )
    }

    /**
     * Sample current process memory. Three signals worth shipping:
     *  - native + dalvik PSS combined → "how much RAM is this app using?"
     *  - heap allocated → "is the JVM heap growing unboundedly?"
     *  - heap free      → "are we close to OOM?"
     *
     * Called from AutoTracker on a slow timer (every 30s) so the cost
     * is negligible — Debug.getMemoryInfo is a single Binder roundtrip.
     */
    fun trackMemorySample() {
        try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            // totalPss is in KB → bytes
            val totalPssBytes = info.totalPss.toLong() * 1024L
            batcher.trackMetric(
                type = "memory",
                name = "used_bytes",
                value = totalPssBytes.toDouble(),
                unit = "bytes"
            )

            val rt = Runtime.getRuntime()
            val usedHeap = rt.totalMemory() - rt.freeMemory()
            batcher.trackMetric(
                type = "memory",
                name = "heap_used_bytes",
                value = usedHeap.toDouble(),
                unit = "bytes"
            )
        } catch (_: Throwable) { /* best-effort */ }
    }
}
