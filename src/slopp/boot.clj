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
  (let [f (io/file dir ".slopp" "store.db")]
    (io/make-parents f)
    (let [conn (jdbc/get-connection
                (jdbc/get-datasource {:dbtype "sqlite" :dbname (str f)}))]
      ;; the live watcher polls a db a live writer owns; without a busy timeout
      ;; every contended read throws instead of waiting (slopp.db/open! sets 5s)
      (jdbc/execute! conn ["PRAGMA busy_timeout=5000"])
      conn)))

^:reads (defn store-sources
  "{ns-sym source} for every namespace in the store db — byte-exact (each
  `elements.source` is a form's canonical CST string; concatenated by pos it IS
  render-ns output). A schema-less db (brand-new dir) is an EMPTY store — the
  served program's own open creates the schema."
  [conn]
  (if (empty? (jdbc/execute! conn ["SELECT name FROM sqlite_master
                                    WHERE type='table' AND name='elements'"]))
    {}
    (reduce (fn [m row]
              (update m (symbol (:elements/ns row)) (fnil str "") (:elements/source row)))
            {}
            (jdbc/execute! conn ["SELECT ns, source FROM elements ORDER BY ns, pos"]))))

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

^:unsafe (defn load-store!
  "Load every namespace of the store at `dir` into the CURRENT JVM, dependency
  order: load-string each rendered source + a *loaded-libs* stamp. Returns the
  {ns source} map that was loaded. A load failure is rethrown NAMING the
  namespace — a bare load-string error carries NO_SOURCE_PATH and no ns, which
  is useless on the one code path with no oracle behind it."
  [dir]
  (with-open [conn (open-conn dir)]
    (let [sources (store-sources conn)]
      (doseq [ns-sym (dependency-order sources)]
        (try (load-string (get sources ns-sym))
             (catch Throwable t
               (throw (ex-info (str "slopp.boot: failed to load namespace "
                                    ns-sym " from the store at " dir
                                    ": " (.getMessage t))
                               {:ns ns-sym :dir dir} t))))
        (stamp-loaded! ns-sym))
      sources)))

;; --- live mode: track the store, reload changed nses into this jvm ---

^:reads (defn- data-version [conn]
          (:data_version (jdbc/execute-one! conn ["PRAGMA data_version"])))

^:unsafe (defn watch-live!
  "Poll the store's data_version; when another writer commits, reload the
  namespaces whose source changed into THIS jvm (dependency order). The store's
  green-gate means only compilable code ever loads. Caveat: long-lived instances
  (servers, background threads) keep their old closure code until re-created.

  Resilient by construction: the ENTIRE poll body is guarded, so a transient
  store error (contention, a swapped db file) logs and RETRIES instead of
  killing the daemon and serving stale code forever. The version baseline
  advances only when every changed namespace reloaded — a failed one keeps its
  OLD source in the baseline AND holds the version back, so the next poll
  retries it rather than treating it as already seen.

  ^:unsafe: the store loader IS load-string (it evaluates rendered store
  source), which the dialect denylist bans for ordinary code — here it is the
  whole point."
  [dir & {:keys [interval-ms] :or {interval-ms 500}}]
  (let [conn (open-conn dir)]
    (loop [dv (data-version conn), prev (store-sources conn)]
      (let [[dv' prev']
            (try
              (Thread/sleep (long interval-ms))
              (let [dv2 (data-version conn)]
                (if (= dv dv2)
                  [dv prev]
                  (let [now     (store-sources conn)
                        changed (filter #(not= (get prev %) (get now %))
                                        (dependency-order now))
                        failed  (reduce (fn [failed ns-sym]
                                          (try (load-string (get now ns-sym))
                                               (stamp-loaded! ns-sym)
                                               failed
                                               (catch Throwable t
                                                 (log! "live-reload failed for " ns-sym
                                                       ": " (.getMessage t))
                                                 (conj failed ns-sym))))
                                        #{} changed)
                        loaded  (remove failed changed)]
                    (when (seq loaded)
                      (log! "live-reloaded: " (str/join " " loaded)))
                    ;; a failed ns keeps its OLD source so it still looks changed,
                    ;; and holding dv back keeps the version-change branch firing
                    [(if (seq failed) dv dv2)
                     (reduce #(assoc %1 %2 (get prev %2)) now failed)])))
              (catch Throwable t
                (log! "live-reload poll error (continuing): " (.getMessage t))
                [dv prev]))]
        (recur dv' prev')))))

;; --- entry ---

(defn parse-args
  "Parse boot's CLI: <dir> [--snapshot|--live] [--main ns/fn arg...]
                           [--call tool [args]].
  Everything after the --main symbol passes through to it verbatim (:args);
  with no explicit args the main receives [dir] (the server convention).
  --call is sugar for --main slopp.mcp/call-main! <dir> <tool> [args] — one
  tool call, result on stdout (args = JSON, EDN, or @file)."
  [args]
  (let [[pre post] (split-with #(not (#{"--main" "--call"} %)) args)
        dir  (or (first (remove #(str/starts-with? % "--") pre))
                 (System/getProperty "user.dir"))
        extra (vec (drop 2 post))]
    (if (= "--call" (first post))
      {:dir   dir
       :live? (boolean (some #{"--live"} pre))
       :main  'slopp.mcp/call-main!
       :args  (into [dir] (rest post))}
      {:dir   dir
       :live? (boolean (some #{"--live"} pre))
       :main  (symbol (or (second post) "slopp.mcp/-main"))
       :args  (if (seq extra) extra [dir])})))

^:unsafe (defn -main
  "clojure -M -m slopp.boot <dir> [--snapshot | --live] [--main ns/fn arg...]

  Load the store's program into THIS jvm and run its entry point (default
  slopp.mcp/-main <dir>). --live tracks the store and hot-reloads changed
  namespaces (the watcher is a DAEMON thread — it never keeps the JVM alive
  after the program exits). --main trampolines any store CLI — in a fileless
  tree this is THE entry point: e.g.
    clojure -M -m slopp.boot . --main slopp.sync/-main push . <url>"
  [& args]
  (let [{:keys [dir live? main args]} (parse-args args)]
    (log! "slopp.boot: loading store at " dir " (" (if live? "live" "snapshot") ")")
    (let [sources (load-store! dir)]
      ;; a typo'd dir CREATES an empty .slopp/store.db and loads zero
      ;; namespaces; without this the real error surfaced downstream as
      ;; requiring-resolve's "Could not locate …__init.class" — say it here
      (when (empty? sources)
        (log! "slopp.boot: WARNING — the store at " dir " is EMPTY (no"
              " namespaces loaded). Wrong directory? Expected a populated"
              " " dir "/.slopp/store.db.")))
    (when live?
      (doto (Thread. ^Runnable (fn [] (watch-live! dir)))
        (.setDaemon true)
        (.start)))
    (apply (requiring-resolve main) args)))