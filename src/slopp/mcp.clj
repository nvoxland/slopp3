(ns slopp.mcp
  "Minimal MCP transport (JSON-RPC 2.0 over stdio) exposing `slopp.api` as tools.
  The pure `handle` dispatch is the core (fully testable with plain maps);
  `serve!`/`-main` are the thin newline-delimited-JSON stdio loop.

  Tool names use underscores (MCP restricts names to [A-Za-z0-9_-]). This is the
  agent-facing surface — everything is form-addressed (ns/name), never file+line."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [slopp.api :as api]
            [slopp.git :as git] [slopp.db :as db] [slopp.sync :as sync] [clojure.edn :as edn]))

(def ^:private protocol-version "2024-11-05")

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
    :description "Read-only REPL eval against the live image (the oracle). Namespaces are pre-loaded; requires are no-ops. Questions OF the code — for questions ABOUT the codebase-as-data, use query_store."
    :inputSchema {:type "object"
                  :properties {:code {:type "string"}}
                  :required ["code"]}}
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
    :inputSchema {:type "object" :properties {}}}])
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
    :description "Small change INSIDE a big form. match = ONE exact subform or pair (a missed/ambiguous match returns :source-now — correct and resend, no read needed); text: true matches raw text (strings/docstrings); OR where: {key value} addresses the unique MAP containing those entries (registry rows by :name — no exact text needed). The replacement may splice several forms."
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
    :description "Extract a unique subform into a new fn (params computed, call site rewritten, verified)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :from {:type "string"}
                               :form {:type "string"} :name {:type "string"}
                               :prompt {:type "string"}}
                  :required ["ns" "from" "form" "name"]}}
   {:name "episode_revert"
    :description "Roll back everything YOU changed since your last done (other sessions' forms skipped, reported)."
    :inputSchema {:type "object"
                  :properties {:prompt {:type "string"}}}}
   {:name "fix_declares"
    :description "Tidy (declare …): reorder defns above callers when safe, delete satisfied declares. Atomic, verified."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :prompt {:type "string"}}
                  :required ["ns"]}}
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
                  :required ["ns" "forms" "to"]}}])
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
    :description "Close a unit of work: normalize your touched forms, re-verify, record a labeled boundary. Affected tests run in EVERY tier — impacted ^:isolated tests included, automatically (a large slice defers to the milestone gate and rides findings as :isolated-pending). You never choose tiers."
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
(def ^:private read-only-tools
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
    "review_scan" "query_store"
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

(def ^:private ^:dynamic *hint*
  "Optional one-line workflow hint, attached to map results (item 3)." nil)

(def ^:private ^:dynamic *spool-session*
  "Bound to the session during tools/call so `text` can spool full
  payloads it trims (the headroom pattern: agents get the gist, the full
  version stays retrievable via query_detail for a short while)." nil)
(def ^:private spool-cap 20)
(defn- spool!
  "Keep `full` retrievable for a while; returns its id. Session-scoped,
  FIFO-capped — short-term memory, not history (that's the store)."
  [session full]
  (swap! session update-in [::spool :n] (fnil inc 0))
  (let [n  (get-in @session [::spool :n])
        id (str "r" n)]
    (swap! session update-in [::spool :entries]
           (fn [es]
             (let [es (assoc (or es {}) id full)]
               (if (> (count es) spool-cap)
                 (dissoc es (str "r" (- n spool-cap)))
                 es))))
    id))
(defn- trim-failure-strings
  "Tool-specific heuristic: test-failure :expected/:actual/:message beyond
  700 chars carry their head + a size marker — the diagnosis-relevant part
  is virtually always at the front."
  [x]
  (if-not (and (map? x) (seq (get-in x [:test :failures])))
    x
    (update-in x [:test :failures]
               (fn [fs]
                 (mapv (fn [f]
                         (reduce (fn [f k]
                                   (let [v (get f k)]
                                     (if (and (string? v) (> (count v) 700))
                                       (assoc f k (str (subs v 0 700)
                                                       " …[+" (- (count v) 700) " chars]"))
                                       f)))
                                 f [:expected :actual :message]))
                       fs)))))
(defn- text! [x]
  (let [x       (cond
                  (and *hint* (map? x) (nil? (:hint x))) (assoc x :hint *hint*)
                  (and *hint* (string? x)) (str x "\n\n[hint] " *hint*)
                  :else x)
        full    (if (string? x) x (pr-str x))
        slimmed (let [t (trim-failure-strings x)]
                  (if (string? t) t (pr-str t)))
        out     (if (and (= full slimmed) (<= (count full) 8000))
                  full
                  (if-let [sess *spool-session*]
                    (let [id   (spool! sess full)
                          body (if (<= (count slimmed) 8000)
                                 slimmed
                                 (subs slimmed 0 8000))]
                      (str body "\n[trimmed — query_detail {:id \"" id
                           "\"} returns the full response]"))
                    (if (<= (count slimmed) 8000) slimmed full)))]
    {:content [{:type "text" :text out}]}))

(def ^:private single-write-tools #{"edit_replace_form" "edit_add_form"})

(def ^:private write-tools
  (into single-write-tools
        ["edit_delete_form" "edit_rename" "edit_extract"
         "edit_move" "ns_add_require" "ns_remove_require" "ns_create"
         "done" "commit_point" "deps_add" "deps_remove" "deps_pure"
         "change_signature"]))

(defn- bump-smell-counts
  "Fold one tool call into the smell counters (pure). :done-now is set
  only on the done/commit_point call itself (true when a test_run
  preceded it with no intervening write — the redundant pre-flight);
  every other call clears it."
  [c tool args]
  (let [c (-> (merge {:test-runs 0 :history 0 :dumps 0 :renames 0 :searches 0} c)
              (dissoc :done-now))]
    (cond
      (= tool "query_search")
      (update c :searches inc)

      (= tool "test_run")
      ;; the ISOLATED suite before a milestone is the documented gate —
      ;; only in-image runs count toward the redundant-pre-flight smell
      (-> c (update :test-runs (if (:isolated args) identity inc))
          (assoc :searches 0))

      (#{"query_history" "query_search_history" "query_changes"} tool)
      (update c :history inc)

      (and (= tool "query_source") (:full args))
      (update c :dumps inc)

      (= tool "edit_rename")
      (-> c (update :renames inc) (assoc :test-runs 0))

      (#{"done" "commit_point"} tool)
      (assoc c :done-now (pos? (:test-runs c 0)) :test-runs 0)

      (write-tools tool)
      (assoc c :test-runs 0)

      :else (assoc c :searches 0))))
(def ^:private smell-registry
  "Deterministic bad-usage smells → one-line redirections naming the better
  tool. EXPAND HERE as new smells surface (dogfooding is the source): each
  entry is [key fires?-pred msg] over the bump-smell-counts map — one
  entry, no plumbing. Fire policy (once per session + 30-min per-store
  cooldown) lives in track-hint!; messages are suggestions, never refusals."
  [[:test-runs #(>= (:test-runs %) 3)
    "every write already verifies (its result includes :test) — test_run is rarely needed"]
   [:pre-done-test #(:done-now %)
    "done already runs the affected tests for everything you touched — a pre-flight test_run is redundant; mid-episode runs are for spot-checks"]
   [:history #(>= (:history %) 2)
    "stitching history calls — report {since/contains} composes milestones + changes + asks in ONE read"]
   [:dumps #(>= (:dumps %) 2)
    "repeated whole-namespace dumps — query_slice {ns name} gives one form's source + cards for what it reaches; targets [{ns name}] reads named forms"]
   [:renames #(>= (:renames %) 2)
    "several renames — if this is one CONCEPT changing name, rename_sweep {from to} does namespaces + vars + keys + prose in ONE call"]
   [:searches #(>= (:searches %) 3)
    "a search streak — asking a QUESTION instead may be one call: query_depends {on X} (who uses/what reaches), query_slice {ns name} (a neighborhood), report {contains} (history)"]])
(defn- track-hint!
  "Run the smell registry over this call: bump counters, then let the FIRST
  fireable smell speak — `some` skips already-fired ones, so one smell never
  shadows another (the old cond did). Anti-spam by construction: each smell
  fires ONCE per session AND at most once per 30 minutes per STORE (db-meta
  cooldown survives sessions)."
  [session tool args]
  (let [s (::stats (swap! session update ::stats
                          #(bump-smell-counts % tool args)))
        fire! (fn [k msg]
                (when-not (contains? (:fired s) k)
                  (let [conn (:db @session)
                        now  (System/currentTimeMillis)
                        hist (or (when conn
                                   (try (some-> (db/get-meta conn "hint-cooldowns")
                                                edn/read-string)
                                        (catch Exception _ nil)))
                                 {})]
                    (when (or (nil? conn)
                              (< (* 30 60 1000) (- now (get hist k 0))))
                      (swap! session update-in [::stats :fired] (fnil conj #{}) k)
                      (when conn
                        (try (db/set-meta! conn "hint-cooldowns"
                                           (pr-str (assoc hist k now)))
                             (catch Exception _ nil)))
                      msg))))]
    (some (fn [[k pred msg]] (when (pred s) (fire! k msg)))
          smell-registry)))

(def ^:private cheat-sheet
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
RULES:   every write must compile (define callees first; (declare x) for cycles)
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

(defn- red? [t]
  (and t (pos? (+ (:fail t 0) (:error t 0)))))

(defn- summarize
  "B1: a green-and-quiet edit result compresses to a terse shape (the Go
  baseline showed slopp's verbose green responses were the token loser).
  :error, NEW red failure detail, or NEW warnings return the full map — a
  red that carries only :still-red names (episode compression) stays
  TERSE. Source echoes are stripped EVERYWHERE (Q1); :untested is a terse
  FLAG; a zero-test verification says :coverage :none (Q8); the :type
  :summary tag is internal and never rides the wire."
  [r verbose?]
  (let [strip (fn [d] (if (map? d) (dissoc d :source :sources :node) d))
        r     (cond-> r
                (:delta r)        (update :delta strip)
                (seq (:deltas r)) (update :deltas (partial mapv strip)))]
    (if (or verbose? (:error r) (seq (:warnings r))
            (and (red? (:test r)) (seq (:failures (:test r)))))
      (update r :test #(if (map? %) (dissoc % :type) %))
      (let [t (:test r)]
        (cond-> {:ok true}
          (:delta r)    (assoc :delta (get-in r [:delta :id]))
          (:group r)    (assoc :group (:group r))
          (:deltas r)   (assoc :deltas (count (:deltas r)))
          (:renamed r)  (assoc :renamed (:renamed r))
          (:mentions r) (assoc :mentions (:mentions r))
          (:renamed-namespaces r) (assoc :renamed-namespaces (:renamed-namespaces r))
          (:forms r)    (assoc :forms (:forms r))
          (:untested r) (assoc :untested true)
          t             (assoc :test (cond-> {:ran (:test t 0) :pass (:pass t 0)
                                              :status (if (red? t) :red (:status t :green))
                                              :scope (:scope t)}
                                       (:staleness-detected t)  (assoc :staleness-healed true)
                                       (zero? (:test t 0))      (assoc :coverage :none)
                                       (red? t)                 (assoc :fail (+ (:fail t 0) (:error t 0)))
                                       (seq (:still-red t))     (assoc :still-red (:still-red t))
                                       (seq (:went-green t))    (assoc :went-green (:went-green t))))
          (:affected r) (assoc :affected (let [a (:affected r)]
                                           (if (= :all a) :all (count a))))
          (:hint r) (assoc :hint (:hint r))
          (:red-first r) (assoc :red-first (:red-first r))
          (:carried-errors r) (assoc :carried-errors (:carried-errors r))
          (:changed-nses r) (assoc :changed-nses (:changed-nses r))
          (:image-healed r) (assoc :image-healed true)
          (:existing-warnings r) (assoc :existing-warnings (:existing-warnings r)))))))

(defn parse-call-args
  "Tool arguments for the one-shot --call CLI: nil/blank → {}; \"@path\"
  reads the file first; the text parses as JSON or EDN (agents emit both)
  and must yield a map."
  [s]
  (let [s (if (and s (str/starts-with? s "@")) (slurp (subs s 1)) s)]
    (if (str/blank? s)
      {}
      (let [v (or (try (json/parse-string s true) (catch Exception _ nil))
                  (try (edn/read-string s) (catch Exception _ nil)))]
        (if (map? v)
          v
          (throw (ex-info (str "--call args must be a JSON or EDN map (or @file): "
                               s)
                          {})))))))
(defn- absorb-pending-intent!
  "Consume <dir>/.slopp/pending-intent when present. The plugin's prompt
  hook writes {\"session-id\": …, \"prompt\": …} (a bare string is
  accepted as prompt-only). The session ADOPTS the harness session id as
  its identity — unless SLOPP_AGENT pinned one — so every delta of one
  Claude session shares a key and concurrent sessions never merge
  episodes; the prompt is stashed as the next auto-turn's intent."
  [session]
  (when-let [dir (:dir @session)]
    (let [f (io/file dir ".slopp" "pending-intent")]
      (when (.exists f)
        (let [raw (slurp f)]
          (.delete f)
          (let [{:keys [sid prompt]}
                (or (try (let [m (json/parse-string raw true)]
                           (when (map? m)
                             {:sid (:session-id m) :prompt (:prompt m)}))
                         (catch Exception _ nil))
                    {:prompt raw})]
            (when (and sid (not (:env-agent? @session)))
              (swap! session assoc :agent-id sid))
            (when-not (str/blank? (or prompt ""))
              (swap! session assoc :pending-intent prompt :last-intent prompt))))))))
(def ^:private env-handlers!
  "call-tool dispatch \u2014 deps/branches/build/help (Q4: the stable dispatch tail lives in\n  per-group handler maps of (fn [session a sym]); call-tool keeps only the\n  hot query/edit clauses)."
  {"deps_add"
   (fn [session a sym]
     (text! (api/deps-add! session (sym :lib)
                                        (or (:coord a)
                                            (when (:version a)
                                              {:mvn/version (:version a)}))
                                        :agent (:agent a))))
   "deps_remove"
   (fn [session a sym]
     (text! (api/deps-remove! session (sym :lib)
                                           :agent (:agent a))))
   "deps_list"
   (fn [session _a _sym]
     (text! (api/deps-list session)))
   "deps_pure"
   (fn [session a sym]
     (text! (if (false? (:pure a))
                           (api/deps-unpure! session (sym :target) :agent (:agent a))
                           (api/deps-pure! session (sym :target) :agent (:agent a)))))
   "branch_create"
   (fn [session a _sym]
     (text! (api/branch! session (:name a))))
   "branch_switch"
   (fn [session a _sym]
     (text! (api/branch-switch! session (:name a))))
   "branch_merge"
   (fn [session a _sym]
     (text! (api/branch-merge! session (:name a))))
   "branch_delete"
   (fn [session a _sym]
     (text! (api/branch-delete! session (:name a))))
   "query_branches"
   (fn [session _a _sym]
     (text! (api/query-branches session)))
   "restart"
   (fn [session _a _sym]
     (api/restart! session)
     (text! "restarted"))
   "build"
   (fn [session a _sym]
     (text! (api/build! session (:dir a)
                                    :main (some-> (:main a) symbol)
                                    :name (:name a))))
   "help"
   (fn [_session _a _sym]
     (text! cheat-sheet))})
(def ^:private file-handlers!
  "call-tool dispatch \u2014 tracked files + config (Q4: the stable dispatch tail lives in\n  per-group handler maps of (fn [session a sym]); call-tool keeps only the\n  hot query/edit clauses)."
  {"config"
   (fn [session a _sym]
     (text! (api/config! session (:key a) (:value a))))
   "file_put"
   (fn [session a _sym]
     (text! (api/file-put! session (:path a) (:content a)
                                        :prompt (:prompt a) :agent (:agent a))))
   "file_remove"
   (fn [session a _sym]
     (text! (api/file-remove! session (:path a)
                                           :prompt (:prompt a) :agent (:agent a))))
   "file_list"
   (fn [session _a _sym]
     (text! (api/files-list session)))
   "file_get"
   (fn [session a _sym]
     (text! (api/file-get session (:path a) :at (:at a))))
   "file_history"
   (fn [session a _sym]
     (text! (api/file-history! session (:path a))))
   "config_file"
   (fn [session a _sym]
     (text! (api/config-file! session (:path a)
                                           :key (:key a) :value (:value a)
                                           :unset (:unset a) :format (:format a)
                                           :prompt (:prompt a) :agent (:agent a))))
   "module_dep"
   (fn [session a _sym]
     (text! (api/module-dep! session (:from a) (:to a)
                             :remove (:remove a)
                             :prompt (:prompt a) :agent (:agent a))))})
(def ^:private sync-handlers!
  "call-tool dispatch \u2014 git publish/absorb + remotes (Q4: the stable dispatch tail lives in\n  per-group handler maps of (fn [session a sym]); call-tool keeps only the\n  hot query/edit clauses)."
  {"git_push"
   (fn [session a _sym]
     (text! (if-let [dir (:dir @session)]
              (if (.exists (io/file dir ".git"))
                (sync/mirror-push! dir :url (:url a) :token (:token a)
                                   :branches (or (:branches a)
                                                 (some-> (:branch a) vector)
                                                 [(:branch @session "main")]))
                ;; fileless store: publish the projection directly
                (sync/push! dir :url (:url a) :token (:token a)
                            :branch (:branch @session "main")))
              {:error "git_push needs a durable session (a store dir)"})))
   "git_clone"
   (fn [_session a _sym]
     (text! (sync/clone! (:url a) (:dir a)
                                      :token (:token a) :agent (:agent a))))
   "git_pull"
   (fn [session a _sym]
     (text! (if-let [dir (:dir @session)]
              (let [m (sync/mirror-pull! dir :url (:url a) :token (:token a)
                                         :branches (or (:branches a)
                                                       [(:branch @session "main")]))
                    p (when-not (:error m)
                        (try (sync/pull! session :token (:token a)
                                         :agent (:agent a))
                             (catch Exception e {:error (ex-message e)})))]
                (cond-> m p (assoc :absorbed p)))
              {:error "git_pull needs a durable session (a store dir)"})))
   "git_conflicts"
   (fn [session a _sym]
     (text! (if-let [dir (:dir @session)]
                           {:conflicts (sync/conflicts dir)}
                           {:error "git_conflicts needs a durable session"})))
   "git_resolve"
   (fn [session a _sym]
     (text! (if-let [dir (:dir @session)]
                           (sync/resolve! dir (:path a))
                           {:error "git_resolve needs a durable session"})))
   "query_git"
   (fn [session _a _sym]
     (text! (let [ext (when-let [conn (:db @session)]
                           (when-let [r (db/get-meta conn "git-remote")]
                             {:git-remote   r
                              :git-base-sha (db/get-meta conn "git-base-sha")}))]
                       (cond
                         (:git-url @session)
                         (cond-> {:url (:git-url @session)
                                  :remote (str "git remote add slopp " (:git-url @session))
                                  :note (str "milestones (commit_point) are the commits; "
                                             "the local listener is read-only clone/fetch; "
                                             "publish OUT with git_push; "
                                             "wip/<branch> = live un-milestone'd state")}
                           ext (assoc :external ext))

                         ext
                         {:external ext
                          :note "no local listener; git_push publishes to :external"}

                         :else
                         {:error (str "no git listener on this session"
                                      " (ephemeral session, or the port"
                                      " couldn't bind)")}))))
   "query_commits"
   (fn [session _a _sym]
     (text! (let [rows (api/query-commits session)
                  conn (:db @session)
                  al   (when (and conn (:dir @session))
                         (sync/alignment (:dir @session) "."
                                         (str "slopp/" (:branch @session))
                                         rows))]
              (if al
                {:commits rows :alignment al}
                rows))))
   "merge_from"
   (fn [session a _sym]
     (text! (api/merge! session (:dir a))))})
(def ^:private tail-handlers!
  "Every handler-map entry (Q4) \u2014 call-tool checks here first."
  (merge env-handlers! file-handlers! sync-handlers!))
(defn- told!
  "Knowledge-differential reads: the session keeps a hash of every
  cacheable VIEW it has sent; an identical re-read returns a tiny
  :unchanged stub instead of the payload. Re-fetching becomes FREE, so
  agents never carry views in context 'just in case' — the whole
  don't-hoard stance depends on cheap re-asks. Any store change alters
  the payload, so staleness is impossible by construction."
  [session tool a payload]
  (let [k [tool (select-keys a [:ns :name :targets :since :detail :depth
                                :limit :contains :full :at :collapse :format
                                :on :direction])]
        h (hash payload)]
    (if (and (= h (get-in @session [::told k]))
             (< 130 (count (pr-str payload))))
      {:unchanged true
       :view (str tool (when (:ns a) (str " " (:ns a)))
                  (when (:name a) (str "/" (:name a))))
       :note "identical to what this session already received"}
      (do (swap! session assoc-in [::told k] h)
          payload))))
(defn- call-tool [session {:keys [name arguments]}]
  (api/sync-with-journal! session)      ; m5b: absorb other servers' commits
  (absorb-pending-intent! session)
  (when (and (:require-turns? @session)
             (contains? write-tools name)
             ;; done/commit_point CLOSE work; always allowed
             (not (#{"done" "commit_point"} name)))
    (let [ag (or (:agent arguments) (:agent-id @session))]
      (when-not (api/turn-open? session ag)
        (if-let [intent (:pending-intent @session)]
          ;; the plugin's prompt hook captured the user's verbatim ask —
          ;; the turn opens itself (zero-ceremony turns)
          (do (swap! session dissoc :pending-intent)
              (api/turn-begin! session :agent ag :intent intent))
          (throw (ex-info (str "no open turn — call turn_begin {intent: "
                               "<the user's verbatim ask>} first")
                          {}))))))
  (let [a   (assoc arguments :agent (or (:agent arguments)
                                        (:agent-id @session)))
        sym (fn [k]
              (if-let [v (get a k)]
                (symbol v)
                (throw (ex-info (str "missing required argument :"
                                     (clojure.core/name k) " for " name)
                                {}))))]
    (if-let [h (tail-handlers! name)]
      (h session a sym)
      (case name
      "ns_create" (text! (api/create-ns! session (sym :ns)
                                                :requires (:requires a)
                                                :source (:source a)
                                                :agent (:agent a)))
      "ns_add_require" (text! (-> (api/add-require! session (sym :ns) (:require a)
                                                      :prompt (:prompt a))
                                    (select-keys [:error :warnings :existing-warnings
                                                  :test :affected :delta])
                                    (summarize (:verbose a))))
      "query_project" (text! (told! session name a
                                        (api/query-project session :since (:since a)
                                                          :detail (:detail a))))
      "query_search" (text! (api/query-search session (:pattern a)
                                                  :limit (or (:limit a) 30)))
      "query_source" (text! (told! session name a
                                        (let [full?   (:full a)
                                              gate    (fn [n]
                                                        {:ns n
                                                         :outline (:forms (api/query-outline session n))
                                                         :note (str "outline by default — name the"
                                                                    " forms you need (targets"
                                                                    " [{ns name}]) or pass full:"
                                                                    " true for the whole namespace")})]
                                          (if-let [ts (:targets a)]
                                            (mapv (fn [t]
                                                    (if (or full? (:name t))
                                                      (first (api/query-sources
                                                              session
                                                              [(cond-> {:ns (symbol (:ns t))}
                                                                 (:name t) (assoc :name (symbol (:name t))))]))
                                                      (gate (symbol (:ns t)))))
                                                  ts)
                                            (if full?
                                              (api/query-source session (sym :ns))
                                              (gate (sym :ns)))))))
      "query_detail" (if-let [full (get-in @session [::spool :entries (:id a)])]
                            ;; the retrieval path must NOT re-trim its own payload
                            {:content [{:type "text" :text full}]}
                            (text! {:error (str "no spooled response " (:id a)
                                                " — the spool keeps the last "
                                                spool-cap " trimmed responses")}))
      "query_brief" (text! (told! session name a (api/query-brief session (sym :ns) (sym :name))))
      "query_slice" (text! (told! session name a
                                        (api/query-slice session (sym :ns) (sym :name)
                                                        :depth (or (:depth a) 2)
                                                        :limit (or (:limit a) 8)
                                                        :match (:match a)
                                                        :window (:window a))))
      "query_depends" (text! (told! session name a
                                        (api/query-depends session (:on a)
                                                          :modules (:modules a)
                                                          :direction (if (= "dependencies" (:direction a))
                                                                       :dependencies :dependents))))
      "session_brief" (text! (let [b    (api/session-brief session)
                                       conn (:db @session)
                                       al   (when (and conn (:dir @session))
                                              (sync/alignment
                                               (:dir @session) "."
                                               (str "slopp/" (:branch @session))
                                               (api/query-commits session)))]
                                   (told! session name a (cond-> b al (assoc :alignment al)))))
      "review_scan" (text! (told! session name a
                                            (api/review-scan session
                                                             :ns (:ns a)
                                                             :limit (or (:limit a) 25))))
      "report" (text! (let [r    (api/report session
                                                       :since (:since a)
                                                       :contains (:contains a)
                                                       :limit (or (:limit a) 50))
                                       conn (:db @session)
                                       al   (when (and conn (:dir @session))
                                              (sync/alignment
                                               (:dir @session) "."
                                               (str "slopp/" (:branch @session))
                                               (api/query-commits session)))]
                                   (cond-> r al (assoc :alignment al))))
      "draft_test" (text! (api/draft-test session (sym :ns) (sym :name)
                                                :code (:code a)
                                                :limit (or (:limit a) 5)))
      "turn_begin" (text! (api/turn-begin! session :agent (:agent a)
                                                 :intent (:intent a)
                                                 :user (:user a)))
      "turn_end" (text! (api/turn-end! session :agent (:agent a)
                                               :note (:note a)))
      "query_changes" (text! (api/query-changes session :agent (:agent a)
                                                   :from (:from a) :to (:to a)
                                                   :format (:format a)))
      "episode_revert" (text! (-> (api/revert-episode! session
                                                         :agent (:agent a)
                                                         :prompt (:prompt a))
                                    (select-keys [:error :conflict :reverted
                                                  :skipped-shared :note :test
                                                  :group :affected])
                                    (summarize (:verbose a))))
      "query_history" (text! (told! session name a
                                        (let [nm (:name a)]
                                          (cond
                                            (and nm (:at a))
                                            (assoc (api/query-form-at session (sym :ns) (sym :name)
                                                                     :at (:at a))
                                                   :kind :form-at)

                                            nm
                                            {:kind :form-history
                                             :versions (api/query-form-history session (sym :ns) (sym :name)
                                                                              :format (:format a))}

                                            (:at a)
                                            (assoc (api/query-status-at session :at (:at a))
                                                   :kind :status-at)

                                            (:contains a)
                                            {:kind :prompts
                                             :hits (api/query-search-history session (:contains a)
                                                                            :limit (:limit a))}

                                            :else
                                            (api/query-history session
                                                              :ns (some-> (:ns a) symbol)
                                                              :collapse (:collapse a)
                                                              :format (:format a)
                                                              :limit (or (:limit a) 20))))))
      "query_eval" (text! (api/query-eval session (:code a)))
      "query_store" (text! (told! session name a
                                  (api/query-store session (:code a)
                                                   :timeout-ms (or (:timeout_ms a) 10000))))
      "query_observe" (text! (let [r (api/query-observe session (sym :ns) (sym :name)
                                                          (:code a)
                                                          :limit (or (:limit a) 10))]
                                 (api/remember-observation! session (sym :ns) (sym :name) r)
                                 r))
      "query_macroexpand" (text! (api/query-macroexpand session (:code a)))
      "edit_replace_form" (text! (-> (api/edit-replace! session (sym :ns) (sym :name)
                                                       (:source a) :prompt (:prompt a)
                                                       :agent (:agent a))
                                    (assoc :forms [(str (sym :ns) "/" (sym :name))])
                                    (select-keys [:error :warnings :existing-warnings :hint :forms
                                                  :untested :image-healed :test :affected :delta
                                                  :red-first :carried-errors :note])
                                    (summarize (:verbose a))))
      "edit_add_form" (text! (-> (api/add-form! session (sym :ns) (:source a)
                                                   :prompt (:prompt a)
                                                   :agent (:agent a)
                                                   :before (some-> (:before a) symbol))
                                    (select-keys [:error :warnings :existing-warnings :hint
                                                  :untested :image-healed :test :affected :delta
                                                  :red-first :carried-errors :note])
                                    (summarize (:verbose a))))
      "edit_delete_form" (text! (-> (api/delete-form! session (sym :ns) (sym :name)
                                                      :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys [:error :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_rename" (let [old (or (:old a) (:name a) (:from a))
                                new (or (:new a) (:to a))]
                            (when-not (and old new)
                              (throw (ex-info "edit_rename needs :old and :new (aliases: :name/:from, :to)" {})))
                            (text! (-> (api/rename! session (sym :ns) (symbol old)
                                                   (symbol new) :prompt (:prompt a)
                                                   :agent (:agent a))
                                      (select-keys [:error :renamed :test :affected :delta
                                                    :mentions :hint])
                                      (summarize (:verbose a)))))
      "ns_remove_require" (text! (-> (api/remove-require! session (sym :ns) (sym :lib)
                                                         :prompt (:prompt a))
                                    (select-keys [:error :test :affected :delta])
                                    (summarize (:verbose a))))
      "rename_sweep" (let [{:keys [from to]} a]
                            (when-not (and from to)
                              (throw (ex-info "rename_sweep needs :from and :to (plain words/segments)" {})))
                            (text! (-> (api/rename-sweep! session from to
                                                         :prompt (:prompt a)
                                                         :agent (:agent a))
                                      (select-keys [:error :source-now :renamed-namespaces :forms
                                                    :group :warnings :existing-warnings
                                                    :changed-nses :test :affected :deltas])
                                      (summarize (:verbose a)))))
      "edit_subform" (let [match (or (:match a) (:from a))
                                src   (or (:source a) (:to a))]
                            (when-not (and (or match (:where a)) src)
                              (throw (ex-info "edit_subform needs :match (exact subform source) OR :where {key value} (the unique map containing it), plus :source" {})))
                            (text! (-> (api/edit-subform! session (sym :ns)
                                            (symbol (or (:form a) (:name a)
                                                        (throw (ex-info "edit_subform needs :form (the containing form's name; :name works too)" {}))))
                                                         match src
                                                         :text (:text a)
                                                         :where (:where a)
                                                         :prompt (:prompt a)
                                                         :agent (:agent a))
                                      (select-keys [:error :source-now :suggestion :conflict
                                                    :warnings :existing-warnings
                                                    :untested :image-healed :test :affected :delta :ms])
                                      (summarize (:verbose a)))))
      "edit_revert" (text! (-> (api/revert-form! session (sym :ns) (sym :name)
                                                      :to (:to a) :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys [:error :conflict :warnings :test
                                                  :affected :delta :ms])
                                    (summarize (:verbose a))))
      "edit_move" (text! (api/move-form! session (sym :ns) (sym :name)
                                                :before (sym :before)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
      "edit_trivia" (text! (api/edit-trivia! session (sym :ns)
                                                  (some-> (:before a) symbol)
                                                  (:text a)
                                                  :prompt (:prompt a)
                                                  :agent (:agent a)))
      "edit_extract" (let [subform (or (:form a) (:source a) (:subform a))]
                            (when-not subform
                              (throw (ex-info "edit_extract needs :form (the exact subform source; aliases :source/:subform accepted)" {})))
                            (text! (-> (api/extract! session (sym :ns) (sym :from)
                                                    (sym :name) subform
                                                    :prompt (:prompt a))
                                      (select-keys [:error :extracted :group :test :affected])
                                      (summarize (:verbose a)))))
      "done" (text! (api/done! session :label (:label a)
                                                  :agent (:agent a)))
      "commit_point" (text! (let [r (api/commit-point! session (:description a)
                                                       :agent (:agent a)
                                                       :force (:force a)
                                                       :target (:target a))]
                                    ;; Q10: the mechanical series is the system's job —
                                    ;; a green milestone on a git-configured store
                                    ;; publishes itself; publish trouble rides along
                                    ;; without failing the milestone
                                    (if (and (:commit r) (not= :red (:status r))
                                             (:dir @session))
                                      (if-let [p (try (sync/publish-local!
                                                       (:dir @session)
                                                       (:branch @session))
                                                      (catch Exception e
                                                        {:error (ex-message e)}))]
                                        (assoc r :published
                                               (select-keys p [:pushed :branch
                                                               :error :status]))
                                        r)
                                      r)))
      "test_run" (text!
                       (cond
                         (:isolated a)
                         (api/isolated-test-run! session
                                                 :ns (some-> (:ns a) symbol)
                                                 :affected (:affected a)
                                                 :parallel (some-> (:parallel a) str parse-long)
                                                 :only (some->> (:only a) (mapv symbol)))
                         ;; surgical spot-checks name a target; bare test_run is
                         ;; almost always the redundant "confirm everything" the
                         ;; done-point already does — make ALL explicit, and teach
                         (or (:ns a) (seq (:only a)))
                         (api/test-run! session
                                        (when (:ns a) (sym :ns))
                                        :only (some->> (:only a) (mapv symbol))
                                        :fresh (:fresh a))

                         (:all a)
                         (assoc (api/test-run! session nil :fresh (:fresh a))
                                :note (str "done runs the affected tests for everything"
                                           " you touched — a whole-suite in-image run is"
                                           " rarely needed mid-episode; the merge gate is"
                                           " test_run {isolated true}"))

                         :else
                         {:guidance (str "name what to spot-check: test_run {ns \"x.y-test\"}"
                                         " or {only [\"x.y-test/some-t\"]}. You do NOT need to"
                                         " run tests before done — done runs the affected"
                                         " tests itself. Whole suite in-image: {all true};"
                                         " external merge gate: {isolated true}.")}))
      "fix_declares" (text! (api/fix-declares! session (sym :ns)
                                                   :prompt (:prompt a)
                                                   :agent (:agent a)))
      "ns_rename" (text! (api/ns-rename! session (:old a) (:new a)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
      "edit_move_forms" (text! (-> (api/move-forms! session (sym :ns)
                                                     (mapv symbol (:forms a))
                                                     (symbol (:to a))
                                                     :export (:export a)
                                                     :prompt (:prompt a)
                                                     :agent (:agent a))
                                    (select-keys [:error :conflict :moved-to
                                                  :moved :rewrote :callers
                                                  :test :group])
                                    (summarize (:verbose a))))
      "change_signature" (text! (-> (api/change-signature! session (sym :ns)
                                                           (sym :name)
                                                           (:source a) (:calls a)
                                                           :prompt (:prompt a)
                                                           :agent (:agent a))
                                    (select-keys [:error :step :group :rewrote :manual
                                                  :warnings :existing-warnings :changed-nses
                                                  :image-healed :test :affected :deltas])
                                    (summarize (:verbose a))))
      (throw (ex-info (str "unknown tool: " name ". Available: "
                           (str/join ", " (map :name tools)))
                      {}))))))

(defn call!
  "One-shot tool invocation against the store at `dir` — the --call CLI's
  engine and the fallback when no MCP connection exists. Opens a durable
  session, dispatches ONE tool call, closes. Returns the wire result map
  ({:content [{:text …}]}; :isError true on tool errors), same as the
  server would send."
  [dir tool arguments]
  (let [session (api/open! {:dir (str dir)})]
    (swap! session assoc :require-turns? true)
    (try
      (try (call-tool session {:name tool :arguments arguments})
           (catch Exception e
             (assoc (text! (str "error: " (ex-message e))) :isError true)))
      (finally (api/close! session)))))
^:unsafe
(defn ^:unused-ok call-main!
  "CLI entry for boot's --call sugar (or --main slopp.mcp/call-main):
  <dir> <tool> [args] — one tool call, result text on stdout, exit 1 on a
  tool error. args is JSON, EDN, or @file (parse-call-args)."
  [& [dir tool args-str]]
  (when (str/blank? tool)
    (binding [*out* *err*]
      (println "usage: --call <tool> [<json/edn args or @file>]"))
    (System/exit 2))
  (let [r (call! (or dir ".") tool (parse-call-args args-str))]
    (println (clojure.string/join "\n" (map :text (:content r))))
    (flush)
    (System/exit (if (:isError r) 1 0))))
^:unsafe (defn handle
  "Dispatch a JSON-RPC request map; return a response map, or nil for
  notifications. Tool exceptions become an `isError` result (so the agent sees
  the message); protocol errors become JSON-RPC errors."
  [session {:keys [id method params]}]
  (case method
    "initialize" {:jsonrpc "2.0" :id id
                  :result {:protocolVersion protocol-version
                           :capabilities {:tools {:listChanged true}}
                           :serverInfo {:name "slopp" :version "0.1.0"}}}
    "notifications/initialized" nil
    "tools/list" (do (swap! session assoc :slopp.mcp/tools-hash (hash tools))
                     {:jsonrpc "2.0" :id id :result {:tools tools}})
    "tools/call" {:jsonrpc "2.0" :id id
                  :result (binding [*hint* (track-hint! session
                                                        (:name params)
                                                        (:arguments params))
                                    *spool-session* session]
                            (try (call-tool session params)
                                 (catch Exception e
                                   (assoc (text! (str "error: " (ex-message e)))
                                          :isError true))))}
    "ping" {:jsonrpc "2.0" :id id :result {}}
    (when id
      {:jsonrpc "2.0" :id id
       :error {:code -32601 :message (str "method not found: " method)}})))

(defn- tools-note!
  "The notifications/tools/list_changed message when the tool registry has
  DRIFTED from what this session last advertised (a live reload renamed or
  added a tool — edit_move_forms replaced edit_extract_ns mid-session and no
  client could see it), else nil. Emitting updates the baseline, so each
  drift notifies exactly once. No baseline (tools/list never served) → nil."
  [session]
  (let [h    (hash tools)
        last (:slopp.mcp/tools-hash @session)]
    (when (and last (not= last h))
      (swap! session assoc :slopp.mcp/tools-hash h)
      {:jsonrpc "2.0" :method "notifications/tools/list_changed"})))
(defn serve!
  "Newline-delimited-JSON stdio loop over `in-reader`/`out-writer`."
  [session in-reader out-writer]
  (doseq [line (line-seq in-reader) :when (not (str/blank? line))]
    (when-let [resp (handle session (json/parse-string line true))]
      (.write out-writer (str (json/generate-string resp) "\n"))
      (.flush out-writer))
    ;; a live reload may have changed the tool registry — tell the client
    ;; to re-list (ordered: same writer, right after the response)
    (when-let [note (tools-note! session)]
      (.write out-writer (str (json/generate-string note) "\n"))
      (.flush out-writer)))
  nil)

^:unsafe
(defn -main
  "Start the stdio MCP server. An optional `dir` argument makes the session
  durable (store at <dir>/.slopp/store.db); without it the session is
  ephemeral. Serving a git checkout that carries a slopp BRANCH with an
  absent/empty store AUTO-IMPORTS it first (zero-ceremony onboarding).
  A durable session ALSO opens an in-process git smart-HTTP listener on a
  dir-derived port (localhost) — a READ-ONLY remote (clone/fetch of
  milestones) any git client can point at with no external daemon;
  `query_git` reports the URL. Publishing to a NORMAL external remote
  (GitHub etc.) goes through `git_push`; `git_clone` rebuilds a fileless
  store from one (slopp.sync)."
  [& [dir]]
  (when dir
    (when-let [r (sync/maybe-auto-import! dir)]
      (binding [*out* *err*]
        (println (str "slopp: auto-imported " (:namespaces r)
                      " namespaces from the repo's slopp branch")))))
  (let [session (api/open! (cond-> {:warm-spare? true}
                             dir (assoc :dir dir)))]
    (swap! session assoc :require-turns? true)   ; real servers enforce turns
    (when dir
      (try
        (let [srv (git/start-server! (git/derived-port dir) {:dir dir})]
          (swap! session assoc :git-server srv :git-url (:url srv))
          ;; stdout is the JSON-RPC channel; banner goes to stderr
          (binding [*out* *err*]
            (println (str "slopp git remote: " (:url srv)))))
        (catch Throwable t                       ; git is optional; MCP must serve
          (binding [*out* *err*]
            (println (str "slopp git remote unavailable: " (.getMessage t)))))))
    (try
      (serve! session (io/reader System/in) (io/writer System/out))
      (finally
        (when-let [srv (:git-server @session)] (git/stop-server! srv))
        (api/close! session)))))
