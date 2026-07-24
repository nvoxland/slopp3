(ns slopp.store.db
  "Durable system of record (C7): SQLite at `<dir>/.slopp/store.db`. Because of
  C1 there are no `.clj` files on disk — this database IS the source code — so
  it gets a real storage engine rather than hand-rolled EDN files.

  Layout:
  - `deltas`   — the append-only log (the history). Op-specific fields live in
                 an EDN `payload` column; EDN stays the value representation,
                 SQLite supplies the durability mechanics.
  - `elements` — the materialized current form-state, kept transactionally
                 in-step with the log (open = read rows, no log replay).
  - `meta`     — the id counter, so a reopened store keeps minting unique ids.

  Every mutation lands in ONE transaction: delta row + its namespace's element
  rows + next-id, atomically. WAL mode for crash safety."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n] [slopp.store.fields :as fields]))

(defn ^:export open!
  "Open (creating if needed) the store db under `dir`; returns the connection.

  `{:create? false}` returns NIL instead of creating one when `dir` has no
  store yet — for callers that merely ASK whether a dir is slopp-managed.
  The MCP server is launched in whatever directory the editor has open, so
  an unconditional create colonises every project a user opens: an empty
  `.slopp/store.db` appears, and from then on the session-pause hook has
  something to write checkpoints into. Serving is a question, not an
  adoption; the store is materialized by the first real write."
  (^java.sql.Connection [dir] (open! dir nil))
  (^java.sql.Connection [dir {:keys [create?] :or {create? true}}]
   (let [f (io/file dir ".slopp" "store.db")]
     (when (or create? (.exists f))
       (io/make-parents f)
       (let [conn (jdbc/get-connection
                   (jdbc/get-datasource {:dbtype "sqlite" :dbname (str f)}))]
         (jdbc/execute! conn ["PRAGMA journal_mode=WAL"])
         (jdbc/execute! conn ["PRAGMA busy_timeout=5000"])
         (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS meta (
                              k TEXT PRIMARY KEY, v TEXT NOT NULL)"])
         ;; `tree` is DELIBERATELY its own column, not part of `payload`: a :commit
         ;; marker's byte-exact tree snapshot is ~1.35MB, and payloads are parsed
         ;; on EVERY session open while only the git projection ever reads a tree
         ;; (measured: 239 markers = 94% of this journal, 7.4s of a 9.4s load).
         ;; load-store selects explicit columns and never touches it; read it
         ;; with db/delta-tree.
         (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS deltas (
                              seq     INTEGER PRIMARY KEY AUTOINCREMENT,
                              id      TEXT UNIQUE NOT NULL,
                              op      TEXT NOT NULL,
                              ns      TEXT NOT NULL,
                              payload TEXT NOT NULL,
                              tree    TEXT)"])
         ;; stores created before the column: SQLite has no ADD COLUMN IF NOT
         ;; EXISTS, so adding it twice is the expected no-op
         (try (jdbc/execute! conn ["ALTER TABLE deltas ADD COLUMN tree TEXT"])
              (catch java.sql.SQLException _ nil))
         (jdbc/execute! conn ["CREATE INDEX IF NOT EXISTS deltas_ns ON deltas(ns)"])
         (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS elements (
                              ns      TEXT NOT NULL,
                              pos     INTEGER NOT NULL,
                              kind    TEXT NOT NULL,
                              form_id TEXT,
                              name    TEXT,
                              source  TEXT NOT NULL,
                              PRIMARY KEY (ns, pos))"])
         ;; content-addressed dependency analysis (P4-deps M4/M6), keyed by
         ;; "lib@version" — a surface/native verdict is a pure fn of the coord
         (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS dep_surface (
                              id      TEXT PRIMARY KEY,
                              surface TEXT,
                              native  TEXT)"])
         ;; git-pull conflicts, held OFF the journal (G-series): the raw remote
         ;; file + provenance, kept until the agent resolves — the journal only
         ;; ever holds slopp-valid forms
         (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS quarantine (
                              path    TEXT PRIMARY KEY,
                              ns      TEXT,
                              source  TEXT,
                              sha     TEXT NOT NULL,
                              reason  TEXT NOT NULL,
                              at      INTEGER NOT NULL)"])
         ;; content-addressed binary assets (D-web wave 4): bytes live HERE,
         ;; the journal carries only shas — a large asset costs the log ~60B
         (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS blobs (
                              sha   TEXT PRIMARY KEY,
                              bytes BLOB NOT NULL)"])
         conn)))))

^:reads (defn ^:export data-version
  "SQLite's cheap foreign-commit detector: this value changes when ANOTHER
  connection (thread or process) has committed to the database since we last
  looked — our own writes through this connection don't bump it."
  [conn]
  (:data_version (jdbc/execute-one! conn ["PRAGMA data_version"])))

(defn- parse-node
  "Re-parse one element's canonical serialization (its source text) back to its
  CST node. Lossless by rewrite-clj's parse/print round-trip."
  [source]
  (let [nodes (n/children (p/parse-string-all source))]
    (assert (= 1 (count nodes))
            (str "element source did not reparse to one node: " (pr-str source)))
    (first nodes)))

(defn- row->element [row]
  (let [kind (keyword (:elements/kind row))
        node (parse-node (:elements/source row))]
    (if (= :form kind)
      {:id   (:elements/form_id row) :kind :form
       :name (some-> (:elements/name row) symbol) :node node}
      {:kind :sep :node node})))

(defn- row->delta [row]
  (merge {:id (:deltas/id row)
          :op (keyword (:deltas/op row))
          :ns (symbol (:deltas/ns row))}
         (edn/read-string (:deltas/payload row))))

(defn ^:export set-line-id!
  "Stamp this store db with its line identity (branch creation)."
  [conn line-id]
  (jdbc/execute! conn ["INSERT INTO meta (k,v) VALUES ('line-id', ?)
                        ON CONFLICT(k) DO UPDATE SET v = excluded.v" line-id]))

^:reads (defn ^:export deps
  "The store's external-dependency manifest, read straight from meta — for
  the git/native/launch paths that need it without opening a session."
  [conn]
  (or (some-> (jdbc/execute-one! conn ["SELECT v FROM meta WHERE k = 'deps'"])
              :meta/v edn/read-string)
      {}))

^:reads (defn ^:export get-dep-surface
  "The cached analysis surface for a dependency `id` (\"lib@version\"), or nil."
  [conn id]
  (some-> (jdbc/execute-one! conn ["SELECT surface FROM dep_surface WHERE id = ?" id])
          :dep_surface/surface edn/read-string))

(defn ^:export put-dep-surface!
  "Cache `surface` (an EDN-able map) for dependency `id`. Content-addressed by
  coord@version — computed once, reused forever."
  [conn id surface]
  (jdbc/execute! conn ["INSERT INTO dep_surface (id, surface) VALUES (?,?)
                        ON CONFLICT(id) DO UPDATE SET surface = excluded.surface"
                       id (pr-str surface)]))

^:reads (defn ^:export get-dep-native
  "The cached native-image verdict for a dependency `id`, or nil (P4-deps M6)."
  [conn id]
  (some-> (jdbc/execute-one! conn ["SELECT native FROM dep_surface WHERE id = ?" id])
          :dep_surface/native edn/read-string))

(defn ^:export put-dep-native!
  "Cache the native-compat `verdict` (EDN map) for dependency `id`."
  [conn id verdict]
  (jdbc/execute! conn ["INSERT INTO dep_surface (id, native) VALUES (?,?)
                        ON CONFLICT(id) DO UPDATE SET native = excluded.native"
                       id (pr-str verdict)]))

^:reads (defn ^:export rendered-sources
  "{ns-sym rendered-source} straight from the element rows — the `source`
  column is each element's canonical serialization, so concatenation by pos
  IS the render-ns output, byte-exact. The live state without parsing,
  replaying, or a session (P4-m8 wip projection reads it per request)."
  [conn]
  (reduce (fn [m row]
            (update m (symbol (:elements/ns row))
                    (fnil str "") (:elements/source row)))
          {}
          (jdbc/execute! conn ["SELECT ns, source FROM elements
                                ORDER BY ns, pos"])))

^:reads (defn ^:export commit-shas
  "P4-m8: {delta-id git-sha} from the projection's pinning table (created and
  written by slopp.git; this is read-only convenience for query surfaces).
  Nil when nothing has been projected. Only UNAMBIGUOUS rows: a delta id
  that collides across lines (post-fork id reuse) is omitted, never guessed."
  [conn]
  (when (seq (jdbc/execute! conn ["SELECT name FROM sqlite_master
                                   WHERE type='table' AND name='git_map'"]))
    (into {}
          (keep (fn [row]
                  ;; aggregates come back unqualified; plain columns may not
                  (when (= 1 (or (:n row) (:git_map/n row)))
                    [(or (:delta_id row) (:git_map/delta_id row))
                     (or (:sha row) (:git_map/sha row))])))
          (jdbc/execute! conn ["SELECT delta_id, MIN(sha) AS sha, COUNT(*) AS n
                                FROM git_map GROUP BY delta_id"]))))

^:reads (defn ^:export deltas-after
  "The journal suffix past the first `n` deltas (incremental sync)."
  [conn n]
  (mapv row->delta
        (jdbc/execute! conn ["SELECT * FROM deltas ORDER BY seq LIMIT -1 OFFSET ?"
                             (long n)])))

^:reads (defn ^:export config-files
  "The store's structured-config entries ({path {:format :values}}), read
  straight from meta — for the projection paths that need it session-free."
  [conn]
  (or (some-> (jdbc/execute-one! conn ["SELECT v FROM meta WHERE k = 'config'"])
              :meta/v edn/read-string)
      {}))

(defn put-blobs!
  "Write `blobs` ({sha → bytes}) INSERT OR IGNORE — content-addressed, so
  rewriting an existing sha is a no-op. Callable inside a transaction."
  [tx blobs]
  (doseq [[sha ^bytes bs] blobs]
    (jdbc/execute! tx ["INSERT OR IGNORE INTO blobs (sha, bytes) VALUES (?,?)"
                       (str sha) bs])))

^:reads (defn ^:export get-blob
  "The bytes stored under `sha`, or nil — the sessionless read (git
  projection, build) and the session cache's fallback."
  [conn sha]
  (some-> (jdbc/execute-one! conn ["SELECT bytes FROM blobs WHERE sha = ?" (str sha)])
          :blobs/bytes))

^:reads (defn ^:export files
  "The store's non-code files manifest ({path → text}), read straight from
  meta — for the git projection paths that need it without a session."
  [conn]
  (or (some-> (jdbc/execute-one! conn ["SELECT v FROM meta WHERE k = 'files'"])
              :meta/v edn/read-string)
      {}))

^:reads (defn ^:export load-store
  "Reconstruct the full in-memory store from the db, or nil if empty. Every
  registry meta row loads through ONE loop (default from :init unless
  :absent-nil?, :normalize applied — retired vocabulary canonicalizes here,
  so an old db stops re-minting it into fold state); only the bespoke
  element/delta/blob storage is hand-read."
  [conn]
  (when-let [next-id (some-> (jdbc/execute-one!
                              conn ["SELECT v FROM meta WHERE k = 'next-id'"])
                             :meta/v Long/parseLong)]
    (into
     {:namespaces (reduce (fn [m row]
                            (update-in m [(symbol (:elements/ns row)) :elements]
                                       (fnil conj []) (row->element row)))
                          {}
                          (jdbc/execute! conn ["SELECT * FROM elements ORDER BY ns, pos"]))
      :deltas     (mapv row->delta
                        ;; EXPLICIT columns — never SELECT `tree`. The whole point of splitting it
      ;; out is that ~1.35MB per :commit marker is neither fetched nor parsed
      ;; here; db/delta-tree reads it on demand for the git projection.
                        (jdbc/execute! conn ["SELECT id, op, ns, payload FROM deltas
                                              ORDER BY seq"]))
      :next-id    next-id
      :line-id    (:meta/v (jdbc/execute-one!
                            conn ["SELECT v FROM meta WHERE k = 'line-id'"]))
      ;; NOT loaded at open. :blobs is a partial cache by design — file-content
      ;; documents the miss and the db fallback owns it, and put-blobs! is
      ;; INSERT OR IGNORE so an empty cache never prunes. Reading every blob's
      ;; bytes here cost a compiled JS bundle (~1.8MB) on every session open.
      :blobs      {}}
     (map (fn [{:keys [field meta-key init absent-nil? normalize]}]
            (let [raw (some-> (jdbc/execute-one!
                               conn ["SELECT v FROM meta WHERE k = ?" meta-key])
                              :meta/v edn/read-string)
                  v   (if (and (nil? raw) (not absent-nil?)) init raw)]
              [field (if (and normalize (some? v)) (normalize v) v)])))
     (fields/meta-fields))))

^:reads (defn ^:export get-meta
  "Read a meta row's value (nil when absent) — the k/v side-table for
  config the journal doesn't track (e.g. `git-remote`, `git-base-sha`)."
  [conn k]
  (:meta/v (jdbc/execute-one! conn ["SELECT v FROM meta WHERE k = ?" k])))

^:reads (defn ^:export meta-with-prefix
  "Every meta row whose key starts with `prefix`, as `{k v}`. The k/v
  side-table has no other way to be enumerated, and observations are stored
  one row per form (`observed/<ns>/<name>`) — they load in one scan at
  session open, like the trace map, so the card view can read them from
  session state instead of the db."
  [conn prefix]
  (into {}
        (map (fn [r] [(:meta/k r) (:meta/v r)]))
        (jdbc/execute! conn ["SELECT k, v FROM meta WHERE k LIKE ?"
                             (str prefix "%")])))

(defn ^:export set-meta!
  "Upsert a meta row — the write side of `get-meta`."
  [conn k v]
  (jdbc/execute! conn ["INSERT INTO meta (k,v) VALUES (?,?)
                        ON CONFLICT(k) DO UPDATE SET v = excluded.v" k (str v)])
  nil)

(defn ^:export quarantine-put!
  "Record a git-pull conflict for `path` (upsert): the raw remote `source`
  (nil for deletions), the remote `sha` it came from, and the human `reason`.
  Off-log by design — never touches the journal."
  [conn {:keys [path ns source sha reason]}]
  (jdbc/execute! conn ["INSERT INTO quarantine (path, ns, source, sha, reason, at)
                        VALUES (?,?,?,?,?,?)
                        ON CONFLICT(path) DO UPDATE SET
                          ns = excluded.ns, source = excluded.source,
                          sha = excluded.sha, reason = excluded.reason,
                          at = excluded.at"
                       path (some-> ns str) source sha reason
                       (System/currentTimeMillis)])
  nil)

^:reads (defn ^:export quarantine-list
  "Every unresolved git-pull conflict, oldest first:
  [{:path :ns :source :sha :reason :at}]."
  [conn]
  (mapv (fn [row]
          {:path   (:quarantine/path row)
           :ns     (some-> (:quarantine/ns row) symbol)
           :source (:quarantine/source row)
           :sha    (:quarantine/sha row)
           :reason (:quarantine/reason row)
           :at     (:quarantine/at row)})
        (jdbc/execute! conn ["SELECT * FROM quarantine ORDER BY at, path"])))

(defn ^:export quarantine-clear!
  "Resolve one conflict (`path`) — or ALL of them when path is nil."
  [conn path]
  (if path
    (jdbc/execute! conn ["DELETE FROM quarantine WHERE path = ?" path])
    (jdbc/execute! conn ["DELETE FROM quarantine"]))
  nil)

(defn- write-snapshot!
  "The shared tail of persist!/append!: the touched namespaces' full element
  rows, the id counter, every registry meta row, and the blob table — ONE
  loop over slopp.store.fields/meta-fields, so a new fold-field persists by
  registration instead of by editing two near-identical transactions (the
  copy-paste this replaces silently lost any field a hand missed in ONE of
  them — surviving tests, vanishing on the live server's restart)."
  [tx store nses]
  (doseq [ns-sym nses]
    ;; delete ALWAYS: a ns absent from the store (renamed away) must have
    ;; its rows purged, not linger for the next reopen
    (jdbc/execute! tx ["DELETE FROM elements WHERE ns = ?" (str ns-sym)])
    (doseq [[pos e] (map-indexed vector
                                 (get-in store [:namespaces ns-sym :elements]))]
      (jdbc/execute! tx ["INSERT INTO elements (ns,pos,kind,form_id,name,source)
                          VALUES (?,?,?,?,?,?)"
                         (str ns-sym) pos (name (:kind e)) (:id e)
                         (some-> (:name e) str) (n/string (:node e))])))
  (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('next-id', ?)
                      ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                     (str (:next-id store))])
  (doseq [{:keys [field meta-key init absent-nil?]} (fields/meta-fields)]
    (let [v (get store field)]
      ;; :absent-nil? fields (the :modules pre-module adoption marker) are
      ;; never written while nil — a default here would destroy the marker
      ;; before open! ever sees it
      (when-not (and absent-nil? (nil? v))
        (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES (?, ?)
                            ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                           meta-key (pr-str (if (nil? v) init v))]))))
  (put-blobs! tx (:blobs store {})))

(defn writer-collision?
  "Is this SQLException SQLite's WRITER COLLISION (busy / locked) — the only
  kind a refresh-and-rebase can fix? Everything else (a missing column, a
  constraint violation) is a real fault and must surface.

  This distinction is load-bearing: `append!` used to treat every SQLException
  as a lost race, so a malformed statement came back as `false`, the caller
  retried it twelve times, and the agent was told \"commit contention: too many
  concurrent writes\" while the store was actually unwritable. An error may only
  name a cause it checked."
  [^java.sql.SQLException e]
  (let [m (.toLowerCase (str (.getMessage e)))]
    (or (.contains m "busy") (.contains m "locked"))))

(defn ^:export append!
  "Phase-a storage inversion: conditionally append `new-deltas` (+ the full
  snapshot tail via write-snapshot!) in ONE transaction, iff the journal head
  still equals `expected-head` (nil for an empty log). Returns true on
  commit; false if the head moved or the db was busy — the caller refreshes
  its cache and rebases. SQLite (WAL) serializes writers across threads AND
  processes, which is what makes the shared-storage multi-server split
  possible."
  [conn store new-deltas nses expected-head]
  (try
    (jdbc/with-transaction [tx conn]
      (let [head (:deltas/id (jdbc/execute-one!
                              tx ["SELECT id FROM deltas ORDER BY seq DESC LIMIT 1"]))]
        (when (not= head expected-head)
          (throw (ex-info "journal head moved" {::head-moved true})))
        (doseq [d new-deltas]
          (jdbc/execute! tx ["INSERT INTO deltas (id, op, ns, payload, tree)
                              VALUES (?,?,?,?,?)"
                             (:id d) (name (:op d)) (str (:ns d))
                             ;; :tree is split OUT of the payload — it is the
                             ;; one huge, rarely-read field, and payloads are
                             ;; parsed on every session open
                             (pr-str (dissoc d :id :op :ns :tree))
                             (some-> (:tree d) pr-str)]))
        (write-snapshot! tx store nses)
        true))
    (catch clojure.lang.ExceptionInfo e
      (if (::head-moved (ex-data e)) false (throw e)))
    ;; ONLY a writer collision is a retryable lost race. Any other SQL fault
    ;; must SURFACE: swallowing it returned false, the caller retried, and the
    ;; agent was told "commit contention" for what was really a bad statement.
    (catch java.sql.SQLException e
      (if (writer-collision? e) false (throw e)))))

(defn ^:export persist!
  "Write one mutation atomically: the delta, then the full snapshot tail
  (element rows of the touched namespaces, id counter, registry meta rows,
  blobs) via write-snapshot!. Namespaces are small; rewriting a ns's rows per
  edit keeps the write-through trivially correct. Multi-ns mutations (e.g. a
  cross-ns rename) pass the touched `nses` explicitly."
  ([conn store delta] (persist! conn store delta [(:ns delta)]))
  ([conn store delta nses]
   (jdbc/with-transaction [tx conn]
     (jdbc/execute! tx ["INSERT INTO deltas (id, op, ns, payload) VALUES (?,?,?,?)"
                        (:id delta) (name (:op delta)) (str (:ns delta))
                        (pr-str (dissoc delta :id :op :ns))])
     (write-snapshot! tx store nses))
   nil))

^:reads (defn ^:export delta-tree
          "The byte-exact rendered `:tree` snapshot of `:commit` delta `id`, parsed
  ON DEMAND, or nil when there is none — a non-commit, a retroactive `:target`
  marker (which never captures one), or a delta written before the split, whose
  tree still rides its payload. It lives in its own column because it is ~1.35MB
  per milestone and ONLY the git projection reads it, while payloads are parsed
  at every session open; `slopp.git/project-journal!` falls back to the payload's
  `:tree`, then to `backfill-tree`, so old markers keep projecting."
          [conn id]
          (some-> (jdbc/execute-one! conn ["SELECT tree FROM deltas WHERE id = ?" id])
                  :deltas/tree
                  edn/read-string))

^:reads (defn ^:export journal-stats
          "What the store CARRIES, in bytes: the journal (per op, heaviest first,
  with commit `tree` snapshots counted APART from payloads), the materialized
  state, and the blob table. A pure read straight off SQLite's LENGTH — nothing
  is parsed, so it stays cheap on a large journal.

  This exists because nothing measured cost. A byte-exact `:tree` snapshot
  inline in every `:commit` payload reached 94% of a 344MB journal — ~1.35MB per
  milestone against a design note estimating \"tens of KB\" — and went unnoticed
  across 239 milestones while `full_check` happily counted namespaces and tests.
  A store can rot by GROWING, and only a number catches that."
          [conn]
          (let [rows (jdbc/execute!
                      conn ["SELECT op,
                                    COUNT(*)                        AS n,
                                    SUM(LENGTH(payload))            AS pbytes,
                                    SUM(LENGTH(COALESCE(tree, ''))) AS tbytes
                             FROM deltas GROUP BY op"])
                by-op (->> rows
                           ;; next.jdbc qualifies a real column by its table (:deltas/op) while a
                           ;; computed alias comes back bare — read both rather than betting
                           (map (fn [r] {:op (or (:deltas/op r) (:op r))
                                         :n (or (:n r) 0)
                                         :payload-bytes (or (:pbytes r) 0)
                                         :tree-bytes (or (:tbytes r) 0)}))
                           (sort-by #(- (+ (:payload-bytes %) (:tree-bytes %))))
                           vec)
                els  (jdbc/execute-one!
                      conn ["SELECT COUNT(*) AS n, SUM(LENGTH(source)) AS b FROM elements"])
                bl   (jdbc/execute-one!
                      conn ["SELECT COUNT(*) AS n, SUM(LENGTH(bytes)) AS b FROM blobs"])]
            {:deltas   {:n (reduce + 0 (map :n by-op))
                        :payload-bytes (reduce + 0 (map :payload-bytes by-op))
                        :tree-bytes    (reduce + 0 (map :tree-bytes by-op))
                        :by-op by-op}
             :elements {:n (or (:n els) 0) :source-bytes (or (:b els) 0)}
             :blobs    {:n (or (:n bl) 0) :bytes (or (:b bl) 0)}}))
