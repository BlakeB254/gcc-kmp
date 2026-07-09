# Block Landmarks — Free-Form Drag-and-Drop Placement (Admin)

**Date:** 2026-07-09 · **Status:** Requirement captured, design NOT approved
**Scope:** Spec 7 of the scan/map series

## Requirement (Blake, 2026-07-09)

> "The block landmarks are currently confined to the boundary. Create an admin interface
> where they can be placed anywhere within the block area, with a drag-drop save mechanism
> that updates to the frontend immediately."

## Why it's confined today (root cause)

`composeApp/.../ui/screens/admin/AdminRouteDetailScreen.kt:596` positions the landmark by
an **`initialFraction`** — a 0..1 distance along the route's boundary polyline. The admin
drags a slider along the loop, and the lat/lng is derived by interpolating that fraction
onto the line. There is no 2-D degree of freedom, by construction.

## What already exists (no migration needed)

- **Columns:** `routes.image_location_lat/lng` (`site/src/db/schema.ts:421-422`) and
  `blocks.image_location_lat/lng` (`:1519-1520`) — both plain `doublePrecision`, already
  free-form points. The schema never required the boundary; only the UI did.
- **DTOs:** `Route.imageLocationLat/Lng` (`Route.kt:38`), `Block.imageLocationLat/Lng`
  (`Block.kt:24`), `UpdateRouteRequest.imageLocationLat/Lng` (`AdminModels.kt:59`).
- **Write path:** `PATCH /api/admin/routes/[routeId]` already accepts and persists them
  (`route.ts:61`).
- **Read path:** the map renders them as gold diamond `imageStars`
  (`MapFullScreen.kt:532`, `GccMap.ios.kt` diamond FillLayer).

So this is a **pure client/admin-UI change**. No schema work, no new endpoint.

## Design sketch (to brainstorm)

1. Replace the along-line fraction slider with a draggable marker on the embedded
   `GccMap` in `AdminRouteDetailScreen`, reusing the direct-touch gesture machinery already
   built for `AdminBoundaryEditorScreen` (single `awaitEachGesture` loop; 2 pointers =
   pinch, 1 pointer = drag the handle). That editor already solved screen↔geo projection
   via the shared `BoundaryProjection` class.
2. **Constrain to the block polygon**, not the boundary line: reject (or clamp) a drop
   outside the outer ring. `pointInPolygon` already exists (note the (lat,lng) vs
   `pointInGeoJsonRing`'s [lng,lat] ordering trap — see the indoor-gate work).
3. Save on drop via `updateRoute(imageLocationLat, imageLocationLng)`; optimistic local
   update, revert on failure.
4. "Updates to the frontend immediately": the worker map reads `imageStars` from
   `uiState.routes`, so it refreshes on the next routes fetch. Decide whether that's
   sufficient or whether the admin save should invalidate/refetch the routes cache
   (`RouteCacheStore`) so an admin sees it move without a restart.

## Open questions

1. **Blocks vs routes.** The columns exist on BOTH tables and the map falls back from
   route stars to block stars (`MapFullScreen.kt:536`). Is the landmark a property of the
   route, the block, or both? Editing one while the other holds a stale value will look
   like the drag "didn't save".
2. Should a landmark outside the block be rejected outright, snapped to the nearest
   interior point, or allowed (some landmarks are across the street)?
3. Is there an equivalent admin surface for **blocks** (`AdminBoundaryEditorScreen` edits
   boundaries, not landmarks), or does this only need to exist on the route detail screen?

## Related

- `AdminBoundaryEditorScreen` — the direct-touch drag/pinch precedent, and the
  `BoundaryProjection` screen↔geo helper.
- Spec 1 removed the observation-footprint layers; landmarks (`imageStars`) are a separate,
  still-rendered layer and were not touched.
