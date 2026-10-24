#!/usr/bin/env bash
# Flags system colors (.red, .gray, Color.white...) in the Apple app's features:
# colors come from design/tokens.json (CLAUDE.md), through the semantic colors of
# apple/Encave/DesignSystem/SemanticColors.swift. A line that must keep a fixed
# color (a QR code's quiet zone, a halo over the camera image) carries the comment
# "fixed color:" on the line itself or the line above.
#
# Usage: scripts/check-system-colors.sh [directory]  (default apple/Encave/Features)
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
dir="${1:-apple/Encave/Features}"
names='red|green|blue|orange|yellow|pink|purple|indigo|teal|cyan|mint|brown|gray|black|white'
# POSIX classes rather than \b, for BSD grep.
pattern="(Color\.($names)([^A-Za-z0-9_]|$))|((foregroundStyle|foregroundColor|tint|fill|background|stroke|strokeBorder|color)\(\.($names)([^A-Za-z0-9_]|$))|((\?\?|,) \.($names)([^A-Za-z0-9_]|$))"

fail=0
while IFS= read -r file; do
  awk -v pat="$pattern" -v file="$file" '
    { line = $0; code = line; sub(/\/\/.*/, "", code) }
    code ~ pat && line !~ /fixed color:/ && prev !~ /fixed color:/ {
      printf "%s:%d: system color, use a design token: %s\n", file, NR, line; bad = 1
    }
    { prev = line }
    END { exit bad }
  ' "$file" || fail=1
done < <(git ls-files -- "$dir" | grep '\.swift$')

if [ "$fail" -ne 0 ]; then
  echo "Use the semantic colors (Color.warning, .action, .neutral...) from design/tokens.json." >&2
  exit 1
fi
echo "No system colors in $dir."
