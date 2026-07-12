(ns slopp.history-test
  "Roadmap #5 — the semantic × history depth surface: form-at-delta
  (time-travel), was-green-at, delta-log search, and form-history diffs.
  Queries over the journal slopp already records — the combination the
  roadmap calls 'the moat'."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.mcp]
            [slopp.store :as store]))

(deftest ^:isolated form-history-is-reconstructible
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'h.core "(ns h.core)\n(defn f [x] x)\n(defn g [x] (f x))\n")
      (api/edit-replace! sess 'h.core 'f "(defn f [x] (inc x))" :prompt "bump by one")
      (api/edit-replace! sess 'h.core 'f "(defn f [x] (+ 2 x))" :prompt "bump by two")
      (testing "every content version of the form, oldest first, with intent"
        (let [h (api/query-form-history sess 'h.core 'f)]
          (is (= 3 (count h)))
          (is (= [:ingest :replace :replace] (mapv :op h)))
          (is (re-find #"\[x\] x" (:source (first h))))
          (is (= "bump by one" (:prompt (second h))))
          (is (re-find #"\+ 2 x" (:source (last h))))))
      (testing "the log reads as a filterable story"
        (let [hist (api/query-history sess :contains "bump by one")]
          (is (= 1 (count hist)))
          (is (= :replace (:op (first hist)))))
        (is (<= (count (api/query-history sess :limit 3)) 3)))
      (testing "checkpoint labels appear in the story"
        (api/checkpoint! sess :label "phase one done")
        (is (= :checkpoint
               (:op (first (api/query-history sess :contains "phase one"))))))
      (testing "lineage responses stay lean (no bulk sources)"
        (is (not-any? :sources (api/query-lineage sess 'h.core 'f))))
      (finally (api/close! sess)))))

(def seed
  (str "(ns hi.core (:require [clojure.test :refer [deftest is]]))\n"
       "(defn f [x] (+ x 1))\n"
       "(defn g [x] (* x 2))\n"
       "(deftest f-t (is (= 2 (f 1))))\n"))

;; ---------------------------------------------------------------------------
;; HM1: form-at-delta (time-travel)

(deftest ^:isolated form-at-delta-travels-through-a-forms-versions
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hi.core seed)
      (let [v1 (api/commit-point! sess "v1: f adds one" :agent "a")]
        (api/edit-replace! sess 'hi.core 'f "(defn f [x] (+ x 100))"
                           :prompt "bump to 100" :agent "a")
        (api/edit-replace! sess 'hi.core 'f-t "(deftest f-t (is (= 101 (f 1))))"
                           :prompt "match" :agent "a")
        (let [v2 (api/commit-point! sess "v2: f adds 100" :agent "a")]
          (testing "a form renders as it stood at a past delta"
            (is (= "(defn f [x] (+ x 1))"
                   (:source (api/query-form-at sess 'hi.core 'f
                                               :at (:target v1)))))
            (is (= "(defn f [x] (+ x 100))"
                   (:source (api/query-form-at sess 'hi.core 'f
                                               :at (:target v2))))))
          (testing "a COMMIT id resolves to its target (time-travel to a milestone)"
            (is (= "(defn f [x] (+ x 1))"
                   (:source (api/query-form-at sess 'hi.core 'f
                                               :at (:commit v1))))))
          (testing "the version carries the was-green-at status of that point"
            (is (= :green (:status (api/query-form-at sess 'hi.core 'f
                                                      :at (:target v2))))))))
      (finally (api/close! sess)))))

(deftest ^:isolated form-at-delta-edge-cases
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hi.core seed)
      (let [early (:id (last (store/deltas (:store @sess))))]
        (api/add-form! sess 'hi.core "(defn late [x] x)"
                       :prompt "added later" :agent "a")
        (testing "a form absent at that point is an honest error, not a guess"
          (is (:error (api/query-form-at sess 'hi.core 'late :at early))))
        (testing "an unknown delta is refused"
          (is (:error (api/query-form-at sess 'hi.core 'f :at "d99999"))))
        (testing ":at is required"
          (is (:error (api/query-form-at sess 'hi.core 'f)))))
      (finally (api/close! sess)))))

(deftest ^:isolated form-at-delta-follows-renames
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hi.core seed)
      (let [before (:id (last (store/deltas (:store @sess))))]
        (api/rename! sess 'hi.core 'g 'doubler :prompt "clearer name" :agent "a")
        (testing "the OLD name resolves at a delta before the rename"
          (let [r (api/query-form-at sess 'hi.core 'g :at before)]
            (is (nil? (:error r)) (pr-str r))
            (is (= "(defn g [x] (* x 2))" (:source r)))))
        (testing "the NEW name resolves at the current head"
          (let [head (:id (last (store/deltas (:store @sess))))
                r    (api/query-form-at sess 'hi.core 'doubler :at head)]
            (is (nil? (:error r)) (pr-str r))
            (is (str/includes? (:source r) "doubler")))))
      (finally (api/close! sess)))))

;; ---------------------------------------------------------------------------
;; HM2: was-green-at

(deftest ^:isolated was-green-at-reads-the-verification-arc
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hi.core seed)
      (let [green-head (:id (last (store/deltas (:store @sess))))]
        (testing "a delta after a passing verify is green, naming its verify delta"
          (let [r (api/query-status-at sess :at green-head)]
            (is (= :green (:status r)))
            (is (some? (:verify r)))))
        (testing "a commit id resolves to its target's status"
          (let [c (api/commit-point! sess "v1" :agent "a")]
            (is (= :green (:status (api/query-status-at sess :at (:commit c)))))))
        (testing "a deliberately red state reads red"
          (api/edit-replace! sess 'hi.core 'f-t
                             "(deftest f-t (is (= 999 (f 1))))"
                             :prompt "break it" :agent "a")
          (let [red-head (:id (last (store/deltas (:store @sess))))]
            (is (= :red (:status (api/query-status-at sess :at red-head))))))
        (testing "an unknown delta is refused"
          (is (:error (api/query-status-at sess :at "d99999")))))
      (finally (api/close! sess)))))

(deftest ^:isolated form-history-versions-carry-was-green-at
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hi.core seed)
      (api/edit-replace! sess 'hi.core 'f "(defn f [x] (+ x 2))"
                         :prompt "green change" :agent "a")
      (api/edit-replace! sess 'hi.core 'f-t "(deftest f-t (is (= 999 (f 1))))"
                         :prompt "make it red" :agent "a")
      (testing "each version of a form is tagged with the state it landed in"
        (let [h (api/query-form-history sess 'hi.core 'f)]
          (is (every? #(contains? % :status) h))
          (is (= :green (:status (first h))))))
      (finally (api/close! sess)))))

(deftest ^:isolated form-at-delta-rides-the-mcp-surface
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hi.core seed)
      (let [v1 (api/commit-point! sess "v1" :agent "a")
            _  (api/edit-replace! sess 'hi.core 'f "(defn f [x] (+ x 100))"
                                  :prompt "bump" :agent "a")
            call (fn [args]
                   (get-in (slopp.mcp/handle
                            sess {:id 1 :method "tools/call"
                                  :params {:name "query_form_at" :arguments args}})
                           [:result :content 0 :text]))]
        (is (str/includes? (call {:ns "hi.core" :name "f" :at (:commit v1)})
                           "(+ x 1)")))
      (finally (api/close! sess)))))

;; ---------------------------------------------------------------------------
;; HM3: delta-log search ("which prompts touched X")

(deftest ^:isolated search-history-finds-prompts-intents-and-descriptions
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hi.core seed)
      (api/edit-replace! sess 'hi.core 'f "(defn f [x] (+ x 3))"
                         :prompt "add auth bounds check" :agent "a")
      (api/edit-replace! sess 'hi.core 'g "(defn g [x] (* x 3))"
                         :prompt "unrelated tweak" :agent "a")
      (testing "a prompt match returns the delta AND the forms it touched"
        (let [r (api/query-search-history sess "auth")]
          (is (= 1 (count r)))
          (is (= "add auth bounds check" (:prompt (first r))))
          (is (some #{'hi.core/f} (:forms (first r))))
          (is (some? (:at (first r))))))
      (testing "matching is case-insensitive"
        (is (= 1 (count (api/query-search-history sess "AUTH")))))
      (testing "a turn INTENT match catches deltas whose own prompt is silent"
        (api/turn-begin! sess :agent "b" :intent "wire up the login flow")
        (api/edit-replace! sess 'hi.core 'g "(defn g [x] (* x 5))"
                           :prompt "tweak again" :agent "b")
        (api/turn-end! sess :agent "b")
        (let [r (api/query-search-history sess "login")]
          (is (seq r))
          (is (every? #(= "wire up the login flow" (:turn-intent %)) r))))
      (testing "a commit-point DESCRIPTION is searchable"
        ;; :force — the earlier edits left f-t red; we only care that the
        ;; :commit marker (with its description) lands and is searchable
        (api/commit-point! sess "auth milestone shipped" :agent "a" :force true)
        (is (some #(= "auth milestone shipped" (:description %))
                  (api/query-search-history sess "milestone"))))
      (testing "a blank pattern is refused; limit is respected"
        (is (:error (api/query-search-history sess "  ")))
        (is (<= (count (api/query-search-history sess "x" :limit 1)) 1)))
      (finally (api/close! sess)))))

(deftest ^:isolated search-history-rides-the-mcp-surface
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hi.core seed)
      (api/edit-replace! sess 'hi.core 'f "(defn f [x] (+ x 9))"
                         :prompt "harden auth path" :agent "a")
      (let [r (get-in (slopp.mcp/handle
                       sess {:id 1 :method "tools/call"
                             :params {:name "query_search_history"
                                      :arguments {:contains "auth"}}})
                      [:result :content 0 :text])]
        (is (str/includes? r "harden auth path")))
      (finally (api/close! sess)))))

;; ---------------------------------------------------------------------------
;; HM4: form-history diffs — one form's life as a diff story

(deftest ^:isolated form-history-renders-as-a-diff-timeline
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'hi.core seed)
      (api/edit-replace! sess 'hi.core 'f "(defn f [x] (+ x 2))"
                         :prompt "bump to two" :agent "a")
      (api/edit-replace! sess 'hi.core 'f "(defn f [x] (- x 9))"
                         :prompt "now subtract" :agent "a")
      (testing "EDN rows now also carry a human :at"
        (is (every? :at (api/query-form-history sess 'hi.core 'f))))
      (testing "text format is a per-version LINE-diff story with intents"
        (let [txt (api/query-form-history sess 'hi.core 'f :format "text")]
          (is (str/includes? txt "form hi.core/f"))
          (is (str/includes? txt "bump to two"))
          (is (str/includes? txt "now subtract"))
          ;; the churn between versions shows as - / + lines
          (is (str/includes? txt "- (defn f [x] (+ x 2))"))
          (is (str/includes? txt "+ (defn f [x] (- x 9))"))))
      (testing "the same story rides the MCP surface via :format"
        (let [r (get-in (slopp.mcp/handle
                         sess {:id 1 :method "tools/call"
                               :params {:name "query_form_history"
                                        :arguments {:ns "hi.core" :name "f"
                                                    :format "text"}}})
                        [:result :content 0 :text])]
          (is (str/includes? r "bump to two"))))
      (finally (api/close! sess)))))
