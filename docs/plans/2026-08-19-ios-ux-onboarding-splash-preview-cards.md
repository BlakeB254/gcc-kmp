# iOS UX: Onboarding, Splash v2 (video), Flow Smoothing & Store Preview Cards

**Date:** 2026-08-19
**Status:** plan — ready for Blake's review; asset briefs in §5.4 / §6.4 await GO
**Base:** full code inspection of `composeApp` (commonMain/iosMain) + `iosApp` (Swift), 2026-08-19. File:line refs throughout.
**Feeding:** v1.0.9 release (see `2026-08-19-scan-to-world-pipeline-and-ios-release-plan.md` §6)

## 1. What the inspection found (current state, verified in code)

**Entry**
- Splash plays on EVERY cold start: 5 phases, 12s (`ui/screens/SplashScreen.kt:122`), tap-to-skip, GeoJSON+Lottie globe. No first-run gating; iOS `UILaunchScreen` is blank (`Info.plist:79`).
- First launch goes straight to Login — zero product context pre-auth (`RootComponent.kt:25-36`).
- `OnboardingScreen.kt` (3-page pager, emoji art) is post-registration only, and SKIPPED for Google/Apple sign-ins (`RootComponent.kt:46-50`). Weakest visual surface in the app.
- Permissions are lazy/context-free: location from Dashboard load, push during login, camera mid-scan. `PermissionManager.kt` is dead code (hardcoded GRANTED).
- First block assignment = one snackbar (`DashboardScreen.kt:120-123`). No tutorial.

**Scan flows** (design doc 2026-07-03 mostly shipped; the gaps:)
- "Add details" link never shipped → LOCATION/DESCRIPTION phases are ~700 lines of dead code (`SpotScreen.kt:1981-2530`); users can't override AI fields.
- No voluntary abort: Complete Scan is the only exit; a zero-cell scan exits silently (feels like a crash) (`SpotViewModel.kt:923-927`).
- Tap vs. drag gesture collision on one surface (`CameraCapture.kt:156-214`) → spurious candidate pills; draw-to-mark is undiscoverable.
- Readiness is text-only and loses to every higher-priority caption (`SpotScreen.kt:1167-1198`) — users can't tell what to fix.
- Route AR: no "Complete Scout" affordance (design wanted one); loop-closure prompt is one caption line (`ArRouteScreen.kt:389`).
- RESULT phase is a scroll-pile; no per-item review/edit of confirmed litter (only a 6s undo).

**Shell/visual**
- 30 screens, Decompose stack, light-only theme, Inter type, φ-token system (`shared/.../GccDesignTokens.kt`).
- Store assets diverge: three different screenshot lineups (`6.5-inch` / `appstore` / `appstore-framed`).
- Store copy claims MapLibre; shared map is Canvas — verify iOS actual (`ui/map/GccMap.ios.kt`) before card copy claims it.
- Existing share-card precedent: `ui/gamification/ShareableProfileCard.kt:44`.
- No `prefers-reduced-motion`-equivalent check anywhere (gap).

## 2. Flow smoothing (quick wins, v1.0.9)

| # | Fix | Where |
|---|---|---|
| F1 | Wire "Add details" link into Complete Scan row OR delete the 700 dead lines (decision: keep = user override of AI fields) | `SpotScreen.kt:344`, `SpotViewModel.kt:1242` |
| F2 | Add voluntary abort (✕ top-left) with confirm sheet; zero-cell exit shows a summary card instead of silent back | `SpotScreen.kt:1801-1805` |
| F3 | Gesture arbitration: long-press = draw mode, tap = candidate; add a one-time draw hint chip | `CameraCapture.kt:156-214` |
| F4 | Readiness meter UI (baseline/tracking/depth pips) next to `ScanQualityBadge` | `SpotScreen.kt:1717-1731` |
| F5 | Route AR: "Complete Scout" pill when lap gates pass; promote loop-closure to a top-center banner | `ArRouteScreen.kt:354-420,389` |
| F6 | Replace hardcoded bottom-chrome magic numbers with an inset-aware column (kills layout pop when sheets show) | `SpotScreen.kt:1461+`, `ArRouteScreen.kt:430+` |
| F7 | Post-scan review screen between CAMERA→RESULT (item thumbnails, edit/undo beyond 6s) — reuses `ScanSessionSummaryCard`; route twin exists (`ScoutReviewScreen.kt:48`) | `SpotViewModel.completeScan:931-948` |
| F8 | Auto-capture toast ("photo captured") so the silent 2.5s shot isn't invisible | `SpotScanCapture.kt:211-225` |
| F9 | Respect reduced-motion setting app-wide (splash, counters, shimmer) | theme layer |

## 3. Intro carousel (first download, pre-auth)

- New `Screen.Intro` in `navigation/Screen.kt`; gate in `RootComponent.initialScreen` on a new `intro_seen` flag (new `FirstRunStore` beside `TokenManager.kt:224-231` — stop overloading the auth store).
- 4 slides, one per core offering (mirrors the preview-card lineup in §6): **Scan** / **Claim your block** / **Earn** / **See the change**. Art: reuse §6 card backgrounds (one asset feeds both).
- Skippable ("Skip" top-right) and ~10s total; last slide CTA "Create account / Sign in" → existing Login.
- Replaces the emoji `OnboardingScreen` pager for ALL sign-in paths (Google/Apple included — close the `RootComponent.kt:46-50` gap).
- Permission priming as slide-integrated explainers (location + camera), so the system prompts land with context. Optional: wire real `PermissionManager` status (it's currently dead code).

## 4. First-block-assignment tutorial

- Replace the snackbar (`DashboardScreen.kt:109-125`) with a bottom-sheet tutorial the FIRST time an assignment lands: route name, mini-map target (`primaryAssignedPair` at `:158`), 3 steps (open route → walk the boundary → mark litter), CTA "Open route" deep-links to `RouteDetailScreen`.
- Secondary coach mark on first claim (`RouteDetailScreen.kt:66-70`) and first scan entry (`RootComponent.navigateToRouteScan:171-173`), handing off to the guided first-lap (2026-07-03 design) — tutorial points at it, doesn't duplicate it.
- Gate: `tutorial_block_seen` flag, "Show again" entry in Settings.

## 5. Splash v2 — HD video graphic

### 5.1 Concept
A cinematic 8–10s aerial: night-to-dawn push over a stylized North Lawndale grid, LiDAR-green scan pulses sweeping the blocks (the app's signature), resolving into the GCC logo. Ends on a hold frame that cross-fades into the app. **Tap-to-skip stays** (300ms fade, same as today, `SplashScreen.kt:188-190`).

### 5.2 Technical design
- Keep the Compose shell + skip/fade logic; swap the 5-phase Canvas content for a full-bleed video surface: AVPlayer (loop-disabled) via `UIKitView` in iosMain behind an `expect SplashVideo()`; **fallback = current animation** if the asset is missing (returning users, offline).
- First-launch plays full video; returning launches short-circuit to the last ~1.5s logo hold (pass `shortMode` from `App.kt:33`, gated on `intro_seen` — same flag as §3).
- Asset: HEVC 1080×2340 (19.5:9), ≤8 MB, bundled in `iosApp` bundle resources; poster frame as `UILaunchScreen` image (fixes today's blank native launch screen).
- Respect reduced-motion: static poster + logo instead of video (F9).

### 5.3 Why this beats more Canvas
The current 12s hand-rolled animation is the ceiling of code-driven motion; a generated video gives real cinematography (light, atmosphere, depth) at zero runtime cost, and the pipeline (Grok Imagine / vision models) is now in-house.

### 5.4 Asset brief (for Grok Imagine / vision-video lane)
> Cinematic aerial over a stylized Chicago neighborhood grid at pre-dawn blue hour, camera pushing slowly forward ~10s. Blocks are clean geometric shapes with warm windows; pulses of green (GCC #16763A) light sweep across the streets like a scan wave, leaving blocks glowing softly. Final 2s: camera settles, green light converges to center-frame where a subtle circular logo mark forms. No text, no people, no photoreal faces. Clean, premium, hopeful. 1080×2340 portrait, 24fps, 8–10s. Negative: clutter, flicker, text artifacts, cartoon look.
- Production: Grok Imagine draft → pick → (optional) extend/upscale via local video lane (Seedance/LTX) → trim + end-hold frame → HEVC export. Tap-to-skip QA on device.

## 6. Store preview cards (v1.0.9 screenshot set)

### 6.1 Lineup — 6 slides, one CTA per core offering
| # | Slide | Screen content | CTA |
|---|---|---|---|
| 1 | Hero | splash end-frame / brand visual | Clean Streets. Earn Money. |
| 2 | Scan | AR scan HUD w/ reticles + minimap (`SpotScreen` camera phase) | Scan your block |
| 3 | Claim | Map heat + route cards (`MapFullScreen` heat mode) | Claim your route |
| 4 | Bounties | `BountyListScreen` ("Cleanup Opportunities") | Fund or claim a cleanup |
| 5 | Earn | `ScanCompleteScreen` points counter + `LeaderboardScreen` | Get paid same day |
| 6 | Impact | `ImpactScreen` + before/after cell | Watch your neighborhood change |

Canonical order replaces all three divergent sets; output 6.5-inch (1284×2778) + 6.7-inch, framed and raw variants. Fastlane `screenshots` lane pushes metadata-only after assets drop (`docs/release/appstore-distribution.md`).

### 6.2 Design language
- Device-framed UI captures (simulator, light theme, seeded demo data) on a **shared professional background system**: deep-green gradient field, soft top-light, faint North Lawndale street-grid line motif, generous negative space; headline top (Inter 700, 64–72pt) + one-line sub.
- CTA copy mirrors store metadata (`iosApp/fastlane/metadata/en-US/`, `docs/marketing/2026-04-24-gcc-site-and-app-breakdown.md` "$100 cleans your block" worker narrative).
- Verify map copy: don't print "MapLibre" until `GccMap.ios.kt` actual is confirmed.

### 6.3 Background art brief (per-slide variants of one system)
> Premium app-store preview background: deep forest-green gradient (#0B3D22 → #16763A), soft studio top-light, ultra-clean, subtle embossed street-grid lines fading to blur at the edges, gentle depth haze. No text, no logos, no people. 1284×2778, print-sharp. 6 variants by accent: scan-green / route-blue / bounty-amber / earn-gold / impact-teal / hero-neutral.
- Production: Grok Imagine (or local vision lane) → color-grade to brand → composite frames+copy in code (Compose export or Figma-class tooling).

### 6.4 Production plan
1. Generate 6 backgrounds + splash video draft (Imagine lane).
2. Capture updated UIs on simulator post-v1.0.9 UI work (scan HUD with readiness meter, tutorial sheet visible, etc.).
3. Composite slides, QA at 100% zoom (type edges, frame shadows), export framed + raw sets.
4. `bundle exec fastlane screenshots` metadata-only push with the v1.0.9 submission.

## 7. Work order

| # | Item | Owner | Depends on |
|---|---|---|---|
| U1 | Flow fixes F1–F9 (batch PR) | agent (iOS lane) | F1 decision: keep "Add details"? |
| U2 | Intro carousel + FirstRunStore | agent | §6 backgrounds (can ship with placeholder art first) |
| U3 | First-block tutorial sheet + coach marks | agent | — |
| U4 | Splash v2 shell (video surface + short mode + poster launch screen) | agent | §5.4 video asset |
| U5 | Splash video + card backgrounds via Imagine lane | Grok/graphic lane (commission via grok-research-imagine) | Blake GO on briefs §5.4/§6.3 |
| U6 | Preview card compositing + fastlane push | agent + Blake (MacBook) | U1–U5 |

**Decisions for Blake:** (1) F1 keep or delete the details phases; (2) GO on the two Imagine briefs (§5.4 splash video, §6.3 backgrounds) — say go and they're commissioned.
