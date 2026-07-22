# The dialect

slopp writes Clojure, with subtractions. Because all the code is
machine-authored, the language can drop conveniences that exist for human
typing comfort and cost analysis accuracy. A generator writes metadata inline
for the same keystrokes; only a person feels the ceremony.

Every write passes the same check, on both entry paths -- single-form edits and
whole-namespace imports.

## What is refused

**`defmacro`.** User macros defeat static analysis, and the whole system leans
on static analysis. Caught wherever it appears, including qualified as
`clojure.core/defmacro` or nested inside another form.

**The `eval` family and friends.** `eval`, `read-string`, `load-string`,
`load-file`, `load-reader`, `alter-var-root`, `binding`, `gen-class`,
`definline`.

**The resolvers.** `requiring-resolve`, `resolve`, `ns-resolve`, `find-var`,
`intern`. These are where a mention becomes a var at runtime, which is exactly
what makes a reference invisible to renames, moves, and the dead-surface gate.

**Runtime metadata mutation.** `alter-meta!` and `reset-meta!`. slopp reads
every load-bearing marker (`^:export`, `^:unsafe`, `^:reads`, `^:auto-declare`,
`:malli/schema`) straight off the stored node -- a form's metadata *is* its
contract. Mutating it at runtime would leave the store showing one contract
while the live var carries another. `with-meta` and `vary-meta` return new
values and stay legal; the cut is exactly the two in-place mutators.

**Hand-written `(declare ...)`.** The pipeline owns form ordering: it moves
definitions above their callers, and for genuine mutual recursion inserts a
marked `^{:auto-declare "why"} (declare ...)` itself. A hand-written one is
therefore always redundant, and refused with teaching. This one lives on the
edit path only, so imports keep their declares.

The denylist matches by **name**, against a bare or `clojure.core/`-qualified
symbol. A same-named var in another namespace is a different var and passes:
`clojure.edn/read-string` (the safe reader) is fine, and so is your own
`my.app/resolve`. The scan descends into literal metadata too, since
`^{:h eval} x` evaluates its metadata at compile time.

The list is deliberately extensible. It is a sample of a policy, not a closed
set.

## The escape hatch

`^:unsafe` on a form opts it out of the dialect gate. It is the greppable last
resort, and it does not silence `!`-naming warnings.

The honest use case: analysis code that *names* a banned symbol as data -- a
head-filter set, an effect-anchor set -- trips the matcher without doing
anything dangerous. Mark the form `^:unsafe`, or better, compare head names as
strings (`(= "defmacro" (str (first s)))`) so no banned symbol appears in the
source at all.

## Markers slopp reads off a form

| Marker | Means |
|---|---|
| `^:export` | Hoist a deep namespace's var into the module's public surface. |
| `^{:export "a.b"}` | Expose it to that subtree only. |
| `^:entry-point` | Invoked from outside the store -- a CLI, the wire, an eval injection. Counts as a reference. |
| `^:unused-ok` | Deliberately uncalled public surface. Fails if the var *is* called. |
| `^:reads` | This form only reads through an opaque dependency, so it needs no `!`. |
| `^:unsafe` | Opt out of the dialect gate. |
| `^:external` | On a test: it exercises IO, so it runs in its own JVM. |
| `^:integration` | On a test: runs in-image with the rest. |
| `^{:malli/schema ...}` | The form's boundary contract. Generatively checked at done. |
| `^{:auto-declare "why"}` | Written by the pipeline, not by you. |

## Naming

Functions that mutate end in `!`. A write that introduces a violation comes
back with a `:warnings` entry carrying a `:suggest` -- rename with `edit_rename`.

Calls into an opaque dependency count as effectful, so a caller either takes
the `!`, tags the form `^:reads`, or the dependency gets `deps_pure`.

## Why subtract at all

Every one of these follows the same template: cheap for a generator to comply
with, expensive for analysis to work around. `eval` and the resolvers make
"who references what" unanswerable. Macros make "what does this expand to"
unanswerable without running it. Metadata mutation makes the stored contract a
lie. `binding` makes a call's behaviour depend on invisible dynamic state.

The trade is that the reference graph, the module gates, the dead-surface gate,
and the trace map are all *sound* rather than best-effort. Losing that is what
makes conventional codebases hard for agents to change safely.

Full reasoning and the decision log live in `.context/dialect.md` in the
development repo.
