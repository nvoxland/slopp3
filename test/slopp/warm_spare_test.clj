(ns slopp.warm-spare-test
  "The WARM SPARE: a second image booting ahead of the restart that will want
  it.

  One claim, and it is the only one worth making — that keeping a spare makes
  a restart cheap. The spare is a pure optimisation, so its correctness
  reduces to \"the image you get is the image you would have got\"; anything
  else asserted here would be asserting the timing of an implementation
  detail. Recorded in `.context/design-disciplines.md` as a measured DEAD END
  in its pooled form, which is why this stayed one test rather than growing."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops] [slopp.ops.external :as external]))

(deftest ^:external warm-spare-makes-restart-cheap
  (let [sess (external/open! {:slopp.ops/warm-spare? true})]
    (try
      (ops/ingest! sess 'wdemo "(ns wdemo)\n(def x 1)\n")
      (let [spare1   @(:spare @sess)              ; wait until the spare is ready
            old-port (:port (:image @sess))
            t0       (System/nanoTime)]
        (ops/restart! sess)
        (let [ms (/ (- (System/nanoTime) t0) 1e6)]
          (testing "restart swapped to the pre-warmed process"
            (is (= (:port spare1) (:port (:image @sess))))
            (is (not= old-port (:port (:image @sess)))))
          (testing "the swap avoids a JVM boot on the critical path"
            (is (< ms 3000) (str "restart took " ms "ms")))))
      (testing "the store was reloaded into the fresh image"
        (is (= [1] (ops/query-eval sess "wdemo/x"))))
      (testing "a new spare is warming behind it"
        (is (some? (:spare @sess))))
      (finally (ops/close! sess)))))
