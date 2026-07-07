package com.gcc.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EarlyAdopterTier {
    @SerialName("founder") FOUNDER,
    @SerialName("pioneer") PIONEER,
    @SerialName("early") EARLY,
    @SerialName("standard") STANDARD
}

@Serializable
enum class VerificationStatus {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED,
    @SerialName("needs_info") NEEDS_INFO
}

@Serializable
enum class DocumentStatus {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED,
    @SerialName("resubmit") RESUBMIT
}

@Serializable
enum class StripeOnboardingStatus {
    @SerialName("not_started") NOT_STARTED,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("restricted") RESTRICTED
}

@Serializable
enum class BulkAction {
    @SerialName("verify") VERIFY,
    @SerialName("suspend") SUSPEND,
    @SerialName("activate") ACTIVATE,
    @SerialName("delete") DELETE,
}
