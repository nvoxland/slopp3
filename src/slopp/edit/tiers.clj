(ns slopp.edit.tiers
  "Purity TIERS and the layering they imply — the write-time half.

  A tier is a claim a namespace makes about itself: `:pure` (referentially
  transparent), `:internal` (may mutate in-process state and nothing outside
  it), `:external` (IO). `tier-refusal` judges a candidate form against its
  namespace's declared tier; `layering-violations` and `tier-violations` ask
  the whole-graph question the per-form gate cannot — whether a namespace
  depends on one at a LOOSER tier, which makes its own claim unearned.

  The axis is internal/external because that is what decides how a thing must
  be TESTED: external needs isolation, internal needs a state reset, pure
  needs nothing.

  Two things worth knowing before touching this. UNDECLARED is `:external`,
  by absence of a claim rather than by judgement — so a namespace that has
  never declared is ungated, and a namespace born from a MOVE has never
  declared. And the per-form gate deliberately does NOT check layering:
  that verdict changes as legitimate work continues, so `full_check` owns it
  and a wrong tier is allowed to stand until then."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.index.analyze :as analyze]
            [slopp.index.derive :as derive]
            [slopp.store :as store]
            [slopp.store.fields :as fields]
            [slopp.store.render :as render]))

(defn ^:export canonical-tier
  "Canonical spelling of a purity tier — delegates to the registry's ONE
  mapping (slopp.store.fields/canonical-tier). Normalize at every boundary
  that READS a recorded tier — deltas legitimately carry the old spellings
  verbatim (history is honest; fold state is canonical)."
  [tier]
  (fields/canonical-tier tier))

(defn ^:export tier-declared?
  "True when ANY tier declaration covers `ns-sym` — itself or an enclosing
  prefix. Distinct from tier-for, which answers :external for the
  undeclared: the distinction matters exactly once, at the write that makes
  a TIERED ns depend on a new one — the moment a declaration is cheap and
  the context is loaded (add-require!'s teaching)."
  [store ns-sym]
  (let [tiers (:module-tiers store)
        segs  (str/split (str ns-sym) #"\.")]
    (boolean (some #(get tiers (str/join "." (take % segs)))
                   (range (count segs) 0 -1)))))

(defn ^:export tier-for
  "The purity tier governing `ns-sym`: the MOST SPECIFIC declaration wins —
   the namespace itself, then each enclosing prefix, then its module, then
   `:external` (undeclared = unrestricted). Always answers in the canonical
   vocabulary; stores that predate the :internal/:external rename may carry
   :reads/:effects and those normalize here.

   Namespace grain exists because a pure core routinely lives one level BELOW
   an effectful module. Measured on slopp itself: `slopp.api` holds seven
   fully-pure namespaces (`shape`, `breakage`, `schema` …) while the module as
   a whole reaches effects. At module grain that core cannot be NAMED, so
   nothing enforces it and no test can rely on it — which is precisely what
   keeps its tests session-bound when they need not be."
  [store ns-sym]
  (let [tiers (:module-tiers store)
        segs  (str/split (str ns-sym) #"\.")]
    ;; down to 1, not 2: a single-segment namespace (`pcore`) has one
    ;; prefix, and stopping at 2 made its declaration unreachable — the
    ;; gate silently stopped firing for it.
    (canonical-tier
     (or (some #(get tiers (str/join "." (take % segs)))
               (range (count segs) 0 -1))
         :external))))

(defn ^:export tier-report
  "Which purity tier `ns-sym`'s CURRENT forms could support, and what blocks a
  stricter one — `tier-refusal`'s gate run as a REPORT over existing code
  instead of as a refusal on a write.

  Declaring a tier is otherwise blind: `module_purity` accepts any tier and the
  gate only bites on the NEXT write, so a wrong call lands on whoever edits
  next rather than on whoever made it. This says where the code actually
  stands before you assert anything about it.

  Returns `{:tier <declared> :supports :pure|:internal|:external :blocking
  {...}}` — `:blocking :pure` lists this namespace's forms reaching an effect,
  non-determinism, or CONSOLE OUTPUT (the three axes `:pure` forbids),
  `:blocking :internal` those reaching OUTSIDE the process (IO, opaque external
  deps). Same classification as `tier-refusal`, and the answer is canonical
  whatever spelling the store carries.

  A MIGRATION aid: the end state is these violations being refused at write
  time, at which point a standing report has no one left to inform."
  [store ns-sym]
  (let [analysis (analyze/analyze (render/render-ns store ns-sym))
        dep-nses (into #{} (mapcat identity) (vals (:dep-ns store)))
        eff-any  (derive/effectful-vars analysis dep-nses (:dep-pure store))
        eff-ext  (derive/externally-effectful-vars analysis dep-nses (:dep-pure store))
        nondet   (derive/nondeterministic-vars analysis)
        console  (derive/console-vars analysis)
        here?    #(= (str ns-sym) (namespace %))
        blocking (fn [vs] (vec (sort (filter here? vs))))
        b-pure   (blocking (into (set eff-any) (concat nondet console)))
        b-int    (blocking eff-ext)]
    {;; tier-for is THE producer of "which tier governs this namespace" —
     ;; namespace grain, most-specific declaration wins. Re-deriving it at
     ;; MODULE grain here disagreed with it on 28 of slopp's own 75
     ;; production namespaces, and in the direction that matters: a
     ;; namespace whose OWN declaration exists precisely because a fold
     ;; mis-governed it read as the fold's tier anyway.
     :tier     (tier-for store ns-sym)
     :supports (cond (empty? b-pure) :pure
                     (empty? b-int)  :internal
                     :else           :external)
     :blocking (cond-> {}
                 (seq b-pure) (assoc :pure b-pure)
                 (seq b-int)  (assoc :internal b-int))}))

(defn ^:export ^{:rule/applies-to :production} tier-refusal
  "The per-form functional-core gate over the CANDIDATE store (D9): refuses a
   form whose reachability exceeds its namespace's declared tier.

   - `:pure` rejects ANY effect (including an opaque-dep read) AND any
     NON-DETERMINISM (`rand`/`slurp`) — a pure core must be referentially
     transparent, not merely mutation-free. That is what lets the generative
     schema oracle run on it at all.
   - `:internal` rejects only what leaves the PROCESS — file/subprocess/network
     IO and opaque external-dep calls. In-process mutation (a memo, a
     registry) is allowed: it is resettable, invisible outside, and needs no
     test isolation.
   - `:external` — or an undeclared namespace — is unrestricted.

   `:reads` is accepted as a legacy spelling of `:internal` and `:effects` of
   `:external`; both retire (see `tier-order` for why `:reads` measured zero).

   Built on `index/effectful-vars`, `index/externally-effectful-vars` and
   `index/nondeterministic-vars`, so it inherits D6's single-ns,
   bang-name-propagating soundness: a CROSS-namespace effect is seen only when
   the callee is `!`-named. That bound is why the blessed cache accessor
   (`slopp.cache/cached`) is deliberately NOT bang-named — memoizing must not
   make every caller effectful. The graph-level check
   (`layering-violations`) is what covers the gap.

   Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (let [tier (tier-for candidate ns-sym)]
    (when (not= tier :external)
      (let [analysis (analyze/analyze (render/render-ns candidate ns-sym))
            dep-nses (into #{} (mapcat identity) (vals (:dep-ns candidate)))
            eff      (if (= tier :pure)
                       (derive/effectful-vars analysis dep-nses (:dep-pure candidate))
                       (derive/externally-effectful-vars analysis dep-nses (:dep-pure candidate)))
            nondet   (when (= tier :pure) (derive/nondeterministic-vars analysis))
            console  (when (= tier :pure) (derive/console-vars analysis))
            vnode    (symbol (str ns-sym) (str form-name))]
        (cond
          (contains? eff vnode)
          (str ns-sym "/" form-name " reaches "
               (if (= tier :pure) "an effect" "OUTSIDE this process (IO)")
               " but " ns-sym " is governed by :" (name tier)
               " (functional-core gate) — move it into an :external namespace"
               (when (= tier :pure)
                 ", or to :internal if it only mutates in-process state")
               ", or loosen the tier with module_purity {module \""
               ns-sym "\" tier :" (if (= tier :pure) "internal" "external")
               "} (say why)")

          (and nondet (contains? nondet vnode))
          (str ns-sym "/" form-name " reaches non-determinism (rand/slurp) but"
               " " ns-sym " is declared :pure — a pure core must be"
               " referentially transparent (deterministic in its args), which"
               " is what lets the generative schema check run on it. Move the"
               " non-determinism to an :external namespace, or loosen the"
               " tier with module_purity {module \"" ns-sym
               "\" tier :internal} (say why)")

          (and console (contains? console vnode))
          (str ns-sym "/" form-name " reaches CONSOLE OUTPUT (println/prn) but"
               " " ns-sym " is declared :pure — printing is an observable side"
               " effect, so a referentially transparent core cannot do it."
               " Return the string and let a caller print it, or loosen the"
               " tier with module_purity {module \"" ns-sym
               "\" tier :internal} (say why). Note this does NOT ask for a `!`"
               " name: `!` means MUTATION, and printing is not one"))))))

(def ^:export tier-order
  "Purity tiers, strictest to loosest. A namespace may only require namespaces
   at its OWN tier or stricter — core never depends on the edge.

   - `:pure`     — referentially transparent. No mutation, no non-determinism.
   - `:internal` — may mutate IN-PROCESS state (a memo, a registry); touches
                   nothing outside the process.
   - `:external` — may do IO: files, subprocesses, network, the database.

   `:reads` (read-yes/write-no) was RETIRED: measured across this whole store
   it had **zero** members — 6 pure, 0 reads, 19 effects — because the
   read/write axis puts a memo `swap!` in the same class as a `git push`.
   internal/external carves the code where it actually divides, and it is the
   axis that decides how a thing must be TESTED: external needs isolation
   (fresh JVM, temp dirs), internal needs only a state reset, pure needs
   nothing. `:effects` is accepted as a legacy spelling of `:external`.

   Exported because comparing tiers is not `edit`'s private business — the
   done-time shell-widening advisory asks whether a tier got LOOSER."
  {:pure 0 :internal 1 :external 2})

(defn late-ref-target-nses
  "Target namespaces `ns-sym` reaches through `(store/late-ref 'ns/name)` — a
  disguised require the ns-form's `:require` clause deliberately omits (to
  break a load cycle). Layering must count them, or a core namespace can
  reach the shell through a carrier invisible to the require graph. Matched by
  the carrier NAME (`late-ref`) regardless of alias, like `refs/carrier-refs`."
  [store ns-sym]
  (into #{}
        (for [e    (store/forms store ns-sym)
              :when (:name e)
              node  (tree-seq coll? seq
                              (try (n/sexpr (:node e)) (catch Exception _ nil)))
              :when (and (seq? node) (symbol? (first node))
                         (= "late-ref" (name (first node))))
              a     (rest node)
              :when (and (seq? a) (= 'quote (first a))
                         (symbol? (second a)) (namespace (second a)))]
          (symbol (namespace (second a))))))

(defn ^:export layering-violations
  "Namespaces required by `ns-sym` that reach OUTSIDE the process while
   `ns-sym` claims not to, as `[{:requires :tier} …]`. Empty when it layers.

   The rule is EXTERNALITY, not tier ordering: a non-`:external` namespace may
   not require an `:external` one. `:pure` MAY depend on `:internal` — an
   in-process memo is observationally pure from outside, and forbidding it
   would mean the pure core could use no memoized helper at all, which in this
   codebase means no pure core at all. (The coupling is real but bounded: a
   `:pure` namespace is then only as referentially transparent as its
   dependency's cache keys are correct. That is a bug in the cache, not a
   layering error — and it is why caches go through `slopp.cache`.)

   This is the check `tier-refusal` cannot make. Effect-reachability sees a
   CROSS-NAMESPACE effect only when the callee is `!`-named (D6's documented
   soundness bound), so a core namespace calling a non-bang IO fn in an edge
   namespace slips through it entirely. Layering reads the REQUIRE graph, so
   it holds regardless of naming discipline — AND the `(store/late-ref …)`
   carrier graph, so a disguised require into the shell cannot slip past it
   either (the dialect gate routes agents to late-ref for load cycles)."
  [store ns-sym tier]
  (let [norm canonical-tier
        mine (norm tier)]
    (if (= mine :external)
      []
      (let [targets (into (set (store/ns-requires store ns-sym))
                          (late-ref-target-nses store ns-sym))]
        (vec (for [req  (sort targets)
                   :let [rt (norm (tier-for store req))]
                   :when (= rt :external)]
               {:requires req :tier rt}))))))

(defn ^:export tier-violations
  "The forms ALREADY in `module` that would violate `tier`, as
   `[{:form :why} …]` — empty when the declaration is honest.

   `tier-refusal` gates FUTURE writes; without this, declaring `:pure` over an
   existing module asserted a purity nothing had verified. A marker that lies
   is worse than no marker: every reader downstream — the tests you decide not
   to isolate, the reviewer trusting the core/shell split — is relying on it.

   PRODUCTION namespaces only, matching `tier-refusal`'s own
   `^{:rule/applies-to :production}`: a module's tests set up sessions and do
   IO by design, so gating them would make declaring a module `:pure`
   silently strand its own test namespace.

   `:external` (or its legacy spelling `:effects`) asserts nothing and
   therefore never has violations."
  [store module tier]
  (if (= :external (canonical-tier tier))
    []
    (let [cand (assoc-in store [:module-tiers (str module)] tier)
          ;; `module` may be a namespace path, so scope by PREFIX: declaring
          ;; slopp.rules.shape covers that namespace and anything under it,
          ;; not the whole slopp.api module.
          pfx  (str module)
          nses (->> (keys (:namespaces store))
                    (filter #(or (= pfx (str %)) (str/starts-with? (str %) (str pfx "."))))
                    (remove #(str/ends-with? (str %) "-test"))
                    sort)]
      ;; FORM-level only. Layering (does this namespace REQUIRE a looser one?)
    ;; is deliberately NOT checked here: its verdict CHANGES as legitimate
    ;; work continues — declare your dependencies and the same declaration
    ;; becomes valid — which is exactly the D-rule-grain test for a check
    ;; that does not belong at write grain. It is a whole-GRAPH property,
    ;; like module cycles, and `full_check` reports it there.
    (vec (for [n nses
               f (store/forms store n)
               :when (:name f)
               :let [why (tier-refusal cand n (:name f))]
               :when why]
           {:form (symbol (str n) (str (:name f))) :why why})))))
