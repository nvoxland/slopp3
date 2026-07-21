(ns slopp.testmain
  "The BUILT project's traced test entry point (#121) — cognitect's runner
  wrapped in slopp's form tracer, so the EXTERNAL tier produces a trace.

  Why this exists: `^:external` tests only ever run out-of-process (they spawn
  images), so the in-image tracer never sees them — 69% of slopp's own suite
  had zero runtime evidence and read as `:warranty {:covered 0}`.

  Why it WRAPS rather than replaces cognitect: `parse-test-summary`,
  `parse-test-failures`, `failing-test-rollup` and `failure-themes` all parse
  cognitect's OUTPUT format, and commit_point's green gate rides them. This
  delegates to `cognitect.test-runner/test` and to clojure.test's own report
  methods, and only ADDS a trace file. The verdict path is untouched.

  Loaded ONLY inside a built project: cognitect is not on slopp's own
  classpath (the tree is fileless — deps.edn carries no :test alias), which is
  why the runner resolves it dynamically instead of requiring it."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [slopp.rt :as rt]))

(def trace-file-prefix
  "Marks this run's trace files in the built dir. Each shard writes its own
  (they run concurrently in ONE built dir); the reader globs and merges."
  "slopp-trace-")

^:unsafe (defn- instrumentation-targets!
  "Load and return the built project's `src` namespaces — the instrumentation
  targets, standing in for the in-image tier's dependency-closure set.
  Tolerant per namespace ON PURPOSE: plain cognitect only ever loads the test
  closure, so force-loading here must never invent a red the untraced runner
  wouldn't have had."
  []
  (let [find-in-dir (requiring-resolve
                     'clojure.tools.namespace.find/find-namespaces-in-dir)]
    (vec (for [n (find-in-dir (io/file "src"))
               :when (try (require n) true (catch Throwable _ false))]
           n))))

(defn- write-trace!
  "Emit this shard's trace as EDN beside the build. A FILE, not stdout: the
  output parsers regex the whole joined stdout+stderr of every shard, and a
  megabyte of form names there is a false-match surface."
  [trace]
  (spit (io/file (str trace-file-prefix (java.util.UUID/randomUUID) ".edn"))
        (pr-str trace)))

^:unsafe ^:entry-point (defn -main
  "Run the built project's tests through cognitect, tracing which src forms
  each test touches; write the trace, then exit on cognitect's own verdict.
  Args are cognitect's, untouched and forwarded."
  [& args]
  (rt/install-parent-watchdog!)
  (let [ctr-test  (requiring-resolve 'cognitect.test-runner/test)
        cli-opts  @(requiring-resolve 'cognitect.test-runner/cli-options)
        parse     (requiring-resolve 'clojure.tools.cli/parse-opts)
        opts      (:options (parse args cli-opts))
        touched   (atom #{})
        current   (atom nil)
        trace     (atom {})
        orig      t/report
        originals (rt/instrument! (instrumentation-targets!) touched)
        ;; track the current test, then DELEGATE — clojure.test's own methods
        ;; do the counting and the printing that the summary parsers read.
        record    (fn [m]
                    (case (:type m)
                      :begin-test-var (do (reset! current (rt/qualified (:var m)))
                                          (reset! touched #{}))
                      :end-test-var   (when @current
                                        (swap! trace update @current
                                               (fnil into #{}) @touched))
                      nil)
                    (orig m))]
    (try
      (let [{:keys [fail error]} (binding [t/report record] (ctr-test opts))]
        (write-trace! @trace)
        (System/exit (if (zero? (+ fail error)) 0 1)))
      (finally
        (rt/restore! originals)))))