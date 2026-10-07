/**
 * DB reads/writes for server-verified scan and job completion. The rules are
 * in lib/scan-verification.ts (pure); this file only fetches and applies.
 *
 * Every function takes the executor so tests can drive it with the recording
 * pg-proxy db (lib/testing/recording-db.ts). No `@/db` import.
 */
import { and, desc, eq, gte, inArray, isNull, or, sql } from 'drizzle-orm'
import { routeScans, routes, scanCoverageEvents, type RouteScanRow } from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'
import {
  COMPLETABLE_SCAN_PHASES,
  ROUTE_SCAN_ABANDON_DAYS,
  WORKED_COVERAGE_QUALITIES,
  computeRouteCoverage,
  pickQualifyingScan,
  routeRequiredCells,
  type ActiveScanForAttribution,
  type JobForScan,
  type RouteCoverage,
  type ScanForJob,
} from '@/lib/scan-verification'

/**
 * The cells this scan worked, from the coverage ledger: route-mode GRAZED or
 * CLOSE events attributed to THIS scan at ingest (`route_scan_id`, stamped by
 * POST /api/scans/coverage from the caller's single active route scan). An
 * event from another scan, another worker, an admin's unrelated scanning, or
 * spot mode never carries this id, so it can never count here.
 */
export async function loadScanWorkedCells(exec: DbExecutor, scanId: number): Promise<Set<string>> {
  const rows = await exec
    .selectDistinct({ cellId: scanCoverageEvents.locationCellId })
    .from(scanCoverageEvents)
    .where(
      and(
        eq(scanCoverageEvents.routeScanId, scanId),
        eq(scanCoverageEvents.scanMode, 'route'),
        inArray(scanCoverageEvents.quality, [...WORKED_COVERAGE_QUALITIES]),
      ),
    )
  return new Set(rows.map((r) => r.cellId))
}

export async function loadRouteGeometry(exec: DbExecutor, routeId: number): Promise<unknown | null> {
  const [route] = await exec
    .select({ geometry: routes.geometry })
    .from(routes)
    .where(eq(routes.id, routeId))
    .limit(1)
  return route?.geometry ?? null
}

export type ScanRouteCoverage = {
  requiredCells: Set<string>
  workedCells: Set<string>
  coverage: RouteCoverage
}

/**
 * The one definition of "how much of its route has this scan covered",
 * computed by the server from attributed coverage events. Both the lap-one
 * stamp (PATCH /api/scans/{id}/phase) and completion (POST
 * /api/scans/{id}/complete) use it.
 */
export async function loadScanRouteCoverage(
  exec: DbExecutor,
  scan: Pick<RouteScanRow, 'id' | 'routeId'>,
): Promise<ScanRouteCoverage> {
  const requiredCells = routeRequiredCells(await loadRouteGeometry(exec, scan.routeId))
  const workedCells = await loadScanWorkedCells(exec, scan.id)
  return { requiredCells, workedCells, coverage: computeRouteCoverage(requiredCells, workedCells) }
}

/** `in_progress` and younger than the abandon cutoff (the sweep's complement). */
function activeScanCondition(now: string) {
  return and(
    eq(routeScans.status, 'in_progress'),
    gte(routeScans.createdAt, sql`${now}::timestamptz - make_interval(days => ${ROUTE_SCAN_ABANDON_DAYS}::int)`),
  )
}

/**
 * An active route scan that a new scan would overlap, or null. A worker has at
 * most one active route scan, and so does the person walking it: refused when
 * the scan's worker already has one (their own, or one an admin is performing
 * for them), or when the caller is already walking one (their own, or one they
 * perform on someone's behalf). This is what makes coverage attribution
 * unambiguous — see findCoverageAttributionScan.
 */
export async function findActiveScanConflict(
  exec: DbExecutor,
  input: { workerId: number; callerId: number },
  now: string,
): Promise<{ id: number; workerId: number; performedByAdminId: number | null } | null> {
  const [row] = await exec
    .select({ id: routeScans.id, workerId: routeScans.workerId, performedByAdminId: routeScans.performedByAdminId })
    .from(routeScans)
    .where(
      and(
        activeScanCondition(now),
        or(
          inArray(routeScans.workerId, [...new Set([input.workerId, input.callerId])]),
          eq(routeScans.performedByAdminId, input.callerId),
        ),
      ),
    )
    .orderBy(desc(routeScans.id))
    .limit(1)
  return row ?? null
}

/**
 * The route scan the caller's coverage events belong to: the active scan they
 * are walking — for an admin, the one with `performed_by_admin_id` = caller;
 * otherwise their own (`worker_id` = caller, no performing admin). Exactly
 * one, or null: if there are several (legacy rows from before the one-active
 * rule) nothing is attributed rather than guess.
 */
export async function findCoverageAttributionScan(
  exec: DbExecutor,
  callerId: number,
  now: string,
): Promise<ActiveScanForAttribution | null> {
  const rows = await exec
    .select({ id: routeScans.id, scoutStartedAt: routeScans.scoutStartedAt })
    .from(routeScans)
    .where(
      and(
        activeScanCondition(now),
        or(
          eq(routeScans.performedByAdminId, callerId),
          and(eq(routeScans.workerId, callerId), isNull(routeScans.performedByAdminId)),
        ),
      ),
    )
    .limit(2)
  return rows.length === 1 ? rows[0] : null
}

export type ScanCompletionPatch = {
  cleanPathGeoJson: string | null
  totalTrashVolumeLiters: number | null
  totalTrashWeightLbs: number | null
  pointsAwarded: number
  verifiedCoverageRatio: number
}

/**
 * The completing UPDATE. Re-checks the whole state-machine gate in its WHERE
 * so a scan that changed between the read and here (a double submit, a phase
 * PATCH) cannot be completed by a stale decision. Null = no row matched.
 */
export async function markScanComplete(
  exec: DbExecutor,
  scanId: number,
  patch: ScanCompletionPatch,
  now: string,
): Promise<RouteScanRow | null> {
  const [row] = await exec
    .update(routeScans)
    .set({
      phase: 'complete',
      status: 'completed',
      cleanCompletedAt: now,
      cleanPathGeoJson: patch.cleanPathGeoJson,
      totalTrashVolumeLiters: patch.totalTrashVolumeLiters,
      totalTrashWeightLbs: patch.totalTrashWeightLbs,
      pointsAwarded: patch.pointsAwarded,
      verifiedCoverageRatio: patch.verifiedCoverageRatio,
      updatedAt: now,
    })
    .where(
      and(
        eq(routeScans.id, scanId),
        eq(routeScans.status, 'in_progress'),
        inArray(routeScans.phase, [...COMPLETABLE_SCAN_PHASES]),
        sql`${routeScans.lapOneVerifiedAt} is not null`,
      ),
    )
    .returning()
  return row ?? null
}

/** Bounds the candidate read; a worker has a handful of scans per route. */
const SCAN_CANDIDATE_LIMIT = 20

/**
 * The scan that may complete this job: on the job's route, by the job's
 * worker, server-verified, created at/after the claim, and not already used
 * by another job (lib/scan-verification.ts `scanQualifiesForJob`). Newest
 * first. The unique index on jobs.completion_scan_id is what makes the
 * "not used" rule hold under a race; this read only picks the candidate.
 */
export async function findQualifyingScanForJob(
  exec: DbExecutor,
  job: JobForScan & { workerId: number },
  routeId: number,
): Promise<ScanForJob | null> {
  // Written out qualified: inside a select list drizzle renders a column
  // unqualified ("id"), which in this subquery would bind to j.id.
  const otherJobUsingScan = sql<number | null>`(
    select j.id from jobs j where j.completion_scan_id = "route_scans"."id" and j.id <> ${job.id} limit 1
  )`
  const rows = await exec
    .select({
      id: routeScans.id,
      status: routeScans.status,
      phase: routeScans.phase,
      verifiedCoverageRatio: routeScans.verifiedCoverageRatio,
      createdAt: routeScans.createdAt,
      usedByJobId: otherJobUsingScan,
    })
    .from(routeScans)
    .where(
      and(
        eq(routeScans.routeId, routeId),
        eq(routeScans.workerId, job.workerId),
        eq(routeScans.status, 'completed'),
      ),
    )
    .orderBy(desc(routeScans.cleanCompletedAt), desc(routeScans.id))
    .limit(SCAN_CANDIDATE_LIMIT)
  return pickQualifyingScan(
    rows.map((r) => ({ ...r, usedByJobId: r.usedByJobId == null ? null : Number(r.usedByJobId) })),
    job,
  )
}

/** Postgres unique_violation — two completes raced for the same scan. */
export function isUniqueViolation(error: unknown): boolean {
  const e = error as { code?: unknown; cause?: { code?: unknown } } | null
  return e?.code === '23505' || e?.cause?.code === '23505'
}
