#!/bin/bash
# Download Manrope font files from Google Fonts
# Run this script from the project root

FONT_DIR="daedalus-companion/app/src/main/res/font"

echo "Downloading Manrope font files..."

# Google Fonts direct download links
curl -L -o "$FONT_DIR/manrope_regular.ttf" "https://fonts.gstatic.com/s/manrope/v20/xn7_YHE41ni1AdIRqAuZuw1Bx9mbZk79FO_F.ttf"
curl -L -o "$FONT_DIR/manrope_medium.ttf" "https://fonts.gstatic.com/s/manrope/v20/xn7_YHE41ni1AdIRqAuZuw1Bx9mbZk7PFO_F.ttf"
curl -L -o "$FONT_DIR/manrope_semibold.ttf" "https://fonts.gstatic.com/s/manrope/v20/xn7_YHE41ni1AdIRqAuZuw1Bx9mbZk4jE-_F.ttf"
curl -L -o "$FONT_DIR/manrope_bold.ttf" "https://fonts.gstatic.com/s/manrope/v20/xn7_YHE41ni1AdIRqAuZuw1Bx9mbZk4aE-_F.ttf"

echo "Font files downloaded to $FONT_DIR"
ls -la "$FONT_DIR"/manrope*.ttf
