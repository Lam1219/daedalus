#!/bin/bash
# Download Manrope font files from Google Fonts
# Run this script from the project root

FONT_DIR="daedalus-companion/app/src/main/res/font"

echo "Downloading Manrope font files..."

# Google Fonts direct download links
curl -L -o "$FONT_DIR/manrope_regular.ttf" "https://fonts.gstatic.com/s/manrope/v15/xn7gYHE41ni1AdIRggqxSvfedto.ttf"
curl -L -o "$FONT_DIR/manrope_medium.ttf" "https://fonts.gstatic.com/s/manrope/v15/xn7gYHE41ni1AdIRgguxRvfedto.ttf"
curl -L -o "$FONT_DIR/manrope_semibold.ttf" "https://fonts.gstatic.com/s/manrope/v15/xn7gYHE41ni1AdIRggvxQFfedto.ttf"
curl -L -o "$FONT_DIR/manrope_bold.ttf" "https://fonts.gstatic.com/s/manrope/v15/xn7gYHE41ni1AdIRggtPQFfedto.ttf"

echo "Font files downloaded to $FONT_DIR"
ls -la "$FONT_DIR"/manrope*.ttf
