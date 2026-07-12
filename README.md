# slopp

An **agent-native codebase system**: the unit of editing, storage, hot-reload,
verification, and history is the **top-level form** — not the file. Code lives
in a SQLite-backed delta journal (`.slopp/store.db`); a working directory
using slopp holds **no source files at all**. Agents (and people) edit through
a tool surface where every write is compile-gated, test-verified, and
provenance-tracked before it lands.

**This repository is a projection.** Every commit here was generated and
pushed by slopp itself from its own store — slopp is written *in* slopp. The
files are real and browsable, CI runs them, and edits made here (PRs, direct
commits) flow back into stores via `git_pull`'s form-granular 3-way merge.

## Quick start

**From the release jar** (needs Java 21+ and the [Clojure CLI](https://clojure.org/guides/install_clojure)):

```sh
# serve the store in the current directory over MCP stdio
java -jar slopp.jar

# clone this repo into a fileless store, then serve it
java -jar slopp.jar --main slopp.sync/-main clone https://github.com/nvoxland/slopp3.git my-slopp
cd my-slopp && java -jar slopp.jar
```

The jar's entry point is itself slopp-tracked config — `META-INF/MANIFEST.MF`
on the store's files manifest names the launcher and the fn it delegates to.

**As an MCP server** for Claude Code or any MCP client (`.mcp.json`):

```json
{"mcpServers": {"slopp": {"command": "java", "args": ["-jar", "slopp.jar", ".", "--live"]}}}
```

`--live` hot-reloads the *running server's own namespaces* as its store
changes; `--snapshot` freezes at startup. From a checkout of this repo,
`clojure -M -m slopp.boot . --live` does the same.

## The model

- **The store is the source.** Namespaces are ordered forms in a delta
  journal; a VFS renders `.clj` text on demand. `build` materializes files
  only when tooling needs them.
- **Every write is gated.** Dialect check (no `eval`/`read-string`/user
  macros), compile into a live subprocess image, a **cold-load check** (a
  form may not reference a later-defined one — hot-loading can't see that,
  a fresh boot dies on it), **error-level lint refusal**, then the affected
  tests run automatically (a trace map knows which tests exercise which
  forms). A failed gate commits nothing.
- **Every write is provenance.** Deltas carry the agent, the prompt, and the
  enclosing *turn* (the user's verbatim ask). `query_form_history` replays
  any form's life; `query_form_at` time-travels; `file_history`/`file_get`
  do the same for tracked non-code files (the CI workflow rides the files
  manifest; the jar manifest is structured config).
- **Milestones are git commits.** `commit_point` (green-gated) snapshots a
  byte-exact tree onto the marker; the git projection deterministically
  mints these as the commits you see here, authored per the store's
  `user.name`/`user.email` config (`"<git>"` defers to your git config).

## Working with git (this repo)

- `git_push` — publish the milestone history to a normal remote
  (fast-forward only, never force).
- `git_clone` — rebuild a **fileless** store from a remote; the clone grafts
  onto the remote's history so its pushes fast-forward.
- `git_pull` — absorb remote changes by a 3-way merge **at form
  granularity**: the remote wins where your store is clean; anything both
  sides touched becomes a conflict (your version stays live, the remote file
  is quarantined, push blocks until you resolve with the edit tools and
  `git_resolve`). Exactly git's model, one file coarser.

Two stores collaborating through this repo — including edits made directly
on GitHub — is the tested workflow.

## Tests

Two tiers, by tag on the test name: plain/`^:integration` tests run
**in-image** on every affected write; `^:isolated` tests (they spawn their
own JVMs) run only via `test_run {:isolated true}`, which materializes the
store into a temp dir and runs `clojure -M:test` there. CI runs both faces:

- **test-files** — the suite straight from this repo's files.
- **test-via-slopp** — the pushed code imports *itself* into a fresh store
  (every namespace through every gate) and runs the store-built suite.
- **native-proof** — a sample app built through slopp, compiled to a GraalVM
  native binary, executed. (slopp itself ships as an uberjar, not a native
  binary — the store loader compiles code at runtime by design.)
- **release** — on `v*` tags: build the uberjar, smoke it with a zero-arg
  boot, attach it to the GitHub Release.


## Mixed ownership: slopp owns ONE branch

slopp pushes and pulls exactly one branch — the store config `git-branch`,
**default `slopp`** — and never touches anything else. `main` (this branch)
is *human-owned*: this README, docs, and anything you manage with regular
git live here; merge `slopp` into `main` (or cherry-pick) whenever you want
the code side updated, and merge `main` edits back for slopp to absorb via
`git_pull`'s form-granular 3-way.

The local variant: set `config git-remote "."` and slopp pushes into your
checkout's own `.git` as the `slopp` branch — you do all origin interaction
with regular git. Pushing onto a branch your working tree has checked out is
refused (the ref would move under you).

Execution config (like the jar's `META-INF/MANIFEST.MF`) isn't a tracked
text file either: the store holds semantic key/values per config path
(`config_file` — per-key history, like forms) and the projection serializes
them into the right format on every push.

## Status

Experimental and self-hosted — slopp has been developed through slopp since
the store flip, and this repo is its published face. Design decisions and
their reasoning live in `.context/decisions.md` in the development repo;
the tool cheat-sheet is one `help` call away on any running server.
