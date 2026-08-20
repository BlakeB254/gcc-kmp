# Unified Route Administration — implementation plan (2026-08-20)

Goal (Blake, 2026-08-20): admins can create routes, publish/unpublish, delete,
activate for claiming, and order a position-based release queue — from **both**
the web admin UI and the mobile (KMP) admin UI, with Android launching onto the
same system later. **One dataset (Neon), one API (`/api/admin/*`), every client
a thin view.**

Source of truth for current state: `docs/plans/route-admin-inventory-2026-08-20.md`.

## Design principles

1. **Server is the only writer.** Web, iOS, and Android admin UIs all call the
   same Next.js admin endpoints guarded by the existing `requireAdmin` JWT role
   check (`site/src/lib/admin-auth.ts:8`). No client-side business logic, no
   per-client caches of record.
2. **Consistency by contract, not realtime.** Every mutation returns the
   updated entity (or the affected list); clients refetch the list on focus /
   pull-to-refresh. Neon is the single store, so "data updates in one
   interface, visible in all" is true by construction. (Optional later phase:
   ETag/`If-Modified-Since` polling or websockets — not needed now.)
3. **Publishing ≠ activation.** Today publish flips a block to `available`
   (`site/src/lib/admin-route-status.ts:14-18`). Blake's ask needs these as
   separate switches: visibility (published) vs. claimable (accepting claims).
4. **Queue order is explicit.** The release queue gets a real `position`
   column; admins drag to reorder; offer candidate ordering honors it.

## Phase 1 — Schema (one migration)

`site/src/db/migrations/00XX_route_admin_v2.sql`:

- `routes` — add `claim_status` enum/text: `closed | open | scheduled`
  (default `open` for existing published routes, `closed` for drafts), plus
  wire the already-planned `publish_at` / `unpublish_at` timestamptz columns
  (DTOs already carry them; server POST/PUT currently ignores them).
- `route_release_routes` — add `position integer not null default 0`
  (backfill: `row_number()` over `batch_id, created_at`).
- Status transition map (server-enforced):

  | route.status | claim_status | worker sees | can claim |
  |--------------|--------------|-------------|-----------|
  | draft        | closed       | no          | no        |
  | published    | closed       | yes (read)  | no        |
  | published    | open         | yes         | yes       |
  | published    | scheduled    | yes + date  | not yet   |
  | archived     | closed       | no          | no        |

  Migration backfill: `published` → `claim_status=open` (preserves today's
  behavior exactly).

## Phase 2 — API (site/src/app/api/admin)

Extend existing, don't parallel-build:

- `POST/PUT admin/routes` — accept `claimStatus`, `publishAt`, `unpublishAt`;
  single transaction flips route + block (`blocks.status=available` only when
  `published+open`; reverting to `funded`/`in_progress` when closed).
- `POST admin/routes/[id]/publish|unpublish|archive` — explicit verbs instead
  of overloaded PUT (keeps mobile code dumb).
- `DELETE admin/routes/[id]` — **fix the dangling-rows bug**: refuse (409)
  when active jobs or open offers exist; otherwise cancel related
  `route_release_routes`/`route_job_offers` and null `jobs.route_id` in one
  transaction. Return a summary of what was cancelled.
- Release batches: `GET/PATCH admin/route-releases/[id]` (detail incl. routes
  + offers, edit name/scheduledAt, cancel), and
  `PUT admin/route-releases/[id]/order` accepting `{routeIds: number[]}` →
  rewrites `position` 1..N transactionally.
- Cron tick (`api/cron/route-releases`) + manual process: candidate ordering
  becomes `position ASC` inside batch, then carryover/last-opportunity/signup
  rank as today (`lib/route-queue.ts:399-408`).
- `GET /api/routes` and `GET /api/blocks`: filter on
  `published + claim_status=open` for claimable lists; include `claimStatus`
  so mobile can render "opens Friday" states. **Unify the two claim paths**:
  require auth on `POST /api/jobs/claim` (drop body-workerId), keep one code
  path (`api/jobs/claim/route.ts` vs `api/jobs/[id]/claim` — consolidate).

## Phase 3 — Web admin UI

- New page `app/admin/route-releases/page.tsx`: batch list (status, wave
  size, scheduled time), create/edit/cancel batch, per-batch route list with
  **drag-to-reorder** (writes `PUT .../order`), offers view per wave.
- Routes list page: add claim-status toggle + publish/unpublish buttons
  (calls the new verb endpoints), show effective state badges
  (Draft / Live / Live-not-claimable / Scheduled / Archived).
- Delete flow: confirmation dialog showing what will be cancelled (from the
  409/summary payload).

## Phase 4 — Mobile admin (composeApp + shared)

Mirror, don't reinvent:

- `shared/.../api/dto`: `AdminReleaseBatchDto`, `AdminReleaseRouteDto`,
  `AdminRouteOrderRequest`, `claimStatus` on route DTOs (pattern already
  established in `GccApiClient.kt:728-973`).
- `GccApiClient`: `publishRoute/unpublishRoute/archiveRoute/setClaimStatus`,
  `listReleaseBatches/createBatch/cancelBatch/reorderBatchRoutes/getBatchDetail`
  — same endpoints, same Bearer JWT (`TokenManager.isAdmin()` gate already
  exists, `RootComponent.kt:264`).
- `composeApp/.../ui/screens/admin/`: new "Release queue" screen (batch list
  → batch detail with up/down reorder — drag is optional, buttons are fine
  and more accessible) and route row actions
  (publish/unpublish/claim toggle/delete with the same confirmation payload).
- **Android needs zero API work**: `androidTarget` + commonMain Ktor means the
  new endpoints and screens work on Android the day it launches. Only verify
  `androidMain` builds the admin screens (no iosMain-only deps there).

## Phase 5 — Hardening + verification

- Integration tests (pattern exists under site tests): publish/unpublish
  state matrix, reorder transaction, delete-refusal paths, scheduled
  activation tick.
- E2E: web admin reorders queue → iOS admin sees same order (screenshot both).
- Concurrency: reorder + process-wave in flight → position writes are
  serialized in one transaction; offer creation takes a row lock on the batch.
- Audit log: every admin mutation writes who/when/what (reuse the pattern
  from `performed_by_admin_id` on jobs).

## Out of scope (deliberate)

- Realtime push/websocket sync — refetch-on-focus is enough at this scale.
- Moving route admin into the Ktor backend — keep one admin API (Next.js)
  until the backend grows an auth layer; revisit if Vercel timeouts bite on
  wave processing (cron already handles the heavy path).

## Order of work

1. Migration + `lib/admin-route-status.ts` rewrite (state matrix).
2. API verbs + release-batch endpoints + claim unification.
3. Web admin pages.
4. shared/ DTOs + client methods; iOS admin screens.
5. Android build check; E2E consistency pass.
