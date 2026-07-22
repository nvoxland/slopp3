# Verification

Every write runs the same gauntlet before anything is committed. A failed gate
commits nothing -- there is no partially applied state to clean up.

1. **Parse** -- exactly one top-level form on the edit path.
2. **Dialect gate** -- no user macros, no `eval` family, no `binding`, no
   metadata mutation, no hand-written `declare`. See
   [the dialect](../reference/dialect.md).
3. **Rule gates** -- module edges, purity tier, boundary schemas, key hygiene,
   and the rest of the [rule registry](#rules).
4. **Compile** -- the candidate namespace hot-loads into the live image.
5. **Cold-load check** -- would a fresh JVM load this?
6. **Lint** -- new error-level clj-kondo findings refuse the write;
   pre-existing ones do not block.
7. **Tests** -- the tests a trace map says exercise the touched forms.

## Reading the result

Green and quiet is terse:

```clj
{:ok true :delta "d42"
 :test {:ran 2 :pass 5 :status :green :scope :affected}
 :affected 2}
```

`:status` is explicit. Never infer red or green from the shape of a result.

A write's `:status` also tells you which tier actually ran:

| `:status` | Meaning |
|---|---|
| `:green` | The impacted tests ran and passed. |
| `:partial` | Some ran; impacted `^:external` ones were deferred (`:external-pending` names them). |
| `:unverified` | Nothing ran. `:reason` says which kind. |

`:unverified` has three reasons worth telling apart:
`:all-impacted-external` (by design -- the done point runs them),
`:no-covering-tests` (yours to fix), and `:scope-ran-nothing` (a slopp bug,
please report it). A run that executed nothing also carries `:coverage :none`.

Other keys you will see:

- `:failures` -- expected, actual, and exception per failing test. Diagnose from
  the response; you do not need another call.
- `:implicated` -- which of *your* changes each failing test exercises. Start
  debugging there.
- `:carried-errors` -- stale callers left broken by a signature change. Normal
  mid-episode; catch them up in the next writes.
- `:red-first` -- vars a new test referenced that do not exist yet, stubbed in
  the image so they fail honestly.
- `:still-red` / `:went-green` -- which reds persisted and which cleared.
- `:staleness-healed true` -- the red was image staleness, already healed.
  `:image-healed true` -- the image was rebuilt under you.
- `:fresh-confirmed true` -- the red survived a fresh image, so it is real.
  This one rides the full result, which a red with failures returns.
- `:untested true` -- nothing exercises the form you changed.
- `:warnings` -- `!`-naming violations you introduced; `edit_rename` with the
  suggested name. `:existing-warnings` counts older ones.
- `:drift` -- a finding surfaced on the write so you see it before `done`.

Writing an `^:external` test is `:partial` or `:unverified`, never green. To
watch it go red first, `test_run {only ["ns/the-test"]}` — a named
`^:external` target runs in its own tier automatically, one serial fresh JVM.

## Test selection is measured

The image instruments the dependency closure of a test run and records which
forms each test actually reaches. That trace map is what makes `:affected` a
small number.

Before any run has built the map, `:affected` is `:all` -- one `test_run` and
narrowing kicks in. Narrowing decides per form, so one untraced form does not
collapse the whole selection back to everything.

`:untested` is therefore a measurement, not a heuristic. `draft_test
{ns name code}` will draft a `deftest` from calls it observes while your driver
code runs.

## The cold-load gate

Your work hot-loads into a live image where the vars already exist. That image
will happily run code a *fresh* load cannot load at all -- and a fresh load is
what a boot, a restart, a clone, and the external test tier all do.

The gate refuses two shapes:

- A form referencing a later form in the same namespace.
- A require **cycle** between namespaces: `would not cold-load -- require CYCLE:
  a -> b -> a`.

Both are invisible to in-image verification by construction, which is why a
write can be refused while every test passes. A cycle usually means a require
that is no longer used: drop it, or move the shared code somewhere both sides
can depend on.

## Reds, staleness, and the restart backstop

A red is cross-checked against a fresh image when staleness is plausible, so
`:fresh-confirmed` and `:staleness-healed` distinguish a real failure from a
warm-image artifact. If the image feels wrong in some other way -- odd arity
errors, unbound vars -- `restart` rebuilds it from the store. It is cheap; state
it perturbs is disposable by design.

Images self-terminate when their parent dies, so a crashed server does not leak
JVMs.

## Rules

Write-time and done-time checks live in one declarative registry rather than
being hand-wired per gate.

- `query_rules` lists the catalog: what each rule checks, its grain, and its
  severity.
- `query_rule_telemetry` shows fire rates and discharge patterns -- which rules
  actually bite, and how people get past them.
- Severity is per-store tunable, so a project can dial a rule between advisory
  and blocking.

Rules that are merely advisory get walked past, so slopp's own store runs the
catalog blocking. A new project inherits sensible defaults and can loosen them.

## Running tests yourself

You should rarely need to. `test_run` is for spot-checking one namespace or one
test mid-flight, not a ritual after a write:

```clj
test_run {only ["invoice.total-test/line-total-applies-discount"]}
test_run {external true}
```

Red runs return `:all-failing {file [tests]}` plus `:themes`, clustered causes.
Read those before drilling into individual failures.

Two things not to do, ever: re-running the suite externally to double-check
work that already verified itself, and cloning or worktree-ing the repo to
check up on an agent. When someone asks how to verify, hand them the command
(`slopp --call test_run`, `query_commits`) rather than executing a dry run.

## A repro can be too minimal

Red-first only protects you if the test is red for the reason you think.
Stripping a bug to its smallest case can strip out the thing that triggers it,
and then a green test reads as "not the cause" when it means "not reproduced".

A real one from slopp's own history: a crash inside a `sort` was minimised to a
single-element collection -- and sorting one element never calls `compare`, so
the test passed over a live bug and sent the diagnosis in the wrong direction.

When a repro comes back green, that is a result to explain, not a fact to
accept. Check the mechanism is still present before concluding the cause is
elsewhere, and use `query_eval` to look at what the code actually sees instead
of bisecting features by intuition.
