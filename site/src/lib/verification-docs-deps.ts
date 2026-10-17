/**
 * Production bindings for legacy-verification-docs.ts and
 * verification-retention.ts: Vercel Blob, private R2 and the media/workers
 * tables. Kept apart from the logic so both stay unit-testable with fakes.
 */
import { del, list } from '@vercel/blob'
import { DeleteObjectCommand, HeadObjectCommand, ListObjectsV2Command, PutObjectCommand } from '@aws-sdk/client-s3'
import { and, eq, inArray, like, or, sql } from 'drizzle-orm'
import { db } from '@/db'
import { media, workers } from '@/db/schema'
import { r2BucketName, r2Client } from '@/lib/r2'
import type { BlobEntry, LegacyMediaRow, MigrationDeps } from '@/lib/legacy-verification-docs'
import type { RetentionDeps } from '@/lib/verification-retention'

const PUBLIC_BLOB_LIKE = 'https://%.public.blob.vercel-storage.com/%'
const CHUNK = 500

function chunks<T>(xs: T[]): T[][] {
  const out: T[][] = []
  for (let i = 0; i < xs.length; i += CHUNK) out.push(xs.slice(i, i + CHUNK))
  return out
}

export const migrationDeps: MigrationDeps = {
  async listLegacyMediaRows(): Promise<LegacyMediaRow[]> {
    const rows = await db
      .select({ id: media.id, url: media.url, mimeType: media.mimeType })
      .from(media)
      .where(and(like(media.url, PUBLIC_BLOB_LIKE), inArray(media.category, ['worker_id', 'proof_of_address'])))
    if (rows.length === 0) return []
    const ids = rows.map((r) => r.id)
    const owners = await db
      .select({
        id: workers.id,
        gov: workers.verificationGovernmentIdId,
        poa: workers.verificationProofOfAddressId,
        addr: workers.pendingAddressChangeProofOfAddressId,
      })
      .from(workers)
      .where(or(
        inArray(workers.verificationGovernmentIdId, ids),
        inArray(workers.verificationProofOfAddressId, ids),
        inArray(workers.pendingAddressChangeProofOfAddressId, ids),
      ))
    const ownerOf = new Map<number, number>()
    for (const o of owners) for (const m of [o.gov, o.poa, o.addr]) if (m != null) ownerOf.set(m, o.id)
    return rows
      .filter((r): r is { id: number; url: string; mimeType: string | null } => typeof r.url === 'string')
      .map((r) => ({ ...r, workerId: ownerOf.get(r.id) ?? null }))
  },

  async listAllMediaUrls() {
    const rows = await db.select({ url: media.url }).from(media).where(like(media.url, PUBLIC_BLOB_LIKE))
    return new Set(rows.map((r) => r.url).filter((u): u is string => !!u))
  },

  async listBlobs(prefix): Promise<BlobEntry[]> {
    const out: BlobEntry[] = []
    let cursor: string | undefined
    do {
      const page = await list({ prefix, cursor, limit: 1000 })
      out.push(...page.blobs.map((b) => ({ url: b.url, pathname: b.pathname })))
      cursor = page.hasMore ? page.cursor : undefined
    } while (cursor)
    return out
  },

  async fetchPublic(url) {
    const res = await fetch(url, { cache: 'no-store' })
    if (res.status === 404) return null
    if (!res.ok) throw new Error(`fetch ${res.status}`)
    return { bytes: Buffer.from(await res.arrayBuffer()), contentType: res.headers.get('content-type') }
  },

  async putPrivate(key, bytes, contentType) {
    await r2Client().send(new PutObjectCommand({ Bucket: r2BucketName(), Key: key, Body: bytes, ContentType: contentType }))
  },

  async headPrivate(key) {
    try {
      const h = await r2Client().send(new HeadObjectCommand({ Bucket: r2BucketName(), Key: key }))
      return { size: Number(h.ContentLength ?? -1), etag: h.ETag ?? null }
    } catch {
      return null
    }
  },

  async repointMedia(id, fromUrl, toKey) {
    const rows = await db
      .update(media)
      .set({ url: toKey, updatedAt: new Date().toISOString() })
      .where(and(eq(media.id, id), eq(media.url, fromUrl)))
      .returning({ id: media.id })
    return rows.length > 0
  },

  async isUrlReferenced(url) {
    const rows = await db.select({ id: media.id }).from(media).where(eq(media.url, url)).limit(1)
    return rows.length > 0
  },

  async deletePublic(url) {
    await del(url)
  },
}

export const retentionDeps: RetentionDeps = {
  async listPrivate(prefix) {
    const out: { key: string; lastModifiedMs: number }[] = []
    let ContinuationToken: string | undefined
    do {
      const page = await r2Client().send(
        new ListObjectsV2Command({ Bucket: r2BucketName(), Prefix: prefix, ContinuationToken }),
      )
      for (const o of page.Contents ?? []) {
        if (o.Key) out.push({ key: o.Key, lastModifiedMs: o.LastModified ? o.LastModified.getTime() : 0 })
      }
      ContinuationToken = page.IsTruncated ? page.NextContinuationToken : undefined
    } while (ContinuationToken)
    return out
  },

  async reviewProtectedWorkers(ids) {
    const found = new Set<number>()
    for (const part of chunks(ids)) {
      const rows = await db
        .select({ id: workers.id })
        .from(workers)
        .where(and(
          inArray(workers.id, part),
          or(
            inArray(workers.verificationStatus, ['pending', 'needs_info']),
            inArray(workers.verificationGovernmentIdStatus, ['pending', 'resubmit']),
            inArray(workers.verificationProofOfAddressStatus, ['pending', 'resubmit']),
            eq(workers.pendingAddressChangeStatus, 'pending'),
          ),
        ))
      for (const r of rows) found.add(r.id)
    }
    return found
  },

  async referencedKeys(keys) {
    const found = new Set<string>()
    for (const part of chunks(keys)) {
      const rows = await db.select({ url: media.url }).from(media).where(inArray(media.url, part))
      for (const r of rows) if (r.url) found.add(r.url)
    }
    return found
  },

  async tombstone(key) {
    const rows = await db
      .update(media)
      .set({ url: sql`'purged:' || ${media.url}`, updatedAt: new Date().toISOString() })
      .where(eq(media.url, key))
      .returning({ id: media.id })
    return rows.length
  },

  async deletePrivate(key) {
    await r2Client().send(new DeleteObjectCommand({ Bucket: r2BucketName(), Key: key }))
  },
}
