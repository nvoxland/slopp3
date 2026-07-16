(ns slopp.api.testrun
  (:require [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]
            [slopp.repl :as repl]))

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
  — so an isolated run NAMES its failures instead of making the caller
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
  "Default shard count for an isolated run over `n` test namespaces on a
  `cores`-core box. Each shard reloads the WHOLE materialized store, so
  sharding only pays at real scale: 1 below ~8 test nses (boot overhead
  beats the gain), then n/8 shards, capped at 4 and at half the cores."
  [n cores]
  (max 1 (min 4 (quot cores 2) (quot n 8))))

(defn ^{:export "slopp.verification"} run-shard!
  "Shell one test shard: a fresh `clojure -M<alias>` over `grp`'s namespaces
  in the materialized `dir`. The seam the shard-death retry rides."
  [alias dir grp]
  (apply sh/sh (concat [repl/clojure-bin (str "-M" alias)]
                       (mapcat #(vector "-n" (str %)) grp)
                       [:dir dir])))
