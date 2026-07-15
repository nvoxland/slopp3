---
name: slopp
description: "Work efficiently with a slopp codebase over MCP: form-addressed reads/writes with built-in verification, provenance, and a live REPL oracle. Read this before your first slopp tool call."
---

# Working with slopp

slopp is an agent-native codebase: code lives in a **store** (not files),
the unit of everything is the **top-level form**, and a **live JVM image**
runs your code continuously. Every write hot-reloads, re-runs exactly the
tests that exercise the touched forms, and records your stated intent.
Address code as `namespace` + `form name` — no files, paths, or line
numbers.

The system runs the mechanical series FOR you: a fresh clone of a
slopp-published repo imports itself; turns and identity are automatic (the
hooks handle them — never call `turn_begin` unless a write is refused);
every write is formatted, linted, compile-gated, and test-verified before
it lands; when your session pauses, a checkpoint pipeline tidies and
re-verifies what you touched. Setup/sync/shipping: the `slopp-setup`
skill. Full result-shape and oracle reference: `reference.md` next to this
file.

## The loop — and the budget

A whole task should be ~10–20 tool calls: orient (1) → read (1) → write
(a few groups) → ONE milestone. Each extra call re-reads your entire
context; the patterns below are where sessions measurably bleed tokens.

1. **Orient with ONE small call: `session_brief`.** Form names, recent
   milestones with their asks, git alignment, the loop — everything a
   fresh session needs to start working. Skip `query_project` unless you
   need arities/flags for a specific ns (`query_source {ns}` = the outline).
   `query_search {pattern}` to find things.
2. **Read only what the brief can't tell you — and prefer NOT reading.**
   About to edit a function? `query_slice {ns name}` is THE read: full
   source of that one form + interface CARDS (sig, doc, why, test
   warranty) for everything it reaches. TRUST the cards — you don't need
   a callee's body to call it; if an assumption is wrong, the write turns
   red with `:implicated` (the covering tests re-run on every edit).
   Writes are OPTIMISTIC: compose `edit_subform`/`edit_group` matches from
   the brief/slice; a missed or ambiguous match returns the form's CURRENT
   source in `:source-now` — correct from the error and resend. Batched
   named reads: `query_source {targets: [{ns name}…]}`; whole-namespace
   dumps are outline-by-default (`full: true` = the rare escape). Never
   re-read what you just wrote. In a LARGE codebase, delegate broad
   comprehension questions to the `slopp-reader` subagent — it returns
   conclusions; your context should hold decisions, not source. For summaries/handoffs/audits: `report {since}` composes
   milestones + changes + asks + verification + alignment in one read —
   never stitch history calls or re-verify via worktrees/raw store.db.
3. **Write with intent; trust the verification.** Every write takes a
   one-line `prompt`. The response carries the affected tests' result —
   `test_run` after an edit is redundant. Red results carry `:failures`
   inline plus `:implicated` (which of YOUR changes each failing test
   exercises) — start debugging there. **Your work is already verified —
   never re-run the suite externally, never clone/worktree the repo to
   double-check yourself.** When the user asks how to verify, GIVE them
   the commands (`slopp --call test_run`, `query_commits`) — don't
   execute a dry run.
4. **Batch multi-form intent.** Several changes for one reason = ONE
   `edit_group` (mixing add/replace/delete/move/subform/require). Red-first
   TDD: stub + test in one group (honest red), real implementation next
   (green) — two writes total. An `:untested` flag? `draft_test {ns name
   code}` drafts a deftest from OBSERVED calls — edit it in, don't compose
   from nothing.
5. **Close ONCE.** Exactly ONE `commit_point {description}` at the end
   (green-gated) unless the user asks for more. Session pauses checkpoint
   automatically.

## Choosing the write tool

| Situation | Tool |
|---|---|
| New namespace (grow with TDD) | `ns_create {ns, requires}` — create dependency nses first |
| New namespace, source ready | `ns_create {ns, source}` — whole text, one verified call |
| Require add/remove | `ns_add_require` / `ns_remove_require` |
| New form | `edit_add_form` (`before` anchors placement) |
| Change a whole form | `edit_replace_form` |
| Small change INSIDE a big form | `edit_subform {ns form match source}` — match ONE subform or ONE pair; a missed match returns `:source-now` (correct + resend); `text: true` for strings/docstrings; `where: {key value}` addresses the unique MAP containing those entries (registry rows — no exact text needed) |
| Change a fn's SIGNATURE | `change_signature {ns name source calls}` — new defn + `$1..$9` call-site template; never signature-change form-by-form |
| Several changes, one reason | `edit_group {steps, prompt}` — atomic, verified once; steps mix add/replace/delete/move/subform/require, so a rename's leftover `:mentions`, a threshold tweak, and a new require are ONE call |
| Rename ONE form | `edit_rename` (def + all references, shadow-safe); its result lists leftover prose `:mentions` |
| Rename a CONCEPT ("zone is now region") | `rename_sweep {from to}` — namespaces + vars + keywords + prose, store-wide, ONE call, one verification; never form-by-form |
| Extract helper / split ns | `edit_extract` / `edit_extract_ns` (plan with `query_depends {on ns/name, direction :dependencies}`) |
| Reorder / delete / undo | `edit_move` / `edit_delete_form` / `edit_revert` |
| Comments between forms | `edit_trivia` |
| Risky experiment | `branch_create` → work → `branch_switch` + `branch_merge` |
| Declare a module dependency | `module_dep {from to prompt}` — one edge, say why; `remove: true` retracts |

**Every write must compile** — callees before callers ((declare) for mutual
recursion). Mutating fns end in `!` (rename with the `:suggest` if warned);
`^:reads` marks read-only dep calls; `^:unsafe` is the dialect escape hatch.

**Modules are enforced.** A module is the first two ns segments
(`logi.parcel`; `x.y-test` belongs to `x.y`). Calling ACROSS modules
needs a declared edge — the refusal names the exact
`module_dep {from to}` call; DECLARE THEN USE (design the dependency,
then write the code). Deeper namespaces (`x.y.z`) are package-private
to `x.y.*`; the `:export` dial on a defn widens it — `^:export` hoists
it into the module's public surface, `^{:export "x.y.z"}` exposes it to
that subtree only. An edge that closes a cycle is refused (the cycle is
named). Read the manifest + standing debt: `query_depends {modules
true}`; browse what a module OFFERS (public fns + exports, deps,
consumers) before calling into it: `query_depends {modules true, on
"x.y"}`. Public-surface fns warn once when a
write leaves them undocumented — add the docstring.

## Questions → the oracle

Run code instead of reading callers: `query_eval "(my.ns/f X)"` (read-only
REPL, image pre-loaded) · `query_observe` (capture args/returns at runtime)
· `query_depends {on X, direction?}` — THE dependency question, any
kind: a namespace, a var (`ns/name`), or a `:keyword`; `:dependents`
(default) = who uses X (callers, refs, field flow, affected tests);
`:dependencies` = what X reaches · `query_brief` (the edit dossier) ·
`query_macroexpand`. Re-reads are FREE: an unchanged view returns a tiny
`:unchanged` stub — re-fetch instead of carrying source in context.
History is ONE door:
`query_history` routes by args ({} episodes · {ns name} a form's life ·
{ns name at} time-travel · {at} was-green-at · {contains} which asks
touched X); `report {since}` for summaries — see reference.md.

## Tool index

session_brief report query_slice query_depends · turn_begin turn_end ·
query_project query_search query_source query_brief query_history
query_changes query_eval query_observe
query_macroexpand query_branches query_commits query_git · ns_create
ns_add_require ns_remove_require ns_rename · edit_add_form
edit_replace_form edit_delete_form edit_subform edit_trivia edit_group
edit_rename change_signature edit_extract edit_extract_ns edit_move
edit_revert episode_revert fix_declares · branch_create branch_switch
branch_merge branch_delete merge_from · deps_add deps_remove deps_list
deps_pure · module_dep · file_put file_remove file_list file_get
file_history · config config_file · git_push git_clone git_pull git_conflicts git_resolve ·
test_run draft_test checkpoint commit_point restart build help
