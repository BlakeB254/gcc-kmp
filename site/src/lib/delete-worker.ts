import { del, list } from '@vercel/blob'
import { eq, inArray } from 'drizzle-orm'
import { db } from '@/db'
import { workers, media } from '@/db/schema'

/**
 * Delete a worker account and its PII side-effects. Verification documents
 * (government ID, proof of address) live in *public* Vercel Blob storage
 * under `worker-docs/{workerId}/` with matching `media` rows — leaving them
 * behind after account deletion would keep identity documents reachable at
 * their public URLs.
 *
 * Blob/media cleanup is best-effort: a storage hiccup must not leave the
 * user unable to delete their account (failures are logged for follow-up).
 *
 * Returns false if the worker does not exist.
 */
export async function deleteWorkerAccount(workerId: number): Promise<boolean> {
  const [worker] = await db
    .select({
      id: workers.id,
      governmentIdId: workers.verificationGovernmentIdId,
      proofOfAddressId: workers.verificationProofOfAddressId,
    })
    .from(workers)
    .where(eq(workers.id, workerId))
    .limit(1)

  if (!worker) return false

  try {
    const { blobs } = await list({ prefix: `worker-docs/${workerId}/` })
    if (blobs.length > 0) {
      await del(blobs.map((b) => b.url))
    }
  } catch (error) {
    console.error(`Failed to delete verification documents for worker ${workerId}:`, error)
  }

  const mediaIds = [worker.governmentIdId, worker.proofOfAddressId].filter(
    (id): id is number => id != null
  )
  if (mediaIds.length > 0) {
    try {
      await db.delete(media).where(inArray(media.id, mediaIds))
    } catch (error) {
      console.error(`Failed to delete media rows for worker ${workerId}:`, error)
    }
  }

  await db.delete(workers).where(eq(workers.id, workerId))
  return true
}
