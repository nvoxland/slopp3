(ns slopp.sync-test
  "Phase-1 git bridge orchestration: push a store to a normal remote, clone
  it back as a FILELESS store (no .clj in the working dir), edit, and push
  again — the clone's chain grafts onto the remote history, so the second
  push is a plain fast-forward. This is the whole collaboration story."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.db :as db]
            [slopp.git :as git]
            [slopp.store :as store]
            [slopp.sync :as sync])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.eclipse.jgit.api Git]
           [org.eclipse.jgit.lib ObjectId]
           [org.eclipse.jgit.revwalk RevWalk]
           [org.eclipse.jgit.storage.file FileRepositoryBuilder]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-sync-test" (make-array FileAttribute 0))))

(defn- rm-rf! [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
    (.delete f)))

(defn- bare-repo! [dir]
  (-> (Git/init) (.setBare true) (.setInitialBranch "main")
      (.setDirectory (io/file dir)) (.call) (.close))
  dir)

(def seed
  (str "(ns gc.core (:require [clojure.test :refer [deftest is]]))\n"
       "\n"
       "(defn f [x] (+ x 1))\n"
       "\n"
       "(deftest f-t (is (= 2 (f 1))))\n"))

(defn- clj-files [dir]
  (->> (file-seq (io/file dir))
       (filter (fn [^java.io.File f]
                 (and (.isFile f) (str/ends-with? (.getName f) ".clj"))))
       (mapv str)))

(deftest ^:isolated clone-is-fileless-and-pushes-fast-forward
  (let [dir-a (temp-dir)
        dir-b (str (temp-dir) "/clone")
        bare  (bare-repo! (str (temp-dir) "/remote.git"))
        sess  (api/open! {:dir dir-a})]
    (try
      (api/ingest! sess 'gc.core seed)
      (api/commit-point! sess "v1: f ships" :agent "alice")
      (let [p1 (sync/push! dir-a :url bare)]
        (is (nil? (:error p1)) (pr-str p1))
        (is (string? (:pushed p1)))

        (testing "clone: a store, no .clj files anywhere in the dir"
          (let [c (sync/clone! bare dir-b :agent "bob")]
            (is (nil? (:error c)) (pr-str c))
            (is (= 1 (:namespaces c)))
            (is (.exists (io/file dir-b ".slopp" "store.db")))
            (is (empty? (clj-files dir-b)))))

        (let [sb (api/open! {:dir dir-b})]
          (try
            (testing "the cloned store carries the source byte-exactly + the remote identity"
              (is (= (api/query-source sess 'gc.core)
                     (api/query-source sb 'gc.core)))
              (let [conn (:db @sb)]
                (is (= (str bare) (db/get-meta conn "git-remote")))
                (is (= (:pushed p1) (db/get-meta conn "git-base-sha")))))

            (testing "edit + milestone + push from the clone = fast-forward onto the remote's history"
              (let [e (api/edit-replace! sb 'gc.core 'f "(defn f [x] (+ 1 x))"
                                         :prompt "flip arg order" :agent "bob")]
                (is (nil? (:error e)) (pr-str e)))
              (let [m (api/commit-point! sb "v2: flipped" :agent "bob")]
                (is (nil? (:error m)) (pr-str m)))
              (let [p2 (sync/push! dir-b)]     ; url comes from the saved meta
                (is (nil? (:error p2)) (pr-str p2))
                (let [remote (-> (FileRepositoryBuilder.)
                                 (.setGitDir (io/file bare)) (.build))]
                  (try
                    (let [tip (.name (.resolve remote "refs/heads/slopp/main"))]
                      (is (= (:pushed p2) tip))
                      (testing "the new tip's parent IS the pre-clone tip (graft worked)"
                        (with-open [rw (RevWalk. remote)]
                          (let [c (.parseCommit rw (ObjectId/fromString tip))]
                            (is (= [(:pushed p1)]
                                   (mapv #(.name ^ObjectId %) (.getParents c)))))))
                      (testing "the remote file shows the clone's edit"
                        (is (str/includes?
                             (get (git/tree-at remote tip) "src/gc/core.clj")
                             "(+ 1 x)"))))
                    (finally (.close remote))))))
            (finally (api/close! sb)))))
      (finally
        (api/close! sess)
        (rm-rf! dir-a)
        (rm-rf! (.getParentFile (io/file dir-b)))
        (rm-rf! (.getParentFile (io/file bare)))))))

(deftest ^:isolated pull-absorbs-remote-changes-bidirectionally
  ;; A pushes v1 → B clones → A pushes v2 → B pulls (absorbs, chains via the
  ;; :git-sha marker) → B pushes v3 → A pulls B's work back. Full circle.
  (let [dir-a (temp-dir)
        dir-b (str (temp-dir) "/clone")
        bare  (bare-repo! (str (temp-dir) "/remote.git"))
        sa    (api/open! {:dir dir-a})]
    (try
      (api/ingest! sa 'gc.core seed)
      (api/commit-point! sa "v1" :agent "alice")
      (is (nil? (:error (sync/push! dir-a :url bare))))
      (is (nil? (:error (sync/clone! bare dir-b :agent "bob"))))

      ;; A moves on: f + its test change together (stays green)
      (api/edit-group! sa [{:action :replace :ns 'gc.core :name 'f
                            :source "(defn f [x] (+ x 10))"}
                           {:action :replace :ns 'gc.core :name 'f-t
                            :source "(deftest f-t (is (= 11 (f 1))))"}]
                       :prompt "v2" :agent "alice")
      (api/commit-point! sa "v2" :agent "alice")
      (let [p2 (sync/push! dir-a)]
        (is (nil? (:error p2)) (pr-str p2))

        (let [sb (api/open! {:dir dir-b})]
          (try
            (testing "B pulls A's v2"
              (let [r (sync/pull! sb :agent "bob")]
                (is (nil? (:error r)) (pr-str r))
                (is (empty? (:conflicts r)) (pr-str r))
                (is (= (:pushed p2) (:base r)))
                (testing "sources converge byte-exactly"
                  (is (= (api/query-source sa 'gc.core)
                         (api/query-source sb 'gc.core))))
                (testing "the pull marker carries :git-sha (the chain node)"
                  (let [d (->> (store/deltas (:store @sb))
                               (filter #(= :commit (:op %))) last)]
                    (is (= (:pushed p2) (:git-sha d)))))))

            (testing "pull again is up-to-date"
              (is (:up-to-date (sync/pull! sb :agent "bob"))))

            (testing "B works on top and pushes — fast-forward through the pulled chain"
              (api/edit-group! sb [{:action :add :ns 'gc.core
                                    :source "(defn g [x] (* 2 x))"}
                                   {:action :add :ns 'gc.core
                                    :source "(deftest g-t (is (= 4 (g 2))))"}]
                               :prompt "v3" :agent "bob")
              (api/commit-point! sb "v3" :agent "bob")
              (let [p3 (sync/push! dir-b)]
                (is (nil? (:error p3)) (pr-str p3))
                (testing "the new tip's parent is A's v2 tip"
                  (let [remote (-> (FileRepositoryBuilder.)
                                   (.setGitDir (io/file bare)) (.build))]
                    (try
                      (with-open [rw (RevWalk. remote)]
                        (let [c (.parseCommit rw (ObjectId/fromString (:pushed p3)))]
                          (is (= [(:pushed p2)]
                                 (mapv #(.name ^ObjectId %) (.getParents c))))))
                      (finally (.close remote)))))))

            (testing "A pulls B's g back"
              (let [r (sync/pull! sa :agent "alice")]
                (is (nil? (:error r)) (pr-str r))
                (is (= (api/query-source sb 'gc.core)
                       (api/query-source sa 'gc.core)))))
            (finally (api/close! sb)))))
      (finally
        (api/close! sa)
        (rm-rf! dir-a)
        (rm-rf! (.getParentFile (io/file dir-b)))
        (rm-rf! (.getParentFile (io/file bare)))))))

(deftest ^:isolated pull-conflicts-quarantine-and-block-push
  ;; Both sides edit the same form → the ns is quarantined (git-style
  ;; conflict, OUR version stays live), push is blocked until the agent
  ;; resolves and clears it.
  (let [dir-a (temp-dir)
        dir-b (str (temp-dir) "/clone")
        bare  (bare-repo! (str (temp-dir) "/remote.git"))
        sa    (api/open! {:dir dir-a})]
    (try
      (api/ingest! sa 'gc.core seed)
      (api/commit-point! sa "v1" :agent "alice")
      (is (nil? (:error (sync/push! dir-a :url bare))))
      (is (nil? (:error (sync/clone! bare dir-b :agent "bob"))))

      ;; A changes f (with its test); B changes f divergently (same behavior)
      (api/edit-group! sa [{:action :replace :ns 'gc.core :name 'f
                            :source "(defn f [x] (+ x 10))"}
                           {:action :replace :ns 'gc.core :name 'f-t
                            :source "(deftest f-t (is (= 11 (f 1))))"}]
                       :prompt "v2" :agent "alice")
      (api/commit-point! sa "v2" :agent "alice")
      (is (nil? (:error (sync/push! dir-a))))

      (let [sb (api/open! {:dir dir-b})]
        (try
          (api/edit-replace! sb 'gc.core 'f "(defn f [x] (+ x 0 1))"
                             :prompt "local tweak" :agent "bob")
          (let [b-version (api/query-source sb 'gc.core)
                r         (sync/pull! sb :agent "bob")]
            (is (nil? (:error r)) (pr-str r))
            (testing "the both-edited ns is a conflict; our version stays live"
              (is (= ["src/gc/core.clj"] (mapv :path (:conflicts r))))
              (is (= b-version (api/query-source sb 'gc.core))))
            (testing "push is blocked while conflicts stand"
              (let [p (sync/push! dir-b)]
                (is (:error p))
                (is (str/includes? (str (:error p)) "conflict"))))
            (testing "conflicts are listable with the remote source"
              (let [[c] (sync/conflicts dir-b)]
                (is (= "src/gc/core.clj" (:path c)))
                (is (str/includes? (str (:source c)) "(+ x 10)"))))
            (testing "resolve: adopt the remote content, clear, milestone, push"
              (api/edit-group! sb [{:action :replace :ns 'gc.core :name 'f
                                    :source "(defn f [x] (+ x 10))"}
                                   {:action :replace :ns 'gc.core :name 'f-t
                                    :source "(deftest f-t (is (= 11 (f 1))))"}]
                               :prompt "adopt remote f" :agent "bob")
              (is (empty? (:conflicts (sync/resolve! dir-b "src/gc/core.clj"))))
              (api/commit-point! sb "v3: merged" :agent "bob")
              (is (nil? (:error (sync/push! dir-b))))))
          (finally (api/close! sb))))
      (finally
        (api/close! sa)
        (rm-rf! dir-a)
        (rm-rf! (.getParentFile (io/file dir-b)))
        (rm-rf! (.getParentFile (io/file bare)))))))

(deftest ^:isolated clone-guards
  (testing "an empty remote (no main) is an honest error"
    (let [bare (bare-repo! (str (temp-dir) "/empty.git"))
          dir  (str (temp-dir) "/x")]
      (try
        (is (:error (sync/clone! bare dir)))
        (finally
          (rm-rf! (.getParentFile (io/file bare)))
          (rm-rf! (.getParentFile (io/file dir)))))))
  (testing "an unreachable remote is an honest error, not a stack trace"
    (let [dir (str (temp-dir) "/x")]
      (try
        (is (:error (sync/clone! "/nowhere/does-not-exist.git" dir)))
        (finally (rm-rf! (.getParentFile (io/file dir)))))))
  (testing "an existing NON-EMPTY store refuses to be clobbered"
    (let [dir  (temp-dir)
          sess (api/open! {:dir dir})]
      (try
        (api/ingest! sess 'cg.core "(ns cg.core)\n(defn f [] 1)\n")
        (api/close! sess)
        (is (:error (sync/clone! "ignored" dir)))
        (finally (rm-rf! dir)))))
  (testing "push with no url and no saved remote is an honest error"
    (let [dir (temp-dir)]
      (try
        (is (:error (sync/push! dir)))
        (finally (rm-rf! dir))))))
(defn- human-commit!
  "Plumbing-commit one file onto `branch` of the BARE repo at `dir` (the
  'human owns this branch with regular git' side of mixed ownership).
  Returns the new sha."
  [dir branch path text]
  (let [repo (-> (org.eclipse.jgit.storage.file.FileRepositoryBuilder.)
                 (.setGitDir (clojure.java.io/file (str dir)))
                 (.build))]
    (try
      (with-open [ins (.newObjectInserter repo)]
        (let [blob (.insert ins org.eclipse.jgit.lib.Constants/OBJ_BLOB
                            (.getBytes ^String text "UTF-8"))
              dc   (org.eclipse.jgit.dircache.DirCache/newInCore)
              b    (.builder dc)]
          (.add b (doto (org.eclipse.jgit.dircache.DirCacheEntry. ^String path)
                    (.setFileMode org.eclipse.jgit.lib.FileMode/REGULAR_FILE)
                    (.setObjectId blob)))
          (.finish b)
          (let [tree (.writeTree dc ins)
                old  (.resolve repo (str "refs/heads/" branch))
                pi   (org.eclipse.jgit.lib.PersonIdent. "human" "human@example.com")
                cb   (doto (org.eclipse.jgit.lib.CommitBuilder.)
                       (.setTreeId tree)
                       (.setAuthor pi)
                       (.setCommitter pi)
                       (.setMessage "human commit"))]
            (when old (.setParentId cb old))
            (let [cid (.insert ins cb)]
              (.flush ins)
              (.update (doto (.updateRef repo (str "refs/heads/" branch))
                         (.setNewObjectId cid)
                         (.setForceUpdate true)))
              (.name cid)))))
      (finally (.close repo)))))
(deftest ^:isolated slopp-owns-one-branch-humans-own-the-rest
  ;; the ownership boundary IS the branch: slopp pushes only refs/heads/slopp/*
  ;; mirrors; a human manages main with regular git and
  ;; slopp never touches it — mixed repos without everything living in slopp
  (let [dir  (temp-dir)
        bare (bare-repo! (str (temp-dir) "/remote.git"))
        sess (api/open! {:dir dir})]
    (try
      (api/ingest! sess 'mo.core "(ns mo.core)\n(defn ^:unused-ok f [] 1)\n")
      (api/commit-point! sess "v1" :agent "a")
      (testing "default push lands on the slopp branch; main is never created"
        (let [p (sync/push! dir :url bare)]
          (is (nil? (:error p)) (pr-str p))
          (let [remote (-> (FileRepositoryBuilder.)
                           (.setGitDir (io/file bare)) (.build))]
            (try
              (is (some? (.resolve remote "refs/heads/slopp/main")))
              (is (nil? (.resolve remote "refs/heads/main")))
              (finally (.close remote))))))
      (testing "a human commit on main survives slopp pushes and stays out of pulls"
        (let [human-sha (human-commit! bare "main" "NOTES.md" "mine, not slopp's\n")]
          (api/edit-replace! sess 'mo.core 'f "(defn ^:unused-ok f [] 2)"
                             :prompt "v2" :agent "a")
          (api/commit-point! sess "v2" :agent "a")
          (is (nil? (:error (sync/push! dir))))
          (let [remote (-> (FileRepositoryBuilder.)
                           (.setGitDir (io/file bare)) (.build))]
            (try
              (is (= human-sha (.name (.resolve remote "refs/heads/main"))))
              (finally (.close remote))))
          (testing "pull reads only the slopp branch — the human file never enters"
            (is (:up-to-date (sync/pull! sess :agent "a")))
            (is (nil? (get (:files (:store @sess)) "NOTES.md"))))))
      (finally
        (api/close! sess)
        (rm-rf! dir)
        (rm-rf! (.getParentFile (io/file bare)))))))
(deftest ^:isolated local-repo-push-guards-the-checked-out-branch
  ;; pushing INTO the working repo's own .git is the local mixed workflow —
  ;; but never onto the branch a working tree has checked out (JGit would
  ;; move the ref under it)
  (let [work (temp-dir)
        dir  (temp-dir)]
    (-> (org.eclipse.jgit.api.Git/init) (.setInitialBranch "slopp/main")
        (.setDirectory (io/file work)) (.call) (.close))
    (let [sess (api/open! {:dir dir})]
      (try
        (api/ingest! sess 'lo.core "(ns lo.core)\n")
        (api/commit-point! sess "v1" :agent "a")
        (testing "the checked-out branch is refused"
          (let [r (sync/push! dir :url work :branch "main")]
            (is (:error r))
            (is (re-find #"checked out" (str (:error r))))))
        (testing "any other branch of the same working repo is fine"
          (let [r (sync/push! dir :url work :branch "other")]
            (is (nil? (:error r)) (pr-str r))))
        (finally
          (api/close! sess)
          (rm-rf! dir)
          (rm-rf! work))))))
(deftest ^:isolated import-into-a-main-checkout
  ;; THE onboarding flow: git clone (main checked out, human files on disk) →
  ;; slopp import . → the store comes from the slopp BRANCH of the same local
  ;; repo; the working dir stays the human's main checkout
  (let [origin (bare-repo! (str (temp-dir) "/origin.git"))
        seed-d (temp-dir)
        sess   (api/open! {:dir seed-d})]
    (try
      ;; seed origin's slopp branch from a store, and main from a "human"
      (api/ingest! sess 'im.core "(ns im.core)\n(defn ^:unused-ok f [] 41)\n")
      (api/commit-point! sess "v1" :agent "a")
      (is (nil? (:error (sync/push! seed-d :url origin))))
      (human-commit! origin "main" "README.md" "# my project\n")
      ;; a human's git clone: main checked out, slopp only remote-tracking
      (let [work (str (temp-dir) "/work")]
        (-> (org.eclipse.jgit.api.Git/cloneRepository)
            (.setURI (str (io/file origin))) (.setDirectory (io/file work))
            (.setBranch "main") (.call) (.close))
        (is (.exists (io/file work "README.md")))
        (testing "import builds the store from the local repo's slopp branch"
          (let [r (sync/import! work)]
            (is (nil? (:error r)) (pr-str r))
            (is (= 1 (:namespaces r)))
            (is (= "slopp/main" (:branch r)))
            (is (.exists (io/file work ".slopp" "store.db")))
            (testing "the human's checkout is untouched"
              (is (.exists (io/file work "README.md")))
              (is (empty? (->> (file-seq (io/file work "src"))
                               (filter #(.isFile ^java.io.File %))))))))
        (testing "the imported store syncs against the LOCAL repo's slopp branch"
          (let [sw (api/open! {:dir work})]
            (try
              (is (= "." (db/get-meta (:db @sw) "git-remote")))
              (api/edit-replace! sw 'im.core 'f "(defn ^:unused-ok f [] 42)"
                                 :prompt "answer" :agent "b")
              (api/commit-point! sw "v2" :agent "b")
              (let [p (sync/push! work)]
                (is (nil? (:error p)) (pr-str p))
                ;; refs/heads/slopp advanced INSIDE the checkout's .git;
                ;; origin untouched (the human pushes with regular git)
                (let [local (-> (org.eclipse.jgit.storage.file.FileRepositoryBuilder.)
                                (.setGitDir (io/file work ".git")) (.build))]
                  (try
                    (is (= (:pushed p) (.name (.resolve local "refs/heads/slopp/main"))))
                    (finally (.close local)))))
              (finally (api/close! sw)))))
        (rm-rf! work))
      (finally
        (api/close! sess)
        (rm-rf! seed-d)
        (rm-rf! (.getParentFile (io/file origin)))))))
(deftest ^:isolated import-tolerates-the-servers-empty-store
  ;; the plugin's MCP server auto-creates an EMPTY store when it serves a
  ;; fresh clone; import must treat that as a fresh dir, not refuse it
  (let [origin (bare-repo! (str (temp-dir) "/origin.git"))
        seed-d (temp-dir)
        sess   (api/open! {:dir seed-d})]
    (try
      (api/ingest! sess 'im2.core "(ns im2.core)\n(defn ^:unused-ok f [] 41)\n")
      (api/commit-point! sess "v1" :agent "a")
      (is (nil? (:error (sync/push! seed-d :url origin))))
      (human-commit! origin "main" "README.md" "# my project\n")
      (let [work (str (temp-dir) "/work")]
        (-> (org.eclipse.jgit.api.Git/cloneRepository)
            (.setURI (str (io/file origin))) (.setDirectory (io/file work))
            (.setBranch "main") (.call) (.close))
        ;; the server's footprint: an empty store.db already in place
        (.close (db/open! work))
        (is (.exists (io/file work ".slopp" "store.db")))
        (let [r (sync/import! work)]
          (is (nil? (:error r)) (pr-str r))
          (is (= 1 (:namespaces r))))
        (rm-rf! work))
      (finally
        (api/close! sess)
        (rm-rf! seed-d)
        (rm-rf! (.getParentFile (io/file origin)))))))
(deftest ^:isolated auto-import-on-serve
  ;; the server auto-imports a fresh clone of a slopp-published repo: the
  ;; slopp BRANCH is the marker; plain git repos are never auto-ingested
  (let [origin (bare-repo! (str (temp-dir) "/origin.git"))
        seed-d (temp-dir)
        sess   (api/open! {:dir seed-d})]
    (try
      (api/ingest! sess 'ai.core "(ns ai.core)\n(defn ^:unused-ok f [] 41)\n")
      (api/commit-point! sess "v1" :agent "a")
      (is (nil? (:error (sync/push! seed-d :url origin))))
      (human-commit! origin "main" "README.md" "# p\n")
      (let [work (str (temp-dir) "/work")]
        (-> (org.eclipse.jgit.api.Git/cloneRepository)
            (.setURI (str (io/file origin))) (.setDirectory (io/file work))
            (.setBranch "main") (.call) (.close))
        (.close (db/open! work))
        (testing "a slopp-published clone auto-imports over the empty store"
          (let [r (sync/maybe-auto-import! work)]
            (is (= 1 (:namespaces r)) (pr-str r))))
        (testing "a second serve is a no-op (store no longer empty)"
          (is (nil? (sync/maybe-auto-import! work))))
        (rm-rf! work))
      (testing "a git repo WITHOUT a slopp branch is never auto-ingested"
        (let [plain (str (temp-dir) "/plain")]
          (-> (org.eclipse.jgit.api.Git/init)
              (.setDirectory (io/file plain)) (.call) (.close))
          (is (nil? (sync/maybe-auto-import! plain)))
          (rm-rf! plain)))
      (finally
        (api/close! sess)
        (rm-rf! seed-d)
        (rm-rf! (.getParentFile (io/file origin)))))))
