(ns slopp.api.done-test
  "The done-point require-prune candidate logic — pure over the store, so it
   runs in-image. The effectful try-remove-verify-restore loop is exercised
   through a real session in slopp.api-test."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api.done :as done]))

(defn- store-with-requires []
  (store/ingest (store/empty-store) 'du.core
                (str "(ns du.core\n"
                     "  (:require [clojure.set :as s]\n"
                     "            ^:side-effect [du.eff :as e]\n"
                     "            [du.used :as u]))\n"
                     "(defn f [] (u/g))\n")))

(deftest unused-requires-finds-only-prunable-candidates
  (let [st (store-with-requires)
        cands (done/unused-requires st 'du.core)]
    (testing "an unused require is a candidate; a USED one is not"
      (is (= '[clojure.set] (mapv :lib cands)) (pr-str cands)))
    (testing "a candidate carries the ^:side-effect re-add form for the restore path"
      (is (= "^:side-effect [clojure.set :as s]" (:marked (first cands)))))
    (testing "a require already marked ^:side-effect is NOT re-offered (no churn)"
      (is (not (contains? (set (map :lib cands)) 'du.eff)) (pr-str cands)))))

(deftest side-effect-required-reads-the-marker
  (let [st (store-with-requires)]
    (is (true?  (done/side-effect-required? st 'du.core 'du.eff)))
    (is (false? (done/side-effect-required? st 'du.core 'clojure.set)))
    (is (false? (done/side-effect-required? st 'du.core 'du.used)))))
