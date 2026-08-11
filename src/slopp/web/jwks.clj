(ns slopp.web.jwks
  "The OIDC key-fetch ADAPTER: the two HTTP GETs that turn an issuer URL into
  its JWK set.

  It exists as its own namespace because `slopp.web.auth` is otherwise POLICY
  and should be able to say so. Everything there takes what it needs as data —
  `verify-jwt` receives `:jwks`, `now` and `getenv` arrive as injected seams,
  the tests pass static keys — and this one form left the process, while the
  namespace was declared `:internal`, which asserts that nothing in it does.
  Nothing caught that, because the call is made through a class rather than a
  var; `index.derive/external-interop-vars` is what made it visible.

  The seam this leaves behind is the one that already existed: the server
  wiring calls this ONCE at startup and hands the result back as the
  `:auth/oidc` config's `:jwks`, so every request afterwards verifies against a
  key set it was given rather than one it fetched. That is why the split costs
  nothing at the call sites — the boundary was already in the right place, only
  the filing was wrong."
  (:require [cheshire.core :as json] [slopp.web.client :as web.client]))

(defn ^:export fetch-jwks!
  "Fetch the issuer's signing keys: GET
  <issuer>/.well-known/openid-configuration → its jwks_uri → the JWK set's
  :keys. The SERVER wiring calls this once at startup when :oidc is
  enabled and passes the result as the config's `:jwks`; tests inject
  static keys instead. Throws on network/parse failure — a misconfigured
  issuer should fail loudly at startup, not 401 mysteriously forever.

  `requester` is the transport — [[slopp.web.client/request]] by default. What
  is worth testing here is the TWO HOPS: that the second url is read out of the
  first document rather than guessed. That is logic, it needs no socket, and it
  used to need one anyway.

  A non-200 now throws with the status in it. It used to parse whatever came
  back regardless of status, so a 404 carrying a JSON error body yielded a map
  with no `:jwks_uri`, and the second hop then requested the EMPTY string —
  failing as \"URI with undefined scheme\", three layers from the mistake and
  naming neither the issuer nor the 404. \"Fail loudly at startup\" was already
  the stated intent; this is the first version that does it."
  ([issuer] (fetch-jwks! issuer web.client/request))
  ([issuer requester]
   (let [GET   (fn [url]
                 (let [{:http/keys [status body]}
                       (requester {:http/method :get :http/url (str url)})]
                   (when-not (= 200 status)
                     (throw (ex-info (str "jwks fetch failed: HTTP " status
                                          " from " url)
                                     {:url (str url) :status status})))
                   (json/parse-string body true)))
         disco (GET (str issuer "/.well-known/openid-configuration"))]
     (vec (:keys (GET (:jwks_uri disco)))))))
