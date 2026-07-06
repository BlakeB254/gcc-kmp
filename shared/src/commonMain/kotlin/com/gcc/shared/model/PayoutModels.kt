@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Ad-hoc admin payouts + worker payout-method preference.
 * Spec: docs/superpowers/specs/2026-07-06-admin-payouts-design.md
 *
 * method: "stripe" | "check". status: "processing" | "completed" | "failed"
 * | "check_pending" | "check_mailed". Kept as strings (not enums) so an
 * older client won't crash deserializing a status added later.
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
)

@Serializable
data class MarkMailedResponse(
    val payout: AdminPayout? = null,
)
