(ns slopp.ui.nsfilter-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ui.nsfilter :as nsf]))

(deftest matches?-is-a-trimmed-case-insensitive-substring
  (testing "an empty or blank needle matches everything (nothing typed = show all)"
    (is (nsf/matches? "" "slopp.http.browse"))
    (is (nsf/matches? "   " "slopp.http.browse")))
  (testing "case-insensitive substring match"
    (is (nsf/matches? "HTTP" "slopp.http.browse"))
    (is (nsf/matches? "browse" "slopp.http.browse"))
    (is (not (nsf/matches? "zzz" "slopp.http.browse"))))
  (testing "the needle is trimmed before matching"
    (is (nsf/matches? "  http  " "slopp.http.browse"))
    (is (not (nsf/matches? "  zzz  " "slopp.http.browse")))))
