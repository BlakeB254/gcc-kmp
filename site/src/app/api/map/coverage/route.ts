import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { cellCoverageSummary } from '@/db/schema'
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

    // Optional delta cursor: unparseable/missing `since` is silently ignored
    // (full response) so old/broken clients never see a 4xx here.
    const sinceParam = searchParams.get('since')
    const sinceMs = sinceParam ? new Date(sinceParam).getTime() : NaN
    const since = Number.isFinite(sinceMs) ? sinceMs : null

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
      .filter(({ r }) => since === null || new Date(r.lastScannedAt).getTime() >= since)
      .map(({ r, center }) => ({
        locationCellId: r.locationCellId,
        lat: center.lat,
        lng: center.lng,
        state: (now - new Date(r.lastScannedAt).getTime() > STALE_AFTER_MS ? 'stale' : 'fresh') as 'fresh' | 'stale',
        quality: r.bestQuality,
        lastScannedAt: r.lastScannedAt,
        totalDistanceMeters: r.totalDistanceMeters,
      }))

    return NextResponse.json({ cells, success: true })
  } catch (error) {
    console.error('GET /api/map/coverage error:', error)
    return NextResponse.json({ cells: [], success: false }, { status: 500 })
  }
}
