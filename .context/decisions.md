# Decision log

Settled decisions. Don't re-litigate silently — revisit explicitly and record
the change here (same commit).

## D — dialect & verification philosophy

- **D1 — No `@examples` / "deterministic choke point".** Old slopp's mechanism
  presumed an untrusted black-box compile step; slopp2's agent authors real,
  readable forms with a live oracle. Verification = tests + REPL observation.
  Form-granularity comes from **runtime tracing** (which forms did each test
  exercise), not co-located examples.
- **D2 — Contracts are an optional, library-agnostic boundary tool.** Shape vs.
  behavior are different lanes; tests+REPL own behavior/requirements. Nothing
  contract-library-specific is built in (no Malli/spec coupling); never
  enforced by the system, anywhere. Lean INTO Clojure's data dynamism — the
  live oracle is what makes that safe for a limited-context agent.
- **D3 — Dialect = allow-by-default with a denylist** (analysis defeaters:
  `eval`, `alter-var-root`, `binding`, `gen-class`, `definline`,
  `read-string`). Keep data dynamism; constrain metaprogramming dynamism.
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

## P4 — Phase 4 (multi-agent)

- **P4-m1 — Shared-session multi-agent = the first Phase-4 face.** One
  server process owns THE session (store + image + db); N agents connect via
  native MCP over streamable HTTP (`POST /mcp`, single-JSON responses,
  notifications → 202). Concurrency safety is the item-4 substrate (atomic
  rebasing commits; same-form → surfaced conflict; ordered persistence).
  Every write accepts an optional `:agent` recorded on its delta —
  provenance is per-agent from here on. Two separate server PROCESSES on one
  store.db remain unsupported (divergent in-memory stores); that's what
  fork/merge (m2) and replica sync (m3, deferred) are for.

- **P4-m2 — Fork = a copied project dir; merge = delta-log replay
  (C4/C5 activated).** `store/merge-logs` replays theirs' suffix onto ours,
  form-id-keyed: different-form work lands (granularity dodge across
  replicas); identical changes converge silently (⇒ merge is idempotent);
  same-form divergence = MV conflict — ours kept, theirs surfaced in
  `:conflicts` and on the `:merge` delta, resolved by hand. Add/add id
  collisions remap to fresh ids (fork-point detection compares full delta
  VALUES — both sides allocate the same next id for different work).
  Changeset ops (rename/normalize) apply all-or-conflict; `:move` skips with
  a note. `api/merge!` owns image loads (new nses in dep order, then changed
  forms through the compile gate) + whole-touched-nses verification + ONE
  `:merge` provenance delta. Globally-unique ids (C2's uuid/lamport) remain
  deferred — remap suffices for dir-forks.
- **P4-m2.1 — Iterated merges are exact via causal delivery (user-probed).**
  Replayed deltas are re-minted with OUR ids, so without bookkeeping a
  continuing fork's round-2 work false-conflicted ("both sides edited") —
  our copy of THEIR round-1 work looked like ours. Now every replayed delta
  carries `:merged-from <their-delta-id>` and the `:merge` delta records
  `:applied [their-ids]`: delivered deltas never replay again, and
  `:merged-from` deltas are excluded from the ours-touched conflict set.
  Fork → merge → keep forking → merge again is a supported loop; genuine
  same-form conflicts still fire. (Conflicted deltas are NOT marked
  delivered — they resurface until resolved or content-converged.)

- **P4-m3 — Branches within one repo (user-requested).** A branch is an O(1)
  snapshot of the store value, sharing the delta-log prefix by construction —
  so `branch_merge` IS the m2 engine, causal delivery included. The single
  live image gets CHECKOUT semantics: `branch_switch` swaps the store and
  reloads only namespaces whose source differs (removed ns → fresh image);
  the trace map resets across lines. Durable sessions persist each branch as
  its own mini store-db under `.slopp/branches/<name>/` (full snapshot on
  create, normal write-through while active, lazy load on switch; survives
  restart). Merge direction: into the CURRENT line (checkout main to merge
  down); the branch survives and can continue. Multi-agent note: one active
  checkout per session — concurrent multi-line work wants forks (or an image
  pool, deferred).
- **P4-m3.1 — Cross-merge id-maps persist, scoped per source.** Found by the
  m3 tests: merge #1 remaps a branch's added form to a fresh id, and without
  remembering that mapping, merge #2 resolves the branch's follow-up edit
  against the WRONG form (mainline's same-numbered add) — silent corruption,
  not just false conflicts. The `:merge` delta now records `:id-map` next to
  `:applied`, and BOTH are scoped by `:from` (different sources mint
  colliding delta AND form ids). merge-logs takes `:from`.

- **P4-m4 — Line-owned images: park / adopt / reap (user-proposed).** Each
  branch line owns its image. Switching away PARKS the outgoing line's image
  intact (REPL state included — safe because inactive lines are immutable,
  so a parked image stays in step with its parked store by construction);
  switching back ADOPTS it (instant, same process); a line with no image
  BOOTS one on demand (warm spare applies). Parked images retire after
  `:branch-image-ttl-ms` idle (default 10 min; per-session daemon Timer
  calls `reap-idle-images!`, also callable directly); `branch_delete` and
  `close!` stop them outright. This replaced m3's diff-reload checkout
  wholesale — images are never mutated to match a line, they belong to one.
  P1 economics respected: JVMs are spun up on demand and reaped, not held
  per branch forever.

- **P4-m5a — Storage inversion: the db is the journal of record
  (user-directed re-architecture).** Toward per-agent servers on shared
  storage: durable commits are now JOURNAL-FIRST — `db/append!` lands the
  new deltas + touched element rows + id counter in ONE conditional
  transaction iff the head still equals the commit's base; the in-memory
  store is a CACHE that only ever trails the journal (`refresh-cache!`
  advances it, never regresses). Losers refresh + rebase (same granularity
  dodge, arbitrated by SQLite's cross-process writer serialization — WAL +
  busy_timeout; "SQLite is single-process" was OUR in-memory-primary
  assumption, not SQLite's limit). Ephemeral sessions keep the
  starvation-free in-swap-transform commit. The async persist queue is
  DELETED — the append IS the persist. Next: m5b multi-process protocol
  (foreign-delta refresh into images), m5c per-agent servers with private
  checkouts via `.mcp.json` stdio.

- **P4-m5b/c — Per-agent servers on shared storage (the Phase-4 endgame,
  user-architected).** `db/data-version` detects foreign commits for one
  PRAGMA read; `sync-with-journal!` (called by the MCP dispatch before every
  tool) refreshes the cache, reloads changed namespaces into the LOCAL
  image, and invalidates touched trace entries — co-resident servers
  converge continuously while m5a's append-CAS arbitrates their writes.
  Checkouts are per-server state over shared branch storage: each agent's
  `.mcp.json`-spawned stdio server holds its own branch, image, and REPL
  runtime; `:data-version` is per-connection, so branch ops re-init it (the
  bug the m5c test caught). The shared-HTTP single-server mode (m1) remains
  for process-light sub-agent swarms. Deferred: incremental delta-suffix
  refresh (full load-store per sync is fine at current scale); branch-create
  races between servers.

- **P4-m5d — Branch identity = name + line-id (user-probed).** Branches
  carry a uuid `:line-id` minted at creation (on the branch's store value;
  persisted as a meta row; shown in `query_branches`), and merge causal
  state is scoped by `branch:<name>#<line-id>` — so deleting and recreating
  a branch name is a genuinely fresh identity, not an accident of the
  monotonic id counter. Fork DIRS can't mint identity (cp -r), so merge
  guards instead: a "delivered" delta whose CONTENT (source texts — the
  form-id keys are remapped on replay) doesn't match our `:merged-from` copy
  means the path was recreated over dead history → loud
  `{:error "merge identity mismatch ..."}` instead of silently swallowing
  the new fork's work. Same-branch multi-agent remains the default working
  mode; branches are opt-in isolation.

- **P4-m6 — Episodes: automatic per-agent work units between checkpoints
  (user-designed).** The "micro branch" need is met WITHOUT branches: an
  episode is DERIVED from the journal (no tagging) — an agent's deltas since
  its last checkpoint. Boundary rule: your own last checkpoint; an agent
  that never checkpointed inherits the last stable spot BEFORE its first
  activity (so pre-existing history is never "contested"). `query_changes`
  = net per-form :was/:now diffs + steps + red→green verification arc;
  `checkpoint {agent}` closes and normalizes ONLY that agent's episode
  (parallel sub-agents don't interfere); `episode_revert` rolls the episode
  back as ONE atomic verified group, SKIPPING forms other agents also
  touched (:skipped-shared — never stomped); `query_history {collapse:true}`
  reads history at episode grain. Chosen over literal auto-branches because:
  zero per-write cost, append-only provenance preserved (collapse is a VIEW,
  not a squash), and shared-line continuous sync semantics unchanged.
  Discipline: parallel sub-agents MUST carry distinct :agent labels (SKILL).
  Deferred: isolation-until-stable as an opt-in built on real branches.

- **P4-m6.1 — Turn trees: label paths + timestamps (user-probed).** slopp
  can't observe conversational turns (it sees tool calls); the parent
  agent's checkpoint-bounded episode is the turn proxy. Hierarchy comes from
  the LABEL CONVENTION `parent/child` on sub-agents (set once at spawn —
  already required for episode independence): the collapsed history nests a
  child episode under the parent episode whose span contains it (orphans
  stay top-level). Every delta now carries `:at` (epoch ms) for forensic
  time — shared prefixes stay value-identical (copied), and replayed deltas
  differing by :at is fine because causal delivery, not value-identity,
  governs iterated merges.

- **P4-m6.2 — Turns are explicit AND enforced (user-designed).**
  `turn_begin {agent, intent: <verbatim user ask>, user}` / `turn_end` record
  the ROOT intent as `:turn-begin`/`:turn-end` deltas; the collapsed history
  wraps the turn's episode tree in a `{:turn {:intent :user :episodes}}`
  bracket. Real servers (mcp/-main, http/-main set `:require-turns?`)
  REFUSE writes without an agent label + open turn — the compile-gate
  precedent: hard gates with teaching errors beat conventions. Sub-agents
  ride their root agent's turn (path labels); `checkpoint` is always allowed
  (it closes work). api-level sessions stay ungated (tests, seeding,
  scripts). The zero-ceremony path: Claude Code hooks run the one-shot
  `slopp.turn` CLI (UserPromptSubmit → begin with the verbatim prompt,
  Stop → end), appending markers OUT-OF-BAND to the journal — the agent's
  server absorbs them via m5b sync, so the model never has to relay its own
  instructions. A turn may end red — failed turns are history too.

- **P4-m7 — Commit points: the MILESTONE grain, purely in-journal
  (user-designed).** `commit_point {description, agent}` = the full
  checkpoint pipeline, then a `:commit` marker delta `{:description :target
  :status :agent :at}` — a named pointer at the just-checkpointed head; the
  better-controlled milestone above turn ends, episode ends, and plain
  checkpoints, per branch. GREEN-GATED: red verification refuses the
  milestone (the checkpoint still stands) unless `:force true`, which
  records `:status :red` honestly. `:target <past delta id>` = pure
  retroactive marker (status derived from the log's last `:verify`).
  Markers-as-deltas ride the journal, branch snapshots, and merge replay
  for free (`:commit` is a no-content op in `replay-delta`). Surfaces:
  `query_commits` (newest first; targets anchor query_changes from/to
  between-milestone diffs), COMMIT rows in the collapsed history
  (contains-searchable by description). **Git integration explicitly
  REJECTED for now** (user, 2026-07-04): a `git_export` projection
  (build! + one git commit per milestone + sha cross-link) was built and
  removed same-day — commit points are slopp-internal; whether/how to
  bridge to git remains an open question, to be driven by demand, not
  built ahead of it. *Revised same day by P4-m8 (user-driven demand): the
  bridge exists, but inverted — slopp SERVES the git protocol; commit
  points stay slopp-internal-first.*

- **P4-m8 — Git compatibility layer: serve the protocol, project the
  milestones (user-requested; explicit revision of P4-m7's rejection).**
  > **REVISED (self-host, user-driven): the projection is now IN-MEMORY and
  > READ-ONLY.** `slopp.git/open-repo!` builds a JGit `InMemoryRepository`
  > generated from the journal on demand — there is NO on-disk `.slopp/git`
  > repo (the db is the sole source of truth; the git repo is a pure cache).
  > **Push-import was DROPPED** — the remote is fetch/clone-only (edits come in
  > through slopp's write tools, not `git push`), so the "imported commits live
  > only in the bare repo / `.slopp/git` is durable state" reasoning below no
  > longer applies (nothing durable to lose). `project-journal!` now inserts a
  > commit whenever its object isn't already in the live repo (insert-if-absent),
  > and `slopp.git` no longer depends on `slopp.api`. The determinism, `git_map`
  > pinning, and `:tree` snapshot mechanics are unchanged.

  The user asked for a git-compatible surface: a standalone server any git
  client can talk to (branches + commits + history), push/pull over the
  regular git protocol, stable git-style ids as a COMPATIBILITY layer
  (native `d<n>` stays authoritative). Shape decisions:
  - **Grain: commit points = git commits.** Turns/episodes/checkpoints stay
    invisible to git ("just the commits come out").
  - **Ids are hashes, not mintable:** git clients re-hash every object — the
    id IS the content hash. Stability therefore comes from DETERMINISM:
    each native commit is a pure function of its marker delta. `git_map`
    (main store.db, keyed delta_id+fingerprint) additionally pins delta→sha
    at first projection; imported commits keep their pushed shas verbatim.
  - **Eager capture, lazy projection:** `commit_point` snapshots the
    rendered `:tree` (byte-exact, trivia intact, sorted-map, schemaless
    payload — no migration) into the `:commit` delta; JGit projection
    happens only in the git server, on demand (`slopp.git/ensure-projected!`
    before every refs advertisement). Keeps JGit out of every agent
    server's write path and makes concurrent projectors converge on
    identical shas with no coordination. Cost accepted: tens of KB of
    journal per milestone (rare top grain; delta-encoding is a recorded
    follow-on). Markers WITHOUT `:tree` (pre-m8 history, retroactive
    `:target`) backfill lossily (inter-form trivia isn't in deltas) — pinned
    at first projection, never recomputed.
  - **Journal order IS the chain:** a retroactive `:target` marker lands as
    the NEWEST git commit carrying the OLDER tree. Git mirrors the journal;
    it does not re-sort chronology.
  - **Zed lesson applied** (their libgit2→CLI arc): never partially
    reimplement git semantics — JGit owns all object/pack/wire format; and
    being the SERVER sidesteps auth entirely (the git client owns
    credentials/SSH/config). Server-only v1; slopp-as-client is a follow-on.
  - **Red pushes land honestly** (user-confirmed): a push that compiles but
    turns tests red is accepted and recorded `:status :red`; only compile
    failures reject. Matches the write-model (edits record, milestones
    gate).
  - Ordering invariant (crash-safe): journal marker → git objects
    (content-addressed, idempotent) → git_map row (INSERT OR IGNORE +
    read-back) → ref CAS. Every step derivable from the previous;
    `ensure-projected!` repairs interruptions.
  - **Import semantics (M3):** a push's NET old→new span lands as new-file
    ingests (dependency order) + ONE edit group; each incoming commit
    becomes a `:commit` marker (`:git-sha`, `:git-author`, `:target` = the
    group head) pinned to its ORIGINAL sha — intermediate content states
    live on the git side only, per the user's "single delta, preserve the
    commits" ask. Honest caveat: a push with new files is N ingest deltas +
    one group, not literally one delta. Converged re-pushes skip
    per-form; a crash between group and markers heals on re-push (forms
    converge → markers land), between markers and rows heals at the next
    projection (`:git-sha` repair). Git is a guest writer — the ambiguous
    cases REJECT with the reason on the pusher's terminal: non-`src/**.clj`
    (or `test/**.clj`) paths, file deletions, ns-declaration changes (requires are
    structural), anonymous top-level forms, duplicate names, per-form
    staleness (slopp moved since the push's base → "fetch first"),
    non-fast-forward/creates/deletes (JGit-level). Form deletions WITHIN a
    file are legitimate `:delete` steps.
  - **Surfacing:** `query-commits` rows carry `:sha` once minted
    (`db/commit-shas` reads git_map read-only; ambiguous post-fork id
    collisions are omitted, never guessed; imported markers surface their
    `:git-sha` from birth).
  - **Wip refs (M6, user-probed):** "uncommitted changes" cannot cross the
    git wire — the protocol moves only refs + committed objects; dirty
    state is a working-directory feature. The protocol-legal idiom
    (cf. refs/pull/*, Gerrit refs/changes/*): `refs/heads/wip/<branch>`
    holds a throwaway commit of the LIVE store state (parent = last
    milestone, tree = the element rows via `db/rendered-sources`,
    byte-exact) whenever it differs from the milestone tree; deleted when
    clean. Tools `git diff origin/main..origin/wip/main`. Deterministic →
    concurrent projectors converge; never in git_map, never a milestone
    parent, pushes to wip/* reject (before the lazy session boots). Known
    costs: the ref moves non-fast-forward by design (fetch shows "forced
    update"), and orphaned wip objects accumulate in the bare repo until a
    gc (maintenance follow-on). A store with zero milestones gets no wip
    ref (no baseline). Hidden-namespace variant (refs/slopp/wip/*) is a
    one-line change if branch-listing noise ever bothers.
  - **Embedded listener (M7, user-requested):** the agent's OWN MCP server
    (`slopp.mcp/-main`, durable dir) opens the git listener in-process — no
    external daemon to run — so "push/pull between local git and slopp" is
    `git remote add slopp <url>` then normal git. Design decisions: (a) an
    in-process PORT listener, not a stdio-spawned `git-upload-pack`
    subprocess — git's native transport is a stdio pipe, but the MCP
    server's stdio is owned by JSON-RPC framing, and a spawned process
    would pay full JVM+deps startup per git command (viable later as a
    native-image binary; wrong default now). (b) The port is DERIVED from
    the store dir (`git/derived-port`, private range, stable across
    restarts so a saved remote survives), with an ephemeral fallback when
    two servers share a dir (first wins the stable port); `query_git`
    reports whichever this server bound. (c) The listener gets its OWN lazy
    api session (second image, first-push only) — NOT the agent's live
    session — so a push's branch-switch/edits never perturb the agent's
    checkout; the agent absorbs the results through its normal
    `sync-with-journal!`. Git remains optional: a bind failure logs to
    stderr and MCP still serves. Server-only still holds — slopp never acts
    as a git CLIENT; the user's git owns auth.
  - **v1 limits (recorded, not accidental):** localhost-only, no auth; no
    branch creation/deletion/tags over push (git clients can't push to a
    store with zero milestones — the first milestone comes from slopp);
    form order and inter-form trivia normalize back to slopp's layout on
    round-trip; **`branch_merge` does not transfer milestones** —
    `merge-logs` routes `:commit` through the unknown-op skip-with-note
    (store.clj), so a merged branch's history surfaces in git only at the
    next main milestone.
  - **Follow-ons (demand-driven):** slopp-as-client (fetch/push to GitHub
    from slopp), auth, push-creates-branch, per-commit import groups
    (better query-changes spans at N× verification cost), file
    deletions/renames on import, ns-decl imports, protocol v2, `:tree`
    delta-encoding if journal growth ever matters, projecting `:merge`
    deltas as git merge commits.

- **SG — clj-surgeon-inspired structural ops (user-directed borrow).**
  Compared against realgenekim/clj-surgeon (stateless babashka file
  surgery): its outline/mv we had richer; its cross-repo `:ls-tree` and CLJC
  family are out of scope (multi-repo files; no cljs image). Borrowed the
  four missing OPS, each stronger here (gated+verified+recorded vs "run
  your tests after"): `query_deps` (transitive callee tree — the planning
  input), `fix_declares` (move defns above callers when safe, delete
  satisfied declares, skip mutual recursion), `ns_rename` (decl + requires +
  FQ refs across the store, rekey, elements purged for the old ns — append!
  now deletes rows for nses absent from the store), `edit_extract_ns`
  (namespace split: new ns with copied requires, remaining callers rewritten
  to alias-qualified calls, require added, moved forms removed — one atomic
  verified group; guards: moved set may not call what stays, no external
  referencers (v1)). Caller rewriting is symbol-mapping (zipper), not
  position-based: local shadowing of moved fn NAMES is the known v1 edge.
  remove-form accepts a string form-ID (anonymous forms like declares).

- **P4-deps — External dependency support: two trust tiers + a greppable
  `unsafe` boundary (user-requested; unblocks self-hosting slopp).** The
  owned image was BARE (Clojure + nREPL), so store code requiring
  rewrite-clj/JGit/etc. couldn't compile — the blocker for slopp hosting its
  own source. Design frame (prior art: Rust `unsafe`, Koka/gradual
  "unknown effect = top", Unison per-hash memoization, capability injection,
  GraalVM reachability metadata):
  - **Tier 0 = authored store code** (full guarantees); **Tier 1 = external
    deps** (declared in a per-store manifest, API-surface analyzed, bodies
    OPAQUE, effects worst-case unless narrowed).
  - **`^:unsafe`** = a per-form, greppable, human-discharged opt-out of the
    dialect ban (M2) — also the fix for the ~12 of slopp's OWN forms using
    `binding`/`alter-var-root`/`read-string`.
  - **Effect stance (user):** a call into an external dep is **effectful by
    default** (M3), narrowable by a per-dep `:pure` set. Warnings, never
    rejections.
  - **Dep apply (user):** hot `add-libs` (Clojure 1.12, no restart), restart
    fallback; removes/downgrades restart (a jar can't unload).
  - **M1 shipped:** the manifest — `:deps-add`/`:deps-remove` tracked deltas
    (state-carrying; ride history/branches/merge/foreign-sync) materialized to
    a `meta` `'deps'` row; threaded into all image launches (`-Sdeps` +
    `image-with-deps!` reconciling the bare spare via add-libs); a complete
    generated `deps.edn` (empty manifest byte-identical to before, so the
    `ours?` guard holds; `*print-namespace-maps*` bound OFF for determinism);
    tools `deps_add`/`deps_remove`/`deps_list`. Verified live: add-libs
    hot-loads a dep into the owned nREPL image with no restart.
  - **M2 shipped:** the `^:unsafe` per-form hatch (see `dialect.md`) —
    `store/form-symbol` unwraps `:meta` (load-bearing — else marked forms are
    anonymous); `dialect-check` early-returns on `unsafe?`; `query_symbol`
    surfaces `:unsafe?`; relaxes ONLY the dialect ban, not effect labeling;
    survives render + checkpoint normalize. **Self-host proven** (M1+M2): a
    store with rewrite-clj added ingests real `slopp.store` + `slopp.render`
    source and runs `render/ns-path` in the self-hosted image; an `^:unsafe`
    def holding the banned symbols (slopp.edit `banned-syms` shape) ingests
    cleanly.
    - **M2 follow-up (import-gate fix, 2026-07, self-host dogfooding):** M2 made
      `dialect-check` early-return on `^:unsafe`, but the IMPORT path
      (`ingest!`) never called it — only the edit path did. So a host form could
      be imported UNMARKED and then be **frozen** (the edit path rejects its own
      body's denylisted symbol on any later edit), and import silently swallowed
      `!`-warnings. Fixed: `edit/dialect-scan` runs `dialect-check` over every
      ingested form BEFORE the image load; a host form must enter already
      `^:unsafe` or the whole ingest is rejected (nothing commits, image
      untouched). `ingest!` now also returns `:warnings`. Both paths share one
      gate; the store is internally consistent (any in-store host form is
      `^:unsafe`, hence editable). Found by dogfooding `slopp.rt` import; log in
      `ideas/self-host-log.md`.
  - **M4 shipped:** `slopp.deps` — a dep's own jars (classpath diff) →
    clj-kondo API surface (namespaces + var arities/docs/macro flags),
    memoized per `coord@version` (process memo + durable `dep_surface`
    table). `deps-add!` returns `:namespaces`+`:vars`.
  - **M3 shipped:** the effect boundary (see `dialect.md`) — external dep
    calls effectful by default; store gains `:dep-ns` (from M4 surfaces) +
    `:dep-pure` (materialized in meta, carried in `:deps-add`/`:deps-pure`
    deltas, branch/merge-aware); `deps_pure` narrows. Warnings only.
    - **M3 follow-up (self-host finding): `:pure` narrows at var, namespace,
      OR lib granularity.** Per-var `:pure` flooded slopp's own rewrite-clj /
      clj-kondo-heavy code with warnings (a wholesale-pure library needs dozens
      of assertions). Now `deps_pure {target}` accepts a var, a whole
      namespace, or a manifest lib (expanded to its provided namespaces via
      `:dep-ns`); the `effectful-vars` anchor treats a call as pure when
      `:dep-pure` contains the var OR its namespace. No store/delta/merge change
      — `:dep-pure` already carries arbitrary symbols; the MCP arg went
      `var` → `target`. (Surfaced in `ideas/self-host-log.md` Attempt 2.)
    - **D6 follow-up (self-host finding): `^:reads` per-form `!`-name override.**
      Loading slopp's own `db`/`index` flagged ~11 read-wrappers (`load-store`,
      `data-version`, `analyze`, …) — they read through an effectful-by-default
      external dep (`jdbc/execute-one!` SELECT, `kondo/run!`), so M3 makes the
      caller "effectful" and D6 wants a `!`. But Clojure convention is that reads
      take no bang (`slurp`/`deref`/`d/q`; even Clojure core doesn't bang impure
      reads), so the authors are right and the LINTER is stricter than the norm.
      `deps_pure` can't fix it (next.jdbc isn't wholesale pure; its read/write
      share one bang-named var). Resolution: tag the caller `^:reads` —
      `edit/ns-warnings` drops its warning (`edit/reads?`, `:reads?` on
      `query_symbol`). Chose a per-form greppable override (like `^:unsafe`) over
      making D6 statically distinguish reads from writes — it can't, and this
      keeps honest labeling with an explicit human assertion. Orthogonal to
      `^:unsafe` (dialect only); a form may carry both. (Community norms
      confirmed this direction; `ideas/self-host-log.md` Attempt 3.)
    - **D6 follow-up 2 (full self-host, warning-clean pass):** `effect-violations`
      now flags only ONE direction — computed-effectful-but-not-`!`-named — and
      exempts `-main` (effectful entry point, never banged, like `deftest`) and
      **trusts an existing `!`** (never demands its removal: the analyzer can't
      see interop/opaque effects, and a `!` is the human's effect-assertion). This
      cleared the `-main`s (6) and the interop-banged writes (`stop!`,
      `upload-pack!`, … — 10). With `deps_pure rewrite-clj/clj-kondo` (store
      setup — pure CST/analysis libs) + `^:reads` on the 27 genuine read/query/
      observe wrappers (db SELECTs, `render-ns`/`analyze` memoized reads, git
      reads, the oracle `observe`/`test-run`), a full self-host load went from
      **104 → 6** warnings, the 6 being genuinely-effectful shell dispatchers
      (`mcp/handle`, `git/import-hook`, `http/handler`, …) — true positives, left
      as-is. No self-host special-casing: all three are general slopp behavior.
  - **M5 shipped:** the `^:integration` test tier (see `verification.md`) —
    the fast per-write path skips `^:integration` tests via a
    `skip-integration?` filter in `rt/traced-run`; `test_run`/`checkpoint`/
    `commit_point` include them (`:include-integration?`). A red integration
    test never blocks a fast edit — the point, for DB-backed deps behind a
    capability.
  - **M6 shipped:** the native-compat gate — `deps/native-verdict` scans a
    dep's jars for `META-INF/native-image/**` (GraalVM reachability metadata)
    → `:declared`/`:none` (cached in `dep_surface.native`); `build! :main`
    warns on metadata-less deps (`:native {:warnings :metadata-missing}`) and
    refuses a `native-incompatible-deps` denylist (empty for now) without
    `:force`. Best-effort, declared-or-traced representation (GraalVM's own
    pattern); a missing manifest is a WARN, not a hard incompatibility.
  - Follow-on (M7): `.context/dependencies.md` holds the full model + the
    capability-injection convention.
  - **Build/projection layout follow-up (self-host finding):** materialized
    projects (`build!` AND the git projection) route **test namespaces to
    `test/`, production to `src/`** — a normal Clojure layout, instead of
    dumping deftests into `src/` on the main classpath. A namespace is a test
    namespace by the **`-test` name suffix** (`render/test-ns?` — matches
    cognitect test-runner's default and slopp's own layout; content-based rules
    misfile a test ns's helper defns or strand a production ns's inline test).
    `render/source-path` picks the root; `build/deps-edn` gains a
    `:test {:extra-paths ["test"]}` alias when the project has tests (so `test/`
    is runnable, off the default classpath — no-test output stays byte-identical
    so the `ours?` guard holds); git `path->ns` accepts `test/**.clj` on push.

## H — host

- **H1 — slopp itself is Clojure/JVM** (same runtime as image + tooling; no
  serialization wall to the oracle; in-process clj-kondo/rewrite-clj). CRDT
  will be built in Clojure; **Rust FFI is a last-resort escape hatch only,
  never planned**. Distribution concerns → GraalVM/babashka later if needed.

## F — user-test findings (status)

F1 failure-details in results ✅ · F2 atomic edit groups ✅ (calculator bench
−49% wall) · F3 `{:error}` on unparseable source ✅ · F4 `create-ns!`/
`ns_create` ✅ · F5 `add-require!`/`ns_add_require` ✅ (structural, dup-checked)
· F6 VFS-mapped stack traces ✅ (nREPL load-file + row padding; frames cite the
exact lines `query-source` shows) · F7 ✅ **decided (user): `!` = mutation only**, per Clojure convention —
stdout/console IO is NOT a `!` trigger; if IO tracking ever matters it becomes
a separate `:effects` fact, never a naming rule · F8 ✅ (ingest tidy-return; `:untested`
flag on edits no test exercises; `build!` emits `src/` + minimal `deps.edn`).
Details: `projects/calculator/REPORT.md` (untracked) and `.context/dogfooding.md`.

## T — tasker user-test findings (round 2, through the MCP wire)

T1 ✅ deftests exempt from the `!` rule · T2 ✅ orientation queries
(`query_namespaces`, `query_outline`) · T3 ✅ edits report only NEW `!`
violations + `:existing-warnings` count · T4 ✅ ingest/ns-create load the image
FIRST and commit only on success — a failed require/compile returns `{:error}`
with no store/image drift · T5 ✅ `query_eval` is observe-only by construction
(`edit/observe-gate` rejects def/in-ns/ns-unmap/alter-var-root/...; calling
effectful fns remains allowed — that's observation) · (obs.) hot-editing a
`(def x (atom ...))` form resets its in-image state — tests re-seed so verify
is unaffected; D5's defonce-preservation opt covers it if it ever matters.
Details: `projects/tasker/REPORT.md` (untracked).

## S/E — symmetric-eval findings (fresh agents driving slopp per model)

S1 ✅ **every write must compile**: all hot-loads checked against the candidate
store before commit; forward refs rejected at write time ((declare) is the
mutual-recursion escape); partial group loads restore a fresh image. This
transformed weak-model runs (haiku: +114% vs Go → beat Go outright on
inventory). S2 ✅ `edit_move` (stylistic/structural reorder; `:move` delta) ·
✅ `ns_remove_require` + unknown-tool errors list available tools (agents
invented both names) · E1 ✅ edit_rename arg aliases + clear missing-arg
errors (every sonnet/opus run guessed name/to first) · E2 ✅ SKILL.md teaches
the two-write red-first TDD shape (fn+test in one group → honest red →
replace). Full data: `benchmarks/results.md` symmetric-eval sections.

## R — eval round 2 (modify-and-extend, seeded codebase)

**Honest result: files won at ~60-line scale for all models** (+32..98% tok,
2.3–3.2× wall). Cause ranking: batching (files cover clustered changes in 2–4
whole-file writes; slopp paid ~10–20 verified round trips), per-write
verification wall (kondo re-runs now memo-cached ✅), schema guessing (arg
aliases + validation messages ✅), redundant test_runs (SKILL guidance ✅).
Correctness/safety all held: rename flawless for every model, checkpoint lint
caught a real ordering mistake, zero wrong-behavior incidents. **Fork partially resolved — W1 (user decision):** whole-namespace batch
writes are allowed for BRAND-NEW namespaces only (never overwrite): `ingest`
is that path, now with the standard verified-write tail (side benefit: it
seeds the trace map, so narrowing works from the first edit).
**W1 follow-up (tool consolidation, 2026-07):** the separate `ingest` MCP tool
was folded into `ns_create` as an optional `:source` mode — `ns_create` was
already `ingest!` of an empty ns, so two creation doors were one primitive with
a "which do I use?" fork. Now ONE door, two mutually-exclusive modes:
`:requires` scaffolds an empty ns to grow with red-first TDD (the default for
new behavior — ingesting finished code skips red→green); `:source` lands a
whole namespace at once (ported/reference/data). The `ingest!` engine fn stays
internal (git-import, seeds); "ingest" is gone from the agent-facing surface. No
alias kept (slopp has no installed base; an alias would re-introduce the fork).
Deferred
verification / whole-ns overwrite remain off the table. The scale side of the
fork (10+-namespace eval, too big to read whole) is the next experiment. Data: benchmarks/results.md; report: projects/eval2/REPORT.md.

## X/N — eval round 3 (scale) findings

X2 ✅ rename hot-loads the renamed DEF first (hash-order destroyed cross-ns
renames) · X3 ✅ image loads follow `store/ns-dependency-order` (topological;
map-key order went hash past 8 nses and silently half-loaded images from
`open!`) and failures throw loudly · X4 ✅ `build!` guarded (absolute paths
only, never a dir enclosing the running process, never clobber an existing
deps.edn — an eval agent built into the host repo) · N1 ✅ `!`-named callees
count as effectful anchors (cross-ns effect propagation).
**Round-3b verdict (the crossover, measured):** at 12-ns scale, slopp beat
files on aggregate tokens (−9%) and tool calls (104 vs 155); sonnet −42%
tokens vs its files baseline; files' costs grew +54% avg with scale while
slopp's stayed flat-to-down. Full data: benchmarks/results.md,
projects/eval3/RUNS.md.

## B — benchmark/baseline findings

B1 ✅ **terse green responses** (from the Go-baseline comparison): MCP write
results compress to `{:ok true :delta id :tests {:ran n :pass n} :affected n}`
when green-and-quiet; full detail on :error / red / NEW warnings / :untested /
explicit `:verbose true`. Measured: output tokens −32–38% across all three
benchmark apps (calculator 906→590, inventory 502→311, wordstats 542→370).

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

## P — probe-session findings (the dev agent as user, 2026-07)

Observed while building G8–G10 through slopp's own tools (MCP + stdin
probes). Same instrument as the eval rounds — self-reports from real usage.

P1 ✅ **The E2 multi-form guard is pair-blind.** Matching one `case`
branch, one `let` binding, or one map entry trips the "match spans multiple
forms" refusal — pairs ARE two forms, but they're one logical unit. Fired
three separate times this round; workaround each time was matching only the
value form (loses the key/binding as an anchor). Fixed: a TWO-form match
landing on a pair boundary of a paired container (map literal, binding
vector, case/cond clauses) addresses the pair as a unit — the whole pair
span is replaced (splice still applies, so one entry can become two);
misaligned or non-pair multi-form matches keep the hard error, and extract
still refuses pairs (a pair is not an expression).

P2 ✅ **Change-signature is a mined workaround now performed by the
maintainer.** Changing `commit-paths` from 3 to 4 args form-by-form was
(correctly) refused by the lint gate — callers momentarily had invalid
arity; the resolution (ONE atomic edit_group: defn + all callers, plus
`ns_add_require` first for the new alias) had to be discovered, not
suggested. Built, both parts: (a) invalid-arity refusals now append the
resolution hint (defn + callers in ONE edit_group, or change_signature);
(b) `change_signature` op — `source` replaces the defn, every CALL site's
arg list is rebuilt from a `calls` template ($1..$9 = the site's existing
arg sources; the callee stays as written, so aliases survive), executed
through edit-group! so every gate applies in one pass. Higher-order
references aren't rewritten (returned under :manual); nested self-call
sites and template/arity misses are hard errors pointing at edit_group.
The demand rule worked as designed: instrument fired → deferred op built.

P3 ✅ **No one-shot CLI for tool calls.** When the session's MCP server
died, the fallback was hand-rolled JSON-RPC over `--snapshot` stdin with
EDN payloads staged as files to survive shell quoting. Built:
`slopp.boot --call <tool> [args]` (args = JSON, EDN, or `@file`) — sugar
for `--main slopp.mcp/call-main!`; `mcp/call!` opens a durable
turn-enforced session for ONE dispatch. P1/P2 were built through it.

Ideas parked (not demanded yet): per-push CI via pass-through grafting of
main's workflows into the projected slopp tree at push time (source of
truth stays main — revisits the G10 trade-off, user call); more
`render-config` formats (`:properties`, `:edn`) when a consumer exists;
`import!` also wiring `.mcp.json` for zero-config onboarding.

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

## Q — self-dogfood findings (rocks 1-2 build turn, 2026-07-13)

Friction measured on the maintainer while building through the live
plugin; staged in cost order.

Q1 (✅ 2026-07-13) **:untested defeats the terse result shape.** Any write to an
untested form returns FULL verbose — including the delta's complete
:sources, echoing multi-KB forms the agent just sent (the tools registry
and call-tool came back whole six times in one turn). Fix: :untested is a
flag ON the terse shape; delta :sources never ride write results;
:untested doesn't fire for deftests or pure-data defs.

Q2 (✅ 2026-07-13) **Isolated-suite debugging is a five-minute blind loop.**
test_run {:isolated true} returns counts only — finding WHICH test failed
means rebuilding jar-src and running clojure -M:test by hand. Fix:
isolated runs accept :ns/:only selectors and return failing test names +
messages, like in-image runs.

Q3 (✅ 2026-07-13) **The trace map dies with the session** — every cross-process
write reports {:ran 0, :affected :all}. Persist it in the store: fixes
CLI/probe verification and is the prerequisite for lifetime warm
narrowing (Rock 6's terrain).

Q4 (✅ 2026-07-13, first pass) **slopp.mcp's monoliths fight the form-is-the-unit thesis.**
The tools registry and call-tool dispatch are the hottest-edited spots
and the worst-shaped: decompose to per-tool defs + a handler map.

Q5 (✅ 2026-07-13) **edit_subform fragment errors teach nothing.** "Unexpected
EOF" on a match that ends mid-expression cost three round trips this
turn. Say where the fragment breaks and suggest the enclosing form/pair.

Q6 (✅ 2026-07-13) **Eval instrumentation gaps:** the transcript miner must be a
checked-in script with run-window scoping (an ad-hoc version silently
summed rounds sharing a path); a `slopp doctor` CLI self-check (jar
cache, hooks, turn automation, serving) would have collapsed two
hook-debugging detours.

Q7 (✅ 2026-07-13) **Image-spawning tests are a silent trap for fresh agents.** A
deftest that opens sessions/spawns JVMs (api/open!, repl/start!,
http/start-server!) without ^:isolated would be run IN-IMAGE by per-write
verification — recursion/hang the author can't diagnose. The maintainer
avoids it by knowing the rule; nothing enforces it. Gate it at write
time: detect the pattern, refuse with the fix named ("tag it ^:isolated —
it runs in the external suite").

Q8 (✅ 2026-07-13) **A no-trace verification looks like success.** {:ran 0
:status :green} reads as "verified" when it means "nothing ran" — an
agent in a fresh/CLI session could ship unverified work believing it
green. Until the trace map persists (Q3), such results must say
:coverage :none plus the one-line fix (run test_run once); green must
never be inferable from a zero-test run.

Q9 (✅ 2026-07-13, shared missing-form-error + fragment refusal; audit continues opportunistically) **Every {:error} should meet the cold-load bar.** The
cold-load refusal names its three resolutions inline ("define earlier,
edit_move, or (declare ...)") and cost zero follow-up; the edit_subform
fragment errors taught nothing and cost three round trips. Audit every
error string in api/mcp/sync/edit: each names the next ACTION, in tool
vocabulary. Errors are the only teaching that arrives exactly when the
agent needs it (G12 corollary: richer results beat more instructions).

Q-series resolution notes (same day): Q1/Q8 in `summarize` (strip
:source/:sources everywhere; :untested terse; :coverage :none) + deftest
never :untested; Q2 `isolated-test-run!` :ns/:only + :failing via
`parse-test-failures`; Q3 `persist-trace!`/`load-trace` on store meta;
Q4 tools registry → 6 per-group defs + concat, call-tool tail → 3
handler maps (hot clauses stay in the case); Q5 fragment refusal in
`find-unique-subform`; Q7 `edit/isolation-refusal` on every replace/add
path; Q6 `benchmarks/mine_transcripts.py` + `slopp --doctor`. Suite
263/1351 green.

Q10 (✅ 2026-07-13) **Milestone publish gap** (eval8 P5): five plugin sessions
produced four milestones that never reached the repo's slopp branch —
nothing at commit_point prompts a git_push or syncs the LOCAL slopp
branch, so a files-path teammate gets the seed. The eval agent caught it
by luck of a thorough P5. Fix direction: commit_point on a git-cloned
store offers/performs the local slopp-branch sync (G12: just do the
series), or at minimum the result names the unpublished delta.

Q11 (✅ 2026-07-13) **Bulk rename UX** (eval8 step 3): the one slopp loss (1.49×
controllable vs files) — a global rename is one sed sweep for files but
edit_rename-per-form for us. A pattern/set-accepting rename (or
edit_plan step) closes it.

Q10/Q11 resolution (same day): commit_point at the MCP layer publishes
the projection when git-remote meta is set (green milestones only;
publish trouble rides as :published {:error} without failing the
milestone); rename! results carry :mentions (word-boundary hits of the
old name that the structural rename could not rewrite — docstrings,
strings, comments) + a hint. Rock 3 scoping: edit_group grew :subform
(structural or :text) and :require steps — rename + prose fixes +
threshold change is now ONE call (eval8 step 3's measured overhead).
Full edit_plan (interleaved reads, :when-green barriers) stays deferred
until demand observation shows sequences groups can't express. Suite
266/1359 green.

Rock 4 (✅ 2026-07-13): query_flow + query_impact shipped. Flow is a
boundary-guarded textual keyword scan (kondo keyword analysis deferred
until false hits show up in demand); impact reuses kondo var-usages —
:arity marks a call site, its absence marks a value/higher-order ref
that no template rewrite reaches — plus trace-map coverage. Suite
267/1363 green.

Rock 5 (✅ 2026-07-13): draft_test shipped — deftest drafts from
OBSERVED calls (rt/observe via the query_observe machinery; each capture
becomes an assertion) or a signature skeleton with named TODO holes.
Suggestions only (red-first dogma intact). Known v1 limit: observe's
200-char arg truncation makes big-arg drafts need hand-editing — the
:note says to read each assertion. Suite 268/1368 green.

Stale-dump hazard (lesson, 2026-07-13): the Q4 bulk split sliced tool
descriptors from a dump captured BEFORE a same-session edit and silently
reverted it (test_run's Q2 description). Bulk rewrites from a dump must
re-dump immediately before slicing, or diff the dump against the store
first. Caught by re-reading the descriptor; re-applied.

Q12 (✅ 2026-07-13) **The handoff trust spiral** (eval8 r2, step 5): given a
high-stakes "summarize everything for a teammate" ask, the agent got the
full answer from the history views in 5 calls, then burned ~15 more
re-verifying through side channels (raw sqlite over store.db, a git
worktree re-running the suite, duplicate test_run) — against the skill's
explicit instruction. Q10's git milestones gave it a THIRD source to
cross-check. Direction: results that PROVE alignment instead of asserting
it — query_commits/query_git rows carrying "slopp branch @<sha> == this
milestone's projection" so one call answers the cross-check the agent
will otherwise perform by hand. G12 corollary: richer results close trust
gaps; instructions alone don't.

Q12 resolution (same day): sync/alignment resolves the configured local
remote's slopp-branch head and compares it to the latest milestone's
minted sha (m8) — query_commits returns {:commits rows :alignment
{:aligned bool :note}} whenever a git remote is configured; the note
says explicitly that no worktree/sqlite cross-check is needed (or that
git_push publishes). http remotes stay rows-only (no network in a
query). Suite 269/1371 green.

Ratio push (2026-07-13, from the eval8 n=3 decomposition — orientation
18%, reads 19%, history views 18%, Bash escapes 14%, actual code 15%):
session_brief = one-call orientation (names-only project, counts when
large; milestones; alignment; the loop) targeting ~600 tok/session;
report {since contains} = the handoff composite (milestones + changes
with recorded asks + last verification + alignment) replacing the
history fan-out; OPTIMISTIC EDITS = missed/ambiguous subform matches
return :source-now (the form's current text) so agents compose writes
from the brief and correct from the error instead of pre-reading.
Skill loop rewritten around brief -> optimistic writes -> one report.
Deliberately skipped: tool-description diet (2.1k tok total exposure,
~100 tok/session — teaching value beats the saving); rename-in-group
(multi-ns group surgery, not a measured cost — step 3 already 0.72x).
Suite 272/1383 green. Target: eval8 r4 lifetime ratio ~0.5x.

G13 (standing, from eval8 r4): **composites must snip their prose.** A
composite that carries verbatim descriptions/asks GROWS with history and
gives back every token it saved (session_brief 751->1,773/session;
report ~2.1k/call). Caps: brief = 5 milestones x 110 chars; report asks
= 3 x 140 chars; full text stays one query away. Any future composite
(dossier, plan result, hint) ships with a size budget and a spec
asserting it.

G14 (standing, 2026-07-14): **adoption is not a strategy — defaults
are.** Every measured win came from changing what happens WITHOUT the
agent's cooperation (auto-import, auto-turns, auto-publish, trims, caps,
outline-by-default reads, the hook micro-brief); every disappointment
came from offering a better tool and hoping (optimistic edits: 0 uses;
query_brief: 487 lifetime tokens). New capability ships as the DEFAULT
path or with the default path routing into it — never as an optional
improvement beside the expensive one.

Defaults wave (same day): query_source {ns} returns the OUTLINE unless
full:true (the wire default; api/query-source stays the raw VFS read for
internals); report self-fits under the trim gate via fit-report
(progressive: 1 ask/row, then take-20 with an honest count — the capped
batch double-paid ~2k twice re-fetching 8.1k-char reports); the
UserPromptSubmit hook (now hooks/prompt-hook.py) injects a ~40-token
micro-brief (store present, ns count, last milestone) so the ls/git/
README scouting ritual has nothing left to discover. Suite 274/1392.

Cards wave (2026-07-14): **opacity with a warranty.** form-card = the
interface view of a form (sig, doc line, effect marker, the recorded WHY,
covering-test warranty) at ~10x under source; query_slice = full source
for ONE entry point + cards for its reachable neighborhood (BFS over
query-deps, depth 2 default, capped 8 + :omitted). The warranty is
mechanical, not rhetorical: edits re-run covering tests, violated
assumptions turn red with :implicated — that's why trusting cards is safe
here and hopeful in files. history-stitch hint (2 history calls -> report,
once/session, track-hint! machinery). Plugin ships agents/slopp-reader.md
(read-only comprehension subagent returning CONCLUSIONS — the context-
carry answer for large codebases; unmeasurable at eval8 scale, ship it
for the scaling terrain). Card gap noted: no observed input->output
examples yet (needs persisted observe captures) — the strongest possible
behavior line; revisit when demand fires. Suite 276/1404.

Q13 (✅ 2026-07-14) **The isolated runner REPLs out on inline-test projects**
(eval9): build!'s generated deps.edn only mints the :test alias for
suffix-convention/test-dir tests, so `clojure -M:test` on an inline-test
project starts a REPL and isolated-test-run! reports {:status :error}
with a Clojure banner. Fix: has-tests? should count inline deftests (the
index knows), and the alias's runner should require+run ALL namespaces'
tests; failing that, refuse with the fix named (Q9 bar).

Q14 (✅ 2026-07-14) **Bulk rename at scale is the measured loss** (eval9 step 3:
13.6k tokens / 37 calls / ~7 errors / one restart vs sed's one pass at
5.8k; the run left an empty ns shell). ns_rename + edit_rename + key
subform edits compose per-form; a docs-team-style rename (ns + fns +
keys + prose, 40+ dependent nses) needs ONE intent-level op:
rename_sweep {from "zone" to "region" :kinds [ns fn key prose]} planned
store-wide, executed as one group, one verification. Q11's :mentions and
group subform steps were the small-scale versions; this is the at-scale
completion.

Q13/Q14 resolution (same day): generated :test alias runs `-d test -d src
-r .*` (inline deftests count via a source scan; build! mkdirs test/ so
the runner never REPLs out); rename_sweep {from to} = ns-renames first
(requires rewritten along), then every still-matching form rewritten in
ONE dependency-ordered group (definitions hot-load before callers), one
verification — boundary-guarded segment match, prose and keywords
included by design. Root-cause bonus: rewrite-symbols returned z/root's
:forms wrapper, so EVERY changeset-rewritten form (var-rename callers,
ns-rename decls) silently lost its :name — dependency ordering broke on
the next image rebuild; latent since the changeset machinery landed,
surfaced only when the sweep renamed an alphabetically-early consumer.
Suite 279/1413 green.

Store-integrity pair (2026-07-14, found BY eval9's sweep cells — the
fresh-session-per-step protocol is a persistence fuzzer no single-session
spec replicates):
1. **Resurrection**: try-commit! filtered touched nses to those present
   in the new store, so a renamed-away ns never reached append!'s
   delete — its rows lingered and the NEXT session loaded both old and
   new namespaces. db/persist! had the same skip. Any durable store that
   ever ns_renamed and reopened was affected.
2. **Ghost vars**: a replace that renames (single edit_replace or group
   step) loaded the new var but never ns-unmapped the old one — stale
   vars then failed as noise in later verifications (the sweep's own red
   came from a ghost zone-t, not from the sweep).
Lesson recorded: durable-session specs must include a CLOSE + REOPEN leg
when they mutate namespace identity; single-session green is not
persistence green. Suite 281/1418.

G15 (standing, 2026-07-14): **the knowledge-differential stance.** The
server models what the session has been told (told-tracking: cacheable
views hash-keyed per session; identical re-reads return an :unchanged
stub, so re-fetching is FREE and agents need not hoard context) and what
the task needs (the verbatim ask, kept as :last-intent, is mined
deterministically against form names; the brief arrives with :relevant
interface cards). Composites scale by AGGREGATION, never amputation
(fit-report rolls changes up by namespace; briefs roll up >=5-sibling
namespace FAMILIES) — eval9 proved take-N amputation CAUSES fan-out:
agents hunt for what was dropped. New front door: query_depends {on X}
answers what-depends-on for a namespace / var / :keyword by
classification + delegation (user-requested; ns-level had no tool at
all). Suite 286/1438 green.

Consolidation wave 2 (2026-07-14, user decision): ONE dependency door —
query_depends {on, direction} (dependents: ns -> required-by + refs,
var -> blast radius, :kw -> flow; dependencies: var -> callee tree,
ns -> requires) — and ONE history door — query_history routed by args
({} episodes, {ns name} form life, {ns name at} time-travel, {at}
was-green-at, {contains} ask search). Twelve wire tools retired
(references/deps/flow/impact/outline/symbol/namespaces/form_history/
form_at/status_at/search_history/lineage); every api fn stays; the wire
surface is 58 tools. Evidence: the specialized quartet had ~5 calls
TOTAL across the whole eval campaign — taxonomy-choice was the barrier,
not capability; the outline gate had already shown removal converts
where offering never does. Deferred deliberately: modal write clusters
(files/branches/deps/git) — distinct verbs are safety-relevant and the
traffic is negligible. Suite 286/1441 green.

G16 (standing, 2026-07-14): **smells are data.** Deterministic bad-usage
detection lives in slopp.mcp/smell-registry — one [key fires?-pred msg]
entry per smell over bump-smell-counts; adding a smell is one entry, no
plumbing. Fire policy is anti-spam BY CONSTRUCTION: once per session +
once per 30 minutes per store (db-meta cooldown), suggestions never
refusals, one line naming the better tool. Current catalog: test_run
streaks, scattered singles, history stitching, whole-ns dump streaks
(-> query_slice), rename streaks (-> rename_sweep). Standing practice:
smells observed in dogfooding (mine or eval transcripts) get an entry
the same day. Two bugs fixed en route: hints now ride STRING results
(source dumps dropped them), and some-over-registry replaced a cond
whose first fired smell shadowed all later ones forever.

Bash-smell hook + card examples (2026-07-14): the smell system reaches
the channel the server can't see — a PostToolUse(Bash) hook injects
one-line redirections via hookSpecificOutput.additionalContext (the ONLY
PostToolUse output that reaches the agent — verified empirically; plain
stdout does not). Smells: raw store.db reads, git log/diff/show/blame in
store dirs, grep/cat of .clj files, clojure -e shell evals; 30-min
per-smell per-store cooldown (.slopp/bash-hint-cooldowns.json), silent
on any failure, active only where .slopp/store.db exists. And interface
cards now carry :examples — query_observe captures persist to store meta
(remember-observation!, called at the MCP layer to keep the api fn's
read-only contract clean) and cards surface up to two real
input->output pairs. Suite 289/1452 green.

Push-default regression (2026-07-14, bit the USER): git_push {url} saved
the url unconditionally, so a one-off push to "." silently repointed the
store's default remote away from slopp3 and broke the normal push flow.
Fixed: first-save only — an existing default is never rewritten by a
one-off push; results carry :default-remote so the standing default is
always visible. Also verified and worth knowing: local and remote slopp
branches are the SAME minted lineage (deterministic projection commits
via git_map), so a local push is never a divergent copy; the local
branch now tracks origin/slopp. Suite 290/1457.

D-local-mirror (user decision, 2026-07-14): **every milestone mirrors
into local git automatically** as refs/heads/slopp/<store-branch>
(slopp/main, slopp/my-branch) — local git durably carries the slopp
history with zero ceremony; REMOTE publishing stays explicit (git_push,
which never rewrites the saved default). sync/publish-local! never
touches git-remote meta; alignment (Q12) now proves against the local
mirror, so it works with no remote configured at all. Naming note: a
flat local branch named `slopp` blocks the slopp/* namespace — the dev
repo's old flat branch was deleted (identical to origin/slopp) and the
local listener remote renamed slopp -> slopp-store to clear ref
ambiguity. Remote publication branches (e.g. slopp3's `slopp`) are
unchanged. Suite 290/1459.

Mirror sync verbs (user design, 2026-07-14): git_push/git_pull have ONE
meaning each — push = send local slopp/<branch> mirrors to the remote
(sync/mirror-push!: ff-only, collision with a legacy FLAT `slopp` branch
refused with the migration taught, {:migrate true} performs it — same
minted lineage); pull = fetch remote mirrors into local slopp/* (ff-only)
AND absorb remote store history (existing 3-way pull!). Direction lives
in the verb, not a mode flag (the user's call: familiar git vocabulary
beats a unified modal tool). No-git dirs never fail: milestones stay
store-durable with no :published leg, and the verbs teach `git init`.
Ecosystem: slopp-branch? marker + clone!'s default resolution accept
slopp/main alongside legacy flat `slopp`; slopp3's branch is migrated to
slopp/main (the old flat sync/push! remains api/CLI-level for legacy
flat remotes). Suite 291/1471.

No-compat rule (user directive, 2026-07-14): **there is no slopp but
this slopp** — never write backwards-compatibility code; when a design
changes, migrate everything (code, specs, seeds, remotes) in the same
wave and delete the old path. Applied immediately: flat `slopp` branch
support removed everywhere (marker/clone/pull/push all speak
slopp/<branch>; clone's legacy-main fallback gone; git-branch config
key retired from config!/push!/clone!), mirror-push!'s flat-collision
migrate machinery deleted (flat is extinct: slopp3 + both eval seeds
migrated to slopp/main, READMEs and accept scripts updated), and the
stale-schema :where string-coercion removed (the restart protocol owns
schema staleness). Kept deliberately, NOT compat: agent arg-forgiveness
aliases (:old/:from etc. — they serve live agents guessing, not old
versions) and push! itself (fileless stores publish projections — a
capability, not a legacy path; git_push routes checkouts to mirrors and
fileless stores to projection publishing). Suite 291/1470.

Module system (user design, 2026-07-14): **enforced architectural
boundaries as a module system.** Module = first two ns segments
(`-test` folds into the subject's module); RECURSIVE VISIBILITY — deeper
namespaces are package-private to their parent prefix, `^:export` on a
defn's name hoists that var into the module surface (definition-site,
no var copying); cross-module calls require a DECLARED edge; a declared
edge that CLOSES a cycle is refused (the check is LOCAL — reachability
to→from — because -test folding makes some adopted cycles legitimate,
e.g. slopp.api↔slopp.db via each other's tests; a pre-existing cycle
never blocks an unrelated declaration); public-surface defns without docstrings warn
(per written form, transition-only — never a ns-wide nag). The manifest
ALWAYS exists (user: "we shouldn't have to explicitly init — start
modifying it from empty"): fresh stores are born enforcing with zero
edges; pre-module dbs adopt at open! (manifest derived from the actual
kondo-resolved graph = zero violations by construction); clone! ingests
gate-off then adopts what landed. Tracked CRDT-WAY with SEMANTIC calls
(user directive): the edge is the unit — one `:module-edge` delta per
declare/retract carrying its why; merges fold edges (adds union, never
a conflict; a cycle the union closes is surfaced as a note); writes go
through `module_dep` only (config_file "modules" is refused and
teaches); reads through `query_depends {modules true}`; the fold
projects as a `modules` file into git commits for transparency.
ns-rename/rename_sweep re-key manifest entries when a module's last ns
renames away. Suite 296/1520.

Inferred episodes — REPL-native flow (user design, 2026-07-15): agents
must NOT plan groups or worry about wire limits; they work like a REPL
developer and the SYSTEM infers the unit of work. STANDARD TERMS (use
everywhere): WRITE (delta) = one gated, hot-loaded, verified form-level
change; CHANGESET = internal atomic multi-form op (rename,
change_signature, normalize) — implementation detail, never an agent
decision; EPISODE = one agent's writes between done-points — inferred,
per-agent, the history unit; DONE-POINT = the boundary (`done {label}`,
or the turn-end hook): normalize + declare hygiene + lint + AFFECTED
TESTS over everything the episode touched + findings recorded on the
boundary delta; TURN = the user-ask bracket; MILESTONE = commit_point,
green-gated, spans turns. Decisions: verb renamed checkpoint→done
(journal op migrated one-off, 85 rows, user-approved); tests never
implicitly close an episode (explicit done + turn-end only; spot-check
test_runs stay in-progress, and a redundant pre-done test_run earns a
hint); edit_group + staging REMOVED from the wire (api-level
edit-group!/changesets stay internal); per-write verification stays
automatic. The invalid-arity lint gate is scoped to the WRITTEN form:
own-form errors refuse (with the change_signature hint), stale-caller
errors ride :carried-errors until done re-checks them hard. done always
verifies (not only when normalize rewrote); findings resurface in
session_brief :last-done + the prompt-hook heads-up. Concurrency note:
sub-agent isolation was never the group's job — form-grain CRDT
rebasing is; episodes are per-agent derived.

Self-hosting lesson (2026-07-15, cost one rescue): changing the
SIGNATURE of a write-pipeline function (edit/lint-refusals 3→4 args)
via a single write deadlocks the pipeline — the hot-loaded new fn
breaks the still-committed old callers, and no write can land to fix
them (there is no fallback pipeline; the jar is only a boot kernel).
Rescue: user-approved hand-minted append!-equivalent delta installing a
dual-arity bridge, then normal writes. RULE: pipeline-critical
signature changes go through the internal atomic changeset
(edit-group!/change_signature), never incremental writes — exactly the
carve-out the inferred-episode design keeps changesets for.

Module-graph honesty (2026-07-16): the module manifest folds `-test`
deps into the subject module (so TDD needs no ceremony), but that
pollutes the architecture graph — test fixtures calling `slopp.api`
manufactured a false 7-module cycle in slopp's adopted store. Decision
(user): the graph VIEWS (`query_depends {modules true}` :layers/:cycles)
compute over PRODUCTION edges only (non-`-test` namespaces;
`api/production-manifest`); the DECLARED `:manifest` and the write GATE
are unchanged (fixtures still declare their edges, enforcement intact).
slopp's own graph went from one condensed 7-module cycle to a clean
8-layer DAG with slopp.api alone at layer 4. Note: the false cycle only
arises via ADOPTION (module_dep's own cycle guard prevents declaring it
fresh).

The first deep-module split (2026-07-16): slopp.api's 8 pure history/status
helpers moved to package-private `slopp.api.history` via `edit_extract_ns` —
the first depth-3 namespace in slopp itself, proving the deep-module
machinery on real code. Dogfooding surfaced and fixed, red-first:
(1) hot-load-all!'s heal path boots from the COMMITTED store, so a
candidate-created namespace vanished mid-heal (FileNotFound) — the heal now
replays the candidate's touched nses first, and the pre-heal error rides out
as :first-err; (2) forms moved across a namespace boundary are PUBLICIZED
(refactor/publicize strips defn-/^:private) — module-grain visibility is the
boundary now, not var privacy; (3) the module gate was blind to
fully-qualified un-required calls (kondo emits no var-usage row for them) —
edit/qualified-usage-rows synthesizes those rows, quote-pruned; (4) -test
namespaces share the subject's prefix for deep visibility (fold-test-ns), so
package-private helpers stay unit-testable; (5) JGit transports now carry a
30s socket timeout after a half-dead fetch froze the single-threaded server
for 40+ minutes mid-publish (the milestone was already durable — journal
first); (6) summary-less test shards (JVM death under fork pressure) retry
once serially (:shard-retries). Remaining slopp.api clusters (testrun, deps,
branch, build; verify/session LAST, via changesets) are a follow-on decision.

The session-engine split (2026-07-17): slopp.api's pipeline substrate —
image lifecycle + journal commit + verification, 27 forms — moved into
package-private `slopp.api.session` via move-forms, live, with the server
executing the very pipeline being relocated (safe because a MOVE rewrites
all addresses atomically and the server hot-reloads only after the
consistent commit; contrast the D-series signature-change deadlock).
The two-way refusals DISCOVERED the layering: branch/deps plumbing
couldn't move before the substrate they call. Deep packages now:
history, testrun, session, deps, branch; the public verbs stay on
slopp.api (the surface). Engine specs live with the engine
(slopp.api.session-test); reap-idle-images! stayed a public verb so
branch specs stay honestly placed. move-forms hardening the campaign
forced: de-qualify refs into the target, moved sets carry their own
(declare ...), publicize/export under form-level meta wrappers, per-move
export levels compose across steps (the hook moved alone with
^{:export "slopp.concurrency"}).

slopp.api decomposition complete at the internals grain (2026-07-16):
seven deep packages — history, testrun, session (the engine), deps,
branch, modules, orient — hold the implementation; slopp.api keeps only
the public verbs and query composites (187KB from 231KB). Placement
rules that emerged: a PRODUCTION cross-module caller makes a helper
public API (adopt-modules! stayed for sync/clone!); specs live with the
machinery they exercise (session/modules/orient -test namespaces);
public verbs stay on the surface even when thin. The debt view caught a
real gate-vs-reality gap: a move's export pre-check trusted intent while
export-mark silently skipped a meta-wrapped name (^:dynamic) — fixed;
the gate reads declared plans, the debt view reads reality, keep both.

Tier-blind verification (2026-07-16, user decision): the in-image vs
^:isolated split is an IMPLEMENTATION DETAIL that must not leak into the
agent's contract. `done` now routes impacted ^:isolated tests to the
external tier automatically (require-closure slice keyed to the episode
boundary, capped at 4 nses; a larger slice defers LOUDLY via findings
:isolated-pending — resurfaced by the brief); `commit_point` green-gates
on the FULL isolated suite it runs itself (:force skips to honest red;
stores with no isolated tests skip the tier). The wave-end ritual is now
just commit_point. Explicitly rejected: auto remote push (the user asks
for mirror pushes), per-wave jar rebuilds (the jar is only the boot
kernel — rebuild on kernel/deps change only). Roadmap: warm isolated
runner + incremental build! make the tier cheap enough to remove the cap.

query_store — the store-value oracle (2026-07-16, user-approved surface):
read-only eval of one `(fn [store] ...)` over the immutable store value,
in the server process. Rationale: the image answers questions OF the
code; there was no oracle for questions ABOUT the codebase-as-data, so
one-off analysis kept becoming canned tools (review_scan) or tempting
raw db reads. Boundaries: pure-eval gate (no effects/defs/interop/IO),
fn-of-store shape only, worker + timeout, pr-str-capped output. The
stance "raw REPL eval may observe but never redefines" extends to the
store value: observation of an immutable pointer, never mutation.
Repeatedly-asked query_store questions are evidence for the next canned
tool.

The unused-public gate (2026-07-16, user decision): dead public surface
fails verification instead of riding as an ignorable advisory. done →
ERROR-grade lint + findings for touched namespaces; commit_point →
GLOBAL whole-store sweep (standing dead surface refuses the milestone
regardless of whose episode left it — episode-scoping provably leaked).
The escape is `^:unused-ok` on the name, and it is self-policing: a
stale marker (var now called) fails symmetrically, so the dial never
rots. Known blind spot the dial exists for: string-eval'd and
runtime-resolved entries (rt/observe, rt/traced-run, mcp/call-main!)
are kondo-invisible by nature. Kondo's unused-private-var already
covers privates per-namespace.

Designated reference carriers (2026-07-16, user decision — thesis-level):
references must not hide in strings or naked quoted symbols; they live in
BLESSED CARRIER positions the tooling reads as real edges, or in
DECLARATIONS. The carrier set: `#'var` literals for in-process references
in data (already hard edges — the preferred form); `store/late-ref 'ns/nm`
for load-cycle late binding (the ONLY sanctioned runtime resolution;
replaced the naked requiring-resolve in git/ensure-projected!);
`api/query-call` / wire `query_call {sym args}` for the oracle's common
invoke case (query_eval stays the escape hatch for arbitrary expressions);
`^:entry-point` on the NAME declares outside-world invocation (CLI, wire,
eval-injected — rt/observe, rt/traced-run, mcp/call-main! migrated from
^:unused-ok; no stale symmetry — the outside world is statically
unverifiable). KEYWORDS REJECTED as the replacement: a keyword names a
table slot, not a var — statically worse than a string. unused-report and
review_scan read carriers and declarations; a naked quoted symbol stays
data. Phase 2 (parked with .ideas/): the dialect LINT that detects
var-shaped strings / naked requiring-resolve outside carriers and teaches
the sanctioned form; per-library carrier adapters (kondo-hook style) for
framework config; the unified reference graph builds over these so
references CANNOT hide rather than being tolerated. Scope note: carriers
relieve markers only for SAME-STORE drivers — slopp's own nested-store
test fixtures keep ^:unused-ok (cross-store references are invisible by
construction, and ordinary apps' tests call statically anyway).

Resolver denylist (2026-07-16, completes the carrier decision's teeth):
requiring-resolve, resolve, ns-resolve, find-var, and intern join the D3
denylist. Blocking var-shaped STRINGS is the wrong enforcement point —
docstrings mention vars and tests hold quoted symbols as data, both
legitimately inert. The gate blocks where a mention could BECOME a var;
refusals teach the carriers (store/late-ref, #'var literals) and the
^:unsafe owned-obligation escape. Sanctioned resolver homes: late-ref
itself and slopp.boot/-main (the OS boundary). Consequence: in gated
store code, a string can no longer become a reference — strings are
inert by construction, which is stronger than any string lint.

THE reference graph (2026-07-16, user decision — single source of truth):
slopp.edit.refs is the ONE place "who references what" lives. Producers
normalize in at the source (kondo statics + un-required qualified calls,
carrier positions, marker declarations as edges from :external); consumers
query refs/refs-to and never fuse sources privately — unused-report,
module-usage-rows (debt/drift), and review_scan ported this wave, which
also FIXED review_scan's scoped-scan caller counts (whole-store graph →
the :unused flag now works under :ns scoping too). Records anchor to
form-ids (semantic, stable), never line/column; rewriters re-derive
positions per-form at rewrite time. The graph is DERIVED (content-memoized
via the kondo cache), never stored — refs are an index of source; the
journal owes them no consistency, so merge/replay/branching need no new
machinery. Remaining consumers to port (recorded): move-plan's usage
assembly, rename's mention sweep, query-depends/query-impact blast, the
module gate row builders; plus the :observed producer (trace map — session
grain, needs an optional arg) and keyword/class targets. Convention going
forward: a tool reading kondo var-usages directly for a REFERENCE question
is a bug — add a producer or consume the graph.

Wire uniformity rule (2026-07-16, follow-up to the codec): the wire speaks
NAMES, always — one output dialect (the grouped-qsym form); short handles
are never an alternative output format. Rationale: reading is ~99% of
traffic and qsyms are self-describing (no resolution round-trip); opaque
ids are a hallucination hazard (a mistyped f4448 can silently resolve to a
REAL other reference, while a mistyped qsym fails loudly — names fail
safe); and handles are form-PAIR grain, not edge grain (static + carrier
between the same forms share one handle). Handles remain an ACCEPTED INPUT
convenience for future ref-consuming tools — which must equally accept
qsym pairs, so agents never need to convert — and are EMITTED only
alongside an actionable follow-up operation, never as noise columns.
