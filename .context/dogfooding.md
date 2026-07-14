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

- Run: `clojure -M -m slopp.benchmark` (NOT part of `clojure -M:test` — it
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

First-person friction (2026-07-14, cards/differential wave): every edit
to a tool DESCRIPTOR or dispatch clause starts with a sqlite grep for
exact match text — subform matching wants a KEYED mode ("the unique map
whose :name is X") for registry-style vectors; would collapse the
grep→copy→match loop into one addressed call. Second (mild): cold-load
ordering hit again (form-card before session-brief) — the teaching error
+ edit_move is fine, but the pattern "add composite, forget dep order"
recurs; a :before hint in the refusal naming the EXACT anchor would
close it fully.

Benchmark cadence (user directive, 2026-07-14): the lifetime evals run
ONLY on explicit request or a genuine decision need — never routinely
per change wave. Self-dogfooding is the continuous gauge; the benches
are for claims.
