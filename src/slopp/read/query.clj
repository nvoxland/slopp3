(ns slopp.read.query
  "Reading the code AS IT STANDS — outline, source, project, search,
  namespaces — plus the composite DRIVER reads built on top of that.

  The drivers are the point. `query-slice` and `query-brief` are not
  conveniences layered over the primitives; they are the reads meant to
  REPLACE a loop. A slice answers \"the form I am about to edit, plus
  interface cards for everything it reaches\" in one call, so
  outline→guess→fetch stops being the shape of reading at all. The primitives
  are what a driver read is BUILT from, not what an agent should normally
  reach for.

  Two subjects used to live here and no longer do: the store over TIME is
  `slopp.read.history`, and how forms reach each other is `slopp.read.graph`.
  This namespace's own docstring had named all four kinds for a long time
  while keeping them, on the argument that the drivers are built from all
  three — which is a DEPENDENCY relationship, not a shared subject, and the
  same mistake that once filed `slopp.lab.mine` under `slopp.store` for
  reading `store.db`. Measured before splitting: partition by those four
  kinds and every internal call lands inside a cluster, with only the drivers
  crossing.

  Still mixed in, and named here rather than quietly kept: five
  single-purpose reads that report a DECLARED fact rather than code —
  `query-capabilities`, `query-routes`, `query-rule-telemetry`,
  `query-vocabulary` — plus `query-store`, the read-only oracle escape hatch.
  Each belongs with the subject it reports on (capabilities with
  `slopp.project`, routes and rule telemetry with `slopp.rules`). Those are
  other MODULES, so moving them is a cross-module change with edges to
  declare — not the within-module regroup this was."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.rules.keywords :as attrs]
            [slopp.read.history :as history]
            [slopp.read.orient :as orient]
            [slopp.read.telemetry :as telemetry]
            [slopp.edit :as edit]
            [slopp.index.refs :as refs]
            [slopp.store.render :as render]
            [slopp.store :as store] [slopp.index.derive :as derive] [slopp.index.analyze :as analyze] [slopp.project.capabilities :as capabilities] [slopp.rules.web :as web] [slopp.read.graph :as graph]))

(defn ^:export query-sources
  "Batched read (ONE call, several targets): `targets` is a vector of
  {:ns sym} (whole namespace) or {:ns sym :name sym} (one form). Returns
  a vector of {:ns :name? :source} in target order; unknown targets get
  {:error} entries instead of failing the batch."
  [session targets]
  (let [st (:store @session)]
    (mapv (fn [{:keys [ns name]}]
            (cond
              (nil? (get-in st [:namespaces ns]))
              {:ns ns :error "no such namespace"}

              (nil? name)
              {:ns ns :source (render/render-ns st ns)}

              :else
              (if-let [e (store/form-named st ns name)]
                {:ns ns :name name :source (n/string (:node e))}
                {:ns ns :name name :error "no such form"})))
          targets)))

(defn ^:export query-source
  "Render `ns-sym`'s current source from the store (the VFS read)."
  [session ns-sym]
  (render/render-ns (:store @session) ns-sym))

(defn ^:export ns-effectful-vars
  "The set of `ns/name` symbols in `ns-sym` that reach an effect (D6) — THE
  one spelling of a namespace's effect set, so every surface that badges it,
  reports it or refuses on it reads the same answer.

  Effectfulness is a per-FORM property and it is DERIVED, never declared:
  the `!` convention seeds the fixpoint and reachability carries it, so a
  form with no `!` in its name that calls one is in this set. That is the
  distinction consumers keep getting wrong — a purity TIER is a NAMESPACE
  declaration, identical for every form in it, which makes it useless as a
  per-form mark however much it sounds like one.

  Members are qualified by the full namespace, so a caller looks up
  `(symbol (str ns-sym) (str nm))` rather than reconstructing an alias."
  [st ns-sym]
  (derive/effectful-vars (analyze/analyze (render/render-ns st ns-sym))))

(defn ^:export query-symbol
  "Describe the form defining `nm`: id, name, effectfulness (D6), source."
  [session ns-sym nm]
  (let [st  (:store @session)
        f   (store/form-named st ns-sym nm)
        eff (ns-effectful-vars st ns-sym)]
    (when f
      (cond-> {:id         (:id f)
               :name       (:name f)
               :effectful? (contains? eff (symbol (str ns-sym) (str nm)))
               :source     (n/string (:node f))}
        (edit/unsafe? (:node f)) (assoc :unsafe? true)
        (edit/reads? (:node f))  (assoc :reads? true)))))

(defn ^:export query-outline
  "A namespace's shape at a glance (orientation, T2): every defined var with
  arities, `!`-effect status, and test-ness — a fraction of the tokens of
  reading the source. COMPACT by default; `:detail true` adds each var's
  docstring first line (the outline's token bulk)."
  [session ns-sym & {:keys [detail]}]
  (let [st  (:store @session)
        an  (analyze/analyze (render/render-ns st ns-sym))
        eff (ns-effectful-vars st ns-sym)]
    {:ns ns-sym
     :forms
     (vec (for [d (:var-definitions an)
                :when (= ns-sym (:ns d))]
            (cond-> {:name (:name d)}
              (:fixed-arities d)      (assoc :arities (vec (sort (:fixed-arities d))))
              (:varargs-min-arity d)  (assoc :varargs-min (:varargs-min-arity d))
              (and detail (:doc d))   (assoc :doc (orient/doc-summary (:doc d)))
              (derive/test-definition? d) (assoc :test? true)
              (and (not (derive/test-definition? d))
                   (contains? eff (symbol (str ns-sym) (str (:name d)))))
              (assoc :effectful? true))))}))

(defn ^:export query-project
  "The WHOLE store's shape in one call: every namespace with its outline
  (item 1 — orientation was ~90% of tool calls in successful runs; this
  replaces the namespaces→outline×N chain). COMPACT by default (names,
  arities, flags); `:detail true` adds doc lines. Pass `:since <delta id>`
  on a re-check: when nothing STRUCTURAL changed after that delta the
  response is a one-liner instead of the full outline (verify/turn/
  milestone markers don't count as change)."
  [session & {:keys [since detail]}]
  (let [st   (:store @session)
        ds   (store/deltas st)
        head (:id (last ds))
        quiet-ops #{:verify :done :commit :turn-begin :turn-end}
        unchanged? (and since
                       (some #(= since (:id %)) ds)
                       (->> ds
                            (drop-while #(not= since (:id %)))
                            rest
                            (every? #(contains? quiet-ops (:op %)))))]
    (if unchanged?
      {:unchanged-since since :head head}
      {:head       head
       :namespaces (mapv (fn [ns-sym] (query-outline session ns-sym :detail detail))
                         (sort (keys (:namespaces st))))})))

(defn ^:export query-search
  "The missing grep: regex over all store source, form-addressed results
  [{:ns :form :line}], capped at `:limit` (default 30)."
  [session pattern & {:keys [limit] :or {limit 30}}]
  (try
    (let [re (re-pattern pattern)
          st (:store @session)]
      (->> (for [ns-sym (sort (keys (:namespaces st)))
                 e      (store/forms st ns-sym)
                 line   (str/split-lines (n/string (:node e)))
                 :when  (re-find re line)]
             {:ns ns-sym
              :form (or (:name e) (:id e))
              :line (str/trim line)})
           (take limit)
           vec))
    (catch Exception ex
      {:error (str "bad pattern: " (ex-message ex))})))

(defn ^:export query-namespaces
  "What exists? Every store namespace with its form count (orientation, T2)."
  [session]
  (let [st (:store @session)]
    (vec (for [ns-sym (keys (:namespaces st))]
           {:ns ns-sym :forms (count (store/forms st ns-sym))}))))

^:reads (defn ^:export query-slice
  "The focused read (driver, not doer): FULL source for the form you're
  about to edit + interface CARDS for what it reaches (same-ns private
  helpers and cross-ns callees, breadth-first to `:depth`, capped at
  `:limit` with an honest :omitted). Replaces outline→guess→fetch loops:
  name ONE entry point, receive the neighborhood. `:match` WINDOWS the
  target — only `:window` lines (default 25) each side of the first line
  containing it ride back, with :window metadata — so one clause of a
  giant form reads without paying for the whole thing."
  [session ns-sym nm & {:keys [depth limit match window] :or {depth 2 limit 8}}]
  (if-let [e (store/form-named (:store @session) ns-sym nm)]
    (let [root    (symbol (str ns-sym) (str nm))
          adj     (:calls (graph/query-deps session ns-sym nm))
          reached (loop [level [root] seen #{root} acc [] d 0]
                    (if (>= d depth)
                      acc
                      (let [nxt (into []
                                      (comp (mapcat #(get adj % []))
                                            (remove seen)
                                            (distinct))
                                      level)]
                        (if (empty? nxt)
                          acc
                          (recur nxt (into seen nxt) (into acc nxt) (inc d))))))
          shown   (vec (take limit reached))
          cards   (into []
                        (keep (fn [q] (orient/form-card session
                                                 (symbol (namespace q))
                                                 (symbol (name q)))))
                        shown)
          src     (n/string (:node e))
          target  (if match
                    (let [lines (vec (str/split-lines src))
                          w     (or (some-> window str parse-long) 25)
                          idx   (first (keep-indexed
                                        (fn [i l] (when (str/includes? l (str match)) i))
                                        lines))]
                      (if idx
                        (let [lo (max 0 (- idx w))
                              hi (min (count lines) (+ idx w 1))]
                          {:form root
                           :source (str/join "\n" (subvec lines lo hi))
                           :window {:match (str match) :lines [(inc lo) hi]
                                    :of (count lines)}})
                        {:form root :source src
                         :note (str "match not found in the form: " match)}))
                    {:form root :source src})]
      (cond-> {:target target
               :cards cards}
        (> (count reached) limit) (assoc :omitted (- (count reached) limit))))
    (edit/missing-form-error (:store @session) ns-sym nm)))

(defn ^:export cause-chain
  "An exception as `Class: message <- Class: message …`, outermost first,
  capped at four links.

  **`ex-message` alone is a lie of omission for the two exception types this
  system produces most.** `CompilerException`'s own message IS \"Syntax error
  compiling at (line:col)\" and `Syntax error macroexpanding at.` — the
  sentence a reader needs is always one or more causes down. Reported when
  `query_store` answered \"query_store threw: Syntax error compiling at (0:0).\"
  on a form that was plainly valid: the surface accused the caller's input
  using a message that names nothing, which is worse than saying it does not
  know.

  The class is worth stating because it recurs: **a report that carries only
  the outermost frame has answered nothing and looks like it answered.**

  A SIBLING copy lives inside `slopp.webdev.screen/drive-code`'s generated
  source, and the two cannot be one. That code is evaluated in a USER's
  verification image, where the only slopp on the classpath is the vendored
  `slopp.web.*` — nothing here is reachable. A duplicate with a reason is
  better than a false dependency, and this is the reason."
  [^Throwable e]
  (str/join " <- " (take 4 (map #(let [m (ex-message %)]
                                  (cond-> (.getSimpleName (class %))
                                    (seq (str m)) (str ": " m)))
                                (take-while some? (iterate #(.getCause ^Throwable %) e))))))

^:unsafe (defn ^:export query-store
  "The STORE-VALUE oracle: evaluate one read-only `(fn [store] ...)` over
  the CURRENT immutable store value, in the server process where that
  value lives — the sanctioned home for ad-hoc codebase-as-data analysis
  (`query_eval` answers questions OF the code in the image; this answers
  questions ABOUT it). Gated hard: the form must be a single fn of the
  store, effect-free by the pure-eval walk (no `!`, defs, interop, IO,
  eval), and it runs on a worker with a timeout so runaway analysis can't
  wedge the serve loop (the store value is immutable — the pointer is safe
  by construction). Results must print small (pr-str capped); fully-qualify
  everything (no aliases in eval context). Returns {:result v :ms n} or
  {:error msg}."
  [session code & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (let [parsed (edit/parse-one (str code))]
    (if (:error parsed)
      {:error (str "query_store takes ONE (fn [store] ...) form — "
                   (:error parsed))}
      (let [sx (try (n/sexpr (:node parsed)) (catch Exception _ nil))]
        (cond
          (not (and (seq? sx) (contains? #{'fn 'fn*} (first sx))))
          {:error "query_store takes ONE (fn [store] ...) form — got something else"}

          :else
          (if-let [refusal (edit/pure-eval-refusal sx)]
            {:error refusal}
            (let [store (:store @session)
                  t0    (System/currentTimeMillis)
                  fut   (future
                          (try {:result ((eval sx) store)}
                               (catch Throwable e
                                 {:error (str "query_store threw: " (cause-chain e))})))
                  out   (deref fut timeout-ms ::timeout)]
              (if (= ::timeout out)
                (do (future-cancel fut)
                    {:error (str "query_store timed out after " timeout-ms
                                 "ms — narrow the analysis (or raise"
                                 " :timeout-ms)")})
                (let [ms (- (System/currentTimeMillis) t0)]
                  (if (:error out)
                    (assoc out :ms ms)
                    (let [s (pr-str (:result out))]
                      (if (> (count s) 32768)
                        {:result (str (subs s 0 32768) " …")
                         :truncated true :ms ms}
                        (assoc out :ms ms)))))))))))))

(defn ^:export query-brief
  "The one-call dossier: everything the store knows about `ns-sym/nm` —
  source, effect flags, cross-ns callers, the tests that exercise it
  (`:covered-by`, trace map; `:coverage :unknown` until a test_run builds one),
  the tests that REACH or CLAIM it but haven't been observed to run it
  (`:reached-by`, each tagged `:via` — `:static` reach carries `:hops`,
  a `:declared` ^{:covers} marker does not; real for the untraced external
  tier and dispatch paths, never a green claim), and the recorded WHY (the last change's prompt + its enclosing
  turn intent). Collapses the source→references→lineage read chain into one
  response."
  [session ns-sym nm]
  (if (nil? (store/form-named (:store @session) ns-sym nm))
    (edit/missing-form-error (:store @session) ns-sym nm)
    (let [
          sym     (query-symbol session ns-sym nm)
          callers (vec (graph/query-references session ns-sym nm))
          tmap    (:test-map @session)
          qsym    (symbol (str ns-sym) (str nm))
          tests   (let [e  (store/form-named (:store @session) ns-sym nm)
                        ks (store/form-trace-keys ns-sym e)]
                    ;; evidence can arrive under any name the form defines (#129)
                    (->> tmap
                         (keep (fn [[t forms]] (when (some forms ks) t)))
                         distinct sort vec))
          reached (let [seen (set tests)]
                    (->> (refs/covered-by (:store @session) tmap qsym)
                         ;; everything that REACHES/CLAIMS the form but wasn't
                         ;; observed to run it — static reach (with :hops) AND
                         ;; ^{:covers} declarations (:via #{:declared}, no hops).
                         ;; Never a green claim; :via stays visible.
                         (filter #(and (not (contains? (:via %) :observed))
                                       (not (seen (:test %)))))
                         (mapv #(select-keys % [:test :via :hops]))))
          why     (last (history/query-lineage session ns-sym nm))]
      (cond-> {:ns ns-sym :name nm :source (:source sym)}
        (:effectful? sym) (assoc :effectful? true)
        (:reads? sym)     (assoc :reads? true)
        (:unsafe? sym)    (assoc :unsafe? true)
        (seq callers)     (assoc :callers callers)
        (seq tests)       (assoc :covered-by (graph/coverage-view tests))
        (seq reached)     (assoc :reached-by reached)
        (and (seq tmap) (empty? tests) (empty? reached) (not (:test? sym)))
        (assoc :untested true)
        (empty? tmap)     (assoc :coverage :unknown)
        why               (assoc :why (cond-> {:op     (:op why)
                                               :prompt (:prompt why)}
                                        (:agent why)       (assoc :agent (:agent why))
                                        (:turn-intent why) (assoc :intent (:turn-intent why))))))))

(defn ^:export query-vocabulary
  "Browse the store's domain-keyword vocabulary — namespaced keys, most-used
   first — so you REUSE an established key (`:user/email`) instead of coining a
   near-duplicate the key-hygiene advisory would flag. Optional `ns` narrows to a
   keyword namespace (exact or dotted-child, e.g. \"user\" → :user/* and
   :user.address/*). Derived from the forms, so it reflects the current branch/
   revision exactly."
  [session & {:keys [ns]}]
  (let [attrs (attrs/vocabulary (:store @session) :ns-prefix ns)]
    {:count (count attrs) :attributes attrs}))

(defn ^:export query-rule-telemetry
  "The D9 rules' fire-rate + discharge signal for THIS store — the demand signal
   the severity dial is set by: how often each rule fires (`:dones`/`:instances`),
   whether its findings get `:discharged` (flagged once) or `:persisted` (keep
   recurring — ignored / friction), the `:escape-markers` density (agents opting
   out via `^:unsafe`/`^:reads`/`^:unused-ok`), and the current `:dials`. Read-only
   analysis over the delta log — no instrumentation. `:since` (a delta or
   commit-point id from `query_commits`) windows it."
  [session & {:keys [since]}]
  (telemetry/rule-telemetry (:store @session) :since since))

(defn ^:export query-capabilities
  "Every capability setting for THIS store: the declared registry joined with
   the stored `capabilities` config — per setting the default, the EFFECTIVE
   value, and (when set) the raw stored string; wildcard families ride as
   `:patterns`. Set one with `config_file {path \"capabilities\" key <k> value
   <v>}` — capability writes validate against the registry at write time."
  [session]
  (capabilities/report (:store @session)))

(defn ^:export query-routes
  "The store's declared web surface: `web.enabled`, every endpoint row
   (method, path, auth policy, handler, declared `:web/effects`/`:web/reads`,
   schema presence, the `^:web/effectful` escape), and the derived
   effect/read vocabularies — the SAME derivations the web write gates
   enforce, so what this shows is what the gates guaranteed. Disabled →
   `{:enabled false}` with the opt-in teaching."
  [session]
  (web/routes-report (:store @session)))
