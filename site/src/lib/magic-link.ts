/**
 * Magic Link Authentication
 *
 * Sends a one-time login link via email instead of SMS codes.
 * More reliable than carrier SMS gateways.
 */

import crypto from 'crypto'
import { Resend } from 'resend'
import { appUrl } from '@/lib/env'

// Defer Resend instantiation until first use so a missing key doesn't crash
// at module-eval time (e.g. during `next build` page-data collection).
let resendClient: Resend | null = null
function getResend(): Resend | null {
  if (resendClient) return resendClient
  const key = process.env.RESEND_API_KEY
  if (!key) return null
  resendClient = new Resend(key)
  return resendClient
}

interface MagicLinkData {
  token: string
  email: string
  expiresAt: Date
  used: boolean
}

// In-memory store for magic links (use Redis in production)
const magicLinks = new Map<string, MagicLinkData>()

// Token expiration: 15 minutes
const TOKEN_EXPIRY_MS = 15 * 60 * 1000

/**
 * Generate a secure magic link token
 */
function generateToken(): string {
  return crypto.randomBytes(32).toString('hex')
}

/**
 * Create and store a magic link for an email
 */
export function createMagicLink(email: string): {
  token: string
  isRateLimited: boolean
} {
  const normalizedEmail = email.toLowerCase().trim()

  // Check rate limiting (max 5 requests per 10 minutes per email)
  const recentLinks = Array.from(magicLinks.values()).filter(
    link => link.email === normalizedEmail &&
    link.expiresAt > new Date(Date.now() - 10 * 60 * 1000)
  )

  if (recentLinks.length >= 5) {
    return { token: '', isRateLimited: true }
  }

  const token = generateToken()
  const expiresAt = new Date(Date.now() + TOKEN_EXPIRY_MS)

  magicLinks.set(token, {
    token,
    email: normalizedEmail,
    expiresAt,
    used: false,
  })

  return { token, isRateLimited: false }
}

/**
 * Verify a magic link token
 */
export function verifyMagicLink(token: string): {
  isValid: boolean
  email?: string
  error?: string
} {
  const link = magicLinks.get(token)

  if (!link) {
    return { isValid: false, error: 'Invalid or expired link. Please request a new one.' }
  }

  if (link.used) {
    return { isValid: false, error: 'This link has already been used. Please request a new one.' }
  }

  if (link.expiresAt < new Date()) {
    magicLinks.delete(token)
    return { isValid: false, error: 'Link expired. Please request a new one.' }
  }

  // Mark as used
  link.used = true
  magicLinks.set(token, link)

  // Clean up after verification
  setTimeout(() => magicLinks.delete(token), 60000)

  return { isValid: true, email: link.email }
}

/**
 * Send magic link email
 */
export async function sendMagicLinkEmail(
  email: string,
  token: string,
  options?: {
    appType?: 'web' | 'mobile'
    baseUrl?: string
  }
): Promise<{ success: boolean; error?: string }> {
  const baseUrl = options?.baseUrl || appUrl()
  const appType = options?.appType || 'web'

  // For mobile app, use deep link; for web, use regular URL
  const magicLinkUrl = appType === 'mobile'
    ? `gcc://auth/verify?token=${token}`
    : `${baseUrl}/auth/verify?token=${token}`

  const emailHtml = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px; background-color: #f5f5f5;">
  <div style="background: white; border-radius: 12px; padding: 40px; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
    <div style="text-align: center; margin-bottom: 30px;">
      <h1 style="color: #5D4037; margin: 0; font-size: 24px;">Global Community Cleaners</h1>
    </div>

    <h2 style="color: #333; margin-bottom: 20px;">Sign in to your account</h2>

    <p style="color: #666; line-height: 1.6;">
      Click the button below to sign in to your GCC account. This link will expire in 15 minutes.
    </p>

    <div style="text-align: center; margin: 30px 0;">
      <a href="${magicLinkUrl}"
         style="display: inline-block; background: linear-gradient(135deg, #16763A, #155B92); color: #ffffff; text-decoration: none; padding: 16px 32px; border-radius: 8px; font-weight: bold; font-size: 16px;">
        Sign In to GCC
      </a>
    </div>

    <p style="color: #999; font-size: 14px; line-height: 1.6;">
      If you didn't request this email, you can safely ignore it.
    </p>

    <p style="color: #999; font-size: 12px; margin-top: 30px; padding-top: 20px; border-top: 1px solid #eee;">
      If the button doesn't work, copy and paste this link into your browser:<br>
      <a href="${magicLinkUrl}" style="color: #16763A; word-break: break-all;">${magicLinkUrl}</a>
    </p>
  </div>

  <p style="text-align: center; color: #999; font-size: 12px; margin-top: 20px;">
    © ${new Date().getFullYear()} Global Community Cleaners. Cleaning Chicago, one block at a time.
  </p>
</body>
</html>
`

  const resend = getResend()
  if (!resend) {
    // Never log the token or the link — anyone with log access would get a
    // working sign-in (audit F-07). In production, fail closed: a login
    // email that silently never sends is a broken auth system, not a
    // degraded one.
    if (process.env.NODE_ENV === 'production') {
      throw new Error('RESEND_API_KEY is not configured — cannot send magic link email')
    }
    console.warn('[Magic link] SKIPPED — RESEND_API_KEY not configured; email not sent (token redacted)')
    return { success: false, error: 'Email service not configured (missing RESEND_API_KEY)' }
  }

  try {
    const { error } = await resend.emails.send({
      from: 'GCC <noreply@globalcommunitycleaners.org>',
      to: email,
      subject: 'Sign in to Global Community Cleaners',
      html: emailHtml,
      text: `Sign in to GCC\n\nClick this link to sign in: ${magicLinkUrl}\n\nThis link expires in 15 minutes.\n\nIf you didn't request this, you can ignore this email.`,
    })

    if (error) {
      console.error('Magic link email error:', error)
      return { success: false, error: error.message }
    }

    console.log(`Magic link sent to ${email}`)
    return { success: true }

  } catch (err) {
    const errorMsg = err instanceof Error ? err.message : 'Failed to send email'
    console.error('Magic link email error:', errorMsg)
    return { success: false, error: errorMsg }
  }
}

/**
 * Clean up expired magic links (call periodically)
 */
export function cleanupExpiredLinks(): void {
  const now = new Date()
  for (const [token, data] of magicLinks.entries()) {
    if (data.expiresAt < now || data.used) {
      magicLinks.delete(token)
    }
  }
}

// Auto-cleanup every minute
if (typeof setInterval !== 'undefined') {
  setInterval(cleanupExpiredLinks, 60000)
}
