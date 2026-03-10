# Field Testing Refactor — Build 33

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Address all issues found during field testing of build 32 — UX improvements, data fixes, tag system, image optimization, and navigation fixes.

**Architecture:** Changes span the full stack: shared Kotlin models, SpotViewModel validation, iOS/common map layers, Next.js API routes, Drizzle schema, and website React components. Each task is independent enough to commit separately.

**Tech Stack:** Kotlin Multiplatform (Compose), Next.js 14 (App Router), Drizzle ORM, PostgreSQL, MapLibre GL, Coil3

---

### Task 1: Spot Tag System — Replace Free-Text with Structured Tags

**Context:** Currently spots use a free-text `description` field. The user wants structured tags (leaves, trash, glass, metal, etc.) to eliminate the notes duality and enable filtering/analytics.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/Enums.kt` — add SpotTag enum
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/CommunityReport.kt` — add `tags` field
- Modify: `site/src/db/schema.ts` — add `tags` jsonb column to communityReports
- Modify: `site/src/app/api/spots/route.ts` — accept and return tags
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt` — replace description TextField with tag chips + optional notes
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt` — add tags to state and submission
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt` — show tags in SpotInfoCard
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminSpotCreateScreen.kt` — add tag selection to admin flow

**Step 1: Add SpotTag enum to shared model**

In `shared/src/commonMain/kotlin/com/gcc/shared/model/Enums.kt`, add after the `CommunityReportStatus` enum (after line 306):

```kotlin
@Serializable
enum class SpotTag {
    @SerialName("trash") TRASH,
    @SerialName("leaves") LEAVES,
    @SerialName("glass") GLASS,
    @SerialName("metal") METAL,
    @SerialName("tires") TIRES,
    @SerialName("furniture") FURNITURE,
    @SerialName("construction") CONSTRUCTION,
    @SerialName("organic") ORGANIC,
    @SerialName("hazardous") HAZARDOUS,
    @SerialName("electronics") ELECTRONICS,
    @SerialName("clothing") CLOTHING,
    @SerialName("other") OTHER,
}
```

**Step 2: Add tags field to CommunityReport**

In `shared/src/commonMain/kotlin/com/gcc/shared/model/CommunityReport.kt`, add after `description` (line 13):

```kotlin
val tags: List<SpotTag> = emptyList(),
```

Add import: `import com.gcc.shared.model.SpotTag`

Also add `tags` to `CreateSpotRequest` in the shared SpotModels file — add after `reporterName`:

```kotlin
val tags: List<SpotTag> = emptyList(),
```

**Step 3: Add tags column to database**

In `site/src/db/schema.ts`, add to `communityReports` table after `description`:

```typescript
tags: jsonb('tags').$type<string[]>(),
```

**Step 4: Update spots API**

In `site/src/app/api/spots/route.ts`:

- In the `toCommunityReport()` helper, add: `tags: r.tags || [],`
- In POST body destructuring, add `tags`
- In the insert values, add: `tags: tags || [],`

**Step 5: Replace description phase in SpotScreen with tag chips**

In `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt`, replace the DESCRIPTION phase content. Keep the `OutlinedTextField` for optional notes but add a tag chip grid above it:

```kotlin
SpotPhase.DESCRIPTION -> {
    Text(
        "What's here?",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
    )
    Text(
        "Select all that apply",
        style = MaterialTheme.typography.bodySmall,
        color = TextSubtle,
    )
    Spacer(modifier = Modifier.height(12.dp))

    // Tag chips in a flow layout
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SpotTag.entries.forEach { tag ->
            val selected = tag in selectedTags
            val emoji = spotTagEmoji(tag)
            FilterChip(
                selected = selected,
                onClick = {
                    selectedTags = if (selected) selectedTags - tag else selectedTags + tag
                },
                label = { Text("$emoji ${spotTagLabel(tag)}") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = GccGreen.copy(alpha = 0.15f),
                    selectedLabelColor = GccGreen,
                ),
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Optional notes (replaces mandatory description)
    OutlinedTextField(
        value = description,
        onValueChange = { onDescriptionChange(it) },
        label = { Text("Additional notes (optional)") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 3,
    )
}
```

Add helper functions at the bottom of the file:

```kotlin
private fun spotTagEmoji(tag: SpotTag): String = when (tag) {
    SpotTag.TRASH -> "\uD83D\uDDD1\uFE0F"
    SpotTag.LEAVES -> "\uD83C\uDF42"
    SpotTag.GLASS -> "\uD83E\uDEE3"
    SpotTag.METAL -> "\u2699\uFE0F"
    SpotTag.TIRES -> "\uD83D\uDEDE"
    SpotTag.FURNITURE -> "\uD83E\uDE91"
    SpotTag.CONSTRUCTION -> "\uD83C\uDFD7\uFE0F"
    SpotTag.ORGANIC -> "\uD83E\uDD6C"
    SpotTag.HAZARDOUS -> "\u2622\uFE0F"
    SpotTag.ELECTRONICS -> "\uD83D\uDDA5\uFE0F"
    SpotTag.CLOTHING -> "\uD83D\uDC55"
    SpotTag.OTHER -> "\uD83D\uDCE6"
}

private fun spotTagLabel(tag: SpotTag): String = when (tag) {
    SpotTag.TRASH -> "Trash"
    SpotTag.LEAVES -> "Leaves"
    SpotTag.GLASS -> "Glass"
    SpotTag.METAL -> "Metal"
    SpotTag.TIRES -> "Tires"
    SpotTag.FURNITURE -> "Furniture"
    SpotTag.CONSTRUCTION -> "Construction"
    SpotTag.ORGANIC -> "Organic"
    SpotTag.HAZARDOUS -> "Hazardous"
    SpotTag.ELECTRONICS -> "Electronics"
    SpotTag.CLOTHING -> "Clothing"
    SpotTag.OTHER -> "Other"
}
```

**Step 6: Add tags to SpotViewModel state and submission**

In `SpotViewModel.kt`, add to `SpotUiState`:

```kotlin
val selectedTags: List<SpotTag> = emptyList(),
```

Add update function:

```kotlin
fun updateTags(tags: List<SpotTag>) {
    _uiState.update { it.copy(selectedTags = tags) }
}
```

In `submitReport()`, add `tags = state.selectedTags` to the `CreateSpotRequest`.

**Step 7: Show tags in SpotInfoCard on map**

In `MapFullScreen.kt` `SpotInfoCard`, after the photos section (~line 440), add:

```kotlin
// Tags
if (spot.tags.isNotEmpty()) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        spot.tags.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = GccGreen.copy(alpha = 0.1f),
            ) {
                Text(
                    "${spotTagEmoji(tag)} ${spotTagLabel(tag)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = GccGreen,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
}
```

**Step 8: Add tags to AdminSpotCreateScreen**

In `AdminSpotCreateScreen.kt` DETAILS step, add tag chips above the existing type toggle (reuse the same `FlowRow` pattern from SpotScreen). Add a `selectedTags` state variable and pass to the `CreateSpotRequest`.

**Step 9: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/Enums.kt \
       shared/src/commonMain/kotlin/com/gcc/shared/model/CommunityReport.kt \
       shared/src/commonMain/kotlin/com/gcc/shared/model/SpotModels.kt \
       site/src/db/schema.ts \
       site/src/app/api/spots/route.ts \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminSpotCreateScreen.kt
git commit -m "feat: add structured spot tag system (leaves, trash, glass, metal, etc.)"
```

---

### Task 2: 3-Image Limit + Duplicate Spot Protection

**Context:** No image count limit exists and spots can be submitted at the same coordinates repeatedly.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt` — enforce 3-photo cap
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt` — disable camera after 3 photos
- Modify: `site/src/app/api/spots/route.ts` — server-side duplicate detection + image limit
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt` — respect photo limit

**Step 1: Add MAX_PHOTOS constant and enforce in SpotViewModel**

In `SpotViewModel.kt`, add constant at top:

```kotlin
private const val MAX_PHOTOS = 3
private const val DUPLICATE_RADIUS_METERS = 50.0
```

In `addPhoto()` (or wherever photos are added to state), add guard:

```kotlin
fun addPhoto(photo: CapturedPhoto) {
    _uiState.update { state ->
        if (state.capturedPhotos.size >= MAX_PHOTOS) state
        else state.copy(capturedPhotos = state.capturedPhotos + photo)
    }
}
```

**Step 2: Disable camera capture button when at limit**

In `CameraCapture.kt`, accept a `maxPhotos: Int = 3` parameter. When `capturedPhotos.size >= maxPhotos`, disable the capture button and show "3/3 photos" label.

**Step 3: Server-side duplicate detection**

In `site/src/app/api/spots/route.ts` POST handler, before inserting, check for existing non-resolved spots within 50m:

```typescript
// Duplicate detection: reject if an active spot exists within 50m
const existingSpots = await db.select({ id: communityReports.id })
  .from(communityReports)
  .where(
    and(
      sql`(coordinates->>'lat')::float BETWEEN ${lat - 0.00045} AND ${lat + 0.00045}`,
      sql`(coordinates->>'lng')::float BETWEEN ${lng - 0.00045} AND ${lng + 0.00045}`,
      not(eq(communityReports.status, 'resolved')),
      not(eq(communityReports.status, 'rejected')),
    )
  )
  .limit(1)

if (existingSpots.length > 0) {
  return NextResponse.json(
    { error: 'A spot report already exists at this location', existingSpotId: existingSpots[0].id },
    { status: 409 }
  )
}
```

Also enforce image limit server-side:

```typescript
if (mediaUrls && mediaUrls.length > 3) {
  return NextResponse.json({ error: 'Maximum 3 images per spot' }, { status: 400 })
}
```

**Step 4: Handle 409 conflict response in SpotViewModel**

In `submitReport()`, catch the 409 response and show a user-friendly error:

```kotlin
if (response.status.value == 409) {
    _uiState.update { it.copy(phase = SpotPhase.ERROR, error = "A spot already exists at this location") }
    return
}
```

**Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt \
       site/src/app/api/spots/route.ts
git commit -m "feat: enforce 3-image limit and duplicate spot protection (50m radius)"
```

---

### Task 3: Spot Radius Drag UX — Bigger Touch Target + Smoother Movement

**Context:** The draggable circle overlay for positioning the affected area is hard to grab. Needs a larger visible drag handle and smoother pixel-to-coordinate conversion.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt:273-314` — improve drag handle
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminSpotCreateScreen.kt:154-174` — same improvements

**Step 1: Add a visible drag handle to the center of the radius circle**

In both `SpotScreen.kt` and `AdminSpotCreateScreen.kt`, replace the small 4f center dot with a larger, more visible drag handle. Update the Canvas drawing:

```kotlin
// Center drag handle — large, visible, inviting to touch
drawCircle(
    color = GccGold,
    radius = 20f,
    center = center,
)
drawCircle(
    color = Color.White,
    radius = 14f,
    center = center,
)
drawCircle(
    color = GccGold,
    radius = 8f,
    center = center,
)
// Crosshair lines for precision
drawLine(
    color = GccGold.copy(alpha = 0.6f),
    start = Offset(center.x - 30f, center.y),
    end = Offset(center.x + 30f, center.y),
    strokeWidth = 1.5f,
)
drawLine(
    color = GccGold.copy(alpha = 0.6f),
    start = Offset(center.x, center.y - 30f),
    end = Offset(center.x, center.y + 30f),
    strokeWidth = 1.5f,
)
```

**Step 2: Increase the gesture detection area**

The current `pointerInput` is on the full canvas. Add a minimum touch slop reduction for smoother start:

```kotlin
.pointerInput(spotLat, spotLng, mapZoom) {
    detectDragGestures(
        onDrag = { change, dragAmount ->
            change.consume()
            val (dLat, dLng) = pixelDeltaToLatLng(
                dragAmount.x, dragAmount.y, spotLat, mapZoom,
            )
            // Smooth movement with the same bounds logic
            // ...
        },
    )
}
```

**Step 3: Add "Drag to position" instruction text**

Below the map in both screens, add a hint label when the user hasn't dragged yet:

```kotlin
Text(
    "Drag the pin to set location",
    style = MaterialTheme.typography.labelSmall,
    color = GccGoldDark,
    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
)
```

**Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminSpotCreateScreen.kt
git commit -m "fix: larger drag handle and smoother movement for spot radius positioning"
```

---

### Task 4: Fix Admin Tab Navigation from Profile

**Context:** `DashboardScreen.kt` hardcodes `selectedTab = 0`. Each screen that uses the bottom nav bar needs to know which tab is currently active. But the real issue is that screens like ProfileScreen and AdminPanelScreen don't have the bottom nav bar at all — they're pushed onto the nav stack. The admin *can* get to admin from bottom nav tab 2 when on Dashboard, but once they navigate to Profile (tab 3), they can't switch back to Admin because Profile screen doesn't show the bottom nav.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt` — add bottom nav with correct selectedTab
- Verify: `composeApp/src/commonMain/kotlin/com/gcc/app/navigation/RootComponent.kt` — confirm admin navigation works

**Step 1: Add bottom nav to MapFullScreen**

The map screen currently has no bottom nav. Add the `GccBottomNavBar` at the bottom:

In `MapFullScreen.kt`, the screen is a `Box(modifier = Modifier.fillMaxSize())`. Wrap it in a `Scaffold` with `bottomBar`:

```kotlin
Scaffold(
    contentWindowInsets = WindowInsets(0),
    bottomBar = {
        GccBottomNavBar(
            selectedTab = 1, // Map tab
            isAdmin = isAdmin,
            onTabSelected = { tab ->
                when (tab) {
                    0 -> onBackClick() // Go back to dashboard
                    2 -> if (isAdmin) onAdminClick() else onProfileClick()
                    if (isAdmin) 3 else 2 -> if (isAdmin) onProfileClick() else Unit
                }
            },
        )
    },
) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        // existing map content...
    }
}
```

Add the necessary callback parameters to `MapFullScreen`:

```kotlin
fun MapFullScreen(
    // ... existing params ...
    onAdminClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
)
```

Wire these up in `RootComponent.kt` where `MapFullScreen` is called.

**Step 2: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/navigation/RootComponent.kt
git commit -m "fix: add bottom nav to map screen for admin/profile tab access"
```

---

### Task 5: Block Route Naming Fix

**Context:** The route displayNames in the database are incorrect. Need to update them via API while preserving existing imageUrl values.

**Files:**
- Modify: `site/src/app/api/admin/routes/[routeId]/route.ts` — already supports PUT with displayName (line 64)

**Step 1: Query current routes to get IDs and current names**

Run from command line or a script:

```bash
curl -s https://globalcommunitycleaners.org/api/routes?status=all | jq '.data[] | {id, code, displayName, imageUrl}'
```

Examine output to map route IDs to correct street names.

**Step 2: Update each route with correct displayName**

For each route, call the admin PUT endpoint with the correct name. The implementer should examine the route geometry to determine the actual street names the route follows and update accordingly. The endpoint already supports this:

```bash
curl -X PUT https://globalcommunitycleaners.org/api/admin/routes/{routeId} \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"displayName": "Correct Street Name Block"}'
```

**Important:** Do NOT modify imageUrl — the existing images should be preserved. The PUT endpoint only updates fields that are provided in the body.

**Step 3: Commit** (if any code changes needed)

```bash
git commit -m "fix: update block route display names to correct street names"
```

---

### Task 6: Block Cover Images — Website Popup + App Route Detail

**Context:** Routes have an `imageUrl` field that was recently populated with images, but these aren't shown when clicking a block on the map (website popup) or in the app's RouteDetailScreen.

**Files:**
- Modify: `site/src/components/map/CombinedMap.tsx` — add image to route popup
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RouteDetailScreen.kt` — add cover image
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt` — add image to route overlay chips

**Step 1: Add cover image to website route popup**

In `CombinedMap.tsx`, in the route popup section (~line 361-402), add the image:

```tsx
{r.imageUrl && (
  <img
    src={r.imageUrl}
    alt={r.displayName}
    className="w-full h-32 object-cover rounded-lg mb-2"
    loading="eager"
  />
)}
```

Add it right after the popup opens, before the route name.

**Step 2: Add cover image to RouteDetailScreen**

In `RouteDetailScreen.kt`, in the bottom sheet content area, add before the route title:

```kotlin
// Cover image
route.imageUrl?.let { url ->
    NetworkImage(
        url = url,
        contentDescription = route.displayName,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Crop,
    )
    Spacer(modifier = Modifier.height(12.dp))
}
```

**Step 3: Add thumbnail to route overlay chips on MapFullScreen**

In `MapFullScreen.kt`, where `RouteOverlay` renders route chips, add a small thumbnail if imageUrl exists:

```kotlin
route.imageUrl?.let { url ->
    NetworkImage(
        url = url,
        modifier = Modifier.size(32.dp).clip(CircleShape),
    )
    Spacer(modifier = Modifier.width(6.dp))
}
```

**Step 4: Commit**

```bash
git add site/src/components/map/CombinedMap.tsx \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RouteDetailScreen.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt
git commit -m "feat: show block cover images in route popup (web) and route detail (app)"
```

---

### Task 7: Intelligent Touch Targets for Overlapping Spots/Blocks

**Context:** When spots and route polygons overlap on the map, taps can be ambiguous. Need priority-based click handling: spots take priority at close zoom, routes at medium zoom.

**Files:**
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt` — adjust layer click priority and tap target sizes
- Modify: `site/src/components/map/CombinedMap.tsx` — adjust interactiveLayerIds order and add click priority logic

**Step 1: iOS map — prioritize spot taps at close zoom, route taps at far zoom**

In `GccMap.ios.kt`, increase the spot tap target radii. The current invisible tap target circle has radius 8-22dp. Increase to 12-28dp:

```kotlin
// Spot tap target — invisible but larger hit area
"circle-radius" to buildJsonArray {
    add("interpolate")
    add(buildJsonArray { add("linear") })
    add(buildJsonArray { add("zoom") })
    add(12); add(12)    // was 8
    add(13); add(24)    // was 20
    add(15); add(28)    // was 22
}
```

Ensure spot layers are added AFTER route layers so they receive clicks first (MapLibre processes layers in reverse order — last added = first to receive clicks).

**Step 2: Website — add click priority logic**

In `CombinedMap.tsx`, in `handleMapClick`, check for spot features first:

```typescript
const handleMapClick = useCallback((event: any) => {
    // Check spots first (higher priority)
    const spotFeature = event.features?.find((f: any) =>
        f.layer?.id?.includes('spot')
    )
    if (spotFeature) {
        const spotId = spotFeature.properties.id
        const spot = spots.find((s) => String(s.id) === String(spotId))
        if (spot) {
            setPopup({ kind: 'spot', data: spot, lng: spot.coordinates[0], lat: spot.coordinates[1] })
            return
        }
    }

    // Then check routes
    const routeFeature = event.features?.find((f: any) =>
        f.layer?.id?.includes('route')
    )
    if (routeFeature) {
        const routeId = routeFeature.properties.id
        const route = routes.find((r) => r.id === routeId)
        if (route) {
            setPopup({ kind: 'route', data: route })
            onRouteSelect?.(route)
        }
    }
}, [routes, spots, onRouteSelect])
```

**Step 3: Commit**

```bash
git add composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt \
       site/src/components/map/CombinedMap.tsx
git commit -m "fix: intelligent touch targets for overlapping spots and blocks"
```

---

### Task 8: Fast Image Loading — Coil Memory/Disk Cache + Thumbnails

**Context:** Spot images load slowly when the info card opens. Need to configure Coil caching and use thumbnail URLs where available.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/App.kt` — configure Coil ImageLoader with disk cache
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/NetworkImage.kt` — add crossfade and placeholder
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt` — prefetch spot images when card is about to show

**Step 1: Configure Coil with disk cache in App.kt**

In `App.kt`, add Coil setup with disk + memory cache:

```kotlin
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.SingletonImageLoader
import okio.Path.Companion.toPath

// In the App composable or a top-level initializer:
SingletonImageLoader.setSafe { context: PlatformContext ->
    ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, percent = 0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .maxSizeBytes(50L * 1024 * 1024) // 50MB
                .build()
        }
        .crossfade(true)
        .build()
}
```

**Step 2: Update NetworkImage with crossfade and size hints**

In `NetworkImage.kt`, add size hints for faster decoding:

```kotlin
SubcomposeAsyncImage(
    model = ImageRequest.Builder(LocalPlatformContext.current)
        .data(url)
        .size(coil3.size.Size(512, 512)) // Decode at reasonable size, not full resolution
        .crossfade(200)
        .build(),
    // ... rest unchanged
)
```

**Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/App.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/NetworkImage.kt
git commit -m "perf: configure Coil disk/memory cache and image size hints for faster loading"
```

---

### Task 9: Spot Timestamp Display + 24h Freshness Tracking

**Context:** Spots already have `createdAt` and `nextVerificationDue` timestamps. Need to display these in the UI so users can see when a spot was reported and when images should be refreshed.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt` — show timestamps in SpotInfoCard

**Step 1: Add timestamp to SpotInfoCard**

In `MapFullScreen.kt` `SpotInfoCard`, add after the reporter name row:

```kotlin
// Timestamp + freshness indicator
val now = kotlinx.datetime.Clock.System.now()
val age = now - spot.createdAt
val hoursOld = (age.inWholeMinutes / 60).toInt()
val isFresh = hoursOld < 24
val timeLabel = when {
    hoursOld < 1 -> "Just now"
    hoursOld < 24 -> "${hoursOld}h ago"
    hoursOld < 48 -> "Yesterday"
    else -> "${hoursOld / 24}d ago"
}

Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(
        if (isFresh) "\uD83D\uDFE2" else "\uD83D\uDFE1", // green or yellow dot
        style = MaterialTheme.typography.labelSmall,
    )
    Text(
        "$timeLabel${if (!isFresh) " \u2022 needs refresh" else ""}",
        style = MaterialTheme.typography.bodySmall,
        color = if (isFresh) GccGreen else Color(0xFFFF9800),
    )
}
```

**Step 2: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt
git commit -m "feat: show spot freshness timestamps with 24h refresh indicator"
```

---

### Task 10: Swipe Between Block Routes — Directional Navigation

**Context:** When viewing a route detail, the user should be able to swipe left/right (and eventually in all 4 cardinal directions) to navigate to adjacent block routes.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RouteDetailScreen.kt` — add swipe gesture for route navigation
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/navigation/RootComponent.kt` — pass adjacent route info

**Step 1: Add adjacent route navigation to RouteDetailScreen**

Accept a list of all routes (or adjacent route IDs) and add swipe detection:

```kotlin
fun RouteDetailScreen(
    routeId: Int,
    allRoutes: List<Route> = emptyList(), // All published routes for navigation
    onBack: () -> Unit = {},
    onNavigateToRoute: (Int) -> Unit = {}, // Navigate to adjacent route
) {
```

Sort routes by their geometry centroid longitude (left-to-right ordering). Find the current route's position and determine left/right neighbors:

```kotlin
val sortedRoutes = remember(allRoutes) {
    allRoutes.sortedBy { route ->
        route.geometry?.coordinates?.let { coords ->
            coords.map { it[0] }.average() // Average longitude = east-west position
        } ?: 0.0
    }
}
val currentIndex = sortedRoutes.indexOfFirst { it.id == routeId }
val leftRoute = if (currentIndex > 0) sortedRoutes[currentIndex - 1] else null
val rightRoute = if (currentIndex < sortedRoutes.lastIndex) sortedRoutes[currentIndex + 1] else null
```

Add swipe detection on the bottom sheet:

```kotlin
.pointerInput(leftRoute, rightRoute) {
    detectHorizontalDragGestures { _, dragAmount ->
        if (dragAmount < -100 && rightRoute != null) {
            onNavigateToRoute(rightRoute.id)
        } else if (dragAmount > 100 && leftRoute != null) {
            onNavigateToRoute(leftRoute.id)
        }
    }
}
```

Add navigation arrows:

```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    if (leftRoute != null) {
        TextButton(onClick = { onNavigateToRoute(leftRoute.id) }) {
            Text("\u2190 ${leftRoute.displayName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else Spacer(Modifier)
    if (rightRoute != null) {
        TextButton(onClick = { onNavigateToRoute(rightRoute.id) }) {
            Text("${rightRoute.displayName} \u2192", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else Spacer(Modifier)
}
```

**Step 2: Wire up in RootComponent**

In `RootComponent.kt`, pass the `allRoutes` list and `onNavigateToRoute` callback that replaces the current screen with the new route:

```kotlin
Screen.RouteDetail(routeId) -> RouteDetailScreen(
    routeId = routeId,
    allRoutes = dashboardViewModel.uiState.value.routes,
    onBack = { navigation.pop() },
    onNavigateToRoute = { newRouteId ->
        navigation.replaceCurrent(Screen.RouteDetail(newRouteId))
    },
)
```

**Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RouteDetailScreen.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/navigation/RootComponent.kt
git commit -m "feat: swipe left/right between adjacent block routes in route detail"
```

---

### Task 11: Website Spot Images in Popup + Block Image Display

**Context:** When spots are clicked on the website map, the popup should show images. When blocks are clicked, the popup should show the cover image.

**Files:**
- Modify: `site/src/components/map/CombinedMap.tsx` — enhance spot popup with images, enhance route popup with cover image

**Step 1: Add spot images to website popup**

In `CombinedMap.tsx`, in the spot popup section, add images. The spot data in the popup already includes `mediaUrls`. Add an image strip:

```tsx
{popup.kind === 'spot' && (() => {
    const s = popup.data as CommunityReport
    return (
        <div className="p-3 min-w-[240px] max-w-[320px]">
            {/* Image strip */}
            {s.mediaUrls && s.mediaUrls.length > 0 && (
                <div className="flex gap-1 mb-2 overflow-x-auto">
                    {s.mediaUrls.slice(0, 3).map((url, i) => (
                        <img
                            key={i}
                            src={url}
                            alt={`Spot photo ${i + 1}`}
                            className="w-24 h-24 object-cover rounded-lg flex-shrink-0"
                            loading="eager"
                        />
                    ))}
                </div>
            )}
            {/* Tags */}
            {s.tags && s.tags.length > 0 && (
                <div className="flex flex-wrap gap-1 mb-2">
                    {s.tags.map((tag) => (
                        <span key={tag} className="px-2 py-0.5 text-xs rounded-full bg-green-100 text-green-700">
                            {tag}
                        </span>
                    ))}
                </div>
            )}
            {/* ... existing spot info ... */}
        </div>
    )
})()}
```

**Step 2: Commit**

```bash
git add site/src/components/map/CombinedMap.tsx
git commit -m "feat: show spot images and tags in website map popup"
```

---

### Task 12: Build, Archive, and Upload to TestFlight (Build 33)

**Files:**
- Modify: `iosApp/iosApp/Info.plist` — bump CFBundleVersion to 33

**Step 1: Bump build number**

```xml
<key>CFBundleVersion</key>
<string>33</string>
```

**Step 2: Compile Kotlin framework**

```bash
./gradlew :composeApp:compileKotlinIosArm64
```

Expected: BUILD SUCCESSFUL

**Step 3: Archive and upload**

```bash
xcodebuild clean archive \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath /tmp/gcc-build/GCC.xcarchive \
  DEVELOPMENT_TEAM=7BBNW25LZM

xcodebuild -exportArchive \
  -archivePath /tmp/gcc-build/GCC.xcarchive \
  -exportPath /tmp/gcc-build \
  -exportOptionsPlist /tmp/gcc-build/ExportOptions.plist \
  -allowProvisioningUpdates
```

Expected: `** ARCHIVE SUCCEEDED **` then `** EXPORT SUCCEEDED **`

**Step 4: Commit all changes**

```bash
git add iosApp/iosApp/Info.plist
git commit -m "chore: bump build to 33 — field testing refactor"
```
