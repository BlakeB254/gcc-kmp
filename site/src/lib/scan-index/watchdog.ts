/**
 * Silence alarm for the scan pipeline. The indexer and the 311 mirror stopped
 * on 2026-09-08 and nothing noticed for six weeks: the Spark recon timer kept
 * logging "queue empty", which is also what a healthy idle queue says. This
 * checks the outputs instead of the processes:
 *
 *  - scanIndex:  a manifest has sat in R2 unindexed for more than N hours
 *                (manifests the indexer has permanently rejected are excluded —
 *                they are reported once, by the next check, instead);
 *  - rejectedManifests: the indexer rejected a manifest nobody has been told
 *                about yet. Fails exactly once per manifest: the run that
 *                reports it marks it alerted.
 *  - civicSync:  the newest mirrored 311 change is more than N hours old
 *                (the portal changes these types many times an hour);
 *  - reconQueue: a reconstruction job has been claimable — queued, or leased
 *                with an expired lease — for more than N hours.
 *
 * Driven by GET /api/cron/scan-watchdog, which answers 503 when any check
 * fails, so the scheduler's `curl -f` fails the unit and the failure is seen.
 */
import type { ObjectInfo } from './indexer'

export interface WatchdogDeps {
  /** raw/ listing, or null when R2 is not configured. */
  listRaw(): Promise<Map<string, ObjectInfo> | null>
  /** Manifest keys that have a capture_chunks row (the indexer writes one per manifest). */
  indexedManifestKeys(): Promise<Set<string>>
  civicWatermarkMs(): Promise<number | null>
  /** Claimable reconstruction jobs untouched since `beforeMs`. */
  stuckReconJobs(beforeMs: number): Promise<{ count: number; oldestMs: number | null }>
  /** scan_manifest_rejects rows. */
  manifestRejects(): Promise<{ r2Key: string; reason: string; alerted: boolean }[]>
  markRejectsAlerted(keys: string[]): Promise<void>
}

export interface WatchdogThresholds {
  scanHours: number
  civicHours: number
  reconHours: number
}

export const DEFAULT_THRESHOLDS: WatchdogThresholds = { scanHours: 6, civicHours: 48, reconHours: 24 }

export interface CheckResult {
  ok: boolean
  detail: string
}

export interface WatchdogReport {
  healthy: boolean
  checkedAt: string
  thresholds: WatchdogThresholds
  scanIndex: CheckResult & { unindexedOverdue: number; examples: string[] }
  rejectedManifests: CheckResult & { known: number; newlyRejected: { r2Key: string; reason: string }[] }
  civicSync: CheckResult & { watermark: string | null }
  reconQueue: CheckResult & { stuck: number }
}

const HOUR = 3_600_000
const hoursAgo = (now: number, ms: number) => ((now - ms) / HOUR).toFixed(1)

export async function runWatchdog(
  deps: WatchdogDeps,
  thresholds: WatchdogThresholds = DEFAULT_THRESHOLDS,
  now = Date.now(),
): Promise<WatchdogReport> {
  const [raw, indexed, civicMs, recon, rejects] = await Promise.all([
    deps.listRaw(),
    deps.indexedManifestKeys(),
    deps.civicWatermarkMs(),
    deps.stuckReconJobs(now - thresholds.reconHours * HOUR),
    deps.manifestRejects(),
  ])
  const rejected = new Set(rejects.map((r) => r.r2Key))

  let scanIndex: WatchdogReport['scanIndex']
  if (raw == null) {
    scanIndex = { ok: false, detail: 'R2 not configured — the indexer cannot run', unindexedOverdue: 0, examples: [] }
  } else {
    const cutoff = now - thresholds.scanHours * HOUR
    const overdue = [...raw.entries()]
      .filter(([k, info]) => k.endsWith('manifest.json') && !indexed.has(k) && !rejected.has(k) && info.lastModifiedMs < cutoff)
      .sort((a, b) => a[1].lastModifiedMs - b[1].lastModifiedMs)
    scanIndex = {
      ok: overdue.length === 0,
      detail: overdue.length === 0
        ? 'every manifest older than the threshold is indexed'
        : `${overdue.length} manifest(s) unindexed; oldest uploaded ${hoursAgo(now, overdue[0][1].lastModifiedMs)}h ago`,
      unindexedOverdue: overdue.length,
      examples: overdue.slice(0, 5).map(([k]) => k),
    }
  }

  const civicSync: WatchdogReport['civicSync'] = civicMs == null
    ? { ok: false, detail: 'civic_reports is empty — the 311 mirror has never run', watermark: null }
    : {
        ok: now - civicMs <= thresholds.civicHours * HOUR,
        // civicWatermarkMs is the true instant (Chicago wall time converted).
        detail: `newest mirrored 311 change is ${hoursAgo(now, civicMs)}h old`,
        watermark: new Date(civicMs).toISOString(),
      }

  const reconQueue: WatchdogReport['reconQueue'] = {
    ok: recon.count === 0,
    detail: recon.count === 0
      ? 'no claimable job is older than the threshold'
      : `${recon.count} claimable job(s) untouched; oldest ${hoursAgo(now, recon.oldestMs ?? now)}h — is the Spark recon worker running?`,
    stuck: recon.count,
  }

  const fresh = rejects.filter((r) => !r.alerted)
  const rejectedManifests: WatchdogReport['rejectedManifests'] = {
    ok: fresh.length === 0,
    detail: fresh.length === 0
      ? `${rejects.length} known-bad manifest(s), all reported before`
      : `${fresh.length} manifest(s) newly rejected by the indexer (reported once)`,
    known: rejects.length,
    newlyRejected: fresh.map(({ r2Key, reason }) => ({ r2Key, reason })),
  }
  if (fresh.length > 0) await deps.markRejectsAlerted(fresh.map((r) => r.r2Key))

  return {
    healthy: scanIndex.ok && rejectedManifests.ok && civicSync.ok && reconQueue.ok,
    checkedAt: new Date(now).toISOString(),
    thresholds,
    scanIndex,
    rejectedManifests,
    civicSync,
    reconQueue,
  }
}

/** Query-string overrides (`scan_hours`, `civic_hours`, `recon_hours`); bad values keep the default. */
export function thresholdsFrom(params: URLSearchParams): WatchdogThresholds {
  const pick = (name: string, fallback: number) => {
    const v = Number(params.get(name))
    return params.has(name) && Number.isFinite(v) && v > 0 ? v : fallback
  }
  return {
    scanHours: pick('scan_hours', DEFAULT_THRESHOLDS.scanHours),
    civicHours: pick('civic_hours', DEFAULT_THRESHOLDS.civicHours),
    reconHours: pick('recon_hours', DEFAULT_THRESHOLDS.reconHours),
  }
}
