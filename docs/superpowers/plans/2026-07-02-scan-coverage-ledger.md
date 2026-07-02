# Scan Coverage Ledger (Phase 0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the missing "was this location physically scanned, and when" data model — a companion to the existing `worldObservations` litter-state log — so downstream features (AR guidance, map tile freshness, distance-tracked points) have a real coverage source instead of inferring it from litter data that doesn't carry that information.

**Architecture:** Two new Drizzle-managed Postgres tables (`scanCoverageEvents` — append-only event log; `cellCoverageSummary` — upserted rollup for cheap reads) plus a `domain` column on the existing `worldObservations` table, a write-path helper, and two Next.js API routes. Pure backend — no mobile, no Ktor server involvement (this whole subsystem already bypasses Ktor entirely; confirmed no references to `worldObservations` anywhere under `server/` or `shared/`).

**Tech Stack:** Next.js App Router (`site/src/app/api/*`), Drizzle ORM against the existing Neon Postgres database, TypeScript.

## Global Constraints

- Drizzle is authoritative for schema (`site/src/db/schema.ts`); this project's canonical deploy mechanism is `npm run db:push` (not `drizzle-kit generate`/`migrate` — the migration chain has known, documented gaps; see project `CLAUDE.md`).
- This GCC project has **no separate dev database** — `db:push` reconciles directly against the same Neon database both backends use. All schema changes in this plan are strictly additive (new tables, one new nullable-with-default column) — nothing here drops or alters existing columns.
- No unit-test framework exists in `site/` (`grep` for `.test.ts`/`.spec.ts` and `vitest`/`jest` in `package.json` found nothing — only Playwright e2e tests under `site/e2e/`, and none apply to this backend-only work). Verification in this plan is TypeScript typecheck (`npx tsc --noEmit`) plus a manual `curl` smoke check against the local dev server — the same verification pattern the rest of this codebase's API routes use.
- Reuse the existing `gridCell()` function (`site/src/lib/world-observations.ts`) for all grid-cell math in this plan — a second, differently-tuned grid implementation would silently break cross-referencing between `worldObservations` and the new coverage ledger.
- Follow existing schema.ts conventions exactly: `pgTable('snake_case_name', { camelCase: type('snake_case_col') }, (table) => ({ idxName: index('idx_name').on(table.col) }))`.

---

### Task 1: Schema — `scanCoverageEvents`, `cellCoverageSummary`, `worldObservations.domain`

**Files:**
- Modify: `site/src/db/schema.ts` (append after the `worldObservations` block, currently ending at line 1607 with `export type NewWorldObservationRow = typeof worldObservations.$inferInsert`)

**Interfaces:**
- Produces: `scanCoverageEvents` table (Drizzle table object), `cellCoverageSummary` table, `worldObservations.domain` column. Task 2 imports both new tables.

- [ ] **Step 1: Add the `domain` column to `worldObservations`**

In `site/src/db/schema.ts`, inside the existing `worldObservations` table definition (starts at line 1583), add one field. Insert it directly after `sourceType`:

```typescript
export const worldObservations = pgTable('world_observations', {
  id: serial('id').primaryKey(),
  scanHotspotId: integer('scan_hotspot_id'),
  communityReportId: integer('community_report_id'),
  sourceType: varchar('source_type', { length: 20 }).notNull(), // 'route_scan' | 'spot_report'
  domain: varchar('domain', { length: 20 }).default('litter').notNull(), // 'litter' | 'vegetation' | 'construction' | 'land_use'
  lat: doublePrecision('lat').notNull(),
  // ...rest of the existing fields unchanged
```

Do not change any other field in this table.

- [ ] **Step 2: Add `scanCoverageEvents` and `cellCoverageSummary` tables**

Append this block immediately after the existing `export type NewWorldObservationRow = typeof worldObservations.$inferInsert` line (line 1607):

```typescript
export const scanCoverageEvents = pgTable('scan_coverage_events', {
  id: serial('id').primaryKey(),
  deviceId: varchar('device_id', { length: 64 }).notNull(),
  platform: varchar('platform', { length: 16 }).notNull(), // 'ios' | 'android'
  workerId: integer('worker_id'),
  locationCellId: varchar('location_cell_id', { length: 32 }).notNull(),
  lat: doublePrecision('lat').notNull(),
  lng: doublePrecision('lng').notNull(),
  distanceMeters: doublePrecision('distance_meters').notNull(),
  scanMode: varchar('scan_mode', { length: 10 }).notNull(), // 'route' | 'spot'
  deviceTier: varchar('device_tier', { length: 16 }).notNull(), // 'basic' | 'scanner' | 'pro_scanner'
  capturedAt: timestamp('captured_at', { mode: 'string', withTimezone: true }).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
}, (table) => ({
  cellCapturedIdx: index('scan_coverage_events_cell_captured_idx').on(table.locationCellId, table.capturedAt),
}))

export type ScanCoverageEventRow = typeof scanCoverageEvents.$inferSelect
export type NewScanCoverageEventRow = typeof scanCoverageEvents.$inferInsert

export const cellCoverageSummary = pgTable('cell_coverage_summary', {
  locationCellId: varchar('location_cell_id', { length: 32 }).primaryKey(),
  firstScannedAt: timestamp('first_scanned_at', { mode: 'string', withTimezone: true }).notNull(),
  lastScannedAt: timestamp('last_scanned_at', { mode: 'string', withTimezone: true }).notNull(),
  totalDistanceMeters: doublePrecision('total_distance_meters').default(0).notNull(),
  scanCount: integer('scan_count').default(0).notNull(),
})

export type CellCoverageSummaryRow = typeof cellCoverageSummary.$inferSelect
export type NewCellCoverageSummaryRow = typeof cellCoverageSummary.$inferInsert
```

- [ ] **Step 3: Typecheck**

Run: `cd site && npx tsc --noEmit`
Expected: no new errors. (Pre-existing unrelated errors, if any, are not this task's concern — confirm the count doesn't increase.)

- [ ] **Step 4: Push schema to the database**

Run: `cd site && npm run db:push`
This is a real write against the live Neon database this project shares across environments (no separate dev DB exists here — see Global Constraints). The change is additive only (two new tables, one new column with a default), so it will not affect existing rows or queries. Confirm the CLI's interactive diff output shows only `CREATE TABLE "scan_coverage_events"`, `CREATE TABLE "cell_coverage_summary"`, and `ALTER TABLE "world_observations" ADD COLUMN "domain"` before accepting it — if it shows anything else (a drop, a rename, an unrelated table), stop and investigate before proceeding.

- [ ] **Step 5: Verify columns landed**

Run: `cd site && npx tsx -e "import { db } from './src/db'; import { sql } from 'drizzle-orm'; db.execute(sql\`SELECT column_name FROM information_schema.columns WHERE table_name = 'scan_coverage_events' ORDER BY ordinal_position\`).then(r => console.log(r.rows)).then(() => process.exit(0))"`
Expected: prints all 10 `scan_coverage_events` column names (`id`, `device_id`, `platform`, `worker_id`, `location_cell_id`, `lat`, `lng`, `distance_meters`, `scan_mode`, `device_tier`, `captured_at`, `created_at`).

- [ ] **Step 6: Commit**

```bash
git add site/src/db/schema.ts
git commit -m "feat(db): add scan_coverage_events + cell_coverage_summary tables, world_observations.domain column"
```

---

### Task 2: Write-path helper — `recordCoverageEvent`

**Files:**
- Create: `site/src/lib/scan-coverage.ts`

**Interfaces:**
- Consumes: `gridCell(lat, lng, cellSizeMeters?)` from `site/src/lib/world-observations.ts` (exported, signature confirmed: `export function gridCell(lat: number, lng: number, cellSizeMeters = 10): string`); `scanCoverageEvents`, `cellCoverageSummary` tables from Task 1.
- Produces: `recordCoverageEvent(input: RecordCoverageEventInput): Promise<ScanCoverageEventRow>`, exported. Task 3 calls this directly.

- [ ] **Step 1: Write `scan-coverage.ts`**

```typescript
import { db } from '@/db'
import { scanCoverageEvents, cellCoverageSummary } from '@/db/schema'
import { gridCell } from '@/lib/world-observations'
import { sql } from 'drizzle-orm'

export interface RecordCoverageEventInput {
  deviceId: string
  platform: 'ios' | 'android'
  workerId?: number
  lat: number
  lng: number
  distanceMeters: number
  scanMode: 'route' | 'spot'
  deviceTier: 'basic' | 'scanner' | 'pro_scanner'
  capturedAt: string
}

/**
 * Records one coverage event (append-only) and upserts the per-cell rollup
 * summary used for cheap reads (map tile freshness, points calculation,
 * AR guidance). The event log is the source of truth; the summary is a
 * derived cache maintained on every write, never the other way around.
 */
export async function recordCoverageEvent(input: RecordCoverageEventInput) {
  const cellId = gridCell(input.lat, input.lng)

  const [row] = await db
    .insert(scanCoverageEvents)
    .values({
      deviceId: input.deviceId,
      platform: input.platform,
      workerId: input.workerId ?? null,
      locationCellId: cellId,
      lat: input.lat,
      lng: input.lng,
      distanceMeters: input.distanceMeters,
      scanMode: input.scanMode,
      deviceTier: input.deviceTier,
      capturedAt: input.capturedAt,
    })
    .returning()

  await db
    .insert(cellCoverageSummary)
    .values({
      locationCellId: cellId,
      firstScannedAt: input.capturedAt,
      lastScannedAt: input.capturedAt,
      totalDistanceMeters: input.distanceMeters,
      scanCount: 1,
    })
    .onConflictDoUpdate({
      target: cellCoverageSummary.locationCellId,
      set: {
        lastScannedAt: sql`GREATEST(${cellCoverageSummary.lastScannedAt}, EXCLUDED.last_scanned_at)`,
        firstScannedAt: sql`LEAST(${cellCoverageSummary.firstScannedAt}, EXCLUDED.first_scanned_at)`,
        totalDistanceMeters: sql`${cellCoverageSummary.totalDistanceMeters} + EXCLUDED.total_distance_meters`,
        scanCount: sql`${cellCoverageSummary.scanCount} + 1`,
      },
    })

  return row
}
```

- [ ] **Step 2: Typecheck**

Run: `cd site && npx tsc --noEmit`
Expected: no new errors.

- [ ] **Step 3: Manual verification script**

Run: `cd site && npx tsx -e "
import { recordCoverageEvent } from './src/lib/scan-coverage'
async function main() {
  const r1 = await recordCoverageEvent({ deviceId: 'test-device-1', platform: 'ios', lat: 41.8617, lng: -87.6851, distanceMeters: 12.5, scanMode: 'route', deviceTier: 'pro_scanner', capturedAt: new Date().toISOString() })
  console.log('event 1:', r1)
  const r2 = await recordCoverageEvent({ deviceId: 'test-device-1', platform: 'ios', lat: 41.8617, lng: -87.6851, distanceMeters: 8.0, scanMode: 'route', deviceTier: 'pro_scanner', capturedAt: new Date().toISOString() })
  console.log('event 2:', r2)
  process.exit(0)
}
main()
"`
Expected: two distinct event rows print (different `id`, same `locationCellId`). Then confirm the rollup accumulated correctly:
Run: `cd site && npx tsx -e "import { db } from './src/db'; import { cellCoverageSummary } from './src/db/schema'; db.select().from(cellCoverageSummary).then(r => console.log(r)).then(() => process.exit(0))"`
Expected: one row with `totalDistanceMeters: 20.5` and `scanCount: 2` — confirms the upsert accumulates rather than overwrites.

- [ ] **Step 4: Commit**

```bash
git add site/src/lib/scan-coverage.ts
git commit -m "feat(scan-coverage): add recordCoverageEvent write-path helper"
```

---

### Task 3: `POST /api/scans/coverage` endpoint

**Files:**
- Create: `site/src/app/api/scans/coverage/route.ts`

**Interfaces:**
- Consumes: `recordCoverageEvent` from Task 2.
- Produces: `POST /api/scans/coverage` accepting `{ events: RecordCoverageEventInput[] }`, returning `{ inserted: number, success: boolean }`.

- [ ] **Step 1: Write the route**

```typescript
import { NextRequest, NextResponse } from 'next/server'
import { recordCoverageEvent, RecordCoverageEventInput } from '@/lib/scan-coverage'

export const dynamic = 'force-dynamic'

export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const events: RecordCoverageEventInput[] = Array.isArray(body.events) ? body.events : []

    if (events.length === 0) {
      return NextResponse.json({ inserted: 0, success: false, error: 'events array required' }, { status: 400 })
    }

    let inserted = 0
    for (const event of events) {
      if (
        typeof event.deviceId !== 'string' ||
        (event.platform !== 'ios' && event.platform !== 'android') ||
        typeof event.lat !== 'number' ||
        typeof event.lng !== 'number' ||
        typeof event.distanceMeters !== 'number' ||
        (event.scanMode !== 'route' && event.scanMode !== 'spot') ||
        !['basic', 'scanner', 'pro_scanner'].includes(event.deviceTier) ||
        typeof event.capturedAt !== 'string'
      ) {
        continue // skip malformed events rather than fail the whole batch
      }
      await recordCoverageEvent(event)
      inserted++
    }

    return NextResponse.json({ inserted, success: true })
  } catch (error) {
    console.error('POST /api/scans/coverage error:', error)
    return NextResponse.json({ inserted: 0, success: false }, { status: 500 })
  }
}
```

- [ ] **Step 2: Typecheck**

Run: `cd site && npx tsc --noEmit`
Expected: no new errors.

- [ ] **Step 3: Manual verification against the dev server**

Run: `cd site && npm run dev` (leave running in background), then in another shell:
```bash
curl -s -X POST http://localhost:7350/api/scans/coverage \
  -H "Content-Type: application/json" \
  -d '{"events":[{"deviceId":"curl-test","platform":"ios","lat":41.87,"lng":-87.68,"distanceMeters":5,"scanMode":"route","deviceTier":"pro_scanner","capturedAt":"2026-07-02T12:00:00Z"}]}'
```
Expected: `{"inserted":1,"success":true}`.

Then verify malformed events are skipped, not fatal:
```bash
curl -s -X POST http://localhost:7350/api/scans/coverage \
  -H "Content-Type: application/json" \
  -d '{"events":[{"deviceId":"curl-test"},{"deviceId":"curl-test-2","platform":"ios","lat":41.87,"lng":-87.68,"distanceMeters":5,"scanMode":"route","deviceTier":"pro_scanner","capturedAt":"2026-07-02T12:01:00Z"}]}'
```
Expected: `{"inserted":1,"success":true}` (only the second, valid event counted).

- [ ] **Step 4: Commit**

```bash
git add site/src/app/api/scans/coverage/route.ts
git commit -m "feat(api): add POST /api/scans/coverage batched ingestion endpoint"
```

---

### Task 4: `GET /api/map/coverage` endpoint

**Files:**
- Modify: `site/src/lib/world-observations.ts` (add `cellCenter`, the inverse of the existing `gridCell`)
- Create: `site/src/app/api/map/coverage/route.ts`

**Interfaces:**
- Consumes: `cellCoverageSummary` table from Task 1; new `cellCenter(cellId, cellSizeMeters?)` from this task's Step 1.
- Produces: `GET /api/map/coverage?bbox=minLng,minLat,maxLng,maxLat` returning `{ cells: Array<{ locationCellId: string, lat: number, lng: number, state: 'fresh' | 'stale', lastScannedAt: string, totalDistanceMeters: number }>, success: boolean }`. Only cells that have **ever** been scanned are included — the frontend (a later phase, not this plan) infers "never scanned" from a cell's absence in this response, since enumerating every possible never-scanned cell in a bbox is the frontend's job, not this endpoint's.

- [ ] **Step 1: Add `cellCenter` to `world-observations.ts`**

Add this function directly after the existing `gridCell` export (line 18) in `site/src/lib/world-observations.ts`:

```typescript
/**
 * Inverse of gridCell: approximate lat/lng center of a cell, given its key.
 * Used by map-facing reads that need real coordinates back, not just a cell id.
 */
export function cellCenter(cellId: string, cellSizeMeters = CELL_SIZE_METERS): { lat: number; lng: number } {
  const [latIdxStr, lngIdxStr] = cellId.split(':')
  const latIdx = Number(latIdxStr)
  const lngIdx = Number(lngIdxStr)
  const latStep = cellSizeMeters / METERS_PER_DEG_LAT
  const lngStep = cellSizeMeters / METERS_PER_DEG_LNG
  return {
    lat: (latIdx + 0.5) * latStep,
    lng: (lngIdx + 0.5) * lngStep,
  }
}
```

- [ ] **Step 2: Write the route**

```typescript
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { cellCoverageSummary } from '@/db/schema'
import { and, between } from 'drizzle-orm'
import { cellCenter } from '@/lib/world-observations'

export const dynamic = 'force-dynamic'

const STALE_AFTER_MS = 24 * 60 * 60 * 1000

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const bboxParam = searchParams.get('bbox')
    if (!bboxParam) {
      return NextResponse.json({ cells: [], success: false, error: 'bbox required' }, { status: 400 })
    }

    const parts = bboxParam.split(',').map(Number)
    if (parts.length !== 4 || !parts.every(Number.isFinite)) {
      return NextResponse.json({ cells: [], success: false, error: 'bbox must be minLng,minLat,maxLng,maxLat' }, { status: 400 })
    }
    const [minLng, minLat, maxLng, maxLat] = parts

    const rows = await db
      .select()
      .from(cellCoverageSummary)
      .limit(5000)

    const now = Date.now()
    const cells = rows
      .map((r) => {
        const center = cellCenter(r.locationCellId)
        return { r, center }
      })
      .filter(({ center }) =>
        center.lng >= minLng && center.lng <= maxLng &&
        center.lat >= minLat && center.lat <= maxLat
      )
      .map(({ r, center }) => ({
        locationCellId: r.locationCellId,
        lat: center.lat,
        lng: center.lng,
        state: (now - new Date(r.lastScannedAt).getTime() > STALE_AFTER_MS ? 'stale' : 'fresh') as 'fresh' | 'stale',
        lastScannedAt: r.lastScannedAt,
        totalDistanceMeters: r.totalDistanceMeters,
      }))

    return NextResponse.json({ cells, success: true })
  } catch (error) {
    console.error('GET /api/map/coverage error:', error)
    return NextResponse.json({ cells: [], success: false }, { status: 500 })
  }
}
```

Note: this filters in application code after a full-table read rather than a `WHERE` clause on `lat`/`lng`, because `cellCoverageSummary` doesn't store lat/lng directly (only the cell id) — deriving center coordinates requires decoding the id first. This is fine at current and near-term scale (a `LIMIT 5000` cap keeps worst case bounded); if the table grows large enough for this to matter, adding a materialized `lat`/`lng` column to `cellCoverageSummary` for direct `between()` filtering is a one-line follow-up, not a redesign.

- [ ] **Step 3: Typecheck**

Run: `cd site && npx tsc --noEmit`
Expected: no new errors.

- [ ] **Step 4: Manual verification against the dev server**

With the dev server still running from Task 3 (or `cd site && npm run dev`):
```bash
curl -s "http://localhost:7350/api/map/coverage?bbox=-87.7,41.8,-87.6,41.9"
```
Expected: `{"cells":[{"locationCellId":"...","lat":41.8...,"lng":-87.6...,"state":"fresh","lastScannedAt":"...","totalDistanceMeters":...}],"success":true}` — includes the cell written by Task 3's curl tests (lat 41.87, lng -87.68 falls inside this bbox).

Then confirm bbox filtering actually excludes out-of-range cells:
```bash
curl -s "http://localhost:7350/api/map/coverage?bbox=10,10,11,11"
```
Expected: `{"cells":[],"success":true}` (no cells in that bbox).

- [ ] **Step 5: Commit**

```bash
git add site/src/lib/world-observations.ts site/src/app/api/map/coverage/route.ts
git commit -m "feat(api): add GET /api/map/coverage endpoint + cellCenter helper"
```

---

## Self-Review Notes

- **Spec coverage:** implements design spec §0.2 (`scanCoverageEvents` schema), §0.3 (`worldObservations.domain`), §0.4 (both new endpoints). Does not implement §0.3's non-litter detection logic (explicitly out of scope per the spec) or the mobile-side emission (Phase 1, separate plan).
- **Placeholder scan:** none found — every step has complete, runnable code and exact verification commands.
- **Type consistency:** `RecordCoverageEventInput` (Task 2) matches the request body shape validated in Task 3's route handler field-by-field; `cellCenter`'s return type (`{ lat, lng }`) matches its usage in Task 4's route.
