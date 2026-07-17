(ns slopp.rt-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.rt :as rt]))
^:unsafe (deftest instrument-seam-records-and-restores
  ;; THE instrumentation seam (#121). traced-run had this loop inline; the
  ;; external trace runner needs the SAME wrapping around cognitect's runner
  ;; instead of around its own per-var loop. Extracted so there is exactly one
  ;; tracer — never two.
  (let [n (create-ns 'rt-probe.core)]
    (intern n 'twice (fn [x] (* 2 x)))
    (intern n 'not-a-fn 42)
    (let [touched   (atom #{})
          originals (rt/instrument! ['rt-probe.core] touched)]
      (try
        (testing "a wrapped fn still returns its value AND records itself"
          (is (= 4 ((ns-resolve n 'twice) 2)))
          (is (= '#{rt-probe.core/twice} @touched)))
        (testing "non-fn vars are left alone"
          (is (= 42 @(ns-resolve n 'not-a-fn))))
        (finally (rt/restore! originals))))
    (testing "restore! un-wraps — instrumentation is temporary"
      (let [touched (atom #{})]
        (is (= 4 ((ns-resolve n 'twice) 2)))
        (is (= #{} @touched))))))
