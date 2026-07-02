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
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

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

    /** Unauthenticated client for website calls (no Bearer token). */
    private val siteClient = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) { json(this@GccApiClient.json) }
    }

    // ── HTTP Helpers ───────────────────────────────────────────────────

    private fun checkUnauthorized(statusCode: Int) {
        if (statusCode == 401 || statusCode == 403) {
            onUnauthorized?.invoke()
        }
    }

    private fun httpErrorMessage(statusCode: Int, body: String): String {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return "HTTP $statusCode"

        runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()?.let { obj ->
            val apiMessage = obj["message"]?.jsonPrimitive?.contentOrNull
                ?: obj["error"]?.jsonPrimitive?.contentOrNull
            if (!apiMessage.isNullOrBlank()) return "HTTP $statusCode: $apiMessage"
        }

        val isHtml = trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.contains("<title>", ignoreCase = true)
        if (isHtml) {
            return when (statusCode) {
                404 -> "HTTP 404: Endpoint not found. The server may need the latest mobile API routes deployed."
                else -> "HTTP $statusCode: Server returned an HTML error page."
            }
        }

        val cleaned = trimmed
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .take(300)
        return "HTTP $statusCode: $cleaned"
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
            throw Exception(httpErrorMessage(response.status.value, errorBody))
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
            throw Exception(httpErrorMessage(response.status.value, errorBody))
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
            throw Exception(httpErrorMessage(response.status.value, errorBody))
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
            throw Exception(httpErrorMessage(response.status.value, errorBody))
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
            throw Exception(httpErrorMessage(response.status.value, errorBody))
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
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
    }

    // ── Worker API ─────────────────────────────────────────────────────

    suspend fun getRoutes(status: String = "published"): List<Route> =
        get("/api/routes") { parameter("status", status) }

    suspend fun getPublicRoutes(status: String = "published"): List<Route> {
        val siteUrl = "https://api.globalcommunitycleaners.org"
        val response = siteClient.get("$siteUrl/api/routes") {
            parameter("status", status)
        }
        if (!response.status.isSuccess()) {
            throw Exception("Failed to load public routes")
        }
        return json.decodeFromString<ApiResponse<List<Route>>>(response.bodyAsText()).data
    }

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

    suspend fun getAvailableRouteJobs(limit: Int = 100): List<Job> =
        get("/api/jobs") {
            parameter("status", "available")
            parameter("limit", limit)
        }

    suspend fun getMyJobs(limit: Int = 20): List<Job> =
        get("/api/workers/me/jobs") {
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

    suspend fun declineJob(jobId: Int): Job =
        patchJson<Unit?, Job>("/api/jobs/$jobId/decline", null)

    suspend fun submitJobEvidence(jobId: Int, request: JobEvidenceRequest): Job =
        patchJson("/api/jobs/$jobId/evidence", request)

    // ── Route Release Offers ─────────────────────────────────────────

    suspend fun getMyRouteOffers(): List<RouteOffer> =
        get("/api/workers/me/route-offers")

    suspend fun acceptRouteOffer(offerId: Int, routeId: Int): Job =
        postJson(
            "/api/workers/me/route-offers/$offerId/accept",
            RouteOfferAcceptRequest(routeId = routeId),
        )

    suspend fun declineRouteOffer(offerId: Int, reason: String? = null): RouteOffer =
        patchJson(
            "/api/workers/me/route-offers/$offerId/decline",
            RouteOfferDeclineRequest(reason = reason),
        )

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

    suspend fun createBounty(request: CreateBountyRequest): Bounty =
        postJson("/api/bounties", request)

    suspend fun acceptBounty(bountyId: Int): Bounty =
        postJson("/api/bounties/$bountyId/accept")

    // ── Wallet ────────────────────────────────────────────────────────

    suspend fun getWallet(): SponsorWallet =
        get("/api/wallet")

    suspend fun depositToWallet(amountCents: Int, fundType: String = "general"): SponsorWallet =
        postJson("/api/wallet/deposit", mapOf("amount" to amountCents.toString(), "fundType" to fundType))

    suspend fun allocateWalletFunds(general: Int, route: Int, bounty: Int): SponsorWallet =
        postJson("/api/wallet/allocate", mapOf("general" to general.toString(), "route" to route.toString(), "bounty" to bounty.toString()))

    suspend fun getWalletTransactions(limit: Int = 50, offset: Int = 0): List<WalletTransaction> =
        get("/api/wallet/transactions") {
            parameter("limit", limit)
            parameter("offset", offset)
        }

    // ── Stripe Checkout (via website) ────────────────────────────────

    suspend fun createCheckoutSession(request: CheckoutRequest): CheckoutResponse {
        val siteUrl = "https://www.globalcommunitycleaners.org"
        val response = siteClient.post("$siteUrl/api/contribute/checkout") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw Exception("Checkout failed: ${httpErrorMessage(response.status.value, errorBody)}")
        }
        return response.body<CheckoutResponse>()
    }

    // ── Feed Social ────────────────────────────────────────────────────

    suspend fun getFeedComments(feedItemId: String, limit: Int = 50): List<FeedComment> =
        get("/api/feed/$feedItemId/comments") { parameter("limit", limit) }

    suspend fun addFeedComment(feedItemId: String, text: String): FeedComment =
        postJson("/api/feed/$feedItemId/comments", mapOf("text" to text))

    suspend fun addFeedCaption(feedItemId: String, caption: String): FeedItem =
        patchJson<Map<String, String>, FeedItem>(
            "/api/feed/$feedItemId/caption",
            mapOf("caption" to caption),
        )

    suspend fun optInFeedProfile(feedItemId: String): FeedItem =
        postJson("/api/feed/$feedItemId/opt-in-profile")

    // ── Spot Reports (Public) ─────────────────────────────────────────

    suspend fun createSpotReport(request: Any): CommunityReport =
        postJson("/api/spots", request)

    // ── Auth ──────────────────────────────────────────────────────────

    suspend fun login(email: String, password: String): AuthResponse =
        postJson("/api/auth/login", LoginRequest(email = email, password = password))

    suspend fun register(request: RegisterRequest): AuthResponse =
        postJson("/api/auth/register", request)

    suspend fun loginWithApple(
        identityToken: String,
        fullName: String?,
        email: String?,
        nonce: String?,
    ): AuthResponse {
        val siteUrl = "https://www.globalcommunitycleaners.org"
        val response = siteClient.post("$siteUrl/api/auth/apple") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(AppleAuthRequest(identityToken = identityToken, fullName = fullName, email = email, nonce = nonce))
        }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw Exception("Apple auth failed: ${httpErrorMessage(response.status.value, errorBody)}")
        }
        return response.body<ApiResponse<AuthResponse>>().data
    }

    suspend fun loginWithGoogle(idToken: String): AuthResponse {
        val siteUrl = "https://www.globalcommunitycleaners.org"
        val response = siteClient.post("$siteUrl/api/auth/google") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(GoogleAuthRequest(idToken = idToken))
        }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw Exception("Google auth failed: ${httpErrorMessage(response.status.value, errorBody)}")
        }
        return response.body<ApiResponse<AuthResponse>>().data
    }

    // ── Articles ──────────────────────────────────────────────────────

    /** List published articles (summaries only — no body). */
    suspend fun getArticles(): List<ArticleSummary> = get("/api/articles")

    /** Fetch a single article including its full markdown body. */
    suspend fun getArticle(slug: String): Article = get("/api/articles/$slug")

    // ── Admin: points management ──────────────────────────────────────

    /**
     * Grant or correct community points for a worker. Admin-only.
     * Caller must already pass `tokenManager.isAdmin()`; the server
     * re-checks via the JWT role claim and returns 403 otherwise.
     */
    suspend fun adminGrantPoints(
        workerId: Int,
        amount: Int,
        reason: String,
        action: String = "admin_grant",
    ): AdminGrantPointsResponse = postJson(
        "/api/admin/workers/$workerId/points",
        AdminGrantPointsRequest(amount = amount, reason = reason, action = action),
    )

    /**
     * Recent points-ledger entries for a worker (most recent first, up
     * to 50). Surfaced in the admin worker-detail audit panel.
     */
    suspend fun getWorkerPointsHistory(workerId: Int): WorkerPointsHistoryResponse =
        get("/api/admin/workers/$workerId/points")

    // ── Public API ────────────────────────────────────────────────────

    suspend fun verify2FA(twoFactorToken: String, code: String): AuthResponse =
        postJson("/api/auth/verify-2fa", Verify2FARequest(twoFactorToken = twoFactorToken, code = code))

    suspend fun getSponsorWall(): List<Sponsor> {
        val siteUrl = "https://www.globalcommunitycleaners.org"
        return try {
            val response = siteClient.get("$siteUrl/api/public/sponsor-wall")
            if (!response.status.isSuccess()) return emptyList()
            val body = response.body<kotlinx.serialization.json.JsonObject>()
            val sponsorsArray = body["sponsors"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            val now = kotlinx.datetime.Clock.System.now()
            sponsorsArray.mapNotNull { element ->
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun str(key: String): String? = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
                fun int(key: String): Int = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
                Sponsor(
                    id = int("id"),
                    name = str("name") ?: "Sponsor",
                    email = "",
                    type = try { SponsorType.valueOf((str("type") ?: "individual").uppercase()) } catch (_: Exception) { SponsorType.INDIVIDUAL },
                    stats = SponsorStats(
                        totalDonated = int("totalContribution"),
                        blocksSponsored = int("blocksSponsored"),
                    ),
                    createdAt = now,
                    updatedAt = now,
                )
            }
        } catch (_: Exception) {
            emptyList()
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

    // ── Admin: Payout Approvals ────────────────────────────────────────

    suspend fun getPendingPayoutReviews(): List<PendingReview> =
        get("/api/admin/jobs/pending-review")

    suspend fun approveJob(jobId: Int): ApproveJobResponse {
        val response = httpClient.post("$baseUrl/api/admin/jobs/$jobId/approve")
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<ApproveJobResponse>()
    }

    suspend fun denyJob(jobId: Int, reason: String) {
        val response = httpClient.post("$baseUrl/api/admin/jobs/$jobId/deny") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("reason" to reason))
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
    }

    suspend fun removeDeviceToken() =
        deleteUnit("/api/workers/me/device-token")

    suspend fun deleteAccount() =
        deleteUnit("/api/workers/me")

    // ── Admin API Methods ─────────────────────────────────────────────

    suspend fun adminAssignRoute(routeId: Int, workerId: Int, isTest: Boolean): Job =
        postJson(
            "/api/admin/routes/$routeId/assign",
            AdminRouteAssignmentRequest(workerId = workerId, isTest = isTest),
        )

    suspend fun getAdminRouteAssignments(routeId: Int): List<AdminRouteAssignment> =
        get("/api/admin/routes/$routeId/assign")

    suspend fun cancelAdminRouteAssignment(routeId: Int, jobId: Int): Unit =
        deleteUnit("/api/admin/routes/$routeId/assign") {
            parameter("jobId", jobId)
        }

    suspend fun createRouteRelease(request: AdminRouteReleaseRequest): RouteReleaseBatch =
        postJson("/api/admin/route-releases", request)

    suspend fun getRouteReleases(limit: Int = 50): List<RouteReleaseBatch> =
        get("/api/admin/route-releases") {
            parameter("limit", limit)
        }

    suspend fun processRouteRelease(releaseId: Int): RouteReleaseRunResponse =
        postJson("/api/admin/route-releases/$releaseId/process")

    suspend fun processRouteReleaseNotifications(): RouteReleaseRunResponse =
        postJson("/api/admin/route-releases/process-notifications")

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
    suspend fun getAdminWorkers(search: String? = null, status: String? = null, page: Int = 1, limit: Int = 20, verificationStatus: String? = null): List<Worker> =
        get("/api/admin/workers") {
            search?.let { parameter("search", it) }
            status?.let { parameter("status", it) }
            verificationStatus?.let { parameter("verificationStatus", it) }
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

    suspend fun getAvailableBlocks(): List<Block> {
        val siteUrl = "https://www.globalcommunitycleaners.org"
        val primaryBody = runCatching {
            val primaryResponse = httpClient.get("$baseUrl/api/blocks") {
                parameter("limit", 200)
            }
            if (primaryResponse.status.isSuccess()) primaryResponse.bodyAsText() else null
        }.getOrNull()
        val responseBody = primaryBody ?: run {
            val fallbackResponse = siteClient.get("$siteUrl/api/blocks") {
                parameter("limit", 200)
            }
            if (!fallbackResponse.status.isSuccess()) {
                throw Exception("Failed to load blocks")
            }
            fallbackResponse.bodyAsText()
        }
        val body = json.decodeFromString<JsonObject>(responseBody)
        val dataArray = body["data"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val now = kotlinx.datetime.Clock.System.now()

        // Fetch routes to get display names (e.g., "1500 S. Pulaski Road")
        // Routes are ordered by code, blocks are ordered by code — they align 1:1
        val routeDisplayNamesByBlockCode = try {
            val routes = getRoutes()
            routes.associate { route ->
                route.code.replace("-R", "-") to route.displayName
            }
        } catch (_: Exception) { emptyMap() }

        val sortedBlocks = dataArray.mapNotNull { it as? JsonObject }
            .filter { obj ->
                // Filter out test blocks
                val name = (obj["name"] as? JsonPrimitive)?.content ?: ""
                name.lowercase() != "test" && name.isNotBlank()
            }
        return sortedBlocks.map { obj ->
            fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
            fun objAt(key: String): JsonObject? = obj[key] as? JsonObject
            fun nestedStr(parent: JsonObject?, key: String): String? =
                (parent?.get(key) as? JsonPrimitive)?.contentOrNull
            fun decodePolygon(parent: JsonObject?): GeoJSONPolygon? = parent?.let {
                runCatching { json.decodeFromJsonElement<GeoJSONPolygon>(it) }.getOrNull()
            }
            fun decodePoint(parent: JsonObject?): GeoJSONPoint? = parent?.let {
                runCatching { json.decodeFromJsonElement<GeoJSONPoint>(it) }.getOrNull()
            }
            fun decodeCheckpoints(): List<RouteCheckpoint> {
                val items = obj["checkpoints"] as? JsonArray ?: return emptyList()
                return items.mapIndexedNotNull { index, item ->
                    val checkpoint = item as? JsonObject ?: return@mapIndexedNotNull null
                    val coordinates = checkpoint["coordinates"] as? JsonArray ?: return@mapIndexedNotNull null
                    val lng = coordinates.getOrNull(0)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    val lat = coordinates.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    if (lng == null || lat == null) return@mapIndexedNotNull null
                    val rawType = (checkpoint["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()
                    val type = when (rawType) {
                        "corner_start", "route_start", "start" -> CheckpointType.ROUTE_START
                        "corner_end", "route_end", "end" -> CheckpointType.ROUTE_END
                        "alley", "alley_entrance" -> CheckpointType.ALLEY_ENTRANCE
                        else -> CheckpointType.PHOTO_POINT
                    }
                    RouteCheckpoint(
                        name = (checkpoint["name"] as? JsonPrimitive)?.contentOrNull
                            ?: "Checkpoint ${index + 1}",
                        sequenceOrder = index + 1,
                        coordinates = Coordinates(lng = lng, lat = lat),
                        photoRequired = true,
                        geofenceRadius = (checkpoint["radius"] as? JsonPrimitive)
                            ?.contentOrNull
                            ?.toDoubleOrNull()
                            ?: 15.0,
                        type = type,
                        instructions = (checkpoint["description"] as? JsonPrimitive)?.contentOrNull,
                    )
                }
            }

            val blockName = str("name") ?: "Block"
            val blockCode = str("code") ?: ""
            val fromCorner = str("fromCorner")
            val location = objAt("location")
            val funding = objAt("funding")
            // Use matching route display name, or build "1500 S. X Ave" from fromCorner.
            val displayName = routeDisplayNamesByBlockCode[blockCode]
                ?: fromCorner?.let { "1500 $it" }
                ?: blockName

            Block(
                id = str("id")?.toIntOrNull() ?: 0,
                code = blockCode,
                name = displayName,
                serviceAreaId = str("serviceAreaId")?.toIntOrNull(),
                status = str("status")?.let { s ->
                    try { BlockStatus.valueOf(s.uppercase()) } catch (_: Exception) { BlockStatus.AVAILABLE }
                } ?: BlockStatus.AVAILABLE,
                boundary = decodePolygon(location?.get("boundary") as? JsonObject),
                center = decodePoint(location?.get("center") as? JsonObject),
                displayImage = str("displayImage"),
                imageLocationLat = str("imageLocationLat")?.toDoubleOrNull(),
                imageLocationLng = str("imageLocationLng")?.toDoubleOrNull(),
                fundingGoal = nestedStr(funding, "goal")?.toDoubleOrNull()?.toInt(),
                fundingCurrent = nestedStr(funding, "current")?.toDoubleOrNull()?.toInt() ?: 0,
                sponsorId = str("fundedById")?.toIntOrNull(),
                checkpoints = decodeCheckpoints(),
                createdAt = now,
                updatedAt = now,
            )
        }.sortedWith(
            compareBy<Block>(
                { it.center?.coordinates?.getOrNull(0) ?: it.boundary.centerLng() ?: Double.MAX_VALUE },
                { it.center?.coordinates?.getOrNull(1) ?: it.boundary.centerLat() ?: Double.MAX_VALUE },
                { it.code },
            ),
        )
    }

    suspend fun sponsorBlock(request: SponsorBlockRequest): Block =
        postJson("/api/blocks/${request.blockId}/sponsor", request)

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
    ): List<CommunityReport> {
        val response = httpClient.get("$baseUrl/api/spots") {
            lat?.let { parameter("lat", it) }
            lng?.let { parameter("lng", it) }
            radiusMeters?.let { parameter("radius", it) }
            parameter("limit", limit)
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }

        val body = response.bodyAsText()
        return runCatching {
            json.decodeFromString<ApiResponse<List<CommunityReport>>>(body).data
        }.getOrElse {
            json.decodeFromString<SpotMarkersResponse>(body).spots.mapNotNull { it.toCommunityReport() }
        }
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
            throw Exception("Upload failed: ${httpErrorMessage(response.status.value, errorBody)}")
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

    suspend fun getSpot(spotId: Int): CommunityReport =
        get("/api/spots/$spotId")

    suspend fun verifySpot(spotId: Int, request: VerifySpotRequest): VerifySpotResponse =
        postJson("/api/spots/$spotId/verify", request)

    suspend fun getSpotsNeedingVerification(limit: Int = 50): List<CommunityReport> =
        get("/api/spots/needs-verification") { parameter("limit", limit) }

    suspend fun getSpotVerifications(spotId: Int): List<SpotVerification> =
        get("/api/spots/$spotId/verifications")

    suspend fun getMyPoints(): PointsSummary =
        get("/api/workers/me/points")

    // ── Scout & Clean Route Scan API ──────────────────────────────

    suspend fun createScan(req: CreateScanRequest, onBehalfOfWorkerId: Int? = null): RouteScan =
        postJson("/api/scans", if (onBehalfOfWorkerId != null) req.copy(onBehalfOfWorkerId = onBehalfOfWorkerId) else req)

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

    // ── Scan Coverage Ledger API ───────────────────────────────────
    // NOTE: these two endpoints return their payload as flat JSON (e.g.
    // {"inserted":1,"success":true}), not wrapped in the {"data":...,"success":...}
    // ApiResponse<T> envelope that postJson/get assume — so they use the raw
    // httpClient pattern (see connectStatus/createCheckoutSession above) instead.

    suspend fun submitCoverageEvents(events: List<ScanCoverageEventRequest>): ScanCoverageSubmitResponse {
        val response = httpClient.post("$baseUrl/api/scans/coverage") {
            contentType(ContentType.Application.Json)
            setBody(ScanCoverageSubmitRequest(events))
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<ScanCoverageSubmitResponse>()
    }

    suspend fun getMapCoverage(bbox: String): MapCoverageResponse {
        val response = httpClient.get("$baseUrl/api/map/coverage") {
            parameter("bbox", bbox)
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<MapCoverageResponse>()
    }

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

    // ── Worker Connect (Stripe) ────────────────────────────────────────

    /** POST /api/workers/me/connect/onboard — returns the Stripe-hosted onboarding URL. */
    suspend fun connectOnboard(): String {
        val response = httpClient.post("$baseUrl/api/workers/me/connect/onboard")
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        val body = response.body<JsonObject>()
        return body["url"]?.jsonPrimitive?.content
            ?: throw Exception("No url in onboard response")
    }

    /** GET /api/workers/me/connect/status — returns Connect account onboarding status. */
    suspend fun connectStatus(): ConnectStatus {
        val response = httpClient.get("$baseUrl/api/workers/me/connect/status")
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<ConnectStatus>()
    }
}

private fun GeoJSONPolygon?.centerLng(): Double? =
    this?.coordinates
        ?.firstOrNull()
        ?.mapNotNull { it.getOrNull(0) }
        ?.takeIf { it.isNotEmpty() }
        ?.average()

private fun GeoJSONPolygon?.centerLat(): Double? =
    this?.coordinates
        ?.firstOrNull()
        ?.mapNotNull { it.getOrNull(1) }
        ?.takeIf { it.isNotEmpty() }
        ?.average()

// ── Payout Approval DTOs ──────────────────────────────────────────────

@Serializable
data class PendingReview(
    val jobId: Int,
    val workerId: Int?,
    val workerName: String?,
    val payoutAmount: Double?,
    val beforePhotos: List<String> = emptyList(),
    val afterPhotos: List<String> = emptyList(),
    val hasScan: Boolean = false,
    val scanDepthAvailable: Boolean = false,
    val completedAt: String?,
)

@Serializable
data class ApproveJobResponse(
    val ok: Boolean,
    val payoutMethod: String? = null,
    val transferId: String? = null,
)

@kotlinx.serialization.Serializable
private data class ImageLocationUpdate(
    val imageLocationLat: Double? = null,
    val imageLocationLng: Double? = null,
)

@Serializable
private data class SpotMarkersResponse(
    val spots: List<SpotMarkerDto> = emptyList(),
)

@Serializable
private data class SpotMarkerDto(
    val id: String,
    val coordinates: List<Double>,
    val radiusMeters: Double = 15.0,
    val label: String? = null,
    val tags: List<SpotTag> = emptyList(),
    val affectedArea: AffectedArea? = null,
    val needsVerification: Boolean = false,
    val photoUrl: String? = null,
)

private fun SpotMarkerDto.toCommunityReport(): CommunityReport? {
    val reportId = id.toIntOrNull() ?: return null
    val lng = coordinates.getOrNull(0) ?: return null
    val lat = coordinates.getOrNull(1) ?: return null
    val now = Clock.System.now()
    val reportType = if (label?.contains("volunteer", ignoreCase = true) == true) {
        CommunityReportType.VOLUNTEER_CLEANUP
    } else {
        CommunityReportType.SIGHTING
    }
    return CommunityReport(
        id = reportId,
        type = reportType,
        coordinates = Coordinates(lng = lng, lat = lat),
        description = label?.takeUnless { it == "Sighting" },
        status = CommunityReportStatus.PENDING,
        affectedRadiusMeters = radiusMeters,
        affectedArea = affectedArea,
        tags = tags,
        photoUrl = photoUrl,
        needsVerification = needsVerification,
        createdAt = now,
        updatedAt = now,
    )
}
