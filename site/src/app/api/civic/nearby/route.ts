/**
 * GET /api/civic/nearby?lat=&lng=&radiusM=75 — open Chicago 311 reports near
 * an active scan, for the in-scan "do you see something matching this
 * report?" prompt (docs/superpowers/specs/2026-07-10-chicago-311-civic-loop-
 * design.md §5). Reads the `civic_reports` mirror the Ktor backend's
 * CivicReportSync maintains; only status='Open' rows prompt.
 *
 * Radius filtering uses a lat/lng bounding box over the mirrored lat/lng
 * columns (same fixed meters-per-degree scales as the cell grid) — exact
 * haversine at these radii (<200 m) is indistinguishable from the box +
 * client-side distance sort, and the box uses the existing indexes.
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { civicReports, civicReportEvidence } from '@/db/schema'
import { and, eq, gte, lte, isNotNull, sql } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'

const METERS_PER_DEG_LAT = 111_000
const METERS_PER_DEG_LNG = 82_300
const MAX_RADIUS_M = 200
const MAX_RESULTS = 10

export async function GET(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  const { searchParams } = new URL(request.url)
  const lat = Number(searchParams.get('lat'))
  const lng = Number(searchParams.get('lng'))
  const radiusM = Math.min(Number(searchParams.get('radiusM')) || 75, MAX_RADIUS_M)
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
    return NextResponse.json({ data: null, success: false, message: 'lat/lng required' }, { status: 400 })
  }

  const dLat = radiusM / METERS_PER_DEG_LAT
  const dLng = radiusM / METERS_PER_DEG_LNG

  const rows = await db
    .select({
      srNumber: civicReports.srNumber,
      srType: civicReports.srType,
      createdDate: civicReports.createdDate,
      streetAddress: civicReports.streetAddress,
      communityArea: civicReports.communityArea,
      lat: civicReports.lat,
      lng: civicReports.lng,
      locationCellId: civicReports.locationCellId,
      evidenceCount: sql<number>`(SELECT count(*)::int FROM ${civicReportEvidence} e WHERE e.sr_number = ${civicReports.srNumber})`,
    })
    .from(civicReports)
    .where(
      and(
        eq(civicReports.status, 'Open'),
        isNotNull(civicReports.lat),
        gte(civicReports.lat, lat - dLat),
        lte(civicReports.lat, lat + dLat),
        gte(civicReports.lng, lng - dLng),
        lte(civicReports.lng, lng + dLng)
      )
    )
    .limit(50)

  const withDistance = rows
    .map((r) => ({
      ...r,
      distanceM: Math.round(
        Math.hypot(((r.lat ?? 0) - lat) * METERS_PER_DEG_LAT, ((r.lng ?? 0) - lng) * METERS_PER_DEG_LNG)
      ),
    }))
    .filter((r) => r.distanceM <= radiusM)
    .sort((a, b) => a.distanceM - b.distanceM)
    .slice(0, MAX_RESULTS)

  return NextResponse.json({ data: { reports: withDistance }, success: true })
}
