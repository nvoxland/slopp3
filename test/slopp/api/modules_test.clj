(ns slopp.api.modules-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api] [slopp.api.modules :as modules] [slopp.store :as store] [slopp.edit.refs :as refs] [slopp.api.query :as query] [slopp.api.external :as external]))

(deftest ^:external the-module-surface-is-browsable
  (let [sess (external/open!)]
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
          (is (= ["ma.core"] (:deps (modules/module-surface sess "mb.app")))
              "mb.app declares ma.core")
          (is (= ["mb.app"] (:consumers r)))))
      (testing "an unknown module teaches the list"
        (is (re-find #"modules true" (str (:error (modules/module-surface sess "zz.nope"))))))
      (testing "the graph view: layers, and drift toward DEAD edges is named"
        (api/module-dep! sess "mb.app" "mc.ghost" :prompt "declared, never used")
        (let [r (query/query-depends sess nil :modules true)]
          ;; mc.ghost is a phantom (declared edge, NO code) — production layers
          ;; exclude it, though :unused-edges still flags the dead edge
          (is (= [["ma.core"] ["mb.app"]] (:layers r)) (pr-str r))
          (is (= [["mb.app" "mc.ghost"]] (:unused-edges r)))
          (is (re-find #"remove true" (:unused-note r)))
          (is (nil? (:cycles r)))))
      (finally (api/close! sess)))))

(deftest ^:external module-graph-views-use-production-edges
  ;; -test namespaces fold into the subject module, so their fixture deps
  ;; pollute the graph and manufacture cycles that don't exist in
  ;; production. The DECLARED manifest keeps them (enforcement); the
  ;; layers/cycles VIEW must reflect production only. Mirrors slopp's own
  ;; adopted-store situation.
  (let [sess (external/open!)]
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
      (let [r (query/query-depends sess nil :modules true)]
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
(deftest a-carrier-self-reference-does-not-keep-a-form-alive
  ;; regression: a form that carrier-references ITSELF was escaping the
  ;; dead-code gate (the graph's carrier producer lacked self-exclusion).
  (let [st (-> (store/empty-store)
               (store/ingest 'cs.core
                             (str "(ns cs.core)\n\n"
                                  "(defn loops \"L.\" [s] (query-call s 'cs.core/loops 1))\n\n"
                                  "(defn dead \"D.\" [x] x)\n")))]
    (is (= '[cs.core/dead cs.core/loops]
           (:unused (modules/unused-report st '[cs.core]))))))
(deftest method-bodies-feed-the-reference-graph
  ;; kondo RESOLVES the usages inside defmethod/defrecord/extend-* bodies —
  ;; aliases and all — but reports them with nil :from-var (the body is not a
  ;; var), and every edge builder filtered on :from-var. So a defn called only
  ;; from a method body read as unused-public (an error-grade gate at done!),
  ;; its blast radius was empty, and the module gates never saw the call.
  ;; The fix attributes those usages to the OWNING FORM via its rendered span.
  (let [st (-> (store/empty-store)
               (store/ingest 'g.core
                             (str "(ns g.core)\n\n"
                                  "(defn helper \"H.\" [x] (inc x))\n\n"
                                  "(defmulti area :shape)\n\n"
                                  "(defmethod area :square [s] (helper (:side s)))\n")))]
    (testing "a defn called ONLY from a defmethod body is NOT unused"
      (is (= [] (:unused (modules/unused-report st '[g.core])))
          (pr-str (modules/unused-report st '[g.core]))))
    (testing "the edge is attributed to the method's FORM (it has no var)"
      (let [rs (refs/refs-to st 'g.core/helper)
            m  (first (filter #(nil? (:name %)) (store/forms st 'g.core)))]
        (is (= [(:id m)] (mapv :from-form rs)) (pr-str rs))))))

(deftest ^:external module-graph-reports-purity-standing
  ;; The gates slopp enforces on a WRITE should also be readable as a REPORT
  ;; over existing code — otherwise a modernization pass has to reconstruct
  ;; them by hand. query_depends {modules true} is where someone asking "what
  ;; is this architecture" already looks, so purity standing belongs there:
  ;; what is declared, and which modules could carry a stricter tier than they
  ;; currently claim.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'pm.core "(ns pm.core)\n(defn add [x y] (+ x y))\n")
      (api/ingest! sess 'pm.edge
                   "(ns pm.edge)\n(defn roll [] (rand))\n")
      (let [r (query/query-depends sess nil :modules true)]
        (testing "a module whose code is clean is reported as tightenable"
          (is (= :pure (get-in r [:purity :could-tighten "pm.core" :supports]))
              (pr-str (:purity r))))
        (testing "a module reaching non-determinism is NOT offered :pure"
          (is (not= :pure
                    (get-in r [:purity :could-tighten "pm.edge" :supports]))
              (pr-str (:purity r)))))
      (testing "once declared, it is reported as declared and no longer offered"
        (api/module-tier! sess "pm.core" :pure :prompt "clean core")
        (let [r (query/query-depends sess nil :modules true)]
          (is (= :pure (get-in r [:purity :declared "pm.core"])) (pr-str r))
          (is (nil? (get-in r [:purity :could-tighten "pm.core"])) (pr-str r))))
      (finally (api/close! sess)))))

(deftest ^:external the-surface-answers-at-namespace-grain
  ;; Tiers became namespace-grained (a pure core one level below an effectful
  ;; module), so "what does this offer?" has to be answerable there too.
  ;; Asking about slopp.api.shape used to error with "no namespaces in module".
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'sg.core
                   "(ns sg.core)\n(defn ^:unused-ok top \"T.\" [x] x)\n")
      (api/ingest! sess 'sg.core.calc
                   (str "(ns sg.core.calc)\n"
                        "(defn ^:unused-ok add \"A.\" [a b] (+ a b))\n"
                        "(defn ^:unused-ok ^:private hidden \"H.\" [x] x)\n"))
      (testing "a MODULE still answers with its whole surface"
        (let [r (modules/module-surface sess "sg.core")]
          (is (some #(= 'top (:name %)) (:surface r)) (pr-str r))))
      (testing "a NAMESPACE answers with just its own surface"
        (let [r (modules/module-surface sess "sg.core.calc")]
          (is (nil? (:error r)) (pr-str r))
          (is (= '[add] (mapv :name (:surface r))) (pr-str r))
          (is (not-any? #(= 'top (:name %)) (:surface r))
              "the parent's forms are not this namespace's surface")))
      (testing "private stays out at either grain"
        (is (not-any? #(= 'hidden (:name %))
                      (:surface (modules/module-surface sess "sg.core.calc")))))
      (testing "and a name matching nothing still says so"
        (is (:error (modules/module-surface sess "sg.nope"))))
      (finally (api/close! sess)))))
