# Admin Proxy Route-Execution — Design Spec

**Date:** 2026-06-30
**Status:** Awaiting review
**Scope:** Let an admin run a full route execution on their own device on behalf of a verified user, crediting the user (with an admin audit trail). For the alpha cohort where users lack an iOS device (no phone, or Android — no Android app yet).

---

## 1. Goal

From the mobile app's Admin area, an admin selects a **verified user** from a dropdown, selects a **route**, presses **Begin**, and runs the normal route-execution (scan) flow on their device. All credit — points, job completion, and the eventual payout — attributes to the **selected user**, while the record also notes which admin performed it.

## 2. Decisions (locked with user)

| Decision | Choice |
|---|---|
| Surface | **Mobile app only** (admin's phone) — extends the existing Admin → Routes screen. Web admin deferred. |
| Execution depth | **Full route execution as the user** — the admin runs the real scout → clean → GPS/photos → complete flow; it all credits the selected user. |
| Attribution | **Credit the user + audit-log the admin** — work/points credit the selected user; the scan/job also stores `performedByAdminId`. |

## 3. Background (from codebase map)

- **Live backend:** Next.js `site/src/app/api/*` (api.globalcommunitycleaners.org → Vercel). All backend work goes here. The Ktor `server/` is a parallel standby.
- **Admin role:** JWT `role` claim; `requireAdmin` (`site/src/lib/admin-auth.ts`) gates admin endpoints.
- **Assignment → Job:** `POST /api/admin/routes/[routeId]/assign` creates `Job(workerId, payoutAmount, status='claimed', payoutStatus='pending')`.
- **Execution = "scan" model:** worker calls `POST /api/scans` (creates `RouteScan(workerId=auth.workerId, phase=scouting)`), logs hotspots, advances phases, then `POST /api/scans/{id}/complete` awards points. The `Job` lifecycle (`/api/jobs/{id}/start|complete`) runs in parallel and already **allows an admin bypass** of ownership checks.
- **Verified user:** `workers.verificationStatus = 'approved'`.

## 4. Gaps to plug

1. `GET /api/admin/workers` returns **all** workers — no verified filter.
2. `POST /api/scans` **hardcodes** `workerId: auth.workerId` (`site/src/app/api/scans/route.ts`) — no way to scan as another user.
3. **No audit field** on `route_scans` for who performed it.

## 5. Backend changes (Next.js `site/`)

1. **Verified-user list** — `GET /api/admin/workers?verificationStatus=approved`: add an optional `verificationStatus` query filter (`WHERE verification_status = ?`). Admin-gated (already is). Returns the existing worker shape.
2. **Audit column** — add nullable `performed_by_admin_id` (int, FK→workers) to `route_scans`; surface as `performedByAdminId: Int?` on the `RouteScan` shared model + `toMobileRouteScan` mapper.
3. **Scan-as-user** — `POST /api/scans` accepts optional `onBehalfOfWorkerId`. When `auth.role === 'admin'` **and** `onBehalfOfWorkerId` is present **and** that worker's `verificationStatus === 'approved'`: create the scan with `workerId = onBehalfOfWorkerId` and `performedByAdminId = auth.workerId`. Otherwise behave exactly as today (`workerId = auth.workerId`). Reject (403) if a non-admin sends `onBehalfOfWorkerId`, or (422) if the target worker isn't verified.
4. **Job presence** — when an admin begins on behalf of a user, ensure a `Job(route, workerId=user)` exists so completion + payout attribute correctly. Reuse the existing assign logic: if no active job for (route, user), create one (`isTest` inherited from the admin's choice); set `performedByAdminId` on the job too (add the same nullable column to `jobs`).
5. Scan `complete` already credits the scan's `workerId` (the selected user) — no change needed beyond carrying the audit id.

## 6. App changes (Compose, `composeApp/`)

1. **Admin → Routes → Route detail** (`AdminRouteDetailScreen.kt`): add a **"Perform as user"** section — a dropdown of **verified** users (loaded via the new filter through `AdminPanelViewModel.ensureVerifiedWorkersLoaded()` → `apiClient.getAdminWorkers(verificationStatus="approved")`), the selected route context, and a **Begin** button (enabled only when a verified user is chosen).
2. **Begin** launches the existing route-execution UI (`RouteDetailViewModel` / `RouteScanViewModel`) in an "on behalf of" mode: the scan-creation call threads `onBehalfOfWorkerId` into `POST /api/scans`. The admin then runs the normal scout → clean → complete flow; all of it credits the selected user.
3. Thread `onBehalfOfWorkerId: Int?` through: `GccApiClient.createScan(...)`, `RouteScanViewModel`, and `RouteDetailViewModel`'s scan-creation gate. Default null = normal worker self-serve behavior (unchanged).
4. A small banner during execution ("Performing as <user name> (admin)") so the admin knows whose route they're running.

## 7. Out of scope

- Web admin surface (deferred).
- **Payouts / approval** — that's the separate "same-day payouts" spec (C). This feature only sets the work up to credit the right user; the payout pipeline consumes it later.
- Stripe, push notifications.

## 8. Data flow

```
Admin (app) → pick verified user U + route R → Begin
  → ensure Job(R, workerId=U, performedByAdminId=admin) exists
  → POST /api/scans { routeId:R, onBehalfOfWorkerId:U }  (admin)
      → RouteScan(workerId=U, performedByAdminId=admin, phase=scouting)
  → admin runs scout → hotspots → clean → POST /api/scans/{id}/complete
      → points credited to U; Job → completed
  → audit: scan/job show performedByAdminId = admin
```

## 9. Risks / verification

- **Verified-only guard** must be enforced server-side (don't trust the client dropdown) — the scan-as-user path re-checks `verificationStatus === 'approved'`.
- **Schema migration** (`performed_by_admin_id` on `route_scans` + `jobs`) via drizzle; additive + nullable, safe.
- **Verify:** TS typecheck + the app compiles (`compileKotlinMetadata` / `compileDebugKotlinAndroid`); functional check on TestFlight (admin runs a route as a test verified user, confirm the scan/job credit that user and the audit id is set). The web e2e/dev path can exercise the new endpoints.
- Keep the default (no `onBehalfOfWorkerId`) path byte-for-byte unchanged so normal workers are unaffected.
