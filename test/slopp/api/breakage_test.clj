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

(deftest node-boundary-and-schema-keys
  (testing "node-boundary?: public defn in a root ns (<=2 segs), or ^:export in a deep ns"
    (is (true?  (breakage/node-boundary? 'app.core '(defn f [x] x))))
    (is (false? (breakage/node-boundary? 'app.core '(defn- f [x] x))))
    (is (false? (breakage/node-boundary? 'app.core.impl '(defn f [x] x))))
    (is (true?  (breakage/node-boundary? 'app.core.impl '(defn ^:export f [x] x)))))
  (testing "removed-schema-keys: arg-map keys in the old :=> schema, gone from the new"
    (is (= #{:b} (breakage/removed-schema-keys
                  '(defn ^{:malli/schema [:=> [:cat [:map [:a :int] [:b :int]]] :int]} f [m] 1)
                  '(defn ^{:malli/schema [:=> [:cat [:map [:a :int]]] :int]} f [m] 1))))
    (testing "adding a key is accretion"
      (is (empty? (breakage/removed-schema-keys
                   '(defn ^{:malli/schema [:=> [:cat [:map [:a :int]]] :int]} f [m] 1)
                   '(defn ^{:malli/schema [:=> [:cat [:map [:a :int] [:b :int]]] :int]} f [m] 1)))))
    (testing "no :=> schema on either side → nothing"
      (is (empty? (breakage/removed-schema-keys '(defn f [m] 1) '(defn f [m] 1)))))))

(deftest ^:isolated done-flags-visibility-and-schema-key-narrowing
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'bc2.core
                   "(ns bc2.core)\n(defn ^{:malli/schema [:=> [:cat [:map [:a :int] [:b :int]]] :int]} shaped \"S.\" [m] (:a m))\n")
      (api/ingest! sess 'bc2.core.impl
                   "(ns bc2.core.impl)\n(defn ^:export widget \"W.\" [x] x)\n")
      (api/done! sess :label "baseline")
      (testing "dropping a :=> arg-map key at a boundary is flagged"
        (api/edit-replace! sess 'bc2.core 'shaped
                           "(defn ^{:malli/schema [:=> [:cat [:map [:a :int]]] :int]} shaped \"S.\" [m] (:a m))"
                           :prompt "drop the :b key")
        (let [r (api/done! sess :label "drop-key")]
          (is (= [{:form 'bc2.core/shaped :removed-keys [:b]}]
                 (get-in r [:findings :breaking-changes])) (pr-str (:findings r)))))
      (testing "un-exporting a boundary fn (visibility narrowing) is flagged"
        (api/edit-replace! sess 'bc2.core.impl 'widget "(defn widget \"W.\" [x] x)"
                           :prompt "drop ^:export")
        (let [r (api/done! sess :label "unexport")]
          (is (= [{:form 'bc2.core.impl/widget :visibility-narrowed true}]
                 (get-in r [:findings :breaking-changes])) (pr-str (:findings r)))))
      (finally (api/close! sess)))))

(deftest node-boundary-any-truthy-export
  (testing "any truthy :export makes a deep-ns fn a boundary (aligns with export-level)"
    (is (true?  (breakage/node-boundary? 'app.core.impl '(defn ^{:export :yes} f [x] x))))
    (is (true?  (breakage/node-boundary? 'app.core.impl '(defn ^{:export 42} f [x] x))))
    (is (false? (breakage/node-boundary? 'app.core.impl '(defn ^{:export false} f [x] x))))
    (is (false? (breakage/node-boundary? 'app.core.impl '(defn ^{:export nil} f [x] x))))))
