'use client'

import { getTierInfo, type EarlyAdopterTier } from '@/lib/waitlist/rankings'

interface EarlyAdopterBadgeProps {
  tier: EarlyAdopterTier
  size?: 'sm' | 'md' | 'lg'
  showDescription?: boolean
}

export function EarlyAdopterBadge({
  tier,
  size = 'md',
  showDescription = false,
}: EarlyAdopterBadgeProps) {
  const info = getTierInfo(tier)

  const sizeClasses = {
    sm: 'text-xs px-2 py-1',
    md: 'text-sm px-3 py-1.5',
    lg: 'text-base px-4 py-2',
  }

  const emojiSizes = {
    sm: 'text-sm',
    md: 'text-lg',
    lg: 'text-xl',
  }

  return (
    <div className="flex flex-col items-center gap-1">
      <div
        className={`
          inline-flex items-center gap-2 rounded-full font-semibold text-white
          ${info.bgColor} ${sizeClasses[size]}
          shadow-lg ring-2 ring-white/20
        `}
      >
        <span className={emojiSizes[size]}>{info.emoji}</span>
        <span>{info.label}</span>
        <span className={emojiSizes[size]}>{info.emoji}</span>
      </div>
      {showDescription && (
        <p className="text-xs text-neutral-500 text-center max-w-48">
          {info.description}
        </p>
      )}
    </div>
  )
}
