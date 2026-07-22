# Why the form is the unit

Pick any system that stores, merges, reloads, tests, or attributes code, and it
has a unit. Usually those units disagree.

Git merges at the line. Your editor edits characters. The reload unit is a
file, a package, or a whole process. The test unit is a suite, or a file if
you are lucky. Attribution is a commit -- a bag of unrelated hunks with one
message stapled to it.

Every one of those mismatches is a place where work leaks. A rename that spans
four files is one intent stored as four diffs. A one-line change forces a
rebuild of everything downstream. A merge conflict appears because two people
edited adjacent lines of unrelated functions.

slopp picks one unit and uses it everywhere:

| Layer | Unit |
|---|---|
| Editing | one top-level form |
| Storage and merge | one top-level form |
| Hot-reload | one top-level form |
| Verification | the tests that reach that form |
| Provenance | one delta, one stated intent |

## What coherence buys

**Merges stop being about text.** Two agents in the same namespace, editing
different functions, cannot conflict -- there is no shared line range to fight
over. Concurrent edits to the *same* form are a real conflict, which is
arguably the only honest answer.

**Verification gets cheap enough to run every time.** Redefining one var is
milliseconds. Running the three tests that reach it is a second. When
verification costs that little, it stops being a phase you enter and becomes a
property every write has.

**Attribution becomes a lookup.** "Why is this line like this" is a question
git answers with a commit that touched thirty files. Here it is a delta with
the agent, its stated reason, and the enclosing turn holding the user's actual
words.

## Why Clojure

This design needs a language where the text is already a tree, where the reload
unit is a single definition, and where a live mutable image is normal rather
than exotic.

In Clojure, `rewrite-clj` gives a lossless node tree over a tiny, uniform
grammar -- lists, vectors, maps, symbols, literals -- and prints back real
`.clj`. A `defn` redefines a var in the running process. The image is where the
program actually is.

In a compiled language the reload-and-verify unit is a package build measured
in seconds, and there is no live image to ask questions of. The storage half of
this design ports through tree-sitter; the live loop does not, and the live
loop is the thesis.

## The pragmatic dodge

slopp does not implement a fully general tree CRDT. A namespace is an ordered
sequence of identified forms, and a form's value is versioned. Concurrent edits
to different forms never conflict, which covers the common case; same-form
concurrency surfaces as a conflict.

That dodge is why the system exists at all. A general node-level tree CRDT with
correct concurrent moves is a research project. An ordered sequence of forms is
an afternoon, and it captures nearly all of the benefit.

## The honest cost

One unit everywhere means everything must fit that unit. A change that
genuinely spans forms is several writes plus an episode that groups them, not
one atomic operation. Cross-form atomicity exists internally for renames and
signature changes, and it is deliberately not an agent-facing surface -- an
agent that plans multi-form transactions plans badly.

That is a real limitation. It is also the trade that makes everything above
work.
