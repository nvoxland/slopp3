(ns slopp.api.breakage-test
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.api.breakage :as breakage]
            [slopp.api :as api]))

(deftest fixed-arities-and-removed
  (testing "single and multi fixed arities"
    (is (= #{1} (breakage/fixed-arities '(defn f "d" [x] x))))
    (is (= #{1 2} (breakage/fixed-arities '(defn f ([x] x) ([x y] (+ x y)))))))
  (testing "a variadic arity yields nil (variadic subsumption deferred)"
    (is (nil? (breakage/fixed-arities '(defn f [x & more] x)))))
  (testing "a non-defn yields nil"
    (is (nil? (breakage/fixed-arities '(def x 1)))))
  (testing "removed-arities = old fixed arities absent from new"
    (is (= #{2} (breakage/removed-arities '(defn f ([x] x) ([x y] y))
                                          '(defn f [x] x)))))
  (testing "adding an arity is accretion, never flagged"
    (is (empty? (breakage/removed-arities '(defn f [x] x)
                                          '(defn f ([x] x) ([x y] y))))))
  (testing "a variadic on either side is skipped (not falsely flagged)"
    (is (empty? (breakage/removed-arities '(defn f [x & r] x)
                                          '(defn f [x] x))))))

(deftest ^:isolated done-flags-arity-narrowing-on-a-boundary-fn
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'bc.core
                   "(ns bc.core)\n(defn handle \"H.\" ([x] x) ([x y] (+ x y)))\n")
      (api/done! sess :label "baseline")
      (api/edit-replace! sess 'bc.core 'handle
                         "(defn handle \"H.\" [x] x)"
                         :prompt "narrow away the 2-arity")
      (let [r (api/done! sess :label "narrow")]
        (testing "the removed arity on a boundary fn is flagged"
          (is (= [{:form 'bc.core/handle :removed-arities [2]}]
                 (get-in r [:findings :breaking-changes])) (pr-str (:findings r)))))
      (testing "advisory only — a breaking change does NOT flip test-status red"
        (api/edit-replace! sess 'bc.core 'handle
                           "(defn handle \"H.\" ([x] x) ([x y] y) ([x y z] z))"
                           :prompt "widen — accretion")
        (let [r (api/done! sess :label "widen")]
          (is (nil? (get-in r [:findings :breaking-changes])) (pr-str (:findings r)))
          (is (not= :red (get-in r [:findings :test-status])) (pr-str (:findings r)))))
      (finally (api/close! sess)))))
