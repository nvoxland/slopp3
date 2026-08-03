(ns slopp.read.graph
  "How forms REACH each other, asked at the query grain: references, the
  callee tree, blast radius, where a keyword flows, and the generic
  dependency front door behind `query_depends`.

  The relationships themselves live lower — `slopp.index.refs` IS the
  reference graph and `slopp.index.crossings` the edges that leave the store.
  This is the READING layer over them: it turns an edge set into the answer
  somebody asked for, and it owns the shapes those answers share.
  `coverage-view` is the `:covered-by` map that both `query-impact` and
  `query-brief` report, in ONE spelling precisely so the two cannot drift.

  Split out of `slopp.read.query`, which held this beside two unrelated
  subjects. The split was not a judgement call. Partition that namespace by
  the four kinds its own docstring named, and every internal call turns out
  to fall INSIDE a cluster — source, graph and time reach each other not at
  all, and only the composite driver reads cross. Take those two away and the
  namespace is three disconnected components. What had held it together was
  that the drivers are built from all three, which is a dependency
  relationship rather than a shared subject."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.edit :as edit]
            [slopp.edit.modules :as edit.modules]
            [slopp.index.analyze :as analyze]
            [slopp.index.derive :as derive]
            [slopp.index.refs :as refs]
            [slopp.read.modules :as modules]
            [slopp.rules.shape :as shape]
            [slopp.store :as store]
            [slopp.store.render :as render]))

(defn ^:export query-references
  "Usages of `ns-sym/nm` across EVERY namespace (F-3c3 — same-ns-only results
  sent an eval agent to query_search instead; analyses are memo-cached, so the
  full scan is cheap)."
  [session ns-sym nm]
  (let [st (:store @session)]
    (vec (mapcat (fn [n]
                   (derive/references (analyze/analyze (render/render-ns st n))
                                     ns-sym nm))
                 (sort (keys (:namespaces st)))))))

(defn ^:export callee-adjacency
  "qsym → sorted vector of STORE-INTERNAL callee qsyms, across every ns."
  [st]
  (let [internal? (:namespaces st)]
    (reduce
     (fn [adj ns-sym]
       (let [an (analyze/analyze (render/render-ns st ns-sym))]
         (reduce (fn [adj u]
                   (if (and (:from-var u) (internal? (:to u)))
                     (update adj
                             (symbol (str (:from u)) (str (:from-var u)))
                             (fnil conj (sorted-set))
                             (symbol (str (:to u)) (str (:name u))))
                     adj))
                 adj (:var-usages an))))
     {}
     (keys (:namespaces st)))))

(defn ^:export query-deps
  "The transitive CALLEE tree of ns/name (store-internal): what does this
  form reach? The planning input for extractions and blast-radius checks.
  Returns {:root q :calls {qsym [callees...]}} for every reachable form."
  [session ns-sym nm]
  (let [st   (:store @session)
        adj  (callee-adjacency st)
        root (symbol (str ns-sym) (str nm))]
    (loop [calls {} frontier [root]]
      (if-let [q (first frontier)]
        (if (contains? calls q)
          (recur calls (subvec frontier 1))
          (let [cs (vec (get adj q []))]
            (recur (assoc calls q cs) (into (subvec frontier 1) cs))))
        {:root root :calls calls}))))

(defn ^:export query-flow
  "Rock 4: where a FIELD flows — every form using keyword `kw` (\":rush?\"),
  with the using lines. The cross-namespace thread an agent otherwise
  re-derives by reading each layer.

  Reads `edit.refs/keyword-refs` — THE keyword graph — rather than scanning
  text, so a key read by DESTRUCTURING is included: `{:user/keys [id]}` uses
  `:user/id` while containing no such token, and a text scan silently omitted
  exactly the module-boundary fns that destructure a handle. Rows from a
  destructuring carry `:via :destructuring`."
  [session kw]
  (let [target (keyword (str/replace (str kw) #"^:" ""))
        st     (:store @session)
        dpat   (re-pattern (str "(?<![\\w.:-])"
                                (java.util.regex.Pattern/quote
                                 (str ":" (some-> (namespace target) (str "/")) "keys"))
                                "(?![\\w-])"))
        lpat   (re-pattern (str "(?<![\\w.:-])"
                                (java.util.regex.Pattern/quote (str target))
                                "(?![\\w?!*+<>=-])"))]
    (->> (refs/keyword-refs st)
         (filter #(= target (:kw %)))
         (sort-by (juxt (comp str :from-ns) (comp str :from-var)))
         (mapv (fn [{:keys [from-ns from-var via]}]
                 (let [src   (some-> (store/form-named st from-ns from-var)
                                     :node n/string)
                       pat   (if (= :destructuring via) dpat lpat)
                       lines (when src
                               (filterv #(re-find pat %) (str/split-lines src)))]
                   (cond-> {:ns from-ns :form from-var
                            :lines (mapv str/trim (take 3 lines))}
                     (= :destructuring via) (assoc :via :destructuring))))))))

(defn ^:export coverage-view
  "The `:covered-by` shape both `query-impact` and `query-brief` report:
   `{:count n :tests [first 8] :more k}`. Capped because a central form is
   covered by HUNDREDS of tests — `slopp.ops.external/open!` by 284 — and printing
   them all pushed the keys the caller actually asked for past the response
   trim, making a working answer read as a broken one. The remainder is
   COUNTED, never silently dropped."
  [test-syms]
  (let [ts (vec test-syms)]
    (cond-> {:count (count ts) :tests (vec (take 8 ts))}
      (> (count ts) 8) (assoc :more (- (count ts) 8)))))

(defn ^:export query-impact
  "Rock 4: the blast radius of reshaping `ns-sym/nm`, answered from THE
  reference graph — call sites grouped per caller form (:calls),
  value/higher-order references (:value-refs — a template rewrite can't
  reach those), CARRIER references (:carrier-refs — quoted-symbol
  positions; signature templates can't reach those either), outside-world
  declarations (:declared), and the tests runtime evidence says exercise
  it (:covered-by — the graph's :observed records, as {:count :tests :more}:
  capped at 8 with the remainder counted, since a central form has hundreds). change_signature's discovery as a READ: plan the edit before paying for it.

  When the form takes or is passed a MAP, `:shape` answers the other half —
  the keys it READS off its first argument (destructured, body, `:=>` schema,
  `:or`-optional) against the literal keys its callers PASS, grouped by
  key-set, with the diff in `:mismatch`. Renaming a key, or wondering who
  supplies one, is a read here rather than a grep. `:unknown-shape` names the
  callers passing a non-literal: a syntactic reader cannot see through a
  binding, so trust `:mismatch` only as far as that list is empty."
  [session ns-sym nm]
  (let [st (:store @session)]
    (if-not (store/form-named st ns-sym nm)
      (edit/missing-form-error st ns-sym nm)
      (let [qsym    (symbol (str ns-sym) (str nm))
            rs      (refs/refs-to st qsym)
            statics (filter #(= :static (:via %)) rs)
            callers (->> statics
                         (group-by (juxt :from-ns :from-var))
                         (mapv (fn [[[nsx from] us]]
                                 {:ns nsx :form from
                                  :calls (count (keep :arity us))
                                  :value-refs (count (remove :arity us))}))
                         (sort-by (juxt (comp str :ns) (comp str :form)))
                         vec)
            carried (vec (sort (distinct
                                (for [r rs :when (= :carrier (:via r))]
                                  (symbol (str (:from-ns r)) (str (:from-var r)))))))
            marks   (vec (sort (remove #{:covers} (keep :marker rs))))
            all-ts  (->> (refs/covered-by st (:test-map @session) qsym)
                         ;; the canonical coverage edge set, sliced to the tests
                         ;; that EXERCISE or CLAIM this form — observed evidence
                         ;; plus ^{:covers} declarations (the dispatch path the
                         ;; trace never records). Static reach is excluded here:
                         ;; :covered-by means "covers it", not "might reach it".
                         (filter #(some #{:observed :declared} (:via %)))
                         (map :test)
                         sort vec)
            ;; a central form is covered by HUNDREDS of tests. Printing them
            ;; all pushed the keys actually asked for past the response
            ;; trim — a working answer read as a broken one.
            tests   (coverage-view all-ts)
            shp     (shape/shape-of st ns-sym nm callers)]
        (cond-> {:target qsym :callers callers :covered-by tests}
          (seq carried) (assoc :carrier-refs carried)
          (seq marks)   (assoc :declared marks)
          shp           (assoc :shape shp)
          (or (some (comp pos? :value-refs) callers) (seq carried))
          (assoc :hint (str "value/higher-order and carrier refs can't be"
                            " template-rewritten — change_signature handles"
                            " :calls; edit the others by hand")))))))

^:reads (defn ^:export query-depends
  "The generic dependency front door: what depends on `on` (`:direction
  :dependents`, the default) or what `on` depends on (`:direction
  :dependencies`), where `on` is a NAMESPACE, a VAR (\"ns/name\"), or a
  KEYWORD (\":dest-zone\"). Dependents: ns → who requires it + qualified
  refs; var → blast radius (callers, value refs, covering tests); keyword
  → the field's flow. Dependencies: var → the transitive callee tree; ns
  → its requires. `:modules true` (no `on`) → the module graph (:manifest=DECLARED, :layers/:cycles=PRODUCTION-only; declared
  edges + any standing debt). One tool to ask — results carry :kind."
  [session on & {:keys [direction modules detail] :or {direction :dependents}}]
  (let [st (:store @session)]
    (if modules
      (if (seq (str on))
        (assoc (modules/module-surface session on) :kind :module-surface)
        (let [manifest (or (edit.modules/modules-manifest st) {})
              rows     (edit.modules/module-usage-rows st)
              actual   (into #{}
                             (comp (map (fn [{:keys [from-ns to]}]
                                          [(edit.modules/module-of from-ns)
                                           (edit.modules/module-of to)]))
                                   (remove (fn [[a b]] (= a b))))
                             rows)
              unused   (vec (for [[m ds] (sort manifest)
                                  d      (sort ds)
                                  :when  (not (contains? actual [m d]))]
                              [m d]))
              over     (modules/overstated-edges st rows)
              ;; layers/cycles reflect PRODUCTION architecture (test fixtures excluded);
              ;; :manifest below stays the DECLARED/enforced set
              graph    (store/module-layers (modules/production-manifest st rows))]
          (cond-> {:kind :modules
                   :manifest (into (sorted-map)
                                   (map (fn [[m ds]] [m (vec (sort ds))]))
                                   manifest)
                   :layers (:layers graph)
                   :debt (modules/module-debt st rows)
                   :purity (let [p (modules/purity-standing st)]
                             ;; :could-tighten is a one-time ADOPTION worklist, but it
                             ;; rode every response: 2,304 of 5,584 chars (41%) on the
                             ;; eval9 seed, byte-identical across three calls in one
                             ;; lifetime. Names answer "which modules?"; the per-module
                             ;; :declared/:supports detail is one flag away.
                             (if detail
                               p
                               (cond-> (assoc p :could-tighten
                                              (vec (sort (keys (:could-tighten p)))))
                                 (seq (:could-tighten p))
                                 (assoc :note (str "could-tighten lists module NAMES —"
                                                   " query_depends {modules true, detail true}"
                                                   " adds each one's declared/supports")))))}
            (seq (:module-platforms st))
            ;; declared target platforms (undeclared = :jvm, so absent here) —
            ;; answers "which namespaces are :cljs client / :cljc shared?" (D-web-cljs)
            (assoc :platforms (into (sorted-map) (:module-platforms st)))

            (seq (:cycles graph))
            (assoc :cycles (:cycles graph))

            (seq (edit.modules/module-test-manifest st))
            ;; a SEPARATE relation, so a reader who saw only :modules would
            ;; conclude these crossings are undeclared debt when they are
            ;; declared and deliberate
            (assoc :test-edges (into (sorted-map)
                                     (map (fn [[m ds]] [m (vec (sort ds))]))
                                     (edit.modules/module-test-manifest st))
                   :test-edges-note (str "edges a module's -test namespaces may"
                                         " cross and its production code may"
                                         " NOT — not production edges, so they"
                                         " are absent from :layers and :cycles"
                                         " by construction"))

            (seq unused)
            (assoc :unused-edges unused
                   :unused-note (str "declared but no call uses them —"
                                     " module_dep {from .. to .. remove true}"
                                     " retires an edge"))

            (seq over)
            ;; the unused report's sibling, and invisible to it: something DOES
            ;; cross these, just never production code. Worth a line because a
            ;; declared edge is what the CYCLE check reads, so one of these can
            ;; refuse a legitimate declaration in an unrelated module.
            (assoc :overstated-edges over
                   :overstated-note (str "declared as PRODUCTION but only -test"
                                         " namespaces cross them — the manifest"
                                         " claims a dependency the production"
                                         " code does not have, and the cycle"
                                         " check believes it. module_dep"
                                         " {from .. to .. test_only true} then"
                                         " {.. remove true} says it honestly")))))
      (let [on (str/trim (str on))]
        (cond
          (str/starts-with? on ":")
          {:kind :keyword :on on :rows (query-flow session on)}

          (str/includes? on "/")
          (let [[nsx nm] (str/split on #"/" 2)]
            (if (= :dependencies direction)
              (let [r (query-deps session (symbol nsx) (symbol nm))]
                (assoc r :kind :var :on on :direction :dependencies))
              (let [r (query-impact session (symbol nsx) (symbol nm))]
                (if (:error r) r (assoc r :kind :var :on on)))))

          (contains? (:namespaces st) (symbol on))
          (let [target   (symbol on)
                requires (vec (sort (distinct (vals (edit/require-aliases st target)))))]
            (if (= :dependencies direction)
              {:kind :namespace :on target :direction :dependencies
               :requires requires}
              (let [req-set     (fn [nsx] (set (vals (edit/require-aliases st nsx))))
                    required-by (vec (sort (filter #(and (not= % target)
                                                         (contains? (req-set %) target))
                                                   (keys (:namespaces st)))))
                    pat         (re-pattern (str "(?<![\\w.-])"
                                                 (java.util.regex.Pattern/quote on) "/"))
                    refs        (vec (for [nsx (sort (keys (:namespaces st)))
                                           :when (not= nsx target)
                                           e (store/forms st nsx)
                                           :when (and (:name e)
                                                      (re-find pat (n/string (:node e))))]
                                       {:ns nsx :form (:name e)}))]
                {:kind :namespace :on target
                 :required-by required-by
                 :requires requires
                 :qualified-refs (vec (take 20 refs))})))

          :else
          {:error (str "nothing named " on
                       " — `on` is a namespace, var (ns/name), or :keyword;"
                       " modules true reads the module manifest")})))))
