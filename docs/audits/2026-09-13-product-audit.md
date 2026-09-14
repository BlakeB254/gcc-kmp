# Product audit — 2026-09-13

Scope: the web app (38 pages), the KMP mobile app (40 screens, verified on a
Galaxy S26 running build 189), the admin surface, and the API contracts that
join them. Method: evidence first. Every finding below cites a file and line
or a production probe run on 2026-09-12/13; anything not verified says so.

Companion to `2026-07-07-codebase-audit.md`. That audit's master list is
mostly closed (see its §6); this one starts from what changed since.

## 0. The one finding to internalize first

Three production bugs were fixed in the 48 hours before this audit. They are
the same bug:

| PR | Symptom | The contract | Where it lived |
|---|---|---|---|
| #287 | iOS shipped build 189, Android stayed on 188 | `versionCode == CFBundleVersion` | a comment in `composeApp/build.gradle.kts` |
| #288 | Spot photos rendered nowhere | marker carries `photoUrl`/`mediaUrls` | nowhere — `selectedSpot` is `any` |
| #289 | A route stayed "in progress" six weeks after cancel | close verbs refuse or release active jobs | the route-admin plan, which the verbs skipped |

**A contract that exists only as a comment, a plan, or an `any` will drift.**
The remedy each time was the same: move the rule into a pure module with a
test, and put a gate in CI. This audit is organised around where the next
ones are.

## 1. Fixed this cycle

- **#287** — version-parity gate (`scripts/check-version-parity.sh`, runs first in the `kotlin` CI job); ms-a2 declared the only Android build host, MacBook iOS-only.
- **#288** — `GET /api/spots` markers carry media (`lib/spot-marker.ts`, tested).
- **#289** — `lib/release-route-jobs.ts` (one definition of releasing a job), unpublish/archive refuse-or-release in one transaction, Assignments/Unassign in the admin routes page, `GET /api/jobs` now requires auth.

## 2. Critical — do first

### 2.1 Unauthenticated endpoints of the `GET /api/jobs` class

`GET /api/jobs` returned every job's `workerId`, `routeId`, timing and payout
to anyone until #289. A sweep of `site/src/app/api/**/route.ts` for every auth
helper that exists (`requireAdmin`, `requireMobileAuth`, `requireSponsor`,
`verifyMobileToken`, `verifyWorkerToken`, cron/service tokens, Stripe
signature) and excluding routes that are public by design (auth, public/*,
health, articles, stats, map layers, webhooks, signup/reset flows) leaves
**25 routes with no recognised guard**. Spot-checked:

| Route | Verdict |
|---|---|
| `GET /api/jobs/[id]` | **No guard** (`route.ts:12`). Same leak as `/api/jobs`, one job at a time. |
| `GET /api/admin/newsletter/preview` | Documented as intentionally unauthenticated; renders a template with no data. Acceptable, but it lives under `/api/admin/`. |
| `GET /api/wallet` | `sponsorId: null` stub at line 7 — needs a real look. |

Still to verify individually: `bounties/[id]/{accept,claim}`,
`community-reports/{my-reports,my-stats,[id]/thank}`, `crashes/*`,
`gamification/me/badges`, `scans/[id]/coverage`,
`sponsors/{checkout,settings,sponsorships}`, `wallet/{allocate,deposit,transactions}`,
`workers/{address-change,me/service-area,upload-document}`,
`spots/[id]/volunteer/[cleanupId]/complete`. Routes named `me/` or `my-`
with no guard are the most likely real defects.

**Fix the class, not the instance:** a unit test that walks
`app/api/**/route.ts` and fails unless the file imports a guard *or* appears
on an explicit `PUBLIC_ROUTES` allowlist. That turns "every route is
guarded" from a habit into a gate.

### 2.2 Community-report photos have no flag-down

`attributes.mediaFlagged` and `servableMediaUrl()` cover `worldObservations`
only (`lib/observation-media.ts`). Community-report photos (`communityReports.mediaIds`)
have no equivalent, so a person photographed in a spot report cannot be
suppressed the way an observation can. `/api/spots` serves them publicly.

Production shows the two tables already overlap: `/api/map/observations` rows
carry `sourceType: "spot_report"` and `communityReportId`, i.e. spots are
mirrored into observations. See §6.2 — unifying media on the observation
side closes this gap and removes a duplicate media model at once.

### 2.3 The job state machine has no `cancelled`

`jobs.status` is `available / claimed / in_progress / pending_review /
completed / disputed`. Release is faked: paid jobs go back to `available`,
`$0` jobs become `disputed` as a tombstone (`releasedJobStatus()`). A real
`cancelled` value (migration + one enum entry on the KMP side) makes the
tombstone unnecessary and the admin history honest. `ACTIVE_JOB_STATUSES` is
still declared three times (`release-route-jobs.ts`, `route-queue.ts`,
`admin/routes/[routeId]/route.ts`); the latter two should import.

## 3. Mobile — verified on the S26 (Android 16, build 189)

### 3.1 Bottom overlays ignore system insets

`MapFullScreen.kt:629` sets `contentWindowInsets = WindowInsets(0)`, and the
map overlays are stacked with plain `Alignment.BottomStart` (zoom buttons,
878/887), `BottomCenter` (active-job sheet and cards, 902–1022) and
`BottomEnd` (811/844). On a 1080-wide One UI device the zoom column occludes
the left edge of the "Route in progress" sheet ("…te in progress" /
"…ki Rd Block") and the route-card row clips at the right edge
("Recor…"). iOS does not show this because its insets differ.

Fix: `navigationBarsPadding()` on the overlay container, and either reserve
the zoom column's width in the sheet's start padding or move zoom into the
existing top cluster (`MapControls` is already `TopCenter`).

### 3.2 Block chips render the placeholder branch

`RouteOverlay.kt:159` shows `block.displayImage` when present. Those blocks
come from `/api/affected-areas` (`AffectedAreasRepository.kt:57`), whose
GeoJSON properties carry **no `displayImage` or `imageUrl`** (nothing in
`api/affected-areas/route.ts` sets either). `/api/blocks` does carry it —
10 of 10 blocks in production. This is #288 again on a different endpoint:
one field missing from one serializer. Route thumbnails (`RouteThumbnail.kt`
← `Route.imageUrl` ← `/api/routes`, 10/10 populated) are fine.

### 3.3 Admin panel parity for the new 409

`AdminPanelViewModel.kt:255` calls `unpublishRoute`/`archiveRoute` and shows
any failure as text via `httpErrorMessage` (`GccApiClient.kt:93`). The web UI
now offers "Release N and unpublish"; the mobile admin panel needs the same
(`RouteDeleteSummary` already parses the `conflicts` shape).

### 3.4 Logging is a no-op on Android

First-launch logcat: `SLF4J(W): No SLF4J providers were found. Defaulting to
no-operation (NOP) logger`. Whatever shared code logs through SLF4J is
discarded on Android. For a field app whose hard bugs are in scan capture and
upload, that is the log you want most. Fix: an SLF4J Android binding, or
route shared logging to Napier/Logcat.

### 3.5 A location dot with no location permission

The map showed a blue dot before `ACCESS_FINE_LOCATION` was granted
(permission dump: `granted=false`). If it is the map SDK's default/last-known
layer, gate it on permission so a worker never trusts a position the app
does not have.

## 4. Web

### 4.1 `any`-typed API consumers — the mechanism behind #288

101 occurrences of `useState<any` / `: any` / `as any` across 20 files in
`app/` and `components/` (`admin/boundaries`, `admin/sponsors`,
`admin/newsletter`, `worker/dashboard`, `sponsor/{signup,dashboard}`,
`bounties/{page,post}`, `support/*`, `community`, and every map component).
A missing response field cannot be caught in any of them.

Fix: typed response DTOs exported next to their serializers (the pattern
already exists: `lib/admin-route-response.ts`, `lib/mobile-job-response.ts`,
now `lib/spot-marker.ts`), consumed on both ends. Then
`@typescript-eslint/no-explicit-any` as an error in those directories —
which first requires **re-enabling ESLint in CI** (disabled since Next 16;
`ci.yml:66`).

### 4.2 Eight spellings for "the picture"

Across `app/api`: `mediaUrls` (37), `mediaUrl` (36), `photos` (26),
`imageUrl` (18), `beforePhotos` (16), `afterPhotos` (14), `photoUrl` (4),
`displayImage` (4). Each client picks one. Recommend one `Media` DTO
`{ url, kind, flagged }` with adapters at the serializer edge; do it
per-endpoint as each is touched, not as a big bang.

### 4.3 Response keys nobody checked

The delete-blocked dialog read `json.summary` while the API sends
`json.conflicts`, so it always showed 0 / 0 (fixed in #289). Same root as
4.1: an untyped `res.json()`.

## 5. Contracts between site and KMP

### 5.1 Enums are hand-copied

`schema.ts` declares **47** `pgEnum`s. The KMP side has six hand-written
enum files (`BlockEnums.kt`, `CommonEnums.kt`, `JobEnums.kt`, `ScanEnums.kt`,
`SpotEnums.kt`, `WorkerEnums.kt`) with `@SerialName` strings. Nothing checks
they agree; a new enum value on the server is a deserialization failure on
the phone, discovered in the field.

Now: a script that emits the Kotlin enums from `schema.ts`, with a CI step
that fails when the committed files differ (small, high value). Target: an
OpenAPI description of the Next handlers and a generated KMP client, which
also retires the hand-written `GccApiClient` request/response pairs.

### 5.2 Duplicated logic

Beyond the enum copies: `ACTIVE_JOB_STATUSES` ×3 (§2.3), two media models
(§2.2), and marker serializers that diverged (#288). Each is a place where
one side can be fixed and the other not.

## 6. Capture pipeline and data quality

This is the "best data, no duplicates, streamlined capture → annotation →
reconstruction" ask, measured against what exists.

### 6.1 What exists and is good

`lib/world-observations.ts` `recordWorldObservation()` is a deliberate,
cell-keyed supersede model: a **clean** scan supersedes every dirty item in
its cell; a **dirty** observation supersedes only the prior observation of
the same `itemType` in the cell; `attributes` and `mediaUrl` **merge
forward** so a lean re-scan cannot wipe measured estimates or evidence
photos; and candidate selection is race-safe (prefers rows with evidence,
then newest). Dedup is spatial (`CELL_SIZE_METERS`) and by item type. This
is the right shape and should be the single write path for everything.

### 6.2 Gaps

- **Two write paths.** Community-report spots are a separate table with
  their own media and no cell supersede; a spot report and a scan
  observation of the same pile coexist. Production already mirrors spots
  into observations (`sourceType: "spot_report"`, `communityReportId`), so
  the community-report media path is the redundant one. Retire it in favour
  of observation media, which has flag-down — closes §2.2 in the same move.
- **No content dedup.** The same photo uploaded twice is two blobs and two
  evidence links. A perceptual hash at upload (`processImage` already
  decodes the JPEG) keyed with the cell gives cheap, honest dedup.
- **Annotation placement.** A litter classifier exists (`scripts/litter_model`,
  Indoor Gate v4 in open PR #259). Where it runs and how its output lands in
  `attributes` is not wired end-to-end in the API (no annotation write path
  found in `api/scans` or `world-observations.ts`). Backend annotation of
  uploaded media into `attributes` — with the merge-forward rule above —
  is the natural home.
- **Reconstruction.** `reconstruction-assets` GET exists (#273) and
  `scan_trace.py` (#274) traces a session to a twin. The PRD
  (`docs/PRD_environment-scan-vidmap-reconstruction.md`) phasing vs what is
  live needs a checkpoint before more is built on it.

### 6.3 Manual resolution

`resolveByCoveredCells` exists server-side; the worker-facing surfaces are
`SpotVerificationScreen` and `ScoutReviewScreen`. Not audited in this pass —
next tranche, on the S26, end to end.

## 7. Release engineering

- **CI** (`ci.yml`): the `kotlin` job compiles `:shared` and the *desktop*
  target; `android-debug` is `workflow_dispatch` only, so no PR has ever been
  gated on the Android app assembling. Playwright e2e is configured and never
  run in CI. ESLint is off. iOS is not built anywhere but the MacBook.
  Recommend: `assembleDebug` on PRs touching `composeApp/`/`shared/` (the
  SDK action is already in the manual job; ~4 min), and an e2e smoke against
  the Vercel preview URL.
- **Play.** Service account (pending JSON), upload keystore, signed AAB, and
  an internal-testing upload script — the same rig then serves every other
  app.
- **Hosts.** Two-host floor (Android on ms-a2, iOS on the MacBook) is now
  documented in `docs/android-build-msa2.md`; the MacBook trim script is
  written and unrun.

## 8. Prioritized master list

| # | Fix | Area | Value / Effort | Status |
|---|---|---|---|---|
| 1 | Route-guard test + fix `jobs/[id]` and the verified `me/`/`my-` routes | site | Critical / S | open |
| 2 | `displayImage` in `/api/affected-areas` properties (Android chips) | site+mobile | High / XS | open |
| 3 | Map overlay insets + zoom column on Android | mobile | High / S | open |
| 4 | Unify spot media onto observations; flag-down covers everything | site | High / M | open |
| 5 | `cancelled` job state; dedupe `ACTIVE_JOB_STATUSES` | site+shared | Med / S | open |
| 6 | Typed API consumers; ESLint back on with `no-explicit-any` | site | Med / M | open |
| 7 | Kotlin enum generator + CI diff | shared | Med / M | open |
| 8 | SLF4J Android binding | mobile | Med / XS | open |
| 9 | Android `assembleDebug` in PR CI | ci | Med / S | open |
| 10 | Mobile admin: release-and-close on 409 | mobile | Med / S | open |
| 11 | Perceptual-hash dedup at upload | site | Med / M | open |
| 12 | Backend annotation → `attributes` write path | site+dgx | Med / M | open |
| 13 | e2e smoke on preview URL | ci | Low / M | open |
| 14 | Location layer gated on permission | mobile | Low / XS | open |

**Proposed first tranche, one PR each, in order: 1, 2, 3, 8.** Together they
close the remaining leak class, make the Android map correct on real
hardware, and make field logs exist. Tranche two: 4, 5, 6. Tranche three:
7, 9, 10. Tranche four: 11, 12, 13, 14.
