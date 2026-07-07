import { NextResponse } from 'next/server'
import { db } from '@/db'
import { crashReports } from '@/db/schema'
import { and, eq, gt, sql } from 'drizzle-orm'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

// Stack traces can be arbitrarily large (Kotlin/Native aborts, MetricKit
// dumps) — cap what we persist so one bad report can't blow up storage.
const MAX_STACK_TRACE_LENGTH = 64 * 1024
const MAX_EXCEPTION_MESSAGE_LENGTH = 4 * 1024
const MAX_SHORT_FIELD_LENGTH = 256

// Soft per-device volume cap: this endpoint is unauthenticated (see below),
// so without a throttle a single misbehaving or malicious device could
// flood the table with 64KB rows. A real device reports at most roughly
// once per launch (the client sends the previous session's crash on
// startup), so 25/hour is far beyond legitimate traffic. deviceId is
// required by validation below, so it is always the throttle key; it is
// client-asserted, though, so this is abuse *dampening*, not a security
// boundary — a determined attacker can rotate deviceIds. One bounded
// count query per insert.
const MAX_REPORTS_PER_DEVICE_PER_HOUR = 25

function boundedString(value: unknown, maxLength: number): string | null {
  if (typeof value !== 'string' || value.length === 0) return null
  return value.length > maxLength ? value.slice(0, maxLength) : value
}

/**
 * Mobile crash telemetry ingest. Deliberately unauthenticated — a crash can
 * (and often does) happen before/without a logged-in session, and dropping
 * the report for auth reasons defeats the point of collecting it. The
 * crash_reports table (see admin viewer at /api/admin/crashes) has no
 * workerId column, so there's nothing an auth check would gate here; body
 * validation (bounded lengths below) plus the per-device throttle above is
 * what protects this endpoint.
 *
 * Success responses use the ApiResponse envelope ({data, success}) because
 * the deployed client decodes them via GccApiClient.postJson, whose
 * ApiResponse<T>.data field is REQUIRED (no default) — a body without
 * `data` throws client-side even on HTTP 200, and the client would then
 * keep its local crash file and re-upload it every launch. kotlinx decodes
 * Unit from an empty object, so `data: {}` is the correct payload. Error
 * responses don't need the envelope: the client throws on non-2xx before
 * ever parsing the body.
 */
export async function POST(request: Request) {
  try {
    const body = await request.json().catch(() => null)
    if (!body || typeof body !== 'object') {
      return NextResponse.json({ success: false, error: 'Invalid body' }, { status: 400 })
    }

    const deviceId = boundedString(body.deviceId, MAX_SHORT_FIELD_LENGTH)
    const platform = boundedString(body.platform, MAX_SHORT_FIELD_LENGTH)
    const exceptionMessage = boundedString(body.exceptionMessage, MAX_EXCEPTION_MESSAGE_LENGTH)
    const stackTrace = boundedString(body.stackTrace, MAX_STACK_TRACE_LENGTH)
    const appVersion = boundedString(body.appVersion, MAX_SHORT_FIELD_LENGTH)

    if (!deviceId || !platform || !exceptionMessage || !stackTrace) {
      return NextResponse.json(
        { success: false, error: 'deviceId, platform, exceptionMessage, and stackTrace are required' },
        { status: 400 }
      )
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

    const occurredAtDate = new Date(typeof body.occurredAt === 'string' ? body.occurredAt : '')
    const occurredAt = Number.isFinite(occurredAtDate.getTime())
      ? occurredAtDate.toISOString()
      : new Date().toISOString()

    await db.insert(crashReports).values({
      deviceId,
      platform,
      appVersion,
      exceptionMessage,
      stackTrace,
      occurredAt,
    })

    return NextResponse.json({ data: {}, success: true })
  } catch (error) {
    console.error('POST /api/crashes error:', error)
    // Still never surface a hard failure that would make the client retry
    // forever for a malformed-but-harmless report; only genuine server
    // errors (DB down, etc.) reach here.
    return NextResponse.json({ success: false, error: 'Failed to record crash report' }, { status: 500 })
  }
}
