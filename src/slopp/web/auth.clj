(ns slopp.web.auth
  "Identity POLICY: turn a request into `{:web/sub :web/groups :web/provider}`
  or nil, for each provider slopp ships — static users, bearer tokens, a
  trusted proxy header, and the resource-server half of OIDC.

  Everything here decides; nothing here fetches. That is the property worth
  protecting, and it is why the tier is `:internal`: `verify-jwt` is handed
  `:jwks` as data, `now` and `getenv` arrive as injected seams with real
  defaults, and the tests pass static keys rather than reaching an identity
  provider. `slopp.web.jwks` holds the one form that used to break it.

  `slopp.web.dispatch` is the caller — identity resolves BEFORE routing, so a
  policy decision is never made against a handler that already ran. Anonymous
  is nil rather than an error; default-deny at the policy layer is what turns
  that into a 401."
  (:require [clojure.string :as str] [clojure.edn :as edn] [cheshire.core :as json]))

(defn- pbkdf2
  "PBKDF2WithHmacSHA256 of `password` with `salt` bytes over `iterations`,
  256-bit output — JDK-only, native-image safe, deterministic."
  [password ^bytes salt iterations]
  (let [spec (javax.crypto.spec.PBEKeySpec.
              (.toCharArray (str password)) salt (int iterations) 256)
        skf  (javax.crypto.SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")]
    (.getEncoded (.generateSecret skf spec))))

(defn verify-password
  "True if `password` matches the encoded `stored` PBKDF2 hash
  (`pbkdf2$<iterations>$<salt-b64>$<hash-b64>`): parse, recompute, and
  compare in CONSTANT TIME (MessageDigest/isEqual). A malformed or nil
  `stored` returns false, never throws (review W6)."
  [password stored]
  (boolean
   (try
     (let [[algo iters salt-b64 hash-b64] (str/split (str stored) #"\$")]
       (when (and (= "pbkdf2" algo) salt-b64 hash-b64)
         (let [dec  (java.util.Base64/getDecoder)
               salt (.decode dec ^String salt-b64)
               want (.decode dec ^String hash-b64)
               got  (pbkdf2 password salt (Long/parseLong iters))]
           (java.security.MessageDigest/isEqual want got))))
     (catch Exception _ false))))

(def ^:private pbkdf2-iterations 210000)

(defn hash-password
  "A salted, iterated PBKDF2 hash of `password`, encoded
  `pbkdf2$<iterations>$<salt-b64>$<hash-b64>` — the static provider's stored
  password-hash, generated with a fresh random 16-byte salt per call. The
  capabilities config is git-projected, so the stored hash must resist
  offline cracking; unsalted SHA-256 did not (review W6)."
  [password]
  (let [salt (byte-array 16)
        _    (.nextBytes (java.security.SecureRandom.) salt)
        enc  (.withoutPadding (java.util.Base64/getEncoder))
        hash (pbkdf2 password salt pbkdf2-iterations)]
    (str "pbkdf2$" pbkdf2-iterations "$"
         (.encodeToString enc salt) "$"
         (.encodeToString enc hash))))

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
  :groups}}), secrets env-indirect. nil = no claim. The token compare is
  CONSTANT TIME (MessageDigest/isEqual) so a byte-by-byte timing side
  channel can't recover the secret (review W6)."
  [config req {:keys [getenv]}]
  (let [h (get-in req [:headers "authorization"] "")]
    (when (str/starts-with? h "Bearer ")
      (let [tok (subs h 7)]
        (some (fn [[nm {:keys [secret groups]}]]
                (when (and (seq tok)
                           (java.security.MessageDigest/isEqual
                            (.getBytes tok "UTF-8")
                            (.getBytes (str (secret-value secret getenv)) "UTF-8")))
                  {:web/sub nm :web/groups (set groups)
                   :web/provider :bearer}))
              (:auth/bearer config))))))

(defn- static-identity
  "Authorization: Basic base64(user:pass) against `:auth/static`
  ({user {:password-hash :groups}}). nil = no claim. The stored
  password-hash is a salted PBKDF2 digest, verified in constant time
  (review W6)."
  [config req _opts]
  (let [h (get-in req [:headers "authorization"] "")]
    (when (str/starts-with? h "Basic ")
      (let [decoded (try (String. (.decode (java.util.Base64/getDecoder)
                                           (subs h 6)) "UTF-8")
                         (catch Exception _ nil))
            [user pass] (when decoded (str/split decoded #":" 2))
            {:keys [password-hash groups]} (get (:auth/static config) user)]
        (when (and password-hash pass (verify-password pass password-hash))
          {:web/sub user :web/groups (set groups)
           :web/provider :static})))))

(defn- proxy-identity
  "Identity headers from a TRUSTED upstream only (`:auth/proxy` {:trusted
  #{addrs} :user-header :groups-header}): the cheapest bridge to any
  external identity system, safe exactly because trust is pinned to the
  remote address. nil = no claim. Header names are lowercased at lookup —
  adapters normalize request header keys to lowercase, so a canonically-
  cased config (`X-Forwarded-User`) must still match (review W7)."
  [config req _opts]
  (let [{:keys [trusted user-header groups-header]} (:auth/proxy config)
        hdr  (fn [h] (when h (get-in req [:headers (str/lower-case (str h))])))
        user (hdr user-header)]
    (when (and (contains? (set trusted) (:remote-addr req))
               (seq (str user)))
      {:web/sub (str user)
       :web/groups (into #{}
                         (remove str/blank?)
                         (str/split (str (or (hdr groups-header) ""))
                                    #","))
       :web/provider :proxy-header})))

(defn ^:export config-from-values
  "Parse the `capabilities` config's {key value} STRINGS into the runtime
  auth config `{:auth/providers [...] :auth/bearer {...} :auth/static {...}
  :auth/proxy {...} :auth/groups {...}}` — ONE parser, used by slopp's own
  serving (store values) and by a built app (the rendered capabilities
  file). Entry values are EDN, read with the SAFE reader; unparseable
  entries are skipped rather than thrown.

  Keys are the `web.auth.*` family (`slopp.api.capabilities/registry` is
  where they are declared and typed). A per-provider name is the tail AFTER
  the prefix, taken from the prefix itself — the counted offset it replaces
  was the same defect as matching by one spelling and trimming by another's
  length, and it survives a rename only by accident."
  [values]
  (let [edn* (fn [s] (try (edn/read-string (str s))
                          (catch Exception _ nil)))
        csv  (fn [s] (into [] (remove str/blank?) (map str/trim (str/split (str s) #","))))
        tail (fn [prefix k] (when (str/starts-with? k prefix) (subs k (count prefix))))]
    (reduce-kv
     (fn [cfg k v]
       (let [k (str k)]
         (cond
           (= k "web.auth.providers")
           (assoc cfg :auth/providers (mapv keyword (csv v)))

           (tail "web.auth.bearer.tokens." k)
           (assoc-in cfg [:auth/bearer (tail "web.auth.bearer.tokens." k)] (edn* v))

           (tail "web.auth.static.users." k)
           (assoc-in cfg [:auth/static (tail "web.auth.static.users." k)] (edn* v))

           (= k "web.auth.proxy.trusted")
           (assoc-in cfg [:auth/proxy :trusted] (set (csv v)))

           (= k "web.auth.proxy.user-header")
           (assoc-in cfg [:auth/proxy :user-header] (str v))

           (= k "web.auth.proxy.groups-header")
           (assoc-in cfg [:auth/proxy :groups-header] (str v))

           (= k "web.auth.oidc.issuer")
           (assoc-in cfg [:auth/oidc :issuer] (str v))

           (= k "web.auth.oidc.audience")
           (assoc-in cfg [:auth/oidc :audience] (str v))

           (= k "web.auth.oidc.groups-claim")
           (assoc-in cfg [:auth/oidc :groups-claim] (str v))

           :else
           (if-let [[_ g] (re-matches #"web\.auth\.groups\.([^.]+)\.members" k)]
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
  key when the header names none), then issuer, expiry, and AUDIENCE.
  Audience validation is MANDATORY (a resource server must reject tokens
  minted for another client): an unconfigured `:audience` denies every
  token, a configured one must match the token's `:aud` (review W2).
  Returns the CLAIMS or nil — never throws."
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
                 ;; unset audience → deny (never accept an unscoped token);
                 ;; set → the token's aud (scalar or array) must contain it
                 (seq (str audience))
                 (let [aud (:aud claims)]
                   (if (coll? aud)
                     (some #(= (str audience) (str %)) aud)
                     (= (str audience) (str aud)))))
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
