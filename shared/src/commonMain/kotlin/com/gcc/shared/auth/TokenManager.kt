package com.gcc.shared.auth

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Manages JWT auth tokens with persistent storage.
 *
 * Uses `multiplatform-settings` for persistence:
 * - Android: SharedPreferences
 * - iOS: NSUserDefaults
 * - Desktop: java.util.prefs.Preferences
 *
 * Token survives app restarts. On construction, loads any saved token.
 */
open class TokenManager(private val settings: Settings = Settings()) {
    private val _token = MutableStateFlow<String?>(settings.getStringOrNull(KEY_AUTH_TOKEN))
    val token: StateFlow<String?> = _token.asStateFlow()

    fun saveToken(token: String) {
        settings.putString(KEY_AUTH_TOKEN, token)
        _token.value = token
    }

    fun getToken(): String? = _token.value

    fun clearToken() {
        settings.remove(KEY_AUTH_TOKEN)
        _token.value = null
    }

    fun isLoggedIn(): Boolean = _token.value != null

    /** Check if onboarding has been completed (stored as a boolean setting). */
    fun isOnboardingComplete(): Boolean = settings.getBoolean(KEY_ONBOARDING_COMPLETE, false)

    fun setOnboardingComplete() {
        settings.putBoolean(KEY_ONBOARDING_COMPLETE, true)
    }

    /**
     * Decode the JWT payload and check the "exp" claim.
     * Returns true if the token is expired or unparseable.
     */
    fun isTokenExpired(): Boolean {
        val token = _token.value ?: return true
        val exp = getJwtClaim(token, "exp") ?: return true
        val expSeconds = exp.toLongOrNull() ?: return true
        return currentTimeSeconds() >= expSeconds
    }

    /**
     * Extract the worker ID from the JWT payload's "workerId" claim.
     * Returns null if the token is missing or the claim cannot be parsed.
     */
    fun getWorkerId(): Int? {
        val token = _token.value ?: return null
        return getJwtClaim(token, "workerId")?.toIntOrNull()
    }

    /**
     * Decode the base64url-encoded JWT payload section.
     * Returns the decoded JSON string, or null if the token is malformed.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeJwtPayload(token: String): String? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val payload = parts[1]
            .replace('-', '+')
            .replace('_', '/')
        return try {
            Base64.decode(payload).decodeToString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract a single claim value from a JWT token using regex.
     * Handles both string and numeric claim values.
     */
    private fun getJwtClaim(token: String, claimName: String): String? {
        val json = decodeJwtPayload(token) ?: return null
        val regex = """"$claimName"\s*:\s*"?([^"\},]+)"?""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }

    companion object {
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"

        /** Platform-independent current time in seconds since epoch. */
        private fun currentTimeSeconds(): Long {
            return kotlinx.datetime.Clock.System.now().epochSeconds
        }
    }
}
