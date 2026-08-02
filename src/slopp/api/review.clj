(ns slopp.api.review
  "Whole-codebase TRIAGE: which forms should a reviewer read first, and why.

  It exists because the obvious reading order is the wrong one. A reviewer
  arriving at a store has no file to open and no diff to follow, and reading
  namespaces alphabetically spends the most attention on whatever sorts first.
  So this derives risk per form from signals the store already holds — observed
  coverage, caller count, size, lint, docs, effectfulness — and ranks by it.

  **The hard part is not the ranking, it is what a flag is allowed to CLAIM.**
  Every signal here is an inference from evidence that can be absent for
  innocent reasons, and a flag that overstates its evidence produces a list
  whose top rows cannot be acted on — which is worse than no list, because
  people stop reading it. Hence the exclusions each carry their reason: a plain
  `(def x <data>)` can never acquire trace evidence, a `^{:covers}` marker names
  a dispatch path nothing can see, and `:cljs` is reported as `:off-platform`
  rather than `:untested` because no test could ever cover it. When adding a
  signal, the question to answer first is what its absence could innocently mean.

  Read-only over the store value plus the session's trace map; the done-point's
  own findings live in `slopp.api.done`."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.edit.modules :as edit.modules]
            [slopp.index.refs :as refs]
            [slopp.index :as index]
            [slopp.store.render :as render]
            [slopp.store :as store] [slopp.api.done :as done]))

^:reads (defn ^:export review-scan
  "Whole-codebase (or one-ns) REVIEW TRIAGE — the fileless store's answer
  to 'where do I look first'. For every form it surfaces what the store
  knows and files don't: tested?, blast radius (callers), size, lint
  findings, undocumented public surface, effects — then RISK-RANKS so a
  reviewer reads the dangerous forms first instead of eyeballing
  everything. Coverage is STATIC (a form reachable in the call graph from
  any test namespace is covered) so the signal survives ^:external tests,
  which never touch the in-image trace map; the trace map, when warm,
  refines it, and a ^{:covers} DECLARATION discharges the dispatch/data
  path neither reach nor trace can see. ONE analysis pass (analyze + lint
  share the memoized kondo). Drill into a flagged form with query_slice.
  `:ns` scopes to one namespace; `:limit` caps the rows (default 25), the
  tail in :omitted. Clean forms drop out."
  [session & {:keys [ns limit] :or {limit 25}}]
  (let [st    (:store @session)
        nses  (if ns [(symbol (str ns))] (sort (keys (:namespaces st))))
        ;; retired: the graph owns the known-set
        tmap  (:test-map @session)
        rendered (into {} (map (fn [n] [n (render/render-ns st n)])) nses)
        ns-lint (into (sorted-map)
                      (for [[nsx src] rendered
                            :let [c (count (for [f (index/lint src (store/kondo-lang st nsx))
                                                 :let [e (render/owner-form st nsx (:row f) (:col f))
                                                       s (when e (try (n/sexpr (:node e)) (catch Exception _ nil)))]
                                                 :when (and (seq? s) (= 'ns (first s))
                                                            (not (done/marked-unused? st nsx f)))]
                                             f))]
                            :when (pos? c)]
                        [nsx c]))
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
        ;; DECLARED coverage: a ^{:covers} marker names a form the graph and
        ;; the trace both miss (dispatch/data/child-image). Whole-store so
        ;; :ns scoping still sees the declaration in the test namespace.
        covered-declared (set (for [r (refs/refs st)
                                    :when (= :covers (:marker r))]
                                (symbol (str (:to-ns r)) (str (:to-name r)))))
        ;; DECLARED LIVENESS: something outside the store references this —
        ;; `^:entry-point`, `^:unused-ok`, and the D-web declarations (a
        ;; `:web/path` endpoint the dispatcher calls, a `:web/read` /
        ;; `:web/effect` performer the interpreter resolves by name). The graph
        ;; already models every one as a `:via :declared` edge from `:external`.
        ;;
        ;; This used to be five hand-written metadata checks a few lines below,
        ;; re-deriving from `(meta (second s))` exactly what `usages` had just
        ;; discarded by filtering `:declared` out — and each was added only when
        ;; its absence bit somebody. Excluding declared edges from the CALLER
        ;; COUNT is right (the framework is not an in-store call site and would
        ;; inflate blast radius); deriving LIVENESS from that same count is what
        ;; was wrong. Two questions, one number.
        ;;
        ;; `:covers` is excluded because coverage is not liveness — the same
        ;; distinction `unused-report` makes.
        declared-alive (set (for [r (refs/refs st)
                                 :when (and (= :declared (:via r))
                                            (not= :covers (:marker r)))]
                             (symbol (str (:to-ns r)) (str (:to-name r)))))
        lint-by-form (frequencies
                      (for [[nsx src] rendered
                            f (index/lint src (store/kondo-lang st nsx))
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
                              ;; ONE question, asked of the graph: does anything
                              ;; outside the store declare that it references
                              ;; this? Covers ^:entry-point, ^:unused-ok and the
                              ;; three D-web declarations at once, and covers the
                              ;; NEXT one for free — `edit.refs/declared-refs` is
                              ;; where that vocabulary lives, so a marker added
                              ;; there arrives here without a second edit.
                              (not (contains? declared-alive q))
                              ;; the two that are genuinely not graph facts:
                              ;; ^:generated is machinery awaiting front-end
                              ;; calls, and -main is a naming convention
                              (not (:generated (meta (second s))))
                              (not= '-main nm))
                         ;; OUTSIDE THE ORACLE, not behind on tests. A :cljs
                         ;; namespace never loads into the JVM image, so no test
                         ;; can trace it and none can statically reach it from a
                         ;; test ns — "add a test" is advice nobody can take, and
                         ;; on a real store it dominated the ranking (15 of
                         ;; slopp-ui's 17 :untested rows). The reasoning was
                         ;; already here, keyed to the wrong fact: ^:generated was
                         ;; excluded BECAUSE it is :cljs. Ask the platform.
                         off-platform (and (not test?)
                                           (= :cljs (store/platform-for st nsx)))
                         untested (and (not test?)
                                       ;; a plain (def x <data>) has no invocation to
                                       ;; trace, so it can never acquire evidence —
                                       ;; flagging it is a finding nobody can discharge.
                                       ;; defn/defmulti stay flaggable: they are callable.
                                       (not (and (seq? s) (= 'def (first s))))
                                       (zero? traced)
                                       (not (contains? covered-static q))
                                       ;; a ^{:covers} declaration discharges :untested —
                                       ;; it names the exact dispatch path neither the
                                       ;; trace nor static reach can see.
                                       (not (contains? covered-declared q))
                                       ;; the oracle cannot reach it at all — reported as
                                       ;; :off-platform below, which says so
                                       (not off-platform)
                                       ;; a ^:generated wrapper is machine-written surface
                                       ;; nobody hand-tests; the :cljc contracts ns is
                                       ;; generated too, so this is not just the :cljs case
                                       (not (:generated (meta (second s)))))
                         flags    (cond-> []
                                    untested       (conj :untested)
                                    off-platform   (conj :off-platform)
                                    unused         (conj :unused)
                                    (>= callers 8) (conj :high-blast)
                                    (>= loc 50)    (conj :large)
                                    (pos? lints)   (conj :lint)
                                    doc?           (conj :undocumented)
                                    bang?          (conj :effectful))
                         risk     (+ (if untested 4 0)
                                     ;; 1, not untested's 4: a standing fact about
                                     ;; the platform is worth SEEING and is not a
                                     ;; gap anyone can close, so it must never
                                     ;; outrank code that could be tested and isn't
                                     (if off-platform 1 0)
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
      ;; NAMESPACE grain, so it cannot ride :top (which ranks forms). A
      ;; namespace that never says what it is FOR is the gap a newcomer hits
      ;; first, and the one thing no tool can derive around — the inventory
      ;; is already shown everywhere.
      true (assoc :purpose
                  (let [ws (keep #(edit.modules/namespace-purpose-warning st %) nses)]
                    (cond-> {:stated (- (count nses) (count ws)) :missing (count ws)}
                      (seq ws) (assoc :namespaces (vec (sort (map :ns ws)))
                                      :teach (:teach (first ws))))))
      (seq ns-lint)          (assoc :ns-lint ns-lint)
      (> (count rows) limit) (assoc :omitted (- (count rows) limit)))))
