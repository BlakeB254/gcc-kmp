'use client'

import * as React from 'react'
import { Check, Star, Sparkles, Crown, Trophy } from 'lucide-react'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { cn, formatCurrency } from '@/lib/utils'
import { type SponsorTier, tierConfig as sharedTierConfig } from '@/lib/sponsor-tiers'

// Re-export from shared module for backwards compatibility
export { getTierBenefits, getTierFromBlockCount, type SponsorTier } from '@/lib/sponsor-tiers'

interface TierCardProps {
  tier: SponsorTier
  blockCount: number
  totalAmount: number // in cents
  benefits: string[]
  isPopular?: boolean
  isSelected?: boolean
  onSelect?: () => void
  className?: string
}

// Extend shared tier config with React icons (can't be in shared module)
const tierIcons: Record<SponsorTier, React.ComponentType<{ className?: string }>> = {
  bronze: Star,
  silver: Sparkles,
  gold: Crown,
  platinum: Trophy,
}

const tierConfig = {
  bronze: { ...sharedTierConfig.bronze, icon: tierIcons.bronze },
  silver: { ...sharedTierConfig.silver, icon: tierIcons.silver },
  gold: { ...sharedTierConfig.gold, icon: tierIcons.gold },
  platinum: { ...sharedTierConfig.platinum, icon: tierIcons.platinum },
}

export function TierCard({
  tier,
  blockCount,
  totalAmount,
  benefits,
  isPopular,
  isSelected,
  onSelect,
  className,
}: TierCardProps) {
  const config = tierConfig[tier]
  const Icon = config.icon

  return (
    <Card
      className={cn(
        'relative overflow-hidden transition-all',
        isSelected && 'ring-2 ring-primary ring-offset-2',
        isPopular && 'scale-[1.02]',
        className
      )}
    >
      {/* Popular badge */}
      {isPopular && (
        <div className="absolute right-0 top-0">
          <div className="rounded-bl-lg bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground">
            Most Popular
          </div>
        </div>
      )}

      <CardHeader
        className={cn(
          'bg-gradient-to-br pb-4',
          config.bgGradient,
          config.borderColor,
          'border-b'
        )}
      >
        <div className="flex items-center gap-3">
          <div
            className={cn(
              'rounded-full bg-white/80 p-2 shadow-sm',
              config.color
            )}
          >
            <Icon className="h-6 w-6" />
          </div>
          <div>
            <Badge variant="outline" className={cn('mb-1', config.color)}>
              {config.label} Sponsor
            </Badge>
            <p className="text-sm text-muted-foreground">
              {blockCount} {blockCount === 1 ? 'block' : 'blocks'}
            </p>
          </div>
        </div>

        {/* Price */}
        <div className="mt-4">
          <span className="text-4xl font-bold">{formatCurrency(totalAmount)}</span>
          <span className="text-muted-foreground"> one-time</span>
        </div>
      </CardHeader>

      <CardContent className="pt-4">
        {/* Benefits list */}
        <ul className="space-y-3">
          {benefits.map((benefit, index) => (
            <li key={index} className="flex items-start gap-2">
              <Check className={cn('mt-0.5 h-5 w-5 flex-shrink-0', config.color)} />
              <span className="text-sm">{benefit}</span>
            </li>
          ))}
        </ul>

        {/* Select button */}
        <Button
          onClick={onSelect}
          variant={isSelected ? 'default' : 'secondary'}
          className="mt-6 w-full"
        >
          {isSelected ? 'Selected' : 'Select This Tier'}
        </Button>
      </CardContent>
    </Card>
  )
}

