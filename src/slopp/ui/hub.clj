(ns slopp.ui.hub
  "The slopp UI hub: one process per machine that a human starts, points a
  browser at, and switches projects inside.

  It exists because the reviewer UI used to come up with every MCP server on
  one fixed port, which works for exactly one project and then either
  collides or scatters across ports nobody can guess (D-ui-hub).

  What it is NOT is the thing that answers questions about a store. Not
  because it could not read one — warranty and observed examples are persisted
  — but because reading it well means being the process that OWNS it: the live
  session ahead of the last snapshot, the booted image, that project's
  classpath and version. A hub answering for N stores would need N of each. Every project keeps serving its own pages and its own `/api/*`
  from its own live session; this process holds a registry fed by heartbeats,
  renders a picker, and PROXIES. It never opens a db and never renders a
  form, which is also what lets a hub front projects from other releases.

  `slopp.ui.registry` owns the registry as a value; this namespace owns the
  atom, the routes and the wire."
  (:require [clojure.string :as str]
            [slopp.api.capabilities :as caps]
            [slopp.store :as store]
            [slopp.ui.registry :as reg]
            [slopp.ui.contracts :as contracts]
            [slopp.ui.views :as views]
            [slopp.web :as web]
            [slopp.web.html :as html]))

(defn- wire-row
  "A registry entry as [[contracts/project-row]].

  `:available?` becomes `:available` and every optional key is present-and-nil
  rather than absent, because the wire is JSON: a contract that let a key go
  missing would make \"this project never told us its version\" and \"this
  project is from an older release\" render as the same nothing."
  [{:keys [slug dir url available? last-seen version status] nm :name}]
  {:slug      (str slug)
   :name      (str nm)
   :dir       (str dir)
   :url       (str url)
   :available (boolean available?)
   :last-seen (long last-seen)
   :version   version
   :status    status})

(defn ^{:web/method :get :web/path "/api/projects" :web/auth :public
        :web/client false
        :web/response contracts/project-list}
  projects
  "GET /api/projects — every project that has checked in, stale ones included
  and flagged.

  Same origin as the proxied project pages, which is the point: a project's
  own top-nav dropdown fetches THIS to list its neighbours, with no CORS and
  no knowledge of where the hub lives. Served directly (no hub in front), the
  fetch 404s and the dropdown degrades to the one project you are looking at."
  [req]
  {:status 200
   :body  (mapv wire-row (reg/projects @(:registry (:web/deps req))
                                       (System/currentTimeMillis)))})

(defn ^{:web/method :post :web/path "/api/register" :web/auth :public
        :web/effectful true :web/client false
        :web/request contracts/project-beat
        :web/response [:map [:slug :string] [:beat-ms :int]]}
  register!
  "POST /api/register — one project's check-in, which both registers it and
  keeps it alive (D-ui-hub).

  The REPLY carries the slug the hub assigned and the interval it expects, so
  a project holds no second copy of either: change `beat-ms` here and every
  running project follows within one beat. It also means a project learns its
  own public address, which is the only way it can render a link to itself.

  `:web/auth :public` because a hub binds loopback and its whole job is to
  accept check-ins from processes that have no credentials to offer. The
  write it performs is bounded — an entry in an in-memory map, keyed by a dir
  the caller already controls."
  [req]
  (let [registry (:registry (:web/deps req))
        {:keys [dir]} (:body req)
        updated  (swap! registry reg/beat (:body req) (System/currentTimeMillis))]
    {:status 200
     :body   {:slug (str (:slug (get updated dir)))
              :beat-ms reg/beat-ms}}))

(defn ^{:web/method :post :web/path "/api/deregister" :web/auth :public
        :web/effectful true :web/client false
        :web/request [:map [:dir :string]]
        :web/response [:map [:dropped :boolean]]}
  deregister!
  "POST /api/deregister — a project saying goodbye on clean shutdown.

  The FAST path only. Staleness is the guarantee: a process killed, crashed
  or suspended never gets here, and the hub has to be right about it anyway."
  [req]
  (let [registry (:registry (:web/deps req))
        dir      (:dir (:body req))
        had?     (contains? @registry dir)]
    (swap! registry reg/forget dir)
    {:status 200 :body {:dropped had?}}))

(defn- unavailable
  "The response for a project that cannot be reached: `status` (404 when the
  slug is unknown, 503 when it is registered but silent) and the page saying
  which and why."
  [status entry]
  (html/html-response
   (html/page {:html/title "slopp"} (views/project-unavailable entry))
   {:status status}))

^:reads
(defn- forward
  "Fetch `path` (+ `query`) from a registered project and answer with its
  bytes VERBATIM — status, content type and body.

  `^:reads`, not a bang: proxying a GET performs IO and changes nothing, at
  the hub or at the project. That distinction is load-bearing here, because
  the safe-method gate refuses a GET endpoint that reaches a mutation and it
  is right to.

  `:web/raw`, so nothing re-encodes what came back: the hub proxies a
  ClojureScript bundle, an SVG module map and a JSON payload with the same
  code, because it understands none of them. That is the whole stance — every
  screen belongs to the project, and the hub is a pipe (D-ui-hub).

  A refused connection lands on the same page as a stale registration.
  Between the hub's last heartbeat and this request the process may simply
  have gone, and telling the human two different stories about one situation
  helps nobody."
  [entry path query base]
  (let [root (str/replace (str (:url entry)) #"/+$" "")
        uri  (str root "/" path (when (seq query) (str "?" query)))]
    (try
      (let [client (java.net.http.HttpClient/newHttpClient)
            resp   (.send client
                          (-> (java.net.http.HttpRequest/newBuilder)
                              (.uri (java.net.URI. uri))
                              ;; the project emits root-absolute urls; without
                              ;; this every one of them would resolve HERE
                              ;; instead of at the project (D-ui-hub part 2)
                              (.header "X-Slopp-Base" base)
                              (.GET)
                              (.build))
                          (java.net.http.HttpResponse$BodyHandlers/ofByteArray))
            ctype  (-> (.headers resp) (.firstValue "content-type")
                       (.orElse "application/octet-stream"))]
        {:status (.statusCode resp)
         :web/raw true
         :headers {"Content-Type" ctype}
         :body (.body resp)})
      (catch java.io.IOException _
        (unavailable 503 (assoc entry :known? true))))))

^:reads
(defn- proxy-request
  "Resolve `<slug>` and forward the request, or answer the page that says why
  not: 404 when nobody holds the slug, 503 when the project is registered but
  has stopped beating."
  [req]
  (let [{:keys [slug path]} (:path-params req)
        entry (reg/find-slug @(:registry (:web/deps req)) slug)]
    (cond
      (nil? entry)
      (unavailable 404 {:slug slug :known? false})

      (not (reg/available? entry (System/currentTimeMillis)))
      (unavailable 503 (assoc entry :known? true))

      :else
      (forward entry (or path "") (:query-string req) (str "/p/" slug)))))

(defn- picker-response
  "The landing page: every project that has checked in, linked.

  This is the address a human remembers, and the reason the hub exists — one
  well-known port per machine instead of one per project, so \"where do I
  look\" has an answer that does not depend on which store you opened first."
  [registry]
  (html/html-response
   (html/page {:html/title "slopp"}
              (views/hub-picker
               (mapv wire-row (reg/projects @registry (System/currentTimeMillis)))))))

(defn hub-routes!
  "The one row the hub serves that cannot be a declared endpoint: its landing
  page.

  `/` is a ROW because `slopp.ui.pages/app-document` already owns that path in
  this store's route table — rightly, since that one is the PROJECT's
  document. The hub is a second application living in the same store, and the
  route-integrity gate is correct that one store cannot declare `/` twice.

  Everything else the hub serves IS declared, including the proxy, because a
  route hidden in a programmatic row is invisible to every integrity check
  slopp has — which is how the picker's own `/p/…` links first read as
  dangling."
  [registry]
  [{:method :get :path "/" :auth :public
    :handler (fn [_req] (picker-response registry))}])

(defn ^:export default-port
  "The port a hub binds and a project beats to when nobody says otherwise.

  Read from the capability registry rather than written here, because the two
  halves have to agree and they are configured from opposite ends: a project
  reads `ui.hub-port` out of its own store, and the hub CLI has no store to
  read at all. One declaration, two consumers."
  []
  (caps/effective (store/empty-store) "ui.hub-port"))

(defn ^:export serve!
  "Start a hub on `port` and return `{:server :registry :port :url}`.

  The registry rides in `:web/perform-ctx` as a VALUE the handlers receive,
  not as ambient state — so a test drives the same endpoints with its own
  atom, and two hubs in one JVM (which the tests do) never see each other's
  projects."
  [port]
  (let [registry (atom {})
        srv      (web/serve! {:web/namespaces ['slopp.ui.hub]
                              :web/routes    (hub-routes! registry)
                              :web/host      "127.0.0.1"
                              :web/port      port
                              :web/perform-ctx {:registry registry}})
        p        (:port srv)]
    {:server srv :registry registry :port p
     :url (str "http://127.0.0.1:" p "/")}))

(defn ^:export stop!
  "Stop a hub started by [[serve!]]; returns nil."
  [hub]
  (some-> (:server hub) web/stop!)
  nil)

(defn -main
  "CLI: serve the UI hub and block.

    slopp --main slopp.ui.hub/-main --port 7359
    java -jar slopp.jar --main slopp.ui.hub/-main

  Needs NO store and no directory — it never opens one, which is what lets a
  single hub front every project on the machine and outlive any of them.
  `slopp.boot` hands a bare invocation the dir as its only argument (the
  server convention), so an argument that is not `--port` is ignored rather
  than refused.

  Without `--port` it binds the `ui.hub-port` default, which is the same
  number a project beats to when its store says nothing — so starting the hub
  and running a project both work with no configuration at all."
  [& args]
  (let [port (or (some-> (second (drop-while #(not= "--port" %) args)) parse-long)
                 (default-port))
        {:keys [url]} (serve! port)]
    (println (str "slopp UI hub: " url))
    (println "projects register themselves within a few seconds of starting")
    @(promise)))

(defn ^{:web/method :get :web/path "/p/:slug" :web/auth :public
        :web/client false :web/response :any}
  project-root
  "GET /p/<slug> — a project's own root, proxied.

  Its own endpoint because the router's trailing catch-all needs at least one
  segment: `/p/:slug/*path` alone would 404 `/p/slopp2`, which is precisely
  the href the picker emits and the first link anyone clicks.

  `:web/response :any` is the honest declaration. The body is whatever the
  project sent — a document, a bundle, an SVG, JSON — and the hub neither
  owns that shape nor wishes to."
  [req]
  (proxy-request req))

(defn ^{:web/method :get :web/path "/p/:slug/*path" :web/auth :public
        :web/client false :web/response :any}
  project-path
  "GET /p/<slug>/… — everything beneath a project's root, proxied.

  GET only, deliberately. Every screen in the reviewer UI is a read, and a
  proxy that forwarded writes would be one for the store's own editing
  surface — a far larger promise than a browsable UI needs to make."
  [req]
  (proxy-request req))
