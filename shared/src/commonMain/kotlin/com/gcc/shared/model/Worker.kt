@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class SignupRankings(
    val global: Int = 0,
    val state: Int = 0,
    val city: Int = 0,
    val neighborhood: Int = 0,
)

@Serializable
data class Worker(
    val id: Int,
    val name: String,
    val email: String,
    val phone: String,
    val emailVerified: Boolean = false,
    val address: WorkerAddress,
    val verification: WorkerVerification = WorkerVerification(),
    val stripe: WorkerStripe = WorkerStripe(),
    /** Ad-hoc payout routing preference: "stripe" | "check". */
    val payoutMethod: String = "stripe",
    val stats: WorkerStats = WorkerStats(),
    val earlyAdopterTier: EarlyAdopterTier = EarlyAdopterTier.STANDARD,
    val role: String = "worker",
    val referralCode: String? = null,
    val displayHandle: String? = null,
    val memberNumber: Int = 0,
    val signupRankings: SignupRankings = SignupRankings(),
    val notifications: WorkerNotifications = WorkerNotifications(),
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class WorkerAddress(
    val street: String,
    val city: String = "",
    val state: String = "",
    val zip: String,
    val neighborhood: String? = null,
    val isNorthLawndale: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

@Serializable
data class WorkerVerification(
    val status: VerificationStatus = VerificationStatus.PENDING,
    val governmentIdStatus: DocumentStatus = DocumentStatus.PENDING,
    val proofOfAddressStatus: DocumentStatus = DocumentStatus.PENDING,
    val reviewNotes: String? = null
)

@Serializable
data class WorkerStripe(
    val accountId: String? = null,
    val onboardingStatus: StripeOnboardingStatus = StripeOnboardingStatus.NOT_STARTED,
    val instantPayoutsEnabled: Boolean = false,
    val defaultPayoutMethod: String = "standard"
)

@Serializable
data class WorkerStats(
    val jobsCompleted: Int = 0,
    val totalEarnings: Int = 0,
    val averageRating: Double? = null,
    val lastJobAt: Instant? = null,
    val communityPoints: Int = 0,
)

@Serializable
data class WorkerNotifications(
    val pushEnabled: Boolean = false,
    val smsEnabled: Boolean = true,
    val emailEnabled: Boolean = true
)
