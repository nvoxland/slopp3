# Operation API (`slopp.api` + `slopp.mcp`)

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
  prompt, checkpoint label, commit/turn description, turn-end note, AND its
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

Ordering note: `query-search-history` sits above `human-time`'s `defn-`, and
`query-form-history` above `render-form-history-text`/`status-after` — all in
the top-of-file `declare`. A fresh-JVM `clojure -M:test` (not the warm REPL)
is what catches a missing one.

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
- `rename!` — coordinated multi-form rename; see `slopp.refactor` notes below.
- `change-signature!` (P2, tool `change_signature`) — the defn + every CALL
  site as ONE intent: `source` replaces the defn (same name), each call's
  arg list is rebuilt from the `calls` template ($1..$9 = the site's
  existing arg sources; the callee stays as written, so aliases survive).
  Planned in `refactor/change-signature-plan`, executed via `edit-group!`
  (all gates, one verification). Higher-order references return under
  `:manual`; nested self-call sites / template-arity misses are hard errors
  pointing at `edit_group`. Companion: invalid-arity lint refusals carry
  the "defn + callers in ONE edit_group, or change_signature" hint.
- `edit-group!` — several `:replace`/`:add`/`:delete` steps as ONE atomic
  intent (F2): all steps apply to a store VALUE first (any error → whole group
  rejected, nothing committed — store purity makes this free), then commit +
  persist + hot-reload together and verify ONCE. Deltas share a `:group` id.
  Use for every multi-form refactor — it avoids the mid-refactor red + wasted
  diagnostic restart.
- `test-run!` — traced+diagnosed run; `ns-sym` nil = the WHOLE project in
  one image eval (instrumentation paid once — F-3c1); refreshes the trace
  map. `query-eval` surfaces evaluation errors as `{:error msg}` (F-3c2);
  `query-references` scans every namespace (F-3c3). `query-eval` strips
  `:reload`/`:reload-all` from `require`/`use` forms (`edit/strip-image-reload`):
  the image has no source files, so a store ns is loaded via `load-ns!` not the
  classpath, and the muscle-memory `(require 'the.ns :reload)` would otherwise
  throw FileNotFoundException instead of the intended no-op.
- `checkpoint!` — unit-of-work boundary (user-designed): deterministically
  normalizes every form changed since the last checkpoint (`slopp.normalize`,
  conservative kibit-style rules, node-level so inner formatting survives),
  commits ONE `:normalize` group delta, hot-reloads + re-verifies affected
  tests, records a labeled `:checkpoint` delta. Never rewrites silently
  mid-edit — only at this explicit call. Add rules deliberately (they must be
  provably behavior-preserving) and note them in the normalize ns.
- `commit-point!` — MILESTONE (P4-m7): the checkpoint pipeline, then a
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
  is bound OFF for determinism). On add, `slopp.deps` analyzes the dep's own
  jars (classpath diff → clj-kondo) into an API SURFACE (provided namespaces +
  per-var arities/docs/macro flags), memoized per `coord@version` (process
  memo + durable `dep_surface` table); `deps-add!` returns `:namespaces` +
  `:vars` count. MCP: `deps_add {lib version|coord}`, `deps_remove`,
  `deps_list`, `deps_pure {target pure?}` — assert a dep pure (narrow M3's
  effectful-by-default boundary) at var / namespace / whole-lib granularity
  (a lib expands to every namespace it provides; see `dependencies.md`).
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

## Concurrency (item 4 — CRDT-aligned, no locks)

Single-form writes (replace/add/delete/move) commit through
`rebased-write!`: the pure store transform runs INSIDE `swap!`, so concurrent
DIFFERENT-form writes rebase and all land (the granularity dodge, made real);
if the target form itself changed since the op began → `{:conflict ...}`
(C5's MV-register semantics, Phase-1 face). The compile gate runs once before
commit (form content is invariant across rebases). Multi-form ops
(group/rename/extract/checkpoint) guard with conflict-on-contention rather
than rebasing. Persistence is ORDERED via a per-session agent
(`persist-async!` — element rows derive from the current store at execution
time, so they never regress); `close!` awaits the queue. The image needs no
locking: all image work rides ONE nREPL session (per-eval serialization) and
`traced-run`/hot-loads are single evals — keep multi-step image work inside
one eval. `*pre-commit-hook*` is the deterministic test seam.

## `slopp.refactor` (rename mechanics)

Position-based: clj-kondo gives resolved sites; **use `:name-row`/`:name-col`
for usages** (`:row`/`:col` point at the CALL's paren, not the symbol —
learned the hard way). Sites → owning element via `render/element-offsets` →
element-local positions → rewrite-clj position-tracked zipper replaces exactly
those tokens (descending order so positions stay valid). Shadowed locals are
never touched because kondo never reports them as var usages.
**Limitation:** symbols inside `:refer` vectors aren't var-usages → not
rewritten.

## Transports

Two transports share the SAME dispatch (`mcp/handle`):
- **MCP stdio** (`clojure -M -m slopp.mcp [dir]`) — Claude Code and Codex
  (`config.toml` recipe in README). Optional dir = durable session. The
  in-repo `.mcp.json` runs it THROUGH `slopp.boot` (`-m slopp.boot . --snapshot`)
  so slopp serves from its own store, no exported source — see
  "Running from the store" below.
- **HTTP** (`clojure -M -m slopp.http <port> [dir]`, or
  `http/start-server!` programmatically) — localhost-only JSON for
  curl/scripting/evals; `/metrics` returns per-call payload sizes.
- **Git smart-HTTP** (`clojure -M -m slopp.git <port> [dir]`, or
  `git/start-server!`) — P4-m8: any git client clones/fetches/pushes the
  milestone projection at `http://127.0.0.1:<port>/slopp.git`.
  JGit's UploadPack/ReceivePack own the wire format (stateless RPC,
  protocol v0); `ensure-projected!` runs before every refs advertisement,
  so foreign commit points from live sessions are served without a
  restart. Pushes import through `import-push!`: net span → ingests + ONE
  verified edit group + a `:commit` marker per incoming commit (original
  sha preserved); the api session (image included) boots LAZILY on the
  first push — clone-only servers never pay for it. Red tests land
  honestly; compile failures and structural violations reject with the
  reason on the pusher's terminal. `refs/heads/wip/<branch>` mirrors
  un-milestone'd live state (read-only, deleted when clean) — tools
  `git diff origin/main..origin/wip/main`. Localhost-only, no auth.
  **Embedded (M7):** `slopp.mcp/-main` on a durable dir ALSO opens this
  listener in-process on a dir-DERIVED port (`git/derived-port` — stable
  across restarts so a saved `git remote` keeps working; a taken port
  falls back to ephemeral; the actual `:port`/`:url` is returned). The
  listener keeps its OWN lazy session, so a push never perturbs the
  agent's checkout. The `query_git` tool surfaces `:git-url` (stashed on
  the session by `-main`). So the agent's own server IS the git remote —
  no external daemon. `slopp.git`'s own `-main` also defaults its port to
  `derived-port` now. GOTCHA: keep
  `slopp.git` reflection-free — reflective JGit calls resolve classes via
  the per-thread classloader and break on HTTP dispatch threads (only
  visible under add-lib REPLs, but the hints also keep the hot path
  cheap).

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
- **The series runs itself (Q10/Q11):** `commit_point` on a store with
  `git-remote` meta pushes the projection and reports `:published`
  (errors ride along; the milestone never fails on publish trouble).
  `edit_rename` results carry `:mentions` — prose/string occurrences of
  the old name the structural rename can't rewrite — and `edit_group`
  steps now include `:subform` (+`:text`) and `:require`, so the
  follow-up fixes are one atomic call.
- **Rock 4 reads:** `query-flow` (boundary-guarded keyword scan — every
  form a field touches, with lines) and `query-impact` (kondo var-usages:
  `:arity` present = call site, absent = value/higher-order ref; plus
  trace-map coverage). Impact is change_signature's discovery as a read —
  plan the edit before paying for it.
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
- `query_git` reports the local read-only listener URL AND `:external`
  (`git-remote`/`git-base-sha`) when set.
- CLI (fileless tree — everything enters through the boot trampoline):
  `clojure -M -m slopp.boot <dir> --main slopp.sync/-main
  clone <url> <dir> | push <dir> [url] | pull <dir>`. Auth:
  `SLOPP_GIT_TOKEN=$(gh auth token)` env on the command. Proven against
  real GitHub (nvoxland/slopp3): push → API edit → pull → FF push.

## Running from the store (`slopp.boot`)

- The entry `clojure -M -m slopp.boot <dir> [--snapshot|--live]` runs the
  store's program WITHOUT exported source: `load-store!` reads every ns's
  byte-exact source with raw next.jdbc, `dependency-order`s them (parses ns
  requires — a self-contained mirror of `store/ns-dependency-order`), and
  `load-string`s each into THIS jvm with a `*loaded-libs*` stamp (in-process
  `image/load-ns!`), then invokes `--main` (default `slopp.mcp/-main <dir>`).
  This is what `.mcp.json` runs. `slopp.boot` is self-contained (next.jdbc +
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
  agent sessions; `slopp.boot` itself stays kernel-only (the sugar just
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
