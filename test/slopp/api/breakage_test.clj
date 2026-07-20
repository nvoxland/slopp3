(ns slopp.api.breakage-test
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.api.breakage :as breakage]
            [slopp.api :as api] [slopp.api.external :as external]))

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

(deftest ^:external done-flags-arity-narrowing-on-a-boundary-fn
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'bc.core
                   "(ns bc.core)\n(defn ^:unused-ok handle \"H.\" ([x] x) ([x y] (+ x y)))\n")
      (external/done! sess :label "baseline")
      (api/edit-replace! sess 'bc.core 'handle
                         "(defn ^:unused-ok handle \"H.\" [x] x)"
                         :prompt "narrow away the 2-arity")
      (let [r (external/done! sess :label "narrow")]
        (testing "the removed arity on a boundary fn is flagged"
          (is (= [{:form 'bc.core/handle :removed-arities [2]}]
                 (get-in r [:findings :breaking-changes])) (pr-str (:findings r)))))
      (testing "advisory only — a breaking change does NOT flip test-status red"
        (api/edit-replace! sess 'bc.core 'handle
                           "(defn ^:unused-ok handle \"H.\" ([x] x) ([_x y] y) ([_x _y z] z))"
                           :prompt "widen — accretion")
        (let [r (external/done! sess :label "widen")]
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

(deftest ^:external done-flags-visibility-and-schema-key-narrowing
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'bc2.core
                   "(ns bc2.core)\n(defn ^{:malli/schema [:=> [:cat [:map [:a :int] [:b :int]]] :int]} shaped \"S.\" [m] (:a m))\n")
      (api/ingest! sess 'bc2.core.impl
                   "(ns bc2.core.impl)\n(defn ^:export widget \"W.\" [x] x)\n")
      (external/done! sess :label "baseline")
      (testing "dropping a :=> arg-map key at a boundary is flagged"
        (api/edit-replace! sess 'bc2.core 'shaped
                           "(defn ^{:malli/schema [:=> [:cat [:map [:a :int]]] :int]} shaped \"S.\" [m] (:a m))"
                           :prompt "drop the :b key")
        (let [r (external/done! sess :label "drop-key")]
          (is (= [{:form 'bc2.core/shaped :removed-keys [:b]}]
                 (get-in r [:findings :breaking-changes])) (pr-str (:findings r)))))
      (testing "un-exporting a boundary fn (visibility narrowing) is flagged"
        (api/edit-replace! sess 'bc2.core.impl 'widget "(defn widget \"W.\" [x] x)"
                           :prompt "drop ^:export")
        (let [r (external/done! sess :label "unexport")]
          (is (= [{:form 'bc2.core.impl/widget :visibility-narrowed true}]
                 (get-in r [:findings :breaking-changes])) (pr-str (:findings r)))))
      (finally (api/close! sess)))))

(deftest node-boundary-any-truthy-export
  (testing "any truthy :export makes a deep-ns fn a boundary (aligns with export-level)"
    (is (true?  (breakage/node-boundary? 'app.core.impl '(defn ^{:export :yes} f [x] x))))
    (is (true?  (breakage/node-boundary? 'app.core.impl '(defn ^{:export 42} f [x] x))))
    (is (false? (breakage/node-boundary? 'app.core.impl '(defn ^{:export false} f [x] x))))
    (is (false? (breakage/node-boundary? 'app.core.impl '(defn ^{:export nil} f [x] x))))))

(deftest ^:external breaking-ok-marks-a-deliberate-break-and-polices-itself
  ;; breaking-changes sat at :advisory because a CORRECT change — privatising a
  ;; fn with no outside callers — was flagged with no escape. A rule you cannot
  ;; discharge has to be ignorable, and an ignorable rule is not a rule.
  ;;
  ;; The ns is DEEP (bok.core.impl): ^:export only widens visibility below the
  ;; module surface, so dropping it from a top-level ns narrows nothing.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'bok.core.impl
                   (str "(ns bok.core.impl)\n"
                        "(defn ^:export ^:unused-ok gone \"G.\" [x] x)\n"
                        "(defn ^:export ^:unused-ok kept \"K.\" [x] x)\n"))
      (external/done! sess :label "baseline")
      (testing "an unmarked narrowing is still flagged — the rule is not weakened"
        (api/edit-replace! sess 'bok.core.impl 'gone
                           "(defn ^:unused-ok gone \"G.\" [x] x)"
                           :prompt "privatise")
        (let [r (external/done! sess :label "unmarked")]
          (is (= [{:form 'bok.core.impl/gone :visibility-narrowed true}]
                 (get-in r [:findings :breaking-changes])) (pr-str (:findings r)))))
      (testing "^:breaking-ok discharges it"
        (api/edit-replace! sess 'bok.core.impl 'kept
                           "(defn ^:breaking-ok ^:unused-ok kept \"K.\" [x] x)"
                           :prompt "privatise, deliberately")
        (let [r (external/done! sess :label "marked")]
          (is (nil? (get-in r [:findings :breaking-changes]))
              (pr-str (:findings r)))))
      (testing "a marker on a form that narrowed NOTHING says so, so it cannot decay"
        (api/edit-replace! sess 'bok.core.impl 'kept
                           "(defn ^:breaking-ok ^:unused-ok kept \"K2.\" [x] x)"
                           :prompt "touch it again, no narrowing this time")
        (let [f (get-in (external/done! sess :label "stale") [:findings :breaking-changes])]
          (is (= ['bok.core.impl/kept] (mapv :form f)) (pr-str f))
          (is (:stale-marker (first f)) (pr-str f))))
      (finally (api/close! sess)))))
