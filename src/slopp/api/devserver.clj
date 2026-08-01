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
            [slopp.api.web :as web] [slopp.store :as store]))

(defn derived-port
  "A localhost port DERIVED from the store dir for this project's APP server —
  stable across restarts, different for every project on the machine.

  SALTED, and the salt is load-bearing rather than decorative. This is the
  THIRD derivation of this shape (`review.server/derived-port` for the UI
  listener, `git.server/derived-port` for the git listener), and
  `review.server/derived-port` already records why they must not share a
  formula: one MCP process binds all of them, so a shared formula would have
  every project \"reliably colliding with itself\".

  A preference, not a guarantee — a taken port falls back at bind time and
  the reported url carries whatever was actually bound."
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
