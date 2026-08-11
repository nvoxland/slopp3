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
            [slopp.ops.external :as external] [slopp.store :as store] [slopp.edit.refactor :as refactor]))

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

(deftest an-alias-inside-a-nameless-form-is-rewritten-too
  ;; A `defmethod` has a dispatch value, not a name, so the plan's
  ;; `{:action :replace :name (:name e)}` addressed it as nil — which matched
  ;; every OTHER nameless form and refused as ambiguous. Measured on the real
  ;; store: this blocked the last of 129 canonical-alias renames, and the
  ;; refusal blamed a legacy declare that did not exist.
  (let [st (store/ingest (store/empty-store) 'ra.dep
                         "(ns ra.dep)\n\n(defn go \"G.\" [] :gone)\n")
        st (store/ingest st 'ra.core
                         (str "(ns ra.core (:require [ra.dep :as d]))\n\n"
                              "(defmulti kind :k)\n\n"
                              "(defmethod kind :a [_] (d/go))\n\n"
                              "(defmethod kind :b [_] :plain)\n\n"
                              "(defn named \"N.\" [] (d/go))\n"))
        plan (refactor/realias-plan st 'ra.core 'd 'dep)]
    (is (nil? (:error plan)) (pr-str plan))
    (is (= 2 (:sites plan)) "the defmethod's use counts like any other")
    (testing "every step addresses a form that exists, uniquely"
      (doseq [s (:steps plan)]
        (is (= 1 (count (store/forms-named st (:ns s) (:name s))))
            (str "step must address exactly one form: " (pr-str s)))))
    (testing "and the nameless form is among them, addressed by its id"
      (let [dm (first (filter #(and (nil? (:name %))
                                    (re-find #"d/go" (str (:node %))))
                              (store/forms st 'ra.core)))
            addressed (set (map :name (:steps plan)))]
        (is (some? dm) "fixture sanity: the defmethod that uses the alias")
        (is (contains? addressed (:id dm))
            (str "expected the id " (:id dm) " among " (pr-str addressed)))))))
