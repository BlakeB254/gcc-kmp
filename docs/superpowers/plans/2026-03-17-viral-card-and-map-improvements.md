# Viral User Card + Map Improvements Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated screenshotable user card page with display handles and multi-level member ranks, photo star markers on route lines, and clickable route polygons on the map.

**Architecture:** Three independent workstreams touching the shared model (Worker.kt + DB schema), the Next.js API (worker-mapper + new columns), and the KMP Compose UI (new screens + map overlays). The DB already has `signup_rankings_*` columns, so rank data is available — we just need to pipe it to mobile.

**Tech Stack:** Kotlin Multiplatform (Compose), Next.js API (Drizzle ORM, Neon Postgres), MapLibre Compose (iOS), Canvas map (desktop)

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `composeApp/.../ui/screens/UserCardScreen.kt` | Dedicated dark-backdrop card page with save/share buttons |
| `composeApp/.../ui/gamification/ShareableProfileCard.kt` | Refactored card: no earnings, with handle + ranks |
| `composeApp/.../ui/map/PhotoStarMarker.kt` | Gold star composable for checkpoints with photos |
| `composeApp/.../ui/map/RoutePolygon.kt` | Route corridor polygon + buffer geometry math |
| `site/src/app/api/workers/me/handle/route.ts` | PATCH endpoint to update display handle |

### Modified Files
| File | Changes |
|------|---------|
| `shared/.../model/Worker.kt` | Add `displayHandle`, `memberNumber`, `signupRankings` |
| `site/src/db/schema.ts` | Add `display_handle`, `member_number` columns |
| `site/src/lib/worker-mapper.ts` | Map new fields to mobile shape |
| `site/src/app/api/auth/register/route.ts` | Assign `memberNumber` on signup |
| `composeApp/.../navigation/Screen.kt` | Add `UserCard` route |
| `composeApp/.../navigation/RootComponent.kt` | Add `navigateToUserCard()` |
| `composeApp/.../App.kt` | Wire `Screen.UserCard` → `UserCardScreen` |
| `composeApp/.../ui/screens/ProfileScreen.kt` | Add "My Card" button, remove share overlay |
| `composeApp/.../ui/screens/SettingsScreen.kt` | Add display handle edit field |
| `composeApp/.../viewmodel/ProfileViewModel.kt` | Add handle update, expose ranks |
| `composeApp/.../ui/map/GccMap.ios.kt` | Add photo star layer + route polygon layer |
| `composeApp/.../ui/map/GccMap.kt` | Add star + polygon parameters to expect fun |
| `composeApp/.../ui/map/MapRenderers.kt` | Add `drawPhotoStar()` + `drawRoutePolygon()` for canvas |
| `composeApp/.../ui/screens/MapFullScreen.kt` | Pass route checkpoints for stars, pass routes for polygons |

---

## Task 1: DB Schema — Add `display_handle` and `member_number` columns

**Files:**
- Modify: `site/src/db/schema.ts:140-149`

- [ ] **Step 1: Add columns to workers table**

In `site/src/db/schema.ts`, add after line 148 (`referralCode`):

```typescript
  // Viral card
  displayHandle: varchar('display_handle').unique(),
  memberNumber: serial('member_number'),
```

Note: `serial` auto-increments. Existing workers will get assigned numbers based on insert order.

- [ ] **Step 2: Push schema to DB**

Run: `cd site && npx drizzle-kit push`
Expected: Schema changes applied to Neon Postgres

- [ ] **Step 3: Backfill memberNumber for existing workers**

Run this SQL via Neon console or drizzle:
```sql
WITH ranked AS (
  SELECT id, ROW_NUMBER() OVER (ORDER BY created_at ASC) as rn
  FROM workers
)
UPDATE workers SET member_number = ranked.rn
FROM ranked WHERE workers.id = ranked.id;
```

- [ ] **Step 4: Commit**

```bash
git add site/src/db/schema.ts
git commit -m "feat(db): add display_handle and member_number columns to workers"
```

---

## Task 2: API — Expose new fields in worker mapper + handle update endpoint

**Files:**
- Modify: `site/src/lib/worker-mapper.ts`
- Modify: `site/src/app/api/auth/register/route.ts`
- Create: `site/src/app/api/workers/me/handle/route.ts`

- [ ] **Step 1: Update worker-mapper to include new fields**

In `site/src/lib/worker-mapper.ts`, add to the return object after `referralCode`:

```typescript
    displayHandle: row.displayHandle ?? null,
    memberNumber: Number(row.memberNumber ?? 0),
    signupRankings: {
      global: Number(row.signupRankingsGlobal ?? 0),
      state: Number(row.signupRankingsState ?? 0),
      city: Number(row.signupRankingsCity ?? 0),
      neighborhood: Number(row.signupRankingsNeighborhood ?? 0),
    },
```

- [ ] **Step 2: Create handle update endpoint**

Create `site/src/app/api/workers/me/handle/route.ts`:

```typescript
import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyMobileToken } from '@/lib/mobile-auth'

export const runtime = 'nodejs'

export async function PATCH(request: NextRequest) {
  const auth = await verifyMobileToken(request)
  if (!auth) {
    return Response.json({ error: 'Unauthorized' }, { status: 401 })
  }

  const { handle } = await request.json()

  // Validate handle format: 3-20 chars, alphanumeric + underscores only
  const cleaned = (handle ?? '').replace(/^@/, '').toLowerCase()
  if (!/^[a-z0-9_]{3,20}$/.test(cleaned)) {
    return Response.json(
      { error: 'Handle must be 3-20 characters, letters/numbers/underscores only' },
      { status: 400 }
    )
  }

  try {
    await db
      .update(workers)
      .set({ displayHandle: cleaned, updatedAt: new Date().toISOString() })
      .where(eq(workers.id, auth.workerId))

    return Response.json({ data: { displayHandle: cleaned } })
  } catch (e: any) {
    if (e.code === '23505') { // unique violation
      return Response.json({ error: 'Handle already taken' }, { status: 409 })
    }
    throw e
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add site/src/lib/worker-mapper.ts site/src/app/api/workers/me/handle/route.ts
git commit -m "feat(api): expose displayHandle, memberNumber, signupRankings + handle update endpoint"
```

---

## Task 3: Shared Model — Add new fields to Worker.kt

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/Worker.kt`

- [ ] **Step 1: Add SignupRankings data class and Worker fields**

Add before the `Worker` data class:

```kotlin
@Serializable
data class SignupRankings(
    val global: Int = 0,
    val state: Int = 0,
    val city: Int = 0,
    val neighborhood: Int = 0,
)
```

Add to `Worker` data class after `referralCode`:

```kotlin
    val displayHandle: String? = null,
    val memberNumber: Int = 0,
    val signupRankings: SignupRankings = SignupRankings(),
```

- [ ] **Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/Worker.kt
git commit -m "feat(shared): add displayHandle, memberNumber, signupRankings to Worker model"
```

---

## Task 4: ViewModel — Add handle update + rank display logic

**Files:**
- Modify: `composeApp/.../viewmodel/ProfileViewModel.kt`

- [ ] **Step 1: Add handle update function and rank display state**

Add to `ProfileUiState`:

```kotlin
    val handleUpdateLoading: Boolean = false,
    val handleUpdateError: String? = null,
```

Add to `ProfileViewModel`:

```kotlin
    fun updateDisplayHandle(handle: String) {
        scope.launch {
            _uiState.value = _uiState.value.copy(handleUpdateLoading = true, handleUpdateError = null)
            try {
                val response = apiClient.httpClient.io.ktor.client.request.patch(
                    "${apiClient.baseUrl}/api/workers/me/handle"
                ) {
                    io.ktor.http.contentType(io.ktor.http.ContentType.Application.Json)
                    io.ktor.client.request.setBody(mapOf("handle" to handle))
                }
                if (response.status.value in 200..299) {
                    // Reload profile to get updated worker
                    loadProfile()
                } else {
                    val body = response.body<Map<String, String>>()
                    _uiState.value = _uiState.value.copy(
                        handleUpdateLoading = false,
                        handleUpdateError = body["error"] ?: "Failed to update handle"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    handleUpdateLoading = false,
                    handleUpdateError = e.message ?: "Failed to update handle"
                )
            }
        }
    }
```

- [ ] **Step 2: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/ProfileViewModel.kt
git commit -m "feat(viewmodel): add display handle update to ProfileViewModel"
```

---

## Task 5: Navigation — Add UserCard route

**Files:**
- Modify: `composeApp/.../navigation/Screen.kt`
- Modify: `composeApp/.../navigation/RootComponent.kt`
- Modify: `composeApp/.../App.kt`

- [ ] **Step 1: Add Screen.UserCard**

In `Screen.kt`, add after `Achievements`:

```kotlin
    @Serializable
    data object UserCard : Screen()
```

- [ ] **Step 2: Add navigateToUserCard in RootComponent.kt**

After `navigateToAchievements()`:

```kotlin
    fun navigateToUserCard() {
        navigation.push(Screen.UserCard)
    }
```

- [ ] **Step 3: Wire in App.kt**

In the `when` block after `Screen.Achievements`, add:

```kotlin
                is Screen.UserCard -> UserCardScreen(
                    onBack = component::navigateBack,
                )
```

Add import: `import com.gcc.app.ui.screens.UserCardScreen`

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/navigation/Screen.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/navigation/RootComponent.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/App.kt
git commit -m "feat(nav): add UserCard screen route"
```

---

## Task 6: ShareableProfileCard — Refactored card composable

**Files:**
- Create: `composeApp/.../ui/gamification/ShareableProfileCard.kt`

- [ ] **Step 1: Create ShareableProfileCard**

New file based on existing `ProfileCard.kt` with these changes:
- Remove earnings column from stats row (keep jobs + streak only)
- Replace `worker.name` display with handle: `@${worker.displayHandle}` or fallback to `"${worker.name.split(" ")[0]} ${worker.name.split(" ").getOrNull(1)?.first()?.uppercase() ?: ""}."`
- Add member rank row: `"#${worker.signupRankings.global} nationally · #${worker.signupRankings.state} in ${worker.address.state} · #${worker.signupRankings.neighborhood} in ${worker.address.neighborhood}"`
- Add "Since ${month} ${year}" from `worker.createdAt`
- Keep: tier gradient, impact score, tier badge, badge showcase, QR code, referral footer

(Full composable code — ~280 lines based on ProfileCard.kt with the modifications above. Reuses all existing sub-composables: TierBadge, AnimatedCounter, AnimatedRating, QrCodeCanvas, ShareButton, GccLogo.)

- [ ] **Step 2: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/gamification/ShareableProfileCard.kt
git commit -m "feat(ui): add ShareableProfileCard with handle, ranks, no earnings"
```

---

## Task 7: UserCardScreen — Dedicated dark-backdrop page

**Files:**
- Create: `composeApp/.../ui/screens/UserCardScreen.kt`

- [ ] **Step 1: Create UserCardScreen**

```kotlin
package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.ShareManager
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.gamification.ShareableProfileCard
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.viewmodel.ProfileViewModel
import org.koin.compose.koinInject
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight

@Composable
fun UserCardScreen(
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<ProfileViewModel>()
    val shareManager = koinInject<ShareManager>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadProfile() }

    val worker = uiState.worker

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        // Close button top-left
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .statusBarsPadding(),
        ) {
            Text(
                "\u2715",
                color = Color.White.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold,
            )
        }

        if (worker != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 21.dp, vertical = 55.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ShareableProfileCard(
                    worker = worker,
                    badges = uiState.badges,
                    streakDays = uiState.streakDays,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Spacer(modifier = Modifier.height(21.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    GccButton(
                        text = "Save to Photos",
                        onClick = { /* TODO: bitmap capture */ },
                        variant = GccButtonVariant.OUTLINED,
                        modifier = Modifier.weight(1f),
                    )
                    GccButton(
                        text = "Share",
                        onClick = {
                            val handle = worker.displayHandle?.let { "@$it" }
                                ?: worker.name.split(" ").first()
                            val badgeCount = uiState.badges.count { it.isUnlocked }
                            shareManager.shareText(
                                text = "$handle · $badgeCount badges earned · " +
                                    "Member #${worker.signupRankings.global} · " +
                                    "Join the movement: https://globalcommunitycleaners.org" +
                                    (worker.referralCode?.let { "/refer/$it" } ?: ""),
                                subject = "My GCC Card",
                            )
                        },
                        variant = GccButtonVariant.PRIMARY,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            // Loading state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(color = GccGreen)
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/UserCardScreen.kt
git commit -m "feat(ui): add UserCardScreen with dark backdrop and share/save buttons"
```

---

## Task 8: Update ProfileScreen — Add "My Card" button, remove old share overlay

**Files:**
- Modify: `composeApp/.../ui/screens/ProfileScreen.kt`

- [ ] **Step 1: Add onUserCardClick param and replace Share Card button**

Add `onUserCardClick: () -> Unit = {}` to ProfileScreen params.

Replace the "Share Card" `GccButton` (lines 296-300) with:

```kotlin
            GccButton(
                text = "My Card",
                onClick = onUserCardClick,
                variant = GccButtonVariant.PRIMARY,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
```

Remove the `showShareOverlay` state and the entire share card overlay block (lines 93-131).

- [ ] **Step 2: Wire in App.kt**

Update `Screen.Profile` in App.kt to pass:

```kotlin
                    onUserCardClick = component::navigateToUserCard,
```

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ProfileScreen.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/App.kt
git commit -m "feat(profile): replace share overlay with My Card navigation button"
```

---

## Task 9: Settings — Add display handle edit field

**Files:**
- Modify: `composeApp/.../ui/screens/SettingsScreen.kt`

- [ ] **Step 1: Add handle editor section**

In SettingsScreen, after the existing settings content, add a "Display Handle" section:

```kotlin
            // Display Handle
            Text(
                "Display Handle",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                "Shown on your shareable card instead of your real name",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            var handleInput by remember(uiState.worker?.displayHandle) {
                mutableStateOf(uiState.worker?.displayHandle ?: "")
            }
            GccTextField(
                value = handleInput,
                onValueChange = { handleInput = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' } },
                label = "@handle",
                modifier = Modifier.fillMaxWidth(),
            )
            if (handleInput != (uiState.worker?.displayHandle ?: "")) {
                GccButton(
                    text = if (profileUiState.handleUpdateLoading) "Saving..." else "Save Handle",
                    onClick = { profileVm.updateDisplayHandle(handleInput) },
                    variant = GccButtonVariant.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            profileUiState.handleUpdateError?.let { error ->
                Text(error, color = GccError, style = MaterialTheme.typography.bodySmall)
            }
```

This requires injecting `ProfileViewModel` in SettingsScreen (add via `koinInject`).

- [ ] **Step 2: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SettingsScreen.kt
git commit -m "feat(settings): add display handle editor"
```

---

## Task 10: Photo Star Markers on Route Lines

**Files:**
- Create: `composeApp/.../ui/map/PhotoStarMarker.kt`
- Modify: `composeApp/.../ui/map/MapRenderers.kt`
- Modify: `composeApp/.../ui/map/GccMap.kt` (expect fun signature)
- Modify: `composeApp/.../ui/map/GccMap.ios.kt` (MapLibre layer)

- [ ] **Step 1: Create PhotoStarMarker composable**

```kotlin
package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.BadgeGold

@Composable
fun PhotoStarMarker(
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(BadgeGold)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "\u2605", // filled star
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
```

- [ ] **Step 2: Add drawPhotoStar to MapRenderers.kt**

Add to `MapRenderers.kt` for the Canvas-based desktop map:

```kotlin
/** Draw a gold star at the given canvas position for checkpoints with photos. */
internal fun DrawScope.drawPhotoStar(cx: Float, cy: Float, radius: Float = 10f) {
    val starColor = BadgeGold
    drawCircle(color = starColor, radius = radius, center = Offset(cx, cy))
    // White star glyph drawn via drawCircle + overlay (simplified)
    drawCircle(color = Color.White, radius = radius * 0.6f, center = Offset(cx, cy))
    drawCircle(color = starColor, radius = radius * 0.4f, center = Offset(cx, cy))
}
```

- [ ] **Step 3: Add checkpointPhotos param to GccMap expect fun**

In `GccMap.kt`, add parameter:

```kotlin
    checkpointPhotos: List<Pair<Coordinates, String>> = emptyList(), // (location, imageUrl)
```

- [ ] **Step 4: Add star GeoJSON source + SymbolLayer in GccMap.ios.kt**

Build a GeoJSON point source from `checkpointPhotos` and render as a SymbolLayer with a star icon.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/PhotoStarMarker.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapRenderers.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GccMap.kt \
       composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt
git commit -m "feat(map): add gold star markers for checkpoints with photos"
```

---

## Task 11: Clickable Route Polygons on Map

**Files:**
- Create: `composeApp/.../ui/map/RoutePolygon.kt`
- Modify: `composeApp/.../ui/map/GccMap.kt` (expect fun signature)
- Modify: `composeApp/.../ui/map/GccMap.ios.kt` (MapLibre polygon layer)
- Modify: `composeApp/.../ui/map/MapRenderers.kt` (canvas polygon)

- [ ] **Step 1: Create RoutePolygon.kt with buffer geometry**

```kotlin
package com.gcc.app.ui.map

import com.gcc.shared.model.Coordinates
import kotlin.math.*

/**
 * Buffer a polyline into a polygon corridor.
 * Returns a list of coordinates forming a closed polygon around the line.
 *
 * @param line The route polyline coordinates
 * @param bufferMeters Buffer distance in meters on each side
 */
fun bufferLineToPolygon(line: List<Coordinates>, bufferMeters: Double = 30.0): List<Coordinates> {
    if (line.size < 2) return emptyList()

    val leftSide = mutableListOf<Coordinates>()
    val rightSide = mutableListOf<Coordinates>()

    for (i in 0 until line.size - 1) {
        val p1 = line[i]
        val p2 = line[i + 1]

        // Calculate perpendicular offset
        val bearing = bearingBetween(p1, p2)
        val leftBearing = (bearing - 90.0 + 360.0) % 360.0
        val rightBearing = (bearing + 90.0) % 360.0

        leftSide.add(offsetPoint(p1, leftBearing, bufferMeters))
        rightSide.add(offsetPoint(p1, rightBearing, bufferMeters))

        if (i == line.size - 2) {
            leftSide.add(offsetPoint(p2, leftBearing, bufferMeters))
            rightSide.add(offsetPoint(p2, rightBearing, bufferMeters))
        }
    }

    // Close the polygon: left side forward, right side reversed
    return leftSide + rightSide.reversed()
}

private fun bearingBetween(from: Coordinates, to: Coordinates): Double {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLng = Math.toRadians(to.lng - from.lng)
    val y = sin(dLng) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

private fun offsetPoint(origin: Coordinates, bearingDeg: Double, distanceMeters: Double): Coordinates {
    val R = 6371000.0 // Earth radius in meters
    val bearing = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(origin.lat)
    val lng1 = Math.toRadians(origin.lng)
    val lat2 = asin(sin(lat1) * cos(distanceMeters / R) + cos(lat1) * sin(distanceMeters / R) * cos(bearing))
    val lng2 = lng1 + atan2(sin(bearing) * sin(distanceMeters / R) * cos(lat1), cos(distanceMeters / R) - sin(lat1) * sin(lat2))
    return Coordinates(Math.toDegrees(lat2), Math.toDegrees(lng2))
}
```

- [ ] **Step 2: Add routePolygons param to GccMap expect fun**

In `GccMap.kt`, add parameter:

```kotlin
    routePolygons: List<Pair<Int, List<Coordinates>>> = emptyList(), // (routeId, polygon coords)
```

- [ ] **Step 3: Add polygon FillLayer + tap handler in GccMap.ios.kt**

Build GeoJSON polygon features from `routePolygons`, render as FillLayer (GccGreen 15% alpha, stroke 40% alpha), handle click to call `onRouteTap`.

- [ ] **Step 4: Add drawRoutePolygon to MapRenderers.kt**

For the canvas desktop fallback:

```kotlin
internal fun DrawScope.drawRoutePolygon(
    polygon: List<Coordinates>,
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
) {
    if (polygon.size < 3) return
    val path = Path()
    polygon.forEachIndexed { i, coord ->
        val px = latLngToPixel(coord.lat, coord.lng, centerLat, centerLng, zoom, w, h)
        if (i == 0) path.moveTo(px.x, px.y) else path.lineTo(px.x, px.y)
    }
    path.close()
    drawPath(path, color = MapRouteGreen.copy(alpha = 0.15f))
    drawPath(path, color = MapRouteGreen.copy(alpha = 0.4f), style = Stroke(width = 2f))
}
```

- [ ] **Step 5: Wire polygon data in MapFullScreen**

In `MapFullScreen.kt`, compute polygons from routes and pass to GccMap:

```kotlin
val routePolygons = remember(routes) {
    routes.mapNotNull { route ->
        route.geometry?.coordinates?.let { coords ->
            val line = coords.map { Coordinates(it[1], it[0]) }
            route.id to bufferLineToPolygon(line)
        }
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/RoutePolygon.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GccMap.kt \
       composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapRenderers.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt
git commit -m "feat(map): add clickable route corridor polygons"
```

---

## Task 12: Build, Deploy to Simulator, Visual Verification

- [ ] **Step 1: Push to git from DGX**

```bash
cd ~/CDX-DEV-PROJECTS/gcc && git push
```

- [ ] **Step 2: Pull on MacBook and build**

```bash
ssh cdx-macbook "cd ~/CDX-DEV-PROJECTS/gcc && git pull && ./gradlew iosSimulatorArm64Debug 2>&1 | tail -5"
```

- [ ] **Step 3: Deploy to simulator and screenshot**

```bash
ssh cdx-macbook "cd ~/CDX-DEV-PROJECTS/gcc && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 16' build 2>&1 | tail -10"
ssh cdx-macbook "xcrun simctl io booted screenshot /tmp/gcc-card-test.png"
scp cdx-macbook:/tmp/gcc-card-test.png /tmp/gcc-card-test.png
```

- [ ] **Step 4: Visual verification of all three features**

Screenshot each: UserCard page, map with photo stars, map with route polygons.

---

## Dependency Order

```
Task 1 (DB schema)
  └→ Task 2 (API mapper + endpoint)
       └→ Task 3 (Shared model)
            ├→ Task 4 (ViewModel)
            │    ├→ Task 6 (ShareableProfileCard)
            │    │    └→ Task 7 (UserCardScreen)
            │    │         └→ Task 8 (ProfileScreen update)
            │    └→ Task 9 (Settings handle editor)
            └→ Task 5 (Navigation)

Task 10 (Photo stars) — independent, can parallel with Tasks 4-9
Task 11 (Route polygons) — independent, can parallel with Tasks 4-9
Task 12 (Build + verify) — after all tasks
```

Tasks 1→2→3 are sequential (data flows up from DB → API → model).
Tasks 4-9 depend on Task 3.
Tasks 5, 6, 7, 8, 9 can be partially parallelized.
Tasks 10 and 11 are fully independent of the card workstream.
