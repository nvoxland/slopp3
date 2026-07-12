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
  (let [msg (edit/lint-refusals (st clean) (st bad) ['lg.core])]
    (is (some? msg))
    (is (re-find #"invalid-arity" (str msg)))))

(deftest clean-writes-pass
  (is (nil? (edit/lint-refusals (st clean) (st clean) ['lg.core]))))

(deftest pre-existing-errors-do-not-block
  (testing "base already has the error — the write is not the one to blame"
    (is (nil? (edit/lint-refusals (st bad) (st bad) ['lg.core])))))
(deftest arity-refusals-suggest-the-atomic-resolution
  (testing "P2a: the discovered resolution is now IN the refusal"
    (let [msg (edit/lint-refusals (st clean) (st bad) ['lg.core])]
      (is (re-find #"edit_group" (str msg)))
      (is (re-find #"change_signature" (str msg)))))
  (testing "non-arity errors don't get the signature hint"
    (let [unresolved "(ns lg.core)\n(defn f [x] x)\n(defn g [] (nope 1))\n"
          msg (edit/lint-refusals (st clean) (st unresolved) ['lg.core])]
      (is (some? msg))
      (is (not (re-find #"edit_group" (str msg)))))))
