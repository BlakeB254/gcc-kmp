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
