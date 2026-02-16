'use client'

import { useState, useEffect, Suspense } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import dynamic from 'next/dynamic'
import { Heart, MapPin, Sparkles, Users, ChevronRight, X, Check, Package } from 'lucide-react'

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

/** Format street name to include 1500 block number for Roosevelt Rd */
function formatStreetName(streetName: string | undefined | null): string {
  if (!streetName) return ''
  if (streetName.includes('1500') || !streetName.toLowerCase().includes('roosevelt')) {
    return streetName
  }
  return streetName.replace(/^W\s+Roosevelt/i, '1500 W Roosevelt')
}

// Block type matches BlockMap component's Block interface
interface Block {
  id: string
  code: string
  streetName: string
  fromCorner: string
  toCorner: string
  status: 'unfunded' | 'available' | 'claimed' | 'in_progress' | 'pending_review' | 'completed'
  geo: {
    center: { coordinates: [number, number] }
    polygon?: { coordinates: [number, number][][] }
  }
}

// Transform API response to BlockMap format
interface ApiBlock {
  id: string
  code: string
  streetName: string
  fromCorner: string
  toCorner: string
  status: string
  location: {
    center: { type: string; coordinates: [number, number] } | null
    boundary: { type: string; coordinates: [number, number][][] } | null
  }
}

function transformApiBlock(apiBlock: ApiBlock): Block {
  return {
    id: apiBlock.id,
    code: apiBlock.code || '',
    streetName: apiBlock.streetName || '',
    fromCorner: apiBlock.fromCorner || '',
    toCorner: apiBlock.toCorner || '',
    status: apiBlock.status as Block['status'],
    geo: {
      center: {
        coordinates: apiBlock.location?.center?.coordinates || [-87.7158, 41.8666],
      },
      polygon: apiBlock.location?.boundary
        ? { coordinates: apiBlock.location.boundary.coordinates }
        : undefined,
    },
  }
}

const PRESET_AMOUNTS = [5, 25, 50, 100]

type TabType = 'donate' | 'sponsor'

function DonateContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const initialTab = searchParams.get('tab') === 'sponsor' ? 'sponsor' : 'donate'

  const [activeTab, setActiveTab] = useState<TabType>(initialTab)

  // Donation state - start with $1 in custom field, no preset selected
  const [selectedPreset, setSelectedPreset] = useState<number | null>(null)
  const [customAmount, setCustomAmount] = useState('1')
  const [frequency, setFrequency] = useState<'one_time' | 'monthly'>('one_time')

  // Sponsor state
  const [blocks, setBlocks] = useState<Block[]>([])
  const [selectedBlocks, setSelectedBlocks] = useState<Block[]>([])
  const [isLoadingBlocks, setIsLoadingBlocks] = useState(false)

  // Shared form state
  const [formData, setFormData] = useState({
    name: '',
    email: '',
    company: '',
    displayName: '',
    isAnonymous: false,
    message: '',
  })
  const [isProcessing, setIsProcessing] = useState(false)
  const [showForm, setShowForm] = useState(false)
  const [recentDonorCount, setRecentDonorCount] = useState<number | null>(null)

  // Material donation form state
  const [showMaterialForm, setShowMaterialForm] = useState(false)
  const [materialFormData, setMaterialFormData] = useState({
    name: '',
    email: '',
    phone: '',
    company: '',
    items: '',
    quantity: '',
    condition: 'new',
    preferredMethod: 'pickup',
    address: '',
    availableDates: '',
    notes: '',
  })
  const [isMaterialSubmitting, setIsMaterialSubmitting] = useState(false)
  const [materialSuccess, setMaterialSuccess] = useState(false)

  // Fetch real donor count
  useEffect(() => {
    async function fetchDonorCount() {
      try {
        const res = await fetch('/api/public/support-wall?view=recent&limit=1')
        if (res.ok) {
          const data = await res.json()
          setRecentDonorCount(data.stats?.recentContributors || 0)
        }
      } catch {
        // Silently fail
      }
    }
    fetchDonorCount()
  }, [])

  // Amount comes from preset if selected, otherwise from custom input
  const donationAmount = selectedPreset !== null ? selectedPreset : (Number(customAmount) || 0)
  const blocksEquivalent = Math.floor(donationAmount / 100)

  const fetchBlocks = async () => {
    if (blocks.length > 0) return
    setIsLoadingBlocks(true)
    try {
      const response = await fetch('/api/blocks?status=available')
      const data = await response.json()
      // Transform API response to BlockMap format
      const transformedBlocks = (data.blocks || []).map(transformApiBlock)
      setBlocks(transformedBlocks)
    } catch (error) {
      console.error('Failed to fetch blocks:', error)
    } finally {
      setIsLoadingBlocks(false)
    }
  }

  const handleTabChange = (tab: TabType) => {
    setActiveTab(tab)
    if (tab === 'sponsor') {
      fetchBlocks()
    }
    setShowForm(false)
  }

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

  const handleContinue = () => {
    if (activeTab === 'donate' && donationAmount < 1) return
    if (activeTab === 'sponsor' && selectedBlocks.length === 0) return
    setShowForm(true)
  }

  const handleCheckout = async () => {
    if (!formData.name || !formData.email) return
    setIsProcessing(true)

    try {
      const payload =
        activeTab === 'donate'
          ? {
              amount: donationAmount,
              frequency,
              name: formData.name,
              email: formData.email,
              company: formData.company,
              displayName: formData.displayName || formData.name,
              isAnonymous: formData.isAnonymous,
              message: formData.message,
            }
          : {
              blockIds: selectedBlocks.map(b => Number(b.id)),
              frequency,
              name: formData.name,
              email: formData.email,
              company: formData.company,
              displayName: formData.displayName || formData.name,
              isAnonymous: formData.isAnonymous,
              message: formData.message,
            }

      const response = await fetch('/api/contribute/checkout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })

      const data = await response.json()

      if (data.checkoutUrl) {
        window.location.href = data.checkoutUrl
      } else if (data.demo) {
        router.push('/donate/success?demo=true')
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

  const totalAmount =
    activeTab === 'donate' ? donationAmount : selectedBlocks.length * 100

  const handleMaterialSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (isMaterialSubmitting) return

    setIsMaterialSubmitting(true)
    try {
      const response = await fetch('/api/material-donations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(materialFormData),
      })

      if (response.ok) {
        setMaterialSuccess(true)
        setTimeout(() => {
          setShowMaterialForm(false)
          setMaterialSuccess(false)
          setMaterialFormData({
            name: '',
            email: '',
            phone: '',
            company: '',
            items: '',
            quantity: '',
            condition: 'new',
            preferredMethod: 'pickup',
            address: '',
            availableDates: '',
            notes: '',
          })
        }, 3000)
      } else {
        const data = await response.json()
        throw new Error(data.error || 'Failed to submit')
      }
    } catch (error) {
      console.error('Material donation error:', error)
      alert('Failed to submit. Please try again or email us directly.')
    } finally {
      setIsMaterialSubmitting(false)
    }
  }

  return (
    <div className="section-container py-12 lg:py-16">
      <div className="max-w-4xl mx-auto">
        {/* Header */}
        <div className="text-center mb-10">
          <h1 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-4">
            Every Dollar Builds Community
          </h1>
          <p className="text-lg text-neutral-600 max-w-2xl mx-auto mb-4">
            $1 from 100 people cleans a block. That&apos;s the power of us.
            Every single dollar goes directly to paying workers.
          </p>
          <div className="bg-brand-green-50 border border-brand-green-200 rounded-xl p-4 max-w-xl mx-auto mb-4">
            <p className="text-brand-green-800 font-medium">
              No donation is too small—$1, $5, whatever you can give matters. When hundreds of us each give a little, we move mountains together. Our collective power is our superpower.
            </p>
          </div>
          {recentDonorCount !== null && recentDonorCount > 0 && (
            <div className="inline-flex items-center gap-2 bg-neutral-100 rounded-full px-4 py-2">
              <span className="text-xl">🤝</span>
              <span className="text-sm text-neutral-700">
                <strong>{recentDonorCount} {recentDonorCount === 1 ? 'person' : 'people'}</strong> donated this month — join them
              </span>
            </div>
          )}
        </div>

        {/* Tab Navigation */}
        <div className="flex bg-neutral-100 p-1 rounded-xl mb-8 max-w-md mx-auto">
          <button
            onClick={() => handleTabChange('donate')}
            className={`flex-1 flex items-center justify-center gap-2 px-6 py-3 rounded-lg font-medium transition-all ${
              activeTab === 'donate'
                ? 'bg-white text-brand-green-700 shadow-sm'
                : 'text-neutral-600 hover:text-neutral-900'
            }`}
          >
            <Heart className="w-4 h-4" />
            Donate
          </button>
          <button
            onClick={() => handleTabChange('sponsor')}
            className={`flex-1 flex items-center justify-center gap-2 px-6 py-3 rounded-lg font-medium transition-all ${
              activeTab === 'sponsor'
                ? 'bg-white text-brand-gold-700 shadow-sm'
                : 'text-neutral-600 hover:text-neutral-900'
            }`}
          >
            <MapPin className="w-4 h-4" />
            Sponsor Block
          </button>
        </div>

        {/* Comparison Cards */}
        <div className="grid md:grid-cols-2 gap-4 mb-8">
          <div
            className={`p-5 rounded-xl border-2 transition-all cursor-pointer ${
              activeTab === 'donate'
                ? 'border-brand-green-500 bg-brand-green-50'
                : 'border-neutral-200 bg-white hover:border-neutral-300'
            }`}
            onClick={() => handleTabChange('donate')}
          >
            <div className="flex items-start gap-3">
              <div className={`w-10 h-10 rounded-full flex items-center justify-center ${
                activeTab === 'donate' ? 'bg-brand-green-500 text-white' : 'bg-neutral-100 text-neutral-500'
              }`}>
                <Heart className="w-5 h-5" />
              </div>
              <div className="flex-1">
                <h3 className="font-semibold text-neutral-900 mb-1">General Donation</h3>
                <p className="text-sm text-neutral-600 mb-3">
                  Any amount. GCC allocates to highest-need blocks.
                </p>
                <ul className="space-y-1 text-sm text-neutral-600">
                  <li className="flex items-center gap-2">
                    <Check className="w-4 h-4 text-brand-green-500" />
                    Choose your amount
                  </li>
                  <li className="flex items-center gap-2">
                    <Check className="w-4 h-4 text-brand-green-500" />
                    One-time or monthly
                  </li>
                  <li className="flex items-center gap-2">
                    <Check className="w-4 h-4 text-brand-green-500" />
                    Recognition on support wall
                  </li>
                </ul>
              </div>
            </div>
          </div>

          <div
            className={`p-5 rounded-xl border-2 transition-all cursor-pointer ${
              activeTab === 'sponsor'
                ? 'border-brand-gold-500 bg-brand-gold-50'
                : 'border-neutral-200 bg-white hover:border-neutral-300'
            }`}
            onClick={() => handleTabChange('sponsor')}
          >
            <div className="flex items-start gap-3">
              <div className={`w-10 h-10 rounded-full flex items-center justify-center ${
                activeTab === 'sponsor' ? 'bg-brand-gold-500 text-white' : 'bg-neutral-100 text-neutral-500'
              }`}>
                <MapPin className="w-5 h-5" />
              </div>
              <div className="flex-1">
                <h3 className="font-semibold text-neutral-900 mb-1">Block Sponsorship</h3>
                <p className="text-sm text-neutral-600 mb-3">
                  $100/block. Choose specific blocks to sponsor.
                </p>
                <ul className="space-y-1 text-sm text-neutral-600">
                  <li className="flex items-center gap-2">
                    <Check className="w-4 h-4 text-brand-gold-500" />
                    Select blocks on map
                  </li>
                  <li className="flex items-center gap-2">
                    <Check className="w-4 h-4 text-brand-gold-500" />
                    Block credited to you
                  </li>
                  <li className="flex items-center gap-2">
                    <Check className="w-4 h-4 text-brand-gold-500" />
                    Before/after photos
                  </li>
                </ul>
              </div>
            </div>
          </div>
        </div>

        {/* Content Area */}
        <div className="bg-white rounded-2xl shadow-lg p-6 lg:p-8">
          {activeTab === 'donate' && !showForm && (
            <div className="space-y-6">
              <h2 className="text-xl font-semibold text-neutral-900">Choose Amount</h2>

              {/* Preset Amounts */}
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                {PRESET_AMOUNTS.map((preset) => (
                  <button
                    key={preset}
                    onClick={() => {
                      setSelectedPreset(preset)
                      setCustomAmount(String(preset))
                    }}
                    className={`py-4 rounded-xl font-semibold text-lg transition-all ${
                      selectedPreset === preset
                        ? 'bg-brand-green-600 text-white shadow-lg scale-105'
                        : 'bg-neutral-100 text-neutral-700 hover:bg-neutral-200'
                    }`}
                  >
                    ${preset}
                  </button>
                ))}
              </div>

              {/* Custom Amount - always shows the current amount */}
              <div className="relative">
                <span className="absolute left-4 top-1/2 -translate-y-1/2 text-neutral-400 font-medium text-lg">
                  $
                </span>
                <input
                  type="number"
                  min="1"
                  step="1"
                  placeholder="Enter amount"
                  value={customAmount}
                  onChange={(e) => {
                    const value = e.target.value
                    setCustomAmount(value)
                    // Sync preset selection with typed value
                    const numValue = Number(value)
                    if (PRESET_AMOUNTS.includes(numValue)) {
                      setSelectedPreset(numValue)
                    } else {
                      setSelectedPreset(null)
                    }
                  }}
                  onFocus={() => {
                    // Select all text on focus for easy replacement
                    const input = document.activeElement as HTMLInputElement
                    input?.select()
                  }}
                  className={`input pl-10 text-center text-lg py-4 ${selectedPreset === null && customAmount ? 'ring-2 ring-brand-green-500' : ''}`}
                />
              </div>

              {/* Frequency Toggle */}
              <div>
                <p className="text-sm font-medium text-neutral-700 mb-2">Frequency</p>
                <div className="flex gap-2">
                  <button
                    onClick={() => setFrequency('one_time')}
                    className={`flex-1 py-3 rounded-lg font-medium transition-all ${
                      frequency === 'one_time'
                        ? 'bg-brand-green-100 text-brand-green-700 border-2 border-brand-green-500'
                        : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200'
                    }`}
                  >
                    One-time
                  </button>
                  <button
                    onClick={() => setFrequency('monthly')}
                    className={`flex-1 py-3 rounded-lg font-medium transition-all ${
                      frequency === 'monthly'
                        ? 'bg-brand-green-100 text-brand-green-700 border-2 border-brand-green-500'
                        : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200'
                    }`}
                  >
                    Monthly
                  </button>
                </div>
              </div>

              {/* Impact Preview */}
              {donationAmount > 0 && (
                <div className="bg-brand-green-50 rounded-xl p-5">
                  <div className="flex items-center gap-3 mb-2">
                    <Sparkles className="w-5 h-5 text-brand-green-600" />
                    <span className="font-semibold text-brand-green-800">Your Impact</span>
                  </div>
                  <p className="text-brand-green-700">
                    <span className="font-bold text-lg">${donationAmount}</span>
                    {frequency === 'monthly' && <span>/month</span>} can fund{' '}
                    <span className="font-bold">
                      {blocksEquivalent > 0
                        ? `${blocksEquivalent} block${blocksEquivalent > 1 ? 's' : ''}`
                        : 'a portion of a block'}
                    </span>{' '}
                    cleaned
                    {blocksEquivalent > 0 && (
                      <span> = ${blocksEquivalent * 100} in worker wages</span>
                    )}
                  </p>
                </div>
              )}

              <button
                onClick={handleContinue}
                disabled={donationAmount < 1}
                className="btn-primary w-full py-4 text-lg disabled:opacity-50 disabled:cursor-not-allowed"
              >
                Continue with ${donationAmount}
                {frequency === 'monthly' ? '/month' : ''}
              </button>
            </div>
          )}

          {activeTab === 'sponsor' && !showForm && (
            <div className="space-y-6">
              <div className="flex items-center justify-between">
                <h2 className="text-xl font-semibold text-neutral-900">Select Blocks to Sponsor</h2>
                <span className="text-sm text-neutral-500">$100 per block</span>
              </div>

              {/* Map */}
              <div className="h-[350px] rounded-xl overflow-hidden border border-neutral-200">
                {isLoadingBlocks ? (
                  <div className="h-full bg-neutral-100 animate-pulse flex items-center justify-center">
                    <span className="text-neutral-400">Loading blocks...</span>
                  </div>
                ) : (
                  <BlockMap
                    blocks={blocks}
                    selectedBlockId={selectedBlocks[0]?.id}
                    onBlockSelect={(block: Block) => toggleBlockSelection(block)}
                  />
                )}
              </div>

              {/* Selected Blocks */}
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
                        {formatStreetName(block.streetName) || block.code}
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
              <div className="max-h-[200px] overflow-y-auto space-y-2">
                {blocks.slice(0, 10).map((block) => {
                  const isSelected = selectedBlocks.some(b => b.id === block.id)
                  return (
                    <button
                      key={block.id}
                      onClick={() => toggleBlockSelection(block)}
                      className={`w-full flex items-center justify-between p-3 rounded-lg border-2 transition-all ${
                        isSelected
                          ? 'border-brand-gold-500 bg-brand-gold-50'
                          : 'border-neutral-200 hover:border-neutral-300 bg-white'
                      }`}
                    >
                      <div className="flex items-center gap-3">
                        <MapPin className={`w-4 h-4 ${isSelected ? 'text-brand-gold-600' : 'text-neutral-400'}`} />
                        <div className="text-left">
                          <p className="font-medium text-neutral-900">{formatStreetName(block.streetName) || block.code}</p>
                          {block.fromCorner && block.toCorner && (
                            <p className="text-xs text-neutral-500">
                              {block.fromCorner} to {block.toCorner}
                            </p>
                          )}
                        </div>
                      </div>
                      <span className="font-semibold text-neutral-700">$100</span>
                    </button>
                  )
                })}
              </div>

              {/* Frequency Toggle */}
              <div>
                <p className="text-sm font-medium text-neutral-700 mb-2">Frequency</p>
                <div className="flex gap-2">
                  <button
                    onClick={() => setFrequency('one_time')}
                    className={`flex-1 py-3 rounded-lg font-medium transition-all ${
                      frequency === 'one_time'
                        ? 'bg-brand-gold-100 text-brand-gold-700 border-2 border-brand-gold-500'
                        : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200'
                    }`}
                  >
                    One-time
                  </button>
                  <button
                    onClick={() => setFrequency('monthly')}
                    className={`flex-1 py-3 rounded-lg font-medium transition-all ${
                      frequency === 'monthly'
                        ? 'bg-brand-gold-100 text-brand-gold-700 border-2 border-brand-gold-500'
                        : 'bg-neutral-100 text-neutral-600 border-2 border-transparent hover:bg-neutral-200'
                    }`}
                  >
                    Monthly
                  </button>
                </div>
              </div>

              {/* Total */}
              {selectedBlocks.length > 0 && (
                <div className="bg-brand-gold-50 rounded-xl p-5">
                  <div className="flex justify-between items-center">
                    <span className="font-semibold text-brand-gold-800">Total</span>
                    <span className="text-2xl font-bold text-brand-gold-700">
                      ${selectedBlocks.length * 100}
                      {frequency === 'monthly' && <span className="text-lg">/month</span>}
                    </span>
                  </div>
                  <p className="text-sm text-brand-gold-600 mt-1">
                    = {selectedBlocks.length} block{selectedBlocks.length > 1 ? 's' : ''} sponsored
                  </p>
                </div>
              )}

              <button
                onClick={handleContinue}
                disabled={selectedBlocks.length === 0}
                className="btn-primary w-full py-4 text-lg disabled:opacity-50 disabled:cursor-not-allowed bg-brand-gold-500 hover:bg-brand-gold-600"
              >
                Continue - ${selectedBlocks.length * 100}
                {frequency === 'monthly' ? '/month' : ''}
              </button>
            </div>
          )}

          {/* Form */}
          {showForm && (
            <div className="space-y-6">
              <div className="flex items-center justify-between">
                <h2 className="text-xl font-semibold text-neutral-900">Your Information</h2>
                <button
                  onClick={() => setShowForm(false)}
                  className="text-sm text-neutral-500 hover:text-neutral-700"
                >
                  ← Back
                </button>
              </div>

              {/* Summary */}
              <div className={`rounded-xl p-4 ${activeTab === 'donate' ? 'bg-brand-green-50' : 'bg-brand-gold-50'}`}>
                <div className="flex justify-between items-center">
                  <span className={activeTab === 'donate' ? 'text-brand-green-700' : 'text-brand-gold-700'}>
                    {activeTab === 'donate' ? 'General Donation' : `${selectedBlocks.length} Block${selectedBlocks.length > 1 ? 's' : ''}`}
                  </span>
                  <span className={`text-xl font-bold ${activeTab === 'donate' ? 'text-brand-green-800' : 'text-brand-gold-800'}`}>
                    ${totalAmount}
                    {frequency === 'monthly' && '/mo'}
                  </span>
                </div>
              </div>

              <form onSubmit={(e) => { e.preventDefault(); handleCheckout(); }} className="space-y-4">
                <div className="grid sm:grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-neutral-700 mb-1">
                      Your Name *
                    </label>
                    <input
                      type="text"
                      required
                      value={formData.name}
                      onChange={(e) => setFormData(prev => ({ ...prev, name: e.target.value }))}
                      className="input"
                      placeholder="John Smith"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-neutral-700 mb-1">
                      Email Address *
                    </label>
                    <input
                      type="email"
                      required
                      value={formData.email}
                      onChange={(e) => setFormData(prev => ({ ...prev, email: e.target.value }))}
                      className="input"
                      placeholder="john@example.com"
                    />
                  </div>
                </div>

                <div>
                  <label className="block text-sm font-medium text-neutral-700 mb-1">
                    Company/Organization (optional)
                  </label>
                  <input
                    type="text"
                    value={formData.company}
                    onChange={(e) => setFormData(prev => ({ ...prev, company: e.target.value }))}
                    className="input"
                    placeholder="ACME Inc."
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-neutral-700 mb-1">
                    Display Name (optional)
                  </label>
                  <input
                    type="text"
                    value={formData.displayName}
                    onChange={(e) => setFormData(prev => ({ ...prev, displayName: e.target.value }))}
                    className="input"
                    placeholder="Name shown on support wall"
                  />
                  <p className="text-xs text-neutral-500 mt-1">
                    Leave blank to use your real name
                  </p>
                </div>

                <div className="flex items-center gap-2">
                  <input
                    type="checkbox"
                    id="anonymous"
                    checked={formData.isAnonymous}
                    onChange={(e) => setFormData(prev => ({ ...prev, isAnonymous: e.target.checked }))}
                    className="rounded border-neutral-300"
                  />
                  <label htmlFor="anonymous" className="text-sm text-neutral-700">
                    Keep my contribution anonymous on the support wall
                  </label>
                </div>

                <div>
                  <label className="block text-sm font-medium text-neutral-700 mb-1">
                    Message (optional)
                  </label>
                  <textarea
                    value={formData.message}
                    onChange={(e) => setFormData(prev => ({ ...prev, message: e.target.value }))}
                    className="input min-h-[80px]"
                    placeholder="Share why you're supporting GCC..."
                    maxLength={280}
                  />
                </div>

                <button
                  type="submit"
                  disabled={isProcessing || !formData.name || !formData.email}
                  className={`w-full py-4 text-lg font-semibold rounded-xl transition-all disabled:opacity-50 disabled:cursor-not-allowed ${
                    activeTab === 'donate'
                      ? 'bg-brand-green-600 hover:bg-brand-green-700 text-white'
                      : 'bg-brand-gold-500 hover:bg-brand-gold-600 text-white'
                  }`}
                >
                  {isProcessing ? (
                    <span className="flex items-center justify-center gap-2">
                      <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                      </svg>
                      Processing...
                    </span>
                  ) : (
                    `Complete ${activeTab === 'donate' ? 'Donation' : 'Sponsorship'} - $${totalAmount}`
                  )}
                </button>

                <p className="text-xs text-neutral-500 text-center">
                  You&apos;ll be redirected to Stripe for secure payment. GCC is a 501(c)(3) nonprofit.
                  Your contribution is tax-deductible.
                </p>
              </form>
            </div>
          )}
        </div>

        {/* Material Donations Section */}
        <div className="mt-10 bg-white rounded-2xl shadow-lg p-6 lg:p-8">
          <div className="flex items-start gap-4">
            <div className="w-14 h-14 rounded-full bg-amber-100 flex items-center justify-center flex-shrink-0">
              <svg className="w-7 h-7 text-amber-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4" />
              </svg>
            </div>
            <div className="flex-1">
              <h2 className="text-xl font-semibold text-neutral-900 mb-2">
                Donate Supplies & Equipment
              </h2>
              <p className="text-neutral-600 mb-4">
                Can&apos;t donate cash? We gratefully accept donations of cleaning supplies and equipment!
                Your material contributions directly support our workers and keep our operations running.
              </p>

              <div className="bg-amber-50 rounded-xl p-5 mb-5">
                <h3 className="font-medium text-amber-800 mb-3">Items We Need:</h3>
                <div className="grid sm:grid-cols-2 gap-3">
                  <div className="space-y-2">
                    <div className="flex items-center gap-2 text-sm text-amber-900">
                      <Check className="w-4 h-4 text-amber-600" />
                      <span>Work Gloves (all sizes)</span>
                    </div>
                    <div className="flex items-center gap-2 text-sm text-amber-900">
                      <Check className="w-4 h-4 text-amber-600" />
                      <span>Brooms & Push Brooms</span>
                    </div>
                    <div className="flex items-center gap-2 text-sm text-amber-900">
                      <Check className="w-4 h-4 text-amber-600" />
                      <span>Rakes (leaf & garden)</span>
                    </div>
                    <div className="flex items-center gap-2 text-sm text-amber-900">
                      <Check className="w-4 h-4 text-amber-600" />
                      <span>Trash Bags (heavy duty)</span>
                    </div>
                  </div>
                  <div className="space-y-2">
                    <div className="flex items-center gap-2 text-sm text-amber-900">
                      <Check className="w-4 h-4 text-amber-600" />
                      <span>Pressure Washers</span>
                    </div>
                    <div className="flex items-center gap-2 text-sm text-amber-900">
                      <Check className="w-4 h-4 text-amber-600" />
                      <span>Wheelbarrows</span>
                    </div>
                    <div className="flex items-center gap-2 text-sm text-amber-900">
                      <Check className="w-4 h-4 text-amber-600" />
                      <span>Safety Vests</span>
                    </div>
                    <div className="flex items-center gap-2 text-sm text-amber-900">
                      <Check className="w-4 h-4 text-amber-600" />
                      <span>Shovels & Hand Tools</span>
                    </div>
                  </div>
                </div>
              </div>

              <div className="flex flex-col sm:flex-row gap-3">
                <button
                  onClick={() => setShowMaterialForm(true)}
                  className="inline-flex items-center justify-center gap-2 bg-amber-500 hover:bg-amber-600 text-white font-medium px-6 py-3 rounded-xl transition-colors"
                >
                  <Package className="w-5 h-5" />
                  Submit Donation Inquiry
                </button>
                <a
                  href="mailto:donate@globalcommunitycleaners.org?subject=Material%20Donation%20Inquiry"
                  className="inline-flex items-center justify-center gap-2 bg-neutral-100 hover:bg-neutral-200 text-neutral-700 font-medium px-6 py-3 rounded-xl transition-colors"
                >
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                  </svg>
                  Or Email Directly
                </a>
              </div>

              <p className="text-sm text-neutral-500 mt-4">
                <strong>Contact:</strong> donate@globalcommunitycleaners.org — We can arrange pickup for large donations in the Chicago area.
              </p>
            </div>
          </div>
        </div>

        {/* Dedicated Sponsor Page Callout */}
        <div className="mt-10 bg-gradient-to-r from-brand-gold-50 to-amber-50 rounded-2xl p-6 lg:p-8 border border-brand-gold-200">
          <div className="flex flex-col sm:flex-row items-center gap-4">
            <div className="w-14 h-14 rounded-full bg-brand-gold-500 flex items-center justify-center flex-shrink-0">
              <MapPin className="w-7 h-7 text-white" />
            </div>
            <div className="flex-1 text-center sm:text-left">
              <h3 className="font-bold text-lg text-neutral-900 mb-1">
                Want to Schedule When Your Block Gets Cleaned?
              </h3>
              <p className="text-neutral-600 text-sm">
                Use our dedicated sponsor page to select a specific block and choose your preferred cleaning date and time.
              </p>
            </div>
            <Link
              href="/sponsor"
              className="flex-shrink-0 inline-flex items-center gap-2 bg-brand-gold-500 hover:bg-brand-gold-600 text-white font-semibold px-6 py-3 rounded-xl transition-colors"
            >
              Go to Sponsor Page
              <ChevronRight className="w-4 h-4" />
            </Link>
          </div>
        </div>

        {/* Links */}
        <div className="mt-8 flex flex-wrap justify-center gap-4 text-sm">
          <Link
            href="/donate/wall"
            className="text-neutral-600 hover:text-brand-green-600 flex items-center gap-1"
          >
            <Users className="w-4 h-4" />
            View Support Wall
          </Link>
          <Link
            href="/sponsor/calculator"
            className="text-neutral-600 hover:text-brand-gold-600 flex items-center gap-1"
          >
            <Sparkles className="w-4 h-4" />
            Enterprise Packages
            <ChevronRight className="w-3 h-3" />
          </Link>
        </div>
      </div>

      {/* Material Donation Form Modal */}
      {showMaterialForm && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center p-4 z-50">
          <div className="bg-white rounded-2xl p-6 max-w-lg w-full max-h-[90vh] overflow-y-auto">
            {materialSuccess ? (
              <div className="text-center py-8">
                <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
                  <Check className="w-8 h-8 text-green-600" />
                </div>
                <h3 className="text-xl font-bold text-neutral-900 mb-2">Thank You!</h3>
                <p className="text-neutral-600">
                  We&apos;ve received your inquiry and will contact you within 2-3 business days.
                </p>
              </div>
            ) : (
              <>
                <div className="flex justify-between items-center mb-6">
                  <h3 className="text-xl font-bold text-neutral-900">Material Donation Inquiry</h3>
                  <button
                    onClick={() => setShowMaterialForm(false)}
                    className="text-neutral-400 hover:text-neutral-600"
                  >
                    <X className="w-6 h-6" />
                  </button>
                </div>

                <form onSubmit={handleMaterialSubmit} className="space-y-4">
                  <div className="grid sm:grid-cols-2 gap-4">
                    <div>
                      <label className="block text-sm font-medium text-neutral-700 mb-1">
                        Your Name *
                      </label>
                      <input
                        type="text"
                        required
                        value={materialFormData.name}
                        onChange={(e) => setMaterialFormData(prev => ({ ...prev, name: e.target.value }))}
                        className="input"
                        placeholder="John Smith"
                      />
                    </div>
                    <div>
                      <label className="block text-sm font-medium text-neutral-700 mb-1">
                        Email *
                      </label>
                      <input
                        type="email"
                        required
                        value={materialFormData.email}
                        onChange={(e) => setMaterialFormData(prev => ({ ...prev, email: e.target.value }))}
                        className="input"
                        placeholder="john@example.com"
                      />
                    </div>
                  </div>

                  <div className="grid sm:grid-cols-2 gap-4">
                    <div>
                      <label className="block text-sm font-medium text-neutral-700 mb-1">
                        Phone (optional)
                      </label>
                      <input
                        type="tel"
                        value={materialFormData.phone}
                        onChange={(e) => setMaterialFormData(prev => ({ ...prev, phone: e.target.value }))}
                        className="input"
                        placeholder="(312) 555-1234"
                      />
                    </div>
                    <div>
                      <label className="block text-sm font-medium text-neutral-700 mb-1">
                        Company (optional)
                      </label>
                      <input
                        type="text"
                        value={materialFormData.company}
                        onChange={(e) => setMaterialFormData(prev => ({ ...prev, company: e.target.value }))}
                        className="input"
                        placeholder="ACME Inc."
                      />
                    </div>
                  </div>

                  <div>
                    <label className="block text-sm font-medium text-neutral-700 mb-1">
                      Items to Donate *
                    </label>
                    <textarea
                      required
                      value={materialFormData.items}
                      onChange={(e) => setMaterialFormData(prev => ({ ...prev, items: e.target.value }))}
                      className="input min-h-[80px]"
                      placeholder="e.g., 10 pairs of work gloves, 5 brooms, 2 rakes..."
                    />
                  </div>

                  <div className="grid sm:grid-cols-2 gap-4">
                    <div>
                      <label className="block text-sm font-medium text-neutral-700 mb-1">
                        Quantity/Amount
                      </label>
                      <input
                        type="text"
                        value={materialFormData.quantity}
                        onChange={(e) => setMaterialFormData(prev => ({ ...prev, quantity: e.target.value }))}
                        className="input"
                        placeholder="e.g., 20 items, 3 boxes"
                      />
                    </div>
                    <div>
                      <label className="block text-sm font-medium text-neutral-700 mb-1">
                        Condition
                      </label>
                      <select
                        value={materialFormData.condition}
                        onChange={(e) => setMaterialFormData(prev => ({ ...prev, condition: e.target.value }))}
                        className="input"
                      >
                        <option value="new">New</option>
                        <option value="like-new">Like New</option>
                        <option value="good">Good (Used)</option>
                        <option value="fair">Fair (Used)</option>
                      </select>
                    </div>
                  </div>

                  <div>
                    <label className="block text-sm font-medium text-neutral-700 mb-1">
                      How would you like to donate?
                    </label>
                    <div className="flex gap-4">
                      <label className="flex items-center gap-2 cursor-pointer">
                        <input
                          type="radio"
                          name="method"
                          checked={materialFormData.preferredMethod === 'pickup'}
                          onChange={() => setMaterialFormData(prev => ({ ...prev, preferredMethod: 'pickup' }))}
                          className="text-amber-500"
                        />
                        <span className="text-sm">Schedule Pickup</span>
                      </label>
                      <label className="flex items-center gap-2 cursor-pointer">
                        <input
                          type="radio"
                          name="method"
                          checked={materialFormData.preferredMethod === 'dropoff'}
                          onChange={() => setMaterialFormData(prev => ({ ...prev, preferredMethod: 'dropoff' }))}
                          className="text-amber-500"
                        />
                        <span className="text-sm">Drop Off</span>
                      </label>
                    </div>
                  </div>

                  {materialFormData.preferredMethod === 'pickup' && (
                    <div>
                      <label className="block text-sm font-medium text-neutral-700 mb-1">
                        Pickup Address
                      </label>
                      <input
                        type="text"
                        value={materialFormData.address}
                        onChange={(e) => setMaterialFormData(prev => ({ ...prev, address: e.target.value }))}
                        className="input"
                        placeholder="123 Main St, Chicago, IL 60623"
                      />
                    </div>
                  )}

                  <div>
                    <label className="block text-sm font-medium text-neutral-700 mb-1">
                      When are you available?
                    </label>
                    <input
                      type="text"
                      value={materialFormData.availableDates}
                      onChange={(e) => setMaterialFormData(prev => ({ ...prev, availableDates: e.target.value }))}
                      className="input"
                      placeholder="e.g., Weekdays after 5pm, or Saturday mornings"
                    />
                  </div>

                  <div>
                    <label className="block text-sm font-medium text-neutral-700 mb-1">
                      Additional Notes
                    </label>
                    <textarea
                      value={materialFormData.notes}
                      onChange={(e) => setMaterialFormData(prev => ({ ...prev, notes: e.target.value }))}
                      className="input min-h-[60px]"
                      placeholder="Anything else we should know?"
                    />
                  </div>

                  <button
                    type="submit"
                    disabled={isMaterialSubmitting || !materialFormData.name || !materialFormData.email || !materialFormData.items}
                    className="btn-primary w-full bg-amber-500 hover:bg-amber-600 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {isMaterialSubmitting ? (
                      <span className="flex items-center justify-center gap-2">
                        <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                        </svg>
                        Submitting...
                      </span>
                    ) : (
                      'Submit Donation Inquiry'
                    )}
                  </button>

                  <p className="text-xs text-neutral-500 text-center">
                    We&apos;ll contact you within 2-3 business days to coordinate your donation.
                  </p>
                </form>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

function LoadingFallback() {
  return (
    <div className="section-container py-12 lg:py-16">
      <div className="max-w-4xl mx-auto">
        <div className="text-center mb-10">
          <div className="h-10 bg-neutral-100 rounded animate-pulse w-64 mx-auto mb-4" />
          <div className="h-6 bg-neutral-100 rounded animate-pulse w-96 mx-auto" />
        </div>
        <div className="bg-white rounded-2xl shadow-lg p-8">
          <div className="space-y-4">
            <div className="h-8 bg-neutral-100 rounded animate-pulse w-48" />
            <div className="grid grid-cols-4 gap-3">
              {[1,2,3,4].map(i => (
                <div key={i} className="h-16 bg-neutral-100 rounded-xl animate-pulse" />
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

export default function DonatePage() {
  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      <Suspense fallback={<LoadingFallback />}>
        <DonateContent />
      </Suspense>
    </div>
  )
}
