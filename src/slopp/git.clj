(ns slopp.git
  "P4-m8: the git compatibility layer. Projects the journal's :commit
  milestones into a bare repo at `<dir>/.slopp/git` — one git commit per
  commit point, chained in journal order, refs/heads/<branch> per line.

  Ids: a git commit id IS the hash of its bytes (clients re-hash everything;
  ids cannot be minted), so stability comes from DETERMINISM — each native
  commit is a pure function of its marker delta (:tree snapshot, :agent,
  :at, :description) and its parent. `git_map` (main store.db) additionally
  PINS delta→sha at first projection; imported commits (:git-sha markers)
  keep their pushed identity verbatim and are never re-projected.

  Ordering invariant (crash-safe, no coordination): journal marker → git
  objects (content-addressed, idempotent) → git_map row (INSERT OR IGNORE +
  read-back) → ref update (CAS). Every step is derivable from the previous,
  so `ensure-projected!` repairs any interruption on the next call.

  Durability: native milestones re-derive from the journal (the bare repo is
  a cache) — but once anything is PUSHED in, the pushed objects live only in
  the bare repo, which is then durable state."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [slopp.api :as api]
            [slopp.build :as build]
            [slopp.db :as db]
            [slopp.render :as render]
            [slopp.store :as store])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.time Instant ZoneOffset]
           [java.util.zip GZIPInputStream]
           [org.eclipse.jgit.dircache DirCache DirCacheEntry]
           [org.eclipse.jgit.lib CommitBuilder Constants FileMode ObjectId
            ObjectInserter PersonIdent Repository]
           [org.eclipse.jgit.revwalk RevCommit RevSort RevWalk]
           [org.eclipse.jgit.storage.file FileRepositoryBuilder]
           [org.eclipse.jgit.transport PacketLineOut PreReceiveHook
            ReceiveCommand ReceiveCommand$Result ReceivePack
            RefAdvertiser$PacketLineOutRefAdvertiser UploadPack]
           [org.eclipse.jgit.treewalk TreeWalk]
           [org.eclipse.jgit.treewalk.filter TreeFilter]))

;; ---------------------------------------------------------------------------
;; repo + mapping table

(defn open-repo!
  "Open (creating if needed) the bare projection repo at `<dir>/.slopp/git`,
  HEAD linked to refs/heads/main."
  ^Repository [dir]
  (let [git-dir  (io/file dir ".slopp" "git")
        existed? (.exists (io/file git-dir "HEAD"))
        repo     (-> (FileRepositoryBuilder.)
                     (.setGitDir git-dir)
                     (.setBare)
                     (.build))]
    (when-not existed?
      (.create repo true)
      (-> repo (.updateRef Constants/HEAD) (.link "refs/heads/main")))
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
  milestone parent, rejected on push."
  [{:keys [^Repository repo]} line-name conn deltas tip-sha]
  (let [ref-name (str "refs/heads/wip/" line-name)]
    (if (nil? tip-sha)
      (delete-ref! repo ref-name)          ; no milestone = no baseline
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
                  since (- (count deltas) (inc mpos))
                  desc  (:description (nth deltas mpos))
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
  "Walk one journal's deltas in order, minting a git commit for every
  :commit marker not yet pinned in git_map. Parent = the previous marker's
  sha — journal order IS the chain (a retroactive :target marker therefore
  lands as the NEWEST commit, carrying the older tree; the journal is the
  truth, git mirrors it). Markers carrying :git-sha (imports) only repair
  their mapping row — never re-projected. Returns the tip sha or nil."
  [{:keys [repo map-conn]} line-label deltas]
  (reduce
   (fn [parent d]
     (if (= :commit (:op d))
       (let [fp (fingerprint d)]
         (or (lookup-sha map-conn (:id d) fp)
             (if-let [gs (:git-sha d)]
               (record-sha! map-conn (:id d) fp gs line-label)
               (let [tree (or (:tree d) (backfill-tree deltas (:target d)))
                     sha  (insert-commit! repo parent d tree)]
                 (record-sha! map-conn (:id d) fp sha line-label)))))
       parent))
   nil
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
  branch — advancing refs/heads/* to each line's newest milestone. Reads the
  dbs directly (always-current, no session needed), deterministic and
  idempotent: safe to call before every refs advertisement.
  Returns {:refs {name sha-or-nil}}."
  [{:keys [dir repo map-conn lock] :as ctx}]
  (locking lock
    (let [main-ds  (db/deltas-after map-conn 0)
          main-tip (project-journal! ctx "main" main-ds)
          _        (ensure-wip! ctx "main" map-conn main-ds main-tip)
          refs     (into {"main" main-tip}
                         (map (fn [[nm bdir]]
                                [nm (with-open [conn (db/open! bdir)]
                                      (let [ds  (db/deltas-after conn 0)
                                            tip (project-journal! ctx nm ds)]
                                        (ensure-wip! ctx nm conn ds tip)
                                        tip))]))
                         (branch-journals dir))]
      (doseq [[nm sha] refs :when sha]
        (set-branch-ref! repo nm sha))
      {:refs refs})))

;; ---------------------------------------------------------------------------
;; import: git push → slopp (M3)
;;
;; The net content change lands as ingests (new files) + ONE verified edit
;; group; each incoming commit is preserved as a :commit marker carrying its
;; original sha. Conservative by design — git is a guest writer, and guests
;; don't get the ambiguous cases (anonymous forms, ns-decl edits, deletions
;; of whole files): those reject with the reason on the pusher's terminal.

(defn- path->ns
  "src/gi/core.clj → gi.core (test/gi/core_test.clj → gi.core-test); nil when the
  path isn't importable source."
  [path]
  (when-let [[_ p] (re-matches #"(?:src|test)/(.+)\.clj" (str path))]
    (symbol (-> p (str/replace "/" ".") (str/replace "_" "-")))))

(defn- blob-str [^Repository repo oid]
  (String. (.getBytes (.open repo ^ObjectId oid)) StandardCharsets/UTF_8))

(defn- tree-changes
  "Net old→new file changes: [{:path :old <oid|nil> :new <oid|nil>}]."
  [^Repository repo ^RevCommit oc ^RevCommit nc]
  (let [tw   (doto (TreeWalk. repo) (.setRecursive true))
        zero (ObjectId/zeroId)]
    (.addTree tw (.getTree oc))
    (.addTree tw (.getTree nc))
    (.setFilter tw TreeFilter/ANY_DIFF)
    (loop [out []]
      (if (.next tw)
        (let [o  (.getObjectId tw 0)
              n' (.getObjectId tw 1)]
          (recur (conj out {:path (.getPathString tw)
                            :old  (when-not (= zero o) o)
                            :new  (when-not (= zero n') n')})))
        out))))

(defn- source-forms
  "Top-level sexpr-able forms of file text: [{:name sym|nil :source str}]."
  [source]
  (into []
        (comp (filter n/sexpr-able?)
              (map (fn [node] {:name   (store/form-symbol node)
                               :source (n/string node)})))
        (n/children (p/parse-string-all source))))

(defn- file-steps
  "Diff one MODIFIED file into edit-group steps against the current store.
  Returns {:steps [...]} or {:error msg}."
  [st ns-sym old-src new-src]
  (let [olds      (source-forms old-src)
        news      (source-forms new-src)
        named-old (filter :name olds)
        named-new (filter :name news)
        old-m     (into {} (map (juxt :name :source)) named-old)
        new-m     (into {} (map (juxt :name :source)) named-new)]
    (cond
      (not= (mapv :source (remove :name olds))
            (mapv :source (remove :name news)))
      {:error (str ns-sym ": anonymous top-level forms can't come in over"
                   " git — name them, or use slopp's edit tools")}

      (or (not= (count named-old) (count old-m))
          (not= (count named-new) (count new-m)))
      {:error (str ns-sym ": duplicate form names in the pushed file")}

      (not= (get old-m ns-sym) (get new-m ns-sym))
      {:error (str ns-sym ": the ns declaration can't change over git"
                   " (requires are structural — use slopp's ns tools)")}

      :else
      (reduce
       (fn [acc nm]
         (let [o     (get old-m nm)
               n'    (get new-m nm)
               cur   (some-> (store/form-named st ns-sym nm) :node n/string)
               stale (reduced
                      {:error (str ns-sym "/" nm " moved in slopp since this"
                                   " push's base — fetch first")})]
           (cond
             (= o n')    acc                  ; untouched by the push
             (= cur n')  acc                  ; converged already (re-push)
             (nil? n')   (cond (nil? cur) acc ; already gone
                               (= cur o)  (update acc :steps conj
                                                  {:action :delete
                                                   :ns ns-sym :name nm})
                               :else      stale)
             (nil? o)    (if (nil? cur)
                           (update acc :steps conj
                                   {:action :add :ns ns-sym
                                    :name nm :source n'})
                           stale)
             :else       (if (= cur o)
                           (update acc :steps conj
                                   {:action :replace :ns ns-sym
                                    :name nm :source n'})
                           stale))))
       {:steps []}
       (distinct (concat (keys old-m) (keys new-m)))))))

(defn- declared-requires
  "{:ns sym :requires #{sym}} from file text's ns declaration, or nil."
  [source]
  (when-let [form (->> (n/children (p/parse-string-all source))
                       (filter n/sexpr-able?)
                       (map n/sexpr)
                       (filter #(and (seq? %) (= 'ns (first %))))
                       first)]
    {:ns       (second form)
     :requires (set (for [clause (filter sequential? form)
                          :when  (= :require (first clause))
                          spec   (rest clause)]
                      (if (sequential? spec) (first spec) spec)))}))

(defn- topo-new-files
  "Order [{:ns :requires ...}] so required nses ingest first; nil on cycle."
  [files]
  (let [by-ns (into {} (map (juxt :ns identity)) files)
        deps  (update-vals by-ns :requires)]
    (loop [pending (set (keys by-ns)), out []]
      (if (empty? pending)
        out
        (let [ready (filter #(empty? (set/intersection (deps %) pending))
                            pending)]
          (if (empty? ready)
            nil
            (recur (reduce disj pending ready)
                   (into out (map by-ns) (sort ready)))))))))

^:reads (defn- sha-imported? [conn sha]
  (some? (jdbc/execute-one!
          conn ["SELECT 1 AS one FROM git_map WHERE sha = ?" sha])))

(defn- validate-changes [changes]
  (some (fn [{:keys [path new]}]
          (cond
            (nil? (path->ns path))
            {:error (str path ": only src/**.clj or test/**.clj can change over git")}
            (nil? new)
            {:error (str path ": file deletion over git is not supported"
                         " — delete forms through slopp instead")}
            :else nil))
        changes))

(defn- record-markers!
  "One :commit marker per incoming commit (parent-first), each pinned to its
  ORIGINAL pushed sha. `head` anchors every marker's :target — the single
  group is the content truth; intermediate states live on the git side."
  [{:keys [map-conn]} session branch incoming head]
  (doseq [^RevCommit c incoming]
    (let [ident (.getAuthorIdent c)
          sha   (.name c)
          msg   (let [m (str/trim (.getFullMessage c))]
                  (if (str/blank? m) (str "git commit " (subs sha 0 8)) m))
          r     (api/commit-point! session msg
                                   :agent (str "git:" (.getEmailAddress ident))
                                   :target head
                                   :extra {:git-sha sha
                                           :git-author
                                           (str (.getName ident) " <"
                                                (.getEmailAddress ident) ">")})]
      (when-let [cid (:commit r)]
        (let [d (first (filter #(= cid (:id %))
                               (store/deltas (:store @session))))]
          (record-sha! map-conn cid (fingerprint d) sha branch)))))
  {:ok true})

(defn- import-push!
  "One pushed ref update → slopp. Validate the whole span, land new files as
  ingests (dependency order) + everything else as ONE edit group (compile
  gate rejects the push; red tests land, recorded honestly), then preserve
  each incoming commit as a marker. Returns {:ok true} | {:error msg}."
  [{:keys [repo map-conn] :as ctx} session ^ReceiveCommand cmd]
  (let [ref-name (.getRefName cmd)
        branch   (when (str/starts-with? ref-name "refs/heads/")
                   (subs ref-name 11))]
    (if (nil? branch)
      {:error "only refs/heads/* can be pushed"}
      (do
        (api/sync-with-journal! session)
        (if (and (not= branch (:branch @session))
                 (:error (api/branch-switch! session branch)))
          {:error (str "no slopp branch " branch
                       " (branch creation over git isn't supported)")}
          (with-open [rw (RevWalk. ^Repository repo)]
            (let [oc (.parseCommit rw (.getOldId cmd))
                  nc (.parseCommit rw (.getNewId cmd))]
              (.markStart rw nc)
              (.markUninteresting rw oc)
              (.sort rw RevSort/TOPO true)
              (.sort rw RevSort/REVERSE true)
              (let [incoming (into []
                                   (remove #(sha-imported?
                                             map-conn (.name ^RevCommit %)))
                                   (iterator-seq (.iterator rw)))
                    changes  (tree-changes repo oc nc)]
                (or (validate-changes changes)
                    (let [news   (filter #(nil? (:old %)) changes)
                          mods   (filter #(and (:old %) (:new %)) changes)
                          parsed (mapv (fn [{:keys [path new]}]
                                         (let [src (blob-str repo new)]
                                           (assoc (declared-requires src)
                                                  :path path :source src)))
                                       news)
                          st     (:store @session)
                          diffs  (mapv (fn [{:keys [path old new]}]
                                         (file-steps st (path->ns path)
                                                     (blob-str repo old)
                                                     (blob-str repo new)))
                                       mods)]
                      (or (some (fn [{:keys [path ns]}]
                                  (when (not= ns (path->ns path))
                                    {:error (str path ": declares ns " ns
                                                 " — path and namespace"
                                                 " must agree")}))
                                parsed)
                          (first (filter :error diffs))
                          (let [steps   (into [] (mapcat :steps) diffs)
                                ordered (topo-new-files parsed)
                                agent   (str "git:" (.getEmailAddress
                                                     (.getAuthorIdent nc)))
                                prompt  (str "git push " (subs (.name nc) 0 8)
                                             ": " (first (str/split-lines
                                                          (.getFullMessage nc))))]
                            (if (and (seq parsed) (nil? ordered))
                              {:error "pushed new namespaces form a require cycle"}
                              (or (first (keep (fn [{:keys [ns source]}]
                                                 (let [r (api/ingest!
                                                          session ns source
                                                          :agent agent)]
                                                   (when (:error r)
                                                     {:error (str "ingest " ns
                                                                  " failed: "
                                                                  (:error r))})))
                                               ordered))
                                  (when (seq steps)
                                    (let [r (api/edit-group! session steps
                                                             :prompt prompt
                                                             :agent agent)]
                                      (when (:error r)
                                        {:error (str "rejected by the compile"
                                                     " gate: " (:error r))})))
                                  (record-markers!
                                   ctx session branch incoming
                                   (:id (last (store/deltas
                                               (:store @session)))))))))))))))))))

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
    (if-not (contains? #{"git-upload-pack" "git-receive-pack"} service)
      (status! ex 403)                       ; no dumb protocol
      (do (ensure-projected! ctx)
          (doto (.getResponseHeaders ex)
            (.add "Content-Type"
                  (str "application/x-" service "-advertisement"))
            (.add "Cache-Control" "no-cache"))
          (.sendResponseHeaders ex 200 0)
          (with-open [os (.getResponseBody ex)]
            (let [pck (PacketLineOut. os)
                  adv (RefAdvertiser$PacketLineOutRefAdvertiser. pck)]
              (.writeString pck (str "# service=" service "\n"))
              (.end pck)
              (if (= "git-upload-pack" service)
                (-> (doto (UploadPack. ^Repository (:repo ctx))
                      (.setBiDirectionalPipe false))
                    (.sendAdvertisedRefs adv))
                (-> (doto (ReceivePack. ^Repository (:repo ctx))
                      (.setBiDirectionalPipe false))
                    (.sendAdvertisedRefs adv)))))))))

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

(defn- import-hook
  "PreReceiveHook: every pushed ref update runs the import pipeline; a
  rejection's reason lands on the pusher's terminal. Commands JGit already
  refused (creates/deletes/non-FF) are skipped."
  ^PreReceiveHook [srv]
  (reify PreReceiveHook
    (onPreReceive [_ _rp commands]
      (locking (:lock (:ctx srv))
        (doseq [^ReceiveCommand cmd commands]
          (when (= (.getResult cmd) ReceiveCommand$Result/NOT_ATTEMPTED)
            (if (str/starts-with? (.getRefName cmd) "refs/heads/wip/")
              ;; checked BEFORE forcing :session — no image boot to say no
              (.setResult cmd ReceiveCommand$Result/REJECTED_OTHER_REASON
                          "wip refs are read-only projections of un-milestone'd state")
              (let [r (try (import-push! (:ctx srv) (force (:session srv)) cmd)
                           (catch Throwable t
                             {:error (str "import failed: " (.getMessage t))}))]
                (when (:error r)
                  (.setResult cmd ReceiveCommand$Result/REJECTED_OTHER_REASON
                              (str (:error r))))))))))))

(defn- receive-pack! [srv ^HttpExchange ex]
  (let [ctx (:ctx srv)]
    (ensure-projected! ctx)   ; fresh refs before JGit validates old-ids
    (doto (.getResponseHeaders ex)
      (.add "Content-Type" "application/x-git-receive-pack-result")
      (.add "Cache-Control" "no-cache"))
    (.sendResponseHeaders ex 200 0)
    (with-open [in (request-body ex)
                os (.getResponseBody ex)]
      (doto (ReceivePack. ^Repository (:repo ctx))
        (.setBiDirectionalPipe false)
        (.setAllowNonFastForwards false)
        (.setAllowCreates false)
        (.setAllowDeletes false)
        (.setPreReceiveHook (import-hook srv))
        (.receive in os nil)))))

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

            (and (= "POST" method) (str/ends-with? path "/git-receive-pack"))
            (receive-pack! srv ex)

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
  "Serve the git smart-HTTP protocol for the store at `:dir` on 127.0.0.1
  (localhost-only, like every slopp transport). Clone with
  `git clone http://127.0.0.1:<port>/slopp.git`; pushes import through the
  full verified write pipeline. The api session (image included) boots
  lazily on the first push — clone/fetch-only servers never pay for it.
  The requested `port` is a PREFERENCE — if it's taken, an ephemeral port
  is bound instead; the actual bound port is returned as `:port`.
  Returns {:server :ctx :session :port :url} for stop-server!."
  [port {:keys [dir]}]
  (when (str/blank? (str dir))
    (throw (ex-info "the git server needs a durable store :dir" {})))
  (let [ctx    (open-ctx! dir)
        server (bind-localhost! port)
        actual (.getPort (.getAddress server))
        srv    {:ctx     ctx
                :server  server
                :session (delay (api/open! {:dir (str dir)}))
                :port    actual
                :url     (str "http://127.0.0.1:" actual "/slopp.git")}]
    (.createContext server "/slopp.git" (git-handler srv))
    (.start server)
    srv))

(defn stop-server! [{:keys [^HttpServer server ctx session]}]
  (.stop server 0)
  (when (and session (realized? session))
    (api/close! (force session)))
  (close-ctx! ctx)
  nil)

(defn -main [& [port dir]]
  (let [dir  (or dir (System/getProperty "user.dir"))
        port (if port (Long/parseLong port) (derived-port dir))
        srv  (start-server! port {:dir dir})]
    (println (str "slopp git server: " (:url srv) "  (store: " dir ")"))
    @(promise)))
