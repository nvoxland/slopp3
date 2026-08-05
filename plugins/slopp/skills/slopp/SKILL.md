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
   them. A finding marked `:severity :info` is deliberately
   INFORMATIONAL — reported for you to eyeball, never status-flipping —
   so a rule can carry both a hard failure and an observation only you
   can judge. It is not a lesser failure; there is nothing to discharge.
   A pre-flight `test_run` is redundant. **`done` REPORTS, it does
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
   **`:host-stale` means DOUBT THE VERDICT.** `done` and `full_check` carry
   it when the process that produced the result is knowingly running code the
   store has moved past — a hot-reload that failed, or a `--snapshot` host
   with code deltas since it booted. The tests may have passed against the
   wrong code. Restart the server (or fix the reload failure named in
   `:failed`) and re-run before believing a green. It is absent unless there
   is something to doubt, so when you see it, act on it.
   **`:host :jar` says which ARTIFACT is answering** — `{:head <delta>
   :behind N}`, the store head the running jar was BUILT from and how many
   code deltas have landed since. `:host` otherwise reports the process's
   MODE, never its code, which leaves "am I running the slopp that has the
   fix?" answerable only by unzipping a file. A `:head` with **no `:behind`**
   is not zero — it means the jar came from a DIFFERENT store than the one you
   are reading (slopp's own jar serves other projects), so the count would be
   meaningless and the identity is what to compare. The whole section is absent
   when the process cannot say: a checkout, a `clojure -M` run.
   **`restart` does not change the jar.** It rebuilds the images INSIDE this
   JVM, which is the remedy for `:host-stale` and for verification drift, and
   is no remedy at all for a stale artifact — a running JVM holds the classes
   it loaded at boot. A jar `:behind` by hundreds needs a rebuild and a
   PROCESS restart, and the tool named `restart` will look like it should work.
   **`:ms` says what verification cost.** Every write's `:test`, every `done`,
   every `full_check` and every external run reports its own wall time, and
   the number persists on the delta — so "where is my time going?" is a query
   over the journal, not a guess. Per-write verification is normally free
   (median 0ms on a 170-namespace store); if yours is not, that is the signal.
   **And each TURN records where its whole wall clock went**: `turn_end`'s
   delta carries `:timing {:slopp-ms :outside-ms :idle-ms :elapsed-ms :top
   :refused}`. One turn is one USER ASK — a new ask closes the open turn and
   opens its own. `:outside-ms` is time slopp was NOT working — your reasoning
   plus every non-slopp tool — and it is usually the majority (measured on
   slopp's own development: 78%). Use it before optimizing a tool: if
   `:slopp-ms` is a fifth of the turn, a faster tool is not what you are
   missing. `:idle-ms` is separate and is the session nobody was in: turns
   rotate on the write-tool gate, so one can straddle a human going away, and
   `:slopp-share` is taken against ACTIVE time so a pause never reads as slopp
   being slow.
   **`:refused` is the one to act on.** It counts the calls that bounced — a
   malformed `edit_subform` match, a lint error in the form you were writing,
   an arity break — as a rate with the tools named, plus `:samples`, the
   verbatim messages the bounced calls answered with. Each is a whole round
   trip that produced nothing, and they land in the half of the clock nothing
   else measures. Read the samples before changing anything: a high rate on one
   tool is a prompt to read its contract, not to retry harder.
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
   **It is NOT a superset of `done`, and the axis it loses on is
   ISOLATION.** `done` puts every impacted `^:external` test in ONE serial
   JVM; `full_check` shards the suite across four. So two tests that only
   fail TOGETHER — through a recycled image, a temp dir, any process-global
   — fail under the narrower check and can pass under the broader one. **A
   red `done` beside a green `full_check` is not `done` being wrong**; it is
   the pair being co-scheduled in one place and split in the other, and the
   reproduction is `test_run {external true affected true parallel 1}`.
   Read the two as differing in COVERAGE and there is no other conclusion
   available than "done is flaky", which is how a real interference gets
   waved through.
   **`:rules` is the rule catalog asked about the WHOLE store, and it is the
   only thing that ever asks.** A `:grain :done` rule fires over the forms an
   episode CHANGED, so a violation older than the rule is invisible to `done`
   — and stays invisible, because no later episode changes that form either.
   Every episode is honestly clean and the store is not. This is why a store
   that has never once seen a rule finding is not evidence the rules are
   holding: **turning a rule on does not check the code already there.** Run
   `full_check` after adding or dialing up a rule, or you have gated the
   future and left the past.
   `:error`-grade findings flip the status; `:advisory` ones are reported and
   do not — the same bar `done` grades on, so a rule means one thing in both
   places. `:swept` and `:not-swept` name the rules it ran and the ones it
   could not: about a third of the registry compares against the episode's
   BASELINE (`key-typos`, `breaking-changes`, `assertions-never-red` …), and
   running one of those over every form reports nothing in the same shape as
   clean, so they say so instead of quietly padding the green.
   **`:crossings` is the one section a green does NOT cover.** Everything else
   full_check reports is an edge inside the store; this names the exits — a
   contract becoming JSON, form metadata becoming a route table, `.cljc` going
   to the ClojureScript compiler — that nothing checks. It is ADVISORY and
   never flips the status, because these are standing holes rather than
   regressions. It is there so a whole-store green is not read as ruling out
   what it never examined; if your change crossed one of those edges, that
   edge is yours to test.
   **`:app {:behind n}` is the browser's view, not the store's.** If slopp
   runs an app server for this project, this says how many code changes the
   SERVED image is behind the store you just called green — it is rebuilt at
   DONE grain, so between done points a browser is looking at an older store.
   `0` is reported too, so the current case is an answer rather than a gap.
   Any `n > 0` means the page you are about to screenshot is not the code you
   just wrote, and a partly-updated page renders BROKEN rather than merely
   old. `done` re-serves.
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
| Put existing code INSIDE something new (a `let`, a `when`, a `try`) | `edit_subform {match <a COMPLETE form>, wrap: true, source "(let [n 1] $1)"}` — `$1` is the matched form, so what was there NESTS inside your template. Without it the only expressible edit is restating the whole enclosing form, since a match that opens a delimiter it doesn't close is refused. Same `$n` templating `change_signature` uses; a template with no `$1` is refused rather than deleting the match |
| Change a form's NAME METADATA (`^:export`, `^{:malli/schema …}`) | `edit_subform {text: true}` matching the `defn` head — `"^:live-handle open!"` → the new metadata. Structural matching can't address the head on its own, so without this you resend the whole form to change one marker |
| Edit a form that has NO NAME (`defmethod`, `use-fixtures`) | `edit_subform {form "<form-id>"}` — **the id addresses a form wherever the name does**, and some forms genuinely have no name: a `defmethod` has a dispatch value, `use-fixtures` defines nothing. Ids come from `query_search` hits (`:form` is the id when there is no name) and from any finding that names one. `edit_replace_form` takes a name, so the id route is `edit_subform` |
| A write refused with `No such namespace: x` | **The refusal names the `ns_add_require` to make.** Run it, then resend the form UNCHANGED — it was correct. slopp resolves the alias against the store (a namespace whose last segment is the alias) and the common `clojure.*` ones; an alias nothing can supply gets no suggestion rather than a wrong one |
| A write refusal says `ALSO PENDING:` | **Fix every line before resending.** A refusal carries EVERY gate the candidate tripped, not just the first — they were all knowable from the same form, so satisfying them one per round-trip is pure waste |
| A subform edit refused with `unresolved-symbol`/`invalid-arity` | **Widen the match.** The change spans more of the form than you matched — a binding and its use, a loop and its `recur`, an arglist and its body. Match the enclosing form, or `edit_replace_form` the whole thing. **Two edits to ONE form is ONE edit**; this is NOT cross-form atomicity and there is no batch tool for it (the refusal says so too) |
| Change a fn's SIGNATURE | `change_signature {ns name source calls}` — new defn + `$1..$9` call-site template; never signature-change form-by-form |
| Several changes, one reason | just make the writes one at a time — episodes group them for you; interim reds/`:carried-errors` are normal until `done` |
| Rename ONE form | `edit_rename` (def + all references, shadow-safe); its result lists leftover prose `:mentions` |
| Rename a namespace ALIAS (`[a.b :as old]` -> `:as new`) | `ns_realias {ns old new}` — the `:as` in the ns form AND every `old/sym` in that namespace's bodies, one verified write. **There is no hand route**, and that is why this is a tool rather than two edits: between the two writes the ns form and the bodies disagree about the qualifier and the namespace does not load, so the alternative is the add-both / migrate / drop dance. **Reach for it right after `ns_rename`**, which rewrites namespaces and walks straight past the `:as` — the moved code keeps being called by its old module's name, and the day that name gets REUSED the alias starts pointing at a real, different module, which is worse than one naming nothing. You do not have to spot them: the rename lists them under `:left-behind :alias`, each with the `:suggest` to pass here. Scoped to one namespace by design: an alias is a name ONE namespace chose, so two namespaces calling a lib different things is not drift and there is no store-wide version. A BARE `old` is left alone — only `old/x` is the qualifier, and the same spelling is routinely a local or a parameter three tokens away. Read `:sites` (0 means the alias was unused, not that nothing happened) and `:left-behind` |
| Rename a CONCEPT ("zone is now region") | `rename_sweep {from to}` — namespaces + vars + keywords + prose, store-wide, ONE call, one verification; never form-by-form. Whole-word only, so `region-ish` survives a `region` sweep. **`dry-run` first and check the count against what you expected** — a mismatch means your pattern is catching something else. Two gotchas: it rewrites prose DESCRIBING the rename (a comment explaining `a -> b` comes out saying `b -> b`), and if a live GATE enforces the thing you are renaming, you need two phases — teach the gate to accept BOTH spellings, sweep, then tighten. A gate runs from the old compiled code while the group rewrites it, so a one-shot sweep is refused at the first form it re-tags. **Pick the most QUALIFIED name that still covers the live references** — a broad name reaches backwards into HISTORY (incident records and frozen fixtures naming what a thing really was called; sweeping those forward invents a past) while a narrow one cannot, and it also misses the unqualified TAIL (`slopp.a.b` as a segment does not match prose writing `b/thing`), so sweep that separately and check user-facing strings — teach strings and error text — for it. If the qualified form leaves a real reference uncovered, that reference wanted naming precisely anyway |
| Rename a QUALIFIED KEYWORD (`:a/x` -> `:b/x`) | `rename_sweep` — it moves the literals AND the `{:a/keys [x]}` destructuring, which names the key as a SYMBOL with the qualifier one position to the left and so is invisible to a text pass. The entry is matched on the FROM qualifier and only on it, so an unqualified `{:keys [x]}` — which names `:x` and has nothing to do with your rename — is left alone. **Read `:requalified` and `:left-behind`; absence of either means checked-and-none.** `:requalified` is the half of the diff that is not a text substitution, and worth an eye for that reason alone. `:left-behind` is the half the tool DECLINED: changing the key's NAME (`:a/x` -> `:a/y`) rather than its qualifier cannot be applied to a destructuring, because the symbol is a LOCAL BINDING the body reads — so sweep the qualifier and rename the name as two steps, or finish the named forms by hand. A stranded destructuring presents as nil arriving silently rather than as an error, so the only tests that can catch one are the ones exercising the value END-TO-END — which for a session, a projection or a subprocess means `^:external`, and those are exactly the ones a write DEFERS. Do not read the write's green as coverage here |
| Rename a CONFIG KEY family (`a.b.*` -> `x.a.b.*`) | **Not `rename_sweep`** — a dotted key is a STRING, and the sweep's whole-word/segment matching is wrong for it in both directions: a segment of the key is usually also a segment of a NAMESPACE and of keys inside the config's own VALUES, so it rewrites things that are not the key, while missing the places the key really lives. Do it by hand and go looking for the three hiding places, none of which a text pass reports: **regex literals** (`#"a\\.b\\..+"` — the sweep silently declines these), **length constants** (`(subs k 19)` standing in for `(count "<the prefix>")` — take the tail from the prefix you matched, so the two cannot disagree), and **a second branch of the same `cond`** a few lines below the one you just fixed. Then `config_file {path "vocabulary" key <old> value <new>}` so the retired spelling is declared. Grep to check yourself with a pattern you did NOT use while editing — a verification grep written from the same assumption as the edit shares its blind spot — and if the new name CONTAINS the old one, anchor the search at a segment boundary or every corrected line reads as a violation |
| Extract helper / move forms to another ns | `edit_extract` / `edit_move_forms` (new OR existing target; callers everywhere rewritten; `export: true` for a deep target with outside callers). **Propose the cluster you want and let it close the set for you** — it refuses a two-way split and NAMES the forms that would leave a cycle ("the moved set calls [x y] (staying)"). Add those and retry. Guessing the seam leaves a cycle; the refusal IS the analysis. `export: true` WIDENS per var — a var already `^:export` keeps its level without the flag, so you never pass it just to restate something already true, and passing it does not silently widen the rest. Read `:export-not-landed` on the result: the move checks its own POSTCONDITION against the committed store and names the VAR, so a planned export the store did not actually get is reported rather than discovered later |
| Regroup whole namespaces under one prefix | `module_extract {namespaces to}` — the MODULE-grain move, for a namespace that grew into its own component or a set that wants one owning prefix. Each named ns takes its subtree and `-test` sibling. **`dry-run` first, always**: going from two segments to three makes a namespace package-private, so every outside caller breaks at once, and the plan is the only place you see WHICH vars must be hoisted and WHICH CALLERS force each. The write order is the design — hoist (`^:export`), then rename, then declare the edges the moved store actually references — so no intermediate state is one the gate would refuse. Refuses a regroup that would leave a production cycle; a `-test` back-edge is not one |
| Reorder / delete / undo | `edit_move` / `edit_delete_form` / `edit_revert`. **A delete whose form still has a caller is REFUSED**, naming every caller — the same stance `ns_delete` takes for a namespace something still requires. Only `:static` references count (a quoted symbol or a `^{:covers}` marker names a form without needing it), and a recursive function is not its own caller. To remove a caller and its callee together, delete in REVERSE DEPENDENCY ORDER — callers first, callee last, one call each; every step verifies and every intermediate state loads. Two forms that call EACH OTHER have no valid order: `edit_replace_form` one to drop the call, then delete both. `query_depends {on "ns/name"}` still answers the question BEFORE you write, and is worth asking when you are planning a removal rather than discovering its size from a refusal. Recovery for any write is `undo {deltas 1}` — but `undo` walks back only YOUR OWN writes, so a delete made under a different agent (a `--call` script, another session) answers `no writes of yours to undo` while looking straight at it; that case needs `episode_revert` |
| Comment on a form | `edit_comment {ns name text}` — the block rendered above it. A comment BELONGS to a form; there is no such thing as a comment between forms |
| Risky experiment | `branch_create` → work → `branch_switch` + `branch_merge` |
| Declare a module dependency | `module_dep {from to prompt}` — one edge, say why; `remove: true` retracts |
| Retire a declaration | `remove: true` on `module_dep`, `module_purity`, `module_platform`, `module_role` — all four. Retiring is not the same as declaring the permissive value: `:external`/`:jvm`/`:product` is a CLAIM, absence is no claim |
| Declare a namespace's purity tier | `module_purity {module tier prompt}` — `:pure` (referentially transparent) / `:internal` (mutates in-process only) / `:external` (IO). Namespace PATH, most-specific wins; declaring verifies the FORMS already there. Undeclared = `:external` = ungated |
| Keep a benchmark / script / one-off CLI out of what ships | `module_role {module "instrument" prompt}` — for code a HUMAN runs by hand, as opposed to `:product` (the default: the system runs it). It is not a label, it MOVES the code: an instrument materializes under `instruments/` rather than `src/`, so any build that jars `src` excludes it **without knowing what a role is**, and it drops out of the architecture view so a harness cannot sit on top of what it measures. Namespace PATH, most-specific wins, so one call covers a subtree. **Declaring is REFUSED while product code requires the module**, naming the callers — otherwise the jar carries a require to a namespace it does not contain and you find out at a consumer's load time. A `-test` requirer is fine: a test does not ship either |

**A declaration tells you which axes it checked.** Every register write —
`module_purity`, `module_platform`, `module_role`, `module_dep`, `config_file` — returns
`:verified` and `:unverified` axis lists plus a `:note` saying where an
unchecked axis IS judged. **Read `:unverified`, because a clean declaration is
not a clean bill of health:**

- `module_purity` checks the forms; it does NOT check layering (does this
  namespace require a LOOSER tier?). That is a whole-graph property and only
  `full_check` reports it — so a tier can be accepted and stand for many writes
  before anything contradicts it. Declaring `:external` verifies nothing at
  all, because `:external` asserts nothing.
- `module_platform` verifies NOTHING about the code; `compile_client` is what
  proves a `:cljc`/`:cljs` namespace actually compiles.
- `module_role` checks the one thing that would BREAK — that no product
  namespace requires an `:instrument`. What it cannot check is the claim
  itself: that a human rather than the system runs this code. Nothing in the
  store distinguishes a benchmark from a scheduled job, so `:instrument` is
  the one register value that is purely your assertion.
- `module_dep` checks cycles over PRODUCTION edges; whether anything uses the
  edge is `query_depends {modules true}`'s `:unused-edges`, and whether only
  TESTS use it is the same call's `:overstated-edges` — a production edge no
  production code crosses. Fix those when you see them: the cycle check reads
  declared edges, so an overstated one can refuse a legitimate declaration in
  a module that has nothing to do with it. `test_only: true`
  declares an edge for the module's `-test` namespaces alone — production
  under that module is still refused, and a test-only edge is not a production
  edge so it is never a cycle. **Reach for it when a fixture must drive a
  surface that calls back into its own module** — a done-time advisory can
  only be tested by writing code and calling `done`, so the fixture
  necessarily calls the operation surface. The cycle refusal offers this
  itself when every namespace crossing is a test.

**Declare a tier BEFORE you move a namespace, or its destination declares it
for you.** `ns_rename` carries an EXPLICIT tier along with the namespace — the
declaration is re-keyed to the new name and nothing changes. It cannot carry
one that was never made. An undeclared namespace is `:external` only because
nothing more specific claims it, so moving it under a prefix that DOES claim
something silently re-tiers it: move an undeclared IO namespace into a module
declared `:pure` and it inherits `:pure`, which is a tightening nobody wrote
and nobody reviewed. The next `done` names it (`:tier-governance`), but as a
finding to clean up rather than a decision you made. One `module_purity` call
before the rename costs nothing and makes the tier survive the move by being
stated rather than inherited.

`edit_move_forms` into a NEW namespace does carry the tier for you — the
target is seeded from the source's, since the forms satisfied it one delta
ago — but only when the SOURCE declared one, and only when the target would
otherwise be governed differently. So the rule is the same rule: an undeclared
source has nothing to carry, and its forms land in whatever the new name
inherits. Declare the tier and the relocation verbs both keep it.

**A relocation is the one path around every write gate — so expect the next
`done` to have opinions.** Both the purity tiers and the module rules are
inherited from a namespace's NAME and enforced when a form is WRITTEN; a
rename or a move changes the name without writing the forms, and `ns_rename`
rewrites its own callers, which then never pass a gate either. `done` closes
that hole from both sides — `:tier-governance` for a namespace whose new
prefix it cannot satisfy, `:module-governance` for a call a relocation put
outside a module rule — and `full_check` reports whatever stands store-wide
(`:tier-layering`, `:module-violations`). Both are error-grade: they are what
a write gate would have refused outright.

**On the module side the namespace that MOVED is usually not the one
reported.** Taking a target from two segments to three makes it
package-private to its parent subtree, so it is the CALLER — which did not
move — that is suddenly reaching in. Fix it at the target (`^:export` on the
defn name hoists it into the module's surface; `^{:export "prefix"}` exposes
it to one subtree) or at the call. `module_extract` does this hoisting for you
and reports which caller forces each export; `ns_rename` does not.

**And a scoped `^{:export "prefix"}` breaks from the OTHER end.** The string
names the CALLER's subtree, so relocating the caller invalidates an export in
a namespace nothing touched — often in a module you are not working on. Same
blind spot, mirrored: re-point the string, or widen it to plain `^:export` if
the var really is module surface. Moving the CALLEE breaks it too, and more
quietly: a scoped export names exactly ONE subtree, so a var reached by two
callers that used to share a module needs plain `^:export` the moment the
regroup separates them.

**A cross-module rename creates architecture debt NO GATE CAN SEE — so read
the `:module-debt` it hands back.** `ns_rename` rewrites every caller's require
and qualified refs through a path that runs no write gates at all. A crossing
that would be refused outright if you typed it is therefore created without a
murmur: nothing turns red, and the first thing to mention it is a `done` some
time later, reported against the unmoved CALLER — which never moved and does
not name the rename. So the rename reports it itself:

- **`:edges-needed`** — grouped to the `module_dep` calls you have to make, not
  to the hundreds of call sites the rule speaks in. Two things the count gets
  wrong by hand: every CALLER's module needs an edge, not just the module you
  are moving; and `:test-only` is read off who ACTUALLY crosses, so a crossing
  only `-test` namespaces make comes back `test_only true` even if the old edge
  was declared production. Declaring the production version there would
  overstate the architecture — the same judgement `:overstated-edges` makes
  after the fact, offered before it instead.
- **`:visibility`** — calls that now reach a package-private namespace, because
  going from two segments to three makes one. Each row's `:error` names the
  options.
- **`:cycles`** — the edges `module_dep` is about to REFUSE. This is the one
  worth reacting to immediately: it means the regroup as drawn cannot be
  declared, and finding out at rename ten instead of rename one is the
  difference between a rethink and an unpick.

The loop is rename → read `:module-debt` → `module_dep` what it names → next
rename. Do not carry the debt across several renames: the reports stay
correct, but you lose which rename caused what. If `:cycles` fires, stop and
`undo` rather than declaring around it.
- `config_file` validates only the `capabilities` path (against the capability
  registry). Every other path — `rules`, `gates`, `client` — is recorded as
  given, key and value unchecked.

**A rename tells you what it did NOT rewrite.** `ns_rename` rewrites every
SYMBOL — including quoted ones inside data literals — and deliberately leaves
strings alone, because a namespace name inside a string might be prose, a
path, or a generated program. It now returns `:left-behind`, grouped by how
each occurrence was found, plus a `:note`. **Read it.** The dangerous rows are
TOKEN strings (`:prose false`): a path, a `:main-opts` namespace, a `(require
'ns)` inside a program string — those BREAK, where a docstring mention merely
reads wrong. `:test-sibling` means the `-test` namespace still carries the old
name, which files its tests under the old module. Absence of `:left-behind`
means checked-and-none, not unchecked.

**Qualified KEYWORDS come back under `:keyword`, and they are the silent
class.** `:acme.billing/customer-id` survives `acme.billing` → `acme.invoice`
intact, because a keyword is not a reference — nothing breaks, no test turns
red, and the name simply starts lying. A broken token string at least turns
something red; this one has no second chance, which is why it is listed with
the keyword text spelled out. Whether to rewrite each is a JUDGEMENT, and that
is exactly why the rename does not: a qualified keyword can be a wire or
storage key that something outside your store already holds, and re-spelling
it there breaks a consumer slopp cannot see.

**Stranded ALIASES come back under `:alias`, and each row carries the fix.** A
rename rewrites the lib symbol in every caller's require clause and never the
`:as` beside it, so `[acme.billing :as billing]` becomes `[acme.invoice :as
billing]` — syntactically perfect, and every call site in that namespace goes on
reading `billing/total` for a namespace called invoice. Harmless while the old
name means nothing; the day it is REUSED for something else, the alias points at
a real and different module, which reads identically and is the worse failure.
`:suggest` is the alias to hand `ns_realias` — omitted where that caller already
spells another lib that way, because then the realias would be refused.

An alias that reads correctly for BOTH names is not reported: a namespace moving
between modules under the same last segment (`acme.api.query` →
`acme.read.query`, aliased `query`) strands nothing, and that is the ordinary
rename. What the report cannot see is an ABBREVIATION — `caps` for
`…capabilities` is derived from nothing readable, so a rename that makes it
wrong makes it wrong silently. Prefer aliases spelled from the namespace.

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

**Every escape marker takes a WHY, and should carry one.**
`^{:unused-ok "library surface for external consumers"}` discharges exactly
as the bare `^:unused-ok` does — a string is as truthy as `true` — and the
dial stops being a mute flag. Same for `^:entry-point` (invoked by WHAT?),
`^:ambient-ok`, `^:breaking-ok`, `^:foreign-keys`, `^:legacy-ok`,
`^:side-effect`. A bare one on a form you touched draws the `marker-why`
advisory. The exception is `^{:export "x.y.z"}`, whose string already means
the subtree it widens to. **A marker slopp does not know waives nothing while
reading exactly as though it does** — `^:unusedok` is not `^:unused-ok`, and
nothing fails; `store_doctor` is what finds those.

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
test is `:partial` or `:unverified`, never green — **to see it go red-first,
`test_run {only ["ns/the-test"]}`: a named `^:external` target runs in its
own tier automatically (one serial fresh JVM).** You never run `test_run`
as a ritual — it's for spot-checking one namespace or test mid-flight. Red runs return
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

**Every assertion must be observed failing at least once.** The load-bearing
part of red-first is not "test before code" — it is that each `is` was seen
red before it was trusted green. ADDING an assertion to an already-green test
skips that, and nothing downstream notices: `(is (empty? (:unused r)))` where
`full-check!` never returns `:unused` is `(empty? nil)` → passes no matter what
the code does. A green you never watched fail proves nothing. When you extend a
passing test, break the subject once and confirm the NEW assertions go red — or
you have written coverage theatre that reads as verification. **`done` asks
about this now** (`assertions-never-red`): a test that gained assertions and
never bounced this episode comes back as an advisory, because a rule that
relies on you remembering is not a rule.

**A filter used as evidence needs a positive control.** The sibling of the
above, and the cheapest habit on this page: before believing a filter found
nothing, assert its POPULATION was non-empty. Two empty sets compare equal, and
a scan that silently found nothing passes every comparison you make against it.

```clj
(is (seq found)                          ; ← the positive control
    "no namespace declares an endpoint — the scan found nothing, which
     would make the comparison below pass by being empty on both sides")
(is (= expected (set (map :ns found))))
```

**The population starts at the FIXTURE, and that is where this gets missed.** A
setup step that failed builds an empty population, and an empty population
satisfies every absence assertion below it — so the test is green, cheap, and
about nothing. `ingest!` and every other write verb RETURN `{:error …}` rather
than throwing, and a fixture is where a return value is least likely to be read.
The specific trap in a store: `ingest!` runs the module gate, so a second
namespace one module over from the first is refused, and the two-namespace
fixture you thought you built is one namespace.

```clj
(api/ingest! sess 'acme.core.thing  "…")
(api/ingest! sess 'acme.core.caller "…")   ; same module — or this is refused
(let [r (api/ns-rename! sess 'acme.core.thing 'acme.moved.thing …)]
  (is (= 2 (:forms (:renamed r))))         ; ← the fixture's own control
  (is (nil? (:alias (:left-behind r)))))   ; ← meaningless without the line above
```

Assert the fixture, not your intention for it: something that counts what the
setup actually produced, before anything is read off it.

**Read the MESSAGE, not the colour.** A red test discharges red-first only if
the failure is the one you set out to reproduce. A fixture broken in some other
way can fail the same assertion, with the same count, and the difference is
visible only in the text — one bug reproduced by hand went red on the right
assertion for a completely unrelated reason (a var the fixture never defined,
throwing inside an ARGUMENT before the code under test was reached). Aim a fix
at that and it lands green over an untouched defect. This is the same trap as
the fixture control above with the sign flipped: there a broken fixture
satisfied an ABSENCE assertion, here it satisfied a PRESENCE one.

**And put the population count in every ad-hoc `query_store` too.** A scan
returning `{:offenders []}` is indistinguishable from a scan that read nothing
— a guessed arity, a filter that matched no namespace, a key that is spelled
differently than you remember. Return `{:namespaces-scanned n :forms-scanned n
:offenders []}` and the empty answer becomes evidence. Compare the count
against something you already know (`full_check` reports the store's form
count) and it becomes proof.

**And if you are building the FILTER, probe it both ways.** A detector needs an
input it must flag and an input it must NOT — verify only that it can fire and
every false-positive mode goes untested. A guard shipped here with a can-fire
probe and no can-stay-silent one; the first rename it met was one whose new
name contained the old (`a.b.` → `x.a.b.`), the match was an unanchored
substring, and it reported every freshly-corrected line as a violation. A check
that flags everything is exactly as uninformative as one that flags nothing,
and it teaches its reader to stop looking.

**And a control on the POPULATION says nothing about your PATTERN.** These are
different claims and it is easy to have the first while believing you have the
second. A guard here asserted that one namespace's rendered source does NOT
mention another, and carried two population controls — 50+ namespaces in the
store, and a `re-find` proving the right source had rendered. Then the named
namespace was RENAMED. Both controls stayed green and the absence assertion went
on searching for a string that could no longer occur anywhere.

A search pattern is DATA: `ns_rename` rewrites requires, qualified references,
quoted symbols and prose, while `rename_sweep` matches text and a regex escapes
its dots — so no verb reaches it. Pair the absence with a match against
something you KNOW contains the name:

```clj
(is (seq (re-seq #"acme\.client" (render/render-ns st 'acme.client))))  ; the needle still bites
(is (= [] (vec (re-seq #"acme\.client" src))))                          ; …and it is absent HERE
```

slopp reports this one for you: the **`stale-pattern`** advisory flags a regex
naming a name in your store's OWN root family that is neither a namespace nor a
prefix of one. The scoping is deliberate and it is the fixture rule read
backwards — *a fixture that names no real production code is exactly a fixture
this check cannot see*. One more reason to name fixtures after nothing real.

**And when you SAMPLE a collection, the sample is a population too.** Reading
the keys off the first row of a sequence whose keys are OPTIONAL tells you what
that row has, and nothing about the shape. Measured both ways in one exchange
here: a consumer read `[form form-id module ns]` off one graph node and
reported that the endpoint had stopped sending `:sig`; over the whole set, 32
of 58 carried it, and the ones without were forms with no arglists. Take the
UNION across rows, or say "this row has" rather than "the endpoint sends".
It is the fixture trap wearing different clothes — **what is absent from the
sample reads as absent from the contract.**

**Two fields that coincide in the common case are ONE field for testing
purposes.** A boundary report carries `:from` (a namespace) and `:from-module`
(its module). At MODULE grain those hold the same string, so a consumer counted
one and labelled it the other, and every test over its module-grain fixture
agreed — the fixture could not distinguish the two readings, so no assertion
over it could. The mislabel surfaced the instant real namespace-grain data
arrived. The rule: **test at the grain where the two differ, or you have tested
neither.** Suspect any pair where one value is derived from the other — a
qualified symbol and its namespace, a path and its root, a form and its
container.

**And a comment asserting that two functions AGREE is a test nothing runs.**
Worse than no comment, because a bare duplicate invites suspicion while a
documented parity disarms it — for exactly the reader checking whether both
paths were covered. Two producers here built the same thing, one was fixed,
and the line above the other said *"the same split X makes locally"*: true when
written, and the commit that made it false was the commit that made that code
wrong. Nothing about fixing the first draws your eye to prose in the second.

The tell is cheap and greppable: **a comment naming another function as the
reason THIS code is correct is a candidate for being that function's test
instead.** Then it is redundant rather than wrong. When you write the test,
assert **the part that must not vary, not the whole output** — a parity test
over everything gets deleted the first time a legitimate difference appears.

**This applies to a shell command exactly as it does to an `is`.** A rename was
verified with `query_capabilities | grep ':set true'` → no output → read as "the
rename landed". Empty meant two things at once: *nothing is set*, and *the tool
cannot see what is set* — and it was the second. The fix is one more command:
count the population first (`| grep -c ':key'`), because a non-zero count is
what makes the zero from the real filter mean anything.

The general form is worth memorizing, because it costs one line and it catches
a class you cannot otherwise see: **an empty result standing in for a verified
one.** Ask it of any check whose pass is a silence.

**The mirror image, and the more tempting one: a check that PASSES while
answering a narrower question than the one you asked.** The cheap check and the
expensive check are not the same check, and the cheap one is the one that gets
run. Worked example: a fix was reported as shipped on the evidence
`slopp/rules/catalog.clj  direct-http  1` — a grep of the built artifact,
returning a true fact. It proves the rule is DEFINED. It does not prove the
sweep RUNS, and the rule had been defined all along; the wiring was the entire
bug. The one artifact fact that distinguishes a shipped fix from an unshipped
one was the only one the grep could not see.

So when you verify that a change reached an artifact, **grep for the CALL SITE,
not the definition.** `sweep-store!` appearing in `rules.clj` says someone wrote
a function; `rules/sweep-store!` appearing in `external.clj` says something
calls it. The second is one character longer to type and it is the claim you
are actually making. The same asymmetry runs through this whole section: "the
symbol is in the jar" and "the code path runs" are different claims, and the
first is much easier to check, which is exactly why it gets checked.

**A metric and its test, written in the same session, cannot validate each
other.** The fixture gets derived from the metric's own definition, so it can
only ever confirm that definition — including the part that is wrong. A layout
tool grew a `crossings` count with an adversarial 3-crossings-→-0 test beside
it; the count was textbook and it only counted crossings between ADJACENT
layers, so on the real graph — where every crossing came from a layer-SKIPPING
edge — it reported 1 where 8 were on the screen. The fixture could not have
caught it. What did was asserting the property a reader actually cares about
(*no drawn edge passes through any box*) against REAL data, which fails
immediately and cannot be satisfied by a wrong metric because it does not go
through the metric. So: whatever you measure with, assert the user-visible
property against the real store at least once, even when it is harder to
phrase.

**And a measurement that reports "no change" is a suspect, not a result.** The
failure mode is not that an instrument lies — it is that it answers a narrower
question in the wider question's voice, and the tell is a suspiciously boring
answer. Before believing "correct, tested, no improvement here", check the
instrument. This is the same class one level up: *"none"* and *"none that I
looked at"* are different claims that share a spelling, and here the thing with
the two meanings is your own yardstick.

**And the version with a precaution attached: "I did X and the problem never
appeared" is not evidence that X worked.** It is equally evidence that the
problem does not exist. Those two produce identical observations, and only one
of them is a reason to keep paying for X. If a practice earns its keep by
making something NOT happen, watch it happen once without the practice —
deliberately, under conditions you control. A causal claim nobody tested reads
exactly like a measurement for as long as nobody checks the implementation, and
it propagates: into a backlog item, into a docs page, into the next agent's
habits.

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
rewritten caller left referencing nothing in the source namespace. Any OTHER
unused require — one an ordinary delete or refactor stranded — you leave
alone: **`done` prunes it for you, and there is no tool to check or remove
one by hand.** At every done point it TRIES removing each require kondo
reports unused; a genuinely dead one is dropped, and one that turns out to be
load-bearing — removing it would break a cold load, a `defmethod`/registration
the reference graph can't see — is restored with a `^:side-effect` marker so
it is never flagged or re-tried. `done` reports what it did in
`:pruned-requires`. (A stale require is not merely untidy: a namespace inherits
the TIER of everything it requires, so one left behind makes a `:pure`
namespace report as depending on the shell for something it no longer uses —
which is why done clears them.)

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
named) — judged on PRODUCTION edges, so a `-test` namespace's fixture
require never vetoes an architecture decision, even though it does show
up in the declared manifest. An `:instrument` module (`module_role`) is
out of that view for the same reason and stays in the manifest the same
way, so its edges are still gated while it cannot distort the layers. **Red-first specs targeting a package-private ns go in a
SAME-PACKAGE test ns** (`x.y.z` spec → `x.y.z-test` or another `x.y.*`
test ns): an outside spec naming not-yet-existing deep vars hits the
visibility gate before its stubs can land, and the escape it teaches
(mark the target `^:export`) is impossible for a var that doesn't exist
yet. Read the whole architecture in one call: `query_depends {modules
true}` — manifest, topological :layers, :cycles, :unused-edges (dead
declarations), :overstated-edges (production edges only tests cross),
standing debt; browse what a module OFFERS (public fns +
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
  **A `:partial` green says nothing about the deferred half, and some changes
  are only visible there** — anything touching a durable session, a projection,
  or a subprocess lives in `^:external` by definition. A keyword rename across
  an options map once came back `:partial` with 1412 assertions green, having
  left the busiest entry point in the store destructuring a key no caller
  passed; every test that could have seen it was deferred. So when the change
  is of that kind, `:partial` + a large `:external-pending` is a prompt to run
  `done` or `full_check` — not a result to move on from.
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
- `:left-behind` (ns_rename, rename_sweep, ns_realias) — occurrences no rewrite
  reaches, grouped by how each was found. Under `ns_rename` the `:alias` rows
  are the callers whose `:as` still spells the old name, each with a `:suggest`
  to hand `ns_realias`. `:requalified` (rename_sweep) — destructurings it
  restructured, which is the half of a keyword rename's diff that is not a text
  substitution. Absence of either means checked-and-none, never unchecked.
- `:sites` (ns_realias) — qualified references rewritten. Zero is a real
  answer: the alias was declared and never used.
- `:conflicts` (merge) — ours kept, theirs surfaced; the payload IS current
  live source, so resolve straight from it.
- `:source-now` — your match missed or was ambiguous. Correct from it and
  resend; no read needed.
- `:hint` — a one-line workflow nudge, at most once per session.

## Effects, deps, escape hatches

- `deps_add` a library, then require it normally (hot classpath add, no
  restart). `deps.edn` is GENERATED — never hand-edit it.
- **A library slopp itself bundles runs at slopp's version in the SERVER
  process**, and `deps_add` says so as `:host-override {lib {:declared
  :in-force}}`. Your declaration still governs the oracle image, the test
  suite and anything `build` produces — so the server and your tests can run
  different versions of the same library. Usually harmless; pin to the
  `:in-force` version when it is not. A jar the server's parent classloader
  already holds cannot be displaced at runtime, so slopp reports the
  disagreement rather than claiming the declaration won. Separately,
  `:shadowed` names a namespace more than one classpath entry provides (the
  first url listed is the one in force) — usually two deps vendoring the same
  code.
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

## Web applications (D-web)

Opt in once: `config_file {path "capabilities" key "web.enabled" value
"true"}` (every capability key is registry-declared — `query_capabilities`
lists them all with types and defaults; a typo'd key or bad value refuses at
the write). A store that never opts in has no web surface and no web rules.

**A capability key's FIRST SEGMENT names who owns it**, and
`query_capabilities` reports it per row with the vocabulary beside it:
`slopp.*` is the framework's and is RESERVED — your app can never own a key
there — `app.*` is any project's, and `web.*` is the web app type's. So
everything a web project configures, auth included, sits under `web.`:
`web.port`, `web.static.<prefix>`, `web.auth.providers`,
`web.auth.groups.<name>.members`. A key belonging to no declared owner is not
a capability and refuses at the write.

**An endpoint is one `defn` carrying its whole contract in name metadata** —
no route table, no macro:

```clj
(defn ^{:web/method :get
        :web/path   "/api/users/:id"
        :web/auth   [:group "admin"]
        :web/reads  {:user [:user/by-id [:path-params :id]]}
        :malli/schema [:=> [:cat Req] Resp]}
  get-user "One user." [{:keys [path-params] :web/keys [reads]}] …)
```

Request/response maps are RING-shaped (`:request-method` `:uri` `:body` /
`:status` `:headers` `:body` as data); everything slopp adds is
`:web/`-namespaced. `query_routes` lists the whole surface: every method,
path, policy, handler, the declared `:web/request`/`:web/response` contract,
and the derived effect/read vocabularies.

**Write gates** (all inert until `web.enabled`; dial via `rules` config):
- every endpoint DECLARES `:web/auth` — `:public` is typed out, never implied
- every endpoint TYPES its contract — `:web/response` (all) and `:web/request`
  (body methods `:post`/`:put`/`:patch`) — a `.cljc` malli schema VAR
  (`some.contracts/order`: shared, refs-visible, and the input to the generated
  client — the paved road) or an inline `[:map …]` for a one-off (D-web-contracts)
- one method+path has one owner (collision refuses at the write)
- a `:get`/`:head` endpoint is SAFE: no `:web/effects`, no reachable mutation
- `:web/effects` may only name kinds a `^{:web/effect <kind>}` performer
  provides

**Keep handlers pure.** Reads: declare `:web/reads {alias [<kind> <req-path>]}`
naming a `^{:web/read <kind>}` performer — the framework fetches BEFORE the
handler, so a unit test just passes the value. Writes: RETURN
`{:web/effects [[<kind> & args]…]}` as data and let the dispatcher run the
marked performer — the test asserts `=` on data, no mocks. An endpoint that
must perform effects directly declares `:web/effectful true` (ON the name,
with the rest of the contract) and lives in an `:external` namespace (the
escape, not the default); its dependencies arrive as `:web/deps` on the
request, never as ambient state.

**For many web projects you do not run it — slopp does.** slopp boots a
DEDICATED image for your app, loads the web surface into it, and re-serves at
every `done` point; `session_brief` carries the url as `:app`. Where that
applies you write no `serve!` call, no namespace list and no port — all three
are derived from the store, so they cannot disagree with the gates.

**There is no switch, and you do not need one.** Whether slopp manages your
server is DERIVED: it does, unless the calling process already serves every
namespace your store would — true of exactly one store, slopp's own, whose web
surface *is* the API the live session already serves. A project cannot answer
this wrong because it is never asked. (It used to be a `dev.server` capability.
The only adopter who ever set it set it to work around 404ing assets, and the
switch then made a bug look like a preference for a week.)

`web.static.*` mounts and handlers taking `:web/deps` both work — the generated
call carries the mounts and calls your context builder.

**One real gap: `:web/auth-config` is not carried,** so an app using it gets a
managed server on which identity does not resolve. There is nothing to
configure around it; if that is you, say so rather than working around it.

**Handlers taking `:web/deps` DO work — declare the builder, and slopp
insists.** Mark one zero-arg fn `^{:web/context true}`; slopp calls it and
passes the result as `:web/perform-ctx`, handlers receive it as `:web/deps`,
performers as their first argument. Exactly one per store (a singleton, unlike
performers, which are keyed by kind). It cannot be a performer — performers
already RECEIVE the context, so it is upstream of that vocabulary. Writing an
endpoint that reads `:web/deps` into a store that declares no builder is
REFUSED (`web-undeclared-context`): nil deps either 500 or, worse, answer 200
with an empty body, and `generate_client` consumes the empty one as a success.
An app that runs its OWN `serve!` should mark the builder it already has and
call it — two definitions of one store's context agree right up until one
gains a key.

**But the context does NOT survive a refresh.** Each `done` boots a fresh app
image, so the builder runs again and anything it allocated — an atom, a pool,
a cache — is new. An app accumulating state there will find it silently empty
after any done point. Keep live state outside the context, or opt out. Note
what that means for the obvious workaround: a builder returning `{:registry
(atom {})}` allocates per CALL, so only a top-level `(defonce registry (atom
{}))` the builder REFERENCES could survive anything. That is deliberately the
shape `ambient-state` flags — advisory, with "a legit top-level cache" as its
named escape, and this is one of them.

**The builder is ZERO-ARG, so it cannot double as a test seam.** If your only
end-to-end seam was context construction — a `serve!` arity taking a fake
collaborator, say — the builder becomes the single source of the context and
simultaneously stops being parameterisable. Two ways out, both fine: inject at
the HANDLER (`(web/handle! … {:web/deps {:registry … :requester fake}})`,
which is below the builder and unaffected), or have your own `serve!` call the
builder and merge an override over it, which keeps one definition and keeps
the seam. Obvious once said, and not before.

Where it does apply, three things worth knowing:

- **`done` is the grain, not the write.** Mid-episode your store is
  intentionally incomplete, so a browser reloading on every write would show
  you a broken app repeatedly. A RED `done` still refreshes — looking at the
  app is part of finding out you were not finished.
- **A store that will not load leaves the previous version serving**, and
  says why. "Always up" and "up to date" only conflict when a boot fails.
- **The app image carries YOUR deps, not slopp's.** Code that works because
  slopp happens to have a library on its classpath fails the moment you look
  at the page instead of when someone deploys — but that signal depends on
  the page working, so it is worth nothing until the exceptions above are.

`web.port` pins the address; unset, it is derived from the store dir so two
projects on one machine never collide.

**The runtime underneath: `slopp.web`.** `(web/serve! {:web/namespaces
['my.api] :web/port 8080})` scans the namespaces' var metadata (the same
contract the gates enforced) and serves on http-kit (`:web/adapter :jdk` =
zero-dep fallback) — that is what a deployed build calls, and what the dev
server calls for you. Tests never need it: `(web/handle! (web/context
{:web/namespaces ['my.api]}) request-map)` runs the ENTIRE pipeline — route,
policy, declared reads, handler, effect interpretation — portlessly. In-handler
guards: `(web/enforce (= owner sub))` throws a 403-mapped ex-info (no bang —
your handler stays analyzer-pure); `(web/authorized? policy identity)`
answers booleans. Test namespaces' endpoint-shaped forms are FIXTURES —
they neither report in query_routes nor claim paths.

**Both halves of the URL are addressed the same way.** The dispatcher puts
`:path-params` AND `:query-params` on the request (the query string is
parsed once, there — don't split `:query-string` yourself), so a declared
read reaches a query parameter exactly as it reaches a path one:
`:web/reads {:page [:my/page [:query-params :view]]}`. Needs both? Declare
the read over the whole request with `[]` and destructure. A parameter the
page cannot honour should be a 404, not a silent fall back to the default —
otherwise a link means "whatever the default became" the day you add a
second value.

**Security posture the runtime enforces** (not just the write gates): auth is
default-deny and an empty `[:all]`/`[:any]` policy DENIES; the dispatcher
bounds a response's effects to the route's declared `:web/effects` (a handler
cannot emit an undeclared kind, even one a performer provides); error bodies
are redacted — an `ex-info` with `:web/status` surfaces its message plus only
a `:web/public` allowlist, anything else is a generic 500 (detail logged, not
returned); request bodies are capped (default 1 MiB — thread
`:web/max-body-bytes` from the `web.max-body-bytes` capability into
`serve!`); the static asset reader contains paths under its root. Auth: static
passwords are salted PBKDF2 (`slopp.web.auth/hash-password` — mint one with
`query_eval`, it is not on the `slopp.web` facade), bearer and
password compares are constant-time, and **OIDC requires a configured
`web.auth.oidc.audience`** — an unset audience denies every token (a resource
server must not accept cross-audience tokens). Row-level authz is still yours:
slopp does not taint-track a handler returning another tenant's rows.

**HTML pages are hiccup — store forms, not template files (D-web-html).** A
page or component is a `defn` returning hiccup data; `slopp.web.html/render`
serializes it (hiccup 2.x underneath, escape-by-default), `html-response`
wraps it as a `text/html` RING map, and `page` is the full-document shell
(`{:html/title … :html/lang … :html/head […]}` + body — doctype and charset
included, NO inline script or style, so a strict CSP needs no carve-outs).
The rules that matter:

- **Attrs are position 2, always a map or absent.** Compute conditional
  attrs — `(cond-> {:class "todo"} done? (update :class str " done"))`;
  `(when p {…})` in position 2 is a vanishing CHILD when p is false.
- **Everything escapes; never pre-escape.** `[:html/raw s]` is the ONE
  bypass (string payload only). The renderer refuses crafted tag/attr
  NAMES and `javascript:`/`data:` URLs in `href`/`src`/`action` — those
  survive escaping, so they throw instead.
- **A vector is an element; a seq splices.** Repeat with `for`/`map`;
  never group siblings in a vector.
- **No React names** — `:class` not `:className`, `:for` not `:htmlFor`,
  no `:onClick`-style handlers (the `web-react-attrs` gate refuses them:
  browsers silently ignore unknown attributes, so the mistake ships and
  does nothing).
- **Component-per-defn.** A thin page shell composing small component fns —
  that is the merge grain, the test grain, and each component stays
  `=`-testable data.
- **Check `query_routes` before writing a link or form path.** Literal
  `:href`/`:action` values are INDEXED: route rows carry `:rendered-by`
  (who links here), and `done` fails on a path nothing serves
  (`web-dangling-route-refs`). `(str "/prefix/" x)` checks by prefix; a
  fully dynamic path is reported `:unresolved`, never counted clean.
  Served by something outside this store? `^{:web/external-path "why"}`
  on the rendering form discharges.
- **See a page without a server:** `(web/handle! (web/context
  {:web/namespaces ['my.ui]}) {:request-method :get :uri "/x"})` via
  `query_eval` — the full pipeline, rendered HTML in the response map.
  Test on data first (call the handler with a synthetic `{:web/reads …}`
  request); pin one rendered string per component. Under `--live`, an
  edited page hot-serves — browser F5, no build step.

**CSS is garden — the same story for stylesheets (`slopp.web.css`).** A
stylesheet is a `defn` GET endpoint returning `css-response`; rules are
garden data (`[:main {:margin "0 auto"}]`, nested `[:main [:a {…}]]`,
`garden.stylesheet/at-media` for `@media`). `render` serializes minified
and validates every selector/value string against block-breakout (`{ } <`
throw — garden renders strings verbatim, so an interpolated value is an
injection door; `;` is allowed because data URIs use it). Serve it, then
`[:link {:rel "stylesheet" :href "/styles/app.css"}]` from `page`'s
`:html/head` — that `:href` is a literal, so `web-dangling-route-refs`
ties the link to the stylesheet endpoint like any other route. Raw or
vendored CSS goes through a static `.css` asset (`file_put` + an
`web.static.*` mount), not the renderer.

**Client code is ClojureScript — same store, compiled to JS (D-web-cljs).**
Browser logic is authored like everything else: forms in the store, edited by
the same tools, gated by the same dialect. A namespace declares its target
**platform** — `module_platform {module platform}`, or born at creation with
`ns_create {ns … platform}`:

- **`:jvm`** (default) — ordinary Clojure, loads into the oracle, never
  compiled to JS. Everything you already write.
- **`:cljc`** — `.cljc`, loads on the JVM (`:clj` branch) AND compiles to JS.
  **This is where testable logic goes.** A `:cljc` form is verified by the JVM
  oracle for FREE, exactly like Clojure — write a `-test`, red→green as usual.
- **`:cljs`** — `.cljs`, client-ONLY (`js/*`, the DOM). Never loads into the
  oracle; a write lands `:unverified` with reason `:cljs-deferred-to-compile`.
  It is verified by the COMPILER, not the suite.

**The discipline that makes this work: keep platform-neutral logic in `.cljc`
so the free JVM oracle covers it; reserve `.cljs` for the thin, genuinely
browser-bound edge (DOM, events).** A predicate, a schema, a state transition
belong in `.cljc` and get red/green; only `js/document`-touching glue is
`.cljs`, and it stays small because a cljs-only form is verified by "it
compiled" alone until browser tests exist (out of scope — that is
Cypress/Playwright territory someday).

- **Compile with `compile_client`** — it compiles every `:cljc`+`:cljs`
  namespace with the configured backend (real ClojureScript, **on the JVM, no
  Node**) to one `:simple` bundle, recorded as a served blob (default
  `public/cljs/main.js`, served at a URL you choose via a static mount).
  Compile-error-as-oracle: analyzer warnings and hard errors are anchored to
  the owning store form. Reference the bundle with `[:script {:src
  "/js/main.js" :defer true}]` from `page`'s `:html/head`; a top-level
  `(defonce _ (main))` self-starts it so the page needs no inline JS.
  **Address it by what it IS, not by what built it** — `/js/main.js`, not
  `/assets/cljs/main.js`. A URL is an address that ends up in bookmarks and
  caches; `cljs` names a toolchain you might change, and nothing about
  serving JavaScript changes if you do.
  **And a `:src` is a route reference** — `web-dangling-route-refs` checks it
  like an `:href`, so a bundle you link but never mount fails `done` instead
  of 404ing silently in a browser.
- **slopp provisions its OWN toolchain — you never `deps_add` the compiler or
  malli.** There are TWO dep configs: **yours** (the `deps_add` manifest —
  application libraries, delta-tracked, in `deps_list`) and **slopp's** (the
  compiler + malli), which slopp injects **at build time**, versioned centrally
  with slopp. They never enter your manifest, never appear in `deps_list`, and
  never land as deltas in your history — so a slopp upgrade moves every store
  forward with no migration. The compiler goes to the build-only `:cljs` alias
  (never hot-loaded, never in the runtime jar or native binary); malli to the
  build's `:deps` (the `:cljs` alias inherits it, so one entry serves the JVM
  oracle, the external tier, and the compile). Injection happens only when the
  store HAS client code. The compiler backend is a per-project config
  (`config_file {path "client" key "compiler"}`, default `:clojurescript`) —
  cherry/squint are future backends, same source.
- **An external JS library goes through `js_dep`, and declaring IS vendoring.**
  `js_dep {name, version, format, global, file, source}` records the library
  AND stores its bytes in one act — `:source` is a path to bytes you already
  fetched, because there is no npm client in the loop. slopp writes a
  `deps.cljs` at compile time, so `(:require [roughjs :as rough])` resolves
  through `:global-exports` to the browser global. `format` is `:iife`/`:umd`
  (concatenated into the bundle) or `:esm` (import map). The bytes are an
  ARTIFACT — sha and a `{:kind :download :npm …}` recipe in the journal, bytes
  on disk — so a vendored library never bloats your delta log.
- **Test the library boundary by testing the DATA, not a fake of it.** A
  hand-written fake needs a contract suite run against the real thing to stay
  honest, and slopp's oracle cannot run JS — so the fake could never be
  checked. Keep the analysis and the emitted structure in `.cljc` where the
  oracle verifies them at full speed, and let the `.cljs` adapter be thin
  enough that reading it is the review. `compile_client` is the only oracle
  the JS side gets; that is a reason to put almost nothing there.
- **Read platforms at a glance** with `query_depends {modules true}` — a
  `:platforms` map names the `:cljs`/`:cljc` namespaces (undeclared = `:jvm`).
- **Share real logic AND libraries in `.cljc`** — a malli schema in `.cljc`
  (`m/validate`) is JVM-verified by the oracle here AND compiled into the
  browser bundle, so the same contract checks both sides. Keep the schema and
  any pure transform in `.cljc`; the `.cljs` stays thin.
- **In `.cljs`, definitions must PRECEDE their callers.** There are no
  top-level forward declarations, so a helper written below its caller
  compiles to an undeclared-var warning. `edit_move {ns name before}` fixes
  it — but the cold-load gate refuses a move while the violation stands, so
  break the forward reference first (revert or edit the caller), move, then
  re-apply. Writing helpers before callers avoids the whole dance.

### Non-trivial apps: a REST API and an SPA that consumes it

For anything past a few pages, the shape that keeps SCALING is **a JSON API
with declared contracts, consumed by client-side code** — not HTML assembled
on the server for the browser to slot in. The reason is not fashion: the API
is an explicit, testable boundary. One call (`query_routes`) answers what the
app can do, each endpoint is `=` on data with no mocks, and the frontend
consumes a GENERATED contract instead of sharing the server's internals.
Server-rendered pages and static content stay fully supported — they just
stop being the assumption once the app grows.

- **Address the JSON surface at `/api/*`** and keep it separate from the
  pages. Same reason URLs name what they ARE: a reader (and an agent) can see
  the whole data surface without reading handlers.
- **Views are `.cljc`, so the SAME function renders on both sides.** The
  server renders it into the page; the client re-renders it after a fetch. One
  renderer means a click and a refresh cannot show different things — the
  drift that otherwise only a browser reveals.
- **Take the WIRE shape in a shared view**, not the server's shape. JSON has
  no symbols and no keywords-as-values; a view taking the server's shape
  renders correctly on the server and renders `nil`s in the browser.
- **THE test of whether you got the split right: your UI tests are in-image**
  (~0.5 ms each), asserting on returned hiccup DATA — `(get-in v [2 1])`. If
  UI tests need the external tier or a browser, too much logic drifted into
  `.cljs`. That is the check on the whole discipline, and it is why a view
  that returns data beats one that returns a framework's component object.
- **The tell that you got it HALF right: every READ of your view state is
  `.cljc` and tested, every WRITE is `.cljs` and is not.** This is the most
  comfortable way to be wrong, because nothing looks missing — the rule
  deciding what a reader SEES is checkable, and only the rule deciding what a
  CLICK DOES is not. Those two have to agree or a toggle lies about its own
  state. Measured in a real app: four handlers, one of them extracted the
  read (`doc-open?`, `:cljc`, tested) and left the write inline
  (`(swap! state assoc-in [:show :doc k] (not (doc-open? (:show @state) k)))`).
  That one carried a deref-compute-swap race — nominal for a human clicking,
  and the first thing a programmatic driver hits.
- **Progressive enhancement beats a hard SPA when a server route exists.**
  Intercept plain left-clicks only — leave middle-clicks and cmd/ctrl/shift
  clicks to the browser, or the enhancement takes away open-in-a-new-tab. And
  on a failed fetch, fall back to a full page load: a stale pane under a new
  URL is the SPA failure mode that lies to the reader.
- **One list of served namespaces, not a literal per server.** Routes and
  `:web/read` performers can live in different namespaces (reads resolve by
  VOCABULARY, store-wide, so an API endpoint can reuse a page's read). A
  server given only half answers **500, not 404** — much harder to diagnose.
  If two servers mount the same app, they share one `def`.
- **Mark transport endpoints `^{:web/client false}`.** Anything that is not
  the app's own API — health, metrics, an RPC transport — otherwise gets a
  typed browser `fetch` wrapper generated for it. The same flag is what keeps
  HTML page endpoints out of the client.

#### If you go all the way: no server-rendered pages at all

- **Serve ONE document** — head, an empty `<div id="app">`, nothing else —
  and declare `:web/spa` with the prefixes your client router owns. Note the
  prefix ROOT is not covered (`["/store"]` generates `/store/*`), so `/store`
  itself needs its own route.
- **Consequence to state out loud: every path under a declared prefix now
  answers 200.** Not-found moves into the client. A bad deep link that used
  to 404 now serves the document and the client renders "not found" after its
  fetch 404s. That is correct, and it is a real change in what your status
  codes mean. `done` says it once, for the episode that adds the declaration
  (`web-spa-consequences`) — and `full_check`'s `:crossings` keeps listing it as an
  UNCHECKED exit, because nothing compares your client's route table to the
  server's.
- **`route-for` in `.cljc`, returning nil for unknown paths.** With server
  rendering gone this IS your routing table, so make it a pure function and
  test it in-image. Never default an unknown path to a screen — that tells
  the reader they are somewhere they are not.
- **ONE pure `app-view` from state to the whole page.** Then *every* screen
  is an in-image assertion on hiccup data, including the two an SPA invents:
  **loading** and **not-found**. Both render as a blank pane if nobody
  handles them, and a blank pane is indistinguishable from a screen whose
  content is empty.
- **Clear the previous screen's data before fetching.** Leaving it up under
  the new URL shows one thing while the address bar claims another.
- **Endpoint tests must ROUND-TRIP through JSON.** `web/handle!` returns the
  body as Clojure DATA — the adapter serializes — so a keyword sails through
  a `[:x :string]` contract in-image and reaches the browser as a string. A
  test that does not serialize is checking a value no client receives. Watch
  for vacuous validation too: `[:sequential …]` over an empty list checks
  nothing inside it, so give fixtures at least one real element.
- **`:uri` and `:query-string` are separate request keys.** Putting `?a=b`
  into `:uri` means no route matches, and the 404 you get looks exactly like
  the one you were trying to assert.
- **Send data, never markup.** Syntax highlighting ships as `[class text]`
  pairs and diffs ship as lines; the client decides what an element is. That
  is what keeps one renderer instead of two.
- **Dev loop (optional):** `config_file {path "client" key "auto-compile" value
  "true"}` recompiles the bundle after a client-ns write — ASYNC and
  non-blocking (single-flight + coalescing): the write returns
  `:client-recompiling`, and a `--live` server serves fresh JS once the
  background compile commits. Off by default. Also fine: `:cljs` forms can be
  renamed/moved/extracted like any code — the refactor ops handle them.
- **One benign rough edge:** the D6 `!`-effect warning fires on idiomatic cljs
  entry points (`^:export main` touches the DOM) — advisory, not a refusal.
  (Kondo lints each form in its platform's language, so `js/*` no longer draws a
  false "unresolved namespace" finding.)

**The typed client is GENERATED, never hand-written (D-web-contracts).** Once
your endpoints declare their `:web/request`/`:web/response` contracts (the write
gate requires it — see the D-web write gates), `generate_client` writes a stored
`:cljs` namespace (default `app.client.api`, set `client`/`generated-ns`) of
typed `fetch` wrappers — one fn per endpoint, validating params OUT and the
response IN against the SAME schema the server enforces. Call them from your
`.cljs`: `(api/create-order! params)` returns a promise; a wrong shape throws
before the request leaves. Rules of the road:
- **It's EXPLICIT** — run `generate_client` after changing an endpoint (like
  `compile_client`, not on every edit). A `web-stale-client` done-advisory nudges you
  when a contract drifts from the last generation; with `client`/`auto-compile`
  on, the generate also refreshes the JS bundle.
- **NEVER hand-edit it.** Every wrapper is `^{:generated "<endpoint>"}` and the
  `web-generated-ns` gate REFUSES edits (regenerate instead; to take manual
  ownership, strip the marker). It's still fully inspectable — `query_source`,
  blast-radius, refs — and because the wrappers reference the schema VARS,
  "change a schema → every affected client call" falls out of the reference graph.
- **Schemas must be `.cljc`.** A `:web/request`/`:web/response` VAR the client
  ships has to live in a `:cljc` ns (so it compiles into the bundle AND is the
  one the server validates); `generate_client` SKIPS an endpoint whose schema
  isn't shippable and reports it in `:problems`. A `web-inline-schema-dup` advisory
  nudges a shape shared across endpoints toward a named `.cljc` var.
- **Serving under a path prefix: `set-base!`.** Every wrapper's path is
  root-absolute (`/api/orders`), so behind a reverse proxy that mounts the app
  at `/app/…` they all resolve at the PROXY and the page does nothing. The
  generated ns exports `set-base!` — call it once where you mount, and every
  wrapper follows. Default `""` is the served-at-the-root behaviour, so an app
  that needs none of this does nothing. Server side, send
  `X-Slopp-Base: /your/prefix` with the proxied request: the document reads it
  per REQUEST (not from config — the same server may also be answering
  directly on its own port) and emits its own asset urls prefixed.
- **A page endpoint opts OUT: `^{:web/client false}`.** An HTML page is a
  `:web/path` form like any other, so it would otherwise get a typed wrapper
  whose `.json` parse can never succeed. Declare it rather than relying on the
  response schema — `:string` is a perfectly good JSON response, so the schema
  can't tell HTML from JSON; only you can.

**Consuming someone else's API: publish a contract, generate against it.**
Everything above assumes the endpoints and the client live in ONE store. When
they don't — a UI in its own project, two services, anything across a process
boundary — the producer publishes its shape and the consumer generates from
that. Neither store reads the other.

- **Producer: serve `slopp.web.contract/contract-document`.** It takes your
  served namespace list and returns `{:slopp/contract-version 1 :endpoints […]}`
  — method, path, name, and the request/response schemas as VALUES. Serve it as
  EDN with `:web/raw true` and `Content-Type: application/edn`; mark the
  endpoint `^{:web/client false}` (describing the wrappers doesn't need a
  wrapper). It ships in the `slopp-web` slim jar, so any app can publish, not
  just one whose code lives in a store.
- **Consumer: `generate_client {from "http://host/api/contracts"}`.** Writes
  TWO namespaces — a `:cljc` contracts ns of the published schemas, and the
  usual `:cljs` client pointing at it. Both `^:generated`; regenerate, never
  hand-edit.
- **EDN, not JSON, and not OpenAPI.** A malli schema is keywords, symbols and
  vectors; JSON renders `:string` and `"string"` identically and the far end
  can't tell them apart. OpenAPI would work but only one direction is
  lossless (malli → JSON Schema), so it forces an importer into the path; keep
  EDN as the source of truth and derive OpenAPI later if a non-Clojure consumer
  ever needs it.
- **Names come from ENDPOINTS, not from the producer's schema names.** Metadata
  is evaluated at def time, so `^{:web/response contracts/timeline}` is already
  a plain vector by the time anything can read it — the name `timeline` never
  existed at runtime. The consumer gets `timeline-response`, and a schema shared
  by two endpoints arrives inlined in both.
- **The version is there to be refused.** An unrecognised
  `:slopp/contract-version` generates nothing and reports a problem, rather than
  guessing at a shape it doesn't know.
- **Pass the served-namespace list to your performers as data.** Only the
  server knows what it serves. Thread it through `:web/perform-ctx` — reaching
  for it from a page namespace inverts the dependency, and forgetting it
  entirely publishes an empty contract with a 200, which a consumer will
  happily generate an empty client from. Test that one over a real socket: an
  in-image test builds `perform-ctx` itself and passes either way.

**If your store declares `io.github.nvoxland/slopp-web`, your DECLARATION is
the version you run — not the slopp hosting you.** The slopp process carries
`slopp/web/**` inside its own jar, and the declared coord still wins: tests,
`query_eval` and your server all load the pinned release. So a `slopp.web` fix
in a newer slopp does not reach you until that release is republished and you
`deps_add` it (then `restart` — a hot `deps_add` cannot displace an
already-loaded namespace). Nothing warns you when the pin is behind, so treat
"is my `slopp-web` current?" as a question you have to ask. Measure rather than
assume: `query_eval` `(.getResource (clojure.lang.RT/baseLoader)
"slopp/web/static.clj")` names the jar actually in force.

### Reviewing a UI without a browser

**`slopp.web.screen` drives your app like a browser, with no rendering engine**
— document, event dispatch, re-render, on the JVM, running your app's OWN
client code. Reach for it the moment you want to *look at* a screen, not just
when writing a test: opening a real browser to read a sentence is the habit
this replaces. (It is called `screen` and not `browser` on purpose — a real
browser is a thing you may also be testing with, and that word has to keep one
meaning.)

**To LOOK, use the `screen` tool** — no code, no test, no browser:

```
screen {steps [{visit "/store"} {fill "Filter" value "web"} {click "Add"}]
        region "main" detail "prose"}
```

**To ASSERT, the same thing in a test.** `screen/drive!` takes the identical
step script, so a screen you looked at is one you can pin without retyping it
as a call chain:

```clj
(require '[slopp.web.screen :as screen])

(def s (screen/open ctx))           ; a slopp.web ctx — nothing else to declare
(screen/drive! s [{:visit "/store"} {:click "Code"}])
(screen/text s "main")              ; ONE region — and it throws if absent
```

Mark the zero-arg PUBLIC defn that builds your app `^:web/page` and the tool
can find it; there is deliberately no session between tool calls, so a script
is the whole interaction and the same script reproduces the same screen
(`trace true` shows the screen after every step of one run).

**How to read a screen — one rule.** Plain text is the page's words,
HTML-escaped, so page text can never be mistaken for markup; an UNPREFIXED tag
or attr was really on the page and survives only where it carries something
you can act on; anything `slopp:`-prefixed the reader derived. A screen looks
like:

```
<slopp:region name="main">
  <h1>orders</h1>
  3 open, 1 overdue
  <input placeholder="filter orders" slopp:on="input :orders/filter"/>
  <svg class="aging-chart">2 bar</svg>
  <ul slopp:count="5">
    <li>#101 late</li>
    <slopp:elided count="4"/>
  </ul>
  <button slopp:on="click :orders/expand true">expand all</button>
</slopp:region>
```

The kept tags: controls (`a href`, `button`, `input`, `select`/`option`,
`textarea`, `form`, `label` — with the state a browser shows: `value`,
`checked`, `selected`, `disabled`, and the page's own not-a-control
statements, `aria-hidden`/`inert`), structure (`h1`–`h6`, `table`/`tr`, `pre`
verbatim, `img alt`, the `svg` class census), enumeration (`ul`/`ol`/`li` with
`slopp:count`, and `<slopp:elided count/>` where the TOOL's 3-row cap bit —
`drive!`/`text` in a test elide NOTHING by default, so an assertion can never
be eaten silently). The guarantee is an INVARIANT, not a property of this
marker set: every structured-mode marker lives inside `<…>` and that alphabet
is escaped in page text — a marker outside the escape is the v1 flaw
returning, however harmless it looks. `class`/`style`/`id` never reach the output (except
`class` on svg, where it is the census vocabulary), so sugar (`:h1.big`) and
plain spellings render identically. A handler on ANY element keeps that
element's tag so `slopp:on` has a place to ride — `slopp:on="click :save
true"` says what a click DOES with its scalar args (twin buttons differ by
them); a closure can only say `click (fn)`. `detail "prose"` drops every tag
and keeps the words, unescaped.

**A server-rendered app declares nothing.** `visit!` goes through
`slopp.web.dispatch/handle!`, so it is a real request down the real pipeline —
routing, auth policy, declared reads, the handler, effects. A page that 401s
here 401s when served, which is the point of driving dispatch rather than
calling a handler. Urls behave like a browser's: `/search?q=web` delivers a
`:query-string`, a `#fragment` is never sent, a bare `#anchor` click is a
scroll (no-op), `#/…` is hash routing and reaches your `:navigate`, and an
external `https://…` link REFUSES — a headless session has nowhere else to go.

An app with CLIENT state supplies a page instead: `{:state (atom …) :view (fn
[state] …) :navigate (fn [state path] …)}` — `:navigate` optional, and one
function rather than a router. An app can be both: routes for the server
render, a `:view` to re-render after a click. `open` REFUSES a page it cannot
run — a missing `:state`/`:view`, a non-atom state, or an unknown key (a
typo'd `:vew` used to render a blank page, the silent worst).

- **Put handlers IN THE TREE — all three idioms are driven.** `:on-click (fn
  [e] …)` (Reagent), `:on {:click (fn [e] …)}` (Replicant), and `:on {:click
  [:action …]}` (Replicant's DATA form), which goes to a page-level
  `:dispatch (fn [event data] …)` mirroring `replicant.dom/set-dispatch!`.
  Typing is the same, under `:on-change` or `:on {:input …}`. Clicks BUBBLE
  as DOM semantics: text inside a handled element reaches that handler, an
  `aria-label` addresses an icon-only control, and a `disabled` control
  refuses. **Hand-rolled `js/document.addEventListener` delegation cannot be
  driven** — it lives in `:cljs` and never runs here. You rarely need it: both
  libraries attach handlers to elements for you, precisely so a re-render does
  not strand them.
- **Prefer the DATA form where you have the choice — and for an INPUT it is the
  only portable one.** It reaches `:dispatch` as `(action value)`: the action
  verbatim, the typed text as a SCALAR, no event map invented by anybody. A
  `<select>` fill must name one of its options (a browser only lets you
  choose); a checkbox's value is its checked boolean.
  **Never write a handler that reads a value out of an event**
  (`(:value e)`, `(get-in e [:target :value])`). Replicant's real event map
  carries `:replicant/dom-event` and no `:value` — the text is behind
  `(.. e -target -value)`, which is interop and cannot run on a JVM. A handler
  reading an invented `:value` passes every headless test and does nothing in a
  browser, which is the one direction of wrong that a test actively conceals.
  **The rule that dissolves it:** your `:cljs` dispatcher normalises the event
  to a scalar, your `:cljc` interpreter takes `(state action value)` and never
  sees an event of any shape — so neither driver's event can be right while the
  other is wrong.
- **How slopp REFUSES a page it cannot open**, at the write: the marker on
  anything but a zero-arity public `defn` (a `def`'s arity cannot be read, a
  `defmethod` discards the marker at macroexpansion, a private page is
  invisible to the tool's scan); the entry in a `:cljs` namespace; a SECOND
  `^:web/page` (the scan would answer from whichever it reached first,
  silently). And the one that catches real apps with no write to your entry at
  all: the entry's namespace CLOSURE reaching `:cljs` — `module_platform`
  reports the pages a `:cljs` declaration strands (`:stranded-pages`) at the
  moment of the declaration, the `web-page-reach` advisory re-grades a page
  you EDIT, and `full_check` re-grades every page.
- **What slopp assumes, so you can tell if you're outside it:** state is an
  atom, handlers are in the tree, the view is a pure function of state. The
  wiring is portable; only the EFFECTS are `:cljs`. An entry the JVM cannot
  call sends every headless test back to hand-building a map that RESEMBLES
  your app, and a resemblance passes while the real screen is wrong — which is
  the whole defect this removes.
- **Read the screen BEFORE asserting on it.** Most view bugs are plain wrong
  sentences, and they are obvious in a readout and invisible in a `get-in`.
  "Look at the UI" costing a browser is why they ship.
- **Scope an assertion to the region it names** — `(screen/text s "main")` is
  shorter than the whole page on purpose, and comes back DEDENTED so moving a
  `<div>` around the region cannot break it. A whole-page `str/includes?` is
  one keystroke from asserting nothing: a real tint check once matched its
  pattern anywhere on the page, claimed the diagram, checked a list, and stayed
  green with the layout torn out. In structured mode remember the text is
  ESCAPED: assert `a &lt; b` when the page says `a < b` (prose mode is
  unescaped).
- **A click refuses rather than shrugging** — nothing says it (and the message
  lists what can be clicked), it is on the screen but nothing over it handles
  a click, two distinct controls say it, it is disabled, or the app has no
  urls. Five different bugs, never one silent no-op.
- **`screen/lines` when you want to address ONE line**, e.g. the `<svg>` census
  — assertions are easy to write here and therefore easy to write too broadly.
- **A readout reveals what CSS was silently supplying, and that is a whole
  class of markup bug you get for free.** Two elements separated only by a
  margin have NOTHING between them in the text: a real page rendered
  `demo.orderstatic`, one wrong word made of two correct elements. Fix it in
  the MARKUP rather than by adding spaces to the reader — a reader without CSS
  is not only a headless driver, it is also a screen reader, and the same gap
  hits both. **And assert it in PROSE**: structured mode places a tag between
  adjacent words (`…rate</a>[kg zone]`), so word-gluing is invisible there
  whether the markup is fixed or not — prose is where adjacency is real, and
  the one mode this class of assertion can live in.
- **It is NOT a screenshot.** An `<svg>` is censused by CLASS rather than
  dumped as coordinates, which catches an overlay's tints with no pixels — but
  a list that wraps over three lines and a tint invisible against white still
  need eyes. Do not assert what a readout cannot see.

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
`query_macroexpand`. Re-reads are FREE: a view you already received THIS ASK
returns a tiny `:unchanged` stub — re-fetch instead of carrying source in
context. The stub is scoped to the ask, so after a context clear or a
compaction the next ask gets payloads again; when one does appear and you have
not in fact seen it (a subagent shares the session), its `:detail` id is a
`query_detail` away.
History is ONE door:
`query_history` routes by args ({} episodes · {ns name} a form's life ·
{ns name at} time-travel · {ns name effort true} what that form COST to get
green · {at} was-green-at · {contains} which asks
touched X · {dead_ends true} the SCRAPPED explorations, {dead_ends "some.ns"}
those that touched it); `report {since, contains}` for summaries and handoffs.
The number to read in `effort` is `:cycles` — red→green RECOVERIES, i.e. things
that had to be fixed. A form with two versions and two cycles was harder than
one with twenty and none, and nothing else tells you that. It carries
`:measured` because verification only started timing itself recently, so a
recorded total covers part of a long form's life and says which part.

**When the answer is for a HUMAN, hand over a URL rather than a wall of
pasted source** — your tools answer questions, a page lets someone LOOK.
There is a browsable view of the store: a milestone timeline, a per-milestone
change review (module → namespace → form, with each form's recorded ask, its
line diff and its blast radius), form permalinks by ID with callers above and
callees inlined below, and the namespace index.

**Hand over `session_brief`'s `:hub`, not its `:ui`.** Those are different
things and giving out the wrong one wastes someone's time:

- `:ui` is THIS project's own listener, and it serves `/api/*` — JSON, and the
  EDN contract at `/api/contracts`. It is already running (the server starts
  it at boot), it runs on your live session so warranty and observed examples
  are the ones you actually have, and it has no pages in it at all. A human
  opening it sees JSON.
- `:hub` is this project's own page on the hub — a separate application, one
  per machine, that renders every screen and fronts each project at
  `/p/<slug>/`. That is the address a person wants, and the screens hang off
  it: `<hub>/change/<from>..<to>`, `<hub>/store`,
  `<hub>/store/form/<id>`.

**`:hub` is present only while a hub is ANSWERING**, because the slug in it
comes back on each heartbeat and cannot be fabricated. If no hub is running you
get `:hub-note` instead, naming the address that is silent — a hub is
optional, so its absence is an ordinary state rather than an error. Don't paper
over the note by handing out the bare hub root: nothing is serving it.

`ui_serve` controls your own listener (port, restart, `{stop: true}`); serving
again evicts the previous server rather than moving the port. It does not
start the hub, which is not slopp's to start.

**On a machine running several slopp projects, hand over the HUB's url
instead.** Your server serves this project's `/api/*` and binds a port derived
from its store dir — it must serve its own, because warranty and observed
examples are only CURRENT in the session doing the work, and another process
reading the same store sees the last verified run rather than the one you are
changing. Those derived ports are not addresses a human should have to collect.

The hub is a SEPARATE APPLICATION (the `slopp-ui` project) that a user starts
once per machine. It needs no store and never opens one: it holds a registry
fed by heartbeats, renders every page a human looks at, and proxies
`/p/<slug>/api/*` to whichever project owns that slug. Every project
registers itself every few seconds, and one that stops answering is greyed out
rather than dropped. Configure with the `slopp.hub.port` capability (`0` = don't
register); the interval comes back on the registration response, so the two
sides share no compiled-in number and can be different releases.

That split is worth knowing about even if you never touch the hub, because it
is the shape a slopp app takes when it consumes another one: the hub generates
its typed client from each project's published `/api/contracts` and talks to
a store it cannot open. See "Consuming someone else's API" above.

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
query_rules query_rule_telemetry query_capabilities query_routes
query_macroexpand query_branches query_commits
query_git query_detail review_scan · ns_create
ns_add_require ns_remove_require ns_rename ns_realias ns_delete · edit_add_form
edit_replace_form edit_delete_form edit_subform edit_comment
edit_rename change_signature rename_sweep edit_requalify edit_extract
edit_move_forms module_extract edit_move edit_revert undo episode_revert cleanup ·
branch_create branch_switch
branch_merge branch_delete merge_from · deps_add deps_remove deps_list
deps_pure · module_dep module_purity module_role · file_put file_remove file_list file_get
file_history · config config_file · git_push git_clone git_pull git_conflicts git_resolve ·
test_run draft_test done full_check commit_point restart build help ·
ui_serve compile_client generate_client js_dep store_health
