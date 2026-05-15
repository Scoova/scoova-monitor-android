# Scoova Monitor — Android SDK

Kotlin Android library for crash reporting, analytics, performance, ANR
detection, and battery monitoring. API 21+ (Android 5.0 Lollipop).

## Install

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.scoova.monitor:sdk-android:1.4.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.scoova.monitor:sdk-android:1.4.0'
}
```

The SDK uses Maven Central — no extra repository setup is needed.

## Usage

Initialize once in your `Application.onCreate()`:

```kotlin
import com.scoova.monitor.sdk.ScoovaMonitor

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ScoovaMonitor.init(this, "sm_your_api_key")
    }
}
```

Don't forget to register your Application class in `AndroidManifest.xml`:

```xml
<application android:name=".MyApp" ... />
```

## Configuration

```kotlin
val config = ScoovaMonitor.Config(
    endpoint = "https://monitor.scoo-va.info",   // self-hosted? change this
    flushIntervalMs = 300_000,                    // 5 minutes
    maxBatchSize = 50,
    enableSDKDetection = false                    // opt-in; default false
)

ScoovaMonitor.init(application, "sm_...", config)
```

### Privacy

The SDK does **not** read device GPS location and touches no location
APIs or permissions. Region (country / city) for analytics is resolved
server-side from the request IP at ingest — the raw IP is never stored.

`enableSDKDetection` is **off by default**. When enabled, on first launch
only the SDK probes for third-party libraries on the classpath (Firebase,
Sentry, Mixpanel, Stripe, Google Maps, Retrofit, OkHttp, etc.) and emits a
single `detected_sdks` event. Useful for the SDK-adoption dashboard, never
required for crash/analytics/perf.

See [the documentation](https://monitor.scoo-va.info/docs)
for the full collection inventory.

## API

### Identify the user

```kotlin
ScoovaMonitor.setUserId("user_123")
```

The user ID is hashed before it leaves the device. Without `setUserId`
the SDK falls back to an anonymous installation ID.

### Track events

```kotlin
ScoovaMonitor.trackEvent("checkout_started", mapOf(
    "plan" to "annual",
    "amount" to "29.99"
))
```

### Track screens

```kotlin
ScoovaMonitor.trackScreen("ProductDetail")
```

### Report a non-fatal exception

```kotlin
try {
    riskyWork()
} catch (e: Exception) {
    ScoovaMonitor.logException(e)
}
```

### Add breadcrumbs

```kotlin
ScoovaMonitor.addBreadcrumb("Started photo upload", category = "media")
```

### Tagged loggers

```kotlin
val log = ScoovaMonitor.logger("payment")
log.info("Started checkout", mapOf("amount" to "29.99"))
log.error("Card declined", mapOf("code" to "card_declined"))
```

### Right-to-erasure (GDPR / CCPA)

```kotlin
ScoovaMonitor.clearLocalUserData()
```

Wipes queued events, the pending crash file, breadcrumbs, the anonymous
installation ID, the session counter, and any cached location. Pair with a
server-side `DELETE /v1/ingest/me/{userId}`.

### Manual flush

```kotlin
ScoovaMonitor.flush()
```

The SDK auto-flushes every 5 minutes, on app background, and when the
batch threshold is hit — manual flush is rarely needed.

## OkHttp instrumentation

For automatic network performance tracking, add the interceptor:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(ScoovaOkHttpInterceptor())
    .build()
```

URL paths are sent; query strings and fragments are stripped before the
event leaves the device.

## Symbolication

Upload your ProGuard / R8 mapping file so server-side stack traces are
de-obfuscated:

```bash
node sdk-android/scripts/scoova-upload-mapping.js \
    --api-key sm_your_api_key \
    --version 1.0.0 \
    --mapping app/build/outputs/mapping/release/mapping.txt
```

Wire this into your release build via a Gradle task — see
[the documentation](https://monitor.scoo-va.info/docs) for the full setup.

## Building from source

```bash
cd sdk-android
gradle build
```

(Requires the Android Gradle Plugin and Android SDK to be installed.)

## License

[Apache 2.0](LICENSE).
