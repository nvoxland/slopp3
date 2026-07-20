(ns slopp.api.review
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.edit.modules :as edit.modules]
            [slopp.edit.refs :as refs]
            [slopp.index :as index]
            [slopp.render :as render]
            [slopp.store :as store]))

^:reads (defn ^:export review-scan
  "Whole-codebase (or one-ns) REVIEW TRIAGE — the fileless store's answer
  to 'where do I look first'. For every form it surfaces what the store
  knows and files don't: tested?, blast radius (callers), size, lint
  findings, undocumented public surface, effects — then RISK-RANKS so a
  reviewer reads the dangerous forms first instead of eyeballing
  everything. Coverage is STATIC (a form reachable in the call graph from
  any test namespace is covered) so the signal survives ^:external tests,
  which never touch the in-image trace map; the trace map, when warm,
  refines it. ONE analysis pass (analyze + lint share the memoized kondo).
  Drill into a flagged form with query_slice. `:ns` scopes to one
  namespace; `:limit` caps the rows (default 25), the tail in :omitted.
  Clean forms drop out."
  [session & {:keys [ns limit] :or {limit 25}}]
  (let [st    (:store @session)
        nses  (if ns [(symbol (str ns))] (sort (keys (:namespaces st))))
         ;; retired: the graph owns the known-set
        tmap  (:test-map @session)
        rendered (into {} (map (fn [n] [n (render/render-ns st n)])) nses)
        ;; one analyze per ns → every store-internal call edge
        ;; THE reference graph — whole-store edges (carriers included), so
        ;; caller counts are true even in :ns-scoped scans
        usages (for [r (refs/refs st)
                     :when (and (not= :declared (:via r)) (:from-var r))]
                 [(symbol (str (:from-ns r)) (str (:from-var r)))
                  (symbol (str (:to-ns r)) (str (:to-name r)))])
        blast (frequencies (for [[from to] usages :when (not= from to)] to))
        adj   (reduce (fn [m [from to]] (update m from (fnil conj #{}) to)) {} usages)
        ;; STATIC coverage: everything reachable from a test ns's forms
        test-seed (set (for [nsx nses
                             :when (str/ends-with? (str nsx) "-test")
                             e (store/forms st nsx) :when (:name e)]
                         (symbol (str nsx) (str (:name e)))))
        covered-static (loop [seen #{} frontier test-seed]
                         (if (empty? frontier)
                           seen
                           (let [seen' (into seen frontier)]
                             (recur seen' (into #{} (comp (mapcat adj) (remove seen'))
                                                frontier)))))
        lint-by-form (frequencies
                      (for [[nsx src] rendered
                            f (index/lint src)
                            :let [e (render/owner-form st nsx (:row f) (:col f))]
                            :when e]
                        (symbol (str nsx) (str (or (:name e) (:id e))))))
        rows (for [nsx nses
                   e (store/forms st nsx)
                   :when (:name e)
                   :let [nm       (:name e)
                         q        (symbol (str nsx) (str nm))
                         s        (try (n/sexpr (:node e)) (catch Exception _ nil))
                         skip?    (and (seq? s) (contains? '#{deftest ns} (first s)))
                         test?    (str/ends-with? (str nsx) "-test")
                         traced   (let [ks (store/form-trace-keys nsx e)]
                                    ;; any name the form defines carries evidence (#129)
                                    (count (keep (fn [[t fs]] (when (some fs ks) t)) tmap)))
                         callers  (get blast q 0)
                         loc      (count (str/split-lines (n/string (:node e))))
                         lints    (get lint-by-form q 0)
                         bang?    (str/ends-with? (str nm) "!")
                         doc?     (some? (edit.modules/missing-doc-warning st nsx nm))
                         ;; zero-caller PUBLICS need the whole graph — only a
                         ;; full scan sees every caller, so :ns scoping skips it
                         unused   ;; the whole-store graph makes caller counts true even
                         ;; under :ns scoping — the flag works everywhere
                         (and (not test?)
                              (zero? callers)
                              (seq? s)
                              (contains? '#{defn def} (first s))
                              (not (:private (meta (second s))))
                              (not (:unused-ok (meta (second s))))
                              (not (:entry-point (meta (second s))))
                              (not= '-main nm))
                         untested (and (not test?)
                                       ;; a plain (def x <data>) has no invocation to
                                       ;; trace, so it can never acquire evidence —
                                       ;; flagging it is a finding nobody can discharge.
                                       ;; defn/defmulti stay flaggable: they are callable.
                                       (not (and (seq? s) (= 'def (first s))))
                                       (zero? traced)
                                       (not (contains? covered-static q)))
                         flags    (cond-> []
                                    untested       (conj :untested)
                                    unused         (conj :unused)
                                    (>= callers 8) (conj :high-blast)
                                    (>= loc 50)    (conj :large)
                                    (pos? lints)   (conj :lint)
                                    doc?           (conj :undocumented)
                                    bang?          (conj :effectful))
                         risk     (+ (if untested 4 0)
                                     (if unused 2 0)
                                     (cond (>= callers 8) 2 (>= callers 3) 1 :else 0)
                                     (cond (>= loc 50) 2 (>= loc 30) 1 :else 0)
                                     (min 2 lints)
                                     (if doc? 1 0)
                                     (if bang? 1 0))]
                   :when (and (not skip?) (pos? risk))]
               {:form q :risk risk :loc loc :callers callers
                :covered traced :flags flags})
        ranked (sort-by (juxt (comp - :risk) (comp - :callers) (comp str :form)) rows)]
    (cond-> {:reviewed (if ns (str ns) (str (count nses) " namespaces"))
             :forms    (reduce + 0 (map #(count (filter :name (store/forms st %))) nses))
             :flagged  (count rows)
             :top      (vec (take limit ranked))
             :totals   (into (sorted-map) (frequencies (mapcat :flags rows)))
             ;; the SHAPE of form sizes, not just how many cross 50 loc:
             ;; decomposing a god-form ADDS forms, so the :large count can
             ;; rise while the codebase genuinely improves. Max and median
             ;; move the right way.
             :loc      (let [sized (for [nsx nses
                                        e (store/forms st nsx)
                                        :when (:name e)]
                                    [(symbol (str nsx) (str (:name e)))
                                     (count (str/split-lines (n/string (:node e))))])
                             ls    (sort (map second sized))
                             n     (count ls)]
                         (when (pos? n)
                           {:max     (last ls)
                            :largest (first (last (sort-by second sized)))
                            :p95     (nth ls (min (dec n) (int (* 0.95 n))))
                            :median  (nth ls (quot n 2))}))}
      (> (count rows) limit) (assoc :omitted (- (count rows) limit)))))
