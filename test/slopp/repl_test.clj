(ns slopp.repl-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.repl :as repl]))

(deftest ^:external owned-repl-eval-and-restart
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

(deftest inherent-deps-ride-every-image
  ;; malli + nrepl ship WITH slopp (inherent), merged into every image's -Sdeps
  ;; — NOT via the project manifest (deps_add), so they are unremovable and
  ;; centrally versioned. Image-tier only (the server runs on kernel deps).
  (let [sdeps (nth (#'slopp.repl/default-cmd nil) 2)]
    (is (re-find #"metosin/malli" sdeps))
    (is (re-find #"nrepl/nrepl" sdeps)))
  (testing "inherent deps win a colliding manifest entry (slopp controls versions)"
    (let [sdeps (nth (#'slopp.repl/default-cmd '{metosin/malli {:mvn/version "0.0.0"}}) 2)]
      (is (re-find #"0\.17\.0" sdeps))
      (is (not (re-find #"0\.0\.0" sdeps))))))
