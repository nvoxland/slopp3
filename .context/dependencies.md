# External dependencies (Tier 1) — the trust-tiered model

slopp's owned image is otherwise **bare** (Clojure + nREPL). A store declares
its own external libraries in a **manifest**, so store code can `(:require …)`
them — the prerequisite for slopp hosting its own source. The whole design is
two **trust tiers** plus a greppable **`unsafe`** boundary (P4-deps; see
`decisions.md` for the decision record).

## Two tiers

- **Tier 0 — authored store code.** Full guarantees: dialect gate
  (`dialect.md`), effect analysis, hot-reload, verified oracle.
- **Tier 1 — external dependencies.** Declared, API-surface analyzed, but
  **bodies opaque** — slopp never sees inside a jar. So their effects are
  assumed **worst-case**, and they get a separate, weaker set of guarantees.

The design spine (from the prior-art research): an opaque dependency edge
**defaults to worst-case**, may be **narrowed by declared metadata / an
assertion** (a proof obligation), and where behavior must be trusted, is made
**visible/greppable**. Rust `unsafe`, Koka's `io`-top, Unison's
content-addressed deps, and GraalVM's declared-or-traced metadata all inform
it.

## The manifest (`slopp.api` `deps_*`, `slopp.store`, `slopp.db`)

- `deps_add {lib version|coord}` / `deps_remove` / `deps_list`. The manifest
  is `{lib coord}` (deps.edn coordinates), kept CLEAN (tools.deps-legal).
- It is a **tracked delta stream** (`:deps-add`/`:deps-remove`, state-carrying
  — they reconstruct `:deps` on foreign-sync, ride branches/merge), materialized
  to a `meta` `'deps'` row for O(1) load. `:deps` is on the store VALUE. On
  merge, a same-lib **version divergence auto-resolves to the newer mvn coord**
  (numeric compare via `slopp.semver/newer?`, so 1.10 > 1.2) with a resolution
  note; only incomparable coords (mvn vs git sha) stay a conflict.
- **Reaches the image classpath** at every launch (`-Sdeps` in
  `repl/default-cmd`; `image-with-deps!` reconciles the bare warm-spare). A
  new coord **hot-loads with no restart** (`repl/add-libs!`, Clojure 1.12);
  `deps_remove`/downgrade restarts (a jar can't unload).
- **Feeds a complete generated `deps.edn`** (`build/deps-edn`) so a `build!`ed
  or git-cloned project is runnable. Empty manifest is byte-identical to the
  pre-manifest output (the `ours?` guard holds); `*print-namespace-maps*` is
  bound OFF for byte-determinism.

## Surface analysis (`slopp.deps`, M4)

On `deps_add`, slopp learns what a dep exposes without reading jars: isolate
the dep's OWN jars (its classpath minus a clojure baseline — a **classpath
diff**), run clj-kondo over them → **provided namespaces** + per public var
`{arities, varargs-min, doc, macro?}`. **Content-addressed**: computed once per
`coord@version`, memoized in-process AND in the durable `dep_surface` table.

## The effect boundary (M3)

A call into a Tier-1 dependency is an **effect anchor by default** (worst-case:
slopp can't see the body). `index/effectful-vars`/`effect-violations` take
`external-ns?` (built from `:dep-ns` — the provided namespaces from M4) and
`pure-vars` (`:dep-pure`). A form calling a non-bang external var
(`jdbc/query`, `json/write-str`) is flagged effectful (name it `!`) unless the
var is asserted pure via `deps_pure`. Bang-named external vars (`execute!`)
were already caught. Store-ns and clojure-stdlib calls are unaffected. Still
**warnings, never rejections** — honest labeling.

**`:pure` narrows at three granularities** (so a wholesale-pure library isn't
enumerated var-by-var — self-host dogfooding finding: slopp is built on
rewrite-clj + clj-kondo, both pure, and per-var `:pure` flooded its own code
with warnings). `deps_pure {target}` accepts a **var** (`clojure.data.json/write-str`),
a whole **namespace** (`clojure.data.json` — every var in it), or a manifest
**lib** (`org.clojure/data.json` — `api/deps-pure!` expands it to every
namespace the dep provides via `:dep-ns`, recording one `:deps-pure` delta per
namespace in a single commit). `:dep-pure` therefore holds symbols at var AND
namespace granularity; the anchor check (`index/effectful-vars`) treats a call
`t` as pure when `:dep-pure` contains `t` OR its bare namespace. All three
persist/branch/merge identically (the delta already carries an arbitrary
`:sym`).

`deps_pure` narrows a **dep** (it IS pure); when instead a *caller* reads through
an effectful dep that can't be narrowed (a `jdbc/execute-one!` SELECT — reads and
writes share the same bang-named var), tag the CALLER `^:reads` (per-form) so its
`!`-naming warning is dropped. See `dialect.md`.

## Impure deps & testing (M5 + convention)

- **`^:integration` test tier.** Tag a deftest `^:integration` (on the test
  NAME) and the **fast per-write path skips it** — an external-system test (a
  DB behind a capability) doesn't fire on every edit, and a red one never
  blocks an edit. `test_run`/`done`/`commit_point` run them.
- **Capability injection (convention, NOT enforced).** Keep the functional
  core pure and push effects to a thin shell: **pass the DB connection / clock
  / HTTP client in as a value (a capability)** rather than reaching for an
  ambient global. Then the pure core stays testable and effect-honest, the
  oracle can inject a fake at the boundary, and `^:integration` covers the
  shell. This is the object-capability / functional-core–imperative-shell
  pattern; slopp *recommends* it (matching the dialect stance: honest
  labeling, not capability restriction), and the greppable `^:unsafe` +
  `:pure` annotations are where the boundary obligations are discharged.

## Native-image compatibility (M6)

Each dep's jars are scanned for `META-INF/native-image/**` (GraalVM
reachability metadata) → `:declared`/`:none`, cached in `dep_surface.native`.
`build! :main` **warns** on metadata-less deps (`:native {:warnings
:metadata-missing}` — may need a tracing-agent run) and **refuses** a dep on
the (tiny, currently empty) `native-incompatible-deps` denylist without
`:force`. Best-effort — a missing manifest is a warn, never a guarantee.

## `^:unsafe` — the dialect escape hatch (M2)

Orthogonal but part of the same boundary story: a top-level form tagged
`^:unsafe` opts out of the D3/D4 dialect ban (macros, `binding`, `eval`,
`read-string`, …) — the Rust-`unsafe` assertion for boundary code the analyzer
can't vet (calling an opaque dep, or the ~12 host forms). Greppable
(`query_symbol` shows `:unsafe?`), does NOT relax the `!`-effect warning. See
`dialect.md`.

## Limits / follow-ons

- The `native-incompatible-deps` denylist is empty (grow deliberately); no
  GraalVM reachability-**repository** lookup yet (network).
- Effect analysis of dep bodies is worst-case (opaque), not a Capslock-style
  bytecode call-graph — `:pure` is the manual narrowing.
- `:pure`/`:dep-ns`/native verdicts are per exact `coord@version`; a version
  change re-analyzes.
