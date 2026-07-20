# slopp reference (load on demand; the SKILL.md core is what you need daily)

## Reading write results, in full

- Green + quiet ⇒ terse `{:ok true :delta "d42" :test {:ran 2 :pass 5
  :status :green :scope :affected} :affected 2}`. `:scope :affected` = only
  the tests exercising your change ran; `:all` = the full suite. `:status`
  is explicit — never infer red/green from shape. `:verbose true` for the
  full map.
- Red ⇒ `:test :failures` carries expected/actual/exception per failure —
  diagnose from the response. Stack traces cite the same `file.clj:line`
  coordinates `query_source` shows.
- `:fresh-confirmed true` — the red survived a fresh image: it's real.
- `:staleness-detected true` — the red was image staleness, already healed.
- `:warnings` — `!`-naming violations YOU introduced (mutating fns must end
  in `!`); fix with `edit_rename` using the `:suggest`.
  `:existing-warnings n` counts older ones.
- `:untested true` — nothing exercises the form you changed; add a test.
- `:affected` — which tests re-ran (`:all` = no trace map yet; one
  `test_run` builds it and narrowing kicks in).
- `:manual` (change_signature) — references it could NOT rewrite
  (higher-order uses); handle them yourself (edit_subform).
- A merge conflict's `:ours`/`:theirs` IS current live source — resolve
  straight from the payload.
- `:hint` — a one-line workflow nudge; each fires at most once per session.

## The oracle: answering questions by running code

`query_eval` is your REPL — call any function (effectful ones included) to
observe real behavior instead of reading callers. It can't define or modify
code; runtime state it perturbs is disposable (`restart` rebuilds).

- "What does this return for X?" → `query_eval "(my.ns/f X)"`
- "What flows through f at runtime?" → `query_observe {ns name code}` —
  captures each call's args/return while your driver code runs.
- "What does this macro do?" → `query_macroexpand {code}`
- "Why does this test fail?" → the `:failures` in the write result, then
  `query_eval` to probe.
- Image feels wrong (weird arity errors, unbound vars)? → `restart`; cheap.

## Effects, deps, escape hatches

- `deps_add` a library, then require it normally (hot classpath add).
- Calls into an opaque dep count as effectful — name callers `!`, or
  `deps_pure` the var/namespace/lib, or tag the form `^:reads` (reads take
  no bang). `^:unsafe` opts one form out of the dialect gate (macros,
  `binding`, `eval` …) — the greppable last resort; doesn't silence `!`.
- **Purity tiers** (the functional-core gate): `module_purity {module tier}` —
  `:pure` (referentially transparent: no mutation, no `rand`/`slurp`),
  `:internal` (may mutate IN-PROCESS state — a memo, a registry — but touches
  nothing outside the process), `:external` (IO: files, subprocesses, network,
  db). Once declared, the write gate HARD-REFUSES a form exceeding its tier.
  Undeclared = `:external` (ungated). Scope is a namespace PATH and the most
  specific declaration WINS, so a pure core below an effectful module is
  declarable; declaring VERIFIES the code already there and refuses a tier the
  existing forms exceed. Read via `query_depends {modules true}`, or
  `{modules true, on "app.core.calc"}` for one namespace's surface + tier.
  The axis is internal/external because it decides how a thing must be TESTED:
  external needs isolation, internal needs a cache/state reset, pure needs
  nothing. Every memo must go through `slopp.cache` — that is what keeps
  `:internal` checkable. (`:reads`/`:effects` are legacy spellings of
  `:internal`/`:external`.)

## History as a query surface

`query_form_at` (a form at any past delta/milestone — old names resolve) ·
`query_status_at` (was-green-at) · `query_search_history` (which prompts
touched X → the forms) · `query_form_history {format "text"}` (one form's
life as diffs) · `query_changes {from to}` (net diff between any two
points, e.g. milestone targets from `query_commits`).

## Habits that pay

- Outline before source; batch source reads (`query_source {targets}`).
- One logical change per write, with a real `prompt` — history quality is
  intent quality.
- Multi-form intent = `edit_group`; signature change = `change_signature`.
- Tests land in the same group as the fn (red-first); `test_run {:only}`
  while iterating; full sweep = `test_run` with no ns — never loop it.
- `done {label}` when you believe your changes are complete — it runs the AFFECTED tests itself (no test_run needed first), plus tidy + lint;
  `commit_point {description}` at milestones (green-gated).

## Shipping

`build {dir}` materializes plain files (absolute path, outside the repo);
with `main` it also emits a GraalVM native-binary recipe
(`./build-native.sh`). Repo sync, uberjars, config files, CI: the
`slopp-setup` skill.
