import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { cellCoverageSummary } from '@/db/schema'
import { cellCenter } from '@/lib/world-observations'

export const dynamic = 'force-dynamic'

const STALE_AFTER_MS = 24 * 60 * 60 * 1000

// Rescan priority (P5): ageFactor x qualityBoost, computed at read time from
// existing summary columns only — no schema migration, no extra join.
// Dirty-observation weighting (nearby unresolved litter) was scoped for v1
// too but skipped: the observations join isn't cheap at this table's scale
// (Global Constraints) — deferred, see PR notes.
const PRIORITY_AGE_FULL_MS = 7 * 24 * 60 * 60 * 1000 // ageFactor saturates at 1.0 after 7d
const PRIORITY_QUALITY_BOOST: Record<string, number> = {
  // SEEN is even less "worked" than grazed (sight isn't work — Pillar 7
  // Global Constraints) — same max urgency boost as grazed.
  seen: 1.0,
  grazed: 1.0,
  close: 0.6,
}

function computePriority(ageMs: number, bestQuality: string): number {
  const ageFactor = Math.min(1, ageMs / PRIORITY_AGE_FULL_MS)
  const qualityBoost = PRIORITY_QUALITY_BOOST[bestQuality] ?? PRIORITY_QUALITY_BOOST.grazed
  return ageFactor * qualityBoost
}

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
      .map(({ r, center }) => {
        const ageMs = now - new Date(r.lastScannedAt).getTime()
        return {
          locationCellId: r.locationCellId,
          lat: center.lat,
          lng: center.lng,
          state: (ageMs > STALE_AFTER_MS ? 'stale' : 'fresh') as 'fresh' | 'stale',
          quality: r.bestQuality,
          lastScannedAt: r.lastScannedAt,
          totalDistanceMeters: r.totalDistanceMeters,
          priority: computePriority(ageMs, r.bestQuality),
        }
      })

    return NextResponse.json({ cells, success: true })
  } catch (error) {
    console.error('GET /api/map/coverage error:', error)
    return NextResponse.json({ cells: [], success: false }, { status: 500 })
  }
}
