(ns slopp.web.dispatch
  (:require [slopp.web.router :as router] [slopp.web.auth :as auth] [clojure.string :as str]))

(defn authorized?
  "Does `identity` ({:web/sub … :web/groups #{…}} or nil) satisfy `policy`?
  The :web/auth grammar: :public | :authenticated | [:group \"g\"] |
  [:any p…] | [:all p…]. A nil policy DENIES — runtime default-deny,
  matching the web-auth-refusal write gate. An EMPTY composite ([:all] /
  [:any] with no sub-policies) also DENIES: a conjunction over nothing is
  vacuously true, so [:all] would otherwise authorize everyone (review W1).
  Pure set logic; no macro."
  [policy identity]
  (cond
    (= :public policy) true
    (= :authenticated policy) (some? identity)
    (and (vector? policy) (= :group (first policy)))
    (contains? (:web/groups identity #{}) (second policy))
    (and (vector? policy) (= :any (first policy)))
    (boolean (some #(authorized? % identity) (rest policy)))
    (and (vector? policy) (= :all (first policy)))
    (and (seq (rest policy))
         (every? #(authorized? % identity) (rest policy)))
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
  app's read performers → the handler, with :path-params, :query-params
  (parsed from :query-string once, here, so no app writes its own
  splitter — and a declared read's path addresses it the same way, so
  [:query-params :view] works exactly like [:path-params :id]),
  :web/deps (the perform-ctx as a value) and the fetched :web/reads on
  the request → the
  response's :web/effects interpreted through the app's effect performers,
  BOUNDED by the route's declared :web/effects (a handler cannot emit a kind
  its route did not declare — the runtime half of web-unsafe-get /
  web-undeclared-effect, which see only the static handler body; review W4).
  Every failure is response DATA — an ex-info carrying :web/status maps to
  it and surfaces its message plus ONLY a :web/public allowlist; any other
  exception is a GENERIC 500 with the detail logged server-side, never in
  the body (review W3)."
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
                        ;; parsed ONCE here, so no app writes its own
                        ;; splitter over the :query-string the adapters carry
                        :query-params (router/query-params (:query-string req))
                        :web/deps (:web/perform-ctx ctx))
            fetch (fn [[alias [kind path]]]
                    (if-let [f (get (:web/read-performers ctx) kind)]
                      [alias (f (:web/perform-ctx ctx) (get-in req' path))]
                      (throw (ex-info (str "no performer for read kind " kind)
                                      {:web/read kind}))))
            declared (set (:web/effects row))
            resp (try
                   (let [reads (when-let [decl (:web/reads row)]
                                 (into {} (map fetch) decl))
                         resp  ((:handler row)
                                (cond-> req' reads (assoc :web/reads reads)))
                         effects (seq (:web/effects resp))
                         undeclared (seq (remove #(contains? declared (first %))
                                                 effects))]
                     (cond
                       ;; a kind the ROUTE never declared — the static gate
                       ;; can't see a handler that computes its effects, so
                       ;; the dispatcher refuses before running any of them
                       undeclared
                       {:status 500
                        :body {:error (str "endpoint emitted undeclared effect kind(s) "
                                           (str/join ", " (map first undeclared))
                                           " — declare them in :web/effects or drop them")}}

                       effects
                       (or (when-let [err (run-effects!
                                           (:web/effect-performers ctx)
                                           (:web/perform-ctx ctx) effects)]
                             {:status 500 :body err})
                           resp)

                       :else resp))
                   (catch Exception e
                     (let [data (ex-data e)]
                       (if-let [status (:web/status data)]
                         ;; a DELIBERATE boundary error: its message and only
                         ;; an explicit :web/public allowlist reach the client
                         {:status status
                          :body (cond-> {:error (ex-message e)}
                                  (contains? data :web/public)
                                  (assoc :data (:web/public data)))}
                         ;; anything else is unexpected — log the detail,
                         ;; return nothing that discloses internals
                         (do (.println System/err
                                       (str "slopp.web: unhandled "
                                            (.getName (class e)) " — "
                                            (ex-message e)))
                             {:status 500 :body {:error "internal server error"}})))))]
        resp))))

(defn bounded-body-string
  "Read up to `max-bytes` from InputStream `in` as a UTF-8 string. Returns
  {:body s-or-nil} within the cap, or {:too-large true} the moment the
  stream exceeds it — the request-body DoS guard both adapters share
  (review W8: an unbounded slurp is bounded only by heap). A nil stream is
  an empty body."
  [in max-bytes]
  (if (nil? in)
    {:body nil}
    (let [buf (java.io.ByteArrayOutputStream.)
          arr (byte-array 8192)
          lim (long max-bytes)]
      (with-open [^java.io.InputStream in in]
        (loop []
          (let [n (.read in arr)]
            (cond
              (neg? n) {:body (when (pos? (.size buf))
                                (String. (.toByteArray buf) "UTF-8"))}
              (> (+ (.size buf) n) lim) {:too-large true}
              :else (do (.write buf arr 0 n) (recur)))))))))
