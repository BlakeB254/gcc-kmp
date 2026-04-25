#!/usr/bin/env python3
"""
Compose App Store screenshots — GCC-branded background, iPhone 16 Pro Max
device frame, headline caption + subcopy.

Output spec
-----------
1320 × 2868 portrait (Apple's 6.9" iPhone 16 Pro Max App Store size).
Brand-green vertical gradient background, centered iPhone bezel with the
simulator screenshot inside, headline caption above, subcopy under it.

Usage
-----
    python3 scripts/make-appstore-screenshot.py \\
        --in screenshots/raw/<sim-screenshot>.png \\
        --headline "See every block." \\
        --subcopy "Live map of cleanings near you." \\
        --out screenshots/appstore-framed/01-heatmap.png

Or batch via the CONFIGS table at the bottom: `--all` runs every entry.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ── Output canvas ─────────────────────────────────────────────────────────
CANVAS_W = 1320
CANVAS_H = 2868

# ── Brand palette ─────────────────────────────────────────────────────────
BRAND_GREEN_500 = (22, 118, 58)     # #16763A — primary
BRAND_GREEN_700 = (14, 82, 38)      # #0E5226 — darker
BRAND_GREEN_900 = (10, 58, 28)      # #0A3A1C — darkest
ACCENT_AMBER = (255, 107, 53)       # #FF6B35 — heatmap accent
WHITE = (255, 255, 255)
TEXT_SUB = (255, 255, 255, 215)     # 84% white for subcopy

# ── Layout ────────────────────────────────────────────────────────────────
HEADLINE_TOP = 140
HEADLINE_SIZE = 116
SUBCOPY_GAP = 32
SUBCOPY_SIZE = 52
SUBCOPY_LINE_GAP = 12
HEADLINE_LINE_GAP = 14

# Device frame metrics — sized so a 1206×2622 sim screenshot maps 1:1
# with comfortable bezel margins on a 1320-wide canvas.
DEVICE_TOP = 600                    # raised so device clears the footer
DEVICE_W = 920                      # outer bezel width — slightly trimmed for breathing room
DEVICE_H = int(DEVICE_W * (2622 / 1206))  # preserve sim aspect (~2000)
BEZEL_PX = 16                       # bezel thickness on each side
BEZEL_RADIUS = 84                   # outer corner radius
SCREEN_RADIUS = BEZEL_RADIUS - BEZEL_PX
ISLAND_W = 220                      # tightened — closer to real 16-Pro pill
ISLAND_H = 64
ISLAND_TOP_OFFSET = 22

# Footer wordmark
FOOTER_TEXT = "globalcommunitycleaners.org"
FOOTER_SIZE = 36
FOOTER_BOTTOM = 90                  # nudged down so device shadow doesn't kiss it

# ── Fonts ─────────────────────────────────────────────────────────────────
FONT_BOLD_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
    "/Library/Fonts/Arial Bold.ttf",
]
FONT_REGULAR_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
]


def load_font(candidates: list[str], size: int) -> ImageFont.FreeTypeFont:
    for path in candidates:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def make_background(hero_photo: Path | None = None) -> Image.Image:
    """
    If `hero_photo` is provided, use it as a full-bleed photographic
    background with a brand-tinted darken overlay so the caption + device
    read clearly. Otherwise fall back to the brand-green gradient.

    The pattern matches what top-selling App Store apps (Headspace,
    Strava, Cash App) do: a real photo grounds the screenshot in the
    product's world, then a darken layer keeps the typography readable.
    """
    if hero_photo and hero_photo.exists():
        photo = Image.open(hero_photo).convert("RGB")
        # Cover-fit into 1320x2868: scale to whichever dim is smaller
        # of (CANVAS_W/photo_W, CANVAS_H/photo_H) — actually we want max
        # so the photo covers without letterbox.
        ph_w, ph_h = photo.size
        scale = max(CANVAS_W / ph_w, CANVAS_H / ph_h)
        new_w = int(ph_w * scale)
        new_h = int(ph_h * scale)
        photo = photo.resize((new_w, new_h), Image.LANCZOS)
        left = (new_w - CANVAS_W) // 2
        top = (new_h - CANVAS_H) // 2
        bg = photo.crop((left, top, left + CANVAS_W, top + CANVAS_H)).convert("RGBA")

        # Two-stop overlay so caption (top) and footer (bottom) stay legible:
        #   top 35% — dark at 65% alpha, fades to 25%
        #   middle — even mid-tone darken at 25%
        #   bottom 18% — dark at 50% alpha for footer
        overlay = Image.new("RGBA", (CANVAS_W, CANVAS_H), (0, 0, 0, 0))
        op = overlay.load()
        for y in range(CANVAS_H):
            t = y / (CANVAS_H - 1)
            if t < 0.35:
                # Top band: 65% → 25%
                k = t / 0.35
                a = int(165 - (165 - 64) * k)
            elif t < 0.82:
                # Middle: gentle darken
                a = 64
            else:
                # Bottom 18%: ramp 25% → 50% for footer legibility
                k = (t - 0.82) / 0.18
                a = int(64 + (128 - 64) * k)
            row = (BRAND_GREEN_900[0], BRAND_GREEN_900[1], BRAND_GREEN_900[2], a)
            for x in range(CANVAS_W):
                op[x, y] = row

        bg = Image.alpha_composite(bg, overlay).convert("RGB")
        return bg

    # ── Fallback: brand-green gradient (used when no hero photo) ──
    bg = Image.new("RGB", (CANVAS_W, CANVAS_H), BRAND_GREEN_700)
    pixels = bg.load()
    for y in range(CANVAS_H):
        t = y / (CANVAS_H - 1)
        if t < 0.5:
            k = t * 2
            r = BRAND_GREEN_900[0] + (BRAND_GREEN_500[0] - BRAND_GREEN_900[0]) * k
            g = BRAND_GREEN_900[1] + (BRAND_GREEN_500[1] - BRAND_GREEN_900[1]) * k
            b = BRAND_GREEN_900[2] + (BRAND_GREEN_500[2] - BRAND_GREEN_900[2]) * k
        else:
            k = (t - 0.5) * 2
            r = BRAND_GREEN_500[0] + (BRAND_GREEN_900[0] - BRAND_GREEN_500[0]) * k
            g = BRAND_GREEN_500[1] + (BRAND_GREEN_900[1] - BRAND_GREEN_500[1]) * k
            b = BRAND_GREEN_500[2] + (BRAND_GREEN_900[2] - BRAND_GREEN_500[2]) * k
        for x in range(CANVAS_W):
            pixels[x, y] = (int(r), int(g), int(b))
    return bg


def draw_wrapped_text(
    draw: ImageDraw.ImageDraw,
    text: str,
    top: int,
    font: ImageFont.FreeTypeFont,
    color,
    line_gap: int,
    max_width: int,
) -> int:
    """Word-wrap `text` at `max_width` px and draw centered on canvas. Returns new top."""
    words = text.split()
    lines: list[str] = []
    cur = ""
    for w in words:
        candidate = w if not cur else f"{cur} {w}"
        bbox = draw.textbbox((0, 0), candidate, font=font)
        if bbox[2] - bbox[0] <= max_width:
            cur = candidate
        else:
            if cur:
                lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)

    y = top
    for line in lines:
        bbox = draw.textbbox((0, 0), line, font=font)
        w = bbox[2] - bbox[0]
        x = (CANVAS_W - w) // 2
        draw.text((x, y), line, font=font, fill=color)
        y += (bbox[3] - bbox[1]) + line_gap
    return y


def build_device_layer(screenshot_path: Path) -> Image.Image:
    """
    Render the iPhone bezel + screen-content + dynamic island on a transparent
    canvas the same size as the output. Easier to apply a soft shadow once
    against the background than per-component.
    """
    layer = Image.new("RGBA", (CANVAS_W, CANVAS_H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    device_left = (CANVAS_W - DEVICE_W) // 2
    device_right = device_left + DEVICE_W
    device_bottom = DEVICE_TOP + DEVICE_H

    # Outer bezel: rounded rect, dark titanium tone
    draw.rounded_rectangle(
        [device_left, DEVICE_TOP, device_right, device_bottom],
        radius=BEZEL_RADIUS,
        fill=(20, 20, 22, 255),
    )

    # Inner screen mask
    screen_left = device_left + BEZEL_PX
    screen_top = DEVICE_TOP + BEZEL_PX
    screen_right = device_right - BEZEL_PX
    screen_bottom = device_bottom - BEZEL_PX
    screen_w = screen_right - screen_left
    screen_h = screen_bottom - screen_top

    # Build screen image via mask
    sim = Image.open(screenshot_path).convert("RGB")
    sim = sim.resize((screen_w, screen_h), Image.LANCZOS)
    screen_mask = Image.new("L", (screen_w, screen_h), 0)
    md = ImageDraw.Draw(screen_mask)
    md.rounded_rectangle(
        [0, 0, screen_w, screen_h],
        radius=SCREEN_RADIUS,
        fill=255,
    )
    layer.paste(sim, (screen_left, screen_top), screen_mask)

    # Dynamic Island — black pill, top-center of screen area
    island_x = screen_left + (screen_w - ISLAND_W) // 2
    island_y = screen_top + ISLAND_TOP_OFFSET
    draw.rounded_rectangle(
        [island_x, island_y, island_x + ISLAND_W, island_y + ISLAND_H],
        radius=ISLAND_H // 2,
        fill=(0, 0, 0, 255),
    )

    return layer


def apply_device_shadow(canvas: Image.Image, device_layer: Image.Image) -> Image.Image:
    """Cast a soft shadow under the device, then composite the device on top."""
    # Shadow = device alpha, blurred + darkened, offset down a bit
    shadow = Image.new("RGBA", (CANVAS_W, CANVAS_H), (0, 0, 0, 0))
    alpha = device_layer.split()[-1]
    shadow.putalpha(alpha.point(lambda p: int(p * 0.6)))
    shadow = shadow.filter(ImageFilter.GaussianBlur(40))
    shadow_offset = Image.new("RGBA", (CANVAS_W, CANVAS_H), (0, 0, 0, 0))
    shadow_offset.paste(shadow, (0, 30))

    out = Image.alpha_composite(canvas.convert("RGBA"), shadow_offset)
    out = Image.alpha_composite(out, device_layer)
    return out


def compose(
    screenshot_path: Path,
    headline: str,
    subcopy: str,
    out_path: Path,
    hero_photo: Path | None = None,
) -> None:
    canvas = make_background(hero_photo)
    canvas = canvas.convert("RGBA")
    draw = ImageDraw.Draw(canvas)

    # Headline
    head_font = load_font(FONT_BOLD_CANDIDATES, HEADLINE_SIZE)
    sub_font = load_font(FONT_REGULAR_CANDIDATES, SUBCOPY_SIZE)
    new_top = draw_wrapped_text(
        draw, headline, HEADLINE_TOP, head_font, WHITE,
        HEADLINE_LINE_GAP, max_width=CANVAS_W - 160,
    )
    if subcopy:
        draw_wrapped_text(
            draw, subcopy, new_top + SUBCOPY_GAP, sub_font, TEXT_SUB,
            SUBCOPY_LINE_GAP, max_width=CANVAS_W - 220,
        )

    # Device frame + screen
    device_layer = build_device_layer(screenshot_path)
    canvas = apply_device_shadow(canvas, device_layer)

    # Footer URL
    foot_font = load_font(FONT_REGULAR_CANDIDATES, FOOTER_SIZE)
    fd = ImageDraw.Draw(canvas)
    bbox = fd.textbbox((0, 0), FOOTER_TEXT, font=foot_font)
    w = bbox[2] - bbox[0]
    h = bbox[3] - bbox[1]
    fd.text(
        ((CANVAS_W - w) // 2, CANVAS_H - FOOTER_BOTTOM - h),
        FOOTER_TEXT, font=foot_font, fill=(255, 255, 255, 200),
    )

    canvas.convert("RGB").save(out_path, "PNG", optimize=True)
    print(f"  wrote {out_path}  ({canvas.size})")


# ── Batch configs — tweak captions + source pairings here ─────────────────
SCRIPT_DIR = Path(__file__).resolve().parent
REPO = SCRIPT_DIR.parent
RAW_DIR = REPO / "screenshots" / "raw"
OUT_DIR = REPO / "screenshots" / "appstore-framed"

SITE_HERO_DIR = REPO / "site" / "public" / "images" / "upsells"

CONFIGS = [
    {
        "src": "12-tap-map.png",
        "out": "01-heatmap.png",
        "headline": "See every block.",
        "subcopy": "Live heatmap of cleanings scheduled, in progress, and done.",
        # Worker behind sweeping in vest — the human face of the program
        "hero": SITE_HERO_DIR / "worker-branding-base.jpg",
    },
    {
        "src": "10-map-fixed.png",
        "out": "02-profile.png",
        "headline": "Earn $100 per block.",
        "subcopy": "Same-day Stripe payout. Streaks, badges, real money for real work.",
        # Equipment cart on the sidewalk — work in progress
        "hero": SITE_HERO_DIR / "equipment-branding-base.jpg",
    },
    {
        "src": "12-tap-map.png",
        "out": "03-routes.png",
        "headline": "Pick your block.",
        "subcopy": "Tap a route, claim the job, log your work, get paid.",
        # Yard sign on residential block — visible community marker
        "hero": SITE_HERO_DIR / "street-advertising-base.jpg",
    },
    {
        "src": "04-further.png",
        "out": "04-onboarding.png",
        "headline": "100% to your neighbor.",
        "subcopy": "Every $100 sponsored pays one resident to clean one block — line-itemized.",
        # Phone showing the social-share post — "your work, public"
        "hero": SITE_HERO_DIR / "digital-presence-base.jpg",
    },
    {
        "src": "12-tap-map.png",
        "out": "05-launch.png",
        "headline": "Built for North Lawndale.",
        "subcopy": "Expanding neighborhood by neighborhood — bring it to yours.",
        # Community event tent — community celebration
        "hero": SITE_HERO_DIR / "events-base.jpg",
    },
]


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--in", dest="src", help="Path to a single sim screenshot PNG")
    p.add_argument("--headline", help="Headline caption")
    p.add_argument("--subcopy", default="", help="Subcopy under the headline")
    p.add_argument("--out", help="Output path")
    p.add_argument("--all", action="store_true", help="Run every entry in CONFIGS")
    args = p.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    if args.all:
        for cfg in CONFIGS:
            src = RAW_DIR / cfg["src"]
            if not src.exists():
                print(f"  ! skipping {cfg['out']} — source missing: {src}")
                continue
            hero = cfg.get("hero")
            if hero and not Path(hero).exists():
                print(f"  ! hero photo missing for {cfg['out']}, falling back to gradient: {hero}")
                hero = None
            compose(src, cfg["headline"], cfg["subcopy"], OUT_DIR / cfg["out"], hero_photo=hero)
        return 0

    if not args.src or not args.headline or not args.out:
        p.error("Provide --in, --headline, --out (or --all)")

    compose(Path(args.src), args.headline, args.subcopy, Path(args.out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
