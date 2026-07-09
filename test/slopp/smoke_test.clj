(ns slopp.smoke-test
  "Toolchain smoke test — confirms deps + test runner work end to end."
  (:require [clojure.test :refer [deftest is]]
            [rewrite-clj.zip :as z]))

(deftest toolchain-works
  (is (= 3 (+ 1 2))))

(deftest rewrite-clj-loads
  ;; rewrite-clj round-trips a form losslessly (the C1/C3 foundation).
  (is (= "(defn f [x]  x)" (z/root-string (z/of-string "(defn f [x]  x)")))))
