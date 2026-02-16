'use client'

import { useState } from 'react'
import { ChevronRight, Info, TrendingUp } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cn } from '@/lib/utils'
import {
  formatPrice,
  calculateSavings,
  volumeDiscounts,
  type SponsorPackageConfig,
} from '@/lib/sponsor-pricing'

interface BlockCountSectionProps {
  blockCount: number
  isRecurring: boolean
  recurringFrequency?: 'monthly' | 'quarterly' | 'annual'
  onChange: (updates: Partial<SponsorPackageConfig>) => void
  onNext: () => void
}

const presetCounts = [1, 5, 10, 25, 50, 100]

export function BlockCountSection({
  blockCount,
  isRecurring,
  recurringFrequency,
  onChange,
  onNext,
}: BlockCountSectionProps) {
  const [customCount, setCustomCount] = useState(
    presetCounts.includes(blockCount) ? '' : String(blockCount)
  )

  const savings = calculateSavings(blockCount)

  const handlePresetClick = (count: number) => {
    setCustomCount('')
    onChange({ blockCount: count })
  }

  const handleCustomChange = (value: string) => {
    const num = parseInt(value, 10)
    setCustomCount(value)
    if (!isNaN(num) && num > 0) {
      onChange({ blockCount: num })
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <span className="w-8 h-8 rounded-full bg-brand-gold-100 text-brand-gold-600 flex items-center justify-center text-sm font-bold">
            1
          </span>
          Choose Your Impact
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        {/* Block Count Selection */}
        <div>
          <label className="block text-sm font-medium text-neutral-700 mb-3">
            How many blocks do you want to clean?
          </label>

          {/* Preset buttons */}
          <div className="grid grid-cols-3 sm:grid-cols-6 gap-2 mb-4">
            {presetCounts.map((count) => (
              <button
                key={count}
                onClick={() => handlePresetClick(count)}
                className={cn(
                  'py-3 px-4 rounded-xl border-2 font-semibold transition-all',
                  blockCount === count && !customCount
                    ? 'border-brand-gold-500 bg-brand-gold-50 text-brand-gold-700'
                    : 'border-neutral-200 hover:border-neutral-300 text-neutral-700'
                )}
              >
                {count}
              </button>
            ))}
          </div>

          {/* Custom input */}
          <div className="flex items-center gap-3">
            <span className="text-sm text-neutral-600">Or enter custom:</span>
            <input
              type="number"
              min="1"
              max="10000"
              value={customCount}
              onChange={(e) => handleCustomChange(e.target.value)}
              placeholder="Custom"
              className="w-24 px-3 py-2 rounded-lg border border-neutral-300 focus:border-brand-gold-500 focus:ring-2 focus:ring-brand-gold-500/20 outline-none"
            />
            <span className="text-sm text-neutral-600">blocks</span>
          </div>
        </div>

        {/* Volume Discount on Add-ons Indicator */}
        {savings.currentTier.addonDiscount > 0 && (
          <div className="bg-brand-green-50 border border-brand-green-200 rounded-xl p-4">
            <div className="flex items-center gap-2 mb-1">
              <TrendingUp className="w-4 h-4 text-brand-green-600" />
              <span className="font-semibold text-brand-green-700">
                {savings.currentTier.addonDiscount}% Off Add-on Services!
              </span>
            </div>
            <p className="text-sm text-brand-green-600">
              Workers still receive the full $100/block. Volume discount applies to add-ons only.
            </p>
          </div>
        )}

        {/* Next Tier Teaser */}
        {savings.nextTier && savings.blocksToNextTier <= 10 && (
          <div className="bg-brand-gold-50 border border-brand-gold-200 rounded-xl p-4">
            <div className="flex items-center gap-2 mb-1">
              <Info className="w-4 h-4 text-brand-gold-600" />
              <span className="font-medium text-brand-gold-700">
                Add {savings.blocksToNextTier} more blocks for {savings.nextTier.addonDiscount}% off add-ons
              </span>
            </div>
            <p className="text-sm text-brand-gold-600">
              Save more on branding, services, and media production
            </p>
          </div>
        )}

        {/* Recurring Toggle */}
        <div className="border-t pt-6">
          <label className="block text-sm font-medium text-neutral-700 mb-3">
            Frequency
          </label>
          <div className="flex gap-2 flex-wrap">
            <button
              onClick={() => onChange({ isRecurring: false, recurringFrequency: undefined })}
              className={cn(
                'px-4 py-2 rounded-lg border-2 font-medium transition-all',
                !isRecurring
                  ? 'border-brand-gold-500 bg-brand-gold-50 text-brand-gold-700'
                  : 'border-neutral-200 hover:border-neutral-300 text-neutral-700'
              )}
            >
              One-time
            </button>
            <button
              onClick={() => onChange({ isRecurring: true, recurringFrequency: 'monthly' })}
              className={cn(
                'px-4 py-2 rounded-lg border-2 font-medium transition-all',
                isRecurring && recurringFrequency === 'monthly'
                  ? 'border-brand-gold-500 bg-brand-gold-50 text-brand-gold-700'
                  : 'border-neutral-200 hover:border-neutral-300 text-neutral-700'
              )}
            >
              Monthly <span className="text-brand-green-600 text-xs ml-1">-10%</span>
            </button>
            <button
              onClick={() => onChange({ isRecurring: true, recurringFrequency: 'quarterly' })}
              className={cn(
                'px-4 py-2 rounded-lg border-2 font-medium transition-all',
                isRecurring && recurringFrequency === 'quarterly'
                  ? 'border-brand-gold-500 bg-brand-gold-50 text-brand-gold-700'
                  : 'border-neutral-200 hover:border-neutral-300 text-neutral-700'
              )}
            >
              Quarterly <span className="text-brand-green-600 text-xs ml-1">-15%</span>
            </button>
            <button
              onClick={() => onChange({ isRecurring: true, recurringFrequency: 'annual' })}
              className={cn(
                'px-4 py-2 rounded-lg border-2 font-medium transition-all',
                isRecurring && recurringFrequency === 'annual'
                  ? 'border-brand-gold-500 bg-brand-gold-50 text-brand-gold-700'
                  : 'border-neutral-200 hover:border-neutral-300 text-neutral-700'
              )}
            >
              Annual <span className="text-brand-green-600 text-xs ml-1">-20%</span>
            </button>
          </div>
        </div>

        {/* Volume Discount Tiers Info */}
        <div className="border-t pt-6">
          <button
            onClick={() => {}}
            className="flex items-center gap-2 text-sm text-neutral-500 hover:text-neutral-700 transition-colors"
          >
            <Info className="w-4 h-4" />
            View volume discount tiers
          </button>
          <div className="mt-3 grid grid-cols-4 gap-2">
            {volumeDiscounts.map((tier, index) => (
              <div
                key={index}
                className={cn(
                  'text-center p-2 rounded-lg border',
                  blockCount >= tier.minBlocks && (tier.maxBlocks === null || blockCount <= tier.maxBlocks)
                    ? 'border-brand-gold-500 bg-brand-gold-50'
                    : 'border-neutral-200'
                )}
              >
                <p className="text-xs text-neutral-500">
                  {tier.minBlocks}{tier.maxBlocks ? `-${tier.maxBlocks}` : '+'}
                </p>
                <p className="font-semibold text-neutral-900">{formatPrice(tier.pricePerBlock)}/block</p>
                {tier.addonDiscount > 0 && (
                  <p className="text-xs text-brand-green-600">{tier.addonDiscount}% off add-ons</p>
                )}
              </div>
            ))}
          </div>
        </div>

        {/* Next Button */}
        <div className="pt-4">
          <button
            onClick={onNext}
            className="w-full btn-primary py-4 text-lg flex items-center justify-center gap-2"
          >
            Continue to Branding
            <ChevronRight className="w-5 h-5" />
          </button>
        </div>
      </CardContent>
    </Card>
  )
}
