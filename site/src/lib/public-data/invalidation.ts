import { revalidateTag } from 'next/cache'

import { GCC_CACHE_TAGS } from './cache-tags'

/**
 * Public cache invalidation vocabulary.
 *
 * Compatibility kinds (`cleanup-impact`, `worker-verification`, `sponsorship`,
 * `supporter`, `affected-area`) remain for callers that intentionally expire a
 * multi-family group. Prefer the granular kinds when a write only touches one
 * projection family so unrelated Next cache entries stay warm.
 */
export type PublicDataChange =
  // Compatibility groups
  | 'cleanup-impact'
  | 'worker-verification'
  | 'sponsorship'
  | 'supporter'
  | 'affected-area'
  // Granular families
  | 'public-stats'
  | 'impact-stats'
  | 'wall-presentation'

export type PublicDataInvalidator = (change: PublicDataChange) => void

type RevalidateTag = (
  tag: string,
  profile: 'max',
) => void

const TAGS_BY_CHANGE: Record<PublicDataChange, readonly string[]> = {
  // Compatibility groups — keep exact multi-family semantics for existing call sites
  'cleanup-impact': [GCC_CACHE_TAGS.publicStats, GCC_CACHE_TAGS.impactStats],
  'worker-verification': [GCC_CACHE_TAGS.publicStats, GCC_CACHE_TAGS.impactStats],
  sponsorship: [
    GCC_CACHE_TAGS.publicStats,
    GCC_CACHE_TAGS.impactStats,
    GCC_CACHE_TAGS.sponsorWall,
    GCC_CACHE_TAGS.supportWall,
  ],
  supporter: [GCC_CACHE_TAGS.publicStats, GCC_CACHE_TAGS.impactStats],
  'affected-area': [GCC_CACHE_TAGS.affectedAreaSnapshots],
  // Granular families — use when a committed write changes only one projection
  'public-stats': [GCC_CACHE_TAGS.publicStats],
  'impact-stats': [GCC_CACHE_TAGS.impactStats],
  'wall-presentation': [GCC_CACHE_TAGS.sponsorWall, GCC_CACHE_TAGS.supportWall],
}

export function tagsForPublicDataChange(change: PublicDataChange): readonly string[] {
  const tags = TAGS_BY_CHANGE[change]
  if (!tags) {
    throw new Error(`Unknown public data change: ${String(change)}`)
  }
  return tags
}

export function createPublicDataInvalidator(
  invalidateTag: RevalidateTag,
): PublicDataInvalidator {
  return (change) => {
    for (const tag of tagsForPublicDataChange(change)) {
      invalidateTag(tag, 'max')
    }
  }
}

const nextPublicDataInvalidator = createPublicDataInvalidator(revalidateTag)

export function invalidatePublicData(change: PublicDataChange): void {
  nextPublicDataInvalidator(change)
}

export async function commitPublicDataMutation<T>(
  change: PublicDataChange,
  mutation: () => Promise<T>,
  invalidate: PublicDataInvalidator = invalidatePublicData,
): Promise<T> {
  return commitPublicDataChanges([change], mutation, invalidate)
}

/**
 * Run an authoritative mutation, then invalidate only if the result indicates
 * a real write occurred. Empty arrays (zero-row UPDATE/INSERT … RETURNING) and
 * null/undefined results skip invalidation so no-ops do not thrash cache.
 */
export async function commitPublicDataChanges<T>(
  changes: readonly PublicDataChange[],
  mutation: () => Promise<T>,
  invalidate: PublicDataInvalidator = invalidatePublicData,
): Promise<T> {
  const result = await mutation()
  if (!didAuthoritativeWriteOccur(result)) {
    return result
  }
  for (const change of changes) {
    try {
      invalidate(change)
    } catch (error) {
      // The authoritative write already committed. Preserve the route's
      // success contract; every cache also has a finite TTL fallback.
      console.error(`Failed to invalidate public data after ${change}:`, error)
    }
  }
  return result
}

function didAuthoritativeWriteOccur(result: unknown): boolean {
  if (result === null || result === undefined) return false
  if (Array.isArray(result) && result.length === 0) return false
  return true
}
