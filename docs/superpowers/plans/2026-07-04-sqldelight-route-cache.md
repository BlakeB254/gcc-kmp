# SQLDelight Route Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire up SQLDelight for the first real time in this codebase and cache one entity (routes), replacing the `emptyList()` fallback in `DashboardViewModel.loadRouteRecordsSafely()` with a genuine local cache of the last successfully-fetched data.

**Architecture:** A JSON-blob-per-row SQLDelight schema in `shared` (avoids flattening `Route`'s 18-field, nested-geometry shape into columns), a small `RouteCacheStore` wrapper class, per-platform `SqlDriver` factories in `composeApp` mirroring the existing `createSecureStorage()` pattern, and one targeted change to `DashboardViewModel`.

**Tech Stack:** SQLDelight 2.0.2, Kotlin Multiplatform (`shared` + `composeApp`), Koin DI.

## Global Constraints

- One entity only (routes) — no `Block`/bounty/spot/wallet caching, no broader offline-first redesign.
- No cache expiry/TTL, no background sync — cache updates only on a successful foreground fetch.
- `RouteCacheStore` and Task 1's tests must use a genuine in-memory SQLite engine (`JdbcSqliteDriver.IN_MEMORY`), not a mock.
- `DashboardViewModel`'s route/block loading is registered via Koin's `singleOf(::DashboardViewModel)` in **both** `AppModule.kt` and `OfflineAppModule.kt` — any new constructor dependency must be registered in both, or the offline/demo build will crash at runtime with an unresolved dependency.
- iOS driver code cannot be compiled or verified on this host at all (confirmed limitation from prior work this session) — Android and Desktop driver code CAN be compile-checked here even though full Android app assembly cannot (separate, pre-existing, unrelated aarch64/AAPT2 limitation).

---

### Task 1: SQLDelight schema + `RouteCacheStore` + tests

**Files:**
- Modify: `shared/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `shared/src/commonMain/sqldelight/com/gcc/shared/cache/RouteCache.sq`
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/cache/RouteCacheStore.kt`
- Test: `shared/src/commonTest/kotlin/com/gcc/shared/cache/RouteCacheStoreTest.kt`

**Interfaces:**
- Produces: `class RouteCacheStore(driver: SqlDriver) { fun replaceAll(routes: List<Route>); fun getAll(): List<Route> }` — consumed by Task 2 (compile-check only) and Task 3.
- Consumes: `com.gcc.shared.model.Route` (pre-existing, `shared/.../model/Route.kt:11-31`), `com.gcc.shared.serialization.GccJson` (pre-existing).

- [ ] **Step 1: Add the missing JVM driver catalog entry**

In `gradle/libs.versions.toml`, in the section around line 90-94 (the existing SQLDelight entries), add:

```toml
sqldelight-sqlite-driver = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }
```

(Alongside the existing `sqldelight-runtime`, `sqldelight-coroutines`, `sqldelight-android-driver`, `sqldelight-native-driver` entries — same `version.ref = "sqldelight"`.)

- [ ] **Step 2: Apply the SQLDelight plugin and configure the database in `shared/build.gradle.kts`**

In the `plugins { }` block (top of the file, alongside `alias(libs.plugins.kotlinMultiplatform)` etc.), add:

```kotlin
    alias(libs.plugins.sqldelight)
```

After the `kotlin { ... }` block (top-level, sibling to it), add:

```kotlin
sqldelight {
    databases {
        create("GccDatabase") {
            packageName.set("com.gcc.shared.cache")
        }
    }
}
```

In `sourceSets { commonMain.dependencies { ... } }`, add:

```kotlin
            implementation(libs.sqldelight.runtime)
```

Add a new `jvmTest.dependencies { }` block (doesn't exist yet in this file — add it as a sibling to the existing `commonTest.dependencies { }` block):

```kotlin
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
```

- [ ] **Step 3: Write the schema**

Create `shared/src/commonMain/sqldelight/com/gcc/shared/cache/RouteCache.sq`:

```sql
CREATE TABLE routeCache (
  id INTEGER NOT NULL PRIMARY KEY,
  json TEXT NOT NULL
);

selectAll:
SELECT * FROM routeCache;

insertOrReplace:
INSERT OR REPLACE INTO routeCache(id, json) VALUES (?, ?);

clear:
DELETE FROM routeCache;
```

(SQLDelight generates a `GccDatabase` class in package `com.gcc.shared.cache` with a `routeCacheQueries` property, and a row data class named `RouteCache` — matching the table name — with fields `id: Long`, `json: String`. This generated `RouteCache` class is why the wrapper below is named `RouteCacheStore`, not `RouteCache` — same name in the same package would be a redeclaration.)

- [ ] **Step 4: Write the failing test**

Create `shared/src/commonTest/kotlin/com/gcc/shared/cache/RouteCacheStoreTest.kt`:

```kotlin
package com.gcc.shared.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.gcc.shared.model.Route
import com.gcc.shared.model.RouteStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun sampleRoute(id: Int, code: String) = Route(
    id = id,
    code = code,
    displayName = "Route $code",
    serviceAreaId = 1,
    geometry = null,
    blockBoundary = null,
    innerBoundary = null,
    status = RouteStatus.PUBLISHED,
    distanceMeters = 1000.0,
    estimatedDurationMinutes = 30,
    payoutAmount = 500,
    checkpoints = emptyList(),
    imageUrl = null,
    imageLocationLat = null,
    imageLocationLng = null,
    description = null,
    createdAt = Instant.parse("2026-07-01T00:00:00Z"),
    updatedAt = Instant.parse("2026-07-01T00:00:00Z"),
)

private fun freshInMemoryStore(): RouteCacheStore {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    GccDatabase.Schema.create(driver)
    return RouteCacheStore(driver)
}

class RouteCacheStoreTest {

    @Test
    fun emptyCacheReturnsEmptyList() {
        val store = freshInMemoryStore()
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun replaceAllThenGetAllRoundTrips() {
        val store = freshInMemoryStore()
        val routes = listOf(sampleRoute(1, "R1"), sampleRoute(2, "R2"))

        store.replaceAll(routes)

        assertEquals(routes, store.getAll())
    }

    @Test
    fun secondReplaceAllFullyReplacesFirst() {
        val store = freshInMemoryStore()
        store.replaceAll(listOf(sampleRoute(1, "R1")))

        store.replaceAll(listOf(sampleRoute(2, "R2")))

        assertEquals(listOf(sampleRoute(2, "R2")), store.getAll())
    }

    @Test
    fun corruptRowIsSkippedNotCrashed() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GccDatabase.Schema.create(driver)
        val store = RouteCacheStore(driver)
        store.replaceAll(listOf(sampleRoute(1, "R1")))
        // Insert a second, corrupt row directly, bypassing RouteCacheStore's
        // normal JSON-encoding path, to simulate a schema-mismatched/corrupted row.
        GccDatabase(driver).routeCacheQueries.insertOrReplace(2L, "not valid json")

        val result = store.getAll()

        assertEquals(listOf(sampleRoute(1, "R1")), result)
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.gcc.shared.cache.RouteCacheStoreTest"`
Expected: FAIL — compile error, `Unresolved reference: RouteCacheStore` / `Unresolved reference: GccDatabase` (neither exists yet).

- [ ] **Step 6: Implement `RouteCacheStore`**

Create `shared/src/commonMain/kotlin/com/gcc/shared/cache/RouteCacheStore.kt`:

```kotlin
package com.gcc.shared.cache

import app.cash.sqldelight.db.SqlDriver
import com.gcc.shared.model.Route
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Local cache of the last successfully-fetched routes list, backed by
 * SQLDelight. One row per route: id (upsert identity) + the whole DTO
 * serialized as JSON -- Route has 18 fields including nested GeoJSON
 * geometry and a checkpoint list, so flattening into columns would be a
 * large, brittle schema that drifts from the Kotlin model over time.
 */
class RouteCacheStore(driver: SqlDriver) {
    private val queries = GccDatabase(driver).routeCacheQueries

    fun replaceAll(routes: List<Route>) {
        queries.transaction {
            queries.clear()
            routes.forEach { route ->
                queries.insertOrReplace(route.id.toLong(), GccJson.encodeToString(route))
            }
        }
    }

    fun getAll(): List<Route> =
        queries.selectAll().executeAsList().mapNotNull { row ->
            try {
                GccJson.decodeFromString(row.json)
            } catch (e: Exception) {
                // A single corrupt/schema-mismatched row must not break the
                // whole cache read -- skip it, keep the rest.
                null
            }
        }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "com.gcc.shared.cache.RouteCacheStoreTest"`
Expected: PASS (4 tests).

- [ ] **Step 8: Run the full shared module test suite to confirm no regressions**

Run: `./gradlew :shared:jvmTest`
Expected: BUILD SUCCESSFUL, all tests pass (including the pre-existing `TokenManagerTest`/`AuthTokenProviderTest`/etc. if present on this branch — note: this branch was created from `main` before the token-refresh work merged, so those specific tests may not exist here yet; that's expected, not a regression).

- [ ] **Step 9: Commit**

```bash
git add shared/build.gradle.kts gradle/libs.versions.toml shared/src/commonMain/sqldelight/com/gcc/shared/cache/RouteCache.sq shared/src/commonMain/kotlin/com/gcc/shared/cache/RouteCacheStore.kt shared/src/commonTest/kotlin/com/gcc/shared/cache/RouteCacheStoreTest.kt
git commit -m "Add SQLDelight route cache schema and RouteCacheStore"
```

---

### Task 2: Platform `SqlDriver` factories

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt`
- Create: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt`
- Create: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt`
- Create: `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt`
- Modify: `composeApp/build.gradle.kts`
- Modify: `composeApp/src/androidMain/kotlin/com/gcc/app/MainActivity.kt`

**Interfaces:**
- Consumes: `RouteCacheStore` (Task 1, only referenced via `GccDatabase.Schema` for driver schema creation).
- Produces: `expect fun createSqlDriver(): SqlDriver` — consumed by Task 3's Koin wiring.

- [ ] **Step 1: Add platform driver dependencies to `composeApp/build.gradle.kts`**

In the `sourceSets { }` block, add to each existing platform dependency block (`androidMain.dependencies { }`, `iosMain.dependencies { }` -- if it doesn't exist yet as a named block, add it, `desktopMain.dependencies { }`):

```kotlin
            implementation(libs.sqldelight.android.driver)   // in androidMain.dependencies
```
```kotlin
            implementation(libs.sqldelight.native.driver)    // in iosMain.dependencies
```
```kotlin
            implementation(libs.sqldelight.sqlite.driver)    // in desktopMain.dependencies
```

Read the actual current file first to see the exact existing structure of these three blocks (they already exist for other platform-specific dependencies like CameraX/MapLibre in androidMain per prior work this session) and add these lines inside the correct existing blocks rather than creating duplicate blocks.

- [ ] **Step 2: Common `expect` declaration**

Create `composeApp/src/commonMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt`:

```kotlin
package com.gcc.app.platform

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific SqlDriver for the shared RouteCacheStore (and any future
 * SQLDelight-backed cache). See docs/superpowers/specs/2026-07-04-sqldelight-
 * route-cache-design.md.
 */
expect fun createSqlDriver(): SqlDriver
```

- [ ] **Step 3: Android actual**

Create `composeApp/src/androidMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt`:

```kotlin
package com.gcc.app.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.gcc.shared.cache.GccDatabase

/**
 * Context must be initialized via [initSqlDriverContext] before first use
 * (called from MainActivity, alongside the existing initSecureStorageContext
 * call -- same pattern as SecureStorage.kt's Android actual).
 */
@Volatile
private var appContext: Context? = null

fun initSqlDriverContext(context: Context) {
    appContext = context.applicationContext
}

actual fun createSqlDriver(): SqlDriver {
    val ctx = requireNotNull(appContext) {
        "SqlDriverFactory: initSqlDriverContext() must be called before createSqlDriver()"
    }
    return AndroidSqliteDriver(GccDatabase.Schema, ctx, "gcc.db")
}
```

- [ ] **Step 4: Wire the Android context init into `MainActivity.kt`**

Read `composeApp/src/androidMain/kotlin/com/gcc/app/MainActivity.kt` around its existing `initSecureStorageContext(this)` call (confirmed at line 32 in a prior investigation this session). Add the import:

```kotlin
import com.gcc.app.platform.initSqlDriverContext
```

And add a call right next to the existing one:

```kotlin
        initSecureStorageContext(this)
        initSqlDriverContext(this)
```

- [ ] **Step 5: iOS actual**

Create `composeApp/src/iosMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt`:

```kotlin
package com.gcc.app.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.gcc.shared.cache.GccDatabase

actual fun createSqlDriver(): SqlDriver = NativeSqliteDriver(GccDatabase.Schema, "gcc.db")
```

- [ ] **Step 6: Desktop actual**

Create `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt`:

```kotlin
package com.gcc.app.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.gcc.shared.cache.GccDatabase

actual fun createSqlDriver(): SqlDriver =
    JdbcSqliteDriver("jdbc:sqlite:gcc.db").also { GccDatabase.Schema.create(it) }
```

- [ ] **Step 7: Verify what can be verified on this host**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL. (This is Kotlin compilation only, not full app assembly — full Android app builds fail on this host for an unrelated, pre-existing reason: AAPT2/adb in the Android SDK are x86_64-only and this host is aarch64, confirmed during prior work this session. Kotlin compilation itself is unaffected by that limitation.)

The iOS actual **cannot be compiled or verified on this host at all** (Kotlin/Native iOS compilation is disabled entirely on this Linux machine) — note this explicitly in the task report rather than attempting any iOS-targeting Gradle command.

- [ ] **Step 8: Commit**

```bash
git add composeApp/build.gradle.kts composeApp/src/commonMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt composeApp/src/androidMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt composeApp/src/desktopMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt composeApp/src/androidMain/kotlin/com/gcc/app/MainActivity.kt
git commit -m "Add platform SqlDriver factories for the route cache"
```

---

### Task 3: Wire the cache into `DashboardViewModel`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/DashboardViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/di/AppModule.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/di/OfflineAppModule.kt`

**Interfaces:**
- Consumes: `RouteCacheStore` (Task 1), `createSqlDriver()` (Task 2).

- [ ] **Step 1: Register `RouteCacheStore` in both Koin modules**

In `composeApp/src/commonMain/kotlin/com/gcc/app/di/AppModule.kt`, add the import:

```kotlin
import com.gcc.shared.cache.RouteCacheStore
```

And add, alongside the other `single { ... }` declarations:

```kotlin
    single { RouteCacheStore(createSqlDriver()) }
```

Apply the identical addition to `composeApp/src/commonMain/kotlin/com/gcc/app/di/OfflineAppModule.kt` — read its current imports/single-declarations first and match its existing style exactly (it uses `singleOf(::DashboardViewModel)` too, per prior investigation this session, so it needs this dependency resolvable as well or the app will crash at runtime in demo/offline mode).

- [ ] **Step 2: Add the constructor dependency to `DashboardViewModel`**

In `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/DashboardViewModel.kt`, add the import:

```kotlin
import com.gcc.shared.cache.RouteCacheStore
```

Change the class declaration from:

```kotlin
class DashboardViewModel(
    private val apiClient: GccApiClient,
) {
```

to:

```kotlin
class DashboardViewModel(
    private val apiClient: GccApiClient,
    private val routeCacheStore: RouteCacheStore,
) {
```

(`singleOf(::DashboardViewModel)` in both Koin modules resolves constructor parameters automatically by type — no other change needed at the call site, as long as Step 1's registration exists in both modules.)

- [ ] **Step 3: Cache on success, fall back to cache on total failure**

Change `loadRouteRecordsSafely()` from:

```kotlin
    private suspend fun loadRouteRecordsSafely(): List<Route> =
        try {
            apiClient.getRoutes()
        } catch (_: Exception) {
            try {
                apiClient.getPublicRoutes()
            } catch (_: Exception) {
                emptyList()
            }
        }
```

to:

```kotlin
    private suspend fun loadRouteRecordsSafely(): List<Route> {
        val routes = try {
            apiClient.getRoutes()
        } catch (_: Exception) {
            try {
                apiClient.getPublicRoutes()
            } catch (_: Exception) {
                null
            }
        }
        if (routes != null) {
            if (routes.isNotEmpty()) routeCacheStore.replaceAll(routes)
            return routes
        }
        // Both live fetches failed -- fall back to the last successfully
        // cached set, instead of returning an empty list outright.
        return routeCacheStore.getAll()
    }
```

(Deliberately does not `replaceAll` when `routes` is a real-but-empty list — an empty live response overwriting a previously-good cache would make the offline fallback worse than doing nothing. Only a genuinely successful, non-empty fetch updates the cache.)

- [ ] **Step 4: Verify the whole module still compiles**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/DashboardViewModel.kt composeApp/src/commonMain/kotlin/com/gcc/app/di/AppModule.kt composeApp/src/commonMain/kotlin/com/gcc/app/di/OfflineAppModule.kt
git commit -m "Wire RouteCacheStore into DashboardViewModel's route-loading fallback"
```

---

## Self-Review Notes

- **Spec coverage**: §2's schema/Gradle setup → Task 1. §2's platform driver factories → Task 2. §2's `DashboardViewModel` wiring → Task 3. §3's 4 named test scenarios → Task 1 Step 4's 4 tests, verbatim. §4 (verification limits, Android/Desktop compile-checkable, iOS not) → Task 2 Step 7's explicit split. §5 (out of scope) — no other entity touched.
- **Placeholder scan**: no TBD/TODO. Task 2 Step 1 has one explicit "read the actual file first" instruction (confirming exact block structure) rather than guessing at code the plan's author didn't re-verify in this exact worktree — a bounded, single fact to confirm, not open-ended design.
- **Type consistency**: `RouteCacheStore(driver: SqlDriver)` constructor and `replaceAll(routes: List<Route>)`/`getAll(): List<Route>` methods (Task 1) are used identically in Task 2 (never directly, only via `GccDatabase.Schema`) and Task 3 (`routeCacheStore.replaceAll(routes)`, `routeCacheStore.getAll()`). `createSqlDriver(): SqlDriver` (Task 2) is called identically in Task 3's Koin wiring (`RouteCacheStore(createSqlDriver())`).
- **Caught during self-review**: Task 1 Step 4's test file originally imported `com.gcc.shared.model.GeoJSONLineString` without using it (`sampleRoute()`'s `geometry` field is always `null`). Removed the unused import.
