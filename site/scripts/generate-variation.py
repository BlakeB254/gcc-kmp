#!/usr/bin/env python3
"""
Generate a single co-branded variation edit of an upsell card base scene.

Usage:
    python3 scripts/generate-variation.py --card <card-slug> --sponsor <sponsor-slug>

Requires GEMINI_API_KEY in the environment. Uses gemini-3-pro-image-preview via
the google-genai SDK. Saves the result as JPEG (same aspect as the source)
at public/images/upsells/<card>-<sponsor>.jpg.

The sponsor's logo PNG is passed as a reference image along with the base
scene, and the prompt is constructed from the card's `surface` instruction
in variationData.ts with the sponsor name + campaign slogan interpolated.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import sys
from pathlib import Path

from google import genai
from google.genai import types as genai_types

SITE_DIR = Path(__file__).resolve().parent.parent
VAR_DATA = SITE_DIR / "src/components/sponsor/calculator/variationData.ts"
UPSELL_DIR = SITE_DIR / "public/images/upsells"
LOGO_DIR = SITE_DIR / "public/images/mockup-sponsors"

SPONSOR_NAMES = {
    "monday-coffee": "Monday Coffee",
    "del-kar": "Del Kar Pharmacy",
    "barneys": "Barney's New Life",
    "bpp-cubs": "Black Panther Party Cubs",
}
SPONSOR_CAMPAIGNS = {
    "monday-coffee": "Monday Morning Street Cleaners",
    "del-kar": "Healthy Blocks by Del Kar",
    "barneys": "New Life in the Streets",
    "bpp-cubs": "Cubs Clean the Block",
}
SPONSOR_LOGO_FILES = {
    "monday-coffee": "monday-coffee.png",
    "del-kar": "del-kar-pharmacy.png",
    "barneys": "barneys-new-life.png",
    "bpp-cubs": "bpp-cubs.png",
}


def extract_surface_prompt(slug: str) -> str:
    """
    Pull the `surface:` string out of the TS variationData module for a card.

    The source is a pure TS literal; a small regex suffices — no TS parser needed.
    """
    source = VAR_DATA.read_text(encoding="utf-8")
    # Match:  'slug': { ... surface: "..." | '...', ... }
    # Allow single, double, or template quotes. Captures until the matching
    # closing quote honoring escaped quotes.
    pattern = re.compile(
        r"['\"]?" + re.escape(slug) + r"['\"]?\s*:\s*\{[^{]*?surface:\s*(['\"])((?:\\.|(?!\1).)*)\1",
        re.DOTALL,
    )
    match = pattern.search(source)
    if not match:
        print(f"could not find surface prompt for card {slug!r}", file=sys.stderr)
        sys.exit(2)
    # Unescape escaped quotes in the captured string
    raw = match.group(2)
    return bytes(raw, "utf-8").decode("unicode_escape")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--card", required=True)
    parser.add_argument("--sponsor", required=True, choices=list(SPONSOR_NAMES))
    parser.add_argument("--model", default="gemini-3-pro-image-preview")
    parser.add_argument("--dry-run", action="store_true", help="Print the prompt only, do not call API")
    args = parser.parse_args()

    base_path = UPSELL_DIR / f"{args.card}-base.jpg"
    logo_path = LOGO_DIR / SPONSOR_LOGO_FILES[args.sponsor]
    output_path = UPSELL_DIR / f"{args.card}-{args.sponsor}.jpg"

    if not base_path.exists():
        print(f"base image missing: {base_path}", file=sys.stderr)
        return 2
    if not logo_path.exists():
        print(f"logo image missing: {logo_path}", file=sys.stderr)
        return 2

    surface = extract_surface_prompt(args.card)
    sponsor_name = SPONSOR_NAMES[args.sponsor]
    campaign = SPONSOR_CAMPAIGNS[args.sponsor]

    # Interpolate the canonical placeholders
    surface_filled = surface.replace("[sponsor name]", sponsor_name).replace("[campaign slogan]", campaign)

    prompt = (
        f"Edit this photograph (the first image): {surface_filled}\n\n"
        f"The second image is the sponsor's official logo for '{sponsor_name}' — "
        f"reproduce it PIXEL-ACCURATELY on the designated surface. Do not redraw, "
        f"stylize, or approximate the logo. Match the original logo's color, "
        f"proportions, and internal spacing. Integrate it naturally into the "
        f"scene with correct perspective and lighting.\n\n"
        f"Campaign slogan text to use verbatim: '{campaign}'\n\n"
        f"Preserve the base photograph's composition, subjects, lighting, and "
        f"existing GCC branding. Output a 3:2 landscape photograph at high quality."
    )

    if args.dry_run:
        print(f"=== {args.card} / {args.sponsor} ===")
        print(f"base:    {base_path}")
        print(f"logo:    {logo_path}")
        print(f"output:  {output_path}")
        print(f"prompt:\n{prompt}")
        return 0

    client = genai.Client(api_key=os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY"))

    base_bytes = base_path.read_bytes()
    logo_bytes = logo_path.read_bytes()

    base_part = genai_types.Part.from_bytes(data=base_bytes, mime_type="image/jpeg")
    logo_part = genai_types.Part.from_bytes(data=logo_bytes, mime_type="image/png")
    prompt_part = genai_types.Part(text=prompt)

    response = client.models.generate_content(
        model=args.model,
        contents=[base_part, logo_part, prompt_part],
        config=genai_types.GenerateContentConfig(
            response_modalities=["IMAGE", "TEXT"],
        ),
    )

    # Extract image bytes from response
    image_bytes: bytes | None = None
    for candidate in response.candidates or []:
        if not candidate.content or not candidate.content.parts:
            continue
        for part in candidate.content.parts:
            inline = getattr(part, "inline_data", None)
            if inline is not None and getattr(inline, "data", None):
                # inline.data is already bytes in google-genai
                image_bytes = inline.data if isinstance(inline.data, (bytes, bytearray)) else base64.b64decode(inline.data)
                break
        if image_bytes:
            break

    if not image_bytes:
        print("No image in response:", file=sys.stderr)
        try:
            print(response.text, file=sys.stderr)
        except Exception:
            pass
        return 3

    output_path.write_bytes(image_bytes)
    print(f"saved {output_path} ({len(image_bytes)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
