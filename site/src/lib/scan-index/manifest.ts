/**
 * Pure pieces of the scan indexer: manifest decoding, R2 key parsing and the
 * row shapes written to `scan_sessions` / `capture_chunks` /
 * `reconstruction_assets`. Port of backend/…/ScanIndexer.kt — every rule here
 * mirrors a line there; the Kotlin file is kept as the reference.
 */

/** `${latIdx}:${lngIdx}` — the shape gridCell() emits (lib/world-observations.ts). */
export const CELL_ID_PATTERN = /^-?\d+:-?\d+$/

/** Cell for a session whose manifest key has no parseable cell segment. */
export const UNKNOWN_CELL = '0:0'

/** The fields of the shared `EnvScanManifest` the indexer reads. */
export interface ScanManifest {
  sessionId: string
  deviceId: string
  deviceModel: string
  osVersion: string
  startedAtMs: number
  completedAtMs: number
  photoKeys: string[]
  observedItemCount: number
  scannedCellCount: number
  captureChunks: string[] | null
  objectCapture: { r2Key: string | null } | null
  lapKind: string | null
  routeId: number | null
  geoRefEventCount: number | null
  eventLogKey: string | null
  captureHealth: {
    sessionSeconds: number | null
    videoCoveredSeconds: number | null
    maxThermalState: string | null
    droppedVideoChunkCount: number | null
  } | null
}

export class ManifestError extends Error {}

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => v !== null && typeof v === 'object' && !Array.isArray(v)

function reqString(o: Obj, k: string): string {
  const v = o[k]
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v) // lenient
  throw new ManifestError(`${k}: expected string`)
}
function optString(o: Obj, k: string): string | null {
  return o[k] == null ? null : reqString(o, k)
}
function reqInt(o: Obj, k: string): number {
  const v = o[k]
  const n = typeof v === 'string' && /^-?\d+$/.test(v) ? Number(v) : v
  if (typeof n === 'number' && Number.isSafeInteger(n)) return n
  throw new ManifestError(`${k}: expected integer`)
}
function optInt(o: Obj, k: string): number | null {
  return o[k] == null ? null : reqInt(o, k)
}
function reqNumber(o: Obj, k: string): number {
  const v = o[k]
  const n = typeof v === 'string' && v.trim() !== '' ? Number(v) : v
  if (typeof n === 'number' && Number.isFinite(n)) return n
  throw new ManifestError(`${k}: expected number`)
}
function optNumber(o: Obj, k: string): number | null {
  return o[k] == null ? null : reqNumber(o, k)
}
function reqArray(o: Obj, k: string): unknown[] {
  const v = o[k]
  if (Array.isArray(v)) return v
  throw new ManifestError(`${k}: expected array`)
}
function optArray(o: Obj, k: string): unknown[] | null {
  return o[k] == null ? null : reqArray(o, k)
}
function stringList(o: Obj, k: string, required: boolean): string[] | null {
  const arr = required ? reqArray(o, k) : optArray(o, k)
  if (arr == null) return null
  return arr.map((v, i) => {
    if (typeof v !== 'string') throw new ManifestError(`${k}[${i}]: expected string`)
    return v
  })
}
function optObj(o: Obj, k: string): Obj | null {
  const v = o[k]
  if (v == null) return null
  if (isObj(v)) return v
  throw new ManifestError(`${k}: expected object`)
}

/**
 * Decode a manifest the way `Json { ignoreUnknownKeys; isLenient }` decodes
 * `EnvScanManifest`: the non-defaulted fields must be present and typed, and
 * every field the indexer reads is type-checked. Throws ManifestError — the
 * caller counts it as a failed manifest, exactly as the Kotlin catch did.
 */
export function parseManifest(text: string): ScanManifest {
  let raw: unknown
  try {
    raw = JSON.parse(text)
  } catch (e) {
    throw new ManifestError(`invalid JSON: ${(e as Error).message}`)
  }
  if (!isObj(raw)) throw new ManifestError('manifest is not an object')
  const o = raw
  // Required by the Kotlin data class (no default) even though the indexer
  // only needs their size, or nothing at all.
  reqArray(o, 'path')
  reqNumber(o, 'scannedCellSizeMeters')
  const objectCapture = optObj(o, 'objectCapture')
  if (objectCapture) {
    for (const k of ['widthMeters', 'heightMeters', 'depthMeters', 'volumeLiters']) reqNumber(objectCapture, k)
    reqInt(objectCapture, 'estimatedWeightGrams')
  }
  const health = optObj(o, 'captureHealth')
  return {
    sessionId: reqString(o, 'sessionId'),
    deviceId: reqString(o, 'deviceId'),
    deviceModel: reqString(o, 'deviceModel'),
    osVersion: reqString(o, 'osVersion'),
    startedAtMs: reqInt(o, 'startedAtMs'),
    completedAtMs: reqInt(o, 'completedAtMs'),
    photoKeys: stringList(o, 'photoKeys', true)!,
    observedItemCount: reqArray(o, 'observedItems').length,
    scannedCellCount: reqArray(o, 'scannedCells').length,
    captureChunks: stringList(o, 'captureChunks', false),
    objectCapture: objectCapture ? { r2Key: optString(objectCapture, 'r2Key') } : null,
    lapKind: optString(o, 'lapKind'),
    routeId: optInt(o, 'routeId'),
    geoRefEventCount: optArray(o, 'geoRefEvents')?.length ?? null,
    eventLogKey: optString(o, 'eventLogKey'),
    captureHealth: health
      ? {
          sessionSeconds: optNumber(health, 'sessionSeconds'),
          videoCoveredSeconds: optNumber(health, 'videoCoveredSeconds'),
          maxThermalState: optString(health, 'maxThermalState'),
          droppedVideoChunkCount: optInt(health, 'droppedVideoChunkCount'),
        }
      : null,
  }
}

/**
 * Upload layout (lib/r2.ts rawScanKey / rawSessionArtifactKey, /api/captures/
 * upload-url). Two separate namespaces:
 *
 *  - device folder  `raw/{cell}/{deviceId}/…`: the manifest itself
 *    (`{sessionId}-manifest.json`), photoKeys and the USDZ;
 *  - session folder `raw/{cell}/{sessionId}/…`: captureChunks, late
 *    `capture/` objects, the event log, and the keyframe photos
 *    CaptureChunkUploader writes to `photos/photo-N.jpg` — which the app also
 *    lists in photoKeys (13 of them in prod R2 on 2026-10-18), so a photoKey
 *    may sit there, but only under `photos/`.
 *
 * The cell may differ from the manifest's. Every one of the 178 manifests in
 * prod R2 on 2026-10-18 follows this.
 */
const MANIFEST_KEY = /^raw\/(-?\d+:-?\d+)\/([^/]+)\/([^/]+)-manifest\.json$/

/** capture_chunks.r2_key (and the session's key columns) are varchar(500). */
export const MAX_KEY_LENGTH = 500

/**
 * scan_sessions varchar widths. A manifest value that doesn't fit would fail
 * the upsert on every run; it is refused as a manifest error instead (Kotlin's
 * JDBC upsert failed it too, just without remembering).
 */
const SESSION_COLUMN_WIDTHS: [keyof ScanManifest, number][] = [
  ['sessionId', 64], ['deviceId', 64], ['deviceModel', 64], ['osVersion', 32], ['lapKind', 16],
]
const FOLDER_KEY = /^raw\/-?\d+:-?\d+\/([^/]+)\/(.+)$/
const SESSION_PHOTO = /^raw\/-?\d+:-?\d+\/[^/]+\/photos\/[^/]+$/

/** Which folder a key must sit in. */
export type KeyNamespace = 'device' | 'session'

/**
 * The manifest's own key must name the device and session the manifest claims,
 * so a manifest can only speak for the folder it was uploaded into. A device
 * id equal to its session id would merge the two namespaces, so it is refused.
 */
export function assertManifestKey(manifestKey: string, manifest: ScanManifest): void {
  if (manifestKey.length > MAX_KEY_LENGTH) throw new ManifestError(`key is ${manifestKey.length} characters (max ${MAX_KEY_LENGTH})`)
  const m = MANIFEST_KEY.exec(manifestKey)
  if (!m) throw new ManifestError('key is not raw/{cell}/{deviceId}/{sessionId}-manifest.json')
  if (m[2] !== manifest.deviceId) throw new ManifestError(`deviceId ${manifest.deviceId} does not match its key`)
  if (m[3] !== manifest.sessionId) throw new ManifestError(`sessionId ${manifest.sessionId} does not match its key`)
  if (manifest.deviceId === manifest.sessionId) throw new ManifestError('deviceId and sessionId must differ')
  for (const [field, width] of SESSION_COLUMN_WIDTHS) {
    const v = manifest[field]
    if (typeof v === 'string' && v.length > width) throw new ManifestError(`${field} is longer than ${width} characters`)
  }
  const thermal = manifest.captureHealth?.maxThermalState
  if (thermal != null && thermal.length > 16) throw new ManifestError('captureHealth.maxThermalState is longer than 16 characters')
}

/** Is `key` under `raw/{cell}/{folder}/…` with no `.`/`..`/empty segments? */
export function isUnderFolder(key: string, folder: string): boolean {
  if (key.length > MAX_KEY_LENGTH) return false
  const m = FOLDER_KEY.exec(key)
  if (!m || m[1] !== folder) return false
  return m[2].split('/').every((seg) => seg !== '' && seg !== '.' && seg !== '..')
}

/** Is `key` in this session's folder for its namespace? */
export function isOwnedKey(key: string, manifest: Pick<ScanManifest, 'deviceId' | 'sessionId'>, ns: KeyNamespace): boolean {
  return isUnderFolder(key, ns === 'device' ? manifest.deviceId : manifest.sessionId)
}

/** Extra per-key gate (the upload-grant check for post-cutover manifests). */
export type KeyGate = (key: string, ns: KeyNamespace) => boolean

/**
 * Drop every key the manifest names outside its own folder for that list —
 * photoKeys and objectCapture.r2Key in the device folder, captureChunks and
 * eventLogKey in the session folder — or refused by `gate`. Without this a
 * manifest could claim another session's objects as its capture_chunks rows.
 * Returns the cleaned manifest and what was dropped, for the log.
 */
export function confineManifestKeys(
  manifest: ScanManifest,
  gate: KeyGate = () => true,
): { manifest: ScanManifest; dropped: string[] } {
  const dropped: string[] = []
  const keep = (ns: KeyNamespace) => (k: string) => {
    if (isOwnedKey(k, manifest, ns) && gate(k, ns)) return true
    dropped.push(k)
    return false
  }
  const keepOne = (k: string | null, ns: KeyNamespace) => (k != null && keep(ns)(k) ? k : null)
  // A photo is a device-folder key, or a session-folder keyframe under photos/;
  // each is gated in the namespace of the folder it is actually in.
  const keepPhoto = (k: string) => {
    if (isOwnedKey(k, manifest, 'device') && gate(k, 'device')) return true
    if (isOwnedKey(k, manifest, 'session') && SESSION_PHOTO.test(k) && gate(k, 'session')) return true
    dropped.push(k)
    return false
  }
  return {
    manifest: {
      ...manifest,
      photoKeys: manifest.photoKeys.filter(keepPhoto),
      captureChunks: manifest.captureChunks?.filter(keep('session')) ?? null,
      objectCapture: manifest.objectCapture ? { r2Key: keepOne(manifest.objectCapture.r2Key, 'device') } : null,
      eventLogKey: keepOne(manifest.eventLogKey, 'session'),
    },
    dropped,
  }
}

/** `raw/{cellId}/…` — the cell is the segment after the top-level prefix. */
export function cellIdFromKey(key: string): string | null {
  const seg = key.split('/')[1]
  return seg !== undefined && CELL_ID_PATTERN.test(seg) ? seg : null
}

export function extensionOf(key: string): string | null {
  const file = key.slice(key.lastIndexOf('/') + 1)
  const dot = file.lastIndexOf('.')
  if (dot < 0) return null
  const ext = file.slice(dot + 1).toLowerCase()
  return ext === '' ? null : ext
}

export function artifactTypeForKey(key: string): string {
  switch (extensionOf(key)) {
    case 'mp4': case 'mov': case 'hevc': return 'video'
    case 'jsonl': return 'pose'
    case 'png': return 'depth'
    case 'ply': return 'pointcloud'
    case 'usdz': return 'usdz'
    case 'jpg': case 'jpeg': case 'heic': return 'photo'
    default: return 'other'
  }
}

export function platformFor(deviceModel: string | null): string | null {
  const m = deviceModel?.toLowerCase()
  if (m == null) return null
  return m.includes('iphone') || m.includes('ipad') || m.includes('ipod') ? 'ios' : null
}

/**
 * Share of the session covered by encoded video, 0..100. Null unless both
 * inputs are present and the session length is positive.
 */
export function videoCoveragePct(sessionSeconds: number | null, videoCoveredSeconds: number | null): number | null {
  if (sessionSeconds == null || videoCoveredSeconds == null || sessionSeconds <= 0) return null
  return Math.min(100, Math.max(0, (videoCoveredSeconds / sessionSeconds) * 100))
}

export interface ReconKey {
  cell: string
  version: number
  assetType: string
  format: string
}

/**
 * `reconstructed/{cellId}/{version}/…/{assetType}.{format}`. Candidate bundles
 * (`…/verified-candidate/…`) belong to recon_worker's lifecycle and are never
 * mirrored as published rows.
 */
export function parseReconstructedKey(key: string): ReconKey | null {
  const parts = key.split('/')
  if (parts.length < 4 || parts[3] === 'verified-candidate') return null
  const cell = parts[1]
  if (!CELL_ID_PATTERN.test(cell)) return null
  if (!/^[+-]?\d+$/.test(parts[2])) return null
  const version = Number(parts[2])
  if (version < -2147483648 || version > 2147483647) return null
  const file = parts[parts.length - 1]
  const dot = file.lastIndexOf('.')
  const assetType = dot < 0 ? file : file.slice(0, dot)
  const format = dot < 0 ? '' : file.slice(dot + 1).toLowerCase()
  if (assetType === '' || format === '') return null
  return { cell, version, assetType, format }
}

export interface ChunkRow {
  sessionId: string
  locationCellId: string
  artifactType: string
  r2Key: string
  format: string | null
  byteSize: number | null
  capturedAtMs: number | null
}

export interface SessionRow {
  sessionId: string
  deviceId: string
  deviceModel: string | null
  platform: string | null
  osVersion: string | null
  lapKind: string | null
  routeId: number | null
  locationCellId: string
  startedAtMs: number | null
  completedAtMs: number | null
  photoCount: number
  chunkCount: number
  observedItemCount: number
  scannedCellCount: number
  hasObjectCapture: boolean
  objectCaptureR2Key: string | null
  manifestR2Key: string
  totalBytes: number | null
  geoRefEventCount: number
  eventLogR2Key: string | null
  videoCoveragePct: number | null
  maxThermalState: string | null
  droppedVideoChunks: number | null
  /** The worker the upload grants tie this session to; null for legacy (pre-grant) manifests. */
  workerId: number | null
}

/**
 * Every R2 object a session owns, in the Kotlin order: manifest, photos,
 * manifest-named capture chunks, late capture chunks the listing has under
 * `/{sessionId}/capture/` that the manifest froze out, the USDZ, the event log.
 * Duplicates are kept here (the session's total_bytes sums them, as Kotlin
 * did); `dedupeChunks` collapses them for the upsert.
 */
export function buildChunkRows(
  manifest: ScanManifest,
  manifestKey: string,
  sizeOf: (key: string) => number | undefined,
  listingKeys: Iterable<string>,
  gate: KeyGate = () => true,
): ChunkRow[] {
  const sessionCell = cellIdFromKey(manifestKey) ?? UNKNOWN_CELL
  const row = (key: string, type: string, capturedAtMs: number | null): ChunkRow => ({
    sessionId: manifest.sessionId,
    locationCellId: cellIdFromKey(key) ?? sessionCell,
    artifactType: type,
    r2Key: key,
    format: extensionOf(key),
    byteSize: sizeOf(key) ?? null,
    capturedAtMs,
  })
  const rows: ChunkRow[] = [row(manifestKey, 'manifest', manifest.completedAtMs)]
  for (const k of manifest.photoKeys) rows.push(row(k, 'photo', null))
  for (const k of manifest.captureChunks ?? []) rows.push(row(k, artifactTypeForKey(k), null))
  const named = new Set(manifest.captureChunks ?? [])
  const lateMarker = `/${manifest.sessionId}/capture/`
  for (const k of listingKeys) {
    if (k.includes(lateMarker) && !named.has(k) && isOwnedKey(k, manifest, 'session') && gate(k, 'session')) {
      rows.push(row(k, artifactTypeForKey(k), null))
    }
  }
  if (manifest.objectCapture?.r2Key != null) rows.push(row(manifest.objectCapture.r2Key, 'usdz', manifest.completedAtMs))
  if (manifest.eventLogKey != null) rows.push(row(manifest.eventLogKey, 'event_log', manifest.completedAtMs))
  return rows
}

/** One row per r2_key, last occurrence wins — what the Kotlin JDBC batch left behind. */
export function dedupeChunks(rows: ChunkRow[]): ChunkRow[] {
  const byKey = new Map<string, ChunkRow>()
  for (const r of rows) {
    byKey.delete(r.r2Key)
    byKey.set(r.r2Key, r)
  }
  return [...byKey.values()]
}

export function buildSessionRow(
  manifest: ScanManifest,
  manifestKey: string,
  chunkRows: ChunkRow[],
  workerId: number | null = null,
): SessionRow {
  const total = chunkRows.reduce((sum, r) => sum + (r.byteSize ?? 0), 0)
  const h = manifest.captureHealth
  return {
    sessionId: manifest.sessionId,
    deviceId: manifest.deviceId,
    deviceModel: manifest.deviceModel,
    platform: platformFor(manifest.deviceModel),
    osVersion: manifest.osVersion,
    lapKind: manifest.lapKind,
    routeId: manifest.routeId,
    locationCellId: cellIdFromKey(manifestKey) ?? UNKNOWN_CELL,
    startedAtMs: manifest.startedAtMs,
    completedAtMs: manifest.completedAtMs,
    photoCount: manifest.photoKeys.length,
    chunkCount: manifest.captureChunks?.length ?? 0,
    observedItemCount: manifest.observedItemCount,
    scannedCellCount: manifest.scannedCellCount,
    hasObjectCapture: manifest.objectCapture != null,
    objectCaptureR2Key: manifest.objectCapture?.r2Key ?? null,
    manifestR2Key: manifestKey,
    totalBytes: total > 0 ? total : null,
    geoRefEventCount: manifest.geoRefEventCount ?? 0,
    eventLogR2Key: manifest.eventLogKey,
    videoCoveragePct: videoCoveragePct(h?.sessionSeconds ?? null, h?.videoCoveredSeconds ?? null),
    maxThermalState: h?.maxThermalState ?? null,
    droppedVideoChunks: h?.droppedVideoChunkCount ?? null,
    workerId,
  }
}
