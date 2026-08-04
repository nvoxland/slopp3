(ns slopp.surgeon-test
  "clj-surgeon-inspired structural ops, slopp-grade: gated, verified,
  recorded. query_deps / fix_declares / ns_rename / edit_move_forms."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops] [slopp.read.query :as query] [slopp.ops.external :as external] [slopp.read.graph :as graph]))

(def core-src
  (str "(ns sg.core (:require [clojure.test :refer [deftest is]]))\n"
       "(defn leaf [x] (* 2 x))\n"
       "(defn mid [x] (leaf x))\n"
       "(defn top [x] (mid (mid x)))\n"
       "(deftest top-t (is (= 8 (top 2))))\n"))

(def util-src
  (str "(ns sg.util (:require [sg.core :as c]\n"
       "                      [clojure.test :refer [deftest is]]))\n"
       "(defn wrap [x] (c/top x))\n"
       "(deftest wrap-t (is (= 8 (wrap 2))))\n"))

(deftest ^:external query-deps-transitive-callee-tree
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'sg.core core-src)
      (ops/module-dep! sess "sg.util" "sg.core" :prompt "fixture edge")
      (ops/ingest! sess 'sg.util util-src)
      (let [d (graph/query-deps sess 'sg.util 'wrap)]
        (is (= 'sg.util/wrap (:root d)))
        (is (= ['sg.core/top] (get (:calls d) 'sg.util/wrap)))
        (is (= ['sg.core/mid] (get (:calls d) 'sg.core/top)))
        (is (= ['sg.core/leaf] (get (:calls d) 'sg.core/mid)))
        (is (= [] (get (:calls d) 'sg.core/leaf))))
      (finally (ops/close! sess)))))

(deftest ^:external fix-declares-moves-and-deletes
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'fd.core
                   (str "(ns fd.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(declare helper)\n"
                        "(defn caller [x] (helper x))\n"
                        "(defn helper [x] (+ x 1))\n"
                        "(deftest caller-t (is (= 3 (caller 2))))\n"))
      (let [r (ops/fix-declares! sess 'fd.core :agent "tidier")]
        (is (= 1 (:removed r)) (pr-str r))
        (is (zero? (+ (:fail (:test r)) (:error (:test r)))))
        (let [src (query/query-source sess 'fd.core)]
          (is (not (re-find #"declare" src)))
          (is (< (.indexOf src "defn helper") (.indexOf src "defn caller")))))
      (testing "mutual recursion: the hand-written declare MIGRATES to a pipeline-owned marked one"
        (ops/ingest! sess 'fd.rec
                     (str "(ns fd.rec)\n"
                          "(declare odd-x)\n"
                          "(defn even-x [n] (if (zero? n) true (odd-x (dec n))))\n"
                          "(defn odd-x [n] (if (zero? n) false (even-x (dec n))))\n"))
        (let [r (ops/fix-declares! sess 'fd.rec)]
          (is (nil? (:error r)) (pr-str r))
          (let [src (query/query-source sess 'fd.rec)]
            (is (re-find #"\(declare" src) "a real cycle still needs a declare")
            (is (re-find #":auto-declare" src)
                "but it is the PIPELINE's now, and it says why"))))
      (finally (ops/close! sess)))))

(deftest ^:external ns-rename-rewrites-the-world
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'sg.core core-src)
      (ops/module-dep! sess "sg.util" "sg.core" :prompt "fixture edge")
      (ops/ingest! sess 'sg.util util-src)
      ;; a fully-qualified reference too
      (ops/add-form! sess 'sg.util "(defn fq [x] (sg.core/leaf x))")
      (let [r (ops/ns-rename! sess 'sg.core 'sg.central :agent "renamer")]
        (is (nil? (:error r)) (pr-str r))
        (is (zero? (+ (:fail (:test r)) (:error (:test r)))))
        (testing "old namespace is GONE, new one answers"
          (is (nil? (get-in @sess [:store :namespaces 'sg.core])))
          (is (= [8] (ops/query-eval sess "(sg.central/top 2)"))))
        (testing "requires and FQ refs across the store were rewritten"
          (let [u (query/query-source sess 'sg.util)]
            (is (re-find #"\[sg\.central :as c\]" u))
            (is (re-find #"sg\.central/leaf" u))
            (is (not (re-find #"sg\.core" u)))))
        (testing "still verified end-to-end in the image"
          (is (= [8] (ops/query-eval sess "(sg.util/wrap 2)")))))
      (finally (ops/close! sess)))))

(deftest ^:external extract-forms-to-a-new-namespace
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'sg.core core-src)
      (testing "guard: moved forms may not call what stays behind"
        (let [r (ops/move-forms! sess 'sg.core '[top] 'sg.top)]
          (is (re-find #"mid" (:error r)))))
      (let [r (ops/move-forms! sess 'sg.core '[leaf mid] 'sg.calc
                               :prompt "split the pure core" :agent "alice")]
        (is (nil? (:error r)) (pr-str r))
        (is (zero? (+ (:fail (:test r)) (:error (:test r)))))
        (testing "the new namespace holds the moved forms"
          (let [src (query/query-source sess 'sg.calc)]
            (is (re-find #"defn leaf" src))
            (is (re-find #"defn mid" src))))
        (testing "the source ns requires the new one; callers are REWRITTEN"
          (let [src (query/query-source sess 'sg.core)]
            (is (re-find #"\[sg\.calc :as calc\]" src))
            (is (re-find #"calc/mid" src))
            (is (not (re-find #"defn leaf" src)))))
        (testing "behavior intact in the live image"
          (is (= [8] (ops/query-eval sess "(sg.core/top 2)")))))
      (finally (ops/close! sess)))))

(deftest ^:external extract-into-a-deep-child-namespace
  ;; the slopp.api split shape: internal helpers move into a PACKAGE-PRIVATE
  ;; deep child ns, and the parent requires its own child back. Regression
  ;; for the live FileNotFound (the new store-only ns must be loadable when
  ;; the parent's ns form re-evaluates).
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'xr.core
                   (str "(ns xr.core)\n\n"
                        "(def ^:private factor \"F.\" 2)\n\n"
                        "(defn- helper-a \"A.\" [x] (inc x))\n\n"
                        "(defn- helper-b \"B.\" [x] (* factor (helper-a x)))\n\n"
                        "(defn entry \"E.\" [x] (+ factor (helper-b x)))\n"))
      (let [r (ops/move-forms! sess 'xr.core '[factor helper-a helper-b] 'xr.core.impl
                               :prompt "package-private helpers" :agent "alice")]
        (is (nil? (:error r)) (pr-str r))
        (testing "the parent requires the deep child; callers rewritten"
          (let [src (query/query-source sess 'xr.core)]
            (is (re-find #"\[xr\.core\.impl :as impl\]" src))
            (is (re-find #"impl/helper-b" src))))
        (testing "behavior intact in the live image"
          (is (= [8] (ops/query-eval sess "(xr.core/entry 2)"))))
        (testing "the deep boundary holds: a foreign module can't reach impl"
          (let [w (ops/ingest! sess 'zz.probe
                               (str "(ns zz.probe (:require [xr.core.impl :as i]))\n\n"
                                    "(defn steal \"S.\" [x] (i/helper-a x))\n"))]
            (is (:error w) (pr-str w))
            (is (re-find #"package-private" (str (:error w)))))))
      (finally (ops/close! sess)))))

(deftest ^:external move-rewrites-callers-everywhere
  ;; THE v1 gap: in a tested codebase everything has external references, so
  ;; no real cluster was movable. v2 rewrites every caller — production AND
  ;; tests — injects requires, and the export dial covers deep targets.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'mvx.core
                   (str "(ns mvx.core)\n\n"
                        "(defn util \"U.\" [x] (inc x))\n\n"
                        "(defn entry \"E.\" [x] (util x))\n"))
      (ops/module-dep! sess "mvx.app" "mvx.core" :prompt "consumer")
      (ops/ingest! sess 'mvx.app
                   (str "(ns mvx.app (:require [mvx.core :as core]))\n\n"
                        "(defn go \"G.\" [x] (core/util x))\n"))
      (ops/ingest! sess 'mvx.core-test
                   (str "(ns mvx.core-test (:require [mvx.core :as core]\n"
                        "                            [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest util-t (is (= 3 (core/util 2))))\n"))
      (testing "a deep target with foreign callers refuses, teaching the dial"
        (let [r (ops/move-forms! sess 'mvx.core '[util] 'mvx.core.util)]
          (is (re-find #"export" (str (:error r))) (pr-str r))))
      (let [r (ops/move-forms! sess 'mvx.core '[util] 'mvx.core.util
                               :export true :prompt "deep home")]
        (is (nil? (:error r)) (pr-str r))
        (is (= '[mvx.app mvx.core mvx.core-test] (:callers r)) (pr-str r))
        (testing "every caller rewritten, requires injected"
          (is (re-find #"util/util" (query/query-source sess 'mvx.app)))
          (is (re-find #"\[mvx\.core\.util :as util\]"
                       (query/query-source sess 'mvx.core-test))))
        (testing "behavior lives at the new address"
          (is (= [4] (ops/query-eval sess "(mvx.app/go 3)")))
          (is (= [3] (ops/query-eval sess "(mvx.core/entry 2)")))))
      (finally (ops/close! sess)))))

(deftest ^:external move-into-an-existing-namespace
  ;; consolidation: the target already exists — moved forms append, the
  ;; stay-behind caller is rewritten, only missing requires are added.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'mve.a
                   (str "(ns mve.a)\n\n"
                        "(defn f \"F.\" [x] (* 2 x))\n\n"
                        "(defn g \"G.\" [x] (f x))\n"))
      (ops/ingest! sess 'mve.b "(ns mve.b)\n\n(defn spare \"S.\" [x] x)\n")
      (ops/module-dep! sess "mve.a" "mve.b" :prompt "f is moving to b")
      (let [r (ops/move-forms! sess 'mve.a '[f] 'mve.b :prompt "consolidate")]
        (is (nil? (:error r)) (pr-str r))
        (testing "appended to the existing target, caller rewritten"
          (is (re-find #"defn f" (query/query-source sess 'mve.b)))
          (is (re-find #"defn spare" (query/query-source sess 'mve.b))
              "existing content untouched")
          (is (re-find #"b/f" (query/query-source sess 'mve.a))))
        (testing "behavior intact"
          (is (= [6] (ops/query-eval sess "(mve.a/g 3)")))))
      (finally (ops/close! sess)))))

(deftest ^:external fix-declares-prunes-phantom-names
  ;; a declare naming a var NOT defined in this ns (moved away by an earlier
  ;; move-forms) is a PHANTOM: it mints an unbound var, so a typo'd unqualified
  ;; call resolves silently instead of failing loudly. It must never BLOCK
  ;; cleanup of the declare around it.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ph.core
                   (str "(ns ph.core)\n"
                        "(declare helper gone-away)\n"
                        "(defn caller [x] (helper x))\n"
                        "(defn helper [x] (inc x))\n"))
      (let [r (ops/fix-declares! sess 'ph.core :agent "t")]
        (is (nil? (:error r)) (pr-str r))
        (let [src (query/query-source sess 'ph.core)]
          (is (not (re-find #"gone-away" src)) "the phantom name is gone")
          (is (not (re-find #"\(declare" src))
              "helper was reorderable, so the whole declare goes")))
      (finally (ops/close! sess)))))

(deftest ^:external move-forms-leaves-ordering-to-the-pipeline
  ;; the planner used to mint an UNMARKED (declare …) for ANY multi-form move,
  ;; whether or not a forward ref existed. That is the source of phantom-declare
  ;; debt: a later move lifts one of those names out and it becomes a phantom
  ;; (declared here, defined nowhere, minting an unbound var). Ordering is the
  ;; pipeline's job — and moved nodes keep source order, so a valid subsequence
  ;; of a loading ns cannot even have an internal forward ref.
  (let [sess (external/open!)]
    (try
      (testing "an ordinary multi-form move needs NO declare at all"
        (ops/ingest! sess 'mv.core
                     (str "(ns mv.core)\n"
                          "(defn a [] 1)\n"
                          "(defn b [] 2)\n"
                          "(defn keep-me [] (a))\n"))
        (let [r (ops/move-forms! sess 'mv.core ["a" "b"] 'mv.target
                                 :prompt "m" :agent "t")]
          (is (nil? (:error r)) (pr-str r))
          (let [src (query/query-source sess 'mv.target)]
            (is (not (re-find #"\(declare" src))
                (str "a and b never reference each other:\n" src)))))

      (testing "a moved CYCLE gets the pipeline's own MARKED declare"
        (ops/ingest! sess 'mv.rec
                     (str "(ns mv.rec)\n"
                          "(declare pong)\n"
                          "(defn ping [n] (pong n))\n"
                          "(defn pong [n] (ping n))\n"))
        (let [r (ops/move-forms! sess 'mv.rec ["ping" "pong"] 'mv.rectarget
                                 :prompt "m" :agent "t")]
          (is (nil? (:error r)) (pr-str r))
          (let [src (query/query-source sess 'mv.rectarget)]
            (is (re-find #"\(declare" src) "a real cycle still needs one")
            (is (re-find #":auto-declare" src)
                (str "and it must be the PIPELINE's, saying why:\n" src)))))
      (finally (ops/close! sess)))))

(deftest ^:external cleanup-runs-the-done-points-tidy-on-demand
  ;; The tidy the done-point applies, callable for one namespace. Code written
  ;; through slopp never needs it — the pipeline owns ordering and declares
  ;; from the first write — but INGESTED code predates those invariants, and
  ;; a legacy declare is otherwise unaddressable (two elements share a name,
  ;; so the name-addressed edit tools cannot reach it).
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'cu.core
                   (str "(ns cu.core)\n\n"
                        "(declare b)\n\n"
                        "(defn a [] (b))\n\n"
                        "(defn b [] 2)\n"))
      (let [r (ops/cleanup! sess 'cu.core :agent "t" :prompt "tidy the import")]
        (is (nil? (:error r)) (pr-str r))
        (is (= 1 (:declares r)) "the legacy declare is retired"))
      (let [src (query/query-source sess 'cu.core)]
        (is (not (re-find #"declare" src)))
        (is (< (.indexOf src "(defn b") (.indexOf src "(defn a"))
            "the definition is reordered above its caller instead"))
      (testing "it is idempotent — a tidy namespace reports no work"
        (let [r (ops/cleanup! sess 'cu.core :agent "t")]
          (is (nil? (:error r)) (pr-str r))
          (is (zero? (:declares r)))
          (is (zero? (:normalized r)))))
      (testing "the code still runs"
        (is (= [2] (ops/query-eval sess "(cu.core/a)"))))
      (finally (ops/close! sess)))))

(deftest ^:external cleanup-reports-what-purity-tier-a-namespace-could-support
  ;; Declaring a tier was BLIND: module_purity accepts any tier and the gate
  ;; only bites on the NEXT write, so a wrong call lands on whoever edits next
  ;; rather than on whoever made it. cleanup reports the standing position
  ;; instead — apply what is mechanical, report what is not. A migration aid:
  ;; the end state is these violations being refused at write time.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'pu.core
                   (str "(ns pu.core)\n\n"
                        "(defn add [x y] (+ x y))\n"))
      (testing "a clean namespace could support :pure"
        (let [r (ops/cleanup! sess 'pu.core)]
          (is (= :pure (get-in r [:purity :supports])) (pr-str r))
          (is (empty? (get-in r [:purity :blocking])) (pr-str r))))
      (ops/add-form! sess 'pu.core "(defn roll [] (rand))"
                     :prompt "non-determinism")
      (testing "non-determinism blocks :pure, and the blocker is named"
        (let [r (ops/cleanup! sess 'pu.core)]
          (is (= :internal (get-in r [:purity :supports])) (pr-str r))
          (is (= '[pu.core/roll] (get-in r [:purity :blocking :pure]))
              (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external cleanup-reports-done-advisories-for-the-whole-namespace
  ;; Done-time advisories run over the forms of an EPISODE, so they already
  ;; fired for anything written through slopp since the rule existed. What they
  ;; have never seen is code that PREDATES the rule — ingested code, or forms
  ;; written before the advisory was added. That is a migration concern, and
  ;; cleanup is where migration concerns live: apply what is mechanical, report
  ;; what is not. Reports the whole namespace, regardless of what was touched.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'adv.core
                   (str "(ns adv.core)\n\n"
                        "(def cache (atom {}))\n\n"
                        "(defn lookup [k] (get @cache k))\n"))
      (let [r (ops/cleanup! sess 'adv.core :prompt "survey ingested code")]
        (is (nil? (:error r)) (pr-str r))
        (is (= '[adv.core/cache]
               (mapv :form (get-in r [:advisories :ambient-state])))
            (str "a global atom that no episode ever touched: "
                 (pr-str (:advisories r)))))
      (testing "a clean namespace reports no advisories"
        ;; the ns docstring is part of being CLEAN now: a namespace that never
        ;; says what it is for is an advisory, so a fixture claiming zero
        ;; advisories has to state a purpose like any other namespace
        (ops/ingest! sess 'adv.clean
                     (str "(ns adv.clean\n"
                          "  \"Arithmetic helpers, kept apart from adv.core so the\n"
                          "  advisory fixtures do not share state.\")\n\n"
                          "(defn add [x y] (+ x y))\n"))
        (let [r (ops/cleanup! sess 'adv.clean)]
          (is (empty? (:advisories r)) (pr-str (:advisories r)))))
      (finally (ops/close! sess)))))

(deftest ^:external cleanup-reports-every-gate-not-just-the-advisories
  ;; Everything slopp can check on a WRITE, checkable over EXISTING code. The
  ;; write gates (module/tier/schema/namespaced-keys) fire per write, so code
  ;; that predates a gate was never subject to it; lint, dead public surface
  ;; and missing docstrings ride done and so only ever saw touched forms.
  ;; cleanup is the migration surface, so it reports all of them.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'gap.core
                   (str "(ns gap.core)\n\n"
                        "(defn documented\n  \"Has one.\"\n  [x] (inc x))\n\n"
                        "(defn nodoc [x] (do (inc x)))\n"))
      (let [r (ops/cleanup! sess 'gap.core :prompt "survey")]
        (is (nil? (:error r)) (pr-str r))
        (testing "dead public surface is reported"
          (is (contains? (set (:unused r)) 'gap.core/documented) (pr-str r)))
        (testing "undocumented public surface is reported"
          (is (contains? (set (:undocumented r)) 'gap.core/nodoc) (pr-str r)))
        (testing "the write gates are replayed over existing forms"
          (is (contains? r :gates) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external cleanup-all-sweeps-the-whole-store
  ;; THE migration surface: adopting slopp on an existing codebase, or landing
  ;; a slopp upgrade that adds a rule, means every namespace needs the tidy
  ;; applied and the whole enforcement surface replayed. Per-namespace is the
  ;; wrong grain for that — you do not know which namespaces predate the rule.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'swa.core
                   (str "(ns swa.core)\n\n"
                        "(defn a [x] (do (inc x)))\n"))
      (ops/ingest! sess 'swb.core
                   (str "(ns swb.core)\n\n"
                        "(def cache (atom {}))\n\n"
                        "(defn b [k] (get @cache k))\n"))
      (let [r (ops/cleanup-all! sess :prompt "migration sweep")]
        (is (nil? (:error r)) (pr-str r))
        (is (= 2 (:namespaces r)) (pr-str r))
        (testing "findings are attributed to their namespace"
          (is (contains? (set (map :ns (:findings r))) 'swb.core)
              (str "the ambient atom must surface: " (pr-str (:findings r)))))
        (testing "a namespace with nothing to report is not listed as a finding"
          (is (every? (fn [f] (seq (dissoc f :ns))) (:findings r))
              (pr-str (:findings r)))))
      (finally (ops/close! sess)))))

(deftest ^:external move-a-form-with-an-unresolvable-callee
  ;; End-to-end guard for the crash that made slopp.api/open! unmovable:
  ;; kondo marks the proxy method body's `run` with the KEYWORD sentinel
  ;; :clj-kondo/unknown-namespace, which reached a sort over namespace
  ;; symbols. Shaped like open! — proxy, a metadata map on the name, and TWO
  ;; libs (with one the sentinel was the only element left, and sorting one
  ;; element never calls compare). refactor-test/plan-survives-an-unresolvable-callee
  ;; covers the planner; this covers the executor.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'mvm.a
                   (str "(ns mvm.a\n"
                        "  (:require [clojure.string :as str]))\n\n"
                        "(defn ^{:live-handle true} spin\n"
                        "  \"S.\"\n"
                        "  [t]\n"
                        "  (.schedule t (proxy [java.util.TimerTask] []\n"
                        "                 (run [] (str/upper-case \"x\")))\n"
                        "               1000 1000))\n"))
      (let [r (ops/move-forms! sess 'mvm.a '[spin] 'mvm.b :export true)]
        (is (nil? (:error r)) (pr-str r))
        (is (re-find #"defn.*spin" (query/query-source sess 'mvm.b)))
        (is (not (re-find #"clj-kondo" (query/query-source sess 'mvm.b)))
            "the unresolvable callee must not become a require"))
      (finally (ops/close! sess)))))

(deftest ^:external move-drops-the-requires-it-orphans
  ;; The executor half of refactor-test/plan-drops-requires-the-move-orphans.
  ;; Moving `f` takes the last user of clojure.string; the require must leave
  ;; with it, while clojure.set stays because `g` still uses it.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'dr.core
                   (str "(ns dr.core\n"
                        "  (:require [clojure.string :as str]\n"
                        "            [clojure.set :as set]))\n\n"
                        "(defn f \"F.\" [x] (str/upper-case x))\n\n"
                        "(defn g \"G.\" [a b] (set/union a b))\n"))
      (let [r (ops/move-forms! sess 'dr.core '[f] 'dr.moved)]
        (is (nil? (:error r)) (pr-str r))
        (let [src (query/query-source sess 'dr.core)]
          (is (not (re-find #"clojure\.string" src))
              (str "the orphaned require must leave with the form:\n" src))
          (is (re-find #"clojure\.set" src)
              (str "a require still in use must stay:\n" src))))
      (finally (ops/close! sess)))))

(deftest ^:external a-test-only-caller-does-not-make-a-within-module-move-a-cycle
  ;; move-forms! computed the edges a move NEEDS against the production
  ;; manifest alone, so a crossing declared {test-only true} looked undeclared
  ;; and was charged to the move. Two things made that wrong at once: the
  ;; crossing is a TEST crossing, which module_dep's own docstring says "is NOT
  ;; a production edge, so no cycle question applies to it"; and the move here
  ;; does not change the crossing at all, both namespaces being in one module.
  ;; store-violations already joins both manifests — this was the second
  ;; derivation of an existing rule, drifting from it.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'tmv.store.core "(ns tmv.store.core)\n\n(defn ^:export keep! \"K.\" [x] x)\n")
      (ops/module-dep! sess "tmv.read" "tmv.store" :prompt "reads read the store")
      (ops/ingest! sess 'tmv.read.query
                   (str "(ns tmv.read.query (:require [tmv.store.core :as core]))\n\n"
                        "(defn ^:export f \"F.\" [x] (core/keep! x))\n"))
      (ops/ingest! sess 'tmv.read.history "(ns tmv.read.history)\n\n(defn spare \"S.\" [x] x)\n")
      (ops/module-dep! sess "tmv.store" "tmv.read" :test-only true
                       :prompt "the store's TESTS drive a read")
      (ops/ingest! sess 'tmv.store.db-test
                   (str "(ns tmv.store.db-test (:require [tmv.read.query :as q]))\n\n"
                        "(defn probe \"P.\" [x] (q/f x))\n"))
      (testing "a within-module move whose only outside caller is a TEST is not a cycle"
        (let [r (ops/move-forms! sess 'tmv.read.query '[f] 'tmv.read.history
                                 ;; f is ALREADY ^:export and its node carries
                                 ;; that to the new home, so the flag is
                                 ;; redundant in fact — but the check cannot see
                                 ;; it, because a row targeting the destination
                                 ;; does not record WHICH moved var was called.
                                 ;; Passing it is the honest fixture until
                                 ;; :module-rows carries the callee.
                                 :export true
                                 :prompt "the time reads belong with history")]
          (is (nil? (:error r)) (pr-str r))
          (is (re-find #"defn \^:export f" (query/query-source sess 'tmv.read.history)))
          (is (re-find #"history/f" (query/query-source sess 'tmv.store.db-test))
              "the test caller was rewritten to the new home")))
      ;; THE CONTROL. Fixing the above must not amount to switching the cycle
      ;; check off: a move that genuinely needs a new PRODUCTION edge closing a
      ;; cycle still has to refuse. Moving f into ctl.c makes ctl.b need ctl.c
      ;; while ctl.c -> ctl.b already exists. TWO-segment namespaces throughout,
      ;; deliberately: at three, every one of these is package-private, and
      ;; module-violations reports visibility BEFORE the undeclared edge — so a
      ;; deep fixture refuses for the wrong reason and proves nothing about the
      ;; cycle check. The first draft of this control did exactly that.
      (testing "a production caller still closes a cycle, and is still refused"
        (ops/ingest! sess 'ctl.a "(ns ctl.a)\n\n(defn f \"F.\" [x] (inc x))\n")
        (ops/module-dep! sess "ctl.b" "ctl.a" :prompt "b uses a")
        (ops/ingest! sess 'ctl.b
                     (str "(ns ctl.b (:require [ctl.a :as a]))\n\n"
                          "(defn g \"G.\" [x] (a/f x))\n"))
        (ops/module-dep! sess "ctl.c" "ctl.b" :prompt "c uses b")
        (ops/ingest! sess 'ctl.c
                     (str "(ns ctl.c (:require [ctl.b :as b]))\n\n"
                          "(defn h \"H.\" [x] (b/g x))\n"))
        (let [r (ops/move-forms! sess 'ctl.a '[f] 'ctl.c :prompt "control")]
          (is (some? (:error r)) (pr-str r))
          (is (re-find #"cycle" (str (:error r))) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external a-move-into-a-new-namespace-carries-the-tier-it-left
  ;; UNDECLARED is `:external` by absence of a claim, so a namespace born from
  ;; a move is born in the shell. The forms that left were PURE the moment
  ;; before — same module, same code — and the split invented a core→shell
  ;; dependency out of nothing. Worse, `full_check` reported it against the
  ;; namespace that did NOT change, since that is the one whose claim breaks;
  ;; the namespace missing the declaration is the actionable one and went
  ;; unmentioned. Hit four times in one session.
  ;;
  ;; `ns_rename` has no equivalent gap — it RELOCATES a declaration rather
  ;; than copying it, measured twice. The asymmetry between the two relocation
  ;; verbs was invisible until a whole-store check.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'tq.core
                   (str "(ns tq.core)\n\n"
                        "(defn ^:unused-ok pick \"P.\" [m] (get m :a))\n\n"
                        "(defn ^:unused-ok double-it \"D.\" [x] (* 2 x))\n"))
      (is (nil? (:error (ops/module-tier! sess "tq.core" :pure
                                          :prompt "a real core"))))
      (let [r (ops/move-forms! sess 'tq.core ["double-it"] 'tq.split
                               :prompt "split the arithmetic out")]
        (is (nil? (:error r)) (pr-str r)))
      (testing "the target carries the tier its forms already satisfied"
        (is (= :pure (get (:module-tiers (:store @sess)) "tq.split"))
            (pr-str (:module-tiers (:store @sess)))))

      (testing "an UNDECLARED source mints nothing — absence is not a claim"
        ;; the honest default is what was true one delta ago, and for an
        ;; undeclared source that is "nobody has said". Stamping :external
        ;; here would defeat a deliberate move INTO a pure subtree, where the
        ;; right outcome is the write gate refusing impure forms on the way in.
        (ops/ingest! sess 'tu.core
                     (str "(ns tu.core)\n\n"
                          "(defn ^:unused-ok keep-it \"K.\" [x] x)\n\n"
                          "(defn ^:unused-ok take-it \"T.\" [x] (inc x))\n"))
        (let [r (ops/move-forms! sess 'tu.core ["take-it"] 'tu.split
                                 :prompt "split an undeclared namespace")]
          (is (nil? (:error r)) (pr-str r)))
        (is (nil? (get (:module-tiers (:store @sess)) "tu.split"))
            (pr-str (:module-tiers (:store @sess)))))
      (finally (ops/close! sess)))))
