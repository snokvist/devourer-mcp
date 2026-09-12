#!/usr/bin/env bash
# Re-materialize vendor/devourer from the commit pinned in vendor/DEVOURER_VERSION,
# then replay every patch in vendor/patches/ in lexical order.
#
#   tools/host/vendor-devourer.sh                 # restore the pinned commit
#   tools/host/vendor-devourer.sh <commit|ref>    # bump the pin to <commit>
#
# A patch that no longer applies is a hard failure, not a warning: it means
# upstream moved under a local change and a human needs to look.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VERSION_FILE="$ROOT/vendor/DEVOURER_VERSION"
DEST="$ROOT/vendor/devourer"
REPO=$(sed -n 's/^repository *= *//p' "$VERSION_FILE")
PIN=$(sed -n 's/^commit *= *//p' "$VERSION_FILE")
TARGET="${1:-$PIN}"

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
echo "==> cloning $REPO"
git clone --quiet "$REPO" "$tmp/devourer"
git -C "$tmp/devourer" checkout --quiet "$TARGET"

SHA=$(git -C "$tmp/devourer" rev-parse HEAD)
DATE=$(git -C "$tmp/devourer" log -1 --format=%cI)
SUBJ=$(git -C "$tmp/devourer" log -1 --format=%s)
rm -rf "$tmp/devourer/.git"

shopt -s nullglob
for p in "$ROOT"/vendor/patches/*.patch; do
  echo "==> applying $(basename "$p")"
  # Check against the tree we are about to patch, not against $ROOT's copy.
  # $ROOT's vendor/devourer already has the previous run's patches applied,
  # so checking there fails on every re-sync and reported the patch as bad
  # when the patch was fine.
  ( cd "$tmp" && git apply --directory=devourer --check "$p" ) \
    || { echo "FATAL: $(basename "$p") does not apply to $SHA" >&2; exit 1; }
  ( cd "$tmp" && git apply --directory=devourer "$p" )
done

rm -rf "$DEST"; mv "$tmp/devourer" "$DEST"

cat > "$VERSION_FILE" <<EOV
# Pinned upstream OpenIPC Devourer. Do not edit vendor/devourer by hand —
# see tools/host/vendor-devourer.sh and vendor/patches/.
repository = $REPO
ref        = master
commit     = $SHA
committed  = $DATE
subject    = $SUBJ
vendored   = $(date -Iseconds)
EOV
echo "==> vendored $SHA"
