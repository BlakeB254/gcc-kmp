import { NextRequest, NextResponse } from 'next/server'
import { retractCoverageWindow } from '@/lib/scan-coverage'
import { requireMobileAuth } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'

export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json(
      { deletedEvents: 0, cellsRecomputed: 0, success: false, error: 'Not authenticated' },
      { status: 401 }
    )
  }

  try {
    const body = await request.json()
    if (
      typeof body.deviceId !== 'string' ||
      typeof body.fromCapturedAt !== 'string' ||
      typeof body.toCapturedAt !== 'string'
    ) {
      return NextResponse.json(
        { deletedEvents: 0, cellsRecomputed: 0, success: false, error: 'deviceId, fromCapturedAt, toCapturedAt required' },
        { status: 400 }
      )
    }

    const result = await retractCoverageWindow({
      deviceId: body.deviceId,
      workerId: auth.workerId,
      fromCapturedAt: body.fromCapturedAt,
      toCapturedAt: body.toCapturedAt,
    })
    if ('error' in result && result.error) {
      return NextResponse.json({ ...result, success: false }, { status: 400 })
    }
    return NextResponse.json({ ...result, success: true })
  } catch (error) {
    console.error('POST /api/scans/coverage/retract error:', error)
    return NextResponse.json({ deletedEvents: 0, cellsRecomputed: 0, success: false }, { status: 500 })
  }
}
