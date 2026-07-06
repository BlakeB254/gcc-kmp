import { NextRequest } from 'next/server'
import crypto from 'crypto'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { sendEmail } from '@/lib/email'
import { emailChangeVerificationEmail } from '@/lib/email/templates/email-change-verification'
import {
  apiEnvelope,
  apiFailure,
  checkPassword,
  getWorkerById,
  sendSecurityAlert,
  unauthorized,
} from '@/lib/account-settings'

export const runtime = 'nodejs'

const PENDING_EMAIL_TTL_MS = 30 * 60 * 1000

/**
 * Step 1 of changing the account email: re-auth with password, then send a
 * 6-digit code to the NEW address. The account email does not change until
 * /email/confirm proves the user controls the new inbox. The old address
 * gets a heads-up notification.
 */
export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) return unauthorized()

  const body = await request.json()
  const newEmail = typeof body.newEmail === 'string'
    ? body.newEmail.replace(/\s/g, '').toLowerCase()
    : ''

  const worker = await getWorkerById(auth.workerId)
  if (!worker) return apiFailure('Worker not found', 404)

  const passwordError = checkPassword(worker, body.password)
  if (passwordError) return passwordError

  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(newEmail) || newEmail.length > 100) {
    return apiFailure('Enter a valid email address', 400)
  }
  if (newEmail === worker.email.toLowerCase()) {
    return apiFailure('That is already the email on your account', 400)
  }

  const [taken] = await db
    .select({ id: workers.id })
    .from(workers)
    .where(eq(workers.email, newEmail))
    .limit(1)
  if (taken) {
    return apiFailure('That email is already in use by another account', 409)
  }

  const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0')
  const token = crypto.randomBytes(32).toString('hex')

  await db
    .update(workers)
    .set({
      pendingEmail: newEmail,
      // Same "token:code" encoding as emailVerificationToken.
      pendingEmailToken: `${token}:${code}`,
      pendingEmailExpires: new Date(Date.now() + PENDING_EMAIL_TTL_MS).toISOString(),
      pendingEmailAttempts: 0,
      updatedAt: new Date().toISOString(),
    })
    .where(eq(workers.id, worker.id))

  const { html, text } = emailChangeVerificationEmail({ name: worker.name, code })
  const sent = await sendEmail({
    to: newEmail,
    subject: `${code} is your GCC email confirmation code`,
    html,
    text,
  })
  if (!sent.success) {
    return apiFailure('Could not send the verification email. Please try again.', 500)
  }

  await sendSecurityAlert(
    worker.email,
    worker.name,
    'A request was made to change the email address on your GCC account.',
    `Requested new address: ${newEmail}. The change only happens after that inbox confirms a verification code.`,
  )

  return Response.json(apiEnvelope({ pendingEmail: newEmail, expiresInMinutes: 30 }))
}
