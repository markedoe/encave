#!/usr/bin/env bash
# XcodeGen and xcbeautify for the CI (apple.yml), from their GitHub releases at pinned
# versions with checked SHA-256 sums, instead of whatever Homebrew has that day.
# Adds them to GITHUB_PATH for the next steps. To update: change a version, download
# the asset and put its `shasum -a 256` here.
set -euo pipefail

XCODEGEN_VERSION=2.46.0
XCODEGEN_SHA256=4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806
XCBEAUTIFY_VERSION=3.2.1
XCBEAUTIFY_SHA256=87fdbe786f71d7b2ee28c5c1ca97087587292f2c5c6f9e721cf02db8b2a3c241

dir="${RUNNER_TEMP:?}/build-tools"
mkdir -p "$dir/bin"
cd "$dir"

fetch() {  # url file sha256
  curl -fsSL --retry 3 -o "$2" "$1"
  echo "$3  $2" | shasum -a 256 -c -
}

fetch "https://github.com/yonaskolb/XcodeGen/releases/download/$XCODEGEN_VERSION/xcodegen.zip" \
  xcodegen.zip "$XCODEGEN_SHA256"
unzip -q -o xcodegen.zip  # xcodegen/bin/xcodegen, with its share/ folder next to bin/

fetch "https://github.com/cpisciotta/xcbeautify/releases/download/$XCBEAUTIFY_VERSION/xcbeautify-$XCBEAUTIFY_VERSION-universal-apple-macosx.zip" \
  xcbeautify.zip "$XCBEAUTIFY_SHA256"
unzip -q -o xcbeautify.zip
mv release/xcbeautify bin/xcbeautify

echo "$dir/xcodegen/bin" >> "$GITHUB_PATH"
echo "$dir/bin" >> "$GITHUB_PATH"
"$dir/xcodegen/bin/xcodegen" --version
"$dir/bin/xcbeautify" --version
