package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Blocks
import com.gcc.server.db.tables.Jobs
import com.gcc.server.db.tables.RouteCommunityQueue
import com.gcc.server.db.tables.RouteJobOffers
import com.gcc.server.db.tables.RouteReleaseBatches
import com.gcc.server.db.tables.RouteReleaseRoutes
import com.gcc.server.db.tables.Routes
import com.gcc.server.db.tables.ServiceAreas
import com.gcc.server.db.tables.Workers
import com.gcc.shared.model.AdminRouteReleaseRequest
import com.gcc.shared.model.Job
import com.gcc.shared.model.RouteJobOfferStatus
import com.gcc.shared.model.RouteOffer
import com.gcc.shared.model.RouteReleaseBatch
import com.gcc.shared.model.RouteReleaseProcessResult
import com.gcc.shared.model.RouteReleaseStatus
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.math.max

class RouteQueueService(
    private val routeService: RouteService,
) {
    suspend fun listReleases(limit: Int = 50): List<RouteReleaseBatch> = dbQuery {
        RouteReleaseBatches.selectAll()
            .orderBy(RouteReleaseBatches.releaseAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toRouteReleaseBatch() }
    }

    suspend fun processDueBatches(now: Instant = Clock.System.now(), limit: Int = 25): List<RouteReleaseProcessResult> {
        val dueBatchIds = dbQuery {
            RouteReleaseBatches.selectAll()
                .where { RouteReleaseBatches.status inList listOf("scheduled", "active") }
                .orderBy(RouteReleaseBatches.nextWaveAt, SortOrder.ASC)
                .limit(limit)
                .filter { row ->
                    val nextWaveAt = row[RouteReleaseBatches.nextWaveAt]
                    nextWaveAt?.let { it <= now } ?: (row[RouteReleaseBatches.releaseAt] <= now)
                }
                .map { it[RouteReleaseBatches.id] }
        }

        return dueBatchIds.map { processBatch(it, now) }
    }

    suspend fun createRelease(request: AdminRouteReleaseRequest, adminWorkerId: Int): RouteReleaseBatch = dbQuery {
        require(request.routeIds.isNotEmpty()) { "At least one route is required" }
        require(request.offerWindowMinutes in 15..1440) { "offerWindowMinutes must be between 15 and 1440" }

        val now = Clock.System.now()
        val serviceArea = ServiceAreas.selectAll()
            .where { ServiceAreas.id eq request.serviceAreaId }
            .firstOrNull()
            ?: throw IllegalArgumentException("Service area not found")

        val communityName = request.communityName
            ?: serviceArea[ServiceAreas.name]
        val eligibleZipCodes = request.eligibleZipCodes.ifEmpty {
            defaultEligibleZipCodes(communityName)
        }
        require(eligibleZipCodes.isNotEmpty()) {
            "eligibleZipCodes are required for this service area"
        }

        val status = if (request.releaseAt <= now) "active" else "scheduled"
        val releaseId = RouteReleaseBatches.insert {
            it[RouteReleaseBatches.serviceAreaId] = request.serviceAreaId
            it[RouteReleaseBatches.name] = request.name
            it[RouteReleaseBatches.communityName] = communityName
            it[RouteReleaseBatches.eligibleZipCodes] = eligibleZipCodes.map { zip -> zip.trim() }
            it[RouteReleaseBatches.status] = status
            it[RouteReleaseBatches.releaseAt] = request.releaseAt
            it[RouteReleaseBatches.nextWaveAt] = request.releaseAt
            it[RouteReleaseBatches.offerWindowMinutes] = request.offerWindowMinutes
            it[RouteReleaseBatches.timezone] = request.timezone
            it[RouteReleaseBatches.daytimeWindowStart] = request.daytimeWindowStart
            it[RouteReleaseBatches.daytimeWindowEnd] = request.daytimeWindowEnd
            it[RouteReleaseBatches.cleanupStartsAt] = request.cleanupStartsAt
            it[RouteReleaseBatches.cleanupEndsAt] = request.cleanupEndsAt
            it[RouteReleaseBatches.targetSlots] = request.routeIds.distinct().size
            it[RouteReleaseBatches.waveNumber] = 0
            it[createdById] = adminWorkerId
            it[RouteReleaseBatches.createdAt] = now
            it[RouteReleaseBatches.updatedAt] = now
        }[RouteReleaseBatches.id]

        for (routeId in request.routeIds.distinct()) {
            val route = Routes.selectAll()
                .where { Routes.id eq routeId }
                .firstOrNull()
                ?: throw IllegalArgumentException("Route $routeId not found")

            val blockId = Blocks.select(Blocks.id)
                .where { Blocks.routeId eq routeId }
                .firstOrNull()
                ?.get(Blocks.id)
                ?: Blocks.insert {
                    it[Blocks.code] = null
                    it[Blocks.name] = route[Routes.displayName]
                    it[Blocks.serviceAreaId] = route[Routes.serviceAreaId]
                    it[Blocks.routeId] = route[Routes.id]
                    it[Blocks.status] = "funded"
                    it[Blocks.fundingGoal] = route[Routes.payoutAmount] ?: "10000"
                    it[Blocks.fundingCurrent] = route[Routes.payoutAmount] ?: "10000"
                    it[Blocks.createdAt] = now
                    it[Blocks.updatedAt] = now
                }[Blocks.id]

            val activeJob = Jobs.selectAll()
                .where {
                    (Jobs.blockId eq blockId) and
                        (Jobs.status inList listOf("claimed", "in_progress", "pending_review"))
                }
                .firstOrNull()
            if (activeJob != null) {
                throw IllegalStateException("Route ${route[Routes.code]} already has an active job")
            }

            val jobId = Jobs.selectAll()
                .where {
                    (Jobs.blockId eq blockId) and
                        (Jobs.status eq "available") and
                        Jobs.workerId.isNull()
                }
                .firstOrNull()
                ?.get(Jobs.id)
                ?: Jobs.insert {
                    it[Jobs.blockId] = blockId
                    it[Jobs.workerId] = null
                    it[Jobs.status] = "available"
                    it[Jobs.payoutAmount] = route[Routes.payoutAmount] ?: "10000"
                    it[Jobs.payoutStatus] = "pending"
                    it[Jobs.createdAt] = now
                    it[Jobs.updatedAt] = now
                }[Jobs.id]

            RouteReleaseRoutes.insert {
                it[RouteReleaseRoutes.batchId] = releaseId
                it[RouteReleaseRoutes.routeId] = routeId
                it[RouteReleaseRoutes.jobId] = jobId
                it[RouteReleaseRoutes.status] = "open"
                it[RouteReleaseRoutes.createdAt] = now
                it[RouteReleaseRoutes.updatedAt] = now
            }
        }

        RouteReleaseBatches.selectAll()
            .where { RouteReleaseBatches.id eq releaseId }
            .first()
            .toRouteReleaseBatch()
    }

    suspend fun processBatch(batchId: Int, now: Instant = Clock.System.now()): RouteReleaseProcessResult = dbQuery {
        val batchRow = RouteReleaseBatches.selectAll()
            .where { RouteReleaseBatches.id eq batchId }
            .firstOrNull()
            ?: throw IllegalArgumentException("Release batch not found")

        val status = batchRow[RouteReleaseBatches.status]
        if (status == "cancelled" || status == "completed") {
            return@dbQuery processResult(batchRow, expiredOffers = 0, offersSent = 0)
        }

        if (now < batchRow[RouteReleaseBatches.releaseAt]) {
            return@dbQuery processResult(batchRow, expiredOffers = 0, offersSent = 0)
        }

        val nextWaveAt = batchRow[RouteReleaseBatches.nextWaveAt]
        if (nextWaveAt != null && now < nextWaveAt) {
            return@dbQuery processResult(batchRow, expiredOffers = 0, offersSent = 0)
        }

        val daytimeNow = nextDaytimeInstant(
            now = now,
            timezone = batchRow[RouteReleaseBatches.timezone],
            start = batchRow[RouteReleaseBatches.daytimeWindowStart],
            end = batchRow[RouteReleaseBatches.daytimeWindowEnd],
        )
        if (daytimeNow > now) {
            RouteReleaseBatches.update({ RouteReleaseBatches.id eq batchId }) {
                it[RouteReleaseBatches.status] = "active"
                it[RouteReleaseBatches.nextWaveAt] = daytimeNow
                it[RouteReleaseBatches.updatedAt] = now
            }
            val refreshed = RouteReleaseBatches.selectAll().where { RouteReleaseBatches.id eq batchId }.first()
            return@dbQuery processResult(refreshed, expiredOffers = 0, offersSent = 0)
        }

        val expiredOfferIds = RouteJobOffers.selectAll()
            .where {
                (RouteJobOffers.batchId eq batchId) and
                    (RouteJobOffers.status eq "pending") and
                    (RouteJobOffers.expiresAt lessEq now)
            }
            .map { it[RouteJobOffers.id] to it[RouteJobOffers.workerId] }

        for ((offerId, workerId) in expiredOfferIds) {
            RouteJobOffers.update({ RouteJobOffers.id eq offerId }) {
                it[RouteJobOffers.status] = "expired"
                it[RouteJobOffers.respondedAt] = now
                it[RouteJobOffers.updatedAt] = now
            }
            markQueueMissed(batchRow[RouteReleaseBatches.serviceAreaId], workerId, "expired", now)
        }

        val openSlots = RouteReleaseRoutes.selectAll()
            .where { (RouteReleaseRoutes.batchId eq batchId) and (RouteReleaseRoutes.status eq "open") }
            .count()
            .toInt()

        val activeOffers = RouteJobOffers.selectAll()
            .where {
                (RouteJobOffers.batchId eq batchId) and
                    (RouteJobOffers.status eq "pending") and
                    (RouteJobOffers.expiresAt greater now)
            }
            .count()
            .toInt()

        if (openSlots == 0) {
            RouteReleaseBatches.update({ RouteReleaseBatches.id eq batchId }) {
                it[RouteReleaseBatches.status] = "completed"
                it[RouteReleaseBatches.nextWaveAt] = null
                it[RouteReleaseBatches.updatedAt] = now
            }
            val refreshed = RouteReleaseBatches.selectAll().where { RouteReleaseBatches.id eq batchId }.first()
            return@dbQuery processResult(refreshed, expiredOffers = expiredOfferIds.size, offersSent = 0)
        }

        val capacity = max(0, openSlots - activeOffers)
        if (capacity == 0) {
            val earliestExpiry = RouteJobOffers.select(RouteJobOffers.expiresAt)
                .where {
                    (RouteJobOffers.batchId eq batchId) and
                        (RouteJobOffers.status eq "pending")
                }
                .orderBy(RouteJobOffers.expiresAt, SortOrder.ASC)
                .firstOrNull()
                ?.get(RouteJobOffers.expiresAt)
            RouteReleaseBatches.update({ RouteReleaseBatches.id eq batchId }) {
                it[RouteReleaseBatches.status] = "active"
                it[RouteReleaseBatches.nextWaveAt] = earliestExpiry
                it[RouteReleaseBatches.updatedAt] = now
            }
            val refreshed = RouteReleaseBatches.selectAll().where { RouteReleaseBatches.id eq batchId }.first()
            return@dbQuery processResult(refreshed, expiredOffers = expiredOfferIds.size, offersSent = 0)
        }

        val candidates = selectCandidates(batchRow, now, capacity)
        val waveNumber = batchRow[RouteReleaseBatches.waveNumber] + 1
        val expiresAt = addDaytimeMinutes(
            startInstant = now,
            minutes = batchRow[RouteReleaseBatches.offerWindowMinutes],
            timezone = batchRow[RouteReleaseBatches.timezone],
            windowStart = batchRow[RouteReleaseBatches.daytimeWindowStart],
            windowEnd = batchRow[RouteReleaseBatches.daytimeWindowEnd],
        )

        for (candidate in candidates) {
            RouteJobOffers.insert {
                it[RouteJobOffers.batchId] = batchId
                it[RouteJobOffers.workerId] = candidate.workerId
                it[RouteJobOffers.waveNumber] = waveNumber
                it[RouteJobOffers.status] = "pending"
                it[RouteJobOffers.offerToken] = UUID.randomUUID().toString().replace("-", "")
                it[RouteJobOffers.offeredAt] = now
                it[RouteJobOffers.expiresAt] = expiresAt
                it[RouteJobOffers.createdAt] = now
                it[RouteJobOffers.updatedAt] = now
            }

            RouteCommunityQueue.update({
                (RouteCommunityQueue.serviceAreaId eq batchRow[RouteReleaseBatches.serviceAreaId]) and
                    (RouteCommunityQueue.workerId eq candidate.workerId)
            }) {
                it[RouteCommunityQueue.lastOpportunityAt] = now
                it[RouteCommunityQueue.lastOfferedBatchId] = batchId
                it[RouteCommunityQueue.lastOfferStatus] = "pending"
                it[RouteCommunityQueue.opportunitiesCount] = candidate.opportunitiesCount + 1
                it[RouteCommunityQueue.carryoverReleaseAfter] = null
                it[RouteCommunityQueue.carryoverAttempts] = if (candidate.isCarryover) candidate.carryoverAttempts + 1 else 0
                it[RouteCommunityQueue.updatedAt] = now
            }
        }

        RouteReleaseBatches.update({ RouteReleaseBatches.id eq batchId }) {
            it[RouteReleaseBatches.status] = "active"
            it[RouteReleaseBatches.waveNumber] = waveNumber
            it[RouteReleaseBatches.nextWaveAt] = expiresAt
            it[RouteReleaseBatches.updatedAt] = now
        }

        val refreshed = RouteReleaseBatches.selectAll().where { RouteReleaseBatches.id eq batchId }.first()
        processResult(refreshed, expiredOffers = expiredOfferIds.size, offersSent = candidates.size)
    }

    suspend fun getWorkerOffers(workerId: Int): List<RouteOffer> = dbQuery {
        expireWorkerOffers(workerId, Clock.System.now())
        val rows = RouteJobOffers.selectAll()
            .where {
                (RouteJobOffers.workerId eq workerId) and
                    (RouteJobOffers.status inList listOf("pending", "accepted"))
            }
            .orderBy(RouteJobOffers.expiresAt, SortOrder.ASC)
            .map { it }

        rows.map { offer ->
            val batch = RouteReleaseBatches.selectAll()
                .where { RouteReleaseBatches.id eq offer[RouteJobOffers.batchId] }
                .firstOrNull()
            val routeIds = RouteReleaseRoutes.select(RouteReleaseRoutes.routeId)
                .where {
                    (RouteReleaseRoutes.batchId eq offer[RouteJobOffers.batchId]) and
                        (RouteReleaseRoutes.status eq "open")
                }
                .map { it[RouteReleaseRoutes.routeId] }
            offer.toRouteOffer(
                batch = batch?.toRouteReleaseBatch(),
                availableRoutes = routeIds.mapNotNull { routeService.getById(it) },
            )
        }
    }

    suspend fun acceptOffer(offerId: Int, workerId: Int, routeId: Int): Job = dbQuery {
        acceptOfferInternal(offerId = offerId, workerId = workerId, routeId = routeId, now = Clock.System.now())
    }

    suspend fun declineOffer(offerId: Int, workerId: Int, reason: String?): RouteOffer = dbQuery {
        val now = Clock.System.now()
        val offer = RouteJobOffers.selectAll()
            .where { RouteJobOffers.id eq offerId }
            .firstOrNull()
            ?: throw IllegalArgumentException("Route offer not found")
        if (offer[RouteJobOffers.workerId] != workerId) {
            throw IllegalStateException("Route offer belongs to a different worker")
        }
        if (offer[RouteJobOffers.status] != "pending") {
            throw IllegalStateException("Only pending offers can be declined")
        }

        RouteJobOffers.update({ RouteJobOffers.id eq offerId }) {
            it[RouteJobOffers.status] = "declined"
            it[RouteJobOffers.declineReason] = reason
            it[RouteJobOffers.respondedAt] = now
            it[RouteJobOffers.updatedAt] = now
        }
        val batch = RouteReleaseBatches.selectAll()
            .where { RouteReleaseBatches.id eq offer[RouteJobOffers.batchId] }
            .first()
        markQueueMissed(batch[RouteReleaseBatches.serviceAreaId], workerId, "declined", now)

        RouteJobOffers.selectAll()
            .where { RouteJobOffers.id eq offerId }
            .first()
            .toRouteOffer(batch = batch.toRouteReleaseBatch())
    }

    suspend fun claimReleasedJob(jobId: Int, workerId: Int, isAdmin: Boolean): Job? = dbQuery {
        val releaseRoute = RouteReleaseRoutes.selectAll()
            .where { RouteReleaseRoutes.jobId eq jobId }
            .firstOrNull()
            ?: return@dbQuery null

        if (isAdmin) {
            val now = Clock.System.now()
            val updated = claimJobRow(jobId, workerId, now)
            RouteReleaseRoutes.update({ RouteReleaseRoutes.id eq releaseRoute[RouteReleaseRoutes.id] }) {
                it[RouteReleaseRoutes.status] = "claimed"
                it[RouteReleaseRoutes.acceptedByWorkerId] = workerId
                it[RouteReleaseRoutes.acceptedAt] = now
                it[RouteReleaseRoutes.updatedAt] = now
            }
            return@dbQuery updated
        }

        val offer = RouteJobOffers.selectAll()
            .where {
                (RouteJobOffers.batchId eq releaseRoute[RouteReleaseRoutes.batchId]) and
                    (RouteJobOffers.workerId eq workerId) and
                    (RouteJobOffers.status eq "pending") and
                    (RouteJobOffers.expiresAt greater Clock.System.now())
            }
            .orderBy(RouteJobOffers.expiresAt, SortOrder.ASC)
            .firstOrNull()
            ?: throw IllegalStateException("This route is reserved for the current community queue wave")

        acceptOfferInternal(
            offerId = offer[RouteJobOffers.id],
            workerId = workerId,
            routeId = releaseRoute[RouteReleaseRoutes.routeId],
            now = Clock.System.now(),
        )
    }

    suspend fun declineReleasedJob(jobId: Int, workerId: Int, isAdmin: Boolean, reason: String?): Job? = dbQuery {
        val releaseRoute = RouteReleaseRoutes.selectAll()
            .where { RouteReleaseRoutes.jobId eq jobId }
            .firstOrNull()
            ?: return@dbQuery null
        val job = Jobs.selectAll().where { Jobs.id eq jobId }.firstOrNull()
            ?: throw IllegalArgumentException("Job not found")
        if (!isAdmin && job[Jobs.workerId] != workerId) {
            throw IllegalStateException("Job belongs to a different worker")
        }
        if (job[Jobs.status] != "claimed") {
            throw IllegalStateException("Only assigned jobs can be declined before start")
        }

        val now = Clock.System.now()
        Jobs.update({ Jobs.id eq jobId }) {
            it[Jobs.workerId] = null
            it[Jobs.status] = "available"
            it[Jobs.claimedAt] = null
            it[Jobs.payoutStatus] = "pending"
            it[Jobs.updatedAt] = now
        }
        RouteReleaseRoutes.update({ RouteReleaseRoutes.id eq releaseRoute[RouteReleaseRoutes.id] }) {
            it[RouteReleaseRoutes.status] = "open"
            it[RouteReleaseRoutes.acceptedByWorkerId] = null
            it[RouteReleaseRoutes.acceptedOfferId] = null
            it[RouteReleaseRoutes.acceptedAt] = null
            it[RouteReleaseRoutes.updatedAt] = now
        }
        releaseRoute[RouteReleaseRoutes.acceptedOfferId]?.let { offerId ->
            RouteJobOffers.update({ RouteJobOffers.id eq offerId }) {
                it[RouteJobOffers.status] = "declined"
                it[RouteJobOffers.declineReason] = reason
                it[RouteJobOffers.respondedAt] = now
                it[RouteJobOffers.updatedAt] = now
            }
        }
        val batch = RouteReleaseBatches.selectAll()
            .where { RouteReleaseBatches.id eq releaseRoute[RouteReleaseRoutes.batchId] }
            .first()
        markQueueMissed(batch[RouteReleaseBatches.serviceAreaId], workerId, "declined", now)
        Jobs.selectAll().where { Jobs.id eq jobId }.first().toJob(routeId = releaseRoute[RouteReleaseRoutes.routeId])
    }

    private fun acceptOfferInternal(offerId: Int, workerId: Int, routeId: Int, now: Instant): Job {
        val offer = RouteJobOffers.selectAll()
            .where { RouteJobOffers.id eq offerId }
            .firstOrNull()
            ?: throw IllegalArgumentException("Route offer not found")
        if (offer[RouteJobOffers.workerId] != workerId) {
            throw IllegalStateException("Route offer belongs to a different worker")
        }
        if (offer[RouteJobOffers.status] != "pending") {
            throw IllegalStateException("Only pending route offers can be accepted")
        }
        if (offer[RouteJobOffers.expiresAt] <= now) {
            RouteJobOffers.update({ RouteJobOffers.id eq offerId }) {
                it[RouteJobOffers.status] = "expired"
                it[RouteJobOffers.respondedAt] = now
                it[RouteJobOffers.updatedAt] = now
            }
            throw IllegalStateException("This route offer has expired")
        }

        val batch = RouteReleaseBatches.selectAll()
            .where { RouteReleaseBatches.id eq offer[RouteJobOffers.batchId] }
            .first()
        ensureEligibleWorker(workerId, batch)

        val activeJob = Jobs.selectAll()
            .where {
                (Jobs.workerId eq workerId) and
                    (Jobs.status inList listOf("claimed", "in_progress", "pending_review"))
            }
            .firstOrNull()
        if (activeJob != null) {
            throw IllegalStateException("You already have an active route assignment")
        }

        val releaseRoute = RouteReleaseRoutes.selectAll()
            .where {
                (RouteReleaseRoutes.batchId eq offer[RouteJobOffers.batchId]) and
                    (RouteReleaseRoutes.routeId eq routeId) and
                    (RouteReleaseRoutes.status eq "open")
            }
            .firstOrNull()
            ?: throw IllegalStateException("That route has already been claimed")

        val job = claimJobRow(releaseRoute[RouteReleaseRoutes.jobId], workerId, now)

        RouteReleaseRoutes.update({ RouteReleaseRoutes.id eq releaseRoute[RouteReleaseRoutes.id] }) {
            it[RouteReleaseRoutes.status] = "claimed"
            it[RouteReleaseRoutes.acceptedByWorkerId] = workerId
            it[RouteReleaseRoutes.acceptedOfferId] = offerId
            it[RouteReleaseRoutes.acceptedAt] = now
            it[RouteReleaseRoutes.updatedAt] = now
        }
        RouteJobOffers.update({ RouteJobOffers.id eq offerId }) {
            it[RouteJobOffers.status] = "accepted"
            it[RouteJobOffers.selectedRouteId] = routeId
            it[RouteJobOffers.selectedJobId] = releaseRoute[RouteReleaseRoutes.jobId]
            it[RouteJobOffers.respondedAt] = now
            it[RouteJobOffers.updatedAt] = now
        }
        RouteJobOffers.update({
            (RouteJobOffers.batchId eq offer[RouteJobOffers.batchId]) and
                (RouteJobOffers.workerId eq workerId) and
                (RouteJobOffers.status eq "pending")
        }) {
            it[RouteJobOffers.status] = "cancelled"
            it[RouteJobOffers.respondedAt] = now
            it[RouteJobOffers.updatedAt] = now
        }
        RouteCommunityQueue.update({
            (RouteCommunityQueue.serviceAreaId eq batch[RouteReleaseBatches.serviceAreaId]) and
                (RouteCommunityQueue.workerId eq workerId)
        }) {
            it[RouteCommunityQueue.lastOfferStatus] = "accepted"
            it[RouteCommunityQueue.carryoverReleaseAfter] = null
            it[RouteCommunityQueue.carryoverAttempts] = 0
            it[RouteCommunityQueue.updatedAt] = now
        }

        val remainingOpen = RouteReleaseRoutes.selectAll()
            .where {
                (RouteReleaseRoutes.batchId eq offer[RouteJobOffers.batchId]) and
                    (RouteReleaseRoutes.status eq "open")
            }
            .count()
        if (remainingOpen == 0L) {
            RouteReleaseBatches.update({ RouteReleaseBatches.id eq offer[RouteJobOffers.batchId] }) {
                it[RouteReleaseBatches.status] = "completed"
                it[RouteReleaseBatches.nextWaveAt] = null
                it[RouteReleaseBatches.updatedAt] = now
            }
        }

        return job
    }

    private fun claimJobRow(jobId: Int, workerId: Int, now: Instant): Job {
        val job = Jobs.selectAll().where { Jobs.id eq jobId }.firstOrNull()
            ?: throw IllegalArgumentException("Job not found")
        if (job[Jobs.status] != "available" || job[Jobs.workerId] != null) {
            throw IllegalStateException("This route has already been claimed")
        }
        Jobs.update({ Jobs.id eq jobId }) {
            it[Jobs.workerId] = workerId
            it[Jobs.status] = "claimed"
            it[Jobs.claimedAt] = now
            it[Jobs.updatedAt] = now
        }
        val routeId = Blocks.select(Blocks.routeId)
            .where { Blocks.id eq job[Jobs.blockId] }
            .firstOrNull()
            ?.get(Blocks.routeId)
        return Jobs.selectAll().where { Jobs.id eq jobId }.first().toJob(routeId = routeId)
    }

    private fun selectCandidates(batch: ResultRow, now: Instant, limit: Int): List<QueueCandidate> {
        val zipCodes = batch[RouteReleaseBatches.eligibleZipCodes].map { it.trim() }
        val eligibleWorkers = Workers.selectAll()
            .where {
                (Workers.verificationStatus eq "approved") and
                    (Workers.verificationProofOfAddressStatus eq "approved") and
                    (Workers.addressZip inList zipCodes)
            }
            .map { it }

        if (eligibleWorkers.isEmpty()) return emptyList()

        val workerIds = eligibleWorkers.map { it[Workers.id] }
        val activeWorkerIds = Jobs.select(Jobs.workerId)
            .where {
                Jobs.workerId.isNotNull() and
                    (Jobs.workerId inList workerIds) and
                    (Jobs.status inList listOf("claimed", "in_progress", "pending_review"))
            }
            .mapNotNull { it[Jobs.workerId] }
            .toSet()
        val alreadyOffered = RouteJobOffers.select(RouteJobOffers.workerId)
            .where { RouteJobOffers.batchId eq batch[RouteReleaseBatches.id] }
            .map { it[RouteJobOffers.workerId] }
            .toSet()

        val availableWorkers = eligibleWorkers
            .filter { it[Workers.id] !in activeWorkerIds && it[Workers.id] !in alreadyOffered }
        if (availableWorkers.isEmpty()) return emptyList()

        syncQueueRows(
            serviceAreaId = batch[RouteReleaseBatches.serviceAreaId],
            workers = availableWorkers,
            now = now,
        )

        val queueRows = RouteCommunityQueue.selectAll()
            .where {
                (RouteCommunityQueue.serviceAreaId eq batch[RouteReleaseBatches.serviceAreaId]) and
                    (RouteCommunityQueue.workerId inList availableWorkers.map { it[Workers.id] })
            }
            .map {
                val carryoverAfter = it[RouteCommunityQueue.carryoverReleaseAfter]
                val isCarryover = carryoverAfter != null &&
                    carryoverAfter <= now &&
                    it[RouteCommunityQueue.carryoverAttempts] < 1
                QueueCandidate(
                    workerId = it[RouteCommunityQueue.workerId],
                    isCarryover = isCarryover,
                    lastOpportunityAt = it[RouteCommunityQueue.lastOpportunityAt],
                    queueRank = it[RouteCommunityQueue.queueRank]?.toDoubleOrNull(),
                    opportunitiesCount = it[RouteCommunityQueue.opportunitiesCount],
                    carryoverAttempts = it[RouteCommunityQueue.carryoverAttempts],
                )
            }

        return queueRows.sortedWith(
            compareByDescending<QueueCandidate> { it.isCarryover }
                .thenBy { it.lastOpportunityAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
                .thenBy { it.queueRank ?: Double.MAX_VALUE }
                .thenBy { it.workerId },
        ).take(limit)
    }

    private fun syncQueueRows(serviceAreaId: Int, workers: List<ResultRow>, now: Instant) {
        val existingWorkerIds = RouteCommunityQueue.select(RouteCommunityQueue.workerId)
            .where {
                (RouteCommunityQueue.serviceAreaId eq serviceAreaId) and
                    (RouteCommunityQueue.workerId inList workers.map { it[Workers.id] })
            }
            .map { it[RouteCommunityQueue.workerId] }
            .toSet()

        for (worker in workers) {
            val workerId = worker[Workers.id]
            if (workerId in existingWorkerIds) continue
            val queueRank = worker[Workers.signupRankingsNeighborhood]
                ?: worker[Workers.signupRankingsCity]
                ?: worker[Workers.signupRankingsGlobal]
            RouteCommunityQueue.insert {
                it[RouteCommunityQueue.serviceAreaId] = serviceAreaId
                it[RouteCommunityQueue.workerId] = workerId
                it[RouteCommunityQueue.queueRank] = queueRank
                it[RouteCommunityQueue.opportunitiesCount] = 0
                it[RouteCommunityQueue.carryoverAttempts] = 0
                it[RouteCommunityQueue.createdAt] = now
                it[RouteCommunityQueue.updatedAt] = now
            }
        }
    }

    private fun ensureEligibleWorker(workerId: Int, batch: ResultRow) {
        val worker = Workers.selectAll().where { Workers.id eq workerId }.firstOrNull()
            ?: throw IllegalArgumentException("Worker not found")
        val eligibleZipCodes = batch[RouteReleaseBatches.eligibleZipCodes].map { it.trim() }
        if (worker[Workers.verificationStatus] != "approved" ||
            worker[Workers.verificationProofOfAddressStatus] != "approved"
        ) {
            throw IllegalStateException("You must be verified before accepting community route work")
        }
        if (worker[Workers.addressZip] !in eligibleZipCodes) {
            throw IllegalStateException("This route release is only for verified ${batch[RouteReleaseBatches.communityName]} residents")
        }
    }

    private fun markQueueMissed(serviceAreaId: Int, workerId: Int, status: String, now: Instant) {
        val row = RouteCommunityQueue.selectAll()
            .where {
                (RouteCommunityQueue.serviceAreaId eq serviceAreaId) and
                    (RouteCommunityQueue.workerId eq workerId)
            }
            .firstOrNull()
        val shouldCarryover = (row?.get(RouteCommunityQueue.carryoverAttempts) ?: 0) == 0
        RouteCommunityQueue.update({
            (RouteCommunityQueue.serviceAreaId eq serviceAreaId) and
                (RouteCommunityQueue.workerId eq workerId)
        }) {
            it[RouteCommunityQueue.lastOfferStatus] = status
            it[RouteCommunityQueue.carryoverReleaseAfter] = if (shouldCarryover) now else null
            it[RouteCommunityQueue.updatedAt] = now
        }
    }

    private fun expireWorkerOffers(workerId: Int, now: Instant) {
        val expired = RouteJobOffers.selectAll()
            .where {
                (RouteJobOffers.workerId eq workerId) and
                    (RouteJobOffers.status eq "pending") and
                    (RouteJobOffers.expiresAt lessEq now)
            }
            .map { it[RouteJobOffers.id] to it[RouteJobOffers.batchId] }
        for ((offerId, batchId) in expired) {
            RouteJobOffers.update({ RouteJobOffers.id eq offerId }) {
                it[RouteJobOffers.status] = "expired"
                it[RouteJobOffers.respondedAt] = now
                it[RouteJobOffers.updatedAt] = now
            }
            val batch = RouteReleaseBatches.selectAll()
                .where { RouteReleaseBatches.id eq batchId }
                .firstOrNull()
            if (batch != null) {
                markQueueMissed(batch[RouteReleaseBatches.serviceAreaId], workerId, "expired", now)
            }
        }
    }

    private fun processResult(batch: ResultRow, expiredOffers: Int, offersSent: Int): RouteReleaseProcessResult {
        val batchId = batch[RouteReleaseBatches.id]
        val openSlots = RouteReleaseRoutes.selectAll()
            .where { (RouteReleaseRoutes.batchId eq batchId) and (RouteReleaseRoutes.status eq "open") }
            .count()
            .toInt()
        val activeOffers = RouteJobOffers.selectAll()
            .where {
                (RouteJobOffers.batchId eq batchId) and
                    (RouteJobOffers.status eq "pending") and
                    (RouteJobOffers.expiresAt greater Clock.System.now())
            }
            .count()
            .toInt()
        return RouteReleaseProcessResult(
            batchId = batchId,
            status = enumValueOfOrDefault(batch[RouteReleaseBatches.status]),
            waveNumber = batch[RouteReleaseBatches.waveNumber],
            expiredOffers = expiredOffers,
            openSlots = openSlots,
            activeOffers = activeOffers,
            offersSent = offersSent,
            nextWaveAt = batch[RouteReleaseBatches.nextWaveAt],
        )
    }
}

private data class QueueCandidate(
    val workerId: Int,
    val isCarryover: Boolean,
    val lastOpportunityAt: Instant?,
    val queueRank: Double?,
    val opportunitiesCount: Int,
    val carryoverAttempts: Int,
)

fun ResultRow.toRouteReleaseBatch(): RouteReleaseBatch = RouteReleaseBatch(
    id = this[RouteReleaseBatches.id],
    serviceAreaId = this[RouteReleaseBatches.serviceAreaId],
    name = this[RouteReleaseBatches.name],
    communityName = this[RouteReleaseBatches.communityName],
    eligibleZipCodes = this[RouteReleaseBatches.eligibleZipCodes],
    status = enumValueOfOrDefault(this[RouteReleaseBatches.status]),
    releaseAt = this[RouteReleaseBatches.releaseAt],
    nextWaveAt = this[RouteReleaseBatches.nextWaveAt],
    offerWindowMinutes = this[RouteReleaseBatches.offerWindowMinutes],
    timezone = this[RouteReleaseBatches.timezone],
    daytimeWindowStart = this[RouteReleaseBatches.daytimeWindowStart],
    daytimeWindowEnd = this[RouteReleaseBatches.daytimeWindowEnd],
    cleanupStartsAt = this[RouteReleaseBatches.cleanupStartsAt],
    cleanupEndsAt = this[RouteReleaseBatches.cleanupEndsAt],
    targetSlots = this[RouteReleaseBatches.targetSlots],
    waveNumber = this[RouteReleaseBatches.waveNumber],
    createdAt = this[RouteReleaseBatches.createdAt],
    updatedAt = this[RouteReleaseBatches.updatedAt],
)

fun ResultRow.toRouteOffer(
    batch: RouteReleaseBatch? = null,
    availableRoutes: List<com.gcc.shared.model.Route> = emptyList(),
): RouteOffer = RouteOffer(
    id = this[RouteJobOffers.id],
    batchId = this[RouteJobOffers.batchId],
    workerId = this[RouteJobOffers.workerId],
    waveNumber = this[RouteJobOffers.waveNumber],
    status = enumValueOfOrDefault(this[RouteJobOffers.status]),
    offeredAt = this[RouteJobOffers.offeredAt],
    expiresAt = this[RouteJobOffers.expiresAt],
    respondedAt = this[RouteJobOffers.respondedAt],
    selectedRouteId = this[RouteJobOffers.selectedRouteId],
    selectedJobId = this[RouteJobOffers.selectedJobId],
    declineReason = this[RouteJobOffers.declineReason],
    batch = batch,
    availableRoutes = availableRoutes,
)

private fun defaultEligibleZipCodes(communityName: String): List<String> =
    when (communityName.trim().lowercase()) {
        "north lawndale" -> listOf("60623", "60624")
        else -> emptyList()
    }

private fun nextDaytimeInstant(now: Instant, timezone: String, start: String, end: String): Instant {
    val zone = ZoneId.of(timezone)
    val current = now.toJavaInstant().atZone(zone)
    val startTime = LocalTime.parse(start)
    val endTime = LocalTime.parse(end)
    val todayStart = current.toLocalDate().atTime(startTime).atZone(zone)
    val todayEnd = current.toLocalDate().atTime(endTime).atZone(zone)
    val adjusted = when {
        current.isBefore(todayStart) -> todayStart
        !current.isBefore(todayEnd) -> current.toLocalDate().plusDays(1).atTime(startTime).atZone(zone)
        else -> current
    }
    return adjusted.toInstant().toKotlinInstant()
}

private fun addDaytimeMinutes(
    startInstant: Instant,
    minutes: Int,
    timezone: String,
    windowStart: String,
    windowEnd: String,
): Instant {
    val zone = ZoneId.of(timezone)
    val startTime = LocalTime.parse(windowStart)
    val endTime = LocalTime.parse(windowEnd)
    var remaining = minutes.toLong()
    var cursor = nextDaytimeInstant(startInstant, timezone, windowStart, windowEnd)
        .toJavaInstant()
        .atZone(zone)

    while (remaining > 0) {
        val windowClose = cursor.toLocalDate().atTime(endTime).atZone(zone)
        val minutesUntilClose = java.time.Duration.between(cursor, windowClose).toMinutes()
        if (minutesUntilClose >= remaining) {
            return cursor.plusMinutes(remaining).toInstant().toKotlinInstant()
        }
        remaining -= max(0, minutesUntilClose)
        val nextDate: LocalDate = cursor.toLocalDate().plusDays(1)
        cursor = ZonedDateTime.of(LocalDateTime.of(nextDate, startTime), zone)
    }
    return cursor.toInstant().toKotlinInstant()
}
