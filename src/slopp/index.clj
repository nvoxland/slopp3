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
            [clj-kondo.core :as kondo] [rewrite-clj.parser :as p] [rewrite-clj.node :as n]))

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

(def ^:private ^:ambient-ok kondo-cache
  "source-string -> {:analysis :findings} memo (bounded). ONE kondo pass
  per unique rendered ns feeds both analyze and lint — every write used
  to run kondo several times over identical source (pre/post warnings,
  affected lookups, AND a separate un-memoized lint pass over the
  unchanged base). Eval round 2 + the post-eval9 wall trace both showed
  these re-runs dominating per-write time on large namespaces."
  (atom {}))

(defn- node
  "Fully-qualified node key for a var: ns/name."
  [ns nm]
  (symbol (str ns) (str nm)))

(defn call-graph
  "Map of caller-node -> #{callee-node} for effect propagation. An edge is added
   for a usage UNLESS it's a `#'var` CARRIER — a non-call reference (kondo marks a
   call with `:arity`) whose position is a var-quote (`:var-quotes`). So a var HELD
   in data (a rule registry carrying `#'some-bang!`) doesn't taint its holder, but
   a CALL `(f …)`, a bare ALIAS `(def g f)`, and a higher-order value-arg
   `(map f xs)` all propagate (conservative — those are callable-as-`f`). Top-level
   usages (`:from-var` nil) are skipped. Reference-tracking for visibility/renames
   is a separate path (`edit.refs`)."
  [analysis]
  (let [vq (or (:var-quotes analysis) #{})]
    (reduce (fn [m u]
              (if (and (:from-var u)
                       (or (contains? u :arity)
                           (not (contains? vq [(:name-row u) (:name-col u)]))))
                (update m (node (:from u) (:from-var u))
                        (fnil conj #{}) (node (:to u) (:name u)))
                m))
            {}
            (:var-usages analysis))))

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
(def ^:private ^:ambient-ok ns-source-hash
  "ns-sym -> hash of the last source we fed kondo for it.

  clj-kondo reads CROSS-NS facts (arities, var existence) from
  `.clj-kondo/.cache`, and WRITES that cache as a side effect of every lint —
  so a source's FINDINGS are not a function of that source alone, and memoizing
  them on source alone replays answers from before a callee moved. (Measured
  2026-07-16: `:analysis` IS cache-independent; only `:findings` are not.)

  This is the missing half of the key. Direct requires suffice: a dep's cache
  entry holds ITS OWN arities, which don't change when the dep's own deps do."
  (atom {}))
(defn- deps-fp
  "Fingerprint of what kondo currently knows about `requires` — the cross-ns
  half of `run-kondo`'s memo key. A dep we have never linted contributes nil
  (stable), which is the honest answer: its `.clj-kondo/.cache` entry, if any,
  predates this process and we cannot account for it."
  [requires]
  (let [known @ns-source-hash]
    (mapv known (sort requires))))
(def ^:ambient-ok kondo-cache-dir
  "Where kondo keeps its CROSS-NS facts (arities, var existence), or nil for
  kondo's own default.

  The default is the killer, which is why this exists: kondo resolves its cache
  from the PROCESS CWD. Probed 2026-07-17 from a cwd with no `.clj-kondo/` —
  exactly a user project's situation — the same source that yields
  `[:invalid-arity]` against this repo's cache yields `[]`. So every cross-ns
  finding worked only because slopp's own repo happens to have a `.clj-kondo/`
  beside it, and a user's `:carried` stale-caller gate silently did nothing,
  failing toward 'no findings'.

  `api/open!` points this at the store's own `.slopp/` so the facts live with
  the code they describe. A slopp-owned dir is self-sufficient — linting a
  namespace teaches it, which is how any kondo cache fills."
  (atom nil))
^:reads (defn- kondo-pass
  "ONE real kondo run over `source`, stored. Also records what this pass just
  taught kondo's cache about `source`'s OWN namespace — that side effect is
  what dependents' fingerprints read, so it must be tracked wherever a pass
  actually happens.

  Runs against `kondo-cache-dir` when set (#134). Unset means kondo resolves
  the cache from the process CWD, which is how cross-ns findings came to
  depend on a `.clj-kondo/` happening to sit next to the process."
  [source]
  (let [dir (some-> @kondo-cache-dir str)
        r (with-in-str source
            (kondo/run! (cond-> {:lint ["-"] :config {:output {:analysis true}}}
                          dir (assoc :cache-dir dir))))
        a (:analysis r)
        requires (into #{} (map :to) (:namespace-usages a))
        own      (-> a :namespace-definitions first :name)
        v {:analysis a
           :ns       own
           :requires requires
           ;; the cross-ns state these FINDINGS are true under: WHICH cache,
           ;; and what it knew about this source's requires
           :cache-dir dir
           :fp       (deps-fp requires)
           :findings (->> (:findings r)
                          (filter #(#{:warning :error} (:level %)))
                          (mapv #(select-keys % [:level :type :message :row :col])))}]
    (when own
      (swap! ns-source-hash assoc own (hash source)))
    (swap! kondo-cache (fn [c] (assoc (if (>= (count c) 256) {} c) source v)))
    v))
^:reads (defn- run-kondo
  "The memoized kondo pass: {:analysis ... :findings ...} for `source`, keyed
  on SOURCE — the honest key for `:analysis`, which is cache-INDEPENDENT
  (measured 2026-07-16: byte-identical with and without `.clj-kondo/.cache`).

  It must stay source-keyed: `edit.refs/static-refs` maps `analyze` over EVERY
  namespace, so keying analysis on the cross-ns fingerprint would re-analyze
  every dependent of any edited namespace — a pass costs ~285ms on a large one.

  `:findings` need a stricter key and get it in `lint`; reading them straight
  off this entry is what made the lint gate stale."
  [source]
  (or (get @kondo-cache source)
      (kondo-pass source)))
^:reads (defn lint
  "clj-kondo FINDINGS for `source` (warnings + errors: [{:level :type :message
  :row :col}]).

  Findings are NOT a function of `source` alone — kondo reads cross-ns facts
  (arities, var existence) from its cache, which other lints rewrite. So the
  memoized entry is only reusable while the world it was computed under still
  holds, which is three things:
  - the same CACHE (`:cache-dir`) — a different cache is a different world
    (#134);
  - the same knowledge of this source's requires (`:fp` vs `deps-fp`);
  - a disk cache still reflecting this source's own namespace — a memo HIT
    skips the pass, and the pass is the side effect that teaches the cache.

  Any mismatch re-passes. `analyze` deliberately keeps the source-only key:
  `:analysis` is cache-INDEPENDENT (measured 2026-07-16) and `edit.refs`
  maps it over EVERY namespace."
  [source]
  (let [ent (run-kondo source)]
    (:findings
     (if (and (= (:cache-dir ent) (some-> @kondo-cache-dir str))
              (= (:fp ent) (deps-fp (:requires ent)))
              (= (get @ns-source-hash (:ns ent)) (hash source)))
       ent
       (kondo-pass source)))))

(def nondeterministic-leaves
  "Core primitives whose result is NOT a function of the args — randomness and
   external reads. These are NOT `!`-effects (D6 scopes `!` to MODIFICATION, and
   reads/non-determinism deliberately take no bang), but they break REFERENTIAL
   TRANSPARENCY, so the `:pure` tier — the strictest, RT core — forbids reaching
   them. Interop non-determinism (`System/currentTimeMillis`, `.nextInt`) is out of
   scope: the analyzer sees core vars, not interop — the same gap D6 has."
  '#{clojure.core/rand clojure.core/rand-int clojure.core/rand-nth
     clojure.core/random-uuid clojure.core/shuffle
     clojure.core/slurp clojure.core/line-seq clojure.core/read-line})

(defn nondeterministic-vars
  "Set of user var nodes that transitively reach a `nondeterministic-leaves`
   anchor — randomness or an external read — over the call graph (monotonic
   fixpoint, cycle-safe; mirrors `effectful-vars`). What the `:pure` tier adds on
   top of the effect check to enforce referential transparency."
  [analysis]
  (let [edges   (call-graph analysis)
        anchor? (fn [t] (contains? nondeterministic-leaves t))]
    (loop [nd (set (for [[n ts] edges :when (some anchor? ts)] n))]
      (let [nd' (into nd (for [[n ts] edges :when (some nd ts)] n))]
        (if (= nd nd') nd (recur nd'))))))

(defn var-quote-positions
  "The positions `[row col]` of the SYMBOL inside every `#'var` var-quote in
   `source` — these align with kondo's `:name-row`/`:name-col` for the usage. Lets
   `call-graph` exclude a var HELD as a carrier (a `#'var` in data, e.g. a rule
   registry) from effect propagation while KEEPING a bare value reference — an
   alias `(def g effectful-fn)` or a higher-order arg `(map effectful-fn xs)` — which
   is callable-as-the-var and must stay conservatively effectful."
  [source]
  (let [root (try (p/parse-string-all source) (catch Exception _ nil))]
    (if-not root
      #{}
      (into #{}
            (comp (filter #(= :var (n/tag %)))
                  (keep (fn [vq]
                          (let [m (meta (first (n/children vq)))]
                            (when (and (:row m) (:col m)) [(:row m) (:col m)])))))
            (tree-seq n/inner? n/children root)))))
^:reads (defn analyze
  "clj-kondo's `:analysis` ({:var-definitions :var-usages
  :namespace-definitions :namespace-usages}) for `source`, from the shared
  memoized kondo pass (run-kondo) that also feeds `lint`, plus `:var-quotes`
  (the `#'var` carrier positions) so the effect `call-graph` can tell a var HELD
  in data from a var CALLED."
  [source]
  (assoc (:analysis (run-kondo source))
         :var-quotes (var-quote-positions source)))

