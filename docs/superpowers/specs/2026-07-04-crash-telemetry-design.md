# Crash Telemetry Visibility — Design Spec

**Date:** 2026-07-04
**Status:** Awaiting review
**Scope:** Get mobile crashes flowing to the backend and visible to the team. Not a third-party SDK integration (Sentry/Crashlytics) — those bundle native/NDK components this session's DGX host cannot build or verify, and require an external account. Instead: extend the existing (but purely local, non-transmitting) iOS crash capture, add the same capability to Android (which has none today), and wire both to a new backend endpoint + minimal admin page.

---

## 1. Background

No crash-reporting SDK exists anywhere in this repo (confirmed: no Sentry/Crashlytics/Bugsnag references in any `build.gradle.kts`, `libs.versions.toml`, `Info.plist`, or Kotlin/Swift source).

**iOS has real, but purely local, crash capture.** `composeApp/src/iosMain/kotlin/com/gcc/app/platform/CrashReporting.kt` hooks Kotlin/Native's `setUnhandledExceptionHook` and writes the exception text to `Documents/gcc-last-kotlin-crash.txt`. On the *next* launch, `importPreviousSessionCrashes()` reads that file, logs it into `DiagnosticsLog` (the in-app 🐞 diagnostics panel), and deletes it. Nothing is ever transmitted anywhere — this is field-debugging tooling (manual Files-app/USB retrieval), not telemetry. `iosApp/iosApp/CrashDiagnostics.swift` similarly persists Apple MetricKit diagnostics locally, also never transmitted.

**Android and Desktop have zero crash handling** — no uncaught-exception hook, no local persistence, nothing.

**No backend endpoint, table, or admin page** exists for crashes/errors of any kind today.

**Reusable precedent:** `CoverageEventQueue` → Postgres table → this is the right shape (client capture → POST → table), though crash reports are best-effort/one-shot on next launch, not a persistent retry queue — crashes are rare enough that "try once, log locally regardless" is sufficient, unlike the high-frequency coverage-event batching this pattern was built for.

## 2. Design

### Ordering constraint (why this can't be one function)

`installCrashReporting()`'s hook-installation must run *before* anything else — including Koin startup — so a crash during subsequent initialization is still caught. But actually *transmitting* a stored crash report needs `GccApiClient`, which only exists after Koin starts. Confirmed in `MainViewController.kt`: `installCrashReporting()` runs first, `startKoin { ... }` runs immediately after. `MainActivity.kt`'s `onCreate` has the same shape (a sequence of `initXContext(this)` calls, then Koin starts).

This splits crash handling into two distinct calls per platform:
1. **`installCrashReporting()`** — hook installation only. Runs first, before Koin.
2. **`flushPendingCrashReport(apiClient: GccApiClient)`** (suspend) — reads any locally-persisted crash from the *previous* session, POSTs it, logs it to `DiagnosticsLog`, deletes the local file. Runs after Koin starts (needs the DI-resolved `GccApiClient`).

### Shared (`shared` module)

New DTO, `shared/src/commonMain/kotlin/com/gcc/shared/model/CrashReport.kt`:
```kotlin
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

New `GccApiClient` method (mirrors the unauthenticated `loginWithApple`-style POST, but through `baseUrl` — mobile API host — via the Bearer-capable `httpClient`, since the Bearer plugin only *attaches* a token if one exists; it never requires one):
```kotlin
suspend fun reportCrash(report: CrashReportRequest) {
    postJson<Unit>("/api/crashes", report)
}
```

### Server (`server` module)

New table. **Per this repo's schema-ownership policy, the Drizzle definition in `site/src/db/schema.ts` is authoritative and must land in the same change as the Exposed mirror.**

`site/src/db/schema.ts` addition (mirrors `newsletterSends`'s style):
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

`server/src/main/kotlin/com/gcc/server/db/tables/CrashReportTables.kt` (new file, mirrors `FeedTables.kt`'s style):
```kotlin
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

No `workerId` column in this pass — attaching the logged-in worker to a crash needs optional (not required) auth extraction, which is real added complexity out of scope for a first pass. Out of scope, §5.

`CrashReportService` (mirrors `BlockService`'s directness — a plain class calling `dbQuery`, no interface, consistent with how this codebase treats equally-simple services; `BlockService`/`BlockRoutes` have no test coverage at this level either, and DB-integration tests are explicitly called out as future Testcontainers work in this repo's existing `AuthRoutesTest.kt`, not something to invent bespoke for one endpoint):
```kotlin
class CrashReportService {
    suspend fun record(request: CrashReportRequest): Unit = dbQuery {
        CrashReports.insert {
            it[deviceId] = request.deviceId
            it[platform] = request.platform
            it[appVersion] = request.appVersion
            it[exceptionMessage] = request.exceptionMessage
            it[stackTrace] = request.stackTrace
            it[occurredAt] = request.occurredAt
            it[createdAt] = kotlinx.datetime.Clock.System.now()
        }
    }
}
```
No `recent()`/listing method — admin visibility (§2, Admin visibility) reads `crashReports` directly via Drizzle from the separate Next.js app, never through the Ktor server, so a Ktor-side listing method would be unused code.

New route, public/unauthenticated (a crash can happen before login or with a broken session — this must never require a valid token):
```kotlin
fun Route.crashRoutes(crashReportService: CrashReportService) {
    post("/api/crashes") {
        val request = call.receive<CrashReportRequest>()
        crashReportService.record(request)
        call.respond(HttpStatusCode.OK, ApiResponse(data = Unit, success = true, message = null))
    }
}
```
Registered in `server/src/main/kotlin/com/gcc/server/routes/Routing.kt` at the top level (alongside `healthRoutes()`), not inside any `authenticate(...)` block.

### iOS (`composeApp/src/iosMain/.../CrashReporting.kt`)

Refactor the existing file into an `actual` implementation of the two commonMain `expect` functions below. `installCrashReporting()` keeps exactly its current hook-install logic (unchanged). `flushPendingCrashReport(apiClient)` becomes what `importPreviousSessionCrashes()` used to do, now also calling `apiClient.reportCrash(...)` before deleting the file (best-effort — log-and-continue on failure, since this runs during startup and must never block the app):
```kotlin
actual suspend fun flushPendingCrashReport(apiClient: GccApiClient) {
    // ... existing file-read logic ...
    if (text != null) {
        DiagnosticsLog.error("crash", text.take(1500))
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
        fm.removeItemAtPath(kotlinCrashPath, null)
    }
    // ... existing MetricKit-diagnostics pruning logic, unchanged ...
}
```
Reuses `CoverageDeviceId.value` (`composeApp/.../platform/CoverageDeviceId.kt`) — the existing stable per-install device identifier already used for scan-coverage events — rather than inventing a new device-identity mechanism.

`MainViewController.kt` wiring: `installCrashReporting()` call stays exactly where it is (before `startKoin`). Immediately after Koin starts, add:
```kotlin
val apiClient = KoinPlatform.getKoin().get<GccApiClient>()
CoroutineScope(Dispatchers.Default).launch { flushPendingCrashReport(apiClient) }
```

### Android (`composeApp/src/androidMain/.../CrashReporting.kt`, new file)

No existing crash handling to extend — this is new. Uses JVM's `Thread.setDefaultUncaughtExceptionHandler`, capturing and re-delegating to the previous handler so the app's actual crash/exit behavior is unchanged (only a side-effect of local persistence is added):
```kotlin
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
**Critical ordering requirement**: `initCrashReportingContext(this)` must be called as the very first line of `MainActivity.onCreate` — *before* `installCrashReporting()` itself — since the hook closure captures `crashFile()`, which reads the `appContext` holder at crash time. `installCrashReporting()` itself must also be the first *behavioral* call (before the other existing `initXContext` calls), matching iOS's "crash reporting first" comment, so a crash during any later init call is still caught. After Koin starts (`startKoin`/`GlobalContext.get()`), add the same flush call iOS uses:
```kotlin
val apiClient = GlobalContext.get().get<GccApiClient>()
CoroutineScope(Dispatchers.Default).launch { flushPendingCrashReport(apiClient) }
```

### Desktop (`composeApp/src/desktopMain/.../CrashReporting.kt`, new file)

No-op stubs — Desktop is a dev target, not a real deployment (consistent with the same decision made for `LocationTrackingController`'s Desktop actual in prior work this session):
```kotlin
actual fun installCrashReporting() {}
actual suspend fun flushPendingCrashReport(apiClient: GccApiClient) {}
```

### commonMain `expect` declarations (new file, `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CrashReporting.kt`)

```kotlin
expect fun installCrashReporting()
expect suspend fun flushPendingCrashReport(apiClient: GccApiClient)
```

### Admin visibility (`site/`)

`site/src/app/api/admin/crashes/route.ts` — `GET`, `requireAdmin(request)` (mirrors `site/src/lib/admin-auth.ts`'s existing pattern), queries `crashReports` via Drizzle ordered by `createdAt` descending, limit 100.

`site/src/app/admin/crashes/page.tsx` — client component mirroring `newsletter/page.tsx`'s exact shape: `fetch('/api/admin/crashes', { headers: { Authorization: \`Bearer ${localStorage.getItem('adminToken')}\` } })`, plain Tailwind table (device ID, platform, exception message, occurred-at, expandable stack trace). Auth is already enforced globally by the existing `site/src/app/admin/layout.tsx`.

## 3. Test coverage

- **`CrashReportService`/route**: no DB-integration test, matching this codebase's existing convention for equally-simple services (`BlockService`/`BlockRoutes` are untested at this level too; real DB-integration tests are explicitly deferred to future Testcontainers work per `AuthRoutesTest.kt`'s own comment). One lightweight route test *is* added: POSTing a body missing a required field (e.g. no `exceptionMessage`) must fail request parsing (`call.receive<CrashReportRequest>()` throws) before `CrashReportService.record()` is ever invoked — this is verifiable without a real database, since parse failure happens first.
- **iOS/Android platform code**: cannot be unit-tested in the traditional sense (real OS-level exception hooks, real file I/O) — verified by inspection and, where possible, compilation only (see §4).
- **Admin page**: `site/` runs natively on this host — `npx tsc --noEmit` and a real `npm run dev` + manual fetch check are both possible here, unlike the mobile-app platform code.

## 4. Verification limits

Same constraints as every mobile-platform task this session: **iOS code cannot be compiled or run on this host at all.** Android Kotlin compiles (`compileDebugKotlinAndroid`, excluding resource-processing tasks per the pattern established in the route-cache work) but full app assembly does not (pre-existing, unrelated aarch64/AAPT2 host limitation). Server (`:server:build`, `:server:test`) and `site/` (`npx tsc --noEmit`, `npm run build`) both fully build/test on this host.

## 5. Out of scope

- Third-party crash SDK (Sentry/Crashlytics/Bugsnag) — native/NDK components, external account, unverifiable on this host.
- Attaching the logged-in `workerId` to a crash report — needs optional (not required) auth extraction on the ingest route, real added complexity for a first pass.
- No explicit retry queue (unlike `CoverageEventQueue`) — but both platforms retry implicitly: the local crash file is deleted only after a successful transmission, so a failed send is naturally retried on the next app launch until it succeeds. This is a deliberately simple, bounded form of retry (once per launch, not a tight loop) rather than `CoverageEventQueue`'s persistent batching queue.
- Desktop crash reporting (no-op stubs only — not a real user deployment target).
- Alerting/paging on new crashes (the admin page is pull, not push) — a future enhancement if crash volume ever warrants it.
