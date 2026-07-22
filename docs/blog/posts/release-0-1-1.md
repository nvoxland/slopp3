---
date: 2026-07-12 15:37:00
slug: slopp-0-1-1
categories:
  - Releases
---

# slopp 0.1.1 -- java -jar with no arguments

Small release, same day as 0.1.0. The theme is that a jar's entry point is
config, and config belongs in the store.

<!-- more -->

- **`java -jar slopp.jar` now boots the current directory** with zero
  arguments. The manifest is tracked jar config: `META-INF/MANIFEST.MF` names a
  generated launcher, and `X-Slopp-Main` names the fn it delegates to. The
  entry point is store-tracked semantic config, not a hardcoded class.
- **`file_get` and `file_history`** complete the generic file surface, so
  tracked non-code files get the same per-change history forms get. `build`
  materializes manifest files too.
- **`edit_trivia`** edits the comment and blank-line run before a form, replayed
  through the anchor form's id rather than a line offset.
- **`edit_subform` gains text mode** for strings and docstrings, which
  structural matching cannot address on their own.
- **The `--live` watcher is daemonized**, so the JVM exits with the program
  instead of hanging on a watcher thread.

0.1.0's own release went out green on both CI faces -- the suite from files and
the suite from a store the pushed code built by importing itself -- and the
native proof passed.
