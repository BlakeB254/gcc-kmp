# Coverage Event Queue Persistence — Design Spec

**Date:** 2026-07-04
**Status:** Awaiting review
**Scope:** Make `CoverageEventQueue` (`composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageEventQueue.kt`) survive a force-quit/crash mid-scan, so a worker's unflushed field-scan coverage data isn't silently lost.

---

## 1. Background

`CoverageEventQueue` buffers `ScanCoverageEventRequest`s locally and flushes them in batches to the coverage-ledger endpoint (every 30s + on session end, per `RouteScanViewModel`). Today `pending` is a plain in-memory `mutableListOf` — if the app is force-quit or killed by the OS before the next flush, any queued-but-unsent events are gone with no warning. For a field-work app, this is a real, silent data-loss risk, not a cosmetic gap.

## 2. Design

`CoverageEventQueue` gains a `Settings` parameter, following the exact precedent already established by `TokenManager` (`shared/.../auth/TokenManager.kt:20`, `Settings = Settings()` default) — no new dependency; `multiplatform-settings` is already used this way elsewhere in the codebase.

- Constructor: `class CoverageEventQueue(private val submitFn: ..., private val settings: Settings = Settings())`.
- On construction, `pending` is seeded from any previously-persisted value: `settings.getStringOrNull(KEY_PENDING_EVENTS)`, JSON-decoded via the existing shared `GccJson` instance (`shared/.../serialization/GccJson.kt`) into `List<ScanCoverageEventRequest>`. A missing key or a decode failure (corrupt data) both degrade to an empty list — never crash on startup because of bad persisted state.
- A private `persist()` function serializes the current `pending` list via `GccJson` and writes it to `settings` under `KEY_PENDING_EVENTS`, called after every mutation to `pending`: the append in `enqueue()`, and after each successful removal batch inside `flush()`'s loop.
- No change to the flush/retry/single-flight logic itself — only the durability of `pending` changes.

This makes a relaunch after a crash/force-quit pick up exactly where it left off: `RouteScanViewModel`'s existing `CoverageEventQueue(submitFn = ...)` call site needs **no changes** (the `settings` parameter defaults exactly like `TokenManager`'s does), and the next periodic flush attempt drains whatever survived.

## 3. Test isolation (a required side-effect of this change)

`CoverageEventQueueTest.kt`'s 5 existing tests construct `CoverageEventQueue(submitFn = fakeSubmit)` without a `settings` argument. Once the class actually reads/writes `Settings` state, that default resolves to a real platform-backed store on the JVM test target (`java.util.prefs.Preferences`, per `TokenManager`'s own doc comment) — meaning test runs would start reading and writing real system preferences on this machine, and could leak persisted state between separate test runs (a previous run's leftover `KEY_PENDING_EVENTS` value bleeding into a later run that never itself persisted anything).

Fix: update all 5 existing test cases to pass an explicit `MemorySettings()` (`shared/src/commonMain/kotlin/com/gcc/shared/offline/MemorySettings.kt` — a real, production `Settings` implementation backed by a plain `HashMap`, already used elsewhere for offline/preview modes, already accessible to `composeApp`'s test source set via the existing `shared` module dependency). A fresh instance per test gives full isolation with zero new test dependencies.

## 4. New test coverage

- **Persisted events survive a fresh instance** (simulates relaunch after force-quit): enqueue events on one `CoverageEventQueue`, construct a second one sharing the same `MemorySettings` instance, confirm the second instance's next `flush()` submits the same events without any new `enqueue()` calls.
- **A successful flush clears persisted state, not just the in-memory list**: enqueue, flush successfully, construct a fresh instance sharing the same `Settings`, confirm its `flush()` is a no-op (nothing left to submit) — proves the persisted copy was actually updated on removal, not just the in-memory one.
- **Corrupt persisted JSON degrades to an empty queue, not a crash**: manually `putString(KEY_PENDING_EVENTS, "not valid json")` on a `MemorySettings`, construct `CoverageEventQueue` against it, confirm construction succeeds and `flush()` is a clean no-op.

## 5. Out of scope

No change to `RouteScanViewModel`'s construction of `CoverageEventQueue` (default parameter covers it). No broader "offline-first" architecture (SQLDelight, full response caching) — that's flagged as separate, larger future work in the earlier foundational-tech audit, not part of this fix.
