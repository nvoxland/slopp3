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
  - untagged / `^:integration` — run in-image (per-write verification).
  - `^:external` — tests that spawn their own images/JVMs; NEVER run
    in-image (the in-image runner filters them OUT and reports them
    pending rather than false-greening them). `test_run {external true}`
    materializes the store (build!) into a temp dir and runs
    `clojure -M:test` there. `done` runs the IMPACTED ones automatically;
    **`full_check` is the whole-store gate** — every namespace, every tier.
- Scratch evals: `query_eval` (read-only oracle). A dev nREPL
  (`clojure -M:nrepl`) only sees the KERNEL — it is not a route to slopp's
  code anymore.
- Structural surgery across forms: edit_move_forms / edit_move / edit_subform
  (subform replacements may SPLICE: one match → several forms; the match is
  ONE form — or ONE pair on a pair boundary: case branch, cond clause,
  let binding, map entry). Multi-form intent needs no group tool: make the
  writes one at a time and let the episode group them.

## The docs site

`docs/` + `mkdocs.yml` — the public site (MkDocs Material). ORDINARY git
files on the human-owned branch, NOT store content: edit them with normal
tools, and remember slopp's per-write verification does not cover them.

MkDocs is Python and this repo is JVM-only, so the toolchain lives in a
container (`Dockerfile.docs`) — nothing to install locally beyond Docker.
Build and serve in one line; see `DEV.md` for the details:

```sh
docker build -q -f Dockerfile.docs -t slopp-docs . && \
  docker run --rm -p 8000:8000 -v "$PWD:/docs" slopp-docs
```

`--strict` (the build form) promotes broken internal links and bad config to
errors — run it before committing doc changes. Tone rules:
`.context/writing-style.md`. Hosting is NOT wired up yet: there is no GitHub
Pages workflow, by request.

The site is DERIVED from the skills. When a tool or a result key changes,
the skill is the source of truth and the site is what goes stale — audit it
in the same pass (`grep` the old name across `docs/` and `plugins/`).

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

- The cold-load gate (S1b) auto-resolves acyclic forward refs by reordering
  defs above callers at write time, and for genuine mutual recursion mints a
  MARKED `^{:auto-declare "why"} (declare …)` itself — the agent never writes
  a declare, and a hand-written one is REFUSED (D7). What it still refuses
  outright is a require CYCLE between namespaces, which no reorder can fix.
- Image-spawning tests MUST be `^:external` (in-image recursion) and must
  `close!`/`stop!` in `finally` — leaked child JVMs are a bug
  (`ps aux | grep nrepl.cmdline`).
- `build!` materializes files on demand (tooling/native); `git_push`
  publishes them; neither is needed to RUN (slopp.boot) or TEST
  (`test_run {external true}`).
