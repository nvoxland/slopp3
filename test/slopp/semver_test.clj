(ns slopp.semver-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.semver :as semver]))

(deftest parse-numeric-segments
  (is (= [1 2 3] (semver/parse "1.2.3")))
  (is (= [1 10 0] (semver/parse "1.10.0")))
  (testing "qualifiers are ignored"
    (is (= [2 0 1] (semver/parse "2.0.1-SNAPSHOT"))))
  (testing "long build segments survive"
    (is (= [7 3 0 202506031305] (semver/parse "7.3.0.202506031305-r")))))

(deftest newer?-compares-numerically
  (testing "numeric, not lexical (1.10 > 1.2)"
    (is (semver/newer? "1.10.0" "1.2.0"))
    (is (not (semver/newer? "1.2.0" "1.10.0"))))
  (testing "major/minor precedence"
    (is (semver/newer? "2.0.0" "1.9.9")))
  (testing "equal is not strictly newer"
    (is (not (semver/newer? "1.0.0" "1.0.0")))))
(deftest older-is-newer-flipped
  (is (true? (slopp.semver/older? "1.2.0" "1.10.0")))
  (is (false? (slopp.semver/older? "1.10.0" "1.2.0"))))
