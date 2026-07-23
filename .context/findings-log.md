# Findings log

Historical findings from user tests, eval rounds, probe sessions, and
self-dogfooding turns. **This is a record of what was OBSERVED, not what was
decided** — settled decisions live in `.context/decisions.md`, and open
frictions live in `ideas/`.

Kept because the observations are evidence: several slopp rules exist
because of a specific failure recorded here, and re-reading them is how we
check whether a rule still earns its cost. Nothing here is authoritative
about current behaviour — a finding describes the system on the day it was
written.

## Review sweep 2026-07-20 — adversarial whole-store review + fixes

A six-agent adversarial review of the whole store (each reader verifying its
findings against the live image), then fixed across eight milestones
(d9432..d9660). What it surfaced, grouped by where the defect lived:

- **Vocabulary migration was half-done and it crashed a live tool.** The
  d9077/d9157 tier rename (`:reads`/`:effects` → `:internal`/`:external`)
  migrated the write gates but not the reporting arm: `purity-standing` still
  ranked with the retired table, so `query_depends {modules true}` NPE'd on any
  store carrying a canonical tier. A shared `canonical-tier` normalizer now
  backs every reader, and `module-tier!` stores canonically. LESSON: a rename
  enforced by some consumers but read by others drifts silently — the ones that
  only READ a value (a keyword in a rank map, a filter set) are invisible to the
  gate that renamed it. This is the keyword-as-second-class-reference problem
  (`ideas/keywords-are-second-class-references.md`) as a live incident.
- **The functional-core gate had reachable holes.** `:pure` admitted console IO
  (`println`), watch mutation (`add-watch`), a var-quote CALL `(#'f x)` (kondo
  sets no `:arity`, so it read as a data carrier), and a `(store/late-ref …)`
  into the shell (invisible to both the require graph and effect derivation — and
  the dialect gate ROUTES agents to late-ref). The D3 dialect denylist matched
  whole symbols, so `(clojure.core/eval x)` sailed past while `(eval x)` was
  refused, and banned symbols hid in literal metadata. All closed; the
  read-only `query_store` sandbox also admitted static interop
  (`Files/delete`), now refused.
- **The milestone gate was quietly weaker than a plain done.** `commit-point!`
  called `done!` with `:external? false`, so a milestone skipped the impacted
  `^:external` slice a standalone done runs — reproduced laundering a touched red
  external test green. Fixed to a real done; `decisions.md` D-full-check now
  spells out that "touched" is load-bearing. (A pinned test had drifted to pin
  the bug; rewritten to pin the decision.)
- **The edit pipeline could corrupt name-addressing and lie about reverts.** A
  replace that RENAMED a form onto an existing name landed two definitions of
  one name; the group path skipped the ambiguity refusal; deleting a `(ns …)`
  form was allowed (and `undo!` of an ingest generated exactly that); `undo!`
  reported success on a conflicted group. All guarded; every lost-race conflict
  now heals the image (the loser's code was hot-loaded, and a two-writer store
  kept the losing image answering with rejected code).
- **The process shell leaked JVMs.** The parent-death watchdog (d9279) installed
  too late — any throw in the spawn→connect→inject window abandoned an nrepl JVM
  that outlives parent death; it now boards the child's launch command line.
  `read-port`'s timeout wasn't enforced during a blocking read; the external test
  runner was unbounded and watchdog-less; `close!`/`open!` leaked on a partial
  failure. All fixed.
- **Two false-positive fixes I DIDN'T make, and why.** Defaulting `done!`'s agent
  to the session id (to close a nil-agent "footgun") broke 42 tests — the
  direct-API convention is nil-consistent by design; reverted. The nil-agent
  laundering is unreachable via the wire. LESSON: `full_check` caught both the
  chunk-1 stale-test regression and this over-fix — a broad change to shared
  machinery is exactly when the whole-store gate earns its cost.

The friction of doing all this THROUGH slopp (where it slowed vs helped, and
larger rethinks) is logged in `ideas/dogfooding-agent-frictions.md`.

## F — user-test findings (status)

F1 failure-details in results ✅ · F2 atomic edit groups ✅ (calculator bench
−49% wall) · F3 `{:error}` on unparseable source ✅ · F4 `create-ns!`/
`ns_create` ✅ · F5 `add-require!`/`ns_add_require` ✅ (structural, dup-checked)
· F6 VFS-mapped stack traces ✅ (nREPL load-file + row padding; frames cite the
exact lines `query-source` shows) · F7 ✅ **decided (user): `!` = mutation only**, per Clojure convention —
stdout/console IO is NOT a `!` trigger; if IO tracking ever matters it becomes
a separate `:effects` fact, never a naming rule · F8 ✅ (ingest tidy-return; `:untested`
flag on edits no test exercises; `build!` emits `src/` + minimal `deps.edn`).
Details: `projects/calculator/REPORT.md` (untracked) and `.context/dogfooding.md`.

## T — tasker user-test findings (round 2, through the MCP wire)

T1 ✅ deftests exempt from the `!` rule · T2 ✅ orientation queries
(`query_namespaces`, `query_outline`) · T3 ✅ edits report only NEW `!`
violations + `:existing-warnings` count · T4 ✅ ingest/ns-create load the image
FIRST and commit only on success — a failed require/compile returns `{:error}`
with no store/image drift · T5 ✅ `query_eval` is observe-only by construction
(`edit/observe-gate` rejects def/in-ns/ns-unmap/alter-var-root/...; calling
effectful fns remains allowed — that's observation) · (obs.) hot-editing a
`(def x (atom ...))` form resets its in-image state — tests re-seed so verify
is unaffected; D5's defonce-preservation opt covers it if it ever matters.
Details: `projects/tasker/REPORT.md` (untracked).

## S/E — symmetric-eval findings (fresh agents driving slopp per model)

S1 ✅ **every write must compile**: all hot-loads checked against the candidate
store before commit; forward refs rejected at write time ((declare) is the
mutual-recursion escape); partial group loads restore a fresh image. This
transformed weak-model runs (haiku: +114% vs Go → beat Go outright on
inventory). S2 ✅ `edit_move` (stylistic/structural reorder; `:move` delta) ·
✅ `ns_remove_require` + unknown-tool errors list available tools (agents
invented both names) · E1 ✅ edit_rename arg aliases + clear missing-arg
errors (every sonnet/opus run guessed name/to first) · E2 ✅ SKILL.md teaches
the two-write red-first TDD shape (fn+test in one group → honest red →
replace). Full data: `benchmarks/results.md` symmetric-eval sections.

## R — eval round 2 (modify-and-extend, seeded codebase)

**Honest result: files won at ~60-line scale for all models** (+32..98% tok,
2.3–3.2× wall). Cause ranking: batching (files cover clustered changes in 2–4
whole-file writes; slopp paid ~10–20 verified round trips), per-write
verification wall (kondo re-runs now memo-cached ✅), schema guessing (arg
aliases + validation messages ✅), redundant test_runs (SKILL guidance ✅).
Correctness/safety all held: rename flawless for every model, checkpoint lint
caught a real ordering mistake, zero wrong-behavior incidents. **Fork partially resolved — W1 (user decision):** whole-namespace batch
writes are allowed for BRAND-NEW namespaces only (never overwrite): `ingest`
is that path, now with the standard verified-write tail (side benefit: it
seeds the trace map, so narrowing works from the first edit).
**W1 follow-up (tool consolidation, 2026-07):** the separate `ingest` MCP tool
was folded into `ns_create` as an optional `:source` mode — `ns_create` was
already `ingest!` of an empty ns, so two creation doors were one primitive with
a "which do I use?" fork. Now ONE door, two mutually-exclusive modes:
`:requires` scaffolds an empty ns to grow with red-first TDD (the default for
new behavior — ingesting finished code skips red→green); `:source` lands a
whole namespace at once (ported/reference/data). The `ingest!` engine fn stays
internal (git-import, seeds); "ingest" is gone from the agent-facing surface. No
alias kept (slopp has no installed base; an alias would re-introduce the fork).
Deferred
verification / whole-ns overwrite remain off the table. The scale side of the
fork (10+-namespace eval, too big to read whole) is the next experiment. Data: benchmarks/results.md; report: projects/eval2/REPORT.md.

## X/N — eval round 3 (scale) findings

X2 ✅ rename hot-loads the renamed DEF first (hash-order destroyed cross-ns
renames) · X3 ✅ image loads follow `store/ns-dependency-order` (topological;
map-key order went hash past 8 nses and silently half-loaded images from
`open!`) and failures throw loudly · X4 ✅ `build!` guarded (absolute paths
only, never a dir enclosing the running process, never clobber an existing
deps.edn — an eval agent built into the host repo) · N1 ✅ `!`-named callees
count as effectful anchors (cross-ns effect propagation).
**Round-3b verdict (the crossover, measured):** at 12-ns scale, slopp beat
files on aggregate tokens (−9%) and tool calls (104 vs 155); sonnet −42%
tokens vs its files baseline; files' costs grew +54% avg with scale while
slopp's stayed flat-to-down. Full data: benchmarks/results.md,
projects/eval3/RUNS.md.

## B — benchmark/baseline findings

B1 ✅ **terse green responses** (from the Go-baseline comparison): MCP write
results compress to `{:ok true :delta id :tests {:ran n :pass n} :affected n}`
when green-and-quiet; full detail on :error / red / NEW warnings / :untested /
explicit `:verbose true`. Measured: output tokens −32–38% across all three
benchmark apps (calculator 906→590, inventory 502→311, wordstats 542→370).


## P — probe-session findings (the dev agent as user, 2026-07)

Observed while building G8–G10 through slopp's own tools (MCP + stdin
probes). Same instrument as the eval rounds — self-reports from real usage.

P1 ✅ **The E2 multi-form guard is pair-blind.** Matching one `case`
branch, one `let` binding, or one map entry trips the "match spans multiple
forms" refusal — pairs ARE two forms, but they're one logical unit. Fired
three separate times this round; workaround each time was matching only the
value form (loses the key/binding as an anchor). Fixed: a TWO-form match
landing on a pair boundary of a paired container (map literal, binding
vector, case/cond clauses) addresses the pair as a unit — the whole pair
span is replaced (splice still applies, so one entry can become two);
misaligned or non-pair multi-form matches keep the hard error, and extract
still refuses pairs (a pair is not an expression).

P2 ✅ **Change-signature is a mined workaround now performed by the
maintainer.** Changing `commit-paths` from 3 to 4 args form-by-form was
(correctly) refused by the lint gate — callers momentarily had invalid
arity; the resolution (ONE atomic edit_group: defn + all callers, plus
`ns_add_require` first for the new alias) had to be discovered, not
suggested. Built, both parts: (a) invalid-arity refusals now append the
resolution hint (defn + callers in ONE edit_group, or change_signature);
(b) `change_signature` op — `source` replaces the defn, every CALL site's
arg list is rebuilt from a `calls` template ($1..$9 = the site's existing
arg sources; the callee stays as written, so aliases survive), executed
through edit-group! so every gate applies in one pass. Higher-order
references aren't rewritten (returned under :manual); nested self-call
sites and template/arity misses are hard errors pointing at edit_group.
The demand rule worked as designed: instrument fired → deferred op built.

P3 ✅ **No one-shot CLI for tool calls.** When the session's MCP server
died, the fallback was hand-rolled JSON-RPC over `--snapshot` stdin with
EDN payloads staged as files to survive shell quoting. Built:
`slopp.boot --call <tool> [args]` (args = JSON, EDN, or `@file`) — sugar
for `--main slopp.mcp/call-main!`; `mcp/call!` opens a durable
turn-enforced session for ONE dispatch. P1/P2 were built through it.

Ideas parked (not demanded yet): per-push CI via pass-through grafting of
main's workflows into the projected slopp tree at push time (source of
truth stays main — revisits the G10 trade-off, user call); more
`render-config` formats (`:properties`, `:edn`) when a consumer exists;
`import!` also wiring `.mcp.json` for zero-config onboarding.


## Q — self-dogfood findings (rocks 1-2 build turn, 2026-07-13)

Friction measured on the maintainer while building through the live
plugin; staged in cost order.

Q1 (✅ 2026-07-13) **:untested defeats the terse result shape.** Any write to an
untested form returns FULL verbose — including the delta's complete
:sources, echoing multi-KB forms the agent just sent (the tools registry
and call-tool came back whole six times in one turn). Fix: :untested is a
flag ON the terse shape; delta :sources never ride write results;
:untested doesn't fire for deftests or pure-data defs.

Q2 (✅ 2026-07-13) **Isolated-suite debugging is a five-minute blind loop.**
test_run {:isolated true} returns counts only — finding WHICH test failed
means rebuilding jar-src and running clojure -M:test by hand. Fix:
isolated runs accept :ns/:only selectors and return failing test names +
messages, like in-image runs.

Q3 (✅ 2026-07-13) **The trace map dies with the session** — every cross-process
write reports {:ran 0, :affected :all}. Persist it in the store: fixes
CLI/probe verification and is the prerequisite for lifetime warm
narrowing (Rock 6's terrain).

Q4 (✅ 2026-07-13, first pass) **slopp.mcp's monoliths fight the form-is-the-unit thesis.**
The tools registry and call-tool dispatch are the hottest-edited spots
and the worst-shaped: decompose to per-tool defs + a handler map.

Q5 (✅ 2026-07-13) **edit_subform fragment errors teach nothing.** "Unexpected
EOF" on a match that ends mid-expression cost three round trips this
turn. Say where the fragment breaks and suggest the enclosing form/pair.

Q6 (✅ 2026-07-13) **Eval instrumentation gaps:** the transcript miner must be a
checked-in script with run-window scoping (an ad-hoc version silently
summed rounds sharing a path); a `slopp doctor` CLI self-check (jar
cache, hooks, turn automation, serving) would have collapsed two
hook-debugging detours.

Q7 (✅ 2026-07-13) **Image-spawning tests are a silent trap for fresh agents.** A
deftest that opens sessions/spawns JVMs (api/open!, repl/start!,
http/start-server!) without ^:isolated would be run IN-IMAGE by per-write
verification — recursion/hang the author can't diagnose. The maintainer
avoids it by knowing the rule; nothing enforces it. Gate it at write
time: detect the pattern, refuse with the fix named ("tag it ^:isolated —
it runs in the external suite").

Q8 (✅ 2026-07-13) **A no-trace verification looks like success.** {:ran 0
:status :green} reads as "verified" when it means "nothing ran" — an
agent in a fresh/CLI session could ship unverified work believing it
green. Until the trace map persists (Q3), such results must say
:coverage :none plus the one-line fix (run test_run once); green must
never be inferable from a zero-test run.

Q9 (✅ 2026-07-13, shared missing-form-error + fragment refusal; audit continues opportunistically) **Every {:error} should meet the cold-load bar.** The
cold-load refusal names its three resolutions inline ("define earlier,
edit_move, or (declare ...)") and cost zero follow-up; the edit_subform
fragment errors taught nothing and cost three round trips. Audit every
error string in api/mcp/sync/edit: each names the next ACTION, in tool
vocabulary. Errors are the only teaching that arrives exactly when the
agent needs it (G12 corollary: richer results beat more instructions).

Q-series resolution notes (same day): Q1/Q8 in `summarize` (strip
:source/:sources everywhere; :untested terse; :coverage :none) + deftest
never :untested; Q2 `isolated-test-run!` :ns/:only + :failing via
`parse-test-failures`; Q3 `persist-trace!`/`load-trace` on store meta;
Q4 tools registry → 6 per-group defs + concat, call-tool tail → 3
handler maps (hot clauses stay in the case); Q5 fragment refusal in
`find-unique-subform`; Q7 `edit/isolation-refusal` on every replace/add
path; Q6 `benchmarks/mine_transcripts.py` + `slopp --doctor`. Suite
263/1351 green.

Q10 (✅ 2026-07-13) **Milestone publish gap** (eval8 P5): five plugin sessions
produced four milestones that never reached the repo's slopp branch —
nothing at commit_point prompts a git_push or syncs the LOCAL slopp
branch, so a files-path teammate gets the seed. The eval agent caught it
by luck of a thorough P5. Fix direction: commit_point on a git-cloned
store offers/performs the local slopp-branch sync (G12: just do the
series), or at minimum the result names the unpublished delta.

Q11 (✅ 2026-07-13) **Bulk rename UX** (eval8 step 3): the one slopp loss (1.49×
controllable vs files) — a global rename is one sed sweep for files but
edit_rename-per-form for us. A pattern/set-accepting rename (or
edit_plan step) closes it.

Q10/Q11 resolution (same day): commit_point at the MCP layer publishes
the projection when git-remote meta is set (green milestones only;
publish trouble rides as :published {:error} without failing the
milestone); rename! results carry :mentions (word-boundary hits of the
old name that the structural rename could not rewrite — docstrings,
strings, comments) + a hint. Rock 3 scoping: edit_group grew :subform
(structural or :text) and :require steps — rename + prose fixes +
threshold change is now ONE call (eval8 step 3's measured overhead).
Full edit_plan (interleaved reads, :when-green barriers) stays deferred
until demand observation shows sequences groups can't express. Suite
266/1359 green.

Rock 4 (✅ 2026-07-13): query_flow + query_impact shipped. Flow is a
boundary-guarded textual keyword scan (kondo keyword analysis deferred
until false hits show up in demand); impact reuses kondo var-usages —
:arity marks a call site, its absence marks a value/higher-order ref
that no template rewrite reaches — plus trace-map coverage. Suite
267/1363 green.

Rock 5 (✅ 2026-07-13): draft_test shipped — deftest drafts from
OBSERVED calls (rt/observe via the query_observe machinery; each capture
becomes an assertion) or a signature skeleton with named TODO holes.
Suggestions only (red-first dogma intact). Known v1 limit: observe's
200-char arg truncation makes big-arg drafts need hand-editing — the
:note says to read each assertion. Suite 268/1368 green.

Stale-dump hazard (lesson, 2026-07-13): the Q4 bulk split sliced tool
descriptors from a dump captured BEFORE a same-session edit and silently
reverted it (test_run's Q2 description). Bulk rewrites from a dump must
re-dump immediately before slicing, or diff the dump against the store
first. Caught by re-reading the descriptor; re-applied.

Q12 (✅ 2026-07-13) **The handoff trust spiral** (eval8 r2, step 5): given a
high-stakes "summarize everything for a teammate" ask, the agent got the
full answer from the history views in 5 calls, then burned ~15 more
re-verifying through side channels (raw sqlite over store.db, a git
worktree re-running the suite, duplicate test_run) — against the skill's
explicit instruction. Q10's git milestones gave it a THIRD source to
cross-check. Direction: results that PROVE alignment instead of asserting
it — query_commits/query_git rows carrying "slopp branch @<sha> == this
milestone's projection" so one call answers the cross-check the agent
will otherwise perform by hand. G12 corollary: richer results close trust
gaps; instructions alone don't.

Q12 resolution (same day): sync/alignment resolves the configured local
remote's slopp-branch head and compares it to the latest milestone's
minted sha (m8) — query_commits returns {:commits rows :alignment
{:aligned bool :note}} whenever a git remote is configured; the note
says explicitly that no worktree/sqlite cross-check is needed (or that
git_push publishes). http remotes stay rows-only (no network in a
query). Suite 269/1371 green.

Ratio push (2026-07-13, from the eval8 n=3 decomposition — orientation
18%, reads 19%, history views 18%, Bash escapes 14%, actual code 15%):
session_brief = one-call orientation (names-only project, counts when
large; milestones; alignment; the loop) targeting ~600 tok/session;
report {since contains} = the handoff composite (milestones + changes
with recorded asks + last verification + alignment) replacing the
history fan-out; OPTIMISTIC EDITS = missed/ambiguous subform matches
return :source-now (the form's current text) so agents compose writes
from the brief and correct from the error instead of pre-reading.
Skill loop rewritten around brief -> optimistic writes -> one report.
Deliberately skipped: tool-description diet (2.1k tok total exposure,
~100 tok/session — teaching value beats the saving); rename-in-group
(multi-ns group surgery, not a measured cost — step 3 already 0.72x).
Suite 272/1383 green. Target: eval8 r4 lifetime ratio ~0.5x.

G13 (standing, from eval8 r4): **composites must snip their prose.** A
composite that carries verbatim descriptions/asks GROWS with history and
gives back every token it saved (session_brief 751->1,773/session;
report ~2.1k/call). Caps: brief = 5 milestones x 110 chars; report asks
= 3 x 140 chars; full text stays one query away. Any future composite
(dossier, plan result, hint) ships with a size budget and a spec
asserting it.

G14 (standing, 2026-07-14): **adoption is not a strategy — defaults
are.** Every measured win came from changing what happens WITHOUT the
agent's cooperation (auto-import, auto-turns, auto-publish, trims, caps,
outline-by-default reads, the hook micro-brief); every disappointment
came from offering a better tool and hoping (optimistic edits: 0 uses;
query_brief: 487 lifetime tokens). New capability ships as the DEFAULT
path or with the default path routing into it — never as an optional
improvement beside the expensive one.

Defaults wave (same day): query_source {ns} returns the OUTLINE unless
full:true (the wire default; api/query-source stays the raw VFS read for
internals); report self-fits under the trim gate via fit-report
(progressive: 1 ask/row, then take-20 with an honest count — the capped
batch double-paid ~2k twice re-fetching 8.1k-char reports); the
UserPromptSubmit hook (now hooks/prompt-hook.py) injects a ~40-token
micro-brief (store present, ns count, last milestone) so the ls/git/
README scouting ritual has nothing left to discover. Suite 274/1392.

Cards wave (2026-07-14): **opacity with a warranty.** form-card = the
interface view of a form (sig, doc line, effect marker, the recorded WHY,
covering-test warranty) at ~10x under source; query_slice = full source
for ONE entry point + cards for its reachable neighborhood (BFS over
query-deps, depth 2 default, capped 8 + :omitted). The warranty is
mechanical, not rhetorical: edits re-run covering tests, violated
assumptions turn red with :implicated — that's why trusting cards is safe
here and hopeful in files. history-stitch hint (2 history calls -> report,
once/session, track-hint! machinery). Plugin ships agents/slopp-reader.md
(read-only comprehension subagent returning CONCLUSIONS — the context-
carry answer for large codebases; unmeasurable at eval8 scale, ship it
for the scaling terrain). Card gap noted: no observed input->output
examples yet (needs persisted observe captures) — the strongest possible
behavior line; revisit when demand fires. Suite 276/1404.

Q13 (✅ 2026-07-14) **The isolated runner REPLs out on inline-test projects**
(eval9): build!'s generated deps.edn only mints the :test alias for
suffix-convention/test-dir tests, so `clojure -M:test` on an inline-test
project starts a REPL and isolated-test-run! reports {:status :error}
with a Clojure banner. Fix: has-tests? should count inline deftests (the
index knows), and the alias's runner should require+run ALL namespaces'
tests; failing that, refuse with the fix named (Q9 bar).

Q14 (✅ 2026-07-14) **Bulk rename at scale is the measured loss** (eval9 step 3:
13.6k tokens / 37 calls / ~7 errors / one restart vs sed's one pass at
5.8k; the run left an empty ns shell). ns_rename + edit_rename + key
subform edits compose per-form; a docs-team-style rename (ns + fns +
keys + prose, 40+ dependent nses) needs ONE intent-level op:
rename_sweep {from "zone" to "region" :kinds [ns fn key prose]} planned
store-wide, executed as one group, one verification. Q11's :mentions and
group subform steps were the small-scale versions; this is the at-scale
completion.

Q13/Q14 resolution (same day): generated :test alias runs `-d test -d src
-r .*` (inline deftests count via a source scan; build! mkdirs test/ so
the runner never REPLs out); rename_sweep {from to} = ns-renames first
(requires rewritten along), then every still-matching form rewritten in
ONE dependency-ordered group (definitions hot-load before callers), one
verification — boundary-guarded segment match, prose and keywords
included by design. Root-cause bonus: rewrite-symbols returned z/root's
:forms wrapper, so EVERY changeset-rewritten form (var-rename callers,
ns-rename decls) silently lost its :name — dependency ordering broke on
the next image rebuild; latent since the changeset machinery landed,
surfaced only when the sweep renamed an alphabetically-early consumer.
Suite 279/1413 green.

Store-integrity pair (2026-07-14, found BY eval9's sweep cells — the
fresh-session-per-step protocol is a persistence fuzzer no single-session
spec replicates):
1. **Resurrection**: try-commit! filtered touched nses to those present
   in the new store, so a renamed-away ns never reached append!'s
   delete — its rows lingered and the NEXT session loaded both old and
   new namespaces. db/persist! had the same skip. Any durable store that
   ever ns_renamed and reopened was affected.
2. **Ghost vars**: a replace that renames (single edit_replace or group
   step) loaded the new var but never ns-unmapped the old one — stale
   vars then failed as noise in later verifications (the sweep's own red
   came from a ghost zone-t, not from the sweep).
Lesson recorded: durable-session specs must include a CLOSE + REOPEN leg
when they mutate namespace identity; single-session green is not
persistence green. Suite 281/1418.

G15 (standing, 2026-07-14): **the knowledge-differential stance.** The
server models what the session has been told (told-tracking: cacheable
views hash-keyed per session; identical re-reads return an :unchanged
stub, so re-fetching is FREE and agents need not hoard context) and what
the task needs (the verbatim ask, kept as :last-intent, is mined
deterministically against form names; the brief arrives with :relevant
interface cards). Composites scale by AGGREGATION, never amputation
(fit-report rolls changes up by namespace; briefs roll up >=5-sibling
namespace FAMILIES) — eval9 proved take-N amputation CAUSES fan-out:
agents hunt for what was dropped. New front door: query_depends {on X}
answers what-depends-on for a namespace / var / :keyword by
classification + delegation (user-requested; ns-level had no tool at
all). Suite 286/1438 green.

Consolidation wave 2 (2026-07-14, user decision): ONE dependency door —
query_depends {on, direction} (dependents: ns -> required-by + refs,
var -> blast radius, :kw -> flow; dependencies: var -> callee tree,
ns -> requires) — and ONE history door — query_history routed by args
({} episodes, {ns name} form life, {ns name at} time-travel, {at}
was-green-at, {contains} ask search). Twelve wire tools retired
(references/deps/flow/impact/outline/symbol/namespaces/form_history/
form_at/status_at/search_history/lineage); every api fn stays; the wire
surface is 58 tools. Evidence: the specialized quartet had ~5 calls
TOTAL across the whole eval campaign — taxonomy-choice was the barrier,
not capability; the outline gate had already shown removal converts
where offering never does. Deferred deliberately: modal write clusters
(files/branches/deps/git) — distinct verbs are safety-relevant and the
traffic is negligible. Suite 286/1441 green.

G16 (standing, 2026-07-14): **smells are data.** Deterministic bad-usage
detection lives in slopp.mcp/smell-registry — one [key fires?-pred msg]
entry per smell over bump-smell-counts; adding a smell is one entry, no
plumbing. Fire policy is anti-spam BY CONSTRUCTION: once per session +
once per 30 minutes per store (db-meta cooldown), suggestions never
refusals, one line naming the better tool. Current catalog: test_run
streaks, scattered singles, history stitching, whole-ns dump streaks
(-> query_slice), rename streaks (-> rename_sweep). Standing practice:
smells observed in dogfooding (mine or eval transcripts) get an entry
the same day. Two bugs fixed en route: hints now ride STRING results
(source dumps dropped them), and some-over-registry replaced a cond
whose first fired smell shadowed all later ones forever.

Bash-smell hook + card examples (2026-07-14): the smell system reaches
the channel the server can't see — a PostToolUse(Bash) hook injects
one-line redirections via hookSpecificOutput.additionalContext (the ONLY
PostToolUse output that reaches the agent — verified empirically; plain
stdout does not). Smells: raw store.db reads, git log/diff/show/blame in
store dirs, grep/cat of .clj files, clojure -e shell evals; 30-min
per-smell per-store cooldown (.slopp/bash-hint-cooldowns.json), silent
on any failure, active only where .slopp/store.db exists. And interface
cards now carry :examples — query_observe captures persist to store meta
(remember-observation!, called at the MCP layer to keep the api fn's
read-only contract clean) and cards surface up to two real
input->output pairs. Suite 289/1452 green.

Push-default regression (2026-07-14, bit the USER): git_push {url} saved
the url unconditionally, so a one-off push to "." silently repointed the
store's default remote away from slopp3 and broke the normal push flow.
Fixed: first-save only — an existing default is never rewritten by a
one-off push; results carry :default-remote so the standing default is
always visible. Also verified and worth knowing: local and remote slopp
branches are the SAME minted lineage (deterministic projection commits
via git_map), so a local push is never a divergent copy; the local
branch now tracks origin/slopp. Suite 290/1457.

D-local-mirror (user decision, 2026-07-14): **every milestone mirrors
into local git automatically** as refs/heads/slopp/<store-branch>
(slopp/main, slopp/my-branch) — local git durably carries the slopp
history with zero ceremony; REMOTE publishing stays explicit (git_push,
which never rewrites the saved default). sync/publish-local! never
touches git-remote meta; alignment (Q12) now proves against the local
mirror, so it works with no remote configured at all. Naming note: a
flat local branch named `slopp` blocks the slopp/* namespace — the dev
repo's old flat branch was deleted (identical to origin/slopp) and the
local listener remote renamed slopp -> slopp-store to clear ref
ambiguity. Remote publication branches (e.g. slopp3's `slopp`) are
unchanged. Suite 290/1459.

Mirror sync verbs (user design, 2026-07-14): git_push/git_pull have ONE
meaning each — push = send local slopp/<branch> mirrors to the remote
(sync/mirror-push!: ff-only, collision with a legacy FLAT `slopp` branch
refused with the migration taught, {:migrate true} performs it — same
minted lineage); pull = fetch remote mirrors into local slopp/* (ff-only)
AND absorb remote store history (existing 3-way pull!). Direction lives
in the verb, not a mode flag (the user's call: familiar git vocabulary
beats a unified modal tool). No-git dirs never fail: milestones stay
store-durable with no :published leg, and the verbs teach `git init`.
Ecosystem: slopp-branch? marker + clone!'s default resolution accept
slopp/main alongside legacy flat `slopp`; slopp3's branch is migrated to
slopp/main (the old flat sync/push! remains api/CLI-level for legacy
flat remotes). Suite 291/1471.

No-compat rule (user directive, 2026-07-14): **there is no slopp but
this slopp** — never write backwards-compatibility code; when a design
changes, migrate everything (code, specs, seeds, remotes) in the same
wave and delete the old path. Applied immediately: flat `slopp` branch
support removed everywhere (marker/clone/pull/push all speak
slopp/<branch>; clone's legacy-main fallback gone; git-branch config
key retired from config!/push!/clone!), mirror-push!'s flat-collision
migrate machinery deleted (flat is extinct: slopp3 + both eval seeds
migrated to slopp/main, READMEs and accept scripts updated), and the
stale-schema :where string-coercion removed (the restart protocol owns
schema staleness). Kept deliberately, NOT compat: agent arg-forgiveness
aliases (:old/:from etc. — they serve live agents guessing, not old
versions) and push! itself (fileless stores publish projections — a
capability, not a legacy path; git_push routes checkouts to mirrors and
fileless stores to projection publishing). Suite 291/1470.

Module system (user design, 2026-07-14): **enforced architectural
boundaries as a module system.** Module = first two ns segments
(`-test` folds into the subject's module); RECURSIVE VISIBILITY — deeper
namespaces are package-private to their parent prefix, `^:export` on a
defn's name hoists that var into the module surface (definition-site,
no var copying); cross-module calls require a DECLARED edge; a declared
edge that CLOSES a cycle is refused (the check is LOCAL — reachability
to→from — because -test folding makes some adopted cycles legitimate,
e.g. slopp.api↔slopp.db via each other's tests; a pre-existing cycle
never blocks an unrelated declaration); public-surface defns without docstrings warn
(per written form, transition-only — never a ns-wide nag). The manifest
ALWAYS exists (user: "we shouldn't have to explicitly init — start
modifying it from empty"): fresh stores are born enforcing with zero
edges; pre-module dbs adopt at open! (manifest derived from the actual
kondo-resolved graph = zero violations by construction); clone! ingests
gate-off then adopts what landed. Tracked CRDT-WAY with SEMANTIC calls
(user directive): the edge is the unit — one `:module-edge` delta per
declare/retract carrying its why; merges fold edges (adds union, never
a conflict; a cycle the union closes is surfaced as a note); writes go
through `module_dep` only (config_file "modules" is refused and
teaches); reads through `query_depends {modules true}`; the fold
projects as a `modules` file into git commits for transparency.
ns-rename/rename_sweep re-key manifest entries when a module's last ns
renames away. Suite 296/1520.

Inferred episodes — REPL-native flow (user design, 2026-07-15): agents
must NOT plan groups or worry about wire limits; they work like a REPL
developer and the SYSTEM infers the unit of work. STANDARD TERMS (use
everywhere): WRITE (delta) = one gated, hot-loaded, verified form-level
change; CHANGESET = internal atomic multi-form op (rename,
change_signature, normalize) — implementation detail, never an agent
decision; EPISODE = one agent's writes between done-points — inferred,
per-agent, the history unit; DONE-POINT = the boundary (`done {label}`,
or the turn-end hook): normalize + declare hygiene + lint + AFFECTED
TESTS over everything the episode touched + findings recorded on the
boundary delta; TURN = the user-ask bracket; MILESTONE = commit_point,
green-gated, spans turns. Decisions: verb renamed checkpoint→done
(journal op migrated one-off, 85 rows, user-approved); tests never
implicitly close an episode (explicit done + turn-end only; spot-check
test_runs stay in-progress, and a redundant pre-done test_run earns a
hint); edit_group + staging REMOVED from the wire (api-level
edit-group!/changesets stay internal); per-write verification stays
automatic. The invalid-arity lint gate is scoped to the WRITTEN form:
own-form errors refuse (with the change_signature hint), stale-caller
errors ride :carried-errors until done re-checks them hard. done always
verifies (not only when normalize rewrote); findings resurface in
session_brief :last-done + the prompt-hook heads-up. Concurrency note:
sub-agent isolation was never the group's job — form-grain CRDT
rebasing is; episodes are per-agent derived.

Self-hosting lesson (2026-07-15, cost one rescue): changing the
SIGNATURE of a write-pipeline function (edit/lint-refusals 3→4 args)
via a single write deadlocks the pipeline — the hot-loaded new fn
breaks the still-committed old callers, and no write can land to fix
them (there is no fallback pipeline; the jar is only a boot kernel).
Rescue: user-approved hand-minted append!-equivalent delta installing a
dual-arity bridge, then normal writes. RULE: pipeline-critical
signature changes go through the internal atomic changeset
(edit-group!/change_signature), never incremental writes — exactly the
carve-out the inferred-episode design keeps changesets for.

Module-graph honesty (2026-07-16): the module manifest folds `-test`
deps into the subject module (so TDD needs no ceremony), but that
pollutes the architecture graph — test fixtures calling `slopp.api`
manufactured a false 7-module cycle in slopp's adopted store. Decision
(user): the graph VIEWS (`query_depends {modules true}` :layers/:cycles)
compute over PRODUCTION edges only (non-`-test` namespaces;
`api/production-manifest`); the DECLARED `:manifest` and the write GATE
are unchanged (fixtures still declare their edges, enforcement intact).
slopp's own graph went from one condensed 7-module cycle to a clean
8-layer DAG with slopp.api alone at layer 4. Note: the false cycle only
arises via ADOPTION (module_dep's own cycle guard prevents declaring it
fresh).

The first deep-module split (2026-07-16): slopp.api's 8 pure history/status
helpers moved to package-private `slopp.api.history` via `edit_extract_ns` —
the first depth-3 namespace in slopp itself, proving the deep-module
machinery on real code. Dogfooding surfaced and fixed, red-first:
(1) hot-load-all!'s heal path boots from the COMMITTED store, so a
candidate-created namespace vanished mid-heal (FileNotFound) — the heal now
replays the candidate's touched nses first, and the pre-heal error rides out
as :first-err; (2) forms moved across a namespace boundary are PUBLICIZED
(refactor/publicize strips defn-/^:private) — module-grain visibility is the
boundary now, not var privacy; (3) the module gate was blind to
fully-qualified un-required calls (kondo emits no var-usage row for them) —
edit/qualified-usage-rows synthesizes those rows, quote-pruned; (4) -test
namespaces share the subject's prefix for deep visibility (fold-test-ns), so
package-private helpers stay unit-testable; (5) JGit transports now carry a
30s socket timeout after a half-dead fetch froze the single-threaded server
for 40+ minutes mid-publish (the milestone was already durable — journal
first); (6) summary-less test shards (JVM death under fork pressure) retry
once serially (:shard-retries). Remaining slopp.api clusters (testrun, deps,
branch, build; verify/session LAST, via changesets) are a follow-on decision.

The session-engine split (2026-07-17): slopp.api's pipeline substrate —
image lifecycle + journal commit + verification, 27 forms — moved into
package-private `slopp.api.session` via move-forms, live, with the server
executing the very pipeline being relocated (safe because a MOVE rewrites
all addresses atomically and the server hot-reloads only after the
consistent commit; contrast the D-series signature-change deadlock).
The two-way refusals DISCOVERED the layering: branch/deps plumbing
couldn't move before the substrate they call. Deep packages now:
history, testrun, session, deps, branch; the public verbs stay on
slopp.api (the surface). Engine specs live with the engine
(slopp.api.session-test); reap-idle-images! stayed a public verb so
branch specs stay honestly placed. move-forms hardening the campaign
forced: de-qualify refs into the target, moved sets carry their own
(declare ...), publicize/export under form-level meta wrappers, per-move
export levels compose across steps (the hook moved alone with
^{:export "slopp.concurrency"}).

slopp.api decomposition complete at the internals grain (2026-07-16):
seven deep packages — history, testrun, session (the engine), deps,
branch, modules, orient — hold the implementation; slopp.api keeps only
the public verbs and query composites (187KB from 231KB). Placement
rules that emerged: a PRODUCTION cross-module caller makes a helper
public API (adopt-modules! stayed for sync/clone!); specs live with the
machinery they exercise (session/modules/orient -test namespaces);
public verbs stay on the surface even when thin. The debt view caught a
real gate-vs-reality gap: a move's export pre-check trusted intent while
export-mark silently skipped a meta-wrapped name (^:dynamic) — fixed;
the gate reads declared plans, the debt view reads reality, keep both.

Tier-blind verification (2026-07-16, user decision): the in-image vs
^:isolated split is an IMPLEMENTATION DETAIL that must not leak into the
agent's contract. `done` now routes impacted ^:isolated tests to the
external tier automatically (require-closure slice keyed to the episode
boundary, capped at 4 nses; a larger slice defers LOUDLY via findings
:isolated-pending — resurfaced by the brief); `commit_point` green-gates
on the FULL isolated suite it runs itself (:force skips to honest red;
stores with no isolated tests skip the tier). The wave-end ritual is now
just commit_point. Explicitly rejected: auto remote push (the user asks
for mirror pushes), per-wave jar rebuilds (the jar is only the boot
kernel — rebuild on kernel/deps change only). Roadmap: warm isolated
runner + incremental build! make the tier cheap enough to remove the cap.

query_store — the store-value oracle (2026-07-16, user-approved surface):
read-only eval of one `(fn [store] ...)` over the immutable store value,
in the server process. Rationale: the image answers questions OF the
code; there was no oracle for questions ABOUT the codebase-as-data, so
one-off analysis kept becoming canned tools (review_scan) or tempting
raw db reads. Boundaries: pure-eval gate (no effects/defs/interop/IO),
fn-of-store shape only, worker + timeout, pr-str-capped output. The
stance "raw REPL eval may observe but never redefines" extends to the
store value: observation of an immutable pointer, never mutation.
Repeatedly-asked query_store questions are evidence for the next canned
tool.

The unused-public gate (2026-07-16, user decision): dead public surface
fails verification instead of riding as an ignorable advisory. done →
ERROR-grade lint + findings for touched namespaces; commit_point →
GLOBAL whole-store sweep (standing dead surface refuses the milestone
regardless of whose episode left it — episode-scoping provably leaked).
The escape is `^:unused-ok` on the name, and it is self-policing: a
stale marker (var now called) fails symmetrically, so the dial never
rots. Known blind spot the dial exists for: string-eval'd and
runtime-resolved entries (rt/observe, rt/traced-run, mcp/call-main!)
are kondo-invisible by nature. Kondo's unused-private-var already
covers privates per-namespace.

Designated reference carriers (2026-07-16, user decision — thesis-level):
references must not hide in strings or naked quoted symbols; they live in
BLESSED CARRIER positions the tooling reads as real edges, or in
DECLARATIONS. The carrier set: `#'var` literals for in-process references
in data (already hard edges — the preferred form); `store/late-ref 'ns/nm`
for load-cycle late binding (the ONLY sanctioned runtime resolution;
replaced the naked requiring-resolve in git/ensure-projected!);
`api/query-call` / wire `query_call {sym args}` for the oracle's common
invoke case (query_eval stays the escape hatch for arbitrary expressions);
`^:entry-point` on the NAME declares outside-world invocation (CLI, wire,
eval-injected — rt/observe, rt/traced-run, mcp/call-main! migrated from
^:unused-ok; no stale symmetry — the outside world is statically
unverifiable). KEYWORDS REJECTED as the replacement: a keyword names a
table slot, not a var — statically worse than a string. unused-report and
review_scan read carriers and declarations; a naked quoted symbol stays
data. Phase 2 (parked with ideas/): the dialect LINT that detects
var-shaped strings / naked requiring-resolve outside carriers and teaches
the sanctioned form; per-library carrier adapters (kondo-hook style) for
framework config; the unified reference graph builds over these so
references CANNOT hide rather than being tolerated. Scope note: carriers
relieve markers only for SAME-STORE drivers — slopp's own nested-store
test fixtures keep ^:unused-ok (cross-store references are invisible by
construction, and ordinary apps' tests call statically anyway).

Resolver denylist (2026-07-16, completes the carrier decision's teeth):
requiring-resolve, resolve, ns-resolve, find-var, and intern join the D3
denylist. Blocking var-shaped STRINGS is the wrong enforcement point —
docstrings mention vars and tests hold quoted symbols as data, both
legitimately inert. The gate blocks where a mention could BECOME a var;
refusals teach the carriers (store/late-ref, #'var literals) and the
^:unsafe owned-obligation escape. Sanctioned resolver homes: late-ref
itself and slopp.boot/-main (the OS boundary). Consequence: in gated
store code, a string can no longer become a reference — strings are
inert by construction, which is stronger than any string lint.

Metadata-mutator denylist (2026-07-18, dialect-prunes-human-conveniences
wave — the template's next cut after `declare`): alter-meta! and reset-meta!
join the D3 denylist. slopp reads every load-bearing marker (^:export,
^:unsafe, ^:reads, ^:auto-declare, :malli/schema) straight off the STORED
node, so a form's metadata IS its contract and must be SOURCE-only truth;
mutating a reference's metadata at runtime is invisible to that read (store
says one contract, the live var carries another). Fits the prune template:
helpful for humans, hard for static analysis, cheap for an agent to give up
(write the metadata inline for the same keystrokes). Unlike `declare` this
lives in dialect-check, so it binds BOTH the edit path and the dialect-scan
import path; the refusal teaches "write the metadata ON the form" and names
the ^:unsafe escape (host/slopp.rt legitimately mutate metadata as
instrumentation). with-meta/vary-meta return NEW values and are untouched —
the cut is exactly the two in-place mutators. Zero usage in slopp's own
store when added, so no adoption break. Orthogonal to the sandbox
(pure-eval-refusal), which is a separate property and was not touched.

THE reference graph (2026-07-16, user decision — single source of truth):
slopp.edit.refs is the ONE place "who references what" lives. Producers
normalize in at the source (kondo statics + un-required qualified calls,
carrier positions, marker declarations as edges from :external); consumers
query refs/refs-to and never fuse sources privately — unused-report,
module-usage-rows (debt/drift), and review_scan ported this wave, which
also FIXED review_scan's scoped-scan caller counts (whole-store graph →
the :unused flag now works under :ns scoping too). Records anchor to
form-ids (semantic, stable), never line/column; rewriters re-derive
positions per-form at rewrite time. The graph is DERIVED (content-memoized
via the kondo cache), never stored — refs are an index of source; the
journal owes them no consistency, so merge/replay/branching need no new
machinery. Remaining consumers to port (recorded): move-plan's usage
assembly, rename's mention sweep, query-depends/query-impact blast, the
module gate row builders; plus the :observed producer (trace map — session
grain, needs an optional arg) and keyword/class targets. Convention going
forward: a tool reading kondo var-usages directly for a REFERENCE question
is a bug — add a producer or consume the graph.

Wire uniformity rule (2026-07-16, follow-up to the codec): the wire speaks
NAMES, always — one output dialect (the grouped-qsym form); short handles
are never an alternative output format. Rationale: reading is ~99% of
traffic and qsyms are self-describing (no resolution round-trip); opaque
ids are a hallucination hazard (a mistyped f4448 can silently resolve to a
REAL other reference, while a mistyped qsym fails loudly — names fail
safe); and handles are form-PAIR grain, not edge grain (static + carrier
between the same forms share one handle). Handles remain an ACCEPTED INPUT
convenience for future ref-consuming tools — which must equally accept
qsym pairs, so agents never need to convert — and are EMITTED only
alongside an actionable follow-up operation, never as noise columns.

Epic close — agent-native addressing end to end (2026-07-16): every
reference consumer ports to THE graph (gates via the ns-refs slice,
move-plan direction/caller analysis, query_impact blast radius — which
now shows agents carrier refs and outside-world declarations; the trace
map joined as observed-refs, giving :via :observed a real consumer in
coverage). qualified-usage-rows deleted — its job became a producer.
Rewriters (rename-changeset) and planner node-walks (imports-for) are
stance-compliant NON-consumers: positions and class names are rewrite-
time derivations inside single forms, not reference questions; external-
lib require selection stays kondo (outside the store graph's domain).
Diagnostics speak anchors: compile errors translate at the hot-load
chokepoint (edit/anchor-error → {:form :at}), lint rows carry :at
snippets, row/col never crosses the wire. kondo-cache bound 64→256 (a
101-ns store reset a 64-entry cache mid-sweep). The graph surfaced and
retired real debt on the way: store-test's undeclared render use became
an element-order assertion (better test, no cross-module reach).

Near-term scope: simple, dependency-lite projects (2026-07-16, user
decision). The target for slopp-built applications right now is SIMPLE and
DEPENDENCY-LIGHT. Deferred as explicit FUTURE projects, not near-term work:
HTTP-server support and how that integrates; framework dependencies
(routers, component/DI systems) and the carrier/kondo-hook ADAPTERS they'd
need; the dogfood PROBE APP (was proposed to gather arbitrary-app
evidence — hold until dependency-heavy support exists to probe). Consequence
for the roadmap: prioritize agent-ergonomics and gate-FIT for simple
projects (diagnostics-anchors, auto-avoid-declare, markers-carry-why,
functional-core-gate, fast-cold-truth) over anything justified mainly by
framework/dependency/HTTP complexity. The reference-carrier phase-2
"per-library carrier adapters for framework config" is part of the deferred
set. Don't build framework/HTTP support speculatively — revisit when the
project explicitly turns to it.

## W — the plugin colonised every project it was enabled in (2026-07-21, user report)

Reported by the user as "other projects are getting `.slopp` dirs created and
stuff maybe changing in there." The plugin was enabled in the user's GLOBAL
`enabledPlugins`, so its MCP server and hooks loaded in every project opened.
Measured across their checkouts before the fix:

| dir | state |
|---|---|
| `metabase/metabase` | 0 elements, 0 namespaces, **128** `"session pause"` checkpoint deltas |
| `metabase/metabase-4` | 0 elements, **31** checkpoints |
| `metabase/metabase-3` | 0 elements, **10** checkpoints |
| `bundlebase` | pristine auto-created store (4096-byte db), never written |
| `metabase-2`, `oxplow` | bare `.slopp/` holding only `pending-intent` |

Six repos, ~9 months of accumulation, and **not one byte of code in any of
them** — every delta was a session-pause checkpoint written by the `Stop`
hook into a store that existed only because serving had created it.

Three findings worth carrying:

1. **A footprint bug hides from its own tests.** Every store-level test passed
   throughout: they all ran on temp dirs where creating `.slopp/` was
   invisible and expected. The invariant nothing asserted was the ABSENCE of a
   write. The regression test now asserts exactly that (`db-test/
   a-storeless-dir-materializes-on-the-first-write`), and it went red on
   `(not (.exists sdir))` — the one claim the old suite had no way to make.
2. **A probe that creates what it probes.** `sync/empty-store?` opens the db
   to ask whether a dir's store is empty — and creating on open meant the
   question wrote the answer. It survived only because its caller
   short-circuits on `.exists` first. Asking should never be a write; that is
   now enforced by `{:create? false}`.
3. **The docs were right and the code was wrong.** `slopp-setup` had promised
   "creates an empty store on the first write" since it was written. Nobody
   checked the claim against the implementation, and the gap ran for months.
   Shipped prose is a testable assertion about behaviour — the P1 self-
   description gates check that prose names real tools, but not yet that it
   describes real behaviour.

## 2026-07-22 — #8 re-diagnosed: the host reloads its own main-line writes; branches were the blindness

Probed on the live dev server (which predated the day's changes): a
freshly-edited serving-machinery path (spot-run!) answered on the running
host WITHOUT a restart — the host hot-reloads its own session's commits.
SQLite `PRAGMA data_version` is per-connection and the watcher's connection
sees the session connection's commits, same process or not. The three #8
incidents all happened on the web BRANCH: branch writes land in
`.slopp/branches/<name>/`'s mini-journal, which `watch-live!` (watching
only `<dir>/.slopp/store.db`) never opens. Corrected operational rule:
**host code = the main journal, live (self-writes included); a branch
line's writes reload the image only — verify branch serving behavior in
the image or a fresh JVM.** The `:host` section of session_brief now
states mode, staleness, failed reloads, and the branch teaching, so the
next currency question is one read instead of a debugging arc.

## 2026-07-22 — adversarial code review of the friction waves + D-web, all fixed

Four independent read-only reviewers (store core, verification loop, web,
host/surface), each VERIFYING findings in the live image, plus a triage
pass. Every finding below landed with a red test modelling the defect.

**One HIGH, mine this session:** the fold-field registry refactor routed
state-carrying merge deltas (config/deps/tier/file) through `replay-delta`,
which re-conj'd them with THEIR verbatim id. Both lines of a fork allocate
from the same counter, so a durable branch merge that touched
capabilities/deps/tiers on both sides produced a duplicate delta id →
`db/append!`'s UNIQUE constraint failed the merge PERMANENTLY with a
misleading "store changed during merge". Ephemeral merge tests never reach
`append!`, so nothing caught it. Fixed by re-minting each crossed state
delta (fresh id + `:merged-from`), guarded by a pure journal-integrity test
AND a durable end-to-end branch merge.

**Verification-loop false-greens (mine):** `inert-ns-require-change?`
classified two behaviour-changing edits as inert (a method-free lib whose
require-CLOSURE loads defmethods; an ns-form metadata edit `edn/read-string`
silently dropped) → `done` skipped external tests. Now walks the closure,
reads metadata-preserving, and diffs against the last-done baseline.

**Data-loss (mine):** `ns_delete` identified the ns decl by NAME at three
sites, so a `(def x)` in ns `x` was mistaken for it → the namespace dropped
with a form still in it. Structural `body-forms` helper now used everywhere.

**Other regressions (mine):** `edit_subform :after` + `:match` duplicated
the neighbor (now refuses the combination); a late `^:breaking-ok` stopped
discharging across an intervening done (now discharges vs any wider unmarked
baseline); host-brief note precedence + delta counting.

**D-web security (pre-existing, all fixed):** `[:all]` empty-policy
auth-bypass; runtime effect-declaration not enforced; error-body ex-data
leak; unsalted git-projected password hashes + non-constant-time compares;
mandatory-OIDC-audience gap; static reader traversal (latent, contained
today by the single-segment router); `http.max-body-bytes` never enforced
(DoS); proxy-header casing. See `.context/decisions.md` D-web hardening.

**Non-finding recorded:** the registry data-defs read `:covered 0` in
review_scan (callable-data-invisible trace limit) — coverage is structural
via the generated merge harness, not absent. The module-edge nil-marker
clobber is unreachable (open! adopts before any write).

**Frozen-form trap (D-web-contracts part 2):** `edit.modules/
missing-doc-warning` classified heads with a `'#{defn defmacro}` DATA literal —
but D4 bans `defmacro` *wherever it appears*, quoted data included
(`dialect-check` flags `'defmacro`). So the form had landed once (pre-strictness
or via a skip) and was then FROZEN: every later edit re-ran the whole-form
dialect scan and refused on its own body. The tell is a dialect refusal naming a
symbol you didn't touch. Fix that also unfreezes: compare the head by
NAME-STRING (`#{"defn" "defmacro"}`) so the form carries no banned literal. A
form that legitimately needs a banned symbol as data wants `^:unsafe`; a form
that only needs to MATCH on one wants the string compare.
