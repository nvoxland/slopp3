(ns slopp.boot
  "Run a slopp store's program directly from the db — no exported source. Reads
  every namespace's byte-exact source from `<dir>/.slopp/store.db` (the
  `elements` table) and loads it into the CURRENT JVM in dependency order, then
  invokes the entry point (default `slopp.mcp/-main`). This is the in-process
  analogue of `slopp.image/load-ns!`, and the general counterpart to `build!`
  (which spits files): the store is RUN, not materialized.

  It is self-contained on purpose (only next.jdbc + clojure core, no internal
  slopp requires) so it can bootstrap slopp itself. Two modes: `--snapshot`
  (default) loads a fixed version at startup; `--live` also tracks the store's
  data_version and hot-reloads changed namespaces into this JVM as they commit.

  slopp running itself is just the self-host instance — `slopp.boot` + `slopp.rt`
  + the dep coords are slopp-the-tool, not project source, so ANY store runs from
  its db with zero project source files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]))

(defn- log! [& parts]
  (.println System/err ^String (apply str parts)))

;; --- store → source (raw jdbc; no slopp code, so it can bootstrap slopp) ---

^:reads (defn- open-conn [dir]
          (jdbc/get-connection
           (jdbc/get-datasource {:dbtype "sqlite"
                                 :dbname (str (io/file dir ".slopp" "store.db"))})))

^:reads (defn store-sources
          "{ns-sym source} for every namespace in the store db — byte-exact (each
  `elements.source` is a form's canonical CST string; concatenated by pos it IS
  render-ns output)."
          [conn]
          (reduce (fn [m row]
                    (update m (symbol (:elements/ns row)) (fnil str "") (:elements/source row)))
                  {}
                  (jdbc/execute! conn ["SELECT ns, source FROM elements ORDER BY ns, pos"])))

;; --- dependency order (internal requires only) ---

(defn- internal-requires
  "The in-store namespaces `source`'s ns form requires (external libs dropped)."
  [source all-nses]
  (let [form (try (edn/read-string source) (catch Exception _ nil))]
    (into #{}
          (for [clause (when (seq? form) (drop 2 form))
                :when  (and (seq? clause) (#{:require :use} (first clause)))
                spec   (rest clause)
                :let   [lib (cond (vector? spec) (first spec)
                                  (symbol? spec) spec)]
                :when  (and lib (contains? all-nses lib))]
            lib))))

(defn dependency-order
  "Store namespaces, dependencies first — a deterministic Kahn sort over the
  internal require graph (ties by sorted name; a cycle appends the sorted
  remainder). Mirrors slopp.store/ns-dependency-order without the store value."
  [sources]
  (let [all  (set (keys sources))
        deps (into {} (map (fn [[n s]] [n (internal-requires s all)])) sources)]
    (loop [order [], remaining (vec (sort (keys deps))), done #{}]
      (if (empty? remaining)
        order
        (if-let [ready (first (filter #(every? done (deps %)) remaining))]
          (recur (conj order ready) (vec (remove #{ready} remaining)) (conj done ready))
          (into order remaining))))))

;; --- load into the CURRENT jvm ---

(defn- stamp-loaded! [ns-sym]
  ;; mark the ns loaded so a later internal (require ...) is a no-op (there is
  ;; no .clj on the classpath for store nses) — the in-process image/load-ns! trick
  (dosync (commute @#'clojure.core/*loaded-libs* conj ns-sym)))

(defn load-store!
  "Load every namespace of the store at `dir` into the CURRENT JVM, dependency
  order: load-string each rendered source + a *loaded-libs* stamp. Returns the
  {ns source} map that was loaded."
  [dir]
  (with-open [conn (open-conn dir)]
    (let [sources (store-sources conn)]
      (doseq [ns-sym (dependency-order sources)]
        (load-string (get sources ns-sym))
        (stamp-loaded! ns-sym))
      sources)))

;; --- live mode: track the store, reload changed nses into this jvm ---

^:reads (defn- data-version [conn]
          (:data_version (jdbc/execute-one! conn ["PRAGMA data_version"])))

(defn watch-live!
  "Poll the store's data_version; when another writer commits, reload the
  namespaces whose source changed into THIS jvm (dependency order). The store's
  green-gate means only compilable code ever loads. Caveat: long-lived instances
  (servers, background threads) keep their old closure code until re-created."
  [dir & {:keys [interval-ms] :or {interval-ms 500}}]
  (let [conn (open-conn dir)]
    (loop [dv (data-version conn), prev (store-sources conn)]
      (Thread/sleep (long interval-ms))
      (let [dv2 (data-version conn)]
        (if (= dv dv2)
          (recur dv prev)
          (let [now     (store-sources conn)
                changed (filter #(not= (get prev %) (get now %)) (dependency-order now))]
            (doseq [ns-sym changed]
              (try (load-string (get now ns-sym))
                   (stamp-loaded! ns-sym)
                   (catch Throwable t
                     (log! "live-reload failed for " ns-sym ": " (.getMessage t)))))
            (when (seq changed) (log! "live-reloaded: " (str/join " " changed)))
            (recur dv2 now)))))))

;; --- entry ---

(defn- parse-args [args]
  {:dir   (or (first (remove #(str/starts-with? % "--") args))
              (System/getProperty "user.dir"))
   :live? (boolean (some #{"--live"} args))
   :main  (symbol (or (second (drop-while #(not= "--main" %) args))
                      "slopp.mcp/-main"))})

(defn -main
  "clojure -M -m slopp.boot <dir> [--snapshot | --live] [--main ns/fn]

  Load the store's program into THIS jvm and run its entry point (default
  slopp.mcp/-main). --live tracks the store and hot-reloads changed namespaces."
  [& args]
  (let [{:keys [dir live? main]} (parse-args args)]
    (log! "slopp.boot: loading store at " dir " (" (if live? "live" "snapshot") ")")
    (load-store! dir)
    (when live? (future (watch-live! dir)))
    (apply (requiring-resolve main) [dir])))