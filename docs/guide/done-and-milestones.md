# Done points and milestones

There is exactly one bar in slopp, and it is `done`.

## `done` is not a once-per-session ritual

Call it at every point you believe a piece of work is complete, before starting
the next one. Finishing a unit of work and moving on *is* a done point.
Multiple `done`s per session is the normal shape.

Each one is cheap, each marks a boundary you can revert to, and each catches a
problem while the work is still in your context rather than three tasks later.

```clj
done {label "line discounts"}
```

What it runs, over everything the episode touched:

- normalize and declare hygiene
- lint
- dead public surface
- the whole in-image test suite
- the `^:external` tests your changes impact, in a separate JVM

Findings are recorded on the episode boundary delta and resurfaced in the next
session's brief, so nothing quietly evaporates when a session ends.

**`done` reports, it does not refuse.** It records the boundary honestly and
tells you "not done yet", so a finding you cannot fix right now never
deadlocks you. What refuses is `commit_point`, and a red `done` stands until
new work supersedes it -- you cannot clear it by committing without changing
anything.

A pre-flight `test_run` before `done` is redundant.

## Scope

`done` is **episode-scoped**, and its `:scope` field says so every time. Lint
and dead-surface cover only the namespaces you touched; the full external and
integration tiers do not run.

If a large external slice would take too long it defers, and rides the findings
as `:external-pending` with a count and a sample rather than burying the
response.

## `full_check` is the whole store

Every namespace linted, dead surface store-wide, every test in every tier.
Nothing forces it -- not `done`, not `commit_point`.

Reach for it when:

- the change was broad
- **you deleted a caller** -- dead surface appears in namespaces you never
  touched, which is the one thing episode scope structurally cannot see
- you are about to make a commit you want to stand behind

There is a middle gear too: `full_check {affected: true}` widens past the
episode without paying for the entire store.

## Dead surface is a hard gate

A public `defn` or `def` that nothing in the store calls is an error at `done`,
and it refuses milestones globally.

Deliberate? Mark the name: `(defn ^:unused-ok f ...)` -- for genuinely external
surface, string-eval'd entry points, runtime-resolved handlers. The dial
polices itself: putting `^:unused-ok` on a var that *is* called fails with
"remove the flag". Fixture namespaces in tests follow the same rule, and edits
must keep the marker.

Public functions without a docstring get a one-time advisory on the write --
only on the has-doc to no-doc transition or a brand-new form, never a
namespace-wide nag.

## Milestones

```clj
commit_point {description "line totals apply per-line discounts"}
```

One at the end of a task, unless someone asks for more. It is the grain a human
diffs and reverts to -- coarser than done points and turns.

`commit_point` has no checks of its own. It runs `done` and gates on that
verdict. That is deliberate: two different bars would mean two different
definitions of finished. You do not need a `test_run` first.

`force: true` records a red milestone honestly instead of refusing, and
`target: "<delta id>"` marks an earlier spot.

A milestone carries a byte-exact rendered tree, which is what makes the git
projection deterministic. In a git checkout it also mirrors the store's history
into local git as `slopp/<store-branch>` automatically, so the repo durably
carries slopp history with no ceremony. Publishing to a remote stays explicit:
see [working with git](git.md).

## Cleaning up

`cleanup {ns}` brings a whole namespace up to current standards regardless of
what you touched; `cleanup {all: true}` sweeps the entire store. That is the
migration surface for adopting slopp on an existing codebase, or for landing an
upgrade that adds a rule every existing form predates.

It **applies** the behaviour-preserving parts: normalizing each form,
reordering definitions above their callers, retiring legacy and stale declares
and phantom names.

It **reports** everything else -- lint, dead public surface, undocumented
public surface, the module, tier, schema and namespaced-keys write gates, the
advisories, and which purity tier the namespace could support. Those normally
fire only as code is written, so a form predating a rule was never subject to
it. Nothing is auto-fixed, because each finding needs a judgement call.

Running it store-wide on a codebase that has been through a few waves of rule
changes tends to surface real defects, not just formatting drift.
