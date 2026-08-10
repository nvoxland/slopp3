---
name: ideas-backlog
description: How the ideas/ backlog is tracked in this repo — the running-log genre, severity/status markers, the move-to-ideas/done rule, splitting a partly-done log with a pointer line, and filename stability. Use whenever adding to, resolving, splitting, or reorganizing anything under ideas/, or when asked to "update the backlog" / "move done items" / "log this idea". The rules originate in AGENTS.md ("ideas/ is the backlog; ideas/done/ is the record"); this skill is the operational how-to. For noticing/recording/analyzing a FRICTION specifically, use friction-log (it defers here for the lifecycle mechanics).
---

# Tracking the ideas/ backlog

`ideas/` is the OPEN worklist; `ideas/done/` is the record. The split is
load-bearing: a worklist that also carries its own history stops reading as a
worklist — a log where nine of ten items are already fixed scans as nine items
of work. Keep OPEN and DONE physically apart.

## Finish every sweep with `bin/check-ideas-backlog.py`

**Run it. Do not rely on this file's rules, including the ones below.** They
were all here and correct on 2026-08-09, a sweep was performed by someone
reading them, and it still left two closed cluster sections unmoved, a finished
cluster directory in the open half, a "Still open" table where six of seven
rows were green, a "Still open" heading whose only two bullets had both
shipped, and **21 broken links**. Prose was not the missing thing
(`.context/design-disciplines.md` Core 4), and the ad-hoc scan invented to
catch it up was itself the bug report — the friction-log skill's own trigger.

The check reads the rules below at every grain and exits non-zero with the file
and line. It also skips cleanly when `ideas/` is absent, so it is safe to run
anywhere.

## What lives in ideas/

Anything still open: frictions, proposals, feature ideas, wave logs, the
prioritized fix plan (`root-cause-fix-plan.md`). One concern per file; a
running log (`logs/*-wave-frictions.md`, `logs/dogfooding-agent-frictions.md`)
collects many numbered items of one genre.

**Since 2026-08-06 it has SUBDIRECTORIES. `ideas/README.md` is the map — read
it before filing.** Five were root-cause clusters, each with a `GOAL.md` stating
the goal, the mechanism slopp already built ONCE for that class, and its
members: `refusal/`, `observation/`, `projection/`, `correspondence/` — and
`addressing/`, which CLOSED 2026-08-09 and is now `done/addressing-cluster.md`,
the worked example of the rename-on-move exception below. Three are not
clusters: `logs/` (the running wave logs),
`product/` (features and app-type design), `research/` (parked or
measurement-gated).

**Where a new item goes.** A friction found DURING a wave still goes to that
wave's log in `logs/` — that is the point of a running log, and most cluster
members physically live there. A standalone file goes to the cluster whose
`GOAL.md` names its root, and **the cluster's `GOAL.md` gains a line for it**;
a `GOAL.md` that does not list a member is the same hand-kept-list defect the
`correspondence/` cluster is about. If it belongs to no cluster, say so
explicitly rather than forcing it — `product/GOAL.md` and `research/GOAL.md`
both open by stating why their contents are NOT a cluster.

## Status & severity markers (house style)

Inline, so a reader sees state at a glance:

- 🔴 open · 🟡 partial / half-fixed · 🟢 fixed
- ⭐ marks the highest-value item in a log (sparingly).
- A 🟢 entry NAMES what fixed it and the test that pins it — e.g.
  `🟢 BUILT (done d6594): slopp.api/undo!, pinned by …/undo-walks-back-by-delta`.
  The record must answer "was this ever addressed, and how?" without a git dig.

## The move-to-done rule

**Move an item when it is actually done — verified GREEN, not merely written.**
"Written a fix" is not done; "a test pins it and `done`/`full_check` is green"
is.

**`ideas/done/` is FLAT.** A file's cluster directory does NOT follow it there
— only its name. `projection/replica-model.md` finishes as
`done/replica-model.md`, not `done/projection/replica-model.md`.

- **A whole file finished** → move the file to `ideas/done/<same-name>.md`.
  Keep the filename identical (stability: links and memory point at it), and
  drop its line from the cluster's `GOAL.md`.

  **Exception, and it is forced: a cluster's `GOAL.md` must be renamed.**
  `done/` is FLAT and every cluster's goal file is called `GOAL.md`, so the
  same-name rule cannot hold — the findable name is the CLUSTER's.
  `addressing/GOAL.md` → `done/addressing-cluster.md`, and remove the emptied
  directory. Say in the moved file where it was.
- **Some items in a running log finished** (the usual case) → move the FINISHED
  items into `ideas/done/<same-name>.md` (create it if needed), and leave the
  open ones behind under a short pointer line naming where the rest went. Both
  halves say which half they are. Worked examples: `logs/web-wave-frictions.md`,
  `logs/cljs-wave-frictions.md`,
  `correspondence/the-patterns-behind-every-failure.md`,
  `compensating-behaviors-are-slopp-bugs.md`.

The pointer line convention, both directions:

```
# open half (ideas/foo.md)
> The RESOLVED bulk of this log is in ideas/done/foo.md. What follows is only
> what is still open.

# done half (ideas/done/foo.md)
> Split from ideas/foo.md — the items now fixed. The still-open half stays there.
```

Nothing is ever deleted — the record just stops competing with the backlog for
attention. (Delete only a memory/idea that turned out flat WRONG, and say so.)

**A finished record hides at THREE grains, and a heading scan misses two of
them.** Measured 2026-08-09: a `GOAL.md` carried a table headed *Still open*
with six of seven rows green, and another carried a *Still open* heading whose
only two bullets had both shipped. Check headings, bullets, AND table rows —
this is what the script does, and why looking at headings only reports clean
over a document that is mostly record.

**Moving a file BREAKS ITS INBOUND LINKS, and nothing else notices.** Every
`ideas/<name>.md` reference now points at nothing. The same sweep found 21
already broken, six of them *inside* `done/` — the half people grep when asking
"was this ever addressed?" Repoint them in the same change.

**Never use a marker GLYPH as a noun.** Writing *"the body said 🟢 and the
heading said 🔴"* overloads the one vocabulary a scan — and a skimming human —
reads for state. Write the words: *"the body said green."*

## When you close an item

1. **Move it** per the rule above (green, not written).
2. **Route the lasting lesson** by the AGENTS.md routing test — *would this help
   someone using slopp on a different codebase?* Yes → a shipping skill
   (`plugins/slopp/skills/**`); no, it's why-slopp-is-built-this-way →
   `.context/` (`design-disciplines.md` for a cross-cutting core, `decisions.md`
   for a settled decision, the subsystem doc otherwise); a historical
   observation → `.context/findings-log.md`. The ideas entry records the
   INCIDENT; the durable rule lives in its home.
3. **Update docs in the same change**, and if a mechanic changed, sweep the
   skills for now-wrong guidance (AGENTS.md rule 4).
4. **Keep the fix plan honest.** When an item in `root-cause-fix-plan.md` lands,
   mark it and point at what pinned it, so the plan reflects reality.
5. **Re-measure the entry's PREMISE before building what it asks for.** Two
   items closed 2026-08-09 and both had a remedy written in the backlog that
   was WRONG: one asked for a better near-miss message for a call that should
   have matched, the other for an attribution to a form that is never edited.
   An entry is filed by someone mid-friction who had no reason to check; the
   proposed fix is a hypothesis, not a spec.
6. **Run `bin/check-ideas-backlog.py`.**

## Don't

- Don't leave a fixed item in the open half "for reference" — that's exactly
  the nine-of-ten-done noise the split exists to kill. Move it; the reference
  survives in `done/`.
- Don't rename a file on move (breaks pointers and memory) — **except a
  cluster `GOAL.md`, which has to be renamed; see above.**
- Don't hand-scan and call it clean. Run the script.
- Don't record the durable lesson ONLY in the ideas entry — it helps one repo
  (this one) and misses its real home. Route it.
