(ns slopp.repl
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

(def clojure-bin
  "The clojure launcher for owned images: SLOPP_CLOJURE env override, else the
  first executable found in the usual install locations, else trust PATH.
  Public so `slopp.deps` reuses the same launcher for classpath resolution."
  (or (System/getenv "SLOPP_CLOJURE")
      (some (fn [dir]
              (let [f (io/file dir "clojure")]
                (when (.canExecute f) (str f))))
            ["/opt/homebrew/bin" "/usr/local/bin" "/usr/bin"])
      "clojure"))

(def inherent-deps
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

(defn- default-cmd
  "The target image launch command: Clojure + nREPL, plus the store's external
  dependency manifest (`deps`, lib→coord) merged into `-Sdeps` so store code
  that requires those libs compiles (trust Tier 1). `inherent-deps` (nREPL,
  malli) are merged LAST so slopp-the-tool's own image deps are always present
  at slopp's versions — regardless of the project manifest."
  ([] (default-cmd nil))
  ([deps]
   [clojure-bin "-Sdeps"
    (pr-str {:deps (merge deps inherent-deps)})
    "-M" "-m" "nrepl.cmdline"]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-image" (make-array FileAttribute 0))))

(defn- read-port
  "Block reading the subprocess's merged output until it announces its port."
  [^BufferedReader rdr timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "owned image did not report a port in time" {})))
      (if-let [line (.readLine rdr)]
        (if-let [m (re-find #"port (\d+)" line)]
          (Long/parseLong (second m))
          (recur))
        (throw (ex-info "owned image ended before reporting a port" {}))))))

^:unsafe (defn eval!
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

(defn- inject-rt!
  "Load slopp's runtime support (slopp.rt — traced test execution) into the
  image, install a parent-death watchdog, wrap rt against itself (#126), then
  return to `user`. Every owned image carries all of it.

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

  The WATCHDOG closes a subprocess leak: a ProcessBuilder child is orphaned,
  not killed, when its parent JVM dies abnormally (OOM, SIGKILL, a killed
  test_run) — no shutdown hook can catch that. The child's stdin is a pipe from
  the parent, so a daemon thread blocked on `System/in` sees EOF the moment the
  parent's fds close, and exits the image. `nrepl.cmdline` never reads stdin,
  so the thread owns it uncontended."
  [handle]
  (eval! handle (slurp (io/resource "slopp/rt.clj")))
  (eval! handle "(when-let [f (resolve 'slopp.rt/self-instrument!)] (f))")
  (eval! handle
         (str "(doto (Thread. (fn [] (try (while (not (neg? (.read System/in))))"
              " (catch Throwable _)) (System/exit 0)) \"slopp-parent-watchdog\")"
              " (.setDaemon true) (.start))"))
  (eval! handle "(in-ns 'user)")
  handle)

(defn ^{:live-handle true
        :malli/schema
        [:=> [:cat [:? [:map
                        [:slopp.repl/cmd {:optional true} [:maybe [:sequential :string]]]
                        [:slopp.repl/dir {:optional true} [:maybe :some]]
                        [:slopp.repl/timeout-ms {:optional true} :int]
                        [:slopp.repl/deps {:optional true} [:maybe :map]]]]]
         :map]}
  start!
  "Launch a fresh owned image (with slopp.rt support loaded); returns a handle
  for eval!/restart!/stop!.

  The OPTION map is a caller-built contract, so its keys are qualified —
  unlike the handle this returns, whose keys are internal and read in the
  body by `eval!`/`stop!` rather than destructured at any boundary.

  The `:=>` schema is DOCUMENTATION here, not a verified claim: this fn
  spawns a JVM, so `analyzer-pure?` excludes it from the generative
  oracle-check. Nothing will catch it drifting from the impl — keep it
  honest by hand."
  ([] (start! {}))
  ([{:slopp.repl/keys [cmd dir timeout-ms deps] :or {timeout-ms 60000}}]
   (let [cmd (or cmd (default-cmd deps))
         dir (or dir (temp-dir))
         pb  (doto (ProcessBuilder. ^java.util.List cmd)
               (.redirectErrorStream true)
               (.directory (io/file dir)))
         proc (.start pb)
         rdr  (io/reader (.getInputStream proc))
         port (read-port rdr timeout-ms)
         conn (nrepl/connect :port port)
         client (nrepl/client conn 30000)
         session (nrepl/new-session client)]
     (inject-rt! {:process proc :port port :conn conn :client client
                  :session session :reader rdr :dir dir}))))

^:unsafe (defn eval-checked!
  "Like `eval!` but surfaces evaluation errors instead of silently dropping
  them (F-3c2 — an eval that throws must not look like an empty result).
  Returns {:values [...]} or {:err msg}. `image` is the opaque handle — see
  `eval!` for why it is not destructured."
  [image code]
  (let [msgs (doall (nrepl/message (:client image) {:op "eval" :code code
                                                    :session (:session image)}))
        errs (concat (keep :err msgs)
                     (mapcat (fn [m]
                               (when (some #{"eval-error"} (:status m))
                                 [(or (:ex m) "eval-error")]))
                             msgs))]
    (if (seq errs)
      {:err (str/trim (str/join " " (distinct errs)))}
      {:values (->> msgs (keep :value)
                    (mapv (fn [v] (try (read-string v) (catch Exception _ v)))))})))

(defn add-libs!
  "Hot-add dependency coords (`deps-map`, lib→coord) to the RUNNING image via
  Clojure 1.12 `clojure.repl.deps/add-libs` — no restart. Idempotent for
  already-present coords (so it also reconciles an adopted bare spare).
  Returns nil on success, or {:err msg} so the caller can fall back to a
  fresh image (a jar can't be unloaded, so removes/downgrades never hot-apply)."
  [handle deps-map]
  (when (seq deps-map)
    (let [r (eval-checked!
             handle
             (str "(do (require 'clojure.repl.deps)"
                  " (clojure.repl.deps/add-libs '" (pr-str deps-map) "))"))]
      (when (:err r) r))))

^:unsafe (defn load-checked!
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
        errs (concat (keep :err msgs)
                     (mapcat (fn [m]
                               (when (some #{"eval-error"} (:status m))
                                 [(or (:ex m) "eval-error")]))
                             msgs))]
    (if (seq errs)
      {:err (str/trim (str/join " " (distinct errs)))}
      {:values (->> msgs (keep :value)
                    (mapv (fn [v] (try (read-string v) (catch Exception _ v)))))})))

(defn stop!
  "Destroy the image subprocess and release its connection. `image` is the
  opaque handle from `start!` — see `eval!` for why it is not destructured.
  Tolerates a partially-built or foreign-shaped handle: each resource is
  released only if present, which is what lets `restart!` rebuild from a
  broken one."
  [image]
  (when-let [^java.io.Closeable conn (:conn image)] (.close conn))
  (when-let [^Process process (:process image)]
    (.destroy process)
    (.waitFor process 5 TimeUnit/SECONDS))
  nil)

(defn restart!
  "Stop the image and start a fresh one (the D5 correctness backstop). Returns a
  new handle; the old one is dead."
  ([handle] (restart! handle {}))
  ([handle opts] (stop! handle) (start! opts)))
