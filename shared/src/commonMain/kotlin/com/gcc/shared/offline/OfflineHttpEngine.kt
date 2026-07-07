package com.gcc.shared.offline

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.encodeToString
import com.gcc.shared.serialization.GccJson

private val json = GccJson

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
        content = """{"data":null,"success":false,"message":"Offline: endpoint not mocked"}""",
        status = HttpStatusCode.NotFound,
        headers = jsonHeaders,
    )

private fun RouteStatus.apiValue(): String = when (this) {
    RouteStatus.DRAFT -> "draft"
    RouteStatus.PUBLISHED -> "published"
    RouteStatus.ARCHIVED -> "archived"
}

private fun JobStatus.apiValue(): String = when (this) {
    JobStatus.AVAILABLE -> "available"
    JobStatus.CLAIMED -> "claimed"
    JobStatus.IN_PROGRESS -> "in_progress"
    JobStatus.PENDING_REVIEW -> "pending_review"
    JobStatus.COMPLETED -> "completed"
    JobStatus.DISPUTED -> "disputed"
}

// Regex patterns for parameterized paths
private val routeIdPattern = Regex("^/api/routes/(\\d+)$")
private val jobActionPattern = Regex("^/api/jobs/(\\d+)/(claim|start|complete)$")
private val jobEvidencePattern = Regex("^/api/jobs/(\\d+)/evidence$")
private val jobIdPattern = Regex("^/api/jobs/(\\d+)$")
private val bountyClaimPattern = Regex("^/api/bounties/(\\d+)/claim$")
private val bountyPledgePattern = Regex("^/api/bounties/(\\d+)/pledge$")
private val bountyIdPattern = Regex("^/api/bounties/(\\d+)$")
private val spotVolunteerPattern = Regex("^/api/spots/(\\d+)/volunteer$")
private val blockIdPattern = Regex("^/api/blocks/(\\d+)$")
private val adminWorkerIdPattern = Regex("^/api/admin/workers/(\\d+)$")
private val adminWorkerActionPattern = Regex("^/api/admin/workers/(\\d+)/(verify|role)$")
private val adminRouteIdPattern = Regex("^/api/admin/routes/(\\d+)$")
private val adminBlockIdPattern = Regex("^/api/admin/blocks/(\\d+)$")
private val adminBountyActionPattern = Regex("^/api/admin/bounties/(\\d+)/(close|expire)$")
private val adminDisputeActionPattern = Regex("^/api/admin/disputes/(\\d+)/resolve$")
private val adminJobStatusPattern = Regex("^/api/admin/jobs/(\\d+)/status$")
private val bountyAcceptPattern = Regex("^/api/bounties/(\\d+)/accept$")
private val spotVolunteerCompletePattern = Regex("^/api/spots/(\\d+)/volunteer/(\\d+)/complete$")
private val adminSponsorIdPattern = Regex("^/api/admin/sponsors/(\\d+)$")

/**
 * Creates a Ktor [MockEngine] that returns canned seed data for every API
 * endpoint the app uses. No network calls are made.
 *
 * Responses are wrapped in `ApiResponse`-shaped JSON (`{"data":...,"success":true}`).
 */
fun createOfflineHttpEngine(): MockEngine = MockEngine { request ->
    val path = request.url.encodedPath

    when {
        // ── Auth ────────────────────────────────────────────────
        path == "/api/auth/login" || path == "/api/auth/register" ->
            respondApi(json.encodeToString(SeedData.authResponse))

        // ── Worker (specific first) ────────────────────────────
        path == "/api/workers/me/service-area" ->
            respondApi(json.encodeToString(SeedData.serviceArea))

        path == "/api/workers/me" ->
            respondApi(json.encodeToString(SeedData.worker))

        path == "/api/workers/me/jobs" ->
            respondApi(json.encodeToString(listOf(SeedData.activeJob)))

        // ── Routes ─────────────────────────────────────────────
        path == "/api/routes" -> {
            val status = request.url.parameters["status"] ?: "published"
            val routes = if (status == "all") {
                SeedData.routes
            } else {
                SeedData.routes.filter { it.status.apiValue() == status }
            }
            respondApi(json.encodeToString(routes))
        }

        routeIdPattern.matches(path) -> {
            val id = routeIdPattern.find(path)!!.groupValues[1].toInt()
            val route = SeedData.routes.find { it.id == id }
            if (route != null) respondApi(json.encodeToString(route))
            else respondNotFound()
        }

        // ── Jobs (actions first) ───────────────────────────────
        jobActionPattern.matches(path) -> {
            val match = jobActionPattern.find(path)!!
            val action = match.groupValues[2]
            val job = when (action) {
                "claim" -> SeedData.activeJob.copy(status = JobStatus.CLAIMED)
                "start" -> SeedData.activeJob.copy(status = JobStatus.IN_PROGRESS)
                "complete" -> SeedData.activeJob.copy(status = JobStatus.COMPLETED)
                else -> SeedData.activeJob
            }
            respondApi(json.encodeToString(job))
        }

        jobEvidencePattern.matches(path) ->
            respondApi(json.encodeToString(SeedData.activeJob))

        jobIdPattern.matches(path) ->
            respondApi(json.encodeToString(SeedData.activeJob))

        path == "/api/jobs" -> {
            val routeId = request.url.parameters["route_id"]?.toIntOrNull()
            val status = request.url.parameters["status"]
            val jobs = listOf(SeedData.activeJob).filter { job ->
                (routeId == null || job.routeId == routeId) &&
                    (status == null || job.status.apiValue() == status)
            }
            respondApi(json.encodeToString(jobs))
        }

        // ── Leaderboard ────────────────────────────────────────
        path == "/api/leaderboard" ->
            respondApi(json.encodeToString(SeedData.leaderboard))

        // ── Feed ───────────────────────────────────────────────
        path == "/api/feed" ->
            respondApi(json.encodeToString(SeedData.feedItems))

        // ── Bounties (actions first) ───────────────────────────
        bountyAcceptPattern.matches(path) -> {
            val id = bountyAcceptPattern.find(path)!!.groupValues[1].toInt()
            val bounty = SeedData.bounties.find { it.id == id } ?: SeedData.bounties.first()
            respondApi(json.encodeToString(bounty.copy(status = BountyStatus.CLAIMED, claimedBy = 1)))
        }

        bountyClaimPattern.matches(path) -> {
            val id = bountyClaimPattern.find(path)!!.groupValues[1].toInt()
            val bounty = SeedData.bounties.find { it.id == id } ?: SeedData.bounties.first()
            respondApi(json.encodeToString(bounty.copy(status = BountyStatus.CLAIMED, claimedBy = 1)))
        }

        bountyPledgePattern.matches(path) -> {
            val id = bountyPledgePattern.find(path)!!.groupValues[1].toInt()
            val bounty = SeedData.bounties.find { it.id == id } ?: SeedData.bounties.first()
            respondApi(json.encodeToString(bounty))
        }

        bountyIdPattern.matches(path) -> {
            val id = bountyIdPattern.find(path)!!.groupValues[1].toInt()
            val bounty = SeedData.bounties.find { it.id == id }
            if (bounty != null) respondApi(json.encodeToString(bounty))
            else respondNotFound()
        }

        path == "/api/bounties" ->
            respondApi(json.encodeToString(SeedData.bounties))

        // ── Wallet ─────────────────────────────────────────────
        path == "/api/wallet/transactions" ->
            respondApi(json.encodeToString(SeedData.transactions))

        path == "/api/wallet/deposit" || path == "/api/wallet/allocate" ->
            respondApi(json.encodeToString(SeedData.wallet))

        path == "/api/wallet" ->
            respondApi(json.encodeToString(SeedData.wallet))

        // ── Gamification ───────────────────────────────────────
        path == "/api/gamification/me/badges" ->
            respondApi(json.encodeToString(SeedData.badges))

        // ── Spots / Volunteer ──────────────────────────────────
        spotVolunteerCompletePattern.matches(path) ->
            respondApi(json.encodeToString(SeedData.volunteerCleanup))

        spotVolunteerPattern.matches(path) ->
            respondApi(json.encodeToString(SeedData.volunteerCleanup))

        path == "/api/spots" ->
            respondApi(json.encodeToString(SeedData.spotResponse))

        // ── Blocks ────────────────────────────────────────────────
        blockIdPattern.matches(path) -> {
            val id = blockIdPattern.find(path)!!.groupValues[1].toInt()
            val block = SeedData.blocks.find { it.id == id }
            if (block != null) respondApi(json.encodeToString(block))
            else respondNotFound()
        }

        path == "/api/blocks" ->
            respondApi(json.encodeToString(SeedData.blocks))

        // ── Stats ──────────────────────────────────────────────
        path == "/api/stats" ->
            respondApi(json.encodeToString(SeedData.stats))

        // ── Admin Endpoints ──────────────────────────────────────
        path == "/api/admin/analytics/stats" ->
            respondApi(json.encodeToString(SeedData.adminStats))

        path == "/api/admin/analytics/activity" ->
            respondApi(json.encodeToString(SeedData.adminActivity))

        adminWorkerActionPattern.matches(path) -> {
            val id = adminWorkerActionPattern.find(path)!!.groupValues[1].toInt()
            val w = SeedData.workerList.find { it.id == id } ?: SeedData.worker
            respondApi(json.encodeToString(w))
        }

        adminWorkerIdPattern.matches(path) -> {
            val id = adminWorkerIdPattern.find(path)!!.groupValues[1].toInt()
            val w = SeedData.workerList.find { it.id == id }
            if (w != null) respondApi(json.encodeToString(w))
            else respondNotFound()
        }

        path == "/api/admin/workers" || path == "/api/admin/workers/bulk-verify" ->
            respondApi(json.encodeToString(SeedData.workerList))

        path == "/api/admin/routes" ->
            respondApi(json.encodeToString(SeedData.routes))

        path == "/api/admin/blocks" ->
            respondApi(json.encodeToString(SeedData.blocks))

        path == "/api/admin/bounties" ->
            respondApi(json.encodeToString(SeedData.bounties))

        // ── Admin Analytics (expanded) ──────────────────────────────
        path == "/api/admin/analytics/daily" ->
            respondApi(json.encodeToString(SeedData.dailyStats))

        // ── Admin Disputes ──────────────────────────────────────────
        adminDisputeActionPattern.matches(path) ->
            respondApi(json.encodeToString(SeedData.disputeList.first()))

        // ── Admin Route mutations ───────────────────────────────────
        adminRouteIdPattern.matches(path) -> {
            val id = adminRouteIdPattern.find(path)!!.groupValues[1].toInt()
            val route = SeedData.routes.find { it.id == id }
            if (route != null) respondApi(json.encodeToString(route))
            else respondNotFound()
        }

        // ── Admin Block mutations ───────────────────────────────────
        adminBlockIdPattern.matches(path) -> {
            val id = adminBlockIdPattern.find(path)!!.groupValues[1].toInt()
            val block = SeedData.blocks.find { it.id == id }
            if (block != null) respondApi(json.encodeToString(block))
            else respondNotFound()
        }

        // ── Admin Bounty actions ────────────────────────────────────
        adminBountyActionPattern.matches(path) -> {
            val id = adminBountyActionPattern.find(path)!!.groupValues[1].toInt()
            val bounty = SeedData.bounties.find { it.id == id } ?: SeedData.bounties.first()
            respondApi(json.encodeToString(bounty.copy(status = BountyStatus.CANCELLED)))
        }

        // ── Admin Job status ────────────────────────────────────────
        adminJobStatusPattern.matches(path) ->
            respondApi(json.encodeToString(SeedData.activeJob))

        // ── Admin Sponsor detail ────────────────────────────────────
        adminSponsorIdPattern.matches(path) -> {
            val id = adminSponsorIdPattern.find(path)!!.groupValues[1].toInt()
            val sponsor = SeedData.sponsorList.find { it.id == id }
            if (sponsor != null) respondApi(json.encodeToString(sponsor))
            else respondNotFound()
        }

        path.startsWith("/api/admin/") ->
            respondApi("""[]""")

        // ── Fallback ───────────────────────────────────────────
        else -> respondNotFound()
    }
}

/**
 * Creates a fully-configured [GccApiClient] backed by the offline [MockEngine].
 * Call this from the DI module — no ktor-client-mock imports needed on the consumer side.
 */
fun createOfflineApiClient(): GccApiClient {
    val mockClient = HttpClient(createOfflineHttpEngine()) {
        install(ContentNegotiation) {
            json(GccJson)
        }
    }
    return GccApiClient(
        baseUrl = "http://offline",
        tokenProvider = { SeedData.offlineToken },
        httpClientOverride = mockClient,
    )
}
