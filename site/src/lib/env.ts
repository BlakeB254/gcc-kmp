/**
 * Centralized accessors for environment variables that must be set in production.
 * Fails fast in prod when missing; falls back with a dev-only warning otherwise,
 * so developers running locally without a full .env don't get cryptic errors.
 */

// `value` must be passed in as a literal `process.env.NEXT_PUBLIC_X` read at
// the call site. Next.js only substitutes NEXT_PUBLIC_ variables into the
// browser bundle when they are written out literally; looking one up by a
// computed key here is undefined in the browser, and the production guard
// below then throws on the visitor's machine and takes the page down.
function readPublicUrl(name: string, value: string | undefined, devFallback: string): string {
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
  return readPublicUrl('NEXT_PUBLIC_APP_URL', process.env.NEXT_PUBLIC_APP_URL, 'https://globalcommunitycleaners.org')
}

export function tileServerUrl(): string {
  return readPublicUrl('NEXT_PUBLIC_TILE_SERVER_URL', process.env.NEXT_PUBLIC_TILE_SERVER_URL, 'http://localhost:7400')
}
