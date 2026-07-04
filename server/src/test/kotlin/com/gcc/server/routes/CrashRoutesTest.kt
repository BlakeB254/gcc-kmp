package com.gcc.server.routes

import com.gcc.server.services.CrashReportService
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Route-level test for /api/crashes. Only covers request-validation failure
 * (missing required field) -- this is verifiable without a real database,
 * since call.receive<T>() throws before CrashReportService.record() is ever
 * invoked. A happy-path test that actually inserts a row would need a real
 * Postgres instance (Testcontainers), which this codebase doesn't have set
 * up yet -- same limitation AuthRoutesTest.kt documents for its own
 * DB-touching paths.
 */
class CrashRoutesTest {

    @AfterTest
    fun stopKoin() {
        if (GlobalContext.getOrNull() != null) GlobalContext.stopKoin()
    }

    @Test
    fun `POST api crashes with missing required field does not succeed`() = testApplication {
        application { testModule() }

        val response = client.post("/api/crashes") {
            contentType(ContentType.Application.Json)
            // Missing "exceptionMessage" and "occurredAt" -- required fields.
            setBody("""{"deviceId":"abc123","platform":"android","stackTrace":"boom"}""")
        }

        assertFalse(response.status == HttpStatusCode.OK, "expected a non-success status, got ${response.status}")
    }
}

private fun Application.testModule() {
    install(Koin) {
        modules(module { single { CrashReportService() } })
    }
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError)
        }
    }
    routing { crashRoutes() }
}
