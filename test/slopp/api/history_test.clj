(ns slopp.api.history-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api.history :as history]
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
