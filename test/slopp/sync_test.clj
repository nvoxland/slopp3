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

(defn- rm-rf [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf (.listFiles f)))
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

(deftest clone-is-fileless-and-pushes-fast-forward
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
                    (let [tip (.name (.resolve remote "refs/heads/main"))]
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
        (rm-rf dir-a)
        (rm-rf (.getParentFile (io/file dir-b)))
        (rm-rf (.getParentFile (io/file bare)))))))

(deftest pull-absorbs-remote-changes-bidirectionally
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
        (rm-rf dir-a)
        (rm-rf (.getParentFile (io/file dir-b)))
        (rm-rf (.getParentFile (io/file bare)))))))

(deftest pull-conflicts-quarantine-and-block-push
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
        (rm-rf dir-a)
        (rm-rf (.getParentFile (io/file dir-b)))
        (rm-rf (.getParentFile (io/file bare)))))))

(deftest clone-guards
  (testing "an empty remote (no main) is an honest error"
    (let [bare (bare-repo! (str (temp-dir) "/empty.git"))
          dir  (str (temp-dir) "/x")]
      (try
        (is (:error (sync/clone! bare dir)))
        (finally
          (rm-rf (.getParentFile (io/file bare)))
          (rm-rf (.getParentFile (io/file dir)))))))
  (testing "an existing store refuses to be clobbered"
    (let [dir (temp-dir)]
      (try
        (io/make-parents (io/file dir ".slopp" "store.db"))
        (spit (io/file dir ".slopp" "store.db") "")
        (is (:error (sync/clone! "ignored" dir)))
        (finally (rm-rf dir)))))
  (testing "push with no url and no saved remote is an honest error"
    (let [dir (temp-dir)]
      (try
        (is (:error (sync/push! dir)))
        (finally (rm-rf dir))))))
