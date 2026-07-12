(ns slopp.repl-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.repl :as repl]))

(deftest ^:isolated owned-repl-eval-and-restart
  (let [h (repl/start!)]
    (try
      (testing "evaluates in the owned image"
        (is (= [3] (repl/eval! h "(+ 1 2)"))))
      (testing "definitions persist within the live image (the refresh model, D5)"
        (repl/eval! h "(def marker 41)")
        (is (= [42] (repl/eval! h "(inc marker)")))
        (is (= [true] (repl/eval! h "(some? (resolve 'marker))"))))
      (let [h2 (repl/restart! h)]
        (try
          (testing "restart yields a faithful, EMPTY image — marker is gone (D5 backstop)"
            (is (= [3] (repl/eval! h2 "(+ 1 2)")))
            (is (= [false] (repl/eval! h2 "(some? (resolve 'marker))"))))
          (finally (repl/stop! h2))))
      (finally (repl/stop! h)))))
