# Admin Interface, Bounty System & Website Parity Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:writing-plans to create implementation plan from this design.

**Goal:** Complete the admin CRUD interface, implement bounty system rules, add bounty analytics dashboards, upgrade the website map to match mobile, and add website bounty pages.

**Architecture:** Three workstreams across the Compose Desktop admin app, Next.js website, and KMP mobile app. Shared design tokens ensure visual consistency. New Ktor API endpoints (stubbed) power bounty analytics. Bounty system is peer-to-peer and location-independent, unlike the GCC-operated route system.

**Tech Stack:** Kotlin/Compose Desktop (admin), Next.js 16/React 19/MapLibre GL JS (website), Kotlin Multiplatform Compose (mobile), Ktor (server), PostgreSQL (data)

---

## Section 1: Route CRUD Completion

### Route List Enhancements
- **Bulk actions toolbar** — select multiple routes, bulk publish/archive
- **Clone route button** — duplicates a route with "(Copy)" suffix
- **Sortable columns** — click column headers to sort by name, status, distance, payout, date

### Route Editor Enhancements
- **Image upload** — drag-drop zone that uploads to `POST /api/admin/media/upload` and returns a URL. Falls back to URL paste.
- **Availability scheduling** — optional `publishAt` / `unpublishAt` datetime fields. Routes auto-transition DRAFT -> PUBLISHED -> ARCHIVED based on timestamps.
- **Route distance auto-calculation** — compute total distance from geometry coordinates and pre-fill the distance field
- **Preview mode** — "Preview as Mobile" button rendering a read-only version matching the mobile app's route detail layout

### New shared model fields
```kotlin
// In AdminRouteRequest
val publishAt: Instant? = null
val unpublishAt: Instant? = null
```

### New API endpoint
```
POST /api/admin/media/upload  -> { url: String }  // multipart file upload
```

---

## Section 2: Bounty System Rules & Flows

### Core Rules
- **Minimum bounty: $10** ($10.00 = 1000 cents). Enforced in mobile creation flow, web form, and API validation.
- **One person per bounty** — first-come-first-served with optimistic locking.
- **GPS-gated acceptance** — must be within geofence radius (~50m) to claim.

### Bounty Acceptance Flow (Mobile)
1. User sees bounty marker on map (visible only in their current community area)
2. Taps marker -> bounty detail sheet slides up (amount, description, photo)
3. "Accept Bounty" button only enabled within geofence. Otherwise: "Get Closer (X m away)"
4. On tap -> `POST /api/bounties/{id}/claim` — optimistic locking: first claim wins, others get HTTP 409
5. Success -> status changes to `IN_PROGRESS`, assigned to that worker
6. Other users see marker change from red (open) to blue (in progress) with claimer's name
7. Failed claim (409) -> toast: "Someone else got there first!"

### API Endpoints
```
POST /api/bounties/{id}/claim     -> Bounty (409 if already claimed)
POST /api/bounties                -> Bounty (validates amount >= 1000 cents)
```

### Admin Bounty Management
- Minimum amount displayed as system setting (read-only, $10)
- Bounty detail shows: claimer info, claim timestamp, GPS coordinates at claim time
- Force-reassign action (admin can unclaim and reopen)

---

## Section 3: Spot Reporter Camera + Map Flow

### Flow (Mobile App)
1. User taps "Report Spot" from map or FAB
2. Camera opens with **mini-map overlay** (120x120dp, top-right corner, rounded) showing real-time GPS as blue dot at street zoom (~17.0). No interaction — purely informational.
3. User snaps photo
4. Transitions to **Spot Placement Screen**:
   - Full-screen map centered on GPS location
   - Red dot placed at user's exact coordinates
   - **Radius slider** at bottom (5m to 200m, default 15m)
   - Semi-transparent red circle expands/contracts as slider moves
   - Live label: "Affected area: ~25m radius"
5. "Submit Report" creates spot report with: photo URL, center coordinates, affected radius, timestamp

### Data Model Additions
```kotlin
// CommunityReport additions
val affectedRadiusMeters: Double = 15.0
val photoUrl: String? = null
val reporterLocation: Coordinates  // GPS at time of snap
```

### Gamification Points
- Cleaning an unbountied spot: **more** gamification points (rewards altruism)
- Cleaning a bountied spot: bounty payout + fewer gamification points

---

## Section 4: Bounty Analytics Dashboard (Admin)

New admin screen under "Data > Analytics" or as a tab within existing Analytics screen.

### Top Stats Row (4 cards)
- Total Bounties Posted
- Total Value Posted ($)
- Avg Bounty Price
- Completion Rate (%)

### Time-Series Chart
- X-axis: dates (daily/weekly/monthly toggle)
- Dual Y-axis: bounty count (left) + total value (right)
- Lines: Posted, Claimed, Completed, Expired

### Location Breakdown
- Table: Community Area | Bounties | Total Value | Avg Price | Completion Rate | Avg Time-to-Claim
- Mini-map showing bounty density as colored circles

### Poster Leaderboard
- Top sponsors/workers by bounties posted
- Columns: Name, Type, Bounties Posted, Total Value, Completion Rate

### Time-to-Claim Distribution
- Bar chart: < 1hr, 1-6hr, 6-24hr, 1-3 days, 3+ days

### New API Endpoints (Stubbed in Ktor)
```
GET /api/admin/analytics/bounty-stats         -> BountyAnalyticsStats
GET /api/admin/analytics/bounty-timeseries    -> List<BountyTimeseriesPoint>
GET /api/admin/analytics/bounty-by-location   -> List<BountyLocationStats>
GET /api/admin/analytics/bounty-leaderboard   -> List<BountyPosterStats>
```

### New Shared Models
```kotlin
@Serializable
data class BountyAnalyticsStats(
    val totalPosted: Int,
    val totalValueCents: Long,
    val avgPriceCents: Int,
    val completionRate: Double,  // 0.0 to 1.0
    val avgTimeToClaimMinutes: Int,
)

@Serializable
data class BountyTimeseriesPoint(
    val date: String,  // ISO date
    val posted: Int,
    val claimed: Int,
    val completed: Int,
    val expired: Int,
    val totalValueCents: Long,
)

@Serializable
data class BountyLocationStats(
    val communityAreaId: Int,
    val communityAreaName: String,
    val bountyCount: Int,
    val totalValueCents: Long,
    val avgPriceCents: Int,
    val completionRate: Double,
    val avgTimeToClaimMinutes: Int,
)

@Serializable
data class BountyPosterStats(
    val userId: Int,
    val userName: String,
    val posterType: PosterType,
    val bountiesPosted: Int,
    val totalValueCents: Long,
    val completionRate: Double,
)
```

---

## Section 5: Website Map Parity + Shared Design System

### Map Upgrade (site/)
- Switch base map from Carto Positron -> OpenFreeMap Liberty
- 3-layer route rendering: glow -> casing -> core (matching mobile)
- Bounty pulse markers (animated red circles)
- Community area boundary fills
- Checkpoint numbered markers (green start, orange end)
- Spot report circles (semi-transparent red with radius)

### Shared Design Tokens

| Token | Value | Used In |
|-------|-------|---------|
| Route line | `#22C55E` | All platforms |
| Route casing | `#14532D` | All platforms |
| Bounty marker | `#EF4444` | All platforms |
| Spot marker | `#F97316` | All platforms |
| Status: draft | gray | Admin + Website |
| Status: published | green | All platforms |
| Status: archived | amber | Admin + Website |
| Checkpoint start | `#16A34A` | All platforms |
| Checkpoint end | `#F97316` | All platforms |

### Files
- New: `site/src/lib/design-tokens.ts` — shared constants
- Modify: `site/src/components/map/BlockMap.tsx` — update base style, add layers
- Modify: `site/src/components/map/RouteMap.tsx` — update base style, 3-layer routes
- New: `site/src/components/map/BountyMap.tsx` — bounty markers + spot circles
- Modify: `adminApp/.../AdminTheme.kt` — add missing tokens to match

---

## Section 6: Website Bounty Pages

**Key distinction:** Routes are GCC-operated (rolled out by location). Bounties are peer-to-peer (anyone can post anywhere).

### `/bounties` — Bounty Explorer (public)
- Hero explaining peer-to-peer model: "See a mess? Post a bounty. Someone nearby will clean it up."
- Full-screen map with all active public bounties across Chicago (no GPS gating on web)
- Click marker -> sidebar with bounty details (photo, amount, description, poster, time)
- Stats bar: "X active bounties | $Y total posted | Z cleaned this week"
- CTA: "Download the app to post or claim bounties"

### `/bounties/how-it-works` — How Bounties Work
- Visual step-by-step: Spot it -> Post it ($10 min) -> Someone claims it (GPS-gated) -> Verified clean -> Payout
- Explain public vs secret bounties
- Explain stacking (multiple bounties on same spot)
- Gamification: unbountied cleanups earn more points

### `/bounties/post` — Post a Bounty (web)
- Map picker: click location or enter address
- Photo upload (optional)
- Amount input with $10 minimum enforced
- Public/Secret toggle
- Stripe checkout
- Confirmation: "Your bounty is live!"

### Existing Page Updates
- **Landing page** — add "Community Bounties" section with live counter + mini-map
- **Sponsor page** — bounty posting as sponsorship option ("Fund a block $500+ or post bounties $10+")
- **Navigation** — add "Bounties" to main nav

### Web vs Mobile Bounty Differences

| Aspect | Mobile App | Website |
|--------|-----------|---------|
| Visibility | GPS-gated to current area | All bounties visible |
| Claiming | Must be at location | "Download app to claim" CTA |
| Posting | In-app with camera | Web form + optional photo |
| Payment | In-app Stripe | Web Stripe checkout |

Website = discovery + posting platform. Mobile app = execution platform.

---

## Section 7: Unified Bounty Visual System

### Color Language (Red-to-Gold Spectrum)

| Element | Color | Hex | Usage |
|---------|-------|-----|-------|
| Open bounty | Red | `#EF4444` | Map dot, card accent, CTA |
| Bounty amount | Green | `#22C55E` | Dollar amounts (brand color) |
| In Progress | Blue | `#3B82F6` | Claimed markers, status |
| Completed | Emerald | `#10B981` | Success states |
| Spot report | Orange | `#F97316` | Spot dots, reporter cards |
| Spotter reward | Gold | `#F59E0B` | Reward badge, tip |
| Secret bounty | Purple | `#8B5CF6` | Hidden amount, surprise |

### Cross-Platform Component Mapping

| Concept | Mobile (Compose) | Admin (Desktop) | Website (React) |
|---------|-----------------|-----------------|----------------|
| Bounty card | GccCard(accent=Gold) | Surface + DataTable | Card gold-50 bg |
| Bounty marker | MapLibre circle+label | AdminMap dot | MapLibre Marker div |
| Amount display | Text(GccGreen, bold) | Text(AdminPrimary) | text-green-500 bold |
| Status chip | StatusChip(color) | StatusBadge | Badge variant |
| Spot card | GccCard(accent=Orange) | Surface+orange border | Card orange-50 bg |

---

## Section 8: Reward the Spotter Feature

### Concept
When posting a bounty linked to a spot report, the funder can optionally tip the person who reported the mess.

### Bounty Creation Flow (Step 3 — Options)
- **Public/Secret toggle** (existing)
- **"Tip the Spotter" checkbox** (new, only when bounty links to a spot report)
  - When checked: reveals amount field with suggested chips ($2, $5, $10)
  - Explanation: "The spotter gets this tip when someone completes the cleanup"
- Total charge breakdown: "Bounty: $25 + Spotter Tip: $5 = $30 total"

### Data Model
```kotlin
// Bounty.kt additions
val spotterTipAmount: Int? = null,   // cents, separate from bounty amount
val spotterTipPaidAt: Instant? = null,
```

### Flow
1. Funder creates bounty on a spot report
2. Checks "Tip the Spotter", enters $5
3. Charged total: bounty + tip
4. On bounty completion: worker gets bounty, spotter gets tip (atomically)

### Admin Visibility
- Bounty detail shows: spotter name, tip amount, tip status
- Analytics: "Total Spotter Tips Paid" metric

---

## Section 9: Enhanced Spot Reporter Flow (Mobile)

### Phase 1 — Camera with Mini-Map
- Camera fills screen
- Mini-map (120x120dp) top-right corner: rounded, semi-transparent, blue GPS dot at street zoom
- Photo counter bottom-left, snap button bottom center

### Phase 2 — Spot Placement with Radius Slider
- Full-screen map centered on GPS
- Red dot at user's coordinates
- Radius slider (5m-200m, default 15m) in bottom card
- Semi-transparent red circle shows affected area
- Live label: "Affected area: ~25m radius"

### Phase 3 — Description (enhanced)
- Description text field + photo thumbnails
- Severity chips: Minor | Moderate | Major | Severe

### Phase 4 — Result (enhanced)
- Success card (green) + reward card (gold)
- Matching bounties (if any) with "Accept" buttons
- "Someone might tip you for spotting this!" text

---

## Section 10: Bounty Acceptance Flow (Mobile, GPS-Gated)

### Map Interaction
- Red pulsing dot with "$XX" label
- Tap -> bounty detail sheet slides up

### Detail Sheet
- Hero mini-map (180dp) with gold zone circle
- Title + status chip
- Large green amount: "$25.00"
- Stacking indicator: "3 pledges — $60 total"
- Before photos (horizontal scroll)
- Accept button:
  - Within 50m: Green PRIMARY, enabled
  - Outside: Gray disabled, "Get closer (127m away)" with live GPS distance
- 409 conflict toast: "Someone else got there first!"
