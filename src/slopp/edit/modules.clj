(ns slopp.edit.modules
  "The MODULE system at write time: what a module is, what it may reach, what
  is visible across it — and what a module SURFACE must declare about itself.

  A module is a namespace's first TWO segments, which is a rule with more
  consequences than it looks: it fixes a namespace's layer, it makes a third
  segment package-private, and it folds a `-test` sibling into its subject.
  On top of that sit the edge manifest, the visibility rule, and the gates
  that refuse a write crossing an undeclared edge or reaching a
  package-private target. `module-test-manifest` is a deliberately SEPARATE
  relation from `modules-manifest`, because a fixture shares its subject's
  module key and one edge would otherwise license both.

  The surface-declaration gates belong here rather than anywhere else,
  because what they police is the module SURFACE: a docstring on a
  module-surface defn, a purpose on a namespace, a boundary schema, canonical
  namespaced keys. Each needs `module-of` / `export-level` / `module-external?`
  to know what the surface even IS.

  Everything is answerable from a store VALUE, with no image and no eval —
  the gates run against the CANDIDATE store, the value a write WOULD produce,
  so a violation is refused before it lands rather than found afterwards.

  Its neighbours: `slopp.edit.gates` is the chassis that REGISTERS and
  dispatches gates (register a new one there, never at the N call sites);
  `slopp.edit.tiers` and `slopp.edit.web` are the other two families;
  `slopp.index.*` derives the reference graph this reads; and `slopp.rules`
  joins these gates with the done-time advisories into the one catalog
  `query_rules` reports."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store.render :as store.render]
            [slopp.store :as store]
            [slopp.index.refs :as refs]
            [slopp.index.analyze :as analyze]))

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

(defn ^:export derive-module-edges
  "The ACTUAL cross-module dependency edges of a store — kondo-resolved var
  usages grouped by module — CLASSIFIED by who needs them:
  `{:production {module #{deps}} :test {module #{deps}}}`, dep-less modules
  absent from each.

  An edge lands in `:test` when only `-test` namespaces cross it. That
  distinction is not cosmetic and not optional: a derived manifest is how
  adoption and `module_extract` write edges nobody typed, and deriving a
  fixture's crossing as a PRODUCTION edge is how slopp's own manifest came
  to assert that `slopp.index` and `slopp.store` depend on `slopp.api` —
  two cycles in the graph enforcement reads, zero production callers behind
  either. Classifying here means a derived manifest says only what is true.

  An edge with any production caller is production, whatever its tests also
  do — so `:test` is exactly the set nothing but a fixture needs."
  [store]
  (let [nses (set (keys (:namespaces store)))
        deps-of (fn [nsx]
                  (let [cmod (module-of nsx)]
                    (into #{}
                          (comp (filter #(contains? nses (:to %)))
                                (map #(module-of (:to %)))
                                (remove #{cmod}))
                          (:var-usages
                           (analyze/analyze (store.render/render-ns store nsx))))))
        collect (fn [pred]
                  (reduce (fn [acc nsx]
                            (let [tmods (deps-of nsx)]
                              (if (seq tmods)
                                (merge-with into acc {(module-of nsx) tmods})
                                acc)))
                          {}
                          (sort (filter pred nses))))
        prod (collect #(= (str %) (fold-test-ns %)))
        test (collect #(not= (str %) (fold-test-ns %)))]
    {:production prod
     ;; only what NOTHING but a fixture needs
     :test (into {}
                 (keep (fn [[m deps]]
                         (let [only (apply disj deps (get prod m #{}))]
                           (when (seq only) [m only]))))
                 test)}))

(defn ^:export module-test-manifest
  "The TEST-ONLY module edges — `{module-string #{dep-module-strings}}`, the
  fold of the store's `:module-test-edge` deltas, same edge-grain CRDT as
  [[modules-manifest]] and deliberately a SEPARATE relation from it.

  A module may declare that its `-test` namespaces cross an edge its
  production code may not. That distinction cannot be made in `:modules`,
  because `module-of` folds a trailing `-test` off each segment — a fixture
  shares its subject's module key, so one edge would license both.

  Separate rather than nested so `:modules` keeps meaning exactly PRODUCTION
  edges: the cycle check, the layer view, `store/module-path` and the
  projected `modules` file all want that graph and are unchanged by this. A
  test edge is not a production edge, so it is not a cycle — which is the
  whole point, and why `module_dep {test-only true}` does not consult the
  cycle check.

  `{}` when nothing has declared one."
  [store]
  (or (:module-test-edges store) {}))

(defn ^:export module-violations
  "The module system's pure RULES over resolved usage rows ({:from-ns
  :from-var :to :to-name :to-export}) — nil `manifest` = a pre-adoption
  store, rules off. Two rules: (1) RECURSIVE VISIBILITY —
  an ns deeper than two segments is callable only from namespaces sharing
  its parent prefix, unless the target var's :export widens it (true =
  world surface; a prefix string = that subtree only); (2) DECLARED EDGES
  — a cross-module call requires the caller's module to list the target
  module in the manifest. Rows must already be filtered to store-internal
  targets. Returns violation maps ({:from-ns :from-var :target-ns
  :target-name :rule :error}), nil when clean.

  `:to-name` is the CALLEE, and a visibility refusal is the one finding that
  needs it: its whole instruction is \"mark the target ^:export\", which was
  unactionable while the row named only the target namespace. It is
  optional — a row that does not know its callee degrades the MESSAGE, never
  the rule, since the rules themselves are about namespaces.

  `test-manifest` ([[module-test-manifest]]) satisfies rule 2 for a TEST
  caller only — a module may declare that its fixtures cross an edge its
  production code may not. Which namespaces count as tests is asked of
  [[fold-test-ns]] rather than a fresh suffix check, so `a.b-test` and
  `a-test.b` answer the same way the rest of the module system answers them.
  The 2-arity is the production-only reading."
  ([manifest rows] (module-violations manifest nil rows))
  ([manifest test-manifest rows]
   (when manifest
     (->> (distinct rows)
          (keep (fn [{:keys [from-ns from-var to to-name to-export]}]
                  (let [caller-mod (module-of from-ns)
                        ;; fold -test so a spec shares its subject package's prefix (deep helpers
                        ;; stay testable); edges already fold via module-of
                        caller-str (fold-test-ns from-ns)
                        test?      (not= (str from-ns) caller-str)
                        tsegs      (str/split (str to) #"\.")
                        tmod       (module-of to)
                        parent     (str/join "." (butlast tsegs))
                        target     (if to-name (str to "/" to-name) (str to))
                        under?     (fn [prefix]
                                     (or (= caller-str prefix)
                                         (str/starts-with? caller-str (str prefix "."))))
                        visible?   (or (under? parent)
                                       (true? to-export)
                                       (and (string? to-export) (under? to-export)))
                        declared?  (or (contains? (get manifest caller-mod #{}) tmod)
                                       (and test?
                                            (contains? (get test-manifest caller-mod #{})
                                                       tmod)))]
                    (cond
                      (= (str from-ns) (str to)) nil

                      (and (> (count tsegs) 2) (not visible?))
                      (cond-> {:from-ns from-ns :from-var from-var :target-ns to
                               :rule :visibility
                               :error (if (string? to-export)
                                        (str from-ns "/" from-var " calls " target
                                             " which is exported only within "
                                             to-export ".* — call"
                                             " it from inside that subtree, raise its"
                                             " :export level, or use " tmod "'s public"
                                             " surface")
                                        (str from-ns "/" from-var " calls " target
                                             " which is package-private to " parent
                                             ".* (recursive visibility) — call " tmod
                                             "'s public surface, mark " target
                                             " ^:export in its defn to hoist it into"
                                             " that surface (^{:export \"prefix\"}"
                                             " exposes it to a subtree only), or move"
                                             " the definition up a level"))}
                        to-name (assoc :target-name to-name))

                      (and (not= tmod caller-mod) (not declared?))
                      (cond-> {:from-ns from-ns :from-var from-var :target-ns to
                               :rule :undeclared-edge
                               :error (str from-ns "/" from-var " uses " target
                                           " but module " caller-mod
                                           " does not declare " tmod
                                           " — declare the edge: module_dep {from \""
                                           caller-mod "\" to \"" tmod "\"} (say why in"
                                           " prompt), or restructure the call"
                                           (when test?
                                             (str ". This caller is a TEST, so"
                                                  " {test-only true} declares it for"
                                                  " fixtures WITHOUT licensing production"
                                                  " code under " caller-mod " to cross —"
                                                  " and a test-only edge is not a"
                                                  " production edge, so it cannot close a"
                                                  " cycle")))}
                        to-name (assoc :target-name to-name))

                      :else nil))))
          seq))))

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
  "The arg-vectors of EVERY arity of a fn-defining sexpr — single-arity
   `[params]` and each multi-arity `([params] …)`. Skips the docstring and
   attr-map. The shared all-arities extraction (so a boundary shape in a LATER
   arity isn't missed — review #6).

   **TOTAL: nil for a form that defines no fn.** It used to answer for whatever
   it was handed — position 2 onward, first vector wins — so `(def geometry
   [1 2 3])` came back `[[1 2 3]]`: a plausible one-arg signature that does not
   throw. That made the head check every CALLER's job, and it was owed by nine
   and paid by two. `:sig` shipped the same wrong-index read from four separate
   producers before this moved here, which is the tell that it was a totality
   problem and not a discipline one — the same shape as any check whose
   population is enumerated from the items it grades.

   The head is matched as TEXT rather than against quoted symbols: the dialect
   denylist reads a banned symbol anywhere in a form as a USE of it, including
   inside a set whose whole job is to recognise one.

   `defmethod` is in the set and is NOT correct here — its arg vector sits
   after the dispatch value, so this has always answered `[]` for one.
   Preserved rather than fixed, so this change is about non-fn forms only and
   no caller's behaviour moves except the one that was wrong."
  [form]
  (when (and (seq? form)
             (#{"defn" "defn-" "defmacro" "defmethod"} (str (first form))))
    (let [body (drop 2 form)
          body (cond->> body (string? (first body)) rest)
          body (cond->> body (map? (first body)) rest)]
      (if (vector? (first body))
        [(first body)]
        (vec (keep #(when (and (seq? %) (vector? (first %))) (first %)) body))))))

(defn ^:export store-violations
  "[[module-violations]] applied to `store`'s declared relations — the
  reading every real caller wants, and the one place that knows WHICH
  relations the rules consult.

  It exists because that knowledge was about to be spelled out at six call
  sites (both write gates, the whole-store debt fold, the done-time
  relocation check, the move planner, the extract planner). Six copies of
  \"fetch the manifests, apply the rules\" is how one of them comes to fetch
  fewer than the others — and the failure is silent, because consulting one
  relation too few reports MORE violations than exist, which reads exactly
  like a strict gate rather than a broken one.

  `rows` stays a parameter: the write gates pass one namespace's slice, the
  whole-store folds pass every row. Scoping is the caller's business; which
  declarations count is not."
  [store rows]
  (module-violations (modules-manifest store) (module-test-manifest store) rows))

(defn ^:export module-refusal
  "The per-form module gate over the CANDIDATE store (post-edit value):
  applies the module rules to `form-name`'s outbound references from THE
  graph (`slopp.index.refs` — resolved statics, un-required qualified calls,
  and carrier positions all count; declarations aren't calls). nil when clean
  or pre-adoption."
  [candidate ns-sym form-name]
  (when-let [_ (modules-manifest candidate)]
    (let [rows (for [r (refs/ns-refs candidate ns-sym)
                     :when (and (= form-name (:from-var r))
                                (not= :declared (:via r)))]
                 {:from-ns ns-sym :from-var (:from-var r) :to (:to-ns r)
                  :to-export (export-level candidate (:to-ns r) (:to-name r))})]
      (when-let [vs (store-violations candidate rows)]
        (str/join "; " (map :error vs))))))

(defn ^:export module-scan
  "The whole-namespace module gate (ingest/ns_create counterpart of
  dialect-scan) over a candidate store value, judged from THE graph's
  slice for the namespace: nil when clean, else every violation joined."
  [candidate ns-sym]
  (when-let [_ (modules-manifest candidate)]
    (let [rows (for [r (refs/ns-refs candidate ns-sym)
                     :when (not= :declared (:via r))]
                 {:from-ns ns-sym :from-var (:from-var r) :to (:to-ns r)
                  :to-export (export-level candidate (:to-ns r) (:to-name r))})]
      (when-let [vs (store-violations candidate rows)]
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
  purpose or an inventory would fire on good docstrings, and the rule is
  to fix the analysis before restricting the language. Absence is objective;
  quality is a review question. The teaching carries the rest.

  Two exemptions, both because there is no author to nag: a GENERATED
  namespace (every named form carries `^:generated` — `generate_client`'s
  output documents itself), and an EMPTY one (nothing to describe yet)."
  [store ns-sym]
  (when (contains? (:namespaces store) ns-sym)
    (let [es      (store/forms store ns-sym)
          named   (remove #(= (str (:name %)) (str ns-sym)) es)
          ;; this read carried all three guards and was RIGHT, which is
          ;; exactly why it is worth collapsing: a correct duplicate is the
          ;; one that agrees today and drifts tomorrow
          gen?    (fn [e] (boolean (:generated (store/form-name-meta e))))
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
      (let [s  (try (n/sexpr (:node e)) (catch Exception _ nil))
            ;; the name's markers through the shared accessor, exactly as the
            ;; docstring below goes through its own. This read was
            ;; `(meta (second s))` three times here and at four other sites —
            ;; the same one-line-with-three-guards shape that made
            ;; `form-docstring` necessary
            nm (store/form-name-meta e)]
        (when (and (seq? s)
                   ;; head compared by NAME-string so this form carries no
                   ;; banned symbol literal (D4 bans defmacro even as data) and
                   ;; so stays editable
                   (contains? #{"defn" "defmacro"} (str (first s)))
                   (symbol? (second s))
                   (not (:private nm))
                   ;; generate_client's output documents itself; never nag it
                   (not (:generated nm))
                   ;; via the shared accessor: (def x "a value") has a string at
                   ;; index 2 that is NOT a docstring, and indexing cannot tell
                   (nil? (store/form-docstring (:node e)))
                   (or (<= (count (str/split (str ns-sym) #"\.")) 2)
                       ;; only a WORLD export is public surface — a subtree
                       ;; export stays internal, no docstring nag
                       (true? (:export nm))))
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

(defn ^:export module-usage-rows
  "Every store-internal usage row ({:from-ns :from-var :to :to-export}) —
  consumed from THE reference graph (`slopp.index.refs`), so carrier
  references count as usage exactly like resolved calls; declarations don't
  (they aren't calls).

  This is the module rules' INPUT VOCABULARY, which is why it lives beside
  them rather than with the reports that read them: [[module-violations]]'s
  docstring specifies this exact shape, and both the debt view and the
  done-time drift check have to produce it. It sat on the read side until
  2026-08-02, when the done-time check needed it and the resulting checks→reads
  reference turned out to be the ONE edge making phase 1b's four-way cut of
  `slopp.api` cyclic."
  [store]
  (vec (for [r (refs/refs store)
             :when (not= :declared (:via r))]
         {:from-ns   (:from-ns r)
          :from-var  (:from-var r)
          :to        (:to-ns r)
          :to-name   (:to-name r)
          :to-export (export-level store (:to-ns r) (:to-name r))})))

(defn ^:export relocation-debt
  "The module debt standing on `ns-sym` after a RELOCATION moved it — the
  edges its callers now need, the visibility its callers now break, and the
  cycles declaring those edges would close. `nil` when clean, so absence
  means checked-and-none.

  This exists because a relocation is the one write the gates cannot see.
  A module rule is inherited from a NAME and enforced when a form is
  WRITTEN; `ns_rename` changes the name and rewrites its own callers through
  `store/apply-changeset`, which runs no gates at all. So a crossing that
  would be refused outright if you typed it is created without a murmur, and
  the first thing that mentions it is a `done` some time later — reported
  against the unmoved CALLER, which never moved and does not name the rename.

  Measured over slopp's own regroup, which is why the shape is what it is:
  module 1 needed 5 undeclared edges worked out by hand, module 2 needed 8,
  module 3 needed 38. Every one was derived by writing the same simulation
  over the reference graph — twice — while the tool holding all of the
  information reported none of it.

  Three things it answers that the raw violation rows do not:

  - **Edges are GROUPED to the declaration you have to make.** The rules
    speak per call site; `module_dep` speaks per module pair. 38 edges came
    out of a few hundred crossings.
  - **`:test-only` is derived from who actually crosses**, never inherited
    from whatever the old edge said. If every crossing namespace is a test,
    `module_dep {test_only true}` is the honest declaration and a production
    edge would overstate the architecture — the same judgement
    `:overstated-edges` makes after the fact, offered before it instead.
  - **`:cycles` is what `module_dep` is about to refuse.** Declaring these
    edges is cycle-checked, so a regroup can be ten renames deep before the
    graph says no. Pre-existing cycles are subtracted: this reports what
    DECLARING would close, not what was already wrong.

  Reads `store-violations`, so it can never disagree with the gate or with
  `done` — the alternative, a hand-rolled simulation of the rename, is a
  second derivation of a rule that already exists, and the two drift."
  [store ns-sym]
  (let [nsx      (str ns-sym)
        touches? (fn [v] (or (= nsx (str (:from-ns v))) (= nsx (str (:target-ns v)))))
        mine     (filter touches? (store-violations store (module-usage-rows store)))
        test?    (fn [n] (not= (str n) (fold-test-ns n)))
        edges    (vec (sort-by (juxt :from :to)
                               (for [[[from to] g]
                                     (group-by (juxt #(module-of (:from-ns %))
                                                     #(module-of (:target-ns %)))
                                               (filter #(= :undeclared-edge (:rule %)) mine))
                                     :let [callers (vec (sort (distinct (map :from-ns g))))]]
                                 {:from from :to to
                                  :test-only (every? test? callers)
                                  :crossed-by callers
                                  :sites (count g)})))
        vis      (vec (for [v mine :when (= :visibility (:rule v))]
                        (select-keys v [:from-ns :from-var :target-ns :error])))
        manifest (modules-manifest store)
        with-new (reduce (fn [m {:keys [from to]}] (update m from (fnil conj #{}) to))
                         manifest (remove :test-only edges))
        already  (into #{} (map set) (:cycles (store/module-layers manifest)))
        cycles   (vec (remove #(contains? already (set %))
                              (:cycles (store/module-layers with-new))))]
    (when (or (seq edges) (seq vis) (seq cycles))
      (cond-> {}
        (seq edges)
        (assoc :edges-needed edges)

        (seq vis)
        (assoc :visibility vis)

        (seq cycles)
        (assoc :cycles cycles)

        :always
        (assoc :note
               (str/join
                " "
                (remove
                 nil?
                 [(when (seq edges)
                    (str (count edges) " module edge(s) are crossed and not"
                         " declared: module_dep {from \"<from>\" to \"<to>\""
                         " test_only true|omitted} for each, saying why in"
                         " prompt. Nothing refused these — a relocation rewrites"
                         " its callers through a path that runs no gates, so this"
                         " is the only notice before done reports them as errors"
                         " against callers that never moved."))
                  (when (seq vis)
                    (str (count vis) " call(s) now reach a package-private"
                         " namespace: going from two segments to three makes a"
                         " namespace visible only under its own parent prefix."
                         " Each :error names the options — ^:export the target,"
                         " scope it to a subtree, or call the module's public"
                         " surface."))
                  (when (seq cycles)
                    (str "DECLARING those edges would close "
                         (count cycles) " dependency cycle(s) "
                         (pr-str cycles)
                         ", and module_dep refuses a cycle — so decide the"
                         " direction before renaming anything else. A crossing"
                         " only tests make is not a cycle; {test_only true} may"
                         " be the answer."))])))))))
