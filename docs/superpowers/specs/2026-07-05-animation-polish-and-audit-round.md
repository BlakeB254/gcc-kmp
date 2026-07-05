# Animation polish + AR/scan completeness audit round

Date: 2026-07-05
Status: shipped (this branch)

## What shipped

### RealityKit animation polish (all iOS 17-safe transform binds)
- Fog cells shrink away on reveal (route AR) — walking a cell visibly clears it
- Vector-scan cells grow in + endless shimmer pulse (env scan)
- Sector posts pop in / shrink out as capture angles cover (spot AR)
- Markers drop in; expected-trash seeds pulse ("come check this")

### Audit fixes (two-agent audit: codebase completeness + latest-API research)
- **Offline 3D-scan loss (HIGH)**: `EnvScanManifest.objectCapture` — the guided
  capture result now rides the durable offline manifest, not just the online
  submission.
- **Supersede wiped rich estimates (HIGH)**: `recordWorldObservation` now
  MERGES the superseded observation's attributes forward (new keys win) — a
  lean re-scan no longer erases measured weight/width/objectCapture data.
- **Uncanonicalized itemTypes (HIGH)**: on-device-analyzer labels ("Glass",
  "Tires") and AR-detected marker labels are canonicalized via
  `TrashItemCatalog.canonicalType` before persistence; catalog gained plural/
  display-token handling. Ends fallback-dot pollution + taxonomy drift.
- **Coaching overlay per mode (HIGH UX)**: the plane-acquisition coaching
  prompt is an env-scan affordance — route/spot modes now suppress it
  (matches the SceneKit fallbacks).
- **Route floor height (MED)**: floor planes are tracked even when the grid
  is hidden, so route fog/walls sit on the detected floor instead of the
  camera−1.4 m estimate forever.
- **Map glyph gaps (MED)**: main map now renders debris_pile + all landmark
  element types (tree/bush/fence/gate/pole/hydrant/bench/mailbox/sign) as
  distinct badges; minimap gained the missing food_waste glyph.
- **Env-scan tracking parity**: native env scan mirrors AR tracking quality;
  "Hold steady" now surfaces in the scan status line.
- **QuickLook payoff**: capture completion shows measured dims + "View 3D
  model" (AR Quick Look) before attaching — the model is finally visible.
- Smaller: confirm/dismiss clears the detection reticle immediately;
  itemType slice matches varchar(50); pinhole ZoneOutlineOverlay only on the
  non-AR fallback; dead SpotArScreen import removed.

## Recorded roadmap (from the latest-API research; not this round)

| Item | Why | Min OS |
|---|---|---|
| **ARGeoAnchor** (Chicago IS covered) | Globally re-localizable trash markers — replaces GPS↔AR pairing where available, keep pairing as fallback | 14+ |
| Live Activities / Dynamic Island | Coverage % + points during an active scan session | 16.1+ |
| TipKit onboarding | Teach AR gestures contextually | 17.0+ |
| Bloom via `ARView.renderCallbacks.postProcess` | Glow pass over grid/vector effects | 13+ |
| iOS-18-guarded upgrades | OpacityComponent fades, richer particles, Object Capture Area Mode (low-texture piles) | 18+ |
| Foundation Models report descriptions | On-device narrative summaries | 26+ |
| Web map sprites + admin USDZ viewer | Web `icon-image` binds to itemType but registers no images; submitted models are write-only | n/a |
| SpotArScreen wiring | v3 spot-AR surface is code-complete but unreachable from navigation (predates this session) — product decision to wire or remove | n/a |

## Verification
desktopTest, compileKotlinDesktop/IosSimulatorArm64, site tsc, xcodebuild
simulator: all green. Device field test covers PRs #72–#75 together.
