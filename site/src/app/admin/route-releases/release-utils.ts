/**
 * Shared types/helpers for the route-release admin pages
 * (list at ../page.tsx, detail at ../[releaseId]/page.tsx).
 */

export interface ReleaseBatch {
  id: number
  serviceAreaId: number
  name: string
  communityName: string | null
  eligibleZipCodes: string[]
  status: string
  releaseAt: string | null
  nextWaveAt: string | null
  offerWindowMinutes: number
  timezone?: string
  cleanupStartsAt: string | null
  cleanupEndsAt: string | null
  targetSlots: number | null
  waveNumber: number
  createdAt: string | null
}

export interface ReleaseRouteRow {
  id: number
  routeId: number
  jobId: number
  position: number
  status: string
  code?: string | null
  displayName?: string | null
}

export interface ReleaseOfferRow {
  id: number
  workerId: number
  status: string
  waveNumber: number
  offeredAt?: string | null
  expiresAt?: string | null
  respondedAt?: string | null
  workerName?: string | null
}

export interface ReleaseDetail extends ReleaseBatch {
  routes: ReleaseRouteRow[]
  offers: ReleaseOfferRow[]
}

export function batchStatusBadge(status: string): { label: string; cls: string } {
  switch (status) {
    case 'active':
      return { label: 'Active', cls: 'bg-green-900/40 text-green-400' }
    case 'scheduled':
      return { label: 'Scheduled', cls: 'bg-blue-900/40 text-blue-400' }
    case 'completed':
      return { label: 'Completed', cls: 'bg-neutral-800 text-neutral-400' }
    case 'cancelled':
      return { label: 'Cancelled', cls: 'bg-red-900/40 text-red-400' }
    default:
      return { label: status || 'Draft', cls: 'bg-neutral-800 text-neutral-400' }
  }
}

export function isoToLocalInput(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function localInputToIso(value: string): string | null {
  if (!value) return null
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString()
}
