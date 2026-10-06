#!/usr/bin/env bash
# Build the multi-size favicon.ico from the SVG logo.
#
# Input (committed):
#   docs/static/assets/images/logo.svg    (cloud logo)
#
# Output:
#   docs/static/favicon.ico (64, 48, 32 and 16 px, logo centered on a square canvas)
#
# Re-run whenever the logo changes.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGO="$ROOT/docs/static/assets/images/logo.svg"
OUT="$ROOT/docs/static/favicon.ico"

[ -f "$LOGO" ] || { echo "missing $LOGO"; exit 1; }

magick -background none -density 300 "$LOGO" \
  -resize 256x256 -gravity center -extent 256x256 \
  -define icon:auto-resize=64,48,32,16 "$OUT"

echo "✓ wrote $OUT ($(stat -c%s "$OUT") bytes)"
