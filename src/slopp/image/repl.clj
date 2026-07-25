(ns slopp.image.repl
  "The owned live image (D5): slopp launches and manages a JVM Clojure nREPL as
  a subprocess. `refresh` (hot eval/redefine) is the fast path; `restart!` throws
  the process away for a guaranteed-faithful fresh image — the correctness
  backstop. Phase-1 uses plain restart; the warm-spare optimization is deferred."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nrepl.core :as nrepl])
  (:import [java.io BufferedReader]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(def ^:export clojure-bin
  "The clojure launcher for owned images: SLOPP_CLOJURE env override, else the
  first executable found in the usual install locations, else trust PATH.
  Public so `slopp.deps` reuses the same launcher for classpath resolution."
  (or (System/getenv "SLOPP_CLOJURE")
      (some (fn [dir]
              (let [f (io/file dir "clojure")]
                (when (.canExecute f) (str f))))
            ["/opt/homebrew/bin" "/usr/local/bin" "/usr/bin"])
      "clojure"))

(def ^:export inherent-deps
  "Dependencies slopp-the-tool provides to EVERY owned image for its OWN
  features — nREPL (the image's REPL server) and malli (image-side schema
  generative-check). NOT the project manifest (`deps_add`): never in
  `deps_list`, never removable, versioned centrally HERE so an upgrade reaches
  existing installs with no per-store migration, and merged into every image's
  `-Sdeps` AFTER the manifest so slopp controls their versions. Image-tier ONLY
  — the server/boot JVM runs on the kernel deps (root deps.edn); slopp code that
  uses these must run in the image (feature-detected, like `slopp.rt`)."
  '{nrepl/nrepl   {:mvn/version "1.3.1"}
    metosin/malli {:mvn/version "0.17.0"}})

(def ^:private watchdog-src
  "Source for the parent-death watchdog thread, evaluated INSIDE the child.
  The child's stdin is a pipe from the parent, so a daemon thread blocked on
  System/in sees EOF the moment the parent's fds close — no shutdown hook
  catches an abnormal parent death, but the OS closing the pipe does. The
  install is guarded by thread NAME so it lands exactly once however many
  surfaces run it (the launch command boards it at birth; inject-rt! re-runs
  it as the safety net for images started with a custom :cmd)."
  (str "(do (when-not (some #(= \"slopp-parent-watchdog\" (.getName %))"
       " (keys (Thread/getAllStackTraces)))"
       " (doto (Thread. (fn [] (try (while (not (neg? (.read System/in))))"
       " (catch Throwable _)) (System/exit 0)) \"slopp-parent-watchdog\")"
       " (.setDaemon true) (.start))) nil)"))

(defn- default-cmd
  "The target image launch command: Clojure + nREPL, plus the store's external
  dependency manifest (`deps`, lib→coord) merged into `-Sdeps` so store code
  that requires those libs compiles (trust Tier 1). `inherent-deps` (nREPL,
  malli) are merged LAST so slopp-the-tool's own image deps are always present
  at slopp's versions — regardless of the project manifest.

  The parent-death watchdog rides `-e` (a clojure.main INIT opt, so it runs
  before `-m` starts nREPL): the child can never exist without its reaper,
  closing the boot-window orphan class — a parent killed between spawn and
  nREPL connect used to leave a JVM nothing would ever reap."
  ([] (default-cmd nil))
  ([deps]
   [clojure-bin "-Sdeps"
    (pr-str {:deps (merge deps inherent-deps)})
    "-M" "-e" watchdog-src "-m" "nrepl.cmdline"]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-image" (make-array FileAttribute 0))))

(defn- read-port
  "Read the subprocess's merged output until it announces its port, bounded by
  `timeout-ms`. Char-at-a-time behind `.ready` so the DEADLINE governs even
  when the child goes silent mid-line — `.readLine` blocked unboundedly, and a
  child that booted quietly and hung wedged every caller up the stack."
  [^BufferedReader rdr timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [sb (StringBuilder.)]
      (cond
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "owned image did not report a port in time" {}))

        (.ready rdr)
        (let [c (.read rdr)]
          (cond
            (neg? c)
            (throw (ex-info "owned image ended before reporting a port" {}))

            (= c (int \newline))
            (if-let [m (re-find #"port (\d+)" (str sb))]
              (Long/parseLong (second m))
              (recur (StringBuilder.)))

            :else
            (recur (doto sb (.append (char c))))))

        :else
        (do (Thread/sleep 25) (recur sb))))))

^:unsafe (defn ^:export eval!
  "Eval `code` in the image; returns a vector of returned values, read as data
  when readable and left as the raw printed string otherwise (so evals that
  return unreadable objects — namespaces, functions — don't blow up).

  `image` is an OPAQUE handle from `start!`: the caller never builds one, it
  passes back what it was given. Destructuring it here would advertise
  internals as a contract a caller is expected to know; reading them in the
  body says the truth. It also keeps the handle's key shape out of arglists,
  which is what made renaming `:client` brick a whole session."
  [image code]
  (->> (nrepl/message (:client image) {:op "eval" :code code
                                       :session (:session image)})
       (keep :value)
       (mapv (fn [v] (try (read-string v) (catch Exception _ v))))))

^:unsafe (defn- eval-outcome
  "Classify a completed nREPL eval's messages: `{:values [...]}` (plus
   `:stderr` when the eval WROTE to stderr without failing) or `{:err msg}`.

   The verdict comes from nREPL's `eval-error` STATUS, which is the only thing
   that actually knows. Reading `:err` — the stderr STREAM — as the verdict
   instead meant any library that prints at load turned a successful eval into
   an error AND discarded its values: garden's `WARNING: abs already refers to
   …` did exactly that, and because a second eval finds the namespace already
   loaded and prints nothing, it was non-reproducible on the retry.

   A failure's message keeps the stderr text, which is where the exception's
   own message lives (`:ex` names only the class), falling back to the class
   when the eval failed silently."
  [msgs]
  (let [stderr  (str/join (keep :err msgs))
        failed? (some #(some #{"eval-error"} (:status %)) msgs)]
    (if failed?
      {:err (str/trim (if (str/blank? stderr)
                        (or (some :ex msgs) "eval-error")
                        stderr))}
      (cond-> {:values (->> msgs (keep :value)
                            (mapv (fn [v] (try (read-string v)
                                               (catch Exception _ v)))))}
        (not (str/blank? stderr)) (assoc :stderr stderr)))))

^:unsafe (defn ^:export eval-checked!
  "Like `eval!` but surfaces evaluation errors instead of silently dropping
  them (F-3c2 — an eval that throws must not look like an empty result).
  Returns `{:values [...]}` — with `:stderr` when the eval printed there
  without failing — or `{:err msg}`. `eval-outcome` does the classifying, and
  it reads nREPL's `eval-error` status rather than the stderr stream. `image`
  is the opaque handle — see `eval!` for why it is not destructured."
  [image code]
  (eval-outcome
   (doall (nrepl/message (:client image) {:op "eval" :code code
                                          :session (:session image)}))))

(defn ^:export add-libs!
  "Hot-add dependency coords (`deps-map`, lib→coord) to the RUNNING image via
  Clojure 1.12 `clojure.repl.deps/add-libs` — no restart. Idempotent for
  already-present coords (so it also reconciles an adopted bare spare).
  Returns nil on success, or {:err msg} so the caller can fall back to a
  fresh image (a jar can't be unloaded, so removes/downgrades never hot-apply).

  MARKS the image dirty, in the image, as a plain interned var. A jar cannot
  be unloaded, so an image that has taken one can never be returned to its
  boot baseline and must never be recycled — and this is recorded as a FACT
  rather than inferred. The first attempt inferred it from the classloader's
  URL list, and that silently missed: nREPL's DynamicClassLoader is not the
  one `add-libs` mutates, so two tests that passed alone went red in the full
  suite. A fact the image carries survives every sweep and cannot be
  out-clevered."
  [handle deps-map]
  (when (seq deps-map)
    (let [r (eval-checked!
             handle
             (str "(do (require 'clojure.repl.deps)"
                  " (intern 'user 'slopp-image-dirty true)"
                  " (clojure.repl.deps/add-libs '" (pr-str deps-map) "))"))]
      (when (:err r) r))))

(defn- benign-load-noise?
  "True when a `load-file` stderr chunk carries ONLY compiler noise — var-shadow
  `WARNING:`s (e.g. garden.color's `abs` re-refer) or reflection warnings — and
  no genuine failure. nREPL reports a real load failure via an `eval-error`
  STATUS (which load-checked! collects separately), so warning-only stderr must
  not be counted as an error (friction #9: garden's benign warning surfaced as a
  restart-load ERROR that obscured every red diagnosis)."
  [chunk]
  (let [lines (remove str/blank? (str/split-lines (str chunk)))]
    (boolean
     (and (seq lines)
          (every? #(or (str/starts-with? % "WARNING:")
                       (str/starts-with? % "Reflection warning"))
                  lines)))))

^:unsafe (defn ^:export load-checked!
  "Like `load!` but surfaces evaluation failures instead of silently dropping
  them (T4 — a failed load must never leave the store and image out of step).
  Returns {:values [...]} or {:err msg}. `image` is the opaque handle — see
  `eval!` for why it is not destructured."
  [image src path]
  (let [msgs (doall (nrepl/message
                     (:client image)
                     {:op "load-file" :file src :file-path path
                      :file-name (subs path (inc (or (str/last-index-of path "/") -1)))
                      :session (:session image)}))
        errs (concat (remove benign-load-noise? (keep :err msgs))
                     (mapcat (fn [m]
                               (when (some #{"eval-error"} (:status m))
                                 [(or (:ex m) "eval-error")]))
                             msgs))]
    (if (seq errs)
      {:err (str/trim (str/join " " (distinct errs)))}
      {:values (->> msgs (keep :value)
                    (mapv (fn [v] (try (read-string v) (catch Exception _ v)))))})))

(def ^:export dirty-probe
  "The expression that asks an image whether it can still be recycled —
  evaluated identically wherever the question is asked, so no two callers can
  disagree about what dirty means.

  Today it means exactly one thing: `add-libs!` has run. A jar cannot be
  unloaded, so an image that has taken one can never return to its boot
  baseline. `add-libs!` interns the flag in `user`, which is part of every
  baseline and therefore survives the namespace sweep — the mark outlives the
  thing that would otherwise erase it.

  This replaced a classloader fingerprint (`.getURLs` on `RT/baseLoader`) that
  looked more rigorous and was wrong: nREPL does not mutate the loader the
  probe read, so a dirtied image passed verification and two tests that were
  green in isolation went red in the full suite. **An inferred signal that can
  silently miss is worse than none**, because it buys confidence it has not
  earned. Anything that dirties an image in a new way must mark it here."
  "(boolean (resolve 'user/slopp-image-dirty))")

(defn- inject-rt!
  "Load slopp's runtime support (slopp.rt — traced test execution) into the
  image, ensure the parent-death watchdog is aboard, wrap rt against itself
  (#126), record the BASELINE namespace set, then return to `user`. Every
  owned image carries all of it.

  The self-instrument call is FEATURE-DETECTED, not assumed. `io/resource` reads
  whichever slopp/rt.clj is on the READING process's classpath, and that differs
  by caller: the external runner is a built project, so it gets the store's
  rendered rt; the MCP server runs from the uberjar, so it gets whatever rt that
  jar was built with — which lags the store by design. Calling a var the older
  copy lacks would break every image the moment the jar fell behind.

  The timing is the point: wrapping here — before anything calls in — is what
  makes rt's own entry points visible. `traced-run` cannot wrap itself from the
  inside; it is already on the stack by then, which is exactly why it measured
  zero covering tests while 213 exercised it.

  The same timing argument gives `:baseline` its only correct moment: the
  namespace set here is the image with rt aboard and NO store code, which is
  exactly what `reset!` must be able to return to before a second tenant may
  have it. Failing to capture it is never fatal — an image with no baseline
  simply refuses to be recycled.

  The WATCHDOG (see `watchdog-src`) normally boards the child's own command
  line, before nREPL starts — this re-run is the safety net for images
  launched with a custom :cmd; the name guard makes it land exactly once."
  [handle]
  (eval! handle (slurp (io/resource "slopp/rt.clj")))
  (eval! handle "(when-let [f (resolve 'slopp.rt/self-instrument!)] (f))")
  (eval! handle watchdog-src)
  (let [handle (assoc handle :baseline
                      (try {:nses (first (eval! handle "(set (map ns-name (all-ns)))"))
                            :cp   (first (eval! handle dirty-probe))}
                           (catch Throwable _ nil)))]
    (eval! handle "(in-ns 'user)")
    handle))

(defn ^:export ^{:live-handle true
        :malli/schema
        [:=> [:cat [:? [:map
                        [:slopp.image.repl/cmd {:optional true} [:maybe [:sequential :string]]]
                        [:slopp.image.repl/dir {:optional true} [:maybe :some]]
                        [:slopp.image.repl/timeout-ms {:optional true} :int]
                        [:slopp.image.repl/deps {:optional true} [:maybe :map]]]]]
         :map]}
  start!
  "Launch a fresh owned image (with slopp.rt support loaded); returns a handle
  for eval!/restart!/stop!.

  The OPTION map is a caller-built contract, so its keys are qualified —
  unlike the handle this returns, whose keys are internal and read in the
  body by `eval!`/`stop!` rather than destructured at any boundary.

  Any throw after the spawn (port timeout, connect failure, rt load) DESTROYS
  the child before rethrowing — with a custom :cmd the watchdog may not be
  aboard yet, and an abandoned nrepl JVM outlives even parent death. The
  ex-info carries the child :pid so the cleanup is verifiable.

  The `:=>` schema is DOCUMENTATION here, not a verified claim: this fn
  spawns a JVM, so `analyzer-pure?` excludes it from the generative
  oracle-check. Nothing will catch it drifting from the impl — keep it
  honest by hand."
  ([] (start! {}))
  ([{:slopp.image.repl/keys [cmd dir timeout-ms deps] :or {timeout-ms 60000}}]
   (let [cmd (or cmd (default-cmd deps))
         dir (or dir (temp-dir))
         pb  (doto (ProcessBuilder. ^java.util.List cmd)
               (.redirectErrorStream true)
               (.directory (io/file dir)))
         proc (.start pb)]
     (try
       (let [rdr  (io/reader (.getInputStream proc))
             port (read-port rdr timeout-ms)
             conn (nrepl/connect :port port)
             client (nrepl/client conn 30000)
             session (nrepl/new-session client)]
         (inject-rt! {:process proc :port port :conn conn :client client
                      :session session :reader rdr :dir dir}))
       (catch Throwable t
         (.destroyForcibly proc)
         (throw (ex-info (str "image boot failed: " (ex-message t))
                         {:pid (.pid proc)} t)))))))

^:unsafe (defn ^:export reset-to-baseline!
  "Return `image` to the state it recorded at boot, so the next tenant gets it
  as if freshly launched — or NIL, meaning it could not be proven clean and
  the caller must destroy it and boot for real.

  **Why this exists.** An image's expensive part is the Clojure runtime, and
  that runtime is IDENTICAL in every image: measured on one box, 830ms of
  Clojure+nREPL class loading against 9.2ms to unmap and reload a
  three-namespace store. What differs between two tenants is the store's
  namespaces, which for a test store is one to three tiny ones. The process
  was ~90x too heavy a unit for the difference it was buying.

  Clojure does give a single root for the code half — `Namespace/namespaces`
  is a static registry and `remove-ns` is the sweep. It gives none for the
  classpath half, which is why that case is refused rather than reset.

  **Two conditions, both learned the hard way.**
  - DIRTY (`dirty-probe`) — refused BEFORE anything is swept. Today that means
    `add-libs!` has run: a jar cannot be unloaded, so the image can never be
    baseline again. Guarding this at the CALL SITE was discipline, not a
    guarantee (a session can gain deps after it opens); fingerprinting the
    classloader silently MISSED (nREPL does not mutate the loader the probe
    read). Recording the fact where it happens is what finally closed it.
  - The SWEEP covers what a TENANT can define, and deliberately leaves
    `clojure.*` / `nrepl.*` alone. Removing Clojure's own lazily-loaded
    machinery — `clojure.repl.deps` above all — left `add-libs` unable to put
    a jar on the classpath at all, so a recycled session could not take a
    dependency. Those namespaces are the runtime's, not a tenant's, and
    leaking them between tenants leaks nothing a tenant wrote.

  **The safety is the verification, not the removal.** `remove-ns` is not
  trusted: the image is asked what it has AFTERWARDS, and unless every
  survivor is either in the recorded baseline or runtime machinery, this
  returns nil. A partial reset handed to the next tenant is a false green —
  the one failure the whole oracle exists to prevent — so any doubt refuses.
  No baseline recorded, or a probe that throws, is itself a doubt.

  Residual, stated rather than hidden: a store that named a namespace
  `clojure.…` or `nrepl.…` would survive the sweep. Nothing in slopp creates
  such a name, and the alternative — sweeping the runtime's own namespaces —
  is measured to break dependency loading.

  Also clears `rt/touched-sink`, which lives in `slopp.rt` and therefore
  SURVIVES the sweep: a leftover sink would silently drain the next tenant's
  trace into the previous tenant's collector.

  **Deliberately not used by `fresh-image!`.** That call is the D5 staleness
  backstop — a genuinely new process is the whole point of it, and recycling
  there would undermine the diagnostic that catches a stale image."
  [image]
  (when-let [{:keys [nses]} (:baseline image)]
    (try
      (when-not (first (eval! image dirty-probe))
        (let [b (set nses)]
          (eval! image
                 (str "(do (when-let [v (resolve 'slopp.rt/touched-sink)]"
                      "      (reset! (var-get v) nil))"
                      "    (let [runtime? (fn [n] (or (.startsWith (name n) \"clojure.\")"
                      "                               (.startsWith (name n) \"nrepl.\")))]"
                      ;; QUOTED: the baseline is data. Unquoted, the image tries
                      ;; to resolve every namespace name in it as a var and the
                      ;; whole form throws — which this then correctly refuses
                      ;; on, but silently, since eval! surfaces values not errors.
                      "      (doseq [n (map ns-name (all-ns))]"
                      "        (when-not (or (contains? '" (pr-str b) " n) (runtime? n))"
                      "          (remove-ns n))))"
                      "    (in-ns 'user) :reset)"))
          ;; ASK, do not assume: the image reports what it actually has left,
          ;; and every survivor must be baseline or runtime machinery
          (let [left (set (first (eval! image "(set (map ns-name (all-ns)))")))
                runtime? (fn [n] (or (.startsWith (name n) "clojure.")
                                     (.startsWith (name n) "nrepl.")))]
            (when (every? #(or (contains? b %) (runtime? %)) left)
              image))))
      (catch Throwable _ nil))))

(defonce ^{:ambient-ok "process-global by necessity: an image is an OS
  subprocess, so the pool of reusable ones is a property of this JVM and not
  of any session. Bounded, and every path that cannot prove an image clean
  destroys it instead of adding it here."}
  parked
  (atom []))

(def ^:export recycle-limits
  "The two bounds on image reuse, and why each exists.

  `:parked` — how many reset images may idle at once. Each is a JVM holding
  tens of megabytes, and the workload that benefits (a test shard opening and
  closing sessions in sequence) needs exactly one at a time, so two is
  generous rather than tuned.

  `:reuses` — how many tenants one image may serve. `reset-to-baseline!`
  verifies the NAMESPACE SET and nothing beyond it, so anything outside that
  view — a thread a tenant started, a system property it set, a shutdown hook
  it registered — accumulates unseen. The cap turns an unbounded slow leak
  into a bounded one, which is the difference between a wrong answer someday
  and a slightly slower suite."
  {:parked 2 :reuses 50})

^:unsafe (defn ^:export unpark!
  "Take a reset image from the pool, or NIL when there is none — in which case
  the caller boots for real, exactly as it always did.

  Every parked image was verified back to its boot baseline BEFORE it was
  parked, so this hands over something already proven rather than something
  about to be checked. Nil is the ordinary case and costs nothing."
  []
  (when (nil? (System/getenv "SLOPP_NO_RECYCLE"))
    (peek (first (swap-vals! parked #(cond-> % (seq %) pop))))))

(defn ^:export stop!
  "Destroy the image subprocess and release its connection. `image` is the
  opaque handle from `start!` — see `eval!` for why it is not destructured.
  Tolerates a partially-built or foreign-shaped handle: each resource is
  released only if present, which is what lets `restart!` rebuild from a
  broken one.

  The PROCESS goes first: closing a broken transport can throw, and a throw
  must never save the child. destroyForcibly backs the 5s graceful window."
  [image]
  (when-let [^Process process (:process image)]
    (.destroy process)
    (when-not (.waitFor process 5 TimeUnit/SECONDS)
      (.destroyForcibly process)))
  (when-let [^java.io.Closeable conn (:conn image)]
    (try (.close conn) (catch Exception _)))
  nil)

^:unsafe (defn ^:export drain-parked!
  "Stop every parked image and empty the pool. For shutdown, and for any test
  that needs to reason about a cold pool — a parked image is still a JVM
  subprocess, and an unreaped one is a leaked JVM.

  Not required for correctness on abnormal death: every image carries the
  parent-death watchdog, so a parked one dies with this process even on
  SIGKILL. This is the tidy path, not the safety net."
  []
  (doseq [img (first (swap-vals! parked (constantly [])))]
    (try (stop! img) (catch Throwable _ nil)))
  nil)

^:unsafe (defn ^:export park!
  "Offer `image` for reuse instead of destroying it. Returns true when it was
  parked, false when it was stopped — and STOPPING IS THE DEFAULT: every path
  that cannot prove the image safe to hand on ends in `stop!`.

  Parked only when all of these hold: recycling is not switched off
  (`SLOPP_NO_RECYCLE`), the image is under its reuse cap, the pool has room,
  and — the load-bearing one — `reset-to-baseline!` VERIFIED it back to its
  boot namespace set. A partial reset handed to the next tenant is a false
  green, the one failure the oracle exists to prevent, so every doubt resolves
  to a fresh JVM.

  A caller that added libs to an image must NOT park it: `add-libs!` cannot be
  undone, so its classpath is no longer the one the baseline was taken on."
  [image]
  (if (and image
           (nil? (System/getenv "SLOPP_NO_RECYCLE"))
           (< (:reuses image 0) (:reuses recycle-limits))
           (< (count @parked) (:parked recycle-limits))
           (reset-to-baseline! image))
    (do (swap! parked conj (update image :reuses (fnil inc 0))) true)
    (do (stop! image) false)))

(defn restart!
  "Stop the image and start a fresh one (the D5 correctness backstop). Returns a
  new handle; the old one is dead."
  ([handle] (restart! handle {}))
  ([handle opts] (stop! handle) (start! opts)))
