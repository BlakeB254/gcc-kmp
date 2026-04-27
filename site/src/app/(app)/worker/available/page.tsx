'use client'

import { useState, useEffect, useCallback } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import dynamic from 'next/dynamic'
import { MapPin, Mail, AlertCircle } from 'lucide-react'

/** Format street name to include 1500 block number for Roosevelt Rd */
function formatStreetName(streetName: string | undefined | null): string {
  if (!streetName) return ''
  if (streetName.includes('1500') || !streetName.toLowerCase().includes('roosevelt')) {
    return streetName
  }
  return streetName.replace(/^W\s+Roosevelt/i, '1500 W Roosevelt')
}

/**
 * Worker Available Blocks Page
 *
 * Map-centric view for workers to discover and claim available blocks.
 * Uses CDX Tile Server for vector tile layers with real-time status.
 *
 * Features:
 * - Full-screen map with North Lawndale boundary
 * - Color-coded blocks by status (green = available)
 * - Block details modal on click
 * - Quick claim functionality
 */

// Dynamically import BlockMap to avoid SSR issues with MapLibre
const BlockMap = dynamic(
  () => import('@/components/map/BlockMap').then(mod => ({ default: mod.BlockMap })),
  {
    ssr: false,
    loading: () => (
      <div className="h-full bg-neutral-100 animate-pulse flex items-center justify-center">
        <div className="text-center">
          <div className="animate-spin rounded-full h-10 w-10 border-b-2 border-brand-green-600 mx-auto mb-3" />
          <span className="text-neutral-500">Loading map...</span>
        </div>
      </div>
    ),
  }
)

// Lightweight map for non-pilot residents — same MapLibre stack, no block layers.
const BaseMap = dynamic(
  () => import('@/components/map/BaseMap').then(mod => ({ default: mod.BaseMap })),
  {
    ssr: false,
    loading: () => (
      <div className="h-full bg-neutral-100 animate-pulse flex items-center justify-center">
        <div className="animate-spin rounded-full h-10 w-10 border-b-2 border-brand-green-600" />
      </div>
    ),
  }
)

type BlockStatus = 'unfunded' | 'available' | 'claimed' | 'in_progress' | 'pending_review' | 'completed'

interface Block {
  id: string
  code: string
  streetName: string
  fromCorner: string
  toCorner: string
  status: BlockStatus
  geo: {
    center: { coordinates: [number, number] }
    polygon?: { coordinates: [number, number][][] }
  }
  payoutAmount?: number
  difficulty?: 'standard' | 'heavy' | 'commercial'
}

const STATUS_INFO: Record<BlockStatus, { label: string; description: string }> = {
  unfunded: { label: 'Needs Sponsor', description: 'Waiting for funding' },
  available: { label: 'Available', description: 'Ready to claim!' },
  claimed: { label: 'Claimed', description: 'Someone is working on it' },
  in_progress: { label: 'In Progress', description: 'Currently being cleaned' },
  pending_review: { label: 'Under Review', description: 'Submitted for approval' },
  completed: { label: 'Completed', description: 'Verified clean' },
}

/**
 * Resident profile derived from the authenticated worker.
 * `mode` decides which map to render:
 *   - 'pilot': user lives in North Lawndale (or has no address yet — they
 *     might be onboarding a Chicago worker). Show the BlockMap with routes.
 *   - 'elsewhere': user lives outside the active service area. Show their
 *     city/zip on a generic map with a "no routes available yet" message.
 *   - 'no-address': no usable address on file — prompt to add one.
 */
type ResidenceMode = 'pilot' | 'elsewhere' | 'no-address'

interface Residence {
  mode: ResidenceMode
  city: string
  state: string
  zip: string
  /** Resolved [longitude, latitude] center for the user's area, when known. */
  coords: [number, number] | null
}

/** Geocode "<zip>, <state>, US" via Nominatim (free, no API key). */
async function geocodeZip(zip: string, state: string): Promise<[number, number] | null> {
  if (!zip) return null
  try {
    const params = new URLSearchParams({
      postalcode: zip,
      country: 'US',
      format: 'json',
      limit: '1',
    })
    if (state) params.set('state', state)
    const r = await fetch(`https://nominatim.openstreetmap.org/search?${params}`, {
      headers: { 'Accept-Language': 'en' },
    })
    const data = (await r.json()) as Array<{ lon: string; lat: string }>
    if (!data?.length) return null
    return [parseFloat(data[0].lon), parseFloat(data[0].lat)]
  } catch {
    return null
  }
}

export default function WorkerAvailablePage() {
  const router = useRouter()
  const [blocks, setBlocks] = useState<Block[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [selectedBlock, setSelectedBlock] = useState<Block | null>(null)
  const [isClaiming, setIsClaiming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [residence, setResidence] = useState<Residence | null>(null)

  // Resolve which map to render based on the worker's address. Runs once on mount.
  useEffect(() => {
    let cancelled = false
    fetch('/api/workers/me', { credentials: 'include' })
      .then((r) => (r.ok ? r.json() : null))
      .then(async (payload) => {
        if (cancelled) return
        const addr = payload?.user?.address
        if (!addr) {
          // Not signed in / no profile — let the page render anyway with the
          // pilot map; auth-gated actions will fail loudly on their own.
          setResidence({ mode: 'pilot', city: '', state: '', zip: '', coords: null })
          return
        }

        if (addr.isNorthLawndale) {
          setResidence({
            mode: 'pilot',
            city: addr.city || '',
            state: addr.state || '',
            zip: addr.zip || '',
            coords: null,
          })
          return
        }

        const hasMinAddress = !!(addr.zip || (addr.city && addr.state))
        if (!hasMinAddress) {
          setResidence({ mode: 'no-address', city: '', state: '', zip: '', coords: null })
          return
        }

        const coords = await geocodeZip(addr.zip || '', addr.state || '')
        if (cancelled) return
        setResidence({
          mode: 'elsewhere',
          city: addr.city || '',
          state: addr.state || '',
          zip: addr.zip || '',
          coords,
        })
      })
      .catch(() => {
        if (!cancelled) {
          setResidence({ mode: 'pilot', city: '', state: '', zip: '', coords: null })
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  // Fetch available blocks (only meaningful for pilot residents).
  useEffect(() => {
    if (residence?.mode === 'pilot') fetchBlocks()
    else setIsLoading(false)
  }, [residence?.mode])

  const fetchBlocks = async () => {
    try {
      setIsLoading(true)
      const response = await fetch('/api/blocks?status=available')
      const data = await response.json()
      setBlocks(data.blocks || [])
    } catch (err) {
      console.error('Failed to fetch blocks:', err)
      setError('Failed to load blocks. Please try again.')
    } finally {
      setIsLoading(false)
    }
  }

  // Handle block selection from map
  const handleBlockSelect = useCallback((block: Block) => {
    setSelectedBlock(block)
  }, [])

  // Handle claiming a block
  const handleClaimBlock = async () => {
    if (!selectedBlock) return

    setIsClaiming(true)
    try {
      const response = await fetch('/api/jobs/claim', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ blockId: selectedBlock.id }),
      })

      const data = await response.json()

      if (response.ok && data.job) {
        // Navigate to job execution page
        router.push(`/worker/job/${data.job.id}`)
      } else {
        setError(data.error || 'Failed to claim block')
      }
    } catch (err) {
      console.error('Failed to claim block:', err)
      setError('Failed to claim block. Please try again.')
    } finally {
      setIsClaiming(false)
    }
  }

  const availableCount = blocks.filter(b => b.status === 'available').length

  return (
    <div className="h-screen flex flex-col bg-neutral-50">
      {/* Header */}
      <header className="bg-white border-b px-4 py-3 flex items-center justify-between z-10">
        <div className="flex items-center gap-3">
          <Link
            href="/worker/dashboard"
            className="p-2 -ml-2 hover:bg-neutral-100 rounded-lg transition-colors"
          >
            <svg className="w-5 h-5 text-neutral-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
          </Link>
          <div>
            <h1 className="font-semibold text-neutral-900">Available Blocks</h1>
            <p className="text-xs text-neutral-500">
              {isLoading ? 'Loading...' : `${availableCount} blocks ready to claim`}
            </p>
          </div>
        </div>
        <button
          onClick={fetchBlocks}
          className="p-2 hover:bg-neutral-100 rounded-lg transition-colors"
          disabled={isLoading}
        >
          <svg
            className={`w-5 h-5 text-neutral-600 ${isLoading ? 'animate-spin' : ''}`}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
            />
          </svg>
        </button>
      </header>

      {/* Map Container */}
      <div className="flex-1 relative">
        {error && (
          <div className="absolute top-4 left-4 right-4 z-10 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg flex items-center justify-between">
            <span className="text-sm">{error}</span>
            <button onClick={() => setError(null)} className="text-red-500 hover:text-red-700">
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        )}

        {/* Residence-aware rendering. Until we know the mode (initial profile
            fetch is in flight) we render a soft skeleton instead of either map. */}
        {!residence && (
          <div className="h-full bg-neutral-100 animate-pulse flex items-center justify-center">
            <div className="text-center">
              <div className="animate-spin rounded-full h-10 w-10 border-b-2 border-brand-green-600 mx-auto mb-3" />
              <span className="text-neutral-500">Locating your area…</span>
            </div>
          </div>
        )}

        {residence?.mode === 'pilot' && (
          <>
            <BlockMap
              blocks={blocks}
              onBlockSelect={handleBlockSelect}
              selectedBlockId={selectedBlock?.id}
              height="100%"
              showTileLayers={true}
              showLegend={false}
            />

            {/* Floating Legend (pilot map only) */}
            <div className="absolute bottom-24 left-4 bg-white/95 backdrop-blur-sm rounded-lg shadow-lg p-3 text-xs">
              <div className="font-medium text-neutral-700 mb-2">Block Status</div>
              <div className="space-y-1.5">
                <div className="flex items-center gap-2">
                  <span className="w-3 h-3 rounded-full bg-[#22c55e]" />
                  <span className="text-neutral-600">Available</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="w-3 h-3 rounded-full bg-[#f59e0b]" />
                  <span className="text-neutral-600">Claimed</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="w-3 h-3 rounded-full bg-[#3b82f6]" />
                  <span className="text-neutral-600">In Progress</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="w-3 h-3 rounded-full bg-[#10b981]" />
                  <span className="text-neutral-600">Completed</span>
                </div>
              </div>
            </div>
          </>
        )}

        {residence?.mode === 'elsewhere' && <ElsewhereMap residence={residence} />}

        {residence?.mode === 'no-address' && <NoAddressPrompt />}
      </div>

      {/* Bottom Sheet - Block Details */}
      {selectedBlock && (
        <div className="bg-white border-t shadow-lg animate-slide-up">
          <div className="px-4 pt-3 pb-6 safe-area-bottom">
            {/* Handle */}
            <div className="w-12 h-1 bg-neutral-200 rounded-full mx-auto mb-4" />

            {/* Block Info */}
            <div className="flex items-start justify-between mb-4">
              <div>
                <div className="flex items-center gap-2 mb-1">
                  <h3 className="text-lg font-bold text-neutral-900">{selectedBlock.code}</h3>
                  <span
                    className={`
                      text-xs px-2 py-0.5 rounded-full font-medium
                      ${selectedBlock.status === 'available'
                        ? 'bg-green-100 text-green-700'
                        : 'bg-neutral-100 text-neutral-600'
                      }
                    `}
                  >
                    {STATUS_INFO[selectedBlock.status].label}
                  </span>
                </div>
                <p className="text-sm text-neutral-600">{formatStreetName(selectedBlock.streetName)}</p>
                {selectedBlock.fromCorner && selectedBlock.toCorner && (
                  <p className="text-xs text-neutral-500">
                    {selectedBlock.fromCorner} to {selectedBlock.toCorner}
                  </p>
                )}
              </div>
              <button
                onClick={() => setSelectedBlock(null)}
                className="p-2 hover:bg-neutral-100 rounded-lg transition-colors -mr-2"
              >
                <svg className="w-5 h-5 text-neutral-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            {/* Payout Info */}
            <div className="bg-gradient-to-r from-brand-green-50 to-emerald-50 rounded-lg p-3 mb-4">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-xs text-neutral-500 uppercase tracking-wide">Payout</p>
                  <p className="text-2xl font-bold text-neutral-900">
                    ${((selectedBlock.payoutAmount || 2000) / 100).toFixed(0)}
                  </p>
                </div>
                <div className="text-right">
                  <p className="text-xs text-neutral-500 uppercase tracking-wide">Difficulty</p>
                  <p className="text-sm font-medium text-neutral-700 capitalize">
                    {selectedBlock.difficulty || 'Standard'}
                  </p>
                </div>
              </div>
            </div>

            {/* Action Button */}
            {selectedBlock.status === 'available' ? (
              <button
                onClick={handleClaimBlock}
                disabled={isClaiming}
                className={`
                  w-full py-4 rounded-xl font-semibold text-white transition-all
                  ${isClaiming
                    ? 'bg-neutral-400 cursor-not-allowed'
                    : 'bg-gradient-to-r from-brand-green-600 to-brand-green-700 hover:from-brand-green-700 hover:to-brand-green-800 active:scale-[0.98]'
                  }
                `}
              >
                {isClaiming ? (
                  <span className="flex items-center justify-center gap-2">
                    <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                    </svg>
                    Claiming...
                  </span>
                ) : (
                  <span className="flex items-center justify-center gap-2">
                    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                    Claim This Block
                  </span>
                )}
              </button>
            ) : (
              <div className="w-full py-4 rounded-xl font-medium text-center bg-neutral-100 text-neutral-500">
                {STATUS_INFO[selectedBlock.status].description}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Empty State - No selection (pilot residents only) */}
      {residence?.mode === 'pilot' && !selectedBlock && !isLoading && blocks.length > 0 && (
        <div className="bg-white border-t p-4 text-center">
          <p className="text-sm text-neutral-500">
            Tap a <span className="text-green-600 font-medium">green block</span> on the map to claim it
          </p>
        </div>
      )}

      {/* Empty State - No blocks (pilot residents only) */}
      {residence?.mode === 'pilot' && !isLoading && blocks.length === 0 && (
        <div className="bg-white border-t p-6 text-center">
          <div className="w-16 h-16 bg-neutral-100 rounded-full flex items-center justify-center mx-auto mb-3">
            <svg className="w-8 h-8 text-neutral-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" />
            </svg>
          </div>
          <h3 className="font-semibold text-neutral-900 mb-1">No Blocks Available</h3>
          <p className="text-sm text-neutral-500 mb-4">
            All blocks are currently being worked on. Check back soon!
          </p>
          <button
            onClick={fetchBlocks}
            className="text-brand-green-600 font-medium text-sm hover:text-brand-green-700"
          >
            Refresh
          </button>
        </div>
      )}
    </div>
  )
}

/**
 * Map view for residents outside the active service area. Centers on the
 * user's zip + a "no routes available yet" overlay. If geocoding failed
 * (Nominatim outage / unrecognizable zip), we render a neutral world map
 * with the same overlay so the page never goes blank.
 */
function ElsewhereMap({ residence }: { residence: Residence }) {
  const [lng, lat] = residence.coords ?? [-95, 39] // continental US fallback
  const zoom = residence.coords ? 11 : 4
  const place =
    [residence.city, residence.state].filter(Boolean).join(', ') ||
    (residence.zip ? `Zip ${residence.zip}` : 'your area')

  return (
    <>
      <BaseMap
        height="100%"
        initialViewState={{ longitude: lng, latitude: lat, zoom }}
      />
      <div className="absolute inset-x-4 top-4 z-10 sm:inset-x-auto sm:left-1/2 sm:-translate-x-1/2 sm:max-w-md">
        <div className="bg-white rounded-2xl shadow-xl border border-neutral-200 p-5">
          <div className="flex items-start gap-3">
            <div className="w-10 h-10 rounded-xl bg-amber-100 text-amber-700 flex items-center justify-center flex-shrink-0">
              <AlertCircle className="w-5 h-5" />
            </div>
            <div className="flex-1 min-w-0">
              <h2 className="font-bold text-neutral-900 mb-1">
                No paid routes in {place} yet
              </h2>
              <p className="text-sm text-neutral-600 mb-3">
                We're expanding city by city. We'll email you the moment paid blocks
                go live in your neighborhood. In the meantime, you can still submit{' '}
                <Link href="/community" className="text-brand-green-700 font-medium hover:underline">
                  spot reports
                </Link>{' '}
                anywhere in the world.
              </p>
              <div className="flex flex-wrap gap-2">
                <Link
                  href="/worker/profile"
                  className="inline-flex items-center gap-1.5 text-xs font-semibold px-3 py-1.5 rounded-lg bg-neutral-100 text-neutral-700 hover:bg-neutral-200 transition-colors"
                >
                  <MapPin className="w-3.5 h-3.5" />
                  Update address
                </Link>
                <Link
                  href="/community"
                  className="inline-flex items-center gap-1.5 text-xs font-semibold px-3 py-1.5 rounded-lg bg-brand-green-600 text-white hover:bg-brand-green-700 transition-colors"
                >
                  Submit a spot report
                </Link>
              </div>
            </div>
          </div>
        </div>
      </div>
    </>
  )
}

/**
 * Empty state when the worker has not entered an address yet — there's no
 * point in geocoding nothing, so we prompt them to fill out their profile
 * before showing any map.
 */
function NoAddressPrompt() {
  return (
    <div className="h-full flex items-center justify-center bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 p-6">
      <div className="max-w-md w-full text-center">
        <div className="w-16 h-16 mx-auto mb-5 rounded-2xl bg-brand-green-100 text-brand-green-700 flex items-center justify-center">
          <MapPin className="w-8 h-8" />
        </div>
        <h2 className="text-2xl font-bold text-neutral-900 mb-2">
          Add your address to see routes near you
        </h2>
        <p className="text-neutral-600 mb-6">
          We use your address to show paid routes available in your community —
          and to email you when blocks unlock in your area.
        </p>
        <div className="flex flex-col sm:flex-row gap-3 justify-center">
          <Link
            href="/worker/profile"
            className="inline-flex items-center justify-center gap-2 px-5 py-3 rounded-xl bg-brand-green-600 text-white font-semibold hover:bg-brand-green-700 hover-lift transition-all"
          >
            <MapPin className="w-4 h-4" />
            Add my address
          </Link>
          <Link
            href="/community"
            className="inline-flex items-center justify-center gap-2 px-5 py-3 rounded-xl bg-white border-2 border-neutral-200 text-neutral-700 font-semibold hover:bg-neutral-50 transition-colors"
          >
            <Mail className="w-4 h-4" />
            Submit a spot report
          </Link>
        </div>
      </div>
    </div>
  )
}
