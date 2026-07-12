import { NextResponse } from 'next/server'
import { GetObjectCommand } from '@aws-sdk/client-s3'
import { getSignedUrl } from '@aws-sdk/s3-request-presigner'
import { gunzipSync } from 'node:zlib'
import { db } from '@/db'
import { captureChunks, scanSessions } from '@/db/schema'
import { asc, eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { isR2Configured, r2BucketName, r2Client } from '@/lib/r2'
import { scanSessionRedFlags } from '@/lib/scan-session-flags'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ sessionId: string }> }

const PRESIGN_TTL_SECONDS = 300
// The manifest carries up to an hour of 1Hz samples (~1MB ceiling); the
// event log is <200KB gzipped by contract. Guard anyway — a corrupt object
// must not balloon the response.
const MAX_ARTIFACT_BYTES = 8 * 1024 * 1024

/** One line of the flight-recorder stream, decoded leniently. */
function parseEventLines(jsonl: string): unknown[] {
  const events: unknown[] = []
  for (const line of jsonl.split('\n')) {
    if (!line.trim()) continue
    try {
      events.push(JSON.parse(line))
    } catch {
      // torn line from a crash-salvaged log — skip, keep the rest
    }
  }
  return events
}

async function fetchR2Object(key: string): Promise<Buffer | null> {
  try {
    const response = await r2Client().send(
      new GetObjectCommand({ Bucket: r2BucketName(), Key: key })
    )
    const bytes = Buffer.from(await response.Body!.transformToByteArray())
    return bytes.length > MAX_ARTIFACT_BYTES ? null : bytes
  } catch (error) {
    console.warn(`[admin/scan-sessions] R2 fetch failed for ${key}:`, error)
    return null
  }
}

/**
 * Admin scan-session detail (scan-session-replay PRD Phase 2): everything
 * the replay page needs in one round-trip —
 *  - the indexed session row + its capture_chunks,
 *  - the manifest JSON (GPS path, observed items, gate events, diagnostics
 *    tail) fetched server-side from R2,
 *  - the flight-recorder event stream, gunzipped and parsed server-side
 *    (spares the browser CORS + DecompressionStream),
 *  - short-lived presigned GET URLs per chunk for artifact download /
 *    future video playback. Admin-only (PRD binding decision §1); imagery
 *    stays behind these expiring admin-only URLs until the DGX scrub pass
 *    exists (§2 mitigation).
 */
export async function GET(request: Request, context: RouteContext) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { sessionId } = await context.params
    const [session] = await db
      .select()
      .from(scanSessions)
      .where(eq(scanSessions.sessionId, sessionId))
      .limit(1)
    if (!session) {
      return NextResponse.json({ error: 'Session not found' }, { status: 404 })
    }

    const chunks = await db
      .select()
      .from(captureChunks)
      .where(eq(captureChunks.sessionId, sessionId))
      .orderBy(asc(captureChunks.r2Key))

    let manifest: unknown = null
    let events: unknown[] | null = null
    let chunkUrls: Record<string, string> = {}

    if (isR2Configured()) {
      const manifestBytes = await fetchR2Object(session.manifestR2Key)
      if (manifestBytes) {
        try {
          manifest = JSON.parse(manifestBytes.toString('utf-8'))
        } catch {
          manifest = null
        }
      }
      if (session.eventLogR2Key) {
        const gz = await fetchR2Object(session.eventLogR2Key)
        if (gz) {
          try {
            events = parseEventLines(gunzipSync(gz).toString('utf-8'))
          } catch {
            events = null
          }
        }
      }
      chunkUrls = Object.fromEntries(
        await Promise.all(
          chunks.map(async (chunk) => [
            chunk.r2Key,
            await getSignedUrl(
              r2Client(),
              new GetObjectCommand({ Bucket: r2BucketName(), Key: chunk.r2Key }),
              { expiresIn: PRESIGN_TTL_SECONDS }
            ),
          ] as const)
        )
      )
    }

    return NextResponse.json({
      data: {
        // Same server-derived flag set as the list route — both admin
        // clients (site + iOS) render identical flags.
        session: { ...session, redFlags: scanSessionRedFlags(session) },
        chunks: chunks.map((chunk) => ({ ...chunk, url: chunkUrls[chunk.r2Key] ?? null })),
        manifest,
        events,
        presignExpiresInSeconds: PRESIGN_TTL_SECONDS,
      },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('GET /api/admin/scan-sessions/[sessionId] error:', error)
    return NextResponse.json({ error: 'Failed to fetch scan session' }, { status: 500 })
  }
}
