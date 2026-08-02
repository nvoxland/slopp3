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
| `ui_serve {port? stop?}` | Control this project's own API listener (`/api/*`, plus the API's shape as EDN at `/api/contracts`). Returns `{:url :port}`. It has no pages -- those belong to [the hub](#one-hub-many-projects). |
| `help` | The workflow cheat-sheet. |

### This project's listener, and where the pages are

`ui_serve` controls a small web application -- built on slopp's own [web
framework](../guide/web/index.md), the way any other slopp app is -- that
serves **this project's API and nothing else**: `/api/*` as JSON, and the shape
of that API as EDN at `/api/contracts`. Ask it for `/` and you get a 404.

It runs on the session that is already open, so covering-test counts are the
ones that session actually measured. Serving again replaces the running server
rather than moving to another port, and a port something else holds is reported
as a sentence.

**The server starts it for you.** Because it serves the live session, it dies
with that session — so the MCP server brings one up at boot and `session_brief`
reports the url as `:ui`. `ui_serve` is for changing the port, restarting it,
or `stop: true`. A listener that cannot bind never blocks the server: it prints
a sentence and MCP carries on. The UI is optional; MCP is not.

**`:ui` is not the address you give a human** — they would see JSON. That is
`:hub`, below.

### One hub, many projects

Every MCP server serves its own **API**, and it has to: covering-test counts
and observed examples live in a session, so a process that opened someone
else's store would show every form as covered by nothing. That means one
listener per project, which is why its port is **derived from the store
directory** rather than fixed — stable across restarts, and never the same as
the project next to it. You are not expected to know that number.

The address you remember belongs to the **hub**, one process per machine, and
`session_brief` reports it as `:hub`. It is a separate application — built
with slopp, not inside it — that never opens a store: it holds a registry fed
by heartbeats, renders every page, and proxies `/p/<slug>/api/*` to whichever
project owns that slug. Every screen lives there:

- **`/`** -- the picker: every project that has checked in, linked.
- **`/p/<slug>`** -- that project's timeline, milestones newest first, each
  linking its own change screen, plus what has been written since the newest.
- **`/p/<slug>/change/<from>..<to>`** -- that milestone reviewed form by form,
  grouped module then namespace, each form leading with its recorded ask, then
  a line diff, then how many forms call it.
- **`/p/<slug>/store/form/<id>`** -- one form's permalink. Form ids are stable
  across edits and names are not, so the id is the address. Laid out for
  arriving cold from a link: breadcrumb, callers above grouped by how each edge
  was found, the source, then callees below with their signature and docstring
  *inlined* rather than linked.
- **`/p/<slug>/store`**, **`/p/<slug>/store/ns/<ns>`** -- the namespace index
  and outlines.

Every MCP server checks in with it a few times a minute, carrying its name,
directory and url, and the hub answers with the interval to use next — so the
two sides share no compiled-in number and can be different slopp releases.
The hub lists projects at `/`, fronts each at `/p/<name>/…`, and greys out one
that has stopped answering rather than dropping it. Registering and keeping
alive are the same call, so you can start the hub after your editors, or
restart it, and everything reappears within one interval.

Because the hub renders the pages, every one of them carries a project
dropdown, so you switch without going back to the picker.

!!! note "Not yet packaged"

    The hub moved out of slopp's own store into its own project, so there is
    no `slopp --main …` command for it any more and no install path published
    yet. Two processes, started by hand, is where this deliberately stands:
    the interesting part was proving an app can consume a slopp project's
    published API over HTTP without opening its store, and packaging is a
    separate problem that can wait.

Two capabilities configure it:

| Key | Default | Meaning |
|---|---|---|
| `ui.hub-port` | `7359` | The hub this project registers with. `0` = register with no hub. |
| `ui.port` | *unset* | The port this project's own listener binds. Unset = derived from the store dir. Set it only to pin a fixed address. |

### Serving a slopp app under a path prefix

An app served behind a reverse proxy lives somewhere other than the root, and
a page cannot work out its own prefix from its own URL — `/p/acme/orders` and
`/orders` are indistinguishable to the code receiving them. So the server that
knows has to say.

This began as a hub requirement and is now purely general, which is the better
test of it: the hub renders its own pages, so nothing downstream of it emits a
url and it uses none of this. What follows is for YOUR app behind YOUR proxy.

- Send **`X-Slopp-Base: /your/prefix`** with the proxied request. It is read
  per request, not configured, because the same server may also be answering
  directly on its own port.
- The document then emits its asset urls prefixed and passes the base to the
  browser on the mount point.
- The **generated client** exposes `set-base!`; call it once when you mount
  the app and every typed wrapper's path follows. The default is `""`, which
  is exactly the behaviour an app served at the root already had.

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

## Web applications

Everything here is inert until `http.enabled`. See the [web apps
guide](../guide/web/index.md).

| Tool | What it does |
|---|---|
| `query_routes` | The declared web surface: every endpoint's method, path, auth policy and handler, `:rendered-by` (the forms whose links/forms target it), plus the derived effect/read vocabularies. Empty with teaching until `http.enabled`. |
| `module_platform {module platform}` | Declare a namespace's target platform: `:jvm`, `:cljc`, or `:cljs`. Namespace path, most-specific wins. |
| `compile_client {output?}` | Compile every `:cljc` and `:cljs` namespace to one JS bundle with the configured backend. Warnings anchor to the owning form. |
| `generate_client {ns? from?}` | Write the typed `fetch` client as an edit-protected `:cljs` namespace — from the endpoints this store serves, or with `from` from a contract another API publishes. |

### Generating a client for an API you don't own

The typed client normally comes from endpoints in the same store. When the API
lives somewhere else — a UI in its own project, another service, anything across
a process boundary — the producer publishes its shape and the consumer generates
from that. Neither store reads the other.

The producer serves `slopp.web.contract/contract-document` over its own
namespace list:

```clojure
{:slopp/contract-version 1
 :endpoints [{:method :get :path "/api/timeline" :name timeline
              :request nil :response [:map [:milestones …]]}]}
```

as EDN (`:web/raw true`, `Content-Type: application/edn`), on an endpoint marked
`^{:web/client false}` — describing the wrappers needs no wrapper. It lives in
`slopp.web`, so it ships in the `slopp-web` slim jar and any app can publish.

The consumer then runs:

```
generate_client {from "http://127.0.0.1:7359/api/contracts"}
```

which writes **two** namespaces: a `:cljc` contracts namespace of the published
schemas, and the usual `:cljs` client pointing at it. Both are `^:generated` —
regenerate, never hand-edit.

Three things worth knowing:

- **EDN rather than JSON or OpenAPI.** A malli schema is keywords, symbols and
  vectors; JSON would render `:string` and `"string"` identically. OpenAPI is
  lossless only in the malli → JSON Schema direction, so leading with it forces
  an importer into the path — keep EDN as the source of truth and derive
  OpenAPI later if a non-Clojure consumer needs one.
- **Schema names come from endpoints.** Metadata is evaluated at def time, so
  the producer's schema names never existed at runtime. `timeline` yields
  `timeline-response`, and a schema shared by two endpoints arrives inlined in
  both.
- **The version is there to be refused.** An unrecognised
  `:slopp/contract-version` generates nothing and reports a problem, rather than
  guessing at a shape it doesn't know.

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
| `query_git` | This session's git view: the saved external remote and the clone base it grafts onto. |

## Writing

| Tool | What it does |
|---|---|
| `ns_create {ns requires\|source}` | A brand-new namespace. Never overwrites. |
| `ns_rename {old new}` | Rename a whole namespace everywhere. |
| `ns_delete {ns}` | Retire an empty namespace. Refuses while any form remains or anything still requires it. |
| `ns_add_require` / `ns_remove_require` | One require clause. Never hand-edit an `ns` form. |
| `edit_add_form {ns source}` | Add a top-level form. `before` anchors placement. |
| `edit_replace_form {ns name source}` | Replace a whole form. |
| `edit_subform {ns form source}` | A change inside a big form, by `match`, `text: true`, or `where: {key value}`. |
| `edit_delete_form {ns name}` | Delete a form (with `ns-unmap`). Refuses while anything still calls it, naming the callers; to remove a caller and its callee together, delete in reverse dependency order — callers first, callee last. |
| `edit_move {ns name before}` | Reorder within a namespace. |
| `edit_comment {ns name text}` | Set (or clear) the comment block rendered above a form. The comment is owned by the form, so it travels with it. |
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
| `build {dir main?}` | Materialize every namespace to `.clj` files. `main` adds a GraalVM native-image recipe. Returns `missing-artifacts` for any derived file absent from the cache, each with the call that refills it. |
| `store_health` | What the store costs in bytes: journal by op, materialized state, blob table, and the on-disk artifact cache (`:orphaned` is the reclaimable part). `full_check` answers whether it is correct; this answers what it weighs. |

## Architecture

| Tool | What it does |
|---|---|
| `module_dep {from to}` | Declare or retract one module dependency edge. Adds are cycle-checked over production edges. `remove: true` retracts. |
| `module_purity {module tier}` | Declare a namespace's purity tier. Verifies the FORMS already there; `:unverified` names what it did not check. `remove: true` retires the declaration. |
| `module_platform {module platform}` | Declare a namespace's target platform. Records only — `:verified []`. `remove: true` retires it. See [web apps](#web-applications). |

All three retire with `remove: true`, and retiring is a different statement
from declaring the permissive value: `:external` and `:jvm` are claims,
absence is no claim. A declaration whose namespace was renamed away is a ghost
that every register view has to carry.

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
| `deps_add {lib version}` | Add a library. Hot to the live classpath, no restart. Reports `:host-override` when slopp's own process bundles it at another version, and `:shadowed` when more than one classpath entry provides a namespace. |
| `deps_remove {lib}` | Drop a library. |
| `deps_list` | The dependency manifest. |
| `deps_pure {target}` | Assert a dependency target is pure, so callers are not `!`-flagged. |
| `file_put` / `file_remove` / `file_list` / `file_get` | Non-code files on the files manifest -- authored, versioned, projected to git. `source` (a path on disk) or `encoding: "base64"` stores the bytes content-addressed. A file you can REGENERATE belongs in `:artifacts` instead, not here. |
| `js_dep {name version format global file source}` | Declare an external JavaScript library. Declaring is vendoring: `source` names bytes on disk, which become an artifact with a download recipe. |
| `file_history {path}` | A tracked file's change history. |
| `config {key value?}` | Read or set store config. |
| `config_file {path key value format}` | Structured config with per-key history, serialized into the projection. |

## Turns

| Tool | What it does |
|---|---|
| `turn_begin {intent}` | Open a turn with the user's verbatim ask. The plugin hooks normally do this. |
| `turn_end` | Close the turn. |
