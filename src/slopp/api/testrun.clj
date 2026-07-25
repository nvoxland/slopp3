(ns slopp.api.testrun
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [slopp.image.repl :as repl] [slopp.image.testmain :as testmain] [clojure.java.io :as io] [clojure.edn :as edn] [slopp.edit.refs :as refs]))

(defn ^{:export "slopp.verification"} parse-test-summary
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

(defn ^{:export "slopp.verification"} parse-test-failures
  "The FAIL/ERROR blocks from a clojure.test runner's output:
  [{:test name :detail block}] (up to `limit` blocks, each capped ~500 chars)
  — so an external run NAMES its failures instead of making the caller
  rebuild the project and rerun the suite just to see them (Q2)."
  [output & {:keys [limit] :or {limit 5}}]
  (->> (str/split (str output) #"\n(?=(?:FAIL|ERROR) in )")
       (keep (fn [b]
               (when-let [[_ nm] (re-find #"^(?:FAIL|ERROR) in \(([^)\s]+)\)" b)]
                 (let [block (-> (->> (str/split-lines b)
                                     (take-while (complement str/blank?))
                                     (str/join "\n"))
                                 ;; strip the VFS coordinate — the test is
                                 ;; NAMED in :test; file:line is unconsumable
                                 (str/replace #"\s*\([\w/._-]+\.clj:\d+(?::\d+)?\)" ""))]
                   {:test nm
                    :detail (if (< 500 (count block))
                              (str (subs block 0 500) " …")
                              block)}))))
       (take limit)
       vec))

(defn ^{:export "slopp.verification"} failing-test-rollup
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

(defn ^{:export "slopp.verification"} failure-themes
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

(defn ^{:export "slopp.verification"} auto-parallel
  "Default shard count for an external run over `n` test namespaces on a
  `cores`-core box. Each shard reloads the WHOLE materialized store, so
  sharding only pays at real scale: 1 below ~8 test nses (boot overhead
  beats the gain), then n/8 shards, capped at 4 and at half the cores."
  [n cores]
  (max 1 (min 4 (quot cores 2) (quot n 8))))

(def shard-timeout-ms
  "Upper bound for one test-runner JVM. A hung ^:external test used to block
  sh/sh forever — wedging done! and the milestone gate with it. Test failures
  PARSE; the only thing this deadline ever kills is a JVM that stopped
  talking."
  (* 20 60 1000))

(defn ^{:export "slopp.verification"} run-cmd!
  "Run `cmd` (a seq of strings) in `dir`, sh-shaped {:exit :out :err}, killed
  at `timeout-ms` (destroy, then destroyForcibly): :exit 124 and no parseable
  summary, which the shard-death retry treats honestly as a dead JVM. Output
  is drained on its own thread so a chatty child cannot fill the pipe and
  deadlock the wait."
  ([cmd dir] (run-cmd! cmd dir shard-timeout-ms))
  ([cmd dir timeout-ms]
   (let [pb   (doto (ProcessBuilder. ^java.util.List (mapv str cmd))
                (.directory (io/file dir))
                (.redirectErrorStream true))
         proc (.start pb)
         out  (future (slurp (.getInputStream proc)))]
     (if (.waitFor proc timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
       {:exit (.exitValue proc) :out (deref out 10000 "") :err ""}
       (do (.destroy proc)
           (when-not (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)
             (.destroyForcibly proc))
           {:exit 124
            :out (str (deref out 1000 "")
                      "\n[slopp] test runner exceeded " timeout-ms "ms — killed")
            :err ""})))))

(defn ^:export balance-shards
  "Split `nses` into `n` shards balanced by IMAGE BOOTS rather than by index.

  Shards run concurrently, so the external tier's wall time is its SLOWEST
  shard, not the average — and a shard's cost is dominated by how many fresh
  image subprocesses its tests boot (~1.15s of Clojure loading each, and that
  boot IS the isolation the tier exists for). Round-robin by index ignored
  that. Measured on slopp's own suite: 402 boots across 52 test namespaces
  split `[139 100 90 73]`, so one shard still had 66 boots to go after the
  fastest had finished. Longest-first gives `[101 101 100 100]` — 27% off the
  critical path for the same work on the same cores.

  This is NOT the warm-pool dead end (`ideas/full-check-is-slow.md`), which
  rescheduled boot work into CPU that was not idle and measured zero gain. It
  removes idle time that already exists, and adds no concurrency.

  The weight is static calls to `open!` read from THE reference graph, not a
  source scan, so it tracks the code and cannot drift. A namespace whose tests
  boot nothing weighs 0 and packs freely. The order is total (weight, then
  name), so the split is deterministic — a shard assignment that varied
  between runs would make a flake unreproducible."
  [store nses n]
  (let [w    (frequencies (map :from-ns
                               (concat (refs/refs-to store 'slopp.api.external/open!)
                                       (refs/refs-to store 'slopp.api/open!))))
        cost (fn [grp] (reduce + 0 (map #(get w % 0) grp)))]
    (reduce (fn [shards x]
              (let [i (apply min-key #(cost (nth shards %)) (range (count shards)))]
                (update shards i conj x)))
            (vec (repeat n []))
            (sort-by (juxt #(- (get w % 0)) str) nses))))

(defn ^:export only-shards
  "Split `only` — qualified test vars — into at most `n` shards, along
  NAMESPACE lines and weighted the same way whole-namespace shards are.

  A namespace cannot straddle two shards. The shard command passes `-n` per
  namespace alongside `-v` per var, and cognitect's var filter resolves a name
  only within a DISCOVERED namespace, so splitting one namespace's vars across
  shards would silently drop tests.

  **Why this exists.** Narrowing and sharding used to be mutually exclusive:
  with `:only` set, `full-set` was nil, `par` fell to 1, and the run was one
  serial JVM. So a narrowed run of 130 tests cost more than the sharded full
  suite, and `done` deferred instead — measured, on 37.5% of recent calls,
  with six of fifteen deferrals in the 52–136 range. The trace map had
  identified those tests correctly; the runner simply could not act on the
  answer. This is the runner catching up to the index."
  [store only n]
  (let [by-ns  (group-by #(symbol (namespace (symbol (str %)))) only)
        shards (balance-shards store (keys by-ns) n)]
    (filterv seq (mapv #(vec (mapcat by-ns %)) shards))))

(defn ^{:export "slopp.verification"} run-shard!
  "Shell one test shard: a fresh `clojure -M<alias>` over `grp`'s namespaces
  in the materialized `dir`, bounded by `shard-timeout-ms` via `run-cmd!`.
  The seam the shard-death retry rides.

  With `only` (qualified test vars), the shard runs just those — `-n` per
  namespace AND `-v` per var, because cognitect's var filter resolves a name
  only within a namespace it has discovered. `grp` must still name every
  namespace `only` mentions; `only-shards` builds both halves together."
  ([alias dir grp] (run-shard! alias dir grp nil))
  ([alias dir grp only]
   (run-cmd! (concat [repl/clojure-bin (str "-M" alias)]
                     (mapcat #(vector "-n" (str %)) grp)
                     (mapcat #(vector "-v" (str %)) only))
             dir)))

(defn ^{:export "slopp.verification"} read-traces
  "Merge the form traces this run's shards wrote into the built `dir` (#121):
  {qualified-test-sym #{qualified-form-sym ...}}, or **nil** when none were
  written.

  nil, not {}: 'the external tier traced nothing' and 'the external tier did
  not trace' are different claims, and only the second is true of a store
  whose build carries no trace runner. An empty map would absorb as evidence.

  `merge-with into` because the run is round-robin SHARDED across concurrent
  JVMs in one dir — each shard emits a partial map, and a test seen by two of
  them must union its forms rather than have half of them dropped."
  [dir]
  (let [fs (->> (.listFiles (io/file dir))
                (filter #(str/starts-with? (.getName ^java.io.File %)
                                           testmain/trace-file-prefix)))]
    (when (seq fs)
      (->> fs
           (map #(edn/read-string (slurp %)))
           (apply merge-with into)))))

(defn anchor-output
  "Runner output made boundary-safe: file.clj:LINE coordinates lose the line
  suffix (a bare file name is not a coordinate and passes the response
  audit; agents anchor by name + snippet, and a crash tail's value is the
  MESSAGE, not the line number)."
  [s]
  (str/replace (str s) #"(\.clj[cx]?):\d+(?::\d+)?" "$1"))
