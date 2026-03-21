/**
 * Auto-generate route and block codes from area name and existing codes.
 *
 * Convention:
 *   Route code: PREFIX-R{SIDE}{NUMBER}  e.g. NL-RN01 (North Lawndale, Route North #1)
 *   Block code:  PREFIX-{SIDE}{NUMBER}   e.g. NL-N01
 *
 * Side: N = north, S = south (relative to area midpoint)
 */

/** Derive a 2-letter area prefix from the display name */
export function deriveAreaPrefix(areaName: string): string {
  const words = areaName.trim().split(/\s+/)
  if (words.length === 1) {
    // Single word: first two letters uppercase
    return words[0].substring(0, 2).toUpperCase()
  }
  // Multiple words: first letter of each (up to 3)
  return words
    .slice(0, 3)
    .map((w) => w[0])
    .join('')
    .toUpperCase()
    .substring(0, 2)
}

/**
 * Find the next available sequence number for a given prefix and side.
 *
 * @param existingCodes All existing route OR block codes (e.g. ['NL-RN01', 'NL-RN02', ...])
 * @param prefix Area prefix (e.g. 'NL')
 * @param side 'north' or 'south'
 * @returns { routeCode, blockCode, sequenceNumber }
 */
export function generateNextCode(
  existingCodes: string[],
  prefix: string,
  side: 'north' | 'south'
): { routeCode: string; blockCode: string; sequenceNumber: number } {
  const sideChar = side === 'north' ? 'N' : 'S'

  // Match patterns like NL-RN01 or NL-RS02 (route codes) and NL-N01 or NL-S02 (block codes)
  const routePattern = new RegExp(`^${prefix}-R${sideChar}(\\d+)$`, 'i')
  const blockPattern = new RegExp(`^${prefix}-${sideChar}(\\d+)$`, 'i')

  let maxNum = 0
  for (const code of existingCodes) {
    const routeMatch = code.match(routePattern)
    if (routeMatch) {
      const n = parseInt(routeMatch[1], 10)
      if (n > maxNum) maxNum = n
      continue
    }
    const blockMatch = code.match(blockPattern)
    if (blockMatch) {
      const n = parseInt(blockMatch[1], 10)
      if (n > maxNum) maxNum = n
    }
  }

  const next = maxNum + 1
  const padded = String(next).padStart(2, '0')

  return {
    routeCode: `${prefix}-R${sideChar}${padded}`,
    blockCode: `${prefix}-${sideChar}${padded}`,
    sequenceNumber: next,
  }
}

/**
 * Generate a batch of sequential codes for multiple routes on the same side.
 *
 * @param count How many code pairs to generate
 * @param existingCodes All existing codes
 * @param prefix Area prefix
 * @param side 'north' or 'south'
 */
export function generateCodeBatch(
  count: number,
  existingCodes: string[],
  prefix: string,
  side: 'north' | 'south'
): { routeCode: string; blockCode: string; sequenceNumber: number }[] {
  const results: { routeCode: string; blockCode: string; sequenceNumber: number }[] = []
  // Start from current max
  const allCodes = [...existingCodes]
  for (let i = 0; i < count; i++) {
    const next = generateNextCode(allCodes, prefix, side)
    results.push(next)
    allCodes.push(next.routeCode, next.blockCode)
  }
  return results
}
