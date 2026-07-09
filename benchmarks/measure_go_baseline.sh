#!/bin/bash
# Measure a conventionally-built (files + go test) baseline project produced by
# an instrumented sub-agent (git commit per write; test runs tee'd to .runs/).
# Metrics mirror slopp's benchmark: tokens ~ chars/4.
#   tok-in  = authored content submitted per write: sum over commits of the
#             byte sizes of the *.go files changed in that commit
#             (whole-file granularity -- that IS the conventional workflow)
#   tok-out = bytes of go tool output the agent had to read (.runs/run-*.txt)
#   wall-s  = .runs/t1 - .runs/t0 (includes the agent's own thinking time;
#             see results.md caveats)
# Usage: measure_go_baseline.sh <project-dir>   ->   "wall-s tok-in tok-out runs"
set -e
cd "$1"
t0=$(cat .runs/t0); t1=$(cat .runs/t1)
bytes_in=0
for c in $(git rev-list HEAD); do
  for f in $(git diff-tree --no-commit-id --name-only -r "$c" | grep '\.go$' || true); do
    sz=$(git cat-file -s "$c:$f" 2>/dev/null || echo 0)
    bytes_in=$((bytes_in + sz))
  done
done
bytes_out=$(cat .runs/run-*.txt 2>/dev/null | wc -c | tr -d ' ')
runs=$(ls .runs/run-*.txt 2>/dev/null | wc -l | tr -d ' ')
echo "$((t1 - t0)) $((bytes_in / 4)) $((bytes_out / 4)) $runs"
