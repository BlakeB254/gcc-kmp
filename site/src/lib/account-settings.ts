/**
 * Shared plumbing for the account-settings endpoints
 * (/api/workers/me/{profile,change-password,email/*,phone/*,2fa/*}).
 */
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyPassword } from '@/lib/mobile-auth'
import { sendEmail } from '@/lib/email'
import { securityAlertEmail } from '@/lib/email/templates/security-alert'

export type WorkerRow = typeof workers.$inferSelect

export async function getWorkerById(workerId: number): Promise<WorkerRow | null> {
  const [worker] = await db.select().from(workers).where(eq(workers.id, workerId)).limit(1)
  return worker ?? null
}

export function apiEnvelope(data: unknown, message: string | null = null) {
  return { data, success: true, message }
}

export function apiFailure(message: string, status: number) {
  return Response.json({ data: null, success: false, message }, { status })
}

export const unauthorized = () => apiFailure('Not authenticated', 401)

/**
 * Re-authentication gate for sensitive changes. Returns null when the
 * password checks out, otherwise a ready-to-return error Response.
 *
 * Failures are 400 (not 401/403) on purpose: the mobile client treats
 * 401/403 globally as "session dead" and logs the user out, and a typo'd
 * password in Settings must not do that.
 */
export function checkPassword(worker: WorkerRow, password: unknown): Response | null {
  if (!worker.hash || !worker.salt) {
    return apiFailure(
      'This account signs in with Apple or Google and has no password set.',
      400,
    )
  }
  if (typeof password !== 'string' || !verifyPassword(password, worker.hash, worker.salt)) {
    return apiFailure('Incorrect password', 400)
  }
  return null
}

/**
 * Best-effort security notification — a failed send must never fail the
 * account mutation itself (matches how existing routes treat email).
 */
export async function sendSecurityAlert(
  to: string,
  name: string,
  headline: string,
  detail?: string,
): Promise<void> {
  try {
    const { html, text } = securityAlertEmail({ name, headline, detail })
    await sendEmail({ to, subject: 'Security alert: a change was made to your GCC account', html, text })
  } catch (error) {
    console.error('[AccountSettings] security alert email failed:', error)
  }
}
