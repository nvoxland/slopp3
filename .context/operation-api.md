# Operation API (`slopp.ops` + `slopp.mcp`)

## Session

An atom: `{:store <value> :image <handle> :db <conn|nil> :test-map {...}
:warm-spare? bool :spare <future|nil>}`.
- `open!` → ephemeral; `open! {:dir d}` → durable (loads store AND replays
  namespaces into the image); `{:warm-spare? true}` for cheap restarts (the
  MCP server sets it).
- `close!` stops image + spare + db. Never leak child JVMs.

## Read surface (form-addressed; never file+line)

`query-source` (VFS render) · `query-symbol` (id, name, `:effectful?`,
source) · `query-references` · `query-lineage` (deltas matching `:form-id`
or membership in `:form-ids`) · `query-eval` (**observe-only** oracle access —
by convention it must not redefine code; redefinition belongs to edit ops).

## History views (the granularity ladder)

commit points → turns → episodes → span diffs → forms, each row carrying
the ids to drill into the next: `query-commits` (rows carry `:sha`, the
milestone's git commit id, once the P4-m8 projection has minted it) /
`query-history {collapse
true}` (COMMIT rows with description + status; turn brackets with the
verbatim intent + nested episode rows; `:contains` searches turn INTENTS,
not just episode labels) → `query-changes {:from/:to | :agent}` (net
`:was`/`:now` per form + red/green arc) → `query-lineage` /
`query-form-history`. Human renderings on top of the same data: every
history row carries `:at` (`yyyy-MM-dd HH:mm`, local zone — the raw epoch
ms stays in the store); `query-history {format "text"}` is the story view;
`query-changes {format "text"}` renders LINE diffs (LCS — context lines are
never re-emitted as churn). EDN stays the agent-facing default.

## Review triage (`review_scan`)

`review-scan` (tool `review_scan`) is the fileless store's answer to
"where do I look first" in a whole-codebase review — the store knows what
files don't. One analysis pass builds the call graph + lint, then every
form is scored on review-relevant signal and RISK-RANKED: `:untested`
(STATIC — not reachable from any test namespace in the call graph, so it
survives `^:external` tests that never touch the in-image trace map; the
trace refines it when warm), `:unused` (public defn/def with ZERO
in-store callers — dead code or unadvertised/entry surface; whole scans
only, since a scoped graph can't see every caller; `-main`, privates, and
test nses exempt — `done` raises the same signal for TOUCHED nses as the
findings advisory `:unused-public`, and kondo's `unused-private-var`
covers privates per-ns), `:high-blast` (many callers), `:large`,
`:lint`, `:undocumented` (public surface), `:effectful` (`!`). Clean forms
drop out; `:top` rows carry `:form/:risk/:flags/:callers/:covered`, drill
in with `query_slice`. `:ns` scopes to a namespace. Lesson baked in
(dogfooding): a trace-only `:untested` signal reports an isolated-test
codebase as ~100% untested — static call-graph reachability is the fix.

## Semantic × history depth (roadmap #5 — "the moat")

Queries over the journal that git can't represent — form granularity ×
verified history:
- `query-form-at {ns name :at}` — **TIME-TRAVEL**: a form's source exactly
  as it stood at delta `at` (a delta id OR a commit-point id → its
  `:target`). Names resolve AS OF that delta (`fid-ns-at` + parse-back via
  `store/name-of-source`), so a later-renamed form still answers to the name
  it had then; a form absent at that point is an honest `{:error}`, never a
  guess. Exact, not reconstructed — each version's source is stored verbatim
  (`store/sources-at`). Carries `:status` = the was-green-at state
  (`status-at`) governing that point.
- `query-status-at {:at}` — **WAS-GREEN-AT**: the verification state
  (`:green`/`:red`/`:unknown`) that GOVERNED delta `at` (a delta or
  commit-point id) — the last `:verify` at or before it (`status-at`), plus
  the governing `:verify` delta id.
- `query-form-history` versions now carry `:status` too — but via
  `status-after` (the verify a version PRODUCED, "did this version land
  green", the first verify AT OR AFTER the delta), not `status-at`'s
  standing-at-a-point reading. Two genuinely different questions; keep them
  distinct.
- `query-search-history {pattern :limit}` — **DELTA-LOG SEARCH** ("which
  prompts touched auth?"): case-insensitive substring over each delta's
  prompt, done label, commit/turn description, turn-end note, AND its
  enclosing turn intent (`turn-intents`), newest-first. Each hit carries the
  forms it touched (ns/name qsyms, names resolved as of that delta) + `:at`
  — drill in with `query-form-at`/`query-lineage`. Distinct from
  `query-history :contains` (that's episode/turn rows; this is log-wide,
  form-addressed, intent-aware).
- `query-form-history {:format "text"}` — one form's LIFE as a per-version
  LINE-diff story (`render-form-history-text`): each version's header (delta,
  op, the prompt/intent, its green/red, when) + the diff FROM the previous
  version (reuses `diff-lines`). EDN rows also now carry `:at`. The
  agent-facing default stays EDN.

Ordering note: these forms must be DEFINED above their callers, which is no
longer anyone's job to maintain — the write pipeline reorders definitions
itself and mints any `^{:auto-declare …}` it needs, and a hand-written
`declare` is refused. The cold-load gate is what catches a bad order now, at
write time, instead of a fresh JVM catching it later.

## Write surface (each = tracked delta(s) + hot-reload + verification + provenance)

- `create-ns!` — the public new-namespace op and the ONLY creation tool
  (`ns_create`). TWO mutually-exclusive modes: `:requires` (clause strings)
  scaffolds an empty ns to grow form-by-form with TDD (the default for new
  behavior); `:source` (the whole namespace text) lands it in one verified call
  (ported/reference/data code). Threads `:agent` for provenance.
- `ingest!` — the shared engine `create-ns!` delegates to: load a whole
  namespace from source. Runs the D3/D4 dialect gate over every form first (via
  `edit/dialect-scan`, the same check the edit path applies) — a host form must
  already be `^:unsafe` or the whole ingest is rejected before the image is
  touched, so imported code is never frozen. Returns `{:ns :forms :warnings
  :test}` (the `:warnings` are the `!`-effect warnings it used to swallow) or
  `{:error}` (never throws on bad source). Internal only — NOT its own tool
  (folded into `ns_create`); also the load path for git-import and seeds.
- `add-require!` — structural, dup-checked require addition through the replace
  pipeline. Prefer these over hand-ingesting/replacing ns forms.
- `edit-replace!` — whole-form replace (O1); the common "semantic patch" path.
- `add-form!` / `delete-form!` — grow/shrink a namespace (delete `ns-unmap`s).
- `rename!` — coordinated multi-form rename; see `slopp.edit.refactor` notes below.
- `change-signature!` (P2, tool `change_signature`) — the defn + every CALL
  site as ONE intent: `source` replaces the defn (same name), each call's
  arg list is rebuilt from the `calls` template ($1..$9 = the site's
  existing arg sources; the callee stays as written, so aliases survive).
  Planned in `refactor/change-signature-plan`, executed via `edit-group!`
  (all gates, one verification). Higher-order references return under
  `:manual`; nested self-call sites / template-arity misses are hard errors
  (rewrite those sites with `edit_subform`). Companion: OWN-FORM
  invalid-arity refusals carry the change_signature hint; stale-CALLER
  arity errors don't refuse at all — they ride `:carried-errors` until
  the done-point.
- `edit-group!` — INTERNAL changeset machinery only (no wire tool): several
  steps applied to a store VALUE, committed + hot-reloaded together, verified
  once, deltas sharing a `:group` id. Used by rename-sweep!,
  change-signature!, revert paths, and normalize. Agents make individual
  writes; episodes group them. RULE: pipeline-critical signature changes
  (anything the write path itself calls) MUST go through a changeset —
  an incremental signature change to the pipeline deadlocks it (see
  decisions.md, self-hosting lesson).
- `test-run!` — traced+diagnosed run; `ns-sym` nil = the WHOLE project in
  one image eval (instrumentation paid once — F-3c1); refreshes the trace
  map. The WIRE tool guards this: a bare `test_run {}` returns GUIDANCE
  (name `:ns`/`:only` to spot-check; done runs the affected tests itself),
  `{all true}` runs the whole in-image suite explicitly with a
  done-covers-it note, `{isolated true}` is the merge gate. Surgical by
  default, whole-suite by explicit request. `query-eval` surfaces evaluation errors as `{:error msg}` (F-3c2);
  `query-references` scans every namespace (F-3c3). `query-eval` strips
  `:reload`/`:reload-all` from `require`/`use` forms (`edit/strip-image-reload`):
  the image has no source files, so a store ns is loaded via `load-ns!` not the
  classpath, and the muscle-memory `(require 'the.ns :reload)` would otherwise
  throw FileNotFoundException instead of the intended no-op.
- Red-first TDD is native and COMMAND-AGNOSTIC: the seam is the compile
  gate itself (`stub-missing-test-vars!`), not any write op. When a
  `-test` namespace fails to load — through `hot-load-all!` (single
  writes, groups, renames), `ingest!`/`ns_create`, `open!`, or a fresh
  image — every store var it references but doesn't define gets a
  throwing stub interned in the image (kondo rows for aliased/qualified
  calls; the ns form's `:refer` vectors for bare names, since stubs
  precede the require) and the load retries: the spec lands as an honest
  red with `:red-first` naming the vars (carried on the wire). Restarts
  and reopens with stubs outstanding survive the same way. Future write
  paths inherit all of this by construction — anything that compiles
  through the image is covered. The isolated suite (fresh JVM, no image)
  still refuses until implementation — the short red-first window is the
  point.
- `external-test-run!` extras: `:parallel` shards a full/affected run
  across JVMs (one build, round-robin ns shards, merged summary — 1.9×
  at N=4). Defaults to AUTO (`auto-parallel`: serial below ~8 test nses,
  else n/8 capped at 4 and half the cores); explicit N overrides, a
  single `:ns`/`:only` run never shards. `:affected true` = the provable slice
  (test namespaces whose require-closure reaches a form changed since
  the last milestone; empty slice returns a note, full suite stays the
  milestone gate); narrowed runs use the generated `:test-run` alias
  (no baked `-r` — cognitect's runner UNIONS -r with -n, which silently
  defeated `:ns` narrowing before). Red results carry `:failing`,
  `:all-failing` {file [tests]}, and `:themes` (cause phrases clustered
  by distinct-test coverage).
- `done!` — THE done-point (see decisions.md terminology). Deterministically
  normalizes every form changed since the last done point (`slopp.index.normalize`,
  conservative kibit-style rules, node-level so inner formatting survives),
  commits ONE `:normalize` group delta, hot-reloads + re-verifies affected
  tests, records a labeled `:done` delta. Never rewrites silently
  mid-edit — only at this explicit call. Add rules deliberately (they must be
  provably behavior-preserving) and note them in the normalize ns.
- `commit-point!` — MILESTONE (P4-m7): the done pipeline, then a
  `:commit` marker at the result with a human `description`. Green-gated
  (`:force` records `:status :red` honestly); `:target` = retroactive pure
  marker (no `:tree`). Since P4-m8 the marker snapshots the rendered
  `:tree` ({ns source}, byte-exact, sorted-map) — the git projection's
  input — and `:extra` plumbs op-specific payload (imports add `:git-sha`).
  `query-commits` lists them; commit `:target`s anchor query-changes
  `:from`/`:to` spans. Projection/serving live in `slopp.git`, NOT here —
  the write path stays JGit-free.
- `deps-add!` / `deps-remove!` / `deps-list` — the external dependency
  manifest (Tier 1, P4-deps). `deps-add!` records a `:deps-add` delta then
  HOT-adds the coord to the running image via `repl/add-libs!`
  (Clojure 1.12 `clojure.repl.deps/add-libs`, no restart; restart fallback on
  failure) — so store code requiring the lib compiles. `deps-remove!` always
  restarts (a jar can't unload). The manifest reaches ALL image launches
  (`image-with-deps!` reconciles the bare warm-spare via add-libs) and the
  generated `deps.edn` (`build/deps-edn` now takes the manifest; empty is
  byte-identical to before so the `ours?` guard holds; `*print-namespace-maps*`
  is bound OFF for determinism). On add, `slopp.index.deps` analyzes the dep's own
  jars (classpath diff → clj-kondo) into an API SURFACE (provided namespaces +
  per-var arities/docs/macro flags), memoized per `coord@version` (process
  memo + durable `dep_surface` table); `deps-add!` returns `:namespaces` +
  `:vars` count. MCP: `deps_add {lib version|coord}`, `deps_remove`,
  `deps_list`, `deps_pure {target pure?}` — assert a dep pure (narrow M3's
  effectful-by-default boundary) at var / namespace / whole-lib granularity
  (a lib expands to every namespace it provides; see `dependencies.md`).
- `module-dep!` / MCP `module_dep {from to [remove] prompt}` — the ONLY
  way the module manifest changes: one `:module-edge` delta per
  declare/retract (edge-grain CRDT — concurrent declarations union in
  merges; the why rides the delta). Adds are cycle-checked against the
  resulting graph; results carry the module's folded dep set plus any
  standing `:violations` debt. A cycle refusal whose only crossing
  namespaces are `-test` says so and names them — the generic "extract the
  shared piece" cannot be done to a fixture (friction 19b). `config_file "modules"` is refused and
  teaches this verb; the manifest reads via `query_depends {modules
  true}` — which also carries the GRAPH: `:layers` (topological, SCC-
  condensed via `store/module-layers`), `:cycles`, `:unused-edges`
  (declared-but-unused drift), `:overstated-edges` (declared for production
  but only `-test` namespaces cross — invisible to the unused report because
  something DOES cross, and load-bearing because the cycle check believes the
  declaration) — (add `on <module>` for that module's
  SURFACE — public fns + exported deep vars with sig/doc/level, deps,
  consumers; `api/module-surface`)
  and projects into commits/builds as a `modules` file. The `:export`
  dial on a defn's name: true = world surface; `"prefix"` string =
  visible to that subtree only. The
  module GATE (recursive visibility + declared edges, kondo-resolved
  over the candidate store) rides `replace-form`/`add-form!`/group
  steps/`ingest!`/`ns_create`; `ns-rename!`/`rename_sweep` re-key
  manifest entries when a module's last ns renames away; adoption
  (pre-module dbs at `open!`, `clone!` after ingest) derives the
  manifest from the actual graph. See `architecture.md` § module system.
  **A RELOCATION is the one write the gate cannot see** — `ns-rename!`
  rewrites its callers through `store/apply-changeset`, one coordinated
  delta running no gates, so a crossing that would be refused if typed is
  created silently. Two things answer for that, at different grains:
  `rules/module-governance-check` catches it at done (scoped to the
  episode's relocations, from either end of the edge), and
  `edit.modules/relocation-debt` reports it at the rename itself, as
  `ns-rename!`'s `:module-debt` — `:edges-needed` grouped to the
  `module_dep` calls to make with `:test-only` derived from who actually
  crosses, `:visibility`, and `:cycles` that declaring would close. It
  reads `store-violations`, so the two can never disagree; simulating the
  rename instead would be a second derivation of an existing rule.
- `move-forms!` / MCP `edit_move_forms {ns forms to [export]}` — the
  general relocation refactor (v2, replacing `extract-ns!`): move forms to
  a NEW or EXISTING namespace; callers EVERYWHERE (production + tests) are
  rewritten alias-qualified with requires injected; the target gets only
  the requires the moved code uses; moved privates are publicized
  (module-grain visibility is the boundary); direction-aware (stay→moved
  requires back; moved→stay qualifies public stay refs; two-way refuses);
  cross-module edges the rewires necessitate are auto-declared with the
  move's prompt (cycle-closers refuse); `export: true` hoists moved vars
  for a deep target with outside callers. Pure planner
  `refactor/move-plan` (unit-tested), atomic executor. Limits (refused or
  compile-gated): `:refer`'d moved names, java `:import`, shadowed-local
  mis-qualification.
  **`:shadowed` is the one limit that is neither refused nor compile-gated**,
  and it is the reason the two shadow directions are not one entry. A moved
  form's refs INTO the target go BARE (the target gets no self-alias), so
  `base/x` becomes `x` — and if that form binds a LOCAL named `x`, the result
  is valid Clojure that calls the local. It compiles, the suite stays green,
  and the behaviour changed. The mirror (qualify turning a bare stay-callee
  into `from/x`) rewrites the binding vector too and therefore fails at
  compile, which is why that one is a documented limit and this one is a
  REPORT: `move-plan` returns `:shadowed` rows `{:form :was :now}` — always
  present, empty or not — and `move-forms!` carries them plus a
  `:shadowed-note` saying the code compiles. The detector is
  `slopp.edit/local-name?`, the same one the D3 refusal uses, made public for
  it. It has no scope tracking and over-matches by design, which bounds what
  it may do: report, never refuse — under a refusal that over-match turns
  from a spurious warning into a legitimate move blocked with no way through.
  **Every `:module-rows` entry is `{:from-ns :from-var :to :to-name}`** —
  the CALL, in both directions. The destination rows carried no callee
  until 2026-08-03 (only `moved→stay` rows did, spelled `:name`), and three
  reports were each one fact short of actionable because of it:
  `:export-not-landed` looked up a var named nil and reported every LANDED
  export as unlanded (39 phantoms on one move), `export: true` could only
  be applied to the whole moved set at once, and a `:visibility` refusal
  said "mark the target `^:export`" while naming only the namespace. All
  three read the row now; `export` WIDENS per var rather than replacing,
  read off `from-ns` where the var still lives.
  **A NEW target is seeded with the source's purity tier** when the source
  DECLARED one and the target would otherwise be governed differently —
  recorded before the ingest, so the moved forms are verified against it on
  the way in. Undeclared is `:external` by absence of a claim, so without
  this a split dropped forms out of a `:pure` core into the shell and
  `full_check` reported the core→shell edge against the namespace that had
  NOT changed. An undeclared source mints nothing: stamping `:external`
  would defeat a deliberate move INTO a pure subtree, where the right
  outcome is the gate refusing impure forms. `ns_rename` needs no
  equivalent — it RELOCATES a declaration rather than copying it, and the
  asymmetry between the two relocation verbs was invisible until a
  whole-store check. `full_check`'s `:tier-layering` rows now carry
  `:requires-undeclared` for the same reason: `:external` by absence and
  `:external` by declaration read identically, and only the first has a
  one-call fix.
- `query-store` / MCP `query_store {code [timeout_ms]}` — the STORE-VALUE
  oracle: one read-only `(fn [store] ...)` evaluated over the current
  immutable store value IN THE SERVER (where the value lives — nothing is
  serialized to the image). The sanctioned home for ad-hoc
  codebase-as-data analysis; `query_eval` stays the oracle for code
  BEHAVIOR. Gated hard: single fn-of-store shape, `edit/pure-eval-refusal`
  (quote-pruned walk — no `!`-enders, defs, interop, IO/eval), worker
  thread + timeout (a wedged analysis can't freeze the serve loop),
  results pr-str-capped at 32KB. Safe by construction against writes: the
  store value is immutable and the eval only ever holds the pointer.
- `restart!` — agent-callable fresh image (D5 escape hatch).
- `build!` — materialize `.clj` files (the C1/C6 explicit build): production
  namespaces under `src/`, **test namespaces (name ends `-test`) under `test/`**
  (`render/source-path`) — a normal Clojure layout. When any test namespace
  exists the generated deps.edn gains a `:test {:extra-paths ["test"]}` alias so
  `test/` is runnable (off the default classpath). With
  `:main` (qualified entry fn) it also emits the O4 native-binary recipe:
  a generated launcher, a `:native` deps alias, and `build-native.sh`
  (user runs it; needs GraalVM 21+ on PATH). Generators live in
  `slopp.build`; X4 guards apply, plus: a deps.edn the build didn't
  generate is never overwritten. **Native-compat gate (M6):** each manifest
  dep's jars are scanned for `META-INF/native-image/**` (GraalVM reachability
  metadata) → `:declared`/`:none` verdict (cached in `dep_surface.native`); a
  metadata-less dep surfaces as `:native {:warnings … :metadata-missing […]}`
  (may need a tracing-agent run), and a dep on the (currently empty)
  `native-incompatible-deps` denylist REFUSES the native build unless
  `:force true`.

Every edit ends with `run-verification!` (affected-narrowed, diagnosed) and a
`:verify` delta. Result shape: `{:delta :warnings :test :affected}` +
`{:error msg}` on validation failure. **Keep return shapes tidy maps** —
every op, `ingest!` included (`{:ns :forms}` / `{:error}`), returns one (F8).

**Verification belongs to the TRANSACTION, not to the verb (2026-07-24).**
A composite built by sequencing user-facing verbs used to pay one verification
per verb — `module_extract` ran N × `ns-rename!`, each re-verifying, and a
three-namespace extraction ran past 465s. `ns-rename!` takes `:defer-verify`,
and `module-extract!` runs ONE `run-verification!` over the union of the
touched namespaces, **strictly after the whole rename set has landed AND after
the derived module edges are declared** — verifying earlier judges an
intermediate store the gate itself would refuse (namespaces renamed, callers
not yet rewritten, edges not yet declared). Any new composite should compose
the same way. The per-rename `fresh-image!` is NOT deferred and remains the
other half of #9's cost: the old namespace must not linger, and skipping it
would hot-load later renames into an image missing the earlier ones.

## Concurrency (item 4 — CRDT-aligned, no locks)

Single-form writes (replace/add/delete/move) commit through
`rebased-write!`: the pure store transform runs INSIDE `swap!`, so concurrent
DIFFERENT-form writes rebase and all land (the granularity dodge, made real);
if the target form itself changed since the op began → `{:conflict ...}`
(C5's MV-register semantics, Phase-1 face). The compile gate runs once before
commit (form content is invariant across rebases). Multi-form ops
(group/rename/extract/done) guard with conflict-on-contention rather
than rebasing. Persistence is ORDERED via a per-session agent
(`persist-async!` — element rows derive from the current store at execution
time, so they never regress); `close!` awaits the queue. The image needs no
locking: all image work rides ONE nREPL session (per-eval serialization) and
`traced-run`/hot-loads are single evals — keep multi-step image work inside
one eval. `*pre-commit-hook*` is the deterministic test seam.

## `slopp.edit.refactor` (rename mechanics)

Position-based: clj-kondo gives resolved sites; **use `:name-row`/`:name-col`
for usages** (`:row`/`:col` point at the CALL's paren, not the symbol —
learned the hard way). Sites → owning element via `render/element-offsets` →
element-local positions → rewrite-clj position-tracked zipper replaces exactly
those tokens (descending order so positions stay valid). Shadowed locals are
never touched because kondo never reports them as var usages.
**Limitation:** symbols inside `:refer` vectors aren't var-usages → not
rewritten.

**`requalify-keys` matches the destructuring entry on the FROM qualifier**,
never on the symbol. `{:a/keys [x]}` names `:a/x` and `{:keys [x]}` names
`:x`; a `:keys` vector writes the key as a SYMBOL with the qualifier one
position to the left, so a pass matching the symbol alone is wrong in BOTH
directions at once — it skipped the qualified destructurings while renaming
`:a/x` and rewrote the unqualified ones, changing which key they read. That
was one missing check, and it broke seven forms through a green verification
on 2026-08-03; `keys-binding` is now the single definition of "this
destructuring names that key", shared by the rewrite and by
`destructures-key?`. `rename_sweep` reports both halves — `:requalified` for
what it restructured (a keyword rename's diff should not contain a semantic
change silently) and `:left-behind` for what it DECLINED. It declines a
NAME change: the symbol is a local binding the body reads, so only the
qualifier can be moved for you.

## Transports

MCP is served over STDIO, and only stdio (D-mcp-stdio-only, 2026-08-01).
There was an HTTP transport sharing the same `mcp/handle` dispatch — `/call`
for curl, `/mcp` for native MCP over streamable HTTP, `/metrics` for payload
sizes — and it is retired. Its reason for existing was N agents sharing one
server, which is explicitly not wanted; nothing else depended on it (the
benchmark calls `mcp/handle!` in-process, `--call` is a one-shot CLI), and
its store-backed static reader moved to `api.web/store-reader`.

- **MCP stdio** (`clojure -M -m slopp.mcp [dir]`) — Claude Code and Codex
  (`config.toml` recipe in README). Optional dir = durable session. The
  in-repo `.mcp.json` runs it THROUGH `slopp.kernel.boot` (`-m slopp.kernel.boot . --snapshot`)
  so slopp serves from its own store, no exported source — see
  "Running from the store" below.
- ~~**Git smart-HTTP**~~ — **REMOVED 2026-08-02.** Serving the store to a git
  client AS a remote (`slopp.git.server`, the embedded listener `slopp.mcp/-main`
  opened on a dir-derived port, `query_git`'s `:git-url`, and the
  `refs/heads/wip/<branch>` mirror of un-milestone'd state) is gone. It forced
  exact-project handling that got complex for what it bought, and it carried
  the third `derived-port` implementation — the salt in
  `api.server/derived-port` exists to dodge a port nothing binds now.
  Git as slopp supports it is **push/pull to a repo slopp does not own**:
  `git_push`, `git_pull`, `git_clone` (`slopp.git` projects, `slopp.git.client`
  transports, `slopp.sync` orchestrates).
  STILL TRUE and worth keeping: **keep `slopp.git` reflection-free** — reflective
  JGit calls resolve classes via the per-thread classloader (only visible under
  add-lib REPLs, but the hints also keep the hot path cheap).

## MCP transport (`slopp.mcp`)

- Minimal JSON-RPC 2.0 over newline-delimited stdio; pure `handle` dispatch
  (testable with plain maps) + `serve!` loop. Entry:
  `clojure -M -m slopp.mcp`.
- Tool names use underscores (MCP name charset). Tool results = `pr-str`'d
  tidy maps in one text content block; tool exceptions → `isError` result,
  protocol errors → JSON-RPC errors.
- When adding an api op, add: tool schema (in the matching per-group
  registry def — `orientation-tools` / `history-tools` / `edit-tools` /
  `flow-tools` / `env-tools` / `sync-tools`; `tools` just concatenates
  them, Q4) + a dispatch entry (hot query/edit ops: `call-tool`'s case;
  stable env/file/sync ops: the matching `*-handlers!` map of
  `(fn [session a sym])`) + (usually) a `select-keys` whitelist of the
  result.
- **Result diet (Q1/Q8, `summarize`):** green-and-quiet writes return the
  terse `{:ok true …}` shape; `:untested` is a terse FLAG (never a reason
  to go verbose); a delta's `:source`/`:sources` are stripped from EVERY
  write result (the agent just sent that text); a zero-test verification
  carries `:coverage :none`. Anything over the size gate is trimmed and
  spooled — `query_detail {id}` returns the full version.
- **The series runs itself (Q10/Q11, revised 2026-07-14):** `commit_point`
  in a git checkout MIRRORS the projection into local git as
  `slopp/<store-branch>` and reports `:published {:branch ...}` (errors
  ride along; the milestone never fails on mirror trouble). Remote
  publishing is explicit (`git_push`; first URL saved as default, never
  rewritten by one-off pushes).
  `edit_rename` results carry `:mentions` — prose/string occurrences of
  the old name the structural rename can't rewrite — and the internal
  `edit-group!` changeset steps include `:subform` (+`:text`) and
  `:require`, so a rewriter's follow-up fixes ride one atomic group.
- **Rock 4 reads:** `query-flow` (boundary-guarded keyword scan — every
  form a field touches, with lines) and `query-impact` (kondo var-usages:
  `:arity` present = call site, absent = value/higher-order ref; plus
  trace-map coverage). Impact is change_signature's discovery as a read —
  plan the edit before paying for it.
- **Alignment is proven, not asserted (Q12):** `query_commits` carries
  `:alignment` (local-remote branch head vs the latest milestone's minted
  sha) so handoff audits are one trusted read — the eval8 trust spiral
  (worktrees, raw sqlite, duplicate runs) was the demand signal.
- **One door per question (consolidation, 2026-07-14):** dependency
  questions enter through `query_depends {on direction}`; history
  questions through arg-routed `query_history`; twelve specialized wire
  tools retired (api fns remain for internal composition). When adding a
  read capability, extend a door's routing before minting a tool.
- **Errors teach (Q5/Q9):** refusals name the next action in tool
  vocabulary — the cold-load gate is the bar. Shared pieces:
  `edit/missing-form-error` (near-miss names or the outline pointer, used
  by every no-such-form site) and the subform matcher's fragment refusal
  (match COMPLETE forms / the enclosing form). Keep new errors to that
  standard.

## Git bridge tools (`git_push` / `git_clone` / `query_git`)

- `git_push {url? token? branch?}` → `sync/push!`: project + push the store's
  milestone history to a normal remote as real files. `url` once (saved as
  `git-remote` meta), reused after. Fast-forward only. Durable sessions only.
- `git_clone {url dir token?}` → `sync/clone!`: rebuild a FILELESS store from
  a remote at `dir` (no `.clj` materialized); records `git-remote` +
  `git-base-sha` so pushes from the clone fast-forward. Also a CLI:
  `clojure -M -m slopp.sync clone <url> <dir> | push <dir> [url]`.
- `git_pull {token? agent?}` → `sync/pull!`: 3-way absorb of remote changes
  (remote wins where we're clean; both-touched → quarantined conflict, our
  version stays live). Ends with a `:git-sha` chain marker so later pushes
  fast-forward.
- `git_conflicts {}` → unresolved pull conflicts (path, ns, reason, RAW
  remote source to merge from). `git_resolve {path?}` clears one (or all) —
  unblocks `git_push`.
- `query_git` reports `:external` (`git-remote`/`git-base-sha`) when set, and
  otherwise refuses naming how to set one. It used to lead with a local
  listener URL; there is no listener.
- CLI (fileless tree — everything enters through the boot trampoline):
  `clojure -M -m slopp.kernel.boot <dir> --main slopp.sync/-main
  clone <url> <dir> | push <dir> [url] | pull <dir>`. Auth:
  `SLOPP_GIT_TOKEN=$(gh auth token)` env on the command. Proven against
  real GitHub (nvoxland/slopp3): push → API edit → pull → FF push.

## Running from the store (`slopp.kernel.boot`)

- The entry `clojure -M -m slopp.kernel.boot <dir> [--snapshot|--live]` runs the
  store's program WITHOUT exported source: `load-store!` reads every ns's
  byte-exact source with raw next.jdbc, `dependency-order`s them (parses ns
  requires — a self-contained mirror of `store/ns-dependency-order`), and
  `load-string`s each into THIS jvm with a `*loaded-libs*` stamp (in-process
  `image/load-ns!`), then invokes `--main` (default `slopp.mcp/-main <dir>`).
  This is what `.mcp.json` runs. `slopp.kernel.boot` is self-contained (next.jdbc +
  core only) so it can bootstrap slopp itself; keep it that way.
- **Serve-time auto-import** (cost-cut round, 2026-07-13): `mcp/-main` calls
  `sync/maybe-auto-import!` first — a git checkout carrying a `slopp` branch
  whose store is absent or EMPTY imports itself before the session opens
  (the slopp BRANCH is the marker; plain git repos are never touched;
  failures are non-blocking). Empty stores (the server's own footprint) are
  valid clone/import targets. Session-cost economics (same round):
  `query_source {targets [{ns name?}…]}` batches orientation reads; tool
  descriptions dieted ~65% (schemas defer to the skill + `help`); workflow
  hints fire once per session; turns are normally opened/closed by the
  PLUGIN's hooks (`mcp_tool` hooks → turn_begin with the verbatim prompt /
  turn_end on Stop) — the turn gate stays as the manual fallback.
- `--call <tool> [args]` (P3) — ONE tool call with no MCP connection: sugar
  for `--main slopp.mcp/call-main! <dir> <tool> [args]`. Args are JSON, EDN,
  or `@file` (`mcp/parse-call-args`); result text on stdout, exit 1 on tool
  error. `mcp/call!` is the engine: open durable session (turns enforced —
  turn state is in the store, so `turn_begin` in one invocation covers the
  next), one `call-tool` dispatch, close. For scripts, CI, and degraded
  agent sessions; `slopp.kernel.boot` itself stays kernel-only (the sugar just
  rewrites argv — `call-main!` resolves from the loaded store).
- `--snapshot` (default) freezes a version at startup. `--live` runs
  `watch-live!`: poll `db data_version`, and on a foreign commit `load-string`
  the changed namespaces into the running host (the host tracks its own
  store). Safe because the core is plain-fn/immutable-map and the store's
  green-gate admits only compiling code; caveat — long-lived instances
  (reaper, git `HttpHandler`) keep old code until re-created. See D-series R.
- `build!` MATERIALIZES the store to files (for tooling/native-image);
  `boot` RUNS it in place. Two exits from the store, same source of truth.
- `config {key value?}` — store config: user.name/user.email, the git author
  identity stamped ON the milestone marker at commit_point time (G5);
  unset or "<git>" defers to `git config` in the project dir. Determinism:
  identity lives on the marker, never read from config at projection time.

## The capability registry (`slopp.project.capabilities`, D-web wave 0)

The `capabilities` config file is the store's APP MANIFEST + opt-in surface
(what kind of application this is: name/version/entry point, whether it
serves HTTP, ports, auth providers, groups). It rides the ordinary `:config`
CRDT (G9) — the new thing is the DECLARED registry: one entry per key
(`{:key :type :default :doc}`, wildcards like `web.auth.static.*` /
`web.auth.groups.*.members`), from which everything derives:

- `find-entry`/`check-value` — `config_file {path "capabilities"}` VALIDATES
  at write: unknown keys and type-failing values refuse with teaching
  (`config-refusal`). A typo'd key must never silently do nothing.
- `effective store k` — parsed stored value else the declared default; a
  registered key with a default never nil-puns.
- `report` → `query_capabilities` (read-only tool): every setting with
  default, effective value, and provenance; wildcard families as
  `:patterns`. The `query_rules` shape, applied to configuration.
- `build!` falls back to `app.main`/`app.name` when its args are absent —
  the entry point is store state, not a tool argument.

`:type` is a small STRUCTURAL vocabulary (`:string :boolean :int :enum
:set-of :qualified-symbol :csv`), deliberately not malli — this ns loads in
the server/boot JVM (kernel deps only; the two-process split). Extending the
vocabulary = a case in `check-value` + the parser in `effective`, in one ns.

## The app server slopp runs for you (`slopp.webdev.live`)

A web project under development should always have a live version up, and the
app should hold no `serve!` call, no namespace list and no port to get it.
Everything needed is already in the store: `web.enabled` says it is a web
project, the endpoint + performer surface says what to serve, the capability
registry says where.

The namespace splits DECIDING from RUNNING, and the split is why most of it
has ordinary in-image tests instead of a JVM apiece:

- `managed? store` — whether slopp should RUN this store's server, which is
  not the same question as whether the store serves HTTP. `web.enabled` is
  read by production and by the web rules; `dev.server` (registry default
  `true`) is the dev lifecycle's own opt-out. They came apart on the first
  store anyone tried: **slopp's own web surface IS the MCP HTTP transport
  plus the reviewer API, which the live session already serves over the LIVE
  store** — so a managed server there would boot a second image and serve a
  snapshot of the page you are looking at, one done point behind it. slopp's
  store sets `dev.server false`. (Not a port conflict — the transport is a
  separate entry point and is usually not running. `web.port` means ONE
  thing, the port a web app's server binds; slopp's own APIs are an INSTANCE
  of that, so its stored 7357 is a correct declaration and `serve-plan`
  reading it is the primary use. What is wrong runs the other way:
  slopp has no web APP at all — the reviewer API is a distinct custom API,
  not this project's web surface.)
  Kept OUT of
  `serve-plan` deliberately: a dev-only opt-out does not belong in the answer
  production reads.
- `serve-plan store dir` → `{:enabled? :mode :namespaces :host :port :adapter}`
  or `{:enabled? false :reason …}`. `:namespaces` comes from
  `api.web/serving-namespaces` — derived, never declared. `:port` prefers a
  stored `web.port` and otherwise `derived-port`, salted per project for the
  reason `review.server/derived-port` records (one MCP process binds them all).
- `load-order store` — the transitive closure of the web surface in dependency
  order, not the whole store. A store namespace has no classpath presence, so
  a dependent loaded first would require its way out and fail.
- `serve-code plan` → the string the image evaluates. The opts are QUOTED
  (generated forms land in evaluated position, and a namespace symbol there
  reads as a class name), and it returns the BOUND PORT — an integer, so a
  throw, which `repl/eval!` returns as a string, cannot read as success.
- `start!`/`stop!`/`refresh!` — the lifecycle, over a `boot!` (image + load,
  no socket) / `serve-in!` (bind) pair. A failure is a sentence, never a
  throw, and the image is stopped on every failing path.

`refresh!` is the DONE-grain swap, and the asymmetry in it is the design:
the new version is verified on LOADING, before the old one is stopped. A
boot that fails at done grain almost always fails because the code does not
compile, and that is decided before a socket is involved — so a red store
leaves the previous version answering and the session's `:app-server`
untouched. "Always up" and "up to date" only conflict when a boot fails, and
that is the answer. Mid-episode incompleteness is exactly why the grain is
`done` and not the write: reloading a browser into a half-written red state
trains the author to ignore it.

**The app gets its OWN image.** `session/fresh-image!` sits on the path of
`edit-replace!`, `rename!`, `move-forms!`, `deps-add!` and
`merge-into-session!`, so an app served from the oracle would be killed by a
refactor that never touched it. The two want opposite things — the oracle
CURRENT and disposable, the app STABLE and pinned — and the oracle's
requirement is the one that cannot move. The dedicated image also runs on the
STORE's dependency manifest rather than slopp's, which makes the dev server a
check on the published surface rather than only a convenience.

Two constraints that are easy to get wrong and expensive to find:

- **Launch through `session/start-image!`, never `repl/start!`.** The door
  brings `image-deps` and the vendored `framework-dir!` with it; a local
  launch reproduces "a spare launched in its own dir has nothing vendored,
  and a JVM cannot pick up a relative classpath directory after launch".
- **Load with `image/load-ns-into!`, never `load-ns!`.** `currency/stamps` is
  one process-global atom describing THE ORACLE — see
  `.context/verification.md`. Stamping a second image's loads into it reports
  forms as current in a process the oracle never saw.

Nothing starts this automatically yet. The done-grain refresh, the browser
reload endpoint, the cljs rebuild and the production main are still open.
