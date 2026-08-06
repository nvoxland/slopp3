(ns slopp.index.refs
  "THE reference graph: every reference in a store as one canonical record
  stream — producers (kondo, carrier walks, declarations) normalize HERE;
  consumers (gates, unused, review, moves) never re-integrate sources.
  Derived and content-memoized, never stored: references are an index of
  source, and the journal owes them no consistency."
  (:require [rewrite-clj.node :as n]
            [slopp.store.render :as render]
            [slopp.store :as store] [slopp.cache :as cache] [slopp.index.analyze :as analyze] [clojure.string :as str]))

(defn ^:export walk-pruned
  "THE quote-aware traversal: depth-first over sexpr `x`, PRUNING quoted
  subtrees (a quoted symbol is data, never a reference). Returns the
  concat of `(f node)` over every SURVIVING node — collections and atoms
  alike — so one traversal serves every extractor: qualified symbols,
  seq-head inspection (carrier positions), `:tag` hints. Callers `keep`/
  `first`/`distinct` the stream. (Four hand-rolled copies of this walk
  preceded it.)"
  [f x]
  (when-not (and (seq? x) (= 'quote (first x)))
    (concat (f x)
            (when (coll? x) (mapcat #(walk-pruned f %) x)))))

(defn quote-pruned-qualified-syms
  "Every namespace-qualified symbol in sexpr `x`, quote-pruned — the
  un-required-call producer's extractor over `walk-pruned`."
  [x]
  (walk-pruned (fn [n] (when (and (symbol? n) (namespace n)) [n])) x))

(defn- static-refs
  "kondo-resolved var usages PLUS syntactically-qualified references into
  store namespaces kondo can't resolve (un-required — the gate-hole class),
  normalized to canonical records. Self-references excluded.

  Usages kondo resolves but cannot attribute to a var — defmethod bodies,
  defrecord/deftype method bodies, extend-* bodies, top-level calls — arrive
  with nil :from-var and were silently DROPPED (#129): a defn called only from
  a defmethod body read as unused-public, and blast radius/module gates never
  saw the call. They are attributed to the OWNING FORM by its rendered span
  (`render/owner-form` over the same render kondo analyzed), with :from-var
  the owner's primary name — nil for a registration, which is the truth."
  [st known nses]
  (mapcat
   (fn [nsx]
     (let [fid-of (into {} (keep (fn [e] (when (:name e) [(:name e) (:id e)])))
                        (store/forms st nsx))
           usages (:var-usages (analyze/analyze (render/render-ns st nsx)))
           kondo  (for [u usages
                        :when (and (:name u) (:from-var u)
                                   (contains? known (:to u))
                                   (not (and (= nsx (:to u))
                                             (= (:from-var u) (:name u)))))]
                    (cond-> {:from-form (fid-of (:from-var u))
                              :from-ns   nsx
                              :from-var  (:from-var u)
                              :to-ns     (:to u)
                              :to-name   (:name u)
                              :to-form   (:id (store/form-named st (:to u) (:name u)))
                              :via       :static}
                      (:arity u) (assoc :arity (:arity u))))
           bodied (for [u usages
                        :when (and (:name u) (nil? (:from-var u))
                                   (contains? known (:to u))
                                   (:row u))
                        :let [owner (render/owner-form st nsx (:row u) (:col u))]
                        :when (and owner
                                   (not (and (= nsx (:to u))
                                             (some? (:name owner))
                                             (= (:name owner) (:name u)))))]
                    (cond-> {:from-form (:id owner)
                             :from-ns   nsx
                             :from-var  (:name owner)
                             :to-ns     (:to u)
                             :to-name   (:name u)
                             :to-form   (:id (store/form-named st (:to u) (:name u)))
                             :via       :static}
                      (:arity u) (assoc :arity (:arity u))))
           seen   (set (map (juxt :from-var :to-ns :to-name)
                            (concat kondo bodied)))
           unreq  (for [e (store/forms st nsx)
                        :when (:name e)
                        s (distinct (quote-pruned-qualified-syms
                                     (try (n/sexpr (:node e))
                                          (catch Exception _ nil))))
                        :let [to (symbol (namespace s))]
                        :when (and (contains? known to)
                                   (not= to nsx)
                                   (not (seen [(:name e) to (symbol (name s))])))]
                    {:from-form (:id e)
                     :from-ns   nsx
                     :from-var  (:name e)
                     :to-ns     to
                     :to-name   (symbol (name s))
                     :to-form   (:id (store/form-named st to (symbol (name s))))
                     :via       :static})]
       (concat kondo bodied unreq)))
   (sort nses)))

(defn- carrier-refs
  "Quoted symbols in DESIGNATED CARRIER positions (query-call / invoke! /
  late-ref) as canonical records — the blessed forms of the reference-
  carrier decision; a naked quoted symbol stays data."
  [st known nses]
  (let [carrier? #{"query-call" "query_call" "invoke!" "late-ref"}]
    (for [nsx (sort nses)
          e   (store/forms st nsx)
          :when (:name e)
          s (walk-pruned
             (fn [f]
               (when (and (seq? f) (symbol? (first f))
                          (carrier? (name (first f))))
                 (for [a (rest f)
                       :when (and (seq? a) (= 'quote (first a))
                                  (symbol? (second a))
                                  (namespace (second a)))]
                   (second a))))
             (try (n/sexpr (:node e)) (catch Exception _ nil)))
          :let [to (symbol (namespace s))]
          :when (contains? known to)]
      {:from-form (:id e)
       :from-ns   nsx
       :from-var  (:name e)
       :to-ns     to
       :to-name   (symbol (name s))
       :to-form   (:id (store/form-named st to (symbol (name s))))
       :via       :carrier})))

(defn- covers-targets
  "Qualified symbols named by a `^{:covers …}` marker value. The value is a
   string \"ns/name — why\" (or a vector of them); the target is the leading
   whitespace-delimited token, the rest is the human why. Non-qualified or
   unreadable tokens are dropped — a declaration only counts when it names a
   real form."
  [v]
  (when v
    (->> (if (sequential? v) v [v])
         (keep (fn [item]
                 (let [tok (first (str/split (str/trim (str item)) #"\s+"))
                       sym (try (symbol tok) (catch Exception _ nil))]
                   (when (and sym (seq (or (namespace sym) "")) (seq (name sym)))
                     sym)))))))

(defn- declared-refs
  "Marker declarations as edges. Two shapes, both `:via :declared`:

   KEEP-ALIVE markers keep a var reachable FROM the outside world —
   ^:entry-point (invoked via CLI/wire/eval injection), ^:unused-ok
   (deliberately uncalled), and the D-web declarations: a `:web/path`
   ENDPOINT (the dispatcher calls it) and a `:web/effect` / `:web/read`
   PERFORMER (the effect interpreter / reads loader calls it). `:from-ns`
   is `:external`; `:marker` preserves WHICH dial so the stale check can
   distinguish.

   A `^{:covers \"ns/name — why\"}` marker on a deftest is the other shape:
   a COVERAGE edge FROM the test TO each form it names — the dispatch /
   data / spawned-child-image path neither the static graph nor the
   in-image trace can see. `:marker :covers`, and `:from-ns`/`:from-var`
   are the TEST (so covered-by can report it). Coverage is not liveness —
   unused-report does not treat `:covers` as an exemption."
  [st _known nses]
  (concat
   (for [nsx (sort nses)
         e   (store/forms st nsx)
         :when (:name e)
         :let [s (try (n/sexpr (:node e)) (catch Exception _ nil))
               m (when (and (seq? s) (symbol? (second s))) (meta (second s)))
               marker (cond (:entry-point m) :entry-point
                            (:unused-ok m)   :unused-ok
                            (:web/path m)    :web-endpoint
                            (:web/effect m)  :web-effect
                            (:web/read m)    :web-read
                            :else nil)]
         :when marker]
     {:from-form nil
      :from-ns   :external
      :from-var  nil
      :to-ns     nsx
      :to-name   (:name e)
      :to-form   (:id e)
      :via       :declared
      :marker    marker})
   (for [nsx    (sort nses)
         e      (store/forms st nsx)
         :when  (:name e)
         :let   [s (try (n/sexpr (:node e)) (catch Exception _ nil))
                 m (when (and (seq? s) (symbol? (second s))) (meta (second s)))]
         target (covers-targets (:covers m))]
     {:from-form (:id e)
      :from-ns   nsx
      :from-var  (:name e)
      :to-ns     (symbol (namespace target))
      :to-name   (symbol (name target))
      :to-form   nil
      :via       :declared
      :marker    :covers})))

(defn- drop-self
  "Remove self-references — a form pointing at ITSELF (same form both ends).
  Not a reference: replacing the defn covers it. Uniform across producers
  (kondo excludes its own inline, but carrier/un-required needed this — a
  carrier self-ref was keeping dead forms alive)."
  [rs]
  (remove #(and (:from-form %) (= (:from-form %) (:to-form %))) rs))

^:reads (defn ^:export refs
  "EVERY reference in the store as canonical records — THE single source
  of truth for 'who references what'. Producers normalize here (kondo
  statics including un-required qualified calls, carrier positions,
  marker declarations); consumers — gates, unused, review, moves — query
  this and never re-integrate sources. Self-references excluded.
  Record: {:from-form fid|nil :from-ns sym|:external :from-var sym|nil
           :to-ns sym :to-name sym :to-form fid|nil
           :via :static|:carrier|:declared [:arity n] [:marker kw]}
  Derived (never stored — refs are an index of source), memoized on the
  immutable store value so repeated whole-graph queries within an
  operation are free."
  [st]
  (cache/cached-last
   ::refs st
   (fn []
     (let [known (set (keys (:namespaces st)))]
       (vec (drop-self
             (concat (static-refs st known (sort known))
                     (carrier-refs st known (sort known))
                     (declared-refs st known (sort known)))))))))

(defn ^:export ns-refs
  "The graph SLICE for one namespace's outbound references — the same
  canonical records `refs` yields, produced for `nsx` alone (the write
  gates run per write; a whole-store sweep there would be waste). Same
  producers, same record shape; scoping is an access path, not a dialect."
  [st nsx]
  (let [known (set (keys (:namespaces st)))]
    (vec (drop-self
          (concat (static-refs st known [nsx])
                  (carrier-refs st known [nsx])
                  (declared-refs st known [nsx]))))))

(defn ^:export observed-refs
  "RUNTIME evidence as graph records: the trace map ({test-qsym #{form-qsym}})
  says test T exercised form F — {:via :observed}. Session-grain input (the
  trace lives with the session, not the store), same canonical record shape;
  consumers merge these with the store graph when runtime truth matters
  (coverage, blast radius)."
  [tmap]
  (vec (for [[t forms] tmap
             f forms]
         {:from-form nil
          :from-ns   (symbol (namespace t))
          :from-var  (symbol (name t))
          :to-ns     (symbol (namespace f))
          :to-name   (symbol (name f))
          :to-form   nil
          :via       :observed})))

(defn ^:export covered-by
  "Every test that covers form `qsym`, each tagged with HOW we know — the
   canonical coverage edge set, the reference-graph epic's shape applied to
   'which test reaches this form'. Three producers, one answer:
   - :observed — the trace map saw the test exercise the form (strongest).
   - :static   — a deftest references the form within `depth` static hops
                 (default 2), so it works for tests that never trace (the
                 external tier) and needs no run; `:hops` is the distance.
   - :declared — a `^{:covers}` marker on the deftest names the form, for the
                 dispatch / data / spawned-child-image path neither static
                 reach nor the trace can see. No hops — it is a claim, direct.
   Returns `[{:test qsym :via #{…} :hops n?}]`, sorted. Neither :static nor
   :declared means verified — they say 'a test REACHES/CLAIMS this', not 'this
   was checked' — so `:via` stays visible and a consumer must weight :observed
   over the others rather than conflate them (do NOT let them claim green)."
  [st tmap qsym & {:keys [depth] :or {depth 2}}]
  (let [to-ns    (symbol (namespace qsym))
        to-nm    (symbol (name qsym))
        test-ns? (fn [ns] (str/ends-with? (str ns) "-test"))
        observed (into #{} (for [r (observed-refs tmap)
                                 :when (and (= to-ns (:to-ns r)) (= to-nm (:to-name r)))]
                             (symbol (str (:from-ns r)) (str (:from-var r)))))
        declared (into #{} (for [r (refs st)
                                 :when (and (= :declared (:via r))
                                            (= :covers (:marker r))
                                            (= to-ns (:to-ns r)) (= to-nm (:to-name r)))]
                             (symbol (str (:from-ns r)) (str (:from-var r)))))
        radj     (reduce (fn [m r]
                           (if (= :static (:via r))
                             (update m [(:to-ns r) (:to-name r)] (fnil conj #{})
                                     [(:from-ns r) (:from-var r)])
                             m))
                         {} (refs st))
        static   (loop [frontier #{[to-ns to-nm]} seen #{} hop 1 acc {}]
                   (if (or (empty? frontier) (> hop depth))
                     acc
                     (let [callers (into #{} (mapcat #(get radj %)) frontier)
                           fresh   (into #{} (remove seen) callers)
                           acc'    (reduce (fn [a [fns fv]]
                                             (if (test-ns? fns)
                                               (update a (symbol (str fns) (str fv))
                                                       (fnil min hop) hop)
                                               a))
                                           acc fresh)]
                       (recur fresh (into seen frontier) (inc hop) acc'))))
        tests    (into (sorted-set) (concat observed (keys static) declared))]
    (vec (for [t tests]
           (cond-> {:test t
                    :via  (cond-> #{}
                            (observed t) (conj :observed)
                            (static t)   (conj :static)
                            (declared t) (conj :declared))}
             (static t) (assoc :hops (static t)))))))

^:reads (defn ^:export refs-by-target
  "THE reverse index over `refs`: `{to-qsym [ref ...]}`, every reference
  grouped by the qualified symbol it points AT.

  `refs-to` filtered the whole record stream on every call, which is fine for
  one question and quadratic for a page that asks it per form (slopp's own
  store carries 7,578 edges). Grouping once turns every blast-radius and
  liveness question into a map lookup.

  Memoized on the immutable store value with the same `cached-last` strategy
  `refs` uses, and for the same reason: the store is too large to hash and a
  new value appears only on a write, so identity is a sound key. It is an
  INDEX, not a second producer — `refs` remains the single source of truth and
  this holds exactly its records, in its order."
  [st]
  (cache/cached-last
   ::refs-by-target st
   (fn []
     (reduce (fn [m r]
               (update m (symbol (str (:to-ns r)) (str (:to-name r)))
                       (fnil conj []) r))
             {}
             (refs st)))))

^:reads (defn ^:export refs-to
  "Every reference TO `qsym` (an ns/name symbol) — the blast-radius/liveness
  question, answered from THE graph.

  A lookup into `refs-by-target`, so asking this once per form costs one
  grouping pass over the store rather than one full scan per call."
  [st qsym]
  (get (refs-by-target st) qsym []))

(defn ^:export to-wire
  "Reference records → the COMPACT wire shape agents read (canonical maps
  stay internal; convert at the boundary, both directions, one place).
  Grouped by target: {:to qsym
                      :from [qsym ...]      ; the common case (:via :static)
                      :tagged [{:from qsym :via kw [:marker kw]} ...]}
  Self-describing qsyms — agents never think in form-ids; ~3-5× slimmer
  than records and repetition-free."
  [rs]
  (let [qsym  (fn [r] (when (:from-var r)
                        (symbol (str (:from-ns r)) (str (:from-var r)))))
        stat  (filter #(= :static (:via %)) rs)
        other (remove #(= :static (:via %)) rs)]
    (cond-> {:to (when-let [r (first rs)]
                   (symbol (str (:to-ns r)) (str (:to-name r))))}
      (seq stat)  (assoc :from (vec (sort (distinct (keep qsym stat)))))
      (seq other) (assoc :tagged
                         (vec (for [r other]
                                (cond-> {:via (:via r)}
                                  (qsym r)     (assoc :from (qsym r))
                                  (:marker r)  (assoc :marker (:marker r)))))))))

(defn ^:export cold-load-order
  "The namespace's forms reordered so every intra-ns definition precedes its
  callers — the arrangement a fresh load resolves top-to-bottom WITHOUT a
  declare. Kahn topological sort over THE reference graph's intra-ns edges
  (same pattern as store/ns-dependency-order, at form grain; ties break by
  original position, so an already-ordered ns is unchanged). Returns
  {:order [form-id ...] :cycle [qsym ...]|nil}: :order is the resolving
  sequence (the ns declaration always first); :cycle names the
  mutual-recursion group when no full order exists — those genuinely need a
  declare, which the reorder alone can't remove."
  [store nsx]
  (let [forms   (vec (store/forms store nsx))
        pos     (into {} (map-indexed (fn [i f] [(:name f) i])) forms)
        names   (set (keep :name forms))
        ;; intra-ns dependency: caller NEEDS callee before it
        needs   (reduce (fn [m r]
                          (if (and (= nsx (:to-ns r)) (:from-var r)
                                   (contains? names (:to-name r))
                                   (not= :declared (:via r)))
                            (update m (:from-var r) (fnil conj #{}) (:to-name r))
                            m))
                        {} (ns-refs store nsx))
        nm->id  (into {} (keep (fn [f] (when (:name f) [(:name f) (:id f)])) forms))
        ns-decl (some (fn [f] (when (= nsx (:name f)) (:id f))) forms)]
    ;; Kahn: repeatedly take the earliest-positioned form whose deps are done
    (loop [order (if ns-decl [ns-decl] [])
           remaining (vec (sort-by pos (remove #{nsx} (keep :name forms))))
           done #{}]
      (if (empty? remaining)
        {:order order :cycle nil}
        (if-let [ready (first (filter #(every? done (get needs % #{})) remaining))]
          (recur (conj order (nm->id ready))
                 (vec (remove #{ready} remaining))
                 (conj done ready))
          ;; nothing ready → the remainder is a dependency cycle
          {:order (into order (map nm->id) remaining)
           :cycle (vec (sort (map #(symbol (str nsx) (str %)) remaining)))})))))

(defn- form-keyword-uses
  "`[[kw via] …]` for one form's sexpr — every keyword it references, and HOW.

  `:literal` is a keyword token. `:destructuring` is a `{:keys [x]}` /
  `{:ns/keys [x]}` entry, which reads `:x` / `:ns/x` while containing no such
  token — the key is computed from the directive's namespace plus the
  symbol's NAME, so no text scan can see it.

  The `:keys`/`:strs`/`:syms` directive keyword itself is NOT reported as a
  literal use: `{:user/keys [id]}` references `:user/id`, not `:user/keys`.
  Quoted data is pruned by the shared `walk-pruned`."
  [s]
  (distinct
   (walk-pruned
    (fn [x]
      (cond
        (and (keyword? x) (not (#{"keys" "strs" "syms"} (name x))))
        [[x :literal]]

        (map? x)
        (for [[k v] x
              :when (and (keyword? k) (#{"keys" "strs" "syms"} (name k))
                         (vector? v))
              sym   v
              :when (symbol? sym)]
          [(keyword (namespace k) (name sym)) :destructuring])))
    s)))

^:reads (defn ^:export keyword-refs
  "EVERY keyword reference in the store as canonical records — the sibling of
  `refs` for keys, and the single source for 'who reads this key'.
  Record: `{:from-form fid :from-ns sym :from-var sym :kw kw
            :via :literal|:destructuring}`.

  A SIBLING index rather than rows in `refs`, because a keyword has no
  defining form: it cannot carry `:to-form`, and forcing it into the var
  record would let the keyword `:a.b/c` collide with a var `a.b/c` in every
  var-oriented consumer (the unused gate, module gates, cold-load order).

  Two ways a key is referenced, and only one is visible as text:

  - `:literal` — the keyword appears as a token.
  - `:destructuring` — `{:ns/keys [x]}` reads `:ns/x` while containing NO such
    token; the key is computed from the directive's namespace plus the
    symbol's NAME. Every text scan is blind to it, which made `query_depends`
    on a keyword return a silently INCOMPLETE blast radius — measured on this
    store, `:slopp.git/map-conn` reported six rows and omitted four
    consumers, every one a module-boundary fn that destructures it.

  Quote-pruned via the shared `walk-pruned`, and memoized on the immutable
  store value exactly as `refs` is."
  [st]
  (cache/cached-last
   ::keyword-refs st
   (fn []
     (vec (for [nsx  (sort (keys (:namespaces st)))
                e    (store/forms st nsx)
                :when (:name e)
                :let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
                [kw via] (when s (form-keyword-uses s))]
            {:from-form (:id e) :from-ns nsx :from-var (:name e)
             :kw kw :via via})))))

(def mention-kinds
  "Every way a NAME can APPEAR in this store, as data.

  This is the `:via` vocabulary [[occurrences-of]] and its siblings tag rows
  with, and the total account of what a re-addressing verb does with each. It
  lived as a TABLE IN A DOCSTRING, which is the failure it exists to fix:
  accurate prose that nothing could grade, and by the time it became data its
  `:register` row named three registers against a store that had five.

  `:handling` is the load-bearing field.

  | value | means |
  |---|---|
  | `:rewrite` | a re-addressing verb CHANGES it, and the change is verified |
  | `:report` | the verb SEES it, leaves it deliberately, and names it in `:left-behind` |
  | `:blind` | nothing produces a row at all |

  `:blind` (the field) states what the handling does NOT cover, and a kind
  whose `:handling` is `:blind` must carry one — that sentence is the only
  artifact a blind kind has. This is `crossings/kinds` one layer in, for the
  same reason: registering a kind verifies nothing, it makes the appearance
  ENUMERABLE. Until a kind is written down, \"nothing produces this\" and
  \"this store has none of these\" are one observation, which is the
  absence-of-check / absence-of-finding conflation moved up out of a check's
  RESULT and into the vocabulary the check reports in.

  `:producer` is the form that emits the row, and `nil` says outright that
  none does. Rows produced elsewhere name their producer qualified — the
  reference graph is not the only thing that answers this question, and a
  registry that listed only its own namespace's answers would be the same
  partial account in a new place."
  [{:kind :ns-form :producer 'occurrences-of :handling :rewrite
    :what "the target's OWN (ns …) declaration"
    :blind nil}

   {:kind :require :producer 'occurrences-of :handling :rewrite
    :what "another namespace's require clause"
    :blind "only the LIB symbol. The :as beside it is a different name that
            follows nothing — that is the :alias kind, and it needs both the
            old name and the new one to be judged at all"}

   {:kind :symbol :producer 'occurrences-of :handling :rewrite
    :what "a symbol token, INCLUDING a quoted one, which the CST rewrite
           reaches like any other"
    :blind "a symbol ASSEMBLED at runtime — (symbol (str base \".core\")) — is
            not a token, so no pass can see it and no report can name it"}

   {:kind :string :producer 'occurrences-of :handling :report
    :what "the name inside a string literal. :prose splits the risk: a string
           with whitespace is a docstring or message and a rename makes it
           WRONG, one without is a path, a main-ns or a require target and a
           rename BREAKS it"
    :blind "the string is reported WHOLE and parsed only far enough to ask the
            one question `:string-source` asks. Text carrying a qualified call
            into the target, or a require of it, is code just as much and
            arrives here reading like a path"}

   {:kind :keyword :producer 'occurrences-of :handling :report
    :what "a qualified keyword whose namespace segment is the target"
    :blind "deliberately never rewritten: a qualified keyword can be a wire or
            storage key something outside this store already holds, so each is
            a judgement rather than a substitution. It is also the SILENT
            member — a broken token string turns something red, while a stale
            keyword stays green and merely starts naming a namespace that is
            gone"}

   {:kind :test-sibling :producer 'occurrences-of :handling :report
    :what "the <target>-test namespace — a NAME by convention, not a reference"
    :blind "renaming it is a second ns_rename and nothing sequences the two,
            so between them the tests are filed under the old module"}

   {:kind :register :producer 'occurrences-of :handling :rewrite
    :what "a key, or a value, in one of store/name-keyed-registers — a
           declaration ABOUT a name rather than a use of it"
    :blind "the rewrite is by the RENAME (ns-grained-registers and
            rekey-module-registers), not by the CST pass, so a row here after
            a rename means a register did NOT follow. That is the safety net
            and it is only as total as store/name-keyed-registers is"}

   {:kind :alias :producer 'slopp.edit.refactor/stranded-aliases :handling :report
    :what "a require :as spelled from the OLD name and now pointing at the new
           one — the residue a rename leaves with no occurrence to find, since
           the lib symbol beside it is exactly what the rename rewrote"
    :blind "an ABBREVIATION (caps for …capabilities) is derived from nothing
            readable, so this reports the aliases it can PROVE stale, not
            every alias a rename made questionable"}

   {:kind :regex :producer 'occurrences-of :handling :report
    :what "a regex literal spelling the name with its dots escaped — a guard's
           search PATTERN is data, and the population is real: 14 sites in this
           store name a live slopp namespace inside one"
    :blind "the walk is over NODES and a node's METADATA is not one of its
            children, so a regex living in metadata is invisible here —
            measured zero store-wide the day rules/stale-pattern-check declared
            the same limit. A pattern ASSEMBLED at runtime from strings is a
            literal to neither of them.

            Reporting is the whole remedy and it is deliberately not a
            rewrite: the pattern may be matching TEXT rather than code, and
            substituting inside one silently changes what a guard searches
            for"}

   {:kind :string-source :producer 'occurrences-of :handling :report
    :what "SOURCE TEXT inside a string literal — a fixture's (ns …) form, a
           child JVM's program text — where the name is code the store never
           parsed. The one occurrence whose two halves are meant to AGREE: the
           quoted symbol beside it is a token and is rewritten, so the pair
           comes apart and the fixture ingests source declaring one namespace
           under the name of another"
    :blind "detection is the `(ns <target>)` DECLARATION only. A string
            carrying a qualified call into the target, or a require of it, is
            code just as much and still arrives as an ordinary `:string` row.
            Narrow on purpose: the declaration is the half that has actually
            shipped broken, twice, and a looser test promotes every docstring
            containing a parenthesis"}])

(defn ^:export occurrences-of
  "Every place `target` (a namespace name) APPEARS, whatever form the
  appearance takes — the occurrence set a rename must answer to.

  THE reference graph is a graph of var/namespace references discovered by
  ANALYSIS. That is the right model for \"who calls this\", and the wrong one
  for \"what would a rename miss\": a name also lives in strings, in qualified
  keywords, in a `-test` sibling's own name, and in the register keys, none of
  which are references. Each rename verb used to re-derive its own partial
  answer, so each had a different blind spot and none reported what it left
  behind.

  Rows are `{:ns :form :via :rewritable}`, plus `:text`/`:prose` on strings,
  `:text` on keywords, and `:register`/`:key`/`:value` on register rows.
  `:via` is the provenance and the whole value, and the vocabulary itself is
  [[mention-kinds]] — DECLARED there, not restated here. This docstring
  carried the table until 2026-08-06, and by then its `:register` row named
  three registers against a store that had five, with nothing able to notice
  because a table in prose grades nothing.

  What that registry adds beyond the spelling: each kind says whether a verb
  REWRITES it, REPORTS it, or is BLIND to it — and the two blind rows are the
  point. A kind nothing produces emits no row here, so before they were
  written down, `:regex` and `:string-source` were indistinguishable from
  kinds this store simply had none of.

  `:prose` splits the string rows the way the risk splits: a string with
  whitespace is a docstring or message (a rename makes it WRONG), one without
  is a path, a main-ns, or a require target (a rename BREAKS it). Measured on
  slopp's own store: 145 prose to 13 load-bearing, and the 13 included the
  generated `deps.edn` main-ns that killed every external test during a
  restructure.

  Keywords are the SILENT member of the set, and the reason they are worth a
  `:via` of their own: a broken token string turns something red, while
  `:billing.invoice/customer-id` survives `billing.invoice` → `billing.statement`
  intact and merely starts lying. Measured in anger during
  `slopp.api.telemetry` → `slopp.read.telemetry`, where
  `:slopp.api.telemetry/calls` stood in 3 forms across 5 sites through a green
  `full_check`. They stay non-rewritable on purpose: a qualified keyword can be
  a wire or storage key something outside the store already holds, so each one
  is a judgement rather than a substitution.

  This does not rewrite anything and takes no position on what should be.
  Conservative string handling is correct; being silent about it is not."
  [store target]
  (let [t     (str target)
        pfx   (str t ".")
        under (fn [s] (or (= s t) (str/starts-with? s pfx)))
        scan  (fn scan [node ns-sym form-name ns-form?]
                (when node
                  (let [s    (when (n/sexpr-able? node)
                               (try (n/sexpr node) (catch Exception _ nil)))
                        here (cond
                               (and (symbol? s)
                                    (or (under (str s))
                                        (and (namespace s) (under (namespace s)))))
                               [{:ns ns-sym :form form-name :rewritable true
                                 :via (cond (not ns-form?)    :symbol
                                            (= ns-sym target) :ns-form
                                            :else             :require)}]

                               (and (string? s) (str/includes? s t))
                               ;; a string whose text DECLARES the target is
                               ;; CODE, and the one occurrence whose two halves
                               ;; are meant to agree: the quoted symbol beside
                               ;; it IS rewritten, so a fixture comes out
                               ;; ingesting source that declares one namespace
                               ;; under the name of another. `:prose` is
                               ;; deliberately absent from that row — source is
                               ;; neither prose nor a path, and grading it prose
                               ;; says a rename merely makes it read wrong.
                               (if (re-find (re-pattern
                                             (str "\\(ns\\s+"
                                                  (str/replace t "." "\\.")
                                                  "[\\s)]"))
                                            s)
                                 [{:ns ns-sym :form form-name :via :string-source
                                   :rewritable false
                                   :text s}]
                                 [{:ns ns-sym :form form-name :via :string
                                   :rewritable false
                                   :text s
                                   :prose (boolean (re-find #"\s" s))}])

                               ;; both halves, matching the symbol branch: a
                               ;; qualified `:t/x` is the measured case, and a
                               ;; bare `:t` is how a config or registry key
                               ;; names a namespace
                               (and (keyword? s)
                                    (or (and (namespace s) (under (namespace s)))
                                        (under (name s))))
                               [{:ns ns-sym :form form-name :via :keyword
                                 :rewritable false
                                 :text (str s)}]

                               ;; a REGEX literal. rewrite-clj renders one as
                               ;; `(re-pattern "…")`, and the dots are escaped
                               ;; — which is why the prose sweep misses the
                               ;; spelling as well. Same extraction as
                               ;; `rules/stale-pattern-check`, deliberately: two
                               ;; readers of one appearance that derive it
                               ;; separately are two readers that can disagree.
                               (and (seq? s) (= 're-pattern (first s))
                                    (string? (second s))
                                    (str/includes?
                                     (str/replace (second s) "\\" "") t))
                               [{:ns ns-sym :form form-name :via :regex
                                 :rewritable false
                                 :text (second s)}]

                               :else [])]
                    (into here
                          (when (n/inner? node)
                            (mapcat #(scan % ns-sym form-name ns-form?)
                                    (n/children node)))))))]
    (vec
     (concat
      (for [n (sort (keys (:namespaces store)))
            f (store/forms store n)
            :when (:node f)
            row (scan (:node f) n (:name f) (= (:name f) n))]
        row)
      (let [sibling (symbol (str t "-test"))]
        (when (contains? (:namespaces store) sibling)
          [{:ns sibling :via :test-sibling :rewritable false}]))
      ;; every DECLARATION register, from the one list of them. Hand-enumerated
      ;; here it was three against a store of five, so a :module-roles or
      ;; :module-test-edges row naming the target had nothing that could report
      ;; it — and :module-test-edges also had nothing that re-keyed it.
      (for [[reg row] store/name-keyed-registers
            :when (= :declaration (:kind row))
            k     (keys (get store reg))
            :when (under (str k))]
        {:register reg :key (str k) :via :register :rewritable false})
      ;; the manifest names a module on BOTH sides of an edge, so a scan of
      ;; keys alone reports half of it
      (for [[reg row] store/name-keyed-registers
            :when (and (= :declaration (:kind row)) (:in-values row))
            [k vs] (get store reg)
            v      vs
            :when  (under (str v))]
        {:register reg :key (str k) :value (str v)
         :via :register :rewritable false})))))
