type LineGeometry = {
  type?: string
  coordinates?: unknown
}

type StoredCheckpoint = {
  id?: number | null
  name: string
  sequenceOrder: number
  coordinates: unknown
  photoRequired?: boolean | null
  geofenceRadius?: string | number | null
  type?: string | null
  instructions?: string | null
  photoInstructions?: string | null
  cameraMode?: string | null
  bountyPrompts?: unknown
  imageUrl?: string | null
}

export type MobileRouteCheckpoint = {
  id?: number | null
  name: string
  sequenceOrder: number
  coordinates: { lat: number; lng: number }
  photoRequired: boolean
  geofenceRadius: number
  type: string
  instructions?: string | null
  cameraMode?: string | null
  bountyPrompts?: string[]
  imageUrl?: string | null
}

export type GeneratedBlockCheckpoint = {
  name: string
  type: 'corner_start' | 'midpoint' | 'alley' | 'corner_end'
  description: string
  coordinates: [number, number]
  radius: number
}

const MIN_POINT_DISTANCE_METERS = 8
export const MIN_ROUTE_CHECKPOINTS = 10
const TARGET_CHECKPOINTS = MIN_ROUTE_CHECKPOINTS

export type GeneratedRouteCheckpointInsert = {
  routeId: number
  name: string
  sequenceOrder: number
  coordinates: [number, number]
  photoRequired: boolean
  photoInstructions: string
  geofenceRadius: string
  type: 'route_start' | 'photo_point' | 'alley_entrance' | 'route_end'
}

export function geometryCoordinates(geometry: unknown): [number, number][] {
  const coordinates = (geometry as LineGeometry | null)?.coordinates
  if (!Array.isArray(coordinates)) return []

  const first = coordinates[0]
  const rawPoints = Array.isArray(first) && Array.isArray(first[0])
    ? first
    : coordinates

  if (!Array.isArray(rawPoints)) return []

  return rawPoints
    .map((point): [number, number] | null => {
      if (!Array.isArray(point)) return null
      const lng = Number(point[0])
      const lat = Number(point[1])
      return Number.isFinite(lat) && Number.isFinite(lng) ? [lng, lat] : null
    })
    .filter((point): point is [number, number] => point != null)
}

export function toMobileRouteCheckpoints(
  checkpoints: StoredCheckpoint[],
  geometry: unknown,
): MobileRouteCheckpoint[] {
  if (checkpoints.length >= MIN_ROUTE_CHECKPOINTS) {
    return checkpoints.map(storedCheckpointToMobile)
  }

  const generated = generateMobileRouteCheckpointsFromGeometry(geometry)
  return generated.length >= MIN_ROUTE_CHECKPOINTS
    ? generated
    : checkpoints.map(storedCheckpointToMobile)
}

export function generateMobileRouteCheckpointsFromGeometry(geometry: unknown): MobileRouteCheckpoint[] {
  const rawCoordinates = geometryCoordinates(geometry)
  if (rawCoordinates.length < 2) return []

  const uniqueCoordinates: [number, number][] = []
  for (const coordinate of rawCoordinates) {
    const previous = uniqueCoordinates.at(-1)
    if (!previous || distanceMeters(previous, coordinate) >= MIN_POINT_DISTANCE_METERS) {
      uniqueCoordinates.push(coordinate)
    }
  }

  if (uniqueCoordinates.length < 2) return []

  const isLoop = distanceMeters(uniqueCoordinates[0], uniqueCoordinates.at(-1)!) < MIN_POINT_DISTANCE_METERS
  const corners = isLoop && uniqueCoordinates.length > 2 ? uniqueCoordinates.slice(0, -1) : uniqueCoordinates
  if (corners.length < 2) return []

  const checkpointCoordinates = interpolateCheckpoints(corners, isLoop)
  return checkpointCoordinates.map((coordinate, index) => {
    const isFirst = index === 0
    const isLast = index === checkpointCoordinates.length - 1
    return {
      name: checkpointName(index, checkpointCoordinates.length, isFirst, isLast),
      sequenceOrder: index + 1,
      coordinates: {
        lat: coordinate[1],
        lng: coordinate[0],
      },
      photoRequired: true,
      geofenceRadius: 25,
      type: isFirst ? 'route_start' : isLast ? 'route_end' : 'record_clean_verify',
      instructions: isFirst
        ? 'Start from any safe point on the route perimeter, then complete every checkpoint.'
        : isLast
          ? 'Finish with a final scan and verification photo for this block.'
        : 'Scan, clean, and verify this route section before moving on.',
      cameraMode: index % 2 === 0 ? 'panoramic' : 'focused',
      bountyPrompts: ['trash', 'illegal dumping', 'overflowing bin', 'broken glass'],
      imageUrl: null,
    }
  })
}

export function hotspotCheckpoints(
  hotspots: Array<{ lat: number; lng: number; severity: number; detectedAt: string | Date; status: string }>,
  startingSequence: number,
): MobileRouteCheckpoint[] {
  return hotspots.map((hotspot, index) => ({
    name: `Known hotspot ${index + 1}`,
    sequenceOrder: startingSequence + index,
    coordinates: { lat: hotspot.lat, lng: hotspot.lng },
    photoRequired: true,
    geofenceRadius: 22,
    type: 'record_clean_verify',
    instructions: `Past scan hotspot (${hotspot.status}, severity ${hotspot.severity}). Re-check and record before/after if trash is present.`,
    cameraMode: 'focused',
    bountyPrompts: ['recurring hotspot', 'trash', 'illegal dumping'],
    imageUrl: null,
  }))
}

export function generateRouteCheckpointInserts(
  routeId: number,
  geometry: unknown,
): GeneratedRouteCheckpointInsert[] {
  return generateMobileRouteCheckpointsFromGeometry(geometry).map((checkpoint, index) => ({
    routeId,
    name: checkpoint.name,
    sequenceOrder: index,
    coordinates: [checkpoint.coordinates.lng, checkpoint.coordinates.lat],
    photoRequired: checkpoint.photoRequired,
    photoInstructions: checkpoint.instructions ?? 'Scan, clean, and verify this route section before moving on.',
    geofenceRadius: String(checkpoint.geofenceRadius),
    type: checkpoint.type === 'route_start'
      ? 'route_start'
      : checkpoint.type === 'route_end'
        ? 'route_end'
        : 'photo_point',
  }))
}

export function generateBlockCheckpointsFromGeometry(geometry: unknown): GeneratedBlockCheckpoint[] {
  return generateMobileRouteCheckpointsFromGeometry(geometry).map((checkpoint, index, all) => ({
    name: checkpoint.name,
    type: index === 0
      ? 'corner_start'
      : index === all.length - 1
        ? 'corner_end'
        : 'midpoint',
    description: checkpoint.instructions ?? 'Scan, clean, and verify this route section before moving on.',
    coordinates: [checkpoint.coordinates.lng, checkpoint.coordinates.lat],
    radius: checkpoint.geofenceRadius,
  }))
}

function interpolateCheckpoints(corners: [number, number][], isLoop: boolean): [number, number][] {
  const segmentCount = isLoop ? corners.length : corners.length - 1
  if (segmentCount <= 0) return corners

  const segmentLengths = Array.from({ length: segmentCount }, (_, index) => {
    const from = corners[index]
    const to = corners[(index + 1) % corners.length]
    return distanceMeters(from, to)
  })
  const totalLength = segmentLengths.reduce((sum, value) => sum + value, 0)
  if (totalLength < 1) return corners

  const targetCount = Math.max(TARGET_CHECKPOINTS, corners.length)
  const extraPointsNeeded = targetCount - corners.length
  const extraPerSegment = segmentLengths.map((length) => {
    if (extraPointsNeeded <= 0) return 0
    const proportional = Math.floor((length / totalLength) * extraPointsNeeded)
    return length > 30 ? Math.max(1, proportional) : proportional
  })

  let remaining = extraPointsNeeded - extraPerSegment.reduce((sum, value) => sum + value, 0)
  while (remaining > 0) {
    let bestIndex = 0
    let bestSpacing = -1
    for (let index = 0; index < segmentLengths.length; index++) {
      const spacing = segmentLengths[index] / (extraPerSegment[index] + 1)
      if (spacing > bestSpacing) {
        bestSpacing = spacing
        bestIndex = index
      }
    }
    extraPerSegment[bestIndex] += 1
    remaining -= 1
  }

  const result: [number, number][] = []
  for (let index = 0; index < segmentCount; index++) {
    const from = corners[index]
    const to = corners[(index + 1) % corners.length]
    result.push(from)
    for (let extra = 1; extra <= extraPerSegment[index]; extra++) {
      const fraction = extra / (extraPerSegment[index] + 1)
      result.push([
        from[0] + (to[0] - from[0]) * fraction,
        from[1] + (to[1] - from[1]) * fraction,
      ])
    }
  }

  if (!isLoop) result.push(corners.at(-1)!)
  return result
}

function storedCheckpointToMobile(cp: StoredCheckpoint): MobileRouteCheckpoint {
  const point = Array.isArray(cp.coordinates) ? cp.coordinates : []
  return {
    id: cp.id ?? null,
    name: cp.name,
    sequenceOrder: cp.sequenceOrder,
    coordinates: {
      lat: Number(point[1]) || 0,
      lng: Number(point[0]) || 0,
    },
    photoRequired: cp.photoRequired ?? true,
    geofenceRadius: Number(cp.geofenceRadius ?? 15),
    type: cp.type ?? 'photo_point',
    instructions: cp.instructions ?? cp.photoInstructions ?? null,
    cameraMode: cp.cameraMode ?? null,
    bountyPrompts: normalizeStringList(cp.bountyPrompts),
    imageUrl: cp.imageUrl ?? null,
  }
}

function checkpointName(index: number, total: number, isFirst: boolean, isLast: boolean): string {
  if (isFirst) return 'Route start zone'
  if (isLast) return 'Route finish'
  return `Perimeter checkpoint ${index + 1} of ${total}`
}

function normalizeStringList(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String)
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      return Array.isArray(parsed) ? parsed.map(String) : []
    } catch {
      return []
    }
  }
  return []
}

function distanceMeters(a: [number, number], b: [number, number]) {
  const radius = 6371000
  const lat1 = toRadians(a[1])
  const lat2 = toRadians(b[1])
  const deltaLat = toRadians(b[1] - a[1])
  const deltaLng = toRadians(b[0] - a[0])
  const haversine = Math.sin(deltaLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLng / 2) ** 2
  return radius * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine))
}

function toRadians(value: number) {
  return value * Math.PI / 180
}
