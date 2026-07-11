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
