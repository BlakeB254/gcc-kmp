import { NextRequest, NextResponse } from 'next/server'
import { createToken } from '@/lib/auth'
import { resolveOAuthWorker } from '@/lib/oauth-workers'
import {
  clearOAuthStateCookie,
  exchangeGoogleCode,
  getBaseUrl,
  oauthErrorRedirect,
  readOAuthState,
  setWorkerCookie,
  verifyGoogleIdToken,
} from '@/lib/web-oauth'

export const runtime = 'nodejs'

export async function GET(request: NextRequest) {
  const code = request.nextUrl.searchParams.get('code')
  const receivedState = request.nextUrl.searchParams.get('state')
  const providerError = request.nextUrl.searchParams.get('error')

  if (providerError) return oauthErrorRedirect(request, 'google_cancelled')
  if (!code) return oauthErrorRedirect(request, 'missing_code')

  try {
    const state = await readOAuthState(request, 'google', receivedState)
    const idToken = await exchangeGoogleCode(request, code)
    const profile = await verifyGoogleIdToken(idToken, state.nonce)
    const worker = await resolveOAuthWorker({
      provider: 'google',
      providerUserId: profile.providerUserId,
      email: profile.email,
      emailVerified: profile.emailVerified,
      name: profile.name,
    })

    const token = await createToken({
      id: worker.id,
      email: worker.email,
      type: 'worker',
    })

    const response = NextResponse.redirect(new URL(state.returnTo, getBaseUrl(request)))
    setWorkerCookie(response, token)
    clearOAuthStateCookie(response)
    return response
  } catch (error) {
    console.error('[web-google-oauth] callback failed:', error)
    return oauthErrorRedirect(request, 'google_failed')
  }
}
