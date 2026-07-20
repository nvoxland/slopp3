(ns slopp.warm-spare-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api] [slopp.api.external :as external]))

(deftest ^:external warm-spare-makes-restart-cheap
  (let [sess (external/open! {:slopp.api/warm-spare? true})]
    (try
      (api/ingest! sess 'wdemo "(ns wdemo)\n(def x 1)\n")
      (let [spare1   @(:spare @sess)              ; wait until the spare is ready
            old-port (:port (:image @sess))
            t0       (System/nanoTime)]
        (api/restart! sess)
        (let [ms (/ (- (System/nanoTime) t0) 1e6)]
          (testing "restart swapped to the pre-warmed process"
            (is (= (:port spare1) (:port (:image @sess))))
            (is (not= old-port (:port (:image @sess)))))
          (testing "the swap avoids a JVM boot on the critical path"
            (is (< ms 3000) (str "restart took " ms "ms")))))
      (testing "the store was reloaded into the fresh image"
        (is (= [1] (api/query-eval sess "wdemo/x"))))
      (testing "a new spare is warming behind it"
        (is (some? (:spare @sess))))
      (finally (api/close! sess)))))
