"""Renders a block-cleaning report (block_report.py output in R2) into a
branded, sponsor-ready marketing PDF — the document an admin hands to a
funder: totals, before/after evidence pairs, lap timing and route stats.

Usage:
  export R2_* ...
  python render_report_pdf.py <cellId> <beforeSession>__<afterSession> \
      [--out ~/Documents/gcc-cleaning-report.pdf]

Requires system python3 + playwright (chromium) for the PDF step.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

import r2_store

CSS = """
  @page { size: letter; margin: 0; }
  * { box-sizing: border-box; margin: 0; }
  body { font: 10.5pt/1.45 Georgia, serif; color: #111827; }
  .band { background: linear-gradient(120deg, #052e16, #14532d 55%, #1a7a3d); color: #fff;
          padding: .4in .6in .28in; }
  .band::after { content: ''; display: block; height: 0; }
  .kicker { font: 700 8.5pt Helvetica, Arial, sans-serif; color: #4ade80;
            text-transform: uppercase; letter-spacing: 2pt; margin-bottom: 4pt; }
  h1 { font: 800 20pt/1.1 Helvetica, Arial, sans-serif; }
  .sub { font: italic 10pt Georgia, serif; color: #d1fae5; margin-top: 4pt; }
  .accent { height: 8pt; background: linear-gradient(90deg, #16a34a 0 30%, #4ade80 30% 55%, #fbbf24 55% 70%, #16a34a 70% 100%); }
  .body { padding: .25in .6in; }
  .stat-row { display: flex; gap: 9pt; margin: 10pt 0; }
  .stat { flex: 1; border-radius: 6pt; padding: 8pt 10pt; color: #fff;
          background: linear-gradient(150deg, #14532d, #052e16); }
  .stat .n { font: 800 16pt/1 Helvetica, Arial, sans-serif; }
  .stat .l { font: 7.6pt/1.3 Helvetica, Arial, sans-serif; color: rgba(255,255,255,.85); margin-top: 3pt; }
  h2 { font: 800 12.5pt Helvetica, Arial, sans-serif; color: #14532d; margin: 12pt 0 6pt;
       display: flex; align-items: center; gap: 6pt; }
  h2::before { content: ''; width: 14pt; height: 5pt; border-radius: 3pt;
               background: linear-gradient(90deg, #16a34a, #4ade80); }
  .pair { display: flex; gap: 10pt; margin-bottom: 10pt; page-break-inside: avoid; }
  .shot { flex: 1; }
  .shot img { width: 100%; border-radius: 6pt; border: 1pt solid #d1d5db; }
  .shot .cap { font: 700 8.5pt Helvetica, Arial, sans-serif; margin-top: 3pt; }
  .shot .cap.before { color: #b91c1c; } .shot .cap.after { color: #15803d; }
  .missing { display: flex; align-items: center; justify-content: center; min-height: 1.6in;
             background: #f3f4f6; border-radius: 6pt; color: #9ca3af;
             font: italic 9pt Georgia, serif; }
  .meta { font: 8.5pt Helvetica, Arial, sans-serif; color: #4b5563; }
  .foot { background: #052e16; color: #a7f3d0; padding: 6pt .6in;
          font: 7.5pt Helvetica, Arial, sans-serif; display: flex; justify-content: space-between; }
"""


def _fmt_ms(ms) -> str:
    if not ms:
        return "—"
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%b %d, %Y %H:%M UTC")


def build_html(report: dict, images: dict[str, bytes], cell: str) -> str:
    t = report["totals"]
    before, after = report["before"], report["after"]
    dur_min = lambda lap: round(((lap.get("completedAtMs") or 0) - (lap.get("startedAtMs") or 0)) / 60000) or "—"
    pairs = []
    for item in report["items"]:
        def img_tag(key, cls, label):
            if key and key.split("/")[-1] in images:
                b64 = base64.b64encode(images[key.split("/")[-1]]).decode()
                return f'<div class="shot"><img src="data:image/jpeg;base64,{b64}"><div class="cap {cls}">{label}</div></div>'
            return f'<div class="shot"><div class="missing">no {label.lower()} angle captured</div><div class="cap {cls}">{label}</div></div>'
        grams = item.get("estimatedWeightGrams")
        title = f"{(item.get('itemType') or 'item').title()}" + (f" · est. {grams/1000:.1f} kg" if grams else "")
        pairs.append(
            f'<h2>{title}</h2><div class="pair">'
            + img_tag(item.get("beforeImage"), "before", "BEFORE — as reported")
            + img_tag(item.get("afterImage"), "after", "AFTER — same angle")
            + "</div>"
        )
    return f"""<!DOCTYPE html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body>
<div class="band">
  <div class="kicker">Global Community Cleaners · Verified Cleanup Report</div>
  <h1>Block Cleaning Report — Cell {cell}</h1>
  <p class="sub">Before/after evidence captured by the workers' own scans — every image
  is geo-anchored, timestamped, and traceable to its scan session.</p>
</div><div class="accent"></div>
<div class="body">
  <div class="stat-row">
    <div class="stat"><div class="n">{t['litterItems']}</div><div class="l">litter items documented</div></div>
    <div class="stat"><div class="n">{t['estimatedGrams']/1000:.1f} kg</div><div class="l">estimated debris (LiDAR-scaled)</div></div>
    <div class="stat"><div class="n">{t['afterMatchedImages']}/{t['litterItems']}</div><div class="l">matched-angle after shots</div></div>
    <div class="stat"><div class="n">{report['route']['beforeWalkedMeters']:.0f} m</div><div class="l">route walked (before lap)</div></div>
  </div>
  <p class="meta"><b>Before lap</b> {before['sessionId']} · {_fmt_ms(before.get('startedAtMs'))} · {dur_min(before)} min · {before.get('scannedCells')} cells scanned
  &nbsp;&nbsp;<b>After lap</b> {after['sessionId']} · {_fmt_ms(after.get('startedAtMs'))} · {dur_min(after)} min</p>
  {''.join(pairs)}
</div>
<div class="foot"><span>GLOBAL COMMUNITY CLEANERS — globalcommunitycleaners.org</span>
<span>generated {datetime.now().strftime('%b %d, %Y')} · report {before['sessionId']} → {after['sessionId']}</span></div>
</body></html>"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cell")
    parser.add_argument("pair", help="<beforeSession>__<afterSession>")
    parser.add_argument("--out", default=os.path.expanduser("~/Documents/gcc-cleaning-report.pdf"))
    args = parser.parse_args()

    prefix = f"reports/{args.cell}/{args.pair}/"
    report = json.loads(r2_store.download_bytes(prefix + "report.json"))
    images: dict[str, bytes] = {}
    for item in report["items"]:
        for key in (item.get("beforeImage"), item.get("afterImage")):
            if key:
                name = key.split("/")[-1]
                try:
                    images[name] = r2_store.download_bytes(prefix + name if "/" not in key else key)
                except Exception:
                    pass

    html = build_html(report, images, args.cell)
    with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False) as f:
        f.write(html)
        html_path = f.name
    render = f"""
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page()
    pg.goto("file://{html_path}")
    pg.pdf(path="{args.out}", format="Letter", print_background=True)
    b.close()
print("PDF: {args.out}")
"""
    subprocess.run([sys.executable if "playwright" in sys.modules else "python3", "-c", render], check=True)
    os.unlink(html_path)


if __name__ == "__main__":
    main()
