(ns slopp.ops.engine
  "The write ENGINE — image lifecycle, the rebasing commit, and verification.

  Every operation in `slopp.api` is a pure transform handed to `rebased-write!`
  or its group sibling, which runs it inside a `swap!` so concurrent writes to
  DIFFERENT forms rebase and land without locks, gates the result once before
  committing, hot-loads it, and verifies exactly the tests the change reaches.
  The operations above supply intent; the sequencing lives here.

  The practical consequence, and it decides where fixes belong: a rule
  implemented HERE lands for every write, and one implemented in an operation
  lands for that operation. Four gates were once hand-pasted at four write
  sites because the chokepoint was not used, and every later fix to them had
  to be applied four times."
  (:require [clojure.edn :as edn] [clojure.set :as set] [clojure.string :as str] [rewrite-clj.node :as n] [slopp.store.db :as db] [slopp.edit :as edit] [slopp.image :as image] [slopp.store.render :as store.render] [slopp.image.repl :as repl] [slopp.store :as store] [slopp.index.analyze :as analyze] [slopp.edit.hotload :as hotload] [slopp.edit.lintgate :as lintgate] [rewrite-clj.parser :as p] [slopp.rules.web :as rules.web] [slopp.index.refs :as refs] [slopp.image.currency :as image.currency] [slopp.kernel.boot :as boot] [clojure.java.io :as io] [slopp.edit.web :as edit.web]))

(def ^{:export "slopp.concurrency"} ^:dynamic *pre-commit-hook*
  "Test seam (item 4): invoked between an op's hot-load and its commit CAS to
  simulate a concurrent competitor deterministically. Never set in production.
  Exported to the contention specs that bind it; package-private otherwise."
  nil)

(defn ^:export framework-injection
  "The framework FILES slopp vendors into `store` — `{\"slopp/web.clj\" src …}` —
  or nil when it should supply nothing.

  D-framework-injection. The framework is slopp's own, so slopp provides it,
  exactly as `external/client-build-deps` provides the ClojureScript compiler
  and `repl/inherent-deps` provides nREPL and malli. A store that declared it
  instead could be pinned to a release the slopp serving it is not — not
  hypothetical: `slopp-ui` sat on 0.1.3 for a day while the fix made FOR it
  shipped in the host at 0.1.4, because the declaration is what loads.

  **Files, not a coord (part 2).** `slopp-web` is never published to a remote,
  so a coord names something only the machine that ran `slim-install` can
  resolve: portable in appearance, not in fact.

  Conditions, each load-bearing in a different direction.

  **USES but does not DEFINE.** slopp's own store CONTAINS `slopp.web.*`, and
  `src` is the FIRST classpath entry — so vendoring there would shadow the code
  being edited with the last-shipped copy, and slopp would test its release
  instead of its working tree.

  **USES, not merely exists.** A store with no web code needs nothing, and
  writing files into every image would cost every fixture boot for nothing.

  **USING is not only REQUIRING (2026-08-05).** A `^:web/page` app is opened by
  `slopp.web.screen`, which slopp calls on the app's BEHALF — so the image
  needs the framework even when the app's own code names none of it. That is
  the ordinary shape for a client-state app: `{:state … :view …}` is hiccup and
  a couple of handlers, requiring nothing.

  Worth the telling, because the failure was a good impostor: it read
  \"Could not locate slopp/web/screen.clj\", which is what a stale jar says, and
  it survived both a rebuild AND a process restart. What ruled the jar out in
  ONE call was `session_brief`'s `:jar {:head}` — added that same morning for
  an unrelated reason, and the difference between a wrong diagnosis held for
  two minutes and one held for a day.

  Empty or nil `files` (a checkout, a `clojure -M` run) vendors nothing rather
  than half a framework."
  [store files]
  (let [nses (keys (:namespaces store))
        ;; dot boundary, or equality: "slopp.web" alone matched a user's
        ;; slopp.website (suppressing injection — that image cannot load
        ;; slopp.web.screen) and slopp.webhooks (injecting spuriously). The
        ;; prefix-and-its-length class, phase 4's sub-core, measured here by
        ;; the review.
        web? (fn [n] (let [s (str n)]
                       (or (= s "slopp.web")
                           (str/starts-with? s "slopp.web."))))]
    (when (and (seq files)
               (not-any? web? nses)
               (or (some (fn [n] (some web? (store/ns-require-libs store n))) nses)
                   (some (fn [n] (some #(:web/page (edit.web/web-name-meta %))
                                       (store/forms store n)))
                         nses)))
      files)))

(defn ^:export vendor-framework!
  "Write the framework `store` needs into `dir`/src. Returns the paths written,
  or nil when the store needs none.

  An image runs with its own dir as cwd and `src` as the FIRST (relative)
  classpath entry. So vendoring is a file write: no `-Sdeps` entry, no
  `:local/root`, no repository. `external/build!` writes into the materialized
  tree the same way, which is why this takes a dir rather than an image.

  **It must happen BEFORE the process starts.** A JVM caches a relative
  classpath directory that did not exist at launch, so writing into a RUNNING
  image's dir does nothing — measured while testing this. Every caller creates
  and fills the dir first, then launches.

  The VERSION STAMP travels with the files, which handles provenance.

  **The files are only half of what a coord carried.** The other half is the
  DEPS — vendored source still has requires, and the pom was what pulled garden,
  hiccup, cheshire and http-kit in. That is [[image-deps]]'s job, and it has to
  be done by every consumer of this fn or the framework lands intact and fails
  inside itself. An earlier docstring here claimed provenance was the only
  property needing replacement; it was wrong, and a store went unloadable
  proving it.

  Deliberately NOT the uberjar on the image classpath, which would be simpler
  and would destroy the property this rests on: a store's image receives the
  FRAMEWORK and nothing else, so reaching for `slopp.api` from an app fails to
  compile there."
  [store dir]
  (when-let [files (framework-injection store (boot/framework-files))]
    (let [written (vec (sort (for [[path src] files]
                               (let [f (io/file dir "src" path)]
                                 (io/make-parents f)
                                 (spit f src)
                                 path))))]
      (when-let [v (boot/framework-version)]
        (let [f (io/file dir "src" boot/framework-version-path)]
          (io/make-parents f)
          (spit f v)))
      written)))

(defn ^{:breaking-ok "never legitimately module-external: ^:export was copied from the forms this replaced, and every caller is inside slopp.api.*. Created and un-exported inside one unreleased wave, so there is no downstream to tell."}
  image-deps
  "The dep map an image for `store` should carry: the store's own manifest plus
  what the vendored framework requires.

  Vendoring hands over SOURCE, and source has requires. `slopp.web.css` needs
  garden, `slopp.web.html` needs hiccup, the servers need cheshire and http-kit
  — all of which used to arrive transitively through the coord's pom, and all of
  which vanished with it. The files landed and then failed inside themselves.

  Merged UNDER the store's manifest, not over it: an app pinning its own hiccup
  keeps it. slopp supplies what the framework needs, never what the app chose."
  [store]
  (if (framework-injection store (boot/framework-files))
    (merge (boot/framework-deps) (:deps store))
    (:deps store)))

(defn ^{:breaking-ok "never legitimately module-external: ^:export was copied from the forms this replaced, and every caller is inside slopp.api.*. Created and un-exported inside one unreleased wave, so there is no downstream to tell."}
  framework-dir!
  "The dir to launch an image for `store` in — one per session, created and
  filled on demand — or nil when that store needs no framework.

  The DECISION is per-store (branch lines each have their own); the DIR is per
  session, because the framework is slopp's own and every image it launches
  needs the same bytes. Cached under `:framework-dir`; the vendor call is
  idempotent file writes, so a store that GAINS web code mid-session gets it on
  its next image rather than never."
  [session store]
  (when (framework-injection store (boot/framework-files))
    (let [dir (or (:framework-dir @session)
                  (let [d (str (java.nio.file.Files/createTempDirectory
                                "slopp-framework"
                                (make-array java.nio.file.attribute.FileAttribute 0)))]
                    (swap! session assoc :framework-dir d)
                    d))]
      (vendor-framework! store dir)
      dir)))

(defn start-spare!
  "Kick off a background-warming spare image (D5 warm spare) if enabled.

  Launched with no DEPS — the manifest can change between warming and adoption,
  so `image-with-deps!` reconciles it there — but IN the session's framework
  dir, because that half cannot be reconciled later: a JVM cannot pick up a
  relative classpath directory after launch. The dir is resolved here, on the
  calling thread, rather than inside the future: it writes to the session."
  [session]
  (when (:warm-spare? @session)
    (let [dir (framework-dir! session (:store @session))]
      (swap! session assoc :spare
             (future (repl/start! (cond-> {}
                                    dir (assoc :slopp.image.repl/dir dir))))))))

(defn ^:export start-image!
  "THE door: every owned image is launched here, for `store`.

  It exists because there was no such door, and that cost three rounds. Four
  paths launch images — session open (`external/boot-image!`), `fresh-image!`
  (restart, deps changes, ns_rename, the D5 staleness heal), `branch/boot-line-image!`
  for a line, and the warm spare — and each was a sibling of `repl/start!`
  rather than a caller of one preparation. So \"every image gets X\" was a
  convention, re-implemented per path. Framework vendoring was added to one of
  the four; the branch-line path had it missing for a week and nobody noticed,
  because nothing exercises branches and web code together.

  This namespace's own docstring already names that class for WRITES —
  four gates hand-pasted at four write sites because the chokepoint was not
  used, and every later fix applied four times. `rebased-write!` is that
  chokepoint. This is its counterpart for images, and the lesson was available
  the whole time.

  Anything that must be true of EVERY image belongs in this function. If a new
  requirement shows up and you find yourself adding it to a caller, that is the
  bug repeating."
  [session store]
  (let [dir (framework-dir! session store)]
    (repl/start! (cond-> {:slopp.image.repl/deps (image-deps store)}
                   dir (assoc :slopp.image.repl/dir dir)))))

(defn image-with-deps!
  "A ready owned image for `store`: adopt the bare `spare` and hot-`add-libs`
  what the store needs into it, or launch fresh through [[start-image!]]. The
  caller owns spare bookkeeping (nil-ing + rewarming).

  Adoption is safe because [[start-spare!]] launches the spare in the session's
  framework dir. It briefly was not: a spare launched in its own dir has nothing
  vendored, and a JVM cannot pick up a relative classpath directory after
  launch, so adopting one handed back an image missing the framework. The first
  fix REFUSED adoption whenever a framework was needed — correct, and it cost
  the warm spare on every restart. Giving the spare the dir up front is the same
  guarantee without the loss, and it follows from having one door: whatever
  `start-image!` prepares, the spare is prepared with too.

  **The spare's dir must MATCH what this store now needs**, which is not the
  same as it having one. A spare is launched from the store as it was THEN; a
  store that gained web code since is a store whose spare was warmed without a
  framework, and adopting it would hand back the exact broken image this whole
  round has been about. `add-libs!` cannot repair that — it reconciles the
  MANIFEST, which can change between warming and adoption, and the framework is
  files, which cannot. Found by writing the adoption test rather than by hitting
  it, which is the order worth preferring."
  [session store spare]
  (let [deps (image-deps store)
        dir  (framework-dir! session store)]
    ;; `nil? dir` FIRST: a spare always has a dir of its own, so a bare
    ;; equality check refuses adoption for every store needing no framework —
    ;; which is most of them, and is a warm-spare regression rather than a
    ;; safety property. The dir only has to MATCH when one is required.
    (if (and spare (or (nil? dir) (= dir (:dir @spare))))
      (let [img @spare]
        (if (and (seq deps) (:err (repl/add-libs! img deps)))
          (do (repl/stop! img) (start-image! session store))
          img))
      (do (when spare (repl/stop! @spare))
          (start-image! session store)))))

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

(defn load-observations
  "Every persisted observation, `{meta-key raw-edn}`, or `{}` without a db —
  the sibling of `load-trace`. Observations are durable (written by
  `remember-observation!`) but READ constantly by the card view, so they load
  once into session state rather than making every card a db query. That is
  what lets `slopp.read.orient` stay off `slopp.db`."
  [conn]
  (if conn
    (or (db/meta-with-prefix conn "observed/") {})
    {}))

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
                             u (:var-usages (analyze/analyze (store.render/render-ns candidate t)))
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

(defn- ensure-db!
  "The session's journal connection, CREATING the store on first use when the
  session has a dir but no store yet.

  This is the only place a directory becomes slopp-managed implicitly, and
  it is deliberately on the WRITE path: `external/open!` no longer creates a
  store just because the MCP server was launched somewhere, so a session on
  an unadopted dir runs cache-only until real work arrives. Returns nil for
  a dirless session, which stays ephemeral forever."
  [session]
  (or (:db @session)
      (when-let [dir (:dir @session)]
        (let [conn (db/open! dir)
              s    (swap! session update :db #(or % conn))]
          ;; a concurrent writer may have won the race — keep the winner's
          ;; connection and release ours rather than leaking it
          (when-not (identical? conn (:db s))
            (.close ^java.sql.Connection conn))
          (:db s)))))

(defn try-commit!
  "Commit base→st' — JOURNAL-FIRST for durable sessions (m5a storage
  inversion): the new deltas + the full element rows of `nses` land in ONE
  conditional db transaction (iff the journal head still equals base's
  head), then the cache follows; the cache is only ever behind the journal,
  never ahead. Ephemeral sessions commit to the cache alone (identity CAS).
  True iff committed; false = the head/cache moved — caller refreshes and
  rebases, or surfaces contention."
  [session base st' nses]
  (if-let [conn (ensure-db! session)]
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

(defn ^:export commit-appended!
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

(defn load-all-namespaces!
  "Load every store namespace into `image` (dependency order, red-first test
  specs stubbed and retried), returning `[{:ns sym :why err} …]` for the ones
  that FAILED — empty when the whole store loaded.

  Collect-and-continue, never throw-on-first: the kernel HOST boot has worked
  this way since frictions 3b/3f/19 (\"ONE namespace that no longer compiles
  took down every tool in every process — including the edit_add_form that
  would have put the missing form back\"), and its boot note promises the
  store stayed open so the broken namespace can be FIXED. The ORACLE boots
  (session open, restart) were the only loops still refusing outright — the
  refusal parked a Throwable in :image-ready and `await-image!` rethrew it in
  front of every non-read tool, wedging a real consumer's store with no
  repair available from inside (slopp-ui, 2026-08-06). The wedge population
  is code verified-good at write time and invalidated from OUTSIDE — a
  framework rename, a dependency bump, a platform declaration stranding a
  JVM caller; per-write verification means nothing inside a store creates it.

  Callers record the result on the session as `:image-load-failures`, where
  the write path reconciles it ([[hot-load-all!]]) and `done!` subtracts and
  reports it."
  [image store]
  (vec (keep (fn [ns-sym]
               (when-let [err (image/load-ns! image store ns-sym)]
                 (when-not (and (stub-missing-test-vars! image store [ns-sym])
                                (nil? (image/load-ns! image store ns-sym)))
                   {:ns ns-sym :why err})))
             (store/ns-dependency-order store))))

(defn fresh-image!
  "Replace the image with a fresh process reloaded from the store — faithful by
  construction (the D5 backstop). With a warm spare, the swap avoids a JVM boot
  on the critical path; the next spare starts warming immediately.

  A namespace that fails to load is RECORDED (session `:image-load-failures`,
  via [[load-all-namespaces!]]) and the boot continues — never thrown. The
  throw was half of a real consumer's wedge: a store invalidated from outside
  could not restart, and the write that would fix it died at the same error."
  [session]
  (let [{:keys [image spare store]} @session
        ;; THE fix: every image this session boots gets the framework, not just
        ;; the first. fresh-image! is on the path of restart, deps_add/remove,
        ;; branch switch, ns_rename and the D5 staleness heal — all of which
        ;; used to hand back an image with no framework at all, masked for as
        ;; long as the store still declared the coord.
        fresh (image-with-deps! session store spare)]  ; adopt+reconcile or fresh
    (repl/stop! image)
    (swap! session assoc :image fresh :spare nil)
    (start-spare! session)
    ;; No reset call: the new image carries its OWN record, minted empty by
    ;; `repl/start!`. This used to be `(currency/forget-all!)` against a
    ;; process-global atom, with a comment explaining that carrying the dead
    ;; image's stamps would claim the new one holds them. A record that cannot
    ;; outlive its image cannot make that claim.
    (let [{:keys [store image]} @session
          fails (load-all-namespaces! image store)]
      (swap! session assoc :image-load-failures (not-empty fails))
      ;; the loop above stamped every namespace it loaded, so the record is now
      ;; complete and a form without a stamp is real news. Arming only here —
      ;; never on a stamp — is what stops a half-filled record reporting the
      ;; whole store as never-loaded.
      (image.currency/arm! image))))

(defn load-error-message
  "The message to report for a `hot-load-all!` result — nil when it loaded.

  When the heal's retry failed DIFFERENTLY from the first attempt, the
  post-heal error is an artifact of the RECOVERY and the pre-heal one is the
  fault. Reporting only `:err` is how a merge refusal pointed at a classpath
  that was never the problem, hiding the compile error underneath it for
  hours. Both, labelled, or the surface is lying about which is which."
  [r]
  (when-let [e (:err r)]
    (if-let [f (:first-err r)]
      (str e "\n\nNOTE: the image was refreshed mid-load and the retry failed"
           " differently. The error BEFORE the refresh — the one to fix — was:\n"
           f)
      e)))

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
  with FileNotFound when a survivor requires it.
  A touched namespace sitting in the session's `:image-load-failures` (it
  failed the last boot) is RECONCILED on success: the candidate namespace is
  loaded WHOLE, and when it loads it leaves the failure set — so the write
  that fixes a boot-broken namespace verifies against the POST-edit state,
  which is the promise the boot note makes and the sequencing used to break
  (a real consumer's wedge, 2026-08-06)."
  [session candidate form-ids]
  (let [nses    (vec (distinct (keep #(store/ns-of-form-id candidate %) form-ids)))
        stub!   #(stub-missing-test-vars! (:image @session) candidate nses)
        replay! #(let [committed (set (keys (:namespaces (:store @session))))
                       ;; every namespace the CANDIDATE has that the COMMITTED
                       ;; store lacks, not just this call's. fresh-image! boots
                       ;; from the committed store, so those cannot survive it —
                       ;; and a MERGE creates them in EARLIER hot-load-all!
                       ;; calls whose form-ids are not ours. Replaying only
                       ;; `nses` left them missing and the dependent's :require
                       ;; died with FileNotFound: an error the heal itself
                       ;; MANUFACTURED, naming a classpath problem that never
                       ;; existed while burying the real first failure.
                       want      (into (set nses)
                                       (remove committed)
                                       (keys (:namespaces candidate)))]
                   (doseq [ns-sym (filter want (store/ns-dependency-order candidate))]
                     (image/load-ns! (:image @session) candidate ns-sym)))
        reconcile! #(when-let [failed (not-empty
                                       (set/intersection
                                        (set (map :ns (:image-load-failures @session)))
                                        (set nses)))]
                      (doseq [ns-sym failed]
                        (when (nil? (image/load-ns! (:image @session) candidate ns-sym))
                          (swap! session update :image-load-failures
                                 (fn [fs] (not-empty
                                           (vec (remove (comp #{ns-sym} :ns) fs))))))))]
    (letfn [(load-all []
              (loop [ids (seq form-ids)]
                (when ids
                  (or (when (store/jvm-loadable? candidate
                                             (store/ns-of-form-id candidate (first ids)))
                        ;; a :cljs form (js/*/DOM) is never JVM-loaded — skip it
                        ;; here exactly as image/load-ns! skips a :cljs ns, so the
                        ;; refactor ops (rename/move/extract/change-sig/…) work on
                        ;; client forms (D-web-cljs). Per-form-id, so a multi-ns op
                        ;; that mixes platforms loads the :jvm/:cljc ids and skips
                        ;; the :cljs ones in the same pass. A skipped id is nil,
                        ;; the same shape as an unresolved id, so the loop recurs.
                        (hotload/hot-load-form! (:image @session) candidate (first ids)))
                      (recur (next ids))))))]
      (let [result
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
                            (seq stubbed) (assoc :stubbed stubbed))))))))]
        (when-not (:err result)
          (reconcile!))
        result))))

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
                      ;; NOT gated on `load?`. Ordering is a property of the SOURCE, and
                      ;; ClojureScript has the same define-before-use rule (its
                      ;; compiler warns). Skipping the reorder for :cljs let a
                      ;; forward reference land SILENTLY — and since the move gate
                      ;; still refused any move while a violation stood, no single
                      ;; edit_move reached a legal state. Created without a word,
                      ;; then unfixable by the tool the error message recommends.
                      (if (:error out)
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
              ;; the loser's code is already hot-loaded, and each durable
              ;; session has its OWN image — the winner's hot-load happened in
              ;; another process. Reboot from the refreshed store so nothing
              ;; verifies against code the journal rejected.
              (do (when loaded? (fresh-image! session))
                  conflict)
              (let [out (transform base)]
                (if (:error out)
                  out
                  (let [load-res (when (and load? (not loaded?))
                                   (let [lr (lintgate/lint-refusals base (:store out) [ns-sym]
                                                                [(:form-id (:delta out))])]
                                     (if-let [gate (or (edit/cold-load-errors (:store out) [ns-sym])
                                                       (:refuse lr))]
                                       {:err gate}
                                       (merge (hot-load-all! session (:store out)
                                                    [(:form-id (:delta out))])
                                     (select-keys lr [:carried :red-first-arity])))))]
                    (if (:err load-res)
                      (edit/compile-error (:store out) (:err load-res)
                                          "form failed to compile: " ns-sym)
                      (do (when *pre-commit-hook* (*pre-commit-hook*))
                          (if (try-commit! session base (:store out) [ns-sym])
                            (cond-> out
                              (or healed? (:healed load-res))
                              (assoc :image-healed true)

                              (or stubbed (:stubbed load-res))
                              (assoc :red-first (or stubbed (:stubbed load-res)))

                              (or carried (:carried load-res))
                              (assoc :carried-errors (or carried (:carried load-res)))

                              ;; NOT threaded through the retry like `stubbed`
                              ;; and `carried` are: those two decide whether the
                              ;; write is honest, this one is a note explaining
                              ;; a red the agent is about to see anyway. A
                              ;; CONTENDED write (attempt > 0, where the gate no
                              ;; longer runs) drops it, and the cost is a missing
                              ;; sentence rather than a missing verdict.
                              (:red-first-arity load-res)
                              (assoc :red-first-arity (:red-first-arity load-res)))
                            (do (refresh-cache! session)
                                (recur (inc attempt) true
                                       (or healed? (boolean (:healed load-res)))
                                       (or stubbed (:stubbed load-res))
                                       (or carried (:carried load-res))))))))))))))
      ;; ephemeral: the pure transform reruns INSIDE swap! — starvation-free.
      ;; No image heal on conflict here: ephemeral writers share ONE image, so
      ;; the competitor's own hot-load already put the winner's code in it.
      (let [base0 (:store @session)
            out0  (transform base0)]
        (if (:error out0)
          out0
          (let [load-res (when load?
                           (let [lr (lintgate/lint-refusals base0 (:store out0) [ns-sym]
                                                        [(:form-id (:delta out0))])]
                             (if-let [gate (or (edit/cold-load-errors (:store out0) [ns-sym])
                                               (:refuse lr))]
                               {:err gate}
                               (merge (hot-load-all! session (:store out0)
                                             [(:form-id (:delta out0))])
                                      (select-keys lr [:carried :red-first-arity])))))]
            (if (:err load-res)
              (edit/compile-error (:store out0) (:err load-res)
                                  "form failed to compile: " ns-sym)
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
                      (assoc :carried-errors (:carried load-res))

                      (and (nil? (:error @res)) (nil? (:conflict @res))
                           (:red-first-arity load-res))
                      (assoc :red-first-arity (:red-first-arity load-res))))))))))))

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

(defn covering-test-nses
  "Test namespaces whose requires REACH any of `ns-syms` — the verification
  scope to fall back on when trace evidence is missing.

  The old fallback ran tests IN the touched PRODUCTION namespaces, which
  contain none: on slopp's own store `test_run {ns \"slopp.git\"}` runs zero
  tests while five test namespaces cover it. So a write without trace evidence
  verified NOTHING while still reporting a result — and a multi-form refactor,
  least likely to carry complete evidence, was the most exposed of all.

  Naming cannot answer this. `slopp.git` is covered by
  `slopp.git-projection-test`, not `slopp.git-test`, so an `x` → `x-test`
  heuristic finds nothing here. Only the require graph knows, and it is
  walked TRANSITIVELY so a test reaching the change through one hop counts.

  Returns a sorted vector, empty when genuinely nothing covers `ns-syms` —
  which is a real answer, and the caller reports it as such rather than as a
  pass."
  [store ns-syms]
  (let [known   (set (keys (:namespaces store)))
        targets (set ns-syms)
        reqs    (memoize (fn [n] (filter known (store/ns-requires store n))))
        reaches? (fn [t]
                   (loop [seen #{}, queue [t]]
                     (if-let [n (first queue)]
                       (cond
                         (seen n)    (recur seen (rest queue))
                         (targets n) true
                         :else       (recur (conj seen n)
                                            (into (vec (rest queue)) (reqs n))))
                       false)))]
    (->> known
         (filter store.render/test-ns?)
         (filter reaches?)
         sort
         vec)))

(defn affected-tests
  "Which tests must re-run after editing `ns-sym/nm`: the tests observed (via
  tracing) to exercise that form — or the form itself if it IS a test. nil =
  no usable trace information; run everything (conservative).

  Form-aware (#129): evidence is matched against EVERY name the form defines
  (`store/form-trace-keys`) — a test calling protocol method `m` recorded
  `ns/m`, though the form's primary name is `P`; `->R` evidence belongs to
  `R`'s form. And a `method-carrying?` form (defmethod, defrecord/deftype,
  extend-*) NEVER narrows: its bodies run where the tracer cannot fully see
  them, so its evidence is structurally partial, and narrowing on partial
  evidence is how a false green happens. nil sends the caller to the same
  closure fallback a silent trace does.

  ROUTE-aware (D-web-html): a web endpoint's tests reach it through
  `web/handle!`'s runtime route scan, so they leave no static reference AND no
  trace evidence until they have run once — every endpoint write reported
  `:no-covering-tests` during exactly the writes its red route test existed
  for. When trace evidence is silent, `api.web/endpoint-test-refs` joins the
  static route table to the literal URIs in test forms. Consulted only AFTER
  tracing, so recorded evidence always wins; a form that is not an endpoint
  simply misses the join and falls through to nil as before.

  DECLARE-aware (#4 follow-up): a `^{:covers}` test reaches the form through a
  dispatch/data/child-image path the tracer structurally can't see, so it
  leaves no trace and no static edge. Its coverage — the `:declared` producer
  of `refs/covered-by` — is UNIONED into any non-nil result: a declaration is
  a floor (at least these run), not a ceiling, so it never narrows on its own
  (a nil result already runs everything, the declared tests included)."
  [session ns-sym nm]
  (let [qform (symbol (str ns-sym) (str nm))
        tmap  (:test-map @session)
        declared (->> (refs/covered-by (:store @session) tmap qform)
                      (filter #(contains? (:via %) :declared))
                      (map :test))
        with-declared (fn [res]
                        (when res
                          (vec (sort (distinct (concat res declared))))))
        via-routes (fn []
                     (when-let [hits (get (rules.web/endpoint-test-refs (:store @session))
                                          qform)]
                       (vec (sort hits))))]
    (with-declared
      (if (contains? tmap qform)
        [qform]
        (let [e (store/form-named (:store @session) ns-sym nm)]
          (cond
            (nil? e)
            (let [hits (->> tmap
                            (keep (fn [[t forms]] (when (contains? forms qform) t)))
                            sort vec)]
              (when (seq hits) hits))

            (store/method-carrying? e) nil

            :else
            (let [ks   (store/form-trace-keys ns-sym e)
                  hits (->> tmap
                            (keep (fn [[t forms]] (when (some forms ks) t)))
                            distinct sort vec)]
              (if (seq hits) hits (via-routes)))))))))

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

(defn external-test-nses
  "Of `nses`, those defining at least one ^:external deftest — tests only
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
                                  (boolean (:external (meta (second s)))))))
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
   {:image [...] :external [...]}. `^:external` tests spawn images / recurse,
   so the IN-IMAGE runner must skip them (they only behave in the external
   tier) — this is what lets `traced-run!` defer them as :external-pending
   instead of running (and false-greening) them in-image."
  [store ns-sym]
  (reduce (fn [m e]
            (let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
              (if (and (seq? s) (= 'deftest (first s)))
                (update m (if (:external (meta (second s))) :external :image)
                        (fnil conj []) (second s))
                m)))
          {:image [] :external []}
          (store/forms store ns-sym)))

(defn- ran-nothing
  "The summary for an in-image run that did not HAPPEN, carrying what the runner
  said instead.

  `image/traced-test-run` answers `{:summary … :trace …}`, and answers something
  else when the eval threw — the exception arrives as printed text. Destructuring
  that gave nil, and nil flowed on to callers whose `cond->` dressed it as a map
  with no counts in it. Counts-of-zero is the one shape that must never come
  back, because every caller reads it as a clean run: `full_check` reported its
  in-image tier green having run nothing at all.

  So `:error 1` — every status check in the codebase sums `:fail` and `:error`,
  which makes this red everywhere without a new convention — and the runner's
  own words ride along, bounded, because the next cause will be a different one
  and undiagnosable without them."
  [result]
  (let [said (str result)]
    {:test 0 :pass 0 :fail 0 :error 1 :type :summary
     :failures [{:test     'slopp.image/traced-test-run
                 :type     :error
                 :message  (str "the in-image runner returned no summary — the"
                                " run did not happen, so this is not a green")
                 :expected "{:summary {...} :trace {...}}"
                 :actual   (subs said 0 (min 400 (count said)))}]}))

(defn traced-run!
  "Run `test-ns`'s tests (all, or `only` names) with form-tracing; absorb the
  observed test→form map into the session (persisted — Q3); return the summary.
  `skip-integration?` drops `^:integration` tests (M5, the fast-path default).
  The in-image tier NEVER runs `^:external` tests (they spawn images / recurse
  and only behave in the external tier): any in scope are filtered OUT of the
  run and reported as `:external-pending` on the summary — never executed
  in-image (which would false-green/false-red them). The done-point / merge
  gate runs them for real in the external tier.

  A result carrying no `:summary` becomes [[ran-nothing]] rather than nil: the
  runner threw, and a run that did not happen has to read as red, not as a run
  that found nothing."
  [session test-ns only & [skip-integration?]]
  (let [{:keys [image store]} @session
        nses     (if (coll? test-ns) test-ns [test-ns])
        external (into #{} (mapcat #(:external (test-var-tiers store %))) nses)
        run!     (fn [only']
                   (let [res (image/traced-test-run
                              image store test-ns :only only'
                              :skip-integration? skip-integration?)
                         {:keys [summary trace]} (when (map? res) res)]
                     (swap! session update :test-map merge trace)
                     (persist-trace! session)
                     (or summary (ran-nothing res))))]
    (if (empty? external)
      ;; no ^:external tests in scope — original path, untouched
      (run! only)
      ;; some are external — run only the in-image tier, defer the rest
      (let [pending (if only (filterv external only) (vec external))
            only'   (if only
                      (vec (remove external only))
                      (vec (mapcat #(:image (test-var-tiers store %)) nses)))
            summary (if (empty? only')
                      ;; every impacted test is external — nothing to run here
                      {:test 0 :pass 0 :fail 0 :error 0 :type :summary}
                      (run! only'))]
        (cond-> summary
          (seq pending)
          (assoc :external-pending
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

(def cljs-deferred-summary
  "Verification summary for a write to a :cljs (non-jvm-loadable) namespace.
  Such code references js/* / the DOM and never loads into the JVM oracle, so
  there is nothing to run here — its red/green comes from the ClojureScript
  compiler (compile_client), not the test suite. Reported :unverified with a
  reason that says the check is DEFERRED, distinct from :no-covering-tests (a
  coverage gap the agent should close). D-web-cljs."
  {:test 0 :pass 0 :status :unverified :reason :cljs-deferred-to-compile})

(defmulti ^:export after-write!
  "Follow-up once a write to `ns-sym` has LANDED, dispatched on that
  namespace's platform (`:jvm` / `:cljc` / `:cljs`). Whatever a method returns
  is merged into the write's result map; nil adds nothing, and `:default` is
  nil — so the ordinary JVM write pays one platform lookup and a dispatch.

  This exists so the write engine does not have to know which app types exist
  (R6). It used to know: four forms of ClojureScript bundle machinery lived
  here, called from every generic write verb, and they could only reach the
  compiler through `store/late-ref` — an ^:unsafe escape hatch whose entire job
  was to break a cycle the misplacement itself created, since the client build
  requires the operation surface that calls this engine.

  Registering inverts that edge: the app type depends on the engine, never the
  reverse, and app type #2 arrives as another `defmethod` rather than another
  branch in here. Dispatching on PLATFORM rather than on \"is this web?\" is the
  same discipline one level down — the engine asks a question the store can
  answer about any namespace, not a question only one app type has."
  (fn [session ns-sym] (store/platform-for (:store @session) ns-sym)))

(defmethod after-write! :default [_ _] nil)

(defn run-verification!
  "Diagnosed run of `affected` tests (grouped by their namespace), or of all of
  `default-ns`'s tests when there's no trace information — and of `default-ns`
  ANYWAY when a non-empty `affected` resolves to no tests at all, because a
  scope that names tests which no longer exist is a stale scope, not an answer.
  `affected` = `[]` is exempt: that is a deliberate verify-nothing, not a gap. `:edited` (the
  just-changed form qsyms) powers the D5.1 genuine-vs-suspicious call and the
  red-result :implicated correlation (Rock 2). Results pass through the
  episode-red shaper (direction over repetition); `:boundary? true` (the
  done-point) bypasses compression and resets the ledger.

  The summary carries `:ms`, the wall time this verification took. It rides
  the `:verify` delta's `:result` through all twelve `record-verification`
  call sites, so the journal answers what verification COSTS as well as what
  it found. Without it the only after-the-fact attribution is the gap between
  consecutive deltas, which mixes tool execution with agent thinking and gets
  the answer wrong — the expensive whole-store operations are precisely the
  ones that leave no delta at all."
  [session default-ns affected & {:keys [edited fresh include-integration? boundary?]}]
  (let [t0 (System/currentTimeMillis)
        r  (cond-> (shape-episode-reds!
                    session
                    (implicate
                     (if (nil? affected)
                       (diagnosed-run! session default-ns nil :edited edited :fresh fresh
                                       :include-integration? include-integration?)
                       (let [by-affected
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
                                     (group-by (comp symbol namespace) affected))]
                         ;; The affected set can name tests that no longer RESOLVE —
                         ;; a renamed deftest, a deleted one, a stale trace entry.
                         ;; Then this runs zero and reports :scope-ran-nothing, which
                         ;; `slopp.mcp/summarize` already classifies as a slopp bug:
                         ;; honest, and useless, because the write IS verifiable —
                         ;; just not by this scope. Fall back to the namespace, the
                         ;; same rule `done` uses per form when trace evidence is
                         ;; missing.
                         ;;
                         ;; Only when something WAS named. `affected` = [] is a
                         ;; DELIBERATE verify-nothing (an alias-only require change
                         ;; is semantically inert), so a blanket retry would overturn
                         ;; a considered decision instead of recovering from a stale
                         ;; one. nil means no evidence, [] means evidence of nothing
                         ;; to do, and only the first two warrant a second look.
                         (if (and (seq affected) (zero? (:test by-affected 0)))
                           (diagnosed-run! session default-ns nil :edited edited :fresh fresh
                                           :include-integration? include-integration?)
                           by-affected)))
                     (:test-map @session)
                     edited)
                    affected default-ns boundary?)
             ;; the done-point runs the external tier for REAL right after this, and
             ;; reports its own cap in :findings — an in-image deferral note there is
             ;; noise about an implementation detail
             boundary? (dissoc :external-pending))]
    (assoc r :ms (- (System/currentTimeMillis) t0))))

(defn absorb-trace!
  "Merge an EXTERNAL-tier trace (#121) into the session's test-map and persist
  it (Q3), exactly as `traced-run!` does for the in-image tier — one test-map,
  one shape, whichever tier observed it. No-op on nil/empty: `read-traces`
  returns nil when the build carried no trace runner, and 'not traced' must
  never overwrite what another tier observed.

  Plain `merge`, not `merge-with into`: a fresh run of a test is the
  AUTHORITATIVE current set for that test — unioning would accumulate forms it
  no longer touches and quietly rot the narrowing."
  [session trace]
  (when (seq trace)
    (swap! session update :test-map merge trace)
    (persist-trace! session)))

(defn external-among
  "Of qualified test syms `tests`, those tagged ^:external — the ones only the
  EXTERNAL tier can execute.

  The routing half of affected-test selection (#127). `affected-tests` names the
  tests a change reaches; this says which of them the in-image runner had to
  defer, so `done!` can hand exactly those to the external tier instead of
  re-deriving a set from the require-closure. That closure selects a median 43
  of 46 external test namespaces (measured over every source ns 2026-07-17) —
  it is not narrowing, it is 'everything' with rounding.

  Empty is NOT the same as a silent trace: it means the evidence names tests and
  none of them are external, so the external tier has nothing to do. A silent
  trace is `affected-tests` returning nil, and that must still fall back to the
  closure."
  [store tests]
  (vec (sort (mapcat (fn [[nsx syms]]
                       (let [iso (set (:external (test-var-tiers store nsx)))]
                         (filter #(iso (symbol (name %))) syms)))
                     (group-by (comp symbol namespace) tests)))))

(defn- prior-source
  "The source `fid` held immediately BEFORE the newest delta that touched it,
  read from the journal — nil when unknown (created by ingest, or touched
  only once), which callers treat conservatively."
  [store fid]
  (->> (rseq (:deltas store))
       (keep #(get (:sources %) fid))
       (drop 1)
       first))

(defn inert-ns-require-change?
  "True when an ns-form edit only ADDED require specs that cannot change the
  resolution or load behaviour of anything already compiled: alias-only
  vectors (`[lib :as a]`) naming IN-STORE namespaces whose require-CLOSURE
  registers no methods. Everything else — :refer (resolution can shift),
  removals or renames, out-of-store libs (load effects unknown), a required
  ns whose closure LOADS defmethods, ANY metadata change on the ns form, any
  non-require edit — is not inert. Conservative: an unreadable or absent
  baseline answers false.

  `old-src` is the ns form's source at the baseline to diff against — the
  1-arity uses the delta immediately prior (the write path, one edit); the
  done path passes the LAST-DONE source so a multi-edit episode where an
  earlier edit added a :refer isn't masked by a later alias-only edit
  (review V-F3).

  frictions #2: ns_add_require on slopp.api invalidated 331 external tests
  for an edit whose blast radius is zero — the require-closure fallback
  treated a require-list touch as a code change to the whole namespace."
  ([store fid] (inert-ns-require-change? store fid (prior-source store fid)))
  ([store fid old-src]
   (let [read* (fn [s] (try (n/sexpr (p/parse-string (str s)))
                            (catch Exception _ nil)))
         e     (store/form-by-id store fid)
         new   (some-> e :node n/sexpr)
         old   (read* old-src)
         req?  (fn [c] (and (seq? c) (= :require (first c))))
         reqs  (fn [form] (set (mapcat rest (filter req? (drop 2 form)))))
         ;; the ns form with its require clauses stripped — everything whose
         ;; change is NOT a plain require add: name, docstring, :import,
         ;; :require-macros, :gen-class …
         non-req (fn [form] (cons (second form) (remove req? (drop 2 form))))
         ;; metadata is invisible to = (on symbols and colls alike), and a
         ;; test-selector tag / load hint on the ns name is behaviourally
         ;; live — compare the metadata of every node explicitly (V-F2)
         metas   (fn [form] (mapv meta (tree-seq coll? seq form)))
         ;; a required ns is quiet only if its WHOLE in-store closure
         ;; registers no methods — loading it loads them all (V-F1)
         quiet?  (fn [lib]
                   (not-any? store/method-carrying?
                             (mapcat #(store/forms store %)
                                     (store/ns-closure store lib))))]
     (boolean
      (and (seq? old) (seq? new)
           (= 'ns (first old) (first new))
           (= (non-req old) (non-req new))
           (= (metas (non-req old)) (metas (non-req new)))
           (set/subset? (reqs old) (reqs new))
           (let [added (set/difference (reqs new) (reqs old))]
             (and (seq added)
                  (every? (fn [spec]
                            (and (vector? spec)
                                 (symbol? (first spec))
                                 (even? (count (rest spec)))
                                 (every? #(= :as %) (take-nth 2 (rest spec)))
                                 (contains? (:namespaces store) (first spec))
                                 (quiet? (first spec))))
                          added))))))))

(defn impacted-tests
  "Every test var the changed form-ids can affect, decided PER FORM (#132):
  a form with trace evidence contributes exactly its observed tests; a form
  without contributes every test in the namespaces whose require-closure
  reaches ITS namespace. Never nil — [] means nothing reaches.

  Replaces the all-or-nothing collapse, where ONE untraced form discarded
  every other form's evidence and reverted the whole done to closure runs.
  Measured on the journal (2026-07-17): 54.4% of real episodes touched a form
  the tracer can never see — 43.2% an NS FORM (ns_add_require edits one),
  28% a data def — so the collapse was the common case, not the corner.

  The dominant untraced form is the ns form, and its commonest edit is an
  alias-only require addition — SEMANTICALLY inert, so it contributes
  NOTHING instead of its whole closure (inert-ns-require-change?,
  frictions #2). Inertness is judged against the LAST-DONE baseline (the
  episode's start), so a multi-edit episode where an earlier edit added a
  :refer isn't masked by a later alias-only edit (review V-F3). Every other
  untraced shape keeps the closure fallback: `test-nses-reaching` over a
  union of namespaces IS the union of the per-namespace calls (the closure
  intersection distributes), so untraced forms select exactly what the
  global fallback selected for them, while traced forms keep their narrow
  sets."
  [session store changed]
  (let [baseline (->> (:deltas store) (filter #(= :done (:op %))) last :id)
        base-src (when baseline (store/sources-at store baseline))
        reach (memoize
               (fn [ns-sym]
                 (vec (for [tns (test-nses-reaching store [ns-sym])
                            :let [tiers (test-var-tiers store tns)]
                            nm (concat (:image tiers) (:external tiers))]
                        (symbol (str tns) (str nm))))))]
    (vec (sort (distinct
                (mapcat (fn [fid]
                          (if-let [e (store/form-by-id store fid)]
                            (let [ns-sym (store/ns-of-form-id store fid)]
                              (cond
                                (and (= (:name e) ns-sym)
                                     (inert-ns-require-change?
                                      store fid
                                      (if baseline
                                        (get base-src fid)
                                        (prior-source store fid))))
                                []

                                :else
                                (or (affected-tests session ns-sym
                                                    (or (:name e) (symbol (:id e))))
                                    (reach ns-sym))))
                            []))
                        changed))))))

(defn impacted-external
  "The ^:external test vars the changed form-ids can affect, for the
  done-point to route to the external tier — `impacted-tests` filtered to the
  tier only the external runner can execute.

  Never nil (#132): an untraced form expands to its own namespace's reach
  instead of collapsing the whole answer, so [] genuinely means no external
  test can be affected. The #127 version returned nil on ANY untraced form and
  done! fell back to the require-closure of everything — which selects a
  median 43 of 46 external test namespaces and deferred 84.6% of changes."
  [session store changed]
  (external-among store (impacted-tests session store changed)))

(defn- sha256
  "Hex SHA-256 of a string. A REAL digest rather than [[slopp.image.currency/hash-of]],
  which says in its own docstring that it is in-process only because its
  registry is never persisted — this one is written into the journal and
  compared by a later process, so the guarantee has to hold across JVMs."
  [^String s]
  (->> (.digest (java.security.MessageDigest/getInstance "SHA-256")
                (.getBytes s "UTF-8"))
       (map #(format "%02x" %))
       (apply str)))

(defn ^:export closure-hashes
  "For each namespace in `scope`, the CONTENT IDENTITY of everything a verdict
  for it depends on: its own source, the source of every namespace its
  require-closure reaches, and the dependency manifest.

  This is what makes a verdict reusable in principle — *this test was green
  against exactly this content* — and it is recorded on the `:observe` delta so
  the question can be asked later, by a different process, from the journal
  alone. Two properties decide soundness and pull opposite ways: it must change
  when anything the test can LOAD changes (or a stale green outlives a real
  edit), and it must NOT change when unrelated code moves (or it is merely a
  store version and nothing is ever reusable).

  Reach is the require closure — [[slopp.store/ns-closure]], the same producer
  `test-nses-reaching` selects with, so the set a verdict is keyed to and the
  set a change is routed to cannot disagree. That closure is a conservative
  OVER-approximation of what a test executes, which is the safe direction here:
  it can only ever invalidate a verdict that would still have been valid.

  Each namespace is digested ONCE and the closures are combined from those
  digests, so asking about a hundred test namespaces renders each source once
  rather than once per closure that contains it."
  [store scope]
  (let [needed (into #{} (mapcat #(store/ns-closure store %)) scope)
        per-ns (into {} (map (juxt identity #(sha256 (str (store.render/render-ns store %))))) needed)
        deps   (sha256 (pr-str (:deps store)))]
    (into {} (for [n scope]
               [n (sha256 (str/join "|" (cons deps (map #(get per-ns % "?")
                                                        (sort (store/ns-closure store n))))))]))))
