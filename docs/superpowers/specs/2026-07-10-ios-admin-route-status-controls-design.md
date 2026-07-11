# iOS Admin Route Status Controls

**Date:** 2026-07-10

**Status:** Approved design; implementation pending specification review

## Problem

The iOS admin route list displays each route's status, but the status chip is also an unlabeled mutation control. Tapping the gray `draft` chip immediately publishes the route even though it looks like a passive badge. The route detail screen has no status management section.

This made `1500 S Millard Ave` appear stuck in draft. The data state is real rather than a map-rendering defect:

- `POST /api/admin/routes` intentionally creates routes as `draft`;
- the linked block starts as `funded`;
- that non-public block state produces the exceptional colored map treatment;
- publishing through `PUT /api/admin/routes/{routeId}` changes the route to `published` and changes an `available`/`funded` linked block to `available`.

The backend capability and mobile API method already exist. The feature is to make that capability explicit, safe, and observable in the iOS admin experience.

## Goals

- Provide discoverable Draft, Published, and Archived controls in both the route list and route detail screen.
- Separate passive status display from status-changing actions.
- Prevent duplicate taps while an update is in flight.
- Explain the worker/map consequence before each change.
- Show success and failure without replacing the entire Routes tab.
- Keep iOS behavior aligned with the existing web admin workflow.
- Validate status values at the API boundary.

## Non-goals

- Automatically publishing newly created routes.
- Changing route geometry, boundaries, payout, assignments, or jobs.
- Canceling an existing worker assignment when a route is unpublished or archived.
- Adding a new database status or migration.
- Changing the map color system; the map should reflect the resulting route/block state normally.

## Existing behavior and root cause

### Mobile

- `AdminRoutesTab.kt` computes a next status and attaches `clickable` directly to the status `Surface`.
- The chip contains only `draft`, `published`, or `archived`; it has no action label, chevron, confirmation, progress state, or success response.
- `AdminRouteDetailScreen.kt` contains image, assignment, boundary, editing, and deletion controls but no route-status control.
- `AdminPanelViewModel.updateRouteStatus` already calls `GccApiClient.updateRouteStatus` and replaces the updated route in state.
- Status update failures currently use the shared `uiState.error`, which can replace the Routes tab with a full-screen error state.

### Backend

- `PUT /api/admin/routes/{routeId}` already updates `routes.status` and `publishedAt`.
- The same handler synchronizes a linked block between `funded` and `available` when the block is not already in a later lifecycle state.
- The handler currently accepts any `body.status` and relies on the PostgreSQL enum to reject invalid values, which produces an avoidable server error rather than a clear 400 response.

## Approaches considered

### 1. Explicit reusable status actions — selected

Keep the badge read-only and add clearly labeled actions to both list and detail. Reuse one transition model so labels, consequences, and confirmation text cannot drift between screens.

This is the selected approach because it fixes discoverability without creating a separate editor or another backend path.

### 2. Keep the tappable chip and add a hint

Adding `Tap to change` or a chevron would be smaller, but the chip would still perform an immediate live mutation and would not expose Archive clearly. It also leaves the detail screen incomplete.

### 3. Add a general route-edit form with a status dropdown

This would match a desktop CRUD interface, but it adds unnecessary navigation and mixes a high-signal lifecycle action with unrelated metadata editing.

## Selected interaction design

### Shared status presentation

A small pure presentation model maps each `RouteStatus` to:

| Current state | Badge | Helper text | Primary action | Secondary action |
|---|---|---|---|---|
| Draft | gray `Draft` | `Hidden from workers` | `Publish` | `Archive` |
| Published | green `Published` | `Live for workers` | `Unpublish` | `Archive` |
| Archived | blue-gray `Archived` | `Hidden and archived` | `Restore to Draft` | none |

The badge has no click handler. Actions are real Material buttons with accessible labels that include the route name.

### Route list card

Each route card keeps the existing image, name, payout, `Open`, and `Assign` controls. A compact status row appears beneath the route metadata:

- read-only badge and helper text on the left;
- the primary lifecycle action on the right;
- an `Archive` text action beside it for Draft/Published routes.

The primary action is visually strongest only for `Publish`. `Unpublish`, `Restore to Draft`, and `Archive` use neutral/outlined treatments so destructive-looking color is reserved for deletion.

### Route detail screen

A `Route Status` card appears after the image controls and before `Assign Route`. It contains:

- current badge;
- helper text describing worker visibility;
- the same primary and secondary actions as the list;
- an in-place progress indicator while the route is updating.

The detail screen uses the route from the shared `AdminPanelViewModel`, so the card updates immediately when the API returns the canonical route.

### Confirmation dialogs

Every state-changing action opens a confirmation dialog. The copy names both the route and consequence:

- **Publish:** `Publish 1500 S Millard Ave? It will become live for workers and its eligible linked block will become available.`
- **Unpublish:** `Return 1500 S Millard Ave to draft? It will be hidden from workers. Existing assignments or active jobs are not canceled.`
- **Archive:** `Archive 1500 S Millard Ave? It will be hidden from workers. Existing assignments or active jobs are not canceled.`
- **Restore:** `Restore 1500 S Millard Ave to draft? It will remain hidden until you publish it.`

The confirm button uses the exact action label. Cancel makes no request.

## State and data flow

```text
AdminRoutesTab / AdminRouteDetailScreen
  -> request RouteStatus transition
  -> confirmation dialog
  -> AdminPanelViewModel.updateRouteStatus(routeId, RouteStatus)
  -> GccApiClient PUT /api/admin/routes/{id}
  -> route status + eligible linked-block status update
  -> canonical Route response
  -> replace route in AdminPanelUiState
  -> both screens recompose + success snackbar
```

`RouteStatus` remains typed from UI through the API client. A serializable `UpdateRouteStatusRequest(status: RouteStatus)` uses the enum's existing `@SerialName` values (`draft`, `published`, and `archived`); UI call sites no longer pass arbitrary strings.

`AdminPanelUiState` gains:

- `updatingRouteStatusIds: Set<Int>` for per-route loading/duplicate-tap protection;
- `routeStatusNotice: String?` for success;
- `routeStatusActionError: String?` for a scoped, dismissible failure.

The shared global loading/error state remains for initial tab loads. A failed status mutation does not remove the route list.

## Backend validation

The admin route update handler parses `body.status` against exactly:

```text
draft | published | archived
```

An invalid value returns HTTP 400 and does not execute either route or block updates. Existing admin authentication remains unchanged.

The route update and eligible linked-block update execute in one database transaction/batch. The API must not return a failure after committing only the route half of the state change, or success after committing only the block half.

The existing linked-block synchronization remains the only map-state side effect:

- publishing maps an eligible linked block to `available`;
- draft/archive maps an eligible linked block to `funded`;
- blocks already in a later lifecycle state are not rewritten because the existing query limits updates to `available` and `funded`.

## Error handling

- Disable every status action for the affected route while its request is in flight.
- Keep other route cards usable.
- On success, show a short snackbar such as `1500 S Millard Ave published — live for workers.`
- On failure, keep the previous route state and show the server message in a dismissible snackbar/banner.
- If the route disappears or returns 404, show `Route no longer exists` and refresh the route list.
- Do not optimistically recolor the status; use the canonical response so the UI cannot claim publication before the linked backend update completes.

## Testing

### Shared/mobile tests

- Draft maps to Publish + Archive and `Hidden from workers`.
- Published maps to Unpublish + Archive and `Live for workers`.
- Archived maps to Restore to Draft and no Archive action.
- `RouteStatus` wire serialization produces only the three accepted lowercase values.
- A route ID enters and leaves `updatingRouteStatusIds` around success and failure.
- Success replaces only the matching route and emits the correct notice.
- Failure preserves the prior route and emits a scoped action error.
- Duplicate taps for an updating route do not issue a second request.

### Backend tests

- Each valid status returns the updated route.
- Publishing synchronizes an eligible linked block to `available`.
- Draft/archive synchronizes an eligible linked block to `funded`.
- A block in a later lifecycle state is not downgraded.
- An invalid status returns 400 without database mutation.
- A simulated linked-block update failure rolls back the route update as well.

### Manual iOS verification

1. Open Admin → Routes and locate `1500 S Millard Ave`.
2. Confirm the Draft badge is passive and a visible Publish button is present.
3. Publish and confirm the loading state prevents a second tap.
4. Confirm the success message and Published badge.
5. Open the route detail and confirm the same state/actions appear there.
6. Refresh the admin routes and worker map; confirm the status persists and the exceptional draft/funded map fill is removed after normal data refresh.
7. Unpublish, archive, and restore a test route; verify confirmation copy and persistence.

## Acceptance criteria

- An admin can discover and perform every valid route lifecycle transition from both the route list and route detail screen.
- No passive status chip performs a mutation.
- Status changes are confirmed, per-route loading is visible, and failures are non-blocking.
- The API rejects invalid status strings with 400.
- Publishing `1500 S Millard Ave` through the new control persists as Published and produces the normal published/available map state after refresh.
