# Working in this repo

This repo is **slopp** — an agent-native codebase system. There are two
distinct modes of working here, and they use different tools.

**1. Working ON slopp** (this repo's own Clojure code). Everything below
applies. Setup, the dev server, test tiers, benchmarks, and the docs site:
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

`ideas/*.md` holds what is still OPEN — frictions, proposals, wave logs.
It is a worklist, and a worklist that also carries its own history stops
reading as a worklist: a log where nine of ten items are already fixed
scans as nine items of work.

So: **when you finish an item, MOVE it — same filename, into
`ideas/done/`.** Nothing is deleted; the record just stops competing with
the backlog for attention.

- **A whole file finished** → move the file to `ideas/done/`.
- **Some items in a running log finished** (the usual case for the
  `*-wave-frictions.md` logs) → move the finished items into
  `ideas/done/<same-name>.md`, creating it if needed, and leave the open
  ones behind under a short pointer line naming where the rest went. Both
  halves say which half they are. `web-wave-frictions.md` and
  `cljs-wave-frictions.md` are the worked examples.
- **Move it when it's actually done** — verified green, not merely
  written. A resolved item carries what fixed it, so the record answers
  "was this ever addressed?" without a git archaeology dig.

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

## Always-on rules

- **What this is:** an agent-native codebase system — the top-level form is
  the unit of editing, storage, hot-reload, verification, and provenance.
  Code lives in a store (SQLite-backed delta log), NOT in `.clj` files on
  disk; a VFS renders source on demand. Stance: `.context/architecture.md`.
- **The working tree is FILELESS**: slopp's own code (system + tests) lives
  in `.slopp/store.db`; only the boot kernel (`src/slopp/boot.clj`,
  `src/slopp/rt.clj`) and `deps.edn` are files. ALL development goes through
  slopp's MCP tools — there are no source files to hand-edit. There is no
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
- **Never credit Claude or any AI in commit messages or PRs.** No
  Co-Authored-By trailers, no "Generated with" footers.
- **Dogfooding is a standing practice:** build real things through slopp
  itself under `projects/<name>/` (untracked); write findings to a
  `REPORT.md` there; findings drive the roadmap. See `.context/dogfooding.md`.
- **Benchmark at milestones** (`clojure -M -m slopp.boot . --snapshot --main slopp.benchmark/-main` — the tree is fileless; plain `-m slopp.benchmark` finds nothing) and commit
  the updated `benchmarks/results.md` row. See `.context/dogfooding.md`.
- **The image is the oracle:** verification correctness depends on
  restart-as-diagnostic and the trace map. Don't weaken those paths without
  reading `.context/verification.md` first.

## Doc map

| Doc | Read before touching |
|---|---|
| `DEV.md` | setup, dev server, tests, benchmarks, the docs site, CI |
| `.context/architecture.md` | anything — the layer map + core stance |
| `.context/decisions.md` | any design-level change — DECISIONS only |
| `.context/findings-log.md` | what past user tests / evals actually observed |
| `.context/store-and-persistence.md` | `slopp.store`, `slopp.db`, `slopp.render` |
| `.context/verification.md` | `slopp.rt`, `slopp.image`, verification in `slopp.api` |
| `.context/dialect.md` | `slopp.edit` dialect gate, `slopp.index` `!`-effects |
| `.context/operation-api.md` | `slopp.api`, `slopp.mcp`, `slopp.refactor` |
| `.context/dogfooding.md` | user tests, benchmark suite, findings backlog |
| `.context/working-in-this-repo.md` | dev workflow, REPL, tests, commits |
| `.context/writing-style.md` | `docs/`, the blog, release notes, README copy |
