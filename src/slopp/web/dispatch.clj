(ns slopp.web.dispatch
  (:require [slopp.web.router :as router] [slopp.web.auth :as auth]))

(defn authorized?
  "Does `identity` ({:web/sub … :web/groups #{…}} or nil) satisfy `policy`?
  The :web/auth grammar: :public | :authenticated | [:group \"g\"] |
  [:any p…] | [:all p…]. A nil policy DENIES — runtime default-deny,
  matching the web-auth-refusal write gate. Pure set logic; no macro."
  [policy identity]
  (cond
    (= :public policy) true
    (= :authenticated policy) (some? identity)
    (and (vector? policy) (= :group (first policy)))
    (contains? (:web/groups identity #{}) (second policy))
    (and (vector? policy) (= :any (first policy)))
    (boolean (some #(authorized? % identity) (rest policy)))
    (and (vector? policy) (= :all (first policy)))
    (every? #(authorized? % identity) (rest policy))
    :else false))

(defn- run-effects!
  "Interpret a response's `:web/effects` ([[kind & args] …]) through
  `performers` ({kind → f}): every kind is validated BEFORE any effect
  runs (all-or-nothing precheck — a typo'd kind must not leave a partial
  write), then each performs in declared order as
  (f perform-ctx & args). Returns nil, or {:error …} naming the alien
  kind."
  [performers perform-ctx effects]
  (if-let [alien (some (fn [[kind]] (when-not (contains? performers kind) kind))
                       effects)]
    {:error (str "no performer for effect kind " alien
                 " — the dispatcher only runs kinds a ^{:web/effect <kind>}"
                 " form provides")}
    (do (doseq [[kind & args] effects]
          (apply (get performers kind) perform-ctx args))
        nil)))

(defn handle!
  "The whole request pipeline, callable in-process — request map in,
  response map out; the socket is an adapter's concern. `ctx`:
  {:web/routes [rows] :web/read-performers {kind→f}
   :web/effect-performers {kind→f} :web/perform-ctx <passed to performers>
   :web/auth-config <the provider config identity resolves through>}.

  Order is the guarantee: IDENTITY (resolved through :web/auth-config when
  the request carries none — a pre-resolved :web/identity is respected) →
  ROUTE (404) → POLICY (401 unauthenticated / 403 unauthorized — the
  handler is unreachable un-checked) → declared :web/reads fetched via the
  app's read performers → the handler, with :path-params, :web/deps (the
  perform-ctx as a value) and the fetched :web/reads on the request → the
  response's :web/effects interpreted through the app's effect performers
  (validated all-or-nothing). Every failure is response DATA — an ex-info
  carrying :web/status maps to it; anything else is a 500."
  [ctx req]
  (let [req (if (or (:web/identity req) (nil? (:web/auth-config ctx)))
              req
              (assoc req :web/identity
                     (auth/resolve-identity (:web/auth-config ctx) req)))
        row (router/match (:web/routes ctx)
                          (:request-method req) (:uri req))]
    (cond
      (nil? row)
      {:status 404 :body {:error "no route"}}

      (not (authorized? (:auth row) (:web/identity req)))
      (if (:web/identity req)
        {:status 403 :body {:error "forbidden"}}
        {:status 401 :body {:error "unauthenticated"}})

      :else
      (let [req' (assoc req :path-params (:path-params row)
                        :web/deps (:web/perform-ctx ctx))
            fetch (fn [[alias [kind path]]]
                    (if-let [f (get (:web/read-performers ctx) kind)]
                      [alias (f (:web/perform-ctx ctx) (get-in req' path))]
                      (throw (ex-info (str "no performer for read kind " kind)
                                      {:web/read kind}))))
            resp (try
                   (let [reads (when-let [decl (:web/reads row)]
                                 (into {} (map fetch) decl))
                         resp  ((:handler row)
                                (cond-> req' reads (assoc :web/reads reads)))]
                     (or (when-let [effects (seq (:web/effects resp))]
                           (when-let [err (run-effects!
                                           (:web/effect-performers ctx)
                                           (:web/perform-ctx ctx) effects)]
                             {:status 500 :body err}))
                         resp))
                   (catch Exception e
                     {:status (or (:web/status (ex-data e)) 500)
                      :body {:error (ex-message e)
                             :data (not-empty (dissoc (ex-data e) :web/status))}}))]
        resp))))
