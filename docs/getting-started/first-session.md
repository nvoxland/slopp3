# Your first session

You do not call slopp's tools yourself -- an agent does, over MCP. This page
walks through what those calls look like for a small piece of work, so the
results you see scrolling past make sense.

Open a project directory with the plugin installed and ask for something. The
agent reads the `slopp` skill before its first call, so it already knows the
loop.

## 1. Orient

```clj
session_brief {}
```

One small call returns every namespace with its form names, the recent
milestones and what was asked for at each, git alignment, and any findings
left over from the last session. That is the whole orientation step -- there
is no "list the files" phase, because there are no files.

## 2. Write the failing test first

Red-first is native here. A test that names a function which does not exist
yet is not an error; it lands as an honest red.

```clj
ns_create {ns "invoice.total-test"
           requires ["[clojure.test :refer [deftest is]]"
                     "[invoice.total :as total]"]}

edit_add_form {ns "invoice.total-test"
               source "(deftest line-total-applies-discount
                          (is (= 90.0 (total/line-total {:qty 1 :price 100.0 :discount 0.1}))))"
               prompt "line totals should apply the per-line discount"}
```

The response comes back red, with `:red-first` naming the vars it stubbed:

```clj
{:ok true :delta "d17"
 :test {:ran 1 :pass 0 :fail 1 :status :red}
 :red-first ["invoice.total/line-total"]}
```

## 3. Implement

```clj
ns_create {ns "invoice.total"}

edit_add_form {ns "invoice.total"
               source "(defn line-total
                         \"Extended price for one line, after its discount.\"
                         [{:keys [qty price discount]}]
                         (* qty price (- 1 (or discount 0))))"
               prompt "implement line-total against the spec"}
```

```clj
{:ok true :delta "d18"
 :test {:ran 1 :pass 1 :status :green :scope :affected}
 :affected 1}
```

`:scope :affected` means only the tests that exercise the form you touched
ran. Nobody selected them by hand -- the image traced which tests reach which
forms.

Form order is not your problem. You can write `line-total` after the code that
calls it; the pipeline moves definitions above their callers, and mints a
marked `(declare ...)` itself for genuine mutual recursion. Hand-written
`declare` is refused.

## 4. Keep going, one small write at a time

Work like a REPL. Mid-episode reds are normal state, not a failure to clean
up: change a signature and the stale callers ride back as `:carried-errors`
until you catch them up in the next writes.

To change something inside a large form without resending it:

```clj
edit_subform {ns "invoice.total" form "line-total"
              match "(or discount 0)"
              source "(max 0 (or discount 0))"
              prompt "clamp negative discounts"}
```

A missed or ambiguous match is not a silent no-op. It returns the form's
current source in `:source-now`, so you correct from the error and resend.

## 5. Say `done`

Call it at **every** point you think a piece of work is finished -- not once at
the end.

```clj
done {label "line discounts"}
```

It runs the whole in-image suite plus the `^:external` tests your changes
impact, normalizes and lints what you touched, checks for dead public surface,
marks the episode boundary, and reports its findings. It reports rather than
refuses, so a finding you cannot fix right now never deadlocks you -- but a red
`done` stands until new work supersedes it.

## 6. Milestone

```clj
commit_point {description "line totals apply per-line discounts"}
```

That is the grain a human diffs and reverts to. It runs `done` and gates on
its verdict; it has no checks of its own, so there is exactly one bar. In a
git checkout it also mirrors the store's history into local git as
`slopp/<branch>`.

## What you never do

- Run the test suite to check the agent's work. Every write already ran the
  tests that cover it, and `done` ran the suite. If you want to see for
  yourself: `slopp --call test_run`.
- Read a diff out of `git diff`. `query_changes {from "start"}` gives every
  form's before and after across any span, with the recorded reason for each.
- Open `store.db` in a SQLite client. Everything in it has a tool.

Next: [the concepts](concepts.md) behind what you just saw.
