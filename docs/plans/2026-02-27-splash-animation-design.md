# Splash Animation Design

## Summary

Cinematic ~8-second splash animation that plays on every cold start (skippable via tap). Shows a "cleaning wave" starting from North Lawndale, zooming out through Chicago community areas, morphing into a spinning globe — all rendered in pure Compose Canvas.

## Timeline

| Phase | Time | Description |
|-------|------|-------------|
| 1. Close-up | 0-2s | North Lawndale polygon fills screen in grey. Red hotspot dots pulse. Green cleaning wave sweeps left-to-right. |
| 2. Zoom out | 2-4.5s | Camera pulls back revealing ~20 community areas in grey. Radial green wave from North Lawndale cleans each area outward. |
| 3. Globe morph | 4.5-6.5s | Map shrinks into circle. Simplified continent outlines fade in. All landmass green, ocean blue. |
| 4. Logo + exit | 6.5-8s | Globe rotates (X-offset shift). GCC logo spring-bounces in. "Global Community Cleaners" fades in. Crossfade to app. |

## Architecture

### Files

- **New**: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt` — full animation
- **Modified**: `composeApp/src/commonMain/kotlin/com/gcc/app/App.kt` — splash gate before Decompose navigation

### Data Flow

```
App.kt
  showSplash = true
    -> SplashScreen(onFinished = { showSplash = false })
         Phase 1-4 via LaunchedEffect + Animatable
         Tap anywhere -> onFinished() with 300ms fadeOut
         After 8s -> onFinished() automatically
  showSplash = false
    -> Children(stack = ...) normal navigation
```

### No New Dependencies

Uses existing Canvas, Animatable, tween, spring APIs. Community area polygons from CommunityAreaData.kt.

## Visual Design

### Colors

| Element | Start | End |
|---------|-------|-----|
| Background | GccGreenDark (0xFF0F4C2A) | Same |
| Community areas | Grey (0xFF4A4A4A) | GccGreen |
| Red hotspots | 0xFFE53935, pulsing | GccGreen (absorbed) |
| Globe ocean | Transparent | 0xFF1565C0 |
| Globe continents | Fade in | GccGreen |
| Logo | Spring scale-in | White |

### Phase Details

**Phase 1**: North Lawndale polygon drawn large (~70% screen width). Cleaning wave is a vertical clip rect sweeping left-to-right revealing green beneath grey. 3-4 red circles pulse then shrink as wave passes.

**Phase 2**: Canvas scale() + translate() to zoom out. Areas appear by distance from North Lawndale centroid. Each grey-to-green with 200ms tween on radial progress trigger. 1-2 red hotspots per area.

**Phase 3**: Circular clip shrinks around map. Chicago polygons alpha-out, continent outlines alpha-in. Blue ocean fill. White ring outline.

**Phase 4**: Continent X-offset shifts for rotation parallax. GccLogo with DampingRatioMediumBouncy spring. Text fadeIn. Splash alpha 1-to-0 over 500ms to exit.

### Skip Behavior

Modifier.pointerInput on root Box. Any tap triggers onFinished() with fast 300ms fadeOut.

### Continent Data

~6 simplified continent Paths (8-12 vertices each) hardcoded in SplashScreen.kt. Just enough to be recognizable on a small globe circle.

## Timing Convention

Follows project Fibonacci timing: 300ms, 377ms, 600ms, 1200ms, 1597ms, 2000ms.
