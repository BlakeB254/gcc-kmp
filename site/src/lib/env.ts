/**
 * Centralized accessors for environment variables that must be set in production.
 * Fails fast in prod when missing; falls back with a dev-only warning otherwise,
 * so developers running locally without a full .env don't get cryptic errors.
 */

function readPublicUrl(name: string, devFallback: string): string {
  const value = process.env[name]
  if (value) return value
  if (process.env.NODE_ENV === 'production') {
    throw new Error(`${name} environment variable must be set in production`)
  }
  if (process.env.NODE_ENV !== 'test') {
    console.warn(`[env] ${name} is not set — using dev fallback ${devFallback}`)
  }
  return devFallback
}

export function appUrl(): string {
  // Dev fallback is the canonical production URL — links generated without
  // local config (emails, share URLs, Stripe return URLs) should point at the
  // real site rather than a localhost port or a stale domain. Production
  // still fails fast when the var is unset.
  return readPublicUrl('NEXT_PUBLIC_APP_URL', 'https://globalcommunitycleaners.org')
}

export function tileServerUrl(): string {
  return readPublicUrl('NEXT_PUBLIC_TILE_SERVER_URL', 'http://localhost:7400')
}
