/**
 * Phone Verification Utilities
 * In-memory store for development, use Redis in production
 */
import crypto from 'crypto'

interface VerificationData {
  code: string
  expiresAt: Date
  attempts: number
}

// In-memory store for verification codes
// In production, replace with Redis: new Map() -> Redis client
const verificationCodes = new Map<string, VerificationData>()

// Development test code (always works in dev mode)
const DEV_TEST_CODE = '123456'

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
export function generateVerificationCode(phone: string): {
  code: string
  isRateLimited: boolean
} {
  const normalizedPhone = normalizePhone(phone)
  const existing = verificationCodes.get(normalizedPhone)

  // Rate limit: max 5 requests per 10 minutes
  if (existing && existing.attempts >= 5 && existing.expiresAt > new Date()) {
    return { code: '', isRateLimited: true }
  }

  // Generate 6-digit code
  const code = crypto.randomInt(100000, 999999).toString()
  const expiresAt = new Date(Date.now() + 10 * 60 * 1000) // 10 minutes

  // Store code
  verificationCodes.set(normalizedPhone, {
    code,
    expiresAt,
    attempts: (existing?.attempts || 0) + 1,
  })

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
export function verifyCode(phone: string, code: string): {
  isValid: boolean
  error?: string
} {
  const normalizedPhone = normalizePhone(phone)

  // In development, accept test code
  if (process.env.NODE_ENV !== 'production' && code === DEV_TEST_CODE) {
    verificationCodes.delete(normalizedPhone)
    return { isValid: true }
  }

  const stored = verificationCodes.get(normalizedPhone)

  if (!stored) {
    return { isValid: false, error: 'No verification code found. Please request a new code.' }
  }

  if (stored.expiresAt < new Date()) {
    verificationCodes.delete(normalizedPhone)
    return { isValid: false, error: 'Code expired. Please request a new code.' }
  }

  if (stored.code !== code) {
    return { isValid: false, error: 'Invalid code. Please try again.' }
  }

  // Code is valid, remove it
  verificationCodes.delete(normalizedPhone)
  return { isValid: true }
}

/**
 * Clean up expired codes (call periodically)
 */
export function cleanupExpiredCodes(): void {
  const now = new Date()
  for (const [phone, data] of verificationCodes.entries()) {
    if (data.expiresAt < now) {
      verificationCodes.delete(phone)
    }
  }
}

// Auto-cleanup every minute
if (typeof setInterval !== 'undefined') {
  setInterval(cleanupExpiredCodes, 60000)
}
