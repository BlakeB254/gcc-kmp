#!/usr/bin/env bash
# Thin wrapper → cdx-mobile (platform-level tool)
# Full tool: ~/bin/cdx-mobile | Config: .cdx-mobile.yaml
exec "$HOME/bin/cdx-mobile" fetch "${1:-all}" --project "$(cd "$(dirname "$0")/.." && pwd)"
