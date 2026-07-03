@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** A blog/news article published by GCC and surfaced inside the app. */
@Serializable
data class Article(
    val slug: String,
    val title: String,
    val excerpt: String,
    val author: String? = null,
    val publishedAt: Instant,
    val coverImageUrl: String? = null,
    val tags: List<String> = emptyList(),
    /**
     * Article body in markdown. The reader UI parses this client-side —
     * keep it to common subset (#/##/###, **, _, >, lists, links).
     */
    val bodyMarkdown: String,
    /**
     * Canonical website URL — used for "Read on the website" fallback
     * if any markdown rendering edge-case slips through the reader.
     */
    val webUrl: String? = null,
)

/** Lightweight summary used for the article list — omits the full body. */
@Serializable
data class ArticleSummary(
    val slug: String,
    val title: String,
    val excerpt: String,
    val author: String? = null,
    val publishedAt: Instant,
    val coverImageUrl: String? = null,
    val tags: List<String> = emptyList(),
)

/**
 * Body of POST /api/admin/workers/{id}/points — grants or corrects
 * community points on a worker. Negative amounts subtract.
 *
 * Server validates: amount integer, |amount| ≤ 10000, reason 1–280 chars,
 * action is one of [admin_grant, bug_report], caller must be admin.
 */
@Serializable
data class AdminGrantPointsRequest(
    val amount: Int,
    val reason: String,
    /** Action category captured in the points ledger. Defaults to admin_grant. */
    val action: String = "admin_grant",
)

/** Action categories the admin can pick from when granting points. */
enum class AdminGrantAction(
    val serverValue: String,
    val display: String,
    val defaultAmount: Int? = null,
) {
    ADMIN_GRANT("admin_grant", "General grant"),
    BUG_REPORT("bug_report", "Bug report", defaultAmount = 50),
}

@Serializable
data class WorkerPointsHistoryResponse(
    val entries: List<PointsLedgerEntry> = emptyList(),
)

/** Response from a successful admin points grant. */
@Serializable
data class AdminGrantPointsResponse(
    val ledgerEntryId: Int,
    val workerId: Int,
    val newTotal: Int,
    val amount: Int,
    val reason: String,
    val action: String = "admin_grant",
)
