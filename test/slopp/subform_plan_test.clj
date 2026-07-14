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
  (testing "3+ forms are always refused"
    (let [plan (refactor/subform-replace-plan
                (st "(ns sp.core)\n(defn f [x] (case x :a 1 :b 2))\n")
                'sp.core 'f ":a 1 :b" ":a 9 :b")]
      (is (:error plan))
      (is (re-find #"ONE" (str (:error plan))))))
  (testing "two forms OUTSIDE a paired context are refused"
    (let [plan (refactor/subform-replace-plan
                (st "(ns sp.core)\n(defn g [x] (do (prn x) (inc x)))\n")
                'sp.core 'g "(prn x) (inc x)" "(inc x)")]
      (is (:error plan))
      (is (re-find #"pair" (str (:error plan))))))
  (testing "a two-form span CROSSING a pair boundary is refused"
    (let [plan (refactor/subform-replace-plan
                (st "(ns sp.core)\n(def m {:a 1 :b 2})\n")
                'sp.core 'm "1 :b" "9 :c")]
      (is (:error plan))
      (is (re-find #"pair" (str (:error plan)))))))

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
(deftest text-mode-reaches-string-content
  (let [st (st "(ns sp.core)\n(def banner \"welcome to the OLD thing\")\n")]
    (testing "unique text inside a string literal is replaceable"
      (let [plan (refactor/text-replace-plan st 'sp.core 'banner
                                             "the OLD thing" "the NEW thing")]
        (is (nil? (:error plan)) (pr-str plan))
        (is (= "(def banner \"welcome to the NEW thing\")" (:new-form-src plan)))))
    (testing "ambiguous text errors"
      (is (:error (refactor/text-replace-plan st 'sp.core 'banner "e" "x"))))
    (testing "absent text errors"
      (is (:error (refactor/text-replace-plan st 'sp.core 'banner "zzz" "x"))))
    (testing "a replacement that breaks the form is refused"
      (is (:error (refactor/text-replace-plan st 'sp.core 'banner
                                              "welcome" "\")(oops"))))))
(deftest pair-matches-address-paired-units
  (testing "a case branch is addressable as DISPATCH RESULT (P1)"
    (let [plan (refactor/subform-replace-plan
                (st "(ns sp.core)\n(defn f [x] (case x :a 1 :b 2 :other))\n")
                'sp.core 'f ":b 2" ":b 20")]
      (is (nil? (:error plan)) (pr-str plan))
      (is (= "(defn f [x] (case x :a 1 :b 20 :other))" (:new-form-src plan)))))
  (testing "a let binding is addressable as NAME INIT"
    (let [plan (refactor/subform-replace-plan
                (st "(ns sp.core)\n(defn h [x] (let [a 1 b (inc a)] (+ a b)))\n")
                'sp.core 'h "b (inc a)" "b (+ a 2)")]
      (is (nil? (:error plan)) (pr-str plan))
      (is (= "(defn h [x] (let [a 1 b (+ a 2)] (+ a b)))" (:new-form-src plan)))))
  (testing "a map entry is addressable as KEY VALUE"
    (let [plan (refactor/subform-replace-plan
                (st "(ns sp.core)\n(def m {:a 1 :b 2})\n")
                'sp.core 'm ":b 2" ":b 20")]
      (is (nil? (:error plan)) (pr-str plan))
      (is (= "(def m {:a 1 :b 20})" (:new-form-src plan)))))
  (testing "cond clauses pair TEST RESULT"
    (let [plan (refactor/subform-replace-plan
                (st "(ns sp.core)\n(defn c [x] (cond (neg? x) :neg :else :pos))\n")
                'sp.core 'c "(neg? x) :neg" "(neg? x) :negative")]
      (is (nil? (:error plan)) (pr-str plan))))
  (testing "pair replacement splices: one entry can become two"
    (let [plan (refactor/subform-replace-plan
                (st "(ns sp.core)\n(def m {:a 1 :b 2})\n")
                'sp.core 'm ":b 2" ":b 20 :c 30")]
      (is (= "(def m {:a 1 :b 20 :c 30})" (:new-form-src plan)))))
  (testing "extract still refuses pairs (a pair is not an expression)"
    (let [plan (refactor/extract-plan
                (st "(ns sp.core)\n(def m {:a 1 :b (+ 1 1)})\n")
                'sp.core 'm ":b (+ 1 1)" 'entry)]
      (is (:error plan))
      (is (re-find #"pair" (str (:error plan)))))))
(deftest fragment-matches-teach-the-fix
  (testing "a match that ends mid-expression names the rule and the way out"
    (let [plan (refactor/subform-replace-plan
                (st "(ns sp.core)\n(defn f [x] (let [y 1] (+ x y)))\n")
                'sp.core 'f "(let [y 1" "(let [y 2")]
      (is (:error plan))
      (is (re-find #"COMPLETE forms" (str (:error plan))) (pr-str plan))))
  (testing "an unmatched delimiter gets the same teaching, not a parser trace"
    (let [plan (refactor/subform-replace-plan
                (st "(ns sp.core)\n(defn g [x] (case x :a 1 :b 2))\n")
                'sp.core 'g ":a 1 :b" ":a 9 :b")]
      (is (:error plan))
      (is (re-find #"ONE subform|COMPLETE forms|PAIR" (str (:error plan)))
          (pr-str plan)))))
(deftest missed-matches-return-the-current-source
  (testing "a not-found match carries the form's CURRENT source (edit optimistically, correct from the error)"
    (let [plan (refactor/subform-replace-plan
                (st "(ns sp.core)\n(defn h [x] (+ x 1))\n")
                'sp.core 'h "(+ x 2)" "(+ x 3)")]
      (is (:error plan))
      (is (re-find #"\(defn h \[x\] \(\+ x 1\)\)" (str (:source-now plan))) (pr-str plan))))
  (testing "an ambiguous match carries it too"
    (let [plan (refactor/subform-replace-plan
                (st "(ns sp.core)\n(defn h [x] (+ (inc x) (inc x)))\n")
                'sp.core 'h "(inc x)" "(dec x)")]
      (is (:error plan))
      (is (re-find #"defn h" (str (:source-now plan))) (pr-str plan)))))
(deftest changeset-nodes-keep-their-names
  (let [st2 (-> (store/empty-store)
                (store/ingest 'cs.a "(ns cs.a)\n(defn f [x] x)\n")
                (store/ingest 'cs.b "(ns cs.b (:require [cs.a :as a]))\n(defn g [x] (a/f x))\n"))
        cs  (refactor/ns-rename-changeset st2 'cs.a 'cs.x)]
    (is (seq cs))
    (is (every? #(some? (store/form-symbol %)) (vals cs))
        (pr-str (map store/form-symbol (vals cs))))))
(deftest keyed-matches-address-maps-by-content
  (let [reg (st (str "(ns sp.core)\n"
                     "(def tools\n"
                     "  [{:name \"alpha\" :description \"a\"}\n"
                     "   {:name \"beta\" :description \"b\"}\n"
                     "   {:name \"gamma\" :description \"g\"}])\n"))]
    (testing "a where-map addresses the one map containing it"
      (let [plan (refactor/keyed-replace-plan reg 'sp.core 'tools
                                              {:name "beta"}
                                              "{:name \"beta\" :description \"B2\"}")]
        (is (nil? (:error plan)) (pr-str plan))
        (is (re-find #"B2" (str (:new-form-src plan))))
        (is (re-find #"\"alpha\"" (str (:new-form-src plan))))))
    (testing "no hit teaches"
      (is (re-find #"no map containing"
                   (str (:error (refactor/keyed-replace-plan reg 'sp.core 'tools
                                                             {:name "zeta"} "{}"))))))
    (testing "ambiguity teaches"
      (let [dup (st "(ns sp.core)\n(def xs [{:k 1 :v 2} {:k 1 :v 3}])\n")]
        (is (re-find #"2 maps"
                     (str (:error (refactor/keyed-replace-plan dup 'sp.core 'xs
                                                               {:k 1} "{}")))))))))
