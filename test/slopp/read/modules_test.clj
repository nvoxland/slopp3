(ns slopp.read.modules-test
  "Cover for the module-level answers: what is dead, what is visible, and
  whether an operation's declared intent actually landed.

  These questions are whole-store by nature — a var is unused only if NOTHING
  anywhere references it — so the fixtures ingest a real store rather than
  mock a graph. It costs nothing (ingest and the reference graph are pure) and
  it means the tests exercise the same derivation production does.

  Where a test asserts an exemption (a marker discharging the unused gate), it
  asserts the unexempted case alongside it: a report that had simply stopped
  finding anything would satisfy the first half on its own."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops] [slopp.read.modules :as modules] [slopp.store :as store] [slopp.index.refs :as refs] [slopp.ops.external :as external] [slopp.edit.modules :as edit.modules] [slopp.read.graph :as graph]))

(deftest ^:external the-module-surface-is-browsable
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ma.core
                   (str "(ns ma.core)\n"
                        "(defn shared \"Public.\" [x] x)\n"
                        "(defn- internal [x] x)\n"
                        "(def rates \"Known rates.\" [0.07 0.20])\n"))
      (ops/ingest! sess 'ma.core.impl
                   (str "(ns ma.core.impl)\n"
                        "(defn hidden \"Package.\" [x] x)\n"
                        "(defn ^:export hoisted \"World.\" [x] x)\n"
                        "(defn ^{:export \"ma.core\"} scoped \"Module-wide.\" [x] x)\n"))
      (ops/module-dep! sess "mb.app" "ma.core" :prompt "consumer")
      (ops/ingest! sess 'mb.app
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
        (testing "a def rides the surface, and does NOT claim to take arguments"
          ;; `sig` was the first vector in the WHOLE form, so a def's value read
          ;; as a parameter list. This is the cheap-browse-before-calling-in
          ;; view, which makes a fabricated arity the most expensive kind of
          ;; wrong it could carry.
          (let [rates (first (filter #(= 'rates (:name %)) (:surface r)))]
            (is (some? rates) "a public def is part of what a module offers")
            (is (nil? (:sig rates)) (pr-str rates))
            (is (= "Known rates." (:doc rates)) "the docstring still reads")))
        (testing "deps and consumers come from the manifest"
          (is (= ["ma.core"] (:deps (modules/module-surface sess "mb.app")))
              "mb.app declares ma.core")
          (is (= ["mb.app"] (:consumers r)))))
      (testing "an unknown module teaches the list"
        (is (re-find #"modules true" (str (:error (modules/module-surface sess "zz.nope"))))))
      (testing "the graph view: layers, and drift toward DEAD edges is named"
        (ops/module-dep! sess "mb.app" "mc.ghost" :prompt "declared, never used")
        (let [r (graph/query-depends sess nil :modules true)]
          ;; mc.ghost is a phantom (declared edge, NO code) — production layers
          ;; exclude it, though :unused-edges still flags the dead edge
          (is (= [["ma.core"] ["mb.app"]] (:layers r)) (pr-str r))
          (is (= [["mb.app" "mc.ghost"]] (:unused-edges r)))
          (is (re-find #"remove true" (:unused-note r)))
          (is (nil? (:cycles r)))))
      (finally (ops/close! sess)))))

(deftest ^:external module-graph-views-use-production-edges
  ;; -test namespaces fold into the subject module, so their fixture deps
  ;; would pollute the graph and manufacture cycles that don't exist in
  ;; production. Two things keep the views honest, and this pins both:
  ;; adoption CLASSIFIES a fixture-only crossing as a test edge rather than
  ;; deriving a dependency the project does not have, and the layers/cycles
  ;; view is computed over production edges regardless.
  ;;
  ;; Until 2026-08-02 only the second held: adoption wrote the fixture's
  ;; crossing straight into `:modules`, which is how slopp's own manifest came
  ;; to assert `slopp.index` and `slopp.store` depend on `slopp.api`. The view
  ;; hid it; the enforcement graph believed it.
  (let [sess (external/open!)]
    (try
      ;; adoption-style setup (gate off) so we can land the cyclic fixture edge
      (swap! sess assoc :adopting? true)
      (ops/ingest! sess 'pa.core "(ns pa.core)\n(defn base \"B.\" [x] x)\n")
      (ops/ingest! sess 'pb.app
                   (str "(ns pb.app (:require [pa.core :as c]))\n"
                        "(defn go \"G.\" [x] (c/base x))\n"))
      (ops/ingest! sess 'pa.core-test
                   (str "(ns pa.core-test (:require [pb.app :as app]\n"
                        "                           [clojure.test :refer [deftest is]]))\n"
                        "(deftest go-t (is (= 1 (app/go 1))))\n"))
      (swap! sess dissoc :adopting?)
      (ops/adopt-modules! sess)   ; production pb.app→pa.core; the back-edge is TEST-only
      (let [r (graph/query-depends sess nil :modules true)]
        (testing "adoption declares the fixture's crossing WITHOUT claiming a dependency"
          (is (not (contains? (set (get-in r [:manifest "pa.core"])) "pb.app"))
              (str "pa.core does not depend on pb.app — only its test does: "
                   (pr-str (:manifest r))))
          (is (contains? (set (get-in r [:test-edges "pa.core"])) "pb.app")
              (pr-str (:test-edges r))))
        (testing "and it is DECLARED, so the fixture's call is not standing debt"
          ;; the must-not-flag half: classifying must permit, not merely relabel
          (is (nil? (modules/module-debt (:store @sess)))
              (pr-str (modules/module-debt (:store @sess)))))
        (testing "the production graph is acyclic — no false cycle in the view"
          (is (nil? (:cycles r)) (pr-str (:cycles r))))
        (testing "layers reflect production: pa.core sits below pb.app"
          (let [layer-of (into {} (for [[i layer] (map-indexed vector (:layers r))
                                        m layer] [m i]))]
            (is (< (layer-of "pa.core") (layer-of "pb.app"))
                (pr-str (:layers r))))))
      (finally (ops/close! sess)))))

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

(deftest a-covers-declaration-is-coverage-not-liveness
  ;; ^{:covers} on a deftest declares which tests reach a form (for the
  ;; dispatch/data path covered-by can't see statically). It is a COVERAGE
  ;; claim, not a keep-alive marker — a form whose only reference is a
  ;; :covers declaration is still dead public surface. The honest liveness
  ;; marker is ^:entry-point / ^:unused-ok ON THE FORM, kept separate.
  (let [st (-> (store/empty-store)
               (store/ingest 'cd.core "(ns cd.core)\n\n(defn dispatched \"D.\" [x] x)\n")
               (store/ingest 'cd.core-test
                             (str "(ns cd.core-test (:require [clojure.test :refer [deftest is]]))\n"
                                  "(deftest ^{:covers \"cd.core/dispatched — via dispatch\"} t (is true))\n")))
        r  (modules/unused-report st '[cd.core])]
    (is (= '[cd.core/dispatched] (:unused r))
        (str "a :covers declaration must not exempt from the unused gate: " (pr-str r)))))

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
      (ops/ingest! sess 'pm.core "(ns pm.core)\n(defn add [x y] (+ x y))\n")
      (ops/ingest! sess 'pm.edge
                   "(ns pm.edge)\n(defn roll [] (rand))\n")
      (let [r (graph/query-depends sess nil :modules true :detail true)]
        (testing "a module whose code is clean is reported as tightenable"
          (is (= :pure (get-in r [:purity :could-tighten "pm.core" :supports]))
              (pr-str (:purity r))))
        (testing "a module reaching non-determinism is NOT offered :pure"
          (is (not= :pure
                    (get-in r [:purity :could-tighten "pm.edge" :supports]))
              (pr-str (:purity r)))))
      (testing "once declared, it is reported as declared and no longer offered"
        (ops/module-tier! sess "pm.core" :pure :prompt "clean core")
        (let [r (graph/query-depends sess nil :modules true :detail true)]
          (is (= :pure (get-in r [:purity :declared "pm.core"])) (pr-str r))
          (is (nil? (get-in r [:purity :could-tighten "pm.core"])) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external the-surface-answers-at-namespace-grain
  ;; Tiers became namespace-grained (a pure core one level below an effectful
  ;; module), so "what does this offer?" has to be answerable there too.
  ;; Asking about slopp.rules.shape used to error with "no namespaces in module".
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'sg.core
                   "(ns sg.core)\n(defn ^:unused-ok top \"T.\" [x] x)\n")
      (ops/ingest! sess 'sg.core.calc
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
      (finally (ops/close! sess)))))

(deftest purity-standing-accepts-canonical-tier-spellings
  ;; d9157 standardized :internal/:external, but the reporting arm's rank table
  ;; still spoke only the retired vocabulary, so (rank :internal) came back nil
  ;; and query_depends {modules true} crashed with an NPE on any store carrying
  ;; a current-vocabulary tier. Both spellings must be readable; answers come
  ;; back canonical.
  (let [pure-src  "(ns cv.core)\n(defn add [x y] (+ x y))\n"
        with-tier (fn [tier]
                    (-> (store/empty-store)
                        (store/ingest 'cv.core pure-src)
                        (store/record-module-tier "cv.core" tier)
                        first))]
    (testing "a canonical :internal declaration ranks instead of crashing"
      (is (= {:declared :internal :supports :pure}
             (get-in (modules/purity-standing (with-tier :internal))
                     [:could-tighten "cv.core"]))))
    (testing "a legacy :reads declaration reads back canonical"
      (is (= {:declared :internal :supports :pure}
             (get-in (modules/purity-standing (with-tier :reads))
                     [:could-tighten "cv.core"]))))))

(deftest ^:external module-tier-records-canonical-spelling
  ;; module_purity accepts :reads/:effects as legacy spellings, but recording
  ;; them raw is how mixed vocabulary entered the store and broke every reader
  ;; that ranked tiers. Legacy in, canonical stored.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'lv.core "(ns lv.core)\n(defn add [x y] (+ x y))\n")
      (is (= :internal
             (get-in (ops/module-tier! sess "lv.core" :reads :prompt "legacy in")
                     [:tiers "lv.core"])))
      (finally (ops/close! sess)))))

(deftest ^:external module-graph-defaults-compact-and-expands-on-detail
  ;; :could-tighten is an ADOPTION worklist — which modules could carry a
  ;; stricter tier. It is one-time information, but it rode every
  ;; query_depends {modules true} response: measured at 2,304 of 5,584 chars
  ;; (41%) on the eval9 seed, byte-identical across three calls in one
  ;; lifetime. Names by default answer "which?"; the per-module
  ;; :declared/:supports detail is one flag away.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ct.core "(ns ct.core)\n(defn ^:unused-ok add \"A.\" [x y] (+ x y))\n")
      (ops/ingest! sess 'ct.edge "(ns ct.edge)\n(defn ^:unused-ok roll \"R.\" [] (rand))\n")
      (testing "default: could-tighten names only, and it says where the detail is"
        (let [r  (graph/query-depends sess nil :modules true)
              ct (get-in r [:purity :could-tighten])]
          (is (vector? ct) (str "expected a name vector, got: " (pr-str ct)))
          (is (some #{"ct.core"} ct) (pr-str ct))
          (is (every? string? ct) (pr-str ct))
          (is (re-find #"(?i)detail" (str (get-in r [:purity :note])))
              (pr-str (:purity r)))))
      (testing "detail true: the full per-module worklist comes back"
        (let [r  (graph/query-depends sess nil :modules true :detail true)
              ct (get-in r [:purity :could-tighten])]
          (is (map? ct) (pr-str ct))
          (is (= :pure (get-in ct ["ct.core" :supports])) (pr-str ct))))
      (testing "the architecture answer is unaffected by the default"
        (let [r (graph/query-depends sess nil :modules true)]
          (is (some? (:layers r)))
          (is (map? (:manifest r)))))
      (finally (ops/close! sess)))))

(deftest generated-forms-are-exempt-from-inspection-gates
  ;; ^:generated forms are generate_client's output — client-API wrappers that
  ;; await FE calls (so a zero-caller wrapper is "available", not dead), are
  ;; documented BY the generator, and are never hand-tested. They must not trip
  ;; the dead-surface or missing-doc inspection gates (D-web-contracts part 2).
  (let [st (-> (store/empty-store)
               (store/ingest 'gc.client
                             (str "(ns gc.client)\n\n"
                                  "(defn ^{:generated \"app.orders/create-order\"} create-order! [x] x)\n\n"
                                  "(defn orphan [x] x)\n")))]
    (testing "dead-surface skips a ^:generated public defn, keeps the real orphan"
      (is (= '[gc.client/orphan] (:unused (modules/unused-report st '[gc.client])))
          (pr-str (modules/unused-report st '[gc.client]))))
    (testing "missing-doc skips a ^:generated public defn, keeps the undocumented real one"
      (is (nil? (edit.modules/missing-doc-warning st 'gc.client 'create-order!)))
      (is (some? (edit.modules/missing-doc-warning st 'gc.client 'orphan))))))

(deftest a-namespace-has-to-say-what-it-is-for
  ;; A namespace's INVENTORY is derived — query_project, the module surface
  ;; and the outline all list its forms. What no tool can derive is why the
  ;; namespace exists, what to expect inside it, and how it relates to its
  ;; neighbours. That is the whole content of an ns docstring, and 100 of
  ;; slopp's own 177 namespaces had none — including slopp.web.css, whose
  ;; unstated garden conventions cost a shipped bug.
  (let [with (store/ingest (store/empty-store) 'app.good
                           (str "(ns app.good\n"
                                "  \"Why this exists and how it relates to app.other.\")\n\n"
                                "(defn f [] 1)\n"))
        without (store/ingest (store/empty-store) 'app.bare "(ns app.bare)\n\n(defn f [] 1)\n")]
    (testing "a namespace stating its purpose draws nothing"
      (is (nil? (edit.modules/namespace-purpose-warning with 'app.good))))
    (testing "a bare ns form is an advisory naming the namespace"
      (let [w (edit.modules/namespace-purpose-warning without 'app.bare)]
        (is (= 'app.bare (:ns w)) (pr-str w))
        (is (true? (:missing-purpose w)))))
    (testing "the teaching says what a purpose IS, and what it is not"
      ;; the failure mode to head off is a docstring that lists the forms —
      ;; that is derived, and restating it is a second copy that drifts
      (let [t (str (:teach (edit.modules/namespace-purpose-warning without 'app.bare)))]
        (is (re-find #"(?i)why" t) t)
        (is (re-find #"(?i)not.*(list|inventor)" t)
            (str "it has to say NOT an inventory: " t))))
    (testing "a docstring that is only an inventory is still no purpose"
      ;; deliberately NOT enforced by shape — a heuristic that guesses at
      ;; prose quality would fire on good docstrings, and Core 2 says fix the
      ;; analysis before restricting the language. The teach line carries it.
      (is (nil? (edit.modules/namespace-purpose-warning
                 (store/ingest (store/empty-store) 'app.listy
                               "(ns app.listy \"Contains f, g and h.\")\n\n(defn f [] 1)\n")
                 'app.listy))
          "stated but poor is a review question, not a gate question"))
    (testing "a GENERATED namespace is exempt — its author is a tool"
      (let [gen (store/ingest (store/empty-store) 'app.client
                              (str "(ns app.client)\n\n"
                                   "(defn ^{:generated \"app.api/f\"} f \"Doc.\" [] 1)\n"))]
        (is (nil? (edit.modules/namespace-purpose-warning gen 'app.client)))))
    (testing "an EMPTY namespace is exempt — there is nothing to describe yet"
      (is (nil? (edit.modules/namespace-purpose-warning
                 (store/ingest (store/empty-store) 'app.empty "(ns app.empty)\n")
                 'app.empty))))))

(deftest an-escape-marker-carrying-its-WHY-discharges-the-same
  ;; Every escape dial takes a bare keyword today — `^:unused-ok`, which says
  ;; nothing about why no caller is expected. The dial becomes self-documenting
  ;; provenance the moment it carries a reason, the way `:prompt` rides every
  ;; delta and `^{:covers "ns/name — why"}` already does.
  ;;
  ;; The compatibility question comes FIRST, because asking agents to say why
  ;; would be actively harmful if the map form silently stopped discharging:
  ;; every escape anyone wrote that way would turn into a gate failure with no
  ;; hint that the shape was the problem.
  (let [ing (fn [src] (store/ingest (store/empty-store) 'mw.core src))]
    (testing "no marker at all: the gate fires — the discriminating half"
      ;; without this, the assertions below would pass on a report that had
      ;; simply stopped finding anything
      (is (= '[mw.core/f]
             (:unused (modules/unused-report (ing "(ns mw.core)\n(defn f \"F.\" [x] x)\n")
                                             '[mw.core])))))
    (testing "the bare keyword discharges, as it always has"
      (is (= [] (:unused (modules/unused-report
                          (ing "(ns mw.core)\n(defn ^:unused-ok f \"F.\" [x] x)\n")
                          '[mw.core])))))
    (testing "and the map form carrying a WHY discharges identically"
      (is (= [] (:unused (modules/unused-report
                          (ing (str "(ns mw.core)\n"
                                    "(defn ^{:unused-ok \"library surface for external consumers\"}\n"
                                    "  f \"F.\" [x] x)\n"))
                          '[mw.core])))))
    (testing "the same for ^:entry-point, the other keep-alive dial"
      (is (= [] (:unused (modules/unused-report
                          (ing (str "(ns mw.core)\n"
                                    "(defn ^{:entry-point \"boot --call dispatch\"}\n"
                                    "  f \"F.\" [x] x)\n"))
                          '[mw.core])))))
    (testing "a marker with a why still polices itself when the var IS called"
      ;; the stale-marker check reads the same dial, so the richer form must
      ;; not become a permanent silent opt-out
      (is (= '[mw.core/f]
             (:stale (modules/unused-report
                      (ing (str "(ns mw.core)\n"
                                "(defn ^{:unused-ok \"nobody calls this\"} f \"F.\" [x] x)\n"
                                "(defn ^:unused-ok g \"G.\" [x] (f x))\n"))
                      '[mw.core])))))))

(deftest a-planned-export-is-checked-against-the-COMMITTED-store
  ;; The incident: `export-mark` silently SKIPPED meta-wrapped names, so
  ;; `(def ^:dynamic *pre-commit-hook* …)` came out of a move with no
  ;; `^:export` on it. The move's own gate pre-check had passed, because it
  ;; trusted the PLANNED export while the store carried no marker. Caught a
  ;; session later by the debt view — which reads reality.
  ;;
  ;; Verification must check REALITY, not intent. An operation that reports
  ;; what it MEANT to do is indistinguishable from one that did it.
  ;;
  ;; The callee is `:to-name`, the one spelling every module row uses. It was
  ;; `:name` here and absent on the destination rows that make up most of a
  ;; real move, which is how this check spent a session reporting every landed
  ;; export as unlanded under a bare namespace and no var.
  (let [st (store/ingest (store/empty-store) 'pe.deep.core
                         (str "(ns pe.deep.core)\n"
                              "(defn ^:export landed \"L.\" [x] x)\n"
                              "(def ^:dynamic *missed* nil)\n"
                              "(defn plain \"P.\" [x] x)\n"))
        row (fn [nm] {:to 'pe.deep.core :to-name nm :to-export true})]
    (testing "a planned export that IS on the committed form reports nothing"
      (is (= [] (modules/unlanded-exports st [(row 'landed)]))))
    (testing "a planned export the store does not carry is REPORTED"
      ;; the meta-wrapped name — the exact shape the marker pass skipped
      (is (= '[pe.deep.core/*missed*]
             (modules/unlanded-exports st [(row '*missed*)]))))
    (testing "several rows report every miss, not just the first"
      (is (= '[pe.deep.core/*missed* pe.deep.core/plain]
             (modules/unlanded-exports st [(row 'landed) (row '*missed*) (row 'plain)]))))
    (testing "rows that planned NO export are not this check's business"
      (is (= [] (modules/unlanded-exports
                 st [{:to 'pe.deep.core :to-name 'plain :to-export nil}]))))
    (testing "a subtree export (a string level) counts as planned too"
      (is (= '[pe.deep.core/plain]
             (modules/unlanded-exports
              st [{:to 'pe.deep.core :to-name 'plain :to-export "pe.other"}]))))))

(deftest substrate-bands-sinks-that-many-modules-depend-on
  (testing "a sink two or more modules depend on is foundation"
    (is (= #{"lib"}
           (modules/substrate {"a" ["lib"] "b" ["lib"] "lib" []}))))
  (testing "a sink only ONE module depends on stays an ordinary node"
    (is (= #{}
           (modules/substrate {"a" ["lib"] "lib" []}))))
  (testing "a depended-on module that itself reaches a non-substrate module is not foundation"
    (is (= #{"lib"}
           (modules/substrate {"a" ["mid" "lib"] "b" ["mid"] "mid" ["lib" "a"] "lib" []})))))

(def slopp-production
  "slopp's own production module manifest as of 2026-07-26 — 14 modules,
   33 edges, 9 layers, no cycles. Small enough to check the answer by hand.

   A DATED snapshot, deliberately not maintained: it is an input to the
   layering algorithm, not a claim about today. Read the two `api` keys with
   that in mind, because both names were later REUSED and a glossary keyed by
   spelling cannot say so — `slopp.api` here is the 322-form operation drawer
   (today's `slopp.ops`), and `slopp.http-api` is what today's `slopp.api`
   was called. `slopp.boot` and `slopp.rt` were later folded into one module,
   `slopp.kernel`. Nothing about this fixture is stale; the names simply moved
   underneath it.

   These are MODULE names, which is why a store-wide prose sweep must not
   touch them: a module is the first TWO segments of a namespace, so the
   rewrite a sweep produces for a namespace that gained a segment — here
   `slopp.rt` to `slopp.kernel.rt` — names a module that `module-of` can
   never yield. It is not merely dated then, it is invalid. Both sweeps of
   the kernel move hit this form and both were reverted."
  {"slopp.api"   ["slopp.boot" "slopp.edit" "slopp.image" "slopp.index" "slopp.store" "slopp.web"]
   "slopp.bench" ["slopp.api" "slopp.mcp" "slopp.store"]
   "slopp.boot"  []
   "slopp.cache" []
   "slopp.edit"  ["slopp.cache" "slopp.image" "slopp.index" "slopp.store"]
   "slopp.git"   ["slopp.store"]
   "slopp.image" ["slopp.rt" "slopp.store"]
   "slopp.index" ["slopp.cache" "slopp.image"]
   "slopp.mcp"   ["slopp.api" "slopp.git" "slopp.store" "slopp.sync" "slopp.http-api" "slopp.web"]
   "slopp.rt"    []
   "slopp.store" ["slopp.cache"]
   "slopp.sync"  ["slopp.api" "slopp.boot" "slopp.git" "slopp.store"]
   "slopp.http-api"    ["slopp.api" "slopp.edit" "slopp.store" "slopp.web"]
   "slopp.web"   []})

(deftest substrate-on-a-real-manifest-names-the-foundation-and-nothing-else
  ;; The names here are the DATED ones `slopp-production` carries and must
  ;; keep — see its docstring. A sweep that renames them renames one half of
  ;; a fixture whose two halves are compared against each other.
  (let [band  (modules/substrate slopp-production)
        edges (for [[m ds] slopp-production d ds] [m d])]
    (testing "the foundation is the three widely-used sinks plus the store hub"
      (is (= #{"slopp.boot" "slopp.cache" "slopp.web" "slopp.store"} band)))
    (testing "store is banded despite an outgoing edge — everyone calls it, it calls almost nothing"
      (is (contains? band "slopp.store")))
    (testing "rt is a sink but NOT foundation: its one edge from image is the informative kind"
      (is (not (contains? band "slopp.rt")))
      (is (some (fn [[m d]] (and (= "slopp.image" m) (= "slopp.rt" d))) edges)
          "and that edge therefore survives into the drawn picture"))
    (testing "promotion stops at one level — these are components, not foundation"
      (is (empty? (filter band ["slopp.git" "slopp.image" "slopp.api" "slopp.edit"]))))
    (testing "banding is what makes the picture readable: 16 of 33 edges stop being drawn"
      (is (= 33 (count edges)))
      (is (= 17 (count (remove (fn [[_ d]] (band d)) edges)))))))

(deftest a-fully-entangled-graph-has-no-foundation-to-name
  (testing "no sinks means no band, and every edge gets drawn — the honest picture"
    (is (= #{} (modules/substrate {"a" ["b"] "b" ["c"] "c" ["a"]}))))
  (testing "a single god-module everything calls is still found"
    (is (= #{"god"}
           (modules/substrate {"a" ["god"] "b" ["god"] "c" ["god"] "god" []})))))

(deftest ^:external a-merge-cycle-note-judges-production-edges
  ;; The merge's cycle warning was the ONE surface still judging the
  ;; DECLARED manifest. A `-test` namespace folds into its subject module,
  ;; so its fixture requires are declared edges — and on slopp's own store
  ;; `slopp.store.db-test`'s require of `slopp.api` closed
  ;; `slopp.api → slopp.edit → slopp.image → slopp.store → slopp.api`.
  ;; Every merge into main reported that cycle, and its advice — retract an
  ;; edge — would have broken the test that created it. There was no
  ;; production cycle to fix.
  (let [sess (external/open!)]
    (try
      (swap! sess assoc :adopting? true)
      (ops/ingest! sess 'pa.core "(ns pa.core)\n(defn base \"B.\" [x] x)\n")
      (ops/ingest! sess 'pb.app
                   (str "(ns pb.app (:require [pa.core :as c]))\n"
                        "(defn go \"G.\" [x] (c/base x))\n"))
      ;; the fixture back-edge: production-wise pa.core NEVER reaches pb.app
      (ops/ingest! sess 'pa.core-test
                   (str "(ns pa.core-test (:require [pb.app :as app]\n"
                        "                           [clojure.test :refer [deftest is]]))\n"
                        "(deftest go-t (is (= 1 (app/go 1))))\n"))
      (swap! sess dissoc :adopting?)
      (ops/adopt-modules! sess)
      (let [;; Adoption no longer derives a fixture's crossing as a production
            ;; edge (it lands in :module-test-edges), so the polluted declared
            ;; manifest is now BUILT here rather than inherited. Building it is
            ;; the honest fixture anyway: a declared-only cycle still arises —
            ;; a merge unions two manifests that are each acyclic, which is
            ;; precisely what merge-production-cycle guards.
            st     (assoc-in (:store @sess) [:modules "pa.core"] #{"pb.app"})
            ;; `before` lacking the edge is what makes this a merge that
            ;; GAINED one — the check is scoped to that, so a standing
            ;; cycle is not re-reported on every unrelated merge.
            before (assoc st :modules {})]
        (testing "a cycle that exists ONLY in the declared manifest is not a note"
          (is (contains? (set (get-in st [:modules "pa.core"])) "pb.app")
              "precondition: the declared manifest carries the fixture edge")
          (is (some? (store/modules-cycle (:modules st)))
              "precondition: judged as DECLARED, this graph is cyclic")
          (is (nil? (modules/merge-production-cycle before st))
              (pr-str (:modules st))))
        (testing "a merge that gained NO module edge reports nothing"
          (is (nil? (modules/merge-production-cycle st st))))
        (testing "and adoption did not put it there in the first place"
          ;; the same fact from the other side: what the fixture crosses is
          ;; declared, but as a TEST edge, so :modules never carried a
          ;; dependency pa.core does not have
          (is (empty? (get-in (:store @sess) [:modules "pa.core"]))
              (pr-str (:modules (:store @sess))))
          (is (contains? (get-in (:store @sess) [:module-test-edges "pa.core"] #{})
                         "pb.app")
              (pr-str (:module-test-edges (:store @sess))))))
      ;; now a REAL production cycle: pa.core.impl reaches pb.app in
      ;; production code. Module edges are first-two-segments, so this
      ;; closes pa.core ⇄ pb.app without an ns-level require cycle.
      (swap! sess assoc :adopting? true)
      (ops/ingest! sess 'pa.core.impl
                   (str "(ns pa.core.impl (:require [pb.app :as app]))\n"
                        "(defn back \"B.\" [x] (app/go x))\n"))
      (swap! sess dissoc :adopting?)
      (ops/adopt-modules! sess)
      (testing "a cycle in PRODUCTION code is still reported"
        (let [st (:store @sess)]
          (is (some? (modules/merge-production-cycle (assoc st :modules {}) st))
              (pr-str (:modules st)))))
      (finally (ops/close! sess)))))

(deftest ^:external an-edge-no-production-code-crosses-is-reported-as-overstated
  ;; A declared production edge that only `-test` namespaces cross OVERSTATES
  ;; the architecture: the manifest asserts a dependency the production code
  ;; does not have. That is not cosmetic — DECLARED edges are what the cycle
  ;; check reads, so an overstated one can refuse a legitimate declaration
  ;; somewhere else entirely. Which is how these were found: four stood in
  ;; slopp's own manifest and one of them blocked a regroup.
  ;;
  ;; `:unused-edges` structurally cannot see them — something DOES cross.
  (let [sess (external/open!)]
    (try
      ;; gate off, so the fixture's undeclared crossings can land at all
      (swap! sess assoc :adopting? true)
      (ops/ingest! sess 'oa.core "(ns oa.core)\n(defn base \"B.\" [x] x)\n")
      (ops/ingest! sess 'ob.app
                   (str "(ns ob.app (:require [oa.core :as c]))\n"
                        "(defn go \"G.\" [x] (c/base x))\n"))
      (ops/ingest! sess 'oc.util "(ns oc.util)\n(defn helper \"H.\" [x] x)\n")
      ;; a module that is ALL test — od.probe has no production namespace, so
      ;; "only tests cross it" is true of every edge it could ever declare
      (ops/ingest! sess 'od.probe-test
                   (str "(ns od.probe-test (:require [oa.core :as c]\n"
                        "                            [clojure.test :refer [deftest is]]))\n"
                        "(deftest c-t (is (= 1 (c/base 1))))\n"))
      (ops/ingest! sess 'ob.app-test
                   (str "(ns ob.app-test (:require [oc.util :as u]\n"
                        "                          [clojure.test :refer [deftest is]]))\n"
                        "(deftest h-t (is (= 1 (u/helper 1))))\n"))
      (swap! sess dissoc :adopting?)
      (ops/module-dep! sess "ob.app" "oa.core"
                       :prompt "production: the app calls the core")
      (ops/module-dep! sess "ob.app" "oc.util"
                       :prompt "declared for production, but only the -test crosses it")
      (ops/module-dep! sess "od.probe" "oa.core"
                       :prompt "an all-test module's edge — no production code exists to cross it")
      (let [r    (graph/query-depends sess nil :modules true)
            over (set (:overstated-edges r))]
        (testing "the edge only a -test namespace crosses is flagged"
          (is (contains? over ["ob.app" "oc.util"]) (pr-str r)))
        (testing "the edge production code crosses is NOT flagged"
          ;; the must-not-flag half — without it this passes by flagging everything
          (is (not (contains? over ["ob.app" "oa.core"])) (pr-str r)))
        (testing "a module with no production code at all is NOT flagged"
          ;; every edge such a module declares is crossed only by tests, by
          ;; construction — flagging it is noise, not a finding. Measured on
          ;; slopp's own store: 80 rows without this, 4 with it.
          (is (not (contains? over ["od.probe" "oa.core"])) (pr-str r)))
        (testing "and neither reads as unused — something crosses both"
          (is (empty? (filter #{["ob.app" "oc.util"] ["ob.app" "oa.core"]}
                              (:unused-edges r)))
              (pr-str (:unused-edges r)))))
      (finally (ops/close! sess)))))

(deftest an-instrument-module-is-not-on-the-architecture-view
  (let [st   (-> (store/ingest (store/empty-store) 'pv.core "(ns pv.core)\n(defn f [] 1)\n")
                 (store/ingest 'pv.lab (str "(ns pv.lab (:require [pv.core :as core]))\n"
                                            "(defn -main [] (core/f))\n")))
        with (first (store/record-module-role st "pv.lab" :instrument))]
    (testing "the fixture is real — undeclared, pv.lab IS on the view and depends on pv.core"
      (let [m (modules/production-manifest st)]
        (is (contains? m "pv.lab") (pr-str m))
        (is (= #{"pv.core"} (get m "pv.lab")) (pr-str m))))
    (testing "declared :instrument, the module leaves the view entirely"
      (let [m (modules/production-manifest with)]
        (is (not (contains? m "pv.lab")) (pr-str m))
        (is (contains? m "pv.core") "the module it MEASURED is still product code")))
    (testing "and it stops contributing edges, so it cannot sit above what it measures"
      (is (= [["pv.core"]]
             (:layers (store/module-layers (modules/production-manifest with))))
          "one layer, not two — the harness was the apex"))))

(deftest a-namespace-with-nothing-left-in-it-is-named
  ;; The empty-namespace exemption asserted in
  ;; a-namespace-has-to-say-what-it-is-for is CORRECT — a namespace someone
  ;; just created has nothing to describe yet. It is also why
  ;; slopp.web-rules-test survived two days and a green full_check after the
  ;; R6 rules move carried its tests to slopp.rules.web-test.
  ;;
  ;; The exemption is the second reason it was invisible. The first is
  ;; ADDRESSING: every done-advisory is handed changed FORM IDS, and
  ;; sweep-store! builds its whole-store population the same way
  ;; (mapcat store/forms), so a namespace with zero forms is in neither
  ;; population and no rule can reach it however it is written. Hence a
  ;; namespace-grained read here rather than another rule.
  (let [husk  (store/ingest (store/empty-store) 'app.husk "(ns app.husk)\n")
        alive (store/ingest (store/empty-store) 'app.alive
                            "(ns app.alive \"Why it exists.\")\n\n(defn f [] 1)\n")]
    (testing "a namespace holding only its own ns form is named"
      (is (= '[app.husk] (modules/empty-namespaces husk))))
    (testing "a namespace with any form at all is not"
      ;; the unexempted case beside the exempted one, per this ns's own rule:
      ;; a report that had stopped finding anything satisfies the first half
      (is (= [] (modules/empty-namespaces alive))))
    (testing "the husk is found among populated neighbours, not only alone"
      (let [mixed (store/ingest alive 'app.gone "(ns app.gone)\n")]
        (is (= '[app.gone] (modules/empty-namespaces mixed)))))))

(deftest ^:external a-husk-is-named-by-the-whole-store-check
  ;; The WIRING half, split from the derivation above so a future failure
  ;; localizes — that one is the read, this one is whether anything asks it.
  ;; `empty-namespaces` merely existing is not the fix: `module-debt` also
  ;; existed, and being unasked is how four real violations stood through a
  ;; green check (friction #19). A check that is never asked reads exactly
  ;; like a check that passes.
  ;;
  ;; Both assertions are VALUE-shaped rather than absence-shaped, which is
  ;; what keeps the pair honest: with the wiring removed the husk half fails
  ;; on `nil` instead of quietly agreeing.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'fs.alive
                   (str "(ns fs.alive \"Why it exists.\")\n\n"
                        "(defn ^{:unused-ok \"fixture: nothing calls it\"} f\n"
                        "  \"Doc.\"\n"
                        "  []\n"
                        "  1)\n"))
      (testing "the must-NOT-flag half — a populated namespace is no husk"
        (let [r (external/full-check! sess)]
          (is (nil? (:empty-namespaces r)) (pr-str (:empty-namespaces r)))))
      (ops/ingest! sess 'fs.gone "(ns fs.gone)\n")
      (testing "a namespace holding only its ns form is named, with its remedy"
        (let [r (external/full-check! sess)]
          (is (= '[fs.gone] (:empty-namespaces r)) (pr-str r))
          (is (re-find #"ns_delete" (str (:empty-namespaces-note r)))
              (str "a finding whose remedy the reader cannot run is half a"
                   " finding: " (pr-str (:empty-namespaces-note r))))
          (testing "and it does NOT flip the check"
            ;; a namespace someone just created is empty for one write, and a
            ;; whole-store check that goes red on it is a check people stop
            ;; running. Reported, never refused.
            (is (= :green (:status r))
                (pr-str (select-keys r [:status :empty-namespaces]))))))
      (finally (ops/close! sess)))))
