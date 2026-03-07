# Admin Interface, Bounty System & Website Parity Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Complete admin CRUD, add bounty analytics, enforce bounty rules, upgrade website maps to match mobile, and add website bounty pages.

**Architecture:** Shared models and server stubs first (foundation), then admin UI, then website, then mobile flows. Each layer builds on the previous.

**Tech Stack:** Kotlin/Compose Desktop, Next.js 16/React 19/MapLibre GL JS, KMP Compose, Ktor server

**Design doc:** `docs/plans/2026-03-07-admin-bounty-website-design.md`

---

## Phase 1: Shared Models & Server Stubs

### Task 1: Add bounty analytics shared models

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/AdminModels.kt`

**Step 1: Add the four new analytics data classes after existing models (after line 158)**

```kotlin
@Serializable
data class BountyAnalyticsStats(
    val totalPosted: Int = 0,
    val totalValueCents: Long = 0,
    val avgPriceCents: Int = 0,
    val completionRate: Double = 0.0,
    val avgTimeToClaimMinutes: Int = 0,
)

@Serializable
data class BountyTimeseriesPoint(
    val date: String = "",
    val posted: Int = 0,
    val claimed: Int = 0,
    val completed: Int = 0,
    val expired: Int = 0,
    val totalValueCents: Long = 0,
)

@Serializable
data class BountyLocationStats(
    val communityAreaId: Int = 0,
    val communityAreaName: String = "",
    val bountyCount: Int = 0,
    val totalValueCents: Long = 0,
    val avgPriceCents: Int = 0,
    val completionRate: Double = 0.0,
    val avgTimeToClaimMinutes: Int = 0,
)

@Serializable
data class BountyPosterStats(
    val userId: Int = 0,
    val userName: String = "",
    val posterType: PosterType = PosterType.WORKER,
    val bountiesPosted: Int = 0,
    val totalValueCents: Long = 0,
    val completionRate: Double = 0.0,
)
```

**Step 2: Add scheduling fields to AdminRouteRequest (lines 35-45)**

Add after `status` field:
```kotlin
val publishAt: String? = null,   // ISO 8601 datetime
val unpublishAt: String? = null, // ISO 8601 datetime
```

**Step 3: Add fields to CommunityReport model**

Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/CommunityReport.kt`

Add after existing fields:
```kotlin
val affectedRadiusMeters: Double = 15.0,
val photoUrl: String? = null,
val reporterLocation: Coordinates? = null,
```

**Step 4: Verify compilation**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc-kmp && ./gradlew :shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/AdminModels.kt shared/src/commonMain/kotlin/com/gcc/shared/model/CommunityReport.kt
git commit -m "feat: add bounty analytics models, route scheduling fields, spot report radius"
```

---

### Task 2: Add bounty analytics API endpoints (server stubs)

**Files:**
- Modify: `server/src/main/kotlin/com/gcc/server/routes/AdminAnalyticsRoutes.kt`

**Step 1: Add four new stubbed bounty analytics endpoints after existing routes (after line 53)**

```kotlin
// Bounty analytics stubs — return demo data until real queries are wired
get("/api/admin/analytics/bounty-stats") {
    call.respond(mapOf(
        "totalPosted" to 147,
        "totalValueCents" to 285000L,
        "avgPriceCents" to 1938,
        "completionRate" to 0.72,
        "avgTimeToClaimMinutes" to 342,
    ))
}

get("/api/admin/analytics/bounty-timeseries") {
    val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
    val now = java.time.LocalDate.now()
    val points = (0 until days).map { i ->
        val date = now.minusDays(i.toLong())
        mapOf(
            "date" to date.toString(),
            "posted" to (3..8).random(),
            "claimed" to (2..6).random(),
            "completed" to (1..5).random(),
            "expired" to (0..2).random(),
            "totalValueCents" to (5000..25000).random().toLong(),
        )
    }.reversed()
    call.respond(points)
}

get("/api/admin/analytics/bounty-by-location") {
    call.respond(listOf(
        mapOf("communityAreaId" to 29, "communityAreaName" to "North Lawndale",
              "bountyCount" to 89, "totalValueCents" to 178000L,
              "avgPriceCents" to 2000, "completionRate" to 0.78,
              "avgTimeToClaimMinutes" to 280),
        mapOf("communityAreaId" to 26, "communityAreaName" to "West Garfield Park",
              "bountyCount" to 34, "totalValueCents" to 61200L,
              "avgPriceCents" to 1800, "completionRate" to 0.65,
              "avgTimeToClaimMinutes" to 420),
        mapOf("communityAreaId" to 27, "communityAreaName" to "East Garfield Park",
              "bountyCount" to 24, "totalValueCents" to 45600L,
              "avgPriceCents" to 1900, "completionRate" to 0.71,
              "avgTimeToClaimMinutes" to 360),
    ))
}

get("/api/admin/analytics/bounty-leaderboard") {
    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
    call.respond(listOf(
        mapOf("userId" to 1, "userName" to "Green Fund Chicago",
              "posterType" to "SPONSOR", "bountiesPosted" to 45,
              "totalValueCents" to 112500L, "completionRate" to 0.82),
        mapOf("userId" to 2, "userName" to "Block Club NL",
              "posterType" to "SPONSOR", "bountiesPosted" to 28,
              "totalValueCents" to 56000L, "completionRate" to 0.75),
        mapOf("userId" to 3, "userName" to "Maria Johnson",
              "posterType" to "WORKER", "bountiesPosted" to 12,
              "totalValueCents" to 18000L, "completionRate" to 0.92),
    ).take(limit))
}
```

**Step 2: Add bounty claim endpoint with $10 minimum validation**

Modify: `server/src/main/kotlin/com/gcc/server/routes/AdminBountyRoutes.kt`

Add after existing routes (after line 82):
```kotlin
// Bounty claim — first-come-first-served with optimistic locking
post("/api/bounties/{id}/claim") {
    val bountyId = call.parameters["id"]?.toIntOrNull()
        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid bounty ID"))
    // TODO: implement real optimistic locking against DB
    // For now, stub with success response
    call.respond(mapOf(
        "id" to bountyId,
        "status" to "IN_PROGRESS",
        "claimedBy" to 1, // would be extracted from JWT
        "message" to "Bounty claimed successfully",
    ))
}

// Bounty creation with $10 minimum
post("/api/bounties") {
    val body = call.receive<Map<String, Any?>>()
    val amount = (body["amount"] as? Number)?.toInt() ?: 0
    if (amount < 1000) {
        return@post call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Minimum bounty amount is $10.00 (1000 cents)")
        )
    }
    // TODO: wire to real bounty creation
    call.respond(HttpStatusCode.Created, mapOf(
        "id" to 999,
        "amount" to amount,
        "status" to "OPEN",
    ))
}

// Force-reassign: unclaim and reopen
put("/api/admin/bounties/{id}/force-reassign") {
    val bountyId = call.parameters["id"]?.toIntOrNull()
        ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid bounty ID"))
    // TODO: wire to real DB update
    call.respond(mapOf(
        "id" to bountyId,
        "status" to "OPEN",
        "claimedBy" to null,
        "message" to "Bounty unclaimed and reopened",
    ))
}
```

**Step 3: Verify compilation**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc-kmp && ./gradlew :server:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add server/src/main/kotlin/com/gcc/server/routes/AdminAnalyticsRoutes.kt server/src/main/kotlin/com/gcc/server/routes/AdminBountyRoutes.kt
git commit -m "feat: stub bounty analytics endpoints and claim/create routes with $10 minimum"
```

---

### Task 3: Add bounty analytics methods to API client

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt`

**Step 1: Add bounty analytics methods after existing analytics methods (after line 129)**

```kotlin
suspend fun getBountyAnalyticsStats(): BountyAnalyticsStats {
    return client.get("$baseUrl/api/admin/analytics/bounty-stats") {
        headers { append("Authorization", "Bearer $token") }
    }.body()
}

suspend fun getBountyTimeseries(days: Int = 30): List<BountyTimeseriesPoint> {
    return client.get("$baseUrl/api/admin/analytics/bounty-timeseries") {
        headers { append("Authorization", "Bearer $token") }
        parameter("days", days)
    }.body()
}

suspend fun getBountyByLocation(): List<BountyLocationStats> {
    return client.get("$baseUrl/api/admin/analytics/bounty-by-location") {
        headers { append("Authorization", "Bearer $token") }
    }.body()
}

suspend fun getBountyLeaderboard(limit: Int = 10): List<BountyPosterStats> {
    return client.get("$baseUrl/api/admin/analytics/bounty-leaderboard") {
        headers { append("Authorization", "Bearer $token") }
        parameter("limit", limit)
    }.body()
}
```

**Step 2: Add bounty claim and force-reassign methods after existing bounty methods (after line 256)**

```kotlin
suspend fun claimBounty(bountyId: Int): Bounty {
    return client.post("$baseUrl/api/bounties/$bountyId/claim") {
        headers { append("Authorization", "Bearer $token") }
    }.body()
}

suspend fun forceReassignBounty(bountyId: Int): Bounty {
    return client.put("$baseUrl/api/admin/bounties/$bountyId/force-reassign") {
        headers { append("Authorization", "Bearer $token") }
    }.body()
}
```

**Step 3: Verify compilation**

Run: `./gradlew :shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt
git commit -m "feat: add bounty analytics and claim API client methods"
```

---

## Phase 2: Admin UI — Bounty Analytics Dashboard

### Task 4: Create BountyAnalyticsViewModel

**Files:**
- Create: `adminApp/src/main/kotlin/com/gcc/admin/viewmodel/BountyAnalyticsViewModel.kt`

**Step 1: Create the viewmodel**

```kotlin
package com.gcc.admin.viewmodel

import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.BountyAnalyticsStats
import com.gcc.shared.model.BountyLocationStats
import com.gcc.shared.model.BountyPosterStats
import com.gcc.shared.model.BountyTimeseriesPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BountyAnalyticsViewModel(private val api: GccApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _stats = MutableStateFlow(BountyAnalyticsStats())
    val stats: StateFlow<BountyAnalyticsStats> = _stats.asStateFlow()

    private val _timeseries = MutableStateFlow<List<BountyTimeseriesPoint>>(emptyList())
    val timeseries: StateFlow<List<BountyTimeseriesPoint>> = _timeseries.asStateFlow()

    private val _locationStats = MutableStateFlow<List<BountyLocationStats>>(emptyList())
    val locationStats: StateFlow<List<BountyLocationStats>> = _locationStats.asStateFlow()

    private val _leaderboard = MutableStateFlow<List<BountyPosterStats>>(emptyList())
    val leaderboard: StateFlow<List<BountyPosterStats>> = _leaderboard.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _timeRange = MutableStateFlow(30) // days
    val timeRange: StateFlow<Int> = _timeRange.asStateFlow()

    init {
        loadAll()
    }

    fun setTimeRange(days: Int) {
        _timeRange.value = days
        loadTimeseries()
    }

    fun loadAll() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _stats.value = api.getBountyAnalyticsStats()
                _timeseries.value = api.getBountyTimeseries(_timeRange.value)
                _locationStats.value = api.getBountyByLocation()
                _leaderboard.value = api.getBountyLeaderboard()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load bounty analytics"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadTimeseries() {
        scope.launch {
            try {
                _timeseries.value = api.getBountyTimeseries(_timeRange.value)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load timeseries"
            }
        }
    }
}
```

**Step 2: Register in Koin DI module**

Find and modify the Koin module file. Look for where `BountyManagementViewModel` is registered and add:
```kotlin
factory { BountyAnalyticsViewModel(get()) }
```

**Step 3: Verify compilation**

Run: `./gradlew :adminApp:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add adminApp/src/main/kotlin/com/gcc/admin/viewmodel/BountyAnalyticsViewModel.kt
git commit -m "feat: add BountyAnalyticsViewModel with time range support"
```

---

### Task 5: Create AdminBountyAnalyticsScreen

**Files:**
- Create: `adminApp/src/main/kotlin/com/gcc/admin/ui/screens/AdminBountyAnalyticsScreen.kt`

**Step 1: Create the screen with 4 stats cards, time-series chart, location table, and leaderboard**

```kotlin
package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.theme.AdminTheme
import com.gcc.admin.viewmodel.BountyAnalyticsViewModel
import org.koin.compose.koinInject

@Composable
fun AdminBountyAnalyticsScreen() {
    val viewModel = koinInject<BountyAnalyticsViewModel>()
    val stats by viewModel.stats.collectAsState()
    val timeseries by viewModel.timeseries.collectAsState()
    val locationStats by viewModel.locationStats.collectAsState()
    val leaderboard by viewModel.leaderboard.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val timeRange by viewModel.timeRange.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Bounty Analytics", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to "7D", 30 to "30D", 90 to "90D").forEach { (days, label) ->
                    FilterChip(
                        selected = timeRange == days,
                        onClick = { viewModel.setTimeRange(days) },
                        label = { Text(label) },
                    )
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        // Stats cards row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
        ) {
            StatsCard(
                title = "Bounties Posted",
                value = stats.totalPosted.toString(),
                modifier = Modifier.weight(1f),
            )
            StatsCard(
                title = "Total Value",
                value = "$${stats.totalValueCents / 100}",
                modifier = Modifier.weight(1f),
            )
            StatsCard(
                title = "Avg Price",
                value = "$${stats.avgPriceCents / 100}.${(stats.avgPriceCents % 100).toString().padStart(2, '0')}",
                modifier = Modifier.weight(1f),
            )
            StatsCard(
                title = "Completion Rate",
                value = "${(stats.completionRate * 100).toInt()}%",
                modifier = Modifier.weight(1f),
            )
        }

        // Time-series chart
        if (timeseries.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(300.dp),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Bounty Activity", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    LineChart(
                        dataSeries = listOf(
                            LineChart.Series("Posted", timeseries.map { it.posted.toFloat() }, com.gcc.admin.ui.theme.ChartGreen),
                            LineChart.Series("Claimed", timeseries.map { it.claimed.toFloat() }, com.gcc.admin.ui.theme.ChartBlue),
                            LineChart.Series("Completed", timeseries.map { it.completed.toFloat() }, com.gcc.admin.ui.theme.ChartGold),
                            LineChart.Series("Expired", timeseries.map { it.expired.toFloat() }, com.gcc.admin.ui.theme.ChartRed),
                        ),
                        labels = timeseries.map { it.date.takeLast(5) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Location breakdown + Leaderboard side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
        ) {
            // Location breakdown table
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("By Location", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    DataTable(
                        items = locationStats,
                        columns = listOf(
                            DataColumn("Area") { Text(it.communityAreaName) },
                            DataColumn("Bounties") { Text(it.bountyCount.toString()) },
                            DataColumn("Total Value") { Text("$${it.totalValueCents / 100}") },
                            DataColumn("Avg Price") { Text("$${it.avgPriceCents / 100}") },
                            DataColumn("Completion") { Text("${(it.completionRate * 100).toInt()}%") },
                            DataColumn("Avg Claim Time") {
                                val hours = it.avgTimeToClaimMinutes / 60
                                Text(if (hours > 0) "${hours}h" else "${it.avgTimeToClaimMinutes}m")
                            },
                        ),
                    )
                }
            }

            // Poster leaderboard
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Top Bounty Posters", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    DataTable(
                        items = leaderboard,
                        columns = listOf(
                            DataColumn("Name") { Text(it.userName) },
                            DataColumn("Type") { Text(it.posterType.name) },
                            DataColumn("Posted") { Text(it.bountiesPosted.toString()) },
                            DataColumn("Value") { Text("$${it.totalValueCents / 100}") },
                            DataColumn("Completion") { Text("${(it.completionRate * 100).toInt()}%") },
                        ),
                    )
                }
            }
        }

        // Time-to-claim distribution bar chart
        if (stats.avgTimeToClaimMinutes > 0) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(250.dp),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Avg Time to Claim: ${stats.avgTimeToClaimMinutes / 60}h ${stats.avgTimeToClaimMinutes % 60}m",
                         style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Detailed time-to-claim distribution requires per-bounty timing data (future backend work)",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
```

**Step 2: Wire into navigation**

Modify: `adminApp/src/main/kotlin/com/gcc/admin/navigation/AdminScreen.kt`

Add new screen class:
```kotlin
data object BountyAnalytics : AdminScreen()
```

Then wire it in the root component and sidebar. In the sidebar, add "Bounty Analytics" under the Data section alongside existing "Analytics".

**Step 3: Verify compilation**

Run: `./gradlew :adminApp:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add adminApp/src/main/kotlin/com/gcc/admin/ui/screens/AdminBountyAnalyticsScreen.kt adminApp/src/main/kotlin/com/gcc/admin/navigation/AdminScreen.kt
git commit -m "feat: add bounty analytics dashboard with stats, charts, location table, leaderboard"
```

---

### Task 6: Enhance AdminBountyDetailScreen with claimer info and force-reassign

**Files:**
- Modify: `adminApp/src/main/kotlin/com/gcc/admin/ui/screens/AdminBountyDetailScreen.kt`
- Modify: `adminApp/src/main/kotlin/com/gcc/admin/viewmodel/BountyManagementViewModel.kt`

**Step 1: Add forceReassign to ViewModel (after forceClose method, ~line 67)**

```kotlin
fun forceReassign(bountyId: Int) {
    scope.launch {
        _isLoading.value = true
        _error.value = null
        try {
            val updated = api.forceReassignBounty(bountyId)
            _selectedBounty.value = updated
            loadBounties()
        } catch (e: Exception) {
            _error.value = e.message ?: "Failed to reassign bounty"
        } finally {
            _isLoading.value = false
        }
    }
}
```

**Step 2: Add claimer info section and force-reassign button to detail screen**

In `AdminBountyDetailScreen.kt`, add after the existing details panel (~line 100):

```kotlin
// Claimer info (if claimed)
bounty.claimedBy?.let { claimerId ->
    Spacer(modifier = Modifier.height(16.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Claimed By", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            DetailRow("Worker ID", claimerId.toString())
            DetailRow("Status", bounty.status.name)
        }
    }
}
```

Add a "Force Reassign" button next to the existing "Force Close" button (~line 131):

```kotlin
if (bounty.status == BountyStatus.IN_PROGRESS || bounty.status == BountyStatus.CLAIMED) {
    Button(
        onClick = { showReassignDialog = true },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
    ) {
        Text("Force Reassign")
    }
}
```

Add the confirmation dialog similar to the existing force-close dialog.

**Step 3: Add minimum bounty amount display**

In the detail panel, add:
```kotlin
DetailRow("Minimum Amount", "$10.00")
DetailRow("Amount", "$${bounty.amount / 100}.${(bounty.amount % 100).toString().padStart(2, '0')}")
```

**Step 4: Verify compilation**

Run: `./gradlew :adminApp:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add adminApp/src/main/kotlin/com/gcc/admin/ui/screens/AdminBountyDetailScreen.kt adminApp/src/main/kotlin/com/gcc/admin/viewmodel/BountyManagementViewModel.kt
git commit -m "feat: add claimer info, force-reassign, and minimum amount to bounty detail"
```

---

### Task 7: Route list enhancements — bulk actions, clone, sortable columns

**Files:**
- Modify: `adminApp/src/main/kotlin/com/gcc/admin/ui/screens/AdminRouteListScreen.kt`
- Modify: `adminApp/src/main/kotlin/com/gcc/admin/viewmodel/RouteManagementViewModel.kt`

**Step 1: Add clone and bulk operations to ViewModel**

In `RouteManagementViewModel.kt`, add after `deleteRoute()` method:

```kotlin
fun cloneRoute(route: Route) {
    startEditRoute(route)
    _editingRouteId.value = null // treat as new
    _editingRoute.value = _editingRoute.value?.copy(
        code = "${route.code}-copy",
        displayName = "${route.displayName} (Copy)",
        status = "draft",
    )
}

fun bulkPublish(routeIds: List<Int>) {
    scope.launch {
        _isLoading.value = true
        _error.value = null
        try {
            routeIds.forEach { id ->
                api.updateRoute(id, AdminRouteRequest(
                    code = "", displayName = "", serviceAreaId = 0,
                    status = "published",
                ))
            }
            loadRoutes()
        } catch (e: Exception) {
            _error.value = e.message ?: "Failed to bulk publish"
        } finally {
            _isLoading.value = false
        }
    }
}

fun bulkArchive(routeIds: List<Int>) {
    scope.launch {
        _isLoading.value = true
        _error.value = null
        try {
            routeIds.forEach { id ->
                api.updateRoute(id, AdminRouteRequest(
                    code = "", displayName = "", serviceAreaId = 0,
                    status = "archived",
                ))
            }
            loadRoutes()
        } catch (e: Exception) {
            _error.value = e.message ?: "Failed to bulk archive"
        } finally {
            _isLoading.value = false
        }
    }
}
```

**Step 2: Add selection state, sort state, and bulk toolbar to the list screen**

In `AdminRouteListScreen.kt`, add state variables at the top of the composable:

```kotlin
var selectedRouteIds by remember { mutableStateOf(setOf<Int>()) }
var sortColumn by remember { mutableStateOf("name") }
var sortAscending by remember { mutableStateOf(true) }
```

Add a bulk actions toolbar that appears when routes are selected:

```kotlin
if (selectedRouteIds.isNotEmpty()) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${selectedRouteIds.size} selected")
            Button(onClick = { viewModel.bulkPublish(selectedRouteIds.toList()); selectedRouteIds = emptySet() }) {
                Text("Publish")
            }
            Button(onClick = { viewModel.bulkArchive(selectedRouteIds.toList()); selectedRouteIds = emptySet() }) {
                Text("Archive")
            }
            TextButton(onClick = { selectedRouteIds = emptySet() }) {
                Text("Clear")
            }
        }
    }
}
```

Add clone action to each row's context menu or as an icon button in the table.

Sort the routes list based on `sortColumn` and `sortAscending` using `remember(routes, sortColumn, sortAscending)`.

**Step 3: Verify compilation**

Run: `./gradlew :adminApp:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add adminApp/src/main/kotlin/com/gcc/admin/ui/screens/AdminRouteListScreen.kt adminApp/src/main/kotlin/com/gcc/admin/viewmodel/RouteManagementViewModel.kt
git commit -m "feat: add route bulk actions, clone, and sortable columns to admin list"
```

---

### Task 8: Route editor — scheduling, distance auto-calc

**Files:**
- Modify: `adminApp/src/main/kotlin/com/gcc/admin/ui/screens/AdminRouteEditorScreen.kt`
- Modify: `adminApp/src/main/kotlin/com/gcc/admin/viewmodel/RouteManagementViewModel.kt`

**Step 1: Add publishAt/unpublishAt field handling to ViewModel**

In `RouteManagementViewModel.kt`, add to the `updateField()` when-block:

```kotlin
"publishAt" -> current.copy(publishAt = value as? String)
"unpublishAt" -> current.copy(unpublishAt = value as? String)
```

**Step 2: Add scheduling fields to the editor left panel**

In `AdminRouteEditorScreen.kt`, add after the status chips section:

```kotlin
// Availability scheduling
Text("Scheduling", style = MaterialTheme.typography.titleSmall)
Spacer(modifier = Modifier.height(4.dp))
OutlinedTextField(
    value = editingRoute?.publishAt ?: "",
    onValueChange = { viewModel.updateField("publishAt", it.ifBlank { null }) },
    label = { Text("Publish At (ISO 8601)") },
    placeholder = { Text("2026-03-15T09:00:00Z") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
)
Spacer(modifier = Modifier.height(4.dp))
OutlinedTextField(
    value = editingRoute?.unpublishAt ?: "",
    onValueChange = { viewModel.updateField("unpublishAt", it.ifBlank { null }) },
    label = { Text("Unpublish At (ISO 8601)") },
    placeholder = { Text("2026-04-15T17:00:00Z") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
)
```

**Step 3: Add distance auto-calculation**

In the ViewModel, add a method:

```kotlin
fun autoCalculateDistance() {
    val checkpoints = _editingCheckpoints.value
    if (checkpoints.size < 2) return
    var totalMeters = 0.0
    for (i in 0 until checkpoints.size - 1) {
        val a = checkpoints[i]
        val b = checkpoints[i + 1]
        totalMeters += haversineDistance(a.lat, a.lng, b.lat, b.lng)
    }
    _editingRoute.value = _editingRoute.value?.copy(distanceMeters = totalMeters)
}

private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val R = 6371000.0 // Earth radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}
```

In the editor, add a button next to the distance field:

```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(
        value = editingRoute?.distanceMeters?.let { "%.0f".format(it) } ?: "",
        onValueChange = { viewModel.updateField("distanceMeters", it.toDoubleOrNull()) },
        label = { Text("Distance (m)") },
        modifier = Modifier.weight(1f),
        singleLine = true,
    )
    IconButton(onClick = { viewModel.autoCalculateDistance() }) {
        Text("📏", style = MaterialTheme.typography.titleMedium)
    }
}
```

**Step 4: Verify compilation**

Run: `./gradlew :adminApp:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add adminApp/src/main/kotlin/com/gcc/admin/ui/screens/AdminRouteEditorScreen.kt adminApp/src/main/kotlin/com/gcc/admin/viewmodel/RouteManagementViewModel.kt
git commit -m "feat: add route scheduling fields and distance auto-calculation"
```

---

## Phase 3: Website — Design Tokens & Map Parity

### Task 9: Create shared design tokens for website

**Files:**
- Create: `site/src/lib/design-tokens.ts`

**Step 1: Create the design tokens file**

```typescript
/**
 * GCC Design Tokens — shared visual constants matching mobile app (MapStyles.kt)
 * and admin app (AdminTheme.kt). Keep in sync across all platforms.
 */

// Map base style — OpenFreeMap Liberty (matches mobile app)
export const MAP_STYLE_URL = 'https://tiles.openfreemap.org/styles/liberty'

// Chicago center coordinates
export const CHICAGO_CENTER = { lat: 41.8603, lng: -87.7228 } as const
export const CHICAGO_CENTER_LNG_LAT: [number, number] = [-87.7228, 41.8603]

// Zoom levels (matching MapStyles.kt)
export const ZOOM = {
  CITY: 11.0,
  SERVICE_AREA: 13.2,
  NEIGHBORHOOD: 14.0,
  ROUTE: 15.0,
  ACTIVE_JOB: 17.0,
  STREET: 19.0,
} as const

// Route rendering (3-layer: glow → casing → core)
export const ROUTE_COLORS = {
  LINE: '#22C55E',       // green-500
  CASING: '#14532D',     // green-950
  GLOW: '#22C55E',       // green-500 at low opacity
  COMPLETED: '#6B7280',  // gray-500
} as const

export const ROUTE_WIDTHS = {
  GLOW: 14,
  CASING: 8,
  CORE: 5,
} as const

// Route status colors (matching mobile + admin)
export const ROUTE_STATUS_COLORS = {
  published: { line: '#22C55E', casing: '#14532D' },
  claimed: { line: '#F59E0B', casing: '#78350F' },
  active: { line: '#3B82F6', casing: '#1E3A8A' },
  completed: { line: '#6B7280', casing: '#1F2937' },
} as const

// Checkpoint colors
export const CHECKPOINT_COLORS = {
  START: '#16A34A',      // green-600
  END: '#F97316',        // orange-500
  PHOTO: '#FFFFFF',      // white with border
  DEFAULT: '#FFFFFF',    // white numbered circle
} as const

// Bounty markers
export const BOUNTY_COLORS = {
  OPEN: '#EF4444',       // red-500
  IN_PROGRESS: '#3B82F6', // blue-500
  COMPLETED: '#22C55E',  // green-500
  PULSE: '#F97316',      // orange-500
  RADIUS_FILL: 'rgba(239, 68, 68, 0.1)', // red at 10%
} as const

// Spot report markers
export const SPOT_COLORS = {
  MARKER: '#F97316',     // orange-500
  RADIUS_FILL: 'rgba(249, 115, 22, 0.15)', // orange at 15%
  RADIUS_STROKE: '#F97316',
} as const

// Community area styling
export const COMMUNITY_AREA_COLORS = {
  FILL: 'rgba(239, 68, 68, 0.08)',  // subtle red fill
  STROKE: '#EF4444',                 // red border
  STROKE_WIDTH: 2,
} as const

// Block status colors (matching BlockMap.tsx existing)
export const BLOCK_STATUS_COLORS = {
  unfunded: { fill: '#6B7280', opacity: 0.2 },
  available: { fill: '#22C55E', opacity: 0.5 },
  claimed: { fill: '#F59E0B', opacity: 0.6 },
  in_progress: { fill: '#3B82F6', opacity: 0.7 },
  pending_review: { fill: '#8B5CF6', opacity: 0.6 },
  completed: { fill: '#10B981', opacity: 0.4 },
} as const

// General status badge colors
export const STATUS_COLORS = {
  draft: { bg: '#374151', text: '#9CA3AF' },      // gray
  published: { bg: '#065F46', text: '#34D399' },   // green
  archived: { bg: '#78350F', text: '#FBBF24' },    // amber
  open: { bg: '#7F1D1D', text: '#FCA5A5' },        // red
  in_progress: { bg: '#1E3A8A', text: '#93C5FD' }, // blue
  completed: { bg: '#065F46', text: '#6EE7B7' },   // green
} as const

// Minimum bounty amount (cents)
export const BOUNTY_MINIMUM_CENTS = 1000 // $10.00
```

**Step 2: Verify no syntax errors**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc-kmp/site && npx tsc --noEmit src/lib/design-tokens.ts 2>&1 || echo "Check errors"`

**Step 3: Commit**

```bash
git add site/src/lib/design-tokens.ts
git commit -m "feat: add shared design tokens matching mobile MapStyles.kt"
```

---

### Task 10: Upgrade RouteMap.tsx to OpenFreeMap Liberty + 3-layer rendering

**Files:**
- Modify: `site/src/components/map/RouteMap.tsx`

**Step 1: Import design tokens at top of file**

Replace the hardcoded Carto Positron URL and color constants with imports:

```typescript
import {
  MAP_STYLE_URL,
  CHICAGO_CENTER_LNG_LAT,
  ZOOM,
  ROUTE_COLORS,
  ROUTE_WIDTHS,
  ROUTE_STATUS_COLORS,
  CHECKPOINT_COLORS,
} from '@/lib/design-tokens'
```

**Step 2: Replace the map style URL**

Find the `mapStyle` prop or `Map` component's `mapStyle` and replace with `MAP_STYLE_URL`.

**Step 3: Update route layer rendering to use 3-layer technique from design tokens**

Replace the existing cased line definitions with:
- Glow layer: `ROUTE_WIDTHS.GLOW` width, `ROUTE_COLORS.GLOW` at 0.3 opacity, blur 4
- Casing layer: `ROUTE_WIDTHS.CASING` width, status-based casing color
- Core layer: `ROUTE_WIDTHS.CORE` width, status-based line color

**Step 4: Update checkpoint marker colors**

Replace hardcoded checkpoint colors with `CHECKPOINT_COLORS.START`, `CHECKPOINT_COLORS.END`, etc.

**Step 5: Verify build**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc-kmp/site && npm run build 2>&1 | tail -20`

**Step 6: Commit**

```bash
git add site/src/components/map/RouteMap.tsx
git commit -m "feat: upgrade RouteMap to OpenFreeMap Liberty with 3-layer rendering"
```

---

### Task 11: Upgrade BlockMap.tsx to OpenFreeMap Liberty

**Files:**
- Modify: `site/src/components/map/BlockMap.tsx`

**Step 1: Import design tokens**

```typescript
import {
  MAP_STYLE_URL,
  CHICAGO_CENTER_LNG_LAT,
  ZOOM,
  BLOCK_STATUS_COLORS,
  COMMUNITY_AREA_COLORS,
} from '@/lib/design-tokens'
```

**Step 2: Replace Carto Positron style URL with `MAP_STYLE_URL`**

**Step 3: Replace hardcoded block status colors with `BLOCK_STATUS_COLORS`**

**Step 4: Add community area boundary styling using `COMMUNITY_AREA_COLORS`**

**Step 5: Verify build**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc-kmp/site && npm run build 2>&1 | tail -20`

**Step 6: Commit**

```bash
git add site/src/components/map/BlockMap.tsx
git commit -m "feat: upgrade BlockMap to OpenFreeMap Liberty with shared design tokens"
```

---

### Task 12: Create BountyMap.tsx component

**Files:**
- Create: `site/src/components/map/BountyMap.tsx`

**Step 1: Create the bounty map component with pulse markers and spot circles**

```typescript
'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import Map, { Layer, Marker, Popup, Source } from 'react-map-gl/maplibre'
import 'maplibre-gl/dist/maplibre-gl.css'
import {
  MAP_STYLE_URL,
  CHICAGO_CENTER_LNG_LAT,
  ZOOM,
  BOUNTY_COLORS,
  SPOT_COLORS,
  COMMUNITY_AREA_COLORS,
} from '@/lib/design-tokens'

interface Bounty {
  id: number
  title: string
  description?: string
  amount: number // cents
  status: 'OPEN' | 'IN_PROGRESS' | 'COMPLETED' | 'EXPIRED'
  locationLat: number
  locationLng: number
  radiusMeters: number
  posterName?: string
  mediaUrls: string[]
  createdAt: string
  pledgeCount: number
}

interface SpotReport {
  id: number
  lat: number
  lng: number
  affectedRadiusMeters: number
  description?: string
  photoUrl?: string
}

interface BountyMapProps {
  bounties: Bounty[]
  spots?: SpotReport[]
  onBountySelect?: (bounty: Bounty) => void
  selectedBountyId?: number
  interactive?: boolean
  height?: string
  showLegend?: boolean
}

export default function BountyMap({
  bounties,
  spots = [],
  onBountySelect,
  selectedBountyId,
  interactive = true,
  height = '500px',
  showLegend = true,
}: BountyMapProps) {
  const [selectedBounty, setSelectedBounty] = useState<Bounty | null>(null)

  const handleBountyClick = useCallback((bounty: Bounty) => {
    setSelectedBounty(bounty)
    onBountySelect?.(bounty)
  }, [onBountySelect])

  const bountyColor = (status: string) => {
    switch (status) {
      case 'OPEN': return BOUNTY_COLORS.OPEN
      case 'IN_PROGRESS': return BOUNTY_COLORS.IN_PROGRESS
      case 'COMPLETED': return BOUNTY_COLORS.COMPLETED
      default: return '#6B7280'
    }
  }

  return (
    <div style={{ height, position: 'relative' }}>
      <Map
        initialViewState={{
          longitude: CHICAGO_CENTER_LNG_LAT[0],
          latitude: CHICAGO_CENTER_LNG_LAT[1],
          zoom: ZOOM.NEIGHBORHOOD,
        }}
        style={{ width: '100%', height: '100%' }}
        mapStyle={MAP_STYLE_URL}
        interactive={interactive}
      >
        {/* Bounty markers */}
        {bounties.map((bounty) => (
          <Marker
            key={bounty.id}
            longitude={bounty.locationLng}
            latitude={bounty.locationLat}
            anchor="center"
            onClick={(e) => {
              e.originalEvent.stopPropagation()
              handleBountyClick(bounty)
            }}
          >
            <div
              className={`bounty-marker ${bounty.status === 'OPEN' ? 'pulse' : ''}`}
              style={{
                width: 28,
                height: 28,
                borderRadius: '50%',
                backgroundColor: bountyColor(bounty.status),
                border: '2px solid white',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: 'white',
                fontSize: 10,
                fontWeight: 700,
                cursor: 'pointer',
                boxShadow: selectedBountyId === bounty.id
                  ? `0 0 0 3px ${bountyColor(bounty.status)}`
                  : '0 1px 3px rgba(0,0,0,0.3)',
              }}
            >
              ${Math.floor(bounty.amount / 100)}
            </div>
          </Marker>
        ))}

        {/* Spot report radius circles */}
        {spots.map((spot) => (
          <Source
            key={`spot-${spot.id}`}
            id={`spot-${spot.id}`}
            type="geojson"
            data={{
              type: 'Feature',
              geometry: {
                type: 'Point',
                coordinates: [spot.lng, spot.lat],
              },
              properties: { radius: spot.affectedRadiusMeters },
            }}
          >
            <Layer
              id={`spot-fill-${spot.id}`}
              type="circle"
              paint={{
                'circle-radius': [
                  'interpolate', ['linear'], ['zoom'],
                  12, 4,
                  16, Math.max(8, spot.affectedRadiusMeters / 2),
                  20, spot.affectedRadiusMeters,
                ],
                'circle-color': SPOT_COLORS.RADIUS_FILL,
                'circle-stroke-color': SPOT_COLORS.RADIUS_STROKE,
                'circle-stroke-width': 1,
              }}
            />
          </Source>
        ))}

        {/* Bounty popup */}
        {selectedBounty && (
          <Popup
            longitude={selectedBounty.locationLng}
            latitude={selectedBounty.locationLat}
            anchor="bottom"
            onClose={() => setSelectedBounty(null)}
            closeButton
            closeOnClick={false}
          >
            <div className="p-2 max-w-[250px]">
              <h3 className="font-bold text-sm">{selectedBounty.title}</h3>
              <p className="text-lg font-bold text-red-500">
                ${(selectedBounty.amount / 100).toFixed(2)}
              </p>
              {selectedBounty.description && (
                <p className="text-xs text-gray-600 mt-1">{selectedBounty.description}</p>
              )}
              {selectedBounty.pledgeCount > 1 && (
                <p className="text-xs text-orange-500 mt-1">
                  {selectedBounty.pledgeCount} pledges stacked
                </p>
              )}
              <p className="text-xs text-gray-400 mt-1">
                {selectedBounty.status === 'OPEN'
                  ? 'Download the app to claim this bounty'
                  : `Status: ${selectedBounty.status.replace('_', ' ')}`}
              </p>
            </div>
          </Popup>
        )}
      </Map>

      {/* Pulse animation CSS */}
      <style jsx global>{`
        .bounty-marker.pulse {
          animation: bounty-pulse 2s ease-in-out infinite;
        }
        @keyframes bounty-pulse {
          0%, 100% { box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.4); }
          50% { box-shadow: 0 0 0 8px rgba(239, 68, 68, 0); }
        }
      `}</style>

      {/* Legend */}
      {showLegend && (
        <div className="absolute bottom-3 left-3 bg-white/90 backdrop-blur rounded-lg px-3 py-2 text-xs shadow-md">
          <div className="flex gap-3">
            <span className="flex items-center gap-1">
              <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: BOUNTY_COLORS.OPEN }} />
              Open
            </span>
            <span className="flex items-center gap-1">
              <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: BOUNTY_COLORS.IN_PROGRESS }} />
              In Progress
            </span>
            <span className="flex items-center gap-1">
              <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: BOUNTY_COLORS.COMPLETED }} />
              Completed
            </span>
          </div>
        </div>
      )}
    </div>
  )
}
```

**Step 2: Verify build**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc-kmp/site && npm run build 2>&1 | tail -20`

**Step 3: Commit**

```bash
git add site/src/components/map/BountyMap.tsx
git commit -m "feat: add BountyMap component with pulse markers, spot circles, and popups"
```

---

## Phase 4: Website — Bounty Pages

### Task 13: Create /bounties explorer page

**Files:**
- Create: `site/src/app/bounties/page.tsx`

**Step 1: Create the bounty explorer page with hero, stats bar, and full-screen map**

```typescript
import { Suspense } from 'react'
import BountyMap from '@/components/map/BountyMap'
import { BOUNTY_MINIMUM_CENTS } from '@/lib/design-tokens'

// TODO: Replace with real API call when backend is ready
async function getBounties() {
  // Stub data for now
  return [
    {
      id: 1, title: 'Illegal dumping on Pulaski', description: 'Large pile of construction debris',
      amount: 2500, status: 'OPEN' as const, locationLat: 41.8612, locationLng: -87.7260,
      radiusMeters: 25, posterName: 'Green Fund Chicago', mediaUrls: [],
      createdAt: '2026-03-05T10:00:00Z', pledgeCount: 2,
    },
    {
      id: 2, title: 'Mattress dumped in alley', description: 'Two mattresses blocking alley entrance',
      amount: 1500, status: 'OPEN' as const, locationLat: 41.8580, locationLng: -87.7195,
      radiusMeters: 10, posterName: 'Maria Johnson', mediaUrls: [],
      createdAt: '2026-03-06T14:30:00Z', pledgeCount: 1,
    },
    {
      id: 3, title: 'Tire pile on vacant lot', description: null,
      amount: 5000, status: 'IN_PROGRESS' as const, locationLat: 41.8635, locationLng: -87.7310,
      radiusMeters: 40, posterName: 'Block Club NL', mediaUrls: [],
      createdAt: '2026-03-04T08:00:00Z', pledgeCount: 3,
    },
  ]
}

export default async function BountiesPage() {
  const bounties = await getBounties()
  const activeBounties = bounties.filter(b => b.status === 'OPEN')
  const totalValue = bounties.reduce((sum, b) => sum + b.amount, 0)
  const completedThisWeek = 12 // stub

  return (
    <main className="flex flex-col min-h-screen">
      {/* Hero */}
      <section className="bg-gradient-to-br from-red-600 to-orange-500 text-white py-16 px-6">
        <div className="max-w-4xl mx-auto text-center">
          <h1 className="text-4xl md:text-5xl font-bold mb-4">Community Bounties</h1>
          <p className="text-xl text-white/90 max-w-2xl mx-auto">
            See a mess? Post a bounty. Someone nearby will clean it up.
            Bounties are peer-to-peer — anyone can post one anywhere, starting at ${BOUNTY_MINIMUM_CENTS / 100}.
          </p>
        </div>
      </section>

      {/* Stats bar */}
      <section className="bg-gray-900 text-white py-3 px-6">
        <div className="max-w-6xl mx-auto flex justify-center gap-8 text-sm">
          <span><strong>{activeBounties.length}</strong> active bounties</span>
          <span className="text-gray-500">|</span>
          <span><strong>${(totalValue / 100).toLocaleString()}</strong> total posted</span>
          <span className="text-gray-500">|</span>
          <span><strong>{completedThisWeek}</strong> cleaned this week</span>
        </div>
      </section>

      {/* Full-screen map */}
      <section className="flex-1">
        <Suspense fallback={<div className="h-[600px] bg-gray-100 animate-pulse" />}>
          <BountyMap
            bounties={bounties}
            height="calc(100vh - 280px)"
            showLegend={true}
          />
        </Suspense>
      </section>

      {/* CTA */}
      <section className="bg-gray-900 text-white py-12 px-6 text-center">
        <h2 className="text-2xl font-bold mb-3">Ready to make a difference?</h2>
        <p className="text-gray-400 mb-6">Download the app to post or claim bounties in your area.</p>
        <div className="flex gap-4 justify-center">
          <a href="#" className="bg-white text-gray-900 font-semibold px-6 py-3 rounded-lg hover:bg-gray-100 transition">
            App Store
          </a>
          <a href="#" className="bg-white text-gray-900 font-semibold px-6 py-3 rounded-lg hover:bg-gray-100 transition">
            Google Play
          </a>
        </div>
      </section>
    </main>
  )
}
```

**Step 2: Verify build**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc-kmp/site && npm run build 2>&1 | tail -20`

**Step 3: Commit**

```bash
git add site/src/app/bounties/page.tsx
git commit -m "feat: add /bounties explorer page with hero, stats, map, and CTA"
```

---

### Task 14: Create /bounties/how-it-works page

**Files:**
- Create: `site/src/app/bounties/how-it-works/page.tsx`

**Step 1: Create the how-it-works explainer page**

```typescript
export default function HowBountiesWorkPage() {
  return (
    <main className="min-h-screen bg-gray-50">
      {/* Hero */}
      <section className="bg-gradient-to-br from-red-600 to-orange-500 text-white py-20 px-6">
        <div className="max-w-3xl mx-auto text-center">
          <h1 className="text-4xl md:text-5xl font-bold mb-4">How Bounties Work</h1>
          <p className="text-xl text-white/90">
            A peer-to-peer system where anyone can fund cleanup anywhere.
          </p>
        </div>
      </section>

      {/* Steps */}
      <section className="max-w-4xl mx-auto py-16 px-6">
        <div className="space-y-16">
          {[
            {
              step: 1, title: 'Spot It', icon: '👀',
              desc: 'See something that needs cleaning? Take a photo from the app or note the location on the web.',
            },
            {
              step: 2, title: 'Post It', icon: '💰',
              desc: 'Set a bounty starting at $10. The more you offer, the faster someone will get to it. Multiple people can stack bounties on the same spot.',
            },
            {
              step: 3, title: 'Someone Claims It', icon: '📍',
              desc: 'Workers in the area see bounties on their map. The first person to arrive at the location and tap "Accept" gets assigned. One person per bounty — first come, first served.',
            },
            {
              step: 4, title: 'Verified Clean', icon: '✅',
              desc: 'The worker cleans the area and submits photo proof. Once verified, the bounty is paid out instantly.',
            },
          ].map(({ step, title, icon, desc }) => (
            <div key={step} className="flex gap-6 items-start">
              <div className="flex-shrink-0 w-16 h-16 rounded-full bg-red-100 flex items-center justify-center text-3xl">
                {icon}
              </div>
              <div>
                <p className="text-sm font-medium text-red-500 mb-1">Step {step}</p>
                <h3 className="text-2xl font-bold mb-2">{title}</h3>
                <p className="text-gray-600 text-lg">{desc}</p>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* Public vs Secret */}
      <section className="bg-white py-16 px-6">
        <div className="max-w-4xl mx-auto">
          <h2 className="text-3xl font-bold mb-8 text-center">Public vs Secret Bounties</h2>
          <div className="grid md:grid-cols-2 gap-8">
            <div className="border rounded-xl p-6">
              <h3 className="text-xl font-bold mb-2">🔴 Public Bounties</h3>
              <p className="text-gray-600">
                Visible to all workers on the map with the dollar amount shown.
                Great for urgent cleanups where visibility drives fast action.
              </p>
            </div>
            <div className="border rounded-xl p-6">
              <h3 className="text-xl font-bold mb-2">🟣 Secret Bounties</h3>
              <p className="text-gray-600">
                Invisible until the job is completed. The worker gets a surprise payout
                notification. Secret bounties can be stacked on top of public ones.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* Gamification callout */}
      <section className="bg-green-50 py-16 px-6">
        <div className="max-w-3xl mx-auto text-center">
          <h2 className="text-3xl font-bold mb-4">No Bounty? Still Worth It.</h2>
          <p className="text-gray-600 text-lg mb-4">
            Cleaning spots without bounties earns <strong>more</strong> gamification points than
            bountied ones. We reward altruism — the leaderboard recognizes people who clean
            because it&apos;s the right thing to do.
          </p>
          <p className="text-gray-500">
            Bountied cleanups still earn points (plus the cash), but selfless work climbs the
            rankings faster.
          </p>
        </div>
      </section>

      {/* CTA */}
      <section className="bg-gray-900 text-white py-12 px-6 text-center">
        <h2 className="text-2xl font-bold mb-6">Ready to post your first bounty?</h2>
        <a
          href="/bounties/post"
          className="inline-block bg-red-500 text-white font-semibold px-8 py-3 rounded-lg hover:bg-red-600 transition"
        >
          Post a Bounty — $10 minimum
        </a>
      </section>
    </main>
  )
}
```

**Step 2: Verify build**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc-kmp/site && npm run build 2>&1 | tail -20`

**Step 3: Commit**

```bash
git add site/src/app/bounties/how-it-works/page.tsx
git commit -m "feat: add /bounties/how-it-works explainer page"
```

---

### Task 15: Create /bounties/post page (web bounty creation form)

**Files:**
- Create: `site/src/app/bounties/post/page.tsx`

**Step 1: Create the bounty posting form with map picker, amount input, and public/secret toggle**

The page should include:
- A map (using BountyMap or a simplified MapPicker) where the user clicks to place a pin
- Address search input (optional, for geocoding)
- Title and description fields
- Amount input with $10 minimum validation (show error if < $10)
- Public/Secret toggle with explanation tooltip
- Optional photo upload field
- "Post Bounty" submit button (for now, show a success confirmation — Stripe integration is a future task)
- Form validation: require title, location, and minimum $10

Use React `useState` for form state, `'use client'` directive since it needs interactivity.

**Step 2: Verify build**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc-kmp/site && npm run build 2>&1 | tail -20`

**Step 3: Commit**

```bash
git add site/src/app/bounties/post/page.tsx
git commit -m "feat: add /bounties/post page with map picker and $10 minimum"
```

---

### Task 16: Update website navigation and landing page

**Files:**
- Modify: site navigation component (find the Header/Navbar component)
- Modify: `site/src/app/page.tsx`

**Step 1: Add "Bounties" to the main navigation**

Find the navigation/header component (likely in `site/src/components/shared/` or `site/src/components/landing/`). Add a "Bounties" link between existing "Routes" and "Sponsors" links pointing to `/bounties`.

**Step 2: Add Community Bounties section to landing page**

In `site/src/app/page.tsx`, add a new section component between existing sections:

```tsx
{/* Community Bounties Section */}
<section className="py-16 px-6 bg-gray-50">
  <div className="max-w-6xl mx-auto">
    <div className="text-center mb-8">
      <h2 className="text-3xl font-bold mb-3">Community Bounties</h2>
      <p className="text-gray-600 text-lg">
        See a mess? Post a bounty. Someone nearby will clean it up.
      </p>
    </div>
    <div className="grid md:grid-cols-3 gap-6 mb-8">
      <div className="text-center">
        <p className="text-4xl font-bold text-red-500">147</p>
        <p className="text-gray-500">Bounties Posted</p>
      </div>
      <div className="text-center">
        <p className="text-4xl font-bold text-green-500">106</p>
        <p className="text-gray-500">Cleanups Completed</p>
      </div>
      <div className="text-center">
        <p className="text-4xl font-bold text-orange-500">$2,850</p>
        <p className="text-gray-500">Paid to Workers</p>
      </div>
    </div>
    <div className="text-center">
      <a href="/bounties" className="inline-block bg-red-500 text-white font-semibold px-6 py-3 rounded-lg hover:bg-red-600 transition">
        Explore Bounties
      </a>
    </div>
  </div>
</section>
```

**Step 3: Update sponsor page to mention bounties**

Find the sponsor page and add a mention: "Fund a block ($500+) or post targeted bounties ($10+)".

**Step 4: Verify build**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc-kmp/site && npm run build 2>&1 | tail -20`

**Step 5: Commit**

```bash
git add site/src/app/page.tsx site/src/components/
git commit -m "feat: add bounties to nav, landing page section, and sponsor page"
```

---

## Phase 5: Mobile Flows (Foundation)

### Task 17: Add spot report radius model fields and claim logic to mobile ViewModels

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/` — find the relevant bounty/spot viewmodel

**Step 1: Verify CommunityReport model changes from Task 1 compile on all targets**

Run: `./gradlew :composeApp:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

**Step 2: Add bounty claim method to the mobile bounty ViewModel (or MapViewModel)**

Find the ViewModel that manages bounty interactions on the mobile map. Add:

```kotlin
fun claimBounty(bountyId: Int) {
    viewModelScope.launch {
        _claimState.value = ClaimState.Loading
        try {
            val result = api.claimBounty(bountyId)
            _claimState.value = ClaimState.Success(result)
        } catch (e: Exception) {
            if (e.message?.contains("409") == true) {
                _claimState.value = ClaimState.Conflict("Someone else got there first!")
            } else {
                _claimState.value = ClaimState.Error(e.message ?: "Failed to claim bounty")
            }
        }
    }
}
```

**Step 3: Verify compilation**

Run: `./gradlew :composeApp:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add composeApp/
git commit -m "feat: add bounty claim logic and spot report radius to mobile viewmodels"
```

---

### Task 18: Final compilation check across all modules

**Step 1: Build shared module**

Run: `./gradlew :shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

**Step 2: Build server**

Run: `./gradlew :server:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Build admin app**

Run: `./gradlew :adminApp:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Build mobile (iOS)**

Run: `./gradlew :composeApp:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

**Step 5: Build website**

Run: `cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc-kmp/site && npm run build`
Expected: Build succeeds

**Step 6: Commit any remaining fixes**

```bash
git add -A && git commit -m "fix: resolve cross-module compilation issues"
```

---

## Summary

| Phase | Tasks | Focus |
|-------|-------|-------|
| 1: Foundation | 1-3 | Shared models, server stubs, API client |
| 2: Admin | 4-8 | Bounty analytics dashboard, route CRUD polish |
| 3: Website Maps | 9-12 | Design tokens, map upgrades, BountyMap |
| 4: Website Pages | 13-16 | Bounty explorer, how-it-works, post form, nav |
| 5: Mobile | 17-18 | Claim logic, radius model, full build check |

**Future tasks (not in this plan):**
- Mobile: Spot reporter camera flow with mini-map overlay (Section 3 of design — needs camera integration)
- Mobile: Bounty detail sheet with GPS-gated accept button
- Mobile: Radius slider composable for spot placement
- Backend: Wire stubbed endpoints to real PostgreSQL queries
- Backend: Stripe integration for web bounty posting
- Backend: Optimistic locking for bounty claims
