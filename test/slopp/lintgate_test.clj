(ns slopp.lintgate-test
  "The lint gate: a write that INTRODUCES an error-level kondo finding is
  refused; pre-existing errors don't block. Pure — stores built with ingest."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.edit :as edit]
            [slopp.store :as store]))

(def clean "(ns lg.core)\n(defn f [x] x)\n(defn g [] (f 1))\n")
(def bad   "(ns lg.core)\n(defn f [x] x)\n(defn g [] (f 1 2))\n")

(defn- st [src] (store/ingest (store/empty-store) 'lg.core src))

(deftest introducing-an-arity-error-is-refused
  (let [msg (edit/lint-refusals (st clean) (st bad) ['lg.core])]
    (is (some? msg))
    (is (re-find #"invalid-arity" (str msg)))))

(deftest clean-writes-pass
  (is (nil? (edit/lint-refusals (st clean) (st clean) ['lg.core]))))

(deftest pre-existing-errors-do-not-block
  (testing "base already has the error — the write is not the one to blame"
    (is (nil? (edit/lint-refusals (st bad) (st bad) ['lg.core])))))