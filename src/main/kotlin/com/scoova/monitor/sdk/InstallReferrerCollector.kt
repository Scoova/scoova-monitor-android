package com.scoova.monitor.sdk

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener

/**
 * Collects Play Store install attribution data — install source and
 * campaign — using Google's free Install Referrer API. Once collected, the
 * referrer is cached forever in SharedPreferences (it never changes for an
 * installed app), so subsequent launches don't re-query Play Services.
 *
 * Maps Play Store referrer string `utm_source=X&utm_campaign=Y` to:
 *   - install_source   = utm_source     ("google_ads", "facebook", "organic", ...)
 *   - install_campaign = utm_campaign   ("summer_2026", "spring_launch", ...)
 *
 * If the referrer is empty or the connection fails, we set source="organic"
 * so the column is non-null and analytics queries can group on it. This
 * mirrors what AppsFlyer / Adjust do when no campaign is detected.
 *
 * Implementation note: the Install Referrer API requires Play Services and
 * an active Play install. On sideloaded APKs / emulators / non-Google
 * devices, `startConnection` returns `FEATURE_NOT_SUPPORTED` and we record
 * `install_source="sideload"` so the data is at least informative.
 */
internal object InstallReferrerCollector {

    private const val PREFS = "scoova_monitor"
    private const val KEY_DONE = "install_referrer_collected"
    private const val KEY_SOURCE = "install_source"
    private const val KEY_CAMPAIGN = "install_campaign"

    /** Cached values populated on first launch; null until then. */
    @Volatile var installSource: String? = null
        private set
    @Volatile var installCampaign: String? = null
        private set

    /**
     * Trigger collection on first launch. Subsequent calls are no-ops.
     * Safe to call from Application.onCreate — the API does its work on
     * a background thread, no main-thread block.
     */
    fun collect(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Already collected on a previous launch — restore from prefs.
        if (prefs.getBoolean(KEY_DONE, false)) {
            installSource = prefs.getString(KEY_SOURCE, null)
            installCampaign = prefs.getString(KEY_CAMPAIGN, null)
            return
        }
        val client = InstallReferrerClient.newBuilder(context).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                try {
                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            val ref = client.installReferrer
                            val parsed = parseReferrer(ref.installReferrer)
                            installSource = parsed.first
                            installCampaign = parsed.second
                            prefs.edit()
                                .putString(KEY_SOURCE, installSource)
                                .putString(KEY_CAMPAIGN, installCampaign)
                                .putBoolean(KEY_DONE, true)
                                .apply()
                        }
                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                            // Sideloaded APK or non-Play device.
                            installSource = "sideload"
                            prefs.edit()
                                .putString(KEY_SOURCE, "sideload")
                                .putBoolean(KEY_DONE, true)
                                .apply()
                        }
                        else -> { /* leave for next launch retry */ }
                    }
                } catch (_: Exception) { /* best-effort */ }
                finally {
                    try { client.endConnection() } catch (_: Exception) {}
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                try { client.endConnection() } catch (_: Exception) {}
            }
        })
    }

    /**
     * Parse a Play Store referrer URL-encoded string into (source, campaign).
     *  - "utm_source=google_ads&utm_campaign=summer" → ("google_ads", "summer")
     *  - empty / no utm_source → ("organic", null)
     */
    private fun parseReferrer(raw: String?): Pair<String, String?> {
        if (raw.isNullOrBlank()) return "organic" to null
        val params = raw.split('&').associate {
            val kv = it.split('=', limit = 2)
            val k = kv.getOrNull(0)?.let { s -> java.net.URLDecoder.decode(s, "UTF-8") } ?: ""
            val v = kv.getOrNull(1)?.let { s -> java.net.URLDecoder.decode(s, "UTF-8") } ?: ""
            k to v
        }
        val source = params["utm_source"]?.takeIf { it.isNotBlank() }
            ?: params["referrer"]?.takeIf { it.isNotBlank() }
            ?: "organic"
        val campaign = params["utm_campaign"]?.takeIf { it.isNotBlank() }
        return source to campaign
    }
}
