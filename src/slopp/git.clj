(ns slopp.git
  "P4-m8: the git compatibility layer. Two faces over one in-memory JGit
  repo (`InMemoryRepository` — there is NO on-disk git repo; `store.db` is
  the source of truth and the git repo a rebuildable cache):

  - PROJECTION: the journal's :commit milestones generated as git objects.
    Serving these to a git client over local smart-HTTP was removed — it
    forced exact-project handling for less than it bought — so the
    projection now exists to be PUSHED rather than browsed in place.
  - CLIENT: the same projection pushed to a NORMAL external remote (GitHub
    etc.) — the remote holds real .clj files; fetch reads a remote's tip and
    tree back (the clone/pull side lives in `slopp.sync`). A cloned store
    records `git-base-sha`, and the projection GRAFTS onto it so local
    milestones extend the remote's history — pushes stay fast-forward.

  Ids: a git commit id IS the hash of its bytes, so stability comes from
  DETERMINISM — each commit is a pure function of its marker delta (:agent,
  :at, :description), its parent, and the tree DERIVED by folding the journal
  up to that marker. `git_map` (main store.db)
  pins delta→sha at first projection: query surfaces read it, and it lets
  re-projection skip a commit whose object is already live in the repo.

  Ordering: journal marker → git objects (content-addressed, idempotent) →
  git_map row (INSERT OR IGNORE + read-back) → ref update (CAS);
  `ensure-projected!` rebuilds the whole thing from the journal on demand."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [slopp.build :as build]
            [slopp.store.db :as db]
            [slopp.store.render :as store.render] [slopp.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant ZoneOffset]
           [org.eclipse.jgit.dircache DirCache DirCacheEntry]
           [org.eclipse.jgit.internal.storage.dfs DfsRepositoryDescription
            InMemoryRepository$Builder]
           [org.eclipse.jgit.lib CommitBuilder Constants FileMode
            ObjectId ObjectInserter PersonIdent Repository]
           [org.eclipse.jgit.revwalk RevWalk]
           [org.eclipse.jgit.revwalk.filter RevFilter]
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

(defn ^:live-handle open-ctx!
  "The projection context over a slopp store dir: bare repo handle, git_map
  connection (main store.db), and the per-process projection lock."
  [dir]
  {:slopp.git/dir      (str dir)
   :slopp.git/repo     (open-repo! dir)
   :slopp.git/map-conn (ensure-map! (db/open! dir))
   :slopp.git/lock     (Object.)})

(defn close-ctx!
  "Close a git context's in-memory JGit repo and its git_map connection, and
  return nil. The repo is a rebuildable CACHE of the journal's milestones — the
  store is the source of truth — so closing one loses nothing;
  `ensure-projected!` rebuilds it on demand.

  `ctx` is an OPAQUE handle from `open-ctx!`: it carries a live JGit
  `Repository`, a JDBC `Connection` and a lock, so no caller builds one and no
  schema can usefully describe one. Destructuring it in the arglist would
  advertise a shape callers must not depend on — the same shape-divergence
  that broke live REPL handles."
  [ctx]
  (let [^Repository repo             (:slopp.git/repo ctx)
        ^java.sql.Connection map-conn (:slopp.git/map-conn ctx)]
    (.close repo)
    (.close map-conn)
    nil))

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
  "{path content} for a milestone's tree: the rendered namespaces at the paths
  the CALLER resolved (`render/source-path` over the store as it stood — so
  production under `src/`, tests under `test/`, instruments under
  `instruments/`, cljs under `cljs-src/`, same layout as build!), the
  generated deps.edn, every non-code file from the `files` manifest — BINARY
  entries ({:sha …}) resolved to real bytes via `blob-of` (sha → bytes; a
  missing blob projects its entry EDN, visible rather than silent) — and every
  structured CONFIG entry rendered to its format (they all ride EVERY projected
  tree, so a slopp push never deletes them).

  The layout matching build! is load-bearing rather than tidy: build.clj's CI
  flow is `clojure -T:build uber :src src` against a CHECKOUT of the published
  repo, so a projection that roots a namespace differently produces a different
  jar from the same store.

  deps.edn's `test?` and `instruments?` are read off the TREE, not off the
  store, so the file cannot describe a layout other than the one it ships
  beside."
  [path-map deps files configs blob-of]
  (let [under? (fn [& prefixes]
                 (boolean (some (fn [p] (some #(str/starts-with? p %) prefixes))
                                (keys path-map))))]
    (into (sorted-map)
          (concat [["deps.edn" (build/deps-edn false deps
                                               (under? "test/" "cljs-test/")
                                               false {}
                                               (under? "instruments/"))]]
                  path-map
                  (map (fn [[p entry]]
                         [p (if (map? entry)
                              (or (blob-of (:sha entry)) (pr-str entry))
                              entry)])
                       files)
                  (map (fn [[p entry]] [p (store/render-config entry)]) configs)))))

;; ---------------------------------------------------------------------------
;; commits + refs
(defn- author-email ^String [agent]
  (let [s (str/replace (str agent) #"[^A-Za-z0-9._-]" ".")]
    (str (if (str/blank? s) "slopp" s) "@slopp")))

(defn- commit-message [d]
  (str (:description d)
       "\n\nSlopp-Commit: " (:id d) "\n"
       (when (and (:author d) (:agent d))
         ;; G5: the author field is the configured human; keep the agent
         ;; visible (new-style markers only — old messages must not change)
         (str "Slopp-Agent: " (:agent d) "\n"))
       (when (= :red (:status d)) "Slopp-Status: red\n")))

(defn- insert-tree!
  "Blobs + git tree for a {path content} map (content: string or BYTES —
  binary assets project as real bytes); returns the tree ObjectId."
  [^ObjectInserter ins paths]
  (let [dc (DirCache/newInCore)
        b  (.builder dc)]
    (doseq [[^String path content] paths]
      (let [^bytes bs (if (bytes? content)
                        content
                        (.getBytes ^String content StandardCharsets/UTF_8))
            blob (.insert ins Constants/OBJ_BLOB bs)]
        (.add b (doto (DirCacheEntry. path)
                  (.setFileMode FileMode/REGULAR_FILE)
                  (.setObjectId blob)))))
    (.finish b)
    (.writeTree dc ins)))

(defn commit-author
  "The projected commit's author identity for marker `d`: the `:author`
  captured at milestone time ({:name :email} — G5 config), else the legacy
  agent-based identity, so pre-G5 markers re-mint byte-identically."
  [d]
  (or (:author d)
      {:name  (str (or (:agent d) "slopp"))
       :email (author-email (:agent d))}))

(defn- insert-commit!
  "Build blobs + tree + commit for marker `d` and return the sha. Pure
  function of (parent-sha, d, tree-map) — determinism is what makes the
  projection rebuildable (which is why the author identity, the files
  manifest, and the structured config live ON the marker, never in ambient
  state)."
  [^Repository repo parent-sha d tree-map blob-of]
  (with-open [ins (.newObjectInserter repo)]
    (let [tree-id (insert-tree! ins (commit-paths tree-map (:deps d) (:files d) (:config d) blob-of))
          at      (Instant/ofEpochMilli (long (:at d)))
          who     (commit-author d)
            ;; reflection-free ctors matter: reflective JGit calls resolve
            ;; classes per-thread and break on server dispatch threads
          cb      (doto (CommitBuilder.)
                    (.setTreeId tree-id)
                    (.setAuthor (PersonIdent. ^String (:name who) ^String (:email who)
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
  no markers) or nil.

  **Each milestone's tree is DERIVED, not stored.** The store is folded from
  the journal as this walk proceeds, so reaching a marker means holding the
  store as it stood there, and the tree is `render-ns` over it. Milestones
  used to carry a byte-exact snapshot of every namespace instead — 82 MB
  across 272 of them here, 39% of the journal — because comments lived
  positionally and could not be reconstructed. They are form-owned content
  now, so the log is a complete account and the snapshot has no job.

  ONE pass matters: folding from empty per marker is quadratic in the journal.

  A marker normally targets the delta immediately before it, which is exactly
  where the fold stands when the walk reaches it. `commit_point {:target ...}`
  can mark an EARLIER spot, so those positions are rendered as the walk passes
  them and held until their marker arrives — the only trees kept in memory.

  A delta that will not replay (a retired `:trivia`) is SKIPPED rather than
  fatal: it edited `:sep` elements the renderer no longer reads, so the state
  it would rebuild is state nothing consults.

  `ctx` is an OPAQUE handle from `open-ctx!` — see `close-ctx!`."
  [ctx line-label deltas & {:keys [base]}]
  (let [map-conn         (:slopp.git/map-conn ctx)
        ^Repository repo (:slopp.git/repo ctx)
        dv       (vec deltas)
        retro    (into #{}
                       (keep (fn [i]
                               (let [d (nth dv i)]
                                 (when (and (= :commit (:op d))
                                            (:target d)
                                            (not= (:target d)
                                                  (:id (get dv (dec i)))))
                                   (:target d)))))
                       (range (count dv)))
        ;; PATHS, not namespace names. The fold holds the store as it stood at
        ;; this milestone, which is the only point where a namespace's platform
        ;; and role are both known — and the projection has to root them the
        ;; way build! does, because CI jars a checkout of this tree.
        tree-of  (fn [st]
                   (into (sorted-map)
                         (map (fn [n] [(store.render/source-path n
                                                           (store/platform-for st n)
                                                           (store/role-for st n))
                                       (store.render/render-ns st n)]))
                         (keys (:namespaces st))))]
    (:parent
     (reduce
      (fn [{:keys [parent store held]} d]
        (let [store' (or (store/replay-delta store d) store)
              held'  (cond-> held
                       (retro (:id d)) (assoc (:id d) (tree-of store')))]
          (if-not (= :commit (:op d))
            {:parent parent :store store' :held held'}
            (let [sha (if-let [gsha (:git-sha d)]
                        (do (record-sha! map-conn (:id d) (fingerprint d)
                                         gsha line-label)
                            gsha)
                        (let [fp     (fingerprint d)
                              pinned (lookup-sha map-conn (:id d) fp)]
                          (if (and pinned
                                   (.has (.getObjectDatabase repo)
                                         (ObjectId/fromString pinned)))
                            pinned
                            (let [tree (or (get held' (:target d)) (tree-of store'))
                                  s    (insert-commit! repo parent d tree
                                                       #(db/get-blob map-conn %))]
                              (record-sha! map-conn (:id d) fp s line-label)
                              s))))]
              ;; NOT dissoc'd: two markers can name the same target — a milestone's
              ;; own target is the delta before it, which is exactly what an
              ;; earlier retroactive marker also points at. Releasing it at the
              ;; first reader left the second rendering the CURRENT state.
              {:parent sha :store store' :held held'}))))
      {:parent base :store (store/empty-store) :held {}}
      dv))))

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
  advertisement. Returns {:refs {name sha-or-nil}}.

  `ctx` is an OPAQUE handle from `open-ctx!` — see `close-ctx!`."
  [ctx]
  (let [dir              (:slopp.git/dir ctx)
        map-conn         (:slopp.git/map-conn ctx)
        ^Repository repo (:slopp.git/repo ctx)]
    (locking (:slopp.git/lock ctx)
      (let [base     (db/get-meta map-conn "git-base-sha")
            main-ds  (db/deltas-after map-conn 0)
            need     (cond-> (into [] (keep :git-sha) main-ds) base (conj base))
            missing? (fn [sha] (not (.has (.getObjectDatabase repo)
                                          (ObjectId/fromString sha))))]
        (when (some missing? need)
          (when-let [url (db/get-meta map-conn "git-remote")]
            ;; late-bound: git.client requires THIS ns (push → ensure-projected!),
            ;; so a static require back would cycle — the carrier makes the
            ;; reference visible to renames/moves/the unused gate
            (try ((store/late-ref 'slopp.git.client/fetch-remote!) repo url)
                 (catch Exception _ nil))))
        (let [main-tip (project-journal! ctx "main" main-ds :base base)
              refs     (into {"main" main-tip}
                             (map (fn [[nm bdir]]
                                    [nm (with-open [conn (db/open! bdir)]
                                          (project-journal! ctx nm (db/deltas-after conn 0)
                                                            :base base))]))
                             (branch-journals dir))]
          (doseq [[nm sha] refs :when sha]
            (set-branch-ref! repo nm sha))
          {:refs refs})))))

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
