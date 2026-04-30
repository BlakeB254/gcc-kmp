// HMAC-signed unsubscribe tokens. Stateless — no DB lookup needed to verify.
// Format: base64url(payload).base64url(signature) where payload = email|expiresAt
//
// We use JWT_SECRET as the HMAC key (already a required env var).

import crypto from 'crypto'

const TTL_DAYS = 365 * 5 // 5 years — unsubscribe links should not expire quickly

function b64url(buf: Buffer | string): string {
  return Buffer.from(buf).toString('base64').replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
}
function b64urlDecode(s: string): Buffer {
  const padded = s.replaceAll('-', '+').replaceAll('_', '/').padEnd(Math.ceil(s.length / 4) * 4, '=')
  return Buffer.from(padded, 'base64')
}

function hmacKey(): Buffer {
  const secret = process.env.JWT_SECRET
  if (!secret) throw new Error('JWT_SECRET not configured')
  return Buffer.from(secret)
}

export function makeUnsubscribeToken(email: string): string {
  const expiresAt = Date.now() + TTL_DAYS * 24 * 60 * 60 * 1000
  const payload = `${email.toLowerCase().trim()}|${expiresAt}`
  const sig = crypto.createHmac('sha256', hmacKey()).update(payload).digest()
  return `${b64url(payload)}.${b64url(sig)}`
}

export function verifyUnsubscribeToken(token: string):
  | { valid: true; email: string }
  | { valid: false; reason: string }
{
  if (!token || typeof token !== 'string' || !token.includes('.')) {
    return { valid: false, reason: 'Malformed token' }
  }
  const [payloadB64, sigB64] = token.split('.')
  let payload: string, expectedSig: Buffer
  try {
    payload = b64urlDecode(payloadB64).toString('utf8')
    expectedSig = b64urlDecode(sigB64)
  } catch {
    return { valid: false, reason: 'Malformed token' }
  }
  const computedSig = crypto.createHmac('sha256', hmacKey()).update(payload).digest()
  if (computedSig.length !== expectedSig.length || !crypto.timingSafeEqual(computedSig, expectedSig)) {
    return { valid: false, reason: 'Invalid signature' }
  }
  const [email, expiresAtStr] = payload.split('|')
  const expiresAt = Number(expiresAtStr)
  if (!email || !Number.isFinite(expiresAt)) {
    return { valid: false, reason: 'Malformed payload' }
  }
  if (Date.now() > expiresAt) {
    return { valid: false, reason: 'Token expired' }
  }
  return { valid: true, email }
}

export function unsubscribeUrl(email: string, baseUrl: string): string {
  return `${baseUrl}/unsubscribe?token=${encodeURIComponent(makeUnsubscribeToken(email))}`
}
