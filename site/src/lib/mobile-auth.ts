import crypto from 'crypto'
import { SignJWT, jwtVerify } from 'jose'

if (!process.env.JWT_SECRET) {
  throw new Error('JWT_SECRET environment variable must be set')
}
const JWT_SECRET = new TextEncoder().encode(process.env.JWT_SECRET)
const JWT_ISSUER = process.env.JWT_ISSUER || 'gcc-api'
const JWT_AUDIENCE = process.env.JWT_AUDIENCE || 'gcc-app'
const TOKEN_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000 // 7 days

const ADMIN_EMAILS = (process.env.ADMIN_EMAILS || '')
  .split(',')
  .map((e) => e.trim().toLowerCase())
  .filter(Boolean)

/**
 * Create a JWT token matching the Ktor server format (worker_id + role claims).
 * The mobile app parses these claims from the JWT.
 */
export async function createMobileToken(
  workerId: number,
  email: string
): Promise<{ token: string; exp: number }> {
  const role = ADMIN_EMAILS.includes(email.toLowerCase()) ? 'admin' : 'worker'
  const expiresAt = Math.floor((Date.now() + TOKEN_LIFETIME_MS) / 1000)

  const token = await new SignJWT({ worker_id: workerId, role })
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuer(JWT_ISSUER)
    .setAudience(JWT_AUDIENCE)
    .setExpirationTime(expiresAt)
    .sign(JWT_SECRET)

  return { token, exp: expiresAt }
}

/**
 * Verify a mobile JWT and extract the worker_id.
 */
export async function verifyMobileToken(
  token: string
): Promise<{ workerId: number; role: string } | null> {
  try {
    const { payload } = await jwtVerify(token, JWT_SECRET, {
      issuer: JWT_ISSUER,
      audience: JWT_AUDIENCE,
    })
    return {
      workerId: payload.worker_id as number,
      role: payload.role as string,
    }
  } catch {
    return null
  }
}

/**
 * Create a short-lived 2FA challenge token, returned by login when the
 * worker has two-factor enabled. Unlike the retired scaffold this carries
 * no expected code — the code is computed from the worker's TOTP secret
 * (or matched against recovery codes) at verify time.
 */
export async function createTwoFactorChallengeToken(
  workerId: number,
  email: string
): Promise<string> {
  return new SignJWT({ worker_id: workerId, email, purpose: '2fa-challenge' })
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuer(JWT_ISSUER)
    .setAudience(JWT_AUDIENCE)
    .setExpirationTime('5m')
    .sign(JWT_SECRET)
}

export async function verifyTwoFactorChallengeToken(
  token: string
): Promise<{ workerId: number; email: string } | null> {
  try {
    const { payload } = await jwtVerify(token, JWT_SECRET, {
      issuer: JWT_ISSUER,
      audience: JWT_AUDIENCE,
    })
    if (payload.purpose !== '2fa-challenge') return null
    return { workerId: payload.worker_id as number, email: payload.email as string }
  } catch {
    return null
  }
}

export function hashPassword(password: string): { hash: string; salt: string } {
  const salt = crypto.randomBytes(16).toString('hex')
  const hash = crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex')
  return { hash, salt }
}

export function verifyPassword(password: string, hash: string, salt: string): boolean {
  const verifyHash = crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex')
  return hash === verifyHash
}

/**
 * Extract and verify Bearer token from request.
 * Returns { workerId, role } or null if auth fails.
 * Use in API routes: `const auth = await requireMobileAuth(request); if (!auth) return ...`
 */
export async function requireMobileAuth(
  request: Request
): Promise<{ workerId: number; role: string } | null> {
  const authHeader = request.headers.get('authorization')
  if (!authHeader?.startsWith('Bearer ')) return null
  return verifyMobileToken(authHeader.substring(7))
}
