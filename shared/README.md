# shared — Kotlin Multiplatform shared code

DTOs, the API client, geo math, and offline caches used by `composeApp` (and, in principle, the Ktor `server/`). Pure Kotlin, compiles on all mobile targets + JVM.

## Layout / key files
- `model/` — `@Serializable` API DTOs. **`GeoTypes.kt::Coordinates` is `(lng, lat)`** (`docs/CONVENTIONS.md` §1). DTOs must align with the columns the server exposes (schema-ownership policy, root `CLAUDE.md`).
- `api/GccApiClient.kt` — the single ktor client for the Next.js API. Note the token-free `siteClient` for presigned R2 PUTs (see `composeApp/README.md` gotchas) and `RawEncodedUrl.kt`.
- `geo/` — `GridCell.kt::gridCell10m` (**must byte-match** the server's `gridCell`, `docs/CONVENTIONS.md` §2), `PolygonContainment.kt` (`pointInPolygon` (lat,lng) vs `pointInGeoJsonRing` (lng,lat)), `CheckpointGenerator`.
- `cache/` — SQLDelight stores (`.sq` schemas + wrapper classes): route cache, scan-area cache, capture-chunk store, building-footprint store. One `GccDatabase`, one `SqlDriver` DI singleton.
- `serialization/GccJson.kt` — the shared Json (lenient Instant handling).

## Test
`./gradlew :shared:jvmTest` (this module uses a plain `jvm()` target). SQLDelight-driver tests live here (JVM `JdbcSqliteDriver`). Note: `shared` `commonTest` is broken by convention — put shared-code tests that need a driver in `shared/jvmTest`, and logic tests that `composeApp` consumes in `composeApp/commonTest`.
