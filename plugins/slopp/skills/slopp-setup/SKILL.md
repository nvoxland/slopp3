---
name: slopp-setup
description: "Set up, sync, and ship a slopp-managed repo: adopt slopp in a project, import a published slopp repo from its git checkout, the branch-ownership model (slopp owns the 'slopp' branch, humans own main), store config, and the one-shot CLI for scripts and CI."
---

# Setting up and shipping a slopp repo

The store is a SQLite delta journal at `<project>/.slopp/store.db` — gitignore
it. What git tracks is a **projection**: at every milestone (`commit_point`)
slopp can render the store as ordinary `.clj` files and push them as a git
commit. Day-to-day editing happens through the MCP tools (see the `slopp`
skill); this skill covers everything around that — onboarding, sync, config,
and CI.

## Local mirror (automatic)

In a git checkout, every `commit_point` mirrors the store's history into
LOCAL git as `slopp/<store-branch>` (e.g. `slopp/main`) — the repo
durably carries the slopp history with zero ceremony; inspect it with
normal git. Publishing to a REMOTE stays explicit: `git_push {url}` (the
first URL becomes the saved default; one-off pushes elsewhere never
rewrite it).

## Starting fresh

Nothing to do: the plugin's server runs in your project directory and creates
an empty store on the first write. Add `.slopp/` to `.gitignore`.

## Importing a repo that's published this way

A slopp-published repo has a `slopp` branch (the store's projection) alongside
a human-owned `main`. To work on it:

```sh
git clone <url> && cd <repo>        # main checked out — normal working dir
slopp --main slopp.sync/-main import .
```

`import` builds `.slopp/store.db` from the repo's `slopp` branch (found via
local or remote-tracking refs) and records `git-remote "."` — slopp then
pushes/pulls the **local repo's** `slopp` branch, and you do all origin
interaction with regular git, on both branches. (`slopp` here is the plugin's
bundled CLI, on your PATH; it's the same jar the MCP server runs.)

## The branch-ownership model

slopp owns exactly ONE branch — store config `git-branch`, default `"slopp"`.
- `git_push {url?, branch?}` publishes milestones to that branch of the
  configured remote (`git-remote`; a relative value like `"."` resolves
  against the store dir). It refuses to move the checked-out branch of a
  non-bare local repo.
- `git_pull` absorbs remote commits on that branch — form-granular 3-way
  merge; same-form divergence is quarantined, listed by `git_conflicts`,
  resolved with `git_resolve` + a normal edit.
- `git_clone {url, dir}` rebuilds a fileless store from a published repo.
- Humans own `main` (and everything else) with regular git; merge
  `slopp ↔ main` yourself when you want code to cross the boundary.
- Milestone authorship: `config {key: "user.name"|"user.email", value}`
  (value `"<git>"` defers to git config).

## Store config that ships with the code

- `config_file {path, key, value, format}` — semantic key/value config,
  history-tracked like code and rendered into every projection. Format
  `manifest` covers `META-INF/MANIFEST.MF`: set `Main-Class`/`X-Slopp-Main`
  once and the repo's uberjar boots with a zero-arg `java -jar slopp.jar`.
- `file_put {path, content}` — genuinely opaque files that must ride the
  projection (build scripts). Keep external-system config (CI workflows,
  READMEs) on the human branch instead — only config the app itself consumes
  belongs in the store.
- `deps_add {lib, version}` — the store's dependency manifest (hot-loads
  into the live image; no restart).

## The one-shot CLI (scripts, CI, no MCP session)

`slopp --call <tool> [args]` runs ONE tool call against the store in the
current directory and prints the result — args as JSON, EDN, or `@file`.
Set `SLOPP_AGENT=<name>` to give a script one identity across invocations
(turn state lives in the store, so a `turn_begin` then covers later
calls; without it each invocation is its own session). Useful shapes:

```sh
slopp --call query_project
SLOPP_AGENT=ci slopp --call commit_point '{"description":"release 1.2"}'
slopp --main slopp.sync/-main test .    # isolated suite from a store build
```

CI for a slopp repo is usually: checkout → `import` (or checkout the `slopp`
branch directly) → `test`. GitHub only runs push-triggered workflows from the
pushed ref's tree, so workflows living on `main` reach the `slopp` branch via
`workflow_dispatch`/`schedule` with `checkout ref: slopp`.
