# Scoova Monitor SDK - ProGuard/R8 consumer rules
# These rules are automatically applied to apps that include this SDK

# Keep SDK public API
-keep class com.scoova.monitor.sdk.ScoovaMonitor { *; }
-keep class com.scoova.monitor.sdk.ScoovaMonitor$Config { *; }
-keep class com.scoova.monitor.sdk.ScoovaOkHttpInterceptor { *; }

# Keep serializable data classes (sent as JSON)
-keep class com.scoova.monitor.sdk.DeviceInfo { *; }
-keep class com.scoova.monitor.sdk.DeviceInfoLight { *; }
-keep class com.scoova.monitor.sdk.EventPayload { *; }
-keep class com.scoova.monitor.sdk.MetricPayload { *; }
-keep class com.scoova.monitor.sdk.CrashPayload { *; }
-keep class com.scoova.monitor.sdk.BatchPayload { *; }
-keep class com.scoova.monitor.sdk.BatchMetricPayload { *; }
-keep class com.scoova.monitor.sdk.Breadcrumb { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class com.scoova.monitor.sdk.** {
    *** Companion;
    *** serializer(...);
    kotlinx.serialization.KSerializer $$serializer(...);
}

# Don't warn about optional OkHttp dependency
-dontwarn okhttp3.**
-dontwarn okio.**
