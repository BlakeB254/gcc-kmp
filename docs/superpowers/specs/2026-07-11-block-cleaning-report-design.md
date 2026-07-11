# Block Cleaning Report — issue stills + matched-angle before/after — Design

**Date:** 2026-07-11 · **Status:** DGX extraction engine built this session; mobile enablers
(route-lap capture + background location) are the MacBook follow-up · **Author:** Claude

## 1. Blake's brief

For a paid block assignment (~$100): use the BEFORE (baseline) lap's scan video to extract
still frames of every detected issue, then find frames from the SAME ANGLE in the AFTER
(clean) lap — producing an admin+worker cleaning report with estimated trash, images,
timestamps, and the route taken. Uber-style: the app should keep tracking the path in the
background during a session.

## 2. What already exists vs. what was missing

- Detections already carry everything needed to anchor stills: `ObservedSpotItem` has
  lat/lng, itemType, severity, **estimatedWeightGrams** (LiDAR-scaled), and **observedAtMs**
  (epoch millis, added expressly to correlate items to the GPS/heading path).
- Scan video + per-frame pose sidecars exist for ENVIRONMENT scans (Pillar-1 recorder).
- **Gap 1 (mobile):** route laps (`baseline`/`clean`) currently record ZERO capture chunks —
  the recorder isn't wired into the route-scan surface. Until it is, reports can only be
  generated for sessions with video.
- **Gap 2 (DGX):** nothing extracted issue-anchored stills or matched angles — built now.

## 3. The DGX engine (`scripts/reconstruction/block_report.py`, built)

Inputs: a BEFORE and AFTER session id (or the two laps of a route). Per before-item:

1. **Before still** — map `observedAtMs` → sidecar time (wall-clock offset from
   `startedAtMs` onto the ARKit clock), extract the nearest video frame (single-decode
   batch extraction reused from the fusion pipeline).
2. **Matched-angle after still** — in the after lap, pick the GPS path sample minimizing
   distance to the item (must be ≤ `MAX_MATCH_DISTANCE_M`, default 12 m) with heading within
   ±60° of the before heading when available; map its `atMs` → sidecar time → extract frame.
   No qualifying sample → item reported `afterImage: null` (unphotographed, not faked).
3. **Report** — `report.json` + JPEGs to R2 under `reports/{cellId}/{before}__{after}/`:
   items (type, severity, grams, before/after keys), totals (count, estimated grams),
   both laps' timestamps/durations, route polyline + walked distance, generator provenance.

Admin/user rendering (site page reading report.json via presign) + a `block_reports` index
table (+ admin listing + payout-report linkage) are the site-side follow-up.

## 4. Mobile follow-ups (MacBook loop, needs Blake's go)

1. **Enable the Pillar-1 recorder on route laps** (baseline + clean) — the engine is data-
   starved without it. Same thermal guards as env scans.
2. **Uber-style background location**: iOS `allowsBackgroundLocationUpdates` + Always
   authorization so the path keeps recording if the worker backgrounds the app mid-lap;
   AR capture necessarily pauses (camera is foreground-only) but GPS path + timestamps
   continue, keeping route distance and lap timing honest. Requires App Store
   justification copy + battery care (pause after N min stationary).

## 5. Non-goals now

CV re-detection on the after frames ("is it actually clean?" scoring — future, pairs with
the civic-evidence CV matcher); PDF/print rendering of reports; retroactive reports for
chunk-less legacy route laps.
