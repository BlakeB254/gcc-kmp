/**
 * Shared CRON_SECRET guard for /api/cron/* handlers.
 *
 * Fails closed (503) when CRON_SECRET is unset, and compares the presented
 * `Authorization: Bearer …` header in constant time: both sides are SHA-256
 * hashed first, so crypto.timingSafeEqual always sees equal-length buffers and
 * neither the secret's content nor its length leaks through response timing.
 */
import crypto from 'crypto'
import { NextResponse } from 'next/server'

function digest(value: string): Buffer {
  return crypto.createHash('sha256').update(value, 'utf8').digest()
}

/** Constant-time string equality (length-independent). */
export function constantTimeEqual(a: string, b: string): boolean {
  return crypto.timingSafeEqual(digest(a), digest(b))
}

/** Returns an error response when the request is not the cron caller, else null. */
export function cronAuthFailure(
  request: Request,
  secret: string | undefined = process.env.CRON_SECRET,
): NextResponse | null {
  if (!secret) {
    return NextResponse.json({ error: 'CRON_SECRET not configured', success: false }, { status: 503 })
  }
  const presented = request.headers.get('authorization') ?? ''
  if (!constantTimeEqual(presented, `Bearer ${secret}`)) {
    return NextResponse.json({ error: 'Unauthorized', success: false }, { status: 401 })
  }
  return null
}
