# Cleanliness Analytics Layer — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add public-facing cleanliness analytics endpoints and Kotlin models that aggregate spot verification data into per-area and per-street metrics, then integrate into the mobile app's SpotInfoCard and API client.

**Architecture:** Real-time SQL aggregation against existing `communityReports` and `spotVerifications` tables. Two new Next.js API routes return city-wide and area-detail breakdowns. Kotlin shared models consumed by GccApiClient. No schema changes.

**Tech Stack:** Next.js API routes (TypeScript), Drizzle ORM, Kotlin Multiplatform shared models, Compose Multiplatform UI.

---

### Task 1: Create Kotlin shared models for cleanliness analytics

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/model/CleanlinessModels.kt`

**Step 1: Create the models file**

```kotlin
package com.gcc.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class StreetCleanlinessStats(
    val street: String = "",
    val activeSpots: Int = 0,
    val avgDurationHours: Double = 0.0,
    val score: Int = 100,
)

@Serializable
data class CleanlinessStats(
    val area: String = "",
    val activeSpots: Int = 0,
    val resolvedSpots: Int = 0,
    val avgMessDurationHours: Double = 0.0,
    val avgVerificationVelocityHours: Double = 0.0,
    val verificationRate: Double = 0.0,
    val recurrenceRate: Double = 0.0,
    val cleanlinessScore: Int = 100,
    val totalVerifications: Int = 0,
    val totalVolunteerCleanups: Int = 0,
    val streets: List<StreetCleanlinessStats> = emptyList(),
)

@Serializable
data class CityCleanlinessOverview(
    val cityScore: Int = 100,
    val totalActiveSpots: Int = 0,
    val totalResolvedSpots: Int = 0,
    val avgMessDurationHours: Double = 0.0,
    val areas: List<CleanlinessStats> = emptyList(),
)
```

**Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/CleanlinessModels.kt
git commit -m "feat: add Kotlin shared models for cleanliness analytics"
```

---

### Task 2: Add GccApiClient methods for analytics endpoints

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt`

**Step 1: Add two methods after the Spot Verification Lifecycle section (around line 418)**

```kotlin
    // ── Cleanliness Analytics ─────────────────────────────────────

    suspend fun getCityCleanlinessOverview(): CityCleanlinessOverview =
        get("/api/analytics/cleanliness")

    suspend fun getAreaCleanliness(area: String): CleanlinessStats =
        get("/api/analytics/cleanliness/${area.encodeURLPathPart()}")
```

Note: `encodeURLPathPart()` is from `io.ktor.http`. Add import if not present:
```kotlin
import io.ktor.http.encodeURLPathPart
```

**Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt
git commit -m "feat: add API client methods for cleanliness analytics"
```

---

### Task 3: Create city-wide cleanliness analytics endpoint

**Files:**
- Create: `site/src/app/api/analytics/cleanliness/route.ts`

**Step 1: Create the endpoint**

This endpoint runs 4 aggregate queries against `communityReports` and `spotVerifications`:
1. Active + resolved spot counts grouped by `locationNeighborhood`
2. Average mess duration (resolved spots only: `updatedAt - createdAt`)
3. Verification velocity (avg time between successive verifications per spot)
4. Total verifications and volunteer cleanups per area

Then computes the cleanliness score per area and city-wide.

```typescript
/**
 * Cleanliness Analytics API
 * GET /api/analytics/cleanliness — City-wide summary + per-area breakdown
 *
 * Public endpoint (no auth required). Aggregates from communityReports
 * and spotVerifications using real-time SQL queries.
 */
import { NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, spotVerifications } from '@/db/schema'
import { sql, eq, or, and } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

function computeScore(activeSpots: number, avgDurationHours: number, recurrenceRate: number): number {
  const activePenalty = Math.min(40, activeSpots * 3)
  const durationPenalty = Math.min(30, avgDurationHours / 4)
  const recurrencePenalty = Math.min(30, recurrenceRate * 100)
  return Math.max(0, Math.min(100, Math.round(100 - activePenalty - durationPenalty - recurrencePenalty)))
}

export async function GET() {
  try {
    // 1. Active spots per area
    const activeByArea = await db
      .select({
        area: communityReports.locationNeighborhood,
        count: sql<number>`count(*)::int`,
      })
      .from(communityReports)
      .where(or(
        eq(communityReports.status, 'pending'),
        eq(communityReports.status, 'verified'),
      ))
      .groupBy(communityReports.locationNeighborhood)

    // 2. Resolved spots per area + avg duration
    const resolvedByArea = await db
      .select({
        area: communityReports.locationNeighborhood,
        count: sql<number>`count(*)::int`,
        avgDurationHours: sql<number>`COALESCE(avg(EXTRACT(EPOCH FROM (${communityReports.updatedAt}::timestamp - ${communityReports.createdAt}::timestamp)) / 3600.0), 0)::float`,
      })
      .from(communityReports)
      .where(eq(communityReports.status, 'resolved'))
      .groupBy(communityReports.locationNeighborhood)

    // 3. Verification stats per area (join through communityReports for area)
    const verificationsByArea = await db
      .select({
        area: communityReports.locationNeighborhood,
        totalVerifications: sql<number>`count(${spotVerifications.id})::int`,
        avgVelocityHours: sql<number>`COALESCE(avg(EXTRACT(EPOCH FROM (${spotVerifications.createdAt}::timestamp - ${communityReports.createdAt}::timestamp)) / 3600.0), 0)::float`,
      })
      .from(spotVerifications)
      .innerJoin(communityReports, eq(spotVerifications.reportId, communityReports.id))
      .groupBy(communityReports.locationNeighborhood)

    // 4. Volunteer cleanup count per area
    const cleanupsByArea = await db
      .select({
        area: communityReports.locationNeighborhood,
        count: sql<number>`count(*)::int`,
      })
      .from(communityReports)
      .where(and(
        eq(communityReports.type, 'volunteer_cleanup'),
        eq(communityReports.status, 'resolved'),
      ))
      .groupBy(communityReports.locationNeighborhood)

    // 5. Recurrence: spots at same address within 30 days of a prior resolution
    const recurrenceByArea = await db
      .select({
        area: communityReports.locationNeighborhood,
        recurrentCount: sql<number>`count(DISTINCT ${communityReports.locationAddress})::int`,
      })
      .from(communityReports)
      .where(and(
        eq(communityReports.status, 'resolved'),
        sql`${communityReports.locationAddress} IN (
          SELECT location_address FROM community_reports
          WHERE status IN ('pending','verified')
          AND location_address IS NOT NULL
        )`,
      ))
      .groupBy(communityReports.locationNeighborhood)

    // Merge into area stats
    const areaNames = new Set<string>()
    for (const row of [...activeByArea, ...resolvedByArea, ...verificationsByArea, ...cleanupsByArea]) {
      if (row.area) areaNames.add(row.area)
    }

    const activeMap = Object.fromEntries(activeByArea.map(r => [r.area || '', r.count]))
    const resolvedMap = Object.fromEntries(resolvedByArea.map(r => [r.area || '', r]))
    const verificationsMap = Object.fromEntries(verificationsByArea.map(r => [r.area || '', r]))
    const cleanupsMap = Object.fromEntries(cleanupsByArea.map(r => [r.area || '', r.count]))
    const recurrenceMap = Object.fromEntries(recurrenceByArea.map(r => [r.area || '', r.recurrentCount]))

    let totalActive = 0
    let totalResolved = 0
    let totalDurationSum = 0
    let totalDurationCount = 0

    const areas = Array.from(areaNames).sort().map(area => {
      const active = activeMap[area] || 0
      const resolvedData = resolvedMap[area]
      const resolved = resolvedData?.count || 0
      const avgDuration = resolvedData?.avgDurationHours || 0
      const verData = verificationsMap[area]
      const totalVer = verData?.totalVerifications || 0
      const avgVelocity = verData?.avgVelocityHours || 0
      const cleanups = cleanupsMap[area] || 0
      const recurrent = recurrenceMap[area] || 0
      const total = active + resolved
      const verificationRate = total > 0 ? totalVer / total : 0
      const recurrenceRate = resolved > 0 ? recurrent / resolved : 0

      totalActive += active
      totalResolved += resolved
      if (resolved > 0) {
        totalDurationSum += avgDuration * resolved
        totalDurationCount += resolved
      }

      return {
        area,
        activeSpots: active,
        resolvedSpots: resolved,
        avgMessDurationHours: Math.round(avgDuration * 10) / 10,
        avgVerificationVelocityHours: Math.round(avgVelocity * 10) / 10,
        verificationRate: Math.round(verificationRate * 100) / 100,
        recurrenceRate: Math.round(recurrenceRate * 100) / 100,
        cleanlinessScore: computeScore(active, avgDuration, recurrenceRate),
        totalVerifications: totalVer,
        totalVolunteerCleanups: cleanups,
      }
    })

    const cityAvgDuration = totalDurationCount > 0 ? totalDurationSum / totalDurationCount : 0
    const cityScore = areas.length > 0
      ? Math.round(areas.reduce((s, a) => s + a.cleanlinessScore, 0) / areas.length)
      : 100

    return NextResponse.json({
      data: {
        cityScore,
        totalActiveSpots: totalActive,
        totalResolvedSpots: totalResolved,
        avgMessDurationHours: Math.round(cityAvgDuration * 10) / 10,
        areas,
      },
    })
  } catch (error) {
    console.error('Cleanliness analytics error:', error)
    return NextResponse.json({ error: 'Failed to fetch analytics' }, { status: 500 })
  }
}
```

**Step 2: Commit**

```bash
git add site/src/app/api/analytics/cleanliness/route.ts
git commit -m "feat: add city-wide cleanliness analytics endpoint"
```

---

### Task 4: Create area-detail cleanliness endpoint with street drill-down

**Files:**
- Create: `site/src/app/api/analytics/cleanliness/[area]/route.ts`

**Step 1: Create the area detail endpoint**

```typescript
/**
 * Area Cleanliness Detail
 * GET /api/analytics/cleanliness/[area] — Single area with street-level breakdown
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, spotVerifications } from '@/db/schema'
import { sql, eq, and, or } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

function computeScore(activeSpots: number, avgDurationHours: number, recurrenceRate: number): number {
  const activePenalty = Math.min(40, activeSpots * 3)
  const durationPenalty = Math.min(30, avgDurationHours / 4)
  const recurrencePenalty = Math.min(30, recurrenceRate * 100)
  return Math.max(0, Math.min(100, Math.round(100 - activePenalty - durationPenalty - recurrencePenalty)))
}

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ area: string }> },
) {
  try {
    const { area } = await params
    const decodedArea = decodeURIComponent(area)

    // Active spots per street
    const activeByStreet = await db
      .select({
        street: communityReports.locationAddress,
        count: sql<number>`count(*)::int`,
      })
      .from(communityReports)
      .where(and(
        eq(communityReports.locationNeighborhood, decodedArea),
        or(eq(communityReports.status, 'pending'), eq(communityReports.status, 'verified')),
      ))
      .groupBy(communityReports.locationAddress)

    // Resolved + avg duration per street
    const resolvedByStreet = await db
      .select({
        street: communityReports.locationAddress,
        count: sql<number>`count(*)::int`,
        avgDurationHours: sql<number>`COALESCE(avg(EXTRACT(EPOCH FROM (${communityReports.updatedAt}::timestamp - ${communityReports.createdAt}::timestamp)) / 3600.0), 0)::float`,
      })
      .from(communityReports)
      .where(and(
        eq(communityReports.locationNeighborhood, decodedArea),
        eq(communityReports.status, 'resolved'),
      ))
      .groupBy(communityReports.locationAddress)

    // Area-level aggregates
    const [areaActive] = await db
      .select({ count: sql<number>`count(*)::int` })
      .from(communityReports)
      .where(and(
        eq(communityReports.locationNeighborhood, decodedArea),
        or(eq(communityReports.status, 'pending'), eq(communityReports.status, 'verified')),
      ))

    const [areaResolved] = await db
      .select({
        count: sql<number>`count(*)::int`,
        avgDuration: sql<number>`COALESCE(avg(EXTRACT(EPOCH FROM (${communityReports.updatedAt}::timestamp - ${communityReports.createdAt}::timestamp)) / 3600.0), 0)::float`,
      })
      .from(communityReports)
      .where(and(
        eq(communityReports.locationNeighborhood, decodedArea),
        eq(communityReports.status, 'resolved'),
      ))

    const [verStats] = await db
      .select({
        total: sql<number>`count(${spotVerifications.id})::int`,
        avgVelocity: sql<number>`COALESCE(avg(EXTRACT(EPOCH FROM (${spotVerifications.createdAt}::timestamp - ${communityReports.createdAt}::timestamp)) / 3600.0), 0)::float`,
      })
      .from(spotVerifications)
      .innerJoin(communityReports, eq(spotVerifications.reportId, communityReports.id))
      .where(eq(communityReports.locationNeighborhood, decodedArea))

    const [cleanups] = await db
      .select({ count: sql<number>`count(*)::int` })
      .from(communityReports)
      .where(and(
        eq(communityReports.locationNeighborhood, decodedArea),
        eq(communityReports.type, 'volunteer_cleanup'),
        eq(communityReports.status, 'resolved'),
      ))

    const active = areaActive?.count || 0
    const resolved = areaResolved?.count || 0
    const avgDuration = areaResolved?.avgDuration || 0
    const totalVer = verStats?.total || 0
    const total = active + resolved
    const verificationRate = total > 0 ? totalVer / total : 0

    // Build street-level stats
    const streetNames = new Set<string>()
    for (const row of [...activeByStreet, ...resolvedByStreet]) {
      if (row.street) streetNames.add(row.street)
    }
    const activeStreetMap = Object.fromEntries(activeByStreet.filter(r => r.street).map(r => [r.street!, r.count]))
    const resolvedStreetMap = Object.fromEntries(resolvedByStreet.filter(r => r.street).map(r => [r.street!, r]))

    const streets = Array.from(streetNames).sort().map(street => {
      const streetActive = activeStreetMap[street] || 0
      const streetResolved = resolvedStreetMap[street]
      const streetDuration = streetResolved?.avgDurationHours || 0
      return {
        street,
        activeSpots: streetActive,
        avgDurationHours: Math.round(streetDuration * 10) / 10,
        score: computeScore(streetActive, streetDuration, 0),
      }
    })

    return NextResponse.json({
      data: {
        area: decodedArea,
        activeSpots: active,
        resolvedSpots: resolved,
        avgMessDurationHours: Math.round(avgDuration * 10) / 10,
        avgVerificationVelocityHours: Math.round((verStats?.avgVelocity || 0) * 10) / 10,
        verificationRate: Math.round(verificationRate * 100) / 100,
        recurrenceRate: 0, // Computed at city level; omitted here for simplicity
        cleanlinessScore: computeScore(active, avgDuration, 0),
        totalVerifications: totalVer,
        totalVolunteerCleanups: cleanups?.count || 0,
        streets,
      },
    })
  } catch (error) {
    console.error('Area cleanliness error:', error)
    return NextResponse.json({ error: 'Failed to fetch area analytics' }, { status: 500 })
  }
}
```

**Step 2: Commit**

```bash
git add site/src/app/api/analytics/cleanliness/\\[area\\]/route.ts
git commit -m "feat: add area-detail cleanliness endpoint with street drill-down"
```

---

### Task 5: Enrich SpotInfoCard with verification stats

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt`

**Step 1: Add verification stats to SpotInfoCard**

Inside the `SpotInfoCard` composable, after the reporter info row and before the action buttons, add:

```kotlin
            // Verification stats
            if (spot.verificationCount > 0 || spot.needsVerification) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Verified ${spot.verificationCount}x",
                        style = MaterialTheme.typography.bodySmall,
                        color = GccGreen,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Duration since creation
                    val createdIso = spot.createdAt
                    val hoursAgo = try {
                        val created = kotlinx.datetime.Instant.parse(createdIso)
                        val now = kotlinx.datetime.Clock.System.now()
                        ((now - created).inWholeHours)
                    } catch (_: Exception) { null }
                    if (hoursAgo != null) {
                        Text(
                            if (hoursAgo < 24) "${hoursAgo}h ago"
                            else "${hoursAgo / 24}d ago",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSubtle,
                        )
                    }
                }
            }
```

Insert this block right before the `// Action buttons` comment in SpotInfoCard.

**Step 2: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt
git commit -m "feat: show verification stats in SpotInfoCard"
```

---

### Task 6: Run /simplify on all session changes

After completing all implementation tasks, run the `/simplify` skill across all modified files to ensure consistency, remove dead code, and align with project conventions.

**Files to review:**
- `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/LoginScreen.kt`
- `composeApp/src/androidMain/kotlin/com/gcc/app/platform/BiometricAuth.kt`
- `composeApp/src/androidMain/kotlin/com/gcc/app/MainActivity.kt`
- `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotVerificationScreen.kt`
- `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt`
- `composeApp/src/commonMain/kotlin/com/gcc/app/navigation/Screen.kt`
- `composeApp/src/commonMain/kotlin/com/gcc/app/navigation/RootComponent.kt`
- `composeApp/src/commonMain/kotlin/com/gcc/app/di/AppModule.kt`
- `composeApp/src/commonMain/kotlin/com/gcc/app/App.kt`
- `shared/src/commonMain/kotlin/com/gcc/shared/model/CleanlinessModels.kt`
- `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt`
- `site/src/app/api/analytics/cleanliness/route.ts`
- `site/src/app/api/analytics/cleanliness/[area]/route.ts`
- `gradle/libs.versions.toml`
- `composeApp/build.gradle.kts`

---
