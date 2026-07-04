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
