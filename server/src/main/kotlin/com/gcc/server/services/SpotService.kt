package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.Bounties
import com.gcc.server.db.tables.CommunityReports
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
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
        isAdmin: Boolean = false,
    ): CommunityReport = dbQuery {
        val now = Clock.System.now()
        val coordsJson = kotlinx.serialization.json.buildJsonArray {
            add(JsonPrimitive(lng))
            add(JsonPrimitive(lat))
        }
        val id = CommunityReports.insert {
            it[reporterType] = if (isAdmin) "admin" else "worker"
            it[CommunityReports.reporterId] = reporterId
            it[reporterName] = if (isAdmin) "GCC" else ""
            it[CommunityReports.type] = type.name.lowercase()
            it[CommunityReports.coordinates] = listOf(lng, lat)
            it[CommunityReports.description] = description
            it[CommunityReports.status] = CommunityReportStatus.PENDING.name.lowercase()
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
                it[reviewedAt] = now
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

/**
 * Map a CommunityReports row to the shared CommunityReport model.
 * Fields that don't exist in the production Drizzle schema are defaulted.
 */
fun ResultRow.toReport(): CommunityReport {
    val coords = this[CommunityReports.coordinates]
    // Extract photo URLs from beforePhotos JSON (array of URL strings)
    val photos = try {
        val json = this[CommunityReports.beforePhotos]
        if (json is kotlinx.serialization.json.JsonArray) {
            json.mapNotNull { el ->
                (el as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
        } else emptyList()
    } catch (_: Exception) { emptyList<String>() }

    return CommunityReport(
        id = this[CommunityReports.id],
        reporterId = this[CommunityReports.reporterId],
        reporterName = this[CommunityReports.reporterName]?.takeIf { it.isNotBlank() },
        type = enumValueOfOrDefault(this[CommunityReports.type]),
        coordinates = Coordinates(
            lng = coords.getOrElse(0) { 0.0 },
            lat = coords.getOrElse(1) { 0.0 }
        ),
        description = this[CommunityReports.description],
        mediaUrls = photos,
        status = enumValueOfOrDefault(this[CommunityReports.status]),
        upvotes = this[CommunityReports.thankCount],
        verifiedBy = this[CommunityReports.reviewedById],
        resolvedAt = this[CommunityReports.reviewedAt],
        bountyId = null,
        observerRewardPoints = 0,
        aiAnalysis = null,
        createdAt = this[CommunityReports.createdAt],
        updatedAt = this[CommunityReports.updatedAt],
    )
}

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
