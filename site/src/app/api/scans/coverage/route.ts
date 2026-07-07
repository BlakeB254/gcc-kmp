import { NextRequest, NextResponse } from 'next/server'
import { recordCoverageEvent, RecordCoverageEventInput } from '@/lib/scan-coverage'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { MAX_DISTANCE_METERS_PER_EVENT } from '@/lib/points-config'

export const dynamic = 'force-dynamic'

const MAX_BATCH_SIZE = 500

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

    let inserted = 0
    for (const event of events) {
      if (
        typeof event.deviceId !== 'string' ||
        (event.platform !== 'ios' && event.platform !== 'android') ||
        typeof event.lat !== 'number' ||
        typeof event.lng !== 'number' ||
        typeof event.distanceMeters !== 'number' ||
        !Number.isFinite(event.distanceMeters) ||
        event.distanceMeters < 0 ||
        event.distanceMeters > MAX_DISTANCE_METERS_PER_EVENT ||
        (event.scanMode !== 'route' && event.scanMode !== 'spot') ||
        !['basic', 'scanner', 'pro_scanner'].includes(event.deviceTier) ||
        typeof event.capturedAt !== 'string'
      ) {
        continue // skip malformed events rather than fail the whole batch
      }
      // Deploy-order note (Pillar 7): this normalizer ships in the same PR
      // that starts sending 'seen' from new clients — the server deploys on
      // merge, so it already accepts 'seen' before any client does. Any
      // OTHER unrecognized value (old clients, which never send 'seen'
      // anyway; or a corrupt payload) coerces to 'grazed', same as before.
      const quality: 'seen' | 'grazed' | 'close' =
        event.quality === 'close' ? 'close' : event.quality === 'seen' ? 'seen' : 'grazed'
      await recordCoverageEvent({ ...event, quality, workerId: auth.workerId })
      inserted++
    }

    if (inserted === 0) {
      return NextResponse.json({ inserted: 0, success: false, error: 'no valid events in batch' }, { status: 422 })
    }

    return NextResponse.json({ inserted, success: true })
  } catch (error) {
    console.error('POST /api/scans/coverage error:', error)
    return NextResponse.json({ inserted: 0, success: false }, { status: 500 })
  }
}
