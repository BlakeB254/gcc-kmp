/**
 * Single-writer leases for cron endpoints.
 *
 * The cron handlers were safe to leave unguarded while `vercel.json` fired them
 * once a day. Once an external scheduler (Render) drives them every few minutes,
 * a slow tick can still be running when the next one starts — and
 * `processDueBatches()` has no locking, so two overlapping runs would issue
 * duplicate offers and duplicate notification rows.
 *
 * `pg_advisory_lock` is not available to us: locks are session-scoped, and the
 * `neon-http` driver (see `site/src/db/index.ts`) sends every query as an
 * independent stateless HTTP request with no `db.transaction` support. So the
 * mutex is a row in `cron_leases`, acquired with one atomic statement:
 * `INSERT … ON CONFLICT DO UPDATE … WHERE expires_at < now()`. Postgres
 * evaluates the conflict target and the predicate together, so exactly one
 * caller gets a `RETURNING` row.
 *
 * The lease carries a TTL rather than a boolean so a crashed run self-heals
 * after `ttlSeconds` instead of wedging the queue permanently.
 */
import { sql } from 'drizzle-orm'
import { db } from '@/db'

export type CronLeaseName = 'route-releases' | 'building-footprints'

export type CronLeaseOutcome<T> =
  | { ran: true; result: T }
  | { ran: false; skipped: 'lease-held' }

/** Rows come back as a bare array on some drivers and `{ rows }` on others. */
function rowsOf(res: unknown): unknown[] {
  if (Array.isArray(res)) return res
  const rows = (res as { rows?: unknown[] } | null)?.rows
  return Array.isArray(rows) ? rows : []
}

async function acquire(name: CronLeaseName, ttlSeconds: number): Promise<boolean> {
  // The `::int` casts are load-bearing: neon-http sends bind params untyped, and
  // `make_interval(secs => $1)` fails to resolve an overload against an unknown
  // param. Casting pins it.
  const res = await db.execute(sql`
    insert into cron_leases (name, locked_at, expires_at)
    values (${name}, now(), now() + make_interval(secs => ${ttlSeconds}::int))
    on conflict (name) do update
      set locked_at = now(),
          expires_at = now() + make_interval(secs => ${ttlSeconds}::int)
      where cron_leases.expires_at < now()
    returning name
  `)
  return rowsOf(res).length > 0
}

/**
 * Expire the lease immediately so the next tick isn't forced to wait out the
 * full TTL after a fast run. Never throws — losing the release is survivable
 * (the TTL still expires it), but losing the caller's result is not.
 */
async function release(name: CronLeaseName): Promise<void> {
  try {
    await db.execute(sql`update cron_leases set expires_at = now() where name = ${name}`)
  } catch (e) {
    console.error(`cron-lease: failed to release ${name} (will expire via TTL)`, e)
  }
}

/**
 * Run `fn` iff no other tick holds the lease. `ttlSeconds` must exceed the
 * handler's worst-case runtime, or a long run will have its lease expire out
 * from under it and a concurrent tick will start.
 */
export async function withCronLease<T>(
  name: CronLeaseName,
  ttlSeconds: number,
  fn: () => Promise<T>,
): Promise<CronLeaseOutcome<T>> {
  if (!(await acquire(name, ttlSeconds))) {
    return { ran: false, skipped: 'lease-held' }
  }
  try {
    return { ran: true, result: await fn() }
  } finally {
    await release(name)
  }
}
