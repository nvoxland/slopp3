# Roadmap (beyond dogfood findings)

**Status 2026-07-02:** #1 ✅ (plugins for Claude Code + Codex, HTTP transport,
portability) · #2 ✅ (outline/namespaces) · #3 ✅ (query_observe +
query_macroexpand) · #4 partially ✅ (extract shipped alongside rename/move;
inline/change-signature + the published CODESTRUCT-style eval remain) ·
#5 ✅ (semantic×history depth SHIPPED 2026-07-04: form-at-delta,
was-green-at, delta-log search, form-history diffs) · #6 open (Phase-4;
much shipped — see decisions.md m3–m8).
The symmetric-eval harness (benchmarks/results.md) is the standing measure;
next eval round should be a LARGER modify-and-extend task, slopp's favorable
terrain.

The functional goal: an agent-native codebase that is *measurably* better to
author in than text files — fewer tokens to orient, faster verified loops,
trustworthy history. Ordered by leverage:

## 1. Become a real MCP server in an agent harness (product moment of truth)
Wire `slopp.mcp` into Claude Code as an installed MCP server and author through
it as *tools* (not REPL calls). This is the true product surface and the
near-zero-config install story — DESIGN.md §5 calls install friction the
single biggest risk. Includes: config recipe, session lifecycle over long
conversations, concurrent-client behavior, portability (drop the hardcoded
homebrew `clojure` path).

## 2. Orientation queries (the token thesis, part 2)
Reading is solved form-by-form; *orientation* isn't. Add: `query_outline`
(namespace map: names, arities, `!`-status, one-liners — clj-surgeon's `:ls`
lesson), expose the existing call graph as `query_graph`, and
`query_namespaces` (what exists?). Prediction from calculator: multi-namespace
projects make this the next friction leader.

## 3. Deepen the L3 oracle (cash the D2 promise)
"Ask the REPL" today = raw `query_eval`. Add first-class observation:
`query_observe` (call a fn / run a test while capturing arg+return shapes at
a target var — the dynamic-typing safety net D2 leans on), and a
`macroexpand` tool. Runtime answers to "what flows through here?" without
reading callers.

## 4. Phase-3 structural ops + behavior preservation
`extract-fn` next (highest value after rename), then inline/move/change-
signature. Verified by execution: snapshot outputs → transform → re-run →
diff (the DESIGN.md §7 oracle). Also the CODESTRUCT-style eval: rename/edit
correctness + token cost vs. a string-replace/grep baseline, once the op set
is broad enough to be worth publishing numbers for.

## 5. Semantic × history depth (the novel core) — ✅ SHIPPED 2026-07-04
The moat, built (P4-m8-adjacent, `slopp.history-test`): **form-at-delta**
(`query_form_at` — a form's source exactly as it stood at any delta or
commit-point, names resolved as of then), **was-green-at**
(`query_status_at` + per-version `:status` on `query_form_history` via
`status-after`), **delta-log search** (`query_search_history` — "which
prompts touched auth?", prompt/intent/label/description, form-addressed
hits), and **form-history diffs** (`query_form_history {:format "text"}` —
one form's life as a per-version LINE-diff story). All read-only over the
journal slopp already records — the "semantic×history combination nobody has
shipped" (DESIGN.md §5). Remaining wisps (lower priority): whole-namespace
render-at-delta (lossy; forms are exact), and cost/token accounting per
delta (`ideas/todo.md`).

## 6. Phase-4: multi-agent / branch / merge
The deferred CRDT half (C4/C5): concurrent sessions as peers, branch/merge
over the delta DAG, form-sequence CRDT algorithm, MV-register same-form
conflicts, globally-unique ids (drop the monotonic counter). In Clojure (H1).
Big; starts after the single-agent loop is polished under real use.

## Housekeeping (whenever touched)
Topological namespace load order on restart; defonce-preserving refresh
(D5's deferred perf opt); fold decisions back into `DESIGN.md` (or mark it
historical, `.context/` is authoritative); MCP request-level concurrency.
