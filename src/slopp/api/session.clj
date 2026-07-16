(ns slopp.api.session (:require [clojure.edn :as edn] [clojure.set :as set] [clojure.string :as str] [rewrite-clj.node :as n] [slopp.db :as db] [slopp.edit :as edit] [slopp.image :as image] [slopp.index :as index] [slopp.render :as render] [slopp.repl :as repl] [slopp.store :as store]))

(def ^{:export "slopp.concurrency"} ^:dynamic *pre-commit-hook*
  "Test seam (item 4): invoked between an op's hot-load and its commit CAS to
  simulate a concurrent competitor deterministically. Never set in production.
  Exported to the contention specs that bind it; package-private otherwise."
  nil)
(defn start-spare!
  "Kick off a background-warming spare image (D5 warm spare) if enabled. The
  spare is launched BARE (deps unknown until a line adopts it); its manifest
  is reconciled at adoption via `image-with-deps!`."
  [session]
  (when (:warm-spare? @session)
    (swap! session assoc :spare (future (repl/start!)))))
(defn image-with-deps!
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
(defn load-trace
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
(defn stub-missing-test-vars!
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
(defn try-commit!
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
(defn refresh-cache!
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
(defn persist-trace!
  "Q3: the trace map survives the session — written to store meta so the NEXT
  session (or a CLI one-shot) starts with narrowing warm instead of
  {:ran 0 :affected :all}. Last writer wins; load-trace prunes stale names."
  [session]
  (when-let [conn (:db @session)]
    (db/set-meta! conn "trace-map" (pr-str (:test-map @session)))))
(defn commit-appended!
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
(defn with-ms
  "Attach total op wall time (item 2 observability)."
  [m t0]
  (if (map? m)
    (assoc m :ms (quot (- (System/nanoTime) t0) 1000000))
    m))
(defn green? [summary]
  (zero? (+ (:fail summary 0) (:error summary 0))))
(defn fresh-image!
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
(defn hot-load-all!
  "Checked-load `form-ids` from a CANDIDATE store value into the image (S1).
  nil on success; {:healed true} when a STALE IMAGE had to be refreshed to
  make the load succeed (D5.1); {:stubbed [qsyms]} when red-first stubs made
  a -test namespace compile (the generic red-first seam — the spec runs and
  fails honestly); {:err msg} when the forms genuinely don't compile (image
  restored either way; :first-err carries the pre-heal error when it
  differs). Keys compose.
  The heal boots from the COMMITTED store, so the candidate's touched nses
  are replayed from the CANDIDATE (dependency order, full load-ns! so new
  namespaces exist and are *loaded-libs*-stamped) before the retry —
  without that, a candidate that CREATES a namespace (extract_ns) dies
  with FileNotFound when a survivor requires it."
  [session candidate form-ids]
  (let [nses    (vec (distinct (keep #(store/ns-of-form-id candidate %) form-ids)))
        stub!   #(stub-missing-test-vars! (:image @session) candidate nses)
        replay! #(doseq [ns-sym (filter (set nses)
                                        (store/ns-dependency-order candidate))]
                   (image/load-ns! (:image @session) candidate ns-sym))]
    (letfn [(load-all []
              (loop [ids (seq form-ids)]
                (when ids
                  (or (edit/hot-load-form! (:image @session) candidate (first ids))
                      (recur (next ids))))))]
      (when-let [err1 (load-all)]
        (let [stubbed (stub!)]
          (if (and (seq stubbed) (nil? (load-all)))
            {:stubbed stubbed}
            (do (fresh-image! session)             ; maybe the image was stale
                (replay!)                          ; candidate truth over the committed boot
                (let [stubbed (stub!)]             ; a fresh image loses stubs
                  (if-let [err2 (load-all)]
                    (do (fresh-image! session)
                        (cond-> (merge {:err err2}
                                       (edit/anchor-error candidate err2))
                          (not= err1 err2) (assoc :first-err err1)))
                    (cond-> {:healed true}
                      (seq stubbed) (assoc :stubbed stubbed)))))))))))
(defn rebased-write!
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
  re-checks. A genuine compile failure returns an ANCHORED error
  (edit/compile-error — form + snippet, no file:line).
  AUTO-AVOID-DECLARE: the pure transform is WRAPPED so a candidate with a
  forward reference is reordered (defs moved above callers) before the
  cold-load gate — the agent never writes (declare ...). The reorder rides
  inside the swap! rerun too, so durable rebasing stays consistent; a genuine
  cycle (mutual recursion) reorder can't fix falls through to the existing
  refusal, which teaches the declare."
  [session raw-transform target-node target-desc ns-sym
   & {:keys [load?] :or {load? true}}]
  (let [orig      (some-> (target-node (:store @session)) n/string)
        conflict  {:conflict {:form target-desc
                              :reason "form changed concurrently — re-read and retry"}}
        transform (fn [base]
                    (let [out (raw-transform base)]
                      (if (or (:error out) (not load?))
                        out
                        (if-let [rz (edit/resolve-cold-load
                                     (:store out) ns-sym
                                     :prompt "auto-reorder: define before use")]
                          (assoc out :store (:store rz))
                          out))))]
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
                      (edit/compile-error (:store out) (:err load-res)
                                          "form failed to compile: ")
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
              (edit/compile-error (:store out0) (:err load-res)
                                  "form failed to compile: ")
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
(def reload-signature-res
  "Failure texts that smell like hot-reload staleness rather than logic bugs."
  [#"Unable to resolve symbol"
   #"Attempting to call unbound fn"
   #"No implementation of method"
   #"Var .* is unbound"])
(defn reload-signature? [failure]
  (let [s (str (:actual failure) " " (:message failure))]
    (or (boolean (some #(re-find % s) reload-signature-res))
        ;; same-named classes cast-failing against each other = redefined type
        (boolean
         (when-let [[_ c1 c2] (re-find #"class (\S+) cannot be cast to class (\S+)" s)]
           (= (last (str/split c1 #"\.")) (last (str/split c2 #"\."))))))))
(defn suspicious-red?
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
(defn affected-tests
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
(defn implicate
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
(defn shape-episode-reds!
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
(defn test-ns?
  "Does `nsx` hold any deftest? (Inline tests count — Q13.)"
  [store nsx]
  (some #(str/starts-with? (str/triml (n/string (:node %))) "(deftest")
        (store/forms store nsx)))
(defn isolated-test-nses
  "Of `nses`, those defining at least one ^:isolated deftest — tests only
  the EXTERNAL tier can execute (they spawn sessions/images; in-image runs
  skip them). The done-point uses this to route impacted tests to the
  right tier without the agent choosing tiers."
  [store nses]
  (vec (for [nsx nses
             :when (some (fn [e]
                           (let [s (try (n/sexpr (:node e))
                                        (catch Exception _ nil))]
                             (and (seq? s)
                                  (= 'deftest (first s))
                                  (boolean (:isolated (meta (second s)))))))
                         (store/forms store nsx))]
         nsx)))
(defn test-nses-reaching
  "Test namespaces (any ns holding a deftest) whose require-closure
  reaches one of `changed-nses` — the PROVABLE set of tests a change can
  affect (a test only exercises code it can load). The honest fallback
  scope when the trace map is silent."
  [store changed-nses]
  (let [changed (set changed-nses)]
    (vec (sort (for [t (keys (:namespaces store))
                     :when (and (test-ns? store t)
                                (seq (set/intersection
                                      (store/ns-closure store t)
                                      changed)))]
                 t)))))
(defn rename-in-trace
  "Carry the observed test→form map across a rename (old qsym → new qsym)."
  [tmap qold qnew]
  (into {}
        (map (fn [[t forms]]
               [(if (= t qold) qnew t)
                (into #{} (map #(if (= % qold) qnew %)) forms)]))
        tmap))
(defn test-var-tiers
  "Plain deftest names of `ns-sym` split by execution tier:
   {:image [...] :isolated [...]}. `^:isolated` tests spawn images / recurse,
   so the IN-IMAGE runner must skip them (they only behave in the external
   tier) — this is what lets `traced-run!` defer them as :isolated-pending
   instead of running (and false-greening) them in-image."
  [store ns-sym]
  (reduce (fn [m e]
            (let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
              (if (and (seq? s) (= 'deftest (first s)))
                (update m (if (:isolated (meta (second s))) :isolated :image)
                        (fnil conj []) (second s))
                m)))
          {:image [] :isolated []}
          (store/forms store ns-sym)))
(defn traced-run!
  "Run `test-ns`'s tests (all, or `only` names) with form-tracing; absorb the
  observed test→form map into the session (persisted — Q3); return the summary.
  `skip-integration?` drops `^:integration` tests (M5, the fast-path default).
  The in-image tier NEVER runs `^:isolated` tests (they spawn images / recurse
  and only behave in the external tier): any in scope are filtered OUT of the
  run and reported as `:isolated-pending` on the summary — never executed
  in-image (which would false-green/false-red them). The done-point / merge
  gate runs them for real in the external tier."
  [session test-ns only & [skip-integration?]]
  (let [{:keys [image store]} @session
        nses     (if (coll? test-ns) test-ns [test-ns])
        isolated (into #{} (mapcat #(:isolated (test-var-tiers store %))) nses)]
    (if (empty? isolated)
      ;; no ^:isolated tests in scope — original path, untouched
      (let [{:keys [summary trace]} (image/traced-test-run
                                     image store test-ns :only only
                                     :skip-integration? skip-integration?)]
        (swap! session update :test-map merge trace)
        (persist-trace! session)
        summary)
      ;; some are isolated — run only the in-image tier, defer the rest
      (let [pending (if only (filterv isolated only) (vec isolated))
            only'   (if only
                      (vec (remove isolated only))
                      (vec (mapcat #(:image (test-var-tiers store %)) nses)))
            summary (if (empty? only')
                      ;; every impacted test is isolated — nothing to run here
                      {:test 0 :pass 0 :fail 0 :error 0 :type :summary}
                      (let [{:keys [summary trace]} (image/traced-test-run
                                                     image store test-ns :only only'
                                                     :skip-integration? skip-integration?)]
                        (swap! session update :test-map merge trace)
                        (persist-trace! session)
                        summary))]
        (cond-> summary
          (seq pending)
          (assoc :isolated-pending
                 (cond-> {:count (count pending)
                          :tests (vec (take 5 (sort pending)))}
                   (> (count pending) 5)
                   (assoc :note (str "first 5 shown — the done-point / merge gate"
                                     " runs them all in the external tier")))))))))
(defn diagnosed-run!
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
(defn run-verification!
  "Diagnosed run of `affected` tests (grouped by their namespace), or of all of
  `default-ns`'s tests when there's no trace information. `:edited` (the
  just-changed form qsyms) powers the D5.1 genuine-vs-suspicious call and the
  red-result :implicated correlation (Rock 2). Results pass through the
  episode-red shaper (direction over repetition); `:boundary? true` (the
  done-point) bypasses compression and resets the ledger."
  [session default-ns affected & {:keys [edited fresh include-integration? boundary?]}]
  (cond-> (shape-episode-reds!
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
           affected default-ns boundary?)
    ;; the done-point runs the isolated tier for REAL right after this, and
    ;; reports its own cap in :findings — an in-image deferral note there is
    ;; noise about an implementation detail
    boundary? (dissoc :isolated-pending)))
