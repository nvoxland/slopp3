(ns slopp.edit.modules (:require [clojure.string :as str] [rewrite-clj.node :as n] [slopp.index :as index] [slopp.render :as render] [slopp.store :as store] [slopp.edit.refs :as refs]))

(defn ^:export modules-manifest
  "The module manifest — {module-string #{dep-module-strings}} — the FOLD
  of the store's :module-edge deltas (edge-grain: concurrent declarations
  merge as a union, and every edge carries its why). {} for a fresh store
  (enforcement is on from birth; the first cross-module call teaches
  declare-then-use). nil ONLY for a populated store that predates the
  module system — open! derives its manifest from reality (adoption)."
  [store]
  (:modules store))
(defn ^:export module-of
  "A namespace's MODULE: its first two segments (\"x.y\"), or the whole
  name for single-segment namespaces. A trailing \"-test\" folds into the
  subject's module (\"x.y-test\" → \"x.y\") — tests live with what they
  test, so the natural TDD flow needs no edge ceremony."
  [ns-sym]
  (let [segs (clojure.string/split (str ns-sym) #"\.")]
    (clojure.string/join "." (map #(clojure.string/replace % #"-test$" "")
                                  (take 2 segs)))))
(defn ^:export export-level
  "The :export declared on the target var's defn name — nil (package-
  private), true (hoisted to the module's WORLD surface, reachable by any
  module with a declared edge), or a namespace-prefix string (visible only
  to callers under that subtree — within-project widening without going
  public). The definition-site visibility dial: no var copying, no facade
  namespace — the gate reads it where the fn lives."
  [store to-ns to-name]
  (when (and to-ns to-name)
    (when-let [e (store/form-named store (symbol (str to-ns)) (symbol (str to-name)))]
      (let [x (some-> (try (n/sexpr (:node e)) (catch Exception _ nil))
                      second meta :export)]
        (cond (true? x)   true
              (string? x) x
              (symbol? x) (str x)          ; ^{:export a.pub} — unquoted, forgiven
              :else       (when x true))))))
(defn ^:export derive-module-edges
  "The ACTUAL cross-module dependency edges of a store — kondo-resolved
  var usages grouped by module — as {module #{dep-modules}}, dep-less
  modules absent. Adoption uses this: a manifest derived from reality is
  acyclic with zero violations by construction."
  [store]
  (let [nses (set (keys (:namespaces store)))]
    (reduce (fn [acc nsx]
              (let [cmod  (module-of nsx)
                    tmods (into #{}
                                (comp (filter #(contains? nses (:to %)))
                                      (map #(module-of (:to %)))
                                      (remove #{cmod}))
                                (:var-usages
                                 (index/analyze (render/render-ns store nsx))))]
                (if (seq tmods) (merge-with into acc {cmod tmods}) acc)))
            {}
            (sort nses))))
(defn ^:export fold-test-ns
  "The namespace with a trailing `-test` stripped from EACH segment. A test
  namespace folds into the package it tests — for recursive VISIBILITY as
  well as module membership (module-of) — so a spec can reach the
  package-private deep helpers it exists to test (x.y.z-test is part of
  x.y.z.*)."
  [ns-sym]
  (->> (clojure.string/split (str ns-sym) #"\.")
       (map #(clojure.string/replace % #"-test$" ""))
       (clojure.string/join ".")))
(defn ^:export module-violations
  "The module system's pure RULES over resolved usage rows (kondo
  var-usages shape: {:from-ns :from-var :to :to-export}) — nil `manifest`
  = a pre-adoption store, rules off. Two rules: (1) RECURSIVE VISIBILITY —
  an ns deeper than two segments is callable only from namespaces sharing
  its parent prefix, unless the target var's :export widens it (true =
  world surface; a prefix string = that subtree only); (2) DECLARED EDGES
  — a cross-module call requires the caller's module to list the target
  module in the manifest. Rows must already be filtered to store-internal
  targets. Returns violation maps ({:from-ns :from-var :target-ns :rule
  :error}), nil when clean."
  [manifest rows]
  (when manifest
    (->> (distinct rows)
         (keep (fn [{:keys [from-ns from-var to to-export]}]
                 (let [caller-mod (module-of from-ns)
                       ;; fold -test so a spec shares its subject package's prefix (deep helpers
                       ;; stay testable); edges already fold via module-of
                       caller-str (fold-test-ns from-ns)
                       tsegs      (str/split (str to) #"\.")
                       tmod       (module-of to)
                       parent     (str/join "." (butlast tsegs))
                       under?     (fn [prefix]
                                    (or (= caller-str prefix)
                                        (str/starts-with? caller-str (str prefix "."))))
                       visible?   (or (under? parent)
                                      (true? to-export)
                                      (and (string? to-export) (under? to-export)))]
                   (cond
                     (= (str from-ns) (str to)) nil

                     (and (> (count tsegs) 2) (not visible?))
                     {:from-ns from-ns :from-var from-var :target-ns to
                      :rule :visibility
                      :error (if (string? to-export)
                               (str from-ns "/" from-var " calls " to " which is"
                                    " exported only within " to-export ".* — call"
                                    " it from inside that subtree, raise its"
                                    " :export level, or use " tmod "'s public"
                                    " surface")
                               (str from-ns "/" from-var " calls " to " which is"
                                    " package-private to " parent ".* (recursive"
                                    " visibility) — call " tmod "'s public"
                                    " surface, mark the target ^:export in its"
                                    " defn to hoist it into that surface"
                                    " (^{:export \"prefix\"} exposes it to a"
                                    " subtree only), or move the definition up"
                                    " a level"))}

                     (and (not= tmod caller-mod)
                          (not (contains? (get manifest caller-mod #{}) tmod)))
                     {:from-ns from-ns :from-var from-var :target-ns to
                      :rule :undeclared-edge
                      :error (str from-ns "/" from-var " uses " to " but module "
                                  caller-mod " does not declare " tmod
                                  " — declare the edge: module_dep {from \""
                                  caller-mod "\" to \"" tmod "\"} (say why in"
                                  " prompt), or restructure the call")}

                     :else nil))))
         seq)))
(defn ^:export tier-refusal
  "The per-form functional-core gate over the CANDIDATE store (D9): refuses a
   form whose reachability exceeds its module's declared purity tier.
   `:pure` rejects ANY effect (incl. an opaque-dep read) AND any NON-DETERMINISM
   (rand/slurp — a pure core must be referentially transparent, not merely
   mutation-free); `:reads` rejects a reach to a MUTATION (an in-process/external
   write or a `!`-named callee) but allows reads and non-determinism; `:effects`
   — or an undeclared module — is unrestricted. Built on index/effectful-vars +
   index/nondeterministic-vars over the candidate ns's analysis, so it inherits
   D6's single-ns, bang-name-propagating soundness (a cross-ns effect is seen only
   when the callee is `!`-named; interop non-determinism is out of scope).
   Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (let [tier (get (:module-tiers candidate) (module-of ns-sym) :effects)]
    (when (not= tier :effects)
      (let [analysis (index/analyze (render/render-ns candidate ns-sym))
            dep-nses (into #{} (mapcat identity) (vals (:dep-ns candidate)))
            eff      (if (= tier :pure)
                       (index/effectful-vars analysis dep-nses (:dep-pure candidate))
                       (index/effectful-vars analysis nil nil))
            nondet   (when (= tier :pure) (index/nondeterministic-vars analysis))
            vnode    (symbol (str ns-sym) (str form-name))]
        (cond
          (contains? eff vnode)
          (str ns-sym "/" form-name " reaches "
               (if (= tier :pure) "an effect" "a mutation")
               " but module " (module-of ns-sym) " is declared :" (name tier)
               " (functional-core gate) — move the effect to a periphery"
               " namespace, or loosen the tier with module_purity {module \""
               (module-of ns-sym) "\" tier :"
               (if (= tier :pure) "reads" "effects") "} (say why)")

          (and nondet (contains? nondet vnode))
          (str ns-sym "/" form-name " reaches non-determinism (rand/slurp) but"
               " module " (module-of ns-sym) " is declared :pure — a pure core"
               " must be referentially transparent (deterministic in its args);"
               " move the non-determinism to a periphery namespace, or loosen the"
               " tier with module_purity {module \"" (module-of ns-sym)
               "\" tier :reads} (say why)"))))))

(defn ^:export module-refusal
  "The per-form module gate over the CANDIDATE store (post-edit value):
  applies the module rules to `form-name`'s outbound references from THE
  graph (edit.refs — resolved statics, un-required qualified calls, and
  carrier positions all count; declarations aren't calls). nil when clean
  or pre-adoption."
  [candidate ns-sym form-name]
  (when-let [manifest (modules-manifest candidate)]
    (let [rows (for [r (refs/ns-refs candidate ns-sym)
                     :when (and (= form-name (:from-var r))
                                (not= :declared (:via r)))]
                 {:from-ns ns-sym :from-var (:from-var r) :to (:to-ns r)
                  :to-export (export-level candidate (:to-ns r) (:to-name r))})]
      (when-let [vs (module-violations manifest rows)]
        (str/join "; " (map :error vs))))))
(defn ^:export module-scan
  "The whole-namespace module gate (ingest/ns_create counterpart of
  dialect-scan) over a candidate store value, judged from THE graph's
  slice for the namespace: nil when clean, else every violation joined."
  [candidate ns-sym]
  (when-let [manifest (modules-manifest candidate)]
    (let [rows (for [r (refs/ns-refs candidate ns-sym)
                     :when (not= :declared (:via r))]
                 {:from-ns ns-sym :from-var (:from-var r) :to (:to-ns r)
                  :to-export (export-level candidate (:to-ns r) (:to-name r))})]
      (when-let [vs (module-violations manifest rows)]
        (str/join "; " (map :error vs))))))
(defn ^:export missing-doc-warning
  "Public-surface documentation rule (module system): a defn/defmacro on
  the module surface — depth<=2 namespace, or a deeper var hoisted by
  ^:export — should carry a docstring. One advisory row for the NAMED form
  (write paths attach it to their result; it never rides ns-warnings, so
  it nags only where you are working), or nil."
  [store ns-sym form-name]
  (when (and (modules-manifest store) form-name)
    (when-let [e (store/form-named store ns-sym form-name)]
      (let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
        (when (and (seq? s)
                   (contains? '#{defn defmacro} (first s))
                   (symbol? (second s))
                   (not (:private (meta (second s))))
                   (not (string? (nth s 2 nil)))
                   (or (<= (count (str/split (str ns-sym) #"\.")) 2)
                       ;; only a WORLD export is public surface — a subtree
                       ;; export stays internal, no docstring nag
                       (true? (:export (meta (second s))))))
          {:var (symbol (str ns-sym) (str (second s)))
           :missing-doc true})))))

(defn ^:export schema-refusal
  "The opt-in per-form BOUNDARY-SCHEMA gate over the CANDIDATE store (D9/D2):
   when the store opts in (config file `gates`, key `require-boundary-schemas`
   set to `true`; OFF by default so nothing retro-breaks — the permissive-default,
   no-adoption-step lesson), a MODULE-EXTERNAL `defn` whose FIRST arg is a
   destructured MAP but which carries no :=> :malli/schema is refused: the one
   boundary a narrow-context caller can't infer the shape of. Structural only
   (rewrite-clj node inspection, no malli server-side), and the schema it demands
   is exactly the :=> shape the done-point oracle-check generatively verifies — so
   requiring it can never mean requiring a drift-prone marker. Returns a teaching
   string, or nil when clean / opted-out."
  [candidate ns-sym form-name]
  (when (= "true" (get-in candidate [:config "gates" :values "require-boundary-schemas"]))
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [form (try (n/sexpr (:node e)) (catch Exception _ nil))]
        (when (and (seq? form) (= 'defn (first form)))
          (let [nm       (second form)
                nmeta    (meta nm)
                body     (drop 2 form)
                body     (cond->> body (string? (first body)) rest)
                body     (cond->> body (map? (first body)) rest)
                arglist  (cond (vector? (first body)) (first body)
                               (seq? (first body))    (first (first body)))
                map-arg?  (and (vector? arglist) (map? (first arglist)))
                public?   (not (:private nmeta))
                external? (or (= (str ns-sym) (module-of ns-sym))
                              (boolean (export-level candidate ns-sym form-name)))
                sch       (:malli/schema nmeta)
                has-sch?  (and (vector? sch) (= :=> (first sch)))]
            (when (and public? external? map-arg? (not has-sch?))
              (str ns-sym "/" form-name " is a module-external fn taking a"
                   " destructured map but declares no :=> :malli/schema — the"
                   " boundary contract a narrow-context caller can't infer. Add"
                   " ^{:malli/schema [:=> [:cat ArgSchema] RetSchema]} to the name"
                   " (the done-point oracle-check then verifies it generatively),"
                   " or opt out with config_file: path `gates` key"
                   " `require-boundary-schemas` unset true"))))))))

(defn ^:export rule-severity
  "The effective severity of rule `rule-key` for this store: a per-store OVERRIDE
   from the `rules` config file — `config_file {path \"rules\" key <rule> value
   <severity>}` — else `default`. `rule-key` is coerced via `name`, so a write
   gate's var name (`'schema-refusal`), a done-advisory `:key`, or a plain string
   all work. Severities: `:refuse`/`:error` (blocking), `:advisory` (surfaced,
   non-blocking), `:off` (skipped). This is the dial that makes the hard-refuse
   program project-tunable — a rule a project can't live with becomes `:off` or
   `:advisory` rather than a wall its agents fight. Rides the store `:config`
   (config_file), so it projects into git."
  [store rule-key default]
  (if-let [v (get-in store [:config "rules" :values (name rule-key)])]
    (keyword v)
    default))

(defn ^:export namespaced-keys-refusal
  "The opt-in NAMESPACED-BOUNDARY-KEYS gate over the CANDIDATE store (D9): when
   the store opts in (config file `gates`, key `require-namespaced-keys` = `true`;
   OFF by default), a MODULE-EXTERNAL `defn` whose FIRST arg destructures
   UNQUALIFIED `:keys` (`{:keys [id]}`) is refused — a boundary map should carry
   namespaced domain keys (`{:some.ns/keys [id]}`), self-documenting at the use
   site and safe against the silent nil-pun. Structural only. Returns a teaching
   string, or nil when clean / opted-out. (Boundary + arglist detection mirrors
   `schema-refusal`; a shared helper could DRY them.)"
  [candidate ns-sym form-name]
  (when (= "true" (get-in candidate [:config "gates" :values "require-namespaced-keys"]))
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [form (try (n/sexpr (:node e)) (catch Exception _ nil))]
        (when (and (seq? form) (= 'defn (first form)))
          (let [nm        (second form)
                nmeta     (meta nm)
                body      (drop 2 form)
                body      (cond->> body (string? (first body)) rest)
                body      (cond->> body (map? (first body)) rest)
                arglist   (cond (vector? (first body)) (first body)
                                (seq? (first body))    (first (first body)))
                param     (first arglist)
                bare?     (and (map? param) (contains? param :keys))
                public?   (not (:private nmeta))
                external? (or (= (str ns-sym) (module-of ns-sym))
                              (boolean (export-level candidate ns-sym form-name)))]
            (when (and public? external? bare?)
              (str ns-sym "/" form-name " destructures unqualified :keys at a"
                   " module boundary, but this store requires namespaced domain"
                   " keys — use {:some.ns/keys [...]} (self-documenting at the use"
                   " site, safe against the nil-pun), or opt out with config_file:"
                   " path `gates` key `require-namespaced-keys` unset true"))))))))

(def per-form-write-gates
  "The ordered per-form WRITE gates (the rule-registry seed, D9): each is a
  (candidate ns-sym form-name) → teaching-string-or-nil check. Held as VARS
  (`#'`) so a hot-reload of a gate is picked up — a value vector would freeze
  the stale fns, the composed-def trap — and so the reference graph sees them.
  Register a new per-form write gate HERE, not at the N write sites. Each gate's
  per-store `rule-severity` (`:off` skips it) is consulted by `gate-refusal`."
  [#'module-refusal #'tier-refusal #'schema-refusal #'namespaced-keys-refusal])

(defn ^:export write-gate-names
  "The keyword rule-names of the registered per-form write gates (from the var
   metadata) — the enumeration the unified rule catalog + its drift-guard use
   without reaching the package-private `per-form-write-gates`."
  []
  (mapv #(keyword (:name (meta %))) per-form-write-gates))

(defn ^:export gate-refusal
  "Run every per-form write gate over the CANDIDATE store (the rule-registry
   seed, D9): first refusal wins, nil when all are clean. Adding a per-form
   write gate means adding it to `per-form-write-gates`, not hand-wiring the N
   write sites (replace-form, add-form!, the two group steps). Each gate's
   per-store `rule-severity` is consulted: `:off` SKIPS it (a project dials a
   gate it can't live with off — the mechanism that makes hard-refuse adoptable);
   any other severity runs it (hard-refuse on a non-nil teaching string).
   Advisory-downgrade for write gates awaits a write-time warning channel."
  [candidate ns-sym form-name]
  (some (fn [gate]
          (when (not= :off (rule-severity candidate (:name (meta gate)) :refuse))
            (gate candidate ns-sym form-name)))
        per-form-write-gates))
