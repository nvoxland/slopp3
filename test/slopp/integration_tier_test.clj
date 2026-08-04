(ns slopp.integration-tier-test
  "External deps M5: the `^:integration` test tier. Fast per-write
  verification SKIPS integration tests (external-system tests — a DB dep
  shouldn't fire on every edit); explicit test_run / done / commit_point
  include them."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops] [slopp.ops.external :as external]))

(def seed
  (str "(ns it.core (:require [clojure.test :refer [deftest is]]))\n\n"
       "(def ran (atom #{}))\n\n"
       "(defn f [x] (+ x 1))\n\n"
       "(deftest unit-t (swap! ran conj :unit) (is (= 2 (f 1))))\n\n"
       ;; metadata on the test NAME reliably tags the var
       "(deftest ^:integration integ-t (swap! ran conj :integ) (is (= 2 (f 1))))\n"))

(defn- ran [sess]
  (first (ops/query-eval sess "@it.core/ran")))

(deftest ^:external integration-tier-skips-fast-path-includes-explicit
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'it.core seed)
      (testing "a fast-path EDIT runs unit tests but SKIPS integration"
        (ops/query-eval sess "(reset! it.core/ran #{})")
        (ops/edit-replace! sess 'it.core 'f "(defn f [x] (+ 1 x))"
                           :prompt "flip" :agent "a")
        (let [r (ran sess)]
          (is (contains? r :unit) (pr-str r))
          (is (not (contains? r :integ)) (pr-str r))))
      (testing "explicit test_run INCLUDES integration"
        (ops/query-eval sess "(reset! it.core/ran #{})")
        (ops/test-run! sess 'it.core)
        (let [r (ran sess)]
          (is (contains? r :unit))
          (is (contains? r :integ) (pr-str r))))
      (testing "a failing integration test does NOT block a fast edit"
        (ops/edit-replace! sess 'it.core 'integ-t
                           "(deftest ^:integration integ-t (swap! ran conj :integ) (is (= 999 (f 1))))"
                           :prompt "break integ" :agent "a")
        (let [res (ops/edit-replace! sess 'it.core 'f "(defn f [x] (+ x 1))"
                                     :prompt "edit while integ red" :agent "a")
              t   (:test res)]
          (is (zero? (+ (:fail t 0) (:error t 0))) (pr-str t))))
      (finally (ops/close! sess)))))
