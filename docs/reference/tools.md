# Tool index

Every tool a slopp MCP server exposes. `help` prints a shorter cheat-sheet from
a running server, and is always current for the version you are on.

## Orientation

| Tool | What it does |
|---|---|
| `session_brief` | Start here, once. Namespaces with form names, recent milestones and their asks, git alignment, the loop. |
| `query_project` | Every namespace's outline -- names, arities, `!`-status, test-ness -- in one response. `since` returns a one-liner when nothing changed. |
| `query_search {pattern}` | Regex across all store source. Hits are `{:ns :form :line}`. |
| `query_source {targets}` | Source of several named forms in one call. `{ns}` alone returns the outline; `full: true` dumps the namespace. |
| `query_slice {ns name}` | The focused read: one form's full source plus interface cards for everything it reaches. `match` + `window` narrows a giant form. |
| `query_brief {ns name}` | One form's dossier: source, effect flags, cross-namespace callers, covering tests, and the recorded why. |
| `query_detail {id}` | The full version of a response that was trimmed by the size gate. |
| `help` | The workflow cheat-sheet. |

## Dependencies and structure

| Tool | What it does |
|---|---|
| `query_depends {on}` | The generic dependency question -- a namespace, a var, or a `:keyword`. `direction` flips between dependents and dependencies. |
| `query_depends {modules true}` | The module manifest, topological layers, cycles, unused edges, standing debt. Add `on` for one module's surface. |
| `query_vocabulary` | The store's domain keywords, most-used first. Browse before coining a new one. |
| `review_scan` | Whole-codebase review triage, risk-ranked: untested, unused, effectful, high-blast, large, lint-flagged, undocumented. |
| `query_rules` | The enforcement catalog: every rule, its grain, its effective severity, how to discharge it. |
| `query_rule_telemetry` | Fire rates and discharge patterns per rule, plus escape-marker density. |
| `query_capabilities` | Every capability setting: type, default, effective value, what's set. Writes to the `capabilities` config validate against this registry. |

## The oracle

| Tool | What it does |
|---|---|
| `query_call {sym args}` | Invoke one var in the live image. The reference is carried, so renames and the unused gate see it. |
| `query_eval {code}` | Read-only REPL eval for arbitrary expressions. Cannot define or modify code. |
| `query_store {code}` | A read-only `(fn [store] ...)` over the immutable store value -- analysis about the codebase. |
| `query_observe {ns name code}` | Capture args and returns flowing through a form while driver code runs. |
| `query_macroexpand {code}` | Expand-1 and full expansion. |
| `restart` | Rebuild the live image from the store. |

## History

| Tool | What it does |
|---|---|
| `query_history` | Everything that happened. Routes by args: `{}`, `{ns name}`, `{ns name at}`, `{at}`, `{contains}`, `{dead_ends}`. |
| `query_changes {from to}` | Net per-form diffs with the red/green arc. `from` takes `"start"`, `"last-commit"`, `"last-done"` or a delta id. |
| `report` | The summary/handoff composite: milestones, changes with their asks, verification state, alignment. |
| `query_commits` | Milestones newest first, with `:alignment` proving the git branch head matches the latest projection. |
| `query_git` | This session's git view: the read-only listener URL and the saved remote. |

## Writing

| Tool | What it does |
|---|---|
| `ns_create {ns requires\|source}` | A brand-new namespace. Never overwrites. |
| `ns_rename {old new}` | Rename a whole namespace everywhere. |
| `ns_add_require` / `ns_remove_require` | One require clause. Never hand-edit an `ns` form. |
| `edit_add_form {ns source}` | Add a top-level form. `before` anchors placement. |
| `edit_replace_form {ns name source}` | Replace a whole form. |
| `edit_subform {ns form source}` | A change inside a big form, by `match`, `text: true`, or `where: {key value}`. |
| `edit_delete_form {ns name}` | Delete a form (with `ns-unmap`). |
| `edit_move {ns name before}` | Reorder within a namespace. |
| `edit_trivia {ns text}` | Replace the comment and blank-line run before a form. |
| `edit_revert {ns name to?}` | Revert one form to an earlier version. |
| `change_signature {ns name source calls}` | New `defn` plus a `$1..$9` call-site template, as one intent. |
| `edit_rename {ns old new}` | Rename a form and all its references, shadow-safe. |
| `rename_sweep {from to}` | A concept rename store-wide: namespaces, vars, keywords, prose. `dry-run` first. |
| `edit_requalify {ns name}` | Namespace a function's option keys in its arglist and every caller's map literal together. |
| `edit_extract {ns from name}` | Extract a subform into a new fn. Address it by `at` (an anchor) rather than quoting it whole. |
| `edit_move_forms {ns forms to}` | Relocate a cluster to another namespace, rewriting callers everywhere. |
| `undo {deltas\|to}` | Walk back your own recent writes. `to: "last-commit"` scraps everything since the milestone. |
| `episode_revert` | Roll back everything you changed since your last done. |
| `cleanup {ns\|all}` | Bring a namespace (or the whole store) up to current standards. Reports, never auto-fixes. |

## Verification and lifecycle

| Tool | What it does |
|---|---|
| `done {label}` | Close a unit of work. Episode-scoped; reports rather than refuses. |
| `full_check` | The whole store: every namespace linted, dead surface everywhere, every test in every tier. `affected: true` is the middle gear. |
| `commit_point {description}` | Record a milestone. Green-gated; `force: true` records a red honestly. `target` marks an earlier spot. |
| `test_run` | Spot-check specific tests. `{external true}` for the external tier, `{all true}` for the whole in-image suite. |
| `draft_test {ns name code?}` | Draft a `deftest` from observed calls. Writes nothing. |
| `build {dir main?}` | Materialize every namespace to `.clj` files. `main` adds a GraalVM native-image recipe. |

## Architecture

| Tool | What it does |
|---|---|
| `module_dep {from to}` | Declare or retract one module dependency edge. Adds are cycle-checked. |
| `module_purity {module tier}` | Declare a namespace's purity tier. Verifies the code already there. |

## Branches

| Tool | What it does |
|---|---|
| `branch_create {name}` | Snapshot the current state and switch to it. |
| `branch_switch {name}` | Check out another branch; the live image follows. |
| `branch_merge {name}` | Merge a branch into the current line. The branch survives. |
| `branch_delete {name}` | Delete a branch (never the current one). |
| `query_branches` | Branches with head deltas; marks the current one. |
| `merge_from {dir}` | Merge a diverged **copy** of the project from another directory. |

## Git

| Tool | What it does |
|---|---|
| `git_push {url? branches?}` | Publish slopp history to the remote. Fast-forward only. |
| `git_pull` | Fetch and absorb remote history by a form-granular 3-way merge. |
| `git_clone {url dir}` | Clone a remote into a fileless store. |
| `git_conflicts` | Unresolved pull conflicts, with the raw remote content. |
| `git_resolve {path?}` | Mark a conflict resolved. Unblocks `git_push`. |

## Dependencies, files, config

| Tool | What it does |
|---|---|
| `deps_add {lib version}` | Add a library. Hot to the live classpath, no restart. |
| `deps_remove {lib}` | Drop a library. |
| `deps_list` | The dependency manifest. |
| `deps_pure {target}` | Assert a dependency target is pure, so callers are not `!`-flagged. |
| `file_put` / `file_remove` / `file_list` / `file_get` | Non-code files on the files manifest. |
| `file_history {path}` | A tracked file's change history. |
| `config {key value?}` | Read or set store config. |
| `config_file {path key value format}` | Structured config with per-key history, serialized into the projection. |

## Turns

| Tool | What it does |
|---|---|
| `turn_begin {intent}` | Open a turn with the user's verbatim ask. The plugin hooks normally do this. |
| `turn_end` | Close the turn. |
