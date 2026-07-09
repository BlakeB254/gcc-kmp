# server — Ktor JVM backend (NOT deployed)

⚠️ **This module is not in production and not in the Gradle build.** `include(":server")` was removed from `settings.gradle.kts` ("stop building undeployed :server in CI"), so `./gradlew :server:*` won't resolve until it's re-added. It also does not compile as-is (out of the build graph + DTO deletions).

The **mobile app talks to the Next.js API (`site/`), not this server** — see root `CLAUDE.md` and `docs/governance/ARCHITECTURE-and-source-of-truth.md`. Ktor is a parallel implementation kept for reference; several paths diverge from prod and its login 2FA-challenges every non-demo account without delivering a code.

## If you ever revive it
- Port endpoints TO `site/src/app/api/`, don't rely on this reaching prod.
- `Tables.kt` (split per-domain: `IdentityTables.kt`, `RouteTables.kt`, …) is a **hand-maintained Exposed mirror** of `site/src/db/schema.ts` — it does NOT create schema; `schema.ts` is authoritative.
- Requires `JWT_SECRET` at startup (must equal the Next.js one so tokens verify across both).
