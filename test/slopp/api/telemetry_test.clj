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

(deftest call-timing-counts-the-calls-that-were-REFUSED
  ;; The measurement nobody had taken. 78% of a session's wall clock is spent
  ;; outside slopp — agent reasoning and non-slopp tools — and the largest
  ;; identifiable waste in that half is calls that get refused and retried: a
  ;; malformed edit_subform match, a lint error in a form being written, an
  ;; arity break. Each costs a full round trip and none was ever counted.
  ;;
  ;; Reported as a RATE with the tools named, because the actionable form is
  ;; "a fifth of your writes bounced, mostly on edit_subform" — a raw count
  ;; says nothing about whether it is worth changing anything.
  (let [calls [{:tool "query_slice"    :start 0    :end 50}
               {:tool "edit_subform"   :start 100  :end 200 :refused? true}
               {:tool "edit_subform"   :start 300  :end 400 :refused? true}
               {:tool "edit_subform"   :start 500  :end 600}
               {:tool "edit_add_form"  :start 700  :end 900 :refused? true}
               {:tool "done"           :start 1000 :end 3000}]
        t (telemetry/call-timing calls)]
    (testing "the rate, and the tools that bounced"
      (is (= 3 (get-in t [:refused :count])))
      (is (= 50 (get-in t [:refused :pct])) "3 of 6 calls")
      (is (= [{:tool "edit_subform" :n 2} {:tool "edit_add_form" :n 1}]
             (get-in t [:refused :by-tool]))
          "largest first — the one to fix is the one that bounces most"))
    (testing "refused calls still count toward the time they cost"
      (is (= 3 (count (filter #(= "edit_subform" (:tool %)) calls)))
          "fixture sanity")
      (is (= 300 (:ms (first (filter #(= "edit_subform" (:tool %)) (:top t)))))
          "100 + 100 + 100 — a bounced call costs its wall time like any other"))
    (testing "a clean turn says so with a zero, not by omitting the key"
      ;; absence would read as unmeasured, which is the conflation this
      ;; codebase keeps paying for
      (let [clean (telemetry/call-timing [{:tool "done" :start 0 :end 10}])]
        (is (= 0 (get-in clean [:refused :count])))
        (is (= [] (get-in clean [:refused :by-tool])))))))
