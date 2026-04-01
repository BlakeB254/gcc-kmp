/**
 * Sponsor tier definitions and utilities
 * This file has NO 'use client' directive so it can be used by Server Components
 */

export type SponsorTier = 'bronze' | 'silver' | 'gold' | 'platinum'

export interface TierInfo {
  label: string
  minBlocks: number
  maxBlocks: number | null
  color: string
  bgGradient: string
  borderColor: string
}

export const tierConfig: Record<SponsorTier, TierInfo> = {
  bronze: {
    label: 'Bronze',
    minBlocks: 1,
    maxBlocks: 4,
    color: 'text-amber-700',
    bgGradient: 'from-amber-50 to-orange-50',
    borderColor: 'border-amber-200',
  },
  silver: {
    label: 'Silver',
    minBlocks: 5,
    maxBlocks: 19,
    color: 'text-slate-500',
    bgGradient: 'from-slate-50 to-gray-100',
    borderColor: 'border-slate-300',
  },
  gold: {
    label: 'Gold',
    minBlocks: 20,
    maxBlocks: 49,
    color: 'text-brand-green-600',
    bgGradient: 'from-brand-green-50 to-amber-50',
    borderColor: 'border-brand-green-300',
  },
  platinum: {
    label: 'Platinum',
    minBlocks: 50,
    maxBlocks: null,
    color: 'text-violet-600',
    bgGradient: 'from-violet-50 to-purple-50',
    borderColor: 'border-violet-300',
  },
}

/**
 * Determine sponsor tier from block count
 */
export function getTierFromBlockCount(count: number): SponsorTier {
  if (count >= 50) return 'platinum'
  if (count >= 20) return 'gold'
  if (count >= 5) return 'silver'
  return 'bronze'
}

/**
 * Get benefits list for a sponsor tier
 */
export function getTierBenefits(tier: SponsorTier): string[] {
  const baseBenefits = [
    'Name on sponsor recognition wall',
    'Digital certificate of appreciation',
    'Tax-deductible donation receipt',
  ]

  const tierBenefits: Record<SponsorTier, string[]> = {
    bronze: baseBenefits,
    silver: [
      ...baseBenefits,
      'Featured on monthly newsletter',
      'Social media recognition',
    ],
    gold: [
      ...baseBenefits,
      'Featured on monthly newsletter',
      'Social media recognition',
      'Branded photo of cleaned blocks',
      'Annual impact report',
    ],
    platinum: [
      ...baseBenefits,
      'Featured on monthly newsletter',
      'Social media recognition',
      'Branded photo of cleaned blocks',
      'Annual impact report',
      'Permanent plaque in community center',
      'VIP invitation to annual celebration',
    ],
  }

  return tierBenefits[tier]
}

/**
 * Calculate amount in cents for a given block count
 */
export function calculateSponsorAmount(blockCount: number): number {
  const COST_PER_BLOCK = 10000 // $100 in cents
  return blockCount * COST_PER_BLOCK
}

/**
 * Get all tiers with their configurations
 */
export function getAllTiers(): Array<{ tier: SponsorTier; info: TierInfo }> {
  return (Object.keys(tierConfig) as SponsorTier[]).map((tier) => ({
    tier,
    info: tierConfig[tier],
  }))
}
