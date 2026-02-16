'use client'

import { useState, useEffect } from 'react'
import Link from 'next/link'
import dynamic from 'next/dynamic'
import { Building2, Sparkles, ChevronRight, Calendar, Clock, MapPin, Users, X, Check } from 'lucide-react'

/** Format street name to include 1500 block number for Roosevelt Rd */
function formatStreetName(streetName: string | undefined | null): string {
  if (!streetName) return ''
  if (streetName.includes('1500') || !streetName.toLowerCase().includes('roosevelt')) {
    return streetName
  }
  return streetName.replace(/^W\s+Roosevelt/i, '1500 W Roosevelt')
}

// Dynamically import BlockMap to avoid SSR issues with MapLibre
const BlockMap = dynamic(
  () => import('@/components/map/BlockMap').then(mod => ({ default: mod.BlockMap })),
  {
    ssr: false,
    loading: () => (
      <div className="h-[300px] bg-neutral-100 rounded-xl animate-pulse flex items-center justify-center">
        <span className="text-neutral-400">Loading map...</span>
      </div>
    ),
  }
)

interface Block {
  id: string
  code: string
  streetName: string
  fromCorner: string
  toCorner: string
  status: 'unfunded' | 'available' | 'claimed' | 'in_progress' | 'pending_review' | 'completed'
  // API returns location object
  location?: {
    neighborhood: string | null
    boundary: { type: 'Polygon'; coordinates: [number, number][][] } | null
    center: { type: 'Point'; coordinates: [number, number] } | null
  }
  // For backward compat
  geo?: {
    center: { coordinates: [number, number] }
    polygon?: { coordinates: [number, number][][] }
  }
}

type SponsorshipMode = 'specific' | 'general'
const GENERAL_BLOCK_COUNTS = [1, 3, 5, 10]

export default function SponsorPage() {
  const [blocks, setBlocks] = useState<Block[]>([])
  const [mode, setMode] = useState<SponsorshipMode>('specific')
  const [selectedBlocks, setSelectedBlocks] = useState<Block[]>([])
  const [generalBlockCount, setGeneralBlockCount] = useState(1)
  const [sponsorType, setSponsorType] = useState<'single' | 'monthly'>('single')
  const [isLoading, setIsLoading] = useState(true)
  const [isProcessing, setIsProcessing] = useState(false)
  const [formData, setFormData] = useState({
    name: '',
    email: '',
    company: '',
    isAnonymous: false,
    message: '',
  })

  // Cleaning schedule state
  const [cleaningDate, setCleaningDate] = useState('')
  const [cleaningTimeStart, setCleaningTimeStart] = useState('')
  const [cleaningTimeEnd, setCleaningTimeEnd] = useState('')

  // Calculate minimum date (tomorrow)
  const tomorrow = new Date()
  tomorrow.setDate(tomorrow.getDate() + 1)
  const minDate = tomorrow.toISOString().split('T')[0]

  // Calculate max date (3 months out)
  const maxDateObj = new Date()
  maxDateObj.setMonth(maxDateObj.getMonth() + 3)
  const maxDate = maxDateObj.toISOString().split('T')[0]

  useEffect(() => {
    fetchUnfundedBlocks()
  }, [])

  const fetchUnfundedBlocks = async () => {
    try {
      const response = await fetch('/api/blocks?status=available')
      const data = await response.json()
      setBlocks(data.blocks || [])
    } catch (error) {
      console.error('Failed to fetch blocks:', error)
    } finally {
      setIsLoading(false)
    }
  }

  // Multi-block selection toggle (from donate page pattern)
  const toggleBlockSelection = (block: Block) => {
    setSelectedBlocks(prev => {
      const isSelected = prev.some(b => b.id === block.id)
      if (isSelected) {
        return prev.filter(b => b.id !== block.id)
      } else {
        return [...prev, block]
      }
    })
  }

  // Calculate pricing based on mode
  const blockCount = mode === 'specific' ? selectedBlocks.length : generalBlockCount
  const totalAmount = blockCount * 100
  const priceLabel = `$${totalAmount}${sponsorType === 'monthly' ? '/mo' : ''}`

  // Check if general area mode has enough available blocks
  const availableBlockCount = blocks.length
  const canUseGeneralMode = generalBlockCount <= availableBlockCount

  const handleCheckout = async () => {
    if (mode === 'specific' && selectedBlocks.length === 0) return
    if (mode === 'general' && !canUseGeneralMode) return
    // Cleaning date is now optional
    setIsProcessing(true)

    try {
      const payload = mode === 'specific'
        ? {
            blockIds: selectedBlocks.map(b => Number(b.id)),
            frequency: sponsorType === 'monthly' ? 'monthly' : 'one_time',
            name: formData.name,
            email: formData.email,
            company: formData.company,
            isAnonymous: formData.isAnonymous,
            message: formData.message,
            // Cleaning schedule
            preferredCleaningDate: cleaningDate,
            preferredCleaningTimeStart: cleaningTimeStart || null,
            preferredCleaningTimeEnd: cleaningTimeEnd || null,
          }
        : {
            allocationType: 'area',
            blockCount: generalBlockCount,
            frequency: sponsorType === 'monthly' ? 'monthly' : 'one_time',
            name: formData.name,
            email: formData.email,
            company: formData.company,
            isAnonymous: formData.isAnonymous,
            message: formData.message,
            // Cleaning schedule
            preferredCleaningDate: cleaningDate,
            preferredCleaningTimeStart: cleaningTimeStart || null,
            preferredCleaningTimeEnd: cleaningTimeEnd || null,
          }

      const response = await fetch('/api/contribute/checkout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })

      const data = await response.json()

      if (data.checkoutUrl) {
        window.location.href = data.checkoutUrl
      } else {
        throw new Error(data.error || 'Failed to create checkout session')
      }
    } catch (error) {
      console.error('Checkout error:', error)
      alert('Failed to process. Please try again.')
    } finally {
      setIsProcessing(false)
    }
  }

  // Cleaning date is optional - form is ready without it
  const isFormReady = mode === 'specific'
    ? selectedBlocks.length > 0 && formData.name && formData.email
    : canUseGeneralMode && formData.name && formData.email

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-brown-50 to-white">
      <div className="section-container py-golden-xl">
        <div className="max-w-golden-wide mx-auto">
          {/* General donation link for smaller amounts */}
          <div className="text-center mb-6 animate-fade-in-up">
            <Link
              href="/donate"
              className="inline-flex items-center gap-2 bg-brand-green-100 hover:bg-brand-green-200 text-brand-green-800 px-4 py-2 rounded-full text-sm font-medium transition-colors"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z" />
              </svg>
              Want to donate less than $100? Make a general donation →
            </Link>
          </div>

          {/* Page heading with entrance animation */}
          <div className="text-center mb-golden-xl animate-fade-in-up">
            <h1 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-golden-sm">
              Sponsor Blocks — $100 Each
            </h1>
            <p className="text-lg text-neutral-600 max-w-golden-content mx-auto mb-4">
              Your $100 sponsorship directly pays a North Lawndale resident to clean a block.
              100% goes to the worker. You select the cleaning date.
            </p>
            {/* Blocks needed banner */}
            {!isLoading && blocks.length > 0 && (
              <div className="inline-flex items-center gap-2 bg-amber-100 text-amber-800 px-4 py-2 rounded-full font-medium">
                <span className="flex h-2.5 w-2.5 relative">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-500 opacity-75"></span>
                  <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-amber-500"></span>
                </span>
                {blocks.length} block{blocks.length !== 1 ? 's' : ''} need{blocks.length === 1 ? 's' : ''} sponsors — be a part of the change!
              </div>
            )}
          </div>

          {/* Calculator Upsell Banner */}
          <div className="mb-golden-xl animate-fade-in-up stagger-1">
            <div className="bg-gradient-to-r from-brand-gold-500 to-amber-500 rounded-2xl p-6 text-white">
              <div className="flex flex-col sm:flex-row items-center justify-between gap-4">
                <div className="flex items-center gap-4">
                  <div className="w-12 h-12 rounded-full bg-white/20 flex items-center justify-center">
                    <Sparkles className="w-6 h-6" />
                  </div>
                  <div>
                    <h3 className="font-bold text-lg">Want to do more?</h3>
                    <p className="text-white/90 text-sm">
                      Build a custom sponsorship with branding, services, and enterprise benefits
                    </p>
                  </div>
                </div>
                <Link
                  href="/sponsor/calculator"
                  className="flex-shrink-0 flex items-center gap-2 bg-white text-brand-gold-700 font-semibold px-5 py-2.5 rounded-xl hover:bg-brand-gold-50 transition-colors"
                >
                  Build Custom Package
                  <ChevronRight className="w-4 h-4" />
                </Link>
              </div>
            </div>
          </div>

          {/* Enterprise Partnership Banner */}
          <div className="mb-golden-lg animate-fade-in-up stagger-2">
            <Link
              href="/sponsor/enterprise"
              className="block bg-gradient-to-r from-brand-brown-800 to-brand-brown-900 rounded-xl p-5 hover:from-brand-brown-700 hover:to-brand-brown-800 transition-all group"
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-4">
                  <div className="w-10 h-10 rounded-full bg-brand-gold-500 flex items-center justify-center">
                    <Building2 className="w-5 h-5 text-brand-brown-900" />
                  </div>
                  <div>
                    <p className="font-semibold text-white">Enterprise Partnerships</p>
                    <p className="text-sm text-white/70">
                      $25,000+ campaigns with dedicated support and documentary production
                    </p>
                  </div>
                </div>
                <ChevronRight className="w-5 h-5 text-white/60 group-hover:translate-x-1 transition-transform" />
              </div>
            </Link>
          </div>

          {/* Mode Selection */}
          <div className="mb-golden-lg animate-fade-in-up stagger-1">
            <h2 className="text-xl font-semibold text-neutral-900 mb-golden-md flex items-center gap-2">
              <span className="w-7 h-7 rounded-full bg-brand-gold-100 text-brand-gold-700 flex items-center justify-center text-sm font-bold">1</span>
              How would you like to sponsor?
            </h2>
            <div className="grid sm:grid-cols-2 gap-golden-sm">
              <button
                onClick={() => setMode('specific')}
                className={`
                  p-golden-md rounded-xl border-2 text-left transition-all duration-200 hover-lift
                  ${mode === 'specific'
                    ? 'border-brand-gold-500 bg-brand-gold-50 shadow-md'
                    : 'border-neutral-200 bg-white hover:border-brand-gold-300'
                  }
                `}
              >
                <div className="flex items-start gap-3">
                  <div className={`w-10 h-10 rounded-full flex items-center justify-center ${
                    mode === 'specific' ? 'bg-brand-gold-500 text-white' : 'bg-neutral-100 text-neutral-500'
                  }`}>
                    <MapPin className="w-5 h-5" />
                  </div>
                  <div className="flex-1">
                    <p className="font-semibold text-neutral-900">Choose Blocks</p>
                    <p className="text-sm text-neutral-600 mt-1">
                      Select specific blocks on the map to sponsor
                    </p>
                  </div>
                  {mode === 'specific' && (
                    <div className="w-6 h-6 rounded-full bg-brand-gold-500 flex items-center justify-center">
                      <Check className="w-4 h-4 text-white" />
                    </div>
                  )}
                </div>
              </button>
              <button
                onClick={() => setMode('general')}
                className={`
                  p-golden-md rounded-xl border-2 text-left transition-all duration-200 hover-lift
                  ${mode === 'general'
                    ? 'border-brand-gold-500 bg-brand-gold-50 shadow-md'
                    : 'border-neutral-200 bg-white hover:border-brand-gold-300'
                  }
                `}
              >
                <div className="flex items-start gap-3">
                  <div className={`w-10 h-10 rounded-full flex items-center justify-center ${
                    mode === 'general' ? 'bg-brand-gold-500 text-white' : 'bg-neutral-100 text-neutral-500'
                  }`}>
                    <Users className="w-5 h-5" />
                  </div>
                  <div className="flex-1">
                    <p className="font-semibold text-neutral-900">Fund Any Block</p>
                    <p className="text-sm text-neutral-600 mt-1">
                      Let GCC assign blocks based on community need
                    </p>
                  </div>
                  {mode === 'general' && (
                    <div className="w-6 h-6 rounded-full bg-brand-gold-500 flex items-center justify-center">
                      <Check className="w-4 h-4 text-white" />
                    </div>
                  )}
                </div>
              </button>
            </div>
          </div>

          <div className="grid lg:grid-cols-2 gap-golden-xl">
            {/* Left: Block selection */}
            <div className="animate-fade-in-up stagger-1">
              {mode === 'specific' ? (
                <>
                  <h2 className="text-xl font-semibold text-neutral-900 mb-golden-md flex items-center gap-2">
                    <span className="w-7 h-7 rounded-full bg-brand-gold-100 text-brand-gold-700 flex items-center justify-center text-sm font-bold">2</span>
                    Select Blocks
                  </h2>

                  {isLoading ? (
                    <div className="bg-white rounded-xl border p-golden-lg text-center hover-lift">
                      <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-brand-gold-600 mx-auto" />
                      <p className="text-neutral-500 mt-golden-sm">Loading available blocks...</p>
                    </div>
                  ) : blocks.length === 0 ? (
                    <div className="bg-white rounded-xl border p-golden-lg text-center animate-scale-in">
                      <div className="w-12 h-12 bg-brand-gold-100 rounded-full flex items-center justify-center mx-auto mb-golden-sm">
                        <svg className="w-6 h-6 text-brand-gold-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                        </svg>
                      </div>
                      <h3 className="font-semibold text-neutral-900 mb-2">All Blocks Funded!</h3>
                      <p className="text-neutral-600 text-sm">
                        Amazing! All available blocks have been sponsored. Check back soon for more blocks or support our general fund.
                      </p>
                      <div className="flex flex-col sm:flex-row gap-3 justify-center mt-golden-md">
                        <Link href="/donate" className="btn-primary inline-block press-effect">
                          Donate to General Fund
                        </Link>
                        <Link href="/sponsor/calculator" className="btn-secondary inline-block press-effect">
                          Build Custom Package
                        </Link>
                      </div>
                    </div>
                  ) : (
                    <div className="space-y-golden-md">
                      {/* Interactive Map */}
                      <div className="bg-white rounded-xl border p-4 hover-lift">
                        <p className="text-sm text-neutral-500 mb-3">Click blocks on the map or select from the list below</p>
                        <BlockMap
                          blocks={blocks.map(b => ({
                            ...b,
                            status: 'unfunded' as const,
                            // Transform location (from API) to geo (for BlockMap)
                            geo: {
                              center: b.location?.center || { coordinates: [-87.7158, 41.8666] },
                              polygon: b.location?.boundary || undefined,
                            },
                          }))}
                          onBlockSelect={(block) => {
                            const found = blocks.find(b => b.id === block.id)
                            if (found) toggleBlockSelection(found)
                          }}
                          selectedBlockId={selectedBlocks[0]?.id}
                          height="280px"
                          showLegend={false}
                        />
                      </div>

                      {/* Selected Blocks Badges */}
                      {selectedBlocks.length > 0 && (
                        <div className="space-y-2">
                          <p className="text-sm font-medium text-neutral-700">
                            Selected Blocks ({selectedBlocks.length})
                          </p>
                          <div className="flex flex-wrap gap-2">
                            {selectedBlocks.map((block) => (
                              <div
                                key={block.id}
                                className="inline-flex items-center gap-2 bg-brand-gold-100 text-brand-gold-800 px-3 py-1.5 rounded-full text-sm"
                              >
                                <MapPin className="w-3 h-3" />
                                {block.fromCorner} → {block.toCorner}
                                <button
                                  onClick={() => toggleBlockSelection(block)}
                                  className="hover:text-brand-gold-600"
                                >
                                  <X className="w-3 h-3" />
                                </button>
                              </div>
                            ))}
                          </div>
                        </div>
                      )}

                      {/* Block List */}
                      <div className="space-y-golden-xs max-h-[200px] overflow-y-auto">
                        {blocks.map((block, index) => {
                          const isSelected = selectedBlocks.some(b => b.id === block.id)
                          return (
                            <button
                              key={block.id}
                              onClick={() => toggleBlockSelection(block)}
                              className={`
                                w-full text-left p-golden-sm rounded-xl border-2 transition-all duration-200
                                hover-lift group
                                ${isSelected
                                  ? 'border-brand-gold-500 bg-brand-gold-50 shadow-md'
                                  : 'border-neutral-200 bg-white hover:border-brand-gold-300'
                                }
                              `}
                              style={{ animationDelay: `${index * 0.05}s` }}
                            >
                              <div className="flex items-center justify-between">
                                <div>
                                  <p className="font-semibold text-neutral-900 group-hover:text-brand-gold-700 transition-colors">
                                    {block.fromCorner} to {block.toCorner}
                                  </p>
                                  <p className="text-sm text-neutral-600">{formatStreetName(block.streetName)}</p>
                                </div>
                                <div className={`
                                  w-6 h-6 rounded-full border-2 flex items-center justify-center transition-all duration-200
                                  ${isSelected
                                    ? 'border-brand-gold-500 bg-brand-gold-500 scale-110'
                                    : 'border-neutral-300 group-hover:border-brand-gold-400'
                                  }
                                `}>
                                  {isSelected && (
                                    <svg className="w-4 h-4 text-white animate-scale-in" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                                    </svg>
                                  )}
                                </div>
                              </div>
                            </button>
                          )
                        })}
                      </div>
                    </div>
                  )}
                </>
              ) : (
                <>
                  <h2 className="text-xl font-semibold text-neutral-900 mb-golden-md flex items-center gap-2">
                    <span className="w-7 h-7 rounded-full bg-brand-gold-100 text-brand-gold-700 flex items-center justify-center text-sm font-bold">2</span>
                    How Many Blocks?
                  </h2>

                  <div className="bg-white rounded-xl border p-golden-lg hover-lift">
                    <p className="text-sm text-neutral-600 mb-golden-md">
                      Select how many blocks you&apos;d like to sponsor. GCC will assign available blocks based on community need.
                    </p>

                    <div className="grid grid-cols-4 gap-3 mb-golden-md">
                      {GENERAL_BLOCK_COUNTS.map((count) => {
                        const isDisabled = count > availableBlockCount
                        return (
                          <button
                            key={count}
                            onClick={() => !isDisabled && setGeneralBlockCount(count)}
                            disabled={isDisabled}
                            className={`
                              py-4 rounded-xl font-semibold text-lg transition-all
                              ${generalBlockCount === count && !isDisabled
                                ? 'bg-brand-gold-500 text-white shadow-lg scale-105'
                                : isDisabled
                                  ? 'bg-neutral-100 text-neutral-400 cursor-not-allowed'
                                  : 'bg-neutral-100 text-neutral-700 hover:bg-neutral-200'
                              }
                            `}
                          >
                            {count}
                          </button>
                        )
                      })}
                    </div>

                    {!canUseGeneralMode && (
                      <div className="bg-amber-50 border border-amber-200 rounded-lg p-3">
                        <p className="text-sm text-amber-800">
                          Only {availableBlockCount} block{availableBlockCount !== 1 ? 's' : ''} available. Please select {availableBlockCount} or fewer.
                        </p>
                      </div>
                    )}

                    <div className="bg-brand-gold-50 rounded-xl p-4 mt-golden-md">
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 rounded-full bg-brand-gold-200 flex items-center justify-center">
                          <Users className="w-5 h-5 text-brand-gold-700" />
                        </div>
                        <div>
                          <p className="font-semibold text-brand-gold-800">
                            {generalBlockCount} block{generalBlockCount !== 1 ? 's' : ''} will be assigned
                          </p>
                          <p className="text-sm text-brand-gold-600">
                            You&apos;ll see which blocks at checkout
                          </p>
                        </div>
                      </div>
                    </div>
                  </div>
                </>
              )}

              {/* Pricing options */}
              <div className="mt-golden-lg">
                <h2 className="text-xl font-semibold text-neutral-900 mb-golden-md flex items-center gap-2">
                  <span className="w-7 h-7 rounded-full bg-brand-gold-100 text-brand-gold-700 flex items-center justify-center text-sm font-bold">3</span>
                  Choose Frequency
                </h2>
                <div className="grid grid-cols-2 gap-golden-sm">
                  <button
                    onClick={() => setSponsorType('single')}
                    className={`
                      p-golden-md rounded-xl border-2 text-center transition-all duration-200 hover-lift
                      ${sponsorType === 'single'
                        ? 'border-brand-gold-500 bg-brand-gold-50 shadow-md'
                        : 'border-neutral-200 bg-white hover:border-brand-gold-300'
                      }
                    `}
                  >
                    <p className="text-2xl font-bold text-neutral-900">${blockCount * 100}</p>
                    <p className="text-sm text-neutral-600">One-time</p>
                  </button>
                  <button
                    onClick={() => setSponsorType('monthly')}
                    className={`
                      p-golden-md rounded-xl border-2 text-center transition-all duration-200 relative hover-lift
                      ${sponsorType === 'monthly'
                        ? 'border-brand-gold-500 bg-brand-gold-50 shadow-md'
                        : 'border-neutral-200 bg-white hover:border-brand-gold-300'
                      }
                    `}
                  >
                    <span className="absolute -top-2 -right-2 bg-brand-green-500 text-white text-xs px-2 py-0.5 rounded-full animate-pulse-ring">
                      Best value
                    </span>
                    <p className="text-2xl font-bold text-neutral-900">${blockCount * 100}</p>
                    <p className="text-sm text-neutral-600">Monthly</p>
                  </button>
                </div>
              </div>

              {/* Cleaning Schedule (Optional) */}
              <div className="mt-golden-lg">
                <h2 className="text-xl font-semibold text-neutral-900 mb-golden-md flex items-center gap-2">
                  <span className="w-7 h-7 rounded-full bg-brand-gold-100 text-brand-gold-700 flex items-center justify-center text-sm font-bold">4</span>
                  Schedule Cleaning
                  <span className="text-xs font-normal text-neutral-500 ml-1">(Optional)</span>
                </h2>
                <div className="bg-white rounded-xl border p-golden-md space-y-golden-sm hover-lift">
                  {/* No schedule info */}
                  {!cleaningDate && (
                    <div className="bg-neutral-50 rounded-lg p-3 mb-2">
                      <p className="text-sm text-neutral-600">
                        <strong>Skip scheduling?</strong> Workers will clean your block{blockCount > 1 ? 's' : ''} as soon as available. You can also set a specific date below.
                      </p>
                    </div>
                  )}

                  {/* Date picker - Optional */}
                  <div className="group">
                    <label className="block text-sm font-medium text-neutral-700 mb-1 group-focus-within:text-brand-gold-600 transition-colors">
                      <Calendar className="w-4 h-4 inline mr-1" />
                      Preferred Date
                    </label>
                    <input
                      type="date"
                      value={cleaningDate}
                      onChange={(e) => setCleaningDate(e.target.value)}
                      min={minDate}
                      max={maxDate}
                      className="input-field focus-glow transition-all duration-200 w-full"
                    />
                    <p className="text-xs text-neutral-500 mt-1">
                      {blockCount > 1
                        ? `This date applies to all ${blockCount} blocks`
                        : 'Choose when you\'d like the block cleaned (within 3 months)'
                      }
                    </p>
                  </div>

                  {/* Time range - Optional */}
                  <div>
                    <label className="block text-sm font-medium text-neutral-700 mb-1">
                      <Clock className="w-4 h-4 inline mr-1" />
                      Preferred Time Window (Optional)
                    </label>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-xs text-neutral-500 mb-1">From</label>
                        <select
                          value={cleaningTimeStart}
                          onChange={(e) => setCleaningTimeStart(e.target.value)}
                          className="input-field focus-glow transition-all duration-200 w-full text-sm"
                        >
                          <option value="">Any time</option>
                          <option value="06:00">6:00 AM</option>
                          <option value="07:00">7:00 AM</option>
                          <option value="08:00">8:00 AM</option>
                          <option value="09:00">9:00 AM</option>
                          <option value="10:00">10:00 AM</option>
                          <option value="11:00">11:00 AM</option>
                          <option value="12:00">12:00 PM</option>
                          <option value="13:00">1:00 PM</option>
                          <option value="14:00">2:00 PM</option>
                          <option value="15:00">3:00 PM</option>
                          <option value="16:00">4:00 PM</option>
                        </select>
                      </div>
                      <div>
                        <label className="block text-xs text-neutral-500 mb-1">To</label>
                        <select
                          value={cleaningTimeEnd}
                          onChange={(e) => setCleaningTimeEnd(e.target.value)}
                          className="input-field focus-glow transition-all duration-200 w-full text-sm"
                        >
                          <option value="">Any time</option>
                          <option value="10:00">10:00 AM</option>
                          <option value="11:00">11:00 AM</option>
                          <option value="12:00">12:00 PM</option>
                          <option value="13:00">1:00 PM</option>
                          <option value="14:00">2:00 PM</option>
                          <option value="15:00">3:00 PM</option>
                          <option value="16:00">4:00 PM</option>
                          <option value="17:00">5:00 PM</option>
                          <option value="18:00">6:00 PM</option>
                          <option value="19:00">7:00 PM</option>
                          <option value="20:00">8:00 PM</option>
                        </select>
                      </div>
                    </div>
                    <p className="text-xs text-neutral-500 mt-1">
                      Leave blank for flexible timing. Workers will aim to complete during this window.
                    </p>
                  </div>

                  {/* Schedule preview */}
                  {cleaningDate && (
                    <div className="bg-brand-gold-50 rounded-lg p-3 mt-2">
                      <p className="text-sm text-brand-gold-800">
                        <strong>Scheduled for:</strong>{' '}
                        {new Date(cleaningDate + 'T12:00:00').toLocaleDateString('en-US', {
                          weekday: 'long',
                          month: 'long',
                          day: 'numeric',
                          year: 'numeric',
                        })}
                        {cleaningTimeStart && cleaningTimeEnd && (
                          <span className="block text-xs mt-1">
                            Between {cleaningTimeStart.replace(/^(\d{2}):(\d{2})$/, (_, h, m) => {
                              const hour = parseInt(h)
                              const ampm = hour >= 12 ? 'PM' : 'AM'
                              const hour12 = hour % 12 || 12
                              return `${hour12}:${m} ${ampm}`
                            })} and {cleaningTimeEnd.replace(/^(\d{2}):(\d{2})$/, (_, h, m) => {
                              const hour = parseInt(h)
                              const ampm = hour >= 12 ? 'PM' : 'AM'
                              const hour12 = hour % 12 || 12
                              return `${hour12}:${m} ${ampm}`
                            })}
                          </span>
                        )}
                        {blockCount > 1 && (
                          <span className="block text-xs mt-1 text-brand-gold-600">
                            All {blockCount} blocks will be cleaned on this date
                          </span>
                        )}
                      </p>
                    </div>
                  )}
                </div>
              </div>
            </div>

            {/* Right: Sponsor info */}
            <div className="animate-fade-in-up stagger-2">
              <h2 className="text-xl font-semibold text-neutral-900 mb-golden-md flex items-center gap-2">
                <span className="w-7 h-7 rounded-full bg-brand-gold-100 text-brand-gold-700 flex items-center justify-center text-sm font-bold">5</span>
                Your Information
              </h2>

              <div className="bg-white rounded-xl border p-golden-lg space-y-golden-md hover-lift">
                <div className="group">
                  <label className="block text-sm font-medium text-neutral-700 mb-1 group-focus-within:text-brand-gold-600 transition-colors">
                    Your Name *
                  </label>
                  <input
                    type="text"
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                    className="input-field focus-glow transition-all duration-200"
                    placeholder="John Smith"
                    required
                  />
                </div>

                <div className="group">
                  <label className="block text-sm font-medium text-neutral-700 mb-1 group-focus-within:text-brand-gold-600 transition-colors">
                    Email *
                  </label>
                  <input
                    type="email"
                    value={formData.email}
                    onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                    className="input-field focus-glow transition-all duration-200"
                    placeholder="you@example.com"
                    required
                  />
                </div>

                <div className="group">
                  <label className="block text-sm font-medium text-neutral-700 mb-1 group-focus-within:text-brand-gold-600 transition-colors">
                    Company/Organization (optional)
                  </label>
                  <input
                    type="text"
                    value={formData.company}
                    onChange={(e) => setFormData({ ...formData, company: e.target.value })}
                    className="input-field focus-glow transition-all duration-200"
                    placeholder="Your business name"
                  />
                </div>

                <div className="group">
                  <label className="block text-sm font-medium text-neutral-700 mb-1 group-focus-within:text-brand-gold-600 transition-colors">
                    Message for the Worker (optional)
                  </label>
                  <textarea
                    value={formData.message}
                    onChange={(e) => setFormData({ ...formData, message: e.target.value })}
                    className="input-field min-h-[80px] focus-glow transition-all duration-200 resize-none"
                    placeholder="Thank you for keeping our community clean!"
                  />
                </div>

                <label className="flex items-center gap-golden-xs cursor-pointer group">
                  <input
                    type="checkbox"
                    checked={formData.isAnonymous}
                    onChange={(e) => setFormData({ ...formData, isAnonymous: e.target.checked })}
                    className="rounded border-neutral-300 text-brand-gold-600 focus:ring-brand-gold-500 focus:ring-offset-0 transition-all duration-200"
                  />
                  <span className="text-sm text-neutral-600 group-hover:text-neutral-900 transition-colors">
                    Sponsor anonymously (your name won&apos;t be shown publicly)
                  </span>
                </label>

                {/* Order Summary */}
                <div className="border-t pt-golden-md mt-golden-md">
                  <div className="bg-brand-gold-50 rounded-xl p-4 mb-golden-md">
                    <div className="flex justify-between items-center mb-2">
                      <span className="text-neutral-700">
                        {blockCount} block{blockCount !== 1 ? 's' : ''} × $100
                      </span>
                      <span className="font-semibold text-neutral-900">${totalAmount}</span>
                    </div>
                    {sponsorType === 'monthly' && (
                      <p className="text-xs text-brand-gold-600">Billed monthly</p>
                    )}
                    <div className="border-t border-brand-gold-200 mt-2 pt-2 flex justify-between items-center">
                      <span className="font-semibold text-brand-gold-800">Total</span>
                      <span className="text-2xl font-bold text-brand-gold-700">{priceLabel}</span>
                    </div>
                  </div>
                </div>

                {/* Checkout button with breathing animation when ready */}
                <button
                  onClick={handleCheckout}
                  disabled={!isFormReady || isProcessing}
                  className={`
                    btn-primary w-full py-4 text-lg mt-golden-md
                    transition-all duration-300
                    ${isFormReady && !isProcessing ? 'animate-breathe' : ''}
                    ${!isFormReady ? 'opacity-50 cursor-not-allowed' : 'press-effect'}
                  `}
                >
                  {isProcessing ? (
                    <span className="flex items-center justify-center gap-2">
                      <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                      </svg>
                      Processing...
                    </span>
                  ) : (
                    <>
                      <span>Sponsor {blockCount} Block{blockCount !== 1 ? 's' : ''} for {priceLabel}</span>
                      {isFormReady && (
                        <svg className="inline-block ml-2 w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
                        </svg>
                      )}
                    </>
                  )}
                </button>

                {/* Progress indicator */}
                {!isFormReady && (
                  <p className="text-xs text-neutral-500 text-center animate-fade-in-up">
                    {mode === 'specific' && selectedBlocks.length === 0 ? 'Select blocks to sponsor' :
                     mode === 'general' && !canUseGeneralMode ? 'Reduce block count' :
                     !formData.name ? 'Enter your name' :
                     'Enter your email to continue'}
                  </p>
                )}

                {/* What you receive */}
                <div className="border-t pt-golden-sm mt-golden-sm">
                  <p className="text-xs font-medium text-neutral-700 mb-2">What you&apos;ll receive:</p>
                  <ul className="text-xs text-neutral-500 space-y-1">
                    <li>✓ Before & after photos from the worker</li>
                    <li>✓ PDF donation receipt for tax purposes</li>
                    <li>✓ Impact summary with block details</li>
                    {mode === 'general' && (
                      <li>✓ Email with your assigned blocks at checkout</li>
                    )}
                  </ul>
                </div>

                {/* Tax deductible notice */}
                <p className="text-xs text-neutral-500 text-center mt-golden-sm">
                  GCC is a registered 501(c)(3). Your donation is 100% tax-deductible.
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
