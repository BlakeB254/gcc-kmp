/**
 * Route queue / batch-release engine — TypeScript port of the Ktor
 * RouteQueueService (server/.../services/RouteQueueService.kt) onto the
 * production Next.js + Drizzle stack and the route_release_* / route_community_queue
 * tables (migration 0018).
 *
 * Responsibilities:
 *  - createRelease: turn N routes into a batch with N "open" jobs.
 *  - processBatch: timed waves — expire stale offers, then offer open slots to
 *    the next eligible workers in community queue order (carryover → never-offered
 *    → signup ranking → workerId).
 *  - accept/decline are handled in the worker route-offer endpoints.
 *
 * Ordering & eligibility mirror RouteQueueService.selectCandidates exactly.
 */
import { db } from '@/db'
import {
  blocks,
  jobs,
  routes,
  routeCheckpoints,
  routeReleaseBatches,
  routeReleaseRoutes,
  routeJobOffers,
  routeCommunityQueue,
  serviceAreas,
  workers,
} from '@/db/schema'
import { and, asc, desc, eq, gt, inArray, lte, sql } from 'drizzle-orm'
import { randomUUID } from 'crypto'
import { toMobileRouteCheckpoints } from '@/lib/route-checkpoints'

const ACTIVE_JOB_STATUSES = ['claimed', 'in_progress', 'pending_review'] as const
const NORTH_LAWNDALE_ZIPS = ['60623', '60624']

const iso = (v: unknown): string | null => (v ? new Date(v as string).toISOString() : null)
const isoNow = () => new Date().toISOString()

export function defaultEligibleZipCodes(communityName: string | null | undefined): string[] {
  if (communityName && /lawndale/i.test(communityName)) return NORTH_LAWNDALE_ZIPS
  return []
}

// ---------- serializers (match the app's kotlinx-serialized models) ----------

export function toRouteReleaseBatch(b: typeof routeReleaseBatches.$inferSelect) {
  return {
    id: b.id,
    serviceAreaId: b.serviceAreaId,
    name: b.name,
    communityName: b.communityName,
    eligibleZipCodes: Array.isArray(b.eligibleZipCodes) ? b.eligibleZipCodes : [],
    status: b.status,
    releaseAt: iso(b.releaseAt),
    nextWaveAt: iso(b.nextWaveAt),
    offerWindowMinutes: b.offerWindowMinutes,
    timezone: b.timezone,
    daytimeWindowStart: b.daytimeWindowStart,
    daytimeWindowEnd: b.daytimeWindowEnd,
    cleanupStartsAt: iso(b.cleanupStartsAt),
    cleanupEndsAt: iso(b.cleanupEndsAt),
    targetSlots: b.targetSlots,
    waveNumber: b.waveNumber,
    createdAt: iso(b.createdAt),
    updatedAt: iso(b.updatedAt),
  }
}

async function toMobileRoute(routeId: number) {
  const [route] = await db.select().from(routes).where(eq(routes.id, routeId)).limit(1)
  if (!route) return null
  const cps = await db
    .select()
    .from(routeCheckpoints)
    .where(eq(routeCheckpoints.routeId, routeId))
    .orderBy(routeCheckpoints.sequenceOrder)
  const [routeBlock] = await db
    .select({ locationBoundary: blocks.locationBoundary })
    .from(blocks)
    .where(eq(blocks.routeId, routeId))
    .limit(1)
  return {
    id: route.id,
    code: route.code,
    displayName: route.displayName,
    serviceAreaId: route.serviceAreaId,
    geometry: route.geometry,
    status: route.status,
    distanceMeters: route.lengthMeters ? parseFloat(route.lengthMeters) : null,
    estimatedDurationMinutes: route.estimatedMinutes ? Math.round(parseFloat(route.estimatedMinutes)) : null,
    payoutAmount: Math.round(parseFloat(route.payoutAmount || '10000')),
    checkpoints: toMobileRouteCheckpoints(cps, routeBlock?.locationBoundary ?? route.geometry),
    imageUrl: route.imageUrl || null,
    imageLocationLat: route.imageLocationLat ?? null,
    imageLocationLng: route.imageLocationLng ?? null,
    description: route.description || null,
    createdAt: iso(route.createdAt),
    updatedAt: iso(route.updatedAt),
  }
}

export async function toRouteOffer(
  offer: typeof routeJobOffers.$inferSelect,
  opts: { includeBatch?: boolean; includeRoutes?: boolean } = {},
) {
  let batch = null
  let availableRoutes: unknown[] = []
  if (opts.includeBatch || opts.includeRoutes) {
    const [b] = await db.select().from(routeReleaseBatches).where(eq(routeReleaseBatches.id, offer.batchId)).limit(1)
    if (b && opts.includeBatch) batch = toRouteReleaseBatch(b)
    if (opts.includeRoutes) {
      const openRoutes = await db
        .select({ routeId: routeReleaseRoutes.routeId })
        .from(routeReleaseRoutes)
        .where(and(eq(routeReleaseRoutes.batchId, offer.batchId), eq(routeReleaseRoutes.status, 'open')))
      const serialized = await Promise.all(openRoutes.map((r) => toMobileRoute(r.routeId)))
      availableRoutes = serialized.filter(Boolean)
    }
  }
  return {
    id: offer.id,
    batchId: offer.batchId,
    workerId: offer.workerId,
    waveNumber: offer.waveNumber,
    status: offer.status,
    offeredAt: iso(offer.offeredAt),
    expiresAt: iso(offer.expiresAt),
    respondedAt: iso(offer.respondedAt),
    selectedRouteId: offer.selectedRouteId ?? null,
    selectedJobId: offer.selectedJobId ?? null,
    declineReason: offer.declineReason ?? null,
    batch,
    availableRoutes,
  }
}

// ---------- create ----------

export async function createRelease(
  request: {
    name: string
    serviceAreaId: number
    routeIds: number[]
    releaseAt: string
    offerWindowMinutes?: number
    eligibleZipCodes?: string[]
    communityName?: string | null
    timezone?: string
    daytimeWindowStart?: string
    daytimeWindowEnd?: string
    cleanupStartsAt?: string | null
    cleanupEndsAt?: string | null
  },
  adminWorkerId: number,
) {
  if (!request.routeIds?.length) throw new Error('At least one route is required')
  const offerWindowMinutes = request.offerWindowMinutes ?? 240
  if (offerWindowMinutes < 15 || offerWindowMinutes > 1440) {
    throw new Error('offerWindowMinutes must be between 15 and 1440')
  }

  const [serviceArea] = await db.select().from(serviceAreas).where(eq(serviceAreas.id, request.serviceAreaId)).limit(1)
  if (!serviceArea) throw new Error('Service area not found')

  const communityName = request.communityName || serviceArea.name
  const eligibleZipCodes = (request.eligibleZipCodes?.length
    ? request.eligibleZipCodes
    : defaultEligibleZipCodes(communityName)
  ).map((z) => z.trim())
  if (!eligibleZipCodes.length) throw new Error('eligibleZipCodes are required for this service area')

  const now = isoNow()
  const releaseAt = new Date(request.releaseAt).toISOString()
  const status = new Date(releaseAt) <= new Date(now) ? 'active' : 'scheduled'
  const routeIds = Array.from(new Set(request.routeIds))

  const [batch] = await db
    .insert(routeReleaseBatches)
    .values({
      serviceAreaId: request.serviceAreaId,
      name: request.name,
      communityName,
      eligibleZipCodes,
      status,
      releaseAt,
      nextWaveAt: releaseAt,
      offerWindowMinutes,
      timezone: request.timezone ?? 'America/Chicago',
      daytimeWindowStart: request.daytimeWindowStart ?? '08:00',
      daytimeWindowEnd: request.daytimeWindowEnd ?? '20:00',
      cleanupStartsAt: request.cleanupStartsAt ? new Date(request.cleanupStartsAt).toISOString() : null,
      cleanupEndsAt: request.cleanupEndsAt ? new Date(request.cleanupEndsAt).toISOString() : null,
      targetSlots: routeIds.length,
      waveNumber: 0,
      createdById: adminWorkerId,
      createdAt: now,
      updatedAt: now,
    })
    .returning()

  for (const routeId of routeIds) {
    const [route] = await db.select().from(routes).where(eq(routes.id, routeId)).limit(1)
    if (!route) throw new Error(`Route ${routeId} not found`)

    let [blockRow] = await db.select({ id: blocks.id }).from(blocks).where(eq(blocks.routeId, routeId)).limit(1)
    if (!blockRow) {
      ;[blockRow] = await db
        .insert(blocks)
        .values({
          code: null,
          name: route.displayName,
          serviceAreaId: route.serviceAreaId,
          routeId: route.id,
          status: 'funded',
          fundingGoal: route.payoutAmount || '10000',
          fundingCurrent: route.payoutAmount || '10000',
          createdAt: now,
          updatedAt: now,
        })
        .returning({ id: blocks.id })
    }

    const [activeJob] = await db
      .select({ id: jobs.id })
      .from(jobs)
      .where(and(eq(jobs.blockId, blockRow.id), inArray(jobs.status, ACTIVE_JOB_STATUSES)))
      .limit(1)
    if (activeJob) throw new Error(`Route ${route.code} already has an active job`)

    let [openJob] = await db
      .select({ id: jobs.id })
      .from(jobs)
      .where(and(eq(jobs.blockId, blockRow.id), eq(jobs.status, 'available'), sql`${jobs.workerId} is null`))
      .limit(1)
    if (!openJob) {
      ;[openJob] = await db
        .insert(jobs)
        .values({
          blockId: blockRow.id,
          workerId: null,
          status: 'available',
          payoutAmount: route.payoutAmount || '10000',
          payoutStatus: 'pending',
          createdAt: now,
          updatedAt: now,
        })
        .returning({ id: jobs.id })
    }

    await db
      .insert(routeReleaseRoutes)
      .values({
        batchId: batch.id,
        routeId,
        jobId: openJob.id,
        status: 'open',
        createdAt: now,
        updatedAt: now,
      })
      .onConflictDoNothing()
  }

  return toRouteReleaseBatch(batch)
}

// ---------- candidate selection (mirrors RouteQueueService.selectCandidates) ----------

type Candidate = {
  workerId: number
  isCarryover: boolean
  lastOpportunityAt: string | null
  queueRank: number | null
  opportunitiesCount: number
  carryoverAttempts: number
}

async function selectCandidates(
  batch: typeof routeReleaseBatches.$inferSelect,
  now: string,
  limit: number,
): Promise<Candidate[]> {
  const zipCodes = (Array.isArray(batch.eligibleZipCodes) ? batch.eligibleZipCodes : []).map((z) => String(z).trim())
  if (!zipCodes.length || limit <= 0) return []

  const eligible = await db
    .select({
      id: workers.id,
      neighborhood: workers.signupRankingsNeighborhood,
      city: workers.signupRankingsCity,
      global: workers.signupRankingsGlobal,
    })
    .from(workers)
    .where(
      and(
        eq(workers.verificationStatus, 'approved'),
        eq(workers.verificationProofOfAddressStatus, 'approved'),
        inArray(workers.addressZip, zipCodes),
      ),
    )
  if (!eligible.length) return []

  const eligibleIds = eligible.map((w) => w.id)
  const activeRows = await db
    .select({ workerId: jobs.workerId })
    .from(jobs)
    .where(and(inArray(jobs.workerId, eligibleIds), inArray(jobs.status, ACTIVE_JOB_STATUSES)))
  const activeIds = new Set(activeRows.map((r) => r.workerId))
  const offeredRows = await db
    .select({ workerId: routeJobOffers.workerId })
    .from(routeJobOffers)
    .where(eq(routeJobOffers.batchId, batch.id))
  const offeredIds = new Set(offeredRows.map((r) => r.workerId))

  const available = eligible.filter((w) => !activeIds.has(w.id) && !offeredIds.has(w.id))
  if (!available.length) return []

  // syncQueueRows: insert any newly-eligible worker into the community queue with
  // queueRank = signup ranking (neighborhood → city → global).
  const existing = await db
    .select({ workerId: routeCommunityQueue.workerId })
    .from(routeCommunityQueue)
    .where(
      and(
        eq(routeCommunityQueue.serviceAreaId, batch.serviceAreaId),
        inArray(routeCommunityQueue.workerId, available.map((w) => w.id)),
      ),
    )
  const existingIds = new Set(existing.map((r) => r.workerId))
  for (const w of available) {
    if (existingIds.has(w.id)) continue
    const rank = w.neighborhood ?? w.city ?? w.global
    await db
      .insert(routeCommunityQueue)
      .values({
        serviceAreaId: batch.serviceAreaId,
        workerId: w.id,
        queueRank: rank != null ? String(rank) : null,
        opportunitiesCount: 0,
        carryoverAttempts: 0,
        createdAt: now,
        updatedAt: now,
      })
      .onConflictDoNothing()
  }

  const queueRows = await db
    .select()
    .from(routeCommunityQueue)
    .where(
      and(
        eq(routeCommunityQueue.serviceAreaId, batch.serviceAreaId),
        inArray(routeCommunityQueue.workerId, available.map((w) => w.id)),
      ),
    )

  const nowMs = new Date(now).getTime()
  const candidates: Candidate[] = queueRows.map((q) => {
    const carryoverAfter = q.carryoverReleaseAfter ? new Date(q.carryoverReleaseAfter).getTime() : null
    const isCarryover = carryoverAfter != null && carryoverAfter <= nowMs && q.carryoverAttempts < 1
    return {
      workerId: q.workerId,
      isCarryover,
      lastOpportunityAt: q.lastOpportunityAt,
      queueRank: q.queueRank != null ? parseFloat(q.queueRank) : null,
      opportunitiesCount: q.opportunitiesCount,
      carryoverAttempts: q.carryoverAttempts,
    }
  })

  candidates.sort((a, b) => {
    if (a.isCarryover !== b.isCarryover) return a.isCarryover ? -1 : 1
    const la = a.lastOpportunityAt ? new Date(a.lastOpportunityAt).getTime() : Number.NEGATIVE_INFINITY
    const lb = b.lastOpportunityAt ? new Date(b.lastOpportunityAt).getTime() : Number.NEGATIVE_INFINITY
    if (la !== lb) return la - lb
    const ra = a.queueRank ?? Number.POSITIVE_INFINITY
    const rb = b.queueRank ?? Number.POSITIVE_INFINITY
    if (ra !== rb) return ra - rb
    return a.workerId - b.workerId
  })

  return candidates.slice(0, limit)
}

async function markQueueMissed(serviceAreaId: number, workerId: number, status: string, now: string) {
  await db
    .update(routeCommunityQueue)
    .set({ lastOfferStatus: status as 'pending', carryoverReleaseAfter: now, updatedAt: now })
    .where(and(eq(routeCommunityQueue.serviceAreaId, serviceAreaId), eq(routeCommunityQueue.workerId, workerId)))
}

// ---------- daytime window helpers (simplified, DST-tolerant for the pilot) ----------

function localMinutes(nowMs: number, tz: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(new Date(nowMs))
  const hh = parseInt(parts.find((p) => p.type === 'hour')?.value ?? '0', 10) % 24
  const mm = parseInt(parts.find((p) => p.type === 'minute')?.value ?? '0', 10)
  return hh * 60 + mm
}
const hhmmToMin = (s: string) => {
  const [h, m] = s.split(':').map((n) => parseInt(n, 10))
  return (h || 0) * 60 + (m || 0)
}

/** Returns the instant (ms) of the next moment inside the daytime window. */
function nextDaytimeMs(nowMs: number, tz: string, start: string, end: string): number {
  const lt = localMinutes(nowMs, tz)
  const s = hhmmToMin(start)
  const e = hhmmToMin(end)
  if (lt >= s && lt < e) return nowMs
  const deltaMin = lt < s ? s - lt : 1440 - lt + s
  return nowMs + deltaMin * 60_000
}

// ---------- process (mirrors RouteQueueService.processBatch) ----------

export type ProcessResult = {
  batchId: number
  status: string
  waveNumber: number
  expiredOffers: number
  openSlots: number
  activeOffers: number
  offersSent: number
  nextWaveAt: string | null
}

async function countOpenSlots(batchId: number) {
  const [{ c }] = await db
    .select({ c: sql<number>`count(*)::int` })
    .from(routeReleaseRoutes)
    .where(and(eq(routeReleaseRoutes.batchId, batchId), eq(routeReleaseRoutes.status, 'open')))
  return c
}
async function countActiveOffers(batchId: number, nowIso: string) {
  const [{ c }] = await db
    .select({ c: sql<number>`count(*)::int` })
    .from(routeJobOffers)
    .where(and(eq(routeJobOffers.batchId, batchId), eq(routeJobOffers.status, 'pending'), gt(routeJobOffers.expiresAt, nowIso)))
  return c
}

export async function processBatch(batchId: number, now: string = isoNow()): Promise<ProcessResult> {
  const [batch] = await db.select().from(routeReleaseBatches).where(eq(routeReleaseBatches.id, batchId)).limit(1)
  if (!batch) throw new Error('Release batch not found')

  const result = (over: Partial<ProcessResult> = {}): ProcessResult => ({
    batchId,
    status: over.status ?? batch.status,
    waveNumber: over.waveNumber ?? batch.waveNumber,
    expiredOffers: over.expiredOffers ?? 0,
    openSlots: over.openSlots ?? 0,
    activeOffers: over.activeOffers ?? 0,
    offersSent: over.offersSent ?? 0,
    nextWaveAt: over.nextWaveAt ?? iso(batch.nextWaveAt),
  })

  if (batch.status === 'cancelled' || batch.status === 'completed') return result()
  const nowMs = new Date(now).getTime()
  if (nowMs < new Date(batch.releaseAt).getTime()) return result()
  if (batch.nextWaveAt && nowMs < new Date(batch.nextWaveAt).getTime()) return result()

  // Only send waves during the daytime window.
  const daytimeMs = nextDaytimeMs(nowMs, batch.timezone, batch.daytimeWindowStart, batch.daytimeWindowEnd)
  if (daytimeMs > nowMs) {
    const nextWave = new Date(daytimeMs).toISOString()
    await db
      .update(routeReleaseBatches)
      .set({ status: 'active', nextWaveAt: nextWave, updatedAt: now })
      .where(eq(routeReleaseBatches.id, batchId))
    return result({ status: 'active', nextWaveAt: nextWave })
  }

  // Expire stale pending offers and flag those workers for carryover.
  const expired = await db
    .select({ id: routeJobOffers.id, workerId: routeJobOffers.workerId })
    .from(routeJobOffers)
    .where(and(eq(routeJobOffers.batchId, batchId), eq(routeJobOffers.status, 'pending'), lte(routeJobOffers.expiresAt, now)))
  for (const o of expired) {
    await db
      .update(routeJobOffers)
      .set({ status: 'expired', respondedAt: now, updatedAt: now })
      .where(eq(routeJobOffers.id, o.id))
    await markQueueMissed(batch.serviceAreaId, o.workerId, 'expired', now)
  }

  const openSlots = await countOpenSlots(batchId)
  const activeOffers = await countActiveOffers(batchId, now)

  if (openSlots === 0) {
    await db
      .update(routeReleaseBatches)
      .set({ status: 'completed', nextWaveAt: null, updatedAt: now })
      .where(eq(routeReleaseBatches.id, batchId))
    return result({ status: 'completed', expiredOffers: expired.length, openSlots: 0, activeOffers, nextWaveAt: null })
  }

  const capacity = Math.max(0, openSlots - activeOffers)
  if (capacity === 0) {
    const [earliest] = await db
      .select({ e: routeJobOffers.expiresAt })
      .from(routeJobOffers)
      .where(and(eq(routeJobOffers.batchId, batchId), eq(routeJobOffers.status, 'pending')))
      .orderBy(asc(routeJobOffers.expiresAt))
      .limit(1)
    const nextWave = earliest?.e ? new Date(earliest.e).toISOString() : null
    await db
      .update(routeReleaseBatches)
      .set({ status: 'active', nextWaveAt: nextWave, updatedAt: now })
      .where(eq(routeReleaseBatches.id, batchId))
    return result({ status: 'active', expiredOffers: expired.length, openSlots, activeOffers, nextWaveAt: nextWave })
  }

  const candidates = await selectCandidates(batch, now, capacity)
  const waveNumber = batch.waveNumber + 1
  const expiresAt = new Date(nowMs + batch.offerWindowMinutes * 60_000).toISOString()

  for (const c of candidates) {
    await db.insert(routeJobOffers).values({
      batchId,
      workerId: c.workerId,
      waveNumber,
      status: 'pending',
      offerToken: randomUUID().replace(/-/g, ''),
      offeredAt: now,
      expiresAt,
      createdAt: now,
      updatedAt: now,
    })
    await db
      .update(routeCommunityQueue)
      .set({
        lastOpportunityAt: now,
        lastOfferedBatchId: batchId,
        lastOfferStatus: 'pending',
        opportunitiesCount: c.opportunitiesCount + 1,
        carryoverReleaseAfter: null,
        carryoverAttempts: c.isCarryover ? c.carryoverAttempts + 1 : 0,
        updatedAt: now,
      })
      .where(and(eq(routeCommunityQueue.serviceAreaId, batch.serviceAreaId), eq(routeCommunityQueue.workerId, c.workerId)))
  }

  const nextWave = expiresAt
  await db
    .update(routeReleaseBatches)
    .set({ status: 'active', waveNumber, nextWaveAt: nextWave, updatedAt: now })
    .where(eq(routeReleaseBatches.id, batchId))

  return result({
    status: 'active',
    waveNumber,
    expiredOffers: expired.length,
    openSlots,
    activeOffers: activeOffers + candidates.length,
    offersSent: candidates.length,
    nextWaveAt: nextWave,
  })
}

/** Process every batch that is due (cron / process-notifications style). */
export async function processDueBatches(now: string = isoNow()): Promise<ProcessResult[]> {
  const due = await db
    .select({ id: routeReleaseBatches.id })
    .from(routeReleaseBatches)
    .where(inArray(routeReleaseBatches.status, ['scheduled', 'active']))
  const out: ProcessResult[] = []
  for (const b of due) out.push(await processBatch(b.id, now))
  return out
}
