package com.scoova.monitor.sdk

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.time.LocalDateTime

internal class SessionTracker(
    private val application: Application,
    private val batcher: EventBatcher
) : DefaultLifecycleObserver {

    private var sessionStartTime: Long = 0
    private var isInForeground = false

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        startNewSession()
    }

    override fun onStart(owner: LifecycleOwner) {
        // App came to foreground
        if (!isInForeground) {
            isInForeground = true
            startNewSession()
            batcher.trackEvent("session_start", mapOf(
                "session_id" to DeviceContext.sessionId
            ))
            ScoovaMonitor.log("Session started: ${DeviceContext.sessionId}")
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // App went to background
        if (isInForeground) {
            isInForeground = false
            val duration = (System.currentTimeMillis() - sessionStartTime) / 1000.0
            batcher.trackEvent("session_end", mapOf(
                "session_id" to DeviceContext.sessionId,
                "duration_seconds" to duration.toString()
            ))
            batcher.flush() // Flush immediately when going to background
            ScoovaMonitor.log("Session ended: ${DeviceContext.sessionId} (${duration}s)")
        }
    }

    private fun startNewSession() {
        DeviceContext.newSession()
        sessionStartTime = System.currentTimeMillis()
    }
}
