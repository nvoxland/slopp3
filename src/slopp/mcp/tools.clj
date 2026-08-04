(ns slopp.mcp.tools
  "The tool descriptors — what an agent sees before it calls anything.

  This is the highest-leverage prose in the system and the easiest to
  under-weight: most agents never read a docstring, a doc page or a skill.
  They read this. A description that omits an argument means nobody finds it;
  one that names a tool that no longer exists costs a failed call to discover.

  The schemas are ENFORCED, not advisory. `call-tool!` refuses any argument a
  schema does not declare, which makes an accurate schema a precondition
  rather than a courtesy — the strictness was added after four rounds of
  silently-ignored arguments, and it immediately surfaced one real gap where
  a tool read a key it had never advertised.

  Split per group so the registry stays editable without touching a monolith,
  and `read-only-tools` rides alongside because the same fact — this never
  modifies the store — decides both the MCP `readOnlyHint` and whether a
  client has to prompt.")

(def orientation-tools
  "Read/orient tool descriptors: project, search, source, dossiers, the oracle. (Q4: the registry is per-group \u2014 editable without touching a monolith.)"
  [{:name "query_project"
    :description "THE orientation call: every namespace's outline (names, arities, !-status, test-ness) in one response. Call ONCE; detail=true adds doc lines; pass since=<your last delta id> on a re-check — unchanged structure returns a one-liner."
    :inputSchema {:type "object" :properties {:since {:type "string"}
                                              :detail {:type "boolean"}}}}
   {:name "query_search"
    :description "Regex search across all store source; hits are {:ns :form :line}. Search before reading."
    :inputSchema {:type "object"
                  :properties {:pattern {:type "string"}
                               :limit {:type "integer"}}
                  :required ["pattern"]}}
   {:name "query_source"
    :description "Form source from the store. targets [{ns name}…] reads SEVERAL named forms in ONE call — the normal read. ns alone returns the OUTLINE (name forms, or pass full: true for a whole-namespace dump — rarely needed; compose edits from the outline and let :source-now correct misses)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :full {:type "boolean"}
                               :targets {:type "array"
                                         :description (str "each target is \"ns/name\" (or plain"
                                                           " \"ns\" for its outline), or the"
                                                           " equivalent {ns, name} object")
                                         :items {:oneOf [{:type "string"}
                                                         {:type "object"
                                                          :properties {:ns {:type "string"}
                                                                       :name {:type "string"}}
                                                          :required ["ns"]}]}}}}}
   {:name "query_brief"
    :description "THE form dossier, one call: source + effect flags + cross-ns callers + the tests covering it + the recorded WHY (last prompt/intent). Prefer this over separate source/references/lineage reads when you're about to change a form."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "session_brief"
    :description "START HERE, once: namespaces with form names, recent milestones, git alignment, and the working loop — orientation in one small call. Depth on demand: query_source {ns}/query_brief/report."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_slice"
    :description "THE focused read: full source of ONE entry-point form + interface CARDS (sig, doc, why, test warranty) for everything it reaches — same-ns private helpers and cross-ns callees, breadth-first to depth (default 2, capped). match=<text> WINDOWS the target to `window` lines (default 25) around the first matching line — use it on giant forms. Trust the cards: edits re-run covering tests, a violated contract turns red with :implicated. Prefer over fetching several forms."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :depth {:type "integer"} :limit {:type "integer"}
                               :match {:type "string"} :window {:type "integer"}}
                  :required ["ns" "name"]}}
   {:name "query_depends"
    :description "THE generic dependency question: what depends on X — a namespace (who requires it + qualified refs), a var ns/name (blast radius), or a :keyword (field flow). modules=true reads the MODULE system: alone = the manifest (declared edges + standing debt); with on=<module> = that module's SURFACE (public fns + exported deep vars with sig/doc, its deps, its consumers) — the cheap browse before calling into a module. Ask this first; query_slice {ns name} and query_brief {ns name} give per-form depth."
    :inputSchema {:type "object"
                  :properties {:on {:type "string"}
                               :direction {:type "string" :enum ["dependents" "dependencies"]}
                               :modules {:type "boolean"}
                               :detail {:type "boolean"}}}}
   {:name "review_scan"
    :description "REVIEW TRIAGE for a whole codebase (or one :ns): every form the store thinks is RISKY — untested (no covering test), off-platform (:cljs — the JVM oracle cannot load it, so NO test could ever cover it: the compiler is its only check, and this is a standing fact rather than a gap to close, so it ranks below untested), unused (public with ZERO in-store callers — dead code or unadvertised surface; whole scans only), effectful (!), high-blast (many callers), large, lint-flagged, or undocumented public surface — RISK-RANKED so you read the dangerous forms first. One pass; :top rows carry :form/:risk/:flags/:callers/:covered; drill in with query_slice. Run a test_run first so :untested is populated — :covered comes from the observed trace, so a form only ever exercised ACROSS A PROCESS BOUNDARY (an endpoint hit over a socket, a namespace mounted by quoted symbol) reads as untested too: check whether a test reaches it that way before believing the flag."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :limit {:type "integer"}}}}
   {:name "query_detail"
    :description "The FULL version of a trimmed response (responses over the size gate carry a query_detail id). The spool keeps the last 20."
    :inputSchema {:type "object"
                  :properties {:id {:type "string"}}
                  :required ["id"]}}
   {:name "query_eval"
    :description "Read-only REPL eval against the live image (the oracle) — the escape hatch for ARBITRARY expressions. For the common case (invoke one fn with data args) prefer query_call: it carries the reference so renames/moves/the unused gate see it. Questions ABOUT the codebase-as-data: query_store."
    :inputSchema {:type "object"
                  :properties {:code {:type "string"}}
                  :required ["code"]}}
   {:name "query_call"
    :description "Observe-only INVOKE of one var in the live image: {sym \"app.core/f\", args [1 2]} — the structured face of query_eval's common case. The reference is CARRIED (visible to renames, moves, and the unused gate) instead of hidden in an eval string; args must be printable data."
    :inputSchema {:type "object"
                  :properties {:sym {:type "string"}
                               :args {:type "array"}}
                  :required ["sym"]}}
   {:name "query_store"
    :description "The STORE-VALUE oracle: one read-only (fn [store] ...) evaluated over the current immutable store value — ad-hoc analysis ABOUT the codebase (form counts, metadata sweeps, custom aggregation) that no canned query covers. Fully-qualify everything (slopp.store/forms, slopp.store.render/render-ns, slopp.index.analyze/analyze ...); no effects/defs/interop/IO; results must print small. timeout_ms default 10000."
    :inputSchema {:type "object"
                  :properties {:code {:type "string"}
                               :timeout_ms {:type "integer"}}
                  :required ["code"]}}
   {:name "query_observe"
    :description "Capture args/returns of ns/name while running driver `code` — what actually flows through it."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :code {:type "string"}
                               :limit {:type "integer"}}
                  :required ["ns" "name" "code"]}}
   {:name "query_macroexpand"
    :description "Macroexpansion (expand-1 + full)."
    :inputSchema {:type "object" :properties {:code {:type "string"}}
                  :required ["code"]}}
   {:name "query_branches"
    :description "Branches with head deltas; marks the current one."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_vocabulary"
    :description "Browse the store's domain-keyword VOCABULARY (namespaced keys, most-used first) BEFORE coining new ones, so you REUSE an established key like :user/email instead of inventing a near-duplicate the key-hygiene advisory flags at done. Optional ns narrows to a keyword namespace (exact or dotted-child; e.g. `user` matches :user/* and :user.address/*)."
    :inputSchema {:type "object" :properties {:ns {:type "string"}}}}
   {:name "query_rules"
    :description "The ENFORCEMENT CATALOG for this store: every D9 rule (write gates + done-time advisories) with its grain, its EFFECTIVE per-store severity, how to discharge it, and what it means. See what's gated and at what grade. Dial any rule with config_file {path rules key <rule> value <severity>} — off / advisory / error / refuse."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_capabilities"
    :description "Every capability setting for this store: the declared registry (type, default, doc) joined with the stored `capabilities` config — effective values, what's set, and the wildcard families (web.static.*, web.auth.<provider>.*, web.auth.groups.*.members). Set with config_file {path capabilities key <k> value <v>}; writes validate against the registry."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_routes"
    :description "The store's declared WEB surface: every endpoint (method, path, auth policy, handler, declared :web/effects / :web/reads, schema presence) plus the derived effect/read performer vocabularies — the same derivations the web write gates enforce. Empty with teaching until web.enabled."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_rule_telemetry"
    :description "The D9 rules' FIRE-RATE + DISCHARGE signal for this store — the demand signal the severity dial is set by. Per rule: how often it fires (dones/instances), whether findings get :discharged (fixed) or :persisted (keep recurring = ignored/friction); plus escape-marker density (agents opting out via ^:unsafe/^:reads/^:unused-ok) and the current dials. Read-only history analysis over the delta log. Optional since (a delta/commit id from query_commits) windows it."
    :inputSchema {:type "object" :properties {:since {:type "string"}}}}])

(def history-tools
  "Provenance tool descriptors: history, time-travel, change queries. (Q4: the registry is per-group \u2014 editable without touching a monolith.)"
  [{:name "report"
    :description "THE summary/handoff composite: milestones + net form changes with their recorded asks + last verification + alignment, in one read. since=<delta/milestone id>, contains=<filter>. Prefer over stitching query_history/query_changes/query_commits."
    :inputSchema {:type "object"
                  :properties {:since {:type "string"}
                               :contains {:type "string"}
                               :limit {:type "integer"}}}}
   {:name "query_history"
    :description "EVERYTHING that happened, one tool: no args = change history (collapse=true for episode rows); {ns name} = one form's life; {ns name at} = TIME-TRAVEL to a past delta/milestone; {ns name effort true} = what that form COST to get green (red→green cycles, distinct asks, recorded verification time + how much of its life that covers); {at} = was-green-at; {contains} = which asks/prompts touched X; {dead_ends true} = SCRAPPED explorations (reverts) with their why + the forms they dropped, {dead_ends \"some.ns\"} narrows to ones that touched it — check it before re-walking a path someone already abandoned. format=text for humans. For summaries/handoffs use report."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :at {:type "string"} :contains {:type "string"}
                               :limit {:type "integer"}
                               :collapse {:type "boolean"}
                               :effort {:type "boolean"}
                               :dead_ends {:type ["boolean" "string"]}
                               :format {:type "string" :enum ["edn" "text"]}}}}
   {:name "query_changes"
    :description "THE code-level change view — net per-form diffs (:was/:now) + red/green arc. Your open episode by default, or a span via :from/:to. :from takes NAMED ANCHORS as well as delta ids: \"start\" (the whole lifetime), \"last-commit\" (since the last milestone), \"last-done\". This is what answers 'show me what changed, WITH the code' — reach for it instead of shelling out to git diff; format=text renders line diffs."
    :inputSchema {:type "object"
                  :properties {:from {:type "string"} :to {:type "string"}
                               :format {:type "string" :enum ["edn" "text"]}}}}])

(def edit-tools
  "Write tool descriptors: forms, groups, renames, refactors. (Q4: the registry is per-group \u2014 editable without touching a monolith.)"
  [{:name "ns_create"
    :description "Create a BRAND-NEW namespace (never overwrites). Either `requires` (clause strings) scaffolds an empty ns to grow with red-first TDD, or `source` lands whole namespace text in one verified call (ported/reference code) — mutually exclusive. `platform` (\"jvm\"/\"cljc\"/\"cljs\") declares the namespace's target platform up front, so a client ns is born :cljs — its js/* forms defer to the ClojureScript compiler (compile_client) instead of failing to load into the JVM oracle."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :requires {:type "array" :items {:type "string"}}
                               :source {:type "string"}
                               :platform {:type "string" :enum ["jvm" "cljc" "cljs"]}
                               :prompt {:type "string"}}
                  :required ["ns"]}}
   {:name "ns_add_require"
    :description "Add one require clause (e.g. \"[clojure.string :as str]\") to the ns form."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :require {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "require"]}}
   {:name "ns_remove_require"
    :description "Remove a library's require spec from the ns form."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :lib {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "lib"]}}
   {:name "edit_move"
    :description "Move a form to just before another (definitions precede callers)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :before {:type "string"} :prompt {:type "string"}}
                  :required ["ns" "name" "before"]}}
   {:name "edit_comment"
    :description "Set (or clear, with empty text) the comment rendered above form `name`. Owned by the form; no anchor, no verification."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :text {:type "string"}
                               :prompt {:type "string"}}
                  :required ["ns" "name" "text"]}}
   {:name "edit_replace_form"
    :description "Replace a whole top-level form (verified write)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :source {:type "string"} :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name" "source"]}}
   {:name "edit_add_form"
    :description "Add a top-level form (verified write); `before` anchors placement, default tail."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :source {:type "string"}
                               :before {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "source"]}}
   {:name "edit_delete_form"
    :description "Delete a top-level form (verified write; ns-unmap in the image, and a defmethod is unregistered from its multi). REFUSES while anything still CALLS it, naming the callers — the same stance ns_delete takes for a namespace something still requires, and for the same reason: the delete would commit, the namespace would fail to RELOAD, and the store would boot nowhere. Only compile-time (:static) references block; a quoted symbol or a ^{:covers} marker does not, and a recursive fn is not its own caller. To remove a caller and its callee together, delete the CALLERS first and the callee last — dependency order reversed, one call each. Two forms that call EACH OTHER have no valid order: edit_replace_form one to drop the call, then delete both. Say WHY in prompt."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "edit_subform"
    :description "Small change INSIDE a big form. match = ONE exact subform or pair (a missed/ambiguous match returns :source-now — correct and resend, no read needed); text: true matches raw text (strings/docstrings) EXACTLY as :source-now shows it — no extra escaping, backslashes literal; where: {key value} addresses the unique MAP containing those entries (registry rows by :name — no exact text needed); OR after: a COMPLETE neighboring form/pair — source is INSERTED right behind it (the let-binding splice without shaping a half-open match); OR wrap: true, where source is a TEMPLATE and $1 is the matched form — `(let [n 1] $1)` NESTS what was there inside what you wrote, so introducing a binding around existing code costs the template instead of a retype of the whole enclosing form. The replacement may splice several forms."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :form {:type "string"}
                               :match {:type "string"} :source {:type "string"}
                               :text {:type "boolean"}
                               :wrap {:type "boolean"}
                               :where {:type "object"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "form" "source"]}}
   {:name "edit_revert"
    :description "Revert a form to an earlier version (default previous, or a delta id)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :to {:type "string"} :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "rename_sweep"
    :description "A concept rename as ONE intent: every namespace, var, keyword, and prose occurrence of `from` (whole word/segment) becomes `to`, store-wide — ns renames + one atomic group, one verification. THE tool for docs-team renames ('zone is now region'); never do those form-by-form. Renaming a KEYWORD also moves its `{:a/keys [x]}` destructuring, which names the key as a SYMBOL and so is invisible to a text pass — matched on the FROM qualifier, so `{:keys [x]}` names `:x` and is left alone by a rename of `:a/x`. READ THE RESULT: :requalified lists the destructurings it restructured (the half of the diff that is not a text substitution), :left-behind the ones it DECLINED — changing a key's NAME rather than its qualifier would rename a local the body reads, so that half is yours. Absence of either means checked-and-none. PREVIEW FIRST with dry-run: it writes nothing and splits the hits into :in-code and :in-strings — the string bucket needs an eye, since a sweep rewrites keyword text inside string literals and a test FIXTURE is data, not prose."
    :inputSchema {:type "object"
                  :properties {:from {:type "string"} :to {:type "string"}
                               :dry-run {:type "boolean"
                                         :description "preview only: write nothing, report :in-code and :in-strings"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["from" "to"]}}
   {:name "edit_requalify"
    :description "Namespace a module-external fn's OPTION KEYS in ONE intent: its arglist destructuring AND every caller's map literal, together. THE way to discharge require-namespaced-keys — a store-wide rename_sweep is unsafe whenever the key means more than one thing (:dir names three different things), and hand-editing dozens of call sites is worse. Keys are DERIVED from the arglist, so half a contract cannot be namespaced and left reading nil. `to-ns` defaults to the target's namespace. Callers passing a NON-literal map are reported under :unknown-shape and left UNTOUCHED — no syntactic reader can see through a binding, and those are yours to check. Call sites outside the store (kernel .clj files) are invisible to it. dry-run previews without writing."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :to-ns {:type "string"}
                               :dry-run {:type "boolean"
                                         :description "preview only: write nothing, report the form count and :unknown-shape"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "edit_rename"
    :description "Rename ONE form + every reference across namespaces (shadow-safe). For concept-wide renames (ns + keys + prose) use rename_sweep; to rename a namespace's ALIAS rather than a var, ns_realias."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :old {:type "string"}
                               :new {:type "string"} :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "old" "new"]}}
   {:name "ns_realias"
    :description "Rename ONE namespace's require alias as a single intent: the `:as` in its ns form and every `alias/sym` in its bodies, together. THE tool for an alias ns_rename left stale — a rename rewrites namespaces and walks straight past the `:as`, so moved code goes on being called by its old module's name; when that name is later REUSED the alias points at a real, different module, which is worse than one naming nothing. There is no safe hand route: the two halves cannot be separate writes, because between them the ns form and the bodies disagree about the qualifier and the namespace does not load. Scoped to one namespace on purpose — an alias is a name ONE namespace chose, so two namespaces calling a lib different things is not drift. A BARE occurrence of the old alias is LEFT ALONE: only `alias/x` is the qualifier, and the same spelling is routinely a local or a parameter. READ THE RESULT: :sites is how many qualified references moved (0 means the alias was unused, not that nothing happened); :left-behind is the alias named inside STRING literals — fixture source, a docstring saying `alias/f` — reported and never rewritten, because rewriting one half of a fixture is how a half-renamed ns form ships green. Absence means checked-and-none."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :old {:type "string"}
                               :new {:type "string"} :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "old" "new"]}}
   {:name "change_signature"
    :description "Change a fn's signature atomically: `source` = the new defn (same name); `calls` = arg-list template rebuilding every call site ($1..$9 = the site's existing args; adding a trailing arg = \"$1 $2 nil\"). Higher-order refs return under :manual."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :source {:type "string"} :calls {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name" "source" "calls"]}}
   {:name "edit_extract"
    :description "Extract a subform of `from` into a new fn (params computed from free locals, call site rewritten, verified). Address the subform EITHER by `form` (its exact source) OR by `at` — an ANCHOR, its first line or so, which need not parse on its own (\"(let [turn-brackets\"). Prefer `at` for anything large: quoting a big subform's whole body means transcribing the exact code you were trying not to touch. A non-unique anchor asks you to extend it; a missing one returns :source-now."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :from {:type "string"}
                               :form {:type "string"}
                               :at {:type "string"
                                    :description "anchor: the subform's head; resolves to the smallest complete form containing it"}
                               :name {:type "string"}
                               :prompt {:type "string"}}
                  :required ["ns" "from" "name"]}}
   {:description "Walk back your OWN recent writes — the cheap, reach-for-it-immediately undo. deltas: n (default 1) undoes your last n writes; to: \"d123\" undoes everything of yours after that delta. to also takes a NAMED anchor: \"last-commit\" scraps everything since the last milestone (the usual dead-end rollback — no delta id to hunt), \"last-done\" goes back to your last done point. Addressed by DELTA, not by name, so it also restores a form you DELETED — the case edit_revert structurally cannot reach (no name left to look up). Forms another agent also wrote in the span are skipped and reported. deltas counts over the LOG and REFUSES rather than reaching past: a delta whose op it cannot invert (ns_rename, edit_move_forms, ns_delete, config_file, module_dep — anything changing more than form sources) comes back in :blocked with NOTHING reverted, rather than being stepped over to undo something older. One atomic verified group. Reach for this the moment a write turns out wrong; use episode_revert only to scrap a whole episode.", :inputSchema {:properties {:prompt {:type "string"}, :deltas {:type "integer"}, :to {:type "string"}}, :type "object"}, :name "undo"}
   {:name "episode_revert"
    :description "Roll back everything YOU changed since your last done (other sessions' forms skipped, reported). To walk back just one write, or a short chain, without losing the rest of the episode, use undo."
    :inputSchema {:type "object"
                  :properties {:prompt {:type "string"}}}}
   
   {:name "ns_rename"
    :description "Rename a WHOLE namespace everywhere (decl, requires, qualified refs). Verified. READ THE RESULT: a relocation lands as one changeset and runs NO write gates, so nothing refuses what it breaks. :left-behind lists what no rewrite reaches — strings, qualified KEYWORDS, the -test sibling, and under :alias the callers whose `:as` still spells the OLD name, because a rename rewrites the lib symbol beside an alias and never the alias itself. Each :alias row carries :suggest, the alias to pass ns_realias — absent where that caller already spells another lib that way, since realias would refuse it. An alias that reads correctly for BOTH names (a namespace changing modules under the same last segment) is not reported and needs nothing. :module-debt lists the module_dep edges its callers now need, calls that now reach a package-private ns, and cycles module_dep will refuse. Absence of either means checked-and-none. Run the ns_realias calls now rather than later: a stale alias is harmless only until the old name is REUSED, after which it points at a real and different module — which reads identically in the source and is the worse failure."
    :inputSchema {:type "object"
                  :properties {:old {:type "string"} :new {:type "string"}
                               :prompt {:type "string"}}
                  :required ["old" "new"]}}
   {:name "ns_delete"
    :description "Retire a namespace: refuses while any form remains (edit_delete_form them first — each deletion verified) or any other ns still requires it (ns_remove_require) — then removes the empty husk from store, image, and every projection. One :ns-delete delta; say WHY in prompt."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :prompt {:type "string"}}
                  :required ["ns"]}}
   {:name "edit_move_forms"
    :description "Move forms to another namespace, NEW or EXISTING — the general relocation refactor. Callers EVERYWHERE (prod + tests) are rewritten to alias-qualified calls and gain the require; moved defs are publicized (module visibility is the boundary); the target gets only the requires the moved code uses; dependency direction is analyzed (a two-way split refuses — a real cycle). export: true WIDENS per var — a var already ^:export keeps its level without the flag, and passing it does not silently widen the rest. A NEW target inherits the SOURCE's purity tier when the source declared one, so a split does not drop its forms into the shell; an undeclared source mints nothing. READ :export-not-landed — the move checks its own postcondition against the committed store and names the var. One atomic group, verified."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :forms {:type "array" :items {:type "string"}}
                               :to {:type "string"}
                               :export {:type ["boolean" "string"]
                                        :description "true = world surface; a namespace-prefix string = visible to that subtree only"}
                               :prompt {:type "string"}}
                  :required ["ns" "forms" "to"]}}
   {:name "module_extract"
    :description "Regroup at MODULE grain: pull whole namespaces (with their subtrees and -test siblings) under `to` — the op for a namespace that grew into its own component, or for giving a set of them one owning prefix. Going from two segments to three makes a namespace PACKAGE-PRIVATE, so every outside caller would break at once: this hoists exactly the vars that lose visibility (^:export) BEFORE renaming, then renames (callers, requires, prose, and the manifest all follow), then declares the edges the moved store actually references. ALWAYS dry-run first: it writes nothing and returns the plan — the renames, every var that must be exported AND WHICH CALLERS FORCE IT, the edges that appear, the edges the regroup unbacks. Refuses a regroup that would leave a production module cycle; a -test back-edge is not one."
    :inputSchema {:type "object"
                  :properties {:namespaces {:type "array" :items {:type "string"}
                                            :description "the namespaces to pull; each takes its subtree and -test sibling with it"}
                               :to {:type "string" :description "the owning prefix, e.g. \"my.core\""}
                               :dry-run {:type "boolean"
                                         :description "plan only: write nothing, report renames / exports+who-forces-them / edges / refusal"}
                               :prompt {:type "string"}}
                  :required ["namespaces" "to"]}}
   {:name "cleanup"
    :description "Bring a namespace up to current standards — the WHOLE namespace, regardless of what you touched. Pass `all: true` instead of `ns` to sweep the ENTIRE store: that is the migration surface for adopting slopp on an existing codebase, or for landing a slopp upgrade that adds a rule every existing form predates. APPLIES: normalize every form (conservative, behavior-preserving), reorder definitions above their callers, retire legacy or stale (declare …)s and phantom names. REPORTS everything slopp can check on a WRITE, replayed over EXISTING code — :lint, :unused (dead public surface), :undocumented, :gates (module / tier / schema / namespaced-keys write gates), :advisories (ambient-state, bare-throw, key-typos, schema-drift) and :purity (which tier the namespace could support). Those all normally fire only as code is written, so a form predating a rule was never subject to it. Findings are REPORTED, never auto-fixed — each needs a judgment call."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :all {:type "boolean"
                                     :description "sweep every namespace in the store (migration mode); omit ns"}
                               :prompt {:type "string"}}}}])

(def flow-tools
  "Session-flow tool descriptors: turns, tests, done-points, milestones, build. (Q4: the registry is per-group \u2014 editable without touching a monolith.)"
  [{:name "turn_begin"
    :description "Open a turn manually (records the verbatim user ask as intent). Turns are normally opened FOR you by the plugin's hooks — only needed if a write is refused."
    :inputSchema {:type "object"
                  :properties {:intent {:type "string"}
                               :user {:type "string"}}
                  :required ["intent"]}}
   {:name "turn_end"
    :description "Close the turn (usually automatic)."
    :inputSchema {:type "object"
                  :properties {:note {:type "string"}}}}
   {:name "full_check"
    :description "The WHOLE-STORE check, on demand: kondo over every namespace, dead public surface over every namespace, and every test in every tier — in-image, ^:integration, and the external ^:external suite. One call, everything; there is no separate integration-only or lint-only tool. NOTHING forces this — not done, not commit_point. `done` is episode-scoped (it answers whether the work you just did is good); this answers whether the STORE is good, which is slower and is your judgement call. Reach for it when a change was broad, when you deleted a caller (dead surface appears in namespaces you never touched), or before a commit you want to stand behind. affected=true is the MIDDLE GEAR between done and the whole thing: lint/dead-surface/layering/in-image still cover every namespace (they cost ~5-7s), while the ^:external tier — which is ~187s of a ~190s run — narrows to the tests your changes since the last milestone can reach. The result states the narrowing."
    :inputSchema {:type "object" :properties {:affected {:type "boolean"}}}}
   {:name "done"
    :description "Close a unit of work: normalize your touched forms, re-verify, record a labeled boundary. EPISODE-SCOPED — it runs the whole in-image suite plus the ^:external tests your changes impact, but lint and dead-surface cover only the namespaces you touched and the full ^:external / ^:integration tiers do not run. Its :scope field says so every time, and names `full_check` for the whole store. done REPORTS; it never refuses — an unfixable finding is recorded honestly rather than blocking you. Selection is per form: trace evidence where it exists, the form's own namespace-reach where it does not."
    :inputSchema {:type "object" :properties {:label {:type "string"}}}}
   {:name "commit_point"
    :description "Record a MILESTONE — green-gated on the FULL ^:external suite (run automatically; no test_run first; force=true records red honestly and skips the gate). The git-projection grain; target=<delta id> marks an earlier spot."
    :inputSchema {:type "object"
                  :properties {:description {:type "string"}
                               :force {:type "boolean"}
                               :target {:type "string"}}
                  :required ["description"]}}
   {:name "test_run"
    :description "SPOT-CHECK specific tests: {ns \"x.y-test\"} or {only [\"x.y-test/some-t\"]}. Targets run in their OWN tier: in-image members in-image, named ^:external members in one serial external JVM — the red/green fast lane for an external test needs no {external true} detour. You do NOT need this before done or commit_point — done runs the affected tests in every tier (impacted ^:external included) and the milestone runs the whole external suite itself. Whole in-image suite: {all true} (rarely needed). Explicit full external run: {external true} — fresh JVM, auto-shards (:parallel N overrides), {affected true} narrows to test nses reaching changes since the last milestone. Red external runs return :failing + :all-failing {file [tests]} + :themes."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :only {:type "array" :items {:type "string"}}
                               :all {:type "boolean"}
                               :external {:type "boolean"}
                               :affected {:type "boolean"}
                               :fresh {:type "boolean"}
                               :parallel {:type "integer"}}}}
   {:name "draft_test"
    :description "A ready-to-edit deftest DRAFT for an :untested form. With :code (a driver expression) it observes real calls and turns each into an assertion; without, a signature skeleton with TODO holes. Nothing is written — adopt via edit_add_form, red-first."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :code {:type "string"}
                               :limit {:type "integer"}}
                  :required ["ns" "name"]}}
   {:name "help"
    :description "The workflow cheat-sheet: which tool for what, how to read results."
    :inputSchema {:type "object" :properties {}}}
   {:name "restart"
    :description "Restart the live image; reload all forms."
    :inputSchema {:type "object" :properties {}}}
   {:name "build"
    :description "Materialize every namespace to .clj files under dir (absolute). Optional main (qualified entry fn) adds a GraalVM native-image recipe."
    :inputSchema {:type "object"
                  :properties {:dir {:type "string"} :main {:type "string"}
                               :name {:type "string"}}
                  :required ["dir"]}}])

(def env-tools
  "Environment tool descriptors: deps, files, config, branches. (Q4: the registry is per-group \u2014 editable without touching a monolith.)"
  [{:name "config"
    :description "Read/set store config (user.name / user.email — the milestone author; \"<git>\" defers to git config). Omit value to read."
    :inputSchema {:type "object"
                  :properties {:key {:type "string"}
                               :value {:type "string"}}
                  :required ["key"]}}
   {:name "file_put"
    :description (str "Track a non-code file on the files manifest (rides every projected"
                      " tree). Text by default; encoding \"base64\" stores BINARY"
                      " content-addressed (content-type labels it) — the journal carries"
                      " only the sha. `source` reads the bytes from a PATH on disk instead"
                      " of taking them inline, which is what you want for a vendored"
                      " library, font or image: inline means reading the file into your"
                      " own context and writing it straight back out.")
    :inputSchema {:type "object"
                  :properties {:path {:type "string"}
                               :content {:type "string"}
                               :source {:type "string"}
                               :encoding {:type "string"}
                               :content-type {:type "string"}
                               :prompt {:type "string"}}
                  :required ["path"]}}
{:name "js_dep"
    :description (str "Vendor and declare a JavaScript library — the third dependency world."
                      " ONE call: source names the bytes on disk, and they are written to"
                      " the content-addressed artifact cache with a :download recipe, so"
                      " the journal carries a sha and a way back rather than the library."
                      " Anchor provenance to the REGISTRY — npm \"roughjs@4.6.6\" +"
                      " npm-path \"bundled/rough.js\" + integrity — because npm versions are"
                      " immutable, where a CDN url only says how the bytes arrived this"
                      " time. format is \"iife\"/\"umd\" (concatenated into the bundle via"
                      " deps.cljs :foreign-libs, and global names what it sets on window)"
                      " or \"esm\" (loaded by the page). file is where it sits in the"
                      " project tree. remove retracts the declaration. Read them back with"
                      " query_store over :js-deps.")
    :inputSchema {:type "object"
                  :properties {:name {:type "string"}
                               :version {:type "string"}
                               :format {:type "string"}
                               :global {:type "string"}
                               :file {:type "string"}
                               :source {:type "string"}
                               :npm {:type "string"}
                               :npm-path {:type "string"}
                               :integrity {:type "string"}
                               :source-url {:type "string"}
                               :license {:type "string"}
                               :remove {:type "boolean"}
                               :prompt {:type "string"}}
                  :required ["name"]}}
   {:name "file_remove"
    :description "Drop a path from the files manifest."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"}
                               :prompt {:type "string"}}
                  :required ["path"]}}
   {:name "file_list"
    :description "The files manifest: {path bytes}."
    :inputSchema {:type "object" :properties {}}}
   {:name "file_get"
    :description "A manifest file's content (optionally at a past delta/milestone via `at`)."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"} :at {:type "string"}}
                  :required ["path"]}}
   {:name "file_history"
    :description "A manifest file's tracked versions with provenance."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"}}
                  :required ["path"]}}
   {:name "config_file"
    :description "STRUCTURED config file: semantic key/values with per-key history, serialized into the projection (e.g. META-INF/MANIFEST.MF). Set path+key+value; unset=true removes; path alone reads. Prefer over file_put for key/value config."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"} :key {:type "string"}
                               :value {:type "string"} :unset {:type "boolean"}
                               :format {:type "string"}
                               :prompt {:type "string"}}
                  :required ["path"]}}
   {:name "module_dep"
    :description "Declare (or retract with remove=true) ONE module dependency edge — modules are the first two ns segments (\"logi.parcel\"). Each call is one journaled delta; say WHY in prompt. Adds are cycle-checked over PRODUCTION edges. `test_only: true` declares the edge for the module's -test namespaces ONLY — production code under `from` is still refused, and a test-only edge is not a production edge so it is never a cycle. Reach for it when a fixture must drive a surface that calls back into this module (a done-time advisory can only be tested by writing code and calling done): the alternative is moving the test away from its subject. The cycle refusal names this option itself when every namespace crossing is a test. Read the manifest: query_depends {modules true}."
    :inputSchema {:type "object"
                  :properties {:from {:type "string"} :to {:type "string"}
                               :remove {:type "boolean"}
                               :test_only {:type "boolean"
                                           :description "bind the module's -test namespaces only; production stays refused"}
                               :prompt {:type "string"}}
                  :required ["from" "to"]}}
   {:name "module_purity"
    :description "Declare a NAMESPACE's purity tier for the functional-core gate. :pure = referentially transparent (no mutation, no rand/slurp) — that is what lets the generative schema check run on it; :internal = may mutate IN-PROCESS state (a memo, a registry) but touches NOTHING outside the process; :external = IO (files, subprocesses, network, db). Undeclared = :external = ungated. Scope is a namespace PATH and the MOST SPECIFIC declaration wins, so a pure core one level below an effectful module (shop.totals.round inside shop.totals) is declarable. Declaring VERIFIES THE FORMS already there and refuses a tier they exceed — the result's :verified/:unverified says so, because it does NOT check layering (whether the namespace requires a LOOSER tier): that verdict changes as legitimate work continues, so full_check owns it and a wrong tier can stand until then. The axis is internal/external because that is what decides how a thing must be TESTED: external needs isolation (fresh JVM, temp dirs), internal needs only a cache/state reset, pure needs nothing. Caches must go through slopp.cache so `internal` stays checkable. Say WHY in prompt. (:reads/:effects are legacy spellings of :internal/:external.) Read tiers: query_depends {modules true}."
    :inputSchema {:type "object"
                  :properties {:module {:type "string"}
                               :tier {:type "string"}
                               :remove {:type "boolean"}
                               :prompt {:type "string"}}
                  :required ["module"]}}
   {:name "deps_add"
    :description "Add an external dependency (hot to the live classpath, no restart). lib like \"org.clojure/data.json\"; version string or full coord map."
    :inputSchema {:type "object"
                  :properties {:lib {:type "string"}
                               :version {:type "string"}
                               :coord {:type "object"}
                               :client {:type "boolean"}
                               :prompt {:type "string"}}
                  :required ["lib"]}}
   {:name "deps_remove"
    :description "Remove a dependency (restarts the image)."
    :inputSchema {:type "object"
                  :properties {:lib {:type "string"}}
                  :required ["lib"]}}
   {:name "deps_list"
    :description "The dependency manifest: {:deps {lib coord}}, plus :host-override for any declaration slopp's own process bundles at a different version and so cannot honor (inert; the host's copy wins). Note what is NOT here: slopp's web framework. slopp vendors it into every store that uses it, at the version slopp is, so it is never declared and never drifts."
    :inputSchema {:type "object" :properties {}}}
   {:name "store_health"
    :description "What this store CARRIES, in bytes: the journal per op (heaviest first), the materialized state, the blob table, and the on-disk artifact cache. Cheap — SQLite LENGTH only, nothing parsed. full_check answers whether the store is CORRECT; this answers what it COSTS. Reach for it when a session feels slow to open, before growing what a delta carries, and periodically: a store can rot by GROWING, and nothing else measures that."
    :inputSchema {:type "object" :properties {}}}
   {:name "store_doctor"
    :description "The LEGACY sweep: elements that predate a rule slopp now enforces and that no ordinary tool can reach — hand-written (declare …) the ordering pipeline cannot see, two elements in one namespace defining ONE name (a form-addressed edit cannot say which you mean, and the last wins at load), and metadata that looks like one of slopp's dials but is not (^:unusedok waives nothing while reading as though it does). Every finding carries the call that fixes it. A THIRD question: full_check asks whether the store is CORRECT, store_health what it COSTS in bytes, this what is in here that the current rules would never have let in. Reach for it right after adopting an existing codebase (git_clone / import), where every form predates every rule — a store written entirely through slopp is normally clean."
    :inputSchema {:type "object" :properties {}}}
   {:name "ui_serve"
    :description "Serve THIS project's own API listener — /api/* as JSON, plus the shape of that API as EDN at /api/contracts. It has NO pages in it: `/` answers 404, so a human handed this url sees JSON. The screens live in the HUB, a separate application that renders every page and fronts this project at /p/<slug>/ (D-hub part 4) — when the answer is for a human, hand over session_brief's :hub, not its :ui. Served on the LIVE session, so warranty and observed examples are the ones this session actually has; a process that opened the same store fresh would show every form as covered by nothing. Returns {:url :port}. `port` pins the address for THIS run only — there is no capability for it, because this port is an output: DERIVED from the store dir so several projects on one machine never collide, stable across restarts, and reported rather than set. 7359 is slopp.hub.port, a different setting and a real one; `stop: true` shuts it down. Serving again EVICTS the running server rather than hunting for a free port, and a port someone else holds comes back as a sentence, not a stack trace."
    :inputSchema {:type "object"
                  :properties {:port {:type "integer"}
                               :stop {:type "boolean"}}}}
{:name "compile_client"
    :description "Compile the store's CLIENT namespaces (:cljc + :cljs, declared via module_platform) to JavaScript with the configured backend (default ClojureScript, compiled ON THE JVM — no Node) and record the output as a served file blob. Compile-error-as-oracle: analyzer warnings and hard errors are anchored to the owning store forms. `output` sets the served path (default public/cljs/main.js). slopp injects its OWN compiler toolchain at build time — never deps_add the compiler."
    :inputSchema {:type "object"
                  :properties {:output {:type "string"}}}}
   {:description "Generate the typed CLIENT (D-web-contracts part 2): one edit-PROTECTED :cljs namespace of typed fetch wrappers, validating the request OUT and the response IN against a shared :cljc schema. Two sources. WITHOUT `from`: read the endpoints THIS store serves. WITH `from` (a URL serving a published contract, e.g. http://host/api/contracts): generate against an API this store CONSUMES — writes a :cljc contracts namespace of the published schemas alongside the client, reads no other store, and refuses a contract version it does not know. The target ns defaults to the client/generated-ns config, else <this store's own namespace family>.client.api; `ns` overrides. Any OTHER namespace already holding generated forms comes back in :other-clients — it stays marked :cljs, so compile_client keeps bundling it. An EXPLICIT step (like compile_client, not on every edit); marks the ns :cljs so compile_client picks it up, and with client/auto-compile on schedules a background recompile. Endpoints whose schema can't ship to the client (non-:cljc, or a missing var) are SKIPPED and reported in :problems. Regenerate, never hand-edit.", :inputSchema {:properties {:ns {:type "string"}, :from {:type "string"}}, :type "object"}, :name "generate_client"}
{:name "module_platform"
    :description "Declare a MODULE's target platform for the client wave — :jvm (Clojure on the JVM, the default), :cljc (portable: loads on the JVM AND compiles to JS — shared schemas/logic), or :cljs (ClojureScript only: compiled to JS, NEVER loaded into the JVM oracle — browser code). Namespace grain, most-specific declaration wins (like module_purity). A :cljs namespace renders as .cljs under a separate cljs-src/ tree, is excluded from JVM image-load, and is verified by the cljs compile step, not the JVM. Say WHY in prompt. Read platforms: query_depends {modules true}."
    :inputSchema {:type "object"
                  :properties {:module {:type "string"}
                               :platform {:type "string"}
                               :remove {:type "boolean"}
                               :prompt {:type "string"}}
                  :required ["module"]}}
   {:name "deps_pure"
    :description "Assert a dep target is PURE so callers aren't !-flagged: a var (\"ns/f\"), a namespace, or a whole lib. pure=false undoes."
    :inputSchema {:type "object"
                  :properties {:target {:type "string"}
                               :pure {:type "boolean"}}
                  :required ["target"]}}
   {:name "branch_create"
    :description "Create a branch from the current state and switch to it (O(1))."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "branch_switch"
    :description "Checkout another branch (or main); the live image follows. Trace narrowing resets."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "branch_merge"
    :description "Merge a branch into the CURRENT line. Same-form divergence returns :conflicts (current kept; payload IS current source). The branch survives."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "branch_delete"
    :description "Delete a branch (never the one you are on)."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "merge_from"
    :description "Merge a diverged COPY of this project (absolute dir). Same-form divergence = :conflicts, ours kept."
    :inputSchema {:type "object"
                  :properties {:dir {:type "string"}}
                  :required ["dir"]}}])

(def sync-tools
  "Git-sync tool descriptors: push/pull/clone/conflicts and remotes. (Q4: the registry is per-group \u2014 editable without touching a monolith.)"
  [{:name "query_commits"
    :description "Milestones, newest first — TITLE lines only (+ :more-lines); {commit \"dN\"} drills into ONE full description (targets plug into query_changes from/to). With a git remote configured, :alignment PROVES whether the slopp branch head is the latest milestone's projection — trust it; no worktree/sqlite cross-checks."
    :inputSchema {:type "object" :properties {:commit {:type "string"}}}}
   {:name "query_git"
    :description "This session's git view: the saved external remote and the clone base it grafts onto, or a refusal naming how to set one."
    :inputSchema {:type "object" :properties {}}}
   {:name "git_push"
    :description "Publish slopp history to the git remote: from a checkout, pushes your slopp/<branch> mirror branches (current store branch by default; branches: [...] for more); a fileless store publishes its projection. First url becomes the saved default; one-off urls never rewrite it. Fast-forward only."
    :inputSchema {:type "object"
                  :properties {:url {:type "string"} :token {:type "string"}
                               :branches {:type "array" :items {:type "string"}}}}}
   {:name "git_clone"
    :description "Clone a remote into dir as a FILELESS store (every ns ingested + verified; no .clj files materialized)."
    :inputSchema {:type "object"
                  :properties {:url {:type "string"}
                               :dir {:type "string"}
                               :token {:type "string"}}
                  :required ["url" "dir"]}}
   {:name "git_pull"
    :description "Fetch the remote's slopp/<branch> mirrors into local git (fast-forward only) AND absorb remote store history (3-way: remote wins where you're clean; both-touched = conflict, yours stays live, push blocked until git_resolve)."
    :inputSchema {:type "object"
                  :properties {:url {:type "string"} :token {:type "string"}
                               :branches {:type "array" :items {:type "string"}}}}}
   {:name "git_conflicts"
    :description "Unresolved pull conflicts, with the raw remote content to merge from."
    :inputSchema {:type "object" :properties {}}}
   {:name "git_resolve"
    :description "Mark a pull conflict resolved (omit path = all). Unblocks git_push."
    :inputSchema {:type "object" :properties {:path {:type "string"}}}}])

(def image-free-tools
  "Tools that answer from the STORE VALUE + in-process analysis alone — they
  touch neither the owned image nor a write path, so the MCP dispatch serves
  them WITHOUT waiting for the async image boot (the server claims ready as
  soon as the store loads; orientation and reading are instant). Everything
  else — the oracle tools (query_eval/query_call/query_observe/
  query_macroexpand/query_store, which eval in the image) and every write —
  `api/await-image!`s the boot first. Being CONSERVATIVE is safe: a tool
  wrongly excluded here merely waits for the boot; one wrongly included
  would touch a not-yet-live image."
  #{"session_brief" "query_project" "query_search" "query_source"
    "query_brief" "query_slice" "query_depends" "query_history"
    "query_changes" "query_commits" "query_git" "query_branches"
    "query_routes" "query_capabilities" "query_rules" "query_rule_telemetry"
    "query_vocabulary" "query_detail" "help" "report" "review_scan"
    "file_get" "file_list" "file_history" "deps_list"})

(def read-only-tools
  "Tool names that never modify the STORE — advertised with the MCP
  readOnlyHint annotation so clients (Claude Code plan mode, permission
  systems) can auto-permit them instead of prompting. query_eval and
  query_observe qualify because the observe gate blocks redefinition —
  the code they run cannot change the codebase (observation captures are
  a metadata cache)."
  #{"query_search" "query_source" "query_detail" "query_project"
    "query_brief" "query_slice" "query_depends" "query_eval"
    "query_observe" "query_macroexpand" "query_branches" "query_history"
    "query_changes" "query_commits" "query_git" "session_brief" "report"
    "review_scan" "query_store" "query_call" "query_vocabulary" "query_rules" "query_rule_telemetry"
    "query_capabilities" "query_routes"
    "help" "deps_list" "file_list" "file_get" "file_history"
    ;; both only READ: health is SQLite LENGTH, doctor is a fold over the store
    ;; value. Plan mode is exactly when someone assessing an adopted codebase
    ;; reaches for them, and that is the mode where a prompt costs most.
    "store_health" "store_doctor"})

(def tools
  "Every tool descriptor the server advertises — concatenated from the
  per-group registries (Q4); read-only tools carry the MCP readOnlyHint
  annotation so plan-mode clients auto-permit them."
  (into []
        (comp cat
              (map #(cond-> %
                      (read-only-tools (:name %))
                      (assoc :annotations {:readOnlyHint true}))))
        [orientation-tools history-tools edit-tools flow-tools env-tools sync-tools]))

(def extra-accepted-arg-keys
  "Per-tool ALIASES the dispatch in slopp.mcp/call-tool! reads via
   (or (:canonical a) (:alias a)) — deliberately kept OUT of the advertised
   inputSchema so agents learn the one canonical key, but accepted (not refused
   as unknown) when a client sends the alias. call-tool! is the source of truth;
   the arg-forgiveness tests pin every entry, so a missed alias REDS the suite
   rather than silently refusing documented behaviour."
  {"edit_rename"    #{:name :from :to}
   "edit_subform"   #{:from :to :after}
   "edit_extract"   #{:source :subform}
   "rename_sweep"   #{:dry_run}
   "edit_requalify" #{:dry_run}})

(defn accepted-arg-keys
  "The full set of argument keys tool `name` accepts: its inputSchema
   properties, its forgiveness aliases (extra-accepted-arg-keys), and the
   universal cross-cutting keys (:agent stamped by the dispatch, :prompt intent,
   :verbose full payload). nil for a name the server does not advertise
   (`edit-group!` is deliberately off-wire; an unknown name) — that tool opts
   OUT of strict validation rather than refusing every key."
  [name]
  (when-let [d (some #(when (= name (:name %)) %) tools)]
    (into (into #{:agent :prompt :verbose} (extra-accepted-arg-keys name))
          (keys (get-in d [:inputSchema :properties])))))

(defn unknown-arg-keys
  "The keys in `arguments` that tool `name` does not accept — the ones the
   dispatch would otherwise silently DROP. Returns a seq, or nil when the tool
   opts out (see accepted-arg-keys) or every key is accepted. The wire refuses
   a call carrying any, so a mistyped or unsupported argument fails loudly
   instead of evaporating into a no-op (or, for a safety flag, its opposite)."
  [name arguments]
  (when-let [acc (accepted-arg-keys name)]
    (seq (remove acc (keys arguments)))))

(def cheat-sheet
  "slopp cheat-sheet
TURN:    turn_begin {agent, intent: <user's verbatim ask>} FIRST -- writes are
         refused without an open turn; turn_end {agent} when done (red is ok)
ORIENT:  query_project (everything, one call) · query_search {pattern} (the grep)
         query_source {targets [{ns name}]} (form source) · query_depends {on ns/name}
OBSERVE: query_eval {code} (your REPL: call anything; cannot redefine code)
         query_observe {ns name code} (capture args/returns flowing through a fn)
WRITE:   work like a REPL: small individual writes, each verifies and returns
         :test — mid-episode reds are normal; stale callers ride :carried-errors
         until done re-checks them.
         edit_add_form / edit_replace_form {ns name source prompt}
         edit_rename {ns old new}   <- never rename by editing call sites
         edit_extract {ns from form name} · edit_move {ns name before}
         ns_create {ns requires?|source?}  <- NEW namespace: scaffold+grow, or whole source at once
         ns_add_require / ns_remove_require  <- never hand-edit the ns form
RULES:   every write must compile -- but form ORDER is not your job: write
         forms in any order; the pipeline moves definitions above their
         callers and mints any (declare) itself. Yours are refused.
         red-first TDD = write the failing test FIRST (missing fns land as
         :red-first stubs and fail honestly), then implement
READ RESULTS: {:ok true ...} terse green · :failures = why (expected/actual)
         :diagnosis :genuine = real red, yours · :staleness-detected = healed
         :warnings = fix with edit_rename per :suggest · :untested = add a test
         (draft_test {ns name code} drafts one from OBSERVED calls)
SHARE:   git_push {url?} (milestones -> a normal git remote; url saved once)
         git_pull (3-way absorb: remote wins where you're clean; both-touched =
         conflict, yours stays live, push blocked until git_resolve {path})
         config {key value?} (user.name/user.email = milestone author identity)
FINISH:  done {label} (tidies, lints, marks the unit boundary)
         commit_point {description} <- MILESTONE: green-gated, the grain a
         human diffs and reverts to; coarser than done-points and turns")

(def single-write-tools #{"edit_replace_form" "edit_add_form"})

(def write-tools
  (into single-write-tools
        ["edit_delete_form" "edit_rename" "edit_extract"
         "edit_move" "ns_add_require" "ns_remove_require" "ns_create"
         "ns_delete" "done" "commit_point" "deps_add" "deps_remove"
         "deps_pure" "change_signature" "ns_realias"]))

(def wire-keys
  "Every key a write result may carry to the agent — ONE list, replacing the
  fourteen hand-maintained `select-keys` allowlists in `call-tool!`.

  **The union is safe, and that is the whole argument.** A key absent from a
  result is absent from the output whatever the allowlist says, so a per-tool
  list never protected anything — each was an independent guess at what that
  one operation returns. What they did instead was lose things: `:dry-run`'s
  payload, `:drift`, `:external-pending` and a `:fix` hint have each been
  built, tested and correct one layer down while the agent saw the old
  behaviour. `summarize`'s docstring has recorded that happening three times;
  the fourth is what produced this.

  Measured before consolidating: 14 lists, 39 distinct keys, and exactly TWO
  (`:error`, `:test`) appearing in all of them.

  Bulk payloads are not excluded here but by `summarize`, which strips
  `:source`/`:sources`/`:node` off deltas — a size concern, not a routing one,
  and it belongs where the shaping happens."
  #{;; refusals and the recovery they name
    :error :conflict :note :hint :suggestion :source-now :fix
    ;; what landed
    :delta :deltas :group :forms :affected :renamed :renamed-namespaces
    :mentions :changed-nses :reverted :skipped-shared :moved-to :moved :rewrote
    :callers :edges-declared :export-not-landed :export-note
    :extracted :step :to-ns :keys :unknown-shape
    ;; what a realias moved, and what it declined to
    :sites :lib :left-behind
    ;; what it cost and whether to believe it
    :test :ms :untested :image-healed :red-first :carried-errors
    :warnings :existing-warnings :advisories :drift :manual
    ;; a preview's whole point
    :dry-run :in-code :in-strings})
