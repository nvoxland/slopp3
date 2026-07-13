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

The plugin serves your project directory automatically: a fresh clone of a
slopp-published repo imports itself (the `slopp` branch); an empty dir gets
an empty store. Your TURN is opened/closed automatically by the plugin's
hooks — if a write is ever refused for a missing turn, call
`turn_begin {agent, intent}` yourself. Setup/sync/shipping: the
`slopp-setup` skill. Full result-shape and oracle reference: `reference.md`
next to this file.

## The loop

1. **Orient in ~2 calls.** `query_project` → every namespace outlined.
   `query_search {pattern}` to find things. Then read ONLY what you'll
   edit, batched: `query_source {targets: [{ns, name}, {ns}, …]}` — one
   call, several forms/namespaces. Never read namespaces one by one.
2. **Write with intent.** Every write takes a one-line `prompt` (why) —
   it's permanent provenance.
3. **Trust the verification in the response.** Writes return the test
   result for exactly the affected tests — you do NOT need `test_run`
   after an edit. Red responses carry `:failures` inline; diagnose from
   them. `:untested true` means add a test.
4. **Batch multi-form intent.** Several forms for one reason = ONE
   `edit_group` (mixing add/replace/delete/move). Sequencing single writes
   burns a false red between them. Red-first TDD: stub + test in one
   group (honest red), real implementation next (green) — two writes.
5. **Close units of work.** `checkpoint {label}` when a piece is done;
   `commit_point {description}` at milestones (green-gated).

## Choosing the write tool

| Situation | Tool |
|---|---|
| New namespace (grow with TDD) | `ns_create {ns, requires}` — create dependency nses first |
| New namespace, source ready | `ns_create {ns, source}` — whole text, one verified call |
| Require add/remove | `ns_add_require` / `ns_remove_require` |
| New form | `edit_add_form` (`before` anchors placement) |
| Change a whole form | `edit_replace_form` |
| Small change INSIDE a big form | `edit_subform {ns form match source}` — match ONE subform or ONE pair (case/cond clause, let binding, map entry); replacement may splice several forms; `text: true` for strings/docstrings |
| Change a fn's SIGNATURE | `change_signature {ns name source calls}` — new defn + `$1..$9` call-site template; never signature-change form-by-form |
| Several forms, one reason | `edit_group {steps, prompt}` — atomic, verified once |
| Rename | `edit_rename` (def + all references, shadow-safe); whole namespace: `ns_rename` |
| Extract helper / split ns | `edit_extract` / `edit_extract_ns` (plan with `query_deps`) |
| Reorder / delete / undo | `edit_move` / `edit_delete_form` / `edit_revert` |
| Comments between forms | `edit_trivia` |
| Risky experiment | `branch_create` → work → `branch_switch` + `branch_merge` |

**Every write must compile** — callees before callers ((declare) for mutual
recursion). Mutating fns end in `!` (rename with the `:suggest` if warned);
`^:reads` marks read-only dep calls; `^:unsafe` is the dialect escape hatch.

## Questions → the oracle

Run code instead of reading callers: `query_eval "(my.ns/f X)"` (read-only
REPL, image pre-loaded) · `query_observe` (capture args/returns at runtime)
· `query_references` / `query_deps` (who calls / what it reaches) ·
`query_macroexpand`. History IS queryable: `query_history {collapse: true}`,
`query_changes`, `query_form_at` (time-travel) — see reference.md.

## Tool index

turn_begin turn_end · query_project query_search query_namespaces
query_outline query_source query_symbol query_references query_deps
query_lineage query_history query_form_history query_form_at query_status_at
query_search_history query_changes query_eval query_observe
query_macroexpand query_branches query_commits query_git · ns_create
ns_add_require ns_remove_require ns_rename · edit_add_form
edit_replace_form edit_delete_form edit_subform edit_trivia edit_group
edit_rename change_signature edit_extract edit_extract_ns edit_move
edit_revert episode_revert fix_declares · branch_create branch_switch
branch_merge branch_delete merge_from · deps_add deps_remove deps_list
deps_pure · file_put file_remove file_list file_get file_history · config
config_file · git_push git_clone git_pull git_conflicts git_resolve ·
test_run checkpoint commit_point restart build help
