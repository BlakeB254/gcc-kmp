# iOS App Store Submission Checklist

## Prerequisites (DGX)

- [ ] Cloudflare Tunnel running: `scripts/setup-cloudflare-tunnel.sh`
- [ ] Verify API reachable: `curl https://api.globalcommunitycleaners.org/api/health`
- [ ] GCC Ktor server running on port 7310
- [ ] Create Apple review demo account (see docs/app-store-metadata.md)
- [ ] Redeploy site to Vercel (privacy policy update): `cd site && vercel --prod`

## On MacBook (cdx-macbook)

### 1. Pull Latest Code
```bash
cd ~/CDX-DEV-PROJECTS/gcc-kmp
git pull origin main
```

### 2. Apple Developer Portal (developer.apple.com)
- [ ] Register App ID: `com.globalcommunitycleaners.worker`
  - Enable: Push Notifications, Associated Domains, Background Modes
- [ ] Automatic signing will handle certificates and profiles

### 3. Xcode Configuration
```bash
open iosApp/iosApp.xcodeproj
```
- [ ] Select iosApp target → Signing & Capabilities
- [ ] Check "Automatically manage signing"
- [ ] Select your team (7BBNW25LZM)
- [ ] Confirm Bundle ID: `com.globalcommunitycleaners.worker`
- [ ] Set deployment target: iOS 17.0
- [ ] Verify capabilities: Push Notifications, Associated Domains, Background Modes

### 4. Build & Test on Simulator
```bash
# From project root — build Kotlin framework for simulator
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```
- [ ] In Xcode: Select iPhone 16 simulator → Cmd+R
- [ ] Verify app launches, login/register screens work
- [ ] Test demo mode if applicable

### 5. Take Screenshots
- [ ] Run on iPhone 15 Pro Max simulator (6.7" required)
- [ ] Cmd+S in Simulator for each screen
- [ ] Minimum 3 screenshots: Login, Dashboard/Map, Route Detail
- [ ] Recommended: also capture Leaderboard, Profile, Active Job

### 6. Archive for App Store
```bash
# Build release Kotlin/Native framework
./gradlew :composeApp:linkReleaseFrameworkIosArm64
```
- [ ] In Xcode: Set destination to "Any iOS Device (arm64)"
- [ ] Product → Archive (takes 3-5 minutes)
- [ ] Organizer opens → select archive → Distribute App
- [ ] Choose "App Store Connect" → Upload
- [ ] Select "Automatically manage signing"
- [ ] Upload (takes 2-5 minutes)

### 7. App Store Connect (appstoreconnect.apple.com)
- [ ] Create New App (see docs/app-store-metadata.md for all text)
- [ ] Fill: App Information, Pricing (Free), Age Rating
- [ ] Fill: App Privacy declarations
- [ ] Upload screenshots
- [ ] Set Privacy Policy URL: `https://globalcommunitycleaners.org/privacy`
- [ ] Set Support URL: `https://globalcommunitycleaners.org/support`
- [ ] Add Review Notes with demo account credentials
- [ ] Select the uploaded build
- [ ] Submit for Review

### 8. TestFlight (parallel)
- [ ] Create Internal Testing group → add yourself
- [ ] Install via TestFlight on your iPhone
- [ ] Verify real-device behavior (GPS, camera, push notifications)

## Post-Submission
- [ ] Monitor App Store Connect for review status (email notifications)
- [ ] Respond to any reviewer questions within 24h
- [ ] First review typically 24-48 hours
