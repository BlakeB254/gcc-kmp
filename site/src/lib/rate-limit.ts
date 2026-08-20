/**
 * Postgres-backed fixed-window rate limiting for the public auth endpoints
 * (security audit 2026-08-20, finding F-06).
 *
 * The previous limits lived in in-memory Maps, which are meaningless on
 * serverless: every instance gets its own Map and cold starts reset it.
 * This helper records one row per request in `rate_limit_events` and counts
 * rows inside the window — correct across instances and cold starts.
 *
 * Key convention: `<endpoint>:<ip>` (see `clientIp`). Rows older than the
 * window are pruned for the key being checked, so the table self-cleans
 * without a sweeper job.
 */
import { sql } from 'drizzle-orm'
import { db } from '@/db'

/** Rows come back as a bare array on some drivers and `{ rows }` on others. */
function rowsOf(res: unknown): Array<Record<string, unknown>> {
  if (Array.isArray(res)) return res as Array<Record<string, unknown>>
  const rows = (res as { rows?: unknown[] } | null)?.rows
  return Array.isArray(rows) ? (rows as Array<Record<string, unknown>>) : []
}

/**
 * Best-effort client IP. On Vercel, `x-forwarded-for` is set by the edge and
 * the first entry is the real client; `x-real-ip` covers other proxies.
 */
export function clientIp(request: Request): string {
  const forwarded = request.headers.get('x-forwarded-for')
  if (forwarded) return forwarded.split(',')[0].trim()
  return request.headers.get('x-real-ip') || 'unknown'
}

/**
 * Record one hit for `key` and report whether the key is now over budget.
 * Defaults: 10 requests per 10 minutes. The hit is recorded before the count
 * is taken, so the request that crosses the limit is the one rejected.
 */
export async function isRateLimited(
  key: string,
  limit = 10,
  windowSeconds = 600,
): Promise<boolean> {
  // The `::int` cast is load-bearing: neon-http sends bind params untyped,
  // and `make_interval(secs => $1)` fails to resolve an overload against an
  // unknown param (same gotcha as lib/cron-lease.ts).
  await db.execute(sql`
    delete from rate_limit_events
    where key = ${key}
      and created_at < now() - make_interval(secs => ${windowSeconds}::int)
  `)
  await db.execute(sql`
    insert into rate_limit_events (key) values (${key})
  `)
  const res = await db.execute(sql`
    select count(*)::int as hits from rate_limit_events where key = ${key}
  `)
  const hits = Number(rowsOf(res)[0]?.hits ?? 0)
  return hits > limit
}
