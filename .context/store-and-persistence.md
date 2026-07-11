# Store & persistence

## In-memory model (`slopp.store` — pure functions over values)

- A namespace = ordered vector of **elements**: `{:id :kind :form :name :node}`
  for semantic forms, `{:kind :sep :node}` for whitespace/comment trivia kept
  only so rendering is lossless. `:node` is a rewrite-clj CST node.
- `:name` is derived from `def-heads` (`def/defn/.../deftest/ns`); nil for
  anonymous top-levels. **`deftest` is a named form on purpose** (tests are
  addressable/editable).
- Ids: `"f<n>"` forms, `"d<n>"` deltas, single monotonic counter (`:next-id`).
  The DB has a UNIQUE constraint on delta ids as a collision backstop.
- Deltas: `{:id :parent :op :ns :prompt ...}` — ops today: `:ingest`,
  `:replace`, `:add`, `:delete`, `:rename` (multi-form, `:form-ids`),
  `:verify` (result attached). `:parent` = previous delta id (linear now,
  DAG-ready).
- Multi-form coordinated edits go through `apply-changeset` → ONE delta over
  N forms (used by rename).
- **Purity is load-bearing:** transactional/atomic behaviors at the api layer
  (e.g. group validation) work by applying store fns to a value and only
  committing the result on success.

## Persistence (`slopp.db`, decision C7)

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

## Rendering (`slopp.render`)

- `render-ns` = concat of element node strings — byte-exact round trip with
  ingestion (tested over a corpus; keep it that way).
- `element-offsets` = each element's [row col] start within the rendered
  source. This is the bridge from clj-kondo positions to store elements —
  rename correctness depends on it.

## Gotchas

- Delta payloads must stay plain EDN data (no CST nodes, no objects).
- If you add a delta op with a new key, nothing else is needed for
  persistence (payload column is schemaless) — but decide whether
  `query-lineage` should match it (it matches `:form-id` and `:form-ids`),
  and add no-content marker ops to `replay-delta`'s marker case (else
  foreign-journal sync falls through to a full reload). `:commit` (P4-m7
  milestones) is a marker — since P4-m8 its payload also carries `:tree`
  (byte-exact rendered {ns source} snapshot) and, on imports, `:git-sha`.
- **External dependency manifest (P4-deps):** `:deps-add`/`:deps-remove` are
  STATE-carrying deltas (not pure markers) — `replay-delta` assoc/dissoc's
  `(:deps store)` (lib→coord) so foreign-sync reconstructs the manifest
  incrementally; `merge-logs` lands foreign deps and, on same-lib version
  divergence, auto-resolves to the NEWER coord (numeric compare via
  `slopp.semver/newer?`) with a resolution `:note` — only truly incomparable
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
- **Everything served is a pure function of the journal.** Each `:commit` delta
  carries a byte-exact `:tree` snapshot and `insert-commit!` is deterministic, so
  a fresh in-memory repo mints IDENTICAL shas — `project-journal!` inserts a
  commit whenever its object isn't already live in the repo (insert-if-absent,
  keyed on the `git_map` pin).
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
  files on a remote (README, CI) are ignored by clone; a `.clj` that fails
  slopp's gates fails the clone with the reason (quarantine/conflict flow =
  Phase-2 pull).
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
