/**
 * Headline metrics from one session's flight-recorder event stream
 * (`events.jsonl.gz`, scan-session-replay PRD Phase 1 §3), extracted at index
 * time and mirrored into `scan_sessions` (migration 0037).
 *
 * Port of backend/…/EventLogMetrics.kt. A line counts only if it decodes as the
 * shared `ScanSessionEvent` would under `ScanEventJson` (lenient, unknown keys
 * ignored): an object with an integer `t` and a `type`. Torn or corrupt lines
 * are skipped; unknown event types still count toward `eventCount`. Gate flips
 * are transitions between DISTINCT consecutive gate states — the first gate
 * event sets the baseline and is not itself a flip.
 */
import { gunzipSync } from 'node:zlib'

export interface EventLogMetrics {
  eventCount: number
  gateFlipCount: number
  tapCount: number
  confirmCount: number
  rejectCount: number
  raycastMissCount: number
  detectionCount: number
  uploadAttemptCount: number
  uploadFailureCount: number
  errorCount: number
}

/**
 * Size limits for an event log. The client contract is <200KB gzipped (real
 * logs are under 10KB), so these only stop a hostile or broken upload — a
 * decompression bomb — from exhausting the function's memory.
 */
export const MAX_EVENT_LOG_BYTES = 2 * 1024 * 1024
export const MAX_EVENT_LOG_INFLATED_BYTES = 64 * 1024 * 1024

/** The log can never be read: too large, not gzip, or inflates past the cap. Retrying won't help. */
export class EventLogUnreadable extends Error {}

/** Gunzip an `events.jsonl.gz` artifact into its non-blank JSONL lines. */
export function gunzipLines(gz: Uint8Array): string[] {
  if (gz.byteLength > MAX_EVENT_LOG_BYTES) throw new EventLogUnreadable(`event log is ${gz.byteLength} bytes`)
  let raw: Buffer
  try {
    raw = gunzipSync(gz, { maxOutputLength: MAX_EVENT_LOG_INFLATED_BYTES })
  } catch (e) {
    throw new EventLogUnreadable(`cannot inflate event log: ${(e as Error).message}`)
  }
  return raw.toString('utf8').split('\n').filter((l) => l.trim() !== '')
}

interface DecodedEvent {
  type: string
  state: string | null
  ok: boolean | null
}

const isPrimitive = (v: unknown) => v === null || ['string', 'number', 'boolean'].includes(typeof v)

/** Kotlin Long/Int accept a JSON integer (or, lenient, a quoted one). */
function isIntegral(v: unknown): boolean {
  if (typeof v === 'number') return Number.isInteger(v)
  return typeof v === 'string' && /^-?\d+$/.test(v)
}

function isNumeric(v: unknown): boolean {
  if (typeof v === 'number') return Number.isFinite(v)
  return typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))
}

const STRING_FIELDS = ['msg', 'label', 'state', 'tag', 'key', 'source', 'outcome'] as const

/** Decode one line the way `ScanEventJson.decodeFromString(ScanSessionEvent)` would, or null. */
export function decodeEvent(line: string): DecodedEvent | null {
  let o: unknown
  try {
    o = JSON.parse(line)
  } catch {
    return null
  }
  if (o === null || typeof o !== 'object' || Array.isArray(o)) return null
  const e = o as Record<string, unknown>
  if (!isIntegral(e.t)) return null
  if (e.type === undefined || e.type === null || !isPrimitive(e.type)) return null
  for (const f of STRING_FIELDS) if (e[f] !== undefined && !isPrimitive(e[f])) return null
  if (e.confidence != null && !isNumeric(e.confidence)) return null
  if (e.distanceM != null && !isNumeric(e.distanceM)) return null
  if (e.pct != null && !isIntegral(e.pct)) return null
  let ok: boolean | null = null
  if (e.ok != null) {
    if (e.ok === true || e.ok === 'true') ok = true
    else if (e.ok === false || e.ok === 'false') ok = false
    else return null
  }
  return { type: String(e.type), state: e.state == null ? null : String(e.state), ok }
}

export function extractEventLogMetrics(lines: Iterable<string>): EventLogMetrics {
  const m: EventLogMetrics = {
    eventCount: 0,
    gateFlipCount: 0,
    tapCount: 0,
    confirmCount: 0,
    rejectCount: 0,
    raycastMissCount: 0,
    detectionCount: 0,
    uploadAttemptCount: 0,
    uploadFailureCount: 0,
    errorCount: 0,
  }
  let lastGateState: string | null = null
  for (const line of lines) {
    const event = decodeEvent(line)
    if (!event) continue
    m.eventCount++
    switch (event.type) {
      case 'gate':
        if (event.state != null && lastGateState != null && event.state !== lastGateState) m.gateFlipCount++
        if (event.state != null) lastGateState = event.state
        break
      case 'tap': m.tapCount++; break
      case 'confirm': m.confirmCount++; break
      case 'reject': m.rejectCount++; break
      case 'pill': if (event.state === 'NO_RAYCAST') m.raycastMissCount++; break
      case 'detection': m.detectionCount++; break
      case 'upload':
        m.uploadAttemptCount++
        if (event.ok === false) m.uploadFailureCount++
        break
      case 'diag': if (event.state === 'ERROR') m.errorCount++; break
    }
  }
  return m
}
