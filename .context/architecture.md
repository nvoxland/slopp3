# Architecture

## The stance (don't violate without a recorded decision)

- **The top-level form is THE unit** — of editing, CRDT/storage, hot-reload,
  verification, and provenance. One atom all the way down. Anything that
  splits those units apart (file-based edits, whole-project reloads) works
  against the thesis. (`edit_subform` is content-addressed sugar that still
  commits a whole form.)
- **No `.clj` files on disk.** The store is the source code; a VFS renders
  source on demand (`query-source`); explicit `build!` materializes files
  only when asked. No file→store reconciliation, ever.
- **The SQLite journal is the record of truth** (m5a inversion). Durable
  commits are conditional appends (deltas + touched element rows + id
  counter, ONE tx, iff the head still matches the commit's base); the
  in-memory store is a cache that only ever trails the journal. Losers
  refresh + rebase. There is no persist queue — the append IS the persist.
  Ephemeral (api-level) sessions commit to the cache alone.
- **Many servers, one store dir, is the operating model** (m5b/c): each
  agent's own MCP server (spawned by its `.mcp.json`) shares the journal;
  `data_version` + `sync-with-journal!` absorb foreign commits (cache, live
  image, trace invalidation) before every tool call. Same-form races surface
  `{:conflict}` to the stale writer; different-form work rebases and lands.
- **The system owns persistent JVM images** (nREPL subprocesses; P1:
  out-of-process for kill/exit/restart guarantees). The image is the L3
  oracle: behavior questions answered by observation (eval, tracing).
  Refresh is the fast path; restart is the always-correct backstop; reds are
  cross-checked only when staleness is plausible (D5.1).
- **Branches are lines; images belong to lines** (m3/m4): a branch is an
  O(1) store snapshot with a name + uuid line-id, persisted as its own mini
  journal under `.slopp/branches/<name>/`; checkout is per-SERVER state.
  Switching parks the line's image intact (adopt on return; idle-reaped).
  `branch_merge`/`merge_from` = delta-log replay with causal delivery
  (`:applied`/`:id-map`/`:merged-from`, scoped per source) — different-form
  work lands, identical changes converge, same-form divergence is an MV
  conflict (ours live, theirs surfaced).
- **Every write is a tracked delta** `{op, ns, prompt, agent, at, ...}`; the
  provenance stack is COMMIT POINT (named milestone, green-gated,
  `commit_point`; carries a byte-exact rendered `:tree` snapshot — the git
  projection's input, P4-m8) → TURN (verbatim user
  ask, `turn_begin`/`turn_end`; enforced on real servers) → EPISODE
  (per-agent work-unit between checkpoints, derived — nothing stored) →
  step → per-form version. Raw REPL eval may observe but never redefines
  code.

## Layer map (bottom-up)

| ns | Role |
|---|---|
| `slopp.store` | pure form store + delta log + merge engine (`merge-logs`) + episode snapshots (`sources-at`) |
| `slopp.render` | VFS: store → source (lossless, memoized); `element-offsets` maps positions back to elements |
| `slopp.db` | the journal: SQLite WAL, conditional `append!`, `data-version`, `load-store`; `persist!` only for branch snapshots |
| `slopp.repl` | owned image subprocess: start!/eval!/eval-checked!/load-checked!/stop!; injects `slopp.rt` |
| `slopp.rt` | runtime support inside the image: traced (multi-ns) test runs + failure capture, observe |
| `slopp.image` | store↔image bridge: load-ns!, traced-test-run (dependency-closure instrumentation) |
| `slopp.index` | clj-kondo static index (content-fed): defs/refs/call graph, `!`-effect reachability, lint |
| `slopp.refactor` | position-based structural rewrites (rename, extract, subform) |
| `slopp.edit` | write pipeline pieces: parse → dialect gate → hot-load; observe gate |
| `slopp.api` | operations + verification orchestration; session atom = cache of one line (store, image, db conn, lines, trace map) |
| `slopp.mcp` | MCP over stdio; tool schemas, hints, turn gate; `handle` is pure dispatch |
| `slopp.http` | same dispatch over localhost HTTP: `/call` (curl), `/mcp` (native MCP, shared-server mode), `/metrics` |
| `slopp.turn` | one-shot CLI for Claude Code hooks: verbatim-prompt turn markers appended out-of-band |
| `slopp.build` | explicit build: files + GraalVM native-image recipe (O4) |
| `slopp.deps` | P4-deps: external-dependency ANALYSIS — resolve a dep's own jars (classpath diff) and extract its API surface (provided namespaces + var arities/docs/macro flags) via clj-kondo, content-addressed by `coord@version` |
| `slopp.semver` | tiny mvn-version parse + numeric compare (`newer?`); used by `merge-logs` to auto-resolve deps version divergence to the newer coord |
| `slopp.git` | P4-m8 git compatibility: generates `:commit` milestones into an IN-MEMORY git repo (JGit `InMemoryRepository`, deterministic shas, `git_map` pinning) served READ-ONLY (clone/fetch) over smart-HTTP; no on-disk repo, no push-import |
| `slopp.bench` / `slopp.benchmark` | metrics / scripted sample-app benchmark |

## Cross-cutting gotchas

- Store namespaces have **no classpath presence**; `load-ns!` marks
  `*loaded-libs*`. Cross-ns loads must be TOPOLOGICAL (`ns-dependency-order`
  — X3: map order goes hash past 8 entries and silently drops namespaces).
- **External deps are Tier 1 (P4-deps):** the owned image is otherwise bare
  (Clojure + nREPL). A store declares its own libs in a `:deps` manifest
  (lib→coord) that reaches every image launch via `-Sdeps`
  (`repl/default-cmd`) and hot-`add-libs` (`image-with-deps!` reconciles the
  bare spare), and feeds a complete generated `deps.edn`. Store code may
  `(:require ...)` a declared dep; its *body* stays opaque (not analyzed,
  effects worst-case — M3). The manifest is a tracked delta stream
  (`:deps-add`/`:deps-remove`) materialized to a `meta` row.
- The rendered source is the coordinate system: kondo rows/cols are
  positions in `render-ns` output, translated back via `element-offsets`.
- Image work is serialized per-eval by the single nREPL session; keep
  multi-step image operations inside ONE eval (traced-run does).
- Delta ids are monotonic per line — two forks/branches from one point mint
  COLLIDING ids. Everything cross-line therefore keys on causal bookkeeping
  (never value/id identity): `:applied`, `:id-map`, `:merged-from`, and the
  recreated-source guard.
- Host language is Clojure/JVM by decision H1; the CRDT is Clojure —
  **no Rust planned**.
