'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import { CombinedMap } from '@/components/map/CombinedMap'
import type { SpotReport } from '@/components/map/BountyMap'

// ============================================================================
// TYPES
// ============================================================================

interface ScanListRow {
  id: number
  routeId: number
  workerId: number
  workerName: string | null
  deviceType: string
  phase: string
  status: string
  coveragePercent: number
  startLat: number
  startLng: number
  totalTrashVolumeLiters: number | null
  totalTrashWeightLbs: number | null
  pointsAwarded: number
  scoutStartedAt: string
  cleanCompletedAt: string | null
  createdAt: string
}

interface ScanHotspot {
  id: number
  scanId: number
  lat: number
  lng: number
  trashTypes: string[]
  severity: number
  estimatedVolumeLiters: number | null
  estimatedWeightLbs: number | null
  confidence: number
  beforePhotoUrl: string
  afterPhotoUrl: string | null
  arAnchorData: string | null
  depthData: string | null
  detectedAt: string
  cleanedAt: string | null
  verificationScore: number | null
  status: string
}

interface ReconstructionAssetRow {
  id: number
  locationCellId: string
  assetType: string
  format: string
  r2Key: string
  version: number
  sourceScanCoverageEventIds: number[]
  activatedAt: string | null
  createdAt: string
  viewUrl: string | null
}

interface CoverageEventRow {
  id: number
  deviceId: string
  platform: string
  workerId: number | null
  locationCellId: string
  lat: number
  lng: number
  distanceMeters: number
  scanMode: string
  deviceTier: string
  capturedAt: string
  rawR2Key: string | null
  rawViewUrl: string | null
  createdAt: string
}

interface ScanDetail {
  scan: ScanListRow
  workerName: string | null
  hotspots: ScanHotspot[]
  locationCellIds: string[]
  reconstructionAssets: ReconstructionAssetRow[]
  coverageEvents: CoverageEventRow[]
  r2Configured: boolean
}

interface WorldObservation {
  id: number
  lat: number
  lng: number
  itemType: string | null
  state: string
  severity: number | null
  mediaUrl: string | null
  sourceType: string
  capturedAt: string
}

type Tab = 'map' | 'scans'

// ============================================================================
// PAGE
// ============================================================================

export default function AdminScansPage() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const selectedScanId = searchParams.get('scan')

  const [tab, setTab] = useState<Tab>('map')

  // ----- world model map -----
  const [observations, setObservations] = useState<WorldObservation[]>([])
  const [observationsLoading, setObservationsLoading] = useState(true)

  useEffect(() => {
    fetch('/api/map/observations')
      .then((r) => (r.ok ? r.json() : null))
      .then((json) => setObservations(json?.observations || []))
      .catch(() => {})
      .finally(() => setObservationsLoading(false))
  }, [])

  // Every current world observation rendered directly as a map spot — no
  // merging with community reports (that's what the public bounties map
  // does). This is the raw "cumulative world model" view: whatever is
  // isCurrent=true right now, one dot per grid cell/item-type, dirty in red
  // and clean-confirmed in green.
  const observationSpots = useMemo<SpotReport[]>(
    () =>
      observations.map((o) => ({
        id: `obs-${o.id}`,
        coordinates: [o.lng, o.lat] as [number, number],
        radiusMeters: 15,
        label: o.itemType || (o.state === 'clean' ? 'Confirmed clean' : 'Reported'),
        tags: o.itemType ? [o.itemType] : [],
        itemType: o.itemType ?? undefined,
        status: o.state === 'clean' ? 'resolved' : 'verified',
        photoUrl: o.mediaUrl || undefined,
        createdAt: o.capturedAt,
        needsVerification: false,
      })),
    [observations]
  )

  // ----- scans list -----
  const [scans, setScans] = useState<ScanListRow[]>([])
  const [scansLoading, setScansLoading] = useState(false)

  const fetchScans = useCallback(async () => {
    setScansLoading(true)
    try {
      const token = localStorage.getItem('adminToken')
      const res = await fetch('/api/admin/scans?limit=100', {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (res.ok) {
        const json = await res.json()
        setScans(json.data || [])
      }
    } catch {
      // silent
    } finally {
      setScansLoading(false)
    }
  }, [])

  useEffect(() => {
    if (tab === 'scans') fetchScans()
  }, [tab, fetchScans])

  // ----- scan detail -----
  const [detail, setDetail] = useState<ScanDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  useEffect(() => {
    if (!selectedScanId) {
      setDetail(null)
      return
    }
    setDetailLoading(true)
    const token = localStorage.getItem('adminToken')
    fetch(`/api/admin/scans/${selectedScanId}`, { headers: { Authorization: `Bearer ${token}` } })
      .then((r) => (r.ok ? r.json() : null))
      .then((json) => setDetail(json?.data || null))
      .catch(() => setDetail(null))
      .finally(() => setDetailLoading(false))
  }, [selectedScanId])

  const openScan = (id: number) => router.push(`/admin/scans?scan=${id}`)
  const closeScan = () => router.push('/admin/scans')

  return (
    <div className="p-6 max-w-6xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-xl font-semibold">Scan Visualizer</h1>
        <div className="flex gap-1 bg-neutral-900 border border-neutral-800 rounded-lg p-1">
          <button
            onClick={() => setTab('map')}
            className={`px-3 py-1.5 rounded-md text-sm transition-colors ${
              tab === 'map' ? 'bg-neutral-700 text-white' : 'text-neutral-400 hover:text-neutral-200'
            }`}
          >
            World Model Map
          </button>
          <button
            onClick={() => setTab('scans')}
            className={`px-3 py-1.5 rounded-md text-sm transition-colors ${
              tab === 'scans' ? 'bg-neutral-700 text-white' : 'text-neutral-400 hover:text-neutral-200'
            }`}
          >
            Individual Scans
          </button>
        </div>
      </div>

      {tab === 'map' && (
        <div>
          <p className="text-sm text-neutral-500 mb-3">
            {observationsLoading
              ? 'Loading current world-model observations…'
              : `${observations.length} current observation${observations.length === 1 ? '' : 's'} (isCurrent=true) — latest scan/report wins per location.`}
          </p>
          <div className="rounded-xl overflow-hidden border border-neutral-800">
            <CombinedMap bounties={[]} spots={observationSpots} routes={[]} height="70vh" showLegend />
          </div>
        </div>
      )}

      {tab === 'scans' && !selectedScanId && (
        <div className="border border-neutral-800 rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-neutral-900 text-neutral-500 text-xs uppercase tracking-wider">
              <tr>
                <th className="text-left px-4 py-2">Scan</th>
                <th className="text-left px-4 py-2">Worker</th>
                <th className="text-left px-4 py-2">Phase</th>
                <th className="text-left px-4 py-2">Status</th>
                <th className="text-left px-4 py-2">Coverage</th>
                <th className="text-left px-4 py-2">Trash</th>
                <th className="text-left px-4 py-2">Points</th>
                <th className="text-left px-4 py-2">Started</th>
              </tr>
            </thead>
            <tbody>
              {scansLoading && (
                <tr>
                  <td colSpan={8} className="px-4 py-6 text-center text-neutral-500">
                    Loading…
                  </td>
                </tr>
              )}
              {!scansLoading && scans.length === 0 && (
                <tr>
                  <td colSpan={8} className="px-4 py-6 text-center text-neutral-500">
                    No scans yet.
                  </td>
                </tr>
              )}
              {scans.map((s) => (
                <tr
                  key={s.id}
                  onClick={() => openScan(s.id)}
                  className="border-t border-neutral-800 hover:bg-neutral-900 cursor-pointer"
                >
                  <td className="px-4 py-2 text-neutral-300">#{s.id}</td>
                  <td className="px-4 py-2 text-neutral-300">{s.workerName || `Worker ${s.workerId}`}</td>
                  <td className="px-4 py-2 text-neutral-400 capitalize">{s.phase}</td>
                  <td className="px-4 py-2">
                    <span className="inline-block px-2 py-0.5 rounded text-xs font-medium bg-blue-900/40 text-blue-400">
                      {s.status}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-neutral-400">{s.coveragePercent.toFixed(0)}%</td>
                  <td className="px-4 py-2 text-neutral-400">
                    {s.totalTrashWeightLbs != null ? `${s.totalTrashWeightLbs.toFixed(1)} lbs` : '—'}
                  </td>
                  <td className="px-4 py-2 text-neutral-400">{s.pointsAwarded}</td>
                  <td className="px-4 py-2 text-neutral-500">{new Date(s.scoutStartedAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {tab === 'scans' && selectedScanId && (
        <div>
          <button onClick={closeScan} className="text-sm text-neutral-400 hover:text-neutral-200 mb-4">
            ← Back to scans
          </button>
          {detailLoading && <p className="text-neutral-500 text-sm">Loading scan #{selectedScanId}…</p>}
          {!detailLoading && !detail && <p className="text-neutral-500 text-sm">Scan not found.</p>}
          {!detailLoading && detail && <ScanDetailPanel detail={detail} />}
        </div>
      )}
    </div>
  )
}

// ============================================================================
// SCAN DETAIL PANEL
// ============================================================================

function ScanDetailPanel({ detail }: { detail: ScanDetail }) {
  const { scan, workerName, hotspots, locationCellIds, reconstructionAssets, coverageEvents, r2Configured } = detail

  return (
    <div className="space-y-6">
      <div className="border border-neutral-800 rounded-xl p-4">
        <h2 className="text-lg font-medium mb-3">Scan #{scan.id}</h2>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
          <Field label="Worker" value={workerName || `Worker ${scan.workerId}`} />
          <Field label="Device" value={scan.deviceType.toUpperCase()} />
          <Field label="Phase" value={scan.phase} />
          <Field label="Status" value={scan.status} />
          <Field label="Coverage" value={`${scan.coveragePercent.toFixed(0)}%`} />
          <Field label="Trash volume" value={scan.totalTrashVolumeLiters != null ? `${scan.totalTrashVolumeLiters.toFixed(1)} L` : '—'} />
          <Field label="Trash weight" value={scan.totalTrashWeightLbs != null ? `${scan.totalTrashWeightLbs.toFixed(1)} lbs` : '—'} />
          <Field label="Points awarded" value={String(scan.pointsAwarded)} />
        </div>
        <div className="mt-3 text-xs text-neutral-500">
          Location cell{locationCellIds.length === 1 ? '' : 's'}: {locationCellIds.join(', ')}
        </div>
        {!r2Configured && (
          <div className="mt-3 text-xs text-amber-500">
            R2 is not configured in this environment — raw/reconstructed asset links will not resolve.
          </div>
        )}
      </div>

      <div>
        <h3 className="text-sm font-semibold text-neutral-400 uppercase tracking-wider mb-2">
          Hotspots ({hotspots.length})
        </h3>
        {hotspots.length === 0 && <p className="text-sm text-neutral-500">No hotspots recorded for this scan.</p>}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          {hotspots.map((h) => (
            <div key={h.id} className="border border-neutral-800 rounded-lg overflow-hidden">
              <div className="flex">
                <img src={h.beforePhotoUrl} alt="before" className="w-1/2 h-28 object-cover" />
                {h.afterPhotoUrl ? (
                  <img src={h.afterPhotoUrl} alt="after" className="w-1/2 h-28 object-cover" />
                ) : (
                  <div className="w-1/2 h-28 flex items-center justify-center text-xs text-neutral-600">
                    not cleaned yet
                  </div>
                )}
              </div>
              <div className="p-2 text-xs space-y-1">
                <div className="flex justify-between">
                  <span className="text-neutral-400 capitalize">{h.status}</span>
                  <span className="text-neutral-500">severity {h.severity}/5</span>
                </div>
                {h.trashTypes.length > 0 && (
                  <div className="text-neutral-500">{h.trashTypes.join(', ')}</div>
                )}
                <div className="text-neutral-600">confidence {(h.confidence * 100).toFixed(0)}%</div>
                {(h.arAnchorData || h.depthData) && (
                  <div className="text-neutral-600">
                    {h.arAnchorData ? 'has AR anchor data' : ''}
                    {h.arAnchorData && h.depthData ? ' · ' : ''}
                    {h.depthData ? 'has depth data' : ''}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div>
        <h3 className="text-sm font-semibold text-neutral-400 uppercase tracking-wider mb-2">
          Reconstructed 3D assets ({reconstructionAssets.length})
        </h3>
        {reconstructionAssets.length === 0 && (
          <p className="text-sm text-neutral-500">
            None yet — nothing has been reconstructed for this scan&apos;s location cell(s).
          </p>
        )}
        <div className="space-y-2">
          {reconstructionAssets.map((a) => (
            <div key={a.id} className="border border-neutral-800 rounded-lg px-3 py-2 text-sm flex items-center justify-between">
              <div>
                <span className="font-medium">{a.assetType}</span>
                <span className="text-neutral-500"> · {a.format} · v{a.version} · cell {a.locationCellId}</span>
              </div>
              {a.viewUrl ? (
                <a href={a.viewUrl} target="_blank" rel="noreferrer" className="text-blue-400 hover:underline text-xs">
                  View / download →
                </a>
              ) : (
                <span className="text-xs text-neutral-600">no link available</span>
              )}
            </div>
          ))}
        </div>
      </div>

      <div>
        <h3 className="text-sm font-semibold text-neutral-400 uppercase tracking-wider mb-2">
          Raw coverage events for this cell ({coverageEvents.length})
        </h3>
        {coverageEvents.length === 0 && <p className="text-sm text-neutral-500">No coverage events recorded.</p>}
        <div className="space-y-1">
          {coverageEvents.slice(0, 30).map((e) => (
            <div key={e.id} className="border border-neutral-800 rounded-lg px-3 py-2 text-xs flex items-center justify-between">
              <div className="text-neutral-400">
                {e.platform} · {e.deviceTier} · {e.distanceMeters.toFixed(0)}m · {new Date(e.capturedAt).toLocaleString()}
              </div>
              {e.rawViewUrl ? (
                <a href={e.rawViewUrl} target="_blank" rel="noreferrer" className="text-blue-400 hover:underline">
                  Raw capture →
                </a>
              ) : (
                <span className="text-neutral-600">no raw blob on file</span>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-neutral-500 text-xs uppercase tracking-wider">{label}</div>
      <div className="text-neutral-200">{value}</div>
    </div>
  )
}
