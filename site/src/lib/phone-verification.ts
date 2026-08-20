/**
 * Phone Verification Utilities
 *
 * Codes are persisted in the `phone_verification_codes` table — the old
 * in-memory Map was both a security gap (no brute-force counter survived a
 * cold start) and functionally broken on serverless (a code requested on
 * instance A could not be verified on instance B). See security audit
 * 2026-08-20, findings F-02 / F-03.
 *
 * Protections:
 *   - verify: max MAX_FAILED_ATTEMPTS wrong guesses, then the code is
 *     invalidated and a new one must be requested.
 *   - resend: RESEND_COOLDOWN_SECONDS between sends, plus
 *     MAX_SENDS_PER_WINDOW per SEND_WINDOW_SECONDS per phone.
 */
import crypto from 'crypto'
import { sql } from 'drizzle-orm'
import { SignJWT, jwtVerify } from 'jose'
import { db } from '@/db'

// Lazily read — module-eval env throws fail Vercel PREVIEW builds (no
// secrets there); see src/lib/mobile-auth.ts for the same pattern.
function jwtSecret(): Uint8Array {
  if (!process.env.JWT_SECRET) {
    throw new Error('JWT_SECRET environment variable must be set')
  }
  return new TextEncoder().encode(process.env.JWT_SECRET)
}

interface StoredCode {
  code: string
  expires_at: string
  failed_attempts: number
  send_count: number
  window_started_at: string
  last_sent_at: string | null
}

// Development test code (always works in dev mode)
const DEV_TEST_CODE = '123456'

const CODE_TTL_SECONDS = 10 * 60 // 10 minutes
const MAX_FAILED_ATTEMPTS = 5
const RESEND_COOLDOWN_SECONDS = 60
const SEND_WINDOW_SECONDS = 10 * 60 // 10 minutes
const MAX_SENDS_PER_WINDOW = 5

/** Rows come back as a bare array on some drivers and `{ rows }` on others. */
function rowsOf(res: unknown): StoredCode[] {
  if (Array.isArray(res)) return res as StoredCode[]
  const rows = (res as { rows?: unknown[] } | null)?.rows
  return Array.isArray(rows) ? (rows as StoredCode[]) : []
}

/**
 * Normalize a phone number to E.164 format
 */
export function normalizePhone(phone: string): string {
  const digits = phone.replace(/\D/g, '')
  return digits.startsWith('1') ? `+${digits}` : `+1${digits}`
}

/**
 * Generate and store a verification code
 * Returns the code for sending via SMS
 */
export async function generateVerificationCode(phone: string): Promise<{
  code: string
  isRateLimited: boolean
}> {
  const normalizedPhone = normalizePhone(phone)
  const existing = rowsOf(
    await db.execute(sql`
      select code, expires_at, failed_attempts, send_count, window_started_at, last_sent_at
      from phone_verification_codes
      where phone = ${normalizedPhone}
    `),
  )[0]

  const now = Date.now()
  const windowStartedAt = existing ? new Date(existing.window_started_at).getTime() : 0
  const inSendWindow = now - windowStartedAt < SEND_WINDOW_SECONDS * 1000

  // Per-phone send quota: max MAX_SENDS_PER_WINDOW per SEND_WINDOW_SECONDS
  if (existing && inSendWindow && existing.send_count >= MAX_SENDS_PER_WINDOW) {
    return { code: '', isRateLimited: true }
  }

  // Resend cooldown: don't let one phone get spammed with back-to-back codes
  const lastSentAt = existing?.last_sent_at ? new Date(existing.last_sent_at).getTime() : 0
  if (now - lastSentAt < RESEND_COOLDOWN_SECONDS * 1000) {
    return { code: '', isRateLimited: true }
  }

  // Generate 6-digit code
  const code = crypto.randomInt(100000, 999999).toString()
  const expiresAt = new Date(now + CODE_TTL_SECONDS * 1000).toISOString()

  const resetWindow = !existing || !inSendWindow
  const sendCount = resetWindow ? 1 : existing.send_count + 1
  const windowStart = resetWindow
    ? new Date(now).toISOString()
    : new Date(windowStartedAt).toISOString()

  await db.execute(sql`
    insert into phone_verification_codes
      (phone, code, expires_at, failed_attempts, send_count, window_started_at, last_sent_at)
    values (
      ${normalizedPhone}, ${code}, ${expiresAt}, 0, ${sendCount}, ${windowStart}, ${new Date(now).toISOString()}
    )
    on conflict (phone) do update set
      code = excluded.code,
      expires_at = excluded.expires_at,
      failed_attempts = 0,
      send_count = excluded.send_count,
      window_started_at = excluded.window_started_at,
      last_sent_at = excluded.last_sent_at
  `)

  // Log in development
  if (process.env.NODE_ENV !== 'production') {
    console.log(`\n📱 Phone Verification Code`)
    console.log(`   Phone: ${normalizedPhone}`)
    console.log(`   Code:  ${code}`)
    console.log(`   Dev test code always works: ${DEV_TEST_CODE}\n`)
  }

  return { code, isRateLimited: false }
}

/**
 * Verify a code for a phone number
 */
export async function verifyCode(phone: string, code: string): Promise<{
  isValid: boolean
  error?: string
}> {
  const normalizedPhone = normalizePhone(phone)

  // In development, accept test code
  if (process.env.NODE_ENV !== 'production' && code === DEV_TEST_CODE) {
    await db.execute(sql`delete from phone_verification_codes where phone = ${normalizedPhone}`)
    return { isValid: true }
  }

  const stored = rowsOf(
    await db.execute(sql`
      select code, expires_at, failed_attempts, send_count, window_started_at, last_sent_at
      from phone_verification_codes
      where phone = ${normalizedPhone}
    `),
  )[0]

  if (!stored) {
    return { isValid: false, error: 'No verification code found. Please request a new code.' }
  }

  if (new Date(stored.expires_at) < new Date()) {
    await db.execute(sql`delete from phone_verification_codes where phone = ${normalizedPhone}`)
    return { isValid: false, error: 'Code expired. Please request a new code.' }
  }

  if (stored.code !== code) {
    const failedAttempts = Number(stored.failed_attempts) + 1
    if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
      // Too many wrong guesses — burn the code so an attacker can't keep
      // grinding through the 10^6 space inside the TTL window.
      await db.execute(sql`delete from phone_verification_codes where phone = ${normalizedPhone}`)
      return {
        isValid: false,
        error: 'Too many incorrect attempts. Please request a new code.',
      }
    }
    await db.execute(sql`
      update phone_verification_codes
      set failed_attempts = ${failedAttempts}
      where phone = ${normalizedPhone}
    `)
    return { isValid: false, error: 'Invalid code. Please try again.' }
  }

  // Code is valid, remove it (single use)
  await db.execute(sql`delete from phone_verification_codes where phone = ${normalizedPhone}`)
  return { isValid: true }
}

/**
 * Signed, short-lived registration token (audit F-02). Issued by
 * verify-phone for numbers with no account yet; register-phone verifies the
 * signature, the expiry, and that the token is bound to the phone being
 * registered. Replaces the old unsigned base64 JSON, which anyone could mint.
 *
 * The `jti` is included for future single-use enforcement; today there is no
 * consumed-token table, so a token can be replayed until it expires
 * (≤ 15 minutes). That is accepted for now — replay only re-proves the same
 * phone verification, it can't be retargeted to another number.
 */
export async function createPhoneRegistrationToken(phone: string): Promise<string> {
  return new SignJWT({ phone, purpose: 'phone-registration' })
    .setProtectedHeader({ alg: 'HS256' })
    .setJti(crypto.randomUUID())
    .setIssuedAt()
    .setExpirationTime('15m')
    .sign(jwtSecret())
}

export async function verifyPhoneRegistrationToken(
  token: string,
  phone: string,
): Promise<{ phone: string } | null> {
  try {
    const { payload } = await jwtVerify(token, jwtSecret())
    if (payload.purpose !== 'phone-registration') return null
    if (payload.phone !== phone) return null
    return { phone: payload.phone as string }
  } catch {
    return null
  }
}
