# Working in this repo

Knowledge here has TWO homes, and putting it in the wrong one is a real
failure mode — not a filing preference.

- **`.context/`** — why slopp is built the way it is. Design decisions,
  system mechanics, internal gotchas. Audience: whoever works ON slopp.
- **`plugins/slopp/skills/`** — how to WORK with slopp. **These SHIP.** Every
  agent and user driving slopp anywhere gets them, and they are the product.

**The routing test: would this help someone using slopp on a completely
different codebase?** If yes it belongs in a skill, whatever else you also
record. "The tier axis is internal/external *because* read/write measured
zero members" is `.context/`. "Caches must go through `slopp.cache`, and
`without-caching!` is how you test them" is a SKILL — a user hits that rule
on day one and will never read our decision log.

A lesson about using slopp that lives only in `.context/` helps exactly one
repo: this one. That is the whole project failing at its own goal.

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
   valid, so a careless sweep breaks correct guidance.

## Always-on rules

- **What this is:** an agent-native codebase system — the top-level form is
  the unit of editing, storage, hot-reload, verification, and provenance.
  Code lives in a store (SQLite-backed delta log), NOT in `.clj` files on
  disk; a VFS renders source on demand. Stance: `.context/architecture.md`.
- **The working tree is FILELESS**: slopp's own code (system + tests) lives
  in `.slopp/store.db`; only the boot kernel (`src/slopp/boot.clj`,
  `src/slopp/rt.clj`) and `deps.edn` are files. ALL development goes through
  slopp's MCP tools — there are no source files to hand-edit.
- **Decisions are settled in `.context/decisions.md`** (D/C/O/H/F series).
  Don't re-litigate silently — revisit explicitly, and record the change.
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
- **Never credit Claude/AI in commit messages or PRs.** No Co-Authored-By
  trailers, no "Generated with" footers.
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
| `.context/architecture.md` | anything — the layer map + core stance |
| `.context/decisions.md` | any design-level change |
| `.context/store-and-persistence.md` | `slopp.store`, `slopp.db`, `slopp.render` |
| `.context/verification.md` | `slopp.rt`, `slopp.image`, verification in `slopp.api` |
| `.context/dialect.md` | `slopp.edit` dialect gate, `slopp.index` `!`-effects |
| `.context/operation-api.md` | `slopp.api`, `slopp.mcp`, `slopp.refactor` |
| `.context/dogfooding.md` | user tests, benchmark suite, findings backlog |
| `.context/working-in-this-repo.md` | dev workflow, REPL, tests, commits |
