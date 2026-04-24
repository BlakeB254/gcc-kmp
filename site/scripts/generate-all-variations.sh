#!/usr/bin/env bash
# Batch generator for the 40 co-branded upsell variations.
# Runs 3 in parallel per card (1 card at a time). Skips files that already exist.

set -u
cd "$(dirname "$0")/.."

CARDS=(
  worker-branding
  equipment-branding
  street-advertising
  digital-presence
  enhanced-cleaning
  seasonal-services
  green-services
  business-engagement
  events
  media
)

SPONSORS=(monday-coffee del-kar barneys bpp-cubs)

LOG="/tmp/gcc-variations-$(date +%s).log"
echo "logging to $LOG"

for card in "${CARDS[@]}"; do
  echo ""
  echo "=== card: $card ==="
  pids=()
  for sponsor in "${SPONSORS[@]}"; do
    out="public/images/upsells/${card}-${sponsor}.jpg"
    if [ -f "$out" ]; then
      echo "[skip] $out (already exists)"
      continue
    fi
    (
      echo "[start] ${card} / ${sponsor}"
      python3 scripts/generate-variation.py --card "$card" --sponsor "$sponsor" >>"$LOG" 2>&1 \
        && echo "[ok]    ${card} / ${sponsor}" \
        || echo "[FAIL]  ${card} / ${sponsor} — see $LOG"
    ) &
    pids+=($!)
  done
  for pid in "${pids[@]}"; do
    wait "$pid" || true
  done
done

echo ""
echo "--- summary ---"
ls -la public/images/upsells/*.jpg | wc -l
echo "expected: 50 (10 base + 40 variations)"
