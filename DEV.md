# Developing slopp

Working **on** slopp itself. For working **through** slopp — authoring code in
a store — read `plugins/slopp/skills/slopp/SKILL.md`, or the published
[docs site](#documentation-site).

## The one thing to understand first

**The working tree is fileless.** slopp's own code, system and tests both,
lives in `.slopp/store.db`. There are no project `.clj` files to edit. Only
these are real files:

- `src/slopp/boot.clj` + `src/slopp/rt.clj` — the boot kernel. `boot` loads the
  store into a JVM; `rt` is injected into every owned image. This is
  slopp-the-tool, not project source, and it is the one layer `--live` cannot
  hot-reload.
- `deps.edn` — the kernel's own dependency coordinates. Project code declares
  its deps in the store manifest (`deps_add`); `build!` generates a project
  `deps.edn` from that.
- `build.clj` — the uberjar recipe.
- Docs, CI config, this file, and everything else humans own.

All code changes go through slopp's MCP tools. There is no file-to-store
reconciliation by construction, so a hand-edited `.clj` would simply be
ignored.

## Prerequisites

- **Java 21+** and the **Clojure CLI**. `mise.toml` pins temurin-21 and
  clojure 1.12.5 — `mise install` picks both up.
- **Docker**, only if you want to preview the docs site.
- **python3**, only if you are exercising the Claude Code plugin hooks.

`mise.toml` also sets `SLOPP_CLOJURE=clojure`: `slopp.repl` probes homebrew
paths for the owned-image launcher before trusting PATH, and forcing the bare
name makes child images inherit mise's pinned clojure.

## Run the dev server

The MCP server is the development surface. From a checkout:

```sh
clojure -M -m slopp.boot . --live
```

`--live` watches the store's `data_version` and hot-reloads changed namespaces
into the running process, so an edit you just committed is live in the server
that made it. `--snapshot` freezes at startup instead.

In this repo the server is normally the **plugin's**, running the local jar
rather than the pinned release:

```sh
clojure -T:build uber            # -> target/slopp.jar
SLOPP_JAR=$PWD/target/slopp.jar  # what the plugin's bin/slopp honours
```

`.claude/settings.json` sets `SLOPP_LIVE=1`. Rebuild the jar only for
kernel or dependency changes — everything else is store code and hot-reloads
(main-line writes, your own included; a BRANCH line's writes reload the
image only — session_brief's `:host` section states what the server is
actually running). Rebuilding under a running server is safe: `uber` builds
aside and atomically renames, so the live process keeps its old jar inode
and the next launch gets the new one. Note `slopp.boot` is file AND store
namespace (like `slopp.rt`) and the jar bundles the STORE copy — kernel
edits go to both.

## Test

There is **no `:test` alias**, and `clojure -M:test` does not work here: the
tree is fileless, so there is no source for a file-based runner to find.

Two tiers, by tag on the deftest name:

| Tier | Runs |
|---|---|
| untagged / `^:integration` | in-image, on every affected write |
| `^:external` | its own JVM; never in-image |

The in-image runner filters `^:external` tests **out** and reports them
pending rather than running them there and false-greening them.

You mostly do not run tests by hand. Every write runs the tests a trace map
says exercise the touched forms, and:

- `done {label}` — the episode bar: whole in-image suite plus the `^:external`
  tests your changes impact, lint and dead surface over touched namespaces.
  Reports, never refuses.
- `full_check` — the whole store, every namespace, every tier. Nothing forces
  it; reach for it on a broad change or after deleting a caller.
- `test_run {external true}` — materializes the store into a temp dir and runs
  `clojure -M:test` **there**, against the generated `deps.edn`.
- From a shell: `slopp --main slopp.sync/-main test .`

Image-spawning tests must be `^:external` and must `close!`/`stop!` in a
`finally` — leaked child JVMs are a bug (`ps aux | grep nrepl.cmdline`).

## Documentation site

`docs/` + `mkdocs.yml`, built with MkDocs Material. These are ordinary git
files on the human-owned branch, not store content — edit them with normal
tools, and note that slopp's per-write verification does not cover them.

MkDocs is Python and this project is otherwise JVM-only, so the toolchain
lives in a container. Nothing to install but Docker:

```sh
docker build -q -f Dockerfile.docs -t slopp-docs . && \
  docker run --rm -p 8000:8000 -v "$PWD:/docs" slopp-docs
```

Then open **<http://127.0.0.1:8000/slopp3/>** — not the bare root. `mkdocs
serve` mounts the site under `site_url`'s path so local paths match
production; the bare root just redirects.

It live-reloads as you edit. To check the build the way CI would:

```sh
docker run --rm -v "$PWD:/docs" slopp-docs build --strict
```

`--strict` promotes broken internal links and bad config to errors. Output
goes to `site/`, which is gitignored.

The image prints an upstream advisory banner from the Material team about
MkDocs 2.0 in red. It is not about this repo and not an error.

Hosting is not wired up: there is no GitHub Pages workflow yet, deliberately.

Two rules for writing:

- Tone and the AI-trope checklist: `.context/writing-style.md`.
- The site is **derived** from the shipped skills. A rule belongs in the skill
  first; the site is what goes stale. When a tool or a result key changes,
  grep the old name across `docs/` and `plugins/` in the same pass.

## Benchmarks

At milestones, and commit the updated row:

```sh
clojure -M -m slopp.boot . --snapshot --main slopp.benchmark/-main
```

The tree is fileless, so a plain `-m slopp.benchmark` finds nothing. History
lives in `benchmarks/results.md`. Background: `.context/dogfooding.md`.

## CI

Three workflows, all on the human-owned branch, all checking out `slopp/main`:

- `test.yml` — the suite from files, plus **via-slopp**: the pushed code
  imports *itself* into a fresh store, putting every namespace through every
  gate, then runs the store-built suite.
- `native-proof.yml` — a sample app built through slopp, compiled to a GraalVM
  native binary, executed.
- `release.yml` — manual dispatch with a version input: build the uberjar,
  smoke it bare, tag it, attach it to a Release.

GitHub only runs push-triggered workflows from the pushed ref's tree, so these
run on `workflow_dispatch` and a schedule rather than per push.

## Commits

- **Both ledgers, every milestone**: `commit_point` (green-gated store
  milestone — what `git_push` publishes) *and* a git commit of kernel/docs
  changes, with plain descriptive messages.
- **Never credit Claude or any AI** — no `Co-Authored-By`, no "Generated
  with" footers.
- Update the relevant `.context/` doc in the same commit as the change it
  documents.
- slopp owns exactly one branch (`git-branch`, default `slopp`). `main` is
  human-owned: docs, CI config, this file.

## Where knowledge goes

Three homes, and the routing matters more than it looks:

| Home | Holds | Audience |
|---|---|---|
| `.context/` | why slopp is built this way; design decisions, mechanics, gotchas | whoever works ON slopp |
| `plugins/slopp/skills/` | how to WORK with slopp — **these ship** | every agent driving slopp anywhere |
| `docs/` | the same rules re-aimed at a human evaluating or adopting slopp | readers without the tools in front of them |

The test: *would this help someone using slopp on a completely different
codebase?* If yes it belongs in a skill. A lesson that lives only in
`.context/` helps exactly one repo — this one.

`.context/decisions.md` holds **decisions only**. Observations go elsewhere:
findings from evals and dogfooding to `.context/findings-log.md`, open
frictions to `ideas/`, forward plans to `.context/roadmap.md`.

Subsystem docs and what to read before touching what: the doc map in
[AGENTS.md](./AGENTS.md), the shared instruction set every agent harness
reads (`CLAUDE.md` is a one-line import of it plus Claude-specific wiring).
