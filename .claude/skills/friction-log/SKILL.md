---
name: friction-log
description: How to track, record, and analyze friction while working ON slopp (dogfooding its own development). Use whenever working through slopp is slower than plain files, you detour to Bash/sqlite/git around the tools, a slopp surface gave a confident wrong or partial answer, you catch yourself inventing an "I always check X now" habit, OR you're asked to review/synthesize the friction backlog. Covers where a friction goes, how to write one, how to root-cause it to a core, and how to close it. Not a product skill — this is about building slopp, not driving it elsewhere.
---

# Recording & analyzing friction

Friction is the primary signal for what to build next in slopp. This is the
loop for capturing it honestly and turning it into a fix that closes a CLASS,
not just an instance. Companion reference: `.context/design-disciplines.md`
(the four recurring cores + the disciplines and dead ends). Backlog:
`ideas/`. Prioritized plan: `ideas/root-cause-fix-plan.md`.

## 1. Notice — the triggers

Log the moment any of these happens. Don't wait for the end of the session;
the detail evaporates.

- **Slower than files.** A thing that would be one grep/edit took a detour.
- **You left slopp.** You reached for Bash / `sqlite3` / raw `git` because no
  slopp surface answered — the cost lands where no slopp metric sees it, so it
  is invisible unless you write it down (P7).
- **A confident wrong or partial answer.** A read/report said something that
  turned out incomplete or false, and you only caught it by doing the work
  yourself. This is the highest-value class — see Core 1.
- **You invented a habit.** Any "I always check X now" / "I pick names no sweep
  will touch." **The habit is the bug report** — it is you hand-patching a hole
  the system should own (Core 4).
- **A gate/rule fought a legitimate change,** or fired on data it shouldn't.

## 2. Where it goes

The backlog MECHANICS — running logs, status markers, the move-to-`done` rule,
splitting with a pointer line — live in the **ideas-backlog** skill; this
section is the friction-specific summary.

`ideas/` is the OPEN backlog (per AGENTS.md — a worklist, not a history).

- **During a build wave** → the wave's running log, `ideas/<name>-wave-frictions.md`
  (`web-wave-frictions.md` / `cljs-wave-frictions.md` are the worked examples).
- **A standalone recurring friction** → its own file, or the general dogfooding
  log `ideas/dogfooding-agent-frictions.md`.
- **When it's RESOLVED (verified green, not merely written)** → move it to
  `ideas/done/<same-name>.md`, leaving a one-line pointer where the rest went.
  A log where nine of ten items are already fixed scans as nine items of work.

The lesson that OUTLIVES the fix routes by the AGENTS.md routing test — *would
this help someone using slopp on a different codebase?*
- Yes → a **skill** (`plugins/slopp/skills/**` — it ships).
- No, it's why-slopp-is-built-this-way → **`.context/`**
  (`design-disciplines.md` for a cross-cutting core; `decisions.md` if it's a
  settled decision; the subsystem doc if it's local).
- A historical observation from an eval/probe → `.context/findings-log.md`.

## 3. Write ONE friction well

Number it and give enough detail to FIX it without you in the room. A good
entry carries:

- **What you were doing and what happened** — the concrete instance, not a
  paraphrase. Quote the actual result keys / the actual wrong value.
- **Severity + status** with the house markers: 🔴 open · 🟡 partial ·
  🟢 fixed (name what fixed it and the test that pins it).
- **Scope label: self-host-only vs user-facing.** THE cheapest, most-skipped
  guard (Core 3). A live-handle brick or an analyzer-names-a-banned-symbol
  problem is catastrophic only when slopp edits slopp; in a user's project it's
  an ordinary red. Say which, so the roadmap doesn't tilt toward failure modes
  the customer never hits.
- **The missing guarantee, if it's a habit.** Name the guarantee that would
  make the habit unnecessary — that's the actual feature request.
- **Measure before proposing.** Run the check over the REAL store and read the
  finding list before claiming a rule/fix. Numbers change the design (the
  keyword guard was redesigned twice by measuring; three dialect restrictions
  died on contact with measurement). A proposal with no number is a guess,
  and should be labelled one.

## 4. Analyze to the core (5 whys)

Individual frictions are usually instances of a few shared roots. Before
writing a fix, ask "why" until you hit something structural, then check it
against the four cores in `.context/design-disciplines.md`:

1. **Reads inherit unearned trust** — a surface reported success without
   checking; absence-of-check shares a representation with absence-of-finding.
2. **One relationship is first-class; the rest rot** — a relationship the tools
   can't see (keyword, coverage, prose, kernel copy) drifted. Fix: a new
   producer into THE canonical edge set; fix the analysis before restricting
   the language.
3. **Self-hosting is a distorting lens** — is this actually user-facing?
4. **The agent is an unreliable narrator** — the answer is a system guarantee,
   never "try harder / add a skill line."

If a friction fits none of the four, that itself is worth flagging — it may be
a fifth core.

## 5. Close it — class over instance

Almost every log in `ideas/done/` reads "fixed this one, the general bug is
open." That is the failure to avoid. Before calling it done:

- **Did you fix the CLASS or the instance?** Spend on the chassis that
  generalizes — the rule registry/catalog, THE reference graph, shared form
  accessors (`store/form-sexpr` etc.) — rather than a point-fix that lets the
  same shape recur elsewhere.
- **Green, not written.** Move to `ideas/done/` only when a test pins it. A
  resolved item carries what fixed it, so the record answers "was this ever
  addressed?" without a git dig.
- **Update the docs in the same change** — the relevant `.context/` doc and,
  when a rule/mechanic changed, sweep the skills for now-wrong guidance
  (AGENTS.md rule 4).

## Don't re-walk the known dead ends

`.context/design-disciplines.md` § "Wrong directions" lists the measured ones
(the warm image pool, restricting-before-analysis, byte-identity kernel guards,
discipline-where-a-guarantee-is-needed). Check it before proposing a fix that
sounds principled — several were built end-to-end and reverted at zero gain.
