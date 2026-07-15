(ns slopp.modules-test
  "The module system: recursive namespace visibility (depth ≤2 public,
  deeper scoped to the parent subtree), declared cross-module dependency
  edges (default-deny once a `modules` manifest exists), acyclic graph,
  and docstring warnings on the public surface."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.edit :as edit]
            [slopp.store :as store]))
(deftest module-of-is-the-first-two-segments
  (is (= "logi.quoting" (edit/module-of 'logi.quoting)))
  (is (= "logi.quoting" (edit/module-of 'logi.quoting.internal)))
  (is (= "scratch" (edit/module-of 'scratch))))
(deftest module-edges-are-crdt-grain
  (let [base    (store/empty-store)
        [s1 d1] (store/record-module-edge base "b.app" "a.core" :add
                                          :prompt "app uses core" :agent "t")
        [s2 d2] (store/record-module-edge s1 "b.app" "a.util" :add)
        [s3 d3] (store/record-module-edge s2 "b.app" "a.util" :remove)]
    (testing "the EDGE is the unit: one semantic delta each, state is the fold"
      (is (= :module-edge (:op d1)))
      (is (= {:from "b.app" :to "a.core" :action :add}
             (select-keys d1 [:from :to :action])))
      (is (= "app uses core" (:prompt d1)) "the why rides the delta")
      (is (= {"b.app" #{"a.core" "a.util"}} (:modules s2)))
      (is (= {"b.app" #{"a.core"}} (:modules s3)) "remove folds out"))
    (testing "replay-delta reconstructs the fold"
      (is (= (:modules s2) (:modules (store/replay-delta s1 d2))))
      (is (= (:modules s3) (:modules (store/replay-delta s2 d3)))))
    (testing "concurrent adds to the SAME module merge as a union — never a conflict"
      (let [[ours _]   (store/record-module-edge s1 "b.app" "a.util" :add)
            [theirs _] (store/record-module-edge s1 "b.app" "a.extra" :add)
            r          (store/merge-logs ours theirs :from "fork")]
        (is (empty? (:conflicts r)) (pr-str (:conflicts r)))
        (is (= #{"a.core" "a.util" "a.extra"}
               (get-in r [:store :modules "b.app"])))))
    (testing "a merge whose union creates a cycle gets a NOTE, not silence"
      (let [[ours _]   (store/record-module-edge base "x.a" "x.b" :add)
            [theirs _] (store/record-module-edge base "x.b" "x.a" :add)
            r          (store/merge-logs ours theirs :from "fork")]
        (is (empty? (:conflicts r)))
        (is (some :modules-cycle (:notes r)) (pr-str (:notes r)))))))
(deftest test-namespaces-see-package-private-deep-vars
  ;; a -test ns folds into the package it tests — for visibility, not just
  ;; module edges — so package-private deep helpers stay unit-testable.
  ;; (found dogfooding the deep-module split: without this, moving a
  ;; test-referenced helper into a deep ns forces a spurious ^:export.)
  (let [viol (fn [rows] (seq (edit/module-violations {} rows)))
        row  (fn [from to] {:from-ns from :from-var 'f :to to})]
    (testing "a -test ns reaches its subject's package-private deep var"
      (is (nil? (viol [(row 'a.b-test 'a.b.impl)]))
          "a.b-test folds to a.b, which shares a.b.impl's parent prefix")
      (is (nil? (viol [(row 'a.b.c-test 'a.b.c.deep)]))
          "deeper test folds too"))
    (testing "a genuine foreign module still can't reach it"
      (is (some #(= :visibility (:rule %)) (viol [(row 'x.y 'a.b.impl)]))))))
(deftest module-rules-are-recursive-and-declared
  (let [viol (fn [manifest rows] (seq (edit/module-violations manifest rows)))
        row  (fn [from to] {:from-ns from :from-var 'f :to to})]
    (testing "nil manifest = a pre-adoption store — rules off until open! adopts"
      (is (nil? (viol nil [(row 'b.user 'a.pub)]))))
    (testing "an undeclared cross-module edge is a violation teaching the semantic verb"
      (let [[v] (viol {"b.user" #{}} [(row 'b.user 'a.pub)])]
        (is (= :undeclared-edge (:rule v)) (pr-str v))
        (is (re-find #"module_dep \{from \"b\.user\" to \"a\.pub\"\}" (:error v))
            (:error v))))
    (testing "a declared edge passes"
      (is (nil? (viol {"b.user" #{"a.pub"}} [(row 'b.user 'a.pub)]))))
    (testing "visibility is recursive: deep namespaces are parent-scoped"
      (is (some #(= :visibility (:rule %))
                (viol {"b.user" #{"a.pub"}} [(row 'b.user 'a.pub.deep)]))
          "a.pub.deep is not reachable from another module even WITH the edge")
      (is (nil? (viol {} [(row 'a.pub.other 'a.pub.deep)]))
          "a.pub.deep IS callable from its sibling under a.pub")
      (is (nil? (viol {} [(row 'a.pub.deep 'a.pub.deep.deeper)]))
          "a.pub.deep.deeper is callable from a.pub.deep")
      (is (some #(= :visibility (:rule %))
                (viol {} [(row 'a.pub.other 'a.pub.deep.deeper)]))
          "but NOT from a.pub.other — sibling sub-packages are isolated")
      (is (re-find #"\^:export"
                   (:error (first (viol {"b.user" #{"a.pub"}}
                                        [(row 'b.user 'a.pub.deep)]))))
          "the refusal teaches the hoist"))
    (testing "^:export true hoists to the WORLD surface (edge still required)"
      (is (nil? (viol {"b.user" #{"a.pub"}}
                      [(assoc (row 'b.user 'a.pub.deep) :to-export true)])))
      (is (some #(= :undeclared-edge (:rule %))
                (viol {} [(assoc (row 'b.user 'a.pub.deep) :to-export true)]))
          "export does not waive the edge declaration"))
    (testing "a string :export names the LEVEL: visible to that subtree only"
      (is (nil? (viol {} [(assoc (row 'a.pub.other 'a.pub.deep.deeper)
                                 :to-export "a.pub")]))
          "exported to a.pub — any a.pub.* caller reaches it")
      (is (nil? (viol {} [(assoc (row 'a.pub 'a.pub.deep.deeper)
                                 :to-export "a.pub")]))
          "the prefix namespace itself counts as inside")
      (is (some #(= :visibility (:rule %))
                (viol {"b.user" #{"a.pub"}}
                      [(assoc (row 'b.user 'a.pub.deep.deeper)
                              :to-export "a.pub")]))
          "NOT world-visible — a foreign module is refused even with an edge")
      (is (re-find #"a\.pub\.\*"
                   (:error (first (viol {"b.user" #{"a.pub"}}
                                        [(assoc (row 'b.user 'a.pub.deep.deeper)
                                                :to-export "a.pub")]))))
          "the refusal names the granted subtree"))
    (testing "same-ns rows are exempt"
      (is (nil? (viol {"b.user" #{}} [(row 'b.user 'b.user)]))))))
(deftest ^:isolated the-manifest-follows-ns-renames
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ma.core "(ns ma.core)\n(defn shared \"Public.\" [x] x)\n")
      (api/module-dep! sess "mb.app" "ma.core" :prompt "app uses core")
      (api/ingest! sess 'mb.app
                   (str "(ns mb.app (:require [ma.core :as core]))\n"
                        "(defn use-it \"Uses ma.\" [x] (core/shared x))\n"))
      (testing "renaming the CALLER module re-keys the manifest entry"
        (is (nil? (:error (api/ns-rename! sess 'mb.app 'mb.hub :prompt "rebrand"))))
        (is (= {"mb.hub" #{"ma.core"}}
               (edit/modules-manifest (:store @sess)))))
      (testing "renaming the TARGET module re-keys the dep values"
        (is (nil? (:error (api/ns-rename! sess 'ma.core 'mx.core :prompt "rebrand"))))
        (is (= {"mb.hub" #{"mx.core"}}
               (edit/modules-manifest (:store @sess))))
        (is (nil? (:error (api/edit-replace! sess 'mb.hub 'use-it
                                             "(defn use-it \"Uses mx.\" [x] (core/shared (inc x)))"
                                             :prompt "still declared under the new names")))))
      (finally (api/close! sess)))))
(deftest ^:isolated an-unadopted-populated-store-adopts-on-reopen
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-modules-adopt"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (api/open! {:dir dir})]
    ;; land cross-module reality with the gate bypassed (what a bulk import
    ;; does) — manifest stays {}, journal has no :module-edge deltas
    (try
      (swap! sess assoc :adopting? true)
      (api/ingest! sess 'ka.core "(ns ka.core)\n(defn f \"F.\" [x] x)\n")
      (api/ingest! sess 'kb.app
                   (str "(ns kb.app (:require [ka.core :as core]))\n"
                        "(defn g \"G.\" [x] (core/f x))\n"))
      (is (= {} (edit/modules-manifest (:store @sess))))
      (finally (api/close! sess)))
    ;; reopen: empty manifest + populated + no edge delta ever = adopt
    (let [sess2 (api/open! {:dir dir})]
      (try
        (is (= {"kb.app" #{"ka.core"}}
               (edit/modules-manifest (:store @sess2))))
        (is (nil? (:error (api/edit-replace! sess2 'kb.app 'g
                                             "(defn g \"G.\" [x] (core/f (inc x)))"
                                             :prompt "gated edits work under the adopted manifest"))))
        (finally (api/close! sess2))))))
(deftest ^:isolated cycle-refusal-is-local-to-the-new-edge
  (testing "module-path answers reachability deterministically"
    (let [m {"a.x" #{"b.y"} "b.y" #{"c.z"}}]
      (is (= ["a.x" "b.y" "c.z"] (store/module-path m "a.x" "c.z")))
      (is (nil? (store/module-path m "c.z" "a.x")))))
  (testing "an adopted cycle (test folding makes them real) blocks nothing unrelated"
    (let [sess (api/open!)]
      (try
        ;; simulate adoption having recorded a cycle: api<->db via test folding
        (swap! sess update :store
               #(-> % (store/record-module-edge "a.x" "b.y" :add) first
                    (store/record-module-edge "b.y" "a.x" :add) first))
        (let [r (api/module-dep! sess "c.z" "a.x" :prompt "unrelated — must land")]
          (is (nil? (:error r)) (pr-str r)))
        (let [r (api/module-dep! sess "a.x" "c.z" :prompt "would close a.x→c.z→a.x")]
          (is (re-find #"(?i)closes a dependency cycle" (str (:error r))) (pr-str r)))
        (let [r (api/module-dep! sess "c.z" "b.y" :prompt "b.y does not reach c.z — fine")]
          (is (nil? (:error r)) (pr-str r)))
        (finally (api/close! sess))))))
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
      (let [r     (api/module-surface sess "ma.core")
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
          (is (= ["ma.core"] (get-in (api/module-surface sess "mb.app") [:deps]))
              "mb.app declares ma.core")
          (is (= ["mb.app"] (:consumers r)))))
      (testing "an unknown module teaches the list"
        (is (re-find #"modules true" (str (:error (api/module-surface sess "zz.nope"))))))
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
(deftest module-layers-condense-cycles
  (testing "a DAG layers by deepest dependency"
    (is (= {:layers [["a.core"] ["a.util"] ["b.app"]] :cycles []}
           (store/module-layers {"b.app" #{"a.core" "a.util"}
                                 "a.util" #{"a.core"}}))))
  (testing "cycle members share a layer and are named, not poisonous"
    (let [r (store/module-layers {"x.a" #{"x.b"} "x.b" #{"x.a"}
                                  "y.c" #{"x.a"}})]
      (is (= [["x.a" "x.b"] ["y.c"]] (:layers r)) (pr-str r))
      (is (= [["x.a" "x.b"]] (:cycles r)))))
  (testing "dep-only modules (declaring nothing) sit at layer 0"
    (is (= [["z.leaf"] ["z.top"]]
           (:layers (store/module-layers {"z.top" #{"z.leaf"}}))))))
(deftest ^:isolated the-module-lifecycle
  (let [sess (api/open!)]
    (try
      (is (nil? (:error (api/ingest! sess 'ma.core
                                     "(ns ma.core)\n(defn shared \"Public.\" [x] x)\n"))))
      (is (nil? (:error (api/ingest! sess 'ma.core.impl
                                     (str "(ns ma.core.impl)\n"
                                          "(defn hidden \"Package.\" [x] x)\n"
                                          "(defn ^:export hoisted \"Public via export.\" [x] x)\n"
                                          "(defn ^{:export \"ma.core\"} scoped \"Module-wide only.\" [x] x)\n"))))
          "deep ns lands fine — same module")
      (testing "enforcement is on from birth: declare-then-use"
        (let [r (api/ingest! sess 'mb.app
                             (str "(ns mb.app (:require [ma.core :as core]\n"
                                  "                     [ma.core.impl :as impl]))\n"
                                  "(defn use-it \"Uses ma.\" [x] (core/shared x))\n"))]
          (is (re-find #"does not declare ma\.core" (str (:error r))) (pr-str r))
          (is (re-find #"module_dep \{from \"mb\.app\" to \"ma\.core\"\}" (str (:error r)))
              "the refusal teaches the semantic verb")))
      (testing "declaring the edge is a semantic call whose WHY lands in the journal"
        (let [r (api/module-dep! sess "mb.app" "ma.core"
                                 :prompt "the app renders core's data")]
          (is (nil? (:error r)) (pr-str r))
          (is (= {:from "mb.app" :to "ma.core" :action :add}
                 (select-keys r [:from :to :action])))
          (let [d (last (filter #(= :module-edge (:op %))
                                (store/deltas (:store @sess))))]
            (is (= "the app renders core's data" (:prompt d)))))
        (is (nil? (:error (api/ingest! sess 'mb.app
                                       (str "(ns mb.app (:require [ma.core :as core]\n"
                                            "                     [ma.core.impl :as impl]))\n"
                                            "(defn use-it \"Uses ma.\" [x] (core/shared x))\n"))))
            "the same ingest now lands"))
      (testing "re-declaring is idempotent, not journal noise"
        (is (:already-declared (api/module-dep! sess "mb.app" "ma.core"))))
      (testing "an edge that would close a CYCLE is refused with the cycle named"
        (let [r (api/module-dep! sess "ma.core" "mb.app" :prompt "nope")]
          (is (re-find #"(?i)cycle" (str (:error r))) (pr-str r))))
      (testing "retracting an edge is the same verb and re-arms the gate"
        (is (nil? (:error (api/module-dep! sess "mb.app" "ma.core" :remove true
                                           :prompt "trying decoupling"))))
        (let [r (api/edit-replace! sess 'mb.app 'use-it
                                   "(defn use-it \"Uses ma.\" [x] (core/shared (inc x)))"
                                   :prompt "should be blocked again")]
          (is (re-find #"does not declare" (str (:error r))) (pr-str r)))
        (is (nil? (:error (api/module-dep! sess "mb.app" "ma.core"
                                           :prompt "restored")))))
      (testing "deep vars are package-private; ^:export hoists into the surface"
        (let [r (api/edit-replace! sess 'mb.app 'use-it
                                   "(defn use-it \"Uses ma.\" [x] (impl/hidden x))"
                                   :prompt "blocked: package-private")]
          (is (re-find #"package-private" (str (:error r))) (pr-str r))
          (is (re-find #"\^:export" (str (:error r))) "the refusal teaches the hoist"))
        (is (nil? (:error (api/edit-replace! sess 'mb.app 'use-it
                                             "(defn use-it \"Uses ma.\" [x] (impl/hoisted x))"
                                             :prompt "fine: exported")))))
      (testing "a subtree :export reaches its prefix but not the world"
        (is (nil? (:error (api/edit-replace! sess 'ma.core 'shared
                                             "(defn shared \"Public.\" [x] (ma.core.impl/scoped x))"
                                             :prompt "fine: ma.core is inside ma.core.*"))))
        (let [r (api/edit-replace! sess 'mb.app 'use-it
                                   "(defn use-it \"Uses ma.\" [x] (impl/scoped x))"
                                   :prompt "blocked: exported to ma.core.* only")]
          (is (re-find #"exported only within ma\.core\.\*" (str (:error r)))
              (pr-str r))))
      (testing "ns_create of a violating namespace is gated too"
        (let [r (api/create-ns! sess 'mc.rogue
                                :source (str "(ns mc.rogue (:require [ma.core :as core]))\n"
                                             "(defn steal \"Rogue.\" [x] (core/shared x))\n"))]
          (is (re-find #"does not declare" (str (:error r))) (pr-str r))))
      (testing "a public defn without a docstring surfaces at the DONE-POINT (never blocks)"
        (let [r (api/edit-replace! sess 'mb.app 'use-it
                                   "(defn use-it [x] (impl/hoisted x))"
                                   :prompt "drop the doc")]
          (is (nil? (:error r)) (pr-str r))
          (is (not-any? :missing-doc (:warnings r)) "the write stays quiet"))
        (let [r (api/done! sess :label "docs review")]
          (is (some #{'mb.app/use-it} (get-in r [:findings :missing-doc]))
              (pr-str (:findings r)))))
      (finally (api/close! sess)))))
(deftest fully-qualified-unrequired-calls-hit-the-gate
  ;; kondo emits NO var-usage row for a qualified call into a namespace the
  ;; caller never requires — `(deep.ns/var x)` compiles in the image (the ns
  ;; is loaded globally) and slipped the module gate entirely (found by a
  ;; live boundary probe). The gates must synthesize rows for these.
  (let [base (-> (store/empty-store)
                 (store/ingest 'a.b.impl "(ns a.b.impl)\n\n(defn hidden \"H.\" [x] x)\n"))]
    (testing "an un-required qualified call into a deep ns is refused"
      (let [cand (store/ingest base 'x.y
                               "(ns x.y)\n\n(defn f \"F.\" [v] (a.b.impl/hidden v))\n")]
        (is (re-find #"package-private"
                     (str (edit/module-refusal cand 'x.y 'f))))
        (is (re-find #"package-private" (str (edit/module-scan cand 'x.y))))))
    (testing "quoted symbols are data, not calls"
      (let [cand (store/ingest base 'x.z
                               "(ns x.z)\n\n(defn g \"G.\" [] 'a.b.impl/hidden)\n")]
        (is (nil? (edit/module-refusal cand 'x.z 'g)))))))
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
