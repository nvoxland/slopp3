(ns slopp.store.semver-test
  "`slopp.store.semver` — comparing two version strings, used wherever a store
  decides whether a dependency or a published contract version is newer than
  the one it holds.

  A generic utility rather than a store concept, and small enough to be
  covered exhaustively on shape rather than illustratively: segments compare
  numerically (so 10 beats 9), and `older?` is `newer?` flipped, asserted
  rather than assumed because a comparator that is wrong only at the boundary
  is a comparator that looks right."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store.semver :as semver]))

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
  (is (true? (slopp.store.semver/older? "1.2.0" "1.10.0")))
  (is (false? (slopp.store.semver/older? "1.10.0" "1.2.0"))))
