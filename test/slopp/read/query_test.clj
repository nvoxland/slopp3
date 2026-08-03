(ns slopp.read.query-test
  "query-brief / query-impact honour declared (^{:covers}) coverage — the
   dispatch/data path the trace and static reach can't see — the same way
   review-scan and affected-tests do. In-image because declared coverage is
   static (no live trace needed)."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.read.query :as query]))

(defn- covers-store []
  (-> (store/empty-store)
      (store/ingest 'qi.core "(ns qi.core)\n(defn target [x] x)\n")
      (store/ingest 'qi.core-test
                    (str "(ns qi.core-test (:require [clojure.test :refer [deftest is]]))\n"
                         "(deftest ^{:covers \"qi.core/target — via dispatch\"} cover-t (is true))\n"))))

(deftest query-impact-reports-declared-coverage
  ;; query-impact's :covered-by is the canonical covered-by, filtered to the
  ;; tests that EXERCISE or CLAIM the form (observed ∪ declared). A ^{:covers}
  ;; test names a dispatch path the trace never records, so it must appear.
  (let [sess (atom {:store (covers-store) :test-map {}})
        r    (query/query-impact sess 'qi.core 'target)]
    (is (= {:count 1 :tests ['qi.core-test/cover-t]} (:covered-by r)) (pr-str r))))

(deftest query-brief-honours-declared-coverage
  ;; query-brief must not call a ^{:covers}-declared form :untested, and the
  ;; declaring test surfaces in :reached-by tagged :via #{:declared} (no hops —
  ;; a claim, not a measured reach).
  (let [;; a non-empty trace map so the :untested branch is live (it needs
        ;; SOME evidence in the session before it will flag anything)
        sess (atom {:store (covers-store) :test-map {'unrelated/t #{'unrelated/x}}})
        b    (query/query-brief sess 'qi.core 'target)]
    (testing "a declared-covered form is not untested"
      (is (not (:untested b)) (pr-str b)))
    (testing "the declaring test is reported in :reached-by via :declared"
      (is (= [{:test 'qi.core-test/cover-t :via #{:declared}}] (:reached-by b))
          (pr-str b)))))
