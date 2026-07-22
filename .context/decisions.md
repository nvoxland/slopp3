# Decision log

Settled decisions. Don't re-litigate silently — revisit explicitly and record
the change here (same commit).

## D — dialect & verification philosophy

- **D1 — No `@examples` / "deterministic choke point".** Old slopp's mechanism
  presumed an untrusted black-box compile step; slopp2's agent authors real,
  readable forms with a live oracle. Verification = tests + REPL observation.
  Form-granularity comes from **runtime tracing** (which forms did each test
  exercise), not co-located examples.
- **D2 — Contracts: data-dynamism by default; instrumented open schemas MAY be
  enforced at the module-external boundary (amended 2026-07-17, see D9).** Shape
  vs. behavior are different lanes; tests+REPL own behavior/requirements, and
  internal/private code leans INTO Clojure's data dynamism — the live oracle is
  what makes loose args safe for a limited-context agent, so schemas are NEVER
  required there. The original D2 read *"nothing contract-library-specific is
  built in (no Malli/spec coupling); never enforced by the system, anywhere."*
  That clause is RELAXED for ONE locus — the exported / module-external boundary
  (the single place a slice-limited agent can't see producer and consumer
  together) — under four conditions that make a schema help rather than tax:
  boundary-scoped (exports only, privates stay schema-free), open by default
  (accretion preserved — Hickey's require-less/provide-more), oracle-instrumented
  (drift becomes a RED test, not a silent lie — why the `^:covers` marker was
  rejected but this is safe), and generative. Malli coupling accepted because
  malli schemas are plain EDN data — they round-trip through the form store like
  any value. Full rationale + roadmap: D9 +
  `ideas/agent-native-best-practice-gates.md`. **SHIPPED 2026-07-17, both
  channels** (see `.context/dialect.md` § Schema oracle-check): a written `:=>`
  `:malli/schema` (on the defn name) is generatively oracle-checked against its
  live impl at `done!` — drift is a red `:schema-drift` finding, never a silent
  lie (`slopp.api.schema`); and an opt-in per-form write gate
  (`edit.modules/schema-refusal`, off by default) can REQUIRE that schema on a
  module-external map-arg fn. Verify shipped before require, so a required schema
  is always one the oracle checks. Schemas stay optional to write, verified once
  written.
- **D3 — Dialect = allow-by-default with a denylist** (analysis defeaters:
  `eval`, `alter-var-root`, `binding`, `gen-class`, `definline`,
  `read-string`; extended with the resolvers 2026-07-16 and the metadata
  mutators `alter-meta!`/`reset-meta!` 2026-07-18 — both amendments below).
  Keep data dynamism; constrain metaprogramming dynamism.
- **D4 — User macros banned** (`defmacro` rejected). Built-in macros fine;
  runtime `macroexpand` remains the oracle for those.
- **D5 — No purity rule; refresh-vs-restart on an owned process.** Refresh is
  the fast path; restart = always-faithful backstop. Warm spare keeps restarts
  off the critical path. Detection is sampling; external side effects are out
  of scope.
- **D5.1 (user-flagged) — Smart red diagnosis, not restart-on-every-red.**
  A red cross-checks on a fresh image ONLY when staleness is plausible:
  (a) reload-signature failures (unbound var / unbound fn / no protocol impl /
  same-named-class CCE); (b) an unexplained flip — a failing test whose traced
  form-set doesn't intersect the just-edited forms (also catches value-capture
  staleness, since captured calls bypass the trace); (c) missing trace info or
  truncated failures. Otherwise `{:diagnosis :genuine}` — one run, no restart.
  Compile-gate failures heal the same way: refresh + one retry (`:image-healed`).
  `test_run {:fresh true}` forces a faithful single run; `restart` remains.
- **P1 — The oracle stays OUT-of-process (asked and answered).** Subprocess
  isolation is load-bearing for agent-generated code: guaranteed kills for
  runaway/OOM evals, `System/exit` containment (no SecurityManager on 21+),
  and D5's "fresh process = faithful by construction" purity (classloaders
  leak statics/hooks/natives). Loopback nREPL RTT is not a measured cost;
  spawn cost is amortized by the warm spare. Revisit only if we ever want
  fleets of parallel throwaway read-only oracles (isolated-classloader mode).
- **D6 — `!` naming enforced as a static effect-marker.** A fn must be
  `!`-named iff it transitively reaches an effectful leaf (call-graph
  propagation via clj-kondo; sound for first-order code; HOFs are the known
  leak, covered by runtime observation). Scope = **modification** (in-process
  mutation + external writes), NOT reads/non-determinism. Open question F7:
  stdout (`println`) is currently unflagged — matches Clojure convention, but
  needs an explicit scope call.
- **D7 — Hand-written `(declare …)` is banned; the pipeline owns form order**
  (2026-07-16). A same-ns forward ref is resolved by REORDERING definitions
  above their callers (Kahn over THE reference graph); a genuine cycle gets a
  MARKED `^{:auto-declare "<why>"}` declare the pipeline inserts itself. So a
  hand-written declare is always redundant → refused with teaching. Lives in
  `parse-form`, NOT `dialect-check`, so it binds the EDIT path only: imports
  (`dialect-scan`) keep their declares, and the pipeline's own inserts (raw
  parser, via `edit/declare-node`) don't trip the gate they enforce. Unlike
  D3/D4 this isn't an analysis defeater — it's the first prune of a human
  convenience the pipeline can fully own (`ideas/dialect-prunes-human-conveniences.md`).
  Mechanics: `.context/verification.md` S1b + `.context/dialect.md`.

## C — storage core

- **C1 — Purely virtual: no on-disk `.clj` by default.** VFS renders from the
  store; explicit `build!` materializes. No reconciliation loop exists.
- **C2 — Identity = opaque synthetic stable ids** (survive rename/edit;
  monotonic counter now, globally-unique ids when multi-agent arrives).
- **C3 — Form-version value = rewrite-clj CST**; canonical serialization is
  the source text (lossless re-parse).
- **C4 — Delta-log-first**: event-sourced log now; concurrent-merge CRDT
  algorithm deferred to Phase 4.
- **C5 — Same-form concurrency = MV-register** (surface conflicts), Phase 4.
- **C6 — External tools: in-process + explicit build; no FUSE dependency.**
- **C7 — Persistence = SQLite** (`.slopp/store.db`, WAL, one tx per mutation:
  delta row + touched namespaces' element rows + id counter). EDN remains the
  value representation (delta payload column). The store IS the source code —
  it gets a real storage engine, not hand-rolled EDN files.

## O — operation API

- **O1 — Write model = whole-form replace** + structural ops layered
  (rename; extract/inline/move later). No sub-form patch language.
- **O2 — Edits auto-run affected tests** (trace-map narrowed; conservative
  full-ns fallback), result recorded on the delta.
- **O3 — Query = static index + runtime oracle from day one** (`query-eval`).
- **O4 — Native-binary build target.** `build!` with `:main` emits a GraalVM
  native-image recipe alongside the sources: a generated gen-class launcher
  (`src/native/main.clj`), a `:native` deps alias (graal-build-time +
  direct linking), and an executable `build-native.sh`. The compile itself
  stays an explicit user-run step — slopp never shells out to GraalVM. The
  launcher's `gen-class` is host-generated scaffolding, NOT authored store
  code, so it sits outside the D3 gate (same standing as `slopp.rt`'s
  instrumentation). The dialect is what makes the target reliable: D3/D4's
  bans (eval, read-string, gen-class, user macros) are exactly native-image's
  closed-world assumptions. Launcher arg passing is arity-aware via the
  index: a single fixed arity of 1 receives the CLI args as one vector;
  anything else is `apply`'d -main style.

## P4 — Phase 4 (multi-agent) — MOVED

See `.context/roadmap.md` § Phase 4. The `P4-m*` / `P4-deps` milestone
names other docs cite still resolve there.
## H — host

- **H1 — slopp itself is Clojure/JVM** (same runtime as image + tooling; no
  serialization wall to the oracle; in-process clj-kondo/rewrite-clj). CRDT
  will be built in Clojure; **Rust FFI is a last-resort escape hatch only,
  never planned**. Distribution concerns → GraalVM/babashka later if needed.

## F/T/S-E/R/X-N/B — user-test & eval findings — MOVED

See `.context/findings-log.md`.
## R — run-from-store (`slopp.boot`)

R1 ✅ **A store's program runs directly from `store.db`, no exported source.**
`slopp.boot` reads every ns's byte-exact source via raw next.jdbc
(`SELECT ns, source FROM elements ORDER BY ns, pos` — the same bytes
`render-ns` emits), computes dependency order by parsing each ns form's
internal requires (a self-contained mirror of `store/ns-dependency-order` —
it can't use the store value, that's the code it's loading), then
`load-string`s each ns into THIS jvm with a `*loaded-libs*` stamp (the
in-process twin of `image/load-ns!`; the stamp is load-bearing — store nses
have no `.clj` on the classpath, so an un-stamped internal `(require …)`
would `FileNotFoundException`). Then it invokes the entry (`--main`, default
`slopp.mcp/-main`). `build!` (files) and `boot` (run) are the two exits from
the store; boot is the general counterpart — **the store is executable, not
just materializable.**

R2 ✅ **Two modes behind a switch.** `--snapshot` (default, safe) freezes a
version at startup; restart to advance. `--live` spawns a watcher that polls
`data_version` (the exact foreign-commit detector `sync-with-journal!` uses)
and, on a bump, `load-string`s the namespaces whose rendered source changed
into the running process — the host tracks its own store. Chosen because
slopp's core is protocol/record/multimethod-free plain-fn-over-immutable-map
code (var-indirection makes redefinition safe) and the green-gate guarantees
only compiling code is ever in the store to load. Documented residual
hazards (live only): the three long-lived instances (reaper `TimerTask`,
two git `HttpHandler`s) keep old closure code until re-created, and an
in-flight request finishes on the old fn body. Snapshot has none of these.

## G — git bridge (forms-as-truth, files-in-git, in-memory client)

G1 ✅ **The shared repo holds FILES; the local dir holds FORMS.** A normal
git remote (GitHub etc.) carries real `.clj` files + a generated `deps.edn`
— browsable, PR-able, useful to non-slopp users; the local working dir holds
`.slopp/store.db` and NO project source (nothing for an agent to hand-edit,
nothing to drift). `git_push` publishes the milestone projection;
`git_clone`/`slopp.sync/clone!` rebuilds a fileless store from a remote
(verified dependency-ordered `ingest!`; manifest restored from the remote
deps.edn). Cross-person merges are GIT-NATIVE (file-level, PR flow) — the
form-level CRDT merge stays a local capability (branches/forks): the merge
engine is journal-to-journal and file trees carry no journal. Incoming
non-slopp-valid files → Phase-2 pull surfaces them as CONFLICTS backed by an
off-log quarantine table (raw file kept for reference, never in the journal).

G2 ✅ **The graft: clones chain onto the remote's real history.** `clone!`
records `git-remote` + `git-base-sha` meta; `project-journal!` seeds its
parent chain with the base, so a clone's first local milestone parents onto
the remote commit it was cloned at and `push!` is a plain fast-forward
(verified: push → clone → edit → milestone → push; the new tip's parent IS
the pre-clone tip). Without this, every clone would mint unrelated history
and could never push back. Push is fast-forward ONLY — a diverged remote is
an honest error, never a force. `push-to-remote!` fetches the remote's
objects first when the graft base isn't in the (per-process) in-memory repo;
the remote itself is the durable object store for foreign history.

G3 ✅ **slopp is a git CLIENT in memory — no on-disk git state returns.**
JGit `Transport` push/fetch runs against the same `InMemoryRepository` as the
local read-only listener (built with `FS/DETECTED` — TransportLocal NPEs on
an FS-less DFS repo; scheme-less remote urls are absolutized). `slopp.git`
stays byte-moving (no `slopp.api` dep); `slopp.sync` owns the store side
(ingest/deps/session). Only MILESTONES cross the wire — a clone reproduces
the last commit_point, not un-milestone'd live state. Auth: token param or
SLOPP_GIT_TOKEN/GIT_TOKEN env. Verified end-to-end by slopp itself: push →
plain `git clone` (normal 6-commit repo) → `slopp.sync` clone (30 nses,
zero `.clj` files) → `slopp.boot` boots and serves it.

G4 ✅ **Pull is a 3-way merge at FORM granularity; conflicts quarantine
off-log.** `sync/pull!` fetches, takes git `merge-base(ours, tip)`, and
diffs base→tip trees: the remote wins wherever WE are clean (our form still
equals the base's — applied as verified `edit-group!`/`ingest!` in remote
dependency order, then `move-form!` fixup to the remote's form order so
trees byte-converge); anything BOTH sides touched, whole-file deletions,
anonymous-form files, and files failing the gates become CONFLICTS: our
version stays live, the raw remote file lands in the `quarantine` table
(off the journal — the journal only ever holds slopp-valid forms), and
**`push!` is refused until the agent resolves** (merge via edit tools →
`git_resolve`) — git's conflicted-merge semantics, one file coarser.
Comment/whitespace-only remote changes are surfaced as notes, not applied
(trivia isn't form-addressable). Each pull ends with a `:git-sha` chain
marker (`commit-point!` `:target` head + `:extra`): `project-journal!`
ADOPTS that remote commit as the chain node (never mints), so the next
local milestone parents on the remote tip and pushes stay fast-forward —
verified bidirectionally (A→B→A round-trip, byte-exact convergence).
Un-milestone'd local work rides through a pull untouched; unpushed local
MILESTONES fold into the next post-pull milestone (documented squash).

## T — the fileless flip (this repo eats its own dogfood completely)

T1 ✅ **The working tree holds NO project source.** Every namespace — the 33
system nses AND all 51 test nses — lives in `.slopp/store.db`; on disk remain
only the boot kernel (`src/slopp/boot.clj`, `src/slopp/rt.clj`),
`deps.edn` (slopp-the-tool's coordinates), docs, and benchmarks. Development
goes exclusively through slopp's MCP tools; the file↔store drift class is
gone by construction. The server runs `slopp.boot --live` so committed edits
hot-reload into the running host.

T2 ✅ **Third test tier: `^:isolated` (tag on the deftest name).** Tests that
spawn their own images/JVMs NEVER run in-image (recursion) — `traced-run`
unconditionally removes them; only the isolated runner executes them.
`isolated-test-run!` no longer shells `clojure -M:test` in the repo dir — it
**build!s the store into a throwaway dir and runs there** (the generated
deps.edn now carries a runnable `:test` alias with the cognitect runner, so
ANY built project is `clojure -M:test`-able out of the box). The suite is
store-sourced end to end: 207 tests / 1136 assertions green from a built
tree, byte-identical numbers to the old file suite. Import mechanics: 42
file-only test nses ingested through a second slopp server over HTTP (m5b
multi-server), deftests auto-tagged `^:isolated`; two forms needed
`^:unsafe` (`binding`/`read-string` boundary tests).

## S — the gate

S2 ✅ **Writes refuse NEW error-level lint** (`edit/lint-refusals`, wired
beside the cold-load check in `rebased-write!`/`edit-group!`). Kondo `:error`
findings are ~never false positives (two "invalid-arity" errors once
dismissed as noise were real ArityExceptions in shipped handlers). Diffed
candidate-vs-base per ns, keyed (type, message): pre-existing errors don't
block (no legacy deadlock), warnings stay advisory. Live-fired: an
arity-breaking add was refused by the very server that had hot-loaded the
gate minutes earlier (live mode, T1 — the running host now serves its own
just-edited code without restart, proven via the cheat-sheet edit).

S1b ✅ **The compile gate proves COLD-load, not just hot-load.** Found while
building git pull: an edit_group replaced `-main` (mid-file) to call `pull!`
(appended at the tail) and committed GREEN — the gate hot-loads into the
live image, where every var already exists and definition order is
invisible; a fresh load (boot, restart, new image) threw "Unable to resolve
symbol". The green gate's promise was violated in the cold-load sense, and
the failure surfaces far from its cause (the next restart). Fix:
`index/forward-refs` (same-ns var usage positioned before the var's first
def/declare, (row, col) lexicographic, from the kondo analysis already run
per write) + `edit/cold-load-errors`, enforced BEFORE the image is touched
in `rebased-write!` (both branches), `edit-group!`, and `move-form!` — a
move can CREATE the forward ref. Validated against the whole store: 0
findings across 33 namespaces (boot cold-loads them all). Over-approximation
accepted: syntax-quoted own-ns symbols count (declare satisfies); quoted
symbols and cross-ns usages are correctly ignored. `ingest!` exempt (a
brand-new ns cold-loads for real). Merge replay gated too
(`merge-into-session!` covers branch_merge + merge_from): replay interleaves
two individually-legal lines and CAN mint a forward ref (proven: ours
tidies away a satisfied declare, theirs adds a forward use — every per-line
write passed the gate, the merge would not cold-load; refused before the
image is touched). Wiring this surfaced that kondo's two "invalid-arity"
lint ERRORS on mcp's branch_merge/merge_from handlers were REAL — both
tools threw ArityException on every call (`:agent` kwarg their api fns
never had); fixed, checkpoint lint now clean.

S1b ✅ **Update — auto-avoid-declare (2026-07-16): the pipeline orders forms,
the agent never writes `(declare …)`.** The cold-load gate refused a forward
ref and told the agent to `edit_move` or add a declare — a mechanical
ordering chore the store (order is an element property, not text) can do
itself. Now `rebased-write!` wraps the pure transform in
`edit/resolve-cold-load`: on a forward ref it computes a topological order
(`refs/cold-load-order`, Kahn over THE reference graph) and realizes it via
`store/reorder-to` (minimal replayable `:move` deltas) BEFORE the gate. The
gate then passes; the write proceeds. Wrapping the *transform* (not patching
each branch's gate) means the reorder rides both the durable append-CAS loop
and the ephemeral in-swap rerun consistently, and all three callers
(`add-form!`, `edit-replace!`, `delete-form!`) inherit it. Only a genuine
cycle (mutual recursion — no legal order) falls through to the original
refusal, which still teaches the `declare`. **The reorder is SILENT to the
agent** — deliberately no `:reordered`/`:moved` result key: form ordering is
a file-oriented concept, and surfacing it would re-anchor the agent to the
"think about the file" model the boundary audit deletes (same category as a
`file:line` leak). Provenance is NOT lost — it lives in the move-deltas'
`"auto-reorder: define before use"` prompt, queryable by a human/tool. The
`fix_declares` **MCP tool was removed** in the same change: with writes
auto-reordered and `done!` running `fix-declares!` internally (declare
hygiene for any cycle-declare that outlives its need), there is no reason for
an agent to ever reach for it. `api/fix-declares!` stays as the internal
cleanup. Friction found + logged: the edit tools' INLINE `:test` summary
reported a green that SILENTLY omitted the `^:isolated` test just written
(the isolated run was red). [Corrected 2026-07-16: I first recorded this as
"ran it in-image and false-greened" — wrong. `slopp.rt/traced-run` has
dropped `^:isolated` unconditionally since d980; they never ran in-image. The
bug was the SILENT skip, not the execution. See `.context/verification.md` §7.]

S1b ✅ **Update 2 — full declare ownership (2026-07-16, same day): cycles
auto-declare, hand-written declares are banned.** Update 1 above left ONE case
where the agent still had to think about ordering: a genuine cycle (mutual
recursion) has no legal order, so the write refused and taught the `declare`.
The user's call: "auto-inserting declares that are needed is a good idea …
then we can fully own the declare pipeline." Now `resolve-cold-load`'s cycle
branch INSERTS the declare itself — a MARKED `^{:auto-declare "<why>"}
(declare …)` for the cycle members, built via the raw parser (bypassing the
edit gate) and appended before the first member. The marker's value is the
why — the first concrete instance of `markers-carry-their-why`; it records
provenance and lets `fix-declares!` (at `done`) remove the declare once the
cycle breaks. `edit-group!` got the same reorder/declare pass (an intra-group
forward ref shouldn't wall a batch a sequence of single writes wouldn't).
With the pipeline owning every declare, **hand-written `(declare …)` is now
REFUSED on the edit path** (`parse-form`, NOT `dialect-check` — so imports via
`dialect-scan` keep their declares, and the pipeline's own raw-parser inserts
are unaffected). Teaching: "slopp orders forms itself — drop the declare."
Consequences: the auto-declare, like the reorder, is SILENT (no `:declared`
result key); `done!`'s declare hygiene stops reporting `:declares-fixed`
(cleanup runs for effect only — the agent never manages declares). Left
refusing (deliberately, for now): `move-form!` (an explicit ordering command)
and merge replay — the agent/merge explicitly commanded that order, so an
illegal one is honest feedback, not a hidden chore.

S1b ✅ **Update 3 — ONE ordering algorithm; phantom names can't freeze a
declare (2026-07-16).** Found dogfooding the slopp.api split. TWO orderers had
grown up: `refs/cold-load-order` (Kahn over THE reference graph, the write
pipeline's) and `fix-declares!`'s bespoke conservative single-form mover (a
clj-surgeon port from when AGENTS minted declares). The Kahn sort strictly
dominates — the mover's giving-up is why `(declare isolated-test-run!)` sat in
slopp.api indefinitely. `fix-declares!` now DROPS a namespace's declares and
delegates to `edit/resolve-cold-load`: reorder, or the pipeline's own MARKED
auto-declare for a live cycle (so a legacy hand-written declare MIGRATES to a
pipeline-owned one that says why); it no-ops when the rendered ns wouldn't
change. It no longer reorders anything itself. Result: ZERO declares remain
anywhere in `slopp.api*`.
Also fixed: a PHANTOM declared name (declared here, defined nowhere — an
earlier `move-forms!` lifted the var out) classified as `:skip`, and removal
required no skips, so ONE phantom froze its declare FOREVER. slopp.api's
`f463` had 7 of 17: they minted unbound vars (a typo'd unqualified call
resolves silently instead of failing loudly) AND appeared as phantom FORMS in
`query_source`'s outline — the agent was told about forms that don't exist,
and no tool could address the anonymous declare to fix it (`query_search`
reports it by form-id; the edit tools only take a name). Phantoms are now
`:phantom`: dead, never a reason to keep a declare. Root cause still open:
`move-forms!` mints its own unmarked declares
(`ideas/move-forms-mints-unmanaged-declares.md`) — it is the last declare
writer outside the pipeline.

S1b ✅ **Update 4 — the pipeline is now the ONLY declare writer (2026-07-16).**
`refactor/move-plan` was minting its own UNMARKED `(declare …)` for the moved
set, UNCONDITIONALLY on `(> (count moved) 1)` — at both emission sites
(`:new-src` for a new target, `:append` for an existing one). That was the
FACTORY for the phantom debt Update 3 cleaned: a move plants a declare naming
what it moved; a later move lifts one of those vars out; the name becomes a
phantom (declared here, defined nowhere, minting an unbound var). Both sites
removed; `move-forms!` now calls `edit/resolve-cold-load` on the target — the
same single call `fix-declares!` makes. `edit/declare-node` (which carries the
`^{:auto-declare "<why>"}` marker) is now the ONLY declare builder in the
store; the sweep for construction outside it is clean.
Worth recording because it corrected a wrong assumption: a moved subsequence
CAN carry a forward ref. Moved nodes keep source order and the source ns
cold-loads, so I reasoned a subsequence must be ordered too — but a source may
order caller-before-callee behind a declare that STAYS BEHIND (declares are
anonymous, never part of the moved set). The planner's declare compensated for
exactly that; the right answer is to REORDER, which resolve-cold-load does.
Also fixed: the agent-facing `mcp.tools/cheat-sheet` still taught the banned
rule ("define callees first; (declare x) for cycles"). Consequence noted:
with the `fix_declares` tool removed, hygiene is LAZY (done sweeps only the
episode's changed namespaces) — 2 legitimate legacy declares (slopp.render,
slopp.repl, zero phantoms) persist until those nses are next touched.

R3 ✅ **Not slopp-special — the kernel is slopp-the-tool.** `slopp.boot` +
`slopp.rt` + the dep coordinates are part of slopp's distribution (bundled in
the jar when packaged), NOT per-project source; `rt` is the runtime slopp
injects into every owned image it spawns. So ANY store runs from its db with
zero project source files; slopp running itself is just the self-host
instance. In THIS repo the on-disk kernel is `src/slopp/boot.clj` +
`src/slopp/rt.clj` + `deps.edn`; the rest of `src/` is no longer needed to
RUN (still needed to run the file-based system-test suite — separate concern).
`--at <commit>` (boot a past milestone) is a noted future refinement.

## E — edit-surface ergonomics (friction-log fixes)

E1 ✅ **Anchored adds**: `edit_add_form` (and group add steps) take
`before=<form-name>` — insert immediately before the anchor instead of the
tail. The `:add` delta records the anchor's form-ID so foreign replay
converges on the same position (merge replay falls back to append).
`edit_group` gains a `move` action (batch reordering, one atomic commit).
The append-at-tail class that warped three designs and caused the S1b
incident is closed.

E2 ✅ **Subform matcher correctness**: matching is structural OR
whitespace-normalized-textual (fn literals gensym their args and regex
Patterns never compare equal — sexpr-only matching could NEVER match them);
a multi-form match string is a hard error (it used to silently match its
FIRST form — corrupted a case dispatch once; the compile gate caught it);
SPLICE (one match → several replacement forms) is a documented guarantee.
String CONTENT and trivia remain non-addressable (open).

E3 note: the isolated suite caught a G5 regression (a projection test
asserted the legacy agent author; the "<git>" fallback now resolves the
machine's git identity) — fixed by pinning store config in the test. Lesson
relearned: run the FULL suite after every feature, not targeted probes.

E4 ✅ **Required source args are validated, not silently dropped**
(2026-07-17, dogfooding friction). `slopp.mcp/call-tool` validated missing
SYMBOL args (`sym` → "missing required argument :ns") but passed source args
raw (`(:source a)`), so a misnamed key (`new_source` instead of `source`)
became nil and fell through to a confusing `expected exactly one top-level
form, got 0` parse error — reading like a paren bug, not a bad parameter. Now
a `src` validator mirrors `sym` on `edit_replace_form`/`edit_add_form`/
`change_signature`: it demands a non-blank source and, if it finds a
near-miss alias (`new_source`, `new-source`, `src`, …) in the args, names it
("you passed :new_source; the form source goes in :source"). Guarded by
`mcp-test/source-arg-friction`.

## G6 — files manifest + slopp3 is the permanent repo (for now)

G6 ✅ **Non-code files ride the store.** `:files` {path → text} manifest
(state-carrying `:file-put`/`:file-remove` deltas, meta row, replay — the
deps-manifest pattern), snapshotted onto milestone markers so the projection
stays a pure fn of the marker. `commit-paths` merges them into EVERY
projected tree — a slopp push never deletes the remote's README/workflows.
clone captures remote extras; pull 3-ways them (remote wins where we're
clean). Tools: `file_put`/`file_remove`/`file_list`. Motivation: CI —
`.github/workflows/test.yml` now lives on the manifest and runs the full
suite on every push to https://github.com/nvoxland/slopp3, which is the
PERMANENT published repo for now (`git-remote` meta points there).

## R4/E4 — release pipeline + trivia/string addressability

R4 ✅ **v0.1.0 shipped.** The release artifact is the UBERJAR
(`java -jar slopp.jar -m slopp.boot <dir> …`, :main clojure.main — nothing
AOT'd, the store loader keeps runtime load-string; needs Java + the Clojure
CLI for owned images). Built by `build.clj` (kernel-side + on the files
manifest so the tag-triggered release workflow can build from a checkout),
smoke-tested on a FRESH dir in CI (which caught two boot bugs: missing
make-parents and a schema-less-db crash), attached to the GitHub Release.
CI green across the board: test-files, test-via-slopp (the pushed code
imports itself into a store through every gate, then runs the store-built
suite), native-proof (a sample app built through slopp → GraalVM binary →
executed — O4's first real verification). Native remains apps-only; slopp
itself is uberjar-only. boot's --live watcher is a daemon thread (a live
server used to hang its JVM after stdin EOF).

E4 ✅ **Trivia and string content are addressable.** `edit_trivia` replaces
the ENTIRE comment/blank-line run before a named form (or the ns tail) —
`:trivia` delta anchored on the form-id, foreign replay converges, forms
untouched by construction (no image work, like move). Text is normalized to
start/end with a newline; empty = delete; code forms refused. And
`edit_subform {text: true}` does RAW-TEXT replace inside a form (unique
occurrence, result must reparse to ONE form) — docstrings and string
literals, riding the full gated replace pipeline. Live-fired: the stale
"import: git push → slopp (M3)" banners in slopp.git (unremovable since
b0511ea) are finally gone, and sync/-main's docstring caught up via text
mode. The last friction-log design item is closed.

## G7 — MANIFEST.MF is tracked jar config (generic file system completed)

G7 ✅ `java -jar slopp.jar` needs NO args: the entry point is CONFIG on the
files manifest — `META-INF/MANIFEST.MF` (tracked like any file: history via
`file_history`, time travel via `file_get {at}`) carries `Main-Class` and
`X-Slopp-Main`. build.clj GENERATES the named launcher class at build time
(host scaffolding, gen-class never enters the store) delegating through
requiring-resolve, so only that one class is AOT'd. `build!` materializes
manifest files (the projection already did). Other config
files/formats ride the same generic system — it's just tracked text.

## G8/G9 — mixed ownership + structured config

G8 ✅ **The branch is the ownership boundary.** slopp pushes/pulls exactly
ONE remote branch — config `git-branch`, default **"slopp"** — and never
touches anything else; humans own `main` (docs, README, hand-managed files)
with regular git and merge across at will. `push-to-remote!` disentangles
the local projection line (`:branch`, default main) from the remote dest
(`:remote-branch`); clone finds `slopp` then falls back to legacy `main`
and records `git-branch`; pull fetches the configured branch. Local-repo
workflow: `git-remote "."` pushes into the checkout's own `.git` as the
slopp branch (guarded: never onto a branch a working tree has checked out —
JGit would move the ref under it). slopp3 migrated: slopp owns `slopp`,
`main` is human-owned (README lives there now, committed via the GitHub
API), CI triggers follow the slopp branch.

G9 ✅ **Execution config is SEMANTIC, not raw text.** The store's `:config`
({path {:format :values}}) holds key/values with per-key delta history
(:config-put/:config-unset — the non-code analog of form edits); the
projection serializes each entry into its format at tree-build time
(`store/render-config`; :manifest = sorted `K: V` lines; new formats add a
serializer case). `config_file` is the tool. META-INF/MANIFEST.MF migrated
from a raw tracked file to two semantic keys — the rendered output is
byte-identical, and `java -jar slopp.jar` still boots bare. The raw files
manifest remains for things that genuinely are opaque files (CI workflows,
until a YAML serializer exists); README and human docs belong on main.

## G10 — the onboarding flow: import into a main checkout

G10 ✅ Working dir = the HUMAN's main checkout; the store = the slopp
branch. `slopp.sync/import!` (CLI `import <dir>`, jar:
`java -jar slopp.jar --main slopp.sync/-main import .`) builds
`.slopp/store.db` inside a plain git clone from the repo's slopp branch —
`fetch-remote!` now also maps the source's remote-tracking refs, so a fresh
clone (slopp only as origin/slopp) imports directly. The store records
`git-remote "."` (relative remotes resolve against the STORE dir, not the
CWD): slopp pushes/pulls refs/heads/slopp of the SAME local repo; the human
does all origin interaction with regular git, on both branches. No
bootstrap files needed on main — the jar carries the kernel, and import
runs from the jar's bundled code before any store exists. External-system
config (CI workflows) lives on main and checks out the slopp branch on
schedule/dispatch (GitHub only runs push-triggered workflows from the
pushed ref — the honest trade for the boundary); the store keeps ONLY
config slopp consumes (MANIFEST.MF semantics, deps).

## P — probe-session findings — MOVED

See `.context/findings-log.md`.
## G11 — plugin packaging (Claude Code first)

G11 ✅ slopp ships as a Claude Code plugin: `.claude-plugin/marketplace.json`
(the repo IS the marketplace — `/plugin marketplace add nvoxland/slopp3`) +
`plugins/slopp/` bundling the MCP server entry, two skills (`slopp` = the
working loop, updated for change_signature/pair-matching/trivia;
`slopp-setup` = onboarding/sync/config/one-shot CLI), and `bin/`
(`slopp`, `slopp-server`) on the session PATH. The binary question is
solved Homebrew-style: git carries only the recipe; the launcher fetches
the VERSIONED release jar on first run (sha256-pinned, cached under
`${CLAUDE_PLUGIN_DATA}`), so bumping VERSION+SHA alongside the plugin
version is the whole upgrade story. The canonical skill home is the plugin
dir (installs are cache-copied and must be self-contained); `.agents/skills/*`
symlinks expose the cross-agent Agent Skills standard location. Parked:
Codex plugin / Gemini extension wrappers around the same jar + skills,
Clojars publication (would enable a package-manager launch path).

## G12 — the automation principle (user decision, 2026-07-13)

**Anything the system can *just do* from a high-level agent signal, it
should — but only at natural workflow boundaries, never fighting the
agent.** The pattern every automation must follow: fire on a signal the
agent already emits (serving a dir → auto-import; the first write of a
prompt → auto-turn with the verbatim ask; session pause → auto-checkpoint
pipeline: normalize, declare hygiene, re-verify, boundary), keep a manual
fallback that still works (import CLI, turn_begin, checkpoint), return to
the agent only what it must care about (terse greens, :implicated reds,
:forms confirmations, state-not-error responses). Corollaries: never
surprise mid-flight (normalize only at boundaries), never block on the
automation failing (auto-import/hook failures degrade to the manual path),
and prefer richer RESULTS over more instructions — results that carry the
reasoning close the trust gap that skill exhortations can't.

## Q — self-dogfood findings — MOVED

See `.context/findings-log.md`.
## D8 — a form defines a SET of names (2026-07-17, user decision)

**Decision:** the store's form identity is `:names`, a set of every symbol the
form defines — possibly empty. `:name` stays as the primary name for labels.
`form-named` matches any of `:names`, or a form ID.

**Why:** the previous premise — one form ↔ one name, via `form-symbol`'s
`(second s)` — is wrong in both directions, probed against kondo:

- **Registrations define nothing.** `(defmethod area :square …)` names its
  TARGET. `defmethod` sat in `def-heads` beside its own siblings
  `extend-type`/`extend-protocol`, which were never there. Result: three forms
  named `area` in one ns; `form-named` returns the first; the methods were
  unreachable by every name-keyed tool; `refs/cold-load-order` silently DROPPED
  forms including the defmulti; `static-refs` resolved `:from-form` last-wins
  against `:to-form` first-wins. Meanwhile `api/add-form!` already refuses
  duplicate names, so **ingest was admitting a state the edit layer considers
  illegal**.
- **Some definitions define several.** `defrecord` → `R`, `->R`, `map->R`;
  `deftype` → `T`, `->T`; `defprotocol` → `P` + each method var. Those were real
  public vars with **no form** — invisible to `form-named`, `:covered`, and the
  unused-public gate. Broken today, independently of multimethods.

**Rejected — a compound name (`area:square`):** `:` is legal inside a Clojure
symbol, so `(defn area:square [x] x)` is a real fn a user can write (probed: it
evaluates). Every ASCII-punctuation separator is likewise legal. The name space
is flat and user-owned — you cannot reserve a corner of it. Any scheme encoding
structure into a flat name has this bug.

**Rejected — refuse `defmulti` at the gate:** honest and close to today's de
facto behaviour, but it rules out idiomatic Clojure and fixes nothing for
`defrecord`/`defprotocol`.

**Consequences / still open:**
- Registrations are addressable only by form ID. That is their only handle, and
  the ID match also fixes `qform`'s label/address asymmetry.
- **Latent ID/name collision:** a user could write `(defn f4 …)` while some
  form's id is `f4`. `form-named` prefers names, so the name wins; `qform` has
  labelled forms `ns/f4` all along, so this predates D8. Not fixed.
- `cold-load-order` does not order anonymous forms, so a forward reference from
  inside a `defmethod` body is not auto-resolved (it was not before either — the
  form was dropped entirely). Not a regression; not fixed.
- The unused-public gate and `review_scan` filter heads to `#{defn def}`, so a
  dead `defmulti` is invisible to `done!` AND `commit_point`. Separate, unfixed.
- ~~The tracer still cannot see method bodies~~ **RESOLVED same day (C-wave):**
  `instrument!` wraps the MultiFn's METHOD TABLE — every dispatched call records
  the multi (both tiers) plus the method's form key in-image. The narrowing rule
  is in `store/method-carrying?`: defmethod/defrecord/deftype/extend-*/defprotocol
  forms never narrow (their evidence is structurally partial — including
  defprotocol, whose inline-impl call sites bypass the wrapped var via the
  protocol inline cache; found red). Static tracking sees method bodies too:
  nil-`:from-var` kondo usages attribute to the owning form by rendered span.
  Still true: defrecord/deftype method BODIES are unobservable at runtime, and
  callable data (`(def valid? #{...})`) reads `:covered 0` forever.

## D9 — Best-practice gates & skills: encoding agent-native architecture (2026-07-17, user decision)

**Decision:** slopp encodes the Clojure best practices that fit LLM agents
through TWO channels over one chassis:
- **Skills** (guidance a machine can't decide) shipped in `plugins/slopp/skills/`
  so a *user's* agent gets them, not just slopp's — a coding-best-practice skill
  and a code-review skill (`ideas/ship-review-and-best-practice-skills.md`).
- **Deterministic gates** (single-form-checkable rules) wired into the write path
  / `done`, each a client of a per-store-configurable **rule registry**
  (`ideas/rule-registry.md`) — the chassis that ends the hand-wire-into-three-
  surfaces pattern (`done!`/`commit_point!`/`review_scan`).

New architectural gates target **hard-refuse-at-write** enforcement, built on the
proven module-gate template: every hard gate ships (a) an escape marker
(`^:unsafe`/`^:reads`-style discharge), (b) an adoption story (derive initial
state from reality so enabling it never retro-breaks working code — the bootstrap
catch-22), and (c) a teaching refusal that names the exact next tool call. Scope
is the FULL checkable menu; the sequenced roadmap is
`ideas/agent-native-best-practice-gates.md`.

**Why:**
- **slopp already enforces much of "the Clojure way" mechanically** — the dialect
  gate (D3/D4/D7), `!`-effect labeling (D6), the module system (declared edges,
  no cycles, recursive visibility, cohesion-decides-location). This program
  extends that spine rather than starting fresh.
- **Research validates slopp's bets.** The REPL-as-oracle is the most-corroborated
  reason practitioners find Clojure good for agents (clojure-mcp/Hauman, Willig,
  Bille, Nubank); form-addressed editing is the documented cure for the #1
  failure mode (the paren death-loop). The ONE *measured* negative — a training-
  data deficit that pulls models toward imperative, non-idiomatic Clojure (Nubank
  MultiPL-E, Clojure/conj 2024) — is exactly what idiom-enforcing gates +
  REPL-first skills exist to counter.
- **Agents are cheap where humans resent ceremony.** A carrier/annotation is the
  same keystroke count for a generator; only humans feel the tax — so the
  cost/benefit of "extra structure that aids analysis" flips relative to a
  human-centric language, PROVIDED the structure is oracle-checked so it can't
  drift into a lie (`dialect-prunes-human-conveniences.md`).

**Status:** IN PROGRESS. Skills shipped (`slopp-style`, `slopp-review`). THREE
gates SHIPPED 2026-07-17: **(1) functional-core purity tiers** (`module_purity`
verb/tool, `edit.modules/tier-refusal`, hard-refuse across add/replace/group; see
`.context/dialect.md` § Purity tiers — `:pure` also forbids NON-DETERMINISM
(`rand`/`slurp`, `index/nondeterministic-vars`) so it means referential
transparency, not just mutation-freedom); **(2) schema-at-boundary** — the generative
oracle-check (`slopp.api.schema` → `done!`'s `:schema-drift`) plus the opt-in
`edit.modules/schema-refusal` require-gate (see D2 and `.context/dialect.md` §
Schema oracle-check); **(3) key hygiene** — a DERIVED attribute inventory
(`slopp.api.attrs/keyword-inventory`) + a near-duplicate-key advisory
(`near-duplicate-keys` → `done!`'s `:key-typos`), the program's FIRST done-time /
advisory-grade rule (the per-form write gates are hard-refuse; this is a heuristic
that never flips status). See § Attribute inventory below and `.context/dialect.md`;
**(4) contract-breakage advisory** (`slopp.api.breakage` → `done!`'s
`:breaking-changes`) — a module-external fn whose fixed-arity surface NARROWS vs
the last-done baseline is flagged (Spec-ulation: growth is safe, breakage must be
visible to the external callers a slice can't see). Advisory, v1 = arity narrowing. The rule-registry chassis has its FIRST SEED (2026-07-17):
`edit.modules/gate-refusal` runs an ordered `per-form-write-gates` registry, held
as VARS so hot-reload is picked up, and the four write sites now call ONE
`gate-refusal` dispatch. The seed's thesis is **confirmed**: `schema-refusal`
joined as a ONE-LINE addition (`[#'module-refusal #'tier-refusal
#'schema-refusal]`) — a second new gate, one edit, not four. The registry now
spans a **SECOND GRAIN** (2026-07-17): `slopp.api.rules/done-advisories`, an
ordered `{:key :severity :check}` registry for the DONE-time findings
(schema-drift, key-typos, breaking-changes) — `done!` collapsed three hand-wired
advisory bindings + clauses + a status term into one `run-done-advisories!` loop,
and **`:severity`** (`:error` flips test-status; `:advisory` never does) is now
formalized. **PER-STORE SEVERITY CONFIG SHIPPED 2026-07-17** — the dial that makes
the hard-refuse program adoptable: `edit.modules/rule-severity` reads a per-store
override from a `rules` config file (`config_file {path "rules" key <rule> value
<severity>}`, git-projecting), else the rule's default. `gate-refusal` SKIPS a
write gate dialed `:off`; the done grain skips `:off` advisories and uses the
EFFECTIVE severity for status, so a project dials `key-typos` up to `:error` or
`schema-drift` down to `:advisory`. This is what lets a project turn off a gate it
can't live with instead of fighting a wall — and unblocks the opinionated gates
(require-namespaced, hard-refuse-breakage) by making them tunable. **UNIFIED
CATALOG + `query_rules` SHIPPED 2026-07-17**: `slopp.api.rules/rule-catalog` is
one declarative `{:rule :grain :severity :escape :teach}` catalog of every rule
across both grains; `query_rules` projects it with each rule's effective per-store
severity (the queryable "what's enforced here, at what grade, how to discharge"
surface), drift-guarded against both registries via `edit.modules/write-gate-names`.
**Follow-ups SHIPPED 2026-07-18** (post-review): write-gate **advisory-downgrade**
(`edit.modules/gate-check` → `{:refuse :advisories}`; an `:advisory` write gate
warns-but-proceeds, teaching on the write result's `:advisories`); a **DRY** of the
boundary/arglist logic into shared `module-external?` + `fn-arglists` (fixing a
first-arity-only blind spot); the **carrier-taint** D6 fix (`call-graph` adds an
edge for every usage EXCEPT a `#'var` carrier — a non-call ref at a var-quote
position, via `analyze`'s `:var-quotes` — so a var held in data doesn't propagate
effects while a call / bare alias / higher-order value-arg still does; dropped the
`^:reads` workarounds); and two more done-advisories
(`:ambient-state` — a global `(def _ (atom …))`; `:bare-throw` — a boundary fn
throwing a constructed non-`ex-info` exception). Nine rules now ride the two
registries. **Rule telemetry** (`slopp.api.telemetry/rule-telemetry` →
`query_rule_telemetry`) makes the severity dial measurable: read-only over the
delta log, per-rule fire-rate + discharge (`:discharged` vs `:persisted`) +
escape-marker density + dials — the demand signal the plan wanted, with no new
instrumentation (diagnostic only, not an auto-tuning control loop). Still ahead:
the EXECUTION-level shape-unification of the two
registries and the `commit_point!`/`review_scan` grains (`ideas/rule-registry.md`).
Design note (D6 interaction, RESOLVED): a data registry carrying a `#'bang-fn`
check ref used to be flagged effectful (carrier-taint), which forced `^:reads`
onto `done-advisories` + `status-affecting-fired?`; the carrier-taint fix (above)
removed both — the analyzer now distinguishes a `#'var` carrier position from a
call.
Remaining waves are picked on demand, each with its own red/green TDD through the
slopp tools. The named gates (purity tiers ✓, schema-at-boundary) have the user
as the demand instrument; the wider menu should still earn hard-refuse via
dogfooding (watch force-rate + marker-density — climbing metrics mean agents are
fighting a gate, the signal to soften severity per store). Design note learned by
building the first gate: **default new gates to permissive + opt-in** (absent =
ungated) — it makes the adoption/migration step disappear (purity tiers needed no
adoption, unlike the on-from-birth module manifest).

**Relation to prior decisions:**
- **Amends D2** — schemas become enforceable at the module-external boundary
  (open, instrumented, generative). See D2's amended text.
- **Extends D6** — the `!` naming gate generalizes to `*earmuffs*` (dynamic vars)
  and `?` (predicates).
- **Compatible with D5** — D5's "no purity rule" governs the RELOAD strategy
  (refresh-vs-restart), NOT architecture. The functional-core purity-tier gate is
  a per-module *locale* rule for where effects may live; it neither requires
  purity for reload nor contradicts D5. Recorded here so the two aren't conflated.
- **Builds on** the module system (the `^:export`/recursive-visibility predicate
  is the public/private cut every boundary rule rides) and `index/effectful-vars`
  (the effect closure the purity gate consumes).

## Inherent deps — slopp ships them, separate from the project manifest (2026-07-17, user decision)

**Decision:** libraries that slopp-the-tool needs for its OWN image-side features
are **inherent deps** (`repl/inherent-deps` — currently `nrepl` + `malli`), merged
into every image's `-Sdeps` in `repl/default-cmd` **after** the project manifest
(so slopp's versions win a collision). They are NOT project dependencies: never in
`deps_list`, never `deps_remove`-able, centrally versioned (an upgrade reaches
every existing install with no per-store migration).

**Why:** malli belongs to slopp, not the user — putting it in the manifest
(`deps_add`) was wrong: it showed in `deps_list`, was removable by accident, and
got pinned per-store. Inherent deps fix all three, and the mechanism generalizes
to any future slopp-owned image-side library.

**The load-bearing constraint (the two-process split):** the image is ALWAYS a
`clojure -Sdeps` subprocess (`start!` → `default-cmd` → `clojure-bin`), whether
slopp ships as a jar or a native binary — so inherent deps resolve from maven at
image launch exactly like manifest deps; it is real-deployment-correct, not a
self-host hack. But the **server/boot JVM runs on kernel deps only** — a store ns
that `:require`d an inherent lib would fail `load-store!`. So any feature needing
an inherent lib must run **image-side** (eval-injected / feature-detected), never
server-side. This is why the schema oracle-check is a self-contained eval-string
and the write-time require-gate stays structural. Litmus for every future gate
that reaches for a library: **can it run image-side? If not, it costs a kernel
dep.** Full finding: `ideas/inherent-deps-and-the-self-host-classpath.md`.

## Attribute inventory — a DERIVED index, not folded state (2026-07-17, user-guided)

**Decision:** the domain-keyword/attribute inventory (`slopp.api.attrs/keyword-inventory`,
`{namespaced-kw -> #{form-ids}}`) that backs the key-hygiene gate (D9 §(3)) is a
**derived view** — a pure function of the stored forms, recomputed when needed —
NOT a folded, persisted store field.

**Why (the reusable litmus):** the deciding constraint was that it must work with
the history-accessible / CRDT / multi-branch model. Because `inventory = f(forms)`:
- **CRDT merge** already reconciles FORMS (`store.merge/merge-logs` + `replay-delta`);
  `inventory(merged) = f(merged-forms)` follows for free. A folded index would need
  a bespoke CRDT merge (union per-form-id sets to match `f`), and any mismatch
  silently drifts.
- **History** is free: `inventory-at-N = f(forms-at-N)`. A folded index would need
  per-delta snapshots.
- There is **no single form-mutation chokepoint** (~7 store fns + every
  `replay-delta` case + merge), so incremental folding is a large, drift-prone
  surface.

So the litmus for any future index: **derivable from the forms the store already
versions ⇒ DERIVE it** (optionally cache behind a store-version key — a pure
optimization, never independent CRDT/history state). Contrast `:module-tiers` /
`:deps` / `:files` / `:config`, which ARE folded+persisted — correctly, because
they are independent DECLARATIONS, not derivable from the forms. SQLite fuzzy
matching (spellfix1/editdist3/soundex) was considered for the near-match and
rejected: loadable native extensions the bundled driver lacks, wrong dataflow
(the check reads the in-memory value, not the db), and no scale need — a
pure-Clojure Damerau-1 suffices. Full reasoning:
`ideas/derived-indexes-and-crdt-safety.md`.

---

## D-gates-required (2026-07-19) — every rule is blocking; zero advisories

**Decision.** All nine rules in the registry are now REQUIRED: 4 write gates at
`:refuse` (module, tier, schema, namespaced-keys) and 5 done-time checks at
`:error` (schema-drift, key-typos, breaking-changes, ambient-state, bare-throw).
No rule sits at `:advisory`. Milestone d7763.

**Why, and the rule it establishes:** an advisory an agent can scroll past is
not a rule — it is documentation with a nag. But a rule cannot be made blocking
until it is DISCHARGEABLE, and two were not:

- **`breaking-changes`** was dialled back to `:advisory` because privatising a
  fn with no outside callers — a correct change — was flagged with no escape.
  Now escapable via `^:breaking-ok` on the name. Like the other markers it
  polices itself (a marker on a changed form that narrowed NOTHING reports
  `:stale-marker`), and that self-check sits deliberately OUTSIDE the
  "was a boundary at baseline" filter: once a narrowing lands the new baseline
  is already private, so a boundary-guarded check would never see it again.
- **`require-namespaced-keys`** had one violation, `api/open!`, with 60 call
  sites. A store-wide `rename_sweep` could not do it (`:dir` names three
  different things in this store) and 60 hand edits was worse. Discharged by
  building the missing capability — `edit_requalify` / `api/requalify-boundary-keys!`
  — which rewrites a boundary fn's arglist destructuring AND every caller's map
  literal as one intent.

**The litmus this leaves:** before making a rule blocking, ask what a person who
hits it LEGITIMATELY is supposed to do. If the answer is "an edit nobody can
reasonably perform", the missing tool is the actual work — the severity is a
one-line config change afterwards. The rule's own docstring already warned that
an undischargeable rule trains people to ignore the channel.

**Two things the enabling exposed, both worth remembering:**

1. **A gate does not verify what it does not inspect.** `require-namespaced-keys`
   reads ARGLISTS, so a stale CALL SITE is invisible to it. After the requalify,
   the check that actually mattered was an independent scan for any map literal
   still handing `open!` an unqualified key. A clean gate would have felt like
   proof and been none.
2. **Syntactic rewriters cannot see through a binding, and must say so.**
   `mcp/-main` and `http/-main` build their options with `cond->`; both were
   correctly reported as `:unknown-shape` and patched by hand. Unqualified there
   would have started the server EPHEMERAL with no store directory — silently.
   Any operation of this kind must NAME what it could not reach rather than
   report a clean sweep over a partial one.

**Effectful boundary schemas are unverified by construction.** `analyzer-pure?`
excludes anything reaching an effect or non-determinism from the generative
`mg/check`, so `repl/start!` and `api/open!` carry `:=>` schemas nothing tests —
required by a gate that cannot validate them. Their docstrings say so. Keeping
them honest is a discipline, not a guarantee.

---

## D-rule-grain (2026-07-19, REVISED 2026-07-20) — two grains, and `done` means done

**Superseded design (recorded because it was wrong in an instructive way):** an
earlier version of this decision described THREE checking grains — write, done,
and milestone — and moved warning-level lint to the milestone. That was wrong,
and the evidence arrived within one session: with two enforcement points, five
`:error` advisories were blocking at `done` and completely invisible at the
milestone, because `commit-point!` recomputed status from raw test counts and
never read `:findings`. **Two bars drift. They drifted here in hours.**

### The design

There are **two** places a rule may live.

| grain | asks | mechanism |
|---|---|---|
| **write** | is this FORM well-formed? | REFUSES — the write does not land |
| **done** | is this CODEBASE good? | REPORTS — records the boundary with findings |

A check belongs at the WRITE grain only if it is decidable from the form
ITSELF and its verdict cannot change as legitimate work continues: a macro
def, a denylisted symbol, an undeclared cross-module call, a boundary contract
with no schema. Nothing else. The write grain must never treat
work-in-progress as a defect — **a red test can never be a done point**, and
red-first TDD lands specs referencing vars that do not exist yet.

Everything else is `done`, and `done` is STORE-WIDE and absolute: it runs the
entire in-image unit suite, all impacted integration and isolated tests, and
scans every namespace for lint and dead public surface. Not the episode's
namespaces — the whole store. You cannot outrun a standing problem by editing
somewhere else.

**`done` REPORTS; it does not refuse.** It records the episode boundary
honestly with its findings and says, in effect, *"no, you are not done — fix
these and call done again."* An agent that genuinely cannot fix a finding is
not deadlocked: the boundary is still recorded, red, in history. The real
block is `commit_point`, which refuses to PUBLISH a red done. You may record
where you got to; you may not ship it.

### The milestone is not a grain

`commit_point` has **no checks of its own**. It runs `done!`, adds the one
thing done deliberately skips (the full isolated tier, which spawns JVMs), and
gates on done's verdict. Nothing is re-judged. Keeping a second set of scans
there is what produced the drift above, and a second bar is somewhere to
accidentally put a check that then does not apply at `done` — which is the
failure this design exists to prevent.

`done` therefore states its one omission in EVERY result
(`:isolated-suite "not run at done …"`). An unstated omission reads as
coverage, and that is how a green status comes to mean less than the agent
thinks it does.

### How the write/done split was learned (2026-07-19)

Warning-level clj-kondo lint was flipped to REFUSE at the write grain, having
measured zero warnings across all 115 namespaces. It turned 33 assertions red
across 14 tests. Two causes:

1. **Wrong population measured.** The production store had zero, but a write
   gate applies to every write — including the fixture stores tests BUILD AT
   RUNTIME, which legitimately carry `unused-referred-var` (a three-line
   fixture doing `:refer [deftest is testing]`) and `unused-binding`.
2. **Wrong grain.** `unused-binding` is a WORK-IN-PROGRESS property. It is
   routinely, correctly true of a form mid-edit.

Same rule at the done grain: green, zero collisions.

**Two reusable lessons:**

- **Measure the population the rule will actually apply to**, not the one that
  is convenient to count. Every earlier flip in this sweep happened to have a
  production-only population, so this failure mode stayed hidden until a rule
  applied to writes-in-general.
- **A gate broad enough to catch everything will catch the tests that verify
  the gates.** `rules-test/done-surfaces-ambient-state-and-bare-throw` exists
  precisely to write rule-violating code. Targeted gates coexist with that; a
  blanket write-grain bar does not.

### Consequences accepted

- `done` is slower — it runs the whole in-image suite (166 tests here, vs ~68
  for the impacted slice). Deliberate: impacted-only answered the weaker
  question *"does what I touched still work"*, which is not what the word
  claims. Impacted SELECTION was also itself a source of misses (one untraced
  form used to collapse the whole narrowing, on 54.4% of real episodes), and
  running everything retires that machinery.
- Absolute rather than diffed, because this repo is the only slopp codebase
  and there is no legacy to deadlock. A store adopting slopp with pre-existing
  findings would be red at every `done` until clean — acceptable, because
  `done` reports rather than refuses, so nothing is blocked meanwhile.

---

## D-kondo-config (2026-07-20) — slopp owns the linter config, and `:level` means "can this be legitimate mid-edit?"

**Decision.** `slopp.index/kondo-config` is a static def, shipped with slopp,
passed EXPLICITLY to `kondo/run!`. It is not a file in the user's repo, not a
per-store knob, and not something a project configures — linter levels are
part of slopp's definition of clean code, like the dialect and the rule
registry.

**Why explicit rather than letting kondo resolve it:** kondo otherwise reads
config relative to the process CWD. That is the bug #134 fixed for the
*cache* — findings that varied by which directory the process happened to
start in. The tree is fileless, so a store cloned elsewhere must lint
identically or `done` means different things on different machines.

**The tiering rule — one question decides everything:**

> Could a form legitimately look like this MID-EDIT, on the way to something
> correct?

- **No → `:error`.** Blocks at every grain. Refusing immediately saves a
  wasted episode.
- **Yes → `:warning`.** `done` LISTS it (`:lint-warnings`) for the agent to
  judge; nothing refuses it.
- **Measured worthless → `:off`,** with the numbers recorded in place.

This puts the write/done grain split (D-rule-grain) into ONE declaration.
Both consumers simply read `:level` — `edit/lint-refusals` gates writes on
`:error`, `done!` counts `:error` and lists `:warning`. An earlier attempt
introduced a separate `write-blocking-lint` TYPE set alongside the levels;
it was deleted, because a second declaration of "what blocks" is a second
place for the two to disagree — the exact failure mode that hid five
`:error` advisories from the milestone.

**The tiering was chosen by the SUITE, not by taste.** Promoting every linter
to `:error` broke 13 assertions across 7 tests, in three distinct ways, and
each failure named a linter that is legitimately true mid-edit:

| broke | linter | why it is legitimate mid-edit |
|---|---|---|
| red-first TDD | `:unused-binding` etc. | a spec names a not-yet-written fn |
| module lifecycle | `:unresolved-namespace` | a legitimate forward reference |
| carried-lint compression | `:redundant-let` | pre-existing, untouched forms |

Those failures map one-to-one onto the warning tier. When a promoted linter
breaks a test, the test is usually right: it is demonstrating a legitimate
intermediate state.

**Two linters were measured and rejected outright**, recorded in the config
so the rationale is not re-derived:

- `:shadowed-var` — 156 findings, **81 of them the parameter `agent`**
  shadowing `clojure.core/agent`, which this codebase never calls. The rule
  an agent actually wants is "a symbol means one thing in one form", but the
  linter cannot distinguish "shadows something unused" from "shadows a fn
  used in this very form", and the volume is slopp's own domain vocabulary.
  Enforcing it renames the RIGHT words to prevent a hazard that has not bitten.
- `:unsorted-required-namespaces` — 62 findings, and the wrong TOOL. Require
  order is mechanical: the normalizer should SORT it. **Never warn about what
  a tool can fix.**

**Two were added for slopp-specific reasons, both measuring zero:**

- `:unused-value` — a value computed and DISCARDED in non-tail position is
  the language-level form of the bug that bit this codebase four times at the
  wire layer (computed, then dropped by an allow-list).
- `:missing-else-branch` — `(if x y)` returns an implicit nil, and this
  project's signature failure is a plausible wrong value rather than a crash.

**Caching:** the lint memo now includes a config fingerprint (`:cfg`).
Without it, editing a linter level silently did nothing until restart — the
config is part of the world a finding is true under, alongside the cache dir
and the dependency fingerprint.

---

## D-full-check (2026-07-20) — `done` is episode-scoped; the whole store is one explicit call

**Supersedes the store-wide half of D-rule-grain.** That decision made `done`
absolute — every namespace linted, dead surface store-wide. This walks that
back: `done` answers *did the work I just did come out clean*, which is the
question an agent can act on. Whether the STORE is clean is a different and
much slower question, and it is `full_check`'s.

| | scope | forced? |
|---|---|---|
| write | this form's coherence (`edit/write-coherence-lint`) | yes, refuses |
| `done` | the episode: whole in-image suite + impacted `^:external`; lint + dead surface over TOUCHED namespaces | automatic, REPORTS |
| `full_check` | every namespace, every tier (in-image, `^:integration`, `^:external`) | **never** — agent's call |
| `commit_point` | nothing of its own; gates on done's verdict | — |

`full_check` also retires any need for an integration-only or lint-only
tool: one call, everything, no tier flags to get wrong.

**`done` states its scope in EVERY result** (`:scope`), naming what it did
NOT cover and pointing at `full_check`. An unstated omission reads as
coverage — that is how a green status comes to mean less than the agent
thinks it does.

### The cost, accepted explicitly

Nothing automatically verifies the whole store before publishing. A red
`^:external` test the episode never TOUCHED will not stop a
milestone, and `commit-test/the-milestone-forces-no-whole-store-check` PINS
that, so it is a test someone must consciously change rather than a silent gap.

The word **touched** is load-bearing. "Gates on done's verdict" means the
milestone's `done!` is a *real* done — `:external? true`, so it runs the
impacted `^:external` slice exactly as a standalone `done` does. A milestone
therefore DOES stop over a red `^:external` test this episode touched
(`commit-test/a-milestone-catches-a-touched-red-external-test`); it is only the
UNTOUCHED corner that rides through to `full_check`. A 2026-07-20 review found
`commit-point!` had regressed to calling `done!` with `:external? false` — a
milestone weaker than a plain done, laundering a touched red external green.
Fixed; the two commit-tests above pin both halves.

The trigger worth internalising is **"you deleted a caller"**: dead public
surface appears in namespaces the episode never touched, which is the one
thing episode scope structurally cannot see. That is in the `:scope` reminder
and the tool description.

### The hole this opened, and the fix

Episode scope plus a milestone with no checks of its own meant **a red done
could be laundered by committing**: `done` reports red → ignore it →
`commit_point` runs a fresh `done` → nothing changed since → `:test-status
:none` → publishes green. Found by `episode-test/unused-publics-gate-the-done`.

`api/last-judged-done` fixes it: an empty done judges NOTHING, so the last
real verdict stands until new work supersedes it. It returns the whole
findings map rather than a status, so a standing red can still NAME what was
wrong — a refusal that cannot say why is one an agent cannot act on.

### Two calibration notes, both learned by breaking the suite

- **`:unresolved-var` must NOT block a write.** It is kondo-default
  `:warning`; promoting it into the write-coherence set broke red-first TDD,
  because a spec naming a fn in another namespace that does not exist yet is
  exactly what red-first IS.
- **Never warn about what a tool can fix — and check whether one already
  does.** A test asserting `missing-else-branch` counts at `done` kept failing
  with zero errors: the NORMALIZER rewrites `(if y 2)` → `(when y 2)` before
  lint runs. The linter is harmless but redundant there. Worth auditing
  `:redundant-fn-wrapper` and `:single-key-in` for the same overlap.

---

## D-tiers-internal-external (2026-07-20) — the axis is internal/external, not read/write

**Decision.** Purity tiers are **`:pure` / `:internal` / `:external`**.
`:reads` is RETIRED. (`:reads`/`:effects` remain accepted as legacy spellings
of `:internal`/`:external`.)

- **`:pure`** — referentially transparent. No mutation, no non-determinism.
  This is what lets the generative schema oracle (`analyzer-pure?` +
  `mg/check`) run on a form at all.
- **`:internal`** — may mutate IN-PROCESS state (a memo, a registry); touches
  nothing outside the process.
- **`:external`** — IO: files, subprocesses, network, the database.

**The evidence that decided it.** Measured across the whole store on the old
read/write axis: **6 `:pure`, 0 `:reads`, 19 `:effects`.** The middle tier had
ZERO members, because read/write puts a memo `swap!` in the same class as a
`git push`. On the internal/external axis the middle is populated
immediately — `render`, `edit.refs` and `cache` moved out of the same tier as
`db` and `repl`.

**Why this axis and not that one:** it is the axis that decides how a thing
must be TESTED, which is the whole reason the tiers exist.

| tier | test strategy |
|---|---|
| `:pure` | plain unit test, no setup; generatively checkable |
| `:internal` | in-image test plus a cache/state reset |
| `:external` | isolation — fresh JVM, temp dirs, cleanup |

Read/write cannot do this: an external READ needs the same isolation as an
external write (`slurp` needs the file to exist), so the distinction buys
nothing at the point where it would have to pay.

**Layering keys on EXTERNALITY, not tier ordering.** A non-`:external`
namespace may not require an `:external` one — but `:pure` MAY depend on
`:internal`, because an in-process memo is observationally pure from outside.
Forbidding that would mean the pure core could use no memoized helper, which
in this codebase means no pure core at all. The coupling is real but bounded:
a `:pure` namespace is then only as transparent as its dependency's cache
KEYS are correct — which is exactly why caches must go through one construct.

### The blessed cache (`slopp.cache`)

Every memo goes through `cache/cached` (value-keyed) or `cache/cached-last`
(identity-keyed). Hand-rolled memo atoms are what this replaces. It buys four
things, and only the last one is about tiers:

1. **Testability** — `reset-all!` clears everything; `without-caching!` makes
   every call recompute, so a test proves the COMPUTATION rather than a
   previous call's answer.
2. **Staleness** — the key is a value you pass, in one place. slopp's lint
   memo silently served findings computed under an old linter config until a
   config hash was added to its key; its validity check is FOUR
   hand-maintained terms, each a place to be wrong.
3. **One eviction policy** — not hand-rolled per site (kondo's memo cleared
   itself at 256 inside a `swap!`).
4. **Mechanically checkable tiers** — "does this namespace mutate only through
   `slopp.cache`?" is DECIDABLE. The alternative considered was a `^:memo`
   marker, rejected because "is my memo semantically transparent?" is an
   unverifiable author claim, and this session has been punished repeatedly
   for those.

**Two strategies, both real** — this was nearly missed:

- `cached` — value-keyed map with eviction. For small keys.
- `cached-last` — memoize-LAST keyed on `identical?`. For keys too large to
  hash: the whole-store reference graph is memoized on the STORE, and hashing
  that map every call would cost more than the computation saves. Sound
  because the store is immutable — a new value appears only on a write, so
  same identity means same content BY CONSTRUCTION. Wrong for anything
  rebuilt per call: two `=` values that are not `identical?` miss every time
  and the cache silently never hits.

**`cached` is deliberately NOT `!`-named.** D6 propagates effects across
namespaces only through `!`-named callees, so `cached!` would make every
memoizing caller effectful and defeat the entire point. `without-caching!` IS
`!`-named — it flips a global, and its callers are tests.

**Tiers are namespace declarations, never form metadata.** They are
`:module-tier` deltas carrying their `:prompt` (why) with full history — not
`^:meta` on a form, which has no provenance. And there is deliberately NO
per-form escape: if one `defn` could opt out, "this namespace is core" would
be unverifiable without reading every form, which destroys the only property
that makes the claim useful. The escape is to MOVE the form. That is the
pressure that produces the shape.

### Standing debt, deliberately not reclassified away

Remaining layering violations are genuine core→edge dependencies, not
classification noise: `edit.modules`/`refactor` → `index` (kondo writes a
cache DIRECTORY on disk), `api.orient` → `db`, `api.telemetry` → `api.rules`
(evals in the image). `slopp.index`'s two remaining hand-rolled caches stay
hand-rolled for now; it is `:external` regardless because of that directory.

---

## D-external-test-tier (2026-07-20) — `^:isolated` is `^:external`; the marker names the REASON

**Decision.** The test marker is `^:external`, matching the namespace tier.
368 forms swept in one intent; the gate accepts ONE spelling.

`^:isolated` named the MECHANISM (runs in a separate JVM) rather than the
reason (it touches the world outside the process). That is why nobody could
answer "should this test be isolated?" — the name did not say. With
`^:external`, the question is mechanical: **a test is external when it
exercises an `:external` namespace.**

Worth noting the codebase was already inconsistent: `isolated-test-run!`'s
docstring said "a FRESH EXTERNAL JVM" and the refusal said "external tests
run in the external suite". The tier was already called external while the
marker was called isolated, which is probably what made the old name
confusing in the first place.

### Two migration hazards, both general

**1. A live gate cannot be renamed atomically with the marker it enforces.**
The first sweep was REFUSED at step 6: `isolation-refusal` requires tests
calling `repl/start!` to carry the marker, and it runs from the OLD compiled
code while the group rewrites it — so a one-shot sweep is refused at the
first test it re-tags. It needs two phases: accept both spellings, sweep,
then tighten. This applies to any self-hosting system where a rule and the
code it governs share a store.

**2. A sweep rewrites prose DESCRIBING the sweep.** The transitional comment
explaining `:isolated -> :external` came out reading `:external ->
:external`.

**Tightening to one spelling was NOT optional.** Tolerating `^:isolated` as
well would be worse than rejecting it: the runner (`test-var-tiers`) reads
`:external`, so a tolerated old marker would pass the gate and then run
in-image and RECURSE. Two checks disagreeing is this codebase's recurring
failure; a compatibility shim would have manufactured a fresh instance.

### Demoting the mislabeled tests

**22 tests** dropped from the external tier to plain in-image units. The
criterion was the codebase's OWN: `edit/spawning-vars` defines what would
recurse in-image, and `isolation-refusal` REFUSES a write that drops the
marker from a test reaching one — so the gate arbitrated every demotion
rather than my judgement. The clearest case: `build-native-test/arg-style-t`
was spawning a JVM to test `arg-style`, a pure function over a clj-kondo map.

**Seven candidates were deliberately left external** (`git-client-test`,
`multiproc-test`, `commit-test`, `mcp-test`). They pass the no-spawning-var
check, but the check sees only DIRECT symbols — a fixture helper could spawn
without the test naming it, and an in-image recursion HANGS rather than
failing cleanly.

**The honest bound on the criterion:** it is sound for REFUSING (the gate
sees what a form directly calls) but not complete for PERMITTING. Demote on
it; do not trust it to clear the last few.

---

## D-api-decomposition (2026-07-20) — what `slopp.api` actually is, measured

**Status: IN PROGRESS, and the remaining step is a DESIGN CALL, not a refactor.**

`slopp.api` was 105 forms / 4030 lines and read as "the god namespace".
Measured on the internal/external axis it is less wrong than that suggested,
and the wrongness is specific.

### What the measurement showed

| | count | |
|---|---|---|
| thin pass-throughs (<15 lines AND delegating) | **10** | it is NOT a facade today |
| self-contained (no delegation to a deep ns) | **50** | implementation living at the top |
| over 40 lines | 34 | |

So `slopp.api` was not a facade being mistaken for a namespace — it was a
namespace doing implementation work. That settled the sequencing: push
implementations DOWN first, and only then ask the facade question.

### Extracted so far

- **`slopp.api.query`** (`:pure`) — 33 forms: the 25 pure query operations
  plus every pure helper they use.
- **`slopp.api.review`** (`:pure`) — `review-scan`, 122 lines of analysis.

`slopp.api`: 105 → 71 forms.

**`edit_move_forms` found the seam, not judgement.** The first attempt
proposed 25 forms and was REFUSED with a two-way-split analysis naming
exactly what was missing (seven pure helpers), then refused again
(`label-ancestors`). The direction it enforces is the correct one:
`revert-episode!`/`undo!`/`done!` calling INTO the moved set is shell→core
and fine; the moved set calling back out is a cycle. **Propose a cluster and
let the tool close it transitively** — guessing leaves a cycle.

### The remaining state, and the open question

`slopp.api` is now **71 forms: 11 pure, 51 internal, 9 external**.

The 51 are `:internal` because they mutate the **session atom** — in-process,
not IO. That is what a functional core with an imperative shell is SUPPOSED
to look like: decisions pure, orchestration mutating one owned piece of
state, IO at nine named entry points. The "63/105 effectful" reading was an
artifact of the read/write axis conflating a memo with a subprocess.

**THE OPEN QUESTION** — and it is the facade question in another form:
should the **9 external** forms (`open!`, `done!`, `commit-point!`, `build!`,
`full-check!`, `external-test-run!`, `config!`, `author-identity`,
`git-config-value`) live apart from the 51 orchestration operations?

- **For:** `slopp.api` becomes declarable `:internal` — the gate would then
  apply to the largest namespace in the store.
- **Against:** `open!`/`done!`/`commit_point` are THE primary operations;
  moving them decides what `slopp.api` *is*. Mechanically safe (the tool
  rewrites every caller), but it is a product decision, not a cleanup.

**Do not infer this one.** Decide it, then execute.

**RESOLVED 2026-07-20 (user decision: push the external usage out).** All
ten IO forms now live in `slopp.api.external`. The stated payoff did NOT
materialise, and the measurement is the useful part: `cleanup` reports
`slopp.api` still `{:tier :external, :supports :effects}`, with 50 of its
62 remaining forms blocking `:internal`. The IO was never those ten forms —
it is the SESSION. Every mutating operation writes a delta to sqlite and
drives the image subprocess, so `slopp.api` is an imperative shell by
construction. That is the correct shape, and the pure core already exists
beneath it (`.query`, `.shape`, `.modules`, `.review`, `.history`,
`.breakage`, `.attrs`, `.orient`). Do not re-attempt this for tier reasons.

### Not yet touched (readability, not architecture)

`edit-group!` (156), `move-forms!` (152), `edit-replace!` (134) — long
internal forms. And `done!` at 200 lines, worth its own pass: it is the
most-changed function in the codebase.

## D-analysis-not-io (2026-07-20) — `:analysis` and `:findings` want different worlds

The tier-layering check reported **9 core→shell dependencies** and nothing
enforced them, which violates the standing "never just warn" rule. The cause
was one shared kondo pass.

`slopp.index` produced `:analysis` and `:findings` from a single cached run
against kondo's on-disk cache dir. The two have different honest keys, and
the store already said so: `run-kondo` was keyed on SOURCE alone *"because
that is the honest key for `:analysis`"*, while findings carry `:cache-dir`
and `:fp` precisely because they depend on cross-namespace cache state.
Sharing the pass made analysis IO — and `analyze` is what nearly the whole
pure core reaches for, so every one of those namespaces inherited an
external dependency it did not actually have.

**Verified before restructuring around the claim** (the premise was
checkable and therefore had to be checked): a warm-cache run and a
`:cache false` run differ ONLY in `:fixed-arities` on cross-namespace
var-USAGES. Nothing in slopp reads that — every arity reader
(`query-outline`, `deps/surface`, `build/arg-style`) takes it from
var-DEFINITIONS, which are same-source.

Three namespaces now:

| | tier | holds |
|---|---|---|
| `slopp.index` | `:external` | the kondo run, cache dir, `lint`, the atoms |
| `slopp.index.analyze` | `:internal` | cache-free analysis, blessed memo |
| `slopp.index.derive` | `:pure` | 15 forms deriving facts from analysis |

**The false start is the transferable part.** The first attempt split only
the pure derivers out — defensible structure, and it discharged **zero** of
the 9, because callers depend on `slopp.index` for `analyze`, not for the
derived facts. The split that looks principled is not always the one that
moves the number. Re-run the check; do not inspect the new shape and infer.

**Cost, measured both sides.** The shared pass existed to hold per-write
kondo cost at one run; this trades it for two. Benchmarks: calculator
504→460ms, inventory 114→109, wordstats 147→144, identical token counts —
no regression. The test that guarded the old invariant was rewritten rather
than deleted, and now guards the concern that was actually load-bearing:
neither pass may recompute for the same source.

Layering went **9 → 5**. The remaining five are genuine judgement calls, not
artifacts: `slopp.api.review` really uses `lint`, `.orient` really uses
`slopp.db`, `.query`/`.telemetry` really use `slopp.api.rules`.

**Corollary, and it caught four namespaces here:** a stale require is not
cosmetic. A namespace inherits the TIER of everything it requires, so a
require left behind after a move makes a `:pure` namespace report as
depending on the shell for something it no longer uses. `move-forms!` now
prunes orphaned requires on BOTH sides — source and rewritten caller —
scoped to the move's own damage, never a blanket prune (a require kept for
`defmethod` registration is indistinguishable from a dead one).

## D-serving-is-not-adoption (2026-07-21) — the store is created by the first WRITE, never by serving

The MCP server is launched by the editor in whatever directory the user has
open. `api/open!` called `db/open!` unconditionally, and `db/open!` creates —
so **every project a user opened got a `.slopp/store.db`**, whether or not
they had ever heard of slopp. Three writers compounded it: the server created
the store, the plugin's `UserPromptSubmit` hook did `makedirs(".slopp")`
unconditionally to drop `pending-intent`, and the `Stop` hook — correctly
gated on the store existing, which the first two guaranteed — then wrote a
`"session pause"` checkpoint at every session end, forever, into a store with
no code in it. Measured damage in `findings-log.md`.

**The decision: opening a session ASKS whether a dir is slopp-managed; it
never answers yes on the dir's behalf.** `db/open!` takes `{:create? false}`
and returns nil when there is no store; `external/open!` uses it, and a
session on an unadopted dir runs cache-only with `:db` nil — which is the
already-tested ephemeral path, not a new one. `api.session/ensure-db!` on the
commit path materializes the store at the first real write. That is the ONLY
place a directory becomes slopp-managed implicitly.

Three things had to follow the store rather than the dir, and each was a
separate leak:
- the kondo cache dir (`external/open!` keyed it on `dir`, so an unadopted
  dir still got a `.slopp/kondo-cache`) — now keyed on `conn`;
- the git smart-HTTP listener (`git/open-ctx!` opens its own connection, and
  would have recreated exactly the store being avoided) — `mcp/-main` now
  starts it only for a session that has one;
- `turn/append-marker!`, the hook-driven CLI path, which opened its own
  connection too. A marker is provenance ABOUT a store; with no store it is a
  no-op, never an adoption.

**This makes the code match its own shipped documentation.** The `slopp-setup`
skill has always told users "the server creates an empty store on the first
write" — the behaviour it described was the intended one all along, and only
the implementation disagreed.

**Accepted cost, and it is the honest trade.** In a genuinely fresh dir the
prompt hook writes no `pending-intent` (it now requires a store too), so the
first write is refused with `no open turn — call turn_begin {intent: …}`. One
extra explicit call, once per new project, and the provenance is *better* for
it: the agent supplies the user's verbatim ask rather than the hook inferring
it. Rejected the alternative of auto-opening a turn for storeless sessions —
it would silently attribute a project's very first write to nothing.

## D-web (2026-07-22) — web applications: the boundary program applied to HTTP

The deferral in `ideas/deferred-framework-and-http.md` is lifted; the design
center is a THIRD-PARTY developer building an arbitrary web app through slopp
(slopp's own endpoints are the dogfood, never the design driver). Settled, to
be built in waves on the `web` store branch (plan of record: the approved
web-applications plan; frictions log: `ideas/web-wave-frictions.md`):

- **Capability registry (wave 0, SHIPPED 2026-07-22).** A store declares what
  kind of application it is in a `capabilities` config file riding the
  EXISTING `:config` CRDT (G9 — no new fold-field). What is new is the
  DECLARED registry (`slopp.api.capabilities/registry`): one entry per key —
  `{:key :type :default :doc}` — from which the validator, the effective-value
  read, and `query_capabilities` all derive (the rule-catalog shape, applied
  to configuration). Types are a small STRUCTURAL vocabulary, not malli:
  this ns loads in the server/boot JVM, kernel deps only (the two-process
  split; the schema-refusal precedent). Write-time validation: `config_file`
  on the capabilities path REFUSES unknown keys (a typo'd capability that
  silently does nothing is the nil-pun failure the registry exists to kill)
  and type-failing values, with teaching. `app.main`/`app.name` persist the
  app manifest — `build!` falls back to them, so the entry point is store
  state, not a tool argument. `http.enabled` (default false) is the master
  opt-in every web rule will be gated on: a store that never opts in is
  untouched (the purity-tier adoption lesson).
- **An endpoint is a `defn` with `:web/*` metadata** (method, path, auth,
  schema) — no macro, no dialect change, no central route table. Metadata is
  read straight off the stored node like `^:export`/`:malli/schema`, so every
  existing gate applies and routes enumerate with zero eval.
  **Wave 1 SHIPPED 2026-07-22:** the `:web/*` markers are declared edges in
  the reference graph (`declared-refs` — `:web-endpoint`/`:web-effect`/
  `:web-read`, so endpoints and performers never trip the unused gate);
  `query_routes` (via `slopp.api.web`, `:pure`) reports the surface from the
  SAME shared derivations (`edit.modules/web-endpoint-rows`/`web-performers`)
  the four new write gates check — `web-auth-refusal` (default-deny),
  `web-route-collision`, `web-undeclared-effect`, `web-unsafe-get` (a safe
  method may neither declare effect kinds nor reach a mutation) — registered
  in `per-form-write-gates`, cataloged, severity-dialable, and all inert
  until `http.enabled`. Design note learned landing it: the route-row keys
  are `:web/effects`/`:web/reads` NAMESPACED — bare `:effects`/`:reads` trips
  the retired-tier-vocabulary advisory, and slopp namespacing what it adds is
  the standing §2 rule anyway.
  **Merged to main 2026-07-22** — after the branch dogfood fixed the merge
  pipeline itself: `merge-logs` replays `:move` deltas (order is
  load-bearing since D7, the "cosmetic" premise predated it);
  `merge-into-session!` loads new namespaces and existing namespaces'
  changed forms in ONE interleaved dependency-ordered pass (a new
  downstream ns must compile against just-merged upstream forms); dead
  changed-ids (added-then-deleted on the branch) are pruned and
  `hot-load-form!` guards vanished forms; the one-shot CLI's errors carry
  their cause chain and stack. Full friction narrative:
  `ideas/web-wave-frictions.md`.
- **Request/response maps stay RING-shaped**; slopp namespaces only what it
  ADDS (`:web/identity`, `:web/effects`, `:web/reads`, …). The training-data
  prior is an asset, not a hazard; the novelty budget is spent on
  effects/reads-as-data and declared auth only.
- **Handlers are pushed pure**: reads declared as data (`:web/reads` naming
  app-defined `^{:web/read k}` performers), writes returned as data
  (`:web/effects` naming `^{:web/effect k}` performers), the dispatcher
  interprets both — slopp never learns domain vocabulary. Escape ladder:
  declared reads → read capability → `^:web/effectful` (`:external` ns).
- **Auth is declared on the form** (`:web/auth`, default-deny via the
  `web-auth-refusal` gate) and enforced by the dispatcher before the handler
  is reachable; providers/groups live in the capabilities config; secrets are
  `env:NAME` indirections.
  **Wave 3 core SHIPPED 2026-07-22:** `slopp.web.auth` (`:internal`) —
  three providers (bearer, static basic-auth with sha-256 verify,
  proxy-header gated on trusted `:remote-addr`), first-claim-wins in
  declared order, config group augmentation, env-indirect secrets through
  an injectable `getenv` seam; `dispatch/handle!` resolves identity through
  `:web/auth-config` when the request carries none (a pre-resolved identity
  is respected); `config-from-values` is the ONE parser from capabilities
  strings to runtime config; `web-unknown-group` gates the policy
  vocabulary (a typo'd group is the authz nil-pun) and the capabilities
  gate refuses credential literals (`web-secret-literal` behavior — the
  config is git-projected). Proven 401/403/200 over the wire on http-kit.
  DEFERRED, deliberately: OIDC (last per plan, with its own native-image
  proof) and the `web-public-mutation` done-advisory. ALSO: the resync
  surfaced a merge bug minting a DUPLICATE form name via the rename
  interplay (frictions #19 — image ran the stale shadow while reads showed
  the fresh form; repaired by id-addressed delete, engine fix owed with
  #16).
- **Server: http-kit default adapter** (native-proven, ring-compatible,
  WebSockets), `:jdk` kept as the zero-dep fallback, Helidon/hirundo the
  named in-process-TLS upgrade path, ring-jetty rejected (native-image
  breakage). The adapter is a capability key behind a one-function seam.
  **Wave 2 SHIPPED 2026-07-22:** the `slopp.web` module — `router`
  (`:pure`), `routes` (var-metadata scan, `:internal` — the universal route
  source: live store, jar, and native binary all answer from var meta),
  `dispatch` (`:external`, the shell by declaration: route → policy →
  declared reads → handler → all-or-nothing effect interpretation, failures
  as data, ex-info `:web/status` mapping), the facade (`context`/`handle!`/
  `enforce`/`authorized?`/`serve!`/`stop!`), and BOTH adapters behind the
  seam. `slopp.http`'s `/call`, `/mcp`, `/metrics` are declared endpoints
  served through the facade (wire-compatible — the old transport tests pass
  unchanged), and slopp's own store runs `http.enabled = true`. Design
  corrections learned landing it: `enforce` not `require!` (a throwing
  guard mutates nothing; a bang would falsely taint every pure handler
  doing row-level authz); test namespaces' endpoint-shaped forms are
  FIXTURES (excluded from rows so they neither report nor claim paths);
  the `:web/effectful` marker must live ON THE NAME with the rest of the
  contract; http-kit rides both the store manifest AND kernel deps.edn
  (mirrored — boot must load `server.httpkit`).
- **Static assets: content-addressed blob table** (sha256 → bytes), `:files`
  values polymorphic, deltas carry the sha (wave 4).
  **Wave 4 SHIPPED 2026-07-22 (on main directly — bidirectional branch
  merges are paused until the #16 causality redesign):** `record-file-put`
  takes `:encoding "base64"` + `:content-type`; the manifest entry becomes
  `{:sha :content-type :bytes}` with bytes in the `:blobs` cache and the
  `blobs` db table (same tx via `put-blobs!`, INSERT OR IGNORE); the delta
  carries the sha only. `store/file-content` is the ONE polymorphic
  accessor; `file_put`/`file_get` speak base64 on the wire; the git tree
  and `build!` emit real bytes (`insert-tree!` accepts byte arrays, the
  projection threads a blob reader); `merge-logs` unions theirs' blobs.
  Serving: `slopp.web.static/mount-routes` (mounts + a reader → `:public`
  GET rows answering `:web/raw` responses both adapters write verbatim —
  no JSON wrapping), wired in `slopp.http` from `http.static.*`
  capabilities over the store, so an edited asset hot-serves under
  `--live`. LATENT BUG FIXED en route (frictions #21): merge-logs' unknown-
  op default silently SKIPPED every `:config-put`/`:file-put` — main had
  lost its whole capabilities config across three wave merges; state-
  carrying non-code ops now replay through `store/replay-delta`
  (key/path-grain last-writer-wins), guarded by
  `merge-test/config-and-files-cross-the-merge`. Deferred: single-segment
  mount files only (the router's declared param scope), blob pruning via
  `cleanup` (blobs are immortal-by-default, the git model), and
  `-H:IncludeResources` (wave 5).
- **Wave 5 partial SHIPPED 2026-07-22:** the native recipe carries assets —
  `native-script` copies manifest paths into `classes/` (resources on the
  compile classpath) and passes `-H:IncludeResources`, so a binary serves
  its own files; `static/file-or-resource-reader` is the built-app reader
  (filesystem under the app root first, classpath resource fallback,
  extension-derived types) — one `mount-routes` reader works for jar and
  native alike. THE WAVE-5 TAIL, deliberately together: the slim
  `io.github.nvoxland/slopp-web` jar (user apps' dep), the native-proof CI
  extension that consumes it (a sample web app with an asset, compiled and
  curled), and `slopp.boot` add-libs for store manifests — plus wave 3's
  OIDC. Each needs the release surface the others define; shipping them as
  one arc beats four stubs.
