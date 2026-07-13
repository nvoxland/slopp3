# Benchmark history

Wall + token cost of building each sample app through the MCP surface
(`clojure -M -m slopp.benchmark`; see `.context/dogfooding.md`).
Rows are comparable only within the same script version (v).

| date | sha | app | v | steps | wall ms | tok in | tok out |
|---|---|---|---|---|---|---|---|
| 2026-07-02 | 9a0caa3 | calculator | 1 | 12 | 2568 | 746 | 1051 |
| 2026-07-02 | 9a0caa3 | inventory | 1 | 7 | 67 | 345 | 502 |
| 2026-07-02 | 9a0caa3 | wordstats | 1 | 8 | 1254 | 427 | 542 |
| 2026-07-02 | 66c30c0 | calculator | 2 | 11 | 1316 | 725 | 906 |
| 2026-07-02 | 66c30c0 | inventory | 1 | 7 | 63 | 345 | 502 |
| 2026-07-02 | 66c30c0 | wordstats | 1 | 8 | 1277 | 427 | 542 |
| 2026-07-02 | 6f42ec6 | calculator | 2 | 11 | 1360 | 725 | 911 |
| 2026-07-02 | 6f42ec6 | inventory | 1 | 7 | 66 | 345 | 508 |
| 2026-07-02 | 6f42ec6 | wordstats | 1 | 8 | 1363 | 427 | 547 |

## Conventional-workflow baselines (Go; one-time rows, 2026-07-02 @ 7cb99a5)

The same three apps built by FRESH sub-agents (no slopp context) with the
conventional files + `go test` workflow, one run per model. Payload metrics
via `measure_go_baseline.sh` (artifact-derived); true tokens/duration from the
harness. All nine finished green with renames verified. Caveats in
`.context/dogfooding.md` (agent wall time includes thinking; slopp script rows
are deterministic replays — compare workflow *shape*, not raw wall).

| model | app | wall s | payload tok in | payload tok out | test runs | true agent tokens | tool calls |
|---|---|---|---|---|---|---|---|
| haiku  | calculator | 84  | 770  | 73  | 5 | 19,574 | 14 |
| haiku  | inventory  | 60  | 515  | 52  | 3 | 19,543 | 15 |
| haiku  | wordstats  | 100 | 2369 | 210 | 6 | 25,654 | 24 |
| sonnet | calculator | 114 | 1112 | 200 | 7 | 27,766 | 23 |
| sonnet | inventory  | 76  | 685  | 53  | 3 | 24,746 | 16 |
| sonnet | wordstats  | 72  | 1013 | 75  | 3 | 24,787 | 15 |
| opus   | calculator | 73  | 865  | 8   | 3 | 18,687 | 11 |
| opus   | inventory  | 80  | 587  | 17  | 2 | 18,483 | 16 |
| opus   | wordstats  | 72  | 785  | 75  | 3 | 18,625 | 11 |

Read against the slopp script rows (calculator 725/906, inventory 345/502,
wordstats 427/542 payload tokens in/out):
- **Input payloads: slopp wins, and the gap tracks rewriting.** Form-granular
  writes beat whole-file writes — modestly on clean runs (inventory 345 vs
  515–685), dramatically when an agent thrashes (haiku wordstats 2369 vs 427,
  5.5x): every conventional fix pays for the whole file again.
- **Output payloads: slopp currently LOSES** (502–906 vs 8–210): every slopp
  write returns a full verification summary + delta + warnings, while a green
  `go test` says almost nothing. Insight B1: make green responses terse
  (detail only on red/warnings/explicit ask).
- **Models:** opus was the most efficient (fewest tools, ~18.6k tokens);
  sonnet the most thorough (red-first TDD, most verbose); haiku cheapest
  per token but flailed hardest (wordstats: 24 tool calls).
| 2026-07-02 | 520b41c | calculator | 2 | 11 | 1305 | 725 | 590 |
| 2026-07-02 | 520b41c | inventory | 1 | 7 | 84 | 345 | 311 |
| 2026-07-02 | 520b41c | wordstats | 1 | 8 | 1236 | 427 | 370 |

## Symmetric eval, wave 1: fresh agents driving SLOPP (calculator, 2026-07-02 @ 42d677e)

Same protocol as the Go baselines (fresh sub-agents, only SKILL.md + the spec),
but building through slopp over the HTTP transport. Payload = server-side
/metrics; true tokens/duration from the harness.

| model | workflow | true tokens | duration | tool calls | payload in | payload out | outcome |
|---|---|---|---|---|---|---|---|
| opus   | Go files | 18,687 | 85s  | 11 | 865  | 8    | clean |
| opus   | slopp    | 23,455 | 131s | 13 | 546  | 143  | clean, linear (11 calls, 0 red) |
| sonnet | Go files | 27,766 | 127s | 23 | 1112 | 200  | clean TDD |
| sonnet | slopp    | 50,974 | 423s | 43 | 2090 | 1958 | green, but fought S1+S2 |
| haiku  | Go files | 19,574 | 97s  | 14 | 770  | 73   | clean |
| haiku  | slopp    | 41,899 | 460s | 93 | 3499 | 2216 | green after heavy flailing (38 query_evals, 3 restarts) |

Honest read: on a tiny greenfield app, slopp costs MORE true tokens than files
today (opus +25%, sonnet +84%, haiku +114%). Opus's clean run shows the floor:
payload-in -37% vs its Go run, perfectly linear workflow, terse greens held.
The weaker-model blowups were dominated by two product defects the eval
surfaced (exactly what it was for):
- S1: hot-load of a non-compiling form is UNCHECKED -- commits to the store,
  image silently keeps/lacks the var, agent sees {:ok :ran 0} instead of red.
- S2: add_form only appends -> top-down authoring creates forward refs that
  break fresh loads; no reorder op (agents did delete+re-add dances).
Also: agents invented plausible tools (help, ns_remove_require) -- add the
symmetric ops or suggest nearest-tool in the unknown-tool error.
Waves 2-3 (inventory, wordstats) deferred until S1/S2 are fixed -- rerunning
known defects wastes runs.

## Symmetric eval, waves 2-3: post-S1/S2 fixes (inventory + wordstats, @ 903214c)

Same protocol; the compile-gate (S1), edit_move, ns_remove_require, and
tool-listing errors were in place. All six runs ended green with the rename
step verified.

| model | app | workflow | true tokens | duration | tool calls | payload in/out |
|---|---|---|---|---|---|---|
| haiku  | inventory | Go    | 19,543 | 82s  | 15 | 515/52 |
| haiku  | inventory | slopp | **18,878** | **56s** | **10** | 316/147 |
| sonnet | inventory | Go    | 24,746 | 89s  | 16 | 685/53 |
| sonnet | inventory | slopp | 30,137 | 145s | 20 | 864/948 |
| opus   | inventory | Go    | 18,483 | 91s  | 16 | 587/17 |
| opus   | inventory | slopp | 21,224 | 97s  | 16 | 357/155 |
| haiku  | wordstats | Go    | 25,654 | 132s | 24 | 2369/210 |
| haiku  | wordstats | slopp | 41,908 | 381s | 62 | 1955/1473 |
| sonnet | wordstats | Go    | 24,787 | 82s  | 15 | 1013/75 |
| sonnet | wordstats | slopp | 34,722 | 179s | 26 | 1058/1169 |
| opus   | wordstats | Go    | 18,625 | 85s  | 11 | 785/75 |
| opus   | wordstats | slopp | 22,266 | 128s | 17 | 440/278 |

Read:
- The S1 compile-gate transformed the weak-model experience: haiku went from
  +114% (wave 1) to BEATING its Go baseline outright on inventory (-3% tokens,
  -32% wall, fewest calls of any run). Overall slopp overhead fell from
  +25..114% (wave 1) to -3..+63% (waves 2-3).
- Remaining measured frictions, all fixable: (1) edit_rename arg-key guessing
  cost every sonnet/opus run retries ("no conversion to symbol" raw error);
  (2) red-first TDD fights the compile-gate -- agents stub-danced; the
  idiomatic answer ((declare f) -> test -> honest red) needs to be taught in
  SKILL.md; (3) fixed overhead: SKILL.md read + curl envelope ~2-3k tokens/run.
- haiku/wordstats remains the outlier (62 calls): weak-model thrash on the
  sort-by-descending logic, not a product defect (its Go run also took 24).
- Payload inputs: slopp lower in 5 of 6 runs (form-writes vs whole-file
  rewrites). Payload outputs remain higher (structured verification vs silent
  green) -- by design, and B1 keeps quiet greens small.

## Symmetric eval, wave 4: calculator RERUN post-fixes (@ 31a002b)

Same app as wave 1, all fixes in (compile-gate, rename aliases, TDD guidance).
All green.

| model | wave 1 (pre-fix) | wave 4 (post-fix) | Go baseline |
|---|---|---|---|
| haiku  | 41.9k tok / 460s / 93 calls | 31.7k / 241s / 29 | 19.6k / 97s / 14 |
| sonnet | 51.0k / 423s / 43 | 34.6k / 175s / 24 | 27.8k / 127s / 23 |
| opus   | 23.5k / 131s / 13 | 32.3k / 264s / 25 | 18.7k / 85s / 11 |

- The fixes bought haiku -24% tokens / -48% wall (calls 93->29) and sonnet
  -32% / -59%. Gaps to Go: haiku +114%->+62%, sonnet +84%->+25%.
- Opus regressed vs its own exceptionally clean wave-1 run (schema probing
  this time) -- it never hit S1/S2, so the fixes had nothing to fix, and
  single-run variance (est. +/-40%) dominates clean runs. n=1 rows are
  directional, not precise; treat trends across models/apps, not cells.
- Standing overhead vs files at this project size: SKILL.md read + curl
  envelope (~2-3k tokens) + slopp's richer verification outputs. The bet
  remains that these amortize/win on larger, longer-lived codebases where
  orientation, narrowing, rename-safety, and provenance compound -- tiny
  greenfield apps are the LEAST favorable terrain for slopp, and it already
  reaches parity-to-modest-overhead there.

## Eval round 2: MODIFY-AND-EXTEND, seeded ~16-form tasker (@ 507d0e2)

Six requirements (optional-arg change, cross-ns feature, extract, rename,
test updates) over an unfamiliar seeded codebase; slopp cohort vs
conventional-files cohort (same seed, same spec). All six runs green;
acceptance verified.

| model | workflow | true tokens | duration | tool calls |
|---|---|---|---|---|
| haiku  | files | 31,475 | 131s | 27 |
| haiku  | slopp | 41,410 | 363s | 53 |
| sonnet | files | 45,627 | 250s | 29 |
| sonnet | slopp | 68,156 | 582s | 60 |
| opus   | files | 26,374 | 154s | 13 |
| opus   | slopp | 52,151 | 497s | 37 |

HONEST RESULT: files won across all models at this scale (+32..98% tokens,
2.3-3.2x wall for slopp). The orientation-advantage prediction failed at
~60 lines / 3 namespaces: file agents read everything (~600 tok) and BATCHED
multiple spec items per file write (2-4 writes, 2 test cycles), while slopp
paid a verified round trip per form write, amplified by red-first-per-function
habits and by schema-guessing friction (edit_extract :form vs :source,
edit_group :action vs :op -- wrong keys gave internal errors, not validation
messages; sonnet burned ~1/3 of its calls there). haiku also ran test_run 12x
despite per-write verification.

What worked as designed: edit_rename (3 forms, zero manual call-site edits,
all models), edit_extract + edit_move used successfully, a checkpoint lint
caught a definition-order mistake (sonnet), restart+history available. The
losses are latency/economics + schema UX, not correctness -- slopp cohort had
zero wrong-behavior incidents.

Fixes queued from this round: analysis memo-cache (kondo re-runs dominate
per-write wall), write-op arg aliases + real validation messages, SKILL
guidance on batching groups. Deeper open question for the design: per-write
verification pricing vs batched intents at small scales (files' advantage
here was BATCHING, which edit_group already offers but agents underused).
Caveat: files-sonnet used an nREPL (inherited user config) instead of cold
`clojure -M` per cycle, flattering its wall time somewhat.

## Eval round 3: SCALE (12-ns orders domain, rush-handling task, @ 23670e4)

Files cohort: 3/3 acceptance pass — haiku 36.3k/192s/40, opus 47.2k/283s/43,
sonnet 79.2k/403s/72. Scale DID tax files (opus +79% vs its round-2 cost).

slopp cohort: sabotaged from minute zero by two scale-only product bugs the
round existed to flush out (all sessions opened against a HALF-LOADED image):
- X3: open!/fresh-image! load namespaces in map-key order; >8 namespaces =
  unordered hash map -> requires hit the no-classpath hole -> SIX namespaces
  silently absent from the image (and restart repeats the damage).
- X2: rename! hot-loads its changeset in hash-map key order -> callers can
  reload before the renamed def exists -> compile fail, destructive.
Outcomes against that: sonnet PASS (161.8k/1539s/121 — diagnosed + hand-
rehydrated the image), opus PASS (144.6k/1521s/87 — diagnosed both bugs),
haiku FAIL acceptance (78.6k/970s/137 — inlined five namespaces' logic into
process-order! to escape, losing the rush-shipping rule).

Verdict: round 3 is defect-dominated, not thesis-answering. Notable even so:
two of three slopp agents delivered correct cross-cutting results against a
broken image, with correct provenance; the files cohort's costs grew with
scale as predicted. Round 3b (rerun post-fix) is the real terrain test.

## Eval round 3b: scale RERUN on fixed slopp (@ b3b5dd4)

Same seed, same task, X2/X3/X4 fixed. 3/3 acceptance PASS (identical correct
rush math; haiku produced clean cross-ns code this time).

| model | files | slopp 3b | delta (slopp vs files) |
|---|---|---|---|
| haiku  | 36.3k / 192s / 40 | 48.7k / 437s / 66 | +34% tok |
| sonnet | 79.2k / 403s / 72 | 46.0k / 244s / 19 | **-42% tok, -39% wall, -74% calls** |
| opus   | 47.2k / 283s / 43 | 52.8k / 342s / 19 | +12% tok, -56% calls |

THE CROSSOVER, MEASURED:
- Aggregate at 12-ns scale: slopp 147.5k vs files 162.7k tokens (-9%), and
  104 vs 155 tool calls -- first scale where slopp wins overall.
- The gradients tell the real story. Files cost grew +54% avg from round-2
  scale to round-3 scale (read-based orientation taxes with size). slopp's
  cost was flat-to-DOWN across the same jump (sonnet 68k->46k) -- orientation
  via outline/references doesn't grow with codebase size.
- Workflow shape converged on the design's intent: opus did the whole task in
  TWO mutations (one cross-ns rename, one 8-form group); sonnet's rename
  propagated across 3 namespaces in one shot.
- haiku remains above its files baseline (weak-model overhead on the tool
  workflow), but passed acceptance with clean architecture -- vs FAILING with
  inlined spaghetti pre-fix.
New (minor) finding N1: effectful-vars doesn't propagate effects across
namespaces (a !-named callee in another ns should count as an effectful
anchor); process-order! showed :effectful? false.
| 2026-07-03 | 8e46d01 | calculator | 2 | 11 | 204 | 725 | 717 |
| 2026-07-03 | 8e46d01 | inventory | 1 | 7 | 66 | 345 | 375 |
| 2026-07-03 | 8e46d01 | wordstats | 1 | 8 | 80 | 427 | 493 |

Note (2026-07-03, 8e46d01): the wall collapse vs 66c30c0 (calculator
1316->204ms, wordstats 1277->80ms) is D5.1 — deliberate TDD reds no longer
pay a fresh-image restart + re-run; they return {:diagnosis :genuine} from
the single run. inventory (no reds in script) stays flat, as expected.

## Eval round 3c: same task/seed on improved slopp (@ fcdbe7d, items 0-5)

Files baseline FROZEN from round 3 (nothing in the files workflow depends on
slopp). 3/3 acceptance PASS, verified independently (rename gone, ship
exactly 2x, bulk dropped/member kept 1500->500, [RUSH], :rush?, 12/12 nses
green in every store).

| model | files (frozen) | slopp 3b | slopp 3c | 3c vs files |
|---|---|---|---|---|
| haiku  | 36.3k / 192s / 40 | 48.7k / 437s / 66 | 40.3k / 380s / 54 | +11% tok (was +34%) |
| sonnet | 79.2k / 403s / 72 | 46.0k / 244s / 19 | 53.6k / 289s / 28 | -32% tok, -28% wall |
| opus   | 47.2k / 283s / 43 | 52.8k / 342s / 19 | 59.8k / 514s / 32 | +27% tok, +82% wall |

- Aggregate: slopp 153.7k vs files 162.8k tokens (-5.5%; 3b was -9%), 114 vs
  155 harness calls (-26%). Wall aggregate now FAVORS files (+35% slopp) --
  the per-ns test_run sweep is the new dominant cost (see below).
- haiku -- the explicit target of items 1+3 (query_project, help, hints) --
  improved on every axis vs 3b and closed most of its files gap. It used
  query_search and edit_rename cleanly and reported zero friction.
- sonnet/opus ran heavier than their 3b selves (n=1 per cell; variance is
  real). Both did far MORE verification this round: full per-namespace
  test_run sweeps (12 calls each; sonnet ran 27 test_runs total) because
  test_run has no project-wide form. That sweep tax -- not per-write
  verification -- is now the top optimization target.

Findings (3c):
- F-3c1 test_run needs a no-:ns / :all form (both strong models paid 12
  calls per full sweep; also each sweep re-pays traced instrumentation).
- F-3c2 query_eval swallows exceptions -> returns [] (found during
  acceptance probing; an agent debugging a red is blind to the error).
- F-3c3 query_references only sees same-namespace usages (sonnet flagged;
  cross-ns callers found only via query_search; rename itself is unaffected).
- F-3c4 SKILL.md lacks the edit_group steps schema (opus probed for it).
- F-3c5 verification narrowing before a trace map exists can report a
  PARTIAL red set for a group (opus: only shipping-t of 3 expected reds).

## Multi-Claude eval: two real Claude Code instances, one store (@ 5d3c48a)

The Phase-4/5 integration test, first run with REAL clients (spec/prompts:
projects/eval-multi/SPEC.md; acceptance 14/14 PASS). Two headless
claude-sonnet-5 instances, each with its OWN stdio MCP server on one shared
store; turns hook-automated; alice on a branch with a DESIGNED same-form
conflict against bob on main.

- alice: 45 turns / 165s -- branch, feature, merge; resolved the task-line
  MV conflict by integrating both features, unprompted, 22/22 green.
- bob: 20 turns / 114s -- watched alice's work land mid-session through
  journal sync, "never had to coordinate or lock anything"; scoped his own
  episode with query_changes.
- Every layer built this phase carried real load for the first time: native
  stdio integration, turn gate + hooks (verbatim prompts in provenance),
  per-agent servers, cross-server sync, branch/merge causal delivery,
  episodes. Zero integration failures; the only scoring bugs were in the
  harness (field name, grep escape), not the product.
| 2026-07-03 | 00d870e | calculator | 2 | 11 | 249 | 725 | 722 |
| 2026-07-03 | 00d870e | inventory | 1 | 7 | 96 | 345 | 380 |
| 2026-07-03 | 00d870e | wordstats | 1 | 8 | 91 | 427 | 497 |

## Eval round 3d: same task on current slopp (@ 00d870e, protocol v2)

Both PASS 20/20 acceptance. vs their own 3c runs (same task/seed), while
ABSORBING the new turn protocol (2+ extra required calls):

| model | 3c | 3d | delta |
|---|---|---|---|
| sonnet | 53.6k / 289s / 28 | 50.9k / 267s / 22 | -5% tok, -8% wall, -21% calls |
| haiku  | 40.3k / 380s / 54 | 44.5k / 318s / 44 | +10% tok, -16% wall, -19% calls |

- haiku vs its FILES baseline: +22% tokens (was +34% in 3c, +11%... varies
  by run; call count keeps falling: 66 -> 54 -> 44). The 35-tool surface did
  NOT confuse it — the turn gate + labels were followed cleanly first try.
- sonnet's workflow is now the designed shape exactly: 1 rename covering all
  call sites, red->green pair per feature, ONE full-project test_run sweep
  (F-3c1), 10 writes total, zero manual multi-site patterns.
- Scripted micro-benchmark (same commit): calculator 249ms / inventory 96 /
  wordstats 91 — up ~3-4ms/call vs 8e46d01 from added per-call dispatch
  (hints, journal-sync check, turn gate). Known cause, acceptable.
- MINING (slopp.mine, all 6 journals incl. 3c + multi-Claude): zero
  workaround fanouts. Only signature-change shapes are the task's own
  make-order arity addition with 0 caller edits (multi-arity absorbed it).
  change_signature / inline stay deferred on evidence.
| 2026-07-11 | 998aaa9 | calculator | 2 | 11 | 256 | 726 | 786 |
| 2026-07-11 | 998aaa9 | inventory | 1 | 7 | 79 | 345 | 456 |
| 2026-07-11 | 998aaa9 | wordstats | 1 | 8 | 155 | 427 | 580 |
| 2026-07-12 | e194fa4 | calculator | 2 | 11 | 334 | 726 | 786 |
| 2026-07-12 | e194fa4 | inventory | 1 | 7 | 97 | 345 | 456 |
| 2026-07-12 | e194fa4 | wordstats | 1 | 8 | 119 | 427 | 580 |

## Symmetric eval, wave 5: the PLUGIN setup (3 apps × 3 models, @ 06a15e0)

The first wave through the artifact users actually install: headless Claude
Code instances in fresh dirs, slopp served by the plugin (release jar
v0.1.2), skills discovered as plugin skills (`slopp:slopp`) instead of a
pasted SKILL.md, native MCP instead of the curl bridge. Protocol + rows:
projects/eval5-plugin/ (SPEC.md carries the verbatim wrapper).

**9/9 orchestrator acceptance PASS — the first zero-failure, zero-flail
wave.** All renames via edit_rename (no manual call-site edits, all models);
sonnet batched both TDD phases as edit_groups; no restarts, no invented
tools, no schema retries; the only self-reported multi-step intent was
opus renaming a test VAR after the real rename (correct: a naming echo is
not a reference).

Clean cross-wave signals (wall / slopp calls; true tokens are NOT
comparable — full Claude Code sessions carry the user's whole tool surface,
vs the old lean sub-agent harness; model ids also drifted):

| model | app | waves 2–4 (bridge) | wave 5 (plugin) |
|---|---|---|---|
| haiku  | calculator | 241s / 29 | 172s / 36 |
| haiku  | inventory  | 56s / 10  | 179s / 13 |
| haiku  | wordstats  | 381s / 62 | 318s / delegated to 8 sub-agents (PASS) |
| sonnet | calculator | 175s / 24 | 163s / 16 |
| sonnet | inventory  | 145s / 20 | **66s / 10** |
| sonnet | wordstats  | 179s / 26 | **124s / 21** |
| opus   | calculator | 264s / 25 | **164s / 12** |
| opus   | inventory  | 97s / 16  | 127s / 14 |
| opus   | wordstats  | 128s / 17 | **91s / 14** |

- Wall fell in 7 of 9 cells; tool calls fell in 7 of 9 (opus calculator
  halved, 25→12). The strong models' workflows converged on the design:
  orient → group writes → rename → milestone, linear.
- haiku-inventory regressed vs its all-time-best wave-2 run (56s/10 was the
  single cleanest run ever recorded; n=1 variance) and haiku-wordstats
  spontaneously fanned out sub-agents — emergent full-harness behavior the
  bridge never allowed; acceptance still passed.
- Payload asymmetry persists by design (in < out): e.g. sonnet inventory
  471 in / 874 out — form-writes stay cheap, verification pays the freight.
- Infrastructure result: zero MCP connect failures across 9 fresh sessions
  (the cold-start single-flight + warm-hook work); skills discovered via
  the plugin; the `slopp` CLI on PATH went unused by agents (MCP sufficed)
  except as the orchestrator's acceptance probe.
