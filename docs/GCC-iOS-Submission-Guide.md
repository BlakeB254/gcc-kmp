# GCC Worker — iOS App Store Submission Guide

## Your MacBook Setup (cdx-macbook)

**What you have:**
- macOS 26.2 (arm64 Apple Silicon)
- Xcode 26.2
- SSH key access to DGX

**What you need to install first:**
- Homebrew (package manager)
- JDK 17 (required for Gradle/KMP builds)
- Claude Code CLI (optional but recommended)

---

## Step 0: Install Prerequisites

Open **Terminal.app** on your MacBook and run these one at a time:

```bash
# 1. Install Homebrew
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Follow the instructions it prints — you'll need to add brew to your PATH:
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zshrc
source ~/.zshrc

# 2. Install JDK 17 (required for Kotlin/Gradle)
brew install openjdk@17
sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 17)' >> ~/.zshrc
source ~/.zshrc

# Verify
java -version  # Should show 17.x

# 3. Install Node.js (needed if you want to redeploy the site)
brew install node

# 4. Clone the project
mkdir -p ~/CDX-DEV-PROJECTS
cd ~/CDX-DEV-PROJECTS
git clone git@github.com:BlakeB254/gcc-kmp.git
cd gcc-kmp

# 5. Test that Gradle builds the Kotlin framework
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
# First run downloads dependencies — takes 5-10 minutes
```

---

## Development Environment: Terminal + Claude Code vs Xcode

### Option A: Terminal with Claude Code CLI (Recommended for code changes)

**Install:**
```bash
brew install claude-code
# Or: npm install -g @anthropic-ai/claude-code
```

**How to use:**
```bash
cd ~/CDX-DEV-PROJECTS/gcc-kmp
claude
```

**Pros:**
- Full codebase access — can edit Kotlin, Swift, Gradle, server code, site code
- Can run Gradle commands to build frameworks
- Can run git operations, manage branches
- Same experience as your DGX sessions
- Can read/write all project files including shared module, view models, etc.
- Best for making code changes before building in Xcode

**Cons:**
- Cannot open Xcode GUI (use Xcode separately for archive/upload)
- Cannot run iOS Simulator directly (use Xcode for that)
- Cannot manage signing certificates or provisioning profiles

**Best for:** Code changes, debugging build errors, modifying Kotlin/Swift code, running Gradle tasks

---

### Option B: Xcode with Claude Code Extension

**Install:** Search "Claude Code" in Xcode Extensions (Settings → Extensions) or install via the App Store.

**Pros:**
- Inline code suggestions while editing Swift files
- Integrated into the IDE you're already using for Archive/Upload
- Can see build errors and get AI help in context

**Cons:**
- Limited to the files open in the Xcode project — cannot edit Kotlin source, Gradle files, server code, or site code
- The Xcode project only contains the Swift wrapper (`iosApp/`) — the actual app logic is in Kotlin (`composeApp/`, `shared/`) which Xcode doesn't understand
- Cannot run Gradle commands
- Less powerful than the full CLI

**Best for:** Editing the Swift wrapper files (iosAppApp.swift, ContentView.swift, Info.plist), fixing Xcode-specific build issues

---

### Option C: Both Together (Recommended workflow)

**Use Claude Code CLI in Terminal for:**
- All Kotlin code changes (composeApp/, shared/)
- Gradle builds (`./gradlew :composeApp:linkReleaseFrameworkIosArm64`)
- Git operations
- Server changes
- Debugging build failures

**Use Xcode for:**
- Signing & Capabilities configuration
- Running on Simulator (Cmd+R)
- Taking screenshots
- Archive → Upload to App Store Connect
- Managing certificates and provisioning profiles

This is the recommended workflow because **90% of the app code is Kotlin** (which Xcode can't edit), and the **10% that's Swift** is just a thin wrapper.

---

## Step 1: Open the Right Folder

**In Terminal (for Claude Code):**
```bash
cd ~/CDX-DEV-PROJECTS/gcc-kmp
claude
```
Open the **project root** (`gcc-kmp/`), NOT the `iosApp/` subfolder. Claude Code needs access to the full project including `shared/`, `composeApp/`, `server/`, and `site/`.

**In Xcode:**
```bash
open ~/CDX-DEV-PROJECTS/gcc-kmp/iosApp/iosApp.xcodeproj
```
Open the **Xcode project file** specifically. Do NOT open the root folder in Xcode — it doesn't understand Gradle projects.

---

## Step 2: Build the Kotlin Framework

Before Xcode can build the iOS app, Gradle must compile the Kotlin code into a native framework. Do this in Terminal:

```bash
cd ~/CDX-DEV-PROJECTS/gcc-kmp

# For Simulator testing:
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

# For App Store submission (real device):
./gradlew :composeApp:linkReleaseFrameworkIosArm64
```

If either fails, the error is usually a Kotlin compilation issue — use Claude Code in Terminal to debug.

---

## Step 3: Configure Signing in Xcode

1. Open `iosApp/iosApp.xcodeproj` in Xcode
2. Select the **iosApp** project (blue icon) in the navigator
3. Select the **iosApp** target
4. Go to **Signing & Capabilities** tab
5. Check **Automatically manage signing**
6. Select your team from the dropdown (Team ID: 7BBNW25LZM)
7. Verify Bundle ID: `com.globalcommunitycleaners.worker`

If Xcode shows "No signing certificate found":
- Xcode → Settings → Accounts → add your Apple ID
- Xcode will auto-download your certificates

---

## Step 4: Test on Simulator

1. In Xcode, select **iPhone 16** from the device dropdown (top center)
2. Press **Cmd+R** to build and run
3. The app should launch in the Simulator
4. Test: registration screen, login, dashboard

---

## Step 5: Take Screenshots

1. Switch Simulator to **iPhone 15 Pro Max** (6.7" — required by Apple)
2. Navigate to each key screen and press **Cmd+S** to save screenshot
3. Required screenshots (minimum 3):
   - Login/Welcome screen
   - Dashboard with map
   - Route detail or active job view
4. Screenshots save to your Desktop

---

## Step 6: Archive and Upload

```bash
# First, build the RELEASE framework (not debug)
cd ~/CDX-DEV-PROJECTS/gcc-kmp
./gradlew :composeApp:linkReleaseFrameworkIosArm64
```

Then in Xcode:
1. Set destination to **Any iOS Device (arm64)** (not a simulator)
2. **Product → Archive** (takes 3-5 minutes)
3. When Organizer opens: select your archive → **Distribute App**
4. Choose **App Store Connect** → **Upload**
5. Leave signing on automatic → **Upload**
6. Wait for processing (5-30 minutes, you'll get an email)

---

## Step 7: App Store Connect

Go to [appstoreconnect.apple.com](https://appstoreconnect.apple.com):

1. **My Apps → + → New App**
   - Platform: iOS
   - Name: `GCC Worker`
   - Bundle ID: select `com.globalcommunitycleaners.worker`
   - SKU: `gcc-worker-v1`

2. All the text you need is in `docs/app-store-metadata.md`:
   - Copy the Description
   - Copy the Keywords
   - Set the Privacy Policy URL
   - Set the Support URL
   - Answer the Age Rating questions
   - Fill in App Privacy declarations
   - Paste the Review Notes (with demo credentials)

3. Upload your screenshots

4. Select the build from Step 6

5. **Submit for Review**

---

## Step 8: TestFlight (Do This Immediately After Upload)

1. In App Store Connect → your app → **TestFlight** tab
2. The build appears after processing
3. **Internal Testing → + → Create Group**
4. Add your Apple ID email
5. You'll get a TestFlight invite — install on your iPhone
6. Test GPS, camera, and push notifications on real hardware

---

## Troubleshooting

### "No such module 'ComposeApp'"
The Kotlin framework hasn't been built yet. Run:
```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

### "Signing certificate not found"
Xcode → Settings → Accounts → add/re-add your Apple ID.

### Gradle build fails with "JDK not found"
```bash
java -version  # Should show 17.x
echo $JAVA_HOME  # Should point to JDK 17
```

### Archive fails with "Unsupported architecture"
Make sure destination is "Any iOS Device (arm64)", NOT a simulator.

### Build number collision on upload
Increment `CFBundleVersion` in `iosApp/iosApp/Info.plist` (currently `1` → change to `2`).

---

## Claude Code Prompt for iOS Development

If you want Claude Code to help you through the process, paste this when starting a session:

```
I'm working on the GCC Worker KMP app (gcc-kmp). I need to submit it to the iOS App Store.

The project structure:
- composeApp/ — Kotlin Multiplatform UI (Compose Multiplatform)
- shared/ — Kotlin shared module (API client, models, auth)
- iosApp/ — Swift/Xcode wrapper that embeds the Kotlin framework
- server/ — Ktor backend (runs on DGX, exposed via Cloudflare Tunnel)
- site/ — Next.js marketing site on Vercel

Key docs:
- docs/ios-submission-checklist.md — step-by-step checklist
- docs/app-store-metadata.md — all App Store text, review notes, demo credentials

Current status: Code is ready. I need help with:
1. Building the Kotlin framework for iOS
2. Fixing any build errors
3. Configuring Xcode signing
4. Archive and upload process

The Gradle commands I need:
- Simulator: ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
- Release: ./gradlew :composeApp:linkReleaseFrameworkIosArm64

Bundle ID: com.globalcommunitycleaners.worker
Team ID: 7BBNW25LZM
```
