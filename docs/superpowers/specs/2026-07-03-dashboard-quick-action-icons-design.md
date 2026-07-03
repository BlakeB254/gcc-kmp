# Dashboard Quick-Action Icons — Design Spec

**Date:** 2026-07-03
**Status:** Awaiting review
**Scope:** Replace the 5 hand-drawn Canvas quick-action icons on the dashboard (and the map's spot-report FABs, which reuse one of them) with Nano Banana–generated flat glyphs, runtime-tinted to match each button's accent color.

---

## 1. Goal

The dashboard's "Quick Actions" row (Scan Spot, Bounties, Wallet, Rankings, Community) and the map's spot-report FAB currently render bespoke Compose `Canvas` vector art (`QuickActionIcons.kt`, added 2026-07-02). At the actual render size (~30dp inside a 55dp circle) these read as low-quality, don't sit well within the circular button, and are inconsistent in scale (30dp vs 32dp) and visual weight (the scan icon is a detailed two-tone scene; the other four are simpler single-shape glyphs). Replace them with a set of professionally generated, single-color flat glyphs that stay legible at small size and fit the circle deliberately.

## 2. Decisions (locked with user)

| Decision | Choice |
|---|---|
| Generation tool | Nano Banana Pro (MCP `nanobanana`) |
| Color model | Flat single-color glyphs on transparent background, tinted at runtime via `ColorFilter.tint(accentColor)` — same per-button accent-color system as today, not baked-in color |
| Icon concepts | Nano Banana proposes fresh concepts per action (given label + semantic hint), not a literal translation of today's shapes; user picks a favorite per icon from generated variants |
| Scan icon concept | Neutral device + scan-beam motif, no specific object (no tree, no litter item) — see §7 |
| Map FAB scope | In scope — update alongside the dashboard icon for the same reason they were unified in the first place (commit `dd45fde`) |
| Asset format | Transparent-background PNG via `composeApp/src/commonMain/composeResources/drawable/`, not vectorized |

## 3. Background (from codebase)

- **UI location:** `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/DashboardScreen.kt` — quick actions row at lines 339–394, `QuickActionButton` composable at lines 505–557.
- **Current icons:** `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/QuickActionIcons.kt` — `EnvironmentScanIcon`, `BountyTargetIcon`, `WalletCardIcon`, `RankingsPodiumIcon`, `CommunityNetworkIcon`, each a `@Composable` drawing raw `Canvas` primitives, taking accent color(s) as parameters.
- **Icon system elsewhere:** every other icon surface in the app (bottom nav, admin stat cards, wallet/feed screens) uses stock `compose.materialIconsExtended` glyphs, not custom art. The dashboard quick actions are the one place with bespoke hand-drawn icons.
- **No existing asset pipeline:** the only file under `composeApp/src/commonMain/composeResources/drawable/` today is `gcc_logo.png`. No `.xcassets`, no Android `drawable/`, no SVG import step exists yet — this spec introduces the first icon-asset pipeline in the app.
- **Map FAB reuse:** `EnvironmentScanIcon` is also drawn on the map's spot-report FABs (commit `dd45fde`, "reused the scan icon on dashboard + map spot FABs for consistency").
- **Design tokens:** `shared/src/commonMain/kotlin/com/gcc/shared/theme/GccDesignTokens.kt` defines an icon-size scale (`iconSm=21`, `iconMd=34`, `iconLg=55`) that today's icons don't strictly follow (30–32dp Canvas draw area). New icons should land on one of these or a clearly-named new constant.
- **Accent colors used per action:** Scan = `GccBlue`, Bounties = `CardAccentSky`, Wallet = `CardAccentBlue`, Rankings = `CardAccentPurple`, Community = `GccGreen` (`ui/theme/Color.kt`).

## 4. Icon generation plan (Nano Banana)

**Shared style prompt** (applied to every icon in the batch, for family consistency):
- Bold, flat, single-color silhouette icon
- Transparent background, no shading/gradient/photographic texture, no text
- Generous internal padding so the shape reads clearly when masked into a circle (roughly 60% fill of the frame, centered)
- Simple enough to be recognizable at ~30px on-screen

**Per-icon semantic hints** (concepts, not literal specs — Nano Banana proposes the actual shape):
1. **Scan Spot** → a device actively scanning/observing an environment: phone or viewfinder shape with a scan beam or radiating detection lines. Deliberately avoid depicting a specific scanned object (no tree, no litter item) — the live on-device scan is scoped to litter detection today (see `project_gcc-scan-scope-litter-vs-infrastructure` memory), but the product's terminology is moving toward broader "environment scan" framing, so the icon should read as generic scanning, not commit to either framing.
2. **Bounties** → claimable reward / targeted task (e.g., target, flag, or prize marker).
3. **Wallet** → earnings / payout (e.g., card, wallet, coin).
4. **Rankings** → competitive standing (e.g., podium, trophy, leaderboard bars).
5. **Community** → people connected (e.g., network of members, group).

**Process:** generate 2-3 variants per icon in a style-consistent batch (10-15 images total). Review as a set for family consistency (stroke weight, fill density, silhouette complexity). Present to user; user picks one variant per icon, mixing across variants if needed.

## 5. Asset pipeline

- Export the chosen variant per icon as a transparent-background PNG at high resolution (≥512×512 — the target render size is ~30dp, so this master resolution has ample headroom for @3x density).
- If the generated alpha channel isn't clean (soft edges, faint background residue), run a quick cleanup pass (threshold/despeckle) before committing.
- Save under `composeApp/src/commonMain/composeResources/drawable/` with clear names: `ic_quick_scan.png`, `ic_quick_bounty.png`, `ic_quick_wallet.png`, `ic_quick_rankings.png`, `ic_quick_community.png`.
- `composeResources` codegen will produce `Res.drawable.ic_quick_*` references on next build.

## 6. Compose integration

Replace the 5 bespoke composables in `QuickActionIcons.kt` with one reusable glyph composable:

```kotlin
@Composable
fun QuickActionGlyph(
    resource: DrawableResource,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(quickActionIconSize),
    )
}
```

`DashboardScreen.kt`'s 5 quick-action call sites change from e.g. `icon = { WalletCardIcon(color = CardAccentBlue) }` to `icon = { QuickActionGlyph(Res.drawable.ic_quick_wallet, CardAccentBlue) }`. The map FAB's `EnvironmentScanIcon(...)` call is updated the same way, using `ic_quick_scan`.

Once all call sites are migrated, delete `QuickActionIcons.kt`. Confirm nothing else references the old composables first (`grep -r "EnvironmentScanIcon\|BountyTargetIcon\|WalletCardIcon\|RankingsPodiumIcon\|CommunityNetworkIcon"`).

The dead `emoji` fallback parameter on `QuickActionButton` (never rendered today since `icon` is always supplied — `DashboardScreen.kt:536-537`) is out of scope to remove here; flagged as a follow-up cleanup since it's unrelated to icon quality.

## 7. Circle-fit sizing

Define one explicit size constant instead of today's inconsistent 30dp/32dp mix. Reuse the existing `GccDesignTokens.GccDimensions.iconMd` (34dp, ~62% of the 55dp circle) rather than introduce a new constant — it's already close to the target ratio and keeps the icon on the app's established size scale. Apply it uniformly via `QuickActionGlyph`'s `modifier.size(...)`.

## 8. Out of scope

- Renaming "Scan Spot" / "Spot Report" UI copy, route names, or click-handler names (`onSpotClick`) to "Environment Scan" terminology — mentioned as a likely future direction but not a locked decision; this spec only ensures the new icon's *visual concept* doesn't foreclose that shift.
- Removing the dead `emoji` fallback on `QuickActionButton`.
- Vectorizing the icons into true `ImageVector`/XML paths.
- Any change to the underlying on-device scan detection scope (litter-only remains unchanged).
- Icon work on any other screen (admin stat cards, bottom nav, etc.) — those already use Material Icons and are out of scope.

## 9. Risks / verification

- **Generation quality risk:** Nano Banana may resist producing a truly flat, single-color glyph (it's an image model, not an icon-vector tool) — first-pass output might carry shading/gradient. Mitigate with explicit corrective re-prompting ("flatten to solid silhouette, remove all shading/gradient, pure single color on transparent background") and a manual cleanup fallback if needed.
- **Alpha-channel cleanliness:** verify each exported PNG's transparency is clean before committing — a muddy alpha channel will show as a faint box/halo when tinted.
- **Circle fit regression:** after wiring in `QuickActionGlyph`, visually verify all 5 dashboard icons and the map FABs at real device size (not just a build check) — build the app and take a screenshot of the dashboard and the map, confirm icons are centered, appropriately padded, and legible against each accent color.
- **Build verification:** `composeResources` codegen must pick up the new drawables (may require a clean build); Gradle desktop compile (`:composeApp` desktop target) at minimum, Android/iOS if simulators/emulators are available in this environment.
