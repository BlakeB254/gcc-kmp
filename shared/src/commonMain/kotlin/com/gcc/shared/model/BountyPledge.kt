@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A single pledge toward a bounty. Multiple users can pledge on the same bounty,
 * creating a crowdfunded cleanup payout. The bounty's total amount is the sum
 * of all active pledges.
 */
@Serializable
data class BountyPledge(
    val id: Int,
    val bountyId: Int,
    val pledgerType: PosterType,
    val pledgerId: Int,
    val walletId: Int,
    val amount: Int,
    val status: PledgeStatus = PledgeStatus.ACTIVE,
    val renewalCount: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class AddPledgeRequest(
    val amount: Int,
)

@Serializable
data class BountyWithPledges(
    val bounty: Bounty,
    val pledges: List<BountyPledge> = emptyList(),
    val totalAmount: Int = 0,
    val pledgerCount: Int = 0,
)
