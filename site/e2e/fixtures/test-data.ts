/**
 * Test Data Fixtures for GCC E2E Tests
 *
 * Contains mock data for various test scenarios.
 * All email addresses use @example.com (RFC 2606 reserved domain).
 */

export const testUsers = {
  waitlistUser: {
    name: 'Jane Doe',
    email: 'jane.doe@example.com',
    zipCode: '60624', // North Lawndale
    phone: '312-555-0101',
  },
  waitlistUserOutsideArea: {
    name: 'John Smith',
    email: 'john.smith@example.com',
    zipCode: '90210', // Beverly Hills (outside service area)
    phone: '310-555-0102',
  },
  worker: {
    firstName: 'Marcus',
    lastName: 'Johnson',
    email: 'marcus.johnson@example.com',
    phone: '312-555-0201',
    address: '4500 W Madison St, Chicago, IL 60624',
  },
  sponsor: {
    name: 'Sarah Williams',
    email: 'sarah.williams@example.com',
    company: 'Williams Foundation',
    message: 'Thank you for keeping our community clean!',
  },
}

export const validZipCodes = [
  '60623', // North Lawndale
  '60624', // North Lawndale
  '60644', // Austin
]

export const invalidZipCodes = [
  '90210', // Beverly Hills
  '10001', // New York
  '00000', // Invalid
]

export const testBlocks = [
  {
    code: 'NL-001',
    streetName: 'W Roosevelt Rd',
    fromCorner: 'Pulaski',
    toCorner: 'Kedzie',
    status: 'unfunded',
  },
  {
    code: 'NL-002',
    streetName: 'W 16th St',
    fromCorner: 'Kostner',
    toCorner: 'Kildare',
    status: 'unfunded',
  },
]

export const membershipTiers = {
  founder: { min: 1, max: 100, label: 'Founder' },
  pioneer: { min: 101, max: 1000, label: 'Pioneer' },
  early: { min: 1001, max: 10000, label: 'Early Adopter' },
  standard: { min: 10001, max: Infinity, label: 'Member' },
}

export function getTierForRank(rank: number): string {
  if (rank <= 100) return 'founder'
  if (rank <= 1000) return 'pioneer'
  if (rank <= 10000) return 'early'
  return 'standard'
}

/**
 * Generate a random test email to avoid conflicts
 */
export function generateTestEmail(prefix: string = 'test'): string {
  const timestamp = Date.now()
  const random = Math.random().toString(36).substring(7)
  return `${prefix}-${timestamp}-${random}@example.com`
}

/**
 * Generate random valid phone number
 */
export function generateTestPhone(): string {
  const area = '312'
  const exchange = Math.floor(Math.random() * 900) + 100
  const subscriber = Math.floor(Math.random() * 9000) + 1000
  return `${area}-${exchange}-${subscriber}`
}
