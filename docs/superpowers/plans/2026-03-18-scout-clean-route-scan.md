# Scout & Clean Route Scanning — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a two-pass route scanning system — Scout Lap (AR fog-of-war trash detection) + Clean Lap (proximity-guided cleanup with camera confirmation) — with tiered AR/GPS device support.

**Architecture:** New `RouteScan` + `ScanHotspot` entities with server CRUD, a state-machine ViewModel orchestrating SCOUTING → SCOUT_REVIEW → CLEANING → COMPLETE phases, platform-specific AR scanning (ARKit/ARCore) with GPS fallback, and fog-of-war coverage tracking on a grid model.

**Tech Stack:** Kotlin Multiplatform (Compose), Ktor (server), Exposed ORM (Postgres), ARKit (iOS), ARCore (Android), kotlinx.serialization, MapLibre

**Spec:** `docs/superpowers/specs/2026-03-18-scout-clean-route-scan-design.md`

---

## File Map

### Shared Models (shared/src/commonMain/kotlin/com/gcc/shared/model/)
| File | Responsibility |
|------|---------------|
| `RouteScan.kt` | RouteScan, ScanHotspot data classes + request/response types |
| `Enums.kt` | Add DeviceType, ScanPhase, ScanStatus, HotspotStatus enums |

### Shared API (shared/src/commonMain/kotlin/com/gcc/shared/api/)
| File | Responsibility |
|------|---------------|
| `GccApiClient.kt` | Add scan CRUD methods |

### Server (server/src/main/kotlin/com/gcc/server/)
| File | Responsibility |
|------|---------------|
| `db/tables/Tables.kt` | Add RouteScans + ScanHotspots table objects |
| `routes/ScanRoutes.kt` | REST endpoints for scan sessions |
| `services/ScanService.kt` | Business logic: create, advance phase, add hotspot, complete |

### Mobile — ViewModels (composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/)
| File | Responsibility |
|------|---------------|
| `RouteScanViewModel.kt` | Top-level state machine orchestrator |
| `ScoutViewModel.kt` | Scout lap: coverage tracking, AI detection, direction prompts |
| `CleanViewModel.kt` | Clean lap: proximity alerts, hotspot confirmation |

### Mobile — Screens (composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/)
| File | Responsibility |
|------|---------------|
| `RouteScanScreen.kt` | Phase router — delegates to scout/review/clean/complete screens |
| `ScoutScreen.kt` | AR camera + fog overlay + minimap + AI banner |
| `ScoutReviewScreen.kt` | Summary of scout results, confirm to start cleaning |
| `CleanScreen.kt` | Map + hotspot pins + proximity alert cards |
| `ScanCompleteScreen.kt` | Results, points, stats |

### Mobile — Components (composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/)
| File | Responsibility |
|------|---------------|
| `CoverageBar.kt` | Progress bar showing scout coverage % |
| `AiDirectionBanner.kt` | Top banner with AI scanning guidance |
| `ProximityAlertCard.kt` | Bottom sheet card for nearby hotspot during clean lap |
| `HotspotMarker.kt` | Animated map marker for detected trash areas |

### Mobile — Platform (composeApp/src/commonMain/kotlin/com/gcc/app/platform/)
| File | Responsibility |
|------|---------------|
| `ArScanner.kt` | expect/actual — AR session management, anchor placement |
| `CoverageTracker.kt` | expect/actual — fog-of-war grid, camera frustum vs GPS radius |
| `DeviceCapability.kt` | expect/actual — detect AR vs GPS tier |

### Mobile — Map (composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/)
| File | Responsibility |
|------|---------------|
| `FogOfWarOverlay.kt` | Canvas rendering of scanned/unscanned grid cells |
| `ScanMinimap.kt` | Small overlay map showing route + coverage + hotspots |

---

## Task 1: Shared Enums

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/Enums.kt`

- [ ] **Step 1: Add new enums to Enums.kt**

Append after the existing `SpotTag` enum:

```kotlin
@Serializable
enum class DeviceType {
    @SerialName("ar") AR,
    @SerialName("gps") GPS,
}

@Serializable
enum class ScanPhase {
    @SerialName("scouting") SCOUTING,
    @SerialName("scout_review") SCOUT_REVIEW,
    @SerialName("cleaning") CLEANING,
    @SerialName("submitting") SUBMITTING,
    @SerialName("complete") COMPLETE,
}

@Serializable
enum class ScanStatus {
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("abandoned") ABANDONED,
}

@Serializable
enum class HotspotStatus {
    @SerialName("detected") DETECTED,
    @SerialName("cleaned") CLEANED,
    @SerialName("skipped") SKIPPED,
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /home/codex450/CDX-DEV-PROJECTS/gcc && ./gradlew :shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/Enums.kt
git commit -m "feat(shared): add scan enums — DeviceType, ScanPhase, ScanStatus, HotspotStatus"
```

---

## Task 2: Shared Models — RouteScan + ScanHotspot

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/model/RouteScan.kt`

- [ ] **Step 1: Create RouteScan.kt with all data classes**

```kotlin
package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class RouteScan(
    val id: Int,
    val routeId: Int,
    val workerId: Int,
    val deviceType: DeviceType,
    val phase: ScanPhase,
    val coveragePercent: Double = 0.0,
    val scoutStartedAt: Instant,
    val scoutCompletedAt: Instant? = null,
    val cleanStartedAt: Instant? = null,
    val cleanCompletedAt: Instant? = null,
    val startLat: Double,
    val startLng: Double,
    val scoutPathGeoJson: String? = null,
    val cleanPathGeoJson: String? = null,
    val totalTrashVolumeLiters: Double? = null,
    val totalTrashWeightLbs: Double? = null,
    val pointsAwarded: Int = 0,
    val status: ScanStatus = ScanStatus.IN_PROGRESS,
    val hotspots: List<ScanHotspot> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class ScanHotspot(
    val id: Int,
    val scanId: Int,
    val lat: Double,
    val lng: Double,
    val trashTypes: List<SpotTag> = emptyList(),
    val severity: Int = 1,
    val estimatedVolumeLiters: Double? = null,
    val estimatedWeightLbs: Double? = null,
    val confidence: Float = 0f,
    val beforePhotoUrl: String,
    val afterPhotoUrl: String? = null,
    val arAnchorData: String? = null,
    val depthData: String? = null,
    val detectedAt: Instant,
    val cleanedAt: Instant? = null,
    val verificationScore: Float? = null,
    val status: HotspotStatus = HotspotStatus.DETECTED,
)

// ── Request / Response types ──

@Serializable
data class CreateScanRequest(
    val routeId: Int,
    val deviceType: DeviceType,
    val startLat: Double,
    val startLng: Double,
)

@Serializable
data class AdvancePhaseRequest(
    val phase: ScanPhase,
    val coveragePercent: Double? = null,
    val scoutPathGeoJson: String? = null,
    val cleanPathGeoJson: String? = null,
)

@Serializable
data class CreateHotspotRequest(
    val lat: Double,
    val lng: Double,
    val trashTypes: List<SpotTag> = emptyList(),
    val severity: Int = 1,
    val estimatedVolumeLiters: Double? = null,
    val estimatedWeightLbs: Double? = null,
    val confidence: Float = 0f,
    val beforePhotoUrl: String,
    val arAnchorData: String? = null,
    val depthData: String? = null,
)

@Serializable
data class UpdateHotspotRequest(
    val afterPhotoUrl: String? = null,
    val verificationScore: Float? = null,
    val status: HotspotStatus? = null,
)

@Serializable
data class CompleteScanRequest(
    val cleanPathGeoJson: String? = null,
    val totalTrashVolumeLiters: Double? = null,
    val totalTrashWeightLbs: Double? = null,
)

@Serializable
data class CompleteScanResponse(
    val scan: RouteScan,
    val pointsAwarded: Int,
)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/RouteScan.kt
git commit -m "feat(shared): add RouteScan + ScanHotspot models with request/response types"
```

---

## Task 3: Server — Database Tables

**Files:**
- Modify: `server/src/main/kotlin/com/gcc/server/db/tables/Tables.kt`

- [ ] **Step 1: Add RouteScans and ScanHotspots tables**

Append at end of Tables.kt, before the closing of the file:

```kotlin
// ===========================================================================
// Route Scans (Scout & Clean feature)
// ===========================================================================
object RouteScans : Table("route_scans") {
    val id = integer("id").autoIncrement()
    val routeId = integer("route_id").references(Routes.id)
    val workerId = integer("worker_id").references(Workers.id)
    val deviceType = varchar("device_type", 10).default("gps")
    val phase = varchar("phase", 30).default("scouting")
    val coveragePercent = double("coverage_percent").default(0.0)
    val scoutStartedAt = timestamp("scout_started_at")
    val scoutCompletedAt = timestamp("scout_completed_at").nullable()
    val cleanStartedAt = timestamp("clean_started_at").nullable()
    val cleanCompletedAt = timestamp("clean_completed_at").nullable()
    val startLat = double("start_lat")
    val startLng = double("start_lng")
    val scoutPathGeoJson = text("scout_path_geojson").nullable()
    val cleanPathGeoJson = text("clean_path_geojson").nullable()
    val totalTrashVolumeLiters = double("total_trash_volume_liters").nullable()
    val totalTrashWeightLbs = double("total_trash_weight_lbs").nullable()
    val pointsAwarded = integer("points_awarded").default(0)
    val status = varchar("status", 30).default("in_progress")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object ScanHotspots : Table("scan_hotspots") {
    val id = integer("id").autoIncrement()
    val scanId = integer("scan_id").references(RouteScans.id)
    val lat = double("lat")
    val lng = double("lng")
    val trashTypes = varchar("trash_types", 500).default("[]") // JSON array of SpotTag names
    val severity = integer("severity").default(1)
    val estimatedVolumeLiters = double("estimated_volume_liters").nullable()
    val estimatedWeightLbs = double("estimated_weight_lbs").nullable()
    val confidence = float("confidence").default(0f)
    val beforePhotoUrl = varchar("before_photo_url", 500)
    val afterPhotoUrl = varchar("after_photo_url", 500).nullable()
    val arAnchorData = text("ar_anchor_data").nullable()
    val depthData = text("depth_data").nullable()
    val detectedAt = timestamp("detected_at")
    val cleanedAt = timestamp("cleaned_at").nullable()
    val verificationScore = float("verification_score").nullable()
    val status = varchar("status", 20).default("detected")

    override val primaryKey = PrimaryKey(id)
}
```

- [ ] **Step 2: Verify server compilation**

Run: `./gradlew :server:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Create tables in production DB**

Run the SQL migration via the Neon console or psql:

```sql
CREATE TABLE route_scans (
    id SERIAL PRIMARY KEY,
    route_id INTEGER REFERENCES routes(id),
    worker_id INTEGER REFERENCES workers(id),
    device_type VARCHAR(10) DEFAULT 'gps',
    phase VARCHAR(30) DEFAULT 'scouting',
    coverage_percent DOUBLE PRECISION DEFAULT 0.0,
    scout_started_at TIMESTAMPTZ NOT NULL,
    scout_completed_at TIMESTAMPTZ,
    clean_started_at TIMESTAMPTZ,
    clean_completed_at TIMESTAMPTZ,
    start_lat DOUBLE PRECISION NOT NULL,
    start_lng DOUBLE PRECISION NOT NULL,
    scout_path_geojson TEXT,
    clean_path_geojson TEXT,
    total_trash_volume_liters DOUBLE PRECISION,
    total_trash_weight_lbs DOUBLE PRECISION,
    points_awarded INTEGER DEFAULT 0,
    status VARCHAR(30) DEFAULT 'in_progress',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE scan_hotspots (
    id SERIAL PRIMARY KEY,
    scan_id INTEGER REFERENCES route_scans(id),
    lat DOUBLE PRECISION NOT NULL,
    lng DOUBLE PRECISION NOT NULL,
    trash_types VARCHAR(500) DEFAULT '[]',
    severity INTEGER DEFAULT 1,
    estimated_volume_liters DOUBLE PRECISION,
    estimated_weight_lbs DOUBLE PRECISION,
    confidence REAL DEFAULT 0,
    before_photo_url VARCHAR(500) NOT NULL,
    after_photo_url VARCHAR(500),
    ar_anchor_data TEXT,
    depth_data TEXT,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cleaned_at TIMESTAMPTZ,
    verification_score REAL,
    status VARCHAR(20) DEFAULT 'detected'
);

CREATE INDEX idx_route_scans_route ON route_scans(route_id);
CREATE INDEX idx_route_scans_worker ON route_scans(worker_id);
CREATE INDEX idx_route_scans_status ON route_scans(status);
CREATE INDEX idx_scan_hotspots_scan ON scan_hotspots(scan_id);
```

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/gcc/server/db/tables/Tables.kt
git commit -m "feat(server): add RouteScans + ScanHotspots table definitions"
```

---

## Task 4: Server — ScanService

**Files:**
- Create: `server/src/main/kotlin/com/gcc/server/services/ScanService.kt`

- [ ] **Step 1: Create ScanService with CRUD operations**

```kotlin
package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.RouteScans
import com.gcc.server.db.tables.ScanHotspots
import com.gcc.shared.model.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*

class ScanService {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createScan(workerId: Int, req: CreateScanRequest): RouteScan = dbQuery {
        val now = Clock.System.now()
        val id = RouteScans.insert {
            it[routeId] = req.routeId
            it[RouteScans.workerId] = workerId
            it[deviceType] = req.deviceType.name.lowercase()
            it[phase] = ScanPhase.SCOUTING.name.lowercase()
            it[startLat] = req.startLat
            it[startLng] = req.startLng
            it[scoutStartedAt] = now
            it[status] = ScanStatus.IN_PROGRESS.name.lowercase()
            it[createdAt] = now
            it[updatedAt] = now
        }[RouteScans.id]
        getScanById(id)!!
    }

    suspend fun advancePhase(scanId: Int, req: AdvancePhaseRequest): RouteScan = dbQuery {
        val now = Clock.System.now()
        RouteScans.update({ RouteScans.id eq scanId }) {
            it[phase] = req.phase.name.lowercase()
            it[updatedAt] = now
            req.coveragePercent?.let { cov -> it[coveragePercent] = cov }
            req.scoutPathGeoJson?.let { path -> it[scoutPathGeoJson] = path }
            req.cleanPathGeoJson?.let { path -> it[cleanPathGeoJson] = path }
            when (req.phase) {
                ScanPhase.SCOUT_REVIEW -> it[scoutCompletedAt] = now
                ScanPhase.CLEANING -> it[cleanStartedAt] = now
                ScanPhase.COMPLETE -> {
                    it[cleanCompletedAt] = now
                    it[status] = ScanStatus.COMPLETED.name.lowercase()
                }
                else -> {}
            }
        }
        getScanById(scanId)!!
    }

    suspend fun addHotspot(scanId: Int, req: CreateHotspotRequest): ScanHotspot = dbQuery {
        val now = Clock.System.now()
        val tagsJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(SpotTag.serializer()),
            req.trashTypes
        )
        val id = ScanHotspots.insert {
            it[ScanHotspots.scanId] = scanId
            it[lat] = req.lat
            it[lng] = req.lng
            it[trashTypes] = tagsJson
            it[severity] = req.severity
            it[estimatedVolumeLiters] = req.estimatedVolumeLiters
            it[estimatedWeightLbs] = req.estimatedWeightLbs
            it[confidence] = req.confidence
            it[beforePhotoUrl] = req.beforePhotoUrl
            it[arAnchorData] = req.arAnchorData
            it[depthData] = req.depthData
            it[detectedAt] = now
            it[status] = HotspotStatus.DETECTED.name.lowercase()
        }[ScanHotspots.id]
        ScanHotspots.selectAll().where { ScanHotspots.id eq id }.first().toHotspot()
    }

    suspend fun updateHotspot(hotspotId: Int, req: UpdateHotspotRequest): ScanHotspot = dbQuery {
        ScanHotspots.update({ ScanHotspots.id eq hotspotId }) {
            req.afterPhotoUrl?.let { url -> it[afterPhotoUrl] = url }
            req.verificationScore?.let { score -> it[verificationScore] = score }
            req.status?.let { s ->
                it[status] = s.name.lowercase()
                if (s == HotspotStatus.CLEANED) it[cleanedAt] = Clock.System.now()
            }
        }
        ScanHotspots.selectAll().where { ScanHotspots.id eq hotspotId }.first().toHotspot()
    }

    suspend fun completeScan(scanId: Int, req: CompleteScanRequest): CompleteScanResponse = dbQuery {
        val now = Clock.System.now()
        val hotspots = getHotspots(scanId)
        val cleanedCount = hotspots.count { it.status == HotspotStatus.CLEANED }
        val points = calculatePoints(hotspots, cleanedCount)

        RouteScans.update({ RouteScans.id eq scanId }) {
            it[phase] = ScanPhase.COMPLETE.name.lowercase()
            it[status] = ScanStatus.COMPLETED.name.lowercase()
            it[cleanCompletedAt] = now
            it[pointsAwarded] = points
            it[updatedAt] = now
            req.cleanPathGeoJson?.let { path -> it[cleanPathGeoJson] = path }
            req.totalTrashVolumeLiters?.let { vol -> it[totalTrashVolumeLiters] = vol }
            req.totalTrashWeightLbs?.let { wt -> it[totalTrashWeightLbs] = wt }
        }
        val scan = getScanById(scanId)!!
        CompleteScanResponse(scan = scan, pointsAwarded = points)
    }

    suspend fun getScanById(id: Int): RouteScan? = dbQuery {
        RouteScans.selectAll().where { RouteScans.id eq id }.firstOrNull()?.let { row ->
            val hotspots = getHotspots(id)
            row.toRouteScan(hotspots)
        }
    }

    suspend fun getScansForWorker(workerId: Int, limit: Int = 20): List<RouteScan> = dbQuery {
        RouteScans.selectAll()
            .where { RouteScans.workerId eq workerId }
            .orderBy(RouteScans.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toRouteScan(getHotspots(it[RouteScans.id])) }
    }

    suspend fun getAllScans(limit: Int = 50): List<RouteScan> = dbQuery {
        RouteScans.selectAll()
            .orderBy(RouteScans.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toRouteScan(getHotspots(it[RouteScans.id])) }
    }

    private fun getHotspots(scanId: Int): List<ScanHotspot> =
        ScanHotspots.selectAll()
            .where { ScanHotspots.scanId eq scanId }
            .orderBy(ScanHotspots.detectedAt, SortOrder.ASC)
            .map { it.toHotspot() }

    private fun calculatePoints(hotspots: List<ScanHotspot>, cleanedCount: Int): Int {
        val basePoints = 50 // completing a scan session
        val perHotspot = 10 * cleanedCount
        val volumeBonus = hotspots
            .filter { it.status == HotspotStatus.CLEANED }
            .mapNotNull { it.estimatedVolumeLiters }
            .sum().toInt()
        return basePoints + perHotspot + volumeBonus
    }
}

// ── ResultRow extensions ──

private fun ResultRow.toRouteScan(hotspots: List<ScanHotspot>): RouteScan = RouteScan(
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

private val hotspotJson = Json { ignoreUnknownKeys = true }

private fun ResultRow.toHotspot(): ScanHotspot {
    val tagsStr = this[ScanHotspots.trashTypes]
    val tags: List<SpotTag> = try {
        hotspotJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(SpotTag.serializer()),
            tagsStr
        )
    } catch (_: Exception) { emptyList() }

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
```

- [ ] **Step 2: Register ScanService in Koin**

In `server/src/main/kotlin/com/gcc/server/plugins/Koin.kt`, add to the module:

```kotlin
single { ScanService() }
```

- [ ] **Step 3: Verify server compilation**

Run: `./gradlew :server:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/gcc/server/services/ScanService.kt
git add server/src/main/kotlin/com/gcc/server/plugins/Koin.kt
git commit -m "feat(server): add ScanService with CRUD, points calculation, row mappings"
```

---

## Task 5: Server — ScanRoutes

**Files:**
- Create: `server/src/main/kotlin/com/gcc/server/routes/ScanRoutes.kt`
- Modify: `server/src/main/kotlin/com/gcc/server/plugins/Routing.kt` (register routes)

- [ ] **Step 1: Create ScanRoutes.kt**

```kotlin
package com.gcc.server.routes

import com.gcc.server.plugins.isAdmin
import com.gcc.server.plugins.koinGet
import com.gcc.server.plugins.workerId
import com.gcc.server.services.ScanService
import com.gcc.shared.model.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.scanRoutes() {
    val scanService = koinGet<ScanService>()

    route("/api/scans") {
        // Create new scan session
        post {
            val wid = call.workerId
            val req = call.receive<CreateScanRequest>()
            val scan = scanService.createScan(wid, req)
            call.respond(HttpStatusCode.Created, ApiResponse(data = scan))
        }

        // Get scan by ID
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse(data = null as RouteScan?, success = false, message = "Invalid scan ID"))
            val scan = scanService.getScanById(id)
            if (scan != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = scan))
            } else {
                call.respond(HttpStatusCode.NotFound, ApiResponse(data = null as RouteScan?, success = false, message = "Scan not found"))
            }
        }

        // Advance scan phase
        patch("/{id}/phase") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiResponse(data = null as RouteScan?, success = false, message = "Invalid scan ID"))
            val req = call.receive<AdvancePhaseRequest>()
            val scan = scanService.advancePhase(id, req)
            call.respond(HttpStatusCode.OK, ApiResponse(data = scan))
        }

        // Add hotspot
        post("/{id}/hotspots") {
            val scanId = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiResponse(data = null as ScanHotspot?, success = false, message = "Invalid scan ID"))
            val req = call.receive<CreateHotspotRequest>()
            val hotspot = scanService.addHotspot(scanId, req)
            call.respond(HttpStatusCode.Created, ApiResponse(data = hotspot))
        }

        // Update hotspot
        patch("/{id}/hotspots/{hid}") {
            val hotspotId = call.parameters["hid"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiResponse(data = null as ScanHotspot?, success = false, message = "Invalid hotspot ID"))
            val req = call.receive<UpdateHotspotRequest>()
            val hotspot = scanService.updateHotspot(hotspotId, req)
            call.respond(HttpStatusCode.OK, ApiResponse(data = hotspot))
        }

        // Update coverage
        patch("/{id}/coverage") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiResponse(data = null as RouteScan?, success = false, message = "Invalid scan ID"))
            val req = call.receive<AdvancePhaseRequest>()
            val scan = scanService.advancePhase(id, req)
            call.respond(HttpStatusCode.OK, ApiResponse(data = scan))
        }

        // Complete scan
        post("/{id}/complete") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiResponse(data = null as CompleteScanResponse?, success = false, message = "Invalid scan ID"))
            val req = call.receive<CompleteScanRequest>()
            val result = scanService.completeScan(id, req)
            call.respond(HttpStatusCode.OK, ApiResponse(data = result))
        }
    }

    // Admin endpoints
    route("/api/admin/scans") {
        get {
            if (!call.isAdmin) return@get call.respond(HttpStatusCode.Forbidden)
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val scans = scanService.getAllScans(limit)
            call.respond(HttpStatusCode.OK, ApiResponse(data = scans))
        }

        get("/{id}") {
            if (!call.isAdmin) return@get call.respond(HttpStatusCode.Forbidden)
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val scan = scanService.getScanById(id)
            if (scan != null) {
                call.respond(HttpStatusCode.OK, ApiResponse(data = scan))
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}
```

- [ ] **Step 2: Register routes in Routing.kt**

In `server/src/main/kotlin/com/gcc/server/plugins/Routing.kt`, inside the `authenticate("auth-jwt")` block, add:

```kotlin
scanRoutes()
```

- [ ] **Step 3: Verify server compilation**

Run: `./gradlew :server:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/gcc/server/routes/ScanRoutes.kt
git add server/src/main/kotlin/com/gcc/server/plugins/Routing.kt
git commit -m "feat(server): add scan REST endpoints — CRUD, phase advance, hotspot management"
```

---

## Task 6: API Client — Scan Methods

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt`

- [ ] **Step 1: Add scan API methods to GccApiClient**

Add these methods to the `GccApiClient` class:

```kotlin
// ── Route Scans ──

suspend fun createScan(req: CreateScanRequest): RouteScan =
    postJson("/api/scans", req)

suspend fun getScan(id: Int): RouteScan =
    get("/api/scans/$id")

suspend fun advanceScanPhase(scanId: Int, req: AdvancePhaseRequest): RouteScan =
    patchJson("/api/scans/$scanId/phase", req)

suspend fun addScanHotspot(scanId: Int, req: CreateHotspotRequest): ScanHotspot =
    postJson("/api/scans/$scanId/hotspots", req)

suspend fun updateScanHotspot(scanId: Int, hotspotId: Int, req: UpdateHotspotRequest): ScanHotspot =
    patchJson("/api/scans/$scanId/hotspots/$hotspotId", req)

suspend fun updateScanCoverage(scanId: Int, req: AdvancePhaseRequest): RouteScan =
    patchJson("/api/scans/$scanId/coverage", req)

suspend fun completeScan(scanId: Int, req: CompleteScanRequest): CompleteScanResponse =
    postJson("/api/scans/$scanId/complete", req)
```

- [ ] **Step 2: Verify shared compilation**

Run: `./gradlew :shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt
git commit -m "feat(shared): add scan API methods to GccApiClient"
```

---

## Task 7: Platform — Device Capability Detection

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/DeviceCapability.kt`
- Create: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/DeviceCapability.kt`
- Create: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/DeviceCapability.kt`
- Create: `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/DeviceCapability.kt`

- [ ] **Step 1: Create common expect declaration**

```kotlin
// composeApp/src/commonMain/kotlin/com/gcc/app/platform/DeviceCapability.kt
package com.gcc.app.platform

import com.gcc.shared.model.DeviceType

expect fun detectDeviceType(): DeviceType
```

- [ ] **Step 2: Create iOS actual — uses existing hasLiDAR()**

```kotlin
// composeApp/src/iosMain/kotlin/com/gcc/app/platform/DeviceCapability.kt
package com.gcc.app.platform

import com.gcc.shared.model.DeviceType

actual fun detectDeviceType(): DeviceType {
    val camera = CameraManager()
    return if (camera.hasLiDAR()) DeviceType.AR else DeviceType.GPS
}
```

- [ ] **Step 3: Create Android actual**

```kotlin
// composeApp/src/androidMain/kotlin/com/gcc/app/platform/DeviceCapability.kt
package com.gcc.app.platform

import com.gcc.shared.model.DeviceType

actual fun detectDeviceType(): DeviceType {
    // ARCore availability check — for v1, default to GPS
    // TODO: Add Google Play Services ARCore availability check
    return DeviceType.GPS
}
```

- [ ] **Step 4: Create Desktop actual (always GPS)**

```kotlin
// composeApp/src/desktopMain/kotlin/com/gcc/app/platform/DeviceCapability.kt
package com.gcc.app.platform

import com.gcc.shared.model.DeviceType

actual fun detectDeviceType(): DeviceType = DeviceType.GPS
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/DeviceCapability.kt
git add composeApp/src/iosMain/kotlin/com/gcc/app/platform/DeviceCapability.kt
git add composeApp/src/androidMain/kotlin/com/gcc/app/platform/DeviceCapability.kt
git add composeApp/src/desktopMain/kotlin/com/gcc/app/platform/DeviceCapability.kt
git commit -m "feat(app): add device capability detection — AR (LiDAR) vs GPS tier"
```

---

## Task 8: Platform — Coverage Tracker

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageTracker.kt`

- [ ] **Step 1: Create CoverageTracker (common, no platform-specific needed)**

The coverage grid model works purely with GPS coordinates and geometry math — no platform APIs needed.

```kotlin
package com.gcc.app.platform

import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.Coordinates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * Fog-of-war coverage tracker for Scout Lap.
 *
 * Divides a route corridor into a grid of cells (~3m x 3m).
 * Cells are marked "scanned" based on:
 * - AR tier: camera frustum intersection (future — for v1, uses GPS radius like GPS tier)
 * - GPS tier: GPS position radius (~5m)
 *
 * Coverage % = scanned cells / total cells * 100
 */
class CoverageTracker {

    private val cellSizeMeters = 3.0
    private val scanRadiusMeters = 5.0
    private val corridorWidthMeters = 15.0 // buffer on each side of route

    private var cells: Array<BooleanArray> = emptyArray()
    private var gridOriginLat = 0.0
    private var gridOriginLng = 0.0
    private var gridRows = 0
    private var gridCols = 0
    private var totalCells = 0
    private var scannedCount = 0

    private val _coveragePercent = MutableStateFlow(0.0)
    val coveragePercent: StateFlow<Double> = _coveragePercent.asStateFlow()

    private val _scannedCells = MutableStateFlow<Set<Pair<Int, Int>>>(emptySet())
    val scannedCells: StateFlow<Set<Pair<Int, Int>>> = _scannedCells.asStateFlow()

    /**
     * Initialize grid from route geometry (list of [lng, lat] waypoints).
     */
    fun initFromRoute(waypoints: List<Coordinates>) {
        if (waypoints.isEmpty()) return

        // Compute bounding box with corridor buffer
        val lats = waypoints.map { it.lat }
        val lngs = waypoints.map { it.lng }
        val bufferDeg = corridorWidthMeters / 111_000.0 // rough meters → degrees

        val minLat = lats.min() - bufferDeg
        val maxLat = lats.max() + bufferDeg
        val minLng = lngs.min() - bufferDeg
        val maxLng = lngs.max() + bufferDeg

        gridOriginLat = minLat
        gridOriginLng = minLng

        val heightMeters = GeofenceVerification.distanceMeters(minLat, minLng, maxLat, minLng)
        val widthMeters = GeofenceVerification.distanceMeters(minLat, minLng, minLat, maxLng)

        gridRows = (heightMeters / cellSizeMeters).toInt().coerceAtLeast(1)
        gridCols = (widthMeters / cellSizeMeters).toInt().coerceAtLeast(1)

        cells = Array(gridRows) { BooleanArray(gridCols) }

        // Only count cells within corridor of route as "total"
        totalCells = 0
        for (r in 0 until gridRows) {
            for (c in 0 until gridCols) {
                val cellLat = gridOriginLat + (r * cellSizeMeters / 111_000.0)
                val cellLng = gridOriginLng + (c * cellSizeMeters / (111_000.0 * cos(Math.toRadians(cellLat))))
                if (isNearRoute(cellLat, cellLng, waypoints)) {
                    totalCells++
                }
            }
        }
        scannedCount = 0
        _coveragePercent.value = 0.0
        _scannedCells.value = emptySet()
    }

    /**
     * Update coverage based on user's current position.
     */
    fun updatePosition(lat: Double, lng: Double) {
        if (totalCells == 0) return
        val newScanned = mutableSetOf<Pair<Int, Int>>()

        for (r in 0 until gridRows) {
            for (c in 0 until gridCols) {
                if (cells[r][c]) continue
                val cellLat = gridOriginLat + (r * cellSizeMeters / 111_000.0)
                val cellLng = gridOriginLng + (c * cellSizeMeters / (111_000.0 * cos(Math.toRadians(cellLat))))
                val dist = GeofenceVerification.distanceMeters(lat, lng, cellLat, cellLng)
                if (dist <= scanRadiusMeters) {
                    cells[r][c] = true
                    scannedCount++
                    newScanned.add(r to c)
                }
            }
        }

        if (newScanned.isNotEmpty()) {
            _scannedCells.value = _scannedCells.value + newScanned
            _coveragePercent.value = if (totalCells > 0) (scannedCount.toDouble() / totalCells * 100.0) else 0.0
        }
    }

    private fun isNearRoute(lat: Double, lng: Double, waypoints: List<Coordinates>): Boolean {
        return waypoints.any { wp ->
            GeofenceVerification.distanceMeters(lat, lng, wp.lat, wp.lng) <= corridorWidthMeters
        }
    }

    fun reset() {
        cells = emptyArray()
        totalCells = 0
        scannedCount = 0
        _coveragePercent.value = 0.0
        _scannedCells.value = emptySet()
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageTracker.kt
git commit -m "feat(app): add CoverageTracker — grid-based fog-of-war coverage system"
```

---

## Task 9: RouteScanViewModel — State Machine Orchestrator

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt`

- [ ] **Step 1: Create the main ViewModel**

```kotlin
package com.gcc.app.viewmodel

import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.detectDeviceType
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock

data class RouteScanUiState(
    val scanId: Int? = null,
    val routeId: Int = 0,
    val route: Route? = null,
    val deviceType: DeviceType = DeviceType.GPS,
    val phase: ScanPhase = ScanPhase.SCOUTING,
    val coveragePercent: Double = 0.0,
    val hotspots: List<ScanHotspot> = emptyList(),
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val currentLat: Double = 0.0,
    val currentLng: Double = 0.0,
    val elapsedSeconds: Long = 0,
    val nearbyHotspot: ScanHotspot? = null,
    val isLoopClosed: Boolean = false,
    val isSubmitting: Boolean = false,
    val isComplete: Boolean = false,
    val pointsAwarded: Int = 0,
    val error: String? = null,
    val aiDirectionText: String? = null,
)

class RouteScanViewModel(
    private val apiClient: GccApiClient,
    private val locationProvider: LocationProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coverageTracker = CoverageTracker()
    private var timerJob: Job? = null
    private var locationJob: Job? = null
    private var gpsPath = mutableListOf<Pair<Double, Double>>()

    private val _uiState = MutableStateFlow(RouteScanUiState())
    val uiState: StateFlow<RouteScanUiState> = _uiState.asStateFlow()

    private val loopClosureThresholdMeters = 20.0
    private val coverageThreshold = 85.0
    private val proximityAlertMeters = 20.0

    fun initForRoute(route: Route) {
        val deviceType = detectDeviceType()
        _uiState.value = RouteScanUiState(
            routeId = route.id ?: 0,
            route = route,
            deviceType = deviceType,
            phase = ScanPhase.SCOUTING,
        )

        // Init coverage grid from route geometry
        val waypoints = route.checkpoints.map {
            Coordinates(lng = it.coordinates.lng, lat = it.coordinates.lat)
        }
        coverageTracker.initFromRoute(waypoints)

        // Collect coverage updates
        scope.launch {
            coverageTracker.coveragePercent.collect { pct ->
                _uiState.value = _uiState.value.copy(coveragePercent = pct)
            }
        }
    }

    fun startScout() {
        scope.launch {
            try {
                // Wait for first GPS fix
                val loc = locationProvider.currentLocation.value
                    ?: return@launch _uiState.value = _uiState.value.copy(error = "Waiting for GPS...")

                val startLat = loc.lat
                val startLng = loc.lng

                // Create scan on server
                val scan = apiClient.createScan(CreateScanRequest(
                    routeId = _uiState.value.routeId,
                    deviceType = _uiState.value.deviceType,
                    startLat = startLat,
                    startLng = startLng,
                ))

                _uiState.value = _uiState.value.copy(
                    scanId = scan.id,
                    startLat = startLat,
                    startLng = startLng,
                    error = null,
                )

                startLocationTracking()
                startTimer()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to start scan: ${e.message}")
            }
        }
    }

    fun addDetectedHotspot(
        lat: Double,
        lng: Double,
        trashTypes: List<SpotTag>,
        severity: Int,
        volumeLiters: Double?,
        weightLbs: Double?,
        confidence: Float,
        beforePhotoUrl: String,
        arAnchorData: String? = null,
        depthData: String? = null,
    ) {
        val scanId = _uiState.value.scanId ?: return
        scope.launch {
            try {
                val hotspot = apiClient.addScanHotspot(scanId, CreateHotspotRequest(
                    lat = lat, lng = lng,
                    trashTypes = trashTypes, severity = severity,
                    estimatedVolumeLiters = volumeLiters, estimatedWeightLbs = weightLbs,
                    confidence = confidence, beforePhotoUrl = beforePhotoUrl,
                    arAnchorData = arAnchorData, depthData = depthData,
                ))
                _uiState.value = _uiState.value.copy(
                    hotspots = _uiState.value.hotspots + hotspot,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to save hotspot: ${e.message}")
            }
        }
    }

    fun completeScout() {
        val state = _uiState.value
        val scanId = state.scanId ?: return
        scope.launch {
            try {
                val pathJson = buildGeoJsonLineString(gpsPath)
                apiClient.advanceScanPhase(scanId, AdvancePhaseRequest(
                    phase = ScanPhase.SCOUT_REVIEW,
                    coveragePercent = state.coveragePercent,
                    scoutPathGeoJson = pathJson,
                ))
                _uiState.value = state.copy(phase = ScanPhase.SCOUT_REVIEW)
            } catch (e: Exception) {
                _uiState.value = state.copy(error = "Failed to complete scout: ${e.message}")
            }
        }
    }

    fun startCleaning() {
        val scanId = _uiState.value.scanId ?: return
        gpsPath.clear()
        scope.launch {
            try {
                apiClient.advanceScanPhase(scanId, AdvancePhaseRequest(
                    phase = ScanPhase.CLEANING,
                ))
                _uiState.value = _uiState.value.copy(phase = ScanPhase.CLEANING)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to start cleaning: ${e.message}")
            }
        }
    }

    fun confirmHotspotCleaned(hotspotId: Int, afterPhotoUrl: String, verificationScore: Float) {
        val scanId = _uiState.value.scanId ?: return
        scope.launch {
            try {
                val updated = apiClient.updateScanHotspot(scanId, hotspotId, UpdateHotspotRequest(
                    afterPhotoUrl = afterPhotoUrl,
                    verificationScore = verificationScore,
                    status = HotspotStatus.CLEANED,
                ))
                _uiState.value = _uiState.value.copy(
                    hotspots = _uiState.value.hotspots.map {
                        if (it.id == hotspotId) updated else it
                    },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to confirm cleanup: ${e.message}")
            }
        }
    }

    fun skipHotspot(hotspotId: Int) {
        val scanId = _uiState.value.scanId ?: return
        scope.launch {
            try {
                val updated = apiClient.updateScanHotspot(scanId, hotspotId, UpdateHotspotRequest(
                    status = HotspotStatus.SKIPPED,
                ))
                _uiState.value = _uiState.value.copy(
                    hotspots = _uiState.value.hotspots.map {
                        if (it.id == hotspotId) updated else it
                    },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to skip hotspot: ${e.message}")
            }
        }
    }

    fun completeScan() {
        val state = _uiState.value
        val scanId = state.scanId ?: return
        scope.launch {
            try {
                _uiState.value = state.copy(isSubmitting = true)
                val pathJson = buildGeoJsonLineString(gpsPath)
                val totalVol = state.hotspots.filter { it.status == HotspotStatus.CLEANED }
                    .mapNotNull { it.estimatedVolumeLiters }.sum()
                val totalWt = state.hotspots.filter { it.status == HotspotStatus.CLEANED }
                    .mapNotNull { it.estimatedWeightLbs }.sum()

                val result = apiClient.completeScan(scanId, CompleteScanRequest(
                    cleanPathGeoJson = pathJson,
                    totalTrashVolumeLiters = totalVol.takeIf { it > 0 },
                    totalTrashWeightLbs = totalWt.takeIf { it > 0 },
                ))

                stopTracking()
                _uiState.value = state.copy(
                    phase = ScanPhase.COMPLETE,
                    isSubmitting = false,
                    isComplete = true,
                    pointsAwarded = result.pointsAwarded,
                )
            } catch (e: Exception) {
                _uiState.value = state.copy(isSubmitting = false, error = "Failed to submit: ${e.message}")
            }
        }
    }

    private fun startLocationTracking() {
        locationJob = scope.launch {
            locationProvider.currentLocation.collect { loc ->
                if (loc == null) return@collect
                val state = _uiState.value
                _uiState.value = state.copy(currentLat = loc.lat, currentLng = loc.lng)
                gpsPath.add(loc.lat to loc.lng)

                when (state.phase) {
                    ScanPhase.SCOUTING -> {
                        coverageTracker.updatePosition(loc.lat, loc.lng)
                        // Check loop closure
                        if (state.startLat != 0.0 && gpsPath.size > 20) {
                            val distToStart = com.gcc.shared.geo.GeofenceVerification.distanceMeters(
                                loc.lat, loc.lng, state.startLat, state.startLng
                            )
                            _uiState.value = _uiState.value.copy(
                                isLoopClosed = distToStart <= loopClosureThresholdMeters
                            )
                        }
                    }
                    ScanPhase.CLEANING -> {
                        // Check proximity to uncleaned hotspots
                        val nearest = state.hotspots
                            .filter { it.status == HotspotStatus.DETECTED }
                            .minByOrNull {
                                com.gcc.shared.geo.GeofenceVerification.distanceMeters(
                                    loc.lat, loc.lng, it.lat, it.lng
                                )
                            }
                        val dist = nearest?.let {
                            com.gcc.shared.geo.GeofenceVerification.distanceMeters(
                                loc.lat, loc.lng, it.lat, it.lng
                            )
                        }
                        _uiState.value = _uiState.value.copy(
                            nearbyHotspot = if (dist != null && dist <= proximityAlertMeters) nearest else null
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    private fun startTimer() {
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                _uiState.value = _uiState.value.copy(
                    elapsedSeconds = _uiState.value.elapsedSeconds + 1
                )
            }
        }
    }

    private fun stopTracking() {
        timerJob?.cancel()
        locationJob?.cancel()
        coverageTracker.reset()
    }

    fun canCompleteScout(): Boolean {
        val state = _uiState.value
        return state.coveragePercent >= coverageThreshold && state.isLoopClosed
    }

    fun allHotspotsDone(): Boolean {
        return _uiState.value.hotspots.all { it.status != HotspotStatus.DETECTED }
    }

    fun onCleared() {
        stopTracking()
        scope.cancel()
    }

    private fun buildGeoJsonLineString(path: List<Pair<Double, Double>>): String {
        val coords = path.joinToString(",") { "[${it.second},${it.first}]" }
        return """{"type":"LineString","coordinates":[$coords]}"""
    }
}
```

- [ ] **Step 2: Register in Koin**

In the app's Koin module, add:

```kotlin
factory { RouteScanViewModel(get(), get()) }
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt
git commit -m "feat(app): add RouteScanViewModel — full state machine orchestrator"
```

---

## Task 10: UI Components

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CoverageBar.kt`
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/AiDirectionBanner.kt`
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/ProximityAlertCard.kt`

- [ ] **Step 1: Create CoverageBar.kt**

```kotlin
package com.gcc.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CoverageBar(
    percent: Double,
    label: String = "SCOUTING",
    modifier: Modifier = Modifier,
) {
    val animatedPercent by animateFloatAsState(percent.toFloat().coerceIn(0f, 100f))

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
            Text("${animatedPercent.toInt()}%", fontSize = 9.sp, color = Color.Gray)
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.1f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedPercent / 100f)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF22C55E), Color(0xFF3B82F6)))),
            )
        }
    }
}
```

- [ ] **Step 2: Create AiDirectionBanner.kt**

```kotlin
package com.gcc.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AiDirectionBanner(
    text: String?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = text != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF3B82F6).copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = text ?: "",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }
    }
}
```

- [ ] **Step 3: Create ProximityAlertCard.kt**

```kotlin
package com.gcc.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.shared.model.ScanHotspot

@Composable
fun ProximityAlertCard(
    hotspot: ScanHotspot?,
    distanceMeters: Double? = null,
    onClean: (ScanHotspot) -> Unit,
    onSkip: (ScanHotspot) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = hotspot != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        hotspot?.let { hs ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF04444)),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Scout photo placeholder
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("📸", fontSize = 20.sp)
                    }
                    // Details
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Hotspot — ${distanceMeters?.toInt() ?: "?"}m away",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Text(
                            "${hs.trashTypes.joinToString(", ") { it.name.lowercase() }} · Sev ${hs.severity}",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    // Actions
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { onClean(hs) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("CLEAN", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF04444))
                        }
                        TextButton(
                            onClick = { onSkip(hs) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Text("Skip", fontSize = 9.sp, color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CoverageBar.kt
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/AiDirectionBanner.kt
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/ProximityAlertCard.kt
git commit -m "feat(app): add Scout & Clean UI components — CoverageBar, AiDirectionBanner, ProximityAlertCard"
```

---

## Task 11: Screens — RouteScanScreen (Phase Router)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RouteScanScreen.kt`

- [ ] **Step 1: Create phase router screen**

```kotlin
package com.gcc.app.ui.screens

import androidx.compose.runtime.*
import com.gcc.app.viewmodel.RouteScanViewModel
import com.gcc.shared.model.Route
import com.gcc.shared.model.ScanPhase
import org.koin.compose.koinInject

@Composable
fun RouteScanScreen(
    route: Route,
    onBack: () -> Unit = {},
    onComplete: () -> Unit = {},
) {
    val viewModel = koinInject<RouteScanViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(route.id) {
        viewModel.initForRoute(route)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onCleared() }
    }

    when (uiState.phase) {
        ScanPhase.SCOUTING -> ScoutScreen(
            uiState = uiState,
            onStartScout = { viewModel.startScout() },
            onCompleteScout = { viewModel.completeScout() },
            canComplete = viewModel.canCompleteScout(),
            onBack = onBack,
        )
        ScanPhase.SCOUT_REVIEW -> ScoutReviewScreen(
            uiState = uiState,
            onStartCleaning = { viewModel.startCleaning() },
            onBack = onBack,
        )
        ScanPhase.CLEANING -> CleanScreen(
            uiState = uiState,
            onConfirmCleaned = { id, url, score -> viewModel.confirmHotspotCleaned(id, url, score) },
            onSkipHotspot = { id -> viewModel.skipHotspot(id) },
            onCompleteScan = { viewModel.completeScan() },
            allDone = viewModel.allHotspotsDone(),
        )
        ScanPhase.SUBMITTING -> ScanSubmittingScreen()
        ScanPhase.COMPLETE -> ScanCompleteScreen(
            pointsAwarded = uiState.pointsAwarded,
            hotspotCount = uiState.hotspots.size,
            cleanedCount = uiState.hotspots.count { it.status == com.gcc.shared.model.HotspotStatus.CLEANED },
            elapsedSeconds = uiState.elapsedSeconds,
            onDone = onComplete,
        )
    }
}
```

- [ ] **Step 2: Create placeholder screens (ScoutScreen, ScoutReviewScreen, CleanScreen, ScanCompleteScreen, ScanSubmittingScreen)**

Create stub `@Composable` functions for each in their respective files. These will be fleshed out in subsequent tasks. For now, each should show the phase name and a "Next" button to test the state machine flow.

Files to create:
- `ScoutScreen.kt` — Shows "Scout Lap" + coverage bar + start/complete buttons
- `ScoutReviewScreen.kt` — Shows hotspot count + "Start Cleaning" button
- `CleanScreen.kt` — Shows hotspot list + "Complete" button
- `ScanCompleteScreen.kt` — Shows points awarded + "Done" button
- `ScanSubmittingScreen.kt` — Loading indicator

Each follows the same pattern as VolunteerCleanupScreen.kt phases but as separate files.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RouteScanScreen.kt
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ScoutScreen.kt
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ScoutReviewScreen.kt
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/CleanScreen.kt
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ScanCompleteScreen.kt
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ScanSubmittingScreen.kt
git commit -m "feat(app): add RouteScanScreen phase router + all phase screen stubs"
```

---

## Task 12: Wire Navigation + Entry Point

**Files:**
- Modify: Route detail screen to add "Scout & Clean" button
- Modify: Navigation graph to include RouteScanScreen route

- [ ] **Step 1: Add "Scout & Clean" button to RouteDetailScreen**

In `RouteDetailScreen.kt`, add a button that navigates to the new RouteScanScreen when a route is published and the user is authenticated. The button should only appear for routes with checkpoints.

- [ ] **Step 2: Add navigation route**

In the app's navigation setup, add a route for RouteScanScreen that accepts a route ID parameter and loads the route data.

- [ ] **Step 3: Verify end-to-end compilation**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(app): wire Scout & Clean entry point from route detail + navigation"
```

---

## Task 13: Database Migration (Production)

- [ ] **Step 1: Run SQL migration against Neon production DB**

Execute the SQL from Task 3, Step 3 against the production database.

- [ ] **Step 2: Verify tables exist**

```sql
SELECT table_name FROM information_schema.tables WHERE table_name IN ('route_scans', 'scan_hotspots');
```
Expected: 2 rows

- [ ] **Step 3: Verify server starts and endpoints respond**

```bash
curl http://localhost:7310/api/health
```
Expected: 200 OK

---

## Future Tasks (post-v1)

These are noted but NOT part of the current plan:

- **Task F1:** ScoutScreen full implementation — AR camera view, fog-of-war Canvas overlay, minimap composable
- **Task F2:** AR Scanner platform implementation — ARKit session on iOS, ARCore on Android
- **Task F3:** AI direction prompt engine — bearing calculation, unscanned cluster detection
- **Task F4:** CleanScreen full implementation — MapLibre with animated hotspot markers
- **Task F5:** Before/after AI comparison integration — wire OnDeviceAnalyzer.compareImages()
- **Task F6:** iOS build & TestFlight deploy with AR features
- **Task F7:** Android ARCore integration (when ARCore check is implemented)
