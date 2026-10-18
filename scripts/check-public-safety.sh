#!/usr/bin/env bash
# Guardrail for a public repository: fails if files that must never be
# published are tracked or were added in the checked commits, or if internal
# infrastructure details leak into the tree or into lines those commits added.
# Runs in pre-commit and CI. Secret detection itself is done by gitleaks.
#
# CHECK_COMMIT_RANGE (default HEAD^!, the last commit): the commits checked, as
# for git log. A file added and removed again within the range is still caught:
# it stays in the public history.
#
# Patterns use POSIX bracket expressions, not \b: BSD grep and the regex
# library git uses on macOS do not support \b.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
fail=0
range="${CHECK_COMMIT_RANGE:-HEAD^!}"

# The range may not exist (first commit, shallow clone): then only the tree is checked.
if ! git rev-list --quiet "$range" -- 2>/dev/null; then
  echo "note: commit range '$range' not found; checking the tree only"
  range=""
fi

# 1. Files that must never be tracked.
forbidden_files() {
  grep -E \
    -e '(^|/)\.env$' \
    -e '(^|/)\.env\.[^/]+$' \
    -e '(^|/)[^/]+\.env$' \
    -e '(^|/)\.envrc$' \
    -e '(^|/)local\.properties$' \
    -e '(^|/)(playwright-report|test-results|blob-report)/' \
    -e '(^|/)playwright/\.auth/' \
    -e '\.xcresult(/|$)' \
    -e '(^|/)TEST-[^/]*\.xml$' \
    -e '(^|/)build/reports/' \
    -e '\.(pem|key|p8|p12|pfx|jks|keystore|cer|crt|der|mobileprovision|provisionprofile|sqlite|db|dump|sql\.gz)$' \
    -e '(^|/)(google-services\.json|GoogleService-Info\.plist)$' \
    | grep -vE '\.example$' || true
}
forbidden=$( { git ls-files; [[ -n "$range" ]] && git log --no-renames --diff-filter=A --name-only --format= "$range"; } \
  | sort -u | forbidden_files)
if [[ -n "$forbidden" ]]; then
  echo "ERROR: these files must not be committed (tracked now, or added in $range):"
  echo "$forbidden" | sed 's/^/  - /'
  fail=1
fi

# 2. Internal infrastructure details: home folders, private, CGNAT/Tailscale
#    addresses and Tailscale names.
# Left boundary of a path: not part of a longer path component (site/root/ is fine).
P='(^|[^[:alnum:]_.~-])'
# An IPv4 address not inside a longer dotted number (versions, OIDs).
B='(^|[^0-9.])'
E='([^0-9.]|\.[^0-9]|\.?$)'
O='[0-9]{1,3}'
patterns=(
  "${P}/home/[a-z_][a-z0-9_.-]*/"
  "${P}/Users/[A-Za-z0-9_][A-Za-z0-9_.-]*/"
  "${P}/root/"
  "${B}(10\\.${O}|192\\.168|172\\.(1[6-9]|2[0-9]|3[01]))\\.${O}\\.${O}${E}"
  "${B}100\\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\.${O}\\.${O}${E}"
  "[[:alnum:]-]\\.ts\\.net([^[:alnum:].-]|\\.?$)"
)
excluded=(':!scripts/check-public-safety.sh')
# Lines added by the commits in the range: content in one file, "commit file"
# for each line in another, so the patterns only see the content.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
: > "$work/added"; : > "$work/where"
if [[ -n "$range" ]]; then
  git log -p --no-merges --no-renames --no-color --format='commit %h' "$range" -- . "${excluded[@]}" \
    | awk -v where="$work/where" '
        /^commit / { c = $2; next }
        /^\+\+\+ / { f = substr($0, 7); next }
        /^\+/ { print c " " f > where; print substr($0, 2) }' > "$work/added"
fi
for p in "${patterns[@]}"; do
  # A line can opt out explicitly with the marker "public-safety: allow".
  hits=$(git grep -nIE "$p" -- . "${excluded[@]}" | grep -v 'public-safety: allow' || true)
  added=$(grep -anE -- "$p" "$work/added" | grep -v 'public-safety: allow' \
    | awk 'NR == FNR { where[NR] = $0; next }
           { n = $0; sub(/:.*/, "", n); line = $0; sub(/^[0-9]+:/, "", line); print "added in " where[n] ": " line }' \
        "$work/where" - || true)
  hits=$(printf '%s\n%s\n' "$hits" "$added" | sed '/^$/d')
  if [[ -n "$hits" ]]; then
    echo "ERROR: internal detail matching /$p/:"
    echo "$hits" | head -20 | sed 's/^/  /'
    fail=1
  fi
done

# 3. Commit identities that expose a machine hostname instead of a real
#    (or GitHub noreply) address.
if [[ -n "$range" ]]; then
  bad_ids=$(git log --format='%ae%n%ce' "$range" \
    | grep -E '^(ubuntu|root|admin|debian|ec2-user)@|@(vps|ip|localhost)[-.]|\.(local|internal|lan)$' \
    | sort -u || true)
  if [[ -n "$bad_ids" ]]; then
    echo "ERROR: commit author/committer email looks like a machine identity:"
    echo "$bad_ids" | sed 's/^/  - /'
    echo "  Fix: git config user.email '<id>+<user>@users.noreply.github.com'"
    fail=1
  fi
fi

if [[ $fail -ne 0 ]]; then
  echo
  echo "Public-safety check failed. See SECURITY.md (Keeping the repo clean)."
  exit 1
fi
echo "Public-safety check passed."
