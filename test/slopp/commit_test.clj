(ns slopp.commit-test
  "Commit points (P4-m7): named MILESTONE markers in a branch's history — the
  human-important grain above turn ends, episode ends, and done-points.
  A commit point implies a done, is green-gated (:force records a red
  one honestly), and is a plain :commit marker delta so it rides the journal,
  branch snapshots, and merges for free."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.mcp]
            [slopp.api :as api]))

(def seed
  (str "(ns cm.core (:require [clojure.test :refer [deftest is]]))\n"
       "(defn f [x] (inc x))\n"
       "(defn ^:unused-ok g [x] (dec x))\n"
       "(deftest f-t (is (= 2 (f 1))))\n"))

(deftest ^:isolated commit-point-marks-a-verified-milestone
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'cm.core seed)
      (api/edit-replace! sess 'cm.core 'f-t "(deftest f-t (is (= 11 (f 1))))"
                         :prompt "red first" :agent "alice")
      (api/edit-replace! sess 'cm.core 'f "(defn f [x] (+ x 10))"
                         :prompt "green" :agent "alice")
      (let [r (api/commit-point! sess "v1: plus-ten shipped" :agent "alice")]
        (is (nil? (:error r)) (pr-str r))
        (is (= :green (:status r)))
        (is (some? (:commit r)))
        (testing "a commit point IMPLIES a done (episode closed)"
          (is (some? (:done r)))
          (is (empty? (:forms (api/query-changes sess :agent "alice")))))
        (testing "the marker delta is real provenance"
          (let [d (first (filter #(= :commit (:op %))
                                 (store/deltas (:store @sess))))]
            (is (= "v1: plus-ten shipped" (:description d)))
            (is (= (:target r) (:target d)))
            (is (= "alice" (:agent d))))))
      (testing "query-commits: newest first, human time, status"
        (api/edit-replace! sess 'cm.core 'g "(defn ^:unused-ok g [x] (- x 2))"
                           :prompt "more" :agent "alice")
        (api/commit-point! sess "v2: minus-two" :agent "alice")
        (let [[c2 c1 :as cs] (api/query-commits sess)]
          (is (= 2 (count cs)))
          (is (= "v2: minus-two" (:description c2)))
          (is (= "v1: plus-ten shipped" (:description c1)))
          (is (= :green (:status c2)))
          (is (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}" (str (:at c2))))))
      (testing "commit targets anchor query-changes spans (diff between milestones)"
        (let [[c2 c1] (api/query-commits sess)
              c (api/query-changes sess :from (:target c1) :to (:target c2))]
          (is (some #(= 'cm.core/g (:form %)) (:forms c)))))
      (testing "the collapsed history shows the milestone; contains finds it"
        (let [rows (api/query-history sess :collapse true :contains "plus-ten shipped")]
          (is (= "v1: plus-ten shipped"
                 (get-in (first rows) [:commit :description]))))
        (is (re-find #"COMMIT \"v2: minus-two\""
                     (api/query-history sess :collapse true :format "text"))))
      (finally (api/close! sess)))))

(deftest ^:isolated commit-point-green-gate
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'cm.core seed)
      (api/edit-replace! sess 'cm.core 'f-t "(deftest f-t (is (= 999 (f 1))))"
                         :prompt "deliberately red" :agent "bob")
      (testing "a red state refuses the milestone (work stays at its done-point)"
        (let [r (api/commit-point! sess "broken milestone" :agent "bob")]
          (is (:error r))
          (is (= :red (:status r)))
          (is (empty? (api/query-commits sess)))))
      (testing ":force records the red milestone HONESTLY"
        (let [r (api/commit-point! sess "broken but important" :agent "bob"
                                   :force true)]
          (is (nil? (:error r)) (pr-str r))
          (is (= :red (:status r)))
          (is (= :red (:status (first (api/query-commits sess)))))))
      (testing "a blank description is refused"
        (is (:error (api/commit-point! sess "  " :agent "bob"))))
      (finally (api/close! sess)))))

(deftest ^:isolated retroactive-commit-point
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'cm.core seed)
      (api/edit-replace! sess 'cm.core 'f "(defn f [x] (+ x 1))"
                         :prompt "v0.9 work" :agent "a")
      (let [past (:head (api/query-project sess))]
        (api/edit-replace! sess 'cm.core 'g "(defn g [x] :newer)"
                           :prompt "later work" :agent "a")
        (testing "a past delta id can be marked after the fact (pure marker)"
          (let [r (api/commit-point! sess "v0.9 was actually here" :agent "a"
                                     :target past)]
            (is (nil? (:error r)) (pr-str r))
            (is (= past (:target r)))
            (is (= past (:target (first (api/query-commits sess)))))))
        (testing "an unknown target is refused"
          (is (:error (api/commit-point! sess "nowhere" :agent "a"
                                         :target "d99999")))))
      (finally (api/close! sess)))))

(deftest ^:isolated commit-replays-as-a-marker
  ;; foreign-journal sync (m5b) must treat the new op as a no-content marker
  (let [st (store/ingest (store/empty-store) 'cm.core seed)]
    (is (some? (store/replay-delta st {:id "d90" :op :commit :ns '*session*
                                       :description "m" :target "d0" :at 1})))))

(deftest ^:isolated commit-point-rides-the-mcp-surface
  (let [sess (api/open!)]
    (try
      (swap! sess assoc :require-turns? true)
      (api/ingest! sess 'cm.core seed)
      (let [call (fn [tool args]
                   (get-in (slopp.mcp/handle! sess
                                             {:id 1 :method "tools/call"
                                              :params {:name tool :arguments args}})
                           [:result :content 0 :text]))]
        (testing "commit_point closes work — allowed WITHOUT an open turn"
          (let [r (call "commit_point" {:description "m1" :agent "z"})]
            (is (not (re-find #"turn_begin" r)) r)))
        (testing "query_commits answers over MCP"
          (is (re-find #"m1" (call "query_commits" {})))))
      (finally (api/close! sess)))))
(deftest ^:isolated repeat-milestone-on-unchanged-store-returns-it
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'rm.core :source "(ns rm.core)\n(defn ^:unused-ok f [] 1)\n")
      (let [m1 (api/commit-point! sess "v1" :agent "t")
            m2 (api/commit-point! sess "anything" :agent "t")]
        (is (nil? (:error m1)))
        (testing "no new marker minted; the existing one comes back"
          (is (= (:commit m1) (:commit m2)))
          (is (= "v1" (:description m2)))
          (is (re-find #"nothing changed" (str (:note m2))))))
      (finally (api/close! sess)))))
(deftest ^:isolated milestones-gate-on-the-isolated-suite
  ;; the milestone owns the FULL gate: a red ^:isolated suite refuses the
  ;; milestone (no manual test_run first), and a green one records its
  ;; isolated summary on the result.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'mg.core "(ns mg.core)\n\n(defn f \"F.\" [x] x)\n")
      (api/ingest! sess 'mg.core-test
                   (str "(ns mg.core-test (:require [mg.core :as core]\n"
                        "                           [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest ^:isolated f-t (is (= :nope (core/f 1))))\n"))
      (let [r (api/commit-point! sess "should refuse")]
        (is (:error r) (pr-str (dissoc r :test)))
        (is (= :red (:status r))))
      (api/edit-replace! sess 'mg.core-test 'f-t
                         "(deftest ^:isolated f-t (is (= 1 (core/f 1))))"
                         :prompt "fix the spec")
      (let [r (api/commit-point! sess "now green")]
        (is (:commit r) (pr-str (dissoc r :test)))
        (is (= :green (:status r)))
        (is (= :green (:status (:isolated r))) (pr-str (:isolated r))))
      (finally (api/close! sess)))))
