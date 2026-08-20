# Route & Block Administration — Inventory (2026-08-20)

Read-only investigation of how routes and blocks are administered today across the
Next.js site, the Neon Postgres dataset, and the KMP mobile app, as input to
designing full admin route management (create / publish / delete / activate /
position-based release queue) on web + mobile + Android.

All paths are relative to `/home/codex450/cdx/dev-projects/gcc`.

---

## 1. Data model

Single Drizzle schema: `site/src/db/schema.ts` (2256 lines). Migrations:
`site/src/db/migrations/*.sql` (0005–0040). The Ktor `backend/` service has **no**
route/job tables — it is the scan control-plane only (see §5).

### 1.1 `routes` — `site/src/db/schema.ts:1508-1545`

- Enum `routeStatusEnum` (`enum_routes_status`), `schema.ts:1501-1505`:
  `'draft' | 'published' | 'archived'`. Default `'draft'` (`:1522`).
- Columns: `id` serial PK; `code` varchar(20) unique (`:1510`); `displayName`
  (`:1511`); `serviceAreaId` int **notNull** → service_areas (`:1512`);
  `geometry` jsonb **notNull** (GeoJSON LineString, `:1515`); `lengthMeters`,
  `estimatedMinutes` numeric (`:1518-1519`); `status` enum (`:1522`);
  `publishedAt` timestamptz (`:1523`); `version` int default 1 (`:1524`);
  `payoutAmount` numeric default `'10000'` (**cents**, `:1527`); `imageUrl`,
  `imageLocationLat/Lng` (`:1530-1534`); `description` varchar(500) (`:1537`);
  `createdById` (admin worker id, `:1540`); `createdAt`/`updatedAt`.

### 1.2 `route_checkpoints` — **DROPPED**

Retired by `site/src/db/migrations/0032_drop_checkpoints.sql` (drops the table,
`blocks.checkpoints` column, and `enum_route_checkpoints_type`). No live
checkpoint concept remains.

### 1.3 `blocks` — `schema.ts:405-458`

- Enum `blockStatusEnum` (`enum_blocks_status`), `schema.ts:397-402`:
  `'available' | 'funded' | 'in_progress' | 'completed'`. Default `'available'`.
- Key columns: `code` varchar(20) unique (`:407`); `name` notNull;
  `serviceAreaId` (`:413`); **`routeId` int — 1:1 back-reference to routes,
  "auto-created when block is saved" (`:415-416`)**; `locationBoundary` jsonb
  GeoJSON polygon (`:420`); `innerBoundary` jsonb (private-property cutoff,
  `:423`); `locationCenter` jsonb point (`:424`); `displayImage`,
  `imageLocationLat/Lng`; street metadata (`streetName`, `fromCorner`,
  `toCorner`, `side`, `includesAlley`, `:434-438`); funding (`fundingGoal`
  default '100', `fundingCurrent`, `fundedById`, `fundedAt`, `:441-444`);
  sponsor cleaning-schedule prefs (`:447-449`); completion (`completedById`,
  `completedAt`, `:452-453`).
- Related: `block_exclusion_zones` (`schema.ts:463-470`) and the admin-only
  staging table `block_proposals` (`schema.ts:491-520`, enum
  `'pending'|'approved'|'rejected'` at `:478-482`).

### 1.4 `jobs` — `schema.ts:536-582`

- Enum `jobStatusEnum` (`enum_jobs_status`), `schema.ts:526-533`:
  `'available' | 'claimed' | 'in_progress' | 'pending_review' | 'completed' |
  'disputed'`. Default `'available'`.
- Columns: `blockId` int **notNull** (`:538`); `workerId` nullable (`:539`);
  claim/start/complete timestamps (`:543-545`); evidence (`beforePhotos`,
  `afterPhotos`, `gpsPath`); payout (`payoutAmount`, `payoutStatus` varchar,
  `payoutStripeTransferId`, `payoutCompletedAt`, `:553-556`); rating; review
  flow fields (`reviewStatus`, `approvedByAdminId`, `deniedByAdminId`,
  `denyReason`, `reviewedAt`, `:566-570`). Indexed on workerId/blockId/status
  (`:579-581`).

### 1.5 Release-queue tables (created by `site/src/db/migrations/0018_add_route_release_queue.sql`)

- **`route_release_batches`** — `schema.ts:609-638`. Enum
  `routeReleaseStatusEnum` (`:587-593`): `'draft' | 'scheduled' | 'active' |
  'completed' | 'cancelled'`. Columns: `serviceAreaId` notNull; `name`;
  `communityName`; `eligibleZipCodes` jsonb notNull; `status`; `releaseAt`
  notNull; `nextWaveAt`; `offerWindowMinutes` default 240; `timezone` default
  'America/Chicago'; `daytimeWindowStart/End` ('08:00'/'20:00');
  `cleanupStartsAt/EndsAt`; `targetSlots`; `waveNumber`; `createdById`.
  Indexes on serviceAreaId and (status, nextWaveAt).
- **`route_release_routes`** — `schema.ts:640-655`. Enum
  `routeReleaseRouteStatusEnum` (`:595-599`): `'open' | 'claimed' |
  'cancelled'`. Columns: `batchId`, `routeId`, `jobId` (all notNull);
  `status`; `acceptedByWorkerId`, `acceptedOfferId`, `acceptedAt`. Unique
  (batchId, routeId) and unique (jobId). **No position/ordering column —
  slots are an unordered set.**
- **`route_job_offers`** — `schema.ts:657-678`. Enum
  `routeJobOfferStatusEnum` (`:601-607`): `'pending' | 'accepted' |
  'declined' | 'expired' | 'cancelled'`. Columns: `batchId`, `workerId`,
  `waveNumber`, `offerToken` unique(96), `offeredAt`, `expiresAt`,
  `respondedAt`, `selectedRouteId`, `selectedJobId`, `declineReason`,
  `notificationStatus`, `notificationLastError`.
- **`route_offer_notifications`** — `schema.ts:698-716`. Outbox rows:
  `offerId`, `workerId`, `channel` ('email'|'push'), `kind`
  ('initial'|'reminder_1h'|'reminder_5m'), `status`, `scheduledAt`, `sentAt`,
  `attempts`, `lastError`, `payload`. Unique (offerId, channel, kind).
- **`route_community_queue`** — `schema.ts:680-696`. One row per
  (serviceAreaId, workerId) (unique `:694`): `queueRank` numeric (signup
  ranking — neighborhood → city → global), `lastOpportunityAt`,
  `lastOfferedBatchId`, `lastOfferStatus` (offer-status enum),
  `opportunitiesCount`, `carryoverReleaseAfter`, `carryoverAttempts`.

### 1.6 `service_areas` — `schema.ts:1474-1498`

- Enum `serviceAreaStatusEnum` (`:1466-1471`): `'planning' | 'pilot' |
  'active' | 'paused'`. Columns: `name`, `city`, `state`, `boundary` jsonb
  notNull, `mapCenter` jsonb notNull, `defaultZoom`, `status`, `launchDate`.

### 1.7 `bounties` — `schema.ts:801-849`

Not route-linked by schema. `status` is a **varchar(32) default `'open'`**
(`:813`) — not a pg enum. `jobId` nullable (`:836`) can point at a job.
Separate claim flow (`/api/bounties/[id]/claim`).

### 1.8 Route lifecycle as implemented

1. **Create (draft)** — `POST /api/admin/routes`
   (`site/src/app/api/admin/routes/route.ts:84-191`): inserts route with
   `status:'draft'` (`:141`) **and** a 1:1 block with `status:'funded'`,
   `locationBoundary:null` (`:152-170`). Auto-generates route/block codes
   (`:107-127`). Grid alternative: `POST /api/admin/routes/generate`
   (proposals) + `POST /api/admin/routes/accept` (bulk-insert drafts,
   `accept/route.ts:19-70`).
2. **Publish (= make claimable)** — `PUT /api/admin/routes/:id` with
   `{status:'published'}` (`[routeId]/route.ts:40-125`): sets `publishedAt`
   (`:69`) and, in the same batch, flips linked blocks in
   `available`/`funded` to the mapped status (`:83-101`). Mapping in
   `site/src/lib/admin-route-status.ts:14-18`:
   `draft→funded`, `published→available`, `archived→funded`.
   **There is no separate "claimable" flag — publishing a route is what
   makes its block claimable.**
3. **Claim** — two paths:
   - Direct/FCFS: `POST /api/jobs/claim` requires block `status='available'`,
     creates job `status='claimed'`, sets block `'in_progress'`
     (`jobs/claim/route.ts:76-109`); or `POST /api/jobs/:id/claim` on an
     `available` job (`jobs/[id]/claim/route.ts:31-42`).
   - Release wave: `createRelease` opens an `available` job + an `open`
     `route_release_routes` slot per route (`site/src/lib/route-queue.ts:230-290`);
     worker accepts an offer picking one open route → job `claimed`, slot
     `claimed`, offer `accepted`
     (`api/workers/me/route-offers/[offerId]/accept/route.ts:42-81`).
4. **Work & review** — job `in_progress` → worker completes
   (`/api/jobs/[id]/complete`) → `pending_review` → admin approve/deny
   (`/api/admin/jobs/[id]/approve|deny`) → `completed` (+ wallet credit via
   `workerWalletEntries`, schema.ts:2060-2087). Block ends `'completed'`.

---

## 2. Existing web admin

### 2.1 Pages — `site/src/app/admin/` (auth via `layout.tsx`)

- `layout.tsx:7-16` — sidebar nav: Routes & Blocks, Boundary Editor, Block
  Proposals, Sponsor Deliverables, Completion Reports, Newsletter, Scan
  Sessions, Crash Reports. **No release-queue page.**
- Auth: token in `localStorage['adminToken']`, sent as
  `Authorization: Bearer …`; layout validates it against `/api/admin/routes`
  and redirects to `/login` (`layout.tsx:26-47`).
- `routes/page.tsx` (491 lines) — list + 3 views: `draw` (`RouteDrawer`),
  `generate` (`RouteGenerator`), `exclusion-zones` (`ExclusionZoneDrawer`).
  Row actions: PUT status draft/published/archived (`page.tsx:108-133`),
  DELETE (`:135-160`), edit modal for displayName/payout/description
  (`:162-194`).
- `boundaries/page.tsx`, `block-proposals/page.tsx` (approve/reject OSM-auto
  candidates), plus sponsors/reports/newsletter/scan-sessions/crashes pages.

### 2.2 Admin API — `site/src/app/api/admin/**` (all guarded by `requireAdmin`)

`requireAdmin` (`site/src/lib/admin-auth.ts:8-28`): Bearer JWT via
`verifyMobileToken`, rejects unless `role === 'admin'` (403 otherwise).

| Method & path | File | What it does |
|---|---|---|
| GET `/api/admin/routes` | `admin/routes/route.ts:10` | List all routes (any status) + blockId/blockBoundary per route |
| POST `/api/admin/routes` | `admin/routes/route.ts:84` | Create route draft + funded 1:1 block |
| PUT `/api/admin/routes/:routeId` | `admin/routes/[routeId]/route.ts:40` | Edit status (publish/unpublish/archive), imageUrl, displayName, description, image location, payoutAmount (dollars→cents `:77`) |
| DELETE `/api/admin/routes/:routeId` | `admin/routes/[routeId]/route.ts:127` | Delete route + linked block + its exclusion zones (`:142-151`) |
| GET/POST/DELETE `/api/admin/routes/:routeId/assign` | `admin/routes/[routeId]/assign/route.ts:62,100,160` | List assignments; assign worker (isTest flag → $0 job); cancel claimed/in_progress assignment (paid → back to `available`) |
| POST `/api/admin/routes/generate` | `admin/routes/generate/route.ts:7` | Route-grid proposals for a community area |
| POST `/api/admin/routes/accept` | `admin/routes/accept/route.ts:19` | Bulk-accept proposals → draft routes + funded blocks |
| POST `/api/admin/routes/:routeId/regenerate-inner-boundary` | `admin/routes/[routeId]/regenerate-inner-boundary/route.ts` | OSM frontage generator → innerBoundary islands |
| GET `/api/admin/blocks` | `admin/blocks/route.ts:7` | List all blocks (no POST/PATCH/DELETE — blocks are manipulated via their route) |
| PUT `/api/admin/blocks/:blockId/boundaries` | `admin/blocks/[blockId]/boundaries/route.ts` | Save outer/inner boundary polygons (thins overlapping neighbors) |
| POST `/api/admin/blocks/:blockId/exclusion-zones` | `admin/blocks/[blockId]/exclusion-zones/route.ts` | Add private-property exclusion polygons |
| GET `/api/admin/block-proposals`; POST `.../:id/approve|reject` | `admin/block-proposals/**` | Review staging table; approve inserts a real block (+zones) |
| GET/POST `/api/admin/route-releases` | `admin/route-releases/route.ts:16,37` | List batches / **create release batch** (name, serviceAreaId, routeIds[], releaseAt, …) |
| POST `/api/admin/route-releases/:releaseId/process` | `admin/route-releases/[releaseId]/process/route.ts:14` | Manually run the next wave for one batch |
| POST `/api/admin/route-releases/process-notifications` | `admin/route-releases/process-notifications/route.ts` | Cron-equivalent: process all due batches |
| GET `/api/admin/jobs`, `.../pending-review`, POST `.../:id/approve|deny|reassign` | `admin/jobs/**` | Job ops / payout review |
| (cron) GET `/api/cron/route-releases` | `api/cron/route-releases/route.ts:33` | `CRON_SECRET`-guarded 15-min tick: `processDueBatches()` + drain notification outbox, under a `cron_leases` lease |

### 2.3 Release-queue semantics today (`site/src/lib/route-queue.ts`)

- `createRelease` (`route-queue.ts:168-293`): batch becomes `scheduled`
  (future releaseAt) or `active`; per route: ensure block (create
  `funded` block if missing, `:234-250`), refuse if an active job exists
  (`:252-257`), ensure an `available` unassigned job (`:259-277`), insert
  `route_release_routes` row `status:'open'` (`:279-289`).
- `processBatch` (`:476-605`): expire stale pending offers → mark workers
  for carryover (`:508-518`); if 0 open slots → batch `completed`
  (`:523-529`); capacity = openSlots − activeOffers; select candidates
  (`:306-411`) = verified + proof-of-address-approved workers in the batch's
  ZIPs, no active job, not yet offered this batch, ordered **carryover first
  → oldest lastOpportunityAt → queueRank (signup ranking) → workerId**
  (`:399-408`); insert pending offers (UUID token, expiry = now +
  offerWindowMinutes), schedule outbox notifications, update queue rows,
  set next wave at offer expiry.
- **Offers are for the whole batch, not a specific route**: the accepting
  worker chooses any still-`open` route (`accept/route.ts:42-53`). There is
  **no per-route position/order, no priority, no reorder API, no
  cancel/edit-batch endpoint** (only create/list/process).

---

## 3. Public / consumer side

- `GET /api/routes` (`api/routes/route.ts:16-118`) — **unauthenticated**;
  defaults to `status=published`; includes geometry, blockBoundary,
  innerBoundary, payout. Draft/archived hidden unless `?status=all`.
- `GET /api/blocks` (`api/blocks/route.ts:20-157`) — **unauthenticated**;
  blocks whose route is draft/archived are filtered out
  (`:44` — `routeId IS NULL OR routes.status='published'`), a fix for the
  "Millard block" leak documented in the comment at `:38-43`.
- `GET /api/jobs?status=available` (`api/jobs/route.ts:19-…`) — job list;
  `available` excludes $0 test jobs unless `include_test=true` (`:33-36`).
- `POST /api/jobs/claim` (`api/jobs/claim/route.ts:15-137`) — **no Bearer
  auth; `workerId` comes from the request body.** Guards: worker exists +
  `verificationStatus='approved'` (`:38-43`), no active job (`:46-62`), block
  `status='available'` (`:76-88`). Creates `claimed` job, block →
  `in_progress`. Error message at `:83` — *"funded but not yet available…
  wait for admin to release it"* — confirms publish = activation.
- `POST /api/jobs/:id/claim` (`api/jobs/[id]/claim/route.ts:13-…`) —
  `requireMobileAuth`; job must be `available` unless caller is admin
  (`:31-33`). This is what the KMP app calls (`GccApiClient.claimJob`,
  `shared/.../api/GccApiClient.kt:314-315`).
- Worker offer flow (`requireMobileAuth`, `site/src/lib/mobile-auth.ts`):
  `GET /api/workers/me/route-offers` (pending, unexpired, with batch +
  open routes, `route-offers/route.ts:16-31`);
  `POST .../route-offers/:offerId/accept` (`accept/route.ts:16-88`);
  `POST .../route-offers/:offerId/decline`.
- **"Available for claim" = block `status='available'` (direct) OR a
  pending, unexpired `route_job_offers` row for that worker (wave).**

---

## 4. Mobile app (KMP) side

### 4.1 Admin UI exists and is substantial

- `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/`:
  `AdminPanelScreen`, `AdminOverviewTab`, `AdminWorkersTab`,
  `AdminWorkerDetailScreen`, **`AdminRoutesTab` (589 lines, includes a
  create-route form — `:322-357`)**, **`AdminRouteDetailScreen` (1147
  lines)**, **`AdminRouteStatusControls` (publish/draft/archive with
  confirm dialogs, `:74-108`)**, `AdminBoundaryEditorScreen` (saves via
  `PUT /api/admin/blocks/:id/boundaries`, `:94`), `AdminJobListScreen`,
  `AdminBountiesTab`, `AdminSpotsTab`, `AdminPayoutsTab`,
  `AdminBlockProposalsTab`.
- `viewmodel/AdminPanelViewModel.kt` (849 lines) drives it: `createRoute`
  (`:416-427`), `updateRouteStatus` (`:222-262`), `updateRouteImage` /
  `updateRouteNameDesc` / `updateRouteImageLocation` (`:268-327`),
  `deleteRoute` (`:626-637`), `assignRouteToWorker` /
  `loadRouteAssignments` / `cancelRouteAssignment` (`:639-705`), block
  proposal approve/reject (`:814-844`).
- API surface used — `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt`:
  `getAdminRoutes` `:823`, `createRoute` `:826`, `updateRoute` `:829`,
  `updateRouteStatus` `:832`, `updateRouteImage` `:835`,
  `updateRouteImageLocation` `:838`, `updateRouteNameDesc` `:841`,
  `deleteRoute` `:847`, assign get/post/delete `:730-742`,
  `regenerateInnerBoundary` `:950`, `updateBlockBoundaries` `:959`,
  `getAvailableBlocks` `:851` (public `/api/blocks` with hard-coded
  fallback to `https://www.globalcommunitycleaners.org`, `:852-866`),
  `getRoutes` `:275-289` (public `/api/routes`), `claimJob` `:314`.
- DTOs — `shared/.../model/Route.kt` (`Route`, `RouteStatus`,
  `blockBoundary`, `blockId` noted as admin-only `:26-32`);
  `shared/.../model/AdminModels.kt`: `AdminRouteRequest` (`:70-84`) —
  includes `status`, **`publishAt`, `unpublishAt` fields the server PUT
  currently ignores**; `UpdateRouteStatusRequest` `:67`;
  `AdminRouteAssignment(Request)` `:115-135`; boundary DTOs `:92-113`.
- **No release-queue surface at all**: grep finds no `route-releases`,
  `RouteRelease`, `RouteJobOffer`, or `route-offers` usage in `shared/` or
  `composeApp/` main trees (they exist only in stale `.claude/worktrees/*`
  checkouts, e.g. `shared/model/RouteQueue.kt` there). The app mentions
  "route offers" only in dashboard helper text
  (`ui/screens/DashboardScreen.kt:617-623`). The offer accept/decline flow
  is server-side + email/push only today.

### 4.2 Admin role concept

- JWT `role` claim; `TokenManager.isAdmin()` returns true for `"admin"` or
  `"super_admin"` (`shared/.../auth/TokenManager.kt:174-188`).
- Client-side gate: `navigation/RootComponent.kt:261-264` — refuses to push
  `Screen.AdminPanel` unless `tokenManager.isAdmin()`; comment notes the
  server independently re-checks the JWT role on every `/api/admin` call
  (which `requireAdmin` does, `admin-auth.ts:23-25`).
- `Worker.role` model field defaults `"worker"`
  (`shared/.../model/Worker.kt:38`).

---

## 5. Android readiness

- `composeApp/build.gradle.kts:26` — `androidTarget { … }`;
  `shared/build.gradle.kts:13-22` — `jvm()`, `androidTarget`, `iosArm64`,
  `iosSimulatorArm64`. `composeApp/src/androidMain/` is populated
  (`MainActivity.kt` + platform implementations: CameraManager,
  PushNotificationManager, LocationProvider, SecureStorage, …).
- Networking is **Ktor client in `commonMain`**
  (`GccApiClient.kt:22-48`), Bearer token injected per-request by a client
  plugin (`:41-47`), `baseUrl` from `expect fun getApiBaseUrl()`
  (`composeApp/.../platform/PlatformConfig.kt:11`, wired in
  `di/AppModule.kt:90`). Timeouts and error handling are shared.
- The Ktor `backend/` service (`backend/src/main/kotlin/com/gcc/backend/`)
  exposes only `/healthz`, `POST /admin/index-scans`, `POST
  /admin/sync-civic` (`backend/.../Application.kt:109-127`) — scan
  indexing + Chicago 311 sync. **No route/job endpoints there**; everything
  route/block lives on the Next.js API against Neon.
- **Conclusion: the same REST endpoints serve Android unchanged.** All
  admin routes are platform-agnostic JSON + Bearer JWT; the existing
  androidMain target already runs the full commonMain admin UI.

---

## 6. Gaps vs. the target design

Target: create, publish/unpublish, delete, activate (make claimable),
position-based release queue — identical from web admin, KMP admin, and
Android, one Neon dataset.

1. **No "activate/claimable" concept separate from publish.** Today
   publish *is* activation (block `funded`→`available`,
   `admin-route-status.ts:14-18`). If activation should be independent
   (published but not yet claimable, or claimable only via queue), the
   block-status mapping and the `/api/jobs/claim` guard
   (`jobs/claim/route.ts:76`) need a new state or flag.
2. **No position-based release queue.** `route_release_routes` has no
   position column (`schema.ts:640-655`); slots are an unordered set and the
   accepting worker picks any open route (`accept/route.ts:42-53`). Needs:
   a `position` column (+ migration), ordered release/claim logic in
   `route-queue.ts`, and reorder endpoints.
3. **Release management is API-only and create-only.** No web admin page
   for batches (`layout.tsx:7-16` nav has none); no PATCH/cancel/delete
   batch, no batch-detail (routes inside), no list/cancel of
   `route_job_offers`, no view of `route_community_queue`. Only
   create/list/process exist (`admin/route-releases/route.ts`,
   `[releaseId]/process/route.ts`).
4. **Mobile admin has zero release-queue surface** — no DTOs, client
   methods, ViewModel, or screens (see §4.1). All of it must be added to
   `shared/` + `composeApp/`; Android then gets it for free via commonMain.
5. **Mobile create-route is thin.** The form in `AdminRoutesTab.kt:322-357`
   posts `AdminRouteRequest`, but server POST ignores `status`, `publishAt`,
   `unpublishAt` (`admin/routes/route.ts:90` destructures only
   displayName/serviceAreaId/geometry/code/payoutAmount/side/streetName/
   fromCorner/toCorner/description), and there is no geometry authoring on
   mobile (web uses `RouteDrawer`/`RouteGenerator`). PUT likewise ignores
   `publishAt`/`unpublishAt` (`[routeId]/route.ts:71-78`) — **scheduled
   publish/unpublish fields exist in the shared DTO but are dead**.
6. **Delete is not FK-safe.** `DELETE /api/admin/routes/:id`
   (`[routeId]/route.ts:142-151`) removes exclusion zones, block, route —
   but leaves `jobs` rows (blockId dangling) and `route_release_routes` /
   `route_job_offers` referencing the route. No guards against deleting a
   route with active jobs or open release slots.
7. **Two divergent claim paths.** `POST /api/jobs/claim` takes `workerId`
   from the body with no auth token (`jobs/claim/route.ts:18`) while
   `/api/jobs/:id/claim` requires mobile auth. Any new claim/activation
   design should unify on the authenticated path.
8. **Consistency model is "shared DB, no cache" — mostly.** All clients
   hit the same Neon tables via the Next.js API, so web/mobile/Android see
   the same state immediately; the only staleness risks are the mobile
   offline cache (`shared/.../offline/OfflineHttpEngine.kt:50-240` caches
   `/api/routes`, `/api/blocks`, `/api/admin/routes` GETs) and the
   public-data invalidation layer (`lib/public-data/invalidation.ts`) used
   for stats — worth accounting for when adding queue mutations.
9. **Admin authZ is uniform and reusable** — `requireAdmin` Bearer-JWT
   guard (`lib/admin-auth.ts`) is already the single guard for every admin
   endpoint and works identically for web (localStorage token) and KMP
   (TokenManager). New admin endpoints should reuse it; no new auth work
   needed for Android.
