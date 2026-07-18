(ns slopp.api.telemetry-test
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.api.telemetry :as telemetry]
            [slopp.store :as store]))

(deftest rule-telemetry-fire-rate-and-persistence
  (let [[s1 _] (store/record-done (store/empty-store) "d1"
                                  :findings {:key-typos [{:used :a/emial :suggest :a/email}]
                                             :test-status :green})
        [s2 _] (store/record-done s1 "d2"
                                  :findings {:key-typos [{:used :a/emial :suggest :a/email}]
                                             :unused-public ['app.core/x]})
        [s3 _] (store/record-done s2 "d3" :findings {:test-status :green})
        t      (telemetry/rule-telemetry s3)]
    (testing "fire-rate: dones-fired + total instances per rule"
      (is (= 3 (get-in t [:window :dones])))
      (is (= 2 (get-in t [:fire-rate :key-typos :dones])))
      (is (= 2 (get-in t [:fire-rate :key-typos :instances])))
      (is (= 1 (get-in t [:fire-rate :unused-public :dones]))))
    (testing "persistence: the same instance flagged across >1 done is un-discharged"
      (is (= 1 (get-in t [:fire-rate :key-typos :persisted])))
      (is (= 0 (get-in t [:fire-rate :unused-public :persisted]))))
    (testing "metadata finding keys (test-status) are not counted as rule fires"
      (is (nil? (get-in t [:fire-rate :test-status]))))))
