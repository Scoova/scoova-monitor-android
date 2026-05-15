package com.scoova.monitor.sdk

import android.content.Context
import java.util.UUID

/**
 * Persistent anonymous installation ID — generated once per app install,
 * stored in SharedPreferences, and used as the user_id fallback when the host
 * app hasn't called setUserId(). Lets DAU/MAU/retention/sessions still count
 * something distinct per device for apps that don't have a login system.
 *
 * The ID is opaque — we never derive it from advertising IDs or device
 * fingerprints, so it doesn't survive app uninstall and isn't shared across
 * apps from the same vendor.
 */
internal object AnonIdStore {

    private const val PREFS = "scoova_monitor"
    private const val KEY = "anon_id"
    private const val PREFIX = "anon_"

    @Volatile private var cached: String? = null

    /**
     * Resolve and cache the anon ID. Call once during init() — subsequent
     * get() calls are lock-free and return the cached value.
     */
    fun init(context: Context) {
        if (cached != null) return
        synchronized(this) {
            if (cached != null) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY, null)
            cached = if (existing != null && existing.startsWith(PREFIX)) {
                existing
            } else {
                val fresh = PREFIX + UUID.randomUUID().toString()
                prefs.edit().putString(KEY, fresh).apply()
                fresh
            }
        }
    }

    /** Returns the cached anon ID, or null if init() hasn't run yet. */
    fun get(): String? = cached

    /**
     * Reset — wipe the persisted ID and the in-memory cache. Used by
     * clearLocalUserData(). The next event will lazily re-initialize a fresh
     * anon ID, exactly as if the user had reinstalled the app.
     */
    fun reset(context: Context) {
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY).apply()
            cached = null
        }
    }
}
