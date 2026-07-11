# Verification

The oracle must never return a false verdict. Everything here serves that.

## The pieces

1. **Traced runs (`slopp.rt/traced-run`, injected into every image).**
   Temporarily wraps fn vars of the store namespaces (alter-var-root,
   restored in `finally`), runs each test var individually, returns
   `{:summary {... :failures [...]} :trace {test-sym #{form-sym}}}`.
   - Failure details (F1) are captured by rebinding clojure.test's dynamic
     `report` multimethod — **`:test` counting stays in `test-var` itself;
     the custom report must inc `:pass/:fail/:error` counters**. Bounded:
     ≤20 failures, 400-char values, Throwables as class+message.
   - clojure.test's `:actual` for an `is` failure is the failed predicate
     form, e.g. `(not (= 5 -1))` — keep it; it's more informative than the
     bare value.
   - **Sampling limits (accepted):** value-captured references
     (`(def g (comp f inc))`) bypass var wrapping; multimethods and macros
     are not instrumented; unexercised paths are unobserved.
2. **The trace map (session `:test-map`).** Flat
   `{qualified-test-sym #{qualified-form-sym}}`, merged in on every traced
   run, carried across renames (`rename-in-trace`). Powers **affected-test
   selection**: edit form F → run only tests whose set contains F (or F
   itself if F is a test). No trace info → conservative full-ns run
   (`:affected :all`).
3. **Smart red diagnosis (`diagnosed-run!`, D5.1).** Reds cross-check on a
   fresh image ONLY when staleness is plausible: reload-signature failures,
   an unexplained flip (failing test's traced set disjoint from the
   just-edited forms — this also catches value-capture staleness because
   captured calls bypass the trace), or missing/truncated trace info. A red
   clearly caused by the edited forms returns immediately as
   `{:diagnosis :genuine}` — one run, no restart. Cross-check outcomes:
   red→green ⇒ `:staleness-detected`; red→red ⇒ `:fresh-confirmed`.
   Compile-gate failures heal likewise: refresh + one load retry
   (`:image-healed true` on the write result). `test_run {:fresh true}`
   forces a faithful single run. Every write path passes its `:edited` qsym
   set into `run-verification!` — keep that plumbing when adding write ops,
   or reds regress to conservative restarts.
   **Integration tier (P4-deps M5):** a `^:integration`-tagged deftest (tag
   on the test NAME) is SKIPPED by the fast per-write path (`traced-run` /
   `traced-test-run` take `skip-integration?`, default on for edits) so an
   external-system test — a DB dep behind a capability — doesn't fire on
   every keystroke and a red one never blocks an edit. `test_run`,
   `checkpoint`, and `commit_point` pass `:include-integration? true` and run
   them. It's a plain runtime-meta filter; `affected-tests` is unaffected
   (skipped tests just never enter the trace).
4. **Warm spare.** `{:warm-spare? true}` keeps a `future`-started image
   warming; `fresh-image!` swaps to it (<~3s vs ~6-8s cold boot) and starts
   the next spare. On for the MCP server. `close!` derefs and stops the
   spare — never leak child JVMs.
5. **Provenance.** Every verification lands as a `:verify` delta with the
   summary (incl. `:failures`, `:staleness-detected`/`:fresh-confirmed`).
6. **Cold-load gate (S1b, `edit/cold-load-errors` → `index/forward-refs`).**
   Hot-loading into the LIVE image cannot see forward references — the vars
   already exist there — so a write could commit a namespace that
   boot/restart/a fresh image cannot load (found the hard way: a
   replace-before-add edit_group; the next `fresh-image!` crashed). Every
   content/order write path (`rebased-write!` both branches, `edit-group!`,
   `move-form!`) statically checks the CANDIDATE's rendered namespaces
   before touching the image: any same-ns var usage positioned (row, col)
   before the var's first definition-or-declare is refused with the fix
   options (reorder / declare). Pure kondo analysis, memoized on content —
   effectively free next to the ns-warnings pass. Known over-approximation:
   syntax-quoted own-ns symbols count as usages (a declare satisfies).
   `ingest!`/`ns_create` are exempt — a brand-new ns cold-loads for real in
   the image. Merge replay is NOT yet gated (follow-up).

## Gotchas

- `clojure.test/*test-out*` does NOT follow `with-out-str` — this is exactly
  why rt captures report events instead of parsing output.
- Image stack traces cite VFS file+line (F6): namespaces load via nREPL's
  `load-file` op with `render/ns-path`; hot-reloaded forms go through
  `edit/hot-load-form!`, which pads the form with newlines to its current VFS
  row. If you add a new write path, use `hot-load-form!` — a plain `eval!`
  regresses traces to NO_SOURCE_FILE.
- Multi-form refactors must go through `edit-group!` (F2): single-form edits
  verify immediately, so sequencing them hits a meaningless mid-refactor red +
  a diagnostic restart between edits (measured: −49% calculator wall time when
  the two-form fix moved to a group).
- Don't run expensive assertions about timing in tests except with generous
  bounds (warm-spare test asserts <3000ms swap vs ~6-8s boot).
