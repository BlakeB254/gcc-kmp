'use client'
import { use, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Link from 'next/link'

// Session replay (scan-session-replay PRD Phase 2): "watch the session
// without video". Left pane: the GPS trail drawn up to the scrubber time
// (inline SVG — same normalize-into-viewbox approach as the completion
// report's GpsTrackSvg; the maplibre components are domain-coupled), with
// litter pins dropping as they were observed. Right pane: the
// flight-recorder event stream as a console, synchronized to the same
// scrubber. Video playback via the presigned chunk URLs is Phase 2b.

interface PathSample {
  lat: number
  lng: number
  headingDegrees?: number | null
  accuracyMeters?: number | null
  atMs: number
}

interface ObservedItem {
  lat: number
  lng: number
  itemType: string
  confidence?: number | null
  provenance?: string | null
  atMs?: number | null
}

interface Manifest {
  sessionId: string
  startedAtMs: number
  completedAtMs: number
  path?: PathSample[]
  observedItems?: ObservedItem[]
  gateEvents?: string[] | null
  diagnosticsLog?: string[] | null
}

interface ReplayEvent {
  t: number
  type: string
  msg?: string
  label?: string
  confidence?: number
  distanceM?: number
  state?: string
  tag?: string
  pct?: number
  ok?: boolean
  key?: string
}

interface ChunkRow {
  id: number
  artifactType: string
  r2Key: string
  format: string | null
  byteSize: number | null
  url: string | null
}

interface SessionDetail {
  session: {
    sessionId: string
    deviceId: string
    deviceModel: string | null
    osVersion: string | null
    lapKind: string | null
    routeId: number | null
    startedAt: string | null
    completedAt: string | null
    observedItemCount: number
    scannedCellCount: number
    eventCount: number | null
    gateFlipCount: number | null
    tapCount: number | null
    confirmCount: number | null
    rejectCount: number | null
    raycastMissCount: number | null
    detectionCount: number | null
    uploadFailureCount: number | null
    errorCount: number | null
  }
  chunks: ChunkRow[]
  manifest: Manifest | null
  events: ReplayEvent[] | null
}

const EVENT_COLORS: Record<string, string> = {
  caption: 'text-sky-300',
  tap: 'text-fuchsia-300',
  pill: 'text-fuchsia-400',
  confirm: 'text-green-400',
  reject: 'text-orange-400',
  detection: 'text-yellow-300',
  gate: 'text-amber-400',
  coverage: 'text-emerald-400',
  lap: 'text-purple-300',
  upload: 'text-cyan-300',
  diag: 'text-neutral-500',
  truncated: 'text-red-400',
}

function fmtT(t: number): string {
  const totalSec = Math.floor(t / 1000)
  return `${Math.floor(totalSec / 60)}:${String(totalSec % 60).padStart(2, '0')}`
}

function eventText(e: ReplayEvent): string {
  switch (e.type) {
    case 'caption':
      return e.msg ?? ''
    case 'pill':
      return [e.state, e.label, e.confidence != null ? `${Math.round(e.confidence * 100)}%` : null, e.distanceM != null ? `${e.distanceM.toFixed(1)}m` : null]
        .filter(Boolean)
        .join(' · ')
    case 'gate':
      return [e.state, e.msg].filter(Boolean).join(' — ')
    case 'coverage':
      return `${e.pct}% covered`
    case 'upload':
      return `${e.tag} ${e.ok ? 'ok' : 'FAILED'}${e.msg ? ` — ${e.msg}` : ''}`
    case 'diag':
      return `${e.state === 'ERROR' ? 'ERR ' : ''}[${e.tag}] ${e.msg ?? ''}`
    case 'truncated':
      return `stream truncated — ${e.pct} events dropped`
    default:
      return [e.label, e.msg, e.state].filter(Boolean).join(' · ')
  }
}

const SVG_W = 480
const SVG_H = 420
const PAD = 20

export default function ScanSessionReplayPage({ params }: { params: Promise<{ sessionId: string }> }) {
  const { sessionId } = use(params)
  const [detail, setDetail] = useState<SessionDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [nowT, setNowT] = useState<number>(Number.MAX_SAFE_INTEGER)
  const [showDiag, setShowDiag] = useState(false)
  const consoleRef = useRef<HTMLDivElement | null>(null)

  const authHeaders = useMemo((): Record<string, string> => {
    if (typeof window === 'undefined') return {}
    const t = localStorage.getItem('adminToken')
    return t ? { Authorization: `Bearer ${t}` } : {}
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch(`/api/admin/scan-sessions/${encodeURIComponent(sessionId)}`, {
        headers: authHeaders,
      })
      if (!res.ok) throw new Error(`Session: ${res.status}`)
      const body = await res.json()
      setDetail(body.data)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load')
    } finally {
      setLoading(false)
    }
  }, [authHeaders, sessionId])

  useEffect(() => {
    load()
  }, [load])

  const manifest = detail?.manifest ?? null
  const path = manifest?.path ?? []
  const startedAtMs = manifest?.startedAtMs ?? 0
  const durationMs = Math.max(
    manifest ? manifest.completedAtMs - manifest.startedAtMs : 0,
    ...(detail?.events ?? []).map((e) => e.t),
    1
  )
  const clampedNow = Math.min(nowT, durationMs)

  const bounds = useMemo(() => {
    const pts = [...path, ...(manifest?.observedItems ?? [])]
    if (pts.length === 0) return null
    const lats = pts.map((p) => p.lat)
    const lngs = pts.map((p) => p.lng)
    return {
      minLat: Math.min(...lats),
      maxLat: Math.max(...lats),
      minLng: Math.min(...lngs),
      maxLng: Math.max(...lngs),
    }
  }, [path, manifest])

  const project = useCallback(
    (lat: number, lng: number): [number, number] => {
      if (!bounds) return [SVG_W / 2, SVG_H / 2]
      const lngSpan = bounds.maxLng - bounds.minLng || 1e-6
      const latSpan = bounds.maxLat - bounds.minLat || 1e-6
      const x = PAD + ((lng - bounds.minLng) / lngSpan) * (SVG_W - PAD * 2)
      const y = PAD + (1 - (lat - bounds.minLat) / latSpan) * (SVG_H - PAD * 2)
      return [x, y]
    },
    [bounds]
  )

  const fullTrail = useMemo(
    () => path.map((p) => project(p.lat, p.lng).map((v) => v.toFixed(1)).join(',')).join(' '),
    [path, project]
  )
  const progressSamples = useMemo(
    () => path.filter((p) => p.atMs - startedAtMs <= clampedNow),
    [path, startedAtMs, clampedNow]
  )
  const progressTrail = useMemo(
    () => progressSamples.map((p) => project(p.lat, p.lng).map((v) => v.toFixed(1)).join(',')).join(' '),
    [progressSamples, project]
  )
  const dot = progressSamples.length > 0 ? project(progressSamples[progressSamples.length - 1].lat, progressSamples[progressSamples.length - 1].lng) : null

  const visibleEvents = useMemo(
    () => (detail?.events ?? []).filter((e) => showDiag || e.type !== 'diag'),
    [detail, showDiag]
  )
  const passedCount = visibleEvents.filter((e) => e.t <= clampedNow).length

  // Keep the last-passed event in view as the scrubber moves.
  useEffect(() => {
    const el = consoleRef.current?.querySelector('[data-current="true"]')
    el?.scrollIntoView({ block: 'nearest' })
  }, [passedCount])

  const s = detail?.session
  const metricChips: Array<[string, number | null | undefined, boolean]> = s
    ? [
        ['events', s.eventCount, false],
        ['taps', s.tapCount, false],
        ['confirms', s.confirmCount, false],
        ['rejects', s.rejectCount, false],
        ['misses', s.raycastMissCount, (s.raycastMissCount ?? 0) >= 3],
        ['detections', s.detectionCount, false],
        ['gate flips', s.gateFlipCount, (s.gateFlipCount ?? 0) >= 4],
        ['upload fails', s.uploadFailureCount, (s.uploadFailureCount ?? 0) > 0],
        ['errors', s.errorCount, (s.errorCount ?? 0) >= 3],
      ]
    : []

  return (
    <div className="p-6 text-white">
      <div className="mb-4 flex items-baseline gap-3">
        <Link href="/admin/scan-sessions" className="text-xs text-neutral-500 hover:text-neutral-300">
          ← Sessions
        </Link>
        <h1 className="text-xl font-semibold">Session replay</h1>
        <span className="font-mono text-xs text-neutral-500">{sessionId}</span>
      </div>

      {loading && <p className="text-neutral-400">Loading...</p>}
      {error && (
        <div className="mb-4 rounded-lg bg-red-950/40 px-4 py-2 text-sm text-red-300">{error}</div>
      )}

      {detail && s && (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-2 text-xs text-neutral-400">
            <span>{s.deviceModel ?? 'unknown device'} · iOS {s.osVersion ?? '?'}</span>
            <span>· lap: {s.lapKind ?? 'env'}</span>
            {s.routeId != null && <span>· route {s.routeId}</span>}
            <span>· {s.startedAt ? new Date(s.startedAt).toLocaleString() : '—'}</span>
            <span>· {s.observedItemCount} item(s), {s.scannedCellCount} cell(s)</span>
          </div>
          <div className="mb-4 flex flex-wrap gap-1.5">
            {metricChips.map(([label, value, alert]) => (
              <span
                key={label}
                className={`rounded px-2 py-1 text-[11px] ${
                  alert ? 'bg-red-950/60 text-red-300' : 'bg-neutral-800 text-neutral-300'
                }`}
              >
                {label}: {value ?? '—'}
              </span>
            ))}
          </div>

          <div className="mb-4 flex items-center gap-3">
            <span className="w-12 text-right font-mono text-xs text-neutral-400">{fmtT(clampedNow)}</span>
            <input
              type="range"
              min={0}
              max={durationMs}
              value={clampedNow}
              onChange={(e) => setNowT(Number(e.target.value))}
              className="flex-1 accent-emerald-500"
            />
            <span className="w-12 font-mono text-xs text-neutral-500">{fmtT(durationMs)}</span>
            <button
              onClick={() => setNowT(Number.MAX_SAFE_INTEGER)}
              className="rounded border border-neutral-700 px-2 py-1 text-[11px] text-neutral-300 hover:bg-neutral-800"
            >
              End
            </button>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <div className="rounded-lg border border-neutral-800 bg-neutral-900/50 p-2">
              {path.length > 1 ? (
                <svg viewBox={`0 0 ${SVG_W} ${SVG_H}`} width="100%" role="img" aria-label="GPS trail replay">
                  <polyline points={fullTrail} fill="none" stroke="#404040" strokeWidth={1.5} strokeLinejoin="round" />
                  {progressSamples.length > 1 && (
                    <polyline points={progressTrail} fill="none" stroke="#10b981" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round" />
                  )}
                  {(manifest?.observedItems ?? []).map((item, i) => {
                    const visible = item.atMs == null || item.atMs - startedAtMs <= clampedNow
                    if (!visible) return null
                    const [x, y] = project(item.lat, item.lng)
                    const confirmed = item.provenance === 'user_confirmed'
                    return (
                      <g key={i}>
                        <circle cx={x} cy={y} r={4} fill={confirmed ? '#f97316' : '#eab308'} opacity={0.9} />
                        <title>{`${item.itemType}${item.confidence != null ? ` (${Math.round(item.confidence * 100)}%)` : ''}${confirmed ? ' — confirmed' : ''}`}</title>
                      </g>
                    )
                  })}
                  {dot && (
                    <>
                      <circle cx={dot[0]} cy={dot[1]} r={7} fill="#10b981" opacity={0.25} />
                      <circle cx={dot[0]} cy={dot[1]} r={3.5} fill="#10b981" stroke="#fff" strokeWidth={1.5} />
                    </>
                  )}
                </svg>
              ) : (
                <div className="flex h-[420px] items-center justify-center text-xs text-neutral-500">
                  {manifest ? 'No GPS trail in this session.' : 'Manifest unavailable (R2 not reachable).'}
                </div>
              )}
              <div className="mt-1 flex gap-4 px-1 text-[10px] text-neutral-400">
                <span className="flex items-center gap-1.5"><span className="inline-block h-0.5 w-3 bg-emerald-500" /> walked (to scrubber)</span>
                <span className="flex items-center gap-1.5"><span className="inline-block h-2 w-2 rounded-full bg-orange-500" /> confirmed litter</span>
                <span className="flex items-center gap-1.5"><span className="inline-block h-2 w-2 rounded-full bg-yellow-500" /> auto-detected</span>
              </div>
            </div>

            <div className="flex flex-col rounded-lg border border-neutral-800 bg-neutral-900/50">
              <div className="flex items-center justify-between border-b border-neutral-800 px-3 py-2">
                <span className="text-xs font-medium text-neutral-300">
                  Event stream {detail.events ? `(${visibleEvents.length})` : ''}
                </span>
                <label className="flex items-center gap-1.5 text-[11px] text-neutral-500">
                  <input type="checkbox" checked={showDiag} onChange={(e) => setShowDiag(e.target.checked)} className="accent-emerald-500" />
                  diagnostics
                </label>
              </div>
              <div ref={consoleRef} className="h-[400px] overflow-y-auto px-1 py-1 font-mono text-[11px] leading-5">
                {!detail.events && (
                  <p className="px-2 py-2 text-neutral-500">
                    No event log for this session (predates the flight recorder, or upload still pending).
                  </p>
                )}
                {visibleEvents.map((e, i) => {
                  const passed = e.t <= clampedNow
                  const isCurrent = passed && (i === visibleEvents.length - 1 || visibleEvents[i + 1].t > clampedNow)
                  return (
                    <div
                      key={i}
                      data-current={isCurrent ? 'true' : undefined}
                      onClick={() => setNowT(e.t)}
                      className={`flex cursor-pointer gap-2 rounded px-2 hover:bg-neutral-800/60 ${
                        passed ? '' : 'opacity-35'
                      } ${isCurrent ? 'bg-neutral-800' : ''}`}
                    >
                      <span className="w-10 shrink-0 text-right text-neutral-500">{fmtT(e.t)}</span>
                      <span className={`w-16 shrink-0 ${EVENT_COLORS[e.type] ?? 'text-neutral-300'}`}>{e.type}</span>
                      <span className="min-w-0 flex-1 truncate text-neutral-300" title={eventText(e)}>
                        {eventText(e)}
                      </span>
                    </div>
                  )
                })}
              </div>
            </div>
          </div>

          <div className="mt-4 rounded-lg border border-neutral-800 bg-neutral-900/50">
            <div className="border-b border-neutral-800 px-3 py-2 text-xs font-medium text-neutral-300">
              Artifacts ({detail.chunks.length}) — links expire in ~5 min
            </div>
            <div className="divide-y divide-neutral-800">
              {detail.chunks.map((c) => (
                <div key={c.id} className="flex items-center gap-3 px-3 py-1.5 text-[11px]">
                  <span className="w-20 shrink-0 text-neutral-400">{c.artifactType}</span>
                  <span className="min-w-0 flex-1 truncate font-mono text-neutral-500">{c.r2Key}</span>
                  <span className="w-16 shrink-0 text-right text-neutral-500">
                    {c.byteSize != null ? `${Math.round(c.byteSize / 1024)}KB` : '—'}
                  </span>
                  {c.url ? (
                    <a href={c.url} target="_blank" rel="noreferrer" className="shrink-0 text-emerald-400 hover:underline">
                      open
                    </a>
                  ) : (
                    <span className="shrink-0 text-neutral-600">n/a</span>
                  )}
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  )
}
