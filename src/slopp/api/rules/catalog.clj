(ns slopp.api.rules.catalog)

(def ^:export rule-catalog
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
    :escape "module_purity {module tier :internal/:external}, or move the effect into an :external namespace (:internal may mutate in-process, e.g. a memo through slopp.cache)"
    :teach "a form's effect or non-determinism exceeds its module's declared purity tier"}
   {:rule :schema-refusal :grain :form :severity :refuse
    :escape "add a :=> :malli/schema, or config_file {path gates key require-boundary-schemas unset true}"
    :teach "a module-external map-arg fn must carry a :=> :malli/schema (when the store opts in)"}
   {:rule :namespaced-keys-refusal :grain :form :severity :refuse
    :escape "use {:some.ns/keys [...]}, ^:foreign-keys for a third-party map, or config_file {path gates key require-namespaced-keys unset true}"
    :teach (str "a module-external fn's ARGLIST destructuring must use namespaced keys"
                " (when the store opts in). SCOPE: arglist destructuring on a"
                " module-external defn ONLY — not map keys generally, not return maps,"
                " not private fns, not (:k m) body reads. Its finding list IS the"
                " worklist. A deliberate HOUSE rule, stricter than Clojure practice,"
                " which defaults to unqualified keys: the argument for bare keys assumes"
                " context disambiguates, and an agent reads one form")}
   {:rule :schema-drift :grain :done :severity :error
    :escape "fix the schema or the impl so they agree"
    :teach "a written :=> schema disagrees with its live impl (generative mg/check)"}
   {:rule :key-typos :grain :done :severity :advisory
    :escape "reuse the established key (query_vocabulary), or accept the new one"
    :teach "a new namespaced key is one Damerau edit from an established same-namespace key"}
   {:rule :breaking-changes :grain :done :severity :advisory
    :escape "^:breaking-ok on the name (a DELIBERATE break — you own telling downstream; it polices itself, a marker that narrowed nothing is reported stale), restore the arity/key/visibility, or rename for a clean break"
    :teach "a module-external fn's contract narrowed (arity/schema-key/visibility) vs the last-done baseline"}
   {:rule :ambient-state :grain :done :severity :advisory
    :escape "pass state in as an arg, or accept it (a legit top-level cache)"
    :teach "a global (def _ (atom/ref/agent/volatile! …)) — ambient mutable state a slice can't track"}
   {:rule :bare-throw :grain :done :severity :advisory
    :escape "return data / (ex-info …) at the boundary, or accept the throw"
    :teach "a module-external fn throws a freshly-constructed non-ex-info exception"}
   {:rule :shell-widening :grain :done :severity :advisory
    :escape "move the effect into an existing SHELL namespace and keep the pure part in core, or accept the widening (it asks once)"
    :teach "this episode declared a namespace :external/:internal — the functional CORE got smaller, and only you know whether it had to"}
   ])
