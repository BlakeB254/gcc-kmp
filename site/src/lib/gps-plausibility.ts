/**
 * Server-side plausibility filter for the GPS fixes behind coverage events.
 *
 * POST /api/scans/coverage used to record every well-formed event, and
 * route-scan completion (lib/scan-verification.ts) computes its 0.9 route
 * coverage from those events — so a client could post fixes it never walked.
 * This filter cannot prove a fix is real (a careful fake walking at a
 * walking pace passes), but it removes what no phone on foot or bike can
 * produce: impossible speed between consecutive fixes, timestamps in the
 * future, duplicate-timestamp bursts, and fixes the OS marked as mocked.
 *
 * Implausible fixes are DROPPED, never the whole batch: real GPS has jitter
 * spikes and losing one fix costs a worker nothing, while refusing a batch
 * would make the client's queue retry it forever. Dropped fixes are never
 * written, so completion coverage is computed from kept fixes only.
 *
 * Each coverage event is one GPS fix (RouteScanViewModel / SpotScreen enqueue
 * one per location update) — not a ray hit — so consecutive events from one
 * device form a trajectory and speed between them is meaningful. The rate is
 * NOT a steady 1 Hz: iOS runs with kCLDistanceFilterNone and Android replays
 * getLastKnownLocation first, so a stream can open with a cached fix from
 * somewhere else and contain bursts. Both are handled below: bursts by the
 * duplicate rule and the interval-scaled slack, a bad first fix by
 * re-anchoring (REANCHOR_RUN).
 *
 * Pure module — no `@/db` import — so `tsx --test` loads it without a DB.
 */
import { METERS_PER_DEG_LAT, METERS_PER_DEG_LNG } from '@/lib/geo-utils'

/**
 * Fastest plausible movement between consecutive fixes, m/s. 8 m/s is
 * 28.8 km/h: above a brisk cycling pace and far above walking (the client
 * already warns at 1.8 m/s — RouteScanViewModel "movingTooFast"), below any
 * car moving with traffic. Conservative on purpose: a real worker is never
 * near it, so it only removes jumps and vehicle-speed tracks.
 */
export const MAX_PLAUSIBLE_SPEED_MPS = 8

/**
 * Distance forgiven before speed is measured, metres, for fixes a second or
 * more apart. Two honest fixes a second apart can disagree by a few metres
 * of GPS noise; without slack that noise alone would read as running speed.
 * 5 m keeps a car at city speed (≥ 13 m/s ⇒ ≥ 8 m/s after slack at 1 Hz)
 * above the ceiling. Fixes closer together than a second get a
 * proportionally smaller slack — otherwise a scripted 10 Hz track could
 * move 5 m per 100 ms (50 m/s) and still read as walking.
 */
export const GPS_JITTER_SLACK_METERS = 5

/** Fixes stamped further than this ahead of the server clock are dropped. */
export const MAX_FUTURE_SKEW_MS = 2 * 60 * 1000

/**
 * Two fixes from one device closer together than this are a burst, not a
 * trajectory: location updates arrive about once a second. A burst is also
 * what a resubmitted event looks like, so dropping it prevents double credit.
 */
export const DUPLICATE_TIMESTAMP_MS = 100

/** A scan is flagged suspect once at least this many of its fixes were dropped… */
export const GPS_SUSPECT_MIN_DROPPED = 10
/** …and they are at least this share of all its fixes. */
export const GPS_SUSPECT_DROPPED_SHARE = 0.2

/**
 * When this many consecutive fixes agree with each other but are all too far
 * from the anchor, the ANCHOR is the outlier (typically a cached last-known
 * fix the OS emits before the first real GNSS fix): the trajectory continues
 * from them, and a shorter-than-this anchor-side chain in the batch is
 * dropped as `stale_anchor`. 3 fixes ≈ 3 s at 1 Hz — a single spike or a
 * pair of bad fixes never re-anchors, and a vehicle-speed track never forms
 * an agreeing run.
 */
export const REANCHOR_RUN = 3

export const GPS_DROP_REASONS = ['future', 'duplicate_timestamp', 'speed', 'mock', 'bad_timestamp', 'stale_anchor'] as const
export type GpsDropReason = (typeof GPS_DROP_REASONS)[number]

export type GpsFix = { lat: number; lng: number; capturedAtMs: number }

export type FixInput = {
  lat: number
  lng: number
  capturedAt: string
  /** Mock-location flag from the OS, when the client sends one. */
  mocked?: boolean
}

export type FilterResult = {
  /** Indexes (into the input) of the fixes to record, in input order. */
  kept: number[]
  dropped: Array<{ index: number; reason: GpsDropReason }>
  /**
   * Per kept index: the server-measured distance from the previous KEPT fix
   * of the trajectory (the stored anchor counts), metres. 0 for a fix with no
   * kept predecessor and for the first fix after a re-anchor — the jump from
   * a stale anchor is not walked ground. The route pays
   * min(client distanceMeters, this).
   */
  distanceMeters: Record<number, number>
}

/** Planar distance with the frozen grid constants (docs/CONVENTIONS.md §2). */
export function fixDistanceMeters(a: { lat: number; lng: number }, b: { lat: number; lng: number }): number {
  return Math.hypot((b.lat - a.lat) * METERS_PER_DEG_LAT, (b.lng - a.lng) * METERS_PER_DEG_LNG)
}

/**
 * Whether moving from `prev` to `next` is plausible. `next` must not be
 * earlier than `prev` (callers walk fixes in time order).
 */
export function plausibleStep(prev: GpsFix, next: GpsFix): 'ok' | 'duplicate_timestamp' | 'speed' {
  const dtMs = next.capturedAtMs - prev.capturedAtMs
  if (dtMs < DUPLICATE_TIMESTAMP_MS) return 'duplicate_timestamp'
  const slack = GPS_JITTER_SLACK_METERS * Math.min(1, dtMs / 1000)
  const meters = Math.max(0, fixDistanceMeters(prev, next) - slack)
  return meters / (dtMs / 1000) > MAX_PLAUSIBLE_SPEED_MPS ? 'speed' : 'ok'
}

/** The client's mock-location flag, under either name it might use. */
export function readMockFlag(event: Record<string, unknown>): boolean {
  return event.isMock === true || event.mocked === true || event.isMocked === true
}

/**
 * Filters one device's fixes. `anchor` is that device's most recent fix the
 * server already recorded (it continues the trajectory across batches);
 * fixes in the batch older than the anchor are checked against the batch
 * fixes before them. Each fix is compared with the last KEPT fix, so one
 * jitter spike is dropped without dragging the next honest fix down with it,
 * and a vehicle-speed track stays dropped for as long as it lasts — but a
 * stale anchor is abandoned once REANCHOR_RUN fixes agree without it.
 */
export function filterDeviceFixes(fixes: FixInput[], anchor: GpsFix | null, nowMs: number): FilterResult {
  const kept = new Set<number>()
  const dropReason = new Map<number, GpsDropReason>()

  const order = fixes
    .map((fix, index) => ({ fix, index, t: Date.parse(fix.capturedAt) }))
    .sort((a, b) => (Number.isNaN(a.t) ? -1 : Number.isNaN(b.t) ? 1 : a.t - b.t || a.index - b.index))

  let prev: GpsFix | null = null
  /** Batch fixes kept since the current anchor was set. */
  let chain: number[] = []
  /** Speed-dropped fixes that agree with each other but not with `prev`. */
  let run: Array<{ index: number; fix: GpsFix }> = []

  const distance = new Map<number, number>()
  const keep = (index: number, fix: GpsFix, walked: boolean) => {
    kept.add(index)
    chain.push(index)
    distance.set(index, walked && prev ? fixDistanceMeters(prev, fix) : 0)
    prev = fix
  }

  for (const { fix, index, t } of order) {
    if (Number.isNaN(t)) {
      dropReason.set(index, 'bad_timestamp')
      continue
    }
    if (t > nowMs + MAX_FUTURE_SKEW_MS) {
      dropReason.set(index, 'future')
      continue
    }
    if (fix.mocked) {
      dropReason.set(index, 'mock')
      continue
    }
    // The stored anchor joins the trajectory at its own time.
    if (anchor && anchor.capturedAtMs <= t && (prev == null || prev.capturedAtMs < anchor.capturedAtMs)) {
      prev = anchor
      chain = []
      run = []
    }
    const current: GpsFix = { lat: fix.lat, lng: fix.lng, capturedAtMs: t }
    if (!prev) {
      keep(index, current, false)
      continue
    }
    const step = plausibleStep(prev, current)
    if (step === 'ok') {
      keep(index, current, true)
      run = []
      continue
    }
    if (step === 'duplicate_timestamp') {
      dropReason.set(index, 'duplicate_timestamp')
      continue
    }

    // Too fast from the anchor. Either this fix is the outlier, or the anchor
    // is (a cached / network last-known fix the OS handed out first). Track
    // fixes that agree with each other; once REANCHOR_RUN of them do, the
    // anchor side was wrong and the trajectory continues from them.
    const last = run[run.length - 1]
    const fromRun = last ? plausibleStep(last.fix, current) : 'speed'
    if (fromRun === 'duplicate_timestamp') {
      dropReason.set(index, 'duplicate_timestamp')
      continue
    }
    run = fromRun === 'ok' ? [...run, { index, fix: current }] : [{ index, fix: current }]
    dropReason.set(index, 'speed')
    if (run.length < REANCHOR_RUN) continue

    // Re-anchor. A short anchor-side chain was the outlier: drop what this
    // batch kept of it. A long one was a real track followed by a real
    // relocation (GPS lost and regained) — both halves stay.
    if (chain.length < REANCHOR_RUN) {
      for (const i of chain) {
        kept.delete(i)
        dropReason.set(i, 'stale_anchor')
      }
    }
    chain = []
    run.forEach((r, i) => {
      dropReason.delete(r.index)
      // The first fix of the new trajectory pays nothing for the jump.
      keep(r.index, r.fix, i > 0)
    })
    run = []
  }

  return {
    distanceMeters: Object.fromEntries([...kept].map((i) => [i, distance.get(i) ?? 0])),
    kept: [...kept].sort((a, b) => a - b),
    dropped: [...dropReason.entries()].map(([index, reason]) => ({ index, reason })).sort((a, b) => a.index - b.index),
  }
}

export type GpsStatsDelta = { kept: number; dropped: number; reasons: Partial<Record<GpsDropReason, number>> }

export function emptyGpsStats(): GpsStatsDelta {
  return { kept: 0, dropped: 0, reasons: {} }
}

export function addDrop(stats: GpsStatsDelta, reason: GpsDropReason): void {
  stats.dropped++
  stats.reasons[reason] = (stats.reasons[reason] ?? 0) + 1
}

/** Whether a scan's running totals make it suspect (see GPS_SUSPECT_*). */
export function isGpsSuspect(kept: number, dropped: number): boolean {
  const total = kept + dropped
  return dropped >= GPS_SUSPECT_MIN_DROPPED && total > 0 && dropped / total >= GPS_SUSPECT_DROPPED_SHARE
}
