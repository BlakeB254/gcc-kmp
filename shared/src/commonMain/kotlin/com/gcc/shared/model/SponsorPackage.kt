package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SponsorPackage(
    val id: Int,
    val sponsorId: Int,
    val name: String,
    val status: SponsorPackageStatus = SponsorPackageStatus.DRAFT,
    val blocks: List<Int> = emptyList(),
    val blockCount: Int = 0,
    val frequency: SponsorFrequency = SponsorFrequency.ONE_TIME,
    val baseAmount: Int = 0,
    val addons: PackageAddons = PackageAddons(),
    val totalAmount: Int = 0,
    val isEnterprise: Boolean = false,
    val enterpriseTier: EnterpriseTier? = null,
    val enterpriseDetails: JsonObject? = null,
    val stripeInvoiceId: String? = null,
    val stripeSubscriptionId: String? = null,
    val paidAt: Instant? = null,
    val expiresAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class PackageAddons(
    val signage: Boolean = false,
    val signageAmount: Int = 0,
    val documentary: Boolean = false,
    val documentaryAmount: Int = 0,
    val socialMedia: Boolean = false,
    val socialMediaAmount: Int = 0,
    val impactReport: Boolean = false,
    val impactReportAmount: Int = 0,
    val customAddons: JsonObject? = null
)
