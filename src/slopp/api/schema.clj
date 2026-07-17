(ns slopp.api.schema
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.render :as render]
            [slopp.index :as index]
            [slopp.repl :as repl]))

(defn schema-of
  "The :=> :malli/schema declared on a stored form's defn name, or nil. Read
   straight off the NAME metadata (^{:malli/schema …} f) like export-level reads
   :export — no eval. Only :=> FUNCTION schemas qualify: mg/check must generate
   the args and check the return, so a plain data schema (:map, …) is not a
   candidate here."
  [store qsym]
  (when-let [e (store/form-named store (symbol (namespace qsym)) (symbol (name qsym)))]
    (let [sch (some-> (try (n/sexpr (:node e)) (catch Exception _ nil))
                      second meta :malli/schema)]
      (when (and (vector? sch) (= :=> (first sch)))
        sch))))

(defn analyzer-pure?
  "True when `qsym` reaches NO effect — the D6 M3 full boundary set (opaque-dep
   READS included, like tier-refusal's :pure branch), because the generative
   check CALLS the fn with generated inputs and a pure fn's call is
   side-effect-free. Single-ns, bang-name-propagating soundness (a cross-ns
   effect is seen only when the callee is `!`-named)."
  [store qsym]
  (let [ns-sym   (symbol (namespace qsym))
        analysis (index/analyze (render/render-ns store ns-sym))
        dep-nses (into #{} (mapcat identity) (vals (:dep-ns store)))
        eff      (index/effectful-vars analysis dep-nses (:dep-pure store))]
    (not (contains? eff qsym))))

(defn schema-candidates
  "CHANGED qsyms that are safe to generatively check: they carry a :=>
   :malli/schema AND are analyzer-pure. Returns [{:form qsym :schema <edn>} …].
   Schemas are opt-in, so this checks every schema it can SAFELY call — export
   status is the require-gate's concern, not the check's."
  [store qsyms]
  (vec (for [q     qsyms
             :let  [sch (schema-of store q)]
             :when (and sch (analyzer-pure? store q))]
         {:form q :schema sch})))

(defn check-string
  "A self-contained eval-string (malli lives ONLY in the image — inherent dep,
   the server runs on kernel deps) that runs malli.generator/check for each
   candidate against its LIVE var and returns a vector of drift findings
   [{:form <qsym> :counterexample <str>} …] — empty when every schema holds.
   Empty candidates short-circuit to `[]` (nothing to resolve). Each check is
   guarded so a single throw can't sink the whole set."
  [candidates]
  (if (empty? candidates)
    "[]"
    (str "(let [check (requiring-resolve 'malli.generator/check)] (vec (remove nil? ["
         (str/join
          " "
          (for [{:keys [form schema]} candidates]
            (format (str "(try (when-let [e (check %s (deref (resolve '%s)))]"
                         " {:form '%s :counterexample (pr-str (-> e :errors first :check :smallest))})"
                         " (catch Throwable t {:form '%s :error (str \"check-threw: \" (.getMessage t))}))")
                    (pr-str schema) form form form)))
         "])))")))

(defn drift!
  "Run the generative schema oracle-check over CHANGED `qsyms`, evaluating in the
   IMAGE (where malli is inherent — the server can't call it). Returns the drift
   findings [{:form <qsym> :counterexample <str>} …], empty when every schema
   holds and nil when there are no candidates to check. A schema that lies about
   its implementation surfaces here instead of drifting silently (D2)."
  [image store qsyms]
  (let [cands (schema-candidates store qsyms)]
    (when (seq cands)
      (first (repl/eval! image (check-string cands))))))
