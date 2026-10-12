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

// ── Aggregate limits ─────────────────────────────────────────────────────────
//
// The per-call bound above (MAX_PAYOUT_CENTS) says nothing about volume: one
// admin could credit a worker $1,000 ten times with fresh idempotency keys, or
// two admins could credit each other. These limits close that:
//
//   perAdminDailyCents            everything one admin credits, to anyone, per UTC day
//   perTargetDailyCents           everything one worker receives, from any admin, per UTC day
//   secondApprovalThresholdCents  a single credit above this needs a DIFFERENT
//                                 admin's approval before it lands (lib/admin-credit-approval.ts)
//
// Both caps are enforced in Postgres (lib/admin-credit-caps.ts). All three are
// env-configured in whole cents and FAIL CLOSED: an unset, empty or unparseable
// value is the (low) default — a typo can never raise a limit.

export interface AdminCreditLimits {
  perAdminDailyCents: number
  perTargetDailyCents: number
  secondApprovalThresholdCents: number
}

export const DEFAULT_ADMIN_CREDIT_LIMITS: Readonly<AdminCreditLimits> = Object.freeze({
  perAdminDailyCents: 50_000, // $500
  perTargetDailyCents: 25_000, // $250
  secondApprovalThresholdCents: 10_000, // $100
})

export const ADMIN_CREDIT_LIMIT_ENV = {
  perAdminDailyCents: 'ADMIN_CREDIT_DAILY_CAP_PER_ADMIN_CENTS',
  perTargetDailyCents: 'ADMIN_CREDIT_DAILY_CAP_PER_TARGET_CENTS',
  secondApprovalThresholdCents: 'ADMIN_CREDIT_SECOND_APPROVAL_THRESHOLD_CENTS',
} as const

/** Whole non-negative cents only ("0" is allowed: it disables credits / requires approval for all). */
function wholeCents(raw: string | undefined, fallback: number): number {
  if (typeof raw !== 'string') return fallback
  const trimmed = raw.trim()
  if (!/^\d+$/.test(trimmed)) return fallback
  const n = Number(trimmed)
  return Number.isSafeInteger(n) ? n : fallback
}

export function adminCreditLimits(env: Record<string, string | undefined> = process.env): AdminCreditLimits {
  return {
    perAdminDailyCents: wholeCents(env[ADMIN_CREDIT_LIMIT_ENV.perAdminDailyCents], DEFAULT_ADMIN_CREDIT_LIMITS.perAdminDailyCents),
    perTargetDailyCents: wholeCents(env[ADMIN_CREDIT_LIMIT_ENV.perTargetDailyCents], DEFAULT_ADMIN_CREDIT_LIMITS.perTargetDailyCents),
    secondApprovalThresholdCents: wholeCents(
      env[ADMIN_CREDIT_LIMIT_ENV.secondApprovalThresholdCents],
      DEFAULT_ADMIN_CREDIT_LIMITS.secondApprovalThresholdCents,
    ),
  }
}

/** A credit strictly above the threshold waits for a second admin. */
export function requiresSecondApproval(amountCents: number, limits: AdminCreditLimits): boolean {
  return amountCents > limits.secondApprovalThresholdCents
}
