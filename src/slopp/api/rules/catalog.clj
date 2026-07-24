(ns slopp.api.rules.catalog)

(def ^:export rule-catalog
  "The unified DECLARATIVE catalog of every D9 rule across both grains — each a
   `{:rule :grain :escape :teach}` map. It holds only what the registries cannot:
   the grain and the PROSE (what the rule means, how to discharge it).

   Severity is NOT here. It is declared where the rule is implemented — a write
   gate's `:rule/severity` metadata, a done-advisory's registry `:severity` — and
   `rule-rows` joins it back in. It used to be a column here, and nothing read it:
   `gate-check` hardcoded `:refuse` while `query_rules` reported whatever this
   said, so the catalog could claim `:advisory` for a rule that refused. Read
   `rule-rows`, not this def.

   Execution runs through the two registries (`edit.modules/per-form-write-gates`,
   `rules/done-advisories`); the `catalog-covers-every-registered-rule` test guards
   that this never drifts behind them."
  [{:rule :module-refusal :grain :form
    :escape "declare the edge (module_dep) or respect visibility (^:export / restructure)"
    :teach "a cross-module call needs a declared edge and must respect recursive visibility"}
   {:rule :tier-refusal :grain :form
    :escape "module_purity {module tier :internal/:external}, or move the effect into an :external namespace (:internal may mutate in-process, e.g. a memo through slopp.cache)"
    :teach "a form's effect or non-determinism exceeds its module's declared purity tier"}
   {:rule :schema-refusal :grain :form
    :escape "add a :=> :malli/schema, or config_file {path gates key require-boundary-schemas unset true}"
    :teach "a module-external map-arg fn must carry a :=> :malli/schema (when the store opts in)"}
   {:rule :namespaced-keys-refusal :grain :form
    :escape "use {:some.ns/keys [...]}, ^:foreign-keys for a third-party map, or config_file {path gates key require-namespaced-keys unset true}"
    :teach (str "a module-external fn's ARGLIST destructuring must use namespaced keys"
                " (when the store opts in). SCOPE: arglist destructuring on a"
                " module-external defn ONLY — not map keys generally, not return maps,"
                " not private fns, not (:k m) body reads. Its finding list IS the"
                " worklist. A deliberate HOUSE rule, stricter than Clojure practice,"
                " which defaults to unqualified keys: the argument for bare keys assumes"
                " context disambiguates, and an agent reads one form")}
   {:rule :web-auth-refusal :grain :form
    :escape "declare :web/auth on the endpoint (:public typed out, :authenticated, or [:group \"<name>\"]) — or dial the rule down and let auth.default-policy govern"
    :teach "an endpoint (:web/path) must declare its auth policy — default-deny: an unsecured route is a visible decision, never an omission (inert until http.enabled)"}
   {:rule :web-route-collision :grain :form
    :escape "change the path or method, or extend the existing handler (query_routes lists every claim)"
    :teach "one method+path has one owning endpoint — a duplicate route refuses at the write instead of surprising at startup (inert until http.enabled)"}
   {:rule :web-undeclared-effect :grain :form
    :escape "define a performer per kind ((defn ^{:web/effect <kind>} name! [ctx …] …)) or reuse an existing kind (query_routes lists the vocabulary)"
    :teach "an endpoint's :web/effects may only name kinds a marked performer provides — a typo'd kind fails at the write, not at the first request (inert until http.enabled)"}
   {:rule :web-unsafe-get :grain :form
    :escape "make it :post/:put/:delete, drop the declared effects, or return the change as data from a non-safe endpoint"
    :teach "a :get/:head endpoint must be SAFE — it may neither declare :web/effects kinds nor reach a mutation (inert until http.enabled)"}
   {:rule :web-unknown-group :grain :form
    :escape "config_file {path \"capabilities\" key \"groups.<name>.members\" value \"…\"} defines the group, or fix the name in :web/auth"
    :teach "an endpoint's [:group …] policy may only name groups the capabilities config defines — a typo'd group silently denies forever, the authz nil-pun (inert until http.enabled)"}
   {:rule :web-react-attrs :grain :form
    :escape "spell it as HTML (:class, :for), replace handlers with a link/form targeting an endpoint, or dial it down (config_file {path \"rules\" key \"web-react-attrs\" value \"advisory\"}) for a map that is genuinely not an element"
    :teach "a literal hiccup element carries a React attribute name (:className, :htmlFor, :onClick…) — browsers silently ignore unknown attributes, so it ships and does nothing (inert until http.enabled)"}
   {:rule :web-endpoint-schema :grain :form
    :escape "add :web/response (and :web/request on a body method) to the endpoint's name metadata — a .cljc malli schema var (shareable/reusable) or an inline [:map …] for a one-off — or dial it down (config_file {path \"rules\" key \"web-endpoint-schema\" value \"advisory\"})"
    :teach "a :web/path endpoint must declare :web/response (and :web/request on a :post/:put/:patch body method) — its contract, shared .cljc so the client validates against the SAME schema (D-web-contracts; inert until http.enabled)"}
   {:rule :web-public-mutation :grain :done
    :escape "tighten :web/auth, or accept it — a deliberately public write surface (signup, webhook) is legitimate and this asks per changed form"
    :teach "a changed :public endpoint declares :web/effects kinds — a publicly writable surface should be a decision, not an omission (inert until http.enabled)"}
   {:rule :web-dangling-route-refs :grain :done
    :escape "fix the path, add the endpoint or static asset, or ^{:web/external-path \"why\"} on the RENDERING form when something outside this store serves it"
    :teach "a rendered link/form targets a path no declared route or static mount serves — the UI nil-pun: it ships and 404s. Dynamic paths ride along as :info findings: reported, never status-flipping (inert until http.enabled)"}
   {:rule :schema-drift :grain :done
    :escape "fix the schema or the impl so they agree"
    :teach "a written :=> schema disagrees with its live impl (generative mg/check)"}
   {:rule :key-typos :grain :done
    :escape "reuse the established key (query_vocabulary), or accept the new one"
    :teach "a new namespaced key is one Damerau edit from an established same-namespace key"}
   {:rule :breaking-changes :grain :done
    :escape "^:breaking-ok on the name (a DELIBERATE break — you own telling downstream; it polices itself, a marker that narrowed nothing is reported stale), restore the arity/key/visibility, or rename for a clean break"
    :teach "a module-external fn's contract narrowed (arity/schema-key/visibility) vs the last-done baseline"}
   {:rule :ambient-state :grain :done
    :escape "pass state in as an arg, or accept it (a legit top-level cache)"
    :teach "a global (def _ (atom/ref/agent/volatile! …)) — ambient mutable state a slice can't track"}
   {:rule :bare-throw :grain :done
    :escape "return data / (ex-info …) at the boundary, or accept the throw"
    :teach "a module-external fn throws a freshly-constructed non-ex-info exception"}
   {:rule :stale-reference :grain :done
    :escape "fix the prose (or the reference) so the name resolves — the text is teaching, and teaching that lies costs a failed call to discover"
    :teach "a docstring/teach-string names a.b/c where namespace a.b is in this store but has no form c — a rename or move left the prose behind (gates never see a var inside a string)"}
   {:rule :retired-vocabulary :grain :done
    :escape "route through the normalizer, or ^:legacy-ok on the name if this form IS the normalizer (it polices itself — a marker that mixes nothing is reported stale)"
    :teach "a form ENUMERATES a retired vocabulary (two retired members, or one beside its replacement) — a second copy that missed the rename; declare yours with config_file {path vocabulary key <old> value <new>}"}
   {:rule :generated-ns :grain :form
    :escape "regenerate via generate_client after changing the ENDPOINT (its :web/request/:web/response), strip the ^:generated marker to take manual ownership, or dial it down (config_file {path \"rules\" key \"generated-ns\" value \"advisory\"})"
    :teach "a ^:generated form is generate_client's output and must not be hand-edited — regeneration rewrites the whole client namespace, so a hand edit is lost on the next generate (D-web-contracts part 2)"}
   {:rule :stale-client :grain :done
    :escape "run generate_client to re-derive the client from the current endpoints, or accept the drift"
    :teach "the generated typed client is stale — an endpoint's contract changed since generate_client last ran (D-web-contracts part 2)"}
   {:rule :inline-schema-dup :grain :done
    :escape "extract the shared inline schema to a named .cljc var both endpoints reference, or accept the duplication"
    :teach "2+ endpoints declare the same inline request/response schema — a shared shape belongs in one named .cljc schema so the server and the generated client agree (D-web-contracts part 2)"}
   {:rule :shell-widening :grain :done
    :escape "move the effect into an existing SHELL namespace and keep the pure part in core, or accept the widening (it asks once)"
    :teach "this episode declared a namespace :external/:internal — the functional CORE got smaller, and only you know whether it had to"}
   {:rule :tracked-file-drift :grain :done
    :escape "file_put the working-tree copy if a human edited it, or project/pull if the store's copy is the newer — reconcile deliberately, in one direction"
    :teach "a tracked manifest file differs from the real file the human branch carries at the same path — the one fact this system keeps two copies of, and nothing compared them until build.clj drifted far enough to reintroduce a fixed jar-corruption bug downstream"}])

(defn ^:export rule-rows
  "The rule catalog with each rule's DECLARED default `:severity` merged in from
   `declared` — a `{rule-key severity}` map, built by `rules/declared-severities`
   from the two registries that own the fact. Read this, not the raw
   `rule-catalog`: the catalog holds only what the registries cannot (the prose
   `:teach`/`:escape` and the `:grain`), so the severity a reader sees is the one
   enforcement uses. It used to be a column here too, and the two disagreed
   silently — `query_rules` reported a catalog claim while `gate-check` refused
   by default regardless.

   `declared` arrives as DATA rather than being fetched here: this namespace is
   `:pure` and the registries live behind an `:external` one, so reaching for
   them would be a core→shell dependency — a tier claim this namespace does not
   earn."
  [declared]
  (mapv (fn [r] (assoc r :severity (get declared (:rule r)))) rule-catalog))
