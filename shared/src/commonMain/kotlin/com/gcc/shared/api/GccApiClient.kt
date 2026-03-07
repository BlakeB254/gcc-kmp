package com.gcc.shared.api

import com.gcc.shared.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.*
import com.gcc.shared.serialization.GccJson

class GccApiClient(
    val baseUrl: String,
    private val tokenProvider: suspend () -> String? = { null },
    httpClientOverride: HttpClient? = null,
) {
    val json = GccJson

    val httpClient = httpClientOverride ?: HttpClient {
        expectSuccess = false // Don't throw on non-2xx; let callers inspect status
        install(ContentNegotiation) {
            json(this@GccApiClient.json)
        }
        install(Auth) {
            bearer {
                loadTokens {
                    tokenProvider()?.let { BearerTokens(it, "") }
                }
            }
        }
    }

    // ── HTTP Helpers ───────────────────────────────────────────────────

    /** GET [path] and unwrap ApiResponse<T>.data */
    private suspend inline fun <reified T> get(
        path: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response = httpClient.get("$baseUrl$path", block)
        return response.body<ApiResponse<T>>().data
    }

    /** GET [path], returning ApiResult.Success on success or ApiResult.Error on failure. */
    private suspend inline fun <reified T> safeGet(
        path: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): ApiResult<T> = try {
        ApiResult.Success(get(path, block))
    } catch (e: Exception) {
        ApiResult.Error(e.message ?: "Unknown error")
    }

    /** POST [path] with JSON [body] and unwrap ApiResponse<R>.data */
    private suspend inline fun <reified R> postJson(
        path: String,
        body: Any? = null,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): R {
        val response = httpClient.post("$baseUrl$path") {
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            block()
        }
        return response.body<ApiResponse<R>>().data
    }

    /** PUT [path] with JSON [body] and unwrap ApiResponse<R>.data */
    private suspend inline fun <reified R> putJson(
        path: String,
        body: Any,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): R {
        val response = httpClient.put("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
            block()
        }
        return response.body<ApiResponse<R>>().data
    }

    /** POST [path] with no response body expected (fire-and-forget). */
    private suspend inline fun postUnit(
        path: String,
        body: Any? = null,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ) {
        httpClient.post("$baseUrl$path") {
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            block()
        }
    }

    /** PUT [path] with no JSON body, unwrap ApiResponse<R>.data */
    private suspend inline fun <reified R> putEmpty(
        path: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): R {
        val response = httpClient.put("$baseUrl$path", block)
        return response.body<ApiResponse<R>>().data
    }

    /** DELETE [path] with no response body expected. */
    private suspend inline fun deleteUnit(
        path: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ) {
        httpClient.delete("$baseUrl$path", block)
    }

    // ── Public API ────────────────────────────────────────────────────

    suspend fun verify2FA(twoFactorToken: String, code: String): AuthResponse =
        postJson("/api/auth/verify-2fa", Verify2FARequest(twoFactorToken = twoFactorToken, code = code))

    suspend fun getLeaderboard(period: String = "all_time", limit: Int = 50): List<LeaderboardEntry> =
        get("/api/leaderboard") {
            parameter("period", period)
            parameter("limit", limit)
        }

    suspend fun getFeed(
        limit: Int = 30,
        offset: Int = 0,
        type: String? = null,
    ): List<FeedItem> = get("/api/feed") {
        parameter("limit", limit)
        parameter("offset", offset)
        type?.let { parameter("type", it) }
    }

    suspend fun getMyBadges(): List<Badge> =
        get("/api/gamification/me/badges")

    suspend fun getMyServiceArea(): ServiceArea? =
        safeGet<ServiceArea>("/api/workers/me/service-area").let {
            when (it) {
                is ApiResult.Success -> it.data
                is ApiResult.Error -> null
            }
        }

    // ── Push Notification Token ────────────────────────────────────────

    suspend fun registerDeviceToken(platform: String, token: String) =
        postUnit("/api/workers/me/device-token", mapOf("platform" to platform, "token" to token))

    suspend fun removeDeviceToken() =
        deleteUnit("/api/workers/me/device-token")

    // ── Admin API Methods ─────────────────────────────────────────────

    suspend fun getAdminStats(): AdminStatsResponse =
        get("/api/admin/analytics/stats")

    suspend fun getAdminActivity(limit: Int = 20): List<AdminActivityItem> =
        get("/api/admin/analytics/activity") { parameter("limit", limit) }

    suspend fun getAdminDailyStats(days: Int = 30): List<AdminDailyStats> =
        get("/api/admin/analytics/daily") { parameter("days", days) }

    suspend fun getRevenueByArea(): List<RevenueByArea> =
        get("/api/admin/analytics/revenue-by-area")

    suspend fun getWorkerMetrics(limit: Int = 20): List<WorkerMetrics> =
        get("/api/admin/analytics/worker-metrics") { parameter("limit", limit) }

    suspend fun getSponsorMetrics(limit: Int = 20): List<SponsorMetrics> =
        get("/api/admin/analytics/sponsor-metrics") { parameter("limit", limit) }

    // Workers
    suspend fun getAdminWorkers(search: String? = null, status: String? = null, page: Int = 1, limit: Int = 20): List<Worker> =
        get("/api/admin/workers") {
            search?.let { parameter("search", it) }
            status?.let { parameter("status", it) }
            parameter("page", page)
            parameter("limit", limit)
        }

    suspend fun getAdminWorker(workerId: Int): Worker =
        get("/api/admin/workers/$workerId")

    suspend fun verifyWorker(workerId: Int, request: VerifyWorkerRequest): Worker =
        postJson("/api/admin/workers/$workerId/verify", request)

    suspend fun updateWorkerRole(workerId: Int, request: UpdateWorkerRoleRequest): Worker =
        putJson("/api/admin/workers/$workerId/role", request)

    suspend fun bulkWorkerAction(request: BulkActionRequest): List<Worker> =
        postJson("/api/admin/workers/bulk-verify", request)

    // Sponsors
    suspend fun getAdminSponsors(search: String? = null, page: Int = 1, limit: Int = 20): List<Sponsor> =
        get("/api/admin/sponsors") {
            search?.let { parameter("search", it) }
            parameter("page", page)
            parameter("limit", limit)
        }

    // Jobs
    suspend fun getAdminJobs(status: String? = null, page: Int = 1, limit: Int = 20): List<Job> =
        get("/api/admin/jobs") {
            status?.let { parameter("status", it) }
            parameter("page", page)
            parameter("limit", limit)
        }

    suspend fun forceJobStatus(jobId: Int, request: ForceJobStatusRequest): Job =
        putJson("/api/admin/jobs/$jobId/status", request)

    // Routes
    suspend fun getAdminRoutes(): List<Route> =
        get("/api/admin/routes")

    suspend fun createRoute(request: AdminRouteRequest): Route =
        postJson("/api/admin/routes", request)

    suspend fun updateRoute(routeId: Int, request: AdminRouteRequest): Route =
        putJson("/api/admin/routes/$routeId", request)

    suspend fun deleteRoute(routeId: Int) =
        deleteUnit("/api/admin/routes/$routeId")

    // Blocks
    suspend fun getAdminBlocks(): List<Block> =
        get("/api/admin/blocks")

    suspend fun createBlock(request: AdminBlockRequest): Block =
        postJson("/api/admin/blocks", request)

    suspend fun updateBlock(blockId: Int, request: AdminBlockRequest): Block =
        putJson("/api/admin/blocks/$blockId", request)

    // Bounties
    suspend fun getAdminBounties(): List<Bounty> =
        get("/api/admin/bounties")

    suspend fun forceCloseBounty(bountyId: Int): Bounty =
        postJson("/api/admin/bounties/$bountyId/close")

    suspend fun getBountyAnalyticsStats(): BountyAnalyticsStats =
        get("/api/admin/analytics/bounty-stats")

    suspend fun getBountyTimeseries(days: Int = 30): List<BountyTimeseriesPoint> =
        get("/api/admin/analytics/bounty-timeseries") { parameter("days", days) }

    suspend fun getBountyByLocation(): List<BountyLocationStats> =
        get("/api/admin/analytics/bounty-by-location")

    suspend fun getBountyLeaderboard(limit: Int = 10): List<BountyPosterStats> =
        get("/api/admin/analytics/bounty-leaderboard") { parameter("limit", limit) }

    suspend fun claimBounty(bountyId: Int): Bounty =
        postJson("/api/bounties/$bountyId/claim")

    suspend fun forceReassignBounty(bountyId: Int): Bounty =
        putEmpty("/api/admin/bounties/$bountyId/force-reassign")

    // Disputes
    suspend fun getAdminDisputes(status: String? = null): List<Dispute> =
        get("/api/admin/disputes") { status?.let { parameter("status", it) } }

    suspend fun resolveDispute(disputeId: Int, request: ResolveDisputeRequest): Dispute =
        postJson("/api/admin/disputes/$disputeId/resolve", request)

    // Service Areas
    suspend fun getAdminServiceAreas(): List<ServiceArea> =
        get("/api/admin/service-areas")

    suspend fun createServiceArea(request: AdminServiceAreaRequest): ServiceArea =
        postJson("/api/admin/service-areas", request)

    // Feedback
    suspend fun getAdminFeedback(status: String? = null): List<FeedbackTicket> =
        get("/api/admin/feedback") { status?.let { parameter("status", it) } }

    // Address Changes
    suspend fun getAddressChanges(): List<AddressChangeRequest> =
        get("/api/admin/address-changes")
}
