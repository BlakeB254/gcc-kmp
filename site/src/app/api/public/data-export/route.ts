/**
 * SODA-Compatible Public Data Export API
 *
 * GET /api/public/data-export
 *
 * Returns community reports in Chicago Data Portal conventions.
 * Supports SODA-style query parameters for filtering and pagination.
 *
 * Query params:
 *   community_area — Filter by community area number (1-77)
 *   ward — Filter by ward (1-50)
 *   status — Filter by status (open, verified, resolved)
 *   type — Filter by type (sighting, volunteer_cleanup)
 *   $limit — Max results (default 100, max 1000)
 *   $offset — Pagination offset
 *   $order — Sort field (created_date, severity_score, community_area)
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { eq, and, desc, asc, sql } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

// Map internal status to SODA-style status
function mapStatus(status: string): string {
  switch (status) {
    case 'pending': return 'open'
    case 'verified': return 'verified'
    case 'acknowledged': return 'acknowledged'
    case 'resolved': return 'resolved'
    case 'expired': return 'expired'
    default: return status
  }
}

// Map SODA status back to internal
function unmapStatus(sodaStatus: string): string[] {
  switch (sodaStatus) {
    case 'open': return ['pending']
    case 'verified': return ['verified']
    case 'acknowledged': return ['acknowledged']
    case 'resolved': return ['resolved']
    default: return [sodaStatus]
  }
}

function toSodaRecord(r: any, index: number) {
  const coords = r.coordinates as any
  let lat = 0, lng = 0
  if (Array.isArray(coords)) {
    lng = coords[0]; lat = coords[1]
  } else if (coords && typeof coords === 'object') {
    lng = coords.lng ?? coords.lon ?? 0; lat = coords.lat ?? 0
  }

  const tags = Array.isArray(r.tags) ? r.tags : []
  const aiResult = r.onDeviceAiResult as any
  const depthData = r.depthAnalysis as any

  return {
    service_request_id: `GCC-${new Date(r.createdAt).getFullYear()}-${String(r.id).padStart(5, '0')}`,
    created_date: new Date(r.createdAt).toISOString(),
    updated_date: new Date(r.updatedAt).toISOString(),
    status: mapStatus(r.status),
    service_request_type: r.type === 'volunteer_cleanup' ? 'Volunteer Cleanup' : 'Community Cleanliness Report',
    latitude: lat,
    longitude: lng,
    community_area: r.geoCommunityAreaNumber || null,
    community_area_name: r.geoCommunityArea || null,
    ward: r.geoWard || null,
    zip_code: r.geoZipCode || null,
    police_district: r.geoPoliceDistrict || null,
    census_tract: r.geoCensusTract || null,
    street_address: r.geoStreetAddress || null,
    waste_categories: tags,
    severity_score: aiResult?.severityScore ?? null,
    estimated_weight_lbs: aiResult?.estimatedWeightLbs ?? null,
    estimated_volume_cuft: depthData?.volumeEstimateCuFt ?? aiResult?.estimatedVolumeCuFt ?? null,
    photo_count: Array.isArray(r.mediaIds) ? r.mediaIds.length : 0,
    verification_count: r.verificationCount || 0,
    has_lidar_analysis: !!r.depthAnalysis,
    affected_radius_meters: r.affectedRadiusMeters ? parseFloat(r.affectedRadiusMeters) : 15,
    cleanup_duration_minutes: r.cleanupDurationMinutes || null,
    cleanup_bags_collected: r.cleanupBagsCollected || null,
    view_count: r.viewCount || 0,
    thank_count: r.thankCount || 0,
  }
}

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)

    // Parse SODA-style parameters
    const limit = Math.min(parseInt(searchParams.get('$limit') || '100', 10), 1000)
    const offset = parseInt(searchParams.get('$offset') || '0', 10)
    const order = searchParams.get('$order') || 'created_date'
    const communityArea = searchParams.get('community_area')
    const ward = searchParams.get('ward')
    const status = searchParams.get('status')
    const type = searchParams.get('type')

    // Build filters
    const conditions: any[] = []

    if (communityArea) {
      conditions.push(eq(communityReports.geoCommunityAreaNumber, parseInt(communityArea, 10)))
    }
    if (ward) {
      conditions.push(eq(communityReports.geoWard, parseInt(ward, 10)))
    }
    if (status) {
      const internalStatuses = unmapStatus(status)
      if (internalStatuses.length === 1) {
        conditions.push(sql`${communityReports.status} = ${internalStatuses[0]}`)
      }
    }
    if (type) {
      conditions.push(eq(communityReports.type, type as any))
    }

    // Build order
    let orderBy
    switch (order) {
      case 'severity_score':
        orderBy = desc(sql`(${communityReports.onDeviceAiResult}->>'severityScore')::float`)
        break
      case 'community_area':
        orderBy = asc(communityReports.geoCommunityAreaNumber)
        break
      case 'created_date':
      default:
        orderBy = desc(communityReports.createdAt)
    }

    const where = conditions.length > 0 ? and(...conditions) : undefined

    // Count total matching records
    const [countResult] = await db
      .select({ count: sql<number>`count(*)` })
      .from(communityReports)
      .where(where)

    const total = Number(countResult.count)

    // Fetch records
    const reports = await db
      .select()
      .from(communityReports)
      .where(where)
      .orderBy(orderBy)
      .limit(limit)
      .offset(offset)

    const data = reports.map((r, i) => toSodaRecord(r, offset + i))

    return NextResponse.json(data, {
      headers: {
        'X-Total-Count': String(total),
        'X-Limit': String(limit),
        'X-Offset': String(offset),
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Expose-Headers': 'X-Total-Count, X-Limit, X-Offset',
      },
    })
  } catch (error) {
    console.error('Data export error:', error)
    return NextResponse.json({ error: 'Internal server error' }, { status: 500 })
  }
}
