/**
 * North Lawndale Block Data — Roosevelt Road Corridor
 *
 * Fallback GeoJSON data matching the 10 production blocks in the database.
 * The website fetches from /api/blocks first; this data is only used when
 * the API is unreachable.
 *
 * Block naming:
 *   NL-N01…N05 = North side of Roosevelt Road
 *   NL-S01…S05 = South side of Roosevelt Road
 *
 * North blocks (west → east): Pulaski → Harding → Springfield → Avers → Hamlin … Ridgeway → Lawndale
 * South blocks (west → east): Hamlin → Ridgeway … Lawndale → Millard → Central Park → Drake → St Louis
 */

export interface BlockCheckpoint {
  name: string
  type: 'corner_start' | 'midpoint' | 'alley' | 'corner_end'
  description: string
  coordinates: [number, number] // [longitude, latitude]
  radius: number // meters for GPS verification
}

export interface BlockDefinition {
  code: string
  streetName: string
  fromCorner: string
  toCorner: string
  side: 'north' | 'south'
  includesAlley: boolean
  geo: {
    center: {
      type: 'Point'
      coordinates: [number, number]
    }
    polygon: {
      type: 'Polygon'
      coordinates: [number, number][][]
    }
  }
  checkpoints: BlockCheckpoint[]
}

// Roosevelt Road latitude (average of production block centers)
const ROOSEVELT_LAT = 41.85984

// Block depth from curb to alley (~300 feet = ~91 meters ≈ 0.00082°)
const BLOCK_DEPTH = 0.00082

/**
 * Street boundaries derived from production database block center midpoints.
 * Each pair [west, east] defines the cross-street edges of one block.
 */
const BLOCK_EDGES = {
  // North side blocks (west → east)
  'NL-N01': { west: -87.72514, east: -87.72422, from: 'S Pulaski Rd',     to: 'S Harding Ave' },
  'NL-N02': { west: -87.72422, east: -87.72315, from: 'S Harding Ave',    to: 'S Springfield Ave' },
  'NL-N05': { west: -87.72315, east: -87.72192, from: 'S Springfield Ave', to: 'S Avers Ave' },
  'NL-N04': { west: -87.72192, east: -87.72068, from: 'S Avers Ave',      to: 'S Hamlin Ave' },
  'NL-N03': { west: -87.71953, east: -87.71829, from: 'S Ridgeway Ave',   to: 'S Lawndale Ave' },
  // South side blocks (west → east)
  'NL-S05': { west: -87.72072, east: -87.71948, from: 'S Hamlin Ave',      to: 'S Ridgeway Ave' },
  'NL-S04': { west: -87.71830, east: -87.71708, from: 'S Lawndale Ave',    to: 'S Millard Ave' },
  'NL-S03': { west: -87.71708, east: -87.71585, from: 'S Millard Ave',     to: 'S Central Park Ave' },
  'NL-S02': { west: -87.71585, east: -87.71462, from: 'S Central Park Ave', to: 'S Drake Ave' },
  'NL-S01': { west: -87.71462, east: -87.71340, from: 'S Drake Ave',       to: 'S St Louis Ave' },
} as const

/** Build a properly-oriented east-west polygon for one side of Roosevelt Road */
function createBlockPolygon(
  westLng: number,
  eastLng: number,
  side: 'north' | 'south'
): [number, number][][] {
  const roadLat = ROOSEVELT_LAT
  const alleyLat = side === 'north'
    ? roadLat + BLOCK_DEPTH
    : roadLat - BLOCK_DEPTH

  if (side === 'north') {
    return [[
      [eastLng, roadLat],   // SE corner
      [eastLng, alleyLat],  // NE corner
      [westLng, alleyLat],  // NW corner
      [westLng, roadLat],   // SW corner
      [eastLng, roadLat],   // close
    ]]
  } else {
    return [[
      [eastLng, roadLat],   // NE corner
      [westLng, roadLat],   // NW corner
      [westLng, alleyLat],  // SW corner
      [eastLng, alleyLat],  // SE corner
      [eastLng, roadLat],   // close
    ]]
  }
}

/** Build checkpoints (4 waypoints) for a block */
function createCheckpoints(
  westLng: number,
  eastLng: number,
  westStreet: string,
  eastStreet: string,
  side: 'north' | 'south'
): BlockCheckpoint[] {
  const roadLat = ROOSEVELT_LAT
  const alleyLat = side === 'north'
    ? roadLat + BLOCK_DEPTH
    : roadLat - BLOCK_DEPTH
  const midLng = (westLng + eastLng) / 2
  const midLat = (roadLat + alleyLat) / 2

  return [
    {
      name: `${westStreet} Corner`,
      type: 'corner_start',
      description: `Start at the ${side} side of Roosevelt & ${westStreet}`,
      coordinates: [westLng, roadLat],
      radius: 15,
    },
    {
      name: 'Sidewalk Midpoint',
      type: 'midpoint',
      description: 'Middle of the block along Roosevelt Road',
      coordinates: [midLng, roadLat],
      radius: 20,
    },
    {
      name: 'Alley',
      type: 'alley',
      description: `Alley behind the ${side} side buildings`,
      coordinates: [midLng, alleyLat],
      radius: 20,
    },
    {
      name: `${eastStreet} Corner`,
      type: 'corner_end',
      description: `End at the ${side} side of Roosevelt & ${eastStreet}`,
      coordinates: [eastLng, roadLat],
      radius: 15,
    },
  ]
}

/**
 * The 10 launch blocks matching the production database.
 *
 * 5 blocks on the NORTH side of Roosevelt Road
 * 5 blocks on the SOUTH side of Roosevelt Road
 */
export const NORTH_LAWNDALE_BLOCKS: BlockDefinition[] = [
  // ===== NORTH SIDE =====
  {
    code: 'NL-N01',
    streetName: 'W Roosevelt Rd (North Side)',
    fromCorner: 'S Pulaski Rd',
    toCorner: 'S Harding Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [(BLOCK_EDGES['NL-N01'].west + BLOCK_EDGES['NL-N01'].east) / 2, ROOSEVELT_LAT + BLOCK_DEPTH / 2] },
      polygon: { type: 'Polygon', coordinates: createBlockPolygon(BLOCK_EDGES['NL-N01'].west, BLOCK_EDGES['NL-N01'].east, 'north') },
    },
    checkpoints: createCheckpoints(BLOCK_EDGES['NL-N01'].west, BLOCK_EDGES['NL-N01'].east, 'Pulaski', 'Harding', 'north'),
  },
  {
    code: 'NL-N02',
    streetName: 'W Roosevelt Rd (North Side)',
    fromCorner: 'S Harding Ave',
    toCorner: 'S Springfield Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [(BLOCK_EDGES['NL-N02'].west + BLOCK_EDGES['NL-N02'].east) / 2, ROOSEVELT_LAT + BLOCK_DEPTH / 2] },
      polygon: { type: 'Polygon', coordinates: createBlockPolygon(BLOCK_EDGES['NL-N02'].west, BLOCK_EDGES['NL-N02'].east, 'north') },
    },
    checkpoints: createCheckpoints(BLOCK_EDGES['NL-N02'].west, BLOCK_EDGES['NL-N02'].east, 'Harding', 'Springfield', 'north'),
  },
  {
    code: 'NL-N03',
    streetName: 'W Roosevelt Rd (North Side)',
    fromCorner: 'S Ridgeway Ave',
    toCorner: 'S Lawndale Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [(BLOCK_EDGES['NL-N03'].west + BLOCK_EDGES['NL-N03'].east) / 2, ROOSEVELT_LAT + BLOCK_DEPTH / 2] },
      polygon: { type: 'Polygon', coordinates: createBlockPolygon(BLOCK_EDGES['NL-N03'].west, BLOCK_EDGES['NL-N03'].east, 'north') },
    },
    checkpoints: createCheckpoints(BLOCK_EDGES['NL-N03'].west, BLOCK_EDGES['NL-N03'].east, 'Ridgeway', 'Lawndale', 'north'),
  },
  {
    code: 'NL-N04',
    streetName: 'W Roosevelt Rd (North Side)',
    fromCorner: 'S Avers Ave',
    toCorner: 'S Hamlin Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [(BLOCK_EDGES['NL-N04'].west + BLOCK_EDGES['NL-N04'].east) / 2, ROOSEVELT_LAT + BLOCK_DEPTH / 2] },
      polygon: { type: 'Polygon', coordinates: createBlockPolygon(BLOCK_EDGES['NL-N04'].west, BLOCK_EDGES['NL-N04'].east, 'north') },
    },
    checkpoints: createCheckpoints(BLOCK_EDGES['NL-N04'].west, BLOCK_EDGES['NL-N04'].east, 'Avers', 'Hamlin', 'north'),
  },
  {
    code: 'NL-N05',
    streetName: 'W Roosevelt Rd (North Side)',
    fromCorner: 'S Springfield Ave',
    toCorner: 'S Avers Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [(BLOCK_EDGES['NL-N05'].west + BLOCK_EDGES['NL-N05'].east) / 2, ROOSEVELT_LAT + BLOCK_DEPTH / 2] },
      polygon: { type: 'Polygon', coordinates: createBlockPolygon(BLOCK_EDGES['NL-N05'].west, BLOCK_EDGES['NL-N05'].east, 'north') },
    },
    checkpoints: createCheckpoints(BLOCK_EDGES['NL-N05'].west, BLOCK_EDGES['NL-N05'].east, 'Springfield', 'Avers', 'north'),
  },

  // ===== SOUTH SIDE =====
  {
    code: 'NL-S01',
    streetName: 'W Roosevelt Rd (South Side)',
    fromCorner: 'S Drake Ave',
    toCorner: 'S St Louis Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [(BLOCK_EDGES['NL-S01'].west + BLOCK_EDGES['NL-S01'].east) / 2, ROOSEVELT_LAT - BLOCK_DEPTH / 2] },
      polygon: { type: 'Polygon', coordinates: createBlockPolygon(BLOCK_EDGES['NL-S01'].west, BLOCK_EDGES['NL-S01'].east, 'south') },
    },
    checkpoints: createCheckpoints(BLOCK_EDGES['NL-S01'].west, BLOCK_EDGES['NL-S01'].east, 'Drake', 'St Louis', 'south'),
  },
  {
    code: 'NL-S02',
    streetName: 'W Roosevelt Rd (South Side)',
    fromCorner: 'S Central Park Ave',
    toCorner: 'S Drake Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [(BLOCK_EDGES['NL-S02'].west + BLOCK_EDGES['NL-S02'].east) / 2, ROOSEVELT_LAT - BLOCK_DEPTH / 2] },
      polygon: { type: 'Polygon', coordinates: createBlockPolygon(BLOCK_EDGES['NL-S02'].west, BLOCK_EDGES['NL-S02'].east, 'south') },
    },
    checkpoints: createCheckpoints(BLOCK_EDGES['NL-S02'].west, BLOCK_EDGES['NL-S02'].east, 'Central Park', 'Drake', 'south'),
  },
  {
    code: 'NL-S03',
    streetName: 'W Roosevelt Rd (South Side)',
    fromCorner: 'S Millard Ave',
    toCorner: 'S Central Park Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [(BLOCK_EDGES['NL-S03'].west + BLOCK_EDGES['NL-S03'].east) / 2, ROOSEVELT_LAT - BLOCK_DEPTH / 2] },
      polygon: { type: 'Polygon', coordinates: createBlockPolygon(BLOCK_EDGES['NL-S03'].west, BLOCK_EDGES['NL-S03'].east, 'south') },
    },
    checkpoints: createCheckpoints(BLOCK_EDGES['NL-S03'].west, BLOCK_EDGES['NL-S03'].east, 'Millard', 'Central Park', 'south'),
  },
  {
    code: 'NL-S04',
    streetName: 'W Roosevelt Rd (South Side)',
    fromCorner: 'S Lawndale Ave',
    toCorner: 'S Millard Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [(BLOCK_EDGES['NL-S04'].west + BLOCK_EDGES['NL-S04'].east) / 2, ROOSEVELT_LAT - BLOCK_DEPTH / 2] },
      polygon: { type: 'Polygon', coordinates: createBlockPolygon(BLOCK_EDGES['NL-S04'].west, BLOCK_EDGES['NL-S04'].east, 'south') },
    },
    checkpoints: createCheckpoints(BLOCK_EDGES['NL-S04'].west, BLOCK_EDGES['NL-S04'].east, 'Lawndale', 'Millard', 'south'),
  },
  {
    code: 'NL-S05',
    streetName: 'W Roosevelt Rd (South Side)',
    fromCorner: 'S Hamlin Ave',
    toCorner: 'S Ridgeway Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [(BLOCK_EDGES['NL-S05'].west + BLOCK_EDGES['NL-S05'].east) / 2, ROOSEVELT_LAT - BLOCK_DEPTH / 2] },
      polygon: { type: 'Polygon', coordinates: createBlockPolygon(BLOCK_EDGES['NL-S05'].west, BLOCK_EDGES['NL-S05'].east, 'south') },
    },
    checkpoints: createCheckpoints(BLOCK_EDGES['NL-S05'].west, BLOCK_EDGES['NL-S05'].east, 'Hamlin', 'Ridgeway', 'south'),
  },
]

/**
 * Map center for the Roosevelt Road corridor
 * Centered across all 10 blocks (Pulaski to St Louis)
 */
export const MAP_CENTER = {
  longitude: -87.71927,  // Midpoint of all blocks
  latitude: 41.85984,    // Roosevelt Road
  zoom: 15.5,
}

/**
 * Map bounds encompassing all 10 blocks with padding
 */
export const MAP_BOUNDS = {
  north: 41.8620,
  south: 41.8575,
  east: -87.7120,
  west: -87.7265,
}

/**
 * Convert block data to GeoJSON FeatureCollection for MapLibre GL
 */
export function blocksToGeoJSON(
  blocks: Array<BlockDefinition & { id: string; status: string }>
) {
  return {
    type: 'FeatureCollection' as const,
    features: blocks.map((block) => ({
      type: 'Feature' as const,
      id: block.id,
      properties: {
        id: block.id,
        code: block.code,
        streetName: block.streetName,
        fromCorner: block.fromCorner,
        toCorner: block.toCorner,
        side: block.side,
        status: block.status,
      },
      geometry: block.geo.polygon,
    })),
  }
}

/**
 * Convert checkpoints to GeoJSON for display
 */
export function checkpointsToGeoJSON(
  checkpoints: BlockCheckpoint[]
) {
  return {
    type: 'FeatureCollection' as const,
    features: checkpoints.map((cp, index) => ({
      type: 'Feature' as const,
      id: `checkpoint-${index}`,
      properties: {
        name: cp.name,
        type: cp.type,
        description: cp.description,
        radius: cp.radius,
        order: index + 1,
      },
      geometry: {
        type: 'Point' as const,
        coordinates: cp.coordinates,
      },
    })),
  }
}
