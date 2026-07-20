(ns slopp.git-concurrency-test
  "P4-m8 M4: the projection's cross-process safety is mechanism, not luck —
  content-addressed object writes are idempotent, git_map pins first-writer
  via INSERT OR IGNORE + read-back, ref updates CAS (a lost race re-reads
  and finds the ref already where it wanted it). Two independent projection
  contexts over one store dir stand in for two server processes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [slopp.api :as api]
            [slopp.git :as git] [slopp.git.server :as server] [clojure.java.io :as io] [slopp.api.external :as external])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.eclipse.jgit.api Git]))

(defn- temp-dir [nm]
  (str (Files/createTempDirectory nm (make-array FileAttribute 0))))

(def seed
  (str "(ns gc.core (:require [clojure.test :refer [deftest is]]))\n"
       "\n"
       "(defn f [x] (+ x 10))\n"
       "\n"
       "(deftest f-t (is (= 11 (f 1))))\n"))

(deftest ^:external concurrent-projection-converges
  (let [dir  (temp-dir "slopp-git-conc")
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'gc.core seed)
      (external/commit-point! sess "v1" :agent "alice")
      (api/edit-replace! sess 'gc.core 'f "(defn f [x] (+ 10 x))"
                         :prompt "flip" :agent "alice")
      (external/commit-point! sess "v2" :agent "alice")
      (api/edit-replace! sess 'gc.core 'f "(defn f [x] (int (+ 10 x)))"
                         :prompt "tighten" :agent "alice")
      (external/commit-point! sess "v3" :agent "alice")
      ;; two ctxs = two processes: separate repo handles, conns, locks
      (let [ctx1 (git/open-ctx! dir)
            ctx2 (git/open-ctx! dir)]
        (try
          (let [f1 (future (git/ensure-projected! ctx1))
                f2 (future (git/ensure-projected! ctx2))
                r1 (deref f1 60000 ::timeout)
                r2 (deref f2 60000 ::timeout)]
            (is (map? r1))
            (is (map? r2))
            (testing "both projectors minted the SAME tip (determinism)"
              (is (= (get-in r1 [:refs "main"])
                     (get-in r2 [:refs "main"])))
              (is (some? (get-in r1 [:refs "main"]))))
            (testing "one mapping row per marker despite the race"
              (is (= 3 (:n (jdbc/execute-one!
                            (:slopp.git/map-conn ctx1)
                            ["SELECT COUNT(*) AS n FROM git_map"])))))
            (testing "re-projection on either side is a stable no-op"
              (is (= (get-in r1 [:refs "main"])
                     (get-in (git/ensure-projected! ctx2) [:refs "main"])))))
          (finally
            (git/close-ctx! ctx1)
            (git/close-ctx! ctx2))))
      (finally (api/close! sess)))))

(deftest ^:external foreign-milestone-served-without-restart
  ;; the m5b operating model: another agent's server shares the store dir;
  ;; its milestones must be served by the git server with NO restart —
  ;; projection reads the journals from disk on every advertisement
  (let [dir   (temp-dir "slopp-git-foreign")
        sess1 (external/open! {:slopp.api/dir dir})
        
        
        ;; 0 = the OS assigns a genuinely-free loopback port ATOMICALLY (#136).
        ;; free-port guessed one BEFORE binding, and its wildcard socket never
        ;; conflicted with 127.0.0.1 — so it could hand back a port another
        ;; shard's server was serving. bind-localhost! then relocated silently
        ;; and the caller talked to the abandoned port.
        srv   (server/start-server! 0 {:dir dir})
        url   (:url srv)
        tip   (fn []
                (some #(when (= "refs/heads/main" (.getName %))
                         (.name (.getObjectId %)))
                      (-> (Git/lsRemoteRepository) (.setRemote url)
                          (.setHeads true) (.call))))]
    (try
      (api/ingest! sess1 'gc.core seed)
      (external/commit-point! sess1 "v1" :agent "alice")
      (let [tip1 (tip)]
        (is (some? tip1))
        ;; a SECOND session on the same dir — a foreign writer
        (let [sess2 (external/open! {:slopp.api/dir dir})]
          (try
            (api/edit-replace! sess2 'gc.core 'f "(defn f [x] (+ 10 x))"
                               :prompt "foreign work" :agent "bob")
            (external/commit-point! sess2 "v2: foreign milestone" :agent "bob")
            (finally (api/close! sess2))))
        (let [tip2 (tip)]
          (is (not= tip1 tip2))
          (let [clone-dir (temp-dir "slopp-git-foreign-clone")]
            (with-open [g (-> (Git/cloneRepository) (.setURI url)
                              (.setDirectory (io/file clone-dir))
                              (.call))]
              (is (str/starts-with?
                   (.getFullMessage (first (-> g (.log) (.call))))
                   "v2: foreign milestone"))))))
      (finally
        (server/stop-server! srv)
        (api/close! sess1)))))
