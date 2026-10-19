/**
 * The server-side evidence a reviewer sees beside each job in the admin
 * review queue (GET /api/admin/jobs/pending-review): the qualifying scan's
 * server-verified route coverage and GPS plausibility, photo fingerprint
 * flags, client vs server-capped hotspot volume, and the AI review written
 * by the review worker (job_ai_reviews).
 *
 * Read-only; every value here was computed by the server when the evidence
 * arrived (lib/gps-plausibility.ts, lib/hotspot-checks.ts,
 * lib/photo-fingerprint.ts) — this module only gathers it.
 */
import { inArray } from 'drizzle-orm'
import { jobAiReviews, routeScans, scanHotspots } from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'
import { capHotspotVolume } from '@/lib/hotspot-checks'
import { flagsForUse, loadPhotoFlags } from '@/lib/photo-fingerprint-db'

export type ReviewPhotoTrust = {
  url: string
  role: 'before' | 'after' | 'hotspot_before' | 'hotspot_after'
  /** 'ok' | 'unverified' | 'not_fingerprinted' (submitted before 0053). */
  status: string
  flags: string[]
}

export type ReviewTrust = {
  scan: {
    scanId: number
    verifiedCoverageRatio: number | null
    gpsPointsKept: number
    gpsPointsDropped: number
    gpsDropReasons: Record<string, number>
    gpsSuspect: boolean
  } | null
  photos: ReviewPhotoTrust[]
  /** Hotspot volume on the qualifying scan; null when there is no scan. */
  volume: { clientLiters: number; cappedLiters: number; hotspots: number } | null
  /** Null = the review worker has not looked at this job yet. */
  aiReview: { status: string; model: string; verdict: unknown; reviewedAt: string } | null
}

export type JobForTrust = {
  jobId: number
  completionScanId: number | null
  beforePhotos: string[]
  afterPhotos: string[]
}

/** Trust block per job id, from batched reads (no per-job queries). */
export async function loadReviewTrust(exec: DbExecutor, jobs: JobForTrust[]): Promise<Map<number, ReviewTrust>> {
  const out = new Map<number, ReviewTrust>()
  if (jobs.length === 0) return out

  const scanIds = [...new Set(jobs.map((j) => j.completionScanId).filter((id): id is number => id != null))]
  const scans = scanIds.length
    ? await exec
        .select({
          id: routeScans.id,
          verifiedCoverageRatio: routeScans.verifiedCoverageRatio,
          gpsPointsKept: routeScans.gpsPointsKept,
          gpsPointsDropped: routeScans.gpsPointsDropped,
          gpsDropReasons: routeScans.gpsDropReasons,
          gpsSuspect: routeScans.gpsSuspect,
        })
        .from(routeScans)
        .where(inArray(routeScans.id, scanIds))
    : []
  const hotspots = scanIds.length
    ? await exec
        .select({
          scanId: scanHotspots.scanId,
          estimatedVolumeLiters: scanHotspots.estimatedVolumeLiters,
          clientEstimatedVolumeLiters: scanHotspots.clientEstimatedVolumeLiters,
          beforePhotoUrl: scanHotspots.beforePhotoUrl,
          afterPhotoUrl: scanHotspots.afterPhotoUrl,
        })
        .from(scanHotspots)
        .where(inArray(scanHotspots.scanId, scanIds))
    : []
  const reviews = await exec
    .select({
      jobId: jobAiReviews.jobId,
      status: jobAiReviews.status,
      model: jobAiReviews.model,
      verdict: jobAiReviews.verdict,
      createdAt: jobAiReviews.createdAt,
      updatedAt: jobAiReviews.updatedAt,
    })
    .from(jobAiReviews)
    .where(inArray(jobAiReviews.jobId, jobs.map((j) => j.jobId)))

  const allUrls = [
    ...jobs.flatMap((j) => [...j.beforePhotos, ...j.afterPhotos]),
    ...hotspots.flatMap((h) => [h.beforePhotoUrl, h.afterPhotoUrl ?? '']),
  ].filter(Boolean)
  const flags = await loadPhotoFlags(exec, allUrls)
  const photo = (url: string, role: ReviewPhotoTrust['role'], ctx: { kind: 'job' | 'scan'; id: number }): ReviewPhotoTrust => {
    const f = flags.get(url)
    return { url, role, status: f?.status ?? 'not_fingerprinted', flags: flagsForUse(f, ctx) }
  }

  const scanById = new Map(scans.map((s) => [s.id, s]))
  const reviewByJob = new Map(reviews.map((r) => [r.jobId, r]))

  for (const job of jobs) {
    const scan = job.completionScanId != null ? scanById.get(job.completionScanId) : undefined
    const scanHotspotRows = scan ? hotspots.filter((h) => h.scanId === scan.id) : []
    const review = reviewByJob.get(job.jobId)
    // Hotspot photos are listed only when flagged, to keep the card short.
    const hotspotPhotos = scanHotspotRows
      .flatMap((h) => {
        const ctx = { kind: 'scan' as const, id: h.scanId }
        return [photo(h.beforePhotoUrl, 'hotspot_before', ctx), ...(h.afterPhotoUrl ? [photo(h.afterPhotoUrl, 'hotspot_after', ctx)] : [])]
      })
      .filter((p) => p.flags.length > 0)

    out.set(job.jobId, {
      scan: scan
        ? {
            scanId: scan.id,
            verifiedCoverageRatio: scan.verifiedCoverageRatio ?? null,
            gpsPointsKept: scan.gpsPointsKept ?? 0,
            gpsPointsDropped: scan.gpsPointsDropped ?? 0,
            gpsDropReasons: (scan.gpsDropReasons as Record<string, number> | null) ?? {},
            gpsSuspect: scan.gpsSuspect ?? false,
          }
        : null,
      photos: [
        ...job.beforePhotos.map((u) => photo(u, 'before', { kind: 'job', id: job.jobId })),
        ...job.afterPhotos.map((u) => photo(u, 'after', { kind: 'job', id: job.jobId })),
        ...hotspotPhotos,
      ],
      volume: scan
        ? {
            // Legacy rows have no client column: their stored value was the client's.
            clientLiters: scanHotspotRows.reduce((s, h) => s + (h.clientEstimatedVolumeLiters ?? h.estimatedVolumeLiters ?? 0), 0),
            cappedLiters: scanHotspotRows.reduce((s, h) => s + (capHotspotVolume(h.estimatedVolumeLiters) ?? 0), 0),
            hotspots: scanHotspotRows.length,
          }
        : null,
      aiReview: review
        ? { status: review.status, model: review.model, verdict: review.verdict, reviewedAt: review.updatedAt ?? review.createdAt }
        : null,
    })
  }
  return out
}
