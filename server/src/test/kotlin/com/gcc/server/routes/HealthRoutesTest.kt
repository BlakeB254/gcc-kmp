package com.gcc.server.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * First server test — establishes the testApplication harness pattern.
 *
 * The health route doesn't depend on the database, Koin, or JWT, so it's the
 * cheapest place to anchor the test infrastructure. Future tests that need
 * services can mirror the install/routing layout in [testModule] and
 * additionally install the relevant plugins (configureAuthentication,
 * configureDI with a test Koin module, etc.).
 */
class HealthRoutesTest {

    @Test
    fun `GET api health returns 200 with expected payload`() = testApplication {
        application { testModule() }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "\"status\":\"ok\"")
        assertContains(body, "\"service\":\"gcc-api\"")
    }
}

private fun Application.testModule() {
    install(ContentNegotiation) { json() }
    routing { healthRoutes() }
}
