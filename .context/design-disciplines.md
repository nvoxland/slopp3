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

Cores 1–4 below are that asymmetry seen from four angles. **Core 5** is a
different axis (2026-07-24): not a category of mistake but a category of
CITIZEN — what the form-shaped machinery does not cover. **Core 6** (2026-07-25)
is a category of PLACE — the boundary, where verification stops. **Core 7**
(2026-07-26) is a category of INTENT — what the agent means to do, against
write verbs named for where they write; and **Core 6b** is Core 6's second
half, promoted once it turned out not to be boundary-specific.

Cores 6b and 7 were both derived by 5-whys over the OPEN frictions and then
confirmed against the RESOLVED ones, which is where the evidence is stronger:
each had been paid for roughly fifteen and seven times respectively, always as
an instance, never as a class. That is the signature worth learning — **a class
being retired one instance at a time looks like steady progress and reads, in
`ideas/done/`, like a list of unrelated fixes.**

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

**Sharpening (2026-07-29): nothing asserts that a check RAN.** Every gate here
reports what it FOUND; none reports its own population, so a check whose scope
collapsed to zero is indistinguishable from a check that passed. Four instances,
three of them among the worst defects recorded:

- `full_check`'s in-image tier ran ZERO tests in any store holding a `:cljs`
  namespace, and reported green — the tracer walks `ns-interns` over what it is
  handed, that throws on a namespace the image cannot hold, and the exception
  came back as text where a summary was expected
  (`ideas/ui-split-frictions.md` item 24).
- `slopp-prose-never-names-a-tool-that-does-not-exist` scanned an empty store
  for its entire life, passing on a population of zero.
- `done`'s `:test-status :none` conflating "nothing was testable" with "no news"
  (item 14).
- A test that `assoc`s the value it then reads back, so it passes whatever the
  production code does (item 23).

The representation half of this — never letting "could not check" and "checked,
fine" share a slot — was the original discipline and is necessary. It is not
sufficient: it presumes the check ran at all. **So: a verdict should carry its
population.** A test run says how many tests were in scope and how many ran; a
scan says how many forms it examined; a guard with a detector proves the detector
still fires (the `:fires-on` discipline, generalised beyond rules).
`session/ran-nothing` does this for one runner by construction — an absent
summary becomes `:error 1` carrying what the runner actually said, so a run that
did not happen cannot read as a run that found nothing.

**A FIXTURE'S FAKE narrows the population, and nothing reports the narrowing.**
Three defects in one wave were each hidden by something that could not fail, and
the third was the test written to catch the first two: a restart test whose fake
framework had no third-party requires passed green against a bug that was
precisely "the vendored framework cannot resolve its own requires". The real
framework requires garden, hiccup, cheshire and http-kit; the stand-in required
nothing. Stated by the slopp-ui agent, who found it:

> A fake that is simpler than every real member of the population it stands for
> is a check that has quietly narrowed its own population, and nothing reports
> that narrowing.

That is the same shape as a scan over an empty store or a `:none` verdict, but
harder to see, because the fixture LOOKS like the thing. The question to ask of
any stand-in: **what property do the real members have that this one does not?**
If the answer is "the one under test", the check is decorative. Cheap tell: a
fake with no dependencies, no failure modes, and no edge cases is standing for a
population where all three are the interesting part.

**The exclusion-list tell, and it is mechanical.** When a check concludes from
absence, the innocent causes accumulate as a hand-written list of negations —
and that list is usually a fact some graph already carries. `review-scan`'s
`unused` predicate hand-derived five markers from form metadata one line after
filtering the reference graph's own `:via :declared` edges, which are exactly
those five (`:entry-point :unused-ok :web-endpoint :web-read :web-effect`,
measured). The cause was one number serving two questions — "how many in-store
forms call this" (where excluding declared refs is correct) and "is anything
expected to reference this at all" (where it is not). **So: when you add a member
to an exclusion list, check whether the thing you are excluding is already data
in something you just filtered.** Full analysis and the fix:
`ideas/one-derivation-two-questions.md`. Prior art for how bad this gets:
`marker-registry`'s docstring counts seven places that kept their own hardcoded
marker list before it existed.

**And the generator sat at the TRANSPORT, one layer below everything above.**
`repl/eval!` kept only `:value` from the nREPL response; an eval that threw
produces no `:value` (the exception arrives as `:err`/`:ex`), so a throw returned
`[]` — identical to an eval that returned nothing. Every caller doing `(first …)`
got nil for both. The `ns-interns` exception that hollowed out the in-image tier
did not vanish somewhere subtle; it vanished HERE, and so would the next one.
The lesson for this core: when an honesty fix reports **"I have nothing to tell
you"** — `:actual ""` was the literal output of the first fix — that is not the
end of the diagnosis, it is a pointer one layer down. Silence has a source, and
it is usually a place where an error channel was dropped in favour of a value
channel.

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

## Core 5 — the form is the unit, and everything that is not a form is unmanaged

**Root.** slopp's central bet is that the top-level form is the unit of
editing, storage, hot-reload, verification and provenance. It pays off so
completely that it hides its own edge: measured over 1,200 deltas, **349 writes
cost 237s of verification, median 0s.** Forms get an id, provenance, impact
analysis, gates, verification, history and a permalink. Four other citizens
participate in the program's meaning and get none of it:

| Citizen | Missing | Seen as |
|---|---|---|
| **Declarations** (tier, platform, module edge, capability) | verification when the governed POPULATION moves; a retract verb | a tier silently tightened by a rename, green in `full_check` |
| **Occurrences** (a name as string, convention, or key) | one canonical occurrence set; a report of what was left | renames that rewrite symbols, miss strings, and say nothing |
| **Copies** (server image, kernel file, materialized dir, client context) | a version stamp ON THE VERDICT | a stale `--live` server producing a false red |
| **Transactions** (composite ops) | verification at the transaction, not the verb | N × (fresh image + full verify) for one logical change |

**Discipline.** Read this as Core 2 generalized from *relationships* to
*citizens*, with the same test: **does this thing participate in the program's
meaning? Then does it have an identity, a provenance and a verification — or is
it a key in a side map?** The recurring failure is not a bad fix, it is a fix
that teaches ONE verb about ONE non-form thing while the class stays open.

**Tell that you are in this core:** the fix you are about to write is a special
case inside a verb (`ns_rename` should also carry X; `module_purity` should also
check Y). That is the instance. The class fix gives the citizen the machinery a
form already has.

**Sharpening on the Copies row (2026-07-28, the UI-split wave).** "A version
stamp on the verdict" is necessary and NOT sufficient, and the wave proved it
by paying five more times. A stamp says which copy answered; it does not say
whether that copy was right. What the copies actually lack is **form-grain
identity**: the store gives every form an id, a hash and provenance, and the
image gives its loaded forms none, so *"is this form's live value the one the
store says?"* is unanswerable. Every mechanism that needs the answer therefore
substitutes a proxy — source-text change, reload-threw, deltas-since-boot — and
a proxy is wrong in BOTH directions. The wave hit both: three silent divergences
that every check called green (a `def` capturing another form's value; var
metadata capturing a schema's value; a generated namespace written and never
loaded) and one false alarm that cost a milestone a fresh JVM (a failed reload
reported as "the host still runs their previous code" while the image was
current).

The cautionary half: this was named here, specified correctly in
`root-cause-fix-plan.md` Tier 0 item B — *"a comparison, not a reload counter"* —
and then marked ✅ on the strength of its wiring while the comparison never
shipped. **A class fix marked closed is worse than one left open**, because the
✅ is what stops anyone looking. Identity for the copy, not a label on the
verdict.

Full derivation, 5-whys and measurements: `ideas/done/the-non-form-citizens.md`;
this wave's clustering in `ideas/ui-split-frictions.md` § "The wave's structural
finding".

## Core 6 — verification stops at the boundary, and every crossing is hand-built

**Root.** Derived from the SPA wave (16 frictions, `ideas/spa-wave-frictions.md`).
Sort them by *where the value was when it went wrong* and all but one land in
the same place: at a **crossing**, where something leaves the world slopp
verifies.

| Crossing | What went wrong |
|---|---|
| form data → a third-party interpreter (garden) | `clojure.core/>` rendered into a CSS selector; the rule landed on `.app` and the whole app was 16rem wide, behind a 200 |
| form metadata → an assembled route table | a context accepted a namespace list that could not perform the reads its own routes declared — 500, not 404 |
| endpoint contract → JSON → browser | a keyword passed a `[:x :string]` contract in-image and arrived as a string; the test validated a value no client receives |
| `:cljc` source → the ClojureScript compiler | `(first "line")` is a char here and a string there; `defonce` arity; `#js` |
| a spec → generated code | `pay!` generated `pay!!`; transport endpoints wrapped as browser `fetch` clients by default |

Inside the boundary the machinery is excellent, and the wave proved it: the
module gate refused a views→client-subtree call, named all three legitimate
answers, and `ns_rename` reported the `-test` sibling it had not rewritten.
That is Core 2's graph paying off. **Nothing comparable exists at the exits.**

**Five whys.** Why do frictions cluster at crossings? Verification is attached
to the WRITE of a form. → Why? Because the form is slopp's unit of storage,
editing, hot-reload and provenance (Core 5). → Why doesn't that cover a
crossing? A crossing is not a form; it is a relationship between a form and
something *outside* — a library, a wire format, another platform, another
process. → Why is that relationship invisible? THE reference graph models
form→form edges and has no representation for an edge that leaves the store.
→ **Base cause: slopp models edges inside the store and has none for an edge
that exits it, so every exit is unverified by construction and each one grows
an ad-hoc, hand-written check — or none.**

**Discipline.** Read this as Core 2 turned outward. Core 2 says *any
relationship the system lets you express, it must be able to see*; Core 6 says
**any boundary the system lets a value cross, it must check AT the crossing —
and report in the AUTHOR's vocabulary, not the checker's.**

The second half is not decoration; it is where the crossing bites twice. The
checker lives on the far side, so its vocabulary is the far side's: the
dialect gate refused `#js` by naming `read-string`, a symbol absent from the
source. `compile_client` anchors *warnings* to the owning form beautifully and
reports a hard *failure* as a bare path — no message, no form, no line. A
report phrased in the far side's terms makes the author debug a translation
they never wrote.

**Tells that you are in this core:**

- A guard that validates one TYPE of the data it forwards (strings, for
  injection) while forwarding everything else unexamined.
- A test that asserts on data that has not yet crossed the boundary its
  contract describes — the cheapest correct-looking test, and wrong. Watch
  for vacuous validation alongside it: `[:sequential …]` over an empty list
  checks nothing inside it.
- A derived artifact (route table, stylesheet, generated namespace, wrapper
  spec) assembled from verified forms and handed on with no check that the
  ASSEMBLY is coherent. Each form was legal; the composition was not.
- An error message naming a symbol, file or process the author never wrote.

**Distinguish from Core 5.** A stale `--live` host serving a deleted route is
Core 5's *Copies* citizen (a copy without a version stamp on the verdict), not
this — and `full_check`'s `:host-stale` verdict-note is that machinery already
working. Core 6 is about the crossing itself, not about a second copy of
something that has one.

*(That one turned out to have a root below the copy, found 2026-07-25:
`load-string` re-defines what a namespace's new source contains and is silent
about what it does not, so a DELETE reached every `--live` host as "still
there" — for every deleted form, not just routes. `reload-ns!` now unmaps what
departed. Routes only made it visible, because a stale route shadows the SPA
catch-all.)*

**The chassis, 2026-07-25 — `slopp.api.crossings`.** The base cause named a
missing REPRESENTATION, so that is what got built: a registry of exit KINDS —
what leaves, to where, `:checked-by`, `:blind` — plus the markers slopp owns
that deliberately stay inside, derived over the store, with `full_check`
reporting the exits nothing checks.

**It verifies nothing, and that is the design.** Nothing could: the far side is
another system, and the checker that would know lives there and speaks that
system's vocabulary — which is the second half of this core restated. What it
buys is that **an exit with no checker and an exit that does not exist stop
looking identical.** That is D-surface-honesty applied to the boundary itself.

Two properties keep an inventory from decaying into a document, and both are
general enough to reuse:

- **Classification must be TOTAL.** Every marker slopp owns is either an exit
  or declared internal-with-a-reason; a marker in neither list is a finding. It
  caught five on its first real run against slopp's own store. Without the
  second list the classification is partial, and a partial one is worse than
  none — every internal marker reads as a hole and the one real hole drowns,
  which is the precision failure that got `:positional-form-access` withdrawn.
- **It must be DERIVED, not hand-listed.** A list of exits cannot notice a new
  exit; a derivation over the store can.

Scoped to slopp's own marker vocabulary on purpose: a user's namespaced
metadata is theirs, and treating it as an unclassified exit would bury the
finding in a store slopp knows nothing about.

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
  *Withdrawn is not dead: that guard came back on 2026-07-25 as
  `:ambiguous-index`, once the predicate stopped being "positional access" and
  became **indexing a position whose MEANING depends on an optional earlier
  element**, restricted to code demonstrably reading store forms. Each of the
  three narrowing conditions carries one of the original false positives, and
  the rewritten rule measured 5 candidates / 1 finding — which was a live bug
  (a `def`'s VALUE rendered as its docstring in the reviewer UI). The lesson is
  not "withdraw and forget" but that a rule is only as good as the sentence
  defining it, and the first sentence is usually the symptom rather than the
  defect.*
- **One question tiers a lint: could a form legitimately look like this
  MID-EDIT?** No → `:error`; yes → `:warning`; worthless → `:off` with the
  numbers. → **D-kondo-config**.
- **Never warn about what a tool can fix** (sort the requires, don't lint them).
  → **D-kondo-config**.
- **Own the process TREE, not the process.** `.destroy` reaches the child
  only, and slopp's runners exist to spawn more processes — so killing one
  without its subtree leaves precisely the workers, orphaned and unreachable.
  Snapshot descendants BEFORE killing (a dead handle reports none), kill the
  parent first so it stops spawning, then sweep. And scope reaping to a
  session: killing "leftovers" globally would kill a concurrent server's work.
- **Teach in the MESSAGE; a new result key is a distribution problem.** A
  refusal's message reaches every caller by construction. A new key must be
  added to ~12 hand-maintained per-tool allowlists in `mcp/call-tool!`, and
  four keys have silently failed to arrive that way (`:dry-run`'s payload,
  `:drift`, `:external-pending`, and a `:fix` hint built and tested correctly
  one layer down). The feature exists, the tests pass, and the agent sees the
  old behaviour — which is the worst shape a failure can have. *Closed
  2026-07-26 by `tools/wire-keys` + a guard that refuses a fifteenth list. The
  general lesson survives the fix: **N independent allowlists over one value
  are never protecting anything** — a key absent from a result is absent from
  the output regardless, so each list was a guess, and guesses lose things.*
- **A registry must be able to notice what it does not contain.** A list of
  what a system has is complete the day it is written and describes nothing a
  year later. Every registry here now carries a drift check that asks the
  STORE rather than the list — and both new ones found real gaps within
  minutes: the marker registry was missing three markers in live use
  (`:external`, `:live-handle`, `:teach`), the crossing inventory five.
  → **D-rule-grounding**.
- **Two registries over one vocabulary need COVERAGE, not disjointness.** The
  first cut of that check asserted the marker and crossing registries do not
  overlap, and failed immediately on `:generated`, which is genuinely both a
  name dial and a crossing signal. A key claimed by BOTH is fine; a key claimed
  by NEITHER is invisible to both guards, and both then report clean.
- **Declare the dimension, then ENFORCE it, or it is an annotation.** Every
  done-advisory now says whether it applies to `:production`, `:tests` or
  `:both`, and the runner filters on it — before that, three rules had reached
  three different answers to the same question and none had written it down.
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

## Core 7 — the edit surface is positional; the agent's intent is transformational

**Root.** Derived 2026-07-26 by 5-whys over every open friction, then checked
against the ~45 resolved ones — where the evidence is much stronger.

slopp's write verbs are named for WHERE they write: replace this form, add a
form, insert after that one, replace this subform. The agent's unit of intent
is a TRANSFORMATION: wrap this in a binding, split this def in two, reorder
these, extract that, rename this concept. Every transformation without a verb
must be decomposed into positional edits by hand — and slopp's own gates make
the intermediate states illegal, so the decomposition is a puzzle rather than
a sequence.

The purest instance, from the SPA wave: reordering two forms in a `.cljs`
namespace took *"revert the caller, move the helpers, re-apply the caller —
three writes to reorder two forms"*, because every single-step move was
refused citing the violation that was already there.

**The evidence is that slopp has been paying for this for its whole life.**
Every named refactor verb exists because someone felt the decomposition, and
`ideas/done/` records each birth separately without ever connecting them:

| Verb | Its recorded reason for existing |
|---|---|
| `rename_sweep` | *"~60 error-prone manual edits into ~8 verified sweeps"* |
| `change_signature` | *"rewrites the defn AND its call sites as one intent"* |
| `edit_requalify` | *"namespaces a boundary fn's option keys as one intent"* |
| `edit_move_forms` | *"would have been one call and none of the damage would have happened"* |
| `edit_extract`, `module_extract`, `edit_rename`, `undo`, `episode_revert`, `cleanup` | same shape |

Plus the automatic passes: `resolve-cold-load` (auto-reorder + auto-declare),
`fix-declares!`, done's require-pruning and normalize. **Roughly fifteen
derived transformations, each shipped as its own named thing.**

**slopp already wrote the principle down — one grain too high.** From the
`edit_group` decision: *"It stays as an internal primitive for transformations
a TOOL derives from ONE intent … whose intermediates are invalid by
construction and which nobody was asked to reason about,"* with its own test:
***"does the AGENT choose the steps, or does the TOOL derive them from one
stated intent? `rename_sweep {from to}` is one intent. `edit_group [step step
step]` is a shopping list."*** That was scoped to multi-form ops on the wire
and never generalised, so nobody asked it one grain down.

**The near-controlled pair.** `auto-avoid-declare` is the same transformation
observed in both states. Derived (`:clj`): the pipeline computes a topological
order and realises it, *deliberately silently* — *"no `:reordered` result key
(form ordering is a file concept; surfacing it re-anchors the agent to 'think
about the file')."* The agent never learns the problem exists. Not derived
(`:cljs`, before the fix): the three-write dance above. Same transformation,
opposite outcomes, one variable.

**Discipline.** Ask the `edit_group` question at EVERY grain, not just above
the form: *does the agent choose the steps, or does the tool derive them from
one stated intent?* When the agent must choose, the intermediate states are
usually ones the gates reject — so the cost is not the typing, it is planning
a legal path through slopp's own safety.

**Tells that you are in this core:**
- A recorded workaround of the form "revert X, do Y, re-apply X".
- An edit whose cost is dominated by RESTATING code that is not changing.
- A refusal that is correct at every step while no single step reaches a legal
  state.
- A new named refactor op that feels obviously right — it is, and it is also
  the fifteenth instance of an unnamed class.

**Status.** The grain analysis is the actionable part: derived transformations
exist at store, module, namespace, forms and form grain. BELOW the form there
was exactly one (`edit_extract`), which is the proof the shape works down
there — and every open Core 7 friction is sub-form. `edit_subform {wrap}`
(2026-07-26) is the second: match a complete form, and `$1` in the template is
where it lands, so introducing a binding around existing code costs the
template instead of a retype. Its siblings — split, reorder-within, unwrap —
are still unbuilt.

## Core 6b — a check reports where it RUNS, not where the author WROTE

**Root.** Core 6's second half, promoted 2026-07-26 because it turned out not
to be boundary-specific. Four of its seven recorded instances are checks that
never cross anything.

A check is written where the data is convenient — after the reader expanded a
literal, inside the analysis representation, at the layer that holds the map —
and its message names what IT is looking at. That message is correct from the
checker's position and useless from the author's, and nothing verifies the
difference.

| Instance | What the author wrote | What the message named |
|---|---|---|
| dialect gate on `#js` | `#js {}` | `read-string` (absent from the source) |
| `edit_subform {where}` | `{:rule "ambient-state"}` | the author's own input, restated |
| `query_source {targets}` | `["a.b/c"]` | `no conversion to symbol` |
| cljs hard compile failure | a `defonce` arity error | a path into a temp dir |
| a write missing an alias | `(str/join …)` | `No such namespace: str` |

**Discipline.** A refusal must name the construct AS WRITTEN, and name the
next call. Two fixes are usually available and the second is better: improve
the message, or make the refusal unnecessary — `query_source {targets}` was
fixed by ACCEPTING every unambiguous spelling, because refusing taught a rule
that did not need to exist.

**Tell:** the message names a symbol, file, layer or process the author never
typed. If you cannot find the named thing in the source you just wrote, the
message is speaking the checker's vocabulary.

### Sharpening (2026-07-28): "this image" is the checker's vocabulary too

The instances above are all about NAMING. There is a harder version, found by
auditing the currency work in the same session that landed it: a measurement
reported under a name that belongs to a DIFFERENT SUBJECT than the one it
measured.

`slopp.image.currency/stamp!` executes in the server process, but it records
what the server pushed INTO the child oracle. So `currency/drift` measures the
ORACLE. `host-brief` — the `session_brief` section describing THIS server —
published that value as `:drift` / `:image-verified`, and answered a failed
live-reload with "the image was COMPARED to the store and holds every form at
its current source, so this is the watcher stuck, not stale code".

Every word of that is true about the oracle and unfounded about the host. And
it fails exactly where it matters: a failed host reload is the one condition
under which the two processes are known to diverge, so the reassurance is
weakest precisely when it is offered. Note this was itself the FIX for a false
alarm (friction 20a) — the first correction over-swung from "asserts staleness
nobody measured" to "asserts currency nobody measured", which is the same error
with the sign flipped.

`host-warning` takes the same value and is CORRECT, because a verdict is
produced by the oracle. Same number, same call, two readers, one of them wrong.

**Discipline.** A measurement's name must carry its SUBJECT, not just its
quantity — `:oracle-drift`, not `:drift`. When one process reports about
another, the reporting process's identity is the thing most likely to be
silently assumed, because it is the one nobody had to look up.

**Tell:** a field named for a quantity (`:drift`, `:verified`, `:loaded`) in a
map named for a subject (`:host`, `:session`, `:branch`). Ask which process,
image, or store the number was taken IN, then ask which one the map is ABOUT.
If answering takes more than a moment, the reader will not answer it at all.

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

## Core 8 — the unit you EDIT is not the unit you must EVALUATE

**Root.** Derived 2026-07-29 by 5-whys over frictions 1 and 17, after noticing
that "fixing" 1 closed 17 without touching it.

slopp's thesis is that the top-level form is the unit of editing, storage,
hot-reload, verification and provenance. Four of those five are right. The
fifth — evaluation — was inherited from the list rather than chosen, and it is
the one Clojure does not agree with.

Loading is order-dependent and leaves effects that outlive the form: a value
snapshotted into a `def`, an evaluated metadata map, a registration in a
multimethod table. Re-evaluating ONE form replays none of them. So per-form
hot-load equals loading only when a form's entire contribution to the image is
its own var binding and nothing else read it at load time — true for a `defn`,
false for everything that captures.

| Instance | What survived the "reload" |
|---|---|
| friction 1 | a `def` holding a value computed from the edited form |
| friction 17 | `^{:web/response schema}` metadata, evaluated at load |
| #131 (twice) | a defmethod's entry in the multi's method table |
| `defonce` | the whole form — it is never re-evaluated by contract |

**The tell that it was structural:** the correct behaviour already existed in
the same codebase. `--live` reloads whole NAMESPACES and never had any of
these; the oracle reloads FORMS and had all of them. Two loaders, two different
meanings of "reload", and nobody had written down that they disagreed.

**Discipline.** When a unit is load-bearing for one concern, do not assume it
is the right unit for the others — say which concern each grain serves. Where
they differ, let the SEMANTICS of the operation pick the grain, not the
ergonomics of the interface. Here: edit a form, repair a namespace.

**Tell:** the same bug fixed twice by category. `edit-replace!` and
`delete-form!` each grew a hand-written `unregister` branch for defmethod
before anyone asked what defmethod had in common with a derived `def`. A second
special case for a third instance of one class is the moment to stop and name
the class — the first one is a fix, the second is evidence.
