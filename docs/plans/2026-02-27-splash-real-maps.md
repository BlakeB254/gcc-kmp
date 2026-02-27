# Splash Real Maps Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace simplified splash animation polygons with real geographic data — detailed Chicago community areas from the Data Portal, continental US with state borders, and smooth zoom-crossfade transitions with contextual labels.

**Architecture:** GeoJSON files in composeResources parsed at splash start into polygon lists. Canvas renders real boundaries for Phases 1-3, Lottie globe overlays for Phases 4-5. Labels as Compose Text composables with alpha animation.

**Tech Stack:** Compose Canvas, kotlinx.serialization (GeoJSON parsing), Compottie (Lottie), Compose resources

---

### Task 1: Add GeoJSON Data Files

**Files:**
- Create: `composeApp/src/commonMain/composeResources/files/chicago_communities_real.json`
- Create: `composeApp/src/commonMain/composeResources/files/us_states.json`

**Step 1: Download and process Chicago community areas**

```bash
# Download from Chicago Data Portal Socrata API
curl -sL "https://data.cityofchicago.org/resource/igwz-8jzy.geojson?\$limit=100" -o /tmp/chicago_raw.json

# Clean: reduce coordinate precision to 4 decimals, minimal properties
python3 << 'PYEOF'
import json

with open('/tmp/chicago_raw.json') as f:
    data = json.load(f)

features = []
for feat in data['features']:
    geom = feat['geometry']
    def round_ring(ring):
        return [[round(c[0], 4), round(c[1], 4)] for c in ring]
    if geom['type'] == 'MultiPolygon':
        new_coords = [[round_ring(ring) for ring in poly] for poly in geom['coordinates']]
    elif geom['type'] == 'Polygon':
        new_coords = [round_ring(ring) for ring in geom['coordinates']]
    else:
        new_coords = geom['coordinates']
    features.append({
        'type': 'Feature',
        'properties': {
            'num': int(feat['properties']['area_numbe']),
            'name': feat['properties']['community'],
        },
        'geometry': {'type': geom['type'], 'coordinates': new_coords}
    })

out = {'type': 'FeatureCollection', 'features': features}
with open('composeApp/src/commonMain/composeResources/files/chicago_communities_real.json', 'w') as f:
    json.dump(out, f, separators=(',', ':'))
PYEOF
```

Expected: ~974KB file with 77 features, each with `num`, `name` properties and MultiPolygon/Polygon geometry.

**Step 2: Download and process US states**

```bash
curl -sL "https://eric.clst.org/assets/wiki/uploads/Stuff/gz_2010_us_040_00_20m.json" -o /tmp/us_raw.json

python3 << 'PYEOF'
import json

with open('/tmp/us_raw.json') as f:
    data = json.load(f)

exclude = {'Alaska', 'Hawaii', 'Puerto Rico', 'American Samoa', 'Guam',
           'United States Virgin Islands', 'Commonwealth of the Northern Mariana Islands'}

def simplify_coords(coords, every_n=3):
    if len(coords) <= 4:
        return coords
    result = [coords[0]]
    for i in range(1, len(coords) - 1):
        if i % every_n == 0:
            result.append(coords[i])
    result.append(coords[-1])
    return result

def round_coords(coords, precision=3):
    return [[round(c[0], precision), round(c[1], precision)] for c in coords]

features = []
for feat in data['features']:
    name = feat['properties'].get('NAME', '')
    if name in exclude:
        continue
    geom = feat['geometry']
    if geom['type'] == 'Polygon':
        geom['coordinates'] = [round_coords(simplify_coords(ring)) for ring in geom['coordinates']]
    elif geom['type'] == 'MultiPolygon':
        geom['coordinates'] = [[round_coords(simplify_coords(ring)) for ring in poly] for poly in geom['coordinates']]
    features.append({
        'type': 'Feature',
        'properties': {'name': name},
        'geometry': geom
    })

out = {'type': 'FeatureCollection', 'features': features}
with open('composeApp/src/commonMain/composeResources/files/us_states.json', 'w') as f:
    json.dump(out, f, separators=(',', ':'))
PYEOF
```

Expected: ~229KB file with 49 continental US states.

**Step 3: Verify files**

```bash
ls -la composeApp/src/commonMain/composeResources/files/*.json
# Expected: chicago_communities_real.json (~974KB), us_states.json (~229KB), spinning_globe.json (~2.5MB)
```

**Step 4: Commit**

```bash
git add composeApp/src/commonMain/composeResources/files/chicago_communities_real.json \
        composeApp/src/commonMain/composeResources/files/us_states.json
git commit -m "data: add real Chicago community area and US state boundary GeoJSON"
```

---

### Task 2: Create SplashGeoData.kt — GeoJSON Parser

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashGeoData.kt`

**Step 1: Write the GeoJSON parser**

This file parses the GeoJSON files into simple polygon lists for Canvas rendering. No external GeoJSON library — just kotlinx.serialization on the raw JSON structure.

```kotlin
package com.gcc.app.ui.screens

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A named polygon with lat/lng pairs, parsed from GeoJSON.
 * Coordinate order is (lat, lng) — converted from GeoJSON [lng, lat] on parse.
 */
data class SplashPolygon(
    val id: Int,
    val name: String,
    val rings: List<List<Pair<Double, Double>>>,  // outer ring + any holes
)

/**
 * Parsed geographic data for splash animation phases.
 */
data class SplashGeoData(
    val chicagoAreas: List<SplashPolygon>,   // 77 community areas
    val usStates: List<SplashPolygon>,        // 49 continental states
) {
    val northLawndale: SplashPolygon? get() = chicagoAreas.find { it.id == 29 }
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parse a GeoJSON FeatureCollection string into SplashPolygon lists.
 * Handles both Polygon and MultiPolygon geometry types.
 * Converts GeoJSON [lng, lat] to Kotlin (lat, lng) pairs.
 */
fun parseChicagoGeoJson(jsonString: String): List<SplashPolygon> {
    val root = json.parseToJsonElement(jsonString).jsonObject
    val features = root["features"]!!.jsonArray

    return features.map { feature ->
        val feat = feature.jsonObject
        val props = feat["properties"]!!.jsonObject
        val num = props["num"]!!.jsonPrimitive.int
        val name = props["name"]!!.jsonPrimitive.content
        val geometry = feat["geometry"]!!.jsonObject
        val type = geometry["type"]!!.jsonPrimitive.content
        val coordinates = geometry["coordinates"]!!.jsonArray

        val rings = when (type) {
            "Polygon" -> parsePolygonRings(coordinates)
            "MultiPolygon" -> {
                // MultiPolygon: take first polygon's rings (skip tiny islands)
                val firstPoly = coordinates[0].jsonArray
                parsePolygonRings(firstPoly)
            }
            else -> emptyList()
        }

        SplashPolygon(id = num, name = name, rings = rings)
    }
}

fun parseUsStatesGeoJson(jsonString: String): List<SplashPolygon> {
    val root = json.parseToJsonElement(jsonString).jsonObject
    val features = root["features"]!!.jsonArray

    return features.mapIndexed { index, feature ->
        val feat = feature.jsonObject
        val props = feat["properties"]!!.jsonObject
        val name = props["name"]!!.jsonPrimitive.content
        val geometry = feat["geometry"]!!.jsonObject
        val type = geometry["type"]!!.jsonPrimitive.content
        val coordinates = geometry["coordinates"]!!.jsonArray

        val rings = when (type) {
            "Polygon" -> parsePolygonRings(coordinates)
            "MultiPolygon" -> {
                // Flatten all polygons into one ring list
                coordinates.flatMap { poly ->
                    parsePolygonRings(poly.jsonArray)
                }
            }
            else -> emptyList()
        }

        SplashPolygon(id = index, name = name, rings = rings)
    }
}

private fun parsePolygonRings(polygonCoords: JsonArray): List<List<Pair<Double, Double>>> {
    return polygonCoords.map { ring ->
        ring.jsonArray.map { point ->
            val coords = point.jsonArray
            val lng = coords[0].jsonPrimitive.double
            val lat = coords[1].jsonPrimitive.double
            lat to lng  // Convert GeoJSON [lng,lat] to Kotlin (lat,lng)
        }
    }
}
```

**Step 2: Verify it compiles**

```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashGeoData.kt
git commit -m "feat(splash): add GeoJSON parser for real map data"
```

---

### Task 3: Rewrite SplashScreen.kt — Full 5-Phase Animation

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt`

**Context:** This is the big rewrite. The file currently has 5 timeline constants, Canvas phases 1-2 with simplified polygons, Lottie globe overlay for phases 3+, and helper functions. We replace Phase 1-2 with real GeoJSON data, add Phase 3 (continental US), and add labels throughout.

**Step 1: Rewrite SplashScreen.kt**

Replace the entire file with the new implementation. Key changes:
- Load GeoJSON data in `LaunchedEffect` at start
- Phase 1 (0–1.5s): Real NL boundary from GeoJSON with green sweep + label "North Lawndale, Chicago"
- Phase 2 (1.5–3.5s): All 77 real community areas with radial green wave + label "Chicago, IL"
- Phase 3 (3.5–5s): Continental US with state borders + label "United States"
- Phase 4 (5–6.5s): Zoom-crossfade US → Lottie globe
- Phase 5 (6.5–8s): Globe + logo + "Global Community Cleaners"

The complete new SplashScreen.kt should:

1. **Imports**: Add `Res`, `ExperimentalResourceApi`, Compottie imports, `LaunchedEffect` for data loading
2. **Timeline constants**: 5 phases (0.1875, 0.4375, 0.625, 0.8125, 1.0)
3. **Data loading**: `rememberLottieComposition` for globe, `LaunchedEffect` to parse GeoJSON
4. **Canvas rendering**:
   - Phase 1: Use `SplashGeoData.northLawndale.rings[0]` for the polygon path
   - Phase 2: Use `SplashGeoData.chicagoAreas` for all 77 areas, sorted by distance from NL centroid
   - Phase 3: Use `SplashGeoData.usStates` for state border outlines
5. **Lottie overlay**: Same as current for phases 4-5
6. **Labels**: Compose `Text` composables with alpha animation per phase

**Coordinate mapping for Canvas:**
- Each phase needs its own lat/lng → pixel mapping based on the data extents
- Phase 1: NL bounding box → 70% screen width
- Phase 2: Chicago bounding box (41.64–42.02 lat, -87.85 to -87.52 lng) → 85% screen height
- Phase 3: Continental US bounding box (24.5–49.5 lat, -125 to -66 lng) → 90% screen width

**Step 2: Build and verify compilation**

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt
git commit -m "feat(splash): rewrite with real GeoJSON maps and 5-phase zoom sequence"
```

---

### Task 4: Build, Deploy, and Visual Test

**Step 1: Build iOS app**

```bash
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -derivedDataPath build build 2>&1 | tail -3
```

Expected: BUILD SUCCEEDED

**Step 2: Install and launch on simulator**

```bash
SIM_ID=7A800B91-D9FD-4AC3-BC6B-3850739B6385
xcrun simctl terminate $SIM_ID com.globalcommunitycleaners.worker 2>/dev/null
xcrun simctl install $SIM_ID iosApp/build/Build/Products/Debug-iphonesimulator/Global\ Community\ Cleaners.app
xcrun simctl launch $SIM_ID com.globalcommunitycleaners.worker
```

**Step 3: Capture screenshots at each phase**

```bash
# Phase 1 (~1s): NL close-up with real boundary
sleep 1 && xcrun simctl io $SIM_ID screenshot /tmp/splash_p1.png

# Phase 2 (~2.5s): Chicago zoom-out with 77 real areas
sleep 1.5 && xcrun simctl io $SIM_ID screenshot /tmp/splash_p2.png

# Phase 3 (~4s): Continental US
sleep 1.5 && xcrun simctl io $SIM_ID screenshot /tmp/splash_p3.png

# Phase 4 (~5.5s): Globe transition
sleep 1.5 && xcrun simctl io $SIM_ID screenshot /tmp/splash_p4.png

# Phase 5 (~7s): Logo reveal
sleep 1.5 && xcrun simctl io $SIM_ID screenshot /tmp/splash_p5.png
```

**Step 4: Visual verification checklist**
- [ ] Phase 1: Real NL boundary visible (detailed shape, not boxy rectangle)
- [ ] Phase 1: "North Lawndale, Chicago" label visible
- [ ] Phase 2: 77 community areas visible with real boundaries
- [ ] Phase 2: "Chicago, IL" label visible
- [ ] Phase 3: Continental US with state borders visible
- [ ] Phase 3: Green dot at Chicago's position
- [ ] Phase 3: "United States" label visible
- [ ] Phase 4: Smooth crossfade from US to spinning globe
- [ ] Phase 5: Globe + logo + "Global Community Cleaners"
- [ ] Tap-to-skip still works
- [ ] Login screen appears after splash

**Step 5: Fix any visual issues and commit**

```bash
git add -A
git commit -m "fix(splash): visual polish from simulator testing"
```

---

### Task 5: Final Commit and Push

**Step 1: Push all changes**

```bash
git push
```
