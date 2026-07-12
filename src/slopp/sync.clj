(ns slopp.sync
  "Phase-1 git bridge orchestration: `push!` a store's projection to a normal
  git remote (GitHub, a bare repo, …) and `clone!` a remote back into a
  FILELESS store — the working dir gets `.slopp/store.db` and NO `.clj`
  files. The remote holds real files (the interchange artifact); the store
  holds forms (the local representation agents edit). `slopp.git` moves the
  bytes; this namespace owns the store side — which is why it, not slopp.git,
  depends on `slopp.api`.

  A clone records `git-remote` + `git-base-sha` meta, so its projection
  GRAFTS onto the remote's history and later pushes are plain fast-forwards.
  Non-slopp-source paths on the remote (README, CI config, …) are ignored on
  clone; a `.clj` that fails slopp's gates (dialect, compile) fails the clone
  with the reason — the quarantine/conflict flow is the Phase-2 pull."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [slopp.api :as api]
            [slopp.boot :as boot]
            [slopp.db :as db]
            [slopp.git :as git] [rewrite-clj.node :as n] [rewrite-clj.parser :as p] [slopp.store :as store]))

(defn path-ns
  "src/foo/bar_baz.clj → foo.bar-baz; nil for anything that isn't a source
  or test .clj path (those remote files are not slopp's to ingest)."
  [path]
  (when-let [[_ rel] (re-matches #"(?:src|test)/(.+)\.clj" (str path))]
    (symbol (-> rel (str/replace "/" ".") (str/replace "_" "-")))))

(defn push!
  "Push the store at `dir`'s projection to a git remote: `:url` the first
  time (saved as `git-remote` meta), the saved remote thereafter. Refused
  while pull conflicts stand (a push would silently revert the remote work
  they represent). Returns {:pushed sha :status s :remote url} | {:error msg}."
  [dir & {:keys [url token branch]}]
  (let [ctx (git/open-ctx! dir)]
    (try
      (let [conn   (:map-conn ctx)
            target (or url (db/get-meta conn "git-remote"))
            q      (db/quarantine-list conn)]
        (cond
          (str/blank? (str target))
          {:error "no remote configured — pass :url once (it is saved as git-remote)"}

          (seq q)
          {:error (str "unresolved git conflicts (" (count q) ": "
                       (str/join ", " (map :path q))
                       ") — inspect with git_conflicts, merge via edit tools, then git_resolve")}

          :else
          (let [r (git/push-to-remote! ctx target
                                       :token token :branch (or branch "main"))]
            (when-not (:error r)
              (db/set-meta! conn "git-remote" (str target)))
            (assoc r :remote (str target)))))
      (finally (git/close-ctx! ctx)))))

(defn clone!
  "Clone git remote `url` into `dir` as a fileless slopp store: fetch the
  tip, restore the deps manifest from the remote deps.edn, ingest every
  namespace through the verified write path (dependency order — reuses
  slopp.boot's require-graph sort), and record `git-remote`/`git-base-sha`
  so the projection grafts onto the remote's history. Ingest is byte-exact,
  so a fresh clone's live tree equals the remote tree (no phantom wip).
  Returns {:dir :namespaces n :base sha} | {:error msg}."
  [url dir & {:keys [token agent]}]
  (if (.exists (io/file dir ".slopp" "store.db"))
    {:error (str dir " already has a store — clone into a fresh dir")}
    (let [repo (git/open-repo! nil)]
      (try
        (let [{:keys [tip]} (git/fetch-remote! repo url :token token)]
          (if-not tip
            {:error (str "remote has no main branch to clone: " url)}
            (let [tree    (git/tree-at repo tip)
                  sources (into {}
                                (keep (fn [[path text]]
                                        (when-let [ns-sym (path-ns path)]
                                          [ns-sym text])))
                                tree)
                  deps    (or (some-> (get tree "deps.edn")
                                      edn/read-string :deps)
                              {})]
              (if (empty? sources)
                {:error (str "nothing to ingest at " url
                             " — no src/**.clj or test/**.clj on main")}
                (let [sess (api/open! {:dir dir})]
                  (try
                    (doseq [[lib coord] (sort-by (comp str key) deps)]
                      (let [r (api/deps-add! sess lib coord :agent agent
                                             :prompt (str "clone: dep from " url))]
                        (when (:error r)
                          (throw (ex-info (str "dep " lib ": " (:error r)) {})))))
                    (doseq [ns-sym (boot/dependency-order sources)]
                      (let [r (api/ingest! sess ns-sym (get sources ns-sym)
                                           :agent agent)]
                        (when (:error r)
                          (throw (ex-info (str ns-sym ": " (:error r)) {})))))
                    (let [conn (:db @sess)]
                      (db/set-meta! conn "git-remote" (str url))
                      (doseq [[path text] tree
                              :when (and (nil? (path-ns path))
                                         (not= "deps.edn" path))]
                        (api/file-put! sess path text :agent agent
                                       :prompt (str "clone: file from " url)))
                      (db/set-meta! conn "git-base-sha" tip))
                    {:dir (str dir) :namespaces (count sources) :base tip}
                    (catch clojure.lang.ExceptionInfo e
                      {:error (str "clone failed at " (ex-message e)
                                   " — partial store left at " dir
                                   "; delete it to retry")})
                    (finally (api/close! sess))))))))
        (finally (.close repo))))))

(defn form-entries
  "Ordered [{:name sym-or-nil :src str}] for each top-level FORM in `source`
  (trivia dropped — the pull diff/merge granularity is the form)."
  [source]
  (into []
        (comp (filter n/sexpr-able?)
              (map (fn [node]
                     (let [s (n/string node)]
                       {:name (store/name-of-source s) :src s}))))
        (n/children (p/parse-string-all (str source)))))
(defn ns-change-plan
  "PURE 3-way merge plan for one namespace at form granularity. `old` = the
  file at the merge base, `new` = at the remote tip, `cur` = our current
  render. Remote wins wherever WE are clean (our form still equals the
  base's); anything both sides touched is a conflict for the agent — exactly
  git's model, one file coarser. Returns
    {:noop true}  |  {:noop true :trivia true}   (only trivia differs)
    {:conflict reason}
    {:steps [edit-group steps] :order [names]}   (order = remote file order)."
  [ns-sym old new cur]
  (if (= cur new)
    {:noop true}
    (let [oldf (form-entries (or old ""))
          newf (form-entries (or new ""))
          curf (form-entries (or cur ""))
          anon? (fn [fs] (some #(nil? (:name %)) fs))
          dup?  (fn [fs] (some (fn [[_ c]] (> c 1)) (frequencies (map :name fs))))]
      (cond
        (or (anon? oldf) (anon? newf) (anon? curf))
        {:conflict "anonymous top-level form — not addressable at form granularity"}

        (or (dup? oldf) (dup? newf) (dup? curf))
        {:conflict "duplicate form names"}

        :else
        (let [om (into {} (map (juxt :name :src)) oldf)
              nm (into {} (map (juxt :name :src)) newf)
              cm (into {} (map (juxt :name :src)) curf)
              verdicts
              (for [nm-sym (distinct (concat (map :name oldf) (map :name newf)))
                    :let [o (om nm-sym) n' (nm nm-sym) c (cm nm-sym)]
                    :when (not= o n')]
                (cond
                  (= c n') nil                                   ; already have it
                  (nil? n') (cond                                ; deleted remotely
                              (nil? c) nil
                              (= c o)  {:step {:action :delete :ns ns-sym :name nm-sym}}
                              :else    {:conflict (str "remote deleted " nm-sym " which we edited")})
                  (nil? o)  (if (nil? c)                         ; added remotely
                              {:step {:action :add :ns ns-sym :source n'}}
                              {:conflict (str "both sides added " nm-sym)})
                  :else     (cond                                ; changed remotely
                              (= c o)  {:step {:action :replace :ns ns-sym :name nm-sym :source n'}}
                              (nil? c) {:conflict (str "remote edited " nm-sym " which we deleted")}
                              :else    {:conflict (str "both sides edited " nm-sym)})))
              verdicts (remove nil? verdicts)]
          (if-let [c (first (filter :conflict verdicts))]
            {:conflict (:conflict c)}
            (if (empty? verdicts)
              {:noop true :trivia true}
              {:steps (mapv :step verdicts) :order (mapv :name newf)})))))))
(defn- reorder-to!
  "Best-effort: make `ns-sym`'s form order match `order` (the remote file's)
  so rendered trees converge byte-wise. Skip-optimized right-to-left
  move-form! pass — each form is placed immediately before its desired
  successor, skipping pairs already adjacent; forms absent on either side
  stay put."
  [session ns-sym order agent]
  (let [present (into #{} (keep :name) (store/forms (:store @session) ns-sym))
        want    (filterv present order)]
    (doseq [i (range (- (count want) 2) -1 -1)]
      (let [nm    (nth want i)
            succ  (nth want (inc i))
            names (into [] (keep :name) (store/forms (:store @session) ns-sym))
            pos   (.indexOf ^java.util.List names nm)
            spos  (.indexOf ^java.util.List names succ)]
        (when (not= (inc pos) spos)
          (api/move-form! session ns-sym nm :before succ
                          :prompt "pull: match remote form order" :agent agent))))))
(defn- apply-deps!
  "Absorb remote deps.edn changes (base→tip) into the manifest: applied when
  OUR coord still equals the base's (clean), conflict when all three
  diverge — the deps.edn analogue of the per-form 3-way."
  [session treeM treeT conflict! agent]
  (let [dm  (or (:deps (edn/read-string (or (get treeM "deps.edn") "{}"))) {})
        dt  (or (:deps (edn/read-string (or (get treeT "deps.edn") "{}"))) {})
        cur (:deps (:store @session))]
    (doseq [lib (distinct (concat (keys dm) (keys dt)))
            :let [mv (get dm lib) tv (get dt lib) cv (get cur lib)]
            :when (not= mv tv)]
      (cond
        (= cv tv) nil
        (= cv mv) (let [r (if tv
                            (api/deps-add! session lib tv :agent agent
                                           :prompt "pull: remote deps change")
                            (api/deps-remove! session lib :agent agent
                                              :prompt "pull: remote deps removal"))]
                    (when (:error r)
                      (conflict! "deps.edn" nil (str lib ": " (:error r)))))
        :else (conflict! "deps.edn" nil
                         (str "deps diverged for " lib ": base " (pr-str mv)
                              ", remote " (pr-str tv) ", ours " (pr-str cv)))))))
(defn- apply-ns!
  "Apply one namespace's remote change (base→tip) to the store: ingest new
  namespaces, edit-group the form-level diff (remote wins where we're
  clean), quarantine everything that needs an agent. Whole-file deletions
  are conservatively a conflict (an agent should confirm destruction)."
  [session ns-sym path old new conflict! applied! note! agent]
  (let [have (get-in (:store @session) [:namespaces ns-sym])
        cur  (when have (api/query-source session ns-sym))]
    (cond
      (nil? new)
      (when have
        (conflict! path ns-sym
                   "remote deleted this namespace — delete its forms locally, then git_resolve"))

      (nil? old)
      (cond
        (= cur new) nil
        (not have)  (let [r (api/ingest! session ns-sym new :agent agent)]
                      (if (:error r)
                        (conflict! path ns-sym (:error r))
                        (applied! ns-sym)))
        :else       (conflict! path ns-sym "both sides added this namespace"))

      :else
      (if-not have
        (conflict! path ns-sym "remote edited a namespace we deleted")
        (let [plan (ns-change-plan ns-sym old new cur)]
          (cond
            (:conflict plan) (conflict! path ns-sym (:conflict plan))
            (:noop plan)     (when (:trivia plan)
                               (note! (str path ": comment/whitespace-only remote change — "
                                           "not representable as a form edit, skipped")))
            :else
            (let [r (api/edit-group! session (:steps plan)
                                     :prompt (str "pull: " path) :agent agent)]
              (if (:error r)
                (conflict! path ns-sym (str "failed to apply: " (:error r)))
                (do (reorder-to! session ns-sym (:order plan) agent)
                    (when (not= (api/query-source session ns-sym) new)
                      (note! (str path ": applied, but trivia differs from the remote")))
                    (applied! ns-sym))))))))))
(defn- apply-files!
  "Absorb remote changes to NON-CODE files (base→tip) into the files
  manifest — the deps.edn-style 3-way: applied when OUR copy still equals
  the base's; all-three-diverged is a conflict."
  [session treeM treeT changed conflict! note! agent]
  (doseq [path changed
          :when (and (nil? (path-ns path)) (not= "deps.edn" path))
          :let [mv (get treeM path)
                tv (get treeT path)
                cv (get-in @session [:store :files path])]]
    (cond
      (= cv tv) nil
      (= cv mv) (let [r (if (some? tv)
                          (api/file-put! session path tv :agent agent
                                         :prompt (str "pull: " path))
                          (api/file-remove! session path :agent agent
                                            :prompt (str "pull: removed " path)))]
                  (if (:error r)
                    (conflict! path nil (str "file change failed: " (:error r)))
                    (note! (str path ": file " (if (some? tv) "updated" "removed")))))
      :else (conflict! path nil
                       (str "file diverged: base/remote/ours all differ")))))
(defn- apply-pull!
  "The pull body once fetch/merge-base decided there IS something to absorb:
  diff tree(merge-base)→tree(tip), deps first, then namespaces in the remote
  tree's dependency order, then the `:git-sha` chain marker (the remote tip
  becomes a chain node, so our next milestone parents on it and pushes stay
  fast-forward). Conflicts land in quarantine (push blocks until resolved)."
  [session ctx url mb tip agent]
  (let [repo    (:repo ctx)
        conn    (:db @session)
        treeM   (git/tree-at repo mb)
        treeT   (git/tree-at repo tip)
        changed (into []
                      (comp (distinct)
                            (filter #(not= (get treeM %) (get treeT %))))
                      (concat (keys treeM) (keys treeT)))
        results (volatile! {:applied [] :conflicts [] :notes []})
        conflict! (fn [path ns-sym reason]
                    (db/quarantine-put! conn {:path path :ns ns-sym
                                              :source (get treeT path)
                                              :sha tip :reason reason})
                    (vswap! results update :conflicts conj
                            {:path path :reason reason}))
        applied!  (fn [n] (vswap! results update :applied conj n))
        note!     (fn [s] (vswap! results update :notes conj s))]
    (when (not= (get treeM "deps.edn") (get treeT "deps.edn"))
      (apply-deps! session treeM treeT conflict! agent))
    (apply-files! session treeM treeT changed conflict! note! agent)
    (let [by-ns (into {} (keep (fn [p] (when-let [n' (path-ns p)] [n' p]))) changed)
          srcs  (into {} (map (fn [[n' p]] [n' (or (get treeT p) (get treeM p) "")])) by-ns)]
      (doseq [ns-sym (boot/dependency-order srcs)
              :let [path (by-ns ns-sym)]]
        (apply-ns! session ns-sym path (get treeM path) (get treeT path)
                   conflict! applied! note! agent)))
    (let [head (:id (last (store/deltas (:store @session))))
          m    (api/commit-point! session
                                  (str "pull " (subs tip 0 8) " from " url)
                                  :agent agent :target head
                                  :extra {:git-sha tip})]
      {:pulled    (:applied @results)
       :conflicts (:conflicts @results)
       :notes     (:notes @results)
       :base      tip
       :marker    (:commit m)})))
(defn pull!
  "Absorb the remote's changes since the last common point into the LIVE
  session: fetch, merge-base against our projected tip, 3-way apply at form
  granularity (remote wins where we're clean; both-touched → quarantined
  CONFLICT, our version stays live, push blocks until git_resolve), then
  record the remote tip as a `:git-sha` chain marker. Returns
  {:pulled [nses] :conflicts [{:path :reason}] :notes [..] :base tip :marker id}
  | {:up-to-date true} | {:error msg}."
  [session & {:keys [token agent]}]
  (let [dir (:dir @session)]
    (if-not dir
      {:error "pull needs a durable session (a store dir)"}
      (let [shared (get-in @session [:git-server :ctx])
            ctx    (or shared (git/open-ctx! dir))]
        (try
          (let [url (db/get-meta (:map-conn ctx) "git-remote")]
            (if (str/blank? (str url))
              {:error "no remote configured — git_push with :url (or clone) first"}
              (let [ours (get-in (git/ensure-projected! ctx) [:refs "main"])
                    tip  (:tip (git/fetch-remote! (:repo ctx) url :token token))]
                (cond
                  (nil? tip)   {:error (str "remote has no main branch: " url)}
                  (nil? ours)  {:error "nothing to pull onto — no local milestones or clone base"}
                  (= tip ours) {:up-to-date true}
                  :else
                  (let [mb (git/merge-base (:repo ctx) ours tip)]
                    (cond
                      (nil? mb)  {:error "unrelated histories — was the remote rewritten? re-clone"}
                      (= mb tip) {:up-to-date true}
                      :else      (apply-pull! session ctx url mb tip agent)))))))
          (finally (when-not shared (git/close-ctx! ctx))))))))
^:reads (defn conflicts
  "Unresolved pull conflicts for the store at `dir` — each row carries the
  raw remote file so the agent can merge it: [{:path :ns :source :sha
  :reason :at}]."
  [dir]
  (with-open [conn (db/open! dir)]
    (db/quarantine-list conn)))
(defn resolve!
  "Mark a pull conflict resolved — the agent has merged/adapted the remote
  content through the edit tools (or decided against it). nil `path` clears
  everything. Returns the remaining {:conflicts [...]}."
  [dir path]
  (with-open [conn (db/open! dir)]
    (db/quarantine-clear! conn path)
    {:conflicts (db/quarantine-list conn)}))
(defn -main
  "clojure -M -m slopp.sync clone <url> <dir> | push <dir> [url] | pull <dir>"
  [& [cmd a b]]
  (let [r (case cmd
            "clone" (clone! a b)
            "push"  (push! a :url b)
            "pull"  (let [sess (api/open! {:dir a})]
                      (try (pull! sess)
                           (finally (api/close! sess))))
            "test"  (let [sess (api/open! {:dir a})]
                      (try (api/isolated-test-run! sess)
                           (finally (api/close! sess))))
            {:error "usage: clone <url> <dir> | push <dir> [url] | pull <dir> | test <dir>"})]
    (println (pr-str r))
    (shutdown-agents)
    (when (or (:error r) (= :red (:status r))) (System/exit 1))))
