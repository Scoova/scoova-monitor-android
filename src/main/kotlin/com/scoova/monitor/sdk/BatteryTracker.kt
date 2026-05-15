package com.scoova.monitor.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Battery health tracker — monitors drain rate, temperature, and charging state.
 *
 * Sends metrics:
 * - battery/drain_rate (%/hour) — calculated from level delta over time
 * - battery/level (%) — current battery level
 * - battery/temperature (°C) — device temperature
 *
 * Also tracks:
 * - Sessions where drain > 5%/hour (high drain)
 * - Thermal throttle events (temp > 40°C)
 */
internal class BatteryTracker(
    private val context: Context,
    private val batcher: EventBatcher
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ScoovaMonitor-Battery").apply { isDaemon = true }
    }

    private var lastLevel: Float? = null
    private var lastSampleTime: Long = 0
    private var sessionStartLevel: Float? = null
    private var sessionStartTime: Long = 0

    fun start() {
        // Initial reading
        sampleBattery()
        sessionStartLevel = lastLevel
        sessionStartTime = System.currentTimeMillis()

        // Sample every 5 minutes
        scheduler.scheduleAtFixedRate({
            try { sampleBattery() } catch (_: Exception) {}
        }, 5, 5, TimeUnit.MINUTES)

        // Listen for significant battery events
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            context.registerReceiver(batteryReceiver, filter)
        } catch (_: Exception) {}

        ScoovaMonitor.log("BatteryTracker started")
    }

    private fun sampleBattery() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).toFloat()
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).toFloat()
        val percentage = (level / scale) * 100f
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f // tenths of °C
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = plugged != 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)

        val now = System.currentTimeMillis()

        // Report battery level
        batcher.trackMetric("battery", "level", percentage.toDouble(), "percent")

        // Report temperature
        batcher.trackMetric("battery", "temperature", temperature.toDouble(), "celsius")

        // Calculate drain rate (only when not charging)
        if (!isCharging && lastLevel != null && lastSampleTime > 0) {
            val levelDelta = lastLevel!! - percentage
            val timeDeltaHours = (now - lastSampleTime) / 3600000.0
            if (timeDeltaHours > 0.01 && levelDelta > 0) { // At least ~36 seconds and draining
                val drainPerHour = levelDelta / timeDeltaHours
                if (drainPerHour in 0.0..100.0) { // Sanity check
                    batcher.trackMetric("battery", "drain_rate", drainPerHour.toDouble(), "percent_per_hour")
                }
            }
        }

        // Thermal warning
        if (temperature > 40f) {
            batcher.trackEvent("thermal_warning", mapOf(
                "temperature" to "%.1f".format(temperature),
                "is_charging" to isCharging.toString(),
                "battery_level" to "%.0f".format(percentage)
            ))
        }

        lastLevel = percentage
        lastSampleTime = now
    }

    // Report session battery summary when app goes to background or is destroyed
    fun reportSessionSummary() {
        val startLevel = sessionStartLevel ?: return
        val currentLevel = lastLevel ?: return
        val startTime = sessionStartTime
        if (startTime <= 0) return

        val durationHours = (System.currentTimeMillis() - startTime) / 3600000.0
        if (durationHours < 0.01) return // Too short

        val drain = startLevel - currentLevel
        val drainPerHour = if (drain > 0 && durationHours > 0) drain / durationHours else 0.0

        batcher.trackEvent("battery_session_summary", mapOf(
            "start_level" to "%.0f".format(startLevel),
            "end_level" to "%.0f".format(currentLevel),
            "drain_percent" to "%.1f".format(drain),
            "drain_per_hour" to "%.2f".format(drainPerHour),
            "duration_minutes" to "%.0f".format(durationHours * 60)
        ))
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_LOW -> {
                    batcher.trackEvent("battery_low", mapOf(
                        "level" to (lastLevel?.toString() ?: "unknown")
                    ))
                    ScoovaMonitor.addBreadcrumb("Battery low: ${lastLevel?.toInt()}%", "system")
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    ScoovaMonitor.addBreadcrumb("Charger connected at ${lastLevel?.toInt()}%", "system")
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    // Reset drain tracking from this point
                    sampleBattery()
                    ScoovaMonitor.addBreadcrumb("Charger disconnected at ${lastLevel?.toInt()}%", "system")
                }
            }
        }
    }
}
