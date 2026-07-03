# Phase 5 — Points Economy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement distance-tracked points per design spec §5 — new-area/stale-rescan/fresh-rescan categorization computed from the coverage ledger (Phase 0), plus the backend half of rectification credit (spec §1.5), wired into the existing `pointsLedger`.

**Architecture:** Extends `pointsActionEnum` with three coverage-related values. `recordCoverageEvent` (Phase 0) gains a categorization step — read the cell's prior `cellCoverageSummary` state *before* the upsert, classify the event (new/stale/fresh), award points via tunable per-meter weight constants. A new `POST /api/scans/[id]/rectify` endpoint (backend half of spec §1.5's expected-litter confirm flow) awards a flat rectification bonus. Does **not** attempt to unify this with the separate mobile-side `routeScans.pointsAwarded`/`ScanService.calculatePoints` path — that's explicitly flagged as separate work in spec §5.1.

**Tech Stack:** Next.js App Router, Drizzle ORM, existing `pointsLedger`/`pointsActionEnum` (extended, not replaced).

## Global Constraints

- Point weights are **admin-tunable config, not hardcoded architecture** per spec §5.1 — implement as named, clearly-commented exported constants in one file, not scattered magic numbers, so a future config-table migration is a small change, not a rewrite.
- `pointsLedger.workerId` is `NOT NULL` — an unauthenticated/workerless coverage event cannot earn points; skip the ledger write (not an error) when `workerId` is absent.
- No unit-test framework in `site/` — verification is typecheck + manual query/curl checks, per this session's established convention.
- Follow existing schema.ts conventions exactly (see `pointsActionEnum`, `pointsLedger` for the patterns to mirror).
- Follow `site/src/app/api/scans/route.ts`'s `requireMobileAuth` pattern for the new rectify endpoint — derive `workerId` from the verified token, never trust a client-supplied worker id.

---

### Task 1: Distance-tracked points — categorize + award on every coverage event

**Files:**
- Modify: `site/src/db/schema.ts` (extend `pointsActionEnum`, around line 1259)
- Create: `site/src/lib/points-config.ts`
- Modify: `site/src/lib/scan-coverage.ts` (`recordCoverageEvent`)

**Interfaces:**
- Produces: `POINTS_PER_METER` config object and `categorizeCoverageEvent(priorSummary, capturedAt): 'new_area' | 'stale_rescan' | 'fresh_rescan'` — both consumed by the modified `recordCoverageEvent`.

- [ ] **Step 1: Extend `pointsActionEnum`**

In `site/src/db/schema.ts`, find the existing enum (around line 1259):

```typescript
export const pointsActionEnum = pgEnum('enum_points_ledger_action', [
  'spot_created',           // Observer reported a new spot (10 pts)
  'verification_dirty',     // Verified spot is still dirty (5 pts)
  'verification_clean',     // Verified spot is already clean (10 pts)
  'volunteer_cleanup',      // Completed a volunteer cleanup (50 pts)
  'already_clean_report',   // Reported spot was already clean (5 pts)
  'admin_grant',            // Admin manually granted/corrected points
  'bug_report',             // Reward for finding a bug in the app/website
])
```

Add three new values, keeping the existing ones byte-for-byte unchanged (this is a live production enum — do not reorder or remove existing values):

```typescript
export const pointsActionEnum = pgEnum('enum_points_ledger_action', [
  'spot_created',           // Observer reported a new spot (10 pts)
  'verification_dirty',     // Verified spot is still dirty (5 pts)
  'verification_clean',     // Verified spot is already clean (10 pts)
  'volunteer_cleanup',      // Completed a volunteer cleanup (50 pts)
  'already_clean_report',   // Reported spot was already clean (5 pts)
  'admin_grant',            // Admin manually granted/corrected points
  'bug_report',             // Reward for finding a bug in the app/website
  'scan_coverage_new_area', // Distance-tracked: scanned a cell never scanned before
  'scan_coverage_stale',    // Distance-tracked: re-scanned a cell stale >24h
  'scan_coverage_rectification', // Confirmed a previously-expected-dirty spot is now clean
])
```

- [ ] **Step 2: Write `points-config.ts`**

```typescript
/**
 * Point weights for the distance-tracked scan coverage economy (design spec
 * §5). Deliberately plain exported constants, not a DB-driven config table —
 * tunable post-launch by editing this file, per the spec's own explicit
 * non-goal ("exact numeric point values... not an architecture decision").
 */
export const POINTS_PER_METER = {
  NEW_AREA: 10,
  STALE_RESCAN: 5,
  FRESH_RESCAN: 0, // no incentive to farm the same block repeatedly in one sitting
} as const

export const RECTIFICATION_BONUS_POINTS = 25

export const STALE_AFTER_MS = 24 * 60 * 60 * 1000
```

- [ ] **Step 3: Write the categorization helper and wire it into `recordCoverageEvent`**

`recordCoverageEvent` must read the cell's prior `cellCoverageSummary` state **before** the upsert (the upsert overwrites `lastScannedAt`, so the "was this new/stale/fresh" question can only be answered from the pre-write state). Rewrite `site/src/lib/scan-coverage.ts`:

```typescript
import { db } from '@/db'
import { scanCoverageEvents, cellCoverageSummary, pointsLedger } from '@/db/schema'
import { gridCell } from '@/lib/world-observations'
import { sql, eq } from 'drizzle-orm'
import { POINTS_PER_METER, STALE_AFTER_MS } from '@/lib/points-config'

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

type CoverageCategory = 'new_area' | 'stale_rescan' | 'fresh_rescan'

/**
 * Classifies a coverage event against the cell's PRIOR state (read before
 * any write this call makes) — 'new_area' if the cell has never been
 * scanned, 'stale_rescan' if the last scan was more than 24h ago,
 * 'fresh_rescan' otherwise.
 */
function categorizeCoverageEvent(
  priorLastScannedAt: string | null,
  capturedAt: string
): CoverageCategory {
  if (priorLastScannedAt === null) return 'new_area'
  const ageMs = new Date(capturedAt).getTime() - new Date(priorLastScannedAt).getTime()
  return ageMs > STALE_AFTER_MS ? 'stale_rescan' : 'fresh_rescan'
}

/**
 * Records one coverage event (append-only) and upserts the per-cell rollup
 * summary used for cheap reads (map tile freshness, points calculation,
 * AR guidance). The event log is the source of truth; the summary is a
 * derived cache maintained on every write, never the other way around.
 *
 * Also computes and awards distance-tracked points (design spec §5) —
 * skipped when the event has no workerId, since pointsLedger.workerId is
 * NOT NULL and an unauthenticated/deviceId-only event can't earn points.
 */
export async function recordCoverageEvent(input: RecordCoverageEventInput) {
  const cellId = gridCell(input.lat, input.lng)

  const [priorSummary] = await db
    .select({ lastScannedAt: cellCoverageSummary.lastScannedAt })
    .from(cellCoverageSummary)
    .where(eq(cellCoverageSummary.locationCellId, cellId))
    .limit(1)

  const category = categorizeCoverageEvent(priorSummary?.lastScannedAt ?? null, input.capturedAt)

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

  if (input.workerId != null) {
    const perMeter =
      category === 'new_area' ? POINTS_PER_METER.NEW_AREA :
      category === 'stale_rescan' ? POINTS_PER_METER.STALE_RESCAN :
      POINTS_PER_METER.FRESH_RESCAN
    const points = Math.round(perMeter * input.distanceMeters)

    if (points > 0) {
      const action = category === 'new_area' ? 'scan_coverage_new_area' : 'scan_coverage_stale'
      await db.insert(pointsLedger).values({
        workerId: input.workerId,
        action,
        points,
        referenceType: 'scan_coverage_event',
        referenceId: row.id,
        createdAt: input.capturedAt,
      })
    }
  }

  return row
}
```

Note `category === 'fresh_rescan'` never reaches the `pointsLedger.insert` call, since `FRESH_RESCAN` is `0` and the `points > 0` guard skips it — this is intentional (spec: "near-zero, to remove incentive to farm the same block") and also means no `pointsActionEnum` value is needed for it in this ledger-row sense; `'scan_coverage_new_area'`/`'scan_coverage_stale'` cover the two cases that actually produce a row. Do not add a `fresh_rescan` ledger action value that's never used — the enum's `'scan_coverage_new_area'` and `'scan_coverage_stale'` values are the only two Task 1 needs.

- [ ] **Step 4: Typecheck**

```bash
cd site && npx tsc --noEmit
```
Expected: no new errors.

- [ ] **Step 5: Push the schema change**

Given this session's documented `drizzle-kit push` introspection bug (see `docs/superpowers/plans/2026-07-02-scan-coverage-ledger.md`'s Task 1 for the full incident), **do not use `drizzle-kit push` for this enum change.** Apply it directly via the same hand-written-DDL escape hatch already proven this session:

```bash
cd site && npx tsx --env-file=.env.local -e "
import { db } from './src/db'
import { sql } from 'drizzle-orm'
db.execute(sql\`ALTER TYPE enum_points_ledger_action ADD VALUE IF NOT EXISTS 'scan_coverage_new_area'\`)
  .then(() => db.execute(sql\`ALTER TYPE enum_points_ledger_action ADD VALUE IF NOT EXISTS 'scan_coverage_stale'\`))
  .then(() => db.execute(sql\`ALTER TYPE enum_points_ledger_action ADD VALUE IF NOT EXISTS 'scan_coverage_rectification'\`))
  .then(() => console.log('enum values added'))
  .then(() => process.exit(0))
  .catch(e => { console.error(e); process.exit(1) })
"
```
(`ADD VALUE IF NOT EXISTS` is safe/idempotent — Postgres 12+ supports this exact syntax for enum extension. This is a pure additive enum extension, not a table/column DDL, so it carries even less risk than Task 1 of the coverage-ledger plan's table creation.)

Verify:
```bash
cd site && npx tsx --env-file=.env.local -e "
import { db } from './src/db'
import { sql } from 'drizzle-orm'
db.execute(sql\`SELECT enumlabel FROM pg_enum WHERE enumtypid = 'enum_points_ledger_action'::regtype ORDER BY enumsortorder\`)
  .then(r => console.log(r.rows))
  .then(() => process.exit(0))
"
```
Expected: all 10 values present (7 original + 3 new), in the order they were added.

- [ ] **Step 6: Manual verification — confirm categorization + points award end-to-end, then clean up completely**

**This writes into the live `pointsLedger` against a real worker row — this repo has no separate dev database (documented team practice throughout this session), and there is no dedicated test-worker account.** The script below is written to be fully self-cleaning: it captures every row id it creates (events, summary, ledger entries) and deletes all of them at the end, so the worker's real points balance and stats are back to exactly their pre-test state when the script exits. Do not skip the cleanup block, and do not modify this script to remove it. If any step after the writes fails, manually verify and clean up before considering this step done — do not leave test data or test points in a real worker's ledger.

```bash
cd site && npx tsx --env-file=.env.local -e "
import { recordCoverageEvent } from './src/lib/scan-coverage'
import { db } from './src/db'
import { pointsLedger, scanCoverageEvents, cellCoverageSummary } from './src/db/schema'
import { eq, or, inArray } from 'drizzle-orm'
import { gridCell } from './src/lib/world-observations'

async function main() {
  const [anyWorker] = await db.select({ id: (await import('./src/db/schema')).workers.id })
    .from((await import('./src/db/schema')).workers).limit(1)
  if (!anyWorker) { console.error('no worker rows exist to test against'); process.exit(1) }
  const workerId = anyWorker.id
  console.log('testing against real workerId:', workerId, '— will fully clean up afterward')

  const lat = 42.001, lng = -87.001 // arbitrary, unlikely to collide with real scanned data
  const cellId = gridCell(lat, lng)

  const r1 = await recordCoverageEvent({
    deviceId: 'points-test', platform: 'ios', workerId, lat, lng,
    distanceMeters: 10, scanMode: 'route', deviceTier: 'pro_scanner',
    capturedAt: new Date().toISOString(),
  })
  console.log('event 1 (expect new_area, 100 pts):', r1.id)

  const r2 = await recordCoverageEvent({
    deviceId: 'points-test', platform: 'ios', workerId, lat, lng,
    distanceMeters: 10, scanMode: 'route', deviceTier: 'pro_scanner',
    capturedAt: new Date().toISOString(),
  })
  console.log('event 2 (expect fresh_rescan, 0 pts, no ledger row):', r2.id)

  const ledgerRows = await db.select().from(pointsLedger).where(eq(pointsLedger.workerId, workerId))
  const thisTestLedgerRows = ledgerRows.filter(r => r.referenceId === r1.id || r.referenceId === r2.id)
  console.log('ledger rows for this test (expect exactly 1, for event 1):', JSON.stringify(thisTestLedgerRows))

  // ── Cleanup: delete every row this test created, in dependency order ──
  if (thisTestLedgerRows.length > 0) {
    await db.delete(pointsLedger).where(inArray(pointsLedger.id, thisTestLedgerRows.map(r => r.id)))
  }
  await db.delete(scanCoverageEvents).where(inArray(scanCoverageEvents.id, [r1.id, r2.id]))
  await db.delete(cellCoverageSummary).where(eq(cellCoverageSummary.locationCellId, cellId))
  console.log('cleanup complete: deleted', thisTestLedgerRows.length, 'ledger row(s), 2 event rows, 1 summary row')

  process.exit(0)
}
main().catch(e => { console.error('FAILED — verify no test data/points remain for worker', e); process.exit(1) })
"
```
Expected: exactly one ledger row created for `r1` (`action: 'scan_coverage_new_area'`, `points: 100`), none for `r2`, and the final cleanup log confirms all test rows were deleted. Re-run the `pointsLedger` query manually afterward if you want extra confidence nothing lingers: `SELECT * FROM points_ledger WHERE reference_type = 'scan_coverage_event' AND reference_id IN (<r1.id>, <r2.id>)` should return zero rows post-cleanup.

- [ ] **Step 7: Commit**

```bash
git add site/src/db/schema.ts site/src/lib/points-config.ts site/src/lib/scan-coverage.ts
git commit -m "feat(points): distance-tracked scan coverage points (new-area/stale-rescan categorization)"
```

---

### Task 2: Rectification endpoint — backend half of spec §1.5

**Files:**
- Create: `site/src/app/api/scans/[id]/rectify/route.ts`

**Interfaces:**
- Consumes: `recordWorldObservation` (`site/src/lib/world-observations.ts`, unchanged), `RECTIFICATION_BONUS_POINTS` (Task 1's `points-config.ts`), `requireMobileAuth` (`site/src/lib/mobile-auth.ts`).
- Produces: `POST /api/scans/[id]/rectify` — the backend endpoint the iOS "confirm clean at expected-dirty spot" interaction (spec §1.5, not yet built on the iOS side — this ships the backend ahead of that MacBook-side work, ready to call once it lands) will call.

- [ ] **Step 1: Write the route**

```typescript
import { NextRequest, NextResponse } from 'next/server'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { recordWorldObservation } from '@/lib/world-observations'
import { db } from '@/db'
import { pointsLedger } from '@/db/schema'
import { RECTIFICATION_BONUS_POINTS } from '@/lib/points-config'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

/**
 * POST /api/scans/[id]/rectify
 *
 * Backend for design spec §1.5: the user aimed the camera at a marker for a
 * previously-expected-dirty spot and confirmed it's actually clean now.
 * Records the clean observation (superseding the prior dirty one via
 * recordWorldObservation's existing logic) and awards a flat rectification
 * bonus — a distinct, higher-value credit than routine coverage scanning,
 * since this is a rarer, higher-effort confirmation action.
 */
export async function POST(request: NextRequest, context: RouteContext) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ success: false, error: 'Not authenticated' }, { status: 401 })
  }

  const { id } = await context.params
  const scanId = parseInt(id, 10)
  if (Number.isNaN(scanId)) {
    return NextResponse.json({ success: false, error: 'Invalid scan ID' }, { status: 400 })
  }

  const body = await request.json().catch(() => null)
  const lat = Number(body?.lat)
  const lng = Number(body?.lng)
  const capturedAt = typeof body?.capturedAt === 'string' ? body.capturedAt : new Date().toISOString()

  if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
    return NextResponse.json({ success: false, error: 'lat and lng are required' }, { status: 400 })
  }

  const observation = await recordWorldObservation({
    sourceType: 'route_scan',
    lat,
    lng,
    itemType: null,
    state: 'clean',
    capturedAt,
  })

  await db.insert(pointsLedger).values({
    workerId: auth.workerId,
    action: 'scan_coverage_rectification',
    points: RECTIFICATION_BONUS_POINTS,
    referenceType: 'world_observation',
    referenceId: observation.id,
    createdAt: capturedAt,
  })

  return NextResponse.json({ success: true, observationId: observation.id, pointsAwarded: RECTIFICATION_BONUS_POINTS })
}
```

- [ ] **Step 2: Typecheck**

```bash
cd site && npx tsc --noEmit
```

- [ ] **Step 3: Manual verification against the dev server**

Requires a valid Bearer token — check `.superpowers/sdd/final-review-fixes-report.md` from this session's earlier Phase 0 auth-fix work for how a test token was obtained (likely via `/api/auth/register` or similar), or check `site/src/lib/mobile-auth.ts`/an existing auth test route for the registration flow. Once you have a token:

```bash
cd site && npm run dev &
sleep 3
curl -s -X POST http://localhost:7350/api/scans/999/rectify \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{"lat":42.002,"lng":-87.002,"capturedAt":"2026-07-02T12:00:00Z"}'
```
Expected: `{"success":true,"observationId":<some id>,"pointsAwarded":25}`. Then confirm the 401 path:
```bash
curl -s -i -X POST http://localhost:7350/api/scans/999/rectify \
  -H "Content-Type: application/json" \
  -d '{"lat":42.002,"lng":-87.002}'
```
Expected: HTTP 401.

Stop the dev server when done.

- [ ] **Step 4: Commit**

```bash
git add site/src/app/api/scans/\[id\]/rectify/route.ts
git commit -m "feat(api): add POST /api/scans/[id]/rectify — backend half of expected-litter confirm flow"
```

---

## Self-Review Notes

- **Spec coverage:** implements design spec §5's three distance-tracked categories (new/stale/fresh, with fresh correctly awarding zero) and the backend half of §1.5's rectification flow. Does not implement §5.1's explicitly-deferred mobile/web points-ledger unification, and does not implement the iOS-side confirm-interaction that calls the Task 2 endpoint (that's MacBook-dependent work, tracked separately in the Phase 1 plan).
- **Placeholder scan:** none — every step has complete code and exact verification commands. The one explicitly-deferred item (ledger unification) is named as deferred by the spec itself, not glossed over here.
- **Type consistency:** `RecordCoverageEventInput` is unchanged from Phase 0/1 (same shape, same callers); `categorizeCoverageEvent`'s return type (`CoverageCategory`) is used consistently within the same function, not exposed elsewhere, so no cross-task drift risk.
