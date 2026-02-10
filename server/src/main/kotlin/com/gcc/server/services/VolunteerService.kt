package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.CommunityReports
import com.gcc.server.db.tables.VolunteerCleanups
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*

class VolunteerService {

    /**
     * Start a volunteer cleanup from an existing spot report.
     */
    suspend fun startCleanup(
        reportId: Int,
        workerId: Int,
        beforeMediaUrls: List<String>,
    ): VolunteerCleanup = dbQuery {
        // Fetch the parent report's location
        val report = CommunityReports.selectAll()
            .where { CommunityReports.id eq reportId }
            .firstOrNull()?.toReport()
            ?: throw IllegalArgumentException("Report not found")

        val now = Clock.System.now()
        val id = VolunteerCleanups.insert {
            it[VolunteerCleanups.reportId] = reportId
            it[VolunteerCleanups.workerId] = workerId
            it[VolunteerCleanups.beforeMediaUrls] = beforeMediaUrls
            it[afterMediaUrls] = emptyList()
            it[locationLat] = report.coordinates.lat
            it[locationLng] = report.coordinates.lng
            it[startedAt] = now
            it[status] = VolunteerCleanupStatus.IN_PROGRESS.name.lowercase()
            it[createdAt] = now
            it[updatedAt] = now
        }[VolunteerCleanups.id]

        // Update the report type to volunteer_cleanup
        CommunityReports.update({ CommunityReports.id eq reportId }) {
            it[type] = CommunityReportType.VOLUNTEER_CLEANUP.name.lowercase()
            it[updatedAt] = now
        }

        VolunteerCleanups.selectAll().where { VolunteerCleanups.id eq id }
            .first().toVolunteerCleanup()
    }

    /**
     * Complete a volunteer cleanup with after-photos and optional trash heap photo.
     */
    suspend fun completeCleanup(
        cleanupId: Int,
        workerId: Int,
        afterMediaUrls: List<String>,
        trashHeapMediaUrl: String? = null,
    ): VolunteerCleanup = dbQuery {
        val cleanup = VolunteerCleanups.selectAll()
            .where { VolunteerCleanups.id eq cleanupId }
            .firstOrNull()?.toVolunteerCleanup()
            ?: throw IllegalArgumentException("Cleanup not found")

        require(cleanup.workerId == workerId) { "Cleanup belongs to a different worker" }
        require(cleanup.status == VolunteerCleanupStatus.IN_PROGRESS) {
            "Cleanup must be in progress to complete"
        }

        val now = Clock.System.now()
        val durationMins = ((now - cleanup.startedAt).inWholeMinutes).toInt()

        VolunteerCleanups.update({ VolunteerCleanups.id eq cleanupId }) {
            it[VolunteerCleanups.afterMediaUrls] = afterMediaUrls
            it[VolunteerCleanups.trashHeapMediaUrl] = trashHeapMediaUrl
            it[completedAt] = now
            it[durationMinutes] = durationMins
            it[status] = VolunteerCleanupStatus.COMPLETED.name.lowercase()
            it[pointsAwarded] = 50 // volunteer cleanup points
            it[updatedAt] = now
        }

        // Mark the parent report as resolved
        val cleanupData = VolunteerCleanups.selectAll()
            .where { VolunteerCleanups.id eq cleanupId }
            .first().toVolunteerCleanup()

        CommunityReports.update({ CommunityReports.id eq cleanupData.reportId }) {
            it[CommunityReports.status] = CommunityReportStatus.RESOLVED.name.lowercase()
            it[resolvedAt] = now
            it[updatedAt] = now
        }

        cleanupData
    }

    /**
     * Verify that a cleanup's GPS location matches the report location (within 50m).
     */
    suspend fun verifyLocation(cleanupId: Int, lat: Double, lng: Double): Boolean = dbQuery {
        val cleanup = VolunteerCleanups.selectAll()
            .where { VolunteerCleanups.id eq cleanupId }
            .firstOrNull()?.toVolunteerCleanup()
            ?: throw IllegalArgumentException("Cleanup not found")

        val distance = haversineMeters(lat, lng, cleanup.locationLat, cleanup.locationLng)
        val verified = distance <= 50.0

        if (verified) {
            VolunteerCleanups.update({ VolunteerCleanups.id eq cleanupId }) {
                it[locationVerified] = true
                it[updatedAt] = Clock.System.now()
            }
        }

        verified
    }

    suspend fun getByWorker(workerId: Int, limit: Int = 50): List<VolunteerCleanup> = dbQuery {
        VolunteerCleanups.selectAll()
            .where { VolunteerCleanups.workerId eq workerId }
            .orderBy(VolunteerCleanups.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toVolunteerCleanup() }
    }

    suspend fun getById(id: Int): VolunteerCleanup? = dbQuery {
        VolunteerCleanups.selectAll().where { VolunteerCleanups.id eq id }
            .firstOrNull()?.toVolunteerCleanup()
    }
}

fun ResultRow.toVolunteerCleanup(): VolunteerCleanup = VolunteerCleanup(
    id = this[VolunteerCleanups.id],
    reportId = this[VolunteerCleanups.reportId],
    workerId = this[VolunteerCleanups.workerId],
    beforeMediaUrls = this[VolunteerCleanups.beforeMediaUrls],
    afterMediaUrls = this[VolunteerCleanups.afterMediaUrls],
    trashHeapMediaUrl = this[VolunteerCleanups.trashHeapMediaUrl],
    aiTrashAnalysis = this[VolunteerCleanups.aiTrashAnalysis],
    locationLat = this[VolunteerCleanups.locationLat],
    locationLng = this[VolunteerCleanups.locationLng],
    locationVerified = this[VolunteerCleanups.locationVerified],
    startedAt = this[VolunteerCleanups.startedAt],
    completedAt = this[VolunteerCleanups.completedAt],
    durationMinutes = this[VolunteerCleanups.durationMinutes],
    pdfUrl = this[VolunteerCleanups.pdfUrl],
    status = enumValueOfOrDefault(this[VolunteerCleanups.status]),
    verificationNotes = this[VolunteerCleanups.verificationNotes],
    pointsAwarded = this[VolunteerCleanups.pointsAwarded],
    createdAt = this[VolunteerCleanups.createdAt],
    updatedAt = this[VolunteerCleanups.updatedAt],
)
