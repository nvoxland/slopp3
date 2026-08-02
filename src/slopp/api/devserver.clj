(ns slopp.api.devserver
  "The app server slopp runs FOR you, so a project under development always
  has a live version up.

  An app should not have to hold a `serve!` call, a namespace list, or a port
  to be reachable while it is being written. Everything needed is already in
  the store — `http.enabled` says it is a web project, the endpoint and
  performer surface says what to serve, the capability registry says where.
  This namespace turns those into a launch, and keeps it current.

  **The deciding is separate from the running.** `serve-plan` is pure data
  from the store, so what a project would serve is answerable without
  spawning anything; the lifecycle spawns. That split is why the interesting
  rules here have ordinary in-image tests instead of needing a JVM apiece.

  **The app gets its OWN image, not the oracle's.** `session/fresh-image!` is
  on the path of `edit-replace!`, `rename!`, `move-forms!`, `deps-add!` and
  `merge-into-session!`, so an app served from the oracle would be killed by
  a refactor that never touched it. The two want opposite things — the oracle
  CURRENT and disposable, the app STABLE and pinned — and the oracle's
  requirement is the one that cannot move. A dedicated image also runs on the
  store's OWN dependency manifest rather than slopp's, which is what makes
  the dev server a check on the published surface rather than only a
  convenience.

  Serves at DONE grain, not per write: mid-episode the store is intentionally
  incomplete, and reloading a browser into a red half-written state trains
  the author to ignore it."
  (:require [slopp.api.capabilities :as capabilities]
            [slopp.api.web :as web] [slopp.store :as store] [slopp.api.session :as session] [slopp.image :as image] [slopp.image.repl :as repl] [clojure.string :as str] [clojure.java.io :as io] [slopp.store.artifacts :as artifacts]))

(defn ^:export self-served?
  "Whether the calling process ALREADY serves everything `store` would —
  `already-served` being the namespaces it has mounted itself.

  The one true case for not managing a store's app server, and the reason
  it is derived rather than declared. Managing this store would boot a
  second image and serve a SNAPSHOT of the very surface you are looking at,
  one done point behind the page in front of you — not a port conflict, a
  staler copy. slopp's own store is that case: its web surface is the
  reviewer API the live session already serves, over the LIVE store.

  It replaced the `dev.server` capability, which asked every project a
  question only one store on earth should answer. That misfired exactly as
  a footgun does: the second project to meet it set it false because its
  static ASSETS were 404ing, and the switch then presented a bug as a
  preference for a week. Computing it means a project cannot answer wrong,
  and cannot use the answer to paper over something else.

  **A store serving NOTHING is not self-served**, though every one of its
  zero namespaces is trivially already served. Vacuous truth here would
  exempt every non-web project for a reason that has nothing to do with
  them — the emptiness guard, in the position where it actually bites."
  [store already-served]
  (let [nses (set (web/serving-namespaces store))]
    (boolean (and (seq nses)
                  (every? (set already-served) nses)))))

(defn ^:export managed?
  "Whether slopp should run this store's app server while someone works on
  it — `already-served` being what the calling process has mounted itself.

  Two questions, and only one of them is the project's. `http.enabled` says
  the project SERVES HTTP — that is what makes the web rules and
  `query_routes` exist, and production reads it. The second used to be the
  `dev.server` capability and is now [[self-served?]], computed: a store
  whose surface this process already serves must not get a second, staler
  copy of it.

  **Nothing a web project configures decides this any more, and that is the
  point.** A project under development should not have to think about
  turning its server on, or keeping it current — those are slopp's job, and
  a per-project switch is an invitation to answer a question nobody should
  be asked. The evidence it was one: the only adopter that ever set the
  switch set it to work around 404ing assets, and the switch then made a
  bug look like a preference.

  Deliberately NOT folded into `serve-plan`. That answers \"what would this
  store serve, and where\", which production asks too, and a dev-only
  exemption in it would be an answer to a question it was not asked."
  [store already-served]
  (boolean (and (capabilities/effective store "http.enabled")
                (not (self-served? store already-served)))))

(defn derived-port
  "A localhost port DERIVED from the store dir for this project's APP server —
  stable across restarts, different for every project on the machine.

  SALTED, and here the salt is still load-bearing. There were three
  derivations of this shape; the git listener's went with the listener, so
  this and `http-api.server/derived-port` are what remain — and one MCP
  process binds both, so a shared formula would have every project
  \"reliably colliding with itself\".

  A PREFERENCE that can be refused, and here the answer DIVERGES from the UI
  listener on purpose. `mcp/start-ui!` falls back to an ephemeral port when
  its derived one is taken, because \"nobody needs to know this number; the
  address a human remembers is the hub's\". Nobody remembers an address for
  the app: a developer types it into a browser and keeps the tab. So a taken
  port is REPORTED (`start!` says so, and `http.port` is the fix, named in
  the message) rather than answered with a url that moves each time.

  The realistic collision is with our OWN previous server, which `refresh!`
  handles by stopping it before binding. A foreign holder is rare, and the
  ladder the UI listener needed — explicit, configured, derived, ephemeral —
  is not built here until something is actually colliding.

  The reported url always carries the port actually BOUND, which is what
  `serve!` hands back, not what was asked for."
  [dir]
  (+ 49152 (mod (hash (str "slopp-app:" dir)) 16384)))

(defn serve-plan
  "What to launch for this store's app server, as data — `{:enabled? :mode
  :namespaces :host :port :adapter}`, or `{:enabled? false :reason …}`.

  Pure, and separate from the launching on purpose: everything worth getting
  wrong here (is this a web project, what does it serve, on what address) is
  decidable from the store, and deciding it inside a function that also
  spawns a JVM would make it testable only by spawning one.

  `:namespaces` is DERIVED (`web/serving-namespaces`) — the app never hands
  over a list it can get wrong.

  `:port` prefers an explicitly SET `http.port` and otherwise DERIVES. The
  registry default of 8080 stands for production, where a known number is the
  point; a dev session wants collision-freedom instead, because two projects
  on one machine both taking the default is not a rare case — it is the
  second project. Same conclusion `slopp.api.port` reached, phrased in its own doc
  as a fixed default having \"worked for exactly one project and collided for
  the second\".

  `:mode` is `:dev`. It rides the plan so nothing downstream reads a dev plan
  as the shipped one: the two serve the same routes from different stores at
  different grains, and an unlabelled plan is a stand-in for whichever the
  reader assumed."
  [store dir]
  (if-not (capabilities/effective store "http.enabled")
    {:enabled? false
     :reason (str "http.enabled is false — config_file {path \"capabilities\" "
                  "key \"http.enabled\" value \"true\"} opts this store into HTTP")}
    {:enabled?   true
     :mode       :dev
     :namespaces (web/serving-namespaces store)
     :host       (capabilities/effective store "http.host")
     :port       (if (capabilities/stored? store "http.port")
                   (capabilities/effective store "http.port")
                   (derived-port dir))
     :adapter    (capabilities/effective store "http.adapter")
     ;; what the app NEEDS, not only where it answers. Dropping these is what
     ;; made a managed server 500 on any app that took slopp's own advice to
     ;; receive its dependencies as :web/deps.
     :max-body-bytes  (capabilities/effective store "http.max-body-bytes")
     :context-builder (web/context-builder store)
     ;; the app's own assets. A UI's stylesheet and cljs bundle ARE the
     ;; product, so a managed server that 404s them is not a lesser version
     ;; of the app — it is an unusable one, and the project it happened to
     ;; switched the managed server off rather than reading it as a bug.
     :static          (web/static-mounts store)}))

(defn load-order
  "The store namespaces to load into the app image, dependencies first.

  The transitive closure of the web surface over the store's require graph
  — NOT the whole store. The app image exists to run the app: loading
  everything would make its boot cost grow with the codebase and would put
  code in a serving process that nothing serving can reach.

  Dependency order is not a nicety here. A store namespace has no classpath
  presence, so a dependent loaded first would `:require` its way out to the
  classpath and fail (`image/load-ns-into!` marks `*loaded-libs*` for exactly
  this reason).

  **`slopp.web` is seeded when the STORE holds it.** slopp's own store does;
  an ordinary app gets the framework from its declared `slopp-web` coord,
  already on the child's classpath. Both must work without the app saying
  which, so this asks the store rather than requiring an answer — and its
  absence is a fact, not an error."
  [store]
  (let [builder (web/context-builder store)
        seeds   (cond-> (set (web/serving-namespaces store))
                  (contains? (:namespaces store) 'slopp.web) (conj 'slopp.web)
                  ;; the context builder is NOT part of the served surface —
                  ;; it declares no route and performs no kind — so nothing
                  ;; else pulls its namespace in, and the generated call would
                  ;; require its way out to a classpath the child lacks
                  builder (conj (symbol (namespace builder))))
        want    (into #{} (mapcat #(store/ns-closure store %)) seeds)]
    (filterv want (store/ns-dependency-order store))))

(def ^:export unserved-options
  "Options `slopp.web/serve!` and `slopp.web/context` accept that the generated
  call deliberately does NOT carry, and why each is missing.

  This exists so the gap is CLASSIFIED rather than merely absent, and
  `devserver-test/the-generated-serve-call-accounts-for-every-option-it-could-carry`
  holds the classification total: a new option on either function fails that
  test until it is generated or listed here. Without it `serve-code` was four
  of eight, and the four it dropped were every option describing the APP —
  found by a real app measuring a live server rather than by anything here.

  A partial classification would be worse than none, which is why each entry
  carries a REASON — the same discipline `slopp.index.crossings/internal-markers`
  follows. An entry whose reason is \"not done yet\" is a worklist item that
  cannot be lost; an entry with no reason is an omission wearing a decision's
  clothes."
  {
   :web/auth-config "not threaded yet. Nobody has hit it only because the apps
                     measuring the managed server have no auth; one that did
                     would deny everything, or fail resolving identity"})

(defn ^:export materialize-static!
  "Write every file the `mounts` cover into a fresh temp dir and return its
  path — nil when there are no mounts, so an app without assets allocates
  nothing. `store-dir` is the STORE's directory, the root of the on-disk
  artifact cache.

  The managed app image is a separate JVM with NO store, which is the whole
  reason static mounts went unserved: `mount-routes` takes a reader, and the
  only reader the child could have used wanted a store. Materializing turns
  that into the reader it CAN use, `file-or-resource-reader`, pointed at a
  dir. Paths keep their manifest shape under the dir, so one mount serves a
  tree rather than a flat list.

  **An ARTIFACT keeps its bytes OUT OF LINE, so `store/file-content` answers
  with metadata and a nil `:content` — that is the ordinary case, not an
  edge one.** `compile_client` writes the cljs bundle as an artifact, so for
  a UI this is every asset it has: the first real app to try this had an
  EMPTY `:files` and two artifacts, and got a materialized dir containing
  nothing. `artifacts/fetch` is the accessor that reads
  `<store-dir>/.slopp/artifacts/<sha>`; the blob table is a different store
  and does not hold these.

  **`store-dir` is deliberately not called `dir` here.** The output dir is
  also a dir, and the first cut of this fetched artifacts from the temp
  directory it was writing INTO — which resolves, returns nothing, and skips
  exactly the files it was added to rescue.

  A path nothing can supply is SKIPPED rather than fatal: a missing asset is
  a 404, and a throw would take down a server serving every other path. That
  skip is why this shipped broken and silent once already, so anything that
  reaches it is worth suspecting before it is trusted."
  [store mounts store-dir]
  (when (seq mounts)
    (let [out     (java.nio.file.Files/createTempDirectory
                   "slopp-static"
                   (make-array java.nio.file.attribute.FileAttribute 0))
          covered (vals mounts)]
      (doseq [path (concat (keys (:files store)) (keys (:artifacts store)))
              :when (some #(str/starts-with? (str path) (str %)) covered)
              :let  [entry   (store/file-content store path)
                     content (or (:content entry)
                                 (when store-dir
                                   (:bytes (artifacts/fetch store-dir store path))))]
              :when (some? content)]
        (let [f (io/file (str out) (str path))]
          (io/make-parents f)
          (if (bytes? content)
            (io/copy content f)
            (spit f content))))
      (str out))))

(defn serve-code
  "The expression the app image evaluates to start serving `plan`, as a
  STRING — it crosses an nREPL wire, which carries text.

  This is where the directive actually lands: the app writes no `serve!`
  call, so slopp writes it, from the plan. Generating it rather than asking
  the app for it is what makes `serve-plan`'s derivations binding — a
  hand-written call could disagree with them, and the running server would be
  the one that disagreed.

  **It must carry what the app NEEDS, not only where to serve.** The first
  cut generated four of `serve!`'s options — namespaces, host, port, adapter
  — and dropped `:web/perform-ctx`, `:web/auth-config`, `:web/routes` and
  `:web/max-body-bytes`, which is every option describing the application
  rather than its address. Measured on a real app: handlers taking
  `:web/deps` received nil, which either 500s (loud) or answers 200 with an
  empty body (silent, and worse — a client generated against that surface
  comes back with zero endpoints and looks successful).

  `:web/perform-ctx` is a CALL to the app's `^{:web/context true}` builder,
  which is why the opts can no longer be one flat quoted map: the address
  fields stay quoted (a namespace symbol in evaluated position is read as a
  class name) and the context is evaluated.

  **The context is built ONCE per app image — and the app image is REPLACED
  at every refresh.** So state accumulated in it does not survive a `done`:
  an atom the builder creates is a new atom each time, and an app that keeps
  a registry there will find it empty after any done point, silently. Say so
  to anyone building one; the alternative — hot-loading a refresh into the
  RUNNING image instead of booting a new one — is the change that would fix
  it, and it is not made yet.

  Still dropped, deliberately rather than by oversight: `:web/auth-config`
  (an app with auth will hit this) and `:web/routes` (static mounts, which
  need the store's bytes and the child image has no store).

  **It evaluates to the BOUND port — an integer.** `repl/eval!` hands a throw
  back as a STRING, so an integer is unambiguous evidence a socket is open;
  anything else is the failure, in its own representation."
  [plan]
  (let [builder (:context-builder plan)
        mounts  (not-empty (:static plan))
        opts    (cond-> {:web/namespaces (list 'quote (vec (:namespaces plan)))
                         :web/host       (:host plan)
                         :web/port       (:port plan)
                         :web/adapter    (:adapter plan)}
                  (:max-body-bytes plan)
                  (assoc :web/max-body-bytes (:max-body-bytes plan))
                  builder
                  (assoc :web/perform-ctx (list builder))
                  mounts
                  (assoc :web/routes
                         (list 'slopp.web.static/mount-routes
                               mounts
                               (list 'slopp.web.static/file-or-resource-reader
                                     (:static-dir plan)))))]
    (pr-str (list* 'do
                   (list 'require ''slopp.web)
                   (concat
                    (when mounts
                      [(list 'require ''slopp.web.static)])
                    (when builder
                      [(list 'require (list 'quote (symbol (namespace builder))))])
                    [(list :port (list 'slopp.web/serve! opts))])))))

(defn- boot!
  "Bring up an app image for `store` and load its web surface into it —
  WITHOUT serving. `{:image :plan}`, or `{:reason …}` and no live process.

  Separable from serving because that is what makes a safe swap possible:
  `refresh!` must know the new version is good BEFORE it kills the one
  currently answering, and \"good\" at done grain means IT LOADS. A red
  half-written store is the ordinary mid-episode state, and it fails here,
  with the old server untouched.

  **Launched through `session/start-image!`, never `repl/start!`.** Its own
  docstring calls it \"THE door: every owned image is launched here\", and the
  door carries two things a local launch does not: `image-deps` (the store's
  manifest PLUS what the vendored framework requires) and `framework-dir!`
  (the materialized framework on the classpath). `image-with-deps!` records
  what a second door bought last time — \"a spare launched in its own dir has
  nothing vendored, and a JVM cannot pick up a relative classpath directory
  after launch\" — and this codebase has already paid for an unenumerated
  door twice.

  **Loaded with `load-ns-into!`, not `load-ns!`.** `currency/stamps` is one
  process-global atom describing THE ORACLE. Stamping this image's loads into
  it would report forms as current in a process the oracle never saw, which
  is the exact false green the registry exists to prevent.

  The image is stopped on every failing path: a child JVM that outlives the
  attempt to use it is the worst of both outcomes."
  [session store plan]
  (let [t0  (System/nanoTime)
        img (session/start-image! session store)]
    (try
      (if-let [err (first (keep (fn [n]
                                  (when-let [e (image/load-ns-into! img store n)]
                                    (str n ": " e)))
                                (load-order store)))]
        (do (repl/stop! img)
            {:reason (str "the app image could not load " err)})
        ;; the bytes are materialized HERE because this is the last place that
        ;; holds the store — `serve-in!` has only the plan, and the child
        ;; image has neither. A fresh dir per boot rather than a cached one:
        ;; the image is replaced at every refresh, so assets that changed
        ;; mid-episode would otherwise serve stale from a dir nobody rewrote.
        {:image img
         :plan  (assoc plan :static-dir (materialize-static! store (:static plan) (:dir @session)))
         :boot-ms (quot (- (System/nanoTime) t0) 1000000)})
      (catch Throwable t
        (repl/stop! img)
        {:reason (str "the app image did not come up: " (ex-message t))}))))

(defn- serve-in!
  "Bind the server inside an already-loaded app image (`boot!`'s result) and
  return the running map — `{:serving? true :image :plan :port :url}`, or
  `{:serving? false :reason …}` with the image stopped.

  The reported `:url` carries the port actually BOUND, which is what the
  generated call hands back, not the one that was asked for.

  A failure here is a BIND failure, and by construction it is the only kind
  left: `boot!` already proved the code loads. So the reason it reports is
  narrow enough to act on — something else holds the port."
  [{:keys [image plan boot-ms]}]
  ;; `:boot-ms` rides through rather than being measured here: hot-loading a
  ;; refresh would remove the BOOT and not the bind, so folding the two into
  ;; one number would make a contended port read as a slow image.
  (try
    (let [[v] (repl/eval! image (serve-code plan))]
      (if (integer? v)
        {:serving? true :image image :plan plan :port v :boot-ms boot-ms
         :url (str "http://" (:host plan) ":" v "/")}
        (do (repl/stop! image)
            {:serving? false :plan plan
             :reason (str "the app image would not serve on port "
                          (:port plan) ": " v)})))
    (catch Throwable t
      (repl/stop! image)
      {:serving? false :plan plan
       :reason (str "the app image would not serve on port " (:port plan)
                    ": " (ex-message t))})))

(defn start!
  "Bring this store's app server up in a DEDICATED image and return
  `{:serving? true :image :plan :port :url}` — or `{:serving? false :reason …}`.

  `dir` is the store's directory, and it is only ever hashed (`derived-port`).

  Boot-and-load (`boot!`) then bind (`serve-in!`), which is the same pair
  `refresh!` uses in a different order. One implementation between them is
  the point: a swap that booted differently from a start would be a second
  lifecycle, and the two would drift exactly where it is hardest to notice.

  **A failure is a SENTENCE, not a throw.** Nothing the caller can do about a
  taken port is expressed by a stack trace, and this runs from the dev
  lifecycle rather than from a user's call — a throw there takes down more
  than the app server.

  **A taken port is reported, not routed around** — see `derived-port` for
  why this diverges from the UI listener, which falls back to an ephemeral
  one. Reporting keeps the decision with the caller: this returns the fact,
  and a wiring layer that wants a fallback ladder can build one on top
  without this function having an opinion baked in."
  [session store dir]
  (let [plan (serve-plan store dir)]
    (if-not (:enabled? plan)
      {:serving? false :reason (:reason plan) :plan plan}
      (let [booted (boot! session store plan)]
        (if (:reason booted)
          (assoc booted :serving? false :plan plan)
          (serve-in! booted))))))

(defn ^:export stop!
  "Stop a running app server — whatever `start!` returned. Idempotent, and
  safe on a `{:serving? false …}` that never had an image.

  There is exactly ONE thing to kill, and that is the point of the dedicated
  image: the listener, the loaded namespaces and the process are the same
  object, so there is no half-stopped state where a port stays bound because
  a handle was dropped. `repl/stop!` already tolerates a partially-built
  handle and destroys the process before touching the transport."
  [running]
  (when-let [img (:image running)]
    (repl/stop! img))
  nil)

(defn ^:export refresh!
  "Re-serve `store` on this session's app server and return the running map.
  The version that was up is stopped only once the new one has PROVED it
  loads; the result is held on the session as `:app-server`.

  Called at DONE grain, not per write. Mid-episode the store is intentionally
  incomplete — a red test written before its implementation is the normal
  state, not a fault — and reloading a browser into that shows the author a
  broken app repeatedly and trains them to ignore it. `done` is the point
  someone says \"I think this is finished\", which is exactly when they want
  to look.

  **The swap is verified on LOADING, not on binding**, and the asymmetry is
  the design. A boot that fails at done grain almost always fails because the
  code does not compile, and that is decided before a socket is involved —
  so the check that protects the running app is cheap and happens first. A
  bind failure means a foreign process holds the port, which no ordering can
  prevent and which is reported instead.

  So a red store leaves the previous version answering and the session's
  `:app-server` untouched: \"always up\" and \"up to date\" only conflict when
  a boot fails, and this is the answer to that conflict. A red `done` still
  refreshes — `done` REPORTS rather than refuses and a red one STANDS, so
  \"finished\" and \"green\" are different questions, and seeing the app is
  part of how you find out you were not finished.

  The old image is stopped BEFORE the new one binds, because they want the
  same derived port. That is a real gap in service, and it is the price of a
  stable url — the alternative, binding the new one somewhere else first,
  keeps the app up under an address nobody was given."
  [session store dir]
  (let [plan (serve-plan store dir)]
    (if-not (:enabled? plan)
      {:serving? false :reason (:reason plan) :plan plan}
      (let [booted (boot! session store plan)]
        (if (:reason booted)
          (assoc booted :serving? false :plan plan)
          (do (stop! (:app-server @session))
              (let [now (serve-in! booted)]
                (swap! session assoc :app-server now)
                now)))))))
