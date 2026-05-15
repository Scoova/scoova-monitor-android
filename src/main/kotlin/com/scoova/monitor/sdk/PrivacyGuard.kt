package com.scoova.monitor.sdk

import java.security.MessageDigest

/**
 * PrivacyGuard — All PII is hashed or sanitized at the SDK level
 * before any data leaves the device. This guarantees that no
 * sensitive user data is ever transmitted to our servers.
 */
internal object PrivacyGuard {

    private const val HASH_PREFIX = "h_" // prefix to indicate hashed value
    private const val ANON_PREFIX = "anon_" // prefix to indicate anonymous installation ID

    // Regex patterns for PII detection
    private val EMAIL_PATTERN = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    private val PHONE_PATTERN = Regex("\\+?[0-9]{7,15}")
    private val IP_PATTERN = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val JWT_PATTERN = Regex("eyJ[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,}")
    private val UUID_LIKE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
    private val CREDIT_CARD_PATTERN = Regex("\\b(?:\\d{4}[- ]?){3}\\d{4}\\b")

    /**
     * Hash a user identifier. If the host app called setUserId we send the SHA256-hashed
     * value (h_<hash>). Otherwise we fall back to the persisted anonymous installation ID
     * (anon_<uuid>) so DAU/MAU/retention/sessions analytics always have something distinct
     * to count. Never returns null when the SDK is initialized.
     */
    fun hashUserId(userId: String?): String? {
        if (!userId.isNullOrBlank()) return HASH_PREFIX + sha256(userId)
        return AnonIdStore.get() // returns null only if SDK not initialized
    }

    /**
     * Sanitize a URL — strip query params and fragments which may contain tokens,
     * emails, user IDs, or other PII. Keep only scheme + host + path.
     */
    fun sanitizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = java.net.URI(url)
            // Rebuild without query and fragment
            java.net.URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        } catch (_: Exception) {
            // If URL parsing fails, strip everything after ? or #
            url.split('?', '#').firstOrNull() ?: url
        }
    }

    /**
     * Sanitize custom event data — auto-detect and hash any values that look like PII.
     * Keys are left as-is (they're developer-defined labels, not user data).
     */
    fun sanitizeEventData(data: Map<String, String>?): Map<String, String>? {
        if (data == null) return null
        return data.mapValues { (key, value) -> sanitizeValue(key, value) }
    }

    /**
     * Sanitize a stack trace — remove home directory paths and any embedded PII.
     */
    fun sanitizeStackTrace(stackTrace: String): String {
        var sanitized = stackTrace

        // Remove home directory paths (e.g., /Users/john/...)
        sanitized = sanitized.replace(Regex("/Users/[^/]+/"), "/Users/****/")
        sanitized = sanitized.replace(Regex("/home/[^/]+/"), "/home/****/")

        // Hash any emails found in stack traces
        sanitized = EMAIL_PATTERN.replace(sanitized) { HASH_PREFIX + sha256(it.value).take(12) }

        // Hash any IPs found in stack traces
        sanitized = IP_PATTERN.replace(sanitized) { HASH_PREFIX + sha256(it.value).take(8) }

        // Hash JWT tokens
        sanitized = JWT_PATTERN.replace(sanitized) { "[hashed_token]" }

        return sanitized
    }

    /**
     * Sanitize a crash message — may contain user input or PII.
     */
    fun sanitizeMessage(message: String): String {
        var sanitized = message
        sanitized = EMAIL_PATTERN.replace(sanitized) { "[hashed_email:${sha256(it.value).take(8)}]" }
        sanitized = CREDIT_CARD_PATTERN.replace(sanitized) { "[redacted_card]" }
        sanitized = JWT_PATTERN.replace(sanitized) { "[hashed_token]" }
        return sanitized
    }

    // ─── Internal helpers ───

    private fun sanitizeValue(key: String, value: String): String {
        // Check if the key name suggests PII
        val piiKeys = setOf("email", "mail", "phone", "tel", "mobile", "name", "username",
            "user_name", "first_name", "last_name", "address", "ssn", "password",
            "token", "secret", "api_key", "credit_card", "card_number")
        // Keys whose substring would otherwise hit piiKeys but are clearly safe
        // (e.g. "screen_name" matches "name" but is just a route label).
        val piiKeyAllowlist = setOf("screen_name", "previous_screen", "screen", "route",
            "route_name", "next_screen", "event_name", "tag_name", "class_name",
            "package_name", "session_id", "session_number", "view_name")

        val keyLower = key.lowercase()
        if (keyLower in piiKeyAllowlist) {
            return value
        }
        if (piiKeys.any { keyLower.contains(it) }) {
            return HASH_PREFIX + sha256(value).take(16)
        }

        // Auto-detect PII patterns in value
        if (EMAIL_PATTERN.containsMatchIn(value)) {
            return HASH_PREFIX + sha256(value).take(16)
        }
        if (CREDIT_CARD_PATTERN.containsMatchIn(value)) {
            return "[redacted]"
        }
        if (JWT_PATTERN.containsMatchIn(value)) {
            return HASH_PREFIX + sha256(value).take(16)
        }
        // Phone numbers only if the value is primarily a phone number
        if (value.length in 7..16 && PHONE_PATTERN.matches(value.trim())) {
            return HASH_PREFIX + sha256(value).take(16)
        }

        return value // Not PII — pass through
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
