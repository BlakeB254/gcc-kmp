/**
 * Upload grants: who was issued each raw/ capture key.
 *
 * R2 object keys are built from client-chosen segments (deviceId, sessionId),
 * so a key on its own proves nothing about who uploaded it. Every presigned
 * PUT that /api/captures/upload-url and /api/scans/[id]/upload-url issue is
 * recorded in `capture_upload_grants` against the authenticated worker, and
 * only if no object is at the key yet. A session folder is bound to the first
 * worker granted there (`capture_namespace_owners`, keyed on the folder
 * segment itself); a device folder is NOT — test handsets are shared between
 * accounts — so device-folder keys are owned key by key (they carry the
 * session id, so two workers only collide on a real clash). The indexer then:
 *
 *  - accepts a manifest written after the cutover only if its own key was
 *    granted to one worker, keeps only keys granted to that worker, and stamps
 *    scan_sessions.worker_id;
 *  - for a legacy session, accepts objects written after the cutover only if
 *    they were granted to one single worker (no late, ungranted additions);
 *  - in both cases counts a grant only if the object was written while the
 *    grant's URL could have been used: between issued_at and last_issued_at +
 *    URL TTL, with clock skew either side.
 *
 * neon-http runs every statement as its own transaction, so the namespace
 * check and the grant write are ONE statement (data-modifying CTEs).
 */
import { sql } from 'drizzle-orm'
import type { SqlExec } from './db'
import { withoutTable } from './db'
import type { ObjectInfo } from './indexer'
import type { KeyGate, KeyNamespace, ScanManifest } from './manifest'
import { isSessionId } from '@/lib/r2'

export interface UploadGrant {
  workerId: number
  deviceId: string | null
  sessionId: string | null
  issuedAtMs: number
  lastIssuedAtMs: number
}

/**
 * Manifests whose object was written at or after the first grant plus this
 * grace are post-cutover and must be granted. The grace covers upload URLs
 * presigned just before the deploy (30 min TTL) whose PUT lands after it.
 */
export const GRANT_CUTOVER_GRACE_MS = 2 * 60 * 60 * 1000
/** Longest presigned-PUT TTL either upload route issues (captures: 1800s). */
export const PRESIGN_TTL_MAX_SECONDS = 1800
/**
 * Clock skew between R2 and Postgres, plus a slow PUT that started just before
 * its URL expired (R2 checks expiry at request start; LastModified is set when
 * the upload completes).
 */
export const GRANT_SKEW_SECONDS = 15 * 60
/** A grant whose URL has expired with no object behind it may be re-granted. */
export const GRANT_EXPIRY_SECONDS = PRESIGN_TTL_MAX_SECONDS + GRANT_SKEW_SECONDS

const GRANTS_MIGRATION = '0054'

export type GrantOutcome = 'granted' | 'namespace-owned' | 'key-owned' | 'object-exists' | 'unavailable'

/** What R2 says is at a key: a clean 404 is `absent`; any error is `unknown`. */
export type ObjectState = 'exists' | 'absent' | 'unknown'

/** R2's answer for a key, with the object's LastModified when it exists. */
export interface ObjectProbe {
  state: ObjectState
  lastModifiedMs?: number | null
}

/**
 * How an upload route answers a refused grant. The app's uploaders
 * (CaptureChunkUploader.isClientErrorMessage) delete a chunk for good on any
 * 4xx and keep it for a later retry on anything else, so:
 *  - `unavailable` (R2 couldn't say whether the key is free) and
 *    `namespace-owned` (another account's session — on a shared handset that
 *    account flushes it later) are 503 + Retry-After: keep the file;
 *  - `key-owned` / `object-exists` are real collisions: 409.
 */
export const RETRY_AFTER_SECONDS = 300
export function grantRefusal(outcome: GrantOutcome): { status: 409 | 503; message: string; retryAfter?: number } | null {
  switch (outcome) {
    case 'granted': return null
    case 'unavailable':
      return { status: 503, message: 'Upload storage is briefly unavailable — retry later', retryAfter: RETRY_AFTER_SECONDS }
    case 'namespace-owned':
      return { status: 503, message: "This scan session belongs to another account on this device — keep it for that account's next sign-in", retryAfter: RETRY_AFTER_SECONDS }
    case 'key-owned':
    case 'object-exists':
      return { status: 409, message: 'This upload location is already taken' }
  }
}

/**
 * Grant `r2Key` to `workerId`, in one statement (neon-http: no multi-statement
 * transactions):
 *  1. the key's folder segment (`folder`: the session id or the device id as it
 *     appears in the key) is looked up in capture_namespace_owners, keyed on
 *     the segment string alone — so a device-shaped request can't write into a
 *     folder someone owns as their session. A session-shaped request
 *     (`claimFolder`) also claims an unowned segment; a device-shaped one never
 *     does (test handsets are shared between accounts);
 *  2. the grant is inserted only if no object exists at the key yet — an
 *     object that predates every grant can't be claimed (legacy data stays
 *     unowned) — with two exceptions: the same worker already holds a grant on
 *     this exact key (their own retry), or, for a session-folder key only, the
 *     worker owned that session BEFORE this statement (the subquery reads the
 *     pre-statement snapshot, never the row this statement inserts) and since
 *     before the object was written. Folder ownership alone proves nothing
 *     about an older object. It is also refreshed for the same worker, or
 *     taken over when the old grant's URL expired with no object behind it.
 *  For a device-shaped key, the device folder's owner and the related
 *  session's owner are checked independently: each must be unset or the
 *  requester.
 * A missing table (migration 0054 not applied yet) is logged and treated as
 * granted, so uploads keep working through the deploy.
 */
export async function recordUploadGrant(
  exec: SqlExec,
  g: {
    r2Key: string
    workerId: number
    deviceId: string | null
    sessionId: string | null
    /** The key's folder segment (sessionId for the session shape, deviceId otherwise). */
    folder: string
    /**
     * A session the device-shaped key belongs to by name (the manifest,
     * `{sessionId}-manifest.json`): refused while another worker owns it.
     */
    relatedSession?: string | null
    /** Claim the folder if unowned: true for session-folder keys only. */
    claimFolder: boolean
    /** Whether an object already exists at the key (R2 HEAD); unknown = true. */
    objectExists: boolean
    /** That object's LastModified, when it exists. */
    objectLastModifiedMs?: number | null
  },
): Promise<GrantOutcome> {
  return withoutTable('capture_upload_grants', GRANTS_MIGRATION, async () => {
    const rows = await exec(sql`
      with ns as (
        ${g.claimFolder
          ? sql`insert into capture_namespace_owners (ns_id, worker_id)
                values (${g.folder}, ${g.workerId}::int)
                on conflict (ns_id) do update set ns_id = excluded.ns_id
                returning worker_id`
          : sql`select case
                    when (o.folder_owner is null or o.folder_owner = ${g.workerId}::int)
                     and (o.related_owner is null or o.related_owner = ${g.workerId}::int)
                    then ${g.workerId}::int
                    else coalesce(nullif(o.folder_owner, ${g.workerId}::int), o.related_owner)
                  end as worker_id
                from (select
                  (select worker_id from capture_namespace_owners where ns_id = ${g.folder}) as folder_owner,
                  (select worker_id from capture_namespace_owners where ns_id = ${g.relatedSession ?? null}) as related_owner
                ) o`}
      ), prior as (
        select worker_id from capture_upload_grants where r2_key = ${g.r2Key}
      ), granted as (
        insert into capture_upload_grants (r2_key, worker_id, device_id, session_id)
        select ${g.r2Key}, ${g.workerId}::int, ${g.deviceId}, ${g.sessionId}
        from ns
        where ns.worker_id = ${g.workerId}::int
          and (not ${g.objectExists}::boolean
               or exists (select 1 from prior where prior.worker_id = ${g.workerId}::int)
               or (${g.claimFolder}::boolean and exists (
                     select 1 from capture_namespace_owners o
                     where o.ns_id = ${g.folder} and o.worker_id = ${g.workerId}::int
                       and o.first_granted_at < ${g.objectLastModifiedMs == null ? null : new Date(g.objectLastModifiedMs).toISOString()}::timestamptz)))
        on conflict (r2_key) do update set
          worker_id = excluded.worker_id,
          device_id = excluded.device_id,
          session_id = excluded.session_id,
          issued_at = case when capture_upload_grants.worker_id = excluded.worker_id
                           then capture_upload_grants.issued_at else now() end,
          last_issued_at = now()
        where capture_upload_grants.worker_id = excluded.worker_id
           or (capture_upload_grants.last_issued_at < now() - make_interval(secs => ${GRANT_EXPIRY_SECONDS}::int)
               and not ${g.objectExists}::boolean)
        returning r2_key
      )
      select (select worker_id from ns) as owner,
             (select count(*) from granted)::int as granted,
             (select count(*) from prior)::int as had_grant
    `)
    const r = rows[0]
    if (Number(r?.owner) !== g.workerId) return 'namespace-owned'
    if (Number(r?.granted ?? 0) > 0) return 'granted'
    return g.objectExists && Number(r?.had_grant ?? 0) === 0 ? 'object-exists' : 'key-owned'
  }, 'granted' as GrantOutcome)
}

/**
 * What the upload routes call. R2 is asked first whether an object is already
 * at the key; the answer feeds the single grant statement, which refuses a
 * first grant over someone else's existing object and an expired-grant
 * takeover over a used key. If R2 can't answer, nothing is decided:
 * `unavailable` (retryable), and no grant is written.
 */
export async function grantUploadKey(
  exec: SqlExec,
  g: Omit<Parameters<typeof recordUploadGrant>[1], 'objectExists' | 'objectLastModifiedMs'>,
  objectState: (key: string) => Promise<ObjectProbe>,
): Promise<GrantOutcome> {
  const probe = await objectState(g.r2Key)
  if (probe.state === 'unknown') return 'unavailable'
  return recordUploadGrant(exec, {
    ...g,
    objectExists: probe.state === 'exists',
    objectLastModifiedMs: probe.lastModifiedMs ?? null,
  })
}

/** `{sessionId}-manifest` capturedAt → the session the manifest belongs to. */
export function manifestSessionOf(capturedAt: string, ext: string): string | null {
  if (ext !== 'json' || !capturedAt.endsWith('-manifest')) return null
  const id = capturedAt.slice(0, -'-manifest'.length)
  return isSessionId(id) ? id : null
}

/** Epoch-ms from which manifests must be granted, or null before any grant exists. */
export async function grantCutoverMs(exec: SqlExec): Promise<number | null> {
  return withoutTable('capture_upload_grants', GRANTS_MIGRATION, async () => {
    const rows = await exec(sql`
      select (extract(epoch from min(issued_at)) * 1000)::float8 as first_ms from capture_upload_grants
    `)
    const first = rows[0]?.first_ms
    return first == null ? null : Number(first) + GRANT_CUTOVER_GRACE_MS
  }, null)
}

export async function grantsFor(exec: SqlExec, keys: string[]): Promise<Map<string, UploadGrant>> {
  const out = new Map<string, UploadGrant>()
  if (keys.length === 0) return out
  return withoutTable('capture_upload_grants', GRANTS_MIGRATION, async () => {
    for (let i = 0; i < keys.length; i += 1000) {
      const rows = await exec(sql`
        select r2_key, worker_id, device_id, session_id,
               (extract(epoch from issued_at) * 1000)::float8 as issued_ms,
               (extract(epoch from last_issued_at) * 1000)::float8 as last_issued_ms
        from capture_upload_grants
        where r2_key in ${keys.slice(i, i + 1000)}
      `)
      for (const r of rows) {
        out.set(String(r.r2_key), {
          workerId: Number(r.worker_id),
          deviceId: r.device_id == null ? null : String(r.device_id),
          sessionId: r.session_id == null ? null : String(r.session_id),
          issuedAtMs: Number(r.issued_ms),
          lastIssuedAtMs: Number(r.last_issued_ms),
        })
      }
    }
    return out
  }, out)
}

/** Was the object written while this grant's URL could have been used? */
export function grantCoversObject(g: UploadGrant, obj: ObjectInfo | undefined): boolean {
  if (!obj) return false
  const skew = GRANT_SKEW_SECONDS * 1000
  return obj.lastModifiedMs >= g.issuedAtMs - skew &&
    obj.lastModifiedMs <= g.lastIssuedAtMs + PRESIGN_TTL_MAX_SECONDS * 1000 + skew
}

/** The grant on `key` if it covers the object there and matches the namespace's id. */
function validGrant(
  key: string,
  ns: KeyNamespace,
  manifest: Pick<ScanManifest, 'deviceId' | 'sessionId'>,
  grants: Map<string, UploadGrant>,
  raw: Map<string, ObjectInfo>,
): UploadGrant | null {
  const g = grants.get(key)
  if (!g || !grantCoversObject(g, raw.get(key))) return null
  // The grant must be for the same folder the key sits in: a session-shaped
  // grant (session id set) for session keys, a device-shaped one for device
  // keys — never one shape vouching for the other's folder.
  const folder = key.split('/')[2]
  const ok = ns === 'device'
    ? g.sessionId == null && g.deviceId === manifest.deviceId && folder === g.deviceId
    : g.sessionId === manifest.sessionId && folder === g.sessionId
  return ok ? g : null
}

/**
 * Proof that the object at `key` was written after its grant was issued (no
 * skew allowance): the bar for stamping a worker onto an existing unowned row.
 */
export function writtenAfterGrant(key: string, grants: Map<string, UploadGrant>, raw: Map<string, ObjectInfo>): boolean {
  const g = grants.get(key)
  const obj = raw.get(key)
  return g != null && obj != null && obj.lastModifiedMs >= g.issuedAtMs
}

/**
 * The worker a post-cutover manifest belongs to: the holder of a grant on the
 * manifest's own key, for the device it names, that covers the object. Null
 * means the manifest must be refused.
 */
export function manifestOwner(
  manifestKey: string,
  manifest: ScanManifest,
  grants: Map<string, UploadGrant>,
  raw: Map<string, ObjectInfo>,
): number | null {
  return validGrant(manifestKey, 'device', manifest, grants, raw)?.workerId ?? null
}

/** Keys a post-cutover manifest may claim: validly granted to its own worker. */
export function grantGate(
  manifest: ScanManifest,
  workerId: number,
  grants: Map<string, UploadGrant>,
  raw: Map<string, ObjectInfo>,
): KeyGate {
  return (key, ns) => validGrant(key, ns, manifest, grants, raw)?.workerId === workerId
}

/**
 * A legacy (pre-cutover) session is frozen against late additions: an object
 * written before the cutover is accepted as always; one written after it only
 * if it is validly granted to the session's single grant owner (the one worker
 * holding valid grants on its late objects). Missing objects are judged as
 * before (no grant needed).
 */
export function legacyGate(
  manifest: ScanManifest,
  candidates: { key: string; ns: KeyNamespace }[],
  cutoverMs: number,
  grants: Map<string, UploadGrant>,
  raw: Map<string, ObjectInfo>,
): KeyGate {
  const late = (key: string) => (raw.get(key)?.lastModifiedMs ?? -Infinity) >= cutoverMs
  const owners = new Set<number>()
  for (const { key, ns } of candidates) {
    if (!late(key)) continue
    const g = validGrant(key, ns, manifest, grants, raw)
    if (g) owners.add(g.workerId)
  }
  // Late objects granted to two different workers: nobody owns the session, so
  // none of them is accepted.
  const owner = owners.size === 1 ? [...owners][0] : null
  return (key, ns) => {
    if (!late(key)) return true
    return owner != null && validGrant(key, ns, manifest, grants, raw)?.workerId === owner
  }
}
