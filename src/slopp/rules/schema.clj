(ns slopp.rules.schema
  "The generative schema check: does a form's declared `:=>` `:malli/schema`
  actually hold when the function is CALLED?

  A schema that lies is worse than no schema, and nothing else in slopp can
  catch it — lint reads syntax, tests read the cases someone thought of, and a
  `:=>` declaration is simply believed by every reader downstream. So a
  changed form carrying one gets `malli.generator/check` run against its LIVE
  var, and a counterexample is drift (D2).

  **Candidate selection is the whole design here.** Generative checking CALLS
  the function, so a candidate has to be safe to call with arbitrary generated
  input:

  - **Analyzer-pure in the strict sense** — no effects AND no non-determinism.
    Effect-free is not enough: a `rand`-using function would make the check
    FLAKE, and drift is `:error`, so a flake would turn a green `done` red.
  - **Declaring no `:throws`.** A `:=>` asserts a total function and the
    checker treats any exception as drift, but a form that declares
    `{:throws …}` has said in advance that it signals failure by throwing —
    calling it with generated input and reporting the throw is the checker
    misreading a declaration it now has access to. An EMPTY `:throws` is
    still checked, and the asymmetry is the point: `[]` is a claim that
    nothing is signalled, so a throw contradicts the author.

  **The check runs in the IMAGE, never in this process.** malli is an inherent
  dependency of the project's image; the server runs on kernel deps and cannot
  call it. Hence `check-string` builds a self-contained eval-string and
  `drift!` hands it to the repl."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.store.render :as render]
            [slopp.image.repl :as repl] [slopp.index.derive :as derive] [slopp.index.analyze :as analyze]))

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
  "True when `qsym` reaches NO effect AND NO non-determinism — the D6 M3 full
   boundary set (opaque-dep READS included) PLUS `index/nondeterministic-vars`
   (`rand`/`slurp`). The generative check CALLS the fn with generated inputs, so
   it must be REFERENTIALLY TRANSPARENT, not merely effect-free: a `rand`-using fn
   would make `mg/check` flake (and schema-drift is `:error`, so a flake would red
   a green `done`). Same RT bar as `tier-refusal`'s `:pure`."
  [store qsym]
  (let [ns-sym   (symbol (namespace qsym))
        analysis (analyze/analyze (render/render-ns store ns-sym))
        dep-nses (into #{} (mapcat identity) (vals (:dep-ns store)))
        eff      (derive/effectful-vars analysis dep-nses (:dep-pure store))
        nondet   (derive/nondeterministic-vars analysis)]
    (not (or (contains? eff qsym) (contains? nondet qsym)))))

(defn schema-candidates
  "CHANGED qsyms that are safe to generatively check: they carry a :=>
   :malli/schema, are analyzer-pure, and DECLARE NO THROWS. Returns
   [{:form qsym :schema <edn>} …]. Schemas are opt-in, so this checks every
   schema it can SAFELY call — export status is the require-gate's concern, not
   the check's.

   The throws filter closes a real conflict rather than papering over one. A
   `:=>` asserts a TOTAL function, and `mg/check` treats any exception as
   drift; a function that declares `{:throws [[:map …]]}` has said in advance
   that it signals failure by throwing, so calling it with generated inputs and
   reporting the throw as drift is the checker misreading a declaration it now
   has access to. `slopp.web.client/request` had to give up its schema entirely
   for exactly this reason before `:throws` existed.

   An EMPTY `:throws` is still checked, and that asymmetry is the point: `[]`
   declares that this function signals nothing by throwing, so a throw during
   the check contradicts a claim the author made and IS drift."
  [store qsyms]
  (vec (for [q     qsyms
             :let  [sch    (schema-of store q)
                    props  (when (and (vector? sch) (= :=> (first sch)))
                             (second sch))
                    signals-failure? (and (map? props)
                                          (seq (:throws props)))]
             :when (and sch (not signals-failure?) (analyzer-pure? store q))]
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
