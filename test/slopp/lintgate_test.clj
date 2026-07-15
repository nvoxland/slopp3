(ns slopp.lintgate-test
  "The lint gate: a write that INTRODUCES an error-level kondo finding is
  refused; pre-existing errors don't block. Pure — stores built with ingest."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.edit :as edit]
            [slopp.store :as store]))

(defn- st [src] (store/ingest (store/empty-store) 'lg.core src))

(def clean "(ns lg.core)\n(defn f [x] x)\n(defn g [] (f 1))\n")
(def bad   "(ns lg.core)\n(defn f [x] x)\n(defn g [] (f 1 2))\n")

(deftest introducing-an-arity-error-is-refused
  (testing "an arity error in a form NOT being written CARRIES (REPL flow)"
    (let [r (edit/lint-refusals (st clean) (st bad) ['lg.core] [])]
      (is (nil? (:refuse r)) (pr-str r))
      (is (some #(re-find #"invalid-arity" (name (:type %))) (:carried r))
          (pr-str r))))
  (testing "the SAME error refuses when it is in the form being written"
    (let [g-fid (:id (store/form-named (st bad) 'lg.core 'g))
          r     (edit/lint-refusals (st clean) (st bad) ['lg.core] [g-fid])]
      (is (re-find #"in the form you are writing" (str (:refuse r))) (pr-str r))
      (is (re-find #"invalid-arity" (str (:refuse r)))))))

(deftest clean-writes-pass
  (is (nil? (edit/lint-refusals (st clean) (st clean) ['lg.core] []))))

(deftest pre-existing-errors-do-not-block
  (testing "base already has the error — the write is not the one to blame"
    (is (nil? (edit/lint-refusals (st bad) (st bad) ['lg.core] [])))))
