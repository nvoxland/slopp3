# Developing slopp

Working **on** slopp itself. For working **through** slopp — authoring code in
a store — read `plugins/slopp/skills/slopp/SKILL.md`, or the published
[docs site](#documentation-site).

## The one thing to understand first

**The working tree is fileless.** slopp's own code, system and tests both,
lives in `.slopp/store.db`. There are no project `.clj` files to edit. Only
these are real files:

- `deps.edn` — the kernel's own dependency coordinates. Project code declares
  its deps in the store manifest (`deps_add`); `build!` generates a project
  `deps.edn` from that.
- `build.clj` — the uberjar recipe.
- Docs, CI config, this file, and everything else humans own.

**`src/slopp/kernel/boot.clj` and `src/slopp/kernel/rt.clj` are NOT in that list**, though
they sit on disk and look like it. The boot kernel lives in the store with
everything else; `build!` materializes it into `target/jar-src/src` and the
uberjar is built from THERE, so those two files are projections. Edit them
with the MCP tools. A hand-edit on disk is overwritten by the next `build!`
and produces the exact signature of a stale jar — your fix runs nowhere and
nothing says why. (`deps.edn` puts `src` on the classpath, so a plain REPL
from a checkout does load the disk copy; that is the only thing it is for.)

What IS special about the kernel is that `--live` cannot hot-reload it — it is
the code doing the reloading. A change there needs `build` → `clojure -T:build
uber` → restart the MCP server.

**"Restart" means two different events, and the `restart` TOOL is the one that
does NOT pick up a new jar.** Worth stating because a consumer spent ten
minutes on the distinction, having done everything right:

| | what it does | picks up a new jar? |
|---|---|---|
| the `restart` **tool** | rebuilds the live/verification image INSIDE the running JVM and reloads the store's forms — this is what clears `:oracle-drift` | **no** |
| a **process** restart | kills and relaunches `java -jar …/slopp.jar` | yes |

A running JVM holds the classes it loaded at boot, so a newer jar on disk is
invisible to it forever. This matters most for a CONSUMER of the jar: every
slopp TOOL runs from it, so a fix that is real in your store and verified live
is still absent from `generate_client`, `done`, or any other tool until the
process that hosts them is relaunched. The store is hot; the toolchain is not.

All code changes go through slopp's MCP tools. There is no file-to-store
reconciliation by construction, so a hand-edited `.clj` would simply be
ignored.

## Prerequisites

- **Java 21+** and the **Clojure CLI**. `mise.toml` pins temurin-21 and
  clojure 1.12.5 — `mise install` picks both up.
- **Docker**, only if you want to preview the docs site.
- **python3**, only if you are exercising the Claude Code plugin hooks.

`mise.toml` also sets `SLOPP_CLOJURE=clojure`: `slopp.image.repl` probes homebrew
paths for the owned-image launcher before trusting PATH, and forcing the bare
name makes child images inherit mise's pinned clojure.

## Run mcp

The MCP server is the development surface, and "mcp" is what to call it. It is
NOT "the dev server": since 2026-08-01 that phrase means the APP server slopp
runs for a web project under development (`slopp.webdev.live`, and whether it
runs is derived, not configured), which mcp is what STARTS. Two servers, one of them serving your
code and the other serving you.

From a checkout:

```sh
clojure -M -m slopp.kernel.boot . --live
```

`--live` watches the store's `data_version` and hot-reloads changed namespaces
into the running process, so an edit you just committed is live in the server
that made it. `--snapshot` freezes at startup instead.

**Startup is async (concurrent sessions).** The MCP server completes its
`initialize` handshake as soon as the store VALUE loads and boots the image
(the child JVM that loads every namespace — the slow part) on a background
thread (`open!`'s `:slopp.api/async-image?`, on for the server). Read-only
store tools serve immediately; oracle and write tools `await` the boot on
first use. This is what keeps a second session on the same store dir from
racing the client's MCP connect timeout while the first session is busy (e.g.
mid-`full_check`) — the store is SQLite-WAL + append-CAS multi-process by
design, so two live sessions share it and each hot-reloads the other's
commits. If a startup still fails under heavy load, bump `MCP_TIMEOUT` (ms)
in `.claude/settings.json`.

In this repo the server is normally the **plugin's**, running the local jar
rather than the pinned release:

```sh
# the tree is FILELESS, so the jar is built from a MATERIALIZATION of the
# store — both steps, in this order, every time:
slopp --call build '{"dir":"'$PWD'/target/jar-src"}'   # or the build MCP tool
clojure -T:build uber                                  # -> target/slopp.jar
SLOPP_JAR=$PWD/target/slopp.jar  # what the plugin's bin/slopp honours
```

**`uber` alone silently ships a STALE jar.** It bundles whatever is under
`target/jar-src/src` — if you skip the materialize step that directory can be
days old, and the build succeeds, prints "built target/slopp.jar", and takes
only a few seconds. Two things say so rather than one:

- `uber` PRINTS the head it is jarring (`build!` writes
  `src/META-INF/slopp/head.edn`), and warns when `store.db` changed after the
  materialization was written.
- **the running process reports it back** — `session_brief`'s `:host :jar
  {:head :behind}`. That is the one that matters, because the question is
  almost never asked while building; it is asked two days later by whoever is
  wondering why a fix they can see in the store is not in the tool.

The old check was `ls -la` plus `unzip -p … | grep` for a symbol, and it was
expensive AND wrong once: mtime and size agreed with a build that had not
finished writing. A head id cannot race that way.

Note `src` specifically, not the whole materialization. `test/`, `cljs-src/`
and `instruments/` are siblings of `src/` in that tree and none of them is
jarred — which is exactly how `module_role :instrument` keeps `slopp.lab` out
of the jar (`D-module-role`): the role moves the file, and this line is the
build script that has never heard of a role.

`.claude/settings.json` sets `SLOPP_LIVE=1`. Rebuild the jar only for
kernel or dependency changes — everything else is store code and hot-reloads
(main-line writes, your own included; a BRANCH line's writes reload the
image only — session_brief's `:host` section states what the server is
actually running). Rebuilding under a running server is safe: `uber` builds
aside and atomically renames, so the live process keeps its old jar inode
and the next launch gets the new one. Note `slopp.kernel.boot` is file AND store
namespace (like `slopp.kernel.rt`) and the jar bundles the STORE copy — kernel
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

## Client code (ClojureScript)

The store now carries client code (`slopp.client.*`, the store-browser
namespace filter). A namespace's target is the `:platform` register — `:jvm`
(default), `:cljc` (portable: JVM-verified AND compiled), `:cljs` (client-only:
compiled, never loaded into the oracle). Declare it with `module_platform`, or
at birth with `ns_create {platform}`.

- **Verify it** the usual way: `:cljc`/`:jvm` logic red/greens on the JVM
  oracle like any Clojure (keep testable logic in `.cljc`); a `:cljs` write
  lands `:unverified :cljs-deferred-to-compile` — its gate is the compiler.
- **Compile it** with the `compile_client` tool → one `:simple` JS bundle
  recorded as a served blob (`public/cljs/main.js` → `/assets/cljs/main.js`).
  It shells **real ClojureScript on the JVM — no Node** — via the generated
  `:cljs` deps.edn alias. **Two dep configs, and slopp owns one of them:**
  the manifest (`deps_add`, delta-tracked, yours) and slopp's own toolchain,
  which `build!` INJECTS at materialization time (`external/client-build-deps`)
  whenever the store has client code — the configured compiler into the
  build-only `:cljs` alias, and malli into the build's `:deps` (inherited by
  `:cljs`, so it covers the external tier and the compile). slopp's deps are
  versioned centrally (malli from `repl/inherent-deps`, the compiler from
  `build/compiler-coord`), never enter the manifest or `deps_list`, and leave
  no `:deps-add` deltas — so an upgrade reaches every store with no migration.
  `build!` is the single materialization point (`external-test-run!` and
  `compile-client!` both go through it), so one injection covers every tier. `compile_client` doesn't run
  automatically by default — it's a build/serve step, not part of the
  write-verify loop.
- **Optional dev loop:** `config_file {path "client" key "auto-compile" value
  "true"}` makes a client-ns write recompile the bundle in the background
  (async, single-flight), so a `--live` server serves fresh JS without a manual
  `compile_client`. Off by default; the write returns `:client-recompiling`.
- Running the compiled JS against a real DOM is out of scope (browser/Cypress
  someday), not the inner loop.

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

At milestones:

```sh
clojure -M -m slopp.kernel.boot . --snapshot --main slopp.lab.benchmark/-main
```

The tree is fileless, so a plain `-m slopp.lab.benchmark` finds nothing.
History appends to `benchmarks/results.md`, which is **gitignored**: it is a
local record, not a committed one, so rows only ever compare against other rows
from the same machine. Don't commit it and don't reinstate it in CI.
Background: `.context/dogfooding.md`.

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

### Local checks (not CI, because their subject is not in the repo)

- `bin/check-shipped-prose.sh` — shipped prose must not document a capability
  key the registry does not declare. Has a CI lane; reads `slopp/main`.
- `bin/check-ideas-backlog.py` — the `ideas/` worklist must not carry its own
  history, and its links must resolve. **No CI lane, deliberately**: `ideas/`
  is gitignored, so CI has nothing to look at. Exits 0 with a note when the
  directory is absent, which is a fresh clone's normal state. Run it after any
  backlog sweep; the reasoning is in its header.

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
