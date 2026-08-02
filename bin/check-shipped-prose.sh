#!/usr/bin/env bash
#
# Does the SHIPPED prose still name things we retired?
#
# The guard that already exists for this class -- mcp-test's
# `slopp-prose-never-names-a-tool-that-does-not-exist` -- scans STORE FORMS,
# and the skills and docs are FILES. That gap is not theoretical: four bugs
# went out through it in one day. The skill told agents to read
# `session_brief`'s `:ui-hub` (a key that returns nil) and to set `dev.server`
# (a capability `config_file` would refuse); slopp-review's URL template
# double-appended `/p/<slug>`; the tools reference documented `ui.hub-port`
# and `ui.port`. Every rename had rewritten the store and walked past the
# files, and no test in the system could see any of it.
#
# WHY A SCRIPT AND NOT A TEST. A store test cannot reach these files. The
# external tier runs in the materialized build dir, which carries `src`,
# `test` and the config projections and NOT `docs/` or `plugins/` -- measured,
# not assumed. And the two halves live on different branches: `main` has the
# prose, `slopp/main` has the store's rendered `vocabulary`. So this reaches
# across, the same way the kernel-parity lane does.
#
# WHAT IT CHECKS. Exact retired spellings, declared in the store's
# `vocabulary` config (`config_file {path "vocabulary" key <old> value <new>}`)
# -- the machine-readable twin of `.context/naming-glossary.md`. Declaring the
# rename is what gives this teeth, and it is the one step a rename must not
# skip.
#
# Deliberately NOT a forward check against the current registries. Measured
# over this corpus: a retired FAMILY is invisible to any check against what
# exists now (`http.*`, `ui.*` and `dev.*` are all simply gone), so the
# forward direction would have caught none of the four. It also produced six
# findings and all six were false -- tutorial examples like `invoice.total`
# and `x.y.z`, a SQLite pragma, a GitHub Actions key.
#
# `docs/blog/posts/**` is EXCLUDED. The glossary already settles this: dated
# records keep their original names, because a release post announcing a tool
# that has since been removed is correct, not stale. Measured: every hit in
# the blog was of exactly that kind.
#
# Usage: bin/check-shipped-prose.sh [vocabulary-file]
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2

VOCAB_SRC="${1:-}"
if [ -z "$VOCAB_SRC" ]; then
  VOCAB_SRC=/tmp/slopp-vocabulary.$$
  git show slopp/main:vocabulary > "$VOCAB_SRC" 2>/dev/null \
    || { echo "FAIL: cannot read vocabulary from slopp/main -- is the projection published?" >&2; exit 2; }
fi

# `old: new` per line. Only the retired spellings matter here.
#
# DISTINCTIVE TERMS ONLY -- a retired spelling with no `.`, `-` or `_` in it is
# skipped, and that is not laziness. The `vocabulary` config has two consumers
# with different precision needs. The store-form rule
# (slopp.api.rules/retired-vocabulary-check) matches KEYWORDS and demands an
# ENUMERATION -- two retired members, or one beside its replacement -- because
# a bare `:reads` is usually the still-valid `^:reads` MARKER, a live concept
# sharing a name with the retired tier. Prose has no such structure to lean on.
#
# Measured: scanning every declared term matched `reads` and `effects` on 90+
# lines of ordinary English ("the dispatcher fetches the declared reads",
# "concentrate effects in a thin shell") across 20 files. Every one a false
# positive. The 14 renames declared on 2026-08-02 all carry a separator and all
# came back clean, so the filter costs nothing real and buys the whole check.
#
# The rule to keep: a term that reads as an English word cannot be scanned for
# in prose. If you retire one, the prose half of the sweep stays manual.
RETIRED=$(sed -E 's/:.*$//' "$VOCAB_SRC" | grep -E '\S' | grep -E '[._-]' | sort -u)
FILES=$(find plugins/slopp/skills docs -name '*.md' 2>/dev/null | grep -v '^docs/blog/' | sort)

n_terms=$(echo "$RETIRED" | grep -c .)
n_files=$(echo "$FILES" | grep -c .)

# POSITIVE CONTROL. Either population being empty makes every check below pass
# by being empty on both sides -- which is how the store-form sibling of this
# guard spent its entire life scanning nothing and reporting success.
echo "scanning $n_files shipped prose file(s) for $n_terms retired spelling(s)"
[ "$n_terms" -gt 0 ] || { echo "FAIL: no retired vocabulary declared -- this check would pass vacuously" >&2; exit 2; }
[ "$n_files" -gt 0 ] || { echo "FAIL: no prose files found -- this check would pass vacuously" >&2; exit 2; }

# MATCH AT A SEGMENT BOUNDARY, not as a substring.
#
# A rename whose NEW name CONTAINS the old one -- `auth.bearer.` ->
# `web.auth.bearer.` -- makes an unanchored fixed-string scan report every
# correctly-renamed line as a violation. Measured the first time one landed:
# 17 findings, all 17 false, every one of them prose that had just been fixed.
# A guard against stale names that fires hardest on the freshly-corrected ones
# teaches you to stop reading it.
#
# So the term must start where a segment starts. `.` counts as a segment
# character, so `web.auth.bearer.` no longer matches `auth.bearer.`, while a
# real mention -- after a backtick, a quote, a space, a line start -- still
# does. Same whole-segment rule rename_sweep uses, for the same reason.
# escape by ALLOWLIST -- anything not alphanumeric/_/- gets a backslash. The
# denylist version of this line was a bracket expression, and it broke on BSD
# sed while looking correct; the negative probe below is what reported it.
rx_escape() { printf '%s' "$1" | sed -E 's/[^A-Za-z0-9_-]/\\&/g'; }

# the detector must fire on a known-bad line AND stay silent on a known-good
# one. Only the first half existed, which is exactly how the substring bug got
# in: "it can fail" was checked, "it can pass" was not.
probe_dir=$(mktemp -d)
first_pair=$(grep -E '^[^:]*[._-][^:]*:' "$VOCAB_SRC" | head -1)
first_old=${first_pair%%:*}
first_new=$(printf '%s' "${first_pair#*:}" | sed -E 's/^ +//')
printf 'a line naming %s\n' "$first_old" > "$probe_dir/bad.md"
printf 'a line naming %s\n' "$first_new" > "$probe_dir/good.md"
first_rx="(^|[^A-Za-z0-9._-])$(rx_escape "$first_old")"
grep -qE -- "$first_rx" "$probe_dir/bad.md" \
  || { echo "FAIL: the detector does not fire on a known-bad line" >&2; exit 2; }
grep -qE -- "$first_rx" "$probe_dir/good.md" \
  && { echo "FAIL: the detector fires on the REPLACEMENT ($first_new) -- every corrected line would read as a violation" >&2; exit 2; }

found=0
while IFS= read -r term; do
  [ -n "$term" ] || continue
  if hits=$(grep -HnE -- "(^|[^A-Za-z0-9._-])$(rx_escape "$term")" $FILES 2>/dev/null); then
    while IFS= read -r h; do
      echo "  [$term] $h"
      found=1
    done <<< "$hits"
  fi
done <<< "$RETIRED"

if [ "$found" -eq 1 ]; then
  cat >&2 <<'MSG'

FAIL: shipped prose names a retired spelling.

These files SHIP -- the skills are the product, and an agent following them
pays a failed call to find out. Two ways out, and they are not equivalent:

  1. Fix the prose. Almost always this one.
  2. If the mention is deliberately historical ("it used to be X"), move it to
     a dated record under docs/blog/posts/, which this check excludes on
     purpose. Live reference prose should not need the retired spelling at all.
MSG
  exit 1
fi

echo "clean"
