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
            [slopp.api.web :as web] [slopp.store :as store] [slopp.api.session :as session] [slopp.image :as image] [slopp.image.repl :as repl]))

(defn derived-port
  "A localhost port DERIVED from the store dir for this project's APP server —
  stable across restarts, different for every project on the machine.

  SALTED, and the salt is load-bearing rather than decorative. This is the
  THIRD derivation of this shape (`review.server/derived-port` for the UI
  listener, `git.server/derived-port` for the git listener), and
  `review.server/derived-port` already records why they must not share a
  formula: one MCP process binds all of them, so a shared formula would have
  every project \"reliably colliding with itself\".

  A PREFERENCE, and one that can be refused: `start!` reports a taken port as
  a sentence rather than moving to another one. Stability is the whole reason
  to derive rather than default, so a port that silently relocates would give
  up the property it exists for — `ui_serve` reached the same conclusion, that
  \"a url you were handed should not quietly stop being the url that works\".
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
  second project. Same conclusion `ui.port` reached, phrased in its own doc
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
     :adapter    (capabilities/effective store "http.adapter")}))

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
  (let [seeds (cond-> (set (web/serving-namespaces store))
                (contains? (:namespaces store) 'slopp.web) (conj 'slopp.web))
        want  (into #{} (mapcat #(store/ns-closure store %)) seeds)]
    (filterv want (store/ns-dependency-order store))))

(defn serve-code
  "The expression the app image evaluates to start serving `plan`, as a
  STRING — it crosses an nREPL wire, which carries text.

  This is where the directive actually lands: the app writes no `serve!`
  call, so slopp writes it, from the plan. Generating it rather than asking
  the app for it is what makes `serve-plan`'s derivations binding — a
  hand-written call in the app could disagree with them, and the running
  server would be the one that disagreed.

  **The opts are QUOTED.** Generating a form puts every value in EVALUATED
  position, and a namespace symbol there is read as a class name: unquoted,
  `demo.app` came back from a real app image as `Syntax error
  (ClassNotFoundException) … demo.app`, thrown from inside a serve call that
  read as correct. The whole map is quoted rather than just the namespace
  vector, so a field added later inherits the fix instead of re-finding it.

  **The `require` is unconditional, so the two framework suppliers are one
  call.** slopp's own store CONTAINS `slopp.web`, so `load-order` loaded it
  and `*loaded-libs*` is marked — the require is a no-op. An ordinary app
  gets the framework vendored onto the child's classpath instead, where the
  require is the load. Branching on which would make the app declare a thing
  it cannot know.

  **It evaluates to the BOUND port — an integer.** `repl/eval!` hands a
  throw back as a STRING, so an integer result is unambiguous evidence that
  a socket is open; anything else is the failure, in its own representation.
  A serve call returning nil on both paths is how a dead app server reads as
  a live one, which is Core 1 at the transport."
  [plan]
  (pr-str (list 'do
                (list 'require ''slopp.web)
                (list :port (list 'slopp.web/serve!
                                  (list 'quote
                                        {:web/namespaces (vec (:namespaces plan))
                                         :web/host       (:host plan)
                                         :web/port       (:port plan)
                                         :web/adapter    (:adapter plan)}))))))

(defn start!
  "Bring this store's app server up in a DEDICATED image and return
  `{:serving? true :image :plan :port :url}` — or `{:serving? false :reason …}`.

  `dir` is the store's directory, and it is only ever hashed (`derived-port`).

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

  **A failure is a SENTENCE, not a throw.** Nothing the caller can do about a
  taken port is expressed by a stack trace, and this runs from the dev
  lifecycle rather than from a user's call — a throw there takes down more
  than the app server. The image is stopped on every failing path, because a
  child JVM that outlives the attempt to use it is the worst of both.

  **A taken port is reported, not routed around.** `ui_serve` settled this
  for slopp's other listener: \"a url you were handed should not quietly stop
  being the url that works.\" A derived port is salted per project, so the
  common collision is with our OWN previous server — which `stop!` handles —
  and silently moving to an ephemeral port would trade a named failure for a
  url that changes under whoever is watching it."
  [session store dir]
  (let [plan (serve-plan store dir)]
    (if-not (:enabled? plan)
      {:serving? false :reason (:reason plan) :plan plan}
      (let [img (session/start-image! session store)]
        (try
          (if-let [err (first (keep (fn [n]
                                      (when-let [e (image/load-ns-into! img store n)]
                                        (str n ": " e)))
                                    (load-order store)))]
            (do (repl/stop! img)
                {:serving? false :plan plan
                 :reason (str "the app image could not load " err)})
            (let [[v] (repl/eval! img (serve-code plan))]
              (if (integer? v)
                {:serving? true :image img :plan plan :port v
                 :url (str "http://" (:host plan) ":" v "/")}
                (do (repl/stop! img)
                    {:serving? false :plan plan
                     :reason (str "the app image would not serve on port "
                                  (:port plan) ": " v)}))))
          (catch Throwable t
            (repl/stop! img)
            {:serving? false :plan plan
             :reason (str "the app image did not come up: " (ex-message t))}))))))

(defn stop!
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
