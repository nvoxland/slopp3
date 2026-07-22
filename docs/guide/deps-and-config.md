# Dependencies and config

## Libraries

The image slopp owns is otherwise bare -- Clojure and nREPL. A store declares
its own libraries in a dependency manifest:

```clj
deps_add    {lib "metosin/malli" version "0.16.4" prompt "boundary schemas"}
deps_remove {lib "metosin/malli" prompt "no longer used"}
deps_list   {}
```

The manifest is a tracked delta stream, like everything else. Adding a library
hot-adds it to the running image, so there is no restart, and it reaches every
later image launch. `build` generates a complete `deps.edn` from it.

**Never hand-edit `deps.edn`.** It is generated. Change the manifest.

Once declared, `(:require ...)` the library normally. Its *body* stays opaque
to analysis, so calls into it count as effectful by default. Three ways to say
otherwise:

- name the caller with a trailing `!`
- `deps_pure` the var, namespace, or whole library
- tag the form `^:reads` if it only reads (reads take no bang)

## Config the application consumes

`config_file` stores semantic key/value config -- per-key history, like forms --
and serializes it into the right format at projection time:

```clj
config_file {path "META-INF/MANIFEST.MF"
             key "X-Slopp-Main" value "myapp.core/-main"
             format "manifest"}
```

Set `Main-Class` and `X-Slopp-Main` once and the repo's uberjar boots with a
zero-argument `java -jar`. slopp's own jar works exactly this way: its entry
point is store-tracked config, not a hardcoded class.

For genuinely opaque files that must ride the projection -- a build script,
say -- use `file_put {path content}`. `file_list`, `file_get`, `file_remove` and
`file_history` complete the surface, and tracked files get the same per-change
history forms do.

## Store settings

`config {key value?}` reads or writes a store setting. Called with just a key,
it reads.

| Key | What it does |
|---|---|
| `user.name` / `user.email` | Milestone author identity. `"<git>"` defers to git config. |
| `git-remote` | Where `git_push` publishes. `"."` means the local repo, resolved against the store directory. |
| `git-branch` | The one branch slopp owns. Default `slopp`. |

## What belongs where

The line is about *who consumes it*:

- Application source, the dependency manifest, and config the application
  itself reads -> **the store**.
- READMEs, project docs, CI workflows, editor config, license, contributor
  docs -> **the human-owned git branch**, edited with regular tools.

External-system config in the store is a smell: a CI workflow lives on `main`
because GitHub reads it, not slopp. This documentation site is on `main` for
the same reason.
