# Dogfooding & benchmarks

## User testing (standing practice)

Build real things **through slopp itself** — the agent is the target user, so
this is user testing in the literal sense.

- Work in `projects/<name>/` (gitignored; never check these in).
- Drive `slopp.api`/`slopp.mcp` the way an MCP agent would (form-addressed
  ops, one write at a time, read only what you need).
- Log honestly to `projects/<name>/REPORT.md`: what worked, friction ranked
  by severity, meta-observations. Findings become F-numbered roadmap items in
  `.context/decisions.md` + tasks.
- First session: CLI calculator (2026-07-02, `projects/calculator/REPORT.md`).
  Wins: tight add→verify loop, real affected-narrowing, honest diagnostics,
  trustworthy rename, story-like lineage, seamless persistence. Friction:
  F1–F8 (F1 fixed).

## THE benchmark: neutral-spec cohort evals (user decision, 2026-07-12)

A list of MCP calls is not a benchmark. What differentiates slopp from a
conventional agent is measured ONLY by: the SAME end-user spec (observable
behavior + an I/O contract; zero implementation details — no namespaces, no
fn names, no tool mentions, no workflow commands), the SAME neutral prompt,
and the ENVIRONMENT as the sole variable (slopp plugin installed vs not).
The agent chooses everything — including whether to use slopp at all; an
agent in the plugin cohort ignoring slopp is a discoverability finding, not
a protocol error. Acceptance follows the agent's own run instructions
(mechanical I/O checks against the spec's contract). Protocols live in
projects/eval*/SPEC.md with verbatim prompts.

The scripted suite below is demoted to an edit-path wire-cost REGRESSION
METER (catches payload/latency creep per write op); its rows are not
evidence about agent-level value. Wave-5-style guided evals measure "does
the taught workflow function", not "does the product win".

## Wire-cost regression meter (`slopp.benchmark`)

Purpose: track whether the product is getting better to use — **wall time**
and **token cost** (chars/4 of the JSON actually sent/received through
`mcp/handle`) to build each sample app via scripted agent sessions.

- Run: `clojure -M -m slopp.boot . --snapshot --main slopp.benchmark/-main`
  (the tree is fileless — plain `-m slopp.benchmark` finds nothing; NOT part
  of `clojure -M:test` — it
  spawns several JVMs and takes minutes).
- Each app = a deterministic script of MCP tool calls (deliberate red steps
  included — debugging is part of real usage). The run fails loudly if an
  app's final test run isn't green.
- Results append to `benchmarks/results.md` (committed — it's the progress
  record): git sha, app, steps, wall ms, tokens in/out.
- **Run it when the numbers should be changing** (user guidance) — edit-path /
  verification / restart changes, not routinely. Skip it for query additions,
  docs, or anything off the measured path.
- Scripts should exercise *current best practice* (e.g. once edit groups
  exist, the multi-form fix step uses them) — the benchmark measures the
  product as it's meant to be used. When a script changes, note it in the
  results row; wall/token comparisons are only valid between rows with the
  same script version.

## Benchmark repeatability (standing rule, user-requested)

Nothing under `projects/` is committed — the whole directory is gitignored
(user decision, 2026-07-04; it briefly had gitignore negations tracking the
files below). Every eval-project dir still carries three LOCAL files that
make reruns reproducible — keep maintaining them, just never `git add`
them:
- **`SPEC.md`** — the exact task text given to agents + the VERBATIM harness
  wrapper prompt (only ports/paths substituted) + how to regenerate the seed
  + measurement sources. Write it BEFORE launching agents. The wrapper
  shapes agent behavior as much as the task does — it must be reproducible.
- **`RUNS.md`** — append-only history: date, model (**exact model id, not
  an alias** — aliases drift as models update; a file-level note maps any
  historical alias rows to the ids they resolved to at recording time),
  setup (`slopp@<sha>` / `files` / `go`), true tokens, duration, tool
  calls, payload metrics when available, outcome/notes. Append a row per
  run, including reruns after product changes.
- **`accept.sh`** — the executable acceptance check (`./accept.sh <port>`,
  exit 0 = pass), run BY THE ORCHESTRATOR against each cohort's finished
  store. Agent self-reports are never trusted; the same script scoring
  every round is what makes outcomes comparable over time.

Two comparability caveats to restate in results write-ups: cells are n=1
(setup reproduces exactly; numbers carry single-run variance), and when
reading server-side `/metrics`, filter to the agent's run window by
timestamp — orchestrator acceptance probes pollute the tail.

## Conventional-workflow baselines (one-time rows)

The same three apps built in **Go by fresh sub-agents** (no context from the
slopp side), one run per model (haiku/sonnet/opus), conventional files +
`go test` workflow. Instrumentation is mechanical so metrics come from
artifacts, not self-reports: `git commit` after every write (tok-in = summed
byte sizes of changed `.go` files per commit), every test run tee'd to
`.runs/` (tok-out), `date +%s` stamps (wall). Measured by
`benchmarks/measure_go_baseline.sh`; recorded once per app×model with
`v = go-<model>`.

**Comparability caveats (keep honest):** Go wall time includes the agent's
thinking time; slopp script rows are deterministic replays (no thinking, no
model). Token metrics compare the *workflow shape* (whole-file writes + test
output reads vs. form writes + structured results) — that's the comparison
that matters. A fully symmetric eval (fresh agents driving slopp over MCP per
model) is roadmap #1 territory.

## Demand observation (discoverability countermeasure)

Needs with a workable manual path never announce themselves ("do agents not
reach for it because they don't know it exists?" — rename would have failed
that test too). Two standing instruments keep the tool surface matched to
REAL demand:

1. **Workaround mining** — `clojure -M -m slopp.mine <store-dir> ...` scans
   any provenance journal for manual refactoring shapes (change-signature:
   defn arg-vector changed + nearby caller replaces; inline: defn deleted +
   nearby caller replaces). Run it over every eval store and dogfood project
   as part of writing up a round.
2. **Friction-report prompt line** — every eval/dogfood agent prompt MUST
   include: "if you ever edit a function AND its callers for one logical
   change, or hand-inline a function, or repeat any other multi-step pattern
   for a single intent — say so in your report." Self-reports are the most
   productive signal this project has (all of F/T/E/S came from them).

Deferred ops become built ops when either instrument fires — never before
(speculative surface confuses weak models), never only on reach-for silence
(silence is what workarounds sound like).

3. **Transcript analysis** — after each round, spawn analyst sub-agents over
   the raw agent transcripts (the harness task `.output` JSONL for
   sub-agents; `~/.claude/projects/<workspace>/` session files for real
   Claude Code runs) with the standing rubric: every error + recovery cost,
   redundant calls, N-calls-where-1-would-do, semantics misunderstandings,
   invented tools/args, and (for curl runs) shell-corruption of payloads.
   Self-reports UNDERSTATE friction — the 3d round's reports said "no
   friction" while the transcripts showed a misfired tool lookup, ignored
   hints, impl-first TDD, and a silently weakened test assertion.
   **Token/usage decomposition goes through `benchmarks/mine_transcripts.py`**
   (Q6) — it dedupes by message id and REFUSES an unscoped directory sweep
   (`--dir` requires `--since`): eval rounds sharing a run path once summed
   into each other and corrupted a round-3 comparison. Never hand-roll this.

Wiring self-check: `slopp --doctor` (launcher) verifies java, the jar
cache/checksum, hook + skill files, python3, and probes the store with a
read call — run it FIRST when a session's tools misbehave, instead of
re-deriving the wiring by hand (two hook-debugging detours predate it).

## Curl-bridge harness notes (NOT product guidance — native MCP has none of this)

For eval prompts that drive slopp over the HTTP bridge:
- Mandate single-quoted `-d '...'` payloads. Double quotes let bash eat `$1`
  from source strings (haiku shipped a weakened assertion routing around
  it); doubled backslashes turn `\n` into stray char literals.
- Recommend batching several curls per Bash call (sonnet: 38 HTTP calls in
  20 Bash invocations).
- State that slopp tools are NOT in the client's local tool registry —
  curl is the only path (sonnet burned a ToolSearch discovering this).

Sequential same-dir sessions (lifetime evals): decompose per transcript
FILE (one file per `claude -p` session, mtime-ordered), not per time
window — windowed sweeps double-count boundary files (found in eval8).

## Self-dogfooding outranks benchmarks (standing, user directive 2026-07-14)

The agent building slopp THROUGH slopp is a better (though unrepeatable)
signal than the eval numbers: benchmarks tell you WHERE cost sits;
first-person friction tells you WHY and what the fix should feel like.
Standing practice: while working, notice every workaround, hesitation,
external escape (Bash/sqlite instead of a tool), and re-read — and turn
them into findings the same day (the Q-series pattern). When a benchmark
result and first-person experience disagree, trust the experience and
re-examine what the benchmark actually measures.

Current first-person backlog (2026-07-14): (1) RESOLVED by protocol:
tool-list staleness on live servers is dev-only (fresh sessions get
fresh servers) — the agent ASKS the user to restart after adding or
retiring wire tools (user decision, 2026-07-14); the tools/list_changed
notification is a someday-nicety, not a need; (2) bulk source reads for planning still route around the
wire (sqlite dumps to a scratchpad) — efficient but caused the stale-dump
clobber; a safe "render to files for reading" flow may be worth having;
(3) targeted isolated runs (Q2) are the single highest-leverage fix this
week by lived experience — protect that path.

First-person friction (2026-07-14, cards/differential wave) — BOTH
RESOLVED same day: edit_subform `where: {key value}` addresses the
unique map containing those entries (registry rows by :name; its first
live use was editing its OWN descriptor, which caught a stale-schema
string-coercion bug); cold-load refusals now name the exact edit_move
call (both names were already in the finding).

Benchmark cadence (user directive, 2026-07-14): the lifetime evals run
ONLY on explicit request or a genuine decision need — never routinely
per change wave. Self-dogfooding is the continuous gauge; the benches
are for claims.

Planning-scale reads (sanctioned bulk path): `build {dir <scratchpad>}`
materializes the whole store as files for grep/read-at-leisure — a
read-only SNAPSHOT (edits still go through the tools; re-build after
writes; never slice stale dumps into edits — the Q4 clobber lesson).
Prefer it over raw sqlite queries against store.db.

First-person friction (2026-07-14, module-system wave) — all fixed the
same day, in-wave: (1) a red isolated run capped :failing at 5 blocks,
so enumerating 50 fallout failures took fix→rerun loops at ~3min each;
isolated results now also carry :all-failing {file [test-names]} — the
whole fallout map in one run. (2) `test_run {only [...]}` without
:isolated silently ran 0 tests when given ns-qualified names (the live
path expected plain names scoped by :ns); qualified names now
auto-scope, and a 0-match run says so instead of passing green.
(3) edit_subform demanded :form where every sibling tool says :name —
it now takes either. (4) Inherent bootstrap catch-22 worth remembering:
hot-loading a new GATE into the live store enforces it before the
adoption/migration machinery exists — sequence gates AFTER their
adoption story (the :modules nil marker resolved it here).

First-person friction (2026-07-15) — six items from the module-system
window, ALL FIXED same day: (1) the full isolated suite (~3.5min) was
the only gate — `test_run {isolated true, affected true}` now runs just
the test namespaces whose require-closure reaches a change since the
last milestone (provable slice; empty slice says so; full suite stays
the milestone gate). Fixing it exposed that the :test alias's baked
`-r ".*"` (Q13) UNIONS with -n in cognitect's runner — :ns narrowing
had been silently broadened ever since; narrowed runs now use a
filter-free :test-run alias. (2) big edit_group payloads truncated on
the wire and splitting broke atomicity — stage=open/add/commit builds a
group across calls, validated at staging, committed as ONE intent.
(3) the compile gate refused specs referencing not-yet-written fns
(green-first inversion) — writes into -test namespaces now intern
throwing IMAGE stubs for missing store vars and land as a REAL red
(:red-first names them); the isolated suite still refuses until
implemented, which is honest. (4) giant forms (call-tool ~320 lines)
kept tripping the response trim — query_slice match+window returns the
neighborhood around the match. (5) 50-failure fallout was 3 root causes
— red isolated runs now carry :themes (phrases clustered by
distinct-test coverage, >=3). (6) the observe-only guard refused
`'#{defn defmacro}` as a quoted literal — it now checks POSITION
(quote-pruned walk), so read-only censuses pass while `apply` smuggling
still refuses. Lesson from the wave itself: a spec that mis-arities an
api call can burn a debug cycle — the keyword-slid-into-source error
("count not supported on Keyword" from parse-form) is worth a nicer
message someday.

Red-first generalization (2026-07-15, user directive: "make sure
red-first stubs cover everything... more purely in the test reporting,
however we got there"): the per-command wiring (add-form!/edit-replace!
only) was the bug factory — groups, ingest, and :refer'd names were
uncovered, and FUTURE commands would silently miss it. Moved the whole
mechanism to the compile gate: any -test namespace that fails to load,
by any path, stubs its missing store vars and retries (failure-
triggered, so zero cost when green). open!/fresh-image! do the same, so
a store carrying an unimplemented spec still opens and restarts. Found
en route: the MCP layer's select-keys silently DROPPED :red-first from
write results — the api-level spec passed while agents on the wire
never saw why their spec was red. Lesson: when a result gains a key,
grep the wire's select-keys; result-shape specs should ride the WIRE
(mcp-test `call`), not just the api. The refactor's 16-delta group was
staged (stage open/add/commit) — first real use, worked as designed.

Inferred-episodes wave (2026-07-15): the grouping burden moves off the
agent — see decisions.md for the terms + design. Ledger notes from the
wave itself: (1) the lint-refusals arity change deadlocked the write
pipeline mid-wave (self-hosting: hot-loaded fn vs committed callers) —
rescued by a user-approved hand-minted append!-equivalent delta; rule
recorded. (2) The carried-errors design would have made that exact
accident survivable — the change prevented its own class of failure one
hour too late. (3) rename_sweep handled the checkpoint→done concept
rename (35 forms, 11 nses, one intent) with the journal op migrated by
one-off UPDATE (85 rows) — sweep-then-migrate-immediately kept the
boundary-blind window to minutes. (4) The staged-group machinery shipped
last wave was removed by this one at the user's direction — measured
lesson: don't gold-plate a crutch when the real fix is removing the
need for it.

Plan-mode prompts (2026-07-15, user friction): allowlists don't help in
plan mode — the client only auto-permits tools that PROVE read-onlyness
via the MCP readOnlyHint annotation; slopp's read tools now declare it.
Lesson: when a client keeps prompting despite allow rules, the fix may
belong in OUR wire contract, not the user's settings.

Benchmark dogfood of the inferred-episodes flow (2026-07-15): findings
in benchmarks/results.md (interim-red cost quantified; :suggest fat
removed; done!'s green-by-vacancy fallback caught by its own first real
use and fixed via closure-bounded selection — test-nses-reaching now
shared by done! and the affected slice). Stale command fixed: the
benchmark runs via the boot kernel (`clojure -M -m slopp.boot .
--snapshot --main slopp.benchmark/-main`) — the tree is fileless.
Improvement candidates parked: :still-red compression for repeated
identical failure sets; per-write :all fallback stays ns-scoped by
design (fast) now that the done-point is the reaching safety net.

Episode diet (2026-07-15, user directive "more efficient than before"):
the interim-red cost was engineered away the same day — episode-red
diffing (:still-red/:went-green, detail rides once), advisories at the
boundary, :type off the wire. Benchmarks: inventory/wordstats −25% vs
the OLD flow; calculator at parity with two honest reds shown. The
diet lesson generalizes: mid-episode responses should report DIRECTION
(what changed state), not restate standing state — the boundary owns
the full picture.

eval9 episodes run (2026-07-15): lifetime 0.63× (best recorded; was
0.82×), handoff flipped 1.24×→0.24×, acceptance 11/11 both cohorts,
full adoption of done/module_dep/rename_sweep with zero hints and zero
removed-tool confusion — details in projects/eval9-lifetime/RUNS.md.
The honest wrinkle: raw model-output tokens now favor plain (1.24×) —
tool traffic is won; the next frontier is the agent's own narration
around results. Friction fixed in-run: report :verify hands over the
one-shot CLI forms (handoff agents were grepping skill files for them).

Wall + narration measurements (2026-07-15, post-eval9): per-call mining
of the eval transcripts + 36h of own sessions. Findings: (1) plugin
agents' WALL is ~98% model generation — total slopp tool execution
across five eval steps was ~22s (writes ~0.15s, done ~0.5s) vs plain's
137s of Bash test runs; the wall fix IS the token fix. (2) The
narration sink is between-call PROSE (79k chars vs plain's 38k), not
thinking, not tool inputs — slopp's structured inputs are CHEAPER than
plain's Edit strings (27k vs 45k). Skill + brief now carry the diet:
"results are self-describing — act on them, don't narrate them."
(3) At repo scale: targeted in-image runs cost 43ms (nothing to fix);
per-write seconds are kondo on giant nses (slopp.api renders 219KB →
~500ms/analyze; semver 4ms) — the STRUCTURAL fix is splitting slopp.api
into deep modules (named target; also dogfoods deep-module machinery);
the isolated full suite dominated everything (~15 runs × ~4-6min) →
test_run {isolated, parallel N} shards it (1.9× at N=4, same merged
truth). (4) The author reached for removed edit_group out of muscle
memory; the eval agents never did — teaching beats habit.

Per-write kondo + test_run interaction (2026-07-15, user questions):
kondo was already SCOPED to changed namespaces (lint-refusals lints only
the written ns; done lints only changed nses — never project-wide). The
waste was structural: analyze and lint ran as TWO kondo passes over the
same content, and lint wasn't memoized, so every write re-linted the
unchanged base. Unified into one memoized run-kondo — measured ~541ms
saved per consecutive write to slopp.api (the base pass is now a cache
hit). test_run wire guardrail (user): bare test_run {} returns guidance
(done runs your affected tests; name a target to spot-check), whole
suite is explicit ({all true} in-image with a done-covers-it note,
{isolated true} the merge gate). isolated :parallel now defaults to
AUTO — scales shards to test-ns count + cores, serial below ~8 nses.
The whole wave dogfooded the diet: :still-red/:went-green/:carried-errors
all fired on their own author's edits and read cleanly.

review_scan tool + the review-tooling assessment (2026-07-15, user): a
code review is mostly read/navigate, and the honest verdict is slopp is
AS GOOD OR BETTER than files for reviewing a CHANGE (query_changes gives
form-addressed diffs + the recorded why + red/green arc; brief/slice give
coverage+blast+effects inline; query_eval runs the code) but SLOWER for
free-roaming sweep (build-to-scratchpad is the escape hatch). The gap
worth a tool was whole-codebase triage → review_scan (risk-ranked forms).
Independence matters more than tooling: an author's self-review is weak
regardless, so route real reviews through the slopp-reader subagent
(fresh eyes). Dogfooding the new tool on slopp itself immediately caught
its own worst bug: a trace-only :untested signal flagged 545/966 forms
because slopp's tests are ^:isolated (external JVMs, invisible to the
in-image trace) — fixed with static call-graph reachability, dropping
false positives to 72 real ones.

## The slopp.api deep-module split — MEASURED, and the premise was wrong
(2026-07-16, user-directed; friction-hunting run)

The parked plan was "split slopp.api into depth-3 `slopp.api.*` namespaces,
package-private by recursive visibility, slopp.api stays the public surface —
and win back the ~500ms/write kondo cost on its 195KB." Measured against THE
reference graph before touching anything:

- slopp.api: **103 forms, 195KB**. Of that, **only 23 forms / 22KB (12%) is
  genuinely internal** (no callers outside the slopp.api module, production
  edges only). **78 forms / 171KB (88%) is PUBLIC surface** — called by
  slopp.mcp, slopp.sync, slopp.http, slopp.bench, slopp.evalseed. The biggest
  forms are ALL public (query-history 12.8KB, done! 12.3KB, move-forms! 8.4KB).
- So a package-private deep split **cannot deliver the kondo win**: the cost
  IS the public surface, and package-private is exactly what public can't be.
  Moving 12% is not worth a refactor.

**The resolution (user's call, and it's the right model): cohesion decides
WHERE code lives; the export dial decides WHO sees it — they are independent.**
Don't keep a form in a god-namespace *because* it's public, and don't distort
structure to dodge an `^:export` marker. Move the cluster where it belongs and
export the genuinely-public vars. That unblocks the 88% and makes the marker
say something true. Now taught in the slopp SKILL.

Proof done this run: the branch/line cluster → `slopp.api.branch` with
`export: true`. `move-forms!` correctly pulled the **downward closure** (the
cluster's private helpers came along unasked). slopp.api **102→95 forms,
195→185KB**; branch tests green. The remaining clean clusters (measured,
zero escapes): deps (3KB), files (5KB, 1 escape via `config!`→`git-config-value`).
The real bulk is the big public ops (done!, edit-group!, move-forms!,
isolated-test-run!, review-scan, commit-point!, build!) — each its own cluster.

### Friction found (each cost real time; several are now fixed)
1. **Stale declares mint PHANTOM forms in `query_source`'s outline** — the
   agent is told about forms that DON'T EXIST (`status-at`, `human-time`, …
   were lifted to api.history/api.session by earlier moves; their names stayed
   in slopp.api's declare). Trying to move them: "no such forms". FIXED.
2. **An anonymous form is unaddressable by the agent-facing tools.**
   `query_search` reports the declare as "form f463"; `query_source {targets}`
   and `edit_delete_form` take only a NAME and reject the id — and the outline
   doesn't list it. The one wire-uniformity hole: ids come OUT but don't go IN.
   (decisions.md says ids should be accepted alongside qsyms.) STILL OPEN.
3. **One phantom froze its whole declare forever** (`:skip` blocked removal).
   FIXED — and it cleared f463 + finally `(declare isolated-test-run!)`.
4. **TWO ordering algorithms** (Kahn topo sort vs fix-declares!'s conservative
   mover). FIXED — one algorithm; fix-declares! delegates.
5. **`move-forms!` mints its own UNMARKED declares** — the source of (1)/(3).
   FIXED same day: `refactor/move-plan` emitted one UNCONDITIONALLY for any
   `(> (count moved) 1)`; both sites removed, `move-forms!` now calls
   `resolve-cold-load` on the target. `edit/declare-node` is the only declare
   builder left in the store. Correction to my own reasoning: a moved
   subsequence CAN have a forward ref (the source may order caller-before-callee
   behind a declare that stays behind — declares are anonymous, never moved),
   which is what the planner was compensating for; the right answer is to
   REORDER, not to mint.
6. **`query_store` refuses `read-string` citing D3** — FIXED 2026-07-16, after
   I got it wrong TWICE. Worth keeping as a worked example of how a friction
   note should be interrogated rather than believed.
   - **First read (wrong):** "the dialect gate is over-applied to read-only
     analysis." No — `read-string` really executes via `#=()`, so refusing it
     in a sandbox is CORRECT.
   - **Second read (also wrong):** "query_store reuses D3's list; give it its
     own." It ALREADY had its own (`edit/pure-eval-refusal`, which already
     banned `read-string`). D3 just fired FIRST, because `query-store` parsed
     through `parse-form`.
   - **The actual finding, proven with a probe:** the coupling was
     LOAD-BEARING. D3's resolver ban was the ONLY thing blocking
     `((requiring-resolve 'clojure.java.shell/sh) …)` — the sandbox never saw
     it, because a resolver is not an effect name and `walk-pruned` PRUNES the
     quoted target symbol. So a SECURITY property rested on a coincidence in a
     list maintained for stored-code ANALYZABILITY, with no test guarding it
     (`pure-eval-refusal` had `:covered-by []`). If the resolvers ever moved
     out of D3 — plausible, they live there per the reference-CARRIER decision
     — the sandbox would have opened SILENTLY.
   - **Fix (all 3 steps):** `pure-eval-refusal` absorbs the resolvers +
     `definline` and says why; `orientation-test/sandbox-refuses-resolver-escapes`
     tests it INDEPENDENT of D3 (that test is what fails if the coupling
     returns); `edit/parse-one` is the raw parse (`parse-form` = `parse-one` +
     gate) and `query_store` uses it, so the sandbox is the sole gate and the
     teaching is right. Bonus irony: the guard test needs `^:unsafe` because it
     MENTIONS the resolvers — same standing as `edit/banned-syms` itself.
   - **Lesson:** two wrong diagnoses in a row, both fixed by reading the code
     instead of trusting the error message. The error message named D3, so I
     believed the coupling was the whole story; the real question was "what
     would still be blocked if I removed it?" — and that took a probe, not a
     theory.
7. **My own regression, caught by dogfooding:** the new `:isolated-pending`
   dumped ~200 test names into every whole-project `done`. Compressed to
   count+sample and suppressed at the boundary. Mid-episode responses must
   report DIRECTION, not enumerate standing state — the recurring lesson.
7b. **And I mis-diagnosed that same fix — corrected 2026-07-16.** I wrote that
   `^:isolated` tests "executed in-image and false-greened". They did NOT:
   `slopp.rt/traced-run` has dropped them unconditionally since **d980**
   (`query_history` on the form proves it). The real bug was that the skip was
   **SILENT** — the summary reported OTHER tests' green while quietly dropping
   the test the agent had just written. The fix is right in effect (report
   `:isolated-pending`), but the tier-filtering half duplicates rt and the
   "`[]`-means-all hazard" I guarded against doesn't exist. Third wrong
   diagnosis in one session (export dial, query_store ×2, this) — every one
   fixed by reading the code or the history instead of theorising from an
   error message. `query_history {ns name}` answers "was it always like this?"
   in one call and I keep not asking it.
8. **Test namespaces are FEATURE-named** (`slopp.history-test`,
   `slopp.episode-test`), not subject-named, so the module test-fold assigns
   them to phantom modules (`slopp.history` doesn't exist) and they can't see
   the deep namespaces of the module they actually test. Inflates "external"
   and would force exports for tests alone. Renaming them `slopp.api.*-test`
   would fold them correctly. OPEN — worth doing before more splitting.

### The "ns-level export dial" gap — RETRACTED, it wasn't real
I first recorded this as "an ergonomics gap worth a feature": every public var
in a deep ns needs its own `^:export`, so the full split means "~78 identical
markers", and an ns-level dial would say it once. Interrogated (user: "what is
the overall point — to avoid putting ^:export on all the functions?"), it
collapses. Recording the retraction because a finding here IS a roadmap input,
and a disproven one sends the next agent to build the wrong thing.

- **The ceremony never existed.** I typed ZERO of those 6 markers. `export:
  true` was passed ONCE to `edit_move_forms`; `refactor/move-plan` applied
  `export-mark` to every moved node. "Say it once" is already implemented — at
  the tool. I complained about work I never did.
- **The arithmetic inverts on the motivating case.** A deep ns is MIXED.
  Measured `slopp.api.branch`: 6 exported API vars + 6 helpers. An ns-level
  `^:export` exports the helpers too, so it needs per-var opt-outs:
  **1 + 6 = 7 markers vs 6 today.** The feature makes its own motivating case
  worse. (The count only wins on a ns that is ~all public surface — none
  measured.)
- **The residual is thin and self-correcting.** A public fn added later must
  carry `^:export` by hand (`edit_add_form` has no export flag); forget it and
  the module gate refuses WITH teaching. Each marker honestly asserts "this IS
  public surface" — signal, not noise.

Two research findings kept, in case it's ever revisited:
- **`export-level` ALREADY reads ns-form metadata** — verified live:
  `(export-level st 'zz.core.deep 'zz.core.deep)` → `"zz"`, because
  `(second '(ns ^{:export "zz"} zz.core.deep ...))` is the name symbol carrying
  meta, structurally identical to `(defn ^:export f ...)`, and the ns form is
  already a store form named by the ns symbol. No new reader would be needed;
  the work is the level-combination rule in `module-violations`' inline
  `visible?` predicate + two readers that bypass `export-level`
  (`api.modules/module-surface`, `edit.modules/missing-doc-warning` hand-roll
  `(:export (meta (second s)))` — `module-surface` under-reporting a module's
  surface is what browsing agents trust).
- **`publicize` is not a bug.** `move-plan` publicizes every moved form, so
  those 6 helpers are public `defn`s called only from their own ns. That is
  coherent with "module-grain visibility replaces var privacy": the depth-3
  package-private rule already makes them unreachable outside `slopp.api.*`.
  The ONLY thing that would leak them is an ns-level `^:export` — the feature
  being closed. The move pulled a cleanly downward-closed cluster; it did well.

**Lesson (the recurring one).** This is fast-cold-truth again: a plausible
ergonomic complaint, recorded mid-flow as a "gap", that measurement kills. The
tell was that I never checked who does the work I was complaining about. Before
a friction note becomes a feature, name the call that costs you and count it.
