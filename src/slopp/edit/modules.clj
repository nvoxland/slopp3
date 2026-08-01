(ns slopp.edit.modules
  "The per-form WRITE GATES (D9) and the derived analysis they judge against.

  A gate is a `(candidate ns-sym form-name)` → teaching-string-or-nil check
  run against the CANDIDATE store — the value the write would produce — so a
  violation is refused before it lands rather than found afterwards. That is
  the whole reason this namespace is shaped the way it is: everything here
  must be answerable from a store VALUE, with no image and no eval.

  Three families, each with its primitives above its gates: module edges and
  visibility, purity TIERS and layering, and the D-web surface (auth, routes,
  effect and context vocabulary, endpoint contracts). `per-form-write-gates`
  near the bottom is the registry every write site consults — register a new
  gate THERE, not at the N call sites — and `gate-refusal` is the entry point.

  Its neighbours: `slopp.index.*` derives the reference graph this reads,
  `slopp.api.rules` joins these gates with the done-time advisories into the
  one catalog `query_rules` reports, and `slopp.api.web` consumes the same
  web primitives to answer questions rather than to refuse."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store.render :as render]
            [slopp.store :as store]
            [slopp.edit.refs :as refs]
            [slopp.index.derive :as derive]
            [slopp.index.analyze :as analyze]
            [slopp.store.fields :as fields]))

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

(defn ^:export namespace-purpose-warning
  "Namespace-purpose rule: a namespace should state what it is FOR.

  Its INVENTORY is derived — `query_project`, the module surface and the
  outline all list its forms — so a docstring that lists them is a second
  copy that drifts. What no tool can derive is the part worth writing: why
  this namespace exists, what to expect inside it, and how it relates to its
  neighbours.

  Advisory, and namespace-grained. Like [[missing-doc-warning]] it is meant
  to nag WHERE YOU ARE WORKING — the done-point reports it for namespaces the
  episode touched — while `review_scan` and `full_check` answer the
  whole-store question.

  Deliberately NOT a shape check. A heuristic guessing whether prose is a
  purpose or an inventory would fire on good docstrings, and Core 2's rule is
  to fix the analysis before restricting the language. Absence is objective;
  quality is a review question. The teaching carries the rest.

  Two exemptions, both because there is no author to nag: a GENERATED
  namespace (every named form carries `^:generated` — `generate_client`'s
  output documents itself), and an EMPTY one (nothing to describe yet)."
  [store ns-sym]
  (when (contains? (:namespaces store) ns-sym)
    (let [es      (store/forms store ns-sym)
          named   (remove #(= (str (:name %)) (str ns-sym)) es)
          gen?    (fn [e] (let [s (store/form-sexpr (:node e))]
                            (boolean (and (seq? s) (symbol? (second s))
                                          (:generated (meta (second s)))))))
          ns-form (first es)]
      (when (and ns-form
                 (seq named)
                 (not (every? gen? named))
                 (nil? (some #(when (string? %) %)
                             (take 2 (drop 2 (store/form-sexpr (:node ns-form)))))))
        {:ns ns-sym
         :missing-purpose true
         :teach (str ns-sym " states no purpose. Add a docstring to its ns form"
                     " saying WHY it exists, what to expect inside, and how it"
                     " relates to its neighbours — NOT a list of what it"
                     " contains, which query_project and the module surface"
                     " already derive and show.")}))))

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
                   ;; head compared by NAME-string so this form carries no
                   ;; banned symbol literal (D4 bans defmacro even as data) and
                   ;; so stays editable
                   (contains? #{"defn" "defmacro"} (str (first s)))
                   (symbol? (second s))
                   (not (:private (meta (second s))))
                   ;; generate_client's output documents itself; never nag it
                   (not (:generated (meta (second s))))
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
   Structural only (rewrite-clj node inspection, no malli server-side).
   Shares `module-external?` + `fn-arglists` with the other boundary gates.
   Returns a teaching string, or nil when clean / opted-out.

   The schema must also declare `:throws`, in the `:=>` PROPERTIES:

       ^{:malli/schema [:=> {:throws []} [:cat ArgSchema] RetSchema]}

   `:throws` is a VECTOR of malli schemas for the `ex-data` this function throws
   to SIGNAL failure. Empty declares that it signals none.

   Requiring it even when empty is the point. A caller reading a signature
   cannot otherwise tell \"this returns nil on failure\" from \"this throws and I
   have not been told what\" — undeclared and declared-nothing look identical,
   which is the conflation D-surface-honesty exists to prevent, one level down
   from where that rule usually bites.

   **It is the CHECKED half, and the split is the familiar one.** `:throws`
   declares what this function throws ON PURPOSE to signal a failure its caller
   is expected to handle — the `ex-info` it constructs and the `ex-data` that
   rides it. An NPE, a bad arity, an assertion three calls down are the
   UNCHECKED half: real, but nobody's declaration, and handled the way unchecked
   exceptions always are. `[]` therefore says exactly what Java's absent
   `throws` clause says and no more; nobody reads a `void` signature as a
   promise that nothing can be thrown.

   That split is what makes the declaration worth requiring even when empty. A
   caller cannot otherwise tell a function that signals failure by RETURNING
   from one that signals it by throwing something nobody has been told about,
   and those need different code at every call site.

   Malli itself has no `throws` concept — `:=>`, `:fn` and `:function` are the
   whole vocabulary. But schema PROPERTIES are open and round-trip verbatim
   through `m/form`, so this rides inside the schema that was already required,
   with no fork and no wrapper. slopp owns the meaning; malli carries it."
  [candidate ns-sym form-name]
  (when (= "true" (get-in candidate [:config "gates" :values "require-boundary-schemas"]))
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [form     (try (n/sexpr (:node e)) (catch Exception _ nil))
            map-arg? (boolean (some #(map? (first %)) (fn-arglists form)))
            sch      (:malli/schema (meta (second form)))
            has-sch? (and (vector? sch) (= :=> (first sch)))
            props    (when has-sch? (second sch))
            throws?  (and (map? props) (vector? (:throws props)))]
        (when (and (module-external? ns-sym form) map-arg?)
          (cond
            (not has-sch?)
            (str ns-sym "/" form-name " is a module-external fn taking a"
                 " destructured map but declares no :=> :malli/schema — the"
                 " boundary contract a narrow-context caller can't infer. Add"
                 " ^{:malli/schema [:=> {:throws []} [:cat ArgSchema] RetSchema]}"
                 " to the name, or opt out with config_file: path `gates` key"
                 " `require-boundary-schemas` unset true")

            (not throws?)
            (str ns-sym "/" form-name " declares a :=> schema but no `:throws`"
                 " in its properties — so a caller cannot tell whether it"
                 " signals failure by throwing or by returning. Say it"
                 " explicitly: [:=> {:throws []} [:cat ArgSchema] RetSchema]"
                 " declares that it signals none, and"
                 " {:throws [[:map [:my/error :keyword]]]} declares the ex-data"
                 " it throws. A VECTOR, not :none — `[]` says this function"
                 " signals nothing by throwing, which is all that can honestly"
                 " be claimed; an NPE from three calls down is nobody's"
                 " declaration.")))))))

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

(defn web-enabled?
  "The D-web master opt-in, read off the candidate store's `capabilities`
  config: http.enabled = \"true\". Every web gate is inert without it — a
  store that never opts into HTTP is untouched (the adoption story)."
  [candidate]
  (= "true" (get-in candidate [:config "capabilities" :values "http.enabled"])))

(defn ^:export web-name-meta
  "The metadata on a stored form's NAME symbol, read off the node — no eval
  (D3 keeps metadata source-only truth). nil for unnamed/unparseable forms.
  THE reader for the `:web/*` declaration vocabulary; `slopp.api.web` and
  the web gates both consume it."
  [e]
  (let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
    (when (and (seq? s) (symbol? (second s)))
      (meta (second s)))))

(defn ^:export web-endpoint-rows
  "Every `:web/path` form in `store`: `{:ns :name :form-id :meta}` rows —
  the single route traversal; the collision gate and `slopp.api.web` both
  build on it. TEST namespaces are excluded: their endpoint-shaped forms
  are fixtures, not servable surface, and a fixture must neither report in
  query_routes nor claim a path against a production endpoint. A pure
  function of the store value."
  [store]
  (vec
   (for [nsx (sort (keys (:namespaces store)))
         :when (not (render/test-ns? nsx))
         e   (store/forms store nsx)
         :when (:name e)
         :let [m (web-name-meta e)]
         :when (:web/path m)]
     {:ns nsx :name (:name e) :form-id (:id e) :meta m})))

(defn ^:export web-performers
  "The app-declared performer vocabulary for `marker-key` (`:web/effect` or
  `:web/read`): {kind → performer qsym}. slopp interprets no domain
  vocabulary of its own — the store declares it, so this registry is a pure
  function of the forms; the undeclared-effect gate and `slopp.api.web`
  both consume it."
  [store marker-key]
  (into {}
        (for [nsx (sort (keys (:namespaces store)))
              e   (store/forms store nsx)
              :when (:name e)
              :let [kind (get (web-name-meta e) marker-key)]
              :when kind]
          [kind (symbol (str nsx) (str (:name e)))])))

(defn ^:export ^{:rule/applies-to :production} web-auth-refusal
  "The default-deny auth gate (D-web): a `:web/path` endpoint with NO
  `:web/auth` declaration is refused — `:public` must be typed out, so an
  unsecured route is always a visible decision, never an omission. Inert
  until the store opts into HTTP (`web-enabled?`). Returns a teaching
  string, or nil when clean."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [m (web-name-meta e)]
        (when (and (:web/path m) (not (contains? m :web/auth)))
          (str ns-sym "/" form-name " declares the route " (pr-str (:web/path m))
               " but no :web/auth — every endpoint declares its policy"
               " (default-deny): add :web/auth :public (deliberately open),"
               " :authenticated, or [:group \"<name>\"] to the name metadata;"
               " groups live in the capabilities config (query_capabilities)"))))))

(defn ^:export ^{:rule/applies-to :production} web-route-collision
  "The route-uniqueness gate (D-web): a `:web/path` endpoint whose
  method+path another FORM already claims is refused at the write — a
  duplicate route is impossible by construction, not a startup surprise.
  The same form re-landing (a replace) is not a collision. Inert until
  `web-enabled?`. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [m (web-name-meta e)]
        (when (:web/path m)
          (let [method (:web/method m)
                path   (str (:web/path m))
                other  (some #(when (and (not= (:form-id %) (:id e))
                                         (= method (:web/method (:meta %)))
                                         (= path (str (:web/path (:meta %)))))
                               %)
                             (web-endpoint-rows candidate))]
            (when other
              (str ns-sym "/" form-name " claims " method " " path
                   " but " (:ns other) "/" (:name other) " already serves it —"
                   " one method+path has one owner: change the path, change the"
                   " method, or extend the existing handler (query_routes lists"
                   " every claim)"))))))))

(defn ^:export web-context-builders
  "Every `^{:web/context true}` fn in the store, as qsyms, sorted — the
  app-declared sources of `:web/perform-ctx`, the map a handler receives as
  `:web/deps` and every performer receives as its first argument.

  PLURAL although exactly one is legal, because the SCAN and the singleton
  POLICY are different jobs and the two callers ask different questions: the
  `web-undeclared-context` write gate asks whether ANY exists,
  `slopp.api.web/context-builder` asks for THE one and refuses two. Splitting
  them keeps a single definition of who builds the context — the alternative
  is two scans that agree until one gains a case."
  [store]
  (vec (for [nsx (sort (keys (:namespaces store)))
             e   (store/forms store nsx)
             :when (and (:name e) (get (web-name-meta e) :web/context))]
         (symbol (str nsx) (str (:name e))))))

(defn ^:export ^{:rule/applies-to :production} web-undeclared-effect
  "The effect-vocabulary gate (D-web): an endpoint declaring `:web/effects`
  kinds may only name kinds some `^{:web/effect <kind>}` performer provides
  — the dispatcher can only run effects the app defined, and a typo'd kind
  must fail at the write, not at the first request. Inert until
  `web-enabled?`. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [m (web-name-meta e)
            kinds (seq (:web/effects m))]
        (when (and (:web/path m) kinds)
          (let [known (set (keys (web-performers candidate :web/effect)))
                missing (remove known kinds)]
            (when (seq missing)
              (str ns-sym "/" form-name " declares :web/effects "
                   (pr-str (vec missing)) " but no performer provides "
                   (if (= 1 (count missing)) "it" "them")
                   " — define one per kind: (defn ^{:web/effect "
                   (pr-str (first missing)) "} <name>! [ctx …] …), or reuse an"
                   " existing kind (query_routes lists the vocabulary)"))))))))

(defn ^:export ^{:rule/applies-to :production} web-unsafe-get
  "The HTTP-safety gate (D-web): a `:get`/`:head` endpoint must be SAFE in
  the RFC sense — it may neither declare `:web/effects` kinds
  (effects-as-data must not launder a mutating GET) nor reach a mutation
  directly (the D6 mutation set: `effectful-vars` with no external
  boundary, the same read `:internal`'s tier check uses). Inert until
  `web-enabled?`. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [m (web-name-meta e)]
        (when (and (:web/path m) (#{:get :head} (:web/method m)))
          (cond
            (seq (:web/effects m))
            (str ns-sym "/" form-name " is a GET/HEAD endpoint but declares"
                 " :web/effects " (pr-str (vec (:web/effects m))) " — a safe"
                 " method must not mutate: make it :post/:put/:delete, or drop"
                 " the effects")

            (contains? (derive/effectful-vars
                        (analyze/analyze (render/render-ns candidate ns-sym))
                        nil nil)
                       (symbol (str ns-sym) (str form-name)))
            (str ns-sym "/" form-name " is a GET/HEAD endpoint but reaches a"
                 " mutation — a safe method must not mutate: move the write"
                 " behind a :post/:put/:delete endpoint's :web/effects, or"
                 " return the change as data")))))))

(defn ^:export ^{:rule/applies-to :production} web-unknown-group
  "The policy-vocabulary gate (D-web): an endpoint's `:web/auth` may only
  name groups the `capabilities` config defines (`groups.<name>.…` keys) —
  a typo'd group would silently deny every request forever, the authz twin
  of the nil-pun. Walks composite policies ([:any …]/[:all …]). Inert
  until `web-enabled?`. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [m (web-name-meta e)]
        (when (:web/path m)
          (let [known (into #{}
                            (keep #(second (re-matches #"groups\.([^.]+)\..*" (str %))))
                            (keys (get-in candidate [:config "capabilities" :values] {})))
                named (fn named [p]
                        (cond
                          (and (vector? p) (= :group (first p))) [(second p)]
                          (and (vector? p) (#{:any :all} (first p))) (mapcat named (rest p))
                          :else nil))
                missing (remove known (named (:web/auth m)))]
            (when (seq missing)
              (str ns-sym "/" form-name " grants by group "
                   (pr-str (vec missing)) " but the capabilities config"
                   " defines no such group"
                   (when (seq known)
                     (str " (configured: " (str/join ", " (sort known)) ")"))
                   " — config_file {path \"capabilities\" key \"groups."
                   (first missing) ".members\" value \"…\"} defines it, or fix"
                   " the name"))))))))

(defn ^:export generated-ns
  "The generated-client protection gate (D-web-contracts part 2): a form marked
  ^{:generated \"<endpoint>\"} is OUTPUT of generate_client and must not be
  hand-edited. Regeneration rewrites the whole client namespace (through
  store/ingest, BELOW this gate layer — so the generator itself is unaffected;
  only edit-tool writes reach here). Returns a teaching string naming the source
  endpoint + generate_client, or nil. To take manual ownership of a generated
  form, strip its ^:generated marker first. Not web-gated — the marker alone
  arms it."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (when-let [g (:generated (web-name-meta e))]
      (str ns-sym "/" form-name " is GENERATED (from endpoint " g
           ") and must not be hand-edited — generate_client rewrites the whole"
           " client namespace from the endpoint schemas, so an edit here is lost"
           " on the next generate. Change the ENDPOINT's :web/request/:web/response"
           " and re-run generate_client; to take manual ownership, strip the"
           " ^:generated marker first."))))

(defn ^:export client-signature
  "A deterministic fingerprint of the store's web endpoint CONTRACTS — the raw
   {:ns :name :method :path :web/request :web/response} of every endpoint — so a
   done-advisory can tell whether the generated typed client (generate_client) is
   stale WITHOUT re-rendering or parsing it. generate_client records this on the
   `client`/`generated-sig` config at generation; the staleness advisory compares
   the recorded value with the current one. A pure function of the store value."
  [store]
  (str (hash (mapv (fn [{:keys [ns name meta]}]
                     [(str ns) (str name) (:web/method meta) (:web/path meta)
                      (pr-str (:web/request meta)) (pr-str (:web/response meta))])
                   (web-endpoint-rows store)))))

(defn ^:export ^{:rule/applies-to :production} web-endpoint-schema
  "The API-contract gate (D-web-contracts): a `:web/path` endpoint must type out
  its contract so the client validates against the SAME schema. `:web/response`
  is required on EVERY endpoint; `:web/request` is required on a BODY method
  (`:post`/`:put`/`:patch`) — a `:get`/`:delete`/`:head` needs only a response.
  Declare a `.cljc` malli schema VAR (shareable/reusable — `some.contracts/order`)
  or an inline `[:map …]` for a one-off shape. Inert until the store opts into
  HTTP (`web-enabled?`); auth is checked first, so a naked endpoint still refuses
  on `:web/auth` before this. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (let [m (web-name-meta e)]
        (when (:web/path m)
          (let [body?   (contains? #{:post :put :patch} (:web/method m))
                missing (cond-> []
                          (not (contains? m :web/response)) (conj :web/response)
                          (and body? (not (contains? m :web/request))) (conj :web/request))]
            (when (seq missing)
              (str ns-sym "/" form-name " declares the route " (pr-str (:web/path m))
                   " but no " (str/join " / " (map str missing))
                   " — every endpoint types out its contract so the client"
                   " validates against the SAME schema (D-web-contracts). Add "
                   (str/join " and " (map str missing))
                   " to the name metadata: a .cljc malli schema VAR"
                   " (shareable/reusable, e.g. some.contracts/order) or an inline"
                   " [:map …] for a one-off shape."))))))))

(defn- web-react-attrs
  "Per-form write gate (D-web-html): a literal hiccup element carrying a
  React attribute name — `:className`, `:htmlFor`, an `:onClick`-style
  handler, `:dangerouslySetInnerHTML`. Browsers silently IGNORE unknown
  attributes, so the mistake ships and does nothing. Scoped to maps in
  position 2 of a keyword-tag vector (a JSON-ish payload map is not an
  element); inert until `web-enabled?`. Returns a teaching string, or nil."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate ns-sym form-name)]
      (let [react->fix {:className ":class"
                        :htmlFor ":for"
                        :dangerouslySetInnerHTML "[:html/raw \"…\"] (string literal only)"}
            handler? (fn [k] (re-matches #"on[A-Z].*" (name k)))
            sx (try (n/sexpr (:node e)) (catch Exception _ nil))
            hit (first (for [v (tree-seq coll? seq sx)
                             :when (and (vector? v)
                                        (keyword? (first v))
                                        (map? (second v)))
                             k (keys (second v))
                             :when (and (keyword? k) (nil? (namespace k))
                                        (or (react->fix k) (handler? k)))]
                         k))]
        (when hit
          (str "React attribute " hit " in a hiccup element — "
               (if-let [fix (react->fix hit)]
                 (str "use " fix)
                 (str "server-rendered pages have no event handlers; "
                      "a link or form targeting an endpoint replaces it"))
               ". Browsers silently ignore unknown attributes, so this would "
               "ship and do nothing."))))))

(defn ^:export ^{:rule/applies-to :production} web-undeclared-context
  "The context-SOURCE gate (D-web): an endpoint whose body reads `:web/deps`
  may only do so in a store that declares where those deps come from — one
  `^{:web/context true}` zero-arg fn. The sibling of `web-undeclared-effect`:
  an effect kind needs a marked performer, and the context needs a marked
  builder. Inert until `web-enabled?`. Returns a teaching string, or nil.

  This gate is the reason the context is a MARKER rather than a capability
  naming a qualified symbol. With the declaration in the store, both halves
  are visible statically — the handlers that read `:web/deps`, and whether
  anything claims to build it — so the failure moves to the write that caused
  it. A capability is a string in config, checkable at boot at the earliest,
  which is after the browser has already seen the 500.

  Scoped to `:web/path` ENDPOINTS, not to every form naming the keyword: the
  framework's own dispatcher assigns `:web/deps` onto the request, and gating
  that would refuse writes to slopp's `slopp.web.dispatch/handle!`.

  **The teaching is three clauses and stops** — what, the consequence, and the
  fix as a LITERAL FORM. A cold read (slopp-ui, hitting this unprepared and
  deliberately not opening the SKILL first) reported the literal form as the
  decisive part: the marker spelling, the arity, `defn`-not-`def` and the
  return shape all come off it at once, so no step sends the reader looking.
  Two clauses were CUT on that evidence and should not come back:

  - **\"it cannot be a performer\"** — the right sentence in the wrong room. It
    answers a DESIGN question to a reader in fix-it mode who has already been
    handed the form, and it is the only clause that requires knowing what a
    performer is. It lives in `slopp.api.web/context-builder`'s docstring and
    the SKILL, where someone deciding meets it.
  - **the lifecycle framed around done points and the managed server** — this
    gate fires on any `http.enabled` store, including one with `dev.server`
    false where no managed server boots at all. Told to that reader it
    asserts a behaviour that does not happen to them: a general truth
    delivered in this store's voice, which is Core 9 one notch down. What is
    left says the same thing unconditionally — anything the builder allocates
    is new each time it RUNS.

  The lifecycle clause STAYS, though, and the test is why: it changes what
  someone writes rather than what they understand. The obvious builder is
  whatever the app's own `serve!` already constructs, moved — which is exactly
  the shape that silently empties."
  [candidate ns-sym form-name]
  (when (web-enabled? candidate)
    (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
      (when (and (:web/path (web-name-meta e))
                 (empty? (web-context-builders candidate)))
        (let [sx    (try (n/sexpr (:node e)) (catch Exception _ nil))
              nodes (tree-seq coll? seq sx)]
          (when (or (some #(= :web/deps %) nodes)
                    (some #(and (map? %) (some #{'deps} (:web/keys %))) nodes))
            (str ns-sym "/" form-name " reads :web/deps, but this store declares"
                 " no context builder — so the map would arrive nil, which either"
                 " 500s or, worse, answers 200 with an empty body. Declare exactly"
                 " ONE zero-arg builder: (defn ^{:web/context true} app-context []"
                 " {…}). Anything it allocates is new each time it runs, so keep"
                 " live state outside it.")))))))

(def ^:export per-form-write-gates
  "The ordered per-form WRITE gates (the rule-registry seed, D9): each is a
  (candidate ns-sym form-name) → teaching-string-or-nil check. Held as VARS
  (`#'`) so a hot-reload of a gate is picked up — a value vector would freeze
  the stale fns, the composed-def trap — and so the reference graph sees them.
  Register a new per-form write gate HERE, not at the N write sites. Each gate's
  per-store `rule-severity` (`:off` skips it) is consulted by `gate-refusal`.
  The web-* gates (D-web) are additionally inert until the store opts into
  HTTP (`web-enabled?`)."
  [#'module-refusal #'tier-refusal #'schema-refusal #'namespaced-keys-refusal #'generated-ns
   #'web-auth-refusal #'web-endpoint-schema #'web-route-collision #'web-undeclared-effect #'web-undeclared-context
   #'web-unsafe-get #'web-unknown-group #'web-react-attrs])

(defn ^:export write-gate-names
  "The keyword rule-names of the registered per-form write gates (from the var
   metadata) — the enumeration the unified rule catalog + its drift-guard use
   without reaching the package-private `per-form-write-gates`."
  []
  (mapv #(keyword (:name (meta %))) per-form-write-gates))

(defn ^:export rule-applies-to-platform?
  "Whether a rule scoped to `rule-scope` (:everywhere / :clojure / :clojurescript)
  fires for a form on `platform` (:jvm / :cljc / :cljs) — the platform axis of a
  rule's applicability (D-web-cljs, the sibling of the :production test-ns axis).
  :everywhere always fires. A :cljc form is checked by BOTH :clojure and
  :clojurescript rules because it compiles to both. :jvm satisfies :clojure;
  :cljs satisfies :clojurescript; an unknown scope defaults to firing."
  [rule-scope platform]
  (case rule-scope
    :clojure       (contains? #{:jvm :cljc} platform)
    :clojurescript (contains? #{:cljs :cljc} platform)
    true))

(defn ^:export write-gate-severities
  "`{rule-key declared-severity}` for every registered per-form write gate — the
   `:rule/severity` each gate declares in its own metadata, `:refuse` when it
   declares none. This is the DEFAULT `gate-check` passes to `rule-severity`, so
   a catalog or report built on it cannot drift from what is enforced (it did:
   the catalog carried a `:severity` column nothing read). The per-store dial
   still overrides it."
  []
  (into {} (map (fn [g] [(keyword (:name (meta g)))
                         (:rule/severity (meta g) :refuse)]))
        per-form-write-gates))

(defn ^:export gate-check
  "Run every per-form write gate over the CANDIDATE store ONCE, bucketed by each
   gate's effective per-store `rule-severity`: returns `{:refuse <first
   refuse-grade teaching, or nil> :refusals [<every refuse-grade teaching>]
   :advisories [<advisory-grade teachings>]}`. A gate dialed `:off` is skipped;
   `:refuse`/`:error` (and the default) BLOCK; `:advisory` is non-blocking and
   its teaching rides the write result (the dial's warn-but-proceed mode).
   `gate-refusal` is the blocking view.

   Every gate is run even once one has refused, and `:refusals` keeps them ALL —
   two stacked requirements are both knowable from the first candidate, so
   teaching one per round-trip costs a resend to learn what was already in hand.

   A gate DECLARES its own default severity as `:rule/severity` metadata
   (`:refuse` when absent); the per-store dial overrides it. The default lives on
   the gate rather than at this call site so the rule catalog can report it
   instead of restating it — a hardcoded default here and a `:severity` column
   there is one fact stored twice, and they did disagree.

   A gate also declares WHERE it applies. `:rule/applies-to :production` skips
   TEST namespaces (declared once, here, so a gate and any REPORT of the same
   rule cannot disagree — they did: purity-standing excluded tests while
   tier-refusal gated them). `:rule/platform` (:everywhere default / :clojure /
   :clojurescript) skips forms whose platform the rule doesn't cover — a :cljs
   gate never fires on a :jvm form, and a :cljc form is checked by both worlds
   (D-web-cljs)."
  [candidate ns-sym form-name]
  (let [platform (store/platform-for candidate ns-sym)]
    (reduce (fn [acc gate]
              (let [sev   (rule-severity candidate (:name (meta gate))
                                         (:rule/severity (meta gate) :refuse))
                    skip? (or (and (= :production (:rule/applies-to (meta gate) :all))
                                   (render/test-ns? ns-sym))
                              (not (rule-applies-to-platform?
                                    (:rule/platform (meta gate) :everywhere)
                                    platform)))]
                (if (or (= :off sev) skip?)
                  acc
                  (if-let [t (gate candidate ns-sym form-name)]
                    (if (= :advisory sev)
                      (update acc :advisories conj t)
                      (-> acc
                          (update :refusals conj t)
                          (cond-> (nil? (:refuse acc)) (assoc :refuse t))))
                    acc))))
            {:refuse nil :refusals [] :advisories []}
            per-form-write-gates)))

(defn ^:export gate-refusal
  "The BLOCKING view of `gate-check`: the refuse-grade per-form write-gate
   teachings over the CANDIDATE store as ONE message, or nil when none refuse. A
   gate dialed `:off` is skipped and an `:advisory` gate is non-blocking (its
   teaching rides `gate-check`'s `:advisories` onto the write result).

   When several gates refuse, the extras follow the first under `ALSO PENDING:`
   — all of them were knowable from the same candidate, so the caller can fix
   everything in one resend. A lone refusal reads exactly as it always did.
   Register a new per-form write gate in `per-form-write-gates`, not at the N
   write sites."
  [candidate ns-sym form-name]
  (let [[t & more] (:refusals (gate-check candidate ns-sym form-name))]
    (when t
      (if (seq more)
        (str t "\nALSO PENDING: " (str/join "\nALSO PENDING: " more))
        t))))
