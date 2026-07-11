(ns slopp.git
  "P4-m8: the git compatibility layer. Two faces over one in-memory JGit
  repo (`InMemoryRepository` — there is NO on-disk git repo; `store.db` is
  the source of truth and the git repo a rebuildable cache):

  - SERVER: a generated projection of the journal's :commit milestones,
    served READ-ONLY (clone/fetch) over local smart-HTTP. Edits arrive
    through slopp's write tools, never `git push` to this server.
  - CLIENT: the same projection pushed to a NORMAL external remote (GitHub
    etc.) — the remote holds real .clj files; fetch reads a remote's tip and
    tree back (the clone/pull side lives in `slopp.sync`). A cloned store
    records `git-base-sha`, and the projection GRAFTS onto it so local
    milestones extend the remote's history — pushes stay fast-forward.

  Ids: a git commit id IS the hash of its bytes, so stability comes from
  DETERMINISM — each commit is a pure function of its marker delta (:tree
  snapshot, :agent, :at, :description) and its parent. `git_map` (main store.db)
  pins delta→sha at first projection: query surfaces read it, and it lets
  re-projection skip a commit whose object is already live in the repo.

  Ordering: journal marker → git objects (content-addressed, idempotent) →
  git_map row (INSERT OR IGNORE + read-back) → ref update (CAS);
  `ensure-projected!` rebuilds the whole thing from the journal on demand."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [slopp.build :as build]
            [slopp.db :as db]
            [slopp.render :as render])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.time Instant ZoneOffset]
           [java.util.zip GZIPInputStream]
           [org.eclipse.jgit.dircache DirCache DirCacheEntry]
           [org.eclipse.jgit.internal.storage.dfs DfsRepositoryDescription
            InMemoryRepository InMemoryRepository$Builder]
           [org.eclipse.jgit.lib CommitBuilder Constants FileMode NullProgressMonitor
            ObjectId ObjectInserter PersonIdent Repository]
           [org.eclipse.jgit.revwalk RevWalk]
           [org.eclipse.jgit.revwalk.filter RevFilter]
           [org.eclipse.jgit.transport PacketLineOut PushResult
            RefAdvertiser$PacketLineOutRefAdvertiser RefSpec RemoteRefUpdate
            Transport UploadPack URIish UsernamePasswordCredentialsProvider]
           [org.eclipse.jgit.treewalk TreeWalk]
           [org.eclipse.jgit.util FS]))

;; ---------------------------------------------------------------------------
;; repo + mapping table

(defn open-repo!
  "An in-memory bare repo (JGit DFS `InMemoryRepository`) — the projection is
  regenerated into it from the journal on demand; nothing touches disk. Built
  with a real FS handle: `TransportLocal` resolves file-path remotes through
  the LOCAL repo's FS, and a DFS repo has none by default (NPE without it)."
  ^Repository [_dir]
  (let [repo (.. (InMemoryRepository$Builder.)
                 (setRepositoryDescription (DfsRepositoryDescription. "slopp"))
                 (setFS FS/DETECTED)
                 (build))]
    (-> repo (.updateRef Constants/HEAD) (.link "refs/heads/main"))
    repo))

(defn ensure-map!
  "Create the git_map pinning table (delta↔sha) if absent; returns conn.
  Keyed (delta_id, fingerprint): branch journals share main's prefix by
  VALUE, so a shared marker resolves to one row with no fork-point math;
  colliding post-fork ids disambiguate by fingerprint. `line` is informative."
  [conn]
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS git_map (
                          delta_id    TEXT NOT NULL,
                          fingerprint TEXT NOT NULL,
                          sha         TEXT NOT NULL,
                          line        TEXT,
                          PRIMARY KEY (delta_id, fingerprint))"])
  conn)

(defn open-ctx!
  "The projection context over a slopp store dir: bare repo handle, git_map
  connection (main store.db), and the per-process projection lock."
  [dir]
  {:dir      (str dir)
   :repo     (open-repo! dir)
   :map-conn (ensure-map! (db/open! dir))
   :lock     (Object.)})

(defn close-ctx! [{:keys [^Repository repo ^java.sql.Connection map-conn]}]
  (.close repo)
  (.close map-conn)
  nil)

(defn fingerprint
  "Line-independent identity of a :commit marker: SHA-256 of the canonical
  tuple [id at description target] (NOT the whole map — map print order is
  not canonical across EDN round-trips)."
  [d]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (->> (.digest md (.getBytes (pr-str [(:id d) (:at d) (:description d)
                                         (:target d)])
                                StandardCharsets/UTF_8))
         (map #(format "%02x" %))
         (apply str))))

^:reads (defn- lookup-sha [conn delta-id fp]
  (:git_map/sha (jdbc/execute-one!
                 conn ["SELECT sha FROM git_map
                        WHERE delta_id = ? AND fingerprint = ?" delta-id fp])))

(defn- record-sha!
  "Pin delta→sha; first writer wins (determinism makes ties identical for
  native commits — read-back keeps every projector converged regardless)."
  [conn delta-id fp sha line]
  (jdbc/execute! conn ["INSERT OR IGNORE INTO git_map
                          (delta_id, fingerprint, sha, line)
                        VALUES (?,?,?,?)" delta-id fp sha line])
  (lookup-sha conn delta-id fp))

;; ---------------------------------------------------------------------------
;; trees

(defn- commit-paths
  "{path content} for a milestone's tree: every namespace under src/ (test
  namespaces under test/ — same layout as build!) plus the generated deps.edn
  (carrying the store's Tier-1 manifest `deps` and, when the project has tests,
  a :test alias, so a clone is runnable)."
  [tree-map deps]
  (into (sorted-map)
        (cons ["deps.edn" (build/deps-edn false deps
                                          (boolean (some render/test-ns? (keys tree-map))))]
              (map (fn [[ns-sym src]]
                     [(render/source-path ns-sym) src])
                   tree-map))))

(defn- backfill-tree
  "Best-effort {ns-sym source} at delta `target-id`, for markers that carry
  no :tree (pre-P4-m8 history, retroactive :target markers): fold the
  journal's content deltas into per-ns ordered form sources. LOSSY by design
  — deltas don't record inter-form trivia (top-level comments/blank lines) —
  but deterministic; once projected, the sha is pinned in git_map anyway."
  [deltas target-id]
  (let [upto  (reduce (fn [acc d]
                        (let [acc (conj acc d)]
                          (if (= target-id (:id d)) (reduced acc) acc)))
                      [] deltas)
        state (reduce
               (fn [{:keys [owner] :as acc} d]
                 (case (:op d)
                   (:ingest :add :replace :rename :normalize)
                   (let [srcs     (:sources d)
                         new-fids (remove owner
                                          (or (:form-ids d)
                                              (some-> (:form-id d) vector)
                                              (keys srcs)))]
                     (-> acc
                         (update :owner into (map #(vector % (:ns d)) new-fids))
                         (update :order update (:ns d) (fnil into []) new-fids)
                         (update :sources merge srcs)))

                   :delete
                   (update acc :sources dissoc (:form-id d))

                   :rename-ns
                   (let [{:keys [old new]} d]
                     (-> acc
                         (update :owner update-vals #(if (= old %) new %))
                         (update :order
                                 (fn [o] (-> o
                                             (assoc new (get o old))
                                             (dissoc old))))
                         (update :sources merge (:sources d))))

                   ;; markers, :move (ordering is cosmetic here), unknown: skip
                   acc))
               {:owner {} :order {} :sources {}}
               upto)]
    (into (sorted-map)
          (keep (fn [[ns-sym fids]]
                  (let [live (filter #(contains? (:sources state) %)
                                     (distinct fids))]
                    (when (seq live)
                      [ns-sym (apply str (map #(str (get (:sources state) %)
                                                    "\n")
                                              live))]))))
          (:order state))))

;; ---------------------------------------------------------------------------
;; commits + refs

(defn- author-email ^String [agent]
  (let [s (str/replace (str agent) #"[^A-Za-z0-9._-]" ".")]
    (str (if (str/blank? s) "slopp" s) "@slopp")))

(defn- commit-message [d]
  (str (:description d)
       "\n\nSlopp-Commit: " (:id d) "\n"
       (when (= :red (:status d)) "Slopp-Status: red\n")))

(defn- insert-tree!
  "Blobs + git tree for a {path content} map; returns the tree ObjectId."
  [^ObjectInserter ins paths]
  (let [dc (DirCache/newInCore)
        b  (.builder dc)]
    (doseq [[^String path ^String content] paths]
      (let [blob (.insert ins Constants/OBJ_BLOB
                          (.getBytes content StandardCharsets/UTF_8))]
        (.add b (doto (DirCacheEntry. path)
                  (.setFileMode FileMode/REGULAR_FILE)
                  (.setObjectId blob)))))
    (.finish b)
    (.writeTree dc ins)))

(defn- insert-commit!
  "Build blobs + tree + commit for marker `d` and return the sha. Pure
  function of (parent-sha, d, tree-map) — determinism is what makes the
  projection rebuildable."
  [^Repository repo parent-sha d tree-map]
  (with-open [ins (.newObjectInserter repo)]
    (let [tree-id (insert-tree! ins (commit-paths tree-map (:deps d)))
          at      (Instant/ofEpochMilli (long (:at d)))
          who     (str (or (:agent d) "slopp"))
            ;; reflection-free ctors matter: reflective JGit calls resolve
            ;; classes per-thread and break on server dispatch threads
          cb      (doto (CommitBuilder.)
                    (.setTreeId tree-id)
                    (.setAuthor (PersonIdent. who (author-email (:agent d))
                                              at ^java.time.ZoneId ZoneOffset/UTC))
                    (.setCommitter (PersonIdent. "slopp" "slopp@slopp"
                                                 at ^java.time.ZoneId ZoneOffset/UTC))
                    (.setMessage (commit-message d)))]
      (when parent-sha
        (.setParentId cb (ObjectId/fromString parent-sha)))
      (let [cid (.insert ins cb)]
        (.flush ins)
        (.name cid)))))

(defn- set-branch-ref!
  "Point refs/heads/<nm> at `sha` (CAS; the journal is authoritative, so a
  lost race is retried against the moved ref — convergence, not failure)."
  [^Repository repo nm sha]
  (let [ref-name (str "refs/heads/" nm)
        new-id   (ObjectId/fromString sha)]
    (loop [n 0]
      (let [cur (.resolve repo ref-name)]
        (when-not (= cur new-id)
          (let [ru  (doto (.updateRef repo ref-name)
                      (.setExpectedOldObjectId (or cur (ObjectId/zeroId)))
                      (.setNewObjectId new-id)
                      (.setForceUpdate true))
                res (.name (.update ru))]
            (cond
              (#{"NEW" "FORCED" "FAST_FORWARD" "NO_CHANGE"} res) nil
              (and (= "LOCK_FAILURE" res) (< n 3)) (recur (inc n))
              :else (throw (ex-info (str "git ref update failed: " res)
                                    {:ref ref-name :result res})))))))))

(defn- delete-ref! [^Repository repo ref-name]
  (when (.resolve repo ref-name)
    (-> (doto (.updateRef repo ref-name) (.setForceUpdate true))
        (.delete))))

(defn- ensure-wip!
  "refs/heads/wip/<line>: a throwaway commit of the LIVE store state (the
  element rows), minted whenever it differs from the last milestone's tree
  and deleted when clean. 'Uncommitted changes' can't cross the git wire —
  a synthetic ref is the protocol-legal idiom (cf. refs/pull/*, Gerrit's
  refs/changes/*): tools `git diff origin/main..origin/wip/main`.
  Deterministic (tree from the rows, timestamp from the journal head), so
  concurrent projectors converge; never pinned in git_map, never a
  milestone parent, rejected on push. On a cloned store the baseline tip
  may be the graft base itself (no local milestone yet)."
  [{:keys [^Repository repo]} line-name conn deltas tip-sha]
  (let [ref-name (str "refs/heads/wip/" line-name)]
    (if (or (nil? tip-sha) (empty? deltas))
      (delete-ref! repo ref-name)          ; no baseline / nothing local yet
      (with-open [ins (.newObjectInserter repo)]
        (let [tree-id (insert-tree! ins (commit-paths
                                         (db/rendered-sources conn)
                                         (db/deps conn)))
              tip     (ObjectId/fromString tip-sha)
              m-tree  (with-open [rw (RevWalk. repo)]
                        (.getId (.getTree (.parseCommit rw tip))))]
          (if (= tree-id m-tree)
            (delete-ref! repo ref-name)
            (let [mpos  (last (keep-indexed
                               (fn [i d] (when (= :commit (:op d)) i))
                               deltas))
                  since (if mpos (- (count deltas) (inc mpos)) (count deltas))
                  desc  (if mpos (:description (nth deltas mpos)) "the clone base")
                  at    (Instant/ofEpochMilli (long (:at (last deltas))))
                  msg   (if (pos? since)
                          (str "wip: " since " delta"
                               (when (not= 1 since) "s")
                               " since \"" desc "\"\n")
                          (str "wip: live state differs from \"" desc "\"\n"))
                  cb    (doto (CommitBuilder.)
                          (.setTreeId tree-id)
                          (.setParentId tip)
                          (.setAuthor (PersonIdent. "slopp" "wip@slopp" at
                                                    ^java.time.ZoneId ZoneOffset/UTC))
                          (.setCommitter (PersonIdent. "slopp" "wip@slopp" at
                                                       ^java.time.ZoneId ZoneOffset/UTC))
                          (.setMessage msg))
                  cid   (.insert ins cb)]
              (.flush ins)
              (set-branch-ref! repo (str "wip/" line-name) (.name cid)))))))))

;; ---------------------------------------------------------------------------
;; projection

(defn project-journal!
  "Walk one journal's deltas in order, minting a git commit in the in-memory
  repo for every :commit marker whose object isn't already present. Parent =
  the previous marker's sha (journal order IS the chain); `:base` seeds the
  chain — a cloned store grafts its first milestone onto the remote commit it
  was cloned at. A marker carrying `:git-sha` (a pull/import) is ADOPTED, not
  minted: the remote commit itself becomes the chain node (its object arrives
  by fetch; the remote durably holds its own history). A pinned sha is reused
  only when its object is live in this repo; on a fresh repo the object is
  re-inserted deterministically (same sha). Returns the tip sha (= base when
  no markers) or nil."
  [{:keys [repo map-conn]} line-label deltas & {:keys [base]}]
  (reduce
   (fn [parent d]
     (if (= :commit (:op d))
       (if-let [gsha (:git-sha d)]
         (do (record-sha! map-conn (:id d) (fingerprint d) gsha line-label)
             gsha)
         (let [fp     (fingerprint d)
               pinned (lookup-sha map-conn (:id d) fp)]
           (if (and pinned
                    (.has (.getObjectDatabase repo) (ObjectId/fromString pinned)))
             pinned
             (let [tree (or (:tree d) (backfill-tree deltas (:target d)))
                   sha  (insert-commit! repo parent d tree)]
               (record-sha! map-conn (:id d) fp sha line-label)
               sha))))
       parent))
   base
   deltas))

(defn- branch-journals
  "[[name dir]] for every on-disk branch that has a store.db — checked
  BEFORE db/open!, which would otherwise create one."
  [dir]
  (let [root (io/file dir ".slopp" "branches")]
    (when (.isDirectory root)
      (for [^java.io.File f (.listFiles root)
            :when (and (.isDirectory f)
                       (.exists (io/file f ".slopp" "store.db")))]
        [(.getName f) (str f)]))))

(defn ensure-projected!
  "Bring the bare repo up to date with the journals — main + every on-disk
  branch — advancing refs/heads/* to each line's newest milestone. A cloned
  store (`git-base-sha` meta) grafts every line onto that base commit; pull
  markers (`:git-sha`) adopt remote commits as chain nodes. Chain objects
  this in-memory repo doesn't hold (fresh process) are fetched from
  `git-remote` on demand — offline, downstream ref updates throw and the
  caller degrades. Reads the dbs directly (always-current, no session
  needed), deterministic and idempotent: safe to call before every refs
  advertisement. Returns {:refs {name sha-or-nil}}."
  [{:keys [dir repo map-conn lock] :as ctx}]
  (locking lock
    (let [base     (db/get-meta map-conn "git-base-sha")
          main-ds  (db/deltas-after map-conn 0)
          need     (cond-> (into [] (keep :git-sha) main-ds) base (conj base))
          missing? (fn [sha] (not (.has (.getObjectDatabase ^Repository repo)
                                        (ObjectId/fromString sha))))]
      (when (some missing? need)
        ;; requiring-resolve: fetch-remote! is defined later in the ns
        ;; (append-only form order), so late-bind instead of forward-ref
        (when-let [url (db/get-meta map-conn "git-remote")]
          (try ((requiring-resolve 'slopp.git/fetch-remote!) repo url)
               (catch Exception _ nil))))
      (let [main-tip (project-journal! ctx "main" main-ds :base base)
            _        (ensure-wip! ctx "main" map-conn main-ds main-tip)
            refs     (into {"main" main-tip}
                           (map (fn [[nm bdir]]
                                  [nm (with-open [conn (db/open! bdir)]
                                        (let [ds  (db/deltas-after conn 0)
                                              tip (project-journal! ctx nm ds :base base)]
                                          (ensure-wip! ctx nm conn ds tip)
                                          tip))]))
                           (branch-journals dir))]
        (doseq [[nm sha] refs :when sha]
          (set-branch-ref! repo nm sha))
        {:refs refs}))))

;; ---------------------------------------------------------------------------
;; import: git push → slopp (M3)
;;
;; The net content change lands as ingests (new files) + ONE verified edit
;; group; each incoming commit is preserved as a :commit marker carrying its
;; original sha. Conservative by design — git is a guest writer, and guests
;; don't get the ambiguous cases (anonymous forms, ns-decl edits, deletions
;; of whole files): those reject with the reason on the pusher's terminal.

;; ---------------------------------------------------------------------------
;; smart-HTTP server (M2: clone/fetch; M3 adds receive-pack)
;;
;; The protocol endpoints, verbatim from the smart-http spec:
;;   GET  /slopp.git/info/refs?service=git-upload-pack   → refs advertisement
;;   POST /slopp.git/git-upload-pack                     → pack negotiation
;; JGit's UploadPack owns the wire format (setBiDirectionalPipe false =
;; stateless RPC); we only route bytes. v0 protocol — the Git-Protocol:
;; version=2 header is deliberately ignored (spec-legal fallback).

(defn- status! [^HttpExchange ex code]
  (.sendResponseHeaders ex code -1)
  (.close ex))

(defn- q-params [^HttpExchange ex]
  (into {}
        (keep (fn [kv]
                (let [[k v] (str/split kv #"=" 2)]
                  [k (java.net.URLDecoder/decode (str v) "UTF-8")])))
        (some-> (.getQuery (.getRequestURI ex)) (str/split #"&"))))

(defn- request-body ^java.io.InputStream [^HttpExchange ex]
  (cond-> (.getRequestBody ex)
    (= "gzip" (some-> (.getFirst (.getRequestHeaders ex) "Content-Encoding")
                      str/lower-case))
    (GZIPInputStream.)))

(defn- advertise-refs! [ctx ^HttpExchange ex]
  (let [service (get (q-params ex) "service")]
    (if-not (= "git-upload-pack" service)
      (status! ex 403)          ; read-only remote — no receive-pack, no dumb protocol
      (do (ensure-projected! ctx)
          (doto (.getResponseHeaders ex)
            (.add "Content-Type" (str "application/x-" service "-advertisement"))
            (.add "Cache-Control" "no-cache"))
          (.sendResponseHeaders ex 200 0)
          (with-open [os (.getResponseBody ex)]
            (let [pck (PacketLineOut. os)
                  adv (RefAdvertiser$PacketLineOutRefAdvertiser. pck)]
              (.writeString pck (str "# service=" service "\n"))
              (.end pck)
              (-> (doto (UploadPack. ^Repository (:repo ctx))
                    (.setBiDirectionalPipe false))
                  (.sendAdvertisedRefs adv))))))))

(defn- upload-pack! [ctx ^HttpExchange ex]
  (doto (.getResponseHeaders ex)
    (.add "Content-Type" "application/x-git-upload-pack-result")
    (.add "Cache-Control" "no-cache"))
  (.sendResponseHeaders ex 200 0)
  (with-open [in (request-body ex)
              os (.getResponseBody ex)]
    (doto (UploadPack. ^Repository (:repo ctx))
      (.setBiDirectionalPipe false)
      (.upload in os nil))))

(defn- git-handler ^HttpHandler [srv]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (let [path   (.getPath (.getRequestURI ex))
              method (.getRequestMethod ex)]
          (cond
            (and (= "GET" method) (str/ends-with? path "/info/refs"))
            (advertise-refs! (:ctx srv) ex)

            (and (= "POST" method) (str/ends-with? path "/git-upload-pack"))
            (upload-pack! (:ctx srv) ex)

            :else (status! ex 404)))
        (catch Throwable _
          (try (status! ex 500) (catch Throwable _)))
        (finally (.close ex))))))

(defn derived-port
  "A localhost port DERIVED from the store dir — stable across restarts, so
  a `git remote` saved against it keeps working next session. In the
  private range [49152, 65535]; a collision (a second server on the same
  dir) falls back to an ephemeral port at bind time (see `start-server!`),
  so this is a preference, not a guarantee."
  [dir]
  (+ 49152 (mod (hash (str dir)) 16384)))

(defn- bind-localhost!
  "HttpServer on 127.0.0.1:port, falling back to an ephemeral port if that
  one is taken (so a shared derived port never blocks startup)."
  ^HttpServer [port]
  (try
    (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)
    (catch java.net.BindException _
      (when (zero? (int port)) (throw (java.net.BindException. "no port free")))
      (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0))))

(defn start-server!
  "Serve the git smart-HTTP protocol for the store at `:dir` on 127.0.0.1 —
  READ-ONLY (clone/fetch of milestones). Clone with
  `git clone http://127.0.0.1:<port>/slopp.git`. The requested `port` is a
  PREFERENCE — if taken, an ephemeral port is bound; the actual bound port is
  returned as `:port`. Returns {:server :ctx :port :url} for stop-server!."
  [port {:keys [dir]}]
  (when (str/blank? (str dir))
    (throw (ex-info "the git server needs a durable store :dir" {})))
  (let [ctx    (open-ctx! dir)
        server (bind-localhost! port)
        actual (.getPort (.getAddress server))
        srv    {:ctx    ctx
                :server server
                :port   actual
                :url    (str "http://127.0.0.1:" actual "/slopp.git")}]
    (.createContext server "/slopp.git" (git-handler srv))
    (.start server)
    srv))

(defn stop-server! [{:keys [^HttpServer server ctx]}]
  (.stop server 0)
  (close-ctx! ctx)
  nil)

(defn -main [& [port dir]]
  (let [dir  (or dir (System/getProperty "user.dir"))
        port (if port (Long/parseLong port) (derived-port dir))
        srv  (start-server! port {:dir dir})]
    (println (str "slopp git server: " (:url srv) "  (store: " dir ")"))
    @(promise)))
^:reads (defn remote-credentials
  "CredentialsProvider for token auth against an https remote (GitHub PAT /
  app token) — the `token` argument, else SLOPP_GIT_TOKEN / GIT_TOKEN env.
  Nil when no token is set (anonymous / filesystem remotes)."
  [token]
  (when-let [t (or token
                   (System/getenv "SLOPP_GIT_TOKEN")
                   (System/getenv "GIT_TOKEN"))]
    (UsernamePasswordCredentialsProvider. "x-access-token" ^String t)))
(defn fetch-remote!
  "Fetch `url`'s branches into `repo` under refs/remotes/origin/*. Returns
  {:tip sha-or-nil} for `branch`. Used to seed a clone, and to bring a
  remote's objects in before a grafted push. Scheme-less urls are local
  paths — made absolute (an in-memory repo has no dir to resolve against)."
  [^Repository repo url & {:keys [token branch] :or {branch "main"}}]
  (let [s   (str url)
        uri (URIish. ^String (if (re-find #"^[a-z+]+://" s)
                                s
                                (.getAbsolutePath (io/file s))))]
    (with-open [tn (Transport/open repo uri)]
      (when-let [creds (remote-credentials token)]
        (.setCredentialsProvider tn creds))
      (.fetch tn NullProgressMonitor/INSTANCE
              [(RefSpec. "+refs/heads/*:refs/remotes/origin/*")])
      {:tip (some-> (.resolve repo (str "refs/remotes/origin/" branch)) (.name))})))
^:reads (defn tree-at
  "{path text} for the whole tree of commit `sha` — UTF-8 blobs, sorted.
  Works on any repo handle (the in-memory projection or an on-disk remote)."
  [^Repository repo sha]
  (with-open [rw (RevWalk. repo)]
    (let [tree (.getTree (.parseCommit rw (ObjectId/fromString sha)))]
      (with-open [tw (TreeWalk. repo)]
        (.addTree tw tree)
        (.setRecursive tw true)
        (loop [m (sorted-map)]
          (if (.next tw)
            (recur (assoc m (.getPathString tw)
                          (String. (.getBytes (.open repo (.getObjectId tw 0)))
                                   StandardCharsets/UTF_8)))
            m))))))
(defn push-to-remote!
  "Push refs/heads/<branch> from the in-memory projection to an external git
  remote `url` (filesystem path or http(s)). Projects first; a cloned store
  fetches the remote's objects so its grafted chain is complete. Fast-forward
  only — a diverged remote is an honest :error, never a force. Returns
  {:pushed sha :status s} | {:error msg}."
  [{:keys [^Repository repo map-conn] :as ctx} url
   & {:keys [token branch] :or {branch "main"}}]
  (when-let [base (db/get-meta map-conn "git-base-sha")]
    (when-not (.has (.getObjectDatabase repo) (ObjectId/fromString base))
      (fetch-remote! repo url :token token)))
  (ensure-projected! ctx)
  (let [ref-name (str "refs/heads/" branch)
        s        (str url)
        uri      (URIish. ^String (if (re-find #"^[a-z+]+://" s)
                                    s
                                    (.getAbsolutePath (io/file s))))]
    (if-let [tip (.resolve repo ref-name)]
      (with-open [tn (Transport/open repo uri)]
        (when-let [creds (remote-credentials token)]
          (.setCredentialsProvider tn creds))
        (let [rru    (RemoteRefUpdate. repo ref-name ref-name false nil nil)
              ^PushResult res (.push tn NullProgressMonitor/INSTANCE [rru])
              ^RemoteRefUpdate upd (first (.getRemoteUpdates res))
              status (str (.getStatus upd))]
          (if (contains? #{"OK" "UP_TO_DATE"} status)
            {:pushed (.name tip) :status status}
            {:error (str "push rejected (" status ")"
                         (when-let [m (.getMessage upd)] (str ": " m))
                         (when (= status "REJECTED_NONFASTFORWARD")
                           " — the remote has history this store doesn't build on (pull first)"))})))
      {:error (str "nothing to push — no " ref-name
                   " in the projection (no milestones yet?)")})))
^:reads (defn merge-base
  "The merge base of two commits in `repo`, or nil when the histories are
  unrelated — standard git ancestry (pull uses it to isolate remote-only
  changes: diff merge-base→remote-tip, never touching local-only work)."
  [^Repository repo sha-a sha-b]
  (with-open [rw (RevWalk. repo)]
    (.setRevFilter rw RevFilter/MERGE_BASE)
    (.markStart rw (.parseCommit rw (ObjectId/fromString sha-a)))
    (.markStart rw (.parseCommit rw (ObjectId/fromString sha-b)))
    (some-> (.next rw) (.name))))
