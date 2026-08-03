(ns slopp.ops.testrun-test
  "Cover for the external runner, split along the line that matters: the
  shard ARITHMETIC is pure and cheap to test, the PROCESS ownership is
  neither.

  So the arithmetic tests (how many shards, which namespaces in each, how
  summaries merge) run in-image on data, and the process tests spawn a real
  tree and assert on real PIDs — because the failure they guard against is
  one you cannot model. A test that pretends to kill a process proves nothing
  about whether the orphans died."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.ops.testrun :as testrun]))

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
             (str "(ns " n " (:require [slopp.ops.external :as external]\n"
                  "                    [clojure.test :refer [deftest is]]))\n"
                  (apply str (for [i (range opens)]
                               (str "(deftest ^:external t" i
                                    " (is (some? (external/open!))))\n")))))
        st (-> (store/empty-store)
               ;; the reference graph only records edges to namespaces the
               ;; store HOLDS, so the weight's target has to be in the fixture
               (store/ingest 'slopp.ops.external
                             "(ns slopp.ops.external)\n(defn open! \"A session.\" [] {})\n")
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

(deftest only-shards-splits-a-narrowed-run-along-namespace-lines
  ;; `done` defers its external tests on 37.5% of calls, measured over the
  ;; last 40. Six of those fifteen deferrals were 52–136 tests — sets the
  ;; trace map identified correctly and the RUNNER could not act on, because
  ;; narrowing and sharding were mutually exclusive: with :only set, full-set
  ;; is nil, par is 1, and the run is one serial JVM. Deferring was the
  ;; least-bad option available, not a judgement about safety.
  ;;
  ;; A namespace cannot straddle two shards: the command needs -n per
  ;; namespace alongside -v per var, and cognitect resolves a named var only
  ;; within a DISCOVERED namespace.
  (let [mk (fn [n opens]
             (str "(ns " n " (:require [slopp.ops.external :as external]\n"
                  "                    [clojure.test :refer [deftest is]]))\n"
                  (apply str (for [i (range opens)]
                               (str "(deftest ^:external t" i
                                    " (is (some? (external/open!))))\n")))))
        st (-> (store/empty-store)
               (store/ingest 'slopp.ops.external
                             "(ns slopp.ops.external)\n(defn open! \"A session.\" [] {})\n")
               (store/ingest 'os.heavy-test (mk "os.heavy-test" 6))
               (store/ingest 'os.light-test (mk "os.light-test" 1)))
        only '[os.heavy-test/t0 os.heavy-test/t1 os.light-test/t0]
        shards (testrun/only-shards st only 2)]
    (testing "every named test lands exactly once"
      (is (= (set only) (set (apply concat shards))))
      (is (= (count only) (count (apply concat shards)))))
    (testing "a namespace never straddles shards — -v resolves only within -n"
      (doseq [sh shards]
        (is (apply = (map #(namespace (symbol (str %))) sh))
            (str "shard mixes namespaces: " (pr-str sh)))))
    (testing "shards are weighted by image boots, like every other split"
      (is (some #(= 1 (count %)) shards)
          (str "the 6-boot namespace should stand alone: " (pr-str shards))))
    (testing "fewer namespaces than shards yields no empty shards to run"
      (is (every? seq (testrun/only-shards st '[os.light-test/t0] 4))))))

(deftest ^:external killing-a-runner-kills-the-processes-it-spawned
  ;; The incident: two full-suite runs timed out CLIENT-side, the server-side
  ;; shard JVMs kept executing, and each kept spawning a fresh per-test image
  ;; JVM every few seconds. Load average hit 20+ and even a trivial
  ;; query_search hung. Recovery was manual pkill.
  ;;
  ;; `run-cmd!` was already bounded and already called .destroy — but destroy
  ;; reaches the child ONLY. A test-runner JVM's whole job is to spawn image
  ;; JVMs, so killing it without its subtree leaves exactly the processes that
  ;; do the damage, now orphaned and unreachable by anything.
  ;;
  ;; The ORDER matters and is asserted below: kill the parent FIRST so it
  ;; stops spawning more, then sweep what it already started.
  (let [pb   (doto (ProcessBuilder. ["bash" "-c" "sleep 30 & sleep 30"])
               (.redirectErrorStream true))
        proc (.start pb)]
    (try
      (Thread/sleep 500)                          ; let the child spawn its own
      (let [kids (vec (.toList (.descendants (.toHandle proc))))]
        (testing "the fixture really does have a subtree, or this proves nothing"
          (is (pos? (count kids))
              "no descendants spawned — the fixture is not exercising the bug"))
        (testrun/reap! proc)
        (testing "the parent is dead"
          (is (not (.isAlive proc))))
        (testing "and so is everything it spawned, BY THE TIME reap! RETURNS —
                  no sleep here on purpose. Signal delivery is asynchronous, so
                  this assertion used to be a race that a loaded machine lost:
                  green in isolation, red under four shards. Waiting here would
                  have hidden the bug instead of naming it; reap! waits now."
          (is (every? #(not (.isAlive %)) kids)
              (str "orphans survived: "
                   (pr-str (mapv #(.pid %) (filter #(.isAlive %) kids)))))))
      (finally
        (.destroyForcibly proc)))))
