(ns slopp.boot
  "Run a slopp store's program directly from the db — no exported source.
  Renders every namespace's source from `<dir>/.slopp/store.db` (the
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
^:reads (defn- open-conn
  "The store db under `dir`, or NIL when `dir` has no store yet.

  A read must never CREATE: the kernel boots in whatever directory the MCP
  client launched the server in, so an unadopted project has to stay
  untouched (D-serving-is-not-adoption). This runs before
  `slopp.mcp/-main`, which is why gating the server layer alone was not
  enough — boot got there first and made the store the server then found.
  Materialization belongs to the first write (`api.session/ensure-db!`)."
  [dir]
  (let [f (io/file dir ".slopp" "store.db")]
    (when (.exists f)
      (let [conn (jdbc/get-connection
                  (jdbc/get-datasource {:dbtype "sqlite" :dbname (str f)}))]
        ;; the live watcher polls a db a live writer owns; without a busy timeout
        ;; every contended read throws instead of waiting (slopp.db/open! sets 5s)
        (jdbc/execute! conn ["PRAGMA busy_timeout=5000"])
        conn))))

^:reads (defn store-sources
          "{ns-sym source} for every namespace in the store db — the store's own
  rendering, reproduced without any slopp code. Forms joined by ONE BLANK
  LINE, a form's `comment` directly above it, one trailing newline. `sep` rows
  are IGNORED, so a store mid-migration boots exactly what a migrated one
  does.

  This used to CONCATENATE every row in `pos` order, which was byte-exact for
  as long as the rows carried the whitespace themselves. Once the renderer
  started supplying it, concatenation became a second and wrong answer: forms
  jammed together and every comment dropped.

  So this is a fourth implementation of one rule, and it cannot call the other
  three — the kernel's whole property is that it loads a store with no slopp
  code available, which is why the module gate refuses it `slopp.store`.
  `SELECT *` rather than named columns for the same reason it reads no schema
  version: a store predating the `comment` column must still boot.

  A schema-less db (brand-new dir) is an EMPTY store — the served program's
  own open creates the schema."
          [conn]
          (if (empty? (jdbc/execute! conn ["SELECT name FROM sqlite_master
                                    WHERE type='table' AND name='elements'"]))
            {}
            (into {}
                  (map (fn [[ns-sym rows]]
                         [ns-sym (str (str/join
                                       "\n\n"
                                       (map (fn [r]
                                              (if-let [c (:elements/comment r)]
                                                (str c "\n" (:elements/source r))
                                                (:elements/source r)))
                                            rows))
                                      "\n")]))
                  (->> (jdbc/execute! conn ["SELECT * FROM elements ORDER BY ns, pos"])
                       (filter #(= "form" (:elements/kind %)))
                       (group-by #(symbol (:elements/ns %)))))))

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

(def default-repos
  "Where to look for artifacts when the runtime cannot say — the same two the
  Clojure CLI's root deps.edn configures, so this RESTORES the default rather
  than inventing one."
  {"central" {:url "https://repo1.maven.org/maven2/"}
   "clojars" {:url "https://repo.clojars.org/"}})

(def bundled-libs-path
  "Resource naming what the host uberjar already provides, lib→coord.

  Written by `build.clj` from the very basis that produced the jar, so it
  cannot drift from what shipped — the alternative, restating slopp's deps by
  hand, is a claim that goes stale the first time `deps.edn` changes."
  "META-INF/slopp/bundled-libs.edn")

(def framework-version-path
  "Resource naming which `slopp-web` release this jar's `slopp/web/**` IS.

  Written by `build.clj` from the tracked `META-INF/MANIFEST.MF`'s
  `X-Slopp-Web-Version`, so the number is authored in ONE place and the jar
  cannot claim a version it was not built as.

  Deliberately NOT in `bundled-libs.edn`, which feeds `host-lib-divergence` —
  that reports \"your declaration is inert, the host's copy wins\", the opposite
  of the truth for slopp-web (D-framework-injection)."
  "META-INF/slopp/framework-version.edn")

(defn framework-version
  "The `slopp-web` release THIS slopp corresponds to, or nil when the process
  cannot say — a `clojure -M` run, a checkout, the oracle image.

  A STAMP, not a maven version: slopp-web is never published, so this says which
  framework a jar carries and which one a built tree was given, and nothing
  resolves against it. `api.session/vendor-framework!` writes it beside the
  vendored files; `slopp.api` reports it as `:framework-drift` while any store
  still declares the retired coord.

  In the KERNEL because both consumers need it and nothing lower is shared:
  `slopp.image` sits below `slopp.api`, so a helper up there would be a
  backwards dependency.

  nil is a legitimate answer and every caller must stay silent on it — a
  checkout has no published identity to vendor or to be behind."
  []
  (when-let [r (io/resource framework-version-path)]
    (not-empty (str/trim (slurp r)))))

(defn framework-files
  "The framework slopp vendors into stores it serves: `{\"slopp/web.clj\" src …}`,
  or nil when this process cannot supply it (a checkout, a `clojure -M` run).

  D-framework-injection part 2. `slopp-web` is NEVER published to a remote, so a
  maven coord in a store's deps or a built app's `deps.edn` names something only
  the machine that ran `slim-install` can resolve — portable in appearance and
  not in fact. Copying the source in is what makes a built app self-contained.

  The LIST comes from a generated resource rather than a glob, because a jar
  cannot enumerate its own resources by prefix, and rather than a hand-written
  vector, because that goes stale the first time a namespace joins slopp.web.
  Missing content for a listed file is skipped rather than thrown on: a partial
  vendor is a compile error at the far end, which is louder and more localised
  than a boot failure here."
  []
  (when-let [r (io/resource "META-INF/slopp/framework-files.edn")]
    (not-empty
     (into {} (keep (fn [p]
                      (when-let [res (io/resource p)]
                        [p (slurp res)])))
           (edn/read-string (slurp r))))))

(defn bundled-libs
  "lib→coord for everything the host uberjar carries, or nil when this process
  is not running from one (a `clojure -M` run, a checkout, the oracle image)."
  []
  (when-let [r (io/resource bundled-libs-path)]
    (not-empty (edn/read-string (slurp r)))))

(defn- basis-libs-to-seed
  "PURE. What belongs in the basis's `:libs` given `current` and what the jar
  bundles — the bundled set when there is no basis to speak of, else nil.

  A FALLBACK, never an override, for the same reason `ensure-repos!` is one: a
  process the Clojure CLI started already has a real basis describing a real
  classpath, and replacing it with the jar's inventory would describe a
  classpath that process does not have."
  [current bundled]
  (when (and (empty? current) (seq bundled))
    bundled))

(defn host-lib-divergence
  "PURE. Where `manifest` and what the host jar `bundled` disagree about a
  version: lib→`{:declared coord :in-force coord}`, empty when they agree.

  Seeding the basis stops `add-libs` from CLAIMING it added a bundled lib, but
  it cannot make the declaration govern — a jar the parent classloader already
  holds cannot be displaced, so in this process the host's copy runs whatever
  the store declares. The point of naming it is that the disagreement is real
  and asymmetric: the oracle image is a separate `clojure -Sdeps` JVM that
  resolves the manifest properly, so the version the TESTS run against and the
  version the SERVER runs can differ, and every surface said neither.

  Compared on version identity rather than the whole coord, because a resolved
  coord carries `:deps/manifest`/`:parents` a declared one never has, and a
  declared one carries `:exclusions` that are not a version disagreement."
  [manifest bundled]
  (let [ident #(select-keys % [:mvn/version :git/sha :git/tag :local/root])]
    (into (sorted-map)
          (keep (fn [[lib coord]]
                  (when-let [have (get bundled lib)]
                    (when (not= (ident coord) (ident have))
                      [lib {:declared coord :in-force (ident have)}]))))
          manifest)))

^{:unsafe "reaching clojure.java.basis.impl needs requiring-resolve, which the dialect denylists. The kernel is the one place that may: it is what knows this process is a jar and what that jar contains."}
(defn ensure-bundled-libs!
  "Tell the dependency resolver what this process ALREADY HAS, returning
  `{:libs n :action :seeded|:kept}`.

  `add-libs` drops any coord whose lib is already in the basis's `:libs` — by
  SYMBOL, ignoring version — and passes the rest to resolution as `:existing`.
  A `java -jar` process has no basis, so that set is empty and both halves
  misfire: a lib the uberjar bundles is 'added' and then loses to the parent
  classloader, and every add drags in a transitive graph resolved as though
  the JVM were bare — MEASURED: adding one small library re-added
  `org.clojure/clojure` itself, plus ten others already present.

  Seeding what the jar bundles fixes both at the source. A bundled lib is
  skipped outright rather than falsely added, and what genuinely is new
  resolves against a true picture of the classpath."
  []
  (let [bundled (bundled-libs)
        current (:libs ((requiring-resolve 'clojure.java.basis/current-basis)))]
    (if-let [seed (basis-libs-to-seed current bundled)]
      (do ((requiring-resolve 'clojure.java.basis.impl/update-basis!)
           update :libs merge seed)
          {:libs (count seed) :action :seeded})
      {:libs (count current) :action :kept})))

^{:unsafe "reaching clojure.java.basis.impl needs requiring-resolve, which the dialect denylists. The kernel is the one place that may: it is what has to make `java -jar slopp.jar <dir>` resolve a manifest at all."}
(defn ensure-repos!
  "Make sure the dependency resolver has somewhere to LOOK, returning
  `{:repos … :action :seeded|:kept}`.

  `add-libs` builds its Maven procurer from the current BASIS's namespaced
  keys, and a `java -jar` process has no basis — so `:mvn/repos` is empty.
  Maven then neither downloads an artifact nor TRUSTS one `~/.m2` already
  holds: a cached POM records the repository it came from
  (`jackson-base-2.17.0.pom>central=`), and one it cannot attribute to a
  configured repo is reported as `Could not find artifact`. That is why this
  looked like a cold cache for so long, and why `clojure -Sdeps … -Spath`
  always resolved the same coord from the same `~/.m2`: the CLI supplies a
  basis, and nothing here did.

  A FALLBACK, never an override. A process started by the CLI, or one pointed
  at a private mirror, has already been told where to look, and replacing that
  would break exactly the case this default is guessing at."
  []
  (let [current ((requiring-resolve 'clojure.java.basis/current-basis))]
    (if (seq (:mvn/repos current))
      {:repos (:mvn/repos current) :action :kept}
      (do ((requiring-resolve 'clojure.java.basis.impl/update-basis!)
           merge {:mvn/repos default-repos})
          {:repos default-repos :action :seeded}))))

^{:unsafe "add-libs IS the dynamic-classpath escape hatch, and making a thread capable of it means installing a classloader and binding the vars the dialect denylists. The kernel is the one place that can do this."}
(defn- add-libs-here!
  "Run Clojure 1.12 `add-libs` for `deps` on THIS thread, whatever thread it
  is — the whole reason this is its own function.

  Two things must be true of the thread, and outside a REPL neither is:

  - a `DynamicClassLoader` as the context loader, or the resolved jars have
    nowhere to land (the launcher's loader is static);
  - a THREAD binding for `*data-readers*`, because add-libs refreshes the
    reader table with `set!` and `set!` on an unbound-in-this-thread var
    throws \"Can't change/establish root binding of *data-readers* with set\".

  `clojure.main` establishes the second (its `with-bindings` covers
  `*data-readers*`); an AOT `java -jar` main does not. MEASURED: without it
  every one of slopp's own 13 manifest coords failed with exactly that
  message and nothing landed on the classpath — while the store booted fine
  off the host uberjar, so the manifest read as satisfied and was not."
  [deps]
  (let [t (Thread/currentThread)]
    (when-not (instance? clojure.lang.DynamicClassLoader
                         (.getContextClassLoader t))
      (.setContextClassLoader
       t (clojure.lang.DynamicClassLoader. (.getContextClassLoader t)))))
  (ensure-repos!)
  (ensure-bundled-libs!)
  (binding [*repl* true, *data-readers* *data-readers*]
    ((requiring-resolve 'clojure.repl.deps/add-libs) deps)))

^{:unsafe "add-libs IS the dynamic-classpath escape hatch: it needs *repl* bound and a DynamicClassLoader installed under it, which is exactly what the dialect denylists. The kernel is the one place that can do this, because it is what makes `java -jar slopp.jar <dir>` work for a store with dependencies."}
(defn- add-manifest-libs!
  "Resolve the store's Tier-1 dependency manifest (the `deps` meta row) onto
  THIS JVM's classpath via Clojure 1.12 add-libs (`*repl*` bound — the
  programmatic context), so a store whose code requires external libs boots
  from the bare kernel: `java -jar slopp.jar <dir>` works for ANY app.
  Idempotent for coords already present.

  ONE failing coord must not take the others down with it. `add-libs` resolves
  the whole map as a single graph, so one unresolvable transitive pom — a
  parent BOM that a local `~/.m2` holds as a `.pom` but Maven declines to use
  offline, say — threw, and the catch dropped EVERY declared dependency.

  What that looks like from inside is worse than a missing jar, because
  nothing appears to be missing. The libs still resolve, from whatever the
  HOST jar happens to carry, at whatever version it carries: a store that had
  `deps_add`ed malli 0.16.4 was running 0.17.0, and its manifest was
  decoration. An app checking that it depends only on what it DECLARES — the
  whole question a store-free consumer of the slim jar exists to answer —
  would have been told yes.

  So: the whole map first, because one resolution is both faster and more
  correct (a single graph, consistent versions), and coord-by-coord only on
  failure — degrading to a partial classpath that NAMES what is missing
  rather than a silent empty one."
  [conn]
  (when-let [deps (some-> (jdbc/execute-one!
                           conn ["SELECT v FROM meta WHERE k = 'deps'"])
                          :meta/v edn/read-string not-empty)]
    (try
      (add-libs-here! deps)
      (catch Throwable t
        (log! "slopp.boot: manifest deps did not resolve as one graph ("
              (.getMessage t) ") — retrying one at a time")
        (let [failed (reduce
                      (fn [acc [lib coord]]
                        (try
                          (add-libs-here! {lib coord})
                          acc
                          (catch Throwable t2
                            (conj acc (str lib " (" (.getMessage t2) ")")))))
                      []
                      deps)]
          (if (seq failed)
            (log! "slopp.boot: could not add " (count failed) " of "
                  (count deps) " manifest deps: " (str/join "; " failed)
                  " — continuing; a require that needs one will say so")
            (log! "slopp.boot: manifest deps resolved individually ("
                  (count deps) ")")))))))

^:reads (defn store-platforms
          "The store's `module-platforms` register — {path-string
  platform-keyword} — read RAW from the meta row (the kernel cannot use
  slopp.store). {} when the row, the table, or the whole schema is absent, so a
  brand-new or pre-client-wave store behaves exactly as before."
          [conn]
          (try
            (or (some-> (jdbc/execute-one!
                         conn ["SELECT v FROM meta WHERE k = 'module-platforms'"])
                        :meta/v edn/read-string)
                {})
            (catch Throwable _ {})))

(defn jvm-loadable?
  "Whether `ns-sym` may load into a JVM, given the store's `platforms` register
  ({path-string platform-keyword}): everything EXCEPT a :cljs namespace, which
  compiles to JavaScript and is never loaded here (D-web-cljs). The MOST
  SPECIFIC declared path wins, mirroring slopp.store/platform-for — the kernel
  reimplements it because it cannot require slopp.store. An empty register loads
  everything, exactly as before the client wave."
  [platforms ns-sym]
  (let [n    (str ns-sym)
        best (->> (keys platforms)
                  (filter (fn [k] (or (= n k) (str/starts-with? n (str k ".")))))
                  (sort-by count)
                  last)]
    (not= :cljs (get platforms best))))

(defonce ^{:doc "What THIS process has loaded, and how it compares to the store.

  `:nses` is {ns-sym source-hash}, written by every door that loads store code
  into this JVM — `load-store!` at boot and `watch-live!`'s reload. `:stale` is
  the last measured answer, recomputed whenever the store's sources are in
  hand; `:armed?` separates \"not measured yet\" from \"measured, nothing stale\",
  because nil and [] are different claims and only one of them is a promise.

  Why a measurement and not the watcher's `:failed` map: those disagree, and
  friction 20a is the case where the map was wrong. A rename left the watcher
  retrying a namespace that no longer EXISTS — failing forever, reporting the
  host stale, costing a milestone a fresh JVM — while the process held every
  live namespace at current source. A comparison against the store's current
  sources answers that correctly and for free: a deleted namespace is simply
  not in `now`, so it cannot be stale."}
  host-loaded
  (atom {:armed? false :nses {} :stale nil}))

(defn host-stale-of
  "Namespaces THIS process does not hold at the store's current source.

  `loaded` is {ns-sym source-hash}, recorded at each successful load;
  `now` is the current {ns-sym source} from `store-sources`, already filtered
  to what a JVM may load (a :cljs namespace is never loaded here by design, so
  counting it would be a permanent false positive).

  Both sides are KERNEL-rendered, and that is load-bearing rather than
  incidental: `store-sources` and `slopp.store.render/render-ns` are
  independent renderings — the kernel has to render with no slopp code loaded
  — so a comparison across them would report every namespace stale the first
  time they differed by a space. Compare like with like or do not compare.

  A namespace the store has since DELETED is absent from `now` and so is not
  reported: this measure answers \"is this process behind the store\", and a
  namespace the store dropped is a different question."
  [loaded now]
  (vec (sort (for [[ns-sym src] now
                   :when (not= (get loaded ns-sym) (hash src))]
               ns-sym))))

(defn- record-loaded!
  "Note that this process now holds `ns-sym` at `src`."
  [ns-sym src]
  (swap! host-loaded assoc-in [:nses ns-sym] (hash src)))

(defn- measure-host!
  "Recompute host staleness against `now` ({ns-sym source}, JVM-loadable only)
  and ARM the record — after this, nil no longer means \"nobody looked\"."
  [now]
  (swap! host-loaded
         (fn [s] (assoc s :armed? true :stale (host-stale-of (:nses s) now)))))

(defn host-drift
  "Namespaces THIS process does not hold at the store's current source, or NIL
  when that has never been measured.

  Never [] on a guess: an empty vector is the positive claim that this host is
  current, and only a comparison earns it. The distinction is the entire point
  — `host-brief` says \"not measured\" for nil and can finally stop hedging for
  []."
  []
  (let [s @host-loaded]
    (when (:armed? s) (:stale s))))

^:unsafe (defn load-store!
  "Load every JVM-LOADABLE namespace of the store at `dir` into the CURRENT JVM,
  dependency order: load-string each rendered source + a *loaded-libs* stamp.
  A :cljs namespace is SKIPPED — it compiles to JavaScript and its libs are not
  on the boot classpath, so loading it made any store carrying client code
  unbootable (D-web-cljs). The store's
  dependency MANIFEST resolves onto the classpath first (add-manifest-libs!),
  so store code may require its Tier-1 libs. Returns the
  {ns source} map that was loaded, carrying `:load-failures` in its METADATA
  when some namespace did not load.

  **Best-effort, deliberately (frictions 3b/3f/19).** This used to rethrow on
  the first failure, and the blast radius was the whole system: `slopp.boot`
  loads every store namespace, so ONE namespace that no longer compiles took
  down every tool in every process — including the `edit_add_form` that would
  have put the missing form back. Three times in one wave a store reached a
  state its own tools could not open, and the only way back was `rm -rf
  .slopp` and a re-import. That is a catastrophic answer to an ordinary
  mistake: a delete whose form still had a caller.

  So a failure now costs its OWN namespace and whatever genuinely depends on
  it, not the session. The editing surface comes up, the failures are NAMED,
  and the agent can fix the thing that broke. A broken namespace you can edit
  is strictly better than a working store you cannot reach.

  The names matter as much as the survival: a bare load-string error carries
  NO_SOURCE_PATH and no ns, which is useless on the one code path with no
  oracle behind it. Each failure records the namespace and the message, and
  dependents that fail because of it are recorded the same way — so the list
  reads as one cause and its consequences rather than as many faults.

  A dir with NO store loads nothing and returns {} — same shape as a store
  that exists and is empty. Serving an unadopted dir is legal and leaves it
  untouched; the caller decides whether an empty program is worth a warning."
  [dir]
  (if-let [c (open-conn dir)]
    (with-open [conn c]
      (add-manifest-libs! conn)
      (let [sources   (store-sources conn)
            platforms (store-platforms conn)
            failed    (volatile! [])]
        (doseq [ns-sym (dependency-order sources)
                :when  (jvm-loadable? platforms ns-sym)]
          (try
            (load-string (get sources ns-sym))
            (stamp-loaded! ns-sym)
            (record-loaded! ns-sym (get sources ns-sym))
            (catch Throwable t
              (vswap! failed conj {:ns ns-sym :why (str (.getMessage t))}))))
        (measure-host! (into {} (filter #(jvm-loadable? platforms (key %))) sources))
        (when (seq @failed)
          (log! "slopp.boot:" (count @failed)
                "namespace(s) did NOT load —" (str/join ", " (map :ns @failed))
                "— the store is open and editable anyway; fix them and restart."
                "First:" (:why (first @failed))))
        (with-meta sources {:load-failures @failed})))
    {}))

;; --- live mode: track the store, reload changed nses into this jvm ---
^:reads (defn- data-version [conn]
          (:data_version (jdbc/execute-one! conn ["PRAGMA data_version"])))

(defonce boot-info
  ;; the host's own currency record — session_brief reads it (through the
  ;; late-ref carrier; absent in processes that didn't boot from a store) to
  ;; answer "which code is this server actually running": :snapshot mode =
  ;; the store AT LAUNCH, :live mode = launch + successful reloads of
  ;; MAIN-journal commits. A branch line's writes live in its own
  ;; mini-journal and are DELIBERATELY invisible to the watcher — host code
  ;; tracks the main line; branch serving behavior is verified in the image
  ;; or a fresh JVM. Keys: :dir :mode :booted-at, then :last-reload-at
  ;; :reloads :failed maintained by watch-live!.
  (atom nil))

(defn current-boot-info
  "The boot-info record, or nil — the fn face session_brief reaches through
  a late-ref carrier (an atom cannot be a carrier target).

  Carries the MEASURED host currency (`host-drift`) beside the recorded boot
  facts, so every reader gets the comparison without having to ask for it. Two
  different KINDS of thing travel in this map on purpose, and the difference is
  the point: `:failed` is what once happened, `:host-drift` is what is true
  now. A reader holding only the first was the whole of friction 20a — a
  watcher retrying a renamed-away namespace, failing forever, reporting stale
  code in a process that held every live namespace at current source.

  `:host-drift` is ABSENT when nothing has measured, `[]` when a comparison
  found this process current, and a list when it is behind. Three claims, not
  two, because \"I did not look\" must not read as \"I looked and it was fine\"."
  []
  (when-let [info @boot-info]
    (let [stale (host-drift)]
      (cond-> info
        (some? stale) (assoc :host-drift stale)))))

^:unsafe (defn- departed-vars
  "The names in `interned` that `new-source` no longer defines — what a live
  reload leaves behind.

  `load-string` re-defines every form the new source contains and says nothing
  about the ones it does not, so a DELETE reaches a `--live` host as \"still
  there\". The store is correct, the suite is green, and the running server
  keeps answering from a var whose definition is gone. Worse for routes than
  for anything else: a stale route SHADOWS a catch-all, so deleting a page
  makes the SPA that replaced it look broken.

  Two directions of error, and they are not symmetric. Missing a departed name
  leaves a stale var — the old behaviour. Reporting a name the source DOES
  define unmaps live code. So every def shape counts, and source that will not
  read departs NOTHING."
  [interned new-source]
  (let [defined (try
                  (->> (read-string {:read-cond :allow}
                                    (str "[" new-source "]"))
                       (keep (fn [f]
                               ;; a leading metadata wrapper needs no unwrapping
                               ;; here: the READER attaches ^:unsafe to the list
                               ;; itself, so (defn f …) is what arrives either
                               ;; way. The store's accessors see a CST, which is
                               ;; why they must unwrap and this must not.
                               (when (and (seq? f) (symbol? (second f)))
                                 (second f))))
                       set)
                  (catch Throwable _ ::unreadable))]
    (if (= ::unreadable defined)
      #{}
      (into #{} (remove defined) interned))))

^:unsafe (defn- reload-ns!
  "Load `new-source` into `ns-sym` and drop the vars it no longer defines.

  The unmapping runs only AFTER a successful load, and the ordering is the
  safety property: a reload that throws leaves the namespace exactly as it
  was. Gutting first would turn a compile error into a dead namespace, which
  is strictly worse than the stale var this exists to remove."
  [ns-sym new-source]
  (let [before (when-let [n (find-ns ns-sym)]
                 (set (keys (ns-interns n))))]
    (load-string new-source)
    (when-let [n (find-ns ns-sym)]
      (doseq [s (departed-vars before new-source)]
        (ns-unmap n s)))))

^:unsafe (defn watch-live!
  "Poll the store's data_version; when another writer commits, reload the
  namespaces whose source changed into THIS jvm (dependency order). The store's
  green-gate means only compilable code ever loads. Caveat: long-lived instances
  (servers, background threads) keep their old closure code until re-created.

  **A reload also DROPS what the new source stopped defining.** `load-string`
  re-defines the forms it is given and is silent about the rest, so without
  this a deleted form keeps answering in the running host — the store correct,
  the suite green, and the server serving a definition that no longer exists.
  See `departed-vars` for why the error directions are not symmetric.

  Resilient by construction: the ENTIRE poll body is guarded, so a transient
  store error (contention, a swapped db file) logs and RETRIES instead of
  killing the daemon and serving stale code forever. The version baseline
  advances only when every changed namespace reloaded — a failed one keeps its
  OLD source in the baseline AND holds the version back, so the next poll
  retries it rather than treating it as already seen.

  **A failure that keeps failing says so, and says why.** The retry note used
  to read the same hopeful sentence forever while a reload had been stuck for
  many minutes, and the REASON existed only in the server log — a file no
  slopp surface exposes, on a system whose whole claim is that the store
  answers everything. `boot-info` now carries the message and the consecutive
  attempt count, so a verdict marked suspect can say what to do about it.

  ^:unsafe: the store loader IS load-string (it evaluates rendered store
  source), which the dialect denylist bans for ordinary code — here it is the
  whole point."
  [dir & {:keys [interval-ms] :or {interval-ms 500}}]
  (let [conn (loop []
             ;; an unadopted dir has no store until its first write
             ;; materializes one — WAIT for it rather than dying at boot
             (or (open-conn dir)
                 (do (Thread/sleep (long interval-ms)) (recur))))]
    (loop [dv (data-version conn), prev (store-sources conn)]
      (let [[dv' prev']
            (try
              (Thread/sleep (long interval-ms))
              (let [dv2 (data-version conn)]
                (if (= dv dv2)
                  [dv prev]
                  (let [now       (store-sources conn)
                        platforms (store-platforms conn)
                        changed (filter #(and (jvm-loadable? platforms %)
                                              (not= (get prev %) (get now %)))
                                        (dependency-order now))
                        failed  (reduce (fn [failed ns-sym]
                                          (try (reload-ns! ns-sym (get now ns-sym))
                                               (stamp-loaded! ns-sym)
                                               (record-loaded! ns-sym (get now ns-sym))
                                               failed
                                               (catch Throwable t
                                                 (log! "live-reload failed for " ns-sym
                                                       ": " (.getMessage t))
                                                 (assoc failed ns-sym (str (.getMessage t))))))
                                        {} changed)
                        loaded  (remove failed changed)]
                    (when (seq loaded)
                      (log! "live-reloaded: " (str/join " " loaded)))
                    (measure-host!
                     (into {} (filter #(jvm-loadable? platforms (key %))) now))
                    ;; keep the currency record honest: a failed ns stays
                    ;; listed until a later poll reloads it (it also holds
                    ;; the version baseline back, below)
                    (swap! boot-info
                           #(when %
                              (-> %
                                  (assoc :last-reload-at (System/currentTimeMillis)
                                         :failed (vec (sort (keys failed)))
                                         ;; the REASON, and how long it has been
                                         ;; true — "the next poll retries" is not
                                         ;; news the twentieth time
                                         :failed-why
                                         (not-empty
                                          (into {}
                                                (for [[ns-sym why] failed]
                                                  [ns-sym
                                                   {:why why
                                                    :attempts (inc (get-in % [:failed-why ns-sym :attempts] 0))}]))))
                                  (update :reloads (fnil + 0) (count loaded)))))
                    ;; a failed ns keeps its OLD source so it still looks changed,
                    ;; and holding dv back keeps the version-change branch firing
                    [(if (seq failed) dv dv2)
                     (reduce #(assoc %1 %2 (get prev %2)) now (keys failed))])))
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
    (reset! boot-info {:dir dir
                       :mode (if live? :live :snapshot)
                       :booted-at (System/currentTimeMillis)})
    (log! "slopp.boot: loading store at " dir " (" (if live? "live" "snapshot") ")")
    (let [sources (load-store! dir)]
      ;; a typo'd dir CREATES an empty .slopp/store.db and loads zero
      ;; namespaces; without this the real error surfaced downstream as
      ;; requiring-resolve's "Could not locate …__init.class" — say it here
      (when (empty? sources)
        ;; two different situations, and only one is a mistake: an EMPTY
        ;; store means someone pointed at the wrong dir, while NO store is
        ;; the ordinary case for a dir that never adopted slopp — the
        ;; server is expected to serve those and leave them alone
        (if (.exists (io/file dir ".slopp" "store.db"))
          (log! "slopp.boot: the store at " dir " has no namespaces yet —"
                " a freshly adopted store, or the wrong directory (a"
                " populated one lives at " dir "/.slopp/store.db).")
          (log! "slopp.boot: no slopp store at " dir " — serving an"
                " unadopted directory and leaving it untouched. Your first"
                " write creates " dir "/.slopp/store.db.")))
      ;; a namespace that did not load is now SURVIVABLE (load-store! is
      ;; best-effort), which makes saying so the whole job: an agent whose
      ;; store came up half-loaded must learn it from orientation rather than
      ;; from the first confusing failure downstream.
      (when-let [f (seq (:load-failures (meta sources)))]
        (swap! boot-info assoc :load-failures (vec f))))
    (when live?
      (doto (Thread. ^Runnable (fn [] (watch-live! dir)))
        (.setDaemon true)
        (.start)))
    (apply (requiring-resolve main) args)))
