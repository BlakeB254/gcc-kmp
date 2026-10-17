import { del, list } from '@vercel/blob'
import { DeleteObjectsCommand, ListObjectsV2Command } from '@aws-sdk/client-s3'
import { eq, inArray } from 'drizzle-orm'
import { db } from '@/db'
import { workers, media } from '@/db/schema'
import { isR2Configured, r2BucketName, r2Client } from '@/lib/r2'

/** Delete every private R2 object under `verification/{workerId}/`. */
async function deleteR2VerificationDocs(workerId: number): Promise<void> {
  if (!isR2Configured()) return
  const Bucket = r2BucketName()
  let ContinuationToken: string | undefined
  do {
    const page = await r2Client().send(
      new ListObjectsV2Command({ Bucket, Prefix: `verification/${workerId}/`, ContinuationToken }),
    )
    const objects = (page.Contents ?? []).flatMap((o) => (o.Key ? [{ Key: o.Key }] : []))
    if (objects.length > 0) {
      await r2Client().send(new DeleteObjectsCommand({ Bucket, Delete: { Objects: objects, Quiet: true } }))
    }
    ContinuationToken = page.IsTruncated ? page.NextContinuationToken : undefined
  } while (ContinuationToken)
}

/**
 * Delete a worker account and its PII side-effects. Verification documents
 * (government ID, proof of address) live privately in R2 under
 * `verification/{workerId}/`; older web uploads also live in *public* Vercel
 * Blob storage under `worker-docs/{workerId}/`. Both have matching `media`
 * rows — leaving any of them behind after account deletion would keep
 * identity documents on file (and, for Blob, reachable at public URLs).
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
      addressChangeProofId: workers.pendingAddressChangeProofOfAddressId,
    })
    .from(workers)
    .where(eq(workers.id, workerId))
    .limit(1)

  if (!worker) return false

  // Legacy public Blob copies: onboarding docs and address-change proofs.
  for (const prefix of [`worker-docs/${workerId}/`, `workers/${workerId}/address-change/`]) {
    try {
      let cursor: string | undefined
      do {
        const page = await list({ prefix, cursor })
        if (page.blobs.length > 0) {
          await del(page.blobs.map((b) => b.url))
        }
        cursor = page.hasMore ? page.cursor : undefined
      } while (cursor)
    } catch (error) {
      console.error(`Failed to delete public documents under ${prefix}:`, error)
    }
  }

  try {
    await deleteR2VerificationDocs(workerId)
  } catch (error) {
    console.error(`Failed to delete private R2 verification documents for worker ${workerId}:`, error)
  }

  const mediaIds = [worker.governmentIdId, worker.proofOfAddressId, worker.addressChangeProofId].filter(
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
