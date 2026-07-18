(ns slopp.api.rules-test
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.api.rules :as rules]))

(deftest done-advisory-registry-and-severity
  (testing "the registry carries every done-time advisory with a key, severity, and check"
    (is (= #{:schema-drift :key-typos :breaking-changes}
           (set (map :key rules/done-advisories))))
    (is (every? (fn [r] (and (:severity r) (:check r))) rules/done-advisories))
    (testing "schema-drift is status-affecting (a lying schema is a real failure); the rest advise"
      (is (= :error (:severity (first (filter #(= :schema-drift (:key %)) rules/done-advisories)))))
      (is (= :advisory (:severity (first (filter #(= :key-typos (:key %)) rules/done-advisories)))))))
  (testing "status-affecting-fired? — only an :error-severity advisory with results flips it"
    (is (true?  (rules/status-affecting-fired? {:schema-drift [{:form 'a/b}]})))
    (is (false? (rules/status-affecting-fired? {:key-typos [{:used :a/b}]})))
    (is (false? (rules/status-affecting-fired? {:breaking-changes [{:form 'a/b}]})))
    (is (false? (rules/status-affecting-fired? {})))))
