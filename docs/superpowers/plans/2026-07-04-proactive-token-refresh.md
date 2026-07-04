# Proactive Token Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the mobile app refresh its JWT before it expires, instead of forcing a re-login on every expiry.

**Architecture:** A new `TokenManager.isTokenExpiringSoon()` method (buffer-based, proactive) plus a new, independently-testable `AuthTokenProvider` class that decides whether to refresh and does so, wired into `GccApiClient`'s existing per-request `tokenProvider` hook via a `lateinit var` self-referencing closure (breaks the circular dependency between the client and its own token-refresh call).

**Tech Stack:** Kotlin Multiplatform (`shared`/`composeApp` commonMain/commonTest), Koin DI.

## Global Constraints

- Confirmed via the real backend handler: the refresh endpoint (`site/src/app/api/auth/refresh/route.ts`) rejects an already-expired token outright (zero JWT clock tolerance) — refresh must be proactive (before expiry), not reactive (after a 401).
- `GccApiClient`'s `tokenProvider` lambda is invoked fresh on every request by deliberate design (avoids a prior Ktor Auth-plugin caching bug) — do not reintroduce Ktor's `Auth`/`bearer{}` plugin.
- On refresh failure, fall back to returning the current token unchanged — the existing `onUnauthorized` → clear token → `SessionExpiredSignal` reactive path must remain the fallback, unmodified.
- `OfflineAppModule.kt` (demo mode) is untouched — confirmed it never constructs a real `GccApiClient`.
- New `AuthRefreshResponse` fields (already exist, unchanged): `token: String`, `exp: Long` (`shared/src/commonMain/kotlin/com/gcc/shared/model/ApiTypes.kt:30-33`).

---

### Task 1: `TokenManager.isTokenExpiringSoon()`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/auth/TokenManager.kt`
- Test: `shared/src/commonTest/kotlin/com/gcc/shared/auth/TokenManagerTest.kt` (new file — no test file exists for this class today)

**Interfaces:**
- Produces: `TokenManager.isTokenExpiringSoon(bufferSeconds: Long = ONE_DAY_SECONDS): Boolean` — a regular member method (not an extension function — it needs access to the private `_token`/`getJwtClaim` members, same as the existing `isTokenExpired()`), added inside the `TokenManager` class body. Consumed by Task 2.

- [ ] **Step 1: Write the failing tests**

Create `shared/src/commonTest/kotlin/com/gcc/shared/auth/TokenManagerTest.kt`:

```kotlin
package com.gcc.shared.auth

import com.gcc.shared.offline.MemorySettings
import kotlinx.datetime.Clock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Builds a syntactically-valid (unsigned) JWT with only an "exp" claim -- TokenManager never verifies the signature client-side, only decodes+regex-extracts claims. */
@OptIn(ExperimentalEncodingApi::class)
private fun fakeJwt(expSeconds: Long): String {
    val payloadJson = """{"exp":$expSeconds}"""
    val standardB64 = Base64.encode(payloadJson.encodeToByteArray())
    val urlSafeB64 = standardB64.replace('+', '-').replace('/', '_').trimEnd('=')
    return "header.$urlSafeB64.signature"
}

class TokenManagerTest {

    @Test
    fun tokenWellWithinBufferIsExpiringSoon() {
        val nowSeconds = Clock.System.now().epochSeconds
        val settings = MemorySettings()
        val manager = TokenManager(settings)
        manager.saveToken(fakeJwt(expSeconds = nowSeconds + 60)) // expires in 1 minute

        assertTrue(manager.isTokenExpiringSoon(bufferSeconds = 3600)) // 1-hour buffer
    }

    @Test
    fun tokenComfortablyValidIsNotExpiringSoon() {
        val nowSeconds = Clock.System.now().epochSeconds
        val settings = MemorySettings()
        val manager = TokenManager(settings)
        manager.saveToken(fakeJwt(expSeconds = nowSeconds + 7 * 24 * 3600)) // expires in 7 days

        assertFalse(manager.isTokenExpiringSoon(bufferSeconds = 3600)) // 1-hour buffer
    }

    @Test
    fun alreadyExpiredTokenIsExpiringSoon() {
        val nowSeconds = Clock.System.now().epochSeconds
        val settings = MemorySettings()
        val manager = TokenManager(settings)
        manager.saveToken(fakeJwt(expSeconds = nowSeconds - 60)) // expired 1 minute ago

        assertTrue(manager.isTokenExpiringSoon(bufferSeconds = 3600))
    }

    @Test
    fun noTokenIsExpiringSoon() {
        val manager = TokenManager(MemorySettings())
        assertTrue(manager.isTokenExpiringSoon())
    }

    @Test
    fun defaultBufferIsOneDay() {
        val nowSeconds = Clock.System.now().epochSeconds
        val settings = MemorySettings()
        val manager = TokenManager(settings)
        // Expires in 12 hours -- within the 1-day default buffer.
        manager.saveToken(fakeJwt(expSeconds = nowSeconds + 12 * 3600))

        assertTrue(manager.isTokenExpiringSoon())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:jvmTest --tests "com.gcc.shared.auth.TokenManagerTest"`
Expected: FAIL — compile error, `Unresolved reference: isTokenExpiringSoon` (the method doesn't exist yet).

- [ ] **Step 3: Add `isTokenExpiringSoon()` to `TokenManager`**

In `shared/src/commonMain/kotlin/com/gcc/shared/auth/TokenManager.kt`, add this method right after the existing `isTokenExpired()` method:

```kotlin
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
```

And add the constant to the existing `companion object` block (alongside `KEY_AUTH_TOKEN` etc.):

```kotlin
        /** Default proactive-refresh buffer -- matches the server's 7-day token lifetime with room to spare. */
        const val ONE_DAY_SECONDS = 86_400L
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests "com.gcc.shared.auth.TokenManagerTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/auth/TokenManager.kt shared/src/commonTest/kotlin/com/gcc/shared/auth/TokenManagerTest.kt
git commit -m "Add TokenManager.isTokenExpiringSoon() for proactive refresh"
```

---

### Task 2: `AuthTokenProvider` + wire into `GccApiClient`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/auth/AuthTokenProvider.kt`
- Test: `shared/src/commonTest/kotlin/com/gcc/shared/auth/AuthTokenProviderTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/di/AppModule.kt`

**Interfaces:**
- Consumes: `TokenManager.isTokenExpiringSoon()` / `TokenManager.saveToken()` / `TokenManager.getToken()` (Task 1 and pre-existing), `AuthRefreshResponse(token: String, exp: Long)` (pre-existing, `shared/.../model/ApiTypes.kt:30-33`).
- Produces: `class AuthTokenProvider(tokenManager: TokenManager, refresh: suspend () -> AuthRefreshResponse) { suspend fun currentToken(): String? }` — consumed by `AppModule.kt`'s Koin wiring.

- [ ] **Step 1: Write the failing tests**

Create `shared/src/commonTest/kotlin/com/gcc/shared/auth/AuthTokenProviderTest.kt`:

```kotlin
package com.gcc.shared.auth

import com.gcc.shared.model.AuthRefreshResponse
import com.gcc.shared.offline.MemorySettings
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
private fun fakeJwt(expSeconds: Long): String {
    val payloadJson = """{"exp":$expSeconds}"""
    val standardB64 = Base64.encode(payloadJson.encodeToByteArray())
    val urlSafeB64 = standardB64.replace('+', '-').replace('/', '_').trimEnd('=')
    return "header.$urlSafeB64.signature"
}

class AuthTokenProviderTest {

    @Test
    fun noTokenReturnsNullWithoutCallingRefresh() = runTest {
        var refreshCalled = false
        val tokenManager = TokenManager(MemorySettings())
        val provider = AuthTokenProvider(tokenManager) {
            refreshCalled = true
            AuthRefreshResponse(token = "new", exp = 0)
        }

        val result = provider.currentToken()

        assertNull(result)
        assertEquals(false, refreshCalled)
    }

    @Test
    fun tokenNotExpiringSoonReturnsItUnchangedWithoutRefresh() = runTest {
        var refreshCalled = false
        val nowSeconds = Clock.System.now().epochSeconds
        val tokenManager = TokenManager(MemorySettings())
        val validToken = fakeJwt(expSeconds = nowSeconds + 7 * 24 * 3600)
        tokenManager.saveToken(validToken)
        val provider = AuthTokenProvider(tokenManager) {
            refreshCalled = true
            AuthRefreshResponse(token = "new", exp = 0)
        }

        val result = provider.currentToken()

        assertEquals(validToken, result)
        assertEquals(false, refreshCalled)
    }

    @Test
    fun tokenExpiringSoonRefreshesAndSavesTheNewToken() = runTest {
        val nowSeconds = Clock.System.now().epochSeconds
        val tokenManager = TokenManager(MemorySettings())
        tokenManager.saveToken(fakeJwt(expSeconds = nowSeconds + 60)) // expiring soon
        val newToken = fakeJwt(expSeconds = nowSeconds + 7 * 24 * 3600)
        val provider = AuthTokenProvider(tokenManager) {
            AuthRefreshResponse(token = newToken, exp = nowSeconds + 7 * 24 * 3600)
        }

        val result = provider.currentToken()

        assertEquals(newToken, result)
        assertEquals(newToken, tokenManager.getToken(), "the refreshed token must be persisted, not just returned")
    }

    @Test
    fun refreshFailureFallsBackToTheCurrentToken() = runTest {
        val nowSeconds = Clock.System.now().epochSeconds
        val tokenManager = TokenManager(MemorySettings())
        val expiringToken = fakeJwt(expSeconds = nowSeconds + 60)
        tokenManager.saveToken(expiringToken)
        val provider = AuthTokenProvider(tokenManager) {
            throw RuntimeException("network unreachable")
        }

        val result = provider.currentToken()

        assertEquals(expiringToken, result, "on refresh failure, fall back to the current token so the request proceeds and 401s normally")
        assertEquals(expiringToken, tokenManager.getToken(), "a failed refresh must not clear or alter the stored token -- onUnauthorized handles that on the next request's real 401")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:jvmTest --tests "com.gcc.shared.auth.AuthTokenProviderTest"`
Expected: FAIL — `Unresolved reference: AuthTokenProvider` (the class doesn't exist yet).

- [ ] **Step 3: Implement `AuthTokenProvider`**

Create `shared/src/commonMain/kotlin/com/gcc/shared/auth/AuthTokenProvider.kt`:

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests "com.gcc.shared.auth.AuthTokenProviderTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Wire `AuthTokenProvider` into `AppModule.kt`**

In `composeApp/src/commonMain/kotlin/com/gcc/app/di/AppModule.kt`, add the import (alongside the existing `com.gcc.shared.auth.TokenManager` import):

```kotlin
import com.gcc.shared.auth.AuthTokenProvider
```

Then change the existing `GccApiClient` Koin `single` block, from:

```kotlin
    single {
        val tokenManager = get<TokenManager>()
        GccApiClient(
            baseUrl = getApiBaseUrl(),
            tokenProvider = { tokenManager.getToken() },
            onUnauthorized = {
                tokenManager.clearToken()
                SessionExpiredSignal.fire()
            },
        )
```

to:

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
```

(Keep whatever closing syntax the existing block already has after this — check the real file, since the brief's excerpt above only shows the part that changes; do not alter anything after this point in the block, e.g. if there's a trailing `.also { ... }` or similar.)

`apiClient` must be the last expression of the `single { }` lambda block (Koin's `single { }` returns whatever the lambda's last line evaluates to) — confirm this is still true after your edit by checking what the block returns today (it likely already ends with the `GccApiClient(...)` call as the last expression; make sure `apiClient` (the variable) is what's returned now, not the `GccApiClient(...)` constructor call directly, since assignment expressions aren't yields in Kotlin).

- [ ] **Step 6: Verify the whole module still compiles**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/auth/AuthTokenProvider.kt shared/src/commonTest/kotlin/com/gcc/shared/auth/AuthTokenProviderTest.kt composeApp/src/commonMain/kotlin/com/gcc/app/di/AppModule.kt
git commit -m "Wire proactive token refresh into GccApiClient via AuthTokenProvider"
```

---

## Self-Review Notes

- **Spec coverage**: §2's `isTokenExpiringSoon()` → Task 1. §2's `AuthTokenProvider` + Koin wiring → Task 2. §3's test coverage (both files) → Task 1/2's test steps, matching every named scenario (missing token, comfortably valid, expiring soon + refresh succeeds, expiring soon + refresh fails) exactly. §4 (out of scope) — `OfflineAppModule.kt` untouched, `onUnauthorized` untouched.
- **Placeholder scan**: no TBD/TODO. Task 2 Step 5 has one explicit "confirm this yourself against the real file" instruction (the exact trailing syntax of the `single {}` block) rather than guessing at code the plan's author didn't fully re-read — this is a bounded, single-fact verification, not open-ended design work.
- **Type consistency**: `AuthTokenProvider(tokenManager: TokenManager, refresh: suspend () -> AuthRefreshResponse)` constructor and `currentToken(): String?` method are used identically in the test file (Task 2 Step 1) and the real wiring (Task 2 Step 5). `isTokenExpiringSoon(bufferSeconds: Long = ONE_DAY_SECONDS)` (Task 1) is called with an explicit buffer in most `TokenManagerTest` cases and the default in `defaultBufferIsOneDay`/throughout `AuthTokenProviderTest` — consistent with the produced signature.
- **Caught during self-review**: Task 1's Interfaces block originally described `isTokenExpiringSoon` with extension-function syntax (`fun TokenManager.isTokenExpiringSoon(...)`), inconsistent with Step 3's correct implementation as a real member method (required, since it reads private members). Fixed inline.
