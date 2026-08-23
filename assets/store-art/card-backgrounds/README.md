# GCC store preview card backgrounds — 2026-08-19

Six App Store preview backgrounds for the v1.0.9 6.5-inch set
(plan: `docs/plans/2026-08-19-ios-ux-onboarding-splash-preview-cards.md` §6.3).

These sit **behind** device-framed iOS screenshots. No text, logos, or people
are baked in. Headline lives in the quiet upper-center; the phone frame lives
in the calm center.

## Deliverables (1284 × 2778 PNG)

| File | Variant | Accent | Hex | Glow |
|---|---|---|---|---|
| `bg-01-hero.png` | hero-neutral | brand green | `#16763A` | quiet top-light |
| `bg-02-scan.png` | scan-green | bright scan green | `#22A852` | lower-left |
| `bg-03-route.png` | route-blue | GccBlue | `#155B92` | mid-right |
| `bg-04-bounty.png` | bounty-amber | StatusWarning | `#F59E0B` | lower-right |
| `bg-05-earn.png` | earn-gold | champagne gold | `#D4AF37` | upper corners |
| `bg-06-impact.png` | impact-teal | Emerald / completed | `#10B981` | bottom edge |

Canonical copies:
- `/home/codex450/cdx/dev-projects/gcc/assets/store-art/card-backgrounds/`
- `/home/codex450/cdx-platform/out/graphics/gcc-store-preview-card-bgs-2026-08-19/`

## System

One photographic plate, six accent grades. Grid/camera/lighting are identical.

1. **Art plate** — Grok Imagine (`grok-imagine-image`, xAI) portrait still, then
   quality edit (`grok-imagine-image-quality`) for micro-detail.
2. **Grade + glow** — deterministic PIL compositor
   (`compose_card_backgrounds.py`) cover-fits to 1284×2778, locks the field
   toward `#0B3D22 → #16763A`, and adds only the per-slide accent glow.

AuraSR 4× was attempted for print-sharp upscale; HF cache permissions + fetch
timeout blocked it. Final export is LANCZOS cover-fit + unsharp/contrast on the
quality plate (source 720×1280). Fine behind a device frame; re-upscale later
if a 6.7-inch / print pass is needed.

## Prompts used

### Master plate (text-to-image)

```
Premium App Store product-background still, vertical portrait, ultra-clean empty studio field. Deep forest-green gradient from near-black pine green #0B3D22 at the bottom to living civic green #16763A toward the top. Soft cinematic studio top-light, gentle volumetric depth haze, faint luxury matte plaster surface. Extremely subtle embossed city street-grid relief like an architectural floor plate, lines barely visible and dissolving into blur at the edges. Completely empty center and upper-center negative space for later overlay. Abstract material only. No text, no letters, no numbers, no logos, no watermarks, no people, no faces, no hands, no phones, no devices, no icons, no UI, no skyline, no cars, no buildings as objects. Photoreal, print-sharp, hopeful civic-tech luxury, quiet and premium.
```

Model: `grok-imagine-image` · aspect: portrait · 720×1280
Source URL: https://files-cdn.x.ai/Qp236dEAQ-GgOlaBDtUX_A/file_058c0c37-7d7d-4d86-9c4a-2d46d0f72cdd.png
Local: `source-plates/master-grok-v001.png`

### Detail pass (image-to-image on the master)

```
Keep this exact same image: same camera, same receding street-grid floor, same deep forest-green studio field, same empty upper space. Do not add objects, text, logos, people, or devices. Only increase micro-detail and print sharpness of the existing plaster-grid texture. No composition change.
```

Model: `grok-imagine-image-quality` · 720×1280
Source URL: https://files-cdn.x.ai/Ss2MRg--R4e3zfMXV-upXQ/file_9e4f3e1d-dfe4-41d6-a0d7-132e47d1fe11.png
Local: `source-plates/master-grok-v002-quality.png`

## Next

U6 compositing (device frames + Inter headlines) on the iOS/MacBook lane.
No publishing, scheduling, or App Store upload was performed.
