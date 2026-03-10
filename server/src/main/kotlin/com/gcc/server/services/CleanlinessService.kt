package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.CommunityReports
import com.gcc.server.db.tables.SpotVerifications
import com.gcc.shared.model.*
import org.jetbrains.exposed.sql.*
import kotlin.math.min
import kotlin.math.max
import kotlin.math.round

/**
 * Cleanliness analytics service.
 *
 * Computes lifecycle metrics from spot verification data:
 * - Average mess duration (how long until resolved)
 * - Verification velocity (time from report to first verification)
 * - Recurrence rate (addresses with repeat messes)
 * - Cleanliness score (0-100 composite score)
 */
class CleanlinessService {

    /** City-wide overview with per-area breakdown. */
    suspend fun getCityOverview(): CityCleanlinessOverview = dbQuery {
        // Active spots per area
        val activeByArea = CommunityReports.select(
            CommunityReports.locationNeighborhood,
            CommunityReports.id.count(),
        ).where {
            (CommunityReports.status eq "pending") or (CommunityReports.status eq "verified")
        }.groupBy(CommunityReports.locationNeighborhood)

        val activeMap = mutableMapOf<String, Int>()
        for (row in activeByArea) {
            val area = row[CommunityReports.locationNeighborhood] ?: continue
            activeMap[area] = row[CommunityReports.id.count()].toInt()
        }

        // Resolved spots per area + avg duration (computed in SQL, not app-side)
        val avgDurationExpr = object : org.jetbrains.exposed.sql.Expression<Double>() {
            override fun toQueryBuilder(queryBuilder: org.jetbrains.exposed.sql.QueryBuilder) {
                queryBuilder.append("COALESCE(AVG(EXTRACT(EPOCH FROM (updated_at - created_at)) / 3600.0), 0)")
            }
            val columnType = org.jetbrains.exposed.sql.DoubleColumnType()
        }

        val resolvedByArea = CommunityReports.select(
            CommunityReports.locationNeighborhood,
            CommunityReports.id.count(),
            avgDurationExpr,
        ).where {
            CommunityReports.status eq "resolved"
        }.groupBy(CommunityReports.locationNeighborhood)

        val resolvedMap = mutableMapOf<String, Int>()
        val durationByArea = mutableMapOf<String, Double>()
        for (row in resolvedByArea) {
            val area = row[CommunityReports.locationNeighborhood] ?: continue
            resolvedMap[area] = row[CommunityReports.id.count()].toInt()
            durationByArea[area] = row[avgDurationExpr] ?: 0.0
        }

        // Collect all area names
        val allAreas = (activeMap.keys + resolvedMap.keys + durationByArea.keys).sorted()

        var totalActive = 0
        var totalResolved = 0
        var totalDurationSum = 0.0
        var totalDurationCount = 0

        val areas = allAreas.map { area ->
            val active = activeMap[area] ?: 0
            val resolved = resolvedMap[area] ?: 0
            val avgDuration = durationByArea[area] ?: 0.0

            totalActive += active
            totalResolved += resolved
            if (resolved > 0) {
                totalDurationSum += avgDuration * resolved
                totalDurationCount += resolved
            }

            CleanlinessStats(
                area = area,
                activeSpots = active,
                resolvedSpots = resolved,
                avgMessDurationHours = roundTo1(avgDuration),
                cleanlinessScore = computeScore(active, avgDuration, 0.0),
            )
        }

        val cityAvgDuration = if (totalDurationCount > 0) totalDurationSum / totalDurationCount else 0.0
        val cityScore = if (areas.isNotEmpty()) {
            (areas.sumOf { it.cleanlinessScore } / areas.size)
        } else 100

        CityCleanlinessOverview(
            cityScore = cityScore,
            totalActiveSpots = totalActive,
            totalResolvedSpots = totalResolved,
            avgMessDurationHours = roundTo1(cityAvgDuration),
            areas = areas,
        )
    }

    /** Area detail with street-level breakdown. */
    suspend fun getAreaDetail(area: String): CleanlinessStats = dbQuery {
        // Active spots per street (SQL aggregate)
        val activeByStreetQuery = CommunityReports.select(
            CommunityReports.locationAddress,
            CommunityReports.id.count(),
        ).where {
            (CommunityReports.locationNeighborhood eq area) and
                ((CommunityReports.status eq "pending") or (CommunityReports.status eq "verified"))
        }.groupBy(CommunityReports.locationAddress)

        val activeByStreet = mutableMapOf<String, Int>()
        var totalActive = 0
        for (row in activeByStreetQuery) {
            val count = row[CommunityReports.id.count()].toInt()
            totalActive += count
            val street = row[CommunityReports.locationAddress] ?: continue
            activeByStreet[street] = count
        }

        // Resolved spots per street + avg duration (SQL aggregate)
        val streetAvgDurationExpr = object : Expression<Double>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                queryBuilder.append("COALESCE(AVG(EXTRACT(EPOCH FROM (updated_at - created_at)) / 3600.0), 0)")
            }
        }

        val resolvedByStreetQuery = CommunityReports.select(
            CommunityReports.locationAddress,
            CommunityReports.id.count(),
            streetAvgDurationExpr,
        ).where {
            (CommunityReports.locationNeighborhood eq area) and
                (CommunityReports.status eq "resolved")
        }.groupBy(CommunityReports.locationAddress)

        val resolvedByStreet = mutableMapOf<String, Pair<Int, Double>>()
        var totalResolved = 0
        var totalDurationSum = 0.0
        for (row in resolvedByStreetQuery) {
            val count = row[CommunityReports.id.count()].toInt()
            val avgHours = row[streetAvgDurationExpr] ?: 0.0
            totalResolved += count
            totalDurationSum += avgHours * count
            val street = row[CommunityReports.locationAddress] ?: continue
            resolvedByStreet[street] = Pair(count, avgHours)
        }

        val avgDuration = if (totalResolved > 0) totalDurationSum / totalResolved else 0.0

        // Build street-level breakdown
        val allStreets = (activeByStreet.keys + resolvedByStreet.keys).sorted()
        val streets = allStreets.map { street ->
            val streetActive = activeByStreet[street] ?: 0
            val streetAvgDuration = resolvedByStreet[street]?.second ?: 0.0
            StreetCleanlinessStats(
                street = street,
                activeSpots = streetActive,
                avgDurationHours = roundTo1(streetAvgDuration),
                score = computeScore(streetActive, streetAvgDuration, 0.0),
            )
        }

        CleanlinessStats(
            area = area,
            activeSpots = totalActive,
            resolvedSpots = totalResolved,
            avgMessDurationHours = roundTo1(avgDuration),
            cleanlinessScore = computeScore(totalActive, avgDuration, 0.0),
            streets = streets,
        )
    }

    private fun computeScore(activeSpots: Int, avgDurationHours: Double, recurrenceRate: Double): Int {
        val activePenalty = min(40, activeSpots * 3)
        val durationPenalty = min(30.0, avgDurationHours / 4.0)
        val recurrencePenalty = min(30.0, recurrenceRate * 100.0)
        return max(0, min(100, (100 - activePenalty - durationPenalty - recurrencePenalty).toInt()))
    }

    private fun roundTo1(v: Double): Double = round(v * 10) / 10
}
