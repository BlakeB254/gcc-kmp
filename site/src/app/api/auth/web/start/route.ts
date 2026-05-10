import { NextRequest, NextResponse } from 'next/server'
import {
  callbackUrl,
  createOAuthState,
  oauthErrorRedirect,
  OAuthMode,
  OAuthProvider,
  safeReturnTo,
  setOAuthStateCookie,
} from '@/lib/web-oauth'

export const runtime = 'nodejs'

function parseProvider(value: string | null): OAuthProvider | null {
  return value === 'google' || value === 'apple' ? value : null
}

function parseMode(value: string | null): OAuthMode {
  return value === 'signup' ? 'signup' : 'signin'
}

export async function GET(request: NextRequest) {
  const provider = parseProvider(request.nextUrl.searchParams.get('provider'))
  const mode = parseMode(request.nextUrl.searchParams.get('mode'))
  const role = request.nextUrl.searchParams.get('role') || 'worker'
  if (!provider || role !== 'worker') return oauthErrorRedirect(request, 'invalid_request', mode)

  const { state, nonce, token } = await createOAuthState(
    provider,
    'worker',
    mode,
    safeReturnTo(request.nextUrl.searchParams.get('returnTo')),
  )

  let authorizationUrl: URL
  if (provider === 'google') {
    const clientId = process.env.GOOGLE_WEB_CLIENT_ID
    const clientSecret = process.env.GOOGLE_WEB_CLIENT_SECRET
    if (!clientId || !clientSecret) return oauthErrorRedirect(request, 'google_not_configured', mode)

    authorizationUrl = new URL('https://accounts.google.com/o/oauth2/v2/auth')
    authorizationUrl.searchParams.set('client_id', clientId)
    authorizationUrl.searchParams.set('redirect_uri', callbackUrl(request, 'google'))
    authorizationUrl.searchParams.set('response_type', 'code')
    authorizationUrl.searchParams.set('scope', 'openid email profile')
    authorizationUrl.searchParams.set('state', state)
    authorizationUrl.searchParams.set('nonce', nonce)
    authorizationUrl.searchParams.set('prompt', 'select_account')
  } else {
    const clientId = process.env.APPLE_WEB_CLIENT_ID
    const teamId = process.env.APPLE_TEAM_ID
    const keyId = process.env.APPLE_KEY_ID
    const privateKey = process.env.APPLE_PRIVATE_KEY
    if (!clientId || !teamId || !keyId || !privateKey) {
      return oauthErrorRedirect(request, 'apple_not_configured', mode)
    }

    authorizationUrl = new URL('https://appleid.apple.com/auth/authorize')
    authorizationUrl.searchParams.set('client_id', clientId)
    authorizationUrl.searchParams.set('redirect_uri', callbackUrl(request, 'apple'))
    authorizationUrl.searchParams.set('response_type', 'code id_token')
    authorizationUrl.searchParams.set('response_mode', 'form_post')
    authorizationUrl.searchParams.set('scope', 'name email')
    authorizationUrl.searchParams.set('state', state)
    authorizationUrl.searchParams.set('nonce', nonce)
  }

  const response = NextResponse.redirect(authorizationUrl)
  setOAuthStateCookie(response, token)
  return response
}
