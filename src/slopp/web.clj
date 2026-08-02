(ns slopp.web
  "The ONE namespace a web app requires — everything else under `slopp.web`
  is reached through here.

  Six functions, and the split between them is the point:

  - **`context` and `serve!`** assemble an app from its NAMESPACES. The route
    table and both performer vocabularies derive from var METADATA
    (`:web/path`, `:web/read`, `:web/effect`), so an app is declared where its
    code is rather than in a table that drifts from it. `serve!` is `context`
    plus a socket; `context` alone is what a test uses.
  - **`handle!`** runs a whole request with no socket anywhere — request map
    in, response map out. An app's tests live here, not against a port.
  - **`enforce` and `authorized?`** are the two shapes of row-level permission
    that route policy cannot express: refuse, or branch.

  The recurring difficulty this namespace exists to manage is that **assembly
  is where a web app fails silently.** Performers resolve by VOCABULARY
  store-wide, so a `:web/namespaces` list missing half the app assembles
  happily and then answers 500 — not 404 — at request time, with the detail
  server-side. That is the worst pairing available: the failure with no check
  is also the hardest one to read from outside. So `context` refuses an
  incomplete context up front; it costs a set difference and it is the only
  place holding everything the check needs.

  This module reaches back into NOTHING else in slopp — pinned by
  `slopp.modules-test/the-web-framework-never-reaches-back-into-slopp` — which
  is what lets `build.clj` ship it as the standalone
  `io.github.nvoxland/slopp-web` jar. A require of `slopp.store` from anywhere
  under here would pass every test in this repo and break at a USER's require
  time."
  (:require [slopp.web.routes :as routes]
            [slopp.web.dispatch :as dispatch]
            [slopp.web.server.jdk :as jdk] [slopp.web.server.httpkit :as httpkit] [clojure.string :as str]))

(defn enforce
  "In-handler guard for what route policy can't see (row-level authz: is
  this the owner?): a falsey `ok?` throws ex-info carrying {:web/status
  403}, which the dispatcher maps to the 403 response. Returns true when
  ok. Deliberately NOT bang-named — a throw mutates nothing, so handlers
  using it stay analyzer-pure."
  ([ok?] (enforce ok? "forbidden"))
  ([ok? msg]
   (when-not ok?
     (throw (ex-info (str msg) {:web/status 403})))
   true))

(defn authorized?
  "Does the resolved identity satisfy the `:web/auth` policy? The boolean
  twin of `enforce`, for handlers that BRANCH on permission rather than
  refuse (`dispatch/authorized?` — :public | :authenticated |
  [:group \"g\"] | [:any …] | [:all …])."
  [policy identity]
  (dispatch/authorized? policy identity))

(defn ^{:malli/schema [:=> {:throws [[:map
                       [:web/missing-performers [:vector :string]]
                       [:web/namespaces [:vector :symbol]]]]} [:cat [:map
                                  [:web/namespaces [:sequential :symbol]]
                                  [:web/routes {:optional true} [:vector :map]]
                                  [:web/perform-ctx {:optional true} :any]
                                  [:web/max-body-bytes {:optional true} :int]
                                  [:web/auth-config {:optional true} [:maybe :map]]]]
                       [:map
                        [:web/routes [:vector :map]]
                        [:web/read-performers :map]
                        [:web/effect-performers :map]]]}
  context
  "Assemble the dispatch context from `{:web/namespaces [ns-syms]
  :web/routes [extra rows — static mounts, programmatic routes]
  :web/perform-ctx <passed to every performer>
  :web/max-body-bytes <request-body cap, default 1 MiB — the web.max-body-bytes
  capability an app threads in>
  :web/auth-config <the provider config identity resolves through>}`: the
  route table and both performer vocabularies derive from the namespaces'
  VAR metadata — the same contract the store gates enforced at write time —
  with the explicit rows appended.

  REFUSES a context whose routes declare reads or effects no performer here
  can serve. Reads resolve by VOCABULARY store-wide, so an endpoint in one
  namespace legitimately reuses a performer declared in another — which means
  a `:web/namespaces` list missing half the app assembles happily and answers
  **500, not 404**, at request time, with the detail server-side and a
  generic error in the body. That is the worst pairing available: the failure
  with no check is also the one that is hardest to read from the outside.

  Everything the check needs is already in hand here, so it costs a set
  difference. Found by dogfooding: adding an `/api` namespace to this repo's
  own reviewer UI hit it immediately, because the endpoints and their read
  performers live in different namespaces on purpose."
  [{:web/keys [namespaces routes perform-ctx max-body-bytes auth-config]}]
  (let [ctx (cond-> {:web/routes (into (routes/from-namespaces namespaces) routes)
                     :web/read-performers (routes/performers-from-namespaces namespaces :web/read)
                     :web/effect-performers (routes/performers-from-namespaces namespaces :web/effect)
                     :web/perform-ctx perform-ctx
                     :web/max-body-bytes (or max-body-bytes 1048576)}
              auth-config (assoc :web/auth-config auth-config))
        missing (for [row (:web/routes ctx)
                      [decl performers] [[:web/reads (:web/read-performers ctx)]
                                         [:web/effects (:web/effect-performers ctx)]]
                      kind (let [d (get row decl)]
                             ;; :web/reads is {key [kind & path]}; :web/effects
                             ;; is a plain collection of kinds
                             (if (map? d) (map (comp first val) d) (seq d)))
                      :when (not (contains? performers kind))]
                  (str kind " (" (:method row) " " (:path row) ")"))]
    (when (seq missing)
      (throw (ex-info (str "this context declares "
                           (if (next missing) "kinds that no performer" "a kind that no performer")
                           " in :web/namespaces can serve: "
                           (str/join ", " (distinct missing))
                           " — a route whose performer is missing answers 500, not 404,"
                           " so the namespace list is checked here rather than at request time")
                      {:web/missing-performers (vec (distinct missing))
                       :web/namespaces (vec namespaces)})))
    ctx))

(defn handle!
  "Run one request through the FULL pipeline — route, policy, declared
  reads, handler, effect interpretation — with no socket anywhere: request
  map in, response map out. The app-test surface (`dispatch/handle!`)."
  [ctx req]
  (dispatch/handle! ctx req))

(defn bind-diagnosis
  "The leading sentence for a failed bind on `port`, or nil when `failure`
  is not a port clash. `failure` is either a Throwable or the TEXT one left
  behind after crossing a wire.

  **The diagnosis only, never the next step.** What to do about a taken port
  is not the framework's to say: slopp's dev server knows the answer is
  `web.port`, an operator running a built jar set the port some other way,
  and inventing advice for them would be a confident wrong sentence. So this
  writes the half every caller shares and each caller appends its own.

  **Both representations, because the callers genuinely hold different
  things.** In-process the failure is an exception, and http-kit wraps it, so
  the cause chain is walked. The dev server's failure comes back from a child
  image over nREPL as printed text with the class name already stringified —
  there is no Throwable left to interrogate. Two ways in, one answer out;
  before this there were three answers, phrased three ways, and \"the port is
  taken\" read differently depending on which listener you asked.

  **Anything unrecognized returns nil and the caller keeps every byte.** A
  privileged port, an unresolvable host, something not thought of — squeezing
  those into the shape of the case that IS understood is how a confident wrong
  sentence replaces a verbose right one."
  [port failure]
  (let [taken? (cond
                 (nil? failure) false
                 (instance? Throwable failure)
                 (loop [t failure]
                   (cond (nil? t) false
                         (instance? java.net.BindException t) true
                         :else (recur (.getCause ^Throwable t))))
                 :else (boolean (re-find #"(?i)address already in use" (str failure))))]
    (when taken?
      (str "port " port " is already in use"))))

(defn ^{:malli/schema [:=> {:throws [[:map
                       [:web/missing-performers [:vector :string]]
                       [:web/namespaces [:vector :symbol]]]
                      [:map
                       [:web/port :int]]]} [:cat [:map
                                  [:web/namespaces [:sequential :symbol]]
                                  [:web/adapter {:optional true} :keyword]
                                  [:web/host {:optional true} :string]
                                  [:web/port {:optional true} :int]
                                  [:web/perform-ctx {:optional true} :any]
                                  [:web/auth-config {:optional true} [:maybe :map]]]]
                       :map]}
  serve!
  "Assemble `context` from the opts and serve it: `{:web/namespaces […]
  :web/adapter :http-kit|:jdk :web/host \"127.0.0.1\" :web/port 8080
  :web/perform-ctx …}`. :http-kit is the production default (D-web §9);
  :jdk is the zero-dep fallback. Returns the adapter's handle
  (+ :web/adapter) for `stop!`. The adapter is a VALUE — the seam that
  keeps the server library choice a config key, not a rewrite.

  **A taken port THROWS, and the diagnosis leads.** It is never routed around
  — an address someone was handed must not quietly become a different one, so
  there is no port hunt here in production any more than in development. What
  changed is only what the operator reads: [[bind-diagnosis]]'s sentence
  first, the adapter's raw failure preserved behind it, and `:web/port` in the
  ex-data so a caller acts on the number rather than re-parsing the sentence."
  [{:web/keys [adapter host port] :or {adapter :http-kit host "127.0.0.1" port 8080}
    :as opts}]
  (let [ctx (context opts)]
    (try
      (case adapter
        :http-kit (assoc (httpkit/start! ctx {:host host :port port})
                         :web/adapter :http-kit)
        :jdk (assoc (jdk/start! ctx {:host host :port port})
                    :web/adapter :jdk))
      (catch Throwable t
        (if-let [d (bind-diagnosis port t)]
          (throw (ex-info (str d "\n" (ex-message t)) {:web/port port} t))
          (throw t))))))

(defn stop!
  "Stop a `serve!` return."
  [srv]
  (case (:web/adapter srv)
    :http-kit (httpkit/stop! srv)
    :jdk (jdk/stop! srv)))
