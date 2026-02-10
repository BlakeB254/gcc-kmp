package com.gcc.shared.demo

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

/**
 * Helper: wrap [dataJson] in the standard `{"data":...,"success":true}` envelope.
 */
private fun MockRequestHandleScope.respondApi(dataJson: String) =
    respond(
        content = """{"data":$dataJson,"success":true}""",
        status = HttpStatusCode.OK,
        headers = jsonHeaders,
    )

private fun MockRequestHandleScope.respondNotFound() =
    respond(
        content = """{"data":null,"success":false,"message":"Demo: endpoint not mocked"}""",
        status = HttpStatusCode.NotFound,
        headers = jsonHeaders,
    )

// Regex patterns for parameterized paths
private val routeIdPattern = Regex("^/api/routes/(\\d+)$")
private val jobActionPattern = Regex("^/api/jobs/(\\d+)/(claim|start|complete)$")
private val jobIdPattern = Regex("^/api/jobs/(\\d+)$")
private val bountyClaimPattern = Regex("^/api/bounties/(\\d+)/claim$")
private val bountyPledgePattern = Regex("^/api/bounties/(\\d+)/pledge$")
private val bountyIdPattern = Regex("^/api/bounties/(\\d+)$")
private val spotVolunteerPattern = Regex("^/api/spots/(\\d+)/volunteer$")

/**
 * Creates a Ktor [MockEngine] that returns canned demo data for every API
 * endpoint the app uses. No network calls are made.
 *
 * Responses are wrapped in `ApiResponse`-shaped JSON (`{"data":...,"success":true}`).
 */
fun createDemoHttpEngine(): MockEngine = MockEngine { request ->
    val path = request.url.encodedPath

    when {
        // ── Auth ────────────────────────────────────────────────
        path == "/api/auth/login" || path == "/api/auth/register" ->
            respondApi(json.encodeToString(DemoData.demoAuthResponse))

        // ── Worker (specific first) ────────────────────────────
        path == "/api/workers/me/service-area" ->
            respondApi(json.encodeToString(DemoData.demoServiceArea))

        path == "/api/workers/me" ->
            respondApi(json.encodeToString(DemoData.demoWorker))

        // ── Routes ─────────────────────────────────────────────
        path == "/api/routes" ->
            respondApi(json.encodeToString(DemoData.demoRoutes))

        routeIdPattern.matches(path) -> {
            val id = routeIdPattern.find(path)!!.groupValues[1].toInt()
            val route = DemoData.demoRoutes.find { it.id == id }
            if (route != null) respondApi(json.encodeToString(route))
            else respondNotFound()
        }

        // ── Jobs (actions first) ───────────────────────────────
        jobActionPattern.matches(path) -> {
            val match = jobActionPattern.find(path)!!
            val action = match.groupValues[2]
            val job = when (action) {
                "claim" -> DemoData.demoActiveJob.copy(status = JobStatus.CLAIMED)
                "start" -> DemoData.demoActiveJob.copy(status = JobStatus.IN_PROGRESS)
                "complete" -> DemoData.demoActiveJob.copy(status = JobStatus.COMPLETED)
                else -> DemoData.demoActiveJob
            }
            respondApi(json.encodeToString(job))
        }

        jobIdPattern.matches(path) ->
            respondApi(json.encodeToString(DemoData.demoActiveJob))

        path == "/api/jobs" ->
            respondApi(json.encodeToString(listOf(DemoData.demoActiveJob)))

        // ── Leaderboard ────────────────────────────────────────
        path == "/api/leaderboard" ->
            respondApi(json.encodeToString(DemoData.demoLeaderboard))

        // ── Feed ───────────────────────────────────────────────
        path == "/api/feed" ->
            respondApi(json.encodeToString(DemoData.demoFeedItems))

        // ── Bounties (actions first) ───────────────────────────
        bountyClaimPattern.matches(path) -> {
            val id = bountyClaimPattern.find(path)!!.groupValues[1].toInt()
            val bounty = DemoData.demoBounties.find { it.id == id } ?: DemoData.demoBounties.first()
            respondApi(json.encodeToString(bounty.copy(status = BountyStatus.CLAIMED, claimedBy = 1)))
        }

        bountyPledgePattern.matches(path) -> {
            val id = bountyPledgePattern.find(path)!!.groupValues[1].toInt()
            val bounty = DemoData.demoBounties.find { it.id == id } ?: DemoData.demoBounties.first()
            respondApi(json.encodeToString(bounty))
        }

        bountyIdPattern.matches(path) -> {
            val id = bountyIdPattern.find(path)!!.groupValues[1].toInt()
            val bounty = DemoData.demoBounties.find { it.id == id }
            if (bounty != null) respondApi(json.encodeToString(bounty))
            else respondNotFound()
        }

        path == "/api/bounties" ->
            respondApi(json.encodeToString(DemoData.demoBounties))

        // ── Wallet ─────────────────────────────────────────────
        path == "/api/wallet/transactions" ->
            respondApi(json.encodeToString(DemoData.demoTransactions))

        path == "/api/wallet/deposit" || path == "/api/wallet/allocate" ->
            respondApi(json.encodeToString(DemoData.demoWallet))

        path == "/api/wallet" ->
            respondApi(json.encodeToString(DemoData.demoWallet))

        // ── Gamification ───────────────────────────────────────
        path == "/api/gamification/me/badges" ->
            respondApi(json.encodeToString(DemoData.demoBadges))

        // ── Spots / Volunteer ──────────────────────────────────
        spotVolunteerPattern.matches(path) ->
            respondApi(json.encodeToString(DemoData.demoVolunteerCleanup))

        path == "/api/spots" ->
            respondApi(json.encodeToString(DemoData.demoSpotResponse))

        // ── Stats ──────────────────────────────────────────────
        path == "/api/stats" ->
            respondApi(json.encodeToString(DemoData.demoStats))

        // ── Fallback ───────────────────────────────────────────
        else -> respondNotFound()
    }
}

/**
 * Creates a fully-configured [GccApiClient] backed by the demo [MockEngine].
 * Call this from the DI module — no ktor-client-mock imports needed on the consumer side.
 */
fun createDemoApiClient(): GccApiClient {
    val mockClient = HttpClient(createDemoHttpEngine()) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            })
        }
    }
    return GccApiClient(
        baseUrl = "http://demo",
        tokenProvider = { DemoData.demoToken },
        httpClientOverride = mockClient,
    )
}
