package com.scoova.monitor.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lightweight session replay via view tree snapshots.
 * Captures UI structure (not pixels) — privacy-safe, tiny payload.
 * Snapshots are taken on screen transitions and user interactions.
 */
internal class SessionReplay(
    private val application: Application,
    private val batcher: EventBatcher
) {
    private val snapshots = CopyOnWriteArrayList<ViewSnapshot>()
    private val maxSnapshots = 100
    private val handler = Handler(Looper.getMainLooper())
    private var currentActivity: Activity? = null

    @Serializable
    data class ViewSnapshot(
        val timestamp: Long,
        val screen: String,
        val type: String, // screen_enter, tap, scroll, input
        val tree: ViewNode? = null,
        val targetId: String? = null,
        val targetType: String? = null
    )

    @Serializable
    data class ViewNode(
        val type: String, // Button, TextView, EditText, ImageView, etc.
        val id: String? = null,
        val bounds: String? = null, // "x,y,w,h"
        val text: String? = null, // "[text]" placeholder, never actual content
        val visible: Boolean = true,
        val children: List<ViewNode>? = null
    )

    fun start() {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
                captureSnapshot(activity, "screen_enter")
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity == activity) currentActivity = null
            }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    fun captureSnapshot(activity: Activity, type: String, targetView: View? = null) {
        handler.post {
            try {
                val rootView = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
                    ?: return@post

                val tree = buildViewTree(rootView, depth = 0, maxDepth = 8)
                val snapshot = ViewSnapshot(
                    timestamp = System.currentTimeMillis(),
                    screen = activity.javaClass.simpleName,
                    type = type,
                    tree = tree,
                    targetId = targetView?.let { getViewId(it) },
                    targetType = targetView?.javaClass?.simpleName
                )

                if (snapshots.size >= maxSnapshots) snapshots.removeAt(0)
                snapshots.add(snapshot)
            } catch (_: Exception) {}
        }
    }

    fun getRecentSnapshots(): List<ViewSnapshot> = snapshots.toList()

    fun flushSnapshots() {
        if (snapshots.isEmpty()) return
        val batch = snapshots.toList()
        snapshots.clear()

        batcher.trackEvent("session_replay", mapOf(
            "snapshot_count" to batch.size.toString(),
            "snapshots" to Json.encodeToString(batch).take(50_000) // limit payload
        ))
    }

    private fun buildViewTree(view: View, depth: Int, maxDepth: Int): ViewNode {
        val type = view.javaClass.simpleName
        val id = getViewId(view)
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val bounds = "${loc[0]},${loc[1]},${view.width},${view.height}"

        // Never capture actual text content — privacy
        val hasText = view is TextView && (view !is EditText)
        val textPlaceholder = if (hasText) "[text:${(view as TextView).text.length}ch]" else null

        val children = if (view is ViewGroup && depth < maxDepth) {
            (0 until view.childCount).mapNotNull { i ->
                val child = view.getChildAt(i)
                if (child.visibility == View.VISIBLE) {
                    buildViewTree(child, depth + 1, maxDepth)
                } else null
            }.takeIf { it.isNotEmpty() }
        } else null

        return ViewNode(
            type = type,
            id = id,
            bounds = bounds,
            text = textPlaceholder,
            visible = view.visibility == View.VISIBLE,
            children = children
        )
    }

    private fun getViewId(view: View): String? {
        return try {
            if (view.id != View.NO_ID) {
                view.resources.getResourceEntryName(view.id)
            } else null
        } catch (_: Exception) { null }
    }
}
