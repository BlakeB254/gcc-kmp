# GCC App — Viral User Card + Map Improvements

**Date**: 2026-03-17
**Status**: Approved

## Overview

Three workstreams to improve the GCC mobile app:

1. **Viral User Card Page** — dedicated screenshotable card page with display handles, multi-level member ranks, and image share
2. **Photo Star Markers** — star icons on route lines at checkpoints where photos were taken
3. **Clickable Route Polygons** — tappable route areas on the map that navigate to route details

---

## Workstream 1: Viral User Card Page

### Goal
A full-screen page users can screenshot and share on social media to flex their badges, rank, and movement tenure. Every screenshot is also a recruitment tool via embedded QR/referral code.

### Data Model Changes

**Worker model** (`shared/src/.../model/Worker.kt`):
- Add `displayHandle: String?` — user-chosen handle (e.g., "cleanking_chi"), stored without "@" prefix, displayed with it
- Add `memberNumber: Int` — sequential signup number, assigned at registration, immutable

**New API endpoint** — `GET /api/member-rank/{workerId}`:
- Returns `{ national: Int, state: Int, local: Int }` — rank by signup order at each geographic level
- National: position among all workers by `createdAt`
- State: position among workers with same `address.state`
- Local: position among workers with same `address.neighborhood` (falls back to `address.city`)

**Registration change**:
- `POST /api/auth/register` assigns `memberNumber` as `MAX(memberNumber) + 1` at insert time

### Card Content (top to bottom)

1. GCC logo + tier label (FOUNDER / PIONEER / EARLY ADOPTER / MEMBER)
2. Avatar initials circle
3. Display handle (`@cleanking_chi`) — falls back to "First L." if no handle set
4. Member ranks: "#47 nationally · #12 in Illinois · #3 in North Lawndale"
5. "Since March 2026"
6. Impact score (large) — `jobs × (rating / 5.0) × (1 + streak / 10.0)`
7. Stats row: Jobs + Streak (no earnings — privacy)
8. Metallic TierBadge with bounce animation
9. Top 5 unlocked badges as emoji circles
10. Badge count text
11. QR code (white on transparent, referral link)
12. Referral code + website footer

### Privacy — Excluded from card
- Email, phone, street address, zip code
- Dollar earnings
- Full name (handle or first-initial format only)
- Worker ID, verification status

### Card Page Layout
- Full-screen dark (#0A0A0A) backdrop
- Card centered, 9:16 aspect, tier-based gradient, subtle glow
- Below card: "Save to Photos" (outlined) + "Share" (primary green)
- Back/close button top-left, no nav bars

### Share Flow
1. Render card composable to PNG (platform-specific bitmap capture)
2. Save to device photo library
3. Open native share sheet with PNG + text: "{handle} · Impact Score {score} · Join: {referral_url}"

### New Files
- `composeApp/.../ui/screens/UserCardScreen.kt` — the page
- `composeApp/.../ui/gamification/ShareableProfileCard.kt` — refactored card (no earnings, with handle + ranks)

### Modified Files
- `shared/.../model/Worker.kt` — add `displayHandle`, `memberNumber`
- `composeApp/.../navigation/Screen.kt` — add `UserCard` route
- `composeApp/.../navigation/RootComponent.kt` — wire navigation
- `composeApp/.../ui/screens/ProfileScreen.kt` — add "My Card" button
- `composeApp/.../ui/screens/SettingsScreen.kt` — add display handle edit field
- `composeApp/.../viewmodel/ProfileViewModel.kt` — add rank loading, handle update
- `site/src/app/api/member-rank/[workerId]/route.ts` — new API endpoint
- `site/src/app/api/auth/register/route.ts` — assign memberNumber on signup

---

## Workstream 2: Photo Star Markers on Route Lines

### Goal
Show where documentation photos were taken along a route, directly on the route line.

### Behavior
- For each checkpoint with a non-null `imageUrl`, render a small gold star marker at `checkpoint.coordinates` on the route polyline
- Star is visually distinct from the numbered checkpoint circles (smaller, gold fill, star shape)
- Tapping a star shows a thumbnail popup of the photo (reuse existing `CheckpointThumbnail`)
- Stars appear in both the main map view and the route detail map

### Implementation
- New composable: `PhotoStarMarker` — gold star icon (16dp) positioned at checkpoint coordinates
- Modify `GccMap.ios.kt` and `GccMap.kt` (common) to render stars for checkpoints where `imageUrl != null`
- On tap: show `CheckpointThumbnail` overlay with the photo

### Files
- New: `composeApp/.../ui/map/PhotoStarMarker.kt`
- Modified: `composeApp/.../ui/map/GccMap.kt` — add star rendering logic
- Modified: `composeApp/.../ui/map/GccMap.ios.kt` — iOS MapKit star annotation

---

## Workstream 3: Clickable Route Polygons

### Goal
Show tappable route coverage areas on the map overview. Tapping navigates to the route detail.

### Behavior
- Each route with `geometry` (GeoJSONLineString) gets a semi-transparent polygon corridor drawn around its line
- Corridor: buffer the line by ~30 meters on each side to create a filled polygon
- Fill: GCC green at 15% opacity, stroke: GCC green at 40% opacity
- Tapping the polygon triggers `onRouteClick(route)` which navigates to RouteDetailScreen
- Polygons visible at overview zoom levels; at close zoom, individual checkpoints dominate

### Implementation
- Line buffering: compute offset polyline on each side of the route geometry to create a closed polygon
- Render as a filled polygon overlay on the map canvas
- Hit testing: check if tap point falls within any route polygon, navigate to the matching route
- Reuse existing `MapRenderers` pattern for drawing

### Files
- New: `composeApp/.../ui/map/RoutePolygon.kt` — polygon composable + buffer geometry math
- Modified: `composeApp/.../ui/map/GccMap.kt` — render polygons, handle tap
- Modified: `composeApp/.../ui/map/GccMap.ios.kt` — iOS MapKit polygon overlay + tap gesture
- Modified: `composeApp/.../ui/map/MapRenderers.kt` — add polygon rendering to canvas

---

## Design Tokens Used
- Fibonacci spacing: 3, 5, 8, 13, 21, 34, 55, 89dp
- Golden ratio: phi = 1.618 (card width = 200 * phi = 324dp)
- Tier gradients: gold (Founder), platinum (Pioneer), silver (Early), green (Standard)
- Badge tier colors: Bronze, Silver, Gold, Platinum, Diamond
- Star marker color: `BadgeGold` (0xFFFFD700)
- Route polygon fill: `GccGreen` at 15% alpha
