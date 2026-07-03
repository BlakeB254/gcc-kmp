# Dashboard Quick-Action Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 5 hand-drawn Canvas dashboard quick-action icons (plus their 2 FAB reuses) with Nano Banana–generated flat glyphs that are legible at small size, fit deliberately inside their circular buttons, and stay tintable to each button's accent color at runtime.

**Architecture:** Generate glyph variants with Nano Banana on a chroma-key background → post-process locally to a clean alpha-mask PNG, autocropped and centered on a fixed-ratio square canvas → land as `composeResources` drawables → swap the 5 bespoke `Canvas` composables for one reusable `QuickActionGlyph(resource, tint)` composable → repoint all 3 call sites (2 in `DashboardScreen.kt`, 1 in `MapFullScreen.kt`).

**Tech Stack:** Kotlin Multiplatform / Compose Multiplatform (`composeApp`), `org.jetbrains.compose.resources` (`composeResources`), Nano Banana Pro MCP (`mcp__nanobanana__generate_image`), Python 3 + Pillow + numpy for local post-processing (job-scoped tooling, not committed to the repo).

## Global Constraints

- Icons are flat, single-color silhouettes on a transparent background — color comes from `ColorFilter.tint(accentColor)` at the call site, never baked into the asset (spec §2, §4).
- Assets are PNG files under `composeApp/src/commonMain/composeResources/drawable/`, not vectorized (spec §2, §5).
- Icon render size is `GccTheme.dimensions.iconMd` (34dp, from `com.gcc.app.ui.theme.GccTheme`) unless a call site has an existing explicit override (spec §7).
- The Scan icon's concept is a neutral device + scan-beam motif — no tree, no litter item, no specific scanned object (spec §2, §4 point 1).
- Map FAB reuse is in scope: `MapFullScreen.kt`'s `SpotCreateButton` (and `DashboardScreen.kt`'s own FAB) must move to the same new scan glyph (spec §2, §8).
- Out of scope: renaming "Scan Spot"/"Spot Report" UI copy or handler names, removing the dead `emoji` fallback on `QuickActionButton`, vectorizing the assets (spec §8).

---

### Task 1: Generate raw icon variants with Nano Banana

**Files:** none in the repo — outputs land under `/home/codex450/.claude/jobs/96ab7a5b/tmp/icon-variants/` (job-scoped, not committed).

**Interfaces:**
- Produces: 10 PNG files (2 variants × 5 icons) at `/home/codex450/.claude/jobs/96ab7a5b/tmp/icon-variants/{scan,bounty,wallet,rankings,community}_v{1,2}.png`, each a flat black glyph on a solid magenta (`#FF00FF`) background — consumed by Task 2.

- [ ] **Step 1: Call `mcp__nanobanana__generate_image` for each of the 5 icons, 2 variants each (10 calls total)**

Shared style suffix, appended to every call's `prompt`:

```
Icon style: bold flat single-color icon design, solid pure black (#000000) shape
only, absolutely no gradients, no shading, no outline-only strokes without fill,
no drop shadows, no anti-aliased soft glow, no text or letters anywhere in the
image. Background: solid flat pure magenta (#FF00FF), completely uniform, no
texture, no vignette. The icon shape should be centered and fill about 65-70%
of the frame with even padding on all sides so it reads clearly when later
masked into a small circular button (~30px on screen). Square 1:1 composition.
```

Per-icon subject (prepended to the shared suffix above) and exact call parameters:

| Icon | Subject prompt | outputPath (both variants, `_v1`/`_v2`) |
|---|---|---|
| scan | "A simple icon glyph of a smartphone/handheld device emitting a radiating scan beam or detection waves outward — communicates 'actively scanning the surrounding environment'. Do not include any tree, plant, or specific scanned object — keep the scanned subject abstract, implied only by the beam." | `/home/codex450/.claude/jobs/96ab7a5b/tmp/icon-variants/scan_v1.png`, `..._v2.png` |
| bounty | "A simple icon glyph combining a target/crosshair with a small flag or star at the center, communicating a claimable reward task." | `.../icon-variants/bounty_v1.png`, `..._v2.png` |
| wallet | "A simple icon glyph of a wallet or stacked payment card with a coin, communicating earnings/payout." | `.../icon-variants/wallet_v1.png`, `..._v2.png` |
| rankings | "A simple icon glyph of a 3-step podium with a star or trophy above the tallest column, communicating competitive standing/leaderboard." | `.../icon-variants/rankings_v1.png`, `..._v2.png` |
| community | "A simple icon glyph of three connected person/member nodes forming a small network, communicating community/connection." | `.../icon-variants/community_v1.png`, `..._v2.png` |

For every call use: `aspectRatio: "1:1"`, `imageSize: "2K"`. Vary only the `prompt` wording slightly between `_v1` and `_v2` of the same icon (e.g. append "alternate composition" to `_v2`'s subject prompt) so the two variants are genuinely different, not identical re-renders.

- [ ] **Step 2: Verify all 10 files were written**

Run: `ls -la /home/codex450/.claude/jobs/96ab7a5b/tmp/icon-variants/`
Expected: 10 `.png` files, one per `{scan,bounty,wallet,rankings,community}_v{1,2}.png`, each with nonzero size.

- [ ] **Step 3: Commit**

Nothing to commit — Task 1 only produces job-scoped tmp files, not repo changes.

---

### Task 2: Post-process variants into clean alpha-mask PNGs

**Files:**
- Create: `/home/codex450/.claude/jobs/96ab7a5b/tmp/process_icon.py` (job-scoped tool, not committed)

**Interfaces:**
- Consumes: the 10 raw PNGs from Task 1 at `/home/codex450/.claude/jobs/96ab7a5b/tmp/icon-variants/*.png`.
- Produces: 10 processed RGBA PNGs at `/home/codex450/.claude/jobs/96ab7a5b/tmp/icon-variants-processed/*.png` (same filenames), each 1024×1024 with a clean alpha channel (magenta background removed, content autocropped and centered at ~65% fill) — consumed by Task 3 and Task 4.

- [ ] **Step 1: Write the post-processing script**

```python
#!/usr/bin/env python3
"""Chroma-key a magenta background to alpha, autocrop to content, and pad to
a square canvas, so a raster icon glyph can be tinted at runtime via
ColorFilter.tint (which only reads the alpha channel under BlendMode.SrcIn —
source RGB is discarded, so only the alpha shape has to be clean)."""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

CHROMA = np.array([255, 0, 255])  # magenta background requested in the generation prompt
THRESHOLD = 60                     # max per-channel L1 distance from CHROMA to treat as background
TARGET_SIZE = 1024                 # output canvas size (square)
FILL_RATIO = 0.65                  # icon content occupies this fraction of the canvas


def chroma_key_to_alpha(img: Image.Image) -> Image.Image:
    arr = np.array(img.convert("RGBA"))
    rgb = arr[:, :, :3].astype(np.int16)
    dist = np.abs(rgb - CHROMA).sum(axis=2)
    arr[:, :, 3] = np.where(dist < THRESHOLD, 0, 255)
    return Image.fromarray(arr, "RGBA")


def autocrop_to_content(img: Image.Image) -> Image.Image:
    bbox = img.getbbox()
    if bbox is None:
        raise ValueError("Image has no opaque content after chroma-keying")
    return img.crop(bbox)


def pad_to_square(img: Image.Image, target_size: int, fill_ratio: float) -> Image.Image:
    content_size = int(target_size * fill_ratio)
    img = img.copy()
    img.thumbnail((content_size, content_size), Image.LANCZOS)
    canvas = Image.new("RGBA", (target_size, target_size), (0, 0, 0, 0))
    offset = ((target_size - img.width) // 2, (target_size - img.height) // 2)
    canvas.paste(img, offset, img)
    return canvas


def process(input_path: Path, output_path: Path) -> None:
    img = Image.open(input_path)
    img = chroma_key_to_alpha(img)
    img = autocrop_to_content(img)
    img = pad_to_square(img, TARGET_SIZE, FILL_RATIO)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    img.save(output_path, "PNG")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: process_icon.py <input.png> <output.png>")
        sys.exit(1)
    process(Path(sys.argv[1]), Path(sys.argv[2]))
```

- [ ] **Step 2: Run it on all 10 variants**

Run:
```bash
cd /home/codex450/.claude/jobs/96ab7a5b/tmp
for f in icon-variants/*.png; do
  python3 process_icon.py "$f" "icon-variants-processed/$(basename "$f")"
done
```
Expected: no exceptions; `icon-variants-processed/` contains the same 10 filenames.

- [ ] **Step 3: Verify one output has a clean, non-trivial alpha shape**

Run: `python3 -c "from PIL import Image; im = Image.open('icon-variants-processed/scan_v1.png'); print(im.mode, im.size, im.getbbox())"`
Expected: `RGBA (1024, 1024) (x0, y0, x1, y1)` where the bbox is roughly centered and smaller than the full canvas (confirms autocrop + pad worked, not a blank or full-bleed image).

- [ ] **Step 4: Commit**

Nothing to commit — job-scoped tmp files only.

---

### Task 3: Build a review gallery and get the user's variant picks (human checkpoint)

**Files:**
- Create: `/home/codex450/.claude/jobs/96ab7a5b/tmp/build_gallery.py` (job-scoped tool, not committed)
- Create: `/home/codex450/.claude/jobs/96ab7a5b/tmp/review-gallery.html` (rendered via the `Artifact` tool, not committed to the repo)

**Interfaces:**
- Consumes: the 10 processed PNGs from Task 2.
- Produces: a user decision — one chosen filename per icon (5 total) — consumed by Task 4. **This task cannot be marked done by a passing check; it requires an explicit user selection for all 5 icons before Task 4 starts.**

- [ ] **Step 1: Write the gallery-builder script**

```python
#!/usr/bin/env python3
"""Build a self-contained HTML gallery of processed icon variants. Each card
shows the raw glyph plus a CSS mask-image swatch tinted to the icon's real
in-app accent color, so the review matches what ColorFilter.tint will
actually render on-device."""
import base64
from pathlib import Path

ICONS = [
    ("scan", "Scan Spot", "#155B92", ["scan_v1.png", "scan_v2.png"]),
    ("bounty", "Bounties", "#30A0CE", ["bounty_v1.png", "bounty_v2.png"]),
    ("wallet", "Wallet", "#155B92", ["wallet_v1.png", "wallet_v2.png"]),
    ("rankings", "Rankings", "#8B5CF6", ["rankings_v1.png", "rankings_v2.png"]),
    ("community", "Community", "#16763A", ["community_v1.png", "community_v2.png"]),
]

SRC_DIR = Path("icon-variants-processed")
OUT_PATH = Path("review-gallery.html")


def data_uri(path: Path) -> str:
    b64 = base64.b64encode(path.read_bytes()).decode("ascii")
    return f"data:image/png;base64,{b64}"


def render() -> str:
    sections = []
    for slug, label, color, files in ICONS:
        cards = []
        for name in files:
            uri = data_uri(SRC_DIR / name)
            cards.append(f"""
            <div class="card">
              <div class="swatch" style="background:{color}; -webkit-mask-image:url({uri}); mask-image:url({uri}); -webkit-mask-size:contain; mask-size:contain; -webkit-mask-repeat:no-repeat; mask-repeat:no-repeat; -webkit-mask-position:center; mask-position:center;"></div>
              <div class="filename">{name}</div>
              <img class="raw" src="{uri}" alt="{name} raw glyph" />
            </div>""")
        sections.append(f"""
        <section>
          <h2>{label}</h2>
          <div class="row">{''.join(cards)}</div>
        </section>""")

    return f"""<!doctype html>
<html><head><meta charset="utf-8"><title>Quick Action Icon Review</title>
<style>
  body {{ font-family: system-ui, sans-serif; background:#111; color:#eee; padding:24px; }}
  h2 {{ margin-top:40px; }}
  .row {{ display:flex; gap:24px; flex-wrap:wrap; }}
  .card {{ background:#1c1c1c; border-radius:12px; padding:16px; width:160px; text-align:center; }}
  .swatch {{ width:64px; height:64px; margin:0 auto 12px; border-radius:50%; background-color:#333; }}
  .raw {{ width:64px; height:64px; background:#fff; border-radius:8px; padding:4px; }}
  .filename {{ font-size:12px; color:#999; margin-bottom:8px; }}
</style></head>
<body>
  <h1>Quick Action Icon Review</h1>
  <p>Each card shows the tinted circle preview (top) and the raw processed glyph (bottom).</p>
  {''.join(sections)}
</body></html>"""


if __name__ == "__main__":
    OUT_PATH.write_text(render())
    print(f"wrote {OUT_PATH} ({OUT_PATH.stat().st_size} bytes)")
```

- [ ] **Step 2: Run it**

Run: `cd /home/codex450/.claude/jobs/96ab7a5b/tmp && python3 build_gallery.py`
Expected: `wrote review-gallery.html (N bytes)` with N > 0.

- [ ] **Step 3: Publish via the `Artifact` tool and get the user's picks**

Call `Artifact` with `file_path: /home/codex450/.claude/jobs/96ab7a5b/tmp/review-gallery.html`, `favicon: "🎨"`, `description: "Quick action icon variant review — pick a favorite per action"`. Share the resulting URL with the user, then ask (via `AskUserQuestion`, one question per icon, options `"Variant 1"` / `"Variant 2"`) which variant to use for each of Scan, Bounties, Wallet, Rankings, Community. Do not proceed to Task 4 until all 5 are answered.

- [ ] **Step 4: Commit**

Nothing to commit — job-scoped tmp files + an Artifact publish, no repo changes.

---

### Task 4: Land the 5 chosen assets into `composeApp` resources

**Files:**
- Create: `composeApp/src/commonMain/composeResources/drawable/ic_quick_scan.png`
- Create: `composeApp/src/commonMain/composeResources/drawable/ic_quick_bounty.png`
- Create: `composeApp/src/commonMain/composeResources/drawable/ic_quick_wallet.png`
- Create: `composeApp/src/commonMain/composeResources/drawable/ic_quick_rankings.png`
- Create: `composeApp/src/commonMain/composeResources/drawable/ic_quick_community.png`

**Interfaces:**
- Consumes: the user's 5 selected filenames from Task 3 (e.g. `scan_v2.png`), sourced from `/home/codex450/.claude/jobs/96ab7a5b/tmp/icon-variants-processed/`.
- Produces: `Res.drawable.ic_quick_scan`, `Res.drawable.ic_quick_bounty`, `Res.drawable.ic_quick_wallet`, `Res.drawable.ic_quick_rankings`, `Res.drawable.ic_quick_community` (generated by `composeResources` codegen on next Gradle build) — consumed by Task 5.

- [ ] **Step 1: Copy the chosen variant for each icon to its final name**

Run (substituting each icon's actual chosen filename from Task 3, e.g. if Scan's pick was `scan_v2.png`):
```bash
cd /home/codex450/cdx/dev-projects/gcc/.claude/worktrees/quick-action-icons
mkdir -p composeApp/src/commonMain/composeResources/drawable
cp /home/codex450/.claude/jobs/96ab7a5b/tmp/icon-variants-processed/scan_v2.png composeApp/src/commonMain/composeResources/drawable/ic_quick_scan.png
cp /home/codex450/.claude/jobs/96ab7a5b/tmp/icon-variants-processed/bounty_v1.png composeApp/src/commonMain/composeResources/drawable/ic_quick_bounty.png
cp /home/codex450/.claude/jobs/96ab7a5b/tmp/icon-variants-processed/wallet_v1.png composeApp/src/commonMain/composeResources/drawable/ic_quick_wallet.png
cp /home/codex450/.claude/jobs/96ab7a5b/tmp/icon-variants-processed/rankings_v1.png composeApp/src/commonMain/composeResources/drawable/ic_quick_rankings.png
cp /home/codex450/.claude/jobs/96ab7a5b/tmp/icon-variants-processed/community_v1.png composeApp/src/commonMain/composeResources/drawable/ic_quick_community.png
```
(The `cp` sources above show one example resolution, e.g. Scan → `scan_v2.png`. Before running, replace each source filename with the actual variant number the user selected for that icon in Task 3 — every icon must resolve to a real file that exists in `icon-variants-processed/`.)

- [ ] **Step 2: Verify the 5 files landed correctly**

Run: `file composeApp/src/commonMain/composeResources/drawable/ic_quick_*.png`
Expected: 5 lines, each reporting `PNG image data, 1024 x 1024, 8-bit/color RGBA, non-interlaced`.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/composeResources/drawable/ic_quick_scan.png \
        composeApp/src/commonMain/composeResources/drawable/ic_quick_bounty.png \
        composeApp/src/commonMain/composeResources/drawable/ic_quick_wallet.png \
        composeApp/src/commonMain/composeResources/drawable/ic_quick_rankings.png \
        composeApp/src/commonMain/composeResources/drawable/ic_quick_community.png
git commit -m "feat(app-ui): add generated quick-action icon glyph assets"
```

---

### Task 5: Add the `QuickActionGlyph` composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/QuickActionGlyph.kt`

`QuickActionIcons.kt` (the old hand-drawn composables) is deleted in Task 8, once Tasks 6 and 7 have migrated every caller — deleting it here would leave the tree non-compiling between commits.

**Interfaces:**
- Consumes: `Res.drawable.ic_quick_*` (Task 4), `GccTheme.dimensions.iconMd` (`com.gcc.app.ui.theme.GccTheme`, pre-existing).
- Produces: `@Composable fun QuickActionGlyph(resource: DrawableResource, tint: Color, modifier: Modifier = Modifier)` — consumed by Task 6 and Task 7.

- [ ] **Step 1: Create the new composable**

```kotlin
package com.gcc.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import com.gcc.app.ui.theme.GccTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Renders a flat, single-color icon glyph (transparent-background PNG,
 * generated art) tinted to the given accent color. Used for the dashboard
 * quick-action row and every FAB that reuses one of those glyphs, so the
 * color always tracks the button/surface it sits in rather than being baked
 * into the asset.
 */
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
        modifier = modifier.size(GccTheme.dimensions.iconMd),
    )
}
```

- [ ] **Step 2: Verify it compiles alongside the still-present old file**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: `BUILD SUCCESSFUL` — `QuickActionGlyph` and the old `QuickActionIcons.kt` composables coexist without conflict (different names, same package).

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/QuickActionGlyph.kt
git commit -m "feat(app-ui): add QuickActionGlyph composable"
```

---

### Task 6: Wire `DashboardScreen.kt`'s two call sites to `QuickActionGlyph`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/DashboardScreen.kt:192` (FAB usage inside `Scaffold`'s `floatingActionButton`)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/DashboardScreen.kt:363` (quick-action row usage)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/DashboardScreen.kt` (imports, top of file)

**Interfaces:**
- Consumes: `QuickActionGlyph` (Task 5), `Res.drawable.ic_quick_scan` / `ic_quick_bounty` / `ic_quick_wallet` / `ic_quick_rankings` / `ic_quick_community` (Task 4).

- [ ] **Step 1: Add the resource import**

`com.gcc.app.ui.components.*` and `com.gcc.app.ui.theme.*` are already imported (lines 27 and 35), so `QuickActionGlyph` and `GccTheme` need no new import. Add one import line after line 46 (`import com.gcc.app.util.padTwo`). Note this must be the **wildcard** import, not just `Res` — `Res.drawable.ic_quick_scan` etc. are Kotlin extension properties generated by `composeResources` codegen, and extension properties/functions must be individually in scope to resolve (this is the same reason `GccLogo.kt` imports both `Res` and `gcc_logo` by name); a `Res`-only import compiles but leaves every `Res.drawable.ic_quick_*` reference as an "Unresolved reference" error:

```kotlin
import gcc_kmp.composeapp.generated.resources.*
```

- [ ] **Step 2: Replace the FAB usage at line 192**

Before:
```kotlin
                EnvironmentScanIcon(
                    beamColor = Color.White,
                    natureColor = Color.White.copy(alpha = 0.92f),
                )
```
After:
```kotlin
                QuickActionGlyph(
                    resource = Res.drawable.ic_quick_scan,
                    tint = Color.White,
                )
```

- [ ] **Step 3: Replace the 5 quick-action row icons at lines 358-392**

Before:
```kotlin
                    QuickActionButton(
                        emoji = "📸",
                        label = "Scan\nSpot",
                        bgColor = GccBlue,
                        onClick = onSpotClick,
                        icon = { EnvironmentScanIcon(beamColor = GccBlue, natureColor = GccGreen) },
                    )
                    QuickActionButton(
                        emoji = "🏆",
                        label = "Bounties",
                        bgColor = CardAccentSky,
                        onClick = onBountyListClick,
                        icon = { BountyTargetIcon(color = CardAccentSky) },
                    )
                    QuickActionButton(
                        emoji = "💰",
                        label = "Wallet",
                        bgColor = CardAccentBlue,
                        onClick = onWalletClick,
                        icon = { WalletCardIcon(color = CardAccentBlue) },
                    )
                    QuickActionButton(
                        emoji = "🏅",
                        label = "Rankings",
                        bgColor = CardAccentPurple,
                        onClick = onLeaderboardClick,
                        icon = { RankingsPodiumIcon(color = CardAccentPurple) },
                    )
                    QuickActionButton(
                        emoji = "📰",
                        label = "Community",
                        bgColor = GccGreen,
                        onClick = onFeedClick,
                        icon = { CommunityNetworkIcon(color = GccGreen) },
                    )
```
After:
```kotlin
                    QuickActionButton(
                        emoji = "📸",
                        label = "Scan\nSpot",
                        bgColor = GccBlue,
                        onClick = onSpotClick,
                        icon = { QuickActionGlyph(Res.drawable.ic_quick_scan, GccBlue) },
                    )
                    QuickActionButton(
                        emoji = "🏆",
                        label = "Bounties",
                        bgColor = CardAccentSky,
                        onClick = onBountyListClick,
                        icon = { QuickActionGlyph(Res.drawable.ic_quick_bounty, CardAccentSky) },
                    )
                    QuickActionButton(
                        emoji = "💰",
                        label = "Wallet",
                        bgColor = CardAccentBlue,
                        onClick = onWalletClick,
                        icon = { QuickActionGlyph(Res.drawable.ic_quick_wallet, CardAccentBlue) },
                    )
                    QuickActionButton(
                        emoji = "🏅",
                        label = "Rankings",
                        bgColor = CardAccentPurple,
                        onClick = onLeaderboardClick,
                        icon = { QuickActionGlyph(Res.drawable.ic_quick_rankings, CardAccentPurple) },
                    )
                    QuickActionButton(
                        emoji = "📰",
                        label = "Community",
                        bgColor = GccGreen,
                        onClick = onFeedClick,
                        icon = { QuickActionGlyph(Res.drawable.ic_quick_community, GccGreen) },
                    )
```

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/DashboardScreen.kt
git commit -m "feat(app-ui): wire DashboardScreen quick actions to generated icon glyphs"
```

---

### Task 7: Wire `MapFullScreen.kt`'s spot-report FAB to `QuickActionGlyph`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt:662` (`SpotCreateButton`)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt` (imports, top of file)

**Interfaces:**
- Consumes: `QuickActionGlyph` (Task 5), `Res.drawable.ic_quick_scan` (Task 4).

- [ ] **Step 1: Add imports**

`MapFullScreen.kt` imports individual components rather than a wildcard (e.g. `import com.gcc.app.ui.components.GccBottomNavBar`), but the resource import must still be a wildcard — see Task 6 Step 1 for why (`Res`-only imports leave `Res.drawable.ic_quick_scan` as an unresolved reference; the specific extension property has to be in scope). Add two lines alongside the existing `com.gcc.app.ui.components.*` imports:

```kotlin
import com.gcc.app.ui.components.QuickActionGlyph
import gcc_kmp.composeapp.generated.resources.*
```

- [ ] **Step 2: Replace the call at line 662**

Before:
```kotlin
        com.gcc.app.ui.components.EnvironmentScanIcon(
            beamColor = Color.White,
            natureColor = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(26.dp),
        )
```
After:
```kotlin
        QuickActionGlyph(
            resource = Res.drawable.ic_quick_scan,
            tint = Color.White,
            modifier = Modifier.size(26.dp),
        )
```

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt
git commit -m "feat(app-ui): wire map spot-report FAB to the generated scan icon glyph"
```

---

### Task 8: Delete the old icon file, build verification, dead-reference sweep

**Files:**
- Delete: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/QuickActionIcons.kt`

- [ ] **Step 1: Confirm no caller still references the old composables**

Run: `grep -rn "EnvironmentScanIcon\|BountyTargetIcon\|WalletCardIcon\|RankingsPodiumIcon\|CommunityNetworkIcon" composeApp/src/commonMain --include=*.kt`
Expected: exactly 1 match — the definitions inside `QuickActionIcons.kt` itself (Tasks 6 and 7 already migrated the 3 call sites). If any match appears in a different file, stop and update that caller before deleting.

- [ ] **Step 2: Delete the old file**

Run: `git rm composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/QuickActionIcons.kt`
Expected: file removed from the working tree and staged for deletion.

- [ ] **Step 3: Compile the desktop target (exercises `commonMain` + `composeResources` codegen)**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: `BUILD SUCCESSFUL`. A failure here most likely means a missing/misnamed `Res.drawable.ic_quick_*` reference (drawable filename typo) or a leftover import — fix and re-run.

- [ ] **Step 4: Visual check**

Run the app per the repo's `run` skill (or `./gradlew :composeApp:run` for the desktop target if no emulator/simulator is available in this environment) and navigate to the dashboard. Confirm all 5 quick-action icons render centered and legible inside their circles, tinted to the correct accent color, and that the FAB on both the dashboard and the map uses the same new scan glyph in white. Take a screenshot if the harness supports it.

If `:composeApp:run` fails with `UnsatisfiedLinkError: ... OpenGLApi.glFlush()` (or similar Skiko/GL errors), the execution environment's display server lacks real OpenGL (common under a headless Xvfb without GPU passthrough) — this is an environment limitation, not a code defect. Treat Step 3's `BUILD SUCCESSFUL` as the verification ceiling in that case, and defer pixel-level visual confirmation to a real device/simulator build (Android emulator, iOS simulator, or a desktop machine with a real GPU).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore(app-ui): remove hand-drawn Canvas quick-action icons"
```
