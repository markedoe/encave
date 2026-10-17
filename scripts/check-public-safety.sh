#!/usr/bin/env bash
# Guardrail for a public repository: fails if files that must never be
# published are tracked, or if internal infrastructure details leak.
# Runs in pre-commit and CI. Secret detection itself is done by gitleaks.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
fail=0

# 1. Files that must never be tracked.
forbidden=$(git ls-files | grep -E \
  -e '(^|/)\.env$' \
  -e '(^|/)\.env\.[^/]+$' \
  -e '(^|/)[^/]+\.env$' \
  -e '(^|/)(playwright-report|test-results|blob-report)/' \
  -e '(^|/)playwright/\.auth/' \
  -e '\.(pem|key|p8|p12|pfx|jks|keystore|mobileprovision|sqlite|db|dump|sql\.gz)$' \
  -e '(^|/)(google-services\.json|GoogleService-Info\.plist)$' \
  | grep -vE '\.example$' || true)
if [[ -n "$forbidden" ]]; then
  echo "ERROR: these files must not be committed:"
  echo "$forbidden" | sed 's/^/  - /'
  fail=1
fi

# 2. Internal infrastructure details (hostnames, home paths, private IPs).
patterns=(
  '/home/(ubuntu|debian|admin|root)/'
  '\b(10\.[0-9]{1,3}|192\.168|172\.(1[6-9]|2[0-9]|3[01]))\.[0-9]{1,3}\.[0-9]{1,3}\b'
)
for p in "${patterns[@]}"; do
  # A line can opt out explicitly with the marker "public-safety: allow".
  hits=$(git grep -nIE "$p" -- . ':!scripts/check-public-safety.sh' | grep -v 'public-safety: allow' || true)
  if [[ -n "$hits" ]]; then
    echo "ERROR: internal detail matching /$p/:"
    echo "$hits" | head -20 | sed 's/^/  /'
    fail=1
  fi
done

# 3. Commit identities that expose a machine hostname instead of a real
#    (or GitHub noreply) address. Checks commits in RANGE (default: HEAD only).
range="${CHECK_COMMIT_RANGE:-HEAD^!}"
bad_ids=$(git log --format='%ae%n%ce' "$range" 2>/dev/null \
  | grep -E '^(ubuntu|root|admin|debian|ec2-user)@|@(vps|ip|localhost)[-.]|\.(local|internal|lan)$' \
  | sort -u || true)
if [[ -n "$bad_ids" ]]; then
  echo "ERROR: commit author/committer email looks like a machine identity:"
  echo "$bad_ids" | sed 's/^/  - /'
  echo "  Fix: git config user.email '<id>+<user>@users.noreply.github.com'"
  fail=1
fi

if [[ $fail -ne 0 ]]; then
  echo
  echo "Public-safety check failed. See SECURITY.md (Keeping the repo clean)."
  exit 1
fi
echo "Public-safety check passed."
