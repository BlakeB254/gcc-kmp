import { NextResponse } from 'next/server'
import { PutObjectCommand } from '@aws-sdk/client-s3'
import { db } from '@/db'
import { crashReports } from '@/db/schema'
import { and, eq, gt, sql } from 'drizzle-orm'
import { isR2Configured, metricKitDiagnosticKey, r2BucketName, r2Client } from '@/lib/r2'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

// MetricKit crash diagnostics run tens of KB; hang diagnostics with deep call
// trees can exceed 100KB. 1MB bounds a hostile payload without truncating any
// legitimate one.
const MAX_PAYLOAD_LENGTH = 1024 * 1024
const MAX_SHORT_FIELD_LENGTH = 256
// The full payload lives in R2; the crash_reports row keeps a truncated head
// so the existing admin crashes viewer shows something useful inline.
const STACK_TRACE_SNIPPET_LENGTH = 64 * 1024

// Same throttle model as POST /api/crashes (see that route's rationale):
// unauthenticated ingest, deviceId-keyed dampening, shared crash_reports
// window — a device flooding either endpoint hits one combined cap.
const MAX_REPORTS_PER_DEVICE_PER_HOUR = 25

function boundedString(value: unknown, maxLength: number): string | null {
  if (typeof value !== 'string' || value.length === 0) return null
  return value.length > maxLength ? value.slice(0, maxLength) : value
}

/**
 * The on-device filename is `diagnostic-<ISO8601 with ':'→'-'>-<n>.json`
 * (CrashDiagnostics.swift) — recover the MetricKit delivery time from it so
 * `occurredAt` reflects when the diagnostic arrived, not when it finally
 * uploaded (possibly many launches later).
 */
function occurredAtFromFileName(fileName: string): string | null {
  const m = fileName.match(/^diagnostic-(\d{4}-\d{2}-\d{2})T(\d{2})-(\d{2})-(\d{2})Z/)
  if (!m) return null
  const date = new Date(`${m[1]}T${m[2]}:${m[3]}:${m[4]}Z`)
  return Number.isFinite(date.getTime()) ? date.toISOString() : null
}

/**
 * MetricKit crash/hang diagnostic ingest (scan-session-replay PRD Phase 1):
 * the JSONs the Swift `CrashDiagnosticsObserver` persists on-device — until
 * now stranded in Files → gcc-crash-diagnostics — upload here on next
 * launch. The full payload lands in R2 (`diagnostics/metrickit/{deviceId}/
 * {fileName}`, retries overwrite) and a `crash_reports` row indexes it
 * (`r2Key` + truncated inline snippet), so the existing admin crashes
 * viewer surfaces them with zero new UI.
 *
 * Unauthenticated like POST /api/crashes (crashes happen before/without a
 * session), and the same envelope contract: success MUST be `{data:{},
 * success:true}` or the deployed client keeps the local file and re-uploads
 * every launch. Returns 501 while R2 is unprovisioned — the client treats
 * that as "keep the file, retry next launch" rather than deleting.
 */
export async function POST(request: Request) {
  try {
    if (!isR2Configured()) {
      return NextResponse.json(
        { data: null, success: false, message: 'Diagnostic storage is not configured yet' },
        { status: 501 }
      )
    }

    const body = await request.json().catch(() => null)
    if (!body || typeof body !== 'object') {
      return NextResponse.json({ success: false, error: 'Invalid body' }, { status: 400 })
    }

    const deviceId = boundedString(body.deviceId, MAX_SHORT_FIELD_LENGTH)
    const platform = boundedString(body.platform, MAX_SHORT_FIELD_LENGTH)
    const appVersion = boundedString(body.appVersion, MAX_SHORT_FIELD_LENGTH)
    const fileName = boundedString(body.fileName, MAX_SHORT_FIELD_LENGTH)
    const payload = typeof body.payload === 'string' ? body.payload : null

    if (!deviceId || !platform || !fileName || !payload) {
      return NextResponse.json(
        { success: false, error: 'deviceId, platform, fileName, and payload are required' },
        { status: 400 }
      )
    }
    if (payload.length > MAX_PAYLOAD_LENGTH) {
      // Deliberately a 4xx the client treats as permanent — a pathological
      // payload must not wedge the retry loop forever.
      return NextResponse.json({ success: false, error: 'payload too large' }, { status: 413 })
    }

    let r2Key: string
    try {
      r2Key = metricKitDiagnosticKey(deviceId, fileName)
    } catch {
      return NextResponse.json({ success: false, error: 'Invalid fileName' }, { status: 400 })
    }

    const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString()
    const [recent] = await db
      .select({ count: sql<number>`count(*)` })
      .from(crashReports)
      .where(and(
        eq(crashReports.deviceId, deviceId),
        gt(crashReports.createdAt, oneHourAgo),
      ))
    if ((Number(recent?.count) || 0) >= MAX_REPORTS_PER_DEVICE_PER_HOUR) {
      return NextResponse.json(
        { success: false, error: 'Rate limit exceeded: too many crash reports from this device' },
        { status: 429 }
      )
    }

    await r2Client().send(
      new PutObjectCommand({
        Bucket: r2BucketName(),
        Key: r2Key,
        Body: payload,
        ContentType: 'application/json',
      })
    )

    await db.insert(crashReports).values({
      deviceId,
      platform,
      appVersion,
      exceptionMessage: `MetricKit diagnostic: ${fileName}`,
      stackTrace: payload.length > STACK_TRACE_SNIPPET_LENGTH
        ? payload.slice(0, STACK_TRACE_SNIPPET_LENGTH)
        : payload,
      occurredAt: occurredAtFromFileName(fileName) ?? new Date().toISOString(),
      r2Key,
    })

    return NextResponse.json({ data: {}, success: true })
  } catch (error) {
    console.error('POST /api/crashes/diagnostics error:', error)
    return NextResponse.json({ success: false, error: 'Failed to record diagnostic' }, { status: 500 })
  }
}
