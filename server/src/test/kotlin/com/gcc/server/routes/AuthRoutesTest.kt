package com.gcc.server.routes

import com.gcc.server.plugins.configureRateLimiting
import com.gcc.server.services.AuthService
import com.gcc.server.services.WorkerService
import com.gcc.shared.model.Worker
import com.gcc.shared.model.WorkerAddress
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Route-level integration tests for /api/auth.
 *
 * Wires a real AuthService backed by an in-memory FakeWorkerService through
 * a real Koin module. This is the pattern other route-level tests should
 * copy when adding coverage for /api/jobs, /api/workers, /api/wallet, etc.
 *
 * Doesn't exercise the database — the WorkerService methods AuthService
 * needs are overridden with simple in-memory lookups. Happy-path register
 * (which writes via dbQuery) is left for a Testcontainers-backed suite.
 */
class AuthRoutesTest {

    @AfterTest
    fun stopKoin() {
        // Koin is global state — leaking between tests causes "already-started"
        // errors on the next testApplication.
        if (GlobalContext.getOrNull() != null) GlobalContext.stopKoin()
    }

    @Test
    fun `POST api auth login with unknown email returns 401`() = testApplication {
        application { testModule(makeAuthService(workers = emptyMap(), creds = emptyMap())) }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"nobody@example.com","password":"whatever"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        // ErrorResponse body shape: {"error": "...", "code": 401}
        val body = response.bodyAsText()
        assertTrue(
            body.contains("Invalid email or password"),
            "expected generic auth-failed message, got: $body",
        )
    }

    @Test
    fun `POST api auth login with wrong password returns 401`() = testApplication {
        val worker = stubWorker(id = 7, email = "u@example.com")
        application {
            testModule(makeAuthService(
                workers = mapOf("u@example.com" to worker),
                creds = mapOf("u@example.com" to ("some-salt" to "stored-hash-that-wont-match")),
            ))
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"u@example.com","password":"wrong"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST api auth verify-2fa with unknown token returns 401`() = testApplication {
        application { testModule(makeAuthService(workers = emptyMap(), creds = emptyMap())) }

        val response = client.post("/api/auth/verify-2fa") {
            contentType(ContentType.Application.Json)
            setBody("""{"twoFactorToken":"never-issued","code":"000000"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Test harness — application wiring + fakes
// ───────────────────────────────────────────────────────────────────────────

private fun Application.testModule(authService: AuthService) {
    install(Koin) {
        modules(module { single { authService } })
    }
    install(ContentNegotiation) { json() }
    configureRateLimiting()
    routing { authRoutes() }
}

private fun makeAuthService(
    workers: Map<String, Worker>,
    creds: Map<String, Pair<String, String>>,
): AuthService =
    AuthService(
        workerService = FakeWorkerService(workers, creds),
        jwtSecret = "test-secret-not-prod",
        jwtIssuer = "gcc-test",
        jwtAudience = "gcc-test",
        adminEmailsEnv = null,
    )

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
        id = id, name = "Test", email = email, phone = "+15555550100",
        address = WorkerAddress(street = "", zip = ""),
        createdAt = now, updatedAt = now,
    )
}
