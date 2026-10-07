import crypto from 'crypto'
import { SignJWT, jwtVerify } from 'jose'
import { eq, sql } from 'drizzle-orm'
import { db } from '@/db'
import { workers } from '@/db/schema'

// Lazily read: a module-eval throw here failed every Vercel PREVIEW build
// (previews define no secrets; `next build` imports this module while
// collecting page data). Runtime behavior is unchanged — the first actual
// sign/verify without the env var throws the same error.
function jwtSecret(): Uint8Array {
  if (!process.env.JWT_SECRET) {
    throw new Error('JWT_SECRET environment variable must be set')
  }
  return new TextEncoder().encode(process.env.JWT_SECRET)
}
const JWT_ISSUER = process.env.JWT_ISSUER || 'gcc-api'
const JWT_AUDIENCE = process.env.JWT_AUDIENCE || 'gcc-app'
/**
 * 2FA challenge tokens get their own audience so no session verifier can ever
 * accept one (they used to share iss/aud with sessions and worked as a 5-minute
 * session, exchangeable at /api/auth/refresh for a 7-day one).
 */
const CHALLENGE_AUDIENCE = `${JWT_AUDIENCE}:2fa-challenge`
const TOKEN_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000 // 7 days

/** Read per call so tests (and a config change) never see a stale list. */
function adminEmails(): string[] {
  return (process.env.ADMIN_EMAILS || '')
    .split(',')
    .map((e) => e.trim().toLowerCase())
    .filter(Boolean)
}

export function isAdminEmail(email: string | null | undefined): boolean {
  return !!email && adminEmails().includes(email.toLowerCase())
}

/** The worker fields a session token is derived from. */
export interface SessionSubject {
  id: number
  email: string | null
  emailVerified: boolean | null
  tokenVersion: number | null
}

/**
 * Admin is granted only to a worker whose email is VERIFIED and listed in
 * ADMIN_EMAILS. An unverified row that merely claims an admin address (fresh
 * /api/auth/register, or an OAuth link without a verified email) is a worker.
 */
export function roleFor(subject: Pick<SessionSubject, 'email' | 'emailVerified'>): 'admin' | 'worker' {
  return subject.emailVerified === true && isAdminEmail(subject.email) ? 'admin' : 'worker'
}

/**
 * Create a session JWT (worker_id + role + tv claims). The mobile app and the
 * web admin panel parse `role` from it; `tv` pins the session to the worker's
 * current token_version so a bump revokes it.
 */
export async function createMobileToken(
  subject: SessionSubject
): Promise<{ token: string; exp: number }> {
  const role = roleFor(subject)
  const expiresAt = Math.floor((Date.now() + TOKEN_LIFETIME_MS) / 1000)

  const token = await new SignJWT({ worker_id: subject.id, role, tv: subject.tokenVersion ?? 0 })
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuer(JWT_ISSUER)
    .setAudience(JWT_AUDIENCE)
    .setExpirationTime(expiresAt)
    .sign(jwtSecret())

  return { token, exp: expiresAt }
}

/**
 * Verify a session JWT and return the worker it belongs to.
 *
 * Rejects (null): bad signature/iss/aud/expiry, any token carrying a `purpose`
 * claim (challenge, registration, …), a token missing the session claims, a
 * worker that no longer exists, and a `tv` that does not match the worker's
 * current token_version. Tokens minted before `tv` existed are read as version
 * 0, so they keep working until the worker's version is first bumped.
 *
 * The role is recomputed from the database row, not trusted from the token:
 * removing an address from ADMIN_EMAILS takes effect on the next request.
 *
 * A database error is thrown, not mapped to null — a DB blip must surface as a
 * 500, not as a 401 that makes the app log the user out.
 */
export async function verifyMobileToken(
  token: string
): Promise<{ workerId: number; role: string } | null> {
  let workerId: number
  let tokenVersion: number
  try {
    const { payload } = await jwtVerify(token, jwtSecret(), {
      issuer: JWT_ISSUER,
      audience: JWT_AUDIENCE,
      algorithms: ['HS256'],
    })
    if ('purpose' in payload) return null
    if (!Number.isInteger(payload.worker_id) || typeof payload.role !== 'string') return null
    const tv = payload.tv === undefined ? 0 : payload.tv
    if (!Number.isInteger(tv)) return null
    workerId = payload.worker_id as number
    tokenVersion = tv as number
  } catch {
    return null
  }

  const [row] = await db
    .select({
      email: workers.email,
      emailVerified: workers.emailVerified,
      tokenVersion: workers.tokenVersion,
    })
    .from(workers)
    .where(eq(workers.id, workerId))
    .limit(1)

  if (!row) return null
  if ((row.tokenVersion ?? 0) !== tokenVersion) return null
  return { workerId, role: roleFor(row) }
}

/**
 * Revoke every session JWT this worker holds (password change/reset,
 * logout-all). Returns the new version, or null when the worker is gone.
 */
export async function bumpTokenVersion(workerId: number): Promise<number | null> {
  const [row] = await db
    .update(workers)
    .set({ tokenVersion: sql`${workers.tokenVersion} + 1` })
    .where(eq(workers.id, workerId))
    .returning({ tokenVersion: workers.tokenVersion })
  return row ? row.tokenVersion : null
}

/** True when this worker must pass a TOTP/recovery code before any session. */
export function requiresSecondFactor(worker: {
  twoFactorEnabled: boolean | null
  totpSecret: string | null
}): boolean {
  return !!(worker.twoFactorEnabled && worker.totpSecret)
}

/**
 * Create a short-lived 2FA challenge token, returned by login when the
 * worker has two-factor enabled. Unlike the retired scaffold this carries
 * no expected code — the code is computed from the worker's TOTP secret
 * (or matched against recovery codes) at verify time. Its audience is
 * CHALLENGE_AUDIENCE, so it is never a session.
 */
export async function createTwoFactorChallengeToken(
  workerId: number,
  email: string
): Promise<string> {
  return new SignJWT({ worker_id: workerId, email, purpose: '2fa-challenge' })
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuer(JWT_ISSUER)
    .setAudience(CHALLENGE_AUDIENCE)
    .setExpirationTime('5m')
    .sign(jwtSecret())
}

export async function verifyTwoFactorChallengeToken(
  token: string
): Promise<{ workerId: number; email: string } | null> {
  try {
    const { payload } = await jwtVerify(token, jwtSecret(), {
      issuer: JWT_ISSUER,
      audience: CHALLENGE_AUDIENCE,
      algorithms: ['HS256'],
    })
    if (payload.purpose !== '2fa-challenge') return null
    if (!Number.isInteger(payload.worker_id)) return null
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
