package com.gcc.shared.auth

import com.gcc.shared.model.AuthRefreshResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides whether the current token needs a proactive refresh before being
 * handed to a request, and performs that refresh if so. See
 * docs/superpowers/specs/2026-07-04-proactive-token-refresh-design.md --
 * reactive refresh-on-401 doesn't work here, the refresh endpoint itself
 * rejects an already-expired token.
 *
 * [refresh] receives the current (soon-to-expire) token explicitly and must
 * authenticate its own network call with it directly -- it must NOT go
 * through the same tokenProvider hook this class's [currentToken] backs,
 * or a refresh in progress recursively re-triggers itself (see GccApiClient.
 * refreshToken()'s doc comment for the concrete mechanism).
 *
 * A [Mutex] serializes concurrent refresh attempts: if two callers both see
 * an expiring token around the same moment, the second waits for the first's
 * refresh to finish, then re-checks (inside the lock) whether a refresh is
 * still needed before doing a second, redundant network call.
 */
class AuthTokenProvider(
    private val tokenManager: TokenManager,
    private val refresh: suspend (currentToken: String) -> AuthRefreshResponse,
) {
    private val mutex = Mutex()

    suspend fun currentToken(): String? {
        val token = tokenManager.getToken() ?: return null
        if (!tokenManager.isTokenExpiringSoon()) return token
        return mutex.withLock {
            val current = tokenManager.getToken() ?: return@withLock null
            if (!tokenManager.isTokenExpiringSoon()) return@withLock current
            try {
                val refreshed = refresh(current)
                tokenManager.saveToken(refreshed.token)
                refreshed.token
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Refresh failed (network error, or the buffer wasn't wide enough
                // and the token's actually dead already) -- fall back to the
                // current token exactly as before this feature existed. The
                // request will 401, onUnauthorized fires, the user is forced
                // back to login. This only ADDS a proactive success path; the
                // existing reactive fallback is unchanged and un-weakened.
                current
            }
        }
    }
}
