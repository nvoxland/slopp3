# CLI and CI

The plugin puts a `slopp` script on your session PATH. It runs the same release
jar the MCP server runs, and it fetches and caches that jar (checksum-verified)
on first use.

```sh
slopp <dir>                             # serve MCP over stdio on a store
slopp --call <tool> [json|edn|@file]    # one-shot tool call, no session
slopp --main slopp.sync/-main import .  # build a store from the repo's slopp branch
slopp --main slopp.sync/-main test .    # isolated suite from a store build
slopp --doctor                          # self-check the wiring end to end
```

Without the plugin, `java -jar slopp.jar ...` takes the same arguments.

## One-shot tool calls

`--call` runs exactly one tool against the store in the current directory and
prints the result. Arguments are JSON, EDN, or `@file`:

```sh
slopp --call query_project
slopp --call test_run '{"external":true}'
slopp --call report '{"contains":"invoice"}'
SLOPP_AGENT=ci slopp --call commit_point '{"description":"release 1.2"}'
```

`SLOPP_AGENT=<name>` gives a script one identity across invocations. Turn state
lives in the store, so a `turn_begin` under that agent covers later calls;
without it, each invocation is its own session.

This is the surface for scripts, CI steps, and for answering "how do I check
this myself" without an MCP client.

## Serving modes

```sh
slopp . --live       # hot-reload the server's own namespaces as the store changes
slopp . --snapshot   # freeze the loaded version at startup
```

`--live` matters when you are working on slopp itself, or on any store whose
program the server is running. It watches the journal's `data_version` and
reloads changed namespaces into the running process.

The one layer `--live` cannot reload is the boot kernel itself
(`src/slopp/boot.clj`, `src/slopp/rt.clj`), because that is the code doing the
loading.

## CI

The usual shape for a slopp repo: checkout, import (or check out the `slopp`
branch directly), test.

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - run: slopp --main slopp.sync/-main import .
      - run: slopp --main slopp.sync/-main test .
```

Workflows live on the human-owned branch. GitHub only runs push-triggered
workflows from the pushed ref's tree, so reach the `slopp` branch with
`workflow_dispatch` or `schedule` plus `checkout ref: slopp`.

### What slopp's own CI runs

Four jobs, worth stealing the shape of:

- **test-files** -- the suite straight from the repo's files.
- **test-via-slopp** -- the pushed code imports *itself* into a fresh store,
  putting every namespace through every gate, then runs the store-built suite.
  This is the one that catches "it works in my warm image".
- **native-proof** -- a sample app built through slopp, compiled to a GraalVM
  native binary, executed.
- **release** -- manual dispatch with a version input: build the uberjar from
  the `slopp` branch, smoke it bare, tag it, attach it to a Release.

slopp itself ships as an uberjar rather than a native binary, because the store
loader compiles code at runtime by design. Apps *built with* slopp have no such
constraint -- `build {dir main}` emits a native-image recipe.

## Test tiers

Two faces, decided by tag on the test name:

- Plain and `^:integration` tests run **in-image** on every affected write.
- `^:external` tests spawn their own JVMs and temp directories. They run at
  done points (impacted ones only) and on demand via
  `test_run {external true}`, which materializes the store into a temp
  directory and runs the suite there.

`full_check` runs every tier over the whole store.
