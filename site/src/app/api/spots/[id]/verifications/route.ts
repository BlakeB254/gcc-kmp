/**
 * Spot Verifications History API
 * GET /api/spots/:id/verifications - Get verification history for a spot
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { spotVerifications } from '@/db/schema'
import { eq, desc } from 'drizzle-orm'
import { verifyMobileToken } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

export async function GET(request: NextRequest, context: RouteContext) {
  try {
    const authHeader = request.headers.get('authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return NextResponse.json({ data: null, success: false, message: 'Authorization required' }, { status: 401 })
    }
    const auth = await verifyMobileToken(authHeader.substring(7))
    if (!auth) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid token' }, { status: 401 })
    }

    const { id } = await context.params
    const spotId = parseInt(id, 10)
    if (isNaN(spotId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid spot ID' }, { status: 400 })
    }

    const verifications = await db
      .select()
      .from(spotVerifications)
      .where(eq(spotVerifications.reportId, spotId))
      .orderBy(desc(spotVerifications.createdAt))
      .limit(50)

    return NextResponse.json({ data: verifications, success: true, message: null })
  } catch (error) {
    console.error('Get verifications error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to fetch verifications' }, { status: 500 })
  }
}
