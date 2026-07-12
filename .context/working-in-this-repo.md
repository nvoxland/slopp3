# Working in this repo

## Layout — the working tree is FILELESS (the T-flip)

- **The store IS the source**: every namespace (system + tests) lives in
  `.slopp/store.db`. There are NO project `.clj` files to edit.
- `src/slopp/boot.clj` + `src/slopp/rt.clj` — the boot KERNEL (slopp-the-tool,
  not project source): `boot` loads the store into the JVM; `rt` is injected
  into every owned image. `deps.edn` = the tool's dep coordinates.
- `projects/` — untracked dogfooding grounds (`.context/dogfooding.md`).
- `benchmarks/results.md` — committed benchmark history.
- `.slopp/` — the store DB (gitignored; the git repo tracks kernel + docs;
  the PUBLISHED file view of the code goes out via `git_push`).

## Dev workflow

- **Everything goes through slopp's MCP tools** — query_* to read, edit_* to
  write. There are no files to hand-edit and no file↔store drift by
  construction. The server runs `clojure -M -m slopp.boot . --live`
  (`.mcp.json`), so committed edits hot-reload into the running server.
- **Red/green TDD always**: add the failing test (edit_add_form/ns_create in
  a test ns), watch the write result report red, implement, watch the
  affected tests re-run green. The trace map picks affected tests per edit.
- **Two test tiers, by tag on the deftest name**:
  - untagged / `^:integration` — run in-image (per-write verification /
    checkpoint+commit_point respectively).
  - `^:isolated` — tests that spawn their own images/JVMs; NEVER run
    in-image. `test_run {:isolated true}` materializes the store (build!)
    into a temp dir and runs `clojure -M:test` there — **this is the full
    suite / merge gate** (was: `clojure -M:test` in the repo).
- Scratch evals: `query_eval` (read-only oracle). A dev nREPL
  (`clojure -M:nrepl`) only sees the KERNEL — it is not a route to slopp's
  code anymore.
- Structural surgery across forms: edit_group / edit_move / edit_subform
  (subform replacements may SPLICE: one match → several forms; the match is
  ONE form — or ONE pair on a pair boundary: case branch, cond clause,
  let binding, map entry).

## Commits

- **Both ledgers, every milestone**: `commit_point` (green-gated store
  milestone — what `git_push` publishes) AND a git commit of kernel/docs
  changes, with plain descriptive messages.
- **Never credit Claude/AI** — no Co-Authored-By, no "Generated with".
- Update the relevant `.context/` doc in the same commit as the change it
  documents.

## Current dev state conventions

- deps (tool-level): rewrite-clj, clj-kondo, nrepl, cheshire, next.jdbc,
  sqlite-jdbc, jgit, slf4j-nop. Project deps live in the STORE manifest
  (`deps_add`/`deps_list`).
- Toolchain pinned in `mise.toml` (Temurin 21, Clojure CLI 1.12.5, babashka);
  `mise install` provisions it; its `[env]` sets `SLOPP_CLOJURE=clojure`.

## Gotchas

- The cold-load gate (S1b) refuses writes/moves/merges whose rendered ns
  can't fresh-load (forward refs) — reorder with edit_move or add a declare.
- Image-spawning tests MUST be `^:isolated` (in-image recursion) and must
  `close!`/`stop!` in `finally` — leaked child JVMs are a bug
  (`ps aux | grep nrepl.cmdline`).
- `build!` materializes files on demand (tooling/native); `git_push`
  publishes them; neither is needed to RUN (slopp.boot) or TEST
  (`test_run {:isolated true}`).
