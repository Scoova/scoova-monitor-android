# Changelog

## 1.4.0

Initial public release of the Scoova Monitor Android SDK.

- Crash reporting and ANR detection
- Analytics events and screen tracking
- Performance metrics and battery monitoring
- OkHttp interceptor for network performance tracking
- Structured logging with tagged loggers
- Privacy: user IDs are SHA-256 hashed on-device before sending;
  the SDK reads no location APIs or permissions
- GDPR / CCPA `clearLocalUserData()` helper
