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
class TokenManager(private val settings: Settings = Settings()) {
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
    @OptIn(ExperimentalEncodingApi::class)
    fun isTokenExpired(): Boolean {
        val token = _token.value ?: return true
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return true
            // JWT payload is base64url-encoded
            val payload = parts[1]
                .replace('-', '+')
                .replace('_', '/')
            val decoded = Base64.decode(payload)
            val json = decoded.decodeToString()
            // Simple regex extraction of "exp" claim (avoids pulling in a JSON parser)
            val expMatch = Regex(""""exp"\s*:\s*(\d+)""").find(json)
                ?: return true
            val expSeconds = expMatch.groupValues[1].toLong()
            val nowSeconds = currentTimeSeconds()
            nowSeconds >= expSeconds
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Extract the worker ID from the JWT payload's "workerId" claim.
     * Returns null if the token is missing or the claim cannot be parsed.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun getWorkerId(): Int? {
        val token = _token.value ?: return null
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return null
            val payload = parts[1]
                .replace('-', '+')
                .replace('_', '/')
            val decoded = Base64.decode(payload)
            val json = decoded.decodeToString()
            val idMatch = Regex(""""workerId"\s*:\s*(\d+)""").find(json)
            idMatch?.groupValues?.get(1)?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
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
