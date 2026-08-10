(ns slopp.effect-boundary-test
  "External deps M3: a call into an opaque Tier-1 dependency is treated as
  EFFECTFUL by default (worst-case — Koka io-top / gradual 'unknown = top'),
  because slopp can't see the dep's body. Narrowable by marking the dep var
  `:pure`. Store/stdlib calls are unaffected. Warnings, never rejections."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops]
            [slopp.edit :as edit] [slopp.read.query :as query] [slopp.ops.external :as external] [slopp.edit.tiers :as tiers])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-eff-test" (make-array FileAttribute 0))))

(defn- warns-about? [sess ns-sym nm]
  (some #(re-find (re-pattern (str "\\b" nm "\\b")) (str %))
        (edit/ns-warnings (:store @sess) ns-sym)))

(deftest ^:external external-dep-call-is-effectful-by-default
  (let [sess (external/open! {:slopp.ops/dir (temp-dir)})]     ; durable → surface is cached
    (try
      (ops/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                     :agent "a")
      ;; dump calls a NON-bang external var (json/write-str) — slopp can't see
      ;; its body, so dump is effectful and should be named dump!
      (ops/ingest! sess 'ex.core
                   (str "(ns ex.core (:require [clojure.data.json :as json]))\n\n"
                        "(defn dump [x] (json/write-str x))\n"))
      (testing "the external call makes the caller effectful (a !-name warning)"
        (is (warns-about? sess 'ex.core 'dump)
            (pr-str (edit/ns-warnings (:store @sess) 'ex.core))))
      (testing "marking the dep var :pure narrows it — no more warning"
        (ops/deps-pure! sess 'clojure.data.json/write-str :agent "a")
        (is (not (warns-about? sess 'ex.core 'dump))))
      (testing "the :pure annotation persists (delta + reopen)"
        (is (contains? (:dep-pure (:store @sess)) 'clojure.data.json/write-str)))
      (finally (ops/close! sess)))))

(deftest ^:external pure-narrows-at-namespace-and-lib-granularity   ; M3 coarser :pure
  ;; slopp is built on wholesale-pure libs (rewrite-clj, clj-kondo); marking
  ;; every var pure one call at a time floods self-host code with warnings, so
  ;; :pure also lands at namespace and whole-dep granularity.
  (let [sess (external/open! {:slopp.ops/dir (temp-dir)})]
    (try
      (ops/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                     :agent "a")
      (ops/ingest! sess 'ex.core
                   (str "(ns ex.core (:require [clojure.data.json :as json]))\n\n"
                        "(defn dump [x] (json/write-str x))\n"))
      (is (warns-about? sess 'ex.core 'dump))
      (testing "marking the whole NAMESPACE pure narrows every var in it"
        (ops/deps-pure! sess 'clojure.data.json :agent "a")
        (is (not (warns-about? sess 'ex.core 'dump)))
        (is (contains? (:dep-pure (:store @sess)) 'clojure.data.json)))
      (testing "un-pure at namespace granularity restores the warning"
        (ops/deps-unpure! sess 'clojure.data.json :agent "a")
        (is (warns-about? sess 'ex.core 'dump)))
      (testing "marking the whole LIB pure expands to its provided namespaces"
        (let [r (ops/deps-pure! sess 'org.clojure/data.json :agent "a")]
          (is (= 'org.clojure/data.json (:lib r)))
          (is (contains? (set (:namespaces r)) 'clojure.data.json)))
        (is (not (warns-about? sess 'ex.core 'dump)))
        (is (contains? (:dep-pure (:store @sess)) 'clojure.data.json)))
      (finally (ops/close! sess)))))

(deftest ^:external reads-suppresses-the-effect-name-warning   ; per-form !-effect override
  ;; A fn that READS through an effectful-by-default external dep is flagged
  ;; effectful (should be `!`). `^:reads` asserts it is a READ, not a mutation,
  ;; so it takes no bang — the Clojure norm (slurp/deref/a SELECT read no bang).
  ;; Greppable + self-limiting, like `^:unsafe` for the dialect gate.
  (let [sess (external/open! {:slopp.ops/dir (temp-dir)})]
    (try
      (ops/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"} :agent "a")
      (ops/ingest! sess 'rd.core
                   (str "(ns rd.core (:require [clojure.data.json :as json]))\n\n"
                        "(defn peek-json [x] (json/read-str x))\n"))
      (testing "a read through an external dep is flagged effectful by default"
        (is (warns-about? sess 'rd.core 'peek-json)))
      (testing "^:reads clears the naming warning"
        (ops/edit-replace! sess 'rd.core 'peek-json
                           "^:reads\n(defn peek-json [x] (json/read-str x))")
        (is (not (warns-about? sess 'rd.core 'peek-json))))
      (testing "query_symbol surfaces :reads? (greppable), form still addressable"
        (let [q (query/query-symbol sess 'rd.core 'peek-json)]
          (is (:reads? q))
          (is (= 'peek-json (:name q)))))
      (finally (ops/close! sess)))))

(deftest ^:external store-and-stdlib-calls-are-not-external
  (let [sess (external/open! {:slopp.ops/dir (temp-dir)})]
    (try
      (ops/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                     :agent "a")
      ;; pure fn using only clojure.core/clojure.string + a store call
      (ops/ingest! sess 'ex.pure
                   (str "(ns ex.pure (:require [clojure.string :as s]))\n\n"
                        "(defn shout [x] (s/upper-case (str x)))\n"))
      (testing "a stdlib-only fn is NOT flagged effectful"
        (is (not (warns-about? sess 'ex.pure 'shout))))
      (finally (ops/close! sess)))))

(deftest ^:external dep-namespaces-persist-and-reopen
  (let [dir (temp-dir)]
    (let [sess (external/open! {:slopp.ops/dir dir})]
      (try
        (ops/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                       :agent "a")
        (ops/deps-pure! sess 'clojure.data.json/write-str :agent "a")
        (is (contains? (get (:dep-ns (:store @sess))
                            'org.clojure/data.json)
                       'clojure.data.json))
        (finally (ops/close! sess))))
    (testing "a reopened session reconstructs :dep-ns and :dep-pure"
      (let [s2 (external/open! {:slopp.ops/dir dir})]
        (try
          (is (contains? (get (:dep-ns (:store @s2)) 'org.clojure/data.json)
                         'clojure.data.json))
          (is (contains? (:dep-pure (:store @s2))
                         'clojure.data.json/write-str))
          (finally (ops/close! s2)))))))

(deftest ^:external declaring-a-tier-verifies-the-code-already-there
  ;; The gap this pins: `:pure` gates only NEW
  ;; writes, so declaring it over an existing module produced a claim nothing
  ;; had verified — a marker that lies. A declaration is an assertion about the
  ;; code, so it has to be checked against the code.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'tv.core
                   (str "(ns tv.core)\n"
                        "(defn ^:unused-ok calc \"Pure.\" [x] (inc x))\n"
                        "(defn ^:unused-ok write! \"Effectful.\" [x] (slurp x))\n"))
      (testing "declaring :pure over an effectful module is REFUSED, and names why"
        (let [r (ops/module-tier! sess "tv.core" :pure :prompt "wishful")]
          (is (:error r) (pr-str r))
          (is (re-find #"tv\.core/write!" (str (:error r))) (str (:error r)))))
      (testing "the tier is NOT recorded — a refused declaration must not land"
        (is (nil? (get (:module-tiers (:store @sess)) "tv.core"))
            (pr-str (:module-tiers (:store @sess)))))
      (testing ":external is always declarable — it asserts nothing"
        (let [r (ops/module-tier! sess "tv.core" :effects :prompt "periphery")]
          (is (nil? (:error r)) (pr-str r))
          (is (= :external (get (:module-tiers (:store @sess)) "tv.core"))
              "legacy :effects in, canonical :external stored")))
      (testing "and a genuinely pure module declares clean"
        (ops/ingest! sess 'tp.core
                     "(ns tp.core)\n(defn ^:unused-ok f \"Pure.\" [x] (* 2 x))\n")
        (let [r (ops/module-tier! sess "tp.core" :pure :prompt "real core")]
          (is (nil? (:error r)) (pr-str r))
          (is (= :pure (get (:module-tiers (:store @sess)) "tp.core")))))
      (finally (ops/close! sess)))))

(deftest ^:external purity-is-declarable-at-namespace-grain
  ;; Measured on slopp itself: slopp.api holds SEVEN fully-pure namespaces
  ;; (shape, breakage, schema, ...) inside an :effects module. At module grain
  ;; the pure core exists but cannot be NAMED — so nothing enforces it and no
  ;; test can rely on it. The most specific declaration wins.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ng.core
                   "(ns ng.core)\n(defn ^:unused-ok boot! \"Edge.\" [p] (slurp p))\n")
      (ops/ingest! sess 'ng.core.calc
                   "(ns ng.core.calc)\n(defn ^:unused-ok add \"Pure.\" [a b] (+ a b))\n")
      (ops/module-tier! sess "ng.core" :effects :prompt "the module has an edge")
      (testing "a pure DEEP namespace declares :pure inside an :effects module"
        (let [r (ops/module-tier! sess "ng.core.calc" :pure :prompt "the core")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "and the deeper declaration WINS for forms in it"
        (let [r (ops/add-form! sess 'ng.core.calc
                               "(defn ^:unused-ok sneak \"Edge.\" [p] (slurp p))"
                               :prompt "an effect in the declared-pure core")]
          (is (:error r) (pr-str r))
          (is (re-find #"ng\.core\.calc" (str (:error r))) (str (:error r)))))
      (testing "while the parent module stays unrestricted"
        (let [r (ops/add-form! sess 'ng.core
                               "(defn ^:unused-ok more! \"Edge.\" [p] (slurp p))"
                               :prompt "effects are fine out here")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "and declaring :pure over an already-effectful deep ns is refused"
        (let [r (ops/module-tier! sess "ng.core" :pure :prompt "wishful")]
          (is (:error r) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external tier-layering-is-reported-by-full-check
  ;; effectful-vars sees a CROSS-NAMESPACE effect only when the callee is
  ;; `!`-named — so a core namespace calling a non-bang effectful fn in a shell
  ;; namespace slips through it entirely. Layering reads the REQUIRE graph, so
  ;; it holds regardless of naming discipline.
  ;;
  ;; It is reported by full_check rather than refusing the declaration: a
  ;; layering verdict CHANGES as legitimate work continues (declare your
  ;; dependencies and the same declaration becomes valid), which is exactly
  ;; the D-rule-grain test for a check that does not belong at write grain.
  ;; Refusing there would also force rigidly bottom-up declaration order.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ly.shell
                   "(ns ly.shell)\n(defn read-cfg \"No bang.\" [p] (slurp p))\n")
      (ops/module-tier! sess "ly.shell" :effects :prompt "the shell")
      (ops/module-dep! sess "ly.core" "ly.shell" :prompt "fixture edge")
      (ops/ingest! sess 'ly.core
                   (str "(ns ly.core (:require [ly.shell :as sh]))\n"
                        "(defn ^:unused-ok load-it \"Looks pure.\" [p] (sh/read-cfg p))\n"))
      (testing "the declaration itself is NOT refused — its verdict could still change"
        (let [r (ops/module-tier! sess "ly.core" :pure :prompt "core, for now")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "but full_check names the core→shell edge effect-reachability missed"
        (let [r (external/full-check! sess)
              v (:tier-layering r)]
          (is (some #(and (= 'ly.core (:ns %)) (= 'ly.shell (:requires %))) v)
              (pr-str v))
          ;; ly.shell DECLARED :effects, so its tier is a claim rather than an
          ;; absence — the control for :requires-undeclared, which otherwise
          ;; could be marking every row and nobody would notice
          (is (not-any? :requires-undeclared v) (pr-str v))
          (is (re-find #"(?i)looser" (str (:tier-layering-note r)))
              (pr-str (:tier-layering-note r)))
          ;; discriminating: `red` proves nothing unless every OTHER red-maker is
          ;; clean. The first version of this assertion passed while the
          ;; layering finding was still purely advisory.
          (is (zero? (:lint-errors r)) (pr-str (:lint r)))
          (is (empty? (:unused-public r)) (pr-str (:unused-public r)))
          (is (empty? (:stale-unused-ok r)) (pr-str (:stale-unused-ok r)))
          (is (zero? (+ (:fail (:test r) 0) (:error (:test r) 0)))
              (pr-str (:test r)))
          (is (= :red (:status r))
              (str "a core→shell dependency must FLIP the check red — a"
                   " finding the agent can scroll past is not a rule: "
                   (pr-str (select-keys r [:status :tier-layering]))))))
      (testing "layering-violations itself: :reads may depend on :pure, not :effects"
        (ops/ingest! sess 'lz.pure "(ns lz.pure)\n(defn ^:unused-ok calc \"P.\" [x] (inc x))\n")
        (ops/module-tier! sess "lz.pure" :pure :prompt "core")
        (ops/module-dep! sess "lz.mid" "lz.pure" :prompt "fixture edge")
        (ops/ingest! sess 'lz.mid
                     (str "(ns lz.mid (:require [lz.pure :as p]))\n"
                          "(defn ^:unused-ok twice \"P.\" [x] (p/calc (p/calc x)))\n"))
        (is (empty? (tiers/layering-violations (:store @sess) 'lz.mid :reads)))
        (is (seq (tiers/layering-violations (:store @sess) 'ly.core :pure))))
      (finally (ops/close! sess)))))

(deftest ^:external full-check-layer-loop-exempts-test-namespaces
  ;; A -test namespace nested UNDER a declared-:pure module inherits :pure via
  ;; tier-for's prefix walk — but it makes no purity claim (tier-violations
  ;; exempts -test at declaration for exactly this reason). full-check!'s layer
  ;; loop iterated ALL namespaces, so the test's legitimate fixture require of
  ;; a shell namespace flipped the whole store red — permanently, on any module
  ;; you declared a tier for.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'pl.core.io
                   "(ns pl.core.io)\n(defn ^:unused-ok read-cfg \"IO.\" [p] (slurp p))\n")
      (ops/module-tier! sess "pl.core.io" :external :prompt "the shell leaf")
      (ops/ingest! sess 'pl.core "(ns pl.core)\n(defn ^:unused-ok f \"P.\" [x] (inc x))\n")
      (ops/module-tier! sess "pl.core" :pure :prompt "pure core")
      ;; a nested test that legitimately exercises the shell leaf
      (ops/ingest! sess 'pl.core.io-test
                   (str "(ns pl.core.io-test (:require [pl.core.io :as io]\n"
                        "                              [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest reads (is (nil? (try (io/read-cfg \"/nope\") (catch Exception _ nil)))))\n"))
      (testing "the nested test's fixture require does not appear as a layering violation"
        (let [r (external/full-check! sess)
              v (:tier-layering r)]
          (is (not-any? #(= 'pl.core.io-test (:ns %)) v)
              (str "a -test namespace was flagged by the layer loop: " (pr-str v)))))
      (finally (ops/close! sess)))))

(deftest ^:external requiring-an-undeclared-ns-from-a-tiered-one-teaches-at-the-write
  ;; frictions #4: wiring a NEW deep ns into a :pure consumer was accepted by
  ;; the write and by done, then full_check went red on :tier-layering two
  ;; gates later — a brand-new ns defaults to :external. The moment the
  ;; declaration is cheap is the write that creates the dependency, so THAT
  ;; result now carries the teaching.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'tw.core "(ns tw.core)\n(defn ^:unused-ok f [x] x)\n")
      (ops/module-tier! sess "tw.core" :pure :prompt "core is pure")
      (ops/ingest! sess 'tw.util "(ns tw.util)\n(defn ^:unused-ok g [x] x)\n")
      (testing "a tiered ns gaining an UNDECLARED dep is taught at the write"
        (let [r (ops/add-require! sess 'tw.core "[tw.util :as u]" :prompt "wire")]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (:tier-note r)) (pr-str (keys r)))
          (is (re-find #"module_purity" (str (:tier-note r))) (:tier-note r))
          (is (re-find #"tw\.util" (str (:tier-note r))) (:tier-note r))))
      (ops/ingest! sess 'tw.led "(ns tw.led)\n(defn ^:unused-ok h [x] x)\n")
      (ops/module-tier! sess "tw.led" :pure :prompt "declared at creation")
      (testing "declared-at-creation keeps the write quiet"
        (let [r (ops/add-require! sess 'tw.core "[tw.led :as l]" :prompt "wire")]
          (is (nil? (:tier-note r)) (pr-str (:tier-note r)))))
      (testing "an untiered consumer stays quiet — nothing to lose"
        (ops/ingest! sess 'tw.free "(ns tw.free)\n(defn ^:unused-ok k [x] x)\n")
        (let [r (ops/add-require! sess 'tw.free "[tw.util :as u]" :prompt "wire")]
          (is (nil? (:tier-note r)) (pr-str (:tier-note r)))))
      (finally (ops/close! sess)))))

(deftest ^:external tier-layering-says-when-the-shell-was-never-declared
  ;; The finding names the namespace whose CLAIM breaks — and that namespace
  ;; is the one with nothing to fix, because it did not change. When the
  ;; required namespace is `:external` only because NOBODY DECLARED IT, the
  ;; actionable move is to declare ITS tier, and the row has to say which of
  ;; the two cases this is: an absence and a deliberate `:external` read
  ;; identically otherwise.
  ;;
  ;; Measured on a split: seven forms left a `:pure` namespace and the new one
  ;; was born undeclared, so the report accused the namespace that had not
  ;; moved. Four times in one session, each read as a fresh mystery.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'lu.helper
                   "(ns lu.helper)\n(defn ^:unused-ok calc \"P.\" [x] (inc x))\n")
      (ops/module-dep! sess "lu.core" "lu.helper" :prompt "fixture edge")
      (ops/ingest! sess 'lu.core
                   (str "(ns lu.core (:require [lu.helper :as h]))\n"
                        "(defn ^:unused-ok go \"P.\" [x] (h/calc x))\n"))
      (is (nil? (:error (ops/module-tier! sess "lu.core" :pure :prompt "core"))))
      (let [r (external/full-check! sess)
            v (first (filter #(= 'lu.core (:ns %)) (:tier-layering r)))]
        (is (some? v) (pr-str (:tier-layering r)))
        (is (= 'lu.helper (:requires v)) (pr-str v))
        (testing "the row says the shell tier is an ABSENCE, not a claim"
          (is (true? (:requires-undeclared v)) (pr-str v)))
        (testing "and the note points at the declaration as a candidate fix"
          (is (re-find #"(?i)declar" (str (:tier-layering-note r)))
              (pr-str (:tier-layering-note r)))))
      (finally (ops/close! sess)))))
