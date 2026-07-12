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
(deftest anchored-add-inserts-before-the-anchor
  (let [base (store/ingest (store/empty-store) 'an.core
                           "(ns an.core)\n(defn early [] 1)\n(defn late [] 2)\n")
        [st d] (store/append-form base 'an.core
                                  (rewrite-clj.parser/parse-string "(defn mid [] 3)")
                                  :prompt "anchored" :before 'late)]
    (testing "renders between early and late"
      (let [src ^String (slopp.render/render-ns st 'an.core)]
        (is (< (.indexOf src "early") (.indexOf src "mid") (.indexOf src "late")))))
    (testing "the delta records the anchor's form-id for replay"
      (is (= (:id (store/form-named base 'an.core 'late)) (:before d))))
    (testing "a foreign store replays the add into the SAME position"
      (let [replayed (store/replay-delta base d)
            src ^String (slopp.render/render-ns replayed 'an.core)]
        (is (< (.indexOf src "early") (.indexOf src "mid") (.indexOf src "late")))))
    (testing "a missing anchor name returns nil (caller errors)"
      (is (nil? (store/append-form base 'an.core
                                   (rewrite-clj.parser/parse-string "(defn x [] 4)")
                                   :before 'nope))))))
