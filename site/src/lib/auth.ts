import crypto from 'crypto'
import { SignJWT, jwtVerify } from 'jose'

// Lazily read — module-eval env throws fail Vercel PREVIEW builds (no
// secrets there); see src/lib/mobile-auth.ts for the same fix.
function jwtSecret(): Uint8Array {
  if (!process.env.JWT_SECRET) {
    throw new Error('JWT_SECRET environment variable must be set')
  }
  return new TextEncoder().encode(process.env.JWT_SECRET)
}

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

/** Current token_version for a worker (0 when the row is missing). */
async function workerTokenVersion(id: number): Promise<number | null> {
  const { db } = await import('@/db')
  const { workers } = await import('@/db/schema')
  const { eq } = await import('drizzle-orm')
  const [row] = await db
    .select({ tokenVersion: workers.tokenVersion })
    .from(workers)
    .where(eq(workers.id, id))
    .limit(1)
  return row ? row.tokenVersion ?? 0 : null
}

/**
 * Create a JWT token. Worker cookies carry the worker's token_version (`tv`)
 * so change-password, reset-password and logout-all end web sessions too.
 */
export async function createToken(payload: { id: number; email: string; type: 'worker' | 'sponsor' }): Promise<string> {
  const claims: Record<string, unknown> = { ...payload }
  if (payload.type === 'worker') {
    claims.tv = (await workerTokenVersion(payload.id)) ?? 0
  }
  return new SignJWT(claims)
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuedAt()
    .setExpirationTime('90d')
    .sign(jwtSecret())
}

/**
 * Verify and decode a JWT token. A worker cookie is rejected when its `tv`
 * no longer matches the worker's token_version (a token without `tv` counts
 * as version 0, so cookies issued before versioning stay valid until the
 * first bump) or when the worker no longer exists.
 */
export async function verifyToken(token: string): Promise<{ id: number; email: string; type: string } | null> {
  try {
    const { payload } = await jwtVerify(token, jwtSecret(), { algorithms: ['HS256'] })
    const claims = payload as { id: number; email: string; type: string; tv?: unknown }
    if (claims.type === 'worker') {
      const current = await workerTokenVersion(claims.id)
      const presented = typeof claims.tv === 'number' ? claims.tv : 0
      if (current === null || current !== presented) return null
    }
    return { id: claims.id, email: claims.email, type: claims.type }
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
