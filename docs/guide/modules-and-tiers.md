# Modules and purity tiers

Two architectural rules run on every write, so drift does not accumulate
between reviews.

## Modules

A **module** is the first two segments of a namespace: `invoice.total` and
`invoice.total.rounding` are both in module `invoice.total`. A trailing `-test`
folds into its subject (`invoice.total-test` belongs to `invoice.total`), so
TDD needs no ceremony.

Two rules follow.

**Cross-module calls need a declared edge.**

```clj
module_dep {from "billing.invoice" to "invoice.total"
            prompt "invoicing prices lines through the totals module"}
```

Declare, then use. The refusal on an undeclared call names the exact
`module_dep` call to make, and an edge that would close a cycle is refused with
the cycle named. `remove: true` retracts an edge.

**A test may need to cross where production must not.**

```clj
module_dep {from "shop.rules" to "shop.ops"
            test_only true
            prompt "advisory tests must write code and call done"}
```

A `-test` namespace folds into its subject's module, so an ordinary edge
declared for a fixture would license production too. `test_only` declares a
separate relation: the module's tests may cross, its production code is still
refused, and because a test edge is not a production edge it can never close a
cycle. Reach for it when a fixture has to drive a surface that calls back into
its own module -- testing a done-time advisory means writing code and calling
`done`, so the fixture necessarily touches the operation surface.

The cycle refusal offers this itself when it can see that every namespace
crossing is a test, and names them.

The manifest is not a file -- it is the fold of `:module-edge` deltas, at edge
grain, so two agents declaring concurrently union without conflict.

**Deep namespaces are package-private.** Anything past two segments is callable
only from namespaces sharing the parent prefix, unless a `defn` widens it with
the export dial:

- `^:export` hoists it into the module's public surface.
- `^{:export "billing.invoice"}` exposes it to that subtree only -- widening
  inside the project without going public.

It is definition-site, with no facade namespace and no potemkin, so the var
keeps its one real address.

!!! tip "Cohesion decides where code lives; the export dial decides who sees it"
    These are independent. Put forms that serve one concern in one namespace --
    a deep `x.y.z` for a cluster inside a module -- and if one has legitimate
    outside callers, mark it `^:export` and move on. Never park a form in a
    grab-bag namespace, or drag unrelated forms along with it, just to dodge an
    export marker: the marker is cheap and a god-namespace is not. Conversely
    `^:export` asserts "this is public surface", so it is not a substitute for
    putting a form where it belongs.

### Reading the architecture

```clj
query_depends {modules true}
```

One call returns the manifest, topological `:layers` (cycles condensed so they
share a layer instead of poisoning the picture), the `:cycles` themselves,
`:unused-edges` (declared but never called -- the retire-direction drift a debt
view cannot see), `:overstated-edges`, and standing debt.

Layers and cycles are computed over production edges only. Two kinds of
namespace are left out, for one reason rather than two -- neither is product
code. A `-test` namespace folds into its subject module, so its fixture
dependencies would otherwise manufacture cycles that do not exist in
production. An `:instrument` module ([Role](#role)) is code a human runs by
hand, so counting it stands a harness on top of the thing it measures. Both
still appear in `:manifest`, which is what the gate enforces.

**`:overstated-edges` is the drift `:unused-edges` structurally cannot see.**
An edge declared for production that only a `-test` namespace crosses: the
manifest asserts a dependency the production code does not have. Something
*does* cross it, so an unused check will never mention it. It is worth fixing
rather than filing as tidiness, because declared edges are what the cycle check
reads -- an overstated edge is a real production edge to `module_dep`, so one
can refuse a legitimate declaration in a module that has nothing to do with it.
Declare it `test_only true`, then `remove true` the production edge.

The question is only asked of modules that have production code. A module made
entirely of tests can only ever be crossed by tests, so every edge it declares
would answer yes.

Before calling into a module, browse what it offers:

```clj
query_depends {modules true, on "invoice.total"}
```

Public functions and exported deep vars with their signatures and docstrings,
plus the module's dependencies, consumers, and tier.

### Adoption

Enforcement is on from birth: a brand-new store starts with an empty manifest,
and the first cross-module call teaches declare-then-use.

A store that predates the module system is *adopted* when it opens -- the
manifest is derived from the actual resolved call graph, so it is acyclic with
zero violations by construction and adoption never breaks working code. The
gate then blocks drift from that point on. `git_clone` ingests with the gate
off and adopts what landed.

## Purity tiers

The tier says what a namespace is allowed to touch:

| Tier | Means |
|---|---|
| `:pure` | Referentially transparent. No mutation, no `rand`, no `slurp`. |
| `:internal` | May mutate in-process state -- a memo, a registry -- but touches nothing outside the process. |
| `:external` | IO: files, subprocesses, network, database, console. |

```clj
module_purity {module "invoice.total" tier :pure
               prompt "the totals core is pure arithmetic"}
```

Scope is a namespace **path**, and the most specific declaration wins, so a
pure core underneath an effectful module is declarable. Declaring *verifies*
the FORMS already there and refuses a tier the existing forms exceed.
Undeclared means `:external`, which means ungated.

Once declared, the write gate hard-refuses a form that exceeds the tier. Tier
layering is itself a graph property: the core is not allowed to depend on the
shell.

**And that second half is deliberately not checked at declaration time**, so
the declaration says as much:

```clj
{:module "shop.totals" :tier :pure
 :verified [:forms] :unverified [:layering]
 :note "…layering is a whole-graph property reported by full_check…"}
```

A layering verdict *changes as legitimate work continues* -- declare the
dependency and the same tier becomes valid -- which is exactly the kind of
check that does not belong at write grain. `full_check` reports it instead. So
a tier can be accepted and stand for many writes before anything contradicts
it, and `:unverified` is how you know to expect that. Read it: a clean
declaration is not a clean bill of health.

Every memo must go through `slopp.cache`. That is what keeps `:internal`
checkable -- an ad-hoc atom is indistinguishable from arbitrary mutation, and
`without-caching!` is how you test around one.

### Renaming is the one thing the write gates cannot see

Both of these rules -- the module a namespace belongs to, and the tier it is
governed by -- are inherited from its **name**, and both are enforced when a
form is **written**. A rename changes the name without writing the forms, and
`ns_rename` rewrites its own callers, so those callers do not pass a gate
either. Nothing about the rename looks wrong afterwards.

So `done` asks again for you, scoped to what the episode actually moved:
`:tier-governance` when a namespace landed under a tier its forms cannot
satisfy, `:module-governance` when a relocation put a call outside a module
rule. `full_check` reports whatever stands across the whole store
(`:tier-layering`, `:module-violations`). Both are errors, not notes -- each
one is something a write gate would have refused outright.

One thing to expect on the module side: **the namespace reported is usually not
the one that moved.** Taking a namespace from two segments to three makes it
package-private, so it is the unmoved *caller* that is suddenly reaching in.
`module_extract` handles the hoisting for you and tells you which caller forces
each `^:export`; `ns_rename` does not, which is what these checks are for.

A scoped export breaks from the other end. `^{:export "billing.invoice"}` names
the *caller's* subtree, so moving the caller invalidates an export in a
namespace nothing touched -- possibly in a module you are not working on.
Re-point the string, or widen it to `^:export` if the var really is module
surface. Moving the *callee* breaks it too, more quietly: a scoped export names
exactly one subtree, so a var reached by two callers that used to share a
module needs a plain `^:export` the moment a regroup separates them.

### A rename reports the debt it leaves, because no gate can see it

`ns_rename` rewrites every caller's require and qualified references, and those
rewrites do **not** go through the write gate -- a coordinated changeset lands
as one delta rather than as N writes. So a crossing that would be refused
outright if you typed it is created silently instead. Nothing turns red, and
the first thing to mention it is a `done` some time later, reported against the
unmoved *caller* -- which never moved, and does not name the rename that
stranded it.

So the rename hands back `:module-debt`:

| Key | What it is |
|---|---|
| `:edges-needed` | crossings now undeclared, grouped to the `module_dep` calls to make |
| `:visibility` | calls that now reach a package-private namespace |
| `:cycles` | the edges `module_dep` will **refuse** |

Two things a hand count usually gets wrong, and this does not: every *caller's*
module needs an edge, not just the module you are moving; and `test-only` is
derived from who actually crosses, so a crossing only `-test` namespaces make
comes back `test_only true` even when the old edge was declared for production.
That is the same judgement `:overstated-edges` makes after the fact, offered
before it instead.

The loop is rename, read, declare, next rename. `:cycles` is the one to stop
on: it means the regroup as drawn cannot be declared at all, and it is far
cheaper to learn that at the first rename than at the tenth.

One more thing a rename leaves behind, and this one is silent even afterwards:
**qualified keywords**. `:billing.invoice/customer-id` survives
`billing.invoice` → `billing.statement` intact, because a keyword is not a
reference. Nothing breaks and nothing turns red -- the name just starts lying.
They come back under `:keyword` in `:left-behind`, spelled out, and they are
deliberately not rewritten: a qualified keyword can be a wire or storage key
something outside the store already holds, so each one is a decision rather
than a substitution.

### Why this axis

The tier is not an aesthetic judgement about functional style. It decides how a
thing has to be **tested**: external needs a separate JVM and temp directories,
internal needs a state reset between runs, pure needs nothing. That is also why
`^:external` on a test is the same vocabulary -- it marks a test that exercises
one.

Calls into an opaque dependency count as effectful. Name the caller with a
trailing `!`, or `deps_pure` the var, namespace or library, or tag the form
`^:reads` if it only reads (reads take no bang).

`:reads` and `:effects` are legacy spellings of `:internal` and `:external`;
they normalize on read.

## Platform

A third namespace-level declaration, with the same path scoping and
most-specific-wins rule:

```clj
module_platform {module "shop.contracts" platform ":cljc"
                 prompt "order schemas are shared with the browser"}
```

`:jvm` is the default and is what you have been writing. `:cljc` loads on the
JVM *and* compiles to JavaScript, which is where shared schemas and portable
logic belong -- the oracle verifies them for free. `:cljs` is browser-only,
never loads into the image, and is verified by the compiler instead of the
suite. See [the ClojureScript client](web/client.md).

`query_depends {modules true}` returns a `:platforms` map alongside the
manifest; anything absent from it is `:jvm`.

## Role

The fourth namespace-level declaration, same path scoping, same
most-specific-wins rule -- and the one that decides whether code SHIPS:

```clj
module_role {module "shop.lab" role ":instrument"
             prompt "load generator, run by hand before a release"}
```

`:product` is the default: the system runs this code, it materializes under
`src/`, and it goes in your jar. `:instrument` is for code a **human** runs by
hand -- a benchmark, a data-migration script, a seeding CLI, a one-off probe.

It is not a label. An `:instrument` materializes under `instruments/` instead
of `src/`, and that is the whole mechanism: your build script copies `src` by
name, like essentially every Clojure build, so the exclusion works without any
build tooling knowing what a role is. The generated `deps.edn` puts
`instruments` on `:paths`, so the code is still *runnable* from a materialized
tree -- it is out of the jar, not out of the project.

Declaring `:instrument` is **refused while product code requires the module**,
and the refusal names the callers. Otherwise your jar would carry a require to
a namespace it does not contain, and you would find that out at a consumer's
load time. A `-test` namespace requiring it is fine: a test does not ship
either.

The one thing slopp cannot check is the claim itself -- nothing in a store
distinguishes a benchmark from a scheduled job -- so `module_role` says so in
its `:unverified`.

## Boundary contracts

Public functions at a module boundary carry Malli schemas
(`^{:malli/schema ...}`), and the schema requirement is an opt-in per-form
write gate. At done time, generative checks run over changed, analyzable forms
against their schemas, so a contract that cannot hold gets caught by generated
input rather than by production.

The `slopp-style` skill covers how to shape code for this: functional core and
imperative shell, program-to-data, contracts at boundaries.
