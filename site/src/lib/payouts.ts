/**
 * Shared helpers for ad-hoc admin payouts (spec:
 * docs/superpowers/specs/2026-07-06-admin-payouts-design.md).
 */

/** Max ad-hoc payout: $1,000. Mirrors the spirit of MAX_GRANT on points. */
export const MAX_PAYOUT_CENTS = 100_000
export const MAX_NOTE_LEN = 280

export interface MailingAddress {
  name: string
  street: string
  city: string
  state: string
  zip: string
}

function nonEmpty(v: unknown): v is string {
  return typeof v === 'string' && v.trim().length > 0 && v.trim().length <= 200
}

/**
 * Parse a client-supplied mailing address. Returns null when the shape is
 * incomplete — callers decide whether that's a 400 or "fall back to home
 * address".
 */
export function parseMailingAddress(raw: unknown, fallbackName?: string): MailingAddress | null {
  if (!raw || typeof raw !== 'object') return null
  const a = raw as Record<string, unknown>
  const name = nonEmpty(a.name) ? a.name.trim() : fallbackName?.trim()
  if (!name || !nonEmpty(a.street) || !nonEmpty(a.city) || !nonEmpty(a.state) || !nonEmpty(a.zip)) {
    return null
  }
  return {
    name,
    street: a.street.trim(),
    city: a.city.trim(),
    state: a.state.trim(),
    zip: a.zip.trim(),
  }
}

/**
 * Where a check for this worker should go: their explicit payout mailing
 * address override when set, else their home address on file. Null when
 * neither is complete enough to mail to.
 */
export function resolveMailingAddress(worker: {
  name: string
  payoutMailingAddress: unknown
  addressStreet: string | null
  addressCity: string | null
  addressState: string | null
  addressZip: string | null
}): MailingAddress | null {
  const override = parseMailingAddress(worker.payoutMailingAddress, worker.name)
  if (override) return override
  return parseMailingAddress(
    {
      name: worker.name,
      street: worker.addressStreet,
      city: worker.addressCity,
      state: worker.addressState,
      zip: worker.addressZip,
    },
    worker.name
  )
}
