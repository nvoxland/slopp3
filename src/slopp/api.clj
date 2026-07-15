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
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.render :as render]
            [slopp.index :as index]
            [slopp.repl :as repl]
            [slopp.image :as image]
            [slopp.edit :as edit]
            [slopp.refactor :as refactor]
            [slopp.normalize :as normalize]
            [slopp.build :as build]
            [slopp.deps :as deps]
            [slopp.db :as db] [clojure.java.shell :as sh] [clojure.edn :as edn]))

(declare run-verification! forms-changed-since query-outline
         hot-load-all! fresh-image! reap-idle-images!
         content-ops delta-fids episode-boundary episode-span
         query-changes edit-group! fix-declares! status-at status-after
         human-time render-form-history-text)

(defn- start-spare!
  "Kick off a background-warming spare image (D5 warm spare) if enabled. The
  spare is launched BARE (deps unknown until a line adopts it); its manifest
  is reconciled at adoption via `image-with-deps!`."
  [session]
  (when (:warm-spare? @session)
    (swap! session assoc :spare (future (repl/start!)))))

(defn- image-with-deps!
  "A ready owned image carrying `deps` (lib→coord) on its classpath: adopt the
  bare `spare` and hot-`add-libs` the manifest into it, falling back to a fresh
  launch if the spare can't reconcile; or launch fresh with `:deps` when there
  is no spare. The caller owns spare bookkeeping (nil-ing + rewarming)."
  [spare deps]
  (if spare
    (let [img @spare]
      (if (and (seq deps) (:err (repl/add-libs! img deps)))
        (do (repl/stop! img) (repl/start! {:deps deps}))
        img))
    (repl/start! {:deps deps})))

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

^:reads
(defn session-identity
  "The identity a fresh session starts with: explicit SLOPP_AGENT env (how
  orchestrators name agents and CLI scripts keep cross-invocation
  continuity), else a generated unique id. The plugin's prompt hook can
  supersede the generated id with the harness session id (adopt-identity!
  in slopp.mcp) so every delta of one Claude session shares a key — and
  two concurrent sessions on one store never merge episodes."
  []
  (or (not-empty (System/getenv "SLOPP_AGENT"))
      (str "s-" (subs (str (java.util.UUID/randomUUID)) 0 8))))
(defn- load-trace
  "The persisted trace map, pruned to tests/forms that still exist in `store`
  (names move between sessions — including renames that never re-persisted —
  so stale entries drop out and narrowing stays conservative)."
  [conn store]
  (when conn
    (let [raw   (try (some-> (db/get-meta conn "trace-map") edn/read-string)
                     (catch Exception _ nil))
          live? (fn [qsym]
                  (let [n (some-> (namespace qsym) symbol)]
                    (boolean (and n (store/form-named store n (symbol (name qsym)))))))]
      (into {}
            (keep (fn [[t forms]]
                    (when (live? t)
                      (let [fs (into #{} (filter live?) forms)]
                        (when (seq fs) [t fs])))))
            raw))))
(declare adopt-modules!)
(defn- stub-missing-test-vars!
  "The GENERIC red-first seam (command-agnostic — every write path that
  compiles through the image inherits it, including future ops): when a
  -test namespace fails to load, intern a throwing stub in `image` for
  every store var the CANDIDATE store shows it referencing but not
  defining — aliased/qualified calls via kondo rows, :refer'd names via
  the ns form (stubs precede the require, so the refer check passes) —
  then the caller retries the load and the spec lands as an honest RED
  naming the stub. Never touches the store; the real implementation
  redefines the var. Returns the stubbed qsyms (nil when none — the
  failure wasn't red-first)."
  [image candidate ns-syms]
  (let [nses    (set (keys (:namespaces candidate)))
        tests   (filter #(str/ends-with? (str %) "-test") ns-syms)
        ns-form (fn [t]
                  (some #(let [s (try (n/sexpr (:node %)) (catch Exception _ nil))]
                           (when (and (seq? s) (= 'ns (first s))) s))
                        (store/forms candidate t)))
        missing (vec (distinct
                      (concat
                       (for [t tests
                             u (:var-usages (index/analyze (render/render-ns candidate t)))
                             :when (and (contains? nses (:to u)) (:name u)
                                        (not (store/form-named candidate (:to u) (:name u))))]
                         (symbol (str (:to u)) (str (:name u))))
                       (for [t tests
                             :let [form (ns-form t)]
                             clause (when form
                                      (mapcat rest
                                              (filter #(and (seq? %) (= :require (first %)))
                                                      form)))
                             :when (and (vector? clause)
                                        (contains? nses (first clause)))
                             [k v] (partition 2 (rest clause))
                             :when (and (= :refer k) (vector? v))
                             sym v
                             :when (not (store/form-named candidate (first clause) sym))]
                         (symbol (str (first clause)) (str sym))))))]
    (doseq [q missing]
      (repl/eval! image
                  (format "(intern '%s '%s (fn [& _] (throw (ex-info \"red-first stub: %s is speced but not implemented\" {:red-first '%s}))))"
                          (namespace q) (name q) q q)))
    (when (seq missing) missing)))
(defn open!
  "Start a session: the owned image + the store — loaded from `<dir>/.slopp/`
  when `:dir` is given and it has history, empty otherwise. `:warm-spare? true`
  keeps a spare image warming in the background so restarts are near-instant.
  `:agent-id` (default: session-identity) keys every delta/turn/episode this
  session writes."
  ([] (open! {}))
  ([{:keys [dir warm-spare? branch-image-ttl-ms agent-id]}]
   (let [conn    (when dir (db/open! dir))
         store   (or (some-> conn db/load-store) (store/empty-store))
         image   (repl/start! {:deps (:deps store)})
         ttl     (or branch-image-ttl-ms 600000)
         session (atom {:store store :image image :db conn
                        :data-version (some-> conn db/data-version)
                        :dir dir :branch "main" :lines {}
                        :test-map (or (load-trace conn store) {})
                        :agent-id (or agent-id (session-identity))
                        :env-agent? (boolean (not-empty (System/getenv "SLOPP_AGENT")))
                        :branch-image-ttl-ms ttl
                        :warm-spare? (boolean warm-spare?)})]
     (start-spare! session)
     ;; m4: parked branch images retire after sitting idle for the TTL
     (let [t      (java.util.Timer. "slopp-branch-reaper" true)
           period (long (max 1000 (quot ttl 3)))]
       (.schedule t
                  (proxy [java.util.TimerTask] []
                    (run [] (try (reap-idle-images! session)
                                 (catch Throwable _))))
                  period period)
       (swap! session assoc :reaper t))
     (doseq [ns-sym (store/ns-dependency-order store)]     ; X3: deps first
       (when-let [err (image/load-ns! image store ns-sym)]
         ;; a store carrying red-first specs still opens — stub and retry
         (when-not (and (stub-missing-test-vars! image store [ns-sym])
                        (nil? (image/load-ns! image store ns-sym)))
           (throw (ex-info (str "image load failed for " ns-sym ": " err) {})))))
     ;; module adoption: a populated store from a pre-module db (:modules
     ;; nil) gets its manifest derived from reality, once — fresh stores
     ;; are born with {} and enforcement already on
     (when (and conn (seq (:namespaces store))
                (or (nil? (:modules store))
                    ;; an EMPTY manifest on a populated store whose journal has
                    ;; never seen a :module-edge delta = pre-adoption (the
                    ;; journal is the record of truth; a user who retracted
                    ;; edges has retraction deltas)
                    (and (empty? (:modules store))
                         (not-any? #(= :module-edge (:op %)) (:deltas store)))))
       (adopt-modules! session :agent (or agent-id "slopp")))
     session)))

(defn close! [session]
  (repl/stop! (:image @session))
  (when-let [spare (:spare @session)]
    (repl/stop! @spare))                          ; reap even if still booting
  (when-let [^java.sql.Connection conn (:db @session)]
    (.close conn))
  (doseq [[_ line] (:lines @session)]
    (when-let [img (:image line)] (repl/stop! img))
    (when-let [^java.sql.Connection c (:conn line)]
      (.close c)))
  (when-let [^java.util.Timer t (:reaper @session)] (.cancel t))
  nil)

(def ^:dynamic *pre-commit-hook*
  "Test seam (item 4): invoked between an op's hot-load and its commit CAS to
  simulate a concurrent competitor deterministically. Never set in production."
  nil)

(defn- try-commit!
  "Commit base→st' — JOURNAL-FIRST for durable sessions (m5a storage
  inversion): the new deltas + the full element rows of `nses` land in ONE
  conditional db transaction (iff the journal head still equals base's
  head), then the cache follows; the cache is only ever behind the journal,
  never ahead. Ephemeral sessions commit to the cache alone (identity CAS).
  True iff committed; false = the head/cache moved — caller refreshes and
  rebases, or surfaces contention."
  [session base st' nses]
  (if-let [conn (:db @session)]
    (if (db/append! conn st'
                    (drop (count (store/deltas base)) (store/deltas st'))
                    (vec nses)
                    (:id (last (store/deltas base))))
      (do (swap! session
                 (fn [s]
                   (if (< (count (store/deltas (:store s)))
                          (count (store/deltas st')))
                     (assoc s :store st')
                     s)))
          true)
      false)
    (let [[old _] (swap-vals! session
                              (fn [s]
                                (if (identical? (:store s) base)
                                  (assoc s :store st')
                                  s)))]
      (identical? (:store old) base))))

(defn- refresh-cache!
  "Advance the cached store from the journal (the record of truth in a
  durable session): INCREMENTALLY when every foreign delta in the suffix
  replays (the common case — no full re-parse), falling back to a full
  load-store otherwise (:ingest/:move/unknown ops). Advance-only — the
  cache can never regress."
  [session]
  (when-let [conn (:db @session)]
    (let [local  (:store @session)
          suffix (db/deltas-after conn (count (store/deltas local)))
          incr   (when (seq suffix)
                   (reduce (fn [st d]
                             (if-let [st' (store/replay-delta st d)]
                               st'
                               (reduced nil)))
                           local suffix))
          fresh  (or incr (when (seq suffix) (db/load-store conn)))]
      (when fresh
        (swap! session
               (fn [s]
                 (if (> (count (store/deltas fresh))
                        (count (store/deltas (:store s))))
                   (assoc s :store fresh)
                   s)))))))

(defn- persist-trace!
  "Q3: the trace map survives the session — written to store meta so the NEXT
  session (or a CLI one-shot) starts with narrowing warm instead of
  {:ran 0 :affected :all}. Last writer wins; load-trace prunes stale names."
  [session]
  (when-let [conn (:db @session)]
    (db/set-meta! conn "trace-map" (pr-str (:test-map @session)))))
(defn sync-with-journal!
  "m5b: absorb commits made by OTHER servers sharing this store dir. Cheap
  when nothing changed (one PRAGMA read). On foreign commits: refresh the
  cached store from the journal, reload every namespace whose source changed
  into the LOCAL image, and drop trace entries touching the changed
  namespaces (conservative — narrowing rebuilds). Returns {:synced n-nses}
  or nil when already current. The MCP dispatch calls this before every
  tool, so servers converge continuously."
  [session]
  (when-let [conn (:db @session)]
    (let [v (db/data-version conn)]
      (when (not= v (:data-version @session))
        (let [old (:store @session)]
          (refresh-cache! session)
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
                (do (persist-trace! session)
                    {:synced (count changed)})))))))))

(defn- commit-appended!
  "Commit a pure APPEND `f` (store → store', deltas only unless `nses`),
  retrying across journal/cache races. Returns the committed store'."
  [session f nses]
  (loop [n 0]
    (let [base (:store @session)
          st'  (f base)]
      (cond
        (try-commit! session base st' nses) st'
        (< n 12) (do (refresh-cache! session) (recur (inc n)))
        :else (throw (ex-info "commit contention on append" {}))))))

(defn- rebased-write!
  "Run a single-form write with an atomic rebasing commit (item 4, the
  granularity dodge). The pure `transform` (store → {:store :delta ...} |
  {:error}) runs INSIDE swap!, so concurrent different-form writes rebase and
  land without locks or starvation; if the TARGET form itself changed since
  this op began (`target-node`: store → CST node), the commit aborts with
  {:conflict ...} — C5's MV-register semantics, Phase-1 face.
  The compile gate runs once, before commit: the form's CONTENT (what the
  image compiles) is invariant across rebases. Red-first stubs surface as
  :red-first; lint errors in OTHER forms (stale callers) surface as
  :carried-errors — both ride the result, never block, and the done-point
  re-checks."
  [session transform target-node target-desc ns-sym
   & {:keys [load?] :or {load? true}}]
  (let [orig     (some-> (target-node (:store @session)) n/string)
        conflict {:conflict {:form target-desc
                             :reason "form changed concurrently — re-read and retry"}}]
    (if (:db @session)
      ;; durable: the JOURNAL arbitrates (m5a) — append-CAS, refresh, rebase
      (loop [attempt 0, loaded? false, healed? false, stubbed nil, carried nil]
        (if (> attempt 12)
          {:error "commit contention: too many concurrent writes — retry"}
          (let [base (:store @session)
                cur  (some-> (target-node base) n/string)]
            (if (and (pos? attempt) (not= orig cur))
              conflict
              (let [out (transform base)]
                (if (:error out)
                  out
                  (let [load-res (when (and load? (not loaded?))
                                   (let [lr (edit/lint-refusals base (:store out) [ns-sym]
                                                                [(:form-id (:delta out))])]
                                     (if-let [gate (or (edit/cold-load-errors (:store out) [ns-sym])
                                                       (:refuse lr))]
                                       {:err gate}
                                       (merge (hot-load-all! session (:store out)
                                                             [(:form-id (:delta out))])
                                              (select-keys lr [:carried])))))]
                    (if (:err load-res)
                      {:error (str "form failed to compile: " (:err load-res))}
                      (do (when *pre-commit-hook* (*pre-commit-hook*))
                          (if (try-commit! session base (:store out) [ns-sym])
                            (cond-> out
                              (or healed? (:healed load-res))
                              (assoc :image-healed true)

                              (or stubbed (:stubbed load-res))
                              (assoc :red-first (or stubbed (:stubbed load-res)))

                              (or carried (:carried load-res))
                              (assoc :carried-errors (or carried (:carried load-res))))
                            (do (refresh-cache! session)
                                (recur (inc attempt) true
                                       (or healed? (boolean (:healed load-res)))
                                       (or stubbed (:stubbed load-res))
                                       (or carried (:carried load-res))))))))))))))
      ;; ephemeral: the pure transform reruns INSIDE swap! — starvation-free
      (let [base0 (:store @session)
            out0  (transform base0)]
        (if (:error out0)
          out0
          (let [load-res (when load?
                           (let [lr (edit/lint-refusals base0 (:store out0) [ns-sym]
                                                        [(:form-id (:delta out0))])]
                             (if-let [gate (or (edit/cold-load-errors (:store out0) [ns-sym])
                                               (:refuse lr))]
                               {:err gate}
                               (merge (hot-load-all! session (:store out0)
                                                     [(:form-id (:delta out0))])
                                      (select-keys lr [:carried])))))]
            (if (:err load-res)
              {:error (str "form failed to compile: " (:err load-res))}
              (do (when *pre-commit-hook* (*pre-commit-hook*))
                  (let [res (volatile! nil)]
                    (swap! session update :store
                           (fn [base]
                             (if (not= orig (some-> (target-node base) n/string))
                               (do (vreset! res conflict) base)
                               (let [out (transform base)]
                                 (if (:error out)
                                   (do (vreset! res out) base)
                                   (do (vreset! res out) (:store out)))))))
                    (cond-> @res
                      (and (nil? (:error @res)) (nil? (:conflict @res))
                           (:healed load-res))
                      (assoc :image-healed true)

                      (and (nil? (:error @res)) (nil? (:conflict @res))
                           (:stubbed load-res))
                      (assoc :red-first (:stubbed load-res))

                      (and (nil? (:error @res)) (nil? (:conflict @res))
                           (:carried load-res))
                      (assoc :carried-errors (:carried load-res))))))))))))

(defn- with-ms
  "Attach total op wall time (item 2 observability)."
  [m t0]
  (if (map? m)
    (assoc m :ms (quot (- (System/nanoTime) t0) 1000000))
    m))

(defn ingest!
  "The batch write for BRAND-NEW namespaces (W1, user decision): land a whole
  namespace's source in one call. Compile-gated like every write (the image
  loads it FIRST; a failed load commits nothing — T4), then verified and
  recorded like every write. Overwriting an existing namespace is NOT allowed
  — edit its forms instead. Returns {:ns :forms :test} or {:error msg}."
  [session ns-sym source & {:keys [agent]}]
  (if (get-in (:store @session) [:namespaces ns-sym])
    {:error (str ns-sym " already exists — edit its forms instead"
                 " (whole-namespace overwrite is not allowed)")}
    (try
      (let [base      (:store @session)
            candidate (store/ingest base ns-sym source :agent agent)]
        (if-let [derr (or (edit/dialect-scan candidate ns-sym)
                          ;; bulk imports (clone) land reality first and derive
                          ;; the manifest after — the gate blocks DRIFT, not adoption
                          (when-not (:adopting? @session)
                            (edit/module-scan candidate ns-sym)))]
          ;; same D3/D4 gate the edit path enforces — a host form can only enter
          ;; the store already ^:unsafe, so imported code is never frozen and the
          ;; image is never touched by a rejected namespace.
          {:error derr}
          (let [load!   #(repl/load-checked! (:image @session)
                                             (render/render-ns candidate ns-sym)
                                             (render/ns-path ns-sym))
                res     (load!)
                ;; the generic red-first seam, ingest face: a spec ns naming
                ;; not-yet-written vars stubs + retries instead of refusing
                stubbed (when (:err res)
                          (stub-missing-test-vars! (:image @session) candidate [ns-sym]))
                res     (if (and stubbed (nil? (:err (load!)))) {} res)]
            (cond
              (:err res)
              {:error (str "namespace failed to load: " (:err res))}

              (not (try-commit! session base candidate [ns-sym]))
              {:conflict {:reason "store changed during ingest — retry"}}

              :else
              (do
                (repl/eval! (:image @session)
                            (format "(dosync (commute (deref #'clojure.core/*loaded-libs*) conj '%s))"
                                    ns-sym))
                (let [edited  (into #{}
                                    (keep (fn [e]
                                            (when (:name e)
                                              (symbol (str ns-sym) (str (:name e))))))
                                    (store/forms candidate ns-sym))
                      summary (run-verification! session ns-sym nil :edited edited)]
                  (commit-appended! session
                                    #(store/record-verification % ns-sym summary)
                                    [])
                  (cond-> {:ns ns-sym
                           :forms (count (store/forms candidate ns-sym))
                           :warnings (vec (edit/ns-warnings candidate ns-sym))
                           :test summary}
                    stubbed (assoc :red-first stubbed
                                   :note (str "these vars don't exist yet — stubbed"
                                              " in-image as failing (red-first);"
                                              " implement them to go green.")))))))))
      (catch Exception e
        {:error (str "unparseable source (unbalanced?): " (ex-message e))}))))

(defn create-ns!
  "F4: bring a brand-new namespace into being — two modes (mutually exclusive):
   - **scaffold** (`:requires`, clause strings like \"[clojure.string :as str]\"):
     build an empty `(ns …)` to grow form-by-form with red-first TDD. The default.
   - **content** (`:source`, the whole namespace text incl. its own `(ns …)`):
     land the entire namespace in one verified call — forward refs within the
     file resolve as a unit, like a real `.clj` load. For ported/reference/data
     code that isn't subject to red→green.
   Delegates to `ingest!` (the shared engine); overwrite is refused there."
  [session ns-sym & {:keys [requires source agent]}]
  (cond
    (and source (seq requires))
    {:error (str ":source and :requires are mutually exclusive — put requires "
                 "inside the source's ns form")}

    source
    (ingest! session ns-sym source :agent agent)

    :else
    (ingest! session ns-sym
             (str "(ns " ns-sym
                  (when (seq requires)
                    (str "\n  (:require " (str/join "\n            " requires) ")"))
                  ")\n")
             :agent agent)))

;; --- query.* (read) ---

(defn query-sources
  "Batched read (ONE call, several targets): `targets` is a vector of
  {:ns sym} (whole namespace) or {:ns sym :name sym} (one form). Returns
  a vector of {:ns :name? :source} in target order; unknown targets get
  {:error} entries instead of failing the batch."
  [session targets]
  (let [st (:store @session)]
    (mapv (fn [{:keys [ns name]}]
            (cond
              (nil? (get-in st [:namespaces ns]))
              {:ns ns :error "no such namespace"}

              (nil? name)
              {:ns ns :source (render/render-ns st ns)}

              :else
              (if-let [e (store/form-named st ns name)]
                {:ns ns :name name :source (n/string (:node e))}
                {:ns ns :name name :error "no such form"})))
          targets)))
(defn query-source
  "Render `ns-sym`'s current source from the store (the VFS read)."
  [session ns-sym]
  (render/render-ns (:store @session) ns-sym))

(defn query-symbol
  "Describe the form defining `nm`: id, name, effectfulness (D6), source."
  [session ns-sym nm]
  (let [st  (:store @session)
        f   (store/form-named st ns-sym nm)
        eff (index/effectful-vars (index/analyze (render/render-ns st ns-sym)))]
    (when f
      (cond-> {:id         (:id f)
               :name       (:name f)
               :effectful? (contains? eff (symbol (str ns-sym) (str nm)))
               :source     (n/string (:node f))}
        (edit/unsafe? (:node f)) (assoc :unsafe? true)
        (edit/reads? (:node f))  (assoc :reads? true)))))

(defn query-references
  "Usages of `ns-sym/nm` across EVERY namespace (F-3c3 — same-ns-only results
  sent an eval agent to query_search instead; analyses are memo-cached, so the
  full scan is cheap)."
  [session ns-sym nm]
  (let [st (:store @session)]
    (vec (mapcat (fn [n]
                   (index/references (index/analyze (render/render-ns st n))
                                     ns-sym nm))
                 (sort (keys (:namespaces st)))))))

(defn- label-ancestors [agent-label]
  (when agent-label
    (let [parts (clojure.string/split agent-label #"/")]
      (map #(clojure.string/join "/" (take (inc %) parts))
           (range (count parts))))))

(defn- turn-intents
  "delta-id → the enclosing turn's verbatim :intent (resolved through the
  delta's agent, sub-agent path labels riding their root's turn). Derived
  at query time; truncated for display."
  [ds]
  (loop [ds ds, open {}, out {}]
    (if-let [d (first ds)]
      (let [open (case (:op d)
                   :turn-begin (assoc open (:agent d) (:intent d))
                   :turn-end   (dissoc open (:agent d))
                   open)
            in   (some open (or (label-ancestors (:agent d)) []))
            out  (if in
                   (assoc out (:id d)
                          (if (> (count in) 160)
                            (str (subs in 0 157) "...")
                            in))
                   out)]
        (recur (rest ds) open out))
      out)))

(defn query-lineage
  "Provenance chain for `nm`: the deltas that created or changed its form (who
  touched it, via which op, driven by which prompt)."
  [session ns-sym nm]
  (let [st (:store @session)
        id (:id (store/form-named st ns-sym nm))]
    (when id
      (let [ti (turn-intents (store/deltas st))]
        (->> (store/deltas st)
             (filter (fn [d]
                       (or (= id (:form-id d))
                           (some #{id} (:form-ids d)))))
             ;; lean: bulk content lives in query-form-history, not here
             (mapv #(cond-> (dissoc % :sources :changeset :result)
                      (ti (:id %)) (assoc :turn-intent (ti (:id %))))))))))

(defn- status-after
  "The verification outcome a delta PRODUCED: the first `:verify` at or after
  `at-id` (a write is immediately followed by its verify) — :green / :red /
  :unknown. This is 'did THIS version land green', vs `status-at`'s 'what
  was the state standing AT this point'."
  [store at-id]
  (let [ds (drop-while #(not= at-id (:id %)) (store/deltas store))
        v  (first (filter #(= :verify (:op %)) ds))]
    (if-let [r (:result v)]
      (if (zero? (+ (:fail r 0) (:error r 0))) :green :red)
      :unknown)))

(defn- human-time
  "Epoch ms → \"2026-07-04 09:15\" in the local zone (the human rendering of
  a delta's `:at`; agents keep the raw ms in the store)."
  [ms]
  (when ms
    (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
             (java.time.LocalDateTime/ofInstant
              (java.time.Instant/ofEpochMilli ms)
              (java.time.ZoneId/systemDefault)))))

(defn query-form-history
  "Every content version of `nm`'s form, oldest first, with the intent that
  produced it, when, and the verification state it landed in:
  [{:delta :op :prompt :source :status :at :turn-intent}]. `:status`
  (was-green-at, HM2) is the project's verification state governing each
  version — semantic × history, per form. `:format \"text\"` (HM4) renders
  the form's LIFE as a per-version LINE-diff story instead."
  [session ns-sym nm & {:keys [format]}]
  (let [st (:store @session)
        id (:id (store/form-named st ns-sym nm))]
    (when id
      (let [ti       (turn-intents (store/deltas st))
            versions (vec (for [d     (store/deltas st)
                                :let  [src (get-in d [:sources id])]
                                :when src]
                            (cond-> {:delta (:id d) :op (:op d)
                                     :prompt (:prompt d) :source src
                                     :status (status-after st (:id d))
                                     :at (human-time (:at d))}
                              (ti (:id d)) (assoc :turn-intent (ti (:id d))))))]
        (if (= "text" (some-> format name))
          (render-form-history-text (symbol (str ns-sym) (str nm)) versions)
          versions)))))

(defn- delta-fids [d]
  (concat (when (:form-id d) [(:form-id d)]) (:form-ids d)))

(defn query-search-history
  "Delta-log search — the 'which prompts touched X' query. Case-insensitive
  substring match of `pattern` against each delta's prompt, done label,
  commit/turn description, turn-end note, AND its enclosing turn intent;
  returns the matching deltas NEWEST-first with the forms they touched (as
  ns/name qsyms, resolved as of that delta) and the human time. `:limit`
  (default 25). Pairs with `query-form-at`/`query-lineage` to drill in."
  [session pattern & {:keys [limit] :or {limit 25}}]
  (if (str/blank? (str pattern))
    {:error "query-search-history needs a non-blank pattern"}
    (let [st  (:store @session)
          ds  (store/deltas st)
          ti  (turn-intents ds)
          pat (str/lower-case (str pattern))
          hit? (fn [d]
                 (some #(and % (str/includes? (str/lower-case (str %)) pat))
                       [(:prompt d) (:label d) (:description d) (:note d)
                        (ti (:id d))]))
          form-name (fn [d fid]
                      (or (some-> (get-in d [:sources fid]) store/name-of-source str)
                          (some-> (store/form-by-id st fid) :name str)
                          (when (= fid (:form-id d)) (some-> (:name d) str))
                          (str fid)))
          touched (fn [d]
                    (vec (for [fid (delta-fids d)]
                           (symbol (str (or (store/ns-of-form-id st fid) (:ns d)))
                                   (form-name d fid)))))]
      (->> ds
           reverse
           (filter hit?)
           (take (or limit 25))
           (mapv (fn [d]
                   (cond-> {:delta (:id d) :op (:op d) :at (human-time (:at d))}
                     (:prompt d)      (assoc :prompt (:prompt d))
                     (:label d)       (assoc :label (:label d))
                     (:description d) (assoc :description (:description d))
                     (:note d)        (assoc :note (:note d))
                     (ti (:id d))     (assoc :turn-intent (ti (:id d)))
                     (seq (delta-fids d)) (assoc :forms (touched d)))))))))

(defn query-history
  "The delta log as a story, newest first. Filters: `:ns`, `:contains`
  (substring of prompt/label — and, collapsed, of turn intents). `:limit`
  (default 20). `:collapse true` returns EPISODE rows instead of raw deltas —
  one row per agent-work-unit between done-points, the readable long-term
  view. All rows carry `:at` (local date-time)."
  [session & {:keys [ns contains limit collapse format] :or {limit 20}}]
  (let [render-text
        (fn [rows]
          (clojure.string/join
           "\n"
           (mapcat
            (fn [row]
              (cond
                (:turn row)
                (let [t (:turn row)]
                  (cons (str "TURN [" (:agent t) (when (:user t)
                                                   (str " for " (:user t)))
                             "] " (or (some-> (:intent t)
                                              (clojure.string/split-lines)
                                              first)
                                      "(no intent)")
                             (when (:open? t) "  (open)")
                             (when (:at t) (str "  @ " (:at t))))
                        (map (fn [e]
                               (str "  episode " (:agent e)
                                    (when (:label e) (str " \"" (:label e) "\""))
                                    ": " (:ops e) " ops, " (:forms e) " forms"
                                    (when (:open? e) " (open)")
                                    (when (:at e) (str "  @ " (:at e)))))
                             (:episodes t))))
                (:episode row)
                (let [e (:episode row)]
                  [(str "episode " (or (:agent e) "-")
                        (when (:label e) (str " \"" (:label e) "\""))
                        ": " (:ops e) " ops, " (:forms e) " forms"
                        (when (:open? e) " (open)")
                        (when (:at e) (str "  @ " (:at e))))])
                (:commit row)
                (let [c (:commit row)]
                  [(str "COMMIT \"" (:description c) "\""
                        (when (:agent c) (str " [" (:agent c) "]"))
                        (when (= :red (:status c)) "  (RED)")
                        (when (:at c) (str "  @ " (:at c))))])
                :else
                [(str (:id row) " " (:op row)
                      (when (:agent row) (str " [" (:agent row) "]"))
                      (when (:prompt row) (str " — " (:prompt row)))
                      (when (:at row) (str "  @ " (:at row))))]))
            rows)))
        rows
        (if collapse
          (let [ds       (store/deltas (:store @session))
                relevant (filter #(or (contains? #{:ingest :add :replace :delete
                                                   :rename :normalize :move :merge}
                                                 (:op %))
                                      (= :done (:op %)))
                                 ds)
                pos      (into {} (map-indexed (fn [i d] [(:id d) i])) ds)
                rows     (mapcat
                          (fn [[agent ads]]
                            (loop [ads ads, cur [], out []]
                              (if-let [d (first ads)]
                                (if (= :done (:op d))
                                  (recur (rest ads) []
                                         (if (seq cur)
                                           (conj out {:episode
                                                      (cond-> {:agent agent
                                                               :label (:label d)
                                                               :at    (human-time (:at d))
                                                               :from  (:id (first cur))
                                                               :to    (:id d)
                                                               :ops   (count cur)
                                                               :forms (count (distinct (mapcat delta-fids cur)))}
                                                        (nil? agent) (dissoc :agent))})
                                           out))
                                  (recur (rest ads) (conj cur d) out))
                                (if (seq cur)
                                  (conj out {:episode
                                             (cond-> {:agent agent
                                                      :open? true
                                                      :at    (human-time (:at (last cur)))
                                                      :from  (:id (first cur))
                                                      :ops   (count cur)
                                                      :forms (count (distinct (mapcat delta-fids cur)))}
                                               (nil? agent) (dissoc :agent))})
                                  out))))
                          (group-by :agent relevant))]
            (let [turn-brackets
                  (vec (mapcat (fn [[agent ms]]
                                 (loop [ms ms, open nil, out []]
                                   (if-let [m (first ms)]
                                     (cond
                                       (= :turn-begin (:op m))
                                       (recur (rest ms) m out)
                                       (and open (= :turn-end (:op m)))
                                       (recur (rest ms) nil
                                              (conj out {:agent agent
                                                         :intent (:intent open)
                                                         :user (:user open)
                                                         :at (human-time (:at open))
                                                         :from (:id open)
                                                         :to (:id m)}))
                                       :else (recur (rest ms) open out))
                                     (if open
                                       (conj out {:agent agent :open? true
                                                  :intent (:intent open)
                                                  :user (:user open)
                                                  :at (human-time (:at open))
                                                  :from (:id open)})
                                       out))))
                               (group-by :agent
                                         (filter #(contains? #{:turn-begin :turn-end}
                                                             (:op %))
                                                 ds))))
                  parent-of (fn [agent]
                              (when-let [i (and agent
                                                (clojure.string/last-index-of agent "/"))]
                                (subs agent 0 i)))
                  contains?* (fn [p c]        ; child's span inside parent's span
                               (let [pf (get pos (get-in p [:episode :from]) 0)
                                     pt (get pos (get-in p [:episode :to])
                                             Long/MAX_VALUE)
                                     cf (get pos (get-in c [:episode :from]) 0)]
                                 (and (<= pf cf) (<= cf pt))))
                  kids   (filter #(parent-of (get-in % [:episode :agent])) rows)
                  tops   (remove #(parent-of (get-in % [:episode :agent])) rows)
                  nested (mapv (fn [p]
                                 (let [cs (filterv #(and (= (parent-of
                                                             (get-in % [:episode :agent]))
                                                            (get-in p [:episode :agent]))
                                                         (contains?* p %))
                                                   kids)]
                                   (if (seq cs)
                                     (update p :episode assoc :children
                                             (mapv :episode cs))
                                     p)))
                               tops)
            ;; orphans: children whose parent episode isn't in view
                  claimed (into #{} (mapcat #(get-in % [:episode :children])) nested)
                  orphans (remove #(claimed (:episode %)) kids)
                  eps     (concat nested orphans)
                  in-turn? (fn [t e]
                             (let [ta (:agent t)
                                   ea (get-in e [:episode :agent])]
                               (and ea ta
                                    (or (= ea ta)
                                        (clojure.string/starts-with? ea (str ta "/")))
                                    (<= (get pos (:from t) 0)
                                        (get pos (get-in e [:episode :from]) 0))
                                    (<= (get pos (get-in e [:episode :from]) 0)
                                        (get pos (:to t) Long/MAX_VALUE)))))
                  turns   (mapv (fn [t]
                                  {:turn (assoc t :episodes
                                                (mapv :episode
                                                      (filter #(in-turn? t %) eps)))})
                                turn-brackets)
                  claimed-eps (into #{}
                                    (mapcat #(get-in % [:turn :episodes]))
                                    turns)
                  eps     (remove #(claimed-eps (:episode %)) eps)
                  ;; commit points: the MILESTONE grain above turns
                  commits (vec (for [d ds :when (= :commit (:op d))]
                                 {:commit
                                  (cond-> {:id          (:id d)
                                           :description (:description d)
                                           :target      (:target d)
                                           :at          (human-time (:at d))}
                                    (:agent d)  (assoc :agent (:agent d))
                                    (:status d) (assoc :status (:status d)))}))]
              (->> (concat turns eps commits)
                   (sort-by #(- (get pos (or (get-in % [:episode :to])
                                             (get-in % [:episode :from])
                                             (get-in % [:turn :to])
                                             (get-in % [:turn :from])
                                             (get-in % [:commit :id]))
                                     0)))
                   (filter #(or (nil? contains)
                                ;; commits match their description; turns
                                ;; match what the USER said (intent, user,
                                ;; agent, contained episode labels);
                                ;; episodes on label/agent
                                (clojure.string/includes?
                                 (cond
                                   (:commit %)
                                   (str (get-in % [:commit :description]) " "
                                        (get-in % [:commit :agent]))
                                   (:turn %)
                                   (let [t (:turn %)]
                                     (clojure.string/join
                                      " " (concat [(:intent t) (:user t) (:agent t)]
                                                  (map :label (:episodes t)))))
                                   :else
                                   (str (get-in % [:episode :label]) " "
                                        (get-in % [:episode :agent])))
                                 contains)))
                   (take limit)
                   vec)))
          (->> (store/deltas (:store @session))
               reverse
               (filter #(or (nil? ns) (= ns (:ns %))))
               (filter #(or (nil? contains)
                            (some (fn [s] (and s (clojure.string/includes? (str s) contains)))
                                  [(:prompt %) (:label %)])))
               (take limit)
               (mapv (fn [d]
                       (cond-> (select-keys d [:id :op :ns :prompt :label :group
                                               :agent :form-id :form-ids :old
                                               :new :before])
                         (:at d) (assoc :at (human-time (:at d))))))))]
    (if (= "text" (some-> format name))
      (render-text rows)
      rows)))

(defn query-outline
  "A namespace's shape at a glance (orientation, T2): every defined var with
  arities, `!`-effect status, and test-ness — a fraction of the tokens of
  reading the source. COMPACT by default; `:detail true` adds each var's
  docstring first line (the outline's token bulk)."
  [session ns-sym & {:keys [detail]}]
  (let [st  (:store @session)
        an  (index/analyze (render/render-ns st ns-sym))
        eff (index/effectful-vars an)]
    {:ns ns-sym
     :forms
     (vec (for [d (:var-definitions an)
                :when (= ns-sym (:ns d))]
            (cond-> {:name (:name d)}
              (:fixed-arities d)      (assoc :arities (vec (sort (:fixed-arities d))))
              (:varargs-min-arity d)  (assoc :varargs-min (:varargs-min-arity d))
              (and detail (:doc d))   (assoc :doc (first (str/split-lines (:doc d))))
              (index/test-definition? d) (assoc :test? true)
              (and (not (index/test-definition? d))
                   (contains? eff (symbol (str ns-sym) (str (:name d)))))
              (assoc :effectful? true))))}))

(defn query-project
  "The WHOLE store's shape in one call: every namespace with its outline
  (item 1 — orientation was ~90% of tool calls in successful runs; this
  replaces the namespaces→outline×N chain). COMPACT by default (names,
  arities, flags); `:detail true` adds doc lines. Pass `:since <delta id>`
  on a re-check: when nothing STRUCTURAL changed after that delta the
  response is a one-liner instead of the full outline (verify/turn/
  milestone markers don't count as change)."
  [session & {:keys [since detail]}]
  (let [st   (:store @session)
        ds   (store/deltas st)
        head (:id (last ds))
        quiet-ops #{:verify :done :commit :turn-begin :turn-end}
        unchanged? (and since
                       (some #(= since (:id %)) ds)
                       (->> ds
                            (drop-while #(not= since (:id %)))
                            rest
                            (every? #(contains? quiet-ops (:op %)))))]
    (if unchanged?
      {:unchanged-since since :head head}
      {:head       head
       :namespaces (mapv (fn [ns-sym] (query-outline session ns-sym :detail detail))
                         (sort (keys (:namespaces st))))})))

(defn query-search
  "The missing grep: regex over all store source, form-addressed results
  [{:ns :form :line}], capped at `:limit` (default 30)."
  [session pattern & {:keys [limit] :or {limit 30}}]
  (try
    (let [re (re-pattern pattern)
          st (:store @session)]
      (->> (for [ns-sym (sort (keys (:namespaces st)))
                 e      (store/forms st ns-sym)
                 line   (str/split-lines (n/string (:node e)))
                 :when  (re-find re line)]
             {:ns ns-sym
              :form (or (:name e) (:id e))
              :line (str/trim line)})
           (take limit)
           vec))
    (catch Exception ex
      {:error (str "bad pattern: " (ex-message ex))})))

(def ^:private content-ops
  #{:ingest :add :replace :delete :rename :normalize :move :merge})

(defn- episode-boundary
  "Where `agent-label`'s episode begins: its own last :done — or, for
  an agent that has never marked done, the last stable spot (ANY agent's
  done) before its first activity, so pre-existing history is never
  mistaken for contested work. nil = log start."
  [store agent-label]
  (let [ds  (store/deltas store)
        own (last (filter #(and (= :done (:op %))
                                (= agent-label (:agent %)))
                          ds))]
    (:id (or own
             (let [ckpts     (filter #(= :done (:op %)) ds)
                   first-own (first (filter #(and (contains? content-ops (:op %))
                                                  (= agent-label (:agent %)))
                                            ds))]
               (if first-own
                 (let [pos  (into {} (map-indexed (fn [i d] [(:id d) i])) ds)
                       fpos (get pos (:id first-own))]
                   (last (filter #(< (get pos (:id %)) fpos) ckpts)))
                 (last ckpts)))))))

(defn- episode-span
  "Deltas after `agent`'s episode boundary (all agents' — callers filter)."
  [store agent]
  (let [ds (store/deltas store)]
    (if-let [b (episode-boundary store agent)]
      (rest (drop-while #(not= b (:id %)) ds))
      ds)))

(defn turn-begin!
  "Open `agent`'s turn, recording the VERBATIM user ask as the root intent of
  everything until turn-end. A new begin supersedes an unclosed one. The
  intent also stays on the session (:last-intent) — orientation mines it so
  the brief arrives task-shaped."
  [session & {:keys [agent intent user]}]
  (when intent (swap! session assoc :last-intent intent))
  (commit-appended! session
                    #(first (store/record-turn % :turn-begin
                                               :agent agent :intent intent
                                               :user user))
                    [])
  {:turn :open :agent agent :intent intent})

(defn turn-end!
  "Close `agent`'s turn (stable or not — a red turn is still history)."
  [session & {:keys [agent note]}]
  (commit-appended! session
                    #(first (store/record-turn % :turn-end
                                               :agent agent :note note))
                    [])
  {:turn :closed :agent agent})

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

(defn- diff-lines
  "Minimal LCS line diff turning `was` into `now` (either may be nil):
  [[:same|:del|:add line] ...]. Forms are small — clarity over speed."
  [was now]
  (let [a   (if was (vec (str/split-lines was)) [])
        b   (if now (vec (str/split-lines now)) [])
        n   (count a)
        m   (count b)
        tbl (reduce (fn [tbl [i j]]
                      (assoc tbl [i j]
                             (if (= (a i) (b j))
                               (inc (get tbl [(inc i) (inc j)] 0))
                               (max (get tbl [(inc i) j] 0)
                                    (get tbl [i (inc j)] 0)))))
                    {}
                    (for [i (range (dec n) -1 -1)
                          j (range (dec m) -1 -1)]
                      [i j]))]
    (loop [i 0, j 0, out []]
      (cond
        (and (< i n) (< j m) (= (a i) (b j)))
        (recur (inc i) (inc j) (conj out [:same (a i)]))

        (and (< i n) (or (= j m) (>= (get tbl [(inc i) j] 0)
                                     (get tbl [i (inc j)] 0))))
        (recur (inc i) j (conj out [:del (a i)]))

        (< j m)
        (recur i (inc j) (conj out [:add (b j)]))

        :else out))))

(defn- render-changes-text
  "query-changes as a human story: steps with prompts, per-form LINE diffs
  (context/-/+ — unchanged lines are never re-emitted as churn), and the
  red→green verification arc."
  [c]
  (str/join
   "\n"
   (concat
    [(str "changes" (when (:agent c) (str " [" (:agent c) "]"))
          " since " (:since c))]
    (when (seq (:steps c))
      (cons "steps:"
            (map #(str "  " (:id %) " " (name (:op %))
                       (when (:ns %) (str " " (:ns %)))
                       (when (:prompt %) (str " — " (:prompt %))))
                 (:steps c))))
    (when (seq (:forms c))
      (cons "forms:"
            (mapcat (fn [f]
                      (cons (str "  " (case (:status f)
                                        :added "+" :deleted "-" "~")
                                 " " (:form f))
                            (map (fn [[tag line]]
                                   (str "    " (case tag
                                                 :same "  "
                                                 :del  "- "
                                                 :add  "+ ")
                                        line))
                                 (diff-lines (:was f) (:now f)))))
                    (:forms c))))
    (when (seq (:verification-arc c))
      [(str "verification: "
            (str/join " → " (map #(if (zero? (:fail %))
                                    "green"
                                    (str "red(" (:fail %) ")"))
                                 (:verification-arc c))))]))))

(defn- render-form-history-text
  "One form's LIFE as a story (HM4): each version's header (delta, op, the
  prompt/intent that produced it, its green/red, when) followed by the LINE
  diff FROM the previous version (the first version shows as all-added).
  Reuses `diff-lines` — unchanged lines are context, not churn."
  [qsym versions]
  (str/join
   "\n"
   (into [(str "form " qsym " — " (count versions) " version"
               (when (not= 1 (count versions)) "s"))]
         (mapcat
          (fn [prev v]
            (cons (str "  " (:delta v) " " (name (:op v))
                       (when-let [why (or (:prompt v) (:turn-intent v))]
                         (str " — " why))
                       "  [" (name (:status v)) "]"
                       (when (:at v) (str "  @ " (:at v))))
                  (map (fn [[tag line]]
                         (str "    " (case tag :same "  " :del "- " :add "+ ")
                              line))
                       (diff-lines (:source prev) (:source v)))))
          (cons nil versions)
          versions))))

(defn query-changes
  "The agent's EPISODE — everything since `:agent`'s last done: net
  per-form diffs (:was/:now), the step list, and the verification arc. The
  'what have I done since my last stable spot' view. Parallel agents with
  distinct :agent labels each see only their own work. `:format \"text\"`
  renders it as a human story with LINE diffs instead of full sources."
  [session & {:keys [agent from to format]}]
  (let [st       (:store @session)
        boundary (if from
                   ;; historical span: `from`/`to` are delta ids (e.g. from a
                   ;; collapsed history row); boundary = just BEFORE `from`
                   (:id (last (take-while #(not= from (:id %))
                                          (store/deltas st))))
                   (episode-boundary st agent))
        span     (if from
                   (let [ds (drop-while #(not= from (:id %))
                                        (store/deltas st))]
                     (if to
                       (let [[pre [t & _]] (split-with #(not= to (:id %)) ds)]
                         (concat pre (when t [t])))
                       ds))
                   (episode-span st agent))
        mine     (filter #(and (contains? content-ops (:op %))
                               (or (nil? agent) (= agent (:agent %))))
                         span)
        fids     (distinct (mapcat delta-fids mine))
        was      (store/sources-at st boundary)
        del-info (into {}
                       (keep (fn [d]
                               (when (= :delete (:op d))
                                 [(:form-id d) [(:ns d) (:name d)]])))
                       mine)
        at-end   (when to (store/sources-at st to))
        forms    (vec (keep (fn [fid]
                              (let [e   (store/form-by-id st fid)
                                    now (if to
                                          (get at-end fid)
                                          (some-> e :node n/string))
                                    old (get was fid)]
                                (when (not= old now)
                                  (let [[dns dnm] (get del-info fid)
                                        qform (if e
                                                (symbol (str (store/ns-of-form-id st fid))
                                                        (str (or (:name e) fid)))
                                                (symbol (str dns) (str (or dnm fid))))]
                                    (cond-> {:form    qform
                                             :form-id fid
                                             :status  (cond (nil? old) :added
                                                            (nil? now) :deleted
                                                            :else      :modified)}
                                      old (assoc :was old)
                                      now (assoc :now now))))))
                            fids))
        arc      (vec (for [d span
                            :when (= :verify (:op d))
                            :let [r (:result d)]]
                        {:delta (:id d)
                         :fail  (+ (:fail r 0) (:error r 0))}))]
    (let [result {:agent agent
                  :since (or boundary :log-start)
                  :steps (mapv #(select-keys % [:id :op :ns :prompt]) mine)
                  :forms forms
                  :verification-arc arc}]
      (if (= "text" (some-> format name))
        (render-changes-text result)
        result))))

(defn- callee-adjacency
  "qsym → sorted vector of STORE-INTERNAL callee qsyms, across every ns."
  [st]
  (let [internal? (:namespaces st)]
    (reduce
     (fn [adj ns-sym]
       (let [an (index/analyze (render/render-ns st ns-sym))]
         (reduce (fn [adj u]
                   (if (and (:from-var u) (internal? (:to u)))
                     (update adj
                             (symbol (str (:from u)) (str (:from-var u)))
                             (fnil conj (sorted-set))
                             (symbol (str (:to u)) (str (:name u))))
                     adj))
                 adj (:var-usages an))))
     {}
     (keys (:namespaces st)))))

(defn query-deps
  "The transitive CALLEE tree of ns/name (store-internal): what does this
  form reach? The planning input for extractions and blast-radius checks.
  Returns {:root q :calls {qsym [callees...]}} for every reachable form."
  [session ns-sym nm]
  (let [st   (:store @session)
        adj  (callee-adjacency st)
        root (symbol (str ns-sym) (str nm))]
    (loop [calls {} frontier [root]]
      (if-let [q (first frontier)]
        (if (contains? calls q)
          (recur calls (subvec frontier 1))
          (let [cs (vec (get adj q []))]
            (recur (assoc calls q cs) (into (subvec frontier 1) cs))))
        {:root root :calls calls}))))

^:reads (defn query-eval
  "Observe-only eval against the live image (the oracle): call anything —
  including effectful fns — but (re)defining code is rejected (T5); writes go
  through the edit tools so provenance stays airtight."
  [session code]
  (if-let [err (edit/observe-gate code)]
    {:error err}
    ;; strip :reload in the owned image — no source files exist to reload, so it
    ;; would only throw FileNotFoundException (store ns) or waste a jar re-read
    (let [r (repl/eval-checked! (:image @session) (edit/strip-image-reload code))]
      (if (:err r)                                  ; F-3c2: never a silent []
        {:error (:err r)}
        (:values r)))))

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

(defn query-namespaces
  "What exists? Every store namespace with its form count (orientation, T2)."
  [session]
  (let [st (:store @session)]
    (vec (for [ns-sym (keys (:namespaces st))]
           {:ns ns-sym :forms (count (store/forms st ns-sym))}))))

;; --- verification (D1 tracing + D5 restart-as-diagnostic) ---

(defn- green? [summary]
  (zero? (+ (:fail summary 0) (:error summary 0))))

(defn- fresh-image!
  "Replace the image with a fresh process reloaded from the store — faithful by
  construction (the D5 backstop). With a warm spare, the swap avoids a JVM boot
  on the critical path; the next spare starts warming immediately."
  [session]
  (let [{:keys [image spare store]} @session
        fresh (image-with-deps! spare (:deps store))]  ; adopt+reconcile or fresh
    (repl/stop! image)
    (swap! session assoc :image fresh :spare nil)
    (start-spare! session)
    (let [{:keys [store image]} @session]
      (doseq [ns-sym (store/ns-dependency-order store)]    ; X3: deps first
        (when-let [err (image/load-ns! image store ns-sym)]
          ;; outstanding red-first stubs die with the old image — re-stub, retry
          (when-not (and (stub-missing-test-vars! image store [ns-sym])
                         (nil? (image/load-ns! image store ns-sym)))
            (throw (ex-info (str "restart load failed for " ns-sym ": " err) {}))))))))

(defn restart!
  "D5 escape hatch: the agent-callable fresh-image restart."
  [session]
  (fresh-image! session)
  session)

(defn- hot-load-all!
  "Checked-load `form-ids` from a CANDIDATE store value into the image (S1).
  nil on success; {:healed true} when a STALE IMAGE had to be refreshed to
  make the load succeed (D5.1); {:stubbed [qsyms]} when red-first stubs made
  a -test namespace compile (the generic red-first seam — the spec runs and
  fails honestly); {:err msg} when the forms genuinely don't compile (image
  restored either way). Keys compose."
  [session candidate form-ids]
  (let [nses  (vec (distinct (keep #(store/ns-of-form-id candidate %) form-ids)))
        stub! #(stub-missing-test-vars! (:image @session) candidate nses)]
    (letfn [(load-all []
              (loop [ids (seq form-ids)]
                (when ids
                  (or (edit/hot-load-form! (:image @session) candidate (first ids))
                      (recur (next ids))))))]
      (when-let [_err (load-all)]
        (let [stubbed (stub!)]
          (if (and (seq stubbed) (nil? (load-all)))
            {:stubbed stubbed}
            (do (fresh-image! session)             ; maybe the image was stale
                (let [stubbed (stub!)]             ; a fresh image loses stubs
                  (if-let [err2 (load-all)]
                    (do (fresh-image! session) {:err err2})
                    (cond-> {:healed true}
                      (seq stubbed) (assoc :stubbed stubbed)))))))))))

(defn- traced-run!
  "Run `test-ns`'s tests (all, or `only` names) with form-tracing; absorb the
  observed test→form map into the session (persisted — Q3); return the summary.
  `skip-integration?` drops `^:integration` tests (M5, the fast-path default)."
  [session test-ns only & [skip-integration?]]
  (let [{:keys [image store]} @session
        {:keys [summary trace]} (image/traced-test-run
                                 image store test-ns :only only
                                 :skip-integration? skip-integration?)]
    (swap! session update :test-map merge trace)
    (persist-trace! session)
    summary))

(def ^:private reload-signature-res
  "Failure texts that smell like hot-reload staleness rather than logic bugs."
  [#"Unable to resolve symbol"
   #"Attempting to call unbound fn"
   #"No implementation of method"
   #"Var .* is unbound"])

(defn- reload-signature? [failure]
  (let [s (str (:actual failure) " " (:message failure))]
    (or (boolean (some #(re-find % s) reload-signature-res))
        ;; same-named classes cast-failing against each other = redefined type
        (boolean
         (when-let [[_ c1 c2] (re-find #"class (\S+) cannot be cast to class (\S+)" s)]
           (= (last (str/split c1 #"\.")) (last (str/split c2 #"\."))))))))

(defn- suspicious-red?
  "Could this red plausibly be image staleness rather than a genuine failure
  (D5.1)? Yes iff: no edit context; a truncated failure list; a
  reload-signature failure; or an UNEXPLAINED FLIP — a failing test whose
  traced form-set doesn't intersect the just-edited forms and which wasn't
  itself edited (this also catches value-capture staleness, since captured
  calls bypass the trace)."
  [session edited summary]
  (let [tmap       (:test-map @session)
        failures   (:failures summary)
        truncated? (> (+ (:fail summary 0) (:error summary 0)) (count failures))]
    (or (nil? edited)
        truncated?
        (boolean (some reload-signature? failures))
        (boolean
         (some (fn [f]
                 (let [t       (:test f)
                       touched (get tmap t)]
                   (or (nil? touched)
                       (and (not (contains? edited t))
                            (empty? (set/intersection touched edited))))))
               failures)))))

(defn- diagnosed-run!
  "Run tests. Reds cross-check on a fresh image ONLY when staleness is
  plausible (D5.1: reload signatures, unexplained flips, missing provenance);
  a red clearly caused by the just-edited forms returns immediately as
  {:diagnosis :genuine} — no restart, no second run. `:fresh true` restarts
  FIRST and runs once against a guaranteed-faithful image."
  [session test-ns only & {:keys [edited fresh include-integration?]}]
  (when fresh (fresh-image! session))
  (let [skip? (not include-integration?)               ; M5: fast path skips
        r1    (traced-run! session test-ns only skip?)]
    (cond
      (green? r1) r1

      fresh (assoc r1 :fresh-confirmed true)

      (suspicious-red? session edited r1)
      (do (fresh-image! session)
          (let [r2 (traced-run! session test-ns only skip?)]
            (if (green? r2)
              (assoc r2 :staleness-detected true)
              (assoc r2 :fresh-confirmed true))))

      :else (assoc r1 :diagnosis :genuine))))

(defn- affected-tests
  "Which tests must re-run after editing `ns-sym/nm`: the tests observed (via
  tracing) to exercise that form — or the form itself if it IS a test. nil =
  no trace information; run everything (conservative)."
  [session ns-sym nm]
  (let [qform (symbol (str ns-sym) (str nm))
        tmap  (:test-map @session)]
    (if (contains? tmap qform)
      [qform]
      (let [hits (->> tmap
                      (keep (fn [[t forms]] (when (contains? forms qform) t)))
                      sort vec)]
        (when (seq hits) hits)))))

(defn- implicate
  "Rock 2: annotate each failure with the just-changed forms that failing
  test actually exercises (trace map ∩ edited) — the correlation agents
  otherwise re-derive from raw expected/actual on every red."
  [summary tmap edited]
  (if-not (and (seq (:failures summary)) (seq edited) (seq tmap))
    summary
    (update summary :failures
            (fn [fs]
              (mapv (fn [f]
                      (let [hits (some->> (get tmap (:test f))
                                          set
                                          (set/intersection (set edited))
                                          seq sort vec)]
                        (cond-> f hits (assoc :implicated hits))))
                    fs)))))
(defn- shape-episode-reds!
  "Mid-episode response diet (direction over repetition): full failure
  detail rides ONLY for tests newly red on THIS write; tests already
  reported red this episode compress to :still-red names; previously-red
  tests that ran clean report :went-green. The ledger lives on the
  session (:episode-reds) and the done-point (`boundary?` true) bypasses
  compression — the boundary always reports every standing red in full —
  and resets the ledger. Explicit test_run bypasses this shaping too
  (spot-checks get everything)."
  [session summary affected scope boundary?]
  (let [prev     (or (:episode-reds @session) #{})
        blocks   (vec (:failures summary))
        now-red  (into #{} (keep :test) blocks)
        scope-ns (into #{} (map str) (if (sequential? scope) scope [scope]))
        ran      (if (seq affected)
                   (set affected)
                   (into #{} (filter #(contains? scope-ns (namespace %))) prev))
        greens   (vec (sort (remove now-red (filter ran prev))))
        ledger   (-> prev (set/difference (set greens)) (into now-red))]
    (swap! session assoc :episode-reds (if boundary? now-red ledger))
    (if boundary?
      summary
      (let [new-blocks (vec (remove #(contains? prev (:test %)) blocks))
            stills     (vec (sort (filter prev now-red)))]
        (cond-> (assoc summary :failures new-blocks)
          (empty? new-blocks) (dissoc :failures)
          (seq stills)        (assoc :still-red stills)
          (seq greens)        (assoc :went-green greens))))))
(defn- run-verification!
  "Diagnosed run of `affected` tests (grouped by their namespace), or of all of
  `default-ns`'s tests when there's no trace information. `:edited` (the
  just-changed form qsyms) powers the D5.1 genuine-vs-suspicious call and the
  red-result :implicated correlation (Rock 2). Results pass through the
  episode-red shaper (direction over repetition); `:boundary? true` (the
  done-point) bypasses compression and resets the ledger."
  [session default-ns affected & {:keys [edited fresh include-integration? boundary?]}]
  (shape-episode-reds!
   session
   (implicate
    (if (nil? affected)
      (diagnosed-run! session default-ns nil :edited edited :fresh fresh
                      :include-integration? include-integration?)
      (reduce (fn [acc [tns tsyms]]
                (merge-with (fn [a b]
                              (cond (number? a) (+ a b)
                                    (and (sequential? a) (sequential? b)) (into (vec a) b)
                                    :else (or b a)))
                            acc
                            (diagnosed-run! session tns (mapv (comp symbol name) tsyms)
                                            :edited edited :fresh fresh
                                            :include-integration? include-integration?)))
              {}
              (group-by (comp symbol namespace) affected)))
    (:test-map @session)
    edited)
   affected default-ns boundary?))

;; --- edit.* / runtime ---

(defn edit-replace!
  "Replace the form `nm` in `ns-sym` with `new-source` (O1 whole-form replace):
  pipeline + hot-reload, then re-verify — only the tests the trace map says
  exercise this form (D1), cross-checked on a fresh image if red (D5) — and
  record the outcome as provenance (C4)."
  [session ns-sym nm new-source & {:keys [prompt agent]}]
  (let [t0 (System/nanoTime)
        pre-warned (set (map :var (edit/ns-warnings (:store @session) ns-sym)))
        r (rebased-write!
           session
           (fn [base] (edit/replace-form base ns-sym nm new-source
                                         :prompt prompt :agent agent))
           (fn [base] (:node (store/form-named base ns-sym nm)))
           (symbol (str ns-sym) (str nm))
           ns-sym)]
    (if (or (:error r) (:conflict r))
      r
      (let [qform    (symbol (str ns-sym) (str nm))
            new-nm   (:name (store/form-by-id (:store r)
                                              (:form-id (:delta r))))
            edited   (into #{qform}
                           (when new-nm [(symbol (str ns-sym) (str new-nm))]))
            affected (affected-tests session ns-sym nm)
            untested (and (nil? affected) (seq (:test-map @session))
                           (not (re-find #"^\(\s*(?:clojure\.test/)?deftest\b"
                                         (str/triml new-source))))
            _        (when (and new-nm (not= new-nm nm))
                       (repl/eval! (:image @session)
                                   (format "(ns-unmap '%s '%s)" ns-sym nm)))
            summary  (run-verification! session ns-sym affected
                                        :edited edited)
            existing (count (filter (comp pre-warned :var) (:warnings r)))]
        (commit-appended! session
                          #(store/record-verification % ns-sym summary) [])
        (with-ms
          (cond-> {:delta    (:delta r)
                   ;; T3: only NEW violations; pre-existing ones as a count
                   :warnings (vec (remove (comp pre-warned :var) (:warnings r)))
                   :test     summary
                   :affected (or affected :all)}
            (:image-healed r) (assoc :image-healed true)
            (pos? existing)   (assoc :existing-warnings existing)
            untested          (assoc :untested true)
            (:red-first r)    (assoc :red-first (:red-first r)
                                     :note (str "these vars don't exist yet — stubbed"
                                                " in-image as failing (red-first);"
                                                " implement them to go green."))
            (:carried-errors r) (assoc :carried-errors (:carried-errors r)))
          t0)))))

(defn add-form!
  "Add a new top-level form to `ns-sym` (O1 base write): dialect gate, `:add`
  delta, hot-reload into the image, verification, provenance. `:before
  <form-name>` anchors the new form immediately before that one (default:
  appended at the tail) — define-before-use without a follow-up move.
  Returns {:delta :warnings :test :affected} or {:error msg}."
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
            r (rebased-write!
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
                     (if-let [merr (when nm (edit/module-refusal st' ns-sym nm))]
                       {:error merr}
                       {:store st' :delta d})
                     {:error (str "no namespace " ns-sym " (ingest it first)")})))
               (fn [base] (when nm (:node (store/form-named base ns-sym nm))))
               (symbol (str ns-sym) (str (or nm "anonymous")))
               ns-sym)]
        (if (or (:error r) (:conflict r))
          r
          (let [edited   (if nm #{(symbol (str ns-sym) (str nm))} #{})
                    affected (when nm (affected-tests session ns-sym nm))
                    summary  (run-verification! session ns-sym affected
                                                :edited edited)
                    all-w    (edit/ns-warnings (:store @session) ns-sym)
                    existing (count (filter (comp pre-warned :var) all-w))]
                (commit-appended! session
                                  #(store/record-verification % ns-sym summary)
                                  [])
                (with-ms
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
                    (:carried-errors r) (assoc :carried-errors (:carried-errors r)))
                  t0)))))))

(defn delete-form!
  "Delete the form named `nm` from `ns-sym`: `:delete` delta, `ns-unmap` in the
  image, verification (tests that exercised it will go red — the honest signal
  if it was still referenced), provenance."
  [session ns-sym nm & {:keys [prompt agent]}]
  (let [r (rebased-write!
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
      (let [affected (affected-tests session ns-sym nm)]
            (repl/eval! (:image @session) (format "(ns-unmap '%s '%s)" ns-sym nm))
            (let [summary (run-verification! session ns-sym affected
                                             :edited #{(symbol (str ns-sym) (str nm))})]
              (commit-appended! session
                                #(store/record-verification % ns-sym summary)
                                [])
              {:delta (:delta r) :test summary :affected (or affected :all)})))))

(defn- apply-group-step
  "Apply one edit-group step to a store VALUE. Returns {:store :delta :hot ...}
  or {:error msg}. `:hot` is the hot-reload action for the commit phase.
  Actions: :replace, :add (optionally anchored via :before), :delete,
  :move (:name before :before — reordering inside the atomic group; image
  vars are order-independent, so no hot action), :subform (:match + :source,
  `:text true` for raw-text matches — a small change INSIDE a big form
  without re-transcribing it), and :require (one require clause into the ns
  form). Subform/require compute the new source and reduce to :replace, so
  every gate (dialect, Q7 isolation, Q9 teaching errors) rides along."
  [st gid prompt agent {:keys [action ns name source before match text] :as step}]
  (case action
    :replace (let [{:keys [node error]} (edit/parse-form source)
                   iso (when node
                         (edit/isolation-refusal (edit/require-aliases st ns) node))]
               (cond
                 error {:error error}
                 iso   {:error iso}
                 :else
                 (if-let [[st' d] (store/replace-node st ns name node
                                                      :prompt prompt :group gid
                                                      :agent agent)]
                   (let [nm' (store/form-symbol node)]
                     (if-let [merr (edit/module-refusal st' ns (or nm' name))]
                       {:error merr}
                       {:store st' :delta d
                        :hot (if (and nm' (not= nm' name))
                               [:load-unmap (:form-id d) ns name]
                               [:load (:form-id d)])}))
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
                   (if-let [merr (when nm (edit/module-refusal st' ns nm))]
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
    :delete  (if-let [[st' d] (store/remove-form st ns name
                                                 :prompt prompt :group gid
                                                 :agent agent)]
               {:store st' :delta d :hot [:unmap ns name]}
               (edit/missing-form-error st ns name))
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
  :ns sym :name sym :source str} ...]."
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
          (let [lr       (edit/lint-refusals base0 st (distinct (map :ns steps))
                                             (keep :form-id deltas))
                load-res (if-let [gate (or (edit/cold-load-errors st (distinct (map :ns steps)))
                                           (:refuse lr))]
                           {:err gate}
                           (merge (hot-load-all! session st
                                                 (keep (fn [[k a]] (when (#{:load :load-unmap} k) a))
                                                       hots))
                                  (select-keys lr [:carried])))]
            (cond
              (:err load-res)
              {:error (str "group failed to compile: " (:err load-res))}

              (not (try-commit! session base0 st
                                (vec (distinct (map :ns steps)))))
              {:conflict {:reason "store changed during multi-form op — retry"}}

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
                                      (let [a (affected-tests session ns nm)]
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
                    main-ns  (vec (distinct (map :ns steps)))
                    summary  (run-verification! session main-ns
                                                (when (seq affected) affected)
                                                :edited edited)]
                (commit-appended! session
                                  #(store/record-verification % main-ns summary)
                                  [])
                (let [all-w    (->> (map :ns steps) distinct
                                    (mapcat #(edit/ns-warnings (:store @session) %)))
                      existing (count (filter (comp pre-warned :var) all-w))]
                  (with-ms
                    (cond-> {:group    gid
                             :deltas   deltas
                             :changed-nses (vec (distinct (map :ns steps)))
                             :warnings (vec (remove (comp pre-warned :var) all-w))
                             :test     summary
                             :affected (or (not-empty affected) :all)}
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
  the normal replace pipeline (delta, hot-reload, verification)."
  [session ns-sym require-str & {:keys [prompt]}]
  (if-let [f (store/form-named (:store @session) ns-sym ns-sym)]
    (let [r (edit/add-require-source (n/string (:node f)) require-str)]
      (if (:error r)
        r
        (edit-replace! session ns-sym ns-sym (:src r)
                       :prompt (or prompt (str "add require " require-str)))))
    {:error (str "no namespace " ns-sym " (create it first)")}))

(defn remove-require!
  "Symmetric counterpart of add-require!: structurally remove `lib`'s require
  spec from `ns-sym`'s ns form, through the normal replace pipeline."
  [session ns-sym lib & {:keys [prompt]}]
  (if-let [f (store/form-named (:store @session) ns-sym ns-sym)]
    (let [r (edit/remove-require-source (n/string (:node f)) lib)]
      (if (:error r)
        r
        (edit-replace! session ns-sym ns-sym (:src r)
                       :prompt (or prompt (str "remove require " lib)))))
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
          (if-not (try-commit! session base0 st' [ns-sym])
            {:conflict {:reason "store changed concurrently — retry"}}
            {:delta delta :moved {:form nm :before before}}))
        {:error (str "cannot move " nm)}))))

(defn- forms-changed-since
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
        summary     (diagnosed-run! session ns-sym only'
                                    :edited edited :fresh fresh
                                    :include-integration? true)]  ; M5: explicit run
    (commit-appended! session
                      #(store/record-verification % ns-sym summary) [])
    (with-ms (cond-> summary
               (and only' (zero? (:test summary 0)))
               (assoc :note (str "0 tests matched :only " (vec only)
                                 " — check the names; ^:isolated tests only run"
                                 " under test_run {:isolated true}")))
             t0)))

(defn fix-declares!
  "clj-surgeon-inspired tidy: for each (declare ...) in `ns-sym`, move the
  declared defns above their first caller when safe (the defn's own intra-ns
  callees must already precede that point — otherwise SKIP with a reason,
  e.g. mutual recursion), then delete declares whose every name was
  satisfied. One atomic group, verified."
  [session ns-sym & {:keys [prompt agent]}]
  (let [st     (:store @session)
        forms  (store/forms st ns-sym)
        idx-of (into {} (map-indexed (fn [i f] [(:name f) i])
                                     forms))
        adj    (callee-adjacency st)
        an     (index/analyze (render/render-ns st ns-sym))
        decls  (filter (fn [f]
                         (and (nil? (:name f))
                              (= 'declare (try (first (n/sexpr (:node f)))
                                               (catch Exception _ nil)))))
                       forms)]
    (if (empty? decls)
      {:removed 0 :note "no declares"}
      (let [[gid st0] (store/alloc-id st "g")
            plan
            (for [d decls
                  nm (rest (n/sexpr (:node d)))]
              (let [def-idx (get idx-of nm)
                    callers (for [u (:var-usages an)
                                  :when (and (= ns-sym (:to u))
                                             (= nm (:name u))
                                             (:from-var u)
                                             (get idx-of (:from-var u)))]
                              (get idx-of (:from-var u)))
                    first-caller (when (seq callers) (apply min callers))
                    my-deps (keep #(when (= (str ns-sym) (namespace %))
                                     (get idx-of (symbol (name %))))
                                  (get adj (symbol (str ns-sym) (str nm)) []))]
                (cond
                  (nil? def-idx)
                  {:name nm :decl (:id d) :action :skip
                   :reason "declared but never defined here"}

                  (or (nil? first-caller) (< def-idx first-caller))
                  {:name nm :decl (:id d) :action :ok}   ; already fine

                  (every? #(or (= % def-idx) (< % first-caller)) my-deps)
                  {:name nm :decl (:id d) :action :move
                   :before (:name (nth forms first-caller))}

                  :else
                  {:name nm :decl (:id d) :action :skip
                   :reason "its own callees sit below the caller (mutual recursion?)"})))
            by-decl (group-by :decl plan)
            removable (into #{}
                            (keep (fn [[decl-id entries]]
                                    (when (every? #(not= :skip (:action %)) entries)
                                      decl-id)))
                            by-decl)
            st' (as-> st0 st*
                  (reduce (fn [st* {:keys [action name before]}]
                            (if (= :move action)
                              (or (first (store/move-form st* ns-sym name before
                                                          :prompt prompt :group gid
                                                          :agent agent))
                                  st*)
                              st*))
                          st* plan)
                  (reduce (fn [st* decl-id]
                            (or (first (store/remove-form st* ns-sym decl-id
                                                          :prompt (or prompt "fix-declares")
                                                          :group gid :agent agent))
                                st*))
                          st* removable))]
        (if (and (empty? removable)
                 (not-any? #(= :move (:action %)) plan))
          {:removed 0 :skipped (vec (filter #(= :skip (:action %)) plan))}
          (if-not (try-commit! session st st' [ns-sym])
            {:conflict {:reason "store changed during fix-declares — retry"}}
            (let [summary (run-verification! session ns-sym nil
                                             :edited (set (map #(symbol (str ns-sym)
                                                                        (str (:name %)))
                                                               plan)))]
              (commit-appended! session
                                #(store/record-verification % ns-sym summary) [])
              {:removed (count removable)
               :moved   (vec (keep #(when (= :move (:action %)) (:name %)) plan))
               :skipped (vec (filter #(= :skip (:action %)) plan))
               :test    summary})))))))

(defn- test-nses-reaching
  "Test namespaces (any ns holding a deftest) whose require-closure
  reaches one of `changed-nses` — the PROVABLE set of tests a change can
  affect (a test only exercises code it can load). The honest fallback
  scope when the trace map is silent."
  [store changed-nses]
  (let [changed  (set changed-nses)
        test-ns? (fn [nsx]
                   (some #(str/starts-with? (str/triml (n/string (:node %)))
                                            "(deftest")
                         (store/forms store nsx)))]
    (vec (sort (for [t (keys (:namespaces store))
                     :when (and (test-ns? t)
                                (seq (set/intersection
                                      (store/ns-closure store t)
                                      changed)))]
                 t)))))
(defn done!
  "The DONE-POINT: call when you believe your changes are complete. Marks
  the episode boundary and runs the automatic done-processing — normalize
  every form changed this episode (conservative behavior-preserving
  rewrites), clean up safe (declare)s, kondo-lint every touched namespace,
  and RUN THE AFFECTED TESTS for everything the episode touched (no
  test_run needed first — mid-episode runs are for spot-checks). Findings
  ride the boundary delta so the next session's brief surfaces anything
  left red. Returns {:done id :normalized n :rewrites [{:form :applied}]
  :lint [...] :test s :findings {...}}."
  [session & {:keys [label agent]}]
  (let [st       (:store @session)
        changed  (->> (episode-span st agent)
                      (filter #(and (contains? content-ops (:op %))
                                    (= agent (:agent %))))
                      (mapcat delta-fids)
                      distinct
                      (filter #(store/ns-of-form-id st %)))
        rewrites (vec (for [fid changed
                            :let [e (store/form-by-id st fid)
                                  {:keys [node applied]} (normalize/normalize-form (:node e))]
                            :when (seq applied)]
                        {:form-id fid
                         :form    (symbol (str (store/ns-of-form-id st fid))
                                          (str (or (:name e) (:id e))))
                         :node    node
                         :applied applied}))
        _        (when (seq rewrites)
                   (let [changeset   (into {} (map (juxt :form-id :node)) rewrites)
                         main-ns     (store/ns-of-form-id st (:form-id (first rewrites)))
                         [st' _]     (store/apply-changeset st :normalize main-ns changeset
                                                            :prompt (or label "done normalization")
                                                            :agent agent)
                         touched     (distinct (map #(store/ns-of-form-id st' %) (keys changeset)))]
                     (when-let [err (:err (hot-load-all! session st' (keys changeset)))]
                       (throw (ex-info (str "normalization failed to compile: " err) {})))
                     (when-not (try-commit! session st st' (vec touched))
                       (throw (ex-info "store changed during done — retry" {})))))
        ;; automatic declare hygiene (user-directed): the compile gate makes
        ;; agents mint (declare)s; the done-point cleans the safe ones up
        declare-fixes
        (vec (for [ns* (distinct (keep #(store/ns-of-form-id (:store @session) %)
                                       changed))
                   :let [r (fix-declares! session ns*
                                          :prompt (or label "done declare hygiene")
                                          :agent agent)]
                   :when (pos? (:removed r 0))]
               {:ns ns* :removed (:removed r) :moved (:moved r)}))
        ;; kondo lint over every namespace touched since the last done-point —
        ;; carried mid-episode errors (stale callers) get re-checked HARD here
        lint (vec (for [ns-sym (distinct (map #(store/ns-of-form-id (:store @session) %)
                                              changed))
                        :let [st* (:store @session)]
                        f (index/lint (render/render-ns st* ns-sym))]
                    (assoc f :ns ns-sym
                           :form (when-let [e (render/owner-form st* ns-sym
                                                                 (:row f) (:col f))]
                                   (symbol (str ns-sym) (str (or (:name e) (:id e))))))))
        ;; THE done-point verification: the episode's whole working set —
        ;; independent of whether normalize rewrote anything
        summary
        (when (seq changed)
          (let [st*      (:store @session)
                qsyms    (into #{}
                               (keep (fn [fid]
                                       (when-let [e (store/form-by-id st* fid)]
                                         (symbol (str (store/ns-of-form-id st* fid))
                                                 (str (or (:name e) (:id e)))))))
                               changed)
                per      (map (fn [q] (affected-tests session
                                                      (symbol (namespace q))
                                                      (symbol (name q))))
                              qsyms)
                affected (when (not-any? nil? per)
                           (vec (sort (distinct (apply concat per)))))
                changed-nses (vec (distinct (keep #(store/ns-of-form-id st* %)
                                                  changed)))
                ;; with no trace coverage the fallback must still REACH the
                ;; tests: closure-bounded test nses, not just the changed ones
                main-ns  (vec (distinct (concat changed-nses
                                                (test-nses-reaching st* changed-nses))))
                s        (run-verification! session main-ns
                                            (when (seq affected) affected)
                                            :edited qsyms
                                            :include-integration? true
                                            :boundary? true)]  ; M5
            (commit-appended! session
                              #(store/record-verification % main-ns s) [])
            s))
        findings (let [lint-errors (count (filter #(= :error (:level %)) lint))
                       failures    (+ (:fail summary 0) (:error summary 0))
                       st*         (:store @session)
                       missing-doc (vec (sort (distinct
                                               (keep (fn [fid]
                                                       (when-let [e (store/form-by-id st* fid)]
                                                         (:var (edit/missing-doc-warning
                                                                st*
                                                                (store/ns-of-form-id st* fid)
                                                                (:name e)))))
                                                     changed))))]
                   (cond-> {:test-status (cond (nil? summary)  :none
                                               (pos? failures) :red
                                               :else           :green)
                            :failures    failures
                            :lint-errors lint-errors}
                     (seq missing-doc) (assoc :missing-doc missing-doc)))
        cid (let [v (volatile! nil)]
              (commit-appended! session
                                (fn [base]
                                  (let [[st2 c] (store/record-done base label
                                                                   :agent agent
                                                                   :findings findings)]
                                    (vreset! v c)
                                    st2))
                                [])
              (swap! session assoc :done @v)
              @v)]
    (cond-> {:done cid
             :normalized (count rewrites)
             :rewrites   (mapv #(select-keys % [:form :applied]) rewrites)
             :lint       lint
             :findings   findings}
      (seq declare-fixes) (assoc :declares-fixed declare-fixes)
      summary             (assoc :test summary))))

(defn- status-at
  "Verification status as of delta `at-id`: the last `:verify` delta at or
  before it — :green / :red / :unknown (no verification on record)."
  [store at-id]
  (let [upto (reduce (fn [acc d]
                       (let [acc (conj acc d)]
                         (if (= at-id (:id d)) (reduced acc) acc)))
                     [] (store/deltas store))
        v    (last (filter #(= :verify (:op %)) upto))]
    (if-let [r (:result v)]
      (if (zero? (+ (:fail r 0) (:error r 0))) :green :red)
      :unknown)))

(defn- resolve-at
  "Normalize an `at` argument to a plain delta id: a `:commit` marker id
  becomes its `:target` (time-travel to a milestone points at the
  milestone's state); any other existing delta id passes through; an unknown
  id → nil (the caller reports it)."
  [store at]
  (when at
    (let [d (first (filter #(= at (:id %)) (store/deltas store)))]
      (cond
        (nil? d)            nil
        (= :commit (:op d)) (:target d)
        :else               at))))

(defn- verify-at
  "The last `:verify` delta at or before `at-id` (the one governing that
  point), or nil."
  [store at-id]
  (let [upto (reduce (fn [acc d]
                       (let [acc (conj acc d)]
                         (if (= at-id (:id d)) (reduced acc) acc)))
                     [] (store/deltas store))]
    (last (filter #(= :verify (:op %)) upto))))

(defn query-status-at
  "was-green-at: the project's verification state that GOVERNED delta `at`
  (a delta id, or a commit-point id → its target) — the last `:verify` at or
  before it. Returns {:at :status (:green|:red|:unknown) :verify <delta-id>}
  or {:error} for an unknown delta."
  [session & {:keys [at]}]
  (let [st (:store @session)]
    (cond
      (nil? at)              {:error "query-status-at needs :at"}
      (nil? (resolve-at st at)) {:error (str "no delta " at
                                             " in this branch's history")}
      :else (let [rid (resolve-at st at)]
              (cond-> {:at rid :status (status-at st rid)}
                (verify-at st rid) (assoc :verify (:id (verify-at st rid))))))))

(defn- fid-ns-at
  "form-id → owning namespace as of delta `at-id`, folded from the log (each
  content delta carries its `:ns` and the form-ids it touched). Lets
  time-travel disambiguate same-named forms in different namespaces at a
  PAST point, without depending on the current store's membership."
  [store at-id]
  (reduce (fn [m d]
            (let [m (reduce #(assoc %1 %2 (:ns d)) m (delta-fids d))]
              (if (= at-id (:id d)) (reduced m) m)))
          {} (store/deltas store)))

(defn query-form-at
  "Time-travel: form `nm` in `ns-sym` as its SOURCE stood at delta `at` (a
  delta id, or a commit-point id → its target). Returns
  {:ns :name :at :source :status} — `:status` is the project's verification
  state that governed that point (was-green-at) — or {:error}. Names are
  resolved AT that delta (so a form that was later renamed still answers to
  the name it had then). The form's source is stored verbatim per version,
  so this is exact, not reconstructed."
  [session ns-sym nm & {:keys [at]}]
  (let [st (:store @session)]
    (cond
      (nil? at)
      {:error "query-form-at needs :at (a delta id or a commit-point id)"}

      (nil? (resolve-at st at))
      {:error (str "no delta " at " in this branch's history")}

      :else
      (let [rid   (resolve-at st at)
            srcs  (store/sources-at st rid)
            ns-of (fid-ns-at st rid)
            fid   (some (fn [[fid src]]
                          (when (and (= ns-sym (get ns-of fid))
                                     (= (str nm) (str (store/name-of-source src))))
                            fid))
                        srcs)]
        (if fid
          {:ns ns-sym :name nm :at rid :source (get srcs fid)
           :status (status-at st rid)}
          {:error (str nm " was not present in " ns-sym " at " rid)})))))

^:reads (defn- git-config-value
  "`git config <k>` as git would resolve it in `dir` (local then global), or
  nil. The \"<git>\" fallback of the G5 author config."
  [dir k]
  (let [r (sh/sh "git" "-C" (str dir) "config" k)]
    (when (zero? (:exit r))
      (let [v (str/trim (:out r))]
        (when-not (str/blank? v) v)))))
^:reads (defn- author-identity
  "The author identity milestones are stamped with (G5): meta `user.name` /
  `user.email`; a key that is unset or \"<git>\" defers to `git config` in
  the project dir. Nil when nothing resolves (the projection then falls back
  to the legacy agent identity). Durable sessions only."
  [session]
  (when-let [conn (:db @session)]
    (let [dir (:dir @session)
          res (fn [k]
                (let [v (db/get-meta conn k)]
                  (if (or (nil? v) (= v "<git>"))
                    (git-config-value dir k)
                    v)))
          nm  (res "user.name")
          em  (res "user.email")]
      (when (and nm em)
        {:name nm :email em}))))
(defn- modules-config-entry
  "The module manifest PROJECTED as a structured-config entry — how the
  edge fold becomes a `modules` file in git commits and builds (read-only
  transparency; writes go through module_dep)."
  [store]
  (when (seq (:modules store))
    {:format :manifest
     :values (into (sorted-map)
                   (map (fn [[m ds]] [m (clojure.string/join " " (sort ds))]))
                   (:modules store))}))
(defn commit-point!
  "Record a MILESTONE (P4-m7): run the full done pipeline (normalize,
  declare hygiene, verify) for `:agent`, then append a `:commit` marker
  pointing at the resulting state with a human `description`. GREEN-GATED:
  a red verification refuses the milestone (the done still stands —
  fix and retry) unless `:force true`, which records `:status :red`
  honestly. Re-requesting a milestone on an UNCHANGED store returns the
  existing marker instead of minting an empty one. With `:target` (a past
  delta id) it is a pure retroactive marker: no done runs, status is
  derived from the log at that spot — and no `:tree` snapshot is captured
  (the live store is past the target by definition; projection backfills,
  lossily). `:extra` merges op-specific payload into the marker delta
  (P4-m8 uses it for `:git-sha` on imported commits)."
  [session description & {:keys [agent force target extra]}]
  (let [mark! (fn [target status result-extra delta-extra]
                (let [v (volatile! nil)]
                  (commit-appended!
                   session
                   (fn [base]
                     (let [[st2 d] (store/record-commit base description
                                                        :agent agent
                                                        :target target
                                                        :status status
                                                        :extra (if-let [au (author-identity session)]
                                                                 (assoc delta-extra :author au)
                                                                 delta-extra))]
                       (vreset! v d)
                       st2))
                   [])
                  (merge {:commit (:id @v) :target target :status status
                          :description description}
                         result-extra)))]
    (cond
      (str/blank? (str description))
      {:error "a commit point needs a human-facing :description"}

      target
      (if (some #(= target (:id %)) (store/deltas (:store @session)))
        (mark! target (status-at (:store @session) target) {} extra)
        {:error (str "no delta " target " in this branch's history")})

      :else
      (let [last-d (last (store/deltas (:store @session)))]
        (if (= :commit (:op last-d))
          (merge {:commit (:id last-d) :target (:target last-d)
                  :status (:status last-d)
                  :description (:description last-d)
                  :note "nothing changed since this milestone — returning it"})
          (let [cp     (done! session :label description :agent agent)
                st     (:store @session)
                head   (:id (last (store/deltas st)))
                status (if-let [t (:test cp)]
                         (if (zero? (+ (:fail t 0) (:error t 0))) :green :red)
                         (status-at st head))
                status (if (= :unknown status) :green status) ; nothing ever ran red
                ;; P4-m8: snapshot the rendered tree — byte-exact, trivia intact —
                ;; so the git projection is a pure function of this marker delta
                tree   (into (sorted-map)
                             (map (fn [n] [n (render/render-ns st n)]))
                             (keys (:namespaces st)))]
            (if (and (= :red status) (not force))
              {:error (str "verification is RED — milestone refused (your work is "
                           "at its done-point; fix and retry, or :force true to record "
                           "a red milestone honestly)")
               :status :red :done (:done cp) :test (:test cp)}
              (mark! head status {:done (:done cp)}
                     (cond-> (merge {:tree tree} extra)
                       (seq (:deps st))  (assoc :deps (:deps st))
                       (seq (:files st)) (assoc :files (:files st))
                       (or (seq (:config st)) (modules-config-entry st))
                            (assoc :config (cond-> (:config st)
                                             (modules-config-entry st)
                                             (assoc "modules" (modules-config-entry st)))))))))))))

^:reads (defn query-commits
  "Milestones, newest first:
  [{:commit :description :target :status :agent :at :sha}]. Commit `:target`
  ids plug straight into query-changes :from/:to for between-milestone
  diffs. `:sha` (P4-m8) is the milestone's git commit id — present once the
  git projection has minted it (imported markers carry theirs from birth)."
  [session]
  (let [{:keys [dir]} @session
        shas (when dir
               (try (with-open [conn (db/open! dir)]
                      (db/commit-shas conn))
                    (catch Exception _ nil)))]
    (->> (store/deltas (:store @session))
         (filter #(= :commit (:op %)))
         reverse
         (mapv (fn [d]
                 (cond-> {:commit      (:id d)
                          :description (:description d)
                          :target      (:target d)
                          :status      (:status d)
                          :at          (human-time (:at d))}
                   (:agent d) (assoc :agent (:agent d))
                   (or (:git-sha d) (get shas (:id d)))
                   (assoc :sha (or (:git-sha d) (get shas (:id d))))))))))

;; ---------------------------------------------------------------------------
;; External dependencies (Tier 1) — the per-store manifest

(defn- analyze-dep!
  "Compute (or reuse the cached) API surface for `lib`@`coord` (M4) —
  best-effort: surface analysis must never fail a deps-add. Persists to the
  durable `dep_surface` cache when the session has a db; the process-level
  memo in `slopp.deps` covers ephemeral sessions. Returns the surface or nil."
  [session lib coord]
  (try
    (let [conn (:db @session)
          id   (deps/coord-key lib coord)]
      (if-let [cached (some-> conn (db/get-dep-surface id))]
        cached
        (let [jars (deps/dep-jars lib coord)                  ; resolve once
              surf (deps/surface jars)]
          (when conn
            (db/put-dep-surface! conn id surf)
            (db/put-dep-native! conn id (deps/native-verdict jars)))  ; M6
          surf)))
    (catch Throwable _ nil)))

(defn- dep-native-verdict
  "The cached (or freshly-computed) GraalVM native-image verdict for a
  dependency (M6). Best-effort; nil on failure."
  [session lib coord]
  (let [conn (:db @session)
        id   (deps/coord-key lib coord)]
    (or (some-> conn (db/get-dep-native id))
        (try (deps/native-verdict (deps/dep-jars lib coord))
             (catch Throwable _ nil)))))

(def ^:private native-incompatible-deps
  "Dependencies KNOWN to break GraalVM native-image (extensible, deliberately
  tiny — a build refuses these without `:force`). Empty for now; a missing
  reachability manifest is only a WARN, not a hard incompatibility."
  #{})

(defn deps-add!
  "Declare external dependency `lib` (a symbol like `org.clojure/data.json`)
  at `coord` (a deps.edn coordinate map, e.g. `{:mvn/version \"2.5.0\"}`).
  Records a `:deps-add` delta (materialized to the store's manifest), then
  HOT-adds the jar to the running image via add-libs — no restart; on failure
  it restarts. Returns {:added lib :coord :hot true|:restarted true} | {:error}."
  [session lib coord & {:keys [agent prompt]}]
  (cond
    (not (symbol? lib))
    {:error "dependency lib must be a symbol like org.clojure/data.json"}
    (not (and (map? coord) (seq coord)))
    {:error "dependency coord must be a non-empty map like {:mvn/version \"1.2.3\"}"}
    :else
    (let [surf (analyze-dep! session lib coord)]                 ; M4: API surface
      (commit-appended! session
                        #(first (store/record-deps-add
                                 % lib coord :agent agent :prompt prompt
                                 :namespaces (:namespaces surf)))  ; M3: dep-ns index
                        [])
      (let [base (cond-> {:added lib :coord coord}
                   surf (assoc :namespaces (vec (:namespaces surf))
                               :vars (count (:vars surf))))]
        (if-let [hot (repl/add-libs! (:image @session) {lib coord})]
          (do (fresh-image! session)            ; hot add failed → faithful restart
              (assoc base :restarted true :note (:err hot)))
          (assoc base :hot true))))))

(defn deps-remove!
  "Drop external dependency `lib` from the manifest. A jar can't be unloaded,
  so this always restarts the image. Returns {:removed lib :restarted true}
  or {:error}."
  [session lib & {:keys [agent prompt]}]
  (if-not (contains? (:deps (:store @session)) lib)
    {:error (str lib " is not a declared dependency")}
    (do
      (commit-appended! session
                        #(first (store/record-deps-remove % lib
                                                          :agent agent :prompt prompt))
                        [])
      (fresh-image! session)
      {:removed lib :restarted true})))

(defn deps-list
  "The store's external dependency manifest: {lib coord}."
  [session]
  (:deps (:store @session)))

(defn- record-pure!
  "Mark each of `syms` pure/un-pure in ONE appended commit (N :deps-pure deltas)."
  [session syms pure? {:keys [agent prompt]}]
  (commit-appended! session
                    (fn [base]
                      (reduce (fn [s x]
                                (first (store/record-deps-pure s x pure?
                                                               :agent agent :prompt prompt)))
                              base syms))
                    []))

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
  (content-addressed; wrap/unwrap are just 'new subform containing/omitting
  the old'). With `:text true` the match is RAW TEXT instead — the escape
  hatch for string literals and docstrings. With `:where {k v ...}` the
  target is the unique MAP containing those entries (registry-style edits
  by key, no exact text needed) and `match` is ignored. Rides the full
  replace pipeline: dialect gate on the RESULTING form, rebase/conflict
  commit, verification, provenance."
  [session ns-sym form-name match new-src & {:keys [prompt agent text where]}]
  (let [plan (cond
               (seq where) (refactor/keyed-replace-plan (:store @session) ns-sym
                                                        form-name where new-src)
               text        (refactor/text-replace-plan (:store @session) ns-sym
                                                       form-name match new-src)
               :else       (refactor/subform-replace-plan (:store @session) ns-sym
                                                          form-name match new-src))]
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
  (let [hist (query-form-history session ns-sym nm)]
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

(defn revert-episode!
  "Scrap the agent's episode: roll every form it changed since its last
  done back to the boundary state — as ONE atomic verified group
  (honest provenance, not history erasure). Forms that OTHER agents also
  touched since the boundary are SKIPPED and reported in :skipped-shared,
  never stomped."
  [session & {:keys [agent prompt]}]
  (let [changes  (query-changes session :agent agent)
        others   (into #{}
                       (mapcat delta-fids)
                       (filter #(and (contains? content-ops (:op %))
                                     (not= agent (:agent %)))
                               (episode-span (:store @session) agent)))
        {shared true mine false} (group-by #(contains? others (:form-id %))
                                           (:forms changes))
        steps    (vec (keep (fn [{:keys [form status was]}]
                              (when (namespace form)   ; anonymous forms: skip
                                (let [ns-sym (symbol (namespace form))
                                      nm     (symbol (name form))]
                                  (case status
                                    :modified {:action :replace :ns ns-sym
                                               :name nm :source was}
                                    :added    {:action :delete :ns ns-sym
                                               :name nm}
                                    :deleted  {:action :add :ns ns-sym
                                               :source was}))))
                            mine))]
    (cond
      (empty? (:forms changes))
      {:reverted 0 :note "episode is empty — already at the last done"}

      (empty? steps)
      {:reverted 0 :skipped-shared (mapv :form shared)
       :note "every changed form is shared with other agents"}

      :else
      (let [r (edit-group! session steps
                           :prompt (or prompt
                                       (str "revert episode"
                                            (when agent (str " of " agent))))
                           :agent agent)]
        (if (:error r)
          r
          (assoc r
                 :reverted (count steps)
                 :skipped-shared (mapv :form shared)))))))

(defn- rename-in-trace
  "Carry the observed test→form map across a rename (old qsym → new qsym)."
  [tmap qold qnew]
  (into {}
        (map (fn [[t forms]]
               [(if (= t qold) qnew t)
                (into #{} (map #(if (= % qold) qnew %)) forms)]))
        tmap))

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
      (let [changeset    (refactor/rename-changeset st ns-sym old-name new-name)
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
        (if-let [err (:err (hot-load-all! session st' ordered-ids))]
          {:error (str "rename failed to compile: " err)}
          (if-not (try-commit! session st st' (vec touched-nses))
            {:conflict {:reason "store changed during rename — retry"}}
            (do
              (do (swap! session update :test-map rename-in-trace qold qnew)
                     (persist-trace! session))
              (repl/eval! (:image @session)
                          (format "(ns-unmap '%s '%s)" ns-sym old-name))
              (let [summary (run-verification! session ns-sym affected
                                               :edited changed-syms)]
                (commit-appended! session
                                  #(store/record-verification % ns-sym summary)
                                  [])
                (let [pat      (re-pattern (str "\\b" (java.util.regex.Pattern/quote (str old-name)) "\\b"))
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
  [session ns-sym from new-name subform-src & {:keys [prompt]}]
  (let [st   (:store @session)
        plan (refactor/extract-plan st ns-sym from subform-src new-name)]
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
                [st2 d2]  (store/move-form st1 ns-sym new-name from
                                           :prompt prompt :group gid)
                [st3 d3]  (store/replace-node st2 ns-sym from (:node pf)
                                              :prompt prompt :group gid)]
            (if-let [err (:err (hot-load-all! session st3
                                              [(:form-id d1) (:form-id d3)]))]
              {:error (str "extract failed to compile: " err)}
              (if-not (try-commit! session st st3 [ns-sym])
                {:conflict {:reason "store changed during extract — retry"}}
                (do (let [affected (affected-tests session ns-sym from)
                          summary  (run-verification! session ns-sym affected
                                                      :edited
                                                      #{(symbol (str ns-sym) (str from))
                                                        (symbol (str ns-sym) (str new-name))})]
                      (commit-appended! session
                                        #(store/record-verification % ns-sym summary)
                                        [])
                      {:extracted {:new    (symbol (str ns-sym) (str new-name))
                                   :params (:params plan)}
                       :group    gid
                       :test     summary
                       :affected (or affected :all)}))))))))))

(defn ns-rename!
  "Rename a WHOLE namespace: its ns decl, every require clause, and every
  fully-qualified reference across the store; the namespaces map rekeys; the
  image rebuilds fresh (old name gone); everything re-verifies."
  [session old new & {:keys [prompt agent]}]
  (let [st  (:store @session)
        old (symbol (str old)) new (symbol (str new))]
    (cond
      (nil? (get-in st [:namespaces old]))
      {:error (str "no namespace " old)}

      (get-in st [:namespaces new])
      {:error (str new " already exists")}

      :else
      (let [changeset (refactor/ns-rename-changeset st old new)
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
        (if-not (try-commit! session st st2 touched)
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
            (let [old-mod (edit/module-of old)
                  new-mod (edit/module-of new)
                  why     (str "manifest follows ns rename " old " → " new)]
              (when (and (not= old-mod new-mod)
                         (not-any? #(= old-mod (edit/module-of %))
                                   (keys (:namespaces (:store @session)))))
                (commit-appended!
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
            (fresh-image! session)          ; the old ns must NOT linger
            (let [verify-nses (vec (remove #{old} touched))
                  summary (run-verification! session verify-nses nil
                                             :edited (into #{}
                                                           (keep (fn [id]
                                                                   (when-let [e (store/form-by-id st2 id)]
                                                                     (symbol (str (store/ns-of-form-id st2 id))
                                                                             (str (or (:name e) (:id e)))))))
                                                           (keys changeset)))]
              (commit-appended! session
                                #(store/record-verification % verify-nses summary)
                                [])
              {:renamed {:old old :new new :forms (count changeset)}
               :delta (:id delta)
               :test summary})))))))

(defn extract-ns!
  "clj-surgeon's :extract!, slopp-grade: move `form-names` from `from-ns`
  into BRAND-NEW `new-ns` — new ns created with from-ns's requires copied
  (over-include is safe), remaining callers REWRITTEN to alias-qualified
  calls, the require added, moved forms removed — one atomic group,
  compile-gated, verified across both namespaces.
  Guards: the moved set may not call what stays behind (move those too), and
  nothing outside `from-ns` may reference the moved forms (v1)."
  [session from-ns form-names new-ns & {:keys [prompt agent]}]
  (let [st     (:store @session)
        moved  (set (map symbol form-names))
        missing (remove #(store/form-named st from-ns %) moved)]
    (cond
      (get-in st [:namespaces new-ns])
      {:error (str new-ns " already exists")}

      (seq missing)
      {:error (str "no such forms in " from-ns ": " (vec missing))}

      :else
      (let [adj      (callee-adjacency st)
            moved-q  (set (map #(symbol (str from-ns) (str %)) moved))
            ;; moved forms calling what STAYS
            stays    (for [m moved-q
                           c (get adj m [])
                           :when (and (= (str from-ns) (namespace c))
                                      (not (moved-q c))
                                      (store/form-named st from-ns
                                                        (symbol (name c))))]
                       (symbol (name c)))
            ;; anything OUTSIDE from-ns referencing the moved forms
            external (for [[q cs] adj
                           :when (not= (str from-ns) (namespace q))
                           c cs
                           :when (moved-q c)]
                       q)]
        (cond
          (seq stays)
          {:error (str "moved forms still call what stays behind — move these "
                       "too or keep them: " (vec (distinct stays)))}

          (seq external)
          {:error (str "referenced outside " from-ns " (v1 limit): "
                       (vec (distinct external)))}

          :else
          (let [alias*   (symbol (last (clojure.string/split (str new-ns) #"\.")))
                ns-decl  (store/form-named st from-ns from-ns)
                requires (let [sx (n/sexpr (:node ns-decl))
                               reqs (rest (first (filter #(and (seq? %)
                                                               (= :require (first %)))
                                                         sx)))]
                           reqs)
                new-src  (str "(ns " new-ns
                              (when (seq requires)
                                (str "
  (:require "
                                     (clojure.string/join "
            "
                                                          (map pr-str requires))
                                     ")"))
                              ")

"
                              (clojure.string/join "

"
                                                   (for [f (store/forms st from-ns)
                                                         :when (moved (:name f))]
                                                     (n/string (:node f))))
                              "
")
                [gid st0] (store/alloc-id st "g")
                st1 (store/ingest st0 new-ns new-src :agent agent)
                ;; rewrite remaining callers: bare moved names → alias/name
                mapper (fn [sym]
                         (when (and (nil? (namespace sym)) (moved sym))
                           (symbol (str alias*) (str sym))))
                rewrites (into {}
                               (for [f (store/forms st1 from-ns)
                                     :when (and (:name f)
                                                (not (moved (:name f)))
                                                (not= from-ns (:name f)))
                                     :let [node' (refactor/rewrite-symbols
                                                  (:node f) mapper)]
                                     :when (not= (n/string node')
                                                 (n/string (:node f)))]
                                 [(:id f) node']))
                ;; require the new ns from from-ns
                req-r (edit/add-require-source (n/string (:node ns-decl))
                                               (str "[" new-ns " :as " alias* "]"))
                st2 (if (:error req-r)
                      st1
                      (or (first (store/replace-node st1 from-ns from-ns
                                                     (first (n/children
                                                             (rewrite-clj.parser/parse-string-all
                                                              (:src req-r))))
                                                     :prompt prompt :group gid
                                                     :agent agent))
                          st1))
                [st3 _] (if (seq rewrites)
                          (store/apply-changeset st2 :extract-ns from-ns rewrites
                                                 :prompt prompt :agent agent)
                          [st2 nil])
                st4 (reduce (fn [st* nm]
                              (or (first (store/remove-form st* from-ns nm
                                                            :prompt prompt
                                                            :group gid :agent agent))
                                  st*))
                            st3 moved)
                load-err (or (image/load-ns! (:image @session) st4 new-ns)
                             (:err (hot-load-all!
                                    session st4
                                    (concat [(:id ns-decl)] (keys rewrites)))))]
            (if load-err
              (do (fresh-image! session)
                  {:error (str "extract-ns failed to compile: " load-err)})
              (if-not (try-commit! session st st4 [from-ns new-ns])
                {:conflict {:reason "store changed during extract-ns — retry"}}
                (do (doseq [nm moved]
                      (repl/eval! (:image @session)
                                  (format "(ns-unmap '%s '%s)" from-ns nm)))
                    (let [summary (run-verification! session [from-ns new-ns] nil
                                                     :edited (into moved-q
                                                                   (map #(symbol (str new-ns) (str %)))
                                                                   moved))]
                      (commit-appended! session
                                        #(store/record-verification
                                          % [from-ns new-ns] summary)
                                        [])
                      {:extracted-to new-ns
                       :moved (vec (sort moved))
                       :rewrote (count rewrites)
                       :group gid
                       :test summary}))))))))))

(defn- merge-into-session!
  "Shared merge pipeline (m2 forks + m3 branches): replay `theirs` onto the
  session store (store/merge-logs), hot-load what arrived (new namespaces in
  dependency order, then changed forms through the compile gate), commit,
  persist, verify every touched namespace, and record ONE `:merge` delta."
  [session theirs from-label]
  (let [t0   (System/nanoTime)
        base (:store @session)
        r    (store/merge-logs base theirs :from from-label)]
    (cond
      (nil? (:fork-point r))
      {:error "stores share no history — not a fork/branch of this project"}

      (and (zero? (:merged r)) (empty? (:conflicts r)))
      {:merged 0 :conflicts [] :note "already converged — nothing to merge"}

      :else
      (let [st'      (:store r)
            load-err (or ;; cold-load first (S1b): two individually-legal lines can
                      ;; interleave into a forward ref — refuse before the
                      ;; image is touched
                      (edit/cold-load-errors
                       st'
                       (filter #(contains? (:namespaces st') %)
                               (distinct
                                (concat (keep :ns (drop (count (store/deltas base))
                                                        (store/deltas st')))
                                        (:new-nses r)))))
                      ;; new namespaces first, dependency order
                      (some (fn [ns-sym]
                              (when (contains? (set (:new-nses r)) ns-sym)
                                (image/load-ns! (:image @session) st' ns-sym)))
                            (store/ns-dependency-order st'))
                      ;; then every changed form (compile gate, heals)
                      (:err (hot-load-all! session st'
                                           (:changed-form-ids r))))]
        (if load-err
          (do (fresh-image! session)
              {:error (str "merge failed to compile: " load-err)})
          (let [[st'' mdelta] (store/record-merge st' from-label r)]
            (if-not (try-commit! session base st''
                                 (vec (distinct
                                       (concat (keep :ns (drop (count (store/deltas base))
                                                               (store/deltas st'')))
                                               (:new-nses r)))))
              {:conflict {:reason "store changed during merge — retry"}}
              (let [new-deltas   (drop (count (store/deltas base))
                                       (store/deltas st''))
                    touched-nses (vec (distinct
                                       (concat (keep :ns new-deltas)
                                               (:new-nses r))))
                    edited       (into #{}
                                       (keep (fn [id]
                                               (when-let [e (store/form-by-id st'' id)]
                                                 (symbol (str (store/ns-of-form-id st'' id))
                                                         (str (or (:name e) (:id e)))))))
                                       (:changed-form-ids r))
                    verify-nses  (vec (remove #{'*session*} touched-nses))
                    summary      (when (seq verify-nses)
                                   (run-verification! session verify-nses nil
                                                      :edited edited))]
                (when summary
                  (commit-appended! session
                                    #(store/record-verification % verify-nses summary)
                                    []))
                (with-ms
                  (cond-> {:merged     (:merged r)
                           :conflicts  (:conflicts r)
                           :merge-delta (:id mdelta)}
                    (seq (:new-nses r)) (assoc :new-nses (:new-nses r))
                    (seq (:notes r))    (assoc :notes (:notes r))
                    summary             (assoc :test summary))
                  t0)))))))))

(defn merge!
  "Phase 4 m2: merge a DIVERGED COPY of this project back into the live
  session. A 'fork' is just a copied project dir edited by its own slopp
  server; `other-dir` is that copy. Their delta-log suffix replays onto our
  store: different-form work lands, identical changes converge, same-form
  divergence returns `:conflicts` (ours kept, theirs surfaced — resolve by
  hand with edit_replace_form)."
  [session other-dir]
  (let [f    (io/file (str other-dir))
        db-f (io/file f ".slopp" "store.db")]
    (cond
      (not (.isAbsolute f))
      {:error "merge needs an ABSOLUTE project-dir path"}

      (not (.exists db-f))
      {:error (str "no slopp store under " other-dir)}

      :else
      (let [conn   (db/open! (str f))
            theirs (try (db/load-store conn)
                        (finally (.close ^java.sql.Connection conn)))]
        (merge-into-session! session theirs (str other-dir))))))

;; --- Phase 4 m3: branches within one repo -------------------------------

(defn- line-dir
  "Where a branch line persists in a durable session."
  [dir nm]
  (str (io/file dir ".slopp" "branches" nm)))

(defn- snapshot-to-conn!
  "Full-store snapshot into a (fresh) branch db: every delta + all elements."
  [conn store]
  (let [ds   (store/deltas store)
        nses (vec (keys (:namespaces store)))]
    (doseq [d (butlast ds)] (db/persist! conn store d []))
    (when-let [d (last ds)] (db/persist! conn store d nses))))

(defn- delete-dir! [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))

^:reads (defn- load-line
  "An inactive line's {:store :conn}: from memory, or lazily from its branch
  db in a durable session. nil if unknown."
  [session nm]
  (let [{:keys [lines dir]} @session]
    (or (get lines nm)
        (when (and dir (.exists (io/file (line-dir dir nm) ".slopp" "store.db")))
          (let [c (db/open! (line-dir dir nm))]
            {:store (db/load-store c) :conn c})))))

(defn branch!
  "Phase 4 m3: create branch `nm` from the CURRENT line's state and switch to
  it — O(1), the store is a value; the image is already correct (identical
  content). Durable sessions snapshot the line under .slopp/branches/<nm>."
  [session nm]
  (let [nm (str nm)
        {:keys [branch lines dir]} @session]
    (cond
      (str/blank? nm)
      {:error "branch needs a name"}

      (= nm "main")
      {:error "main is the trunk — branch FROM it"}

      (or (= nm branch)
          (contains? lines nm)
          (and dir (.exists (io/file (line-dir dir nm)))))
      {:error (str "branch " nm " already exists")}

      :else
      ;; claim the name atomically: in-process via the lines map, and
      ;; cross-process via mkdir (fails if the dir exists)
      (let [[old _] (swap-vals! session
                                (fn [s]
                                  (if (or (= nm (:branch s))
                                          (contains? (:lines s) nm))
                                    s
                                    (update s :lines assoc nm ::claimed))))]
        (if (or (= nm (:branch old)) (contains? (:lines old) nm))
          {:error (str "branch " nm " already exists")}
          (let [bdir (when dir (io/file (line-dir dir nm)))]
            (when bdir (.mkdirs (.getParentFile bdir)))
            (if (and bdir (not (.mkdir bdir)))
              (do (swap! session update :lines dissoc nm)   ; release the claim
                  {:error (str "branch " nm " already exists")})
              (let [line-id (str (java.util.UUID/randomUUID))
                    conn    (when dir
                              (doto (db/open! (line-dir dir nm))
                                (snapshot-to-conn! (:store @session))
                                (db/set-line-id! line-id)))]
                (swap! session
                       (fn [s]
                         (-> s
                             (update :lines dissoc nm)      ; claim → active
                             (update :lines assoc (:branch s)
                                     {:store (:store s) :conn (:db s)})
                             (assoc :branch nm :db conn
                                    :store (assoc (:store s) :line-id line-id)
                                    :data-version (some-> conn db/data-version)))))
                {:branch nm :from branch :id line-id}))))))))

(defn- boot-line-image!
  "A fresh image loaded with `store` (consumes the warm spare when ready).
  Returns {:image handle} or {:error msg}."
  [session store]
  (let [spare (:spare @session)
        img   (image-with-deps! spare (:deps store))]
    (when spare
      (swap! session assoc :spare nil)
      (start-spare! session))
    (if-let [err (some #(image/load-ns! img store %)
                       (store/ns-dependency-order store))]
      (do (repl/stop! img)
          {:error (str "branch image failed to load: " err)})
      {:image img})))

(defn branch-switch!
  "Checkout with LINE-OWNED images (m4): the outgoing line PARKS its image
  intact (its REPL state included — inactive lines are immutable, so a parked
  image stays in step by construction); the target ADOPTS its parked image if
  it still has one, else BOOTS a fresh one on demand (the warm spare makes
  that cheap). Parked images retire after the session's idle TTL. The trace
  map resets — it described the other line."
  [session nm]
  (let [nm (str nm)]
    (if (= nm (:branch @session))
      {:switched nm :note "already on it"}
      (if-let [target (load-line session nm)]
        (do (let [adopted (:image target)
                  booted  (when-not adopted
                            (boot-line-image! session (:store target)))]
              (if (:error booted)
                booted
                (do (swap! session
                           (fn [s]
                             (-> s
                                 (update :lines assoc (:branch s)
                                         {:store     (:store s)
                                          :conn      (:db s)
                                          :image     (:image s)
                                          :last-used (System/currentTimeMillis)})
                                 (update :lines dissoc nm)
                                 (assoc :branch nm
                                        :db (:conn target)
                                        :store (:store target)
                                        :data-version (some-> (:conn target)
                                                              db/data-version)
                                        :image (or adopted (:image booted))
                                        :test-map {}))))
                    (cond-> {:switched nm}
                      adopted       (assoc :adopted true)
                      (not adopted) (assoc :booted true))))))
        {:error (str "no branch named " nm)}))))

(defn branch-merge!
  "Merge branch `nm` into the CURRENT line (switch to main first to merge
  down). Same engine and semantics as fork merges, iterated merges included;
  the branch survives and can keep going."
  [session nm]
  (let [nm (str nm)]
    (if (= nm (:branch @session))
      {:error "cannot merge a branch into itself — switch to the target line first"}
      (if-let [target (load-line session nm)]
        (let [res (merge-into-session! session (:store target)
                                       (str "branch:" nm "#"
                                            (or (:line-id (:store target))
                                                "legacy")))]
          ;; lazily-opened conn is only needed for reading here
          (when (and (:conn target)
                     (not (contains? (:lines @session) nm)))
            (.close ^java.sql.Connection (:conn target)))
          res)
        {:error (str "no branch named " nm)}))))

(defn branch-delete!
  "Drop branch `nm` (never the one you are on). Durable sessions also remove
  its .slopp/branches dir."
  [session nm]
  (let [nm (str nm)
        {:keys [branch lines dir]} @session]
    (cond
      (= nm branch)
      {:error "cannot delete the branch you are on"}

      (not (or (contains? lines nm)
               (and dir (.exists (io/file (line-dir dir nm))))))
      {:error (str "no branch named " nm)}

      :else
      (do (some-> (get-in lines [nm :image]) repl/stop!)
          (some-> (get-in lines [nm :conn])
                  ^java.sql.Connection (.close))
          (swap! session update :lines dissoc nm)
          (when dir (delete-dir! (io/file (line-dir dir nm))))
          {:deleted nm}))))

(defn query-branches
  "Every line in the repo: the current one, in-memory lines, and (durable)
  on-disk branches not yet loaded this session."
  [session]
  (let [{:keys [branch lines dir store]} @session
        on-disk (when dir
                  (let [bdir (io/file dir ".slopp" "branches")]
                    (when (.exists bdir)
                      (map #(.getName ^java.io.File %)
                           (filter #(.isDirectory ^java.io.File %)
                                   (.listFiles bdir))))))
        info    (fn [nm st line]
                  (cond-> {:name nm}
                    st (assoc :head   (:id (last (store/deltas st)))
                              :deltas (count (store/deltas st)))
                    (:line-id st) (assoc :id (:line-id st))
                    (:image line) (assoc :image :parked)))]
    {:current  branch
     :branches (vec (concat
                     [(assoc (info branch store nil) :image :live)]
                     (for [[nm line] (sort-by key lines)]
                       (info nm (:store line) line))
                     (for [nm (sort (remove (set (conj (keys lines) branch))
                                            (or on-disk [])))]
                       {:name nm})))}))

(defn build!
  "C1/C6 explicit build: materialize a runnable project under `dir` —
  `src/<ns-path>.clj` per namespace plus a minimal `deps.edn` (F8). Guarded
  (X4: an eval agent once built into the host repo, clobbering its deps.edn):
  absolute paths only, never a directory enclosing the running process, and a
  deps.edn this build didn't generate is never overwritten.

  With `:main` (a qualified entry fn, e.g. 'calc.core/run-cli) also emits the
  native-binary recipe (O4): a generated gen-class launcher at
  src/native/main.clj, a `:native` deps alias, and an executable
  build-native.sh that GraalVM-compiles the project to a self-contained
  binary `:name` (default: the entry ns's first segment)."
  [session dir & {:keys [main force] bin-name :name}]
  (let [f        (io/file dir)
        target   (.getCanonicalFile f)
        cwd      (.getCanonicalFile (io/file "."))
        st       (:store @session)
        de       (io/file target "deps.edn")
        deps     (:deps st)
        has-tests? (boolean (or (some render/test-ns? (keys (:namespaces st)))
                                (some (fn [nsx]
                                        (some #(re-find #"^\(deftest\b"
                                                        (n/string (:node %)))
                                              (store/forms st nsx)))
                                      (keys (:namespaces st)))))
        incompat (when main (seq (filter native-incompatible-deps (keys deps))))
        ;; a deps.edn is ours iff it's byte-identical to a generated variant
        ;; (for THIS store's manifest + test layout — else it reads as foreign)
        ours?    #(contains? #{(build/deps-edn false deps has-tests?)
                               (build/deps-edn true deps has-tests?)}
                             (slurp de))
        entry-ns (some-> main namespace symbol)]
    (cond
      (not (.isAbsolute f))
      {:error "build needs an ABSOLUTE directory path"}

      (.startsWith (.toPath cwd) (.toPath target))
      {:error (str "refusing to build into " target
                   " — it contains the running system")}

      (and main (nil? entry-ns))
      {:error (str ":main must be a qualified entry fn (ns/name), got " main)}

      (and main (nil? (store/form-named st entry-ns (symbol (name main)))))
      (edit/missing-form-error st entry-ns (symbol (name main)))

      (and main (get-in st [:namespaces 'native.main]))
      {:error "a store namespace named native.main collides with the generated launcher"}

      (and main (.exists de) (not (ours?)))
      {:error (str target "/deps.edn exists and wasn't generated by build! — "
                   "the native recipe must own it; build into a fresh directory")}

      (and incompat (not force))
      {:error (str "refusing a native build: dependencies known to break "
                   "GraalVM native-image: " (str/join ", " incompat)
                   " (pass :force true to build anyway)")
       :native-incompatible (vec incompat)}

      :else
      (do (doseq [ns-sym (keys (:namespaces st))]
    (let [file (io/file target (render/source-path ns-sym))]
      (io/make-parents file)
      (spit file (render/render-ns st ns-sym))))
  (doseq [[path text] (:files st)]
    (let [file (io/file target (str path))]
      (io/make-parents file)
      (spit file text)))
  (doseq [[path entry] (cond-> (:config st)
                               (modules-config-entry st)
                               (assoc "modules" (modules-config-entry st)))]
    (let [file (io/file target (str path))]
      (io/make-parents file)
      (spit file (store/render-config entry))))
          (when (or main (not (.exists de)))
            (do (when has-tests? (.mkdirs (io/file target "test")))
                (spit de (build/deps-edn (boolean main) deps has-tests?))))
          (cond-> {:built (str target)}
            main
            (assoc :native
                   (let [an    (index/analyze (render/render-ns st entry-ns))
                         vdef  (first (filter #(and (= entry-ns (:ns %))
                                                    (= (symbol (name main)) (:name %)))
                                              (:var-definitions an)))
                         bin   (or bin-name (first (str/split (str entry-ns) #"\.")))
                         launcher (io/file target "src" "native" "main.clj")
                         script   (io/file target "build-native.sh")]
                     (io/make-parents launcher)
                     (spit launcher (build/launcher-source main (build/arg-style vdef)))
                     (spit script (build/native-script bin))
                     (.setExecutable script true false)
                     (let [warns (vec (for [[lib coord] deps
                                            :when (= :none (:verdict
                                                            (dep-native-verdict session lib coord)))]
                                        lib))]
                       (cond-> {:binary bin
                                :launcher "src/native/main.clj"
                                :script   "build-native.sh"}
                         ;; M6: deps with no reachability metadata may need
                         ;; a tracing-agent run before native-image succeeds
                         (seq warns)
                         (assoc :warnings
                                (str "no GraalVM reachability metadata for: "
                                     (str/join ", " warns)
                                     " — the native build may need a tracing-agent run")
                                :metadata-missing warns))))))))))
(defn parse-test-summary
  "Parse a clojure.test runner's terminal summary into
  {:ran :assertions :failures :errors :status}, or nil if none is present."
  [output]
  (when-let [[_ t a f e] (re-find
                          #"Ran (\d+) tests containing (\d+) assertions\.\s+(\d+) failures?, (\d+) errors?"
                          (str output))]
    (let [f (parse-long f) e (parse-long e)]
      {:ran (parse-long t) :assertions (parse-long a)
       :failures f :errors e
       :status (if (and (zero? f) (zero? e)) :green :red)})))
(defn parse-test-failures
  "The FAIL/ERROR blocks from a clojure.test runner's output:
  [{:test name :detail block}] (up to `limit` blocks, each capped ~500 chars)
  — so an isolated run NAMES its failures instead of making the caller
  rebuild the project and rerun the suite just to see them (Q2)."
  [output & {:keys [limit] :or {limit 5}}]
  (->> (str/split (str output) #"\n(?=(?:FAIL|ERROR) in )")
       (keep (fn [b]
               (when-let [[_ nm] (re-find #"^(?:FAIL|ERROR) in \(([^)\s]+)\)" b)]
                 (let [block (->> (str/split-lines b)
                                  (take-while (complement str/blank?))
                                  (str/join "\n"))]
                   {:test nm
                    :detail (if (< 500 (count block))
                              (str (subs block 0 500) " …")
                              block)}))))
       (take limit)
       vec))
(defn- failing-test-rollup
  "EVERY failing test name from a runner's output, grouped by file:
  {file [test-names]} — the :failing detail blocks are capped, so without
  this a many-failure run needs fix-rerun loops just to enumerate its
  fallout classes (measured: 50 failures × 5-block cap = four reruns)."
  [output]
  (->> (str/split (str output) #"\n(?=(?:FAIL|ERROR) in )")
       (keep (fn [b]
               (when-let [[_ nm] (re-find #"^(?:FAIL|ERROR) in \(([^)\s]+)\)" b)]
                 [(or (second (re-find #"\(([^()\s]+\.clj):" b)) "?") nm])))
       distinct
       (reduce (fn [m [f nm]] (update m f (fnil conj []) nm)) (sorted-map))))
(defn- failure-themes
  "Heuristic ROOT-CAUSE clusters for a red run: word 3-grams from the
  QUOTED strings inside each failure block (error messages carry the
  cause; expected/actual scaffolding is noise), ranked by how many
  distinct tests mention them (>=3), subset-covered grams dropped —
  '38 failures say does-not-declare' in one read instead of an
  enumerate-classify loop. Advisory; the blocks stay authoritative."
  [output]
  (let [blocks (keep (fn [b]
                       (when-let [[_ nm] (re-find #"^(?:FAIL|ERROR) in \(([^)\s]+)\)" b)]
                         [nm b]))
                     (str/split (str output) #"\n(?=(?:FAIL|ERROR) in )"))
        grams  (fn [b]
                 (let [quoted (map second (re-seq #"\"([^\"]+)\"" b))
                       ws     (mapcat #(re-seq #"[A-Za-z][A-Za-z-]{2,}" %) quoted)]
                   (distinct (map #(str/join " " %) (partition 3 1 ws)))))
        counts (reduce (fn [m [nm b]]
                         (reduce #(update %1 %2 (fnil conj #{}) nm) m (grams b)))
                       {} blocks)
        ranked (sort-by (fn [[g ts]] [(- (count ts)) g])
                        (filter #(>= (count (val %)) 3) counts))]
    (loop [rs ranked, seen [], out []]
      (if (or (empty? rs) (>= (count out) 5))
        out
        (let [[g ts] (first rs)]
          (if (some #(set/subset? ts %) seen)
            (recur (rest rs) seen out)
            (recur (rest rs) (conj seen ts)
                   (conj out {:phrase g :tests (count ts)}))))))))
(defn- affected-test-nses
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
     :selected     (test-nses-reaching st changed)}))
(defn isolated-test-run!
  "Run the STORE's test suite in a FRESH EXTERNAL JVM: materialize the store
  (build!) into a throwaway dir and shell `clojure -M<alias>` there — the
  out-of-process counterpart to in-image `traced-run`, and the ONLY tier that
  executes ^:isolated tests (they spawn their own images/subprocesses, so
  running them in-image would recurse). Needs no repo files — the store is
  the source, which is what lets the working dir go fileless. `:ns` narrows
  to one test namespace, `:only` to specific ns-qualified test vars (Q2 —
  targeted red/green loops without paying for the whole suite);
  `:affected true` narrows to the PROVABLE slice — test namespaces whose
  require-closure reaches a form changed since the last milestone (iterate
  on this; run the full suite at milestones). Returns {:isolated true
  :status :ran :assertions :failures :errors :exit} plus :failing
  [{:test :detail}] + :all-failing {file [tests]} + :themes (clustered
  root-cause phrases) when red (:output = last lines when the run didn't
  even produce a summary)."
  [session & {:keys [alias ns only affected]}]
  (let [aff   (when affected (affected-test-nses session))
        ;; narrowed runs need the filter-free alias: the :test alias bakes
        ;; -r \".*\" (inline tests, Q13) which UNIONS with -n and defeats it
        alias (or alias
                  (if (or ns aff (seq only)) ":test-run" ":test"))]
    (if (and aff (empty? (:selected aff)))
      {:isolated true :ran 0 :status :green :affected aff
       :note (str "no test namespace can reach the changes since the last"
                  " milestone — nothing to verify (run without affected for"
                  " the full gate)")}
      (let [dir (str (java.nio.file.Files/createTempDirectory
                      "slopp-isolated"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
            b   (build! session dir)]
        (if (:error b)
          b
          (let [args (cond-> [repl/clojure-bin (str "-M" alias)]
                       ns         (conj "-n" (str ns))
                       aff        (into (mapcat #(vector "-n" (str %))
                                                (:selected aff)))
                       (seq only) (into (mapcat #(vector "-v" (str %)) only)))
                r    (apply sh/sh (concat args [:dir dir]))
                out  (str (:out r) "\n" (:err r))
                s    (parse-test-summary out)]
            (merge {:isolated true :exit (:exit r)}
                   (when aff {:affected aff})
                   (cond
                     (nil? s)           {:status :error
                                         :output (->> (str/split-lines out)
                                                      (remove str/blank?)
                                                      (take-last 8) (str/join "\n"))}
                     (= :red (:status s)) (cond-> (assoc s
                                                         :failing (parse-test-failures out)
                                                         :all-failing (failing-test-rollup out))
                                            (seq (failure-themes out))
                                            (assoc :themes (failure-themes out)))
                     :else s))))))))
(defn config!
  "Read or set store config (the meta k/v side-table): keys `user.name` /
  `user.email` — the git author identity milestones are stamped with (G5).
  A key unset or set to \"<git>\" defers to `git config <key>` in the project
  dir, resolved AT MILESTONE TIME. With no `v`: read —
  {:key :configured :effective}. Durable sessions only."
  [session k & [v]]
  (let [allowed #{"user.name" "user.email" "git-remote"}]
    (cond
      (not (contains? allowed (str k)))
      {:error (str "unknown config key " k " — allowed: "
                   (str/join ", " (sort allowed)))}

      (not (:db @session))
      {:error "config lives in the durable store (this session has no db)"}

      (some? v)
      (do (db/set-meta! (:db @session) (str k) (str v))
          {:key (str k) :configured (str v)})

      :else
      (let [conf (db/get-meta (:db @session) (str k))]
        {:key (str k)
         :configured conf
         :effective (if (or (nil? conf) (= conf "<git>"))
                      (git-config-value (:dir @session) (str k))
                      conf)}))))
(defn file-put!
  "Track a NON-CODE file on the store's files manifest (README, .github
  workflows, …) — it rides every projected tree, so slopp pushes never
  delete it from the remote. Returns {:path :bytes}."
  [session path content & {:keys [prompt agent]}]
  (cond
    (str/blank? (str path))
    {:error "file_put needs a :path"}

    (nil? content)
    {:error "file_put needs :content"}

    :else
    (do (commit-appended! session
                          #(first (store/record-file-put % path content
                                                         :prompt prompt :agent agent))
                          [])
        {:path (str path) :bytes (count (str content))})))
(defn file-remove!
  "Drop `path` from the files manifest. Returns {:removed path} | {:error}."
  [session path & {:keys [prompt agent]}]
  (if-not (contains? (:files (:store @session)) (str path))
    {:error (str path " is not on the files manifest")}
    (do (commit-appended! session
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
(defn edit-trivia!
  "Replace the comment/blank-line run immediately before form `anchor`
  (nil = the namespace tail) with `text` — the trivia counterpart of
  edit_replace_form. Forms are untouched by construction, so there is no
  image work and no verification; the `:trivia` delta anchors on the form-id
  for foreign replay. Returns {:delta :before} | {:error} | {:conflict}."
  [session ns-sym anchor text & {:keys [prompt agent]}]
  (let [base (:store @session)
        r    (store/replace-trivia base ns-sym anchor text
                                   :prompt prompt :agent agent)]
    (if (:error r)
      r
      (let [[st' d] r]
        (if-not (try-commit! session base st' [ns-sym])
          {:conflict {:reason "store changed concurrently — retry"}}
          {:delta (:id d) :before (some-> anchor str) :ns (str ns-sym)})))))
^:reads (defn file-get
  "A manifest file's content — current, or as of a past delta via `:at`
  (a delta id or commit-point id resolves through its :target like
  query_form_at). Returns {:path :content} | {:error}."
  [session path & {:keys [at]}]
  (let [st (:store @session)]
    (if at
      (let [at-id (or (some (fn [d] (when (and (= :commit (:op d)) (= at (:id d)))
                                      (:target d)))
                            (store/deltas st))
                      at)
            c     (store/file-at st (str path) at-id)]
        (if (some? c)
          {:path (str path) :at at :content c}
          {:error (str path " has no content at " at)}))
      (if-let [c (get (:files st) (str path))]
        {:path (str path) :content c}
        {:error (str path " is not on the files manifest")}))))
^:reads (defn file-history!
  "Every tracked version of a manifest file, oldest first, with provenance —
  the file counterpart of query_form_history."
  [session path]
  (let [h (store/file-history (:store @session) (str path))]
    (if (seq h)
      {:path (str path) :versions h}
      {:error (str path " has never been tracked")})))
(defn- module-usage-rows
  "Every store-internal, kondo-resolved var-usage row ({:from-ns :from-var
  :to :to-export}) — ONE pass over the store shared by the debt view and
  the drift (declared-but-unused) view."
  [store]
  (let [nses (set (keys (:namespaces store)))]
    (vec (for [nsx (sort nses)
               u   (:var-usages (index/analyze (render/render-ns store nsx)))
               :when (contains? nses (:to u))]
           {:from-ns nsx :from-var (:from-var u) :to (:to u)
            :to-export (edit/export-level store (:to u) (:name u))}))))
(defn- module-debt
  "Whole-store module violations under the store's CURRENT manifest —
  compact rows, G13-capped — the debt a manifest change reveals (per-write
  gates block NEW violations; the advisory shows what already stands).
  Pass precomputed `rows` (module-usage-rows) to share the kondo pass."
  ([store] (module-debt store (module-usage-rows store)))
  ([store rows]
   (when-let [manifest (edit/modules-manifest store)]
     (let [vs (edit/module-violations manifest rows)]
       (when vs
         {:rows (vec (take 20 (map #(select-keys % [:from-ns :from-var :target-ns :rule]) vs)))
          :count (count vs)})))))
(defn config-file!
  "Structured config files: the store holds SEMANTIC key/values per path
  (per-key delta history, like forms); the projection serializes them into
  the file format (`:manifest` → sorted `K: V` lines). Set a key
  (`:key`+`:value`, `:format` on first touch, default :manifest), remove one
  (`:key`+`:unset true`), or read (path only: values + rendered preview).
  The module manifest is NOT a config file — module_dep is its verb."
  [session path & {:keys [key value unset format prompt agent]}]
  (let [entry (get-in (:store @session) [:config (str path)])]
    (cond
      (= "modules" (str path))
      {:error "the module manifest is edge-grain, not a file — declare or retract one dependency at a time: module_dep {from \"x.y\" to \"a.b\"} (add) or module_dep {from \"x.y\" to \"a.b\" remove true}; read it via query_depends {modules true}"}

      (and key unset)
      (if-not (get-in entry [:values (str key)])
        {:error (str key " is not set on " path)}
        (do (commit-appended! session
                              #(first (store/record-config-unset % path key
                                                                 :prompt prompt
                                                                 :agent agent))
                              [])
            {:path (str path) :unset (str key)}))

      (and key (some? value))
      (let [fmt (or (some-> format clojure.core/keyword)
                    (:format entry) :manifest)]
        (commit-appended! session
                          #(first (store/record-config-put % path fmt key value
                                                           :prompt prompt
                                                           :agent agent))
                          [])
        {:path (str path) :key (str key) :value (str value) :format fmt})

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
(defn query-flow
  "Rock 4: where a FIELD flows — every form using keyword `kw` (\":rush?\"),
  with the using lines. The cross-namespace thread an agent otherwise
  re-derives by reading each layer. Boundary-guarded textual scan over
  rendered forms — keyword-precise enough in practice; revisit with kondo
  keyword analysis if demand shows false hits."
  [session kw]
  (let [k   (str/replace (str kw) #"^:" "")
        pat (re-pattern (str "(?<![\\w.:-]):" (java.util.regex.Pattern/quote k)
                             "(?![\\w?!*+<>=-])"))
        st  (:store @session)]
    (->> (for [nsx (sort (keys (:namespaces st)))
               e   (store/forms st nsx)
               :let [lines (filterv #(re-find pat %)
                                    (str/split-lines (n/string (:node e))))]
               :when (seq lines)]
           {:ns nsx :form (or (:name e) (:id e))
            :lines (mapv str/trim (take 3 lines))})
         vec)))
(defn query-impact
  "Rock 4: the blast radius of reshaping `ns-sym/nm` — call sites grouped
  per caller form (:calls), value/higher-order references (:value-refs — a
  template rewrite can't reach those), and the tests the trace map will
  run. change_signature's discovery as a READ: plan the edit before paying
  for it. Self-references inside the target form are excluded — replacing
  the defn covers them."
  [session ns-sym nm]
  (let [st (:store @session)]
    (if-not (store/form-named st ns-sym nm)
      (edit/missing-form-error st ns-sym nm)
      (let [rows    (for [nsx (keys (:namespaces st))
                          :let [an (index/analyze (render/render-ns st nsx))]
                          u   (:var-usages an)
                          :when (and (= ns-sym (:to u)) (= nm (:name u))
                                     (not (and (= nsx ns-sym)
                                               (= nm (:from-var u)))))]
                      {:ns nsx :from (:from-var u) :arity (:arity u)})
            callers (->> rows
                         (group-by (juxt :ns :from))
                         (mapv (fn [[[nsx from] us]]
                                 {:ns nsx :form from
                                  :calls (count (keep :arity us))
                                  :value-refs (count (remove :arity us))}))
                         (sort-by (juxt (comp str :ns) (comp str :form)))
                         vec)
            qsym    (symbol (str ns-sym) (str nm))
            tests   (->> (:test-map @session)
                         (keep (fn [[t fs]] (when (contains? fs qsym) t)))
                         sort vec)]
        (cond-> {:target qsym :callers callers :covered-by tests}
          (some (comp pos? :value-refs) callers)
          (assoc :hint (str "value/higher-order refs can't be template-rewritten"
                            " — change_signature handles :calls; edit the"
                            " :value-refs forms by hand")))))))
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
(defn- snip
  "Cap `s` at `n` chars with an ellipsis — composites (brief/report) carry
  MANY prose fields and must never give back the tokens they save; the
  full text stays one query away (report {contains}, query_history)."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 n) "…"))))
(defn remember-observation!
  "Persist what an observation SAW (up to two {:args :ret} captures) under
  store meta observed/<ns>/<name> — interface cards surface them as
  :examples, the strongest behavior line a card can carry (examples don't
  lie; prose can). Called by the MCP layer after query_observe; a no-op
  for ephemeral sessions or empty captures."
  [session ns-sym nm observe-result]
  (when-let [conn (:db @session)]
    (when-let [calls (seq (:calls observe-result))]
      (try
        (db/set-meta! conn (str "observed/" ns-sym "/" nm)
                      (pr-str (vec (take 2 calls))))
        (catch Exception _ nil))))
  nil)
^:reads (defn form-card
  "The INTERFACE view of a form (opacity with a warranty): signature,
  doc line, effect marker, the recorded WHY (last ask), and the warranty
  (covering tests from the trace map) — what a CALLER needs, at ~10x less
  than source. Trusting it is mechanical, not hopeful: every edit re-runs
  the covering tests, so a violated contract turns red with :implicated."
  [session ns-sym nm]
  (when-let [e (store/form-named (:store @session) ns-sym nm)]
    (let [q       (symbol (str ns-sym) (str nm))
          s       (try (n/sexpr (:node e)) (catch Exception _ nil))
          body    (when (seq? s) s)
          doc     (some #(when (string? %) %) (take 3 (drop 2 (or body ()))))
          sig     (or (some #(when (vector? %) %) (drop 1 (or body ())))
                      (let [arities (keep #(when (and (seq? %) (vector? (first %)))
                                             (first %))
                                          (drop 2 (or body ())))]
                        (when (seq arities) (vec arities))))
          why     (->> (store/deltas (:store @session))
                       reverse
                       (some #(when (and (:prompt %)
                                         (or (= (:id e) (:form-id %))
                                             (some #{(:id e)} (or (:form-ids %) []))))
                                (:prompt %))))
          covered (count (keep (fn [[t fs]] (when (contains? fs q) t))
                               (:test-map @session)))
          examples (when-let [conn (:db @session)]
                     (try
                       (when-let [raw (db/get-meta conn (str "observed/" ns-sym "/" nm))]
                         (->> (edn/read-string raw)
                              (take 2)
                              (mapv (fn [{:keys [args ret threw]}]
                                      (snip (str "(" nm " " (str/join " " args)
                                                 ") → " (or ret threw))
                                            90)))
                              not-empty))
                       (catch Exception _ nil)))]
      (cond-> {:form q :warranty {:covered covered}}
        sig  (assoc :sig sig)
        doc  (assoc :doc (snip (first (str/split-lines doc)) 90))
        (str/ends-with? (str nm) "!") (assoc :effectful true)
        why  (assoc :why (snip why 90))
        examples (assoc :examples examples)))))
^:reads (defn session-brief
  "THE one-call orientation, task-shaped (knowledge-differential stance):
  breadth stays CHEAP — namespace FAMILIES (≥5 same-prefix siblings) roll
  up to one row, form names ride only for solo nses on small stores — and
  depth arrives WHERE THE ASK POINTS: the session's :last-intent (the
  user's verbatim words, via the prompt hook or turn_begin) is mined
  against form names, deterministically, and the top matches ride as
  interface CARDS under :relevant. The agent starts working instead of
  orienting."
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
                                 (update :description snip 110))))
        last-done (let [d (last (filter #(= :done (:op %)) (store/deltas st)))]
                    (when (and d (or (= :red (get-in d [:findings :test-status]))
                                     (pos? (get-in d [:findings :lint-errors] 0))))
                      (-> (select-keys d [:label :at :findings])
                          (assoc :note (str "the last done-point left problems —"
                                            " address them or tell the user why not")))))
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
                        (keep #(form-card session (symbol (namespace %))
                                          (symbol (name %))))
                        vec
                        not-empty))]
    (cond-> {:project project
             :loop (str "work like a REPL: small writes (edit_replace_form / "
                        "edit_add_form / edit_subform — each self-verifies against "
                        "the live image; mid-episode reds are normal TDD state); "
                        "probe with query_eval; spot-check with targeted test_run; "
                        "call done {label} when you believe you're finished — it "
                        "runs the affected tests + lint + tidy for you; ONE "
                        "commit_point {description} closes the user-visible chunk.")}
      (seq ms)   (assoc :milestones ms)
      last-done  (assoc :last-done last-done)
      relevant   (assoc :relevant relevant))))
(defn- fit-report
  "G13 at the gate boundary — by AGGREGATION, never amputation: an
  over-budget report first trims asks to 1/row, then ROLLS CHANGES UP by
  namespace ({:ns :forms :ops :asks}) — the information survives at a
  coarser grain and report {contains} expands any group. Amputation
  (take 20 of the rollup) is the last resort for pathological stores.
  (eval9: the take-20 amputation CAUSED the handoff fan-out — agents went
  hunting for what the report dropped.)"
  [r]
  (let [fits? #(<= (count (pr-str %)) 6500)]
    (if (fits? r)
      r
      (let [slim (update r :changes
                         (fn [cs] (mapv #(update % :asks (comp vec (partial take 1))) cs)))]
        (if (fits? slim)
          (assoc slim :note "asks trimmed to 1/row — report {contains} narrows")
          (let [rolled (->> (:changes slim)
                            (group-by :ns)
                            (mapv (fn [[nsx rows]]
                                    {:ns nsx :forms (count rows)
                                     :ops (vec (distinct (mapcat :ops rows)))
                                     :asks (vec (take 1 (distinct (mapcat :asks rows))))}))
                            (sort-by (comp str :ns)))
                slim2  (assoc slim :changes (vec rolled)
                              :note "changes rolled up by namespace — report {contains <ns or word>} expands a group")]
            (if (fits? slim2)
              slim2
              (-> slim2
                  (update :changes #(vec (take 20 %)))
                  (assoc :note (str "rolled up by namespace, showing 20 of "
                                    (count rolled) " — {contains} narrows"))))))))))
^:reads (defn report
  "The handoff/summary composite (ratio push): milestones, net form-level
  changes with their recorded ASKS, and the last verification state — the
  history fan-out (query_history + query_search_history + query_changes +
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
                               :asks (vec (take 3 (distinct (map #(snip % 140) (keep :ask es)))))}))
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
                                  (update :description snip 110))))
        verify*   (->> deltas (filter #(= :verify (:op %))) last)]
    (fit-report
     {:milestones ms
      :changes changes
      :suite (when verify*
               {:as-of (:id verify*)
                :status (or (get-in verify* [:summary :status])
                            (:status verify*) :unknown)})
      :verify (str "writes self-verify; test_run {} re-runs in-image; "
                   "test_run {:isolated true} = the full external suite")})))
^:reads (defn query-slice
  "The focused read (driver, not doer): FULL source for the form you're
  about to edit + interface CARDS for what it reaches (same-ns private
  helpers and cross-ns callees, breadth-first to `:depth`, capped at
  `:limit` with an honest :omitted). Replaces outline→guess→fetch loops:
  name ONE entry point, receive the neighborhood. `:match` WINDOWS the
  target — only `:window` lines (default 25) each side of the first line
  containing it ride back, with :window metadata — so one clause of a
  giant form reads without paying for the whole thing."
  [session ns-sym nm & {:keys [depth limit match window] :or {depth 2 limit 8}}]
  (if-let [e (store/form-named (:store @session) ns-sym nm)]
    (let [root    (symbol (str ns-sym) (str nm))
          adj     (:calls (query-deps session ns-sym nm))
          reached (loop [level [root] seen #{root} acc [] d 0]
                    (if (>= d depth)
                      acc
                      (let [nxt (into []
                                      (comp (mapcat #(get adj % []))
                                            (remove seen)
                                            (distinct))
                                      level)]
                        (if (empty? nxt)
                          acc
                          (recur nxt (into seen nxt) (into acc nxt) (inc d))))))
          shown   (vec (take limit reached))
          cards   (into []
                        (keep (fn [q] (form-card session
                                                 (symbol (namespace q))
                                                 (symbol (name q)))))
                        shown)
          src     (n/string (:node e))
          target  (if match
                    (let [lines (vec (str/split-lines src))
                          w     (or (some-> window str parse-long) 25)
                          idx   (first (keep-indexed
                                        (fn [i l] (when (str/includes? l (str match)) i))
                                        lines))]
                      (if idx
                        (let [lo (max 0 (- idx w))
                              hi (min (count lines) (+ idx w 1))]
                          {:form root
                           :source (str/join "\n" (subvec lines lo hi))
                           :window {:match (str match) :lines [(inc lo) hi]
                                    :of (count lines)}})
                        {:form root :source src
                         :note (str "match not found in the form: " match)}))
                    {:form root :source src})]
      (cond-> {:target target
               :cards cards}
        (> (count reached) limit) (assoc :omitted (- (count reached) limit))))
    (edit/missing-form-error (:store @session) ns-sym nm)))
(defn module-surface
  "What module `m` OFFERS: the public defns/defmacros/defs of its depth<=2
  namespaces plus every deeper var widened by :export (with its level) —
  compact rows {:ns :name :sig :doc :export?}, -test namespaces and
  ^:private vars excluded. Plus :deps (its declared edges) and :consumers
  (modules declaring an edge to it). The cheap browse before calling into
  a module."
  [session m]
  (let [st       (:store @session)
        m        (str m)
        manifest (or (edit/modules-manifest st) {})
        nses     (filter #(= m (edit/module-of %)) (keys (:namespaces st)))
        rows     (for [nsx  (sort nses)
                       :when (not (str/ends-with? (str nsx) "-test"))
                       :let [deep? (> (count (str/split (str nsx) #"\.")) 2)]
                       e    (store/forms st nsx)
                       :let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
                       :when (and (seq? s)
                                  (contains? '#{defn defmacro def} (first s))
                                  (symbol? (second s))
                                  (not (:private (meta (second s))))
                                  (or (not deep?)
                                      (:export (meta (second s)))))
                       :let [doc (first (filter string? (take 2 (drop 2 s))))
                             sig (first (filter vector? s))
                             ex  (:export (meta (second s)))]]
                   (cond-> {:ns nsx :name (second s)}
                     sig             (assoc :sig sig)
                     doc             (assoc :doc (first (str/split-lines doc)))
                     (and deep? ex)  (assoc :export (if (true? ex) true (str ex)))))]
    (if (empty? nses)
      {:error (str "no namespaces in module " m
                   " — query_depends {modules true} lists the modules")}
      {:module    m
       :surface   (vec rows)
       :deps      (vec (sort (get manifest m #{})))
       :consumers (vec (sort (keep (fn [[k deps]] (when (contains? deps m) k))
                                   manifest)))})))
^:reads (defn query-depends
  "The generic dependency front door: what depends on `on` (`:direction
  :dependents`, the default) or what `on` depends on (`:direction
  :dependencies`), where `on` is a NAMESPACE, a VAR (\"ns/name\"), or a
  KEYWORD (\":dest-zone\"). Dependents: ns → who requires it + qualified
  refs; var → blast radius (callers, value refs, covering tests); keyword
  → the field's flow. Dependencies: var → the transitive callee tree; ns
  → its requires. `:modules true` (no `on`) → the module manifest (declared
  edges + any standing debt). One tool to ask — results carry :kind."
  [session on & {:keys [direction modules] :or {direction :dependents}}]
  (let [st (:store @session)]
    (if modules
      (if (seq (str on))
        (assoc (module-surface session on) :kind :module-surface)
        (let [manifest (or (edit/modules-manifest st) {})
              rows     (module-usage-rows st)
              actual   (into #{}
                             (comp (map (fn [{:keys [from-ns to]}]
                                          [(edit/module-of from-ns)
                                           (edit/module-of to)]))
                                   (remove (fn [[a b]] (= a b))))
                             rows)
              unused   (vec (for [[m ds] (sort manifest)
                                  d      (sort ds)
                                  :when  (not (contains? actual [m d]))]
                              [m d]))
              graph    (store/module-layers manifest)]
          (cond-> {:kind :modules
                   :manifest (into (sorted-map)
                                   (map (fn [[m ds]] [m (vec (sort ds))]))
                                   manifest)
                   :layers (:layers graph)
                   :debt (module-debt st rows)}
            (seq (:cycles graph))
            (assoc :cycles (:cycles graph))

            (seq unused)
            (assoc :unused-edges unused
                   :unused-note (str "declared but no call uses them —"
                                     " module_dep {from .. to .. remove true}"
                                     " retires an edge")))))
      (let [on (str/trim (str on))]
        (cond
          (str/starts-with? on ":")
          {:kind :keyword :on on :rows (query-flow session on)}

          (str/includes? on "/")
          (let [[nsx nm] (str/split on #"/" 2)]
            (if (= :dependencies direction)
              (let [r (query-deps session (symbol nsx) (symbol nm))]
                (assoc r :kind :var :on on :direction :dependencies))
              (let [r (query-impact session (symbol nsx) (symbol nm))]
                (if (:error r) r (assoc r :kind :var :on on)))))

          (contains? (:namespaces st) (symbol on))
          (let [target   (symbol on)
                requires (vec (sort (distinct (vals (edit/require-aliases st target)))))]
            (if (= :dependencies direction)
              {:kind :namespace :on target :direction :dependencies
               :requires requires}
              (let [req-set     (fn [nsx] (set (vals (edit/require-aliases st nsx))))
                    required-by (vec (sort (filter #(and (not= % target)
                                                         (contains? (req-set %) target))
                                                   (keys (:namespaces st)))))
                    pat         (re-pattern (str "(?<![\\w.-])"
                                                 (java.util.regex.Pattern/quote on) "/"))
                    refs        (vec (for [nsx (sort (keys (:namespaces st)))
                                           :when (not= nsx target)
                                           e (store/forms st nsx)
                                           :when (and (:name e)
                                                      (re-find pat (n/string (:node e))))]
                                       {:ns nsx :form (:name e)}))]
                {:kind :namespace :on target
                 :required-by required-by
                 :requires requires
                 :qualified-refs (vec (take 20 refs))})))

          :else
          {:error (str "nothing named " on
                       " — `on` is a namespace, var (ns/name), or :keyword;"
                       " modules true reads the module manifest")})))))
(defn adopt-modules!
  "ADOPTION (internal — never a tool, never explicit): derive the module
  manifest from the CURRENT actual dependency graph — kondo-resolved, so
  :refer'd calls count — and record it as one :module-edge delta per edge.
  Called once by open! for a populated store whose db predates the module
  system (:modules nil); by construction the result is acyclic with zero
  violations, so adoption never breaks working code — the gate then blocks
  DRIFT until the agent declares new edges (module_dep)."
  [session & {:keys [agent]}]
  (let [edges (edit/derive-module-edges (:store @session))]
    (commit-appended!
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
(defn module-dep!
  "Declare or retract ONE module dependency edge — the semantic verb behind
  the module manifest (there is no file to edit): each call is one
  :module-edge delta carrying its why (:prompt). Adds are refused when the
  resulting graph would contain a cycle; the response carries the module's
  folded dep set and, when any exists, the store's remaining :violations
  debt."
  [session from to & {:keys [remove prompt agent]}]
  (let [manifest (or (edit/modules-manifest (:store @session)) {})
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
      (if-let [back (and (not remove) (store/module-path manifest to from))]
        {:error (str "that edge CLOSES a dependency cycle: "
                     (clojure.string/join " → " (conj back to))
                     " — point the dependency one way (usually by extracting"
                     " the shared piece into a module both sides may depend on)")}
        (let [st' (commit-appended!
                   session
                   #(first (store/record-module-edge % from to action
                                                     :prompt prompt
                                                     :agent agent))
                   [])]
          (cond-> {:from from :to to :action action
                   :deps (vec (sort (get-in st' [:modules from])))}
            (module-debt st')
            (assoc :violations (module-debt st')
                   :note (str "existing debt under this manifest — writes"
                              " touching these forms stay blocked until the"
                              " edge is declared or the call restructured"))))))))
(defn query-brief
  "The one-call dossier: everything the store knows about `ns-sym/nm` —
  source, effect flags, cross-ns callers, the tests that exercise it
  (trace map; `:coverage :unknown` until a test_run builds one), and the
  recorded WHY (the last change's prompt + its enclosing turn intent).
  Collapses the source→references→lineage read chain into one response."
  [session ns-sym nm]
  (if (nil? (store/form-named (:store @session) ns-sym nm))
    (edit/missing-form-error (:store @session) ns-sym nm)
    (let [qsym    (symbol (str ns-sym) (str nm))
          sym     (query-symbol session ns-sym nm)
          callers (vec (query-references session ns-sym nm))
          tmap    (:test-map @session)
          tests   (->> tmap
                       (keep (fn [[t forms]] (when (contains? forms qsym) t)))
                       sort vec)
          why     (last (query-lineage session ns-sym nm))]
      (cond-> {:ns ns-sym :name nm :source (:source sym)}
        (:effectful? sym) (assoc :effectful? true)
        (:reads? sym)     (assoc :reads? true)
        (:unsafe? sym)    (assoc :unsafe? true)
        (seq callers)     (assoc :callers callers)
        (seq tests)       (assoc :covered-by tests)
        (and (seq tmap) (empty? tests) (not (:test? sym)))
        (assoc :untested true)
        (empty? tmap)     (assoc :coverage :unknown)
        why               (assoc :why (cond-> {:op     (:op why)
                                               :prompt (:prompt why)}
                                        (:agent why)       (assoc :agent (:agent why))
                                        (:turn-intent why) (assoc :intent (:turn-intent why))))))))
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
  [session from to & {:keys [prompt agent]}]
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
            nsr  (reduce (fn [acc nsx]
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
                         (sort nses))]
        (if (:error nsr)
          nsr
          (let [st    (:store @session)
                steps (vec (for [nsx (store/ns-dependency-order st)
                                 e   (store/forms st nsx)
                                 :let [src (n/string (:node e))]
                                 :when (and (:name e) (re-find pat src))]
                             {:action :replace :ns nsx :name (:name e)
                              :source (str/replace src pat to)}))]
            (cond
              (and (empty? steps) (empty? (:renamed-namespaces nsr)))
              {:error (str "nothing named " from
                           " in the store — query_search shows what exists")}

              (empty? steps)
              (assoc nsr :forms 0)

              :else
              (let [r (edit-group! session steps :prompt why :agent agent)]
                (if (:error r)
                  r
                  (merge r (assoc nsr :forms (count steps))))))))))))
