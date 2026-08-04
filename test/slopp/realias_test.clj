(ns slopp.realias-test
  "`ns_realias` end to end: the `:as` in a namespace's ns form and every
  `alias/sym` in its bodies, rewritten as ONE intent through `edit-group!` —
  one gate pass, one verification.

  The planner's correctness lives with the other planners in
  `slopp.edit.refactor-test`. What is only observable HERE is that the two
  halves land together: split across two writes, the first one leaves a
  namespace whose ns form and bodies disagree, which does not load."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops]
            [slopp.ops.external :as external]))

(deftest ^:external realias-moves-the-declaration-and-its-sites-together
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'ra.e2e.dep :source "(ns ra.e2e.dep)\n(defn f [x] (inc x))\n")
      (ops/create-ns! sess 'ra.e2e.user
                      :source (str "(ns ra.e2e.user (:require [ra.e2e.dep :as d]\n"
                                   "                          [clojure.string :as s]\n"
                                   "                          [clojure.test :refer [deftest is]]))\n"
                                   ;; `d` is BOTH the alias and the parameter, three
                                   ;; tokens apart. g-t is what makes that a proof
                                   ;; rather than a hope: capture the local and the
                                   ;; form does not compile, rewrite nothing and
                                   ;; :sites below is 0.
                                   "(defn g [d] (s/join \"-\" [(d/f d) d]))\n"
                                   "(deftest g-t (is (= \"3-2\" (g 2))))\n"))
      (testing "the ns form and the call sites move as ONE verified write"
        (let [r (ops/realias! sess 'ra.e2e.user 'd 'dep)]
          (is (nil? (:error r)) (pr-str r))
          (is (= 1 (:sites r)) (pr-str r))
          (is (zero? (get-in r [:test :fail] 0)) (pr-str (:test r)))))
      (testing "the DECLARATION moved, not just the call sites — asked through
                the surface rather than by reading source back: the old
                spelling is now an alias this namespace does not have"
        (let [r (ops/realias! sess 'ra.e2e.user 'd 'dep2)]
          (is (:error r))
          (is (re-find #"no alias d\b" (str (:error r))) (pr-str r))))
      (testing "a spelling another require already holds is refused by the
                plan, so nothing is written and nothing needs rolling back"
        (let [r (ops/realias! sess 'ra.e2e.user 'dep 's)]
          (is (:error r))
          (is (re-find #"clojure\.string" (str (:error r))) (pr-str r))))
      (finally (ops/close! sess)))))
