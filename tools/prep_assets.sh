#!/usr/bin/env bash
# ECU_TESTER :: background-remove + optimize reference photos -> web/assets/img/*.png
# Backgrounds are solid, so we flood-fill from all four corners using each corner's
# own seed color (won't hole the subject). rembg is not needed. See plan.
set -euo pipefail
SRC="references"
OUT="web/assets/img"
mkdir -p "$OUT"

# key <src> <outname> <fuzz%> <maxpx>  — corner flood-fill to transparent
key () {
  local src="$SRC/$1" out="$OUT/$2" fuzz="$3" max="$4"
  local w h; w=$(magick "$src" -format "%w" info:); h=$(magick "$src" -format "%h" info:)
  local x1=$((w-1)) y1=$((h-1))
  magick "$src" -alpha set -fuzz "${fuzz}%" \
    -draw "alpha 0,0 floodfill" \
    -draw "alpha $x1,0 floodfill" \
    -draw "alpha 0,$y1 floodfill" \
    -draw "alpha $x1,$y1 floodfill" \
    -channel A -blur 0x0.6 -level 45%,100% +channel \
    -trim +repage -resize "${max}x${max}>" \
    -strip "$out"
  printf "  %-22s %s\n" "$2" "$(magick "$out" -format '%wx%h %B bytes' info:)"
}

echo "sensors / actuators (white/gray bg):"
key MAF.png           maf.png        13 460
key MAP.jpeg          map.png        15 460
key IAT.png           iat.png        13 460
key IAC.png           iac.png        13 460
key CTS.png           cts.png        13 460
key FPC.png           fpc.png        13 460
key Coil.png          coil.png       11 460
key GDI_Injector.png  gdi.png        11 460

echo "sky-blue bg:"
key Injector.jpeg     injector.png   16 460

echo "black bg glyph (keep red):"
key Key_Indicator.jpeg immo.png      14 300

echo "spray sprite (white on transparent, alpha from red-channel separation):"
W=$(magick "$SRC/spray.jpeg" -format "%w" info:); H=$(magick "$SRC/spray.jpeg" -format "%h" info:)
magick \( -size "${W}x${H}" xc:white \) \
       \( "$SRC/spray.jpeg" -channel R -separate +channel -level 60%,97% -blur 0x0.8 \) \
       -alpha off -compose CopyOpacity -composite \
       -trim +repage -resize "320x320>" -strip "$OUT/spray.png"
printf "  %-22s %s\n" "spray.png" "$(magick "$OUT/spray.png" -format '%wx%h %B bytes' info:)"

echo "total:"; du -sh "$OUT"
