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
surface: what the application is called, its entry point, and whether it serves
HTTP and how.

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

| Key | Default | Meaning |
|---|---|---|
| `app.name` | the store directory name | Application name, at build time. |
| `app.version` | `0.0.0` | Carried into build artifacts. |
| `app.main` | unset | The entry fn (`myapp.core/-main`). `build` falls back to it when given no `main` argument. |
| `web.enabled` | `false` | Whether this project serves HTTP. The master opt-in: every web rule and `query_routes` exists only when true. |
| `web.adapter` | `:http-kit` | `:jdk` is the zero-dependency fallback. |
| `web.host` | `127.0.0.1` | Bind address. Widen deliberately. |
| `web.port` | unset | The port the app's server binds. Unset means 8080 in production (`serve!` defaults it) and DERIVED from the store directory for the dev server, so two projects on one machine cannot collide. Set it to pin one address for both. |
| `web.max-body-bytes` | `1048576` | Largest accepted request body. |
| `auth.providers` | none | Enabled identity providers, comma-separated, tried in order. |
| `auth.default-policy` | `:deny` | For an endpoint with no `:web/auth`, which only happens if `web-auth-refusal` is dialed down. |
| `auth.session.ttl-seconds` | `86400` | Browser session lifetime. |

!!! note "There is no `dev.server` setting"

    Whether slopp runs your app server while you work is **derived, not
    configured**. It manages one unless the calling process already serves
    every namespace that store would -- which is true of exactly one store on
    earth, slopp's own, whose web surface *is* the API the live session
    already serves.

    It used to be a `dev.server` capability, and that asked every project a
    question only one of them should answer. It misfired the way footguns do:
    the second project to meet it set `false` because its static assets were
    404ing, and the switch then presented a bug as a preference for a week.
    Computing it means a project cannot answer wrong, and cannot use the
    answer to paper over something else. See
    [running](../guide/web/running.md).

Some keys are *families* whose tail is part of the setting:

| Pattern | Meaning |
|---|---|
| `web.static.<url-prefix>` | A static mount. The value is a files-manifest path prefix: `web.static./assets` = `public`. |
| `auth.static.users.<name>` | `{:password-hash "pbkdf2$..." :groups [...]}` |
| `auth.bearer.tokens.<name>` | `{:secret "env:NAME" :groups [...]}` |
| `auth.proxy.*` / `auth.oidc.*` | Provider settings. Secrets are `env:NAME` indirections. |
| `groups.<name>.members` | Comma-separated members of a named group. |

`query_capabilities` is the current list for the version you are on. The web
keys are covered in [auth and security](../guide/web/auth.md).

## The client config file

`config_file {path "client"}` holds the ClojureScript build settings:

| Key | Default | Meaning |
|---|---|---|
| `compiler` | `:clojurescript` | The compile backend. |
| `auto-compile` | `false` | Recompile the bundle in the background after a client-namespace write. |
| `generated-ns` | `app.client.api` | Where `generate_client` writes the typed client. |

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
