package com.gcc.shared.model

import com.gcc.shared.constants.GccConstants
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

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
    val stats: WorkerStats = WorkerStats(),
    val earlyAdopterTier: EarlyAdopterTier = EarlyAdopterTier.STANDARD,
    val role: String = "worker",
    val referralCode: String? = null,
    val notifications: WorkerNotifications = WorkerNotifications(),
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class WorkerAddress(
    val street: String,
    val city: String = GccConstants.DEFAULT_CITY,
    val state: String = GccConstants.DEFAULT_STATE,
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
    val lastJobAt: Instant? = null
)

@Serializable
data class WorkerNotifications(
    val pushEnabled: Boolean = false,
    val smsEnabled: Boolean = true,
    val emailEnabled: Boolean = true
)
