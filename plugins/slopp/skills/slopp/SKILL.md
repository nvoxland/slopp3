---
name: slopp
description: "Work efficiently with a slopp codebase over MCP: form-addressed reads/writes with built-in verification, provenance, and a live REPL oracle. Read this before your first slopp tool call."
---

# Working with slopp

slopp is an agent-native codebase: code lives in a **store** (not files),
the unit of everything is the **top-level form**, and a **live JVM image**
runs your code continuously. Every write hot-reloads, re-runs exactly the
tests that exercise the touched forms, and records your stated intent.
Address code as `namespace` + `form name` — no files, paths, or line
numbers.

The system runs the mechanical series FOR you: a fresh clone of a
slopp-published repo imports itself; turns and identity are automatic (the
hooks handle them — never call `turn_begin` unless a write is refused);
every write is formatted, linted, compile-gated, and test-verified before
it lands; when your session pauses, a done-point pipeline tidies and
re-verifies what you touched. Setup/sync/shipping: the `slopp-setup`
skill. **Before writing non-trivial code or making a design call, read the
`slopp-style` skill** (functional-core shape, program-to-data, boundary
contracts, the conventions slopp enforces); reviewing slopp code: `slopp-review`.

## The loop — and the budget

A whole task should be ~10–20 tool calls: orient (1) → read (1) → write
REPL-style (small individual writes) → `done` → ONE milestone. `done` is
not once-per-session: call it at every point you believe a piece of work is
complete, before starting the next. Each extra
call re-reads your entire context; the patterns below are where sessions
measurably bleed tokens.

1. **Orient with ONE small call: `session_brief`.** Form names, recent
   milestones with their asks, git alignment, the loop — everything a
   fresh session needs to start working. Skip `query_project` unless you
   need arities/flags for a specific ns (`query_source {ns}` = the outline).
   `query_search {pattern}` to find things.
2. **Read only what the brief can't tell you — and prefer NOT reading.**
   About to edit a function? `query_slice {ns name}` (add `match`+`window` on giant forms — the neighborhood, not the whole thing) is THE read: full
   source of that one form + interface CARDS (sig, doc, why, test
   warranty) for everything it reaches. TRUST the cards — you don't need
   a callee's body to call it; if an assumption is wrong, the write turns
   red with `:implicated` (the covering tests re-run on every edit).
   Writes are OPTIMISTIC: compose `edit_subform` matches from the
   brief/slice; a missed or ambiguous match returns the form's CURRENT
   source in `:source-now` — correct from the error and resend. Batched
   named reads: `query_source {targets: [{ns name}…]}`; whole-namespace
   dumps are outline-by-default (`full: true` = the rare escape). Never
   re-read what you just wrote. In a LARGE codebase, delegate broad
   comprehension questions to the `slopp-reader` subagent — it returns
   conclusions; your context should hold decisions, not source.
   **For summaries/handoffs/audits: `report` is TERMINAL, not a starting
   point.** One read already carries `:intents` (the USER's verbatim asks,
   recorded per turn), `:milestones`, `:changes` with their recorded `:asks`,
   `:dead-ends`, the suite state, and `:code` — the follow-up that carries
   source. Narrow it with `report {contains "eco"}`; do NOT re-ask
   `query_history {contains …}` once per feature (measured: four such calls
   at ~6k each, re-deriving what one report already held). The code itself is
   `query_changes {from "start"}` — every form's `:was`/`:now` across the
   lifetime, `format: "text"` for line diffs — never `git diff`, and never
   raw `store.db`.
3. **Write with intent; trust the verification.** Every write takes a
   one-line `prompt`: ONE logical change per write, and say WHY — history
   quality is intent quality. The response carries the affected tests' result —
   `test_run` after an edit is redundant. Red results carry `:failures`
   inline plus `:implicated` (which of YOUR changes each failing test
   exercises) — start debugging there. **Your work is already verified —
   never re-run the suite externally, never clone/worktree the repo to
   double-check yourself.** When the user asks how to verify, GIVE them
   the commands (`slopp --call test_run`, `query_commits`) — don't
   execute a dry run.
4. **Work like a REPL — one small write at a time.** Mid-episode reds are
   normal TDD state: a spec naming a not-yet-written fn lands as an honest
   red (`:red-first` names the stubs); changing a signature lands with the
   stale callers riding `:carried-errors` — catch them up in your next
   writes. Nothing asks you to pre-plan groups. Red-first TDD: write the
   failing test FIRST, then implement. An `:untested` flag? `draft_test
   {ns name code}` drafts a deftest from OBSERVED calls.
5. **Say `done {label}` at EVERY point you think you're finished with
   something — not once at the end.** Finished a unit of work and about to
   start the next? That's a done point. Call it, read the findings, and
   find out whether you were actually done before you move on. Multiple
   `done`s per session is the normal shape, not an exception: each one is
   cheap, each marks a boundary you can revert to, and each catches a
   problem while the work is still fresh in your context rather than three
   tasks later. (A turn ending is merely one such moment — the hook fires
   it for you then.) It runs the
   WHOLE in-image suite plus the `^:external` tests your changes impact,
   normalizes, marks the episode boundary, and reports findings; address
   them. A pre-flight `test_run` is redundant. **`done` REPORTS, it does
   not refuse** — it records the boundary honestly and tells you "not done
   yet", so you are never deadlocked by a finding you cannot fix.
   `commit_point` is what refuses to PUBLISH a red done — and a red done
   STANDS until new work supersedes it, so you cannot clear it by
   committing without changing anything.
   **`done` is EPISODE-scoped**, and its `:scope` field says so every
   time: lint and dead-surface cover only the namespaces you touched, and
   the full `^:external`/`^:integration` tiers do not run. `full_check`
   answers the whole-store question — see below. If your session pauses
   first, the hook fires done for you and the findings greet the next
   session's brief.
   **Tier vocabulary** (namespaces AND tests): `:pure` (referentially
   transparent) · `:internal` (mutates in-process state only — a memo via
   `slopp.cache`) · `:external` (IO: files, subprocesses, network, db).
   `module_purity {module tier}` declares a namespace's; `^:external` marks a
   test that exercises one. The axis is what decides how a thing is TESTED —
   external needs a separate JVM and temp dirs, internal needs a cache reset,
   pure needs nothing.
6. **`full_check` when the episode's scope isn't the question.** Every
   namespace linted, dead surface store-wide, every test in every tier.
   NOTHING forces it — not `done`, not `commit_point`. Reach for it when a
   change was broad, when you DELETED A CALLER (dead surface appears in
   namespaces you never touched — the one thing episode scope structurally
   cannot see), or before a commit you want to stand behind.
7. **Close ONCE.** Exactly ONE `commit_point {description}` at the end
   (it runs `done` and gates on that verdict — it has no checks of its own)
   unless the user asks for more.

## Choosing the write tool

| Situation | Tool |
|---|---|
| New namespace (grow with TDD) | `ns_create {ns, requires}` — create dependency nses first |
| New namespace, source ready | `ns_create {ns, source}` — whole text, one verified call |
| Require add/remove | `ns_add_require` / `ns_remove_require` |
| New form | `edit_add_form` (`before` anchors placement) |
| Change a whole form | `edit_replace_form` |
| Small change INSIDE a big form | `edit_subform {ns form match source}` — match ONE subform or ONE pair; a missed match returns `:source-now` (correct + resend); `text: true` for strings/docstrings; `where: {key value}` addresses the unique MAP containing those entries (registry rows — no exact text needed) |
| Change a form's NAME METADATA (`^:export`, `^{:malli/schema …}`) | `edit_subform {text: true}` matching the `defn` head — `"^:live-handle open!"` → the new metadata. Structural matching can't address the head on its own, so without this you resend the whole form to change one marker |
| A subform edit refused with `unresolved-symbol`/`invalid-arity` | **Widen the match.** The change spans more of the form than you matched — a binding and its use, a loop and its `recur`, an arglist and its body. Match the enclosing form, or `edit_replace_form` the whole thing. **Two edits to ONE form is ONE edit**; this is NOT cross-form atomicity and there is no batch tool for it (the refusal says so too) |
| Change a fn's SIGNATURE | `change_signature {ns name source calls}` — new defn + `$1..$9` call-site template; never signature-change form-by-form |
| Several changes, one reason | just make the writes one at a time — episodes group them for you; interim reds/`:carried-errors` are normal until `done` |
| Rename ONE form | `edit_rename` (def + all references, shadow-safe); its result lists leftover prose `:mentions` |
| Rename a CONCEPT ("zone is now region") | `rename_sweep {from to}` — namespaces + vars + keywords + prose, store-wide, ONE call, one verification; never form-by-form. Whole-word only, so `region-ish` survives a `region` sweep. **`dry-run` first and check the count against what you expected** — a mismatch means your pattern is catching something else. Two gotchas: it rewrites prose DESCRIBING the rename (a comment explaining `a -> b` comes out saying `b -> b`), and if a live GATE enforces the thing you are renaming, you need two phases — teach the gate to accept BOTH spellings, sweep, then tighten. A gate runs from the old compiled code while the group rewrites it, so a one-shot sweep is refused at the first form it re-tags |
| Extract helper / move forms to another ns | `edit_extract` / `edit_move_forms` (new OR existing target; callers everywhere rewritten; `export: true` for a deep target with outside callers). **Propose the cluster you want and let it close the set for you** — it refuses a two-way split and NAMES the forms that would leave a cycle ("the moved set calls [x y] (staying)"). Add those and retry. Guessing the seam leaves a cycle; the refusal IS the analysis |
| Reorder / delete / undo | `edit_move` / `edit_delete_form` / `edit_revert` |
| Comments between forms | `edit_trivia` |
| Risky experiment | `branch_create` → work → `branch_switch` + `branch_merge` |
| Declare a module dependency | `module_dep {from to prompt}` — one edge, say why; `remove: true` retracts |
| Declare a namespace's purity tier | `module_purity {module tier prompt}` — `:pure` (referentially transparent) / `:internal` (mutates in-process only) / `:external` (IO). Namespace PATH, most-specific wins; declaring VERIFIES the code already there. Undeclared = `:external` = ungated |

**Red-first is native:** a spec in a `-test` ns may reference store fns
that don't exist yet — it lands as a REAL red (`:red-first` names the
missing vars, stubbed in-image as failing); implement them to go green.

**References never hide in strings:** in-process references in data use
`#'var` literals; late binding across a load cycle uses
`(store/late-ref 'ns/name)`; vars invoked from OUTSIDE (CLI, wire, eval
injection) declare `^:entry-point` on the name. These carriers are what
renames, moves, and the unused gate can see — a naked quoted symbol or a
var name in a string is invisible to all three.

**Dead surface fails the gate:** a public `defn`/`def` nothing in the
store calls is an ERROR at `done` and refuses milestones (globally).
Deliberate? Mark the NAME: `(defn ^:unused-ok f ...)` — external surface,
string-eval'd or runtime-resolved entries. The dial polices itself: a
marker on a var that IS called fails with "remove the flag". Fixture
namespaces in tests follow the same rule (and edits must KEEP the marker).

**Tiers are not your problem:** `done` runs the WHOLE in-image suite plus
the `^:external` tests your changes impact (in a separate JVM,
automatically; a large slice defers and rides findings as
`:external-pending`). `commit_point` has NO checks of its own — it runs
done and gates on that verdict. There is exactly ONE bar, and it is
`done`. The whole-store answer is `full_check`, and nothing forces it. **A write's `:status` says which tier actually ran**:
`:green` = the impacted tests ran and passed · `:partial` = some ran, but
impacted `^:external` ones were DEFERRED (`:external-pending` names them —
a green here would be earned by other tests) · `:unverified` = nothing ran,
with `:reason` distinguishing `:all-impacted-external` (by design, the
done point runs them) from `:no-covering-tests` (yours to fix) and
`:scope-ran-nothing` (a slopp bug — report it). Writing an `^:external`
test is `:partial` or `:unverified`, never green — **to see it go red-first
you must run `test_run {external true, only [...]}` yourself.** You never run `test_run` as a ritual — it's for
spot-checking one namespace or test mid-flight. Red runs return
`:all-failing {file [tests]}` and `:themes` (clustered causes) — read
those before drilling into blocks.

**A repro can be too minimal.** Red-first protects you only if the test is
red for the REASON you think. Stripping a bug down to its smallest case can
strip out the very thing that triggers it, and then a green test reads as
"not the cause" when it means "not reproduced". A real one from this
codebase: a crash in a `sort` was minimised to a single-element collection —
and sorting one element never calls `compare`, so the test passed over a
live bug and sent the diagnosis in the wrong direction. When a repro comes
back green, that is a RESULT to explain, not a fact to accept: check that
the mechanism is still present before concluding the cause is elsewhere.
Reach for `query_eval` to look at what the code actually sees rather than
bisecting features by intuition — measuring the analysis found this one
after four wrong guesses.

**Say less between calls.** Results are structured and self-describing —
never restate a result's contents in prose (eval9 measured: agents wrote
2× the commentary plain-file agents did, and it was ALL of the remaining
overhead — the tool traffic itself is cheaper than files). Between
calls: nothing, unless a decision changed. Final summary: short —
name what shipped and quote result keys (`:test`, `:done`,
`:findings`); don't re-describe what the tools already said.

**Every write must compile — AND must still cold-load.** Form ORDER is not
your job: write forms in any order — the pipeline moves definitions above
their callers, and inserts a marked `(declare …)` itself for genuine mutual
recursion.

The distinction worth carrying: your work hot-loads into a LIVE image where
the vars already exist, so the image happily runs code that a FRESH load
(boot, restart, a clone, the external test tier) cannot load at all. That is
what the cold-load gate is for, and it refuses two shapes — a form
referencing a later form in the same namespace, and a require CYCLE between
namespaces (`would not cold-load — require CYCLE: a -> b -> a`). Both are
invisible to in-image verification by construction, which is why a write can
be refused while every test passes. A cycle usually means a require that is
no longer referenced: drop it, or move the shared code somewhere both sides
can depend on. `edit_move_forms` drops the requires IT orphans on BOTH
sides — the source namespace whose last user of a lib just left, and a
rewritten caller left referencing nothing in the source namespace — but only
those: a require kept for its load side effects, like `defmethod`
registration, is indistinguishable from a dead one, so nothing prunes those
for you. A stale require is worse than untidy — a namespace inherits the
TIER of everything it requires, so one left behind makes a `:pure` namespace
report as depending on the shell for something it no longer uses at all.

**Moving a form re-resolves its `::auto-keywords`.** `::foo` is read as
`:current-namespace/foo`, so the same text means something DIFFERENT after
`edit_move_forms` — `::analysis` in `a.b` silently becomes `:a.b.c/analysis`
in its new home. Harmless for a local marker; a live bug when the keyword is
a persisted key, a map key another namespace reads, a `defmethod` dispatch
value, or a cache id. Write the keyword out in full when it has to survive
relocation, and check `::` in anything you move. Hand-written
`(declare …)` is refused; you never need one. Mutating fns end in `!` (rename
with the `:suggest` if warned); `^:reads` marks read-only dep calls;
`^:unsafe` is the dialect escape hatch.

**Modules are enforced.** A module is the first two ns segments
(`logi.parcel`; `x.y-test` belongs to `x.y`). Calling ACROSS modules
needs a declared edge — the refusal names the exact
`module_dep {from to}` call; DECLARE THEN USE (design the dependency,
then write the code). Deeper namespaces (`x.y.z`) are package-private
to `x.y.*`; the `:export` dial on a defn widens it — `^:export` hoists
it into the module's public surface, `^{:export "x.y.z"}` exposes it to
that subtree only. An edge that closes a cycle is refused (the cycle is
named). Read the whole architecture in one call: `query_depends {modules
true}` — manifest, topological :layers, :cycles, :unused-edges (dead
declarations), standing debt; browse what a module OFFERS (public fns +
exports, deps, consumers) before calling into it: `query_depends
{modules true, on "x.y"}`. Public-surface fns warn once when a
write leaves them undocumented — add the docstring.

**Cohesion decides WHERE code lives; the export dial decides WHO sees it —
they are independent.** Put forms that serve one concern in one namespace (a
deep `x.y.z` for a cluster inside a module); if one has legitimate outside
callers, mark it `^:export` and move on. Never park a form in a grab-bag
namespace — or drag unrelated forms along with it — just to dodge an export
marker: the marker is cheap, a god-namespace is not. Conversely, `^:export`
ASSERTS "this is public surface", so it is not a substitute for putting a form
where it belongs. `edit_move_forms` relocates a cluster in one verified
intent (callers everywhere rewritten, requires added, `export: true` for a
deep target with outside callers).

## Result keys

Green and quiet compresses to `{:ok true :delta "d42" :test {:ran 2 :pass 5
:status :green :scope :affected} :affected 2}`. `:status` is EXPLICIT — never
infer red/green from a result's SHAPE. `verbose: true` on any write returns the
full map.

- `:scope` — `:affected` (only the tests exercising your change ran) vs `:all`.
  `:affected` — how many re-ran; `:all` = no trace map yet.
- `:status :partial` — impacted `^:external` tests were DEFERRED, and
  `:external-pending` names them. `:unverified` — nothing ran; `:reason` says
  which kind, and a zero-test run also carries `:coverage :none`.
- `:failures` — expected/actual/exception per failure. Diagnose from the
  response; a follow-up `test_run` re-derives what you already have.
  `:implicated` — which of YOUR changes each failing test exercises.
- `:red-first` — the not-yet-written vars a new spec named (stubbed to fail
  honestly). `:carried-errors` — stale callers a signature change left behind.
  `:still-red` / `:went-green` — which reds persisted, which cleared.
- `:staleness-healed true` — the red was image staleness, already healed.
  `:image-healed true` — the image was rebuilt under you. `:fresh-confirmed
  true` (red path) — the red survived a fresh image, so it is real.
- `:untested true` — nothing exercises the form you changed; `draft_test`.
- `:warnings` — `!`-naming violations YOU introduced; fix with `edit_rename`
  per the `:suggest`. `:existing-warnings n` counts older ones.
- `:drift` — a finding surfaced on the WRITE precisely so you see it before
  calling `done`.
- `:manual` (change_signature) — references it could NOT rewrite (higher-order
  uses); handle those with `edit_subform`.
- `:dry-run` (rename_sweep) — `:in-code` / `:in-strings`, nothing written.
- `:conflicts` (merge) — ours kept, theirs surfaced; the payload IS current
  live source, so resolve straight from it.
- `:source-now` — your match missed or was ambiguous. Correct from it and
  resend; no read needed.
- `:hint` — a one-line workflow nudge, at most once per session.

## Effects, deps, escape hatches

- `deps_add` a library, then require it normally (hot classpath add, no
  restart). `deps.edn` is GENERATED — never hand-edit it.
- Calls into an opaque dep count as EFFECTFUL: name the caller `!`, or
  `deps_pure` the var/namespace/lib, or tag the form `^:reads` (reads take no
  bang).
- `^:unsafe` opts ONE form out of the dialect gate — the greppable last resort.
  It does not silence `!`-naming. The honest case is analysis code that NAMES a
  banned symbol as data; comparing head names as strings avoids the marker
  entirely.
- **Every memo goes through `slopp.cache`.** That is what keeps `:internal`
  checkable — an ad-hoc atom is indistinguishable from arbitrary mutation.
  `without-caching!` bypasses for a test; `reset-all!` clears every cache.
- `build {dir}` materializes plain files (absolute path, outside the repo);
  with `main` it also emits a GraalVM native-image recipe. Repo sync, uberjars,
  config files, CI: the `slopp-setup` skill.

## Questions → the oracle

Run code instead of reading callers: `query_call {sym "my.ns/f", args [X]}`
(the reference is CARRIED — renames/moves/the unused gate see it; args are
printable data) · `query_eval "(...)"` for arbitrary expressions (read-only
REPL, image pre-loaded — questions OF the code) · `query_store
"(fn [store] ...)"` (read-only analysis over the immutable store VALUE —
questions ABOUT the codebase: counts, metadata sweeps, custom aggregation
no canned query covers; fully-qualify, no effects/interop)
· `query_observe` (capture args/returns at runtime)
· `query_depends {on X, direction?}` — THE dependency question, any
kind: a namespace, a var (`ns/name`), or a `:keyword`; `:dependents`
(default) = who uses X (callers, refs, field flow, affected tests);
`:dependencies` = what X reaches. On a var taking or passed a MAP it also
returns `:shape` — the keys the form READS (destructured, body, `:=>`
schema, `:or`-optional) against the literal keys its callers PASS, grouped
by key-set, with the diff in `:mismatch`. **Renaming a key, or asking who
supplies one, is this read — never a grep.** `:unknown-shape` names the
callers passing a non-literal: trust `:mismatch` only as far as that list
is empty · `query_brief` (the edit dossier) ·
`query_macroexpand`. Re-reads are FREE: an unchanged view returns a tiny
`:unchanged` stub — re-fetch instead of carrying source in context.
History is ONE door:
`query_history` routes by args ({} episodes · {ns name} a form's life ·
{ns name at} time-travel · {at} was-green-at · {contains} which asks
touched X · {dead_ends true} the SCRAPPED explorations, {dead_ends "some.ns"}
those that touched it); `report {since, contains}` for summaries and handoffs.

**When you hit a dead end, revert cleanly and say WHY.** `undo` walks back
your OWN writes by delta — `{deltas n}` for the last n, or `{to :last-commit}`
to scrap everything since the last milestone (the usual "this whole approach
was wrong" move) / `{to :last-done}` to your last done. Always pass a
`prompt` naming *why* you're abandoning it: that records the revert as a
searchable **dead-end**, so a later session (or you) running
`query_history {dead_ends "some.ns"}` finds "someone tried X here and dropped
it because Y" instead of re-walking it. `episode_revert` scraps the whole
episode. Reverting before a `commit_point` leaves the milestone history clean
— the dead end shows in `dead_ends`, not in the commit log.

## Tool index

session_brief report query_slice query_depends · turn_begin turn_end ·
query_project query_search query_source query_brief query_history
query_changes query_eval query_store query_observe query_call query_vocabulary
query_rules query_rule_telemetry query_macroexpand query_branches query_commits
query_git query_detail review_scan · ns_create
ns_add_require ns_remove_require ns_rename · edit_add_form
edit_replace_form edit_delete_form edit_subform edit_trivia
edit_rename change_signature rename_sweep edit_requalify edit_extract
edit_move_forms edit_move edit_revert undo episode_revert cleanup ·
branch_create branch_switch
branch_merge branch_delete merge_from · deps_add deps_remove deps_list
deps_pure · module_dep module_purity · file_put file_remove file_list file_get
file_history · config config_file · git_push git_clone git_pull git_conflicts git_resolve ·
test_run draft_test done full_check commit_point restart build help
