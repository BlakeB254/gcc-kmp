/**
 * Daily aggregate caps on ad-hoc admin wallet credits, enforced by Postgres.
 *
 * Two counters per UTC day in admin_credit_daily_totals: what an admin has
 * credited to anyone (scope 'admin') and what a worker has received from any
 * admin (scope 'target'). neon-http has no interactive transactions, so the
 * cap cannot be "read the sum, then write": both counters are bumped in ONE
 * upsert whose DO UPDATE … WHERE refuses a total above that scope's cap. The
 * row lock Postgres takes for the conflicting row is what makes concurrent
 * requests serialize — a counter can never end up above its cap.
 *
 * The statement can apply to one counter and refuse the other (one of them is
 * at its cap). That is a refusal: the counter that was bumped is released
 * again with a compensating UPDATE. A crash between the two leaves the counter
 * high, which only means less room today — the safe direction.
 *
 * Callers reserve BEFORE writing money (ledger row or pending request) and
 * release when that write turns out to be a no-op (idempotent retry) or when a
 * pending request is rejected.
 */
import { and, eq, sql } from 'drizzle-orm'
import { adminCreditDailyTotals } from '@/db/schema'
import type { AdminCreditLimits } from '@/lib/admin-credit'
import type { DbExecutor } from '@/lib/job-lifecycle'

export type CapScope = 'admin' | 'target'

export interface DailyCapacityArgs {
  adminWorkerId: number
  targetWorkerId: number
  amountCents: number
  /** UTC calendar date, 'YYYY-MM-DD' (utcDay()). */
  day: string
  limits: AdminCreditLimits
}

export type DailyCapacityResult =
  | { ok: true; adminTotalCents: number; targetTotalCents: number }
  | { ok: false; scope: CapScope; capCents: number }

/** The cap day is the UTC calendar date of `now`. */
export function utcDay(now: Date = new Date()): string {
  return now.toISOString().slice(0, 10)
}

function capFor(scope: CapScope, limits: AdminCreditLimits): number {
  return scope === 'admin' ? limits.perAdminDailyCents : limits.perTargetDailyCents
}

/**
 * Count `amountCents` against both daily counters, or refuse without counting.
 * Refuses before any statement when the amount alone exceeds a cap (it could
 * never fit).
 */
export async function reserveDailyCapacity(exec: DbExecutor, args: DailyCapacityArgs): Promise<DailyCapacityResult> {
  const { adminWorkerId, targetWorkerId, amountCents, day, limits } = args
  if (!Number.isInteger(amountCents) || amountCents <= 0) {
    throw new Error(`reserveDailyCapacity: amountCents must be a positive integer, got ${amountCents}`)
  }
  for (const scope of ['target', 'admin'] as const) {
    if (amountCents > capFor(scope, limits)) return { ok: false, scope, capCents: capFor(scope, limits) }
  }

  const t = adminCreditDailyTotals
  const applied = await exec
    .insert(t)
    .values([
      { scope: 'admin', subjectId: adminWorkerId, day, totalCents: amountCents },
      { scope: 'target', subjectId: targetWorkerId, day, totalCents: amountCents },
    ])
    .onConflictDoUpdate({
      target: [t.scope, t.subjectId, t.day],
      set: { totalCents: sql`${t.totalCents} + excluded.total_cents` },
      // Evaluated under the conflicting row's lock: the cap holds under races.
      setWhere: sql`${t.totalCents} + excluded.total_cents <= case excluded.scope when 'admin' then ${limits.perAdminDailyCents} else ${limits.perTargetDailyCents} end`,
    })
    .returning({ scope: t.scope, totalCents: t.totalCents })

  const byScope = new Map(applied.map((r) => [r.scope as CapScope, Number(r.totalCents)]))
  const adminTotal = byScope.get('admin')
  const targetTotal = byScope.get('target')
  if (adminTotal !== undefined && targetTotal !== undefined) {
    return { ok: true, adminTotalCents: adminTotal, targetTotalCents: targetTotal }
  }

  // Partial or no application: give back whatever was counted, report the cap that refused.
  const bumped = (['admin', 'target'] as const).filter((s) => byScope.has(s))
  if (bumped.length > 0) {
    await releaseDailyCapacity(exec, { adminWorkerId, targetWorkerId, amountCents, day, scopes: bumped })
  }
  const refused: CapScope = adminTotal === undefined ? 'admin' : 'target'
  return { ok: false, scope: refused, capCents: capFor(refused, limits) }
}

/**
 * Give reserved capacity back (idempotent retry that credited nothing, a
 * rejected pending request, or the partial case above). Never below zero.
 */
export async function releaseDailyCapacity(
  exec: DbExecutor,
  args: { adminWorkerId: number; targetWorkerId: number; amountCents: number; day: string; scopes?: readonly CapScope[] },
): Promise<void> {
  const t = adminCreditDailyTotals
  const scopes = args.scopes ?? (['admin', 'target'] as const)
  for (const scope of scopes) {
    const subjectId = scope === 'admin' ? args.adminWorkerId : args.targetWorkerId
    await exec
      .update(t)
      .set({ totalCents: sql`GREATEST(${t.totalCents} - ${args.amountCents}, 0)` })
      .where(and(eq(t.scope, scope), eq(t.subjectId, subjectId), eq(t.day, args.day)))
  }
}
