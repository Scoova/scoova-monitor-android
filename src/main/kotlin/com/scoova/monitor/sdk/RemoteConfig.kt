package com.scoova.monitor.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Remote configuration — SDK polls server on init to get sampling rate,
 * feature flags, and enable/disable switches. (Fix #39)
 */
@Serializable
internal data class SDKConfig(
    val samplingRate: Double = 1.0,
    val enableCrashReporting: Boolean = true,
    val enableAnalytics: Boolean = true,
    val enablePerformance: Boolean = true,
    val enableSessionReplay: Boolean = false,
    val customFlags: Map<String, String> = emptyMap()
)

internal class RemoteConfig(private val apiKey: String, private val endpoint: String) {

    var config = SDKConfig()
        private set

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ScoovaMonitor-Config").apply { isDaemon = true }
    }

    fun fetch() {
        executor.submit { fetchSync() }
        // Refresh every 30 minutes
        executor.scheduleAtFixedRate(::fetchSync, 30, 30, TimeUnit.MINUTES)
    }

    private fun fetchSync() {
        try {
            val url = URL("$endpoint/v1/sdk/config")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-API-Key", apiKey)

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = Json { ignoreUnknownKeys = true }
                val response = json.decodeFromString<ApiResponseWrapper>(body)
                response.data?.let { config = it }
                ScoovaMonitor.log("Remote config loaded: samplingRate=${config.samplingRate}")
            }
            conn.disconnect()
        } catch (_: Exception) {
            // Use defaults on failure
        }
    }

    fun shouldSample(): Boolean {
        if (config.samplingRate >= 1.0) return true
        if (config.samplingRate <= 0.0) return false
        return Math.random() < config.samplingRate
    }

    @Serializable
    private data class ApiResponseWrapper(
        val success: Boolean,
        val data: SDKConfig? = null
    )
}
