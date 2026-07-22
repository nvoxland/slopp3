# Branches and merging

A branch is an O(1) snapshot of the store with a name and a line id, persisted
as its own mini journal under `.slopp/branches/<name>/`. Making one is cheap
enough to use for a risky experiment you expect to throw away.

```clj
branch_create {name "queue-per-tenant"}   ;; creates and switches, O(1)
...
branch_switch {name "main"}
branch_merge  {name "queue-per-tenant"}   ;; into the current line
branch_delete {name "queue-per-tenant"}
```

`query_branches` lists them with their head deltas and marks the current one.

## Checkout is per server

Which branch you are on is *server* state, not store state. Two agents sharing
one store directory can sit on different branches at the same time, each with
its own live image.

Switching parks the line's image intact rather than tearing it down, and
adopts it again when you switch back. Idle images get reaped. Trace narrowing
resets on a switch, so the first write after one may report `:affected :all`
until a run rebuilds the map.

## Merging is delta replay

`branch_merge {name}` merges a branch into the current line, replaying the
source line's delta log with causal delivery. The branch survives the merge.
`merge_from {dir}` does the same thing for a diverged **copy** of the project
living in another directory.

Three outcomes, per form:

- Work on **different forms** lands. This is the common case, and it is why two
  agents in one namespace do not collide.
- **Identical changes** on both sides converge -- no conflict, nothing to
  resolve.
- **Same-form divergence** is a real conflict. Your version stays live; theirs
  is surfaced alongside it.

Results carry `:applied`, `:id-map` and `:merged-from`, scoped per source.
Delta ids are monotonic per line, so two forks from one point mint colliding
ids -- everything cross-line keys on causal bookkeeping rather than on id or
value identity.

The dependency manifest merges too, resolving a version divergence to the newer
coordinate by semver comparison. A module edge declared on both sides unions,
and the merge notes a cycle that neither side could see on its own.

Merge replay runs the cold-load gate: an interleaving that would produce a
namespace no fresh JVM could load is refused rather than merged.

## Conflicts

A conflict's `:ours` and `:theirs` are the current live source, so you resolve
straight from the payload -- no separate fetch, no conflict-marker parsing.

The same model applies at the git boundary, where a remote's changes are
absorbed by a form-granular 3-way merge. See [working with git](git.md).

## When to reach for a branch

Not often. The store's normal mode already tolerates several agents writing
concurrently, and episodes plus `undo {to :last-commit}` cover "this whole
approach was wrong" without a branch at all.

Branches earn their keep when the experiment is long enough that you want to
keep working on the main line meanwhile, or when you want two lines to exist
side by side long enough to compare them.
