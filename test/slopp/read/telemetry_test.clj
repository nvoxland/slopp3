(ns slopp.read.telemetry-test
  "Cover for `slopp.read.telemetry` — the folds slopp uses to measure itself.

  Every subject here is pure, so the tests hand it a synthetic journal or call
  ring and assert on data: no session, no image, in-image and sub-millisecond.
  The fixtures deliberately carry the REAL numbers that motivated each
  measure (the turn that read 0% while a human was asleep, the 57 refusals in
  the first nine records), because a measurement whose motivating observation
  is lost is one nobody can tell is still worth taking."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.read.telemetry :as telemetry]
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

(deftest call-timing-does-not-count-a-paused-session-as-time-slopp-failed-to-use
  ;; Read from the live journal, 2026-07-25, the first nine turns that ever
  ;; carried timing: one recorded 46 calls, 224s of slopp work and 45,501s
  ;; elapsed — `:slopp-share "0%"`. Nothing was slow. The human went away
  ;; mid-turn and the turn stayed open, because rotation fires on the
  ;; WRITE-tool gate and a read-only ask folds into the next writing one.
  ;;
  ;; So the instrument's headline number read "slopp was 0% of the working
  ;; time" about a turn where slopp was most of it. `:outside-ms` is honestly
  ;; named for what it measures, but a twelve-hour gap is not the thing the
  ;; name was defending — it is a session nobody was in, and folding it into
  ;; the same bucket as agent reasoning makes both unreadable.
  (let [hour  (* 60 60 1000)
        calls [{:tool "query_slice"   :start 0                :end 1000}
               {:tool "edit_add_form" :start (+ 1000 (* 6 hour)) :end (+ 2000 (* 6 hour))}]
        t     (telemetry/call-timing calls)]
    (testing "a gap no agent could spend is IDLE, named separately"
      (is (= (* 6 hour) (:idle-ms t)))
      (is (= 0 (:outside-ms t))
          "and it does NOT also count as time slopp was not working"))
    (testing "the split stays exhaustive — now three ways"
      (is (= (:elapsed-ms t) (+ (:slopp-ms t) (:outside-ms t) (:idle-ms t)))))
    (testing "share is against ACTIVE time, so a pause cannot read as slopp being slow"
      (is (= "100%" (:slopp-share t))))
    (testing "ordinary between-call gaps are NOT idle — that is the number being defended"
      (let [t2 (telemetry/call-timing [{:tool "query_slice" :start 0    :end 100}
                                       {:tool "done"        :start 2100 :end 2200}])]
        (is (= 0 (:idle-ms t2)))
        (is (= 2000 (:outside-ms t2)))))))

(deftest refused-calls-carry-the-reason-they-bounced
  ;; The first nine real turn records: 57 refusals across 492 calls (11.6%),
  ;; 23 of them `edit_subform` and 41 of 57 writes. The RATE was actionable.
  ;; The cause was recorded nowhere, so the only advice the number could ever
  ;; support was "read that tool's contract" — which is the guess, not the
  ;; finding.
  ;;
  ;; A classification table written now would be invented from source greps
  ;; rather than derived from what actually bounces, and this codebase has
  ;; already paid for one of those (the `:positional-form-access` advisory,
  ;; withdrawn at 4-5 false positives out of 5). So carry the messages
  ;; VERBATIM and bounded, and let a later read derive the classes.
  (let [calls [{:tool "edit_subform"  :start 0  :end 10 :refused? true
                :error "no match for `(let [x 1]` in my.app.orders/place!"}
               {:tool "edit_add_form" :start 20 :end 30 :refused? true
                :error "dialect (D3): denylisted symbol used — read-string"}
               {:tool "done"          :start 40 :end 50}]
        t     (telemetry/call-timing calls)]
    (testing "each bounced call reports what it said, with the tool that said it"
      (is (= [{:tool "edit_subform"  :error "no match for `(let [x 1]` in my.app.orders/place!"}
              {:tool "edit_add_form" :error "dialect (D3): denylisted symbol used — read-string"}]
             (get-in t [:refused :samples]))))
    (testing "bounded — a turn that bounces fifty times does not carry fifty messages onto its delta"
      (let [many (map (fn [i] {:tool "edit_subform" :start i :end i
                               :refused? true :error (str "reason " i)})
                      (range 50))]
        (is (= 10 (count (get-in (telemetry/call-timing many) [:refused :samples]))))))
    (testing "and each message is truncated — a refusal can carry a whole form back"
      (let [long-one [{:tool "edit_subform" :start 0 :end 1 :refused? true
                       :error (apply str (repeat 900 "x"))}]]
        (is (= 200 (count (:error (first (get-in (telemetry/call-timing long-one)
                                                 [:refused :samples])))))
            "a bounded sample, not a payload")))
    (testing "a clean turn reports an empty vector, never an absent key"
      ;; absence would read as unmeasured — the same conflation the count and
      ;; the by-tool list already refuse to make
      (is (= [] (get-in (telemetry/call-timing [{:tool "done" :start 0 :end 1}])
                        [:refused :samples]))))))
