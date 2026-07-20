(ns slopp.api.orient-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api] [slopp.api.orient :as orient] [slopp.api.external :as external]))

(deftest fit-report-keeps-reports-under-the-gate
  (let [fat {:milestones [{:commit "d9" :description "m"}]
             :changes (vec (for [i (range 80)]
                             {:ns (symbol (str "big.ns" i)) :form (symbol (str "fn" i))
                              :ops [:replace]
                              :asks [(apply str (repeat 130 "x")) (apply str (repeat 130 "y"))]}))
             :suite {:status :green} :verify "test_run"}
        r  (#'orient/fit-report fat)]
    (is (<= (count (pr-str r)) 6500) (str (count (pr-str r))))
    (is (seq (:milestones r)))
    (is (re-find #"narrows" (str (:note r))) (pr-str (keys r)))))

(deftest fit-report-aggregates-instead-of-amputating
  (let [fat {:milestones [{:commit "d9" :description "m"}]
             :changes (vec (for [i (range 80)]
                             {:ns (symbol (str "big.ns" (mod i 8))) :form (symbol (str "fn" i))
                              :ops [:replace]
                              :asks [(apply str (repeat 130 "x")) (apply str (repeat 130 "y"))]}))
             :suite {:status :green} :verify "test_run"}
        r  (#'orient/fit-report fat)]
    (is (<= (count (pr-str r)) 6500) (str (count (pr-str r))))
    (testing "over-budget changes ROLL UP by namespace — information aggregates, never amputates"
      (is (some #(and (:ns %) (number? (:forms %))) (:changes r)) (pr-str (take 3 (:changes r))))
      (is (re-find #"rolled up" (str (:note r))) (pr-str (:note r))))))
(deftest ^:external form-cards-are-the-interface-view
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'cd.core
                   (str "(ns cd.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn scale\n  \"Rounds to the nearest cent.\"\n  [cents rate]\n"
                        "  (long (Math/round (double (* cents rate)))))\n"
                        "(deftest scale-t (is (= 50 (scale 100 0.5))))\n"))
      (api/test-run! sess 'cd.core)
      (api/edit-replace! sess 'cd.core 'scale
                         "(defn scale\n  \"Rounds to the nearest cent.\"\n  [cents rate]\n  (long (Math/round (* (double cents) rate))))"
                         :prompt "avoid double-coercion of the product" :agent "t")
      (let [c (orient/form-card sess 'cd.core 'scale)]
        (is (= 'cd.core/scale (:form c)) (pr-str c))
        (is (= '[cents rate] (:sig c)) (pr-str c))
        (is (re-find #"nearest" (str (:doc c))) (pr-str c))
        (is (re-find #"double-coercion" (str (:why c))) (pr-str c))
        (is (= 1 (get-in c [:warranty :covered])) (pr-str c))
        (is (nil? (:source c)) (pr-str c))
        (is (< (count (pr-str c)) 400) (str (count (pr-str c)))))
      (finally (api/close! sess)))))
(deftest ^:external cards-carry-observed-examples
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-obs" (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.api/dir dir})]
    (try
      (api/ingest! sess 'ob.core
                   "(ns ob.core)\n(defn scale \"Half it.\" [c r] (long (* c r)))\n")
      (api/remember-observation! sess 'ob.core 'scale
                                 (api/query-observe sess 'ob.core 'scale
                                                    "(ob.core/scale 100 0.5)"))
      (testing "the card carries observed input→output pairs (Q: examples don't lie)"
        (let [c (orient/form-card sess 'ob.core 'scale)]
          (is (vector? (:examples c)) (pr-str c))
          (is (some #(re-find #"100" %) (:examples c)) (pr-str c))
          (is (some #(re-find #"50" %) (:examples c)) (pr-str c))))
      (finally (api/close! sess)))))

(deftest ^:external observed-examples-survive-a-reopen
  ;; The half cards-carry-observed-examples does NOT cover: examples written
  ;; in one session must still show in the next. This pins the durable path
  ;; before form-card stops reading the db directly — without it, moving
  ;; observations into session state could silently reduce them to
  ;; this-session-only and every existing test would still pass.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-obs-reopen" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (let [sess (external/open! {:slopp.api/dir dir})]
      (try
        (api/ingest! sess 'ob2.core
                     "(ns ob2.core)\n(defn scale \"Half it.\" [c r] (long (* c r)))\n")
        (api/remember-observation! sess 'ob2.core 'scale
                                   (api/query-observe sess 'ob2.core 'scale
                                                      "(ob2.core/scale 100 0.5)"))
        (finally (api/close! sess))))
    (let [sess2 (external/open! {:slopp.api/dir dir})]
      (try
        (let [c (orient/form-card sess2 'ob2.core 'scale)]
          (is (vector? (:examples c))
              (str "a reopened session must still carry observed examples: " (pr-str c)))
          (is (some #(re-find #"100" %) (:examples c)) (pr-str c)))
        (finally (api/close! sess2))))))
