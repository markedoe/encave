#!/usr/bin/env bash
# Keeps commit messages short and free of conversation/prompt text.
# Usage:
#   scripts/check-commit-message.sh <message-file>      (commit-msg hook)
#   scripts/check-commit-message.sh --range A..B        (CI)
#   scripts/check-commit-message.sh --text-only <file>  (PR title/body: phrases only)
set -euo pipefail

MAX_SUBJECT=72
MAX_BODY_LINES=12
# Phrases that indicate a pasted prompt or chat transcript.
BANNED='((my|your|this|the user.s|mon|ton|le) prompt|the user (asked|wants|said|requested)|as requested|you asked|l.utilisateur|je veux|tu peux|peux-tu|stp|s.il te pla|merci|as an ai|claude code session transcript|<pasted_content|system-reminder)'

# Links to AI chat sessions expose private conversations, even as trailers.
SESSION_LINK='(claude\.ai/(code/)?(session|chat)|chatgpt\.com/(c|share)/|Claude-Session:)'

check_message() {
  local label="$1" msg="$2" errors=0
  if printf '%s\n' "$msg" | grep -qiE "$SESSION_LINK"; then
    echo "$label: contains a link to an AI chat session; remove it"; errors=1
  fi
  # Drop comment lines; in the body, also drop git trailers
  # (Co-Authored-By, Signed-off-by...) from the line count. Session links fail
  # above, trailers included.
  local nocomment subject body clean
  nocomment=$(printf '%s\n' "$msg" | grep -v '^#' || true)
  subject=$(printf '%s\n' "$nocomment" | sed -n '1p')
  body=$(printf '%s\n' "$nocomment" | sed '1d' | grep -vE '^[A-Z][A-Za-z-]+: ' || true)
  clean=$(printf '%s\n%s\n' "$subject" "$body")
  local body_lines
  body_lines=$(printf '%s\n' "$body" | grep -c '[^[:space:]]' || true)

  if [[ -z "$subject" ]]; then
    echo "$label: empty subject"; errors=1
  elif (( ${#subject} > MAX_SUBJECT )); then
    echo "$label: subject is ${#subject} chars (max $MAX_SUBJECT)"; errors=1
  fi
  if (( body_lines > MAX_BODY_LINES )); then
    echo "$label: body has $body_lines lines (max $MAX_BODY_LINES); summarize the change"; errors=1
  fi
  if printf '%s\n' "$clean" | grep -qiE "$BANNED"; then
    echo "$label: message looks like it contains prompt or chat text:"
    printf '%s\n' "$clean" | grep -inE "$BANNED" | head -3 | sed 's/^/    /'
    errors=1
  fi
  return $errors
}

if [[ "${1:-}" == "--text-only" ]]; then
  if grep -qiE "$SESSION_LINK" "$2"; then
    echo "Pull request text contains a link to an AI chat session; remove it"
    exit 1
  fi
  if grep -qiE "$BANNED" "$2"; then
    echo "Pull request text looks like it contains prompt or chat text:"
    grep -inE "$BANNED" "$2" | head -3 | sed 's/^/    /'
    exit 1
  fi
  exit 0
fi

if [[ "${1:-}" == "--range" ]]; then
  status=0
  # Assigned first so an unknown range fails instead of checking nothing.
  shas=$(git rev-list --no-merges "$2")
  for sha in $shas; do
    check_message "commit ${sha:0:8}" "$(git log -1 --format=%B "$sha")" || status=1
  done
  exit $status
fi

check_message "commit message" "$(cat "$1")"
