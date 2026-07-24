(ns slopp.api.telemetry-test
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.api.telemetry :as telemetry]
            [slopp.store :as store]))

(deftest rule-telemetry-fire-rate-and-persistence
  (let [[s1 _] (store/record-done (store/empty-store) "d1"
                                  :findings {:key-typos [{:used :a/emial :suggest :a/email}]
                                             :test-status :green})
        [s2 _] (store/record-done s1 "d2"
                                  :findings {:key-typos [{:used :a/emial :suggest :a/email}]
                                             :unused-public ['app.core/x]})
        [s3 _] (store/record-done s2 "d3" :findings {:test-status :green})
        t      (telemetry/rule-telemetry s3)]
    (testing "fire-rate: dones-fired + total instances per rule"
      (is (= 3 (get-in t [:window :dones])))
      (is (= 2 (get-in t [:fire-rate :key-typos :dones])))
      (is (= 2 (get-in t [:fire-rate :key-typos :instances])))
      (is (= 1 (get-in t [:fire-rate :unused-public :dones]))))
    (testing "persistence: the same instance flagged across >1 done is un-discharged"
      (is (= 1 (get-in t [:fire-rate :key-typos :persisted])))
      (is (= 0 (get-in t [:fire-rate :unused-public :persisted]))))
    (testing "metadata finding keys (test-status) are not counted as rule fires"
      (is (nil? (get-in t [:fire-rate :test-status]))))))

(deftest call-timing-splits-a-turn-into-slopp-and-everything-else
  ;; Measured over a real session: 1,703s elapsed, 390s of it recorded as
  ;; verification — 22%. The other 78% was invisible, and "invisible" was the
  ;; whole problem: you cannot tell an agent that reasons slowly from a tool
  ;; that runs slowly if only one of them is instrumented.
  ;;
  ;; The server sees both edges. It knows when a call arrived and when it
  ;; answered, so the gap BETWEEN calls is time slopp was not working. That is
  ;; deliberately not called "thinking time": it is agent reasoning plus every
  ;; non-slopp tool (file reads, shell, subagents) plus the harness, and the
  ;; server cannot tell them apart. Naming it for what it measures is the
  ;; point — P7 says the cost of leaving slopp is invisible unless written
  ;; down, and this is where it lands.
  (let [calls [{:tool "query_slice" :start 1000 :end 1050}
               {:tool "edit_add_form" :start 3050 :end 3550}
               {:tool "done" :start 5550 :end 9550}]]
    (testing "the two halves and their total"
      (let [t (telemetry/call-timing calls)]
        (is (= 3 (:calls t)))
        (is (= 4550 (:slopp-ms t)) "50 + 500 + 4000")
        (is (= 4000 (:outside-ms t)) "2000 between call 1 and 2, 2000 between 2 and 3")
        (is (= 8550 (:elapsed-ms t)) "first arrival to last answer")
        (is (= (:elapsed-ms t) (+ (:slopp-ms t) (:outside-ms t)))
            "the split must be exhaustive — an unexplained remainder is the bug")))
    (testing "the tools that actually cost, largest first"
      (let [top (:top (telemetry/call-timing calls))]
        (is (= "done" (:tool (first top))))
        (is (= 4000 (:ms (first top))))
        (is (= 1 (:n (first top))))))
    (testing "repeated calls of one tool aggregate"
      (let [t (telemetry/call-timing [{:tool "test_run" :start 0 :end 100}
                                      {:tool "test_run" :start 100 :end 300}])]
        (is (= [{:tool "test_run" :n 2 :ms 300}] (:top t)))
        (is (zero? (:outside-ms t)))))
    (testing "no calls is nil, not a zeroed record that reads as measured"
      (is (nil? (telemetry/call-timing [])))
      (is (nil? (telemetry/call-timing nil))))))
