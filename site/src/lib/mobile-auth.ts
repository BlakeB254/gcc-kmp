import crypto from 'crypto'
import { SignJWT, jwtVerify } from 'jose'

const JWT_SECRET = new TextEncoder().encode(
  process.env.JWT_SECRET || 'development-secret-change-in-production'
)
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

export function hashPassword(password: string): { hash: string; salt: string } {
  const salt = crypto.randomBytes(16).toString('hex')
  const hash = crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex')
  return { hash, salt }
}

export function verifyPassword(password: string, hash: string, salt: string): boolean {
  const verifyHash = crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex')
  return hash === verifyHash
}
