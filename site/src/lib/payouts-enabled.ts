/**
 * PAYOUTS MASTER SWITCH.
 *
 * `PAYOUTS_ENABLED` gates every route that can move money out of the platform
 * or credit a worker's wallet. It is OFF unless the env var is explicitly one
 * of "true" / "1" / "yes" / "on" (case-insensitive) — an unset, empty or
 * misspelled value keeps payouts paused, so a missing config can never
 * release money.
 *
 * Usage, first statement of the handler (before auth, parsing or any DB read):
 *
 *   const paused = payoutsPausedResponse()
 *   if (paused) return paused
 */
import { NextResponse } from 'next/server'

const ON_VALUES = new Set(['true', '1', 'yes', 'on'])

export function payoutsEnabled(env: Record<string, string | undefined> = process.env): boolean {
  const raw = env.PAYOUTS_ENABLED
  return typeof raw === 'string' && ON_VALUES.has(raw.trim().toLowerCase())
}

export const PAYOUTS_PAUSED_ERROR = 'Payouts are paused'

/** A 503 `{ error: "Payouts are paused" }` when the switch is off, else null. */
export function payoutsPausedResponse(
  env: Record<string, string | undefined> = process.env,
): NextResponse | null {
  if (payoutsEnabled(env)) return null
  return NextResponse.json({ error: PAYOUTS_PAUSED_ERROR }, { status: 503 })
}
