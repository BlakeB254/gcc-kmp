#!/bin/sh
# Invoke one CRON_SECRET-guarded Next.js cron endpoint, once.
#
# Exits non-zero on any non-2xx so Render marks the run failed and surfaces it.
# A `"skipped":"lease-held"` body is a 200 — an overlapping tick is expected at
# a 15m cadence, not a failure. See site/src/lib/cron-lease.ts.
set -eu

: "${CRON_TARGET_URL:?CRON_TARGET_URL is required}"
: "${CRON_SECRET:?CRON_SECRET is required}"

TIMEOUT="${CRON_TIMEOUT:-90}"
BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT

echo "tick: GET ${CRON_TARGET_URL} (timeout ${TIMEOUT}s)"

# --retry covers transient DNS/5xx during a Vercel deploy; it does not retry a
# 4xx, so a bad secret fails fast instead of hammering the endpoint.
STATUS="$(
  curl --silent --show-error \
       --location \
       --max-time "$TIMEOUT" \
       --retry 2 --retry-delay 5 --retry-connrefused \
       --header "Authorization: Bearer ${CRON_SECRET}" \
       --output "$BODY" \
       --write-out '%{http_code}' \
       "$CRON_TARGET_URL"
)"

echo "tick: HTTP ${STATUS}"
cat "$BODY"
echo

case "$STATUS" in
  2*) echo "tick: ok" ;;
  401|403) echo "tick: FAILED — CRON_SECRET does not match the value set on Vercel" >&2; exit 1 ;;
  503) echo "tick: FAILED — endpoint reports CRON_SECRET unset on Vercel" >&2; exit 1 ;;
  *) echo "tick: FAILED — unexpected status ${STATUS}" >&2; exit 1 ;;
esac
