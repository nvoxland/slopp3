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
            [slopp.git :as git] [slopp.db :as db] [slopp.sync :as sync]))

(def ^:private protocol-version "2024-11-05")

(def tools
  [{:name "ns_create"
    :description "Bring a BRAND-NEW namespace into being (cannot overwrite an existing one — edit its forms instead). TWO modes: pass `requires` (clause strings like \"[clojure.string :as str]\") to scaffold an empty namespace you then grow form-by-form with red-first TDD — the default for new behavior; OR pass `source` (the whole namespace text, including its own (ns …) form) to land the entire namespace in one verified call (forward refs resolve as a unit, like a real .clj load) — for ported/reference/data code not subject to red→green. `requires` and `source` are mutually exclusive."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :requires {:type "array" :items {:type "string"}}
                               :source {:type "string"}
                               :agent {:type "string"}}
                  :required ["ns"]}}
   {:name "ns_add_require"
    :description "Add one require clause (e.g. \"[clojure.string :as str]\") to a namespace's ns form (tracked, hot-reloaded)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :require {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "require"]}}
   {:name "ns_remove_require"
    :description "Remove a library's require spec from a namespace's ns form (tracked, hot-reloaded)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :lib {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "lib"]}}
   {:name "edit_move"
    :description "Move a form to just before another form in its namespace — use when a definition must precede its (already-added) caller."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :before {:type "string"} :prompt {:type "string"} :agent {:type "string"}}
                  :required ["ns" "name" "before"]}}
   {:name "edit_trivia"
    :description "Replace the ENTIRE comment/blank-line run immediately before form `before` (omit before = the namespace tail) with `text` — how top-level comments and section banners are edited, added, or deleted (empty text = a bare newline). Text is trivia only (a code form is refused) and is normalized to start/end with a newline. Forms are untouched."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :before {:type "string"}
                               :text {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}}
                  :required ["ns" "text"]}}
   {:name "query_project"
    :description "THE orientation call: every namespace with its full outline (names, arities, doc lines, !-status, test-ness) in one response. Start here."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_search"
    :description "Regex search across all store source; form-addressed hits [{:ns :form :line}]. Search before reading source."
    :inputSchema {:type "object"
                  :properties {:pattern {:type "string"}
                               :limit {:type "integer"}}
                  :required ["pattern"]}}
   {:name "query_namespaces"
    :description "List every namespace in the store with its form count (orient here first)."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_outline"
    :description "A namespace's shape at a glance: vars with arities, doc line, !-effect status, test-ness. Far cheaper than query_source."
    :inputSchema {:type "object" :properties {:ns {:type "string"}} :required ["ns"]}}
   {:name "query_source"
    :description "Render a namespace's current source from the store (VFS read)."
    :inputSchema {:type "object" :properties {:ns {:type "string"}} :required ["ns"]}}
   {:name "query_symbol"
    :description "Describe a form: id, name, effectfulness (!), source."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "query_references"
    :description "Who references ns/name."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "query_lineage"
    :description "Provenance chain (deltas: op + prompt) for a form."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "query_history"
    :description "The change history, newest first. Default: raw deltas (op, prompt, label; filters ns/contains/limit). Pass collapse=true for EPISODE rows — one per agent-work-unit between checkpoints, the readable long-term view."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :contains {:type "string"}
                               :limit {:type "integer"}
                               :collapse {:type "boolean"}
                               :format {:type "string" :enum ["edn" "text"]}}}}
   {:name "query_form_history"
    :description "Every content version of a form, oldest first, with the prompt that produced it, when, and the verification state it landed in (:status). format=\"text\" renders the form's LIFE as a per-version line-diff story."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :format {:type "string" :enum ["text"]}}
                  :required ["ns" "name"]}}
   {:name "query_form_at"
    :description "TIME-TRAVEL: a form's source exactly as it stood at a past point. :at is a delta id OR a commit-point id (resolves to that milestone's state). Names resolve as of that delta (a later-renamed form still answers to its old name). Returns {:source :status (was-green-at) :at} or {:error}."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :at {:type "string"}}
                  :required ["ns" "name" "at"]}}
   {:name "query_status_at"
    :description "WAS-GREEN-AT: the project's verification state (:green/:red/:unknown) that governed a past point — the last verify at or before :at (a delta id OR a commit-point id). Returns {:at :status :verify <delta>} or {:error}."
    :inputSchema {:type "object"
                  :properties {:at {:type "string"}}
                  :required ["at"]}}
   {:name "query_search_history"
    :description "DELTA-LOG SEARCH ('which prompts touched auth?'): case-insensitive substring match of :contains against every delta's prompt, checkpoint label, commit/turn description, and enclosing turn intent, newest-first. Each hit carries the forms it touched (ns/name) + human time — drill in with query_form_at / query_lineage."
    :inputSchema {:type "object"
                  :properties {:contains {:type "string"}
                               :limit {:type "integer"}}
                  :required ["contains"]}}
   {:name "query_eval"
    :description "Read-only eval against the live image (the oracle); never edits code. Namespaces are already loaded in the image (no source files) — just call fns; a bare `require` is a no-op and `:reload`/`:reload-all` are ignored."
    :inputSchema {:type "object" :properties {:code {:type "string"}} :required ["code"]}}
   {:name "query_observe"
    :description "Run driver code while capturing the args and return value of calls to ns/name — 'what actually flows through this function?'"
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :code {:type "string"}
                               :limit {:type "integer"}}
                  :required ["ns" "name" "code"]}}
   {:name "query_macroexpand"
    :description "Show a form's macroexpansion (expand-1 and full)."
    :inputSchema {:type "object" :properties {:code {:type "string"}}
                  :required ["code"]}}
   {:name "edit_replace_form"
    :description "Replace a whole top-level form (tracked delta, hot-reload, verify)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :source {:type "string"} :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name" "source"]}}
   {:name "edit_add_form"
    :description "Add a new top-level form to a namespace (tracked delta, hot-reload, verify). Default position: the tail. Pass before=<form-name> to insert immediately before that form — define callees before their callers in one step."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :source {:type "string"}
                               :before {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "source"]}}
   {:name "edit_delete_form"
    :description "Delete a top-level form from a namespace (tracked delta, ns-unmap, verify)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "edit_subform"
    :description "Replace ONE subexpression inside a form (give the exact subform source as `match` and its replacement as `source`) — for small changes inside big forms; never re-transcribe the rest. Wrap = a replacement containing the match. SPLICE is guaranteed: the replacement may be SEVERAL forms (they land in the match's place — how you insert into a big vector or case). The match must parse to exactly ONE form (multi-form matches are refused). Matching is structural OR whitespace-insensitive-textual, so fn literals #(...) and regexes match. text=true switches to RAW-TEXT matching (unique occurrence, result must stay one form) — the escape hatch for string literals and docstrings."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :form {:type "string"}
                               :match {:type "string"} :source {:type "string"}
                               :text {:type "boolean"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "form" "match" "source"]}}
   {:name "edit_revert"
    :description "Revert a form to an earlier version of itself (default: previous; or a specific delta id from query_form_history). Verified and recorded like any write."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :to {:type "string"} :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "edit_group"
    :description "Apply several form writes as ONE atomic intent: all-or-nothing commit, one verification at the end. Use for multi-form refactors. Steps: replace/add/delete/move — add takes optional before=<form-name> (insert anchored, not at the tail); move needs name + before (batch reordering rides the same atomic commit)."
    :inputSchema {:type "object"
                  :properties {:steps {:type "array"
                                       :items {:type "object"
                                               :properties {:action {:type "string" :enum ["replace" "add" "delete" "move"]}
                                                            :ns {:type "string"}
                                                            :name {:type "string"}
                                                            :source {:type "string"}
                                                            :before {:type "string"}}
                                               :required ["action" "ns"]}}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["steps"]}}
   {:name "edit_rename"
    :description "Rename a form and every reference to it, across namespaces (one coordinated delta; shadow-safe)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :old {:type "string"}
                               :new {:type "string"} :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "old" "new"]}}
   {:name "edit_extract"
    :description "Extract a unique subform of a function into a new function (free locals become params; placed before the caller; the subform becomes the call). One atomic, verified intent."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :from {:type "string"}
                               :form {:type "string"} :name {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}}
                  :required ["ns" "from" "form" "name"]}}
   {:name "turn_begin"
    :description "Open your TURN: record the user's VERBATIM ask as the root intent of everything you do until turn_end. Required before any write when the server enforces turns. Pass your agent label."
    :inputSchema {:type "object"
                  :properties {:agent {:type "string"} :intent {:type "string"}
                               :user {:type "string"}}
                  :required ["agent" "intent"]}}
   {:name "turn_end"
    :description "Close your turn (stable or not — a red turn is still history). Sub-agents don't call this; they ride your turn."
    :inputSchema {:type "object"
                  :properties {:agent {:type "string"} :note {:type "string"}}
                  :required ["agent"]}}
   {:name "query_changes"
    :description "Net per-form diffs (:was/:now), steps, and the red/green verification arc — for YOUR open episode (pass :agent), or for ANY PAST span: pass :from/:to delta ids straight from a collapsed history row (drill-down). format=text renders line diffs for humans."
    :inputSchema {:type "object"
                  :properties {:agent {:type "string"}
                               :from {:type "string"} :to {:type "string"}
                               :format {:type "string" :enum ["edn" "text"]}}}}
   {:name "episode_revert"
    :description "Scrap your episode: roll every form you changed since your last checkpoint back to that stable spot, as ONE atomic verified group. Forms other agents also touched are skipped and reported in :skipped-shared, never stomped."
    :inputSchema {:type "object"
                  :properties {:agent {:type "string"} :prompt {:type "string"}}}}
   {:name "checkpoint"
    :description "Mark a unit of work done and CLOSE your episode: deterministically normalize the forms YOU changed since your last checkpoint (tracked :normalize delta, re-verified), record a labeled boundary. Pass your :agent label so parallel agents' checkpoints stay independent."
    :inputSchema {:type "object" :properties {:label {:type "string"}
                                              :agent {:type "string"}}}}
   {:name "commit_point"
    :description "Record a MILESTONE: runs the full checkpoint pipeline, then marks this spot in the branch's history with a human description (the important-checkpoint grain above turns). GREEN-GATED: refused while tests are red unless force=true (which records status :red honestly). Pass target=<past delta id> to retroactively mark an earlier spot."
    :inputSchema {:type "object"
                  :properties {:description {:type "string"}
                               :agent {:type "string"}
                               :force {:type "boolean"}
                               :target {:type "string"}}
                  :required ["description"]}}
   {:name "query_commits"
    :description "Milestones, newest first: description, status, human time, and target delta id (plug targets into query_changes from/to for a between-milestones diff). Rows carry :sha — the milestone's git commit id — once the git projection has minted it."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_git"
    :description "The LOCAL git view of this session's store: the embedded read-only listener's URL (durable sessions only) — `git remote add slopp <url>`, then clone/fetch: milestones (commit_point) are the commits, wip/<branch> mirrors un-milestone'd live state. Plus the saved external remote (git-remote/git-base-sha meta) when this store pushes to or was cloned from one. Edits arrive through slopp's write tools; publishing goes OUT via git_push."
    :inputSchema {:type "object" :properties {}}}
   {:name "git_push"
    :description "Push this store's projection — the milestone history as real .clj files + a generated deps.edn — to a normal git remote (GitHub or any bare repo). Pass url once (saved as git-remote; later calls reuse it). Fast-forward only: a diverged remote is an honest error, never a force. A cloned store grafts onto the remote's history, so its pushes fast-forward too. Auth for https: token param, else SLOPP_GIT_TOKEN/GIT_TOKEN env."
    :inputSchema {:type "object"
                  :properties {:url {:type "string"}
                               :token {:type "string"}
                               :branch {:type "string"}}}}
   {:name "git_clone"
    :description "Clone a git remote into dir as a FILELESS slopp store: every src/test namespace is ingested (verified) into <dir>/.slopp/store.db — NO .clj files are materialized locally. Records git-remote + git-base-sha so a later git_push from that store fast-forwards onto the remote's history. dir must not already hold a store. Non-source files on the remote are ignored; a .clj that fails slopp's gates fails the clone with the reason."
    :inputSchema {:type "object"
                  :properties {:url {:type "string"}
                               :dir {:type "string"}
                               :token {:type "string"}}
                  :required ["url" "dir"]}}
   {:name "git_pull"
    :description "Absorb the remote's changes since the last common point: a 3-way merge at FORM granularity — the remote wins wherever this store is clean; anything both sides touched becomes a CONFLICT (our version stays live; the remote file is quarantined off-log; git_push is blocked until resolved). Whole-file deletions and comment-only changes are surfaced, never silently applied. Records the remote tip as the new chain point so later pushes fast-forward. Needs a durable session with a saved git-remote (from git_push or a clone)."
    :inputSchema {:type "object"
                  :properties {:token {:type "string"}
                               :agent {:type "string"}}}}
   {:name "git_conflicts"
    :description "Unresolved git-pull conflicts: path, namespace, reason, and the RAW remote file content to merge from. Resolve by applying/adapting that content through the edit tools, then git_resolve the path."
    :inputSchema {:type "object" :properties {}}}
   {:name "git_resolve"
    :description "Mark a git-pull conflict resolved (after merging the remote content through the edit tools — or deciding against it). Omit path to clear ALL. Unblocks git_push."
    :inputSchema {:type "object" :properties {:path {:type "string"}}}}
   {:name "config"
    :description "Read or set store config: user.name / user.email — the git author identity milestone commits are stamped with (captured on the marker at commit_point time). Unset, or the value \"<git>\", defers to `git config <key>` in the project dir. Omit value to read (shows :configured and the :effective resolution)."
    :inputSchema {:type "object"
                  :properties {:key {:type "string"}
                               :value {:type "string"}}
                  :required ["key"]}}
   {:name "file_put"
    :description "Track a NON-CODE file (README, .github workflows, LICENSE) on the store's files manifest — it rides every projected tree, so git_push never deletes it from the remote. Content is the full file text."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"}
                               :content {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}}
                  :required ["path" "content"]}}
   {:name "file_remove"
    :description "Drop a path from the files manifest (it disappears from the next pushed tree)."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}}
                  :required ["path"]}}
   {:name "file_list"
    :description "The files manifest: {path byte-count}."
    :inputSchema {:type "object" :properties {}}}
   {:name "file_get"
    :description "A manifest file's content — current, or as of a past delta/commit-point via at (time travel, like query_form_at)."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"} :at {:type "string"}}
                  :required ["path"]}}
   {:name "file_history"
    :description "Every tracked version of a manifest file, oldest first, with provenance (delta, op, agent, prompt, time, bytes) — query_form_history for non-code files."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"}}
                  :required ["path"]}}
   {:name "deps_add"
    :description "Declare an external library dependency for THIS store (Tier 1). It reaches the live image's classpath immediately (hot add-libs, no restart) and the generated deps.edn, so store code can require it. lib is a symbol like \"org.clojure/data.json\"; give version (\"2.5.0\" → {:mvn/version ...}) OR a full coord map. Records a tracked :deps-add delta."
    :inputSchema {:type "object"
                  :properties {:lib {:type "string"}
                               :version {:type "string"}
                               :coord {:type "object"}
                               :agent {:type "string"}}
                  :required ["lib"]}}
   {:name "deps_remove"
    :description "Drop an external dependency from this store's manifest (restarts the image — a jar can't be unloaded)."
    :inputSchema {:type "object"
                  :properties {:lib {:type "string"} :agent {:type "string"}}
                  :required ["lib"]}}
   {:name "deps_list"
    :description "This store's external dependency manifest: {lib coord}."
    :inputSchema {:type "object" :properties {}}}
   {:name "deps_pure"
    :description "Assert a dependency is PURE (no effect slopp should track), narrowing the effectful-by-default boundary so callers aren't flagged. `target` lands at three granularities: a fully-qualified var (\"clojure.data.json/write-str\"), a whole namespace (\"clojure.data.json\", every var in it), or a manifest lib (\"org.clojure/data.json\", which expands to every namespace the dep provides — best for a wholesale-pure library like rewrite-clj). Pass pure=false to undo."
    :inputSchema {:type "object"
                  :properties {:target {:type "string"}
                               :pure {:type "boolean"}
                               :agent {:type "string"}}
                  :required ["target"]}}
   {:name "test_run"
    :description "Run tests in the live image and record the result. No :ns = EVERY namespace's tests in one call (the full-project sweep). :only restricts to named tests; :fresh true restarts first for a guaranteed-faithful run. :isolated true instead runs the project's file-based suite in a FRESH EXTERNAL JVM (clojure -M:test) — for tests that spawn their own images/subprocesses and so can't run in the owned image; returns a parsed summary, not the trace map."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :only {:type "array" :items {:type "string"}}
                               :fresh {:type "boolean"}
                               :isolated {:type "boolean"}}}}
   {:name "help"
    :description "The slopp workflow cheat-sheet: which tool for what, how to read results."
    :inputSchema {:type "object" :properties {}}}
   {:name "branch_create"
    :description "Create a branch from the current line's state and switch to it (O(1); the image is already correct)."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "branch_switch"
    :description "Checkout another branch (or main): swaps the store and brings the live image in step. The test trace map resets."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "branch_merge"
    :description "Merge a branch into the CURRENT line (switch to main first to merge down). Different-form work lands; same-form divergence returns :conflicts (current line kept, branch surfaced — that payload IS current source, no re-read needed). The branch survives. Pass your :agent."
    :inputSchema {:type "object" :properties {:name {:type "string"}
                                              :agent {:type "string"}}
                  :required ["name"]}}
   {:name "branch_delete"
    :description "Delete a branch (never the one you are on)."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "query_branches"
    :description "List every branch with its head delta, and which one is current."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_deps"
    :description "The transitive CALLEE tree of ns/name: what does this form reach (store-internal)? Plan extractions and blast radius with it."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "fix_declares"
    :description "Tidy a namespace's (declare ...) forms: move declared defns above their first caller when safe, delete satisfied declares; unsafe cases (mutual recursion) are skipped and reported. Atomic, verified."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :prompt {:type "string"}
                               :agent {:type "string"}}
                  :required ["ns"]}}
   {:name "ns_rename"
    :description "Rename a WHOLE namespace: its ns decl, every require clause, and every fully-qualified reference across the store. Verified end-to-end; the old name is gone."
    :inputSchema {:type "object"
                  :properties {:old {:type "string"} :new {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}}
                  :required ["old" "new"]}}
   {:name "edit_extract_ns"
    :description "Move forms into a BRAND-NEW namespace: new ns created (requires copied), remaining callers rewritten to alias-qualified calls, require added, moved forms removed — one atomic verified group. Guards: the moved set may not call what stays; nothing outside the source ns may reference it. Use query_deps to plan the set."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :forms {:type "array" :items {:type "string"}}
                               :to {:type "string"} :prompt {:type "string"}
                               :agent {:type "string"}}
                  :required ["ns" "forms" "to"]}}
   {:name "merge_from"
    :description "Merge a diverged COPY of this project (a fork = a copied project dir, edited by its own slopp server) back into this session. Different-form work lands; same-form divergence returns :conflicts (ours kept, theirs surfaced). Absolute dir path."
    :inputSchema {:type "object"
                  :properties {:dir {:type "string"}}
                  :required ["dir"]}}
   {:name "restart"
    :description "Restart the live image (D5 backstop); reload all forms."
    :inputSchema {:type "object" :properties {}}}
   {:name "build"
    :description "Materialize every namespace to real .clj files under dir (absolute path). Optional main (qualified entry fn, e.g. \"calc.core/run-cli\") also emits a GraalVM native-image recipe: a generated launcher plus an executable build-native.sh that compiles a self-contained native binary (optional name overrides the binary name)."
    :inputSchema {:type "object"
                  :properties {:dir {:type "string"} :main {:type "string"}
                               :name {:type "string"}}
                  :required ["dir"]}}])

(def ^:private ^:dynamic *hint*
  "Optional one-line workflow hint, attached to map results (item 3)." nil)

(defn- text [x]
  (let [x (if (and *hint* (map? x) (nil? (:hint x))) (assoc x :hint *hint*) x)]
    {:content [{:type "text" :text (if (string? x) x (pr-str x))}]}))

(def ^:private single-write-tools #{"edit_replace_form" "edit_add_form"})

(def ^:private write-tools
  (into single-write-tools
        ["edit_delete_form" "edit_group" "edit_rename" "edit_extract"
         "edit_move" "ns_add_require" "ns_remove_require" "ns_create"
         "checkpoint" "commit_point" "deps_add" "deps_remove" "deps_pure"]))

(defn- track-hint!
  "Session-scoped usage counters → an optional one-line hint (item 3: haiku's
  66-vs-19 call gap was redundant test_runs + scattered single writes)."
  [session tool args]
  (let [s (::stats (swap! session update ::stats
                          (fn [{:keys [test-runs singles last-ns]
                                :or {test-runs 0 singles 0}}]
                            (cond
                              (= tool "test_run")
                              {:test-runs (inc test-runs)
                               :singles singles :last-ns last-ns}

                              (single-write-tools tool)
                              {:test-runs 0
                               :singles (if (= (:ns args) last-ns) (inc singles) 1)
                               :last-ns (:ns args)}

                              (#{"edit_group" "checkpoint" "commit_point"} tool)
                              {:test-runs 0 :singles 0 :last-ns nil}

                              (write-tools tool)  ; other writes keep the streak
                              {:test-runs 0 :singles singles :last-ns last-ns}

                              :else
                              {:test-runs test-runs
                               :singles singles :last-ns last-ns}))))]
    (cond
      (>= (:test-runs s) 3)
      "every write already verifies (its result includes :test) — test_run is rarely needed"

      (>= (:singles s) 4)
      "several single-form writes in a row — batch related changes into ONE edit_group"

      :else nil)))

(def ^:private cheat-sheet
  "slopp cheat-sheet
TURN:    turn_begin {agent, intent: <user's verbatim ask>} FIRST -- writes are
         refused without an open turn; turn_end {agent} when done (red is ok)
ORIENT:  query_project (everything, one call) · query_search {pattern} (the grep)
         query_symbol {ns name} (one form's source) · query_references {ns name}
OBSERVE: query_eval {code} (your REPL: call anything; cannot redefine code)
         query_observe {ns name code} (capture args/returns flowing through a fn)
WRITE:   every write verifies immediately and returns :test — trust it.
         edit_add_form / edit_replace_form {ns name source prompt}
         edit_group {steps prompt}  <- SEVERAL forms for one reason: always batch
         edit_rename {ns old new}   <- never rename by editing call sites
         edit_extract {ns from form name} · edit_move {ns name before}
         ns_create {ns requires?|source?}  <- NEW namespace: scaffold+grow, or whole source at once
         ns_add_require / ns_remove_require  <- never hand-edit the ns form
RULES:   every write must compile (define callees first; (declare x) for cycles)
         red-first TDD = minimal fn + test in ONE edit_group, then replace
READ RESULTS: {:ok true ...} terse green · :failures = why (expected/actual)
         :diagnosis :genuine = real red, yours · :staleness-detected = healed
         :warnings = fix with edit_rename per :suggest · :untested = add a test
SHARE:   git_push {url?} (milestones -> a normal git remote; url saved once)
         git_pull (3-way absorb: remote wins where you're clean; both-touched =
         conflict, yours stays live, push blocked until git_resolve {path})
         config {key value?} (user.name/user.email = milestone author identity)
FINISH:  checkpoint {label} (tidies, lints, marks the unit boundary)
         commit_point {description} <- MILESTONE: green-gated, the grain a
         human diffs and reverts to; coarser than checkpoints and turns")

(defn- red? [t]
  (and t (pos? (+ (:fail t 0) (:error t 0)))))

(defn- summarize
  "B1: a green-and-quiet edit result compresses to a terse shape (the Go
  baseline showed slopp's verbose green responses were the token loser).
  Anything noteworthy — :error, red tests, NEW warnings, :untested — or an
  explicit :verbose returns the full map."
  [r verbose?]
  (if (or verbose? (:error r) (:untested r)
          (seq (:warnings r)) (red? (:test r)))
    r
    (let [t (:test r)]
      (cond-> {:ok true}
        (:delta r)   (assoc :delta (get-in r [:delta :id]))
        (:group r)   (assoc :group (:group r))
        (:deltas r)  (assoc :deltas (count (:deltas r)))
        (:renamed r) (assoc :renamed (:renamed r))
        t            (assoc :test (cond-> {:ran (:test t 0) :pass (:pass t 0)
                                           :status (:status t :green)
                                           :scope (:scope t)}
                                    (:staleness-detected t) (assoc :staleness-healed true)))
        (:affected r) (assoc :affected (let [a (:affected r)]
                                         (if (= :all a) :all (count a))))
        (:hint r) (assoc :hint (:hint r))
        (:changed-nses r) (assoc :changed-nses (:changed-nses r))
        (:image-healed r) (assoc :image-healed true)
        (:existing-warnings r) (assoc :existing-warnings (:existing-warnings r))))))

(defn- call-tool [session {:keys [name arguments]}]
  (api/sync-with-journal! session)      ; m5b: absorb other servers' commits
  (when (and (:require-turns? @session)
             (contains? write-tools name)
             ;; checkpoint/commit_point CLOSE work; always allowed
             (not (#{"checkpoint" "commit_point"} name)))
    (let [agent (:agent arguments)]
      (cond
        (nil? agent)
        (throw (ex-info (str name " needs an :agent label (turns are enforced "
                             "here — every write must trace to who did it and "
                             "why)")
                        {}))
        (not (api/turn-open? session agent))
        (throw (ex-info (str "no open turn for \"" agent "\" — call "
                             "turn_begin {agent, intent: <the user's verbatim "
                             "ask>} first; sub-agents ride their root agent's "
                             "turn")
                        {})))))
  (let [a   arguments
        sym (fn [k]
              (if-let [v (get a k)]
                (symbol v)
                (throw (ex-info (str "missing required argument :"
                                     (clojure.core/name k) " for " name)
                                {}))))]
    (case name
      "ns_create"         (text (api/create-ns! session (sym :ns)
                                                :requires (:requires a)
                                                :source (:source a)
                                                :agent (:agent a)))
      "ns_add_require"    (text (-> (api/add-require! session (sym :ns) (:require a)
                                                      :prompt (:prompt a))
                                    (select-keys [:error :warnings :existing-warnings
                                                  :test :affected :delta])
                                    (summarize (:verbose a))))
      "query_project"     (text (api/query-project session))
      "query_search"      (text (api/query-search session (:pattern a)
                                                  :limit (or (:limit a) 30)))
      "query_namespaces"  (text (api/query-namespaces session))
      "query_outline"     (text (api/query-outline session (sym :ns)))
      "query_source"      (text (api/query-source session (sym :ns)))
      "query_symbol"      (text (api/query-symbol session (sym :ns) (sym :name)))
      "query_references"  (text (vec (api/query-references session (sym :ns) (sym :name))))
      "query_lineage"     (text (vec (api/query-lineage session (sym :ns) (sym :name))))
      "turn_begin"        (text (api/turn-begin! session :agent (:agent a)
                                                 :intent (:intent a)
                                                 :user (:user a)))
      "turn_end"          (text (api/turn-end! session :agent (:agent a)
                                               :note (:note a)))
      "query_changes"     (text (api/query-changes session :agent (:agent a)
                                                   :from (:from a) :to (:to a)
                                                   :format (:format a)))
      "episode_revert"    (text (-> (api/revert-episode! session
                                                         :agent (:agent a)
                                                         :prompt (:prompt a))
                                    (select-keys [:error :conflict :reverted
                                                  :skipped-shared :note :test
                                                  :group :affected])
                                    (summarize (:verbose a))))
      "query_history"     (text (api/query-history session
                                                   :ns (some-> (:ns a) symbol)
                                                   :contains (:contains a)
                                                   :collapse (:collapse a)
                                                   :format (:format a)
                                                   :limit (or (:limit a) 20)))
      "query_form_history" (text (api/query-form-history session (sym :ns) (sym :name)
                                                         :format (:format a)))
      "query_form_at"     (text (api/query-form-at session (sym :ns) (sym :name)
                                                   :at (:at a)))
      "query_status_at"   (text (api/query-status-at session :at (:at a)))
      "query_search_history" (text (api/query-search-history session (:contains a)
                                                             :limit (:limit a)))
      "query_eval"        (text (api/query-eval session (:code a)))
      "query_observe"     (text (api/query-observe session (sym :ns) (sym :name)
                                                   (:code a)
                                                   :limit (or (:limit a) 10)))
      "query_macroexpand" (text (api/query-macroexpand session (:code a)))
      "edit_replace_form" (text (-> (api/edit-replace! session (sym :ns) (sym :name)
                                                       (:source a) :prompt (:prompt a)
                                                       :agent (:agent a))
                                    (select-keys [:error :warnings :existing-warnings :hint
                                                  :untested :image-healed :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_add_form"     (text (-> (api/add-form! session (sym :ns) (:source a)
                                                   :prompt (:prompt a)
                                                   :agent (:agent a)
                                                   :before (some-> (:before a) symbol))
                                    (select-keys [:error :warnings :existing-warnings :hint
                                                  :untested :image-healed :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_delete_form"  (text (-> (api/delete-form! session (sym :ns) (sym :name)
                                                      :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys [:error :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_group"        (text (-> (api/edit-group!
                                     session
                                     (mapv (fn [s]
                                             (let [action (or (:action s) (:op s))] ; :op guessed in evals
                                               (when-not (contains? #{"replace" "add" "delete" "move"} action)
                                                 (throw (ex-info (str "edit_group step needs :action of replace|add|delete|move (got "
                                                                      (pr-str action) "); keys: :action :ns :name :source :before")
                                                                 {})))
                                               (cond-> {:action (keyword action)
                                                        :ns (symbol (:ns s))}
                                                 (:name s)   (assoc :name (symbol (:name s)))
                                                 (:source s) (assoc :source (:source s))
                                                 (:before s) (assoc :before (symbol (:before s))))))
                                           (:steps a))
                                     :prompt (:prompt a) :agent (:agent a))
                                    (select-keys [:error :step :group :warnings :existing-warnings :changed-nses
                                                  :image-healed :test :affected :deltas])
                                    (summarize (:verbose a))))
      ;; arg forgiveness: every eval run guessed name/to before finding old/new
      "edit_rename"       (let [old (or (:old a) (:name a) (:from a))
                                new (or (:new a) (:to a))]
                            (when-not (and old new)
                              (throw (ex-info "edit_rename needs :old and :new (aliases: :name/:from, :to)" {})))
                            (text (-> (api/rename! session (sym :ns) (symbol old)
                                                   (symbol new) :prompt (:prompt a)
                                                   :agent (:agent a))
                                      (select-keys [:error :renamed :test :affected :delta])
                                      (summarize (:verbose a)))))
      "ns_remove_require" (text (-> (api/remove-require! session (sym :ns) (sym :lib)
                                                         :prompt (:prompt a))
                                    (select-keys [:error :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_subform"      (let [match (or (:match a) (:from a))
                                src   (or (:source a) (:to a))]
                            (when-not (and match src)
                              (throw (ex-info "edit_subform needs :match (exact subform source) and :source (its replacement)" {})))
                            (text (-> (api/edit-subform! session (sym :ns) (sym :form)
                                                         match src
                                                         :text (:text a)
                                                         :prompt (:prompt a)
                                                         :agent (:agent a))
                                      (select-keys [:error :conflict :warnings :existing-warnings
                                                    :untested :image-healed :test :affected :delta :ms])
                                      (summarize (:verbose a)))))
      "edit_revert"       (text (-> (api/revert-form! session (sym :ns) (sym :name)
                                                      :to (:to a) :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys [:error :conflict :warnings :test
                                                  :affected :delta :ms])
                                    (summarize (:verbose a))))
      "edit_move"         (text (api/move-form! session (sym :ns) (sym :name)
                                                :before (sym :before)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
      "edit_trivia"       (text (api/edit-trivia! session (sym :ns)
                                                  (some-> (:before a) symbol)
                                                  (:text a)
                                                  :prompt (:prompt a)
                                                  :agent (:agent a)))
      "edit_extract"      (let [subform (or (:form a) (:source a) (:subform a))]
                            (when-not subform
                              (throw (ex-info "edit_extract needs :form (the exact subform source; aliases :source/:subform accepted)" {})))
                            (text (-> (api/extract! session (sym :ns) (sym :from)
                                                    (sym :name) subform
                                                    :prompt (:prompt a))
                                      (select-keys [:error :extracted :group :test :affected])
                                      (summarize (:verbose a)))))
      "checkpoint"         (text (api/checkpoint! session :label (:label a)
                                                  :agent (:agent a)))
      "commit_point"       (text (api/commit-point! session (:description a)
                                                    :agent (:agent a)
                                                    :force (:force a)
                                                    :target (:target a)))
      "query_commits"      (text (api/query-commits session))
      "deps_add"           (text (api/deps-add! session (sym :lib)
                                                (or (:coord a)
                                                    (when (:version a)
                                                      {:mvn/version (:version a)}))
                                                :agent (:agent a)))
      "deps_remove"        (text (api/deps-remove! session (sym :lib)
                                                   :agent (:agent a)))
      "deps_list"          (text (api/deps-list session))
      "deps_pure"          (text (if (false? (:pure a))
                                   (api/deps-unpure! session (sym :target) :agent (:agent a))
                                   (api/deps-pure! session (sym :target) :agent (:agent a))))
      "query_git"          (text (let [ext (when-let [conn (:db @session)]
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
                                              " couldn't bind)")})))
      "git_push"           (text (if-let [dir (:dir @session)]
                                   (sync/push! dir :url (:url a) :token (:token a)
                                               :branch (:branch a))
                                   {:error "git_push needs a durable session (a store dir)"}))
      "git_clone"          (text (sync/clone! (:url a) (:dir a)
                                              :token (:token a) :agent (:agent a)))
      "git_pull"           (text (sync/pull! session
                                             :token (:token a) :agent (:agent a)))
      "git_conflicts"      (text (if-let [dir (:dir @session)]
                                   {:conflicts (sync/conflicts dir)}
                                   {:error "git_conflicts needs a durable session"}))
      "git_resolve"        (text (if-let [dir (:dir @session)]
                                   (sync/resolve! dir (:path a))
                                   {:error "git_resolve needs a durable session"}))
      "config"             (text (api/config! session (:key a) (:value a)))
      "file_put"           (text (api/file-put! session (:path a) (:content a)
                                                :prompt (:prompt a) :agent (:agent a)))
      "file_remove"        (text (api/file-remove! session (:path a)
                                                   :prompt (:prompt a) :agent (:agent a)))
      "file_list"          (text (api/files-list session))
      "file_get"           (text (api/file-get session (:path a) :at (:at a)))
      "file_history"       (text (api/file-history! session (:path a)))
      "test_run"          (text (if (:isolated a)
                                   (api/isolated-test-run! session)
                                   (api/test-run! session
                                                  (when (:ns a) (sym :ns))
                                                  :only (some->> (:only a) (mapv symbol))
                                                  :fresh (:fresh a))))
      "help"              (text cheat-sheet)
      "branch_create"     (text (api/branch! session (:name a)))
      "branch_switch"     (text (api/branch-switch! session (:name a)))
      "branch_merge"      (text (api/branch-merge! session (:name a)))
      "branch_delete"     (text (api/branch-delete! session (:name a)))
      "query_branches"    (text (api/query-branches session))
      "query_deps"        (text (api/query-deps session (sym :ns) (sym :name)))
      "fix_declares"      (text (api/fix-declares! session (sym :ns)
                                                   :prompt (:prompt a)
                                                   :agent (:agent a)))
      "ns_rename"         (text (api/ns-rename! session (:old a) (:new a)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
      "edit_extract_ns"   (text (-> (api/extract-ns! session (sym :ns)
                                                     (mapv symbol (:forms a))
                                                     (symbol (:to a))
                                                     :prompt (:prompt a)
                                                     :agent (:agent a))
                                    (select-keys [:error :conflict :extracted-to
                                                  :moved :rewrote :test :group])
                                    (summarize (:verbose a))))
      "merge_from"        (text (api/merge! session (:dir a)))
      "restart"           (do (api/restart! session) (text "restarted"))
      "build"             (text (api/build! session (:dir a)
                                            :main (some-> (:main a) symbol)
                                            :name (:name a)))
      (throw (ex-info (str "unknown tool: " name ". Available: "
                           (str/join ", " (map :name tools)))
                      {})))))

^:unsafe (defn handle
  "Dispatch a JSON-RPC request map; return a response map, or nil for
  notifications. Tool exceptions become an `isError` result (so the agent sees
  the message); protocol errors become JSON-RPC errors."
  [session {:keys [id method params]}]
  (case method
    "initialize" {:jsonrpc "2.0" :id id
                  :result {:protocolVersion protocol-version
                           :capabilities {:tools {}}
                           :serverInfo {:name "slopp" :version "0.1.0"}}}
    "notifications/initialized" nil
    "tools/list" {:jsonrpc "2.0" :id id :result {:tools tools}}
    "tools/call" {:jsonrpc "2.0" :id id
                  :result (binding [*hint* (track-hint! session
                                                        (:name params)
                                                        (:arguments params))]
                            (try (call-tool session params)
                                 (catch Exception e
                                   (assoc (text (str "error: " (ex-message e)))
                                          :isError true))))}
    "ping" {:jsonrpc "2.0" :id id :result {}}
    (when id
      {:jsonrpc "2.0" :id id
       :error {:code -32601 :message (str "method not found: " method)}})))

(defn serve!
  "Newline-delimited-JSON stdio loop over `in-reader`/`out-writer`."
  [session in-reader out-writer]
  (doseq [line (line-seq in-reader) :when (not (str/blank? line))]
    (when-let [resp (handle session (json/parse-string line true))]
      (.write out-writer (str (json/generate-string resp) "\n"))
      (.flush out-writer)))
  nil)

^:unsafe
(defn -main
  "Start the stdio MCP server. An optional `dir` argument makes the session
  durable (store at <dir>/.slopp/store.db); without it the session is
  ephemeral. A durable session ALSO opens an in-process git smart-HTTP
  listener on a dir-derived port (localhost) — a READ-ONLY remote (clone/fetch
  of milestones) any git client can point at with no external daemon;
  `query_git` reports the URL. Publishing to a NORMAL external remote
  (GitHub etc.) goes through `git_push`; `git_clone` rebuilds a fileless
  store from one (slopp.sync)."
  [& [dir]]
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
