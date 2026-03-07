'use client'

import { useState, useCallback } from 'react'
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

interface BountyPin {
  lng: number
  lat: number
}

export default function PostBountyPage() {
  // Map pin state
  const [pin, setPin] = useState<BountyPin | null>(null)

  // Form state
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [amount, setAmount] = useState('')
  const [selectedQuickAmount, setSelectedQuickAmount] = useState<number | null>(null)
  const [visibility, setVisibility] = useState<BountyVisibility>('public')
  const [tipSpotter, setTipSpotter] = useState(false)
  const [tipAmount, setTipAmount] = useState('')
  const [selectedTipAmount, setSelectedTipAmount] = useState<number | null>(null)

  // UI state
  const [submitted, setSubmitted] = useState(false)
  const [amountTouched, setAmountTouched] = useState(false)

  const currentAmount = selectedQuickAmount ?? (Number(amount) || 0)
  const currentTip = tipSpotter ? (selectedTipAmount ?? (Number(tipAmount) || 0)) : 0
  const total = currentAmount + currentTip
  const amountError = amountTouched && currentAmount > 0 && currentAmount < BOUNTY_MINIMUM_DOLLARS

  const handleMapClick = useCallback((event: { lngLat: { lng: number; lat: number } }) => {
    setPin({ lng: event.lngLat.lng, lat: event.lngLat.lat })
  }, [])

  const handleQuickAmount = (value: number) => {
    setSelectedQuickAmount(value)
    setAmount(String(value))
    setAmountTouched(true)
  }

  const handleAmountChange = (value: string) => {
    setAmount(value)
    setAmountTouched(true)
    const numValue = Number(value)
    if (QUICK_AMOUNTS.includes(numValue)) {
      setSelectedQuickAmount(numValue)
    } else {
      setSelectedQuickAmount(null)
    }
  }

  const handleTipQuickAmount = (value: number) => {
    setSelectedTipAmount(value)
    setTipAmount(String(value))
  }

  const handleTipAmountChange = (value: string) => {
    setTipAmount(value)
    const numValue = Number(value)
    if (TIP_AMOUNTS.includes(numValue)) {
      setSelectedTipAmount(numValue)
    } else {
      setSelectedTipAmount(null)
    }
  }

  const canSubmit =
    title.trim() !== '' &&
    pin !== null &&
    currentAmount >= BOUNTY_MINIMUM_DOLLARS

  const handleSubmit = () => {
    if (!canSubmit) return
    setSubmitted(true)
  }

  if (submitted) {
    return (
      <div className="min-h-screen flex items-center justify-center p-4">
        <div className="max-w-md w-full bg-green-50 border-2 border-green-200 rounded-2xl p-8 text-center">
          <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <CheckCircle2 className="w-8 h-8 text-green-600" />
          </div>
          <h2 className="text-2xl font-bold text-green-800 mb-2">Bounty Posted!</h2>
          <p className="text-green-700 mb-6">
            Your bounty &ldquo;{title}&rdquo; for <strong>${currentAmount}</strong>
            {currentTip > 0 && <> (+ ${currentTip} spotter tip)</>} has been submitted.
            A cleaner in the area will be notified.
          </p>
          <div className="bg-white rounded-xl p-4 mb-6 text-left space-y-2 border border-green-200">
            <div className="flex justify-between text-sm">
              <span className="text-neutral-600">Location</span>
              <span className="text-neutral-900 font-medium">
                {pin?.lat.toFixed(4)}, {pin?.lng.toFixed(4)}
              </span>
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
            <div className="flex justify-between text-sm">
              <span className="text-neutral-600">Visibility</span>
              <span className="text-neutral-900 font-medium capitalize">{visibility}</span>
            </div>
          </div>
          <div className="flex flex-col gap-3">
            <Link
              href="/bounties"
              className="inline-flex items-center justify-center gap-2 bg-green-600 hover:bg-green-700 text-white font-semibold px-6 py-3 rounded-xl transition-colors"
            >
              View All Bounties
            </Link>
            <button
              onClick={() => {
                setSubmitted(false)
                setTitle('')
                setDescription('')
                setAmount('')
                setSelectedQuickAmount(null)
                setPin(null)
                setTipSpotter(false)
                setTipAmount('')
                setSelectedTipAmount(null)
                setAmountTouched(false)
              }}
              className="text-sm text-neutral-600 hover:text-neutral-800"
            >
              Post Another Bounty
            </button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-neutral-50">
      <div className="section-container py-8 lg:py-12">
        {/* Back link */}
        <Link
          href="/bounties"
          className="inline-flex items-center gap-1.5 text-sm text-neutral-500 hover:text-neutral-700 mb-6"
        >
          <ArrowLeft className="w-4 h-4" />
          Back to Bounties
        </Link>

        <h1 className="text-3xl font-bold text-neutral-900 mb-2">Post a Bounty</h1>
        <p className="text-neutral-600 mb-8">
          Drop a pin on the mess, set a reward, and let the community handle the rest.
        </p>

        <div className="grid lg:grid-cols-2 gap-8">
          {/* Map Section */}
          <div>
            <label className="block text-sm font-medium text-neutral-700 mb-2">
              <MapPin className="w-4 h-4 inline-block mr-1" />
              Click the map to drop a bounty pin *
            </label>
            <div className="h-[350px] rounded-xl overflow-hidden border border-neutral-200">
              <Map
                initialViewState={{
                  longitude: CHICAGO_CENTER_LNG_LAT[0],
                  latitude: CHICAGO_CENTER_LNG_LAT[1],
                  zoom: ZOOM.NEIGHBORHOOD,
                }}
                mapStyle={MAP_STYLE_URL}
                cursor="crosshair"
                onClick={handleMapClick}
                style={{ width: '100%', height: '100%' }}
              >
                <NavigationControl position="top-right" />
                {pin && (
                  <MarkerComponent
                    longitude={pin.lng}
                    latitude={pin.lat}
                    anchor="bottom"
                  >
                    <div className="flex flex-col items-center">
                      <div className="w-8 h-8 bg-red-500 rounded-full border-2 border-white shadow-lg flex items-center justify-center">
                        <DollarSign className="w-4 h-4 text-white" />
                      </div>
                      <div className="w-0 h-0 border-l-[6px] border-r-[6px] border-t-[8px] border-l-transparent border-r-transparent border-t-red-500 -mt-0.5" />
                    </div>
                  </MarkerComponent>
                )}
              </Map>
            </div>
            {pin && (
              <p className="text-xs text-neutral-500 mt-2">
                Pin: {pin.lat.toFixed(5)}, {pin.lng.toFixed(5)}
              </p>
            )}
            {!pin && (
              <p className="text-xs text-red-500 mt-2">
                Tap the map to place your bounty pin
              </p>
            )}
          </div>

          {/* Form Section */}
          <div className="space-y-6">
            {/* Title */}
            <div>
              <label className="block text-sm font-medium text-neutral-700 mb-1.5">
                Title *
              </label>
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="e.g., Illegal dumping on Pulaski"
                className="input"
                required
              />
            </div>

            {/* Description */}
            <div>
              <label className="block text-sm font-medium text-neutral-700 mb-1.5">
                Description (optional)
              </label>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Describe what needs to be cleaned up..."
                className="input min-h-[80px]"
                maxLength={500}
              />
            </div>

            {/* Amount */}
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
                  onChange={(e) => handleAmountChange(e.target.value)}
                  onBlur={() => setAmountTouched(true)}
                  onFocus={(e) => (e.target as HTMLInputElement).select()}
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

            {/* Visibility Toggle */}
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

            {/* Tip the Spotter */}
            <div>
              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={tipSpotter}
                  onChange={(e) => {
                    setTipSpotter(e.target.checked)
                    if (!e.target.checked) {
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
                      onChange={(e) => handleTipAmountChange(e.target.value)}
                      placeholder="Custom tip"
                      className="input pl-8 text-sm"
                    />
                  </div>
                </div>
              )}
            </div>

            {/* Total Breakdown */}
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

            {/* Submit */}
            <button
              type="button"
              onClick={handleSubmit}
              disabled={!canSubmit}
              className="w-full py-4 text-lg font-semibold rounded-xl transition-all disabled:opacity-50 disabled:cursor-not-allowed bg-red-500 hover:bg-red-600 text-white"
            >
              Post Bounty{currentAmount > 0 ? ` - $${total}` : ''}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
