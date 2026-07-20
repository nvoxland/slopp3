---
name: slopp-style
description: "Design and write idiomatic, agent-friendly Clojure in a slopp store — functional core / imperative shell, program-to-data, boundary contracts, module architecture, locality of behaviour. Read before writing non-trivial code or making a design decision; the operational loop is the main `slopp` skill."
---

# Writing good Clojure in slopp

slopp rewards a specific shape of code — the shape that stays correct when the
one editing it (you) sees a narrow slice, not the whole codebase. These are not
style opinions; each rule names the slopp surface that checks it, or says plainly
that it's on your judgment. The operational loop (reads, writes, `done`,
milestones) is the main `slopp` skill — this is the *what to write*, not *how to
drive the tools*.

The organizing idea: **a pure function is a closed world.** Its whole contract is
args-in → value-out, with no hidden state to hold in mind — which is exactly the
slice you can reason about locally and the REPL can confirm in one eval. Push code
toward that shape; push the parts that can't be (I/O, state, the outside world) to
a thin edge.

## 1. Functional core, imperative shell

Keep decision logic **pure** (data transforms, no I/O, no ambient state) and
concentrate effects — DB, network, clock, randomness, mutation — in a thin
**shell** that fetches inputs, calls the core, then performs what the core
decided. Even better: have the core *return* the effects as data
(`{:db [...] :email {...}}`) and let the shell execute them, so the effect
decision stays pure and `=`-testable.

- **slopp checks part of this today:** a fn that transitively reaches a mutation
  or opaque-dep call must be `!`-named (the write result warns with a `:suggest`;
  `edit_rename` to fix). A `!` deep in what should be your pure core is the smell
  — it means an effect leaked inward. `^:reads` marks a read-through-a-dep that
  isn't a mutation; `^:unsafe` is the dialect escape hatch.
- **slopp ENFORCES it when you commit to it:** declare a module's purity tier
  with `module_purity {module "app.core" tier :pure}` (or `:reads`, or `:effects`
  for the periphery) and the write gate then HARD-REFUSES any form in that module
  that reaches an effect (`:pure`) or a mutation (`:reads`) — teaching you to move
  the IO to a periphery namespace. Tiers are opt-in: an undeclared module is
  `:effects` (ungated). Declare the tier once a module is genuinely a pure core;
  the gate keeps it that way. Read tiers with `query_depends {modules true}`.
- **On your judgment (no gate yet):** whether logic that *could* be pure was
  actually pushed to the shell. Ask of any function: "could this be args-in →
  value-out?" If yes, make it so and test it by value.
- Determinism belongs in the core: pass `now`, a random seed, config, and env
  *into* functions rather than reading them — a deterministic core keeps the REPL
  oracle and the affected-test re-runs stable. A `:pure` module **enforces** this:
  reaching `rand`/`slurp` (core non-determinism) is refused, not just mutation —
  `:pure` means referentially transparent. (Interop non-determinism like
  `System/currentTimeMillis` is invisible to the analyzer — still your judgment.)

## 2. Program to data, not to types

Prefer plain maps / vectors / sets and namespaced keywords over bespoke types.
Data is transparent — you (and `query_eval`, and the trace) can print a value and
see everything, with no class hierarchy to reconstruct. Write many functions over
few data shapes, not many types each hiding state. Avoid `deftype`/`gen-class` for
domain data (they also cost you the tracer — see the main skill's polymorphism
notes).

- **slopp leans INTO this deliberately** (decision D2): loose args and open maps
  are an *advantage* the live oracle makes safe for a limited-context agent. Do
  not reach for rigid closed shapes to feel safe.

## 3. Open maps + the key-hygiene that makes them safe

A function should read only the keys it needs (`(:order/total m)`, destructure the
few it uses) and **tolerate extra keys it doesn't**. Grow shapes by *accretion*
(add a key, relax a requirement — provably safe to unseen callers), never by
*breakage* (remove a key, newly-require one, narrow a type). This is Hickey's
"require less, provide more" — it's what lets you safely edit a slice without
surveying every caller.

The one hazard it opens: a mis-spelled or renamed key (`:user/id` vs `:user/idd`)
silently reads `nil` and nil-puns downstream, where you can't see the producer. To
keep accretion's safety without the trap:

- **Namespace a key that CROSSES FORMS** (`:invoice/due-date`) — so a reader of
  one form learns where the key came from without opening another. **Not every
  key.** Unqualified is the Clojure default and the right choice for a map that
  lives inside one form; the community namespaces only for a specific reason
  (spec's flat global registry, data crossing systems), and `:req-un` exists
  because bare keys are what real code has. slopp is deliberately *stricter at
  boundaries* for one agent-specific reason: **every argument for bare keys
  assumes context disambiguates** — a human has the producer twenty lines up,
  you have one form. Measured in slopp's own store, `:session` meant an nREPL
  session id *and* the session atom; `:dir` meant three things; `:values` meant
  a config entry *and* a return map. Each was found by tooling, never by
  reading.
  - **Scope, so you don't over-reach:** the gate
    (`gates`/`require-namespaced-keys`) covers a **module-external defn's
    arglist destructuring** and nothing else — not map keys generally, not
    return maps, not private fns, not `(:k m)` body reads. Its finding list IS
    the worklist (`cleanup {all true}` reports it under `:gates`); `query_rules`
    states the same scope. Don't infer a broader mandate: measured across 674
    unqualified keys in one real store, 445 appear in more than one form, and
    the most-shared are Clojure syntax (`:require`, `:as`) and universal result
    vocabulary (`:error` in 119 forms) — where ONE shared spelling is right and
    namespacing is pure loss.
  - **What it does not buy:** namespacing does *not* prevent the typo above —
    `:user/idd` nil-puns exactly like `:idd`. That is the next bullet's job.
    Namespacing buys RESOLVABILITY (one key, one meaning, one graph answer),
    not spelling safety.
- Reuse an existing key spelling rather than minting a near-duplicate:
  `query_vocabulary {ns "invoice"}` lists the `:invoice/*` keys already in use,
  most-used first — reuse before you coin. `done` catches a slip anyway (a
  near-duplicate is flagged as a `:key-typos` advisory), but reuse-first prevents
  the synonym drift the advisory can only detect after the fact.
- Validate/normalize nil at the boundary where data *enters*, not deep inside.

## 4. Contracts live at the module boundary

The place a schema or a docstring earns its keep is the **module-external
(exported) surface** — the one point where a caller can't see your body. Internal
helpers need no ceremony; the module editing them sees the whole module.

- **Docstring + namespaced arg keys:** every exported fn should carry a docstring
  (a write leaving public surface undocumented warns once — add it) and namespaced
  domain keys in its arg maps (the key-hygiene of §3 — `{:some.ns/keys [id]}` over
  bare `{:keys [id]}`), so a caller reads the contract without reading the body
  (`query_depends {modules true, on "x.y"}` browses a module's offered surface).
  This is the arglist rule of §3 and it stops there — a docstring on an exported
  fn is always right, but the key rule applies to the *arg map you destructure*,
  not to everything the fn touches.
- **An opaque HANDLE is not a boundary contract.** If a fn takes a map the caller
  never builds — one your own constructor returned, and the caller only passes
  back — you have two honest options: qualify its keys (they then name their
  origin in every slice, which is worth it once the handle is read in several
  places), or don't destructure it in the arglist at all and read what you need
  in the body. What is *not* honest is destructuring it with bare keys, which
  advertises internals as a contract the caller is expected to know. Mark the
  constructor `^:live-handle` if a long-lived caller holds the result — changing
  such a map's key shape is a live-state migration, not a code change.
- **Open malli `:=>` schemas (shipped):** put the contract in the defn's NAME
  metadata — `(defn ^{:malli/schema [:=> [:cat [:map [:id :int]]] :string]} f
  …)` — mirroring `^:export`. Keep maps **open** (pin only the keys you depend
  on; extra keys tolerated — accretion). It pays for itself: at `done` a written
  `:=>` schema is **generatively oracle-checked** against your live impl, so a
  schema that drifts from the code is a red `:schema-drift` finding, not a silent
  lie. Schemas are OPTIONAL to write — but writing one on a module-external
  map-arg fn is the highest-value place, because that's the shape a slice-limited
  caller can't infer. A store can even REQUIRE them there (`config_file {path
  "gates" key "require-boundary-schemas" value "true"}` → the `schema-refusal`
  write-gate); off by default.

## 5. Module architecture: cohesion places, export exposes

(Enforced — the main `slopp` skill has the operational rules.) Design the
dependency, then write the code: cross-module calls need a declared edge
(`module_dep`), cycles are refused, deeper namespaces are package-private.
**Cohesion decides WHERE a form lives; `^:export` decides WHO sees it — they are
independent.** Keep pure-domain namespaces separate from effectful/wiring ones so
the shell/core split is visible at the module grain, not just per-fn. Check the
whole shape in one call: `query_depends {modules true}` — `:layers`, `:cycles`,
`:unused-edges`.

## 6. Locality of behaviour: put meaning in the name and the key

Optimize every form for someone who sees *only that form* — literally you on your
next edit. That's the principle behind slopp's naming conventions; follow them and
a reader knows an effect from a pure call without chasing definitions:

- `!` suffix = mutates / performs effects (**enforced**, D6).
- `?` suffix = predicate returning a boolean.
- `*earmuffs*` = a dynamic var (and prefer threading a value as an argument over
  `binding` action-at-a-distance — `binding` is dialect-banned anyway).
- No ambient mutable state: don't `def` an `atom`/`ref` as a global for logic —
  pass state in. A top-level mutable `def` is spooky action a slice-limited editor
  can't track.

## 7. Return data, don't throw for expected outcomes

For expected alternate results (not-found, validation failure) return data — a
result map, an `:error` key — so control flow stays visible in the slice you're
editing and composes in a pipeline. Reserve `throw` for the truly exceptional, and
when you do, carry structured data with `ex-info`/`ex-data`, never a bare
`Exception`. Data results are `=`-testable at the REPL; a throw lands somewhere you
can't see.

## 8. Work REPL-first — and resist the imperative default

Models (measured: Nubank's MultiPL-E, Clojure/conj 2024) drift toward imperative,
non-idiomatic Clojure — a `doseq` + `atom` where a `map`/`reduce`/`reduce-kv` or a
threading pipeline (`->`/`->>`) fits, or piling on complexity when a test goes red.
Counter it deliberately:

- Reach for `reduce`/`map`/`into`/`transduce` and threading pipelines over
  loop-and-mutate. One linear pipeline is easy to read, edit one step at a time,
  and test incrementally.
- **Let the REPL be the arbiter, not your confidence.** Before (or right after)
  writing a fn, exercise it: `query_call {sym args}` or `query_eval "(...)"`. The
  oracle is ground truth — it's the single most-cited reason Clojure suits agents;
  use it instead of reasoning about what code "should" do.
- When a test goes red, simplify toward the data transform — don't add machinery.

## Checkable vs. judgment — a quick map

**`query_rules` shows exactly what's enforced in THIS store, at what severity, and
how to discharge each rule** — a project can dial any rule `:off`/`:advisory`/
`:error`/`:refuse` with `config_file {path "rules" key <rule> value <severity>}`,
so treat the table below as the defaults and `query_rules` as the truth here.

**A rule's SCOPE is part of the rule.** When you're about to apply one, ask what
the gate actually FLAGS — `cleanup {all true}` reports every gate's findings, and
that list is the worklist — not what feels in its spirit. Prose invites
interpretation and interpretation drifts, usually stricter: reading "namespace
your domain keys" as a mandate turned a twelve-form gate finding into an attempted
store-wide key migration, including keys whose rename is genuinely dangerous.
Where a rule's boundary matters it is pinned by a test asserting what it does NOT
cover, so drifting either way turns the suite red.

| Rule | slopp enforces today? |
|---|---|
| Effectful fn is `!`-named | **Yes** (D6, write warning + `:suggest`) |
| No macros / eval / dynamic-var action-at-a-distance | **Yes** (dialect gate) |
| Cross-module edge declared; no cycles; cohesion vs export | **Yes** (module gate) |
| Public surface documented | **Yes** (write advisory) |
| Dead public surface removed | **Yes** (`done`/`commit_point` gate) |
| Pure core / effects at the edge (locale) | **Yes, when the module declares a tier** — `module_purity {module :pure/:reads}` hard-refuses effect-reaching writes; **`:pure` also forbids non-determinism** (`rand`/`slurp` — referential transparency) |
| Boundary schemas on exports | **Yes** — a written `:=>` `:malli/schema` is generatively oracle-checked at `done` (drift → red `:schema-drift`); opt-in `require-boundary-schemas` gate mandates them on module-external map-arg fns |
| Key hygiene (namespaced + no near-dups) | **Yes** — `done` flags likely-typo keys (`:key-typos` advisory); opt-in `require-namespaced-keys` gate refuses bare `{:keys}` at a boundary; browse/reuse with `query_vocabulary` |
| Accretion over breakage | **Advisory** — `done` flags a narrowed boundary contract (`:breaking-changes`: arity / `:=>` schema-key / visibility, vs the last-done baseline) |
| Return data over a bare `throw` at a boundary | **Advisory** — `done` flags a module-external fn throwing a freshly-constructed non-`ex-info` exception (`:bare-throw`) |
| No ambient global mutable state | **Advisory** — `done` flags a global `(def _ (atom/ref/agent …))` (`:ambient-state`) |
| `?` / `*earmuffs*` predicate / dynamic-var naming | Judgment (no gate yet) |

The judgment rows are yours to hold. Where a rule IS gated, the code that reached
you already passed it — so spend your attention on the judgment rows and on
whether a *could-be-pure* function actually was made pure.
