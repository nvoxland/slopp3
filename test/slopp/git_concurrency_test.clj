(ns slopp.git-concurrency-test
  "P4-m8 M4: the projection's cross-process safety is mechanism, not luck —
  content-addressed object writes are idempotent, git_map pins first-writer
  via INSERT OR IGNORE + read-back, ref updates CAS (a lost race re-reads
  and finds the ref already where it wanted it). Two independent projection
  contexts over one store dir stand in for two server processes."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [slopp.ops :as ops]
            [slopp.git :as git]
            [slopp.ops.external :as external])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

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
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'gc.core seed)
      (external/commit-point! sess "v1" :agent "alice")
      (ops/edit-replace! sess 'gc.core 'f "(defn f [x] (+ 10 x))"
                         :prompt "flip" :agent "alice")
      (external/commit-point! sess "v2" :agent "alice")
      (ops/edit-replace! sess 'gc.core 'f "(defn f [x] (int (+ 10 x)))"
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
      (finally (ops/close! sess)))))

(deftest ^:external foreign-milestone-projected-without-restart
  ;; the m5b operating model: another agent's server shares the store dir, and
  ;; its milestones must reach the projection with NO restart — projection
  ;; re-reads the journals from disk every time rather than trusting a cache
  ;; built when the context opened.
  ;;
  ;; This used to observe the property by cloning from the git listener, which
  ;; is gone. The listener was only the instrument; the property is the
  ;; projection's, and asserting it against a FRESHLY opened context is a
  ;; stronger check than the old commit-message prefix — it compares the
  ;; long-lived context to ground truth rather than to a string.
  (let [dir   (temp-dir "slopp-git-foreign")
        sess1 (external/open! {:slopp.ops/dir dir})
        ctx   (git/open-ctx! dir)
        tip   (fn [c] (get-in (git/ensure-projected! c) [:refs "main"]))]
    (try
      (ops/ingest! sess1 'gc.core seed)
      (external/commit-point! sess1 "v1" :agent "alice")
      (let [tip1 (tip ctx)]
        (is (some? tip1))
        ;; a SECOND session on the same dir — a foreign writer
        (let [sess2 (external/open! {:slopp.ops/dir dir})]
          (try
            (ops/edit-replace! sess2 'gc.core 'f "(defn f [x] (+ 10 x))"
                               :prompt "foreign work" :agent "bob")
            (external/commit-point! sess2 "v2: foreign milestone" :agent "bob")
            (finally (ops/close! sess2))))
        (let [tip2  (tip ctx)
              fresh (let [c2 (git/open-ctx! dir)]
                      (try (tip c2) (finally (git/close-ctx! c2))))]
          (testing "the long-lived context advanced past the pre-foreign tip"
            (is (not= tip1 tip2)))
          (testing "and it agrees with a context opened after the foreign write"
            (is (= fresh tip2)))))
      (finally
        (git/close-ctx! ctx)
        (ops/close! sess1)))))
