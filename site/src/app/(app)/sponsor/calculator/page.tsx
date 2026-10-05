'use client'

import { useState, useMemo, useCallback, Suspense } from 'react'
import { useSearchParams } from 'next/navigation'
import { Calculator, Sparkles, Building2 } from 'lucide-react'
import { BlockCountSection } from '@/components/sponsor/calculator/sections/BlockCountSection'
import { BrandingSection } from '@/components/sponsor/calculator/sections/BrandingSection'
import { ServicesSection } from '@/components/sponsor/calculator/sections/ServicesSection'
import { EventsSection } from '@/components/sponsor/calculator/sections/EventsSection'
import { MediaSection } from '@/components/sponsor/calculator/sections/MediaSection'
import { QuoteSummary } from '@/components/sponsor/calculator/QuoteSummary'
import {
  createEmptyPackageConfig,
  calculatePackagePrice,
  type SponsorPackageConfig,
} from '@/lib/sponsor-pricing'

export default function SponsorCalculatorPageWrapper() {
  return (
    <Suspense fallback={
      <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white flex items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-brand-green-600" />
      </div>
    }>
      <SponsorCalculatorPage />
    </Suspense>
  )
}

/** Map sponsor-page frequency values to calculator recurringFrequency */
const FREQUENCY_MAP: Record<string, SponsorPackageConfig['recurringFrequency']> = {
  weekly: 'weekly',
  biweekly: 'biweekly',
  monthly: 'monthly',
}

function initConfigFromParams(params: URLSearchParams): SponsorPackageConfig {
  const base = createEmptyPackageConfig()

  const count = parseInt(params.get('count') || '', 10)
  if (count > 0) base.blockCount = count

  const freq = params.get('frequency')
  if (freq && freq in FREQUENCY_MAP) {
    base.isRecurring = true
    base.recurringFrequency = FREQUENCY_MAP[freq]
  }

  return base
}

function SponsorCalculatorPage() {
  const searchParams = useSearchParams()
  const [config, setConfig] = useState<SponsorPackageConfig>(() => initConfigFromParams(searchParams))
  const [activeStep, setActiveStep] = useState(0)
  // Incremented when the last step asks for checkout; QuoteSummary owns the form.
  const [checkoutRequest, setCheckoutRequest] = useState(0)

  const priceBreakdown = useMemo(() => calculatePackagePrice(config), [config])

  const updateConfig = useCallback((updates: Partial<SponsorPackageConfig>) => {
    setConfig((prev) => ({ ...prev, ...updates }))
  }, [])

  const steps = [
    { id: 'blocks', label: 'Blocks', icon: Calculator },
    { id: 'branding', label: 'Branding', icon: Sparkles },
    { id: 'services', label: 'Services', icon: Building2 },
    { id: 'events', label: 'Events', icon: Sparkles },
    { id: 'media', label: 'Media', icon: Sparkles },
  ]

  return (
    <div className="bg-gradient-to-b from-brand-green-50 to-white">
      {/* Page Title and Steps */}
      <div className="bg-white/80 backdrop-blur border-b sticky top-20 z-10">
        <div className="section-container py-4">
          <h1 className="font-bold text-xl mb-4">Build Your Sponsorship Package</h1>
          {/* Progress Steps */}
          <div className="flex gap-2 overflow-x-auto scrollbar-hide">
            {steps.map((step, index) => {
              const Icon = step.icon
              const isActive = index === activeStep
              const isCompleted = index < activeStep

              return (
                <button
                  key={step.id}
                  onClick={() => setActiveStep(index)}
                  className={`flex items-center gap-2 px-4 py-2 rounded-full text-sm font-medium transition-all whitespace-nowrap ${
                    isActive
                      ? 'bg-brand-green-500 text-neutral-900'
                      : isCompleted
                        ? 'bg-brand-green-100 text-brand-green-700'
                        : 'bg-neutral-100 text-neutral-600 hover:bg-neutral-200'
                  }`}
                >
                  <Icon className="w-4 h-4" />
                  {step.label}
                </button>
              )
            })}
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div className="section-container py-8">
        <div className="grid lg:grid-cols-3 gap-8">
          {/* Left Column - Package Builder */}
          <div className="lg:col-span-2 space-y-6">
            {/* Transparency Banner */}
            <div className="bg-brand-green-50 border border-brand-green-200 rounded-xl p-4">
              <div className="flex items-start gap-3">
                <div className="w-10 h-10 rounded-full bg-brand-green-100 flex items-center justify-center flex-shrink-0">
                  <span className="text-lg">💰</span>
                </div>
                <div>
                  <h3 className="font-semibold text-brand-green-800">100% Transparent Pricing</h3>
                  <p className="text-sm text-brand-green-700">
                    <strong>$100 base = $100 to the worker.</strong> Add-ons fund GCC operations,
                    equipment, and creative services. Every invoice shows exactly where your money
                    goes.
                  </p>
                </div>
              </div>
            </div>

            {/* Step Content */}
            {activeStep === 0 && (
              <BlockCountSection
                blockCount={config.blockCount}
                isRecurring={config.isRecurring}
                recurringFrequency={config.recurringFrequency}
                onChange={updateConfig}
                onNext={() => setActiveStep(1)}
              />
            )}

            {activeStep === 1 && (
              <BrandingSection
                config={config}
                onChange={updateConfig}
                onNext={() => setActiveStep(2)}
                onBack={() => setActiveStep(0)}
              />
            )}

            {activeStep === 2 && (
              <ServicesSection
                config={config}
                onChange={updateConfig}
                onNext={() => setActiveStep(3)}
                onBack={() => setActiveStep(1)}
              />
            )}

            {activeStep === 3 && (
              <EventsSection
                config={config}
                onChange={updateConfig}
                onNext={() => setActiveStep(4)}
                onBack={() => setActiveStep(2)}
              />
            )}

            {activeStep === 4 && (
              <MediaSection
                config={config}
                onChange={updateConfig}
                onBack={() => setActiveStep(3)}
                onCheckout={() => setCheckoutRequest((n) => n + 1)}
                priceBreakdown={priceBreakdown}
              />
            )}
          </div>

          {/* Right Column - Quote Summary (Sticky) */}
          <div className="lg:col-span-1">
            <div className="sticky top-36">
              <QuoteSummary
                config={config}
                priceBreakdown={priceBreakdown}
                checkoutRequest={checkoutRequest}
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
