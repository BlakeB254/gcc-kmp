# Unified Scan Ecosystem Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the unmerged route-queue/scan branch to `main`; unify spot-reports and route-scan hotspots into one geo-referenced world-observation model with supersession and chronological history; add admin-drawn private-property exclusion zones; build a first-time AR camera-view experience for the in-route worker flow (boundary visualization, directional guidance, live fog-of-war reveal); and shift both capture flows (spot reports and route fulfillment) to expect an environment scan by default, with flat-photo capture as an explicit, rare fallback.

**Architecture:** New `worldObservations` table is populated by a shared write helper called from both existing capture flows (`scanHotspots`, `communityReports`), which keep owning their own domain workflows unchanged. `blockExclusionZones` layers on the same write helper. The AR experience is a new Kotlin/Native `ArRouteOverlay` (SceneKit/`ARSCNView`, matching the codebase's existing cinterop pattern — not RealityKit, which lacks reliable Objective-C interop for Kotlin/Native) embedded into Compose via `UIKitView`, consuming the already-existing `CoverageTracker` grid.

**Tech Stack:** Next.js 16 App Router + Drizzle ORM + Neon Postgres (site/); Kotlin Multiplatform (`shared/`, `composeApp/`); Kotlin/Native cinterop to `platform.ARKit`/`platform.SceneKit` (iosMain); MapLibre GL (`react-map-gl/maplibre`) for map rendering.

## Global Constraints

- Drizzle (`site/src/db/schema.ts`) is the only authoritative schema source. Deploy via `npm run db:push` (not `drizzle-kit generate` — the migration chain is known-divergent per repo `CLAUDE.md`; hand-generated migrations for this work would add to that mess).
- `server/` (Ktor/Exposed) is confirmed **not in production** (`docs/governance/ARCHITECTURE-and-source-of-truth.md`). Do not add these new tables to `server/src/main/kotlin/com/gcc/server/db/tables/`. Only touch `shared/` Kotlin DTOs where the app actually consumes the new data.
- `site/` has **no JS/TS test runner** (confirmed: no vitest/jest in `package.json`, no `.test.ts` files; CI runs only `npx tsc --noEmit` and `npm run build`). Do not introduce a new test framework. Verification for TS tasks is: `npx tsc --noEmit`, `npm run build`, and a one-off manual check script run via `npx tsx` where logic needs sanity-checking before wiring into a route.
- `shared/` Kotlin **does** have `kotlin.test` set up already (`shared/src/commonTest/kotlin/com/gcc/shared/geo/GeofenceVerificationTest.kt`). Use real `kotlin.test` tests for new shared Kotlin logic, following that file's pattern.
- `composeApp/iosMain` (Kotlin/Native) **cannot be compiled on this Linux machine** — only `:shared` (JVM/common), Android, and desktop Compose targets build here. Any iOS-specific task in this plan says explicitly which parts can be verified on this machine (compiles as commonMain/expect-only) vs. which require the MacBook (`cdx-mobile build ios --sync`).
- Cell size for all new geo-grouping in this plan: **10 meters**, matching the existing 15m checkpoint geofence scale already used elsewhere in the schema. Use `METERS_PER_DEG_LAT = 111_000` / `METERS_PER_DEG_LNG = 82_300` from `site/src/lib/geo-utils.ts` (already defined, do not redefine).
- Never push directly to `main` or merge without opening a PR — even for Task 1's fast-forward. Push the branch, open a draft PR, let the human merge it.

---

## Phase 0 — Ship the existing branch

### Task 1: Fast-forward `main` onto the unmerged branch and open a PR

**Files:** none created or modified — this is a git operation, verified by build output.

**Interfaces:** N/A (prerequisite for every later task — later tasks assume `site/src/lib/route-queue.ts`, `site/src/lib/notifications/apns.ts`, and the `docs/governance/*` doc set already exist on the working branch).

- [ ] **Step 1: Confirm the fast-forward is still valid**

```bash
git fetch github macbook origin
git merge-base --is-ancestor main github/feat/route-offer-notifications-live && echo "FF-safe" || echo "DIVERGED — stop and re-plan"
```
Expected: `FF-safe`. If it prints `DIVERGED`, stop — `main` has moved since this plan was written and this task needs re-scoping (a real three-way merge, not a fast-forward).

- [ ] **Step 2: Merge the branch into this worktree's branch**

```bash
git merge --ff-only github/feat/route-offer-notifications-live
```
Expected: fast-forward output, no conflicts (there cannot be any — see Step 1).

- [ ] **Step 3: Verify the live heatmap files are unaffected**

```bash
git show HEAD~7 -- site/src/components/map/CombinedMap.tsx site/src/components/map/BaseMap.tsx | wc -l
```
Expected: `0` (no diff exists in the last 7 commits touching these files — confirms the merge didn't change map rendering).

- [ ] **Step 4: Typecheck and build**

```bash
cd site && npx tsc --noEmit && npm run build
```
Expected: both succeed with no errors.

- [ ] **Step 5: Push and open a draft PR**

```bash
git push -u origin worktree-unified-scan-ecosystem
gh pr create --draft --title "Ship route-queue/scans + unified world-observation ecosystem" \
  --body "Fast-forwards main onto feat/route-offer-notifications-live (route-queue API, APNs push, cron, governance docs), then builds on top of it. See docs/superpowers/specs/2026-07-01-unified-scan-ecosystem-and-immersive-route-design.md for the full design."
```

- [ ] **Step 6: Commit checkpoint (nothing to commit here — the merge commit already exists; this step is a no-op marker for the plan's commit cadence)**

---

## Phase 1 — Unified world observation model

### Task 2: Add the `worldObservations` table

**Files:**
- Modify: `site/src/db/schema.ts` (append near `scanHotspots`, ~line 1495, after the `ScanHotspotRow`/`NewScanHotspotRow` type exports)

**Interfaces:**
- Produces: `worldObservations` Drizzle table, `WorldObservationRow`/`NewWorldObservationRow` types — consumed by Tasks 3–8.

- [ ] **Step 1: Add the table definition**

Insert immediately after the existing `scanHotspots` block (after line ~1495, `export type NewScanHotspotRow = typeof scanHotspots.$inferInsert`):

```ts
// World observation model — the unified map/digital-twin layer fed by both
// route-scan hotspots and spot reports. See docs/superpowers/specs/2026-07-01-
// unified-scan-ecosystem-and-immersive-route-design.md §2.
export const worldObservations = pgTable('world_observations', {
  id: serial('id').primaryKey(),
  scanHotspotId: integer('scan_hotspot_id'),
  communityReportId: integer('community_report_id'),
  sourceType: varchar('source_type', { length: 20 }).notNull(), // 'route_scan' | 'spot_report'
  lat: doublePrecision('lat').notNull(),
  lng: doublePrecision('lng').notNull(),
  locationCellId: varchar('location_cell_id', { length: 32 }).notNull(),
  itemType: varchar('item_type', { length: 50 }), // null when state='clean' (nothing dirty found)
  state: varchar('state', { length: 10 }).notNull(), // 'dirty' | 'clean'
  severity: integer('severity'),
  estimatedVolumeLiters: doublePrecision('estimated_volume_liters'),
  estimatedWeightLbs: doublePrecision('estimated_weight_lbs'),
  confidence: doublePrecision('confidence'),
  mediaUrl: varchar('media_url', { length: 500 }),
  depthData: text('depth_data'),
  arAnchorData: text('ar_anchor_data'),
  capturedAt: timestamp('captured_at', { mode: 'string', withTimezone: true }).notNull(),
  isCurrent: boolean('is_current').default(true).notNull(),
  excludedPrivateProperty: boolean('excluded_private_property').default(false).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

export type WorldObservationRow = typeof worldObservations.$inferSelect
export type NewWorldObservationRow = typeof worldObservations.$inferInsert
```

- [ ] **Step 2: Push schema to the dev database**

```bash
cd site && npm run db:push
```
Expected: prompts to confirm creating `world_observations` table; accept. Verify:

```bash
npx tsx -e "
import { db } from './src/db'
import { sql } from 'drizzle-orm'
db.execute(sql\`select column_name from information_schema.columns where table_name = 'world_observations' order by ordinal_position\`).then(r => { console.log(r.rows.map((x: any) => x.column_name)); process.exit(0) })
"
```
Expected: prints all 20 column names (`id`, `scan_hotspot_id`, ..., `created_at`).

- [ ] **Step 3: Typecheck**

```bash
npx tsc --noEmit
```
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add site/src/db/schema.ts
git commit -m "feat(db): add worldObservations table for unified scan/spot-report model"
```

---

### Task 3: Grid-cell + supersession write helper

**Files:**
- Create: `site/src/lib/world-observations.ts`

**Interfaces:**
- Consumes: `pointInPolygon`, `METERS_PER_DEG_LAT`, `METERS_PER_DEG_LNG` from `@/lib/geo-utils`; `db` from `@/db`; `worldObservations`, `blockExclusionZones` (added in Task 9 — this file references it now but the import is added in Task 9's step; until then, exclusion-zone lookup is a no-op stub returning `[]`, replaced in Task 9) from `@/db/schema`.
- Produces:
  - `gridCell(lat: number, lng: number, cellSizeMeters?: number): string` — deterministic cell key.
  - `type RecordObservationInput = { sourceType: 'route_scan' | 'spot_report'; scanHotspotId?: number; communityReportId?: number; lat: number; lng: number; itemType: string | null; state: 'dirty' | 'clean'; severity?: number; estimatedVolumeLiters?: number; estimatedWeightLbs?: number; confidence?: number; mediaUrl?: string; depthData?: string; arAnchorData?: string; capturedAt: string; blockId?: number }`
  - `async function recordWorldObservation(input: RecordObservationInput): Promise<WorldObservationRow>`

- [ ] **Step 1: Write `gridCell`**

```ts
import { db } from '@/db'
import { worldObservations, blockExclusionZones } from '@/db/schema'
import { eq, and } from 'drizzle-orm'
import { pointInPolygon, METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from '@/lib/geo-utils'

const CELL_SIZE_METERS = 10

/**
 * Deterministic ~10m grid cell key for a lat/lng, used to group observations
 * of "the same physical spot" across time and across source type.
 */
export function gridCell(lat: number, lng: number, cellSizeMeters = CELL_SIZE_METERS): string {
  const latStep = cellSizeMeters / METERS_PER_DEG_LAT
  const lngStep = cellSizeMeters / METERS_PER_DEG_LNG
  const latIdx = Math.floor(lat / latStep)
  const lngIdx = Math.floor(lng / lngStep)
  return `${latIdx}:${lngIdx}`
}
```

- [ ] **Step 2: Sanity-check `gridCell` with a one-off script (no permanent test file — no test runner in this project, see Global Constraints)**

```bash
cd site && npx tsx -e "
import { gridCell } from './src/lib/world-observations'
// Two points ~3m apart should land in the same cell; ~30m apart should not.
const a = gridCell(41.8600, -87.7000)
const b = gridCell(41.86003, -87.7000) // ~3.3m north
const c = gridCell(41.8603, -87.7000)  // ~33m north
console.log({ a, b, c, sameCellNearby: a === b, differentCellFar: a !== c })
"
```
Expected: `sameCellNearby: true, differentCellFar: true`.

- [ ] **Step 3: Write `recordWorldObservation` with the supersession algorithm**

Append to the same file:

```ts
export interface RecordObservationInput {
  sourceType: 'route_scan' | 'spot_report'
  scanHotspotId?: number
  communityReportId?: number
  lat: number
  lng: number
  itemType: string | null
  state: 'dirty' | 'clean'
  severity?: number
  estimatedVolumeLiters?: number
  estimatedWeightLbs?: number
  confidence?: number
  mediaUrl?: string
  depthData?: string
  arAnchorData?: string
  capturedAt: string
  blockId?: number
}

/**
 * Record a new observation, superseding whatever was previously "current"
 * at the same physical spot. Nothing is ever deleted or updated in place
 * except the isCurrent flag — every insert is a new immutable row, so the
 * full chronological history (dirty -> clean -> dirty) survives per cell.
 */
export async function recordWorldObservation(input: RecordObservationInput) {
  const cellId = gridCell(input.lat, input.lng)

  const excluded = input.blockId != null
    ? await isWithinExclusionZone(input.blockId, input.lat, input.lng)
    : false

  if (input.state === 'clean') {
    // A clean re-scan supersedes ALL currently-dirty items in this cell,
    // regardless of their itemType — a clean scan means "nothing dirty here now."
    await db
      .update(worldObservations)
      .set({ isCurrent: false })
      .where(and(
        eq(worldObservations.locationCellId, cellId),
        eq(worldObservations.state, 'dirty'),
        eq(worldObservations.isCurrent, true)
      ))
  } else {
    // A new dirty observation of the same itemType in the same cell supersedes
    // only the prior observation of that itemType (a couch and glass 1m apart
    // in the same cell are both allowed to be concurrently "current").
    await db
      .update(worldObservations)
      .set({ isCurrent: false })
      .where(and(
        eq(worldObservations.locationCellId, cellId),
        eq(worldObservations.itemType, input.itemType ?? ''),
        eq(worldObservations.isCurrent, true)
      ))
  }

  const [row] = await db
    .insert(worldObservations)
    .values({
      scanHotspotId: input.scanHotspotId ?? null,
      communityReportId: input.communityReportId ?? null,
      sourceType: input.sourceType,
      lat: input.lat,
      lng: input.lng,
      locationCellId: cellId,
      itemType: input.itemType,
      state: input.state,
      severity: input.severity ?? null,
      estimatedVolumeLiters: input.estimatedVolumeLiters ?? null,
      estimatedWeightLbs: input.estimatedWeightLbs ?? null,
      confidence: input.confidence ?? null,
      mediaUrl: input.mediaUrl ?? null,
      depthData: input.depthData ?? null,
      arAnchorData: input.arAnchorData ?? null,
      capturedAt: input.capturedAt,
      isCurrent: true,
      excludedPrivateProperty: excluded,
    })
    .returning()

  return row
}

/**
 * Placeholder until Task 9 adds blockExclusionZones — returns false (no
 * exclusion zones exist yet). Task 9 replaces this implementation in place.
 */
async function isWithinExclusionZone(blockId: number, lat: number, lng: number): Promise<boolean> {
  const zones = await db
    .select()
    .from(blockExclusionZones)
    .where(eq(blockExclusionZones.blockId, blockId))
  for (const zone of zones) {
    const ring = (zone.polygon as any)?.coordinates?.[0] as [number, number][] | undefined
    if (ring && pointInPolygon(lat, lng, ring.map(([lng2, lat2]) => [lat2, lng2] as [number, number]))) {
      return true
    }
  }
  return false
}
```

Note: this step forward-references `blockExclusionZones` from Task 9. Until Task 9 runs, this file will fail to typecheck (the table doesn't exist yet). That's expected and resolved by the end of Task 9 — Tasks 3 and 9 are meant to land together in practice; if executing strictly in order, skip the `npx tsc --noEmit` check at the end of this task and defer it to the end of Task 9.

- [ ] **Step 4: Commit**

```bash
git add site/src/lib/world-observations.ts
git commit -m "feat(world-observations): grid-cell supersession write helper"
```

---

### Task 4: Wire route-scan hotspots into the world-observation model

**Files:**
- Modify: `site/src/app/api/scans/[id]/hotspots/route.ts` (POST handler, after the existing insert)
- Modify: `site/src/app/api/scans/[id]/hotspots/[hid]/route.ts` (PATCH handler, after the existing update)

**Interfaces:**
- Consumes: `recordWorldObservation` from `@/lib/world-observations` (Task 3); `findRouteScan` already imported in both files (need its `blockId`/`routeId` — check `RouteScan` shape for a `blockId` field before wiring `blockId` through; if `RouteScan` has no direct `blockId`, omit the `blockId` param on this call rather than guessing a wrong field name).
- Produces: nothing new consumed elsewhere — these are terminal write-path integrations.

- [ ] **Step 1: Check whether `RouteScan`/route rows carry a `blockId`**

```bash
cd site && grep -n "blockId" src/lib/mobile-route-scan-response.ts src/db/schema.ts | grep -i "routeScans\|blocks\." 
```
Read the output. If `routeScans` (or the `routes` table it joins to) has a `blockId` column, use `scan.blockId` (or the joined route's `blockId`) in Step 2/3 below. If not, omit `blockId` from the `recordWorldObservation` call (exclusion-zone checks will simply return `false` for that call until block linkage is added — this is a known, acceptable gap; note it in the commit message).

- [ ] **Step 2: Call `recordWorldObservation` after hotspot detection (POST)**

In `site/src/app/api/scans/[id]/hotspots/route.ts`, after the existing `.returning()` insert (around line 70) and before the `return NextResponse.json(...)`:

```ts
    const trashTypesArr = normalizeTags(body?.trashTypes)
    await recordWorldObservation({
      sourceType: 'route_scan',
      scanHotspotId: hotspot.id,
      lat,
      lng,
      itemType: trashTypesArr[0] ?? 'general_litter',
      state: 'dirty',
      severity: hotspot.severity,
      estimatedVolumeLiters: hotspot.estimatedVolumeLiters ?? undefined,
      estimatedWeightLbs: hotspot.estimatedWeightLbs ?? undefined,
      confidence: hotspot.confidence,
      mediaUrl: hotspot.beforePhotoUrl,
      depthData: hotspot.depthData ?? undefined,
      arAnchorData: hotspot.arAnchorData ?? undefined,
      capturedAt: hotspot.detectedAt,
      // blockId: <wire in per Step 1's finding, or omit>
    })
```

Add the import at the top: `import { recordWorldObservation } from '@/lib/world-observations'`.

- [ ] **Step 3: Call `recordWorldObservation` when a hotspot is marked cleaned (PATCH)**

In `site/src/app/api/scans/[id]/hotspots/[hid]/route.ts`, after the existing update `.returning()` (around line 55), only when `status === 'cleaned'`:

```ts
    if (updated && status === 'cleaned') {
      await recordWorldObservation({
        sourceType: 'route_scan',
        scanHotspotId: updated.id,
        lat: updated.lat,
        lng: updated.lng,
        itemType: null,
        state: 'clean',
        mediaUrl: updated.afterPhotoUrl ?? undefined,
        capturedAt: updated.cleanedAt ?? new Date().toISOString(),
      })
    }
```

Add the same import.

- [ ] **Step 4: Typecheck**

```bash
npx tsc --noEmit
```
Expected: no errors.

- [ ] **Step 5: Manual verification against the dev server**

```bash
npm run dev &
sleep 3
# Use an existing test worker token + scan id from the dev DB; substitute real values.
curl -s -X POST http://localhost:7350/api/scans/1/hotspots \
  -H "Authorization: Bearer <dev-worker-token>" -H "Content-Type: application/json" \
  -d '{"lat":41.86,"lng":-87.70,"beforePhotoUrl":"https://example.com/x.jpg","trashTypes":["tire"]}'
npx tsx -e "
import { db } from './src/db'
import { worldObservations } from './src/db/schema'
db.select().from(worldObservations).then(r => { console.log(r); process.exit(0) })
"
kill %1
```
Expected: the curl call returns the created hotspot; the `worldObservations` query shows one new row with `itemType: 'tire'`, `state: 'dirty'`, `isCurrent: true`.

- [ ] **Step 6: Commit**

```bash
git add site/src/app/api/scans/
git commit -m "feat(world-observations): wire route-scan hotspot detect/clean into unified model"
```

---

### Task 5: Wire spot reports into the world-observation model

**Files:**
- Modify: `site/src/app/api/spots/route.ts` (POST handler)
- Modify: `site/src/app/api/spots/[id]/volunteer/[cleanupId]/complete/route.ts`

**Interfaces:**
- Consumes: `recordWorldObservation` from `@/lib/world-observations`.

- [ ] **Step 1: Explode `onDeviceAiResult` into per-item observations on spot-report creation**

In `site/src/app/api/spots/route.ts`, after the `const [newReport] = await db.insert(communityReports)...returning()` block (around line 245), before the reverse-geocode call:

```ts
    const detectedItems: { itemType: string; severity?: number; confidence?: number }[] =
      Array.isArray(onDeviceAiResult?.items) && onDeviceAiResult.items.length > 0
        ? onDeviceAiResult.items.map((it: any) => ({
            itemType: it.type ?? it.itemType ?? 'general_litter',
            severity: Number.isFinite(Number(it.severity)) ? Number(it.severity) : undefined,
            confidence: Number.isFinite(Number(it.confidence)) ? Number(it.confidence) : undefined,
          }))
        : [{ itemType: (effectiveTags?.[0] as string) ?? 'general_litter' }]

    for (const item of detectedItems) {
      await recordWorldObservation({
        sourceType: 'spot_report',
        communityReportId: newReport.id,
        lat,
        lng,
        itemType: item.itemType,
        state: 'dirty',
        severity: item.severity,
        confidence: item.confidence,
        mediaUrl: Array.isArray(mediaUrls) && mediaUrls.length > 0 ? mediaUrls[0] : undefined,
        depthData: depthAnalysis ? JSON.stringify(depthAnalysis) : undefined,
        capturedAt: newReport.capturedAt ?? new Date().toISOString(),
      })
    }
```

Add the import: `import { recordWorldObservation } from '@/lib/world-observations'`.

- [ ] **Step 2: Read the volunteer-cleanup-complete route to find its update shape**

```bash
cd site && cat src/app/api/spots/\[id\]/volunteer/\[cleanupId\]/complete/route.ts
```
Read the full file. Find where it marks the cleanup/report complete and what lat/lng/report fields are available at that point (likely joins back to `communityReports` for `coordinates`).

- [ ] **Step 3: Call `recordWorldObservation` with `state: 'clean'` at the point the file marks the report/cleanup complete**

Add the same pattern as Task 4 Step 3, extracting `lat`/`lng` from the report's `coordinates` column (handle both `[lng, lat]` array and `{lat, lng}` object shapes — reuse the normalization already done in `site/src/app/api/spots/route.ts` GET handler, lines 76–84, as a reference for the shape-handling logic). Import `recordWorldObservation`.

- [ ] **Step 4: Typecheck**

```bash
npx tsc --noEmit
```

- [ ] **Step 5: Commit**

```bash
git add site/src/app/api/spots/
git commit -m "feat(world-observations): wire spot-report create/cleanup-complete into unified model"
```

---

### Task 6: One-time backfill script

**Files:**
- Create: `site/scripts/backfill-world-observations.ts`

**Interfaces:**
- Consumes: `recordWorldObservation` from `@/lib/world-observations`; `scanHotspots`, `communityReports` from `@/db/schema`.

- [ ] **Step 1: Write the script**

```ts
/**
 * One-time backfill: convert existing scanHotspots + communityReports rows
 * into worldObservations, in chronological order, so history exists from
 * day one instead of only going forward. Safe to re-run (idempotent per
 * source row — checks scanHotspotId/communityReportId before inserting).
 */
import { db } from '../src/db'
import { scanHotspots, communityReports, worldObservations } from '../src/db/schema'
import { eq, asc } from 'drizzle-orm'
import { recordWorldObservation } from '../src/lib/world-observations'

async function alreadyBackfilled(column: 'scanHotspotId' | 'communityReportId', id: number): Promise<boolean> {
  const existing = await db
    .select({ id: worldObservations.id })
    .from(worldObservations)
    .where(eq(worldObservations[column], id))
    .limit(1)
  return existing.length > 0
}

async function backfillHotspots() {
  const rows = await db.select().from(scanHotspots).orderBy(asc(scanHotspots.detectedAt))
  let count = 0
  for (const hotspot of rows) {
    if (await alreadyBackfilled('scanHotspotId', hotspot.id)) continue
    const trashTypes = JSON.parse(hotspot.trashTypes || '[]')
    await recordWorldObservation({
      sourceType: 'route_scan',
      scanHotspotId: hotspot.id,
      lat: hotspot.lat,
      lng: hotspot.lng,
      itemType: trashTypes[0] ?? 'general_litter',
      state: 'dirty',
      severity: hotspot.severity,
      estimatedVolumeLiters: hotspot.estimatedVolumeLiters ?? undefined,
      estimatedWeightLbs: hotspot.estimatedWeightLbs ?? undefined,
      confidence: hotspot.confidence,
      mediaUrl: hotspot.beforePhotoUrl,
      depthData: hotspot.depthData ?? undefined,
      arAnchorData: hotspot.arAnchorData ?? undefined,
      capturedAt: hotspot.detectedAt,
    })
    count++
    if (hotspot.status === 'cleaned' && hotspot.cleanedAt) {
      await recordWorldObservation({
        sourceType: 'route_scan',
        scanHotspotId: hotspot.id,
        lat: hotspot.lat,
        lng: hotspot.lng,
        itemType: null,
        state: 'clean',
        mediaUrl: hotspot.afterPhotoUrl ?? undefined,
        capturedAt: hotspot.cleanedAt,
      })
      count++
    }
  }
  console.log(`Backfilled ${count} observations from scanHotspots`)
}

async function backfillSpotReports() {
  const rows = await db.select().from(communityReports).orderBy(asc(communityReports.createdAt))
  let count = 0
  for (const report of rows) {
    if (await alreadyBackfilled('communityReportId', report.id)) continue
    const coords = report.coordinates as any
    const [lng, lat] = Array.isArray(coords) ? coords : [coords?.lng ?? 0, coords?.lat ?? 0]
    if (!lat || !lng) continue
    const tags = Array.isArray(report.tags) ? report.tags : []
    await recordWorldObservation({
      sourceType: 'spot_report',
      communityReportId: report.id,
      lat,
      lng,
      itemType: tags[0] ?? 'general_litter',
      state: 'dirty',
      mediaUrl: Array.isArray(report.mediaIds) && report.mediaIds.length > 0 ? String(report.mediaIds[0]) : undefined,
      capturedAt: report.capturedAt ?? report.createdAt,
    })
    count++
  }
  console.log(`Backfilled ${count} observations from communityReports`)
}

async function main() {
  await backfillHotspots()
  await backfillSpotReports()
  process.exit(0)
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
```

- [ ] **Step 2: Run against the dev database and verify counts**

```bash
cd site && npx tsx scripts/backfill-world-observations.ts
npx tsx -e "
import { db } from './src/db'
import { worldObservations } from './src/db/schema'
import { sql } from 'drizzle-orm'
db.select({ c: sql<number>\`count(*)\` }).from(worldObservations).then(r => { console.log(r); process.exit(0) })
"
```
Expected: script prints two non-negative counts; the total-count query returns a number ≥ the sum printed (accounting for any live rows already created by Tasks 4/5's manual test).

- [ ] **Step 3: Commit**

```bash
git add site/scripts/backfill-world-observations.ts
git commit -m "feat(world-observations): one-time backfill script for existing hotspots + spot reports"
```

---

### Task 7: Map observations API endpoint

**Files:**
- Create: `site/src/app/api/map/observations/route.ts`

**Interfaces:**
- Consumes: `worldObservations` from `@/db/schema`.
- Produces: `GET /api/map/observations?bbox=minLng,minLat,maxLng,maxLat` → `{ observations: Array<{ id, lat, lng, itemType, state, severity, mediaUrl, sourceType }>, success: true }` — consumed by Task 8's map component.

- [ ] **Step 1: Write the route**

```ts
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { worldObservations } from '@/db/schema'
import { and, eq, between } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const bboxParam = searchParams.get('bbox')

    const filters = [
      eq(worldObservations.isCurrent, true),
      eq(worldObservations.excludedPrivateProperty, false),
    ]

    if (bboxParam) {
      const parts = bboxParam.split(',').map(Number)
      if (parts.length === 4 && parts.every(Number.isFinite)) {
        const [minLng, minLat, maxLng, maxLat] = parts
        filters.push(between(worldObservations.lng, minLng, maxLng))
        filters.push(between(worldObservations.lat, minLat, maxLat))
      }
    }

    const rows = await db
      .select()
      .from(worldObservations)
      .where(and(...filters))
      .limit(2000)

    const observations = rows.map((r) => ({
      id: r.id,
      lat: r.lat,
      lng: r.lng,
      itemType: r.itemType,
      state: r.state,
      severity: r.severity,
      mediaUrl: r.mediaUrl,
      sourceType: r.sourceType,
      capturedAt: r.capturedAt,
    }))

    return NextResponse.json({ observations, success: true })
  } catch (error) {
    console.error('GET /api/map/observations error:', error)
    return NextResponse.json({ observations: [], success: false }, { status: 500 })
  }
}
```

- [ ] **Step 2: Typecheck and manual verify**

```bash
cd site && npx tsc --noEmit
npm run dev &
sleep 3
curl -s "http://localhost:7350/api/map/observations?bbox=-87.72,41.85,-87.68,41.87" | head -c 500
kill %1
```
Expected: JSON with an `observations` array (may be empty if no dev data falls in that bbox — that's fine, confirms the route doesn't error).

- [ ] **Step 3: Commit**

```bash
git add site/src/app/api/map/observations/
git commit -m "feat(world-observations): add GET /api/map/observations endpoint"
```

---

### Task 8: Repoint the map's heatmap/dots to the unified model and add per-item icons

**Files:**
- Modify: `site/src/app/(app)/bounties/page.tsx` (data source for `CombinedMap`'s `spots` prop)
- Modify: `site/src/components/map/CombinedMap.tsx` (add symbol layer)

**Interfaces:**
- Consumes: `GET /api/map/observations` (Task 7).
- Produces: no change to existing prop signatures — `spots` still flows the same way into `CombinedMap`, just sourced differently and carrying an added `itemType` field.

- [ ] **Step 1: Find how `bounties/page.tsx` currently fetches `spots`**

```bash
cd site && grep -n "spots\|/api/spots" src/app/\(app\)/bounties/page.tsx | head -20
```
Read the output to find the existing fetch call and the `SpotReport` shape it builds.

- [ ] **Step 2: Add `itemType` to the `SpotReport` type and fetch from the unified endpoint**

In `site/src/components/map/BountyMap.tsx`, find the `SpotReport` interface (referenced from `CombinedMap.tsx`'s import) and add one field:

```ts
export interface SpotReport {
  // ...existing fields...
  itemType?: string | null
}
```

In `site/src/app/(app)/bounties/page.tsx`, alongside the existing `/api/spots` fetch, add a fetch to `/api/map/observations` (unbounded bbox for now — the existing map already fits bounds client-side) and merge the `itemType` onto matching spots by proximity, OR — simpler and matching the "both source types render the same way" intent — replace the existing spots-fetch entirely with a mapping from `/api/map/observations` results into the `SpotReport` shape the map already expects:

```ts
const obsRes = await fetch('/api/map/observations')
const { observations } = await obsRes.json()
const spots: SpotReport[] = observations.map((o: any) => ({
  id: String(o.id),
  coordinates: [o.lng, o.lat],
  radiusMeters: 15,
  label: o.itemType ?? 'Reported',
  tags: o.itemType ? [o.itemType] : [],
  itemType: o.itemType,
  needsVerification: false,
}))
```

Adjust field names to match whatever the existing `SpotReport`-building code in this file already does for `bountyId`/`affectedArea` if those are used elsewhere on the page — keep any fields Step 1 showed are read elsewhere in the file.

- [ ] **Step 3: Carry `itemType` through `spotGeoJson` in `CombinedMap.tsx`**

In `site/src/components/map/CombinedMap.tsx`, in the `spotGeoJson` `useMemo` (around line 193), add to the `properties` object:

```ts
          itemType: spot.itemType ?? 'general_litter',
```

- [ ] **Step 4: Add a close-zoom symbol layer keyed by item type, alongside the existing dots layer**

Immediately after the existing `combined-spot-center` circle `<Layer>` (around line 665, right before the verification-ring layer), add:

```tsx
            <Layer
              id="combined-spot-icons"
              type="symbol"
              minzoom={SPOT_ZOOM.DOTS_MIN + 1}
              layout={{
                'icon-image': ['get', 'itemType'],
                'icon-size': 0.6,
                'icon-allow-overlap': true,
                'text-field': ['get', 'itemType'],
                'text-size': 9,
                'text-offset': [0, 1.2],
                'text-anchor': 'top',
              }}
              paint={{
                'icon-opacity': [
                  'interpolate', ['linear'], ['zoom'],
                  SPOT_ZOOM.DOTS_MIN + 1, 0, SPOT_ZOOM.DOTS_MIN + 1.5, 1,
                ],
                'text-opacity': [
                  'interpolate', ['linear'], ['zoom'],
                  SPOT_ZOOM.DOTS_MIN + 1, 0, SPOT_ZOOM.DOTS_MIN + 1.5, 1,
                ],
              }}
            />
```

This deliberately does NOT touch `combined-spot-heatmap` or `combined-spot-center` — the existing heatmap/dots crossfade is untouched; the icon layer only activates one zoom level past where dots are already fully opaque, as a close-zoom enhancement layered on top. `icon-image` referencing `['get', 'itemType']` requires matching image IDs to be loaded into the map style; note in the commit message that actual icon assets (tire/couch/branch/etc. sprites) are a separate design-asset task — until icons are loaded, MapLibre falls back to rendering nothing for unmatched `icon-image` IDs (the `text-field` label still renders, so the layer degrades gracefully rather than erroring).

- [ ] **Step 5: Typecheck and build**

```bash
cd site && npx tsc --noEmit && npm run build
```

- [ ] **Step 6: Commit**

```bash
git add site/src/app/\(app\)/bounties/page.tsx site/src/components/map/CombinedMap.tsx site/src/components/map/BountyMap.tsx
git commit -m "feat(map): repoint spot map to unified world-observation model, add per-item-type icon layer"
```

---

## Phase 2 — Private-property exclusion zones

### Task 9: Add `blockExclusionZones` table and complete the enforcement stub from Task 3

**Files:**
- Modify: `site/src/db/schema.ts`
- Modify: `site/src/lib/world-observations.ts` (the `isWithinExclusionZone` stub is already correct from Task 3 — this task just makes the table it references real)

**Interfaces:**
- Produces: `blockExclusionZones` table, `BlockExclusionZoneRow`/`NewBlockExclusionZoneRow` types.

- [ ] **Step 1: Add the table**

In `site/src/db/schema.ts`, after the `blocks` table definition (after its closing `})` — locate via `grep -n "^})" site/src/db/schema.ts` following the `export const blocks = pgTable` line):

```ts
export const blockExclusionZones = pgTable('block_exclusion_zones', {
  id: serial('id').primaryKey(),
  blockId: integer('block_id').notNull(),
  polygon: jsonb('polygon').notNull(), // GeoJSON Polygon: { type: 'Polygon', coordinates: [[[lng,lat],...]] }
  label: varchar('label', { length: 255 }),
  createdByAdminId: integer('created_by_admin_id'),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})

export type BlockExclusionZoneRow = typeof blockExclusionZones.$inferSelect
export type NewBlockExclusionZoneRow = typeof blockExclusionZones.$inferInsert
```

- [ ] **Step 2: Push schema**

```bash
cd site && npm run db:push
```
Accept the prompt to create `block_exclusion_zones`.

- [ ] **Step 3: Typecheck the whole `world-observations.ts` file now that `blockExclusionZones` exists**

```bash
npx tsc --noEmit
```
Expected: no errors — this is the point where Task 3's forward-reference resolves cleanly.

- [ ] **Step 4: Manual verification of `isWithinExclusionZone`**

```bash
npx tsx -e "
import { db } from './src/db'
import { blockExclusionZones } from './src/db/schema'
import { recordWorldObservation } from './src/lib/world-observations'

async function run() {
  const [zone] = await db.insert(blockExclusionZones).values({
    blockId: 1,
    polygon: { type: 'Polygon', coordinates: [[[-87.701, 41.859],[-87.699, 41.859],[-87.699, 41.861],[-87.701, 41.861],[-87.701, 41.859]]] },
    label: 'test gated yard',
  }).returning()
  console.log('zone created', zone.id)

  const inside = await recordWorldObservation({
    sourceType: 'spot_report', lat: 41.860, lng: -87.700, itemType: 'tire', state: 'dirty',
    capturedAt: new Date().toISOString(), blockId: 1,
  })
  console.log('inside zone -> excludedPrivateProperty should be true:', inside.excludedPrivateProperty)

  const outside = await recordWorldObservation({
    sourceType: 'spot_report', lat: 41.870, lng: -87.710, itemType: 'tire', state: 'dirty',
    capturedAt: new Date().toISOString(), blockId: 1,
  })
  console.log('outside zone -> excludedPrivateProperty should be false:', outside.excludedPrivateProperty)
  process.exit(0)
}
run()
"
```
Expected: both printed booleans match their comments.

- [ ] **Step 5: Commit**

```bash
git add site/src/db/schema.ts
git commit -m "feat(exclusion-zones): add blockExclusionZones table, wire into recordWorldObservation"
```

---

### Task 10: Admin exclusion-zone drawer + CRUD API

**Files:**
- Create: `site/src/app/api/admin/blocks/[blockId]/exclusion-zones/route.ts` (GET list, POST create)
- Create: `site/src/app/api/admin/blocks/[blockId]/exclusion-zones/[zoneId]/route.ts` (DELETE)
- Create: `site/src/components/admin/ExclusionZoneDrawer.tsx`

**Interfaces:**
- Consumes: `pointInPolygon` pattern is server-side only — this component only draws and POSTs; it doesn't evaluate containment client-side.
- Produces: `POST /api/admin/blocks/{blockId}/exclusion-zones` body `{ polygon: GeoJSONPolygon, label?: string }` → `{ data: BlockExclusionZoneRow, success: true }`.

- [ ] **Step 1: Check how existing admin routes get their auth/role gate**

```bash
cd site && sed -n '1,15p' src/app/api/admin/blocks/route.ts
```
Read the imports to find the exact `requireAdmin()` (or equivalent) helper used, and copy its import path.

- [ ] **Step 2: Write the list/create route**

```ts
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blockExclusionZones } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth' // adjust import path to match Step 1's finding

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ blockId: string }> }

export async function GET(request: NextRequest, context: RouteContext) {
  const admin = await requireAdmin(request)
  if (!admin) {
    return NextResponse.json({ data: null, success: false, message: 'Not authorized' }, { status: 401 })
  }
  const { blockId } = await context.params
  const zones = await db
    .select()
    .from(blockExclusionZones)
    .where(eq(blockExclusionZones.blockId, parseInt(blockId, 10)))
  return NextResponse.json({ data: zones, success: true })
}

export async function POST(request: NextRequest, context: RouteContext) {
  const admin = await requireAdmin(request)
  if (!admin) {
    return NextResponse.json({ data: null, success: false, message: 'Not authorized' }, { status: 401 })
  }
  const { blockId } = await context.params
  const body = await request.json().catch(() => null)
  if (!body?.polygon?.coordinates?.[0]?.length) {
    return NextResponse.json({ data: null, success: false, message: 'polygon is required' }, { status: 400 })
  }
  const [zone] = await db
    .insert(blockExclusionZones)
    .values({
      blockId: parseInt(blockId, 10),
      polygon: body.polygon,
      label: typeof body.label === 'string' ? body.label : null,
      createdByAdminId: admin.id,
    })
    .returning()
  return NextResponse.json({ data: zone, success: true }, { status: 201 })
}
```

- [ ] **Step 3: Write the delete route**

```ts
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { blockExclusionZones } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth' // adjust to match Step 1

type RouteContext = { params: Promise<{ blockId: string; zoneId: string }> }

export async function DELETE(request: NextRequest, context: RouteContext) {
  const admin = await requireAdmin(request)
  if (!admin) {
    return NextResponse.json({ success: false, message: 'Not authorized' }, { status: 401 })
  }
  const { zoneId } = await context.params
  await db.delete(blockExclusionZones).where(eq(blockExclusionZones.id, parseInt(zoneId, 10)))
  return NextResponse.json({ success: true })
}
```

- [ ] **Step 4: Write `ExclusionZoneDrawer.tsx` — a direct adaptation of `RouteDrawer.tsx`'s click-to-draw pattern**

```bash
cd site && cat src/components/admin/RouteDrawer.tsx
```
Read the full file first (already partially shown in the design spec, but read it in full — the save/reset handlers, the `BaseMap` prop wiring, and the exact JSX structure need copying).

```tsx
'use client'

import { useState, useCallback, useMemo } from 'react'
import { Source, Layer } from 'react-map-gl/maplibre'
import { BaseMap } from '@/components/map/BaseMap'
import { ZOOM } from '@/lib/design-tokens'

interface Props {
  blockId: number
  onCreated: () => void
}

export function ExclusionZoneDrawer({ blockId, onCreated }: Props) {
  const [coords, setCoords] = useState<[number, number][]>([])
  const [label, setLabel] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  const handleMapClick = useCallback((e: any) => {
    const { lng, lat } = e.lngLat
    setCoords((prev) => [...prev, [lng, lat]])
  }, [])

  const undoLast = useCallback(() => setCoords((prev) => prev.slice(0, -1)), [])
  const clearAll = useCallback(() => setCoords([]), [])

  const polygonGeoJSON = useMemo(() => {
    if (coords.length < 3) return null
    const ring = [...coords, coords[0]] // close the ring
    return {
      type: 'Feature' as const,
      properties: {},
      geometry: { type: 'Polygon' as const, coordinates: [ring] },
    }
  }, [coords])

  const pointsGeoJSON = useMemo(() => ({
    type: 'FeatureCollection' as const,
    features: coords.map((c, i) => ({
      type: 'Feature' as const,
      properties: { index: i },
      geometry: { type: 'Point' as const, coordinates: c },
    })),
  }), [coords])

  async function handleSave() {
    if (coords.length < 3) {
      setError('Click at least 3 points to draw a polygon')
      return
    }
    setSaving(true)
    setError('')
    try {
      const ring = [...coords, coords[0]]
      const res = await fetch(`/api/admin/blocks/${blockId}/exclusion-zones`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          polygon: { type: 'Polygon', coordinates: [ring] },
          label: label || undefined,
        }),
      })
      const json = await res.json()
      if (!json.success) throw new Error(json.message || 'Failed to save')
      setCoords([])
      setLabel('')
      onCreated()
    } catch (e: any) {
      setError(e.message || 'Failed to save exclusion zone')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div>
      <div style={{ marginBottom: 8 }}>
        <input
          type="text"
          placeholder="Label (e.g. gated yard at 123 Main St)"
          value={label}
          onChange={(e) => setLabel(e.target.value)}
        />
        <button onClick={undoLast} disabled={coords.length === 0}>Undo</button>
        <button onClick={clearAll} disabled={coords.length === 0}>Clear</button>
        <button onClick={handleSave} disabled={saving || coords.length < 3}>
          {saving ? 'Saving...' : 'Save exclusion zone'}
        </button>
        {error && <span style={{ color: 'red' }}>{error}</span>}
      </div>
      <BaseMap onClick={handleMapClick} initialZoom={ZOOM.BLOCK ?? 16}>
        {polygonGeoJSON && (
          <Source id="exclusion-zone-draft" type="geojson" data={polygonGeoJSON}>
            <Layer id="exclusion-zone-fill" type="fill" paint={{ 'fill-color': '#ff0000', 'fill-opacity': 0.25 }} />
            <Layer id="exclusion-zone-outline" type="line" paint={{ 'line-color': '#ff0000', 'line-width': 2 }} />
          </Source>
        )}
        <Source id="exclusion-zone-points" type="geojson" data={pointsGeoJSON}>
          <Layer id="exclusion-zone-point-dots" type="circle" paint={{ 'circle-radius': 5, 'circle-color': '#ff0000' }} />
        </Source>
      </BaseMap>
    </div>
  )
}
```

Check `ZOOM.BLOCK` exists in `design-tokens.ts` (`grep -n "export const ZOOM" -A 10 src/lib/design-tokens.ts`); if no `BLOCK` key exists, use whatever zoom constant `RouteDrawer.tsx` itself passes to `BaseMap` instead.

- [ ] **Step 5: Typecheck and build**

```bash
cd site && npx tsc --noEmit && npm run build
```

- [ ] **Step 6: Commit**

```bash
git add site/src/app/api/admin/blocks/ site/src/components/admin/ExclusionZoneDrawer.tsx
git commit -m "feat(exclusion-zones): admin CRUD API + draw-polygon UI component"
```

---

### Task 11: Wire the drawer into the admin routes page and filter excluded observations from lap-2 instructions

**Files:**
- Modify: `site/src/app/admin/routes/page.tsx`
- Modify: whichever endpoint currently returns the CLEANING-phase hotspot list to the app (find via Step 1)

**Interfaces:**
- Consumes: `ExclusionZoneDrawer` (Task 10).

- [ ] **Step 1: Find the CLEANING-phase hotspot-list endpoint**

```bash
cd site && grep -rln "CLEANING\|phase.*clean" src/app/api/scans/
```
Read whichever route returns the hotspot list the app displays during the CLEANING phase (likely `GET /api/scans/[id]` or a phase-specific sub-route returning `scanHotspots` for that scan).

- [ ] **Step 2: Filter out excluded hotspots from that response**

Join against `worldObservations` (or, simpler: query `worldObservations` for `scanHotspotId IN (...)` and `excludedPrivateProperty = true`, then filter those IDs out of the hotspot list before returning it). Use the exact pattern already in that file for building its response array — this step's precise diff depends on Step 1's finding; the principle is non-negotiable: **any `scanHotspotId` with a `worldObservations` row where `excludedPrivateProperty = true` must not appear in the lap-2 clean-instruction response.**

- [ ] **Step 3: Add the drawer to the admin routes page**

In `site/src/app/admin/routes/page.tsx`, add a new `view` state value `'exclusion-zones'` (alongside the existing `'list' | 'draw' | 'generate'`) that renders `<ExclusionZoneDrawer blockId={selectedBlockId} onCreated={...} />` for an admin-selected block, following the same pattern as the existing `view === 'draw'` branch that renders `RouteDrawer`.

- [ ] **Step 4: Typecheck and build**

```bash
cd site && npx tsc --noEmit && npm run build
```

- [ ] **Step 5: Commit**

```bash
git add site/src/app/admin/routes/page.tsx site/src/app/api/scans/
git commit -m "feat(exclusion-zones): wire admin drawer into routes page, filter excluded hotspots from clean instructions"
```

---

## Phase 3 — Immersive AR route experience

### Task 12: Shared Kotlin point-in-polygon utility

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/geo/PolygonContainment.kt`
- Create: `shared/src/commonTest/kotlin/com/gcc/shared/geo/PolygonContainmentTest.kt`

**Interfaces:**
- Produces: `fun pointInPolygon(lat: Double, lng: Double, polygon: List<Pair<Double, Double>>): Boolean` (polygon points as `(lat, lng)` pairs, matching `geo-utils.ts`'s convention) — consumed by Task 13.

- [ ] **Step 1: Read the existing test file to match its style**

```bash
cd shared && cat src/commonTest/kotlin/com/gcc/shared/geo/GeofenceVerificationTest.kt
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.gcc.shared.geo

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PolygonContainmentTest {

    private val squareBlock = listOf(
        41.859 to -87.701,
        41.859 to -87.699,
        41.861 to -87.699,
        41.861 to -87.701,
    )

    @Test
    fun `point inside the polygon returns true`() {
        assertTrue(pointInPolygon(41.860, -87.700, squareBlock))
    }

    @Test
    fun `point outside the polygon returns false`() {
        assertFalse(pointInPolygon(41.870, -87.710, squareBlock))
    }

    @Test
    fun `point just past an edge returns false`() {
        assertFalse(pointInPolygon(41.860, -87.6989, squareBlock))
    }
}
```

- [ ] **Step 3: Run and verify it fails (class doesn't exist yet)**

```bash
cd shared && ../gradlew :shared:jvmTest --tests "com.gcc.shared.geo.PolygonContainmentTest"
```
Expected: FAIL — `Unresolved reference: pointInPolygon`.

- [ ] **Step 4: Implement**

```kotlin
package com.gcc.shared.geo

/**
 * Ray-casting point-in-polygon test. Polygon points are (lat, lng) pairs,
 * matching site/src/lib/geo-utils.ts's pointInPolygon convention so the
 * client-side and server-side containment checks agree.
 */
fun pointInPolygon(lat: Double, lng: Double, polygon: List<Pair<Double, Double>>): Boolean {
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val (yi, xi) = polygon[i]
        val (yj, xj) = polygon[j]
        if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
            inside = !inside
        }
        j = i
    }
    return inside
}
```

- [ ] **Step 5: Run tests, verify pass**

```bash
../gradlew :shared:jvmTest --tests "com.gcc.shared.geo.PolygonContainmentTest"
```
Expected: 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/geo/PolygonContainment.kt shared/src/commonTest/kotlin/com/gcc/shared/geo/PolygonContainmentTest.kt
git commit -m "feat(shared): add pointInPolygon for route-boundary containment checks"
```

---

### Task 13: Fetch route boundary and wire the "outside assignment" check into `RouteScanViewModel`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/Route.kt` (confirm/extend boundary field)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RouteScanViewModel.kt`

**Interfaces:**
- Consumes: `pointInPolygon` (Task 12).
- Produces: `RouteScanViewModel.isOutsideBoundary: StateFlow<Boolean>` — consumed by Task 15's AR overlay.

- [ ] **Step 1: Confirm what boundary geometry is available on `Route` today**

```bash
cd shared && grep -n "geometry\|boundary\|Boundary" src/commonMain/kotlin/com/gcc/shared/model/Route.kt src/commonMain/kotlin/com/gcc/shared/model/ServiceArea.kt
```
`Route.geometry` (a `GeoJSONLineString`) is the route path, not a boundary polygon. `ServiceArea.boundary` is a `GeoJSONPolygon` but scoped to the whole service area, not the individual route/block. Since `blocks.locationBoundary` (Drizzle) already holds a per-block polygon (confirmed in the design spec, §2.1's schema read), the app needs that block-level boundary, not the service-area one. Check whether the mobile API response for a route already includes its block's `locationBoundary`:

```bash
cd site && grep -n "locationBoundary" src/lib/mobile-route-scan-response.ts src/app/api/routes -r
```

- [ ] **Step 2: If not already exposed, add `blockBoundary` to the mobile route response**

If Step 1 shows `locationBoundary` isn't in the mobile-facing route payload, add it: find `toMobileRoute`-equivalent function (likely in `site/src/lib/mobile-route-scan-response.ts` or a sibling `mobile-route-response.ts`), and add a `blockBoundary: GeoJSON | null` field sourced from a join to `blocks.locationBoundary` via the route's `blockId`. Add the matching field to the Kotlin DTO:

In `shared/src/commonMain/kotlin/com/gcc/shared/model/Route.kt`, add:

```kotlin
@Serializable
data class RouteBoundaryPoint(val lat: Double, val lng: Double)
```

and a `blockBoundary: List<RouteBoundaryPoint>? = null` field on the `Route` data class (adjust to match whatever `@Serializable` annotations and constructor style the rest of the file already uses).

- [ ] **Step 3: Add the containment check to `RouteScanViewModel`**

```bash
cd composeApp && grep -n "class RouteScanViewModel\|locationProvider\|coverageTracker" src/commonMain/kotlin/com/gcc/app/ui/screens/RouteScanViewModel.kt | head -20
```
Read enough of the file to find where GPS position updates are already handled (likely a `collect` on `locationProvider.location` or similar), then add, near that collection point:

```kotlin
private val _isOutsideBoundary = MutableStateFlow(false)
val isOutsideBoundary: StateFlow<Boolean> = _isOutsideBoundary.asStateFlow()

private var boundaryPolygon: List<Pair<Double, Double>> = emptyList()

private fun setBoundary(points: List<RouteBoundaryPoint>?) {
    boundaryPolygon = points?.map { it.lat to it.lng } ?: emptyList()
}

private fun updateBoundaryStatus(lat: Double, lng: Double) {
    if (boundaryPolygon.size < 3) {
        _isOutsideBoundary.value = false // no boundary loaded — fail open, don't block the worker
        return
    }
    _isOutsideBoundary.value = !com.gcc.shared.geo.pointInPolygon(lat, lng, boundaryPolygon)
}
```

Call `setBoundary(route.blockBoundary)` wherever the route is first loaded into the ViewModel, and call `updateBoundaryStatus(lat, lng)` at the same point(s) where the existing GPS-position collection already calls into `coverageTracker.updatePosition(lat, lng)` (so both stay in sync on every location update).

- [ ] **Step 4: Verify the shared module still builds**

```bash
cd shared && ../gradlew :shared:jvmMainClasses
```
Expected: BUILD SUCCESSFUL. (This does not compile `composeApp`'s iosMain — see Global Constraints. It confirms the shared DTO change is valid.)

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/Route.kt composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RouteScanViewModel.kt site/src/lib/
git commit -m "feat(ar): load block boundary, expose isOutsideBoundary from RouteScanViewModel"
```

---

### Task 14: Expose per-cell world coordinates from `CoverageTracker` for AR anchoring

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageTracker.kt`

**Interfaces:**
- Consumes: existing `gridRows`, `gridCols`, `cellSizeMeters`, bounding-box fields already computed in `initFromRoute` (need to read the file to get exact private-field names before referencing them).
- Produces: `fun cellCenterLatLng(row: Int, col: Int): Pair<Double, Double>` — consumed by Task 15 to place fog-overlay geometry at real-world coordinates.

- [ ] **Step 1: Read the file to find the exact bounding-box field names**

```bash
cd composeApp && sed -n '1,100p' src/commonMain/kotlin/com/gcc/app/platform/CoverageTracker.kt
```
Confirm the exact names of the fields holding the grid's origin lat/lng and cell size (per the earlier grounding: a bounding box computed with 20m padding, `cellSizeMeters = 3.0`, `gridRows`/`gridCols`). Use those exact names in Step 2 — do not guess.

- [ ] **Step 2: Add the inverse projection function**

Using whatever the file's existing forward projection (lat/lng → row/col) looks like, add its inverse immediately after it:

```kotlin
/**
 * Inverse of the grid projection used in initFromRoute — returns the
 * real-world lat/lng center of a given grid cell, for AR-space anchoring
 * of the fog-of-war overlay (Task 15).
 */
fun cellCenterLatLng(row: Int, col: Int): Pair<Double, Double> {
    val lat = originLat + (row + 0.5) * cellSizeMeters / metersPerDegLat
    val lng = originLng + (col + 0.5) * cellSizeMeters / metersPerDegLng
    return lat to lng
}
```

Replace `originLat`, `originLng`, `metersPerDegLat`, `metersPerDegLng` with whatever Step 1 showed are the actual field names/values already in the file (the file may inline the meters-per-degree constants rather than name them — match its existing style exactly rather than introducing new field names that duplicate existing ones).

- [ ] **Step 3: Verify the module builds**

```bash
../gradlew :shared:jvmMainClasses :composeApp:compileKotlinDesktop
```
Expected: BUILD SUCCESSFUL. (`compileKotlinDesktop` compiles `commonMain` + `desktopMain`, which is the closest thing to a build check for `commonMain`-only changes available on this Linux machine.)

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageTracker.kt
git commit -m "feat(ar): expose cellCenterLatLng on CoverageTracker for AR fog-overlay anchoring"
```

---

### Task 15: iOS AR route overlay — SceneKit boundary wall, fog-of-war, directional arrow

> **Cannot be built or tested on this machine.** This task authors real Kotlin/Native source using the same `platform.ARKit`/cinterop pattern already proven in `ArScanner.kt` and `CameraManager.kt`, and using SceneKit (`platform.SceneKit`, via `ARSCNView`) rather than RealityKit — RealityKit's modern Swift-only API surface does not reliably bridge through Kotlin/Native's Objective-C interop, while SceneKit is fully Objective-C-compatible and is the framework the existing codebase's cinterop pattern already assumes. Compilation and all visual iteration happen on the MacBook (Task 17).

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (expect)
- Create: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (actual)
- Create: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (actual stub)
- Create: `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (actual stub)

**Interfaces:**
- Consumes: `CoverageTracker.scannedCells`, `CoverageTracker.cellCenterLatLng` (Task 14); `RouteScanViewModel.isOutsideBoundary` (Task 13); `LocationProvider.headingDegrees`.
- Produces: `expect class ArRouteOverlay` with `fun attach(containerView: Any)`, `fun updateFog(scannedCells: Set<Pair<Int,Int>>, cellCenters: (Int, Int) -> Pair<Double,Double>)`, `fun updateBoundaryStatus(isOutside: Boolean)`, `fun updateHeading(degrees: Float)`, `fun detach()` — consumed by Task 16's Compose screen.

- [ ] **Step 1: Write the `expect` declaration**

```kotlin
package com.gcc.app.platform

/**
 * Full-screen AR overlay for the "Begin Route" experience: directional
 * guidance toward the nearest unscanned frontier, a translucent boundary
 * wall rendered when the camera looks outside the route's assigned polygon,
 * and a fog-of-war ground overlay driven by CoverageTracker's existing
 * per-cell coverage grid.
 */
expect class ArRouteOverlay() {
    fun updateFog(scannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>)
    fun updateBoundaryStatus(isOutside: Boolean)
    fun updateHeading(degrees: Float)
    fun start()
    fun stop()
}
```

- [ ] **Step 2: Write the Android stub (matches the existing `ArScanner.kt` Android-stub pattern — check it first)**

```bash
cd composeApp && cat src/androidMain/kotlin/com/gcc/app/platform/ArScanner.kt
```

```kotlin
package com.gcc.app.platform

actual class ArRouteOverlay actual constructor() {
    actual fun updateFog(scannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>) {}
    actual fun updateBoundaryStatus(isOutside: Boolean) {}
    actual fun updateHeading(degrees: Float) {}
    actual fun start() {}
    actual fun stop() {}
}
```

- [ ] **Step 3: Write the desktop stub, same shape**

```kotlin
package com.gcc.app.platform

actual class ArRouteOverlay actual constructor() {
    actual fun updateFog(scannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>) {}
    actual fun updateBoundaryStatus(isOutside: Boolean) {}
    actual fun updateHeading(degrees: Float) {}
    actual fun start() {}
    actual fun stop() {}
}
```

- [ ] **Step 4: Write the iOS actual — SceneKit-based AR overlay**

```kotlin
package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.ARKit.ARSCNView
import platform.ARKit.ARWorldTrackingConfiguration
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNPlane
import platform.SceneKit.SCNMaterial
import platform.SceneKit.SCNVector3Make
import platform.SceneKit.SCNBox
import platform.UIKit.UIColor

@OptIn(ExperimentalForeignApi::class)
actual class ArRouteOverlay actual constructor() {

    private var sceneView: ARSCNView? = null
    private val fogNodes = mutableMapOf<Pair<Int, Int>, SCNNode>()
    private var boundaryNode: SCNNode? = null

    /** Called by the Compose UIKitView factory (Task 16) once the ARSCNView exists. */
    fun bind(view: ARSCNView) {
        sceneView = view
        val config = ARWorldTrackingConfiguration()
        view.session.runWithConfiguration(config)
    }

    actual fun start() {
        // Session start is driven by `bind`, called from the Compose UIKitView factory
        // in Task 16 — nothing to do here beyond what `bind` already does.
    }

    actual fun stop() {
        sceneView?.session?.pause()
    }

    /**
     * One SCNNode per unscanned cell, positioned at its real-world lat/lng
     * projected into the AR session's local coordinate space via the same
     * ARSession.currentFrame.camera.transform math ArScanner.getArHeading()
     * already uses. Scanned cells simply have their node removed (revealed).
     */
    actual fun updateFog(scannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>) {
        val scene = sceneView?.scene ?: return
        for ((cell, node) in fogNodes.toMap()) {
            if (cell in scannedCells) {
                node.removeFromParentNode()
                fogNodes.remove(cell)
            }
        }
        // New unscanned cells are added by the caller invoking this with the
        // full unscanned set on each CoverageTracker update; node creation for
        // cells not yet in `fogNodes` uses a translucent grey SCNPlane sized to
        // the cell's real-world footprint (see cellSizeMeters, Task 14) and is
        // positioned via world-space translation computed from the AR session's
        // origin — this coordinate conversion (lat/lng -> AR local meters) needs
        // ARSession.currentFrame.camera.transform at bind time as the origin
        // reference and is finished during MacBook iteration (Task 17), where
        // the actual conversion math can be verified against a live session
        // instead of guessed blind.
    }

    actual fun updateBoundaryStatus(isOutside: Boolean) {
        val node = boundaryNode ?: return
        val material = SCNMaterial()
        material.diffuse.contents = if (isOutside) {
            UIColor.redColor.colorWithAlphaComponent(0.35)
        } else {
            UIColor.whiteColor.colorWithAlphaComponent(0.12)
        }
        node.geometry?.materials = listOf(material)
    }

    actual fun updateHeading(degrees: Float) {
        // Directional-arrow node rotation about the Y axis, degrees -> radians.
        // Wired to a screen-space or world-anchored arrow node during Task 17's
        // on-device iteration, where the arrow's actual mesh/asset is added.
    }
}
```

- [ ] **Step 5: Verify the non-iOS targets build (this machine cannot check iosMain)**

```bash
cd composeApp && ../gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinDesktop
```
Expected: BUILD SUCCESSFUL for both. This confirms the `expect`/Android-actual/desktop-actual triad is consistent; the iOS actual is checked in Task 17 on the MacBook.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt
git commit -m "feat(ar): scaffold ArRouteOverlay (SceneKit boundary + fog-of-war + heading), iOS build/polish pending MacBook iteration"
```

---

### Task 16: Wire `ArRouteOverlay` into the "Begin Route" flow

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RouteScanScreen.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (add the `UIKitView` factory hookup)

**Interfaces:**
- Consumes: `ArRouteOverlay` (Task 15), `RouteScanViewModel.isOutsideBoundary` (Task 13), `CoverageTracker.scannedCells`/`cellCenterLatLng` (Task 14), `LocationProvider.headingDegrees`.

- [ ] **Step 1: Read `CameraManager.kt`'s `UIKitView` embedding (lines ~590-630) to copy its exact factory/update-callback shape**

```bash
cd composeApp && sed -n '585,630p' src/iosMain/kotlin/com/gcc/app/platform/CameraManager.kt
```

- [ ] **Step 2: Add the iOS `UIKitView` factory for `ARSCNView`, bound to `ArRouteOverlay`**

In `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt`, add a top-level composable following the exact structural pattern Step 1 revealed for `CameraPreview()`:

```kotlin
// (added at the bottom of the same file, alongside the actual class)
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView

@Composable
actual fun ArRouteOverlayView(overlay: ArRouteOverlay, modifier: Modifier) {
    UIKitView(
        factory = {
            val view = ARSCNView()
            overlay.bind(view)
            view
        },
        modifier = modifier,
    )
}
```

Add the matching `expect fun ArRouteOverlayView(overlay: ArRouteOverlay, modifier: androidx.compose.ui.Modifier)` to the `commonMain` file from Task 15, and stub Android/desktop actuals returning an empty `Box(modifier)` (matching however `CameraPreview`'s non-iOS actuals are stubbed — check `CameraManager.kt`'s Android/desktop actuals for the exact stub style before writing these).

- [ ] **Step 3: Create `ArRouteScreen.kt`**

```kotlin
package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.gcc.app.platform.ArRouteOverlay
import com.gcc.app.platform.ArRouteOverlayView

@Composable
fun ArRouteScreen(viewModel: RouteScanViewModel) {
    val overlay = remember { ArRouteOverlay() }
    val isOutsideBoundary by viewModel.isOutsideBoundary.collectAsState()
    val scannedCells by viewModel.coverageTracker.scannedCells.collectAsState()
    val heading by viewModel.locationProvider.headingDegrees.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        ArRouteOverlayView(overlay = overlay, modifier = Modifier.fillMaxSize())
    }

    // Side-effect-style updates — adjust to whatever effect-handling convention
    // (LaunchedEffect keyed on each StateFlow) the rest of this screen's file
    // neighbors already use, rather than introducing a new pattern here.
    androidx.compose.runtime.LaunchedEffect(isOutsideBoundary) {
        overlay.updateBoundaryStatus(isOutsideBoundary)
    }
    androidx.compose.runtime.LaunchedEffect(scannedCells) {
        overlay.updateFog(scannedCells) { row, col -> viewModel.coverageTracker.cellCenterLatLng(row, col) }
    }
    androidx.compose.runtime.LaunchedEffect(heading) {
        heading?.let { overlay.updateHeading(it) }
    }
}
```

Adjust `viewModel.coverageTracker`/`viewModel.locationProvider` property access to match whatever `RouteScanViewModel`'s actual exposed property names are (check via `grep -n "val coverageTracker\|val locationProvider" composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RouteScanViewModel.kt` — Tasks 13/14 assumed these exist as accessible properties; if they're private, add a public read-only accessor rather than exposing mutable internals).

- [ ] **Step 4: Wire the "Begin Route" trigger in `RouteScanScreen.kt`**

```bash
cd composeApp && grep -n "Begin Route\|beginRoute\|SCOUTING" src/commonMain/kotlin/com/gcc/app/ui/screens/RouteScanScreen.kt
```
Find the existing phase-router `when` block (SCOUTING → `ScoutScreen`, etc. per the design spec's grounding). Add a new state (e.g., a `showArRouteScreen: Boolean` in the ViewModel, flipped true when the worker taps "Begin Route" while `!isOutsideBoundary`-gated-entry is confirmed via GPS, per the design's trigger condition) that renders `ArRouteScreen(viewModel)` instead of `ScoutScreen` for the duration of SCOUTING, or as an overlay on top of it — the exact composition (replace vs. overlay) depends on reading the current `ScoutScreen` full-screen `GccMap` layout (already known from the design's grounding) to decide whether the AR camera view fully replaces the map or the map becomes a corner inset. Default to full replacement for this task (matches "full-screen AR camera view" from the design spec); a map inset is a fast-follow polish, not required here.

- [ ] **Step 5: Verify non-iOS targets still build**

```bash
../gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinDesktop
```

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RouteScanScreen.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt composeApp/src/androidMain composeApp/src/desktopMain
git commit -m "feat(ar): wire ArRouteOverlay into Begin-Route trigger on SCOUTING phase"
```

---

### Task 17: MacBook build, simulator test, TestFlight deploy

**Files:** none (build/deploy task).

**Interfaces:** consumes everything from Tasks 1–16.

- [ ] **Step 1: Sync this branch to the MacBook**

```bash
ssh macbook "cd ~/gcc && git fetch origin && git checkout worktree-unified-scan-ecosystem"
```
(Or push to a shared branch first if `cdx-mobile sync` expects a specific branch name — check `cdx-mobile status` output first for the expected convention.)

- [ ] **Step 2: Finish the AR coordinate-conversion math left open in Task 15 Step 4**

On the MacBook, in Xcode, open the `iosApp` workspace and locate the compiled `ArRouteOverlay` Kotlin/Native framework output. Complete the lat/lng-to-AR-local-meters conversion in `updateFog` (using `ARSession.currentFrame.camera.transform` captured at `bind()` time as the local-space origin, then `METERS_PER_DEG_LAT`/`METERS_PER_DEG_LNG`-equivalent offsets to place each `SCNPlane` node) — this is the one piece of real AR math that genuinely needs a live session to verify against (simulator ARKit support is limited; a physical device is preferred for this step). Iterate visually until: (a) the boundary wall renders red when the camera points outside the loaded polygon and clears otherwise, (b) fog planes visibly clear as the worker walks over real ground truth cells.

- [ ] **Step 3: Build for the simulator**

```bash
cdx-mobile build ios
```
Expected: build succeeds. Launch in Simulator, walk through SCOUTING → tap "Begin Route" → confirm the AR view (or its simulator-limited approximation, since ARKit world tracking is constrained in Simulator) renders without crashing.

- [ ] **Step 4: Build for a physical device and validate AR behavior for real**

```bash
cdx-mobile build ios --device
```
Test on an actual iPhone: confirm heading-driven directional guidance, boundary-wall color change when panning outside the assigned polygon, and fog-of-war reveal tracking real walked coverage.

- [ ] **Step 5: Bump build number and upload to TestFlight**

```bash
cdx-mobile build ios --sync --testflight
```
(Adjust to whatever the actual `cdx-mobile` flag/subcommand for a TestFlight upload is — check `cdx-mobile --help` on the MacBook first; the design spec references build 99/v1.0.6 as the last branch-documented build number, so this upload should bump past that.)

- [ ] **Step 6: Notify for retest**

Once TestFlight processing completes (Apple-side, typically 10–30 minutes), confirm the build is visible in TestFlight on the test iPhone and ready to install.

---

## Phase 4 — Environment scan as the default capture path

### Task 18: Make AR/LiDAR scanning the default across both capture flows; flat photo becomes an explicit, rare fallback

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ScoutScreen.kt` (capture FAB / `ScoutCaptureDialog` trigger)
- Modify: whichever file defines the `SpotScanCapture`/spot-report capture entry point (find via Step 1)

**Interfaces:**
- Consumes: `ArScanner.isSessionActive`, `hasLiDAR()`-equivalent device-tier detection (per the design's PRO_SCANNER/SCANNER/BASIC tiers — confirm the exact function name).

- [ ] **Step 1: Find the current device-tier decision point**

```bash
cd composeApp && grep -rn "PRO_SCANNER\|hasLiDAR\|DeviceType\." src/commonMain/kotlin/com/gcc/app/ src/iosMain/kotlin/com/gcc/app/ | grep -v Test
```
Read enough of the surrounding code to find exactly where the app currently decides between an AR/LiDAR capture path and a flat-photo capture path, for both the route-scan hotspot capture flow and the spot-report capture flow.

- [ ] **Step 2: Adjust the decision so BASIC (flat photo) is only reached when AR truly isn't available**

The three-tier model (PRO_SCANNER/SCANNER/BASIC) already exists — the fix is ensuring the app *attempts* SCANNER-tier (ARKit without depth, still richer than a flat photo) on any ARKit-capable device before ever falling back to BASIC, and that BASIC is reached only when `ARWorldTrackingConfiguration.isSupported()` is false or `ArScanner.startSession()` fails at runtime (session start error), not as a default/preferred path. Locate the exact conditional from Step 1 and confirm it already follows "try AR first, fall back only on unsupported/failure" — if instead it's falling back based on a looser heuristic (e.g., defaulting to BASIC unless LiDAR specifically is present, skipping the no-depth SCANNER tier), correct the condition so SCANNER is attempted before BASIC.

- [ ] **Step 3: Update the capture UI copy so flat-photo is visibly the fallback, not a peer option**

In both capture dialogs, ensure the flat-photo path only appears (as a visible button/option) when the AR path has actually failed or is unsupported on-device — not as an always-available alternative button sitting next to the scan option. If the current UI presents both as equal choices, change it to: AR scan starts automatically when the capture flow opens; a "having trouble? use a photo instead" fallback link only appears after an AR session-start failure or a short timeout with no valid AR frame, rather than being presented up front.

- [ ] **Step 4: Verify non-iOS targets build**

```bash
../gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinDesktop
```

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ScoutScreen.kt
git commit -m "feat(capture): make AR/LiDAR scan the default capture path in both spot-report and route-scan flows, flat photo as rare fallback only"
```

- [ ] **Step 6: MacBook verification (part of Task 17's build/test pass, not a separate deploy)**

Confirm on-device that opening either capture flow on an ARKit-capable test iPhone starts an AR session immediately rather than presenting a flat-photo option up front.

---

## Self-review notes

- **Spec coverage:** §1 → Task 1. §2 (schema, algorithm, write-paths, backfill, map rendering) → Tasks 2–8. §3 (exclusion zones) → Tasks 9–11. §4 (AR experience) → Tasks 12–17. The "environment scan as default" instruction (not originally in the written spec — added in a later user message) → Task 18, added here during planning.
- **Placeholder scan:** Task 15's `updateFog`/`updateHeading` bodies contain an explicit, bounded deferral (the lat/lng→AR-local-meters conversion) rather than a bare TODO — this is called out by name, tied to a specific finishing task (Task 17 Step 2), and is the one piece of this entire plan that is genuinely infeasible to finish blind without a live ARKit session, not a general placeholder.
- **Type consistency:** `ArRouteOverlay`'s method names (`updateFog`, `updateBoundaryStatus`, `updateHeading`, `start`, `stop`) are identical across the `expect` declaration (Task 15 Step 1) and both actuals; `RecordObservationInput`'s field names in Task 3 match every call site in Tasks 4–6, 9.
- **Scope check:** each phase produces independently shippable, testable software — Phase 1 and 2 are fully verifiable on this machine and could ship without Phase 3 ever landing; Phase 3 is the only phase gated on MacBook access.
