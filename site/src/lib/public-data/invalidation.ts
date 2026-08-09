import { revalidateTag } from 'next/cache'

import { GCC_CACHE_TAGS } from './cache-tags'

export type PublicDataChange =
  | 'cleanup-impact'
  | 'worker-verification'
  | 'sponsorship'
  | 'supporter'
  | 'affected-area'

export type PublicDataInvalidator = (change: PublicDataChange) => void

type RevalidateTag = (
  tag: string,
  profile: 'max',
) => void

const TAGS_BY_CHANGE: Record<PublicDataChange, readonly string[]> = {
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

export async function commitPublicDataChanges<T>(
  changes: readonly PublicDataChange[],
  mutation: () => Promise<T>,
  invalidate: PublicDataInvalidator = invalidatePublicData,
): Promise<T> {
  const result = await mutation()
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
