/**
 * Evidence photo fingerprints — the pure half (hashing math, EXIF date
 * parsing, and the rules that turn fingerprints into review flags).
 *
 * Before this, nothing compared one evidence photo with another: the same
 * before/after photo could be submitted for every job a worker claimed, and
 * a before photo could double as its own after photo. Now every evidence and
 * hotspot photo gets a row in `photo_fingerprints` (sha256 of the stored
 * bytes, a 64-bit difference hash, the EXIF capture time of the original
 * upload) and every submission is checked against the rest.
 *
 * Only exact reuse of one photo across two different JOBS is refused; every
 * other finding is a flag shown to the reviewer, because honest work can
 * trip them (a cleaned spot photographed from the same place looks a lot
 * like its before photo; phone clocks drift; a block gets cleaned again).
 *
 * Pure module — no `@/db`, no `sharp` — so `tsx --test` loads it anywhere.
 * Hashing I/O is in photo-fingerprint-compute.ts, DB in photo-fingerprint-db.ts.
 */

/**
 * dHash Hamming distance at or under which two photos are near-duplicates.
 * Out of 64 bits, re-encodes/resizes of one photo land at 0–4 and different
 * scenes typically 20+; 6 leaves room for a crop or brightness change while
 * staying far from unrelated photos. Near-duplicates are flags, never refusals.
 */
export const NEAR_DUPLICATE_MAX_HAMMING = 6

/**
 * EXIF capture time this far before the work started is flagged. EXIF
 * DateTimeOriginal usually has no timezone (we read it as UTC), so anything
 * inside ±14 h may be a timezone artefact; 24 h clears that with margin.
 */
export const EXIF_STALE_BEFORE_WORK_MS = 24 * 60 * 60 * 1000

/** Fetch/decode budget for one photo; past it the photo is recorded unverified. */
export const FINGERPRINT_TIMEOUT_MS = 4000
/** Largest blob fetched for hashing (uploads are re-encoded to ≤2048 px JPEG). */
export const FINGERPRINT_MAX_BYTES = 15 * 1024 * 1024
/** Near-duplicate search window (rows older than this are not compared). */
export const NEAR_DUPLICATE_LOOKBACK_DAYS = 180

export const PHOTO_FLAGS = [
  'unverified',
  // The URL is not an upload by the submitter (e.g. a hotspot photo stored
  // before origin checks existed); it is never fetched.
  'not_own_upload',
  'reused_other_job',
  'reused_other_scan',
  'reused_across_job_and_scan',
  'near_duplicate_other_work',
  'identical_before_after',
  'near_duplicate_before_after',
  'exif_before_work',
] as const
export type PhotoFlag = (typeof PHOTO_FLAGS)[number]

// ── Hashing math ────────────────────────────────────────────────────────────

/**
 * 64-bit difference hash from a 9×8 grayscale image (row-major, 72 bytes):
 * bit = left pixel brighter than its right neighbour, 8 per row. Returned as
 * a SIGNED 64-bit bigint so it fits a Postgres bigint column.
 */
export function dhashFromGray9x8(pixels: Uint8Array | number[]): bigint {
  if (pixels.length < 72) throw new Error(`dHash needs 72 pixels, got ${pixels.length}`)
  let hash = 0n
  for (let y = 0; y < 8; y++) {
    for (let x = 0; x < 8; x++) {
      hash = (hash << 1n) | (pixels[y * 9 + x] > pixels[y * 9 + x + 1] ? 1n : 0n)
    }
  }
  return BigInt.asIntN(64, hash)
}

export function hammingDistance64(a: bigint, b: bigint): number {
  let x = BigInt.asUintN(64, a ^ b)
  let count = 0
  while (x) {
    x &= x - 1n
    count++
  }
  return count
}

// ── EXIF ────────────────────────────────────────────────────────────────────

const TAG_DATETIME = 0x0132
const TAG_EXIF_IFD = 0x8769
const TAG_DATETIME_ORIGINAL = 0x9003
const TAG_OFFSET_TIME_ORIGINAL = 0x9011

type Ifd = Map<number, { type: number; count: number; valueOffset: number; entryOffset: number }>

function readIfd(tiff: Buffer, offset: number, le: boolean): Ifd {
  const out: Ifd = new Map()
  if (offset < 8 || offset + 2 > tiff.length) return out
  const n = le ? tiff.readUInt16LE(offset) : tiff.readUInt16BE(offset)
  for (let i = 0; i < n && i < 512; i++) {
    const e = offset + 2 + i * 12
    if (e + 12 > tiff.length) break
    const u16 = (o: number) => (le ? tiff.readUInt16LE(o) : tiff.readUInt16BE(o))
    const u32 = (o: number) => (le ? tiff.readUInt32LE(o) : tiff.readUInt32BE(o))
    out.set(u16(e), { type: u16(e + 2), count: u32(e + 4), valueOffset: u32(e + 8), entryOffset: e + 8 })
  }
  return out
}

function readAscii(tiff: Buffer, entry: { type: number; count: number; valueOffset: number; entryOffset: number } | undefined): string | null {
  if (!entry || entry.type !== 2 || entry.count === 0 || entry.count > 64) return null
  const start = entry.count <= 4 ? entry.entryOffset : entry.valueOffset
  if (start + entry.count > tiff.length) return null
  return tiff.subarray(start, start + entry.count).toString('latin1').replace(/\0+$/, '')
}

/**
 * EXIF "YYYY:MM:DD HH:MM:SS" (+ optional "+HH:MM" offset) → ISO string.
 * Without an offset the time is read as UTC (see EXIF_STALE_BEFORE_WORK_MS).
 */
export function parseExifDateTime(value: string | null, offset: string | null = null): string | null {
  if (!value) return null
  const m = /^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(value.trim())
  if (!m) return null
  const tz = offset && /^[+-]\d{2}:\d{2}$/.test(offset.trim()) ? offset.trim() : 'Z'
  const ms = Date.parse(`${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6]}${tz}`)
  if (Number.isNaN(ms) || m[1] === '0000') return null
  return new Date(ms).toISOString()
}

/**
 * Capture time from a raw EXIF block (what `sharp().metadata().exif`
 * returns: optional "Exif\0\0" header, then a TIFF structure).
 * DateTimeOriginal wins; IFD0 DateTime is the fallback. Null when absent or
 * malformed — never throws.
 */
export function exifCaptureTime(exif: Buffer | null | undefined): string | null {
  try {
    if (!exif || exif.length < 14) return null
    const tiff = exif.subarray(0, 6).toString('latin1') === 'Exif\0\0' ? exif.subarray(6) : exif
    const order = tiff.subarray(0, 2).toString('latin1')
    if (order !== 'II' && order !== 'MM') return null
    const le = order === 'II'
    if ((le ? tiff.readUInt16LE(2) : tiff.readUInt16BE(2)) !== 42) return null
    const ifd0 = readIfd(tiff, le ? tiff.readUInt32LE(4) : tiff.readUInt32BE(4), le)
    const exifPtr = ifd0.get(TAG_EXIF_IFD)
    if (exifPtr) {
      const sub = readIfd(tiff, exifPtr.valueOffset, le)
      const original = parseExifDateTime(
        readAscii(tiff, sub.get(TAG_DATETIME_ORIGINAL)),
        readAscii(tiff, sub.get(TAG_OFFSET_TIME_ORIGINAL)),
      )
      if (original) return original
    }
    return parseExifDateTime(readAscii(tiff, ifd0.get(TAG_DATETIME)))
  } catch {
    return null
  }
}

// ── Rules ───────────────────────────────────────────────────────────────────

export type FingerprintFacts = {
  url: string
  status: 'ok' | 'unverified'
  sha256: string | null
  dhash: bigint | null
  exifCapturedAt: string | null
  firstJobId: number | null
  firstScanId: number | null
}

/** What a photo is being submitted as evidence for. */
export type EvidenceContext = {
  kind: 'job' | 'scan'
  id: number
  /** When the work started (job claim / scan start) — for the EXIF check. */
  workStartedAt: string | null
}

/** Another fingerprint row: same sha256, or a dHash near-duplicate. */
export type OtherPhoto = {
  url: string
  firstJobId: number | null
  firstScanId: number | null
  /** 0 for an exact (sha256) match. */
  distance: number
}

export type PhotoVerdict = {
  flags: PhotoFlag[]
  /** Set only for exact reuse across different jobs — the one hard refusal. */
  reject: string | null
}

function boundElsewhere(other: Pick<OtherPhoto, 'firstJobId' | 'firstScanId'>, ctx: EvidenceContext) {
  return {
    otherJob: ctx.kind === 'job' && other.firstJobId != null && other.firstJobId !== ctx.id,
    otherScan: ctx.kind === 'scan' && other.firstScanId != null && other.firstScanId !== ctx.id,
    crossKind:
      (ctx.kind === 'job' && other.firstJobId == null && other.firstScanId != null) ||
      (ctx.kind === 'scan' && other.firstScanId == null && other.firstJobId != null),
  }
}

/**
 * Flags for one photo submitted in `ctx`. `self` is its own fingerprint row
 * (already bound to a job/scan if it was submitted before); `exact` are other
 * rows with the same sha256; `near` are rows within NEAR_DUPLICATE_MAX_HAMMING.
 */
export function evaluatePhoto(
  self: FingerprintFacts,
  ctx: EvidenceContext,
  exact: OtherPhoto[],
  near: OtherPhoto[],
): PhotoVerdict {
  const flags = new Set<PhotoFlag>()
  let reject: string | null = null
  if (self.status !== 'ok') flags.add('unverified')

  for (const other of [{ ...self, distance: 0 }, ...exact]) {
    const where = boundElsewhere(other, ctx)
    if (where.otherJob) {
      flags.add('reused_other_job')
      reject = `This photo was already submitted as evidence for job #${other.firstJobId}. Take a new photo for this job.`
    }
    if (where.otherScan) flags.add('reused_other_scan')
    if (where.crossKind) flags.add('reused_across_job_and_scan')
  }

  for (const other of near) {
    if (other.distance > NEAR_DUPLICATE_MAX_HAMMING) continue
    const where = boundElsewhere(other, ctx)
    if (where.otherJob || where.otherScan) flags.add('near_duplicate_other_work')
  }

  if (self.exifCapturedAt && ctx.workStartedAt) {
    const shot = Date.parse(self.exifCapturedAt)
    const started = Date.parse(ctx.workStartedAt)
    if (!Number.isNaN(shot) && !Number.isNaN(started) && shot < started - EXIF_STALE_BEFORE_WORK_MS) {
      flags.add('exif_before_work')
    }
  }

  return { flags: [...flags], reject }
}

/** Flags for a before/after pair of one piece of work. */
export function evaluateBeforeAfter(before: FingerprintFacts, after: FingerprintFacts): PhotoFlag[] {
  if (before.url === after.url || (before.sha256 && before.sha256 === after.sha256)) return ['identical_before_after']
  if (before.dhash != null && after.dhash != null && hammingDistance64(before.dhash, after.dhash) <= NEAR_DUPLICATE_MAX_HAMMING) {
    return ['near_duplicate_before_after']
  }
  return []
}

/** Flags that stop a cleaned hotspot from paying (its after photo proves nothing new). */
export const NON_PAYING_PHOTO_FLAGS: ReadonlySet<string> = new Set<PhotoFlag>([
  'reused_other_job',
  'reused_other_scan',
  'identical_before_after',
])
