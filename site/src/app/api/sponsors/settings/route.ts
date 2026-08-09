import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { sponsors } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyToken } from '@/lib/auth'
import { commitPublicDataMutation } from '@/lib/public-data/invalidation'

export const runtime = 'nodejs'

// Helper to authenticate and get sponsor ID from JWT
async function getAuthenticatedSponsorId(request: NextRequest): Promise<number | null> {
  const token = request.cookies.get('sponsor-token')?.value
  if (!token) return null

  const payload = await verifyToken(token)
  if (!payload || payload.type !== 'sponsor') return null

  return payload.id
}

export async function PUT(request: NextRequest) {
  try {
    const sponsorId = await getAuthenticatedSponsorId(request)

    if (!sponsorId) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }

    const { frequency, isAnonymous, wantsUpdates, autoDonateEnabled } = await request.json()

    // Update sponsor preferences
    await commitPublicDataMutation('sponsorship', () => db
      .update(sponsors)
      .set({
        preferencesFrequency: frequency || 'one_time',
        preferencesIsAnonymous: isAnonymous || false,
        preferencesWantsUpdates: wantsUpdates !== false,
        activeSponsorship: autoDonateEnabled || false,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(sponsors.id, sponsorId)))

    return NextResponse.json({
      success: true,
      message: 'Settings updated successfully',
    })
  } catch (error) {
    console.error('Update sponsor settings error:', error)
    return NextResponse.json({ error: 'Failed to update settings' }, { status: 500 })
  }
}
