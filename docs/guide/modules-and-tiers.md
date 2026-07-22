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
view cannot see), and standing debt.

Layers and cycles are computed over production edges only. A `-test` namespace
folds into its subject module, so its fixture dependencies would otherwise
manufacture cycles that do not exist in production.

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
the code already there and refuses a tier the existing forms exceed.
Undeclared means `:external`, which means ungated.

Once declared, the write gate hard-refuses a form that exceeds the tier. Tier
layering is itself a graph property: the core is not allowed to depend on the
shell.

Every memo must go through `slopp.cache`. That is what keeps `:internal`
checkable -- an ad-hoc atom is indistinguishable from arbitrary mutation, and
`without-caching!` is how you test around one.

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

## Boundary contracts

Public functions at a module boundary carry Malli schemas
(`^{:malli/schema ...}`), and the schema requirement is an opt-in per-form
write gate. At done time, generative checks run over changed, analyzable forms
against their schemas, so a contract that cannot hold gets caught by generated
input rather than by production.

The `slopp-style` skill covers how to shape code for this: functional core and
imperative shell, program-to-data, contracts at boundaries.
