import { drizzle } from 'drizzle-orm/neon-http'
import { neon } from '@neondatabase/serverless'
import * as schema from './schema'

// Get database URL (support both env var names)
const databaseUrl = process.env.DATABASE_URL || process.env.DATABASE_URI

if (!databaseUrl) {
  throw new Error('DATABASE_URL or DATABASE_URI environment variable is required')
}

// Create Neon serverless client (works with Vercel Edge/Serverless)
const sql = neon(databaseUrl)

// Create drizzle instance with Neon HTTP driver
export const db = drizzle({ client: sql, schema })
