# Against files

A slopp working directory contains no source. Not source that is generated from
something else, not source that is synced -- none. The store is the code.

This is the part people push back on hardest, so here is the reasoning.

## Half-measures do not work

The obvious compromise is to keep files as the visible artifact and a semantic
store alongside them. Then something has to reconcile the two, and that
something has to answer questions with no good answers. Somebody edited a file
outside the tools -- is that the truth now? A form was renamed in the store and
its file moved on disk; which wins? Two writes raced, one through each door.

Every system that has tried this spends most of its complexity budget on the
reconciler, and the reconciler is where the bugs live. Deleting the files
deletes the reconciler.

## Files were an interface for people

A file is a unit of editing because a person opens one in an editor and reads
it top to bottom. Ordering within a file matters because a reader benefits from
narrative order. Paths encode structure because a person browses a tree.

None of those reasons survive contact with an agent that addresses code by
name, reads one function at a time along with cards for what it calls, and has
no eyes to scroll with. What is left is the cost: paths that go stale, line
numbers that shift under you, a patch format that fails silently when its
context does not match.

The measured version of that last point: on SWE-Bench, moving from
string-replace edits to an AST action space cut invalid-patch failures from
46.6% to 7.2%, and cut tokens by 12 to 38%. String editing fails quietly, and
it fails often.

## The invariant is mechanical

Deciding agents should not think in files is easy. Keeping it true is not, so
slopp enforces it rather than trusting it.

Nothing crossing the wire may contain a `file.clj:line` coordinate or a `:row`
or `:col` key. There is an audit at the response boundary that throws when one
appears, and it runs in the test suite -- a tool that leaks a coordinate fails
CI.

Diagnostics say anchors instead: the owning form's qualified symbol, plus a
match-ready snippet that pastes straight into `edit_subform` or `query_slice`.
Compile errors translate once, at the hot-load chokepoint, so every "failed to
compile" surface returns the same shape. Coordinates still exist internally,
where the code that owns a form uses them to rewrite inside it. They just do
not escape.

## What you give up, and what gives it back

You give up opening the project in an editor and reading it, and you give up
`rg` over a source tree.

What gives it back:

- **The git projection.** Milestones render the store as ordinary files and
  become deterministic git commits, so the code is browsable, diffable and
  reviewable on GitHub like anything else. Edits made there come back through a
  form-granular 3-way merge.
- **`build`.** Materializes real files whenever tooling needs a tree.
- **`query_search`.** Regex across all store source, returning namespace and
  form rather than path and line.

The projection is the important one. slopp's own repo is a projection of its
own store: every commit in it was generated and pushed by slopp, and the files
are real enough that CI runs them and people have made edits in GitHub's web
editor that flowed back into the store.

You do not lose the ability to look at the code. You lose the ability to edit
it as text without going through a gate -- which, for machine-authored code, is
the entire point.
