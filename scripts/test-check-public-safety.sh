#!/usr/bin/env bash
# Tests of scripts/check-public-safety.sh: each case is a throwaway repository
# that the check must refuse (or, for the clean case, pass). Runs in CI.
# The paths below are built from parts so that this file passes the check itself.
set -euo pipefail

script="$(cd "$(dirname "$0")" && pwd)/check-public-safety.sh"
work=$(mktemp -d)
trap '[[ -n "${KEEP:-}" ]] || rm -rf "$work"' EXIT
failures=0
h=home u=Users

# A fresh repository with the check and one base commit, in $repo.
new_repo() {
  repo="$work/$1"
  git init -q -b main "$repo"
  git -C "$repo" config user.email test@users.noreply.github.com
  git -C "$repo" config user.name Test
  mkdir -p "$repo/scripts"
  cp "$script" "$repo/scripts/"
  echo base > "$repo/README.md"
  git -C "$repo" add -A
  git -C "$repo" commit -q -m base
  base=$(git -C "$repo" rev-parse HEAD)
}

commit() { git -C "$repo" add -A && git -C "$repo" commit -q -m "$1"; }

# expect pass|fail NAME: runs the check on the commits after the base one.
expect() {
  local want=$1 name=$2 got=pass
  (cd "$repo" && CHECK_COMMIT_RANGE="$base..HEAD" scripts/check-public-safety.sh > "$work/$name.log" 2>&1) || got=fail
  if [[ $got == "$want" ]]; then
    echo "ok   $name ($want)"
  else
    echo "FAIL $name: expected $want, got $got"; sed 's/^/     /' "$work/$name.log"
    failures=$((failures + 1))
  fi
}

# One forbidden file each.
for file in id_rsa .ssh/id_ed25519 id_ecdsa android/keystore.properties signing.properties .netrc; do
  name="file-${file//\//-}"
  new_repo "$name"
  mkdir -p "$(dirname "$repo/$file")"
  echo secret > "$repo/$file"
  commit "add $file"
  expect fail "$name"
done

new_repo service-account
printf '{\n  "type": "service_%s",\n  "private_key_id": "0",\n  "private_key": "x"\n}\n' account > "$repo/play.json"
commit "add a key"
expect fail service-account

# Added and removed again: still in the public history.
new_repo service-account-history
printf '{"type": "service_%s", "private_key": "x"}\n' account > "$repo/play.json"
commit "add a key"
git -C "$repo" rm -q play.json
commit "remove it"
expect fail service-account-history

new_repo path-var-home
echo "see /var/$h/alice/notes" > "$repo/notes.txt"
commit "add a path"
expect fail path-var-home

new_repo path-windows
echo "see C:\\$u\\alice\\notes" > "$repo/notes.txt"
commit "add a path"
expect fail path-windows

# Text written only in a merge resolution, removed again afterwards.
new_repo merge-resolution
echo one > "$repo/notes.txt"; commit one
git -C "$repo" checkout -q -b side
echo side > "$repo/notes.txt"; commit side
git -C "$repo" checkout -q main
echo main > "$repo/notes.txt"; commit main
git -C "$repo" merge -q side > /dev/null 2>&1 || true
echo "resolved, see /$h/alice/notes" > "$repo/notes.txt"
git -C "$repo" add notes.txt
git -C "$repo" commit -q --no-edit
echo clean > "$repo/notes.txt"; commit clean
expect fail merge-resolution

# Look-alikes that must pass: a public key, a service account without a key, a site path.
new_repo clean
echo "ssh-ed25519 AAAA test" > "$repo/id_ed25519.pub"
printf '{"type": "service_%s", "client_email": "x"}\n' account > "$repo/account.json"
echo "served from site/$h/page and C:\\Program Files\\x" > "$repo/notes.txt"
commit "look-alikes"
expect pass clean

if [[ $failures -ne 0 ]]; then
  echo "$failures public-safety test(s) failed."
  exit 1
fi
echo "Public-safety tests passed."
