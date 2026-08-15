#!/usr/bin/env bash
# Refresh the git-tracked files on `main` that are really PROJECTIONS of the store.
#
# WHY THIS EXISTS, AND WHY IT IS A SCRIPT RATHER THAN A SLOPP FEATURE.
# slopp bootstraps itself: its own code lives in its own store, so the kernel
# (slopp.kernel.boot / slopp.kernel.rt) exists TWICE -- as store forms, and as
# files on `main` that a plain checkout can run before any jar or build! exists.
# deps.edn is {:paths ["src"]}, so `clojure -M -m slopp.kernel.boot .` works from
# a bare clone, and the documented benchmark invocation depends on that.
#
# NO USER HAS THIS PROBLEM. slopp-ui has no src/ at all -- it is fileless and its
# kernel comes from the jar. Building kernel-copy handling into slopp would put
# machinery in the product for a condition only the self-host can be in, which is
# the routing test failing. So the knowledge lives here, in the repo that has it.
#
# The copies have drifted FIVE times, and every reconciliation was a copy:
# `:file-only` has been empty every time -- the typed file has never, in its whole
# history, held a definition the store lacked. If the answer is always "overwrite
# the file", then typing it is the bug and this script is the fix.
#
# SOURCE: build!'s materialization, NOT `git show slopp/main:...`. Two reasons.
# (1) The branch LAGS the store until the next commit_point, so extracting from it
# would force an absurd order -- change the kernel, publish, extract, commit again.
# build! is the store as it stands right now, and is the same producer the jar and
# CI use, so what lands here is byte-identical to what ships.
# (2) It keeps the kernel-parity CI lane HONEST. That lane compares main's
# committed file against slopp/main's projection. Extract from build! and the two
# agree when this script was run and DIVERGE when it was forgotten -- which is the
# only failure mode left, and exactly what the lane should catch. Extract from
# slopp/main instead and the lane would be comparing the projection to itself: a
# green that is structurally incapable of being anything else, which this repo has
# now shipped four times and should not ship a fifth.
#
# THE DECLARED SET POLICES ITSELF. A declared path the materialization does not
# produce is a hard error, not a skip -- a silently-skipped entry is how a list
# like this rots into describing files that no longer exist.
#
# Usage:
#   bin/extract-projection.sh                 refresh the declared set
#   bin/extract-projection.sh --check         report staleness, write nothing (exit 1 if stale)
#   bin/extract-projection.sh <path|glob>...  one-off, e.g. 'src/slopp/kernel/*.clj'
set -euo pipefail

# The git-tracked files on `main` that are projections of the store. Paths are
# relative to the materialization root, which is also the repo root.
#
# EXPLICIT, not `src/slopp/kernel/*.clj`. The store also renders
# src/slopp/kernel/parity.clj, which main deliberately does NOT carry -- only
# boot and rt are needed for the bootstrap, and parity is reached from the
# slopp/main checkout where every file exists. The glob would silently ADD a
# third tracked file. A list you have to edit is the point: what belongs on main
# is a decision, and a pattern would keep making it for you.
DECLARED=(
  src/slopp/kernel/boot.clj
  src/slopp/kernel/rt.clj
)

CHECK=0
if [ "${1:-}" = "--check" ]; then CHECK=1; shift; fi

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

if [ "$#" -gt 0 ]; then PATTERNS=("$@"); else PATTERNS=("${DECLARED[@]}"); fi

JAR="${SLOPP_JAR:-$ROOT/target/slopp.jar}"
[ -f "$JAR" ] \
  || { echo "FAIL: no jar at $JAR -- set SLOPP_JAR or run 'clojure -T:build uber'" >&2; exit 2; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Materialize the WHOLE store and take what we declared. Rendering a subset by
# hand would be a second producer of the same bytes, and the one that ships would
# not be this one.
# Boot chatter is noise on a good run and the only diagnosis on a bad one, so it
# is held and shown only if the build fails.
if ! java -jar "$JAR" --call build "{\"dir\":\"$TMP\"}" >"$TMP/.build.log" 2>&1; then
  echo "FAIL: build! could not materialize the store into $TMP" >&2
  cat "$TMP/.build.log" >&2
  exit 2
fi

stale=0
touched=0
for pat in "${PATTERNS[@]}"; do
  # Expand against the MATERIALIZATION: the store decides what exists.
  matches=$( (cd "$TMP" && ls -d $pat 2>/dev/null) || true )
  if [ -z "$matches" ]; then
    echo "FAIL: '$pat' matches nothing in the materialized store -- has it been renamed or retired?" >&2
    exit 2
  fi
  while IFS= read -r rel; do
    [ -f "$TMP/$rel" ] || continue
    if [ -f "$rel" ] && cmp -s "$TMP/$rel" "$rel"; then
      echo "  ok      $rel"
    elif [ "$CHECK" = 1 ]; then
      # ABSENT and DIFFERS are different findings: absent usually means the path
      # does not belong on main at all (a glob reached too far), while differs
      # means a real drift. Saying "stale" for both sent one reader hunting.
      if [ -f "$rel" ]; then echo "  DIFFERS $rel"; else echo "  ABSENT  $rel"; fi
      stale=$((stale + 1))
    else
      mkdir -p "$(dirname "$rel")"
      cp "$TMP/$rel" "$rel"
      echo "  updated $rel"
      touched=$((touched + 1))
    fi
  done <<< "$matches"
done

if [ "$CHECK" = 1 ]; then
  if [ "$stale" -gt 0 ]; then
    echo "FAIL: $stale projected file(s) differ from the store -- run bin/extract-projection.sh" >&2
    exit 1
  fi
  echo "every projected file matches the store"
else
  echo "$touched updated"
fi
