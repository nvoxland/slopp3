(ns slopp.git-import-test
  "P4-m8 M3: git push INTO slopp. The net content change lands as ONE
  verified edit group (new files as ingests); every incoming git commit is
  preserved as a :commit marker carrying its original sha (:git-sha) — never
  re-projected, so pushed shas survive re-clone. Red tests land honestly
  (:status :red); only compile failures and structural violations reject,
  with the reason delivered to the pusher."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [slopp.api :as api]
            [slopp.git :as git]
            [slopp.store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.eclipse.jgit.api Git ResetCommand$ResetType]
           [org.eclipse.jgit.transport PushResult RefSpec RemoteRefUpdate]))

(defn- temp-dir [nm]
  (str (Files/createTempDirectory nm (make-array FileAttribute 0))))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(def seed
  (str "(ns gi.core (:require [clojure.test :refer [deftest is]]))\n"
       "\n"
       "(defn f [x] (+ x 10))\n"
       "\n"
       "(defn g [x] (* x 2))\n"
       "\n"
       "(deftest f-t (is (= 11 (f 1))))\n"))

(defn- setup! []
  (let [dir  (temp-dir "slopp-git-import")
        sess (api/open! {:dir dir})
        port (free-port)
        srv  (git/start-server! port {:dir dir})]
    (api/ingest! sess 'gi.core seed)
    (api/commit-point! sess "v1: seed" :agent "alice")
    {:dir dir :sess sess :port port :srv srv
     :url (str "http://127.0.0.1:" port "/slopp.git")}))

(defn- teardown! [{:keys [sess srv]}]
  (git/stop-server! srv)
  (api/close! sess))

(defn- clone! [{:keys [url]} to]
  (-> (Git/cloneRepository) (.setURI url) (.setDirectory (io/file to)) (.call)))

(defn- edit-file! [clone-dir path from to]
  (let [f (io/file clone-dir path)]
    (spit f (str/replace (slurp f) from to))))

(defn- commit! [^Git g msg]
  (-> g (.add) (.addFilepattern ".") (.call))
  (-> g (.commit) (.setMessage msg)
      (.setAuthor "Dana Dev" "dana@example.com")
      (.setCommitter "Dana Dev" "dana@example.com")
      (.setSign false)
      (.call)))

(defn- push!
  "Push and return {:status kw :message} of the first remote ref update."
  [^Git g & {:keys [refspec force]}]
  (let [cmd (cond-> (.push g)
              refspec (.setRefSpecs
                       ^java.util.List (java.util.List/of (RefSpec. refspec)))
              force   (.setForce true))
        ^PushResult pr (first (.call cmd))
        ^RemoteRefUpdate upd (first (.getRemoteUpdates pr))]
    {:status  (keyword (str (.getStatus upd)))
     :message (.getMessage upd)}))

(defn- reset-hard! [^Git g]
  (-> g (.reset) (.setMode ResetCommand$ResetType/HARD)
      (.setRef "origin/main") (.call)))

(defn- remote-main-sha [{:keys [url]}]
  (some #(when (= "refs/heads/main" (.getName %)) (.name (.getObjectId %)))
        (-> (Git/lsRemoteRepository) (.setRemote url) (.setHeads true) (.call))))

(defn- commit-markers [sess]
  (filterv #(= :commit (:op %)) (store/deltas (:store @sess))))

(deftest push-imports-group-markers-and-honest-status
  (let [{:keys [sess srv] :as env} (setup!)
        clone-dir (temp-dir "slopp-import-clone")]
    (try
      (with-open [g (clone! env clone-dir)]
        (let [groups-before (count (distinct (keep :group (store/deltas (:store @sess)))))]
          (edit-file! clone-dir "src/gi/core.clj"
                      "(defn f [x] (+ x 10))" "(defn f [x] (+ 10 x))")
          (commit! g "flip f arg order")
          (edit-file! clone-dir "src/gi/core.clj"
                      "(defn g [x] (* x 2))" "(defn g [x] (* 2 x))")
          (commit! g "flip g arg order")
          (let [pushed-tip (.name (.resolve (.getRepository g) "HEAD"))
                r (push! g)]
            (is (= :OK (:status r)) (pr-str r))
            (api/sync-with-journal! sess)
            (testing "the store took the net change"
              (is (str/includes? (api/query-source sess 'gi.core) "(+ 10 x)"))
              (is (str/includes? (api/query-source sess 'gi.core) "(* 2 x)")))
            (testing "ONE new edit group for the whole push"
              (is (= (inc groups-before)
                     (count (distinct (keep :group (store/deltas (:store @sess))))))))
            (testing "each incoming commit is a marker with its original sha"
              (let [[m1 m2] (->> (commit-markers sess) (drop 1) vec)]
                (is (= "flip f arg order" (:description m1)))
                (is (= "flip g arg order" (:description m2)))
                (is (= pushed-tip (:git-sha m2)))
                (is (= "git:dana@example.com" (:agent m1)))
                (is (= "Dana Dev <dana@example.com>" (:git-author m1)))
                (is (nil? (:tree m1)))     ; imports are never re-projected
                (is (every? #(= :green (:status %)) [m1 m2]))))
            (testing "pushed shas survive a fresh clone (identity preserved)"
              (is (= pushed-tip (remote-main-sha env)))
              (is (= pushed-tip (:sha (first (api/query-commits sess)))))
              (let [again (temp-dir "slopp-import-reclone")]
                (with-open [g2 (clone! env again)]
                  (is (= pushed-tip
                         (.name (.resolve (.getRepository g2) "HEAD")))))))
            (testing "a push that turns tests red LANDS, recorded honestly"
              (edit-file! clone-dir "src/gi/core.clj"
                          "(deftest f-t (is (= 11 (f 1))))"
                          "(deftest f-t (is (= 999 (f 1))))")
              (commit! g "break the expectation")
              (let [r2 (push! g)]
                (is (= :OK (:status r2)) (pr-str r2))
                (api/sync-with-journal! sess)
                (let [m (last (commit-markers sess))]
                  (is (= "break the expectation" (:description m)))
                  (is (= :red (:status m))))))
            (testing "wiped mapping rows repair from the :git-sha markers"
              (jdbc/execute! (:map-conn (:ctx srv))
                             ["DELETE FROM git_map WHERE sha = ?" pushed-tip])
              (git/ensure-projected! (:ctx srv))
              (is (= 1 (:n (jdbc/execute-one!
                            (:map-conn (:ctx srv))
                            ["SELECT COUNT(*) AS n FROM git_map WHERE sha = ?"
                             pushed-tip]))))))))
      (finally (teardown! env)))))

(deftest push-new-files-ingest-in-dependency-order
  (let [{:keys [sess] :as env} (setup!)
        clone-dir (temp-dir "slopp-newfile-clone")]
    (try
      (with-open [g (clone! env clone-dir)]
        ;; extra.clj sorts BEFORE util.clj alphabetically but REQUIRES it —
        ;; naive file order would fail the compile gate
        (spit (doto (io/file clone-dir "src" "gi" "util.clj")
                (-> .getParentFile .mkdirs))
              "(ns gi.util)\n\n(defn helper [x] (inc x))\n")
        (spit (io/file clone-dir "src" "gi" "extra.clj")
              (str "(ns gi.extra (:require [gi.util :as u]))\n\n"
                   "(defn h2 [x] (u/helper x))\n"))
        (commit! g "add util + extra namespaces")
        (let [r (push! g)]
          (is (= :OK (:status r)) (pr-str r))
          (api/sync-with-journal! sess)
          (is (str/includes? (api/query-source sess 'gi.util) "helper"))
          (is (str/includes? (api/query-source sess 'gi.extra) "u/helper"))
          (is (= "add util + extra namespaces"
                 (:description (last (commit-markers sess)))))))
      (finally (teardown! env)))))

(deftest push-rejections-deliver-reasons
  (let [{:keys [sess] :as env} (setup!)
        clone-dir (temp-dir "slopp-reject-clone")]
    (try
      (with-open [g (clone! env clone-dir)]
        (let [tip-before (remote-main-sha env)]
          (testing "a compile error rejects the WHOLE push, store untouched"
            (edit-file! clone-dir "src/gi/core.clj"
                        "(defn f [x] (+ x 10))"
                        "(defn f [x] (undefined-fn x))")
            (commit! g "broken code")
            (let [r (push! g)]
              (is (= :REJECTED_OTHER_REASON (:status r)) (pr-str r))
              (api/sync-with-journal! sess)
              (is (str/includes? (api/query-source sess 'gi.core) "(+ x 10)"))
              (is (= tip-before (remote-main-sha env)))
              (is (= 1 (count (commit-markers sess)))))
            (reset-hard! g))
          (testing "non-clj files are rejected"
            (spit (io/file clone-dir "README.md") "# hello\n")
            (commit! g "add readme")
            (let [r (push! g)]
              (is (= :REJECTED_OTHER_REASON (:status r)))
              (is (str/includes? (str (:message r)) "src/")))
            (reset-hard! g)
            (.delete (io/file clone-dir "README.md")))
          (testing "file deletion is rejected"
            (-> g (.rm) (.addFilepattern "src/gi/core.clj") (.call))
            (commit! g "delete everything")
            (let [r (push! g)]
              (is (= :REJECTED_OTHER_REASON (:status r)))
              (is (str/includes? (str (:message r)) "deletion")))
            (reset-hard! g))
          (testing "ns-declaration changes are rejected"
            (edit-file! clone-dir "src/gi/core.clj"
                        "(ns gi.core (:require [clojure.test :refer [deftest is]]))"
                        "(ns gi.core (:require [clojure.test :refer [deftest is]] [clojure.string :as s]))")
            (commit! g "sneak a require in")
            (let [r (push! g)]
              (is (= :REJECTED_OTHER_REASON (:status r)))
              (is (str/includes? (str (:message r)) "ns declaration")))
            (reset-hard! g))
          (testing "anonymous top-level forms are rejected"
            (edit-file! clone-dir "src/gi/core.clj"
                        "(defn g [x] (* x 2))"
                        "(defn g [x] (* x 2))\n\n(declare zz)")
            (commit! g "anonymous form")
            (let [r (push! g)]
              (is (= :REJECTED_OTHER_REASON (:status r)))
              (is (str/includes? (str (:message r)) "anonymous")))
            (reset-hard! g))
          (testing "a force push (rewriting PUSHED history) is refused"
            (edit-file! clone-dir "src/gi/core.clj"
                        "(defn g [x] (* x 2))" "(defn g [x] (* 4 x))")
            (commit! g "rewrite candidate")
            (is (= :OK (:status (push! g))))       ; lands on the remote…
            (-> g (.commit) (.setAmend true) (.setMessage "amended!")
                (.setAuthor "Dana Dev" "dana@example.com")
                (.setCommitter "Dana Dev" "dana@example.com")
                (.setSign false) (.call))          ; …then gets rewritten
            (let [r (push! g :force true)]
              (is (not= :OK (:status r)) (pr-str r)))
            (reset-hard! g))
          (testing "a form slopp changed since the clone rejects as stale"
            (api/edit-replace! sess 'gi.core 'f "(defn f [x] (+ x 10 0))"
                               :prompt "concurrent slopp work" :agent "alice")
            (edit-file! clone-dir "src/gi/core.clj"
                        "(defn f [x] (+ x 10))" "(defn f [x] (+ 10 x))")
            (commit! g "conflicting edit")
            (let [r (push! g)]
              (is (= :REJECTED_OTHER_REASON (:status r)))
              (is (str/includes? (str (:message r)) "moved"))))))
      (finally (teardown! env)))))

(deftest push-to-branch-lands-in-branch-journal
  (let [{:keys [sess] :as env} (setup!)
        clone-dir (temp-dir "slopp-branch-clone")]
    (try
      (api/branch! sess "feature")
      (api/edit-replace! sess 'gi.core 'g "(defn g [x] (* x 3))"
                         :prompt "feature work" :agent "alice")
      (api/commit-point! sess "feature: triple" :agent "alice")
      (let [main-before (remote-main-sha env)]
        (with-open [g (clone! env clone-dir)]
          (-> g (.checkout) (.setCreateBranch true) (.setName "feature")
              (.setStartPoint "origin/feature") (.call))
          (edit-file! clone-dir "src/gi/core.clj"
                      "(defn g [x] (* x 3))" "(defn g [x] (* 3 x))")
          (commit! g "flip triple")
          (let [r (push! g :refspec "refs/heads/feature:refs/heads/feature")]
            (is (= :OK (:status r)) (pr-str r))
            (api/sync-with-journal! sess)   ; sess is checked out on feature
            (is (str/includes? (api/query-source sess 'gi.core) "(* 3 x)"))
            (is (= "flip triple" (:description (last (commit-markers sess)))))
            (testing "main is untouched"
              (is (= main-before (remote-main-sha env)))))))
      (finally (teardown! env)))))
