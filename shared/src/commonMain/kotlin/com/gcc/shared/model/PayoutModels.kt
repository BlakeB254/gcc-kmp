@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Wallet, cash-outs (withdrawals), admin wallet credits + worker
 * payout-method preference.
 *
 * Since the wallet-ledger rework (2026-07-13): earnings are credited to an
 * in-app wallet balance with NO Stripe Connect prerequisite; the worker
 * cashes out on their own schedule via POST /api/workers/me/withdraw.
 * Original ad-hoc payout spec: docs/superpowers/specs/2026-07-06-admin-payouts-design.md
 *
 * method: "stripe" | "check" (and "wallet" on admin-credit responses).
 * status: "processing" | "completed" | "failed" | "check_pending" |
 * "check_mailed". Kept as strings (not enums) so an older client won't crash
 * deserializing a value added later.
 */

@Serializable
data class MailingAddress(
    val name: String = "",
    val street: String = "",
    val city: String = "",
    val state: String = "",
    val zip: String = "",
) {
    val isComplete: Boolean
        get() = street.isNotBlank() && city.isNotBlank() && state.isNotBlank() && zip.isNotBlank()

    fun oneLine(): String = listOf(street, city, "$state $zip".trim())
        .filter { it.isNotBlank() }
        .joinToString(", ")
}

/** GET/PUT /api/workers/me/payout-method response. */
@Serializable
data class PayoutMethodInfo(
    val method: String = "stripe",
    /** Where a check would go today (override ?? home address); null = none usable. */
    val effectiveMailingAddress: MailingAddress? = null,
    /** Explicit override, when the worker set one. */
    val mailingAddressOverride: MailingAddress? = null,
)

@Serializable
data class UpdatePayoutMethodRequest(
    val method: String,
    val mailingAddress: MailingAddress? = null,
)

/** Row from GET /api/workers/me/payouts. */
@Serializable
data class WorkerPayout(
    val id: Int,
    val amountCents: Int,
    val note: String? = null,
    val method: String,
    val status: String,
    val checkMailedAt: Instant? = null,
    val createdAt: Instant,
)

@Serializable
data class WorkerPayoutsResponse(
    val payouts: List<WorkerPayout> = emptyList(),
)

/** Row from GET /api/admin/payouts (worker name/email joined). */
@Serializable
data class AdminPayout(
    val id: Int,
    val workerId: Int,
    val workerName: String? = null,
    val workerEmail: String? = null,
    val amountCents: Int,
    val note: String? = null,
    val method: String,
    val status: String,
    val stripeTransferId: String? = null,
    val initiatedByAdminId: Int = 0,
    /** Set on worker-initiated cash-outs (wallet withdrawals). */
    val initiatedByWorkerId: Int? = null,
    val mailingAddress: MailingAddress? = null,
    val checkMailedAt: Instant? = null,
    val createdAt: Instant? = null,
)

@Serializable
data class AdminPayoutsResponse(
    val payouts: List<AdminPayout> = emptyList(),
    val page: Int = 1,
    val limit: Int = 50,
)

@Serializable
data class CreatePayoutRequest(
    val amountCents: Int,
    val note: String? = null,
    /** Client-generated; the server dedupes on it so retries can't double-pay. */
    val idempotencyKey: String,
)

@Serializable
data class CreatePayoutResponse(
    val payout: AdminPayout? = null,
    val alreadyProcessed: Boolean = false,
    val persistFailed: Boolean = false,
    /** Worker's wallet balance after the credit (wallet-rework servers). */
    val balanceCents: Int = 0,
)

@Serializable
data class MarkMailedResponse(
    val payout: AdminPayout? = null,
)

// ─── Worker wallet (in-app balance + cash out) ──────────────────────────

/**
 * One row of the wallet audit ledger. amountCents is signed: positive =
 * money into the wallet (job earning, admin credit, refund), negative =
 * money out (cash out, adjustment).
 *
 * kind: "job_earning" | "admin_credit" | "withdrawal" |
 * "withdrawal_reversal" | "adjustment" — string, not enum, so older clients
 * tolerate new kinds.
 */
@Serializable
data class WalletEntry(
    val id: Int,
    val amountCents: Int,
    val kind: String,
    val jobId: Int? = null,
    val payoutId: Int? = null,
    val note: String? = null,
    val createdAt: Instant? = null,
)

/** GET /api/workers/me/wallet response. */
@Serializable
data class WorkerWallet(
    val balanceCents: Int = 0,
    val entries: List<WalletEntry> = emptyList(),
)

/**
 * POST /api/workers/me/withdraw request. method: "stripe" (direct deposit,
 * requires completed Connect onboarding) | "check" (mailed paper check,
 * requires a mailing address; $25 minimum).
 */
@Serializable
data class WithdrawRequest(
    val amountCents: Int,
    val method: String,
    /** Client-generated; the server dedupes on it so retries can't double-pay. */
    val idempotencyKey: String,
)

@Serializable
data class WithdrawResponse(
    val payout: WorkerPayout? = null,
    val alreadyProcessed: Boolean = false,
    /** Wallet balance after the hold. */
    val balanceCents: Int = 0,
)
