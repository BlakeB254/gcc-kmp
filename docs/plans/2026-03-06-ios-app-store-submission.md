# iOS App Store Submission Preparation

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Prepare the GCC Worker KMP app for iOS App Store submission by hardening the server, fixing production configs, updating legal pages, and preparing store metadata.

**Architecture:** The Ktor server (port 7310) will be exposed via Cloudflare Tunnel at `api.globalcommunitycleaners.org`. Mobile PlatformConfig will point to this HTTPS endpoint. Server gets rate limiting and CORS lockdown. Privacy policy updated with mobile-specific disclosures.

**Tech Stack:** Kotlin/Ktor server, KMP/Compose Multiplatform mobile, Next.js site on Vercel, Cloudflare Tunnel

---

## Task 1: Server Security — Rate Limiting

**Files:**
- Create: `server/src/main/kotlin/com/gcc/server/plugins/RateLimiting.kt`
- Modify: `server/src/main/kotlin/com/gcc/server/Application.kt`
- Modify: `server/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`

Add Ktor's built-in rate-limit plugin. Limit auth endpoints to 10 req/min, general API to 60 req/min.

## Task 2: Server Security — CORS Lockdown & Request Size Limit

**Files:**
- Modify: `server/src/main/kotlin/com/gcc/server/plugins/Cors.kt`
- Modify: `server/src/main/kotlin/com/gcc/server/Application.kt`

Remove `anyHost()`, add explicit mobile origins. Add request body size limit (10MB for photo uploads).

## Task 3: Mobile PlatformConfig — Production API URL

**Files:**
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/PlatformConfig.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/PlatformConfig.kt`

Point to `https://api.globalcommunitycleaners.org` instead of `http://100.67.196.13:7310`.

## Task 4: iOS Info.plist — Remove HTTP Exception

**Files:**
- Modify: `iosApp/iosApp/Info.plist`

Remove the `NSAppTransportSecurity` exception for `100.67.196.13`. HTTPS via Cloudflare Tunnel makes it unnecessary.

## Task 5: Privacy Policy — Mobile App Disclosures

**Files:**
- Modify: `site/src/app/(app)/privacy/page.tsx`

Update "Last updated" date. Add sections for: push notifications, device identifiers, background location tracking. These are required by Apple's App Privacy questionnaire.

## Task 6: Sitemap — Add Legal Pages

**Files:**
- Modify: `site/src/app/sitemap.ts`

Add /privacy, /support, /terms, /faq routes.

## Task 7: App Store Metadata & Review Notes

**Files:**
- Create: `docs/app-store-metadata.md`

App Store description, keywords, category, age rating answers, App Privacy declarations, review notes with demo account instructions.

## Task 8: Cloudflare Tunnel Setup Script

**Files:**
- Create: `scripts/setup-cloudflare-tunnel.sh`
- Create: `docs/ios-submission-checklist.md`

Shell script for MacBook to set up cloudflared on DGX. Checklist doc for the Xcode/App Store Connect steps.
