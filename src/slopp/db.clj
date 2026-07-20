(ns slopp.db
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
            [rewrite-clj.node :as n]))

(defn open!
  "Open (creating if needed) the store db under `dir`; returns the connection."
  ^java.sql.Connection [dir]
  (let [f (io/file dir ".slopp" "store.db")]
    (io/make-parents f)
    (let [conn (jdbc/get-connection
                (jdbc/get-datasource {:dbtype "sqlite" :dbname (str f)}))]
      (jdbc/execute! conn ["PRAGMA journal_mode=WAL"])
      (jdbc/execute! conn ["PRAGMA busy_timeout=5000"])
      (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS meta (
                              k TEXT PRIMARY KEY, v TEXT NOT NULL)"])
      (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS deltas (
                              seq     INTEGER PRIMARY KEY AUTOINCREMENT,
                              id      TEXT UNIQUE NOT NULL,
                              op      TEXT NOT NULL,
                              ns      TEXT NOT NULL,
                              payload TEXT NOT NULL)"])
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
      conn)))

(defn persist!
  "Write one mutation atomically: the delta, the (full) current element rows of
  the namespaces it touched, and the id counter. Namespaces are small; rewriting
  a ns's rows per edit keeps the write-through trivially correct. Multi-ns
  mutations (e.g. a cross-ns rename) pass the touched `nses` explicitly."
  ([conn store delta] (persist! conn store delta [(:ns delta)]))
  ([conn store delta nses]
   (jdbc/with-transaction [tx conn]
     (jdbc/execute! tx ["INSERT INTO deltas (id, op, ns, payload) VALUES (?,?,?,?)"
                        (:id delta) (name (:op delta)) (str (:ns delta))
                        (pr-str (dissoc delta :id :op :ns))])
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
     (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('deps', ?)
                         ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                        (pr-str (:deps store {}))])
     (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('dep-ns', ?)
                         ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                        (pr-str (:dep-ns store {}))])
     (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('dep-pure', ?)
                         ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                        (pr-str (:dep-pure store #{}))])
     (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('module-tiers', ?)
                         ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                        (pr-str (:module-tiers store {}))])
     (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('files', ?)
                         ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                        (pr-str (:files store {}))])
     (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('config', ?)
                         ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                        (pr-str (:config store {}))])
     (when (:modules store)
       (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('modules', ?)
                           ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                          (pr-str (:modules store))])))
   nil))

^:reads (defn data-version
  "SQLite's cheap foreign-commit detector: this value changes when ANOTHER
  connection (thread or process) has committed to the database since we last
  looked — our own writes through this connection don't bump it."
  [conn]
  (:data_version (jdbc/execute-one! conn ["PRAGMA data_version"])))

(defn append!
  "Phase-a storage inversion: conditionally append `new-deltas` (+ the full
  element rows of `nses`, + the id counter) in ONE transaction, iff the
  journal head still equals `expected-head` (nil for an empty log). Returns
  true on commit; false if the head moved or the db was busy — the caller
  refreshes its cache and rebases. SQLite (WAL) serializes writers across
  threads AND processes, which is what makes the shared-storage multi-server
  split possible."
  [conn store new-deltas nses expected-head]
  (try
    (jdbc/with-transaction [tx conn]
      (let [head (:deltas/id (jdbc/execute-one!
                              tx ["SELECT id FROM deltas ORDER BY seq DESC LIMIT 1"]))]
        (when (not= head expected-head)
          (throw (ex-info "journal head moved" {::head-moved true})))
        (doseq [d new-deltas]
          (jdbc/execute! tx ["INSERT INTO deltas (id, op, ns, payload) VALUES (?,?,?,?)"
                             (:id d) (name (:op d)) (str (:ns d))
                             (pr-str (dissoc d :id :op :ns))]))
        (doseq [ns-sym nses]
          ;; delete ALWAYS: a ns absent from the store (renamed away) must
          ;; have its rows purged, not linger for the next reopen
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
        (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('deps', ?)
                            ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                           (pr-str (:deps store {}))])
        (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('dep-ns', ?)
                            ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                           (pr-str (:dep-ns store {}))])
        (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('dep-pure', ?)
                            ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                           (pr-str (:dep-pure store #{}))])
        (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('module-tiers', ?)
                            ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                           (pr-str (:module-tiers store {}))])
        (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('files', ?)
                            ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                           (pr-str (:files store {}))])
        (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('config', ?)
                            ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                           (pr-str (:config store {}))])
        (when (:modules store)
          ;; nil = pre-module session; writing a default here would destroy
          ;; the adoption marker before open! ever sees it
          (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('modules', ?)
                              ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                             (pr-str (:modules store))]))
        true))
    (catch clojure.lang.ExceptionInfo e
      (if (::head-moved (ex-data e)) false (throw e)))
    (catch java.sql.SQLException _ false)))   ; busy/locked = contention

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

(defn set-line-id!
  "Stamp this store db with its line identity (branch creation)."
  [conn line-id]
  (jdbc/execute! conn ["INSERT INTO meta (k,v) VALUES ('line-id', ?)
                        ON CONFLICT(k) DO UPDATE SET v = excluded.v" line-id]))

^:reads (defn deps
  "The store's external-dependency manifest, read straight from meta — for
  the git/native/launch paths that need it without opening a session."
  [conn]
  (or (some-> (jdbc/execute-one! conn ["SELECT v FROM meta WHERE k = 'deps'"])
              :meta/v edn/read-string)
      {}))

^:reads (defn get-dep-surface
  "The cached analysis surface for a dependency `id` (\"lib@version\"), or nil."
  [conn id]
  (some-> (jdbc/execute-one! conn ["SELECT surface FROM dep_surface WHERE id = ?" id])
          :dep_surface/surface edn/read-string))

(defn put-dep-surface!
  "Cache `surface` (an EDN-able map) for dependency `id`. Content-addressed by
  coord@version — computed once, reused forever."
  [conn id surface]
  (jdbc/execute! conn ["INSERT INTO dep_surface (id, surface) VALUES (?,?)
                        ON CONFLICT(id) DO UPDATE SET surface = excluded.surface"
                       id (pr-str surface)]))

^:reads (defn get-dep-native
  "The cached native-image verdict for a dependency `id`, or nil (P4-deps M6)."
  [conn id]
  (some-> (jdbc/execute-one! conn ["SELECT native FROM dep_surface WHERE id = ?" id])
          :dep_surface/native edn/read-string))

(defn put-dep-native!
  "Cache the native-compat `verdict` (EDN map) for dependency `id`."
  [conn id verdict]
  (jdbc/execute! conn ["INSERT INTO dep_surface (id, native) VALUES (?,?)
                        ON CONFLICT(id) DO UPDATE SET native = excluded.native"
                       id (pr-str verdict)]))

^:reads (defn rendered-sources
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

^:reads (defn commit-shas
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

^:reads (defn deltas-after
  "The journal suffix past the first `n` deltas (incremental sync)."
  [conn n]
  (mapv row->delta
        (jdbc/execute! conn ["SELECT * FROM deltas ORDER BY seq LIMIT -1 OFFSET ?"
                             (long n)])))

^:reads (defn config-files
  "The store's structured-config entries ({path {:format :values}}), read
  straight from meta — for the projection paths that need it session-free."
  [conn]
  (or (some-> (jdbc/execute-one! conn ["SELECT v FROM meta WHERE k = 'config'"])
              :meta/v edn/read-string)
      {}))
^:reads (defn files
  "The store's non-code files manifest ({path → text}), read straight from
  meta — for the git projection paths that need it without a session."
  [conn]
  (or (some-> (jdbc/execute-one! conn ["SELECT v FROM meta WHERE k = 'files'"])
              :meta/v edn/read-string)
      {}))
^:reads (defn load-store
  "Reconstruct the full in-memory store from the db, or nil if empty."
  [conn]
  (when-let [next-id (some-> (jdbc/execute-one!
                              conn ["SELECT v FROM meta WHERE k = 'next-id'"])
                             :meta/v Long/parseLong)]
    {:namespaces (reduce (fn [m row]
                           (update-in m [(symbol (:elements/ns row)) :elements]
                                      (fnil conj []) (row->element row)))
                         {}
                         (jdbc/execute! conn ["SELECT * FROM elements ORDER BY ns, pos"]))
     :deltas     (mapv row->delta
                       (jdbc/execute! conn ["SELECT * FROM deltas ORDER BY seq"]))
     :next-id    next-id
     :line-id    (:meta/v (jdbc/execute-one!
                           conn ["SELECT v FROM meta WHERE k = 'line-id'"]))
     :deps       (deps conn)
     :files      (files conn)
     :config     (config-files conn)
     ;; nil (row absent) = a pre-module db — open! adopts it by deriving
     ;; the manifest from the actual dependency graph
     :modules    (some-> (jdbc/execute-one!
                          conn ["SELECT v FROM meta WHERE k = 'modules'"])
                         :meta/v edn/read-string)
     :dep-ns     (or (some-> (jdbc/execute-one!
                              conn ["SELECT v FROM meta WHERE k = 'dep-ns'"])
                             :meta/v edn/read-string) {})
     :dep-pure   (or (some-> (jdbc/execute-one!
                              conn ["SELECT v FROM meta WHERE k = 'dep-pure'"])
                             :meta/v edn/read-string) #{})
     ;; absent = {} (no tiers declared); tiers are opt-in tightening (D9)
     :module-tiers (or (some-> (jdbc/execute-one!
                                conn ["SELECT v FROM meta WHERE k = 'module-tiers'"])
                               :meta/v edn/read-string) {})}))
^:reads (defn get-meta
  "Read a meta row's value (nil when absent) — the k/v side-table for
  config the journal doesn't track (e.g. `git-remote`, `git-base-sha`)."
  [conn k]
  (:meta/v (jdbc/execute-one! conn ["SELECT v FROM meta WHERE k = ?" k])))
^:reads (defn meta-with-prefix
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

(defn set-meta!
  "Upsert a meta row — the write side of `get-meta`."
  [conn k v]
  (jdbc/execute! conn ["INSERT INTO meta (k,v) VALUES (?,?)
                        ON CONFLICT(k) DO UPDATE SET v = excluded.v" k (str v)])
  nil)
(defn quarantine-put!
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
^:reads (defn quarantine-list
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
(defn quarantine-clear!
  "Resolve one conflict (`path`) — or ALL of them when path is nil."
  [conn path]
  (if path
    (jdbc/execute! conn ["DELETE FROM quarantine WHERE path = ?" path])
    (jdbc/execute! conn ["DELETE FROM quarantine"]))
  nil)
