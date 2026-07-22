(ns slopp.web.auth
  (:require [clojure.string :as str] [clojure.edn :as edn] [cheshire.core :as json]))

(defn sha256-hex
  "SHA-256 of `s` as lowercase hex — the v1 password hash for the static
  provider (JDK-only; swap for bcrypt/argon2 when a store's threat model
  demands it — the config carries a hash either way)."
  [s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn- secret-value
  "Resolve a configured secret: \"env:NAME\" reads through `getenv` (a map
  or fn — the seam that keeps resolution testable and the config free of
  literals); anything else is the value itself."
  [v getenv]
  (let [v (str v)]
    (if (str/starts-with? v "env:")
      (some-> (getenv (subs v 4)) str)
      v)))

(defn- augment-groups
  "The identity's groups plus every configured group (`:auth/groups`,
  {group-name #{subs}}) that lists the subject."
  [{:web/keys [sub] :as identity} groups-config]
  (update identity :web/groups
          (fnil into #{})
          (for [[g members] groups-config
                :when (contains? (set members) sub)]
            g)))

(defn- bearer-identity
  "Authorization: Bearer <token> against `:auth/bearer` ({name {:secret
  :groups}}), secrets env-indirect. nil = no claim."
  [config req {:keys [getenv]}]
  (let [h (get-in req [:headers "authorization"] "")]
    (when (str/starts-with? h "Bearer ")
      (let [tok (subs h 7)]
        (some (fn [[nm {:keys [secret groups]}]]
                (when (and (seq tok) (= tok (secret-value secret getenv)))
                  {:web/sub nm :web/groups (set groups)
                   :web/provider :bearer}))
              (:auth/bearer config))))))

(defn- static-identity
  "Authorization: Basic base64(user:pass) against `:auth/static`
  ({user {:password-hash :groups}}). nil = no claim."
  [config req _opts]
  (let [h (get-in req [:headers "authorization"] "")]
    (when (str/starts-with? h "Basic ")
      (let [decoded (try (String. (.decode (java.util.Base64/getDecoder)
                                           (subs h 6)) "UTF-8")
                         (catch Exception _ nil))
            [user pass] (when decoded (str/split decoded #":" 2))
            {:keys [password-hash groups]} (get (:auth/static config) user)]
        (when (and password-hash pass
                   (= password-hash (sha256-hex pass)))
          {:web/sub user :web/groups (set groups)
           :web/provider :static})))))

(defn- proxy-identity
  "Identity headers from a TRUSTED upstream only (`:auth/proxy` {:trusted
  #{addrs} :user-header :groups-header}): the cheapest bridge to any
  external identity system, safe exactly because trust is pinned to the
  remote address. nil = no claim."
  [config req _opts]
  (let [{:keys [trusted user-header groups-header]} (:auth/proxy config)
        user (get-in req [:headers (str user-header)])]
    (when (and (contains? (set trusted) (:remote-addr req))
               (seq (str user)))
      {:web/sub (str user)
       :web/groups (into #{}
                         (remove str/blank?)
                         (str/split (str (get-in req [:headers (str groups-header)] ""))
                                    #","))
       :web/provider :proxy-header})))

(defn ^:export config-from-values
  "Parse the `capabilities` config's {key value} STRINGS into the runtime
  auth config `{:auth/providers [...] :auth/bearer {...} :auth/static {...}
  :auth/proxy {...} :auth/groups {...}}` — ONE parser, used by slopp's own
  serving (store values) and by a built app (the rendered capabilities
  file). Entry values are EDN, read with the SAFE reader; unparseable
  entries are skipped rather than thrown."
  [values]
  (let [edn* (fn [s] (try (edn/read-string (str s))
                          (catch Exception _ nil)))
        csv  (fn [s] (into [] (remove str/blank?) (map str/trim (str/split (str s) #","))))]
    (reduce-kv
     (fn [cfg k v]
       (let [k (str k)]
         (cond
           (= k "auth.providers")
           (assoc cfg :auth/providers (mapv keyword (csv v)))

           (str/starts-with? k "auth.bearer.tokens.")
           (assoc-in cfg [:auth/bearer (subs k 19)] (edn* v))

           (str/starts-with? k "auth.static.users.")
           (assoc-in cfg [:auth/static (subs k 18)] (edn* v))

           (= k "auth.proxy.trusted")
           (assoc-in cfg [:auth/proxy :trusted] (set (csv v)))

           (= k "auth.proxy.user-header")
           (assoc-in cfg [:auth/proxy :user-header] (str v))

           (= k "auth.proxy.groups-header")
           (assoc-in cfg [:auth/proxy :groups-header] (str v))

           (= k "auth.oidc.issuer")
           (assoc-in cfg [:auth/oidc :issuer] (str v))

           (= k "auth.oidc.audience")
           (assoc-in cfg [:auth/oidc :audience] (str v))

           (= k "auth.oidc.groups-claim")
           (assoc-in cfg [:auth/oidc :groups-claim] (str v))

           :else
           (if-let [[_ g] (re-matches #"groups\.([^.]+)\.members" k)]
             (assoc-in cfg [:auth/groups g] (set (csv v)))
             cfg))))
     {}
     values)))

(defn- b64url-bytes
  "Base64url-decode `s` (padding optional), nil on garbage."
  [s]
  (try (.decode (java.util.Base64/getUrlDecoder) (str s))
       (catch Exception _ nil)))

(defn- decode-jwt
  "Split a compact JWT into {:header :claims :signed-bytes :signature} —
  header/claims JSON-parsed (keywordized), :signed-bytes the raw
  `header.payload` UTF-8, :signature decoded. nil for anything malformed."
  [token]
  (let [parts (str/split (str token) #"\." 3)]
    (when (= 3 (count parts))
      (let [[h p s] parts
            parse (fn [seg] (some-> (b64url-bytes seg)
                                    (String. "UTF-8")
                                    (as-> t (try (json/parse-string t true)
                                                 (catch Exception _ nil)))))
            header (parse h)
            claims (parse p)
            sig    (b64url-bytes s)]
        (when (and (map? header) (map? claims) sig)
          {:header header :claims claims
           :signed-bytes (.getBytes (str h "." p) "UTF-8")
           :signature sig})))))

(defn- jwk->rsa-key
  "An RSAPublicKey from a JWK map's base64url :n/:e, nil when not RSA."
  [{:keys [n e] :as _jwk}]
  (try
    (let [nb (b64url-bytes n) eb (b64url-bytes e)]
      (when (and nb eb)
        (.generatePublic (java.security.KeyFactory/getInstance "RSA")
                         (java.security.spec.RSAPublicKeySpec.
                          (java.math.BigInteger. 1 ^bytes nb)
                          (java.math.BigInteger. 1 ^bytes eb)))))
    (catch Exception _ nil)))

(defn- verify-jwt
  "Verify a decoded JWT against `{:issuer :audience :jwks [jwk …]}` at
  `now` (epoch seconds): RS256 signature against the kid-matched JWK (any
  key when the header names none), then issuer, expiry, and audience when
  configured. Returns the CLAIMS or nil — never throws."
  [{:keys [header claims signed-bytes signature]} {:keys [issuer audience jwks]} now]
  (try
    (let [kid  (:kid header)
          keys* (if kid (filter #(= (str kid) (str (:kid %))) jwks) jwks)
          ok?  (and (= "RS256" (:alg header))
                    (some (fn [jwk]
                            (when-let [k (jwk->rsa-key jwk)]
                              (let [sig (doto (java.security.Signature/getInstance
                                               "SHA256withRSA")
                                          (.initVerify ^java.security.PublicKey k)
                                          (.update ^bytes signed-bytes))]
                                (.verify sig ^bytes signature))))
                          keys*))]
      (when (and ok?
                 (= (str issuer) (str (:iss claims)))
                 (number? (:exp claims))
                 (< (long now) (long (:exp claims)))
                 (or (nil? audience)
                     (let [aud (:aud claims)]
                       (if (coll? aud)
                         (some #(= (str audience) (str %)) aud)
                         (= (str audience) (str aud))))))
        claims))
    (catch Exception _ nil)))

(defn- oidc-identity
  "Authorization: Bearer <jwt> against `:auth/oidc` ({:issuer :audience
  :jwks :groups-claim}) — the RESOURCE-SERVER half of OIDC: validate the
  token an external IdP minted; the browser login flow stays the IdP's/a
  proxy's job. `(:now opts)` is the deterministic-time seam (default: the
  wall clock, seconds). nil = no claim."
  [config req opts]
  (let [h (get-in req [:headers "authorization"] "")]
    (when (str/starts-with? h "Bearer ")
      (let [oidc (:auth/oidc config)
            now  (or (:now opts) (quot (System/currentTimeMillis) 1000))]
        (when-let [claims (some-> (decode-jwt (subs h 7))
                                  (verify-jwt oidc now))]
          {:web/sub (str (or (:sub claims) (:preferred_username claims)))
           :web/groups (into #{}
                             (map str)
                             (get claims (keyword (or (:groups-claim oidc)
                                                      "groups"))))
           :web/provider :oidc})))))

(defn ^:export resolve-identity
  "Resolve a request into `{:web/sub :web/groups :web/provider}` or nil
  (anonymous — the policy layer's default-deny takes it from there). Walks
  `:auth/providers` in declared order; the FIRST provider claiming the
  request wins; configured group membership (`:auth/groups`) augments
  whatever the provider asserted. Seams: `:getenv` (map or fn, default
  System/getenv) resolves env-indirect secrets; `:now` (epoch seconds,
  default the wall clock) is OIDC expiry's deterministic-time hook."
  [config req & {:keys [getenv now] :or {getenv #(System/getenv %)}}]
  (let [opts {:getenv getenv :now now}
        provider-fn {:bearer bearer-identity
                     :static static-identity
                     :proxy-header proxy-identity
                     :oidc oidc-identity}]
    (some-> (some (fn [p]
                    (when-let [f (provider-fn p)]
                      (f config req opts)))
                  (:auth/providers config))
            (augment-groups (:auth/groups config)))))

(defn ^:export ^:unused-ok fetch-jwks!
  "Fetch the issuer's signing keys: GET
  <issuer>/.well-known/openid-configuration → its jwks_uri → the JWK set's
  :keys. The SERVER wiring calls this once at startup when :oidc is
  enabled and passes the result as the config's `:jwks`; tests inject
  static keys instead. Throws on network/parse failure — a misconfigured
  issuer should fail loudly at startup, not 401 mysteriously forever.
  ^:unused-ok: the slim jar's consumer surface — slopp's own store
  configures no OIDC, so no in-store caller exists by design."
  [issuer]
  (let [http (java.net.http.HttpClient/newHttpClient)
        GET  (fn [url]
               (json/parse-string
                (.body (.send http
                              (-> (java.net.http.HttpRequest/newBuilder)
                                  (.uri (java.net.URI/create (str url)))
                                  (.build))
                              (java.net.http.HttpResponse$BodyHandlers/ofString)))
                true))
        disco (GET (str issuer "/.well-known/openid-configuration"))]
    (vec (:keys (GET (:jwks_uri disco))))))
