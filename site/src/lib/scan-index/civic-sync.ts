/**
 * Chicago 311 mirror: GCC-relevant service requests from the Data Portal
 * (dataset `v6vf-nfxy`, SODA API) into `civic_reports`, keyed by the city's own
 * `sr_number` (docs/superpowers/specs/2026-07-10-chicago-311-civic-loop-design.md §4).
 * Driven by GET /api/cron/civic-sync.
 *
 * Port of backend/…/CivicReportSync.kt, frozen since 2026-09-08 when that
 * process stopped running anywhere. Same rules:
 *
 * - Watermark = MAX(last_modified_date) in the table itself (no state row);
 *   empty table → now − backfill days. Rows with last_modified_date strictly
 *   after it are fetched in order, 1000 per page.
 * - No status filter: Open → Completed transitions update in place; readers
 *   filter status = 'Open'.
 * - The portal publishes floating (zone-less) Chicago timestamps. They are
 *   stored as if UTC and passed back in the same form, so comparisons stay
 *   internally consistent — the stored instants are NOT true UTC.
 * - location_cell_id comes from gridCell() — the same function the app uses,
 *   byte-equal to the shared Kotlin gridCell10m.
 * - Stop on an empty page, a short page, a page that doesn't move the
 *   watermark, or the page cap. Added here: a time budget, so a backlog drains
 *   across runs instead of overrunning the function's maxDuration.
 */
import { gridCell } from '@/lib/world-observations'

export const SODA_URL = 'https://data.cityofchicago.org/resource/v6vf-nfxy.json'
export const PAGE_SIZE = 1000
export const MAX_PAGES_PER_SWEEP = 50
const REQUEST_TIMEOUT_MS = 60_000

export const DEFAULT_CIVIC_SR_TYPES = [
  'Graffiti Removal Request',
  'Fly Dumping Complaint',
  'Sanitation Code Violation',
  'Weed Removal Request',
  'Clean Vacant Lot Request',
  'Street Cleaning Request',
  'Tree Debris Clean-Up Request',
]

export interface CivicRow {
  srNumber: string
  srType: string
  status: string
  ownerDepartment: string | null
  createdDateMs: number | null
  lastModifiedDateMs: number | null
  closedDateMs: number | null
  streetAddress: string | null
  communityArea: number | null
  lat: number | null
  lng: number | null
  locationCellId: string | null
}

export interface CivicSyncDeps {
  /** MAX(last_modified_date) in epoch ms, or null when the table is empty. */
  currentWatermarkMs(): Promise<number | null>
  /** GET a SODA URL within `timeoutMs`; `ok: false` on a non-200 (the caller stops, as Kotlin did). */
  fetchJson(url: string, timeoutMs: number): Promise<{ ok: true; body: unknown } | { ok: false; status: number; text: string }>
  /** Upsert on sr_number; returns rows written. Rows are unique on srNumber. */
  upsert(rows: CivicRow[]): Promise<number>
}

export interface CivicSyncOptions {
  srTypes?: string[]
  backfillDays?: number
  /** Start no new page after this epoch-ms. */
  deadlineMs: number
  /** No request may still be in flight at this epoch-ms (the function's own limit). */
  hardDeadlineMs?: number
  maxPages?: number
  now?: () => number
}

export interface CivicSyncResult {
  fetched: number
  upserted: number
  pages: number
  watermark: string
  /** True when the sweep reached the end of the feed (not cut by the budget or page cap). */
  caughtUp: boolean
  httpError: { status: number; body: string } | null
  durationMs: number
}

/** `2026-10-18T14:03:07` — the floating literal SODA compares against. */
export function sodaLiteral(ms: number): string {
  return new Date(Math.floor(ms / 1000) * 1000).toISOString().slice(0, 19)
}

export function buildPageUrl(srTypes: string[], sinceMs: number, base = SODA_URL): string {
  const typeList = srTypes.map((t) => `'${t.replace(/'/g, "''")}'`).join(',')
  const where = `sr_type in(${typeList}) AND last_modified_date > '${sodaLiteral(sinceMs)}'`
  const q = new URLSearchParams({ $where: where, $order: 'last_modified_date', $limit: String(PAGE_SIZE) })
  return `${base}?${q.toString()}`
}

/**
 * Kotlin `(obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }`.
 * JSON null is a JsonPrimitive whose content is "null", so it comes through as
 * the string "null" — kept for parity; the portal omits null fields anyway.
 */
function str(o: Record<string, unknown>, key: string): string | null {
  const v = o[key]
  let s: string
  if (v === null) s = 'null'
  else if (typeof v === 'string') s = v
  else if (typeof v === 'number' || typeof v === 'boolean') s = String(v)
  else return null
  return s.trim() === '' ? null : s
}

const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?Z$/

/** Floating portal timestamp → epoch ms, read as UTC (Kotlin appended "Z"). */
export function parseFloating(s: string | null): number | null {
  if (s == null) return null
  const z = s.endsWith('Z') ? s : `${s}Z`
  if (!ISO.test(z)) return null
  // Date.parse keeps milliseconds; trim sub-ms digits it may reject.
  const ms = Date.parse(z.replace(/(\.\d{3})\d+Z$/, '$1Z'))
  return Number.isFinite(ms) ? ms : null
}

function toDoubleOrNull(s: string | null): number | null {
  if (s == null || !/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(s)) return null
  return Number(s)
}

function toIntOrNull(s: string | null): number | null {
  if (s == null || !/^[+-]?\d+$/.test(s)) return null
  const n = Number(s)
  return n >= -2147483648 && n <= 2147483647 ? n : null
}

/** One SODA row → a civic_reports row, or null when sr_number/sr_type is missing. */
export function parseSodaRow(el: unknown): CivicRow | null {
  if (el === null || typeof el !== 'object' || Array.isArray(el)) return null
  const o = el as Record<string, unknown>
  const srNumber = str(o, 'sr_number')
  const srType = str(o, 'sr_type')
  if (srNumber == null || srType == null) return null
  const lat = toDoubleOrNull(str(o, 'latitude'))
  const lng = toDoubleOrNull(str(o, 'longitude'))
  return {
    srNumber,
    srType: srType.slice(0, 100),
    status: (str(o, 'status') ?? 'Unknown').slice(0, 50),
    ownerDepartment: str(o, 'owner_department')?.slice(0, 100) ?? null,
    createdDateMs: parseFloating(str(o, 'created_date')),
    lastModifiedDateMs: parseFloating(str(o, 'last_modified_date')),
    closedDateMs: parseFloating(str(o, 'closed_date')),
    streetAddress: str(o, 'street_address')?.slice(0, 200) ?? null,
    communityArea: toIntOrNull(str(o, 'community_area')),
    lat,
    lng,
    locationCellId: lat != null && lng != null ? gridCell(lat, lng) : null,
  }
}

/** One row per sr_number, last occurrence wins (the Kotlin batch's end state). */
export function dedupeBySr(rows: CivicRow[]): CivicRow[] {
  const m = new Map<string, CivicRow>()
  for (const r of rows) {
    m.delete(r.srNumber)
    m.set(r.srNumber, r)
  }
  return [...m.values()]
}

export async function runCivicSync(deps: CivicSyncDeps, opts: CivicSyncOptions): Promise<CivicSyncResult> {
  const now = opts.now ?? Date.now
  const start = now()
  const srTypes = opts.srTypes ?? DEFAULT_CIVIC_SR_TYPES
  const maxPages = opts.maxPages ?? MAX_PAGES_PER_SWEEP
  let watermark = (await deps.currentWatermarkMs()) ?? start - (opts.backfillDays ?? 90) * 86_400_000
  const result: CivicSyncResult = {
    fetched: 0, upserted: 0, pages: 0, watermark: '', caughtUp: false, httpError: null, durationMs: 0,
  }

  while (result.pages < maxPages && now() < opts.deadlineMs) {
    // The portal usually answers a page in ~1s but has been seen taking
    // 30–70s; never let one request outlive the function (Kotlin capped each
    // request at 60s).
    const timeoutMs = Math.max(1_000, Math.min(REQUEST_TIMEOUT_MS, (opts.hardDeadlineMs ?? Infinity) - now()))
    const res = await deps.fetchJson(buildPageUrl(srTypes, watermark), timeoutMs)
    if (!res.ok) {
      result.httpError = { status: res.status, body: res.text.slice(0, 200) }
      break
    }
    const rows = Array.isArray(res.body)
      ? res.body.map(parseSodaRow).filter((r): r is CivicRow => r !== null)
      : []
    if (rows.length === 0) {
      result.caughtUp = true
      break
    }
    result.pages++
    result.fetched += rows.length
    result.upserted += await deps.upsert(dedupeBySr(rows))
    const modified = rows.map((r) => r.lastModifiedDateMs).filter((m): m is number => m != null)
    if (modified.length === 0) break
    const maxModified = Math.max(...modified)
    if (maxModified <= watermark) break // no forward progress — stop rather than spin
    watermark = maxModified
    if (rows.length < PAGE_SIZE) {
      result.caughtUp = true
      break
    }
  }

  result.watermark = new Date(watermark).toISOString()
  result.durationMs = now() - start
  return result
}
