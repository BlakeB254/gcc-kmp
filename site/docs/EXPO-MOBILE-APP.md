# GCC Mobile App - Expo Setup Prompt

## Why GCC Needs a Mobile App

GCC workers perform block cleaning jobs **in the field** using their phones. The current web app works but a native mobile app provides:

1. **Reliable GPS Tracking** - Native location APIs are more accurate than web geolocation
2. **Offline Support** - Workers may lose signal in some areas; offline mode caches job data
3. **Background Location** - Track cleaning path even when app is backgrounded
4. **Push Notifications** - Alert workers when new blocks become available
5. **Camera Integration** - Native camera for before/after verification photos
6. **Geofencing** - Native geofencing for checkpoint verification (more reliable than web)

## Expo App Setup Prompt

Use this prompt to create the GCC Expo mobile app:

---

### Prompt: Create GCC Expo Mobile App

```
Create an Expo mobile app for GCC (Global Community Cleaners) - a block cleaning gig economy app.

## Project Context

GCC is a community block cleaning app where:
- Workers claim funded blocks in North Lawndale, Chicago
- Workers walk the block perimeter, verifying 4 corner checkpoints via GPS
- GPS path is recorded during cleaning
- Before/after photos are submitted
- Payment is released after review

The web app exists at /home/codex450/CDX-DEV-PROJECTS/gcc - use it as reference for:
- UI patterns and color scheme
- Status flow (claimed → started → checkpoints → submitted → review → paid)
- API endpoints (src/app/api/)

## Technical Requirements

1. **Create Expo app structure:**
   - Location: /home/codex450/CDX-DEV-PROJECTS/gcc-mobile
   - Use Expo SDK 52+ with expo-router
   - Follow Buddy Ride structure as template: /home/codex450/CDX-DEV-PROJECTS/buddy-ride/apps/expo

2. **Install core dependencies:**
   ```bash
   npx create-expo-app@latest gcc-mobile --template tabs
   cd gcc-mobile
   npm install @cdx/native-maps@workspace:* \
     @rnmapbox/maps expo-location expo-camera \
     expo-notifications expo-secure-store \
     react-native-reanimated nativewind \
     zustand @tanstack/react-query
   ```

3. **Configure for Chicago maps:**
   - Import from @cdx/native-maps: gccConfig, GCC_TILE_LAYERS, GCC_GEOFENCE_CONFIG
   - Use MapLibre GL Native (react-native-maplibre-gl) for free maps
   - Set default center to North Lawndale: [-87.7158, 41.8666]

4. **Key screens to implement:**
   - (tabs)/index.tsx - Available blocks map view
   - (tabs)/my-jobs.tsx - Worker's claimed/active jobs
   - job/[id].tsx - Active job with map, checkpoints, path recording
   - job/[id]/checkpoint.tsx - Checkpoint verification screen
   - job/[id]/submit.tsx - Photo upload and submission
   - profile/ - Worker profile, earnings history

5. **Critical features:**
   - Background location tracking during active jobs
   - Geofencing for 4 checkpoint corners (15m radius per GCC_GEOFENCE_CONFIG)
   - Path recording (position every 5 seconds)
   - Offline job data caching
   - Native camera for verification photos
   - Celebration animation on job completion (use existing confetti from web)

6. **Environment variables:**
   - GCC_API_URL (web backend API)
   - EXPO_PUBLIC_TILE_SERVER_URL (CDX tile server)

## Files to Reference

- GCC web app: /home/codex450/CDX-DEV-PROJECTS/gcc/src/
- Native maps config: /home/codex450/CDX-DEV-PROJECTS/packages/native-maps/src/config/gcc.ts
- Buddy Ride Expo app: /home/codex450/CDX-DEV-PROJECTS/buddy-ride/apps/expo/

## Architecture Notes

- Use Zustand for local state (job progress, GPS positions)
- Use TanStack Query for API calls with offline caching
- MapLibre GL Native for free vector tile rendering
- Expo Location for foreground/background tracking
- Expo TaskManager for background geofencing
```

---

## Implementation Phases

### Phase 1: Foundation (Day 1-2)
- [ ] Create Expo app with expo-router
- [ ] Configure NativeWind + tailwind
- [ ] Set up @cdx/native-maps integration
- [ ] Implement authentication flow (phone verification)

### Phase 2: Core Features (Day 3-5)
- [ ] Available blocks map view
- [ ] Job claiming flow
- [ ] Active job screen with map
- [ ] GPS path recording

### Phase 3: Geofencing (Day 6-7)
- [ ] Checkpoint verification system
- [ ] Background location tracking
- [ ] Geofence triggers for checkpoints

### Phase 4: Completion Flow (Day 8-9)
- [ ] Photo capture (before/after)
- [ ] Job submission
- [ ] Celebration animations
- [ ] Earnings display

### Phase 5: Polish (Day 10)
- [ ] Offline mode
- [ ] Push notifications
- [ ] Error handling
- [ ] Performance optimization

## Integration with @cdx/native-maps

The shared package provides:

```typescript
import {
  // GCC-specific configuration
  gccConfig,
  GCC_TILE_LAYERS,
  GCC_GEOFENCE_CONFIG,
  GCC_BLOCK_COLORS,
  GCC_STATUS_STEPS,
  GCC_CELEBRATION_CONFIG,

  // Shared Chicago data
  NORTH_LAWNDALE_CENTER,
  NORTH_LAWNDALE_BOUNDS,

  // Tile server helpers
  getTileSource,
  getTileUrlTemplate,
  checkTileServerHealth,
} from '@cdx/native-maps'
```

## MapLibre vs Mapbox for GCC

GCC uses **MapLibre GL** (free, no API key) instead of Mapbox because:
1. Cost-effective for gig economy workers
2. Same vector tile support
3. CDX tile server works with both

For React Native, use `@maplibre/maplibre-react-native`:
```bash
npm install @maplibre/maplibre-react-native
```
