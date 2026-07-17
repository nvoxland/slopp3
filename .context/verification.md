# Verification

The oracle must never return a false verdict. Everything here serves that.

## The pieces

1. **Traced runs (`slopp.rt/traced-run`, injected into every image).**
   Temporarily wraps fn vars of the store namespaces (alter-var-root,
   restored in `finally`), runs each test var individually, returns
   `{:summary {... :failures [...]} :trace {test-sym #{form-sym}}}`.
   - **`rt/instrument!` + `restore!` are THE instrumentation seam** — ONE
     tracer, two runners. `traced-run` wraps its own per-var loop with it
     (in-image); `slopp.testmain` wraps cognitect's runner with it (external,
     item 7). The runners genuinely differ; the wrapping must never be copied,
     because a second tracer is a second truth.
   - `traced-run` drops `^:isolated` tests **unconditionally, by design** —
     they spawn images and would recurse in-image. That is not an oversight to
     "fix": the external tier is where they run, and it traces them there.
   - **Two copies of `slopp.rt` exist** and each has its own consumer: the
     KERNEL FILE (`src/slopp/rt.clj`, shipped in the uberjar) is what
     `repl/inject-rt!` evals into every image; the STORE namespace is what
     `build!` renders and what slopp's own image actually loads (it wins —
     it loads after the injection). They must agree on **public surface and
     behaviour, not bytes**: `render-ns` drops inter-form blank lines and the
     store copy legitimately carries `^:entry-point` markers the file has no
     use for. Nothing enforces this; they HAVE drifted
     (`.ideas/rt-is-duplicated-file-and-store.md`). Change both.
   - Failure details (F1) are captured by rebinding clojure.test's dynamic
     `report` multimethod — **`:test` counting stays in `test-var` itself;
     the custom report must inc `:pass/:fail/:error` counters**. Bounded:
     ≤20 failures, 400-char values, Throwables as class+message.
   - clojure.test's `:actual` for an `is` failure is the failed predicate
     form, e.g. `(not (= 5 -1))` — keep it; it's more informative than the
     bare value.
   - **Polymorphism (C-wave, 2026-07-17): observed where possible, refused
     where not.** The tracer's unit is the var (`instrumentable?` gates on
     `(fn? @v)`), and each construct lands differently:
     - **multimethods ARE observed** — at the METHOD TABLE. A `MultiFn` is
       `ifn?` but not `fn?`, and replacing the var would break `defmethod`
       (it macroexpands to `.addMethod` on the var's value), so `instrument!`
       wraps each table entry: every dispatched call records the MULTI's qsym
       (both tiers), plus the METHOD's own form key in-image —
       `store/method-registrations` ships `[multi dispatch-sexpr form-key]`
       rows and `traced-run` evals each dispatch sexpr in the image, where
       `defmethod` itself evaled it (source says `String`; the table holds
       `java.lang.String`). External tier records multi-grain only.
     - **protocol calls on INLINE impls bypass the var** — found red: the
       method vars are wrapped, but a protocol call site's inline cache hits
       the interface directly when the target implements it inline (the
       common case). Evidence through the var exists only for extend-based
       dispatch. `defrecord`/`deftype` method bodies are class methods —
       never observable by any var wrap. Constructor evidence (`->R`,
       `map->R` — plain fns) flows to the record's form via D8 names.
     - **callable data is invisible** — `(def valid? #{:a :b})` is `ifn?`, not
       `fn?`, so `api.deps/native-incompatible-deps` reads `:covered 0`
       honestly and forever.
     - **value-captured references** (`(def g (comp f inc))`) bypass the var.
     - macros are excluded by `(not (:macro m))`; unexercised paths are
       unobserved.
     **The narrowing rule that makes the partial evidence safe:**
     `store/method-carrying?` (defmethod, defrecord/deftype, extend-*,
     defprotocol) forms NEVER narrow — `session/affected-tests` returns nil
     for them, the same closure fallback a silent trace gets. Partial
     evidence must not select: a form on both a traced and an untraced path
     gets a small count and narrows to it, which is the false-green shape.
     Editing a defMULTI narrows (every dispatched call records it, both
     tiers); editing a defMETHOD falls back (the external tier records it at
     multi grain only). Ops are hardened to match: deleting or
     dispatch-changing a defmethod `remove-method`s the old registration in
     the image — `ns-unmap` was a no-op and the deleted method KEPT
     ANSWERING, the green-when-red direction nothing cross-checks.
     **Static tracking is separate and now sees method bodies**: kondo
     resolves defmethod/defrecord/extend-* body usages but reports them with
     nil `:from-var`, and every edge builder dropped them — a defn called
     only from a method body read as unused-public. `static-refs` now
     attributes those to the OWNING FORM by rendered span
     (`render/owner-form`); `cold-load-order` ignores nil-from-var edges (its
     Kahn is name-keyed), and the gate still catches method-before-multi
     because `forward-refs` reads kondo rows directly — pinned in
     `slopp.coldload-gate-test/registrations-and-the-cold-load-gate`.
2. **The trace map (session `:test-map`).** Flat
   `{qualified-test-sym #{qualified-form-sym}}`, merged in on every traced
   run, carried across renames (`rename-in-trace`). Powers **affected-test
   selection**: edit form F → run only tests whose set contains F (or F
   itself if F is a test). No trace info → conservative full-ns run
   (`:affected :all`).
   **BOTH tiers select from it (#127, 2026-07-17).** `done!` routes the
   external tier through `session/impacted-isolated` → `isolated-among`, the
   same evidence the in-image half uses, and only falls back to
   `test-nses-reaching` (the require-closure) when the trace is SILENT. Three
   answers, and the difference is load-bearing: **nil** = silent, fall back;
   **[]** = evidence names tests, none isolated, nothing to run; **[syms]** =
   run exactly these. Until #127 the tier re-derived the closure — which
   selects a **median 43 of 46** isolated test namespaces (measured over every
   source ns), so the cap of 4 fired and **84.6% of changes deferred**: the
   evidence was computed four lines above and discarded.
   **The cap is on TESTS (40), not namespaces**, because `isolated-test-run!`'s
   `:only` and `:nses` do NOT compose — the sharded branch calls
   `run-shard!` with the ns group and never passes `only`, so passing both
   would silently run whole namespaces. A `:only` run is one serial JVM
   (`full-set` is nil ⇒ `par` = 1). Measured: p50 = 12 covering tests, a cap of
   40 fits ~71% of forms, and the tail (p90 = 218) is the core-form case that
   honestly wants the whole suite.
   **Do not "improve" this with static reach.** Measured on this store: static
   transitive selects p50 = 49 tests vs the trace's 11, and kondo reports
   `defmethod`/`defrecord`/`extend-type` bodies with nil `:from-var` — every
   edge-builder drops them — so it under-approximates through dispatch. The
   RTS literature agrees (STARTS 68.5% reduction with a 3.19% safety violation
   vs Ekstazi's 84.2%); both are class-grain, this is form-grain.
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
   **The external tier TRACES (#121, 2026-07-16).** It is the only tier that
   ever runs an `^:isolated` test, so it is the only place their test→form
   evidence can come from — before this, 69% of slopp's own suite (257/370
   deftests) produced ZERO runtime evidence and `slopp.api/done!` itself read
   `:warranty {:covered 0}`. `slopp.testmain` is the built project's entry
   point: it rides rt's `instrument!`/`restore!` seam — **the SAME tracer as
   in-image, never a second copy** — tracks the current test by delegating
   `clojure.test/report`, and **WRAPS `cognitect.test-runner/test` rather than
   replacing it**. That wrapping is load-bearing: `parse-test-summary`,
   `parse-test-failures`, `failing-test-rollup` and `failure-themes` all parse
   cognitect's OUTPUT FORMAT and the commit gate rides them, so the verdict
   path must stay cognitect's. The trace goes to a FILE per shard beside the
   build (never stdout — those parsers regex the whole joined output of every
   shard, so a trace on stdout is a false-match surface on the oracle's own
   path); `testrun/read-traces` globs + `merge-with into` (shards are
   concurrent JVMs in ONE dir) and `session/absorb-trace!` merges it into the
   same `:test-map` the in-image tier feeds. Silent — it surfaces as honest
   `:warranty` and narrowing, not as output. Routing is conditional on the
   store PROVIDING `slopp.testmain`, so a store without it (any user project;
   every throwaway test store) builds byte-identically to before and stays on
   plain cognitect.
   **The child image REPORTS BACK (#126, 2026-07-17) — the subprocess limit is
   CLOSED, not accepted.** It used to read "the tracer cannot trace itself
   through a subprocess". It can, because slopp.rt is the ONLY slopp code that
   executes in a child (`repl/inject-rt!` is the sole place slopp code is evaled
   in; the child otherwise loads its own store). So:
   - `inject-rt!` calls `rt/self-instrument!`, wrapping rt against
     `rt/self-touched`. **The timing is the mechanism:** a fn already on the
     stack cannot record its own entry, so wrapping from inside `traced-run`
     would miss `traced-run` — which is exactly why it read `:covered 0`.
     `self-instrument!` records ITSELF explicitly for the same reason; nothing
     else can see the installer.
   - `image/traced-test-run` calls `image/drain-child-rt!`, which moves
     `rt/drain-self!`'s syms onto `rt/touched-sink` — the atom `instrument!`
     publishes for whichever run is collecting. No sink (the MCP server's own
     images, overwhelmingly) means no round-trip at all.
   - `instrument!` calls NEST — testmain wraps the whole external run,
     individual tests wrap again inside it — so the displaced sink rides home on
     the originals map's metadata and `restore!` hands it back. Clearing it to
     nil stops the drain for every later test in the shard, SILENTLY.
   - Feature-detected via `resolve` at both ends: a lagging uberjar's rt predates
     the seam, and `io/resource` reads whichever `slopp/rt.clj` is on the READING
     process's classpath. A missing drain must degrade to no evidence, never an
     error.

   **Measured, full suite, before → after:** `rt/traced-run` **0 → 214**;
   `rt/instrument!`/`restore!`/`qualified` **1 → 218**. The **1** was the
   dangerous one: zero means "no information" and falls back to the whole
   closure, while a partial count narrows to one test and calls it green. It
   was 1 because a probe test called it directly in-process — **adding a test
   is what made it unsafe to narrow on.**

   A declared `^{:covers …}` marker is NOT needed and should not be added: it
   would drift, and the evidence is now measured. Static reach is not a filler
   either — at depth 3–4 across 376 tests everything is "covered" (p90 = 227
   tests, measured 2026-07-17).
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
