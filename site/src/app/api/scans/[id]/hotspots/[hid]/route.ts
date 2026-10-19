import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { scanHotspots } from '@/db/schema'
import { and, eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import {
  canAccessRouteScan,
  findRouteScan,
  normalizeHotspotStatus,
  toMobileScanHotspot,
} from '@/lib/mobile-route-scan-response'
import { recordWorldObservation } from '@/lib/world-observations'
import { clientVerificationScore, isScanPhotoUrl } from '@/lib/hotspot-checks'
import { checkEvidencePhotos } from '@/lib/photo-fingerprint-db'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string; hid: string }> }

export async function PATCH(request: NextRequest, context: RouteContext) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const { id, hid } = await context.params
    const scanId = parseInt(id, 10)
    const hotspotId = parseInt(hid, 10)
    if (Number.isNaN(scanId) || Number.isNaN(hotspotId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid scan or hotspot ID' }, { status: 400 })
    }

    const scan = await findRouteScan(scanId)
    if (!scan) {
      return NextResponse.json({ data: null, success: false, message: 'Scan not found' }, { status: 404 })
    }
    if (!canAccessRouteScan(scan, auth)) {
      return NextResponse.json({ data: null, success: false, message: 'Scan belongs to a different worker' }, { status: 403 })
    }

    const [existing] = await db
      .select({ beforePhotoUrl: scanHotspots.beforePhotoUrl, afterPhotoUrl: scanHotspots.afterPhotoUrl })
      .from(scanHotspots)
      .where(and(eq(scanHotspots.id, hotspotId), eq(scanHotspots.scanId, scanId)))
      .limit(1)
    if (!existing) {
      return NextResponse.json({ data: null, success: false, message: 'Hotspot not found' }, { status: 404 })
    }

    const body = await request.json().catch(() => null)
    const status = body?.status == null ? undefined : normalizeHotspotStatus(body.status)
    const cleanedAt = status === 'cleaned' ? new Date().toISOString() : undefined
    const afterPhotoUrl = typeof body?.afterPhotoUrl === 'string' && body.afterPhotoUrl ? body.afterPhotoUrl : undefined

    if (afterPhotoUrl && !isScanPhotoUrl(afterPhotoUrl, scan, auth.workerId)) {
      return NextResponse.json(
        { data: null, success: false, message: 'Hotspot photos must be uploaded through the app' },
        { status: 400 },
      )
    }
    // "Cleaned" is a claim the after photo backs; without one there is
    // nothing for review (or scan points) to look at.
    if (status === 'cleaned' && !afterPhotoUrl && !existing.afterPhotoUrl) {
      return NextResponse.json(
        { data: null, success: false, message: 'An after photo is required to mark a hotspot cleaned' },
        { status: 400 },
      )
    }
    if (afterPhotoUrl) {
      // The stored before photo may predate origin checks: isOwn gates it
      // too, so a foreign URL is flagged and never fetched.
      const photoCheck = await checkEvidencePhotos(
        db,
        [{ url: afterPhotoUrl, role: 'after' }],
        { kind: 'scan', id: scanId, workStartedAt: scan.scoutStartedAt },
        {
          isOwn: (url) => isScanPhotoUrl(url, scan, auth.workerId),
          pairs: [[existing.beforePhotoUrl, afterPhotoUrl]],
        },
      )
      if (photoCheck.reject) {
        return NextResponse.json({ data: null, success: false, message: photoCheck.reject }, { status: 409 })
      }
    }

    const [updated] = await db
      .update(scanHotspots)
      .set({
        afterPhotoUrl,
        // The client's own match score — stored as its claim; scan points
        // never read it (lib/hotspot-checks.ts scanCompletionPoints).
        verificationScore: body?.verificationScore == null ? undefined : (clientVerificationScore(body.verificationScore) ?? undefined),
        status,
        cleanedAt,
      })
      .where(and(eq(scanHotspots.id, hotspotId), eq(scanHotspots.scanId, scanId)))
      .returning()

    if (!updated) {
      return NextResponse.json({ data: null, success: false, message: 'Hotspot not found' }, { status: 404 })
    }

    if (status === 'cleaned') {
      await recordWorldObservation({
        sourceType: 'route_scan',
        scanHotspotId: updated.id,
        lat: updated.lat,
        lng: updated.lng,
        itemType: null,
        state: 'clean',
        mediaUrl: updated.afterPhotoUrl ?? undefined,
        capturedAt: updated.cleanedAt ?? new Date().toISOString(),
        reporterWorkerId: scan.workerId,
      })
    }

    return NextResponse.json({ data: toMobileScanHotspot(updated), success: true })
  } catch (error) {
    console.error('PATCH /api/scans/[id]/hotspots/[hid] error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to update hotspot' }, { status: 500 })
  }
}
