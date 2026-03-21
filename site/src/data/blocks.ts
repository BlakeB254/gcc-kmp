/**
 * North Lawndale Block Data — Roosevelt Road Corridor
 *
 * Fallback data matching the 10 production blocks in the database.
 * These are GPS-traced polygon boundaries covering blocks between
 * 15th St and 16th St (Roosevelt Rd) in North Lawndale.
 *
 * The website fetches from /api/blocks first; this data is only used
 * when the API is unreachable.
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

/**
 * The 10 launch blocks — GPS-traced boundaries from production database.
 * Blocks run between 15th St and 16th St (Roosevelt Rd corridor).
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
      center: { type: 'Point', coordinates: [-87.72468409297217, 41.859784844988184] },
      polygon: { type: 'Polygon', coordinates: [[[-87.72436621243862,41.85887713030937],[-87.72440387844341,41.860699244917356],[-87.72501338445508,41.8606911657443],[-87.72495289655153,41.85887183898171],[-87.72436621243862,41.85887713030937]]] },
    },
    checkpoints: [],
  },
  {
    code: 'NL-N02',
    streetName: 'W Roosevelt Rd (North Side)',
    fromCorner: 'S Harding Ave',
    toCorner: 'S Springfield Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [-87.7237635445187, 41.85980304089159] },
      polygon: { type: 'Polygon', coordinates: [[[-87.72310944121632,41.858901094921634],[-87.72314511046446,41.860716759951],[-87.72443494720221,41.86071341799601],[-87.72436467919184,41.85888089069772],[-87.72310944121632,41.858901094921634]]] },
    },
    checkpoints: [],
  },
  {
    code: 'NL-N03',
    streetName: 'W Roosevelt Rd (North Side)',
    fromCorner: 'S Ridgeway Ave',
    toCorner: 'S Lawndale Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [-87.71891057876738, 41.85984871949483] },
      polygon: { type: 'Polygon', coordinates: [[[-87.71827424468796,41.858961246012996],[-87.71834096492195,41.860751496466605],[-87.71953970620343,41.86075132923571],[-87.71948739925617,41.858930806264],[-87.71827424468796,41.858961246012996]]] },
    },
    checkpoints: [],
  },
  {
    code: 'NL-N04',
    streetName: 'W Roosevelt Rd (North Side)',
    fromCorner: 'S Avers Ave',
    toCorner: 'S Hamlin Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [-87.7213029695082, 41.85981860777793] },
      polygon: { type: 'Polygon', coordinates: [[[-87.72061470463386,41.85892300649701],[-87.72075164656154,41.86073321229429],[-87.72196385861172,41.86071285531176],[-87.72188166822572,41.858905357008666],[-87.72061470463386,41.85892300649701]]] },
    },
    checkpoints: [],
  },
  {
    code: 'NL-N05',
    streetName: 'W Roosevelt Rd (North Side)',
    fromCorner: 'S Springfield Ave',
    toCorner: 'S Avers Ave',
    side: 'north',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [-87.72252501962954, 41.859809016798266] },
      polygon: { type: 'Polygon', coordinates: [[[-87.72188166822572,41.858905357008666],[-87.72196385861172,41.86071285531176],[-87.72314511046446,41.860716759951],[-87.72310944121632,41.858901094921634],[-87.72188166822572,41.858905357008666]]] },
    },
    checkpoints: [],
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
      center: { type: 'Point', coordinates: [-87.7140143183777, 41.859915968319314] },
      polygon: { type: 'Polygon', coordinates: [[[-87.71343772696717,41.86083650194416],[-87.71465168391362,41.86081931346976],[-87.7146000344683,41.858993625301764],[-87.71336782816174,41.859014432561565],[-87.71343772696717,41.86083650194416]]] },
    },
    checkpoints: [],
  },
  {
    code: 'NL-S02',
    streetName: 'W Roosevelt Rd (South Side)',
    fromCorner: 'S Central Park Ave',
    toCorner: 'S Drake Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [-87.71523298766681, 41.85989385109283] },
      polygon: { type: 'Polygon', coordinates: [[[-87.7146580099565,41.8608114574389],[-87.71586975683759,41.86079192181697],[-87.7158041494049,41.85897839981368],[-87.7146000344683,41.858993625301764],[-87.7146580099565,41.8608114574389]]] },
    },
    checkpoints: [],
  },
  {
    code: 'NL-S03',
    streetName: 'W Roosevelt Rd (South Side)',
    fromCorner: 'S Millard Ave',
    toCorner: 'S Central Park Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [-87.71645933534221, 41.859877341847756] },
      polygon: { type: 'Polygon', coordinates: [[[-87.71586975683759,41.86079192181697],[-87.71711589055013,41.86077708975296],[-87.71703596353454,41.858966447327674],[-87.71581573044654,41.858973908493425],[-87.71586975683759,41.86079192181697]]] },
    },
    checkpoints: [],
  },
  {
    code: 'NL-S04',
    streetName: 'W Roosevelt Rd (South Side)',
    fromCorner: 'S Lawndale Ave',
    toCorner: 'S Millard Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [-87.71768495492599, 41.859860690860984] },
      polygon: { type: 'Polygon', coordinates: [[[-87.71711589055013,41.86077708975296],[-87.71834096492195,41.860751496466605],[-87.71824700069735,41.858947729896684],[-87.71703596353454,41.858966447327674],[-87.71711589055013,41.86077708975296]]] },
    },
    checkpoints: [],
  },
  {
    code: 'NL-S05',
    streetName: 'W Roosevelt Rd (South Side)',
    fromCorner: 'S Hamlin Ave',
    toCorner: 'S Ridgeway Ave',
    side: 'south',
    includesAlley: true,
    geo: {
      center: { type: 'Point', coordinates: [-87.72009836416376, 41.85983458857275] },
      polygon: { type: 'Polygon', coordinates: [[[-87.71953970620343,41.86075132923571],[-87.72075164656154,41.86073321229429],[-87.72061470463386,41.85892300649701],[-87.71948739925617,41.858930806264],[-87.71953970620343,41.86075132923571]]] },
    },
    checkpoints: [],
  },
]

/**
 * Map center — midpoint of all 10 blocks
 */
export const MAP_CENTER = {
  longitude: -87.71927,
  latitude: 41.85984,
  zoom: 15.5,
}

/**
 * Map bounds encompassing all 10 blocks (15th–16th St corridor)
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
