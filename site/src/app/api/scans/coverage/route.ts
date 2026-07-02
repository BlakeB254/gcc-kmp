import { NextRequest, NextResponse } from 'next/server'
import { recordCoverageEvent, RecordCoverageEventInput } from '@/lib/scan-coverage'

export const dynamic = 'force-dynamic'

export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const events: RecordCoverageEventInput[] = Array.isArray(body.events) ? body.events : []

    if (events.length === 0) {
      return NextResponse.json({ inserted: 0, success: false, error: 'events array required' }, { status: 400 })
    }

    let inserted = 0
    for (const event of events) {
      if (
        typeof event.deviceId !== 'string' ||
        (event.platform !== 'ios' && event.platform !== 'android') ||
        typeof event.lat !== 'number' ||
        typeof event.lng !== 'number' ||
        typeof event.distanceMeters !== 'number' ||
        (event.scanMode !== 'route' && event.scanMode !== 'spot') ||
        !['basic', 'scanner', 'pro_scanner'].includes(event.deviceTier) ||
        typeof event.capturedAt !== 'string'
      ) {
        continue // skip malformed events rather than fail the whole batch
      }
      await recordCoverageEvent(event)
      inserted++
    }

    return NextResponse.json({ inserted, success: true })
  } catch (error) {
    console.error('POST /api/scans/coverage error:', error)
    return NextResponse.json({ inserted: 0, success: false }, { status: 500 })
  }
}
