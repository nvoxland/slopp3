(ns slopp.image-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.repl :as repl]
            [slopp.image :as image]))

(def target
  (str "(ns demo\n"
       "  (:require [clojure.test :refer [deftest is]]))\n\n"
       "(defn add [x y] (+ x y))\n\n"
       "(deftest add-works (is (= 5 (add 2 3))))\n"))

(deftest load-and-test-run
  (let [s (store/ingest (store/empty-store) 'demo target)
        h (repl/start!)]
    (try
      (image/load-ns! h s 'demo)
      (testing "the namespace lives in the image (loaded from the store, no disk)"
        (is (= [5] (repl/eval! h "(demo/add 2 3)"))))
      (testing "test.run reports green"
        (let [r (image/test-run h 'demo)]
          (is (= 1 (:pass r)))
          (is (= 0 (:fail r)))
          (is (= 0 (:error r)))
          (testing "and the result is recorded on a delta (provenance, C4)"
            (let [s2 (store/record-verification s 'demo r)
                  d  (last (store/deltas s2))]
              (is (= :verify (:op d)))
              (is (= 'demo (:ns d)))
              (is (= r (:result d)))))))
      (finally (repl/stop! h)))))
