# App Store distribution runbook (iOS)

The lanes already exist and are guarded (`iosApp/fastlane/Fastfile`). Once a
TestFlight build has field-validated, distribution is three steps, all from
the MacBook:

## 1. Freshen release metadata (5 min, human)

Edit under `iosApp/fastlane/metadata/en-US/`:

- `release_notes.txt` — the what's-new users/testers see. For the env-scan
  overhaul cycle (builds 165-174), lead with: litter identification rebuilt
  (LiDAR ground sensing + tap/draw marking), AR trash anchors that persist
  across visits, scan-verified clean flow, map area galleries.
- `review_information/notes.txt` — reviewer walkthrough (mention the demo
  account skips 2FA and where the env-scan lives).
- `review_information/demo_user.txt` / `demo_password.txt` — verify the demo
  login still works against prod BEFORE submitting.
- `privacy_url.txt` — confirm the URL resolves.

Version string: both lanes default `app_version` to **1.0.7** — bump the
option (`version:1.0.8`) if marketing version should advance with this cycle
(CFBundleShortVersionString in Info.plist must match).

## 2. External TestFlight (Beta App Review)

```bash
export APP_REVIEW_CONTACT_PHONE="+1..."   # required, never defaulted
cd iosApp && bundle exec fastlane distribute build:<N>
```

- Distributes an EXISTING processed build (no rebuild) to the external
  "Beta Testers" group; first external build triggers Beta App Review
  (usually <24h).
- Demo account creds are read from metadata — step 1's verification matters.

## 3. App Store Review

```bash
cd iosApp && bundle exec fastlane submit build:<N> [version:1.0.8]
```

- Submits for review with manual release (approved builds wait in Pending
  Developer Release; pass `automatic_release:true` to auto-release).
- Screenshots are SKIPPED by this lane; if store screenshots need updating,
  run `bundle exec fastlane screenshots` (metadata-only push) after placing
  new images under `metadata/en-US/`.

## Pre-submit checklist

- [ ] Field-validated TestFlight build number chosen (no "latest" defaults)
- [ ] `ITSAppUsesNonExemptEncryption` = false (already in Info.plist)
- [ ] Demo account logs in on prod; 2FA bypass confirmed
- [ ] Privacy policy URL live; App Privacy answers in ASC match current data
      collection (location, camera, photos — plus scan telemetry to R2)
- [ ] release_notes.txt reflects THIS cycle
- [ ] Crash-free session sanity: check ASC → TestFlight → Crashes for the
      chosen build before promoting it
