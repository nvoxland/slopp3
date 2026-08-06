(ns slopp.modules-test
  "The module system: recursive namespace visibility (depth ≤2 public,
  deeper scoped to the parent subtree), declared cross-module dependency
  edges (default-deny once a `modules` manifest exists), acyclic graph,
  and docstring warnings on the public surface."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops]
            [slopp.store :as store] [slopp.edit.modules :as modules] [slopp.store.merge :as merge] [slopp.ops.external :as external] [clojure.java.io] [clojure.edn] [slopp.store.render :as render] [slopp.read.graph :as graph] [slopp.edit.tiers :as tiers] [slopp.edit.gates :as gates]))

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
    (testing "a union that closes a cycle still merges — judging it is the CALLER's"
      ;; merge-logs can only see the DECLARED manifest, where a -test
      ;; namespace's fixture requires are edges. Warning from here reported
      ;; a cycle on every merge into slopp's own main that no production
      ;; code had, advising a retraction that would have broken the very
      ;; test that created the edge. api.modules/merge-production-cycle
      ;; judges the production graph instead — and it can only run after
      ;; the merge has produced a store, which is why it cannot live here.
      ;; These stores have no namespaces at all, which is the other half of
      ;; the same point: nothing at this layer knows what production means.
      (let [[ours _]   (store/record-module-edge base "x.a" "x.b" :add)
            [theirs _] (store/record-module-edge base "x.b" "x.a" :add)
            r          (merge/merge-logs ours theirs :from "fork")]
        (is (empty? (:conflicts r)))
        (is (= {"x.a" #{"x.b"} "x.b" #{"x.a"}} (:modules (:store r)))
            "the union lands — CRDT grain is not conditional on acyclicity")
        (is (not-any? :modules-cycle (:notes r)) (pr-str (:notes r)))))))

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
          "but NOT from a.pub.other — sibling sub-packages are external")
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

(deftest ^:external the-manifest-follows-ns-renames
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ma.core "(ns ma.core)\n(defn shared \"Public.\" [x] x)\n")
      (ops/module-dep! sess "mb.app" "ma.core" :prompt "app uses core")
      (ops/ingest! sess 'mb.app
                   (str "(ns mb.app (:require [ma.core :as core]))\n"
                        "(defn use-it \"Uses ma.\" [x] (core/shared x))\n"))
      (testing "renaming the CALLER module re-keys the manifest entry"
        (is (nil? (:error (ops/ns-rename! sess 'mb.app 'mb.hub :prompt "rebrand"))))
        (is (= {"mb.hub" #{"ma.core"}}
               (modules/modules-manifest (:store @sess)))))
      (testing "renaming the TARGET module re-keys the dep values"
        (is (nil? (:error (ops/ns-rename! sess 'ma.core 'mx.core :prompt "rebrand"))))
        (is (= {"mb.hub" #{"mx.core"}}
               (modules/modules-manifest (:store @sess))))
        (is (nil? (:error (ops/edit-replace! sess 'mb.hub 'use-it
                                             "(defn use-it \"Uses mx.\" [x] (core/shared (inc x)))"
                                             :prompt "still declared under the new names")))))
      (finally (ops/close! sess)))))

(deftest ^:external an-unadopted-populated-store-adopts-on-reopen
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-modules-adopt"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})]
    ;; land cross-module reality with the gate bypassed (what a bulk import
    ;; does) — manifest stays {}, journal has no :module-edge deltas
    (try
      (swap! sess assoc :adopting? true)
      (ops/ingest! sess 'ka.core "(ns ka.core)\n(defn f \"F.\" [x] x)\n")
      (ops/ingest! sess 'kb.app
                   (str "(ns kb.app (:require [ka.core :as core]))\n"
                        "(defn g \"G.\" [x] (core/f x))\n"))
      (is (= {} (modules/modules-manifest (:store @sess))))
      (finally (ops/close! sess)))
    ;; reopen: empty manifest + populated + no edge delta ever = adopt
    (let [sess2 (external/open! {:slopp.ops/dir dir})]
      (try
        (is (= {"kb.app" #{"ka.core"}}
               (modules/modules-manifest (:store @sess2))))
        (is (nil? (:error (ops/edit-replace! sess2 'kb.app 'g
                                             "(defn g \"G.\" [x] (core/f (inc x)))"
                                             :prompt "gated edits work under the adopted manifest"))))
        (finally (ops/close! sess2))))))

(deftest ^:external cycle-refusal-is-local-to-the-new-edge
  (testing "module-path answers reachability deterministically"
    (let [m {"a.x" #{"b.y"} "b.y" #{"c.z"}}]
      (is (= ["a.x" "b.y" "c.z"] (store/module-path m "a.x" "c.z")))
      (is (nil? (store/module-path m "c.z" "a.x")))))
  ;; The gate reads PRODUCTION edges (see
  ;; cycle-refusal-judges-production-edges-not-test-fixtures), so the
  ;; fixture is real code rather than hand-placed manifest entries — an
  ;; adopted cycle from test folding no longer reaches this question at all.
  (testing "an unrelated edge lands; closing a real chain refuses"
    (let [sess (external/open!)]
      (try
        (ops/ingest! sess 'a.x "(ns a.x)\n(defn f \"F.\" [n] n)\n")
        (ops/module-dep! sess "b.y" "a.x" :prompt "b calls a")
        (ops/ingest! sess 'b.y
                     "(ns b.y (:require [a.x :as x]))\n(defn g \"G.\" [n] (x/f n))\n")
        (ops/module-dep! sess "c.z" "b.y" :prompt "c calls b")
        (ops/ingest! sess 'c.z
                     "(ns c.z (:require [b.y :as y]))\n(defn h \"H.\" [n] (y/g n))\n")
        (let [r (ops/module-dep! sess "d.w" "c.z" :prompt "unrelated — must land")]
          (is (nil? (:error r)) (pr-str r)))
        (let [r (ops/module-dep! sess "a.x" "c.z" :prompt "would close a.x→c.z→b.y→a.x")]
          (is (re-find #"(?i)closes a dependency cycle" (str (:error r))) (pr-str r)))
        (let [r (ops/module-dep! sess "b.y" "d.w" :prompt "d.w reaches nothing — fine")]
          (is (nil? (:error r)) (pr-str r)))
        (finally (ops/close! sess))))))

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

(deftest ^:external the-module-lifecycle
  (let [sess (external/open!)]
    (try
      (is (nil? (:error (ops/ingest! sess 'ma.core
                                     "(ns ma.core)\n(defn shared \"Public.\" [x] x)\n"))))
      (is (nil? (:error (ops/ingest! sess 'ma.core.impl
                                     (str "(ns ma.core.impl)\n"
                                          "(defn hidden \"Package.\" [x] x)\n"
                                          "(defn ^:export hoisted \"Public via export.\" [x] x)\n"
                                          "(defn ^{:export \"ma.core\"} scoped \"Module-wide only.\" [x] x)\n"))))
          "deep ns lands fine — same module")
      (testing "enforcement is on from birth: declare-then-use"
        (let [r (ops/ingest! sess 'mb.app
                             (str "(ns mb.app (:require [ma.core :as core]\n"
                                  "                     [ma.core.impl :as impl]))\n"
                                  "(defn use-it \"Uses ma.\" [x] (core/shared x))\n"))]
          (is (re-find #"does not declare ma\.core" (str (:error r))) (pr-str r))
          (is (re-find #"module_dep \{from \"mb\.app\" to \"ma\.core\"\}" (str (:error r)))
              "the refusal teaches the semantic verb")))
      (testing "declaring the edge is a semantic call whose WHY lands in the journal"
        (let [r (ops/module-dep! sess "mb.app" "ma.core"
                                 :prompt "the app renders core's data")]
          (is (nil? (:error r)) (pr-str r))
          (is (= {:from "mb.app" :to "ma.core" :action :add}
                 (select-keys r [:from :to :action])))
          (let [d (last (filter #(= :module-edge (:op %))
                                (store/deltas (:store @sess))))]
            (is (= "the app renders core's data" (:prompt d)))))
        (is (nil? (:error (ops/ingest! sess 'mb.app
                                       (str "(ns mb.app (:require [ma.core :as core]\n"
                                            "                     [ma.core.impl :as impl]))\n"
                                            "(defn use-it \"Uses ma.\" [x] (core/shared x))\n"))))
            "the same ingest now lands"))
      (testing "re-declaring is idempotent, not journal noise"
        (is (:already-declared (ops/module-dep! sess "mb.app" "ma.core"))))
      (testing "an edge that would close a CYCLE is refused with the cycle named"
        (let [r (ops/module-dep! sess "ma.core" "mb.app" :prompt "nope")]
          (is (re-find #"(?i)cycle" (str (:error r))) (pr-str r))))
      (testing "retracting an edge is the same verb and re-arms the gate"
        (is (nil? (:error (ops/module-dep! sess "mb.app" "ma.core" :remove true
                                           :prompt "trying decoupling"))))
        (let [r (ops/edit-replace! sess 'mb.app 'use-it
                                   "(defn use-it \"Uses ma.\" [x] (core/shared (inc x)))"
                                   :prompt "should be blocked again")]
          (is (re-find #"does not declare" (str (:error r))) (pr-str r)))
        (is (nil? (:error (ops/module-dep! sess "mb.app" "ma.core"
                                           :prompt "restored")))))
      (testing "deep vars are package-private; ^:export hoists into the surface"
        (let [r (ops/edit-replace! sess 'mb.app 'use-it
                                   "(defn use-it \"Uses ma.\" [x] (impl/hidden x))"
                                   :prompt "blocked: package-private")]
          (is (re-find #"package-private" (str (:error r))) (pr-str r))
          (is (re-find #"\^:export" (str (:error r))) "the refusal teaches the hoist"))
        (is (nil? (:error (ops/edit-replace! sess 'mb.app 'use-it
                                             "(defn use-it \"Uses ma.\" [x] (impl/hoisted x))"
                                             :prompt "fine: exported")))))
      (testing "a subtree :export reaches its prefix but not the world"
        (is (nil? (:error (ops/edit-replace! sess 'ma.core 'shared
                                             "(defn shared \"Public.\" [x] (ma.core.impl/scoped x))"
                                             :prompt "fine: ma.core is inside ma.core.*"))))
        (let [r (ops/edit-replace! sess 'mb.app 'use-it
                                   "(defn use-it \"Uses ma.\" [x] (impl/scoped x))"
                                   :prompt "blocked: exported to ma.core.* only")]
          (is (re-find #"exported only within ma\.core\.\*" (str (:error r)))
              (pr-str r))))
      (testing "ns_create of a violating namespace is gated too"
        (let [r (ops/create-ns! sess 'mc.rogue
                                :source (str "(ns mc.rogue (:require [ma.core :as core]))\n"
                                             "(defn steal \"Rogue.\" [x] (core/shared x))\n"))]
          (is (re-find #"does not declare" (str (:error r))) (pr-str r))))
      (testing "a public defn without a docstring surfaces at the DONE-POINT (never blocks)"
        (let [r (ops/edit-replace! sess 'mb.app 'use-it
                                   "(defn use-it [x] (impl/hoisted x))"
                                   :prompt "drop the doc")]
          (is (nil? (:error r)) (pr-str r))
          (is (not-any? :missing-doc (:warnings r)) "the write stays quiet"))
        (let [r (external/done! sess :label "docs review")]
          (is (some #{'mb.app/use-it} (get-in r [:findings :missing-doc]))
              (pr-str (:findings r)))))
      (finally (ops/close! sess)))))

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
  (testing "tiers declared on either side land on the merged store, canonically"
    (let [base       (store/empty-store)
          [ours _]   (store/record-module-tier base "a.util" :effects)
          [theirs _] (store/record-module-tier base "a.core" :pure)
          {:keys [store]} (merge/merge-logs ours theirs :from "fork")]
      (is (= :pure (get-in store [:module-tiers "a.core"])))
      ;; :effects is a retired spelling — fold state is canonical everywhere
      (is (= :external (get-in store [:module-tiers "a.util"]))))))

(deftest purity-tier-gate
  (let [pure-src "(ns app.core)\n\n(defn add \"A.\" [x y] (+ x y))\n"
        eff-src  "(ns app.core)\n\n(defn tick! \"T.\" [a] (swap! a inc))\n"
        io-src   "(ns app.core)\n\n(defn grab! \"G.\" [p] (spit p \"x\"))\n"
        at       (fn [src tier]
                   (first (store/record-module-tier
                           (store/ingest (store/empty-store) 'app.core src)
                           "app.core" tier)))]
    (testing "an undeclared namespace (:external default) gates nothing"
      (let [cand (store/ingest (store/empty-store) 'app.core eff-src)]
        (is (nil? (tiers/tier-refusal cand 'app.core 'tick!)))))
    (testing ":pure refuses a form that reaches a mutation, with teaching"
      (let [t (at eff-src :pure)]
        (is (re-find #":pure" (str (tiers/tier-refusal t 'app.core 'tick!))))
        (is (re-find #"functional-core"
                     (str (tiers/tier-refusal t 'app.core 'tick!))))))
    (testing ":pure allows a pure form"
      (is (nil? (tiers/tier-refusal (at pure-src :pure) 'app.core 'add))))
    (testing ":external is unrestricted"
      (is (nil? (tiers/tier-refusal (at eff-src :external) 'app.core 'tick!))))
    (testing ":internal ALLOWS in-process mutation — a memo is not an effect
              on the world, and treating it as one is what put a memoized
              projection in the same class as a subprocess spawn"
      (is (nil? (tiers/tier-refusal (at eff-src :internal) 'app.core 'tick!))))
    (testing ":internal REFUSES what leaves the process"
      (let [msg (str (tiers/tier-refusal (at io-src :internal) 'app.core 'grab!))]
        (is (re-find #":internal" msg) msg)
        (is (re-find #"(?i)outside this process" msg) msg)))
    (testing "legacy spellings still resolve: :reads => :internal, :effects => :external"
      (is (nil? (tiers/tier-refusal (at eff-src :reads) 'app.core 'tick!)))
      (is (nil? (tiers/tier-refusal (at eff-src :effects) 'app.core 'tick!))))))

(deftest ^:external module-purity-verb
  (let [sess (external/open!)]
    (try
      (testing "declares a tier, folded onto the store"
        (let [r (ops/module-tier! sess "app.core" :pure :prompt "keep core pure")]
          (is (= :pure (:tier r)))
          (is (= "app.core" (:module r)))
          (is (= :pure (get-in @sess [:store :module-tiers "app.core"])))))
      (testing "rejects a bogus tier"
        (is (:error (ops/module-tier! sess "app.core" :bogus))))
      (testing "rejects a non-module string"
        ;; a DEEP namespace is now legal — a pure core routinely lives below an
      ;; effectful module, and the tier exists to make agents move code into
      ;; that shape, which it cannot do if it cannot name it
      (is (nil? (:error (ops/module-tier! sess "app.core.impl" :pure))))
      (is (:error (ops/module-tier! sess "has spaces" :pure))))
      (finally (ops/close! sess)))))

(deftest ^:external purity-gate-refuses-effectful-writes
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'pcore "(ns pcore)\n\n(defn add \"A.\" [x y] (+ x y))\n")
      (ops/module-tier! sess "pcore" :pure :prompt "core stays pure")
      (testing "an effectful ADD into a :pure module is hard-refused with teaching"
        (let [r (ops/add-form! sess 'pcore "(defn tick! \"T.\" [a] (swap! a inc))"
                               :prompt "sneak in a mutation")]
          (is (re-find #"functional-core" (str (:error r))))
          (is (nil? (store/form-named (:store @sess) 'pcore 'tick!))
              "the refused form never landed")))
      (testing "REPLACING a pure form with an effectful body is refused"
        (let [r (ops/edit-replace! sess 'pcore 'add
                                   "(defn add \"A.\" [x y] (swap! x + y))"
                                   :prompt "turn add effectful")]
          (is (re-find #"functional-core" (str (:error r))))))
      (testing "a pure edit into the same module lands"
        (let [r (ops/add-form! sess 'pcore "(defn sub \"S.\" [x y] (- x y))"
                               :prompt "pure helper")]
          (is (nil? (:error r)))))
      (finally (ops/close! sess)))))

(deftest gate-refusal-composes-module-and-tier-gates
  (testing "it catches a purity-tier violation (tier gate is registered)"
    (let [[t _] (store/record-module-tier
                 (store/ingest (store/empty-store) 'app.core
                               "(ns app.core)\n\n(defn tick! \"T.\" [a] (swap! a inc))\n")
                 "app.core" :pure)]
      (is (re-find #"functional-core" (str (gates/gate-refusal t 'app.core 'tick!))))))
  (testing "it catches a module-visibility violation (module gate is registered)"
    (let [base (store/ingest (store/empty-store) 'a.b.impl
                             "(ns a.b.impl)\n\n(defn hidden \"H.\" [x] x)\n")
          cand (store/ingest base 'x.y
                             "(ns x.y)\n\n(defn f \"F.\" [v] (a.b.impl/hidden v))\n")]
      (is (re-find #"package-private" (str (gates/gate-refusal cand 'x.y 'f))))))
  (testing "clean form → nil"
    (let [cand (store/ingest (store/empty-store) 'app.core
                             "(ns app.core)\n\n(defn add \"A.\" [x y] (+ x y))\n")]
      (is (nil? (gates/gate-refusal cand 'app.core 'add))))))

(deftest schema-required-gate
  (let [ext-noschema  "(ns app.core)\n\n(defn handle \"H.\" [{:keys [x]}] x)\n"
        ext-schema    "(ns app.core)\n\n(defn ^{:malli/schema [:=> [:cat [:map [:x :int]]] :int]} handle \"H.\" [{:keys [x]}] x)\n"
        ext-throws-none "(ns app.core)\n\n(defn ^{:malli/schema [:=> {:throws []} [:cat [:map [:x :int]]] :int]} handle \"H.\" [{:keys [x]}] x)\n"
        ext-throws-some "(ns app.core)\n\n(defn ^{:malli/schema [:=> {:throws [[:map [:app/error :keyword]]]} [:cat [:map [:x :int]]] :int]} handle \"H.\" [{:keys [x]}] (throw (ex-info \"no\" {:app/error :nope})))\n"
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
    (testing "ON: a :=> schema that says nothing about THROWS is still refused —
              a caller cannot tell a function that signals failure by returning
              from one that throws something nobody named, and those need
              different code at every call site"
      (let [s (on ext-schema 'app.core)]
        (is (re-find #":throws" (str (modules/schema-refusal s 'app.core 'handle))))))
    (testing "ON: an EMPTY :throws passes — declaring that it signals nothing is
              itself a declaration, and requiring it even when empty is the
              whole point: undeclared and declared-nothing must not look alike"
      (let [s (on ext-throws-none 'app.core)]
        (is (nil? (modules/schema-refusal s 'app.core 'handle)))))
    (testing "ON: a declared ex-data shape passes too. This is the CHECKED half
              — the ex-info a caller is expected to handle. An NPE from three
              calls down is unchecked, and nobody's declaration"
      (let [s (on ext-throws-some 'app.core)]
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

(deftest ^:external schema-require-gate-refuses-boundary-writes
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'sg.core "(ns sg.core)\n\n(defn seed \"S.\" [x] x)\n")
      (testing "OFF by default: a module-external map-arg fn with no schema lands"
        (let [r (ops/add-form! sess 'sg.core "(defn handle \"H.\" [{:keys [x]}] x)"
                               :prompt "no gate yet")]
          (is (nil? (:error r)) (pr-str r))))
      (ops/config-file! sess "gates" :key "require-boundary-schemas" :value "true"
                        :prompt "require boundary schemas")
      (testing "enabling does NOT retro-break the already-landed boundary fn"
        (is (some? (store/form-named (:store @sess) 'sg.core 'handle))))
      (testing "ON: a NEW module-external map-arg fn lacking a :=> schema is hard-refused"
        (let [r (ops/add-form! sess 'sg.core "(defn accept \"A.\" [{:keys [y]}] y)"
                               :prompt "boundary fn, no schema")]
          (is (re-find #":malli/schema" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'sg.core 'accept))
              "the refused form never landed")))
      (testing "ON: a :=> schema WITHOUT :throws is still refused — a caller cannot
             tell whether it signals failure by throwing or by returning"
        (let [r (ops/add-form! sess 'sg.core
                               "(defn ^{:malli/schema [:=> [:cat [:map [:y :int]]] :int]} accept \"A.\" [{:keys [y]}] y)"
                               :prompt "boundary fn, schema but no throws")]
          (is (re-find #":throws" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'sg.core 'accept))
              "the refused form never landed")))
      (testing "ON: the same boundary fn WITH :=> and an explicit :throws lands"
        (let [r (ops/add-form! sess 'sg.core
                               "(defn ^{:malli/schema [:=> {:throws []} [:cat [:map [:y :int]]] :int]} accept \"A.\" [{:keys [y]}] y)"
                               :prompt "boundary fn, schema and explicit empty throws")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest rule-severity-reads-per-store-config
  (let [s0 (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [x] x)\n")]
    (testing "no override → the passed default"
      (is (= :refuse (gates/rule-severity s0 'module-refusal :refuse)))
      (is (= :advisory (gates/rule-severity s0 :key-typos :advisory))))
    (testing "the rules config file overrides per rule; the key coerces symbol/keyword/string"
      (let [s (first (store/record-config-put s0 "rules" :manifest "schema-refusal" "off"))]
        (is (= :off (gates/rule-severity s 'schema-refusal :refuse)))
        (is (= :off (gates/rule-severity s :schema-refusal :refuse)))
        (is (= :off (gates/rule-severity s "schema-refusal" :refuse)))
        (testing "an un-overridden rule keeps its default"
          (is (= :refuse (gates/rule-severity s 'module-refusal :refuse))))))))

(deftest gate-refusal-honors-off-severity
  (let [[t _] (store/record-module-tier
               (store/ingest (store/empty-store) 'app.core
                             "(ns app.core)\n\n(defn tick! \"T.\" [a] (swap! a inc))\n")
               "app.core" :pure)]
    (testing "the tier gate fires by default"
      (is (re-find #"functional-core" (str (gates/gate-refusal t 'app.core 'tick!)))))
    (testing "dialing tier-refusal :off in the rules config skips it (per-store severity)"
      (let [off (first (store/record-config-put t "rules" :manifest "tier-refusal" "off"))]
        (is (nil? (gates/gate-refusal off 'app.core 'tick!)))))
    (testing "an unrelated rule dialed :off leaves the tier gate firing"
      (let [other (first (store/record-config-put t "rules" :manifest "schema-refusal" "off"))]
        (is (re-find #"functional-core" (str (gates/gate-refusal other 'app.core 'tick!))))))))

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

(deftest ^:external namespaced-keys-gate-refuses-boundary-writes
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'nk.core "(ns nk.core)\n\n(defn seed \"S.\" [x] x)\n")
      (ops/config-file! sess "gates" :key "require-namespaced-keys" :value "true"
                        :prompt "require namespaced boundary keys")
      (testing "a boundary fn destructuring unqualified :keys is hard-refused"
        (let [r (ops/add-form! sess 'nk.core "(defn accept \"A.\" [{:keys [id]}] id)"
                               :prompt "bare keys at the boundary")]
          (is (re-find #"namespaced" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'nk.core 'accept)))))
      (testing "the namespaced form lands"
        (let [r (ops/add-form! sess 'nk.core "(defn accept \"A.\" [{:acct/keys [id]}] id)"
                               :prompt "namespaced keys")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest pure-tier-forbids-nondeterminism
  (let [rand-src "(ns app.core)\n\n(defn roll \"R.\" [] (rand-int 6))\n"
        pure-src "(ns app.core)\n\n(defn add \"A.\" [x y] (+ x y))\n"]
    (testing ":pure refuses a form reaching non-determinism (rand), with teaching"
      (let [[t _] (store/record-module-tier
                   (store/ingest (store/empty-store) 'app.core rand-src)
                   "app.core" :pure)]
        (is (re-find #"(?i)determinis" (str (tiers/tier-refusal t 'app.core 'roll))))))
    (testing ":pure still allows a referentially-transparent form"
      (let [[t _] (store/record-module-tier
                   (store/ingest (store/empty-store) 'app.core pure-src)
                   "app.core" :pure)]
        (is (nil? (tiers/tier-refusal t 'app.core 'add)))))
    (testing ":reads tolerates non-determinism (rand is not a mutation)"
      (let [[t _] (store/record-module-tier
                   (store/ingest (store/empty-store) 'app.core rand-src)
                   "app.core" :reads)]
        (is (nil? (tiers/tier-refusal t 'app.core 'roll)))))))

(deftest rule-severity-coerces-and-validates
  (let [s0   (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [x] x)\n")
        with (fn [v] (first (store/record-config-put s0 "rules" :manifest "schema-refusal" v)))]
    (testing "a leading colon is tolerated — ':off' and 'off' both disable"
      (is (= :off (gates/rule-severity (with ":off") 'schema-refusal :refuse)))
      (is (= :off (gates/rule-severity (with "off") 'schema-refusal :refuse))))
    (testing "an unknown/empty severity falls back to the default, not a junk keyword"
      (is (= :refuse (gates/rule-severity (with "garbage") 'schema-refusal :refuse)))
      (is (= :refuse (gates/rule-severity (with "") 'schema-refusal :refuse))))))

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
      (is (nil? (gates/gate-refusal adv 'app.core 'tick!)))
      (is (nil? (:refuse (gates/gate-check adv 'app.core 'tick!)))))
    (testing "but its teaching surfaces via gate-check :advisories (warn-but-proceed)"
      (is (re-find #"functional-core"
                   (str (first (:advisories (gates/gate-check adv 'app.core 'tick!)))))))
    (testing "a refuse-grade gate blocks and is not an advisory"
      (let [gc (gates/gate-check t 'app.core 'tick!)]
        (is (re-find #"functional-core" (str (:refuse gc))))
        (is (empty? (:advisories gc)))))))

(deftest ^:external advisory-write-gate-warns-but-proceeds
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'aw.core "(ns aw.core)\n\n(defn seed \"S.\" [x] x)\n")
      (ops/config-file! sess "gates" :key "require-namespaced-keys" :value "true"
                        :prompt "require namespaced boundary keys")
      (ops/config-file! sess "rules" :key "namespaced-keys-refusal" :value "advisory"
                        :prompt "but only advise, don't block")
      (let [r (ops/add-form! sess 'aw.core "(defn accept \"A.\" [{:keys [id]}] id)"
                             :prompt "bare keys — should warn, not block")]
        (testing "the write LANDS (advisory, not blocked)"
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'aw.core 'accept))))
        (testing "and the gate's teaching rides the result's :advisories"
          (is (re-find #"namespaced" (str (first (:advisories r)))) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external purity-gate-exempts-test-namespaces
  ;; A test namespace belongs to its module (x.y-test → x.y), so declaring a
  ;; module :pure was silently making its TESTS unwritable — they set up
  ;; sessions and exercise effects by design, which is the whole job. The tier
  ;; is a claim about the functional CORE, not about the code that drives it.
  ;; Found by cleanup {all true} on slopp's own store, where declaring
  ;; slopp.normalize :pure had already stranded slopp.normalize-test.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'pt.core "(ns pt.core)\n(defn add [x y] (+ x y))\n")
      (ops/module-tier! sess "pt.core" :pure :prompt "a pure core")
      (testing "an effectful write to the production namespace is still refused"
        (let [r (ops/add-form! sess 'pt.core "(defn slurp! [f] (slurp f))"
                               :prompt "effect into a pure core")]
          (is (re-find #"declared :pure" (str (:error r))) (pr-str r))))
      (testing "the module's TEST namespace may reach effects"
        (ops/ingest! sess 'pt.core-test "(ns pt.core-test)\n")
        (let [r (ops/add-form! sess 'pt.core-test
                               "(defn setup! [f] (slurp f))"
                               :prompt "a test fixture doing IO")]
          (is (nil? (:error r))
              (str "tests exercise effects by design: " (pr-str r)))))
      (finally (ops/close! sess)))))

(deftest ^:external foreign-keys-marks-a-third-party-map-and-polices-itself
  ;; require-namespaced-keys cannot be satisfied by a fn that destructures
  ;; SOMEONE ELSE'S map — slopp.build/arg-style takes clj-kondo's analysis, and
  ;; we do not get to rename kondo's keys. ^:foreign-keys records that, and
  ;; polices itself like ^:ambient-ok / ^:unused-ok: a marker on a fn that has
  ;; no bare boundary keys is itself refused, so it cannot decay into a blanket
  ;; opt-out someone sprinkles to silence the gate.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "gates" :key "require-namespaced-keys" :value "true")
      (ops/ingest! sess 'fk.core "(ns fk.core)\n")
      (testing "an unmarked bare-keys boundary fn is refused"
        (let [r (ops/add-form! sess 'fk.core
                               "(defn takes-bare [{:keys [id]}] id)"
                               :prompt "bare keys at a boundary")]
          (is (re-find #"namespaced" (str (:error r))) (pr-str r))))
      (testing "^:foreign-keys discharges it"
        (let [r (ops/add-form! sess 'fk.core
                               "(defn ^:foreign-keys takes-foreign [{:keys [id]}] id)"
                               :prompt "third-party map")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "a marker with nothing to excuse is refused — no blanket opt-out"
        (let [r (ops/add-form! sess 'fk.core
                               "(defn ^:foreign-keys no-map [x] x)"
                               :prompt "stale marker")]
          (is (re-find #"remove the flag" (str (:error r))) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external rule-test-applicability-is-declared-not-rediscovered
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
    (doseq [g gates/per-form-write-gates]
      (is (contains? #{:all :production} (:rule/applies-to (meta g) :all))
          (str (:name (meta g)) " must declare :rule/applies-to :all or"
               " :production — leaving it implicit is how two surfaces"
               " disagreed about tests"))))
  (testing "the purity gate is production-only, and says so in one place"
    (is (= :production (:rule/applies-to (meta #'tiers/tier-refusal)))))
  (testing "and the report agrees with the gate by construction"
    (let [sess (external/open!)]
      (try
        (ops/ingest! sess 'ra.core "(ns ra.core)\n(defn add [x y] (+ x y))\n")
        (ops/ingest! sess 'ra.core-test "(ns ra.core-test)\n(defn setup! [f] (slurp f))\n")
        (is (= :pure (:supports (tiers/tier-report (:store @sess) 'ra.core)))
            "the effectful TEST namespace must not veto the module's tier")
        (finally (ops/close! sess))))))

(deftest ^:external namespaced-keys-gate-scope-is-pinned-in-both-directions
  ;; The anti-drift guard. A rule stated only in prose drifts: I read
  ;; "namespaced boundary keys" as a mandate to namespace keys store-wide and
  ;; set off migrating :session, :dir, :values — overloaded keys whose renames
  ;; are risky — when the gate's actual finding list was twelve forms.
  ;;
  ;; So the SCOPE is asserted, not described. Tightening the gate breaks the
  ;; negative cases; loosening it breaks the positive one. An agent cannot
  ;; drift in either direction without turning the suite red.
  ;;
  ;; Measured justification for the narrow scope: 674 distinct unqualified keys
  ;; appear in this store's production code and 445 in more than one form. The
  ;; most-shared are Clojure syntax (:require, :as, :when) and slopp's
  ;; universal result vocabulary (:error in 119 forms) — where one shared
  ;; spelling is right and namespacing is pure loss.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "gates" :key "require-namespaced-keys" :value "true")
      (ops/ingest! sess 'ks.core "(ns ks.core)\n")
      (testing "IN scope: a module-external defn destructuring bare :keys"
        (is (re-find #"namespaced"
                     (str (:error (ops/add-form! sess 'ks.core
                                                 "(defn boundary [{:keys [id]}] id)"
                                                 :prompt "in scope"))))))
      (testing "OUT of scope: a PRIVATE fn — the module can see its own producer"
        (is (nil? (:error (ops/add-form! sess 'ks.core
                                         "(defn- internal [{:keys [id]}] id)"
                                         :prompt "private")))))
      (testing "OUT of scope: keys read in the BODY, not destructured in the arglist"
        (is (nil? (:error (ops/add-form! sess 'ks.core
                                         "(defn reads-body [m] (:id m))"
                                         :prompt "body read")))))
      (testing "OUT of scope: a RETURN map's keys"
        (is (nil? (:error (ops/add-form! sess 'ks.core
                                         "(defn returns [] {:id 1 :error nil})"
                                         :prompt "return map")))))
      (testing "OUT of scope: a non-map argument"
        (is (nil? (:error (ops/add-form! sess 'ks.core
                                         "(defn plain [id] id)"
                                         :prompt "no map arg")))))
      (finally (ops/close! sess)))))

(deftest purity-gate-sees-console-io-and-watch-mutation
  (let [at (fn [src tier]
             (first (store/record-module-tier
                     (store/ingest (store/empty-store) 'app.core src)
                     "app.core" tier)))]
    (testing ":pure refuses a form that reaches console IO (println is an effect)"
      (let [t (at "(ns app.core)\n\n(defn shout \"S.\" [x] (println x))\n" :pure)]
        (is (tiers/tier-refusal t 'app.core 'shout)
            "println breaks referential transparency but entered :pure")))
    (testing ":pure refuses a form that reaches add-watch (registry mutation)"
      (let [t (at "(ns app.core)\n\n(defn spy \"S.\" [a] (add-watch a :k identity))\n" :pure)]
        (is (tiers/tier-refusal t 'app.core 'spy)
            "add-watch mutates the watch registry but entered :pure")))
    (testing ":internal STILL allows println (in-process, capturable)"
      (let [tp (at "(ns app.core)\n\n(defn shout \"S.\" [x] (println x))\n" :internal)]
        (is (nil? (tiers/tier-refusal tp 'app.core 'shout)))))))

(deftest late-ref-into-the-shell-is-a-layering-violation
  ;; the dialect gate ROUTES agents to (store/late-ref 'ns/name) to break a
  ;; load cycle — but the quoted target is invisible to both the require graph
  ;; (layering) and effect derivation (the symbol is pruned). So a :pure core
  ;; namespace could reach IO in an :external namespace with NO gate firing,
  ;; defeating d9157's "core must not depend on the shell".
  (let [st (-> (store/empty-store)
               (store/ingest 'app.io "(ns app.io)\n\n(defn ^:unused-ok read! \"R.\" [p] (slurp p))\n")
               (store/ingest 'app.core
                             (str "(ns app.core (:require [slopp.store :as store]))\n\n"
                                  "(defn ^:unused-ok pull \"P.\" [p]\n"
                                  "  ((store/late-ref 'app.io/read!) p))\n"))
               (store/record-module-tier "app.io" :external) first
               (store/record-module-tier "app.core" :pure) first)]
    (testing "layering flags a late-ref from a non-external ns into an :external one"
      (let [v (tiers/layering-violations st 'app.core :pure)]
        (is (some #(= 'app.io (:requires %)) v)
            (str "late-ref into the shell slipped past layering: " (pr-str v)))))
    (testing "a late-ref BETWEEN external namespaces is fine (both shell)"
      (let [st2 (first (store/record-module-tier st "app.core" :external))]
        (is (empty? (tiers/layering-violations st2 'app.core :external)))))))

(deftest ^:external module-platforms-surface-in-query-depends
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'plat.client :source "(ns plat.client)\n"
                      :platform :cljs :prompt "browser")
      (ops/create-ns! sess 'plat.shared :source "(ns plat.shared)\n"
                      :platform :cljc :prompt "portable")
      (ops/create-ns! sess 'plat.server :source "(ns plat.server)\n(defn ^:unused-ok f [] 1)\n")
      (let [r (graph/query-depends sess "" :modules true)]
        (testing "declared platforms surface in the module graph"
          (is (= :cljs (get (:platforms r) "plat.client")) (pr-str (:platforms r)))
          (is (= :cljc (get (:platforms r) "plat.shared")) (pr-str (:platforms r))))
        (testing "an undeclared ns (= :jvm default) is absent, not noise"
          (is (nil? (get (:platforms r) "plat.server")) (pr-str (:platforms r)))))
      (finally (ops/close! sess)))))

(deftest ^:external module-platform-verb
  (let [sess (external/open!)]
    (try
      (testing "declares a platform, folded onto the store"
        (let [r (ops/module-platform! sess "app.client" :cljs :prompt "browser code")]
          (is (= :cljs (:platform r)))
          (is (= "app.client" (:module r)))
          (is (= :cljs (get-in @sess [:store :module-platforms "app.client"])))))
      (testing "accepts a string spelling (MCP/JSON carries no keyword)"
        (is (= :cljc (:platform (ops/module-platform! sess "app.shared" ":cljc")))))
      (testing "defaults nil to :jvm"
        (is (= :jvm (:platform (ops/module-platform! sess "app.server" nil)))))
      (testing "rejects an unknown platform"
        (is (:error (ops/module-platform! sess "app.client" :wasm))))
      (testing "rejects a non-module string"
        (is (:error (ops/module-platform! sess "has spaces" :cljs))))
      (finally (ops/close! sess)))))

(deftest rule-applies-to-platform?-scopes-by-target
  (testing ":everywhere fires on every platform"
    (is (gates/rule-applies-to-platform? :everywhere :jvm))
    (is (gates/rule-applies-to-platform? :everywhere :cljs))
    (is (gates/rule-applies-to-platform? :everywhere :cljc)))
  (testing ":clojure fires on :jvm and :cljc, not :cljs"
    (is (gates/rule-applies-to-platform? :clojure :jvm))
    (is (gates/rule-applies-to-platform? :clojure :cljc))
    (is (not (gates/rule-applies-to-platform? :clojure :cljs))))
  (testing ":clojurescript fires on :cljs and :cljc, not :jvm"
    (is (gates/rule-applies-to-platform? :clojurescript :cljs))
    (is (gates/rule-applies-to-platform? :clojurescript :cljc))
    (is (not (gates/rule-applies-to-platform? :clojurescript :jvm))))
  (testing "the load-bearing case: a :cljc form is checked by BOTH worlds"
    (is (gates/rule-applies-to-platform? :clojure :cljc))
    (is (gates/rule-applies-to-platform? :clojurescript :cljc))))

(defn ^{:rule/severity :advisory} fixture-advisory-gate
  "Test fixture: a write gate that declares its OWN default severity as
   :advisory, with no per-store dial. Always fires."
  [_candidate _ns-sym _form-name]
  "fixture-advisory teaching")

(defn fixture-refuse-gate-a
  "Test fixture: a refuse-grade write gate (no :rule/severity declared, so the
   :refuse default applies). Always fires."
  [_candidate _ns-sym _form-name]
  "fixture-refuse-a teaching")

(defn fixture-refuse-gate-b
  "Test fixture: a second refuse-grade write gate, so one candidate trips two
   refusals in one pass. Always fires."
  [_candidate _ns-sym _form-name]
  "fixture-refuse-b teaching")

(deftest write-gate-declares-its-own-default-severity
  (let [t (store/ingest (store/empty-store) 'app.core
                        "(ns app.core)\n\n(defn f \"D.\" [x] x)\n")]
    (with-redefs [gates/per-form-write-gates [#'fixture-advisory-gate
                                                #'fixture-refuse-gate-a]]
      (let [gc (gates/gate-check t 'app.core 'f)]
        (testing "the gate's own :rule/severity is the default — no dial needed"
          (is (= ["fixture-advisory teaching"] (:advisories gc))))
        (testing "an undeclared gate still defaults to :refuse"
          (is (= "fixture-refuse-a teaching" (:refuse gc))))))
    (testing "a per-store dial still WINS over the declared default"
      (let [up (first (store/record-config-put
                       t "rules" :manifest "fixture-advisory-gate" "refuse"))]
        (with-redefs [gates/per-form-write-gates [#'fixture-advisory-gate]]
          (is (= "fixture-advisory teaching"
                 (:refuse (gates/gate-check up 'app.core 'f)))))))))

(deftest stacked-gates-teach-every-refusal-at-once
  (let [t (store/ingest (store/empty-store) 'app.core
                        "(ns app.core)\n\n(defn f \"D.\" [x] x)\n")]
    (with-redefs [gates/per-form-write-gates [#'fixture-refuse-gate-a
                                                #'fixture-refuse-gate-b]]
      (testing "gate-check keeps EVERY refuse-grade teaching, not just the first"
        (is (= ["fixture-refuse-a teaching" "fixture-refuse-b teaching"]
               (:refusals (gates/gate-check t 'app.core 'f)))))
      (testing ":refuse stays the first teaching (the existing shape)"
        (is (= "fixture-refuse-a teaching"
               (:refuse (gates/gate-check t 'app.core 'f)))))
      (testing "the blocking message carries the others so ONE resend satisfies both"
        (let [msg (gates/gate-refusal t 'app.core 'f)]
          (is (re-find #"fixture-refuse-a teaching" msg))
          (is (re-find #"fixture-refuse-b teaching" msg))
          (is (re-find #"(?i)also pending" msg)))))
    (testing "a lone refusal reads exactly as before — no 'also pending' noise"
      (with-redefs [gates/per-form-write-gates [#'fixture-refuse-gate-a]]
        (is (= "fixture-refuse-a teaching" (gates/gate-refusal t 'app.core 'f)))))))

(deftest ^:external module-extract-lands-exports-renames-and-edges-as-one-intent
  ;; The regroup a component restructure repeats per component. me.helper goes
  ;; DEEP under me.core, so me.other loses visibility of everything it calls
  ;; there. Order is the whole design: exports must land BEFORE the rename, or
  ;; the intermediate store is one the module gate refuses.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'me.helper
                   (str "(ns me.helper)\n"
                        "(defn shared \"Reached from outside.\" [x] x)\n"
                        "(defn local \"Reached only from me.core.\" [x] x)\n"))
      (ops/module-dep! sess "me.core" "me.helper" :prompt "core uses helper")
      (ops/module-dep! sess "me.other" "me.helper" :prompt "other uses helper")
      (ops/ingest! sess 'me.core
                   (str "(ns me.core (:require [me.helper :as h]))\n"
                        "(defn a \"A.\" [x] (h/local x))\n"))
      (ops/ingest! sess 'me.other
                   (str "(ns me.other (:require [me.helper :as h]))\n"
                        "(defn b \"B.\" [x] (h/shared x))\n"))
      (let [r (ops/module-extract! sess '[me.helper] 'me.core
                                   :prompt "regroup helper under core")]
        (is (nil? (:error r)) (pr-str r))
        (testing "the namespace moved, with its callers rewritten"
          (is (nil? (get-in @sess [:store :namespaces 'me.helper])))
          (is (some? (get-in @sess [:store :namespaces 'me.core.helper]))))
        (testing "only the var an outside caller reaches was hoisted"
          (let [st (:store @sess)]
            (is (true? (modules/export-level st 'me.core.helper 'shared)))
            (is (nil? (modules/export-level st 'me.core.helper 'local)))))
        (testing "the edge reality now requires is declared"
          (is (contains? (get (modules/modules-manifest (:store @sess)) "me.other")
                         "me.core")))
        (testing "THE property: the end state has no module violations"
          (is (nil? (:error (ops/edit-replace!
                             sess 'me.other 'b
                             "(defn b \"B.\" [x] (h/shared (inc x)))"
                             :prompt "a write still passes the gate afterwards")))))) 
      (finally (ops/close! sess)))))

(deftest ^:external namespace-grained-registers-follow-an-ns-rename
  ;; The manifest already follows a rename. The purity TIER and the PLATFORM
  ;; are the other two registers keyed by namespace, and they did not — so
  ;; extracting slopp.store left "slopp.render" :internal declared for a
  ;; namespace that no longer exists while slopp.store.render, which holds
  ;; the actual code, had no tier at all. Silent un-gating, plus a view that
  ;; lists ghosts.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'tr.core "(ns tr.core)\n(defn f \"F.\" [x] x)\n")
      (ops/module-tier! sess "tr.core" :pure :prompt "a pure core")
      (ops/module-platform! sess "tr.core" "cljc" :prompt "shared with the client")
(ops/module-role! sess "tr.core" :instrument :prompt "a hand-run probe")
      (is (= :pure (get-in @sess [:store :module-tiers "tr.core"])))
      (is (nil? (:error (ops/ns-rename! sess 'tr.core 'tr.hub :prompt "rebrand"))))
      (testing "the tier follows the name it describes"
        (is (= :pure (get-in @sess [:store :module-tiers "tr.hub"])))
        (is (nil? (get-in @sess [:store :module-tiers "tr.core"]))
            "and the old key does not linger as a ghost"))
      (testing "so does the platform"
        (is (= :cljc (get-in @sess [:store :module-platforms "tr.hub"])))
        (is (nil? (get-in @sess [:store :module-platforms "tr.core"]))))
(testing "and so does the ROLE — three registers, one rule"
        (is (= :instrument (get-in @sess [:store :module-roles "tr.hub"])))
        (is (nil? (get-in @sess [:store :module-roles "tr.core"]))))
      (finally (ops/close! sess)))))

(deftest ^:external the-whole-store-check-names-no-app-type
  ;; R6 (no `slopp.*` surface may assume a project is a web project), and the
  ;; sibling of `ops.engine-test/the-write-engine-names-no-app-type` — same
  ;; rule, the other generic surface. `full_check` answers "is the STORE good",
  ;; which is a question every project has, and it reached into the WEB tooling
  ;; for one part of the answer: how far behind the served app image is.
  ;;
  ;; **It was a CYCLE before it was anything else.** With the tooling in
  ;; `slopp.webdev`, the edges run BOTH ways: several the right way (tooling
  ;; calls the operation surface — that is what tooling does) and this ONE
  ;; back. `module_dep` cycle-checks adds, so the single edge blocked the whole
  ;; regroup, and the move to `slopp.webdev` would only have renamed the cycle.
  ;;
  ;; And it never needed to be there. `behind` was `(store running)` delegating
  ;; the count to `read.orient/code-deltas-since`, and `running` is the
  ;; app-server map already on the session — nothing in it knows the app serves
  ;; HTTP. So the R6 violation and the cycle had one fix.
  ;;
  ;; Why a named test rather than a layering rule: while both namespaces sat in
  ;; module `slopp.api`, layering could not see this at all — it is a
  ;; MODULE-grain question, so the drawer hid the violation from the check
  ;; built to find it, the third time in this restructure. Now that the tooling
  ;; has its own module the layering check CAN see it, and this test survives
  ;; the move as the specific statement of what layering states generically.
  ;; Its sibling `web-tooling-is-reached-only-by-the-transport` states it over
  ;; the whole image; this one states it about the surface that broke.
  (let [st  (external/built-store)
        src (render/render-ns st 'slopp.ops.external)
        pat #"slopp\.webdev"]
    (testing "there is a population — the vacuity that ate a sibling guard"
      ;; and it doubles as the guard on the quoted symbol above: a namespace
      ;; name in a test body is DATA, so a rename walks straight past it and
      ;; the check silently starts reading nothing
      (is (< 50 (count (:namespaces st))))
      (is (re-find #"full-check!" src)
          "rendered the wrong namespace, or rendered nothing"))
    (testing "the search pattern still matches something, somewhere"
      ;; The SAME guard, one level down, and the level this test was missing:
      ;; the pattern is data too. Phase 3 renamed the web tooling out of
      ;; `slopp.api`, and the previous pattern — `slopp\.api\.(?:cljs|devserver)`
      ;; — went on matching nothing, forever, silently. `slopp.mcp` names the
      ;; tooling on purpose (it is the transport, the one declared exception),
      ;; so if the pattern stops matching THERE it has stopped matching
      ;; anywhere and the assertion below is measuring an empty search.
      (is (seq (re-seq pat (render/render-ns st 'slopp.mcp)))
          "the pattern no longer matches the tooling's own consumer — retarget it"))
    (testing "the whole-store check names no web-tooling namespace, by any path"
      ;; require, qualified ref and prose all read the same here on purpose
      (is (= [] (vec (re-seq pat src)))
          "the offending mentions are the failure value"))))

(deftest the-web-framework-never-reaches-back-into-slopp
  ;; slopp.web.* is the FRAMEWORK slopp ships to users: build.clj's slim
  ;; io.github.nvoxland/slopp-web jar is exactly slopp/web.clj + slopp/web/**.
  ;; slopp.api.* is slopp's OWN webapp built on that framework — a peer of any
  ;; user's app. The dependency runs ui -> web and NEVER the reverse, because a
  ;; framework namespace that reaches back into slopp's core makes that jar
  ;; unloadable, and it breaks at the USER's require time rather than ours.
  ;;
  ;; Asserted rather than remembered: the invariant is one :require line away
  ;; from being false, added for a perfectly good local reason, with nothing
  ;; else to complain. The module gate states the same rule from the other side
  ;; (slopp.web declares no outgoing edges and sits at layer 0); this catches a
  ;; require that never became a declared edge.
  ;;
  ;; **The population is DERIVED, and it used to be a hand-kept vector of ten.**
  ;; That list had a liveness control on it — every named namespace must load
  ;; before it can be checked — and the control was answering a different
  ;; question than the one that mattered. It catches a namespace that failed to
  ;; LOAD. Nothing caught the list falling behind the CODE, so the eleventh
  ;; framework namespace was unchecked while this test reported the rule green
  ;; over the ten that were remembered. Same shape as the stale search PATTERN
  ;; two waves ago: a guard whose population is authored drifts from the thing
  ;; it guards, and the store already knows the answer.
  ;;
  ;; MEASURED when the derivation landed: the list held ten and the framework
  ;; is FOURTEEN. `slopp.web.client`, `slopp.web.contract` and `slopp.web.jwks`
  ;; had never been in it — three namespaces that ship in the slim jar, whose
  ;; leaks would surface at a user's require time, and which this test had been
  ;; reporting on without ever looking at. They are clean, which is luck rather
  ;; than evidence: nothing would have said otherwise.
  (let [web?      #(boolean (re-matches #"slopp\.web(\..*)?" (str %)))
        framework (->> (all-ns)
                       (map ns-name)
                       (filter web?)
                       (remove #(re-find #"-test$" (str %)))
                       sort
                       vec)
        leaks     (for [n     framework
                        [_ dep] (ns-aliases (find-ns n))
                        :let  [d (ns-name dep)]
                        :when (and (re-find #"^slopp\." (str d)) (not (web? d)))]
                    [n d])]
    ;; guard the guard: over an empty set "no leaks" is vacuously true, which
    ;; is exactly the "I could not check" / "I checked and found nothing"
    ;; conflation this codebase refuses everywhere else.
    (is (some #{'slopp.web.html} framework)
        (str "the scan found the framework — two empty sets agree about "
             "everything, and a derived population can go empty in ways a "
             "literal one cannot. Pinned on a NAMED member rather than a "
             "count, because a count is a second hand-kept number and would "
             "go stale in the other direction the first time a framework "
             "namespace legitimately leaves. Found: " framework))
    (is (empty? leaks)
        (str "slopp.web.* must not depend on anything outside slopp.web.* — "
             "the slim slopp-web jar ships only slopp/web/**, so these "
             "requires would not resolve in a user's project: "
             (vec leaks)))))

(deftest ^:external cycle-refusal-judges-production-edges-not-test-fixtures
  ;; A `-test` namespace folds into its subject's module, so a fixture
  ;; require manufactures a module edge that no production namespace has.
  ;; production-manifest already excludes those — "excluding them tells the
  ;; truth", its own docstring says — and query_depends reports layers from
  ;; that graph. The cycle GATE was asking the declared graph instead, so
  ;; the two surfaces disagreed: the architecture view showed a clean DAG
  ;; while the gate refused an edge for closing a cycle only a test made.
  ;;
  ;; Found in anger: slopp.mcp → slopp.http-api was refused for closing
  ;; ui → api → index → mcp → ui, where index → mcp exists only because
  ;; slopp.index.deps-test calls slopp.mcp/handle!.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'mp.core "(ns mp.core)\n(defn shared \"P.\" [x] x)\n")
      (ops/ingest! sess 'mr.tool "(ns mr.tool)\n(defn helper \"H.\" [x] x)\n")
      (ops/module-dep! sess "mq.app" "mp.core" :prompt "production: app calls core")
      (ops/ingest! sess 'mq.app
                   (str "(ns mq.app (:require [mp.core :as core]))\n"
                        "(defn use-it \"Uses mp.\" [x] (core/shared x))\n"))
      (ops/module-dep! sess "mp.core" "mr.tool" :prompt "a TEST fixture reaches for a tool")
      (ops/ingest! sess 'mp.core-test
                   (str "(ns mp.core-test (:require [clojure.test :refer [deftest is]]\n"
                        "                            [mr.tool :as tool]))\n"
                        "(deftest fixture-uses-the-tool (is (= 1 (tool/helper 1))))\n"))
      (testing "the manufactured edge is in the DECLARED manifest"
        (is (contains? (get (modules/modules-manifest (:store @sess)) "mp.core") "mr.tool")))
      (testing "an edge blocked only by a test-manufactured path is allowed"
        (let [r (ops/module-dep! sess "mr.tool" "mq.app"
                                 :prompt "acyclic in production: mq→mp, and mr is a leaf")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "a genuine production cycle is still refused"
        (let [r (ops/module-dep! sess "mp.core" "mq.app"
                                 :prompt "production mq.app → mp.core already exists")]
          (is (re-find #"(?i)closes a dependency cycle" (str (:error r))) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external declarations-say-which-axes-they-verified
  ;; D-surface-honesty at DECLARATION grain. Every register verb checks some
  ;; axes and not others, and the omissions are deliberate (layering is a
  ;; whole-GRAPH property, so D-rule-grain keeps it out of write grain). What
  ;; is NOT defensible is returning the same shape either way: an agent reads
  ;; "declared" as "checked". Each verb names what it verified, what it did
  ;; not, and where the unverified axis IS judged.
  (let [sess (external/open!)]
    (try
      (testing "module_purity verified the forms, NOT the require graph"
        (let [r (ops/module-tier! sess "app.core" :pure)]
          (is (= [:forms] (:verified r)))
          (is (= [:layering] (:unverified r)))
          (is (re-find #"full_check" (str (:note r)))
              "the note must name where layering IS checked")))
      (testing "an :external tier asserts nothing, so it verified nothing"
        ;; tier-violations returns [] immediately for :external — the claim is
        ;; empty, and a verb that reported [:forms] here would be claiming a
        ;; check it skipped by definition.
        (let [r (ops/module-tier! sess "app.shell" :external)]
          (is (= [] (:verified r)))))
      (testing "module_platform verifies nothing about the code at all"
        (let [r (ops/module-platform! sess "app.client" :cljs)]
          (is (= [] (:verified r)))
          (is (= [:compilation] (:unverified r)))
          (is (re-find #"compile_client" (str (:note r))))))
      (testing "module_dep verified cycles over PRODUCTION edges only"
        (let [r (ops/module-dep! sess "app.core" "app.util")]
          (is (= [:cycles] (:verified r)))
          (is (= [:usage] (:unverified r)))))
      (testing "a refusal carries no axes — it is not a partial answer"
        (let [r (ops/module-tier! sess "has spaces" :pure)]
          (is (:error r))
          (is (nil? (:verified r)))
          (is (nil? (:unverified r)))))
      (finally (ops/close! sess)))))

(deftest tier-report-reads-the-GOVERNING-tier-not-the-modules
  ;; `tier-for` is THE producer of "which tier governs this namespace": most
  ;; specific declaration wins, namespace grain, because a pure core routinely
  ;; lives one level below an effectful module. `tier-report` answered the same
  ;; question a second way — `(get (:module-tiers store) (module-of ns))` — and
  ;; the two disagreed on 28 of slopp's own 75 production namespaces.
  ;;
  ;; The dangerous direction is the one measured on `slopp.lab.mine`, whose
  ;; own `:external` declaration exists BECAUSE a fold silently governed it
  ;; `:pure` (frictions #11): the report said `:pure` anyway. A migration aid
  ;; that misreports where the code stands is worse than none.
  (let [st (-> (store/empty-store)
               (store/ingest 'tg.core "(ns tg.core)\n(defn f \"F.\" [] 1)\n")
               (store/ingest 'tg.core.deep "(ns tg.core.deep)\n(defn g \"G.\" [] 2)\n")
               (as-> s (first (store/record-module-tier s "tg.core" :external)))
               (as-> s (first (store/record-module-tier s "tg.core.deep" :pure))))]
    (is (= :pure (tiers/tier-for st 'tg.core.deep))
        "the deep namespace's own declaration is the most specific")
    (is (= :pure (:tier (tiers/tier-report st 'tg.core.deep)))
        "the report must name the tier that GOVERNS, from the one producer")
    (is (= :external (:tier (tiers/tier-report st 'tg.core)))
        "and the module's own declaration still governs the module namespace")))

(deftest ^:external a-tier-or-platform-declaration-can-be-RETIRED
  ;; `record-module-tier` has accepted `:action :remove` since the rename path
  ;; needed it, and no TOOL could reach it — so an agent that mis-declared a
  ;; tier could overwrite it but never retire it. Measured on slopp's own
  ;; store: five tier entries naming namespaces that no longer exist, listed
  ;; by query_depends as though they governed something.
  ;;
  ;; A store op nothing can reach is its own smell, and a register view that
  ;; lists things which are not there is the wart D-surface-honesty names.
  ;; `module_dep` always had `remove: true`; these two now match it.
  (let [sess (external/open!)]
    (try
      (testing "a tier can be declared and then retired"
        (ops/module-tier! sess "rm.core" :pure :prompt "core is pure")
        (is (= :pure (get-in @sess [:store :module-tiers "rm.core"])))
        (let [r (ops/module-tier! sess "rm.core" nil :remove true :prompt "not any more")]
          (is (nil? (:error r)) (pr-str r))
          (is (= :removed (:action r)) (pr-str r))
          (is (nil? (get-in @sess [:store :module-tiers "rm.core"]))
              "retired, not overwritten with a looser tier")))
      (testing "retiring what was never declared is an ERROR, not a silent no-op"
        (is (:error (ops/module-tier! sess "rm.absent" nil :remove true))))
      (testing "the same for a platform"
        (ops/module-platform! sess "rm.ui" :cljs :prompt "browser code")
        (let [r (ops/module-platform! sess "rm.ui" nil :remove true :prompt "back to jvm")]
          (is (nil? (:error r)) (pr-str r))
          (is (= :removed (:action r)))
          (is (nil? (get-in @sess [:store :module-platforms "rm.ui"]))))
        (is (:error (ops/module-platform! sess "rm.absent" nil :remove true))))
      (finally (ops/close! sess)))))

(deftest ^:external module-extract-verifies-ONCE-not-once-per-rename
  ;; A three-namespace extraction ran past 465s and had to be backgrounded.
  ;; The cost is structural, not accidental: composites in slopp are written
  ;; as sequences of user-facing verbs, and each verb carries its own
  ;; verification because it is a verb. So one logical change pays N of them.
  ;;
  ;; The honest bar is that the END STATE is green, and it is the only bar a
  ;; batch can meet anyway — mid-batch the store has namespaces renamed and
  ;; callers not yet rewritten, so a verification there is meaningless.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'mx.one "(ns mx.one)\n(defn a \"A.\" [] 1)\n")
      (ops/ingest! sess 'mx.two "(ns mx.two)\n(defn b \"B.\" [] 2)\n")
      (let [verifies #(count (filter (fn [d] (= :verify (:op d)))
                                     (store/deltas (:store @sess))))
            before   (verifies)
            r        (ops/module-extract! sess ['mx.one 'mx.two] "mx.core"
                                          :prompt "regroup under one prefix")]
        (is (nil? (:error r)) (pr-str r))
        (is (= 2 (count (:renames (:extracted r)))) (pr-str r))
        (is (= 1 (- (verifies) before))
            "one transaction, ONE verification — not one per rename"))
      (finally (ops/close! sess)))))

(deftest ^:external deleting-a-namespace-retires-declarations-it-alone-owned
  ;; A tier or platform describes a NAME, and `ns_rename` already carries them
  ;; across for exactly that reason — orphaned, a declaration "lists a
  ;; namespace that no longer exists". DELETE never did, so slopp's own store
  ;; accumulated FIFTEEN of them (5 tiers, 10 platforms) across one wave of
  ;; deletions, each listed by query_depends as though it governed something.
  ;;
  ;; The exception is what makes this a PREFIX question rather than a key
  ;; lookup: a declaration on `dg.keep` governs `dg.keep.deep` too, so
  ;; retiring it along with the empty husk would silently UNGATE live code.
  ;; Only a declaration left covering nothing goes.
  (let [sess (external/open!)]
    (try
      (testing "a declaration naming only the deleted namespace is retired with it"
        (ops/ingest! sess 'dg.gone "(ns dg.gone)\n")
        (ops/module-tier! sess "dg.gone" :pure :prompt "leaf, pure")
        (ops/module-platform! sess "dg.gone" :cljc :prompt "shared")
(ops/module-role! sess "dg.gone" :instrument :prompt "hand-run")
        (is (= :pure (get-in @sess [:store :module-tiers "dg.gone"])))
        (let [r (ops/delete-ns! sess 'dg.gone :prompt "retire the scaffold")]
          (is (nil? (:error r)) (pr-str r)))
        (is (nil? (get-in @sess [:store :module-tiers "dg.gone"]))
            "the tier went with the namespace it named")
        (is (nil? (get-in @sess [:store :module-platforms "dg.gone"]))
            "and so did the platform")
(is (nil? (get-in @sess [:store :module-roles "dg.gone"]))
            "and so did the role — three registers, one rule"))
      (testing "a declaration still governing live code SURVIVES the husk's deletion"
        (ops/ingest! sess 'dg.keep "(ns dg.keep)\n")
        (ops/ingest! sess 'dg.keep.deep "(ns dg.keep.deep)\n(defn ^:unused-ok f [x] x)\n")
        (ops/module-tier! sess "dg.keep" :pure :prompt "the whole subtree is pure")
        (let [r (ops/delete-ns! sess 'dg.keep :prompt "the husk is empty; the subtree is not")]
          (is (nil? (:error r)) (pr-str r)))
        (is (= :pure (get-in @sess [:store :module-tiers "dg.keep"]))
            "dg.keep.deep is still governed by it — retiring would ungate live code"))
      (finally (ops/close! sess)))))

(deftest ^:external a-build-carries-its-purity-tiers-as-a-resource
  ;; friction 2. A purity tier is a DECLARATION in the producer's store, not
  ;; anything in the code — so a published jar carries the code and leaves the
  ;; tiers behind. Measured: `slopp.ui.hub` moved into the slopp-ui project
  ;; unchanged and immediately drew four effect warnings it never drew at home,
  ;; because `slopp.web.html` is declared :pure at home and undeclared (hence
  ;; :external) in the consumer.
  ;;
  ;; The damage is not the warning, it is the SUGGESTION: rename `picker` to
  ;; `picker!`. Following it would permanently mislabel four correct pure
  ;; functions in the consumer's public API to compensate for a declaration
  ;; that never shipped.
  ;;
  ;; So a build emits the tiers as a classpath resource, next to the sources it
  ;; already writes, where `deps_add` can find them.
  (let [sess (external/open!)
        dir  (str (System/getProperty "java.io.tmpdir")
                  "/slopp-tiers-" (System/nanoTime))]
    (try
      (ops/ingest! sess 'pl.core
                   "(ns pl.core)\n(defn ^:unused-ok render [x] (str x))\n")
      (ops/ingest! sess 'pl.io
                   "(ns pl.io)\n(defn ^:unused-ok touch! [f] (slurp f))\n")
      (ops/module-tier! sess "pl.core" :pure :prompt "rendering is pure")
      (ops/module-tier! sess "pl.io" :external :prompt "it reads files")
      (let [r (external/build! sess dir)]
        (is (nil? (:error r)) (pr-str r)))
      (let [f (clojure.java.io/file dir "src" "META-INF" "slopp" "tiers.edn")]
        (is (.exists f) (str "no tier resource at " f))
        (is (= {"pl.core" :pure "pl.io" :external}
               (clojure.edn/read-string (slurp f)))
            "every declared tier travels, not just the pure ones"))
      (finally
        (ops/close! sess)
        (doseq [f (reverse (file-seq (clojure.java.io/file dir)))] (.delete f))))))

(deftest ^:external deps-add-adopts-a-published-librarys-purity-tiers
  ;; friction 2's other half. Emitting the tiers is useless unless the consumer
  ;; FINDS them, so this drives the actual user flow across two stores: build a
  ;; library, then depend on it from somewhere else and check the consumer's
  ;; effect analysis learned something.
  ;;
  ;; Adopting the producer's declaration is better founded than the alternative
  ;; the consumer has today. `deps_pure` makes the consumer assert purity about
  ;; code it did not write and cannot check; the producer's tier was VERIFIED
  ;; against the forms when it was declared.
  (let [dir      (str (System/getProperty "java.io.tmpdir")
                      "/slopp-pub-" (System/nanoTime))
        producer (external/open!)]
    (try
      (ops/ingest! producer 'plib.core
                   "(ns plib.core)\n(defn ^:unused-ok render [x] (str x))\n")
      (ops/module-tier! producer "plib.core" :pure :prompt "rendering is pure")
      (is (nil? (:error (external/build! producer dir))))
      (finally (ops/close! producer)))
    (let [consumer (external/open!)]
      (try
        (let [r (ops/deps-add! consumer 'pub/lib {:local/root dir}
                               :prompt "depend on the published library")]
          (is (nil? (:error r)) (pr-str r))
          (is (= '[plib.core] (:adopted-pure r))
              (str "the producer's :pure declaration travelled: " (pr-str r))))
        (is (contains? (:dep-pure (:store @consumer)) 'plib.core)
            "so a caller of plib.core/render is not flagged effectful")
        (finally
          (ops/close! consumer)
          (doseq [f (reverse (file-seq (clojure.java.io/file dir)))]
            (.delete f)))))))

(deftest ^:external deps-add-says-when-a-declared-namespace-is-shadowed
  ;; friction 15a: resolving is not GOVERNING. `add-libs` appends to a
  ;; DynamicClassLoader that delegates to its parent first, and anything the
  ;; host jar already carries lives in that parent — so the declared version
  ;; loses. Measured on slopp's own store: the manifest declares
  ;; metosin/malli 0.16.4 and `malli/core.cljc` resolves out of slopp.jar,
  ;; before AND after a successful add of 0.16.4.
  ;;
  ;; Fixing that is a packaging change. Saying it is not, and a silently wrong
  ;; version is exactly the shape D-surface-honesty exists to forbid: the
  ;; manifest reads as satisfied and is not.
  ;;
  ;; A shadow is reproduced here rather than assumed: publish one library to
  ;; two roots and depend on both, so `plib2.core` genuinely resolves from two
  ;; places at once.
  (let [d1 (str (System/getProperty "java.io.tmpdir") "/slopp-sh1-" (System/nanoTime))
        d2 (str (System/getProperty "java.io.tmpdir") "/slopp-sh2-" (System/nanoTime))
        producer (external/open!)]
    (try
      (ops/ingest! producer 'plib2.core
                   "(ns plib2.core)\n(defn ^:unused-ok v [] :one)\n")
      (is (nil? (:error (external/build! producer d1))))
      (is (nil? (:error (external/build! producer d2))))
      (finally (ops/close! producer)))
    (let [consumer (external/open!)]
      (try
        (let [r1 (ops/deps-add! consumer 'sh/one {:local/root d1} :prompt "first copy")]
          (is (nil? (:error r1)) (pr-str r1))
          (is (nil? (:shadowed r1)) "nothing shadows it yet"))
        (let [r2 (ops/deps-add! consumer 'sh/two {:local/root d2} :prompt "second copy")]
          (is (nil? (:error r2)) (pr-str r2))
          (is (contains? (:shadowed r2) 'plib2.core)
              (str "the second copy cannot govern — say so: " (pr-str r2))))
        (finally
          (ops/close! consumer)
          (doseq [f (concat (reverse (file-seq (clojure.java.io/file d1)))
                            (reverse (file-seq (clojure.java.io/file d2))))]
            (.delete f)))))))

(deftest ^:external module-violations-standing-in-the-store-are-reported-by-full-check
  ;; Friction #19. The module rules are WRITE gates — `module-scan` on
  ;; ingest, `module-refusal` per form — so they only ever see code being
  ;; written THROUGH them. A rename rewrites its own callers, and those
  ;; rewritten callers never pass a gate: the violation lands, and nothing
  ;; asks again. Four real ones stood through a GREEN `full_check` on slopp's
  ;; own store — the operation most likely to drift the architecture being the
  ;; one operation the architecture check cannot see.
  ;;
  ;; The whole-store fold was not missing. `modules/module-debt` — whose own
  ;; docstring says it "shows what already stands" — was wired into the module
  ;; graph view and into `module_dep`'s response, and not into the whole-store
  ;; gate. A check that exists and is never asked reads exactly like a check
  ;; that passes.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'mo.core "(ns mo.core)\n(defn shared \"Public.\" [x] x)\n")
      (ops/module-dep! sess "mo.app" "mo.core" :prompt "app uses core")
      (ops/ingest! sess 'mo.app
                   (str "(ns mo.app (:require [mo.core :as core]))\n"
                        "(defn ^:unused-ok use-it \"Uses core.\" [x] (core/shared x))\n"))
      (testing "the must-NOT-flag half — same fixture, one rename earlier"
        (let [r (external/full-check! sess)]
          (is (nil? (:module-violations r)) (pr-str (:module-violations r)))
          (is (= :green (:status r)) (pr-str r))))
      (testing "the rename that CREATES the violation is not refused"
        ;; 2 segments → 3 makes the target package-private to `mo.core.*`, and
        ;; the caller is outside that subtree. The declared EDGE survives —
        ;; `module-of` is still "mo.core" — so visibility alone is what breaks,
        ;; which is why nothing about the manifest looks wrong afterwards.
        (is (nil? (:error (ops/ns-rename! sess 'mo.core 'mo.core.impl
                                          :prompt "regroup")))))
      (testing "the RULES see it — so a green whole-store check is the check not asking"
        ;; localizes a future failure: this half is the rule, the next is the
        ;; wiring, and they fail for opposite reasons.
        (is (re-find #"package-private"
                     (str (modules/module-scan (:store @sess) 'mo.app)))))
      (testing "and full_check names it, red"
        (let [r (external/full-check! sess)]
          (is (= 1 (:count (:module-violations r)))
              (pr-str (:module-violations r)))
          (is (= [{:from-ns 'mo.app :from-var 'use-it
                   :target-ns 'mo.core.impl :rule :visibility}]
                 (:rows (:module-violations r)))
              (pr-str (:module-violations r)))
          ;; discriminating: red proves nothing unless every OTHER red-maker is
          ;; clean — the lesson `tier-layering-is-reported-by-full-check`
          ;; records, where the first version passed while the finding it named
          ;; was still purely advisory.
          (is (zero? (:lint-errors r)) (pr-str (:lint r)))
          (is (empty? (:unused-public r)) (pr-str (:unused-public r)))
          (is (empty? (:stale-unused-ok r)) (pr-str (:stale-unused-ok r)))
          (is (empty? (:tier-layering r)) (pr-str (:tier-layering r)))
          (is (zero? (+ (:fail (:test r) 0) (:error (:test r) 0)))
              (pr-str (:test r)))
          (is (= :red (:status r))
              (str "a standing module violation must FLIP the check red — a"
                   " finding the agent can scroll past is not a rule: "
                   (pr-str (select-keys r [:status :module-violations]))))))
      (finally (ops/close! sess)))))

(deftest ^:external a-rename-that-strands-a-caller-is-caught-at-done
  ;; The MODULE sibling of
  ;; `rules-test/a-namespace-that-MOVES-under-a-stricter-tier-is-caught-at-done`,
  ;; and the same class exactly: a purity tier and a module rule are both
  ;; inherited from a namespace's NAME, both enforced by gates that fire on
  ;; WRITE, and a relocation changes the name without writing the forms. The
  ;; tier half was found in anger and fixed at done. The module half went on
  ;; standing, and produced four real violations on slopp's own store that a
  ;; green `full_check` reported as clean.
  ;;
  ;; One asymmetry worth the fixture: the namespace that MOVED is not the one
  ;; that violates. `md.core` going three segments deep is legal; it is the
  ;; unmoved CALLER that is suddenly reaching into a package-private
  ;; namespace. So scoping this to "what moved" the way the tier check does
  ;; would find nothing — the episode's moves select which violations to
  ;; report, from either end of the edge.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'md.core "(ns md.core)\n(defn shared \"Public.\" [x] x)\n")
      (ops/module-dep! sess "md.app" "md.core" :prompt "app uses core")
      (ops/ingest! sess 'md.app
                   (str "(ns md.app (:require [md.core :as core]))\n"
                        "(defn ^:unused-ok use-it \"Uses core.\" [x] (core/shared x))\n"))
      (testing "the must-NOT-flag half: a done with no relocation in it"
        (let [r (external/done! sess :label "baseline")]
          (is (nil? (get-in r [:findings :module-governance]))
              (pr-str (get-in r [:findings :module-governance])))
          (is (= :green (get-in r [:findings :test-status])) (pr-str (:findings r)))))
      (testing "the rename itself is allowed — nothing about it is wrong"
        (is (nil? (:error (ops/ns-rename! sess 'md.core 'md.core.impl
                                          :prompt "regroup")))))
      (testing "and done names the caller the rename stranded"
        (let [r (external/done! sess :label "after the rename")
              f (get-in r [:findings :module-governance])]
          (is (seq f) (str "findings: " (pr-str (keys (:findings r)))))
          (is (= 'md.app (:ns (first f))) (pr-str f))
          (is (= 'use-it (:from-var (first f))) (pr-str f))
          (is (= 'md.core.impl (:target-ns (first f))) (pr-str f))
          (is (= :visibility (:rule (first f))) (pr-str f))
          (is (= :red (get-in r [:findings :test-status]))
              (str "a module rule the code no longer satisfies is the same"
                   " failure a write gate refuses: " (pr-str (:findings r))))))
      (finally (ops/close! sess)))))

(deftest ^:external a-cycle-refusal-names-a-test-only-crossing-as-what-it-is
  ;; Friction 19b, with its filed diagnosis CORRECTED by measurement. The claim
  ;; was that `module_dep` lacked a rule `module_extract` has — that a `-test`
  ;; back-edge is not a cycle. Both already judge cycles over PRODUCTION edges;
  ;; the refusal was right.
  ;;
  ;; What is actually in the way is narrower and worse: the manifest is
  ;; MODULE-grained, and `module-of` folds a trailing `-test` off each segment,
  ;; so a fixture shares its subject's module key. An edge that permits the
  ;; test permits production too. There is no declaration for "only my tests
  ;; cross here". Measured on slopp's own store: `slopp.store → slopp.api` and
  ;; `slopp.index → slopp.api` are both declared and both used by test
  ;; namespaces ONLY — zero production callers. Two standing edges that say
  ;; more than they mean.
  ;;
  ;; So the fix is not to relax the refusal. It is to stop giving advice the
  ;; agent cannot follow: "extract the shared piece into a module both sides
  ;; may depend on" is unactionable when the thing reaching across is a
  ;; fixture. Name the real obstruction, and name who is causing it.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'mq.helper "(ns mq.helper)\n(defn h \"H.\" [x] x)\n")
      (ops/module-dep! sess "mq.core" "mq.helper" :prompt "core uses helper")
      (ops/ingest! sess 'mq.core
                   (str "(ns mq.core (:require [mq.helper :as hp]))\n"
                        "(defn op \"O.\" [x] (hp/h x))\n"))
      ;; The fixture starts INSIDE its subject's module, where no edge is
      ;; needed — which is why nothing exists that a rename could carry along.
      ;; (An earlier version started it in a module of its own with a declared
      ;; edge, and `ns_rename` RE-KEYED that edge onto the new module, quietly
      ;; installing the very cycle this refusal exists to prevent. Logged
      ;; separately as its own friction; here it would hide the case under
      ;; test.)
      (is (nil? (:error (ops/ingest! sess 'mq.core.spec-test
                                     (str "(ns mq.core.spec-test (:require [mq.core :as core]))\n"
                                          "(defn ^:unused-ok probe \"P.\" [x] (core/op x))\n")))))
      ;; …and a regroup moves it under a DIFFERENT module, which is the
      ;; sequence that produces this every time.
      (is (nil? (:error (ops/ns-rename! sess 'mq.core.spec-test 'mq.helper.spec-test
                                        :prompt "regroup the fixture"))))
      (testing "the edge is still refused — the cycle is real"
        (let [r (ops/module-dep! sess "mq.helper" "mq.core" :prompt "the fixture needs it")]
          (is (re-find #"CLOSES a dependency cycle" (str (:error r))) (pr-str r))))
      (testing "but the refusal names the test and offers the declaration that fits"
        (let [e (str (:error (ops/module-dep! sess "mq.helper" "mq.core"
                                              :prompt "the fixture needs it")))]
          (is (re-find #"mq\.helper\.spec-test" e) e)
          (is (re-find #"(?i)is a TEST" e) e)
          (is (re-find #"test-only true" e) e)
          (is (not (re-find #"extracting the shared piece" e))
              (str "the generic advice cannot be followed when a fixture is"
                   " what reaches across: " e))))
      (testing "and a cycle with a PRODUCTION caller keeps the generic advice"
        ;; the must-not-flag half: the new branch must not swallow the case it
        ;; was carved out of.
        (ops/module-dep! sess "mr.core" "mr.helper" :prompt "core uses helper")
        (ops/ingest! sess 'mr.helper "(ns mr.helper)\n(defn h \"H.\" [x] x)\n")
        (ops/ingest! sess 'mr.core
                     (str "(ns mr.core (:require [mr.helper :as hp]))\n"
                          "(defn ^:unused-ok op \"O.\" [x] (hp/h x))\n"))
        (let [e (str (:error (ops/module-dep! sess "mr.helper" "mr.core"
                                              :prompt "the other way too")))]
          (is (re-find #"CLOSES a dependency cycle" e) e)
          (is (re-find #"extracting the shared piece" e) e)))
      (finally (ops/close! sess)))))

(deftest ^:external a-test-only-edge-is-declarable-and-binds-only-tests
  ;; Friction #20, settled 2026-08-02 (user). A done-time advisory can only be
  ;; tested by WRITING code and calling `done!`, so the fixture necessarily
  ;; calls the operation surface — while the operation surface calls the rules.
  ;; Once the rules become their own module that pair is a cycle, and the
  ;; manifest could not say "my tests cross here, my production code does not":
  ;; `module-of` folds a trailing `-test` off each segment, so a fixture shares
  ;; its subject's module key and one edge would license both.
  ;;
  ;; slopp already carried two edges of exactly this shape —
  ;; `slopp.index → slopp.api` and `slopp.store → slopp.api`, zero production
  ;; callers each — declared before the cycle check existed. They were not
  ;; grandfathered exceptions to a rule; they were the rule failing to be
  ;; expressible, written down twice.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'tz.helper "(ns tz.helper)\n(defn h \"H.\" [x] x)\n")
      (ops/module-dep! sess "tz.core" "tz.helper" :prompt "core uses helper")
      (ops/ingest! sess 'tz.core
                   (str "(ns tz.core (:require [tz.helper :as hp]))\n"
                        "(defn ^:unused-ok op \"O.\" [x] x)\n"
                        "(defn ^:unused-ok use-h \"U.\" [x] (hp/h x))\n"))
      (testing "the production edge back is refused — the cycle is real"
        (let [r (ops/module-dep! sess "tz.helper" "tz.core" :prompt "no")]
          (is (re-find #"CLOSES a dependency cycle" (str (:error r))) (pr-str r))))
      (testing "the SAME edge declared test-only is not a cycle — it is not a production edge"
        (let [r (ops/module-dep! sess "tz.helper" "tz.core"
                                 :test-only true
                                 :prompt "advisory tests must produce a done point")]
          (is (nil? (:error r)) (pr-str r))
          (is (true? (:test-only r)) (pr-str r))))
      (testing "`:modules` is untouched, so every production reader sees no new edge"
        (let [st (:store @sess)]
          (is (not (contains? (get (modules/modules-manifest st) "tz.helper" #{})
                              "tz.core"))
              (pr-str (modules/modules-manifest st)))
          (is (contains? (get (modules/module-test-manifest st) "tz.helper" #{})
                         "tz.core")
              (pr-str (modules/module-test-manifest st)))))
      (testing "a -test namespace under tz.helper may now cross"
        (is (nil? (:error (ops/ingest! sess 'tz.helper.spec-test
                                       (str "(ns tz.helper.spec-test (:require [tz.core :as core]))\n"
                                            "(defn ^:unused-ok probe \"P.\" [x] (core/op x))\n"))))))
      (testing "and PRODUCTION under tz.helper still may not — the guarantee the old edge never gave"
        (let [r (ops/ingest! sess 'tz.helper.impl
                             (str "(ns tz.helper.impl (:require [tz.core :as core]))\n"
                                  "(defn ^:unused-ok sneak \"S.\" [x] (core/op x))\n"))]
          (is (re-find #"does not declare tz\.core" (str (:error r))) (pr-str r))))
      (testing "the whole-store fold agrees with the write gate, in both directions"
        (let [debt (fn [] (let [st (:store @sess)]
                            (modules/module-violations (modules/modules-manifest st)
                                                       (modules/module-test-manifest st)
                                                       (modules/module-usage-rows st))))]
          ;; must-NOT-flag: nothing stands while only the test crosses…
          (is (nil? (debt)) (pr-str (debt)))
          ;; …and retracting the test edge makes that same crossing a violation,
          ;; which is what proves the fold CONSULTS the test manifest rather
          ;; than exempting test namespaces wholesale.
          (is (nil? (:error (ops/module-dep! sess "tz.helper" "tz.core"
                                             :test-only true :remove true
                                             :prompt "retract"))))
          (let [vs (debt)]
            (is (= 1 (count vs)) (pr-str vs))
            (is (= 'tz.helper.spec-test (:from-ns (first vs))) (pr-str vs)))))
      (finally (ops/close! sess)))))

(deftest ^:external a-rename-REPORTS-the-module-debt-it-leaves-standing
  ;; The sibling of `a-rename-that-strands-a-caller-is-caught-at-done`, one
  ;; step earlier. That test pins that `done` CATCHES the drift; this one pins
  ;; that the rename that caused it SAYS SO, at the moment the reader can act.
  ;;
  ;; Why it needs saying: the drift is silent by construction. `ns_rename`
  ;; rewrites its own callers through `apply-changeset`, which runs no gates,
  ;; so a crossing that would be REFUSED outright if you typed it is created
  ;; without a murmur. Measured over slopp's own regroup: module 1 cost 5
  ;; hand-declared edges, module 2 cost 8, module 3 cost 38 — every one worked
  ;; out by hand-writing the same simulation over the reference graph, twice,
  ;; because the tool that had all the information reported none of it.
  ;;
  ;; This state is ONLY reachable through a rename. `ingest!` gates, so the
  ;; fixture cannot type the undeclared call directly — which is the finding
  ;; in one sentence.
  (let [sess (external/open!)]
    (try
      ;; module `mr.core` keeps a second member, so the manifest does NOT
      ;; re-key. The re-key path (last namespace of a module leaves) already
      ;; carries the edges over; the uncovered case is the ordinary one, where
      ;; a module sheds ONE namespace and the callers are left pointing at a
      ;; module nobody declared.
      (ops/ingest! sess 'mr.core.query
                   "(ns mr.core.query)\n(defn ^:export read-it \"R.\" [] 2)\n")
      (ops/ingest! sess 'mr.core.history
                   "(ns mr.core.history)\n(defn ^:unused-ok h \"H.\" [] 1)\n")
      (ops/module-dep! sess "mr.app" "mr.core" :prompt "app reads")
      (ops/ingest! sess 'mr.app
                   (str "(ns mr.app (:require [mr.core.query :as q]))\n"
                        "(defn ^:unused-ok uses \"U.\" [] (q/read-it))\n"))
      ;; declared PRODUCTION, crossed only by a test — so the recommendation
      ;; below cannot be inherited from the old declaration, only derived
      (ops/module-dep! sess "mr.tool" "mr.core" :prompt "tool reads")
      (ops/ingest! sess 'mr.tool.core-test
                   (str "(ns mr.tool.core-test (:require [mr.core.query :as q]))\n"
                        "(defn ^:unused-ok t \"T.\" [] (q/read-it))\n"))
      (let [r    (ops/ns-rename! sess 'mr.core.query 'mr.read.query
                                 :prompt "the reads are their own module")
            debt (:module-debt r)
            edge (fn [to] (first (filter #(= to (:to %)) (:edges-needed debt))))]
        (is (nil? (:error r)) (pr-str r))
        (is (some? debt)
            (str "the rename must say what it left undeclared: " (pr-str (keys r))))
        (testing "every caller's module needs the edge, not just the one that moved"
          (is (= #{"mr.app" "mr.tool"}
                 (into #{} (map :from) (:edges-needed debt)))
              (pr-str (:edges-needed debt))))
        (testing "a production caller wants a production edge"
          (is (false? (:test-only (edge "mr.read"))) (pr-str debt))
          (is (= "mr.app" (:from (edge "mr.read"))) (pr-str debt)))
        (testing "a crossing only tests make wants test_only, whatever the OLD edge said"
          ;; mr.tool → mr.core was declared production; the recommendation is
          ;; read off who actually crosses, which is the same judgement
          ;; `:overstated-edges` makes after the fact, made before it instead
          (let [e (first (filter #(= "mr.tool" (:from %)) (:edges-needed debt)))]
            (is (true? (:test-only e)) (pr-str debt))
            (is (= ['mr.tool.core-test] (:crossed-by e)) (pr-str debt))))
        (testing "the note hands over the calls to make"
          (is (re-find #"module_dep" (str (:note debt))) (pr-str debt))))
      (testing "a rename that crosses nothing says nothing — absence means checked"
        (ops/ingest! sess 'mr.lone.thing
                     "(ns mr.lone.thing)\n(defn ^:unused-ok x \"X.\" [] 1)\n")
        (let [r (ops/ns-rename! sess 'mr.lone.thing 'mr.solo.thing :prompt "regroup")]
          (is (nil? (:module-debt r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external a-rename-warns-when-DECLARING-its-edges-would-close-a-cycle
  ;; The expensive half of the same report. An undeclared edge is a chore;
  ;; an undeclared edge that CANNOT be declared is a redesign, and the regroup
  ;; finds out only when `module_dep` refuses — which, in a wave of ten
  ;; renames, can be nine renames after the one that caused it.
  ;;
  ;; `mc.util → mc.app` is declared and legal. Moving a namespace INTO
  ;; `mc.util` that `mc.app` calls means `mc.app → mc.util` is now needed, and
  ;; those two together are a cycle. Nothing about either rename looks wrong;
  ;; the graph is what is wrong, and only the graph can say so.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'mc.app.thing
                   "(ns mc.app.thing)\n(defn ^:export t \"T.\" [] 1)\n")
      (ops/module-dep! sess "mc.util" "mc.app" :prompt "util builds on app")
      (ops/ingest! sess 'mc.util.other
                   (str "(ns mc.util.other (:require [mc.app.thing :as th]))\n"
                        "(defn ^:unused-ok o \"O.\" [] (th/t))\n"))
      (ops/ingest! sess 'mc.core.query
                   "(ns mc.core.query)\n(defn ^:export read-it \"R.\" [] 2)\n")
      (ops/ingest! sess 'mc.core.history
                   "(ns mc.core.history)\n(defn ^:unused-ok h \"H.\" [] 1)\n")
      (ops/module-dep! sess "mc.app" "mc.core" :prompt "app reads core")
      (ops/ingest! sess 'mc.app.reader
                   (str "(ns mc.app.reader (:require [mc.core.query :as q]))\n"
                        "(defn ^:unused-ok r \"R.\" [] (q/read-it))\n"))
      (testing "the graph is acyclic before the move — mc.util → mc.app → mc.core"
        (is (empty? (:cycles (store/module-layers
                              (modules/modules-manifest (:store @sess)))))
            "fixture precondition"))
      (let [r    (ops/ns-rename! sess 'mc.core.query 'mc.util.query
                                 :prompt "the reads join util")
            debt (:module-debt r)]
        (is (nil? (:error r)) (pr-str r))
        (testing "the edge is reported as needed, exactly as any other would be"
          (is (= [{:from "mc.app" :to "mc.util"}]
                 (mapv #(select-keys % [:from :to]) (:edges-needed debt)))
              (pr-str debt)))
        (testing "and the report says declaring it is a cycle, before you try"
          (is (= [["mc.app" "mc.util"]] (mapv vec (:cycles debt))) (pr-str debt))
          (is (re-find #"(?i)cycle" (str (:note debt))) (pr-str debt))))
      (testing "and module_dep does refuse it — the warning was not hypothetical"
        (let [r (ops/module-dep! sess "mc.app" "mc.util" :prompt "as advised")]
          (is (re-find #"CLOSES a dependency cycle" (str (:error r))) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest a-visibility-refusal-names-the-var-to-export
  ;; The refusal's one instruction is "mark the target ^:export in its defn",
  ;; and it named only the target NAMESPACE — so the var to mark had to be
  ;; worked out by hand, every time, from a message that had it available.
  ;; The callee was dropped one layer down, in the usage row, which is why
  ;; three separate reports each came out one fact short of actionable.
  (let [v (first (modules/module-violations
                  {}
                  [{:from-ns 'vz.a.caller :from-var 'go
                    :to 'vz.b.deep :to-name 'helper :to-export nil}]))]
    (is (= :visibility (:rule v)) (pr-str v))
    (testing "the prose names the var, so the fix is readable straight off"
      (is (re-find #"vz\.b\.deep/helper" (str (:error v))) (pr-str v)))
    (testing "and the row carries it, so a consumer need not parse the prose"
      (is (= 'helper (:target-name v)) (pr-str v))))
  (testing "a row that does not know its callee still refuses, generically"
    ;; module_extract and the hand-built rows in these tests both predate
    ;; :to-name; a missing callee must degrade the MESSAGE, never the rule.
    (let [v (first (modules/module-violations
                    {}
                    [{:from-ns 'vz.a.caller :from-var 'go
                      :to 'vz.b.deep :to-export nil}]))]
      (is (= :visibility (:rule v)) (pr-str v))
      (is (not (re-find #"/nil" (str (:error v)))) (pr-str v)))))

(deftest web-tooling-is-reached-only-by-the-transport
  ;; R6: no slopp.* surface may assume a project is a WEB project. Support for
  ;; an app TYPE lives in a module named for that type — slopp.webdev — and the
  ;; pattern has to be replicable for app type #2 without renaming type #1.
  ;; The generic surfaces must therefore not reach into it.
  ;;
  ;; slopp.mcp is the one exception, BY ROLE rather than by convenience: it is
  ;; the tool transport, so it wires every module's operations to a tool name
  ;; and necessarily touches all of them. Spelled at module grain because that
  ;; is the grain the exception is true at — the transport, not one namespace.
  ;;
  ;; Asserted rather than left to the module gate, which states a weaker thing.
  ;; slopp.read -> slopp.webdev is not a CYCLE, so the gate would only ask for
  ;; the edge to be DECLARED — and declaring it is exactly what a future agent
  ;; would reach for when the gate complains. This says the edge is wrong
  ;; however it is spelled.
  ;;
  ;; Not hypothetical: slopp.ops.external/full-check! called devserver/behind —
  ;; the generic whole-store check reaching into web tooling for an answer that
  ;; is not web-specific — and nothing complained, because both ends were in
  ;; one module and layering is a MODULE-grain question. The drawer hid the
  ;; violation from the check built to find it, which is the third time that
  ;; shape has been recorded in this restructure.
  (let [webdev?  #(boolean (re-matches #"slopp\.webdev(\..*)?" (str %)))
        exempt?  #(boolean (or (re-matches #"slopp\.mcp(\..*)?" (str %))
                               (re-find #"-test$" (str %))))
        reaching (for [n    (all-ns)
                       :let [nm (ns-name n)]
                       :when (and (re-find #"^slopp\." (str nm))
                                  (not (webdev? nm))
                                  (not (exempt? nm)))
                       [_ dep] (ns-aliases n)
                       :when (webdev? (ns-name dep))]
                   [nm (ns-name dep)])]
    ;; guard the guard: with no slopp.webdev.* loaded at all, "nothing reaches
    ;; it" is vacuously true — the could-not-check / checked-and-none
    ;; conflation this codebase refuses everywhere else.
    (is (seq (filter #(webdev? (ns-name %)) (all-ns)))
        "slopp.webdev.* must be loaded before anything can be said about it")
    (is (empty? reaching)
        (str "these generic namespaces reach app-type-specific web tooling; "
             "the question being asked is almost certainly not web-specific, "
             "so the answer is to move it, not to declare the edge: "
             (vec reaching)))))

(deftest ^:external module-role-verb
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'rv.core "(ns rv.core)\n(defn ^:unused-ok f [] 1)\n")
      (ops/ingest! sess 'rv.lab "(ns rv.lab)\n(defn ^:unused-ok -main [] 1)\n")
      (testing "undeclared is :product, and the register is empty"
        (is (= {} (:module-roles (:store @sess))))
        (is (= :product (store/role-for (:store @sess) 'rv.lab))))
      (testing "declaring records one canonical entry"
        (let [r (ops/module-role! sess "rv.lab" :instrument :prompt "run by hand")]
          (is (nil? (:error r)) (pr-str r))
          (is (= :instrument (:role r)))
          (is (= {"rv.lab" :instrument} (:roles r)))
          (is (= :instrument (store/role-for (:store @sess) 'rv.lab)))))
      (testing "a JSON string spelling round-trips to the keyword"
        (is (= :instrument (:role (ops/module-role! sess "rv.lab" ":instrument")))))
      (testing "an unknown role is refused by name"
        (let [r (ops/module-role! sess "rv.lab" :scratch)]
          (is (:error r))
          (is (re-find #":product" (:error r)) (pr-str r))))
      (testing "and it can be RETIRED — absent is not the same claim as :product"
        (let [r (ops/module-role! sess "rv.lab" nil :remove true)]
          (is (nil? (:error r)) (pr-str r))
          (is (= {} (:roles r))))
        (is (:error (ops/module-role! sess "rv.lab" nil :remove true))
            "removing a declaration that is not there is an error, not a silent no-op"))
      (finally (ops/close! sess)))))

(deftest ^:external declaring-an-instrument-checks-nothing-product-needs-it
  ;; :instrument is not a label, it MOVES the code out of src/ — so the one
  ;; thing that must not be true is that product code requires it. Unchecked,
  ;; the failure lands at a consumer's load time naming a namespace the store
  ;; plainly has, which is the worst possible place to learn it.
  ;;
  ;; The `is` on each ingest is not ceremony. Written without them this test
  ;; PASSED against a store where `ri.app` had been refused for crossing an
  ;; undeclared module edge — so the refusal assertions were satisfied by there
  ;; being no caller at all (Correction 7: a fixture that failed to build
  ;; satisfies every absence assertion downstream of it).
  (let [sess (external/open!)]
    (try
      (is (pos? (:forms (ops/ingest! sess 'ri.lab
                                     "(ns ri.lab)\n(defn ^:unused-ok probe [] 1)\n"))))
      (ops/module-dep! sess "ri.app" "ri.lab")
      (is (pos? (:forms (ops/ingest! sess 'ri.app
                                     (str "(ns ri.app (:require [ri.lab :as lab]))\n"
                                          "(defn ^:unused-ok go [] (lab/probe))\n"))))
          "the CALLER has to exist for its absence to mean anything")
      (testing "product code requires it → refused, and the refusal NAMES the caller"
        (let [r (ops/module-role! sess "ri.lab" :instrument)]
          (is (:error r) (pr-str r))
          (is (re-find #"ri\.app" (:error r)) (pr-str r))
          (is (= ["ri.app"] (:required-by r)) (pr-str r))
          (is (= {} (:module-roles (:store @sess)))
              "and nothing was recorded")))
      (testing "a TEST requiring it is fine — a test does not ship either"
        (is (pos? (:forms (ops/ingest! sess 'ri.lab-test
                                       (str "(ns ri.lab-test (:require [ri.lab :as lab]\n"
                                            "                          [clojure.test :refer [deftest is]]))\n"
                                            "(deftest probe-t (is (= 1 (lab/probe))))\n")))))
        (ops/edit-replace! sess 'ri.app 'go "(defn ^:unused-ok go [] 1)")
        (ops/remove-require! sess 'ri.app 'ri.lab)
        (let [r (ops/module-role! sess "ri.lab" :instrument)]
          (is (nil? (:error r)) (pr-str r))
          (is (= :instrument (store/role-for (:store @sess) 'ri.lab)))))
      (finally (ops/close! sess)))))

(deftest ^:external a-build-leaves-an-instrument-out-of-src
  ;; R5's second clause, made mechanical. What ships is decided at
  ;; MATERIALIZATION, because the build script copies `src` by name and knows
  ;; nothing about roles — so the role has to move the file, not label it.
  (let [sess (external/open!)
        dir  (str (System/getProperty "java.io.tmpdir")
                  "/slopp-role-" (System/nanoTime))]
    (try
      (is (pos? (:forms (ops/ingest! sess 'rb.core
                                     "(ns rb.core)\n(defn ^:unused-ok f [] 1)\n"))))
      (is (pos? (:forms (ops/ingest! sess 'rb.lab
                                     "(ns rb.lab)\n(defn ^:unused-ok -main [] 1)\n"))))
      (is (nil? (:error (ops/module-role! sess "rb.lab" :instrument))))
      (let [r (external/build! sess dir)]
        (is (nil? (:error r)) (pr-str r)))
      (testing "product code is under src/, exactly as before"
        (is (.exists (clojure.java.io/file dir "src" "rb" "core.clj"))))
      (testing "the instrument is materialized — but NOT under src/"
        ;; both halves: absent-from-src is also what a namespace that failed
        ;; to materialize at all would look like
        (is (.exists (clojure.java.io/file dir "instruments" "rb" "lab.clj"))
            "the code still exists — an instrument is materialized, not dropped")
        (is (not (.exists (clojure.java.io/file dir "src" "rb" "lab.clj")))
            "and a build that copies src/ therefore cannot pick it up"))
      (testing "the generated deps.edn declares the path that RUNS it"
        (is (= ["src" "instruments"]
               (:paths (clojure.edn/read-string
                        (slurp (clojure.java.io/file dir "deps.edn")))))))
      (finally
        (ops/close! sess)
        (doseq [f (reverse (file-seq (clojure.java.io/file dir)))] (.delete f))))))

(deftest fn-arglists-is-total-so-no-caller-owes-it-a-guard
  ;; `:sig` has now had FOUR producers read a `def`'s VALUE as a parameter
  ;; list. The reason it keeps happening is in the call graph rather than in
  ;; any one of them: `fn-arglists` drops the first two elements of whatever it
  ;; is handed and looks for a vector, so `(def geometry [1 2 3])` answers
  ;; `[[1 2 3]]` — a plausible one-arg signature that does not throw. Its
  ;; docstring says it takes a `defn` sexpr, which makes the check every
  ;; caller's job: NINE production callers read it and TWO check.
  ;;
  ;; A guard owed by everyone and paid by two is not a discipline problem, it
  ;; is a totality problem. So the function answers for every form, and the
  ;; answer for a form with no arities is nil.
  ;;
  ;; What made it structural rather than careless: `slopp.api.reads/form-shape`
  ;; asserted in its DOCSTRING that fn-arglists "knows a `def` has no arities".
  ;; It did not — the head guard one line below the claim did. A reader
  ;; believed the docstring, called the function unguarded, and shipped the
  ;; fourth producer. A docstring asserting a property of another function is a
  ;; parity comment: nothing runs it, and it actively licenses the bug.
  ;;
  ;; `defmethod` stays in the allowed set deliberately, and is NOT fixed here:
  ;; its arg vector sits at a different position, so this answered `[]` for one
  ;; long before today. Preserving that keeps this change about non-fn forms
  ;; only; the defmethod read is its own finding.
  (let [st   (store/ingest
              (store/empty-store) 'fa.core
              (str "(ns fa.core \"D.\")\n"
                   "(defn one \"D.\" [x] x)\n"
                   "(defn many \"D.\" ([x] x) ([x y] y))\n"
                   "(defn- priv \"D.\" [x] x)\n"
                   "(defmacro mac \"D.\" [x] x)\n"
                   "(defn none \"D.\" [] 1)\n"
                   "(def geometry [1 2 3])\n"
                   "(def shaped \"D.\" {:a 1})\n"
                   "(def registry \"D.\" [{:kind :regex} {:kind :string}])\n"
                   "(defmulti area :shape)\n"))
        args (fn [nm]
               (modules/fn-arglists
                (store/form-sexpr (:node (store/form-named st 'fa.core nm)))))]
    (testing "a fn, every arity — unchanged, and the reason this exists"
      (is (= '[[x]] (args 'one)))
      (is (= '[[x] [x y]] (args 'many)))
      (is (= '[[x]] (args 'priv)))
      (is (= '[[x]] (args 'mac)))
      (is (= '[[]] (args 'none))
          "a zero-arity is a real signature and must not read as absent"))

    (testing "a DEF has no arities, whatever its value happens to look like"
      (is (nil? (args 'geometry))
          "the measured bug: a vector VALUE read as a parameter list")
      (is (nil? (args 'shaped)))
      (is (nil? (args 'registry))
          "the live instance — a registry advertising its own contents as a sig"))

    (testing "nor does anything else that does not define a fn"
      (is (nil? (args 'fa.core)) "the ns form")
      (is (nil? (args 'area)) "a defmulti dispatches; it has no arg vector"))))
