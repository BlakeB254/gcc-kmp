/**
 * Movement Rankings Utility
 *
 * Pure utility functions for the GCC movement ranking system.
 * These don't depend on any database - the actual DB queries are in API routes.
 */

export interface MovementRankings {
  global: number
  country: number
  state: number
  city: number
}

export interface LocationParams {
  countryCode: string
  stateCode: string
  city: string
}

/**
 * Determine early adopter tier based on global rank
 */
export function getEarlyAdopterTier(
  globalRank: number
): 'founder' | 'pioneer' | 'early' | 'standard' {
  if (globalRank <= 100) return 'founder'
  if (globalRank <= 1000) return 'pioneer'
  if (globalRank <= 10000) return 'early'
  return 'standard'
}

/**
 * Generate a unique, URL-safe referral code
 * Format: 6 alphanumeric characters (e.g., "ABC123")
 */
export function generateReferralCode(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789' // Removed ambiguous characters
  let code = ''
  for (let i = 0; i < 6; i++) {
    code += chars.charAt(Math.floor(Math.random() * chars.length))
  }
  return code
}

/**
 * Get tier display info
 */
export function getTierInfo(tier: string): {
  emoji: string
  label: string
  description: string
} {
  switch (tier) {
    case 'founder':
      return {
        emoji: '👑',
        label: 'Founder',
        description: 'One of the first 100 to join the movement',
      }
    case 'pioneer':
      return {
        emoji: '🚀',
        label: 'Pioneer',
        description: 'One of the first 1,000 to join the movement',
      }
    case 'early':
      return {
        emoji: '⭐',
        label: 'Early Adopter',
        description: 'One of the first 10,000 to join the movement',
      }
    default:
      return {
        emoji: '🌱',
        label: 'Supporter',
        description: 'A valued member of the GCC movement',
      }
  }
}

/**
 * Tier thresholds
 */
export const TIER_THRESHOLDS = {
  founder: 100,
  pioneer: 1000,
  early: 10000,
}
