# Why slopp

Coding agents work through an interface built for humans: a filesystem, a text
editor, and a diff. That interface loses two things.

It is not semantic. `grep` and string-replace do not know what a symbol is.
When a patch fails to apply, or applies in the wrong place, nothing in the
stack noticed -- the agent finds out later, if at all.

And it loses the *why*. A file records the result of a change. It does not
record which operation produced it, what the agent was trying to do, or what
the person actually asked for. That information exists at edit time and is
thrown away.

slopp takes the other branch: give the agent a semantic operation API, store
the code as semantic units, and keep a live process running so questions about
behaviour can be answered by running the code instead of reading it.

## One unit, all the way down

The top-level form is simultaneously the unit of:

| Layer | Unit |
|---|---|
| Editing | one form |
| Storage and merge | one form |
| Hot-reload (redefining a var) | one form |
| Verification (which tests re-run) | one form |
| Provenance (one delta, one intent) | one form |

That coherence is the whole design. Because a `defn` is also the reload unit,
a write can be compiled and tested in the time it takes to send it. Because it
is also the merge unit, two agents editing different functions in the same
namespace never conflict. Because it is also the provenance unit, "who changed
this and why" is a lookup rather than an archaeology project.

In a language where the reload-and-verify unit is a whole-package build, none
of this is available. Clojure gets it for free, which is why slopp is a
Clojure system.

## What that buys, concretely

**Writes fail loudly at the moment they are wrong.** A write parses, passes
the dialect gate, compiles into a running image, passes a cold-load check
(a form referencing a later one loads fine in a warm image and kills a fresh
boot), passes lint at error level, and then runs the tests that a trace map
says exercise the touched forms. Anything that fails commits nothing.

**Test selection is measured, not guessed.** The image traces which tests
touch which forms, so a write re-runs the three tests that matter rather than
the whole suite -- or tells you honestly that nothing covers the form you just
changed.

**History has intent in it.** Deltas carry the agent, its one-line reason, and
the enclosing turn, which holds the user's verbatim ask. Abandoned work that
gets reverted is recorded as a searchable dead end instead of vanishing, so
the next session can find out that someone already tried that and why they
dropped it.

**Architecture is checked, not documented.** Module edges are declared and
enforced; deep namespaces are package-private; a namespace declared `:pure` will
refuse a write that reaches an effect. The rules run on every write, so drift
does not accumulate between reviews.

**Agents stop spending tokens on the wrong things.** Reads are name-addressed
and outline-by-default, re-reading an unchanged view returns a stub, and the
response never contains a file path or a line number, so there is nothing to
tempt an agent into thinking in files.

## What it costs

- Java 21+, and a Clojure dialect with real subtractions: no user macros, no
  `eval`, no `binding`, no `alter-var-root`, no hand-written `declare`. See
  [the dialect](reference/dialect.md).
- Your source is not on disk. That is the point, but it means your editor,
  your linter's editor plugin and your usual `rg` habits do not see the code.
  `build` materializes files when tooling needs them; a git projection makes
  the code browsable and reviewable on GitHub.
- It is early. slopp has been developed through slopp since its own store
  flip, so the paths its author uses daily are solid and the ones nobody has
  walked are not.

## What it is not

slopp is not an editor, an agent, or a model. It is the surface an agent works
through, and it works with any MCP client -- the [Claude Code
plugin](getting-started/install.md) is the path with the least setup.

It is also not a wrapper around file edits. There is no reconciliation step
between a store and a working tree, because there is no working tree to
reconcile with.

Next: [install it](getting-started/install.md), or read
[the concepts](getting-started/concepts.md) first.
