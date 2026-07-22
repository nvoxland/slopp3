# Editing forms

Every write takes a `prompt`: one line saying why. It is not a formality --
history quality is intent quality, and the string ends up in
[`query_history`](history.md), in review triage, and in the next session's
brief.

## Picking the tool

| Situation | Tool |
|---|---|
| New namespace, grown with TDD | `ns_create {ns requires}` |
| New namespace, source already written | `ns_create {ns source}` |
| Add or drop a require | `ns_add_require` / `ns_remove_require` |
| New form | `edit_add_form` (`before` anchors placement) |
| Replace a whole form | `edit_replace_form` |
| Small change inside a big form | `edit_subform` |
| Change a function's signature | `change_signature` |
| Rename one form | `edit_rename` |
| Rename a concept everywhere | `rename_sweep` |
| Pull a helper out | `edit_extract` |
| Move forms to another namespace | `edit_move_forms` |
| Reorder, delete, revert | `edit_move` / `edit_delete_form` / `edit_revert` |
| Comments between forms | `edit_trivia` |
| Change a form's name metadata | `edit_subform {text: true}` on the `defn` head |

Never rename by editing call sites, and never hand-edit an `ns` form.

## edit_subform

The workhorse for changes inside a large form:

```clj
edit_subform {ns "invoice.total" form "line-total"
              match "(or discount 0)"
              source "(max 0 (or discount 0))"
              prompt "clamp negative discounts"}
```

Matching is structural, so it is whitespace-insensitive and cannot half-apply.
Three modes:

- Default: match one subform, or one *pair* on a pair boundary -- a map entry,
  a binding, a `case`/`cond` clause -- which addresses the pair as a unit.
- `text: true`: strings, docstrings, and name metadata (`^:export`,
  `^{:malli/schema ...}`), which structural matching cannot address on their own.
- `where: {key value}`: address the unique **map** containing those entries.
  Good for registry rows, where you know the content but not the exact text.

Writes are optimistic: compose a match from the brief or a slice and send it. A
missed or ambiguous match returns the form's current source in `:source-now`,
so you correct from the error rather than re-reading first.

If a subform edit is refused with `unresolved-symbol` or `invalid-arity`, the
change spans more of the form than you matched -- a binding and its use, a loop
and its `recur`, an arglist and its body. Widen the match, or replace the whole
form. Two edits to one form is one edit; there is no cross-form batch tool.

## Signature changes

```clj
change_signature {ns "invoice.total" name "line-total"
                  source "(defn line-total [line currency] ...)"
                  calls "(total/line-total $1 :usd)"
                  prompt "line totals carry a currency"}
```

The new `defn` plus every call site as one atomic intent. `$1..$9` are the
old call's arguments, kept as written. References it could not rewrite --
higher-order uses, mostly -- come back in `:manual` for you to handle with
`edit_subform`.

## Renaming a concept

`rename_sweep {from to}` rewrites namespaces, vars, keywords and prose across
the whole store in one call, with one verification. Whole-word only, so
`region-ish` survives a `region` sweep.

Renaming a keyword also requalifies `{:keys [x]}` destructuring, which names
its key as a *symbol* and so is invisible to a text pass.

Two things to know before you run one:

- **`dry-run` first.** It writes nothing and splits the hits into `:in-code`
  and `:in-strings`. The string bucket needs an eye, because a sweep rewrites
  keyword text inside string literals -- and a test fixture is data, not prose.
- It rewrites prose *describing* the rename too. A comment explaining
  `a -> b` comes out saying `b -> b`.

If a live gate enforces the thing being renamed, you need two phases: teach the
gate to accept both spellings, sweep, then tighten. A one-shot sweep is refused
at the first form it re-tags, because the gate is running from the old compiled
code while the group rewrites it.

A related tool for one common half of this: `edit_requalify {ns name}`
namespaces a function's *option keys* -- its arglist destructuring and every
caller's map literal, together. A store-wide sweep is unsafe whenever the key
means more than one thing (`:dir` naming three different things), and keys are
derived from the arglist, so half a contract cannot end up namespaced and left
reading `nil`. Callers passing a non-literal map are reported under
`:unknown-shape` and left untouched -- no syntactic pass can see through a
binding, and those are yours to check.

## Extracting

`edit_extract {ns from name}` pulls a subform into a new function, computing
parameters from free locals and rewriting the call site.

Address the subform by `at` -- an anchor, its first line or so, which does not
need to parse on its own (`"(let [turn-brackets"`). Prefer that to quoting the
subform whole: transcribing a big body exactly is the work you were trying to
avoid. A non-unique anchor asks you to extend it; a missing one returns
`:source-now`.

## Moving code

`edit_move_forms` relocates a cluster: callers everywhere get rewritten,
requires get added, and `export: true` marks a deep target with outside
callers.

Propose the cluster you want and let the tool close the set. It refuses a
two-way split and names the forms that would be left behind -- "the moved set
calls [x y] (staying)". Add those and retry. The refusal is the analysis;
guessing the seam yourself leaves a cycle.

It drops the requires it orphans on both sides, but only those. A require kept
for its load side effects (a `defmethod` registration) is indistinguishable
from a dead one, so nothing prunes it for you. Leaving a stale require behind
is worse than untidy: a namespace inherits the tier of everything it requires,
so one leftover makes a `:pure` namespace report as depending on the shell.

!!! warning "Moving a form re-resolves its `::auto-keywords`"
    `::foo` reads as `:current-namespace/foo`, so the same text means something
    different after a move -- `::analysis` in `a.b` silently becomes
    `:a.b.c/analysis`. Harmless for a local marker; a live bug when the keyword
    is a persisted key, a map key another namespace reads, a `defmethod`
    dispatch value, or a cache id. Write it out in full when it has to survive
    relocation.

## Form order is not your job

Write forms in whatever order you like. The pipeline moves definitions above
their callers, and for genuine mutual recursion inserts a marked
`^{:auto-declare "why"} (declare ...)` itself. A hand-written `declare` is
refused, because it is always either redundant or wrong.

What you do have to care about is that the result still *cold-loads*. See
[verification](verification.md#the-cold-load-gate).

## References must not hide in strings

Renames, moves, and the dead-surface gate can only see references they can
find. Three carriers make one visible:

- In-process references in data: `#'var` literals.
- Late binding across a load cycle: `(store/late-ref 'ns/name)`.
- Vars invoked from outside -- a CLI, the wire, eval injection -- declare
  `^:entry-point` on the name.

A naked quoted symbol, or a var name inside a string, is invisible to all
three.

## Undoing

`undo {deltas n}` walks back your own writes; `undo {to "last-commit"}` scraps
everything since the last milestone; `undo {to "last-done"}` goes back to your
last done point. It is addressed by delta rather than by name, so it also
restores a form you *deleted* -- the case `edit_revert` structurally cannot
reach, since there is no name left to look up. Forms another session also wrote
in the span are skipped and reported. `episode_revert` scraps the whole
episode.

Always pass a `prompt` saying *why* you are abandoning it. That records the
revert as a searchable **dead end**, so a later session running
`query_history {dead_ends "some.ns"}` finds "someone tried X here and dropped
it because Y" instead of walking it again.

Reverting before a `commit_point` keeps the milestone history clean: the dead
end shows up in `dead_ends`, not in the commit log.
