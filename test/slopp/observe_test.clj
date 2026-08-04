(ns slopp.observe-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops] [slopp.ops.external :as external]))

(deftest ^:external observe-captures-what-flows-through
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ob.core
                   (str "(ns ob.core)\n"
                        "(defn area [shape] (* (:w shape) (:h shape)))\n"
                        "(defn total-area [shapes] (reduce + (map area shapes)))\n"
                        "(defn risky [x] (if (neg? x) (throw (ex-info \"neg!\" {})) x))\n"))
      (testing "args and returns are captured per call, driver result included"
        (let [r (ops/query-observe sess 'ob.core 'area
                                   "(ob.core/total-area [{:w 2 :h 3} {:w 4 :h 5}])")]
          (is (= 2 (:count r)))
          (is (= "26" (:result r)))
          (is (= ["{:w 2, :h 3}"] (:args (first (:calls r)))))
          (is (= "6" (:ret (first (:calls r)))))))
      (testing "exceptions are recorded, not swallowed"
        (let [r (ops/query-observe sess 'ob.core 'risky
                                   "(try (ob.core/risky -1) (catch Exception _ :caught))")]
          (is (= ":caught" (:result r)))
          (is (re-find #"neg!" (:threw (first (:calls r)))))))
      (testing "the sample limit bounds capture"
        (let [r (ops/query-observe sess 'ob.core 'area
                                   "(ob.core/total-area (repeat 50 {:w 1 :h 1}))"
                                   :limit 5)]
          (is (= 5 (:count r)))))
      (testing "instrumentation is restored afterwards"
        (is (= [6] (ops/query-eval sess "(ob.core/area {:w 2 :h 3})"))))
      (testing "the driver is observe-gated (T5 applies here too)"
        (is (:error (ops/query-observe sess 'ob.core 'area "(def sneaky 1)"))))
      (finally (ops/close! sess)))))

(deftest ^:external macroexpand-is-a-first-class-question
  (let [sess (external/open!)]
    (try
      (let [r (ops/query-macroexpand sess "(when x y z)")]
        (is (re-find #"\(if x" (:expand-1 r)))
        (is (re-find #"do" (:expand-1 r))))
      (testing "unparseable input errors cleanly"
        (is (:error (ops/query-macroexpand sess "(when x"))))
      (finally (ops/close! sess)))))
