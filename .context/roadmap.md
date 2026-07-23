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

## Typed API contracts, gated and shared with the client (user ask, 2026-07-23)

The endgame the D-web-cljs hand-shared schema (`slopp.client.nsschema`) only
gestures at. Two enforceable pieces the user proposed:

1. **A gate requiring a SCHEMA on every web endpoint** — a web-facing `defn`
   (`:web/method`/`:web/path`) must declare the shape of what it READS
   (request/params) and RETURNS (response body), the way `:web/auth` is already
   mandatory. Refuse-grade, dial-able; inert until `http.enabled`. This makes
   the request/response contract a first-class, checkable artifact instead of
   living implicitly in the handler body.

2. **The schema is `.cljc`, SHARED with the client** — the same schema the
   server validates against is compiled into the client bundle, so front-end
   code that calls the endpoint is checked against the SAME contract. A FE call
   that sends the wrong request shape, or reads a response field the endpoint
   doesn't declare, becomes a WRITE-TIME red (the deeper cousin of the existing
   `web-dangling-route-refs` route-integrity index — that ties links to routes;
   this ties data shapes to endpoints). Malli is the vehicle (proven to compile
   to cljs, D-web-cljs (b)); a generated typed client (`fetch` wrapper + a
   validating decoder per endpoint) is the natural output.

Relation to what shipped: the route-ref index already joins literal
`:href`/`:action` to routes; `:web/reads`/`:web/effects` already declare an
endpoint's data dependencies; malli already compiles to cljs. So the pieces
exist — the work is (a) the endpoint-schema gate, (b) deriving/sharing the
`.cljc` schema, (c) the FE-side contract check (a client-usage lint or a
generated typed client). Supersedes the terse "typed client API" deferral in
D-web-cljs. Needs its own design pass and decision record before building.

**STATUS (2026-07-23): decided + parts 1/1b/2 SHIPPED.** Decision record is
`.context/decisions.md` (D-web-contracts): FE-check = GENERATED typed client
(not a lint); gate = refuse-for-new; named `.cljc` schemas are the paved road
with inline allowed and a DRY advisory to guide. Part 1: `web-endpoint-schema`
refuses a `:web/path` endpoint missing `:web/response` (every endpoint) or
`:web/request` (`:post`/`:put`/`:patch` body methods) — milestones d11968/d11981.
Part 2: `generate_client` — a stored, edit-protected, inspectable `:cljs`
namespace of typed fetch wrappers; the `^:generated` marker's triple duty; the
`:cljc`-placement check; the explicit-regeneration `stale-client` advisory; the
`inline-schema-dup` DRY advisory. Only part 3 (the dogfood) remains.

**PART 2 = the generated typed client — ✅ SHIPPED (2026-07-23).**
1. **Delivery: DECIDED — a stored, generated, edit-PROTECTED `.cljs` namespace**
   (`app.client.api`), not a blob. Driven by the user's confirmation that the
   client is PURELY GENERATED / never hand-edited: it must be a stored ns
   because the FE has to `(:require)` and CALL the wrappers as cljs fns AND its
   references (`api/create-order!`) must RESOLVE in the store at write-time to
   pass slopp's reference/cold-load gates — a blob or build-time-only ns can't
   provide that. "Never edited" is ENFORCED: mark the ns `^:generated` and add a
   write gate that REFUSES hand-edits (regeneration is the only writer), same
   machinery as `web-endpoint-schema`.
   **INSPECTABLE, though — a first-class benefit (user, 2026-07-23).** Being a
   stored ns, the generated client is fully visible to tooling (query_source,
   query_depends/blast-radius, usage/reference graphs) — a blob would be opaque.
   The payoff: the chain schema → endpoint → generated wrapper → FE call site is
   connected by REAL references, so "edit this schema → every affected FE call
   site" falls out of the refs graph for free (the deep contract-integrity
   story). So `^:generated` does DOUBLE duty: (a) refuse hand-edits; (b) tune the
   inspection gates — generated code must NOT be flagged untested/undocumented/
   dead-surface (a wrapper no FE calls yet is "available," not dead), and the
   generator stamps each wrapper's provenance (`:why` = "generated from endpoint
   X") so inspection shows the derivation.
2. **Regeneration: DECIDED — an explicit `generate_client` step + a staleness
   ADVISORY (user, 2026-07-23).** Regeneration is a WRITE (to the generated ns),
   so tying it to every endpoint edit couples the generator to the write path
   and risks churn; an explicit build step mirrors the established
   `compile_client`/`build!` pattern. The safety net is a done-advisory —
   "endpoints changed since the client was last generated → run
   `generate_client`" — so staleness is surfaced without the coupling. It
   composes with what exists: `generate_client` WRITES the `.cljs` (well, the
   generated ns), and if `client/auto-compile` is on, that write already
   triggers the async recompile, so the bundle refreshes off an explicit
   generate.
Then the wrapper shape: async `fetch` → malli-validate params OUT / response IN,
`malli.transform` at the JSON boundary. And the three pieces that bundle into
part 2 because they only bite with real schemas + generation: the schema-var
RESOLVER (symbol → schema value — the reference graph already tracks the symbol,
only the value lookup is new; blocked from reusing `slopp.edit/require-aliases`
by the `slopp.edit ↔ slopp.edit.modules` cycle, so inline a small alias reader);
the `:cljc`-PLACEMENT check (a referenced schema var must be in a `:cljc` ns so
it compiles into the client); and the DRY inline-duplication ADVISORY
(structurally-equal inline schemas on 2+ endpoints → "extract to a named `.cljc`
schema"). Part 3 (dogfood on real endpoints) comes after part 2 — the user
explicitly said NOT to jump to the dogfood.
