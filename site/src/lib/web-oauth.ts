import { NextRequest, NextResponse } from 'next/server'
import { createHash, randomBytes, timingSafeEqual } from 'crypto'
import {
  createRemoteJWKSet,
  importPKCS8,
  jwtVerify,
  JWTPayload,
  SignJWT,
} from 'jose'

export type OAuthProvider = 'google' | 'apple'
export type OAuthRole = 'worker'
export type OAuthMode = 'signin' | 'signup'

const STATE_COOKIE = 'gcc-oauth-state'
const GOOGLE_JWKS = createRemoteJWKSet(new URL('https://www.googleapis.com/oauth2/v3/certs'))
const APPLE_JWKS = createRemoteJWKSet(new URL('https://appleid.apple.com/auth/keys'))

const jwtSecret = new TextEncoder().encode(
  process.env.JWT_SECRET || 'development-secret-change-in-production',
)

interface OAuthStatePayload extends JWTPayload {
  state: string
  provider: OAuthProvider
  role: OAuthRole
  mode: OAuthMode
  returnTo: string
  nonce: string
}

export interface VerifiedProviderProfile {
  providerUserId: string
  email: string
  emailVerified: boolean
  name?: string | null
}

export function getBaseUrl(request: NextRequest) {
  if (
    request.nextUrl.hostname === 'localhost' ||
    request.nextUrl.hostname === '127.0.0.1'
  ) {
    return request.nextUrl.origin
  }

  const configured = process.env.NEXT_PUBLIC_APP_URL?.replace(/\/+$/, '')
  if (configured) return configured
  return request.nextUrl.origin
}

export function callbackUrl(request: NextRequest, provider: OAuthProvider) {
  return `${getBaseUrl(request)}/api/auth/web/callback/${provider}`
}

export function safeReturnTo(value: string | null) {
  if (!value || !value.startsWith('/') || value.startsWith('//')) return '/worker/dashboard'
  return value
}

export function oauthErrorRedirect(request: NextRequest, code: string, mode: OAuthMode = 'signin') {
  const path = mode === 'signup' ? '/worker/signup' : '/login'
  const url = new URL(path, getBaseUrl(request))
  url.searchParams.set('oauthError', code)
  return NextResponse.redirect(url)
}

export async function createOAuthState(
  provider: OAuthProvider,
  role: OAuthRole,
  mode: OAuthMode,
  returnTo: string,
) {
  const state = randomBytes(32).toString('base64url')
  const nonce = randomBytes(32).toString('base64url')
  const token = await new SignJWT({
    state,
    provider,
    role,
    mode,
    returnTo: safeReturnTo(returnTo),
    nonce,
  })
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuedAt()
    .setExpirationTime('10m')
    .sign(jwtSecret)

  return { state, nonce, token }
}

export function setOAuthStateCookie(response: NextResponse, token: string) {
  response.cookies.set(STATE_COOKIE, token, {
    httpOnly: true,
    secure: process.env.NODE_ENV === 'production',
    sameSite: 'lax',
    path: '/',
    maxAge: 10 * 60,
  })
}

export function clearOAuthStateCookie(response: NextResponse) {
  response.cookies.set(STATE_COOKIE, '', {
    httpOnly: true,
    secure: process.env.NODE_ENV === 'production',
    sameSite: 'lax',
    path: '/',
    maxAge: 0,
  })
}

export async function readOAuthState(
  request: NextRequest,
  provider: OAuthProvider,
  receivedState: string | null,
) {
  const token = request.cookies.get(STATE_COOKIE)?.value
  if (!token || !receivedState) throw new Error('Missing OAuth state')

  const { payload } = await jwtVerify(token, jwtSecret)
  const state = payload as OAuthStatePayload
  if (state.provider !== provider || state.role !== 'worker') {
    throw new Error('OAuth state provider mismatch')
  }

  if (!timingSafeStringEqual(state.state, receivedState)) {
    throw new Error('OAuth state mismatch')
  }

  return state
}

function timingSafeStringEqual(actual: string, expected: string) {
  const actualBuffer = Buffer.from(actual)
  const expectedBuffer = Buffer.from(expected)
  return actualBuffer.length === expectedBuffer.length &&
    timingSafeEqual(actualBuffer, expectedBuffer)
}

function sha256Hex(value: string) {
  return createHash('sha256').update(value, 'utf8').digest('hex')
}

export async function exchangeGoogleCode(request: NextRequest, code: string) {
  const clientId = process.env.GOOGLE_WEB_CLIENT_ID
  const clientSecret = process.env.GOOGLE_WEB_CLIENT_SECRET
  if (!clientId || !clientSecret) throw new Error('Google web OAuth is not configured')

  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      client_id: clientId,
      client_secret: clientSecret,
      code,
      grant_type: 'authorization_code',
      redirect_uri: callbackUrl(request, 'google'),
    }),
  })

  if (!response.ok) {
    throw new Error(`Google token exchange failed: ${response.status}`)
  }

  const data = await response.json() as { id_token?: string }
  if (!data.id_token) throw new Error('Google did not return an ID token')
  return data.id_token
}

export async function verifyGoogleIdToken(idToken: string, nonce: string): Promise<VerifiedProviderProfile> {
  const clientId = process.env.GOOGLE_WEB_CLIENT_ID
  if (!clientId) throw new Error('Google web OAuth is not configured')

  const { payload } = await jwtVerify(idToken, GOOGLE_JWKS, {
    issuer: ['https://accounts.google.com', 'accounts.google.com'],
    audience: clientId,
    clockTolerance: '5s',
  })

  if (payload.nonce !== nonce) throw new Error('Invalid Google OAuth nonce')

  return {
    providerUserId: String(payload.sub || ''),
    email: String(payload.email || ''),
    emailVerified: payload.email_verified === true || payload.email_verified === 'true',
    name: typeof payload.name === 'string' ? payload.name : null,
  }
}

async function appleClientSecret() {
  const teamId = process.env.APPLE_TEAM_ID
  const keyId = process.env.APPLE_KEY_ID
  const clientId = process.env.APPLE_WEB_CLIENT_ID
  const privateKey = process.env.APPLE_PRIVATE_KEY?.replace(/\\n/g, '\n')

  if (!teamId || !keyId || !clientId || !privateKey) {
    throw new Error('Apple web OAuth is not configured')
  }

  const key = await importPKCS8(privateKey, 'ES256')
  return new SignJWT({})
    .setProtectedHeader({ alg: 'ES256', kid: keyId })
    .setIssuer(teamId)
    .setAudience('https://appleid.apple.com')
    .setSubject(clientId)
    .setIssuedAt()
    .setExpirationTime('180d')
    .sign(key)
}

export async function exchangeAppleCode(request: NextRequest, code: string) {
  const clientId = process.env.APPLE_WEB_CLIENT_ID
  if (!clientId) throw new Error('Apple web OAuth is not configured')

  const response = await fetch('https://appleid.apple.com/auth/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      client_id: clientId,
      client_secret: await appleClientSecret(),
      code,
      grant_type: 'authorization_code',
      redirect_uri: callbackUrl(request, 'apple'),
    }),
  })

  if (!response.ok) {
    throw new Error(`Apple token exchange failed: ${response.status}`)
  }

  const data = await response.json() as { id_token?: string }
  if (!data.id_token) throw new Error('Apple did not return an ID token')
  return data.id_token
}

export async function verifyAppleIdToken(idToken: string, nonce: string): Promise<VerifiedProviderProfile> {
  const clientId = process.env.APPLE_WEB_CLIENT_ID
  if (!clientId) throw new Error('Apple web OAuth is not configured')

  const { payload } = await jwtVerify(idToken, APPLE_JWKS, {
    issuer: 'https://appleid.apple.com',
    audience: clientId,
    clockTolerance: '5s',
  })

  const returnedNonce = payload.nonce
  if (
    returnedNonce !== nonce &&
    returnedNonce !== sha256Hex(nonce)
  ) {
    throw new Error('Invalid Apple OAuth nonce')
  }

  return {
    providerUserId: String(payload.sub || ''),
    email: String(payload.email || ''),
    emailVerified: payload.email_verified === true || payload.email_verified === 'true',
  }
}

export function setWorkerCookie(response: NextResponse, token: string) {
  response.cookies.set('worker-token', token, {
    httpOnly: true,
    secure: process.env.NODE_ENV === 'production',
    sameSite: 'lax',
    path: '/',
    maxAge: 60 * 60 * 24 * 90,
  })
}
