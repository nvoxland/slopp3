# The image is the oracle

Static analysis tells you what code *says*. A running process tells you what it
*does*. slopp keeps one running at all times and treats it as the authority.

## Three levels

- **L1, characters.** Where text CRDTs live. slopp does not.
- **L2, the syntax tree.** Where structural editing lives. The store lives
  here.
- **L3, semantics.** Bindings, references, arities, behaviour. Where the agent
  thinks.

Most tooling tries to reach L3 by analysing L2 harder. That works until it
meets a macro, a dynamic dispatch, or a value whose shape is only ever
determined at runtime -- and then it either guesses or gives up.

slopp reaches L3 by running the code. The image is a JVM subprocess with an
nREPL that slopp owns, holding your program with every namespace loaded.

## What that changes

**Verification is observation.** A write hot-loads into the image and the tests
that reach it run there. The answer is not "this should compile"; it is "this
compiled and these assertions passed, just now."

**Test selection is measured.** The image instruments the dependency closure of
a test run and records which forms each test actually touches. That is the
trace map. It is why a write re-runs three tests rather than four hundred, and
why `:untested` is a fact about coverage rather than a guess from requires.

**Questions get answers instead of inferences.** "What does this return for
that input" is `query_call`. "What actually flows through this function" is
`query_observe`, which captures args and returns while driver code runs. "What
does this macro expand to" is `query_macroexpand`, at runtime, which dissolves
the problem macros normally create for analysis.

**Polymorphism is visible.** Multimethods and protocols are where static call
graphs go blind. Instrumenting the dispatch point means both tracing and
reference tracking see through them.

## Restart is the backstop

A long-lived image accumulates state, and a warm image can happily run code
that a fresh load could not. slopp treats that as a first-class hazard rather
than an occupational hazard.

A red result is cross-checked against a fresh image when staleness is
plausible, so results carry `:fresh-confirmed` or `:staleness-healed` and you
know which kind of failure you are looking at. `restart` rebuilds the image
from the store whenever it feels wrong; the state it destroys is disposable by
construction.

And the [cold-load gate](../guide/verification.md#the-cold-load-gate) exists
precisely because the image is *too* forgiving: a form that references a
later-defined one loads fine in a warm image and kills a fresh boot. That class
of bug is invisible to in-image verification by construction, so it gets its
own gate.

## The limits

The image is bare -- Clojure and nREPL -- until a store declares its own
libraries. Third-party code is opaque to analysis: slopp can run it, but it
cannot see inside it, so calls into it count as effectful until someone asserts
otherwise with `deps_pure`.

And the oracle only knows what you run. A code path with no test and no driver
expression is as unknown to the image as it is to a linter. That is what
`:untested` is telling you.
