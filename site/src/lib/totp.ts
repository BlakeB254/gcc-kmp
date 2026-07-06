/**
 * TOTP (RFC 6238) + recovery codes for two-factor authentication.
 *
 * Implemented with node:crypto so we don't take on an OTP dependency.
 * Parameters match every mainstream authenticator app's defaults:
 * HMAC-SHA1, 6 digits, 30-second step. Verified against the RFC 6238
 * Appendix B test vectors (see scripts/verify-totp-vectors.mjs).
 */
import { createHmac, createHash, randomBytes, randomInt, timingSafeEqual } from 'crypto'

const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
const TIME_STEP_MS = 30_000
export const TOTP_ISSUER = 'Global Community Cleaners'

export function base32Encode(buf: Buffer): string {
  let bits = 0
  let value = 0
  let output = ''
  for (const byte of buf) {
    value = (value << 8) | byte
    bits += 8
    while (bits >= 5) {
      output += BASE32_ALPHABET[(value >>> (bits - 5)) & 31]
      bits -= 5
    }
  }
  if (bits > 0) output += BASE32_ALPHABET[(value << (5 - bits)) & 31]
  return output
}

export function base32Decode(encoded: string): Buffer {
  const clean = encoded.toUpperCase().replace(/[^A-Z2-7]/g, '')
  let bits = 0
  let value = 0
  const out: number[] = []
  for (const char of clean) {
    value = (value << 5) | BASE32_ALPHABET.indexOf(char)
    bits += 5
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff)
      bits -= 8
    }
  }
  return Buffer.from(out)
}

/** 160-bit random secret, base32-encoded (the format authenticator apps expect). */
export function generateTotpSecret(): string {
  return base32Encode(randomBytes(20))
}

function hotp(secret: string, counter: number): string {
  const counterBuf = Buffer.alloc(8)
  counterBuf.writeBigUInt64BE(BigInt(counter))
  const digest = createHmac('sha1', base32Decode(secret)).update(counterBuf).digest()
  const offset = digest[digest.length - 1] & 0x0f
  const binCode =
    ((digest[offset] & 0x7f) << 24) |
    (digest[offset + 1] << 16) |
    (digest[offset + 2] << 8) |
    digest[offset + 3]
  return String(binCode % 1_000_000).padStart(6, '0')
}

function safeEqual(a: string, b: string): boolean {
  const bufA = Buffer.from(a)
  const bufB = Buffer.from(b)
  return bufA.length === bufB.length && timingSafeEqual(bufA, bufB)
}

/**
 * Accepts the code for the current 30s step or +-`window` adjacent steps
 * (tolerates clock skew between phone and server).
 */
export function verifyTotpCode(
  secret: string,
  code: string,
  window = 1,
  nowMs = Date.now(),
): boolean {
  const normalized = code.replace(/\s/g, '')
  if (!/^\d{6}$/.test(normalized)) return false
  const step = Math.floor(nowMs / TIME_STEP_MS)
  for (let i = -window; i <= window; i++) {
    if (safeEqual(hotp(secret, step + i), normalized)) return true
  }
  return false
}

/** otpauth:// URI — iOS hands this straight to the installed authenticator app. */
export function buildOtpauthUri(secret: string, accountEmail: string): string {
  const issuer = encodeURIComponent(TOTP_ISSUER)
  const label = `${issuer}:${encodeURIComponent(accountEmail)}`
  return `otpauth://totp/${label}?secret=${secret}&issuer=${issuer}&algorithm=SHA1&digits=6&period=30`
}

// --- Recovery codes -------------------------------------------------------

const RECOVERY_CODE_COUNT = 10
// No 0/O/1/I — recovery codes get read back from paper.
const RECOVERY_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'

/** Uppercase alphanumerics only — "ab3d-e2f9" and "AB3D E2F9" hash identically. */
export function normalizeRecoveryCode(code: string): string {
  return code.toUpperCase().replace(/[^A-Z0-9]/g, '')
}

export function hashRecoveryCode(code: string): string {
  return createHash('sha256').update(normalizeRecoveryCode(code)).digest('hex')
}

/** 10 codes shaped XXXX-XXXX; plaintext is shown to the user exactly once. */
export function generateRecoveryCodes(): { plain: string[]; hashes: string[] } {
  const plain: string[] = []
  for (let i = 0; i < RECOVERY_CODE_COUNT; i++) {
    let code = ''
    for (let j = 0; j < 8; j++) {
      code += RECOVERY_ALPHABET[randomInt(RECOVERY_ALPHABET.length)]
      if (j === 3) code += '-'
    }
    plain.push(code)
  }
  return { plain, hashes: plain.map(hashRecoveryCode) }
}

/** Returns the remaining hashes with the matched code consumed, or null if no match. */
export function consumeRecoveryCode(code: string, hashes: string[]): string[] | null {
  const hashed = hashRecoveryCode(code)
  const index = hashes.findIndex((h) => safeEqual(h, hashed))
  if (index === -1) return null
  return hashes.filter((_, i) => i !== index)
}
