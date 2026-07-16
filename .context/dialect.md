# Dialect & effects

## The gate (`slopp.edit`)

Every write passes the same dialect check (`edit/dialect-check`), by BOTH
entry paths — the single-form edit path via `parse-form` (exactly ONE top-level
form) and the whole-namespace import path (`ingest!`/`ns_create {:source}`) via
`edit/dialect-scan`, which runs it over every form of the ingested namespace.
The check —
- **D4:** `defmacro` rejected ("user macros are banned").
- **D3 denylist** (analysis defeaters): `eval`, `alter-var-root`, `binding`,
  `gen-class`, `definline`, `read-string`. Extensible — the list is a sample,
  grow it deliberately (and record here).
- **D5 — no hand-written `(declare …)`** (edit path only, `parse-form`): the
  pipeline OWNS form ordering and declares (auto-avoid-declare). A same-ns
  forward ref is resolved by reordering definitions above their callers, or —
  for a genuine cycle — by inserting a MARKED `^{:auto-declare "<why>"}
  (declare …)` itself. So a hand-written declare is always redundant and is
  refused with teaching ("slopp orders forms itself — drop the declare"). This
  is the FIRST prune of a human convenience the pipeline can fully own; it
  lives in `parse-form` (NOT `dialect-check`), so **imports (`dialect-scan`)
  keep their declares** and the pipeline's own inserts (built via the raw
  parser, not `parse-form`) are unaffected. See `.context/verification.md` S1b.

Import is gated identically to edit (fixed 2026-07 via self-host dogfooding):
before this, `ingest!` skipped the gate, so a host form could enter the store
UNMARKED and then be **frozen** — the edit path would refuse to modify its own
body (it contains a denylisted symbol). Now a host form can only enter already
`^:unsafe`, so imported code is never frozen. `ingest!` also returns its
`!`-warnings now (it used to swallow them). `dialect-scan` reports EVERY
offending form in one error (not just the first) — else a whole-ns import must
be re-sent once per host form, discovering them one rejection at a time (a
self-host loading finding).

Philosophy: keep **data dynamism** (open maps, loose args — the advantage the
live oracle makes safe); constrain **metaprogramming dynamism** (what defeats
machine understanding). The denylist is about the *semantic layer*, not the
CST (which can represent anything).

Note: the D3 bans apply to *authored* store code. The host and `slopp.rt`
legitimately use `alter-var-root`/`binding` as instrumentation machinery.

## The `^:unsafe` escape hatch (P4-deps M2)

A top-level form tagged `^:unsafe` (or `^{:unsafe true}`) **bypasses D3+D4** —
the Rust-`unsafe` move: the author asserts an obligation the analyzer can't
discharge (boundary work: calling into an opaque Tier-1 dependency, or the
handful of host forms that genuinely need `binding`/`alter-var-root`/
`read-string`). It is a **coarse, greppable** opt-out (blanket, not per-ban),
surfaced at read time as `:unsafe? true` on `query_symbol`. It relaxes ONLY
the dialect ban — the `!`-effect warning is orthogonal honest-labeling and
still fires. Implementation load-bearing point: `store/form-symbol` unwraps
`:meta`, so an `^:unsafe (defn f …)` stays NAMED and addressable (else it'd be
an anonymous `:meta` node). The marker round-trips render + checkpoint
normalize intact.

## External-dependency effect boundary (P4-deps M3)

A call into an opaque **Tier-1 dependency** is an effect anchor **by default**
(worst-case — slopp can't see the dep's body, so it assumes effect; Koka
`io`-top / gradual "unknown = top"). Mechanism: `index/effectful-vars`/
`effect-violations` take an `external-ns?` predicate + `pure-vars` set;
`edit/ns-warnings` builds `external-ns?` from `(:dep-ns store)` (the provided
namespaces from M4's surface) and `pure-vars` from `(:dep-pure store)`.
Narrow a false positive (e.g. a pure math/parse lib) with `deps_pure`
(a `:deps-pure` delta → `:dep-pure` set) at **var, namespace, or whole-lib**
granularity — a lib expands to every namespace it provides, so a wholesale-pure
dependency (rewrite-clj, clj-kondo) is narrowed in one call rather than
per-var; the anchor check treats a call as pure when `:dep-pure` holds the var
OR its namespace. NOTE: bang-named external vars
(`jdbc/execute!`) were already caught by `bang-target?`; the net-new surface
is **non-bang** external calls (`jdbc/query`, `json/write-str`). Still
WARNINGS, never rejections. Store-ns and clojure-stdlib calls are unaffected
(not in `:dep-ns`).

## `!`-effect checking (D6, `slopp.index`)

- A var is **effectful iff it transitively reaches an effectful leaf** through
  the clj-kondo call graph (monotonic fixpoint, cycle-safe).
- Leaf set (`effectful-leaves`): in-process mutation prims (`swap!`, `reset!`,
  `alter`, transients, agents/promises, ...) + external writes (`spit`,
  `delete-file`). Reads and non-determinism (`slurp`, `rand`, `now`) are NOT
  effects — `!` tracks *modification* (what causes reload staleness), not
  referential transparency.
- The rule: a **computed-effectful var that is NOT `!`-named** is a violation
  (name it `!`) — surfaced on every write (`edit/ns-warnings`) with the suggested
  name; fixing one is just `rename!`. Only this ONE direction is flagged
  (`index/effect-violations`), with two exemptions (self-host findings):
  - **`-main` is exempt** (like `deftest`) — an effectful entry point that is
    never bang-named by convention.
  - **A `!` is trusted, never flagged for removal.** The reverse direction
    (banged but the analyzer computes pure) is NOT reported: a `!` is a human
    assertion of effectfulness, and the call graph can't see interop/opaque
    effects (`.close`, a socket/JGit write), so demanding the `!` be removed
    would be wrong. (Consistent with `^:unsafe`/`^:reads`: human assertion wins.)
- **Known leak:** higher-order fns are effect-polymorphic and can't be soundly
  marked statically — runtime observation covers them.
- **Open (F7, needs user):** stdout (`println`) is currently NOT a leaf —
  matches idiomatic Clojure (print fns aren't bang-named) but sits oddly with
  "external writes." Recommendation on file: keep `!` = mutation per
  convention; if console IO matters, surface it as separate `:effects` info
  rather than a naming rule.

### `^:reads` — the per-form `!`-name override

D6 knows store-internal reads aren't effects (`slurp`/`rand`/`now` aren't
leaves). But the **M3 external boundary** treats an opaque-dep call as effectful
worst-case — so a *read* through a dep (`jdbc/execute-one!` on a SELECT,
`json/read-str`, `kondo/run!`) makes its caller "effectful" and D6 wants a `!`.
By Clojure convention reads take no bang (`slurp`/`deref`/`d/q`), so a form
tagged **`^:reads`** asserts exactly that — "I read external/mutable state but
am not a mutation" — and `edit/ns-warnings` drops its naming warning
(`edit/reads?`; surfaced as `:reads? true` on `query_symbol`). It is the same
greppable, human-discharged, self-limiting move as `^:unsafe` — but
**orthogonal**: `^:reads` touches ONLY the `!`-effect warning (not the dialect
gate), `^:unsafe` touches ONLY the dialect gate (not the `!` warning); a form
may carry both. (Self-host finding: slopp's own read-wrappers over jdbc/kondo —
`db/load-store`, `index/analyze`, … — are exactly this case.)

## Enforcement stance

Honest **labeling**, not capability restriction: the agent may write any
effectful code; the name must tell the truth. Nothing here rejects effects —
only lies about them (and even violations are warnings + auto-fixable, not
hard rejections).
