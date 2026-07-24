---
name: ideas-backlog
description: How the ideas/ backlog is tracked in this repo — the running-log genre, severity/status markers, the move-to-ideas/done rule, splitting a partly-done log with a pointer line, and filename stability. Use whenever adding to, resolving, splitting, or reorganizing anything under ideas/, or when asked to "update the backlog" / "move done items" / "log this idea". The rules originate in AGENTS.md ("ideas/ is the backlog; ideas/done/ is the record"); this skill is the operational how-to. For noticing/recording/analyzing a FRICTION specifically, use friction-log (it defers here for the lifecycle mechanics).
---

# Tracking the ideas/ backlog

`ideas/` is the OPEN worklist; `ideas/done/` is the record. The split is
load-bearing: a worklist that also carries its own history stops reading as a
worklist — a log where nine of ten items are already fixed scans as nine items
of work. Keep OPEN and DONE physically apart.

## What lives in ideas/

Anything still open: frictions, proposals, feature ideas, wave logs, the
prioritized fix plan (`root-cause-fix-plan.md`). One concern per file; a
running log (`*-wave-frictions.md`, `dogfooding-agent-frictions.md`) collects
many numbered items of one genre.

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

- **A whole file finished** → move the file to `ideas/done/<same-name>.md`.
  Keep the filename identical (stability: links and memory point at it).
- **Some items in a running log finished** (the usual case) → move the FINISHED
  items into `ideas/done/<same-name>.md` (create it if needed), and leave the
  open ones behind under a short pointer line naming where the rest went. Both
  halves say which half they are. Worked examples: `web-wave-frictions.md`,
  `cljs-wave-frictions.md`, `the-patterns-behind-every-failure.md`,
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

## Don't

- Don't leave a fixed item in the open half "for reference" — that's exactly
  the nine-of-ten-done noise the split exists to kill. Move it; the reference
  survives in `done/`.
- Don't rename a file on move (breaks pointers and memory).
- Don't record the durable lesson ONLY in the ideas entry — it helps one repo
  (this one) and misses its real home. Route it.
