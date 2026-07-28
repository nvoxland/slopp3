(ns slopp.modules-test
  "The module system: recursive namespace visibility (depth ≤2 public,
  deeper scoped to the parent subtree), declared cross-module dependency
  edges (default-deny once a `modules` manifest exists), acyclic graph,
  and docstring warnings on the public surface."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.store :as store] [slopp.edit.modules :as modules] [slopp.store.merge :as merge] [slopp.api.external :as external] [slopp.api.query :as query]))

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

(deftest ^:external an-unadopted-populated-store-adopts-on-reopen
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-modules-adopt"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.api/dir dir})]
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
    (let [sess2 (external/open! {:slopp.api/dir dir})]
      (try
        (is (= {"kb.app" #{"ka.core"}}
               (modules/modules-manifest (:store @sess2))))
        (is (nil? (:error (api/edit-replace! sess2 'kb.app 'g
                                             "(defn g \"G.\" [x] (core/f (inc x)))"
                                             :prompt "gated edits work under the adopted manifest"))))
        (finally (api/close! sess2))))))

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
        (api/ingest! sess 'a.x "(ns a.x)\n(defn f \"F.\" [n] n)\n")
        (api/module-dep! sess "b.y" "a.x" :prompt "b calls a")
        (api/ingest! sess 'b.y
                     "(ns b.y (:require [a.x :as x]))\n(defn g \"G.\" [n] (x/f n))\n")
        (api/module-dep! sess "c.z" "b.y" :prompt "c calls b")
        (api/ingest! sess 'c.z
                     "(ns c.z (:require [b.y :as y]))\n(defn h \"H.\" [n] (y/g n))\n")
        (let [r (api/module-dep! sess "d.w" "c.z" :prompt "unrelated — must land")]
          (is (nil? (:error r)) (pr-str r)))
        (let [r (api/module-dep! sess "a.x" "c.z" :prompt "would close a.x→c.z→b.y→a.x")]
          (is (re-find #"(?i)closes a dependency cycle" (str (:error r))) (pr-str r)))
        (let [r (api/module-dep! sess "b.y" "d.w" :prompt "d.w reaches nothing — fine")]
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

(deftest ^:external the-module-lifecycle
  (let [sess (external/open!)]
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
        (let [r (external/done! sess :label "docs review")]
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
        (is (nil? (modules/tier-refusal cand 'app.core 'tick!)))))
    (testing ":pure refuses a form that reaches a mutation, with teaching"
      (let [t (at eff-src :pure)]
        (is (re-find #":pure" (str (modules/tier-refusal t 'app.core 'tick!))))
        (is (re-find #"functional-core"
                     (str (modules/tier-refusal t 'app.core 'tick!))))))
    (testing ":pure allows a pure form"
      (is (nil? (modules/tier-refusal (at pure-src :pure) 'app.core 'add))))
    (testing ":external is unrestricted"
      (is (nil? (modules/tier-refusal (at eff-src :external) 'app.core 'tick!))))
    (testing ":internal ALLOWS in-process mutation — a memo is not an effect
              on the world, and treating it as one is what put a memoized
              projection in the same class as a subprocess spawn"
      (is (nil? (modules/tier-refusal (at eff-src :internal) 'app.core 'tick!))))
    (testing ":internal REFUSES what leaves the process"
      (let [msg (str (modules/tier-refusal (at io-src :internal) 'app.core 'grab!))]
        (is (re-find #":internal" msg) msg)
        (is (re-find #"(?i)outside this process" msg) msg)))
    (testing "legacy spellings still resolve: :reads => :internal, :effects => :external"
      (is (nil? (modules/tier-refusal (at eff-src :reads) 'app.core 'tick!)))
      (is (nil? (modules/tier-refusal (at eff-src :effects) 'app.core 'tick!))))))

(deftest ^:external module-purity-verb
  (let [sess (external/open!)]
    (try
      (testing "declares a tier, folded onto the store"
        (let [r (api/module-tier! sess "app.core" :pure :prompt "keep core pure")]
          (is (= :pure (:tier r)))
          (is (= "app.core" (:module r)))
          (is (= :pure (get-in @sess [:store :module-tiers "app.core"])))))
      (testing "rejects a bogus tier"
        (is (:error (api/module-tier! sess "app.core" :bogus))))
      (testing "rejects a non-module string"
        ;; a DEEP namespace is now legal — a pure core routinely lives below an
      ;; effectful module, and the tier exists to make agents move code into
      ;; that shape, which it cannot do if it cannot name it
      (is (nil? (:error (api/module-tier! sess "app.core.impl" :pure))))
      (is (:error (api/module-tier! sess "has spaces" :pure))))
      (finally (api/close! sess)))))

(deftest ^:external purity-gate-refuses-effectful-writes
  (let [sess (external/open!)]
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

(deftest ^:external schema-require-gate-refuses-boundary-writes
  (let [sess (external/open!)]
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

(deftest ^:external namespaced-keys-gate-refuses-boundary-writes
  (let [sess (external/open!)]
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

(deftest ^:external advisory-write-gate-warns-but-proceeds
  (let [sess (external/open!)]
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

(deftest ^:external purity-gate-exempts-test-namespaces
  ;; A test namespace belongs to its module (x.y-test → x.y), so declaring a
  ;; module :pure was silently making its TESTS unwritable — they set up
  ;; sessions and exercise effects by design, which is the whole job. The tier
  ;; is a claim about the functional CORE, not about the code that drives it.
  ;; Found by cleanup {all true} on slopp's own store, where declaring
  ;; slopp.normalize :pure had already stranded slopp.normalize-test.
  (let [sess (external/open!)]
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

(deftest ^:external foreign-keys-marks-a-third-party-map-and-polices-itself
  ;; require-namespaced-keys cannot be satisfied by a fn that destructures
  ;; SOMEONE ELSE'S map — slopp.build/arg-style takes clj-kondo's analysis, and
  ;; we do not get to rename kondo's keys. ^:foreign-keys records that, and
  ;; polices itself like ^:ambient-ok / ^:unused-ok: a marker on a fn that has
  ;; no bare boundary keys is itself refused, so it cannot decay into a blanket
  ;; opt-out someone sprinkles to silence the gate.
  (let [sess (external/open!)]
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
    (doseq [g modules/per-form-write-gates]
      (is (contains? #{:all :production} (:rule/applies-to (meta g) :all))
          (str (:name (meta g)) " must declare :rule/applies-to :all or"
               " :production — leaving it implicit is how two surfaces"
               " disagreed about tests"))))
  (testing "the purity gate is production-only, and says so in one place"
    (is (= :production (:rule/applies-to (meta #'modules/tier-refusal)))))
  (testing "and the report agrees with the gate by construction"
    (let [sess (external/open!)]
      (try
        (api/ingest! sess 'ra.core "(ns ra.core)\n(defn add [x y] (+ x y))\n")
        (api/ingest! sess 'ra.core-test "(ns ra.core-test)\n(defn setup! [f] (slurp f))\n")
        (is (= :pure (:supports (modules/tier-report (:store @sess) 'ra.core)))
            "the effectful TEST namespace must not veto the module's tier")
        (finally (api/close! sess))))))

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
      (api/config-file! sess "gates" :key "require-namespaced-keys" :value "true")
      (api/ingest! sess 'ks.core "(ns ks.core)\n")
      (testing "IN scope: a module-external defn destructuring bare :keys"
        (is (re-find #"namespaced"
                     (str (:error (api/add-form! sess 'ks.core
                                                 "(defn boundary [{:keys [id]}] id)"
                                                 :prompt "in scope"))))))
      (testing "OUT of scope: a PRIVATE fn — the module can see its own producer"
        (is (nil? (:error (api/add-form! sess 'ks.core
                                         "(defn- internal [{:keys [id]}] id)"
                                         :prompt "private")))))
      (testing "OUT of scope: keys read in the BODY, not destructured in the arglist"
        (is (nil? (:error (api/add-form! sess 'ks.core
                                         "(defn reads-body [m] (:id m))"
                                         :prompt "body read")))))
      (testing "OUT of scope: a RETURN map's keys"
        (is (nil? (:error (api/add-form! sess 'ks.core
                                         "(defn returns [] {:id 1 :error nil})"
                                         :prompt "return map")))))
      (testing "OUT of scope: a non-map argument"
        (is (nil? (:error (api/add-form! sess 'ks.core
                                         "(defn plain [id] id)"
                                         :prompt "no map arg")))))
      (finally (api/close! sess)))))

(deftest purity-gate-sees-console-io-and-watch-mutation
  (let [at (fn [src tier]
             (first (store/record-module-tier
                     (store/ingest (store/empty-store) 'app.core src)
                     "app.core" tier)))]
    (testing ":pure refuses a form that reaches console IO (println is an effect)"
      (let [t (at "(ns app.core)\n\n(defn shout \"S.\" [x] (println x))\n" :pure)]
        (is (modules/tier-refusal t 'app.core 'shout)
            "println breaks referential transparency but entered :pure")))
    (testing ":pure refuses a form that reaches add-watch (registry mutation)"
      (let [t (at "(ns app.core)\n\n(defn spy \"S.\" [a] (add-watch a :k identity))\n" :pure)]
        (is (modules/tier-refusal t 'app.core 'spy)
            "add-watch mutates the watch registry but entered :pure")))
    (testing ":internal STILL allows println (in-process, capturable)"
      (let [tp (at "(ns app.core)\n\n(defn shout \"S.\" [x] (println x))\n" :internal)]
        (is (nil? (modules/tier-refusal tp 'app.core 'shout)))))))

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
      (let [v (modules/layering-violations st 'app.core :pure)]
        (is (some #(= 'app.io (:requires %)) v)
            (str "late-ref into the shell slipped past layering: " (pr-str v)))))
    (testing "a late-ref BETWEEN external namespaces is fine (both shell)"
      (let [st2 (first (store/record-module-tier st "app.core" :external))]
        (is (empty? (modules/layering-violations st2 'app.core :external)))))))

(deftest ^:external module-platforms-surface-in-query-depends
  (let [sess (external/open!)]
    (try
      (api/create-ns! sess 'plat.client :source "(ns plat.client)\n"
                      :platform :cljs :prompt "browser")
      (api/create-ns! sess 'plat.shared :source "(ns plat.shared)\n"
                      :platform :cljc :prompt "portable")
      (api/create-ns! sess 'plat.server :source "(ns plat.server)\n(defn ^:unused-ok f [] 1)\n")
      (let [r (query/query-depends sess "" :modules true)]
        (testing "declared platforms surface in the module graph"
          (is (= :cljs (get (:platforms r) "plat.client")) (pr-str (:platforms r)))
          (is (= :cljc (get (:platforms r) "plat.shared")) (pr-str (:platforms r))))
        (testing "an undeclared ns (= :jvm default) is absent, not noise"
          (is (nil? (get (:platforms r) "plat.server")) (pr-str (:platforms r)))))
      (finally (api/close! sess)))))

(deftest ^:external module-platform-verb
  (let [sess (external/open!)]
    (try
      (testing "declares a platform, folded onto the store"
        (let [r (api/module-platform! sess "app.client" :cljs :prompt "browser code")]
          (is (= :cljs (:platform r)))
          (is (= "app.client" (:module r)))
          (is (= :cljs (get-in @sess [:store :module-platforms "app.client"])))))
      (testing "accepts a string spelling (MCP/JSON carries no keyword)"
        (is (= :cljc (:platform (api/module-platform! sess "app.shared" ":cljc")))))
      (testing "defaults nil to :jvm"
        (is (= :jvm (:platform (api/module-platform! sess "app.server" nil)))))
      (testing "rejects an unknown platform"
        (is (:error (api/module-platform! sess "app.client" :wasm))))
      (testing "rejects a non-module string"
        (is (:error (api/module-platform! sess "has spaces" :cljs))))
      (finally (api/close! sess)))))

(deftest rule-applies-to-platform?-scopes-by-target
  (testing ":everywhere fires on every platform"
    (is (modules/rule-applies-to-platform? :everywhere :jvm))
    (is (modules/rule-applies-to-platform? :everywhere :cljs))
    (is (modules/rule-applies-to-platform? :everywhere :cljc)))
  (testing ":clojure fires on :jvm and :cljc, not :cljs"
    (is (modules/rule-applies-to-platform? :clojure :jvm))
    (is (modules/rule-applies-to-platform? :clojure :cljc))
    (is (not (modules/rule-applies-to-platform? :clojure :cljs))))
  (testing ":clojurescript fires on :cljs and :cljc, not :jvm"
    (is (modules/rule-applies-to-platform? :clojurescript :cljs))
    (is (modules/rule-applies-to-platform? :clojurescript :cljc))
    (is (not (modules/rule-applies-to-platform? :clojurescript :jvm))))
  (testing "the load-bearing case: a :cljc form is checked by BOTH worlds"
    (is (modules/rule-applies-to-platform? :clojure :cljc))
    (is (modules/rule-applies-to-platform? :clojurescript :cljc))))

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
    (with-redefs [modules/per-form-write-gates [#'fixture-advisory-gate
                                                #'fixture-refuse-gate-a]]
      (let [gc (modules/gate-check t 'app.core 'f)]
        (testing "the gate's own :rule/severity is the default — no dial needed"
          (is (= ["fixture-advisory teaching"] (:advisories gc))))
        (testing "an undeclared gate still defaults to :refuse"
          (is (= "fixture-refuse-a teaching" (:refuse gc))))))
    (testing "a per-store dial still WINS over the declared default"
      (let [up (first (store/record-config-put
                       t "rules" :manifest "fixture-advisory-gate" "refuse"))]
        (with-redefs [modules/per-form-write-gates [#'fixture-advisory-gate]]
          (is (= "fixture-advisory teaching"
                 (:refuse (modules/gate-check up 'app.core 'f)))))))))

(deftest stacked-gates-teach-every-refusal-at-once
  (let [t (store/ingest (store/empty-store) 'app.core
                        "(ns app.core)\n\n(defn f \"D.\" [x] x)\n")]
    (with-redefs [modules/per-form-write-gates [#'fixture-refuse-gate-a
                                                #'fixture-refuse-gate-b]]
      (testing "gate-check keeps EVERY refuse-grade teaching, not just the first"
        (is (= ["fixture-refuse-a teaching" "fixture-refuse-b teaching"]
               (:refusals (modules/gate-check t 'app.core 'f)))))
      (testing ":refuse stays the first teaching (the existing shape)"
        (is (= "fixture-refuse-a teaching"
               (:refuse (modules/gate-check t 'app.core 'f)))))
      (testing "the blocking message carries the others so ONE resend satisfies both"
        (let [msg (modules/gate-refusal t 'app.core 'f)]
          (is (re-find #"fixture-refuse-a teaching" msg))
          (is (re-find #"fixture-refuse-b teaching" msg))
          (is (re-find #"(?i)also pending" msg)))))
    (testing "a lone refusal reads exactly as before — no 'also pending' noise"
      (with-redefs [modules/per-form-write-gates [#'fixture-refuse-gate-a]]
        (is (= "fixture-refuse-a teaching" (modules/gate-refusal t 'app.core 'f)))))))

(deftest ^:external module-extract-lands-exports-renames-and-edges-as-one-intent
  ;; The regroup a component restructure repeats per component. me.helper goes
  ;; DEEP under me.core, so me.other loses visibility of everything it calls
  ;; there. Order is the whole design: exports must land BEFORE the rename, or
  ;; the intermediate store is one the module gate refuses.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'me.helper
                   (str "(ns me.helper)\n"
                        "(defn shared \"Reached from outside.\" [x] x)\n"
                        "(defn local \"Reached only from me.core.\" [x] x)\n"))
      (api/module-dep! sess "me.core" "me.helper" :prompt "core uses helper")
      (api/module-dep! sess "me.other" "me.helper" :prompt "other uses helper")
      (api/ingest! sess 'me.core
                   (str "(ns me.core (:require [me.helper :as h]))\n"
                        "(defn a \"A.\" [x] (h/local x))\n"))
      (api/ingest! sess 'me.other
                   (str "(ns me.other (:require [me.helper :as h]))\n"
                        "(defn b \"B.\" [x] (h/shared x))\n"))
      (let [r (api/module-extract! sess '[me.helper] 'me.core
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
          (is (nil? (:error (api/edit-replace!
                             sess 'me.other 'b
                             "(defn b \"B.\" [x] (h/shared (inc x)))"
                             :prompt "a write still passes the gate afterwards")))))) 
      (finally (api/close! sess)))))

(deftest ^:external namespace-grained-registers-follow-an-ns-rename
  ;; The manifest already follows a rename. The purity TIER and the PLATFORM
  ;; are the other two registers keyed by namespace, and they did not — so
  ;; extracting slopp.store left "slopp.render" :internal declared for a
  ;; namespace that no longer exists while slopp.store.render, which holds
  ;; the actual code, had no tier at all. Silent un-gating, plus a view that
  ;; lists ghosts.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'tr.core "(ns tr.core)\n(defn f \"F.\" [x] x)\n")
      (api/module-tier! sess "tr.core" :pure :prompt "a pure core")
      (api/module-platform! sess "tr.core" "cljc" :prompt "shared with the client")
      (is (= :pure (get-in @sess [:store :module-tiers "tr.core"])))
      (is (nil? (:error (api/ns-rename! sess 'tr.core 'tr.hub :prompt "rebrand"))))
      (testing "the tier follows the name it describes"
        (is (= :pure (get-in @sess [:store :module-tiers "tr.hub"])))
        (is (nil? (get-in @sess [:store :module-tiers "tr.core"]))
            "and the old key does not linger as a ghost"))
      (testing "so does the platform"
        (is (= :cljc (get-in @sess [:store :module-platforms "tr.hub"])))
        (is (nil? (get-in @sess [:store :module-platforms "tr.core"]))))
      (finally (api/close! sess)))))

(deftest the-web-framework-never-reaches-back-into-slopp
  ;; slopp.web.* is the FRAMEWORK slopp ships to users: build.clj's slim
  ;; io.github.nvoxland/slopp-web jar is exactly slopp/web.clj + slopp/web/**.
  ;; slopp.review.* is slopp's OWN webapp built on that framework — a peer of any
  ;; user's app. The dependency runs ui -> web and NEVER the reverse, because a
  ;; framework namespace that reaches back into slopp's core makes that jar
  ;; unloadable, and it breaks at the USER's require time rather than ours.
  ;;
  ;; Asserted rather than remembered: the invariant is one :require line away
  ;; from being false, added for a perfectly good local reason, with nothing
  ;; else to complain. The module gate states the same rule from the other side
  ;; (slopp.web declares no outgoing edges and sits at layer 0); this catches a
  ;; require that never became a declared edge.
  (let [framework '[slopp.web slopp.web.auth slopp.web.css slopp.web.dispatch
                    slopp.web.html slopp.web.router slopp.web.routes
                    slopp.web.static slopp.web.server.httpkit
                    slopp.web.server.jdk]
        web?      #(boolean (re-matches #"slopp\.web(\..*)?" (str %)))]
    (run! require framework)
    (let [loaded (keep find-ns framework)
          leaks  (for [n loaded
                       [_ dep] (ns-aliases n)
                       :let [d (ns-name dep)]
                       :when (and (re-find #"^slopp\." (str d)) (not (web? d)))]
                   [(ns-name n) d])]
      ;; guard the guard: over an empty set "no leaks" is vacuously true, which
      ;; is exactly the "I could not check" / "I checked and found nothing"
      ;; conflation this codebase refuses everywhere else.
      (is (= (count framework) (count loaded))
          (str "every framework namespace must be loaded before it can be "
               "checked; missing "
               (vec (remove find-ns framework))))
      (is (empty? leaks)
          (str "slopp.web.* must not depend on anything outside slopp.web.* — "
               "the slim slopp-web jar ships only slopp/web/**, so these "
               "requires would not resolve in a user's project: "
               (vec leaks))))))

(deftest ^:external cycle-refusal-judges-production-edges-not-test-fixtures
  ;; A `-test` namespace folds into its subject's module, so a fixture
  ;; require manufactures a module edge that no production namespace has.
  ;; production-manifest already excludes those — "excluding them tells the
  ;; truth", its own docstring says — and query_depends reports layers from
  ;; that graph. The cycle GATE was asking the declared graph instead, so
  ;; the two surfaces disagreed: the architecture view showed a clean DAG
  ;; while the gate refused an edge for closing a cycle only a test made.
  ;;
  ;; Found in anger: slopp.mcp → slopp.review was refused for closing
  ;; ui → api → index → mcp → ui, where index → mcp exists only because
  ;; slopp.index.deps-test calls slopp.mcp/handle!.
  (let [sess (external/open!)]
    (try
      (api/ingest! sess 'mp.core "(ns mp.core)\n(defn shared \"P.\" [x] x)\n")
      (api/ingest! sess 'mr.tool "(ns mr.tool)\n(defn helper \"H.\" [x] x)\n")
      (api/module-dep! sess "mq.app" "mp.core" :prompt "production: app calls core")
      (api/ingest! sess 'mq.app
                   (str "(ns mq.app (:require [mp.core :as core]))\n"
                        "(defn use-it \"Uses mp.\" [x] (core/shared x))\n"))
      (api/module-dep! sess "mp.core" "mr.tool" :prompt "a TEST fixture reaches for a tool")
      (api/ingest! sess 'mp.core-test
                   (str "(ns mp.core-test (:require [clojure.test :refer [deftest is]]\n"
                        "                            [mr.tool :as tool]))\n"
                        "(deftest fixture-uses-the-tool (is (= 1 (tool/helper 1))))\n"))
      (testing "the manufactured edge is in the DECLARED manifest"
        (is (contains? (get (modules/modules-manifest (:store @sess)) "mp.core") "mr.tool")))
      (testing "an edge blocked only by a test-manufactured path is allowed"
        (let [r (api/module-dep! sess "mr.tool" "mq.app"
                                 :prompt "acyclic in production: mq→mp, and mr is a leaf")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "a genuine production cycle is still refused"
        (let [r (api/module-dep! sess "mp.core" "mq.app"
                                 :prompt "production mq.app → mp.core already exists")]
          (is (re-find #"(?i)closes a dependency cycle" (str (:error r))) (pr-str r))))
      (finally (api/close! sess)))))

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
        (let [r (api/module-tier! sess "app.core" :pure)]
          (is (= [:forms] (:verified r)))
          (is (= [:layering] (:unverified r)))
          (is (re-find #"full_check" (str (:note r)))
              "the note must name where layering IS checked")))
      (testing "an :external tier asserts nothing, so it verified nothing"
        ;; tier-violations returns [] immediately for :external — the claim is
        ;; empty, and a verb that reported [:forms] here would be claiming a
        ;; check it skipped by definition.
        (let [r (api/module-tier! sess "app.shell" :external)]
          (is (= [] (:verified r)))))
      (testing "module_platform verifies nothing about the code at all"
        (let [r (api/module-platform! sess "app.client" :cljs)]
          (is (= [] (:verified r)))
          (is (= [:compilation] (:unverified r)))
          (is (re-find #"compile_client" (str (:note r))))))
      (testing "module_dep verified cycles over PRODUCTION edges only"
        (let [r (api/module-dep! sess "app.core" "app.util")]
          (is (= [:cycles] (:verified r)))
          (is (= [:usage] (:unverified r)))))
      (testing "a refusal carries no axes — it is not a partial answer"
        (let [r (api/module-tier! sess "has spaces" :pure)]
          (is (:error r))
          (is (nil? (:verified r)))
          (is (nil? (:unverified r)))))
      (finally (api/close! sess)))))

(deftest tier-report-reads-the-GOVERNING-tier-not-the-modules
  ;; `tier-for` is THE producer of "which tier governs this namespace": most
  ;; specific declaration wins, namespace grain, because a pure core routinely
  ;; lives one level below an effectful module. `tier-report` answered the same
  ;; question a second way — `(get (:module-tiers store) (module-of ns))` — and
  ;; the two disagreed on 28 of slopp's own 75 production namespaces.
  ;;
  ;; The dangerous direction is the one measured on `slopp.store.mine`, whose
  ;; own `:external` declaration exists BECAUSE a fold silently governed it
  ;; `:pure` (frictions #11): the report said `:pure` anyway. A migration aid
  ;; that misreports where the code stands is worse than none.
  (let [st (-> (store/empty-store)
               (store/ingest 'tg.core "(ns tg.core)\n(defn f \"F.\" [] 1)\n")
               (store/ingest 'tg.core.deep "(ns tg.core.deep)\n(defn g \"G.\" [] 2)\n")
               (as-> s (first (store/record-module-tier s "tg.core" :external)))
               (as-> s (first (store/record-module-tier s "tg.core.deep" :pure))))]
    (is (= :pure (modules/tier-for st 'tg.core.deep))
        "the deep namespace's own declaration is the most specific")
    (is (= :pure (:tier (modules/tier-report st 'tg.core.deep)))
        "the report must name the tier that GOVERNS, from the one producer")
    (is (= :external (:tier (modules/tier-report st 'tg.core)))
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
        (api/module-tier! sess "rm.core" :pure :prompt "core is pure")
        (is (= :pure (get-in @sess [:store :module-tiers "rm.core"])))
        (let [r (api/module-tier! sess "rm.core" nil :remove true :prompt "not any more")]
          (is (nil? (:error r)) (pr-str r))
          (is (= :removed (:action r)) (pr-str r))
          (is (nil? (get-in @sess [:store :module-tiers "rm.core"]))
              "retired, not overwritten with a looser tier")))
      (testing "retiring what was never declared is an ERROR, not a silent no-op"
        (is (:error (api/module-tier! sess "rm.absent" nil :remove true))))
      (testing "the same for a platform"
        (api/module-platform! sess "rm.ui" :cljs :prompt "browser code")
        (let [r (api/module-platform! sess "rm.ui" nil :remove true :prompt "back to jvm")]
          (is (nil? (:error r)) (pr-str r))
          (is (= :removed (:action r)))
          (is (nil? (get-in @sess [:store :module-platforms "rm.ui"]))))
        (is (:error (api/module-platform! sess "rm.absent" nil :remove true))))
      (finally (api/close! sess)))))

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
      (api/ingest! sess 'mx.one "(ns mx.one)\n(defn a \"A.\" [] 1)\n")
      (api/ingest! sess 'mx.two "(ns mx.two)\n(defn b \"B.\" [] 2)\n")
      (let [verifies #(count (filter (fn [d] (= :verify (:op d)))
                                     (store/deltas (:store @sess))))
            before   (verifies)
            r        (api/module-extract! sess ['mx.one 'mx.two] "mx.core"
                                          :prompt "regroup under one prefix")]
        (is (nil? (:error r)) (pr-str r))
        (is (= 2 (count (:renames (:extracted r)))) (pr-str r))
        (is (= 1 (- (verifies) before))
            "one transaction, ONE verification — not one per rename"))
      (finally (api/close! sess)))))
