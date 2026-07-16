(ns slopp.api.modules-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api] [slopp.api.modules :as modules] [slopp.store :as store]))

(deftest ^:isolated the-module-surface-is-browsable
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ma.core
                   (str "(ns ma.core)\n"
                        "(defn shared \"Public.\" [x] x)\n"
                        "(defn- internal [x] x)\n"))
      (api/ingest! sess 'ma.core.impl
                   (str "(ns ma.core.impl)\n"
                        "(defn hidden \"Package.\" [x] x)\n"
                        "(defn ^:export hoisted \"World.\" [x] x)\n"
                        "(defn ^{:export \"ma.core\"} scoped \"Module-wide.\" [x] x)\n"))
      (api/module-dep! sess "mb.app" "ma.core" :prompt "consumer")
      (api/ingest! sess 'mb.app
                   (str "(ns mb.app (:require [ma.core :as core]))\n"
                        "(defn go \"Runs.\" [x] (core/shared x))\n"))
      (let [r     (modules/module-surface sess "ma.core")
            names (into #{} (map (juxt :ns :name)) (:surface r))]
        (testing "public fns and exported deep vars ride; private and hidden don't"
          (is (contains? names ['ma.core 'shared]) (pr-str r))
          (is (contains? names ['ma.core.impl 'hoisted]))
          (is (contains? names ['ma.core.impl 'scoped]))
          (is (not (contains? names ['ma.core 'internal])))
          (is (not (contains? names ['ma.core.impl 'hidden]))))
        (testing "rows carry sig, first doc line, and the export level"
          (let [hoisted (first (filter #(= 'hoisted (:name %)) (:surface r)))
                scoped  (first (filter #(= 'scoped (:name %)) (:surface r)))]
            (is (= '[x] (:sig hoisted)))
            (is (= "World." (:doc hoisted)))
            (is (true? (:export hoisted)))
            (is (= "ma.core" (:export scoped)))))
        (testing "deps and consumers come from the manifest"
          (is (= ["ma.core"] (get-in (modules/module-surface sess "mb.app") [:deps]))
              "mb.app declares ma.core")
          (is (= ["mb.app"] (:consumers r)))))
      (testing "an unknown module teaches the list"
        (is (re-find #"modules true" (str (:error (modules/module-surface sess "zz.nope"))))))
      (testing "the graph view: layers, and drift toward DEAD edges is named"
        (api/module-dep! sess "mb.app" "mc.ghost" :prompt "declared, never used")
        (let [r (api/query-depends sess nil :modules true)]
          ;; mc.ghost is a phantom (declared edge, NO code) — production layers
          ;; exclude it, though :unused-edges still flags the dead edge
          (is (= [["ma.core"] ["mb.app"]] (:layers r)) (pr-str r))
          (is (= [["mb.app" "mc.ghost"]] (:unused-edges r)))
          (is (re-find #"remove true" (:unused-note r)))
          (is (nil? (:cycles r)))))
      (finally (api/close! sess)))))

(deftest ^:isolated module-graph-views-use-production-edges
  ;; -test namespaces fold into the subject module, so their fixture deps
  ;; pollute the graph and manufacture cycles that don't exist in
  ;; production. The DECLARED manifest keeps them (enforcement); the
  ;; layers/cycles VIEW must reflect production only. Mirrors slopp's own
  ;; adopted-store situation.
  (let [sess (api/open!)]
    (try
      ;; adoption-style setup (gate off) so we can land the cyclic fixture edge
      (swap! sess assoc :adopting? true)
      (api/ingest! sess 'pa.core "(ns pa.core)\n(defn base \"B.\" [x] x)\n")
      (api/ingest! sess 'pb.app
                   (str "(ns pb.app (:require [pa.core :as c]))\n"
                        "(defn go \"G.\" [x] (c/base x))\n"))
      (api/ingest! sess 'pa.core-test
                   (str "(ns pa.core-test (:require [pb.app :as app]\n"
                        "                           [clojure.test :refer [deftest is]]))\n"
                        "(deftest go-t (is (= 1 (app/go 1))))\n"))
      (swap! sess dissoc :adopting?)
      (api/adopt-modules! sess)   ; records pb.app→pa.core AND (test) pa.core→pb.app
      (let [r (api/query-depends sess nil :modules true)]
        (testing "the DECLARED manifest still carries the test-fixture back-edge"
          (is (contains? (set (get-in r [:manifest "pa.core"])) "pb.app")
              (pr-str (:manifest r))))
        (testing "the production graph is acyclic — no false cycle in the view"
          (is (nil? (:cycles r)) (pr-str (:cycles r))))
        (testing "layers reflect production: pa.core sits below pb.app"
          (let [layer-of (into {} (for [[i layer] (map-indexed vector (:layers r))
                                        m layer] [m i]))]
            (is (< (layer-of "pa.core") (layer-of "pb.app"))
                (pr-str (:layers r))))))
      (finally (api/close! sess)))))
(deftest entry-points-and-carriers-are-real-references
  ;; the designated-carrier decision: references may live in data ONLY
  ;; through blessed forms. ^:entry-point DECLARES outside-world invocation
  ;; (exempt from the unused gate — no stale symmetry, the outside world is
  ;; statically unverifiable); quoted symbols in CARRIER positions
  ;; (query-call, late-ref, invoke!) COUNT as use.
  (let [base (-> (store/empty-store)
                 (store/ingest 'cr.core
                               (str "(ns cr.core)\n\n"
                                    "(defn ^:entry-point serve \"S.\" [x] x)\n\n"
                                    "(defn helper \"H.\" [x] x)\n\n"
                                    "(defn orphan \"O.\" [x] x)\n")))]
    (testing "^:entry-point exempts; carriers count; naked orphans still fail"
      (let [st (store/ingest base 'cr.driver
                             (str "(ns cr.driver)\n\n"
                                  "(defn drive \"D.\" [sess]\n"
                                  "  (query-call sess 'cr.core/helper 1))\n"))
            r  (modules/unused-report st '[cr.core])]
        (is (= '[cr.core/orphan] (:unused r)) (pr-str r))))
    (testing "without the carrier, the same quoted symbol is just data"
      (let [st (store/ingest base 'cr.driver
                             (str "(ns cr.driver)\n\n"
                                  "(defn drive \"D.\" [_]\n"
                                  "  ['cr.core/helper 1])\n"))
            r  (modules/unused-report st '[cr.core])]
        (is (= '[cr.core/helper cr.core/orphan] (:unused r)) (pr-str r))))))
