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

## Git projection durability (P4-m8, `slopp.git`)

- `git_map` (main store.db) pins each `:commit` delta → git sha at first
  projection, keyed `(delta_id, fingerprint)` — branch journals share
  main's prefix by VALUE, so shared markers resolve to one row;
  fingerprint = SHA-256 of `[id at description target]` (a canonical tuple,
  never the printed map).
- **Native milestones re-derive from the journal** (`:tree` payload makes
  each commit a pure function of its marker): the bare repo at
  `.slopp/git` is a rebuildable cache — delete it + the git_map rows and
  re-projection mints IDENTICAL shas.
- **Imported commits (git push) exist only in the bare repo** (the pushed
  pack); their shas are identity, never re-derived. Once anything has been
  pushed in, `.slopp/git` is durable state — deleting it rewrites imported
  history.
- Projection ordering (crash-safe): journal marker → git objects →
  git_map row → ref CAS; `ensure-projected!` repairs any interruption.

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
