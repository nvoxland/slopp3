# Concepts

The vocabulary that shows up in tool results, in that order of importance.

## Form

A top-level `defn`, `def`, `deftest`, `defmulti` -- one s-expression at the
root of a namespace. It is the unit of editing, storage, merge, hot-reload,
verification and history. Everything else here is defined in terms of it.

A form defines a *set* of names (a `defrecord` mints several), and it is
addressed by any of them: namespace plus name, never a path or a line.

## Store

`<project>/.slopp/store.db` -- a SQLite delta journal holding the ordered forms
of every namespace, the deltas that produced them, the module manifest, the
dependency manifest, and tracked non-code files. It is the source code, not a
cache of it. Gitignore it; what you share is the projection.

A [VFS](../guide/store.md) renders `.clj` text on demand, so anything that
needs to read source still can. `build` writes real files when outside tooling
needs them.

## Delta

One verified write: `{op, ns, prompt, agent, at, ...}`. The `prompt` is the
one-line reason the agent gave. Deltas are appended to the journal
conditionally -- the append *is* the persist, and a concurrent writer that
loses the race refreshes and rebases.

## The image

A JVM subprocess slopp owns, running an nREPL, with your code loaded. Writes
hot-load into it; tests run in it; `query_eval` and `query_call` ask it
questions. When it might be stale, a restart is the always-correct backstop --
`restart` is cheap and rebuilds it from the store.

## Trace map

Which tests exercise which forms, measured by instrumenting a test run rather
than inferred from requires. It is what lets a write re-run three tests instead
of four hundred, and what makes `:untested` a fact rather than a guess.

## Turn, episode, milestone

The provenance stack, coarsest last:

- **Write** -- one verified form-level delta.
- **Episode** -- the work between two done-points. Inferred; agents never plan
  the grouping.
- **Turn** -- one user ask, verbatim. `turn_begin` / `turn_end`; the Claude Code
  hooks do it for you.
- **Milestone** -- `commit_point`. Named, green-gated, carries a byte-exact
  rendered tree, and becomes a git commit.

## Done point

`done {label}` closes an episode: normalize, declare hygiene, lint, dead
surface, the whole in-image suite plus impacted `^:external` tests. It reports
rather than refuses. `commit_point` is what refuses to publish a red one.

`done` is episode-scoped, and says so in its `:scope`. The whole-store question
-- every namespace, every tier -- is `full_check`, and nothing forces it.

## Module

The first two segments of a namespace (`invoice.total`). A trailing `-test`
folds into its subject, so TDD needs no ceremony. Calls across modules need a
declared edge (`module_dep`); a namespace deeper than two segments
(`invoice.total.rounding`) is package-private to its module unless a defn
carries the `:export` dial.

## Purity tier

What a namespace or test is allowed to touch: `:pure` (referentially
transparent), `:internal` (mutates in-process state only, through
`slopp.cache`), `:external` (files, subprocesses, network, db). Declared with
`module_purity`; once declared, the write gate hard-refuses a form that
exceeds it. Undeclared means `:external`, which means ungated.

The axis is about testing: external needs a separate JVM and temp dirs,
internal needs a state reset, pure needs nothing.

## Line and branch

A branch is an O(1) snapshot of the store with a name and a line id, persisted
as its own mini journal. Checkout is per-server state, so two agents can sit on
different branches of one store. Merging is delta-log replay: different-form
work lands, identical edits converge, same-form divergence is a conflict with
your version still live.

## Projection

The git face of the store. At each milestone slopp renders the store as
ordinary `.clj` files and deterministically mints a commit. It owns exactly one
branch (`git-branch`, default `slopp`); humans own `main` and everything else
with regular git.

## Anchors, not coordinates

Everything that tells an agent to fix something carries the owning form's
qualified symbol plus a match-ready snippet. Row and column never cross the
wire -- there is an audit at the response boundary that throws if one does. It
is enforced by tests, so a tool that leaks a coordinate fails CI.
