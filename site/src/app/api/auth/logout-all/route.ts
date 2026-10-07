/**
 * POST /api/auth/logout-all
 *
 * Signs the worker out everywhere: bumps workers.token_version, which makes
 * every session JWT minted before now fail verifyMobileToken (the calling
 * device's token included). Also clears this browser's session cookies.
 *
 * Auth: Bearer session token (requireMobileAuth).
 */
import { NextRequest, NextResponse } from 'next/server'
import { requireMobileAuth, bumpTokenVersion } from '@/lib/mobile-auth'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json(
      { data: null, success: false, message: 'Not authenticated' },
      { status: 401 },
    )
  }

  const version = await bumpTokenVersion(auth.workerId)
  if (version === null) {
    return NextResponse.json(
      { data: null, success: false, message: 'Worker not found' },
      { status: 404 },
    )
  }

  const response = NextResponse.json({ data: { ok: true }, success: true, message: 'Signed out of every device' })
  response.cookies.delete('worker-token')
  response.cookies.delete('sponsor-token')
  return response
}
