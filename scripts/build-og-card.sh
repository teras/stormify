#!/usr/bin/env bash
# Build the Open Graph / Twitter social card from a background photo + the logo.
#
# Inputs (committed):
#   docs/static/assets/images/og-bg.jpg   (1200x630 background)
#   docs/static/assets/images/logo.png    (cloud logo)
#
# Output:
#   docs/static/assets/images/og-card.jpg (1200x630 social card, ~150 KB)
#
# Re-run whenever the background, logo, tagline, or layout changes.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/docs/static/assets/images"
BG="$ASSETS/og-bg.jpg"
LOGO="$ASSETS/logo.png"
OUT="$ASSETS/og-card.jpg"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

[ -f "$BG" ]   || { echo "missing $BG"; exit 1; }
[ -f "$LOGO" ] || { echo "missing $LOGO"; exit 1; }

FONT_BOLD=/usr/share/fonts/noto/NotoSans-Bold.ttf
FONT_REG=/usr/share/fonts/noto/NotoSans-Regular.ttf
# DejaVu Sans Bold has broader Unicode coverage (used for the → arrow glyph
# that NotoSans-Bold lacks).
FONT_CTA=/usr/share/fonts/TTF/DejaVuSans-Bold.ttf
SHADOW="rgba(0,0,0,0.5)"

# 1. Darken background + add right-side gradient for better text contrast
magick "$BG" \
  \( -size 1200x630 xc:"rgba(0,0,0,0.4)" \) -compose over -composite \
  \( -size 600x630 gradient:"rgba(0,0,0,0)-rgba(0,0,0,0.35)" \) -gravity East -compose over -composite \
  "$TMP/bg.png"

# 2. Compose logo on left (larger for stronger brand presence)
magick "$TMP/bg.png" \
  \( "$LOGO" -resize 380x \) \
  -gravity West -geometry +75+0 -composite "$TMP/with_logo.png"

# 3. Render text with subtle shadow (4px right-down offset)
magick "$TMP/with_logo.png" \
  -font "$FONT_BOLD" -gravity West \
  -fill "$SHADOW" -pointsize 124 -annotate +534-104 "Stormify" \
  -fill white      -pointsize 124 -annotate +530-108 "Stormify" \
  -font "$FONT_REG" -pointsize 44 \
  -fill "$SHADOW"               -annotate +534+8   "A Kotlin Multiplatform ORM," \
  -fill "rgba(255,255,255,0.95)" -annotate +530+4   "A Kotlin Multiplatform ORM," \
  -fill "$SHADOW"               -annotate +534+66  "without the ceremony." \
  -fill "rgba(255,255,255,0.95)" -annotate +530+62  "without the ceremony." \
  -font "$FONT_CTA" -pointsize 36 \
  -fill "$SHADOW"               -annotate +534+189 "Try it →" \
  -fill "#7BC9F2"               -annotate +530+185 "Try it →" \
  -font "$FONT_BOLD" \
  -fill "$SHADOW"               -annotate +700+186 "stormify.org" \
  -fill "rgba(255,255,255,0.85)" -annotate +696+182 "stormify.org" \
  -quality 88 -strip "$OUT"

echo "✓ wrote $OUT ($(stat -c%s "$OUT") bytes)"
