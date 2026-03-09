# Cleanliness Analytics Layer — Design

**Date:** 2026-03-09
**Status:** Approved

## Purpose

Derive lifecycle metrics from spot verification data so that every user can compare cleanliness between geographic regions. Admins get street-level drill-down; public users see community area scores on the map and dashboard.

## Approach: Real-time SQL Aggregation

All metrics are computed from existing tables (`communityReports`, `spotVerifications`, `pointsLedger`) via aggregate queries. No new tables, no cron, no sync issues.

**Why not materialized stats?** Spot volume will be in the hundreds for the foreseeable future. SQL aggregation on indexed columns is fast enough and avoids stale-data bugs. Migrate to denormalized stats if spot count exceeds 100k.

## Data Sources

| Metric | Source |
|---|---|
| Mess duration | `communityReports.createdAt` → `updatedAt` where `status = 'resolved'` |
| Verification velocity | Time between successive `spotVerifications.createdAt` per report |
| Recurrence rate | Count of spots at the same `locationAddress` within 30 days of a prior resolution |
| Active/resolved counts | `communityReports.status` grouping |
| Geographic grouping | `communityReports.locationNeighborhood` (community area), `communityReports.locationAddress` (street) |

## Cleanliness Score Formula

```
score = 100 - (activeSpotPenalty + durationPenalty + recurrencePenalty)

activeSpotPenalty  = min(40, activeSpots * 3)
durationPenalty    = min(30, avgDurationHours / 4)
recurrencePenalty  = min(30, recurrenceRate * 100)
```

Clamped 0–100. Areas with zero spots score 100.

## API Endpoints

| Endpoint | Auth | Returns |
|---|---|---|
| `GET /api/analytics/cleanliness` | Public | City-wide summary + per-community-area breakdown |
| `GET /api/analytics/cleanliness/[area]` | Public | Single area detail with street-level drill-down |
| `GET /api/spots/[id]` (enriched) | Public | Per-spot verification timeline in existing response |

### Response: City Overview (`/api/analytics/cleanliness`)

```json
{
  "data": {
    "cityScore": 72,
    "totalActiveSpots": 34,
    "totalResolvedSpots": 128,
    "avgMessDurationHours": 36.5,
    "areas": [
      {
        "area": "North Lawndale",
        "activeSpots": 12,
        "resolvedSpots": 45,
        "avgMessDurationHours": 36.5,
        "avgVerificationVelocityHours": 8.0,
        "verificationRate": 0.82,
        "recurrenceRate": 0.15,
        "cleanlinessScore": 72,
        "totalVerifications": 156,
        "totalVolunteerCleanups": 28
      }
    ]
  }
}
```

### Response: Area Detail (`/api/analytics/cleanliness/[area]`)

Same as above, plus `streets` array:

```json
{
  "streets": [
    { "street": "S Pulaski Rd", "activeSpots": 3, "avgDurationHours": 24.0, "score": 65 },
    { "street": "W Roosevelt Rd", "activeSpots": 1, "avgDurationHours": 12.0, "score": 85 }
  ]
}
```

## Kotlin Shared Models

```kotlin
data class CleanlinessStats(area, activeSpots, resolvedSpots, avgMessDurationHours, avgVerificationVelocityHours, verificationRate, recurrenceRate, cleanlinessScore, totalVerifications, totalVolunteerCleanups, streets)

data class StreetCleanlinessStats(street, activeSpots, avgDurationHours, score)

data class CityCleanlinessOverview(cityScore, totalActiveSpots, totalResolvedSpots, avgMessDurationHours, areas)
```

## UI Integration Points

1. **SpotInfoCard** — "Verified X times" + duration since creation
2. **Community area overlay** — cleanliness score badge per area on map
3. **Dashboard** — city-wide + "your area" score card
4. **Admin panel** — full breakdown with street drill-down

## Schema Changes

**None.** All fields needed already exist:
- `communityReports.locationNeighborhood` — community area
- `communityReports.locationAddress` — street-level
- `communityReports.lastVerifiedAt`, `verificationCount`, `createdAt`, `status`
- `spotVerifications.createdAt`, `result`
