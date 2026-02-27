# Splash Animation — Real Maps Upgrade Design

## Summary

Upgrade the splash animation to use real geographic data at every zoom level: detailed North Lawndale boundary from Chicago Data Portal, all 77 real community area polygons, continental US with state borders, then crossfade to spinning Lottie globe. Adds contextual map labels at each phase.

## Timeline (8 seconds, skippable via tap)

| Phase | Time | Description | Label |
|-------|------|-------------|-------|
| 1. NL Close-Up | 0–1.5s | Real North Lawndale boundary (~100+ vertices). Green sweep + red hotspots. | "North Lawndale, Chicago" |
| 2. Chicago Zoom-Out | 1.5–3.5s | All 77 real community areas. Radial green wave from NL outward. City boundary visible. | "Chicago, IL" |
| 3. Continental US | 3.5–5s | Chicago shrinks/fades. US outline + 48 state borders fade in. Green glow dot on Chicago. | "United States" |
| 4. Globe Transition | 5–6.5s | US shrinks to point on globe. Lottie globe fades in. Zoom + crossfade with ~500ms overlap. | — |
| 5. Logo Reveal | 6.5–8s | Globe shrinks/moves up. GCC logo spring-bounces in. Brand text fades in. Fadeout to app. | "Global Community Cleaners" |

## Data Sources

| File | Source | Size (est.) | Format |
|------|--------|-------------|--------|
| `chicago_communities_real.json` | Chicago Data Portal (`data.cityofchicago.org/resource/igwz-8jzy.geojson`) | ~800KB | GeoJSON FeatureCollection |
| `us_states.json` | Natural Earth simplified | ~200KB | GeoJSON FeatureCollection |

Both files placed in `composeResources/files/`, loaded via `Res.readBytes()`, parsed with kotlinx.serialization into `List<List<Pair<Double, Double>>>` polygon lists. No new library dependencies.

## Architecture

### Files

- **New**: `composeResources/files/chicago_communities_real.json` — real community area boundaries
- **New**: `composeResources/files/us_states.json` — continental US + state borders
- **New**: `SplashGeoData.kt` — GeoJSON parser utility, loads and caches polygon data
- **Modified**: `SplashScreen.kt` — 5-phase animation with real data, labels, US phase

### Rendering Layers

```
Box(fillMaxSize) {
    Canvas(fillMaxSize)          // Phases 1-3: real polygons on dark background
                                  // Phase 4: radial glow behind globe
    Image(lottiePainter)          // Phases 4-5: spinning globe
    Column(labels + logo)         // Phase labels + Phase 5 logo/text
}
```

Canvas draws real polygon data for Phases 1-3. Lottie Image overlays for Phases 4-5. Labels rendered as Compose Text composables with alpha animation.

### Data Flow

```
App.kt → SplashScreen(onFinished)
  LaunchedEffect: load GeoJSON from resources (async, ~50ms)
  masterProgress: Animatable 0→1 over 8000ms
  Phase 1: draw NL polygon from chicago_communities_real.json (area #29)
  Phase 2: draw all 77 areas, radial green wave
  Phase 3: draw US states outline, shrinking Chicago overlay
  Phase 4: crossfade US → Lottie globe
  Phase 5: globe + logo + text
  onFinished() after 8s or tap-to-skip
```

## Visual Design

### Colors (unchanged from current)

| Element | Color |
|---------|-------|
| Background | `0xFF0A0E0A` (near-black green) |
| Uncleaned areas | `0xFF4A4A4A` (grey) |
| Cleaned areas | GccGreen |
| Hotspots | `0xFFE53935` (red), transitions to green |
| US state borders | `Color.White` at 0.2f alpha |
| Labels | `Color.White`, Inter font |
| Green glow | `0xFF2E7D32` |

### Phase 1 — NL Close-Up

- Real NL boundary fills ~70% screen width
- Grey fill → green sweep left-to-right (smoothstep easing)
- 4 red hotspot dots with pulsing glow rings, absorbed by wave
- White border + green glow stroke
- Label "North Lawndale, Chicago" fades in at bottom-center at ~0.3s

### Phase 2 — Chicago Zoom-Out

- Camera pulls back, all 77 real areas render
- Areas grey, radial green wave from NL cleans outward
- NL stays bright green with dark border on top
- Chicago city boundary as thin white outline
- Red hotspot at each area centroid → green when cleaned
- Label "Chicago, IL" fades in at top-center

### Phase 3 — Continental US

- Chicago map shrinks and fades (first 30% of phase)
- US outline + 48 state borders fade in as thin grey/white strokes (no fill)
- Green glowing dot pulses at Chicago's position
- Label "United States" fades in at bottom-center

### Phase 4 — Globe Transition

- US map shrinks toward center while alpha fades
- Lottie globe fades in simultaneously (~500ms crossfade overlap)
- Green particle dots orbit outward
- Radial green glow behind globe

### Phase 5 — Logo Reveal

- Globe shrinks (280dp → 200dp) and moves up
- GCC logo spring-bounces in below globe (dampingRatio 0.5, stiffness 300)
- "Global Community Cleaners" text fades in
- 500ms fadeout to app

### Label Pattern

All labels use consistent animation: 0% alpha → fade in 300ms → hold → fade out 300ms at phase end. White text, Inter font, letter-spacing 0.5–1sp.

### Skip Behavior

Tap anywhere triggers 300ms fadeout via `splashAlpha` Animatable. Unchanged from current.

## No New Dependencies

- GeoJSON parsing: kotlinx.serialization (already in project)
- Lottie globe: Compottie 2.0.2 (already in project)
- Canvas rendering: Compose Foundation (already in project)
