'use client'

import { useState, useEffect, useRef, useMemo, Suspense } from 'react'
import { useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { Building2, Sparkles, ChevronRight, CheckCircle2 } from 'lucide-react'

import { SponsorModeSelector } from '@/components/sponsor/SponsorModeSelector'
import { BlockSelectionStep } from '@/components/sponsor/BlockSelectionStep'
import { FrequencySelector, type SponsorFrequency } from '@/components/sponsor/FrequencySelector'
import { SponsorInfoForm } from '@/components/sponsor/SponsorInfoForm'
import { CheckoutSummary } from '@/components/sponsor/CheckoutSummary'
import { UpsellModal } from '@/components/sponsor/UpsellModal'
import type { SponsorBlock, SponsorshipMode, SponsorFormData } from '@/components/sponsor/types'
import { formatStreetName } from '@/components/sponsor/types'
import { InlineError } from '@/components/shared/InlineError'
import { blocksFromApi, buildSponsorCheckoutPayload, isAssignableNow } from '@/lib/sponsor-checkout'

const FREQUENCY_TO_API: Record<SponsorFrequency, string> = {
  single: 'one_time',
  weekly: 'weekly',
  biweekly: 'biweekly',
  monthly: 'monthly',
}

export default function SponsorPageWrapper() {
  return (
    <Suspense fallback={
      <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white flex items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-brand-green-600" />
      </div>
    }>
      <SponsorPage />
    </Suspense>
  )
}

function SponsorPage() {
  const searchParams = useSearchParams()
  const preselectedBlockId = searchParams.get('block')

  const [blocks, setBlocks] = useState<SponsorBlock[]>([])
  const [mode, setMode] = useState<SponsorshipMode>('specific')
  const [selectedBlocks, setSelectedBlocks] = useState<SponsorBlock[]>([])
  const [generalBlockCount, setGeneralBlockCount] = useState(1)
  const [sponsorType, setSponsorType] = useState<SponsorFrequency>('single')
  const [isLoading, setIsLoading] = useState(true)
  const [isProcessing, setIsProcessing] = useState(false)
  const [preselectedBlock, setPreselectedBlock] = useState<SponsorBlock | null>(null)
  const [showUpsellModal, setShowUpsellModal] = useState(false)
  const [checkoutError, setCheckoutError] = useState<string | null>(null)
  const [formData, setFormData] = useState<SponsorFormData>({
    name: '',
    email: '',
    company: '',
    isAnonymous: false,
    message: '',
  })

  const [cleaningDate, setCleaningDate] = useState('')
  const [cleaningTimeStart, setCleaningTimeStart] = useState('')
  const [cleaningTimeEnd, setCleaningTimeEnd] = useState('')

  const blockSectionRef = useRef<HTMLDivElement>(null)
  const infoSectionRef = useRef<HTMLDivElement>(null)
  const checkoutSectionRef = useRef<HTMLDivElement>(null)

  // Date bounds for cleaning schedule
  const tomorrow = new Date()
  tomorrow.setDate(tomorrow.getDate() + 1)
  const minDate = tomorrow.toISOString().split('T')[0]
  const maxDateObj = new Date()
  maxDateObj.setMonth(maxDateObj.getMonth() + 3)
  const maxDate = maxDateObj.toISOString().split('T')[0]

  useEffect(() => {
    fetchUnfundedBlocks()
  }, [])

  // Auto-select block from URL param once blocks are loaded
  useEffect(() => {
    if (!preselectedBlockId || blocks.length === 0) return
    const match = blocks.find(b => b.id === preselectedBlockId)
    if (match && !selectedBlocks.some(b => b.id === match.id)) {
      setSelectedBlocks([match])
      setPreselectedBlock(match)
      setMode('specific')
      setTimeout(() => {
        blockSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      }, 300)
    }
  }, [preselectedBlockId, blocks])

  const fetchUnfundedBlocks = async () => {
    try {
      const response = await fetch('/api/blocks?limit=50')
      const data = await response.json()
      // Every block is offered, whatever its status: a block can be sponsored
      // at any time, and one that is already cleaned is funded for its next cycle.
      setBlocks(blocksFromApi<SponsorBlock>(data))
    } catch (error) {
      console.error('Failed to fetch blocks:', error)
    } finally {
      setIsLoading(false)
    }
  }

  const toggleBlockSelection = (block: SponsorBlock) => {
    setSelectedBlocks(prev =>
      prev.some(b => b.id === block.id)
        ? prev.filter(b => b.id !== block.id)
        : [...prev, block]
    )
  }

  // Blocks that can be funded and released to a worker right away.
  const assignableBlockCount = blocks.filter(isAssignableNow).length

  // Pricing
  const blockCount = mode === 'specific' ? selectedBlocks.length : generalBlockCount
  // "Let GCC choose" is always a valid selection: when fewer blocks are ready
  // than were asked for, the sponsorship is a reservation for the next cycle.
  const isBlocksSelected = mode === 'specific' ? selectedBlocks.length > 0 : generalBlockCount > 0
  const isFormReady = isBlocksSelected && !!formData.name && !!formData.email

  // Build calculator URL with current selections preserved
  const calculatorUrl = useMemo(() => {
    const params = new URLSearchParams()
    params.set('count', String(blockCount))
    if (sponsorType !== 'single') params.set('frequency', sponsorType)
    if (mode === 'specific' && selectedBlocks.length > 0) {
      params.set('blocks', selectedBlocks.map(b => b.id).join(','))
    }
    return `/sponsor/calculator?${params.toString()}`
  }, [blockCount, sponsorType, mode, selectedBlocks])

  const handleCheckout = async () => {
    const payload = buildSponsorCheckoutPayload({
      mode,
      selectedBlocks,
      generalBlockCount,
      availableBlockCount: assignableBlockCount,
      shared: {
        frequency: FREQUENCY_TO_API[sponsorType],
        name: formData.name,
        email: formData.email,
        company: formData.company,
        isAnonymous: formData.isAnonymous,
        message: formData.message,
        preferredCleaningDate: cleaningDate,
        preferredCleaningTimeStart: cleaningTimeStart || null,
        preferredCleaningTimeEnd: cleaningTimeEnd || null,
      },
    })
    if (!payload) return
    setIsProcessing(true)
    setCheckoutError(null)

    try {
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
      setCheckoutError('Failed to process. Please try again.')
    } finally {
      setIsProcessing(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      <div className="section-container py-golden-xl">
        <div className="max-w-golden-wide mx-auto">

          {/* Hero */}
          <SponsorHero blockCount={assignableBlockCount} isLoading={isLoading} />

          {/* Returning-sponsor sign-in prompt */}
          <div className="mb-6 rounded-xl border border-brand-green-200 bg-brand-green-50 px-4 py-3 flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-brand-green-900">
              <strong>Already sponsored before?</strong> Sign in to see all your donations, scheduled cleanings, and receipts.
            </p>
            <Link
              href="/sponsor/login"
              className="inline-flex items-center gap-1.5 rounded-lg bg-white px-4 py-2 text-sm font-semibold text-brand-green-700 border border-brand-green-200 hover:bg-brand-green-100 transition-colors"
            >
              Sign in to dashboard →
            </Link>
          </div>

          {/* Pre-selected block confirmation */}
          {preselectedBlock && (
            <PreselectedBlockBanner block={preselectedBlock} />
          )}

          {/* Step 1: Mode */}
          <SponsorModeSelector mode={mode} onModeChange={setMode} />

          {/* Step 2: Block selection */}
          <BlockSelectionStep
            ref={blockSectionRef}
            mode={mode}
            blocks={blocks}
            assignableBlockCount={assignableBlockCount}
            selectedBlocks={selectedBlocks}
            isLoading={isLoading}
            generalBlockCount={generalBlockCount}
            onToggleBlock={toggleBlockSelection}
            onGeneralCountChange={setGeneralBlockCount}
          />

          {/* Step 3: Frequency */}
          <FrequencySelector
            blockCount={blockCount}
            sponsorType={sponsorType}
            onSponsorTypeChange={setSponsorType}
          />

          {/* Step 4: Info */}
          <SponsorInfoForm
            ref={infoSectionRef}
            formData={formData}
            onFormDataChange={setFormData}
            cleaningDate={cleaningDate}
            onCleaningDateChange={setCleaningDate}
            cleaningTimeStart={cleaningTimeStart}
            onCleaningTimeStartChange={setCleaningTimeStart}
            cleaningTimeEnd={cleaningTimeEnd}
            onCleaningTimeEndChange={setCleaningTimeEnd}
            minDate={minDate}
            maxDate={maxDate}
          />

          {/* Checkout */}
          <InlineError message={checkoutError} className="mb-4" />
          <CheckoutSummary
            ref={checkoutSectionRef}
            blockCount={blockCount}
            sponsorType={sponsorType}
            isFormReady={isFormReady}
            isBlocksSelected={isBlocksSelected}
            isProcessing={isProcessing}
            formName={formData.name}
            calculatorUrl={calculatorUrl}
            onCheckoutClick={() => isFormReady && setShowUpsellModal(true)}
          />

          {/* Bottom: enterprise & custom */}
          <BottomCTAs calculatorUrl={calculatorUrl} />
        </div>
      </div>

      {showUpsellModal && (
        <UpsellModal
          onClose={() => setShowUpsellModal(false)}
          onProceed={handleCheckout}
          calculatorUrl={calculatorUrl}
        />
      )}
    </div>
  )
}

/* ─── Small inline sub-components (page-specific, not reusable) ─── */

function SponsorHero({ blockCount, isLoading }: { blockCount: number; isLoading: boolean }) {
  return (
    <>
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

      <div className="text-center mb-12 animate-fade-in-up">
        <h1 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-3">
          Sponsor a Block — $100
        </h1>
        <p className="text-lg text-neutral-600 max-w-golden-content mx-auto mb-5">
          Your $100 sponsorship directly pays a North Lawndale resident to clean a block.
          100% goes to the worker. You select the cleaning date.
        </p>
        {!isLoading && blockCount > 0 && (
          <div className="inline-flex items-center gap-2 bg-amber-100 text-amber-800 px-4 py-2 rounded-full font-medium">
            <span className="flex h-2.5 w-2.5 relative">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-500 opacity-75"></span>
              <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-amber-500"></span>
            </span>
            {blockCount} block{blockCount !== 1 ? 's' : ''} need{blockCount === 1 ? 's' : ''} sponsors
          </div>
        )}
      </div>
    </>
  )
}

function PreselectedBlockBanner({ block }: { block: SponsorBlock }) {
  return (
    <div className="mb-10 animate-fade-in-up">
      <div className="bg-gradient-to-r from-brand-green-50 to-emerald-50 border-2 border-brand-green-200 rounded-2xl p-5">
        <div className="flex items-center gap-4">
          <div className="w-12 h-12 rounded-full bg-brand-green-100 flex items-center justify-center flex-shrink-0">
            <CheckCircle2 className="w-6 h-6 text-brand-green-600" />
          </div>
          <div className="flex-1 min-w-0">
            <h3 className="font-bold text-brand-green-900 text-lg">
              Funding: {block.fromCorner} to {block.toCorner}
            </h3>
            <p className="text-brand-green-700 text-sm">
              {formatStreetName(block.streetName)} — Your $100 pays a local resident to clean this block
            </p>
          </div>
          <div className="text-right flex-shrink-0">
            <p className="text-2xl font-bold text-brand-green-700">$100</p>
          </div>
        </div>
      </div>
    </div>
  )
}

function BottomCTAs({ calculatorUrl }: { calculatorUrl: string }) {
  return (
    <section className="mb-8">
      {/* Decorated section header */}
      <div className="text-center mb-8">
        <div className="flex items-center justify-center gap-4 mb-3">
          <span className="h-px flex-1 max-w-[80px] bg-gradient-to-r from-transparent to-brand-green-300" />
          <Sparkles className="w-5 h-5 text-brand-green-500" />
          <span className="h-px flex-1 max-w-[80px] bg-gradient-to-l from-transparent to-brand-green-300" />
        </div>
        <h3 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-2">
          Want to do more?
        </h3>
        <p className="text-neutral-500 text-sm max-w-md mx-auto">
          Maximize your community impact with custom branding, dedicated routes, and enterprise-level partnerships
        </p>
      </div>

      <div className="space-y-4">
        <Link
          href={calculatorUrl}
          className="block bg-gradient-to-r from-brand-green-500 to-amber-500 rounded-2xl p-7 text-white hover:from-brand-green-600 hover:to-amber-600 transition-all group shadow-lg hover:shadow-xl"
        >
          <div className="flex items-center gap-5">
            <div className="w-14 h-14 rounded-full bg-white/20 flex items-center justify-center flex-shrink-0">
              <Sparkles className="w-7 h-7" />
            </div>
            <div className="flex-1">
              <h4 className="font-bold text-xl mb-1">Build a Custom Package</h4>
              <p className="text-white/90 text-sm">
                Custom branding, dedicated routes, documentary features, and enterprise benefits
              </p>
            </div>
            <ChevronRight className="w-6 h-6 text-white/60 group-hover:translate-x-1 transition-transform" />
          </div>
        </Link>

        <Link
          href="/sponsor/enterprise"
          className="block bg-gradient-to-r from-brand-green-800 to-brand-green-900 rounded-2xl p-7 hover:from-brand-green-700 hover:to-brand-green-800 transition-all group shadow-lg hover:shadow-xl"
        >
          <div className="flex items-center gap-5">
            <div className="w-14 h-14 rounded-full bg-brand-green-500 flex items-center justify-center flex-shrink-0">
              <Building2 className="w-6 h-6 text-brand-green-900" />
            </div>
            <div className="flex-1">
              <h4 className="font-bold text-xl text-white mb-1">Enterprise Partnerships</h4>
              <p className="text-sm text-white/70">
                $25,000+ — Dedicated support, documentary production, and full neighborhood impact campaigns
              </p>
            </div>
            <ChevronRight className="w-6 h-6 text-white/60 group-hover:translate-x-1 transition-transform" />
          </div>
        </Link>
      </div>
    </section>
  )
}
