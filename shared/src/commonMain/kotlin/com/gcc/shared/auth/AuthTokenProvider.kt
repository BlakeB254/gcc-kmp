package com.gcc.shared.auth

import com.gcc.shared.model.AuthRefreshResponse

/**
 * Decides whether the current token needs a proactive refresh before being
 * handed to a request, and performs that refresh if so. See
 * docs/superpowers/specs/2026-07-04-proactive-token-refresh-design.md --
 * reactive refresh-on-401 doesn't work here, the refresh endpoint itself
 * rejects an already-expired token.
 */
class AuthTokenProvider(
    private val tokenManager: TokenManager,
    private val refresh: suspend () -> AuthRefreshResponse,
) {
    suspend fun currentToken(): String? {
        val token = tokenManager.getToken() ?: return null
        if (!tokenManager.isTokenExpiringSoon()) return token
        return try {
            val refreshed = refresh()
            tokenManager.saveToken(refreshed.token)
            refreshed.token
        } catch (e: Exception) {
            // Refresh failed (network error, or the buffer wasn't wide enough
            // and the token's actually dead already) -- fall back to the
            // current token exactly as before this feature existed. The
            // request will 401, onUnauthorized fires, the user is forced
            // back to login. This only ADDS a proactive success path; the
            // existing reactive fallback is unchanged and un-weakened.
            token
        }
    }
}
