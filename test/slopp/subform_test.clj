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

(deftest ^:isolated subform-edit-touches-only-what-it-names
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
