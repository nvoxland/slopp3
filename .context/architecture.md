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
- **The store is RUN, not just materialized** (`slopp.boot`). A tiny
  self-contained kernel loads every namespace's byte-exact source straight
  from `store.db` into the JVM (in dependency order) and invokes the entry
  point — no exported project source needed. `--snapshot` freezes a version
  at startup; `--live` tracks the store's `data_version` and hot-reloads
  changed namespaces into the running process. General: any store runs from
  its db; slopp running itself is the self-host instance. See
  `.context/operation-api.md`.
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
| `slopp.boot` | run a store's program straight from `store.db` (no exported source): load-string every ns into THIS jvm in dependency order (`*loaded-libs*` stamp = in-process `load-ns!`), then invoke the entry (default `slopp.mcp/-main`). `--snapshot` / `--live` (watches `data_version`, self-reloads). The on-disk kernel + `slopp.rt` are slopp-the-tool, not project source |
| `slopp.deps` | P4-deps: external-dependency ANALYSIS — resolve a dep's own jars (classpath diff) and extract its API surface (provided namespaces + var arities/docs/macro flags) via clj-kondo, content-addressed by `coord@version` |
| `slopp.semver` | tiny mvn-version parse + numeric compare (`newer?`); used by `merge-logs` to auto-resolve deps version divergence to the newer coord |
| `slopp.git` | P4-m8 git compatibility, two faces over one IN-MEMORY JGit repo (deterministic shas, `git_map` pinning, no on-disk repo): SERVER — milestones served READ-ONLY (clone/fetch) over local smart-HTTP; CLIENT — push the projection to a NORMAL external remote / fetch a remote tip+tree (byte-moving only, no `slopp.api` dep). A cloned store's chain GRAFTS onto its `git-base-sha` so pushes stay fast-forward |
| `slopp.sync` | git bridge orchestration (the store side, so IT depends on `slopp.api`): `push!` store→remote (saves `git-remote` meta; refused while conflicts stand), `clone!` remote→FILELESS store (verified dependency-ordered ingest, deps manifest restored, `git-base-sha` recorded), `pull!` 3-way form-granular absorb (remote wins where we're clean; both-touched → off-log `quarantine` conflict; ends with a `:git-sha` chain marker); CLI `-main clone|push|pull` |
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

## The module system (enforced architecture)

- **Module = the first two ns segments** (`logi.parcel`); a trailing
  `-test` folds into the subject's module (`x.y-test` → `x.y`), so TDD
  needs no ceremony. Deeper namespaces (`x.y.z`) are **package-private**:
  callable only from namespaces sharing the parent prefix — unless the
  var's defn widens it with the `:export` dial: `^:export` (true) hoists
  it into the module's WORLD surface; `^{:export "prefix"}` exposes it to
  callers under that subtree only (within-project widening without going
  public). Definition-site, no potemkin, no facade ns — the gate checks
  resolved var-usage rows, so the var keeps its one real address.
  Browse a module's surface (public fns + exported deep vars with
  sig/doc, deps, consumers): `query_depends {modules true, on "x.y"}`;
  the bare `{modules true}` view carries the GRAPH: topological `:layers`
  (cycles condensed via SCC so they share a layer instead of poisoning
  the picture), the `:cycles` themselves, and `:unused-edges` (declared
  but no call uses them — the retire-direction drift the debt view can't
  see). One kondo pass feeds debt and drift both.
- **Cross-module calls need a DECLARED edge.** The manifest is NOT a file:
  it is the fold of `:module-edge` deltas — edge-grain CRDT (concurrent
  declarations union; `merge-logs` folds them without conflict and NOTES a
  cycle neither side saw). Writes go through the semantic verb
  `module_dep {from to [remove] prompt}` — an add that CLOSES a cycle is
  refused (LOCAL reachability check; adopted test-fold cycles like
  api↔db never block unrelated declarations), the why rides the delta;
  reads through `query_depends {modules true}` (manifest +
  standing debt). The manifest projects into git commits/builds as a
  `modules` file (read-only transparency).
- **Enforcement is on from birth** (`empty-store` has `:modules {}`); the
  first cross-module call teaches declare-then-use. A populated store
  whose db predates the system (`:modules` nil) is ADOPTED at `open!`:
  the manifest derives from the actual kondo-resolved graph — acyclic
  with zero violations by construction, so adoption never breaks working
  code; the gate then blocks drift. `clone!` ingests with the gate off
  (`:adopting?`) and adopts what landed.
- **Gates** ride the existing write pipeline (`replace-form`, `add-form!`,
  group steps, `ingest!`/`ns_create`) over the CANDIDATE store via kondo
  `var-usages` (so `:refer`'d calls count). Refusals teach the exact fix.
  `ns-rename!`/`rename_sweep` re-key the manifest automatically when a
  module's last ns renames away. Public-surface defns without docstrings
  get a per-form advisory on the WRITE result (only on the has-doc→no-doc
  transition or brand-new forms — never a ns-wide nag).
