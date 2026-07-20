---
name: slopp-review
description: "Review a change or a whole codebase in a slopp store using slopp's own surfaces — risk-ranked triage, form-granular diffs with recorded intent, test warranty, and the module manifest. Use when asked to review slopp-managed code; for a generic git diff use /code-review."
---

# Reviewing slopp code

A slopp review answers questions from **record**, not guesses. The store carries
what a pile of files can't: each form's stated intent (`:prompt` per delta), the
red/green arc it landed on, the tests that actually exercise it (the trace), and
the enforced module manifest. Review *with* those surfaces — don't re-derive them
by reading source top to bottom.

This is the slopp-native angle; it complements Claude Code's `/code-review` (which
reads a text diff). Reach for this when the code lives in a store.

## Independence first

An author reviewing their own work is weak regardless of tooling. Route a real
review through the **`slopp-reader` subagent** (fresh eyes, read-only) — give it
the scope (a milestone range, a namespace, a module) and prompt it
**adversarially**: *assume there ARE bugs and hunt them, and VERIFY each finding
in the live image with `query_eval` before reporting it.* A descriptive "how does
X work" pass rubber-stamps; an adversarial, image-verified pass is what surfaces
the real defect — the false positive, the corner input an analysis predicate gets
wrong, the gate that blocks a legitimate write. Especially for gate/analysis code,
have it CALL the predicate on hand-picked edge inputs, not read it. It returns
conclusions, so the review reasons about findings, not source dumps.

## Where to start (don't read everything)

1. **Whole-codebase triage → `review_scan`.** Risk-ranks every form by signal:
   `:untested` (unreachable from any test in the call graph), `:unused` (dead
   public surface), `:high-blast` (many callers), `:large`, `:lint`,
   `:undocumented` (public), `:effectful` (`!`). Clean forms drop out; `:top` rows
   carry `:form/:risk/:flags/:callers/:covered`. `{ns "x.y"}` scopes it. Start at
   the top of the risk ranking, not the top of a file.
2. **Reviewing a CHANGE → `report {since}`** composes milestones + changes + the
   asks + verification + alignment in one read; **`query_changes {from to}`** gives
   the net per-form diff (`:was`/`:now` + red/green arc) between two points
   (milestone `:target`s from `query_commits`). `query_changes {agent}` scopes to
   one author's work.
3. **Drill into a form → `query_slice {ns name}`** — full source of that one form
   plus interface CARDS for everything it reaches (sig, doc, why, warranty). For a
   giant form add `match`+`window`.

## What to check — each maps to a surface

- **Correctness → run it, don't reason about it.** `query_call {sym args}` or
  `query_eval "(...)"` exercises the real code against the live image; a red write
  already carries `:failures` + `:implicated`. The oracle is ground truth — prefer
  it to arguing about what the code "should" return.
- **Coverage → the warranty, not a vibe.** `:untested` on a form (from the slice
  card or `review_scan`) means no test exercises it. `draft_test {ns name code}`
  drafts one from observed calls. Zero coverage on a `:high-blast` form is the
  first thing to flag.
- **Architecture → `query_depends {modules true}`.** `:cycles` (should be empty —
  the gate refuses new ones, but adopted/test-fold cycles surface here),
  `:unused-edges` (declared deps nothing uses — retire them), `:layers` (is the
  dependency direction sane?). Boundary violations and visibility are already
  gated, so a *standing* one is debt worth naming. `query_depends {on X}` traces
  who a form/keyword touches before you judge a change's blast radius.
- **Effect honesty → `!` and the boundary.** Is an effectful fn `!`-named? Does a
  fn that should be pure-core reach a mutation or opaque dep (a `!` where the
  design says none should be)? `review_scan`'s `:effectful` flag surfaces the
  `!`-bearing forms; the `query_slice` card shows a form's effect/`^:reads`/
  `^:unsafe` status inline.
- **Dead surface → the unused gate.** `review_scan` `:unused` and the `done`
  `:unused-public` finding flag public vars nothing calls — dead code, or missing
  a reference carrier (`#'var`, `store/late-ref`, `^:entry-point`) / an
  `^:unused-ok` marker for genuine external surface.
- **Contract findings → `query_rules` + the `done` boundary.** `query_rules` lists
  every rule active in this store and its severity — so a review knows what the
  gates already guarantee (don't re-check) and what's dialed `:off` (check by
  hand). The done/milestone boundary records the contract findings a review should
  read: `:schema-drift` (a `:=>` schema that lies about its impl), `:breaking-changes`
  (a boundary contract narrowed vs the last-done baseline — arity / `:=>` schema-key /
  visibility), `:key-typos` (a likely-mis-spelled domain key). `report {since}`
  surfaces them; a breaking-change on a `:high-blast` export is a lead finding.
- **Intent vs. code → provenance.** `query_history {ns name}` (a form's life with
  each version's `:prompt`) and `query_history {contains "X"}` (which asks touched
  X) answer "why does this exist / does the code match what it was asked to do?" A
  form whose body drifted from its stated intent is a finding files can't surface.

## Reviewing against `slopp-style`

Read the `slopp-style` skill for the design shapes. **`query_rules` tells you which
of them this store already gates** (and at what severity) — the code that reached
you passed those, so don't re-litigate them; spend review budget on the *judgment*
rows the gates can't see:

- **Functional-core completeness** — the gate stops an effect *reaching* a `:pure`
  module, but only a reviewer catches logic that *could* be args-in→value-out yet
  was left tangled in the shell. This is the highest-value judgment call.
- **Return-data-not-throw** for expected outcomes; `?`/`*earmuffs*` naming; no
  ambient global mutable state (`def` of an `atom` for logic).
- Where a rule is dialed **`:off`** for this store (check `query_rules`), review it
  by hand — e.g. key hygiene / boundary schemas when the opt-in gates are off.

## Reporting

- **Address findings form-first:** `ns/name`, never a line number — that's how the
  store is addressed and how the fix will be made (`query_slice`, `edit_subform`).
- **Cite the surface** that found it ("`review_scan` flags `:untested :high-blast`",
  "`query_depends` shows a cycle x.y↔x.z") so the finding is reproducible, not an
  opinion.
- **Make it actionable:** name the next tool call to fix or investigate. Rank by
  risk (the `review_scan` weighting is a good prior); lead with correctness and
  untested-high-blast, then architecture, then style.
- Don't re-verify by cloning/worktree/raw store.db — the store IS the source of
  truth and it's already verified; `query_commits {:alignment}` proves handoff
  state in one read.
