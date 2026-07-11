/**
 * Blob pathname for one uploaded spot photo.
 *
 * This is a PREFIX, not a unique name. Uniqueness comes from `put()`'s
 * `addRandomSuffix: true` — which defaults to FALSE, and whose absence is why
 * production returned HTTP 500 "This blob already exists" when two uploads
 * landed in the same millisecond with the same multipart form key (`photo_0`).
 */
export function spotPhotoPathname(workerId: number, formKey: string): string {
  // Strip anything that could escape the worker's folder or confuse the store.
  const safeKey = formKey.replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 32) || 'photo'
  return `spots/${workerId}/${Date.now()}-${safeKey}.jpg`
}
