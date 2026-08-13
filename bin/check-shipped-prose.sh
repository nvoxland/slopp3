#!/usr/bin/env bash
# Shipped prose must not document a capability key the registry does not declare.
#
# `query_capabilities` advertises what a project may set; docs/reference/config.md
# tells a HUMAN the same thing, and nothing compared them. Measured 2026-08-06:
# `web.auth.session.ttl-seconds` was declared, typed, defaulted to 86400 and
# documented as "Browser session lifetime" -- and read by nothing.
# `web.auth/config-from-values` falls through unchanged for it and no session
# mechanism exists. Deleting the registry row left the docs row behind, and only
# a hand grep found it.
#
# WHY A SCRIPT AND NOT A TEST. `docs/` is a repo file and a store test cannot
# reach it. `slopp/main` carries the store's rendered source, so this reaches
# both sides with git and grep -- which also means a broken store cannot make
# the one lane aimed at what SHIPS unrunnable. The other direction (declared but
# never READ) is a store question and lives in
# `ops.selfcheck-test/no-capability-key-goes-unmentioned-by-production-code`.
#
# ONE DIRECTION ONLY, deliberately. Declared-but-undocumented measured 7 of 18
# (slopp.hub.port, the auth families, web.static.*). That is a docs backlog, not
# drift, and asserting it would red the lane on day one for reasons that have
# nothing to do with anything going stale.
#
# SOURCE AND ITS AGE, both printed. The registry comes off `slopp/main`, which
# LAGS the store until the projection is published -- so a key deleted in the
# store still reads as declared here and this lane goes green on prose that is
# already stale. Lenient rather than wrong (in CI the deletion and the docs land
# together), but someone running it locally right after a change deserves to
# know which copy answered. $1 overrides the source, which is also what makes
# this check's own failure path demonstrable without publishing.
#
# Usage: bin/check-shipped-prose.sh [capability-registry-file]
set -euo pipefail

if [ -n "${1:-}" ]; then
  CAPS_SRC=$(cat "$1") || { echo "FAIL: cannot read capability registry from $1" >&2; exit 2; }
  CAPS_LABEL="$1"
else
  # RESOLVE the ref rather than naming it. `actions/checkout` makes only the
  # ref it checked out a LOCAL branch; every other branch arrives as a
  # remote-tracking ref, so `slopp/main` does not exist in CI even with
  # fetch-depth: 0 -- that fetches all HISTORY, not all local branches. This
  # lane runs from a `main` checkout, so the branch it needs is always the
  # remote-tracking one there and always the local one here.
  SLOPP_REF=""
  for r in slopp/main origin/slopp/main; do
    if git rev-parse --verify --quiet "$r^{commit}" >/dev/null; then SLOPP_REF="$r"; break; fi
  done
  [ -n "$SLOPP_REF" ] \
    || { echo "FAIL: no slopp/main or origin/slopp/main in this checkout -- is the projection published, and does this job fetch it?" >&2; exit 2; }
  CAPS_SRC=$(git show "$SLOPP_REF:src/slopp/project/capabilities.clj" 2>/dev/null) \
    || { echo "FAIL: cannot read the capability registry from $SLOPP_REF -- is the projection published?" >&2; exit 2; }
  CAPS_LABEL="$SLOPP_REF @ $(git log -1 --format='%h %cs' "$SLOPP_REF" 2>/dev/null || echo '?')"
fi

DECLARED=$(printf '%s' "$CAPS_SRC" | grep -oE '\{:key "[^"]+"' | sed -E 's/^\{:key "//; s/"$//' | sort -u)
# scoped to the capabilities SECTION: config.md documents several config files,
# and the store-settings table above it carries user.name/user.email, which are
# git author identity and not capabilities at all. Owner segments (`web.`) live
# in a nested table in the same section and end in a dot -- keys do not.
DOCUMENTED=$(awk '/^## The capabilities file/{f=1;next} /^## /{f=0} f' docs/reference/config.md \
             | grep -oE '^\| *`[a-zA-Z0-9._*-]+`' | tr -d '|` ' | grep -v '\.$' | sort -u)

n_decl=$(echo "$DECLARED" | grep -c .)
n_doc=$(echo "$DOCUMENTED" | grep -c .)
echo "capabilities: $CAPS_LABEL -- $n_doc documented in the config reference, $n_decl declared"
[ "$n_decl" -gt 0 ] || { echo "FAIL: no capability keys declared -- this check would pass vacuously" >&2; exit 2; }
[ "$n_doc"  -gt 0 ] || { echo "FAIL: no documented capability keys found -- the section heading or table shape changed, and an empty scan reads exactly like a clean one" >&2; exit 2; }

# the detector must fire on a key that is not declared AND stay silent on one
# that is -- both halves, because "it can fail" without "it can pass" is how a
# substring bug once reported every corrected line as a violation.
probe_real=$(echo "$DECLARED" | head -1)
[ -n "$(comm -23 <(printf 'slopp.nosuch.invented.key\n') <(echo "$DECLARED"))" ] \
  || { echo "FAIL: the detector does not report an undeclared key" >&2; exit 2; }
[ -z "$(comm -23 <(printf '%s\n' "$probe_real") <(echo "$DECLARED"))" ] \
  || { echo "FAIL: the detector reports a DECLARED key ($probe_real) -- every documented capability would read as a violation" >&2; exit 2; }

STALE=$(comm -23 <(echo "$DOCUMENTED") <(echo "$DECLARED"))
if [ -n "$STALE" ]; then
  echo "$STALE" | sed 's/^/  [undeclared capability] docs\/reference\/config.md: /'
  cat >&2 <<'MSG'

FAIL: the config reference documents a capability key the registry does not declare.

A user reads that table and sets the key. Nothing rejects it and nothing reads
it, so the setting is inert and the silence looks like it worked -- strictly
worse than an absent row, which would at least prompt a question. Either the
key was deleted from the registry and the docs row was left behind (fix the
docs), or it should exist (add the registry row).
MSG
  exit 1
fi

echo "clean"
