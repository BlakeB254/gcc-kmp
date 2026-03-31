package com.gcc.shared.api

import com.gcc.shared.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.api.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import com.gcc.shared.serialization.GccJson

class GccApiClient(
    val baseUrl: String,
    private val tokenProvider: suspend () -> String? = { null },
    private val onUnauthorized: (() -> Unit)? = null,
    httpClientOverride: HttpClient? = null,
) {
    val json = GccJson

    val httpClient = httpClientOverride ?: HttpClient {
        expectSuccess = false // Don't throw on non-2xx; let callers inspect status
        install(ContentNegotiation) {
            json(this@GccApiClient.json)
        }
        // Add Bearer token fresh on every request (avoids Ktor Auth plugin caching issue)
        install(createClientPlugin("BearerAuth") {
            onRequest { request, _ ->
                tokenProvider()?.let { token ->
                    request.headers[HttpHeaders.Authorization] = "Bearer $token"
                }
            }
        })
    }

    // ── HTTP Helpers ───────────────────────────────────────────────────

    private fun checkUnauthorized(statusCode: Int) {
        if (statusCode == 401 || statusCode == 403) {
            onUnauthorized?.invoke()
        }
    }

    /** GET [path] and unwrap ApiResponse<T>.data */
    private suspend inline fun <reified T> get(
        path: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response = httpClient.get("$baseUrl$path", block)
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception("HTTP ${response.status.value}: $errorBody")
        }
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
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception("HTTP ${response.status.value}: $errorBody")
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
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception("HTTP ${response.status.value}: $errorBody")
        }
        return response.body<ApiResponse<R>>().data
    }

    /** POST [path] with no response body expected (fire-and-forget). */
    private suspend inline fun postUnit(
        path: String,
        body: Any? = null,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ) {
        val response = httpClient.post("$baseUrl$path") {
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            block()
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
        }
    }

    /** PUT [path] with no JSON body, unwrap ApiResponse<R>.data */
    private suspend inline fun <reified R> putEmpty(
        path: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): R {
        val response = httpClient.put("$baseUrl$path", block)
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception("HTTP ${response.status.value}: $errorBody")
        }
        return response.body<ApiResponse<R>>().data
    }

    /** PATCH [path] with JSON body, unwrap ApiResponse<R>.data */
    private suspend inline fun <reified B, reified R> patchJson(
        path: String,
        body: B,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): R {
        val response = httpClient.patch("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
            block()
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception("HTTP ${response.status.value}: $errorBody")
        }
        return response.body<ApiResponse<R>>().data
    }

    /** DELETE [path] with no response body expected. */
    private suspend inline fun deleteUnit(
        path: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ) {
        val response = httpClient.delete("$baseUrl$path", block)
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception("HTTP ${response.status.value}: $errorBody")
        }
    }

    // ── Worker API ─────────────────────────────────────────────────────

    suspend fun getRoutes(status: String = "published"): List<Route> =
        get("/api/routes") { parameter("status", status) }

    suspend fun getRoute(routeId: Int): Route =
        get("/api/routes/$routeId")

    suspend fun getWorkerProfile(): Worker =
        get("/api/workers/me")

    // ── Jobs API ──────────────────────────────────────────────────────

    suspend fun getAvailableJobs(routeId: Int, limit: Int = 1): List<Job> =
        get("/api/jobs") {
            parameter("route_id", routeId)
            parameter("status", "available")
            parameter("limit", limit)
        }

    suspend fun claimJob(jobId: Int): Job =
        postJson("/api/jobs/$jobId/claim")

    suspend fun getJob(jobId: Int): Job =
        get("/api/jobs/$jobId")

    suspend fun startJob(jobId: Int): Job =
        patchJson<Unit?, Job>("/api/jobs/$jobId/start", null)

    suspend fun completeJob(jobId: Int): Job =
        patchJson<Unit?, Job>("/api/jobs/$jobId/complete", null)

    // ── Worker Profile ────────────────────────────────────────────────

    suspend fun updateDisplayHandle(handle: String): Worker =
        patchJson("/api/workers/me/handle", mapOf("handle" to handle))

    // ── Bounties (Public) ─────────────────────────────────────────────

    suspend fun getBounties(status: String? = null, limit: Int = 50): List<Bounty> =
        get("/api/bounties") {
            status?.let { parameter("status", it) }
            parameter("limit", limit)
        }

    suspend fun getBounty(bountyId: Int): Bounty =
        get("/api/bounties/$bountyId")

    suspend fun createBounty(request: Any): Bounty =
        postJson("/api/bounties", request)

    suspend fun acceptBounty(bountyId: Int): Bounty =
        postJson("/api/bounties/$bountyId/accept")

    // ── Wallet ────────────────────────────────────────────────────────

    suspend fun getWallet(): SponsorWallet =
        get("/api/wallet")

    suspend fun depositToWallet(amountCents: Int, fundType: String = "general"): SponsorWallet =
        postJson("/api/wallet/deposit", mapOf("amount" to amountCents, "fundType" to fundType))

    suspend fun allocateWalletFunds(general: Int, route: Int, bounty: Int): SponsorWallet =
        postJson("/api/wallet/allocate", mapOf("general" to general, "route" to route, "bounty" to bounty))

    suspend fun getWalletTransactions(limit: Int = 50, offset: Int = 0): List<WalletTransaction> =
        get("/api/wallet/transactions") {
            parameter("limit", limit)
            parameter("offset", offset)
        }

    // ── Spot Reports (Public) ─────────────────────────────────────────

    suspend fun createSpotReport(request: Any): CommunityReport =
        postJson("/api/spots", request)

    // ── Auth ──────────────────────────────────────────────────────────

    suspend fun login(email: String, password: String): AuthResponse =
        postJson("/api/auth/login", LoginRequest(email = email, password = password))

    suspend fun register(request: RegisterRequest): AuthResponse =
        postJson("/api/auth/register", request)

    // ── Public API ────────────────────────────────────────────────────

    suspend fun verify2FA(twoFactorToken: String, code: String): AuthResponse =
        postJson("/api/auth/verify-2fa", Verify2FARequest(twoFactorToken = twoFactorToken, code = code))

    suspend fun getSponsorWall(): List<Sponsor> {
        val response = httpClient.get("$baseUrl/api/public/sponsor-wall")
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw Exception("HTTP ${response.status.value}: $errorBody")
        }
        // The public sponsor-wall endpoint may return a raw array or ApiResponse wrapper
        val text = response.bodyAsText()
        return try {
            json.decodeFromString<ApiResponse<List<Sponsor>>>(text).data
        } catch (_: Exception) {
            json.decodeFromString<List<Sponsor>>(text)
        }
    }

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

    suspend fun deleteAccount() =
        deleteUnit("/api/workers/me")

    // ── Admin API Methods ─────────────────────────────────────────────

    suspend fun adminAssignRoute(routeId: Int, workerId: Int, isTest: Boolean): Job =
        postJson("/api/admin/routes/$routeId/assign", mapOf(
            "workerId" to workerId,
            "isTest" to isTest,
        ))

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

    suspend fun getWorkerDocuments(workerId: Int): WorkerDocuments =
        get("/api/admin/workers/$workerId/documents")

    suspend fun verifyWorker(workerId: Int, request: VerifyWorkerRequest): Worker =
        postJson("/api/admin/workers/$workerId/verify", request)

    suspend fun updateWorkerRole(workerId: Int, request: UpdateWorkerRoleRequest): Worker =
        putJson("/api/admin/workers/$workerId/role", request)

    suspend fun bulkWorkerAction(request: BulkActionRequest): List<Worker> =
        postJson("/api/admin/workers/bulk-verify", request)

    suspend fun deleteWorker(workerId: Int): Unit =
        deleteUnit("/api/admin/workers/$workerId")

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

    suspend fun updateRouteStatus(routeId: Int, status: String): Route =
        putJson("/api/admin/routes/$routeId", mapOf("status" to status))

    suspend fun updateRouteImage(routeId: Int, imageUrl: String?): Route =
        putJson("/api/admin/routes/$routeId", mapOf("imageUrl" to imageUrl))

    suspend fun updateRouteImageLocation(routeId: Int, lat: Double?, lng: Double?): Route =
        putJson("/api/admin/routes/$routeId", ImageLocationUpdate(lat, lng))

    suspend fun updateRouteNameDesc(routeId: Int, displayName: String, description: String?): Route =
        putJson("/api/admin/routes/$routeId", buildMap {
            put("displayName", displayName)
            if (description != null) put("description", description)
        })

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

    // Community Spot Reports
    suspend fun getAdminSpotReports(): List<CommunityReport> =
        get("/api/admin/spots")

    /** Fetch nearby spots for duplicate detection. Returns spots within [radiusMeters] of (lat, lng). */
    suspend fun getSpots(
        lat: Double? = null,
        lng: Double? = null,
        radiusMeters: Double? = null,
        limit: Int = 50,
    ): List<CommunityReport> = get("/api/spots") {
        lat?.let { parameter("lat", it) }
        lng?.let { parameter("lng", it) }
        radiusMeters?.let { parameter("radius", it) }
        parameter("limit", limit)
    }

    // Upload spot photos (multipart form data), returns list of public URLs
    suspend fun uploadSpotPhotos(photos: List<ByteArray>): List<String> {
        if (photos.isEmpty()) return emptyList()
        val token = tokenProvider()
        val response = httpClient.submitFormWithBinaryData(
            url = "$baseUrl/api/spots/upload",
            formData = formData {
                photos.forEachIndexed { index, bytes ->
                    append("photo_$index", bytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=\"photo_$index.jpg\"")
                    })
                }
            },
        ) {
            // Explicitly set auth header for multipart uploads
            if (token != null) {
                headers[HttpHeaders.Authorization] = "Bearer $token"
            }
        }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw Exception("Upload failed (${response.status.value}): $errorBody")
        }
        return response.body<ApiResponse<PhotoUploadResponse>>().data.urls
    }

    // Volunteer cleanup flow
    suspend fun startVolunteerCleanup(reportId: Int, request: StartVolunteerRequest): VolunteerCleanup =
        postJson("/api/spots/$reportId/volunteer", request)

    suspend fun completeVolunteerCleanup(reportId: Int, request: CompleteVolunteerRequest): VolunteerCleanup =
        postJson("/api/spots/$reportId/volunteer/complete", request)

    suspend fun reportSpotAlreadyClean(reportId: Int, beforeMediaUrls: List<String>): VolunteerCleanup =
        postJson("/api/spots/$reportId/volunteer/already-clean", mapOf("beforeMediaUrls" to beforeMediaUrls))

    // Admin: Create spot report
    suspend fun createAdminSpot(request: CreateSpotRequest): SpotResponse =
        postJson("/api/admin/spots", request)

    // Admin: Update spot report status (resolve, verify, reject)
    suspend fun updateSpotStatus(spotId: Int, status: String) =
        patchJson<Map<String, String>, Map<String, Any>>(
            "/api/spots/$spotId",
            mapOf("status" to status),
        )

    // Admin: Delete a spot report
    suspend fun deleteSpot(spotId: Int) =
        deleteUnit("/api/spots/$spotId")

    // Admin: Add checkpoint to route
    suspend fun addCheckpoint(routeId: Int, request: AdminCheckpointRequest): RouteCheckpoint =
        postJson("/api/admin/routes/$routeId/checkpoints", request)

    // Admin: Delete a checkpoint from a route
    suspend fun deleteCheckpoint(routeId: Int, checkpointId: Int) =
        deleteUnit("/api/admin/routes/$routeId/checkpoints") {
            url { parameters.append("id", checkpointId.toString()) }
        }

    // Admin: Update worker info (name, phone, address, tier)
    suspend fun updateAdminWorker(workerId: Int, updates: Map<String, String>): Worker =
        putJson("/api/admin/workers/$workerId", updates)

    // Auth: Refresh JWT token — returns new token + expiry
    suspend fun refreshToken(): AuthRefreshResponse =
        postJson("/api/auth/refresh")

    // Admin: Add photos to an existing spot report
    suspend fun addSpotPhotos(spotId: Int, newUrls: List<String>): CommunityReport =
        patchJson<Map<String, Any>, CommunityReport>(
            "/api/spots/$spotId",
            mapOf("addMediaUrls" to newUrls),
        )

    // ── Spot Verification Lifecycle ──────────────────────────────────

    suspend fun verifySpot(spotId: Int, request: VerifySpotRequest): VerifySpotResponse =
        postJson("/api/spots/$spotId/verify", request)

    suspend fun getSpotsNeedingVerification(limit: Int = 50): List<CommunityReport> =
        get("/api/spots/needs-verification") { parameter("limit", limit) }

    suspend fun getSpotVerifications(spotId: Int): List<SpotVerification> =
        get("/api/spots/$spotId/verifications")

    suspend fun getMyPoints(): PointsSummary =
        get("/api/workers/me/points")

    // ── Scout & Clean Route Scan API ──────────────────────────────

    suspend fun createScan(req: CreateScanRequest): RouteScan =
        postJson("/api/scans", req)

    suspend fun getScan(id: Int): RouteScan =
        get("/api/scans/$id")

    suspend fun advanceScanPhase(scanId: Int, req: AdvancePhaseRequest): RouteScan =
        patchJson("/api/scans/$scanId/phase", req)

    suspend fun addScanHotspot(scanId: Int, req: CreateHotspotRequest): ScanHotspot =
        postJson("/api/scans/$scanId/hotspots", req)

    suspend fun updateScanHotspot(scanId: Int, hotspotId: Int, req: UpdateHotspotRequest): ScanHotspot =
        patchJson("/api/scans/$scanId/hotspots/$hotspotId", req)

    suspend fun updateScanCoverage(scanId: Int, req: AdvancePhaseRequest): RouteScan =
        patchJson("/api/scans/$scanId/coverage", req)

    suspend fun completeScan(scanId: Int, req: CompleteScanRequest): CompleteScanResponse =
        postJson("/api/scans/$scanId/complete", req)

    // ── Cleanliness Analytics ─────────────────────────────────────

    suspend fun getCityCleanlinessOverview(): CityCleanlinessOverview =
        get("/api/analytics/cleanliness")

    suspend fun getAreaCleanliness(area: String): CleanlinessStats =
        get("/api/analytics/cleanliness/${area.encodeURLPathPart()}")

    // ── Document Verification ──────────────────────────────────────

    /** Submit verification documents (gov ID + proof of address URLs) */
    suspend fun submitVerificationDocuments(
        governmentIdUrl: String,
        proofOfAddressUrl: String,
    ) {
        postUnit("/api/workers/me/verification") {
            contentType(ContentType.Application.Json)
            setBody(VerificationDocumentSubmission(governmentIdUrl, proofOfAddressUrl))
        }
    }

    /** Get current verification status */
    suspend fun getVerificationStatus(): WorkerVerification =
        get("/api/workers/me/verification")
}

@kotlinx.serialization.Serializable
private data class ImageLocationUpdate(
    val imageLocationLat: Double? = null,
    val imageLocationLng: Double? = null,
)
