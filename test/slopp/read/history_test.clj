(ns slopp.read.history-test
  "The pure FOLDS under the store's history reads — the milestone rows behind
  `query_commits`, and the join of provenance, verification and cost behind
  form effort.

  Tested at the fold rather than through the tool on purpose: a fold over the
  delta log has a value-in / value-out contract, and the tool wrapping it adds
  only shape. When one of these breaks, the failure should name the
  derivation, not the endpoint that happened to ask."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.read.history :as history]
            [slopp.store :as store]))

(deftest milestone-rows-is-the-pure-fold-behind-query-commits
  ;; query-commits is :external because it opens the db to join git shas
  ;; from the projection's pinning table. The FOLD underneath is a pure
  ;; read of the delta log, and that is what a pure reader (the reviewer
  ;; UI's timeline) needs — so it lives here, at :pure, and query-commits
  ;; is that fold plus the sha join. One producer, two tiers.
  (let [st (assoc (store/empty-store)
                  :deltas
                  [{:id "d1" :op :add :form-id "f1"}
                   {:id "d2" :op :commit :target "d1" :status :green :at 1784900000000
                    :description "first milestone\n\nwith a body\nand another line"}
                   {:id "d3" :op :add :form-id "f2"}
                   {:id "d4" :op :commit :target "d3" :status :green :at 1784900060000
                    :description "second milestone" :agent "ada" :git-sha "abc123"}])]
    (testing "newest first — the order a reader scans"
      (is (= ["d4" "d2"] (mapv :commit (history/milestone-rows st)))))
    (testing "the full row carries what a milestone IS"
      (let [r (first (history/milestone-rows st))]
        (is (= "second milestone" (:description r)))
        (is (= "d3" (:target r)) "the target plugs into query-changes as a range end")
        (is (= :green (:status r)))
        (is (= "ada" (:agent r)))
        (is (re-find #"^\d{4}-\d{2}-\d{2} " (:at r)) "the timestamp is human, not epoch ms")))
    (testing "a sha the DELTA carries needs no db — that is what keeps this pure"
      (is (= "abc123" (:sha (first (history/milestone-rows st)))))
      (is (nil? (:sha (second (history/milestone-rows st))))
          "absent, not blank — a milestone the projection has not minted yet"))
    (testing "titles-only is the LIST rung: one line, with the body COUNTED not dropped"
      (let [[newest oldest] (history/milestone-rows st :titles-only true)]
        (is (= "second milestone" (:description newest)))
        (is (nil? (:more-lines newest)) "nothing more to read — absent, not zero")
        (is (= "first milestone" (:description oldest)))
        (is (= 2 (:more-lines oldest))
            "blank lines don't count; needing one sha should not fetch five essays")))
    (testing "a log with no milestones is empty, not nil"
      (is (= [] (history/milestone-rows (store/empty-store)))))))

(deftest form-effort-joins-provenance-verification-and-cost
  ;; The semantic × history combination, applied to the question the journal
  ;; can answer and a git log cannot: *what did it cost to get this form
  ;; green?* Versions and red→green cycles come from the whole lifetime; the
  ;; wall-clock only from deltas written after verification started recording
  ;; `:ms`.
  ;;
  ;; That gap is the interesting part. A bare sum would read as "this form cost
  ;; 480ms" when it means "the three versions we measured cost 480ms", so the
  ;; result carries its own coverage.
  (let [versions [{:delta "d1" :status :red   :prompt "first cut"     :ms 10}
                  {:delta "d2" :status :red   :prompt "fix the arity" :ms 20}
                  {:delta "d3" :status :green :prompt "fix the arity"}
                  {:delta "d4" :status :green :prompt "tidy"}
                  {:delta "d5" :status :red   :prompt "extend it"     :ms 5}
                  {:delta "d6" :status :green :prompt "extend it"     :ms 7}]
        e        (history/form-effort 'app.core/f versions)]
    (testing "the shape of the work, over the whole lifetime"
      (is (= 'app.core/f (:form e)))
      (is (= 6 (:versions e)))
      (is (= 3 (:reds e)))
      (is (= 2 (:cycles e))
          "two red→green recoveries — d2→d3 and d5→d6; d3→d4 is green→green"))
    (testing "distinct ASKS, not deltas — one intent can take several writes"
      (is (= 4 (:asks e)) "first cut / fix the arity / tidy / extend it"))
    (testing "recorded cost, and how much of the life it actually covers"
      (is (= 42 (:verification-ms e)) "10 + 20 + 5 + 7")
      (is (= {:with-cost 4 :of 6} (:measured e))
          "most of a long-lived form predates the timing instrument, and a sum
           that does not say so reads as a total"))
    (testing "a form with NO recorded cost reports no total rather than zero"
      ;; zero would read as "measured, and it was free"
      (let [e2 (history/form-effort 'app.core/g [{:delta "d1" :status :green :prompt "add"}])]
        (is (nil? (:verification-ms e2)))
        (is (= {:with-cost 0 :of 1} (:measured e2)))
        (is (= 0 (:cycles e2)))))))
