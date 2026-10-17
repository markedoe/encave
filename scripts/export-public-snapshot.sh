#!/usr/bin/env bash
# Builds a fresh repository containing only the current tracked files, as a
# single commit, so that no earlier history (old secrets, machine identities,
# commit messages) is ever published. Then runs every leak check on it.
#
# Usage:
#   PUBLIC_AUTHOR_NAME="Your Name" \
#   PUBLIC_AUTHOR_EMAIL="12345+you@users.noreply.github.com" \
#   scripts/export-public-snapshot.sh /path/to/new-public-repo
set -euo pipefail

dest="${1:?usage: $0 <empty-destination-dir>}"
: "${PUBLIC_AUTHOR_NAME:?set PUBLIC_AUTHOR_NAME}"
: "${PUBLIC_AUTHOR_EMAIL:?set PUBLIC_AUTHOR_EMAIL}"
[[ "$PUBLIC_AUTHOR_EMAIL" == *@users.noreply.github.com ]] \
  || { echo "Use your GitHub noreply address for PUBLIC_AUTHOR_EMAIL."; exit 1; }
command -v gitleaks >/dev/null || { echo "gitleaks is required (https://github.com/gitleaks/gitleaks)."; exit 1; }

src="$(git rev-parse --show-toplevel)"
cd "$src"
[[ -z "$(git status --porcelain)" ]] || { echo "Commit or stash your changes first."; exit 1; }
if [[ -e "$dest" ]] && [[ -n "$(ls -A "$dest" 2>/dev/null)" ]]; then
  echo "Destination must be empty or not exist: $dest"; exit 1
fi

mkdir -p "$dest"
git archive --format=tar HEAD | tar -x -C "$dest"

cd "$dest"
git init -q -b main
git add -A
GIT_AUTHOR_NAME="$PUBLIC_AUTHOR_NAME" GIT_AUTHOR_EMAIL="$PUBLIC_AUTHOR_EMAIL" \
GIT_COMMITTER_NAME="$PUBLIC_AUTHOR_NAME" GIT_COMMITTER_EMAIL="$PUBLIC_AUTHOR_EMAIL" \
  git commit -q -m "Initial public release"

echo "== gitleaks (history + tree)"
gitleaks git . --config .gitleaks.toml --redact --no-banner
gitleaks dir . --config .gitleaks.toml --redact --no-banner
echo "== public-safety"
CHECK_COMMIT_RANGE="HEAD" scripts/check-public-safety.sh
echo "== commit message"
scripts/check-commit-message.sh --range HEAD

echo
echo "Snapshot ready in $dest ($(git ls-files | wc -l) files, 1 commit)."
echo "Review the file list before pushing:  git -C '$dest' ls-files | less"
