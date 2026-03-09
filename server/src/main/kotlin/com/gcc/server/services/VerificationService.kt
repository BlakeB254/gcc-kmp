package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.CommunityReports
import com.gcc.server.db.tables.PointsLedger
import com.gcc.server.db.tables.SpotVerifications
import com.gcc.server.db.tables.Workers
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import kotlin.math.roundToInt

class VerificationService {

    /**
     * Verify a spot report — confirm it's still dirty or already clean.
     *
     * - GPS anti-gaming: checks verifier is within 200m of the spot
     * - Awards points and updates spot status when verified clean
     */
    suspend fun verifySpot(
        spotId: Int,
        verifierId: Int,
        request: VerifySpotRequest,
    ): VerifySpotResponse = dbQuery {
        val now = Clock.System.now()

        // Look up the spot
        val spot = CommunityReports.selectAll()
            .where { CommunityReports.id eq spotId }
            .firstOrNull() ?: throw IllegalArgumentException("Spot not found")

        val status = spot[CommunityReports.status]
        if (status != "pending" && status != "verified") {
            throw IllegalArgumentException("Spot is not eligible for verification (status: $status)")
        }

        // GPS distance check (200m buffer)
        val spotCoords = spot[CommunityReports.coordinates]
        val spotLat = spotCoords[1]
        val spotLng = spotCoords[0]
        var distanceMeters: Double? = null
        val vLat = request.verifierLat
        val vLng = request.verifierLng
        if (vLat != null && vLng != null) {
            distanceMeters = haversineMeters(spotLat, spotLng, vLat, vLng)
            if (distanceMeters > 200.0) {
                throw IllegalArgumentException("Too far from spot (${distanceMeters.roundToInt()}m). Must be within 200m.")
            }
        }

        // Determine points
        val isDirty = request.result == SpotVerificationResult.STILL_DIRTY
        val pointsAwarded = if (isDirty) 5 else 10
        val spotResolved = !isDirty

        // Look up verifier name
        val verifierName = Workers.select(Workers.name)
            .where { Workers.id eq verifierId }
            .firstOrNull()?.get(Workers.name)

        // Insert verification record
        val verificationId = SpotVerifications.insert {
            it[reportId] = spotId
            it[SpotVerifications.verifierId] = verifierId
            it[SpotVerifications.verifierName] = verifierName
            it[SpotVerifications.result] = request.result.name.lowercase()
            it[photoUrl] = request.photoUrl
            it[SpotVerifications.verifierLat] = request.verifierLat?.toString()
            it[SpotVerifications.verifierLng] = request.verifierLng?.toString()
            it[SpotVerifications.distanceFromSpotMeters] = distanceMeters?.toString()
            it[SpotVerifications.pointsAwarded] = pointsAwarded
            it[createdAt] = now
        }[SpotVerifications.id]

        // Award points
        PointsLedger.insert {
            it[workerId] = verifierId
            it[action] = if (isDirty) "verification_dirty" else "verification_clean"
            it[points] = pointsAwarded
            it[referenceType] = "verification"
            it[referenceId] = verificationId
            it[createdAt] = now
        }

        // Update spot status
        CommunityReports.update({ CommunityReports.id eq spotId }) {
            it[CommunityReports.status] = if (spotResolved) "resolved" else "verified"
            it[updatedAt] = now
        }

        VerifySpotResponse(
            verification = SpotVerification(
                id = verificationId,
                reportId = spotId,
                verifierId = verifierId,
                verifierName = verifierName,
                result = request.result,
                photoUrl = request.photoUrl,
                verifierLat = request.verifierLat,
                verifierLng = request.verifierLng,
                distanceFromSpotMeters = distanceMeters,
                pointsAwarded = pointsAwarded,
                createdAt = now,
            ),
            pointsAwarded = pointsAwarded,
            spotResolved = spotResolved,
        )
    }

    /** Get all verifications for a spot report. */
    suspend fun getVerificationsForSpot(spotId: Int): List<SpotVerification> = dbQuery {
        SpotVerifications.selectAll()
            .where { SpotVerifications.reportId eq spotId }
            .orderBy(SpotVerifications.createdAt, SortOrder.DESC)
            .map { it.toSpotVerification() }
    }

    /** Get spots needing verification (pending/verified, oldest first). */
    suspend fun getSpotsNeedingVerification(limit: Int = 50): List<CommunityReport> = dbQuery {
        CommunityReports.selectAll()
            .where {
                (CommunityReports.status eq "pending") or (CommunityReports.status eq "verified")
            }
            .orderBy(CommunityReports.createdAt, SortOrder.ASC)
            .limit(limit)
            .map { it.toReport() }
    }

    private fun ResultRow.toSpotVerification() = SpotVerification(
        id = this[SpotVerifications.id],
        reportId = this[SpotVerifications.reportId],
        verifierId = this[SpotVerifications.verifierId],
        verifierName = this[SpotVerifications.verifierName],
        result = if (this[SpotVerifications.result] == "still_dirty")
            SpotVerificationResult.STILL_DIRTY else SpotVerificationResult.ALREADY_CLEAN,
        photoUrl = this[SpotVerifications.photoUrl],
        verifierLat = this[SpotVerifications.verifierLat]?.toDoubleOrNull(),
        verifierLng = this[SpotVerifications.verifierLng]?.toDoubleOrNull(),
        distanceFromSpotMeters = this[SpotVerifications.distanceFromSpotMeters]?.toDoubleOrNull(),
        pointsAwarded = this[SpotVerifications.pointsAwarded],
        createdAt = this[SpotVerifications.createdAt],
    )
}
