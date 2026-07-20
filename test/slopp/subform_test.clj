(ns slopp.subform-test
  "Item 5: paredit's valid-tree→valid-tree invariant as ONE content-addressed
  primitive — sub-form edits that never re-transcribe sibling code."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]))

(def target
  (str "(ns sf.core (:require [clojure.test :refer [deftest is]]))\n"
       "(defn price [items rate]\n"
       "  ;; keep this comment intact\n"
       "  (let [subtotal (reduce + (map :cents items))\n"
       "        fee      (max 50 (quot subtotal 100))]\n"
       "    (+ subtotal fee (long (* subtotal rate)))))\n"
       "(deftest price-t\n"
       "  (is (= 1060 (price [{:cents 500} {:cents 500}] 0.01))))\n"))

(deftest ^:external subform-edit-touches-only-what-it-names
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sf.core target)
      (testing "replace one inner expression; siblings and comments untouched"
        (let [r (api/edit-subform! sess 'sf.core 'price
                                   "(max 50 (quot subtotal 100))"
                                   "(min 500 (max 50 (quot subtotal 100)))"
                                   :prompt "cap the fee")]
          (is (nil? (:error r)))
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))
          (let [src (api/query-source sess 'sf.core)]
            (is (re-find #";; keep this comment intact" src))
            (is (re-find #"\(min 500 \(max 50" src))
            ;; untouched siblings byte-identical
            (is (re-find #"\(\+ subtotal fee \(long \(\* subtotal rate\)\)\)" src)))))
      (testing "wrap falls out for free (new subform containing the old)"
        (let [r (api/edit-subform! sess 'sf.core 'price
                                   "(reduce + (map :cents items))"
                                   "(long (reduce + (map :cents items)))"
                                   :prompt "force long subtotals")]
          (is (nil? (:error r)))
          (is (re-find #"\(long \(reduce \+" (api/query-source sess 'sf.core)))))
      (testing "ambiguity, absence, and degenerate results are clean errors"
        (api/add-form! sess 'sf.core "(defn dup [a] (+ (inc a) (inc a)))")
        (is (re-find #"2 times" (:error (api/edit-subform! sess 'sf.core 'dup
                                                           "(inc a)" "(dec a)"))))
        (is (:error (api/edit-subform! sess 'sf.core 'price "(nope)" "(yep)")))
        (is (re-find #"dialect"
                     (:error (api/edit-subform! sess 'sf.core 'dup
                                                "(+ (inc a) (inc a))"
                                                "(eval a)")))))
      (finally (api/close! sess)))))
(deftest ^:external text-misses-teach-and-reflow
  ;; text-mode misses returned a bare error (no :source-now to correct
  ;; against), and exact-text matching is brittle across docstring
  ;; reflows — a whitespace-fuzzy fallback should land the unique match.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'tm.core
                   (str "(ns tm.core)\n\n"
                        "(defn f \"Original doc line one\n  continued here.\" [x] x)\n"))
      (testing "a text miss carries :source-now like structural misses do"
        (let [r (api/edit-subform! sess 'tm.core 'f "no such text" "x"
                                   :text true)]
          (is (:error r))
          (is (re-find #"defn f" (str (:source-now r))) (pr-str r))))
      (testing "whitespace-fuzzy: a reflowed match still lands uniquely"
        (let [r (api/edit-subform! sess 'tm.core 'f
                                   "Original doc line one continued here."
                                   "New doc." :text true)]
          (is (nil? (:error r)) (pr-str r))
          (is (re-find #"New doc" (api/query-source sess 'tm.core)))))
      (finally (api/close! sess)))))
(deftest ^:external fragment-matches-suggest-the-enclosing-form
  ;; the recurring loop: a match that opens a delimiter it doesn't close is
  ;; refused with the rule — but when the fragment appears in the form, the
  ;; error should SHOW the smallest complete form containing it, so the
  ;; retry needs no re-read.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'fg.core
                   (str "(ns fg.core)\n\n"
                        "(defn g \"G.\" [x]\n"
                        "  (let [a (inc x)\n"
                        "        b (* 2 a)]\n"
                        "    (+ a b)))\n"))
      (let [r (api/edit-subform! sess 'fg.core 'g
                                 "a (inc x)\n        b (* 2 a)]" "ignored")]
        (is (:error r))
        (is (re-find #"\[a \(inc x\)" (str (:suggestion r)))
            (pr-str r)))
      (finally (api/close! sess)))))
