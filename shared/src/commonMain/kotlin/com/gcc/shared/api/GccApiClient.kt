package com.gcc.shared.api

import com.gcc.shared.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.timeout
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
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
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
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
        install(ContentNegotiation) { json(this@GccApiClient.json) }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 60_000L
        private const val SOCKET_TIMEOUT_MS = 60_000L

        /** Per-request override for large multipart/presigned uploads on slow cellular links. */
        private const val UPLOAD_REQUEST_TIMEOUT_MS = 180_000L
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

    /**
     * GET [path] and unwrap ApiResponse<T>.data, treating HTTP 404 as a
     * recoverable "not there yet" signal (returns null) rather than an
     * exception. For resources that are generated asynchronously/best-effort
     * after some other action completes (e.g. the block-completion-report,
     * which the server assembles in a fire-and-forget hook right after job
     * completion — see GET /api/jobs/{id}/completion-report), a missing row
     * is expected transient state, not an error the caller needs to surface.
     * Any other non-2xx status still throws, same as [get].
     */
    private suspend inline fun <reified T> getOrNull(
        path: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): T? {
        val response = httpClient.get("$baseUrl$path", block)
        if (response.status.value == 404) return null
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<ApiResponse<T>>().data
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

    /**
     * Fetch the Pillar 9 block-completion report for [jobId]. Returns null on
     * HTTP 404 — the server generates this report non-fatally right after job
     * completion, so it may not exist yet by the time a worker's client asks
     * for it; callers should treat null as "still generating" and retry
     * (see [com.gcc.app.viewmodel.RouteScanViewModel.fetchCompletionReport]),
     * not as a hard error.
     */
    suspend fun getCompletionReport(jobId: Int): BlockCompletionReport? =
        getOrNull("/api/jobs/$jobId/completion-report")

    // ── Worker Profile ────────────────────────────────────────────────

    suspend fun updateDisplayHandle(handle: String): Worker =
        patchJson("/api/workers/me/handle", mapOf("handle" to handle))

    // ── Account Settings ──────────────────────────────────────────────
    // Sensitive changes re-authenticate with the account password; the
    // server intentionally returns those failures as 400 (not 401/403) so
    // they never trip onUnauthorized/logout.

    suspend fun updateProfileName(name: String): UpdateProfileResponse =
        patchJson("/api/workers/me/profile", UpdateProfileRequest(name = name))

    suspend fun changePassword(currentPassword: String, newPassword: String): AccountActionResponse =
        postJson(
            "/api/workers/me/change-password",
            ChangePasswordRequest(currentPassword = currentPassword, newPassword = newPassword),
        )

    /** Step 1 of email change: sends a 6-digit code to [newEmail]. */
    suspend fun requestEmailChange(newEmail: String, password: String): EmailChangeStarted =
        postJson(
            "/api/workers/me/email/change-request",
            EmailChangeRequest(newEmail = newEmail, password = password),
        )

    /** Step 2 of email change: the account email switches on success. */
    suspend fun confirmEmailChange(code: String): EmailChangeConfirmed =
        postJson("/api/workers/me/email/confirm", EmailChangeConfirmRequest(code = code))

    suspend fun changePhone(newPhone: String, password: String): PhoneChangeResponse =
        postJson(
            "/api/workers/me/phone/change-request",
            PhoneChangeRequest(newPhone = newPhone, password = password),
        )

    /** Begin TOTP enrollment. 2FA stays off until [enableTwoFactor] confirms a code. */
    suspend fun setupTwoFactor(password: String): TwoFactorSetupResponse =
        postJson("/api/workers/me/2fa/setup", TwoFactorSetupRequest(password = password))

    /** Confirm enrollment; returns recovery codes (shown exactly once). */
    suspend fun enableTwoFactor(code: String): TwoFactorEnableResponse =
        postJson("/api/workers/me/2fa/enable", TwoFactorEnableRequest(code = code))

    suspend fun disableTwoFactor(password: String, code: String): AccountActionResponse =
        postJson("/api/workers/me/2fa/disable", TwoFactorDisableRequest(password = password, code = code))

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

    // ── Admin: Ad-hoc Payouts ──────────────────────────────────────────

    /**
     * One-off payout to a worker (bonus/correction). Routes by the
     * worker's payout method: Stripe transfer+payout, or queued as a
     * check to mail. idempotencyKey dedupes retries server-side.
     */
    suspend fun adminCreatePayout(workerId: Int, request: CreatePayoutRequest): CreatePayoutResponse =
        postJson("/api/admin/workers/$workerId/payout", request)

    /** Ad-hoc payouts, newest first. status=check_pending is the checks-to-mail queue. */
    suspend fun getAdminPayouts(status: String? = null, page: Int = 1, limit: Int = 50): AdminPayoutsResponse =
        get("/api/admin/payouts") {
            status?.let { parameter("status", it) }
            parameter("page", page)
            parameter("limit", limit)
        }

    suspend fun markPayoutMailed(payoutId: Int): MarkMailedResponse =
        postJson("/api/admin/payouts/$payoutId/mark-mailed")

    // ── Worker: payout method + history ────────────────────────────────

    suspend fun getPayoutMethod(): PayoutMethodInfo =
        get("/api/workers/me/payout-method")

    suspend fun updatePayoutMethod(request: UpdatePayoutMethodRequest): PayoutMethodInfo =
        putJson("/api/workers/me/payout-method", request)

    suspend fun getMyPayouts(): WorkerPayoutsResponse =
        get("/api/workers/me/payouts")

    // ── Admin: Block Proposals ──────────────────────────────────────────
    // AI-generated block candidates staged for review — see
    // site/src/app/api/admin/block-proposals/*. Nothing here is live/public
    // until approved, which inserts a real Block row on the server.

    suspend fun getAdminBlockProposals(status: BlockProposalStatus? = null): List<BlockProposal> {
        val path = if (status != null) {
            "/api/admin/block-proposals?status=${status.name.lowercase()}"
        } else {
            "/api/admin/block-proposals"
        }
        return get(path)
    }

    suspend fun approveBlockProposal(proposalId: Int): ApproveBlockProposalResponse {
        val response = httpClient.post("$baseUrl/api/admin/block-proposals/$proposalId/approve")
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<ApproveBlockProposalResponse>()
    }

    suspend fun rejectBlockProposal(proposalId: Int, reason: String? = null) {
        val response = httpClient.post("$baseUrl/api/admin/block-proposals/$proposalId/reject") {
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

    suspend fun getAdminStats(): AdminStatsResponse =
        get("/api/admin/analytics/stats")

    suspend fun getAdminActivity(limit: Int = 20): List<AdminActivityItem> =
        get("/api/admin/analytics/activity") { parameter("limit", limit) }

    suspend fun getAdminDailyStats(days: Int = 30): List<AdminDailyStats> =
        get("/api/admin/analytics/daily") { parameter("days", days) }

    // Workers
    suspend fun getAdminWorkers(search: String? = null, status: String? = null, page: Int = 1, limit: Int = 20, verificationStatus: String? = null): List<Worker> =
        get("/api/admin/workers") {
            search?.let { parameter("search", it) }
            status?.let { parameter("status", it) }
            verificationStatus?.let { parameter("verificationStatus", it) }
            parameter("page", page)
            parameter("limit", limit)
        }

    /** Every worker, across all pages — the admin list must match the
     *  overview count, so a single capped fetch is not enough. */
    suspend fun getAllAdminWorkers(verificationStatus: String? = null): List<Worker> =
        fetchAllPages { page, limit ->
            getAdminWorkers(page = page, limit = limit, verificationStatus = verificationStatus)
        }

    suspend fun getAdminWorker(workerId: Int): Worker =
        get("/api/admin/workers/$workerId")

    suspend fun getWorkerDocuments(workerId: Int): WorkerDocuments =
        get("/api/admin/workers/$workerId/documents")

    suspend fun verifyWorker(workerId: Int, request: VerifyWorkerRequest): Worker =
        postJson("/api/admin/workers/$workerId/verify", request)

    suspend fun bulkWorkerAction(request: BulkActionRequest): List<Worker> =
        postJson("/api/admin/workers/bulk-verify", request)

    suspend fun deleteWorker(workerId: Int): Unit =
        deleteUnit("/api/admin/workers/$workerId")

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
                innerBoundary = (obj["innerBoundary"] as? JsonObject)?.let {
                    runCatching { json.decodeFromJsonElement<GeoJSONMultiPolygon>(it) }.getOrNull()
                },
                center = decodePoint(location?.get("center") as? JsonObject),
                displayImage = str("displayImage"),
                imageLocationLat = str("imageLocationLat")?.toDoubleOrNull(),
                imageLocationLng = str("imageLocationLng")?.toDoubleOrNull(),
                fundingGoal = nestedStr(funding, "goal")?.toDoubleOrNull()?.toInt(),
                fundingCurrent = nestedStr(funding, "current")?.toDoubleOrNull()?.toInt() ?: 0,
                sponsorId = str("fundedById")?.toIntOrNull(),
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

    /**
     * Save block boundaries (admin boundary editor). v1 only sends
     * innerBoundary — the endpoint also accepts locationBoundary, but the
     * mobile editor never touches the outer ring. The response is a bare
     * `{ success, blockId, thinnedNeighbors }` object (no ApiResponse
     * envelope), so this bypasses [putJson].
     */
    /**
     * Run the OSM frontage generator for this route's block — the server
     * saves the generated islands AND returns them.
     */
    suspend fun regenerateInnerBoundary(routeId: Int): RegenerateInnerBoundaryResponse {
        val response = httpClient.post("$baseUrl/api/admin/routes/$routeId/regenerate-inner-boundary")
        if (!response.status.isSuccess()) {
            val body = runCatching { response.body<RegenerateInnerBoundaryResponse>() }.getOrNull()
            throw Exception(body?.error ?: "Island generation failed (${response.status.value})")
        }
        return response.body()
    }

    suspend fun updateBlockBoundaries(
        blockId: Int,
        request: UpdateBlockBoundariesRequest,
    ): UpdateBlockBoundariesResponse {
        val response = httpClient.put("$baseUrl/api/admin/blocks/$blockId/boundaries") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<UpdateBlockBoundariesResponse>()
    }

    // Bounties
    suspend fun getAdminBounties(): List<Bounty> =
        get("/api/admin/bounties")

    suspend fun claimBounty(bountyId: Int): Bounty =
        postJson("/api/bounties/$bountyId/claim")

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

    /**
     * Fetch the unified world-observation layer for the map — current
     * (non-superseded, non-excluded) observations from both route scans and
     * spot reports. Optional [bbox] is "minLng,minLat,maxLng,maxLat".
     */
    suspend fun getMapObservations(bbox: String? = null, since: String? = null): List<WorldObservation> {
        val response = httpClient.get("$baseUrl/api/map/observations") {
            bbox?.let { parameter("bbox", it) }
            since?.let { parameter("since", it) }
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return json.decodeFromString<WorldObservationsResponse>(response.bodyAsText()).observations
    }

    /** Batch-submit live AR-detected trash markers so they survive past the AR session. */
    suspend fun reportArDetectedMarkers(markers: List<ArDetectedMarkerRequest>): ArDetectedMarkerBatchResponse =
        postJson("/api/observations/ar-detected", ArDetectedMarkerBatchRequest(markers))

    // Upload spot photos (multipart form data), returns list of public URLs
    suspend fun uploadSpotPhotos(photos: List<ByteArray>): List<String> =
        uploadPhotosMultipart("$baseUrl/api/spots/upload", photos)

    /**
     * Upload verification documents (gov ID + proof of address photos) to the
     * private verification bucket. Same multipart shape as [uploadSpotPhotos];
     * the returned strings are private R2 object keys, not public URLs.
     */
    suspend fun uploadVerificationDocuments(photos: List<ByteArray>): List<String> =
        uploadPhotosMultipart("$baseUrl/api/workers/me/verification/upload", photos)

    private suspend fun uploadPhotosMultipart(url: String, photos: List<ByteArray>): List<String> {
        if (photos.isEmpty()) return emptyList()
        val token = tokenProvider()
        val response = httpClient.submitFormWithBinaryData(
            url = url,
            formData = formData {
                photos.forEachIndexed { index, bytes ->
                    append("photo_$index", bytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=\"photo_$index.jpg\"")
                    })
                }
            },
        ) {
            // Multi-photo multipart bodies on cellular need more than the
            // default request timeout.
            timeout { requestTimeoutMillis = UPLOAD_REQUEST_TIMEOUT_MS }
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

    // Admin: Update worker info (name, phone, address, tier)
    suspend fun updateAdminWorker(workerId: Int, updates: Map<String, String>): Worker =
        putJson("/api/admin/workers/$workerId", updates)

    // Auth: Refresh JWT token — returns new token + expiry. Uses siteClient (no
    // BearerAuth plugin) with an explicitly-passed token, rather than the usual
    // tokenProvider()-driven httpClient — going through the normal client would
    // call tokenProvider() again mid-request (which, for a refresh-triggering
    // caller, IS AuthTokenProvider.currentToken()), causing unbounded recursion
    // back into this exact call. Found and fixed after a task review traced it.
    suspend fun refreshToken(currentToken: String): AuthRefreshResponse {
        val response = siteClient.post("$baseUrl/api/auth/refresh") {
            header(HttpHeaders.Authorization, "Bearer $currentToken")
        }
        if (!response.status.isSuccess()) {
            // A 401/403 here means the buffer wasn't wide enough and the token is
            // genuinely dead -- fire the session-expired flow now rather than
            // waiting for the next real API call to also 401 one round-trip later.
            checkUnauthorized(response.status.value)
            throw Exception(httpErrorMessage(response.status.value, response.bodyAsText()))
        }
        return response.body<ApiResponse<AuthRefreshResponse>>().data
    }

    suspend fun reportCrash(report: CrashReportRequest): Unit =
        postJson("/api/crashes", report)

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

    suspend fun getMyPoints(): PointsSummary =
        get("/api/workers/me/points")

    // ── Scout & Clean Route Scan API ──────────────────────────────

    suspend fun createScan(req: CreateScanRequest, onBehalfOfWorkerId: Int? = null): RouteScan =
        postJson("/api/scans", if (onBehalfOfWorkerId != null) req.copy(onBehalfOfWorkerId = onBehalfOfWorkerId) else req)

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

    suspend fun retractCoverageEvents(request: RetractCoverageRequest): RetractCoverageResponse {
        val response = httpClient.post("$baseUrl/api/scans/coverage/retract") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<RetractCoverageResponse>()
    }

    /**
     * Confirm a previously-expected-dirty world observation is actually clean
     * ("rectification"). Flat-JSON endpoint, same pattern as coverage above.
     * The scan must belong to the authenticated worker.
     */
    suspend fun rectifyScanObservation(scanId: Int, lat: Double, lng: Double): RectifyResponse {
        val response = httpClient.post("$baseUrl/api/scans/$scanId/rectify") {
            contentType(ContentType.Application.Json)
            setBody(RectifyRequest(lat = lat, lng = lng))
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<RectifyResponse>()
    }

    /**
     * Environment-scan clean confirmation (no route scan in progress):
     * supersedes a prior dirty observation at this location and awards the
     * rectification bonus when one existed.
     */
    suspend fun rectifyObservationAtLocation(lat: Double, lng: Double): RectifyResponse {
        val response = httpClient.post("$baseUrl/api/observations/rectify") {
            contentType(ContentType.Application.Json)
            setBody(RectifyRequest(lat = lat, lng = lng))
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<RectifyResponse>()
    }

    /** Presigned R2 upload ticket for an environment-scan session artifact. */
    suspend fun createCaptureUploadUrl(request: CaptureUploadUrlRequest): CaptureUploadTicket {
        val response = httpClient.post("$baseUrl/api/captures/upload-url") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value == 501) {
            throw CaptureStorageNotConfiguredException()
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<ApiResponse<CaptureUploadTicket>>().data
    }

    /** PUT a blob straight to a presigned URL (no auth header — it's in the signature). */
    suspend fun uploadToPresignedUrl(url: String, bytes: ByteArray, contentType: String) {
        // A presigned URL is fully self-authenticating, and R2 rejects a
        // request that adds a SECOND auth mechanism: [httpClient]'s
        // BearerAuth plugin stamps the worker JWT on every request, and that
        // Authorization header alone makes R2 answer 400 InvalidRequest
        // ("Missing x-amz-content-sha256") — so this PUT must use the
        // token-free [siteClient].
        val response = siteClient.put {
            // Presigned URLs are signature-sensitive: ktor's normal URL
            // parsing DECODES and RE-ENCODES the path and query, changing
            // percent-encoding (e.g. %2F in X-Amz-Credential, %3A in the
            // key) — R2 then rejects the PUT with 400. Feed the builder the
            // raw ENCODED components so nothing is ever re-encoded. (curl
            // with the same ticket succeeds; this was why every app-side
            // R2 upload failed while server-side verification passed.)
            url { takeFromRawEncoded(url) }
            // Session-artifact blobs can be large; allow the longer upload timeout.
            timeout { requestTimeoutMillis = UPLOAD_REQUEST_TIMEOUT_MS }
            contentType(ContentType.parse(contentType))
            setBody(bytes)
        }
        if (!response.status.isSuccess()) {
            // Surface the storage host's error body — S3-style 400s carry the
            // decisive code (BadDigest, InvalidRequest, …) only in the XML.
            val detail = runCatching { response.bodyAsText() }.getOrNull()?.trim().orEmpty()
            throw Exception("presigned upload failed: ${response.status.value} ${detail.take(300)}".trim())
        }
    }

    suspend fun getMapCoverage(bbox: String, since: String? = null): MapCoverageResponse {
        val response = httpClient.get("$baseUrl/api/map/coverage") {
            parameter("bbox", bbox)
            since?.let { parameter("since", it) }
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<MapCoverageResponse>()
    }

    /**
     * Real OSM building footprints intersecting [bbox]
     * ("minLng,minLat,maxLng,maxLat") — backs the indoor gate's map-containment
     * signal. Server-side lazy-fetches from Overpass + Neon cache; each ring is
     * GeoJSON [lng,lat]. Callers treat failure as "no footprints" (fall back to
     * the hardcoded rectangles) — never block a scan on this.
     */
    suspend fun getBuildingFootprints(bbox: String): List<BuildingPolygon> {
        val response = httpClient.get("$baseUrl/api/map/buildings") {
            parameter("bbox", bbox)
        }
        if (!response.status.isSuccess()) {
            checkUnauthorized(response.status.value)
            val errorBody = response.bodyAsText()
            throw Exception(httpErrorMessage(response.status.value, errorBody))
        }
        return response.body<BuildingFootprintsResponse>().buildings
    }

    // ── Cleanliness Analytics ─────────────────────────────────────

    suspend fun getCityCleanlinessOverview(): CityCleanlinessOverview =
        get("/api/analytics/cleanliness")

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

@Serializable
data class ApproveBlockProposalResponse(
    val blockId: Int,
    val exclusionZonesCreated: Int = 0,
    val innerBoundaryGenerated: Boolean = false,
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


/** R2 storage isn't provisioned yet (501) — expected until env vars land. */
class CaptureStorageNotConfiguredException : Exception("capture storage not configured")
