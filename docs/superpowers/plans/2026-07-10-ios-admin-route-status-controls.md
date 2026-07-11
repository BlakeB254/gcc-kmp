# iOS Admin Route Status Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hidden iOS route-status mutation with explicit, confirmed Draft/Published/Archived controls on both admin screens, backed by typed requests and an atomic validated route/block update.

**Architecture:** Pure Kotlin functions define the route-status transition and copy contract and are covered by `commonTest`; one reusable Compose control renders that contract on list and detail screens. `AdminPanelViewModel` owns per-route concurrency and scoped feedback while preserving admin-only fields omitted by the thin PUT response. The Next.js handler validates status through a pure TypeScript helper and uses Neon HTTP `db.batch` for one atomic route/block transaction.

**Tech Stack:** Kotlin Multiplatform, Compose Material 3, kotlinx.serialization, StateFlow, Ktor client, Next.js App Router, TypeScript, Drizzle ORM with Neon HTTP, Node `node:test` through `tsx`.

## Global Constraints

- Draft remains the default for newly created routes.
- Valid route statuses are exactly `draft`, `published`, and `archived`.
- The status badge is read-only; only labeled buttons may mutate status.
- Publish is the only strong green status action; other lifecycle actions are neutral/outlined.
- Existing assignments and active jobs are not canceled by status changes.
- Linked blocks may change only when their current status is `available` or `funded`.
- Status action failures must not populate the global admin load error.
- No schema migration, Ktor-server change, or new JavaScript test framework.
- Preserve unrelated untracked `.claude/` and `docs/governance/improvement-roadmap.md` files.

---

### Task 1: Typed status contract and pure transition presentation

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminRouteStatus.kt`
- Create: `composeApp/src/commonTest/kotlin/com/gcc/app/ui/screens/admin/AdminRouteStatusTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/AdminModels.kt`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt:747-748`

**Interfaces:**
- Produces: `UpdateRouteStatusRequest(status: RouteStatus)` for the API client.
- Produces: `AdminRouteStatusAction`, `RouteStatusPresentation`, `RouteStatus.presentation()`, `confirmationCopy(routeName)`, and `successCopy(routeName)` for the UI; the UI passes the selected target and notice into the ViewModel.
- Consumes: existing `RouteStatus` enum and its `@SerialName` values.

- [ ] **Step 1: Write the failing Kotlin tests**

Create `AdminRouteStatusTest.kt`:

```kotlin
package com.gcc.app.ui.screens.admin

import com.gcc.shared.model.RouteStatus
import com.gcc.shared.model.UpdateRouteStatusRequest
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminRouteStatusTest {
    @Test fun draftOffersPublishAndArchive() {
        val p = RouteStatus.DRAFT.presentation()
        assertEquals("Draft", p.label)
        assertEquals("Hidden from workers", p.helperText)
        assertEquals(AdminRouteStatusAction.PUBLISH, p.primaryAction)
        assertEquals(AdminRouteStatusAction.ARCHIVE, p.secondaryAction)
    }

    @Test fun publishedOffersUnpublishAndArchive() {
        val p = RouteStatus.PUBLISHED.presentation()
        assertEquals("Published", p.label)
        assertEquals("Live for workers", p.helperText)
        assertEquals(AdminRouteStatusAction.UNPUBLISH, p.primaryAction)
        assertEquals(AdminRouteStatusAction.ARCHIVE, p.secondaryAction)
    }

    @Test fun archivedOffersRestoreOnly() {
        val p = RouteStatus.ARCHIVED.presentation()
        assertEquals("Archived", p.label)
        assertEquals("Hidden and archived", p.helperText)
        assertEquals(AdminRouteStatusAction.RESTORE, p.primaryAction)
        assertNull(p.secondaryAction)
    }

    @Test fun actionTargetsAndCopyAreExplicit() {
        assertEquals(RouteStatus.PUBLISHED, AdminRouteStatusAction.PUBLISH.targetStatus)
        assertEquals(RouteStatus.DRAFT, AdminRouteStatusAction.UNPUBLISH.targetStatus)
        assertEquals(RouteStatus.ARCHIVED, AdminRouteStatusAction.ARCHIVE.targetStatus)
        assertEquals(RouteStatus.DRAFT, AdminRouteStatusAction.RESTORE.targetStatus)
        assertEquals(
            "Publish 1500 S Millard Ave? It will become live for workers and its eligible linked block will become available.",
            AdminRouteStatusAction.PUBLISH.confirmationCopy("1500 S Millard Ave"),
        )
    }

    @Test fun requestUsesRouteStatusSerialNames() {
        assertEquals("{\"status\":\"draft\"}", GccJson.encodeToString(UpdateRouteStatusRequest(RouteStatus.DRAFT)))
        assertEquals("{\"status\":\"published\"}", GccJson.encodeToString(UpdateRouteStatusRequest(RouteStatus.PUBLISHED)))
        assertEquals("{\"status\":\"archived\"}", GccJson.encodeToString(UpdateRouteStatusRequest(RouteStatus.ARCHIVED)))
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.ui.screens.admin.AdminRouteStatusTest"
```

Expected: compilation fails because the request, action, and presentation types do not exist.

- [ ] **Step 3: Add the typed request and pure transition model**

Add to `AdminModels.kt`:

```kotlin
@Serializable
data class UpdateRouteStatusRequest(val status: RouteStatus)
```

Create `AdminRouteStatus.kt`:

```kotlin
package com.gcc.app.ui.screens.admin

import com.gcc.shared.model.RouteStatus

internal enum class AdminRouteStatusAction(val label: String, val targetStatus: RouteStatus) {
    PUBLISH("Publish", RouteStatus.PUBLISHED),
    UNPUBLISH("Unpublish", RouteStatus.DRAFT),
    ARCHIVE("Archive", RouteStatus.ARCHIVED),
    RESTORE("Restore to Draft", RouteStatus.DRAFT),
}

internal data class RouteStatusPresentation(
    val label: String,
    val helperText: String,
    val primaryAction: AdminRouteStatusAction,
    val secondaryAction: AdminRouteStatusAction?,
)

internal fun RouteStatus.presentation(): RouteStatusPresentation = when (this) {
    RouteStatus.DRAFT -> RouteStatusPresentation("Draft", "Hidden from workers", AdminRouteStatusAction.PUBLISH, AdminRouteStatusAction.ARCHIVE)
    RouteStatus.PUBLISHED -> RouteStatusPresentation("Published", "Live for workers", AdminRouteStatusAction.UNPUBLISH, AdminRouteStatusAction.ARCHIVE)
    RouteStatus.ARCHIVED -> RouteStatusPresentation("Archived", "Hidden and archived", AdminRouteStatusAction.RESTORE, null)
}

internal fun AdminRouteStatusAction.confirmationCopy(routeName: String): String = when (this) {
    AdminRouteStatusAction.PUBLISH -> "Publish $routeName? It will become live for workers and its eligible linked block will become available."
    AdminRouteStatusAction.UNPUBLISH -> "Return $routeName to draft? It will be hidden from workers. Existing assignments or active jobs are not canceled."
    AdminRouteStatusAction.ARCHIVE -> "Archive $routeName? It will be hidden from workers. Existing assignments or active jobs are not canceled."
    AdminRouteStatusAction.RESTORE -> "Restore $routeName to draft? It will remain hidden until you publish it."
}

internal fun AdminRouteStatusAction.successCopy(routeName: String): String = when (this) {
    AdminRouteStatusAction.PUBLISH -> "$routeName published — live for workers."
    AdminRouteStatusAction.UNPUBLISH -> "$routeName is now a draft — hidden from workers."
    AdminRouteStatusAction.ARCHIVE -> "$routeName archived — hidden from workers."
    AdminRouteStatusAction.RESTORE -> "$routeName restored to draft."
}
```

Change `GccApiClient.updateRouteStatus` to:

```kotlin
suspend fun updateRouteStatus(routeId: Int, status: RouteStatus): Route =
    putJson("/api/admin/routes/$routeId", UpdateRouteStatusRequest(status))
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/AdminModels.kt shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminRouteStatus.kt composeApp/src/commonTest/kotlin/com/gcc/app/ui/screens/admin/AdminRouteStatusTest.kt
git commit -m "feat(admin): type route status transitions"
```

---

### Task 2: Per-route ViewModel state, feedback, and thin-response preservation

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/AdminRouteStatusState.kt`
- Create: `composeApp/src/commonTest/kotlin/com/gcc/app/viewmodel/AdminRouteStatusStateTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/AdminPanelViewModel.kt:20-60,225-236`

**Interfaces:**
- Consumes: typed `RouteStatus` plus a success notice supplied by the UI.
- Produces: per-route busy state, scoped feedback, and callback-capable `updateRouteStatus` for both screens.
- Preserves: `blockId`, `blockBoundary`, and `innerBoundary` omitted by the PUT response.

- [ ] **Step 1: Write the failing state-transition tests**

Create `AdminRouteStatusStateTest.kt`:

```kotlin
package com.gcc.app.viewmodel

import com.gcc.shared.model.GeoJSONPolygon
import com.gcc.shared.model.Route
import com.gcc.shared.model.RouteStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminRouteStatusStateTest {
private val boundary = GeoJSONPolygon(
    coordinates = listOf(
        listOf(
            listOf(-87.72, 41.86), listOf(-87.71, 41.86),
            listOf(-87.72, 41.87), listOf(-87.72, 41.86),
        ),
    ),
)
private fun route(
    status: RouteStatus = RouteStatus.DRAFT,
    blockId: Int? = 9,
    blockBoundary: GeoJSONPolygon? = boundary,
) = Route(
    id = 4,
    code = "NL-R004",
    displayName = "1500 S Millard Ave",
    serviceAreaId = 1,
    blockId = blockId,
    blockBoundary = blockBoundary,
    status = status,
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
)

@Test fun startRejectsDuplicate() {
    val started = AdminPanelUiState(routes = listOf(route())).startRouteStatusUpdate(4)
    assertEquals(setOf(4), started?.updatingRouteStatusIds)
    assertNull(started?.startRouteStatusUpdate(4))
}

@Test fun successUsesServerStatusButPreservesAdminOnlyFields() {
    val before = AdminPanelUiState(routes = listOf(route())).startRouteStatusUpdate(4)!!
    val thin = route(status = RouteStatus.PUBLISHED, blockId = null, blockBoundary = null)
    val after = before.completeRouteStatusUpdate(4, thin, "published")
    assertEquals(RouteStatus.PUBLISHED, after.routes.single().status)
    assertEquals(9, after.routes.single().blockId)
    assertEquals(boundary, after.routes.single().blockBoundary)
    assertTrue(after.updatingRouteStatusIds.isEmpty())
}

@Test fun failurePreservesRouteAndUsesScopedError() {
    val original = route()
    val after = AdminPanelUiState(routes = listOf(original)).startRouteStatusUpdate(4)!!
        .failRouteStatusUpdate(4, "HTTP 500")
    assertEquals(original, after.routes.single())
    assertEquals("HTTP 500", after.routeStatusActionError)
    assertNull(after.error)
}
}
```

- [ ] **Step 2: Run the focused test and verify RED**

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.viewmodel.AdminRouteStatusStateTest"
```

Expected: compilation fails because the state fields and reducer functions do not exist.

- [ ] **Step 3: Implement pure state transitions and ViewModel wiring**

Create `AdminRouteStatusState.kt`:

```kotlin
internal fun AdminPanelUiState.startRouteStatusUpdate(routeId: Int): AdminPanelUiState? {
    if (routeId in updatingRouteStatusIds) return null
    return copy(
        updatingRouteStatusIds = updatingRouteStatusIds + routeId,
        routeStatusNotice = null,
        routeStatusActionError = null,
    )
}

private fun Route.mergeThinAdminUpdate(updated: Route): Route = updated.copy(
    blockId = updated.blockId ?: blockId,
    blockBoundary = updated.blockBoundary ?: blockBoundary,
    innerBoundary = updated.innerBoundary ?: innerBoundary,
)

internal fun AdminPanelUiState.completeRouteStatusUpdate(routeId: Int, updated: Route, notice: String) = copy(
    routes = routes.map { if (it.id == routeId) it.mergeThinAdminUpdate(updated) else it },
    updatingRouteStatusIds = updatingRouteStatusIds - routeId,
    routeStatusNotice = notice,
    routeStatusActionError = null,
)

internal fun AdminPanelUiState.failRouteStatusUpdate(routeId: Int, message: String) = copy(
    updatingRouteStatusIds = updatingRouteStatusIds - routeId,
    routeStatusActionError = message,
)
```

Add `updatingRouteStatusIds`, `routeStatusNotice`, and `routeStatusActionError` to `AdminPanelUiState`. Implement the ViewModel boundary without importing an UI action type:

```kotlin
fun updateRouteStatus(
    routeId: Int,
    newStatus: RouteStatus,
    successNotice: String,
    onResult: (success: Boolean, error: String?) -> Unit = { _, _ -> },
) {
    val started = _uiState.value.startRouteStatusUpdate(routeId) ?: return
    _uiState.value = started
    scope.launch {
        try {
            val updated = apiClient.updateRouteStatus(routeId, newStatus)
            _uiState.value = _uiState.value.completeRouteStatusUpdate(routeId, updated, successNotice)
            onResult(true, null)
        } catch (e: Exception) {
            val missing = e.message?.contains("404") == true
            val message = if (missing) "Route no longer exists" else e.message ?: "Failed to update route status"
            _uiState.value = _uiState.value.failRouteStatusUpdate(routeId, message)
            onResult(false, message)
            if (missing) forceRefreshCurrentTab()
        }
    }
}

fun clearRouteStatusFeedback() {
    _uiState.value = _uiState.value.copy(routeStatusNotice = null, routeStatusActionError = null)
}
```

- [ ] **Step 4: Run Tasks 1-2 tests and verify GREEN**

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.ui.screens.admin.AdminRouteStatusTest" --tests "com.gcc.app.viewmodel.AdminRouteStatusStateTest"
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/AdminRouteStatusState.kt composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/AdminPanelViewModel.kt composeApp/src/commonTest/kotlin/com/gcc/app/viewmodel/AdminRouteStatusStateTest.kt
git commit -m "feat(admin): track route status updates"
```

---

### Task 3: Reusable Compose controls on route list and detail

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminRouteStatusControls.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminRoutesTab.kt:28-42,200-320`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminRouteDetailScreen.kt:145-270`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminPanelScreen.kt:45-180`

**Interfaces:**
- Consumes: `RouteStatus.presentation()`, `AdminRouteStatusAction`, and ViewModel busy/feedback state.
- Produces: one reusable `AdminRouteStatusControls` plus `ConfirmRouteStatusDialog`.

- [ ] **Step 1: Add the reusable status controls**

Create `AdminRouteStatusControls.kt`. The public composable owns only local confirmation state:

```kotlin
@Composable
internal fun AdminRouteStatusControls(
    route: Route,
    isUpdating: Boolean,
    actionError: String?,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onStatusAction: (AdminRouteStatusAction, (Boolean, String?) -> Unit) -> Unit,
) {
    val presentation = route.status.presentation()
    var pending by remember(route.id) { mutableStateOf<AdminRouteStatusAction?>(null) }
    var dialogError by remember(route.id, pending) { mutableStateOf<String?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RouteStatusBadge(route.status)
            Spacer(Modifier.width(8.dp))
            Text(presentation.helperText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val primary = presentation.primaryAction
            if (primary == AdminRouteStatusAction.PUBLISH) {
                Button(onClick = { pending = primary }, enabled = !isUpdating, modifier = Modifier.weight(1f)) { Text(primary.label) }
            } else {
                OutlinedButton(onClick = { pending = primary }, enabled = !isUpdating, modifier = Modifier.weight(1f)) { Text(primary.label) }
            }
            presentation.secondaryAction?.let { secondary ->
                TextButton(onClick = { pending = secondary }, enabled = !isUpdating) { Text(secondary.label) }
            }
        }
        if (expanded && isUpdating) LinearProgressIndicator(Modifier.fillMaxWidth())
    }

    pending?.let { action ->
        ConfirmRouteStatusDialog(
            route = route,
            action = action,
            isUpdating = isUpdating,
            errorMessage = dialogError ?: actionError,
            onDismiss = { if (!isUpdating) pending = null },
            onConfirm = {
                dialogError = null
                onStatusAction(action) { success, error ->
                    if (success) pending = null else dialogError = error
                }
            },
        )
    }
}
```

Add the badge/accent mapping and dialog in the same file:

```kotlin
internal fun RouteStatus.statusAccentColor(): Color = when (this) {
    RouteStatus.DRAFT -> Color.Gray
    RouteStatus.PUBLISHED -> GccGreen
    RouteStatus.ARCHIVED -> GccSky
}

@Composable
private fun RouteStatusBadge(status: RouteStatus) {
    val p = status.presentation()
    val color = status.statusAccentColor()
    StatusChip(text = p.label, dotColor = color, backgroundColor = color.copy(alpha = 0.15f))
}

@Composable
private fun ConfirmRouteStatusDialog(
    route: Route,
    action: AdminRouteStatusAction,
    isUpdating: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isUpdating) onDismiss() },
        title = { Text(action.label, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(action.confirmationCopy(route.displayName))
                errorMessage?.let { Text(it, color = GccError, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isUpdating) {
                if (isUpdating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(action.label, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isUpdating) { Text("Cancel") } },
    )
}
```

Add `Modifier.semantics { contentDescription = "${action.label} ${route.displayName}" }` to visible action buttons.

- [ ] **Step 2: Replace the hidden list mutation**

Change `AdminRoutesTab` to accept `onStatusChange: (Route, AdminRouteStatusAction, (Boolean, String?) -> Unit) -> Unit`, `updatingRouteStatusIds`, and `routeStatusActionError`. Delete `nextStatus` and remove `Modifier.clickable` from the badge. Render before Open/Assign:

```kotlin
AdminRouteStatusControls(
    route = route,
    isUpdating = route.id in updatingRouteStatusIds,
    actionError = routeStatusActionError,
    onStatusAction = { action, onResult -> onStatusChange(route, action, onResult) },
)
```

- [ ] **Step 3: Add the expanded detail control**

Insert after the photo buttons and before Assign Route:

```kotlin
item {
    GccCard(accentColor = route.status.statusAccentColor()) {
        Text("Route Status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        AdminRouteStatusControls(
            route = route,
            isUpdating = route.id in uiState.updatingRouteStatusIds,
            actionError = uiState.routeStatusActionError,
    expanded = true,
            onStatusAction = { action, onResult ->
                viewModel.updateRouteStatus(
                    routeId = route.id,
                    newStatus = action.targetStatus,
                    successNotice = action.successCopy(route.displayName),
                    onResult = onResult,
                )
            },
        )
    }
}
```

Install a `SnackbarHostState` in the detail `Scaffold`. Observe scoped status notice/error, show the message, then call `clearRouteStatusFeedback()`.

- [ ] **Step 4: Wire admin-panel feedback and typed callbacks**

Add a `SnackbarHostState` to `AdminPanelScreen`'s existing `Scaffold`. Pass the busy/error fields and this typed callback to `AdminRoutesTab`:

```kotlin
onStatusChange = { route, action, onResult ->
    viewModel.updateRouteStatus(
        routeId = route.id,
        newStatus = action.targetStatus,
        successNotice = action.successCopy(route.displayName),
        onResult = onResult,
    )
}
```

Observe `routeStatusNotice`/`routeStatusActionError` in `LaunchedEffect`, show one snackbar, then clear the feedback.

- [ ] **Step 5: Compile and run the Kotlin tests**

```bash
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit Task 3**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminRouteStatusControls.kt composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminRoutesTab.kt composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminRouteDetailScreen.kt composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/admin/AdminPanelScreen.kt
git commit -m "feat(admin): expose route status controls"
```

---

### Task 4: Validate status and atomically synchronize the linked block

**Files:**
- Create: `site/src/lib/admin-route-status.ts`
- Create: `site/src/lib/admin-route-status.test.ts`
- Modify: `site/src/app/api/admin/routes/[routeId]/route.ts:7-100`

**Interfaces:**
- Produces: `AdminRouteStatus`, `parseAdminRouteStatus(value)`, and `eligibleBlockStatusForRouteStatus(status)`.
- Consumes: `routeStatusEnum.enumValues`, Drizzle query builders, and Neon HTTP `db.batch`.

- [ ] **Step 1: Write the failing TypeScript helper tests**

```typescript
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseAdminRouteStatus, eligibleBlockStatusForRouteStatus } from './admin-route-status'

test('accepts exactly the database route statuses', () => {
  assert.equal(parseAdminRouteStatus('draft'), 'draft')
  assert.equal(parseAdminRouteStatus('published'), 'published')
  assert.equal(parseAdminRouteStatus('archived'), 'archived')
})

test('rejects malformed status values', () => {
  for (const value of [undefined, null, 'PUBLISHED', 'active', 1, true]) {
    assert.equal(parseAdminRouteStatus(value), null)
  }
})

test('maps route visibility to eligible linked-block state', () => {
  assert.equal(eligibleBlockStatusForRouteStatus('published'), 'available')
  assert.equal(eligibleBlockStatusForRouteStatus('draft'), 'funded')
  assert.equal(eligibleBlockStatusForRouteStatus('archived'), 'funded')
})
```

- [ ] **Step 2: Run the helper test and verify RED**

```bash
cd site && npx tsx --test src/lib/admin-route-status.test.ts
```

Expected: FAIL because the helper module does not exist.

- [ ] **Step 3: Implement the pure helper**

```typescript
import { routeStatusEnum } from '@/db/schema'

export type AdminRouteStatus = (typeof routeStatusEnum.enumValues)[number]
export type EligibleBlockStatus = 'available' | 'funded'

const ADMIN_ROUTE_STATUSES = new Set<string>(routeStatusEnum.enumValues)

export function parseAdminRouteStatus(value: unknown): AdminRouteStatus | null {
  return typeof value === 'string' && ADMIN_ROUTE_STATUSES.has(value)
    ? (value as AdminRouteStatus)
    : null
}

export function eligibleBlockStatusForRouteStatus(status: AdminRouteStatus): EligibleBlockStatus {
  return status === 'published' ? 'available' : 'funded'
}
```

- [ ] **Step 4: Validate before writes and batch the status transaction**

Distinguish an omitted field from explicit `null`:

```typescript
const hasStatus = Object.prototype.hasOwnProperty.call(body, 'status')
const nextStatus = hasStatus ? parseAdminRouteStatus(body.status) : null
if (hasStatus && !nextStatus) {
  return NextResponse.json({ error: 'status must be draft, published, or archived' }, { status: 400 })
}
```

Build the route update once. For a valid status, execute it with the eligible-block update in one batch:

```typescript
const now = new Date().toISOString()
const routeUpdate = db.update(routes).set(updateData).where(eq(routes.id, routeId)).returning()
let updated: typeof routes.$inferSelect | undefined

if (nextStatus) {
  const [updatedRoutes] = await db.batch([
    routeUpdate,
    db.update(blocks)
      .set({ status: eligibleBlockStatusForRouteStatus(nextStatus), updatedAt: now })
      .where(and(
        inArray(blocks.routeId, db.select({ id: routes.id }).from(routes).where(eq(routes.id, routeId))),
        inArray(blocks.status, ['available', 'funded']),
      )),
  ])
  updated = updatedRoutes[0]
} else {
  ;[updated] = await routeUpdate
}
```

Use `nextStatus` for `updateData.status`/`publishedAt`, reuse `now`, and preserve the existing 404 response. Do not use `db.transaction`; Neon HTTP supports this atomic unit through `db.batch`.

- [ ] **Step 5: Run backend test, typecheck, and build**

```bash
cd site
npx tsx --test src/lib/admin-route-status.test.ts
npx tsc --noEmit
npm run build
```

Expected: helper tests PASS, typecheck exits 0, and Next.js build exits 0. If the local environment exposes a pre-existing dependency-resolution failure, record the exact error and do not report a passing build.

- [ ] **Step 6: Commit Task 4**

```bash
git add site/src/lib/admin-route-status.ts site/src/lib/admin-route-status.test.ts 'site/src/app/api/admin/routes/[routeId]/route.ts'
git commit -m "fix(admin): validate route status atomically"
```

---

### Task 5: End-to-end verification and handoff

**Files:**
- Verify only; update planned code/tests only if a gate exposes a defect.

**Interfaces:**
- Verifies all outputs from Tasks 1-4 together.

- [ ] **Step 1: Run focused tests**

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.ui.screens.admin.AdminRouteStatusTest" --tests "com.gcc.app.viewmodel.AdminRouteStatusStateTest"
cd site && npx tsx --test src/lib/admin-route-status.test.ts && cd ..
```

Expected: all focused tests PASS.

- [ ] **Step 2: Run full compile/test gates**

```bash
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
cd site && npx tsc --noEmit && npm run build
```

Expected: both commands exit 0. Report any unrelated pre-existing failure exactly instead of claiming success.

- [ ] **Step 3: Inspect final scope**

```bash
git diff --check HEAD~4..HEAD
git status --short
git log -4 --oneline
```

Expected: only planned files changed; `.claude/` and `docs/governance/improvement-roadmap.md` remain untracked and untouched.

- [ ] **Step 4: Manual iOS release check**

On an admin-authenticated simulator/device build:

1. Open Admin → Routes and find `1500 S Millard Ave`.
2. Verify the Draft chip is passive and Publish/Archive actions are visible.
3. Publish; verify confirmation, single in-flight request, success message, and Published badge.
4. Open route detail; verify identical status/actions.
5. Refresh Admin and Map; verify persistence and normal published/available map styling.
6. Exercise Unpublish, Archive, and Restore on a test route; verify active assignments are not canceled.

Do not claim this manual gate passed unless it was actually run with an admin account.
