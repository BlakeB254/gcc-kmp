import { NextResponse } from 'next/server'
import { db } from '@/db'
import { scanSessions } from '@/db/schema'
import { and, eq, gte, sql } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { scanSessionRedFlags } from '@/lib/scan-session-flags'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

/**
 * Admin scan-session list (scan-session-replay PRD Phase 2): the review
 * surface's entry point — filterable by platform/lap/device/route/date,
 * lean columns only (no jsonb blobs), headline flight-recorder metrics
 * riding along so the client can render red flags (gate-flapped, upload
 * failures, zero chunks) without a second query. Admin-only by decision
 * (PRD "Decisions from Blake" §1).
 */
export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { searchParams } = new URL(request.url)
    const platform = searchParams.get('platform')
    const lapKind = searchParams.get('lapKind')
    const deviceId = searchParams.get('deviceId')
    const routeId = Number(searchParams.get('routeId'))
    const days = Number(searchParams.get('days'))

    const conditions = [
      platform ? eq(scanSessions.platform, platform) : null,
      lapKind ? eq(scanSessions.lapKind, lapKind) : null,
      deviceId ? eq(scanSessions.deviceId, deviceId) : null,
      Number.isFinite(routeId) && routeId > 0 ? eq(scanSessions.routeId, routeId) : null,
      Number.isFinite(days) && days > 0
        ? gte(scanSessions.startedAt, new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString())
        : null,
    ].filter((c): c is NonNullable<typeof c> => c !== null)

    const rows = await db
      .select({
        id: scanSessions.id,
        sessionId: scanSessions.sessionId,
        deviceId: scanSessions.deviceId,
        deviceModel: scanSessions.deviceModel,
        platform: scanSessions.platform,
        osVersion: scanSessions.osVersion,
        lapKind: scanSessions.lapKind,
        routeId: scanSessions.routeId,
        locationCellId: scanSessions.locationCellId,
        startedAt: scanSessions.startedAt,
        completedAt: scanSessions.completedAt,
        photoCount: scanSessions.photoCount,
        chunkCount: scanSessions.chunkCount,
        observedItemCount: scanSessions.observedItemCount,
        scannedCellCount: scanSessions.scannedCellCount,
        totalBytes: scanSessions.totalBytes,
        geoRefEventCount: scanSessions.geoRefEventCount,
        eventLogR2Key: scanSessions.eventLogR2Key,
        eventCount: scanSessions.eventCount,
        gateFlipCount: scanSessions.gateFlipCount,
        tapCount: scanSessions.tapCount,
        confirmCount: scanSessions.confirmCount,
        rejectCount: scanSessions.rejectCount,
        raycastMissCount: scanSessions.raycastMissCount,
        detectionCount: scanSessions.detectionCount,
        uploadAttemptCount: scanSessions.uploadAttemptCount,
        uploadFailureCount: scanSessions.uploadFailureCount,
        errorCount: scanSessions.errorCount,
        indexedAt: scanSessions.indexedAt,
      })
      .from(scanSessions)
      .where(conditions.length > 0 ? and(...conditions) : undefined)
      // NULLS LAST: recovered/incomplete sessions (no completed_at) sink
      // below live ones instead of floating to the top of a DESC sort.
      .orderBy(sql`${scanSessions.startedAt} DESC NULLS LAST`)
      .limit(200)

    // Server-derived red flags: one threshold set for both admin clients
    // (site panel + iOS GccApiClient) — see lib/scan-session-flags.ts.
    const data = rows.map((row) => ({ ...row, redFlags: scanSessionRedFlags(row) }))

    return NextResponse.json({ data, success: true, message: null })
  } catch (error) {
    console.error('GET /api/admin/scan-sessions error:', error)
    return NextResponse.json({ error: 'Failed to fetch scan sessions' }, { status: 500 })
  }
}
