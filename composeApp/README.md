# composeApp — Compose Multiplatform mobile UI

The mobile app (Android, iOS, Desktop) — screens, view models, and the on-device scan/AR/coverage logic. iOS is the primary target; Desktop exists mainly to run `commonMain` logic tests fast. Talks to the **Next.js API** (`site/`), never the Ktor `server/`.

## Layout
- `src/commonMain` — shared UI + logic (most of the app): `ui/screens`, `ui/components`, `ui/map`, `viewmodel`, `platform` (device sensors, coverage, gate), `di` (Koin).
- `src/iosMain` — iOS `actual`s: AR surfaces (`ArEnvScan.kt`, `ArRouteOverlay.kt`), `CoverageRayFan.kt`, `GccMap.ios.kt`, camera/location.
- `src/androidMain`, `src/desktopMain` — platform `actual`s + fallbacks.
- `src/commonTest` (runs on desktop **and** iOS) and `src/desktopTest` (JVM-only, e.g. `JdbcSqliteDriver`).

## Patterns this module owns
- **Scan/coverage** — see `docs/governance/scan-and-coverage-model.md`. Core: `platform/CoverageTracker.kt`, `platform/OutdoorGate.kt`, `ui/components/ScanMinimapOverlay.kt`, `ui/map/{CoverageOutline,VisibilityBeam,GeoJsonLayers}.kt`.
- **AR native bridge** — `platform/ArNativeSurface.kt` (`expect`) ↔ `iosApp/iosApp/GccArNative.swift` (`actual`). Kotlin keeps policy; Swift renders.
- **MapLibre overlay zoom bias** — `ui/map/MapStyles.kt::mapOverlayZoomBias` (`docs/CONVENTIONS.md` §5).
- **DI** — Koin (`di/AppModule.kt`, `di/OfflineAppModule.kt`).
- **Offline caches** — SQLDelight stores in `shared/.../cache` (route, scan-area, capture-chunk, building-footprint) + persistent retry queues.

## Build / test
- Fast logic tests: `./gradlew :composeApp:desktopTest` (JVM target is `jvm("desktop")` — the task is **`desktopTest`**, not `jvmTest`).
- iOS compile: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`.
- iOS app build/upload happens in `iosApp/` via fastlane (see that README) on the MacBook.

## Gotchas (hard-won)
- **`@Synchronized` / `@Volatile` are JVM-only** — unavailable in `commonMain`/Kotlin-Native. `desktopTest` passes, `compileKotlinIosSimulatorArm64` catches it. Use single-writer reference-swap instead.
- **`commonTest` must compile for iOS too** — JVM-only tests (SQLite driver) go in `src/desktopTest`, not `commonTest`.
- **Always `rm -rf composeApp/build/xcode-frameworks` before an iOS xcodebuild after Kotlin edits** — a stale framework makes fixes appear on sim/main but not in the built app.
- **maplibre-compose 0.12: `camera.animateTo(duration = 0ms)` does NOT move the camera** — assign `camera.position =` for instant moves.
- **R2 presigned uploads must use the token-free `siteClient`, not `httpClient`** — `GccApiClient`'s BearerAuth stamps the worker JWT on every request; an `Authorization` header alongside query-string SigV4 makes R2 answer `400 InvalidRequest`. Also `URLBuilder.takeFromRawEncoded` to stop ktor re-encoding `%2F` in the SigV4 query.
- **`Coordinates` is `(lng, lat)`** — always named args (`docs/CONVENTIONS.md` §1).
