---
date: 2026-07-13 02:25:00
slug: slopp-0-1-2
categories:
  - Releases
---

# slopp 0.1.2 -- change_signature, and who owns which branch

Three things worth having: an atomic signature change, a one-shot CLI, and a
clear answer to "what happens to my `main` branch".

<!-- more -->

## change_signature

Changing a function's shape used to mean editing the `defn`, then chasing every
call site by hand, with the store red in between. Now it is one intent:

```clj
change_signature {ns "invoice.total" name "line-total"
                  source "(defn line-total [line currency] ...)"
                  calls  "(total/line-total $1 :usd)"
                  prompt "line totals carry a currency"}
```

`$1..$9` are the call site's existing arguments, kept exactly as written.
References it cannot rewrite -- higher-order uses -- come back under `:manual`.
The plan is computed in the refactor layer and executed as one verified group,
and an `invalid-arity` lint refusal now points you at this tool instead of
leaving you to guess.

## Pair-aware subform matching

A two-form match on a pair boundary -- a map entry, a `let` binding, a
`case`/`cond` clause -- now addresses the pair as a unit, replacing the whole
span with a splice. `edit_extract` refuses pairs, which is the correct answer
rather than a surprising one.

## One-shot CLI

```sh
slopp --call query_project
SLOPP_AGENT=ci slopp --call commit_point '{"description":"release 1.2"}'
```

One tool call against the store in the current directory, args as JSON, EDN or
`@file`. It opens a durable turn-enforced session for that single dispatch, so
scripts and CI steps get the same provenance as an interactive session.

## Mixed ownership: slopp owns exactly one branch

This one is a decision more than a feature. slopp pushes and pulls the branch
named by the `git-branch` config -- default `slopp` -- and touches nothing else.
`main` is yours: README, docs, CI workflows, editor config, all managed with
regular git.

The corollary is that only config the **application itself** consumes belongs
in the store. `META-INF/MANIFEST.MF` does, because the jar reads it. A GitHub
Actions workflow does not, because GitHub reads it. Those moved to `main`,
along with the README.

There is also a friendlier onboarding path now:

```sh
git clone <url> && cd <repo>
slopp --main slopp.sync/-main import .
```

Clone normally, then `import` builds the store from the local repo's `slopp`
branch and sets `git-remote "."`. Your working directory stays your `main`
checkout, and you do all origin interaction with regular git on both branches.
