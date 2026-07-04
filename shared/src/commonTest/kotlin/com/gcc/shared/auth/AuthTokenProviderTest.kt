package com.gcc.shared.auth

import com.gcc.shared.model.AuthRefreshResponse
import com.gcc.shared.offline.MemorySettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        val provider = AuthTokenProvider(tokenManager) { _ ->
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
        val provider = AuthTokenProvider(tokenManager) { _ ->
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
        val provider = AuthTokenProvider(tokenManager) { _ ->
            AuthRefreshResponse(token = newToken, exp = nowSeconds + 7 * 24 * 3600)
        }

        val result = provider.currentToken()

        assertEquals(newToken, result)
        assertEquals(newToken, tokenManager.getToken(), "the refreshed token must be persisted, not just returned")
    }

    @Test
    fun refreshReceivesTheActualExpiringTokenAsItsArgument() = runTest {
        // The recursion-breaking design (see GccApiClient.refreshToken()'s doc
        // comment) hinges on refresh() being handed the current token
        // explicitly rather than reaching back through the tokenProvider hook.
        // This is the testable proxy for that: capture what refresh() actually
        // received and confirm it's the same token that was current beforehand.
        val nowSeconds = Clock.System.now().epochSeconds
        val tokenManager = TokenManager(MemorySettings())
        val expiringToken = fakeJwt(expSeconds = nowSeconds + 60)
        tokenManager.saveToken(expiringToken)
        val newToken = fakeJwt(expSeconds = nowSeconds + 7 * 24 * 3600)
        var receivedToken: String? = null
        val provider = AuthTokenProvider(tokenManager) { current ->
            receivedToken = current
            AuthRefreshResponse(token = newToken, exp = nowSeconds + 7 * 24 * 3600)
        }

        provider.currentToken()

        assertEquals(expiringToken, receivedToken, "refresh() must receive the current (pre-refresh) token explicitly")
    }

    @Test
    fun refreshFailureFallsBackToTheCurrentToken() = runTest {
        val nowSeconds = Clock.System.now().epochSeconds
        val tokenManager = TokenManager(MemorySettings())
        val expiringToken = fakeJwt(expSeconds = nowSeconds + 60)
        tokenManager.saveToken(expiringToken)
        val provider = AuthTokenProvider(tokenManager) { _ ->
            throw RuntimeException("network unreachable")
        }

        val result = provider.currentToken()

        assertEquals(expiringToken, result, "on refresh failure, fall back to the current token so the request proceeds and 401s normally")
        assertEquals(expiringToken, tokenManager.getToken(), "a failed refresh must not clear or alter the stored token -- onUnauthorized handles that on the next request's real 401")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentCurrentTokenCallsResultInOnlyOneRefreshCall() = runTest {
        // Mirrors CoverageEventQueueTest.concurrentFlushCallsResultInOnlyOneSubmitCall:
        // gate the in-flight refresh so two concurrent currentToken() calls
        // genuinely overlap, then confirm only one network call happened.
        val nowSeconds = Clock.System.now().epochSeconds
        val tokenManager = TokenManager(MemorySettings())
        val expiringToken = fakeJwt(expSeconds = nowSeconds + 60)
        tokenManager.saveToken(expiringToken)
        val newToken = fakeJwt(expSeconds = nowSeconds + 7 * 24 * 3600)
        var callCount = 0
        val gate = CompletableDeferred<Unit>()
        val provider = AuthTokenProvider(tokenManager) { _ ->
            callCount++
            gate.await()
            AuthRefreshResponse(token = newToken, exp = nowSeconds + 7 * 24 * 3600)
        }

        // Launch two currentToken() calls without awaiting the first, so they overlap.
        val firstCall = async { provider.currentToken() }
        runCurrent() // drive the first call up through refresh(), where it suspends on the gate

        val secondCall = async { provider.currentToken() }
        runCurrent() // drive the second call as far as it can go while the first is still in-flight

        // Nothing can complete refresh() until the gate opens.
        gate.complete(Unit)
        advanceUntilIdle()

        val firstResult = firstCall.await()
        val secondResult = secondCall.await()

        assertEquals(1, callCount, "refresh() must be invoked exactly once despite two concurrent currentToken() calls")
        assertEquals(newToken, firstResult)
        assertEquals(newToken, secondResult)
    }
}
