(ns slopp.web
  (:require [slopp.web.routes :as routes]
            [slopp.web.dispatch :as dispatch]
            [slopp.web.server.jdk :as jdk] [slopp.web.server.httpkit :as httpkit]))

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

(defn ^{:malli/schema [:=> [:cat [:map
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
  :web/max-body-bytes <request-body cap, default 1 MiB — the http.max-body-bytes
  capability an app threads in>
  :web/auth-config <the provider config identity resolves through>}`: the
  route table and both performer vocabularies derive from the namespaces'
  VAR metadata — the same contract the store gates enforced at write time —
  with the explicit rows appended."
  [{:web/keys [namespaces routes perform-ctx max-body-bytes auth-config]}]
  (cond-> {:web/routes (into (routes/from-namespaces namespaces) routes)
           :web/read-performers (routes/performers-from-namespaces namespaces :web/read)
           :web/effect-performers (routes/performers-from-namespaces namespaces :web/effect)
           :web/perform-ctx perform-ctx
           :web/max-body-bytes (or max-body-bytes 1048576)}
    auth-config (assoc :web/auth-config auth-config)))

(defn handle!
  "Run one request through the FULL pipeline — route, policy, declared
  reads, handler, effect interpretation — with no socket anywhere: request
  map in, response map out. The app-test surface (`dispatch/handle!`)."
  [ctx req]
  (dispatch/handle! ctx req))

(defn ^{:malli/schema [:=> [:cat [:map
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
  keeps the server library choice a config key, not a rewrite."
  [{:web/keys [adapter host port] :or {adapter :http-kit host "127.0.0.1" port 8080}
    :as opts}]
  (let [ctx (context opts)]
    (case adapter
      :http-kit (assoc (httpkit/start! ctx {:host host :port port})
                       :web/adapter :http-kit)
      :jdk (assoc (jdk/start! ctx {:host host :port port})
                  :web/adapter :jdk))))

(defn stop!
  "Stop a `serve!` return."
  [srv]
  (case (:web/adapter srv)
    :http-kit (httpkit/stop! srv)
    :jdk (jdk/stop! srv)))
