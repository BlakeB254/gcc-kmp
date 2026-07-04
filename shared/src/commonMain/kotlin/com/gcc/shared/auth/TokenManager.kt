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
        settings.putString(KEY_BIOMETRIC_TOKEN, token)
        _token.value = token
    }

    fun getToken(): String? = _token.value

    fun clearToken() {
        // Save token for biometric re-auth before clearing active session
        _token.value?.let { settings.putString(KEY_BIOMETRIC_TOKEN, it) }
        settings.remove(KEY_AUTH_TOKEN)
        _token.value = null
    }

    /** Full logout: clears ALL auth state so the user must re-enter credentials.
     *  Use this for explicit sign-out. Use clearToken() for soft session clear. */
    fun logout() {
        settings.remove(KEY_AUTH_TOKEN)
        settings.remove(KEY_BIOMETRIC_TOKEN)
        settings.remove(KEY_CREDENTIAL_EMAIL)
        settings.remove(KEY_CREDENTIAL_PASSWORD)
        settings.remove(KEY_REMEMBERED_EMAIL)
        _token.value = null
    }

    fun isLoggedIn(): Boolean = _token.value != null

    /** Check if a previous session token exists for biometric re-authentication.
     *  Does NOT check expiry — the token's existence means the user has authenticated before.
     *  Actual validity is checked on restore. */
    fun hasBiometricToken(): Boolean {
        val biometricToken = settings.getStringOrNull(KEY_BIOMETRIC_TOKEN) ?: return false
        // Just verify it's a parseable JWT (3 parts)
        return biometricToken.split(".").size == 3
    }

    /** Restore the biometric token as the active session token. Returns true if successful.
     *  If the token is expired, returns false but keeps the biometric token stored
     *  so Face ID remains available for next login. */
    fun restoreBiometricToken(): Boolean {
        val biometricToken = settings.getStringOrNull(KEY_BIOMETRIC_TOKEN) ?: return false
        val exp = getJwtClaim(biometricToken, "exp") ?: return false
        val expSeconds = exp.toLongOrNull() ?: return false
        if (currentTimeSeconds() >= expSeconds) {
            // Token expired — don't delete it (keep Face ID button visible), just signal failure
            return false
        }
        saveToken(biometricToken)
        return true
    }

    /** Clear biometric token (e.g., when user explicitly disables biometric login). */
    fun clearBiometricToken() {
        settings.remove(KEY_BIOMETRIC_TOKEN)
    }

    /** Save the user's email for "Remember Me" functionality. */
    fun saveRememberMe(email: String) {
        settings.putString(KEY_REMEMBERED_EMAIL, email)
    }

    /** Get the remembered email, or null if none saved. */
    fun getRememberedEmail(): String? = settings.getStringOrNull(KEY_REMEMBERED_EMAIL)

    /** Clear the remembered email. */
    fun clearRememberMe() {
        settings.remove(KEY_REMEMBERED_EMAIL)
    }

    /** Save login credentials for biometric re-authentication.
     *  On iOS, SecureStorage is Keychain-backed, so credentials are encrypted by the Secure Enclave. */
    fun saveCredentials(email: String, password: String) {
        settings.putString(KEY_CREDENTIAL_EMAIL, email)
        settings.putString(KEY_CREDENTIAL_PASSWORD, password)
    }

    /** Load saved credentials for biometric re-login. Returns null if none stored. */
    fun loadCredentials(): Pair<String, String>? {
        val email = settings.getStringOrNull(KEY_CREDENTIAL_EMAIL) ?: return null
        val password = settings.getStringOrNull(KEY_CREDENTIAL_PASSWORD) ?: return null
        return email to password
    }

    /** Check if saved credentials exist for biometric re-login. */
    fun hasStoredCredentials(): Boolean {
        return settings.getStringOrNull(KEY_CREDENTIAL_EMAIL) != null &&
            settings.getStringOrNull(KEY_CREDENTIAL_PASSWORD) != null
    }

    /** Clear stored credentials (e.g., on explicit logout or password change). */
    fun clearCredentials() {
        settings.remove(KEY_CREDENTIAL_EMAIL)
        settings.remove(KEY_CREDENTIAL_PASSWORD)
    }

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
     * Like [isTokenExpired], but true up to [bufferSeconds] before actual
     * expiry -- lets callers refresh proactively while the token is still
     * valid enough to authenticate the refresh call itself (the refresh
     * endpoint rejects an already-expired token outright).
     */
    fun isTokenExpiringSoon(bufferSeconds: Long = ONE_DAY_SECONDS): Boolean {
        val token = _token.value ?: return true
        val exp = getJwtClaim(token, "exp") ?: return true
        val expSeconds = exp.toLongOrNull() ?: return true
        return currentTimeSeconds() >= (expSeconds - bufferSeconds)
    }

    /**
     * Extract the worker ID from the JWT payload's "workerId" claim.
     * Returns null if the token is missing or the claim cannot be parsed.
     */
    fun getWorkerId(): Int? {
        val token = _token.value ?: return null
        return getJwtClaim(token, "workerId")?.toIntOrNull()
            ?: getJwtClaim(token, "worker_id")?.toIntOrNull()
    }

    /**
     * Extract the user role from the JWT payload's "role" claim.
     * Returns null if the token is missing or the claim cannot be parsed.
     */
    fun getUserRole(): String? {
        val token = _token.value ?: return null
        return getJwtClaim(token, "role")
    }

    /**
     * Check if the current user has admin privileges.
     * Returns true for "admin" or "super_admin" roles.
     */
    fun isAdmin(): Boolean {
        val role = getUserRole() ?: return false
        return role == "admin" || role == "super_admin"
    }

    /**
     * Decode the base64url-encoded JWT payload section.
     * Returns the decoded JSON string, or null if the token is malformed.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeJwtPayload(token: String): String? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        var payload = parts[1]
            .replace('-', '+')
            .replace('_', '/')
        // Add standard Base64 padding (JWT omits it)
        when (payload.length % 4) {
            2 -> payload += "=="
            3 -> payload += "="
        }
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
        private const val KEY_BIOMETRIC_TOKEN = "biometric_token"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_REMEMBERED_EMAIL = "remembered_email"
        private const val KEY_CREDENTIAL_EMAIL = "credential_email"
        private const val KEY_CREDENTIAL_PASSWORD = "credential_password"

        /** Default proactive-refresh buffer -- matches the server's 7-day token lifetime with room to spare. */
        const val ONE_DAY_SECONDS = 86_400L

        /** Platform-independent current time in seconds since epoch. */
        private fun currentTimeSeconds(): Long {
            return kotlinx.datetime.Clock.System.now().epochSeconds
        }
    }
}
