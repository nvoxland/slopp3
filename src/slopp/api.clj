(ns slopp.api
  "The agent-facing operation surface (the tools an MCP adapter exposes). A
  session is an atom holding the evolving store + the owned image. Everything is
  form-addressed (ns/name), never file+line: the agent *sees* code via
  `query-source` (the VFS) and *edits* only through `edit-replace!` (tracked
  deltas). `query-eval` lets it observe the live image (the oracle) without
  mutating code.

  With `{:dir ...}` the session is durable (C7): the store is write-through to
  SQLite at `<dir>/.slopp/store.db`, and `open!` reconstructs both the store and
  the live image from it. Without `:dir` the session is ephemeral (tests,
  scratch)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.store.render :as render]
            [slopp.image.repl :as repl]
            [slopp.image :as image]
            [slopp.edit :as edit]
            [slopp.edit.refactor :as refactor]
            [slopp.index.normalize :as normalize]
            [slopp.store.db :as db] [rewrite-clj.parser :as p] [slopp.api.history :as history] [slopp.api.deps :as api.deps] [slopp.api.session :as session] [slopp.api.modules :as modules] [slopp.api.orient :as orient] [slopp.edit.modules :as edit.modules] [slopp.api.rules :as rules] [slopp.api.done :as done] [slopp.api.shape :as shape] [slopp.api.query :as query] [slopp.index.analyze :as analyze] [slopp.edit.lintgate :as lintgate] [slopp.api.capabilities :as capabilities] [clojure.edn :as edn] [slopp.store.fields :as fields] [slopp.edit.refs :as refs] [slopp.api.telemetry :as telemetry] [slopp.api.artifacts :as artifacts] [clojure.java.io :as io] [slopp.api.currency :as currency] [slopp.image.currency :as registry] [slopp.boot :as boot]))

(defn reap-idle-images!
  "Stop parked branch images idle past the session TTL (the session's reaper
  timer calls this periodically; callable directly). Returns {:reaped n}."
  [session]
  (let [ttl     (:branch-image-ttl-ms @session 600000)
        now     (System/currentTimeMillis)
        victims (volatile! #{})]
    (swap! session update :lines
           (fn [lines]
             (into {}
                   (map (fn [[nm line]]
                          (if (and (:image line)
                                   (> (- now (:last-used line 0)) ttl))
                            (do (vswap! victims conj (:image line))
                                [nm (dissoc line :image)])
                            [nm line])))
                   lines)))
    (doseq [img @victims] (repl/stop! img))
    {:reaped (count @victims)}))

(defn adopt-modules!
  "ADOPTION (internal — never a tool, never explicit): derive the module
  manifest from the CURRENT actual dependency graph — kondo-resolved, so
  :refer'd calls count — and record it as one :module-edge delta per edge.
  Called once by open! for a populated store whose db predates the module
  system (:modules nil); by construction the result is acyclic with zero
  violations, so adoption never breaks working code — the gate then blocks
  DRIFT until the agent declares new edges (module_dep)."
  [session & {:keys [agent]}]
  (let [edges (edit.modules/derive-module-edges (:store @session))]
    (session/commit-appended!
     session
     (fn [base]
       (reduce (fn [s [m deps]]
                 (reduce (fn [s2 dep]
                           (first (store/record-module-edge
                                   s2 m dep :add
                                   :prompt "module adoption: edge derived from the actual dependency graph"
                                   :agent agent)))
                         s (sort deps)))
               (update base :modules #(or % {}))
               (sort edges)))
     [])
    {:modules (count edges)
     :edges   (reduce + 0 (map count (vals edges)))}))

(defn await-image!
  "Block until the session's background image boot has finished, then return
  the session (its image live). A synchronously-opened session (the default)
  carries no ready-promise and returns immediately. A boot FAILURE delivered
  to the promise is RETHROWN here — with the async-boot server path the MCP
  connection is already up by the time the image loads, so a boot error
  surfaces on the first oracle/write call instead of killing the server at
  startup (which is what let a slow store race the MCP connect timeout)."
  [session]
  (when-let [p (:image-ready @session)]
    (let [r (deref p)]
      (when (instance? Throwable r) (throw r))))
  session)

(defn close! "Release everything the session owns and return nil: its image, a warm spare
  still booting, the SQLite connection, every per-branch line's image and
  connection, and the idle-image reaper timer.

  Always call it — an owned image is a JVM subprocess, so a dropped session
  leaks one. `(try … (finally (api/close! sess)))` is the shape every test and
  entry point uses. Safe on a partially-built session: each resource is
  released only if present, and each release is ISOLATED — a throwing close
  (broken transport, a spare whose boot failed) must not leak everything
  after it. The spare deref is bounded: boot itself is bounded by start!'s
  timeout, so the cap only guards a wedged future thread."
  [session]
  (letfn [(safely! [f] (try (f) (catch Throwable _ nil)))]
    ;; PARK, not stop: the image's Clojure runtime is identical to the one the
    ;; next session would spend ~830ms rebuilding. park! verifies the image
    ;; back to its boot baseline and STOPS it whenever it cannot — so the
    ;; worst case here is exactly the old behaviour.
    ;; PARK, not stop: the image's Clojure runtime is identical to the one the
    ;; next session would spend ~830ms rebuilding. Keyed by the store's OWN
    ;; dependency manifest — that is what put the jars on this image's
    ;; classpath, so it is the honest key, and it lets a real project with
    ;; deps recycle exactly as well as an empty one. park! verifies the image
    ;; back to its boot baseline and STOPS it whenever it cannot, so the worst
    ;; case here is exactly the old behaviour.
    (safely! #(repl/park! (:image @session) (:deps (:store @session))))
    (safely! #(when-let [spare (:spare @session)]
                (repl/stop! (deref spare 65000 nil))))   ; reap even if still booting
    (safely! #(when-let [^java.sql.Connection conn (:db @session)]
                (.close conn)))
    (doseq [[_ line] (:lines @session)]
      (safely! #(when-let [img (:image line)] (repl/stop! img)))
      (safely! #(when-let [^java.sql.Connection c (:conn line)]
                  (.close c))))
    (safely! #(when-let [^java.util.Timer t (:reaper @session)] (.cancel t))))
  nil)

(defn sync-with-journal!
  "m5b: absorb commits made by OTHER servers sharing this store dir. Cheap
  when nothing changed (one PRAGMA read). On foreign commits: refresh the
  cached store from the journal, reload every namespace whose source changed
  into the LOCAL image, and drop trace entries touching the changed
  namespaces (conservative — narrowing rebuilds). Returns {:synced n-nses}
  or nil when already current. The MCP dispatch calls this before every
  tool, so servers converge continuously."
  [session]
  (when-let [conn (and (:image @session) (:db @session))]
    (let [v (db/data-version conn)]
      (when (not= v (:data-version @session))
        (let [old (:store @session)]
          (session/refresh-cache! session)
          (swap! session assoc :data-version v)
          (let [new (:store @session)]
            (if (identical? old new)
              {:synced 0}
              (let [changed (filterv #(not= (render/render-ns old %)
                                            (render/render-ns new %))
                                     (store/ns-dependency-order new))
                    stale   (into #{}
                                  (mapcat (fn [n]
                                            (map #(symbol (str n)
                                                          (str (or (:name %) (:id %))))
                                                 (store/forms new n))))
                                  changed)]
                (doseq [n changed]
                  (image/load-ns! (:image @session) new n))
                (swap! session update :test-map
                       (fn [tm]
                         (into {}
                               (remove (fn [[t forms]]
                                         (or (contains? stale t)
                                             (seq (set/intersection forms stale)))))
                               tm)))
                (session/persist-trace! session)
                {:synced (count changed)}))))))))

(defn ingest!
  "The batch write for BRAND-NEW namespaces (W1, user decision): land a whole
  namespace's source in one call. Compile-gated like every write (the image
  loads it FIRST; a failed load commits nothing — T4), then verified and
  recorded like every write. Overwriting an existing namespace is NOT allowed
  — edit its forms instead. Returns {:ns :forms :test} or {:error msg}.

  A :cljs (non-jvm-loadable) namespace is ingested the same way, but its code
  references js/* / the DOM and cannot load into the JVM oracle, so ingest
  SKIPS the hot-load and defers verification to the ClojureScript compiler
  (compile_client) — reporting `cljs-deferred-summary`. D-web-cljs."
  [session ns-sym source & {:keys [agent]}]
  (if-let [pre (or (when (get-in (:store @session) [:namespaces ns-sym])
                     (str ns-sym " already exists — edit its forms instead"
                          " (whole-namespace overwrite is not allowed)"))
                   ;; parse-form guards the per-FORM door; this is the other one
                   (edit/control-char-refusal source))]
    {:error pre}
    (try
      (let [base      (:store @session)
            candidate (store/ingest base ns-sym source :agent agent)
            load?     (store/jvm-loadable? base ns-sym)]
        (if-let [derr (or (edit/dialect-scan candidate ns-sym)
                          ;; bulk imports (clone) land reality first and derive
                          ;; the manifest after — the gate blocks DRIFT, not adoption
                          (when-not (:adopting? @session)
                            (edit.modules/module-scan candidate ns-sym)))]
          ;; same D3/D4 gate the edit path enforces — a host form can only enter
          ;; the store already ^:unsafe, so imported code is never frozen and the
          ;; image is never touched by a rejected namespace.
          {:error derr}
          (let [load!   #(repl/load-checked! (:image @session)
                                             (render/render-ns candidate ns-sym)
                                             (render/ns-path ns-sym
                                                             (store/platform-for base ns-sym)))
                ;; :cljs code never loads into the JVM oracle — skip the hot-load
                ;; and let the cljs compiler be its gate (below).
                res     (if load? (load!) {})
                ;; the generic red-first seam, ingest face: a spec ns naming
                ;; not-yet-written vars stubs + retries instead of refusing
                stubbed (when (and load? (:err res))
                          (session/stub-missing-test-vars! (:image @session) candidate [ns-sym]))
                res     (if (and stubbed (nil? (:err (load!)))) {} res)]
            (cond
              (:err res)
              {:error (str "namespace failed to load: " (:err res))}

              (not (session/try-commit! session base candidate [ns-sym]))
              {:conflict {:reason "store changed during ingest — retry"}}

              :else
              (do
                (when load?
                  (repl/eval! (:image @session)
                              (format "(dosync (commute (deref #'clojure.core/*loaded-libs*) conj '%s))"
                                      ns-sym))
                  ;; ingest loads through `load-checked!` directly rather than
                  ;; `image/load-ns!`, so it is a THIRD door and has to stamp
                  ;; what it put there. Missing this, every ingested namespace
                  ;; read as :never-loaded forever — and nothing derived from
                  ;; it could ever be judged stale, because staleness is only
                  ;; meaningful for a form the image is known to hold.
                  (doseq [e (store/elements candidate ns-sym)]
                    (registry/stamp! (:id e) (n/string (:node e)))))
                (let [edited  (into #{}
                                    (keep (fn [e]
                                            (when (:name e)
                                              (symbol (str ns-sym) (str (:name e))))))
                                    (store/forms candidate ns-sym))
                      summary (if load?
                                (session/run-verification! session ns-sym nil :edited edited)
                                session/cljs-deferred-summary)
                      recompiled (session/maybe-recompile-client! session ns-sym)]
                  (session/commit-appended! session
                                            #(store/record-verification % ns-sym summary)
                                            [])
                  (cond-> {:ns ns-sym
                           :forms (count (store/forms candidate ns-sym))
                           :warnings (vec (edit/ns-warnings candidate ns-sym))
                           :test summary}
                    stubbed (assoc :red-first stubbed
                                   :note (str "these vars don't exist yet — stubbed"
                                              " in-image as failing (red-first);"
                                              " implement them to go green."))
                    recompiled (merge recompiled))))))))
      (catch Exception e
        {:error (str "unparseable source (unbalanced?): " (ex-message e))}))))

(defn turn-begin!
  "Open `agent`'s turn, recording the VERBATIM user ask as the root intent of
  everything until turn-end. A new begin supersedes an unclosed one. The
  intent also stays on the session (:last-intent) — orientation mines it so
  the brief arrives task-shaped.

  Also resets the wall-clock ring (`:slopp.api.telemetry/calls`) so this ask
  measures only itself. The wire records a call AFTER it returns — otherwise
  `turn_end` would read a half-finished entry for itself — which leaves the
  previous turn's closing bracket in the ring. Clearing here is what keeps one
  ask's cost from bleeding into the next."
  [session & {:keys [agent intent user]}]
  (when intent (swap! session assoc :last-intent intent))
  (swap! session dissoc :slopp.api.telemetry/calls)
  (session/commit-appended! session
                    #(first (store/record-turn % :turn-begin
                                               :agent agent :intent intent
                                               :user user))
                    [])
  {:turn :open :agent agent :intent intent})

(defn turn-end!
  "Close `agent`'s turn (stable or not — a red turn is still history), and
  record where the turn's WALL CLOCK went.

  The wire accumulates one `{:tool :start :end}` per call into the session
  (`:slopp.api.telemetry/calls`); this folds it with
  `telemetry/call-timing` onto the `:turn-end` delta and clears the ring, so
  each ask measures only itself. Nothing called → no `:timing` key, rather
  than a zeroed record that would read as measured.

  The turn is the right grain because it is the USER-ASK bracket the prompt
  hook already maintains: the question worth answering is \"what did this ask
  cost, and how much of it was slopp\", and until now the second half had no
  producer at all. This call's own time is not in its own total — it is still
  in flight."
  [session & {:keys [agent note]}]
  (let [timing (telemetry/call-timing (:slopp.api.telemetry/calls @session))]
    (session/commit-appended! session
                      #(first (store/record-turn % :turn-end
                                                 :agent agent :note note
                                                 :timing timing))
                      [])
    (swap! session dissoc :slopp.api.telemetry/calls)
    (cond-> {:turn :closed :agent agent}
      timing (assoc :timing timing))))

(defn turn-open?
  "Does `agent-label` (or any of its path ancestors — sub-agents ride the
  root agent's turn) have an open :turn-begin?"
  [session agent-label]
  (let [ds (store/deltas (:store @session))
        open? (fn [lbl]
                (let [marks (filter #(and (contains? #{:turn-begin :turn-end}
                                                     (:op %))
                                          (= lbl (:agent %)))
                                    ds)]
                  (= :turn-begin (:op (last marks)))))
        roots (when agent-label
                (let [parts (clojure.string/split agent-label #"/")]
                  (map #(clojure.string/join "/" (take (inc %) parts))
                       (range (count parts)))))]
    (boolean (some open? (or roots [agent-label])))))

^:reads (defn query-eval
  "Observe-only eval against the live image (the oracle): call anything —
  including effectful fns — but (re)defining code is rejected (T5); writes go
  through the edit tools so provenance stays airtight.

  Returns the values, or `{:error msg}` when the eval actually FAILED — nREPL's
  `eval-error` status, not merely something reaching stderr. A library that
  prints at load (`WARNING: abs already refers to …`) is output, not failure:
  its warning is dropped here, as in any REPL, and the values come back. It used
  to come back as `{:error …}` with the values discarded, and since a second
  eval finds the namespace loaded and prints nothing, the failure vanished on
  retry."
  [session code]
  (if-let [err (edit/observe-gate code)]
    {:error err}
    ;; strip :reload in the owned image — no source files exist to reload, so it
    ;; would only throw FileNotFoundException (store ns) or waste a jar re-read
    (let [r (repl/eval-checked! (:image @session) (edit/strip-image-reload code))]
      (if (:err r)                                  ; F-3c2: never a silent []
        {:error (:err r)}
        (:values r)))))

^:reads (defn query-call
  "Observe-only INVOKE of one var in the live image: `(query-call session
  'app.core/f 1 2)` — the structured face of the common query-eval case.
  The var reference is CARRIED (a quoted symbol in a designated position —
  renames, moves, and the unused gate all see it) instead of hidden in an
  eval string; args must be printable data (they cross the nREPL boundary
  as pr-str). query-eval remains the escape hatch for genuinely arbitrary
  expressions."
  [session qsym & args]
  (query-eval session
              (str "(" qsym (apply str (map #(str " " (pr-str %)) args)) ")")))

^:reads (defn query-observe
  "Run `driver-code` (observe-gated) while capturing the args and return value
  of up to `:limit` calls to `ns-sym/nm` — the oracle's direct answer to 'what
  flows through this function?' (D2: observe, don't declare)."
  [session ns-sym nm driver-code & {:keys [limit] :or {limit 10}}]
  (if-let [err (edit/observe-gate driver-code)]
    {:error err}
    (first (repl/eval! (:image @session)
                       (format "(slopp.rt/observe '%s/%s (fn [] %s) %d)"
                               ns-sym nm driver-code limit)))))

^:reads (defn query-macroexpand
  "Expand a form (built-in macros are part of the dialect; expansion is how
  the oracle explains them). Returns {:expand-1 str :full str} or {:error}."
  [session code]
  (try
    (let [{:keys [error]} (edit/parse-form code)]
      ;; parse-form also dialect-checks; for expansion we only care that it READS
      (if (and error (re-find #"unparseable" error))
        {:error error}
        {:expand-1 (first (repl/eval! (:image @session)
                                      (format "(pr-str (macroexpand-1 '%s))" code)))
         :full     (first (repl/eval! (:image @session)
                                      (format "(pr-str (macroexpand '%s))" code)))}))
    (catch Exception e {:error (ex-message e)})))

(defn restart!
  "D5 escape hatch: the agent-callable fresh-image restart."
  [session]
  (session/fresh-image! session)
  session)

(defn edit-replace!
  "Replace the form `nm` in `ns-sym` with `new-source` (O1 whole-form replace):
  pipeline + hot-reload, then re-verify — only the tests the trace map says
  exercise this form (D1), cross-checked on a fresh image if red (D5) — and
  record the outcome as provenance (C4). A replace that RENAMES the form
  refuses while committed callers still reference the old name — edit_rename
  is the atomic path (the store must keep cold-loading)."
  [session ns-sym nm new-source & {:keys [prompt agent]}]
  (let [t0 (System/nanoTime)
        pf       (edit/parse-form new-source)
        ;; a replaced defmethod leaves its OLD dispatch registered unless the
        ;; replacement re-registers the same [multi dispatch] (#131): hot-load
        ;; evals the new form, but nothing removes the old method, so the image
        ;; answers BOTH dispatches while the store says one — green-when-red.
        old-node (some-> (store/form-named (:store @session) ns-sym nm) :node)
        old-s    (when old-node
                   (try (n/sexpr old-node) (catch Exception _ nil)))
        new-s    (when-not (:error pf)
                   (try (n/sexpr (:node pf)) (catch Exception _ nil)))
        unregister
        (when (and (seq? old-s) (= 'defmethod (first old-s)) (> (count old-s) 2)
                   (not (and (seq? new-s) (= 'defmethod (first new-s))
                             (= (second old-s) (second new-s))
                             (= (nth old-s 2) (nth new-s 2)))))
          (format "(when-let [v (ns-resolve '%s '%s)]
                     (when (instance? clojure.lang.MultiFn @v)
                       (remove-method @v
                         (binding [*ns* (find-ns '%s)] (eval '%s)))))"
                  ns-sym (second old-s) ns-sym (pr-str (nth old-s 2))))
        new-name (when-not (:error pf) (store/form-symbol (:node pf)))
        stranded (when (and new-name (not= new-name (symbol (str nm)))
                            (store/form-named (:store @session) ns-sym nm))
                   (let [st    (:store @session)
                         known (set (keys (:namespaces st)))]
                     (vec (distinct
                           (for [nsx known
                                 u   (:var-usages (analyze/analyze (render/render-ns st nsx)))
                                 :when (and (= (symbol (str ns-sym)) (:to u))
                                            (= (symbol (str nm)) (:name u))
                                            (not (and (= nsx (symbol (str ns-sym)))
                                                      (= (symbol (str nm)) (:from-var u)))))]
                             (symbol (str nsx) (str (:from-var u))))))))]
    (if (seq stranded)
      {:error (str "this replace RENAMES " nm " → " new-name " but committed"
                   " callers still reference " ns-sym "/" nm ": " stranded
                   " — edit_rename rewrites every caller atomically (or land"
                   " the callers in this same change)")}
      (let [pre-warned (set (map :var (edit/ns-warnings (:store @session) ns-sym)))
load? (store/jvm-loadable? (:store @session) ns-sym)
            r (session/rebased-write!
               session
               (fn [base] (edit/replace-form base ns-sym nm new-source
                                             :prompt prompt :agent agent))
               (fn [base] (:node (store/form-named base ns-sym nm)))
               (symbol (str ns-sym) (str nm))
               ns-sym
               :load? load?)]
        (if (or (:error r) (:conflict r))
          r
          (let [qform    (symbol (str ns-sym) (str nm))
                new-nm   (:name (store/form-by-id (:store r)
                                                  (:form-id (:delta r))))
                edited   (into #{qform}
                               (when new-nm [(symbol (str ns-sym) (str new-nm))]))
                affected (or (session/affected-tests session ns-sym nm)
                             ;; an alias-only require addition is semantically
                             ;; inert — verify NOTHING rather than the whole
                             ;; namespace reach (frictions #2); [] is honest
                             ;; (:coverage :none), never a claimed green
                             (when (session/inert-ns-require-change?
                                    (:store @session) (:form-id (:delta r)))
                               []))
                untested (and (nil? affected) (seq (:test-map @session))
                               (not (re-find #"^\(\s*(?:clojure\.test/)?deftest\b"
                                             (str/triml new-source))))
                _        (when (and new-nm (not= new-nm nm))
                           (repl/eval! (:image @session)
                                       (format "(ns-unmap '%s '%s)" ns-sym nm)))
                _        (when unregister
                           (repl/eval! (:image @session) unregister))
;; ROOT of frictions 1 and 17. A per-form hot-load is equivalent to
                ;; LOADING the form only when the form's whole contribution to
                ;; the image is its own var binding. Anything that CAPTURED a
                ;; value from it — a derived def, an evaluated metadata schema —
                ;; still holds the old one, and re-evaluating one form replays
                ;; none of that. `--live` never had this bug because it reloads
                ;; whole NAMESPACES; the oracle did because it reloads forms.
                ;;
                ;; So: keep the fast path, and when something captured, repair
                ;; through the same `load-ns!` every other loader uses. This is
                ;; measured to be rare — 35 of 2202 forms in this store capture
                ;; at load at all — so an ordinary defn write pays nothing.
                ;; Before verification deliberately: tests must run against a
                ;; repaired image, not a half-stale one.
                captured (when load?
                           (currency/stale-after (:store @session)
                                                 (:form-id (:delta r))))
                reloaded (when (seq captured)
                           (vec (sort (distinct (map (comp symbol namespace)
                                                     captured)))))
                reload-errs
                (when (seq reloaded)
                  (not-empty
                   (into {}
                         (keep (fn [nsx]
                                 (when-let [e (image/load-ns! (:image @session)
                                                              (:store @session)
                                                              nsx)]
                                   [nsx e])))
                         reloaded)))
                ;; what the repair could NOT reach — normally nothing, and
                ;; reported rather than assumed away when it happens
                stale    (when (seq reloaded)
                           (not-empty (currency/stale-after
                                       (:store @session)
                                       (:form-id (:delta r)))))
                ;; no trace evidence → fall back to the tests that REACH this
                ;; namespace, not to tests named after it (there are none)
                ;; a ^:live-handle constructor changed shape: the map already in
                ;; the session was built by the OLD code and no write can
                ;; reach it. Rebuild BEFORE verification, which would
                ;; otherwise be the first thing to read the stale handle —
                ;; and discard the warm spare, which was built under the old
                ;; code too (that is how it broke a second time).
                handle-shift (when (and old-node (:node pf))
                               (edit/live-handle-shape-change old-node (:node pf)))
                ;; The rebuild can FAIL mid-migration and that is normal: a shape
                ;; change lands on the constructor BEFORE its callers are
                ;; updated, so the fresh image may launch mis-configured (a
                ;; renamed option key read as nil). Keep the working image and
                ;; report it — the episode continues, and the next write once
                ;; the callers catch up rebuilds cleanly. Throwing here would
                ;; make a legitimate in-progress migration look like a broken
                ;; write.
                rebuild-err
                (when handle-shift
                  (swap! session assoc :spare nil)
                  (try (session/fresh-image! session) nil
                       (catch Throwable t (ex-message t))))
                scope    (if affected
                           ns-sym
                           (or (seq (session/covering-test-nses
                                     (:store @session) [ns-sym]))
                               ns-sym))
                summary  (if load?
                             (session/run-verification! session scope affected
                                                        :edited edited)
                             session/cljs-deferred-summary)
                existing (count (filter (comp pre-warned :var) (:warnings r)))
recompiled (session/maybe-recompile-client! session ns-sym)]
            (session/commit-appended! session
                              #(store/record-verification % ns-sym summary) [])
            (session/with-ms
              (cond-> {:delta    (:delta r)
                       ;; T3: only NEW violations; pre-existing ones as a count
                       :warnings (vec (remove (comp pre-warned :var) (:warnings r)))
                       :test     summary
                       :affected (or affected :all)}
                (:image-healed r) (assoc :image-healed true)
                ;; what this write changed BEYOND what was asked — a lost type
                ;; hint, docstring or arity. Reported, never refused.
                (seq (:drift r)) (assoc :drift (:drift r))
                ;; friction 1: forms this write left holding a value computed
                ;; from the OLD source. Not :drift — that key is taken, and it
                ;; means something else on this very map.
                ;; the repair, reported: a namespace reload is a real cost and a
                ;; silent one reads as an unexplained slow write
                (seq reloaded) (assoc :image-reloaded reloaded)
                reload-errs    (assoc :image-reload-failed reload-errs)
                (seq stale)    (assoc :stale-in-image (vec stale))
                ;; say WHY the image was replaced — a silent rebuild is a
                ;; surprising cost, and the reason is the teaching
                handle-shift (assoc :image-rebuilt
                                    (cond-> (assoc handle-shift
                                                   :reason :live-handle-shape-change)
                                      rebuild-err
                                      (assoc :rebuild-failed rebuild-err
                                             :note (str "kept the working image — normal"
                                                        " MID-MIGRATION, when the constructor"
                                                        " has changed but its callers have"
                                                        " not. Update them and the next write"
                                                        " rebuilds cleanly."))))
                (pos? existing)   (assoc :existing-warnings existing)
                untested          (assoc :untested true)
                (:red-first r)    (assoc :red-first (:red-first r)
                                         :note (str "these vars don't exist yet — stubbed"
                                                    " in-image as failing (red-first);"
                                                    " implement them to go green."))
                (:carried-errors r) (assoc :carried-errors (:carried-errors r))
                recompiled          (merge recompiled))
              t0)))))))

(defn add-form!
  "Add a new top-level form to `ns-sym` (O1 base write): dialect gate, `:add`
  delta, hot-reload into the image, verification, provenance. `:before
  <form-name>` anchors the new form immediately before that one (default:
  appended at the tail) — define-before-use without a follow-up move.
  Returns {:delta :warnings :test :affected} or {:error msg}.

  A :cljs (non-jvm-loadable) namespace is authored the same way but its form
  references js/* / the DOM and cannot load into the JVM oracle, so the write
  SKIPS the per-form hot-load (`:load? false`) and defers verification to the
  ClojureScript compiler — reporting `cljs-deferred-summary` (:unverified,
  reason :cljs-deferred-to-compile) rather than running the suite. D-web-cljs."
  [session ns-sym source & {:keys [prompt agent before]}]
  (let [t0 (System/nanoTime)
        {:keys [node error]} (edit/parse-form source)
        nm (some-> node store/form-symbol)
        iso (when node
              (edit/isolation-refusal
               (edit/require-aliases (:store @session) ns-sym) node))]
    (cond
      error {:error error}

      iso {:error iso}

      (and nm (store/form-named (:store @session) ns-sym nm))
      {:error (str nm " already exists in " ns-sym)}

      :else
      (let [pre-warned (set (map :var (edit/ns-warnings (:store @session) ns-sym)))
            load?      (store/jvm-loadable? (:store @session) ns-sym)
            r (session/rebased-write!
               session
               (fn [base]
                 (cond
                   (and nm (store/form-named base ns-sym nm))
                   {:error (str nm " already exists in " ns-sym)}

                   (and before (not (store/form-named base ns-sym before)))
                   {:error (str "no anchor form named " before " in " ns-sym
                                " — :before must name an existing form")}

                   :else
                   (if-let [[st' d] (store/append-form base ns-sym node
                                                       :prompt prompt :agent agent
                                                       :before before)]
                     (if-let [merr (when nm (edit.modules/gate-refusal st' ns-sym nm))]
                       {:error merr}
                       {:store st' :delta d})
                     {:error (str "no namespace " ns-sym " (ingest it first)")})))
               (fn [base] (when nm (:node (store/form-named base ns-sym nm))))
               (symbol (str ns-sym) (str (or nm "anonymous")))
               ns-sym
               :load? load?)]
        (if (or (:error r) (:conflict r))
          r
          (let [edited     (if nm #{(symbol (str ns-sym) (str nm))} #{})
                affected   (when (and load? nm) (session/affected-tests session ns-sym nm))
                summary    (if load?
                             (session/run-verification! session ns-sym affected
                                                        :edited edited)
                             session/cljs-deferred-summary)
                all-w      (edit/ns-warnings (:store @session) ns-sym)
                existing   (count (filter (comp pre-warned :var) all-w))
                advisories (when nm (:advisories (edit.modules/gate-check
                                                  (:store @session) ns-sym nm)))
recompiled (session/maybe-recompile-client! session ns-sym)]
            (session/commit-appended! session
                                      #(store/record-verification % ns-sym summary)
                                      [])
            (session/with-ms
              (cond-> {:delta    (:delta r)
                       ;; T3: only NEW violations; pre-existing as a count
                       :warnings (vec (remove (comp pre-warned :var) all-w))
                       :test     summary
                       :affected (or affected :all)}
                (:image-healed r) (assoc :image-healed true)
                (pos? existing)   (assoc :existing-warnings existing)
                (:red-first r)    (assoc :red-first (:red-first r)
                                         :note (str "these vars don't exist yet —"
                                                    " stubbed in-image as failing"
                                                    " (red-first); implement them to"
                                                    " go green."))
                (:carried-errors r) (assoc :carried-errors (:carried-errors r))
                (seq advisories)    (assoc :advisories advisories)
                recompiled          (merge recompiled))
              t0)))))))

(defn delete-form!
  "Delete the form named `nm` from `ns-sym`: `:delete` delta, `ns-unmap` in the
  image, verification, provenance.

  **Refuses while anything still CALLS it** (`edit/live-callers-error`), the
  same stance `delete-ns!` has always taken for a namespace something still
  requires. This docstring used to promise the opposite — that a still-
  referenced delete was fine because \"tests that exercised it will go red, the
  honest signal\". There is no such signal: the namespace fails to RELOAD before
  any test runs, so verification reported zero tests and nothing wrong while
  the store stopped booting entirely (frictions 3f/19).

  The escape hatch is `edit_group`, which is deliberately NOT guarded this way:
  its steps apply in order to one store value and verify once at the end, so a
  caller and its callee can go together, and a mid-sequence state with a
  dangling reference is legitimate. That also keeps `undo!` /
  `revert-episode!` / the rename sweeps working — they ride through the group
  path and replay machine-ordered deltas, where insisting on a
  never-dangling intermediate state would refuse correct work.

  Refuses when `nm` addresses TWO elements (a legacy `(declare nm)` beside its
  definition): resolving by position deletes whichever happens to come first,
  which is silent destruction of live code.

  A defmethod needs more than ns-unmap (#131): its name is its form id and its
  registration lives in the MULTI's method table, so ns-unmap is a no-op and
  the deleted method KEPT ANSWERING — tests stayed green after the delete, and
  green-when-red is the direction the staleness diagnostics never cross-check.
  The dispatch value is evaled in the form's own namespace, exactly where
  defmethod evaled it; if that fails the eval is skipped and the stale method
  survives until restart — conservative, and only reachable from a dispatch
  expression that itself no longer evaluates."
  [session ns-sym nm & {:keys [prompt agent]}]
  (or
   (edit/ns-form-delete-error ns-sym nm)
   (edit/ambiguous-form-error (:store @session) ns-sym nm)
   (edit/live-callers-error (:store @session) ns-sym nm)
   (let [victim  (store/form-named (:store @session) ns-sym nm)
         vsexpr  (when victim (try (n/sexpr (:node victim)) (catch Exception _ nil)))
         unregister
         (when (and (seq? vsexpr) (= 'defmethod (first vsexpr)) (> (count vsexpr) 2))
           (format "(when-let [v (ns-resolve '%s '%s)]
                     (when (instance? clojure.lang.MultiFn @v)
                       (remove-method @v
                         (binding [*ns* (find-ns '%s)] (eval '%s)))))"
                   ns-sym (second vsexpr) ns-sym (pr-str (nth vsexpr 2))))
         r (session/rebased-write!
            session
            (fn [base]
              (if-let [[st' d] (store/remove-form base ns-sym nm
                                                  :prompt prompt :agent agent)]
                {:store st' :delta d}
                (edit/missing-form-error base ns-sym nm)))
            (fn [base] (:node (store/form-named base ns-sym nm)))
            (symbol (str ns-sym) (str nm))
            ns-sym
            :load? false)]
     (if (or (:error r) (:conflict r))
       r
       (let [affected (session/affected-tests session ns-sym nm)]
         (repl/eval! (:image @session) (format "(ns-unmap '%s '%s)" ns-sym nm))
         (when unregister (repl/eval! (:image @session) unregister))
         (let [summary (session/run-verification! session ns-sym affected
                                                  :edited #{(symbol (str ns-sym) (str nm))})]
           (session/commit-appended! session
                                     #(store/record-verification % ns-sym summary)
                                     [])
           {:delta (:delta r) :test summary :affected (or affected :all)}))))))

(defn- apply-group-step
  "Apply one edit-group step to a store VALUE. Returns {:store :delta :hot ...}
  or {:error msg}. `:hot` is the hot-reload action for the commit phase.
  Actions: :replace, :add (optionally anchored via :before), :delete,
  :move (:name before :before — reordering inside the atomic group; image
  vars are order-independent, so no hot action), :subform (:match + :source,
  `:text true` for raw-text matches — a small change INSIDE a big form
  without re-transcribing it), and :require (one require clause into the ns
  form). Subform/require compute the new source and reduce to :replace, so
  every gate (dialect, Q7 isolation, Q9 teaching errors) rides along.
  :replace and :delete carry the same destructive-write guards as the
  single-form paths — ambiguity, rename-collision, ns-form protection —
  since undo!/revert-episode!/sweeps all ride through here."
  [st gid prompt agent {:keys [action ns name source before match text] :as step}]
  (case action
    :replace (let [{:keys [node error]} (edit/parse-form source)
                   iso (when node
                         (edit/isolation-refusal (edit/require-aliases st ns) node))
                   nm' (some-> node store/form-symbol)
                   ambiguous (edit/ambiguous-form-error st ns name)
                   collision (when (and nm' (not= nm' name))
                               (let [hit (store/form-named st ns nm')
                                     old (store/form-named st ns name)]
                                 (when (and hit (not= (:id hit) (:id old)))
                                   {:error (str nm' " already exists in " ns
                                                " — a replace may not RENAME "
                                                name " onto an existing form"
                                                " (two definitions would answer"
                                                " to one name). Delete or rename"
                                                " one of them first.")})))]
               (cond
                 ambiguous ambiguous
                 error {:error error}
                 iso   {:error iso}
                 collision collision
                 :else
                 (if-let [[st' d] (store/replace-node st ns name node
                                                      :prompt prompt :group gid
                                                      :agent agent)]
                   (if-let [merr (edit.modules/gate-refusal st' ns (or nm' name))]
                     {:error merr}
                     {:store st' :delta d
                      :hot (if (and nm' (not= nm' name))
                             [:load-unmap (:form-id d) ns name]
                             [:load (:form-id d)])})
                   (edit/missing-form-error st ns name))))
    :add     (let [{:keys [node error]} (edit/parse-form source)
                   nm (some-> node store/form-symbol)
                   iso (when node
                         (edit/isolation-refusal (edit/require-aliases st ns) node))]
               (cond
                 error {:error error}
                 iso   {:error iso}
                 (and nm (store/form-named st ns nm))
                 {:error (str nm " already exists in " ns)}
                 (and before (not (store/form-named st ns before)))
                 {:error (str "no anchor form named " before " in " ns)}
                 :else
                 (if-let [[st' d] (store/append-form st ns node
                                                     :prompt prompt :group gid
                                                     :agent agent :before before)]
                   (if-let [merr (when nm (edit.modules/gate-refusal st' ns nm))]
                     {:error merr}
                     {:store st' :delta d :hot [:load (:form-id d)]})
                   {:error (str "no namespace " ns " (ingest it first)")})))
    :subform (let [plan (cond
                          (seq (:where step))
                          (refactor/keyed-replace-plan st ns name (:where step) source)
                          text (refactor/text-replace-plan st ns name match source)
                          :else (refactor/subform-replace-plan st ns name match source))]
               (if (:error plan)
                 plan
                 (apply-group-step st gid prompt agent
                                   {:action :replace :ns ns :name name
                                    :source (:new-form-src plan)})))
    :require (if-let [f (store/form-named st ns ns)]
               (let [r (edit/add-require-source (n/string (:node f)) (:require step))]
                 (if (:error r)
                   r
                   (apply-group-step st gid prompt agent
                                     {:action :replace :ns ns :name ns
                                      :source (:src r)})))
               {:error (str "no namespace " ns " (ingest it first)")})
    :delete  (or (edit/ns-form-delete-error ns name)
                 (edit/ambiguous-form-error st ns name)
                 (if-let [[st' d] (store/remove-form st ns name
                                                     :prompt prompt :group gid
                                                     :agent agent)]
                   {:store st' :delta d :hot [:unmap ns name]}
                   (edit/missing-form-error st ns name)))
    :move    (if-let [[st' d] (store/move-form st ns name before
                                               :prompt prompt :agent agent)]
               {:store st' :delta d :hot nil}
               {:error (str "cannot move " name " before " before " in " ns
                            " — both must be existing forms")})
    {:error (str "unknown action: " action)}))

(defn edit-group!
  "Apply several form writes as ONE atomic intent (F2). All steps are validated
  and applied to a store value first — any error rejects the WHOLE group with
  nothing committed (store, deltas, image untouched). On success: all deltas
  (sharing a `:group` id) commit and persist, every change hot-reloads, and
  verification runs ONCE at the end — no meaningless mid-refactor red, no
  wasted diagnostic restart. Steps: [{:action :replace|:add|:delete
  :ns sym :name sym :source str} ...].

  DELIBERATELY NOT AN MCP TOOL, and it must stay that way. This is an
  IMPLEMENTATION PRIMITIVE for transformations a TOOL derives from a single
  stated intent — `change-signature!`, `rename-sweep!`, `revert-episode!`,
  `undo!`, `sync/apply-ns!`. Their intermediate states are invalid by
  construction and no one was ever asked to reason about them.

  Exposed to agents it becomes a shopping list, and that was measured to go
  badly: agents batched a whole feature into one call instead of working
  incrementally, which is too much to hold at once and skips the property that
  makes this system work — every step is a VALID PROGRAM, verified, with the
  automated checks at `done` and the judgement call about completeness made
  there too.

  The test for whether a multi-form op belongs on the wire: does the AGENT
  choose the steps, or does the TOOL derive them from one intent? `rename_sweep
  {from to}` is one intent. `edit_group [step step step]` is a shopping list.
  If you are reaching for this because a single-form edit would not compile,
  you almost certainly need a BIGGER MATCH on that one form, not atomicity
  across several."
  [session steps & {:keys [prompt agent]}]
  (if (empty? steps)
    {:error "edit-group needs at least one step"}
    (let [t0 (System/nanoTime)
          base0 (:store @session)
          pre-warned (into #{}
                           (mapcat (fn [ns-sym]
                                     (map :var (edit/ns-warnings (:store @session) ns-sym))))
                           (distinct (map :ns steps)))
          [gid st0] (store/alloc-id base0 "g")]
      (loop [st st0, remaining steps, deltas [], hots [], i 0]
        (if-let [step (first remaining)]
          (let [r (apply-group-step st gid prompt agent step)]
            (if (:error r)
              (cond-> {:error (str "step " i ": " (:error r)) :step i}
                (:source-now r) (assoc :source-now (:source-now r)))
              (recur (:store r) (rest remaining)
                     (conj deltas (:delta r)) (conj hots (:hot r)) (inc i))))
          ;; commit phase — checked loads FIRST (S1), commit only if all compile
          (let [st       (reduce (fn [s ns-sym]
                                   (if-let [rz (edit/resolve-cold-load
                                                s ns-sym
                                                :prompt "auto-reorder: define before use"
                                                :agent agent)]
                                     (:store rz) s))
                                 st (distinct (map :ns steps)))
                lr       (lintgate/lint-refusals base0 st (distinct (map :ns steps))
                                             (keep :form-id deltas))
                load-res (if-let [gate (or (edit/cold-load-errors st (distinct (map :ns steps)))
                                           (:refuse lr))]
                           {:err gate}
                           (merge (session/hot-load-all! session st
                                                 (keep (fn [[k a]] (when (#{:load :load-unmap} k) a))
                                                       hots))
                                  (select-keys lr [:carried])))]
            (cond
              (:err load-res)
              (edit/compile-error st (:err load-res) "group failed to compile: ")

              (not (session/try-commit! session base0 st
                                (vec (distinct (map :ns steps)))))
              (do ;; the group's forms are already hot-loaded — never leave the loser's
      ;; code answering for the winner's store
      (session/fresh-image! session)
      {:conflict {:reason "store changed during multi-form op — retry"}})

              :else
              (let [image    (:image @session)
                    _        (doseq [[kind a b c] hots]
                               (cond
                                 (= :unmap kind)
                                 (repl/eval! image (format "(ns-unmap '%s '%s)" a b))
                                 (= :load-unmap kind)
                                 (repl/eval! image (format "(ns-unmap '%s '%s)" b c))))
                    ;; per-step names double as the D5.1 edited set
                    step-nms (map (fn [{:keys [action ns name source]}]
                                    (let [nm (case action
                                               :add (some-> (edit/parse-form source)
                                                            :node store/form-symbol)
                                               name)]
                                      (when nm [action ns nm])))
                                  steps)
                    edited   (into #{}
                                   (keep (fn [x]
                                           (when-let [[_ ns nm] x]
                                             (symbol (str ns) (str nm)))))
                                   step-nms)
                    ;; affected = union across steps; unknown → conservative full
                    per-step (map (fn [x]
                                    (if-let [[action ns nm] x]
                                      (let [a (session/affected-tests session ns nm)]
                                        (cond
                                          (some? a)       (set a)
                                          (= action :add) #{}
                                          :else           :unknown))
                                      :unknown))
                                  step-nms)
                    affected (when (not-any? #{:unknown} per-step)
                               (vec (sort (apply set/union per-step))))
                    ;; F-3c5: with no/partial trace info the fallback run must
                    ;; cover EVERY touched namespace, not just the first step's
                    ;; the fallback scope is a GRAPH question: tests that REACH the
                    ;; touched namespaces. Running tests IN the production
                    ;; namespaces found none, so a group write with incomplete
                    ;; trace evidence verified nothing at all.
                    touched  (vec (distinct (map :ns steps)))
                    main-ns  (or (seq (session/covering-test-nses
                                       (:store @session) touched))
                                 touched)
                    summary  (session/run-verification! session main-ns
                                                (when (seq affected) affected)
                                                :edited edited)]
                (session/commit-appended! session
                                  #(store/record-verification % main-ns summary)
                                  [])
                (let [all-w    (->> (map :ns steps) distinct
                                    (mapcat #(edit/ns-warnings (:store @session) %)))
                      existing (count (filter (comp pre-warned :var) all-w))]
                  (session/with-ms
                    (cond-> {:group    gid
                             :deltas   deltas
                             :changed-nses (vec (distinct (map :ns steps)))
                             :warnings (vec (remove (comp pre-warned :var) all-w))
                             :test     summary
                             :affected (or (not-empty affected) :all)
                             ;; drift for the WHOLE group, read off the deltas —
                             ;; every step kind (including :subform, which
                             ;; computes its own source) records its final
                             ;; source there, so one place covers them all.
                             ;; Detecting it per-step would need a loop arity
                             ;; change; the deltas already carry the answer.
                             :drift
                             (vec (for [d     deltas
                                        :when (= :replace (:op d))
                                        :let  [fid (:form-id d)
                                               e   (store/form-by-id base0 fid)
                                               nu  (some-> (get (:sources d) fid)
                                                           edit/parse-form :node)]
                                        :when (and (:node e) nu)
                                        x     (edit/contract-drift (:node e) nu)]
                                    (assoc x :form (symbol (str (:ns d))
                                                           (str (or (:name e) fid))))))}
                      (:healed load-res) (assoc :image-healed true)
                      (:stubbed load-res) (assoc :red-first (:stubbed load-res)
                                                 :note (str "these vars don't exist yet —"
                                                            " stubbed in-image as failing"
                                                            " (red-first); implement them to"
                                                            " go green."))
                      (:carried load-res) (assoc :carried-errors (:carried load-res))
                      (pos? existing)    (assoc :existing-warnings existing))
                    t0))))))))))

(defn add-require!
  "F5: add one require clause to `ns-sym`'s ns form — structural edit through
  the normal replace pipeline (delta, hot-reload, verification).

  Forwards `:agent` (#132): without it the delta landed agent-nil and the edit
  never entered ANY agent's episode — `done` never linted, normalized, or
  verified an ns_add_require at the boundary. Found by the collapse fix's own
  e2e: the ns-form change it staged simply never arrived.

  Attaches `:tier-note` when a TIERED namespace gains an in-store dep no
  declaration covers: undeclared defaults :external, so the consumer's tier
  claim dies at the next full_check's layering pass — and the write that
  creates the dependency is the one moment the declaration is cheap and the
  author's context is loaded (frictions #4: the signal used to arrive two
  gates late)."
  [session ns-sym require-str & {:keys [prompt agent]}]
  (if-let [f (store/form-named (:store @session) ns-sym ns-sym)]
    (let [r (edit/add-require-source (n/string (:node f)) require-str)]
      (if (:error r)
        r
        (let [res (edit-replace! session ns-sym ns-sym (:src r)
                                 :prompt (or prompt (str "add require " require-str))
                                 :agent agent)
              st  (:store @session)
              lib (try (let [spec (edn/read-string (str require-str))]
                         (cond (vector? spec) (first spec)
                               (symbol? spec) spec))
                       (catch Exception _ nil))
              note (when (and (nil? (:error res)) lib
                              (contains? (:namespaces st) lib)
                              (edit.modules/tier-declared? st ns-sym)
                              (contains? #{:pure :internal}
                                         (edit.modules/tier-for st ns-sym))
                              (not (edit.modules/tier-declared? st lib)))
                     (str ns-sym " is declared " (edit.modules/tier-for st ns-sym)
                          " and now depends on UNDECLARED " lib " (defaults"
                          " :external — full_check's tier-layering will flag"
                          " this). Declare it while the context is loaded:"
                          " module_purity {module \"" lib "\" tier \"...\"} —"
                          " a new ns's tier is cheapest at creation."))]
          (cond-> res
            note (assoc :tier-note note)))))
    {:error (str "no namespace " ns-sym " (create it first)")}))

(defn remove-require!
  "Symmetric counterpart of add-require!: structurally remove `lib`'s require
  spec from `ns-sym`'s ns form, through the normal replace pipeline.
  Forwards `:agent` (#132) for the same reason add-require! does — an
  agent-nil delta never enters any episode."
  [session ns-sym lib & {:keys [prompt agent]}]
  (if-let [f (store/form-named (:store @session) ns-sym ns-sym)]
    (let [r (edit/remove-require-source (n/string (:node f)) lib)]
      (if (:error r)
        r
        (edit-replace! session ns-sym ns-sym (:src r)
                       :prompt (or prompt (str "remove require " lib))
                       :agent agent)))
    {:error (str "no namespace " ns-sym)}))

(defn move-form!
  "S2: reorder — move form `nm` to just before `:before` in its namespace (the
  fix for append-only forward references). Image vars are order-independent so
  nothing re-evals; the next fresh load / restart uses the new order — which
  is exactly why the move itself must pass the cold-load check (S1b): a move
  can CREATE the forward reference a fresh load dies on."
  [session ns-sym nm & {:keys [before prompt agent]}]
  (cond
    (nil? (store/form-named (:store @session) ns-sym nm))
    (edit/missing-form-error (:store @session) ns-sym nm)

    (nil? (store/form-named (:store @session) ns-sym before))
    (edit/missing-form-error (:store @session) ns-sym before)

    :else
    (let [base0 (:store @session)]
      (if-let [[st' delta] (store/move-form base0 ns-sym nm before
                                            :prompt prompt :agent agent)]
        (if-let [cold (edit/cold-load-errors st' [ns-sym])]
          {:error cold}
          (if-not (session/try-commit! session base0 st' [ns-sym])
            {:conflict {:reason "store changed concurrently — retry"}}
            {:delta delta :moved {:form nm :before before}}))
        {:error (str "cannot move " nm)}))))

(defn forms-changed-since
  "Ids of forms touched by deltas after `since-id` (nil = since the beginning)
  that still exist in the store."
  [store since-id]
  (let [ds   (store/deltas store)
        tail (if since-id
               (rest (drop-while #(not= since-id (:id %)) ds))
               ds)]
    (->> tail
         (mapcat (fn [d] (if (:form-id d) [(:form-id d)] (:form-ids d))))
         distinct
         (filter #(store/ns-of-form-id store %)))))

(defn test-run!
  "Traced, diagnosed run of `ns-sym`'s tests (all, or just those in `:only`
  — plain names within `ns-sym`, or ns-qualified names which auto-scope);
  refreshes the test→form map and records the result (C4). `ns-sym` nil =
  the WHOLE project in one image eval, instrumentation paid once (F-3c1 —
  per-ns sweeps were 12 calls and 12 instrumentation passes). D5.1: reds
  are judged against the forms changed since the last verification;
  `:fresh true` restarts first for a guaranteed-faithful single run."
  [session ns-sym & {:keys [only fresh]}]
  (let [t0          (System/nanoTime)
        st          (:store @session)
        only        (seq only)
        qual        (filter #(str/includes? (str %) "/") only)
        ns-sym      (or ns-sym
                        (when (seq qual)
                          (vec (sort (distinct (map #(symbol (namespace (symbol (str %))))
                                                    qual)))))
                        (vec (sort (keys (:namespaces st)))))
        only'       (seq (map #(let [s (str %)]
                                 (if (str/includes? s "/")
                                   (symbol (name (symbol s)))
                                   %))
                              only))
        last-verify (:id (last (filter #(= :verify (:op %)) (store/deltas st))))
        edited      (into #{}
                          (keep (fn [id]
                                  (when-let [e (store/form-by-id st id)]
                                    (symbol (str (store/ns-of-form-id st id))
                                            (str (or (:name e) (:id e)))))))
                          (forms-changed-since st last-verify))
        summary     (session/diagnosed-run! session ns-sym only'
                                    :edited edited :fresh fresh
                                    :include-integration? true)]  ; M5: explicit run
    (session/commit-appended! session
                      #(store/record-verification % ns-sym summary) [])
    (session/with-ms (cond-> summary
               (and only' (zero? (:test summary 0)))
               (assoc :note (str "0 tests matched :only " (vec only)
                                 " — check the names (a named ^:external test"
                                 " routes to the external tier automatically)")))
             t0)))

(defn- require-orphaned-registrar?
  "After a require to `lib` has been dropped, would a COLD LOAD lose a
   registration? True when `lib` is an in-store namespace whose require-closure
   registers something (defmethod / extend-* / deftype / defrecord …) AND
   nothing else in the store still requires it — so dropping this require
   orphans it and its registrations never run again. The in-image suite cannot
   see this break: the registration is already loaded in the live image, so a
   green in-image verdict is not enough to prove the require dead."
  [st lib]
  (boolean
   (and (contains? (:namespaces st) lib)
        (not (some (fn [nsx] (contains? (set (store/ns-requires st nsx)) lib))
                   (keys (:namespaces st))))
        (some store/method-carrying?
              (mapcat #(store/forms st %) (store/ns-closure st lib))))))

(defn prune-requires!
  "Done-point require hygiene — the agent never manages unused requires; done
   does, and there is deliberately no MCP tool for it. For each require kondo
   reports unused (`done/unused-requires`), TRY removing it and re-verify.

   Removing a kondo-unused require cannot break COMPILATION — nothing used it —
   so the only ways it can break are (1) a test the removal's own affected set
   catches, or (2) a load effect a cold load would lose: an orphaned in-store
   target whose closure REGISTERS something (a defmethod the reference graph
   can't see). The live image already has that registration loaded, so a green
   in-image verdict does NOT prove the require dead — `require-orphaned-registrar?`
   is the static backstop.

   Genuinely dead → drop it. Load-bearing (or the removal went red) → restore it
   WITH a `^:side-effect` marker, so it no longer reads as unused and done never
   re-tries it. Returns `{:pruned [lib …] :kept [lib …]}`."
  [session ns-sym & {:keys [prompt agent]}]
  (reduce
   (fn [acc {:keys [lib marked]}]
     (let [r    (remove-require! session ns-sym lib
                                 :prompt (or prompt (str "done: try pruning unused require " lib))
                                 :agent agent)
           red? (let [t (:test r)]
                  (boolean (and t (or (pos? (:fail t 0)) (pos? (:error t 0))))))]
       (cond
         ;; couldn't remove it at all (conflict/refusal) — leave it untouched
         (or (:error r) (:conflict r))
         (update acc :kept conj lib)

         ;; removing it broke a test, or would lose a registration on cold load:
         ;; restore it, marked, so it is not reported unused or re-tried
         (or red? (require-orphaned-registrar? (:store @session) lib))
         (do (add-require! session ns-sym marked
                           :prompt (str "done: keep load-bearing require " lib
                                        " (removing it breaks a cold load) — marked ^:side-effect")
                           :agent agent)
             (update acc :kept conj lib))

         :else
         (update acc :pruned conj lib))))
   {:pruned [] :kept []}
   (done/unused-requires (:store @session) ns-sym)))

(defn fix-declares!
  "Declare hygiene at the done-point. The write pipeline OWNS form ordering, so
  this no longer reorders anything itself (it used to carry a second,
  conservative single-form mover that gave up on cases the topological sort
  handles). It DROPS `ns-sym`'s declares and lets `edit/resolve-cold-load`
  re-establish what is genuinely needed — a topological reorder (Kahn over THE
  reference graph), or the pipeline's own MARKED auto-declare for a real cycle.
  Net effect: a satisfied declare vanishes, PHANTOM names (a var an earlier
  move lifted out — they mint unbound vars) vanish with it, a legacy
  hand-written declare MIGRATES to a pipeline-owned marked one that says why,
  and a stale auto-declare disappears once its cycle breaks. No-ops when the
  rendered namespace would be unchanged. One atomic group, verified."
  [session ns-sym & {:keys [prompt agent]}]
  (let [st    (:store @session)
        decls (filter (fn [f]
                        (and (nil? (:name f))
                             (= 'declare (try (first (n/sexpr (:node f)))
                                              (catch Exception _ nil)))))
                      (store/forms st ns-sym))]
    (if (empty? decls)
      {:removed 0 :note "no declares"}
      (let [[gid st0] (store/alloc-id st "g")
            stripped  (reduce (fn [s d]
                                (or (first (store/remove-form s ns-sym (:id d)
                                                              :prompt (or prompt "fix-declares")
                                                              :group gid :agent agent))
                                    s))
                              st0 decls)
            rz  (edit/resolve-cold-load stripped ns-sym
                                        :prompt (or prompt "fix-declares: pipeline owns ordering")
                                        :agent agent)
            st' (or (:store rz) stripped)]
        (cond
          ;; the pipeline could not make it load without the declares — leave
          ;; the namespace exactly as it was
          (edit/cold-load-errors st' [ns-sym])
          {:removed 0 :note "declares still required — left as-is"}

          ;; nothing would actually change: don't churn the journal
          (= (render/render-ns st ns-sym) (render/render-ns st' ns-sym))
          {:removed 0 :note "already tidy"}

          :else
          (if-not (session/try-commit! session st st' [ns-sym])
            {:conflict {:reason "store changed during fix-declares — retry"}}
            (let [summary (session/run-verification! session ns-sym nil)]
              (session/commit-appended! session
                                        #(store/record-verification % ns-sym summary) [])
              {:removed (count decls) :test summary})))))))

(defn cleanup!
  "Run the done-point's TIDY over one namespace, on demand: normalize every
  form (conservative, behavior-preserving rewrites), then declare hygiene via
  `fix-declares!` — definitions reordered above their callers, a legacy or
  stale `(declare …)` retired, phantom names pruned. One verified pass.

  You should rarely need this. The write pipeline owns ordering and declares
  from the FIRST write, and `done` runs the same tidy over everything you
  touched — so code written through slopp arrives clean. Reach for it on code
  that predates those invariants (an ingested file-based namespace), or when a
  legacy declare is blocking you mid-episode: two elements then share a name,
  which the name-addressed edit tools cannot resolve.

  Returns `{:ns :normalized n :rewrites [{:form :applied}] :declares n}`, or
  `{:error …}` — nothing is committed unless the tidied namespace compiles."
  [session ns-sym & {:keys [prompt agent]}]
  (let [st       (:store @session)
        rewrites (vec (for [f (store/forms st ns-sym)
                            :let [{:keys [node applied]}
                                  (normalize/normalize-form (:node f))]
                            :when (seq applied)]
                        {:form-id (:id f)
                         :form    (symbol (str ns-sym) (str (or (:name f) (:id f))))
                         :node    node
                         :applied applied}))
        normed   (when (seq rewrites)
                   (let [changeset (into {} (map (juxt :form-id :node)) rewrites)
                         [st' _]   (store/apply-changeset
                                    st :normalize ns-sym changeset
                                    :prompt (or prompt "cleanup: normalize")
                                    :agent agent)]
                     (if-let [err (:err (session/hot-load-all! session st'
                                                               (keys changeset)))]
                       {:error (str "cleanup: normalization would not compile — "
                                    err)}
                       (if-not (session/try-commit! session st st' [ns-sym])
                         {:conflict {:reason "store changed during cleanup — retry"}}
                         {:ok true}))))]
    (cond
      (:error normed)    normed
      (:conflict normed) normed
      :else
      (let [d (fix-declares! session ns-sym
                             :prompt (or prompt "cleanup: declare hygiene")
                             :agent agent)]
        (cond-> {:ns         ns-sym
                 :normalized (count rewrites)
                 :rewrites   (mapv #(select-keys % [:form :applied]) rewrites)
                 :declares   (:removed d 0)
                 :purity     (edit.modules/tier-report (:store @session) ns-sym)
                 ;; the done-time advisories, re-run over the WHOLE namespace.
                 ;; They already fired for anything written through slopp since
                 ;; the rule existed — what they have never seen is code that
                 ;; PREDATES the rule (ingested, or written before the advisory
                 ;; was added). That is exactly this tool's job.
                 :advisories (let [st* (:store @session)]
                               (rules/run-done-advisories!
                                session st* (mapv :id (store/forms st* ns-sym))))
                 ;; the rest of the enforcement surface, replayed over EXISTING
                 ;; code: kondo lint, dead public surface, undocumented public
                 ;; surface, and the per-form WRITE gates (module / tier /
                 ;; schema / namespaced-keys). Each normally fires only as code
                 ;; is written, so a form predating a rule was never subject to
                 ;; it. Reported, never auto-applied — every one needs judgment.
                 :lint       (let [st* (:store @session)]
                               (vec (done/anchored-lint
                                     session (mapv :id (store/forms st* ns-sym)))))
                 :unused     (vec (:unused (modules/unused-report
                                            (:store @session) [ns-sym])))
                 :undocumented
                 (let [st* (:store @session)]
                   (vec (keep #(:var (edit.modules/missing-doc-warning st* ns-sym (:name %)))
                              (filter :name (store/forms st* ns-sym)))))
                 :gates
                 (let [st* (:store @session)]
                   (vec (for [f (store/forms st* ns-sym)
                              :when (:name f)
                              :let [g (edit.modules/gate-check st* ns-sym (:name f))
                                    hits (remove nil? (cons (:refuse g) (:advisories g)))]
                              :when (seq hits)]
                          {:form (symbol (str ns-sym) (str (:name f)))
                           :teach (vec hits)})))}
          (:conflict d) (assoc :conflict (:conflict d))
          (:test d)     (assoc :test (:test d)))))))

(defn cleanup-all!
  "Run `cleanup!` over EVERY namespace in the store — the MIGRATION surface.

  Per-namespace is the wrong grain for a migration, because you do not know
  which namespaces predate a rule. Two cases need this: adopting slopp on an
  existing codebase (nothing in it was ever subject to any gate), and landing
  a slopp upgrade that ADDS a rule (every existing form predates it).

  Applies the tidy everywhere it is needed, and aggregates what tidying cannot
  fix. Returns `{:namespaces n :normalized n :declares n :findings [{:ns …}]}`
  — `:findings` carries only namespaces with something to report, each with
  whichever of `:lint :unused :undocumented :gates :advisories` fired, so a
  clean store returns an empty vector rather than 100 empty rows.

  Reports; it never auto-fixes a finding. Dead surface, a missing docstring, a
  gate violation and an ambient atom each need a human decision — and the last
  is often correct as written."
  [session & {:keys [prompt agent]}]
  (let [nses (sort (keys (:namespaces (:store @session))))
        rs   (mapv #(cleanup! session %
                              :prompt (or prompt "cleanup-all: migration sweep")
                              :agent agent)
                   nses)]
    (if-let [bad (first (filter :error rs))]
      bad
      {:namespaces (count rs)
       :normalized (reduce + 0 (map #(:normalized % 0) rs))
       :declares   (reduce + 0 (map #(:declares % 0) rs))
       :findings
       (vec (keep (fn [r]
                    (let [hit (cond-> {}
                                (seq (:lint r))         (assoc :lint (:lint r))
                                (seq (:unused r))       (assoc :unused (:unused r))
                                (seq (:undocumented r)) (assoc :undocumented (:undocumented r))
                                (seq (:gates r))        (assoc :gates (:gates r))
                                (seq (:advisories r))   (assoc :advisories (:advisories r)))]
                      (when (seq hit) (assoc hit :ns (:ns r)))))
                  rs))})))

^:reads (defn query-commits
  "Milestones, newest first:
  [{:commit :description :target :status :agent :at :sha}]. The list rung
  carries each description's TITLE LINE only (+ :more-lines when a body
  follows) — needing one sha used to fetch five whole milestone essays;
  `:commit \"dN\"` returns that ONE milestone with its full description.
  Commit `:target` ids plug straight into query-changes :from/:to for
  between-milestone diffs. `:sha` (P4-m8) is the milestone's git commit id —
  present once the git projection has minted it (imported markers carry
  theirs from birth).

  This is `history/milestone-rows` — a pure fold over the delta log — plus
  the one thing that needs the db: joining the shas the git projection
  pinned. The split is why the reviewer UI can read milestones at :pure."
  [session & {:keys [commit]}]
  (let [{:keys [dir]} @session
        st   (:store @session)
        shas (when dir
               (try (with-open [conn (db/open! dir)]
                      (db/commit-shas conn))
                    (catch Exception _ nil)))
        join (fn [row]
               (if-let [s (or (:sha row) (get shas (:commit row)))]
                 (assoc row :sha s)
                 row))]
    (if commit
      (some #(when (= (str commit) (:commit %)) (join %))
            (history/milestone-rows st))
      (mapv join (history/milestone-rows st :titles-only true)))))

(defn deps-remove!
  "Drop external dependency `lib` from the manifest. A jar can't be unloaded,
  so this always restarts the image. Returns {:removed lib :restarted true}
  or {:error}."
  [session lib & {:keys [agent prompt]}]
  (if-not (contains? (:deps (:store @session)) lib)
    {:error (str lib " is not a declared dependency")}
    (do
      (session/commit-appended! session
                        #(first (store/record-deps-remove % lib
                                                          :agent agent :prompt prompt))
                        [])
      (session/fresh-image! session)
      {:removed lib :restarted true})))

(defn deps-list
  "The store's external dependency manifest: {lib coord}."
  [session]
  (:deps (:store @session)))

(defn deps-manifest
  "The dependency manifest as an AGENT should read it: `{:deps {lib coord}}`,
  plus `:host-override` naming any declaration slopp's own server process
  cannot honor because it bundles that library itself.

  Separate from `deps-list` on purpose. `deps-list` is the accessor — the
  store's data, which other code and tests build on, and the host's classpath
  is not part of it. This is the SURFACE, and the surface carries an
  obligation the accessor does not: `deps_list` is the one answer an inherited
  store ever gives about its dependencies, so a declaration that is inert in
  the running server has to be visible here or nowhere."
  [session]
  (let [deps (deps-list session)
        over (boot/host-lib-divergence deps (boot/bundled-libs))]
    (cond-> {:deps deps}
      (seq over)
      (assoc :host-override over
             :host-override-note
             (str "slopp's own server process bundles these at the :in-force"
                  " version and cannot displace them. Your declarations still"
                  " govern the oracle image, the test suite and anything"
                  " build! produces")))))

(defn- record-pure!
  "Mark each of `syms` pure/un-pure in ONE appended commit (N :deps-pure deltas)."
  [session syms pure? {:keys [agent prompt]}]
  (session/commit-appended! session
                    (fn [base]
                      (reduce (fn [s x]
                                (first (store/record-deps-pure s x pure?
                                                               :agent agent :prompt prompt)))
                              base syms))
                    []))

(defn- adopt-published-tiers!
  "Adopt the purity tiers the libraries on the image's classpath PUBLISHED,
  returning the namespaces newly marked pure (a vector, possibly empty).

  A tier is a declaration in the producer's store and nothing in the code, so
  before this a consumer saw every namespace of a published library as
  undeclared — hence `:external` — and its own correct functions were flagged
  effectful for calling them. Worse than the warning was its SUGGESTION:
  rename `picker` to `picker!`, which would have mislabelled four correct pure
  functions to compensate for a declaration that never shipped.

  Only `:pure` is adopted. `:external` is already the default, and `:internal`
  is a statement about in-process state that means nothing across a jar
  boundary — the consumer cannot reset a dependency's caches.

  Adopting the producer's word is better founded than the alternative the
  consumer has otherwise: `deps_pure` asks them to assert purity about code
  they did not write and cannot check, while the producer's tier was VERIFIED
  against the forms when it was declared. Reported as `:adopted-pure` either
  way — a silent change to what the effect gate flags is the kind of thing
  someone should be able to see happen.

  Read through the IMAGE, which is where the new jar actually landed: the
  server never added it to its own classpath."
  [session {:keys [agent]}]
  (let [code  (str "(mapv slurp (enumeration-seq (.getResources"
                   " (clojure.lang.RT/baseLoader) \""
                   modules/tiers-resource-path "\")))")
        res   (try (first (repl/eval! (:image @session) code))
                   (catch Throwable _ nil))
        tiers (reduce (fn [acc s]
                        (if (string? s)
                          (merge acc (try (edn/read-string s)
                                          (catch Throwable _ nil)))
                          acc))
                      {}
                      (when (coll? res) res))
        known (:dep-pure (:store @session))
        fresh (vec (sort (distinct (for [[path tier] tiers
                                         :when (= :pure tier)
                                         :let  [nsx (symbol (str path))]
                                         :when (not (contains? known nsx))]
                                     nsx))))]
    (when (seq fresh)
      (record-pure! session fresh true
                    {:agent  agent
                     :prompt (str "adopted from a dependency's published purity"
                                  " tiers (" modules/tiers-resource-path ")")}))
    fresh))

(defn- shadowed-dep-namespaces!
  "Of `nses`, those provided by MORE than one place on the image's classpath —
  `{ns [url …]}`, or nil. The declared coord did not win those.

  `add-libs` appends to a `DynamicClassLoader`, which delegates to its PARENT
  first, and everything the host jar carries lives in that parent. So a
  dependency can resolve perfectly and still not govern: measured on slopp's
  own store, the manifest declares metosin/malli 0.16.4 while
  `malli/core.cljc` resolves out of slopp.jar both before AND after a
  successful add of exactly that coord.

  Fixing that is a packaging change — shading, a slim launcher, or a
  child-first loader — and none of those belong in `deps_add`. Saying it does:
  a manifest that reads as satisfied while a different version is in force is
  precisely what D-surface-honesty forbids, and it is invisible from every
  surface a consumer has. The FIRST url is the one in force."
  [session nses]
  (when (seq nses)
    (let [code (str "(into {} (for [n '" (pr-str (vec nses))
                    " :let [b (clojure.string/replace"
                    " (clojure.string/replace (str n) \".\" \"/\") \"-\" \"_\")"
                    " us (mapcat #(enumeration-seq"
                    " (.getResources (clojure.lang.RT/baseLoader) (str b %)))"
                    " [\".clj\" \".cljc\"])]"
                    " :when (> (count us) 1)] [n (mapv str us)]))")
          res  (try (first (repl/eval! (:image @session) code))
                    (catch Throwable _ nil))]
      (when (and (map? res) (seq res)) res))))

(defn deps-add!
  "Declare external dependency `lib` (a symbol like `org.clojure/data.json`)
  at `coord` (a deps.edn coordinate map, e.g. `{:mvn/version \"2.5.0\"}`).
  Records a `:deps-add` delta (materialized to the store's manifest), then
  HOT-adds the jar to the running image via add-libs — no restart; on failure
  it restarts. Returns {:added lib :coord :hot true|:restarted true} | {:error}.

  A coord carrying `:exclusions` RESTARTS rather than hot-adds. `add-libs`
  silently ignores exclusions and a fresh JVM honors them, so hot-adding leaves
  the oracle running a classpath no fresh JVM can reproduce: the in-image suite
  goes green with the excluded jar present while every external shard, `build!`
  and native fail to load. An image that can run what a fresh JVM cannot LOAD is
  the cold-load failure class, and the cheapest place to not have it is here.

  `:host-override` names a library slopp's OWN process bundles at a different
  version. It is not a warning about this store — the declaration governs the
  oracle, the test suite and every built artifact — but the server process
  cannot honor it, because a jar its parent classloader already holds cannot be
  displaced. Saying so is the whole point: the alternative is two processes
  quietly running different code with every surface reporting agreement.

  With `:client true` the dep is BUILD-ONLY (the ClojureScript compiler): it
  records to the separate `:client-deps` manifest, is NOT analyzed and NOT
  hot-loaded, and routes to the `:cljs` alias in the generated deps.edn — so it
  never enters the running oracle nor ships in the jar (D-web-cljs)."
  [session lib coord & {:keys [agent prompt client]}]
  (cond
    (not (symbol? lib))
    {:error "dependency lib must be a symbol like org.clojure/data.json"}
    (not (and (map? coord) (seq coord)))
    {:error "dependency coord must be a non-empty map like {:mvn/version \"1.2.3\"}"}

    :else
    (let [coord (fields/canonical-coord coord)]      ; JSON has no symbol type
      (if client
        (do (session/commit-appended! session
                                      #(first (store/record-client-dep
                                               % lib coord :agent agent :prompt prompt))
                                      [])
            {:added lib :coord coord :client true})
        (let [surf (api.deps/analyze-dep! session lib coord)]             ; M4: API surface
          (session/commit-appended! session
                                    #(first (store/record-deps-add
                                             % lib coord :agent agent :prompt prompt
                                             :namespaces (:namespaces surf)))  ; M3: dep-ns index
                                    [])
          (let [base (cond-> {:added lib :coord coord}
                       surf (assoc :namespaces (vec (:namespaces surf))
                                   :vars (count (:vars surf))))
                res  (if (seq (:exclusions coord))
                       (do (session/fresh-image! session)
                           (assoc base :restarted true
                                  :note (str "restarted rather than hot-added: add-libs ignores"
                                             " :exclusions but a fresh JVM honors them, so the"
                                             " image would have run a classpath no build or"
                                             " external shard could reproduce")))
                       (if-let [hot (repl/add-libs! (:image @session) {lib coord})]
                         (do (session/fresh-image! session) ; hot add failed → faithful restart
                             (assoc base :restarted true :note (:err hot)))
                         (assoc base :hot true)))
                ;; friction 2: only now is the jar on the image's classpath,
                ;; so only now can its published tiers be read
                adopted (adopt-published-tiers! session {:agent agent})
                ;; friction 15a: it RESOLVED — but did it win? Anything the host
                ;; jar already provides sits earlier on the classpath.
                shadowed (shadowed-dep-namespaces! session (:namespaces surf))
                overridden (boot/host-lib-divergence {lib coord} (boot/bundled-libs))]
            (cond-> res
              (seq adopted) (assoc :adopted-pure adopted)
              (seq overridden)
              (assoc :host-override overridden
                     :host-override-note
                     (str "slopp's own server process bundles this library at"
                          " the :in-force version and cannot displace it — a jar"
                          " the parent classloader already holds stays. Your"
                          " declaration still governs the oracle image, the test"
                          " suite and anything build! produces, so the two can"
                          " run different code; pin to the bundled version if"
                          " that matters here"))
              (seq shadowed)
              (assoc :shadowed shadowed
                     :shadowed-note
                     (str "these namespaces are provided by something EARLIER"
                          " on the classpath, so the version you declared is"
                          " NOT the one in force — the first url listed is."
                          " add-libs appends to a classloader that delegates"
                          " to its parent first, and the host jar is that"
                          " parent")))))))))

(defn deps-pure!
  "Assert a dependency is PURE — narrowing M3's effectful-by-default boundary so
  callers aren't flagged effectful. `target` lands at THREE granularities: a
  fully-qualified var (`clojure.data.json/write-str`), a whole NAMESPACE
  (`clojure.data.json`, every var in it), or a manifest LIB
  (`org.clojure/data.json`, which expands to every namespace the dep provides —
  the ergonomic default for a wholesale-pure library like rewrite-clj). Returns
  {:pure sym}, or {:lib sym :namespaces [...]} for a lib."
  [session target & {:keys [agent prompt]}]
  (let [st   (:store @session)
        lib? (contains? (:deps st) target)
        nses (when lib? (vec (get (:dep-ns st) target)))]
    (record-pure! session (or nses [target]) true {:agent agent :prompt prompt})
    (if lib? {:lib target :namespaces nses} {:pure target})))

(defn deps-unpure!
  "Undo `deps-pure!` for `target` (a var, namespace, or manifest lib — the same
  granularities as `deps-pure!`). Calls into it are effectful again."
  [session target & {:keys [agent prompt]}]
  (let [st   (:store @session)
        lib? (contains? (:deps st) target)
        nses (when lib? (vec (get (:dep-ns st) target)))]
    (record-pure! session (or nses [target]) false {:agent agent :prompt prompt})
    (if lib? {:lib target :namespaces nses} {:unpure target})))

(defn edit-subform!
  "Item 5 — paredit's invariant, agent-shaped: replace the UNIQUE structural
  occurrence of `match` inside form `form-name` with `new-src`
  (content-addressed). With `:text true` the match is RAW TEXT instead — the
  escape hatch for string literals and docstrings. With `:where {k v ...}` the
  target is the unique MAP containing those entries (registry-style edits
  by key, no exact text needed) and `match` is ignored. Rides the full
  replace pipeline: dialect gate on the RESULTING form, rebase/conflict
  commit, verification, provenance.

  With `:wrap true`, `new-src` is a TEMPLATE and `$1` is the matched form —
  `(let [n 1] $1)` nests what was there inside what you wrote. This docstring
  used to say wrap was 'just a new subform containing the old', which was true
  and not cheap: expressing it meant retyping the matched form inside the
  replacement, so a two-line change to a large form became a large paste.
  `$1` is the same template mechanism `change_signature` uses for call sites."
  [session ns-sym form-name match new-src & {:keys [prompt agent text where wrap]}]
  (let [plan (cond
               (seq where) (refactor/keyed-replace-plan (:store @session) ns-sym
                                                        form-name where new-src)
               text        (refactor/text-replace-plan (:store @session) ns-sym
                                                       form-name match new-src)
               :else       (refactor/subform-replace-plan (:store @session) ns-sym
                                                          form-name match new-src
                                                          (boolean wrap)))]
    (if (:error plan)
      plan
      (edit-replace! session ns-sym form-name (:new-form-src plan)
                     :prompt (or prompt (str "subform edit in " form-name))
                     :agent agent))))

(defn revert-form!
  "One-call rollback (item 4): replace `nm` with an earlier version of itself —
  by default the previous one, or the version at delta `:to` (see
  query-form-history). Rides the standard replace pipeline, so the revert is
  itself compile-gated, verified, and recorded provenance."
  [session ns-sym nm & {:keys [to prompt agent]}]
  (let [hist (query/query-form-history session ns-sym nm)]
    (cond
      (nil? hist)
      (edit/missing-form-error (:store @session) ns-sym nm)

      (< (count hist) 2)
      {:error (str nm " has no earlier version to revert to")}

      :else
      (let [target (if to
                     (first (filter #(= to (:delta %)) hist))
                     (nth hist (- (count hist) 2)))]
        (if-not target
          {:error (str "no version of " nm " at delta " to)}
          (edit-replace! session ns-sym nm (:source target)
                         :prompt (or prompt
                                     (str "revert to " (:delta target)))
                         :agent agent))))))

(defn undo!
  "Walk back your own recent writes — the reach-for-it-without-thinking undo.
  Addressed by DELTA, not by name: `:deltas n` (default 1) undoes your last `n`
  content writes, `:to \"d123\"` undoes everything of yours after that delta.
  `:to` also takes a NAMED anchor — `:last-commit` (scrap everything since the
  last milestone — the usual dead-end rollback) or `:last-done` (back to your
  last done point) — as a keyword or the same string over the wire. One atomic
  verified group, recorded as honest provenance rather than erased.

  Delta addressing is the point. `revert-form!` looks a form up by name, so it
  can never undo a DELETE — there is no name left to find. The log still holds
  the source, so undo puts it back. Forms another agent also wrote in the span
  are SKIPPED and reported in `:skipped-shared`, never stomped, which is what
  makes this safe to reach for while others are working.

  Returns `{:reverted n :undid [delta-ids] :skipped-shared [...]}`."
  [session & {:keys [deltas to agent prompt]}]
  (let [st       (:store @session)
        ;; undo means "walk back MY writes"; with no explicit agent that is the
        ;; session's own id — what every live write is tagged with. Left nil,
        ;; `mine?` counted every delta as mine while `others` counted every
        ;; real-agent delta as someone else's, so a session's own forms were
        ;; skipped as :skipped-shared, leaving the store red. One agent, one
        ;; ownership test.
        agent    (or agent (:agent-id @session))
        all      (store/deltas st)
        ;; :last-commit / :last-done name the two anchors worth rolling back to
        ;; without knowing a delta id — accepted as keyword or wire string.
        commit-anchor? (contains? #{:last-commit "last-commit" ":last-commit"} to)
        done-anchor?   (contains? #{:last-done "last-done" ":last-done"} to)
        to       (cond
                   commit-anchor?
                   (:id (last (filter #(= :commit (:op %)) all)))
                   done-anchor?
                   (:id (last (filter #(= :done (:op %)) all)))
                   :else to)
        mine?    (fn [d] (and (contains? query/content-ops (:op d))
                              (= agent (:agent d))))
        target   (if to
                   (first (filter mine? (rest (drop-while #(not= to (:id %)) all))))
                   (first (take-last (max 1 (or deltas 1)) (filter mine? all))))]
    (cond
      (and (or commit-anchor? done-anchor?) (nil? to))
      {:reverted 0
       :note (str "no " (if commit-anchor? "commit" "done")
                  " to roll back to")}

      (not target)
      {:reverted 0
       :note (if to
               (str "nothing of yours after " to)
               "no writes of yours to undo")}

      :else
      (let [from    (:id target)
            changes (query/query-changes session :agent agent :from from)
            span    (drop-while #(not= from (:id %)) all)
            others  (into #{}
                          (mapcat history/delta-fids)
                          (filter #(and (contains? query/content-ops (:op %))
                                        (not (mine? %)))
                                  span))
            {:keys [steps shared]} (history/revert-steps changes others)]
        (cond
          (empty? (:forms changes))
          {:reverted 0 :note (str "nothing of yours changed since " from)}

          (empty? steps)
          {:reverted 0 :skipped-shared shared
           :note "every changed form is shared with other agents"}

          :else
          (let [r (edit-group! session steps
                               :prompt (or prompt (str "undo back to " from))
                               :agent agent)]
            (if (or (:error r) (:conflict r))
              r
              (let [undid-ids (mapv :id (filter mine? span))
                    reverted  (vec (remove (set shared) (map :form (:forms changes))))]
                ;; mark the dead end so it stays findable: what was scrapped
                ;; and (if given) why. A vanished exploration teaches nothing.
                (session/commit-appended!
                 session
                 (fn [base] (first (store/record-revert base :why prompt
                                                        :forms reverted
                                                        :undid undid-ids
                                                        :agent agent)))
                 [])
                (assoc r
                       :reverted (count steps)
                       :undid undid-ids
                       :skipped-shared shared)))))))))

(defn revert-episode!
  "Scrap the agent's episode: roll every form it changed since its last
  done back to the boundary state — as ONE atomic verified group
  (honest provenance, not history erasure). Forms that OTHER agents also
  touched since the boundary are SKIPPED and reported in :skipped-shared,
  never stomped.

  This is the whole-episode grain. To walk back one write, or a short chain
  that went off the rails, without losing the rest of the episode, use
  `undo!` — same inverse, addressed by delta."
  [session & {:keys [agent prompt]}]
  (let [;; no explicit agent means \"MY episode\" — the session's own id, what
        ;; every live write carries. Left nil, `others` counted every
        ;; real-agent delta as someone else's and skipped the session's own
        ;; forms.
        agent   (or agent (:agent-id @session))
        changes (query/query-changes session :agent agent)
        others  (into #{}
                      (mapcat history/delta-fids)
                      (filter #(and (contains? query/content-ops (:op %))
                                    (not= agent (:agent %)))
                              (query/episode-span (:store @session) agent)))
        {:keys [steps shared]} (history/revert-steps changes others)]
    (cond
      (empty? (:forms changes))
      {:reverted 0 :note "episode is empty — already at the last done"}

      (empty? steps)
      {:reverted 0 :skipped-shared shared
       :note "every changed form is shared with other agents"}

      :else
      (let [r (edit-group! session steps
                           :prompt (or prompt
                                       (str "revert episode"
                                            (when agent (str " of " agent))))
                           :agent agent)]
        (if (or (:error r) (:conflict r))
          r
          (let [reverted (vec (remove (set shared) (map :form (:forms changes))))]
            (session/commit-appended!
             session
             (fn [base] (first (store/record-revert base :why prompt
                                                    :forms reverted
                                                    :agent agent)))
             [])
            (assoc r
                   :reverted (count steps)
                   :skipped-shared shared)))))))

(defn rename!
  "Rename `ns-sym/old-name` to `new-name` everywhere: ONE coordinated delta over
  the def + every reference across all namespaces (position-based via the index,
  so shadowed locals are untouched — see slopp.refactor). Hot-reloads every
  rewritten form, drops the old var (`ns-unmap`), re-verifies the affected
  tests, and records the outcome. Returns {:delta :renamed :test :affected} or
  {:error msg}."
  [session ns-sym old-name new-name & {:keys [prompt agent]}]
  (let [st   (:store @session)
        qold (symbol (str ns-sym) (str old-name))
        qnew (symbol (str ns-sym) (str new-name))]
    (cond
      (and (nil? (store/form-named st ns-sym old-name))
           (store/form-named st ns-sym new-name))
      ;; already renamed (a retried/duplicated intent) — state, not refusal
      {:renamed {:old old-name :new new-name :forms 0 :already true}}

      (nil? (store/form-named st ns-sym old-name))
      (edit/missing-form-error st ns-sym old-name)

      (store/form-named st ns-sym new-name)
      {:error (str new-name " already exists in " ns-sym)}

      :else
      (let [code-cs      (refactor/rename-changeset st ns-sym old-name new-name)
            ;; QUALIFIED references in prose follow mechanically — `a.b/c` in a
            ;; docstring can only mean that var. BARE mentions stay a :mentions
            ;; hint below, since `fee`/`zone` are usually domain words too.
            changeset    (merge code-cs
                                (refactor/qualified-mention-changeset
                                 st {qold qnew} code-cs))
            [st' delta]  (store/apply-changeset st :rename ns-sym changeset
                                                :prompt prompt :agent agent
                                                :extra {:old old-name :new new-name})
            touched-nses (distinct (map #(store/ns-of-form-id st' %) (keys changeset)))
            ;; affected tests, judged against the PRE-rename trace map
            changed-syms (into #{qold qnew}
                               (keep (fn [id]
                                       (let [e (store/form-by-id st' id)]
                                         (when (:name e)
                                           (symbol (str (store/ns-of-form-id st' id))
                                                   (str (:name e)))))))
                               (keys changeset))
            tmap         (:test-map @session)
            affected     (when (seq tmap)
                           (let [hits (->> tmap
                                           (keep (fn [[t forms]]
                                                   (when (or (contains? changed-syms t)
                                                             (seq (set/intersection forms changed-syms)))
                                                     (if (= t qold) qnew t))))
                                           sort vec)]
                             (when (seq hits) hits)))
            ;; X2: the renamed DEFINITION must reload before its callers —
            ;; hash-map key order destroyed cross-ns renames at scale
            def-id       (:id (store/form-named st' ns-sym new-name))
            ordered-ids  (into [def-id] (remove #{def-id} (keys changeset)))]
        (if-let [err (:err (session/hot-load-all! session st' ordered-ids))]
          (edit/compile-error st' err "rename failed to compile: ")
          (if-not (session/try-commit! session st st' (vec touched-nses))
            (do (session/fresh-image! session)
              {:conflict {:reason "store changed during rename — retry"}})
            (do
              (swap! session update :test-map session/rename-in-trace qold qnew)
              (session/persist-trace! session)
              (repl/eval! (:image @session)
                          (format "(ns-unmap '%s '%s)" ns-sym old-name))
              (let [summary (session/run-verification! session ns-sym affected
                                               :edited changed-syms)]
                (session/commit-appended! session
                                  #(store/record-verification % ns-sym summary)
                                  [])
                (let [pat      (refactor/symbol-mention-re old-name)
                      mentions (->> (for [nsx (sort (keys (:namespaces st')))
                                          e   (store/forms st' nsx)
                                          :when (some #(re-find pat %)
                                                      (str/split-lines (n/string (:node e))))]
                                      {:ns nsx :form (or (:name e) (:id e))})
                                    (take 10) vec)]
                  (cond-> {:delta    delta
                           :renamed  {:old qold :new qnew :forms (count changeset)}
                           :test     summary
                           :affected (or affected :all)}
                    (seq mentions)
                    (assoc :mentions mentions
                           :hint (str "prose/string mentions of " old-name
                                      " remain in these forms — edit_subform"
                                      " {text: true} rewrites them if the docs"
                                      " should follow"))))))))))))

(defn extract!
  "Phase-3 structural op: extract a UNIQUE subform of `from` into a new fn
  `new-name` — params are the free locals in first-use order (computed from
  the index's local analysis), the new fn lands BEFORE `from` (compile order),
  and the subform becomes the call. One atomic intent: three grouped deltas
  (add, move, replace), compile-checked before commit, verified once."
  [session ns-sym from new-name subform-src & {:keys [prompt at]}]
  (let [st   (:store @session)
        plan (refactor/extract-plan st ns-sym from subform-src new-name :at at)]
    (cond
      (:error plan) plan

      (store/form-named st ns-sym new-name)
      {:error (str new-name " already exists in " ns-sym)}

      :else
      (let [pd (edit/parse-form (:new-defn-src plan))
            pf (edit/parse-form (:new-from-src plan))]
        (cond
          (:error pd) pd
          (:error pf) pf
          :else
          (let [[gid st0] (store/alloc-id st "g")
                [st1 d1]  (store/append-form st0 ns-sym (:node pd)
                                             :prompt prompt :group gid)
                [st2 _]   (store/move-form st1 ns-sym new-name from
                                           :prompt prompt :group gid)
                [st3 d3]  (store/replace-node st2 ns-sym from (:node pf)
                                              :prompt prompt :group gid)]
            (if-let [err (:err (session/hot-load-all! session st3
                                              [(:form-id d1) (:form-id d3)]))]
              (edit/compile-error st3 err "extract failed to compile: ")
              (if-not (session/try-commit! session st st3 [ns-sym])
                {:conflict {:reason "store changed during extract — retry"}}
                (let [affected (session/affected-tests session ns-sym from)
                          summary  (session/run-verification! session ns-sym affected
                                                      :edited
                                                      #{(symbol (str ns-sym) (str from))
                                                        (symbol (str ns-sym) (str new-name))})]
                      (session/commit-appended! session
                                        #(store/record-verification % ns-sym summary)
                                        [])
                      {:extracted {:new    (symbol (str ns-sym) (str new-name))
                                   :params (:params plan)}
                       :group    gid
                       :test     summary
                       :affected (or affected :all)})))))))))

(defn- add-require-node
  "Candidate-store helper: add require `spec-str` to `nsx`'s ns form,
  returning the updated store (unchanged when the spec can't land — the
  compile gate downstream reports honestly)."
  [st nsx spec-str & {:keys [prompt group agent]}]
  (let [decl (store/form-named st nsx nsx)
        r    (when decl (edit/add-require-source (n/string (:node decl)) spec-str))]
    (if (or (nil? r) (:error r))
      st
      (or (first (store/replace-node st nsx nsx
                                     (first (n/children (p/parse-string-all (:src r))))
                                     :prompt prompt :group group :agent agent))
          st))))

(defn- remove-require-node
  "Candidate-store helper, symmetric with `add-require-node`: drop `lib`'s
  require spec from `nsx`'s ns form, returning the updated store (unchanged
  when the spec can't be dropped — the compile gate downstream reports
  honestly)."
  [st nsx lib & {:keys [prompt group agent]}]
  (let [decl (store/form-named st nsx nsx)
        r    (when decl (edit/remove-require-source (n/string (:node decl)) lib))]
    (if (or (nil? r) (:error r))
      st
      (or (first (store/replace-node st nsx nsx
                                     (first (n/children (p/parse-string-all (:src r))))
                                     :prompt prompt :group group :agent agent))
          st))))

(defn move-forms!
  "Move `form-names` from `from-ns` into `to-ns` — NEW or EXISTING — the
  general relocation refactor (clj-surgeon's :extract!, slopp-grade, v2).
  Callers EVERYWHERE (production + tests) are rewritten to alias-qualified
  calls and gain the require; the moved defs are publicized (module-grain
  visibility replaces var privacy); the target gets only the requires the
  moved code uses. Dependency direction is analyzed: stay→moved adds the
  require back to from-ns, moved→stay qualifies stay refs instead, a
  two-way split refuses (real cycle). Cross-module edges the move's own
  rewires necessitate are AUTO-DECLARED (the move's prompt rides each
  :module-edge delta — the move IS the declared intent), refusing only a
  cycle-closer; `:export true` marks moved vars ^:export when the deep
  target must stay callable from outside its subtree. One atomic group +
  changeset, compile-gated, verified across every touched namespace."
  [session from-ns form-names to-ns & {:keys [prompt agent export]}]
  (let [st   (:store @session)
        plan (refactor/move-plan st from-ns form-names to-ns {:export export})]
    (if (:error plan)
      (select-keys plan [:error])
      (let [manifest (edit.modules/modules-manifest st)
            rows     (map (fn [r]
                            (assoc r :to-export
                                   (if (= (symbol (str to-ns)) (:to r))
                                     export   ; true = world, string = subtree
                                     (edit.modules/export-level st (:to r) (:name r)))))
                          (:module-rows plan))
            ;; edges the move's rewires necessitate are part of its intent
            edges    (when manifest
                       (->> rows
                            (map (fn [r] [(edit.modules/module-of (:from-ns r))
                                          (edit.modules/module-of (:to r))]))
                            (remove (fn [[a b]] (= a b)))
                            (remove (fn [[a b]] (contains? (get manifest a #{}) b)))
                            distinct vec))
            cyclic   (seq (filter (fn [[a b]] (store/module-path manifest b a))
                                  edges))
            manifest' (reduce (fn [m [a b]] (update m a (fnil conj #{}) b))
                              manifest edges)
            viols    (edit.modules/module-violations manifest' rows)
            refusal  (cond
                       cyclic
                       (str "the move would close a module dependency cycle ("
                            (str/join ", " (map (fn [[a b]] (str a " → " b))
                                                cyclic))
                            ") — move the shared piece the other way, or"
                            " restructure the callers first")

                       viols
                       (str (str/join "; " (map :error viols))
                            (when (and (not export)
                                       (every? #(= :visibility (:rule %)) viols))
                              " — or pass export: true to hoist the moved vars")))]
        (if refusal
          {:error refusal}
          (let [[gid st-g] (store/alloc-id st "g")
                ;; 0. the auto-declared edges, each carrying the move's why
                st0 (reduce (fn [s [a b]]
                              (first (store/record-module-edge
                                      s a b :add
                                      :prompt (or prompt (str "move-forms: " from-ns " → " to-ns))
                                      :agent agent)))
                            st-g edges)
                ;; 1. the target: ingest new, or append + requires to existing
                st1 (if (:new-ns? plan)
                      (store/ingest st0 to-ns (:new-src plan) :agent agent)
                      (let [st* (reduce (fn [s node]
                                          (or (first (store/append-form
                                                      s to-ns node
                                                      :prompt prompt :group gid
                                                      :agent agent))
                                              s))
                                        st0 (:append plan))]
                        (reduce (fn [s spec]
                                  (add-require-node s to-ns spec
                                                    :prompt prompt :group gid
                                                    :agent agent))
                                st* (:to-require-adds plan))))
                ;; 2. callers gain [to-ns :as alias]
                st2 (reduce (fn [s [nsx spec]]
                              (add-require-node s nsx spec
                                                :prompt prompt :group gid
                                                :agent agent))
                            st1 (:require-adds plan))
                ;; 3. every rewritten caller, ONE changeset (multi-ns)
                [st3 _] (if (seq (:rewrites plan))
                          (store/apply-changeset
                           st2 :move-forms from-ns
                           (into {} (map (fn [[fid m]] [fid (:node m)]))
                                 (:rewrites plan))
                           :prompt prompt :agent agent)
                          [st2 nil])
                ;; 4. the moved forms leave home
                st4 (reduce (fn [s nm]
                              (or (first (store/remove-form s from-ns nm
                                                            :prompt prompt
                                                            :group gid :agent agent))
                                  s))
                            st3 (:removals plan))
                ;; 4b. the requires the move just orphaned leave with them.
                ;; A sequential move is what surfaced this: a caller rewritten
                ;; to `external/author-identity` moved in the NEXT batch and
                ;; took the reference with it, and the cold-load gate then
                ;; REFUSED a state the move itself had created.
                st4 (reduce (fn [s lib]
                              (remove-require-node s from-ns lib
                                                   :prompt prompt :group gid
                                                   :agent agent))
                            st4 (:from-require-drops plan))
                ;; 4c. and the mirror: a rewritten caller left referencing
                ;; nothing in from-ns drops that require. Left behind, a
                ;; :pure caller keeps inheriting from-ns's TIER for a
                ;; dependency it no longer has.
                st4 (reduce (fn [s nsx]
                              (remove-require-node s nsx from-ns
                                                   :prompt prompt :group gid
                                                   :agent agent))
                            st4 (:caller-require-drops plan))
                ;; the PIPELINE owns ordering. The planner no longer mints a
                ;; declare for the moved set: a source ns may have ordered
                ;; caller-before-callee behind a declare that STAYS BEHIND, so
                ;; the target can land with a forward ref — resolve-cold-load
                ;; reorders it (or inserts the pipeline's own MARKED declare
                ;; 4d. the PROSE follows the move: a docstring naming
                ;; from-ns/x must become to-ns/x. Qualified references only —
                ;; a bare name may be a domain word. This is the d9077 class
                ;; at its source: `analyze` moved namespaces and two guidance
                ;; surfaces kept naming its pre-move address.
                st4 (let [cs (refactor/qualified-mention-changeset
                              st4
                              (into {} (for [nm (:moved plan)]
                                         [(symbol (str from-ns) (str nm))
                                          (symbol (str to-ns) (str nm))]))
                              {})]
                      (if (seq cs)
                        (first (store/apply-changeset st4 :move-forms from-ns cs
                                                      :prompt prompt :agent agent))
                        st4))
                ;; for a genuine cycle). Same one call fix-declares! makes.
                st4 (if-let [rz (edit/resolve-cold-load
                                 st4 to-ns
                                 :prompt (or prompt (str "move-forms: " from-ns
                                                         " → " to-ns))
                                 :agent agent)]
                      (:store rz)
                      st4)
                touched (vec (distinct
                              (into [from-ns to-ns]
                                    (concat (map :ns (vals (:rewrites plan)))
                                            (keys (:require-adds plan))))))
                ;; defs before callers (X2): target forms, then decls, then rewrites
                ordered (distinct
                         (concat (map :id (store/forms st4 to-ns))
                                 (keep #(:id (store/form-named st4 % %))
                                       (keys (:require-adds plan)))
                                 (keys (:rewrites plan))))
                hl       (session/hot-load-all! session st4 ordered)
                ;; the COLD-load gate, which a move needs more than any other write:
                ;; it is the one operation that reorders NAMESPACE dependencies,
                ;; and hot-loading structurally cannot see a require cycle
                ;; because the vars already exist in the image. A move once
                ;; committed `[slopp/api] -> slopp/api/external -> [slopp/api]`
                ;; and verified GREEN over it.
                load-err (or (:err hl) (edit/cold-load-errors st4 touched))]
            (if load-err
              (do (session/fresh-image! session)
                  (edit/compile-error st4 load-err "move failed to compile: "))
              (if-not (session/try-commit! session st st4 touched)
                (do (session/fresh-image! session)
                    {:conflict {:reason "store changed during move — retry"}})
                (do (doseq [nm (:removals plan)]
                      (repl/eval! (:image @session)
                                  (format "(ns-unmap '%s '%s)" from-ns nm)))
                    (let [moved-q (into (set (map #(symbol (str from-ns) (str %))
                                                  (:moved plan)))
                                        (map #(symbol (str to-ns) (str %)))
                                        (:moved plan))
                          summary (session/run-verification!
                                   session touched nil
                                   :edited (into moved-q
                                                 (map (fn [[_ m]]
                                                        (symbol (str (:ns m)) (str (:name m)))))
                                                 (:rewrites plan)))]
                      (session/commit-appended! session
                                        #(store/record-verification
                                          % touched summary)
                                        [])
                      (let [;; POSTCONDITION, read back from the store this move actually
                            ;; COMMITTED rather than from its plan. The gate pre-check
                            ;; consults the PLANNED export, so a marker pass that skips a
                            ;; name — it once skipped every meta-wrapped one, leaving
                            ;; `^:dynamic` vars unexported — passes the gate and surfaces
                            ;; a session later, somewhere else.
                            miss (modules/unlanded-exports (:store @session) rows)]
                        (cond-> {:moved-to to-ns
                                 :moved (:moved plan)
                                 :rewrote (count (:rewrites plan))
                                 :callers (vec (sort (distinct (map :ns (vals (:rewrites plan))))))
                                 :group gid
                                 :test summary}
                          (seq edges) (assoc :edges-declared edges)
                          (seq miss)
                          (assoc :export-not-landed miss
                                 :export-note
                                 (str "the move planned an export for "
                                      (str/join ", " miss)
                                      " and the committed store carries no marker —"
                                      " mark the name(s) ^:export directly; callers"
                                      " outside the subtree will not resolve them"))))))))))))))

(defn ns-rename!
  "Rename a WHOLE namespace: its ns decl, every require clause, and every
  fully-qualified reference across the store; the namespaces map rekeys; the
  image rebuilds fresh (old name gone); everything re-verifies."
  [session old new & {:keys [prompt agent defer-verify]}]
  (let [st  (:store @session)
        old (symbol (str old)) new (symbol (str new))]
    (cond
      (nil? (get-in st [:namespaces old]))
      {:error (str "no namespace " old)}

      (get-in st [:namespaces new])
      {:error (str new " already exists")}

      :else
      (let [code-cs   (refactor/ns-rename-changeset st old new)
            ;; every form of the renamed ns is re-addressed, so prose naming
            ;; old/x must follow to new/x — qualified references only; a bare
            ;; name may be a domain word
            changeset (merge code-cs
                             (refactor/qualified-mention-changeset
                              st
                              (into {} (for [e (store/forms st old)
                                             :when (:name e)]
                                         [(symbol (str old) (str (:name e)))
                                          (symbol (str new) (str (:name e)))]))
                              code-cs))
            [st1 delta] (store/apply-changeset st :rename-ns old changeset
                                               :prompt (or prompt (str "rename ns "
                                                                       old " → " new))
                                               :agent agent
                                               :extra {:old old :new new})
            st2 (update st1 :namespaces
                        (fn [m] (-> m (dissoc old) (assoc new (get m old)))))
            touched (vec (distinct (concat [old new]
                                           (keep #(store/ns-of-form-id st2 %)
                                                 (keys changeset)))))]
        (if-not (session/try-commit! session st st2 touched)
          {:conflict {:reason "store changed during ns-rename — retry"}}
          (do
            ;; trace map: rewrite every old/... qsym
            (swap! session update :test-map
                   (fn [tm]
                     (let [fix #(if (= (str old) (namespace %))
                                  (symbol (str new) (name %)) %)]
                       (into {} (map (fn [[t fs]]
                                       [(fix t) (into #{} (map fix) fs)]))
                             tm))))
            ;; the manifest follows: module names are ns prefixes, so when the
            ;; LAST ns of a module renames away, its edges re-key (semantic
            ;; :module-edge removes+adds — the journal shows the follow)
            (let [old-mod (edit.modules/module-of old)
                  new-mod (edit.modules/module-of new)
                  why     (str "manifest follows ns rename " old " → " new)]
              (when (and (not= old-mod new-mod)
                         (not-any? #(= old-mod (edit.modules/module-of %))
                                   (keys (:namespaces (:store @session)))))
                (session/commit-appended!
                 session
                 (fn [base]
                   (let [sub #(if (= old-mod %) new-mod %)]
                     (reduce
                      (fn [s [m deps]]
                        (cond
                          (= m old-mod)
                          (as-> s $
                            (reduce #(first (store/record-module-edge
                                             %1 m %2 :remove :prompt why :agent agent))
                                    $ (sort deps))
                            (reduce #(first (store/record-module-edge
                                             %1 new-mod %2 :add :prompt why :agent agent))
                                    $ (sort (disj (into #{} (map sub) deps) new-mod))))

                          (contains? deps old-mod)
                          (as-> s $
                            (first (store/record-module-edge
                                    $ m old-mod :remove :prompt why :agent agent))
                            (if (= m new-mod)
                              $
                              (first (store/record-module-edge
                                      $ m new-mod :add :prompt why :agent agent))))

                          :else s))
                      base (or (:modules base) {}))))
                 [])))
            ;; and so do the namespace-grained registers. A tier or platform describes
            ;; a NAME: orphaned, it both lists a namespace that no longer exists and
            ;; leaves the renamed code ungated. Deep namespaces follow by prefix.
            (let [st*     (:store @session)
                  why     (str "declaration follows ns rename " old " → " new)
                  moved   (fn [reg]
                            (vec (for [[k v] (get st* reg)
                                       :when (or (= k (str old))
                                                 (str/starts-with? k (str old ".")))]
                                   [k (str new (subs k (count (str old)))) v])))
                  tiers   (moved :module-tiers)
                  plats   (moved :module-platforms)]
              (when (or (seq tiers) (seq plats))
                (session/commit-appended!
                 session
                 (fn [base]
                   (as-> base $
                     (reduce (fn [s [k k' v]]
                               (-> s
                                   (store/record-module-tier k' v :prompt why :agent agent) first
                                   (store/record-module-tier k nil :action :remove
                                                             :prompt why :agent agent) first))
                             $ tiers)
                     (reduce (fn [s [k k' v]]
                               (-> s
                                   (store/record-module-platform k' v :prompt why :agent agent) first
                                   (store/record-module-platform k nil :action :remove
                                                                 :prompt why :agent agent) first))
                             $ plats)))
                 [])))
            (session/fresh-image! session)          ; the old ns must NOT linger
            (let [verify-nses (vec (remove #{old} touched))
                  ;; `defer-verify` hands the verification to the COMPOSITE that owns
                  ;; this rename (module_extract). Mid-batch the store has
                  ;; namespaces renamed and callers not yet rewritten, so
                  ;; verifying here is both expensive and meaningless — the only
                  ;; bar a batch can meet is that its END STATE is green. One
                  ;; logical change used to pay N verifications purely because
                  ;; each step was spelled as a verb.
                  summary (when-not defer-verify
                            (session/run-verification!
                             session verify-nses nil
                             :edited (into #{}
                                           (keep (fn [id]
                                                   (when-let [e (store/form-by-id st2 id)]
                                                     (symbol (str (store/ns-of-form-id st2 id))
                                                             (str (or (:name e) (:id e)))))))
                                           (keys changeset))))]
              (when summary
                (session/commit-appended! session
                                  #(store/record-verification % verify-nses summary)
                                  []))
              ;; frictions #7/#13: symbols are rewritten perfectly and everything else
              ;; — a name inside a string, the `-test` sibling's own name — is
              ;; left, correctly and SILENTLY. Silence reads as "there was
              ;; nothing to carry". Scanned AFTER the write over the OLD name, so
              ;; whatever is still there was, by construction, not rewritten.
              (let [left (group-by :via (refs/occurrences-of (:store @session) old))]
                (cond-> {:renamed {:old old :new new :forms (count changeset)}
                         :delta (:id delta)}
                  summary      (assoc :test summary)
                  ;; hand the scope UP: the owning transaction verifies this
                  ;; namespace set once, after the whole batch has landed
                  defer-verify (assoc :deferred-verification true
                                      :verify-nses verify-nses)
                  (seq left)
                  (assoc :left-behind left
                         :note
                         (str (count (apply concat (vals left))) " occurrence(s) of "
                              old " were NOT rewritten"
                              (when-let [ss (seq (remove :prose (:string left)))]
                                (str " — " (count ss) " in TOKEN strings (a path, a"
                                     " main-ns, a require target: these BREAK, they do"
                                     " not merely read wrong)"))
                              (when (:test-sibling left)
                                (str "; the -test sibling still carries the old name,"
                                     " which files its tests under the old module"))
                              ". Judge each: string rewriting is deliberately"
                              " conservative, so this list is the whole signal.")))))))))))

(defn affected-test-nses
  "The PROVABLE verification slice: test namespaces (any ns holding a
  deftest) whose require-closure reaches a form changed since the last
  MILESTONE — a test can only exercise code it can load. Returns
  {:changed-nses [...] :selected [...]}; empty :selected = nothing since
  the milestone can affect any test. Full-suite confidence stays the
  milestone gate's job."
  [session]
  (let [st      (:store @session)
        last-c  (:id (last (filter #(= :commit (:op %)) (store/deltas st))))
        changed (into #{}
                      (keep #(store/ns-of-form-id st %))
                      (forms-changed-since st last-c))]
    {:changed-nses (vec (sort changed))
     :selected     (session/test-nses-reaching st changed)}))

(defn last-judged-done
  "The most recent verdict that actually JUDGED something (`:test-status`
  `:red` or `:green`), or nil — from a `:done` delta or from a whole-store
  `full_check`, whichever came last.

  Exists because `done` is episode-scoped: calling it twice with no writes
  between yields `:none` the second time — nothing changed, so nothing was
  checked. Without this a RED done could be laundered by simply committing
  afterwards: the milestone runs its own done, gets `:none`, and publishes.
  The last real verdict stands until new work supersedes it.

  **A green `full_check` is such a verdict (friction 14).** Reading only
  `:done` deltas left a trap with no way out: an episode whose changed forms
  have no covering tests — a rename, a docstring, a `:cljs` edit — judges
  `:none`, so `commit_point` reaches back to a verdict that can be arbitrarily
  old, and every subsequent done judges `:none` too. The store got greener
  while the milestone stayed refused, and the whole-store check — broader,
  slower, more authoritative — could not clear what the narrower one left
  behind. Now it can, and a later `done` supersedes it in turn: most recent
  judgement wins, whichever kind it is.

  Only `:scope :full-check` counts. `record-verification` also lands on
  ordinary writes, and those are form-scoped — a per-write green says nothing
  about the store.

  Returns the whole findings map rather than the status alone so a standing
  red can still NAME what was wrong — a refusal that cannot say why is a
  refusal an agent cannot act on. The full_check verdict carries only what its
  delta recorded, and deliberately not its namespace count or wall time:
  `commit_point` builds its refusal reason from whichever keys are present and
  non-zero, so an informational count would make a refusal say `namespaces`
  as though that were the thing that fired."
  [store]
  (->> (store/deltas store)
       (keep (fn [d]
               (cond
                 (and (= :done (:op d))
                      (#{:red :green} (:test-status (:findings d))))
                 (:findings d)

                 (and (= :verify (:op d))
                      (= :full-check (:scope (:result d)))
                      (#{:red :green} (:status (:result d))))
                 (let [r (:result d)]
                   (cond-> {:test-status (:status r) :scope :full-check}
                     (pos? (:lint-errors r 0))
                     (assoc :lint-errors (:lint-errors r)))))))
       last))

(defn file-put!
  "Track a NON-CODE file on the store's files manifest — it rides every
  projected tree, so slopp pushes never delete it.

  Content comes from `content` inline, or from `:source`, a path on disk.
  Both are AUTHORED — tracked, versioned, and part of the projected tree.
  They differ only in how the bytes are stored:

  - `content` inline: text the agent wrote. Stored in the delta itself, so
    it is diffable and time-travellable with `file_get :at`.
  - `:source`: a file that already exists — an image, a font, something
    imported. Stored CONTENT-ADDRESSED, so the journal carries a sha and
    the bytes live once in `:blobs` rather than inline in every delta.

  Measured cause: 30.5 MB of this store's delta log is inline file content,
  99.8% of it one regenerable bundle re-appended on every build. Sniffing
  the content-type was worse than useless here — it called
  `application/javascript` text and put a vendored library straight into a
  delta. Provenance decides, not file type.

  What does NOT belong on this manifest at all is a file that can be
  REGENERATED — a compiled bundle, a downloaded library. Those are
  `:artifacts`: bytes on disk, sha and recipe in the journal, written by
  `compile_client` and `js_dep`. The distinction is recoverability, not
  authorship: an artifact that is deleted is rebuilt, a file that is
  deleted is lost.

  `:encoding \"base64\"` still decodes `content` explicitly.
  Returns {:path :bytes} (+ :sha when content-addressed)."
  [session path content & {:keys [prompt agent encoding content-type source]}]
  (let [from-src (when (and source (nil? content))
                   (let [f (java.io.File. (str source))]
                     (if-not (.exists f)
                       {:error (str "no file at " (str source))}
                       ;; ALWAYS content-addressed: a file read off disk is an
                       ;; artifact, whether or not its bytes happen to be text
                       {:content  (.encodeToString (java.util.Base64/getEncoder)
                                                   (java.nio.file.Files/readAllBytes
                                                    (.toPath f)))
                        :encoding "base64"})))
        content  (or content (:content from-src))
        encoding (or encoding (:encoding from-src))]
    (cond
      (str/blank? (str path))
      {:error "file_put needs a :path"}

      (:error from-src)
      {:error (:error from-src)}

      (nil? content)
      {:error "file_put needs :content, or :source naming a file to read it from"}

      (and encoding (not= "base64" (str encoding)))
      {:error (str "unknown :encoding " encoding " — omit it for text, or"
                   " \"base64\" for binary")}

      :else
      (let [st' (session/commit-appended! session
                                          #(first (store/record-file-put % path content
                                                                         :prompt prompt :agent agent
                                                                         :encoding encoding
                                                                         :content-type content-type))
                                          [])
            e   (get (:files st') (str path))]
        (if (map? e)
          {:path (str path) :bytes (:bytes e) :sha (:sha e)}
          {:path (str path) :bytes (count (str content))})))))

(defn file-remove!
  "Drop `path` from the files manifest. Returns {:removed path} | {:error}."
  [session path & {:keys [prompt agent]}]
  (if-not (contains? (:files (:store @session)) (str path))
    {:error (str path " is not on the files manifest")}
    (do (session/commit-appended! session
                          #(first (store/record-file-remove % path
                                                            :prompt prompt :agent agent))
                          [])
        {:removed (str path)})))

^:reads (defn files-list
  "The files manifest: {path byte-count} (content via the git projection or
  a build)."
  [session]
  {:files (into (sorted-map)
                (map (fn [[p t]] [p (count t)]))
                (:files (:store @session)))})

(defn set-comment!
  "Set (or clear, with blank `text`) the comment rendered above form `nm`.

  A different shape from the positional predecessor it replaces: trivia was
  placed BEFORE a form and owned by nobody, a comment is owned BY the form.
  That removes the positional question entirely — no anchor, no run to
  replace, and a merge reconciles it as ordinary content on a form identity.

  No image work and no verification: a comment changes what a namespace
  RENDERS, never what it means. Returns {:delta :ns :name} | {:error} |
  {:conflict}."
  [session ns-sym nm text & {:keys [prompt agent]}]
  (let [base (:store @session)
        r    (store/set-comment base ns-sym nm text :prompt prompt :agent agent)]
    (if (:error r)
      r
      (let [[st' d] r]
        (if-not (session/try-commit! session base st' [ns-sym])
          {:conflict {:reason "store changed concurrently — retry"}}
          {:delta (:id d) :ns (str ns-sym) :name (str nm)})))))

^:reads (defn file-get
  "A manifest file's content — current, or as of a past delta via `:at`
  (a delta id or commit-point id resolves through its :target). Text →
  {:path :content}; binary → {:path :content <base64> :encoding \"base64\"
  :content-type :sha} (bytes from the in-memory cache, else the db — a
  foreign-synced entry). Returns {:error} for unknown paths."
  [session path & {:keys [at]}]
  (let [st (:store @session)
        b64 (fn [^bytes bs] (.encodeToString (java.util.Base64/getEncoder) bs))
        resolved (fn [entry]
                   (if (map? entry)
                     (let [bs (or (get (:blobs st) (:sha entry))
                                  (some-> (:db @session)
                                          (db/get-blob (:sha entry))))]
                       (if bs
                         {:path (str path) :content (b64 bs)
                          :encoding "base64"
                          :content-type (:content-type entry) :sha (:sha entry)}
                         {:error (str path " is a binary entry whose blob "
                                      (:sha entry) " is not in this store")}))
                     {:path (str path) :content entry}))]
    (if at
      (let [at-id (or (some (fn [d] (when (and (= :commit (:op d)) (= at (:id d)))
                                      (:target d)))
                            (store/deltas st))
                      at)
            c     (store/file-at st (str path) at-id)]
        (if (some? c)
          (assoc (resolved c) :at at)
          {:error (str path " has no content at " at)}))
      (if-let [e (get (:files st) (str path))]
        (resolved e)
        {:error (str path " is not on the files manifest")}))))

^:reads (defn file-history!
  "Every tracked version of a manifest file, oldest first, with provenance —
  the file counterpart of query_history {ns name}."
  [session path]
  (let [h (store/file-history (:store @session) (str path))]
    (if (seq h)
      {:path (str path) :versions h}
      {:error (str path " has never been tracked")})))

(defn config-file!
  "Structured config files: the store holds SEMANTIC key/values per path
  (per-key delta history, like forms); the projection serializes them into
  the file format (`:manifest` → sorted `K: V` lines). Set a key
  (`:key`+`:value`, `:format` on first touch, default :manifest), remove one
  (`:key`+`:unset true`), or read (path only: values + rendered preview).
  The module manifest is NOT a config file — module_dep is its verb. The
  `capabilities` path validates through the capability registry
  (`capabilities/config-refusal`): unknown keys and type-failing values are
  refused with teaching before any delta lands."
  [session path & {:keys [key value unset format prompt agent]}]
  (let [entry (get-in (:store @session) [:config (str path)])]
    (cond
      (= "modules" (str path))
      {:error "the module manifest is edge-grain, not a file — declare or retract one dependency at a time: module_dep {from \"x.y\" to \"a.b\"} (add) or module_dep {from \"x.y\" to \"a.b\" remove true}; read it via query_depends {modules true}"}

      (and key unset)
      (if-not (get-in entry [:values (str key)])
        {:error (str key " is not set on " path)}
        (do (session/commit-appended! session
                              #(first (store/record-config-unset % path key
                                                                 :prompt prompt
                                                                 :agent agent))
                              [])
            {:path (str path) :unset (str key)}))

      (and key (some? value))
      (if-let [refusal (when (= "capabilities" (str path))
                         (capabilities/config-refusal (str key) (str value)))]
        {:error refusal}
        (let [fmt (or (some-> format clojure.core/keyword)
                      (:format entry) :manifest)]
          (session/commit-appended! session
                            #(first (store/record-config-put % path fmt key value
                                                             :prompt prompt
                                                             :agent agent))
                            [])
          ;; `capabilities` is the ONLY path with a registry behind it. Every
          ;; other path records the key and value as given, so a caller
          ;; cannot tell a checked write from an unchecked one unless the
          ;; result says which happened (D-surface-honesty).
          (let [caps? (= "capabilities" (str path))]
            (cond-> {:path (str path) :key (str key) :value (str value) :format fmt
                     :verified (if caps? [:registry] [])
                     :unverified (if caps? [] [:schema])}
              (not caps?)
              (assoc :note (str "recorded as given — no registry governs the "
                                path " config, so neither the key nor the value"
                                " was validated. Only `capabilities` writes are"
                                " checked (query_capabilities lists them)."))))))

      key
      (if-let [v (get-in entry [:values (str key)])]
        {:path (str path) :key (str key) :value v}
        {:error (str key " is not set on " path)})

      :else
      (if entry
        {:path (str path) :format (:format entry) :values (:values entry)
         :rendered (store/render-config entry)}
        {:error (str path " has no structured config")}))))

(defn change-signature!
  "P2: change `ns-sym/fn-name`'s signature as ONE atomic intent — replace
  the defn with `new-source` (keep the name; the lint gate is the oracle if
  you don't) and mechanically rewrite every call site's argument list from
  `args-template` ($1..$9 = the site's existing arg sources; the callee
  stays as written, so aliases survive — see refactor/change-signature-plan).
  Executes through edit-group! (one gate pass, one verification).
  References that can't be rewritten come back under :manual."
  [session ns-sym fn-name new-source args-template & {:keys [prompt agent]}]
  (let [st (:store @session)]
    (if (nil? (store/form-named st ns-sym fn-name))
      (edit/missing-form-error st ns-sym fn-name)
      (let [plan (refactor/change-signature-plan st ns-sym fn-name args-template)]
        (if (:error plan)
          plan
          (let [steps (into [{:action :replace :ns ns-sym :name fn-name
                              :source new-source}]
                            (:caller-steps plan))
                r     (edit-group! session steps
                                   :prompt (or prompt
                                               (str "change signature: " fn-name))
                                   :agent agent)]
            (cond-> (assoc r :rewrote (count (:caller-steps plan)))
              (seq (:manual plan)) (assoc :manual (:manual plan)))))))))

^:reads (defn draft-test
  "Rock 5: a ready-to-EDIT deftest draft for `ns-sym/nm`. With `:code` (a
  driver expression) it OBSERVES real calls and turns each capture into an
  assertion — tests grown from observed behavior, not invented values.
  Without :code, a signature-shaped skeleton with TODO holes. The draft is
  a SUGGESTION in the result — nothing is written; adopt it with
  edit_add_form after reading each assertion (red-first still applies)."
  [session ns-sym nm & {:keys [code limit] :or {limit 5}}]
  (if-let [e (store/form-named (:store @session) ns-sym nm)]
    (let [qname (str ns-sym "/" nm)
          obs   (when code
                  (if-let [err (edit/observe-gate code)]
                    {:error err}
                    (first (repl/eval! (:image @session)
                                       (format "(slopp.rt/observe '%s (fn [] %s) %d)"
                                               qname code limit)))))]
      (cond
        (:error obs) obs

        (seq (:calls obs))
        {:draft    (str "(deftest " nm "-t\n"
                        (str/join "\n"
                                  (map (fn [{:keys [args ret threw]}]
                                         (if threw
                                           (str "  (is (thrown? Exception ("
                                                qname " " (str/join " " args) ")))")
                                           (str "  (is (= " ret " ("
                                                qname " " (str/join " " args) ")))")))
                                       (:calls obs)))
                        ")")
         :observed (count (:calls obs))
         :note     (str "grown from OBSERVED calls — read each assertion before"
                        " adopting; edit_add_form lands it (the ns needs"
                        " [clojure.test :refer [deftest is]])")}

        :else
        (let [params (or (some #(when (vector? %) %) (drop 2 (n/sexpr (:node e))))
                         '[args])]
          {:draft (str "(deftest " nm "-t\n  (is (= :TODO-expected (" qname " "
                       (str/join " " (map #(str ":TODO-" %) params)) "))))")
           :note  (str "no examples — pass :code (a driver expression) to observe"
                       " real calls and get value-true assertions instead of holes")})))
    (edit/missing-form-error (:store @session) ns-sym nm)))

(defn remember-observation!
  "Persist what an observation SAW (up to two {:args :ret} captures) under
  store meta observed/<ns>/<name> — interface cards surface them as
  :examples, the strongest behavior line a card can carry (examples don't
  lie; prose can). Called by the MCP layer after query_observe; a no-op
  for ephemeral sessions or empty captures.

  Writes THROUGH: the db row is the durable copy (reloaded by
  `session/load-observations` at open) and the session's `:observed` map is
  the working one that `orient/form-card` reads. Both, or a card in this
  session would not see what was just observed."
  [session ns-sym nm observe-result]
  (when-let [calls (seq (:calls observe-result))]
    (let [k   (str "observed/" ns-sym "/" nm)
          raw (pr-str (vec (take 2 calls)))]
      (swap! session assoc-in [:observed k] raw)
      (when-let [conn (:db @session)]
        (try
          (db/set-meta! conn k raw)
          (catch Exception _ nil)))))
  nil)

^:reads (defn session-brief
  "THE one-call orientation, task-shaped (knowledge-differential stance):
  breadth stays CHEAP — namespace FAMILIES (≥5 same-prefix siblings) roll
  up to one row, form names ride only for solo nses on small stores — and
  depth arrives WHERE THE ASK POINTS: the session's :last-intent (the
  user's verbatim words, via the prompt hook or turn_begin) is mined
  against form names, deterministically, and the top matches ride as
  interface CARDS under :relevant. The agent starts working instead of
  orienting. :host is the serving process's code-currency record
  (orient/host-brief over the kernel's boot-info, reached through the
  late-ref carrier — absent when this process didn't boot from a store):
  which code the host actually runs, and what a restart would change."
  [session]
  (let [st       (:store @session)
        nss      (sort (keys (:namespaces st)))
        names    (into {} (map (fn [n] [n (vec (remove #{n} (keep :name (store/forms st n))))])) nss)
        total    (reduce + 0 (map (comp count val) names))
        fams     (group-by #(first (str/split (str %) #"\.")) nss)
        project  (vec (mapcat (fn [[seg members]]
                                (if (<= 5 (count members))
                                  [{:family (str seg ".*") :nses (count members)
                                    :forms (reduce + 0 (map (comp count names) members))}]
                                  (for [n members]
                                    (if (< 200 total)
                                      {:ns n :forms (count (names n))}
                                      {:ns n :forms (names n)}))))
                              (sort-by key fams)))
        ms       (->> (query-commits session)
                      (take 5)
                      (mapv #(-> (select-keys % [:commit :description :at :status])
                                 (update :description orient/snip 110))))
        last-done (let [d (last (filter #(= :done (:op %)) (store/deltas st)))]
                    (when (and d (or (= :red (get-in d [:findings :test-status]))
                                     (pos? (get-in d [:findings :lint-errors] 0))))
                      (-> (select-keys d [:label :at :findings])
                          (assoc :note (str "the last done-point left problems —"
                                            " address them or tell the user why not")))))
        ;; the kernel ns exists only in a process that booted from a store
        ;; (the dev server, a jar launch) — reach it through the carrier and
        ;; treat any failure as absence, never an error
        host     (when-let [info (try ((store/late-ref 'slopp.boot/current-boot-info))
                                      (catch Throwable _ nil))]
                   (orient/host-brief info (count (filter #(and (> (:at % 0) (:booted-at info 0))
                                        (not (contains? fields/markers (:op %))))
                                   (store/deltas st))) (boolean (when-let [b (:branch @session)]
                               (not= "main" (str b))))
                                     ;; MEASURED, not inferred: without this the
                                     ;; brief repeats whatever the reload counter
                                     ;; believes, which is how it once announced
                                     ;; five stale namespaces to a process that
                                     ;; held every one of them current.
                                     (currency/drift st)))
        intent   (:last-intent @session)
        stop     #{"with" "that" "this" "must" "have" "from" "when" "will" "your"
                   "tell" "every" "should" "their" "them" "than" "then" "they"
                   "what" "where" "which" "been" "back" "also" "only" "into"}
        tokens   (when intent
                   (into #{}
                         (comp (map str/lower-case)
                               (filter #(<= 4 (count %)))
                               (remove stop))
                         (re-seq #"[A-Za-z][A-Za-z0-9-]+" intent)))
        score    (fn [nm]
                   (let [words (str/split (str/lower-case (str nm)) #"-")]
                     (count (filter tokens words))))
        relevant (when (seq tokens)
                   (->> (for [n nss, f (names n)
                              :let [s (score f)]
                              :when (pos? s)]
                          [s (symbol (str n) (str f))])
                        (sort-by (comp - first))
                        (map second)
                        (take 5)
                        (keep #(orient/form-card session (symbol (namespace %))
                                          (symbol (name %))))
                        vec
                        not-empty))]
    (cond-> {:project project
             ;; the loop is taught in full by the slopp SKILL; the brief only needs to
             ;; NAME it. Measured: 472 chars, byte-identical in all 5 sessions of an
             ;; eval9 lifetime — orientation should carry what CHANGED, not re-teach
             ;; what the skill already said.
             :loop (str "small verified writes → done {label} at each finish point"
                        " → ONE commit_point. Results are self-describing: act on"
                        " them, don't narrate them. (Full loop: the slopp skill.)")}
      (seq ms)   (assoc :milestones ms)
      last-done  (assoc :last-done last-done)
      host       (assoc :host host)
      ;; the reviewer UI, when the server brought one up. It is for a HUMAN,
      ;; and its only other announcement is a line on the server's stderr —
      ;; which most clients never show anyone. Hand the url over when asked
      ;; what is going on, rather than making them know to ask for it.
      (:ui-url @session) (assoc :ui (:ui-url @session))
    ;; the HUB's url when this project registered with one — that is the
    ;; address to hand a human on a machine running several projects, and
    ;; the per-project one above is a derived port nobody should type
    (:ui-hub @session) (assoc :ui-hub (:ui-hub @session))
      relevant   (assoc :relevant relevant))))

^:reads (defn report
  "The handoff/summary composite (ratio push): milestones, net form-level
  changes with their recorded ASKS, and the last verification state — the
  history fan-out (query_history + query_history {contains} + query_changes +
  query_commits + git diffs) as ONE deterministic read. `:since` = a
  delta/milestone id; `:contains` filters asks/descriptions."
  [session & {:keys [since contains limit] :or {limit 50}}]
  (let [st        (:store @session)
        deltas    (store/deltas st)
        after     (if since
                    (->> deltas (drop-while #(not= since (:id %))) rest vec)
                    (vec deltas))
        after-ids (into #{} (map :id) after)
        content   #{:add :replace :delete :rename :move}
        changes   (->> after
                       (filter (comp content :op))
                       (mapcat (fn [d]
                                 (for [fid (or (:form-ids d)
                                               (some-> (:form-id d) vector))]
                                   {:ns (:ns d) :fid fid :op (:op d)
                                    :ask (:prompt d)})))
                       (group-by (juxt :ns :fid))
                       (map (fn [[[nsx fid] es]]
                              {:ns nsx
                               :form (let [e (store/form-by-id st fid)]
                                       (or (:name e) fid))
                               :ops (vec (distinct (map :op es)))
                               :asks (vec (take 3 (distinct (map #(orient/snip % 140) (keep :ask es)))))}))
                       (filter (fn [row]
                                 (or (nil? contains)
                                     (some #(str/includes? (str %) (str contains))
                                           (cons (str (:form row)) (:asks row))))))
                       (sort-by (juxt (comp str :ns) (comp str :form)))
                       (take limit)
                       vec)
        ms        (->> (query-commits session)
                       (filter #(and (or (nil? since) (after-ids (:commit %)))
                                     (or (nil? contains)
                                         (str/includes? (str (:description %))
                                                        (str contains)))))
                       (take 20)
                       (mapv #(-> (select-keys % [:commit :description :at :status])
                                  (update :description orient/snip 110))))
        verify*   (->> deltas (filter #(= :verify (:op %))) last)
        dead      (->> after
                       (filter #(= :revert (:op %)))
                       (mapv (fn [d] (cond-> {:why (:why d)
                                              :forms (vec (:forms d))}
                                       (:at d) (assoc :at (history/human-time (:at d)))))))
        ;; the USER's verbatim asks, recorded on turn-begin. A handoff's first
        ;; question is "what was I asked to do?", and per-form :asks answer a
        ;; different one (what each write intended). Without these, handoffs
        ;; read the journal by hand — eval9 shelled out to sqlite3 on
        ;; .slopp/store.db to get exactly this.
        intents   (->> after
                       (filter #(= :turn-begin (:op %)))
                       (keep :intent)
                       distinct
                       (mapv #(orient/snip % 160)))]
    (orient/fit-report
     (cond-> {:milestones ms
             :changes changes
             :suite (when verify*
                      {:as-of (:id verify*)
                       :status (or (get-in verify* [:summary :status])
                                   (:status verify*) :unknown)})
             ;; the report is names + asks; the CODE lives one call away.
             ;; Say so here, or a handoff goes hunting in `git diff` (eval9
             ;; measured ~20k chars of it) for something slopp already has.
             :code "query_changes {from \"start\"} = every form's :was/:now across this lifetime (or from \"last-commit\"); format=text for line diffs"
             :verify (str "writes self-verify; test_run {all true} re-runs the "
                          "whole in-image suite (bare {} only returns guidance); "
                          "test_run {:external true} = the full external suite. "
                          "HANDOFF one-shots (humans/scripts, no session needed): "
                          "`slopp --call test_run '{\"external\":true}'` and "
                          "`slopp --call query_commits` — quote these in handoff "
                          "docs; no need to read skill files for the CLI forms")}
       (seq intents) (assoc :intents intents)
       (seq dead)    (assoc :dead-ends dead)))))

(defn module-platform!
  "Declare a module's target PLATFORM — the client wave's router (D-web-cljs):
  :jvm (Clojure on the JVM, the default), :cljc (portable — loads on the JVM AND
  compiles to JS), or :cljs (ClojureScript only — compiled to JS, never loaded
  into the JVM oracle). One :module-platform delta carrying its why (:prompt);
  last write per module wins. Namespace grain, like module_purity — the
  most-specific declaration governs. Read platforms via query_depends
  {modules true}."
  [session module platform & {:keys [prompt agent remove]}]
  (let [module   (str module)
        ;; every surface spells platforms WITH the colon, and MCP/JSON carries
        ;; a string, so accept both (nil defaults to :jvm) rather than minting
        ;; a bad keyword
        platform (keyword (str/replace (name (or platform :jvm)) #"^:" ""))
        modish   (re-matches #"[^.\s]+(\.[^.\s]+)*" module)]
    (cond
      (not modish)
      {:error (str "modules are the first TWO segments of a namespace"
                   " (\"logi.parcel\", not \"logi.parcel.impl\") — got "
                   (pr-str module))}

      ;; RETIRE, matching module_dep and module_purity. Declaring :jvm is not
      ;; the same statement as making no claim: an explicit :jvm on a
      ;; namespace nobody compiles is noise a register view has to carry.
      remove
      (if (contains? (:module-platforms (:store @session)) module)
        (let [st' (session/commit-appended!
                   session
                   #(first (store/record-module-platform % module nil :action :remove
                                                        :prompt prompt :agent agent))
                   [])]
          {:module module :action :removed :platforms (:module-platforms st')})
        {:error (str module " has no platform declaration — nothing to remove."
                     " Undeclared already means :jvm.")})

      (not (#{:jvm :cljc :cljs} platform))
      {:error (str "platform must be :jvm, :cljc, or :cljs — got "
                   (pr-str platform)
                   ". :jvm = Clojure on the JVM (default); :cljc = portable"
                   " (loads on the JVM AND compiles to JS); :cljs = ClojureScript"
                   " only (compiled to JS, never loaded into the oracle).")}

      :else
      (let [st' (session/commit-appended!
                 session
                 #(first (store/record-module-platform % module platform
                                                       :prompt prompt :agent agent))
                 [])]
        ;; This verb checks NOTHING about the code — it records a routing fact.
        ;; Whether a :cljc/:cljs namespace actually compiles for its declared
        ;; platform is compile_client's answer, and it can arrive much later.
        ;; An empty :verified is the honest shape (D-surface-honesty).
        {:module module :platform platform
         :platforms (:module-platforms st')
         :verified []
         :unverified [:compilation]
         :note (str "the platform is RECORDED, not verified: nothing here checks"
                    " that " module " compiles for :" (name platform)
                    ". compile_client is what proves it, and its warnings anchor"
                    " to the owning form.")}))))

(defn- shadow-warning
  "A warning when `ns-sym` names a namespace a CLASSPATH resource already owns
   — slopp's own code, or a dependency's — and the store does not yet define it.

   Why it warns and does not refuse: overriding a slopp namespace is a
   supported capability. `slopp.image.testmain` is how a store supplies its own
   trace runner, and `build!` materializes the store's version over slopp's. A
   refusal would break a documented extension point to prevent a naming
   mistake.

   Why it warns at all: `slopp.boot` loads store namespaces BEFORE slopp's own,
   so a shadowing namespace that does not define everything the real one does
   breaks the server at its next boot — and the store is then unopenable by the
   only tool that could remove it. That happened: a project defined
   `slopp.review.views` with two of its own views, and the next boot died on
   `No such var: views/module-graph`.

   The check is CLASSPATH ownership, not `find-ns`: a namespace an earlier
   store hot-loaded into this process is interned here too, and treating that
   as a collision false-flags a fresh store reusing a name."
  [store ns-sym]
  (when-not (contains? (:namespaces store) ns-sym)
    (let [base (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/"))]
      (when (some #(io/resource (str base %)) [".clj" ".cljc" ".cljs"])
        {:kind :shadows-classpath-ns
         :ns ns-sym
         :message (str ns-sym " SHADOWS a namespace already on the classpath "
                       "(slopp's own, or a dependency's). Your store's version "
                       "loads FIRST, so anything the real one defines and yours "
                       "does not will break at the next server boot — and a "
                       "store that cannot boot cannot be edited. Deliberate "
                       "overrides are fine (slopp.image.testmain is one); if "
                       "this was not deliberate, pick a name your project owns.")}))))

(defn create-ns!
  "F4: bring a brand-new namespace into being — two modes (mutually exclusive):
   - **scaffold** (`:requires`, clause strings like \"[clojure.string :as str]\"):
     build an empty `(ns …)` to grow form-by-form with red-first TDD. The default.
   - **content** (`:source`, the whole namespace text incl. its own `(ns …)`):
     land the entire namespace in one verified call — forward refs within the
     file resolve as a unit, like a real `.clj` load. For ported/reference/data
     code that isn't subject to red→green.
   `:platform` (:jvm/:cljc/:cljs) declares the namespace's target platform
   (module_platform grain = this namespace) BEFORE the source lands, so a
   client ns is BORN :cljs — its first js/* form defers to the cljs compiler
   instead of failing to load into the JVM oracle (the inherited-default
   footgun). A bad platform refuses the whole create.
   Delegates to `ingest!` (the shared engine); overwrite is refused there."
  [session ns-sym & {:keys [requires source agent platform prompt]}]
  (if (and source (seq requires))
    {:error (str ":source and :requires are mutually exclusive — put requires "
                 "inside the source's ns form")}
    ;; platform must be declared FIRST: ingest reads it to decide whether to
    ;; hot-load, so a :cljs source with js/* would fail to load otherwise
    (let [perr (when platform
                 (:error (module-platform! session (str ns-sym) platform
                                           :prompt (or prompt "platform declared at namespace creation")
                                           :agent agent)))
          ;; computed BEFORE the write, while the store still lacks the name
          shadow (shadow-warning (:store @session) ns-sym)]
      (cond
        perr {:error perr}

        :else
        (let [r (if source
                  (ingest! session ns-sym source :agent agent)
                  (ingest! session ns-sym
                           (str "(ns " ns-sym
                                (when (seq requires)
                                  (str "\n  (:require " (str/join "\n            " requires) ")"))
                                ")\n")
                           :agent agent))]
          (cond-> r
            (and shadow (not (:error r)))
            (update :warnings (fnil conj []) shadow)))))))

;; --- query.* (read) ---

;; --- verification (D1 tracing + D5 restart-as-diagnostic) ---

;; --- edit.* / runtime ---

;; ---------------------------------------------------------------------------
;; External dependencies (Tier 1) — the per-store manifest

;; --- Phase 4 m3: branches within one repo -------------------------------
(defn module-tier!
  "Declare a module's purity TIER — the functional-core gate's dial (D9):
  :pure (referentially transparent), :internal (may mutate IN-PROCESS state
  only — a memo, a registry), :external (IO — the periphery; unrestricted).
  Legacy spellings :reads/:effects are accepted and stored canonically as
  :internal/:external. One :module-tier delta carrying its why (:prompt); last
  write per module wins. Declaring :external (or never declaring) leaves a
  module ungated. Read tiers via query_depends {modules true}."
  [session module tier & {:keys [prompt agent remove]}]
  (let [module (str module)
        ;; every surface — this docstring, the tool description, query_depends'
        ;; output — spells tiers WITH the colon, so accept that spelling too
        ;; rather than turning ":pure" into ::pure and refusing it
        tier   (edit.modules/canonical-tier
                (keyword (str/replace (name (or tier "")) #"^:" "")))
        ;; namespace grain, not just module grain: a pure CORE routinely lives
        ;; one level below an effectful module (slopp.api holds seven fully-pure
        ;; namespaces). At module grain that core cannot be named, so nothing
        ;; enforces it — and the tier's whole job is to make agents MOVE code
        ;; into core/shell shape, which it cannot do if it cannot describe it.
        modish (re-matches #"[^.\s]+(\.[^.\s]+)*" module)]
    (cond
      (not modish)
      {:error (str "modules are the first TWO segments of a namespace"
                   " (\"logi.parcel\", not \"logi.parcel.impl\") — got "
                   (pr-str module))}

      ;; RETIRE, matching module_dep's `remove: true`. The store op has always
      ;; supported this (ns_rename needs it, so an orphaned declaration does
      ;; not outlive its namespace); nothing exposed it, so a mis-declared
      ;; tier could be overwritten but never withdrawn — and overwriting with
      ;; :external is not the same statement as making no claim at all.
      remove
      (if (contains? (:module-tiers (:store @session)) module)
        (let [st' (session/commit-appended!
                   session
                   #(first (store/record-module-tier % module nil :action :remove
                                                    :prompt prompt :agent agent))
                   [])]
          {:module module :action :removed :tiers (:module-tiers st')})
        {:error (str module " has no tier declaration — nothing to remove."
                     " Undeclared already means :external (ungated).")})

      (not (#{:pure :internal :external} tier))
      {:error (str "tier must be :pure, :internal, or :external — got "
                   (pr-str tier)
                   ". :pure = referentially transparent; :internal = may mutate"
                   " IN-PROCESS state (a memo, a registry) but touches nothing"
                   " outside; :external = IO (files, subprocesses, network, db)."
                   " (:reads and :effects are legacy spellings of :internal and"
                   " :external.)")}

      ;; a tier is an ASSERTION ABOUT THE CODE, so check it against the
      ;; code. Gating only future writes let :pure land on a module full of
      ;; effects — a marker that lies, which is worse than no marker.
      :else
      (let [bad (edit.modules/tier-violations (:store @session) module tier)]
        (if (seq bad)
          {:error (str "cannot declare " module " :" (name tier) " — "
                       (count bad) " existing form(s) already exceed it: "
                       (str/join ", " (map (comp str :form) (take 5 bad)))
                       (when (> (count bad) 5)
                         (str " (+" (- (count bad) 5) " more)"))
                       ". Move the effects to a periphery namespace, or declare"
                       " a looser tier. The first: " (:why (first bad)))
           :violations (mapv :form bad)}
          (let [st' (session/commit-appended!
                     session
                     #(first (store/record-module-tier % module tier
                                                       :prompt prompt :agent agent))
                     [])]
            ;; D-surface-honesty at declaration grain. This call checked the FORMS
            ;; in the governed namespaces and nothing else. Layering — does this
            ;; namespace REQUIRE a looser tier? — is deliberately not checked
            ;; here (its verdict changes as legitimate work continues, which is
            ;; the D-rule-grain test for a check that does not belong at write
            ;; grain). The omission is fine; being silent about it is not.
            {:module module :tier tier
             :tiers (:module-tiers st')
             :verified (if (= :external tier) [] [:forms])
             :unverified [:layering]
             :note (if (= :external tier)
                     (str ":external asserts nothing about the code, so nothing"
                          " was checked. Layering — whether these namespaces"
                          " require a LOOSER tier — is a whole-graph property"
                          " and is reported by full_check.")
                     (str "the forms already in " module " were checked against :"
                          (name tier) ". Layering — whether they require a LOOSER"
                          " tier — is a whole-graph property reported by"
                          " full_check, not at write grain."))}))))))

(defn module-dep!
  "Declare or retract ONE module dependency edge — the semantic verb behind
  the module manifest (there is no file to edit): each call is one
  :module-edge delta carrying its why (:prompt). Adds are refused when the
  resulting graph would contain a cycle; the response carries the module's
  folded dep set and, when any exists, the store's remaining :violations
  debt.

  The cycle question is asked of PRODUCTION edges — the same graph
  query_depends draws its layers from. A `-test` namespace folds into its
  subject's module, so a fixture require manufactures an edge no production
  namespace has, and judging against those refused architecture that is
  genuinely acyclic while the architecture view showed a clean DAG."
  [session from to & {:keys [remove prompt agent]}]
  (let [manifest (or (edit.modules/modules-manifest (:store @session)) {})
        from     (str from)
        to       (str to)
        modish   #(re-matches #"[^.\s]+(\.[^.\s]+)?" %)
        action   (if remove :remove :add)]
    (cond
      (not (and (modish from) (modish to)))
      {:error (str "modules are the first TWO segments of a namespace"
                   " (\"logi.parcel\", not \"logi.parcel.impl\") — got "
                   (pr-str [from to]))}

      (= from to)
      {:error "a module never declares itself"}

      (and remove (not (contains? (get manifest from #{}) to)))
      {:error (str from " does not declare " to " — nothing to remove")}

      (and (not remove) (contains? (get manifest from #{}) to))
      {:from from :to to :action action :already-declared true
       :deps (vec (sort (get manifest from)))}

      :else
      (if-let [back (and (not remove)
                         ;; PRODUCTION edges — the same graph query_depends draws
                         ;; its layers from. A `-test` namespace folds into its
                         ;; subject's module, so a fixture require manufactures an
                         ;; edge no production namespace has; judging against those
                         ;; refused architecture that is genuinely acyclic while the
                         ;; architecture view showed a clean DAG.
                         (store/module-path
                          (modules/production-manifest
                           (:store @session)
                           (modules/module-usage-rows (:store @session)))
                          to from))]
        {:error (str "that edge CLOSES a dependency cycle: "
                     (clojure.string/join " → " (conj back to))
                     " — point the dependency one way (usually by extracting"
                     " the shared piece into a module both sides may depend on)")}
        (let [st'  (session/commit-appended!
                    session
                    #(first (store/record-module-edge % from to action
                                                      :prompt prompt
                                                      :agent agent))
                    [])
              debt (modules/module-debt st')
              ;; what this call checked, and what it did not: the cycle
              ;; question is real and was asked, but whether anything USES the
              ;; edge is a different question with a different owner.
              axes (str "cycles were judged over PRODUCTION edges. Whether"
                        " anything USES this edge is not checked here —"
                        " query_depends {modules true} reports :unused-edges.")]
          (cond-> {:from from :to to :action action
                   :verified [:cycles] :unverified [:usage] :note axes
                   :deps (vec (sort (get-in st' [:modules from])))}
            debt
            (assoc :violations debt
                   :note (str "existing debt under this manifest — writes"
                              " touching these forms stay blocked until the"
                              " edge is declared or the call restructured. "
                              axes))))))))

(defn rename-sweep!
  "Q14: the docs-team rename as ONE intent — every namespace, var, keyword,
  and prose occurrence of `from` (as a whole word/segment, boundary-guarded)
  becomes `to`, store-wide: matching namespaces rename first (requires
  rewrite along), then every still-matching form rewrites in ONE atomic
  group with ONE verification. The textual segment match is deliberate: a
  sweep means 'everything named that', locals and prose included; the
  dialect/isolation gates and the test run judge the result. eval9's
  measured loss (13.6k tokens / 37 calls / one restart for zone->region
  across 41 nses vs sed's one pass) is this op's demand signal."
  [session from to & {:keys [prompt agent dry-run]}]
  (let [from (str from)
        to   (str to)
        pat  (re-pattern (str "(?<![A-Za-z])"
                              (java.util.regex.Pattern/quote from)
                              "(?![A-Za-z])"))
        why  (or prompt (str "sweep " from " -> " to))]
    (cond
      (or (str/blank? from) (str/blank? to))
      {:error "rename_sweep needs :from and :to"}

      (= from to)
      {:error ":from and :to are identical"}

      :else
      (let [nses (filterv #(re-find pat (str %))
                          (keys (:namespaces (:store @session))))
            ;; namespace renames WRITE, so a preview must not run them — it
                     ;; reports what they would be instead
                     nsr  (if dry-run
                            {:renamed-namespaces
                             (mapv (fn [nsx]
                                     [nsx (symbol (str/replace (str nsx) pat to))])
                                   (sort nses))}
                            (reduce (fn [acc nsx]
                                      (if (:error acc)
                                        acc
                                        (let [new-ns (str/replace (str nsx) pat to)
                                              r (ns-rename! session (str nsx) new-ns
                                                            :prompt why :agent agent)]
                                          (if (:error r)
                                            {:error (str "renaming " nsx ": " (:error r))}
                                            (update acc :renamed-namespaces conj
                                                    [nsx (symbol new-ns)])))))
                                    {:renamed-namespaces []}
                                    (sort nses)))]
        (if (:error nsr)
          nsr
          (let [st    (:store @session)
                ;; renaming a KEYWORD to a qualified one has a structural half the text
                ;; pass cannot see: `{:keys [x]}` names its key as a SYMBOL, so a
                ;; literal-only sweep leaves it reading the old unqualified key —
                ;; compiles, gates clean, reads nil at runtime.
                kw?     (and (str/starts-with? from ":")
                             (str/starts-with? to ":"))
                kname   (when kw? (last (str/split (subs from 1) #"/")))
                to-ns   (when kw?
                          (let [b (subs to 1)]
                            (when (str/includes? b "/")
                              (first (str/split b #"/")))))
                rewrite (fn [src]
                          (let [s (str/replace src pat to)]
                            (if (and to-ns (str/includes? s ":keys")
                                     (str/includes? s kname))
                              (refactor/requalify-keys s kname to-ns)
                              s)))
                ;; select on the REWRITE, not the pattern: a form whose only
                ;; occurrence is a :keys destructuring holds no keyword literal
                steps (vec (for [nsx (store/ns-dependency-order st)
                                 e   (store/forms st nsx)
                                 :when (:name e)
                                 :let [src  (n/string (:node e))
                                       src' (rewrite src)]
                                 :when (not= src src')]
                             {:action :replace :ns nsx :name (:name e)
                              :source src'}))]
            (cond
              (and (empty? steps) (empty? (:renamed-namespaces nsr)))
              {:error (str "nothing named " from
                           " in the store — query_search shows what exists")}

              ;; PREVIEW: a sweep is store-wide and rewrites string literals as
              ;; well as code. Sweeping prose is intended; rewriting a test
              ;; FIXTURE is not, and does it silently. Separate the two so the
              ;; string hits get an eye before anything lands.
              dry-run
              (let [classify (fn [{:keys [ns name]}]
                               (let [src (n/string (:node (store/form-named
                                                           (:store @session) ns name)))]
                                 {:form (symbol (str ns) (str name))
                                  :strings? (refactor/match-in-strings? src pat)}))
                    rows     (mapv classify steps)]
                (merge nsr
                       {:dry-run true
                        :forms (count steps)
                        :in-code (filterv (complement :strings?) rows)
                        :in-strings (filterv :strings? rows)}
                       (when (some :strings? rows)
                         {:note (str "string-literal hits REVIEW FIRST: a sweep"
                                     " rewrites keyword text inside strings, so"
                                     " a test fixture can be left"
                                     " self-inconsistent")})))

              (empty? steps)
              (assoc nsr :forms 0)

              :else
              (let [r (edit-group! session steps :prompt why :agent agent)]
                (if (:error r)
                  r
                  (merge r (assoc nsr :forms (count steps))))))))))))

(defn requalify-boundary-keys!
  "Namespace a module-external fn's OPTION KEYS in one verified intent: its
  arglist destructuring AND the map literals its callers pass, together.

  This exists because `require-namespaced-keys` was otherwise UNDISCHARGEABLE.
  Its last violation, `api/open!`, has 60 call sites; a store-wide
  `rename_sweep` is unsafe whenever the key means more than one thing (`:dir`
  names three different things here), and 60 hand edits is worse. A rule
  nobody can discharge trains people to ignore the channel — the rule's own
  docstring says so.

  `to-ns` defaults to the target's namespace. The keys are DERIVED — every
  unqualified key its first arg destructures — so the caller cannot namespace
  half a contract and leave the rest reading nil.

  A call site counts only when its head RESOLVES to the target: the defining
  ns's own name, the caller's alias for it, or the fully-qualified symbol.
  Matching by bare name instead silently included `slopp.db/open!` alongside
  `slopp.api.external/open!` — caught by a dry-run reporting 62 forms and 24 unknowns
  where the caller graph said 60 and 4.

  Reports `:unknown-shape`: callers passing a non-literal (`(open! opts)`),
  which no syntactic reader can rewrite. Those are left untouched and NAMED,
  never silently skipped — the count is the part you still owe by hand. Call
  sites OUTSIDE the store (the kernel's own .clj files) are invisible to this
  and to every store-based analysis; check them yourself.
  `:dry-run true` previews without writing."
  [session ns-sym nm & {:keys [to-ns prompt agent dry-run]}]
  (let [st     (:store @session)
        ns-sym (symbol (str ns-sym))
        nm     (symbol (str nm))
        form   (store/named-sexpr st ns-sym nm)]
    (if-not form
      (edit/missing-form-error st ns-sym nm)
      (let [tons (str (or to-ns ns-sym))
            ks   (vec (sort (remove namespace (:destructured (shape/read-keys form)))))]
        (if (empty? ks)
          {:error (str ns-sym "/" nm " destructures no unqualified keys —"
                       " nothing to requalify")}
          (let [why     (or prompt (str "namespace " ns-sym "/" nm "'s option keys"
                                        " under " tons))
                heads   (fn [nsx]
                          (cond-> #{(str ns-sym "/" nm)}
                            (= nsx ns-sym) (conj (str nm))
                            true (into (for [[alias lib] (edit/require-aliases st nsx)
                                             :when (= (symbol (str lib)) ns-sym)]
                                         (str alias "/" nm)))))
                rewrite (fn [src nsx target?]
                          (reduce (fn [s k]
                                    (let [s' (refactor/requalify-call-args
                                              s (heads nsx) (name k) tons)]
                                      (if target?
                                        (refactor/requalify-keys s' (name k) tons)
                                        s')))
                                  src ks))
                steps   (vec (for [nsx (store/ns-dependency-order st)
                                   e   (store/forms st nsx)
                                   :when (:name e)
                                   :let [src  (n/string (:node e))
                                         tgt? (and (= nsx ns-sym) (= (:name e) nm))
                                         src' (rewrite src nsx tgt?)]
                                   :when (not= src src')]
                               {:action :replace :ns nsx :name (:name e) :source src'}))
                opaque? (fn [nsx e]
                          (let [hs (heads nsx)]
                            (some (fn [node]
                                    (and (seq? node)
                                         (symbol? (first node))
                                         (contains? hs (str (first node)))
                                         (next node)
                                         (not (map? (second node)))))
                                  (tree-seq coll? seq (store/form-sexpr (:node e))))))
                unknown (vec (sort (for [nsx (keys (:namespaces st))
                                         e   (store/forms st nsx)
                                         :when (and (:name e) (opaque? nsx e))]
                                     (symbol (str nsx) (str (:name e))))))
                report  (cond-> {:keys ks :to-ns tons :forms (count steps)
                                 ;; a preview that only COUNTS is not a preview: you
                                 ;; cannot check 62 rewrites against a caller graph
                                 ;; you are not shown. The bare-name bug looked
                                 ;; exactly like a correct run until the numbers
                                 ;; were compared.
                                 :in-code (vec (sort (map #(symbol (str (:ns %))
                                                                   (str (:name %)))
                                                          steps)))}
                          (seq unknown)
                          (assoc :unknown-shape unknown
                                 :note (str (count unknown) " call site(s) pass a"
                                            " non-literal map — no syntactic reader"
                                            " can see through a binding, so those"
                                            " are UNTOUCHED and yours to check")))]
            (cond
              (empty? steps) {:error (str "no call site or arglist to rewrite for "
                                          ns-sym "/" nm)}
              dry-run        (assoc report :dry-run true)
              :else          (let [r (edit-group! session steps :prompt why :agent agent)]
                               (if (:error r) r (merge r report))))))))))

(defn delete-ns!
  "Remove namespace `ns-sym` from the store — the retirement half creation
  never had (a mistaken scaffold used to ride every projection and build
  forever). Refuses while the namespace holds any form beyond its ns decl
  (delete those first — each deletion individually verified) or while any
  other namespace still requires it (ns_remove_require first). On success:
  one :ns-delete delta (id returned), element rows cleared by the persist,
  and the image drops the namespace so a stale require fails fast."
  [session ns-sym & {:keys [prompt agent]}]
  (let [st (:store @session)]
    (cond
      (nil? (get-in st [:namespaces ns-sym]))
      {:error (str "no namespace " ns-sym)}

      ;; STRUCTURAL emptiness (review S-F2): a def whose name equals the ns
      ;; symbol is not the ns decl and must still block deletion
      (seq (store/body-forms st ns-sym))
      (let [held (keep :name (store/body-forms st ns-sym))]
        {:error (str ns-sym " still holds " (count (store/body-forms st ns-sym))
                     " form(s)"
                     (when (seq held) (str " (" (str/join ", " held) ")"))
                     " — delete them first (edit_delete_form); ns_delete"
                     " removes only an empty namespace")})

      :else
      (let [requirers (vec (sort (for [other (keys (:namespaces st))
                                       :when (and (not= other ns-sym)
                                                  (some #{ns-sym}
                                                        (store/ns-requires st other)))]
                                   other)))]
        (if (seq requirers)
          {:error (str ns-sym " is still required by " (str/join ", " requirers)
                       " — ns_remove_require them first")}
          (let [st'  (session/commit-appended!
                      session
                      #(first (store/record-ns-delete % ns-sym :prompt prompt :agent agent))
                      [ns-sym])
                did  (:id (last (store/deltas st')))
                ;; A tier or platform describes a NAME — ns-rename! carries them
                ;; across for exactly this reason. Left behind by a DELETE, one
                ;; names a namespace that no longer exists and query_depends
                ;; lists it as though it governed something. The exception is a
                ;; declaration a deeper namespace still lives under: that one
                ;; governs live code by prefix, and retiring it would ungate it.
                path    (str ns-sym)
                governs (some #(str/starts-with? (str %) (str path "."))
                              (keys (:namespaces st')))
                orphans (when-not governs
                          (vec (for [[reg record] [[:module-tiers store/record-module-tier]
                                                   [:module-platforms store/record-module-platform]]
                                     :when (contains? (get st' reg) path)]
                                 [reg record])))]
            (when (seq orphans)
              (session/commit-appended!
               session
               (fn [base]
                 (reduce (fn [s [_ record]]
                           (first (record s path nil :action :remove
                                          :prompt (str "declaration retired with namespace " ns-sym)
                                          :agent agent)))
                         base orphans))
               []))
            (repl/eval! (:image @session)
                        (format "(remove-ns '%s)" ns-sym))
            (cond-> {:deleted (str ns-sym) :delta did}
              (seq orphans) (assoc :retired (mapv first orphans)))))))))

(defn module-extract!
  "Pull `ns-syms` (each with its subtree and `-test` siblings) under
  `to-prefix` — the module-grain regroup, as ONE intent.

  Order is the design, not a detail. A namespace that moves from two segments
  to three becomes package-private, so every caller outside the new parent
  becomes a module violation the instant the rename lands. The vars the plan
  named are therefore hoisted FIRST; only then do the renames run, and the
  edges are declared LAST from what the store actually references — by then
  `ns-rename!` has already re-keyed the manifest for whole modules that moved,
  so replaying the plan's pre-rename edge list would re-declare them.

  `dry-run` returns the plan and writes nothing — what can be extracted, what
  must be exported and who forces it, which edges appear. Refuses when the
  regroup would leave a production module cycle."
  [session ns-syms to-prefix & {:keys [prompt agent dry-run]}]
  (let [st   (:store @session)
        plan (refactor/module-extract-plan st ns-syms to-prefix)]
    (cond
      (:error plan) (select-keys plan [:error])

      dry-run {:plan plan}

      (empty? (:renames plan))
      {:error (str "no namespace matches " (pr-str ns-syms)
                   " — name the namespaces to pull under " to-prefix
                   " (subtrees and -test siblings follow on their own)")}

      :else
      (let [why (or prompt (str "extract " (str/join ", " ns-syms)
                                " under " to-prefix))
            inv (into {} (map (fn [[k v]] [v k])) (:renames plan))
            cur (mapv (fn [e] {:ns (get inv (:ns e) (:ns e)) :name (:name e)})
                      (:exports plan))
            cs  (refactor/export-changeset st cur)]
        ;; 1. hoist first — never leave an intermediate store the gate refuses
        (when (seq cs)
          (let [[st1 _] (store/apply-changeset st :module-extract
                                               (symbol (str to-prefix)) cs
                                               :prompt why :agent agent)]
            (session/try-commit! session st st1
                                 (vec (distinct (map :ns cur))))))
        ;; 2. the renames, each carrying its own manifest follow + verification
        ;; every rename DEFERS its verification to this transaction. Mid-batch the
        ;; store has namespaces renamed and callers not yet rewritten, so a
        ;; verification between steps is meaningless as well as expensive — the
        ;; only bar a batch can meet is that its END STATE is green. A
        ;; three-namespace extraction used to run past 465s paying N of them.
        (let [done (reduce (fn [acc [o v]]
                             (let [r (ns-rename! session o v
                                                 :prompt why :agent agent
                                                 :defer-verify true)]
                               (if (:error r)
                                 (reduced {:error (str "renaming " o " → " v ": "
                                                       (:error r))
                                           :landed (:pairs acc)})
                                 (cond-> (-> acc
                                             (update :pairs conj [o v])
                                             (update :nses into (:verify-nses r)))
                                   (:left-behind r)
                                   (update :left-behind (fnil conj [])
                                           (select-keys r [:renamed :left-behind :note]))))))
                           {:pairs [] :nses #{}}
                           (sort-by first (:renames plan)))]
          (if (:error done)
            done
            ;; 3. the edges reality now requires, derived from the moved store
            (let [st'      (:store @session)
                  manifest (or (edit.modules/modules-manifest st') {})
                  missing  (vec (sort (for [[m deps] (edit.modules/derive-module-edges st')
                                            d deps
                                            :when (not (contains? (get manifest m #{}) d))]
                                        [m d])))]
              (when (seq missing)
                (session/commit-appended!
                 session
                 (fn [base]
                   (reduce (fn [s [a b]]
                             (first (store/record-module-edge s a b :add
                                                              :prompt why :agent agent)))
                           base missing))
                 []))
              ;; 4. ONE verification, strictly after the whole set has landed
              ;; AND after the edges are declared — verifying earlier would
              ;; judge a store the gate itself would refuse.
              (let [vn      (vec (sort (remove (set (map first (:pairs done)))
                                               (:nses done))))
                    summary (session/run-verification! session vn nil)]
                (session/commit-appended!
                 session #(store/record-verification % vn summary) [])
                (cond-> {:extracted {:to (symbol (str to-prefix))
                                     :renames (into (sorted-map) (:renames plan))
                                     :exported (count cs)
                                     :edges-declared missing}
                         :test summary}
                  (seq (:left-behind done))
                  (assoc :left-behind (:left-behind done)
                         :note (str (count (:left-behind done)) " of the renamed"
                                    " namespaces left occurrences of their old name"
                                    " behind — strings and -test siblings the symbol"
                                    " rewrite cannot reach. Judge each.")))))))))))

(defn js-dep!
  "Vendor and declare a JavaScript library `js-name` (a string like
  \"roughjs\"), or retract it with `:remove`.

  The third dependency world. Nothing is resolved — there is no npm client
  here — so `:source` names the bytes on disk and this does the rest in one
  act: writes them to the content-addressed cache, records a `:download`
  recipe built from the npm coordinate, and declares the library.

  It is one call rather than two (`file_put` then declare) because a library
  is DERIVED, and the recipe is what makes it recoverable. That also deletes
  a refusal: you can no longer declare a library whose bytes were never
  vendored, because declaring IS vendoring. This mirrors importmap-rails'
  `pin --download`.

  `spec` wants `{:version :format :global :file}` plus provenance — `:npm`
  (`\"roughjs@4.6.6\"`), `:npm-path` (which file inside the package),
  `:integrity` (the registry's own hash) and `:license`. Anchor to the
  REGISTRY, not to a CDN url: npm versions are immutable, so the coordinate
  is re-fetchable and verifiable, where a delivery url only records how the
  bytes arrived once.

  `:format` is `:iife`/`:umd` (concatenated into the bundle via `deps.cljs`
  `:foreign-libs`) or `:esm` (loaded by the page). A typo must fail here
  rather than as a silent no-op at compile time.

  Returns {:declared name :sha ...} | {:error}."
  [session js-name spec & {:keys [agent prompt remove source]}]
  (cond
    (not (string? js-name))
    {:error "js dependency name must be a string like \"roughjs\""}

    remove
    (do (session/commit-appended!
         session
         #(first (store/record-js-dep % js-name nil :agent agent :prompt prompt
                                      :remove true))
         [])
        {:retracted js-name})

    (not (contains? #{:iife :umd :esm} (:format spec)))
    {:error (str "js dependency format must be :iife, :umd or :esm — got "
                 (pr-str (:format spec))
                 ". :iife/:umd concatenate into the bundle; :esm is loaded by"
                 " the page and shimmed to a global.")}

    (and (not= :esm (:format spec)) (not (seq (str (:global spec)))))
    {:error (str ":iife/:umd libraries must name the :global they set"
                 " (roughjs sets \"rough\") — that is what :global-exports"
                 " maps a require onto.")}

    (not (and source (.exists (java.io.File. (str source)))))
    {:error (str "js_dep needs :source — a path to the library's bytes on disk."
                 " Declaring IS vendoring: there is no npm client here, so the"
                 " bytes have to come from somewhere you already fetched them.")}

    (str/blank? (str (:file spec)))
    {:error "js dependency needs a :file — where the library sits in the project tree"}

    :else
    (let [bs     (java.nio.file.Files/readAllBytes
                  (.toPath (java.io.File. (str source))))
          recipe (cond-> {:kind :download}
                   (:npm spec)       (assoc :npm (:npm spec))
                   (:npm-path spec)  (assoc :npm-path (:npm-path spec))
                   (:integrity spec) (assoc :integrity (:integrity spec))
                   (:source-url spec) (assoc :source-url (:source-url spec)))
          entry  (artifacts/put! (:dir @session) bs recipe
                                 :content-type "application/javascript")
          spec'  (assoc spec :sha (:sha entry))]
      (let [prior (get-in @session [:store :artifacts (str (:file spec)) :sha])]
        (session/commit-appended!
         session
         (fn [s] (-> s
                     (as-> s' (first (store/record-artifact s' (:file spec) entry
                                                            :agent agent :prompt prompt)))
                     (as-> s' (first (store/record-js-dep s' js-name spec'
                                                          :agent agent :prompt prompt)))))
         [])
        ;; re-vendoring at a new version strands the old bytes exactly as a
        ;; recompile does
        (artifacts/prune-superseded! (:dir @session) (:store @session) prior))
      {:declared js-name :version (:version spec) :format (:format spec)
       :file (:file spec) :sha (:sha entry) :bytes (alength bs)})))
