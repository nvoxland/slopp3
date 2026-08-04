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
half, promoted once it turned out not to be boundary-specific. **Core 8**
(2026-07-29) is a category of GRAIN, and **Core 9** (2026-07-31) a category of
SUBJECT — what a check computes over, versus what it claims to describe.

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

**Sharpening (2026-08-02): a JOIN drops what falls off it, and reports the
remainder as the whole truth.** `query_capabilities` is a join of the capability
registry against the stored config. A stored key with no registry row simply
fell out of the `keep`, so a store carrying three settings under RETIRED names
reported zero `:set true` and mentioned nothing — the tool whose job is *what is
configured here* describing an unconfigured store, while the reason its app
server would not start sat in the config it declined to name.

The shape is this core's exactly: **UNSET** and **SET UNDER A NAME THIS BUILD NO
LONGER KNOWS** shared one representation, at the precise moment the difference
IS the diagnosis. What makes it worth its own entry is *where* it hid — not in a
check that under-counted, but in the ordinary structure of a join, where the
unmatched side is discarded by construction and nothing looks like it is missing.

Two things generalize:

- **Ask what falls off the join.** Any surface built by matching stored data
  against a declared schema has an unmatched bucket, and reporting it is nearly
  free — both halves are already in hand. `report` gained `:orphaned` in one
  binding.
- **`no-backwards-compatibility` makes the migration path COMMON, not rare.**
  Retired names are the standing policy, so "your store holds values under names
  I no longer know" is an ordinary state a read has to be able to say. It was
  found by the first store other than ours to cross a capability rename, and it
  would have replaced a bespoke hand-written migration message with one call.

Worth noting how it was nearly missed: the verification command run to confirm
the rename was `grep -o '…:set true…'` returning no output, which was read as
"nothing stale is recognized" — the correct conclusion, from output that was
itself the symptom. A check whose PASS and whose BUG produce the same silence
is this core aimed at the person holding it.

**And that has a cheap general form, contributed by the consumer who found the
join bug:**

> **Any filter used as evidence needs a positive control — assert the
> population is non-empty before believing the filter found nothing in it.**

One line, no infrastructure, and it applies to a shell pipeline exactly as it
applies to an `is`. The grep above needed `| grep -c ':key'` first: a non-zero
count proves the TOOL answered, which is what makes the zero from the real
filter mean anything.

**Third instance, 2026-08-02, and this one was already IN the suite.** The
assertion `(nil? (caps/find-entry "groups.admin.member"))` existed to pin that
the mid-`*` pattern demands its `.members` tail. Moving the family to
`web.auth.groups.*` left it green — because no `groups.` key resolves at all
now, so the whole population it was filtering had gone. A passing assertion
that no longer observes what it was written to observe is indistinguishable
from one that does, which is Core 1 in the test suite rather than in a tool.
The retarget is trivial; noticing is the work, and what makes it noticeable is
pairing every "must be absent" with a "must be present" against the same
population.

**And the twin the same day, from the other direction.** The shipped-prose
guard was built with a probe asserting its detector CAN FIRE on a known-bad
line, and none asserting it STAYS SILENT on a known-good one. The first rename
it met was one whose new name contains the old (`auth.bearer.` →
`web.auth.bearer.`), the match was an unanchored substring, and it reported 17
findings of which 17 were false — every one a line that had just been
corrected. So:

> A detector needs BOTH probes — one input it must flag, one it must not.
> A check that flags everything is exactly as uninformative as one that flags
> nothing, and only the second failure mode is usually tested for.

The good-input probe caught a second bug within a minute of existing: a
bracket expression in the escaping that BSD `sed` rejected while looking
correct.

**And a third probe the day after (2026-08-02), which only MEASUREMENT asked
for.** `overstated-edges` — declared production module edges only `-test`
namespaces cross — shipped with both probes green and returned **80 rows** on
its first run over the real store. Roughly forty of slopp's modules are made
entirely of tests, where "only tests cross it" is true by construction and
carries no information. Four real findings under seventy-six non-findings is a
list nobody reads, which is the failure the rule-precision discipline already
names. Both probes were correct; neither was drawn from the actual population.

> Both probes prove the detector DISCRIMINATES. Only running it over the real
> population tells you whether the distinction is worth reporting.

### The instances you found came through a FILTER, and the filter is not the class

2026-08-02, two units apart, the same shape. A class was named and fixed — the
manifest was recording fixture crossings as production dependencies — and the
fix was applied to the instances in hand: the two edges that formed declared
CYCLES, because a cycle is what made me look. The general question, *which
declared production edges have no production caller?*, was never asked. It had
four answers, and the two I missed sat there until one of them refused a
legitimate declaration in an unrelated module a day later.

> When you fix a class, the instances you have came from whatever made you
> notice. Before calling it closed, run the NEW distinction over the whole
> population — the fix has just given you a predicate you did not have when
> you were collecting.

This is Core 1 pointed at the author rather than the tool. "I fixed the class"
and "I fixed every instance of the class" read identically in a commit message,
and only one of them is checkable. The cheap discharge is a one-line
`query_store` fold over the real store the moment the predicate exists; here it
turned a two-instance anecdote into a four-instance measurement and a reported
`:overstated-edges` key, at a cost of about a minute.

### A measurement whose success condition is an ABSENCE proves nothing on its own

2026-08-02. Module 3's regroup was preceded by hand-simulating the rename and
pre-declaring all 38 module edges, and the record of it reads:

> **Payoff, measured: ten renames, ZERO refusals.**

Zero refusals is equally consistent with *"pre-declaring prevented them"* and
*"there would have been none anyway."* The second turned out to be true.
`ns_rename` never refuses on an undeclared edge — its rewrites go through
`store/apply-changeset`, one coordinated delta running no write gates, which is
the entire reason `rules/module-governance-check` exists and says so in its own
docstring. The real failure mode was never a refusal storm; it was SILENT debt,
which is worse, and which the practice did nothing about.

The number was real. The causal claim attached to it was never tested, and it
survived into a filed friction, a task description, a shipped skill paragraph
and a docs section before anyone pulled on it.

> A measurement that succeeds by observing NOTHING needs the something
> demonstrated at least once — deliberately, under conditions you control.
> Otherwise "my precaution worked" and "the hazard does not exist" produce
> identical evidence, and only one of them is a reason to keep paying for the
> precaution.

This is the positive-control discipline (from slopp-ui, via
`assertions-never-red`) pointed at a RECORD rather than a test — and it was
violated one wave after being written into the shipped skill, by its own
author, in the record of the measurement that was supposed to demonstrate it.
Which is the argument for it being a system property rather than a habit: the
discharge is one deliberate observation of the hazard, and the cost of skipping
it is a false premise that reads like evidence for as long as nobody happens to
read the implementation.

#### Sharpening (2026-08-03): the population starts at the FIXTURE

Every statement of this core, including the one in the shipped skill, points at
the ASSERTION. One level lower is where it actually gets missed. The e2e for
`ns_rename`'s stranded-alias report opened:

```clj
(api/ingest! sess 'ra.core.thing "…")
(api/ingest! sess 'ra.caller     "… (:require [ra.core.thing :as thing]) …")
(let [r (api/ns-rename! sess 'ra.core.thing 'ra.moved.thing …)]
  (is (nil? (:alias (:left-behind r)))))          ; green, and about nothing
```

`ingest!` runs `edit.modules/module-scan`. `ra.caller` is one module over with
no declared edge, so it was REFUSED — `{:error …}` returned into statement
position — and the fixture was one namespace. The rename had no caller to
strand, so "the report stays quiet" passed against a store in which quiet was
the only reachable answer.

It surfaced only because the sibling block asserting rows ARE present went red.
Written alone, the quiet half would have shipped green and asserted nothing,
permanently — and it is the half a reader trusts most, because a check that
stays quiet on the common case is exactly what you want to believe.

> A fixture that failed to build satisfies every absence assertion downstream of
> it. So the control belongs on the SETUP, not only on the finder: assert what
> the fixture actually produced — a count, a `:forms`, a membership — before
> anything is read off it.

The generalisation past slopp: this is what makes write verbs that RETURN
`{:error …}` rather than throwing a sharp edge in test code specifically.
Everywhere else a discarded return is a missed diagnostic; in a fixture it is a
missing population, and the failure propagates as green. Filed as friction #53,
whose remedy list starts with `done` deriving it — a test body calling a write
verb in statement position is visible in the store.

#### Sharpening (2026-08-04): a population control is not a PATTERN control

The two sharpenings above push the control DOWN, from the assertion to the
fixture. This one pushes it sideways, and it is the case where every existing
form of the discipline was already satisfied.
`ops.engine-test/the-write-engine-names-no-app-type` carried the population
control twice, explicitly labelled:

```clj
(testing "there is a population — the vacuity that ate a sibling guard"
  (is (< 50 (count (:namespaces st))))
  (is (re-find #"rebased-write!" src) "rendered the wrong namespace, or rendered nothing"))
…
(is (= [] (vec (re-seq #"slopp\.api\.cljs" src))))
```

Phase 3 renamed `slopp.api.cljs` to `slopp.webdev.cljs`. The haystack stayed
real — 193 namespaces, the right rendered source, both controls green — and the
needle named a string that could no longer occur anywhere in the store. The
guard's own comment says it "survives the move as the specific statement of it".
It survived syntactically and died semantically, the day after the move it was
written to outlive.

> A control on the POPULATION says the haystack is real. It says nothing about
> whether the NEEDLE still matches anything. When a check's subject is a
> LITERAL — a regex, a search string, a path — it needs a second control
> asserting that literal matches something known to contain it.

The fix costs one line and is always available, because whatever the pattern was
written to find exists somewhere:

```clj
(is (seq (re-seq #"slopp\.webdev\.cljs" (render/render-ns st 'slopp.webdev.cljs))))
```

Two of the three instances measured **already had that control, three lines
below the broken assertion and spelled correctly**: `build-deps-edn-trace-alias`
asserts `slopp\.image\.testmain` appears exactly twice in the trace branch,
while the branch above searched for `slopp\.testmain` and therefore could not
fail whatever the generator emitted. Nothing compares two literals inside one
form — friction #47's shape (a fixture's two halves, three tokens apart, checked
by nothing) landing in an assertion instead of a fixture.

**Why this one could be mechanised where the others could not.** A search
pattern is DATA: `ns_rename` rewrites requires, qualified refs, quoted symbols
and prose, and a regex escapes its dots, so even the text sweep misses the
spelling. But the store knows every namespace it has, so a regex naming a name
in the store's OWN root family that is not one is checkable rather than a
discipline — the `:stale-pattern` done-advisory. The scope is what makes it
usable and it is not a heuristic: unrestricted the rule reports 119 findings of
which 2 are real (fixtures name `mv.core`, libraries name `clojure.set`, config
keys name `web.static`, assets name `logo.png`); restricted to the store's own
roots it reports 3, all three bugs. That restriction is the *fixture should not
name real production code* rule read backwards — **a fixture that names nothing
real is exactly a fixture this check cannot see.**

#### Third instance (2026-08-04): a walk over a form's sexpr sees CODE, not CONTRACTS

The reach tell — *what does this scan address things BY, and what is addressed
some other way?* — landed on its own author the day it was written down, twice
in one measurement.

`tree-seq coll? seq` over a form's sexpr addresses nodes by TREE POSITION. A
node's METADATA is not one of its children, so the walk never enters it — and
metadata is where slopp keeps malli schemas, `:web/*` declarations, and every
other contract. A scan built that way sees a namespace's code and none of what
it promises.

It surfaced as a false FINDING rather than a miss, which is why it was caught:
the new vocabulary check reported `:slopp.ops/agent-id` as a name occurring
nowhere in the store. It occurs in the schema on `slopp.ops.external/open!`.
Two independent confirmations had already agreed with the false finding — a
keyword census (same `tree-seq`) and `form-named` returning nil (a keyword is
not a form) — so the reach gap was reproduced three times before it was seen
once.

**The general form:** a walk is an addressing scheme like any other, and its
residents-it-cannot-name are whatever the language attaches sideways rather than
nests. In Clojure that is metadata, and metadata is disproportionately where
DECLARATIONS live — so the blind spot is not random, it is aimed squarely at the
things a store is asked about.

### The prefix and its length, written down in two places

Twice in two days, in different namespaces, the same defect: a name matched by
one spelling and trimmed by another's LENGTH.

```clj
(re-matches #"http\.static\..+" (str k))            ; matched the OLD key
(subs (str k) (count "web.static."))                ; trimmed by the NEW one
(str/starts-with? k "auth.bearer.tokens.")          ; matched the prefix
(subs k 19)                                         ; trimmed by a number
```

Both produced a value that looked structurally fine and matched nothing —
`{}` from the first, `"s.ci"` from the second. Neither threw. This is Core 2
at its smallest grain: the relationship "this integer IS the length of that
string" is real, load-bearing, and invisible to every tool in the system, so
it rots the moment the string changes. The fix is the same each time — derive
the tail from the prefix you matched — and the tell is any literal count, or
any regex, standing next to the name it silently duplicates.

That is why a config-KEY rename is not a concept rename: the token is a
dotted string, so it lives in string literals, regex literals, doc tables and
character counts, and only the first of those is anything a sweep can see.

It unifies two instances we had been treating as unrelated. Ours was the
verification grep. Theirs, written two days earlier without naming the rule:

```clj
(is (seq found)
    "no namespace declares an endpoint — the scan found nothing, which would
     make the comparison below pass by being empty on both sides")
```

Same failure in different clothes — **an empty result standing in for a
verified one** — and it is also the exact shape of this skill's existing
`(is (empty? (:unused r)))` example, where the key never existed and
`(empty? nil)` passed forever. Three instances, one rule, and the rule is
cheaper than any of the three fixes.

It composes with **Core 9**'s tell (*what did I avoid doing to make this check
cheap?*): a bare filter IS the cheap version of a comparison, and the
population check is precisely the part it skipped.

**Not a gate, deliberately — for now.** Detecting "this assertion could pass
vacuously" statically is the kind of restrict-before-analysis this document's
"Wrong directions" section already records as a measured dead end, and a noisy
advisory on every `empty?`/`=` over a derived collection would be worse than
nothing. It ships as a PRACTICE in the skill instead. Revisit if a precise
population-shaped signal turns up.

### Sharpening (2026-08-04): a fixture where two fields COINCIDE has tested neither

Four instances of the population trap landed in one day, and the fourth is a
different mechanism from the other three. The first three are all *the sample
was too small*: a fixture that failed to build, a contract test whose fixture
omitted the optional branch carrying `:via`, and a consumer reading the keys
off ONE row of a collection whose keys are per-form optional (they reported
`:sig` missing; the union over 58 rows had it on 32).

The fourth is *the sample could not tell two things apart*. A boundary report
carries `:from` (a namespace) and `:from-module` (its module). slopp-ui counted
one and labelled it the other; every test they had ran against a MODULE-grain
fixture, where the two hold the same string — so the fixture could not
distinguish the readings and no assertion over it could. It surfaced the
instant namespace-grain data arrived.

**Two fields that coincide in the common case are ONE field for testing
purposes. Test at the grain where they differ, or you have tested neither.**

The tell is structural rather than statistical, which is what makes it usable:
suspect any pair where one value is DERIVED from the other — a qualified symbol
and its namespace, a path and its root, a form and its container, a module and
one of its namespaces. In the degenerate case the derivation is the identity,
and identity is where a confusion hides.

Their half of it, which is the actionable one and belongs beside ours: *"my
fallback and your endpoint disagreed about the GRAIN of the same field, and my
tests only ever ran against the fallback."* A fixture built to stand in for a
surface you do not have yet is a fixture whose shape you chose — so it agrees
with your reading of the contract by construction, and the disagreement it
exists to catch is the one thing it cannot.

## Core 2 — one relationship is first-class; the rest rot

**Root.** THE reference graph (`slopp.index.refs`) is the crown jewel —
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
- **An error produced AFTER a retry must say which attempt it came from.** A
  recovery path re-runs the failing operation, so it can fail a second and
  DIFFERENT way — and that error describes the recovery, not the fault.
  Surfacing it alone makes the recovery the story and hides the bug behind it.
  Measured 2026-07-31: `hot-load-all!`'s heal reboots the image from the
  COMMITTED store, so a merge's already-loaded namespaces vanished and the
  retry died with `Could not locate …clj on classpath` — a classpath problem
  that never existed. Callers read only `:err`, `:first-err` held the real
  compile failure, and four merges were refused over an ordering bug that was
  not there. → **D-surface-honesty**. This is Core 1's shape one step on: not
  "could not check" wearing the face of "checked", but a MANUFACTURED
  diagnosis wearing the face of a real one, which is worse — an agent will act
  on it.
- **A recovery that rebuilds state from a checkpoint must restore everything
  the checkpoint cannot know about.** `fresh-image!` boots from the committed
  store; anything living only in the candidate has to be replayed explicitly,
  and "the current call's namespaces" is the wrong scope the moment a caller
  loads across several calls with nothing committed between them.
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
- **A check belongs at the layer that can see what it judges — a layer that
  can only see a PROXY for the real thing will report on the proxy and sound
  certain.** `slopp.store.merge` sees a delta log and some maps; it cannot
  tell a test namespace from production code. Judging module cycles there
  judged the DECLARED manifest, so `slopp.store.db-test`'s require of
  `slopp.api` made every merge into main warn of a cycle production didn't
  have — and advise a retraction that would have broken that test. The
  question was answerable, just not there: it moved up to `slopp.api.branch`,
  which can derive the production graph. **The tell is a check that lands in
  a low layer because the DATA happened to be in hand there**, rather than
  because the layer understands the question. (Core 9.)
- **A standing condition re-announced on every unrelated operation is noise,
  not news.** Scope a warning to what the operation CHANGED — the merge cycle
  note fires only when the merge actually gained a module edge. Otherwise the
  signal that ought to mean "you just did this" degrades into weather, and
  the first thing anyone learns is to skip it.
- **Measure MECHANICAL changes on the deterministic wire-cost meter; reserve
  lifetime evals for BEHAVIORAL questions at n≥3.** A per-step delta off ONE
  lifetime run is noise (steps you never touched swung ±30%). (P10.)
- **A rule INHERITED FROM A NAME and enforced AT WRITE needs a done-time
  relocation check — a relocation changes the name without writing the forms,
  and it is the one path around every write gate.** Earned twice, in two
  systems, before it was named. Purity tiers: folding `slopp.mine` under
  `slopp.store` made it `:pure` along with the SQLite layer, and `full_check`
  stayed green for weeks until a docstring typo-fix happened to touch one of
  the forms (`tier-governance`). Module rules: regrouping into `slopp.project`
  took a namespace from two segments to three, making it package-private while
  four callers kept reaching in — again green (`module-governance`, 2026-08-02).
  **`ns_rename` makes it worse than it looks: it rewrites its own callers, and
  a caller a rename rewrote never passes a gate either.** So the operation most
  likely to drift the architecture is the one operation the architecture's own
  check structurally cannot see. Fire the check on `:rename-ns` / `:move-forms`
  / `:extract-ns` / `:module-extract` deltas since the last done. **And do not
  assume the moved namespace is the one to report** — for the module rules it
  is the CALLER that violates, and the caller did not move, so scoping to
  "what moved" (correct for tiers) finds nothing.
- **A check that is never ASKED reads exactly like a check that passes.** Core
  1 one level out: not a surface reporting success without checking, but a
  correct checker with no caller. `module-debt` computed whole-store module
  violations, and was wired into the graph view and into `module_dep`'s
  response — never into `full_check`, which meanwhile advertised
  "lint/dead-surface/layering/in-image cover every namespace". Before adding a
  detector, grep for one: the second-cheapest fix in the codebase is a
  call site.
- **A check's GRAIN is a scope claim, and turning a rule on does not check the
  code already there.** The sharper sibling of the bullet above, found
  2026-08-03: not a checker with no caller, but a checker called correctly over
  the wrong POPULATION. A `:grain :done` rule fires over the forms an episode
  CHANGED, so a violation older than the rule is invisible to `done` — and
  stays invisible, because no later episode changes that form either. Every
  episode is honestly clean and the store is not. slopp-ui reported it from
  outside: two known `direct-http` violations, `full_check` reporting zero rule
  findings of any kind, three tools consulted. **So "this rule has never fired"
  is worth nothing on its own** — it is equally consistent with a clean store
  and with a rule that has never once been asked about the code it governs.
  The fix is `slopp.rules/sweep-store!`, folded into `full_check` beside
  `module-debt` on the identical argument one layer up.
  **The load-bearing half is `:not-swept`.** About a third of the advisory
  registry compares against the last-done BASELINE or reads the episode's
  DELTAS, and running one of THOSE over every form does not report clean — it
  reports nothing, in the same shape. `key-typos` is the worked example: an
  "established" key is one that ≥2 UNCHANGED forms use, so a sweep in which
  every form is in scope establishes nothing and would have been green forever
  for a reason having nothing to do with the code. That is Core 1's
  representation collapse exactly, so the registry DECLARES `:sweep` — `true`,
  or a string saying why not — and the sweep reports both lists, total by
  construction. A rule can be reported as passed or reported as unasked; there
  is no third state where it silently vanishes.
  **Measured on landing:** 2342 forms, ~6s of a ~196s `full_check`, zero
  `:error`-grade findings, and 34 standing `:advisory` ones (17 `marker-why`,
  17 `namespace-purpose`) that no `done` could ever have seen. The
  `namespace-purpose` half independently reproduced phase 0b's hand census,
  which is a decent cross-check on both.

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

## Core 9 — a check computed over a PROXY reports on the proxy, in the real thing's voice

**Root.** Named 2026-07-31 by slopp-ui, after the third instance in one
evening. Not a category of mistake, place, citizen, or intent — a category of
SUBJECT: what the check is actually looking at, versus what it claims to
describe.

A check is written against the thing at hand rather than the thing in
question. The stand-in is usually correct-looking, often derived from the real
thing, and always cheaper to reach. What it is not is capable of failing the
way the real thing fails — so the check's *green* means less than it says, its
*red* names the wrong subject, and either way it speaks with the authority of
the thing it replaced.

| Instance | The proxy | Standing in for |
|---|---|---|
| merge cycle note (`cb007ff`) | the DECLARED manifest | the production module graph |
| merge heal (`a246bd6`) | `:err`, the post-retry failure | the compile error that actually fired |
| framework fixture (`09e8535`) | a dependency-free fake | a real framework with transitive deps |
| slopp-ui's `forward` | `catch java.io.IOException` | "the far side is down" |
| slopp-ui's load path | `:data nil` | a load's actual state |

Three of those are in this repo's log inside one week, and each was fixed as an
instance. That is the signature the preamble already warns about.

**Why it is not Core 1.** Core 1 is a READER trusting a value it did not earn.
This is a CHECK, an error, a fixture or a catch clause being *authored* against
a stand-in — the failure is on the writing side, and it survives every amount
of care taken by whoever reads the result.

**Discipline.** Before writing a check, say out loud what it is computing over
and what question it claims to answer. If those are different nouns, either
move the check to where the real noun is visible (`slopp.store.merge` could
not see production code, so the cycle question left that layer) or say in the
result which noun was used. The existing bullet *"verification checks REALITY,
not intent"* is this discipline at write-gate scope; the class is wider —
error reports, test fixtures and catch clauses have all done it.

**The tell to actually use — it is a SEARCH, not a recognition** (slopp-ui,
2026-07-31): *what did I avoid doing to make this check cheap, and what can go
wrong only there?* The proxy and the real thing sit on opposite sides of a
boundary the check does not cross, and **that boundary is where the cheapness
came from**. Name the saving and you have named the boundary; the failures the
proxy cannot express are the ones living on its far side.

| check | boundary the cheapness bought |
|---|---|
| module cycles over the declared manifest | test code vs production code |
| `:err` alone, after a retry | before vs after the heal |
| `framework-files` glob vs the jar's actual contents | packaging |
| the JVM oracle vs `:cljs` | platform |
| a dependency-free fake framework | the dependency graph |
| `catch IOException` vs an unreachable host | the process / the network |

This is answerable **while writing the check**, which is what makes it worth
more than the two recognitions below: it also predicts where the next instance
is — *any check you were pleased to have made fast.*

A boundary NAMED in the result is the discipline working, not a violation:
`review_scan` reports `:cljs` forms as off-platform and says the compiler is
their only check, so nobody reads that green as coverage. The bug is the
unstated crossing, not the crossing.

**Two after-the-fact tells,** for reviewing something already written: the
check lives at that layer because the DATA was in hand there, not because the
layer understands the question; and the stand-in cannot express the interesting
failure at all — a dependency-free fake has no way to have a bad transitive
dep, so its green was never evidence. Both need a reader to already suspect
something, which is why neither caught any of the six above.

### Sharpening (2026-08-03): a COUNT is a check, and it reports on the population it counted

The table above is checks, errors, fixtures and catch clauses. A **metric** is
the same class and reaches further, because a number carries no hedge: nothing
about `:callers-out 4` says which population it counted.

`/api/ns/:ns` shipped `:callers-out` — "how many forms outside this namespace
call it" — for slopp-ui's importance ranking. Measured against
`slopp-ui.views` before anyone drew with it: **ten of the twelve
cross-namespace callers were deftests.** The top-ranked form held first place
on four callers, all four of them tests, while the form that IS the render sat
fourth on its single production caller. The field was ranking by test count and
saying "importance".

The boundary the cheapness bought is **test code vs production code** — which
is the FIRST row of the table above, hit a second time by a different surface.
That boundary is now the one to check first: it is invisible in the reference
graph (a `-test` namespace is an ordinary namespace with an ordinary require),
so every whole-store count crosses it by default and none of them mention it.

Two things follow, and the second is the reusable one:

- **Ship both numbers, not the sum.** `:callers-out` and `:callers-out-test`
  add back up; one integer cannot be taken apart again. Where a mixed
  population is the honest input, the fix is to stop mixing it, not to
  document the mix — a docstring saying "includes tests" is Core 4 territory.
- **What caught it was an acceptance test written before any numbers
  existed.** slopp-ui named the five forms that should rank darkest in
  `slopp-ui.views` and the four that should rank lightest, sight unseen, and
  sent them with the request. The fixture that shipped alongside the metric —
  written by the same hand, in the same hour — was green throughout. A metric
  and its fixture written together cannot validate each other; that rule was
  already in the shipped skill, and this is the first time it paid.

### Sharpening (2026-08-01): a teach string is a check that SPEAKS, and Core 9 applies to what it asserts

Found on `web-undeclared-context`'s refusal, in a cold read by slopp-ui:

> Lifecycle: the builder runs once per app image and **the managed server
> boots a FRESH image at every done point**, so anything it allocates is new
> each time.

True of a store slopp runs the server for. The gate fires on any
`web.enabled` store, including one with `dev.server false` where no managed
server boots at all — and that store's author is being told, unconditionally,
about a lifecycle that does not happen to them. A general truth delivered in
this store's voice: Core 9 one notch down, since nothing is *computed* over a
proxy, but the sentence still describes a subject other than the reader.

The fix is not to make it conditional. It is to find the phrasing true either
way — *"anything the builder allocates is new each time it runs"* holds
whether a managed server exists or not, and is one clause shorter.

**Discipline.** A gate's teaching is asserted to EVERY store the gate can fire
on. Before writing a clause, ask which stores it is false for; if the answer is
not "none", either narrow the gate or find the phrasing that holds for all of
them. The arming condition (`web-enabled?`) tells you the audience — anything
narrower than it that appears in the string is a claim about a store that may
not be the one reading.

### Sharpening (2026-08-04): the noun is RIGHT and the REACH is short

Core 9 proper is a check computed over a different noun. There is a neighbour
with the same tell and a different remedy: the check reads **the right noun,
partially**, and reports on the part it reached in the voice of the whole.
slopp-ui's formulation, after we each hit it the same week:

> Not a wrong check — a check whose subject is partly outside its own reach,
> reporting on the part it can see in the voice of the whole.

| Instance | Subject | What the reach excluded |
|---|---|---|
| `rename_sweep` over store forms | every form | forms with `:name nil` — 3 of them, invisible to a name-addressed pass. It reported "15 forms", green, and left a docstring with four stale mentions |
| `X-Slopp-Main` in the jar manifest | every reference to `slopp.boot` | a config VALUE that names code. No reference-tracking models it, so a full green preceded a jar that would not start |
| slopp-ui's `tree-seq vector?` | the rendered hiccup | anything spliced as a bare seq |
| slopp-ui's `tree-seq coll?` | the rendered TEXT | nothing — it reached too FAR, into attribute maps, so a text assertion passed on an `href` |
| slopp-ui's `{:total 0}` | a 248-form store | all of it, from a guessed arity. Caught only because the count was in the result |

**Both directions fail toward clean, and that is the part worth carrying.** My
own framing of the traversal case was that reach is too NARROW; slopp-ui
measured both predicates and found the too-WIDE one was the instance already
biting. A walk that descends into an attribute map still returns strings, so
"the name is rendered" passes on a link with no visible text. Too little reach
finds nothing; too much finds the wrong thing. Neither announces itself.

**The remedy is different from Core 9's, which is why this is worth separating.**
There the fix is to move the check to where the real noun is visible. Here the
noun is already right, so the fix is to **report the reach**: `rename_sweep`
saying "15 forms, and 3 I could not address" costs one line and turns a silent
class into a visible one. Same for an ad-hoc query — put the population count in
the result, always, because `{:nameless []}` and `{:forms-scanned 248
:nameless []}` are the same answer and only one of them is evidence.

**The tell, adapted from Core 9's:** *what does my traversal/scan/index address
things BY, and what is addressed some other way?* Names, paths, symbols and
types are all addressing schemes, and every one of them has residents that do
not have one. A form with no name, a reference living in a config value, a node
spliced rather than wrapped — each is a citizen of the subject that the
addressing scheme cannot name, and therefore cannot count.

## Core 10 — a refusal is read in FIX-IT mode, and design rationale is a different room

**Root.** Named 2026-08-01 from the same cold read. slopp-ui hit
`web-undeclared-context` unprepared, deliberately without opening the SKILL,
and reported which clauses did work.

**What carried the whole thing was a LITERAL FORM**, not a description of one:

```clj
(defn ^{:web/context true} app-context [] {…})
```

From that alone: the marker spelling, the arity, `defn`-not-`def`, the return
shape. No step sends the reader looking anything up. *"That is the difference
between a refusal that teaches and one that announces."*

**What did not carry was the sentence I was proudest of.** The refusal argued
that the context cannot be a performer — true, non-obvious, and the answer to
a question the reader had not asked. They had already been handed the form;
they were not reaching for a performer. It was also the only clause requiring
knowledge of what a performer IS, so it was the one clause opaque to a
first-time reader, inside the string a first-time reader is most likely to
meet.

**The two rooms.** Someone DECIDING how to model a context meets the
docstring, `query_rules`, the SKILL — all fine places for "and here is why it
cannot be a performer". Someone REFUSED is mid-write with a broken store; the
question is *what do I type*. Rationale in that room does not just fail to
help, it argues with an idea the reader has not had.

**Discipline.** A teach string carries three things: WHAT is wrong, the
CONSEQUENCE if it shipped, and the FIX AS A LITERAL FORM. A fourth clause has
to earn its place by changing what the reader WRITES, not what they
understand. The lifecycle caveat passes that test — the obvious builder is
whatever the app's `serve!` already constructs, moved, which is exactly the
shape that silently empties, so being told before beats being told after by a
silent bug. The performer clause fails it. Cutting it took the refusal to
about two-thirds its length and lost nothing a refused reader could use.

**The measurement is one-shot and worth protecting.** This only works from a
reader who has not yet learned the rule. Ask them to hit it cold, report
BEFORE fixing, and answer "could you act on it without going back to the
skill?" — not "was it clear". "I re-read the skill" is a failure even when the
reader ends up in the right place. slopp-ui held their fix specifically to keep
this measurement spendable, which was the right call and better than the
instruction they were given.

### Sharpening (2026-08-03): the FIX clause names an ACTOR, and it has to be one the reader can be

Core 10 says a teach string carries the fix as a literal form. A REPORT — a
verdict note, a brief line — carries the fix as an INSTRUCTION, and the same
discipline applies to a part the form version never has to think about: who
performs it.

`full_check`'s stale-image note said *"treat it as suspect and restart the
server"*. slopp-ui read "the server" as the MCP process a human owns, concluded
the staleness was unfixable from where they stood, and reported it as a wall
that wanted a mechanism. `restart` was in their tool list. **A remedy is only a
remedy if the reader can be its subject**, and "the server" named a subject they
could not be.

**The correction then failed the mirror test, which is the part worth keeping.**
Naming `restart` in every branch reads obviously right and is wrong:
`slopp.ops/restart!` calls `session/fresh-image!` — it replaces the VERIFICATION
image and never touches the serving JVM. Two of the three branches are about the
host. So:

> A remedy the reader cannot run and a remedy that does not work fail the same
> way. The second is harder to catch, because the sentence reads helpful.

**And silence is not the safe third option.** Having split the two, the tidy
move was to leave `restart` out of the host notes. But a reader just told their
verdict is suspect will reach for the one restart-ish verb they have, mentioned
or not — so omitting it costs the same call as recommending it. The host notes
now name the tool in order to RULE IT OUT. Ruling out is a fix clause; it tells
the reader what to type by telling them what not to.

**The check that catches this is cheap and was nearly skipped.** The first
assertion written here — "the note contains `restart`" — went green immediately,
against the exact sentence being fixed, because *"restart the server"* contains
the word. An assertion about a REPORT's wording is an absence-shaped measurement
wearing a presence costume (Core 1); watch it fail before believing it.
