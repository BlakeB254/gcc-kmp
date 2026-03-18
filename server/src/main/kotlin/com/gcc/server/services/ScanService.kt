package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.RouteScans
import com.gcc.server.db.tables.ScanHotspots
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

private val scanJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

class ScanService {

    // ── Public CRUD ──────────────────────────────────────────────────────

    suspend fun createScan(workerId: Int, request: CreateScanRequest): RouteScan = dbQuery {
        val now = Clock.System.now()
        val id = RouteScans.insert {
            it[RouteScans.routeId] = request.routeId
            it[RouteScans.workerId] = workerId
            it[deviceType] = request.deviceType.name.lowercase()
            it[phase] = ScanPhase.SCOUTING.name.lowercase()
            it[coveragePercent] = 0.0
            it[scoutStartedAt] = now
            it[startLat] = request.startLat
            it[startLng] = request.startLng
            it[status] = ScanStatus.IN_PROGRESS.name.lowercase()
            it[pointsAwarded] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }[RouteScans.id]

        val hotspots = getHotspots(id)
        RouteScans.selectAll().where { RouteScans.id eq id }.first().toRouteScan(hotspots)
    }

    suspend fun advancePhase(scanId: Int, request: AdvancePhaseRequest): RouteScan = dbQuery {
        val now = Clock.System.now()
        RouteScans.update({ RouteScans.id eq scanId }) {
            it[phase] = request.phase.name.lowercase()
            val cp = request.coveragePercent
            if (cp != null) it[coveragePercent] = cp
            if (request.scoutPathGeoJson != null) it[scoutPathGeoJson] = request.scoutPathGeoJson
            if (request.cleanPathGeoJson != null) it[cleanPathGeoJson] = request.cleanPathGeoJson
            when (request.phase) {
                ScanPhase.SCOUT_REVIEW -> it[scoutCompletedAt] = now
                ScanPhase.CLEANING -> it[cleanStartedAt] = now
                else -> { /* no timestamp update needed */ }
            }
            it[updatedAt] = now
        }
        val hotspots = getHotspots(scanId)
        RouteScans.selectAll().where { RouteScans.id eq scanId }.first().toRouteScan(hotspots)
    }

    suspend fun addHotspot(scanId: Int, request: CreateHotspotRequest): ScanHotspot = dbQuery {
        val now = Clock.System.now()
        val id = ScanHotspots.insert {
            it[ScanHotspots.scanId] = scanId
            it[lat] = request.lat
            it[lng] = request.lng
            it[trashTypes] = scanJson.encodeToString(request.trashTypes.map { tag -> tag.name.lowercase() })
            it[severity] = request.severity
            it[estimatedVolumeLiters] = request.estimatedVolumeLiters
            it[estimatedWeightLbs] = request.estimatedWeightLbs
            it[confidence] = request.confidence
            it[beforePhotoUrl] = request.beforePhotoUrl
            it[arAnchorData] = request.arAnchorData
            it[depthData] = request.depthData
            it[detectedAt] = now
            it[status] = HotspotStatus.DETECTED.name.lowercase()
        }[ScanHotspots.id]

        ScanHotspots.selectAll().where { ScanHotspots.id eq id }.first().toHotspot()
    }

    suspend fun updateHotspot(hotspotId: Int, request: UpdateHotspotRequest): ScanHotspot = dbQuery {
        val now = Clock.System.now()
        ScanHotspots.update({ ScanHotspots.id eq hotspotId }) {
            if (request.afterPhotoUrl != null) it[afterPhotoUrl] = request.afterPhotoUrl
            if (request.verificationScore != null) it[verificationScore] = request.verificationScore
            val newStatus = request.status
            if (newStatus != null) {
                it[status] = newStatus.name.lowercase()
                if (newStatus == HotspotStatus.CLEANED) it[cleanedAt] = now
            }
        }
        ScanHotspots.selectAll().where { ScanHotspots.id eq hotspotId }.first().toHotspot()
    }

    suspend fun completeScan(scanId: Int, request: CompleteScanRequest): CompleteScanResponse = dbQuery {
        val now = Clock.System.now()
        val hotspots = getHotspots(scanId)
        val cleanedCount = hotspots.count { it.status == HotspotStatus.CLEANED }
        val points = calculatePoints(hotspots, cleanedCount)

        RouteScans.update({ RouteScans.id eq scanId }) {
            it[phase] = ScanPhase.COMPLETE.name.lowercase()
            it[status] = ScanStatus.COMPLETED.name.lowercase()
            it[cleanCompletedAt] = now
            if (request.cleanPathGeoJson != null) it[cleanPathGeoJson] = request.cleanPathGeoJson
            if (request.totalTrashVolumeLiters != null) it[totalTrashVolumeLiters] = request.totalTrashVolumeLiters
            if (request.totalTrashWeightLbs != null) it[totalTrashWeightLbs] = request.totalTrashWeightLbs
            it[pointsAwarded] = points
            it[updatedAt] = now
        }

        val scan = RouteScans.selectAll().where { RouteScans.id eq scanId }.first().toRouteScan(hotspots)
        CompleteScanResponse(scan = scan, pointsAwarded = points)
    }

    suspend fun getScanById(id: Int): RouteScan? = dbQuery {
        val hotspots = getHotspots(id)
        RouteScans.selectAll().where { RouteScans.id eq id }.firstOrNull()?.toRouteScan(hotspots)
    }

    suspend fun getScansForWorker(workerId: Int): List<RouteScan> = dbQuery {
        val rows = RouteScans.selectAll()
            .where { RouteScans.workerId eq workerId }
            .orderBy(RouteScans.createdAt, SortOrder.DESC)
            .toList()
        val hotspotMap = getHotspotsForScans(rows.map { it[RouteScans.id] })
        rows.map { it.toRouteScan(hotspotMap[it[RouteScans.id]] ?: emptyList()) }
    }

    suspend fun getAllScans(limit: Int = 50): List<RouteScan> = dbQuery {
        val rows = RouteScans.selectAll()
            .orderBy(RouteScans.createdAt, SortOrder.DESC)
            .limit(limit)
            .toList()
        val hotspotMap = getHotspotsForScans(rows.map { it[RouteScans.id] })
        rows.map { it.toRouteScan(hotspotMap[it[RouteScans.id]] ?: emptyList()) }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun getHotspots(scanId: Int): List<ScanHotspot> =
        ScanHotspots.selectAll()
            .where { ScanHotspots.scanId eq scanId }
            .orderBy(ScanHotspots.detectedAt, SortOrder.ASC)
            .map { it.toHotspot() }

    /** Batch-loads hotspots for multiple scan IDs in a single query to avoid N+1. */
    private fun getHotspotsForScans(scanIds: List<Int>): Map<Int, List<ScanHotspot>> {
        if (scanIds.isEmpty()) return emptyMap()
        return ScanHotspots.selectAll()
            .where { ScanHotspots.scanId inList scanIds }
            .orderBy(ScanHotspots.detectedAt, SortOrder.ASC)
            .map { it.toHotspot() }
            .groupBy { it.scanId }
    }

    /**
     * Points = 50 base + 10 per cleaned hotspot + 1 per liter of trash removed.
     */
    private fun calculatePoints(hotspots: List<ScanHotspot>, cleanedCount: Int): Int {
        val base = 50
        val perHotspot = cleanedCount * 10
        val volumeBonus = hotspots
            .filter { it.status == HotspotStatus.CLEANED }
            .sumOf { it.estimatedVolumeLiters ?: 0.0 }
            .toInt()
        return base + perHotspot + volumeBonus
    }
}

// ── ResultRow extension functions ────────────────────────────────────────────

fun ResultRow.toRouteScan(hotspots: List<ScanHotspot>): RouteScan = RouteScan(
    id = this[RouteScans.id],
    routeId = this[RouteScans.routeId],
    workerId = this[RouteScans.workerId],
    deviceType = enumValueOfOrDefault(this[RouteScans.deviceType]),
    phase = enumValueOfOrDefault(this[RouteScans.phase]),
    coveragePercent = this[RouteScans.coveragePercent],
    scoutStartedAt = this[RouteScans.scoutStartedAt],
    scoutCompletedAt = this[RouteScans.scoutCompletedAt],
    cleanStartedAt = this[RouteScans.cleanStartedAt],
    cleanCompletedAt = this[RouteScans.cleanCompletedAt],
    startLat = this[RouteScans.startLat],
    startLng = this[RouteScans.startLng],
    scoutPathGeoJson = this[RouteScans.scoutPathGeoJson],
    cleanPathGeoJson = this[RouteScans.cleanPathGeoJson],
    totalTrashVolumeLiters = this[RouteScans.totalTrashVolumeLiters],
    totalTrashWeightLbs = this[RouteScans.totalTrashWeightLbs],
    pointsAwarded = this[RouteScans.pointsAwarded],
    status = enumValueOfOrDefault(this[RouteScans.status]),
    hotspots = hotspots,
    createdAt = this[RouteScans.createdAt],
    updatedAt = this[RouteScans.updatedAt],
)

fun ResultRow.toHotspot(): ScanHotspot {
    val rawTags = this[ScanHotspots.trashTypes]
    val tags: List<SpotTag> = try {
        val strings: List<String> = scanJson.decodeFromString(rawTags)
        strings.mapNotNull { s ->
            enumValues<SpotTag>().firstOrNull { it.name.equals(s, ignoreCase = true) }
        }
    } catch (_: Exception) {
        emptyList()
    }
    return ScanHotspot(
        id = this[ScanHotspots.id],
        scanId = this[ScanHotspots.scanId],
        lat = this[ScanHotspots.lat],
        lng = this[ScanHotspots.lng],
        trashTypes = tags,
        severity = this[ScanHotspots.severity],
        estimatedVolumeLiters = this[ScanHotspots.estimatedVolumeLiters],
        estimatedWeightLbs = this[ScanHotspots.estimatedWeightLbs],
        confidence = this[ScanHotspots.confidence],
        beforePhotoUrl = this[ScanHotspots.beforePhotoUrl],
        afterPhotoUrl = this[ScanHotspots.afterPhotoUrl],
        arAnchorData = this[ScanHotspots.arAnchorData],
        depthData = this[ScanHotspots.depthData],
        detectedAt = this[ScanHotspots.detectedAt],
        cleanedAt = this[ScanHotspots.cleanedAt],
        verificationScore = this[ScanHotspots.verificationScore],
        status = enumValueOfOrDefault(this[ScanHotspots.status]),
    )
}
