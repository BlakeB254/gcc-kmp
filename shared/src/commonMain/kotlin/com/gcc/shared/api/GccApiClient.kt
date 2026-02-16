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
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class GccApiClient(
    val baseUrl: String,
    private val tokenProvider: suspend () -> String? = { null },
    httpClientOverride: HttpClient? = null,
) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    val httpClient = httpClientOverride ?: HttpClient {
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

    suspend fun getLeaderboard(period: String = "all_time", limit: Int = 50): List<LeaderboardEntry> {
        val response = httpClient.get("$baseUrl/api/leaderboard") {
            parameter("period", period)
            parameter("limit", limit)
        }
        return response.body<ApiResponse<List<LeaderboardEntry>>>().data
    }

    suspend fun getFeed(
        limit: Int = 30,
        offset: Int = 0,
        type: String? = null,
    ): List<FeedItem> {
        val response = httpClient.get("$baseUrl/api/feed") {
            parameter("limit", limit)
            parameter("offset", offset)
            type?.let { parameter("type", it) }
        }
        return response.body<ApiResponse<List<FeedItem>>>().data
    }

    suspend fun getMyBadges(): List<Badge> {
        val response = httpClient.get("$baseUrl/api/gamification/me/badges")
        return response.body<ApiResponse<List<Badge>>>().data
    }

    suspend fun getMyServiceArea(): ServiceArea? {
        return try {
            val response = httpClient.get("$baseUrl/api/workers/me/service-area")
            response.body<ApiResponse<ServiceArea>>().data
        } catch (_: Exception) {
            null
        }
    }

    // ── Admin API Methods ─────────────────────────────────────────────

    suspend fun getAdminStats(): AdminStatsResponse {
        val response = httpClient.get("$baseUrl/api/admin/analytics/stats")
        return response.body<ApiResponse<AdminStatsResponse>>().data
    }

    suspend fun getAdminActivity(limit: Int = 20): List<AdminActivityItem> {
        val response = httpClient.get("$baseUrl/api/admin/analytics/activity") {
            parameter("limit", limit)
        }
        return response.body<ApiResponse<List<AdminActivityItem>>>().data
    }

    suspend fun getAdminDailyStats(days: Int = 30): List<AdminDailyStats> {
        val response = httpClient.get("$baseUrl/api/admin/analytics/daily") {
            parameter("days", days)
        }
        return response.body<ApiResponse<List<AdminDailyStats>>>().data
    }

    suspend fun getRevenueByArea(): List<RevenueByArea> {
        val response = httpClient.get("$baseUrl/api/admin/analytics/revenue-by-area")
        return response.body<ApiResponse<List<RevenueByArea>>>().data
    }

    suspend fun getWorkerMetrics(limit: Int = 20): List<WorkerMetrics> {
        val response = httpClient.get("$baseUrl/api/admin/analytics/worker-metrics") {
            parameter("limit", limit)
        }
        return response.body<ApiResponse<List<WorkerMetrics>>>().data
    }

    suspend fun getSponsorMetrics(limit: Int = 20): List<SponsorMetrics> {
        val response = httpClient.get("$baseUrl/api/admin/analytics/sponsor-metrics") {
            parameter("limit", limit)
        }
        return response.body<ApiResponse<List<SponsorMetrics>>>().data
    }

    // Workers
    suspend fun getAdminWorkers(search: String? = null, status: String? = null, page: Int = 1, limit: Int = 20): List<Worker> {
        val response = httpClient.get("$baseUrl/api/admin/workers") {
            search?.let { parameter("search", it) }
            status?.let { parameter("status", it) }
            parameter("page", page)
            parameter("limit", limit)
        }
        return response.body<ApiResponse<List<Worker>>>().data
    }

    suspend fun getAdminWorker(workerId: Int): Worker {
        val response = httpClient.get("$baseUrl/api/admin/workers/$workerId")
        return response.body<ApiResponse<Worker>>().data
    }

    suspend fun verifyWorker(workerId: Int, request: VerifyWorkerRequest): Worker {
        val response = httpClient.post("$baseUrl/api/admin/workers/$workerId/verify") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(request)
        }
        return response.body<ApiResponse<Worker>>().data
    }

    suspend fun updateWorkerRole(workerId: Int, request: UpdateWorkerRoleRequest): Worker {
        val response = httpClient.put("$baseUrl/api/admin/workers/$workerId/role") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(request)
        }
        return response.body<ApiResponse<Worker>>().data
    }

    suspend fun bulkWorkerAction(request: BulkActionRequest): List<Worker> {
        val response = httpClient.post("$baseUrl/api/admin/workers/bulk-verify") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(request)
        }
        return response.body<ApiResponse<List<Worker>>>().data
    }

    // Sponsors
    suspend fun getAdminSponsors(search: String? = null, page: Int = 1, limit: Int = 20): List<Sponsor> {
        val response = httpClient.get("$baseUrl/api/admin/sponsors") {
            search?.let { parameter("search", it) }
            parameter("page", page)
            parameter("limit", limit)
        }
        return response.body<ApiResponse<List<Sponsor>>>().data
    }

    // Jobs
    suspend fun getAdminJobs(status: String? = null, page: Int = 1, limit: Int = 20): List<Job> {
        val response = httpClient.get("$baseUrl/api/admin/jobs") {
            status?.let { parameter("status", it) }
            parameter("page", page)
            parameter("limit", limit)
        }
        return response.body<ApiResponse<List<Job>>>().data
    }

    suspend fun forceJobStatus(jobId: Int, request: ForceJobStatusRequest): Job {
        val response = httpClient.put("$baseUrl/api/admin/jobs/$jobId/status") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(request)
        }
        return response.body<ApiResponse<Job>>().data
    }

    // Routes
    suspend fun getAdminRoutes(): List<Route> {
        val response = httpClient.get("$baseUrl/api/admin/routes")
        return response.body<ApiResponse<List<Route>>>().data
    }

    suspend fun createRoute(request: AdminRouteRequest): Route {
        val response = httpClient.post("$baseUrl/api/admin/routes") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(request)
        }
        return response.body<ApiResponse<Route>>().data
    }

    suspend fun updateRoute(routeId: Int, request: AdminRouteRequest): Route {
        val response = httpClient.put("$baseUrl/api/admin/routes/$routeId") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(request)
        }
        return response.body<ApiResponse<Route>>().data
    }

    suspend fun deleteRoute(routeId: Int) {
        httpClient.delete("$baseUrl/api/admin/routes/$routeId")
    }

    // Blocks
    suspend fun getAdminBlocks(): List<Block> {
        val response = httpClient.get("$baseUrl/api/admin/blocks")
        return response.body<ApiResponse<List<Block>>>().data
    }

    suspend fun createBlock(request: AdminBlockRequest): Block {
        val response = httpClient.post("$baseUrl/api/admin/blocks") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(request)
        }
        return response.body<ApiResponse<Block>>().data
    }

    suspend fun updateBlock(blockId: Int, request: AdminBlockRequest): Block {
        val response = httpClient.put("$baseUrl/api/admin/blocks/$blockId") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(request)
        }
        return response.body<ApiResponse<Block>>().data
    }

    // Bounties
    suspend fun getAdminBounties(): List<Bounty> {
        val response = httpClient.get("$baseUrl/api/admin/bounties")
        return response.body<ApiResponse<List<Bounty>>>().data
    }

    suspend fun forceCloseBounty(bountyId: Int): Bounty {
        val response = httpClient.post("$baseUrl/api/admin/bounties/$bountyId/close")
        return response.body<ApiResponse<Bounty>>().data
    }

    // Disputes
    suspend fun getAdminDisputes(status: String? = null): List<Dispute> {
        val response = httpClient.get("$baseUrl/api/admin/disputes") {
            status?.let { parameter("status", it) }
        }
        return response.body<ApiResponse<List<Dispute>>>().data
    }

    suspend fun resolveDispute(disputeId: Int, request: ResolveDisputeRequest): Dispute {
        val response = httpClient.post("$baseUrl/api/admin/disputes/$disputeId/resolve") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(request)
        }
        return response.body<ApiResponse<Dispute>>().data
    }

    // Service Areas
    suspend fun getAdminServiceAreas(): List<ServiceArea> {
        val response = httpClient.get("$baseUrl/api/admin/service-areas")
        return response.body<ApiResponse<List<ServiceArea>>>().data
    }

    suspend fun createServiceArea(request: AdminServiceAreaRequest): ServiceArea {
        val response = httpClient.post("$baseUrl/api/admin/service-areas") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(request)
        }
        return response.body<ApiResponse<ServiceArea>>().data
    }

    // Feedback
    suspend fun getAdminFeedback(status: String? = null): List<FeedbackTicket> {
        val response = httpClient.get("$baseUrl/api/admin/feedback") {
            status?.let { parameter("status", it) }
        }
        return response.body<ApiResponse<List<FeedbackTicket>>>().data
    }

    // Address Changes
    suspend fun getAddressChanges(): List<AddressChangeRequest> {
        val response = httpClient.get("$baseUrl/api/admin/address-changes")
        return response.body<ApiResponse<List<AddressChangeRequest>>>().data
    }
}
