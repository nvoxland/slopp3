(ns slopp.edit.modules (:require [clojure.string :as str] [rewrite-clj.node :as n] [slopp.render :as render] [slopp.store :as store] [slopp.edit.refs :as refs] [slopp.index.derive :as derive] [slopp.index.analyze :as analyze]))

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
                                 (analyze/analyze (render/render-ns store nsx))))]
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
(defn ^:export module-external?
  "The single boundary predicate the write gates and the breakage classifier
   share: true when a `defn` `form` (sexpr) in `ns-sym` is reachable from OUTSIDE
   its module — public (not `defn-`/`^:private`) in a module-root ns (<= 2
   segments), or any truthy `^:export` in a deeper ns. Node-based (reads the
   sexpr's own metadata), so it judges an old/candidate form version too."
  [ns-sym form]
  (and (seq? form) (= 'defn (first form))
       (not (:private (meta (second form))))
       (or (= (str ns-sym) (module-of ns-sym))
           (boolean (:export (meta (second form)))))))

(defn ^:export fn-arglists
  "The arg-vectors of EVERY arity of a `defn` sexpr — single-arity `[params]` and
   each multi-arity `([params] …)`. Skips the docstring and attr-map. The shared
   all-arities extraction (so a boundary shape in a LATER arity isn't missed —
   review #6)."
  [form]
  (let [body (drop 2 form)
        body (cond->> body (string? (first body)) rest)
        body (cond->> body (map? (first body)) rest)]
    (if (vector? (first body))
      [(first body)]
      (vec (keep #(when (and (seq? %) (vector? (first %))) (first %)) body)))))

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
                   ;; via the shared accessor: (def x "a value") has a string at
                   ;; index 2 that is NOT a docstring, and indexing cannot tell
                   (nil? (store/form-docstring (:node e)))
                   (or (<= (count (str/split (str ns-sym) #"\.")) 2)
                       ;; only a WORLD export is public surface — a subtree
                       ;; export stays internal, no docstring nag
                       (true? (:export (meta (second s))))))
          {:var (symbol (str ns-sym) (str (second s)))
           :missing-doc true})))))

(defn ^:export schema-refusal
  "The opt-in per-form BOUNDARY-SCHEMA gate over the CANDIDATE store (D9/D2): when
   the store opts in (config file `gates`, key `require-boundary-schemas` = `true`;
   OFF by default so nothing retro-breaks), a MODULE-EXTERNAL `defn` any of whose
   arities takes a destructured MAP first arg but which carries no :=> :malli/schema
   is refused: the one boundary a narrow-context caller can't infer the shape of.
   Structural only (rewrite-clj node inspection, no malli server-side), and the
   schema it demands is exactly the :=> shape the done-point oracle-check
   generatively verifies. Shares `module-external?` + `fn-arglists` with the other
   boundary gates. Returns a teaching string, or nil when clean / opted-out."
  [candidate ns-sym form-name]
  (when (= "true" (get-in candidate [:config "gates" :values "require-boundary-schemas"]))
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [form     (try (n/sexpr (:node e)) (catch Exception _ nil))
            map-arg? (boolean (some #(map? (first %)) (fn-arglists form)))
            sch      (:malli/schema (meta (second form)))
            has-sch? (and (vector? sch) (= :=> (first sch)))]
        (when (and (module-external? ns-sym form) map-arg? (not has-sch?))
          (str ns-sym "/" form-name " is a module-external fn taking a"
               " destructured map but declares no :=> :malli/schema — the"
               " boundary contract a narrow-context caller can't infer. Add"
               " ^{:malli/schema [:=> [:cat ArgSchema] RetSchema]} to the name"
               " (the done-point oracle-check then verifies it generatively),"
               " or opt out with config_file: path `gates` key"
               " `require-boundary-schemas` unset true"))))))

(defn ^:export rule-severity
  "The effective severity of rule `rule-key` for this store: a per-store OVERRIDE
   from the `rules` config file — `config_file {path \"rules\" key <rule> value
   <severity>}` — else `default`. `rule-key` is coerced via `name`, so a write
   gate's var name (`'schema-refusal`), a done-advisory `:key`, or a plain string
   all work. Severities: `:refuse`/`:error` (blocking), `:advisory` (surfaced,
   non-blocking), `:off` (skipped). The stored value is a string; a leading colon
   is tolerated (`\":off\"` == `\"off\"`) and an UNKNOWN value falls back to
   `default` — a mistyped severity must not silently mint a junk keyword that
   leaves the rule enabled-but-unrecognized. This is the dial that makes the
   hard-refuse program project-tunable; it rides the store `:config`, so it
   projects into git."
  [store rule-key default]
  (if-let [v (get-in store [:config "rules" :values (name rule-key)])]
    (let [k (keyword (str/replace (str v) #"^:+" ""))]
      (if (#{:off :advisory :error :refuse} k) k default))
    default))

(defn ^:export namespaced-keys-refusal
  "The opt-in NAMESPACED-BOUNDARY-KEYS gate over the CANDIDATE store (D9): when the
   store opts in (config file `gates`, key `require-namespaced-keys` = `true`; OFF
   by default), a MODULE-EXTERNAL `defn` any of whose arities destructures
   UNQUALIFIED `:keys` (`{:keys [id]}`) is refused — use
   `{:some.ns/keys [id]}`. Structural only; shares `module-external?` +
   `fn-arglists` with the other boundary gates.

   SCOPE — read this before \"improving\" beyond it. This gate is deliberately
   NARROW and it is the WHOLE rule. It covers a module-external defn's ARGLIST
   destructuring, nothing else. It does NOT ask you to namespace map keys
   generally, return maps, internal fns, or keys read as `(:k m)`. Its finding
   list IS the worklist; `cleanup {all true}` reports it under `:gates`.

   WHY the narrow scope is not timidity: measured on this store, 674 distinct
   unqualified keys appear in production code and 445 of them appear in more
   than one form. The most-shared are Clojure syntax (`:require`, `:as`,
   `:when`) and slopp's universal result vocabulary (`:error` in 119 forms,
   `:id`, `:name`, `:ns`) — where ONE shared spelling is exactly right and
   namespacing would be pure loss. A broader rule is undischargeable, and an
   undischargeable rule trains people to ignore the channel.

   WHY the rule exists at all, given that general Clojure practice defaults to
   UNQUALIFIED keys and namespaces only for a specific reason (spec's flat
   global registry, data crossing systems): this is a deliberate HOUSE rule,
   stricter than community practice, because the argument for bare keys
   assumes CONTEXT DISAMBIGUATES — a reader with the file open sees the
   producer twenty lines up. An agent reads one form. Measured here: `:session`
   means an nREPL session id AND slopp's session atom; `:dir` means three
   things; `:values` means a config entry's values AND eval-checked!'s return
   map. Each was found by tooling, never by reading. A qualified key names its
   own origin inside the slice.

   What it does NOT buy, so do not claim it: it does not prevent typos
   (`:some.ns/idd` nil-puns exactly like `:idd` — that is `key-typos`' job).

   `^:foreign-keys` on the NAME discharges it, for the one case our own code
   cannot fix: a fn destructuring a THIRD-PARTY map (clj-kondo analysis, a JDBC
   row) whose keys are not ours to rename. Like `^:ambient-ok` and
   `^:unused-ok` it POLICES ITSELF — a marker on a fn with no unqualified
   boundary keys is refused with 'remove the flag', so it cannot decay into a
   blanket opt-out sprinkled to silence the gate.

   Returns a teaching string, or nil when clean / opted-out."
  [candidate ns-sym form-name]
  (when (= "true" (get-in candidate [:config "gates" :values "require-namespaced-keys"]))
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [form    (try (n/sexpr (:node e)) (catch Exception _ nil))
            bare?   (boolean (some #(and (map? (first %)) (contains? (first %) :keys))
                                   (fn-arglists form)))
            marked? (boolean (and (seq? form) (symbol? (second form))
                                  (:foreign-keys (meta (second form)))))]
        (cond
          (and marked? (not bare?))
          (str ns-sym "/" form-name " carries ^:foreign-keys but destructures"
               " no unqualified boundary keys — remove the flag")

          (and (module-external? ns-sym form) bare? (not marked?))
          (str ns-sym "/" form-name " destructures unqualified :keys at a"
               " module boundary, but this store requires namespaced domain"
               " keys — use {:some.ns/keys [...]} (self-documenting at the use"
               " site, safe against the nil-pun). If the map is THIRD-PARTY and"
               " its keys are not yours to rename, mark the name"
               " ^:foreign-keys; or opt out with config_file: path `gates` key"
               " `require-namespaced-keys` unset true"))))))

(defn ^:export ^:legacy-ok canonical-tier
  "Canonical spelling of a purity tier: the retired :reads/:effects map to
  :internal/:external; canonical spellings (and nil) pass through. Normalize at
  every boundary that READS a recorded tier — stores that predate the rename
  legitimately carry the old spellings."
  [tier]
  ({:reads :internal :effects :external} tier tier))

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
    {:tier     (canonical-tier (get (:module-tiers store) (module-of ns-sym) :external))
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

(def ^:export per-form-write-gates
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

(defn ^:export gate-check
  "Run every per-form write gate over the CANDIDATE store ONCE, bucketed by each
   gate's effective per-store `rule-severity`: returns `{:refuse <first
   refuse-grade teaching, or nil> :advisories [<advisory-grade teachings>]}`. A
   gate dialed `:off` is skipped; `:refuse`/`:error` (and the default) BLOCK;
   `:advisory` is non-blocking and its teaching rides the write result (the
   dial's warn-but-proceed mode). `gate-refusal` is the blocking view."
  [candidate ns-sym form-name]
  (reduce (fn [acc gate]
            (let [sev (rule-severity candidate (:name (meta gate)) :refuse)
                  ;; a gate declares whether it applies to TEST namespaces.
                  ;; Declared once, here, so a gate and any REPORT of the same
                  ;; rule cannot disagree — they did: purity-standing excluded
                  ;; tests while tier-refusal gated them, so the report
                  ;; recommended a tier the gate would then punish.
                  skip? (and (= :production (:rule/applies-to (meta gate) :all))
                             (render/test-ns? ns-sym))]
              (if (or (= :off sev) skip?)
                acc
                (if-let [t (gate candidate ns-sym form-name)]
                  (if (= :advisory sev)
                    (update acc :advisories conj t)
                    (cond-> acc (nil? (:refuse acc)) (assoc :refuse t)))
                  acc))))
          {:refuse nil :advisories []}
          per-form-write-gates))

(defn ^:export gate-refusal
  "The BLOCKING view of `gate-check`: the first refuse-grade per-form write-gate
   teaching over the CANDIDATE store, or nil. A gate dialed `:off` is skipped and
   an `:advisory` gate is non-blocking (its teaching rides `gate-check`'s
   `:advisories` onto the write result). Register a new per-form write gate in
   `per-form-write-gates`, not at the N write sites."
  [candidate ns-sym form-name]
  (:refuse (gate-check candidate ns-sym form-name)))

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

(defn- late-ref-target-nses
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
          ;; slopp.api.shape covers that namespace and anything under it,
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
