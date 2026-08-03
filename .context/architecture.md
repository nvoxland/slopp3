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
  provenance stack is MILESTONE (commit_point: named, green-gated; carries
  a byte-exact rendered `:tree` snapshot — the git projection's input,
  P4-m8) ⊃ TURN (verbatim user ask, `turn_begin`/`turn_end`; enforced on
  real servers) ⊃ EPISODE (per-agent work-unit between DONE-POINTS —
  inferred, nothing stored; agents never plan grouping) ⊃ WRITE (one
  verified form-level delta). A DONE-POINT (`done {label}`, or the
  turn-end hook) closes the episode: normalize + declare hygiene + lint +
  affected tests over everything touched, findings recorded on the `:done`
  boundary delta and resurfaced by the next session's brief. CHANGESETS
  (rename, change_signature, normalize) are internal atomic multi-form
  ops — implementation detail, not an agent surface. Raw REPL eval may
  observe but never redefines code.

- **THE reference graph is the single source of truth for "who references
  what"** (`slopp.index.refs`). Every producer of reference knowledge
  NORMALIZES INTO it at the source — kondo-resolved statics, syntactically-
  qualified un-required calls, carrier positions (`query-call`/`invoke!`/
  `late-ref`), and marker declarations (`^:entry-point`/`^:unused-ok` as
  edges from `:external`) — and every consumer (module gates, the unused
  gate, review triage, debt/drift views, moves, renames, blast radius)
  queries `refs`/`refs-to` and NEVER re-integrates sources privately.
  Records are FORM-ANCHORED (`:from-form` is a stable form-id — semantic,
  not line/column); rewriters re-derive positions inside one form at
  rewrite time. The graph is DERIVED and content-memoized, never stored:
  references are an index of source, and the journal owes them no
  consistency. Adding a new reference kind = adding a producer HERE; a
  tool consuming kondo rows directly for reference questions is a bug.
  `refs` is MEMOIZED on store-value identity (immutable store → one build
  per version; `^:reads`), and all producers share ONE quote-aware
  traversal (`walk-pruned`).
  ALL consumers ported (epic closed 2026-07-16): the module gates
  (`ns-refs` slice — per-write affordable), unused/debt/drift, review
  triage, move-plan's direction+caller analysis, and query_impact blast
  radius (carrier refs and declarations now visible to agents; coverage
  via `observed-refs`, the trace map joining as `:via :observed`).
  STANCE-COMPLIANT NON-CONSUMERS: rewriters (rename-changeset) and
  planner node-walks (imports-for) re-derive positions/classes inside
  forms at rewrite time — their rightful domain, not a reference
  question; move-plan's external-LIB require selection keeps kondo (out
  of the store graph's domain by design).
- **Diagnostics speak anchors**: everything telling an agent to fix
  something carries `:form` (the owning qsym) plus `:at` (a match-ready
  snippet that pastes into edit_subform/query_slice match) — render
  row/col NEVER crosses the wire (reads are name-addressed, edits are
  anchor-addressed; coordinates are consumed internally by owner-form
  only). Compile errors translate once at the hot-load chokepoint
  (`edit/anchor-error` → `edit/compile-error`, the one shape every
  'failed to compile' surface returns); lint rows carry :at natively;
  test-failure detail strips the coordinate (the test is named). The
  invariant is ENFORCED, not trusted: `mcp/boundary-leak` + the
  `strict-boundary?` audit at `text!` (the universal response chokepoint)
  THROW on any `file.clj:line` or `:row`/`:col` reaching an agent — on
  across the wire test suite, so any tool that leaks a coordinate fails a
  test.
  **The wire never carries canonical records**: `to-wire` groups by target
  ({:to qsym :from [qsym ...] :tagged [...]} — :static implied, tags only
  for exceptions, ~3-5× slimmer). NAMES are the only reference currency on
  the wire (opaque ids fail unsafe; a mistyped name fails loudly) — short
  handles were built and deleted same-day as unconsumed surface; the
  version-locked variant that WOULD earn its keep is parked in
  `ideas/version-locked-references.md`. Convert at the boundary, one
  place.

## Components (2026-07-24)

Namespaces are grouped so that **a component IS a module** — modules are the
first two ns segments, so `slopp.store.db` is part of module `slopp.store`,
and a cross-component call needs a declared edge while an intra-component one
does not. Before this, 28 flat modules sat with no coarser grouping and sizes
160× apart; `slopp.lab` (2 forms) had the same standing as `slopp.api` (322).

The resulting graph is acyclic and nearly linear:

| Layer | Component | Holds |
|---|---|---|
| 0 | `boot` `rt` | the on-disk kernel — outside the component map by design |
| 0 | `cache` | the blessed memo |
| 0 | `web` | the app FRAMEWORK slopp ships to users; zero outgoing edges, by rule |
| 1 | `store` | `store` `.db` `.render` `.build` `.mine` `.semver` `.fields` `.merge` |
| 2 | `image` | `image` `.repl` `.testmain` — the oracle |
| 2 | `git` | `git` `.client` `.server` |
| 3 | `index` | `index` `.analyze` `.derive` `.deps` `.normalize` — static analysis |
| 4 | `edit` | `edit` `.refs` `.modules` `.hotload` `.lintgate` `.refactor` |
| 5 | `api` | operations + verification orchestration (22 namespaces) |
| 6 | `sync` | the git bridge — **stays top-level**: `slopp.sync/-main` is a published CLI entry in the plugin launcher, the install docs, a release blog post, and users' CI recipes |
| 6 | `ui` | `ui.model` `.pages` `.server` `.client.*` — slopp's OWN webapp: the reviewer view of a store, built on `slopp.web` exactly as a user's app would be |
| 7 | `mcp` | `mcp` `.tools` `.smells` `.http` `.turn` — the agent-facing transports |
| 8 | `bench` | `bench` `.benchmark` `.evalseed` — dev instruments, on no user path |

**`slopp.boot`, `slopp.rt` and `slopp.sync` are deliberately exempt.** Each is a
published interface (`java -jar slopp.jar`, `clojure -M -m slopp.boot`,
`slopp --main slopp.sync/-main import .`); renaming them breaks other people's
scripts to buy taxonomy.

**The framework/app boundary is load-bearing and mechanically enforced.**
`slopp.web.*` is what users depend on — `build.clj`'s slim `slopp-web` jar ships
exactly `slopp/web.clj` + `slopp/web/**`, and the `slopp.web` module declares
ZERO outgoing edges, so the gate refuses any call from the framework into
slopp's core. slopp's own webapp (`slopp.http-api`) depends on the framework the
way a user's app would, never the reverse.

**And since 2026-07-28 there is a stronger test of that boundary than a gate.**
The reviewer UI moved OUT, into its own repo and its own store (`../slopp-ui`,
D-ui-hub part 4). It depends on the published `slopp-web` artifact and nothing
else, and it reaches slopp projects only over HTTP — generating a typed client
from each one's `/api/contracts`. `slopp/store.clj` is not on its classpath at
all. What remains here is the API and its read performers — a project serves
`/api/*` and no HTML — and the module was renamed `slopp.ui` → `slopp.review`
to say so, since a module named for a UI it no longer contains is exactly the
kind of claim a reader trusts. That rename overshot: `review` is what
`slopp.api.review` (review_scan, risk triage) already meant, so one word named
two unrelated things across two modules. It is **`slopp.http-api`** since
2026-08-01 — not the UI, the API *for* it, which is what the first rename was
reaching for. A gate says the framework may not call into the
core; a separate process that cannot even load it says so louder, and it found
four real bugs in the first session (`ideas/ui-split-frictions.md`).

The cost accepted: ~80 namespace→namespace edges that were enforced when these
were separate modules (`db → store`, `render → store`) are now intra-component
and unchecked. Layering *within* a component is no longer a gate.

## Layer map (bottom-up)

| ns | Role |
|---|---|
| `slopp.store` | pure form store + delta log + episode snapshots (`sources-at`); public toolkit (`gen-id`, `now-ms`, `qform`, `suffix-touched`) serves the deep packages |
| `slopp.store.fields` | the fold-field/op REGISTRY (deep, :pure): one declaration per field/op drives empty-store seeds, THE shared fold (record/replay/merge), db meta rows, merge strategy, and the generated round-trip proof (D-fold-field-registry) |
| `slopp.store.merge` | the merge ENGINE (deep, world-exported): `merge-logs` delta-log replay with causal delivery, MV conflicts, semver auto-resolution, merge markers; unregistered ops REFUSE |
| `slopp.store.render` | VFS: store → source (lossless, memoized); `element-offsets` maps positions back to elements |
| `slopp.store.db` | the journal: SQLite WAL, conditional `append!`, `data-version`, `load-store`; `persist!` only for branch snapshots |
| `slopp.image.repl` | owned image subprocess: start!/eval!/eval-checked!/load-checked!/stop!; injects `slopp.rt` |
| `slopp.rt` | runtime support inside the image: traced (multi-ns) test runs + failure capture, observe |
| `slopp.image` | store↔image bridge: load-ns!, traced-test-run (dependency-closure instrumentation) |
| `slopp.index` | clj-kondo static index (content-fed): defs/refs/call graph, `!`-effect reachability, lint |
| `slopp.index.refs` | THE reference graph (deep, world-exported): canonical form-anchored records from every producer — static/carrier/declared; `refs`/`refs-to` are the only reference query surface. Every edge INSIDE the store |
| `slopp.index.crossings` | its outward pair: the registry of exit KINDS — what leaves, to where, `:checked-by`, `:blind` — plus the markers slopp owns that deliberately stay inside. Verifies nothing on purpose; makes exits ENUMERABLE so an unchecked one says so |
| `slopp.edit.refactor` | position-based structural rewrites (rename, extract, subform) |
| `slopp.edit` | write pipeline pieces: parse → dialect gate → hot-load; observe gate; pure-eval gate (query_store) |
| `slopp.edit.modules` | the module-RULES engine (deep, world-exported surface): membership (`module-of`, test-fold), recursive visibility + the `:export` dial, declared-edge checks, gate entry points (`module-refusal`/`module-scan`), manifest derivation — plus the gates policing what a module SURFACE must declare (purpose, docstring, boundary schema, namespaced keys), which need `module-of`/`export-level` to know what the surface is |
| `slopp.edit.gates` | the write-gate CHASSIS: `per-form-write-gates` (the registry every write site consults — register there, never at the N call sites), dispatch, `rule-severity` (the per-store dial), `gate-refusal` (the entry point). Knows all three gate families; none of them knows it |
| `slopp.edit.tiers` | purity TIERS and the layering they imply: `tier-refusal` per form, `layering-violations`/`tier-violations` for the whole-graph question the per-form gate deliberately skips. UNDECLARED is `:external`, by absence of a claim |
| `slopp.edit.web` | the D-web write gates + the store-value primitives they judge against (auth, routes, effect/context vocabulary, endpoint contracts, generated-client surface). Store-analysis of `:web/*` metadata, NOT the framework — `slopp.web` is layer 0 and knows nothing about stores. `slopp.rules.web` shares these primitives on purpose: a gate that refuses and a report that lists must be one derivation |
| `slopp.ops` | operations + verification orchestration; session atom = cache of one line (store, image, db conn, lines, trace map) |
| `slopp.webdev` | tooling for building a WEB project — app type #1, and named so type #2 needs no rename (R6): `webdev.live` (the server slopp runs for you so a project under development is always up) and `webdev.cljs` (the client build — ClojureScript on the JVM, plus the typed client generated from the endpoints' own contracts). Consumed ONLY by `slopp.mcp`, which is the transport and reaches every module; a generic surface reaching in here is the R6 violation `slopp.modules-test/web-tooling-is-reached-only-by-the-transport` exists to catch. NOT `slopp.web` — that is the layer-0 framework a user's app runs on, and mixing tooling under the prefix would make one mixed-layer module, since `module-of` is the first two segments |
| `slopp.read` | every question asked OF the store, one layer BELOW the operations that answer with it: `read.query` (the `query_*` front door), `read.history` (the store over time — status-at/after, resolve-at, verify-at, plus the human renderings), `read.orient` (`session_brief`, form cards, host warnings), `read.modules` (the module system's read side, against `slopp.edit.modules`'s write side), `read.telemetry` (the folds slopp measures itself with) |
| `slopp.mcp` | MCP over stdio; dispatch (`call-tool`/`handle`), the serve loop (+ tools/list_changed), wire shaping (spool/told/hints), turn gate |
| `slopp.mcp.tools` | the tool REGISTRY (deep): six descriptor groups, `read-only-tools` → readOnlyHint annotations, write-tool sets, the composed wire list, the cheat sheet |
| `slopp.mcp.smells` | workflow-smell machinery (deep): the smell registry, per-session counters, the hint chooser |
| `slopp.mcp.turn` | one-shot CLI for Claude Code hooks: verbatim-prompt turn markers appended out-of-band |
| `slopp.build` | explicit build: files + GraalVM native-image recipe (O4). Pure generators, zero internal requires — layer 0, because its three callers (`slopp.git`, `slopp.ops.external`, `slopp.webdev.cljs`) sit in different modules |
| `slopp.boot` | run a store's program straight from `store.db` (no exported source): load-string every ns into THIS jvm in dependency order (`*loaded-libs*` stamp = in-process `load-ns!`), then invoke the entry (default `slopp.mcp/-main`). `--snapshot` / `--live` (watches `data_version`, self-reloads). The on-disk kernel + `slopp.rt` are slopp-the-tool, not project source |
| `slopp.index.deps` | P4-deps: external-dependency ANALYSIS — resolve a dep's own jars (classpath diff) and extract its API surface (provided namespaces + var arities/docs/macro flags) via clj-kondo, content-addressed by `coord@version` |
| `slopp.store.semver` | tiny mvn-version parse + numeric compare (`newer?`); used by `merge-logs` to auto-resolve deps version divergence to the newer coord |
| `slopp.git` | P4-m8 git compatibility: the PROJECTION over one IN-MEMORY JGit repo (deterministic shas, `git_map` pinning, journal→commit projection, grafting onto `git-base-sha`). Exists to be PUSHED — serving it to a git client as a remote was removed |
| `slopp.git.client` | CLIENT face (deep): push the projection to a normal external remote / fetch a remote's objects; credentials; 30s transport timeouts |
| `slopp.sync` | git bridge orchestration (the store side, so IT depends on `slopp.ops`): `push!` store→remote (saves `git-remote` meta; refused while conflicts stand), `clone!` remote→FILELESS store (verified dependency-ordered ingest, deps manifest restored, `git-base-sha` recorded), `pull!` 3-way form-granular absorb (remote wins where we're clean; both-touched → off-log `quarantine` conflict; ends with a `:git-sha` chain marker); CLI `-main clone|push|pull` |
| `slopp.lab` | the instruments a HUMAN runs (R5), never the system: `lab.benchmark` (scripted sample-app wire-cost meter), `lab.evalseed` (seeds eval-round template codebases), `lab.mine` (demand mining over provenance journals), plus reference-query-cost on the root. None has a caller or a test, and that is the shared property rather than rot |

## Cross-cutting gotchas

- Store namespaces have **no classpath presence**; `load-ns!` marks
  `*loaded-libs*`. Cross-ns loads must be TOPOLOGICAL (`ns-dependency-order`
  — X3: map order goes hash past 8 entries and silently drops namespaces).
- **Not every ns loads into the oracle (D-web-cljs).** The `:platform`
  register (`:module-platforms`, namespace-grained like the purity tiers) marks
  a ns `:jvm` (default — loads), `:cljc` (loads AND compiles to JS), or `:cljs`
  (compiled to JS ONLY, NEVER loaded — it references `js/*`/DOM). So `load-ns!`,
  the write ops, and `build!` all consult `store/jvm-loadable?`: a `:cljs` write
  skips the hot-load and reports `:unverified :cljs-deferred-to-compile`, its
  oracle being `compile_client` (real ClojureScript, compiled on the JVM — no
  Node) instead of the test suite. Reading source, this is why a `:cljs` form's
  extension is `.cljs` under `cljs-src/` and it never appears in the image.
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
  the picture), the `:cycles` themselves, `:unused-edges` (declared
  but no call uses them — the retire-direction drift the debt view can't
  see) and `:overstated-edges`. One kondo pass feeds debt and drift both.
  **`:layers`/`:cycles` compute over PRODUCTION edges only**
  (`api/production-manifest`): a `-test` namespace folds into its subject
  module, so its fixture deps would manufacture cycles that don't exist in
  production; `:manifest` (declared/enforced) still carries them.
- **`:overstated-edges` is what the unused report structurally cannot see.**
  A production edge only `-test` namespaces cross: something DOES cross it,
  so "declared but no call uses it" is false, yet the manifest asserts a
  dependency production doesn't have. `api.modules/overstated-edges` is the
  unused report's sibling, and it reuses `production-manifest` rather than
  spelling a second is-this-a-test predicate — which also gives it the
  restriction for free, since that map keys exactly the modules WITH
  production code (an all-test module can only ever be crossed by tests, so
  asking there produced 80 rows against 4 real ones). It reports rather than
  gates, same genre as unused; what makes it more than tidiness is that the
  CYCLE check reads declared edges, so an overstated one refuses a legitimate
  declaration in an unrelated module. Four stood here on 2026-08-02 and
  `slopp.index → slopp.mcp` blocked the `slopp.rules` regroup.
- **Cross-module calls need a DECLARED edge.** The manifest is NOT a file:
  it is the fold of `:module-edge` deltas — edge-grain CRDT (concurrent
  declarations union; `merge-logs` folds them without conflict, and
  `api.modules/merge-production-cycle` NOTES a cycle neither side saw).
  Writes go through the semantic verb
  `module_dep {from to [remove] prompt}` — an add that CLOSES a cycle is
  refused, the why rides the delta. The cycle question is asked of the
  **PRODUCTION** graph, the same `production-manifest` the layer view
  above uses, so a `-test` namespace's fixture require can never veto an
  architecture decision. It used to ask the DECLARED manifest, which meant
  the gate and the architecture view disagreed: `slopp.mcp → slopp.ui` was
  refused for closing `ui → api → index → mcp → ui`, where the
  `index → mcp` hop existed solely because `slopp.index.deps-test` calls
  `slopp.mcp/handle!` — while `query_depends` showed a clean nine-layer
  DAG. `module_extract` already judged on production edges; `module_dep`
  now matches (`slopp.modules-test/cycle-refusal-judges-production-edges-not-test-fixtures`);
  and the MERGE note was the last holdout, warning on every merge into
  main about `api → edit → image → store → api`, a cycle owed entirely to
  `slopp.store.db-test` requiring `slopp.api`. Its advice — retract an
  edge — would have broken that test. **`slopp.store.merge` cannot ask the
  question at all**: at that layer a store is a delta log and some maps,
  with no notion of which namespaces are tests, so the check moved UP to
  `slopp.ops.branch`, which takes `production-manifest` of the store the
  merge produced. It fires only when the merge actually GAINED an edge — a
  standing cycle re-announced on every unrelated merge is noise, not news.
  Reads go through `query_depends {modules true}` (manifest +
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
- **The gates are not the whole enforcement, because a RELOCATION never
  passes one.** A rename rewrites its own callers, so the rewritten callers
  are never written through a gate — and the module rules are inherited from
  the NAME, so the drift is silent. Two folds close it, both over
  `api.modules/module-debt` (whole-graph, ~0.9s on 188 nses; the per-ns
  `module-scan` × 188 is 4.9s for the same answer): `full_check` reports
  `:module-violations` store-wide, and `done` reports `:module-governance`
  scoped to the episode's relocation deltas. Both error-grade — a violation
  still standing is one a write gate would have REFUSED, so advisory would
  make the write gate the stricter of the two. `tier-governance` is the same
  fix one system over and predates it; see the discipline in
  `design-disciplines.md`. **On the module side the reported namespace is the
  CALLER, which did not move** — two segments to three makes the TARGET
  package-private — so unlike the tier check this one selects violations from
  EITHER end of the episode's moves.
- **A TEST-ONLY edge is its own relation** (`:module-test-edges`, op
  `:module-test-edge`, `module_dep {test-only true}`). `module-of` folds a
  trailing `-test` off each segment, so a fixture shares its subject's module
  key — one edge would license production too. It is separate rather than
  nested inside `:modules` precisely so `:modules` keeps meaning PRODUCTION
  edges: the cycle check, `query_depends`' layers, `store/module-path` and the
  projected `modules` file are all that graph and are untouched. A test edge
  is therefore not a production edge, is never a cycle, and
  `module-violations` honours it only when the caller is a test —
  **production under that module is still refused, which is the guarantee the
  old shape never gave.** `derive-module-edges` classifies, so adoption and
  `module_extract` stop writing a fixture's crossing as a dependency the
  project does not have.

  Settled 2026-08-02 (user) because a done-time advisory can ONLY be tested by
  writing code and calling `done!` — the fixture necessarily calls the
  operation surface that calls the rules — so phase 1b's `slopp.rules` and
  `slopp.read` had no other way out: 41 deftests / 243 call sites would
  otherwise have become permanent violations. The two edges slopp already
  carried (`slopp.index → slopp.ops`, `slopp.store → slopp.ops`, both with
  ZERO production callers) were the same rule failing to be expressible,
  written down twice; they are now declared test-only and the store has **no
  declared-manifest cycles at all**, which makes gating on that possible for
  the first time. `ns_rename`'s manifest re-key still does no cycle check —
  the remaining half of `ideas/restructure-wave-frictions.md` #20.

  **A test-only edge is re-keyed by NOTHING, which is its own hazard.** When a
  rename empties a module, `ns_rename` relocates the PRODUCTION manifest entry
  (measured: `slopp.api`'s eight deps arrived at `slopp.webdev` already
  declared, and `slopp.mcp → slopp.api` re-keyed to `slopp.mcp →
  slopp.webdev`) — and leaves every test-only edge spelled the old way, in
  both directions. Phase 3 found six: `slopp.api → slopp.read`, which had to
  be re-declared by hand as `slopp.webdev → slopp.read` while the original sat
  beside it, and four `X → slopp.api` survivors of module 4's rename. Nothing
  reports them, because an edge naming a module with no namespaces is merely
  unused. That is harmless right up until the NAME IS REUSED — phase 2 gives
  `slopp.api` to the external API — at which point six dead entries silently
  become live permissions for something they were never about. Retired in
  phase 3; the general fix belongs with #20.
