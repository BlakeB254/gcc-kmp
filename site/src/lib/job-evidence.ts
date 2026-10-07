/**
 * Reading `jobs.before_photos` / `jobs.after_photos`.
 *
 * PATCH /api/jobs/:id/evidence appends `{ url, capturedAt }` objects. The
 * column comment in schema.ts still says "Array of media IDs", and the admin
 * pending-review queue read it that way — handing `{url, capturedAt}` objects
 * to `inArray(media.id, …)`, which fails the whole query for any job with
 * evidence. This module accepts every shape the column can hold:
 *
 *  - `{ url, capturedAt }` objects (what the app writes today)
 *  - bare URL strings
 *  - integer media ids (legacy; resolved through the `media` table by the caller)
 *
 * No `@/db` import, so the `tsx --test` suite loads it without a DATABASE_URL.
 */

export interface EvidenceRef {
  /** Present when the entry carries its own URL. */
  url?: string
  /** Present for a legacy integer media id that still needs resolving. */
  mediaId?: number
}

export function evidenceRefs(raw: unknown): EvidenceRef[] {
  if (!Array.isArray(raw)) return []
  const out: EvidenceRef[] = []
  for (const item of raw) {
    if (typeof item === 'string' && item.length > 0) {
      out.push({ url: item })
    } else if (typeof item === 'number' && Number.isInteger(item)) {
      out.push({ mediaId: item })
    } else if (item && typeof item === 'object') {
      const url = (item as { url?: unknown }).url
      if (typeof url === 'string' && url.length > 0) out.push({ url })
    }
  }
  return out
}

/** Integer media ids referenced by an evidence column (legacy rows only). */
export function evidenceMediaIds(raw: unknown): number[] {
  return evidenceRefs(raw).flatMap((ref) => (ref.mediaId !== undefined ? [ref.mediaId] : []))
}

/** Evidence entries as URLs, in stored order; unresolvable media ids are dropped. */
export function evidenceUrls(raw: unknown, mediaUrlById: Map<number, string> = new Map()): string[] {
  const urls: string[] = []
  for (const ref of evidenceRefs(raw)) {
    const url = ref.url ?? (ref.mediaId !== undefined ? mediaUrlById.get(ref.mediaId) : undefined)
    if (url) urls.push(url)
  }
  return urls
}
