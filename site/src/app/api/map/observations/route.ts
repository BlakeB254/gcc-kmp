import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { worldObservations } from '@/db/schema'
import { servableMediaUrl } from '@/lib/observation-media'
import { and, eq, between, gte } from 'drizzle-orm'

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

    // Optional delta cursor: unparseable/missing `since` is silently ignored
    // (full response) so old/broken clients never see a 4xx here.
    const sinceParam = searchParams.get('since')
    const sinceDate = sinceParam && Number.isFinite(new Date(sinceParam).getTime())
      ? new Date(sinceParam).toISOString()
      : null
    if (sinceDate) {
      filters.push(gte(worldObservations.capturedAt, sinceDate))
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
      domain: r.domain,
      // Litter-truth lifecycle (Pillar 8 Task 4) — additive/nullable field,
      // wire-safe for old clients. See WorldObservation.kt's diagnosticStatus
      // KDoc for the null-handling contract on the reading side.
      diagnosticStatus: r.diagnosticStatus,
      // Wire shape is unchanged from before the attributes refactor — only
      // the internal storage location of `severity` moved.
      severity: (r.attributes as Record<string, unknown> | null)?.severity ?? null,
      estimatedWeightGrams: (r.attributes as Record<string, unknown> | null)?.estimatedWeightGrams ?? null,
      estimatedWidthMeters: (r.attributes as Record<string, unknown> | null)?.estimatedWidthMeters ?? null,
      // Footprint-rendering fields (Pillar 3): widthMeters is the generic
      // physical-extent name the footprint layer builder reads regardless of
      // domain — landmarks and sized trash both persist their measured width
      // under the same `estimatedWidthMeters` attribute key, so this is an
      // alias, not a second stored value. yawDegrees is the bearing of the
      // observed face, captured at registration (client Task 1).
      widthMeters: (r.attributes as Record<string, unknown> | null)?.estimatedWidthMeters ?? null,
      yawDegrees: (r.attributes as Record<string, unknown> | null)?.yawDegrees ?? null,
      // Flag-down aware: a user-flagged photo (person/animal/interior/no
      // visible trash) serves as null while the URL stays stored for review.
      mediaUrl: servableMediaUrl(r),
      sourceType: r.sourceType,
      capturedAt: r.capturedAt,
      // Source-record links so clients can resolve the backing report/hotspot
      // (the mobile map uses communityReportId to avoid double-pinning spots
      // it already renders from /api/spots).
      scanHotspotId: r.scanHotspotId,
      communityReportId: r.communityReportId,
    }))

    return NextResponse.json({ observations, success: true })
  } catch (error) {
    console.error('GET /api/map/observations error:', error)
    return NextResponse.json({ observations: [], success: false }, { status: 500 })
  }
}
