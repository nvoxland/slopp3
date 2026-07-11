(ns slopp.index
  "Static semantic index over rendered source via clj-kondo (content-fed through
  stdin — no disk, C1/C6): var definitions, references, the call graph, and the
  `!`-effect analysis (D6).

  `!`-effect (D6): a var is effectful iff it can transitively reach a known
  effectful primitive through the call graph — a sound, decidable check for
  first-order code (higher-order/effect-polymorphic cases are left to the live
  oracle, D5). The dialect requires the `!` in a var's name to match its computed
  effectfulness; mismatches are reported for the edit pipeline to flag/auto-fix."
  (:require [clojure.string :as str]
            [clj-kondo.core :as kondo]))

(def effectful-leaves
  "Fixed anchor set: core primitives that modify in-process or external state
  (D6 scope = modification). Extensible; reads / non-determinism are NOT here."
  '#{clojure.core/swap! clojure.core/reset! clojure.core/swap-vals!
     clojure.core/reset-vals! clojure.core/compare-and-set!
     clojure.core/vreset! clojure.core/vswap!
     clojure.core/alter clojure.core/ref-set clojure.core/alter-var-root
     clojure.core/commute clojure.core/send clojure.core/send-off
     clojure.core/deliver clojure.core/conj! clojure.core/disj!
     clojure.core/assoc! clojure.core/dissoc! clojure.core/pop!
     clojure.core/spit clojure.core/delete-file})

(def ^:private analysis-cache
  "source-string -> :analysis memo (bounded). Every write triggers several
  analyses of identical rendered source (pre/post warnings, affected lookups);
  eval round 2 showed the re-runs dominating per-write wall time."
  (atom {}))

^:reads (defn analyze
  "Run clj-kondo over `source` (fed via stdin, no disk); return its `:analysis`
  ({:var-definitions :var-usages :namespace-definitions :namespace-usages}).
  Memoized on the source string (bounded)."
  [source]
  (or (get @analysis-cache source)
      (let [an (:analysis
                (with-in-str source
                  (kondo/run! {:lint ["-"] :config {:output {:analysis true}}})))]
        (swap! analysis-cache
               (fn [c] (assoc (if (>= (count c) 32) {} c) source an)))
        an)))

(defn- node
  "Fully-qualified node key for a var: ns/name."
  [ns nm]
  (symbol (str ns) (str nm)))

(defn call-graph
  "Map of caller-node -> #{callee-node}, over user vars (top-level usages, whose
  `:from-var` is nil, are skipped)."
  [analysis]
  (reduce (fn [m u]
            (if (:from-var u)
              (update m (node (:from u) (:from-var u))
                      (fnil conj #{}) (node (:to u) (:name u)))
              m))
          {}
          (:var-usages analysis)))

(defn- bang-target?
  "A call target whose NAME is bang-marked counts as an effectful anchor (D6:
  '...or another ! function') — this is what carries effectfulness across
  namespace boundaries, since analysis is per-namespace (N1)."
  [target]
  (str/ends-with? (name target) "!"))

(defn effectful-vars
  "Set of user var nodes that transitively reach an effectful anchor (D6).
  An anchor is a `!`-leaf, a bang-named callee, OR (M3) a call into an OPAQUE
  external dependency — a target whose namespace `external-ns?` accepts and
  which `pure-vars` does not cover (worst-case: slopp can't see the dep's body,
  so the call is effectful unless the author asserts it pure). `pure-vars` is
  matched at TWO granularities: the fully-qualified var (`ext.lib/go`) OR its
  bare namespace (`ext.lib`) — so a whole pure library can be narrowed without
  enumerating every var. Monotonic fixpoint — cycle-safe. 1-arg = the pre-M3
  behavior (no external boundary)."
  ([analysis] (effectful-vars analysis nil nil))
  ([analysis external-ns? pure-vars]
   (let [edges (call-graph analysis)
         ext?  (or external-ns? (constantly false))
         pure  (or pure-vars #{})
         pure? (fn [t] (or (contains? pure t)
                           (contains? pure (some-> (namespace t) symbol))))
         anchor? (fn [t]
                   (or (effectful-leaves t)
                       (bang-target? t)
                       (and (ext? (some-> (namespace t) symbol))
                            (not (pure? t)))))]
     (loop [eff (set (for [[n ts] edges :when (some anchor? ts)] n))]
       (let [eff' (into eff (for [[n ts] edges :when (some eff ts)] n))]
         (if (= eff eff') eff (recur eff')))))))

(defn- bang? [nm] (str/ends-with? (str nm) "!"))

(defn test-definition?
  "Is this var-definition a test (deftest)? Tests are exempt from the `!`
  naming rule (T1) — they routinely exercise effectful code but are never
  bang-named by convention."
  [d]
  (= 'clojure.test/deftest (:defined-by d)))

(defn effect-violations
  "Vars that are computed effectful (D6) but NOT `!`-named — the one actionable
  direction: name it `!`. Each {:var node :effectful? true :named-bang? false
  :suggest name!}. `external-ns?`/`pure-vars` (M3) extend the anchors to
  opaque-dependency calls. Exemptions:
  - `deftest` vars (T1) — tests exercise effects but are never banged.
  - `-main` — an effectful entry point, never banged by convention.
  - the REVERSE direction (banged but computed pure) is NOT reported: a `!` is a
    human assertion of effectfulness, and the analyzer can't see interop/opaque
    effects (`.close`, a socket/JGit write), so it must not demand the `!` be
    removed. Only a MISSING `!` is a real signal."
  ([analysis] (effect-violations analysis nil nil))
  ([analysis external-ns? pure-vars]
   (let [eff (effectful-vars analysis external-ns? pure-vars)]
     (for [d (:var-definitions analysis)
           :when (not (test-definition? d))
           :when (not= '-main (:name d))
           :let [n (node (:ns d) (:name d))]
           :when (and (contains? eff n) (not (bang? (:name d))))]
       {:var n :effectful? true :named-bang? false
        :suggest (str (:name d) "!")}))))

^:reads (defn analyze-with-locals
  "Like `analyze`, but including local-binding definitions and usages
  (`:locals` / `:local-usages`, linked by `:id`) — the basis for free-variable
  computation in structural extraction."
  [source]
  (:analysis
   (with-in-str source
     (kondo/run! {:lint ["-"]
                  :config {:analysis {:locals true} :output {:analysis true}}}))))

^:reads (defn lint
  "clj-kondo FINDINGS for `source` (syntax + best-practice violations, distinct
  from the :analysis extraction): [{:level :type :message :row :col} ...],
  warnings and errors only."
  [source]
  (->> (:findings (with-in-str source (kondo/run! {:lint ["-"]})))
       (filter #(#{:warning :error} (:level %)))
       (mapv #(select-keys % [:level :type :message :row :col]))))

(defn references
  "Usages of `to-ns/to-name` — who references this var."
  [analysis to-ns to-name]
  (for [u (:var-usages analysis)
        :when (and (= to-ns (:to u)) (= to-name (:name u)))]
    {:from-ns (:from u) :from-var (:from-var u) :row (:row u) :col (:col u)}))
(defn forward-refs
  "Same-namespace var usages positioned BEFORE the var's first definition or
  declare — code a LIVE image hot-loads happily (the var already exists) but
  a FRESH namespace load cannot resolve (boot, restart, a new image). The
  cold-load half of the compile gate. Position is (row, col) lexicographic —
  forms can share a line. Returns [{:symbol :row :col :form :def-row}].
  Known over-approximation: a syntax-quoted own-ns symbol counts as a usage
  (kondo doesn't distinguish it) — a declare satisfies the gate there too."
  [analysis ns-sym]
  (let [before? (fn [[r1 c1] [r2 c2]]
                  (or (< r1 r2) (and (= r1 r2) (< c1 c2))))
        dpos    (reduce (fn [m d]
                          (let [p [(:row d) (:col d)]]
                            (update m (:name d)
                                    #(if (and % (before? % p)) % p))))
                        {}
                        (filter #(= ns-sym (:ns %)) (:var-definitions analysis)))]
    (vec (for [u (:var-usages analysis)
               :when (and (= ns-sym (:to u)) (= ns-sym (:from u)))
               :let [d (get dpos (:name u))]
               :when (and d (before? [(:row u) (:col u)] d))]
           {:symbol  (:name u)
            :row     (:row u)
            :col     (:col u)
            :form    (:from-var u)
            :def-row (first d)}))))
