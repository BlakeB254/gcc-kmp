# GCC Bounty System & Explorer Map Design

**Date**: 2026-03-06
**Status**: Approved

## Overview

Dual-mode map with GPS-gated bounty visibility, peer-to-peer bounty posting (public + secret), spot report integration, and gamification point system.

## 1. Dual-Mode Map Architecture

### Mode Toggle

Segmented control at top of the existing `MapFullScreen`: **"My Area"** | **"Explorer"**

**My Area (default)**:
- Camera bounded to user's assigned community area polygon (e.g., North Lawndale)
- Shows: CommunityInfoPanel, route overlays, local bounties/spots
- Camera pan/zoom constrained to community bounds + ~15% padding
- All bounty markers and amounts visible

**Explorer**:
- Camera bounds released to full Chicago
- Community areas show aggregate counters (messes, bounties, total $)
- Individual bounty markers are GPS-gated: only visible when user is physically in that community area
- Crossing a boundary toggles visibility with smooth fade transition

### Camera Bounding (Soft)

MapLibre Compose lacks native `setMaxBounds`. Implementation:
- After each camera move, check if target is within allowed bounds
- If outside, animate back to nearest in-bounds position
- Mode switch: remove check for Explorer, re-enable + animate-to-fit for My Area

### Community Area Labels

Each area gets a text label at its centroid:
- Zoom 10-11: Hidden
- Zoom 11-13: Area name only (small text)
- Zoom 13-15: Area name + aggregate counters
- Zoom 15+: Fade out

My Area mode: assigned area label is larger/bold/green; adjacent areas in muted gray.
Explorer mode: all areas show names; GPS-current area is highlighted.

## 2. Bounty Map Visualization

### Bounty Markers (Red Dots)

| Zoom | Display |
|---|---|
| Far (10-12) | Red dot (6dp) + "$" text above |
| Medium (12-14) | Red dot (8dp) + "$XX" amount above |
| Close (14+) | Red dot (10dp) + pulse + "$XX.XX" + radius circle |

- Red: `Color(0xFFEF4444)` (existing `PIN_RED`)
- Pulse: orange ring (existing `BOUNTY_PULSE_COLOR`)
- Dollar text: white with dark shadow
- Radius circle: semi-transparent red fill at `radiusMeters`
- Secret bounties: completely invisible

### Spot Report Markers (Orange Triangles)

- Distinct from bounty red dots
- Visible at zoom 13+
- Tap shows: photo thumbnail, description, "Clean This" button, points available

### Community Aggregate Counters (Explorer Mode)

Floating labels on each community polygon at zoom 10-13:
```
  12 messes
  3 bounties
  $175 open
```
Always visible regardless of GPS (aggregate data is public).

### GPS-Gated Visibility (Explorer)

- Poll GPS every ~10 seconds
- Point-in-polygon check against all 77 official community area polygons
- Current area: individual bounty/spot markers visible
- Other areas: aggregate counters only
- Boundary crossing: smooth fade transition

## 3. Bounty Lifecycle

### Creating Bounties

**Public**:
1. Tap spot on map or existing spot report -> "Post Bounty"
2. Form: Title, Description, Amount ($), optional photos
3. Stripe Connect escrow holds funds
4. Bounty appears on map as red dot

**Secret (Private)**:
1. Same form + "Make Secret" toggle
2. Invisible on map, no aggregate counter contribution
3. Poster sees it in "My Bounties" with lock icon

### Stacking

- Multiple users fund the same location via Pledges (`BountyPledges` table)
- New contributors see "Add to Bounty" if one exists at the spot
- Public pledges increase visible $ on map
- Secret pledges invisible until completion
- Example: $25 public + $25 public + $25 secret = "$50" shown, $75 actual payout

### Completion Flow

1. Worker claims (GPS within `radiusMeters`)
2. Cleans mess, takes after-photos
3. Submits completion -> verification (AI + optional manual)
4. Public payout notification: "You earned $50!"
5. Secret bounty reveal: "Bonus! Secret benefactor added $25! Total: $75"
6. Stripe releases escrowed funds
7. Gamification points awarded

### First-Refusal

Spot reporter gets first-refusal on bounties for their reported spot. Already implemented server-side via `firstRefusalExpiresAt`.

## 4. Gamification Points

### Point Values

| Action | Points | Notes |
|---|---|---|
| Report a spot (observer) | 50 | Eyes on the street |
| Clean unbountied spot (altruistic) | 200 | Highest — pure community service |
| Reporter cleans own spot (no bounty) | 250 | Report + clean combo — full lifecycle hero |
| Clean bountied spot | 75 | $ payout is main incentive |
| Early cleanup bonus (< 1 hour) | +50 | Speed bonus on top of base points |
| Post a public bounty | 25 | Funding community good |
| Post a secret bounty | 40 | Anonymous generosity |
| Complete a route | 100 | Core job completion |

**Principles**:
- Altruistic cleanup (200 pts) earns ~2.7x more than bountied (75 pts)
- Reporter who cleans their own report (250 pts) gets highest single-action reward — incentivizes "see it, clean it"
- Early cleanup bonus (+50 pts) rewards fast response within 1 hour of spot creation

### Anti-Gaming

- Cannot claim own bounty
- GPS verification required for claim/clean
- Before + after photos required, AI comparison
- 15-minute cooldown between reporting and cleaning same spot
- 2000 pts/day cap

### Streaks & Multipliers

- **Daily streak**: Consecutive days with at least one cleanup action
  - 3-day streak: 1.2x point multiplier
  - 7-day streak: 1.5x multiplier + "Week Warrior" badge
  - 30-day streak: 2.0x multiplier + "Monthly Champion" badge
- **Weekend warrior bonus**: +25% points for Saturday/Sunday activity (when most people don't clean)
- **Weather hero bonus**: +30% points when cleaning during rain/snow (via weather API check)
- **Night owl bonus**: +20% points for cleanups between 6 PM - 6 AM

### Badges & Achievements

| Badge | Requirement | Rarity |
|---|---|---|
| First Clean | Complete first cleanup | Common |
| Eagle Eye | Report 10 spots | Common |
| Block Captain | Clean 5 spots in one block | Uncommon |
| Neighborhood Guardian | Clean 25 spots in one community area | Uncommon |
| Bounty Hunter | Complete 10 bounties | Uncommon |
| Silent Hero | Complete 5 unbountied cleanups | Rare |
| Full Lifecycle | Report and clean same spot 3 times | Rare |
| Benefactor | Post 10 bounties funding $500+ total | Rare |
| Secret Santa | Post 5 secret bounties | Rare |
| Speed Demon | Earn early cleanup bonus 10 times | Rare |
| Storm Trooper | Clean during adverse weather 5 times | Epic |
| Iron Streak | Maintain 30-day streak | Epic |
| Community Pillar | Earn 10,000 lifetime points | Legendary |
| Founding Cleaner | Active in first 100 days of GCC launch | Limited |

### Seasonal Challenges

- Monthly community-wide goals: "Clean 500 spots in North Lawndale this month"
- Progress bar visible on dashboard and community info panel
- Completion unlocks seasonal badge + bonus points for all participants
- Creates collective motivation beyond individual competition

### Referral Points

- Invite a friend who completes their first cleanup: 100 pts for referrer
- Friend also gets +50 bonus points on their first clean
- Referral chain: if your referral refers someone, you get 25 pts (one level deep only)

### Leaderboard

Existing `/api/leaderboard` endpoint. Profile shows:
- Lifetime points + current multiplier
- Active streak + next milestone
- Community area rank (within North Lawndale)
- City-wide rank (across all community areas)
- Badge showcase (top 3 displayed, full gallery in profile)
- Seasonal challenge progress

## 5. Community Info Panel (Updated)

```
Welcome, [Name]
[green dot] North Lawndale
---
 10      0/10      0
Routes  Available  Jobs
---
 5 messes   3 bounties
 $175 in open bounties
```

New counters from `GET /api/community-stats?area={area_name}`:
- Messes identified count
- Active bounties count
- Total open bounty $ amount

## 6. Map Enhancements

### City Boundary (More Pronounced)

- Width: 1.5dp -> 3dp at zoom 11
- Color: dark gray -> deeper blue-gray `Color(0xFF1E3A5F)`
- Add glow/casing layer for depth

### Dashboard Map Preview

- Camera centered and bounded to assigned area
- Shows area name label and aggregate counters
- Tap opens full map in "My Area" mode

## 7. Payment Architecture

- **Stripe Connect**: Worker accounts already have `stripeAccountId` field
- **Escrow**: Funds held on bounty creation, released on verified completion
- **Refunds**: If bounty expires (configurable, default 30 days), funds return to poster
- **Pledge renewals**: 24-hour renewal window after expiry (already in server)

## 8. What Already Exists

| Component | Status |
|---|---|
| Spot report UI (5-phase) | Built |
| Bounty list/detail screens | Built |
| Bounty API (CRUD + pledges) | Built |
| Database (Bounties, Pledges, Reports) | Built |
| Map rendering (routes, areas, layers) | Built |
| Leaderboard API | Built |
| Camera bounds constraint | Not built |
| Map bounty markers | Not built |
| Explorer mode / GPS gating | Not built |
| Community aggregate counters | Not built |
| Community area name labels | Not built |
| Stripe escrow integration | Not built |
| Secret bounty reveal flow | Not built |
| Gamification point awards | Not built |

## Implementation Priority

1. Map enhancements (city boundary, area labels, camera bounding)
2. Dual-mode segmented control (My Area / Explorer)
3. Bounty marker layer (red dots + $)
4. Spot marker layer (orange triangles)
5. Community aggregate counters
6. GPS-gated layer visibility
7. Community info panel counters
8. Secret bounty creation + reveal flow
9. Gamification point system
10. Stripe Connect escrow integration
