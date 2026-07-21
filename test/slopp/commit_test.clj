(ns slopp.commit-test
  "Commit points (P4-m7): named MILESTONE markers in a branch's history — the
  human-important grain above turn ends, episode ends, and done-points.
  A commit point implies a done, is green-gated (:force records a red
  one honestly), and is a plain :commit marker delta so it rides the journal,
  branch snapshots, and merges for free."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.mcp]
            [slopp.api :as api] [slopp.api.query :as query] [slopp.api.external :as external]))

(def seed
  (str "(ns cm.core (:require [clojure.test :refer [deftest is]]))\n"
       "(defn f [x] (inc x))\n"
       "(defn ^:unused-ok g [x] (dec x))\n"
       "(deftest f-t (is (= 2 (f 1))))\n"))

(deftest ^:external commit-point-marks-a-verified-milestone
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'cm.core seed)
      (api/edit-replace! sess 'cm.core 'f-t "(deftest f-t (is (= 11 (f 1))))"
                         :prompt "red first" :agent "alice")
      (api/edit-replace! sess 'cm.core 'f "(defn f [x] (+ x 10))"
                         :prompt "green" :agent "alice")
      (let [r (external/commit-point! sess "v1: plus-ten shipped" :agent "alice")]
        (is (nil? (:error r)) (pr-str r))
        (is (= :green (:status r)))
        (is (some? (:commit r)))
        (testing "a commit point IMPLIES a done (episode closed)"
          (is (some? (:done r)))
          (is (empty? (:forms (query/query-changes sess :agent "alice")))))
        (testing "the marker delta is real provenance"
          (let [d (first (filter #(= :commit (:op %))
                                 (store/deltas (:store @sess))))]
            (is (= "v1: plus-ten shipped" (:description d)))
            (is (= (:target r) (:target d)))
            (is (= "alice" (:agent d))))))
      (testing "query-commits: newest first, human time, status"
        (api/edit-replace! sess 'cm.core 'g "(defn ^:unused-ok g [x] (- x 2))"
                           :prompt "more" :agent "alice")
        (external/commit-point! sess "v2: minus-two" :agent "alice")
        (let [[c2 c1 :as cs] (api/query-commits sess)]
          (is (= 2 (count cs)))
          (is (= "v2: minus-two" (:description c2)))
          (is (= "v1: plus-ten shipped" (:description c1)))
          (is (= :green (:status c2)))
          (is (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}" (str (:at c2))))))
      (testing "commit targets anchor query-changes spans (diff between milestones)"
        (let [[c2 c1] (api/query-commits sess)
              c (query/query-changes sess :from (:target c1) :to (:target c2))]
          (is (some #(= 'cm.core/g (:form %)) (:forms c)))))
      (testing "the collapsed history shows the milestone; contains finds it"
        (let [rows (query/query-history sess :collapse true :contains "plus-ten shipped")]
          (is (= "v1: plus-ten shipped"
                 (get-in (first rows) [:commit :description]))))
        (is (re-find #"COMMIT \"v2: minus-two\""
                     (query/query-history sess :collapse true :format "text"))))
      (finally (api/close! sess)))))

(deftest ^:external commit-point-green-gate
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'cm.core seed)
      (api/edit-replace! sess 'cm.core 'f-t "(deftest f-t (is (= 999 (f 1))))"
                         :prompt "deliberately red" :agent "bob")
      (testing "a red state refuses the milestone (work stays at its done-point)"
        (let [r (external/commit-point! sess "broken milestone" :agent "bob")]
          (is (:error r))
          (is (= :red (:status r)))
          (is (empty? (api/query-commits sess)))))
      (testing ":force records the red milestone HONESTLY"
        (let [r (external/commit-point! sess "broken but important" :agent "bob"
                                   :force true)]
          (is (nil? (:error r)) (pr-str r))
          (is (= :red (:status r)))
          (is (= :red (:status (first (api/query-commits sess)))))))
      (testing "a blank description is refused"
        (is (:error (external/commit-point! sess "  " :agent "bob"))))
      (finally (api/close! sess)))))

(deftest ^:external retroactive-commit-point
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'cm.core seed)
      (api/edit-replace! sess 'cm.core 'f "(defn f [x] (+ x 1))"
                         :prompt "v0.9 work" :agent "a")
      (let [past (:head (query/query-project sess))]
        (api/edit-replace! sess 'cm.core 'g "(defn g [x] :newer)"
                           :prompt "later work" :agent "a")
        (testing "a past delta id can be marked after the fact (pure marker)"
          (let [r (external/commit-point! sess "v0.9 was actually here" :agent "a"
                                     :target past)]
            (is (nil? (:error r)) (pr-str r))
            (is (= past (:target r)))
            (is (= past (:target (first (api/query-commits sess)))))))
        (testing "an unknown target is refused"
          (is (:error (external/commit-point! sess "nowhere" :agent "a"
                                         :target "d99999")))))
      (finally (api/close! sess)))))

(deftest ^:external commit-replays-as-a-marker
  ;; foreign-journal sync (m5b) must treat the new op as a no-content marker
  (let [st (store/ingest (store/empty-store) 'cm.core seed)]
    (is (some? (store/replay-delta st {:id "d90" :op :commit :ns '*session*
                                       :description "m" :target "d0" :at 1})))))

(deftest ^:external commit-point-rides-the-mcp-surface
  (let [sess (external/open!)]
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
(deftest ^:external repeat-milestone-on-unchanged-store-returns-it
  (let [sess (external/open!)]
    (try
      (api/create-ns! sess 'rm.core :source "(ns rm.core)\n(defn ^:unused-ok f [] 1)\n")
      (let [m1 (external/commit-point! sess "v1" :agent "t")
            m2 (external/commit-point! sess "anything" :agent "t")]
        (is (nil? (:error m1)))
        (testing "no new marker minted; the existing one comes back"
          (is (= (:commit m1) (:commit m2)))
          (is (= "v1" (:description m2)))
          (is (re-find #"nothing changed" (str (:note m2))))))
      (finally (api/close! sess)))))
(deftest ^:external the-milestone-forces-no-whole-store-check
  ;; CONTRACT (D-full-check): the milestone runs `done!` and gates on that
  ;; verdict — the impacted ^:external slice included, since that IS what a
  ;; standalone done runs. What it does NOT do is run the WHOLE external suite;
  ;; `full_check` is the whole-store answer and is the agent's call.
  ;;
  ;; The cost is pinned here deliberately: a red ^:external test the episode
  ;; never TOUCHED will not stop a milestone (only full_check catches it). A
  ;; TOUCHED red one does — a-milestone-catches-a-touched-red-external-test.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'mg.bad "(ns mg.bad)\n\n(defn f \"F.\" [x] x)\n")
      (api/ingest! sess 'mg.bad-test
                   (str "(ns mg.bad-test (:require [mg.bad :as bad]\n"
                        "                          [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest ^:external f-t (is (= :nope (bad/f 1))))\n"))
      ;; record the red spec as an honest red milestone; now it sits in the
      ;; store, and the NEXT episode does not touch it
      (external/commit-point! sess "known-red corner" :force true)
      (testing "unrelated work milestones green over the untouched red corner"
        (api/ingest! sess 'mg.ok "(ns mg.ok)\n\n(defn ^:unused-ok g \"G.\" [x] (inc x))\n")
        (let [r (external/commit-point! sess "unrelated work")]
          (is (:commit r) (pr-str (dissoc r :test :findings)))
          (is (not= :red (:status r)) (pr-str (dissoc r :test :findings)))))
      (testing "full_check is where the untouched red external spec surfaces"
        (let [r (external/full-check! sess)]
          (is (= :red (:status r)) (pr-str (dissoc r :lint :warnings)))
          (is (= :red (:status (:external r))) (pr-str (:external r)))))
      (testing "and once the spec is honest, full_check is green"
        (api/edit-replace! sess 'mg.bad-test 'f-t
                           "(deftest ^:external f-t (is (= 1 (bad/f 1))))"
                           :prompt "fix the spec")
        (let [r (external/full-check! sess)]
          (is (= :green (:status r)) (pr-str (dissoc r :lint :warnings)))))
      (finally (api/close! sess)))))

(deftest ^:external a-milestone-catches-a-touched-red-external-test
  ;; D-full-check: `done` runs the impacted ^:external slice, and a milestone
  ;; "gates on done's verdict". So a red ^:external test the episode TOUCHED
  ;; must stop the milestone — only an UNTOUCHED one is exempt (that is what
  ;; full_check is for). commit-point! passed done! :external? false, making
  ;; the milestone's done SKIP the external tier the in-image suite already
  ;; skips — so going straight to a milestone (no prior standalone done to
  ;; carry a verdict) landed green over exactly the red a plain done catches.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'mt.core "(ns mt.core)\n\n(defn f \"F.\" [x] x)\n")
      (api/ingest! sess 'mt.core-test
                   (str "(ns mt.core-test (:require [mt.core :as core]\n"
                        "                           [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest ^:external f-t (is (= :nope (core/f 1))))\n"))
      (testing "the milestone runs the impacted external tier and refuses"
        (let [r (external/commit-point! sess "should refuse")]
          (is (= :red (:status r)) (pr-str (dissoc r :test :findings)))
          (is (:error r) (pr-str (dissoc r :test :findings)))
          (is (empty? (api/query-commits sess)) "no green milestone was minted")))
      (finally (api/close! sess)))))
