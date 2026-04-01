'use client'

import { forwardRef } from 'react'
import Link from 'next/link'
import { Check, CreditCard, Sparkles } from 'lucide-react'
import type { SponsorFrequency } from './FrequencySelector'

const FREQUENCY_LABELS: Record<SponsorFrequency, { suffix: string; period: string }> = {
  single: { suffix: '', period: 'one-time' },
  weekly: { suffix: '/wk', period: 'weekly' },
  biweekly: { suffix: '/2wk', period: 'bi-weekly' },
  monthly: { suffix: '/mo', period: 'monthly' },
}

interface CheckoutSummaryProps {
  blockCount: number
  sponsorType: SponsorFrequency
  isFormReady: boolean
  isBlocksSelected: boolean
  isProcessing: boolean
  formName: string
  calculatorUrl: string
  onCheckoutClick: () => void
}

export const CheckoutSummary = forwardRef<HTMLDivElement, CheckoutSummaryProps>(
  function CheckoutSummary(
    { blockCount, sponsorType, isFormReady, isBlocksSelected, isProcessing, formName, calculatorUrl, onCheckoutClick },
    ref,
  ) {
    const totalAmount = blockCount * 100
    const freq = FREQUENCY_LABELS[sponsorType]
    const priceLabel = `$${totalAmount}${freq.suffix}`

    return (
      <section className="mb-16" ref={ref}>
        <div className="bg-gradient-to-br from-brand-green-900 to-brand-green-800 rounded-2xl p-8 text-white shadow-xl">
          <div className="flex flex-col sm:flex-row items-center justify-between gap-6">
            <div>
              <h3 className="text-2xl font-bold mb-1">Order Summary</h3>
              <p className="text-white/70 text-sm">
                {blockCount} block{blockCount !== 1 ? 's' : ''} × $100
                {sponsorType === 'single' ? ' one-time' : ` ${freq.period}`}
              </p>
              <div className="mt-3 flex items-center gap-4 text-xs text-white/60">
                <span className="flex items-center gap-1"><Check className="w-3 h-3" /> Before &amp; after photos</span>
                <span className="flex items-center gap-1"><Check className="w-3 h-3" /> Tax receipt</span>
                <span className="flex items-center gap-1"><Check className="w-3 h-3" /> Impact summary</span>
              </div>
            </div>
            <div className="text-center sm:text-right">
              <p className="text-4xl font-bold text-brand-green-400">{priceLabel}</p>
              {sponsorType !== 'single' && (
                <p className="text-xs text-white/50 mt-1">Billed {freq.period}</p>
              )}
            </div>
          </div>

          <div className="mt-6 flex flex-col sm:flex-row gap-3">
            <button
              onClick={onCheckoutClick}
              disabled={!isFormReady || isProcessing}
              aria-disabled={!isFormReady || isProcessing}
              aria-label={
                isProcessing
                  ? 'Processing checkout'
                  : !isFormReady
                    ? `Checkout disabled — ${!isBlocksSelected ? 'select blocks first' : !formName ? 'enter your name' : 'enter your email'}`
                    : `Checkout ${priceLabel}`
              }
              className={`flex-1 py-4 px-8 rounded-xl text-lg font-bold transition-all duration-300 flex items-center justify-center gap-2 ${
                isFormReady && !isProcessing
                  ? 'bg-brand-green-500 hover:bg-brand-green-600 text-brand-green-900 shadow-lg animate-breathe cursor-pointer'
                  : 'bg-white/20 text-white/50 cursor-not-allowed'
              }`}
            >
              {isProcessing ? (
                <>
                  <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" /><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" /></svg>
                  Processing...
                </>
              ) : (
                <>
                  <CreditCard className="w-5 h-5" />
                  Checkout — {priceLabel}
                </>
              )}
            </button>
            <Link
              href={calculatorUrl}
              className="py-4 px-6 rounded-xl border-2 border-white/30 text-white font-semibold text-center hover:bg-white/10 transition-colors flex items-center justify-center gap-2"
            >
              <Sparkles className="w-5 h-5" />
              Custom Package
            </Link>
          </div>

          {/* Progress hint */}
          {!isFormReady && (
            <p className="text-xs text-white/50 text-center mt-4">
              {!isBlocksSelected ? 'Select blocks above to continue' :
               !formName ? 'Enter your name to continue' :
               'Enter your email to continue'}
            </p>
          )}

          <p className="text-xs text-white/40 text-center mt-4">
            GCC is a registered 501(c)(3). Your donation is 100% tax-deductible.
          </p>
        </div>
      </section>
    )
  }
)
