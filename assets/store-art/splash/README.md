# GCC splash v2 — cinematic aerial drafts

Task: `t_98d25ee1`
Date: 2026-08-19
Lane: Grok Imagine (xAI) draft → ffmpeg HEVC 1080×2340
Entity: Global Community Cleaners (iOS splash / AVPlayer, tap-to-skip)
No publishing, no Postiz, no app bundle import.

## Recommended pick

**cD still-lock I2V** — real North Lawndale-style grid, single circular mark, green street scan, 10s push then hold.

- HEVC: `gcc_splash_v2_cD_stilllock_1080x2340.mp4` (1080×2340, 24fps, hvc1, silent, 6.1 MB)
- Hold poster: `gcc_splash_v2_recommended_hold_1080x2340.png` (last frame of cD)
- UILaunchScreen still: `gcc_splash_v2_poster_1080x2340.png` (Grok still, scaled)

## Candidates (all 10.04s / 241 frames / 24fps)

| ID | Direction | HEVC 1080×2340 | Size | Notes |
|---|---|---|---|---|
| cA | Geometric city-model grid | `gcc_splash_v2_cA_geometric_1080x2340.mp4` | 2.1 MB | Closest to “clean geometric blocks.” Logo arrives a bit early (~5s). Stylized 3D, not cartoon. |
| cB | Photoreal street drone | `gcc_splash_v2_cB_photoreal_1080x2340.mp4` | 4.2 MB | Strong blue-hour + scan outlines. **Reject for pick:** suburban not NL, **two stacked logos** at end. |
| cC | Editorial fog grid | `gcc_splash_v2_cC_editorial_1080x2340.mp4` | 2.5 MB | Premium atmosphere. Mid-clip scan became a 3D green ribbon, not a street wave. |
| cD | Still-lock I2V (recommended) | `gcc_splash_v2_cD_stilllock_1080x2340.mp4` | 6.1 MB | Best NL grid + single mark + street glow. Logo is a stand-in eco-hands globe, not the official GCC icon. |

Raw 720×1280 Grok masters kept as `*_raw.mp4` (do not ship those).

## Spec vs delivered

| Spec | Delivered |
|---|---|
| 1080×2340 portrait | Yes (cover-crop from 9:16 720×1280) |
| 24fps, 8–10s | 10.04s, 24fps, 241 frames |
| HEVC ≤8 MB | 2.1–6.1 MB, `hvc1`, no audio |
| No text / no people / no faces | Pass on all four |
| Circular mark, no wordmark | Pass except cB (duplicate marks) |
| Official GCC icon | **Not locked** — generated stand-in. Composite real icon in a v003 pass if Blake picks. |

## Prompts

### cA geometric (T2V, grok-imagine-video)

Cinematic vertical aerial over a stylized Chicago North Lawndale neighborhood grid at pre-dawn blue hour. Clean geometric city blocks as dark architectural volumes with warm amber window lights. Camera slowly pushes forward and slightly down over the grid for ten seconds, premium hopeful civic-tech mood, photoreal cinematic lighting, shallow atmospheric haze, no cartoon look.
0-3s: high aerial establish, deep indigo sky fading to teal, empty streets, quiet blue-hour glow, slow forward push.
3-8s: pulses of forest green light hex 16763A sweep the streets like a LiDAR scan wave, leaving blocks softly glowing after the wave passes, light travels block to block, no flicker.
8-10s: camera settles and holds; the green scan light converges to exact center frame and forms a subtle circular emblem: two green hands cupping a small blue Earth globe with tiny leaf shapes, no letters, no words, no typography.
No people, no faces, no cars with drivers, no text, no watermarks, no logos with words, no clutter, no flicker, no cartoon, no anime.

Public: https://files-cdn.x.ai/1d-kCnOQRM-EOTaBgS4PSg/file_3f4a700e-7a3c-4ff6-b831-07aacbf911fb.mp4

### cB photoreal (T2V, grok-imagine-video)

Photoreal cinematic drone shot over real Chicago West Side / North Lawndale rooftops at blue hour just before dawn. Modest two-flats, brick bungalows, vacant lots, church steeple far off, tree canopy, wet asphalt reflecting sky. Camera slowly pushes forward 10 seconds, locked aerial, premium hopeful, filmic, clean, no cartoon.
0-3s: high drone over a specific neighborhood grid, cool cobalt sky, warm kitchen windows, empty streets, quiet.
3-8s: a precise forest-green hex 16763A LiDAR scan pulse travels the alleys and streets like a mapping wave, briefly outlining block parcels then leaving a soft green afterglow on rooftops, one continuous sweep not strobing.
8-10s: camera eases to a still hold; green light gathers at center frame into a small circular icon of two cupped green hands holding a blue globe, no text, no letters.
No people, no faces, no readable signage, no watermarks.

Public: https://files-cdn.x.ai/IMLrkcEzRom9JaDJYLN3dQ/file_4a162f35-0dbc-414a-9c01-f828f92cbb79.mp4

### cC editorial (T2V, grok-imagine-video)

Editorial cinematic aerial, almost abstract city model of Chicago blocks at blue hour, premium civic-tech commercial look. Dark teal atmosphere, soft volumetric fog, clean geometric volumes, warm window grids, forest-green accent light. Slow majestic forward push, then settle.
0-2s: camera already moving, vast dark grid of neighborhood blocks receding to horizon, indigo-to-teal sky, hopeful quiet.
2-8s: a single elegant green scan ribbon hex 16763A travels toward camera then across the streets, igniting block after block with a soft living glow, like a cleanup map coming online.
8-10s: motion eases to a locked hold; all green light converges to a centered circular mark: cupped green hands around a blue Earth, leaf details, no typography, no letters, emblem only, then holds still for the last second.
No people, no faces, no text, no cartoon, no flicker, no clutter.

Public: https://files-cdn.x.ai/Rvum6UmhTHK57T7n-ZhwBQ/file_f50d6a9b-375b-4165-a6fe-de9042d4319b.mp4

### End-hold still (T2I, grok-imagine-image)

Vertical 9:16 cinematic still, final hold frame for an iOS splash. Aerial view of a stylized Chicago North Lawndale neighborhood grid at pre-dawn blue hour. Clean geometric blocks with warm amber windows, streets faintly glowing forest green #16763A after a scan wave. Centered in frame: a subtle circular emblem of two green hands cupping a small blue Earth globe with tiny leaves, no letters, no words, no typography. Camera settled, premium hopeful civic-tech, photoreal cinematic, soft haze, no people, no faces, no cartoon, no clutter, no watermark.

Public: https://files-cdn.x.ai/LxhOjMniQ7qcaAWrvKnF4Q/file_c6da8f19-b00e-4229-a1b9-f254ce459b40.png

### cD still-lock (I2V, grok-imagine-video-1.5)

Animate this exact aerial still. Keep the same North Lawndale neighborhood grid, same blue-hour lighting, same single centered circular emblem of two green hands cupping a blue globe. Camera slowly pushes forward for 8 seconds then settles for 2 seconds. Forest-green #16763A scan pulses travel along the streets leaving a soft glow. The emblem is already present and only becomes slightly more luminous and sharp in the final 2 seconds. Do not add a second logo. Do not add text, people, or new buildings. Photoreal cinematic, no cartoon, no flicker.

Public: https://files-cdn.x.ai/TTwDhJGnQ8y1mpoRNRe5kw/file_c68854f5-2eab-4196-9f4f-ba546f048743.mp4

## Next

1. Blake picks one candidate (recommend cD; cA if he wants more geometric/stylized).
2. Optional v003: composite official GCC icon (hands+globe, no wordmark) onto the hold; LTX extend/upscale if 720p crop is too soft on device.
3. iOS lane wires AVPlayer + this poster as UILaunchScreen. Reduced-motion uses the poster only.

Provenance copies: `/home/codex450/cdx-platform/out/_creative_ops/gcc-splash-v2/t_98d25ee1/`
