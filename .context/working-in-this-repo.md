# Working in this repo

## Layout

- `src/slopp/*.clj` — the system (see `.context/architecture.md` layer map).
- `test/slopp/*_test.clj` — the suite; integration tests spawn real child
  JVMs (owned images), so the full run takes a few minutes.
- `projects/` — untracked dogfooding grounds (`.context/dogfooding.md`).
- `benchmarks/results.md` — committed benchmark history.
- `.slopp/` — a store DB (gitignored) wherever a durable session ran.
- `DESIGN.md` — the original design brief (historical; decisions have moved
  on — `.context/decisions.md` is authoritative where they differ).

## Dev workflow

- **Red/green TDD always**: write the test, watch it fail, implement, watch
  it pass. No exceptions for "obvious" code.
- **REPL-first via clojure-eval** (global practice): a dev nREPL runs from
  the repo root (`clojure -M:nrepl`, port in `.nrepl-port`). Evaluate with
  `clj-nrepl-eval -p <port> "..."`; always `:reload` changed namespaces
  before running tests. Prefer this over spawning `clojure -M:test` per
  iteration (JVM startup).
- **Full suite** (fresh JVM, the merge gate): `clojure -M:test`.
- The dev nREPL's classpath is fixed at start — after adding deps or creating
  a NEW source root, restart it (`kill`, re-launch, re-read `.nrepl-port`).
- Structural Clojure surgery (moving forms, renames across files, outlines):
  use `clj-surgeon` (`~/bin/clj-surgeon`, see its skill doc) rather than
  hand-editing.
- Integration tests that spawn images must `close!`/`stop!` in `finally` —
  leaked child JVMs are a bug. `ps aux | grep nrepl.cmdline` to check.

## Commits

- Commit at working milestones (suite green) with plain descriptive messages.
- **Never credit Claude/AI** — no Co-Authored-By, no "Generated with".
- Update the relevant `.context/` doc in the same commit as the change it
  documents.

## Current dev state conventions

- deps: rewrite-clj, clj-kondo, nrepl, cheshire, next.jdbc, sqlite-jdbc,
  cognitect test-runner (`:test` alias).
- Toolchain is pinned in `mise.toml` (Temurin 21 java, Clojure CLI 1.12.5,
  babashka for `clj-nrepl-eval`) — `mise install` provisions it. Its `[env]`
  sets `SLOPP_CLOJURE=clojure`, so owned images launch the mise-pinned CLI
  via PATH instead of `slopp.repl`'s homebrew-path fallback.

## Paren-repair gotcha (learned twice)

The dev nREPL masks file syntax errors: an UNGUARDED `(require ... :reload)`
that throws leaves the OLD namespace loaded, and subsequent tests pass
against stale code. After ANY structural/python edit to a `.clj` file:
wrap the reload in try/catch and CHECK it returned `:reloaded`, and treat a
fresh-JVM `clojure -M:test` as the only honest reader. clj-nrepl-eval's
auto-delimiter-repair applies to eval INPUT only, never to files.
