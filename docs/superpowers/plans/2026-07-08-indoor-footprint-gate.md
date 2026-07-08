# Indoor Footprint Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task is a self-contained TDD cycle: failing test → run (FAIL) → minimal impl → run (PASS) → commit. Read a file before editing it — line numbers drift as edits land.

**Goal:** Replace the ~28 hardcoded North Lawndale rectangles that drive the indoor gate's map-evidence signal (`BuildingData.isInsideMappedBuilding`) with real OSM building polygons — cached server-side in Neon, fetched lazily via Overpass, cached client-side in SQLDelight, tested client-side by point-in-polygon. The gate wiring shape is unchanged: inside a building → `onMapEvidence(insideBuilding=true)` → instant INDOOR. This is **Workstream 1 only** of `docs/superpowers/specs/2026-07-08-scan-visibility-and-indoor-footprint-design.md`.

**Architecture:** Overpass (way["building"]) → `GET /api/map/buildings?bbox=` (Next.js, lazy fetch on tile cache-miss) → `building_footprints` + `building_footprint_tiles` (Neon) → `GccApiClient.getBuildingFootprints(bbox)` → `BuildingFootprintStore` (SQLDelight per-tile cache) → `MappedBuildingRegistry` (in-memory rings) → `BuildingData.isInsideMappedBuilding` (point-in-polygon; NL rectangles = offline fallback) → `outdoorGate.onMapEvidence(...)` (MAP_STREAK_TO_FLIP=1 → instant INDOOR, unchanged).

**Tech Stack:** Kotlin Multiplatform (commonMain), SQLDelight (`GccDatabase`, pkg `com.gcc.shared.cache`), Ktor client (`GccApiClient`), Next.js 16 App Router + Drizzle over Neon serverless HTTP, point-in-polygon in `com.gcc.shared.geo`. Tests: `composeApp` commonTest (`kotlin.test`); server tests use `node:test` + `tsx` (NOT vitest/jest — repo convention).

## Global Constraints

1. **Mobile API = `site/src/app/api/`, NOT Ktor.** New endpoint goes under `site/src/app/api/map/buildings/`.
2. **Drizzle `schema.ts` authoritative; `Tables.kt` is a hand mirror.** Ktor does NOT read `building_footprints` today, so the `Tables.kt` mirror is optional here — note it in the PR body.
3. **`db:push` unsafe on drift → hand-written psql migration.** Edit `schema.ts` AND hand-write a `CREATE TABLE IF NOT EXISTS` `.sql` artifact. Applied to prod via `psql` BY BLAKE on his word; do not run migrations yourself.
4. **neon-http has NO `db.transaction`.** Use sequential awaited statements or `db.batch([...])`.
5. **Tests in `composeApp` commonTest, NOT `shared` commonTest** (shared commonTest is broken). `composeApp` depends on `shared` so it can test `com.gcc.shared.geo`.
6. **Coordinates are (lng, lat).** Footprint rings, the DTO `polygon`, and the new point-in-polygon util all use `[lng, lat]`. The pre-existing `PolygonContainment.pointInPolygon` uses `(lat,lng)` pairs — do NOT reuse it for GeoJSON rings; add a separate `[lng,lat]`-aware function.
7. **Server test runner:** `node:test` + `node:assert/strict`, run via `DATABASE_URL=postgres://test:test@localhost/test npx tsx --test <file>`. Exports under test must be pure.
8. **Commit trailer:** end every commit message with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## Task 1 — Shared `[lng,lat]`-aware point-in-polygon util

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/geo/PolygonContainment.kt` (append; keep existing `pointInPolygon`)
- Test: `composeApp/src/commonTest/kotlin/com/gcc/shared/geo/PolygonContainmentTest.kt`

**Interfaces:**
- Produces: `fun pointInGeoJsonRing(lng: Double, lat: Double, ring: List<List<Double>>): Boolean` — `ring` vertices are `[lng, lat]`.

- [ ] Step 1: Write failing test `PolygonContainmentTest.kt`:
```kotlin
package com.gcc.shared.geo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolygonContainmentTest {
    private val square = listOf(
        listOf(-87.720, 41.860), listOf(-87.710, 41.860),
        listOf(-87.710, 41.866), listOf(-87.720, 41.866),
        listOf(-87.720, 41.860),
    )
    @Test fun pointInsideSquareIsInside() = assertTrue(pointInGeoJsonRing(-87.715, 41.863, square))
    @Test fun pointOutsideSquareIsOutside() {
        assertFalse(pointInGeoJsonRing(-87.700, 41.863, square))
        assertFalse(pointInGeoJsonRing(-87.715, 41.900, square))
    }
    @Test fun ringWithoutExplicitClosureStillWorks() {
        val open = square.dropLast(1)
        assertTrue(pointInGeoJsonRing(-87.715, 41.863, open))
        assertFalse(pointInGeoJsonRing(-87.700, 41.863, open))
    }
    @Test fun degenerateRingIsOutside() {
        assertFalse(pointInGeoJsonRing(-87.715, 41.863, emptyList()))
        assertFalse(pointInGeoJsonRing(-87.715, 41.863, listOf(listOf(-87.72, 41.86), listOf(-87.71, 41.86))))
    }
}
```
- [ ] Step 2: Run — expect FAIL (unresolved `pointInGeoJsonRing`): `./gradlew :composeApp:jvmTest --tests "com.gcc.shared.geo.PolygonContainmentTest"`
- [ ] Step 3: Append to `PolygonContainment.kt`:
```kotlin

/**
 * Ray-casting point-in-polygon over a GeoJSON linear ring. Ring vertices are
 * [lng, lat] pairs (GeoJSON order). Distinct from [pointInPolygon] above,
 * which takes (lat,lng) Pairs — DO NOT cross the two conventions. Rings with
 * fewer than 3 vertices are never inside; open or closed rings both work.
 */
fun pointInGeoJsonRing(lng: Double, lat: Double, ring: List<List<Double>>): Boolean {
    if (ring.size < 3) return false
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val xi = ring[i][0]; val yi = ring[i][1]
        val xj = ring[j][0]; val yj = ring[j][1]
        if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}
```
- [ ] Step 4: Run — expect PASS.
- [ ] Step 5: Commit `Add [lng,lat] GeoJSON-ring point-in-polygon util + tests`

---

## Task 2 — Neon schema: `building_footprints` + `building_footprint_tiles`

**Files:**
- Modify: `site/src/db/schema.ts` (add `bigint` import; append two `pgTable`s after `reconstructionAssets`)
- Create: `site/src/db/migrations/0032_add_building_footprints.sql`

**Interfaces:** Produces `buildingFootprints`, `buildingFootprintTiles` tables + inferred types.

- [ ] Step 1: Add `bigint` to the `drizzle-orm/pg-core` import block in `schema.ts`.
- [ ] Step 2: Append after `reconstructionAssets`:
```ts
// Building footprints (scan-visibility WS1): real OSM building polygons for
// the indoor gate. One row per OSM way; bbox cols indexed for intersection
// lookup, full ring in `polygon` as GeoJSON [lng,lat][]. Lazily fetched from
// Overpass on a tile cache-miss (GET /api/map/buildings). Additive, no FKs
// (osm_id natural key), matching scan_coverage_events / world_observations.
export const buildingFootprints = pgTable('building_footprints', {
  id: serial('id').primaryKey(),
  osmId: bigint('osm_id', { mode: 'number' }),
  source: varchar('source', { length: 16 }).default('overpass').notNull(),
  tileKey: varchar('tile_key', { length: 32 }).notNull(),
  minLng: doublePrecision('min_lng').notNull(),
  minLat: doublePrecision('min_lat').notNull(),
  maxLng: doublePrecision('max_lng').notNull(),
  maxLat: doublePrecision('max_lat').notNull(),
  polygon: jsonb('polygon').notNull(),
  buildingType: varchar('building_type', { length: 64 }),
  fetchedAt: timestamp('fetched_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  bboxIdx: index('building_footprints_bbox_idx').on(table.minLng, table.maxLng, table.minLat, table.maxLat),
  tileIdx: index('building_footprints_tile_idx').on(table.tileKey),
  sourceOsmIdx: uniqueIndex('building_footprints_source_osm_idx').on(table.source, table.osmId),
}))
export type BuildingFootprintRow = typeof buildingFootprints.$inferSelect
export type NewBuildingFootprintRow = typeof buildingFootprints.$inferInsert

// Per-tile fetch ledger: a ~1.1km tile (even an empty one) is fetched from
// Overpass at most once per TTL.
export const buildingFootprintTiles = pgTable('building_footprint_tiles', {
  tileKey: varchar('tile_key', { length: 32 }).primaryKey(),
  fetchedAt: timestamp('fetched_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  footprintCount: integer('footprint_count').default(0).notNull(),
})
export type BuildingFootprintTileRow = typeof buildingFootprintTiles.$inferSelect
export type NewBuildingFootprintTileRow = typeof buildingFootprintTiles.$inferInsert
```
- [ ] Step 3: Create `0032_add_building_footprints.sql` with `CREATE TABLE IF NOT EXISTS` for both tables + the three `CREATE INDEX IF NOT EXISTS`, and a header noting it is applied via psql BY BLAKE + insert a `drizzle.__drizzle_migrations` row after. (Columns exactly mirror the schema above; `polygon jsonb`, bbox `double precision`, `osm_id bigint`.)
- [ ] Step 4: `cd site && npx tsc --noEmit` → PASS.
- [ ] Step 5: Commit `schema: add building_footprints + building_footprint_tiles (0032 hand-written migration)`

---

## Task 3 — Server route `GET /api/map/buildings?bbox=`

**Files:**
- Create: `site/src/app/api/map/buildings/route.ts`
- Test: `site/src/app/api/map/buildings/route.test.ts` (node:test + tsx)

**Interfaces:** `GET ?bbox=minLng,minLat,maxLng,maxLat` → `{ buildings: {polygon:number[][], buildingType:string|null, source:string}[], success:boolean }`. Pure exports: `tilesForBbox`, `parseOverpassElements`.

- [ ] Step 1: Write failing test `route.test.ts` (node:test):
```ts
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { tilesForBbox, parseOverpassElements } from './route'

test('tilesForBbox one tile for sub-tile bbox', () => {
  const tiles = tilesForBbox(-87.715, 41.862, -87.714, 41.863)
  assert.equal(tiles.length, 1)
  assert.equal(tiles[0].key, 'b:4186:-8772')
})
test('tilesForBbox covers multi-tile bbox', () => {
  assert.ok(tilesForBbox(-87.72, 41.86, -87.70, 41.87).length > 1)
})
test('parseOverpassElements builds [lng,lat] ring + bbox', () => {
  const p = parseOverpassElements({ elements: [{ type:'way', id:42, tags:{building:'house'},
    geometry:[{lat:41.860,lon:-87.720},{lat:41.860,lon:-87.710},{lat:41.866,lon:-87.710},{lat:41.860,lon:-87.720}] }] })
  assert.equal(p.length, 1); assert.equal(p[0].osmId, 42); assert.equal(p[0].buildingType, 'house')
  assert.deepEqual(p[0].polygon[0], [-87.720, 41.860])
})
test('parseOverpassElements skips <3-point ways', () => {
  assert.equal(parseOverpassElements({ elements:[{type:'way',id:1,geometry:[{lat:1,lon:1},{lat:2,lon:2}]}] }).length, 0)
})
```
- [ ] Step 2: Run — expect FAIL: `cd site && DATABASE_URL=postgres://test:test@localhost/test npx tsx --test src/app/api/map/buildings/route.test.ts`
- [ ] Step 3: Create `route.ts` — `export const dynamic='force-dynamic'`; `TILE_DEGREES=0.01`, `TTL_MS=30d`, `MAX_TILES=12`, `OVERPASS_URL` env-overridable. Pure `tilesForBbox`, `parseOverpassElements` (see spec/agent draft). `GET`: validate bbox (400 on bad), quantize to tiles (400 if >MAX_TILES), read `buildingFootprintTiles` ledger, for each stale/missing tile try `fetchOverpassTile` (25s AbortController; `[out:json];(way["building"](s,w,n,e););out geom;`) then sequential `onConflictDoUpdate` upserts into `buildingFootprints` (target `[source, osmId]`) + ledger upsert; catch per-tile (log, leave cached, do NOT mark fresh). Finally bbox-intersection select (lte/gte on min/max cols, limit 5000) → map to `{polygon, buildingType, source}` → `{buildings, success:true}`. neon-http: sequential awaits, no transaction.
- [ ] Step 4: Run — expect PASS; also `cd site && npx tsc --noEmit` → PASS.
- [ ] Step 5: Commit `Add GET /api/map/buildings with lazy Overpass fetch + Neon tile cache`

---

## Task 4 — Client `BuildingFootprintStore` (SQLDelight) + DI wiring

**Files:**
- Create: `shared/src/commonMain/sqldelight/com/gcc/shared/cache/BuildingFootprintCache.sq`
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/model/BuildingFootprints.kt`
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/cache/BuildingFootprintStore.kt`
- Test: `shared/src/jvmTest/kotlin/com/gcc/shared/cache/BuildingFootprintStoreTest.kt` (jvmTest — SQLite driver available; mirror `ScanAreaCacheStoreTest`)
- Modify: `composeApp/.../di/AppModule.kt` + `OfflineAppModule.kt` (register `single { BuildingFootprintStore(get()) }` + import)

**Interfaces:** `BuildingPolygon(polygon: List<List<Double>>, buildingType: String?=null, source: String="overpass")`, `BuildingFootprintsResponse(buildings, success)`. `BuildingFootprintStore(driver)` with `put/get/getAll/prune` + companion `tileKeyFor(lat,lng)`, `tileBbox(tileKey)`, `DEFAULT_MAX_AGE_MS`, `TILE_DEGREES=0.01`.

- [ ] Step 1: DTO `BuildingFootprints.kt` (`@Serializable BuildingPolygon`, `BuildingFootprintsResponse`).
- [ ] Step 2: `.sq` schema `buildingFootprintCache(tileKey TEXT PK, buildingsJson TEXT, cachedAtMs INTEGER)` + queries `selectByTile/selectAll/insertOrReplace/deleteOlderThan/clear`. (SQLDelight auto-discovers `.sq` under this dir into `GccDatabase` — no gradle edit.)
- [ ] Step 3: Failing store test (in-memory JdbcSqliteDriver + `GccDatabase.Schema.create`): put→get round-trip, missing→null, prune drops stale, `tileKeyFor(41.863,-87.715)=="b:4186:-8772"` and `tileBbox` brackets the point.
- [ ] Step 4: Run — expect FAIL: `./gradlew :shared:jvmTest --tests "com.gcc.shared.cache.BuildingFootprintStoreTest"`
- [ ] Step 5: Implement `BuildingFootprintStore` (queries via `GccDatabase(driver).buildingFootprintCacheQueries`; JSON via `GccJson`; `decodeRow` swallows corrupt rows → null; companion `tileKeyFor`/`tileBbox` with `TILE_DEGREES=0.01`, `DEFAULT_MAX_AGE_MS=30d`).
- [ ] Step 6: Run — expect PASS.
- [ ] Step 7: Register `single { BuildingFootprintStore(get()) }` + import in BOTH `AppModule.kt` and `OfflineAppModule.kt`.
- [ ] Step 8: `./gradlew :composeApp:compileKotlinJvm` → PASS.
- [ ] Step 9: Commit `Add BuildingFootprintStore SQLDelight cache + DTO + DI wiring`

---

## Task 5 — `GccApiClient.getBuildingFootprints(bbox)`

**Files:** Modify `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt` (after `getMapCoverage`).

**Interfaces:** `suspend fun getBuildingFootprints(bbox: String): List<BuildingPolygon>`.

- [ ] Step 1: Add method mirroring `getMapCoverage` (GET `$baseUrl/api/map/buildings`, `parameter("bbox", bbox)`, `checkUnauthorized` + `httpErrorMessage` on non-success, `body<BuildingFootprintsResponse>().buildings`).
- [ ] Step 2: `./gradlew :shared:compileKotlinJvm` → PASS.
- [ ] Step 3: Commit `GccApiClient.getBuildingFootprints(bbox)`

---

## Task 6 — Rewrite `isInsideMappedBuilding` to point-in-polygon + registry

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/BuildingData.kt` (import `pointInGeoJsonRing`; add `MappedBuildingRegistry`; rewrite `isInsideMappedBuilding`)
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/ui/map/BuildingDataTest.kt`

**Interfaces:** `object MappedBuildingRegistry { setRingsForTile(tileKey, rings); clear(); current(): List<List<List<Double>>>; hasFootprints(): Boolean }`; unchanged `fun isInsideMappedBuilding(lat, lng): Boolean`.

- [ ] Step 1: Failing test: fallback-to-rectangles when registry empty; registered footprint (far from NL) takes over; when footprints registered, old NL rectangle center is NO LONGER inside (registry wins). `@AfterTest` clears registry.
- [ ] Step 2: Run — expect FAIL: `./gradlew :composeApp:jvmTest --tests "com.gcc.app.ui.map.BuildingDataTest"`
- [ ] Step 3: Implement `MappedBuildingRegistry` (`@Synchronized` map keyed by tile, flattened cache) + rewrite:
```kotlin
fun isInsideMappedBuilding(lat: Double, lng: Double): Boolean {
    val rings = MappedBuildingRegistry.current()
    if (rings.isNotEmpty()) return rings.any { pointInGeoJsonRing(lng = lng, lat = lat, ring = it) }
    return northLawndaleBuildings.any { b -> lat <= b.north && lat >= b.south && lng <= b.east && lng >= b.west }
}
```
- [ ] Step 4: Run — expect PASS.
- [ ] Step 5: Commit `isInsideMappedBuilding: point-in-polygon over cached footprints, rectangles as offline fallback`

---

## Task 7 — `BuildingFootprintLoader` + gate wiring + INDOOR integration test

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/BuildingFootprintLoader.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/BuildingFootprintLoaderTest.kt` (+ append to `OutdoorGateTest.kt`)
- Modify: `AppModule.kt` + `OfflineAppModule.kt` (register loader; RouteScanViewModel factory +1 `get()`)
- Modify: `RouteScanViewModel.kt` (6th ctor param; drive loader in SCOUTING before `onMapEvidence`)
- Modify: `SpotScreen.kt` (koinInject loader; drive it in the location `LaunchedEffect`)

**Interfaces:** `class BuildingFootprintLoader(apiClient, store) { suspend fun ensureFootprintsFor(lat, lng, nowMs) }` — cache-first seed of `MappedBuildingRegistry`, lazy fetch on miss/stale, NEVER throws.

- [ ] Step 1: Failing loader test — cache-hit (fresh row) seeds registry WITHOUT network (dummy apiClient), `isInsideMappedBuilding` true inside the cached square. `@AfterTest` clears registry.
- [ ] Step 2: Run — expect FAIL.
- [ ] Step 3: Implement loader (per-session `loadedTiles` set; cache-first via `store.get`; if fresh, seed + mark loaded + return; else `try apiClient.getBuildingFootprints(tileBbox)` catch→log via `DiagnosticsLog` + return (not marked loaded → retried); on success `store.put` + `store.prune` + `setRingsForTile` + mark loaded).
- [ ] Step 4: Run — expect PASS.
- [ ] Step 5: DI: `single { BuildingFootprintLoader(get(), get()) }` + import in both modules; RouteScanViewModel factory → six `get()`.
- [ ] Step 6: RouteScanViewModel: add `private val buildingFootprintLoader: BuildingFootprintLoader` as the 6th ctor param + import; in the SCOUTING branch (inside the existing `locationFlow.collect`) call `buildingFootprintLoader.ensureFootprintsFor(lat, lng, now)` right before `outdoorGate.onMapEvidence(...)` (call site unchanged).
- [ ] Step 7: SpotScreen: `val buildingFootprintLoader = koinInject<BuildingFootprintLoader>()` + import; in the location `LaunchedEffect`, precede each `gate.onMapEvidence(...)` (initial + `locationFlow.collect`) with `buildingFootprintLoader.ensureFootprintsFor(loc.lat, loc.lng, now)`.
- [ ] Step 8: Append integration test to `OutdoorGateTest.kt`: register a ring, `isInsideMappedBuilding` → true, `gate.onMapEvidence(insideBuilding=true, t0)` → `OutdoorState.INDOOR` (MAP_STREAK_TO_FLIP=1); clear registry after.
- [ ] Step 9: Run gate+building suite — expect PASS: `./gradlew :composeApp:jvmTest --tests "com.gcc.app.platform.*" --tests "com.gcc.app.ui.map.*"`
- [ ] Step 10: `./gradlew :composeApp:compileKotlinJvm :shared:compileKotlinJvm` → PASS.
- [ ] Step 11: Commit `BuildingFootprintLoader + gate wiring (RouteScanViewModel, SpotScreen) — inside-polygon flips INDOOR`

---

## Self-review

Every WS1 requirement maps to a task: table (T2), route + lazy Overpass + tile TTL (T3), Overpass-failure graceful/never-block (T3+T7 loader never-throws), schema policy (T2, Tables.kt skip noted), client store + DI (T4), point-in-polygon over footprints (T6), shared ray-cast util (T1), NL rectangles fallback (T6), unchanged gate call sites now fed real containment (T7), inside→instant INDOOR (T7 step 8), client fetch mirrors getMapCoverage (T5). No placeholders. Types consistent: GeoJSON ring `List<List<Double>>`/`number[][]`, vertex `[lng,lat]`, tile key `b:{latIdx}:{lngIdx}` with `TILE_DEGREES=0.01` on both sides, DTO `BuildingPolygon` distinct from the existing `BuildingFootprint` rectangle class, `isInsideMappedBuilding(lat,lng)` signature byte-identical.
