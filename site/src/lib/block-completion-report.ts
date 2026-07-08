// Pillar 9 — block completion report generation (server-side, Task 1 of 4).
//
// Assembles a structured report (duration, GPS track, areas cleaned,
// before/after photo pairs, weights, counts) from a job's linked
// route_scans session, right after the job-completion PATCH handler
// (site/src/app/api/jobs/[id]/complete/route.ts) finishes its write. Called
// non-fatally there — a thrown error here must never fail job completion.
//
// Chain: jobs.blockId -> blocks.routeId -> most recent completed
// route_scans row for that route -> scan_hotspots (scanId) and
// world_observations (time-windowed) for that session.
//
// P8 boundary note: this file writes its own read-only SELECT queries
// against world_observations directly (does not import from
// site/src/lib/world-observations.ts's DB-touching internals, which are
// P8-owned). It DOES import the two pure geometry helpers exported there
// (gridCell, cellCenter) to stay on the exact same 10m-cell convention
// rather than reimplementing it — those are plain functions with no DB
// access, safe to reuse.

import { and, desc, eq, gte, lte, or } from 'drizzle-orm'
import { db } from '@/db'
import {
  jobs,
  blocks,
  routeScans,
  scanHotspots,
  worldObservations,
  blockCompletionReports,
  type ScanHotspotRow,
  type BlockCompletionReportRow,
  type NewBlockCompletionReportRow,
} from '@/db/schema'
import { gridCell } from '@/lib/world-observations'
import { haversineDistance, pointInPolygon } from '@/lib/geo-utils'

// ─────────────────────────────────────────────────────────────────────────
// Types (also the DTO shape returned by GET /api/jobs/[id]/completion-report
// — Task 2/3 implementers: rely on BlockCompletionReportRow's field names)
// ─────────────────────────────────────────────────────────────────────────

export interface GpsTrack {
  /** Parsed scoutPathGeoJson (lap 1), or null if the session never recorded one. */
  scout: unknown | null
  /** Parsed cleanPathGeoJson (lap 2), or null. */
  clean: unknown | null
}

export interface AreaCleaned {
  locationCellId: string
  lat: number
  lng: number
  domain: string
  observationCount: number
}

export interface PhotoCandidate {
  locationCellId: string
  lat: number
  lng: number
  photoUrl: string
  itemType: string | null
  /** ISO timestamp string */
  timestamp: string
}

export interface ImagePairSide {
  photoUrl: string
  lat: number
  lng: number
  timestamp: string
}

export interface ImagePair {
  locationCellId: string
  itemType: string | null
  before: ImagePairSide
  /** null when no after-candidate matched this before-candidate's cell+type */
  after: ImagePairSide | null
}

export interface CountsByType {
  byType: Record<string, number>
  bySeverity: Record<string, number>
}

// ─────────────────────────────────────────────────────────────────────────
// Pure function: before/after photo pairing (unit-testable, no DB access)
// ─────────────────────────────────────────────────────────────────────────

/**
 * v1 pairing heuristic: same locationCellId (10m grid cell, see gridCell)
 * + same-or-compatible itemType. "Compatible" means a null itemType (what
 * world_observations records on 'clean' rows — see the itemType comment on
 * the worldObservations table in schema.ts: it's null when nothing dirty
 * was found) matches any before-side itemType; two non-null itemTypes must
 * match exactly. Nearest-position (haversine) tiebreak when multiple
 * after-candidates match a before-candidate's cell+type.
 *
 * Matching is greedy one-to-one: candidates are consumed as they're
 * matched, so one after-photo is never paired with two different
 * before-photos. Before-candidates with no match still produce a pair with
 * `after: null` (there's a photo of the mess; no confirmed-clean photo yet).
 * After-candidates with no match are dropped — there's nothing to compare
 * them against.
 *
 * Takes/returns plain data only — no DB access — so it can be sanity-checked
 * against hand-built fixtures without a database (see task report for the
 * verification transcript; this project has no test runner configured).
 */
export function pairBeforeAfterPhotos(
  beforeCandidates: PhotoCandidate[],
  afterCandidates: PhotoCandidate[]
): ImagePair[] {
  const availableAfter = afterCandidates.map((candidate, idx) => ({ ...candidate, idx }))
  const consumed = new Set<number>()

  const sortedBefore = [...beforeCandidates].sort(
    (a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime()
  )

  return sortedBefore.map((before) => {
    const candidates = availableAfter.filter(
      (after) =>
        !consumed.has(after.idx) &&
        after.locationCellId === before.locationCellId &&
        (after.itemType === null || before.itemType === null || after.itemType === before.itemType)
    )

    const beforeSide: ImagePairSide = {
      photoUrl: before.photoUrl,
      lat: before.lat,
      lng: before.lng,
      timestamp: before.timestamp,
    }

    if (candidates.length === 0) {
      return { locationCellId: before.locationCellId, itemType: before.itemType, before: beforeSide, after: null }
    }

    let best = candidates[0]
    let bestDistance = haversineDistance([[before.lng, before.lat], [best.lng, best.lat]])
    for (const candidate of candidates.slice(1)) {
      const distance = haversineDistance([[before.lng, before.lat], [candidate.lng, candidate.lat]])
      if (distance < bestDistance) {
        best = candidate
        bestDistance = distance
      }
    }
    consumed.add(best.idx)

    return {
      locationCellId: before.locationCellId,
      itemType: before.itemType ?? best.itemType,
      before: beforeSide,
      after: { photoUrl: best.photoUrl, lat: best.lat, lng: best.lng, timestamp: best.timestamp },
    }
  })
}

// ─────────────────────────────────────────────────────────────────────────
// Areas-cleaned query (isolated so a future upgrade to P8's
// supersede/diagnostic fields, if/when they land, is a one-function change)
// ─────────────────────────────────────────────────────────────────────────

/**
 * v1 heuristic: world_observations rows with state='clean' AND
 * isCurrent=true, captured within [sessionStart, sessionEnd], optionally
 * restricted to a block's boundary polygon (ray-cast point-in-polygon —
 * same test used elsewhere for exclusion zones), grouped by locationCellId.
 *
 * Read-only. Does not touch the 'superseded'-vs-'dirty' distinction because
 * the schema has no such state today (state is 'dirty'|'clean' only;
 * supersession is tracked via isCurrent) — see task brief.
 */
export async function queryAreasCleaned(
  sessionStart: string,
  sessionEnd: string,
  blockBoundary?: { coordinates?: [number, number][][] } | null
): Promise<AreaCleaned[]> {
  const rows = await db
    .select({
      locationCellId: worldObservations.locationCellId,
      lat: worldObservations.lat,
      lng: worldObservations.lng,
      domain: worldObservations.domain,
    })
    .from(worldObservations)
    .where(
      and(
        eq(worldObservations.state, 'clean'),
        eq(worldObservations.isCurrent, true),
        gte(worldObservations.capturedAt, sessionStart),
        lte(worldObservations.capturedAt, sessionEnd)
      )
    )

  const ring = blockBoundary?.coordinates?.[0]
  const filtered = ring
    ? rows.filter((row) => pointInPolygon(row.lat, row.lng, ring.map(([lng, lat]) => [lat, lng] as [number, number])))
    : rows

  const byCell = new Map<string, AreaCleaned>()
  for (const row of filtered) {
    const existing = byCell.get(row.locationCellId)
    if (existing) {
      existing.observationCount += 1
    } else {
      byCell.set(row.locationCellId, {
        locationCellId: row.locationCellId,
        lat: row.lat,
        lng: row.lng,
        domain: row.domain,
        observationCount: 1,
      })
    }
  }
  return [...byCell.values()]
}

// ─────────────────────────────────────────────────────────────────────────
// Small helpers (DB-touching but not exported — internal assembly only)
// ─────────────────────────────────────────────────────────────────────────

function parseGeoJson(text: string | null): unknown | null {
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function buildGpsTrack(scoutPathGeoJson: string | null, cleanPathGeoJson: string | null): GpsTrack {
  return { scout: parseGeoJson(scoutPathGeoJson), clean: parseGeoJson(cleanPathGeoJson) }
}

function parseTrashTypes(trashTypesJson: string): string[] {
  try {
    const parsed = JSON.parse(trashTypesJson)
    return Array.isArray(parsed) ? parsed.filter((t): t is string => typeof t === 'string') : []
  } catch {
    return []
  }
}

function firstTrashType(trashTypesJson: string): string | null {
  return parseTrashTypes(trashTypesJson)[0] ?? null
}

function hotspotBeforeCandidates(hotspotRows: ScanHotspotRow[]): PhotoCandidate[] {
  return hotspotRows
    .filter((h) => !!h.beforePhotoUrl)
    .map((h) => ({
      locationCellId: gridCell(h.lat, h.lng),
      lat: h.lat,
      lng: h.lng,
      photoUrl: h.beforePhotoUrl,
      itemType: firstTrashType(h.trashTypes),
      timestamp: h.detectedAt,
    }))
}

function hotspotAfterCandidates(hotspotRows: ScanHotspotRow[]): PhotoCandidate[] {
  return hotspotRows
    .filter((h) => !!h.afterPhotoUrl)
    .map((h) => ({
      locationCellId: gridCell(h.lat, h.lng),
      lat: h.lat,
      lng: h.lng,
      photoUrl: h.afterPhotoUrl as string,
      itemType: firstTrashType(h.trashTypes),
      timestamp: h.cleanedAt ?? h.detectedAt,
    }))
}

async function observationPhotoCandidates(
  state: 'dirty' | 'clean',
  sessionStart: string,
  sessionEnd: string
): Promise<PhotoCandidate[]> {
  const rows = await db
    .select({
      locationCellId: worldObservations.locationCellId,
      lat: worldObservations.lat,
      lng: worldObservations.lng,
      itemType: worldObservations.itemType,
      mediaUrl: worldObservations.mediaUrl,
      capturedAt: worldObservations.capturedAt,
    })
    .from(worldObservations)
    .where(
      and(
        eq(worldObservations.state, state),
        gte(worldObservations.capturedAt, sessionStart),
        lte(worldObservations.capturedAt, sessionEnd)
      )
    )

  return rows
    .filter((r) => !!r.mediaUrl)
    .map((r) => ({
      locationCellId: r.locationCellId,
      lat: r.lat,
      lng: r.lng,
      photoUrl: r.mediaUrl as string,
      itemType: r.itemType,
      timestamp: r.capturedAt,
    }))
}

// ─────────────────────────────────────────────────────────────────────────
// Generator (entry point called from jobs/[id]/complete and the admin
// regenerate route)
// ─────────────────────────────────────────────────────────────────────────

/**
 * Assembles and upserts (one row per jobId) a block_completion_reports row
 * for the given job. Throws on any missing-link failure (no block, no
 * routeId, no completed route_scans session) — callers decide whether that's
 * fatal; the job-completion hook treats it as non-fatal (log + continue),
 * the admin regenerate endpoint surfaces it as a 500.
 */
export async function generateBlockCompletionReport(jobId: number): Promise<BlockCompletionReportRow> {
  const [job] = await db.select().from(jobs).where(eq(jobs.id, jobId)).limit(1)
  if (!job) {
    throw new Error(`generateBlockCompletionReport: job ${jobId} not found`)
  }
  if (job.blockId == null) {
    throw new Error(`generateBlockCompletionReport: job ${jobId} has no blockId`)
  }

  const [block] = await db.select().from(blocks).where(eq(blocks.id, job.blockId)).limit(1)
  if (!block) {
    throw new Error(`generateBlockCompletionReport: block ${job.blockId} not found (job ${jobId})`)
  }
  if (block.routeId == null) {
    throw new Error(`generateBlockCompletionReport: block ${block.id} has no routeId (job ${jobId})`)
  }

  const [session] = await db
    .select()
    .from(routeScans)
    .where(
      and(
        eq(routeScans.routeId, block.routeId),
        or(eq(routeScans.status, 'completed'), eq(routeScans.phase, 'complete'))
      )
    )
    .orderBy(desc(routeScans.cleanCompletedAt))
    .limit(1)
  if (!session) {
    throw new Error(
      `generateBlockCompletionReport: no completed route_scans session for route ${block.routeId} (job ${jobId})`
    )
  }

  const hotspotRows = await db.select().from(scanHotspots).where(eq(scanHotspots.scanId, session.id))

  const startedAt = session.scoutStartedAt
  const completedAt = session.cleanCompletedAt ?? null
  const durationSeconds =
    completedAt != null
      ? Math.max(0, Math.round((new Date(completedAt).getTime() - new Date(startedAt).getTime()) / 1000))
      : null

  const gpsTrack = buildGpsTrack(session.scoutPathGeoJson, session.cleanPathGeoJson)

  const areasCleaned = completedAt
    ? await queryAreasCleaned(
        startedAt,
        completedAt,
        block.locationBoundary as { coordinates?: [number, number][][] } | null
      )
    : []

  const beforeCandidates: PhotoCandidate[] = [...hotspotBeforeCandidates(hotspotRows)]
  const afterCandidates: PhotoCandidate[] = [...hotspotAfterCandidates(hotspotRows)]
  if (completedAt) {
    beforeCandidates.push(...(await observationPhotoCandidates('dirty', startedAt, completedAt)))
    afterCandidates.push(...(await observationPhotoCandidates('clean', startedAt, completedAt)))
  }
  const imagePairs = pairBeforeAfterPhotos(beforeCandidates, afterCandidates)

  let totalEstimatedWeightLbs = 0
  let cleanedEstimatedWeightLbs = 0
  const byType: Record<string, number> = {}
  const bySeverity: Record<string, number> = {}
  for (const h of hotspotRows) {
    const weight = h.estimatedWeightLbs ?? 0
    totalEstimatedWeightLbs += weight
    const isCleaned = h.cleanedAt != null || h.afterPhotoUrl != null
    if (isCleaned) cleanedEstimatedWeightLbs += weight

    for (const t of parseTrashTypes(h.trashTypes)) byType[t] = (byType[t] ?? 0) + 1
    const severityKey = String(h.severity)
    bySeverity[severityKey] = (bySeverity[severityKey] ?? 0) + 1
  }

  const values: NewBlockCompletionReportRow = {
    jobId,
    routeId: block.routeId,
    workerId: job.workerId ?? session.workerId,
    sessionId: session.id,
    startedAt,
    completedAt,
    durationSeconds,
    gpsTrack,
    areasCleaned,
    imagePairs,
    totalEstimatedWeightLbs,
    cleanedEstimatedWeightLbs,
    countsByType: { byType, bySeverity } satisfies CountsByType,
  }

  const [reportRow] = await db
    .insert(blockCompletionReports)
    .values(values)
    .onConflictDoUpdate({
      target: blockCompletionReports.jobId,
      set: {
        routeId: values.routeId,
        workerId: values.workerId,
        sessionId: values.sessionId,
        startedAt: values.startedAt,
        completedAt: values.completedAt,
        durationSeconds: values.durationSeconds,
        gpsTrack: values.gpsTrack,
        areasCleaned: values.areasCleaned,
        imagePairs: values.imagePairs,
        totalEstimatedWeightLbs: values.totalEstimatedWeightLbs,
        cleanedEstimatedWeightLbs: values.cleanedEstimatedWeightLbs,
        countsByType: values.countsByType,
      },
    })
    .returning()

  return reportRow
}
