/**
 * When an evidence photo was really uploaded, asked of OUR blob store:
 * @vercel/blob head() returns the stored object's `uploadedAt` (the store's
 * clock). Null when the object doesn't exist, or when no store token is
 * configured (fail closed: no store, no evidence). Other errors throw, so a
 * store outage answers 500 rather than quietly refusing or accepting work.
 *
 * Kept out of lib/job-lifecycle.ts so that module (and its tests) never
 * import the blob SDK; the completion route passes this in.
 */
import { BlobNotFoundError, head } from '@vercel/blob'

export async function blobUploadedAt(
  url: string,
  token: string | undefined = process.env.BLOB_READ_WRITE_TOKEN,
): Promise<number | null> {
  if (!token) return null
  try {
    const meta = await head(url, { token })
    const at = meta.uploadedAt instanceof Date ? meta.uploadedAt.getTime() : Date.parse(String(meta.uploadedAt))
    return Number.isNaN(at) ? null : at
  } catch (error) {
    if (error instanceof BlobNotFoundError) return null
    throw error
  }
}
