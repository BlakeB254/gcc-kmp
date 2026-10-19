import { NextRequest, NextResponse } from 'next/server'
import { recordCoverageEvent, RecordCoverageEventInput } from '@/lib/scan-coverage'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { MAX_DISTANCE_METERS_PER_EVENT } from '@/lib/points-config'
import { db } from '@/db'
import { findCoverageAttributionScan } from '@/lib/scan-completion'
import { attributeCoverageEvent } from '@/lib/scan-verification'
import {
  addDrop,
  emptyGpsStats,
  filterDeviceFixes,
  readMockFlag,
  type GpsDropReason,
} from '@/lib/gps-plausibility'
import { loadDeviceAnchor, loadRecordedCaptureTimes, recordScanGpsStats } from '@/lib/gps-plausibility-db'

export const dynamic = 'force-dynamic'

const MAX_BATCH_SIZE = 500

function isWellFormed(event: RecordCoverageEventInput): boolean {
  return (
    typeof event.deviceId === 'string' &&
    (event.platform === 'ios' || event.platform === 'android') &&
    typeof event.lat === 'number' &&
    typeof event.lng === 'number' &&
    Number.isFinite(event.lat) &&
    Number.isFinite(event.lng) &&
    typeof event.distanceMeters === 'number' &&
    Number.isFinite(event.distanceMeters) &&
    event.distanceMeters >= 0 &&
    event.distanceMeters <= MAX_DISTANCE_METERS_PER_EVENT &&
    (event.scanMode === 'route' || event.scanMode === 'spot') &&
    ['basic', 'scanner', 'pro_scanner'].includes(event.deviceTier) &&
    typeof event.capturedAt === 'string'
  )
}

export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ inserted: 0, success: false, error: 'Not authenticated' }, { status: 401 })
  }

  try {
    const body = await request.json()
    const events: RecordCoverageEventInput[] = Array.isArray(body.events) ? body.events : []

    if (events.length === 0) {
      return NextResponse.json({ inserted: 0, success: false, error: 'events array required' }, { status: 400 })
    }

    if (events.length > MAX_BATCH_SIZE) {
      return NextResponse.json({ inserted: 0, success: false, error: 'events array too large (max 500)' }, { status: 400 })
    }

    // Server-side attribution: route-mode events are tied to the caller's
    // single active route scan (route_scans with performed_by_admin_id =
    // caller, or the caller's own). Nothing in the payload can choose it.
    const nowMs = Date.now()
    const activeScan = await findCoverageAttributionScan(db, auth.workerId, new Date(nowMs).toISOString())

    // Malformed events are skipped rather than failing the whole batch.
    const valid = events.filter(isWellFormed)

    // GPS plausibility (lib/gps-plausibility.ts): each device's fixes are a
    // trajectory continuing from its last recorded fix. Implausible fixes
    // are dropped — not recorded, so they earn nothing and never count
    // toward a scan's route coverage.
    const byDevice = new Map<string, number[]>()
    valid.forEach((event, i) => {
      const list = byDevice.get(event.deviceId) ?? []
      list.push(i)
      byDevice.set(event.deviceId, list)
    })
    const dropReason = new Map<number, GpsDropReason>()
    // Fixes this device already has on record (a replay of history): not
    // recorded or paid again, reported as duplicates.
    const replayed = new Set<number>()
    // Server-measured walked distance per kept fix (see FilterResult.distanceMeters).
    const serverDistance = new Map<number, number>()
    for (const [deviceId, indexes] of byDevice) {
      const anchor = await loadDeviceAnchor(db, auth.workerId, deviceId)
      if (anchor) {
        const older = indexes.filter((i) => Date.parse(valid[i].capturedAt) < anchor.capturedAtMs)
        const onRecord = await loadRecordedCaptureTimes(db, auth.workerId, deviceId, older.map((i) => valid[i].capturedAt))
        for (const i of older) if (onRecord.has(Date.parse(valid[i].capturedAt))) replayed.add(i)
      }
      const fixes = indexes.map((i) => ({
        lat: valid[i].lat,
        lng: valid[i].lng,
        capturedAt: valid[i].capturedAt,
        mocked: readMockFlag(valid[i] as unknown as Record<string, unknown>),
      }))
      const result = filterDeviceFixes(fixes, anchor, nowMs)
      for (const { index, reason } of result.dropped) dropReason.set(indexes[index], reason)
      for (const [index, meters] of Object.entries(result.distanceMeters)) serverDistance.set(indexes[Number(index)], meters)
    }

    // Counted against the active scan: route-mode fixes it would have been
    // credited with, plus route-mode fixes whose timestamp could not place
    // them (future / unparseable) — those are exactly the suspicious ones.
    const scanStats = emptyGpsStats()
    let inserted = 0
    let dropped = 0
    let duplicates = 0
    for (let i = 0; i < valid.length; i++) {
      const event = valid[i]
      if (replayed.has(i)) {
        duplicates++
        continue
      }
      const routeScanId = attributeCoverageEvent(event, activeScan)
      const reason = dropReason.get(i)
      if (reason) {
        dropped++
        const timeless = reason === 'future' || reason === 'bad_timestamp'
        if (activeScan && event.scanMode === 'route' && (routeScanId != null || timeless)) addDrop(scanStats, reason)
        continue
      }
      // Deploy-order note (Pillar 7): this normalizer ships in the same PR
      // that starts sending 'seen' from new clients — the server deploys on
      // merge, so it already accepts 'seen' before any client does. Any
      // OTHER unrecognized value (old clients, which never send 'seen'
      // anyway; or a corrupt payload) coerces to 'grazed', same as before.
      const quality: 'seen' | 'grazed' | 'close' =
        event.quality === 'close' ? 'close' : event.quality === 'seen' ? 'seen' : 'grazed'
      const row = await recordCoverageEvent({
        ...event,
        // Paid distance is never more than the server measured between kept
        // fixes — a jump across a re-anchor or a dropped spike is not walked.
        distanceMeters: Math.min(event.distanceMeters, serverDistance.get(i) ?? 0),
        quality,
        workerId: auth.workerId,
        routeScanId,
      })
      // null = this fix was already recorded (a retried batch): not counted twice.
      if (!row) {
        duplicates++
        continue
      }
      if (routeScanId != null) scanStats.kept++
      inserted++
    }

    // Review-only counters: the fixes are already recorded, so a failure here
    // must not 500 the batch (the client would resend it).
    if (activeScan) {
      try {
        await recordScanGpsStats(db, activeScan.id, scanStats)
      } catch (error) {
        console.error('POST /api/scans/coverage: GPS stats update failed (fixes were recorded):', error)
      }
    }

    if (inserted === 0 && dropped === 0 && duplicates === 0) {
      return NextResponse.json({ inserted: 0, success: false, error: 'no valid events in batch' }, { status: 422 })
    }

    // A batch whose fixes were all implausible is still "delivered": the
    // client must not keep retrying fixes the server will never accept.
    return NextResponse.json({ inserted, dropped, ...(duplicates ? { duplicates } : {}), success: true })
  } catch (error) {
    console.error('POST /api/scans/coverage error:', error)
    return NextResponse.json({ inserted: 0, success: false }, { status: 500 })
  }
}
