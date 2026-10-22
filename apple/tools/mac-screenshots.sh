#!/bin/bash
# Mac screenshots with the sample cellar, for the App Store and the project page (CI).
#   apple/tools/mac-screenshots.sh path/to/Encave.app OUTPUT_DIR
# Adds a 1440x900-point HiDPI virtual display, launches the app on each section in
# English and French, light and dark, full screen on that display, and keeps the window:
# OUTPUT_DIR/{light,dark}/{en,fr}-NN-section.png, 2880x1800 pixels, without transparency.
# Needs screen recording permission (granted on GitHub's Mac runners).
set -uo pipefail

app=${1:?usage: mac-screenshots.sh Encave.app OUTPUT_DIR}
out=${2:?usage: mac-screenshots.sh Encave.app OUTPUT_DIR}
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
mkdir -p "$out"

clang -fobjc-arc -Wno-objc-method-access -framework Foundation -framework CoreGraphics \
  -framework ImageIO -framework UniformTypeIdentifiers "$here/mac-screens.m" -o "$work/mac-screens" || exit 1

"$work/mac-screens" display 1440 900 > "$work/display.log" 2>&1 &
display=$!
trap 'kill $display 2>/dev/null' EXIT
for _ in $(seq 30); do grep -q ready "$work/display.log" && break; sleep 1; done
cat "$work/display.log"

status=0
count=0
failures=0
for appearance in light dark; do
  for language in en fr; do
    locale=$([ "$language" = fr ] && echo fr_FR || echo en_US)
    step=0
    for section in cellar racks tonight journal; do
      step=$((step + 1))
      # Something is wrong with every launch: say so once rather than 16 times.
      [ "$failures" -ge 2 ] && break 3
      name=$(printf "%s-%02d-%s" "$language" "$step" "$section")
      log="$work/$appearance-$name.log"
      # AppKit reads "-name value" pairs as settings and opens any other word as a
      # document, without a window: --sample-data, alone, comes last.
      "$app/Contents/MacOS/Encave" --tab "$section" \
        -EncaveScreenshots YES -EncaveAppearance "$appearance" \
        -AppleLanguages "($language)" -AppleLocale "$locale" \
        -ApplePersistenceIgnoreState YES -NSAppSleepDisabled YES --sample-data > "$log" 2>&1 &
      pid=$!
      window=
      for _ in $(seq 60); do
        window=$(sed -n 's/^encave-screenshots: window \([0-9]*\) .*/\1/p' "$log" | head -1)
        [ -n "$window" ] && break
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
      grep '^encave-screenshots:' "$log" | sed "s/^/$appearance $name: /"
      if [ -n "$window" ]; then
        mkdir -p "$out/$appearance"
        if screencapture -x -o -l "$window" "$work/shot.png" &&
          "$work/mac-screens" opaque "$work/shot.png" "$out/$appearance/$name.png"; then
          count=$((count + 1))
        else
          echo "::warning::$appearance $name: capture failed"
          status=1
        fi
      else
        echo "::warning::$appearance $name: no window to capture"
        tail -n 40 "$log"
        # Where the app is: its main thread.
        sample "$pid" 2 -file "$work/sample.txt" > /dev/null 2>&1 && sed -n '/^Call graph:/,/^Total number/p' "$work/sample.txt" | head -n 120
        status=1
        failures=$((failures + 1))
      fi
      kill "$pid" 2>/dev/null
      wait "$pid" 2>/dev/null
      sleep 1
    done
  done
done
echo "$count screenshots"
for file in "$out"/*/*.png; do
  [ -e "$file" ] && echo "$file $(sips -g pixelWidth -g pixelHeight "$file" | awk '/pixel/ {printf "%s ", $2}')"
done
exit $status
