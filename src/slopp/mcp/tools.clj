(ns slopp.mcp.tools)

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
                               :targets {:type "array"
                                         :items {:type "object"
                                                 :properties {:ns {:type "string"}
                                                              :name {:type "string"}}
                                                 :required ["ns"]}}}}}
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
    :description "THE generic dependency question: what depends on X — a namespace (who requires it + qualified refs), a var ns/name (blast radius), or a :keyword (field flow). modules=true reads the MODULE system: alone = the manifest (declared edges + standing debt); with on=<module> = that module's SURFACE (public fns + exported deep vars with sig/doc, its deps, its consumers) — the cheap browse before calling into a module. Ask this first; query_impact/query_flow/query_references give depth."
    :inputSchema {:type "object"
                  :properties {:on {:type "string"}
                               :direction {:type "string" :enum ["dependents" "dependencies"]}
                               :modules {:type "boolean"}}}}
   {:name "review_scan"
    :description "REVIEW TRIAGE for a whole codebase (or one :ns): every form the store thinks is RISKY — untested (no covering test), unused (public with ZERO in-store callers — dead code or unadvertised surface; whole scans only), effectful (!), high-blast (many callers), large, lint-flagged, or undocumented public surface — RISK-RANKED so you read the dangerous forms first. One pass; :top rows carry :form/:risk/:flags/:callers/:covered; drill in with query_slice. Run a test_run first so :untested is populated."
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
    :description "The STORE-VALUE oracle: one read-only (fn [store] ...) evaluated over the current immutable store value — ad-hoc analysis ABOUT the codebase (form counts, metadata sweeps, custom aggregation) that no canned query covers. Fully-qualify everything (slopp.store/forms, slopp.render/render-ns, slopp.index/analyze ...); no effects/defs/interop/IO; results must print small. timeout_ms default 10000."
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
    :description "EVERYTHING that happened, one tool: no args = change history (collapse=true for episode rows); {ns name} = one form's life; {ns name at} = TIME-TRAVEL to a past delta/milestone; {at} = was-green-at; {contains} = which asks/prompts touched X. format=text for humans. For summaries/handoffs use report."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :at {:type "string"} :contains {:type "string"}
                               :limit {:type "integer"}
                               :collapse {:type "boolean"}
                               :format {:type "string" :enum ["edn" "text"]}}}}
   {:name "query_changes"
    :description "Net per-form diffs + red/green arc: your open episode (default), or any past span (:from/:to delta ids); format=text for humans."
    :inputSchema {:type "object"
                  :properties {:from {:type "string"} :to {:type "string"}
                               :format {:type "string" :enum ["edn" "text"]}}}}])

(def edit-tools
  "Write tool descriptors: forms, groups, renames, refactors. (Q4: the registry is per-group \u2014 editable without touching a monolith.)"
  [{:name "ns_create"
    :description "Create a BRAND-NEW namespace (never overwrites). Either `requires` (clause strings) scaffolds an empty ns to grow with red-first TDD, or `source` lands whole namespace text in one verified call (ported/reference code). Mutually exclusive."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :requires {:type "array" :items {:type "string"}}
                               :source {:type "string"}}
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
   {:name "edit_trivia"
    :description "Replace the comment/blank-line run before form `before` (omit = namespace tail) with `text`. Trivia only; forms untouched."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :before {:type "string"}
                               :text {:type "string"}
                               :prompt {:type "string"}}
                  :required ["ns" "text"]}}
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
    :description "Delete a top-level form (ns-unmap, verified)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "edit_subform"
    :description "Small change INSIDE a big form. match = ONE exact subform or pair (a missed/ambiguous match returns :source-now — correct and resend, no read needed); text: true matches raw text (strings/docstrings) EXACTLY as :source-now shows it — no extra escaping, backslashes literal; whitespace runs are equivalent (reflowed docstrings match); OR where: {key value} addresses the unique MAP containing those entries (registry rows by :name — no exact text needed). The replacement may splice several forms."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :form {:type "string"}
                               :match {:type "string"} :source {:type "string"}
                               :text {:type "boolean"}
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
    :description "A concept rename as ONE intent: every namespace, var, keyword, and prose occurrence of `from` (whole word/segment) becomes `to`, store-wide — ns renames + one atomic group, one verification. THE tool for docs-team renames ('zone is now region'); never do those form-by-form."
    :inputSchema {:type "object"
                  :properties {:from {:type "string"} :to {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["from" "to"]}}
   {:name "edit_rename"
    :description "Rename ONE form + every reference across namespaces (shadow-safe). For concept-wide renames (ns + keys + prose) use rename_sweep."
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
   {:name "undo"
    :description "Walk back your OWN recent writes — the cheap, reach-for-it-immediately undo. deltas: n (default 1) undoes your last n writes; to: \"d123\" undoes everything of yours after that delta. Addressed by DELTA, not by name, so it also restores a form you DELETED — the case edit_revert structurally cannot reach (no name left to look up). Forms another agent also wrote in the span are skipped and reported. One atomic verified group. Reach for this the moment a write turns out wrong; use episode_revert only to scrap a whole episode."
    :inputSchema {:type "object"
                  :properties {:deltas {:type "integer"}
                               :to {:type "string"}
                               :prompt {:type "string"}}}}
   {:name "episode_revert"
    :description "Roll back everything YOU changed since your last done (other sessions' forms skipped, reported). To walk back just one write, or a short chain, without losing the rest of the episode, use undo."
    :inputSchema {:type "object"
                  :properties {:prompt {:type "string"}}}}
   
   {:name "ns_rename"
    :description "Rename a WHOLE namespace everywhere (decl, requires, qualified refs). Verified."
    :inputSchema {:type "object"
                  :properties {:old {:type "string"} :new {:type "string"}
                               :prompt {:type "string"}}
                  :required ["old" "new"]}}
   {:name "edit_move_forms"
    :description "Move forms to another namespace, NEW or EXISTING — the general relocation refactor. Callers EVERYWHERE (prod + tests) are rewritten to alias-qualified calls and gain the require; moved defs are publicized (module visibility is the boundary); the target gets only the requires the moved code uses; dependency direction is analyzed (a two-way split refuses — a real cycle). export: true marks moved vars ^:export for a deep target with outside callers. One atomic group, verified."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :forms {:type "array" :items {:type "string"}}
                               :to {:type "string"}
                               :export {:type ["boolean" "string"]
                                        :description "true = world surface; a namespace-prefix string = visible to that subtree only"}
                               :prompt {:type "string"}}
                  :required ["ns" "forms" "to"]}}
   {:name "cleanup"
    :description "Tidy ONE namespace the way the done-point does, and report what tidying cannot fix. APPLIES: normalize every form (conservative, behavior-preserving), reorder definitions above their callers, retire legacy or stale (declare …)s and phantom names. REPORTS :purity — which tier (:pure/:reads/:effects) this namespace's current forms could support and which forms block a stricter one, so module_purity is an informed call rather than a blind assertion that only bites on the next write. You rarely need the APPLY half: the pipeline keeps ordering and declares right from your FIRST write, and done runs the same tidy over what you touched. Reach for it on INGESTED code, when a legacy declare blocks you mid-episode, or to see where a namespace stands before declaring a tier."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :prompt {:type "string"}}
                  :required ["ns"]}}])

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
   {:name "done"
    :description "Close a unit of work: normalize your touched forms, re-verify, record a labeled boundary. Affected tests run in EVERY tier — impacted ^:isolated tests included, automatically. Selection is per form: trace evidence where it exists, the form's own namespace-reach where it does not (a brand-new form runs its slice). You never choose tiers. One thing defers to the milestone gate, riding findings as :isolated-pending: a change whose reach exceeds the cap — something so central it names most of the suite."
    :inputSchema {:type "object"
                  :properties {:label {:type "string"}}}}
   {:name "commit_point"
    :description "Record a MILESTONE — green-gated on the FULL ^:isolated suite (run automatically; no test_run first; force=true records red honestly and skips the gate). The git-projection grain; target=<delta id> marks an earlier spot."
    :inputSchema {:type "object"
                  :properties {:description {:type "string"}
                               :force {:type "boolean"}
                               :target {:type "string"}}
                  :required ["description"]}}
   {:name "test_run"
    :description "SPOT-CHECK specific tests: {ns \"x.y-test\"} or {only [\"x.y-test/some-t\"]}. You do NOT need this before done or commit_point — done runs the affected tests in every tier (impacted ^:isolated included) and the milestone runs the whole isolated suite itself. Whole in-image suite: {all true} (rarely needed). Explicit full external run: {isolated true} — fresh JVM, auto-shards (:parallel N overrides), {affected true} narrows to test nses reaching changes since the last milestone. Red isolated runs return :failing + :all-failing {file [tests]} + :themes."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :only {:type "array" :items {:type "string"}}
                               :all {:type "boolean"}
                               :isolated {:type "boolean"}
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
    :description "Track a non-code file on the files manifest (rides every projected tree)."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"}
                               :content {:type "string"}
                               :prompt {:type "string"}}
                  :required ["path" "content"]}}
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
    :description "Declare (or retract with remove=true) ONE module dependency edge — modules are the first two ns segments (\"logi.parcel\"). Each call is one journaled delta; say WHY in prompt. Adds are cycle-checked. Read the manifest: query_depends {modules true}."
    :inputSchema {:type "object"
                  :properties {:from {:type "string"} :to {:type "string"}
                               :remove {:type "boolean"}
                               :prompt {:type "string"}}
                  :required ["from" "to"]}}
   {:name "module_purity"
    :description "Declare a module's purity TIER for the functional-core gate (D9): :pure (may reach NO effect, incl. an opaque-dep read), :reads (reads ok, no mutation), :effects (unrestricted — the periphery). modules are the first two ns segments; :effects or undeclared = ungated. Say WHY in prompt. Read tiers: query_depends {modules true}."
    :inputSchema {:type "object"
                  :properties {:module {:type "string"}
                               :tier {:type "string"}
                               :prompt {:type "string"}}
                  :required ["module" "tier"]}}
   {:name "deps_add"
    :description "Add an external dependency (hot to the live classpath, no restart). lib like \"org.clojure/data.json\"; version string or full coord map."
    :inputSchema {:type "object"
                  :properties {:lib {:type "string"}
                               :version {:type "string"}
                               :coord {:type "object"}}
                  :required ["lib"]}}
   {:name "deps_remove"
    :description "Remove a dependency (restarts the image)."
    :inputSchema {:type "object"
                  :properties {:lib {:type "string"}}
                  :required ["lib"]}}
   {:name "deps_list"
    :description "The dependency manifest: {lib coord}."
    :inputSchema {:type "object" :properties {}}}
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
    :description "Milestones, newest first (targets plug into query_changes from/to). With a git remote configured, :alignment PROVES whether the slopp branch head is the latest milestone's projection — trust it; no worktree/sqlite cross-checks."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_git"
    :description "This session's git view: the embedded read-only listener URL + the saved external remote."
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
    "help" "deps_list" "file_list" "file_get" "file_history"})

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

(def cheat-sheet
  "slopp cheat-sheet
TURN:    turn_begin {agent, intent: <user's verbatim ask>} FIRST -- writes are
         refused without an open turn; turn_end {agent} when done (red is ok)
ORIENT:  query_project (everything, one call) · query_search {pattern} (the grep)
         query_symbol {ns name} (one form's source) · query_references {ns name}
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
         "done" "commit_point" "deps_add" "deps_remove" "deps_pure"
         "change_signature"]))
