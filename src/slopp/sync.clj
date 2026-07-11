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
            [slopp.git :as git]))

(defn path-ns
  "src/foo/bar_baz.clj → foo.bar-baz; nil for anything that isn't a source
  or test .clj path (those remote files are not slopp's to ingest)."
  [path]
  (when-let [[_ rel] (re-matches #"(?:src|test)/(.+)\.clj" (str path))]
    (symbol (-> rel (str/replace "/" ".") (str/replace "_" "-")))))

(defn push!
  "Push the store at `dir`'s projection to a git remote: `:url` the first
  time (saved as `git-remote` meta), the saved remote thereafter. Returns
  {:pushed sha :status s :remote url} | {:error msg}."
  [dir & {:keys [url token branch]}]
  (let [ctx (git/open-ctx! dir)]
    (try
      (let [conn   (:map-conn ctx)
            target (or url (db/get-meta conn "git-remote"))]
        (if (str/blank? (str target))
          {:error "no remote configured — pass :url once (it is saved as git-remote)"}
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
                      (db/set-meta! conn "git-base-sha" tip))
                    {:dir (str dir) :namespaces (count sources) :base tip}
                    (catch clojure.lang.ExceptionInfo e
                      {:error (str "clone failed at " (ex-message e)
                                   " — partial store left at " dir
                                   "; delete it to retry")})
                    (finally (api/close! sess))))))))
        (finally (.close repo))))))

(defn -main
  "clojure -M -m slopp.sync clone <url> <dir> | push <dir> [url]"
  [& [cmd a b]]
  (let [r (case cmd
            "clone" (clone! a b)
            "push"  (push! a :url b)
            {:error "usage: clone <url> <dir> | push <dir> [url]"})]
    (println (pr-str r))
    (shutdown-agents)
    (when (:error r) (System/exit 1))))