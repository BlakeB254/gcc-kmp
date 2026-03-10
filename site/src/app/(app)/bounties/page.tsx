'use client'

import { useState, useEffect, useMemo } from 'react'
import dynamic from 'next/dynamic'
import Link from 'next/link'
import { MapPin, DollarSign, Trash2, ArrowRight, Route as RouteIcon, X, ChevronLeft, ChevronRight, Clock, AlertTriangle, Grid3X3 } from 'lucide-react'
import type { Bounty, SpotReport } from '@/components/map/BountyMap'
import type { Route } from '@/components/map/RouteMap'
import type { BlockOnMap } from '@/components/map/CombinedMap'

// Dynamically import CombinedMap to avoid SSR issues with MapLibre
const CombinedMap = dynamic(
  () => import('@/components/map/CombinedMap').then(mod => ({ default: mod.CombinedMap })),
  {
    ssr: false,
    loading: () => (
      <div className="h-[500px] bg-neutral-100 rounded-xl animate-pulse flex items-center justify-center">
        <span className="text-neutral-400">Loading map...</span>
      </div>
    ),
  }
)

// ============================================================================
// STATUS HELPERS
// ============================================================================

const ROUTE_STATUS_COLORS: Record<string, string> = {
  published: 'bg-green-100 text-green-700',
  draft: 'bg-neutral-100 text-neutral-500',
  archived: 'bg-amber-100 text-amber-700',
}

const ROUTE_STATUS_LABELS: Record<string, string> = {
  published: 'Available',
  draft: 'Coming Soon',
  archived: 'Archived',
}

const BLOCK_STATUS_BADGE: Record<string, string> = {
  available: 'bg-green-100 text-green-700',
  funded: 'bg-emerald-100 text-emerald-700',
  in_progress: 'bg-blue-100 text-blue-700',
  completed: 'bg-neutral-100 text-neutral-600',
}

// ============================================================================
// TIME HELPERS
// ============================================================================

function timeAgo(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime()
  const hours = Math.floor(diff / (1000 * 60 * 60))
  if (hours < 1) return 'Just now'
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  if (days === 1) return '1 day ago'
  return `${days} days ago`
}

function isOverdue(dateStr: string | undefined | null): boolean {
  if (!dateStr) return false
  return new Date(dateStr) <= new Date()
}

// ============================================================================
// PAGE COMPONENT
// ============================================================================

export default function BountiesPage() {
  const [bounties, setBounties] = useState<Bounty[]>([])
  const [spots, setSpots] = useState<(SpotReport & { needsVerification?: boolean; nextVerificationDue?: string; lastVerifiedAt?: string })[]>([])
  const [routes, setRoutes] = useState<Route[]>([])
  const [blocks, setBlocks] = useState<any[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [lightbox, setLightbox] = useState<{ urls: string[]; index: number } | null>(null)

  // Fetch data from API endpoints
  useEffect(() => {
    async function fetchData() {
      // Fetch bounties, spots, routes, and blocks in parallel
      const [bountiesResult, spotsResult, routesResult, blocksResult] = await Promise.allSettled([
        fetch('/api/bounties?limit=50').then(res => res.ok ? res.json() : null),
        fetch('/api/spots?limit=100').then(res => res.ok ? res.json() : null),
        fetch('/api/routes?status=all').then(res => res.ok ? res.json() : null),
        fetch('/api/blocks?limit=50').then(res => res.ok ? res.json() : null),
      ])

      // Process bounties (from community reports/sightings)
      if (bountiesResult.status === 'fulfilled' && bountiesResult.value) {
        const data = bountiesResult.value
        const apiBounties = data.bounties || data.data || []
        setBounties(apiBounties)
      }

      // Process spots — merge map markers with full report data for descriptions/photos
      if (spotsResult.status === 'fulfilled' && spotsResult.value) {
        const data = spotsResult.value
        const mapSpots = data.spots || []
        const fullReports = data.data || []
        if (mapSpots.length > 0) {
          // Enrich map spots with description, photos, status from full reports
          const enriched = mapSpots.map((s: any) => {
            const full = fullReports.find((r: any) => String(r.id) === String(s.id))
            return {
              ...s,
              description: full?.description || undefined,
              photoUrl: full?.photoUrl || (full?.mediaUrls?.[0]) || undefined,
              mediaUrls: full?.mediaUrls || [],
              status: full?.status || undefined,
              reporterName: full?.reporterName || undefined,
              createdAt: full?.createdAt || undefined,
              needsVerification: full?.needsVerification ?? s.needsVerification ?? false,
              nextVerificationDue: full?.nextVerificationDue || undefined,
              lastVerifiedAt: full?.lastVerifiedAt || undefined,
              tags: full?.tags || s.tags || [],
            }
          })
          setSpots(enriched)
        }
      }

      // Process routes - transform API response to match Route type
      if (routesResult.status === 'fulfilled' && routesResult.value) {
        const data = routesResult.value
        const apiRoutes = data.routes || data.data || []
        if (apiRoutes.length > 0) {
          const transformed = apiRoutes.map((r: any) => ({
            id: r.id,
            code: r.code,
            displayName: r.displayName,
            geometry: r.geometry,
            lengthMeters: r.distanceMeters || r.lengthMeters || null,
            estimatedMinutes: r.estimatedDurationMinutes || r.estimatedMinutes || null,
            payoutAmount: r.payoutAmount || 10000,
            status: r.status || 'published',
            checkpoints: (r.checkpoints || []).map((cp: any) => ({
              name: cp.name,
              sequenceOrder: cp.sequenceOrder,
              coordinates: Array.isArray(cp.coordinates)
                ? cp.coordinates
                : [cp.coordinates?.lng ?? 0, cp.coordinates?.lat ?? 0],
              photoRequired: cp.photoRequired ?? true,
              photoInstructions: cp.photoInstructions,
              geofenceRadius: cp.geofenceRadius || 15,
              type: cp.type || 'photo_point',
            })),
          }))
          setRoutes(transformed)
        }
      }

      // Process blocks
      if (blocksResult.status === 'fulfilled' && blocksResult.value) {
        const data = blocksResult.value
        const apiBlocks = data.blocks || data.data || []
        setBlocks(apiBlocks)
      }

      setIsLoading(false)
    }

    fetchData()
  }, [])

  // Build BlockOnMap objects from API data (displayImages come from the API)
  const blocksWithImages = useMemo<BlockOnMap[]>(() => {
    if (blocks.length === 0) return []

    return blocks
      .filter((b: any) => b.location?.boundary?.coordinates)
      .map((block: any) => {
        const boundary = block.location.boundary
        const center: [number, number] = block.location.center?.coordinates || [0, 0]
        const displayImages: string[] = block.displayImages || []

        return {
          id: block.id,
          code: block.code,
          name: block.name,
          status: block.status,
          boundary,
          center,
          funding: block.funding || { goal: 10000, current: 0 },
          displayImages,
          spotCount: displayImages.length,
          streetName: block.streetName,
          fromCorner: block.fromCorner,
          toCorner: block.toCorner,
          side: block.side,
        } satisfies BlockOnMap
      })
  }, [blocks])

  // Compute dynamic stats from fetched data
  const stats = useMemo(() => {
    const activeBounties = bounties.filter(b => b.status === 'open' || b.status === 'in_progress').length
    const totalPostedCents = bounties.reduce((sum, b) => sum + b.amountCents, 0)
    const cleanedCount = bounties.filter(b => b.status === 'completed').length
    const activeSpots = spots.length
    const totalBlocks = blocksWithImages.length
    const blocksWithPhotos = blocksWithImages.filter(b => b.displayImages.length > 0).length

    return {
      activeBounties,
      totalPosted: `$${(totalPostedCents / 100).toLocaleString()}`,
      cleaned: cleanedCount,
      activeSpots,
      totalBlocks,
      blocksWithPhotos,
    }
  }, [bounties, spots, blocksWithImages])

  return (
    <div className="min-h-screen">
      {/* Hero */}
      <section className="bg-gradient-to-br from-red-600 via-red-500 to-orange-500 text-white py-16 lg:py-20">
        <div className="section-container text-center">
          <h1 className="text-4xl sm:text-5xl font-bold mb-4">
            Community Bounties
          </h1>
          <p className="text-lg text-white/90 max-w-2xl mx-auto mb-6">
            See something dirty? Post a bounty. Someone in the community will claim it and clean it up.
            A peer-to-peer system that puts cleanup power in your hands.
          </p>
          <div className="flex flex-wrap justify-center gap-3">
            <Link
              href="/bounties/post"
              className="inline-flex items-center gap-2 bg-white text-red-600 font-semibold px-6 py-3 rounded-xl hover:bg-white/90 transition-colors"
            >
              <DollarSign className="w-5 h-5" />
              Post a Bounty
            </Link>
            <Link
              href="/bounties/how-it-works"
              className="inline-flex items-center gap-2 bg-white/20 text-white font-semibold px-6 py-3 rounded-xl hover:bg-white/30 transition-colors border border-white/30"
            >
              How It Works
              <ArrowRight className="w-4 h-4" />
            </Link>
          </div>
        </div>
      </section>

      {/* Stats Bar */}
      <section className="bg-neutral-900 text-white py-6">
        <div className="section-container">
          <div className="grid grid-cols-3 sm:grid-cols-5 gap-4 text-center">
            <div>
              <p className="text-2xl sm:text-3xl font-bold text-red-400">
                {isLoading ? '...' : stats.activeBounties}
              </p>
              <p className="text-sm text-neutral-400">Active Bounties</p>
            </div>
            <div>
              <p className="text-2xl sm:text-3xl font-bold text-orange-400">
                {isLoading ? '...' : stats.totalPosted}
              </p>
              <p className="text-sm text-neutral-400">Total Posted</p>
            </div>
            <div>
              <p className="text-2xl sm:text-3xl font-bold text-green-400">
                {isLoading ? '...' : stats.cleaned}
              </p>
              <p className="text-sm text-neutral-400">Cleaned</p>
            </div>
            {stats.activeSpots > 0 && (
              <div>
                <p className="text-2xl sm:text-3xl font-bold text-blue-400">
                  {stats.activeSpots}
                </p>
                <p className="text-sm text-neutral-400">Active Spots</p>
              </div>
            )}
            {stats.totalBlocks > 0 && (
              <div>
                <p className="text-2xl sm:text-3xl font-bold text-emerald-400">
                  {stats.totalBlocks}
                </p>
                <p className="text-sm text-neutral-400">Blocks</p>
              </div>
            )}
          </div>
        </div>
      </section>

      {/* Combined Map */}
      <section className="py-8 lg:py-12">
        <div className="section-container">
          <div className="mb-6 flex items-center justify-between">
            <h2 className="text-xl font-bold text-neutral-900">
              <MapPin className="w-5 h-5 inline-block mr-2 text-red-500" />
              Service Area Map
            </h2>
            <span className="text-sm text-neutral-500">
              {bounties.length} bounties
              {spots.length > 0 && ` \u00B7 ${spots.length} spots`}
              {blocksWithImages.length > 0 && ` \u00B7 ${blocksWithImages.length} blocks`}
              {routes.length > 0 && ` \u00B7 ${routes.length} routes`}
            </span>
          </div>
          <CombinedMap
            bounties={bounties}
            spots={spots}
            routes={routes}
            blocks={blocksWithImages}
            height="500px"
            showLegend={true}
          />

          {/* Bounty Cards */}
          <div className="mt-8">
            <h3 className="text-lg font-bold text-neutral-900 mb-4">Active Bounties</h3>
            {bounties.length === 0 && !isLoading && (
              <div className="text-center py-12 bg-neutral-50 rounded-xl border border-neutral-200">
                <MapPin className="w-10 h-10 mx-auto mb-3 text-neutral-300" />
                <p className="text-neutral-500 mb-2">No active bounties yet</p>
                <p className="text-sm text-neutral-400">Community sightings will appear here as bounties. Be the first to report an area that needs cleaning!</p>
              </div>
            )}
            <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-4">
              {bounties.map((bounty) => (
                <div
                  key={bounty.id}
                  className="bg-white rounded-xl border border-neutral-200 p-5 hover:shadow-md transition-shadow"
                >
                  <div className="flex items-start justify-between mb-3">
                    <h3 className="font-semibold text-neutral-900">{bounty.title}</h3>
                    <span
                      className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                        bounty.status === 'open'
                          ? 'bg-red-100 text-red-700'
                          : bounty.status === 'in_progress'
                          ? 'bg-blue-100 text-blue-700'
                          : 'bg-green-100 text-green-700'
                      }`}
                    >
                      {bounty.status === 'open'
                        ? 'Open'
                        : bounty.status === 'in_progress'
                        ? 'In Progress'
                        : 'Completed'}
                    </span>
                  </div>
                  <p className="text-sm text-neutral-600 mb-4 line-clamp-2">
                    {bounty.description}
                  </p>
                  <div className="flex items-center justify-between pt-3 border-t border-neutral-100">
                    <span className="text-lg font-bold text-green-600">
                      ${(bounty.amountCents / 100).toFixed(0)}
                    </span>
                    <span className="text-xs text-neutral-500">
                      {bounty.pledgeCount} {bounty.pledgeCount === 1 ? 'pledge' : 'pledges'}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Block Reports Section — shows blocks with matched spot images */}
          {blocksWithImages.length > 0 && (
            <div className="mt-10">
              <h3 className="text-lg font-bold text-neutral-900 mb-4 flex items-center gap-2">
                <Grid3X3 className="w-5 h-5 text-emerald-600" />
                Block Reports
                {stats.blocksWithPhotos > 0 && (
                  <span className="text-sm font-normal text-neutral-500">
                    ({stats.blocksWithPhotos} with photos)
                  </span>
                )}
              </h3>
              <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-4">
                {blocksWithImages.map((block) => {
                  const fundingPct = block.funding.goal > 0
                    ? Math.min(100, (block.funding.current / block.funding.goal) * 100)
                    : 0
                  return (
                    <div
                      key={block.id}
                      className="bg-white rounded-xl border border-neutral-200 overflow-hidden hover:shadow-md transition-shadow"
                    >
                      {/* Block display image */}
                      {block.displayImages.length > 0 && (
                        <div className="relative">
                          <img
                            src={block.displayImages[0]}
                            alt={`${block.name} report`}
                            className="w-full h-48 object-cover cursor-pointer hover:opacity-95 transition-opacity"
                            onClick={() => setLightbox({ urls: block.displayImages, index: 0 })}
                          />
                          {block.displayImages.length > 1 && (
                            <div className="absolute bottom-2 right-2 bg-black/60 text-white text-xs px-2 py-1 rounded-full">
                              {block.displayImages.length} photos
                            </div>
                          )}
                          <span
                            className={`absolute top-2 right-2 text-xs px-2 py-0.5 rounded-full font-medium capitalize ${
                              BLOCK_STATUS_BADGE[block.status] || 'bg-neutral-100 text-neutral-600'
                            }`}
                          >
                            {block.status.replace(/_/g, ' ')}
                          </span>
                        </div>
                      )}
                      <div className="p-4">
                        <div className="flex items-start justify-between mb-1">
                          <h4 className="font-semibold text-neutral-900">{block.name}</h4>
                          <span className="text-xs text-neutral-500 ml-2 flex-shrink-0">{block.code}</span>
                        </div>
                        {block.streetName && (
                          <p className="text-xs text-neutral-500 mb-2">
                            {block.streetName} &middot; {block.fromCorner} to {block.toCorner}
                            {block.side && ` (${block.side} side)`}
                          </p>
                        )}
                        {/* Funding progress */}
                        <div className="mb-2">
                          <div className="flex justify-between text-xs text-neutral-500 mb-1">
                            <span>${(block.funding.current / 100).toFixed(0)} raised</span>
                            <span>${(block.funding.goal / 100).toFixed(0)} goal</span>
                          </div>
                          <div className="h-1.5 bg-neutral-100 rounded-full overflow-hidden">
                            <div
                              className="h-full bg-green-500 rounded-full transition-all"
                              style={{ width: `${fundingPct}%` }}
                            />
                          </div>
                        </div>
                        <div className="flex items-center gap-3 text-xs text-neutral-500 pt-2 border-t border-neutral-100">
                          {block.spotCount > 0 && (
                            <span>{block.spotCount} spot report{block.spotCount !== 1 ? 's' : ''}</span>
                          )}
                          {!block.displayImages.length && (
                            <span
                              className={`px-2 py-0.5 rounded-full font-medium capitalize ${
                                BLOCK_STATUS_BADGE[block.status] || 'bg-neutral-100 text-neutral-600'
                              }`}
                            >
                              {block.status.replace(/_/g, ' ')}
                            </span>
                          )}
                        </div>
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {/* Spot Reports Section */}
          {spots.length > 0 && (
            <div className="mt-10">
              <h3 className="text-lg font-bold text-neutral-900 mb-4 flex items-center gap-2">
                <MapPin className="w-5 h-5 text-orange-500" />
                Active Spot Reports
              </h3>
              <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-4">
                {spots.map((spot) => {
                  const allPhotos = spot.mediaUrls?.length ? spot.mediaUrls : spot.photoUrl ? [spot.photoUrl] : []
                  const verificationOverdue = spot.needsVerification || isOverdue(spot.nextVerificationDue)
                  return (
                    <div
                      key={spot.id}
                      className={`bg-white rounded-xl border overflow-hidden hover:shadow-md transition-shadow ${
                        verificationOverdue ? 'border-amber-300' : 'border-neutral-200'
                      }`}
                    >
                      {/* Photo area — large hero image or photo strip */}
                      {allPhotos.length > 0 && (
                        <div className="relative">
                          <img
                            src={allPhotos[0]}
                            alt="Spot report"
                            className="w-full h-48 object-cover cursor-pointer hover:opacity-95 transition-opacity"
                            onClick={() => setLightbox({ urls: allPhotos, index: 0 })}
                          />
                          {allPhotos.length > 1 && (
                            <div className="absolute bottom-2 right-2 bg-black/60 text-white text-xs px-2 py-1 rounded-full">
                              {allPhotos.length} photos
                            </div>
                          )}
                          {/* Status badge */}
                          {spot.status && (
                            <span
                              className="absolute top-2 right-2 text-xs px-2 py-0.5 rounded-full text-white font-medium capitalize"
                              style={{ backgroundColor: spot.status === 'verified' ? '#22C55E' : spot.status === 'acknowledged' ? '#3B82F6' : '#F59E0B' }}
                            >
                              {spot.status}
                            </span>
                          )}
                          {/* Verification overdue indicator */}
                          {verificationOverdue && (
                            <span className="absolute top-2 left-2 flex items-center gap-1 text-xs px-2 py-0.5 rounded-full bg-amber-500 text-white font-medium">
                              <AlertTriangle className="w-3 h-3" />
                              Needs Re-check
                            </span>
                          )}
                        </div>
                      )}
                      <div className="p-4">
                        <div className="flex items-start justify-between mb-2">
                          <div className="flex items-center gap-2">
                            <span
                              className="w-3 h-3 rounded-full flex-shrink-0"
                              style={{ backgroundColor: verificationOverdue ? '#F59E0B' : '#F97316' }}
                            />
                            <h4 className="font-semibold text-neutral-900 capitalize">
                              {spot.label?.replace(/_/g, ' ') || 'Spot Report'}
                            </h4>
                          </div>
                          {!allPhotos.length && spot.status && (
                            <span
                              className={`text-xs px-2 py-0.5 rounded-full font-medium capitalize ${
                                spot.status === 'verified' ? 'bg-green-100 text-green-700'
                                : spot.status === 'acknowledged' ? 'bg-blue-100 text-blue-700'
                                : 'bg-amber-100 text-amber-700'
                              }`}
                            >
                              {spot.status}
                            </span>
                          )}
                        </div>
                        {/* Tags */}
                        {(spot as any).tags?.length > 0 && (
                          <div className="flex flex-wrap gap-1 mb-2">
                            {((spot as any).tags as string[]).map((tag: string) => (
                              <span
                                key={tag}
                                className="text-[11px] px-2 py-0.5 rounded-full bg-orange-50 text-orange-700 font-medium capitalize"
                              >
                                {tag}
                              </span>
                            ))}
                          </div>
                        )}
                        {/* Verification due warning (no-photo cards) */}
                        {verificationOverdue && !allPhotos.length && (
                          <div className="flex items-center gap-1.5 text-xs text-amber-600 bg-amber-50 rounded-md px-2 py-1 mb-2">
                            <AlertTriangle className="w-3.5 h-3.5" />
                            <span>Due for re-verification</span>
                          </div>
                        )}
                        {spot.description && (
                          <p className="text-sm text-neutral-600 mb-3 line-clamp-2">{spot.description}</p>
                        )}
                        <div className="flex items-center gap-3 text-xs text-neutral-500 pt-2 border-t border-neutral-100">
                          <span>~{spot.radiusMeters}m radius</span>
                          {spot.reporterName && <span>By {spot.reporterName}</span>}
                          {spot.createdAt && (
                            <span className="ml-auto flex items-center gap-1">
                              <Clock className="w-3 h-3" />
                              {timeAgo(spot.createdAt)}
                            </span>
                          )}
                        </div>
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {/* Routes Section */}
          {routes.length > 0 && (
            <div className="mt-10">
              <h3 className="text-lg font-bold text-neutral-900 mb-4 flex items-center gap-2">
                <RouteIcon className="w-5 h-5 text-green-600" />
                Cleanup Routes
              </h3>
              <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-4">
                {routes.map((route) => (
                  <div
                    key={route.id}
                    className="bg-white rounded-xl border border-neutral-200 p-5 hover:shadow-md transition-shadow"
                  >
                    <div className="flex items-start justify-between mb-3">
                      <div>
                        <h4 className="font-semibold text-neutral-900">{route.displayName}</h4>
                        <p className="text-xs text-neutral-500 mt-0.5">{route.code}</p>
                      </div>
                      <span
                        className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                          ROUTE_STATUS_COLORS[route.status] || 'bg-neutral-100 text-neutral-600'
                        }`}
                      >
                        {ROUTE_STATUS_LABELS[route.status] || route.status}
                      </span>
                    </div>
                    <div className="flex items-center gap-3 text-xs text-neutral-500 mb-3">
                      {route.lengthMeters && (
                        <span>{(route.lengthMeters / 1000).toFixed(1)} km</span>
                      )}
                      {route.estimatedMinutes && (
                        <span>{route.estimatedMinutes} min</span>
                      )}
                      <span>{route.checkpoints.length} checkpoints</span>
                    </div>
                    <div className="flex items-center justify-between pt-3 border-t border-neutral-100">
                      <span className="text-lg font-bold text-green-600">
                        ${(route.payoutAmount / 100).toFixed(0)}
                      </span>
                      {route.status === 'published' && (
                        <Link
                          href="/worker/signup"
                          className="text-sm font-medium text-green-600 hover:text-green-700 transition-colors"
                        >
                          Claim Route &rarr;
                        </Link>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </section>

      {/* Image Lightbox */}
      {lightbox && (
        <div
          className="fixed inset-0 z-50 bg-black/90 flex items-center justify-center"
          onClick={() => setLightbox(null)}
        >
          <button
            className="absolute top-4 right-4 text-white/80 hover:text-white z-10"
            onClick={() => setLightbox(null)}
          >
            <X className="w-8 h-8" />
          </button>
          {lightbox.urls.length > 1 && (
            <>
              <button
                className="absolute left-4 text-white/80 hover:text-white z-10 p-2"
                onClick={(e) => {
                  e.stopPropagation()
                  setLightbox({ ...lightbox, index: (lightbox.index - 1 + lightbox.urls.length) % lightbox.urls.length })
                }}
              >
                <ChevronLeft className="w-10 h-10" />
              </button>
              <button
                className="absolute right-4 text-white/80 hover:text-white z-10 p-2"
                onClick={(e) => {
                  e.stopPropagation()
                  setLightbox({ ...lightbox, index: (lightbox.index + 1) % lightbox.urls.length })
                }}
              >
                <ChevronRight className="w-10 h-10" />
              </button>
            </>
          )}
          <img
            src={lightbox.urls[lightbox.index]}
            alt={`Photo ${lightbox.index + 1} of ${lightbox.urls.length}`}
            className="max-h-[85vh] max-w-[90vw] object-contain rounded-lg"
            onClick={(e) => e.stopPropagation()}
          />
          {lightbox.urls.length > 1 && (
            <div className="absolute bottom-6 text-white/80 text-sm">
              {lightbox.index + 1} / {lightbox.urls.length}
            </div>
          )}
        </div>
      )}

      {/* CTA */}
      <section className="bg-gradient-to-r from-neutral-900 to-neutral-800 text-white py-16">
        <div className="section-container text-center">
          <Trash2 className="w-10 h-10 mx-auto mb-4 text-red-400" />
          <h2 className="text-2xl sm:text-3xl font-bold mb-3">
            Download the App to Post or Claim Bounties
          </h2>
          <p className="text-neutral-400 max-w-xl mx-auto mb-8">
            GPS-verified cleanups ensure bounties only pay out when the job is actually done.
            Available on iOS and Android.
          </p>
          <div className="flex flex-wrap justify-center gap-4">
            <a
              href="#"
              className="inline-flex items-center gap-3 bg-white text-neutral-900 font-semibold px-6 py-3.5 rounded-xl hover:bg-neutral-100 transition-colors"
            >
              <svg className="w-6 h-6" viewBox="0 0 24 24" fill="currentColor">
                <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.8-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z" />
              </svg>
              App Store
            </a>
            <a
              href="#"
              className="inline-flex items-center gap-3 bg-white text-neutral-900 font-semibold px-6 py-3.5 rounded-xl hover:bg-neutral-100 transition-colors"
            >
              <svg className="w-6 h-6" viewBox="0 0 24 24" fill="currentColor">
                <path d="M3.609 1.814L13.792 12 3.61 22.186a.996.996 0 01-.61-.92V2.734a1 1 0 01.609-.92zm10.89 10.893l2.302 2.302-10.937 6.333 8.635-8.635zm3.199-3.199l2.807 1.626a1 1 0 010 1.732l-2.807 1.626L15.206 12l2.492-2.492zM5.864 2.658L16.8 8.99l-2.302 2.302-8.634-8.634z" />
              </svg>
              Google Play
            </a>
          </div>
        </div>
      </section>
    </div>
  )
}
