/**
 * North Lawndale Block Data - Roosevelt Road Corridor
 *
 * Real GeoJSON polygon data for the 10 launch blocks along Roosevelt Road
 * from Kedzie Avenue to Lawndale Avenue in North Lawndale, Chicago.
 *
 * Blocks are defined on both the NORTH and SOUTH sides of Roosevelt Road,
 * covering the storefronts and sidewalks on each side.
 *
 * Coordinate Reference (verified via Google Maps):
 * - Roosevelt Road runs east-west at approximately latitude 41.8603
 * - Kedzie Ave (3200 W) is at longitude -87.7055
 * - Lawndale Ave (3700 W) is at longitude -87.7180
 *
 * Block depth (north-south) is approximately 300 feet from curb to alley.
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
      coordinates: [number, number] // [longitude, latitude]
    }
    polygon: {
      type: 'Polygon'
      coordinates: [number, number][][] // GeoJSON polygon format
    }
  }
  checkpoints: BlockCheckpoint[]
}

// Roosevelt Road latitude at Kedzie (verified via Google Maps)
// Note: Roosevelt Road is at 1200 South in Chicago's grid system
const ROOSEVELT_LAT = 41.8603

// Block depth from curb to alley (in degrees, ~300 feet = ~91 meters)
// 1 degree latitude = ~111,000 meters, so 91m = ~0.00082 degrees
const BLOCK_DEPTH = 0.00082

// Street longitudes (west to east, based on Chicago grid: 8 blocks = 1 mile)
// Each 100 address numbers = ~0.0025° longitude
// Kedzie (3200 W) verified at -87.7055
const STREETS = {
  kedzie: -87.7055,      // 3200 W - verified
  spaulding: -87.7080,   // 3300 W
  homan: -87.7105,       // 3400 W
  stLouis: -87.7130,     // 3500 W
  centralPark: -87.7155, // 3600 W
  lawndale: -87.7180,    // 3700 W
} as const

/**
 * Generate a block polygon for the north or south side of Roosevelt Road
 */
function createBlockPolygon(
  westLng: number,
  eastLng: number,
  side: 'north' | 'south'
): [number, number][][] {
  const roadLat = ROOSEVELT_LAT
  const alleyLat = side === 'north'
    ? roadLat + BLOCK_DEPTH
    : roadLat - BLOCK_DEPTH

  // GeoJSON polygon: array of linear rings, first ring is exterior
  // Coordinates are [longitude, latitude] and must be closed (first = last)
  if (side === 'north') {
    return [[
      [eastLng, roadLat],      // SE corner (road & east street)
      [eastLng, alleyLat],     // NE corner (alley & east street)
      [westLng, alleyLat],     // NW corner (alley & west street)
      [westLng, roadLat],      // SW corner (road & west street)
      [eastLng, roadLat],      // Close the polygon
    ]]
  } else {
    return [[
      [eastLng, roadLat],      // NE corner (road & east street)
      [westLng, roadLat],      // NW corner (road & west street)
      [westLng, alleyLat],     // SW corner (alley & west street)
      [eastLng, alleyLat],     // SE corner (alley & east street)
      [eastLng, roadLat],      // Close the polygon
    ]]
  }
}

/**
 * Generate checkpoints for a block
 */
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
      description: `Middle of the block along Roosevelt Road`,
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
 * The 10 launch blocks for GCC
 *
 * 5 blocks on the NORTH side of Roosevelt Road (storefronts facing south)
 * 5 blocks on the SOUTH side of Roosevelt Road (storefronts facing north)
 */
export const NORTH_LAWNDALE_BLOCKS: BlockDefinition[] = [
  // ===== NORTH SIDE OF ROOSEVELT ROAD =====
  {
    code: 'NL-N01',
    streetName: '1500 W Roosevelt Rd (North Side)',
    fromCorner: 'S Kedzie Ave',
    toCorner: 'S Spaulding Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: {
        type: 'Point',
        coordinates: [(STREETS.kedzie + STREETS.spaulding) / 2, ROOSEVELT_LAT + BLOCK_DEPTH / 2],
      },
      polygon: {
        type: 'Polygon',
        coordinates: createBlockPolygon(STREETS.spaulding, STREETS.kedzie, 'north'),
      },
    },
    checkpoints: createCheckpoints(
      STREETS.spaulding, STREETS.kedzie,
      'Spaulding', 'Kedzie', 'north'
    ),
  },
  {
    code: 'NL-N02',
    streetName: '1500 W Roosevelt Rd (North Side)',
    fromCorner: 'S Spaulding Ave',
    toCorner: 'S Homan Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: {
        type: 'Point',
        coordinates: [(STREETS.spaulding + STREETS.homan) / 2, ROOSEVELT_LAT + BLOCK_DEPTH / 2],
      },
      polygon: {
        type: 'Polygon',
        coordinates: createBlockPolygon(STREETS.homan, STREETS.spaulding, 'north'),
      },
    },
    checkpoints: createCheckpoints(
      STREETS.homan, STREETS.spaulding,
      'Homan', 'Spaulding', 'north'
    ),
  },
  {
    code: 'NL-N03',
    streetName: '1500 W Roosevelt Rd (North Side)',
    fromCorner: 'S Homan Ave',
    toCorner: 'S St Louis Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: {
        type: 'Point',
        coordinates: [(STREETS.homan + STREETS.stLouis) / 2, ROOSEVELT_LAT + BLOCK_DEPTH / 2],
      },
      polygon: {
        type: 'Polygon',
        coordinates: createBlockPolygon(STREETS.stLouis, STREETS.homan, 'north'),
      },
    },
    checkpoints: createCheckpoints(
      STREETS.stLouis, STREETS.homan,
      'St Louis', 'Homan', 'north'
    ),
  },
  {
    code: 'NL-N04',
    streetName: '1500 W Roosevelt Rd (North Side)',
    fromCorner: 'S St Louis Ave',
    toCorner: 'S Central Park Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: {
        type: 'Point',
        coordinates: [(STREETS.stLouis + STREETS.centralPark) / 2, ROOSEVELT_LAT + BLOCK_DEPTH / 2],
      },
      polygon: {
        type: 'Polygon',
        coordinates: createBlockPolygon(STREETS.centralPark, STREETS.stLouis, 'north'),
      },
    },
    checkpoints: createCheckpoints(
      STREETS.centralPark, STREETS.stLouis,
      'Central Park', 'St Louis', 'north'
    ),
  },
  {
    code: 'NL-N05',
    streetName: '1500 W Roosevelt Rd (North Side)',
    fromCorner: 'S Central Park Ave',
    toCorner: 'S Lawndale Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: {
        type: 'Point',
        coordinates: [(STREETS.centralPark + STREETS.lawndale) / 2, ROOSEVELT_LAT + BLOCK_DEPTH / 2],
      },
      polygon: {
        type: 'Polygon',
        coordinates: createBlockPolygon(STREETS.lawndale, STREETS.centralPark, 'north'),
      },
    },
    checkpoints: createCheckpoints(
      STREETS.lawndale, STREETS.centralPark,
      'Lawndale', 'Central Park', 'north'
    ),
  },

  // ===== SOUTH SIDE OF ROOSEVELT ROAD =====
  {
    code: 'NL-S01',
    streetName: '1500 W Roosevelt Rd (South Side)',
    fromCorner: 'S Kedzie Ave',
    toCorner: 'S Spaulding Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: {
        type: 'Point',
        coordinates: [(STREETS.kedzie + STREETS.spaulding) / 2, ROOSEVELT_LAT - BLOCK_DEPTH / 2],
      },
      polygon: {
        type: 'Polygon',
        coordinates: createBlockPolygon(STREETS.spaulding, STREETS.kedzie, 'south'),
      },
    },
    checkpoints: createCheckpoints(
      STREETS.spaulding, STREETS.kedzie,
      'Spaulding', 'Kedzie', 'south'
    ),
  },
  {
    code: 'NL-S02',
    streetName: '1500 W Roosevelt Rd (South Side)',
    fromCorner: 'S Spaulding Ave',
    toCorner: 'S Homan Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: {
        type: 'Point',
        coordinates: [(STREETS.spaulding + STREETS.homan) / 2, ROOSEVELT_LAT - BLOCK_DEPTH / 2],
      },
      polygon: {
        type: 'Polygon',
        coordinates: createBlockPolygon(STREETS.homan, STREETS.spaulding, 'south'),
      },
    },
    checkpoints: createCheckpoints(
      STREETS.homan, STREETS.spaulding,
      'Homan', 'Spaulding', 'south'
    ),
  },
  {
    code: 'NL-S03',
    streetName: '1500 W Roosevelt Rd (South Side)',
    fromCorner: 'S Homan Ave',
    toCorner: 'S St Louis Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: {
        type: 'Point',
        coordinates: [(STREETS.homan + STREETS.stLouis) / 2, ROOSEVELT_LAT - BLOCK_DEPTH / 2],
      },
      polygon: {
        type: 'Polygon',
        coordinates: createBlockPolygon(STREETS.stLouis, STREETS.homan, 'south'),
      },
    },
    checkpoints: createCheckpoints(
      STREETS.stLouis, STREETS.homan,
      'St Louis', 'Homan', 'south'
    ),
  },
  {
    code: 'NL-S04',
    streetName: '1500 W Roosevelt Rd (South Side)',
    fromCorner: 'S St Louis Ave',
    toCorner: 'S Central Park Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: {
        type: 'Point',
        coordinates: [(STREETS.stLouis + STREETS.centralPark) / 2, ROOSEVELT_LAT - BLOCK_DEPTH / 2],
      },
      polygon: {
        type: 'Polygon',
        coordinates: createBlockPolygon(STREETS.centralPark, STREETS.stLouis, 'south'),
      },
    },
    checkpoints: createCheckpoints(
      STREETS.centralPark, STREETS.stLouis,
      'Central Park', 'St Louis', 'south'
    ),
  },
  {
    code: 'NL-S05',
    streetName: '1500 W Roosevelt Rd (South Side)',
    fromCorner: 'S Central Park Ave',
    toCorner: 'S Lawndale Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: {
        type: 'Point',
        coordinates: [(STREETS.centralPark + STREETS.lawndale) / 2, ROOSEVELT_LAT - BLOCK_DEPTH / 2],
      },
      polygon: {
        type: 'Polygon',
        coordinates: createBlockPolygon(STREETS.lawndale, STREETS.centralPark, 'south'),
      },
    },
    checkpoints: createCheckpoints(
      STREETS.lawndale, STREETS.centralPark,
      'Lawndale', 'Central Park', 'south'
    ),
  },
]

/**
 * Map center for the Roosevelt Road corridor
 * Centered between Kedzie and Lawndale on Roosevelt Road
 */
export const MAP_CENTER = {
  longitude: -87.7118,  // Midpoint between Kedzie (-87.7055) and Lawndale (-87.7180)
  latitude: 41.8603,    // Roosevelt Road
  zoom: 15.5,           // Slightly closer for better block visibility
}

/**
 * Map bounds for the launch area
 * Extended west to include 15th/16th streets from Pulaski to Lawndale
 * Updated: 2025-01-27
 */
export const MAP_BOUNDS = {
  north: 41.8640,   // Extended north
  south: 41.8560,   // Extended south
  east: -87.7040,   // East of Kedzie
  west: -87.7270,   // Extended west past Pulaski
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
