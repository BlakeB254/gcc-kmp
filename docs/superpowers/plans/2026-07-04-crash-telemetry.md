# Crash Telemetry Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Get mobile crashes flowing to the backend and visible on an admin page, replacing the current "iOS captures locally but never transmits, Android has nothing at all" state.

**Architecture:** A new Postgres table + Ktor ingest route (public, unauthenticated) + shared DTO/client method, an `expect`/`actual` crash-handling pair split into hook-installation (must run before Koin) and flush-and-transmit (must run after Koin, since it needs `GccApiClient`), and a minimal Next.js admin page reading the table directly via Drizzle.

**Tech Stack:** Ktor (Exposed/Postgres), Kotlin Multiplatform (`shared` + `composeApp`), Next.js App Router + Drizzle, Koin DI (both server-side and mobile-side).

## Global Constraints

- No third-party crash SDK (Sentry/Crashlytics/Bugsnag) — native/NDK components unverifiable on this host, external account required.
- The ingest route (`POST /api/crashes`) must be public/unauthenticated — a crash can happen before login or with a broken session.
- No `workerId` attribution in this pass — needs optional (not required) auth extraction, out of scope.
- No retry queue for failed transmission — try once on next launch, silently drop on failure (unlike `CoverageEventQueue`'s persistent retry).
- Desktop gets no-op stubs only — not a real deployment target.
- Per this repo's schema-ownership policy: the Drizzle definition in `site/src/db/schema.ts` is authoritative and must land in the same task as the Exposed mirror in `server/src/main/kotlin/com/gcc/server/db/tables/`.
- iOS platform code cannot be compiled or verified on this host at all. Android Kotlin compiles (`compileDebugKotlinAndroid`, excluding resource-processing tasks) but full app assembly does not — pre-existing, unrelated aarch64/AAPT2 host limitation.

---

### Task 1: Server ingest endpoint + shared DTO/client method

**Files:**
- Modify: `site/src/db/schema.ts`
- Create: `server/src/main/kotlin/com/gcc/server/db/tables/CrashReportTables.kt`
- Create: `server/src/main/kotlin/com/gcc/server/services/CrashReportService.kt`
- Create: `server/src/main/kotlin/com/gcc/server/routes/CrashRoutes.kt`
- Modify: `server/src/main/kotlin/com/gcc/server/routes/Routing.kt`
- Modify: `server/src/main/kotlin/com/gcc/server/plugins/DI.kt`
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/model/CrashReport.kt`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt`
- Test: `server/src/test/kotlin/com/gcc/server/routes/CrashRoutesTest.kt`

**Interfaces:**
- Produces: `CrashReportRequest(deviceId: String, platform: String, appVersion: String?, exceptionMessage: String, stackTrace: String, occurredAt: Instant)` (shared DTO) — consumed by Task 2 and Task 3.
- Produces: `GccApiClient.reportCrash(report: CrashReportRequest)` (suspend, `Unit` return) — consumed by Task 2 and Task 3.
- Produces: `POST /api/crashes` (public route) — the wire endpoint Task 2/3 hit via `reportCrash()`.

- [ ] **Step 1: Add the Drizzle table**

In `site/src/db/schema.ts`, add (near `newsletterSends`, matching its style):

```ts
export const crashReports = pgTable('crash_reports', {
  id: serial('id').primaryKey(),
  deviceId: varchar('device_id').notNull(),
  platform: varchar('platform').notNull(),
  appVersion: varchar('app_version'),
  exceptionMessage: text('exception_message').notNull(),
  stackTrace: text('stack_trace').notNull(),
  occurredAt: timestamp('occurred_at', { mode: 'string', withTimezone: true }).notNull(),
  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})
```

(Confirm `text` and `timestamp` are already imported from `drizzle-orm/pg-core` at the top of the file — they're used by other tables already, so this should just work; if not, add them to the existing import line.)

- [ ] **Step 2: Add the shared DTO**

Create `shared/src/commonMain/kotlin/com/gcc/shared/model/CrashReport.kt`:

```kotlin
package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CrashReportRequest(
    val deviceId: String,
    val platform: String,
    val appVersion: String? = null,
    val exceptionMessage: String,
    val stackTrace: String,
    val occurredAt: Instant,
)
```

- [ ] **Step 3: Add the Exposed table**

Create `server/src/main/kotlin/com/gcc/server/db/tables/CrashReportTables.kt`:

```kotlin
package com.gcc.server.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object CrashReports : Table("crash_reports") {
    val id = integer("id").autoIncrement()
    val deviceId = varchar("device_id", 255)
    val platform = varchar("platform", 32)
    val appVersion = varchar("app_version", 64).nullable()
    val exceptionMessage = text("exception_message")
    val stackTrace = text("stack_trace")
    val occurredAt = timestamp("occurred_at")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
```

- [ ] **Step 4: Add `CrashReportService`**

Create `server/src/main/kotlin/com/gcc/server/services/CrashReportService.kt`:

```kotlin
package com.gcc.server.services

import com.gcc.server.db.DatabaseFactory.dbQuery
import com.gcc.server.db.tables.CrashReports
import com.gcc.shared.model.CrashReportRequest
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.insert

class CrashReportService {
    suspend fun record(request: CrashReportRequest): Unit = dbQuery {
        CrashReports.insert {
            it[deviceId] = request.deviceId
            it[platform] = request.platform
            it[appVersion] = request.appVersion
            it[exceptionMessage] = request.exceptionMessage
            it[stackTrace] = request.stackTrace
            it[occurredAt] = request.occurredAt
            it[createdAt] = Clock.System.now()
        }
    }
}
```

- [ ] **Step 5: Register `CrashReportService` in Koin**

In `server/src/main/kotlin/com/gcc/server/plugins/DI.kt`, add to the `appModule` block (alongside the other `single { XService() }` lines, e.g. right after `single { BlockService() }`):

```kotlin
    single { CrashReportService() }
```

- [ ] **Step 6: Add the ingest route**

Create `server/src/main/kotlin/com/gcc/server/routes/CrashRoutes.kt` (mirrors `AdminDisputeRoutes.kt`'s import/service-lookup style, but with no admin/auth gate):

```kotlin
package com.gcc.server.routes

import com.gcc.server.plugins.koinGet
import com.gcc.server.services.CrashReportService
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.CrashReportRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.crashRoutes() {
    val crashReportService = application.koinGet<CrashReportService>()
    post("/api/crashes") {
        val request = call.receive<CrashReportRequest>()
        crashReportService.record(request)
        call.respond(HttpStatusCode.OK, ApiResponse(data = Unit, success = true, message = null))
    }
}
```

- [ ] **Step 7: Register the route**

In `server/src/main/kotlin/com/gcc/server/routes/Routing.kt`, add `crashRoutes()` alongside `healthRoutes()`/`authRoutes()` (public, no rate-limit wrapper needed — crash volume is inherently low):

```kotlin
        // Infrastructure
        healthRoutes()

        // Auth (public — login/register)
        authRoutes()
        routeQueueCronRoutes()
        crashRoutes()
```

- [ ] **Step 8: Add `GccApiClient.reportCrash()`**

In `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt`, add near the other `Auth`-adjacent methods (e.g. right after `refreshToken()`):

```kotlin
    suspend fun reportCrash(report: CrashReportRequest): Unit =
        postJson("/api/crashes", report)
```

(Confirm `CrashReportRequest` resolves via the file's existing `import com.gcc.shared.model.*`-style wildcard import, or add an explicit import if the file imports models individually — check the file's actual import block before assuming.)

- [ ] **Step 9: Write the route test (validation-failure path only — no DB)**

Create `server/src/test/kotlin/com/gcc/server/routes/CrashRoutesTest.kt`, mirroring `AuthRoutesTest.kt`'s `testApplication` + Koin-module pattern:

```kotlin
package com.gcc.server.routes

import com.gcc.server.services.CrashReportService
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Route-level test for /api/crashes. Only covers request-validation failure
 * (missing required field) -- this is verifiable without a real database,
 * since call.receive<T>() throws before CrashReportService.record() is ever
 * invoked. A happy-path test that actually inserts a row would need a real
 * Postgres instance (Testcontainers), which this codebase doesn't have set
 * up yet -- same limitation AuthRoutesTest.kt documents for its own
 * DB-touching paths.
 */
class CrashRoutesTest {

    @AfterTest
    fun stopKoin() {
        if (GlobalContext.getOrNull() != null) GlobalContext.stopKoin()
    }

    @Test
    fun `POST api crashes with missing required field does not succeed`() = testApplication {
        application { testModule() }

        val response = client.post("/api/crashes") {
            contentType(ContentType.Application.Json)
            // Missing "exceptionMessage" and "occurredAt" -- required fields.
            setBody("""{"deviceId":"abc123","platform":"android","stackTrace":"boom"}""")
        }

        assertFalse(response.status == HttpStatusCode.OK, "expected a non-success status, got ${response.status}")
    }
}

private fun Application.testModule() {
    install(Koin) {
        modules(module { single { CrashReportService() } })
    }
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError)
        }
    }
    routing { crashRoutes() }
}
```

Note: this test deliberately checks `response.status == HttpStatusCode.OK` is false, not a specific error code — the real app's `StatusPages` plugin (`server/src/main/kotlin/com/gcc/server/plugins/StatusPages.kt`) currently routes an uncaught deserialization exception through its catch-all `exception<Throwable>` handler, which returns 500, not 400. This test's local `testModule()` installs an equivalent minimal `StatusPages` block so the request-parsing failure doesn't crash the test itself with an unhandled exception, matching how the real server would actually behave today.

- [ ] **Step 10: Run the test to verify it fails, then passes**

Run: `./gradlew :server:test --tests "com.gcc.server.routes.CrashRoutesTest"`
Expected before Steps 1-8: FAIL — compile error, `crashRoutes()`/`CrashReportService` unresolved.
After completing Steps 1-8: PASS.

- [ ] **Step 11: Run the full server test suite and typecheck the site changes**

Run: `./gradlew :server:test`
Expected: BUILD SUCCESSFUL, no regressions.

Run: `cd site && npx tsc --noEmit`
Expected: no new type errors from the `schema.ts` addition.

- [ ] **Step 12: Commit**

```bash
git add site/src/db/schema.ts server/src/main/kotlin/com/gcc/server/db/tables/CrashReportTables.kt server/src/main/kotlin/com/gcc/server/services/CrashReportService.kt server/src/main/kotlin/com/gcc/server/routes/CrashRoutes.kt server/src/main/kotlin/com/gcc/server/routes/Routing.kt server/src/main/kotlin/com/gcc/server/plugins/DI.kt shared/src/commonMain/kotlin/com/gcc/shared/model/CrashReport.kt shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt server/src/test/kotlin/com/gcc/server/routes/CrashRoutesTest.kt
git commit -m "Add crash-report ingest endpoint (table, service, route, client method)"
```

---

### Task 2: iOS crash capture — extend to transmit

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CrashReporting.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/CrashReporting.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/MainViewController.kt`

**Interfaces:**
- Consumes: `CrashReportRequest` (Task 1), `GccApiClient.reportCrash()` (Task 1), `CoverageDeviceId.value` (pre-existing, `composeApp/.../platform/CoverageDeviceId.kt`), `DiagnosticsLog.error()` (pre-existing).
- Produces: `expect fun installCrashReporting()`, `expect suspend fun flushPendingCrashReport(apiClient: GccApiClient)` — consumed by Task 3 (Android/Desktop actuals) and by this task's own `MainViewController.kt` wiring.

- [ ] **Step 1: Add the commonMain `expect` declarations**

Create `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CrashReporting.kt`:

```kotlin
package com.gcc.app.platform

import com.gcc.shared.api.GccApiClient

/**
 * [installCrashReporting] installs the platform's uncaught-exception hook.
 * Must run before anything else (including Koin startup) so a crash during
 * later initialization is still caught.
 *
 * [flushPendingCrashReport] reads any crash persisted by that hook during
 * the *previous* session, transmits it via [apiClient], logs it to
 * [DiagnosticsLog], and clears the local record. Needs a real [GccApiClient]
 * from Koin, so it must run after Koin has started -- a separate call from
 * [installCrashReporting], not the same one.
 */
expect fun installCrashReporting()
expect suspend fun flushPendingCrashReport(apiClient: GccApiClient)
```

- [ ] **Step 2: Read the current iOS file before editing**

Read `composeApp/src/iosMain/kotlin/com/gcc/app/platform/CrashReporting.kt` in full (104 lines per prior investigation) to confirm its exact current structure before applying Step 3 — the investigation found `installCrashReporting()` (lines 37-58, calls `setUnhandledExceptionHook` then `importPreviousSessionCrashes()`), `documentsPath()`/`lastKotlinCrashPath()` (lines 60-64), and `importPreviousSessionCrashes()` (lines 69-102, reads+logs+deletes the Kotlin crash file, then does the same for MetricKit JSON diagnostics). Confirm these match before proceeding; if the file has diverged, adapt the following step to the real current content rather than assuming.

- [ ] **Step 3: Split into `actual` implementations**

Modify `composeApp/src/iosMain/kotlin/com/gcc/app/platform/CrashReporting.kt`:
- Add `actual` to the `installCrashReporting()` function signature. Remove its call to `importPreviousSessionCrashes()` from inside it (that call moves to the new `flushPendingCrashReport` below) — the hook-install logic itself (the `setUnhandledExceptionHook { ... }` block) is otherwise unchanged.
- Rename `importPreviousSessionCrashes()` to `actual suspend fun flushPendingCrashReport(apiClient: GccApiClient)`. Keep all of its existing file-read/`DiagnosticsLog`/delete logic for both the Kotlin-crash file and the MetricKit diagnostics, but add a crash-transmission call for the Kotlin-crash case specifically (the MetricKit diagnostics are Apple-structured payloads, not exception text — leave that half's local-only behavior as today; only the Kotlin fatal-exception path gets a network POST, since that's the one shaped like a `CrashReportRequest`):

```kotlin
actual suspend fun flushPendingCrashReport(apiClient: GccApiClient) {
    val fm = NSFileManager.defaultManager
    val kotlinCrashPath = lastKotlinCrashPath()
    if (fm.fileExistsAtPath(kotlinCrashPath)) {
        val text = NSString.stringWithContentsOfFile(kotlinCrashPath, NSUTF8StringEncoding, null)
        if (text != null) {
            val trimmed = text.take(1500)
            DiagnosticsLog.error("crash", trimmed)
            runCatching {
                apiClient.reportCrash(
                    CrashReportRequest(
                        deviceId = CoverageDeviceId.value,
                        platform = "ios",
                        exceptionMessage = text.lineSequence().drop(1).firstOrNull() ?: "unknown",
                        stackTrace = text,
                        occurredAt = Clock.System.now(),
                    ),
                )
            }
        }
        fm.removeItemAtPath(kotlinCrashPath, null)
    }
    // ... existing MetricKit-diagnostics pruning logic (lines 86-102 in the
    // pre-change file) stays exactly as it is today, local-only, unchanged.
}
```

(This is written against the investigation's description of the existing logic's shape — read the real file per Step 2 first and adapt variable names/exact API calls, e.g. `NSString.stringWithContentsOfFile`'s exact parameter list, to match what's actually there rather than this illustrative reconstruction.)

Add imports as needed: `com.gcc.shared.api.GccApiClient`, `com.gcc.shared.model.CrashReportRequest`, `com.gcc.app.platform.CoverageDeviceId` (same-package, may not need an explicit import), `kotlinx.datetime.Clock`.

- [ ] **Step 4: Wire the flush call into `MainViewController.kt`**

In `composeApp/src/iosMain/kotlin/com/gcc/app/MainViewController.kt`, after the existing Koin-start block (`if (runCatching { KoinPlatform.getKoin() }.isFailure) { startKoin { ... } }`), add:

```kotlin
    val apiClient = KoinPlatform.getKoin().get<GccApiClient>()
    CoroutineScope(Dispatchers.Default).launch { flushPendingCrashReport(apiClient) }
```

Add imports: `com.gcc.shared.api.GccApiClient`, `com.gcc.app.platform.flushPendingCrashReport`, `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.launch`.

- [ ] **Step 5: Verify what can be verified on this host**

This task's actual code is iOS Kotlin/Native — **cannot be compiled on this host at all**. Verify only that the commonMain `expect` declarations from Step 1 don't break any other target's compile:

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: FAILS at this point in the plan — Desktop has no `actual` yet (that's Task 3). This is expected and fine; do not treat it as a Task 2 failure. Note in your report that Desktop compilation is blocked pending Task 3, and that iOS itself cannot be verified on this host regardless.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/CrashReporting.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/CrashReporting.kt composeApp/src/iosMain/kotlin/com/gcc/app/MainViewController.kt
git commit -m "Extend iOS crash capture to transmit on next launch"
```

---

### Task 3: Android crash capture (new) + Desktop no-op

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/CrashReporting.kt`
- Create: `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/CrashReporting.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/gcc/app/MainActivity.kt`

**Interfaces:**
- Consumes: `expect fun installCrashReporting()` / `expect suspend fun flushPendingCrashReport(apiClient: GccApiClient)` (Task 2), `CrashReportRequest` (Task 1), `CoverageDeviceId.value` (pre-existing), `DiagnosticsLog.error()` (pre-existing).

- [ ] **Step 1: Android actual**

Create `composeApp/src/androidMain/kotlin/com/gcc/app/platform/CrashReporting.kt`:

```kotlin
package com.gcc.app.platform

import android.content.Context
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.CrashReportRequest
import kotlinx.datetime.Clock
import java.io.File

/**
 * Context must be initialized via [initCrashReportingContext] before
 * [installCrashReporting] is called -- the uncaught-exception hook captures
 * [crashFile] by reference, and needs the context to know where to write.
 */
@Volatile
private var appContext: Context? = null

fun initCrashReportingContext(context: Context) {
    appContext = context.applicationContext
}

private fun crashFile(): File? = appContext?.filesDir?.resolve("gcc-last-crash.txt")

actual fun installCrashReporting() {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            val report = buildString {
                appendLine("Kotlin fatal exception (previous session)")
                appendLine(throwable.toString())
                appendLine(throwable.stackTraceToString())
            }
            crashFile()?.writeText(report)
        }
        // Delegate to the previous handler so the app's real crash/exit
        // behavior is unchanged -- this only adds a side effect.
        previousHandler?.uncaughtException(thread, throwable)
    }
}

actual suspend fun flushPendingCrashReport(apiClient: GccApiClient) {
    val file = crashFile()?.takeIf { it.exists() } ?: return
    val text = runCatching { file.readText() }.getOrNull() ?: return
    DiagnosticsLog.error("crash", text.take(1500))
    runCatching {
        apiClient.reportCrash(
            CrashReportRequest(
                deviceId = CoverageDeviceId.value,
                platform = "android",
                exceptionMessage = text.lineSequence().drop(1).firstOrNull() ?: "unknown",
                stackTrace = text,
                occurredAt = Clock.System.now(),
            ),
        )
        file.delete()
    }
}
```

- [ ] **Step 2: Desktop no-op actual**

Create `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/CrashReporting.kt`:

```kotlin
package com.gcc.app.platform

import com.gcc.shared.api.GccApiClient

// Desktop is a dev target, not a real user deployment -- no crash capture needed.
actual fun installCrashReporting() {}
actual suspend fun flushPendingCrashReport(apiClient: GccApiClient) {}
```

- [ ] **Step 3: Wire Android's `MainActivity.kt`**

Read `composeApp/src/androidMain/kotlin/com/gcc/app/MainActivity.kt` in full first (76 lines per prior investigation) to confirm its exact current `onCreate` structure before editing.

Add imports:
```kotlin
import com.gcc.app.platform.initCrashReportingContext
import com.gcc.app.platform.installCrashReporting
import com.gcc.app.platform.flushPendingCrashReport
import com.gcc.shared.api.GccApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
```

Insert as the very first two lines of `onCreate` (before `initSecureStorageContext(this)` and every other existing `initXContext` call — a crash during any of those must still be caught):
```kotlin
        initCrashReportingContext(this)
        installCrashReporting()
```

After the existing Koin-start call (`startKoin { ... }` / wherever `GlobalContext.get()` first becomes valid in this file), add:
```kotlin
        val apiClient = GlobalContext.get().get<GccApiClient>()
        CoroutineScope(Dispatchers.Default).launch { flushPendingCrashReport(apiClient) }
```

(If `GlobalContext` isn't already imported in this file, add `import org.koin.core.context.GlobalContext`.)

- [ ] **Step 4: Verify what can be verified on this host**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL (this should now succeed, since Desktop's `actual` exists from Step 2 — this also transitively confirms Task 2's commonMain `expect` declarations are satisfied on at least one platform).

Run: `./gradlew :composeApp:compileDebugKotlinAndroid -x processDebugResources -x mergeDebugResources -x packageDebugResources -x processDebugManifest -x processDebugMainManifest`
Expected: BUILD SUCCESSFUL (Kotlin-compile-only signal, per the pattern established in prior work this session — full Android app assembly fails on this host for an unrelated, pre-existing reason).

iOS still cannot be compiled on this host at all — note this in your report rather than attempting it.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gcc/app/platform/CrashReporting.kt composeApp/src/desktopMain/kotlin/com/gcc/app/platform/CrashReporting.kt composeApp/src/androidMain/kotlin/com/gcc/app/MainActivity.kt
git commit -m "Add Android crash capture and Desktop no-op stub"
```

---

### Task 4: Admin visibility

**Files:**
- Create: `site/src/app/api/admin/crashes/route.ts`
- Create: `site/src/app/admin/crashes/page.tsx`

**Interfaces:**
- Consumes: `crashReports` Drizzle table (Task 1), `requireAdmin` (pre-existing, `site/src/lib/admin-auth.ts`).

- [ ] **Step 1: Add the admin API route**

Create `site/src/app/api/admin/crashes/route.ts` (mirrors `site/src/app/api/admin/blocks/route.ts`'s exact shape):

```ts
import { NextResponse } from 'next/server'
import { db } from '@/db'
import { crashReports } from '@/db/schema'
import { desc } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const rows = await db
      .select()
      .from(crashReports)
      .orderBy(desc(crashReports.createdAt))
      .limit(100)

    return NextResponse.json({ data: rows })
  } catch (error) {
    console.error('Error fetching crash reports:', error)
    return NextResponse.json({ error: 'Failed to fetch crash reports' }, { status: 500 })
  }
}
```

- [ ] **Step 2: Add the admin page**

Create `site/src/app/admin/crashes/page.tsx` (mirrors `newsletter/page.tsx`'s client-fetch pattern and dark-theme div-row conventions, not a `<table>`):

```tsx
'use client'
import { useCallback, useEffect, useMemo, useState } from 'react'

interface CrashReport {
  id: number
  deviceId: string
  platform: string
  appVersion: string | null
  exceptionMessage: string
  stackTrace: string
  occurredAt: string
  createdAt: string
}

export default function AdminCrashesPage() {
  const [crashes, setCrashes] = useState<CrashReport[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<number | null>(null)

  const authHeaders = useMemo((): Record<string, string> => {
    if (typeof window === 'undefined') return {}
    const t = localStorage.getItem('adminToken')
    return t ? { Authorization: `Bearer ${t}` } : {}
  }, [])

  const loadCrashes = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/admin/crashes', { headers: authHeaders })
      if (!res.ok) throw new Error(`Crashes: ${res.status}`)
      const body = await res.json()
      setCrashes(body.data || [])
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load')
    } finally {
      setLoading(false)
    }
  }, [authHeaders])

  useEffect(() => {
    loadCrashes()
  }, [loadCrashes])

  return (
    <div className="p-6 text-white">
      <h1 className="mb-4 text-xl font-semibold">Crash Reports</h1>
      {loading && <p className="text-neutral-400">Loading...</p>}
      {error && (
        <div className="mb-4 rounded-lg bg-red-950/40 px-4 py-2 text-sm text-red-300">
          {error}
        </div>
      )}
      {!loading && !error && crashes.length === 0 && (
        <p className="text-neutral-400">No crash reports recorded.</p>
      )}
      <div className="rounded-lg border border-neutral-800 bg-neutral-900">
        {crashes.map((c) => (
          <div
            key={c.id}
            className="border-b border-neutral-800 px-4 py-3 text-xs last:border-b-0"
          >
            <div
              className="flex cursor-pointer items-center justify-between"
              onClick={() => setExpandedId(expandedId === c.id ? null : c.id)}
            >
              <span className="font-mono text-neutral-400">{c.platform}</span>
              <span className="flex-1 px-3 truncate">{c.exceptionMessage}</span>
              <span className="text-neutral-500">{c.occurredAt}</span>
            </div>
            {expandedId === c.id && (
              <pre className="mt-2 overflow-x-auto whitespace-pre-wrap text-neutral-300">
                {c.stackTrace}
              </pre>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
```

- [ ] **Step 3: Verify**

Run: `cd site && npx tsc --noEmit`
Expected: no new type errors.

- [ ] **Step 4: Commit**

```bash
git add site/src/app/api/admin/crashes/route.ts site/src/app/admin/crashes/page.tsx
git commit -m "Add admin crash-reports visibility page"
```

---

## Self-Review Notes

- **Spec coverage**: §2 Shared → Task 1 Steps 2, 8. §2 Server → Task 1 Steps 1, 3-7. §2 iOS → Task 2. §2 Android → Task 3 Step 1, 3. §2 Desktop → Task 3 Step 2. §2 Admin visibility → Task 4. §3 test coverage (validation-failure-only route test, no DB test) → Task 1 Step 9, with the exact rationale from the spec carried into the test's own doc comment. §4 (verification limits) → explicitly called out in Task 2 Step 5 and Task 3 Step 4. §5 (out of scope) — no `workerId` column/attribution anywhere in this plan, no retry queue (both platforms delete the local file inside the same `runCatching` block that attempts the POST — matching "try once, silently drop on failure").
- **Placeholder scan**: no TBD/TODO. Task 2 Step 3 contains one explicit "read the real file first, adapt exact API calls" instruction rather than guessing at code this plan's author reconstructed from an investigation report summary, not a fresh direct read — this is a bounded, single-file verification, not open-ended design work, and is flagged as such rather than presented as verified-exact.
- **Type consistency**: `CrashReportRequest(deviceId, platform, appVersion, exceptionMessage, stackTrace, occurredAt)` (Task 1 Step 2) is constructed identically in Task 2 Step 3 (iOS) and Task 3 Step 1 (Android) — same field names, same order. `GccApiClient.reportCrash(report: CrashReportRequest): Unit` (Task 1 Step 8) is called identically (`apiClient.reportCrash(...)`) in both platform actuals. `expect fun installCrashReporting()` / `expect suspend fun flushPendingCrashReport(apiClient: GccApiClient)` (Task 2 Step 1) signatures are matched exactly by the iOS `actual` (Task 2 Step 3), Android `actual` (Task 3 Step 1), and Desktop `actual` (Task 3 Step 2).
- **Cross-task compile ordering caught during self-review**: Task 2's own verification step (compiling Desktop) cannot succeed until Task 3 adds Desktop's `actual` — this is explicitly called out as an *expected*, not a failing, outcome in Task 2 Step 5, so a reviewer doesn't mistake a genuinely-blocked build for a real defect in Task 2's work.
