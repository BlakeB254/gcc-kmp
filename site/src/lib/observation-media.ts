/**
 * The observation's media URL IF it is publishable, else null. Privacy
 * flag-down (Blake, 2026-07-22): `POST /api/observations/:id/flag-media`
 * sets `attributes.mediaFlagged` when a user reports a photo (person /
 * animal / home interior / no visible trash) — the URL stays stored for
 * admin review but must never be served. EVERY surface that exposes
 * observation imagery (public map, bounties, sponsor completion reports)
 * must read the URL through this helper.
 *
 * Standalone module on purpose: NO `@/db` import, so pure consumers
 * (`bounties/_derived.ts` and its `tsx --test` suite) stay loadable
 * without a DATABASE_URL.
 */
export function servableMediaUrl(row: {
  mediaUrl: string | null
  attributes: unknown
}): string | null {
  const flagged = (row.attributes as Record<string, unknown> | null)?.mediaFlagged === true
  return flagged ? null : row.mediaUrl
}
