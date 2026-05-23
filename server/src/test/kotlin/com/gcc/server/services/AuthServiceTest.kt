package com.gcc.server.services

import com.gcc.shared.model.LoginRequest
import com.gcc.shared.model.RegisterRequest
import com.gcc.shared.model.Worker
import com.gcc.shared.model.WorkerAddress
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    private fun makeService(initialWorkers: Map<String, Worker> = emptyMap(),
                            initialCreds: Map<String, Pair<String, String>> = emptyMap()) =
        AuthService(
            workerService = FakeWorkerService(initialWorkers, initialCreds),
            jwtSecret = "test-secret-only-used-in-tests",
            jwtIssuer = "gcc-test",
            jwtAudience = "gcc-test",
            adminEmailsEnv = null,
        )

    @Test
    fun `register throws when email is already registered`() = runTest {
        val existing = stubWorker(id = 1, email = "taken@example.com")
        val service = makeService(initialWorkers = mapOf("taken@example.com" to existing))

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
        val service = makeService()  // no credentials registered

        val ex = assertFailsWith<IllegalArgumentException> {
            service.login(LoginRequest(email = "nobody@example.com", password = "anything"))
        }
        // Same error message as wrong-password path — avoids leaking which is which to attackers.
        assertEquals("Invalid email or password", ex.message)
    }

    @Test
    fun `login throws on wrong password`() = runTest {
        // Salt + hash that won't match anything the test submits.
        val service = makeService(
            initialCreds = mapOf(
                "user@example.com" to ("some-salt-value" to "some-stored-hash-that-wont-match"),
            ),
        )

        val ex = assertFailsWith<IllegalArgumentException> {
            service.login(LoginRequest(email = "user@example.com", password = "wrong-password"))
        }
        assertEquals("Invalid email or password", ex.message)
    }
}

/** Minimal in-memory fake covering the WorkerService methods AuthService calls. */
private class FakeWorkerService(
    private val workersByEmail: Map<String, Worker>,
    private val credsByEmail: Map<String, Pair<String, String>>,
) : WorkerService() {

    override suspend fun getByEmail(email: String): Worker? = workersByEmail[email]

    override suspend fun getById(id: Int): Worker? =
        workersByEmail.values.firstOrNull { it.id == id }

    override suspend fun getAuthCredentials(email: String): Pair<String, String>? =
        credsByEmail[email]
}

private fun stubWorker(id: Int, email: String): Worker {
    val now = Clock.System.now()
    return Worker(
        id = id,
        name = "Test Worker",
        email = email,
        phone = "+15555550100",
        address = WorkerAddress(street = "", zip = ""),
        createdAt = now,
        updatedAt = now,
    )
}
