# SQLDelight Route Cache — Design Spec

**Date:** 2026-07-04
**Status:** Awaiting review
**Scope:** Minimal, first real use of SQLDelight in this codebase — get the plumbing genuinely wired up and cache exactly one entity (the routes list), replacing part of the hardcoded-demo-data fallback found during a foundational-tech audit. **Not** a broader offline-first redesign — that's explicitly out of scope, a separate future initiative if wanted.

---

## 1. Background

`SQLDelight` is declared in the Gradle version catalog (`gradle/libs.versions.toml:12,90-94`) and the root `build.gradle.kts:9` (`alias(libs.plugins.sqldelight) apply false`) but is applied in **zero** modules — no `.sq` files exist anywhere in the repo. "Offline mode" today means `SeedData.blocks` (`shared/.../offline/SeedData.kt:592`) — 10 hardcoded demo blocks — which `DashboardViewModel.loadBlockRecordsSafely()` (`composeApp/.../viewmodel/DashboardViewModel.kt:307-316`) and `MapFullScreen.kt:251-253` fall back to when the real API returns nothing. This is never a cache of anything the user's own device actually saw — it's the same 10 fake rows for every user, forever.

`loadRouteRecordsSafely()` (`DashboardViewModel.kt:296-306`) fetches routes via `apiClient.getRoutes()` → `apiClient.getPublicRoutes()` → `emptyList()` on total failure — with no seed fallback of its own today. Since `loadBlockRecordsSafely()` already derives blocks from routes when the blocks API is empty (`routeRecords.map { it.toBlockRecord() }`, `DashboardViewModel.kt:314`), making the routes fetch itself resilient (cache-backed) has a cascading benefit: it makes the SeedData fallback for *blocks* fire far less often too, without touching block-caching directly.

## 2. Design

### Schema (pragmatic JSON-blob approach, not flattened columns)

`Route` (`shared/.../model/Route.kt:11-31`) has 18 fields including nested `GeoJSONLineString`/`GeoJSONPolygon`/`GeoJSONMultiPolygon` geometry and a `List<RouteCheckpoint>` — flattening all of this into SQL columns would be a large, brittle schema that drifts from the Kotlin model over time. Instead: one row per route, `id` (for upsert identity) + the whole DTO serialized as JSON (via the existing shared `GccJson` instance, `shared/.../serialization/GccJson.kt`) in a `TEXT` column.

`shared/src/commonMain/sqldelight/com/gcc/shared/cache/RouteCache.sq`:
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

SQLDelight auto-generates a `RouteCache` row data class (`id: Long`, `json: String`) in package `com.gcc.shared.cache` (configured via the Gradle plugin's `packageName`) — this collides by name with the intended wrapper class, so the wrapper is named **`RouteCacheStore`**, not `RouteCache`, to avoid a redeclaration.

### Gradle setup (`shared/build.gradle.kts`)

- Apply `alias(libs.plugins.sqldelight)` in the plugins block.
- Add the `sqldelight { databases { create("GccDatabase") { packageName.set("com.gcc.shared.cache") } } }` config block.
- `commonMain.dependencies`: add `libs.sqldelight.runtime`.
- A new catalog entry is needed (doesn't exist yet): `sqldelight-sqlite-driver = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }` (JVM/JDBC driver — for both `:shared:jvmTest`'s in-memory testing and `composeApp`'s Desktop actual).
- `jvmTest.dependencies` (new block — doesn't exist yet in this file): add `libs.sqldelight.sqlite.driver`. Deliberately scoped to `jvmTest`, not `jvmMain`, so the `server/` module (which depends on `shared`'s `jvm()` target output) doesn't pick up an unused SQL driver dependency transitively.

### `RouteCacheStore` (`shared/src/commonMain/kotlin/com/gcc/shared/cache/RouteCacheStore.kt`)

```kotlin
package com.gcc.shared.cache

import app.cash.sqldelight.db.SqlDriver
import com.gcc.shared.model.Route
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

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
                GccJson.decodeFromString<Route>(row.json)
            } catch (e: Exception) {
                // A single corrupt/schema-mismatched row must not break the whole
                // cache read -- skip it, keep the rest.
                null
            }
        }
}
```

### Platform driver factory (`composeApp`, mirrors the existing `createSecureStorage()` pattern exactly)

`composeApp/src/commonMain/kotlin/com/gcc/app/platform/SqlDriverFactory.kt`:
```kotlin
package com.gcc.app.platform

import app.cash.sqldelight.db.SqlDriver

expect fun createSqlDriver(): SqlDriver
```

- **Android** (`composeApp/src/androidMain/.../SqlDriverFactory.kt`): `AndroidSqliteDriver(GccDatabase.Schema, context, "gcc.db")`, using the exact same `@Volatile private var appContext` + `initSqlDriverContext(context)` pattern `SecureStorage.kt` (Android) already uses — called from `MainActivity` alongside the existing `initSecureStorageContext(this)` call.
- **iOS** (`composeApp/src/iosMain/.../SqlDriverFactory.kt`): `NativeSqliteDriver(GccDatabase.Schema, "gcc.db")` — no context needed, this one is genuinely simpler than Android's.
- **Desktop** (`composeApp/src/desktopMain/.../SqlDriverFactory.kt`): `JdbcSqliteDriver("jdbc:sqlite:gcc.db").also { GccDatabase.Schema.create(it) }` (JDBC driver needs explicit schema creation on first use, unlike Android/iOS's drivers which handle it internally).

New dependencies needed in `composeApp/build.gradle.kts`: `libs.sqldelight.android.driver` (androidMain), `libs.sqldelight.native.driver` (iosMain), `libs.sqldelight.sqlite.driver` (desktopMain).

### Wiring (`DashboardViewModel.kt`)

`loadRouteRecordsSafely()` changes from:
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
to (conceptually — exact diff finalized in the implementation plan): fetch succeeds → `routeCacheStore.replaceAll(routes)` then return them; both fetches fail → return `routeCacheStore.getAll()` instead of `emptyList()`. `RouteCacheStore` is injected via the existing Koin DI pattern (`AppModule.kt`), constructed from `createSqlDriver()`.

## 3. Test coverage

`RouteCacheStoreTest` (`shared/src/commonTest/kotlin/com/gcc/shared/cache/RouteCacheStoreTest.kt`), using `app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)` + `GccDatabase.Schema.create(driver)` for a genuine (not mocked) in-memory SQLite instance per test:
- Empty cache → `getAll()` returns an empty list.
- `replaceAll(routes)` then `getAll()` → returns the same routes (round-trip through real JSON serialization + real SQL).
- `replaceAll(routes)` called twice with different data → `getAll()` reflects only the second call's data (confirms `clear()` inside the transaction actually replaces, not appends).
- A corrupted row (`json` column containing malformed data, inserted directly via a raw query bypassing `RouteCacheStore`) → `getAll()` skips it and returns the remaining valid rows, not a crash.

## 4. Verification limits

Android and Desktop driver code can be verified for compile-correctness on this machine (`:composeApp:compileDebugKotlinAndroid`, `:composeApp:compileKotlinDesktop`) even though full Android app assembly cannot (separate, pre-existing aarch64/AAPT2 host limitation, unrelated to this feature). **iOS driver code cannot be verified at all on this host** — same limitation as prior iOS work this session, needs a real MacBook build. `RouteCacheStore`'s actual logic is fully verified via a real in-memory SQLite engine regardless of platform, since that's pure `shared`/commonMain code.

## 5. Out of scope

No caching for `Block`, bounties, spots, wallet, or any other entity — this is deliberately the smallest real slice that proves the SQLDelight plumbing works end-to-end. No cache expiry/TTL policy (routes don't change often; a simple "replace on every successful fetch" is sufficient for this pass). No background sync — the cache only updates when a normal foreground fetch succeeds.
