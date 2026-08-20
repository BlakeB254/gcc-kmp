#!/usr/bin/env python3
"""
Compose a 1284×2778 App Store preview card (6.9" class) in the style of
assets/store-art/preview-cards-1284x2778/: textured brand background,
headline + subcopy up top, iPhone-style framed simulator screenshot below.

The 1320×2868 composer (scripts/make-appstore-screenshot.py) predates the
preview-card set; this one matches the preview-card layout measured from the
existing cards so regenerated cards sit consistently next to their siblings.

Usage
-----
    python3 scripts/make-preview-card.py \
        --in /tmp/raw-intro-page1.png \
        --background assets/store-art/card-backgrounds/bg-02-scan.png \
        --headline "Scan-verify the clean." \
        --subcopy "Camera on. Litter mapped. Cleanup verified." \
        --out assets/store-art/preview-cards-1284x2778/card-02.png
"""
from __future__ import annotations

import argparse
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

# ── Output canvas (matches the existing preview-cards-1284x2778 set) ──────
CANVAS_W = 1284
CANVAS_H = 2778

# ── Layout (measured from card-02.png) ────────────────────────────────────
HEADLINE_Y = 240
HEADLINE_SIZE = 100
SUBCOPY_Y = 430
SUBCOPY_SIZE = 48
SUBCOPY_COLOR = (255, 255, 255)  # drawn at 84% alpha

DEVICE_TOP = 958
DEVICE_W = 768
DEVICE_H = 1597
BEZEL_PX = 23
BEZEL_RADIUS = 110
BEZEL_COLOR = (232, 236, 234)
SCREEN_RADIUS = BEZEL_RADIUS - BEZEL_PX
ISLAND_W = 200
ISLAND_H = 58
ISLAND_TOP = 22

FONT_BOLD = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
]
FONT_REGULAR = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/System/Library/Fonts/Supplemental/Arial.ttf",
]


def load_font(candidates: list[str], size: int) -> ImageFont.FreeTypeFont:
    for path in candidates:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def draw_centered(draw: ImageDraw.ImageDraw, y: int, text: str,
                  font: ImageFont.FreeTypeFont, fill) -> None:
    bbox = draw.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0]
    draw.text(((CANVAS_W - tw) / 2, y), text, font=font, fill=fill)


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return mask


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", required=True, help="raw simulator screenshot")
    ap.add_argument("--background", required=True, help="card background image")
    ap.add_argument("--headline", required=True)
    ap.add_argument("--subcopy", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    # Background: cover-fit onto the canvas
    bg = Image.open(args.background).convert("RGB")
    scale = max(CANVAS_W / bg.width, CANVAS_H / bg.height)
    bg = bg.resize((round(bg.width * scale), round(bg.height * scale)), Image.LANCZOS)
    left = (bg.width - CANVAS_W) // 2
    top = (bg.height - CANVAS_H) // 2
    canvas = bg.crop((left, top, left + CANVAS_W, top + CANVAS_H)).convert("RGB")

    draw = ImageDraw.Draw(canvas)
    draw_centered(draw, HEADLINE_Y, args.headline, load_font(FONT_BOLD, HEADLINE_SIZE), (255, 255, 255))
    sub = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw_centered(ImageDraw.Draw(sub), SUBCOPY_Y, args.subcopy, load_font(FONT_REGULAR, SUBCOPY_SIZE),
                  SUBCOPY_COLOR + (215,))
    canvas.paste(sub, (0, 0), sub)

    # Device frame
    dev_x = (CANVAS_W - DEVICE_W) // 2
    frame = Image.new("RGBA", (DEVICE_W, DEVICE_H), (0, 0, 0, 0))
    fd = ImageDraw.Draw(frame)
    fd.rounded_rectangle([0, 0, DEVICE_W - 1, DEVICE_H - 1], radius=BEZEL_RADIUS, fill=BEZEL_COLOR)

    # Screen: cover-fit the raw screenshot into the bezel inset
    sw, sh = DEVICE_W - 2 * BEZEL_PX, DEVICE_H - 2 * BEZEL_PX
    shot = Image.open(args.src).convert("RGB")
    sscale = max(sw / shot.width, sh / shot.height)
    shot = shot.resize((round(shot.width * sscale), round(shot.height * sscale)), Image.LANCZOS)
    sx = (shot.width - sw) // 2
    sy = (shot.height - sh) // 2
    shot = shot.crop((sx, sy, sx + sw, sy + sh))
    smask = rounded_mask((sw, sh), SCREEN_RADIUS)
    frame.paste(shot, (BEZEL_PX, BEZEL_PX), smask)

    # Dynamic island
    ix = (DEVICE_W - ISLAND_W) // 2
    fd.rounded_rectangle(
        [ix, BEZEL_PX + ISLAND_TOP, ix + ISLAND_W, BEZEL_PX + ISLAND_TOP + ISLAND_H],
        radius=ISLAND_H // 2, fill=(8, 8, 8),
    )

    canvas.paste(frame, (dev_x, DEVICE_TOP), frame)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out, "PNG")
    print(f"wrote {out} ({CANVAS_W}x{CANVAS_H})")


if __name__ == "__main__":
    main()
