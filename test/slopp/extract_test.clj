(ns slopp.extract-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]))

(deftest extract-subform-to-function
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ex.core
                   (str "(ns ex.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn pricey [items tax]\n"
                        "  (reduce + (map (fn [i] (* (:price i) (+ 1 tax))) items)))\n"
                        "(deftest pricey-t\n"
                        "  (is (= 22.0 (pricey [{:price 10} {:price 10}] 0.1))))\n"))
      (api/test-run! sess 'ex.core)
      (let [r (api/extract! sess 'ex.core 'pricey 'taxed-prices
                            "(map (fn [i] (* (:price i) (+ 1 tax))) items)"
                            :prompt "isolate the per-item tax step")]
        (testing "the plan: free locals become params in first-use order"
          (is (nil? (:error r)))
          (is (= '[tax items] (get-in r [:extracted :params]))))
        (testing "the new fn is defined BEFORE its caller; caller calls it"
          (let [src ^String (api/query-source sess 'ex.core)]
            (is (< (.indexOf src "(defn taxed-prices [tax items]")
                   (.indexOf src "(defn pricey")))
            (is (re-find #"\(reduce \+ \(taxed-prices tax items\)\)" src))))
        (testing "behavior preserved: affected test re-ran green"
          (is (= ['ex.core/pricey-t] (:affected r)))
          (is (zero? (+ (:fail (:test r)) (:error (:test r))))))
        (testing "live image agrees, incl. after a faithful restart"
          (is (= [22.0] (api/query-eval sess "(ex.core/pricey [{:price 10} {:price 10}] 0.1)")))
          (api/restart! sess)
          (is (= [22.0] (api/query-eval sess "(ex.core/pricey [{:price 10} {:price 10}] 0.1)")))))
      (testing "ambiguous subforms are rejected with a count"
        (api/add-form! sess 'ex.core "(defn dup [a] (+ (inc a) (inc a)))")
        (let [r (api/extract! sess 'ex.core 'dup 'inc2 "(inc a)")]
          (is (re-find #"2 times" (:error r)))))
      (testing "missing subform / taken name are clean errors"
        (is (:error (api/extract! sess 'ex.core 'pricey 'x "(nope)")))
        (is (:error (api/extract! sess 'ex.core 'pricey 'taxed-prices
                                  "(reduce + (taxed-prices tax items))"))))
      (finally (api/close! sess)))))
