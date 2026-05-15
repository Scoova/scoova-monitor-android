package com.scoova.monitor.sdk

import android.app.Application
import android.content.Context

/**
 * Scoova Monitor SDK — Main entry point.
 *
 * Initialize in your Application.onCreate():
 * ```kotlin
 * ScoovaMonitor.init(this, "sm_your_api_key")
 * ```
 */
object ScoovaMonitor {

    internal var apiKey: String = ""
    internal var bundleId: String = ""
    internal var endpoint: String = "https://monitor.scoo-va.info"
    internal var isInitialized = false
    internal lateinit var appContext: Context

    private lateinit var crashHandler: CrashHandler
    private lateinit var eventBatcher: EventBatcher
    private lateinit var sessionTracker: SessionTracker
    internal lateinit var performanceTracker: PerformanceTracker
    private lateinit var autoTracker: AutoTracker
    private lateinit var batteryTracker: BatteryTracker

    /**
     * Initialize the SDK. Call this in Application.onCreate().
     */
    fun init(application: Application, apiKey: String, config: Config = Config()) {
        if (isInitialized) return

        this.apiKey = apiKey
        this.endpoint = config.endpoint
        this.appContext = application.applicationContext
        this.bundleId = application.packageName
        this.isInitialized = true

        // Resolve the anonymous installation ID first — every event from this point
        // onwards needs it as the user_id fallback for DAU/MAU/retention analytics.
        AnonIdStore.init(this.appContext)

        // Collect Play Store install referrer once per install — fills
        // install_source / install_campaign on user_profiles. Async, no
        // main-thread block; the values arrive a few seconds after launch.
        try { InstallReferrerCollector.collect(this.appContext) } catch (_: Exception) { /* */ }

        // Initialize components
        eventBatcher = EventBatcher(apiKey, endpoint)
        crashHandler = CrashHandler(apiKey, endpoint)
        sessionTracker = SessionTracker(application, eventBatcher)
        performanceTracker = PerformanceTracker(eventBatcher)

        // Install crash handler
        crashHandler.install()

        // Start session tracking
        sessionTracker.start()

        // Auto tracking (screens, breadcrumbs, frames, memory)
        autoTracker = AutoTracker(application, eventBatcher, crashHandler)
        autoTracker.start()

        // Track app start performance
        performanceTracker.trackAppStart()

        // Battery tracking
        batteryTracker = BatteryTracker(application, eventBatcher)
        batteryTracker.start()

        // Initialize log queue
        LogQueue.init()

        // Send any pending crashes from previous session
        sendPendingCrashes()

        // Increment session count and detect first launch
        DeviceContext.incrementSession(application)

        // Detect and report third-party SDKs on first launch — only if the
        // host explicitly opted in via Config.enableSDKDetection. Off by default.
        if (DeviceContext.isFirstLaunch(application)) {
            if (config.enableSDKDetection) reportDetectedSDKs()
            trackEvent("first_launch", mapOf(
                "install_time" to DeviceContext.getInstallDate(application).toString()
            ))
        }

        log("Scoova Monitor initialized for project")
    }

    /**
     * Track a custom analytics event.
     */
    fun trackEvent(name: String, data: Map<String, String>? = null) {
        ensureInitialized()
        eventBatcher.trackEvent(name, data)
    }

    /**
     * Set the user ID for crash and analytics attribution.
     *
     * Side-effect: when a previously-anonymous install transitions to a
     * known user, fire one /v1/ingest/identify so the server merges the
     * anon profile into the real one. Without this, the same human
     * shows up as two rows in user_profiles (anon + real) and gets
     * counted twice in DAU/MAU/cohort retention. Best-effort and
     * idempotent — repeated setUserId calls with the same id no-op,
     * and a network failure just leaves the merge for the next call.
     */
    fun setUserId(userId: String) {
        ensureInitialized()
        val previousUserId = DeviceContext.userId
        DeviceContext.userId = userId
        if (userId.isBlank()) return
        val anon = AnonIdStore.get() ?: return
        // hashUserId returns "h_<sha256>" — exactly what flows in events
        // for an authenticated user, so it's what the alias must point at.
        val hashed = PrivacyGuard.hashUserId(userId) ?: return
        if (lastIdentifiedAs == hashed) return
        if (previousUserId == userId && identifySent) return
        Thread {
            try {
                // Force-flush queued events + metrics + logs *before* firing
                // identify. Otherwise the anon row may not yet exist on
                // the server when the merge runs, leaving an orphaned
                // anon profile that double-counts in MAU.
                try {
                    eventBatcher.flush()
                    LogQueue.flush()
                } catch (_: Throwable) { /* best-effort */ }
                // Tiny pause to let the IngestBuffer drain — flushes are
                // queued onto worker threads server-side.
                Thread.sleep(500)
                val ok = postIdentify(anon, hashed)
                if (ok) {
                    identifySent = true
                    lastIdentifiedAs = hashed
                }
            } catch (_: Exception) { /* best-effort */ }
        }.apply { isDaemon = true }.start()
    }

    @Volatile private var identifySent = false
    @Volatile private var lastIdentifiedAs: String? = null

    private fun postIdentify(anonId: String, hashedUserId: String): Boolean {
        val url = java.net.URL("$endpoint/v1/ingest/identify")
        val conn = url.openConnection() as java.net.HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-API-Key", apiKey)
            conn.setRequestProperty("X-Bundle-Id", bundleId)
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            val body = "{\"anonId\":\"$anonId\",\"userId\":\"$hashedUserId\"}"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Track a network request performance metric.
     */
    fun trackNetworkRequest(
        url: String,
        method: String,
        statusCode: Int,
        durationMs: Long
    ) {
        ensureInitialized()
        performanceTracker.trackNetworkRequest(url, method, statusCode, durationMs)
    }

    /**
     * Log a non-fatal exception.
     */
    fun logException(throwable: Throwable) {
        ensureInitialized()
        crashHandler.reportNonFatal(throwable)
    }

    /**
     * Add a breadcrumb for crash context.
     */
    fun addBreadcrumb(message: String, category: String = "custom") {
        ensureInitialized()
        crashHandler.addBreadcrumb(message, category)
    }

    /**
     * Track a screen view for user flow analysis.
     */
    fun trackScreen(screenName: String) {
        ensureInitialized()
        eventBatcher.trackEvent("screen_view", mapOf("screen_name" to screenName))
    }

    /**
     * Set the install source for acquisition tracking. Manually-supplied
     * values override anything the Play Install Referrer API auto-detected.
     */
    fun setInstallSource(source: String, campaign: String? = null) {
        ensureInitialized()
        eventBatcher.trackEvent("install_info", mapOf(
            "install_source" to source,
            "install_campaign" to (campaign ?: ""),
            "session_number" to DeviceContext.sessionNumber.toString()
        ))
    }

    /** Auto-emit the install referrer the next time we have a network window.  */
    internal fun maybeEmitAutoInstallReferrer() {
        val src = InstallReferrerCollector.installSource ?: return
        eventBatcher.trackEvent("install_info", mapOf(
            "install_source" to src,
            "install_campaign" to (InstallReferrerCollector.installCampaign ?: ""),
            "session_number" to DeviceContext.sessionNumber.toString(),
            "auto_collected" to "true"
        ))
    }

    /**
     * Get a logger for a specific tag/module.
     * ```kotlin
     * val logger = ScoovaMonitor.logger("payment")
     * logger.info("Payment started", mapOf("amount" to "29.99"))
     * ```
     */
    fun logger(tag: String): ScoovaLogger {
        ensureInitialized()
        return ScoovaLogger(tag)
    }

    /**
     * Quick log — shorthand without creating a logger instance.
     * ```kotlin
     * ScoovaMonitor.log("auth", "info", "User logged in")
     * ```
     */
    fun log(tag: String, level: String, message: String, data: Map<String, String>? = null) {
        ensureInitialized()
        ScoovaLogger(tag).log(level, message, data)
    }

    /**
     * Flush any pending events, metrics, and logs immediately.
     */
    fun flush() {
        ensureInitialized()
        eventBatcher.flush()
        LogQueue.flush()
    }

    /**
     * Wipe every piece of telemetry the SDK has buffered or persisted on this
     * device. Call this when the host app's user invokes "delete my account" —
     * pairs with the server-side `DELETE /v1/ingest/me/{userId}` to satisfy
     * GDPR Article 17 / CCPA "right to be forgotten" end-to-end.
     *
     * What this clears:
     *   - the in-memory + on-disk event / metric / log queues
     *   - the pending crash file (if any)
     *   - breadcrumbs accumulated this session
     *   - the anonymous installation ID (a fresh one is generated on the next event)
     *   - the persisted session counter
     *   - the user_id set via setUserId()
     *
     * Does NOT contact the server. The host app should also call your server's
     * GDPR delete endpoint with the user_id you previously sent.
     */
    fun clearLocalUserData() {
        ensureInitialized()
        // Best-effort wipe — never throw and block the host's delete flow.
        try { eventBatcher.clearAllQueues() } catch (_: Exception) {}
        try { crashHandler.clearPendingCrashes() } catch (_: Exception) {}
        try { crashHandler.clearBreadcrumbs() } catch (_: Exception) {}
        try { AnonIdStore.reset(appContext) } catch (_: Exception) {}
        DeviceContext.userId = null
        try { DeviceContext.resetSessionCounter(appContext) } catch (_: Exception) {}
        log("Local user data cleared")
    }

    private fun reportDetectedSDKs() {
        val sdks = DeviceContext.detectThirdPartySDKs()
        if (sdks.isNotEmpty()) {
            eventBatcher.trackEvent("detected_sdks", sdks.associate { it.first to (it.second ?: "unknown") })
        }
    }

    private fun sendPendingCrashes() {
        Thread {
            try {
                val file = java.io.File(appContext.filesDir, "scoova_pending_crash.json")
                if (file.exists()) {
                    val json = file.readText()
                    val url = java.net.URL("$endpoint/v1/ingest/crashes")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("X-API-Key", apiKey)
                    if (bundleId.isNotBlank()) conn.setRequestProperty("X-Bundle-Id", bundleId)
                    conn.doOutput = true
                    conn.outputStream.use { it.write(json.toByteArray()) }
                    if (conn.responseCode in 200..299) {
                        file.delete()
                        log("Sent pending crash from previous session")
                    }
                    conn.disconnect()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun ensureInitialized() {
        if (!isInitialized) throw IllegalStateException(
            "ScoovaMonitor not initialized. Call ScoovaMonitor.init(application, apiKey) first."
        )
    }

    internal fun log(message: String) {
        android.util.Log.d("ScoovaMonitor", message)
    }

    data class Config(
        val endpoint: String = "https://monitor.scoo-va.info",
        val enableCrashReporting: Boolean = true,
        val enableAnalytics: Boolean = true,
        val enablePerformance: Boolean = true,
        val flushIntervalMs: Long = 300_000, // 5 minutes — radio-friendly default; flush is also triggered by batch size, lifecycle (background), and crashes (which use a separate immediate path)
        val maxBatchSize: Int = 50,
        /**
         * Probe the bundle for third-party SDK presence (Firebase, Sentry,
         * Mixpanel, etc) and report once-per-install. **Disabled by default**
         * — enable explicitly only if you want the "Detected SDKs" dashboard.
         */
        val enableSDKDetection: Boolean = false
    )
}
