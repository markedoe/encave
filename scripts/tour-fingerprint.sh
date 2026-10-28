#!/usr/bin/env bash
# Prints a fingerprint of everything that can change the Apple screen tour: the files
# that trigger .github/workflows/apple.yml plus what its tour jobs read. Two commits with the
# same fingerprint build the same app and run the same tests and workflow, so a tour that
# passed on one has passed on the other (used by the tour-check job).
#
# Usage: scripts/tour-fingerprint.sh [rev]   (default HEAD; needs the commit's tree only)
#
# When a path may matter, list it here; a path listed in too many places only costs a tour.
set -euo pipefail

rev="${1:-HEAD}"
paths=(
  apple                                # apps, CaveKit and its tests, tools the jobs run
  design                               # tokens.json and the asset generators
  fixtures                             # shared test vectors read by the CaveKit tests
  licences LICENSE                     # bundled in the apps
  scripts/check-privacy-manifest.sh    # run by the apps job
  scripts/tour-fingerprint.sh          # this list
  .github/workflows/apple.yml
)

git ls-tree -r "$rev" -- "${paths[@]}" | sha256sum | cut -d' ' -f1
