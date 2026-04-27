/**
 * POST /api/auth/logout
 *
 * Clears every session cookie this app issues — worker-token, sponsor-token —
 * in one call. Mobile clients (Bearer-token-based) handle their own session
 * lifecycle and don't need this endpoint.
 */
import { NextResponse } from 'next/server'

export const runtime = 'nodejs'

export async function POST() {
  const response = NextResponse.json({ success: true })
  response.cookies.delete('worker-token')
  response.cookies.delete('sponsor-token')
  return response
}
