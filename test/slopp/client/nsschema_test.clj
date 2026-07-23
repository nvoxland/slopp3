(ns slopp.client.nsschema-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.client.nsschema :as sch]))

(deftest valid-ns-row?-checks-the-shared-shape
  (testing "a well-formed row validates"
    (is (sch/valid-ns-row? {:ns "slopp.http.browse" :forms 12})))
  (testing "wrong types / missing keys / negatives fail"
    (is (not (sch/valid-ns-row? {:ns "x"})))            ; missing :forms
    (is (not (sch/valid-ns-row? {:ns "x" :forms -1})))  ; negative count
    (is (not (sch/valid-ns-row? {:ns 42 :forms 1})))    ; :ns not a string
    (is (not (sch/valid-ns-row? "nope")))))

(deftest parse-ns-cell-round-trips-a-rendered-cell
  (testing "parses \"name (n)\" into a valid row"
    (let [row (sch/parse-ns-cell "slopp.http.browse (12)")]
      (is (= {:ns "slopp.http.browse" :forms 12} row))
      (is (sch/valid-ns-row? row))))
  (testing "tolerates surrounding whitespace"
    (is (= {:ns "a.b" :forms 3} (sch/parse-ns-cell "  a.b (3)  "))))
  (testing "nil when the cell doesn't match the shape"
    (is (nil? (sch/parse-ns-cell "no count here")))
    (is (nil? (sch/parse-ns-cell "")))))
