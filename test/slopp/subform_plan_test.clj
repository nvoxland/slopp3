(ns slopp.subform-plan-test
  "The subform matcher's correctness (friction-log fixes): fn literals and
  regexes must match (sexpr equality can't — gensym'd args / Pattern never
  compares equal), a multi-form match must be a hard error (silently matching
  the first form once corrupted a case dispatch), and the documented SPLICE
  (one match → several replacement forms) must hold."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.refactor :as refactor]
            [slopp.store :as store]))

(defn- st [src] (store/ingest (store/empty-store) 'sp.core src))

(deftest fn-literals-match
  (let [plan (refactor/subform-replace-plan
              (st "(ns sp.core)\n(defn f [xs] (mapv #(inc %) xs))\n")
              'sp.core 'f "#(inc %)" "#(+ 2 %)")]
    (is (nil? (:error plan)) (pr-str plan))
    (is (= "(defn f [xs] (mapv #(+ 2 %) xs))" (:new-form-src plan)))))

(deftest regex-literals-match
  (let [plan (refactor/subform-replace-plan
              (st "(ns sp.core)\n(defn g [s] (re-find #\"a+b\" s))\n")
              'sp.core 'g "#\"a+b\"" "#\"a+c\"")]
    (is (nil? (:error plan)) (pr-str plan))
    (is (= "(defn g [s] (re-find #\"a+c\" s))" (:new-form-src plan)))))

(deftest multiline-match-tolerates-indentation
  ;; the author copies the form but not necessarily its exact columns
  (let [plan (refactor/subform-replace-plan
              (st "(ns sp.core)\n(defn h [x]\n  (when x\n    (inc x)))\n")
              'sp.core 'h "(when x\n(inc x))" "(when x (dec x))")]
    (is (nil? (:error plan)) (pr-str plan))))

(deftest multi-form-match-is-a-hard-error
  (let [plan (refactor/subform-replace-plan
              (st "(ns sp.core)\n(defn f [x] (case x :a 1 :b 2))\n")
              'sp.core 'f ":a 1" ":a 9")]
    (is (:error plan))
    (is (re-find #"ONE" (str (:error plan))))))

(deftest splice-still-replaces-one-with-several
  (let [plan (refactor/subform-replace-plan
              (st "(ns sp.core)\n(def xs [:a :c])\n")
              'sp.core 'xs ":a" ":a :b")]
    (is (nil? (:error plan)) (pr-str plan))
    (is (= "(def xs [:a :b :c])" (:new-form-src plan)))))

(deftest fn-literal-ambiguity-counts-both-matchers
  ;; two textually-identical fn literals: the text fallback must count both
  (let [plan (refactor/subform-replace-plan
              (st "(ns sp.core)\n(defn f [xs] [(mapv #(inc %) xs) (filterv #(inc %) xs)])\n")
              'sp.core 'f "#(inc %)" "#(dec %)")]
    (is (re-find #"2 times" (str (:error plan))))))
(deftest ambiguity-still-errors
  (let [plan (refactor/subform-replace-plan
              (st "(ns sp.core)\n(defn f [x] (+ (inc x) (inc x)))\n")
              'sp.core 'f "(inc x)" "(dec x)")]
    (is (re-find #"2 times" (str (:error plan))))))