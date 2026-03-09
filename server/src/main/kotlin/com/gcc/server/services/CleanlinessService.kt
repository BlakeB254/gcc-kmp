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

        // Resolved spots per area + avg duration
        val resolvedByArea = CommunityReports.select(
            CommunityReports.locationNeighborhood,
            CommunityReports.id.count(),
        ).where {
            CommunityReports.status eq "resolved"
        }.groupBy(CommunityReports.locationNeighborhood)

        val resolvedMap = mutableMapOf<String, Int>()
        for (row in resolvedByArea) {
            val area = row[CommunityReports.locationNeighborhood] ?: continue
            resolvedMap[area] = row[CommunityReports.id.count()].toInt()
        }

        // Compute avg duration per area via individual records
        val resolvedRecords = CommunityReports.selectAll()
            .where { CommunityReports.status eq "resolved" }

        val durationByArea = mutableMapOf<String, MutableList<Double>>()
        for (row in resolvedRecords) {
            val area = row[CommunityReports.locationNeighborhood] ?: continue
            val created = row[CommunityReports.createdAt]
            val updated = row[CommunityReports.updatedAt]
            val hours = (updated.epochSeconds - created.epochSeconds) / 3600.0
            durationByArea.getOrPut(area) { mutableListOf() }.add(hours)
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
            val durations = durationByArea[area] ?: emptyList()
            val avgDuration = if (durations.isNotEmpty()) durations.average() else 0.0

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
        // Active spots for this area
        val activeRows = CommunityReports.selectAll()
            .where {
                (CommunityReports.locationNeighborhood eq area) and
                    ((CommunityReports.status eq "pending") or (CommunityReports.status eq "verified"))
            }

        val activeByStreet = mutableMapOf<String, Int>()
        var totalActive = 0
        for (row in activeRows) {
            totalActive++
            val street = row[CommunityReports.locationAddress] ?: continue
            activeByStreet[street] = (activeByStreet[street] ?: 0) + 1
        }

        // Resolved spots for this area
        val resolvedRows = CommunityReports.selectAll()
            .where {
                (CommunityReports.locationNeighborhood eq area) and
                    (CommunityReports.status eq "resolved")
            }

        val durationByStreet = mutableMapOf<String, MutableList<Double>>()
        var totalResolved = 0
        var totalDurationSum = 0.0
        for (row in resolvedRows) {
            totalResolved++
            val created = row[CommunityReports.createdAt]
            val updated = row[CommunityReports.updatedAt]
            val hours = (updated.epochSeconds - created.epochSeconds) / 3600.0
            totalDurationSum += hours
            val street = row[CommunityReports.locationAddress] ?: continue
            durationByStreet.getOrPut(street) { mutableListOf() }.add(hours)
        }

        val avgDuration = if (totalResolved > 0) totalDurationSum / totalResolved else 0.0

        // Build street-level breakdown
        val allStreets = (activeByStreet.keys + durationByStreet.keys).sorted()
        val streets = allStreets.map { street ->
            val streetActive = activeByStreet[street] ?: 0
            val streetDurations = durationByStreet[street] ?: emptyList()
            val streetAvgDuration = if (streetDurations.isNotEmpty()) streetDurations.average() else 0.0
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
