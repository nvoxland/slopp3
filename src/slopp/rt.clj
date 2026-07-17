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

^:unsafe ^:reads (defn observe
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

^:unsafe ^:reads (defn instrument!
  "Wrap every instrumentable fn var of `target-nses` so each call conjes its
  qualified symbol onto `touched` (an atom holding a set). Returns the
  originals map — hand it to `restore!` from a `finally`; instrumentation is
  ALWAYS temporary.

  THE one instrumentation mechanism (#121). Two runners ride it and they
  genuinely differ: `traced-run` wraps its own per-var loop for the in-image
  tier; the built project's trace runner wraps cognitect's runner for the
  external tier (the only tier that executes ^:isolated tests). The wrapping
  itself must never be copied — a second tracer is a second truth."
  [target-nses touched]
  (let [originals (atom {})]
    (doseq [n target-nses
            v (vals (ns-interns n))
            :when (instrumentable? v)]
      (let [orig @v
            qs   (qualified v)]
        (swap! originals assoc v orig)
        (alter-var-root v (fn [_]
                            (fn [& args]
                              (swap! touched conj qs)
                              (apply orig args))))))
    @originals))

^:unsafe (defn restore!
  "Put back the originals `instrument!` returned. Call from a `finally` — an
  image whose vars stay wrapped reports every later run through a stale
  closure."
  [originals]
  (doseq [[v orig] originals]
    (alter-var-root v (constantly orig))))

^:unsafe ^:reads (defn traced-run
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

  Failure details are captured by rebinding clojure.test's dynamic `report`
  multimethod (F1) — without this they'd be printed to the image's stdout and
  lost. Bounded: ≤20 entries, values truncated to 400 chars."
  [test-ns target-nses only & [skip-integration?]]
  (let [test-nses (if (coll? test-ns) test-ns [test-ns]) ; F-3c1: whole project
        touched   (atom #{})
        current   (atom nil)
        failures  (atom [])
        originals (instrument! target-nses touched)]
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

