/**
 * GCC Sponsor Pricing Calculations
 *
 * This module provides all pricing logic for the sponsor calculator.
 * Core principle: 100% of base block price goes to workers.
 * Add-ons fund GCC operations, equipment, and creative services.
 */

import {
  BASE_BLOCK_PRICE,
  WORKER_PAYOUT_PER_BLOCK,
  volumeDiscounts,
  workerBrandingAddons,
  equipmentBrandingAddons,
  streetAdvertisingAddons,
  digitalPresenceAddons,
  enhancedCleaningServices,
  seasonalServices,
  greenServices,
  businessEngagementAddons,
  eventPackages,
  eventTypes,
  mediaPackages,
  videoPackages,
  enterpriseTiers,
  recognitionLevels,
  recurringDiscounts,
  multiWorkerOptions,
  extendedTimeOptions,
  type WorkerBrandingAddon,
  type EquipmentBrandingAddon,
  type StreetAdvertisingAddon,
  type DigitalPresenceAddon,
  type EnhancedCleaningService,
  type SeasonalService,
  type GreenService,
  type BusinessEngagementAddon,
  type EventPackage,
  type MediaPackage,
  type VideoPackage,
  type EnterpriseTier,
  type RecognitionLevel,
  type MultiWorkerOption,
  type ExtendedTimeOption,
} from './sponsor-pricing-data'

// Package configuration types
export interface SponsorPackageConfig {
  // Base
  blockCount: number
  isRecurring: boolean
  recurringFrequency?: 'weekly' | 'biweekly' | 'monthly' | 'quarterly' | 'annual'

  // Branding add-ons
  workerBranding: Partial<Record<WorkerBrandingAddon, boolean>>
  equipmentBranding: Partial<Record<EquipmentBrandingAddon, boolean>>
  streetAdvertising: Partial<Record<StreetAdvertisingAddon, boolean>>
  digitalPresence: Partial<Record<DigitalPresenceAddon, boolean>>

  // Service add-ons
  enhancedCleaning: Partial<Record<EnhancedCleaningService, boolean>>
  seasonalServices: Partial<Record<SeasonalService, boolean>>
  greenServices: Partial<Record<GreenService, boolean>>

  // Business engagement
  businessEngagement: Partial<Record<BusinessEngagementAddon, boolean>>

  // Events (array since multiple events can be selected)
  events: Array<{
    packageId: EventPackage
    eventTypeId?: string
    date?: string
    quantity?: number
  }>

  // Media
  photoPackage?: MediaPackage
  videoPackage?: VideoPackage
}

export interface PriceBreakdown {
  // Base pricing
  blockCount: number
  baseBlockPrice: number // Per block - always $100, 100% to worker
  baseTotal: number // blockCount × $100

  // Worker payout (ALWAYS $100/block - never discounted)
  workerPayoutTotal: number

  // Add-on discount (volume discounts ONLY apply to add-ons)
  addonDiscount: number // Percentage discount on add-ons
  addonDiscountAmount: number // Actual savings on add-ons

  // Add-ons
  workerBrandingTotal: number
  equipmentBrandingTotal: number
  streetAdvertisingTotal: number
  digitalPresenceTotal: number
  enhancedCleaningTotal: number
  seasonalServicesTotal: number
  greenServicesTotal: number
  businessEngagementTotal: number
  eventsTotal: number
  mediaTotal: number

  // Totals
  addonsTotal: number
  subtotal: number

  // Recurring discount (if applicable)
  recurringDiscount: number // Percentage
  recurringDiscountAmount: number

  // Final
  total: number
  gccOperationsTotal: number // Total minus worker payout

  // Recognition
  recognitionLevel: RecognitionLevel | null
  isEnterprise: boolean
  enterpriseTier: EnterpriseTier | null
}

/**
 * Get the volume discount tier for a given block count
 */
export function getVolumeDiscountTier(blockCount: number) {
  for (let i = volumeDiscounts.length - 1; i >= 0; i--) {
    const tier = volumeDiscounts[i]
    if (blockCount >= tier.minBlocks) {
      return tier
    }
  }
  return volumeDiscounts[0]
}

/**
 * Calculate base block pricing
 * IMPORTANT: Worker always receives $100/block - this is never discounted
 * Volume discounts only apply to add-on services
 */
export function calculateBasePrice(blockCount: number): {
  pricePerBlock: number
  workerPayoutPerBlock: number
  total: number
  workerPayoutTotal: number
  addonDiscount: number
} {
  const tier = getVolumeDiscountTier(blockCount)

  return {
    pricePerBlock: WORKER_PAYOUT_PER_BLOCK, // Always $100
    workerPayoutPerBlock: WORKER_PAYOUT_PER_BLOCK, // Always $100
    total: blockCount * WORKER_PAYOUT_PER_BLOCK,
    workerPayoutTotal: blockCount * WORKER_PAYOUT_PER_BLOCK, // 100% to workers
    addonDiscount: tier.addonDiscount, // Volume discount only for add-ons
  }
}

/**
 * Calculate add-on totals for a category
 */
function calculateCategoryAddons<T extends string>(
  addons: Record<T, { pricePerBlock?: number; pricePerLot?: number; pricePerPlot?: number; price?: number }>,
  selected: Partial<Record<T, boolean>>,
  blockCount: number
): number {
  let total = 0
  for (const [key, isSelected] of Object.entries(selected) as [T, boolean][]) {
    if (isSelected && addons[key]) {
      const addon = addons[key]
      if ('pricePerBlock' in addon && addon.pricePerBlock) {
        total += addon.pricePerBlock * blockCount
      } else if ('pricePerLot' in addon && addon.pricePerLot) {
        // Lot-based pricing (e.g., vacant lot maintenance)
        total += addon.pricePerLot * blockCount
      } else if ('pricePerPlot' in addon && addon.pricePerPlot) {
        // Plot-based pricing (e.g., community garden)
        total += addon.pricePerPlot * blockCount
      } else if ('price' in addon && addon.price) {
        total += addon.price
      }
    }
  }
  return total
}

/**
 * Calculate event totals
 */
function calculateEventsTotal(
  events: SponsorPackageConfig['events']
): number {
  let total = 0
  for (const event of events) {
    const pkg = eventPackages[event.packageId]
    if (pkg) {
      total += pkg.price * (event.quantity || 1)
    }
  }
  return total
}

/**
 * Calculate media totals
 */
function calculateMediaTotal(
  photoPackage?: MediaPackage,
  videoPackage?: VideoPackage
): number {
  let total = 0
  if (photoPackage && mediaPackages[photoPackage]) {
    total += mediaPackages[photoPackage].price
  }
  if (videoPackage && videoPackages[videoPackage]) {
    total += videoPackages[videoPackage].price
  }
  return total
}

/**
 * Get recognition level based on total amount
 */
export function getRecognitionLevel(totalAmount: number): RecognitionLevel | null {
  const levels = Object.entries(recognitionLevels) as [RecognitionLevel, typeof recognitionLevels[RecognitionLevel]][]

  // Sort by minAmount descending to find highest matching tier
  const sortedLevels = levels.sort((a, b) => b[1].minAmount - a[1].minAmount)

  for (const [key, level] of sortedLevels) {
    if (totalAmount >= level.minAmount) {
      if (level.maxAmount === null || totalAmount <= level.maxAmount) {
        return key
      }
    }
  }

  return null
}

/**
 * Get enterprise tier based on total amount
 */
export function getEnterpriseTier(totalAmount: number): EnterpriseTier | null {
  const tiers = Object.entries(enterpriseTiers) as [EnterpriseTier, typeof enterpriseTiers[EnterpriseTier]][]

  // Sort by minAmount descending
  const sortedTiers = tiers.sort((a, b) => b[1].minAmount - a[1].minAmount)

  for (const [key, tier] of sortedTiers) {
    if (totalAmount >= tier.minAmount) {
      if (tier.maxAmount === null || totalAmount <= tier.maxAmount) {
        return key
      }
    }
  }

  return null
}

/**
 * Calculate full price breakdown for a package
 *
 * IMPORTANT BUSINESS RULES:
 * 1. Workers ALWAYS receive $100/block - this is NEVER discounted
 * 2. Volume discounts only apply to add-on services (GCC's margin)
 * 3. Recurring discounts only apply to add-ons, not worker pay
 */
export function calculatePackagePrice(config: SponsorPackageConfig): PriceBreakdown {
  const { blockCount } = config

  // Base pricing - worker always gets $100/block
  const baseCalc = calculateBasePrice(blockCount)

  // Worker payout is ALWAYS $100/block - never discounted
  const workerPayoutTotal = baseCalc.workerPayoutTotal

  // Get volume discount for add-ons only
  const addonDiscount = baseCalc.addonDiscount

  // Calculate all add-on categories (before volume discount)
  const workerBrandingTotal = calculateCategoryAddons(
    workerBrandingAddons,
    config.workerBranding,
    blockCount
  )
  const equipmentBrandingTotal = calculateCategoryAddons(
    equipmentBrandingAddons,
    config.equipmentBranding,
    blockCount
  )
  const streetAdvertisingTotal = calculateCategoryAddons(
    streetAdvertisingAddons,
    config.streetAdvertising,
    blockCount
  )
  const digitalPresenceTotal = calculateCategoryAddons(
    digitalPresenceAddons,
    config.digitalPresence,
    blockCount
  )
  const enhancedCleaningTotal = calculateCategoryAddons(
    enhancedCleaningServices,
    config.enhancedCleaning,
    blockCount
  )
  const seasonalServicesTotal = calculateCategoryAddons(
    seasonalServices,
    config.seasonalServices,
    blockCount
  )
  const greenServicesTotal = calculateCategoryAddons(
    greenServices,
    config.greenServices,
    blockCount
  )
  const businessEngagementTotal = calculateCategoryAddons(
    businessEngagementAddons,
    config.businessEngagement,
    blockCount
  )

  // Events and media (one-time, not per-block)
  const eventsTotal = calculateEventsTotal(config.events)
  const mediaTotal = calculateMediaTotal(config.photoPackage, config.videoPackage)

  // Total add-ons before discount
  const addonsBeforeDiscount =
    workerBrandingTotal +
    equipmentBrandingTotal +
    streetAdvertisingTotal +
    digitalPresenceTotal +
    enhancedCleaningTotal +
    seasonalServicesTotal +
    greenServicesTotal +
    businessEngagementTotal

  // Apply volume discount to per-block add-ons only (not events/media)
  const addonDiscountAmount = Math.round(addonsBeforeDiscount * (addonDiscount / 100))
  const addonsAfterVolumeDiscount = addonsBeforeDiscount - addonDiscountAmount

  // Total add-ons includes discounted per-block + full price one-time items
  const addonsTotal = addonsAfterVolumeDiscount + eventsTotal + mediaTotal

  // Subtotal before recurring discount
  const subtotal = baseCalc.total + addonsTotal

  // Recurring discount (only applies to ADD-ONS, not worker pay)
  let recurringDiscount = 0
  let recurringDiscountAmount = 0
  if (config.isRecurring && config.recurringFrequency) {
    recurringDiscount = recurringDiscounts[config.recurringFrequency]
    // Apply recurring discount to per-block add-ons only (not base, not events/media)
    recurringDiscountAmount = Math.round(addonsAfterVolumeDiscount * (recurringDiscount / 100))
  }

  // Final total
  const total = subtotal - recurringDiscountAmount

  // GCC operations = everything except worker payout
  const gccOperationsTotal = total - workerPayoutTotal

  // Recognition and enterprise tiers
  const recognitionLevel = getRecognitionLevel(total)
  const enterpriseTier = getEnterpriseTier(total)
  const isEnterprise = total >= 2500000 // $25,000

  return {
    blockCount,
    baseBlockPrice: baseCalc.pricePerBlock,
    baseTotal: baseCalc.total,
    workerPayoutTotal,
    addonDiscount,
    addonDiscountAmount,
    workerBrandingTotal,
    equipmentBrandingTotal,
    streetAdvertisingTotal,
    digitalPresenceTotal,
    enhancedCleaningTotal,
    seasonalServicesTotal,
    greenServicesTotal,
    businessEngagementTotal,
    eventsTotal,
    mediaTotal,
    addonsTotal,
    subtotal,
    recurringDiscount,
    recurringDiscountAmount,
    total,
    gccOperationsTotal,
    recognitionLevel,
    isEnterprise,
    enterpriseTier,
  }
}

/**
 * Create an empty package configuration
 */
export function createEmptyPackageConfig(): SponsorPackageConfig {
  return {
    blockCount: 1,
    isRecurring: false,
    workerBranding: {},
    equipmentBranding: {},
    streetAdvertising: {},
    digitalPresence: {},
    enhancedCleaning: {},
    seasonalServices: {},
    greenServices: {},
    businessEngagement: {},
    events: [],
  }
}

/**
 * Format price in cents to display string
 */
export function formatPrice(cents: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  }).format(cents / 100)
}

/**
 * Check if a package requires a custom quote (over $10k)
 */
export function requiresCustomQuote(totalCents: number): boolean {
  return totalCents >= 1000000 // $10,000
}

/**
 * Get suggested add-ons based on block count
 */
export function getSuggestedAddons(blockCount: number): {
  popular: string[]
  recommended: string[]
} {
  if (blockCount >= 50) {
    return {
      popular: ['fullKit', 'blockSigns', 'socialTag'],
      recommended: ['standardPhoto', 'thankYouVideo'],
    }
  } else if (blockCount >= 10) {
    return {
      popular: ['vests', 'socialTag', 'logoOnPhotos'],
      recommended: ['basicPhoto'],
    }
  } else {
    return {
      popular: ['vests', 'socialTag'],
      recommended: [],
    }
  }
}

/**
 * Calculate savings from volume discount
 */
export function calculateSavings(blockCount: number): {
  currentTier: typeof volumeDiscounts[number]
  nextTier: typeof volumeDiscounts[number] | null
  blocksToNextTier: number
  savingsAtNextTier: number
} {
  const currentTier = getVolumeDiscountTier(blockCount)
  const currentIndex = volumeDiscounts.indexOf(currentTier)
  const nextTier = currentIndex < volumeDiscounts.length - 1 ? volumeDiscounts[currentIndex + 1] : null

  let blocksToNextTier = 0
  let savingsAtNextTier = 0

  if (nextTier) {
    blocksToNextTier = nextTier.minBlocks - blockCount
    const currentTotal = blockCount * currentTier.pricePerBlock
    const nextTotal = blockCount * nextTier.pricePerBlock
    savingsAtNextTier = currentTotal - nextTotal
  }

  return {
    currentTier,
    nextTier,
    blocksToNextTier,
    savingsAtNextTier,
  }
}

// Re-export data for component usage
export {
  BASE_BLOCK_PRICE,
  WORKER_PAYOUT_PER_BLOCK,
  volumeDiscounts,
  multiWorkerOptions,
  extendedTimeOptions,
  workerBrandingAddons,
  equipmentBrandingAddons,
  streetAdvertisingAddons,
  digitalPresenceAddons,
  enhancedCleaningServices,
  seasonalServices,
  greenServices,
  businessEngagementAddons,
  eventPackages,
  eventTypes,
  mediaPackages,
  videoPackages,
  enterpriseTiers,
  recognitionLevels,
  recurringDiscounts,
}

// Re-export types for component usage
export type {
  WorkerBrandingAddon,
  EquipmentBrandingAddon,
  StreetAdvertisingAddon,
  DigitalPresenceAddon,
  EnhancedCleaningService,
  SeasonalService,
  GreenService,
  BusinessEngagementAddon,
  EventPackage,
  MediaPackage,
  VideoPackage,
  EnterpriseTier,
  RecognitionLevel,
  MultiWorkerOption,
  ExtendedTimeOption,
}
