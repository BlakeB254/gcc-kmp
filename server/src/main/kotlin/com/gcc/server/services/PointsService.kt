package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.PointsLedger
import com.gcc.shared.model.*
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.*

class PointsService {

    /** Get a worker's total points, breakdown by action type, and recent entries. */
    suspend fun getPointsSummary(workerId: Int): PointsSummary = dbQuery {
        // Aggregate by action type
        val rows = PointsLedger.select(PointsLedger.action, PointsLedger.points.sum())
            .where { PointsLedger.workerId eq workerId }
            .groupBy(PointsLedger.action)

        val breakdownMap = mutableMapOf<String, Int>()
        for (row in rows) {
            val action = row[PointsLedger.action]
            val total = row[PointsLedger.points.sum()] ?: 0
            breakdownMap[action] = total
        }

        val totalPoints = breakdownMap.values.sum()

        // Recent entries
        val recent = PointsLedger.selectAll()
            .where { PointsLedger.workerId eq workerId }
            .orderBy(PointsLedger.createdAt, SortOrder.DESC)
            .limit(20)
            .map { it.toPointsLedgerEntry() }

        PointsSummary(
            totalPoints = totalPoints,
            breakdown = PointsBreakdown(
                spotCreated = breakdownMap["spot_created"] ?: 0,
                verificationDirty = breakdownMap["verification_dirty"] ?: 0,
                verificationClean = breakdownMap["verification_clean"] ?: 0,
                volunteerCleanup = breakdownMap["volunteer_cleanup"] ?: 0,
                alreadyCleanReport = breakdownMap["already_clean_report"] ?: 0,
            ),
            recentEntries = recent,
        )
    }

    private fun ResultRow.toPointsLedgerEntry() = PointsLedgerEntry(
        id = this[PointsLedger.id],
        workerId = this[PointsLedger.workerId],
        action = try {
            PointsAction.valueOf(this[PointsLedger.action].uppercase())
        } catch (_: Exception) {
            PointsAction.SPOT_CREATED
        },
        points = this[PointsLedger.points],
        referenceType = this[PointsLedger.referenceType],
        referenceId = this[PointsLedger.referenceId],
        createdAt = this[PointsLedger.createdAt],
    )
}
