'use client'

import { Check, Star, Gift } from 'lucide-react'
import { cn } from '@/lib/utils'
import { formatPrice } from '@/lib/sponsor-pricing'

interface AddonCardProps {
  id: string
  name: string
  description: string
  pricePerBlock: number
  blockCount: number
  isSelected: boolean
  isBundle?: boolean
  workerBonus?: number
  onToggle: () => void
}

export function AddonCard({
  id,
  name,
  description,
  pricePerBlock,
  blockCount,
  isSelected,
  isBundle,
  workerBonus,
  onToggle,
}: AddonCardProps) {
  const totalPrice = pricePerBlock * blockCount

  return (
    <button
      onClick={onToggle}
      className={cn(
        'w-full text-left p-4 rounded-xl border-2 transition-all',
        isSelected
          ? 'border-brand-gold-500 bg-brand-gold-50'
          : 'border-neutral-200 hover:border-brand-gold-300 hover:bg-neutral-50'
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <h4 className="font-semibold text-neutral-900 truncate">{name}</h4>
            {isBundle && (
              <span className="flex-shrink-0 text-xs px-2 py-0.5 bg-brand-gold-100 text-brand-gold-700 rounded-full font-medium">
                Bundle
              </span>
            )}
          </div>
          <p className="text-sm text-neutral-600 line-clamp-2">{description}</p>

          {/* Pricing */}
          <div className="flex items-center gap-2 mt-2 text-sm">
            <span className="text-neutral-500">
              {formatPrice(pricePerBlock)}/block
            </span>
            <span className="text-neutral-400">×</span>
            <span className="text-neutral-500">{blockCount} blocks</span>
            <span className="text-neutral-400">=</span>
            <span className="font-semibold text-brand-gold-600">{formatPrice(totalPrice)}</span>
          </div>

          {/* Worker Bonus */}
          {workerBonus && (
            <div className="flex items-center gap-1 mt-2 text-xs text-brand-green-600">
              <Gift className="w-3 h-3" />
              <span>+{formatPrice(workerBonus)}/block worker bonus</span>
            </div>
          )}
        </div>

        {/* Checkbox */}
        <div
          className={cn(
            'flex-shrink-0 w-6 h-6 rounded-md border-2 flex items-center justify-center transition-colors',
            isSelected
              ? 'bg-brand-gold-500 border-brand-gold-500'
              : 'border-neutral-300 bg-white'
          )}
        >
          {isSelected && <Check className="w-4 h-4 text-white" />}
        </div>
      </div>
    </button>
  )
}
