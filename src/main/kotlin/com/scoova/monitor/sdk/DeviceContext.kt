package com.scoova.monitor.sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.util.DisplayMetrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@Serializable
data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val osName: String = "Android",
    val osVersion: String? = null,
    val osApiLevel: Int? = null,
    val appVersion: String? = null,
    val buildNumber: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val country: String? = null,
    val carrier: String? = null,
    val networkType: String? = null,
    val networkGeneration: String? = null,
    val screenResolution: String? = null,
    val cpuArch: String? = null,
    val gpuRenderer: String? = null,
    val ramTotal: Long? = null,
    val ramFree: Long? = null,
    val diskFree: Long? = null,
    val batteryLevel: Float? = null,
    val isCharging: Boolean? = null,
    val thermalState: String? = null,
    val orientation: String? = null,
    val jailbroken: Boolean? = null,
    val framework: String? = null,
    val sdkVersion: String? = null,
)

internal const val SDK_VERSION = "1.4.0"

internal object DeviceContext {
    var userId: String? = null
    var sessionId: String = UUID.randomUUID().toString()

    var sessionNumber: Int = 0
        private set

    fun collect(context: Context): DeviceInfo {
        val pm = context.packageManager
        val pi = try { pm.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        val runtime = Runtime.getRuntime()
        val dm = context.resources.displayMetrics

        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            osName = "Android",
            osVersion = Build.VERSION.RELEASE,
            osApiLevel = Build.VERSION.SDK_INT,
            appVersion = pi?.versionName,
            buildNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi?.longVersionCode?.toString()
            } else {
                @Suppress("DEPRECATION")
                pi?.versionCode?.toString()
            },
            locale = Locale.getDefault().toString(),
            timezone = TimeZone.getDefault().id,
            country = Locale.getDefault().country,
            carrier = getCarrier(context),
            networkType = getNetworkType(context),
            networkGeneration = getNetworkGeneration(context),
            screenResolution = "${dm.widthPixels}x${dm.heightPixels}",
            cpuArch = Build.SUPPORTED_ABIS.firstOrNull(),
            gpuRenderer = getGpuRenderer(),
            ramTotal = runtime.maxMemory(),
            ramFree = runtime.freeMemory(),
            diskFree = getDiskFree(),
            batteryLevel = getBatteryLevel(context),
            isCharging = getIsCharging(context),
            thermalState = getThermalState(context),
            orientation = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                "landscape" else "portrait",
            jailbroken = isRooted(),
            framework = detectFramework(),
            sdkVersion = SDK_VERSION,
        )
    }

    fun collectLight(context: Context): Map<String, String?> {
        val pi = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "osName" to "Android",
            "osVersion" to Build.VERSION.RELEASE,
            "osApiLevel" to Build.VERSION.SDK_INT.toString(),
            "appVersion" to pi?.versionName,
            "locale" to Locale.getDefault().toString(),
            "country" to Locale.getDefault().country,
            "carrier" to getCarrier(context),
            "networkType" to getNetworkType(context),
            "networkGeneration" to getNetworkGeneration(context),
            "cpuArch" to Build.SUPPORTED_ABIS.firstOrNull(),
            "framework" to detectFramework(),
            "sdkVersion" to SDK_VERSION
        )
    }

    private fun getCarrier(context: Context): String? {
        // networkOperatorName doesn't require permission, but wrap safely
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            tm?.networkOperatorName?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) { null }
        catch (_: Exception) { null }
    }

    private fun getNetworkType(context: Context): String {
        return try {
            // Requires ACCESS_NETWORK_STATE — only collect if host app has it
            if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
                return "unknown"
            }
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
            when {
                nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } catch (_: Exception) { "unknown" }
    }

    private fun getDiskFree(): Long? {
        return try {
            val stat = StatFs(android.os.Environment.getDataDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) { null }
    }

    private fun getBatteryLevel(context: Context): Float? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.toFloat()?.div(100f)
        } catch (_: Exception) { null }
    }

    private fun getIsCharging(context: Context): Boolean? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.isCharging
        } catch (_: Exception) { null }
    }

    private fun isRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun getNetworkGeneration(context: Context): String? {
        // Two-tier strategy:
        //   1. If READ_PHONE_STATE is granted, use TelephonyManager.dataNetworkType
        //      for the precise generation (accurate, but permission-gated on API 30+).
        //   2. Otherwise fall back to NetworkCapabilities.linkDownstreamBandwidthKbps,
        //      which gives a rough mapping bandwidth → generation. ~85% accurate
        //      and requires no extra permission.
        return try {
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                @Suppress("DEPRECATION")
                val precise = when (tm?.dataNetworkType) {
                    android.telephony.TelephonyManager.NETWORK_TYPE_GPRS,
                    android.telephony.TelephonyManager.NETWORK_TYPE_EDGE,
                    android.telephony.TelephonyManager.NETWORK_TYPE_CDMA -> "2G"
                    android.telephony.TelephonyManager.NETWORK_TYPE_UMTS,
                    android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA,
                    android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA,
                    android.telephony.TelephonyManager.NETWORK_TYPE_HSPA,
                    android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
                    android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                    20 -> "5G" // NETWORK_TYPE_NR
                    else -> null
                }
                if (precise != null) return precise
            }
            // Permissionless fallback — bandwidth-based inference. Requires
            // ACCESS_NETWORK_STATE which most apps already declare.
            if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) !=
                PackageManager.PERMISSION_GRANTED) return null
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
            if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return null
            val kbps = nc.linkDownstreamBandwidthKbps
            when {
                kbps < 500 -> "2G"
                kbps < 3_000 -> "3G"
                kbps < 30_000 -> "4G"
                else -> "5G"
            }
        } catch (_: Exception) { null }
    }

    private fun getThermalState(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                when (pm?.currentThermalStatus) {
                    android.os.PowerManager.THERMAL_STATUS_NONE -> "nominal"
                    android.os.PowerManager.THERMAL_STATUS_LIGHT -> "fair"
                    android.os.PowerManager.THERMAL_STATUS_MODERATE -> "fair"
                    android.os.PowerManager.THERMAL_STATUS_SEVERE -> "serious"
                    android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                    android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "critical"
                    android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "critical"
                    else -> null
                }
            } else null
        } catch (_: Exception) { null }
    }

    private fun getGpuRenderer(): String? {
        // GLES20.glGetString() crashes if called off the GL thread.
        // Read from system property instead — works without GL context or permissions.
        return try {
            val process = Runtime.getRuntime().exec("getprop ro.hardware.egl")
            process.inputStream.bufferedReader().readLine()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun detectFramework(): String {
        return try {
            when {
                classExists("com.facebook.react.ReactApplication") -> "react-native"
                classExists("io.flutter.embedding.engine.FlutterEngine") -> "flutter"
                classExists("org.koin.core.KoinApplication") ||
                    classExists("kotlin.native.concurrent.SharedImmutable") -> "kmp"
                else -> "native"
            }
        } catch (_: Exception) { "native" }
    }

    private fun classExists(className: String): Boolean {
        return try { Class.forName(className); true } catch (_: ClassNotFoundException) { false }
    }

    fun detectThirdPartySDKs(): List<Pair<String, String?>> {
        val sdks = mutableListOf<Pair<String, String?>>()
        val knownSDKs = mapOf(
            "com.google.firebase.FirebaseApp" to "firebase",
            "com.google.android.gms.ads.MobileAds" to "google-admob",
            "com.facebook.FacebookSdk" to "facebook-sdk",
            "com.stripe.android.Stripe" to "stripe",
            "io.sentry.Sentry" to "sentry",
            "com.crashlytics.android.Crashlytics" to "crashlytics",
            "com.amplitude.api.Amplitude" to "amplitude",
            "com.mixpanel.android.mpmetrics.MixpanelAPI" to "mixpanel",
            "com.appsflyer.AppsFlyerLib" to "appsflyer",
            "com.adjust.sdk.Adjust" to "adjust",
            "com.braze.Braze" to "braze",
            "com.onesignal.OneSignal" to "onesignal",
            "com.google.android.gms.maps.GoogleMap" to "google-maps",
            "com.squareup.retrofit2.Retrofit" to "retrofit",
            "com.squareup.okhttp3.OkHttpClient" to "okhttp",
            "io.realm.Realm" to "realm",
            "com.google.gson.Gson" to "gson",
            "com.airbnb.lottie.LottieAnimationView" to "lottie"
        )
        for ((className, sdkName) in knownSDKs) {
            if (classExists(className)) sdks.add(sdkName to null)
        }
        return sdks
    }

    fun incrementSession(context: Context) {
        val prefs = context.getSharedPreferences("scoova_monitor", Context.MODE_PRIVATE)
        sessionNumber = prefs.getInt("session_number", 0) + 1
        prefs.edit().putInt("session_number", sessionNumber).apply()
    }

    /**
     * Wipe persisted session number + first-launch marker. Called from
     * clearLocalUserData() so the next session looks like a fresh install.
     */
    fun resetSessionCounter(context: Context) {
        val prefs = context.getSharedPreferences("scoova_monitor", Context.MODE_PRIVATE)
        prefs.edit().remove("session_number").remove("first_launch_done").apply()
        sessionNumber = 0
    }

    fun isFirstLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences("scoova_monitor", Context.MODE_PRIVATE)
        val first = !prefs.contains("first_launch_done")
        if (first) prefs.edit().putBoolean("first_launch_done", true).apply()
        return first
    }

    fun getInstallDate(context: Context): Long {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        } catch (_: Exception) { System.currentTimeMillis() }
    }

    // Device GPS location is intentionally NOT collected by this SDK.
    // Region is derived server-side from the request IP at ingest, which
    // covers the regional analytics the dashboard shows — so the SDK
    // touches no location APIs and reads no location permission.

    fun newSession() {
        sessionId = UUID.randomUUID().toString()
    }
}
