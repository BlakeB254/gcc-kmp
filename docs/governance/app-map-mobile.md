# GCC — Mobile App Map (Compose Multiplatform worker app)

iOS bundle `com.globalcommunitycleaners.worker` (App ID 6760203806). Nav = Decompose stack (`navigation/Screen.kt`, `RootComponent.kt`). API base from `PlatformConfig.kt` → prod `https://api.globalcommunitycleaners.org`. API client: `shared/.../api/GccApiClient.kt`. Audited 2026-06-24.

## Screens by area
- **Auth/onboarding:** Login (email + Apple/Google), Register, Onboarding, BiometricGate (Face ID cold start), DocumentUpload, VerificationGate(actionLabel).
- **Main tabs:** Dashboard (routes, active jobs, news), MapFullScreen (heatmap + spots), Admin (if role=admin), Profile. Plus Settings.
- **Routes & jobs:** RouteDetail(routeId), ActiveJob(jobId,routeId) (per-checkpoint before/after evidence), VolunteerCleanup(reportId), SpotVerification(reportId).
- **Scan/clean flow:** RouteScan(routeId, allowUnpublished) → ScoutScreen → ScoutReviewScreen → CleanScreen → ScanSubmittingScreen → ScanCompleteScreen.
- **Community/gamification:** Spot, BountyList/BountyDetail, Feed, Achievements, UserCard, Wallet, Leaderboard, PointsDashboard, Impact, SponsorWall, Articles, Legal.
- **In-app admin:** AdminPanel (tabs: Overview, Routes, Workers, Spots, Bounties), AdminRouteDetail, AdminWorkerDetail, AdminSpotCreate/Detail.

## Scan/clean state machine (`RouteScanViewModel`)
`SCOUTING → SCOUT_REVIEW → CLEANING → SUBMITTING → COMPLETE`
- **Scouting:** `POST /api/scans` (routeId, deviceType, startLat/Lng); GPS breadcrumb (≤500-pt GeoJSON); capture hotspots `POST /api/scans/{id}/hotspots` (lat/lng, beforePhotoUrl via `/api/spots/upload`, severity, trash types, depth); `PATCH …/coverage`; complete via `PATCH …/phase`.
- **Clean:** per hotspot `PATCH /api/scans/{id}/hotspots/{hid}` (afterPhotoUrl, verificationScore, CLEANED/SKIPPED); proximity alerts ~20 m.
- **Complete:** `POST /api/scans/{id}/complete` (cleanPath GeoJSON, volume/weight) → points.
- **Device tiers:** AR (LiDAR/ARKit depth + frustum coverage) vs GPS (radius circles) — `detectDeviceType()`.

## Assignment/queue (client)
- **Direct assignment / self-claim (works in prod):** `GET /api/workers/me/jobs`; claim via `POST /api/jobs/{id}/claim`; lifecycle `start`/`complete`/`decline`/`evidence`.
- **Route offers / queue (client built, ✅ now LIVE in prod 2026-06-24):** `GET /api/workers/me/route-offers`, `…/accept`, `…/decline`; admin `POST /api/admin/route-releases` + `/process`. Ported to the Next.js API (Option B) and validated end-to-end — the app's offer UI now works against prod.

## In-app admin capabilities
Overview (KPIs/activity), Routes (edit, checkpoints, **assign worker `isTest`**, view/cancel assignments; release-batch UI exists but backend missing), Workers (verify, bulk-verify*, grant points, role*, edit, delete), Spots (create/verify/resolve), Bounties (close*/force-reassign*). *Endpoints marked may not exist in prod (parity gap).

## Governance notes
- The app targets the **Ktor** API surface; several calls (route-offers, route-releases, disputes, role, bulk-verify) have **no prod endpoint** and silently fail. Keep app calls in lockstep with `API-reference-production.md`.
- Cleaning scan flow + direct assignment are the **prod-ready** paths — that's what to test on TestFlight build 100.
