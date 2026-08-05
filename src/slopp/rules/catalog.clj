(ns slopp.rules.catalog
  "The PROSE half of every rule: what it means, and how to discharge it.

  Deliberately nothing else. Severity lives where the rule is implemented and
  is joined back in, because when it was a column here it drifted — the
  catalog claimed `:advisory` for a rule that refused. Execution lives in the
  two registries. What remains is the part no registry can hold: the sentence
  an agent reads when a finding fires.

  Being a separate, inert data def is what makes it POLICEABLE: a coverage
  test asserts that every registered rule has an entry here and vice versa, so
  a new rule cannot ship without saying what it wants and how to satisfy it.")

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
    :escape "declare :web/auth on the endpoint (:public typed out, :authenticated, or [:group \"<name>\"]) — or dial the rule down and let web.auth.default-policy govern"
    :teach "an endpoint (:web/path) must declare its auth policy — default-deny: an unsecured route is a visible decision, never an omission (inert until web.enabled)"}
   {:rule :web-route-collision :grain :form
    :escape "change the path or method, or extend the existing handler (query_routes lists every claim)"
    :teach "one method+path has one owning endpoint — a duplicate route refuses at the write instead of surprising at startup (inert until web.enabled)"}
   {:rule :web-page-unreachable :grain :form
    :escape "move the entry — and the routing, derive and view code it reaches — to a :jvm or :cljc namespace, passing the browser-shaped parts in (:fetch, :render, a url pusher); or drop the ^:web/page marker if this app is not meant to be reviewed headlessly"
    :teach "a ^:web/page entry may not sit in a :cljs namespace — no JVM can open the app there, so every headless test drives a hand-built lookalike instead, and a lookalike passes while the real screen is wrong. The wiring is portable; only the effects are :cljs (inert until web.enabled)"}
   {:rule :web-undeclared-effect :grain :form
    :escape "define a performer per kind ((defn ^{:web/effect <kind>} name! [ctx …] …)) or reuse an existing kind (query_routes lists the vocabulary)"
    :teach "an endpoint's :web/effects may only name kinds a marked performer provides — a typo'd kind fails at the write, not at the first request (inert until web.enabled)"}
{:rule :web-undeclared-context :grain :form
    :escape "declare ONE zero-arg builder ((defn ^{:web/context true} app-context [] {…})) — an app that runs its own serve! should mark the builder it already has and call it, since two definitions of one store's context agree until one gains a key. Dial it down (config_file {path \"rules\" key \"web-undeclared-context\" value \"advisory\"}) for a context that genuinely cannot be built without arguments"
    :teach "an endpoint reading :web/deps needs a store that declares where those deps come from — otherwise the map arrives nil, which 500s or, worse, answers 200 with an empty body, and generate_client consumes the empty one as a success (inert until web.enabled)"}
   {:rule :web-unsafe-get :grain :form
    :escape "make it :post/:put/:delete, drop the declared effects, or return the change as data from a non-safe endpoint"
    :teach "a :get/:head endpoint must be SAFE — it may neither declare :web/effects kinds nor reach a mutation (inert until web.enabled)"}
   {:rule :web-unknown-group :grain :form
    :escape "config_file {path \"capabilities\" key \"web.auth.groups.<name>.members\" value \"…\"} defines the group, or fix the name in :web/auth"
    :teach "an endpoint's [:group …] policy may only name groups the capabilities config defines — a typo'd group silently denies forever, the authz nil-pun (inert until web.enabled)"}
   {:rule :web-react-attrs :grain :form
    :escape "spell it as HTML (:class, :for), replace handlers with a link/form targeting an endpoint, or dial it down (config_file {path \"rules\" key \"web-react-attrs\" value \"advisory\"}) for a map that is genuinely not an element"
    :teach "a literal hiccup element carries a React attribute name (:className, :htmlFor, :onClick…) — browsers silently ignore unknown attributes, so it ships and does nothing (inert until web.enabled)"}
   {:rule :web-endpoint-schema :grain :form
    :escape "add :web/response (and :web/request on a body method) to the endpoint's name metadata — a .cljc malli schema var (shareable/reusable) or an inline [:map …] for a one-off — or dial it down (config_file {path \"rules\" key \"web-endpoint-schema\" value \"advisory\"})"
    :teach "a :web/path endpoint must declare :web/response (and :web/request on a :post/:put/:patch body method) — its contract, shared .cljc so the client validates against the SAME schema (D-web-contracts; inert until web.enabled)"}
   {:rule :web-public-mutation :grain :done
    :escape "tighten :web/auth, or accept it — a deliberately public write surface (signup, webhook) is legitimate and this asks per changed form"
    :teach "a changed :public endpoint declares :web/effects kinds — a publicly writable surface should be a decision, not an omission (inert until web.enabled)"}
   {:rule :web-dangling-route-refs :grain :done
    :escape "fix the path, add the endpoint or static asset, or mark the RENDERING form — ^{:web/external-path \"why\"} when something OUTSIDE this store serves it, ^{:web/client-path \"why\"} when the literal is THIS app's own client-router key that the render prefixes before it reaches the DOM (an SPA screen). Pick by which is true: the crossings inventory reports them as different exits, and external-path on an app path files a false statement in the one report that says what is unchecked"
    :teach "a rendered link/form targets a path no declared route or static mount serves — the UI nil-pun: it ships and 404s. Dynamic paths ride along as :info findings: reported, never status-flipping (inert until web.enabled)"}
   {:rule :schema-drift :grain :done
    :escape "fix the schema or the impl so they agree"
    :teach "a written :=> schema disagrees with its live impl (generative mg/check)"}
{:rule :stored-name :grain :done
    :escape "rewrite the form so the store recomputes its name — edit_replace_form, or edit_subform addressing it by the form ID the finding names (an id works where the name does not, which is the whole problem)"
    :teach (str "a form's stored :name disagrees with the name its own source"
                " defines. The store keeps both and derives one from the other"
                " at every write, so a disagreement means some write did not —"
                " and the consequence is silent: name-addressed surfaces"
                " (rename_sweep, edit_subform {form}, :left-behind's :form)"
                " skip the form and report their own counts as complete, while"
                " id-addressed passes keep working, so half the rename"
                " machinery succeeds and the other half says nothing")}
   {:rule :stale-pattern :grain :done
    :escape (str "rewrite the pattern to a name that exists — the finding's"
                 " :suggest is the only namespace sharing its last segment."
                 " There is deliberately no dial: a regex naming a name this"
                 " store's own family does not have has never yet been"
                 " intentional here, and an escape invented before its first"
                 " real case is one nobody can evaluate")
    :teach (str "a regex literal spells a name in this store's own namespace"
                " family that is neither a namespace nor a prefix of one, so"
                " the pattern cannot match what it was written to find. A"
                " search pattern is DATA: ns_rename rewrites requires,"
                " qualified refs, quoted symbols and prose but not patterns,"
                " and rename_sweep's text pass misses the escaped dots. The"
                " worst case is an absence assertion — (is (not (re-find …)))"
                " over a name that moved is permanently, silently true")}
   {:rule :key-typos :grain :done
    :escape "reuse the established key (query_vocabulary), or accept the new one"
    :teach "a new namespaced key is one Damerau edit from an established same-namespace key"}
   {:rule :breaking-changes :grain :done
    :escape "^:breaking-ok on the name (a DELIBERATE break — you own telling downstream; it polices itself, a marker that narrowed nothing is reported stale), restore the arity/key/visibility, or rename for a clean break"
    :teach "a module-external fn's contract narrowed (arity/schema-key/visibility) vs the last-done baseline"}
   {:rule :ambient-state :grain :done
    :escape "pass state in as an arg, or accept it (a legit top-level cache — and a defonce that a ^{:web/context true} builder merely REFERENCES is one, since a builder allocating its own atom hands the app a fresh one per call)"
    :teach "a global (def _ (atom/ref/agent/volatile! …)) — ambient mutable state a slice can't track"}
   {:rule :assertions-never-red :grain :done
    :escape (str "break the subject with a WRITE and watch the test bounce."
                 " Red is read from :verify deltas, so a bare test_run does not"
                 " clear this however red it was — and neither does a write"
                 " whose verification DEFERS the test, which is every"
                 " ^:external one. For those two paths there is nothing to run:"
                 " accept it. Advisory precisely because only you know whether"
                 " you already watched it fail")
    :teach (str "a changed deftest GAINED assertion forms and never went red this"
                " episode, so the new ones have only ever been seen green. The"
                " load-bearing half of red-first is not test-before-code, it is"
                " that every assertion was WATCHED FAIL at least once — adding"
                " one to an already-passing test skips that and nothing else"
                " notices. key-not-returned catches the one silently-vacuous"
                " shape; this asks the general question")}
   {:rule :marker-why :grain :done
    :escape "write the reason into the marker — ^{:unused-ok \"why\"} discharges exactly as ^:unused-ok does — or accept it; advisory, never blocking"
    :teach (str "an escape marker on a changed form is a BARE keyword, so it says"
                " a rule was waived and nothing about why. ^:unused-ok — ok for"
                " what reason? ^:entry-point — invoked by WHAT? The map form"
                " answers in place and the dial becomes provenance instead of a"
                " mute flag, the way :prompt rides every delta and"
                " ^{:covers \"ns/name — why\"} already does. NOT ^:export (its"
                " string already means the subtree it widens to)")}
   {:rule :ambiguous-index :grain :done
    :escape "read through store/form-docstring, store/def-init or store/form-symbol — or accept it; advisory, never blocking"
    :teach (str "a changed form indexes position 2 of a STORE FORM, where a"
                " docstring and a def's VALUE share the slot. This is the"
                " codebase's worst bug class and it is SILENT: a wrong index"
                " yields nil, nil is falsy, and the rule reading it stops firing"
                " while looking healthy. NOT 'positional access' in general —"
                " that predicate measured 4-5 false positives out of 5; a"
                " defmethod's dispatch value at index 2 cannot shift and is not"
                " flagged")}
   {:rule :web-spa-consequences :grain :done
    :escape "nothing to discharge — it states a consequence once, for the episode that declared the prefix"
    :teach (str "an endpoint gained :web/spa this episode: every path under the"
                " declared prefix now answers 200 instead of 404, and NOT-FOUND"
                " moves into the client. Correct, and what :web/spa is for — but"
                " a real semantic change that no surface mentioned, and one that"
                " two existing tests caught only by asserting the old status."
                " The prefix ROOT is not covered by the fallback and still needs"
                " its own route")}
   {:rule :namespace-purpose :grain :done
    :escape "add a docstring to the ns form saying why the namespace exists — or accept it; this is advisory and never blocks"
    :teach (str "a namespace the episode touched states no PURPOSE. Its inventory is"
                " DERIVED — query_project, the module surface and the outline all"
                " list its forms — so the docstring is for what no tool can derive:"
                " why it exists, what to expect inside, and how it relates to its"
                " neighbours. NOT a list of what it contains. review_scan :purpose"
                " answers the same question for the whole store; generated and"
                " empty namespaces are exempt (there is no author to ask)")}
   {:rule :bare-throw :grain :done
    :escape "return data / (ex-info …), or ^{:bare-throw-ok \"why\"} on the name when the exact exception type is required by something outside your control — a Java API contract, an InterruptedException, a test proving a non-ex-info gets masked (it polices itself: a marker on a form with no bare throw is reported stale)"
    :teach "ANY fn throws a freshly-constructed non-ex-info exception. The cost is not tidiness: a bare exception can only be caught by TYPE, so a caller handling one failure catches a whole class and swallows every unrelated bug with it — which is exactly how slopp.hub/post! came to report a project ABSENT whenever any bug fired. Give the throw ex-data and the catch can be narrow"}
{:rule :key-not-returned :grain :done
    :escape "fix the assertion to read a key the callee returns, or drop it — a read of a key the callee never returns is always nil"
    :teach "(:k local) where local is bound to a call whose statically-known return shape has no :k — a vacuous assertion that stays green no matter what the code does (assertions-that-cannot-fail)"}
   {:rule :stale-reference :grain :done
    :escape "fix the prose (or the reference) so the name resolves — the text is teaching, and teaching that lies costs a failed call to discover"
    :teach "a docstring/teach-string names a.b/c where namespace a.b is in this store but has no form c — a rename or move left the prose behind (gates never see a var inside a string)"}
   {:rule :retired-vocabulary :grain :done
    :escape "route through the normalizer, or ^:legacy-ok on the name if this form IS the normalizer (it polices itself — a marker that mixes nothing is reported stale)"
    :teach "a form ENUMERATES a retired vocabulary (two retired members, or one beside its replacement) — a second copy that missed the rename; declare yours with config_file {path vocabulary key <old> value <new>}"}
   {:rule :direct-http :grain :done
    :escape "call slopp.web.client/request, taking it as a PARAMETER so callers can pass client/fake-requester — or ^{:adapter \"http — why\"} on the name if this form IS the adapter (it polices itself; the value's first word names the port, so a \"postgres\" adapter is ignored rather than called stale)"
    :teach "a form reaches the network itself — a java.net.http.HttpClient, or a slurp of an http(s):// literal. Raw reaching belongs in a declared ADAPTER; everything else goes through the port and inherits its fake and its contract suite. TESTS ARE NOT EXEMPT: calling the port from a test still makes a REAL call, so an exemption would buy nothing and would carve out the one place this boilerplate breeds. Scoped to HTTP because a gate may only demand a port that EXISTS — slopp ships one for HTTP and none for files or subprocesses"}
   {:rule :web-generated-ns :grain :form
    :escape "regenerate via generate_client after changing the ENDPOINT (its :web/request/:web/response), strip the ^:generated marker to take manual ownership, or dial it down (config_file {path \"rules\" key \"web-generated-ns\" value \"advisory\"})"
    :teach "a ^:generated form is generate_client's output and must not be hand-edited — regeneration rewrites the whole client namespace, so a hand edit is lost on the next generate (D-web-contracts part 2)"}
   {:rule :web-page-reach :grain :done
    :escape "move the view/derive code the entry reaches into a :jvm or :cljc namespace and pass the browser-shaped parts IN (:fetch, :render, a url pusher); or drop the ^:web/page marker if this app is not meant to be reviewed headlessly"
    :teach "a ^:web/page entry REACHES a :cljs namespace, so no JVM can open the app — the screen tool and every headless test fall back to a hand-built lookalike, which passes while the real screen is wrong. web-page-unreachable refuses the ENTRY's own shape at the write; this is the reach. At done it sees only pages you CHANGED — the dependency-flip case (some other namespace declared :cljs, no write to the entry) is reported by module_platform itself at the declaration (:stranded-pages) and re-graded by the full_check sweep (inert until web.enabled)"}
   {:rule :web-stale-client :grain :done
    :escape "run generate_client to re-derive the client from the current endpoints, or accept the drift"
    :teach "the generated typed client is stale — an endpoint's contract changed since generate_client last ran (D-web-contracts part 2)"}
   {:rule :web-inline-schema-dup :grain :done
    :escape "extract the shared inline schema to a named .cljc var both endpoints reference, or accept the duplication"
    :teach "2+ endpoints declare the same inline request/response schema — a shared shape belongs in one named .cljc schema so the server and the generated client agree (D-web-contracts part 2)"}
   {:rule :shell-widening :grain :done
    :escape "move the effect into an existing SHELL namespace and keep the pure part in core, or accept the widening (it asks once)"
    :teach "this episode declared a namespace :external/:internal — the functional CORE got smaller, and only you know whether it had to"}
   {:rule :tier-governance :grain :done
    :escape "declare the moved namespace's OWN tier (module_purity, namespace path — most specific wins), or move the effects out of it"
    :teach "a namespace this episode RENAMED or relocated now sits under a stricter tier by prefix, and its forms exceed it. Tiers are inherited, so the move — not any write — put this code under a rule it cannot satisfy, and the write-time gate only ever sees forms a write touches: nothing would re-check it until someone happened to edit one"}
      {:rule :module-governance :grain :done
    :escape "declare the edge (module_dep), hoist the target into its module's surface (^:export on the defn name, or ^{:export \"prefix\"} for a subtree), or restructure the call"
    :teach "a relocation this episode made left a call that breaks a module rule — usually a rename taking a namespace from two segments to three, which makes it package-private while its callers stay outside. The namespace that MOVED is not the one reported: the caller is, and the caller never moved. Module rules are inherited from the NAME and enforced at write time, and ns_rename rewrites its own callers, so nothing re-checks them — the operation most likely to drift the architecture is the one the architecture's check cannot see"}
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
