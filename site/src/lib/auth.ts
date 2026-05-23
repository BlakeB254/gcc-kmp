import crypto from 'crypto'
import { SignJWT, jwtVerify } from 'jose'

if (!process.env.JWT_SECRET) {
  throw new Error('JWT_SECRET environment variable must be set')
}
const JWT_SECRET = new TextEncoder().encode(process.env.JWT_SECRET)

/**
 * Hash a password using PBKDF2
 */
export function hashPassword(password: string): { hash: string; salt: string } {
  const salt = crypto.randomBytes(16).toString('hex')
  const hash = crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex')
  return { hash, salt }
}

/**
 * Verify a password against stored hash
 */
export function verifyPassword(password: string, hash: string, salt: string): boolean {
  const verifyHash = crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex')
  return hash === verifyHash
}

/**
 * Create a JWT token
 */
export async function createToken(payload: { id: number; email: string; type: 'worker' | 'sponsor' }): Promise<string> {
  return new SignJWT(payload)
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuedAt()
    .setExpirationTime('90d')
    .sign(JWT_SECRET)
}

/**
 * Verify and decode a JWT token
 */
export async function verifyToken(token: string): Promise<{ id: number; email: string; type: string } | null> {
  try {
    const { payload } = await jwtVerify(token, JWT_SECRET)
    return payload as { id: number; email: string; type: string }
  } catch {
    return null
  }
}

/**
 * Generate a random session token
 */
export function generateSessionToken(): string {
  return crypto.randomBytes(32).toString('hex')
}

/**
 * Verify a token and return the worker record
 */
export async function verifyWorkerToken(token: string): Promise<{ id: number; name: string; email: string } | null> {
  try {
    const payload = await verifyToken(token)
    if (!payload || payload.type !== 'worker') {
      return null
    }

    // Import db lazily to avoid circular dependencies
    const { db } = await import('@/db')
    const { workers } = await import('@/db/schema')
    const { eq } = await import('drizzle-orm')

    const [worker] = await db
      .select({ id: workers.id, name: workers.name, email: workers.email })
      .from(workers)
      .where(eq(workers.id, payload.id))
      .limit(1)

    return worker || null
  } catch {
    return null
  }
}
