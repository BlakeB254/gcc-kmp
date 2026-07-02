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
