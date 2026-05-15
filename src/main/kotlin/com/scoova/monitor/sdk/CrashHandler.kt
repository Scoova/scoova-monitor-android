package com.scoova.monitor.sdk

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

private val crashJson = Json { encodeDefaults = true }

@Serializable
internal data class CrashPayload(
    val exceptionType: String,
    val message: String,
    val stackTrace: String,
    val isFatal: Boolean = true,
    val device: DeviceInfo? = null,
    val userId: String? = null,
    val sessionId: String? = null,
    val timestamp: String? = null,
    // Enhanced forensic data
    val allThreads: String? = null,
    val appState: Map<String, String>? = null,
    val memoryState: Map<String, String>? = null,
    val recentLogs: List<String>? = null,
    val recentNetworkRequests: List<String>? = null,
    val crashContext: Map<String, String>? = null
)

@Serializable
internal data class Breadcrumb(
    val message: String,
    val category: String,
    val timestamp: String
)

internal class CrashHandler(
    private val apiKey: String,
    private val endpoint: String
) {
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private val breadcrumbs = CopyOnWriteArrayList<Breadcrumb>()
    private val recentLogs = CopyOnWriteArrayList<String>()
    private val recentNetworkRequests = CopyOnWriteArrayList<String>()
    private val maxBreadcrumbs = 100
    private val maxRecentItems = 50

    fun install() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(thread, throwable, isFatal = true)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        startANRDetector()
        ScoovaMonitor.log("Crash handler installed")
    }

    fun reportNonFatal(throwable: Throwable) {
        Thread {
            try {
                val report = buildFullCrashReport(Thread.currentThread(), throwable, isFatal = false)
                sendCrashReport(report)
            } catch (_: Exception) { }
        }.start()
    }

    fun addBreadcrumb(message: String, category: String) {
        if (breadcrumbs.size >= maxBreadcrumbs) breadcrumbs.removeAt(0)
        breadcrumbs.add(Breadcrumb(message, category, LocalDateTime.now().toString()))
    }

    fun addRecentLog(log: String) {
        if (recentLogs.size >= maxRecentItems) recentLogs.removeAt(0)
        recentLogs.add(log)
    }

    fun addRecentNetworkRequest(request: String) {
        if (recentNetworkRequests.size >= maxRecentItems) recentNetworkRequests.removeAt(0)
        recentNetworkRequests.add(request)
    }

    /** Delete the persisted pending crash file (if any). Used by clearLocalUserData(). */
    fun clearPendingCrashes() {
        try {
            val file = java.io.File(ScoovaMonitor.appContext.filesDir, "scoova_pending_crash.json")
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    /** Drop in-memory breadcrumbs + recent log/network buffers. Used by clearLocalUserData(). */
    fun clearBreadcrumbs() {
        breadcrumbs.clear()
        recentLogs.clear()
        recentNetworkRequests.clear()
    }

    // ─── Core Crash Handling ───

    private fun handleCrash(thread: Thread, throwable: Throwable, isFatal: Boolean) {
        // Build and save full report to disk FIRST (survives process death)
        val report = try {
            buildFullCrashReport(thread, throwable, isFatal)
        } catch (_: Exception) { null }

        val file = java.io.File(ScoovaMonitor.appContext.filesDir, "scoova_pending_crash.json")
        if (report != null) {
            try { file.writeText(crashJson.encodeToString(report)) } catch (_: Exception) {}
        } else {
            saveCrashToDisk(throwable, isFatal)
        }

        // Then try to send immediately (best-effort)
        if (report != null) {
            try { sendCrashReport(report) } catch (_: Exception) {}
        }
    }

    private fun buildFullCrashReport(crashThread: Thread, throwable: Throwable, isFatal: Boolean): CrashPayload {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val primaryStackTrace = sw.toString()

        // ── Chained exceptions (full cause chain) ──
        val causeChain = buildCauseChain(throwable)

        // ── All thread dumps ──
        val allThreadDump = captureAllThreads(crashThread)

        // ── App state ──
        val appState = captureAppState()

        // ── Memory forensics ──
        val memoryState = captureMemoryState()

        // ── Breadcrumbs ──
        val breadcrumbStr = if (breadcrumbs.isNotEmpty()) {
            "\n\n--- Breadcrumbs (last ${breadcrumbs.size}) ---\n" +
            breadcrumbs.takeLast(30).joinToString("\n") {
                "[${it.timestamp}] [${it.category}] ${it.message}"
            }
        } else ""

        // ── Crash context (what was happening) ──
        val crashContext = mutableMapOf<String, String>()
        crashContext["crashThread"] = "${crashThread.name} (id=${crashThread.id}, priority=${crashThread.priority})"
        crashContext["threadState"] = crashThread.state.name
        crashContext["threadGroup"] = crashThread.threadGroup?.name ?: "unknown"
        crashContext["activeThreadCount"] = Thread.activeCount().toString()
        crashContext["exceptionChainDepth"] = countCauseDepth(throwable).toString()

        // Detect common crash patterns
        crashContext["crashPattern"] = detectCrashPattern(throwable, primaryStackTrace)

        val device = try { DeviceContext.collect(ScoovaMonitor.appContext) } catch (_: Exception) { null }

        return CrashPayload(
            exceptionType = throwable.javaClass.name,
            message = PrivacyGuard.sanitizeMessage(throwable.message ?: "No message"),
            stackTrace = PrivacyGuard.sanitizeStackTrace(
                primaryStackTrace + causeChain + breadcrumbStr
            ),
            isFatal = isFatal,
            device = device,
            userId = PrivacyGuard.hashUserId(DeviceContext.userId),
            sessionId = DeviceContext.sessionId,
            timestamp = LocalDateTime.now().toString(),
            allThreads = allThreadDump,
            appState = appState,
            memoryState = memoryState,
            recentLogs = recentLogs.takeLast(20).toList(),
            recentNetworkRequests = recentNetworkRequests.takeLast(10).toList(),
            crashContext = crashContext
        )
    }

    // ─── Thread Dump: ALL threads at crash time ───

    private fun captureAllThreads(crashThread: Thread): String {
        return try {
            val sb = StringBuilder()
            sb.appendLine("=== ALL THREADS AT CRASH TIME ===")
            sb.appendLine("Total threads: ${Thread.activeCount()}")
            sb.appendLine()

            val allStacks = Thread.getAllStackTraces()
            for ((thread, stack) in allStacks) {
                val marker = if (thread.id == crashThread.id) " *** CRASHED ***" else ""
                val daemon = if (thread.isDaemon) " [daemon]" else ""
                sb.appendLine("--- Thread: \"${thread.name}\" (id=${thread.id}) state=${thread.state}$daemon$marker ---")
                if (stack.isEmpty()) {
                    sb.appendLine("    (no stack trace available)")
                } else {
                    for (frame in stack) {
                        sb.appendLine("    at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
                    }
                }
                sb.appendLine()
            }
            PrivacyGuard.sanitizeStackTrace(sb.toString())
        } catch (_: Exception) { null } ?: ""
    }

    // ─── Cause Chain: full exception chain with each cause's stack ───

    private fun buildCauseChain(throwable: Throwable): String {
        val sb = StringBuilder()
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 10) {
            depth++
            sb.appendLine("\n--- Caused by (depth=$depth): ${cause.javaClass.name}: ${cause.message} ---")
            val sw = StringWriter()
            cause.printStackTrace(PrintWriter(sw))
            // Take first 20 frames of each cause
            val lines = sw.toString().lines()
            lines.take(25).forEach { sb.appendLine(it) }
            if (lines.size > 25) sb.appendLine("    ... ${lines.size - 25} more frames")
            cause = cause.cause
        }
        return sb.toString()
    }

    // ─── App State ───

    private fun captureAppState(): Map<String, String> {
        val state = mutableMapOf<String, String>()
        try {
            val ctx = ScoovaMonitor.appContext

            // App process info
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val processInfo = am?.runningAppProcesses?.find { it.pid == Process.myPid() }
            state["processImportance"] = when (processInfo?.importance) {
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground_service"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
                else -> "unknown (${processInfo?.importance})"
            }

            // Is app in foreground
            state["isAppInForeground"] = (processInfo?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND).toString()

            // Process uptime
            val uptimeMs = android.os.SystemClock.elapsedRealtime()
            state["processUptimeMinutes"] = "%.1f".format(uptimeMs / 60000.0)

            // Session info
            state["sessionNumber"] = DeviceContext.sessionNumber.toString()
            state["sessionId"] = DeviceContext.sessionId

            // App version
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            state["appVersion"] = pi.versionName ?: "unknown"
            state["packageName"] = ctx.packageName

            // Android-specific
            state["apiLevel"] = Build.VERSION.SDK_INT.toString()
            state["device"] = "${Build.MANUFACTURER} ${Build.MODEL}"
            state["cpuAbi"] = Build.SUPPORTED_ABIS.joinToString(", ")

            // Tasks
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val isDeviceIdle = (ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isDeviceIdleMode
                state["isDeviceIdle"] = isDeviceIdle.toString()
            }

        } catch (_: Exception) {}
        return state
    }

    // ─── Memory Forensics ───

    private fun captureMemoryState(): Map<String, String> {
        val mem = mutableMapOf<String, String>()
        try {
            val runtime = Runtime.getRuntime()
            val maxMem = runtime.maxMemory()
            val totalMem = runtime.totalMemory()
            val freeMem = runtime.freeMemory()
            val usedMem = totalMem - freeMem

            mem["heapMax"] = formatBytes(maxMem)
            mem["heapTotal"] = formatBytes(totalMem)
            mem["heapUsed"] = formatBytes(usedMem)
            mem["heapFree"] = formatBytes(freeMem)
            mem["heapUsagePercent"] = "%.1f%%".format(usedMem.toDouble() / maxMem * 100)

            // Native memory
            mem["nativeHeapSize"] = formatBytes(Debug.getNativeHeapSize())
            mem["nativeHeapAllocated"] = formatBytes(Debug.getNativeHeapAllocatedSize())
            mem["nativeHeapFree"] = formatBytes(Debug.getNativeHeapFreeSize())

            // System memory
            val am = ScoovaMonitor.appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            mem["systemTotalRam"] = formatBytes(memInfo.totalMem)
            mem["systemAvailableRam"] = formatBytes(memInfo.availMem)
            mem["isLowMemory"] = memInfo.lowMemory.toString()
            mem["lowMemoryThreshold"] = formatBytes(memInfo.threshold)

            // GC stats: java.lang.management is not available on Android,
            // so we rely on the Runtime-level memory counters captured above.
        } catch (_: Exception) {}
        return mem
    }

    // ─── Crash Pattern Detection ───

    private fun detectCrashPattern(throwable: Throwable, stackTrace: String): String {
        val type = throwable.javaClass.name
        val msg = throwable.message ?: ""

        return when {
            type.contains("NullPointerException") -> "NULL_POINTER: Attempted to use a null reference"
            type.contains("OutOfMemoryError") -> "OOM: App exceeded memory limit. Check for memory leaks, large bitmaps, or unbounded caches"
            type.contains("StackOverflowError") -> "STACK_OVERFLOW: Infinite recursion detected"
            type.contains("ClassCastException") -> "CLASS_CAST: Invalid type conversion"
            type.contains("IndexOutOfBoundsException") -> "INDEX_OOB: Array/list access beyond bounds"
            type.contains("IllegalStateException") && msg.contains("already") -> "LIFECYCLE: Operation on destroyed/finished component"
            type.contains("IllegalStateException") -> "ILLEGAL_STATE: Operation not valid in current state"
            type.contains("SecurityException") -> "SECURITY: Missing permission or security violation"
            type.contains("NetworkOnMainThreadException") -> "NETWORK_MAIN_THREAD: Network call on UI thread"
            type.contains("DeadObjectException") -> "DEAD_OBJECT: Binder/IPC failure — process may have been killed"
            type.contains("SQLiteException") -> "DATABASE: SQLite error — check schema, migrations, or disk space"
            type.contains("JsonException") || type.contains("JsonSyntaxException") || type.contains("SerializationException") ->
                "JSON_PARSE: Failed to parse JSON — check API response format"
            type.contains("SSLException") || type.contains("CertificateException") ->
                "SSL: TLS/SSL error — check certificate pinning or server config"
            type.contains("SocketTimeoutException") || type.contains("ConnectException") ->
                "NETWORK_TIMEOUT: Connection failed or timed out"
            type.contains("WindowManager\$BadTokenException") ->
                "BAD_TOKEN: Showing dialog/UI after activity destroyed"
            type.contains("TransactionTooLargeException") ->
                "TRANSACTION_TOO_LARGE: Data passed via Intent/Bundle exceeds 1MB limit"
            type.contains("RuntimeException") && msg.contains("Canvas") ->
                "CANVAS: Drawing error — bitmap may be recycled or too large"
            stackTrace.contains("RecyclerView") && type.contains("IllegalStateException") ->
                "RECYCLERVIEW: Adapter data changed without notifyDataSetChanged"
            stackTrace.contains("Fragment") && type.contains("IllegalStateException") ->
                "FRAGMENT: Fragment lifecycle error — may be detached or activity destroyed"
            type.contains("ConcurrentModificationException") ->
                "CONCURRENT_MODIFICATION: Collection modified during iteration — use synchronized access"
            type.contains("UnsatisfiedLinkError") ->
                "NATIVE_LIB: Failed to load native library (.so) — check ABI/architecture support"
            else -> "UNKNOWN: Review stack trace for root cause"
        }
    }

    // ─── Enhanced ANR Detection ───

    private fun startANRDetector() {
        val mainThread = Looper.getMainLooper().thread
        val watchdog = Thread({
            while (true) {
                try {
                    val responded = AtomicBoolean(false)
                    Handler(Looper.getMainLooper()).post { responded.set(true) }
                    Thread.sleep(5000) // 5 second threshold

                    if (!responded.get()) {
                        // ANR detected — capture FULL forensic dump
                        val allThreadDump = captureAllThreads(mainThread)
                        val mainStack = mainThread.stackTrace

                        // Detect what's blocking the main thread
                        val blockingInfo = analyzeANR(mainStack, mainThread)

                        val anrException = RuntimeException(
                            "ANR detected — main thread blocked for >5s\n" +
                            "Blocking: ${blockingInfo["blockingOperation"]}\n" +
                            "State: ${mainThread.state}"
                        )
                        anrException.stackTrace = mainStack

                        val report = CrashPayload(
                            exceptionType = "ANR (Application Not Responding)",
                            message = PrivacyGuard.sanitizeMessage(
                                "Main thread blocked for >5 seconds. ${blockingInfo["explanation"] ?: ""}"
                            ),
                            stackTrace = PrivacyGuard.sanitizeStackTrace(
                                buildANRStackTrace(mainStack, blockingInfo)
                            ),
                            isFatal = false,
                            device = try { DeviceContext.collect(ScoovaMonitor.appContext) } catch (_: Exception) { null },
                            userId = PrivacyGuard.hashUserId(DeviceContext.userId),
                            sessionId = DeviceContext.sessionId,
                            timestamp = LocalDateTime.now().toString(),
                            allThreads = allThreadDump,
                            appState = captureAppState(),
                            memoryState = captureMemoryState(),
                            crashContext = blockingInfo
                        )
                        sendCrashReport(report)

                        Thread.sleep(30_000) // Don't re-report for 30s
                    }
                    Thread.sleep(10_000) // Check every 15s total
                } catch (_: InterruptedException) { break }
            }
        }, "ScoovaMonitor-ANR-Watchdog")
        watchdog.isDaemon = true
        watchdog.start()
    }

    private fun analyzeANR(mainStack: Array<StackTraceElement>, mainThread: Thread): Map<String, String> {
        val info = mutableMapOf<String, String>()
        info["threadState"] = mainThread.state.name

        val stackStr = mainStack.joinToString("\n") { it.toString() }

        info["blockingOperation"] = when {
            stackStr.contains("SharedPreferences") -> "SharedPreferences commit/apply on main thread"
            stackStr.contains("SQLite") || stackStr.contains("database") -> "Database operation on main thread"
            stackStr.contains("HttpURLConnection") || stackStr.contains("OkHttp") || stackStr.contains("Retrofit") ->
                "Network request on main thread"
            stackStr.contains("BitmapFactory") || stackStr.contains("ImageDecoder") ->
                "Image decoding on main thread"
            stackStr.contains("FileInputStream") || stackStr.contains("FileOutputStream") ->
                "File I/O on main thread"
            stackStr.contains("ContentResolver") || stackStr.contains("ContentProvider") ->
                "ContentProvider query on main thread"
            stackStr.contains("Binder") || stackStr.contains("IPC") ->
                "IPC/Binder call blocking main thread"
            stackStr.contains("inflate") || stackStr.contains("LayoutInflater") ->
                "Complex layout inflation"
            stackStr.contains("GC") || stackStr.contains("garbage") ->
                "Garbage collection pause"
            mainThread.state == Thread.State.BLOCKED -> "Thread deadlock or lock contention"
            mainThread.state == Thread.State.WAITING -> "Waiting for lock/resource"
            else -> "Unknown — check main thread stack trace"
        }

        info["explanation"] = when {
            stackStr.contains("SharedPreferences") ->
                "SharedPreferences.commit() blocks the main thread. Use apply() instead, or move to a background thread."
            stackStr.contains("SQLite") ->
                "Database operations should never run on the main thread. Use Room with coroutines or an AsyncTask."
            stackStr.contains("HttpURLConnection") || stackStr.contains("OkHttp") ->
                "Network requests must run on a background thread. Use Retrofit with coroutines or OkHttp's enqueue()."
            stackStr.contains("BitmapFactory") ->
                "Decoding large images on the main thread causes ANRs. Use Glide, Coil, or decode in a background thread."
            mainThread.state == Thread.State.BLOCKED ->
                "The main thread is blocked waiting for a lock held by another thread. This is a potential deadlock."
            else -> "The main thread was unresponsive for >5 seconds. Review the stack trace to identify the blocking operation."
        }

        // Find the first app frame (not android/java framework)
        val appFrame = mainStack.firstOrNull { frame ->
            !frame.className.startsWith("android.") &&
            !frame.className.startsWith("java.") &&
            !frame.className.startsWith("com.android.") &&
            !frame.className.startsWith("dalvik.") &&
            !frame.className.startsWith("kotlin.") &&
            !frame.className.startsWith("kotlinx.")
        }
        if (appFrame != null) {
            info["firstAppFrame"] = "${appFrame.className}.${appFrame.methodName}(${appFrame.fileName}:${appFrame.lineNumber})"
        }

        return info
    }

    private fun buildANRStackTrace(mainStack: Array<StackTraceElement>, info: Map<String, String>): String {
        val sb = StringBuilder()
        sb.appendLine("=== ANR REPORT ===")
        sb.appendLine("Blocking: ${info["blockingOperation"]}")
        sb.appendLine("Explanation: ${info["explanation"]}")
        sb.appendLine("First app frame: ${info["firstAppFrame"] ?: "N/A"}")
        sb.appendLine()
        sb.appendLine("=== MAIN THREAD STACK ===")
        for (frame in mainStack) {
            sb.appendLine("    at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
        }
        return sb.toString()
    }

    // ─── Send / Save ───

    private fun sendCrashReport(payload: CrashPayload) {
        val json = crashJson.encodeToString(payload)
        postJson("$endpoint/v1/ingest/crashes", json)
    }

    private fun saveCrashToDisk(throwable: Throwable, isFatal: Boolean) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val payload = CrashPayload(
                exceptionType = throwable.javaClass.name,
                message = throwable.message ?: "No message",
                stackTrace = sw.toString(),
                isFatal = isFatal,
                timestamp = LocalDateTime.now().toString()
            )
            val file = java.io.File(ScoovaMonitor.appContext.filesDir, "scoova_pending_crash.json")
            file.writeText(crashJson.encodeToString(payload))
        } catch (_: Exception) { }
    }

    private fun postJson(urlStr: String, json: String) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-API-Key", apiKey)
        if (ScoovaMonitor.bundleId.isNotBlank()) {
            conn.setRequestProperty("X-Bundle-Id", ScoovaMonitor.bundleId)
        }
        conn.doOutput = true
        conn.outputStream.use { it.write(json.toByteArray()) }
        conn.responseCode
        conn.disconnect()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun countCauseDepth(throwable: Throwable): Int {
        var depth = 0
        var cause = throwable.cause
        while (cause != null && depth < 20) { depth++; cause = cause.cause }
        return depth
    }
}
