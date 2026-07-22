# Store configuration

## Store settings

Read with `config {key}`, set with `config {key value}`.

| Key | Default | Meaning |
|---|---|---|
| `user.name` | -- | Milestone author name. `"<git>"` defers to git config. |
| `user.email` | -- | Milestone author email. `"<git>"` defers to git config. |
| `git-remote` | unset | Where `git_push` publishes. A relative value like `"."` resolves against the store directory. |
| `git-branch` | `slopp` | The one branch slopp owns. |

`git_push {url}` saves the first url it is given as the default. One-off urls
never rewrite it.

## Rule severity

Every write gate and done-time advisory in the [rule
catalog](../guide/verification.md#rules) has a per-store severity dial:

```clj
config_file {path "rules" key "<rule-id>" value "advisory"}
```

Severities are `off`, `advisory`, `error`, and `refuse`. `query_rules` lists
every rule with its current effective severity and how to discharge it;
`query_rule_telemetry` shows which ones actually fire and whether findings get
fixed or ignored.

slopp's own store runs the catalog blocking. A rule an agent can walk past does
not change behaviour, and a store with no legacy code has no reason to tolerate
one. A project adopting slopp on an existing codebase is the case for dialing
things down while it migrates -- `cleanup {all: true}` is the sweep that tells
you how much there is.

## The capabilities file

`config_file {path "capabilities"}` is the project's app manifest and opt-in
surface: what the application is called (`app.name`, `app.version`), its
entry point (`app.main` -- `build` uses it when no `:main` argument is
passed), and, as the web waves land, whether it serves HTTP and how.

Unlike a free-form config file, every `capabilities` key is declared in a
registry with a type, a default, and a doc line. That buys two things:

- **Writes validate.** An unknown key or a value that fails its type is
  refused at the write, with teaching -- a typo'd setting can never silently
  do nothing.
- **Reads never nil-pun.** `query_capabilities` lists every setting with its
  default and its effective value; a registered key always has an answer.

```clj
config_file {path "capabilities" key "app.main" value "myapp.core/-main"}
query_capabilities {}
```

## Structured config files

`config_file` stores semantic key/values with per-key history and serializes
them into every projection:

```clj
config_file {path "META-INF/MANIFEST.MF"
             key "Main-Class"    value "slopp.launcher"
             format "manifest"}
config_file {path "META-INF/MANIFEST.MF"
             key "X-Slopp-Main"  value "myapp.core/-main"
             format "manifest"}
```

`path` alone reads back everything set for that file. `unset: true` removes a
key.

Prefer this over `file_put` for anything key-shaped: you get per-key history
and a merge that resolves at key grain instead of line grain.

## The dependency manifest

`deps_add`, `deps_remove`, `deps_list`. It is a tracked delta stream, reaches
every image launch, hot-adds to the running one, and generates `deps.edn` at
build time.

Do not hand-edit `deps.edn`. It is output.

## Files manifest

`file_put {path content}` tracks an opaque file so it rides every projected
tree; `file_list`, `file_get`, `file_remove` and `file_history` complete the
surface.

Keep external-system config off it. A CI workflow belongs on the human-owned
git branch, because GitHub reads it and slopp does not.

## Environment

| Variable | Effect |
|---|---|
| `SLOPP_AGENT` | Identity for CLI invocations, so a script's calls share one session and one turn. |
| `SLOPP_JAR` | Point the plugin's `slopp` wrapper at a local jar instead of the pinned release. Useful when developing slopp itself. |
| `CLAUDE_PLUGIN_DATA` | Where the plugin caches the downloaded jar. Falls back to `$XDG_CACHE_HOME/slopp` or `~/.cache/slopp`. |

## Server flags

```sh
slopp <dir>              # serve MCP over stdio
slopp <dir> --live       # hot-reload the server's own namespaces as the store changes
slopp <dir> --snapshot   # freeze the loaded version at startup
slopp --call <tool> ...  # one-shot call, no session
slopp --doctor           # self-check java, jar, hooks, skills, store probe
```
