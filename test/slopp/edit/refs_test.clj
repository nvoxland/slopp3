(ns slopp.edit.refs-test
  "THE reference graph: every kind of reference — kondo-static, carrier
  positions, declarations — as ONE canonical record stream all tools
  consume. Producers normalize here; consumers never re-integrate."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.edit.refs :as refs]
            [slopp.store :as store]))
(deftest the-graph-sees-every-reference-kind
  (let [st (-> (store/empty-store)
               (store/ingest 'g.core
                             (str "(ns g.core)\n\n"
                                  "(defn ^:entry-point serve \"S.\" [x] x)\n\n"
                                  "(defn helper \"H.\" [x] x)\n\n"
                                  "(defn ^:unused-ok spare \"P.\" [x] x)\n"))
               (store/ingest 'g.app
                             (str "(ns g.app (:require [g.core :as core]))\n\n"
                                  "(defn go \"G.\" [x] (core/helper x))\n\n"
                                  "(defn drive \"D.\" [sess]\n"
                                  "  (query-call sess 'g.core/helper 2))\n"))
               (store/ingest 'g.sneak
                             (str "(ns g.sneak)\n\n"
                                  "(defn s \"S.\" [x] (g.core/helper x))\n")))]
    (testing "static (required AND un-required qualified), carrier, declared"
      (let [rs (refs/refs-to st 'g.core/helper)
            by (fn [nsx] (set (map :via (filter #(= nsx (:from-ns %)) rs))))]
        (is (= #{:static :carrier} (by 'g.app)) (pr-str rs))
        (is (= #{:static} (by 'g.sneak)) "the gate-hole class is a static ref")))
    (testing "declarations are edges from the outside world"
      (let [r (first (refs/refs-to st 'g.core/serve))]
        (is (= :declared (:via r)))
        (is (= :entry-point (:marker r)))
        (is (= :external (:from-ns r))))
      (is (= :unused-ok (:marker (first (refs/refs-to st 'g.core/spare))))))
    (testing "records anchor to the owning FORM, not positions"
      (let [r (first (filter #(and (= :static (:via %)) (= 'g.app (:from-ns %)))
                             (refs/refs-to st 'g.core/helper)))]
        (is (= 'go (:name (store/form-by-id st (:from-form r)))))))
    (testing "self-references are excluded; unknown targets empty"
      (is (empty? (refs/refs-to st 'g.core/nope))))))
