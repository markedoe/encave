#!/usr/bin/env bash
# Checks that a built Encave app bundles its privacy manifest, and that the manifest
# says the app does not track and collects nothing.
#   scripts/check-privacy-manifest.sh path/to/Encave.app [...]
set -euo pipefail

status=0
for app in "$@"; do
  manifest=""
  for candidate in "$app/PrivacyInfo.xcprivacy" "$app/Contents/Resources/PrivacyInfo.xcprivacy"; do
    if [ -f "$candidate" ]; then manifest="$candidate"; break; fi
  done
  if [ -z "$manifest" ]; then
    echo "error: no PrivacyInfo.xcprivacy in $(basename "$app")"
    status=1
    continue
  fi
  if command -v plutil >/dev/null; then
    plutil -lint "$manifest" >/dev/null
    tracking=$(plutil -extract NSPrivacyTracking raw -o - "$manifest")
    collected=$(plutil -extract NSPrivacyCollectedDataTypes raw -o - "$manifest")
    if [ "$tracking" != "false" ] || [ "$collected" != "0" ]; then
      echo "error: $manifest declares tracking or collected data"
      status=1
      continue
    fi
  fi
  echo "ok: $manifest"
done
exit $status
