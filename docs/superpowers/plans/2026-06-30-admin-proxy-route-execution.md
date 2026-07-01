# Admin Proxy Route-Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an admin run a full route execution on their own device on behalf of a verified user, crediting the user with an admin audit trail (for alpha users without an iOS device).

**Architecture:** Small, additive changes. Backend: a verified-worker filter, an admin-only `onBehalfOfWorkerId` path on scan creation (server re-checks the target is verified), and an audit column. App: a verified-user dropdown + Begin on the existing Admin route-detail screen that threads `onBehalfOfWorkerId` into the existing scan-execution flow. The default (no `onBehalfOfWorkerId`) path stays byte-for-byte unchanged.

**Tech Stack:** Next.js `site/` (App Router API routes, Drizzle ORM, Postgres/Neon) is the live backend; KMP `composeApp/` (Compose, Ktor client `GccApiClient`); shared models in `shared/`.

## Global Constraints

- **Live backend is `site/src/app/api/*`** (api.globalcommunitycleaners.org). Do NOT implement in the Ktor `server/`.
- **Verified = `workers.verificationStatus === 'approved'`.** The scan-as-user path MUST re-verify this server-side; never trust the client.
- **Admin = JWT `role === 'admin'`** via `requireAdmin` / the mobile-auth verify (`site/src/lib/admin-auth.ts`, `site/src/lib/mobile-auth.ts`).
- **Audit:** admin-performed scans/jobs store `performedByAdminId = auth.workerId`.
- **No unit-test runner in `site/`** (Playwright e2e only). Backend verification = `npx tsc --noEmit` + exercising the endpoint against the local dev server (homebrew Postgres `gcc` DB is available; a `site/.env.local` exists). App verification = `./gradlew :composeApp:compileKotlinMetadata` and `:composeApp:compileDebugKotlinAndroid`.
- Keep the non-admin / no-`onBehalfOfWorkerId` behavior unchanged.
- Commit after every task.

---

### Task 1: Audit column `performed_by_admin_id` on `route_scans` and `jobs`

**Files:**
- Modify: `site/src/db/schema.ts` (the `routeScans` and `jobs` table definitions)
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/RouteScan.kt` (add `performedByAdminId`)

**Interfaces:**
- Produces: DB columns `route_scans.performed_by_admin_id` (int, nullable, FK→workers.id) and `jobs.performed_by_admin_id` (same); `RouteScan.performedByAdminId: Int?`.

- [ ] **Step 1: Read** `site/src/db/schema.ts` — find the `routeScans` and `jobs` table definitions and how other nullable int FK columns to `workers` are declared (copy that exact style, e.g. `integer('...').references(() => workers.id)`).

- [ ] **Step 2: Add the column to both tables.** In the `routeScans` table add (matching the file's column style):

```ts
performedByAdminId: integer('performed_by_admin_id').references(() => workers.id),
```

Add the identical column to the `jobs` table definition.

- [ ] **Step 3: Add the field to the shared model.** In `RouteScan.kt`, add to the data class (nullable, default null so existing constructors are unaffected):

```kotlin
val performedByAdminId: Int? = null,
```

- [ ] **Step 4: Push the schema to the dev DB.**

Run: `cd site && DATABASE_URL="postgresql://codexmetatron@localhost:5432/gcc" DATABASE_URI="$DATABASE_URL" npx drizzle-kit push`
Expected: "Changes applied" (adds the two nullable columns; no data loss). If the dev DB currently points at Neon in `.env.local`, this still works because the command overrides the env var to local.

- [ ] **Step 5: Typecheck + compile.**

Run: `cd site && npx tsc --noEmit` (expected: clean) and `./gradlew :composeApp:compileKotlinMetadata` (expected: BUILD SUCCESSFUL).

- [ ] **Step 6: Commit.**

```bash
git add site/src/db/schema.ts shared/src/commonMain/kotlin/com/gcc/shared/model/RouteScan.kt
git commit -m "feat(db): add performed_by_admin_id audit column to route_scans + jobs"
```

---

### Task 2: Verified-worker filter on `GET /api/admin/workers`

**Files:**
- Modify: `site/src/app/api/admin/workers/route.ts`

**Interfaces:**
- Produces: `GET /api/admin/workers?verificationStatus=approved` returns only workers whose `verificationStatus` equals the param; same response shape as today. Absent param → unchanged (all workers).

- [ ] **Step 1: Read** `site/src/app/api/admin/workers/route.ts` — confirm the `requireAdmin`/auth guard, the drizzle `db.select().from(workers)` query, and how query params are read (`new URL(req.url).searchParams`).

- [ ] **Step 2: Add the optional filter.** Read `verificationStatus` from the query string; when present, add a `where(eq(workers.verificationStatus, verificationStatus))` clause (import `eq` from `drizzle-orm` if not already imported). Leave the response mapping unchanged. Example shape:

```ts
const verificationStatus = new URL(req.url).searchParams.get('verificationStatus')
const rows = verificationStatus
  ? await db.select().from(workers).where(eq(workers.verificationStatus, verificationStatus)).limit(limit)
  : await db.select().from(workers).limit(limit)
```

- [ ] **Step 3: Typecheck.**

Run: `cd site && npx tsc --noEmit` — Expected: clean.

- [ ] **Step 4: Manual check (if dev server runnable).** With the local dev server up (`cd site && npx next dev --webpack -p 7350`, `.env.local` DB present) and an admin token, `GET /api/admin/workers?verificationStatus=approved` returns only approved workers. If the dev server can't run in this environment, verify by code inspection and note it.

- [ ] **Step 5: Commit.**

```bash
git add site/src/app/api/admin/workers/route.ts
git commit -m "feat(api): verificationStatus filter on GET /api/admin/workers"
```

---

### Task 3: Scan-as-user backend (`POST /api/scans` admin `onBehalfOfWorkerId`)

**Files:**
- Modify: `site/src/app/api/scans/route.ts`
- Modify: `site/src/lib/mobile-route-scan-response.ts` (surface `performedByAdminId` in `toMobileRouteScan`)
- (Reference) `site/src/app/api/admin/routes/[routeId]/assign/route.ts` — reuse its job-ensure logic

**Interfaces:**
- Consumes: `route_scans.performed_by_admin_id` + `jobs.performed_by_admin_id` (Task 1); the verified filter concept (Task 2).
- Produces: `POST /api/scans` accepts optional `onBehalfOfWorkerId: number`. Admin + verified target → scan `workerId = onBehalfOfWorkerId`, `performedByAdminId = auth.workerId`, and an active `Job(route, workerId=target)` is ensured. Non-admin sending it → 403. Admin + non-verified target → 422.

- [ ] **Step 1: Read** `site/src/app/api/scans/route.ts` (esp. the `workerId: auth.workerId` line and the auth object shape — `auth.workerId`, `auth.role`), and the job-creation block in `site/src/app/api/admin/routes/[routeId]/assign/route.ts` (how it finds/creates a `Job` for a route+worker and sets `payoutAmount`).

- [ ] **Step 2: Resolve the effective worker + audit id.** After auth, before scan insert, add:

```ts
const onBehalfOfWorkerId = typeof body?.onBehalfOfWorkerId === 'number' ? body.onBehalfOfWorkerId : null
let effectiveWorkerId = auth.workerId
let performedByAdminId: number | null = null
if (onBehalfOfWorkerId != null) {
  if (auth.role !== 'admin') {
    return NextResponse.json({ error: 'Only admins can scan on behalf of a worker' }, { status: 403 })
  }
  const [target] = await db.select().from(workers).where(eq(workers.id, onBehalfOfWorkerId)).limit(1)
  if (!target || target.verificationStatus !== 'approved') {
    return NextResponse.json({ error: 'Target worker is not a verified user' }, { status: 422 })
  }
  effectiveWorkerId = onBehalfOfWorkerId
  performedByAdminId = auth.workerId
}
```

(Import `workers`, `eq` if needed.)

- [ ] **Step 3: Use the effective ids in the scan insert.** Change the scan insert so `workerId: effectiveWorkerId` (replacing `workerId: auth.workerId`) and add `performedByAdminId`.

- [ ] **Step 4: Ensure a Job exists for (route, effectiveWorker) when acting on behalf.** When `onBehalfOfWorkerId != null`, before/after the scan insert, reuse the assign endpoint's find-or-create-Job logic: if no active `Job` (status in claimed/in_progress/pending_review) exists for this route+`effectiveWorkerId`, insert one with `workerId=effectiveWorkerId`, `status='in_progress'`, `payoutAmount=routePayout`, `payoutStatus='pending'`, `performedByAdminId=auth.workerId`. (Extract the existing assign logic into a shared helper `ensureJobForRouteWorker(routeId, workerId, { isTest, performedByAdminId })` in `site/src/lib/` and call it from both places, to stay DRY.)

- [ ] **Step 5: Surface the audit id.** In `site/src/lib/mobile-route-scan-response.ts`'s `toMobileRouteScan`, include `performedByAdminId: row.performedByAdminId ?? null` in the returned object (matching the shared model field name).

- [ ] **Step 6: Typecheck.**

Run: `cd site && npx tsc --noEmit` — Expected: clean.

- [ ] **Step 7: Manual check (if dev server runnable).** As an admin token: `POST /api/scans { routeId, onBehalfOfWorkerId: <verified worker id> }` → 201, scan `workerId` = that worker, `performedByAdminId` = admin. As a non-admin with `onBehalfOfWorkerId` → 403. Admin with an unverified target → 422. As any worker with no `onBehalfOfWorkerId` → unchanged (workerId = self). If the dev server can't run, verify by inspection + note it.

- [ ] **Step 8: Commit.**

```bash
git add site/src/app/api/scans/route.ts site/src/lib/mobile-route-scan-response.ts site/src/lib/*.ts
git commit -m "feat(api): admin onBehalfOfWorkerId scan-as-user with verified guard + job ensure + audit"
```

---

### Task 4: App API client — thread `onBehalfOfWorkerId` + verified filter

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/data/GccApiClient.kt` (or wherever `createScan` and `getAdminWorkers` live — confirm by grep)

**Interfaces:**
- Consumes: the backend from Tasks 2–3.
- Produces: `GccApiClient.createScan(..., onBehalfOfWorkerId: Int? = null)` sends the field only when non-null; `GccApiClient.getAdminWorkers(verificationStatus: String? = null)` appends the query param when non-null.

- [ ] **Step 1: Locate** the client methods: `grep -rn "fun createScan\|getAdminWorkers\|/api/scans\|/api/admin/workers" composeApp/src/commonMain`. Read those methods + their request-body models.

- [ ] **Step 2: Add the optional param to `createScan`.** Add `onBehalfOfWorkerId: Int? = null` to the function signature and the request body (only include the JSON key when non-null so normal calls are unchanged). If the body is a serializable data class, add `val onBehalfOfWorkerId: Int? = null` to it (kotlinx.serialization omits nulls if configured; otherwise gate inclusion).

- [ ] **Step 3: Add the filter to `getAdminWorkers`.** Add `verificationStatus: String? = null`; when non-null, append `?verificationStatus=$verificationStatus` to the URL.

- [ ] **Step 4: Compile.**

Run: `./gradlew :composeApp:compileKotlinMetadata` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/data/GccApiClient.kt
git commit -m "feat(app): api client supports onBehalfOfWorkerId + verified-worker filter"
```

---

### Task 5: App admin UI — verified-user dropdown + Begin

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminRouteDetailScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/AdminPanelViewModel.kt`

**Interfaces:**
- Consumes: `GccApiClient.getAdminWorkers(verificationStatus="approved")` (Task 4).
- Produces: a "Perform as user" section (verified dropdown + Begin) that invokes an `onBeginAsUser(routeId, workerId)` callback wired to launch execution (Task 6).

- [ ] **Step 1: Read** `AdminRouteDetailScreen.kt` (esp. the existing "Assign Route" worker-dropdown UI to mirror its style) and `AdminPanelViewModel.kt` (its `ensureWorkersLoaded()` / `getAdminWorkers()` usage and state holders).

- [ ] **Step 2: Add verified-worker state to the viewmodel.** Add `ensureVerifiedWorkersLoaded()` that calls `apiClient.getAdminWorkers(verificationStatus = "approved")` and stores the result in a `verifiedWorkers` state (mirror the existing workers-loading pattern). Add a `selectedProxyWorkerId` state + setter.

- [ ] **Step 3: Add the "Perform as user" UI** to `AdminRouteDetailScreen.kt`, near the existing assignment section: a dropdown populated from `verifiedWorkers` (label = worker name), and a **Begin** button enabled only when a verified worker is selected. On Begin, call the screen's `onBeginAsUser(route.id, selectedProxyWorkerId)` callback (added as a new screen parameter). Reuse the existing dropdown composable style used for "Assign Route".

- [ ] **Step 4: Compile.**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminRouteDetailScreen.kt composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/AdminPanelViewModel.kt
git commit -m "feat(app-admin): verified-user dropdown + Begin on route detail"
```

---

### Task 6: Thread `onBehalfOfWorkerId` through execution + banner

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteDetailViewModel.kt`
- Modify: the navigation wiring that opens route execution from `AdminRouteDetailScreen`'s `onBeginAsUser` (confirm by grep — likely the app's navigation/root component or the screen host)

**Interfaces:**
- Consumes: `createScan(..., onBehalfOfWorkerId)` (Task 4); `onBeginAsUser(routeId, workerId)` (Task 5).
- Produces: launching execution with a non-null `onBehalfOfWorkerId` runs the scan credited to that worker and shows a "Performing as <name> (admin)" banner.

- [ ] **Step 1: Read** `RouteScanViewModel.kt` + `RouteDetailViewModel.kt` (the scan-creation gate that calls `createScan`) and grep for how route execution is navigated to: `grep -rn "RouteScanViewModel\|RouteDetailScreen\|navigateToRoute\|onBeginAsUser" composeApp/src/commonMain`.

- [ ] **Step 2: Add `onBehalfOfWorkerId: Int?` param** to `RouteDetailViewModel` (and/or `RouteScanViewModel`) — carried from navigation into the `createScan(...)` call so the scan is created on behalf of the selected worker. Default null = normal worker flow (unchanged).

- [ ] **Step 3: Wire `onBeginAsUser`** to navigate into route execution passing `onBehalfOfWorkerId = workerId` (and the routeId). Follow the existing navigation pattern used to open a route for a normal worker.

- [ ] **Step 4: Add the execution banner.** In the route-execution screen, when `onBehalfOfWorkerId != null`, show a small banner: `"Performing as ${workerName} (admin)"` (resolve the name from the loaded verified workers, or pass it through navigation).

- [ ] **Step 5: Compile.**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid` and `:composeApp:compileKotlinMetadata` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteDetailViewModel.kt composeApp/src/commonMain/kotlin/com/gcc/app/
git commit -m "feat(app): run route execution on behalf of verified user + admin banner"
```

---

## Self-Review

**Spec coverage:**
- §5.1 verified filter → Task 2. ✓
- §5.2 audit column → Task 1. ✓
- §5.3 scan-as-user + verified re-check → Task 3. ✓
- §5.4 job ensure → Task 3 (Step 4). ✓
- §5.5 credit user on complete → unchanged (scan workerId is the user); audit id carried → Task 3. ✓
- §6.1 verified dropdown + Begin → Task 5. ✓
- §6.2/6.3 thread onBehalfOfWorkerId through execution → Task 6. ✓
- §6.4 execution banner → Task 6 (Step 4). ✓
- §7 out-of-scope (web admin, payouts) → not touched. ✓

**Type consistency:** `performedByAdminId` (Kotlin `RouteScan`) ↔ `performed_by_admin_id` (DB) ↔ `performedByAdminId` (mobile JSON, Task 3 Step 5). `onBehalfOfWorkerId: Int?` consistent across `createScan` (Task 4), viewmodels (Task 6), and the backend field name (Task 3). `getAdminWorkers(verificationStatus)` (Task 4) matches the query param (Task 2). `ensureJobForRouteWorker` helper (Task 3 Step 4) used by both scans + assign.

**Placeholder scan:** No TBD/TODO. Tasks 3–6 include explicit "read the file first" steps because the exact current contents of `scans/route.ts`, `AdminRouteDetailScreen.kt`, and the viewmodels/navigation aren't reproduced here — the *changes* (field names, guard logic, endpoints, UI additions) are fully specified; the implementer confirms the surrounding code by reading.

**Executor caveats:**
- If the site dev server can't run in the sandbox, backend endpoints verify via `tsc` + inspection (note it in the report); the local homebrew Postgres `gcc` DB is available for `drizzle-kit push`.
- iOS-specific navigation may need a mirror of the Android wiring; `compileDebugKotlinAndroid` + `compileKotlinMetadata` are the gates (iOS compiles in the TestFlight build).
