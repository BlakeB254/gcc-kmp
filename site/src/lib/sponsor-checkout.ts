/**
 * Rules for the $100 "Sponsor a Block" checkout (/sponsor ->
 * POST /api/contribute/checkout).
 *
 * A sponsor can always pay, whether or not a block is ready to assign:
 *
 *   - chosen blocks are all `available`  -> `blockIds`            (block sponsorship)
 *   - "let GCC choose", enough available -> `allocationType:area` (auto-assigned)
 *   - anything else                      -> `reservation`         (next cleaning cycle)
 *
 * "Anything else" includes a block that has already been cleaned: every block
 * can be sponsored again at any time, and the money buys its next cleaning.
 *
 * A reservation is charged at $100 a block and recorded as a general-fund
 * contribution tagged with the reserved count (and block codes, if the
 * sponsor picked upcoming route blocks), so an admin can allocate it once
 * blocks are scheduled. Before this existed, a sponsor who arrived while no
 * block was `available` could not check out at all.
 *
 * Pure — no `@/db` or Stripe import — so it runs under `tsx --test`.
 */

export const BLOCK_PRICE_DOLLARS = 100
/** Upper bound on one reservation; larger gifts go through the package builder. */
const MAX_RESERVED_BLOCKS = 10000
const BLOCK_CODE_RE = /^[A-Za-z0-9][A-Za-z0-9-]{0,31}$/

/** GET /api/blocks returns its list under `data`; older callers read `blocks`. */
export function blocksFromApi<T = unknown>(body: unknown): T[] {
  const b = body as { data?: unknown; blocks?: unknown } | null
  if (Array.isArray(b?.data)) return b.data as T[]
  if (Array.isArray(b?.blocks)) return b.blocks as T[]
  return []
}

export interface Reservation {
  blockCount: number
  /** Route block codes the sponsor asked for, when they picked upcoming blocks. */
  blockCodes?: string[]
}

interface SelectableBlock {
  id: number | string
  code?: string
  status?: string
}

/** Only an `available` block can be funded and released to a worker right now. */
export function isAssignableNow(block: { status?: string }): boolean {
  return block.status === 'available'
}

/** A block id the database knows: a positive integer, as a number or a string. */
function realBlockId(id: number | string): number | null {
  const n = typeof id === 'number' ? id : /^\d+$/.test(id) ? Number(id) : NaN
  return Number.isInteger(n) && n > 0 ? n : null
}

/**
 * The request body for POST /api/contribute/checkout, or null when there is
 * nothing to check out yet (specific mode, no block picked).
 */
export function buildSponsorCheckoutPayload<S extends object>(input: {
  mode: 'specific' | 'general'
  selectedBlocks: SelectableBlock[]
  generalBlockCount: number
  /** Blocks that can be assigned right now. */
  availableBlockCount: number
  /** Fields sent with every kind of checkout (name, email, frequency, ...). */
  shared: S
}):
  | (S & { blockIds: number[] })
  | (S & { allocationType: 'area'; blockCount: number })
  | (S & { reservation: Reservation })
  | null {
  const { mode, selectedBlocks, generalBlockCount, availableBlockCount, shared } = input

  if (mode === 'specific') {
    if (selectedBlocks.length === 0) return null
    const ids = selectedBlocks.map((b) => (isAssignableNow(b) ? realBlockId(b.id) : null))
    if (ids.every((id): id is number => id !== null)) return { ...shared, blockIds: ids }
    // A block that is already cleaned or in progress, or an upcoming route
    // block with no database row yet, is reserved by code for its next cycle.
    const blockCodes = selectedBlocks.map((b) => b.code).filter((c): c is string => !!c)
    return { ...shared, reservation: { blockCount: selectedBlocks.length, blockCodes } }
  }

  if (generalBlockCount <= availableBlockCount) {
    return { ...shared, allocationType: 'area', blockCount: generalBlockCount }
  }
  return { ...shared, reservation: { blockCount: generalBlockCount } }
}

/**
 * Server-side reading of a `reservation` from the request body. The amount is
 * always computed here from the block count, never taken from the client.
 */
export function parseReservation(
  raw: unknown,
): { blockCount: number; blockCodes: string[]; amountCents: number } | null {
  const r = raw as { blockCount?: unknown; blockCodes?: unknown } | null | undefined
  const blockCount = r?.blockCount
  if (
    typeof blockCount !== 'number' ||
    !Number.isInteger(blockCount) ||
    blockCount < 1 ||
    blockCount > MAX_RESERVED_BLOCKS
  ) {
    return null
  }
  const blockCodes = Array.isArray(r?.blockCodes)
    ? r.blockCodes.filter((c): c is string => typeof c === 'string' && BLOCK_CODE_RE.test(c))
    : []
  return { blockCount, blockCodes, amountCents: blockCount * BLOCK_PRICE_DOLLARS * 100 }
}
