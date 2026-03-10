export interface SponsorBlock {
  id: string
  code: string
  streetName: string
  fromCorner: string
  toCorner: string
  status: 'unfunded' | 'available' | 'claimed' | 'in_progress' | 'pending_review' | 'completed'
  location?: {
    neighborhood: string | null
    boundary: { type: 'Polygon'; coordinates: [number, number][][] } | null
    center: { type: 'Point'; coordinates: [number, number] } | null
  }
  geo?: {
    center: { coordinates: [number, number] }
    polygon?: { coordinates: [number, number][][] }
  }
}

export type SponsorshipMode = 'specific' | 'general'

export interface SponsorFormData {
  name: string
  email: string
  company: string
  isAnonymous: boolean
  message: string
}

export const GENERAL_BLOCK_COUNTS = [1, 3, 5, 10] as const
export const PRICE_PER_BLOCK = 100

/** Format street name to include 1500 block number for Roosevelt Rd */
export function formatStreetName(streetName: string | undefined | null): string {
  if (!streetName) return ''
  if (streetName.includes('1500') || !streetName.toLowerCase().includes('roosevelt')) {
    return streetName
  }
  return streetName.replace(/^W\s+Roosevelt/i, '1500 W Roosevelt')
}
