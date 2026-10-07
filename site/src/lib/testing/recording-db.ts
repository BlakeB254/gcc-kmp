/**
 * Test-only drizzle instance (pg-proxy driver) that records every statement
 * and answers from a script instead of a database. Lets unit tests assert on
 * the exact SQL a money path would run, and simulate "0 rows" races.
 *
 * Rows are returned in pg-proxy's array form: one array of column values per
 * row, in the order of the query's selected/returning columns.
 */
import { drizzle } from 'drizzle-orm/pg-proxy'
import * as schema from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'

export interface RecordedQuery {
  sql: string
  params: unknown[]
}

export type Responder = (q: RecordedQuery) => unknown[][] | undefined

export function recordingDb(...responders: Responder[]) {
  const queries: RecordedQuery[] = []
  const db = drizzle(
    async (sql, params) => {
      const q = { sql, params }
      queries.push(q)
      for (const r of responders) {
        const rows = r(q)
        if (rows) return { rows }
      }
      return { rows: [] }
    },
    { schema },
  )
  return { db: db as unknown as DbExecutor, queries }
}

/** Answer statements whose SQL matches `pattern` with `rows`. */
export function when(pattern: RegExp, rows: unknown[][]): Responder {
  return (q) => (pattern.test(q.sql) ? rows : undefined)
}
