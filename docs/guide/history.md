# History and provenance

Every write records what changed, who changed it, why they said they were
changing it, and what the person actually asked for. None of that is
reconstructed later -- it is captured at edit time, when it is still true.

## The stack

- **Write** -- one verified delta, carrying the agent, the timestamp, and the
  one-line `prompt` the agent gave.
- **Episode** -- the work between two done points. Inferred; nothing is stored
  and agents never plan the grouping.
- **Turn** -- one user ask, verbatim. Opened by `turn_begin {intent}`, closed by
  `turn_end`.
- **Milestone** -- `commit_point`. Named, green-gated, a git commit.

Turns are automatic under the Claude Code plugin: a prompt hook drops the
verbatim ask where the server picks it up, and identity comes off the harness
session id. You should never need to call `turn_begin` yourself unless a write
is refused with `no open turn` -- which happens once, in a brand-new project
that has no store yet.

Without the hooks (a bare MCP client), open the turn yourself with the user's
words, not your paraphrase. The verbatim ask is the part that has value later.

## Asking history questions

`query_history` routes by its arguments:

| Call | Answers |
|---|---|
| `query_history {}` | recent episodes |
| `query_history {ns name}` | one form's whole life |
| `query_history {ns name at}` | that form as of a past delta or milestone |
| `query_history {at}` | was it green at that point |
| `query_history {contains "invoice"}` | which asks touched this |
| `query_history {dead_ends true}` | abandoned explorations |
| `query_history {dead_ends "invoice.total"}` | ones that touched this namespace |

Old names resolve, so time-travelling to a form that has since been renamed
works.

`query_changes {from to}` is the diff between any two points -- every form's
`:was` and `:now`, `format: "text"` for line diffs. `from "start"` covers the
lifetime. This is how you read what changed, not `git diff`, and never the raw
`store.db`.

## Summaries and handoffs

`report` is the composite: the user's verbatim asks per turn, milestones,
changes with their recorded reasons, dead ends, suite state, and the code
itself. Narrow it with `report {contains "invoice"}`.

It is a *terminal* call, not a starting point. One read already carries what
four separate history queries would re-derive, and re-deriving it is a
measurable waste of a session's budget.

## Dead ends are first-class

When an approach does not work out, revert it with a `prompt` saying why:

```clj
undo {to "last-commit"
      prompt "abandoned the queue-per-tenant design -- ordering guarantees
              cannot survive a shard split"}
```

That records a searchable dead end. Six weeks later someone asking
`query_history {dead_ends "billing.queue"}` finds out the idea was tried and
why it was dropped, instead of spending a day rediscovering it.

Reverting before a `commit_point` keeps the milestone history clean: the dead
end lives in `dead_ends`, not in the commit log.

## Provenance survives refactoring

Reference records are anchored to stable form ids rather than to lines or
columns, and history follows renames. Moving a form to a new namespace, or
renaming it three times, does not orphan its past.

Prose follows too: a rename or a move rewrites qualified references in
docstrings and comments, and a stale reference reports where the thing went.

## Reviewing

`review_scan` is whole-codebase review triage: it ranks what deserves
attention rather than dumping a diff. Combined with `query_changes` and the
recorded reasons, a review reads as "here is what was attempted, here is what
landed, here is what is unverified" rather than a wall of hunks.

The `slopp-review` skill drives this for agents.
