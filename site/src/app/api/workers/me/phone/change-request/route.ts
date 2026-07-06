import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import {
  apiEnvelope,
  apiFailure,
  checkPassword,
  getWorkerById,
  sendSecurityAlert,
  unauthorized,
} from '@/lib/account-settings'

export const runtime = 'nodejs'

/**
 * Change the account phone number. Requires password re-auth; the account
 * email gets a security notification.
 *
 * NOTE: an SMS-OTP confirmation of the new number (the full industry
 * pattern) is deliberately deferred — the only SMS channel today is the
 * unreliable carrier-email gateway, and phone is contact info here, not an
 * auth factor. This endpoint is the seam where a confirm step slots in once
 * Twilio is wired up.
 */
export async function POST(request: NextRequest) {
  const auth = await requireMobileAuth(request)
  if (!auth) return unauthorized()

  const body = await request.json()

  const worker = await getWorkerById(auth.workerId)
  if (!worker) return apiFailure('Worker not found', 404)

  const passwordError = checkPassword(worker, body.password)
  if (passwordError) return passwordError

  const rawPhone = typeof body.newPhone === 'string' ? body.newPhone.trim() : ''
  // Keep digits and a leading +; tolerate common separators in input.
  const normalized = (rawPhone.startsWith('+') ? '+' : '') + rawPhone.replace(/\D/g, '')
  const digitCount = normalized.replace(/\D/g, '').length
  if (digitCount < 7 || digitCount > 15) {
    return apiFailure('Enter a valid phone number', 400)
  }

  await db
    .update(workers)
    .set({ phone: normalized, updatedAt: new Date().toISOString() })
    .where(eq(workers.id, worker.id))

  await sendSecurityAlert(
    worker.email,
    worker.name,
    'The phone number on your GCC account was just changed.',
    `New number: ${normalized}`,
  )

  return Response.json(apiEnvelope({ phone: normalized }))
}
