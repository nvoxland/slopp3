(ns slopp.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]))

(def src "(ns foo)\n\n(defn add [x y]\n  (+ x y))\n\n;; a comment\n(def z 1)\n")

(deftest ingest-extracts-forms
  (let [s (store/ingest (store/empty-store) 'foo src)
        forms (store/forms s 'foo)]
    (testing "one Form per top-level sexpr (whitespace/comments are separators)"
      (is (= 3 (count forms))))
    (testing "names derived from def*/ns head"
      (is (= '[foo add z] (mapv :name forms))))
    (testing "every form gets a unique synthetic id (C2)"
      (is (every? :id forms))
      (is (apply distinct? (map :id forms))))))

(deftest ingest-appends-a-delta
  (let [s (store/ingest (store/empty-store) 'foo src)
        ds (store/deltas s)]
    (is (= 1 (count ds)))
    (is (= :ingest (:op (first ds))))
    (is (= 'foo (:ns (first ds))))
    (is (= 3 (count (:form-ids (first ds)))))))

(deftest form-lookup-by-name
  (let [s (store/ingest (store/empty-store) 'foo src)]
    (is (= 'add (:name (store/form-named s 'foo 'add))))
    (is (nil? (store/form-named s 'foo 'missing)))))
