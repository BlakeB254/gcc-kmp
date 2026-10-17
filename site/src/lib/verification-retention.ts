/**
 * Retention sweep for private verification documents (R2 `verification/`
 * prefix — government ID, proof of address, address-change proofs). These are
 * PII we should not hold longer than review needs them.
 *
 * Ported from backend/…/VerificationPhotoRetention.kt, which was never
 * deployed (the Next.js API is the only backend). Same rules:
 *
 * - Review-flow guard: a key is NEVER purged while its worker is still in the
 *   verification loop — overall status pending/needs_info, either document
 *   status pending/resubmit — or (new here) has a pending address change. A key
 *   whose worker row no longer exists cannot be in review and is purgeable.
 * - Tombstone, then delete: media rows referencing the key get
 *   `url = 'purged:' || key` first, so the admin view shows a missing document
 *   (not a presigned 404) and the purge stays visible in the DB. If the R2
 *   delete then fails, the object is unreferenced and the next sweep removes it.
 * - Dry-run unless `enforce` is set; never fewer than MIN_RETENTION_DAYS.
 */

export const MIN_RETENTION_DAYS = 30
export const PREFIX = 'verification/'
const WORKER_KEY = /^verification\/(\d+)\//

export interface RetentionDeps {
  listPrivate(prefix: string): Promise<{ key: string; lastModifiedMs: number }[]>
  /** Worker ids (of those given) still inside the review loop. */
  reviewProtectedWorkers(workerIds: number[]): Promise<Set<number>>
  /** Keys (of those given) referenced by a media row. */
  referencedKeys(keys: string[]): Promise<Set<string>>
  tombstone(key: string): Promise<number>
  deletePrivate(key: string): Promise<void>
}

export interface RetentionResult {
  enforce: boolean
  retentionDays: number
  scanned: number
  expired: number
  protectedByReview: number
  expiredReferenced: number
  deleted: number
  tombstoned: number
  failed: number
}

export function clampRetentionDays(days: number): number {
  if (!Number.isFinite(days)) return MIN_RETENTION_DAYS
  return Math.max(MIN_RETENTION_DAYS, Math.floor(days))
}

export async function sweepVerificationDocs(
  deps: RetentionDeps,
  opts: { retentionDays: number; enforce: boolean; now?: number },
): Promise<RetentionResult> {
  const retentionDays = clampRetentionDays(opts.retentionDays)
  const cutoff = (opts.now ?? Date.now()) - retentionDays * 24 * 60 * 60 * 1000
  const objects = await deps.listPrivate(PREFIX)
  const expired = objects.filter((o) => o.lastModifiedMs > 0 && o.lastModifiedMs < cutoff).map((o) => o.key)

  const result: RetentionResult = {
    enforce: opts.enforce, retentionDays, scanned: objects.length, expired: expired.length,
    protectedByReview: 0, expiredReferenced: 0, deleted: 0, tombstoned: 0, failed: 0,
  }
  if (expired.length === 0) return result

  const workerOf = (k: string) => {
    const m = k.match(WORKER_KEY)
    return m ? Number(m[1]) : null
  }
  const ids = [...new Set(expired.map(workerOf).filter((w): w is number => w != null && w > 0))]
  const protectedWorkers = ids.length ? await deps.reviewProtectedWorkers(ids) : new Set<number>()
  const purgeable = expired.filter((k) => {
    const w = workerOf(k)
    return !(w != null && protectedWorkers.has(w))
  })
  result.protectedByReview = expired.length - purgeable.length

  const referenced = purgeable.length ? await deps.referencedKeys(purgeable) : new Set<string>()
  result.expiredReferenced = purgeable.filter((k) => referenced.has(k)).length
  if (!opts.enforce) return result

  for (const key of purgeable) {
    try {
      if (referenced.has(key) && (await deps.tombstone(key)) > 0) result.tombstoned++
      await deps.deletePrivate(key)
      result.deleted++
    } catch (e) {
      result.failed++
      console.warn(`[verification-retention] failed to purge ${key}:`, e instanceof Error ? e.message : e)
    }
  }
  return result
}
