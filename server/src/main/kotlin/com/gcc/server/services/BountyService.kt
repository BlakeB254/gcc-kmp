package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Bounties
import com.gcc.server.db.tables.BountyPledges
import com.gcc.server.db.tables.Jobs
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class BountyService(
    private val walletService: WalletService,
) {

    /**
     * Create a new bounty with an initial pledge. Bounties expire after 24 hours
     * if unclaimed. The poster's first pledge deducts from their bounty fund.
     * Cannot be cancelled — only expires via the 24h renewal flow.
     */
    suspend fun createBounty(
        posterType: PosterType,
        posterId: Int,
        walletId: Int,
        title: String,
        description: String?,
        amount: Int,
        lat: Double,
        lng: Double,
        radiusMeters: Double = 200.0,
        communityArea: String? = null,
        neighborhood: String? = null,
        mediaUrls: List<String> = emptyList(),
    ): Bounty = dbQuery {
        val now = Clock.System.now()
        val expiresAt = now + 24.hours

        val bountyId = Bounties.insert {
            it[Bounties.posterType] = posterType.name.lowercase()
            it[Bounties.posterId] = posterId
            it[Bounties.walletId] = walletId
            it[Bounties.title] = title
            it[Bounties.description] = description
            it[Bounties.amount] = amount
            it[status] = BountyStatus.OPEN.name.lowercase()
            it[locationLat] = lat
            it[locationLng] = lng
            it[Bounties.radiusMeters] = radiusMeters
            it[Bounties.communityArea] = communityArea
            it[Bounties.neighborhood] = neighborhood
            it[Bounties.mediaUrls] = mediaUrls
            it[Bounties.expiresAt] = expiresAt
            it[createdAt] = now
            it[updatedAt] = now
        }[Bounties.id]

        // Create the initial pledge & deduct from wallet
        walletService.deductForBounty(walletId, amount, bountyId)

        BountyPledges.insert {
            it[BountyPledges.bountyId] = bountyId
            it[pledgerType] = posterType.name.lowercase()
            it[pledgerId] = posterId
            it[BountyPledges.walletId] = walletId
            it[BountyPledges.amount] = amount
            it[BountyPledges.status] = PledgeStatus.ACTIVE.name.lowercase()
            it[createdAt] = now
            it[updatedAt] = now
        }

        Bounties.selectAll().where { Bounties.id eq bountyId }.first().toBounty()
    }

    /**
     * Add a pledge to an existing open bounty (stackable bounties).
     * Multiple users can pile on to increase the total payout.
     * The bounty's amount is recalculated as the sum of all active pledges.
     */
    suspend fun addPledge(
        bountyId: Int,
        pledgerType: PosterType,
        pledgerId: Int,
        walletId: Int,
        amount: Int,
    ): BountyPledge = dbQuery {
        val bounty = Bounties.selectAll().where { Bounties.id eq bountyId }
            .firstOrNull()?.toBounty()
            ?: throw IllegalArgumentException("Bounty not found")

        require(bounty.status == BountyStatus.OPEN || bounty.status == BountyStatus.FIRST_REFUSAL) {
            "Can only add pledges to open bounties (current: ${bounty.status})"
        }

        // Deduct from pledger's wallet
        walletService.deductForBounty(walletId, amount, bountyId)

        val now = Clock.System.now()
        val pledgeId = BountyPledges.insert {
            it[BountyPledges.bountyId] = bountyId
            it[BountyPledges.pledgerType] = pledgerType.name.lowercase()
            it[BountyPledges.pledgerId] = pledgerId
            it[BountyPledges.walletId] = walletId
            it[BountyPledges.amount] = amount
            it[status] = PledgeStatus.ACTIVE.name.lowercase()
            it[createdAt] = now
            it[updatedAt] = now
        }[BountyPledges.id]

        // Recalculate bounty total from all active pledges
        recalculateBountyAmount(bountyId)

        BountyPledges.selectAll().where { BountyPledges.id eq pledgeId }
            .first().toBountyPledge()
    }

    /**
     * Get bounty with all its pledges (for detail view).
     */
    suspend fun getBountyWithPledges(bountyId: Int): BountyWithPledges? = dbQuery {
        val bounty = Bounties.selectAll().where { Bounties.id eq bountyId }
            .firstOrNull()?.toBounty() ?: return@dbQuery null

        val pledges = BountyPledges.selectAll()
            .where { BountyPledges.bountyId eq bountyId }
            .orderBy(BountyPledges.createdAt, SortOrder.ASC)
            .map { it.toBountyPledge() }

        BountyWithPledges(
            bounty = bounty,
            pledges = pledges,
            totalAmount = pledges.filter { it.status == PledgeStatus.ACTIVE }.sumOf { it.amount },
            pledgerCount = pledges.map { it.pledgerId }.distinct().size,
        )
    }

    /**
     * Auto-create a bounty from a spot report when it falls in a sponsor's funded zone.
     * Sets a 5-minute first-right-of-refusal window for the observer.
     * Also sets the 24-hour expiration.
     */
    suspend fun createFromSpotReport(
        reportId: Int,
        observerId: Int,
        sponsorId: Int,
        walletId: Int,
        amount: Int,
        lat: Double,
        lng: Double,
        mediaUrls: List<String> = emptyList(),
        communityArea: String? = null,
    ): Bounty = dbQuery {
        val now = Clock.System.now()
        val firstRefusalExpires = now + 5.minutes
        val bountyExpires = now + 24.hours

        val bountyId = Bounties.insert {
            it[posterType] = PosterType.SPONSOR.name.lowercase()
            it[posterId] = sponsorId
            it[Bounties.walletId] = walletId
            it[title] = "Cleanup needed"
            it[Bounties.amount] = amount
            it[status] = BountyStatus.FIRST_REFUSAL.name.lowercase()
            it[locationLat] = lat
            it[locationLng] = lng
            it[Bounties.communityArea] = communityArea
            it[triggerReportId] = reportId
            it[Bounties.observerId] = observerId
            it[firstRefusalExpiresAt] = firstRefusalExpires
            it[expiresAt] = bountyExpires
            it[Bounties.mediaUrls] = mediaUrls
            it[createdAt] = now
            it[updatedAt] = now
        }[Bounties.id]

        // Create the sponsor's pledge
        walletService.deductForBounty(walletId, amount, bountyId)

        BountyPledges.insert {
            it[BountyPledges.bountyId] = bountyId
            it[pledgerType] = PosterType.SPONSOR.name.lowercase()
            it[pledgerId] = sponsorId
            it[BountyPledges.walletId] = walletId
            it[BountyPledges.amount] = amount
            it[BountyPledges.status] = PledgeStatus.ACTIVE.name.lowercase()
            it[createdAt] = now
            it[updatedAt] = now
        }

        Bounties.selectAll().where { Bounties.id eq bountyId }.first().toBounty()
    }

    /**
     * Observer accepts the bounty within the 5-minute first-refusal window.
     * The 24-hour expiration timer PAUSES when claimed (expiresAt set to null).
     */
    suspend fun acceptFirstRefusal(bountyId: Int, observerId: Int): Bounty = dbQuery {
        val bounty = Bounties.selectAll().where { Bounties.id eq bountyId }
            .firstOrNull()?.toBounty()
            ?: throw IllegalArgumentException("Bounty not found")

        require(bounty.status == BountyStatus.FIRST_REFUSAL) {
            "Bounty is not in first-refusal state (current: ${bounty.status})"
        }
        require(bounty.observerId == observerId) {
            "Only the original observer can accept first refusal"
        }

        val now = Clock.System.now()
        bounty.firstRefusalExpiresAt?.let { expires ->
            require(now < expires) { "First-refusal window has expired" }
        }

        // Create a job for this bounty
        val jobId = Jobs.insert {
            it[status] = JobStatus.CLAIMED.name.lowercase()
            it[workerId] = observerId
            it[payoutAmount] = bounty.amount
            it[fundingSource] = "bounty"
            it[Jobs.bountyId] = bountyId
            it[Jobs.sponsorId] = bounty.posterId
            it[claimedAt] = now
            it[createdAt] = now
            it[updatedAt] = now
        }[Jobs.id]

        // Pause 24h timer on claim
        Bounties.update({ Bounties.id eq bountyId }) {
            it[status] = BountyStatus.CLAIMED.name.lowercase()
            it[claimedBy] = observerId
            it[Bounties.jobId] = jobId
            it[expiresAt] = null  // Timer paused
            it[updatedAt] = now
        }

        Bounties.selectAll().where { Bounties.id eq bountyId }.first().toBounty()
    }

    /**
     * Observer declines first refusal — release bounty to the public pool.
     * The 24-hour timer continues ticking.
     */
    suspend fun declineFirstRefusal(bountyId: Int): Bounty = dbQuery {
        val now = Clock.System.now()
        Bounties.update({ Bounties.id eq bountyId }) {
            it[status] = BountyStatus.OPEN.name.lowercase()
            it[firstRefusalExpiresAt] = null
            it[updatedAt] = now
        }
        Bounties.selectAll().where { Bounties.id eq bountyId }.first().toBounty()
    }

    /**
     * Claim an open bounty. Creates a Job and pauses the 24h timer.
     */
    suspend fun claimBounty(bountyId: Int, workerId: Int): Bounty = dbQuery {
        val bounty = Bounties.selectAll().where { Bounties.id eq bountyId }
            .firstOrNull()?.toBounty()
            ?: throw IllegalArgumentException("Bounty not found")

        require(bounty.status == BountyStatus.OPEN) {
            "Bounty is not available for claiming (current: ${bounty.status})"
        }

        // Check 24h expiration
        bounty.expiresAt?.let { expires ->
            val now = Clock.System.now()
            require(now < expires) { "Bounty has expired" }
        }

        val now = Clock.System.now()
        val jobId = Jobs.insert {
            it[status] = JobStatus.CLAIMED.name.lowercase()
            it[Jobs.workerId] = workerId
            it[payoutAmount] = bounty.amount
            it[fundingSource] = "bounty"
            it[Jobs.bountyId] = bountyId
            it[Jobs.sponsorId] = if (bounty.posterType == PosterType.SPONSOR) bounty.posterId else null
            it[claimedAt] = now
            it[createdAt] = now
            it[updatedAt] = now
        }[Jobs.id]

        // Pause 24h timer on claim
        Bounties.update({ Bounties.id eq bountyId }) {
            it[status] = BountyStatus.CLAIMED.name.lowercase()
            it[claimedBy] = workerId
            it[Bounties.jobId] = jobId
            it[expiresAt] = null  // Timer paused — cleaner doesn't need to worry
            it[updatedAt] = now
        }

        Bounties.selectAll().where { Bounties.id eq bountyId }.first().toBounty()
    }

    suspend fun completeBounty(
        bountyId: Int,
        workerId: Int,
        afterMediaUrls: List<String> = emptyList(),
    ): Bounty = dbQuery {
        val bounty = Bounties.selectAll().where { Bounties.id eq bountyId }
            .firstOrNull()?.toBounty()
            ?: throw IllegalArgumentException("Bounty not found")

        require(bounty.claimedBy == workerId) { "Bounty belongs to a different worker" }
        require(bounty.status == BountyStatus.CLAIMED || bounty.status == BountyStatus.IN_PROGRESS) {
            "Bounty must be claimed or in progress to complete"
        }

        val now = Clock.System.now()
        Bounties.update({ Bounties.id eq bountyId }) {
            it[status] = BountyStatus.COMPLETED.name.lowercase()
            it[completionMediaUrls] = afterMediaUrls
            it[updatedAt] = now
        }

        // Mark all active pledges as paid out
        BountyPledges.update({
            (BountyPledges.bountyId eq bountyId) and
                    (BountyPledges.status eq PledgeStatus.ACTIVE.name.lowercase())
        }) {
            it[status] = PledgeStatus.PAID_OUT.name.lowercase()
            it[updatedAt] = now
        }

        Bounties.selectAll().where { Bounties.id eq bountyId }.first().toBounty()
    }

    /**
     * Handle expired bounties (24-hour timer). Called by scheduled task.
     *
     * For expired bounties:
     * 1. Mark status as EXPIRED
     * 2. Each pledger will be notified to renew (handled client-side)
     *
     * Returns count of newly expired bounties.
     */
    suspend fun expireStale(): Int = dbQuery {
        val now = Clock.System.now()

        // Expire first-refusal bounties (5-min window)
        Bounties.update({
            (Bounties.status eq BountyStatus.FIRST_REFUSAL.name.lowercase()) and
                    (Bounties.firstRefusalExpiresAt lessEq now)
        }) {
            it[status] = BountyStatus.OPEN.name.lowercase()
            it[firstRefusalExpiresAt] = null
            it[updatedAt] = now
        }

        // Expire 24-hour bounties
        val expiredCount = Bounties.update({
            (Bounties.status eq BountyStatus.OPEN.name.lowercase()) and
                    (Bounties.expiresAt lessEq now)
        }) {
            it[status] = BountyStatus.EXPIRED.name.lowercase()
            it[updatedAt] = now
        }

        // Mark pledges on expired bounties as expired
        BountyPledges.update({
            BountyPledges.bountyId inSubQuery
                    Bounties.select(Bounties.id)
                        .where { Bounties.status eq BountyStatus.EXPIRED.name.lowercase() }
        }) {
            it[status] = PledgeStatus.EXPIRED.name.lowercase()
            it[updatedAt] = now
        }

        expiredCount
    }

    /**
     * Renew an expired bounty's pledge for another 24 hours.
     * If at least one pledge is renewed, the bounty re-opens.
     */
    suspend fun renewPledge(pledgeId: Int): BountyPledge = dbQuery {
        val pledge = BountyPledges.selectAll().where { BountyPledges.id eq pledgeId }
            .firstOrNull()?.toBountyPledge()
            ?: throw IllegalArgumentException("Pledge not found")

        require(pledge.status == PledgeStatus.EXPIRED) {
            "Can only renew expired pledges"
        }

        val now = Clock.System.now()

        // Re-activate the pledge
        BountyPledges.update({ BountyPledges.id eq pledgeId }) {
            it[status] = PledgeStatus.ACTIVE.name.lowercase()
            it[renewalCount] = pledge.renewalCount + 1
            it[updatedAt] = now
        }

        // Re-open the bounty with a fresh 24h timer
        recalculateBountyAmount(pledge.bountyId)
        Bounties.update({ Bounties.id eq pledge.bountyId }) {
            it[status] = BountyStatus.OPEN.name.lowercase()
            it[expiresAt] = now + 24.hours
            it[updatedAt] = now
        }

        BountyPledges.selectAll().where { BountyPledges.id eq pledgeId }
            .first().toBountyPledge()
    }

    /**
     * Decline renewal of an expired pledge — refund the pledger.
     * If no active pledges remain, the bounty stays expired.
     * If some pledges remain, recalculate the bounty amount.
     */
    suspend fun declineRenewal(pledgeId: Int): BountyPledge = dbQuery {
        val pledge = BountyPledges.selectAll().where { BountyPledges.id eq pledgeId }
            .firstOrNull()?.toBountyPledge()
            ?: throw IllegalArgumentException("Pledge not found")

        require(pledge.status == PledgeStatus.EXPIRED) {
            "Can only decline renewal for expired pledges"
        }

        val now = Clock.System.now()

        // Refund the pledge amount
        walletService.refund(pledge.walletId, FundType.BOUNTY, pledge.amount, pledge.bountyId)

        BountyPledges.update({ BountyPledges.id eq pledgeId }) {
            it[status] = PledgeStatus.REFUNDED.name.lowercase()
            it[updatedAt] = now
        }

        // Recalculate remaining bounty amount
        val remainingActive = BountyPledges.selectAll()
            .where {
                (BountyPledges.bountyId eq pledge.bountyId) and
                        (BountyPledges.status eq PledgeStatus.ACTIVE.name.lowercase())
            }
            .sumOf { it[BountyPledges.amount] }

        if (remainingActive > 0) {
            Bounties.update({ Bounties.id eq pledge.bountyId }) {
                it[amount] = remainingActive
                it[updatedAt] = now
            }
        }
        // If no active pledges, bounty stays expired

        BountyPledges.selectAll().where { BountyPledges.id eq pledgeId }
            .first().toBountyPledge()
    }

    suspend fun getOpenBounties(
        lat: Double? = null,
        lng: Double? = null,
        radiusMeters: Double? = null,
        city: String? = null,
        communityArea: String? = null,
        limit: Int = 50,
    ): List<Bounty> = dbQuery {
        val query = Bounties.selectAll()
            .where { Bounties.status eq BountyStatus.OPEN.name.lowercase() }

        city?.let { query.andWhere { Bounties.city eq it } }
        communityArea?.let { query.andWhere { Bounties.communityArea eq it } }

        val bounties = query
            .orderBy(Bounties.amount, SortOrder.DESC) // highest bounty first
            .limit(limit)
            .map { it.toBounty() }

        if (lat != null && lng != null && radiusMeters != null) {
            bounties.filter {
                haversineMeters(lat, lng, it.locationLat, it.locationLng) <= radiusMeters
            }
        } else {
            bounties
        }
    }

    suspend fun getById(id: Int): Bounty? = dbQuery {
        Bounties.selectAll().where { Bounties.id eq id }
            .firstOrNull()?.toBounty()
    }

    suspend fun getBountiesByPoster(posterType: PosterType, posterId: Int): List<Bounty> = dbQuery {
        Bounties.selectAll()
            .where {
                (Bounties.posterType eq posterType.name.lowercase()) and
                        (Bounties.posterId eq posterId)
            }
            .orderBy(Bounties.createdAt, SortOrder.DESC)
            .map { it.toBounty() }
    }

    /**
     * Get pledges by pledger (for "my pledges" view).
     */
    suspend fun getPledgesByPledger(pledgerId: Int): List<BountyPledge> = dbQuery {
        BountyPledges.selectAll()
            .where { BountyPledges.pledgerId eq pledgerId }
            .orderBy(BountyPledges.createdAt, SortOrder.DESC)
            .map { it.toBountyPledge() }
    }

    // ---- Private helpers ----

    /**
     * Recalculate bounty amount from sum of active pledges.
     */
    private fun recalculateBountyAmount(bountyId: Int) {
        val total = BountyPledges.selectAll()
            .where {
                (BountyPledges.bountyId eq bountyId) and
                        (BountyPledges.status eq PledgeStatus.ACTIVE.name.lowercase())
            }
            .sumOf { it[BountyPledges.amount] }

        val now = Clock.System.now()
        Bounties.update({ Bounties.id eq bountyId }) {
            it[amount] = total
            it[updatedAt] = now
        }
    }
}

fun ResultRow.toBounty(): Bounty {
    val pledgeCount = try {
        BountyPledges.selectAll()
            .where {
                (BountyPledges.bountyId eq this@toBounty[Bounties.id]) and
                        (BountyPledges.status eq PledgeStatus.ACTIVE.name.lowercase())
            }
            .count().toInt()
    } catch (_: Exception) { 1 }

    return Bounty(
        id = this[Bounties.id],
        posterType = enumValueOfOrDefault(this[Bounties.posterType]),
        posterId = this[Bounties.posterId],
        walletId = this[Bounties.walletId],
        title = this[Bounties.title],
        description = this[Bounties.description],
        amount = this[Bounties.amount],
        status = enumValueOfOrDefault(this[Bounties.status]),
        locationLat = this[Bounties.locationLat],
        locationLng = this[Bounties.locationLng],
        radiusMeters = this[Bounties.radiusMeters],
        city = this[Bounties.city],
        communityArea = this[Bounties.communityArea],
        neighborhood = this[Bounties.neighborhood],
        triggerReportId = this[Bounties.triggerReportId],
        observerId = this[Bounties.observerId],
        firstRefusalExpiresAt = this[Bounties.firstRefusalExpiresAt],
        claimedBy = this[Bounties.claimedBy],
        jobId = this[Bounties.jobId],
        mediaUrls = this[Bounties.mediaUrls],
        completionMediaUrls = this[Bounties.completionMediaUrls],
        aiAnalysis = this[Bounties.aiAnalysis],
        expiresAt = this[Bounties.expiresAt],
        pledgeCount = pledgeCount,
        createdAt = this[Bounties.createdAt],
        updatedAt = this[Bounties.updatedAt],
    )
}

fun ResultRow.toBountyPledge(): BountyPledge = BountyPledge(
    id = this[BountyPledges.id],
    bountyId = this[BountyPledges.bountyId],
    pledgerType = enumValueOfOrDefault(this[BountyPledges.pledgerType]),
    pledgerId = this[BountyPledges.pledgerId],
    walletId = this[BountyPledges.walletId],
    amount = this[BountyPledges.amount],
    status = enumValueOfOrDefault(this[BountyPledges.status]),
    renewalCount = this[BountyPledges.renewalCount],
    createdAt = this[BountyPledges.createdAt],
    updatedAt = this[BountyPledges.updatedAt],
)
