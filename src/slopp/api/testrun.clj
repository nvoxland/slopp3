(ns slopp.api.testrun
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [slopp.repl :as repl] [slopp.testmain :as testmain] [clojure.java.io :as io] [clojure.edn :as edn]))

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

(defn ^{:export "slopp.verification"} run-shard!
  "Shell one test shard: a fresh `clojure -M<alias>` over `grp`'s namespaces
  in the materialized `dir`, bounded by `shard-timeout-ms` via `run-cmd!`.
  The seam the shard-death retry rides."
  [alias dir grp]
  (run-cmd! (concat [repl/clojure-bin (str "-M" alias)]
                    (mapcat #(vector "-n" (str %)) grp))
            dir))
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
