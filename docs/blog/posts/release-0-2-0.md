---
date: 2026-07-21 21:10:00
slug: slopp-0-2-0
categories:
  - Releases
---

# slopp 0.2.0 -- the rules are enforced now

Nine days and 172 milestones since 0.1.2. The short version: slopp stopped
*advising* and started *enforcing*, the reference question got exactly one
answer, and the write flow became REPL-shaped instead of transaction-shaped.

<!-- more -->

## Architecture is checked on every write

A module is the first two segments of a namespace, and a trailing `-test` folds
into its subject so TDD needs no ceremony. Two rules ride the existing write
pipeline:

- Cross-module calls need a **declared edge** (`module_dep {from to prompt}`).
  The refusal names the exact call to make, and an edge that closes a cycle is
  refused with the cycle named. The manifest is not a file -- it is the fold of
  edge-grain deltas, so concurrent declarations union without conflict.
- Namespaces deeper than two segments are **package-private** to their module.
  The `:export` dial on a `defn` widens one var: `^:export` hoists it to the
  module's public surface, `^{:export "a.b"}` exposes it to that subtree only.
  Definition-site, no facade namespace, so the var keeps its one real address.

`query_depends {modules true}` reads the whole thing in one call: manifest,
topological layers with cycles condensed, unused edges (declared but never
called), standing debt. Add `on` and you get one module's public surface with
signatures and docs -- the cheap browse before calling into it.

Adoption is automatic. A store that predates the system derives its manifest
from the actual resolved call graph, so it starts acyclic with zero violations
and the gate blocks drift from there.

## Purity tiers, and the functional core

Namespaces declare what they are allowed to touch:

- `:pure` -- referentially transparent. No mutation, no `rand`, no `slurp`.
- `:internal` -- may mutate in-process state (a memo, a registry) and nothing
  outside the process.
- `:external` -- IO.

`module_purity {module tier prompt}` declares it, verifies the code already
there, and refuses a tier the existing forms exceed. Scope is a namespace path
with most-specific-wins, so a pure core one level below an effectful module is
declarable. After that the write gate hard-refuses a form that exceeds its
tier, and tier layering is itself a graph property -- the core does not get to
depend on the shell.

The axis is `internal`/`external` because that is what decides how a thing must
be **tested**: external needs a fresh JVM and temp dirs, internal needs a state
reset, pure needs nothing. Which is also why the test tier got renamed:

!!! warning "`^:isolated` is now `^:external`"
    The tier names what a test *exercises*, not how it is run. Legacy spellings
    (`:reads`/`:effects` for `:internal`/`:external`) normalize on read, so
    existing stores keep working, but the canonical vocabulary changed
    everywhere.

Along the way: 22 tests that were tagged as exercising IO turned out to
exercise nothing external and got demoted, and the in-image runner now filters
external tests **out** of an in-image run and reports them pending, instead of
running them there and false-greening them.

## One bar: `done`

There used to be several definitions of finished. Now there is one.

- `done {label}` runs the whole in-image suite plus the `^:external` tests your
  changes impact, normalizes, lints, checks dead surface, and reports. It is
  **episode-scoped** and says so in its `:scope` every time.
- `commit_point` has **no checks of its own**. It runs `done` and gates on that
  verdict.
- `full_check` is the whole store -- every namespace, every tier -- and
  **nothing forces it**. Reach for it on a broad change, after deleting a
  caller, or before a commit you want to stand behind. `full_check
  {affected: true}` is the middle gear.

`done` reports rather than refuses, so an unfixable finding never deadlocks
you. A red `done` stands until new work supersedes it, which means you cannot
clear one by committing without changing anything.

Dead public surface is now a hard gate: a public `defn` nothing in the store
calls is an error at `done` and refuses milestones. Deliberate surface takes
`^:unused-ok`, and the dial polices itself -- putting the marker on a var that
*is* called fails with "remove the flag".

## Every rule is required and blocking

Advisory rules get walked past, so they do not change behaviour. The gates and
done-time findings now live in one declarative registry rather than being
hand-wired, with a per-store severity dial (`off` / `advisory` / `error` /
`refuse`).

- `query_rules` -- the catalog: what each rule checks, its grain, its effective
  severity, how to discharge it.
- `query_rule_telemetry` -- fire rate and discharge pattern per rule. Which
  rules bite, and whether findings get fixed or just keep recurring.

New rules in the catalog: boundary schemas required at module edges (with a
generative `malli.generator/check` over changed analyzable forms at done),
contract-breakage classification beyond arity, key hygiene (a near-duplicate
key advisory plus a require-namespaced-keys gate), ambient state, bare throw,
and schema drift.

## The reference graph

"Who references what" had been answered by several producers that each
integrated sources privately. Now there is one graph
(`slopp.edit.refs`) and every producer normalizes into it: kondo-resolved
statics, syntactically-qualified un-required calls, carrier positions, keyword
references, and marker declarations.

Every consumer queries it -- module gates, the unused gate, review triage, debt
and drift views, moves, renames, blast radius. Records are anchored to stable
form ids, not lines, so a rename or a move does not orphan them.

That forced a thesis-level rule:

!!! note "References must not hide in strings"
    In-process references in data use `#'var` literals. Late binding across a
    load cycle uses `(store/late-ref 'ns/name)`. Vars invoked from outside --
    a CLI, the wire, an eval injection -- declare `^:entry-point`. A naked
    quoted symbol, or a var name inside a string, is invisible to renames,
    moves, and the unused gate.

The resolvers (`resolve`, `requiring-resolve`, `ns-resolve`, `find-var`,
`intern`) joined the dialect denylist for the same reason, as did the metadata
mutators `alter-meta!` / `reset-meta!` -- a form's metadata is its contract,
and it has to be source-only truth.

## Agents never think in files, mechanically

This was a rule everyone agreed with and nothing enforced. Now nothing crossing
the wire may carry a `file.clj:line` coordinate or a `:row`/`:col` key: there
is an audit at the response boundary that throws, and it runs in the suite, so
a tool that leaks a coordinate fails CI.

Diagnostics speak anchors instead -- the owning form's qualified symbol plus a
match-ready snippet. Compile errors translate once, at the hot-load chokepoint,
so every "failed to compile" surface returns the same shape.

## The pipeline owns form order

You no longer write `declare`, and you no longer order definitions. The write
pipeline moves definitions above their callers (Kahn over the reference graph)
and, for genuine mutual recursion, inserts a marked `^{:auto-declare "why"}
(declare ...)` itself. A hand-written one is refused.

That took a few rounds: the move planner was minting unmarked declares for any
multi-form move, phantom declared names could freeze a declare forever, and
`fix_declares` had its own bespoke ordering algorithm. There is one algorithm
now, one declare builder, and `fix_declares` is gone.

The cold-load gate also learned to see require **cycles**, which are the other
shape that loads fine in a warm image and dies on a fresh boot.

## The flow is REPL-shaped

Agents used to plan multi-form groups. They no longer plan anything: episodes
are **inferred** from done points, and the agent just makes small individual
writes. Mid-episode reds are normal state, stale callers ride back as
`:carried-errors`, and `done` re-checks the lot.

Measured on the standing eval: lifetime token cost 0.63x the file-based cohort,
the best recorded so far. That is n=1 per cohort, so treat it as a direction
rather than a number -- but the REPL flow came out cheaper than the atomic-group
flow it replaced, which was the bar.

## Tools

New:

- `session_brief` -- one-call orientation, replacing the four-call opening.
- `query_slice` -- one form's source plus interface cards for everything it
  reaches. The read to reach for before editing.
- `report` -- the summary/handoff composite, with the user's verbatim asks.
- `review_scan` -- whole-codebase review triage, risk-ranked.
- `query_store` -- a read-only `(fn [store] ...)` over the immutable store
  value, for analysis no canned query covers.
- `query_vocabulary` -- the store's domain keywords, so you reuse `:user/email`
  instead of coining a near-duplicate.
- `undo` -- delta-grain rollback, including `to: "last-commit"`, which is the
  usual "this whole approach was wrong" move. It restores deleted forms too,
  which `edit_revert` structurally cannot.
- `cleanup` -- bring a namespace, or the whole store with `all: true`, up to
  current standards. This is the migration surface for adopting slopp on
  existing code, or for landing an upgrade that adds a rule every existing form
  predates.
- `edit_requalify` -- namespace a function's option keys in its arglist and
  every caller's map literal, together.
- `full_check`, `query_rules`, `query_rule_telemetry`, `draft_test`,
  `query_call`.

Changed:

- `edit_move_forms` replaces `extract-ns!`: the general relocation refactor,
  callers rewritten everywhere, requires added and orphans pruned, a two-way
  split refused with the offending forms named.
- `rename_sweep` gained `dry-run`, which writes nothing and splits hits into
  `:in-code` and `:in-strings` -- the string bucket needs an eye, since a test
  fixture is data, not prose.
- `edit_extract` takes an `at` anchor, so you no longer transcribe a large
  subform just to extract it.
- `edit_subform` takes `where: {key value}` to address a registry row by
  content.
- Read tools declare `readOnlyHint` on the wire, so plan-mode clients can
  auto-approve them.
- Re-reading an unchanged view returns a small `:unchanged` stub.

## Dead ends are recorded, not vanished

`undo` and `episode_revert` take a `prompt` saying why you are abandoning
something, and that records a searchable dead end:
`query_history {dead_ends "some.ns"}` finds "someone tried X here and dropped it
because Y" instead of letting the next session walk it again. Reverting before a
milestone keeps the commit log clean -- the dead end shows in `dead_ends`, not
in the history.

## Polymorphism, tracing, and the child image

Multimethods and protocols were where the static call graph went blind.
Instrumenting the dispatch point means both the trace map and reference
tracking see through them now. The external tier traces too, so `^:external`
tests finally yield form-granular evidence instead of being a black box, and
the child image reports its runtime calls back rather than swallowing them.

Test narrowing also decides **per form** now. It used to collapse to
"everything" if any single form lacked trace evidence.

## Plugin and lifecycle

- Turns and identity are automatic under the Claude Code plugin: a prompt hook
  drops the verbatim ask where the server finds it, identity comes off the
  harness session id.
- A workflow-smell registry gives deterministic bad-usage detection as data,
  plus a bash-smell hook that catches raw-`store.db` and git-archaeology
  reflexes before they cost a session.
- `slopp --doctor` self-checks the whole wiring: java, the cached jar, hook and
  skill files, python3, and a live store probe.
- Images self-terminate when their parent dies, so a crashed server stops
  leaking JVMs. JGit transports carry a 30-second timeout, which was the
  server-freeze fix.
- Serving a directory does not adopt it: in a project with no store the server
  writes nothing to disk at all, so the plugin can stay enabled globally.

## Git

`git_push` and `git_pull` each got exactly one meaning: push sends your
`slopp/<branch>` mirrors up, pull fetches them down and absorbs remote store
history. Milestones mirror into local git automatically as `slopp/<branch>`, so
a checkout durably carries slopp history with no ceremony. One-off push urls
never rewrite the saved default.

## Upgrading

- Retag `^:isolated` tests as `^:external`. Legacy spellings still read, but
  the vocabulary is canonical now.
- Remove hand-written `(declare ...)` forms; the pipeline will refuse new ones
  and `cleanup` retires the existing ones.
- Run `cleanup {all: true}` once. It reports what every rule added since your
  store was written would have said, and it found real defects when I ran it
  on slopp's own store -- not just formatting drift.
- Expect the module and dead-surface gates to have opinions on first contact.
  The refusals name the exact call that discharges them.

Please file anything that breaks or confuses at
[github.com/nvoxland/slopp3](https://github.com/nvoxland/slopp3).
