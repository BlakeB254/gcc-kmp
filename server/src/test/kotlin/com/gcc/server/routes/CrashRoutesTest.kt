package com.gcc.server.routes

import com.gcc.server.services.CrashReportService
import com.gcc.shared.model.CrashReportRequest
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
 * Sentinel message thrown by [SpyCrashReportService.record] -- if this text
 * ever shows up in an HTTP response body, it proves record() actually ran.
 */
private const val RECORD_INVOKED_SENTINEL = "SENTINEL_CRASH_REPORT_SERVICE_RECORD_WAS_INVOKED"

/**
 * Test-only spy that stands in for the real CrashReportService (which needs
 * a live database via dbQuery/newSuspendedTransaction). record() always
 * throws a recognizable sentinel error so tests can prove -- by asserting
 * the sentinel is ABSENT from the response -- that the service was never
 * reached, rather than merely asserting "some non-200 came back" (which
 * would also be true if the service blew up for an unrelated reason, e.g.
 * no test database).
 */
private class SpyCrashReportService : CrashReportService() {
    override suspend fun record(request: CrashReportRequest) {
        throw IllegalStateException(RECORD_INVOKED_SENTINEL)
    }
}

/**
 * Route-level test for /api/crashes. Only covers request-validation failure
 * (missing required field) -- this is verifiable without a real database,
 * since call.receive<T>() throws before CrashReportService.record() is ever
 * invoked. A happy-path test that actually inserts a row would need a real
 * Postgres instance (Testcontainers), which this codebase doesn't have set
 * up yet -- same limitation AuthRoutesTest.kt documents for its own
 * DB-touching paths.
 *
 * The Koin module below registers SpyCrashReportService instead of a real
 * CrashReportService so that IF request validation were ever loosened (e.g.
 * exceptionMessage/occurredAt made nullable) and the malformed body slipped
 * past deserialization, the test would fail loudly: record() would throw
 * the sentinel, StatusPages would surface it in the response body, and the
 * assertion below would catch it. A bare "status != 200" assertion can't
 * tell "rejected before reaching the service" apart from "the service
 * blew up for some other reason" -- this can.
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

        val body = response.bodyAsText()
        assertFalse(
            body.contains(RECORD_INVOKED_SENTINEL),
            "response contained the record()-was-invoked sentinel, meaning the malformed " +
                "request reached CrashReportService.record() instead of being rejected at " +
                "deserialization -- request validation regressed. Body: $body",
        )
    }
}

private fun Application.testModule() {
    install(Koin) {
        modules(module { single<CrashReportService> { SpyCrashReportService() } })
    }
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError, cause.message ?: "")
        }
    }
    routing { crashRoutes() }
}
