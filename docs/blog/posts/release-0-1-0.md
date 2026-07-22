---
date: 2026-07-12 06:58:00
slug: slopp-0-1-0
categories:
  - Releases
---

# slopp 0.1.0 -- the first jar

First tagged release. There is a jar on the
[releases page](https://github.com/nvoxland/slopp3/releases/tag/v0.1.0), it
runs, and the repo you can browse is a projection of slopp's own store.

<!-- more -->

The thing that makes this a release rather than a checkpoint: slopp is now
written in slopp. All 51 test namespaces and every production namespace live in
the store, the working tree holds only the boot kernel and `deps.edn`, and
every commit on the code branch was generated and pushed by the system itself.

## What's in it

- **The store is the source.** Namespaces are ordered top-level forms in a
  SQLite delta journal. A VFS renders `.clj` on demand; `build` materializes
  files when tooling needs them. No file-to-store reconciliation, ever.
- **`slopp.boot` runs a store directly.** A small self-contained kernel loads
  every namespace's byte-exact source out of `store.db` into the JVM in
  dependency order and invokes the entry point. `--snapshot` freezes at
  startup; `--live` watches the journal and hot-reloads the running process.
- **Verified writes.** Dialect gate, compile into a live image, cold-load
  check, error-level lint refusal, then the tests that a trace map says
  exercise the touched forms. A failed gate commits nothing.
- **The cold-load gate.** A form that references a later one in the same
  namespace hot-loads fine and kills a fresh boot. The gate refuses those, and
  it covers the merge door too -- a replay interleaving that would not
  cold-load is refused rather than merged.
- **The git bridge.** slopp is an in-memory git client: `git_push` publishes
  the projection to a normal remote, `git_clone` rebuilds a fileless store from
  one, and `git_pull` absorbs remote changes through a 3-way merge at form
  granularity. Conflicts quarantine, your version stays live, push blocks until
  you resolve.
- **Files manifest.** Non-code files ride every projected tree, so the CI
  workflow can live in the store alongside the code.
- **Author identity.** Milestones are stamped with the store's configured
  `user.name` / `user.email`; `"<git>"` defers to your git config.
- **A release pipeline.** Tagging builds an uberjar and attaches it, plus a
  native-image proof job that compiles a slopp-built sample app to a GraalVM
  binary and runs it.

## Where it stands

Experimental, self-hosted, and honest about it. The paths I drive daily are
solid -- the write pipeline, the store, the git bridge -- and the ones nobody
has walked are not.

Two round trips through real GitHub have happened, including edits made in the
web editor flowing back into the store, which is the workflow I most wanted to
prove out.

If you try it and something breaks, or something is just confusing, file it at
[github.com/nvoxland/slopp3](https://github.com/nvoxland/slopp3). The bar for
"this should be filed" is low.
