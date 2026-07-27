# Store & persistence

## In-memory model (`slopp.store` — pure functions over values)

- A namespace = ordered vector of **forms**:
  `{:id :kind :form :name :names :node}`, plus an optional `:comment` — the
  block rendered directly above that form. `:node` is a rewrite-clj CST node.
  That is the whole model.
- **There used to be a second element kind, `{:kind :sep :node}`** — whitespace,
  blank lines and comments, positional and idless, kept so rendering could be
  lossless. Lumping those together forced byte preservation: a comment stored
  positionally is content the delta log never recorded, which is why a
  milestone had to snapshot every namespace's bytes to keep it. Splitting them
  dissolved the requirement. **The space between forms is RENDERING** —
  `render-ns` supplies one blank line, nothing stores it — and **a comment is
  CONTENT owned by a form**, travelling in that form's delta like anything
  else. `store/fold-comments` is the single place the old shape becomes the
  new one; it runs on `ingest` AND on db load, so any store migrates itself
  the first time it is opened. A `#_` discard folds too: it is code, and
  dropping it silently would be the one genuinely bad outcome.
  Rationale and measurements: `ideas/done/whitespace-is-rendering.md`.
- **A form defines a SET of names (`:names`, via `form-symbols`) — #128.**
  `:name` (via `form-symbol` + `def-heads`) is the PRIMARY name, for labels;
  `:names` is what addressing uses. They differ for most def forms, and the old
  one-form-↔-one-name premise was wrong in BOTH directions:

  | form | `:name` | `:names` |
  |---|---|---|
  | `(defn f …)` / `(defmulti area …)` | `f` / `area` | `#{f}` / `#{area}` |
  | `(defmethod area :square …)` | **nil** | **`#{}`** |
  | `(extend-type String P …)` | nil | `#{}` |
  | `(defprotocol P (m …) (n …))` | `P` | `#{P m n}` |
  | `(defrecord R [x])` | `R` | `#{R ->R map->R}` |
  | `(deftype T [x])` | `T` | `#{T ->T}` |

  **`defmethod` is NOT a def-head.** Its second element is the multimethod it
  REGISTERS ONTO, not a name it defines — it is a registration, like
  `extend-type`/`extend-protocol`, which were never in the set. Including it put
  three forms named `area` in one ns, and everything downstream broke silently:
  `form-named` returns the FIRST (so methods were unreachable by every
  name-keyed tool); `refs/cold-load-order` **DROPPED** forms, the defmulti among
  them (`{:order ["f0" "f3"]}` for a 4-form ns); `static-refs` resolved
  `:from-form` last-wins against `:to-form` first-wins in adjacent lines. Note
  the edit layer ALREADY refuses duplicate names (`api/add-form!`, twice), so
  ingest was the only door and it admitted a state the rest of slopp considers
  illegal.
  **No compound name (`area:square`) could have worked**: `:` is legal in a
  symbol, so `(defn area:square …)` is a real fn a user can write — and every
  other ASCII-punctuation spelling is legal too (probed). The name space is flat
  and user-owned; you cannot reserve a corner of it.
- **`form-named` matches any of `:names`, or a form ID.** The id match is what
  makes registrations addressable (they define nothing, so an id is their only
  handle) and it fixes a live round-trip bug: `qform` has always LABELLED
  unnamed forms `ns/f4`, and `form-named` could not fetch one back.
- `form-symbols` is **syntactic, per head — deliberately not kondo**: it runs per
  form on every ingest and replay, where a kondo pass costs ~285ms on a large ns.
  Every writer of `:name` must write `:names` too — there are six, all in
  `slopp.store` (`ingest`, `replace-node`, `append-form`, `apply-changeset`,
  `replay-delta` ×2). **Missing `replay-delta` is the sharp one**: the store
  rebuilds from the journal on `open!`, so `->R` would stop being addressable
  after a reopen.
- **`deftest` is a named form on purpose** (tests are addressable/editable).
- Ids: `"f<n>"` forms, `"d<n>"` deltas, single monotonic counter (`:next-id`).
  The DB has a UNIQUE constraint on delta ids as a collision backstop.
- Deltas: `{:id :parent :op :ns :prompt ...}` — ops today: `:ingest`,
  `:replace`, `:add`, `:delete`, `:rename` (multi-form, `:form-ids`),
  `:verify` (result attached). `:parent` = previous delta id (linear now,
  DAG-ready).
- Multi-form coordinated edits go through `apply-changeset` → ONE delta over
  N forms (used by rename).
- **`:system true` marks a delta the PIPELINE wrote, not the agent** — today
  only the cold-load auto-reorder (`edit/resolve-cold-load` → `reorder-to` →
  `move-form`). It exists because a derived view otherwise cannot tell
  housekeeping from intent: `prompt-by-form` takes the last prompt naming a
  form, so the reorder's constant prompt became the recorded WHY of 142 of
  1,898 forms (7%) — on `form-card`, `query_slice`'s cards and the reviewer
  UI alike. The op is not the discriminator (`edit_move` is the same op with a
  real intent) and neither is the absence of `:agent` (measured: 75 genuine
  hand-written asks carry none). It has to be recorded as a fact.
  The log is append-only, so already-written reorders cannot be re-stamped;
  `prompt-by-form` also recognises `store/auto-reorder-prompt`, ONE constant
  owned here and used by the one writer so the two cannot drift.
  **A writer acting on the agent's behalf must mark what it writes**, or it is
  indistinguishable from the agent in every view derived from the log.
- **Purity is load-bearing:** transactional/atomic behaviors at the api layer
  (e.g. group validation) work by applying store fns to a value and only
  committing the result on success.

## Persistence (`slopp.store.db`, decision C7)

- SQLite at `<dir>/.slopp/store.db`, WAL mode. Tables:
  - `deltas(seq, id UNIQUE, op, ns, payload)` — append-only log; everything
    except id/op/ns lives in the EDN `payload` column (exact-reconstruction
    rule: `(merge {:id :op :ns} (edn/read-string payload)) = original`).
  - `elements(ns, pos, kind, form_id, name, source)` — materialized current
    state; `source` is the CST's canonical serialization (re-parsed on load;
    must reparse to exactly ONE node — asserted).
  - `meta(k,v)` — `next-id`.
- `persist!` = ONE transaction: delta row + full element rows of the touched
  namespace(s) (multi-ns arity for cross-ns ops like rename) + counter.
  Namespaces are small; full-ns row rewrite keeps write-through trivially
  correct.
- `load-store` reconstructs the entire in-memory store (returns nil if empty).
  `api/open! {:dir ...}` loads it AND replays every namespace into a fresh
  image.
- **`db/open!` creates; `db/open! dir {:create? false}` returns nil instead**
  (D-serving-is-not-adoption). The MCP server is launched in whatever dir the
  editor has open, so a caller that merely ASKS whether a dir is slopp-managed
  must not answer yes on its behalf. `external/open!` uses `{:create? false}`
  and a session on an unadopted dir carries `:db` nil — the pre-existing
  ephemeral path. `api.session/ensure-db!`, on the commit path, materializes
  the store at the first real write; it is the ONLY implicit adoption in the
  system. When you add a caller of `db/open!`, decide which one it is: a
  question takes `{:create? false}`, a write takes the default.

## Rendering (`slopp.store.render`)

- `render-ns` = forms joined by ONE BLANK LINE, each preceded by its own
  `:comment` if it has one, one trailing newline. It does not round-trip
  ingestion byte-for-byte and is not supposed to: it NORMALIZES, the way
  `gofmt` does, which is what makes a namespace's bytes a pure function of the
  store rather than of whoever typed them.
- **There are FOUR implementations of that one rule**, and they have each
  disagreed at least once:
  `store.render/render-ns` (the reference), `store.db/rendered-sources` (rows →
  the git wip ref), `slopp.boot/store-sources` (the kernel — cannot call the
  others, since its whole property is booting a store with no slopp code
  loaded), and `element-offsets` below, which must SIMULATE the rendering
  rather than reproduce it. Change one and check all four; the compiler will
  not tell you.
- `element-offsets` = each form's [row col] start within the rendered source.
  This is the bridge from clj-kondo positions to store elements — rename
  correctness depends on it. **It fails silently**: when it went on assuming
  concatenation after the renderer started synthesizing, `change_signature` and
  `rename` returned clean plans containing NO call sites.
- **Placement is just position** (`store/place-form`): a tail append or a
  `:before` insert is a plain vector insert, because the blank line comes from
  the renderer. It stays SHARED by `append-form` (live write) and
  `replay-delta`'s `:add` (journal replay); the two MUST agree on POSITION or a
  reopen / foreign-sync would render differently from the write that produced
  it (`multiproc-test/incremental-sync-replays-the-suffix-exactly` is the
  guard). This used to also juggle whitespace — absorbing trailing trivia,
  preserving a trailing comment, choosing one newline or two — and got it
  wrong: `slopp.api.session` alone carried 33 single-newline separators against
  11 blank-line ones, the dogfooding papercut where added forms jammed together
  (`ideas/git-bridge-friction.md` 1b). One rule in one place cost 345 bytes
  across the whole store and removed the class.

## The fold-field registry (`slopp.store.fields`, D-fold-field-registry)

ONE declaration site per store op / fold-field; the old seven hand-edited
sites derive from it:

- `field-registry`: field → `:init` (seeds `empty-store`), `:meta-key`
  (the db meta row `write-snapshot!` writes and `load-store` reads),
  `:normalize` (load-time canonicalization — retired tier spellings die
  here), `:absent-nil?` (:modules' pre-module marker: never defaulted,
  never written while nil).
- `op-registry`: op → `:fold` (THE fold — `record-*`, `replay-delta`, and
  merge replay all call it; they cannot drift), `:merge` (`:replay` =
  last-writer-wins through the fold; `:bespoke` = merge-logs keeps a
  semantic arm), `:sample`/`:crossed` (the GENERATED merge round-trip test
  in `slopp.store.fields-test` — an op cannot register without proving it
  crosses a merge).
- `markers` / `element-ops` classify the rest. merge-logs REFUSES an op no
  set knows (never a silent skip — that once cost three waves of dropped
  config); replay-delta full-reloads it (safe: load-store reads meta rows).
- ADDING AN OP = one registry entry (+ a `record-*` writer that calls
  `fields/fold`). Nothing else: persistence, replay, merge, and the
  round-trip proof all follow from the entry.
- `db/write-snapshot!` is the single transaction tail `persist!`/`append!`
  share — element rows + next-id + registry meta rows + blobs.

## Gotchas

- Delta payloads must stay plain EDN data (no CST nodes, no objects).
- If you add a delta op with a new key, nothing else is needed for
  persistence (payload column is schemaless) — but decide whether
  `query-lineage` should match it (it matches `:form-id` and `:form-ids`),
  and register the op in `slopp.store.fields` (a marker op joins `markers`,
  else foreign-journal sync falls through to a full reload and a merge
  REFUSES it). `:commit` (P4-m7 milestones) is a marker carrying only its
  description, target and status — plus `:git-sha` on imports.
- **`replay-delta` is TOTAL, and that is load-bearing rather than tidy.** A nil
  return means "the journal is not enough, reload from the elements table",
  and the git projection now derives each milestone's tree by folding the log
  — so an op that cannot replay is a milestone whose bytes cannot be
  reconstructed. Six ops used to return nil (`:ingest`, `:move`, `:rename-ns`,
  `:move-forms`, `:extract-ns`, `:module-extract`); all six carry what they
  need and now replay. The four `apply-changeset` ops are pure node rewrites
  BY FORM-ID — the relocation people assume they carry rides separate
  `:add`/`:delete`/`:ingest` deltas, and the delta names the SOURCE namespace,
  so "these forms now live in `:ns`" is a plausible and wrong reading.
- **External dependency manifest (P4-deps):** `:deps-add`/`:deps-remove` are
  STATE-carrying deltas (not pure markers) — `replay-delta` assoc/dissoc's
  `(:deps store)` (lib→coord) so foreign-sync reconstructs the manifest
  incrementally; `merge-logs` lands foreign deps and, on same-lib version
  divergence, auto-resolves to the NEWER coord (numeric compare via
  `slopp.store.semver/newer?`) with a resolution `:note` — only truly incomparable
  coords (mvn vs git sha, etc.) stay a `:conflict`. The current manifest is materialized
  to a `meta` row `'deps'` (written by `persist!`/`append!` from
  `(:deps store)`, read by `load-store` into `:deps`) so launch/git/native
  read it O(1) without replaying — `db/deps [conn]` is the session-free read.
  Branch propagation is free (snapshot goes through persist!). `:deps` is on
  the store VALUE (like `:next-id`/`:line-id`).
- `.slopp/` is gitignored; what users commit to VCS is an open Phase-4
  question (the delta DAG is meant to BE the history).

## Git bridge (P4-m8 + G-series, `slopp.git` + `slopp.sync`) — in-memory, two faces

- **No on-disk git repo, ever.** `open-repo!` builds a JGit in-memory
  `InMemoryRepository` (DFS backend, built with `FS/DETECTED` — TransportLocal
  needs an FS to resolve file-path remotes); the whole projection is
  **generated from the journal on demand**. `store.db` is the source of truth;
  the git repo is a pure, rebuildable cache.
- **Everything served is a pure function of the journal, and now literally
  so.** `project-journal!` folds the log as it walks, so reaching a `:commit`
  marker means holding the store as it stood there, and the tree is `render-ns`
  over it. `insert-commit!` is deterministic, so a fresh in-memory repo mints
  identical shas — `project-journal!` inserts a commit whenever its object
  isn't already live in the repo (insert-if-absent, keyed on the `git_map`
  pin).
  - Each marker used to carry a byte-exact `:tree` snapshot instead: 82 MB
    across 272 markers (39% of the journal), and 94% of a 344 MB journal in an
    earlier round. It existed because comments lived positionally in the
    elements table — CURRENT state only — so a past milestone's bytes were
    genuinely unreconstructible. Once comments became form-owned content the
    log was complete and the snapshot had no job.
  - **ONE pass matters.** Folding from empty per marker is quadratic in the
    journal; threading the store through the existing walk is not.
  - A marker normally targets the delta immediately before it, which is exactly
    where the fold stands on arrival. `commit_point {:target ...}` can name an
    EARLIER delta, so those positions are rendered as the walk passes them and
    held. **A held tree is not released at its first reader** — a milestone's
    own target is the delta before it, which is what an earlier retroactive
    marker also points at, and dropping it there left the retroactive commit
    silently projecting the CURRENT state.
- `git_map` (main store.db) pins each `:commit` delta → sha at first projection,
  keyed `(delta_id, fingerprint)` (fingerprint = SHA-256 of `[id at description
  target]`); query surfaces read it, and it's the insert-skip key above.
- **SERVER face (local, read-only):** milestones served over localhost
  smart-HTTP (clone/fetch); `git-receive-pack` is never advertised, so pushes
  to the local listener are refused. Edits arrive through slopp's write tools.
  (The old push-IMPORT was dropped with the on-disk bare repo — nothing durable
  to lose.)
- **CLIENT face (external, G-series):** `git/push-to-remote!` pushes the same
  projection to a NORMAL remote (GitHub, any bare repo) — fast-forward only,
  never force; `git/fetch-remote!` + `git/tree-at` read a remote tip and tree
  back. `slopp.sync/clone!` rebuilds a **fileless store** from a remote (verified
  dependency-ordered `ingest!`, deps manifest restored from the remote's
  generated deps.edn); `slopp.sync/push!` saves the remote as `git-remote` meta.
- **The graft:** a cloned store records `git-base-sha` (the remote tip it was
  cloned at); `project-journal!` seeds its parent chain with it, so the clone's
  first local milestone chains onto the remote's REAL history and its pushes
  fast-forward. `push-to-remote!` fetches the remote's objects first when the
  base object isn't in the in-memory repo (fresh process). Serving the LOCAL
  listener for a cloned store offline (base objects unfetched) degrades with an
  error — push/pull paths always fetch first.
- The remote is a normal file repo: only MILESTONES cross the wire (a clone
  gets the last commit_point's tree, not un-milestone'd live state); non-source
  files on a remote (README, CI) are ignored by clone/pull.
- **Pull (G4):** `sync/pull!` = fetch → `merge-base(ours, tip)` → 3-way diff
  applied at form granularity (remote wins where we're clean; verified
  writes, remote dependency order, form-order fixup). Both-touched forms,
  file deletions, and gate-failing files → the **`quarantine` table**
  (path/ns/raw source/sha/reason — OFF the journal); `push!` refuses while
  rows exist; the agent merges via edit tools then `git_resolve`. The pull
  ends with a `:commit` marker carrying `:git-sha <tip>`, which
  `project-journal!` ADOPTS as the chain node (never mints) — the next
  milestone parents on the remote tip, keeping pushes fast-forward.
  `ensure-projected!` lazily fetches chain objects it doesn't hold
  (`requiring-resolve` of `fetch-remote!` — append-order forced the late
  bind); offline, ref updates throw and callers degrade.
- Projection ordering: journal marker → git objects (content-addressed,
  idempotent) → git_map row (INSERT OR IGNORE + read-back) → ref CAS;
  `ensure-projected!` rebuilds the in-memory repo from the journal on demand.

## m5a: journal-first commits (storage inversion)

Durable sessions commit through `db/append!`: new deltas + full element rows
of the touched namespaces + the id counter, in ONE transaction, conditional
on the journal head still matching the commit's base. On head-moved (or
SQLITE_BUSY) the writer refreshes its cached store from the db
(`api/refresh-cache!`, advance-only) and rebases. The in-memory store is a
cache of the journal, never ahead of it; there is NO async persist queue —
the append is the persist. `db/persist!` remains only for whole-store
snapshots (branch creation). This is the substrate for multi-process
servers sharing one store dir (m5b/c): SQLite WAL serializes writers across
processes, and the same append-CAS protocol arbitrates them.
