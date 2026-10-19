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
import {
  HOTSPOT_ROUTE_BUFFER_METERS,
  capHotspotVolume,
  hotspotPlacement,
  isScanPhotoUrl,
} from '@/lib/hotspot-checks'
import { loadRouteGeometry } from '@/lib/scan-completion'
import { checkEvidencePhotos } from '@/lib/photo-fingerprint-db'

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
    const estimatedWeightGrams = Number.isFinite(Number(body?.estimatedWeightGrams))
      ? Math.round(Number(body.estimatedWeightGrams))
      : null
    const estimatedWidthMeters = Number.isFinite(Number(body?.estimatedWidthMeters))
      ? Number(body.estimatedWidthMeters)
      : null
    const beforePhotoUrl = typeof body?.beforePhotoUrl === 'string' ? body.beforePhotoUrl : ''
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
      return NextResponse.json({ data: null, success: false, message: 'lat and lng are required' }, { status: 400 })
    }
    if (!beforePhotoUrl) {
      return NextResponse.json({ data: null, success: false, message: 'beforePhotoUrl is required' }, { status: 400 })
    }
    if (!isScanPhotoUrl(beforePhotoUrl, scan, auth.workerId)) {
      return NextResponse.json(
        { data: null, success: false, message: 'Hotspot photos must be uploaded through the app' },
        { status: 400 },
      )
    }

    // The hotspot must be on the block this scan is for (lib/hotspot-checks.ts).
    // A route with no geometry cannot be checked here — and cannot complete
    // either (scan-verification coverageRefusal), so nothing pays from it.
    const placement = hotspotPlacement(lat, lng, await loadRouteGeometry(db, scan.routeId))
    if (placement === 'off-route') {
      return NextResponse.json(
        {
          data: null,
          success: false,
          message: `This hotspot is more than ${HOTSPOT_ROUTE_BUFFER_METERS} m from the route being scanned`,
        },
        { status: 422 },
      )
    }

    // Fingerprint the photo; exact reuse from another job is refused, the
    // rest are review flags (lib/photo-fingerprint.ts).
    const photoCheck = await checkEvidencePhotos(
      db,
      [{ url: beforePhotoUrl, role: 'before' }],
      { kind: 'scan', id: scanId, workStartedAt: scan.scoutStartedAt },
      { isOwn: (url) => isScanPhotoUrl(url, scan, auth.workerId) },
    )
    if (photoCheck.reject) {
      return NextResponse.json({ data: null, success: false, message: photoCheck.reject }, { status: 409 })
    }

    const clientVolume = Number.isFinite(Number(body?.estimatedVolumeLiters)) && body?.estimatedVolumeLiters != null
      ? Number(body.estimatedVolumeLiters)
      : null
    const [hotspot] = await db
      .insert(scanHotspots)
      .values({
        scanId,
        lat,
        lng,
        trashTypes: JSON.stringify(normalizeTags(body?.trashTypes)),
        severity: Number.isInteger(Number(body?.severity)) ? Number(body.severity) : 1,
        estimatedVolumeLiters: capHotspotVolume(clientVolume),
        clientEstimatedVolumeLiters: clientVolume,
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
      attributes: {
        severity: hotspot.severity,
        ...(hotspot.estimatedVolumeLiters != null ? { estimatedVolumeLiters: hotspot.estimatedVolumeLiters } : {}),
        ...(hotspot.estimatedWeightLbs != null ? { estimatedWeightLbs: hotspot.estimatedWeightLbs } : {}),
        ...(estimatedWeightGrams != null ? { estimatedWeightGrams } : {}),
        ...(estimatedWidthMeters != null ? { estimatedWidthMeters } : {}),
      },
      confidence: hotspot.confidence,
      mediaUrl: hotspot.beforePhotoUrl,
      depthData: hotspot.depthData ?? undefined,
      arAnchorData: hotspot.arAnchorData ?? undefined,
      capturedAt: hotspot.detectedAt,
      // Block linkage (route_scans.block_id, resolved at scan creation) lets
      // recordWorldObservation run the block's exclusion-zone check; null on
      // legacy scans or routes without a block row, which skips the check.
      blockId: scan.blockId ?? undefined,
      reporterWorkerId: scan.workerId,
    })

    return NextResponse.json({ data: toMobileScanHotspot(hotspot), success: true }, { status: 201 })
  } catch (error) {
    console.error('POST /api/scans/[id]/hotspots error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to add hotspot' }, { status: 500 })
  }
}
