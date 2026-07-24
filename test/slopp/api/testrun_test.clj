(ns slopp.api.testrun-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api.testrun :as testrun]))

(deftest balance-shards-minimises-the-SLOWEST-shard-not-the-average
  ;; The external tier is 98.4% of a full_check (measured: 288s of 293s), and
  ;; a shard's cost is dominated by how many fresh IMAGES its tests boot — the
  ;; isolation the tier exists for, ~1.15s of Clojure loading each. Shards run
  ;; CONCURRENTLY, so the tier's wall time is the slowest shard, not the
  ;; average. Round-robin by index split slopp's own 402 boots [139 100 90 73]:
  ;; one shard still had 66 boots to go after the fastest had finished.
  ;; Longest-first gives [101 101 100 100] — 27% off the critical path, same
  ;; work, same cores.
  ;;
  ;; NOT the warm-pool mistake (rescheduling work into CPU that is not idle,
  ;; built end-to-end and reverted at zero gain). The idle time already exists.
  (let [mk (fn [n opens]
             (str "(ns " n " (:require [slopp.api.external :as external]\n"
                  "                    [clojure.test :refer [deftest is]]))\n"
                  (apply str (for [i (range opens)]
                               (str "(deftest ^:external t" i
                                    " (is (some? (external/open!))))\n")))))
        st (-> (store/empty-store)
               ;; the reference graph only records edges to namespaces the
               ;; store HOLDS, so the weight's target has to be in the fixture
               (store/ingest 'slopp.api.external
                             "(ns slopp.api.external)\n(defn open! \"A session.\" [] {})\n")
               (store/ingest 'bs.heavy-test (mk "bs.heavy-test" 6))
               (store/ingest 'bs.mid-test   (mk "bs.mid-test" 3))
               (store/ingest 'bs.a-test     (mk "bs.a-test" 1))
               (store/ingest 'bs.b-test     (mk "bs.b-test" 1))
               (store/ingest 'bs.c-test     (mk "bs.c-test" 1)))
        nses ['bs.a-test 'bs.b-test 'bs.c-test 'bs.heavy-test 'bs.mid-test]
        shards (testrun/balance-shards st nses 2)]
    (testing "every namespace lands exactly once — a shard split may not lose work"
      (is (= (set nses) (set (apply concat shards))))
      (is (= (count nses) (count (apply concat shards)))))
    (testing "the heaviest namespace gets a shard to itself: 6 against 3+1+1+1"
      (is (some #(= ['bs.heavy-test] %) shards)
          (str "round-robin by index would have paired it with others: "
               (pr-str shards))))
    (testing "the requested shard count is honoured even when one would be empty"
      (is (= 4 (count (testrun/balance-shards st ['bs.a-test] 4)))))
    (testing "deterministic — a split that varies between runs is unrepeatable"
      (is (= shards (testrun/balance-shards st nses 2))))))
