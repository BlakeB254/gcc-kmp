import { NextRequest, NextResponse } from 'next/server'
import { createToken } from '@/lib/auth'
import { resolveOAuthWorker } from '@/lib/oauth-workers'
import {
  clearOAuthStateCookie,
  exchangeAppleCode,
  getBaseUrl,
  oauthErrorRedirect,
  readOAuthState,
  setWorkerCookie,
  verifyAppleIdToken,
} from '@/lib/web-oauth'

export const runtime = 'nodejs'

function appleName(userJson: string | null) {
  if (!userJson) return null
  try {
    const user = JSON.parse(userJson) as {
      name?: { firstName?: string; lastName?: string }
    }
    return [user.name?.firstName, user.name?.lastName].filter(Boolean).join(' ') || null
  } catch {
    return null
  }
}

async function handleCallback(
  request: NextRequest,
  params: {
    code: string | null
    state: string | null
    error: string | null
    user: string | null
  },
) {
  if (params.error) return oauthErrorRedirect(request, 'apple_cancelled')
  if (!params.code) return oauthErrorRedirect(request, 'missing_code')

  try {
    const state = await readOAuthState(request, 'apple', params.state)
    const idToken = await exchangeAppleCode(request, params.code)
    const profile = await verifyAppleIdToken(idToken, state.nonce)
    const worker = await resolveOAuthWorker({
      provider: 'apple',
      providerUserId: profile.providerUserId,
      email: profile.email,
      emailVerified: profile.emailVerified,
      name: appleName(params.user),
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
    console.error('[web-apple-oauth] callback failed:', error)
    return oauthErrorRedirect(request, 'apple_failed')
  }
}

export async function POST(request: NextRequest) {
  const form = await request.formData()
  const code = form.get('code')
  const state = form.get('state')
  const error = form.get('error')
  const user = form.get('user')
  return handleCallback(request, {
    code: typeof code === 'string' ? code : null,
    state: typeof state === 'string' ? state : null,
    error: typeof error === 'string' ? error : null,
    user: typeof user === 'string' ? user : null,
  })
}

export async function GET(request: NextRequest) {
  return handleCallback(request, {
    code: request.nextUrl.searchParams.get('code'),
    state: request.nextUrl.searchParams.get('state'),
    error: request.nextUrl.searchParams.get('error'),
    user: request.nextUrl.searchParams.get('user'),
  })
}
