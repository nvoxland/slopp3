# Dependencies and config

## Libraries

The image slopp owns is otherwise bare -- Clojure and nREPL. A store declares
its own libraries in a dependency manifest:

```clj
deps_add    {lib "metosin/malli" version "0.20.1" prompt "boundary schemas"}
deps_remove {lib "metosin/malli" prompt "no longer used"}
deps_list   {}
```

The manifest is a tracked delta stream, like everything else. Adding a library
hot-adds it to the running image, so there is no restart, and it reaches every
later image launch. `build` generates a complete `deps.edn` from it.

**Never hand-edit `deps.edn`.** It is generated. Change the manifest.

**A library slopp itself bundles keeps slopp's version in the server process.**
`deps_add` reports that as `:host-override`, naming both what you declared and
what is actually in force. Your declaration still governs the oracle image, the
test suite, and anything `build` produces -- so the server and your tests can run
different versions of the same library. Usually that is harmless; pin to the
reported version when it is not. slopp cannot displace a jar its own classloader
already holds, so it says so rather than reporting a version it did not get.

A related report, `:shadowed`, names a namespace that more than one entry on the
classpath provides, with the winning url first. That one is usually two of your
own dependencies vendoring the same code.

Once declared, `(:require ...)` the library normally. Its *body* stays opaque
to analysis, so calls into it count as effectful by default. Three ways to say
otherwise:

- name the caller with a trailing `!`
- `deps_pure` the var, namespace, or whole library
- tag the form `^:reads` if it only reads (reads take no bang)

### slopp's own toolchain is not your manifest

There are two dependency configurations, and only one of them is yours. The
manifest above holds application libraries: delta-tracked, visible in
`deps_list`, part of your history. slopp's own plumbing -- the ClojureScript
compiler when a store has [client code](web/client.md), malli for schema
checks -- is injected at build time and versioned centrally with slopp.

Those never enter your manifest, never show up in `deps_list`, and never land
as deltas, so a slopp upgrade moves every store forward with no migration on
your side. Do not `deps_add` them.

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
