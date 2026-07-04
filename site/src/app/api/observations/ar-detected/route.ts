import { NextRequest, NextResponse } from 'next/server'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { recordWorldObservation } from '@/lib/world-observations'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

interface ArDetectedMarker {
  lat: number
  lng: number
  label: string
  capturedAt: string
}

export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const body = await request.json()
    const markers: ArDetectedMarker[] = body?.markers ?? []

    for (const marker of markers) {
      await recordWorldObservation({
        sourceType: 'ar_auto_detect',
        lat: marker.lat,
        lng: marker.lng,
        itemType: marker.label,
        state: 'dirty',
        capturedAt: marker.capturedAt,
      })
    }

    return NextResponse.json(
      { data: { inserted: markers.length, success: true }, success: true },
      { status: 201 },
    )
  } catch (error) {
    console.error('POST /api/observations/ar-detected error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to record markers' }, { status: 500 })
  }
}
