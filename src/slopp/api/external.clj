(ns slopp.api.external
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str] [slopp.store.db :as db] [clojure.java.io :as io] [rewrite-clj.node :as n] [slopp.api :as api] [slopp.api.deps :as api.deps] [slopp.api.done :as done] [slopp.api.history :as history] [slopp.api.modules :as modules] [slopp.api.query :as query] [slopp.api.rules :as rules] [slopp.api.session :as session] [slopp.api.testrun :as testrun] [slopp.store.build :as build] [slopp.edit :as edit] [slopp.edit.modules :as edit.modules] [slopp.index :as index] [slopp.store.render :as render] [slopp.image.repl :as repl] [slopp.store :as store] [slopp.image :as image] [slopp.index.analyze :as analyze] [slopp.api.branch :as branch] [slopp.api.capabilities :as capabilities]))

^:reads (defn ^:export git-config-value
  "`git config <k>` as git would resolve it in `dir` (local then global), or
  nil. The \"<git>\" fallback of the G5 author config."
  [dir k]
  (let [r (sh/sh "git" "-C" (str dir) "config" k)]
    (when (zero? (:exit r))
      (let [v (str/trim (:out r))]
        (when-not (str/blank? v) v)))))

^:reads (defn ^:export author-identity
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

(defn ^:export client-build-deps
  "slopp's OWN toolchain deps for a BUILD of `store`, injected at build time —
   NEVER user manifest deltas — when the store carries CLIENT code (:cljc/:cljs):
   {:runtime {lib coord} :client {lib coord}}. malli goes in the runtime channel
   (schema code loads on the JVM oracle + external tier, and the :cljs compile
   inherits base :deps), versioned centrally from repl/inherent-deps; the
   configured compiler (build/compiler-coord) goes in the build-only :client
   channel. {:runtime {} :client {}} for a non-client store, so its generated
   deps.edn stays byte-identical. slopp versions these centrally so an upgrade
   reaches every store with no migration; the agent adds only APPLICATION deps (a
   D-web-contracts dogfood finding — the two-config split the user named)."
  [store]
  (if (some #(#{:cljc :cljs} (store/platform-for store %)) (keys (:namespaces store)))
    (let [[clib ccoord] (build/compiler-coord (build/client-compiler store))]
      {:runtime (select-keys repl/inherent-deps '[metosin/malli])
       :client  (if clib {clib ccoord} {})})
    {:runtime {} :client {}}))

(defn ^:export build!
  "C1/C6 explicit build: materialize a runnable project under `dir` —
  `src/<ns-path>.clj` per namespace plus a minimal `deps.edn` (F8). Guarded
  (X4: an eval agent once built into the host repo, clobbering its deps.edn):
  absolute paths only, never a directory enclosing the running process, and a
  deps.edn this build didn't generate is never overwritten.

  With `:main` (a qualified entry fn, e.g. 'calc.core/run-cli) also emits the
  native-binary recipe (O4): a generated gen-class launcher at
  src/native/main.clj, a `:native` deps alias, and an executable
  build-native.sh that GraalVM-compiles the project to a self-contained
  binary `:name` (default: the entry ns's first segment). `:main` and `:name`
  FALL BACK to the persisted app manifest — the `app.main` / `app.name`
  capability settings — so a store that declares its entry point builds with
  no arguments; explicit args override."
  [session dir & {:keys [main force] bin-name :name}]
  (let [f        (io/file dir)
        target   (.getCanonicalFile f)
        cwd      (.getCanonicalFile (io/file "."))
        st       (:store @session)
        main     (or main (capabilities/effective st "app.main"))
        bin-name (or bin-name (capabilities/effective st "app.name"))
        de       (io/file target "deps.edn")
        provided (client-build-deps st)
        deps     (merge (:deps st) (:runtime provided))
client-deps (merge (:client-deps st) (:client provided))
        has-tests? (boolean (or (some render/test-ns? (keys (:namespaces st)))
                                (some (fn [nsx]
                                        (some #(re-find #"^\(deftest\b"
                                                        (n/string (:node %)))
                                              (store/forms st nsx)))
                                      (keys (:namespaces st)))))
        incompat (when main (seq (filter api.deps/native-incompatible-deps (keys deps))))
        ;; a deps.edn is ours iff it's byte-identical to a generated variant
        ;; (for THIS store's manifest + test layout — else it reads as foreign)
        traced?  (boolean (and has-tests?
                               (get-in st [:namespaces 'slopp.image.testmain])))
        ours?    #(contains? #{(build/deps-edn false deps has-tests? traced? client-deps)
                               (build/deps-edn true deps has-tests? traced? client-deps)}
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
    (let [file (io/file target (render/source-path ns-sym (store/platform-for st ns-sym)))]
      (io/make-parents file)
      (spit file (render/render-ns st ns-sym))))
  (doseq [[path entry] (:files st)]
    (let [file (io/file target (str path))]
      (io/make-parents file)
      (if (map? entry)
        ;; a binary asset: real bytes from the content-addressed cache
        ;; a binary asset: real bytes, from the in-memory cache when this
        ;; session wrote them, else straight from the content-addressed
        ;; table — :blobs is a PARTIAL cache and is not populated at open
        (when-let [^bytes bs (or (get (:blobs st) (:sha entry))
                                 (some-> (:db @session) (db/get-blob (:sha entry))))]
          (io/copy bs file))
        (spit file entry))))
  (doseq [[path entry] (cond-> (:config st)
                               (modules/modules-config-entry st)
                               (assoc "modules" (modules/modules-config-entry st)))]
    (let [file (io/file target (str path))]
      (io/make-parents file)
      (spit file (store/render-config entry))))
          ;; PROVENANCE: what this materialization was built FROM. A derived
          ;; artifact that cannot state its origin eventually gets trusted when
          ;; it should not — `uber` jarred a two-day-old materialization and
          ;; printed success. The head delta id makes the staleness check exact
          ;; instead of an mtime guess.
          (spit (io/file target ".slopp-head") (str (:id (last (store/deltas st)))))
          (when (or main (not (.exists de)))
            (when has-tests? (.mkdirs (io/file target "test")))
            (spit de (build/deps-edn (boolean main) deps has-tests? traced? client-deps)))
          (cond-> {:built (str target)}
            main
            (assoc :native
                   (let [an    (analyze/analyze (render/render-ns st entry-ns))
                         vdef  (first (filter #(and (= entry-ns (:ns %))
                                                    (= (symbol (name main)) (:name %)))
                                              (:var-definitions an)))
                         bin   (or bin-name (first (str/split (str entry-ns) #"\.")))
                         launcher (io/file target "src" "native" "main.clj")
                         script   (io/file target "build-native.sh")]
                     (io/make-parents launcher)
                     (spit launcher (build/launcher-source main (build/arg-style vdef)))
                     (spit script (build/native-script bin (keys (:files st))))
                     (.setExecutable script true false)
                     (let [warns (vec (for [[lib coord] deps
                                            :when (= :none (:verdict
                                                            (api.deps/dep-native-verdict session lib coord)))]
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

(defn ^:export external-test-run!
  "Run the STORE's test suite in a FRESH EXTERNAL JVM: materialize the store
  (build!) into a throwaway dir and shell `clojure -M<alias>` there — the
  out-of-process counterpart to in-image `traced-run`, and the ONLY tier that
  executes ^:external tests (they spawn their own images/subprocesses, so
  running them in-image would recurse). Needs no repo files — the store is
  the source, which is what lets the working dir go fileless. `:ns` narrows
  to one test namespace, `:only` to specific ns-qualified test vars (Q2);
  `:affected true` narrows to the PROVABLE slice (test namespaces whose
  require-closure reaches a form changed since the last milestone);
  `:parallel` SHARDS a full/affected run across concurrent JVMs — one
  build, round-robin namespace shards, merged into one summary. Defaults
  to AUTO (auto-parallel: scales with test-ns count + cores, serial below
  ~8 nses where boot overhead beats the gain); an explicit N overrides
  (1 forces serial). A single :ns/:only run never shards. Returns {:external
  true :status :ran :assertions :failures :errors :exit} plus :failing +
  :all-failing {file [tests]} + :themes (clustered causes) when red.

  Every runner is BOUNDED (testrun/run-cmd!) — a hung ^:external test used
  to wedge done! and the milestone gate forever. A green summary is only
  trusted when the JVM also exited zero: a runner that printed green then
  died (System/exit in teardown, OOM in a shutdown hook) is :error, not
  :green. The throwaway build dir is deleted when the run ends, whatever
  the outcome — it used to leak a full materialized project per run.

  Also ABSORBS the run's form trace (#121) when the build carried the trace
  runner: this is the only tier that ever executes an ^:external test, so it
  is the only place their test→form evidence can come from. Silent — the
  trace lands in the session's test-map (and persists), surfacing later as
  honest `:warranty` and affected-test narrowing, not as output here."
  [session & {:keys [alias ns only affected parallel nses]}]
  (let [aff (when affected (api/affected-test-nses session))]
    (if (and aff (empty? (:selected aff)))
      {:external true :ran 0 :status :green :affected aff
       :note (str "no test namespace can reach the changes since the last"
                  " milestone — nothing to verify (run without affected for"
                  " the full gate)")}
      ;; the full/affected set is shardable (a single :ns or :only run is not);
      ;; :parallel defaults to AUTO — scale the shard count to the work + cores
      (let [full-set (cond
                       (seq nses)
                       (vec (sort (map symbol nses)))

                       (and (nil? ns) (empty? only))
                       (or (:selected aff)
                           (vec (sort (filter #(session/test-ns? (:store @session) %)
                                              (keys (:namespaces (:store @session))))))))
            par (cond (some? parallel) parallel
                      (nil? full-set)  1
                      :else (testrun/auto-parallel (count full-set)
                                           (.availableProcessors (Runtime/getRuntime))))
            shard-nses (when (and (> par 1) (seq full-set)) full-set)
            ;; narrowed runs need the filter-free alias: the :test alias bakes
            ;; -r \".*\" (inline tests, Q13) which UNIONS with -n and defeats it
            alias (or alias
                      (if (or ns aff (seq only) (seq nses) (seq shard-nses))
                        ":test-run" ":test"))
            dir (str (java.nio.file.Files/createTempDirectory
                      "slopp-external"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
        (try
          (let [b (build! session dir)]
            (if (:error b)
              b
              (let [result
                    (if (seq shard-nses)
                      (let [shards (->> (map-indexed vector shard-nses)
                                        (group-by #(mod (first %) par))
                                        vals
                                        (mapv #(mapv second %)))
                            runs   (mapv (fn [grp] (future (testrun/run-shard! alias dir grp)))
                                         shards)
                            outs0  (mapv deref runs)
                            ;; a shard with NO parseable summary is a JVM-level death
                            ;; (fork pressure, OOM) — test failures PARSE. Retry those
                            ;; shards once, SERIALLY, off the concurrent storm.
                            dead?  (fn [o] (nil? (testrun/parse-test-summary
                                                  (str (:out o) "\n" (:err o)))))
                            outs   (mapv (fn [grp o]
                                           (if (dead? o) (testrun/run-shard! alias dir grp) o))
                                         shards outs0)
                            retries (count (filter dead? outs0))
                            out    (str/join "\n" (map #(str (:out %) "\n" (:err %)) outs))
                            sums   (mapv #(testrun/parse-test-summary (str (:out %) "\n" (:err %))) outs)]
                        (if (some nil? sums)
                          (cond-> {:external true :exit (apply max (map :exit outs))
                                   :status :error
                                   :shards (count shards)
                                   :output (->> (str/split-lines out)
                                                (remove str/blank?)
                                                (take-last 12) (str/join "\n")
                                                testrun/anchor-output)}
                            (pos? retries) (assoc :shard-retries retries))
                          (let [merged {:ran        (reduce + (map :ran sums))
                                        :assertions (reduce + (map :assertions sums))
                                        :failures   (reduce + (map :failures sums))
                                        :errors     (reduce + (map :errors sums))}
                                exit   (apply max (map :exit outs))
                                red?   (pos? (+ (:failures merged) (:errors merged)))]
                            (cond-> (merge {:external true
                                            :exit exit
                                            :shards (count shards)
                                            :status (cond red?        :red
                                                          (pos? exit) :error
                                                          :else       :green)}
                                           merged
                                           (when aff {:affected aff})
                                           (when (pos? retries) {:shard-retries retries}))
                              (and (not red?) (pos? exit))
                              (assoc :note (str "summaries parsed green but a runner"
                                                " JVM exited nonzero — not trusting"
                                                " the green"))
                              red? (assoc :failing (testrun/parse-test-failures out)
                                          :all-failing (testrun/failing-test-rollup out))
                              (and red? (seq (testrun/failure-themes out)))
                              (assoc :themes (testrun/failure-themes out))))))
                      (let [args (cond-> [repl/clojure-bin (str "-M" alias)]
                                   ns         (conj "-n" (str ns))
                                   (seq nses) (into (mapcat #(vector "-n" (str %)) nses))
                                   aff        (into (mapcat #(vector "-n" (str %))
                                                            (:selected aff)))
                                   ;; -n rides along with -v: cognitect's var
                                   ;; filter only resolves vars in DISCOVERED
                                   ;; namespaces, and the default discovery
                                   ;; regex is -test$ — a named test living
                                   ;; anywhere else was unresolvable
                                   (seq only) (into (mapcat #(vector "-n" %)
                                                            (distinct
                                                             (keep #(namespace (symbol (str %)))
                                                                   only))))
                                   (seq only) (into (mapcat #(vector "-v" (str %)) only)))
                            r    (testrun/run-cmd! args dir)
                            out  (str (:out r) "\n" (:err r))
                            s    (testrun/parse-test-summary out)]
                        (merge {:external true :exit (:exit r)}
                               (when aff {:affected aff})
                               (cond
                                 (nil? s)           {:status :error
                                                     :output (->> (str/split-lines out)
                                                                  (remove str/blank?)
                                                                  (take-last 8) (str/join "\n")
                                                                  testrun/anchor-output)}
                                 (= :red (:status s)) (cond-> (assoc s
                                                                     :failing (testrun/parse-test-failures out)
                                                                     :all-failing (testrun/failing-test-rollup out))
                                                        (seq (testrun/failure-themes out))
                                                        (assoc :themes (testrun/failure-themes out)))
                                 (pos? (:exit r))
                                 (assoc s :status :error
                                        :note (str "summary parsed green but the JVM"
                                                   " exited nonzero — not trusting"
                                                   " the green"))
                                 :else s))))]
                ;; #121: ONE absorb point for BOTH branches — the external tier is
                ;; the only place an ^:external test ever runs, so a trace missed
                ;; here is missed forever. nil when the build carried no runner, so
                ;; untraced stores behave exactly as before.
                (session/absorb-trace! session (testrun/read-traces dir))
                result)))
          (finally
            ;; a full materialized project per run; nothing else ever deletes it
            (branch/delete-dir! (io/file dir))))))))

(defn ^:export done!
  "The DONE-POINT: call when you believe your changes are complete. Marks
  the episode boundary and runs the automatic done-processing — normalize
  every form changed this episode (conservative behavior-preserving
  rewrites), clean up safe (declare)s, kondo-lint every touched namespace,
  and RUN THE AFFECTED TESTS for everything the episode touched (no
  test_run needed first — mid-episode runs are for spot-checks). Unused
  PUBLIC surface in touched namespaces GATES here (error-grade): delete it
  or mark the name ^:unused-ok; a stale marker (the var is called now)
  fails symmetrically. Findings ride the boundary delta so the next
  session's brief surfaces anything left red. Returns {:done id
  :normalized n :rewrites [{:form :applied}] :lint [...] :test s
  :findings {...}}."
  [session & {:keys [label agent external?] :or {external? true}}]
  (let [st       (:store @session)
        changed  (->> (query/episode-span st agent)
                      (filter #(and (contains? query/content-ops (:op %))
                                    (= agent (:agent %))))
                      (mapcat history/delta-fids)
                      distinct
                      (filter #(store/ns-of-form-id st %)))
        rewrites (done/normalize-rewrites changed st)
        _        (done/apply-normalization! rewrites st label agent  session)
        ;; automatic declare hygiene: the pipeline OWNS declares (auto-inserted
        ;; for a genuine cycle); once the cycle breaks the declare is stale —
        ;; remove it here. SILENT: the agent never manages declares, so this
        ;; runs for effect and is not reported.
        _
        (doseq [ns* (distinct (keep #(store/ns-of-form-id (:store @session) %)
                                    changed))]
          (api/fix-declares! session ns*
                         :prompt (or label "done declare hygiene")
                         :agent agent))
        ;; require hygiene, REPORTED (unlike declares, which the agent never
        ;; sees): try dropping each unused require — a genuinely dead one goes,
        ;; a load-bearing one is restored marked ^:side-effect. The agent never
        ;; manages unused requires; done does.
        pruned-reqs
        (into (sorted-map)
              (for [ns* (distinct (keep #(store/ns-of-form-id (:store @session) %)
                                        changed))
                    :let [pr (api/prune-requires! session ns*
                                                  :prompt (or label "done require hygiene")
                                                  :agent agent)]
                    :when (or (seq (:pruned pr)) (seq (:kept pr)))]
                [ns* pr]))
        ;; kondo lint over every namespace touched since the last done-point —
        ;; carried mid-episode errors (stale callers) get re-checked HARD here
        lint (done/anchored-lint session changed)
        ;; the unused-public GATE: unmarked dead surface — and stale
        ;; ^:unused-ok markers — join as ERROR-grade lint (never demoted)
        unused-rep (let [st* (:store @session)]
                     ;; episode-scoped, like the lint scan: a form elsewhere
                     ;; can become dead because THIS episode deleted its last
                     ;; caller, so the store-wide sweep is real — it is just
                     ;; `full_check`'s job, not every done point's.
                     (modules/unused-report
                      st* (distinct (keep #(store/ns-of-form-id st* %) changed))))
        lint (done/with-unused-gate lint unused-rep)
        ;; NEW warnings (on forms this episode touched) report in full;
        ;; CARRIED ones (pre-existing, untouched forms) compress to a count —
        ;; re-listing them at every done buries real findings. Errors and
        ;; unattributed rows never demote.
        touched-q (into #{}
                        (keep (fn [fid]
                                (let [st* (:store @session)]
                                  (when-let [e (store/form-by-id st* fid)]
                                    (symbol (str (store/ns-of-form-id st* fid))
                                            (str (or (:name e) (:id e))))))))
                        changed)
        loud?     (fn [f] (or (= :error (:level f))
                              (nil? (:form f))
                              (contains? touched-q (:form f))))
        lint-new  (vec (filter loud? lint))
        carried   (vec (remove loud? lint))
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
                ;; the ENTIRE in-image suite, not the impacted slice: "done
                ;; means done". Impacted-only answered the weaker question
                ;; "does what I touched still work" — and impacted SELECTION
                ;; was itself a source of misses (one untraced form used to
                ;; collapse the whole narrowing, on 54.4% of real episodes).
                ;; Running everything retires that machinery here.
                ;;
                ;; The full ISOLATED tier is still skipped (it spawns JVMs)
                ;; and the findings SAY so, so running it stays a visible
                ;; choice rather than a silent omission.
                main-ns  (vec (sort (keys (:namespaces st*))))
                ;; nil affected => every test in main-ns; :edited still powers
                ;; the red :implicated correlation
                s        (session/run-verification! session main-ns nil
                                            :edited qsyms
                                            :include-integration? true
                                            :boundary? true)]  ; M5
            (session/commit-appended! session
                              #(store/record-verification % main-ns s) [])
            s))
        ;; the tier is an implementation detail: ^:external tests the episode's
        ;; changes reach run in the EXTERNAL tier here — capped, and a deferral
        ;; is REPORTED (external-pending), never silent.
        ;;
        ;; #127: selected from THE TRACE, like the in-image half above, instead
        ;; of re-derived from the require-closure. That closure selects a median
        ;; 43 of 46 external test nses (measured over every source ns
        ;; 2026-07-17) — it never narrowed, it just always blew the cap, so 84.6%
        ;; of changes deferred and the tier effectively never ran here. The
        ;; evidence was already computed a few lines up and thrown away.
        iso (when (and external? (seq changed))
              (let [st*      (:store @session)
                    iso-only (session/impacted-external session st* changed)]
                ;; #132: impacted-external is never silent — an untraced form expands
                ;; to its own namespace's reach — so the old closure fallback is
                ;; gone with the collapse that needed it. Run exactly the named
                ;; tests. A :only run is one serial JVM (it never shards), so the
                ;; cap is on TESTS: p50 is 12 covering tests and a cap of 40 fits
                ;; ~71% of forms, while the tail (p90 = 218) is the core-form
                ;; case that honestly wants the whole suite anyway.
                (when (seq iso-only)
                  (if (<= (count iso-only) 40)
                    (external-test-run! session :only iso-only)
                    {:pending {:count (count iso-only)
                               :tests (vec (take 5 iso-only))
                               :note  (str "first 5 shown — over the per-done cap, so these"
                                           " deferred; test_run {external true} or"
                                           " full_check runs them all")}}))))
        findings (let [lint-errors (count (filter #(= :error (:level %)) lint))
      lint-warns  (vec (for [f lint :when (= :warning (:level f))]
                         (select-keys f [:form :type :message])))
      failures    (+ (:fail summary 0) (:error summary 0)
                     (:failures iso 0) (:errors iso 0))
      iso-red?    (contains? #{:red :error} (:status iso))
      st*         (:store @session)
      ;; the done-time advisory REGISTRY (D9 rule-registry, done grain): schema
      ;; drift (status-affecting), key typos + contract breakage (advisory). A
      ;; new done finding registers in slopp.api.rules/done-advisories — ONE
      ;; entry — not by hand-wiring a binding, a clause, and a status term here.
      advisories  (rules/run-done-advisories! session st* changed)
      missing-doc (vec (sort (distinct
                              (keep (fn [fid]
                                      (when-let [e (store/form-by-id st* fid)]
                                        (:var (edit.modules/missing-doc-warning
                                               st*
                                               (store/ns-of-form-id st* fid)
                                               (:name e)))))
                                    changed))))]
  (cond-> {:test-status (cond (and (nil? summary) (nil? iso) (zero? lint-errors)) :none
                              (or (pos? failures) iso-red?
                                  ;; lint errors — which INCLUDE dead public
                                  ;; surface, folded in as ERROR rows by
                                  ;; with-unused-gate — are part of "is this
                                  ;; codebase good?". They were absent here
                                  ;; while commit-point! kept its own dead-surface
                                  ;; scan; with that removed, omitting them let a
                                  ;; store with dead surface milestone green.
                                  (pos? lint-errors)
                                  (rules/status-affecting-fired? st* advisories)) :red
                              :else                           :green)
           :failures    failures
           :lint-errors lint-errors
           ;; done runs the WHOLE in-image suite but not the full external
           ;; tier. Say so EVERY time: an unstated omission reads as coverage,
           ;; and that is how a green status comes to mean less than the agent
           ;; thinks it does.
           ;; done is EPISODE-scoped: the whole in-image suite plus impacted
           ;; ^:external tests, but lint and dead-surface cover only what this
           ;; episode touched, and the full external + integration tiers do
           ;; not run. Say so EVERY time: an unstated omission reads as
           ;; coverage, and that is how a green status comes to mean less than
           ;; the agent thinks it does.
           :scope
           (str "EPISODE-scoped: lint + dead-surface cover only the namespaces"
                " you touched, and the full ^:external / ^:integration tiers"
                " did not run. `full_check` does the whole store — every"
                " namespace, every tier. Nothing forces it, including the"
                " milestone; run it when the change is broad, when you deleted"
                " a caller, or before a commit you want to stand behind")}
    ;; ADVISORY, and named as such: kondo findings slopp's config
    ;; deliberately does not block on, because each is routinely true of a
    ;; form mid-edit. Listed so the agent can judge them, never counted.
    (seq lint-warns)  (assoc :lint-warnings lint-warns)
    (:pending iso)    (assoc :external-pending (:pending iso))
    (seq missing-doc) (assoc :missing-doc missing-doc)
    (seq advisories)  (merge advisories)
    (seq (:unused unused-rep)) (assoc :unused-public (:unused unused-rep))
    (seq (:stale unused-rep))  (assoc :stale-unused-ok (:stale unused-rep))))
        cid (let [v (volatile! nil)]
              (session/commit-appended! session
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
             :lint       lint-new
             :findings   findings}
      (seq carried)       (assoc :lint-carried
                                 {:count (count carried)
                                  :forms (vec (sort (distinct (keep :form carried))))})
      summary             (assoc :test summary)
      (seq pruned-reqs)   (assoc :pruned-requires pruned-reqs)
      (:status iso)       (assoc :external iso))))

(defn ^:export full-check!
  "The WHOLE-STORE check, on demand: kondo over every namespace, the
  dead-public-surface report over every namespace, and every test in every
  tier — the in-image suite, `^:integration`, and the external `^:external`
  tier.

  Deliberately NOT forced anywhere, not by `done` and not by `commit_point`.
  `done` is episode-scoped: it answers whether the work you just did is good,
  which is the question you can act on. This answers whether the STORE is
  good, which is a different and much slower question — and one only the
  agent can judge the right moment for. `done` names this tool in its result
  so the choice is visible rather than forgotten.

  It also retires any need for an integration-only or lint-only tool: one
  call, everything, no tier flags to get wrong.

  Returns {:lint [...] :lint-errors n :lint-warnings n :unused [...] :stale
  [...] :test {...} :external {...} :status :green|:red}."
  [session & {:keys [affected]}]
  (let [st    (:store @session)
        nses  (sort (keys (:namespaces st)))
        ;; The whole-store gate must not inherit incremental kondo state. kondo
        ;; reads cross-ns facts from a disk cache that each lint TEACHES, so a
        ;; cache predating recent vars makes whatever is linted early get judged
        ;; against yesterday's facts — once four phantom `invalid-arity` ERRORS
        ;; and eleven unresolved vars, on a store whose every test passed. A
        ;; STALE fact lies confidently; an ABSENT one is benign.
        _     (index/reset-kondo-cache!)
        ;; and teach it callees-first — the same "deps first" order every loader
        ;; uses — so nothing is judged against a fact not yet refreshed
        lint  (vec (for [n (store/ns-dependency-order st)
                         :let [src (render/render-ns st n)]
                         f (index/lint src (store/kondo-lang st n))]
                     (-> f (dissoc :row :col) (assoc :ns n))))
        rep   (modules/unused-report st nses)
        ;; tier LAYERING — a whole-graph property, so it lives here rather
        ;; than at a declaration: core must not depend on shell. This is the
        ;; check effect-reachability cannot make, since that sees a cross-ns
        ;; effect only when the callee is `!`-named.
        layer (vec (for [n nses
                         :when (not (str/ends-with? (str n) "-test"))
                         :let [t (edit.modules/tier-for st n)]
                         v (edit.modules/layering-violations st n t)]
                     {:ns n :tier t :requires (:requires v) :requires-tier (:tier v)}))
        errs  (filterv #(= :error (:level %)) lint)
        warns (filterv #(= :warning (:level %)) lint)
        tests (session/run-verification! session (vec nses) nil
                                         :include-integration? true
                                         :boundary? true)
        ;; ONLY this tier narrows. Measured: the external suite is ~187s of a
        ;; ~190s full_check (299 image boots), while lint + dead surface +
        ;; layering + the in-image suite together are ~5-7s. Narrowing the
        ;; cheap half would buy nothing and cost exactly the coverage
        ;; full_check exists for.
        iso   (when (seq (session/external-test-nses
                          st (filter #(session/test-ns? st %) nses)))
                (external-test-run! session :affected affected))
        red?  (or (seq errs) (seq (:unused rep)) (seq (:stale rep))
                  (seq layer)                ; core→shell is a failure, not a note
                  (pos? (+ (:fail tests 0) (:error tests 0)))
                  (contains? #{:red :error} (:status iso)))]
    (cond-> {:namespaces (count nses)
             :lint-errors (count errs)
             :lint-warnings (count warns)
             :test tests
             :status (if red? :red :green)}
      affected (assoc :scope (str "lint, dead surface, layering and the in-image"
                                  " suite covered ALL " (count nses) " namespaces;"
                                  " the ^:external tier was narrowed to the tests"
                                  " that changes since the last milestone can"
                                  " reach. Drop :affected for the whole tier"))
      (seq errs)          (assoc :lint errs)
      (seq warns)         (assoc :warnings warns)
            (seq layer)         (assoc :tier-layering layer
                                 :tier-layering-note
                                 (str (count layer) " core→shell dependency(ies):"
                                      " a namespace depends on one at a LOOSER"
                                      " tier. Either move what it needs into a"
                                      " core namespace, or its own tier is a"
                                      " claim it does not earn"))
      (seq (:unused rep)) (assoc :unused-public (:unused rep))
      (seq (:stale rep))  (assoc :stale-unused-ok (:stale rep))
      iso                 (assoc :external iso))))

(defn ^:export commit-point!
  "Record a MILESTONE (P4-m7): run the full done pipeline (normalize,
  declare hygiene, verify) for `:agent`, then append a `:commit` marker
  pointing at the resulting state with a human `description`.

  THE MILESTONE HAS NO GATES OF ITS OWN. It runs `done!` and gates on that
  verdict — nothing is re-judged here, and nothing whole-store is forced.
  `full_check` (every namespace, every tier) is the agent's call, before a
  commit or any other time; a milestone records what the done point verified. Two enforcement points DRIFT: this
  function used to recompute status from raw test counts and so never saw
  the `:error` done-advisories at all, and it carried its own copies of the
  dead-surface and lint scans. `done` means done, which only holds if done
  is the single bar; a second bar is somewhere to accidentally put a check
  that then does not apply at done.

  GREEN-GATED: a red verification refuses the milestone (the done still
  stands — fix and retry) unless `:force true`, which records `:status :red`
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
                  (session/commit-appended!
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
        (mark! target (history/status-at (:store @session) target) {} extra)
        {:error (str "no delta " target " in this branch's history")})

      :else
      (let [last-d (last (store/deltas (:store @session)))]
        (if (= :commit (:op last-d))
          (merge {:commit (:id last-d) :target (:target last-d)
                  :status (:status last-d)
                  :description (:description last-d)
                  :note "nothing changed since this milestone — returning it"})
          (let [cp     (done! session :label description :agent agent)
                ;; done runs the impacted ^:external slice itself (:external?
                ;; defaults true), so the milestone's done is a REAL done — not
                ;; one weakened to skip the tier the in-image suite already
                ;; skips. The milestone still runs no WHOLE-store check (that is
                ;; `full_check`, the agent's call, per D-full-check): a red
                ;; ^:external test the episode never TOUCHED does not stop it,
                ;; but one this episode touched does — exactly what a standalone
                ;; done catches. :force skips straight to an honest red.
                st     (:store @session)
                head   (:id (last (store/deltas st)))
                ;; done's OWN verdict — it already accounts for failures, the
                ;; :error advisories, store-wide lint and store-wide dead
                ;; surface. Believe it rather than re-deriving a weaker answer.
                ;; :none means this done judged NOTHING (no writes since the last
                ;; one) — so the previous real verdict stands. Otherwise a red
                ;; done is laundered by committing without changing anything.
                ;; the findings this milestone is judged on: THIS done's when it
                ;; judged something, otherwise the last done that did.
                verdict (if (#{:red :green} (get-in cp [:findings :test-status]))
                          (:findings cp)
                          (api/last-judged-done st))
                status  (or (:test-status verdict) (history/status-at st head))
                status (if (= :unknown status) :green status) ; nothing ever ran red
                ;; P4-m8: snapshot the rendered tree — byte-exact, trivia intact —
                ;; so the git projection is a pure function of this marker delta
                tree   (into (sorted-map)
                             (map (fn [n] [n (render/render-ns st n)]))
                             (keys (:namespaces st)))
                ;; a SUMMARY of done's findings, not a second implementation:
                ;; name the findings that actually fired so the refusal is
                ;; actionable without re-deriving anything
                ;; :scope and :lint-warnings are INFORMATIONAL — always present,
                ;; never a reason. Listing them as things that fired made a
                ;; refusal say "scope" instead of "unused-public".
                wrong  (->> (dissoc verdict :test-status
                                    :scope :lint-warnings :failures)
                            (remove (fn [[_ v]] (or (and (number? v) (zero? v))
                                                    (and (coll? v) (empty? v)))))
                            (map (comp name key))
                            sort vec)]
            (if (and (= :red status) (not force))
              {:error (str "verification is RED — milestone refused"
                           (when (seq wrong)
                             (str " — " (str/join ", " wrong)))
                           ". Your work is at its done-point; the full"
                           " list is in :findings — and if this done"
                           " judged nothing (no writes since the last"
                           " one), the RED verdict of that earlier done"
                           " still stands. Fix and retry, or :force"
                           " true to record a red milestone honestly.")
               :status :red :done (:done cp) :test (:test cp)
               :findings verdict}
              (mark! head status {:done (:done cp)}
                     (cond-> (merge {:tree tree} extra)
                       (seq (:deps st))  (assoc :deps (:deps st))
                       (seq (:files st)) (assoc :files (:files st))
                       (or (seq (:config st)) (modules/modules-config-entry st))
                            (assoc :config (cond-> (:config st)
                                             (modules/modules-config-entry st)
                                             (assoc "modules" (modules/modules-config-entry st)))))))))))))

(defn ^:export config!
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

(defn- boot-image!
  "Bring the session's image up: spawn it, warm a spare, schedule the branch
  reaper, load every namespace (dependency order, stubbing red-first specs),
  and adopt modules. THE slow part of open! (loading N namespaces into a
  child JVM). On the SYNC path (no :image-ready) a failure throws, as open!
  always did; on the ASYNC path (a background thread) the failure is
  delivered to the ready-promise so `api/await-image!` surfaces it on first
  oracle use instead of killing the server at startup. Returns the session."
  [session store conn agent-id ttl]
  (try
    (let [image (repl/start! {:slopp.image.repl/deps (:deps store)})]
      (swap! session assoc :image image)
      (session/start-spare! session)
      (let [t      (java.util.Timer. "slopp-branch-reaper" true)
            period (long (max 1000 (quot ttl 3)))]
        (.schedule t
                   (proxy [java.util.TimerTask] []
                     (run [] (try (api/reap-idle-images! session)
                                  (catch Throwable _))))
                   period period)
        (swap! session assoc :reaper t))
      (doseq [ns-sym (store/ns-dependency-order store)]     ; X3: deps first
        (when-let [err (image/load-ns! image store ns-sym)]
          ;; a store carrying red-first specs still opens — stub and retry
          (when-not (and (session/stub-missing-test-vars! image store [ns-sym])
                         (nil? (image/load-ns! image store ns-sym)))
            (throw (ex-info (str "image load failed for " ns-sym ": " err) {})))))
      ;; module adoption: a populated store from a pre-module db (:modules
      ;; nil) gets its manifest derived from reality, once — fresh stores
      ;; are born with {} and enforcement already on
      (when (and conn (seq (:namespaces store))
                 (or (nil? (:modules store))
                     (and (empty? (:modules store))
                          (not-any? #(= :module-edge (:op %)) (:deltas store)))))
        (api/adopt-modules! session :agent (or agent-id "slopp")))
      (when-let [p (:image-ready @session)] (deliver p :ok))
      session)
    (catch Throwable t
      (if-let [p (:image-ready @session)]
        (do (deliver p t) session)   ; async: rides home to await-image!
        (throw t)))))

(defn ^:export ^{:live-handle true
        :malli/schema
        [:=> [:cat [:? [:map
                        [:slopp.api/dir {:optional true} [:maybe :some]]
                        [:slopp.api/warm-spare? {:optional true} [:maybe :boolean]]
                        [:slopp.api/async-image? {:optional true} [:maybe :boolean]]
                        [:slopp.api/branch-image-ttl-ms {:optional true} [:maybe :int]]
                        [:slopp.api/agent-id {:optional true} [:maybe :string]]]]]
         :any]}
  open!
  "Start a session: the owned image + the store — loaded from `<dir>/.slopp/`
  when `:slopp.api/dir` is given and it has history, empty otherwise.
  `:slopp.api/warm-spare? true` keeps a spare image warming in the background
  so restarts are near-instant. `:slopp.api/agent-id` (default:
  session-identity) keys every delta/turn/episode this session writes.

  `:slopp.api/async-image? true` returns as soon as the store VALUE is
  loaded (fast) and boots the image on a BACKGROUND thread — the MCP server
  uses this so its `initialize` handshake completes without waiting for N
  namespaces to load into a child JVM (which, under load, raced the client's
  connect timeout and left a concurrent session with zero tools). Read-only
  store tools serve immediately; oracle/write tools `api/await-image!` the
  boot. The DEFAULT stays synchronous — every existing caller gets a
  fully-loaded image on return, unchanged.

  The option keys are QUALIFIED — `{:slopp.api/dir …}` — and the schema, the
  destructure, and every call site agree. (The schema once documented bare
  `:dir` while the destructure required the qualified key, so a caller
  trusting it silently opened an EMPTY store — on the busiest entry point in
  the store.)

  The `:=>` schema is DOCUMENTATION, not a verified claim: this fn boots a
  JVM, so `analyzer-pure?` excludes it from the generative oracle-check.

  The session atom is built FIRST and every resource lands in it as it comes
  up, so the single failure path is `close!` — which is per-resource safe.
  Before this, a throw during the image-load loop abandoned the booted image,
  the warming spare, the reaper timer, and the SQLite connection: the atom
  never reached the caller, so nothing could ever release them."
  ([] (open! {}))
  ([{:slopp.api/keys [agent-id branch-image-ttl-ms dir warm-spare? async-image?]}]
   (let [conn    (when dir (db/open! dir {:create? false}))
         session (atom {:db conn :dir dir :branch "main" :lines {}})]
     (try
       (let [store (or (some-> conn db/load-store) (store/empty-store))
             ttl   (or branch-image-ttl-ms 600000)]
         ;; SYNC phase: the store value + everything reads need, no image
         (swap! session assoc
                :store store
                :data-version (some-> conn db/data-version)
                :test-map (or (session/load-trace conn store) {})
                :observed (session/load-observations conn)
                :agent-id (or agent-id (session/session-identity))
                :env-agent? (boolean (not-empty (System/getenv "SLOPP_AGENT")))
                :branch-image-ttl-ms ttl
                :warm-spare? (boolean warm-spare?))
         ;; #134: kondo's cross-ns cache follows the STORE, not the process cwd.
         ;; Unset, kondo resolves it from cwd — so cross-ns findings existed only
         ;; where a .clj-kondo/ happened to sit beside the process, and a user
         ;; project's :carried stale-caller gate silently found nothing. A dirless
         ;; session gets an owned temp dir rather than inheriting whatever is there.
         (reset! index/kondo-cache-dir
                 (if conn
                   (str (io/file dir ".slopp" "kondo-cache"))
                   (str (java.nio.file.Files/createTempDirectory
                         "slopp-kondo"
                         (make-array java.nio.file.attribute.FileAttribute 0)))))
         ;; image boot: inline (sync default) or on a daemon thread (async),
         ;; which arms the ready-promise await-image! blocks on
         (if async-image?
           (do (swap! session assoc :image-ready (promise))
               (doto (Thread. ^Runnable #(boot-image! session store conn agent-id ttl)
                              "slopp-image-boot")
                 (.setDaemon true)
                 (.start))
               session)
           (boot-image! session store conn agent-id ttl)))
       (catch Throwable t
         (api/close! session)
         (throw t))))))

(defn ^:export spot-run!
  "The tier-aware SPOT-CHECK behind test_run {ns ..}/{only ..}: each named
  target runs in ITS tier — in-image members through the traced, diagnosed
  in-image runner, ^:external members through ONE serial external JVM
  (build + cognitect -v), which is the targeted fresh run the red/green
  loop on an external test needs (naming one used to match 0 tests
  in-image and teach a manual whole-ns detour). No external member named →
  exactly the in-image run of api/test-run!. Entries that cannot be
  tier-resolved (unqualified without :ns, unknown names) stay on the
  in-image side, where the 0-matched teaching still applies."
  [session & {:keys [ns only fresh]}]
  (let [st       (:store @session)
        ns-sym   (some-> ns symbol)
        tiers    (memoize (fn [tns] (session/test-var-tiers st tns)))
        qual     (fn [o] (let [s (str o)]
                           (if (str/includes? s "/")
                             (symbol s)
                             (when ns-sym (symbol (str ns-sym) s)))))
        ext?     (fn [q] (let [tns (symbol (namespace q))
                               nm  (symbol (name q))]
                           (boolean (some #(= nm %) (:external (tiers tns))))))
        pairs    (map (fn [o] [o (qual o)]) only)
        ext      (cond
                   (seq only) (vec (for [[_ q] pairs :when (and q (ext? q))] q))
                   ns-sym     (mapv #(symbol (str ns-sym) (str %))
                                    (:external (tiers ns-sym)))
                   :else      [])
        img-only (seq (for [[o q] pairs :when (not (and q (ext? q)))] o))
        img?     (cond
                   (seq only) (boolean img-only)
                   ns-sym     (boolean (seq (:image (tiers ns-sym))))
                   :else      true)]
    (cond
      (empty? ext)
      (api/test-run! session ns-sym :only only :fresh fresh)

      (not img?)
      (assoc (external-test-run! session :only ext)
             :note "external-tier spot-check — ran in one fresh serial JVM")

      :else
      (let [img (api/test-run! session ns-sym :only img-only :fresh fresh)
            ex  (external-test-run! session :only ext)]
        ;; the external members RAN — the in-image side's pending note about
        ;; them would contradict the result beside it
        {:image    (dissoc img :note :external-pending)
         :external ex
         :status   (if (or (pos? (:fail img 0)) (pos? (:error img 0))
                           (not= :green (:status ex)))
                     :red
                     :green)}))))

(defn ^:export store-health
  "What this store CARRIES, in bytes — the journal per op (heaviest first, with
  `:commit` tree snapshots counted APART from payloads), the materialized state,
  and the blob table. Cheap: SQLite LENGTH only, nothing parsed.

  Reach for it when a session feels slow to open, before growing what a delta
  carries, and periodically. `full_check` answers whether the store is CORRECT;
  this answers what it COSTS, and nothing else did — which is how a byte-exact
  tree snapshot inline in every milestone reached 94% of a 344MB journal,
  unnoticed across 239 of them, against a design note estimating \"tens of KB\".
  A store can rot by growing."
  [session]
  (if-let [conn (:db @session)]
    (db/journal-stats conn)
    {:note "no durable store on disk yet — nothing has been written"}))
