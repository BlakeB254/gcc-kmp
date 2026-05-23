import { NextRequest, NextResponse } from 'next/server'
import { requireMobileAuth } from '@/lib/mobile-auth'
import {
  canAccessRouteScan,
  findRouteScan,
  hotspotsForScan,
  toMobileRouteScan,
} from '@/lib/mobile-route-scan-response'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

export async function GET(request: NextRequest, context: RouteContext) {
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

    return NextResponse.json({
      data: toMobileRouteScan(scan, await hotspotsForScan(scanId)),
      success: true,
    })
  } catch (error) {
    console.error('GET /api/scans/[id] error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to fetch scan' }, { status: 500 })
  }
}
