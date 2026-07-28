import { drizzle } from 'drizzle-orm/neon-http'
import { neon } from '@neondatabase/serverless'
import * as schema from './schema'

// Lazily initialized: reading DATABASE_URL at module-evaluation time made
// `next build` throw while collecting page data for any route that imports
// db — which failed EVERY Vercel Preview deployment, because only the
// Production environment defines DATABASE_URL (2026-07-28: all branch
// previews ERROR at "Failed to collect configuration for /api/...", all
// production deploys READY). Deferring to first use lets previews build; a
// runtime request without the env var still fails loudly with the same
// message.
function initDb() {
  const databaseUrl = process.env.DATABASE_URL || process.env.DATABASE_URI
  if (!databaseUrl) {
    throw new Error('DATABASE_URL or DATABASE_URI environment variable is required')
  }
  // Neon serverless client (works with Vercel Edge/Serverless)
  return drizzle({ client: neon(databaseUrl), schema })
}

let instance: ReturnType<typeof initDb> | null = null

export const db: ReturnType<typeof initDb> = new Proxy({} as ReturnType<typeof initDb>, {
  get(_target, prop) {
    if (!instance) instance = initDb()
    const value = instance[prop as keyof typeof instance]
    // Bind methods to the real instance — drizzle internals rely on `this`,
    // and an unbound method invoked through the proxy would get the proxy
    // as its receiver.
    return typeof value === 'function'
      ? (value as (...args: unknown[]) => unknown).bind(instance)
      : value
  },
})
