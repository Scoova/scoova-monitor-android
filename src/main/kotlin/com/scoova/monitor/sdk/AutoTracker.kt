package com.scoova.monitor.sdk

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AbsListView
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Switch
import android.widget.ToggleButton

/**
 * Automatic tracking — screens, taps, gestures, memory, frame rate, app lifecycle.
 *
 * PRIVACY RULES:
 * - Track UI element IDs and types, NEVER text content
 * - Track that user tapped a button, not what the button says
 * - Track screen names (class names), not screen content
 * - Track network URL paths, strip query params
 * - Track that keyboard appeared, not what was typed
 * - All user IDs are pre-hashed by PrivacyGuard
 */
internal class AutoTracker(
    private val application: Application,
    private val batcher: EventBatcher,
    private val crashHandler: CrashHandler
) {
    private var previousScreen: String? = null
    private var screenStartTime: Long = 0
    private var frameCallback: Choreographer.FrameCallback? = null
    private var lastFrameTimeNanos: Long = 0
    private var droppedFrames = 0
    private var totalFrames = 0
    private var currentActivity: Activity? = null

    fun start() {
        trackActivityLifecycle()
        trackMemoryWarnings()
        trackFrameRate()
        startMemorySampling()
        ScoovaMonitor.log("AutoTracker started (privacy-safe)")
    }

    private val memoryHandler = Handler(Looper.getMainLooper())
    private val memorySampler = object : Runnable {
        override fun run() {
            // Snapshot used PSS + heap on a slow timer so the perf
            // dashboard's memory chart has actual data without noticeably
            // burning battery. 30s cadence matches what we found is the
            // smallest window where the OS bookkeeping actually changes.
            try { ScoovaMonitor.performanceTracker.trackMemorySample() } catch (_: Throwable) {}
            memoryHandler.postDelayed(this, 30_000L)
        }
    }
    private fun startMemorySampling() {
        // Fire one immediate sample so we don't have to wait 30s for the
        // first data point to appear in the dashboard, then start the
        // periodic timer.
        memoryHandler.post(memorySampler)
    }

    // ─── Screen Tracking + Auto Tap Detection ───

    private fun trackActivityLifecycle() {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val screenName = activity.javaClass.simpleName
                val now = System.currentTimeMillis()

                // Track duration on previous screen
                if (previousScreen != null && screenStartTime > 0) {
                    val duration = (now - screenStartTime) / 1000.0
                    batcher.trackEvent("screen_view", mapOf(
                        "screen_name" to previousScreen!!,
                        "duration_seconds" to "%.1f".format(duration),
                        "next_screen" to screenName
                    ))
                }

                previousScreen = screenName
                screenStartTime = now
                currentActivity = activity

                crashHandler.addBreadcrumb("Screen: $screenName", "navigation")

                // Install tap tracking on this activity's view hierarchy
                activity.window?.let { installTapTracking(it, screenName) }
            }

            override fun onActivityPaused(activity: Activity) {
                crashHandler.addBreadcrumb("Paused: ${activity.javaClass.simpleName}", "navigation")
                if (currentActivity == activity) currentActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                crashHandler.addBreadcrumb("Created: ${activity.javaClass.simpleName}", "lifecycle")
                batcher.trackEvent("app_lifecycle", mapOf(
                    "action" to "activity_created",
                    "screen" to activity.javaClass.simpleName
                ))
            }

            override fun onActivityDestroyed(activity: Activity) {
                crashHandler.addBreadcrumb("Destroyed: ${activity.javaClass.simpleName}", "lifecycle")
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                // Track app going to background
                batcher.trackEvent("app_lifecycle", mapOf("action" to "activity_stopped"))
                // Drain queued events. Disk queue keeps anything that fails so a
                // process kill is safe; flushing here keeps the dashboard fresh
                // under longer flush intervals.
                batcher.flush()
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    // ─── Privacy-Safe Tap Tracking ───
    // Tracks WHAT was tapped (button ID, type), NOT the text content

    private fun installTapTracking(window: Window, screenName: String) {
        val decorView = window.decorView
        val callback = window.callback
        window.callback = object : Window.Callback by callback {
            override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean {
                if (event?.action == android.view.MotionEvent.ACTION_UP) {
                    // Find which view was tapped
                    val x = event.x.toInt()
                    val y = event.y.toInt()
                    val tappedView = findViewAt(decorView as? ViewGroup, x, y)
                    if (tappedView != null) {
                        trackTap(tappedView, screenName)
                    }
                }
                return callback.dispatchTouchEvent(event)
            }
        }
    }

    private fun trackTap(view: View, screenName: String) {
        // Get safe identifier — resource ID name, not text
        val viewId = try {
            if (view.id != View.NO_ID) {
                view.resources.getResourceEntryName(view.id)
            } else null
        } catch (_: Exception) { null }

        val viewType = when (view) {
            is Button -> "button"
            is ImageButton -> "image_button"
            is Switch, is ToggleButton -> "toggle"
            is ImageView -> "image"
            is AbsListView -> "list_item"
            else -> view.javaClass.simpleName.lowercase()
        }

        // Only track interactive elements (not every touch on the screen)
        if (view.isClickable || view is Button || view is ImageButton) {
            val data = mutableMapOf(
                "view_type" to viewType,
                "screen" to screenName
            )
            if (viewId != null) data["view_id"] = viewId
            // NEVER include text content — just the ID and type

            batcher.trackEvent("user_tap", data)
            crashHandler.addBreadcrumb("Tap: ${viewType}${if (viewId != null) "#$viewId" else ""} on $screenName", "ui")
        }
    }

    private fun findViewAt(parent: ViewGroup?, x: Int, y: Int): View? {
        if (parent == null) return null
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue

            val location = IntArray(2)
            child.getLocationOnScreen(location)
            val left = location[0]
            val top = location[1]
            val right = left + child.width
            val bottom = top + child.height

            if (x in left..right && y in top..bottom) {
                // Recurse into ViewGroups to find the most specific view
                if (child is ViewGroup) {
                    val deeper = findViewAt(child, x, y)
                    if (deeper != null && (deeper.isClickable || deeper is Button)) return deeper
                }
                if (child.isClickable || child is Button || child is ImageButton) return child
            }
        }
        return parent
    }

    // ─── Memory Warning Tracking ───

    private fun trackMemoryWarnings() {
        application.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                val levelName = when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "running_low"
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "running_critical"
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "complete"
                    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "moderate"
                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "background"
                    else -> "level_$level"
                }
                batcher.trackEvent("memory_warning", mapOf(
                    "level" to levelName,
                    "free_memory" to Runtime.getRuntime().freeMemory().toString()
                ))
                crashHandler.addBreadcrumb("Memory trim: $levelName", "system")
            }

            override fun onConfigurationChanged(newConfig: Configuration) {
                // Track orientation changes
                val orientation = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
                batcher.trackEvent("config_change", mapOf("orientation" to orientation))
                crashHandler.addBreadcrumb("Orientation: $orientation", "system")
            }

            @Deprecated("Deprecated") override fun onLowMemory() {
                batcher.trackEvent("memory_warning", mapOf("level" to "low_memory"))
                crashHandler.addBreadcrumb("Low memory warning", "system")
            }
        })
    }

    // ─── Frame Rate Tracking ───

    private fun trackFrameRate() {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
                if (lastFrameTimeNanos > 0) {
                    val frameDurationMs = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000.0
                    totalFrames++

                    if (frameDurationMs > 32) { // > 2 frames dropped (> 32ms at 60fps)
                        droppedFrames++
                    }

                    if (frameDurationMs > 700) { // Frozen frame (> 700ms)
                        batcher.trackEvent("frozen_frame", mapOf(
                            "duration_ms" to "%.0f".format(frameDurationMs),
                            "screen" to (previousScreen ?: "unknown")
                        ))
                        crashHandler.addBreadcrumb("Frozen frame: ${"%.0f".format(frameDurationMs)}ms on ${previousScreen ?: "?"}", "performance")
                    }

                    // Report frame stats every 60 seconds
                    if (totalFrames > 0 && totalFrames % 3600 == 0) {
                        val fps = 1_000_000_000.0 / ((frameTimeNanos - lastFrameTimeNanos).toDouble() / totalFrames)
                        batcher.trackMetric("frame_rate", "fps", fps.coerceIn(0.0, 120.0), "fps")
                        batcher.trackMetric("frame_rate", "dropped_frames_percent",
                            (droppedFrames.toDouble() / totalFrames * 100), "percent")
                        droppedFrames = 0
                        totalFrames = 0
                    }
                }
                lastFrameTimeNanos = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(frameCallback!!)
            }
            Choreographer.getInstance().postFrameCallback(frameCallback!!)
        }
    }
}
