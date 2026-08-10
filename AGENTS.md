# Working in this repo

This repo is **slopp** — an agent-native codebase system. There are two
distinct modes of working here, and they use different tools.

**1. Working ON slopp** (this repo's own Clojure code). Everything below
applies. Setup, running mcp, test tiers, benchmarks, and the docs site:
[DEV.md](./DEV.md).

**2. Working THROUGH slopp** — authoring code in a store, here or anywhere.
Read `plugins/slopp/skills/slopp/SKILL.md` FIRST; it teaches the efficient
loop: orient with `session_brief`, read with `query_slice`, write one small
form at a time with an intent `prompt`, trust the verification attached to
every write, and call `done` at every point you believe a unit of work is
finished. Under Claude Code those skills load automatically from the plugin;
other harnesses should read the file directly. Connect the MCP server per the
README's quick start.

Mode 1 *is* mode 2: slopp's own code lives in a store, so working on slopp
means working through slopp.

## Knowledge has homes, and the routing is load-bearing

Putting knowledge in the wrong home is a real failure mode, not a filing
preference.

- **`.context/`** — why slopp is built the way it is. Design decisions,
  system mechanics, internal gotchas. Audience: whoever works ON slopp.
- **`plugins/slopp/skills/`** — how to WORK with slopp. **These SHIP.** Every
  agent and user driving slopp anywhere gets them, and they are the product.

(A third, narrower home: **`docs/`** + `mkdocs.yml` — the public site, for a
HUMAN evaluating or adopting slopp. It's the skills' content re-aimed at a
reader who doesn't have the tools in front of them, plus the release blog. It
is derived, not a source of truth: write the rule in the skill first, then
audit the site. Tone: `.context/writing-style.md`.)

**The routing test: would this help someone using slopp on a completely
different codebase?** If yes it belongs in a skill, whatever else you also
record. "The tier axis is internal/external *because* read/write measured
zero members" is `.context/`. "Caches must go through `slopp.cache`, and
`without-caching!` is how you test them" is a SKILL — a user hits that rule
on day one and will never read our decision log.

A lesson about using slopp that lives only in `.context/` helps exactly one
repo: this one. That is the whole project failing at its own goal.

### `ideas/` is the backlog; `ideas/done/` is the record

`ideas/` holds what is still OPEN — frictions, proposals, wave logs.
It is a worklist, and a worklist that also carries its own history stops
reading as a worklist: a log where nine of ten items are already fixed
scans as nine items of work.

**Read `ideas/README.md` first — it is the map.** Reorganized 2026-08-06
around the five root-cause clusters in
`ideas/five-mechanisms-that-exist-once.md`: `refusal/`, `observation/`,
`projection/`, `correspondence/`, `addressing/`, each with a `GOAL.md`
stating the goal, the mechanism slopp already built ONCE for that class, and
its members. Everything else is `logs/` (the running wave logs — chronological
and multi-cluster, which is where most cluster members physically live),
`product/` (features and app-type design) and `research/` (parked or
measurement-gated). `ideas/root-cause-fix-plan.md` stays the item-grain
router.

**`ideas/` is gitignored, deliberately and permanently.** It is a LOCAL
worklist. Do not un-ignore it, do not `git add -f` under it, and do not
report the untracked state as a finding — the conventions below read like
a tracked backlog, which is why this keeps getting raised as an accident.
It isn't one.

So: **when you finish an item, MOVE it — same filename, into
`ideas/done/`.** `done/` is FLAT: a file's cluster directory does not
follow it there, only its name. Nothing is deleted; the record just stops
competing with the backlog for attention.

- **A whole file finished** → move the file to `ideas/done/`.
- **Some items in a running log finished** (the usual case for the
  `ideas/logs/*-wave-frictions.md` logs) → move the finished items into
  `ideas/done/<same-name>.md`, creating it if needed, and leave the open
  ones behind under a short pointer line naming where the rest went. Both
  halves say which half they are. `logs/web-wave-frictions.md` and
  `logs/cljs-wave-frictions.md` are the worked examples.
- **Move it when it's actually done** — verified green, not merely
  written. A resolved item carries what fixed it, so the record answers
  "was this ever addressed?" without a git archaeology dig.

**Finish any backlog sweep with `bin/check-ideas-backlog.py`.** The rules above
are necessary and have proved insufficient twice: a finished record hides as a
bullet or a TABLE ROW where a heading scan reports clean, and moving a file
silently breaks every inbound link to it. The script reads all three grains
plus the links, and skips cleanly when `ideas/` is absent (a fresh clone).

1. **Read the relevant doc before touching its subsystem.** They're short on
   purpose — skipping them costs more than reading them.
2. **Update the relevant doc in the same commit as your change.** Docs that
   drift from code are worse than no docs.
3. **Capture new knowledge in `.context/` AND/OR the skills — never in agent
   memory or chat.** Apply the routing test above; plenty of things belong in
   both, phrased differently (the skill states the rule, `.context/` records
   why it was chosen and what was measured).
4. **When you change how slopp WORKS, audit the skills for what now points
   the wrong way.** Stale guidance is worse than missing guidance: a skill
   that names a retired tier or a renamed marker actively misleads. Grep the
   old vocabulary before you finish — and watch for near-misses, e.g. the
   `:reads` TIER is retired while the form-level `^:reads` marker is still
   valid, so a careless sweep breaks correct guidance. The same sweep covers
   `docs/`, `DEV.md`, and this file.

   **This sweep is YOURS — there is no mechanism behind it, deliberately.**
   A rename that rewrites the store and walks past the files is how four bugs
   shipped on 2026-08-02, including a skill telling agents to read a
   `session_brief` key that returned nil. The machinery built in response —
   a declared old→new table plus two checks reading it — was retired on
   2026-08-06 once the restructure it existed for finished: it had become a
   72-row hand-kept ledger for a conversion nobody was doing, and its own
   documentation admitted nothing read the prose half. Rebuild something like
   it if a rename of that scale happens again; do not carry it between times.

## Always-on rules

- **What this is:** an agent-native codebase system — the top-level form is
  the unit of editing, storage, hot-reload, verification, and provenance.
  Code lives in a store (SQLite-backed delta log), NOT in `.clj` files on
  disk; a VFS renders source on demand. Stance: `.context/architecture.md`.
- **The working tree is FILELESS**: slopp's own code (system + tests) lives
  in `.slopp/store.db`; only `deps.edn` and `build.clj` are files humans own.
  ALL development goes through slopp's MCP tools — including the boot kernel,
  which is IN the store despite `src/slopp/kernel/boot.clj` and `src/slopp/kernel/rt.clj`
  existing on disk. **Those two are projections**: `build!` materializes them
  from the store over `target/jar-src/`, so a hand-edit there is silently
  discarded at build time and looks exactly like a stale jar. Edit
  `slopp.kernel.boot` / `slopp.kernel.rt` through the tools like everything else. What is
  special about them is narrower than "needs a restart", and the distinction
  is worth knowing because it decides whether you interrupt the user:
  **a kernel function the poll loop CALLS is hot-fixable** (`reload-ns!` is
  looked up per poll, so a redefinition takes effect on the next one — this
  is how the alias wedge was fixed on a running host); **the loop's own body
  is not**, since `watch-live!` is already executing; and **a fresh boot
  always needs the rebuilt jar**, because the kernel must come from the jar
  to exist before the store is readable at all. So: rebuild always, restart
  only when the change is in the boot path or the loop body. There is no
  `:test` alias, so `clojure -M:test` does not work here; see DEV.md.
- **Decisions are settled in `.context/decisions.md`** — that file holds
  DECISIONS ONLY (D/C/O/H/G/S/R4 + the named `D-*` series). Don't
  re-litigate silently — revisit explicitly, and record the change.
  Observations are a different genre and go elsewhere: historical findings
  from user tests, evals, probe sessions and dogfooding turns →
  `.context/findings-log.md`; open frictions → `ideas/`; forward plans →
  `.context/roadmap.md`. This split is load-bearing — filing findings in the
  decision log once grew it to 47% non-decisions, which is how a settled
  decision becomes hard to find and easy to contradict.
- **Red/green TDD, always.** Tests first, watch them fail, then implement —
  through the edit tools (per-write verification reports the red/green).
  **Call `done` at every point you think you're finished with something,
  not once at the end** — finishing a unit of work and starting the next IS
  a done point, and finding out you weren't done is cheapest right then.
  `done` runs the whole in-image suite plus impacted `^:external` tests and
  REPORTS rather than refuses; `commit_point` has no checks of its own and
  gates on done's verdict, so there is exactly ONE bar. A red done STANDS
  until new work supersedes it. `done` is EPISODE-scoped (its `:scope` field
  says so); **`full_check`** is the whole store — every namespace, every
  tier — and nothing forces it. Reach for it on a broad change, after
  deleting a caller, or before a commit you want to stand behind. No manual
  `test_run` ritual.
- **The CODE never references `.context/` or `ideas/`.** Those are helper
  directories for whoever is working ON slopp; they are not part of the
  product. The store SHIPS — every form materializes into `src/` and is
  jarred — so a docstring is read by people who have neither, and `ideas/` is
  gitignored so its paths resolve for nobody at all. This covers concept
  CITATIONS as well as paths: a bare "Core 2" leans on a file the reader does
  not have exactly as `see .context/design-disciplines.md` does. **State the
  reasoning inline** — if a point is worth citing it is worth one sentence.
  Write the durable rule in `.context/` too when it generalizes; the routing is
  both/and, phrased differently. Enforced by
  `ops.selfcheck-test/no-form-cites-a-document-that-does-not-ship`, which was
  written after every `ideas/` path in the store turned out to be ALREADY DEAD
  — four docstrings pointing at files a directory reorganization had moved,
  with nothing able to notice.
- **Never credit Claude or any AI in commit messages or PRs.** No
  Co-Authored-By trailers, no "Generated with" footers.
- **Dogfooding is a standing practice:** build real things through slopp
  itself under `projects/<name>/` (untracked); write findings to a
  `REPORT.md` there; findings drive the roadmap. See `.context/dogfooding.md`.
- **Benchmark at milestones** (`clojure -M -m slopp.kernel.boot . --snapshot --main slopp.lab.benchmark/-main` — the tree is fileless; plain `-m slopp.lab.benchmark` finds nothing).
  The row appends to `benchmarks/results.md`, which is **gitignored** — a LOCAL
  record, never committed. See `.context/dogfooding.md`.
- **The image is the oracle:** verification correctness depends on
  restart-as-diagnostic and the trace map. Don't weaken those paths without
  reading `.context/verification.md` first.

## Doc map

| Doc | Read before touching |
|---|---|
| `DEV.md` | setup, running mcp, tests, benchmarks, the docs site, CI |
| `.context/architecture.md` | anything — the layer map + core stance |
| `.context/design-disciplines.md` | building ANY surface/rule — the friction cores + the disciplines that avoid them |
| `.context/decisions.md` | any design-level change — DECISIONS only |
| `.context/findings-log.md` | what past user tests / evals actually observed |
| `.context/store-and-persistence.md` | `slopp.store`, `slopp.store.db`, `slopp.store.render` |
| `.context/verification.md` | `slopp.kernel.rt`, `slopp.image`, verification in `slopp.ops.engine` |
| `.context/dialect.md` | `slopp.edit` dialect gate, `slopp.index` `!`-effects |
| `.context/operation-api.md` | `slopp.ops`, `slopp.mcp`, `slopp.edit.refactor` |
| `.context/dogfooding.md` | user tests, benchmark suite, findings backlog |
| `.context/working-in-this-repo.md` | dev workflow, REPL, tests, commits |
| `.context/writing-style.md` | `docs/`, the blog, release notes, README copy |
