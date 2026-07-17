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
   **Persisted across sessions (Q3):** every traced run writes the map to
   store meta (`persist-trace!`); `open!` loads it back pruned to
   still-existing tests/forms (`load-trace`), so a fresh session — or a CLI
   one-shot — starts with narrowing warm instead of `{:ran 0 :affected
   :all}`. Last writer wins; staleness is safe because pruning drops moved
   names and `sync-with-journal!` re-prunes (and re-persists) on foreign
   commits. A zero-test verification result says `:coverage :none` (Q8) —
   green must never be inferable from an empty run.
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
   before the var's first definition-or-declare that the pipeline cannot
   auto-resolve is refused with the fix options.
   **Auto-avoid-declare (2026-07-16): the pipeline OWNS form ordering and
   declares — the agent never writes `(declare …)`.** Before the gate can
   refuse, `rebased-write!` and `edit-group!` wrap the candidate in
   `edit/resolve-cold-load`, which makes the ns cold-load one of two ways,
   both **SILENT** (no result key — form ordering is a file concept the agent
   must not hold; provenance lives in the deltas):
   - **Reorder** (acyclic forward ref): definitions moved above their callers
     (`refs/cold-load-order` = Kahn over THE reference graph → `store/reorder-to`,
     minimal replayable `:move` deltas).
   - **Auto-declare** (a genuine cycle — mutual recursion, no legal order):
     insert a MARKED `^{:auto-declare "<why>"} (declare …)` for the cycle
     members (built via the raw parser, bypassing the edit gate). The marker's
     value is the why (markers-carry-their-why); `fix-declares!` at `done`
     removes it once the cycle breaks (silently — no `:declares-fixed` report).
   Hand-written declares are consequently REFUSED on the edit path (`parse-form`)
   with teaching — the pipeline owns them (see `dialect.md`). `move-form!` (an
   explicit reorder command) and merge replay are NOT auto-resolved: an illegal
   move / merge-interleaved forward ref still refuses, since the agent (or the
   merge) explicitly commanded that order. Pure kondo analysis, memoized on
   content — effectively free next to the ns-warnings pass. Known over-approximation:
   syntax-quoted own-ns symbols count as usages (a declare satisfies).
   `ingest!`/`ns_create` are exempt — a brand-new ns cold-loads for real in
   the image. Merge replay IS gated (`merge-into-session!`, so both
   branch_merge and merge_from): two individually-legal lines can interleave
   into a forward ref — e.g. ours deletes a now-satisfied `declare` while
   theirs grows a new forward use of it — and the merge is refused before
   the image is touched.

7. **Isolated tier (`isolated-test-run!`).** Builds the store to a temp dir
   and shells `clojure -M:test` (cognitect runner) — the ONLY tier that
   executes `^:isolated` tests, because they spawn images/subprocesses and
   would recurse in-image. `:ns`/`:only` narrow the run (cognitect `-n`/`-v`)
   and a red run returns `:failing [{:test :detail}]` blocks parsed from the
   output (`parse-test-failures`) — targeted red/green loops without
   rebuilding by hand (Q2).
   **The skip is REPORTED, not silent (`traced-run!`, 2026-07-16).**
   `slopp.rt/traced-run` has dropped `^:isolated` tests unconditionally since
   d980 (`true (remove (comp :isolated meta))`) — they have never executed
   in-image, and that half was never broken. What WAS broken: the skip was
   SILENT. An `^:isolated` test entering the impacted set — most easily the
   just-added/edited test var itself, or any test in a whole-ns fallback run —
   was dropped, and the summary reported the OTHER tests' green with no
   mention that the one you just wrote never ran. (Repro: adding an
   `^:isolated` deftest returned `:test {:ran 2 :pass 5 :status :green}` — two
   unrelated tests; the new one was dropped, and the isolated run was red.)
   Now `traced-run!` partitions the scope with `session/test-var-tiers` and
   attaches the deferred ones as `:isolated-pending` on the summary (compressed
   to count+sample; suppressed at the done boundary, which runs them for real).
   So a per-write `:test` never claims green while quietly dropping the test
   the agent is working on.
   NOTE the redundancy: `traced-run!`'s tier filter duplicates rt's own —
   belt-and-braces, since only the REPORTING was missing. Worth collapsing if
   this area is touched again (the reporting needs the partition; the filtering
   does not).
8. **The isolation gate (Q7, `edit/isolation-refusal`).** Every replace/add
   path refuses an UNTAGGED deftest that calls a spawning var
   (`edit/spawning-vars`, resolved through the ns's require aliases via
   `edit/require-aliases`) — the refusal names the fix (`^:isolated`).
   Without the gate such a test hangs in-image verification with nothing
   pointing at why. `ns_create`/import stay exempt (whole-ns ingestion is
   the tolerant path).

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
