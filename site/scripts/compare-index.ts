/**
 * Run the ported scan indexer against the real R2 bucket, READ-ONLY, into a
 * LOCAL Postgres, and compare the counts with what prod Neon holds from the
 * Kotlin indexer. One command once R2 credentials are in the environment:
 *
 *   cd site && npm i --no-save pg@8
 *   R2_ACCOUNT_ID=… R2_ACCESS_KEY_ID=… R2_SECRET_ACCESS_KEY=… R2_BUCKET_NAME=gcc-scan-storage \
 *   LOCAL_DATABASE_URL=postgres://gcc:gcc@127.0.0.1:56811/gcc_index \
 *     npx tsx scripts/compare-index.ts [--reset]
 *
 * Only R2 List/Get are issued (lib/scan-index/deps.ts r2Reader). The target
 * database must be on localhost and its scan tables empty (or pass --reset to
 * empty them first). Expected prod figures can be overridden with
 * EXPECT_SESSIONS / EXPECT_CHUNKS / EXPECT_ZERO_CELL / EXPECT_JOBS.
 */
import { PgDialect } from 'drizzle-orm/pg-core'
import type { SQL } from 'drizzle-orm'
import { scanIndexDb, watchdogDb, type SqlExec } from '../src/lib/scan-index/db'
import { runScanIndex } from '../src/lib/scan-index/indexer'
import { grantCutoverMs, grantsFor } from '../src/lib/scan-index/grants'
import { listR2, r2Reader } from '../src/lib/scan-index/deps'
import { runWatchdog } from '../src/lib/scan-index/watchdog'
import { isR2Configured } from '../src/lib/r2'

// Prod Neon on 2026-10-18 (Kotlin indexer, last run ~09-08).
const EXPECTED = {
  sessions: Number(process.env.EXPECT_SESSIONS ?? 173),
  chunks: Number(process.env.EXPECT_CHUNKS ?? 670),
  zeroCell: Number(process.env.EXPECT_ZERO_CELL ?? 58),
  jobs: Number(process.env.EXPECT_JOBS ?? 39),
}

async function main() {
  const url = process.env.LOCAL_DATABASE_URL
  if (!url || !/@(127\.0\.0\.1|localhost)(:\d+)?\//.test(url)) {
    throw new Error('LOCAL_DATABASE_URL must point at a localhost Postgres — this never writes to Neon')
  }
  if (!isR2Configured()) throw new Error('R2_ACCOUNT_ID / R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY / R2_BUCKET_NAME are required')

  const pg = (await import('pg')).default
  const pool = new pg.Pool({ connectionString: url })
  const dialect = new PgDialect()
  const exec: SqlExec = async (q: SQL) => {
    const { sql, params } = dialect.sqlToQuery(q)
    return (await pool.query(sql, params as unknown[])).rows
  }
  const count = async (q: string) => Number((await pool.query(q)).rows[0].n)

  try {
    // scan_manifest_rejects needs migration 0052 applied to the local DB.
    const tables = ['reconstruction_jobs', 'capture_chunks', 'scan_sessions', 'reconstruction_assets', 'scan_manifest_rejects']
    if (process.argv.includes('--reset')) for (const t of tables) await pool.query(`delete from ${t}`)
    for (const t of tables) {
      const n = await count(`select count(*) as n from ${t}`)
      if (n > 0) throw new Error(`${t} has ${n} rows; pass --reset to empty the local scan tables first`)
    }

    const result = await runScanIndex(
      {
        ...r2Reader,
        ...scanIndexDb(exec),
        // The local DB has no grants, so every prod manifest is judged by the legacy rules.
        grantCutoverMs: () => grantCutoverMs(exec),
        grantsFor: (keys) => grantsFor(exec, keys),
        log: (m) => console.error(`  ${m}`),
      },
      { deadlineMs: Number.POSITIVE_INFINITY, full: true },
    )
    console.log('indexer result:', JSON.stringify(result, null, 2))

    const got = {
      sessions: await count('select count(*) as n from scan_sessions'),
      chunks: await count('select count(*) as n from capture_chunks'),
      zeroCell: await count(`select count(*) as n from scan_sessions where location_cell_id = '0:0'`),
      jobs: await count('select count(*) as n from reconstruction_jobs'),
      reconAssets: await count('select count(*) as n from reconstruction_assets'),
      withMetrics: await count('select count(*) as n from scan_sessions where event_count is not null'),
    }
    console.log('\n            local   prod   diff')
    for (const k of ['sessions', 'chunks', 'zeroCell', 'jobs'] as const) {
      console.log(`${k.padEnd(10)} ${String(got[k]).padStart(7)} ${String(EXPECTED[k]).padStart(6)} ${String(got[k] - EXPECTED[k]).padStart(6)}`)
    }
    console.log(`reconstruction_assets ${got.reconAssets}, sessions with event metrics ${got.withMetrics}`)
    console.log('(jobs: local enqueues onto an empty queue with no reconstruction_version set, so it is the would-enqueue set, not prod history)')

    // The watchdog over the result, twice: new rejects alert once, then it is green.
    const watchdog = { ...watchdogDb(exec), listRaw: () => listR2('raw/') }
    for (const pass of [1, 2]) {
      const w = await runWatchdog(watchdog)
      console.log(`watchdog pass ${pass}: healthy=${w.healthy} scanIndex=${w.scanIndex.ok} (${w.scanIndex.detail}) ` +
        `rejected=${w.rejectedManifests.ok} (${w.rejectedManifests.detail}) civic=${w.civicSync.ok} recon=${w.reconQueue.ok}`)
    }
  } finally {
    await pool.end()
  }
}

main().catch((e) => {
  console.error(e instanceof Error ? e.message : e)
  process.exit(1)
})
