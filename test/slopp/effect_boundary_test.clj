(ns slopp.effect-boundary-test
  "External deps M3: a call into an opaque Tier-1 dependency is treated as
  EFFECTFUL by default (worst-case — Koka io-top / gradual 'unknown = top'),
  because slopp can't see the dep's body. Narrowable by marking the dep var
  `:pure`. Store/stdlib calls are unaffected. Warnings, never rejections."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.edit :as edit] [slopp.edit.modules :as edit.modules])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-eff-test" (make-array FileAttribute 0))))

(defn- warns-about? [sess ns-sym nm]
  (some #(re-find (re-pattern (str "\\b" nm "\\b")) (str %))
        (edit/ns-warnings (:store @sess) ns-sym)))

(deftest ^:isolated external-dep-call-is-effectful-by-default
  (let [sess (api/open! {:slopp.api/dir (temp-dir)})]     ; durable → surface is cached
    (try
      (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                     :agent "a")
      ;; dump calls a NON-bang external var (json/write-str) — slopp can't see
      ;; its body, so dump is effectful and should be named dump!
      (api/ingest! sess 'ex.core
                   (str "(ns ex.core (:require [clojure.data.json :as json]))\n\n"
                        "(defn dump [x] (json/write-str x))\n"))
      (testing "the external call makes the caller effectful (a !-name warning)"
        (is (warns-about? sess 'ex.core 'dump)
            (pr-str (edit/ns-warnings (:store @sess) 'ex.core))))
      (testing "marking the dep var :pure narrows it — no more warning"
        (api/deps-pure! sess 'clojure.data.json/write-str :agent "a")
        (is (not (warns-about? sess 'ex.core 'dump))))
      (testing "the :pure annotation persists (delta + reopen)"
        (is (contains? (:dep-pure (:store @sess)) 'clojure.data.json/write-str)))
      (finally (api/close! sess)))))

(deftest ^:isolated pure-narrows-at-namespace-and-lib-granularity   ; M3 coarser :pure
  ;; slopp is built on wholesale-pure libs (rewrite-clj, clj-kondo); marking
  ;; every var pure one call at a time floods self-host code with warnings, so
  ;; :pure also lands at namespace and whole-dep granularity.
  (let [sess (api/open! {:slopp.api/dir (temp-dir)})]
    (try
      (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                     :agent "a")
      (api/ingest! sess 'ex.core
                   (str "(ns ex.core (:require [clojure.data.json :as json]))\n\n"
                        "(defn dump [x] (json/write-str x))\n"))
      (is (warns-about? sess 'ex.core 'dump))
      (testing "marking the whole NAMESPACE pure narrows every var in it"
        (api/deps-pure! sess 'clojure.data.json :agent "a")
        (is (not (warns-about? sess 'ex.core 'dump)))
        (is (contains? (:dep-pure (:store @sess)) 'clojure.data.json)))
      (testing "un-pure at namespace granularity restores the warning"
        (api/deps-unpure! sess 'clojure.data.json :agent "a")
        (is (warns-about? sess 'ex.core 'dump)))
      (testing "marking the whole LIB pure expands to its provided namespaces"
        (let [r (api/deps-pure! sess 'org.clojure/data.json :agent "a")]
          (is (= 'org.clojure/data.json (:lib r)))
          (is (contains? (set (:namespaces r)) 'clojure.data.json)))
        (is (not (warns-about? sess 'ex.core 'dump)))
        (is (contains? (:dep-pure (:store @sess)) 'clojure.data.json)))
      (finally (api/close! sess)))))

(deftest ^:isolated reads-suppresses-the-effect-name-warning   ; per-form !-effect override
  ;; A fn that READS through an effectful-by-default external dep is flagged
  ;; effectful (should be `!`). `^:reads` asserts it is a READ, not a mutation,
  ;; so it takes no bang — the Clojure norm (slurp/deref/a SELECT read no bang).
  ;; Greppable + self-limiting, like `^:unsafe` for the dialect gate.
  (let [sess (api/open! {:slopp.api/dir (temp-dir)})]
    (try
      (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"} :agent "a")
      (api/ingest! sess 'rd.core
                   (str "(ns rd.core (:require [clojure.data.json :as json]))\n\n"
                        "(defn peek-json [x] (json/read-str x))\n"))
      (testing "a read through an external dep is flagged effectful by default"
        (is (warns-about? sess 'rd.core 'peek-json)))
      (testing "^:reads clears the naming warning"
        (api/edit-replace! sess 'rd.core 'peek-json
                           "^:reads\n(defn peek-json [x] (json/read-str x))")
        (is (not (warns-about? sess 'rd.core 'peek-json))))
      (testing "query_symbol surfaces :reads? (greppable), form still addressable"
        (let [q (api/query-symbol sess 'rd.core 'peek-json)]
          (is (:reads? q))
          (is (= 'peek-json (:name q)))))
      (finally (api/close! sess)))))

(deftest ^:isolated store-and-stdlib-calls-are-not-external
  (let [sess (api/open! {:slopp.api/dir (temp-dir)})]
    (try
      (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                     :agent "a")
      ;; pure fn using only clojure.core/clojure.string + a store call
      (api/ingest! sess 'ex.pure
                   (str "(ns ex.pure (:require [clojure.string :as s]))\n\n"
                        "(defn shout [x] (s/upper-case (str x)))\n"))
      (testing "a stdlib-only fn is NOT flagged effectful"
        (is (not (warns-about? sess 'ex.pure 'shout))))
      (finally (api/close! sess)))))

(deftest ^:isolated dep-namespaces-persist-and-reopen
  (let [dir (temp-dir)]
    (let [sess (api/open! {:slopp.api/dir dir})]
      (try
        (api/deps-add! sess 'org.clojure/data.json {:mvn/version "2.5.0"}
                       :agent "a")
        (api/deps-pure! sess 'clojure.data.json/write-str :agent "a")
        (is (contains? (get (:dep-ns (:store @sess))
                            'org.clojure/data.json)
                       'clojure.data.json))
        (finally (api/close! sess))))
    (testing "a reopened session reconstructs :dep-ns and :dep-pure"
      (let [s2 (api/open! {:slopp.api/dir dir})]
        (try
          (is (contains? (get (:dep-ns (:store @s2)) 'org.clojure/data.json)
                         'clojure.data.json))
          (is (contains? (:dep-pure (:store @s2))
                         'clojure.data.json/write-str))
          (finally (api/close! s2)))))))

(deftest ^:isolated declaring-a-tier-verifies-the-code-already-there
  ;; The gap recorded in ideas/functional-core-gate.md: `:pure` gates only NEW
  ;; writes, so declaring it over an existing module produced a claim nothing
  ;; had verified — a marker that lies. A declaration is an assertion about the
  ;; code, so it has to be checked against the code.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'tv.core
                   (str "(ns tv.core)\n"
                        "(defn ^:unused-ok calc \"Pure.\" [x] (inc x))\n"
                        "(defn ^:unused-ok write! \"Effectful.\" [x] (slurp x))\n"))
      (testing "declaring :pure over an effectful module is REFUSED, and names why"
        (let [r (api/module-tier! sess "tv.core" :pure :prompt "wishful")]
          (is (:error r) (pr-str r))
          (is (re-find #"tv\.core/write!" (str (:error r))) (str (:error r)))))
      (testing "the tier is NOT recorded — a refused declaration must not land"
        (is (nil? (get (:module-tiers (:store @sess)) "tv.core"))
            (pr-str (:module-tiers (:store @sess)))))
      (testing ":effects is always declarable — it asserts nothing"
        (let [r (api/module-tier! sess "tv.core" :effects :prompt "periphery")]
          (is (nil? (:error r)) (pr-str r))
          (is (= :effects (get (:module-tiers (:store @sess)) "tv.core")))))
      (testing "and a genuinely pure module declares clean"
        (api/ingest! sess 'tp.core
                     "(ns tp.core)\n(defn ^:unused-ok f \"Pure.\" [x] (* 2 x))\n")
        (let [r (api/module-tier! sess "tp.core" :pure :prompt "real core")]
          (is (nil? (:error r)) (pr-str r))
          (is (= :pure (get (:module-tiers (:store @sess)) "tp.core")))))
      (finally (api/close! sess)))))

(deftest ^:isolated purity-is-declarable-at-namespace-grain
  ;; Measured on slopp itself: slopp.api holds SEVEN fully-pure namespaces
  ;; (shape, breakage, schema, ...) inside an :effects module. At module grain
  ;; the pure core exists but cannot be NAMED — so nothing enforces it and no
  ;; test can rely on it. The most specific declaration wins.
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ng.core
                   "(ns ng.core)\n(defn ^:unused-ok boot! \"Edge.\" [p] (slurp p))\n")
      (api/ingest! sess 'ng.core.calc
                   "(ns ng.core.calc)\n(defn ^:unused-ok add \"Pure.\" [a b] (+ a b))\n")
      (api/module-tier! sess "ng.core" :effects :prompt "the module has an edge")
      (testing "a pure DEEP namespace declares :pure inside an :effects module"
        (let [r (api/module-tier! sess "ng.core.calc" :pure :prompt "the core")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "and the deeper declaration WINS for forms in it"
        (let [r (api/add-form! sess 'ng.core.calc
                               "(defn ^:unused-ok sneak \"Edge.\" [p] (slurp p))"
                               :prompt "an effect in the declared-pure core")]
          (is (:error r) (pr-str r))
          (is (re-find #"ng\.core\.calc" (str (:error r))) (str (:error r)))))
      (testing "while the parent module stays unrestricted"
        (let [r (api/add-form! sess 'ng.core
                               "(defn ^:unused-ok more! \"Edge.\" [p] (slurp p))"
                               :prompt "effects are fine out here")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "and declaring :pure over an already-effectful deep ns is refused"
        (let [r (api/module-tier! sess "ng.core" :pure :prompt "wishful")]
          (is (:error r) (pr-str r))))
      (finally (api/close! sess)))))

(deftest ^:isolated tier-layering-is-reported-by-full-check
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
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ly.shell
                   "(ns ly.shell)\n(defn ^:unused-ok read-cfg \"No bang.\" [p] (slurp p))\n")
      (api/module-tier! sess "ly.shell" :effects :prompt "the shell")
      (api/module-dep! sess "ly.core" "ly.shell" :prompt "fixture edge")
      (api/ingest! sess 'ly.core
                   (str "(ns ly.core (:require [ly.shell :as sh]))\n"
                        "(defn ^:unused-ok load-it \"Looks pure.\" [p] (sh/read-cfg p))\n"))
      (testing "the declaration itself is NOT refused — its verdict could still change"
        (let [r (api/module-tier! sess "ly.core" :pure :prompt "core, for now")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "but full_check names the core→shell edge effect-reachability missed"
        (let [r (api/full-check! sess)
              v (:tier-layering r)]
          (is (some #(and (= 'ly.core (:ns %)) (= 'ly.shell (:requires %))) v)
              (pr-str v))
          (is (re-find #"(?i)looser" (str (:tier-layering-note r)))
              (pr-str (:tier-layering-note r)))))
      (testing "layering-violations itself: :reads may depend on :pure, not :effects"
        (api/ingest! sess 'lz.pure "(ns lz.pure)\n(defn ^:unused-ok calc \"P.\" [x] (inc x))\n")
        (api/module-tier! sess "lz.pure" :pure :prompt "core")
        (api/module-dep! sess "lz.mid" "lz.pure" :prompt "fixture edge")
        (api/ingest! sess 'lz.mid
                     (str "(ns lz.mid (:require [lz.pure :as p]))\n"
                          "(defn ^:unused-ok twice \"P.\" [x] (p/calc (p/calc x)))\n"))
        (is (empty? (edit.modules/layering-violations (:store @sess) 'lz.mid :reads)))
        (is (seq (edit.modules/layering-violations (:store @sess) 'ly.core :pure))))
      (finally (api/close! sess)))))
