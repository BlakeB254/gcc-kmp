/**
 * Pre-flight guards for POST /api/workers/me/withdraw — who may move money
 * out, and when. Pure functions so the rules are testable without a DB.
 */

/** A change to how/where a worker is paid locks cash outs for this long. */
export const PAYOUT_CHANGE_COOLDOWN_MS = 24 * 60 * 60 * 1000

export interface WithdrawEligibilityWorker {
  verificationStatus: string | null
  emailVerified: boolean | null
  payoutMethodChangedAt: string | null
  /** Home-address change — where a check goes when no override is set. */
  lastAddressChangeAt: string | null
}

export type WithdrawRefusal = { status: number; message: string }

/**
 * Null when the worker may withdraw now. Applies to BOTH methods (stripe and
 * check): an account that is not approved or has no verified email cannot
 * cash out, and a recent change to the payout destination holds cash outs for
 * 24h so a hijacked session can't redirect and drain the wallet in one go
 * (the worker is notified of the change when it happens).
 */
export function withdrawRefusal(
  worker: WithdrawEligibilityWorker,
  method: 'stripe' | 'check',
  nowMs: number = Date.now(),
): WithdrawRefusal | null {
  if (worker.verificationStatus !== 'approved') {
    return {
      status: 403,
      message: 'Your account must be approved before you can cash out.',
    }
  }
  if (worker.emailVerified !== true) {
    return {
      status: 403,
      message: 'Verify your email address before cashing out.',
    }
  }

  const changes = [worker.payoutMethodChangedAt]
  if (method === 'check') changes.push(worker.lastAddressChangeAt)
  const latestChange = changes
    .map((v) => (v ? Date.parse(v) : NaN))
    .filter((t) => Number.isFinite(t))
    .reduce((a, b) => Math.max(a, b), -Infinity)

  if (Number.isFinite(latestChange) && nowMs - latestChange < PAYOUT_CHANGE_COOLDOWN_MS) {
    const unlockAt = new Date(latestChange + PAYOUT_CHANGE_COOLDOWN_MS)
    return {
      status: 409,
      message:
        'You recently changed how you get paid. For your security, cash outs unlock 24 hours after that change ' +
        `(${unlockAt.toISOString()}).`,
    }
  }
  return null
}

/**
 * payouts.idempotency_key is globally unique, but the key is client-chosen.
 * Namespacing it by worker means two workers' keys can never collide — so a
 * retry lookup can never hand one worker another worker's payout row.
 */
export function scopedWithdrawIdempotencyKey(workerId: number, clientKey: string): string {
  return `w${workerId}:${clientKey}`
}
