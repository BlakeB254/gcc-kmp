# iOS Simulator + App Store Submission Handoff

> Written to be run by you at the Mac. Every code change through the dynamic heatmap is landed on `origin/main`; the Mac's 5-minute auto-sync pulls it on its own, or do a manual pull. This doc is the handover because the DGX can't SSH the Mac right now — both Macs are showing offline in Tailscale (macbook last seen 3d ago, mac mini 17d).

**App identity (confirmed in repo):**
- Bundle ID: `com.globalcommunitycleaners.worker`
- Team ID: `7BBNW25LZM`
- Current version: `1.0.4` (`CFBundleShortVersionString`) build `80` (`CFBundleVersion`)

**What landed since the last build that shipped to TestFlight / App Store Connect:**
- New web API `/api/affected-areas` (GeoJSON FeatureCollection for every block with intensity-weighted status)
- `AffectedBlock` + `AffectedBlockStatus` shared Kotlin models + `AffectedAreasRepository` with 30s polling
- `GccMap` expect/actual now takes `affectedBlocks` + `onAffectedBlockTap` (both default empty/null, fully backward compatible with every existing call-site)
- MapLibre Compose layers in both iOS + Android actuals: heatmap-style `CircleLayer` at zoom 8-13 (warm amber density glow) + status-colored `FillLayer` at zoom 13+ (orange=in_progress, green=completed, blue=funded, gray=available) with tap-to-inspect
- `AffectedBlocksViewModel` wraps the repository with Koin-registered singleton, exposes `uiState` StateFlow for the map
- `MapFullScreen` wires the VM via `koinInject`, starts polling on enter, stops on dispose, renders `AffectedBlockInfoCard` when a polygon is tapped (block code, status pill, funding %, sponsor count, next scheduled cleaning date)

---

## One-time sanity check after sync

```bash
# 1. Make sure Tailscale is up so the DGX can reach you in future
tailscale status | grep cdx
# (DGX should show up; if your Mac is offline in the DGX's view, that's why SSH was timing out)

# 2. Get to latest
cd ~/CDX-DEV-PROJECTS/gcc
git fetch origin
git log origin/main --oneline | head -5
# Expect to see:
#   7fe5259 feat(ios-map): wire dynamic heatmap into MapFullScreen
#   6414945 feat(compose-map): render affected-block heatmap + tappable polygons
#   3a52d76 feat(compose-map): add affectedBlocks + onAffectedBlockTap to GccMap API

# If your local main is behind, pull:
git checkout main && git pull --ff-only origin main
```

If the auto-sync launchd timer left WIP branches behind on `origin` (e.g. `macbook-wip-main`), they're snapshot-only — main on GitHub is authoritative.

---

## Run in simulator

```bash
cd ~/CDX-DEV-PROJECTS/gcc

# 1. Build the Compose Multiplatform framework that the Xcode project links against.
#    Use iosSimulatorArm64 on Apple Silicon Macs, iosX64 on Intel.
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

# Expected output: composeApp/build/xcode-frameworks/... with ComposeApp.framework
# If the build fails with "SDK not found" see Troubleshooting below.

# 2. Open the Xcode project.
open iosApp/iosApp.xcodeproj
```

In Xcode:
1. Scheme selector (top bar) → **iosApp**
2. Destination selector → **iPhone 15 Pro** (or iPhone 16 if you have the latest sim)
3. `⌘R` to build and run

Expected flow once the simulator is up:
- Splash → login → dashboard (if you don't have a worker account, use the dev admin account or tap through the guest path)
- Tap the **map** tab → MapFullScreen opens
- Wait ~1 second for the first `/api/affected-areas` fetch → you should see warm amber heatmap glows over the North Lawndale blocks
- Zoom in past level ~13 → polygons fill in with status colors
- Tap a polygon → bottom sheet shows the block code, status, funding %, sponsor count, and next scheduled cleaning date

If the map shows but the heatmap layer doesn't appear, check:
- The `API_BASE_URL` on the simulator resolves to your deployed site (`https://gcc-omega.vercel.app`). Look at `com.gcc.app.platform.getApiBaseUrl()` — iOS actual hardcodes or reads from Info.plist.
- The simulator has network. Safari inside the sim should hit the API at `/api/affected-areas` and return a FeatureCollection.

### Troubleshooting the simulator build

| Symptom | Fix |
|---|---|
| `SDK not found` | `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` |
| `No such module 'ComposeApp'` in Xcode | Rerun `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`, then in Xcode `Product → Clean Build Folder` (`⇧⌘K`) |
| Koin not finding `AffectedAreasRepository` at runtime | The pattern is `single { AffectedAreasRepository(get()) }` in `AppModule.kt`; verify it's in the modules list passed to `startKoin()` — the file was committed but double-check `git log` |
| Network 404 on `/api/affected-areas` | Confirm the API is live: `curl https://gcc-omega.vercel.app/api/affected-areas \| head -c 400` — should return GeoJSON |
| Heatmap renders but no polygons when zoomed in | The zoom threshold is 13; confirm the camera is zoomed past that. Scroll wheel in the simulator works for zoom. |

---

## App Store submission prep

Before archiving for release, walk through this checklist:

### 1. Version bump

The heatmap is a user-facing feature bump. Update both:

```bash
# In Xcode: iosApp target → General tab
#   Version:     1.0.6     (marketing-visible — the one in App Store)
#   Build:       85        (auto-increments, used by TestFlight)
```

Or edit directly: `iosApp/iosApp/Info.plist` (`CFBundleShortVersionString` = 1.0.6, `CFBundleVersion` = 85).

### 2. App Store Connect release notes

Paste this into the "What's New in This Version" field:

```
Dynamic affected-block heatmap — see every funded, scheduled, in-progress,
and completed cleaning in your area at a glance. Tap any block for instant
details on status, sponsor count, and scheduled cleaning date.

Under-the-hood: tighter sponsor co-branded mockups, synced wipe animation,
clearer sign-in discoverability, forward-looking block-availability copy.
```

### 3. Screenshots

`screenshots/appstore/` already has prior-version shots. For the heatmap release, capture fresh 6.7" iPhone screenshots:

- `01-heatmap-overview.png` — MapFullScreen, zoomed to community level, heatmap glows visible
- `02-heatmap-street.png` — zoomed to street level, status-colored polygons visible
- `03-heatmap-tap.png` — info card showing for a tapped block
- `04-dashboard.png` — worker dashboard (already shipping)
- `05-sponsor.png` — sponsor calculator if we're including (optional — depends on whether Apple's review cares about cross-role screens)

Simulator shortcut: `⌘S` saves a screenshot to the Mac desktop (configurable via Device menu).

### 4. Archive + upload

```bash
# Build a release archive
xcodebuild -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath build/ios/gcc.xcarchive \
  archive

# Upload to App Store Connect
xcodebuild -exportArchive \
  -archivePath build/ios/gcc.xcarchive \
  -exportOptionsPlist iosApp/ExportOptions.plist \
  -exportPath build/ios/export
# This generates build/ios/export/iosApp.ipa — use Transporter.app to upload

# OR directly via altool (newer xcrun tool is `notarytool` but for IPA upload use):
xcrun altool --upload-app \
  --type ios \
  --file build/ios/export/iosApp.ipa \
  --apiKey <key-id> \
  --apiIssuer <issuer-id>
```

If `ExportOptions.plist` doesn't exist, Xcode's Organizer (`Window → Organizer`) is the easiest path — archive via the Product menu, then Distribute App.

### 5. TestFlight first

Push the build to TestFlight internal testing before hitting "Submit for Review". Run on a real device, verify the heatmap fetches cleanly over cellular (not just wifi). Even one installer TestFlight loop catches embarrassing regressions.

### 6. Submit

In App Store Connect:
1. App Store tab → select the new version 1.0.6
2. Fill in "What's New" (text above)
3. Upload screenshots (4–5)
4. Build section → select the TestFlight build that passed QA
5. Submit for Review

Apple's review has been 24–48h lately for non-sensitive updates.

---

## Post-submission — things to tell Apple in the review notes

> "This release adds a dynamic heatmap visualization for block cleaning activity. The map data is public information (block locations and cleaning status) served via our public API at https://globalcommunitycleaners.org/api/affected-areas. No sensitive user data is surfaced via the new feature."

Helps avoid a re-review if an Apple reviewer wonders about location data or new API calls.

---

## If you hit any issue

The DGX at `100.67.196.13` is reachable over Tailscale when you're on — I can help debug from there. Either SSH from the Mac back to the DGX (`ssh codex450@100.67.196.13`) or message back and I'll pick up remotely.
