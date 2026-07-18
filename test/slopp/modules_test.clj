(ns slopp.modules-test
  "The module system: recursive namespace visibility (depth ≤2 public,
  deeper scoped to the parent subtree), declared cross-module dependency
  edges (default-deny once a `modules` manifest exists), acyclic graph,
  and docstring warnings on the public surface."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.store :as store] [slopp.edit.modules :as modules] [slopp.store.merge :as merge]))
(deftest module-of-is-the-first-two-segments
  (is (= "logi.quoting" (modules/module-of 'logi.quoting)))
  (is (= "logi.quoting" (modules/module-of 'logi.quoting.internal)))
  (is (= "scratch" (modules/module-of 'scratch))))
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
            r          (merge/merge-logs ours theirs :from "fork")]
        (is (empty? (:conflicts r)) (pr-str (:conflicts r)))
        (is (= #{"a.core" "a.util" "a.extra"}
               (get-in r [:store :modules "b.app"])))))
    (testing "a merge whose union creates a cycle gets a NOTE, not silence"
      (let [[ours _]   (store/record-module-edge base "x.a" "x.b" :add)
            [theirs _] (store/record-module-edge base "x.b" "x.a" :add)
            r          (merge/merge-logs ours theirs :from "fork")]
        (is (empty? (:conflicts r)))
        (is (some :modules-cycle (:notes r)) (pr-str (:notes r)))))))
(deftest test-namespaces-see-package-private-deep-vars
  ;; a -test ns folds into the package it tests — for visibility, not just
  ;; module edges — so package-private deep helpers stay unit-testable.
  ;; (found dogfooding the deep-module split: without this, moving a
  ;; test-referenced helper into a deep ns forces a spurious ^:export.)
  (let [viol (fn [rows] (seq (modules/module-violations {} rows)))
        row  (fn [from to] {:from-ns from :from-var 'f :to to})]
    (testing "a -test ns reaches its subject's package-private deep var"
      (is (nil? (viol [(row 'a.b-test 'a.b.impl)]))
          "a.b-test folds to a.b, which shares a.b.impl's parent prefix")
      (is (nil? (viol [(row 'a.b.c-test 'a.b.c.deep)]))
          "deeper test folds too"))
    (testing "a genuine foreign module still can't reach it"
      (is (some #(= :visibility (:rule %)) (viol [(row 'x.y 'a.b.impl)]))))))
(deftest module-rules-are-recursive-and-declared
  (let [viol (fn [manifest rows] (seq (modules/module-violations manifest rows)))
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
               (modules/modules-manifest (:store @sess)))))
      (testing "renaming the TARGET module re-keys the dep values"
        (is (nil? (:error (api/ns-rename! sess 'ma.core 'mx.core :prompt "rebrand"))))
        (is (= {"mb.hub" #{"mx.core"}}
               (modules/modules-manifest (:store @sess))))
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
      (is (= {} (modules/modules-manifest (:store @sess))))
      (finally (api/close! sess)))
    ;; reopen: empty manifest + populated + no edge delta ever = adopt
    (let [sess2 (api/open! {:dir dir})]
      (try
        (is (= {"kb.app" #{"ka.core"}}
               (modules/modules-manifest (:store @sess2))))
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
                     (str (modules/module-refusal cand 'x.y 'f))))
        (is (re-find #"package-private" (str (modules/module-scan cand 'x.y))))))
    (testing "quoted symbols are data, not calls"
      (let [cand (store/ingest base 'x.z
                               "(ns x.z)\n\n(defn g \"G.\" [] 'a.b.impl/hidden)\n")]
        (is (nil? (modules/module-refusal cand 'x.z 'g)))))))

(deftest module-tiers-merge-clean
  (testing "tiers declared on either side land on the merged store"
    (let [base       (store/empty-store)
          [ours _]   (store/record-module-tier base "a.util" :effects)
          [theirs _] (store/record-module-tier base "a.core" :pure)
          {:keys [store]} (merge/merge-logs ours theirs :from "fork")]
      (is (= :pure (get-in store [:module-tiers "a.core"])))
      (is (= :effects (get-in store [:module-tiers "a.util"]))))))

(deftest purity-tier-gate
  (let [pure-src "(ns app.core)\n\n(defn add \"A.\" [x y] (+ x y))\n"
        eff-src  "(ns app.core)\n\n(defn tick! \"T.\" [a] (swap! a inc))\n"]
    (testing "an undeclared module (:effects default) gates nothing"
      (let [cand (store/ingest (store/empty-store) 'app.core eff-src)]
        (is (nil? (modules/tier-refusal cand 'app.core 'tick!)))))
    (testing ":pure refuses a form that reaches a mutation, with teaching"
      (let [[t _] (store/record-module-tier
                   (store/ingest (store/empty-store) 'app.core eff-src)
                   "app.core" :pure)]
        (is (re-find #":pure" (str (modules/tier-refusal t 'app.core 'tick!))))
        (is (re-find #"functional-core"
                     (str (modules/tier-refusal t 'app.core 'tick!))))))
    (testing ":pure allows a pure form"
      (let [[t _] (store/record-module-tier
                   (store/ingest (store/empty-store) 'app.core pure-src)
                   "app.core" :pure)]
        (is (nil? (modules/tier-refusal t 'app.core 'add)))))
    (testing ":effects is unrestricted"
      (let [[t _] (store/record-module-tier
                   (store/ingest (store/empty-store) 'app.core eff-src)
                   "app.core" :effects)]
        (is (nil? (modules/tier-refusal t 'app.core 'tick!)))))
    (testing ":reads refuses a mutation-reaching form"
      (let [[t _] (store/record-module-tier
                   (store/ingest (store/empty-store) 'app.core eff-src)
                   "app.core" :reads)]
        (is (re-find #":reads" (str (modules/tier-refusal t 'app.core 'tick!))))))))

(deftest ^:isolated module-purity-verb
  (let [sess (api/open!)]
    (try
      (testing "declares a tier, folded onto the store"
        (let [r (api/module-tier! sess "app.core" :pure :prompt "keep core pure")]
          (is (= :pure (:tier r)))
          (is (= "app.core" (:module r)))
          (is (= :pure (get-in @sess [:store :module-tiers "app.core"])))))
      (testing "rejects a bogus tier"
        (is (:error (api/module-tier! sess "app.core" :bogus))))
      (testing "rejects a non-module string"
        (is (:error (api/module-tier! sess "app.core.impl" :pure))))
      (finally (api/close! sess)))))

(deftest ^:isolated purity-gate-refuses-effectful-writes
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'pcore "(ns pcore)\n\n(defn add \"A.\" [x y] (+ x y))\n")
      (api/module-tier! sess "pcore" :pure :prompt "core stays pure")
      (testing "an effectful ADD into a :pure module is hard-refused with teaching"
        (let [r (api/add-form! sess 'pcore "(defn tick! \"T.\" [a] (swap! a inc))"
                               :prompt "sneak in a mutation")]
          (is (re-find #"functional-core" (str (:error r))))
          (is (nil? (store/form-named (:store @sess) 'pcore 'tick!))
              "the refused form never landed")))
      (testing "REPLACING a pure form with an effectful body is refused"
        (let [r (api/edit-replace! sess 'pcore 'add
                                   "(defn add \"A.\" [x y] (swap! x + y))"
                                   :prompt "turn add effectful")]
          (is (re-find #"functional-core" (str (:error r))))))
      (testing "a pure edit into the same module lands"
        (let [r (api/add-form! sess 'pcore "(defn sub \"S.\" [x y] (- x y))"
                               :prompt "pure helper")]
          (is (nil? (:error r)))))
      (finally (api/close! sess)))))

(deftest gate-refusal-composes-module-and-tier-gates
  (testing "it catches a purity-tier violation (tier gate is registered)"
    (let [[t _] (store/record-module-tier
                 (store/ingest (store/empty-store) 'app.core
                               "(ns app.core)\n\n(defn tick! \"T.\" [a] (swap! a inc))\n")
                 "app.core" :pure)]
      (is (re-find #"functional-core" (str (modules/gate-refusal t 'app.core 'tick!))))))
  (testing "it catches a module-visibility violation (module gate is registered)"
    (let [base (store/ingest (store/empty-store) 'a.b.impl
                             "(ns a.b.impl)\n\n(defn hidden \"H.\" [x] x)\n")
          cand (store/ingest base 'x.y
                             "(ns x.y)\n\n(defn f \"F.\" [v] (a.b.impl/hidden v))\n")]
      (is (re-find #"package-private" (str (modules/gate-refusal cand 'x.y 'f))))))
  (testing "clean form → nil"
    (let [cand (store/ingest (store/empty-store) 'app.core
                             "(ns app.core)\n\n(defn add \"A.\" [x y] (+ x y))\n")]
      (is (nil? (modules/gate-refusal cand 'app.core 'add))))))

(deftest schema-required-gate
  (let [ext-noschema  "(ns app.core)\n\n(defn handle \"H.\" [{:keys [x]}] x)\n"
        ext-schema    "(ns app.core)\n\n(defn ^{:malli/schema [:=> [:cat [:map [:x :int]]] :int]} handle \"H.\" [{:keys [x]}] x)\n"
        no-map-arg    "(ns app.core)\n\n(defn handle \"H.\" [x] x)\n"
        private-fn    "(ns app.core)\n\n(defn- handle \"H.\" [{:keys [x]}] x)\n"
        deep-noexport "(ns app.core.impl)\n\n(defn handle \"H.\" [{:keys [x]}] x)\n"
        on (fn [src ns]
             (first (store/record-config-put
                     (store/ingest (store/empty-store) ns src)
                     "gates" :manifest "require-boundary-schemas" "true")))]
    (testing "OFF by default (opt-in, permissive default) → never fires"
      (let [s (store/ingest (store/empty-store) 'app.core ext-noschema)]
        (is (nil? (modules/schema-refusal s 'app.core 'handle)))))
    (testing "ON: a module-external map-arg fn lacking a :=> schema is refused, with teaching"
      (let [s (on ext-noschema 'app.core)]
        (is (re-find #":malli/schema" (str (modules/schema-refusal s 'app.core 'handle))))))
    (testing "ON: the same fn WITH a :=> schema passes"
      (let [s (on ext-schema 'app.core)]
        (is (nil? (modules/schema-refusal s 'app.core 'handle)))))
    (testing "ON: a non-map first arg is not a boundary-contract case"
      (let [s (on no-map-arg 'app.core)]
        (is (nil? (modules/schema-refusal s 'app.core 'handle)))))
    (testing "ON: a private fn is not module-external"
      (let [s (on private-fn 'app.core)]
        (is (nil? (modules/schema-refusal s 'app.core 'handle)))))
    (testing "ON: a deep, non-exported fn is package-private, not a module boundary"
      (let [s (on deep-noexport 'app.core.impl)]
        (is (nil? (modules/schema-refusal s 'app.core.impl 'handle)))))))

(deftest ^:isolated schema-require-gate-refuses-boundary-writes
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'sg.core "(ns sg.core)\n\n(defn seed \"S.\" [x] x)\n")
      (testing "OFF by default: a module-external map-arg fn with no schema lands"
        (let [r (api/add-form! sess 'sg.core "(defn handle \"H.\" [{:keys [x]}] x)"
                               :prompt "no gate yet")]
          (is (nil? (:error r)) (pr-str r))))
      (api/config-file! sess "gates" :key "require-boundary-schemas" :value "true"
                        :prompt "require boundary schemas")
      (testing "enabling does NOT retro-break the already-landed boundary fn"
        (is (some? (store/form-named (:store @sess) 'sg.core 'handle))))
      (testing "ON: a NEW module-external map-arg fn lacking a :=> schema is hard-refused"
        (let [r (api/add-form! sess 'sg.core "(defn accept \"A.\" [{:keys [y]}] y)"
                               :prompt "boundary fn, no schema")]
          (is (re-find #":malli/schema" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'sg.core 'accept))
              "the refused form never landed")))
      (testing "ON: the same boundary fn WITH a :=> schema lands"
        (let [r (api/add-form! sess 'sg.core
                               "(defn ^{:malli/schema [:=> [:cat [:map [:y :int]]] :int]} accept \"A.\" [{:keys [y]}] y)"
                               :prompt "boundary fn, with schema")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (api/close! sess)))))

(deftest rule-severity-reads-per-store-config
  (let [s0 (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [x] x)\n")]
    (testing "no override → the passed default"
      (is (= :refuse (modules/rule-severity s0 'module-refusal :refuse)))
      (is (= :advisory (modules/rule-severity s0 :key-typos :advisory))))
    (testing "the rules config file overrides per rule; the key coerces symbol/keyword/string"
      (let [s (first (store/record-config-put s0 "rules" :manifest "schema-refusal" "off"))]
        (is (= :off (modules/rule-severity s 'schema-refusal :refuse)))
        (is (= :off (modules/rule-severity s :schema-refusal :refuse)))
        (is (= :off (modules/rule-severity s "schema-refusal" :refuse)))
        (testing "an un-overridden rule keeps its default"
          (is (= :refuse (modules/rule-severity s 'module-refusal :refuse))))))))

(deftest gate-refusal-honors-off-severity
  (let [[t _] (store/record-module-tier
               (store/ingest (store/empty-store) 'app.core
                             "(ns app.core)\n\n(defn tick! \"T.\" [a] (swap! a inc))\n")
               "app.core" :pure)]
    (testing "the tier gate fires by default"
      (is (re-find #"functional-core" (str (modules/gate-refusal t 'app.core 'tick!)))))
    (testing "dialing tier-refusal :off in the rules config skips it (per-store severity)"
      (let [off (first (store/record-config-put t "rules" :manifest "tier-refusal" "off"))]
        (is (nil? (modules/gate-refusal off 'app.core 'tick!)))))
    (testing "an unrelated rule dialed :off leaves the tier gate firing"
      (let [other (first (store/record-config-put t "rules" :manifest "schema-refusal" "off"))]
        (is (re-find #"functional-core" (str (modules/gate-refusal other 'app.core 'tick!))))))))

(deftest namespaced-keys-gate
  (let [bare      "(ns app.core)\n\n(defn handle \"H.\" [{:keys [id]}] id)\n"
        qualified "(ns app.core)\n\n(defn handle \"H.\" [{:user/keys [id]}] id)\n"
        no-map    "(ns app.core)\n\n(defn handle \"H.\" [id] id)\n"
        private   "(ns app.core)\n\n(defn- handle \"H.\" [{:keys [id]}] id)\n"
        on (fn [src ns]
             (first (store/record-config-put
                     (store/ingest (store/empty-store) ns src)
                     "gates" :manifest "require-namespaced-keys" "true")))]
    (testing "OFF by default (opt-in) → never fires"
      (let [s (store/ingest (store/empty-store) 'app.core bare)]
        (is (nil? (modules/namespaced-keys-refusal s 'app.core 'handle)))))
    (testing "ON: a module-external fn destructuring unqualified :keys is refused"
      (let [s (on bare 'app.core)]
        (is (re-find #"namespaced" (str (modules/namespaced-keys-refusal s 'app.core 'handle))))))
    (testing "ON: namespaced :ns/keys passes"
      (let [s (on qualified 'app.core)]
        (is (nil? (modules/namespaced-keys-refusal s 'app.core 'handle)))))
    (testing "ON: a non-map first arg is not a boundary-keys case"
      (let [s (on no-map 'app.core)]
        (is (nil? (modules/namespaced-keys-refusal s 'app.core 'handle)))))
    (testing "ON: a private fn is not a module boundary"
      (let [s (on private 'app.core)]
        (is (nil? (modules/namespaced-keys-refusal s 'app.core 'handle)))))))

(deftest ^:isolated namespaced-keys-gate-refuses-boundary-writes
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'nk.core "(ns nk.core)\n\n(defn seed \"S.\" [x] x)\n")
      (api/config-file! sess "gates" :key "require-namespaced-keys" :value "true"
                        :prompt "require namespaced boundary keys")
      (testing "a boundary fn destructuring unqualified :keys is hard-refused"
        (let [r (api/add-form! sess 'nk.core "(defn accept \"A.\" [{:keys [id]}] id)"
                               :prompt "bare keys at the boundary")]
          (is (re-find #"namespaced" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'nk.core 'accept)))))
      (testing "the namespaced form lands"
        (let [r (api/add-form! sess 'nk.core "(defn accept \"A.\" [{:acct/keys [id]}] id)"
                               :prompt "namespaced keys")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (api/close! sess)))))

(deftest pure-tier-forbids-nondeterminism
  (let [rand-src "(ns app.core)\n\n(defn roll \"R.\" [] (rand-int 6))\n"
        pure-src "(ns app.core)\n\n(defn add \"A.\" [x y] (+ x y))\n"]
    (testing ":pure refuses a form reaching non-determinism (rand), with teaching"
      (let [[t _] (store/record-module-tier
                   (store/ingest (store/empty-store) 'app.core rand-src)
                   "app.core" :pure)]
        (is (re-find #"(?i)determinis" (str (modules/tier-refusal t 'app.core 'roll))))))
    (testing ":pure still allows a referentially-transparent form"
      (let [[t _] (store/record-module-tier
                   (store/ingest (store/empty-store) 'app.core pure-src)
                   "app.core" :pure)]
        (is (nil? (modules/tier-refusal t 'app.core 'add)))))
    (testing ":reads tolerates non-determinism (rand is not a mutation)"
      (let [[t _] (store/record-module-tier
                   (store/ingest (store/empty-store) 'app.core rand-src)
                   "app.core" :reads)]
        (is (nil? (modules/tier-refusal t 'app.core 'roll)))))))

(deftest rule-severity-coerces-and-validates
  (let [s0   (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [x] x)\n")
        with (fn [v] (first (store/record-config-put s0 "rules" :manifest "schema-refusal" v)))]
    (testing "a leading colon is tolerated — ':off' and 'off' both disable"
      (is (= :off (modules/rule-severity (with ":off") 'schema-refusal :refuse)))
      (is (= :off (modules/rule-severity (with "off") 'schema-refusal :refuse))))
    (testing "an unknown/empty severity falls back to the default, not a junk keyword"
      (is (= :refuse (modules/rule-severity (with "garbage") 'schema-refusal :refuse)))
      (is (= :refuse (modules/rule-severity (with "") 'schema-refusal :refuse))))))

(deftest gates-inspect-all-arities
  (let [multi "(ns app.core)\n\n(defn handle \"H.\" ([x] x) ([{:keys [id]} y] id))\n"
        on (fn [k]
             (first (store/record-config-put
                     (store/ingest (store/empty-store) 'app.core multi)
                     "gates" :manifest k "true")))]
    (testing "namespaced-keys gate catches bare :keys in a LATER arity"
      (is (re-find #"namespaced"
                   (str (modules/namespaced-keys-refusal (on "require-namespaced-keys")
                                                         'app.core 'handle)))))
    (testing "schema gate catches a map first-arg in a LATER arity"
      (is (re-find #":malli/schema"
                   (str (modules/schema-refusal (on "require-boundary-schemas")
                                                'app.core 'handle)))))))

(deftest write-gate-advisory-severity
  (let [[t _] (store/record-module-tier
               (store/ingest (store/empty-store) 'app.core
                             "(ns app.core)\n\n(defn tick! \"T.\" [a] (swap! a inc))\n")
               "app.core" :pure)
        adv (first (store/record-config-put t "rules" :manifest "tier-refusal" "advisory"))]
    (testing "an :advisory-dialed write gate does NOT block"
      (is (nil? (modules/gate-refusal adv 'app.core 'tick!)))
      (is (nil? (:refuse (modules/gate-check adv 'app.core 'tick!)))))
    (testing "but its teaching surfaces via gate-check :advisories (warn-but-proceed)"
      (is (re-find #"functional-core"
                   (str (first (:advisories (modules/gate-check adv 'app.core 'tick!)))))))
    (testing "a refuse-grade gate blocks and is not an advisory"
      (let [gc (modules/gate-check t 'app.core 'tick!)]
        (is (re-find #"functional-core" (str (:refuse gc))))
        (is (empty? (:advisories gc)))))))

(deftest ^:isolated advisory-write-gate-warns-but-proceeds
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'aw.core "(ns aw.core)\n\n(defn seed \"S.\" [x] x)\n")
      (api/config-file! sess "gates" :key "require-namespaced-keys" :value "true"
                        :prompt "require namespaced boundary keys")
      (api/config-file! sess "rules" :key "namespaced-keys-refusal" :value "advisory"
                        :prompt "but only advise, don't block")
      (let [r (api/add-form! sess 'aw.core "(defn accept \"A.\" [{:keys [id]}] id)"
                             :prompt "bare keys — should warn, not block")]
        (testing "the write LANDS (advisory, not blocked)"
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'aw.core 'accept))))
        (testing "and the gate's teaching rides the result's :advisories"
          (is (re-find #"namespaced" (str (first (:advisories r)))) (pr-str r))))
      (finally (api/close! sess)))))

(deftest ^:isolated purity-gate-exempts-test-namespaces
  ;; A test namespace belongs to its module (x.y-test → x.y), so declaring a
  ;; module :pure was silently making its TESTS unwritable — they set up
  ;; sessions and exercise effects by design, which is the whole job. The tier
  ;; is a claim about the functional CORE, not about the code that drives it.
  ;; Found by cleanup {all true} on slopp's own store, where declaring
  ;; slopp.normalize :pure had already stranded slopp.normalize-test.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'pt.core "(ns pt.core)\n(defn add [x y] (+ x y))\n")
      (api/module-tier! sess "pt.core" :pure :prompt "a pure core")
      (testing "an effectful write to the production namespace is still refused"
        (let [r (api/add-form! sess 'pt.core "(defn slurp! [f] (slurp f))"
                               :prompt "effect into a pure core")]
          (is (re-find #"declared :pure" (str (:error r))) (pr-str r))))
      (testing "the module's TEST namespace may reach effects"
        (api/ingest! sess 'pt.core-test "(ns pt.core-test)\n")
        (let [r (api/add-form! sess 'pt.core-test
                               "(defn setup! [f] (slurp f))"
                               :prompt "a test fixture doing IO")]
          (is (nil? (:error r))
              (str "tests exercise effects by design: " (pr-str r)))))
      (finally (api/close! sess)))))

(deftest ^:isolated foreign-keys-marks-a-third-party-map-and-polices-itself
  ;; require-namespaced-keys cannot be satisfied by a fn that destructures
  ;; SOMEONE ELSE'S map — slopp.build/arg-style takes clj-kondo's analysis, and
  ;; we do not get to rename kondo's keys. ^:foreign-keys records that, and
  ;; polices itself like ^:ambient-ok / ^:unused-ok: a marker on a fn that has
  ;; no bare boundary keys is itself refused, so it cannot decay into a blanket
  ;; opt-out someone sprinkles to silence the gate.
  (let [sess (api/open!)]
    (try
      (api/config-file! sess "gates" :key "require-namespaced-keys" :value "true")
      (api/ingest! sess 'fk.core "(ns fk.core)\n")
      (testing "an unmarked bare-keys boundary fn is refused"
        (let [r (api/add-form! sess 'fk.core
                               "(defn takes-bare [{:keys [id]}] id)"
                               :prompt "bare keys at a boundary")]
          (is (re-find #"namespaced" (str (:error r))) (pr-str r))))
      (testing "^:foreign-keys discharges it"
        (let [r (api/add-form! sess 'fk.core
                               "(defn ^:foreign-keys takes-foreign [{:keys [id]}] id)"
                               :prompt "third-party map")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "a marker with nothing to excuse is refused — no blanket opt-out"
        (let [r (api/add-form! sess 'fk.core
                               "(defn ^:foreign-keys no-map [x] x)"
                               :prompt "stale marker")]
          (is (re-find #"remove the flag" (str (:error r))) (pr-str r))))
      (finally (api/close! sess)))))

(deftest ^:isolated rule-test-applicability-is-declared-not-rediscovered
  ;; Whether a rule applies to TEST namespaces bit twice: a :pure tier silently
  ;; stranded its own test namespace, and effect-naming flagged three test
  ;; helpers. Each was fixed ad hoc. Worse, two surfaces answered the question
  ;; DIFFERENTLY — purity-standing excluded tests when recommending a tier
  ;; while tier-refusal gated them, so the report recommended a tier the gate
  ;; would then punish, and nothing could see the contradiction.
  ;;
  ;; It is now declared on the gate itself via ^{:rule/applies-to :production},
  ;; so there is ONE answer and both surfaces read it.
  (testing "every write gate declares its applicability"
    (doseq [g modules/per-form-write-gates]
      (is (contains? #{:all :production} (:rule/applies-to (meta g) :all))
          (str (:name (meta g)) " must declare :rule/applies-to :all or"
               " :production — leaving it implicit is how two surfaces"
               " disagreed about tests"))))
  (testing "the purity gate is production-only, and says so in one place"
    (is (= :production (:rule/applies-to (meta #'modules/tier-refusal)))))
  (testing "and the report agrees with the gate by construction"
    (let [sess (api/open!)]
      (try
        (api/ingest! sess 'ra.core "(ns ra.core)\n(defn add [x y] (+ x y))\n")
        (api/ingest! sess 'ra.core-test "(ns ra.core-test)\n(defn setup! [f] (slurp f))\n")
        (is (= :pure (:supports (modules/tier-report (:store @sess) 'ra.core)))
            "the effectful TEST namespace must not veto the module's tier")
        (finally (api/close! sess))))))
