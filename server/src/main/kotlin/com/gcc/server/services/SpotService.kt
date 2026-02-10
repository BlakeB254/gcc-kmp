package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Bounties
import com.gcc.server.db.tables.CommunityReports
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import kotlin.math.*

class SpotService {

    suspend fun createReport(
        reporterId: Int,
        type: CommunityReportType,
        lat: Double,
        lng: Double,
        description: String?,
        mediaUrls: List<String>,
    ): CommunityReport = dbQuery {
        val now = Clock.System.now()
        val id = CommunityReports.insert {
            it[CommunityReports.reporterId] = reporterId
            it[CommunityReports.type] = type.name.lowercase()
            it[CommunityReports.lat] = lat
            it[CommunityReports.lng] = lng
            it[CommunityReports.description] = description
            it[CommunityReports.mediaUrls] = mediaUrls
            it[CommunityReports.status] = CommunityReportStatus.PENDING.name.lowercase()
            it[CommunityReports.observerRewardPoints] = 10
            it[createdAt] = now
            it[updatedAt] = now
        }[CommunityReports.id]

        CommunityReports.selectAll().where { CommunityReports.id eq id }
            .first().toReport()
    }

    suspend fun getReportsByArea(
        lat: Double,
        lng: Double,
        radiusMeters: Double = 500.0,
        limit: Int = 50,
    ): List<CommunityReport> = dbQuery {
        // Fetch all reports then filter by Haversine distance (no PostGIS)
        CommunityReports.selectAll()
            .orderBy(CommunityReports.createdAt, SortOrder.DESC)
            .limit(500)
            .map { it.toReport() }
            .filter { haversineMeters(lat, lng, it.coordinates.lat, it.coordinates.lng) <= radiusMeters }
            .take(limit)
    }

    suspend fun getReportsByWorker(workerId: Int, limit: Int = 50): List<CommunityReport> = dbQuery {
        CommunityReports.selectAll()
            .where { CommunityReports.reporterId eq workerId }
            .orderBy(CommunityReports.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toReport() }
    }

    suspend fun getById(id: Int): CommunityReport? = dbQuery {
        CommunityReports.selectAll().where { CommunityReports.id eq id }
            .firstOrNull()?.toReport()
    }

    suspend fun updateStatus(id: Int, status: CommunityReportStatus): CommunityReport? = dbQuery {
        val now = Clock.System.now()
        CommunityReports.update({ CommunityReports.id eq id }) {
            it[CommunityReports.status] = status.name.lowercase()
            it[updatedAt] = now
            if (status == CommunityReportStatus.RESOLVED) {
                it[resolvedAt] = now
            }
        }
        CommunityReports.selectAll().where { CommunityReports.id eq id }
            .firstOrNull()?.toReport()
    }

    /**
     * Check if the given coordinates fall within any open bounty zones.
     * Uses Haversine distance for point-in-circle matching.
     */
    suspend fun checkBountyZones(lat: Double, lng: Double): List<Bounty> = dbQuery {
        Bounties.selectAll()
            .where { Bounties.status eq BountyStatus.OPEN.name.lowercase() }
            .map { it.toBounty() }
            .filter { bounty ->
                haversineMeters(lat, lng, bounty.locationLat, bounty.locationLng) <= bounty.radiusMeters
            }
    }

    suspend fun getAll(limit: Int = 50, offset: Long = 0): List<CommunityReport> = dbQuery {
        CommunityReports.selectAll()
            .orderBy(CommunityReports.createdAt, SortOrder.DESC)
            .limit(limit).offset(offset)
            .map { it.toReport() }
    }
}

fun ResultRow.toReport(): CommunityReport = CommunityReport(
    id = this[CommunityReports.id],
    reporterId = this[CommunityReports.reporterId],
    type = enumValueOfOrDefault(this[CommunityReports.type]),
    coordinates = Coordinates(lng = this[CommunityReports.lng], lat = this[CommunityReports.lat]),
    description = this[CommunityReports.description],
    mediaUrls = this[CommunityReports.mediaUrls],
    status = enumValueOfOrDefault(this[CommunityReports.status]),
    upvotes = this[CommunityReports.upvotes],
    verifiedBy = this[CommunityReports.verifiedBy],
    resolvedAt = this[CommunityReports.resolvedAt],
    bountyId = this[CommunityReports.bountyId],
    observerRewardPoints = this[CommunityReports.observerRewardPoints],
    aiAnalysis = this[CommunityReports.aiAnalysis],
    createdAt = this[CommunityReports.createdAt],
    updatedAt = this[CommunityReports.updatedAt],
)

/**
 * Haversine formula — distance in meters between two lat/lng points on Earth.
 */
fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0 // Earth radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
