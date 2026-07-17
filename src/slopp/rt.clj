(ns slopp.rt
  "Runtime support slopp injects into every owned image (see slopp.repl/start!).
  Lives IN the image, next to the code under management.

  `traced-run` is the D1 form-granularity mechanism: run tests while observing —
  via temporary var instrumentation — exactly which store forms each test
  exercises. The observed test→form map is what lets an edit re-verify only the
  forms it touched, replacing @examples' co-location with runtime observation.

  Known sampling limits (accepted, per the design): value-captured references
  (e.g. `(def g (comp f inc))`) bypass the var and aren't observed; multimethods
  and macros are not instrumented."
  (:require [clojure.test :as t]))

(defn- instrumentable? [v]
  (and (bound? v) (fn? @v)
       (let [m (meta v)]
         (and (not (:macro m)) (not (:test m))))))

(defn qualified
  "A var's fully-qualified symbol — the key shape of every trace map, both
  tiers (#121). Public so the external trace runner keys tests identically to
  the in-image tracer instead of re-deriving it."
  [v]
  (let [m (meta v)]
    (symbol (str (ns-name (:ns m))) (str (:name m)))))

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn- render-actual [x]
  (truncate (if (instance? Throwable x)
              (str (.getName (class x)) ": " (ex-message x))
              (pr-str x))
            400))

^:unsafe ^:reads (defn ^:entry-point observe
  "Temporarily instrument the var named by `target` (qualified symbol),
  capturing the args and return (or thrown exception) of up to `limit` calls
  while `thunk` runs; the original is restored in a finally. The oracle's
  answer to 'what actually flows through this function?'
  Returns {:result str :calls [{:args [str] :ret str (or :threw str)}] :count n}."
  [target thunk limit]
  (let [v     (resolve target)
        orig  @v
        calls (atom [])
        note! (fn [entry]
                (when (< (count @calls) limit)
                  (swap! calls conj entry)))]
    (alter-var-root
     v (fn [_]
         (fn [& args]
           (let [shown (mapv #(truncate (pr-str %) 200) args)]
             (try
               (let [ret (apply orig args)]
                 (note! {:args shown :ret (truncate (pr-str ret) 200)})
                 ret)
               (catch Throwable e
                 (note! {:args shown :threw (render-actual e)})
                 (throw e)))))))
    (try
      (let [res (thunk)]
        {:result (truncate (pr-str res) 400)
         :calls  @calls
         :count  (count @calls)})
      (finally
        (alter-var-root v (constantly orig))))))

(def touched-sink
  "The atom `instrument!` is currently collecting into, or nil.

  THE child-image drain's handle. rt runs in TWO processes: a runner wraps its
  own vars, but code that runner evals into a CHILD image executes where no
  wrapper can reach. `image/traced-test-run` receives those child-side rt calls
  in its response and needs somewhere to put them — the atom belonging to the
  test being traced RIGHT NOW. Only the tracer knows which atom that is, so the
  tracer publishes it.

  nil outside a traced run, and that matters: the MCP server evals into images
  constantly with nothing instrumented, and those calls must never land in some
  earlier run's set."
  (atom nil))
^:unsafe (defn restore!
  "Put back what `instrument!` wrapped — var roots AND multimethod table
  entries — and hand the `touched-sink` back to whatever run was collecting
  before it (nil at the outermost). Call from a `finally` — an image whose vars
  stay wrapped reports every later run through a stale closure, and a sink left
  pointing at a finished run's atom would attribute later child-image calls to
  a test that already ended.

  A method a test itself registered mid-run (a defmethod in a test body) is not
  in the originals and survives restore — the same tolerance var wrapping has
  always had for vars a test defines."
  [originals]
  (reset! touched-sink (::prev-sink (meta originals)))
  (doseq [[v orig] (:vars originals)]
    (alter-var-root v (constantly orig)))
  (doseq [[^clojure.lang.MultiFn mf k orig] (:methods originals)]
    (.addMethod mf k orig)))
^:unsafe ^:reads (defn instrument!
  "Wrap every instrumentable fn var of `target-nses` so each call conjes its
  qualified symbol onto `touched` (an atom holding a set). Returns the
  originals map — hand it to `restore!` from a `finally`; instrumentation is
  ALWAYS temporary.

  THE one instrumentation mechanism (#121). Two runners ride it and they
  genuinely differ: `traced-run` wraps its own per-var loop for the in-image
  tier; the built project's trace runner wraps cognitect's runner for the
  external tier (the only tier that executes ^:isolated tests). The wrapping
  itself must never be copied — a second tracer is a second truth.

  MULTIMETHODS are wrapped at their METHOD TABLE (#129): a MultiFn is `ifn?`
  but not `fn?`, and replacing the var with a plain fn would break defmethod
  (it macroexpands to `.addMethod` on the var's value). Each table entry is a
  plain fn, so each is wrapped to record the MULTI's qualified sym — dispatch
  itself (dispatch fn, hierarchy, prefers) stays untouched. `attr`
  ({multi-qsym {dispatch-val form-qsym}}, optional) additionally records the
  METHOD's own form key when the dispatch value is known — the in-image tier
  derives it from the store; without it a method call still honestly records
  the multi.

  Also publishes `touched` as the `touched-sink` (#126) so rt calls made in a
  CHILD image — where no wrapper of ours can reach — reach the test being
  traced right now. Calls NEST (testmain instruments once around the whole
  external run; individual tests instrument again inside it), so the sink it
  displaces rides home on the returned map's metadata and `restore!` puts it
  back. Clearing it to nil instead would silently stop the drain for every
  later test in the shard."
  ([target-nses touched] (instrument! target-nses touched nil))
  ([target-nses touched attr]
   (let [prev      @touched-sink
         vars      (atom {})
         methods   (atom [])]
     (reset! touched-sink touched)
     (doseq [n target-nses
             v (vals (ns-interns n))]
       (cond
         (instrumentable? v)
         (let [orig @v
               qs   (qualified v)]
           (swap! vars assoc v orig)
           (alter-var-root v (fn [_]
                               (fn [& args]
                                 (swap! touched conj qs)
                                 (apply orig args)))))

         (and (bound? v) (instance? clojure.lang.MultiFn @v))
         (let [^clojure.lang.MultiFn mf @v
               qs (qualified v)]
           (doseq [[k orig] (.getMethodTable mf)]
             (let [form-key (get-in attr [qs k])]
               (swap! methods conj [mf k orig])
               (.addMethod mf k
                           (fn [& args]
                             (swap! touched conj qs)
                             (when form-key (swap! touched conj form-key))
                             (apply orig args))))))))
     (with-meta {:vars @vars :methods @methods} {::prev-sink prev}))))
^:unsafe ^:reads (defn ^:entry-point traced-run
  "Run `test-ns`'s test vars (all of them, or just those named in `only`),
  recording which fn vars of `target-nses` each test touches. `test-ns` may be
  a collection of namespaces — the whole project verifies in ONE run, paying
  instrumentation once (F-3c1). Instrumentation is temporary — originals are
  restored in a finally. Returns
  {:summary {:test .. :pass .. :fail .. :error .. :type :summary
             :failures [{:test :type :message :expected :actual} ...]}  ; when red
   :trace   {qualified-test-sym #{qualified-form-sym ...}}}

  The IN-IMAGE tier. Wraps its own per-var loop with `instrument!`; the
  external tier's trace runner wraps cognitect's runner with the same seam
  (#121) — two runners, one tracer.

  `methods` ([[form-ns multi-sym dispatch-sexpr form-key] ...], optional) is
  the store's defmethod attribution (#129): each dispatch sexpr is evaled HERE
  — in the image, where defmethod itself evaled it — because only the runtime
  value keys a method table (source says String, the table holds
  java.lang.String). A row that fails to resolve or eval is skipped: the
  method then records its multi alone, which is less precise, never wrong.
  Stale injected rt (an older jar) has no `methods` param and silently drops
  the extra arg — same degradation.

  Failure details are captured by rebinding clojure.test's dynamic `report`
  multimethod (F1) — without this they'd be printed to the image's stdout and
  lost. Bounded: ≤20 entries, values truncated to 400 chars."
  [test-ns target-nses only & [skip-integration? methods]]
  (let [test-nses (if (coll? test-ns) test-ns [test-ns]) ; F-3c1: whole project
        touched   (atom #{})
        current   (atom nil)
        failures  (atom [])
        attr      (reduce (fn [m [form-ns multi-sym dispatch form-key]]
                            (try
                              (let [v (ns-resolve form-ns multi-sym)]
                                (if (and v (instance? clojure.lang.MultiFn @v))
                                  (assoc-in m [(qualified v) (eval dispatch)]
                                            form-key)
                                  m))
                              (catch Throwable _ m)))
                          {} methods)
        originals (instrument! target-nses touched attr)]
    (try
      (let [tvars    (cond->> (mapcat #(filter (comp :test meta)
                                               (vals (ns-interns %)))
                                      test-nses)
                       ;; M5: the fast per-write path skips ^:integration tests
                       ;; (external-system tests — a DB dep shouldn't fire on
                       ;; every edit); done/commit/test_run include them
                       skip-integration? (remove (comp :integration meta))
                       ;; ^:isolated tests spawn their own images/JVMs — they
                       ;; NEVER run in-image (recursion). Unconditional BY
                       ;; DESIGN, not an oversight: the external trace runner
                       ;; is where they run, and it traces them there (#121).
                       true (remove (comp :isolated meta))
                       only (filter (comp (set only) :name meta)))
            counters (ref t/*initial-report-counters*)
            record   (fn [m]
                       (case (:type m)
                         :begin-test-var (reset! current (qualified (:var m)))
                         (:pass :fail :error)
                         (do (t/inc-report-counter (:type m))
                             (when (and (not= :pass (:type m))
                                        (< (count @failures) 20))
                               (swap! failures conj
                                      {:test     @current
                                       :type     (:type m)
                                       :message  (some-> (:message m) str)
                                       :expected (truncate (pr-str (:expected m)) 400)
                                       :actual   (render-actual (:actual m))})))
                         nil))
            trace    (binding [t/*report-counters* counters
                               t/report record]
                       (into {}
                             (for [tv (sort-by (comp :line meta) tvars)]
                               (do (reset! touched #{})
                                   (t/test-vars [tv])
                                   [(qualified tv) @touched]))))]
        {:summary (cond-> (assoc @counters :type :summary)
                    (seq @failures) (assoc :failures @failures))
         :trace   trace})
      (finally
        (restore! originals)))))
(def self-touched
  "What rt's OWN fns have been called since the last drain (#126).

  Separate from any run's `touched` because it outlives them: it is installed
  ONCE per image (`inject-rt!` → `self-instrument!`) and drained per eval,
  whereas a run's atom is born and restored inside a single `traced-run`."
  (atom #{}))
^:unsafe (defn self-instrument!
  "Wrap rt's OWN fn vars against `self-touched`. Returns the originals map for
  `restore!`, exactly like `instrument!` — it IS `instrument!`, aimed at rt.

  Called once per image by `repl/inject-rt!`, and the timing is the whole
  point: a fn already on the stack cannot record its own entry, so wrapping
  from inside `traced-run` would miss `traced-run`. Installing at injection —
  before anything calls in — is what makes the child's entry points visible.

  Only meaningful in a CHILD image, where rt is the sole slopp code running and
  nothing else wraps it. In slopp's own image the store's rt loads over the
  injected copy and takes these wrappers with it, which is harmless: that tier
  instruments rt directly through `traced-run`'s target-nses.

  Records ITSELF explicitly, because nothing else can: the installer is on the
  stack when the wrap goes on, so no wrapper ever catches its entry — the same
  self-reference that made `traced-run` read 0 covering tests. Left implicit it
  reads as covered by the single test calling it directly, and a PARTIAL count
  is worse than none: zero means 'no information' and falls back to running the
  whole closure, one narrows to one test and calls the result green."
  []
  (let [originals (instrument! ['slopp.rt] self-touched)]
    (swap! self-touched conj 'slopp.rt/self-instrument!)
    originals))
(defn drain-self!
  "Take and clear what rt has recorded about itself since the last drain.

  The runner calls this over the eval it was already making (`traced-run`,
  `observe`), so the child's rt calls ride home for free — no extra round-trip.
  It CLEARS because those calls belong to the test being traced right now; left
  in place they would re-report against whichever test is traced next.

  Records itself, and that is honest: `drain-self!` really does run in the
  child on the parent test's behalf, so a change to it really does reach every
  test that drove a child image."
  []
  (let [s @self-touched]
    (reset! self-touched #{})
    s))
