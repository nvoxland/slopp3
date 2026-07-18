(ns slopp.api.rules
  (:require [slopp.store :as store]
            [slopp.api.schema :as schema]
            [slopp.api.attrs :as attrs]
            [slopp.api.breakage :as breakage] [slopp.edit.modules :as edit.modules] [rewrite-clj.node :as n] [clojure.string :as str]))

(defn- changed-qsyms
  "The qualified symbols of the CHANGED forms this episode."
  [st* changed]
  (into #{}
        (keep (fn [fid]
                (when-let [e (store/form-by-id st* fid)]
                  (symbol (str (store/ns-of-form-id st* fid))
                          (str (or (:name e) (:id e)))))))
        changed))

(defn schema-drift-check!
  "Done-advisory check: generative schema drift over the episode's changed forms
   (image-side)."
  [session st* changed]
  (schema/drift! (:image @session) st* (changed-qsyms st* changed)))

(defn key-typos-check
  "Done-advisory check: near-duplicate-key typos among the episode's changes."
  [_session st* changed]
  (attrs/near-duplicate-keys st* changed))

(defn breaking-check
  "Done-advisory check: contract narrowing (arity breakage) among the changes."
  [_session st* changed]
  (breakage/breaking-changes st* changed))

(def rule-catalog
  "The unified DECLARATIVE catalog of every D9 rule across both grains — each a
   `{:rule :grain :severity :escape :teach}` map (`:severity` is the DEFAULT; the
   effective one is the per-store `rule-severity` override). The single place that
   describes the program's enforcement surface; `query_rules` projects it with
   each rule's effective severity. Execution still runs through the two registries
   (`edit.modules/per-form-write-gates`, `done-advisories`); the
   `catalog-covers-every-registered-rule` test guards that this never drifts
   behind them."
  [{:rule :module-refusal :grain :form :severity :refuse
    :escape "declare the edge (module_dep) or respect visibility (^:export / restructure)"
    :teach "a cross-module call needs a declared edge and must respect recursive visibility"}
   {:rule :tier-refusal :grain :form :severity :refuse
    :escape "module_purity {module tier :reads/:effects}, or move the effect/non-determinism to a periphery ns"
    :teach "a form's effect or non-determinism exceeds its module's declared purity tier"}
   {:rule :schema-refusal :grain :form :severity :refuse
    :escape "add a :=> :malli/schema, or config_file {path gates key require-boundary-schemas unset true}"
    :teach "a module-external map-arg fn must carry a :=> :malli/schema (when the store opts in)"}
   {:rule :namespaced-keys-refusal :grain :form :severity :refuse
    :escape "use {:some.ns/keys [...]}, or config_file {path gates key require-namespaced-keys unset true}"
    :teach "a module-external fn must use namespaced boundary keys (when the store opts in)"}
   {:rule :schema-drift :grain :done :severity :error
    :escape "fix the schema or the impl so they agree"
    :teach "a written :=> schema disagrees with its live impl (generative mg/check)"}
   {:rule :key-typos :grain :done :severity :advisory
    :escape "reuse the established key (query_vocabulary), or accept the new one"
    :teach "a new namespaced key is one Damerau edit from an established same-namespace key"}
   {:rule :breaking-changes :grain :done :severity :advisory
    :escape "restore the arity/key/visibility, or rename for a clean break"
    :teach "a module-external fn's contract narrowed (arity/schema-key/visibility) vs the last-done baseline"}
   {:rule :ambient-state :grain :done :severity :advisory
    :escape "pass state in as an arg, or accept it (a legit top-level cache)"
    :teach "a global (def _ (atom/ref/agent/volatile! …)) — ambient mutable state a slice can't track"}
   {:rule :bare-throw :grain :done :severity :advisory
    :escape "return data / (ex-info …) at the boundary, or accept the throw"
    :teach "a module-external fn throws a freshly-constructed non-ex-info exception"}])

(defn- ambient-def?
  "True when `node` is a top-level `(def name (atom|ref|agent|volatile! …))` —
   ambient MUTABLE state a slice-limited editor can't track.

   Reads the LAST element, not index 2: `(def x \"doc\" (atom {}))` puts the
   docstring there, so an index-2 lookup silently missed every DOCUMENTED
   global — which is to say, every one someone had bothered to justify."
  [node]
  (let [s (try (n/sexpr node) (catch Exception _ nil))]
    (boolean (and (seq? s) (= 'def (first s)) (>= (count s) 3)
                  (let [v (last s)]
                    (and (seq? v)
                         (contains? '#{atom ref agent volatile!} (first v))))))))

(defn- bare-throws?
  "True when `node`'s body throws a freshly-CONSTRUCTED exception that isn't
   `ex-info` — `(throw (SomeException. …))` or `(throw (new … ))`. A re-throw of a
   bound exception `(throw e)` and a structured `(throw (ex-info …))` are fine."
  [node]
  (let [form (try (n/sexpr node) (catch Exception _ nil))]
    (boolean
     (some (fn [x]
             (and (seq? x) (= 'throw (first x))
                  (let [arg (second x)]
                    (and (seq? arg)
                         (let [h (first arg)]
                           (and (symbol? h)
                                (or (= 'new h)
                                    (str/ends-with? (name h) "."))))))))
           (tree-seq coll? seq form)))))

(defn ambient-state-check
  "Done-advisory: changed forms that are ambient MUTABLE state — a global
   `(def _ (atom/ref/agent/volatile! …))`. A global for LOGIC is spooky action a
   slice-limited editor can't track — pass state in instead.

   `^:ambient-ok` on the NAME discharges it, for the case that is genuinely
   deliberate (a memo whose answer is immutable, a process-local cache). The
   marker POLICES ITSELF, exactly as `^:unused-ok` does: a marker on a def that
   is NOT ambient state is itself a finding, so the flag can never drift into
   decoration once whatever justified it is gone."
  [_session st* changed]
  (vec (keep (fn [fid]
               (when-let [e (store/form-by-id st* fid)]
                 (let [ambient? (ambient-def? (:node e))
                       marked?  (let [s (try (n/sexpr (:node e))
                                             (catch Exception _ nil))]
                                  (boolean (and (seq? s) (= 'def (first s))
                                                (symbol? (second s))
                                                (:ambient-ok (meta (second s))))))
                       q        (symbol (str (store/ns-of-form-id st* fid))
                                        (str (or (:name e) (:id e))))]
                   (cond
                     (and ambient? (not marked?)) {:form q}
                     (and marked? (not ambient?))
                     {:form q :stale-marker true
                      :teach (str q " carries ^:ambient-ok but is not ambient"
                                  " state — remove the flag")}))))
             changed)))

(defn bare-throw-check
  "Done-advisory: a changed MODULE-EXTERNAL fn that throws a freshly-constructed
   non-`ex-info` exception. At a boundary, prefer returning data or
   `(ex-info …)` (carrying `ex-data`) — a bare throw lands where a slice-limited
   caller can't see or `=`-test it."
  [_session st* changed]
  (vec (keep (fn [fid]
               (when-let [e (store/form-by-id st* fid)]
                 (let [ns-sym (store/ns-of-form-id st* fid)
                       form   (try (n/sexpr (:node e)) (catch Exception _ nil))]
                   (when (and (edit.modules/module-external? ns-sym form)
                              (bare-throws? (:node e)))
                     {:form (symbol (str ns-sym) (str (or (:name e) (:id e))))}))))
             changed)))
(def done-advisories
  "The done-time advisory registry (D9 rule-registry — the done-grain sibling of
   `edit.modules/per-form-write-gates`): an ordered list of {:key :severity
   :check} entries. `:check` is `(session store changed) -> findings-seq` (empty
   when clean); `:severity` is `:error` (its findings flip `test-status` red — a
   real failure) or `:advisory` (a heuristic that never does). A NEW done-time
   finding registers HERE, in ONE entry, instead of hand-wiring a binding, a
   cond-> clause, and a status term into `done!`. Checks are held as VARS so a
   hot-reload is picked up and the reference graph sees them — and a carried
   `#'var` is NOT a call, so the analyzer no longer taints this data def effectful."
  [{:key :schema-drift     :severity :error    :check #'schema-drift-check!}
   {:key :key-typos        :severity :advisory :check #'key-typos-check}
   {:key :breaking-changes :severity :advisory :check #'breaking-check}
   {:key :ambient-state    :severity :advisory :check #'ambient-state-check}
   {:key :bare-throw       :severity :advisory :check #'bare-throw-check}])

(defn run-done-advisories!
  "Run every registered done-advisory `:check` over the episode's changes —
   EXCEPT those a project dialed `:off` (`edit.modules/rule-severity`) — and
   return `{:key findings}` for the checks that FIRED (non-empty result), ready
   to merge into `done!`'s findings. `!` — `schema-drift-check!` evals in the
   image."
  [session st* changed]
  (into {}
        (keep (fn [{:keys [key severity check]}]
                (when (not= :off (edit.modules/rule-severity st* key severity))
                  (let [r (check session st* changed)]
                    (when (seq r) [key r])))))
        done-advisories))

(defn status-affecting-fired?
  "True when an advisory whose EFFECTIVE severity is `:error` produced findings —
   a real failure that should flip `test-status` red. Effective severity is the
   per-store override (`edit.modules/rule-severity`) else the registry default, so
   a project can dial `key-typos` up to `:error` or `schema-drift` down to
   `:advisory`. `:advisory`/`:off` never flip status. Args: the store (for the
   config) and the `{:key findings}` map from `run-done-advisories!`."
  [store advisories]
  (boolean (some (fn [{:keys [key severity]}]
                   (and (= :error (edit.modules/rule-severity store key severity))
                        (seq (get advisories key))))
                 done-advisories)))

