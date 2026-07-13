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

(def tools
  [{:name "ns_create"
    :description "Create a BRAND-NEW namespace (never overwrites). Either `requires` (clause strings) scaffolds an empty ns to grow with red-first TDD, or `source` lands whole namespace text in one verified call (ported/reference code). Mutually exclusive."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :requires {:type "array" :items {:type "string"}}
                               :source {:type "string"}
                               :agent {:type "string"}}
                  :required ["ns"]}}
   {:name "ns_add_require"
    :description "Add one require clause (e.g. \"[clojure.string :as str]\") to the ns form."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :require {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "require"]}}
   {:name "ns_remove_require"
    :description "Remove a library's require spec from the ns form."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :lib {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "lib"]}}
   {:name "edit_move"
    :description "Move a form to just before another (definitions precede callers)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :before {:type "string"} :prompt {:type "string"} :agent {:type "string"}}
                  :required ["ns" "name" "before"]}}
   {:name "edit_trivia"
    :description "Replace the comment/blank-line run before form `before` (omit = namespace tail) with `text`. Trivia only; forms untouched."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :before {:type "string"}
                               :text {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}}
                  :required ["ns" "text"]}}
   {:name "query_project"
    :description "THE orientation call: every namespace's full outline (names, arities, docs, !-status, test-ness) in one response. Start here."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_search"
    :description "Regex search across all store source; hits are {:ns :form :line}. Search before reading."
    :inputSchema {:type "object"
                  :properties {:pattern {:type "string"}
                               :limit {:type "integer"}}
                  :required ["pattern"]}}
   {:name "query_namespaces"
    :description "Namespaces with form counts."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_outline"
    :description "One namespace's outline (vars, arities, doc, !, test-ness) — far cheaper than source."
    :inputSchema {:type "object" :properties {:ns {:type "string"}} :required ["ns"]}}
   {:name "query_source"
    :description "Source from the store. `ns` alone = one whole namespace; OR `targets` [{ns, name?}…] reads SEVERAL forms/namespaces in ONE call — batch your orientation reads."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :targets {:type "array"
                                         :items {:type "object"
                                                 :properties {:ns {:type "string"}
                                                              :name {:type "string"}}
                                                 :required ["ns"]}}}}}
   {:name "query_symbol"
    :description "One form: id, name, !-status, source."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "query_references"
    :description "Who references ns/name."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "query_lineage"
    :description "A form's provenance chain (deltas: op + prompt)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "query_history"
    :description "Change history, newest first. collapse=true = episode rows (the readable long view); format=text for humans."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :contains {:type "string"}
                               :limit {:type "integer"}
                               :collapse {:type "boolean"}
                               :format {:type "string" :enum ["edn" "text"]}}}}
   {:name "query_form_history"
    :description "Every version of a form with prompt/time/verification; format=text renders its life as diffs."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :format {:type "string" :enum ["text"]}}
                  :required ["ns" "name"]}}
   {:name "query_form_at"
    :description "TIME-TRAVEL: a form's source at a past delta/milestone id (old names still resolve)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :at {:type "string"}}
                  :required ["ns" "name" "at"]}}
   {:name "query_status_at"
    :description "Was-green-at: the verification state governing a past delta/milestone id."
    :inputSchema {:type "object"
                  :properties {:at {:type "string"}}
                  :required ["at"]}}
   {:name "query_search_history"
    :description "Search prompts/labels/intents across the delta log ('which prompts touched auth?'); hits carry the touched forms."
    :inputSchema {:type "object"
                  :properties {:contains {:type "string"}
                               :limit {:type "integer"}}
                  :required ["contains"]}}
   {:name "query_eval"
    :description "Read-only REPL eval against the live image (the oracle). Namespaces are pre-loaded; requires are no-ops."
    :inputSchema {:type "object" :properties {:code {:type "string"}} :required ["code"]}}
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
   {:name "edit_replace_form"
    :description "Replace a whole top-level form (verified write)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :source {:type "string"} :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name" "source"]}}
   {:name "edit_add_form"
    :description "Add a top-level form (verified write); `before` anchors placement, default tail."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :source {:type "string"}
                               :before {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "source"]}}
   {:name "edit_delete_form"
    :description "Delete a top-level form (ns-unmap, verified)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "edit_subform"
    :description "Replace ONE subexpression inside a big form: `match` = its exact source (structural or whitespace-insensitive — fn literals/regexes match), `source` = replacement (may be SEVERAL forms: splice). Match may also be ONE pair on a pair boundary (case/cond clause, let binding, map entry). text=true = raw-text mode for strings/docstrings. Never re-transcribe the rest."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :form {:type "string"}
                               :match {:type "string"} :source {:type "string"}
                               :text {:type "boolean"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "form" "match" "source"]}}
   {:name "edit_revert"
    :description "Revert a form to an earlier version (default previous, or a delta id)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :to {:type "string"} :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "edit_group"
    :description "Several form writes as ONE atomic intent, one verification — the default for ANY multi-form change. Steps: replace/add/delete/move; add takes optional `before`."
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
    :description "Rename a form + every reference across namespaces (shadow-safe). Never rename by hand."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :old {:type "string"}
                               :new {:type "string"} :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "old" "new"]}}
   {:name "change_signature"
    :description "Change a fn's signature atomically: `source` = the new defn (same name); `calls` = arg-list template rebuilding every call site ($1..$9 = the site's existing args; adding a trailing arg = \"$1 $2 nil\"). Higher-order refs return under :manual."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :source {:type "string"} :calls {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name" "source" "calls"]}}
   {:name "edit_extract"
    :description "Extract a unique subform into a new fn (params computed, call site rewritten, verified)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :from {:type "string"}
                               :form {:type "string"} :name {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}}
                  :required ["ns" "from" "form" "name"]}}
   {:name "turn_begin"
    :description "Open your turn (records the verbatim user ask). Usually AUTOMATED by the plugin's hooks — call manually only if a write is refused for a missing turn."
    :inputSchema {:type "object"
                  :properties {:agent {:type "string"} :intent {:type "string"}
                               :user {:type "string"}}
                  :required ["agent" "intent"]}}
   {:name "turn_end"
    :description "Close your turn (usually automated by the plugin's hooks)."
    :inputSchema {:type "object"
                  :properties {:agent {:type "string"} :note {:type "string"}}
                  :required ["agent"]}}
   {:name "query_changes"
    :description "Net per-form diffs + red/green arc: your open episode (:agent) or any past span (:from/:to delta ids); format=text for humans."
    :inputSchema {:type "object"
                  :properties {:agent {:type "string"}
                               :from {:type "string"} :to {:type "string"}
                               :format {:type "string" :enum ["edn" "text"]}}}}
   {:name "episode_revert"
    :description "Roll back everything you changed since your last checkpoint (other agents' forms skipped, reported)."
    :inputSchema {:type "object"
                  :properties {:agent {:type "string"} :prompt {:type "string"}}}}
   {:name "checkpoint"
    :description "Close a unit of work: normalize your touched forms, re-verify, record a labeled boundary."
    :inputSchema {:type "object" :properties {:label {:type "string"}
                                              :agent {:type "string"}}}}
   {:name "commit_point"
    :description "Record a MILESTONE (green-gated; force=true records red honestly). The git-projection grain; target=<delta id> marks an earlier spot."
    :inputSchema {:type "object"
                  :properties {:description {:type "string"}
                               :agent {:type "string"}
                               :force {:type "boolean"}
                               :target {:type "string"}}
                  :required ["description"]}}
   {:name "query_commits"
    :description "Milestones, newest first (targets plug into query_changes from/to)."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_git"
    :description "This session's git view: the embedded read-only listener URL + the saved external remote."
    :inputSchema {:type "object" :properties {}}}
   {:name "git_push"
    :description "Push the milestone projection to a git remote (fast-forward only). url saved on first use; https auth via token or SLOPP_GIT_TOKEN."
    :inputSchema {:type "object"
                  :properties {:url {:type "string"}
                               :token {:type "string"}
                               :branch {:type "string"}}}}
   {:name "git_clone"
    :description "Clone a remote into dir as a FILELESS store (every ns ingested + verified; no .clj files materialized)."
    :inputSchema {:type "object"
                  :properties {:url {:type "string"}
                               :dir {:type "string"}
                               :token {:type "string"}}
                  :required ["url" "dir"]}}
   {:name "git_pull"
    :description "Absorb remote changes: form-granular 3-way merge; both-sides-touched = conflicts (quarantined; push blocked until resolved)."
    :inputSchema {:type "object"
                  :properties {:token {:type "string"}
                               :agent {:type "string"}}}}
   {:name "git_conflicts"
    :description "Unresolved pull conflicts, with the raw remote content to merge from."
    :inputSchema {:type "object" :properties {}}}
   {:name "git_resolve"
    :description "Mark a pull conflict resolved (omit path = all). Unblocks git_push."
    :inputSchema {:type "object" :properties {:path {:type "string"}}}}
   {:name "config"
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
                               :prompt {:type "string"} :agent {:type "string"}}
                  :required ["path" "content"]}}
   {:name "file_remove"
    :description "Drop a path from the files manifest."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}}
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
                               :prompt {:type "string"} :agent {:type "string"}}
                  :required ["path"]}}
   {:name "deps_add"
    :description "Add an external dependency (hot to the live classpath, no restart). lib like \"org.clojure/data.json\"; version string or full coord map."
    :inputSchema {:type "object"
                  :properties {:lib {:type "string"}
                               :version {:type "string"}
                               :coord {:type "object"}
                               :agent {:type "string"}}
                  :required ["lib"]}}
   {:name "deps_remove"
    :description "Remove a dependency (restarts the image)."
    :inputSchema {:type "object"
                  :properties {:lib {:type "string"} :agent {:type "string"}}
                  :required ["lib"]}}
   {:name "deps_list"
    :description "The dependency manifest: {lib coord}."
    :inputSchema {:type "object" :properties {}}}
   {:name "deps_pure"
    :description "Assert a dep target is PURE so callers aren't !-flagged: a var (\"ns/f\"), a namespace, or a whole lib. pure=false undoes."
    :inputSchema {:type "object"
                  :properties {:target {:type "string"}
                               :pure {:type "boolean"}
                               :agent {:type "string"}}
                  :required ["target"]}}
   {:name "test_run"
    :description "Run tests in the live image (no ns = the whole project in one call). :only names tests; :fresh restarts first; :isolated runs the file-based suite in a fresh JVM (for tests that spawn processes). Writes already verify — rarely needed after edits."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :only {:type "array" :items {:type "string"}}
                               :fresh {:type "boolean"}
                               :isolated {:type "boolean"}}}}
   {:name "help"
    :description "The workflow cheat-sheet: which tool for what, how to read results."
    :inputSchema {:type "object" :properties {}}}
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
    :inputSchema {:type "object" :properties {:name {:type "string"}
                                              :agent {:type "string"}}
                  :required ["name"]}}
   {:name "branch_delete"
    :description "Delete a branch (never the one you are on)."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "query_branches"
    :description "Branches with head deltas; marks the current one."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_deps"
    :description "Transitive callee tree of ns/name (plan extractions / blast radius)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "fix_declares"
    :description "Tidy (declare …): reorder defns above callers when safe, delete satisfied declares. Atomic, verified."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :prompt {:type "string"}
                               :agent {:type "string"}}
                  :required ["ns"]}}
   {:name "ns_rename"
    :description "Rename a WHOLE namespace everywhere (decl, requires, qualified refs). Verified."
    :inputSchema {:type "object"
                  :properties {:old {:type "string"} :new {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}}
                  :required ["old" "new"]}}
   {:name "edit_extract_ns"
    :description "Move forms into a BRAND-NEW namespace (callers rewritten to alias-qualified calls; one atomic group). The moved set may not call what stays; plan with query_deps."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :forms {:type "array" :items {:type "string"}}
                               :to {:type "string"} :prompt {:type "string"}
                               :agent {:type "string"}}
                  :required ["ns" "forms" "to"]}}
   {:name "merge_from"
    :description "Merge a diverged COPY of this project (absolute dir). Same-form divergence = :conflicts, ours kept."
    :inputSchema {:type "object"
                  :properties {:dir {:type "string"}}
                  :required ["dir"]}}
   {:name "restart"
    :description "Restart the live image; reload all forms."
    :inputSchema {:type "object" :properties {}}}
   {:name "build"
    :description "Materialize every namespace to .clj files under dir (absolute). Optional main (qualified entry fn) adds a GraalVM native-image recipe."
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
         "checkpoint" "commit_point" "deps_add" "deps_remove" "deps_pure"
         "change_signature"]))

(defn- track-hint!
  "Session-scoped usage counters → an optional one-line hint (item 3: haiku's
  66-vs-19 call gap was redundant test_runs + scattered single writes).
  Each hint fires ONCE per session — repetition measurably pads outputs
  (+9–20% tok-out on streaky scripts) without changing behavior."
  [session tool args]
  (let [s (::stats (swap! session update ::stats
                          (fn [{:keys [test-runs singles last-ns fired]
                                :or {test-runs 0 singles 0 fired #{}}}]
                            (cond
                              (= tool "test_run")
                              {:test-runs (inc test-runs)
                               :singles singles :last-ns last-ns :fired fired}

                              (single-write-tools tool)
                              {:test-runs 0
                               :singles (if (= (:ns args) last-ns) (inc singles) 1)
                               :last-ns (:ns args) :fired fired}

                              (#{"edit_group" "checkpoint" "commit_point"} tool)
                              {:test-runs 0 :singles 0 :last-ns nil :fired fired}

                              (write-tools tool)  ; other writes keep the streak
                              {:test-runs 0 :singles singles :last-ns last-ns :fired fired}

                              :else
                              {:test-runs test-runs
                               :singles singles :last-ns last-ns :fired fired}))))
        fire! (fn [k msg]
                (when-not (contains? (:fired s) k)
                  (swap! session update-in [::stats :fired] (fnil conj #{}) k)
                  msg))]
    (cond
      (>= (:test-runs s) 3)
      (fire! :test-runs
             "every write already verifies (its result includes :test) — test_run is rarely needed")

      (>= (:singles s) 4)
      (fire! :singles
             "several single-form writes in a row — batch related changes into ONE edit_group")

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
      "query_source"      (text (if-let [ts (:targets a)]
                            (api/query-sources
                             session
                             (mapv (fn [t]
                                     (cond-> {:ns (symbol (:ns t))}
                                       (:name t) (assoc :name (symbol (:name t)))))
                                   ts))
                            (api/query-source session (sym :ns))))
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
      "config_file"        (text (api/config-file! session (:path a)
                                                   :key (:key a) :value (:value a)
                                                   :unset (:unset a) :format (:format a)
                                                   :prompt (:prompt a) :agent (:agent a)))
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
      "change_signature"  (text (-> (api/change-signature! session (sym :ns)
                                                           (sym :name)
                                                           (:source a) (:calls a)
                                                           :prompt (:prompt a)
                                                           :agent (:agent a))
                                    (select-keys [:error :step :group :rewrote :manual
                                                  :warnings :existing-warnings :changed-nses
                                                  :image-healed :test :affected :deltas])
                                    (summarize (:verbose a))))
      "build"             (text (api/build! session (:dir a)
                                            :main (some-> (:main a) symbol)
                                            :name (:name a)))
      (throw (ex-info (str "unknown tool: " name ". Available: "
                           (str/join ", " (map :name tools)))
                      {})))))

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
             (assoc (text (str "error: " (ex-message e))) :isError true)))
      (finally (api/close! session)))))
^:unsafe
(defn call-main!
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
