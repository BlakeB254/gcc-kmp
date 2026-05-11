'use client'

import { useEffect, useMemo, useState } from 'react'
import dynamic from 'next/dynamic'
import Link from 'next/link'
import {
  DollarSign,
  Globe,
  Lock,
  Heart,
  CheckCircle2,
  MapPin,
  ArrowLeft,
  Camera,
  AlertCircle,
} from 'lucide-react'
import {
  MAP_STYLE_URL,
  CHICAGO_CENTER_LNG_LAT,
  ZOOM,
  BOUNTY_MINIMUM_CENTS,
} from '@/lib/design-tokens'

// Dynamically import Map to avoid SSR issues with MapLibre
const Map = dynamic(
  () => import('react-map-gl/maplibre').then(mod => ({ default: mod.default })),
  { ssr: false }
)
const MarkerComponent = dynamic(
  () => import('react-map-gl/maplibre').then(mod => ({ default: mod.Marker })),
  { ssr: false }
)
const NavigationControl = dynamic(
  () => import('react-map-gl/maplibre').then(mod => ({ default: mod.NavigationControl })),
  { ssr: false }
)

const BOUNTY_MINIMUM_DOLLARS = BOUNTY_MINIMUM_CENTS / 100
const QUICK_AMOUNTS = [10, 25, 50, 100]
const TIP_AMOUNTS = [2, 5, 10]

type BountyVisibility = 'public' | 'secret'

interface SpotOption {
  id: string
  label: string
  description?: string
  status?: string
  coordinates: [number, number]
  radiusMeters: number
  mediaUrls: string[]
  createdAt?: string
  bountyId?: string | number | null
}

export default function PostBountyPage() {
  const [spots, setSpots] = useState<SpotOption[]>([])
  const [selectedSpotId, setSelectedSpotId] = useState<string | null>(null)
  const [spotsLoading, setSpotsLoading] = useState(true)

  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [amount, setAmount] = useState('')
  const [selectedQuickAmount, setSelectedQuickAmount] = useState<number | null>(null)
  const [visibility, setVisibility] = useState<BountyVisibility>('public')
  const [tipSpotter, setTipSpotter] = useState(false)
  const [tipAmount, setTipAmount] = useState('')
  const [selectedTipAmount, setSelectedTipAmount] = useState<number | null>(null)

  const [submitted, setSubmitted] = useState(false)
  const [amountTouched, setAmountTouched] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  const selectedSpot = useMemo(
    () => spots.find((spot) => spot.id === selectedSpotId) ?? null,
    [spots, selectedSpotId]
  )

  const currentAmount = selectedQuickAmount ?? (Number(amount) || 0)
  const currentTip = tipSpotter ? (selectedTipAmount ?? (Number(tipAmount) || 0)) : 0
  const total = currentAmount + currentTip
  const amountError = amountTouched && currentAmount > 0 && currentAmount < BOUNTY_MINIMUM_DOLLARS

  useEffect(() => {
    async function loadSpots() {
      setSpotsLoading(true)
      try {
        const response = await fetch('/api/spots?limit=100')
        if (!response.ok) throw new Error('Failed to load spots')

        const data = await response.json()
        const mapSpots = data.spots || []
        const fullReports = data.data || []
        const querySpotId = typeof window !== 'undefined'
          ? new URLSearchParams(window.location.search).get('spotId')
          : null

        const nextSpots = mapSpots
          .map((spot: any) => {
            const full = fullReports.find((report: any) => String(report.id) === String(spot.id))
            const lng = spot.coordinates?.[0] ?? full?.coordinates?.[0] ?? full?.locationLng
            const lat = spot.coordinates?.[1] ?? full?.coordinates?.[1] ?? full?.locationLat
            const status = full?.status || spot.status || 'pending'
            const mediaUrls = Array.isArray(full?.mediaUrls)
              ? full.mediaUrls
              : full?.photoUrl
              ? [full.photoUrl]
              : []

            if (!Number.isFinite(Number(lng)) || !Number.isFinite(Number(lat))) return null
            if (status === 'resolved' || status === 'completed') return null

            return {
              id: String(spot.id),
              label: spot.label?.replace(/_/g, ' ') || full?.title || 'Spot report',
              description: full?.description || undefined,
              status,
              coordinates: [Number(lng), Number(lat)] as [number, number],
              radiusMeters: Number(spot.radiusMeters ?? full?.affectedRadiusMeters ?? 30),
              mediaUrls,
              createdAt: full?.createdAt || undefined,
              bountyId: full?.bountyId ?? null,
            }
          })
          .filter(Boolean) as SpotOption[]

        setSpots(nextSpots)

        const initialSpot =
          nextSpots.find((spot) => spot.id === querySpotId) ??
          nextSpots.find((spot) => !spot.bountyId) ??
          nextSpots[0] ??
          null
        if (initialSpot) {
          setSelectedSpotId(initialSpot.id)
          setTitle((existing) => existing || `Cleanup bounty for ${initialSpot.label}`)
          setDescription((existing) => existing || initialSpot.description || '')
        }
      } catch {
        setSpots([])
      } finally {
        setSpotsLoading(false)
      }
    }

    loadSpots()
  }, [])

  const selectSpot = (spot: SpotOption) => {
    setSelectedSpotId(spot.id)
    setSubmitError(null)
    setTitle((existing) => existing || `Cleanup bounty for ${spot.label}`)
    setDescription((existing) => existing || spot.description || '')
  }

  const handleQuickAmount = (value: number) => {
    setSelectedQuickAmount(value)
    setAmount(String(value))
    setAmountTouched(true)
  }

  const handleAmountChange = (value: string) => {
    setAmount(value)
    setAmountTouched(true)
    const numValue = Number(value)
    setSelectedQuickAmount(QUICK_AMOUNTS.includes(numValue) ? numValue : null)
  }

  const handleTipQuickAmount = (value: number) => {
    setSelectedTipAmount(value)
    setTipAmount(String(value))
  }

  const handleTipAmountChange = (value: string) => {
    setTipAmount(value)
    const numValue = Number(value)
    setSelectedTipAmount(TIP_AMOUNTS.includes(numValue) ? numValue : null)
  }

  const canSubmit =
    selectedSpot !== null &&
    title.trim() !== '' &&
    currentAmount >= BOUNTY_MINIMUM_DOLLARS &&
    !isSubmitting

  const handleSubmit = async () => {
    setAmountTouched(true)
    setSubmitError(null)
    if (!canSubmit || !selectedSpot) return

    setIsSubmitting(true)
    try {
      const response = await fetch('/api/bounties', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          triggerReportId: Number(selectedSpot.id),
          amount: Math.round(currentAmount * 100),
          title: title.trim(),
          description: description.trim() || selectedSpot.description || null,
          visibility,
          isAnonymous: true,
        }),
      })
      const payload = await response.json().catch(() => null)

      if (!response.ok || payload?.success === false) {
        setSubmitError(
          payload?.message ||
          (response.status === 401
            ? 'Sign in with the app before posting a bounty.'
            : 'Bounty could not be posted.')
        )
        return
      }

      setSubmitted(true)
    } catch {
      setSubmitError('Bounty could not be posted. Check your connection and try again.')
    } finally {
      setIsSubmitting(false)
    }
  }

  if (submitted && selectedSpot) {
    return (
      <div className="min-h-screen flex items-center justify-center p-4">
        <div className="max-w-md w-full bg-green-50 border-2 border-green-200 rounded-2xl p-8 text-center">
          <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <CheckCircle2 className="w-8 h-8 text-green-600" />
          </div>
          <h2 className="text-2xl font-bold text-green-800 mb-2">Bounty Posted!</h2>
          <p className="text-green-700 mb-6">
            Your bounty &ldquo;{title}&rdquo; for <strong>${currentAmount}</strong>
            {currentTip > 0 && <> (+ ${currentTip} spotter tip)</>} has been attached to a real spot report.
          </p>
          <div className="bg-white rounded-xl p-4 mb-6 text-left space-y-2 border border-green-200">
            <div className="flex justify-between text-sm">
              <span className="text-neutral-600">Spot</span>
              <span className="text-neutral-900 font-medium">{selectedSpot.label}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-neutral-600">Bounty Amount</span>
              <span className="text-neutral-900 font-medium">${currentAmount}</span>
            </div>
            {currentTip > 0 && (
              <div className="flex justify-between text-sm">
                <span className="text-neutral-600">Spotter Tip</span>
                <span className="text-neutral-900 font-medium">${currentTip}</span>
              </div>
            )}
            <div className="flex justify-between text-sm pt-2 border-t border-green-100">
              <span className="text-neutral-900 font-semibold">Total</span>
              <span className="text-green-700 font-bold">${total}</span>
            </div>
          </div>
          <Link
            href="/bounties"
            className="inline-flex items-center justify-center gap-2 bg-green-600 hover:bg-green-700 text-white font-semibold px-6 py-3 rounded-xl transition-colors"
          >
            View All Bounties
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-neutral-50">
      <div className="section-container py-8 lg:py-12">
        <Link
          href="/bounties"
          className="inline-flex items-center gap-1.5 text-sm text-neutral-500 hover:text-neutral-700 mb-6"
        >
          <ArrowLeft className="w-4 h-4" />
          Back to Bounties
        </Link>

        <h1 className="text-3xl font-bold text-neutral-900 mb-2">Post a Bounty</h1>
        <p className="text-neutral-600 mb-8">
          Select an active spot report, add at least $10, and turn it into a funded cleanup bounty.
        </p>

        <div className="grid lg:grid-cols-2 gap-8">
          <div className="space-y-4">
            <label className="block text-sm font-medium text-neutral-700">
              <MapPin className="w-4 h-4 inline-block mr-1" />
              Choose a reported spot *
            </label>
            <div className="h-[350px] rounded-xl overflow-hidden border border-neutral-200">
              <Map
                initialViewState={{
                  longitude: selectedSpot?.coordinates[0] ?? CHICAGO_CENTER_LNG_LAT[0],
                  latitude: selectedSpot?.coordinates[1] ?? CHICAGO_CENTER_LNG_LAT[1],
                  zoom: selectedSpot ? ZOOM.ROUTE : ZOOM.NEIGHBORHOOD,
                }}
                mapStyle={MAP_STYLE_URL}
                cursor="default"
                style={{ width: '100%', height: '100%' }}
              >
                <NavigationControl position="top-right" />
                {spots.map((spot) => {
                  const selected = spot.id === selectedSpotId
                  return (
                    <MarkerComponent
                      key={spot.id}
                      longitude={spot.coordinates[0]}
                      latitude={spot.coordinates[1]}
                      anchor="bottom"
                    >
                      <button
                        type="button"
                        onClick={() => selectSpot(spot)}
                        className="flex flex-col items-center"
                        aria-label={`Select ${spot.label}`}
                      >
                        <div className={`rounded-full border-2 border-white shadow-lg flex items-center justify-center ${
                          selected ? 'w-10 h-10 bg-red-500' : 'w-8 h-8 bg-orange-500'
                        }`}>
                          {selected ? (
                            <DollarSign className="w-4 h-4 text-white" />
                          ) : (
                            <MapPin className="w-4 h-4 text-white" />
                          )}
                        </div>
                        <div className={`w-0 h-0 border-l-[6px] border-r-[6px] border-t-[8px] border-l-transparent border-r-transparent -mt-0.5 ${
                          selected ? 'border-t-red-500' : 'border-t-orange-500'
                        }`} />
                      </button>
                    </MarkerComponent>
                  )
                })}
              </Map>
            </div>

            {spotsLoading ? (
              <p className="text-sm text-neutral-500">Loading active spot reports...</p>
            ) : spots.length === 0 ? (
              <div className="rounded-xl border border-neutral-200 bg-white p-4 text-sm text-neutral-600">
                No active spot reports are available for bounties right now. Report a spot in the app first, then come back to fund it.
              </div>
            ) : (
              <div className="max-h-[360px] space-y-3 overflow-y-auto pr-1">
                {spots.map((spot) => (
                  <button
                    key={spot.id}
                    type="button"
                    onClick={() => selectSpot(spot)}
                    className={`w-full rounded-xl border bg-white p-3 text-left transition-colors ${
                      spot.id === selectedSpotId
                        ? 'border-red-500 ring-2 ring-red-100'
                        : 'border-neutral-200 hover:border-neutral-300'
                    }`}
                  >
                    <div className="flex gap-3">
                      {spot.mediaUrls.length > 0 ? (
                        <img
                          src={spot.mediaUrls[0]}
                          alt=""
                          className="h-16 w-20 flex-shrink-0 rounded-lg object-cover"
                        />
                      ) : (
                        <div className="h-16 w-20 flex-shrink-0 rounded-lg bg-neutral-100 flex items-center justify-center text-neutral-400">
                          <Camera className="h-5 w-5" />
                        </div>
                      )}
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center justify-between gap-2">
                          <p className="truncate text-sm font-semibold capitalize text-neutral-900">{spot.label}</p>
                          {spot.status && (
                            <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium capitalize text-amber-700">
                              {spot.status}
                            </span>
                          )}
                        </div>
                        {spot.description && (
                          <p className="mt-1 line-clamp-2 text-xs text-neutral-500">{spot.description}</p>
                        )}
                        <p className="mt-1 text-xs text-neutral-400">~{Math.round(spot.radiusMeters)}m radius</p>
                      </div>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="space-y-6">
            {selectedSpot && (
              <div className="rounded-xl border border-neutral-200 bg-white p-4">
                <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-neutral-900">
                  <MapPin className="h-4 w-4 text-red-500" />
                  Selected spot
                </div>
                <div className="flex gap-3">
                  {selectedSpot.mediaUrls.length > 0 ? (
                    <img
                      src={selectedSpot.mediaUrls[0]}
                      alt=""
                      className="h-20 w-24 flex-shrink-0 rounded-lg object-cover"
                    />
                  ) : (
                    <div className="h-20 w-24 flex-shrink-0 rounded-lg bg-neutral-100 flex items-center justify-center text-neutral-400">
                      <Camera className="h-6 w-6" />
                    </div>
                  )}
                  <div>
                    <p className="font-medium capitalize text-neutral-900">{selectedSpot.label}</p>
                    {selectedSpot.description && (
                      <p className="mt-1 line-clamp-2 text-sm text-neutral-500">{selectedSpot.description}</p>
                    )}
                  </div>
                </div>
              </div>
            )}

            <div>
              <label className="block text-sm font-medium text-neutral-700 mb-1.5">
                Title *
              </label>
              <input
                type="text"
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                placeholder="e.g., Cleanup bounty for reported dumping"
                className="input"
                required
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-neutral-700 mb-1.5">
                Description (optional)
              </label>
              <textarea
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                placeholder="Add any details a cleaner should know..."
                className="input min-h-[80px]"
                maxLength={500}
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-neutral-700 mb-1.5">
                Bounty Amount *
              </label>
              <div className="grid grid-cols-4 gap-2 mb-3">
                {QUICK_AMOUNTS.map((val) => (
                  <button
                    key={val}
                    type="button"
                    onClick={() => handleQuickAmount(val)}
                    className={`py-3 rounded-lg font-semibold transition-all ${
                      selectedQuickAmount === val
                        ? 'bg-red-500 text-white shadow-md'
                        : 'bg-neutral-100 text-neutral-700 hover:bg-neutral-200'
                    }`}
                  >
                    ${val}
                  </button>
                ))}
              </div>
              <div className="relative">
                <span className="absolute left-4 top-1/2 -translate-y-1/2 text-neutral-400 font-medium">
                  $
                </span>
                <input
                  type="number"
                  min={BOUNTY_MINIMUM_DOLLARS}
                  step="1"
                  value={amount}
                  onChange={(event) => handleAmountChange(event.target.value)}
                  onBlur={() => setAmountTouched(true)}
                  onFocus={(event) => event.currentTarget.select()}
                  placeholder={`${BOUNTY_MINIMUM_DOLLARS} minimum`}
                  className={`input pl-10 ${
                    amountError
                      ? 'border-red-500 ring-2 ring-red-200 focus:ring-red-300'
                      : ''
                  }`}
                />
              </div>
              {amountError && (
                <p className="text-sm text-red-600 mt-1">
                  Minimum bounty amount is ${BOUNTY_MINIMUM_DOLLARS}
                </p>
              )}
            </div>

            <div>
              <label className="block text-sm font-medium text-neutral-700 mb-1.5">
                Visibility
              </label>
              <div className="grid grid-cols-2 gap-2">
                <button
                  type="button"
                  onClick={() => setVisibility('public')}
                  className={`flex items-center justify-center gap-2 py-3 rounded-lg font-medium transition-all ${
                    visibility === 'public'
                      ? 'bg-green-100 text-green-700 border-2 border-green-500'
                      : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200'
                  }`}
                >
                  <Globe className="w-4 h-4" />
                  Public
                </button>
                <button
                  type="button"
                  onClick={() => setVisibility('secret')}
                  className={`flex items-center justify-center gap-2 py-3 rounded-lg font-medium transition-all ${
                    visibility === 'secret'
                      ? 'bg-purple-100 text-purple-700 border-2 border-purple-500'
                      : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200'
                  }`}
                >
                  <Lock className="w-4 h-4" />
                  Secret
                </button>
              </div>
            </div>

            <div>
              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={tipSpotter}
                  onChange={(event) => {
                    setTipSpotter(event.target.checked)
                    if (!event.target.checked) {
                      setTipAmount('')
                      setSelectedTipAmount(null)
                    }
                  }}
                  className="rounded border-neutral-300 text-orange-500 focus:ring-orange-500"
                />
                <Heart className="w-4 h-4 text-orange-500" />
                <span className="text-sm font-medium text-neutral-700">
                  Tip the Spotter
                </span>
              </label>

              {tipSpotter && (
                <div className="mt-3 pl-6">
                  <div className="grid grid-cols-3 gap-2 mb-2">
                    {TIP_AMOUNTS.map((val) => (
                      <button
                        key={val}
                        type="button"
                        onClick={() => handleTipQuickAmount(val)}
                        className={`py-2 rounded-lg text-sm font-semibold transition-all ${
                          selectedTipAmount === val
                            ? 'bg-orange-500 text-white'
                            : 'bg-orange-50 text-orange-700 hover:bg-orange-100'
                        }`}
                      >
                        ${val}
                      </button>
                    ))}
                  </div>
                  <div className="relative">
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400 text-sm">
                      $
                    </span>
                    <input
                      type="number"
                      min="1"
                      step="1"
                      value={tipAmount}
                      onChange={(event) => handleTipAmountChange(event.target.value)}
                      placeholder="Custom tip"
                      className="input pl-8 text-sm"
                    />
                  </div>
                </div>
              )}
            </div>

            {currentAmount > 0 && (
              <div className="bg-neutral-100 rounded-xl p-4 space-y-1 text-sm">
                <div className="flex justify-between">
                  <span className="text-neutral-600">Bounty</span>
                  <span className="font-medium text-neutral-900">${currentAmount}</span>
                </div>
                {currentTip > 0 && (
                  <div className="flex justify-between">
                    <span className="text-neutral-600">Spotter Tip</span>
                    <span className="font-medium text-neutral-900">${currentTip}</span>
                  </div>
                )}
                <div className="flex justify-between pt-2 border-t border-neutral-200">
                  <span className="font-semibold text-neutral-900">Total</span>
                  <span className="font-bold text-red-600">${total}</span>
                </div>
              </div>
            )}

            {submitError && (
              <div className="flex gap-2 rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700">
                <AlertCircle className="h-4 w-4 flex-shrink-0 mt-0.5" />
                <span>{submitError}</span>
              </div>
            )}

            <button
              type="button"
              onClick={handleSubmit}
              disabled={!canSubmit}
              className="w-full py-4 text-lg font-semibold rounded-xl transition-all disabled:opacity-50 disabled:cursor-not-allowed bg-red-500 hover:bg-red-600 text-white"
            >
              {isSubmitting ? 'Posting...' : `Post Bounty${currentAmount > 0 ? ` - $${total}` : ''}`}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
