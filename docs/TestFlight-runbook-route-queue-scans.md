# TestFlight Runbook — Promote route-queue + scans build

**Goal:** Get the `wip/route-queue-and-scans` work (queue, batch release, scan/clean flow) onto TestFlight so Blake can test the cleaning sequence on a real device.

**App identity:** Bundle `com.globalcommunitycleaners.worker` · Team `7BBNW25LZM` · App Store ID 6760203806
**Current:** local v1.0.6 build 99 · **last TestFlight = build 88** (this branch's features have never shipped to TF).
**Prereqs on the Mac:** Xcode 26.2 (`xcode-select -p` set), JDK 17+, signed into the Apple Developer account, repo at `~/CDX-DEV-PROJECTS/gcc`.

> This branch is WIP — **smoke-test in the simulator before archiving.** If it doesn't build, fix-or-fallback before uploading.

---

## Step 0 — Sync & branch
```bash
cd ~/CDX-DEV-PROJECTS/gcc
git status                      # confirm on wip/route-queue-and-scans-20260523, stash if dirty
git fetch origin
git log --oneline -5
```

## Step 1 — Bump build number (avoid collision with 88..99)
Set in Xcode (iosApp target → General) or edit `iosApp/iosApp/Info.plist`:
- `CFBundleShortVersionString` = `1.0.6`
- `CFBundleVersion` = **`100`** (must be > any build already in App Store Connect; bump if 99/100 already used)

## Step 2 — Build the Compose/KMP framework (Release, device)
```bash
cd ~/CDX-DEV-PROJECTS/gcc
./gradlew :composeApp:linkReleaseFrameworkIosArm64
# first run 5-10 min. SDK error? → sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```

## Step 3 — Simulator smoke test FIRST (catch WIP breakage)
```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
open iosApp/iosApp.xcodeproj
```
In Xcode: scheme **iosApp** → destination **iPhone 16 / 15 Pro** → ⌘R. Verify: launch → login (dev/admin) → map (North Lawndale heatmap) → **claim/assigned route → start scan → SCOUTING captures + live feedback → SCOUT_REVIEW → CLEANING after-capture → submit**. If a clean build is needed: ⇧⌘K then rerun the gradle link.

## Step 4 — Archive (device) and upload
**Option A — Xcode Organizer (simplest):**
1. Destination → **Any iOS Device (arm64)**.
2. Product → **Archive** (3–5 min).
3. Organizer → select archive → **Distribute App → App Store Connect → Upload** → automatic signing → Upload.

**Option B — CLI (matches handoff doc):**
```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/ios/gcc.xcarchive archive
xcodebuild -exportArchive -archivePath build/ios/gcc.xcarchive \
  -exportOptionsPlist iosApp/ExportOptions.plist -exportPath build/ios/export
xcrun altool --upload-app --type ios --file build/ios/export/iosApp.ipa \
  --apiKey <key-id> --apiIssuer <issuer-id>     # or upload the .ipa via Transporter.app
```
(If `iosApp/ExportOptions.plist` is missing, use Option A.)

**Option C — fastlane (if lanes are current):** `cd iosApp && fastlane ios upload`

## Step 5 — TestFlight
1. App Store Connect → app → **TestFlight**. Build processes ~5–30 min (export-compliance: no encryption → answer once).
2. **Internal Testing** group → add Blake's Apple ID → install via TestFlight app on a real iPhone.
3. Use a **LiDAR (Pro)** device to exercise depth scans and ideally a non-Pro for image fallback.

## Step 6 — Make the cleaning sequence testable end-to-end
Before the device test, set up test data via the admin panel / API so there's something to clean:
- Confirm a **published route** in North Lawndale (or use `allowUnpublished=true` admin test).
- **Direct-assign** that route to the test worker with `isTest=true` (unpaid), or run a small `route-release` batch.
- Walk the scout→clean flow on the assigned route; confirm scan data lands (admin `GET /api/admin/scans`).

## Rollback / safety
- TestFlight only — does **not** affect the live App Store listing or production users.
- If the WIP build misbehaves, testers stay on build 88; just don't promote to App Store review.
- Don't submit for App Store Review until the dress rehearsal in the test plan passes.

## Common issues
| Symptom | Fix |
|---|---|
| `No such module 'ComposeApp'` | rerun the gradle link step, then ⇧⌘K in Xcode |
| `SDK not found` | `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` |
| Upload rejected: build number used | bump `CFBundleVersion` and re-archive |
| Signing error | iosApp target → Signing → Team 7BBNW25LZM, Automatically manage signing |
| API 404s in app | confirm `getApiBaseUrl()` points at the live API (`gcc-omega.vercel.app` / globalcommunitycleaners.org) |
