/**
 * Guards for POST /api/admin/workers/[id]/payout (ad-hoc admin wallet credit).
 *
 * An admin credit is for money that is NOT a job earning — a bonus or a
 * correction. Job money has exactly one path: the worker completes, the job
 * goes to pending_review, a different admin approves it
 * (POST /api/admin/jobs/[id]/approve). This guard refuses everything that
 * would bypass that path, and refuses crediting your own wallet.
 */
import { MAX_NOTE_LEN, MAX_PAYOUT_CENTS } from '@/lib/payouts'

export interface AdminCreditBody {
  amountCents?: unknown
  reason?: unknown
  /** Older mobile admin builds send the reason as `note`. */
  note?: unknown
  idempotencyKey?: unknown
  jobId?: unknown
}

export type AdminCreditDecision =
  | { ok: true; amountCents: number; reason: string; idempotencyKey: string }
  | { ok: false; status: number; message: string }

// "job 123", "job #123", "job#123", "jobs 12" — a credit that names a job is
// job money, and job money goes through approve.
const JOB_REFERENCE = /\bjobs?\s*#?\s*\d+/i

export function validateAdminCredit(args: {
  adminWorkerId: number
  targetWorkerId: number
  body: AdminCreditBody
}): AdminCreditDecision {
  const { adminWorkerId, targetWorkerId, body } = args

  if (adminWorkerId === targetWorkerId) {
    return {
      ok: false,
      status: 403,
      message: 'You cannot credit your own wallet — another admin must do it.',
    }
  }

  if (body.jobId !== undefined && body.jobId !== null) {
    return {
      ok: false,
      status: 400,
      message: 'Job payments go through job approval (Jobs → Pending review), not a manual credit.',
    }
  }

  const amountCents = typeof body.amountCents === 'number' ? body.amountCents : NaN
  if (!Number.isInteger(amountCents) || amountCents <= 0 || amountCents > MAX_PAYOUT_CENTS) {
    return {
      ok: false,
      status: 400,
      message: `amountCents must be an integer between 1 and ${MAX_PAYOUT_CENTS} ($${MAX_PAYOUT_CENTS / 100})`,
    }
  }

  const rawReason = typeof body.reason === 'string' ? body.reason : typeof body.note === 'string' ? body.note : ''
  const reason = rawReason.trim()
  if (!reason) {
    return { ok: false, status: 400, message: 'A reason is required for a manual credit.' }
  }
  if (reason.length > MAX_NOTE_LEN) {
    return { ok: false, status: 400, message: `reason must be ≤ ${MAX_NOTE_LEN} chars` }
  }
  if (JOB_REFERENCE.test(reason)) {
    return {
      ok: false,
      status: 400,
      message: 'This credit names a job. Job payments go through job approval, not a manual credit.',
    }
  }

  const idempotencyKey = typeof body.idempotencyKey === 'string' ? body.idempotencyKey.trim() : ''
  if (idempotencyKey.length < 8 || idempotencyKey.length > 128) {
    return { ok: false, status: 400, message: 'idempotencyKey must be 8–128 chars' }
  }

  return { ok: true, amountCents, reason, idempotencyKey }
}
