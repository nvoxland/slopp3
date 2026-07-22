# Install

slopp needs **Java 21+** and nothing else. Everything below runs the same jar.

!!! note
    slopp is experimental. On-disk shapes and tool signatures still change
    between releases. The store is a SQLite journal in your project -- back it
    up the way you would back up a database, and push milestones to git.

## Claude Code plugin (recommended)

```
/plugin marketplace add nvoxland/slopp3
/plugin install slopp@slopp
```

That gives you the MCP server, the workflow skills (`slopp`, `slopp-setup`,
`slopp-style`, `slopp-review`), a `slopp-reader` subagent, and a `slopp` CLI on
the session PATH. The server runs in whatever project directory you are in.

The first launch downloads the release jar (~27MB) and checksum-verifies it. A
SessionStart hook pre-warms that cache, but if the very first session's MCP
connection times out mid-download, reconnect with `/mcp` once the fetch
finishes. Every later start is instant.

Check the wiring end to end:

```sh
slopp --doctor
```

It reports on java, the cached jar, the hook and skill files, python3 (the
hooks need it), and a live store probe. Exit 0 means a session has everything
it needs.

### Offline or no marketplace

Unpack a checkout of `plugins/slopp/` into `~/.claude/skills/slopp/` -- it
loads as a plugin from there. If the server does not come up, add
`"slopp@skills-dir": true` under `enabledPlugins` in `~/.claude/settings.json`.

## Any MCP client

Point your client at the jar:

```json
{
  "mcpServers": {
    "slopp": {
      "command": "java",
      "args": ["-jar", "/path/to/slopp.jar", ".", "--live"]
    }
  }
}
```

`--live` hot-reloads the running *server's own* namespaces as its store
changes; `--snapshot` freezes at startup. With no directory argument,
`java -jar slopp.jar` boots the current working directory -- the jar's entry
point is itself store-tracked config (`META-INF/MANIFEST.MF` on the files
manifest names the launcher and the fn it delegates to).

Grab the jar from the [releases
page](https://github.com/nvoxland/slopp3/releases).

Without the Claude Code plugin you lose the prompt hooks, which means turns
are not opened for you. Your first write will be refused with `no open turn`;
call `turn_begin {intent: "<the user's verbatim ask>"}` and continue. See
[history and provenance](../guide/history.md).

## Starting a new project

Nothing to set up. The server runs in your project directory and creates
`.slopp/store.db` on your first **write**. Add `.slopp/` to `.gitignore`.

Serving a directory does not adopt it. In a project with no store, the server
writes nothing to disk at all -- no `.slopp/`, no git listener, no
session-pause checkpoints -- so leaving the plugin enabled globally does not
turn unrelated repos into slopp projects.

One consequence in a brand-new project: the prompt hook has no store to record
intent against yet, so the first write is refused until you call `turn_begin`
once. From then on turns open themselves.

## Working on a repo that is already published this way

A slopp-published repo has a `slopp` branch (the store's projection) alongside
a human-owned `main`:

```sh
git clone <url> && cd <repo>          # main is checked out -- a normal working dir
slopp --main slopp.sync/-main import .
```

`import` builds `.slopp/store.db` from the repo's `slopp` branch and records
`git-remote "."`, so slopp pushes and pulls the *local* repo's `slopp` branch
and you do all origin interaction with regular git, on both branches.

To build a store with no working tree at all, clone straight from the remote:

```sh
slopp --call git_clone '{"url":"https://github.com/you/proj.git","dir":"proj"}'
```

## From a source checkout

```sh
git clone https://github.com/nvoxland/slopp3.git && cd slopp3
clojure -M -m slopp.boot . --live
```

The working tree is fileless: only the boot kernel (`src/slopp/boot.clj`,
`src/slopp/rt.clj`) and `deps.edn` are real files. `slopp.boot` loads every
namespace's byte-exact source out of `store.db` into the JVM in dependency
order and invokes the entry point, so a plain `-m slopp.mcp` finds nothing.

Next: [your first session](first-session.md).
