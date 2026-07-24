# Design disciplines — the cores behind the frictions, and how to avoid the wrong turns

For whoever builds slopp. This is the layer ABOVE the individual `D-*`
decisions: it names the recurring ROOTS behind ~25 dogfooding frictions, the
disciplines that dissolve them, and the wrong directions that keep getting
re-walked. When a new friction appears, find its core here before writing a
fix — most fixes that only address the instance leave the class open.

Raw material: `ideas/the-patterns-behind-every-failure.md`,
`ideas/compensating-behaviors-are-slopp-bugs.md`,
`ideas/dogfooding-agent-frictions.md`, and the wave logs. Prioritized fixes:
`ideas/root-cause-fix-plan.md`.

## The generator: one asymmetry

slopp poured its entire "make wrongness loud, cover what the sliced agent
cannot see" investment into the **write boundary** (dialect → compile →
cold-load → lint → tests; a failed gate refuses). It left the
**read / analysis / report** boundary as best-effort — *while making reads
MORE trusted than on any file system*, because the write gates earn justified
trust and the agent spends it on every slopp surface without double-checking.
That asymmetry generates most of the friction. It shows up in correctness
(confident wrong values), in completeness (gates with no matching report), in
cost (reads are 52% of the token bill and got the least optimization, P8), and
in reachability (real capabilities one registry entry from unreachable).

The four cores below are that asymmetry seen from four angles.

## Core 1 — reads inherit unearned trust

**Root.** The analysis layer has none of the write layer's discipline: it
returns `nil` and shrugs where the write path refuses. Of ~20 project failures,
roughly TWO threw; the rest returned a confident, well-formed, wrong answer (the
wrong element deleted, zero tests reported green, a rule that silently never
fired). Clojure's nil-punning + open maps + positional destructuring turn a
wrong structural assumption into a plausible value, not an error — and a
sliced-reader agent cannot detect confident wrongness by construction.

**Discipline.** Any surface that can be incomplete must say so in the SAME
breath. Never let "I could not check" and "I checked and it was fine" share a
representation. This is now a decision: **D-surface-honesty**. It is the
finding-grade `:info` rule of **D-rule-grounding** raised to surface grade.

**Tells that you are re-creating this core:** a report that can under-count
silently; a composite that gained a field nobody found (P9 — capability existing
≠ capability found; discoverability lives in the SKILL and the RESULT, not the
feature); a tool argument that is dropped rather than refused when unknown (the
`dry-run` flag that evaporated and ran the real operation — "the most dangerous
friction of the project").

## Core 2 — one relationship is first-class; the rest rot

**Root.** THE reference graph (`slopp.edit.refs`) is the crown jewel —
architecture.md calls it "the single source of truth for who references what."
But it was a **var** reference graph, and every other relationship the code
expresses got a point-fix or nothing: keyword contracts read via
`{:keys [...]}` (invisible — the key is computed from a symbol), teaching prose
(docstrings/tool-descriptions/refusal text naming moved vars), coverage edges
(test → form), and the kernel copies (`rt`/`boot` as file + store + jar). Each
looked like a different problem, so the one proven pattern — *one canonical,
provenance-carrying edge set, every consumer queries it, nobody re-derives* —
was not reached for.

**Discipline.** A new relationship kind is a new PRODUCER into the one graph,
never a private re-integration. And the load-bearing sequencing rule, measured:
**fix the ANALYSIS before restricting the LANGUAGE.** Three of five proposed
dialect restrictions died once the analysis saw better
(`ideas/dialect-candidates-human-conveniences-that-hurt-agents.md`) — a gate
flipped on a graph that cannot see half its subject produces exactly the
confident wrong answers Core 1 warns about. The general form:
**"Any relationship the system lets you express, it must be able to see."**

Largely discharged for keywords (destructuring refs now carry
`:via :literal|:destructuring`, so `query_depends` on a key is complete) and for
prose (`stale-reference-check`, the tool-name-existence test). Still open:
coverage edges as one graph query with per-edge `:via`, and kernel/config
parity. Same move, same payoff.

## Core 3 — self-hosting is a distorting lens

**Root.** Dogfooding is the standing practice, so the loudest pains an agent
feels are self-host artifacts, and they read as more product-broken than they
are. A keyword-shape change bricks the session because slopp holds live handles
across calls and edits its own running image (`undo`/`restart` both work THROUGH
the handle they would repair) — an ordinary red in a user's project. Hardening
the dialect gate makes analysis forms that NAME banned symbols as data
un-editable — a self-referential property no user codebase has. A meaningful
share of "full_check is slow" (P2) was self-inflicted usage of a tool the
decisions say nothing forces.

**Discipline.** Scope every dogfooding friction as **self-host-only vs
user-facing** before it enters the roadmap — one label, the cheapest guard
against tilting the work toward failure modes the actual customer never hits.
Dogfooding is right (it found all of this); the discipline is labeling, not
doing less of it.

## Core 4 — the agent is an unreliable narrator, correctable only from outside

**Root.** Every confident wrong diagnosis was internally consistent, so
re-reading the reasoning surfaced nothing; every correction came from a red
test, a gate, or the user. Self-assessment is systematically too generous
exactly at the moment of declaring done — when it is least checkable. Each
"I always check X now" habit is the agent hand-patching a hole the system
should own: **the habit is the bug report**, and six of nine such habits traced
to one missing guarantee (a surface that reports success without checking).

**Discipline.** The sliced agent cannot be its own completeness backstop, so the
answer to a friction is never "try harder / add a skill line" — it is *make the
system answer the completeness question.* `cleanup {all true}`, the rule
self-test fixtures, and the shared form accessors are the model: each replaced a
habit with a guarantee. **A rule that relies on remembering is not a rule** (the
"repro can be too minimal" and "every assertion observed failing" lessons in the
`slopp` skill are the exceptions that earn a skill line — they are irreducibly
about how the agent writes tests, and even those want a static/mutation backstop).

---

## Disciplines catalog (the standing rules)

Several are already `D-*` decisions; cited so this doc stays the index, not a
second copy.

- **Make incompleteness explicit.** `:unverified` / `:partial` / `:coverage
  :none` / `:via`. → **D-surface-honesty**.
- **Ship every finding; grade the ones that shouldn't flip status.** A rule with
  a hidden finding class has unmeasurable precision. → **D-rule-grounding**.
- **A rule's predicate must be a ROLE (declared or grammatical), never a
  coincidence** (name-present-somewhere). → **D-rule-grounding**.
- **A metric/rule must only count findings someone can discharge.** Withdraw a
  rule at high false-positive rate rather than ship it to look thorough (the
  `:positional-form-access` guard, pulled at 4–5/5). → **D-rule-grounding**.
- **One question tiers a lint: could a form legitimately look like this
  MID-EDIT?** No → `:error`; yes → `:warning`; worthless → `:off` with the
  numbers. → **D-kondo-config**.
- **Never warn about what a tool can fix** (sort the requires, don't lint them).
  → **D-kondo-config**.
- **Gate and report read ONE declaration.** A second declaration of "what
  blocks" is a second place to disagree, silently. → **D-kondo-config**,
  **D-rule-grain**.
- **Every gate slopp enforces on a write should be readable as a REPORT over
  existing code.** The gates are predicates already; for a modernization/review
  pass the report is the whole job. (`ideas/modernization-sweep-friction.md`
  I2/I3.)
- **Prevention > detection.** `query_vocabulary` (stop key-invention at write
  time) beat the after-the-fact near-dup advisory. For every detection gate ask:
  what is the write-time prevention surface?
- **Verification checks REALITY, not intent** — the committed store, a cold
  load — not what an op claims it did. (`ideas/postcondition-reality-checks.md`.)
- **A refactor moves NODES, never round-trips through `sexpr`** (which models
  neither metadata, comments, nor reader tags — lossy in ways that compile
  fine). Dropped `^Repository` hints → reflection; earned twice.
- **"Zero current violations" is NOT grounds to make a rule blocking.** Every
  case the rule CAN fire on needs a way out, including cases that don't exist
  yet (`breaking-changes` at `:error` was undischargeable for
  accidentally-public surface).
- **Discoverability is in the SKILL and the RESULT.** Adding a field to a
  composite does not make it found (`report`'s `:intents`, twice ignored).
- **Measure MECHANICAL changes on the deterministic wire-cost meter; reserve
  lifetime evals for BEHAVIORAL questions at n≥3.** A per-step delta off ONE
  lifetime run is noise (steps you never touched swung ±30%). (P10.)

## Wrong directions (measured dead ends — don't re-walk)

- **The warm image pool / any scheme that RESCHEDULES boot work.** Built end to
  end and reverted at zero measured gain (172s vs 183s, inside load noise). At 4
  shards the cores are saturated, so a background boot has no idle CPU to hide in
  — it steals cycles from the running test. Only schemes that REDUCE work pay
  (`full_check {affected true}`, `spot-run!`). → `ideas/full-check-is-slow.md`,
  `ideas/verdict-cache.md`. **Measure first; prefer work-reducing over
  work-rescheduling.**
- **Restricting the language before fixing the analysis.** See Core 2. The
  restriction that looks necessary usually stops being necessary once the blind
  spot closes; enforced first, it bakes a permanent verbosity tax for a bug
  fixable in one namespace.
- **Fixing the instance and calling the class done.** Almost every friction log
  reads "fixed this one, the general bug is open." `fn-arglists` fixed one
  multi-arity gate; the class recurred. One accessor bug fixed; the same class
  bit `contract-drift` a day later. The meta-lever of this whole file: **spend
  on the chassis (registry, canonical edge set, shared accessors) that converts
  instance-fixes into class-fixes.**
- **Answering a friction with a discipline where a guarantee is needed.** Core 4.
  A skill line the agent will forget (and did, two hours after writing it) is not
  a fix. Escalate to a static rule, a shared accessor, or a gate.
- **A byte-identity guard for the kernel copies.** `render-ns` drops blank
  lines and the store copy legitimately carries markers; identity is not even
  the goal. The honest invariant is surface + behaviour parity, checked by a
  build-to-temp-dir diff (`ideas/rt-is-duplicated-file-and-store.md`).

## The one test to apply to any new construct or gate

> Does it let code express a relationship the tools cannot SEE — or does it let
> a surface report success it did not earn?

That is the same test that banned macros and `eval`, generalized. A construct
that only saves typing is not worth an invisible edge; a surface that cannot
say "I didn't check" is not worth its trust.
