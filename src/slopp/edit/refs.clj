(ns slopp.edit.refs
  "THE reference graph: every reference in a store as one canonical record
  stream — producers (kondo, carrier walks, declarations) normalize HERE;
  consumers (gates, unused, review, moves) never re-integrate sources.
  Derived and content-memoized, never stored: references are an index of
  source, and the journal owes them no consistency."
  (:require [rewrite-clj.node :as n]
            [slopp.index :as index]
            [slopp.render :as render]
            [slopp.store :as store] [clojure.string :as str]))
(defn quote-pruned-qualified-syms
  "Every namespace-qualified symbol in sexpr `x`, skipping quoted subtrees —
  a quoted symbol is data and never resolves, so it isn't a call."
  [x]
  (cond
    (and (seq? x) (= 'quote (first x))) nil
    (symbol? x) (when (namespace x) [x])
    (map-entry? x) (concat (quote-pruned-qualified-syms (key x))
                           (quote-pruned-qualified-syms (val x)))
    (coll? x) (mapcat quote-pruned-qualified-syms x)
    :else nil))
(defn- static-refs
  "kondo-resolved var usages PLUS syntactically-qualified references into
  store namespaces kondo can't resolve (un-required — the gate-hole class),
  normalized to canonical records. Self-references excluded."
  [st known nses]
  (mapcat
   (fn [nsx]
     (let [fid-of (into {} (keep (fn [e] (when (:name e) [(:name e) (:id e)])))
                        (store/forms st nsx))
           kondo  (for [u (:var-usages (index/analyze (render/render-ns st nsx)))
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
           seen   (set (map (juxt :from-var :to-ns :to-name) kondo))
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
       (concat kondo unreq)))
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
          s ((fn walk [f]
               (cond
                 (and (seq? f) (= 'quote (first f))) nil
                 (seq? f)
                 (concat
                  (when (and (symbol? (first f)) (carrier? (name (first f))))
                    (for [a (rest f)
                          :when (and (seq? a) (= 'quote (first a))
                                     (symbol? (second a))
                                     (namespace (second a)))]
                      (second a)))
                  (mapcat walk f))
                 (coll? f) (mapcat walk f)
                 :else nil))
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
(defn- declared-refs
  "Marker declarations as edges FROM the outside world: ^:entry-point
  (invoked via CLI/wire/eval injection) and ^:unused-ok (deliberately
  uncalled) both keep a var alive in the graph; :marker preserves WHICH
  dial so consumers like the stale check can distinguish."
  [st _known nses]
  (for [nsx (sort nses)
        e   (store/forms st nsx)
        :when (:name e)
        :let [s (try (n/sexpr (:node e)) (catch Exception _ nil))
              m (when (and (seq? s) (symbol? (second s))) (meta (second s)))
              marker (cond (:entry-point m) :entry-point
                           (:unused-ok m)   :unused-ok
                           :else nil)]
        :when marker]
    {:from-form nil
     :from-ns   :external
     :from-var  nil
     :to-ns     nsx
     :to-name   (:name e)
     :to-form   (:id e)
     :via       :declared
     :marker    marker}))
(defn ^:export refs
  "EVERY reference in the store as canonical records — THE single source
  of truth for 'who references what'. Producers normalize here (kondo
  statics including un-required qualified calls, carrier positions,
  marker declarations); consumers — gates, unused, review, moves — query
  this and never re-integrate sources.
  Record: {:from-form fid|nil :from-ns sym|:external :from-var sym|nil
           :to-ns sym :to-name sym :via :static|:carrier|:declared
           [:marker :entry-point|:unused-ok]}
  Derived (rides the memoized kondo cache), never stored: references are
  an index of source, and the journal owes them no consistency."
  [st]
  (let [known (set (keys (:namespaces st)))]
    (vec (concat (static-refs st known (sort known))
                 (carrier-refs st known (sort known))
                 (declared-refs st known (sort known))))))
(defn ^:export ns-refs
  "The graph SLICE for one namespace's outbound references — the same
  canonical records `refs` yields, produced for `nsx` alone (the write
  gates run per write; a whole-store sweep there would be waste). Same
  producers, same record shape; scoping is an access path, not a dialect."
  [st nsx]
  (let [known (set (keys (:namespaces st)))]
    (vec (concat (static-refs st known [nsx])
                 (carrier-refs st known [nsx])
                 (declared-refs st known [nsx])))))
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
(defn ^:export refs-to
  "Every reference TO `qsym` (an ns/name symbol) — the blast-radius/liveness
  question, answered from THE graph."
  [st qsym]
  (let [to-ns (symbol (namespace qsym))
        to-nm (symbol (name qsym))]
    (vec (filter #(and (= to-ns (:to-ns %)) (= to-nm (:to-name %)))
                 (refs st)))))
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
