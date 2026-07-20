(ns slopp.git-projection-test
  "P4-m8: git compatibility layer, projection core. Commit points carry a
  byte-exact rendered :tree in their marker delta; slopp.git projects them
  lazily and deterministically into an in-memory repo (no on-disk repo) — same
  journal, same shas, every time. Native d<n> ids stay authoritative; git_map
  pins each marker's sha at first projection."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [slopp.api :as api]
            [slopp.db :as db]
            [slopp.git :as git]
            [slopp.store :as store] [slopp.api.branch :as branch] [slopp.api.query :as query] [slopp.api.external :as external])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.eclipse.jgit.lib ObjectId Repository]
           [org.eclipse.jgit.revwalk RevWalk]
           [org.eclipse.jgit.treewalk TreeWalk]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-git-test" (make-array FileAttribute 0))))

(defn- rm-rf! [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
    (.delete f)))

(def seed
  (str "(ns gp.core (:require [clojure.test :refer [deftest is]]))\n"
       "\n"
       ";; top-level trivia must survive projection\n"
       "(defn f [x] (+ x 10))\n"
       "\n"
       "(deftest f-t (is (= 11 (f 1))))\n"))

(defn- commit-info [^Repository repo sha]
  (with-open [rw (RevWalk. repo)]
    (let [c (.parseCommit rw (ObjectId/fromString sha))
          a (.getAuthorIdent c)]
      {:message (.getFullMessage c)
       :author  (.getName a)
       :email   (.getEmailAddress a)
       :at-ms   (.toEpochMilli (.getWhenAsInstant a))
       :parents (mapv #(.name ^ObjectId %) (.getParents c))})))

(defn- blob-text [^Repository repo sha path]
  (with-open [rw (RevWalk. repo)]
    (let [c  (.parseCommit rw (ObjectId/fromString sha))
          tw (TreeWalk/forPath repo ^String path (.getTree c))]
      (when tw
        (String. (.getBytes (.open repo (.getObjectId tw 0))) "UTF-8")))))

(deftest ^:external record-commit-extra-round-trips
  ;; the schemaless payload carries op-specific extras (P4-m8: :tree, :git-sha)
  (let [st (store/ingest (store/empty-store) 'gp.core seed)
        [st2 d] (store/record-commit st "v1" :agent "alice"
                                     :extra {:tree {'gp.core seed}
                                             :git-sha "abc"})]
    (is (= {'gp.core seed} (:tree d)))
    (is (= "abc" (:git-sha d)))
    (testing "still a no-content marker for foreign-journal sync"
      (is (some? (store/replay-delta st d))))
    (testing "the db round-trips the payload exactly"
      (let [dir  (temp-dir)
            conn (db/open! dir)]
        (try
          (db/persist! conn st2 d)
          (is (= d (first (db/deltas-after conn 0))))
          (finally (.close conn) (rm-rf! dir)))))))

(deftest ^:external commit-point-snapshots-the-rendered-tree
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'gp.core seed)
      (let [r (external/commit-point! sess "v1: f ships" :agent "alice")
            d (->> (store/deltas (:store @sess))
                   (filter #(= (:commit r) (:id %))) first)]
        (is (nil? (:error r)) (pr-str r))
        (testing "byte-exact rendered source, trivia included"
          (is (= (query/query-source sess 'gp.core) (get (:tree d) 'gp.core)))
          (is (str/includes? (get (:tree d) 'gp.core) ";; top-level trivia")))
        (testing "a retroactive :target marker carries NO tree (backfill path)"
          (let [r2 (external/commit-point! sess "was here" :agent "alice"
                                      :target (:target r))
                d2 (->> (store/deltas (:store @sess))
                        (filter #(= (:commit r2) (:id %))) first)]
            (is (nil? (:error r2)) (pr-str r2))
            (is (nil? (:tree d2))))))
      (finally (api/close! sess)))))

(deftest ^:external projection-mints-deterministic-shas
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'gp.core seed)
      ;; G5: milestones stamp a configured author; pin it so the assertions
      ;; below don't depend on this machine's global git config
      (external/config! sess "user.name" "alice")
      (external/config! sess "user.email" "alice@slopp")
      (external/commit-point! sess "v1: f ships" :agent "alice")
      (api/edit-replace! sess 'gp.core 'f "(defn f [x] (+ 10 x))"
                         :prompt "flip arg order" :agent "alice")
      (external/commit-point! sess "v2: flipped" :agent "alice")
      (let [ctx  (git/open-ctx! dir)
            tip  (get-in (git/ensure-projected! ctx) [:refs "main"])
            info (commit-info (:slopp.git/repo ctx) tip)
            cd   (->> (store/deltas (:store @sess))
                      (filter #(= :commit (:op %))) last)]
        (is tip)
        (testing "the tip is v2, chained on v1, authored by the agent at :at"
          (is (str/starts-with? (:message info) "v2: flipped"))
          (is (str/includes? (:message info) (str "Slopp-Commit: " (:id cd))))
          (is (= "alice" (:author info)))
          (is (= "alice@slopp" (:email info)))
          ;; git timestamps are second-granular; truncation is deterministic
          (is (= (quot (:at cd) 1000) (quot (:at-ms info) 1000)))
          (is (= 1 (count (:parents info))))
          (is (str/starts-with?
               (:message (commit-info (:slopp.git/repo ctx) (first (:parents info))))
               "v1: f ships")))
        (testing "blob bytes ARE the live render"
          (is (= (query/query-source sess 'gp.core)
                 (blob-text (:slopp.git/repo ctx) tip "src/gp/core.clj"))))
        (testing "the clone is a runnable project (deps.edn present)"
          (is (str/includes? (str (blob-text (:slopp.git/repo ctx) tip "deps.edn"))
                             ":paths")))
        (testing "re-projection is a no-op"
          (is (= tip (get-in (git/ensure-projected! ctx) [:refs "main"]))))
        (testing "query-commits surfaces the projected sha"
          (let [[c2 c1] (api/query-commits sess)]
            (is (= tip (:sha c2)))
            (is (= (first (:parents info)) (:sha c1)))))
        (git/close-ctx! ctx)
        (testing "rebuild from scratch (a fresh in-memory repo) mints IDENTICAL shas"
          (let [ctx2 (git/open-ctx! dir)]
            (try
              (jdbc/execute! (:slopp.git/map-conn ctx2) ["DELETE FROM git_map"])
              (is (= tip (get-in (git/ensure-projected! ctx2) [:refs "main"])))
              (finally (git/close-ctx! ctx2))))))
      (finally (api/close! sess)))))

(deftest ^:external branch-shares-prefix-shas
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'gp.core seed)
      (external/commit-point! sess "v1: f ships" :agent "alice")
      (branch/branch! sess "feature")
      (api/edit-replace! sess 'gp.core 'f "(defn f [x] (int (+ x 10)))"
                         :prompt "tweak on feature" :agent "bob")
      (external/commit-point! sess "feature: tweak" :agent "bob")
      (let [ctx (git/open-ctx! dir)]
        (try
          (let [{:keys [refs]} (git/ensure-projected! ctx)
                main-tip (get refs "main")
                feat-tip (get refs "feature")]
            (is main-tip)
            (is feat-tip)
            (testing "the branch commit chains on main's milestone"
              (is (= [main-tip] (:parents (commit-info (:slopp.git/repo ctx) feat-tip)))))
            (testing "ONE mapping row for the shared v1 marker (2 rows total)"
              (is (= 2 (:n (jdbc/execute-one!
                            (:slopp.git/map-conn ctx)
                            ["SELECT COUNT(*) AS n FROM git_map"]))))))
          (finally (git/close-ctx! ctx))))
      (finally (api/close! sess)))))

(deftest ^:external retroactive-target-is-lossy-but-pinned
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'gp.core seed)
      (let [r1 (external/commit-point! sess "v1" :agent "alice")]
        (api/edit-replace! sess 'gp.core 'f "(defn f [x] (+ 10 x))"
                           :prompt "newer work" :agent "alice")
        (external/commit-point! sess "v2" :agent "alice")
        (external/commit-point! sess "v1.5 was actually here" :agent "alice"
                           :target (:target r1))
        (let [ctx (git/open-ctx! dir)]
          (try
            (let [tip  (get-in (git/ensure-projected! ctx) [:refs "main"])
                  info (commit-info (:slopp.git/repo ctx) tip)]
              (testing "the retroactive marker is the newest commit (journal order)"
                (is (str/starts-with? (:message info) "v1.5 was actually here")))
              (testing "its tree is the OLD state — reconstructed, trivia-lossy"
                (let [src (blob-text (:slopp.git/repo ctx) tip "src/gp/core.clj")]
                  (is (str/includes? (str src) "(+ x 10)"))
                  (is (not (str/includes? (str src) "(+ 10 x)")))
                  (is (not (str/includes? (str src) ";; top-level trivia")))))
              (testing "re-projection returns the PINNED sha"
                (is (= tip (get-in (git/ensure-projected! ctx) [:refs "main"])))))
            (finally (git/close-ctx! ctx)))))
      (finally (api/close! sess)))))

(deftest ^:external forced-red-milestone-carries-status-trailer
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'gp.core seed)
      (api/edit-replace! sess 'gp.core 'f-t "(deftest f-t (is (= 999 (f 1))))"
                         :prompt "deliberately red" :agent "bob")
      (let [r (external/commit-point! sess "broken but important" :agent "bob"
                                 :force true)]
        (is (= :red (:status r)))
        (let [ctx (git/open-ctx! dir)]
          (try
            (let [tip (get-in (git/ensure-projected! ctx) [:refs "main"])]
              (is (str/includes? (:message (commit-info (:slopp.git/repo ctx) tip))
                                 "Slopp-Status: red")))
            (finally (git/close-ctx! ctx)))))
      (finally (api/close! sess)))))
