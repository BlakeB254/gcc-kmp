import { NextResponse } from 'next/server'
import { and, eq, isNull, inArray } from 'drizzle-orm'
import { db } from '@/db'
import { jobs, workers, blocks, media, routeScans, scanHotspots } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import { evidenceMediaIds, evidenceUrls } from '@/lib/job-evidence'
import { loadReviewTrust } from '@/lib/review-trust'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

/**
 * GET /api/admin/jobs/pending-review
 * Returns jobs with status='pending_review' and reviewStatus IS NULL,
 * with worker info, resolved photo URLs, scan presence flags, and `trust`:
 * the server-side evidence checks (qualifying scan coverage + GPS
 * plausibility, photo fingerprint flags, client vs capped hotspot volume,
 * AI review — lib/review-trust.ts). Admin-gated.
 */
export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    // 1. Query pending-review jobs, joining worker and block (for routeId)
    const pendingJobs = await db
      .select({
        jobId: jobs.id,
        workerId: jobs.workerId,
        workerName: workers.name,
        payoutAmount: jobs.payoutAmount,
        beforePhotos: jobs.beforePhotos,
        afterPhotos: jobs.afterPhotos,
        completedAt: jobs.completedAt,
        completionScanId: jobs.completionScanId,
        routeId: blocks.routeId,
      })
      .from(jobs)
      .leftJoin(workers, eq(jobs.workerId, workers.id))
      .leftJoin(blocks, eq(jobs.blockId, blocks.id))
      .where(and(
        eq(jobs.status, 'pending_review'),
        isNull(jobs.reviewStatus),
      ))

    if (pendingJobs.length === 0) {
      return NextResponse.json({ data: [] })
    }

    // 2. Collect legacy integer media ids. Evidence written by
    //    PATCH /api/jobs/:id/evidence is `{url, capturedAt}` objects and needs
    //    no lookup — only old integer-id rows go through the media table.
    const allMediaIds: number[] = []
    for (const job of pendingJobs) {
      allMediaIds.push(...evidenceMediaIds(job.beforePhotos), ...evidenceMediaIds(job.afterPhotos))
    }

    // 3. Batch-resolve media IDs → URLs via the media table
    const mediaUrlMap = new Map<number, string>()
    if (allMediaIds.length > 0) {
      const uniqueIds = [...new Set(allMediaIds)]
      const mediaRows = await db
        .select({ id: media.id, url: media.url })
        .from(media)
        .where(inArray(media.id, uniqueIds))
      for (const m of mediaRows) {
        if (m.url) mediaUrlMap.set(m.id, m.url)
      }
    }

    // 4. Determine hasScan / scanDepthAvailable via blocks.routeId → route_scans → scan_hotspots
    const routeIds = pendingJobs
      .map(j => j.routeId)
      .filter((id): id is number => id !== null && id !== undefined)

    // routeId → { hasScan, scanDepthAvailable }
    const routeScanInfo = new Map<number, { hasScan: boolean; scanDepthAvailable: boolean }>()

    if (routeIds.length > 0) {
      const uniqueRouteIds = [...new Set(routeIds)]

      const scans = await db
        .select({ id: routeScans.id, routeId: routeScans.routeId })
        .from(routeScans)
        .where(inArray(routeScans.routeId, uniqueRouteIds))

      for (const scan of scans) {
        if (!routeScanInfo.has(scan.routeId)) {
          routeScanInfo.set(scan.routeId, { hasScan: true, scanDepthAvailable: false })
        }
      }

      const scanIds = scans.map(s => s.id)
      if (scanIds.length > 0) {
        // Build scanId → routeId lookup
        const scanRouteMap = new Map<number, number>()
        for (const scan of scans) {
          scanRouteMap.set(scan.id, scan.routeId)
        }

        // Fetch hotspots with depth or AR anchor data
        const hotspots = await db
          .select({
            scanId: scanHotspots.scanId,
            depthData: scanHotspots.depthData,
            arAnchorData: scanHotspots.arAnchorData,
          })
          .from(scanHotspots)
          .where(inArray(scanHotspots.scanId, scanIds))

        for (const hotspot of hotspots) {
          if (hotspot.depthData !== null || hotspot.arAnchorData !== null) {
            const routeId = scanRouteMap.get(hotspot.scanId)
            if (routeId !== undefined) {
              const entry = routeScanInfo.get(routeId)
              if (entry) entry.scanDepthAvailable = true
            }
          }
        }
      }
    }

    // 5. Server-side evidence checks per job (batched reads).
    const resolved = pendingJobs.map(job => ({
      jobId: job.jobId,
      completionScanId: job.completionScanId ?? null,
      beforePhotos: evidenceUrls(job.beforePhotos, mediaUrlMap),
      afterPhotos: evidenceUrls(job.afterPhotos, mediaUrlMap),
    }))
    const trustByJob = await loadReviewTrust(db, resolved)

    // 6. Build the response payload
    const data = pendingJobs.map((job, i) => {
      const scanInfo =
        job.routeId !== null && job.routeId !== undefined
          ? (routeScanInfo.get(job.routeId) ?? { hasScan: false, scanDepthAvailable: false })
          : { hasScan: false, scanDepthAvailable: false }

      return {
        jobId: job.jobId,
        workerId: job.workerId ?? null,
        workerName: job.workerName ?? null,
        payoutAmount: job.payoutAmount !== null ? parseFloat(job.payoutAmount) : null,
        beforePhotos: resolved[i].beforePhotos,
        afterPhotos: resolved[i].afterPhotos,
        hasScan: scanInfo.hasScan,
        scanDepthAvailable: scanInfo.scanDepthAvailable,
        completedAt: job.completedAt ?? null,
        trust: trustByJob.get(job.jobId) ?? null,
      }
    })

    return NextResponse.json({ data })
  } catch (error) {
    console.error('Error fetching pending-review jobs:', error)
    return NextResponse.json({ error: 'Failed to fetch pending-review jobs' }, { status: 500 })
  }
}
