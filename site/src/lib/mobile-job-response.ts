import { db } from '@/db'
import { blocks, jobs } from '@/db/schema'
import { inArray } from 'drizzle-orm'

export type JobRow = typeof jobs.$inferSelect

export const activeJobStatuses = ['claimed', 'in_progress', 'pending_review'] as const

function toIso(value: string | Date | null | undefined): string | null {
  if (!value) return null
  if (value instanceof Date) return value.toISOString()
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toISOString()
}

function combinedPhotos(before: unknown, after: unknown): unknown[] | null {
  const b = Array.isArray(before) ? before : []
  const a = Array.isArray(after) ? after : []
  return b.length + a.length > 0 ? [...b, ...a] : null
}

export function toMobileJob(job: JobRow, routeId: number | null = null) {
  const createdAt = toIso(job.createdAt) ?? new Date().toISOString()
  const updatedAt = toIso(job.updatedAt) ?? createdAt

  return {
    id: job.id,
    blockId: job.blockId,
    routeId,
    workerId: job.workerId,
    status: job.status ?? 'available',
    timing: {
      claimedAt: toIso(job.claimedAt),
      startedAt: toIso(job.startedAt),
      completedAt: toIso(job.completedAt),
      durationMinutes: null,
    },
    // Evidence entries are `{url, capturedAt}` objects (PATCH
    // /api/jobs/:id/evidence). Before and after travel separately; the old
    // single `photos` field sent `beforePhotos ?? afterPhotos`, so after-photos
    // vanished whenever before-photos existed. `photos` stays (before + after)
    // for app builds that still read it.
    beforePhotos: Array.isArray(job.beforePhotos) ? job.beforePhotos : null,
    afterPhotos: Array.isArray(job.afterPhotos) ? job.afterPhotos : null,
    photos: combinedPhotos(job.beforePhotos, job.afterPhotos),
    gpsPath: job.gpsPath ?? null,
    payoutAmount: job.payoutAmount ? Math.round(parseFloat(job.payoutAmount)) : null,
    payoutStatus: job.payoutStatus ?? null,
    // Admin review of a paid completion (pending_review → approve/deny).
    // A denied job stays pending_review with reviewStatus 'denied'.
    reviewStatus: job.reviewStatus ?? null,
    approvedByAdminId: job.approvedByAdminId ?? null,
    deniedByAdminId: job.deniedByAdminId ?? null,
    denyReason: job.denyReason ?? null,
    reviewedAt: toIso(job.reviewedAt),
    rating: job.rating ? parseFloat(job.rating) : null,
    reviewNotes: job.ratingNotes ?? null,
    fundingSource: null,
    bountyId: null,
    sponsorId: null,
    completionReport: null,
    createdAt,
    updatedAt,
  }
}

export async function routeIdsByBlockId(blockIds: number[]): Promise<Map<number, number | null>> {
  if (blockIds.length === 0) return new Map()

  const blockRows = await db
    .select({ id: blocks.id, routeId: blocks.routeId })
    .from(blocks)
    .where(inArray(blocks.id, blockIds))

  return new Map(blockRows.map((block) => [block.id, block.routeId]))
}

export async function toMobileJobs(jobRows: JobRow[]) {
  const routeMap = await routeIdsByBlockId([...new Set(jobRows.map((job) => job.blockId))])
  return jobRows.map((job) => toMobileJob(job, routeMap.get(job.blockId) ?? null))
}
