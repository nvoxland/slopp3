(ns slopp.api.rules
  (:require [slopp.store :as store]
            [slopp.api.schema :as schema]
            [slopp.api.attrs :as attrs]
            [slopp.api.breakage :as breakage]))

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

^:reads
(def done-advisories
  "The done-time advisory registry (D9 rule-registry — the done-grain sibling of
   `edit.modules/per-form-write-gates`): an ordered list of {:key :severity
   :check} entries. `:check` is `(session store changed) -> findings-seq` (empty
   when clean); `:severity` is `:error` (its findings flip `test-status` red — a
   real failure) or `:advisory` (a heuristic that never does). A NEW done-time
   finding registers HERE, in ONE entry, instead of hand-wiring a binding, a
   cond-> clause, and a status term into `done!`. Checks are held as VARS so a
   hot-reload is picked up and the reference graph sees them. `^:reads`: this def
   is inert data — it CARRIES an effectful check ref but never invokes it (that's
   `run-done-advisories!`), so it is not itself a mutation."
  [{:key :schema-drift     :severity :error    :check #'schema-drift-check!}
   {:key :key-typos        :severity :advisory :check #'key-typos-check}
   {:key :breaking-changes :severity :advisory :check #'breaking-check}])

(defn run-done-advisories!
  "Run every registered done-advisory `:check` over the episode's changes;
   returns `{:key findings}` for the checks that FIRED (non-empty result), ready
   to merge into `done!`'s findings. `!` — `schema-drift-check!` evals in the
   image."
  [session st* changed]
  (into {}
        (keep (fn [{:keys [key check]}]
                (let [r (check session st* changed)]
                  (when (seq r) [key r]))))
        done-advisories))

^:reads
(defn status-affecting-fired?
  "True when an `:error`-severity advisory produced findings — a real failure that
   should flip `test-status` red — given the `{:key findings}` map from
   `run-done-advisories!`. `:advisory` findings never flip status. `^:reads`: it
   only reads the registry's `:key`/`:severity` (carrier-taint from the
   `#'schema-drift-check!` ref makes the analyzer think it reaches an effect; it
   invokes nothing)."
  [advisories]
  (boolean (some (fn [{:keys [key severity]}]
                   (and (= :error severity) (seq (get advisories key))))
                 done-advisories)))
