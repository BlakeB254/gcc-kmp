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
  estimatedWeightGrams?: number
  estimatedWidthMeters?: number
  mediaUrl?: string | null
}

export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const body = await request.json()
    const markers: ArDetectedMarker[] = body?.markers ?? []

    // mediaUrl is optional (best-effort marker photo — the client never
    // blocks a marker on capture/upload failure), but when present it must
    // be a real, resolvable public URL: bounded length and https:// only, so
    // an unresolvable/garbage string can't ride through to world_observations.
    // null must validate like undefined: the KMP client serializes no-photo
    // markers as literal JSON null (kotlinx GccJson encodeDefaults), and
    // rejecting it would 400 the whole batch forever — ArMarkerQueue keeps
    // retrying a rejected batch indefinitely.
    const isValidMediaUrl = (mediaUrl: unknown) =>
      mediaUrl === undefined ||
      mediaUrl === null ||
      (typeof mediaUrl === 'string' && mediaUrl.length <= 500 && mediaUrl.startsWith('https://'))

    const isValidMarker = (marker: ArDetectedMarker) =>
      Number.isFinite(marker?.lat) &&
      Number.isFinite(marker?.lng) &&
      typeof marker?.label === 'string' &&
      marker.label.length > 0 &&
      typeof marker?.capturedAt === 'string' &&
      marker.capturedAt.length > 0 &&
      isValidMediaUrl(marker?.mediaUrl)

    if (!markers.every(isValidMarker)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid marker data' }, { status: 400 })
    }

    for (const marker of markers) {
      const estimatedWeightGrams = Number.isFinite(Number(marker.estimatedWeightGrams))
        ? Math.round(Number(marker.estimatedWeightGrams))
        : undefined
      const estimatedWidthMeters = Number.isFinite(Number(marker.estimatedWidthMeters))
        ? Number(marker.estimatedWidthMeters)
        : undefined
      await recordWorldObservation({
        sourceType: 'ar_auto_detect',
        lat: marker.lat,
        lng: marker.lng,
        itemType: marker.label,
        state: 'dirty',
        attributes: (estimatedWeightGrams !== undefined || estimatedWidthMeters !== undefined)
          ? {
              ...(estimatedWeightGrams !== undefined ? { estimatedWeightGrams } : {}),
              ...(estimatedWidthMeters !== undefined ? { estimatedWidthMeters } : {}),
            }
          : undefined,
        mediaUrl: marker.mediaUrl ?? undefined,
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
