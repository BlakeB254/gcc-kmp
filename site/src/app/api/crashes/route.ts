import { NextResponse } from 'next/server'
import { db } from '@/db'
import { crashReports } from '@/db/schema'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

// Stack traces can be arbitrarily large (Kotlin/Native aborts, MetricKit
// dumps) — cap what we persist so one bad report can't blow up storage.
const MAX_STACK_TRACE_LENGTH = 64 * 1024
const MAX_EXCEPTION_MESSAGE_LENGTH = 4 * 1024
const MAX_SHORT_FIELD_LENGTH = 256

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
 * validation (bounded lengths below) is what protects this endpoint.
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

    return NextResponse.json({ success: true })
  } catch (error) {
    console.error('POST /api/crashes error:', error)
    // Still never surface a hard failure that would make the client retry
    // forever for a malformed-but-harmless report; only genuine server
    // errors (DB down, etc.) reach here.
    return NextResponse.json({ success: false, error: 'Failed to record crash report' }, { status: 500 })
  }
}
