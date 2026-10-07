package com.gcc.shared.api

import com.gcc.shared.model.CompleteVolunteerRequest
import com.gcc.shared.model.VolunteerCleanupStatus
import com.gcc.shared.serialization.GccJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app used to POST completion to /api/spots/{id}/volunteer/complete,
 * which has no route (404 on every completion). The server completes a
 * volunteer cleanup on POST /api/spots/{id}/volunteer?complete
 * (site/src/app/api/spots/[id]/volunteer/route.ts).
 */
class VolunteerCleanupRouteTest {

    // Response body copied from the route's `?complete` branch.
    private val completeBody = """
        {"data":{"id":42,"reportId":42,"workerId":7,"beforeMediaUrls":[],"afterMediaUrls":["https://blob.example/a.jpg"],
         "trashHeapMediaUrl":null,"aiTrashAnalysis":null,"locationLat":41.88,"locationLng":-87.63,"locationVerified":true,
         "startedAt":"2026-10-07 09:00:00+00","completedAt":"2026-10-07T10:00:00.000Z","durationMinutes":0,"pdfUrl":null,
         "status":"completed","verificationNotes":null,"pointsAwarded":50,
         "createdAt":"2026-10-01 10:00:00+00","updatedAt":"2026-10-07T10:00:00.000Z"},"success":true,"message":null}
    """.trimIndent()

    @Test
    fun completePostsToVolunteerRouteWithCompleteFlag() = runBlocking {
        var seen: HttpRequestData? = null
        val engine = MockEngine { request ->
            seen = request
            respond(completeBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = GccApiClient(
            baseUrl = "https://api.example",
            httpClientOverride = HttpClient(engine) { install(ContentNegotiation) { json(GccJson) } },
        )

        val cleanup = client.completeVolunteerCleanup(42, CompleteVolunteerRequest(afterMediaUrls = listOf("https://blob.example/a.jpg")))

        val req = seen!!
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/api/spots/42/volunteer", req.url.encodedPath)
        assertTrue(req.url.parameters.contains("complete"), "missing ?complete flag: ${req.url}")
        assertEquals(VolunteerCleanupStatus.COMPLETED, cleanup.status)
        assertEquals(50, cleanup.pointsAwarded)
    }
}
