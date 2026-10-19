/**
 * civic_reports SQL for the 311 mirror — the exact upsert CivicReportSync.kt
 * used, against a bare SqlExec (neon-http in production, plain Postgres in
 * local verification).
 */
import { sql } from 'drizzle-orm'
import type { SqlExec } from './db'
import type { CivicRow, CivicSyncDeps } from './civic-sync'

const ts = (ms: number | null) => (ms == null ? null : new Date(ms).toISOString())

export function civicSyncDb(exec: SqlExec): Pick<CivicSyncDeps, 'currentWatermarkMs' | 'upsert'> {
  return {
    async currentWatermarkMs() {
      const rows = await exec(sql`
        select (extract(epoch from max(last_modified_date)) * 1000)::float8 as ms from civic_reports
      `)
      const ms = rows[0]?.ms
      return ms == null ? null : Number(ms)
    },

    async upsert(rows: CivicRow[]) {
      if (rows.length === 0) return 0
      const values = rows.map((r) => sql`(${r.srNumber}, ${r.srType}, ${r.status}, ${r.ownerDepartment},
        ${ts(r.createdDateMs)}::timestamptz, ${ts(r.lastModifiedDateMs)}::timestamptz, ${ts(r.closedDateMs)}::timestamptz,
        ${r.streetAddress}, ${r.communityArea}::int, ${r.lat}::float8, ${r.lng}::float8, ${r.locationCellId}, now())`)
      const res = await exec(sql`
        with up as (
          insert into civic_reports
            (sr_number, sr_type, status, owner_department, created_date, last_modified_date,
             closed_date, street_address, community_area, lat, lng, location_cell_id, synced_at)
          values ${sql.join(values, sql`, `)}
          on conflict (sr_number) do update set
             sr_type = excluded.sr_type,
             status = excluded.status,
             owner_department = excluded.owner_department,
             created_date = excluded.created_date,
             last_modified_date = excluded.last_modified_date,
             closed_date = excluded.closed_date,
             street_address = excluded.street_address,
             community_area = excluded.community_area,
             lat = excluded.lat,
             lng = excluded.lng,
             location_cell_id = excluded.location_cell_id,
             synced_at = now()
          returning 1
        )
        select count(*)::int as n from up
      `)
      return Number(res[0]?.n ?? 0)
    },
  }
}
