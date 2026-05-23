package com.gcc.server.services

import com.gcc.server.support.makeAuthService
import com.gcc.server.support.stubWorker
import com.gcc.shared.model.LoginRequest
import com.gcc.shared.model.RegisterRequest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Failure-path unit tests for AuthService. These don't touch the database
 * because they exercise paths that short-circuit before the dbQuery call:
 * duplicate-email registration, non-existent login, wrong-password login.
 *
 * The happy-path tests (successful register + login) would need either a
 * test DB (Testcontainers Postgres) or a refactor of the dbQuery indirection;
 * left as a follow-up. Failure paths are also the security-critical surface
 * (no one wants brute-force or account-takeover regressions) so they're the
 * right thing to lock down first.
 */
class AuthServiceTest {

    @Test
    fun `register throws when email is already registered`() = runTest {
        val existing = stubWorker(id = 1, email = "taken@example.com")
        val service = makeAuthService(workers = mapOf("taken@example.com" to existing))

        val ex = assertFailsWith<IllegalArgumentException> {
            service.register(
                RegisterRequest(
                    name = "New Person",
                    email = "taken@example.com",
                    phone = "+15555550101",
                    password = "doesnt-matter",
                ),
            )
        }
        assertEquals("Email already registered", ex.message)
    }

    @Test
    fun `login throws when email is not registered`() = runTest {
        val service = makeAuthService()  // no credentials registered

        val ex = assertFailsWith<IllegalArgumentException> {
            service.login(LoginRequest(email = "nobody@example.com", password = "anything"))
        }
        // Same error message as wrong-password path — avoids leaking which is which to attackers.
        assertEquals("Invalid email or password", ex.message)
    }

    @Test
    fun `login throws on wrong password`() = runTest {
        // Salt + hash that won't match anything the test submits.
        val service = makeAuthService(
            creds = mapOf(
                "user@example.com" to ("some-salt-value" to "some-stored-hash-that-wont-match"),
            ),
        )

        val ex = assertFailsWith<IllegalArgumentException> {
            service.login(LoginRequest(email = "user@example.com", password = "wrong-password"))
        }
        assertEquals("Invalid email or password", ex.message)
    }

    @Test
    fun `login with correct password issues a 2FA challenge`() = runTest {
        // Replicate the production hash algorithm so we can seed a valid
        // (salt, storedHash) pair for a known password. Production:
        //   PBKDF2WithHmacSHA512, 100_000 iterations, 64-byte output, hex.
        val salt = "abc123-test-salt"
        val password = "correct-horse-battery-staple"
        val storedHash = pbkdf2Hex(password, salt)
        val worker = stubWorker(id = 42, email = "ok@example.com")
        val service = makeAuthService(
            workers = mapOf("ok@example.com" to worker),
            creds = mapOf("ok@example.com" to (salt to storedHash)),
        )

        val response = service.login(LoginRequest(email = "ok@example.com", password = password))

        // Non-demo accounts get a 2FA challenge, not a JWT. The route layer
        // then redirects the client to enter the code.
        assertTrue(response.requiresTwoFactor, "expected 2FA challenge")
        assertNotNull(response.twoFactorToken)
        assertTrue(
            response.twoFactorToken!!.isNotBlank(),
            "twoFactorToken should be a UUID-like string",
        )
    }

    @Test
    fun `verify2FA throws on unknown token`() = runTest {
        val service = makeAuthService()

        val ex = assertFailsWith<IllegalArgumentException> {
            service.verify2FA(twoFactorToken = "this-token-was-never-issued", code = "000000")
        }
        assertEquals("Invalid or expired verification token", ex.message)
    }
}

private fun pbkdf2Hex(password: String, salt: String): String {
    val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(), 100_000, 512)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    return factory.generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
}
