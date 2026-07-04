# Proactive Token Refresh — Design Spec

**Date:** 2026-07-04
**Status:** Awaiting review
**Scope:** Make the mobile app refresh its JWT before it expires, instead of the current behavior (nothing calls the existing, working `/api/auth/refresh` endpoint — an expired token just fails every request and forces the user to log back in).

---

## 1. Background

`GccApiClient.refreshToken()` (`shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt:987-988`) is fully implemented and hits a real, working backend endpoint. Nothing calls it. `TokenManager.isTokenExpired()` (`shared/.../auth/TokenManager.kt:131-136`) exists but nothing checks it proactively either. The only thing that happens today when a token expires: the next request 401s, `onUnauthorized` fires (`AppModule.kt:66-69`), the token is cleared, and `SessionExpiredSignal` forces the user back to the login screen.

**Reactive refresh (refresh-on-401) cannot work here at all** — confirmed by reading the actual backend handler (`site/src/app/api/auth/refresh/route.ts:20`, via `verifyMobileToken`/`jose.jwtVerify` in `site/src/lib/mobile-auth.ts:45-48`): JWT verification uses `jose`'s default zero-second clock tolerance, so an already-expired token gets a flat 401 *before* the refresh handler's token-issuing logic ever runs. The refresh call itself needs a still-valid (not-yet-expired) token to authenticate. This must be a **proactive** refresh (before expiry), not reactive.

`GccApiClient`'s `tokenProvider: suspend () -> String?` is invoked fresh on every single HTTP request — a deliberate design choice (`GccApiClient.kt:29`, comment: "Add Bearer token fresh on every request (avoids Ktor Auth plugin caching issue)"). This existing hook is where the proactive check belongs; it doesn't need Ktor's `Auth`/`bearer{}` plugin (already deliberately avoided) or any new request-lifecycle mechanism.

## 2. Design

**`TokenManager` gets a new method**, `isTokenExpiringSoon(bufferSeconds: Long = ONE_DAY_SECONDS): Boolean`, alongside the existing `isTokenExpired()` — same JWT `exp`-claim parsing, but true when `currentTimeSeconds() >= (expSeconds - bufferSeconds)` rather than exact expiry. Given the server issues tokens with a 7-day lifetime (`site/src/lib/mobile-auth.ts:26`, `TOKEN_LIFETIME_MS`), a 1-day buffer is a reasonable default — refreshing once a day of validity remains, comfortably before a user could plausibly go a full day without opening the app and hitting this check.

**A new, separately-testable `AuthTokenProvider` class** (not inline in Koin DI wiring, so it has its own unit tests) owns the decide-and-maybe-refresh logic:

```kotlin
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
            // Refresh failed (network error, or the buffer wasn't wide enough and
            // the token's actually dead already) -- fall back to the current token
            // exactly as today. The request will 401, onUnauthorized fires, the
            // user is forced back to login. This only ADDS a proactive success
            // path; the existing reactive fallback is unchanged.
            token
        }
    }
}
```

**Wiring (`AppModule.kt`):** `GccApiClient`'s `tokenProvider` lambda needs to call `refreshToken()` — a method *on* the very `GccApiClient` being constructed with that lambda. Resolved with a `lateinit var` self-referencing closure (the lambda is only ever invoked per-HTTP-request, well after full construction completes — not at closure-definition time), the same shape of one-time-late-binding this codebase already uses elsewhere (`LocationTrackingController.initLocationProvider`, `PermissionManager.initActivity`):

```kotlin
single {
    val tokenManager = get<TokenManager>()
    lateinit var apiClient: GccApiClient
    val authTokenProvider = AuthTokenProvider(tokenManager) { apiClient.refreshToken() }
    apiClient = GccApiClient(
        baseUrl = getApiBaseUrl(),
        tokenProvider = { authTokenProvider.currentToken() },
        onUnauthorized = {
            tokenManager.clearToken()
            SessionExpiredSignal.fire()
        },
    )
    apiClient
}
```

**`OfflineAppModule.kt` (demo mode) is untouched** — confirmed it never constructs a real `GccApiClient` at all, so there's nothing there to wire.

## 3. Test coverage

- `TokenManagerTest` (new file — no test file exists for this class today): `isTokenExpiringSoon()` true when within the buffer of expiry, false when comfortably valid, true when already past expiry (a superset case), consistent behavior with a missing/malformed token (same as `isTokenExpired()`'s existing "missing/unparseable → true" convention).
- `AuthTokenProviderTest` (new file): no token → returns null, doesn't call refresh; token not expiring soon → returns it unchanged, doesn't call refresh; token expiring soon + refresh succeeds → returns the new token AND calls `tokenManager.saveToken()` with it; token expiring soon + refresh throws → falls back to returning the current (soon-to-expire) token, doesn't crash, doesn't clear the token itself (that stays `onUnauthorized`'s job on the next request's real 401).

## 4. Out of scope

No change to `onUnauthorized`/`SessionExpiredSignal`'s existing reactive behavior — it remains the correct fallback for refresh failures and any 401/403 this proactive check doesn't catch. No change to the buffer being configurable per-environment (single default constant is enough for this fix). No change to `OfflineAppModule.kt`.
