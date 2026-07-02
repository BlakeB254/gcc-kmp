import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { scanHotspots } from '@/db/schema'
import { requireMobileAuth } from '@/lib/mobile-auth'
import {
  canAccessRouteScan,
  findRouteScan,
  normalizeTags,
  toMobileScanHotspot,
} from '@/lib/mobile-route-scan-response'
import { recordWorldObservation } from '@/lib/world-observations'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

export async function POST(request: NextRequest, context: RouteContext) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const { id } = await context.params
    const scanId = parseInt(id, 10)
    if (Number.isNaN(scanId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid scan ID' }, { status: 400 })
    }

    const scan = await findRouteScan(scanId)
    if (!scan) {
      return NextResponse.json({ data: null, success: false, message: 'Scan not found' }, { status: 404 })
    }
    if (!canAccessRouteScan(scan, auth)) {
      return NextResponse.json({ data: null, success: false, message: 'Scan belongs to a different worker' }, { status: 403 })
    }

    const body = await request.json().catch(() => null)
    const lat = Number(body?.lat)
    const lng = Number(body?.lng)
    const beforePhotoUrl = typeof body?.beforePhotoUrl === 'string' ? body.beforePhotoUrl : ''
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
      return NextResponse.json({ data: null, success: false, message: 'lat and lng are required' }, { status: 400 })
    }
    if (!beforePhotoUrl) {
      return NextResponse.json({ data: null, success: false, message: 'beforePhotoUrl is required' }, { status: 400 })
    }

    const [hotspot] = await db
      .insert(scanHotspots)
      .values({
        scanId,
        lat,
        lng,
        trashTypes: JSON.stringify(normalizeTags(body?.trashTypes)),
        severity: Number.isInteger(Number(body?.severity)) ? Number(body.severity) : 1,
        estimatedVolumeLiters: Number.isFinite(Number(body?.estimatedVolumeLiters))
          ? Number(body.estimatedVolumeLiters)
          : null,
        estimatedWeightLbs: Number.isFinite(Number(body?.estimatedWeightLbs))
          ? Number(body.estimatedWeightLbs)
          : null,
        confidence: Number.isFinite(Number(body?.confidence)) ? Number(body.confidence) : 0,
        beforePhotoUrl,
        arAnchorData: typeof body?.arAnchorData === 'string' ? body.arAnchorData : null,
        depthData: typeof body?.depthData === 'string' ? body.depthData : null,
        detectedAt: new Date().toISOString(),
        status: 'detected',
      })
      .returning()

    const trashTypesArr = normalizeTags(body?.trashTypes)
    await recordWorldObservation({
      sourceType: 'route_scan',
      scanHotspotId: hotspot.id,
      lat,
      lng,
      itemType: trashTypesArr[0] ?? 'general_litter',
      state: 'dirty',
      severity: hotspot.severity,
      estimatedVolumeLiters: hotspot.estimatedVolumeLiters ?? undefined,
      estimatedWeightLbs: hotspot.estimatedWeightLbs ?? undefined,
      confidence: hotspot.confidence,
      mediaUrl: hotspot.beforePhotoUrl,
      depthData: hotspot.depthData ?? undefined,
      arAnchorData: hotspot.arAnchorData ?? undefined,
      capturedAt: hotspot.detectedAt,
      // No blockId: routeScans/routes carry no block_id column (verified in schema.ts) —
      // exclusion-zone checks return false for route-scan observations until block
      // linkage is added to the schema (tracked as a known gap, not this task's scope).
    })

    return NextResponse.json({ data: toMobileScanHotspot(hotspot), success: true }, { status: 201 })
  } catch (error) {
    console.error('POST /api/scans/[id]/hotspots error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to add hotspot' }, { status: 500 })
  }
}
